package com.muoniumplayer.core.ncm.music;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * HTDemucs ONNX 推理与统计的 Java 移植（对照 {@code muonium/automix/folia_htdemucs.py}）。
 *
 * <p>输入是 44100Hz 的单声道窗口（左右两侧携带同一份证据——分离模型是立体声的，
 * 但单声道复用两声道具保留中置人声效果，不会假装凭空构建立体声影像）。
 * 输出与 Python 路径同形：时间戳证据（人声/鼓/贝斯的起止、持续人声尾部、
 * 置信度、人声投影系数），外加可选的单声道人声素材 float32。
 *
 * <h2>手机上的资源约束</h2>
 * 这是整条 automix 链里最贵的一步（108 MB 的模型、30 秒窗口分若干个 chunk 做卷积）。
 * 三处刻意的让步都收在 {@link FoliaInferenceBudget} 里：会话复用（建图只发生一次）、
 * 算子级线程数按核数收紧、每个 chunk 之间让出 CPU。数学部分与 Python 参考逐位对齐，
 * 只改「用多少资源算」而不改「算出什么」。
 */
final class FoliaStemOnnx {

    private static final int DRUMS = 0, BASS = 1, VOCALS = 3;
    private static final double CELL_SECONDS = 0.05;
    private static final int SAMPLE_RATE = 44_100;

    private FoliaStemOnnx() {}

