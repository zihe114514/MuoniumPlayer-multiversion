package com.muoniumplayer.core.input;

import com.muoniumplayer.core.settings.TouchSettings;

/**
 * 轮询式触屏手势识别器：按下 → （位移超阈值）锁轴拖动 → 松手点按判定 / 惯性甩动速度。
 *
 * <h2>为什么是轮询、不是事件</h2>
 * 本 UI 工具链的全部既有交互（进度条 seek、音量、队列排序、HUD 编辑器）都是
 * 「每帧轮询 {@code Mouse.isButtonDown(0)} + 自己记边沿」的形状，没有事件系统。
 * FCL 系启动器把触屏映射成左键，所以左键的按下/移动/抬起<b>就是</b>手指的落/滑/抬。
 * 手势层沿用同一条数据流：由 {@code NCMScreen.drawScreen} 每帧喂一次
 * {@link #advance(long, double, double, boolean)}，消费者（ScrollPanel、歌词页、队列抽屉、
 * 延迟点击分发）在渲染回调里轮询查询。全部实现落在共享层，五棵版本树零适配；
 * 26.3 依赖 {@code MouseButtonStateMixin} 让 SDL 后端开屏后左键状态仍然真实。
 *
 * <h2>状态机</h2>
 * <pre>
 * IDLE ──按下──▶ PRESSED ──累计位移 &gt; 阈值──▶ V_DRAG / H_DRAG（主轴一次锁定，不再改判）
 *  ▲                │                            │
 *  └──────松手（wasUnmovedTap 点按）◀────────────┘─松手（甩动速度供惯性）
 * </pre>
 *
 * <ul>
 *   <li><b>锁轴</b>：|总位移Y| ≥ |总位移X| × 1.2 判纵向。锁定之前一切移动都算抖动（点按不失效、
 *       横向滑条照常响应）；锁定之后另一轴的消费者必须让位（见 {@link #isVerticalDrag()}）。</li>
 *   <li><b>认领</b>：只有纵向拖动可以被 {@link #claim(Object)} 独占——第一个「按下起点落在
 *       自己视口里」的滚动容器先到先得；认领只影响滚动容器之间的排他，不影响延迟点击。</li>
 *   <li><b>点按</b>：松手时累计位移从未超阈值 → {@link #consumeTap()} 返回 true，
 *       {@code NCMScreen.mouseReleased} 以此决定是否补发被扣住的点击。</li>
 *   <li><b>长按</b>：按住不动超 {@link TouchSettings#LONG_PRESS_MS} 且未锁轴 →
 *       {@link #longPressElapsed()} 给出一个单帧边沿（队列抓取排序用）。</li>
 * </ul>
 *
 * <h2>线程与时序</h2>
 * 只应在渲染线程使用。松手有两个入口：{@code advance} 发现左键已松（事件丢失时的兜底）、
 * {@link #consumeTap()} 由 vanilla {@code mouseReleased} 事件直接询问——两者幂等，谁先到谁结算。
 * 手势只在 NCM 界面开着的帧里推进；{@code advance} 发现与上次相隔超过
 * {@link #STALE_RESET_NANOS}（切后台 / 换屏）会自动复位，不会把上一个界面的按压带进来。
 *
 * <p>坐标空间：调用方喂什么就是什么（NCM 喂 WindowAdaptiveScale 校正后的布局坐标），
 * 阈值/速度的单位与该空间一致（GUI 像素）。
 */
public final class TouchGestures {

    /** 单例。渲染线程专用，不需要并发防护。 */
    private static final TouchGestures INSTANCE = new TouchGestures();

    public static TouchGestures get() {
        return INSTANCE;
    }

    /** 拖动主轴。锁定前为 NONE——此时所有位移都是「抖动」。 */
    public enum Axis {
        NONE, VERTICAL, HORIZONTAL
    }

    /**
     * 纵向优势比：|dy| ≥ |dx| × 1.2 才判纵向。
     *
     * <p>1.2 而不是 1.0：斜着 45° 的手指轨迹更可能是想滚列表（手机上横向可拖的东西少），
     * 给纵向一点优惠；也不到 1.5，否则进度条上方稍陡的斜滑会被误判成滚动。</p>
     */
    private static final double VERTICAL_BIAS = 1.2;

