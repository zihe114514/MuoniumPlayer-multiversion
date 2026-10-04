package com.muoniumplayer.core.screens.ncm;

/**
 * 转场性能档位。
 *
 * <p>这个类只负责“判断现在该用哪一档”，不直接改渲染代码。帧率采样放在全局灵动岛渲染入口，
 * 每个真实渲染帧只采一次；播放器和全屏歌词转场再按档位选择降载策略。</p>
 *
 * <h2>为什么换档要「累计够时间」而不是当场决定</h2>
 * 平滑帧时（EMA）跨过门槛的那一刻，往往只是一次 GC、一次区块加载、或切回窗口那一下——
 * 不是机器不行了。原来降级是<em>立即</em>生效的，代价全落在玩家眼睛看得见的那一项上：
 * 真玻璃一被摘掉，整块面板就从「糊过的亮画面」一帧切回「原来的实色深底」，症状就是
 * 2026-09-30 反馈的「有的时候会闪烁，表现为暗一下然后恢复」。现在一条慢帧要<em>本帧自己也慢</em>
 * 才计入累计（一次 200ms 的卡顿会把 EMA 顶上去十几帧才回落，但那十几帧都是快的，
 * 累计会被清零），而且两次真正换档之间再按住 {@link #FLIP_HOLD_NANOS}。
 *
 * <h2>更要紧的是它自己会打自己</h2>
 * 玻璃是贵的那一层，所以「掉帧 → 摘玻璃 → 帧率好了 → 装回玻璃 → 又掉帧」是一条天然的正反馈，
 * 周期大约两三秒，读起来就是呼吸式的闪。断掉它用的是两条规则：
 * <ol>
 *   <li><b>玻璃只在转场期间降载</b>（{@link #glassTarget}）。这一档原本就是这个类存在的理由
 *       ——「优先保动画连续性」；界面好好开着的时候没有任何理由替玩家把玻璃摘了。
 *       稳态下机器真的扛不住，那是设置里四个生效面与「实时背景采集」该管的事，
 *       不是一条没人看得懂的暗改。</li>
 *   <li><b>摘与装都是 250ms 斜坡</b>（{@link #liquidGlassWeight()}），不是开关。
 *       最坏情况是一次淡出加一次淡入，而不是一帧变黑。</li>
 * </ol>
 *
 * <h2>档位决定是纯函数</h2>
 * {@link #step} 吃进「当前状态 + 平滑帧时 + 本帧实测 + 是否正在转场」，吐出新的状态，
 * 自己不看时钟也不碰任何渲染类。「闪烁修好了没有」这件事没法靠读代码证明，只能拿假帧序列
 * 走一遍——见 {@code GlassFlickerStaticTest}。
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
    /** EMA 权重：约 7~10 帧内感知到掉帧。 */
    private static final double SAMPLE_WEIGHT = 0.15;

    /**
     * 慢帧要<em>连续</em>累计 400ms 才真的降一档。
     *
     * <p>按时间而不是按帧数，是因为这件事在高帧率和低帧率下要给玩家同一个手感——
     * 而「连续 45 帧」在 30fps 是 1.5 秒、在 240fps 只有 0.19 秒。</p>
     */
    private static final long DEGRADE_SUSTAIN_NANOS = 400_000_000L;
    /** 好转要持续 1.5 秒才升一档：比降档慢得多，装回来这件事不该抢。 */
    private static final long RECOVERY_SUSTAIN_NANOS = 1_500_000_000L;
    /** 一次真正换档之后按住 2 秒，期间不改档位。 */
    private static final long FLIP_HOLD_NANOS = 2_000_000_000L;
    /** 玻璃强度从「没有」走到「完整」要 250ms：和主题淡入淡出同一量级，读作淡出而不是闪。 */
    private static final long GLASS_RAMP_NANOS = 250_000_000L;
    /** 比整条斜坡还长的卡顿就一步走完，不留「玻璃卡在半透明」这种中间态。 */
    private static final long GLASS_RAMP_SNAP_NANOS = GLASS_RAMP_NANOS * 2L;

    private static long lastFrameNanos;
    private static double smoothedFrameMs = 16.7;
    private static final State STATE = new State();

    private TransitionPerformanceBudget() {
    }

    /** {@link #step} 要读写的那几样计时器；放在一个对象里是为了让它能当纯函数被测。 */
    static final class State {
        Quality quality = Quality.BALANCED;
        /** 已经连续「本帧自己就慢」多久 / 已经连续好到可以回升多久。 */
        long degradeHeldNanos;
        long recoveryHeldNanos;
        /** 上一次<em>真的</em>换了档的时刻；{@code flipped} 之前没有时刻可言。 */
        long lastFlipNanos;
        boolean flipped;
        /** 玻璃那一项专用的缓动值：0 = 普通底色，1 = 完整真玻璃。 */
        float glassWeight = 1f;
    }

    /** 由全局灵动岛渲染入口调用；同一个渲染帧只会进来一次。 */
    public static void tick() {
        long now = System.nanoTime();
        if (lastFrameNanos != 0L) {
            long frameNanos = now - lastFrameNanos;
            double frameMs = frameNanos / 1_000_000.0;
            // 样本的可信区间和原来一样：亚毫秒的间隔没有意义，超长的那一下（切标签页、
            // 加载界面）会把均值拽歪，所以都不进 EMA。区别是「不可信」不再连带冻住缓动。
            if (frameMs > 0.5 && frameMs < 250.0) {
                smoothedFrameMs += (frameMs - smoothedFrameMs) * SAMPLE_WEIGHT;
                step(STATE, smoothedFrameMs, frameMs, frameNanos, now, anyTransitionActive());
            } else {
                step(STATE, smoothedFrameMs, -1.0, frameNanos, now, anyTransitionActive());
            }
        }
        lastFrameNanos = now;
    }

    /**
     * 走一帧。见类注释「档位决定是纯函数」。
     *
     * @param smoothedFrameMs <em>已经更新过</em>的平滑帧时，决定该往哪一档看
     * @param frameMs 本帧实测；{@code <= 0} 表示这一帧的样本不可信，只用它的时长推进计时与斜坡，
     *        不给任何一档加分（也不清零）
     * @param frameNanos 本帧实际过了多久
     * @param transitionActive 播放器展开/收起或封面转场正在播
     */
    static void step(State state, double smoothedFrameMs, double frameMs, long frameNanos,
                     long nowNanos, boolean transitionActive) {
        Quality target = targetFor(state.quality, smoothedFrameMs);
        if (target.ordinal() > state.quality.ordinal()) {
            // 只有「这一帧自己就慢」才累计。一次 200ms 的卡顿会把平滑帧时顶到四十多毫秒
            // 并慢慢回落十几帧，但那十几帧全是快的——那种情况该当作「刚才卡了一下」，
            // 而不是「这台机器不行」，否则一次随机 GC 就够把整页玻璃闪掉。
            if (frameMs >= stepDownThreshold(target)) {
                state.degradeHeldNanos += frameNanos;
            } else if (frameMs > 0.0) {
                state.degradeHeldNanos = 0L;
            }
            state.recoveryHeldNanos = 0L;
        } else if (target.ordinal() < state.quality.ordinal()) {
            // 回升这边反过来：只要本帧没慢到 LOW 门槛就算「好」，所以一台 100fps 的机器
            // （10ms 一帧）照样能在 1.5 秒后把玻璃装回来。原来的写法要求帧时落在
            // 15.5~18.5ms 之间才算数，快机器反而永远凑不够。
            if (frameMs > 0.0 && frameMs < LOW_FRAME_MS) {
                state.recoveryHeldNanos += frameNanos;
            } else if (frameMs >= LOW_FRAME_MS) {
                state.recoveryHeldNanos = 0L;
            }
            state.degradeHeldNanos = 0L;
        } else {
            state.degradeHeldNanos = 0L;
            state.recoveryHeldNanos = 0L;
        }
        long sinceFlip = state.flipped ? nowNanos - state.lastFlipNanos : Long.MAX_VALUE;
        if (allowsFlip(state.quality, target, state.degradeHeldNanos, state.recoveryHeldNanos,
                sinceFlip, transitionActive)) {
            state.quality = target;
            state.degradeHeldNanos = 0L;
            state.recoveryHeldNanos = 0L;
            state.lastFlipNanos = nowNanos;
            state.flipped = true;
        }
        state.glassWeight = rampToward(state.glassWeight,
                glassTarget(state.quality, transitionActive), frameNanos);
    }

    /**
     * 只看平滑帧时该往哪一档走，<b>不含</b>任何「能不能现在就走」。
     *
     * <p>15.5~18.5ms 那一段是迟滞带：既不够得上降一档，也不够资格回升，返回 {@code current}。
     * 60fps 带 vsync 抖动时这一段正是最常被踩到的地方。</p>
     */
    static Quality targetFor(Quality current, double smoothedFrameMs) {
        if (smoothedFrameMs >= MINIMAL_FRAME_MS) {
            return Quality.MINIMAL;
        }
        if (smoothedFrameMs >= LOW_FRAME_MS) {
            return Quality.LOW;
        }
        if (smoothedFrameMs < RECOVERY_FRAME_MS) {
            return Quality.BALANCED;
        }
        return current;
    }

    /** 降向 {@code target} 那一档时，「本帧自己算慢」的门槛。 */
    private static double stepDownThreshold(Quality target) {
        return target == Quality.MINIMAL ? MINIMAL_FRAME_MS : LOW_FRAME_MS;
    }

    /**
     * 这一步到底走不走：降档要慢帧累计够时间，升档要好帧累计够时间而且不正在转场，
     * 并且离上一次真的换档至少隔 {@link #FLIP_HOLD_NANOS}。
     */
    static boolean allowsFlip(Quality current, Quality target, long degradeHeldNanos,
                              long recoveryHeldNanos, long sinceFlipNanos, boolean transitionActive) {
        if (target == null || target.ordinal() == current.ordinal()) {
            return false;
        }
        if (sinceFlipNanos < FLIP_HOLD_NANOS) {
            return false;
        }
        if (target.ordinal() > current.ordinal()) {
            return degradeHeldNanos >= DEGRADE_SUSTAIN_NANOS;
        }
        return recoveryHeldNanos >= RECOVERY_SUSTAIN_NANOS && !transitionActive;
    }

    /**
     * 玻璃这一档<em>此刻</em>该出多少：极低档且正在转场 → 0，其余 → 1。
     *
     * <p>界面好好开着的时候不给 0，是这一轮改掉的那个「暗一下」的根因（见类注释）。
     * 转场期间仍然给 0：那几百毫秒里同屏要糊的东西最多，掉在这上面最划算。</p>
     */
    static float glassTarget(Quality quality, boolean transitionActive) {
        return transitionActive && quality == Quality.MINIMAL ? 0f : 1f;
    }

    /** 朝目标走一步斜坡；超过整条斜坡两倍的卡顿直接走完，不留半截状态。 */
    static float rampToward(float current, float target, long elapsedNanos) {
        if (current == target || elapsedNanos <= 0L) {
            return current;
        }
        float delta = Math.min(elapsedNanos, GLASS_RAMP_SNAP_NANOS) / (float) GLASS_RAMP_NANOS;
        return target > current
                ? Math.min(target, current + delta)
                : Math.max(target, current - delta);
    }

    public static Quality quality() {
        return STATE.quality;
    }

    public static boolean isLow() {
        return STATE.quality != Quality.BALANCED;
    }

    public static boolean isMinimal() {
        return STATE.quality == Quality.MINIMAL;
    }

    /**
     * 真玻璃此刻该出多少：0 = 完全按普通底色画，1 = 完整玻璃。
     *
     * <p>{@code GlassLook} 读的是这个连续值，不是 {@link #isMinimal()} 那个布尔——稳态下两者
     * 等价，差的那 250ms 正是「暗一下」和「淡一下」的区别。</p>
     */
    public static float liquidGlassWeight() {
        return STATE.glassWeight;
    }

    /**
     * 播放器从灵动岛展开/收回时只关闭高成本的材质分支。
     *
     * <p>交接期间仍保留预糊背景、轻量亮部和圆角边界；只有低配档才额外收掉多层阴影、
     * 扫光等次要细节。这样既不在动画帧里启动逐像素液态折射或融合重建，也不会把表面
     * 突然变成一块没有层次的纯色。</p>
     */
    public static boolean simplifySurfaceDetail() {
        // 几何阴影和边缘光很便宜；真正需要降载的是液态玻璃和融合 shader，
        // 不能为了省一点矩形绘制把转场的层次感一起删掉。
        return isLow();
    }

    /**
     * 灵动岛交接的硬实时路径。与根据帧率慢慢换档的 {@link #isMinimal()} 不同，
     * 这里从转场第一帧就生效，避免第一帧先支付整面液态玻璃的成本。
     */
    public static boolean isPlayerIslandTransitionActive() {
        return PlayerIslandTransition.isRunning();
    }

    /**
     * 过渡期间跳过播放器外层的高成本液态特效叠加。
     *
     * <p>播放器表面本身不再走逐像素折射，而由 {@code paintPlayerTransitionSurface} 使用
     * 已缓存的预糊背景接管；这个开关只负责阻止外层主题高光重复采样。动画结束后恢复正常
     * 的液态玻璃路径。</p>
     */
    public static boolean skipLiquidGlass() {
        return isPlayerIslandTransitionActive()
                || com.muoniumplayer.core.settings.HudConfig.glassAutoDegrade
                && STATE.glassWeight <= 0.001f;
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
