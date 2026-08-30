package com.muoniumplayer.core.interfaces;

import com.muoniumplayer.core.rendering.RGBA;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaImages;
import com.muoniumplayer.render.skija.SkijaShapes;

import java.awt.*;

import static com.muoniumplayer.core.rendering.rendersystem.RenderSystem.DIVIDE_BY_255;


/**
 * 形状绘制门面。签名一个字没动，方法体从「自写着色器 + 固定管线四边形」换成 Skia 绘制。
 *
 * <p>这个接口被 16 个业务类 implements，方法都是 default 的，所以换实现不需要动任何调用方。
 * 几何定义集中在 {@link SkijaShapes}，这里只做参数换算。
 *
 * <p>底下有 8 个方法是纯计算（{@code evenAdd} / {@code getWidth} / {@code getHeight} /
 * 四个 {@code hexColor} / {@code reAlpha} / {@code isHovered}），一行都没改。
 *
 * @author MuoniumPlayer Contributors
 * @since 2023/12/17
 */
public interface SharedRenderingConstants {

    /**
     * 贴当前绑定的纹理，带圆角，uv 用「起点 + 跨度」表示。
     *
     * <p>对应旧的 {@code RQT_SHADER.drawSpecial}：它的 {@code u_scale} 直接就是这里的
     * {@code u} / {@code v}，也就是<b>跨度</b>而不是终点——和下面几个重载的 uv 语义不一样，
     * 这个区别必须保留，否则封面的取景框会错。
     */
    default void roundedRectTextured(double x, double y, double width, double height, double texX, double texY, double u, double v, double radius, double expand, float alpha) {
        SkijaImages.drawBoundRoundedTextured(x - expand, y - expand,
                width + expand * 2, height + expand * 2, radius,
                texX, texY, texX + u, texY + v, alpha);
    }

    default void roundedOutlineGradient(double x, double y, double width, double height, double radius, double thickness, Color bottomLeft, Color topLeft, Color bottomRight, Color topRight) {
        SkijaShapes.roundedOutlineGradient(x, y, width, height, radius, thickness,
                bottomLeft, topLeft, bottomRight, topRight);
    }

    default void roundedOutline(double x, double y, double width, double height, double radius, double thickness, Color outline) {
        SkijaShapes.roundedOutline(x, y, width, height, radius, thickness, SkijaGfx.argb(outline));
    }

    default void roundedOutline(double x, double y, double width, double height, double radius, double thickness, double expand, Color outline) {
        this.roundedOutline(x - expand, y - expand, width + expand * 2, height + expand * 2, radius, thickness, outline);
    }

    default void roundedRect(double x, double y, double width, double height, double radius, float r, float g, float b, float a) {
        SkijaShapes.roundedRect(x, y, width, height, radius, SkijaGfx.argb(r, g, b, a));
    }

    default void roundedRect(double x, double y, double width, double height, double radius, double expand, float r, float g, float b, float a) {
        roundedRect(x - expand, y - expand, width + expand * 2, height + expand * 2, radius, r, g, b, a);
    }

    default void roundedRect(double x, double y, double width, double height, double radius, int r, int g, int b, int a) {
        SkijaShapes.roundedRect(x, y, width, height, radius,
                (a << 24) | (r << 16) | (g << 8) | b);
    }

    default void roundedRect(double x, double y, double width, double height, double radius, int color) {

        float f = (color >> 24 & 255) * DIVIDE_BY_255;
        float f1 = (color >> 16 & 255) * DIVIDE_BY_255;
        float f2 = (color >> 8 & 255) * DIVIDE_BY_255;
        float f3 = (color & 255) * DIVIDE_BY_255;

        SkijaShapes.roundedRect(x, y, width, height, radius, SkijaGfx.argb(f1, f2, f3, f));
    }

    default void roundedRect(double x, double y, double width, double height, double radius, Color color) {
        SkijaShapes.roundedRect(x, y, width, height, radius, SkijaGfx.argb(color));
    }

    default void roundedRect(double x, double y, double width, double height, double radius, double expand, Color color) {
        SkijaShapes.roundedRect(x - expand, y - expand, width + expand * 2, height + expand * 2,
                radius, SkijaGfx.argb(color));
    }

    default void roundedRectTextured(double x, double y, double width, double height, double radius) {
        SkijaImages.drawBoundRoundedTextured(x, y, width, height, radius, 0, 0, 1, 1, 1f);
    }

    default void roundedRectTextured(double x, double y, double width, double height, double radius, float alpha) {
        SkijaImages.drawBoundRoundedTextured(x, y, width, height, radius, 0, 0, 1, 1, alpha);
    }

