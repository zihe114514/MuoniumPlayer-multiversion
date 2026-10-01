package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.settings.HudConfig;

/**
 * 二级浮层的进出时钟：一个 {@code t ∈ [0,1]}，进场和退场读同一条。
 *
 * <p><b>为什么不是 {@code Interpolations.interpolate(p, 1.0, .2f)}。</b>那一套没有「时长」概念：
 * 每帧只走剩下的一成二，默认 3x 倍率下两三帧就把 90% 吃掉、剩下的尾巴再拖好几帧才看不见，
 * 读起来是「先窜一下然后慢慢糊住」，而不是「落到位」。换页转场已经为这件事改成实时毫秒
 * （{@code NCMScreen#panelTransitionMillis}），浮层却还留着指数逼近，同一个播放器里两套节奏。</p>
 *
 * <p><b>为什么是一个 t 而不是进场一套、退场一套。</b>进场没播完就按 ESC 时，界面要从当前位置
 * 原路折回，而不是跳回全亮再重新淡出 —— 这也是 {@link PlayerIslandTransition} 早先立下的规矩。
 * 退场比进场短一档，是「关闭要比打开果断」的老惯例。</p>
 *
 * <p><b>各层内容不各自计时。</b>页眉、卡片、页脚都是同一条 {@code t} 上取不同的时间窗
 * （{@link #layer(double, double)}），于是错峰天然可逆：退场时 {@code t} 往回走，那一串窗口
 * 按相反的顺序收回去，不需要再写一套退场编排。这一点只有在真时钟上成立 —— 指数逼近没有
 * 「现在走到第几成」这个可读量，也就没法给每一层开窗口。</p>
 */
public final class SheetClock {

    /**
     * 基准毫秒，运行时再除以用户的「界面切换动画速度」倍率（默认 3.0，钳在 [.30,3.00]）。
     * 默认档下约为 153/100ms —— 这一档只够一块板子淡进来。
     */
    private static final double OPEN_MS = 460.0;
    private static final double CLOSE_MS = 300.0;

    /**
     * 带分层瀑布的那一族（{@link NcmSheet} 和它的六个浮层）用的基准毫秒。
     *
     * <p>为什么不能和上面那一档共用：底板 → 页眉 → 卡片 → 页脚是四层，卡片之间还要按序号错开，
     * 整条瀑布是按 {@code t} 的<b>百分比</b>开窗的。总时长 153ms 时相邻两张卡差 6ms，肉眼数不出
     * 任何先后，四层等于同时到场 —— 读起来就是「啪一下全有了」。放大到默认档 383ms 之后每张卡
     * 差 15ms、单张走完 207ms，这条瀑布才真的看得见。</p>
     *
     * <p>比换页转场（默认档 173ms）长一档多，这跟「小东西不该比整页还磨蹭」不矛盾：换页只是
     * 一整块平移，接力封面也只是一块板子，而这一族要演的是一串东西依次落位。</p>
     */
    private static final double CASCADE_OPEN_MS = 1150.0;
    private static final double CASCADE_CLOSE_MS = 750.0;

    /**
     * 一帧最多折算这么多毫秒。上限只挡切后台回来和调试器暂停这类断档；
     * 播放引起的正常卡顿（可以掉到三四十毫秒）必须照常按实时走，否则动画在墙上时钟里被拉长。
     */
    private static final double MAX_FRAME_MS = 250.0;

    private final double openMs;
    private final double closeMs;
    private double t;
    private boolean dismissing;
    private long lastNanos;

    public SheetClock() {
        this(OPEN_MS, CLOSE_MS);
    }

    private SheetClock(final double openMs, final double closeMs) {
        this.openMs = openMs;
        this.closeMs = closeMs;
    }

    /**
     * 一条要走完分层瀑布的时钟。
     *
     * <p>注意 {@link NcmSheet} 的<b>两条</b>时钟都得用它：飞行接力板的不透明度是「1 − 落点那张卡
     * 自己的入场进度」，两条时长一旦错开，接力会在卡片还没亮时就收手，半空留下一块透明的板。</p>
     */
    public static SheetClock cascade() {
        return new SheetClock(CASCADE_OPEN_MS, CASCADE_CLOSE_MS);
    }

    /** 全新打开（或整页重建）：从全隐开始。已经在退场途中就不动 —— 退完之前这一层不该复活。 */
    public void restart() {
        if (dismissing) return;
        t = 0.0;
        lastNanos = 0L;
    }

    /** 请求关闭。正在进场途中按下的，从当前进度原路折回。 */
    public void requestDismiss() {
        dismissing = true;
        lastNanos = 0L;
    }

    /** 已经收到关闭请求，但退场还没播完 —— 这一帧仍然要画，只是不再吃输入。 */
    public boolean isDismissing() {
        return dismissing;
    }

    /** 完全隐没：调用方可以把这一层回收掉了。 */
    public boolean isGone() {
        return dismissing && t <= 0.0;
    }

    /** 推进到本帧并返回原始进度。每帧只能调一次，各层读的都是它算出来的那个 {@code t}。 */
    public double tick() {
        long now = System.nanoTime();
        double deltaMs = lastNanos == 0L ? 0.0 : (now - lastNanos) / 1_000_000.0;
        lastNanos = now;
        deltaMs = Math.min(MAX_FRAME_MS, Math.max(0.0, deltaMs));

        double duration = (dismissing ? closeMs : openMs) / speed();
        t = dismissing ? Math.max(0.0, t - deltaMs / duration)
                : Math.min(1.0, t + deltaMs / duration);
        return t;
    }

    /** 本帧的缓动进度，给整块弹窗自己用。 */
    public double progress() {
        return TransitionCurveCache.MOTION.at(t);
    }

    /**
     * 在 {@code t} 上开一个时间窗，返回窗口里走好的缓动进度。
     *
     * <p>{@code from}/{@code to} 是总时长的百分比。窗口起点就是这一层的到场时机，终点决定它
     * 用多长一段走完 —— 后面那些层用同样长度平移，整串才是一条瀑布而不是越往后越慢。</p>
     */
    public double layer(final double from, final double to) {
        return TransitionCurveCache.MOTION.at(window(t, from, to));
    }

    /** 夹到 [0,1] 之后线性重映射。越界不回弹：这一层还没轮到就是全隐，没有负不透明度。 */
    public static double window(final double value, final double from, final double to) {
        if (value <= from) return 0.0;
        if (value >= to) return 1.0;
        return (value - from) / (to - from);
    }

    /**
     * 「界面切换动画速度」这一档的倍率，钳到 {@code HudConfig} 加载时那一档区间之外，
     * 免得有人把倍率配成 0。
     *
     * <p>整个播放器只有这一份：换页、浮层、分段滑块、侧栏高亮条都除以它。各处自己再夹一道
     * 迟早和 HUD 编辑器里那一档对不上，而默认值 3.0 就是<b>最快</b>那一档 —— 也就是说每个
     * 基础毫秒数实际都被除了 3，写常量的时候要按<b>除完</b>那一档估观感。</p>
     */
    public static double speed() {
        return Math.max(.30, Math.min(3.00, HudConfig.playerUiSwitchSpeed));
    }
}
