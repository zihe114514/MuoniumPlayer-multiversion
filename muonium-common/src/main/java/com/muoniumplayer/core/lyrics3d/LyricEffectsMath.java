package com.muoniumplayer.core.lyrics3d;

/**
 * 3D 歌词三特效的纯函数数学：Wiggle（低帧率抖动）、Word Pop（词被唱到的瞬间弹一下）、
 * Text Flash（按节拍闪）。
 *
 * <p>全部无状态、可离线断言（ProbeTest），与帧率无关 —— 时间都由调用方以毫秒/秒传入。
 * 参考实现（Placement: In the world）的对应项：Wiggle fps≈8、Wiggle amount 0..1、
 * Word Pop 弹一下（带 size 上限）、Text Flash 按拍闪（可调幅度）。</p>
 */
public final class LyricEffectsMath {

    private LyricEffectsMath() {
    }

    // ==================== Wiggle ====================

    /**
     * Wiggle 的量化时钟：把连续时间按 fps 切成台阶。
     *
     * <p>参考实现默认 8fps —— 手绘逐帧的抖动质感来自<b>低帧率</b>，用连续时间反而像信号抖动。
     * {@code floor(t*fps)/fps} 让同一台阶内相位恒定，字稳住一拍再跳到下一组偏移。</p>
     *
     * @param tSeconds 连续动画时间（秒）
     * @param fps      量化帧率（参考实现 8；&lt;=0 视为 1）
     */
    public static double wiggleStep(double tSeconds, double fps) {
        double f = fps <= 0 ? 1.0 : fps;
        return Math.floor(tSeconds * f) / f;
    }

    /**
     * 逐字符 Wiggle 偏移（单位：行高的比例）。相位由「字符下标 + 量化时间」经确定性随机给出
     * （与全屏页 {@code FullscreenLyricStage.random} 同一族：fract(sin(x)*10000)），
     * 同一字符同一台阶永远取同一偏移 —— 录屏与回放一致。
     *
     * @param charIndex  字符下标（行内）
     * @param timeStep   已量化的时间（{@link #wiggleStep} 的返回值）
     * @param amount     抖动幅度 0..1（设置项 Wiggle amount）
     * @return 水平/垂直偏移，单位是「行高的比例」；乘上行高（方块）得世界偏移
     */
    public static double[] wiggleOffset(long seed, int charIndex, double timeStep, double amount) {
        if (amount <= 0.001) {
            return new double[] {0.0, 0.0};
        }
        double phase = timeStep * 7.0 + charIndex * 1.37;
        double rx = frac(Math.sin((seed + phase) * 12.9898) * 43758.5453);
        double ry = frac(Math.sin((seed + phase) * 78.2330) * 24634.6345);
        // ±0.5 行高以内；amount 线性缩放。参考实现 amount 默认 0.61 时约 ±0.3 行高。
        return new double[] { (rx - 0.5) * amount, (ry - 0.5) * amount };
    }

    private static double frac(double v) {
        return v - Math.floor(v);
    }

    // ==================== Word Pop ====================

    /**
     * 词刚被唱到那一瞬的弹出量：欠阻尼弹簧包络（与全屏页 qplayerSyllableLift 同族的形状，
     * 但输入是「距词起始的毫秒」），单峰：0 → 冲过 1 → 回落 → 稳定 0。
     *
     * <p>数学：欠阻尼二阶系统的速度冲量响应。ω0 = 18 rad/s（周期 ≈ 0.35s），
     * ζ = 0.55（明显回弹一次）。{@code elapsed<=0}（还没唱到）与 {@code elapsed>1.2s}（演完）
     * 恒 0，所以这个函数可以直接乘在词的缩放上。</p>
     *
     * @param elapsedMs 距这个词时间戳的毫秒数（positionMs - word.timestamp）
     * @return 0..1.6 左右的弹跳量；乘弹跳幅度（方块）加到词尺寸/位移上
     */
    public static double wordPop(double elapsedMs) {
        if (elapsedMs <= 0.0 || elapsedMs > 1200.0) {
            return 0.0;
        }
        double t = elapsedMs / 1000.0;
        double w0 = 18.0;
        double zeta = 0.55;
        double wd = w0 * Math.sqrt(1.0 - zeta * zeta);
        double env = Math.exp(-zeta * w0 * t);
        // 冲量响应：env * sin(wd t) 形状，归一化到峰值 ≈ 1
        double value = env * Math.sin(wd * t) * (w0 / wd);
        return value > 0 ? value : 0.0; // 只取正半周：词是「弹出」不是「缩进」
    }

    /**
     * 词级进度：有逐字轴时线性 0..1，无逐字轴时整行同亮（退化为 0 或 1）。
     * 渲染层用它决定 Word Pop 作用在哪个词上 —— 与 {@code FullscreenLyricStage.graphemeProgress}
     * 的降级语义一致，绝不给普通 LRC 伪造逐字。
     */
    public static double wordProgress(long wordStartMs, long wordDurationMs, double positionMs) {
        double window = wordDurationMs > 0 ? wordDurationMs : 320.0;
        if (window < 40.0) window = 40.0;
        double p = (positionMs - wordStartMs) / window;
        if (p < 0) return 0;
        if (p > 1) return 1;
        return p;
    }

    // ==================== Text Flash ====================

    /**
     * 按节拍触发的亮度闪动：beatCount 边缘检测 + 指数衰减包络。
     *
     * <p>调用方每帧传同一个 {@code beatCount}（{@code PlaybackPulse.beatCount()}，单调递增），
     * 这里对比内部记忆的上一次值：计数变了说明新拍落下，闪烁量拉满 1，随后按 tau 衰减。
     * 纯函数化：状态由调用方持有（{@code long lastBeat} 字段），便于测试与多渲染目标隔离。</p>
     *
     * @param lastBeatOut 调用方的状态数组 [0] = 上次见到的拍数；传入前先更新，
     *                    这里只做衰减。保留这个参数形式是为了状态归属清晰。
     * @param secondsSinceBeat 距上一拍落下过了多少秒（调用方用墙钟算）
     * @param amount      闪烁幅度 0..1（设置项）
     * @return 0..1 的亮度加成，乘在文字亮度/alpha 上
     */
    public static double textFlash(double secondsSinceBeat, double amount) {
        if (amount <= 0.001) return 0.0;
        if (secondsSinceBeat < 0) return 0.0;
        double tau = 0.22; // 与 PlaybackPulse 的 release 0.14s 同量级，闪一下就灭
        return amount * Math.exp(-secondsSinceBeat / tau);
    }
}