    default void roundedRectTextured(double x, double y, double width, double height, double texX, double texY, double u, double v, double radius) {
        SkijaImages.drawBoundRoundedTextured(x, y, width, height, radius, texX, texY, u, v, 1f);
    }

    default void roundedRectTextured(double x, double y, double width, double height, double texX, double texY, double u, double v, double radius, float alpha) {
        SkijaImages.drawBoundRoundedTextured(x, y, width, height, radius, texX, texY, u, v, alpha);
    }

    default void roundedRectGradientHorizontal(double x, double y, double width, double height, double radius, Color left, Color right) {
        SkijaShapes.roundedRectGradient(x, y, width, height, radius,
                SkijaGfx.argb(left), SkijaGfx.argb(right), true);
    }

    /**
     * 竖向渐变。传进来的颜色<b>包括 alpha</b>：最终透明度是插值出来的 alpha。
     *
     * <p>旧实现走的是四角渐变着色器（把两个颜色各喂给两个角）。两个颜色、一个方向的情况下
     * 双线性插值退化成线性插值，所以这里直接用 Skia 的线性渐变，结果是精确等价的，
     * 顺带省掉一次自定义着色器的调用。
     */
    default void roundedRectGradientVertical(double x, double y, double width, double height, double radius, Color top, Color bottom) {
        SkijaShapes.roundedRectGradient(x, y, width, height, radius,
                SkijaGfx.argb(top), SkijaGfx.argb(bottom), false);
    }

    default void drawGradientCornerLR(double x, double y, double width, double height, double radius, Color topLeft, Color bottomRight) {
        Color mixedColor = evenAdd(topLeft, bottomRight);
        SkijaShapes.roundedRectCornerGradient(x, y, width, height, radius,
                mixedColor, topLeft, bottomRight, mixedColor, 1f);
    }

    default void drawGradientCornerRL(double x, double y, double width, double height, double radius, Color bottomLeft, Color topRight) {
        Color mixedColor = evenAdd(bottomLeft, topRight);
        SkijaShapes.roundedRectCornerGradient(x, y, width, height, radius,
                bottomLeft, mixedColor, mixedColor, topRight, 1f);
    }

    /**
     * 两个颜色的等权中间色，供 {@link #drawGradientCornerLR} / {@link #drawGradientCornerRL}
     * 补出对角的两个角。
     *
     * <p>alpha 也要一起平均。三参数的 {@code Color} 构造器给出的是不透明色，于是两个半透明角之间
     * 会补出一个不透明的角，对角渐变上会出现一条突然变实的亮带。</p>
     */
    default Color evenAdd(Color a, Color b) {
        return new Color(a.getRed() / 2 + b.getRed() / 2, a.getGreen() / 2 + b.getGreen() / 2,
                a.getBlue() / 2 + b.getBlue() / 2, a.getAlpha() / 2 + b.getAlpha() / 2);
    }

    default void scaleAtPos(double posX, double posY, double scale) {
        SharedConstants.api.getGLStateManager().translate(posX, posY, 0);
        SharedConstants.api.getGLStateManager().scale(scale, scale, 1);
        SharedConstants.api.getGLStateManager().translate(-posX, -posY, 0);
    }

    default void rotateAtPos(double posX, double posY, float rotate) {
        SharedConstants.api.getGLStateManager().translate(posX, posY, 0);
        SharedConstants.api.getGLStateManager().rotate(rotate, 0, 0, 1);
        SharedConstants.api.getGLStateManager().translate(-posX, -posY, 0);
    }

    default double getWidth() {
        return RenderSystem.getWidth();
    }

    default double getHeight() {
        return RenderSystem.getHeight();
    }

    default int hexColor(int red, int green, int blue) {
        return RGBA.color(red, green, blue);
    }

    default int hexColor(int red, int green, int blue, int alpha) {
        return RGBA.color(red, green, blue, alpha);
    }

    default int hexColor(float r, float g, float b) {
        return RGBA.color(r, g, b);
    }

    default int hexColor(float r, float g, float b, float a) {
        return RGBA.color(r, g, b, a);
    }

    default int reAlpha(int color, float alpha) {
        if (alpha > 1) {
            alpha = 1;
        }

        if (alpha < 0) {
            alpha = 0;
        }
        return RGBA.color((color >> 16) & 0xFF, (color >> 8) & 0xFF, (color) & 0xFF, (int) (alpha * 255));
    }

    default boolean isHovered(double mouseX, double mouseY, double x, double y, double width, double height) {
        return RenderSystem.isHovered(mouseX, mouseY, x, y, width, height);
    }

}
