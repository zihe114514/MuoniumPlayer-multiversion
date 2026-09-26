package com.muoniumplayer.core.rendering.ui.widgets;

import com.muoniumplayer.core.rendering.Image;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.texture.ITextureObject;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.utils.Location;

import java.awt.Color;
import java.util.function.Supplier;

/**
 * 可点击、可按主题着色的纹理图标，具有与 {@link IconWidget} 相同的
 * 悬停/点击反馈。所提供的纹理是透明背景上的白色字形，因此该组件
 * 可安全地为每个播放器主题着色，而不会引入矩形图像背景。
 */
public class ThemedTextureIconWidget extends AbstractWidget<ThemedTextureIconWidget> {

    private final Supplier<Location> iconLocationSupplier;
    private final Supplier<String> fallbackIconSupplier;
    private final CFontRenderer fallbackFont;

    /**
     * 贴图往里缩多少逻辑像素再画。交互框、悬停底、点击底仍然吃满 bounds，只有画出来的那张图变小。
     *
     * <p>存在的理由是<b>画布留白不一致</b>。播放器那批 PNG 是 96×96 的画布，墨迹只占中间一小块，
     * 画满 20×20 的框时墨迹就比同一排相邻的图标字体大一圈，看上去就是「这一颗大一号、没对齐」。
     * 相邻字形（icomoon 那几颗）的墨迹是量得出来的，所以内缩量由调用方按邻居反推，
     * 见 {@code ControlsBar} 里播放模式那一颗。</p>
     */
    private double contentInset;

    private float hoverAlpha;
    private float clickAlpha;
    private boolean clicked;

    /** 设置贴图的内缩量。真正的夹取放在绘制时，因为 bounds 是宿主每帧重写的。 */
    public ThemedTextureIconWidget setContentInset(double inset) {
        this.contentInset = Math.max(0.0, inset);
        return this;
    }

    public ThemedTextureIconWidget(Supplier<Location> iconLocationSupplier,
                                    Supplier<String> fallbackIconSupplier,
                                    CFontRenderer fallbackFont,
                                    double x, double y, double width, double height) {
        this.iconLocationSupplier = iconLocationSupplier;
        this.fallbackIconSupplier = fallbackIconSupplier;
        this.fallbackFont = fallbackFont;
        this.setBounds(x, y, width, height);
        this.setShouldOverrideMouseCursor(true);
    }

    public ThemedTextureIconWidget(Location iconLocation, CFontRenderer fallbackFont,
                                    double x, double y, double width, double height) {
        this(() -> iconLocation, () -> null, fallbackFont, x, y, width, height);
    }

    public ThemedTextureIconWidget(Location iconLocation, String fallbackIcon, CFontRenderer fallbackFont,
                                    double x, double y, double width, double height) {
        this(() -> iconLocation, () -> fallbackIcon, fallbackFont, x, y, width, height);
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        api.getGLStateManager().disableAlpha();
        renderInteractionFeedback();

        Location iconLocation = iconLocationSupplier == null ? null : iconLocationSupplier.get();
        ITextureObject texture = null;
        if (iconLocation != null) {
            try {
                TextureManager.getInstance().bindTexture(iconLocation);
                // 资源包或过期的开发 jar 可能缺失某个
                // 可选纹理。保留既有字体图标回退，
                // 而不是在渲染播放器界面时崩溃。
                RenderSystem.linearFilter();
                texture = TextureManager.getInstance().getTexture(iconLocation);
            } catch (Throwable ignored) {
                // 资源包或过时的开发 jar 可能缺少某个
                // 可选纹理。保持已有的字体图标回退机制，
                // 而不是在渲染播放器界面时崩溃。
                texture = null;
            }
        }

        if (texture != null) {
            Color tint = this.getColor();
            api.getGLStateManager().color(
                    tint.getRed() / 255.0f,
                    tint.getGreen() / 255.0f,
                    tint.getBlue() / 255.0f,
                    tint.getAlpha() / 255.0f
            );
            // 内缩只作用在贴图上：字体兜底那条路径的墨迹本来就是参照物（相邻几颗都是图标字体），
            // 把同一份内缩加到它身上会把它压成比邻居还小。
            double inset = Math.min(contentInset, Math.min(getWidth(), getHeight()) * .5 - 1.0);
            Image.draw(texture, this.getX() + inset, this.getY() + inset,
                    this.getWidth() - inset * 2.0, this.getHeight() - inset * 2.0, Image.Type.NoColor);
            api.getGLStateManager().color(1.0f, 1.0f, 1.0f, 1.0f);
            return;
        }

        drawFallbackIcon();
    }

    private void renderInteractionFeedback() {
        int alpha = (int) (this.getAlpha() * 255.0f);
        double radius = this.getWidth() * 0.5;
        double x = this.getX() + this.getWidth() * 0.5 - radius;
        double y = this.getY() + this.getHeight() * 0.5 - radius;

        if (this.hoverAlpha != 0.0f) {
            roundedRect(x, y, radius * 2.0, radius * 2.0, radius - 0.5,
                    reAlpha(this.getHexColor(), Math.min(this.hoverAlpha, alpha)));
        }
        if (this.clickAlpha != 0.0f) {
            roundedRect(x, y, radius * 2.0, radius * 2.0, radius - 0.5,
                    reAlpha(this.getHexColor(), Math.min(this.clickAlpha, alpha)));
        }

        if (this.clicked) {
            this.clickAlpha = Interpolations.interpolate(this.clickAlpha, 40.0f * RenderSystem.DIVIDE_BY_255, 0.2f);
            if (Math.abs(this.clickAlpha - 40.0f * RenderSystem.DIVIDE_BY_255) < 0.05f) {
                this.clicked = false;
            }
        } else {
            this.clickAlpha = Interpolations.interpolate(this.clickAlpha, 0.0f, 0.2f);
        }

        this.hoverAlpha = Interpolations.interpolate(this.hoverAlpha,
                this.isHovering() ? 40.0f * RenderSystem.DIVIDE_BY_255 : 0.0f, 0.2f);
    }

    private void drawFallbackIcon() {
        if (fallbackFont == null || fallbackIconSupplier == null) {
            return;
        }
        String fallbackIcon = fallbackIconSupplier.get();
        if (fallbackIcon == null || fallbackIcon.isEmpty()) {
            return;
        }

        int width = fallbackFont.getStringWidth(fallbackIcon);
        double height = fallbackFont.getFontHeight();
        fallbackFont.drawString(fallbackIcon,
                this.getX() + this.getWidth() * 0.5 - width * 0.5,
                this.getY() + this.getHeight() * 0.5 - height * 0.5,
                this.getHexColor());
    }

    @Override
    public boolean onMouseClicked(double relativeX, double relativeY, int mouseButton) {
        this.clicked = true;
        return super.onMouseClicked(relativeX, relativeY, mouseButton);
    }
}
