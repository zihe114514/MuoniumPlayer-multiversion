package com.muoniumplayer.core.rendering;

import com.muoniumplayer.render.skija.SkijaClip;

/**
 * 矩形硬裁剪，跟随当前变换。
 *
 * <h2>旧实现</h2>
 * 用 {@code gluProject} 把矩形四角按当前 modelview / projection / viewport 手工投影到
 * 窗口坐标，再和上一层裁剪盒求交，最后 {@code glScissor}。
 * 它最脆的一环是<b>回读 GL 矩阵</b>：只要绘制链上任何一处改了矩阵而它没预料到，
 * 算出来的裁剪盒就是错的。「下一首播放」抽屉收起时溢出播放器边界，
 * 就是这一类问题——那个动画在设计上依赖裁剪把滑出内容区的部分吃掉。
 *
 * <h2>现在</h2>
 * {@code canvas.clipRect} 本身就跟随当前变换，并自动与已有裁剪求交，
 * 上面整套换算全部消失，连带消掉了对回读 GL 矩阵的依赖。
 *
 * <p>抗锯齿关掉，对齐旧的 {@code glScissor} 的逐像素硬边界：滚动视口的上下边沿用硬边更干净，
 * 开抗锯齿会在边界上留一行半透明残影。
 */
public final class ScissorClipManager {

    private ScissorClipManager() {
    }

    public static void begin(double x, double y, double width, double height) {
        SkijaClip.scissorBegin(x, y, width, height);
    }

    public static void end() {
        SkijaClip.scissorEnd();
    }
}
