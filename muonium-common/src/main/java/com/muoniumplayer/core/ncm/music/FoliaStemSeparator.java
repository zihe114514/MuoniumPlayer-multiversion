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
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/** HTDemucs ONNX 适配器。它把繁重的分离保留在下载的 Folia Python 运行时中。 */
final class FoliaStemSeparator {
    /** 模型只认这个采样率；素材缓存也按它落盘，两处必须是同一个数（{@link FoliaStemProfile#MATERIAL_RATE}）。 */
    private static final int MODEL_RATE = FoliaStemProfile.MATERIAL_RATE;
    /** 素材缓存的编码宽度：模型输出 float32，逐字节写盘，没有头。 */
    private static final int FLOAT_BYTES = 4;
    private static final long TIMEOUT_SECONDS = 180L;

    private FoliaStemSeparator() {}

    static FoliaStemProfile analyse(File source, long startMillis, long lengthMillis,
                                    AnalysisAudio audio, boolean outgoing, File material) {
        if (source == null || audio == null || lengthMillis <= 0L
                || !FoliaModelManager.isInstalled(FoliaModelManager.Kind.HTDEMUCS)
                || !FoliaModelManager.isInstalled(FoliaModelManager.Kind.RUNTIME)) return null;
        File input = null, output = null, script = null;
        File materialPart = material == null ? null : new File(material.getAbsolutePath() + ".part");
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
                    FoliaModelManager.path(FoliaModelManager.Kind.HTDEMUCS).getAbsolutePath(),
                    materialPart == null ? "-" : materialPart.getAbsolutePath())
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
            // 老 profile 没这个键：number() 还 -1，于是落在可信区间之外，素材这一路自动不启用，
            // 时间戳证据照旧。不必为此作废整份缓存、把每首歌重新推理一遍。
            profile.vocalProjection = number(root, "vocal_projection");
            profile.materialFrames = longOr(root, "samples", -1L);
            if (!profile.isUsable()) {
                delete(materialPart);
                return null;
            }
            if (materialPart == null) {
                profile.materialFrames = 0L;              // 出场侧只要时间戳，没有素材可发布
            } else if (!publishMaterial(materialPart, material, profile)) {
                delete(materialPart);
                profile.materialFrames = 0L;
            }
            return profile;
        } catch (Throwable failure) {
            System.err.println("[Automix/Folia] HTDemucs unavailable: " + failure.getMessage());
            delete(materialPart);
            return null;
        } finally {
            delete(input);
            delete(output);
            delete(script);
        }
    }

    /**
     * 把模型写好的素材从 {@code .part} 落成正式缓存文件。
     *
     * <p>先量长度再换名，是因为这份缓存的正确性完全建立在「素材第 0 帧 = 窗口第 0 帧」上：
     * 半截文件（进程被超时杀在写的中途、磁盘满）一旦当成好的用下去，交接期减掉的就是错位的人声，
     * 听起来是伴奏被啃掉一块，比没有素材严重得多。缓存头里记的是帧数，按 float32 折成字节校验。</p>
     */
    private static boolean publishMaterial(File part, File target, FoliaStemProfile profile) {
        try {
            if (!profile.hasVocalMaterial()) return false;
            if (!part.isFile() || part.length() != profile.materialFrames * FLOAT_BYTES) return false;
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return target.isFile();
        } catch (Throwable failure) {
            System.err.println("[Automix/Folia] 人声素材落盘失败: " + failure.getMessage());
            return false;
        }
    }

    private static long longOr(JsonObject root, String name, long missing) {
        return root.has(name) && !root.get(name).isJsonNull() ? root.get(name).getAsLong() : missing;
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