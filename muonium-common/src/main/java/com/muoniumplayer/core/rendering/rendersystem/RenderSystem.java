package com.muoniumplayer.core.rendering.rendersystem;

import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.compat.lwjgl2.Display;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaShapes;
import com.muoniumplayer.api.OpenAPI;
import com.muoniumplayer.api.events.EventRender2D;
import com.muoniumplayer.api.interfaces.EventHandler;
import com.muoniumplayer.api.interfaces.render.GLStateManager;
import com.muoniumplayer.api.interfaces.render.WindowResolution;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.rendering.RGBA;
import com.muoniumplayer.core.rendering.Rect;

import java.awt.*;
import java.util.UUID;

/**
 * @since 4/15/2023 8:47 PM
 */
public class RenderSystem implements SharedConstants {
    public static final Object ASYNC_LOCK = new Object();
    public static final float DIVIDE_BY_255 = 0.003921568627451F;
    @Getter
    @Setter
    private static double frameDeltaTime = 0;


    /**
     * 采样方式。
     *
     * <p>不再改 GL 的纹理参数：Skia 的采样方式是<b>每次绘制</b>指定的（{@code SamplingMode}），
     * 不是挂在纹理对象上的全局状态。改 GL 参数对 Skia 的绘制没有任何影响，
     * 反而会在它的绘制序列中间插入裸 GL 调用把它的状态缓存搞乱。
     * 所以这里只记下调用方的意图，由 {@code SkijaImages} 在绘制时应用。
     *
     * <p>{@code mipmap} 参数被丢弃：这是一个 2D 界面层，贴图永远是 1:1 或轻微缩放，
     * 从来没有需要多级渐远纹理的场合，旧代码传进来的也一直是 false。
     */
    public static void setBlurMipmapDirect(boolean blur, boolean mipmap) {
        SkijaGfx.setLinearFiltering(blur);
    }

    public static void linearFilter() {
        SkijaGfx.setLinearFiltering(true);
    }

    public static void nearestFilter() {
        SkijaGfx.setLinearFiltering(false);
    }

    private static int scaleFactor, width, height;

    public static double getScaleMultiplier() {
        return getScaleFactor() * 0.5;
    }

    @SneakyThrows
    public static int getScaleFactor() {
        return scaleFactor;
    }

    static {
        api.registerEvent(new EventHandler() {
            @Override
            public void onRender2D(EventRender2D event) {
                WindowResolution wr = event.getWindowResolution();

                scaleFactor = wr.getScaleFactor();
                width = wr.getWidth();
                height = wr.getHeight();
            }
        });
    }
    
    public static boolean FIXED_SCALE = false;

    /**
     * 重新读取当前 ScaledResolution（响应窗口尺寸 / GUI Scale 变化）。
     * width/height/scaleFactor 仅在 RenderGameOverlayEvent（游戏内 HUD 渲染）里刷新，
     * 而 GuiScreen（NCMScreen/CoverflowOverlay）打开期间该事件不触发，导致字段停留在上一次
     * 游戏帧的值。窗口 resize / 改 GUI Scale 后若不在此处刷新，屏幕坐标映射与布局会错位。
     * 各 GuiScreen 应在 drawScreen 开头调用一次。
     */
    public static void refreshResolution() {
        com.muoniumplayer.compat.mc.ScaledResolution sr = new com.muoniumplayer.compat.mc.ScaledResolution(
                (Object) null);
        scaleFactor = sr.getScaleFactor();
        width = sr.getScaledWidth();
        height = sr.getScaledHeight();
    }

    public static double getWidthNotScaled() {
        return width;
    }

    public static double getHeightNotScaled() {
        return height;
    }

    public static double getWidth() {
        if (!FIXED_SCALE) {
            return width;
        }

        return getFixedWidth() * .5;
    }

    public static double getHeight() {
        if (!FIXED_SCALE) {
            return height;
        }

        return getFixedHeight() * .5;
    }

    public static double getFixedWidth() {
        return Math.min(Display.getWidth(), 1920);
    }

    public static double getFixedHeight() {
        double scaleFactor = Display.getWidth() / getFixedWidth();
        return Display.getHeight() / scaleFactor;
    }

    /**
     * 恒定的布局轴宽高（不受 {@link #FIXED_SCALE} 当前状态影响）。
     *
     * <p>布局轴 = 「物理窗口归一到 ≤1920 宽后的一半」，一单位对应的物理像素只随真实窗口大小
     * 变化、与 Minecraft 的 GUI Scale 档位完全无关。{@link #getWidth()} 的返回值取决于调用时
     * FIXED_SCALE 的状态（HUD 回调里是 GUI 逻辑宽、NCMScreen 里是布局宽），跨两种上下文共享
     * 几何的代码（HUD widget 的位置/尺寸、编辑器的预览与命中）必须用这一对访问器，否则
     * 「控件大小跟随游戏 GUI 倍率」「预览与实物对不上」都会回来。</p>
     */
    public static double getLayoutWidth() {
        return getFixedWidth() * .5;
    }

    public static double getLayoutHeight() {
        return getFixedHeight() * .5;
    }