    /** 与上次推进相隔超过该值视为断档（切后台 / 换屏），状态复位而不是拿巨大 dt 算速度。 */
    private static final long STALE_RESET_NANOS = 250_000_000L;

    /** 速度 EMA 的时间平滑系数：新样本占 1/4，兼顾跟手与不过分放大末帧抖动。 */
    private static final double VELOCITY_SMOOTHING = 0.25;

    /** 速度样本的最小有效 dt（毫秒）：dt 过小时瞬时速度是噪声，跳过采样。 */
    private static final double MIN_SAMPLE_DT_MS = 0.5;

    // ---- 状态 ----
    private boolean pressed;
    /** 本轮按压的起点（布局坐标）。 */
    private double startX;
    private double startY;
    /** 上一次样本的位置。 */
    private double lastX;
    private double lastY;
    private long pressNanos;
    private long lastSampleNanos;
    /**
     * 是否已建立时间基准。{@code lastSampleNanos} 初值 0 与「真实的 t=0」无法区分，
     * 没有这个旗子的话首个样本会拿「距纪元的差值」当 dt，断档判定跟着失真。
     */
    private boolean hasSampled;
    private Axis axis = Axis.NONE;
    /** 锁轴后本帧的增量（advance 时更新，消费者两次 advance 之间读到的是同一帧的值）。 */
    private double frameDx;
    private double frameDy;
    /** 未锁轴期间「是否曾超过阈值」：一旦为 true，本轮按压永远不再算点按。 */
    private boolean exceededThreshold;
    /** y 方向速度（GUI 像素/ms，向下为正）。按住期间持续更新，松手后冻结供甩动消费。 */
    private double velocityY;
    private double velocityX;
    /** 纵向拖动的独占认领者。 */
    private Object claim;
    /** 上一轮按压结算时的拖动结论；{@link #lastPressWasDrag()} 在松手后到下一轮按压前可查。 */
    private boolean lastPressDragged;
    /**
     * 松手已发生、答案已算好但还没被取走的点按结论（null = 没有待交付的）。
     *
     * <p>松手有两个入口：mouseReleased 事件（通常先到，当面结算）与 advance 兜底
     * （事件丢失时）。后者先到时答案存在这里，等消费者下次 {@link #consumeTap()} 来取——
     * 「事件丢了点按就凭空消失」和「拖动被当成点按」两种错都不许发生。</p>
     */
    private Boolean pendingTapAnswer;
    /** 长按边沿：仅在跨越阈值的那一次 advance 里为 true。 */
    private boolean longPressEdge;
    private boolean longPressFired;

    private TouchGestures() {
    }

    /**
     * 每帧推进一次。由 {@code NCMScreen.drawScreen} 在坐标校正之后调用。
     *
     * @param nowNanos {@link System#nanoTime()}；同一帧重复调用用同一时间戳防重复推进
     * @param mouseX   布局空间鼠标 X
     * @param mouseY   布局空间鼠标 Y
     * @param leftDown 左键（= 触屏手指）当前是否按住
     */
    public void advance(long nowNanos, double mouseX, double mouseY, boolean leftDown) {
        long dtNanos = hasSampled ? nowNanos - lastSampleNanos : 0L;
        if (hasSampled && dtNanos > STALE_RESET_NANOS) {
            // 断档太久：上一轮状态不可信，全部丢弃后按「新的一帧」重新起步。
            resetPress();
            lastSampleNanos = nowNanos;
            if (leftDown) {
                // 断档期间手指一直按着（如切后台再回来）：保留按压，但速度清零重新采样。
                beginPress(nowNanos, mouseX, mouseY);
            }
            return;
        }
        boolean sameFrame = hasSampled && lastSampleNanos == nowNanos;
        if (!sameFrame) {
            lastSampleNanos = nowNanos;
            hasSampled = true;
        }

        longPressEdge = false;

        if (!pressed) {
            if (leftDown) {
                beginPress(nowNanos, mouseX, mouseY);
            }
            return;
        }

        // —— 按压中 ——
        double dx = mouseX - lastX;
        double dy = mouseY - lastY;
        if (leftDown) {
            if (!sameFrame) {
                frameDx = axis == Axis.NONE ? dx : (axis == Axis.HORIZONTAL ? dx : 0);
                frameDy = axis == Axis.NONE ? dy : (axis == Axis.VERTICAL ? dy : 0);
                sampleVelocity(dtNanos, dx, dy);
                lastX = mouseX;
                lastY = mouseY;
                maybeLockAxis();
                maybeFireLongPress(nowNanos);
            }
            return;
        }

        // —— 松手（advance 入口的兜底；事件入口通常先到这里）——
        // 点按结论先算好挂在 pendingTapAnswer 上，由 consumeTap 取走（幂等）。
        pendingTapAnswer = !exceededThreshold;
        settleRelease();
    }

