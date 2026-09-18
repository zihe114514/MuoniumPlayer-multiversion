package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/** HTDemucs ONNX 适配器。它把繁重的分离保留在下载的 Folia Python 运行时中。 */
final class FoliaStemSeparator {
    private static final int MODEL_RATE = 44_100;
    private static final long TIMEOUT_SECONDS = 180L;

    private FoliaStemSeparator() {}

    static FoliaStemProfile analyse(File source, long startMillis, long lengthMillis,
                                    AnalysisAudio audio, boolean outgoing) {
        if (source == null || audio == null || lengthMillis <= 0L
                || !FoliaModelManager.isInstalled(FoliaModelManager.Kind.HTDEMUCS)
                || !FoliaModelManager.isInstalled(FoliaModelManager.Kind.RUNTIME)) return null;
        File input = null, output = null, script = null;
        try {
            float[] mono = audio.readMono(startMillis, lengthMillis);
            if (mono.length < MODEL_RATE / 2) return null;
            float[] samples = resample(mono, audio.sampleRate(), MODEL_RATE);
            input = File.createTempFile("folia-stems-", ".f32");
            output = File.createTempFile("folia-stems-", ".json");
            ByteBuffer bytes = ByteBuffer.allocate(samples.length * 8).order(ByteOrder.LITTLE_ENDIAN);
            // 模型是立体声的。分析输入为单声道，因此两侧携带相同的证据；
            // 这保留了中人声分离效果，而不会假装凭空构建立体声影像。
            for (float value : samples) bytes.putFloat(value);
            for (float value : samples) bytes.putFloat(value);
            Files.write(input.toPath(), bytes.array());
            script = materialiseScript();
            Process process = new ProcessBuilder(FoliaModelManager.pythonExecutable().getAbsolutePath(),
                    script.getAbsolutePath(), input.getAbsolutePath(), output.getAbsolutePath(),
                    FoliaModelManager.path(FoliaModelManager.Kind.HTDEMUCS).getAbsolutePath())
                    .redirectErrorStream(true).start();
            boolean exited = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                process.waitFor(5L, TimeUnit.SECONDS);
                readAll(process.getInputStream());
                System.err.println("[Automix/Folia] HTDemucs timed out after " + TIMEOUT_SECONDS + "s");
                return null;
            }
            byte[] log = readAll(process.getInputStream());
            if (process.exitValue() != 0) {
                System.err.println("[Automix/Folia] HTDemucs failed: "
                        + new String(log, StandardCharsets.UTF_8));
                return null;
            }
            JsonObject root = JsonParser.parseString(new String(Files.readAllBytes(output.toPath()),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            FoliaStemProfile profile = new FoliaStemProfile();
            profile.windowStartMillis = startMillis;
            profile.windowLengthMillis = lengthMillis;
            profile.vocalStartMillis = absolute(startMillis, root, "vocal_start");
            profile.vocalEndMillis = absolute(startMillis, root, "vocal_end");
            profile.sustainedVocalEndMillis = absolute(startMillis, root, "sustained_vocal_end");
            profile.drumEndMillis = absolute(startMillis, root, "drum_end");
            profile.bassEndMillis = absolute(startMillis, root, "bass_end");
            profile.confidence = number(root, "confidence");
            return profile.isUsable() ? profile : null;
        } catch (Throwable failure) {
            System.err.println("[Automix/Folia] HTDemucs unavailable: " + failure.getMessage());
            return null;
        } finally {
            delete(input);
            delete(output);
            delete(script);
        }
    }

    private static long absolute(long origin, JsonObject root, String name) {
        float value = number(root, name);
        return value < 0f ? -1L : origin + Math.round(value * 1000f);
    }

    private static float number(JsonObject root, String name) {
        return root.has(name) && !root.get(name).isJsonNull() ? root.get(name).getAsFloat() : -1f;
    }

    private static float[] resample(float[] input, int from, int to) {
        if (from == to) return input;
        int length = Math.max(1, (int) Math.round(input.length * (double) to / from));
        float[] output = new float[length];
        for (int i = 0; i < length; i++) {
            double position = i * (double) from / to;
            int a = Math.min(input.length - 1, (int) position);
            int b = Math.min(input.length - 1, a + 1);
            double t = position - a;
            output[i] = (float) (input[a] * (1.0 - t) + input[b] * t);
        }
        return output;
    }

    private static File materialiseScript() throws Exception {
        File file = File.createTempFile("folia-htdemucs-", ".py");
        try (InputStream input = FoliaStemSeparator.class.getResourceAsStream(
                "/muonium/automix/folia_htdemucs.py");
             FileOutputStream output = new FileOutputStream(file)) {
            if (input == null) throw new IllegalStateException("missing HTDemucs runner resource");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        }
        return file;
    }

    private static byte[] readAll(InputStream input) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private static void delete(File file) {
        if (file != null) try { Files.deleteIfExists(file.toPath()); } catch (Throwable ignored) {}
    }
}