    /**
     * 设置「当前颜色」。位序是 ARGB：f 是 alpha，f1/f2/f3 依次是 R/G/B。
     * 这个顺序看着别扭但不能动——45 个文件按这个约定传色。
     */
    public static void color(int color) {
        float f = (color >> 24 & 255) * DIVIDE_BY_255;
        float f1 = (color >> 16 & 255) * DIVIDE_BY_255;
        float f2 = (color >> 8 & 255) * DIVIDE_BY_255;
        float f3 = (color & 255) * DIVIDE_BY_255;
        api.getGLStateManager().color(f1, f2, f3, f);
    }
    /**
     * 直角矩形填充。
     *
     * <p>两点可能是反的（左右/上下颠倒），调用方确实这么用，交换逻辑保留在
     * {@link SkijaShapes#rect} 里。
     *
     * <p>它还有一个<b>副作用</b>必须保留：旧实现会把 {@code color} 设成「当前颜色」并留在那里，
     * 后续贴图与文字绘制如果没自己设色就会沿用它。有调用方依赖这个行为，
     * 所以这里照旧先 {@link #color(int)} 再画。
     */
    public static void drawRect(double left, double top, double right, double bottom, int color) {
        RenderSystem.color(color);
        SkijaShapes.rect(left, top, right, bottom, color);
    }

    public static void resetColor() {
        RenderSystem.color(-1);
    }

    /**
     * 横向两色渐变（直角矩形）。
     *
     * <p>旧实现是一个 {@code GL_QUADS} 顶点色插值。两个颜色、单一方向的插值和
     * Skia 的线性渐变是精确等价的，所以直接用 {@code Shader.makeLinearGradient}。
     */
    public static void drawGradientRectLeftToRight(final double left, final double top, final double right, final double bottom, final int startColor, final int endColor) {
        SkijaShapes.gradientRect(left, top, right, bottom, startColor, endColor, true);
    }

    public static boolean isHovered(double mouseX, double mouseY, double startX, double startY, double width, double height) {

        if (width < 0) {
            width = -width;
            startX -= width;
        }

        if (height < 0) {
            height = -height;
            startY -= height;
        }

        return mouseX >= startX && mouseY >= startY && mouseX <= startX + width && mouseY <= startY + height;
    }

    public static boolean isHovered(double mouseX, double mouseY, double startX, double startY, double width, double height, double shrink) {
        return RenderSystem.isHovered(mouseX, mouseY, startX + shrink, startY + shrink, width - shrink * 2, height - shrink * 2);
    }

    /**
     * alpha 阈值丢弃。
     *
     * <p>受控空实现。这是固定管线为了省下混合而做的「alpha 低于阈值就整个丢掉」，
     * Skia 直接做正确的 alpha 混合，没有这个概念也不需要它。
     * 保留方法是因为有调用方在用，那属于业务代码。
     */
    public static void setAlphaLimit(float limit) {
    }

    /**
     * 记住「当前纹理」，供随后的贴图绘制使用。
     *
     * <p>不真的发 {@code glBindTexture}：Skia 自己管纹理绑定，
     * 在它的绘制序列中间插入裸 GL 绑定会让它的状态缓存失真。
     */
    public static void bindTexture(int textureId) {
        SkijaGfx.setBoundTexture(textureId);
    }

    public static double getMouseX() {
        return Mouse.getX() * RenderSystem.getScaleFactor();
    }

    public static double getMouseY() {
        return Mouse.getY() * RenderSystem.getScaleFactor();
    }

    /**
     * 以 (posX, posY) 为原点缩放。
     *
     * <p>{@code scale(scale, scale, 2)} 的第三个参数是 z 方向的缩放，在 2D 画布上没有作用，
     * 原样保留调用形态（GLStateManager 的实现会丢弃 z）。
     */
    public static void translateAndScale(double posX, double posY, double scale) {

        api.getGLStateManager().translate(posX, posY, 0);
        api.getGLStateManager().scale(scale, scale, 2);
        api.getGLStateManager().translate(-posX, -posY, 0);

    }

    public static void drawOutLine(double x, double y, double width, double height, double thickness, int color) {
//        RenderSystem.color(color);

        Rect.draw(x - thickness, y - thickness, width + thickness * 2, thickness, color, Rect.RectType.EXPAND);
        Rect.draw(x - thickness, y - thickness, thickness, height + thickness, color, Rect.RectType.EXPAND);
        Rect.draw(x + width, y - thickness, thickness, height + thickness, color, Rect.RectType.EXPAND);
        Rect.draw(x - thickness, y + height, width + thickness * 2, thickness, color, Rect.RectType.EXPAND);
    }

    public static Color getOppositeColor(Color colorIn) {
        return new Color(255 - colorIn.getRed(), 255 - colorIn.getGreen(), 255 - colorIn.getBlue(), colorIn.getAlpha());
    }

    public static int getOppositeColorHex(int colorHex) {
        return getOppositeColor(new Color(colorHex, true)).getRGB();
    }

    public static int cRange(int c) {
        if (c < 0) {
            c = 0;
        }

        if (c > 255) {
            c = 255;
        }

        return c;
    }

    public static int reAlpha(int color, float alpha) {
        if (alpha > 1) {
            alpha = 1;
        }

        if (alpha < 0) {
            alpha = 0;
        }
        return RGBA.color((color >> 16) & 0xFF, (color >> 8) & 0xFF, (color) & 0xFF, (int) (alpha * 255));
    }

}