    private void beginPress(long nowNanos, double mouseX, double mouseY) {
        pressed = true;
        pendingTapAnswer = null;
        exceededThreshold = false;
        longPressFired = false;
        longPressEdge = false;
        axis = Axis.NONE;
        claim = null;
        frameDx = 0;
        frameDy = 0;
        velocityX = 0;
        velocityY = 0;
        startX = mouseX;
        startY = mouseY;
        lastX = mouseX;
        lastY = mouseY;
        pressNanos = nowNanos;
    }

    /** 松手收尾：速度冻结（甩动消费者此刻来取），按压状态清空。 */
    private void settleRelease() {
        pressed = false;
        axis = Axis.NONE;
        claim = null;
        frameDx = 0;
        frameDy = 0;
        lastPressDragged = exceededThreshold;
        // velocityX/Y 故意保留：甩动消费发生在松手后的同一帧渲染里。
    }

    private void resetPress() {
        if (pressed) {
            // 断档复位也算一次松手：是不是点按的结论留给消费者取，不能凭空吞掉。
            pendingTapAnswer = !exceededThreshold;
        }
        pressed = false;
        axis = Axis.NONE;
        claim = null;
        frameDx = 0;
        frameDy = 0;
        velocityX = 0;
        velocityY = 0;
        exceededThreshold = false;
        lastPressDragged = false;
        longPressFired = false;
        longPressEdge = false;
    }

    private void sampleVelocity(long dtNanos, double dx, double dy) {
        double dtMs = dtNanos / 1_000_000.0;
        if (dtMs < MIN_SAMPLE_DT_MS) {
            return;
        }
        velocityX += (dx / dtMs - velocityX) * VELOCITY_SMOOTHING;
        velocityY += (dy / dtMs - velocityY) * VELOCITY_SMOOTHING;
    }

    private void maybeLockAxis() {
        if (axis != Axis.NONE || exceededThreshold) {
            return;
        }
        double totalDx = lastX - startX;
        double totalDy = lastY - startY;
        if (Math.hypot(totalDx, totalDy) <= Math.max(1.0, TouchSettings.DRAG_THRESHOLD_PX)) {
            return;
        }
        exceededThreshold = true;
        axis = Math.abs(totalDy) >= Math.abs(totalDx) * VERTICAL_BIAS
                ? Axis.VERTICAL : Axis.HORIZONTAL;
    }

    private void maybeFireLongPress(long nowNanos) {
        if (longPressFired || axis != Axis.NONE) {
            return;
        }
        if (nowNanos - pressNanos >= TouchSettings.LONG_PRESS_MS * 1_000_000L) {
            longPressFired = true;
            longPressEdge = true;
        }
    }

    // ------------------------------------------------------------------
    // 消费者查询
    // ------------------------------------------------------------------

    /** 左键当前是否按住（无论是否已锁轴）。 */
    public boolean isPressed() {
        return pressed;
    }

    /** 按压起点（布局坐标）；无按压时返回值无意义。 */
    public double startX() {
        return startX;
    }

    public double startY() {
        return startY;
    }

