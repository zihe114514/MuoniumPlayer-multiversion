package com.muoniumplayer.core.ncm.music;

import java.util.ArrayList;
import java.util.List;

/**
 * Beat This! ONNX 推理的 Java 移植（对照 {@code muonium/automix/folia_beat_this.py}）。
 *
 * <p>输入是已重采样到 22050Hz 的单声道；输出与 Python 路径完全同形——
 * 秒为单位的节拍/强拍时间轴。链路：mel 前端（{@link FoliaMelFrontend}）→
 * 1500 帧分块推理（边界 6 帧零填充）→ keep-first 聚合 → 七帧正峰值 → 一帧去重 →
 * 强拍吸附到最近的节拍。
 */
final class FoliaBeatOnnx {

    private static final int CHUNK = 1500;
    private static final int BORDER = 6;
    private static final int INTRA_THREADS = 4;

    private FoliaBeatOnnx() {}

    /** 返回 [2][n]：{beats, downbeats}，单位秒、保留 4 位小数；模型不可用时返回 null。 */
    static double[][] analyse(float[] mono) {
        long startedAt = System.nanoTime();
        float[][] mel = FoliaMelFrontend.spectrogram(mono);
        if (mel.length <= 0) return null;
        Object session = null;
        try {
            session = FoliaOnnx.createSession(
                    FoliaModelManager.path(FoliaModelManager.Kind.BEAT_THIS).getAbsolutePath(),
                    false, INTRA_THREADS);
            String inputName = FoliaOnnx.inputName(session);
            int outputCount = FoliaOnnx.outputCount(session);
            int beatIndex = 0, downbeatIndex = 1;
            if (outputCount >= 2) {
                for (int index = 0; index < outputCount; index++) {
                    String name = FoliaOnnx.outputName(session, index).toLowerCase(java.util.Locale.ROOT);
                    if (name.contains("beat") && !name.contains("down")) beatIndex = index;
                    else if (name.contains("down")) downbeatIndex = index;
                }
            }
            List<Chunk> predictions = new ArrayList<>();
            for (int[] chunkSpec : chunks(mel.length)) {
                int start = chunkSpec[0], count = chunkSpec[1];
                float[] input = new float[count * FoliaMelFrontend.N_MELS];
                for (int frame = 0; frame < count; frame++) {
                    int source = Math.min(mel.length - 1, Math.max(0, start + frame));
                    System.arraycopy(mel[source], 0, input, frame * FoliaMelFrontend.N_MELS,
                            FoliaMelFrontend.N_MELS);
                }
                long[] tensorShape = new long[] {1, count, FoliaMelFrontend.N_MELS};
                Object tensor = FoliaOnnx.tensor(input, tensorShape);
                Object[] outputs = FoliaOnnx.run(session, inputName, tensor);
                predictions.add(new Chunk(start, count,
                        FoliaOnnx.flatten(outputs[Math.min(beatIndex, outputs.length - 1)]),
                        FoliaOnnx.flatten(outputs[Math.min(downbeatIndex, outputs.length - 1)])));
            }

            float[] beatLogits = new float[mel.length];
            float[] downbeatLogits = new float[mel.length];
            java.util.Arrays.fill(beatLogits, -1000.0f);
            java.util.Arrays.fill(downbeatLogits, -1000.0f);
            // 参考实现是 keep-first：倒序写入，让最早的分块在重叠区说了算。
            for (int p = predictions.size() - 1; p >= 0; p--) {
                Chunk chunk = predictions.get(p);
                boolean wide = chunk.count >= 2 * BORDER;
                int left = wide ? BORDER : 0;
                int right = wide ? chunk.count - BORDER : chunk.count;
                for (int offset = left; offset < right; offset++) {
                    int target = chunk.start + offset;
                    if (target >= 0 && target < mel.length) {
                        beatLogits[target] = chunk.beat[offset];
                        downbeatLogits[target] = chunk.downbeat[offset];
                    }
                }
            }

            double fps = (double) FoliaMelFrontend.SR / FoliaMelFrontend.HOP;
            double[] beats = toSeconds(pickPeaks(beatLogits), fps);
            double[] downbeats = toSeconds(pickPeaks(downbeatLogits), fps);
            if (beats.length > 0 && downbeats.length > 0) {
                java.util.TreeSet<Double> snapped = new java.util.TreeSet<>();
                for (double downbeat : downbeats) {
                    double nearest = beats[0];
                    for (double beat : beats) {
                        if (Math.abs(beat - downbeat) < Math.abs(nearest - downbeat)) nearest = beat;
                    }
                    snapped.add(nearest);
                }
                downbeats = snapped.stream().mapToDouble(Double::doubleValue).toArray();
            } else if (beats.length == 0) {
                downbeats = new double[0];
            }
            System.out.println("[Automix/Folia] Beat This ONNX: " + mel.length + " 帧 mel、"
                    + beats.length + " 拍 / " + downbeats.length + " 强拍，"
                    + (System.nanoTime() - startedAt) / 1_000_000L + "ms");
            return new double[][] {beats, downbeats};
        } catch (Throwable failure) {
            System.err.println("[Automix/Folia] Beat This ONNX 推理失败: " + failure.getMessage());
            return null;
        } finally {
            if (session != null) FoliaOnnx.closeSession(session);
        }
    }

