package com.muoniumplayer.core.rendering;

import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.render.skija.SkijaClip;

/**
 * 圆角裁剪门面。方法名保留了「Stencil」的历史称谓，实现已换成 Skia 的裁剪栈。
 *
 * <h2>旧实现为什么这么复杂</h2>
 * 模板缓冲裁剪要在同一块画布上分两遍：第一遍「只写模板不写颜色」把裁剪形状印进模板缓冲，
 * 第二遍才按模板值测试着画内容；嵌套靠 {@code GL_INCR} 的计数值实现。围绕这个流程，
 * 旧实现还得保存/恢复颜色遮罩、深度遮罩、深度测试、模板写掩码，
 * 处理「别处偷偷改了真实 GL 状态导致缓存失真」，以及形状画一半抛异常时的回滚——
 * 一共 260 行，而且它<b>依赖十几项全局 GL 状态在整条绘制链上保持一致</b>，很容易被打破。
 *
 * <h2>换成 Skia 之后</h2>
 * 裁剪是 canvas 状态的一部分，{@code save()/restore()} 天然带嵌套和自动求交，
 * 没有全局状态可失效、没有 8 位计数上限、抗锯齿也是解析求解的（模板法只能是硬边，
 * 圆角上会漏出一圈锯齿）。所有实际逻辑都在 {@link SkijaClip} 里，这里只是门面。
 *
 * <h2>为什么有几个方法变成了空实现</h2>
 * {@code enableStencilTest} / {@code disableStencilTest} / {@code restoreActiveClip} /
 * {@code initialize} 都是围绕「全局 GL 开关」和「状态可能失真」这两件事存在的。
 * Skia 里裁剪就挂在 canvas 上，不存在「暂时关掉再恢复」，也不存在需要重新断言的时刻。
 *
 * <p>顺带说明：{@code restoreActiveClip} 的 javadoc 记录过一个具体缺陷——
 * 离屏着色器通道会关掉模板测试，回到主画布后如果没重新断言父级裁剪，
 * 全尺寸的四边形就会盖住圆角容器的透明角，表现为「圆角外面套了一层直角边框」。
 * 在 canvas 裁剪栈下这个失效窗口根本不存在。
 */
public class StencilClipManager implements SharedConstants {

    /** 当前是否有生效中的裁剪层。 */
    public static boolean stencilClipping() {
        return SkijaClip.hasActiveClip();
    }

    /**
     * 受控空实现：Skia 的裁剪不是全局开关，无法「暂时关掉」。
     *
     * <p>旧调用点全是「我要去离屏帧缓冲画自己的东西，先把裁剪关掉」，
     * 而离屏帧缓冲已经整体换成 {@code canvas.saveLayer}——图层继承外层裁剪本来就是对的行为。
     */
    public static void disableStencilTest() {
    }

    /** 受控空实现，理由同 {@link #disableStencilTest()}。 */
    public static void enableStencilTest() {
    }

    public static boolean isStencilEnabled() {
        return SkijaClip.hasActiveClip();
    }

    /** 受控空实现：不再需要清模板缓冲。 */
    public static void initialize() {
    }

    /**
     * 开始一层裁剪，随后由调用方自己画形状，再调 {@link #updateClip()} 生效。
     * 两段式写法保留兼容，推荐用 {@link #beginClip(Runnable)}。
     */
    public static void beginClip() {
        SkijaClip.beginClip();
    }

    /** 结束形状录制并让裁剪生效。 */
    public static void updateClip() {
        SkijaClip.updateClip();
    }

    /**
     * 开一层裁剪，裁剪范围由 {@code drawClipShape} 画出来的形状决定。
     *
     * <p>回调会被<b>照原样执行一次</b>，但此时形状绘制方法处于「录制」模式，
     * 只登记几何、不落到画布上（见 {@link SkijaClip}）。因此调用方一个字都不用改。
     */
    public static void beginClip(Runnable drawClipShape) {
        SkijaClip.beginClip(drawClipShape);
    }

    public static void endClip() {
        SkijaClip.endClip();
    }

    /**
     * 受控空实现。
     *
     * <p>旧实现需要它，是因为离屏通道会把模板测试关掉、把颜色遮罩改掉，
     * 回来之后必须重新断言父级裁剪。Skia 的裁剪跟着 canvas 的 save 栈走，
     * 中间无论画了什么都不会丢，没有需要「恢复」的时刻。
     */
    public static void restoreActiveClip() {
    }

    /** 受控空实现：没有全局模板状态需要关。 */
    public static void disable() {
    }

    /** 受控空实现：裁剪栈由宿主层每帧重置（见 {@code SkijaHost.beginFrame}）。 */
    public static void clear() {
    }
}
