package com.muoniumplayer.core.rendering;

import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.texture.DynamicTexture;
import com.muoniumplayer.core.rendering.texture.ITextureObject;
import com.muoniumplayer.core.settings.ClientSettings;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaImages;

/**
 * 贴图绘制门面。签名全部保持不变，绘制改由 Skia 完成。
 *
 * <h2>纹理仍然是真实的 GL 纹理</h2>
 * 「解码图片、把像素上传到显存」不是 UI 渲染而是资源加载，这条链路
 * （{@code TextureManager} / {@code DynamicTexture} / 封面下载）没有旧固定管线的包袱，
 * 重写它只会凭空引入 bug。所以纹理照旧由现有代码上传，Skia 通过
 * {@code Image.borrowTextureFrom} 借来采样，见 {@link SkijaImages}。
 *
 * <h2>Type 的语义</h2>
 * {@code Type.Normal} 把当前颜色重置成不透明白（也就是不做调制），
 * {@code Type.NoColor} 保留调用方之前设的当前颜色，让它去调制贴图——
 * 这是旧固定管线 {@code GL_MODULATE} 的行为，界面上大量用它来给图标染色和整体淡入淡出。
 * Skia 侧的等价物是 {@code BlendMode.MODULATE} 的 ColorFilter。
 *
 * <h2>uv 换算</h2>
 * 旧实现给的是像素级的 {@code u/v} 加纹理尺寸，着色器里除一下变成归一化坐标。
 * 这里在 Java 侧做同样的除法。{@code Flipped*} 系列在旧实现里是把四个顶点的 uv 换位，
 * 换成 Skia 之后就是把源矩形的左右或上下边界对调。
 */
public class Image implements SharedConstants {

    private static void renderDbgInfo(String str, double x, double y, double width, double height) {

        // show layout
        RenderSystem.drawOutLine(x, y, width, height, 0.5, 0x40FF0000);

        double lineLength = Math.min(8, Math.min(width * .25, height * .25));
        double lineSize = 1;
        int lineColor = 0x400090FF;
        // left top
        Rect.draw(x, y, lineLength, lineSize, lineColor);
        Rect.draw(x, y, lineSize, lineLength, lineColor);

        // right top
        Rect.draw(x + width - lineLength, y, lineLength, lineSize, lineColor);
        Rect.draw(x + width - lineSize, y, lineSize, lineLength, lineColor);

        // left bottom
        Rect.draw(x, y + height - lineLength, lineSize, lineLength, lineColor);
        Rect.draw(x, y + height - lineSize, lineLength, lineSize, lineColor);

        // right bottom
        Rect.draw(x + width - lineLength, y + height - lineSize, lineLength, lineSize, lineColor);
        Rect.draw(x + width - lineSize, y + height - lineLength, lineSize, lineLength, lineColor);

        CFontRenderer fr = FontManager.pf18;

        double sw = fr.getStringWidthD(str);
        double dbgX = Math.max(0, Math.min(RenderSystem.getWidth() - sw, x));
        double dbgY = Math.max(0, Math.min(RenderSystem.getHeight() - fr.getFontHeight(), y));
        fr.drawStringWithShadow(str, dbgX, dbgY, -1);
    }

    /**
     * {@code Type.Normal} 意味着「不要调制」，也就是把当前颜色恢复成不透明白。
     * 这个副作用是旧实现留下的、被调用方依赖的行为，必须保留。
     */
    private static int resolveTint(Type type) {
        if (type == Type.Normal) {
            api.getGLStateManager().color(1, 1, 1, 1);
        }
        return SkijaGfx.color();
    }

    /** 取纹理，没有就发起一次加载，并返回 GL 纹理 id（-1 表示这一帧还画不了）。 */
    private static int resolveTexture(Location img) {
        ITextureObject textureObj = TextureManager.getInstance().getTexture(img);
        if (textureObj != null) {
            return textureObj.getGlTextureId();
        }
        TextureManager.getInstance().loadTexture(img, new DynamicTexture(img));
        return -1;
    }

    private static void dbg(Location img, double x, double y, double width, double height) {
        if (!ClientSettings.SHOW_WIDGET_BOUNDARY) {
            return;
        }
        int beginIndex = img.getResourcePath().indexOf("/");
        String str = img.getResourcePath().substring(beginIndex == -1 ? 0 : beginIndex + 1);
        renderDbgInfo(str, x, y, width, height);
    }

    public static void draw(Location img, double x, double y, double width, double height) {
        draw(img, x, y, width, height, Type.Normal);
    }

    public static void draw(Location img, double x, double y, double width, double height, Type type) {
        draw(img, x, y, width, height, width, height, type);
    }

    public static void draw(ITextureObject img, double x, double y, double width, double height) {
        draw(img, x, y, width, height, Type.Normal);
    }

