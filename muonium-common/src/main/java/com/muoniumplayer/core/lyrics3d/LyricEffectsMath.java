package com.muoniumplayer.core.lyrics3d;

/**
 * 3D 歌词特效的纯函数数学：Word Pop（词被唱到的瞬间弹一下）、Text Flash（按节拍闪）。
 * （Wiggle 低帧率抖动已整体移除；逐字入出场动画的曲线在 WorldLyricsState.buildGlyphs。）
 *
 * <p>全部无状态、可离线断言（ProbeTest），与帧率无关 —— 时间都由调用方以毫秒/秒传入。</p>
 */
public final class LyricEffectsMath {

    private LyricEffectsMath() {
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