    /**
     * 分离一个窗口并产出证据。{@code materialPart} 非 null 时把单声道人声素材
     * float32 写进去（帧位与输入窗口逐帧对齐，交接期按位置从原曲减掉）。
     *
     * <p>会话走 {@link FoliaOnnx#session} 复用：同一个模型在整局游戏里只建一次图，
     * 每个窗口省下几百毫秒的建图尖峰。线程数由 {@link FoliaInferenceBudget} 按核数收紧，
     * 每个 chunk 之间让出 CPU；压低调用线程优先级只覆盖 {@code run} 外侧的那段 Java，
     * 不作用于 ORT 内部的 worker（理由见 {@link FoliaInferenceBudget} 的类注释）。
     *
     * @param mono 44100Hz 单声道窗口（调用方已重采样）
     */
    static FoliaStemProfile analyse(float[] mono, long startMillis, long lengthMillis,
                                    boolean writeMaterial, File materialPart) {
        if (mono == null || mono.length < 2) return null;
        long startedAt = System.nanoTime();
        File materialPartCopy = materialPart;
        try {
            Object session = FoliaOnnx.session(
                    FoliaModelManager.path(FoliaModelManager.Kind.HTDEMUCS).getAbsolutePath(),
                    true, FoliaInferenceBudget.intraOpThreads());
            String inputName = FoliaOnnx.inputName(session);
            long[] shape = FoliaOnnx.inputShape(session, inputName);
            if (shape.length < 3 || shape[2] <= 0) {
                throw new IllegalStateException("the HTDemucs export must have a static segment length");
            }
            int segment = (int) shape[2];
            int total = mono.length;
            // 声道的处理必须与 Python 参考实现逐位对齐（folia_htdemucs.py 的 separate/main）：
            // 分析输入是单声道，参考实现把它同时写进 left/right 两条声道送入模型，再把输出的
            // 两条声道取平均（`(stems["vocals"][0] + stems["vocals"][1]) * 0.5`）。
            //
            // 两侧喂同一份数据时，模型输出的两条声道在数值上就是同一个张量，所以「两条取平均」
            // 等价于「只取第一条」。旧代码两条都累加再除 2·weight，与本实现除 weight 得到的是
            // 同一个数；这里不再重复累加第二条，省掉一次 total 长度的乘加，但**输入仍按模型
            // 声明的声道数填满两条** —— 不能只喂一条、另一条留零，那会改变卷积的输入分布，
            // 分离结果与参考实现不再一致（模型是在立体声上训练的，零声道是有效信号）。
            int channels = Math.max(1, (int) shape[1]);

            // 分段推理 + 重叠相加。三支 stem（drums/bass/vocals）一次推理同时取齐——
            // htdemucs 的输出本来就是 [1,4,2,segment]，按 stem 分开跑只会翻倍算力。
            int overlap = segment / 4;
            int stride = segment - overlap;
            int chunks = Math.max(1, (total + stride - 1) / stride);
            float[][] output = new float[3][total];
            float[] weight = new float[total];
            float[] window = buildWindow(segment);
            float[] batch = new float[channels * segment];
            int[] indices = {DRUMS, BASS, VOCALS};
            for (int chunk = 0; chunk < chunks; chunk++) {
                int start = chunk * stride;
                int end = Math.min(start + segment, total);
                if (end <= start) break;
                int count = end - start;
                Arrays.fill(batch, 0.0f);
                // 每条声道都填同一份单声道数据 —— 与参考实现的 left/right 逐位一致。
                for (int channel = 0; channel < channels; channel++) {
                    int base = channel * segment;
                    for (int t = 0; t < count; t++) {
                        batch[base + t] = mono[start + t];
                    }
                }
                Object tensor = FoliaOnnx.tensor(batch, new long[] {1, channels, segment});
                Object[] outputs = FoliaOnnx.run(session, inputName, tensor);
                float[][][][] prediction;
                try {
                    prediction = (float[][][][]) outputs[0];
                } catch (ClassCastException rankMismatch) {
                    // 某些导出把 batch 维并进首维：降级成 [4][2][seg] 再包一层。
                    @SuppressWarnings("unchecked")
                    float[][][] flat = (float[][][]) outputs[0];
                    prediction = new float[][][][] {flat};
                }
                for (int s = 0; s < 3; s++) {
                    // 参考实现是 `(channels[0] + channels[1]) * 0.5`。两条声道拿到的是同一份
                    // 输入，输出因而逐位相同，(a + a)/2 == a，所以只取第一条就与参考一致，
                    // 而下面的归一化相应地只除 weight（参考的 max(weight, 1e-8) 是同一个式子）。
                    float[][] stemChannels = prediction[0][indices[s]];
                    float[] stem = stemChannels[0];
                    for (int t = 0; t < count; t++) {
                        output[s][start + t] += stem[t] * window[t];
                    }
                }
                for (int t = 0; t < count; t++) weight[start + t] += window[t];
                // 让出 CPU：每个 chunk 是几十到几百毫秒的连续占核，中间不留让步点
                // 会让渲染线程等满整个窗口。见 FoliaInferenceBudget.yieldToGame。
                FoliaInferenceBudget.yieldToGame();
            }
            for (int s = 0; s < 3; s++) {
                for (int t = 0; t < total; t++) {
                    output[s][t] /= Math.max(weight[t], 1e-8f);
                }
            }
            float[] drum = output[0];
            float[] bass = output[1];
            float[] vocal = output[2];

            int hop = (int) Math.round(SAMPLE_RATE * CELL_SECONDS);
            float[] mixEnv = envelope(mono, hop);
            float[] vocalEnv = envelope(vocal, hop);
            float[] drumEnv = envelope(drum, hop);
            float[] bassEnv = envelope(bass, hop);

            float vocalThreshold = activeThreshold(vocalEnv, mixEnv);
            float drumThreshold = activeThreshold(drumEnv, mixEnv);
            float bassThreshold = activeThreshold(bassEnv, mixEnv);
            int vocalStart = firstSustained(vocalEnv, vocalThreshold);
            int vocalEnd = lastActive(vocalEnv, vocalThreshold);
            if (vocalEnd >= 0) vocalEnd += (int) Math.round(0.30 / CELL_SECONDS);
            int drumEnd = lastActive(drumEnv, drumThreshold);
            int bassEnd = lastActive(bassEnv, bassThreshold);
            int release = sustainEnd(vocalEnv, vocalThreshold);

            double mixEnergy = sumSquares(mono) + 1e-12;
            double stemEnergy = sumSquares(vocal) + sumSquares(drum) + sumSquares(bass);
            float confidence = (float) Math.max(0.0, Math.min(1.0, stemEnergy / mixEnergy));
            float projection = vocalProjection(mono, vocal, vocalEnv, vocalThreshold, hop);

            FoliaStemProfile profile = new FoliaStemProfile();
            profile.windowStartMillis = startMillis;
            profile.windowLengthMillis = lengthMillis;
            profile.vocalStartMillis = secondsMillis(startMillis, vocalStart);
            profile.vocalEndMillis = secondsMillis(startMillis, vocalEnd);
            profile.sustainedVocalEndMillis = secondsMillis(startMillis, release);
            profile.drumEndMillis = secondsMillis(startMillis, drumEnd);
            profile.bassEndMillis = secondsMillis(startMillis, bassEnd);
            profile.confidence = round5(confidence);
            profile.vocalProjection = round5(projection);
            profile.materialFrames = 0L;

            // 人声素材：交接期把这一段按位置从进来的歌里减掉，让它先只剩伴奏。
            // 只在调用方给了路径时才写（出场侧只要时间戳）。
            if (writeMaterial && materialPartCopy != null) {
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(materialPartCopy))) {
                    ByteBuffer bytes = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);
                    for (float value : vocal) {
                        bytes.putFloat(value);
                        if (bytes.position() == bytes.capacity()) {
                            out.write(bytes.array());
                            bytes.clear();
                        }
                    }
                    if (bytes.position() > 0) out.write(bytes.array(), 0, bytes.position());
                } catch (Throwable failure) {
                    System.err.println("[Automix/Folia] 人声素材落盘失败: " + failure.getMessage());
                    materialPartCopy = null;
                }
                profile.materialFrames = materialPartCopy != null ? total : 0L;
            }
            System.out.println("[Automix/Folia] HTDemucs ONNX: segment=" + segment
                    + "、confidence=" + profile.confidence + "、vocal 投影=" + profile.vocalProjection
                    + "、素材=" + (profile.materialFrames > 0 ? profile.materialFrames + " 帧" : "未写")
                    + "，" + (System.nanoTime() - startedAt) / 1_000_000L + "ms");
            return profile;
        } catch (Throwable failure) {
            System.err.println("[Automix/Folia] HTDemucs ONNX 推理失败: " + failure.getMessage());
            return null;
        }
        // 不关会话：它由 FoliaOnnx.session 持有并复用。见那边的注释。
    }

    private static float round5(float value) {
        return Math.round(value * 100000f) / 100000f;
    }

    /** 与 Python 链路同秒数取整：先 round(cell·0.05, 4) 再折成窗口绝对毫秒。 */
    private static long secondsMillis(long origin, int cell) {
        if (cell < 0) return -1L;
        double seconds = Math.round(cell * CELL_SECONDS * 10000.0) / 10000.0;
        return origin + Math.round(seconds * 1000.0);
    }

    private static float[] buildWindow(int segment) {
        float[] window = new float[segment];
        Arrays.fill(window, 1.0f);
        int overlap = segment / 4;
        if (overlap > 1) {
            for (int i = 0; i < overlap; i++) {
                float ramp = (float) i / (overlap - 1);
                window[i] = ramp;
                window[segment - overlap + i] = ramp;
            }
        }
        return window;
    }

    /** 每 0.05s 一格的 RMS 包络（平方平均在 float64 里算，与 numpy 参考一致）。 */
    private static float[] envelope(float[] values, int hop) {
        int frames = values.length / hop;
        if (frames <= 0) return new float[0];
        float[] out = new float[frames];
        for (int frame = 0; frame < frames; frame++) {
            double sum = 0.0;
            int base = frame * hop;
            for (int i = 0; i < hop; i++) {
                double v = values[base + i];
                sum += v * v;
            }
            out[frame] = (float) Math.sqrt(sum / hop + 1e-12);
        }
        return out;
    }

    /** numpy percentile 的 linear 语义：rank = q/100·(n-1)，相邻两样本线性插值。 */
    static double percentile(float[] values, double q) {
        if (values.length == 0) return 0.0;
        float[] sorted = values.clone();
        Arrays.sort(sorted);
        double rank = q / 100.0 * (sorted.length - 1);
        int lower = (int) Math.floor(rank);
        int upper = Math.min(sorted.length - 1, lower + 1);
        double frac = rank - lower;
        return sorted[lower] + (sorted[upper] - sorted[lower]) * frac;
    }

    private static float activeThreshold(float[] level, float[] mix) {
        double peak = percentile(level, 95);
        double mixPeak = percentile(mix, 90);
        return (float) Math.max(1e-5, Math.max(peak * 0.075, mixPeak * 0.025));
    }

    private static int firstSustained(float[] level, float threshold) {
        int run = 0;
        for (int index = 0; index < level.length; index++) {
            if (level[index] >= threshold) {
                run++;
                if (run >= 5) return Math.max(0, index - 4);
            } else {
                run = 0;
            }
        }
        return -1;
    }

    private static int lastActive(float[] level, float threshold) {
        for (int index = level.length - 1; index >= 0; index--) {
            if (level[index] >= threshold) return index;
        }
        return -1;
    }

    private static int sustainEnd(float[] level, float threshold) {
        if (level.length == 0) return -1;
        double peak = percentile(level, 95);
        float band = (float) Math.max(threshold, peak * 0.30);
        int minimum = Math.max(1, (int) Math.round(1.2 / CELL_SECONDS));
        int best = -1, run = 0;
        for (int index = 0; index < level.length; index++) {
            if (level[index] >= band) {
                run++;
                if (run >= minimum) best = index;
            } else {
                run = 0;
            }
        }
        return best;
    }

    private static double sumSquares(float[] values) {
        double sum = 0.0;
        for (float v : values) sum += (double) v * v;
        return sum;
    }

    /**
     * 人声在混音里的实际比例：把 mix 投影到 vocal 方向上的最小二乘系数。
     * 分离模型给出的不是「混音里那一份人声」的严格副本，直接相减会留残留；
     * 这个系数让残差能量最小，交接期用它把素材对回原曲响度。
     * 只统计确实有人在唱的时间格——把静音段算进来会把系数拉向 0。
     */
    private static float vocalProjection(float[] mix, float[] vocal, float[] env,
                                         float threshold, int hop) {
        if (hop <= 0) return 1.0f;
        int usable = Math.min(env.length, Math.min(vocal.length / hop, mix.length / hop));
        double numerator = 0.0, denominator = 0.0;
        for (int index = 0; index < usable; index++) {
            if (env[index] < threshold) continue;
            int start = index * hop;
            for (int i = 0; i < hop; i++) {
                numerator += (double) mix[start + i] * vocal[start + i];
                denominator += (double) vocal[start + i] * vocal[start + i];
            }
        }
        if (denominator <= 1e-12) return 1.0f;
        return (float) (numerator / denominator);
    }
}