    public static void draw(ITextureObject img, double x, double y, double width, double height, Type type) {
        draw(img, x, y, width, height, width, height, type);
    }

    public static void drawLinear(Location img, double x, double y, double width, double height, Type type) {
        int tint = resolveTint(type);
        int glId = resolveTexture(img);
        if (glId > 0) {
            SkijaGfx.setBoundTexture(glId);
            RenderSystem.linearFilter();
            SkijaImages.hintSize(glId, (int) Math.ceil(width), (int) Math.ceil(height));
            SkijaImages.drawTextured(x, y, width, height, 0, 0, 1, 1,
                    glId, (int) Math.ceil(width), (int) Math.ceil(height), tint, true);
        }
        dbg(img, x, y, width, height);
    }

    public static void drawNearest(Location img, double x, double y, double width, double height, Type type) {
        int tint = resolveTint(type);
        int glId = resolveTexture(img);
        if (glId > 0) {
            SkijaGfx.setBoundTexture(glId);
            RenderSystem.nearestFilter();
            SkijaImages.hintSize(glId, (int) Math.ceil(width), (int) Math.ceil(height));
            SkijaImages.drawTextured(x, y, width, height, 0, 0, 1, 1,
                    glId, (int) Math.ceil(width), (int) Math.ceil(height), tint, false);
        }
        dbg(img, x, y, width, height);
    }

    public static void drawLinearFlippedX(Location img, double x, double y, double width, double height, Type type) {
        int tint = resolveTint(type);
        int glId = resolveTexture(img);
        if (glId > 0) {
            SkijaGfx.setBoundTexture(glId);
            RenderSystem.linearFilter();
            SkijaImages.hintSize(glId, (int) Math.ceil(width), (int) Math.ceil(height));
            SkijaImages.drawTextured(x, y, width, height, 1, 0, 0, 1,
                    glId, (int) Math.ceil(width), (int) Math.ceil(height), tint, true);
        }
        dbg(img, x, y, width, height);
    }

    public static void draw(ITextureObject img, double x, double y, double width, double height, double tWidth, double tHeight, Type type) {
        int tint = resolveTint(type);
        int glId = img.getGlTextureId();
        SkijaGfx.setBoundTexture(glId);
        drawModalRectWithCustomSizedTexture(x, y, 0, 0, tWidth, tHeight, width, height);

        if (ClientSettings.SHOW_WIDGET_BOUNDARY) {
            renderDbgInfo("TexID: " + glId, x, y, width, height);
        }
    }

    public static void draw(Location img, double x, double y, double width, double height, double tWidth, double tHeight, Type type) {
        resolveTint(type);
        int glId = resolveTexture(img);
        if (glId > 0) {
            SkijaGfx.setBoundTexture(glId);
            drawModalRectWithCustomSizedTexture(x, y, 0, 0, tWidth, tHeight, width, height);
        }
        dbg(img, x, y, width, height);
    }

    public static void draw(int textureId, double x, double y, double width, double height, Type type) {
        resolveTint(type);
        SkijaGfx.setBoundTexture(textureId);
        drawModalRectWithCustomSizedTexture(x, y, 0, 0, width, height, width, height);

        if (ClientSettings.SHOW_WIDGET_BOUNDARY)
            renderDbgInfo("TexID: " + textureId, x, y, width, height);
    }

    /**
     * 把纹理上以像素计的一块 {@code (u, v, width, height)} 画到屏幕的
     * {@code (x, y, width, height)}。
     *
     * <p>纹理尺寸顺手登记给 {@link SkijaImages}：另有一批入口（圆角贴图）只给了绘制尺寸、
     * 没给纹理尺寸，靠这里攒下来的记录才能建源矩形。
     */
    public static void drawModalRectWithCustomSizedTexture(double x, double y, double u, double v, double width, double height, double textureWidth, double textureHeight) {
        blit(x, y, width, height, u, v, textureWidth, textureHeight, false, false);
    }

    public static void drawLinearFlippedY(Location img, double x, double y, double width, double height, Type type) {
        resolveTint(type);
        ITextureObject textureObj = TextureManager.getInstance().getTexture(img);
        if (textureObj != null) {
            api.getGLStateManager().bindTexture(textureObj.getGlTextureId());
            textureObj.linearFilter();
            drawModalRectWithCustomSizedTextureFlippedY(x, y, 0, 0, width, height, width, height);
        }
    }

    public static void drawLinearFlippedXAndY(Location img, double x, double y, double width, double height, Type type) {
        resolveTint(type);
        ITextureObject textureObj = TextureManager.getInstance().getTexture(img);
        if (textureObj != null) {
            api.getGLStateManager().bindTexture(textureObj.getGlTextureId());
            textureObj.linearFilter();
            drawModalRectWithCustomSizedTextureFlippedXAndY(x, y, 0, 0, width, height, width, height);
        }
    }

