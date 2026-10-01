package com.muoniumplayer.core.rendering.svg;

/**
 * 一份动画的播放状态：把墙上时钟换算成 {@link SmilSvg} 要的时间轴位置。
 *
 * <p>刻意用 {@link System#nanoTime()} 而不是累加帧间隔。三个理由：</p>
 * <ol>
 *   <li>界面卡一下（切页、GC、加载纹理）之后动画该接着哪儿就哪儿，不该跟着慢半拍；</li>
 *   <li>「播完一轮再换页」这件事要在<b>后台线程</b>上判（登录轮询就在那儿），
 *       墙上时钟谁都能自己算，不用把渲染线程的状态传过去；</li>
 *   <li>同一个控件被复用时不用重置累加器。</li>
 * </ol>
 *
 * <p>不是线程安全的对象，但 {@link #time()} / {@link #finished()} 只读时间戳，
 * 从别的线程调也不会读到半个值 —— 登录那条路就是这么用的。</p>
 */
public final class SvgPlayback {

    private final double duration;
    private final boolean repeat;
    /** 起播相位（0..1）。素材开头是空档时用它跳过那一段。 */
    private final double startPhase;
    /** 停住相位（0..1）。素材结尾是空档时用它提前收在有效画面的最后一帧。 */
    private final double endPhase;
    private long startNanos = System.nanoTime();

    private SvgPlayback(SmilSvg svg, boolean repeat, double startPhase, double endPhase) {
        this.duration = svg == null ? 1.0 : svg.duration();
        this.repeat = repeat;
        this.startPhase = phase(startPhase);
        this.endPhase = Math.max(this.startPhase, phase(endPhase));
    }

    private static double phase(double value) {
        return value < 0 ? 0 : Math.min(value, 1);
    }

    /** 一直循环。 */
    public static SvgPlayback looping(SmilSvg svg) {
        return new SvgPlayback(svg, true, 0, 1);
    }

    /** 只播一轮，停在末帧。{@link #finished()} 在整轮走完时变 true。 */
    public static SvgPlayback once(SmilSvg svg) {
        return new SvgPlayback(svg, false, 0, 1);
    }

    /**
     * 从一轮的某个相位起播、只播一轮，并在 {@code endPhase} 处停住。
     *
     * <p>有些素材两头都是空档：{@code like.svg} 前 20% 整枝 {@code display:none}（从 0 起播
     * 用户点完心要先看一秒多空白），而 80% 之后又空一次（冻在末帧等于心炸没了）。
     * 起播与停住两个相位都得由素材自己说。</p>
     */
    public static SvgPlayback once(SmilSvg svg, double startPhase, double endPhase) {
        return new SvgPlayback(svg, false, startPhase, endPhase);
    }

    /**
     * 一造出来就已经演完、停在 {@code endPhase} 那一帧的一次性时钟。
     *
     * <p>给「常驻显示动画里的某一帧」用：爱心那枚素材自己有一颗画全的实心红心，界面开列表时
     * 不该让 209 行跟着一起爆心，所以直接给它一条已经走完的时钟；要爆的时候由调用方换一条
     * 新的 {@link #once(SmilSvg, double, double)}。</p>
     */
    public static SvgPlayback settled(SmilSvg svg, double startPhase, double endPhase) {
        SvgPlayback playback = new SvgPlayback(svg, false, startPhase, endPhase);
        playback.startNanos -= (long) (playback.span() * 1_000_000_000.0);
        return playback;
    }

    /**
     * 只给测试：造一条「已经走了 {@code elapsedSeconds}」的一次性时钟。
     *
     * <p>播完一轮要真等五秒的话，这个测试就没法进 CI。时间轴本来就是从
     * {@code startNanos} 推出来的，把起点往前挪即可，走的还是同一段代码。</p>
     */
    static SvgPlayback onceElapsed(SmilSvg svg, double elapsedSeconds) {
        SvgPlayback playback = new SvgPlayback(svg, false, 0, 1);
        playback.startNanos = System.nanoTime() - (long) (elapsedSeconds * 1_000_000_000.0);
        return playback;
    }

    /** 只给测试：同上，但是循环的那一种。 */
    static SvgPlayback loopingElapsed(SmilSvg svg, double elapsedSeconds) {
        SvgPlayback playback = new SvgPlayback(svg, true, 0, 1);
        playback.startNanos = System.nanoTime() - (long) (elapsedSeconds * 1_000_000_000.0);
        return playback;
    }

    /** 当前时间轴位置（秒）。 */
    public double time() {
        double elapsed = (System.nanoTime() - startNanos) / 1_000_000_000.0 + startPhase * duration;
        if (duration <= 0) {
            return 0;
        }
        if (repeat) {
            double progress = elapsed / duration;
            return (progress - Math.floor(progress)) * duration;
        }
        return Math.min(elapsed, endPhase * duration);
    }

    /** 这一轮走完了没有。循环着的永远没完。 */
    public boolean finished() {
        if (repeat) {
            return false;
        }
        double elapsed = (System.nanoTime() - startNanos) / 1_000_000_000.0 + startPhase * duration;
        return elapsed >= endPhase * duration;
    }

    /** 实际会播多久（秒）：从起播相位到停住相位那一段。 */
    public double span() {
        return (endPhase - startPhase) * duration;
    }

    /** 一整轮的时长（秒）。 */
    public double duration() {
        return duration;
    }
}
