package com.muoniumplayer.core.screens.ncm;

/**
 * 转场性能档位。
 *
 * <p>这个类只负责“判断现在该用哪一档”，不直接改渲染代码。帧率采样放在全局灵动岛渲染入口，
 * 每个真实渲染帧只采一次；播放器和全屏歌词转场再按档位选择降载策略。</p>
 */
public final class TransitionPerformanceBudget {

    public enum Quality {
        BALANCED, LOW, MINIMAL
    }

    /** 60fps 的帧预算是 16.7ms；留一点调度抖动，超过 18.5ms 才进入低配档。 */
    private static final double LOW_FRAME_MS = 18.5;
    /** 40fps 以下进入极低档，优先保动画连续性。 */
    private static final double MINIMAL_FRAME_MS = 25.0;
    /** 回升到 15.5ms 以下并持续一段时间才恢复，避免档位来回跳。 */
    private static final double RECOVERY_FRAME_MS = 15.5;
    private static final int RECOVERY_FRAMES = 45;
    /** EMA 权重：约 7~10 帧内感知到掉帧。 */
    private static final double SAMPLE_WEIGHT = 0.15;

    private static long lastFrameNanos;
    private static double smoothedFrameMs = 16.7;
    private static Quality quality = Quality.BALANCED;
    private static int recoveryFrames;

    private TransitionPerformanceBudget() {
    }

    /** 由全局灵动岛渲染入口调用；同一个渲染帧只会进来一次。 */
    public static void tick() {
        long now = System.nanoTime();
        if (lastFrameNanos != 0L) {
            double frameMs = (now - lastFrameNanos) / 1_000_000.0;
            if (frameMs > 0.5 && frameMs < 250.0) {
                smoothedFrameMs += (frameMs - smoothedFrameMs) * SAMPLE_WEIGHT;
                updateQuality();
            }
        }
        lastFrameNanos = now;
    }

    private static void updateQuality() {
        Quality target = smoothedFrameMs >= MINIMAL_FRAME_MS
                ? Quality.MINIMAL
                : (smoothedFrameMs >= LOW_FRAME_MS ? Quality.LOW
                : (smoothedFrameMs < RECOVERY_FRAME_MS ? Quality.BALANCED : quality));

        // 降级立即生效；升级要等转场结束且连续 45 帧稳定，避免画面来回跳。
        if (target.ordinal() > quality.ordinal()) {
            quality = target;
            recoveryFrames = 0;
            return;
        }
        if (target.ordinal() < quality.ordinal()) {
            recoveryFrames++;
            if (!anyTransitionActive() && recoveryFrames >= RECOVERY_FRAMES) {
                quality = target;
                recoveryFrames = 0;
            }
        }
    }

    public static Quality quality() {
        return quality;
    }

    public static boolean isLow() {
        return quality != Quality.BALANCED;
    }

    public static boolean isMinimal() {
        return quality == Quality.MINIMAL;
    }

    /** 低配档下，播放器落地光保留一层白边，跳过强调色描边和扫光。 */
    public static boolean simplifySurfaceDetail() {
        return isLow();
    }

    /** 极低档下，液态玻璃表面在转场期间退化为普通底色。 */
    public static boolean skipLiquidGlass() {
        return anyTransitionActive() && isMinimal();
    }

    /**
     * 极低档下，全屏歌词背景仍然保留当前模糊封面。
     *
     * <p>这里只跳过“上一首封面 -> 当前封面”的交叉淡入：少画一张全屏纹理，
     * 但不会把背景退化成黑色。当前封面会立即以完整不透明度显示。</p>
     */
    public static boolean skipLyricBackgroundCrossfade() {
        return CoverHeroTransition.isRunning() && isMinimal();
    }

    /** 极低档下，全屏歌词页的快捷入口按钮也暂时不画。 */
    public static boolean skipLyricQuickPanel() {
        return CoverHeroTransition.isRunning() && isMinimal();
    }

    /** 低配档下，舞台样式的装饰层（几何背景、齿轮、尘埃、参考线）先让位。 */
    public static boolean skipStageDecorations() {
        return anyTransitionActive() && isLow();
    }

    /** 低配档下，舞台歌词的可见窗口收窄。 */
    public static boolean reduceStageWindow() {
        return anyTransitionActive() && isLow();
    }

    /** 极低档下，舞台歌词只保留当前行前后各一行。 */
    public static boolean minimalStageWindow() {
        return anyTransitionActive() && isMinimal();
    }

    /** 经典滚动歌词在低配档下只保留当前行附近几行。 */
    public static int classicLyricWindow() {
        if (!CoverHeroTransition.isRunning()) return Integer.MAX_VALUE;
        if (isMinimal()) return 1;
        if (isLow()) return 3;
        return Integer.MAX_VALUE;
    }

    private static boolean anyTransitionActive() {
        return PlayerIslandTransition.isRunning() || CoverHeroTransition.isRunning();
    }
}
