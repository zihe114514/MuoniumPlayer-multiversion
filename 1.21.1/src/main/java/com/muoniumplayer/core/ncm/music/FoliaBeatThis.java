package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
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
import java.util.Arrays;

/** Beat This! ONNX adapter. It runs in the downloaded Folia Python runtime. */
final class FoliaBeatThis {
    private static final int MODEL_RATE = 22_050;

    private FoliaBeatThis() {}

    static AutomixAnalyzer.BeatGrid analyse(float[] mono, int sampleRate, boolean outgoing) {
        if (mono == null || mono.length < MODEL_RATE || sampleRate <= 0) return null;
        if (!FoliaModelManager.isInstalled(FoliaModelManager.Kind.BEAT_THIS)
                || !FoliaModelManager.isInstalled(FoliaModelManager.Kind.RUNTIME)) return null;
        File input = null, output = null, script = null;
        try {
            input = File.createTempFile("folia-beat-", ".raw");
            output = File.createTempFile("folia-beat-", ".json");
            float[] resampled = resample(mono, sampleRate, MODEL_RATE);
            ByteBuffer bytes = ByteBuffer.allocate(resampled.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (float value : resampled) bytes.putFloat(value);
            Files.write(input.toPath(), bytes.array());
            script = materialiseScript();
            Process process = new ProcessBuilder(FoliaModelManager.pythonExecutable().getAbsolutePath(),
                    script.getAbsolutePath(), input.getAbsolutePath(),
                    FoliaModelManager.path(FoliaModelManager.Kind.BEAT_THIS).getAbsolutePath(),
                    output.getAbsolutePath()).redirectErrorStream(true).start();
            boolean exited = process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                readAll(process.getInputStream());
                System.err.println("[Automix/Folia] Beat This timed out");
                return null;
            }
            byte[] log = readAll(process.getInputStream());
            if (process.exitValue() != 0) {
                System.err.println("[Automix/Folia] Beat This failed: " + new String(log, StandardCharsets.UTF_8));
                return null;
            }
            JsonObject root = JsonParser.parseString(new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
            double[] beats = numbers(root.getAsJsonArray("beats"));
            double[] downbeats = numbers(root.getAsJsonArray("downbeats"));
            return grid(beats, downbeats, outgoing);
        } catch (Throwable failure) {
            System.err.println("[Automix/Folia] Beat This unavailable: " + failure.getMessage());
            return null;
        } finally {
            delete(input); delete(output); delete(script);
        }
    }

    private static AutomixAnalyzer.BeatGrid grid(double[] beats, double[] downbeats, boolean outgoing) {
        if (beats.length < 8) return null;
        double[] intervals = new double[beats.length - 1];
        for (int i = 1; i < beats.length; i++) intervals[i - 1] = beats[i] - beats[i - 1];
        Arrays.sort(intervals);
        double period = intervals[intervals.length / 2];
        if (!(period > .18) || !(period < 2.0)) return null;
        double mean = 0.0; for (double interval : intervals) mean += interval; mean /= intervals.length;
        double variance = 0.0; for (double interval : intervals) variance += (interval - mean) * (interval - mean);
        double tolerance = Math.sqrt(variance / intervals.length) / period;
        float confidence = (float) Math.max(.2, Math.min(1.0, 1.0 - tolerance * 8.0));
        double phase = positive(beats[beats.length - 1], period) * 1000.0;
        double barBeat = downbeats.length > 0 ? downbeats[outgoing ? downbeats.length - 1 : 0] : beats[beats.length - 1];
        double bar = positive(barBeat, period * 4.0) * 1000.0;
        return new AutomixAnalyzer.BeatGrid((float) (60.0 / period), (float) phase, (float) bar,
                confidence, (float) Math.max(.003, Math.min(.05, tolerance)));
    }

    private static double positive(double value, double period) {
        double out = value % period; return out < 0.0 ? out + period : out;
    }

    private static double[] numbers(JsonArray array) {
        double[] values = new double[array == null ? 0 : array.size()];
        for (int i = 0; i < values.length; i++) values[i] = array.get(i).getAsDouble();
        return values;
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
        File file = File.createTempFile("folia-beat-this-", ".py");
        try (InputStream input = FoliaBeatThis.class.getResourceAsStream("/muonium/automix/folia_beat_this.py");
             FileOutputStream output = new FileOutputStream(file)) {
            if (input == null) throw new IllegalStateException("missing beat runner resource");
            byte[] buffer = new byte[8192]; int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        }
        return file;
    }

    private static byte[] readAll(InputStream input) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int read;
        while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private static void delete(File file) { if (file != null) try { Files.deleteIfExists(file.toPath()); } catch (Throwable ignored) {} }
}