    /** 当前锁定的主轴；未超阈值时为 {@link Axis#NONE}。 */
    public Axis axis() {
        return axis;
    }

    /** 是否处于<b>纵向</b>拖动（已锁轴 + 按住）。滚动容器只在这种情况下可以认领。 */
    public boolean isVerticalDrag() {
        return pressed && axis == Axis.VERTICAL;
    }

    /** 是否处于<b>横向</b>拖动。横向滑条据此让位/接管。 */
    public boolean isHorizontalDrag() {
        return pressed && axis == Axis.HORIZONTAL;
    }

    /**
     * 本帧的纵向增量（布局像素，向下为正）。仅 {@link #isVerticalDrag()} 时有意义，
     * 其余情况为 0。数值来自锁轴后的原始 dy——滚动容器自己带符号取用。
     */
    public double dy() {
        return isVerticalDrag() ? frameDy : 0;
    }

    /** 本帧横向增量，语义同 {@link #dy()}。 */
    public double dx() {
        return isHorizontalDrag() ? frameDx : 0;
    }

    /**
     * 松手瞬间的纵向速度（GUI 像素/ms，向下为正）。
     *
     * <p>按住期间是实时 EMA；松手后冻结，甩动公式
     * {@code distance = v² / (2·TouchSettings.FLING_DECEL)} 由滚动容器套方向后使用。</p>
     */
    public double velocityY() {
        return velocityY;
    }

    /** 本轮按压是否<b>从未</b>超过拖动阈值（可能是点按）。配合 {@link #consumeTap()}。 */
    public boolean isUnmoved() {
        return pressed && !exceededThreshold;
    }

    /**
     * 上一轮按压是否是拖动（松手结算后到下一轮按压前有效）。
     *
     * <p>给「按下沿起手、松手沿收手」的原始轮询交互（歌词行 seek、进度条点击）用：
     * 它们不在延迟分发的树里，没法搭 {@link #consumeTap()} 的便车，只能在松手沿上问
     * 「这一按到底拖没拖」。拖了就不许触发点击语义。</p>
     */
    public boolean lastPressWasDrag() {
        return lastPressDragged;
    }

    /**
     * 结算一次点按：本次按压从未超阈值 → true（是点按），拖动过 → false。
     *
     * <p>三条路径共用一份答案：按压仍在身（mouseReleased 事件先到，当面结算）；
     * 松手已被 advance 兜底结算（答案挂在 {@code pendingTapAnswer} 上，这里取走，
     * 幂等——同一轮按压只 true 一次）；没有按压在身（按压发生在手势层视野之外，
     * 如从游戏内点开界面）——保守起见按点对待，让点击照常分发。</p>
     */
    public boolean consumeTap() {
        if (pressed) {
            boolean answer = !exceededThreshold;
            settleRelease();
            return answer;
        }
        if (pendingTapAnswer != null) {
            boolean answer = pendingTapAnswer;
            pendingTapAnswer = null;
            return answer;
        }
        return true;
    }

    /**
     * 是否恰好在本帧跨过长按阈值（未锁轴）。
     *
     * <p>边沿语义：只在跨越的那一刻为 true，下一帧自动回 false——消费者按帧轮询即可，
     * 不需要自己记状态。锁轴（开始拖动）之后长按永不再触发。</p>
     */
    public boolean longPressElapsed() {
        return longPressEdge;
    }

    /**
     * 纵向拖动的独占认领。只有 {@link #isVerticalDrag()} 时才允许调用，返回是否认领成功。
     * 松手 / 新一轮按压自动解除。
     */
    public boolean claim(Object consumer) {
        if (!isVerticalDrag()) {
            return false;
        }
        if (claim == null) {
            claim = consumer;
        }
        return claim == consumer;
    }

    /** 当前认领者；无认领或非拖动时为 null。 */
    public Object claimedBy() {
        return isVerticalDrag() ? claim : null;
    }

    // ------------------------------------------------------------------
    // 测试入口：把内部状态拨到指定形状，供单元测试构造场景。
    // ------------------------------------------------------------------

    static TouchGestures newInstanceForTest() {
        return new TouchGestures();
    }
}