    public static void drawModalRectWithCustomSizedTextureFlippedX(double x, double y, double u, double v, double width, double height, double textureWidth, double textureHeight) {
        blit(x, y, width, height, u, v, textureWidth, textureHeight, true, false);
    }

    public static void drawModalRectWithCustomSizedTextureFlippedY(double x, double y, double u, double v, double width, double height, double textureWidth, double textureHeight) {
        blit(x, y, width, height, u, v, textureWidth, textureHeight, false, true);
    }

    /**
     * 竖向渐隐的贴图：顶边一个透明度、底边另一个。
     *
     * <p>注意这一支和不带 alpha 的同名重载一样是<b>上下翻转</b>的（{@code FlippedY}），
     * 所以 {@code topAlpha} 对应的是屏幕上的顶边，不是纹理的顶边。
     * 旧实现最后还会把当前颜色复位成不透明白，这个副作用一并保留。
     */
    public static void drawModalRectWithCustomSizedTextureFlippedY(double x, double y, double u, double v, double width, double height, double textureWidth, double textureHeight, float topAlpha, float bottomAlpha) {
        int glId = SkijaGfx.boundTexture();
        if (glId > 0 && textureWidth > 0 && textureHeight > 0) {
            SkijaImages.hintSize(glId, (int) Math.ceil(textureWidth), (int) Math.ceil(textureHeight));
            double u0 = u / textureWidth;
            double u1 = (u + width) / textureWidth;
            double v0 = v / textureHeight;
            double v1 = (v + height) / textureHeight;
            SkijaImages.drawTexturedVerticalFade(x, y, width, height,
                    u0, v1, u1, v0,
                    glId, (int) Math.ceil(textureWidth), (int) Math.ceil(textureHeight),
                    SkijaGfx.linearFiltering(), topAlpha, bottomAlpha);
        }
        api.getGLStateManager().color(1, 1, 1, 1);
    }

    public static void drawModalRectWithCustomSizedTextureFlippedXAndY(double x, double y, double u, double v, double width, double height, double textureWidth, double textureHeight) {
        blit(x, y, width, height, u, v, textureWidth, textureHeight, true, true);
    }

    public static void drawModalRectWithCustomSizedTextureRotate90R(double x, double y, double u, double v, double width, double height, double textureWidth, double textureHeight) {
        rotatedBlit(x, y, width, height, u, v, textureWidth, textureHeight, 90f);
    }

    public static void drawModalRectWithCustomSizedTextureRotate90L(double x, double y, double u, double v, double width, double height, double textureWidth, double textureHeight) {
        rotatedBlit(x, y, width, height, u, v, textureWidth, textureHeight, -90f);
    }

    public static void draw(double x, double y, double width, double height, Type type) {
        resolveTint(type);
        drawModalRectWithCustomSizedTexture(x, y, 0, 0, width, height, width, height);
    }

    // ------------------------------------------------------------------
    // 内部：像素 uv → 归一化 uv，然后交给 Skia
    // ------------------------------------------------------------------

    private static void blit(double x, double y, double width, double height,
                             double u, double v, double textureWidth, double textureHeight,
                             boolean flipX, boolean flipY) {
        int glId = SkijaGfx.boundTexture();
        if (glId <= 0 || textureWidth <= 0 || textureHeight <= 0) {
            return;
        }
        int texW = (int) Math.ceil(textureWidth);
        int texH = (int) Math.ceil(textureHeight);
        SkijaImages.hintSize(glId, texW, texH);

        double u0 = u / textureWidth;
        double u1 = (u + width) / textureWidth;
        double v0 = v / textureHeight;
        double v1 = (v + height) / textureHeight;
        if (flipX) {
            double t = u0;
            u0 = u1;
            u1 = t;
        }
        if (flipY) {
            double t = v0;
            v0 = v1;
            v1 = t;
        }
        SkijaImages.drawTextured(x, y, width, height, u0, v0, u1, v1,
                glId, texW, texH, SkijaGfx.color(), SkijaGfx.linearFiltering());
    }

    private static void rotatedBlit(double x, double y, double width, double height,
                                    double u, double v, double textureWidth, double textureHeight,
                                    float degrees) {
        int glId = SkijaGfx.boundTexture();
        if (glId <= 0 || textureWidth <= 0 || textureHeight <= 0) {
            return;
        }
        int texW = (int) Math.ceil(textureWidth);
        int texH = (int) Math.ceil(textureHeight);
        SkijaImages.hintSize(glId, texW, texH);
        SkijaImages.drawTexturedRotated(x, y, width, height,
                u / textureWidth, v / textureHeight,
                (u + width) / textureWidth, (v + height) / textureHeight,
                glId, texW, texH, SkijaGfx.color(), SkijaGfx.linearFiltering(), degrees);
    }

    public enum Type {
        NoColor, Normal
    }
}