    private record Chunk(int start, int count, float[] beat, float[] downbeat) {}

    /**
     * 分块起点。对照 Python：stride = CHUNK - 2·BORDER，起点从 -BORDER 起步；
     * 帧数超过一个 stride 时，最后一块被钉到「尾部恰好补到 frames」的位置。
     */
    static List<int[]> chunks(int frames) {
        int stride = CHUNK - 2 * BORDER;
        List<Integer> starts = new ArrayList<>();
        for (int start = -BORDER; start < frames - BORDER; start += stride) {
            starts.add(start);
        }
        if (starts.isEmpty()) starts.add(-BORDER);
        if (frames > stride) {
            starts.set(starts.size() - 1, frames - (CHUNK - BORDER));
        }
        List<int[]> result = new ArrayList<>();
        for (int start : starts) {
            int sourceFrom = Math.max(0, start);
            int sourceTo = Math.min(start + CHUNK, frames);
            int left = Math.max(0, -start);
            int right = Math.min(BORDER, start + CHUNK - frames);
            int count = left + Math.max(0, sourceTo - sourceFrom) + right;
            result.add(new int[] {start, Math.max(0, Math.min(count, CHUNK))});
        }
        return result;
    }

    /** Beat This! 的正七帧局部极大（严格比较），随后一帧宽度去重。 */
    static List<Integer> pickPeaks(float[] logits) {
        List<Integer> peaks = new ArrayList<>();
        for (int index = 0; index < logits.length; index++) {
            float value = logits[index];
            if (!(value > 0.0f)) continue;
            int lo = Math.max(0, index - 3), hi = Math.min(logits.length, index + 4);
            boolean beaten = false;
            for (int i = lo; i < hi; i++) {
                if (logits[i] > value) { beaten = true; break; }
            }
            if (!beaten) peaks.add(index);
        }
        return deduplicate(peaks, 1);
    }

    static List<Integer> deduplicate(List<Integer> peaks, int width) {
        List<Integer> merged = new ArrayList<>();
        if (peaks.isEmpty()) return merged;
        double mean = peaks.get(0);
        int count = 1;
        for (int i = 1; i < peaks.size(); i++) {
            double peak = peaks.get(i);
            if (peak - mean <= width) {
                count++;
                mean += (peak - mean) / count;
            } else {
                merged.add((int) Math.round(mean));
                mean = peak;
                count = 1;
            }
        }
        merged.add((int) Math.round(mean));
        return merged;
    }

    private static double[] toSeconds(List<Integer> frames, double fps) {
        double[] seconds = new double[frames.size()];
        for (int i = 0; i < seconds.length; i++) {
            seconds[i] = round4(frames.get(i) / fps);
        }
        return seconds;
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
