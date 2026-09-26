package com.muoniumplayer.core.rendering.ui.widgets;

import com.muoniumplayer.core.rendering.FoliaLucideIcons;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;

import java.util.function.Supplier;

/**
 * 一枚 lucide 矢量图标按钮，带与 {@link IconWidget}、{@link ThemedTextureIconWidget} 同一套
 * 悬停/点击反馈。
 *
 * <p><b>为什么用矢量而不是图标字体。</b>字体每一档字号是烘死的，想做到「墨迹高 = 边长 × 某个比例」
 * 只能在现有几档里挑最接近的那一档；本仓库那几套私用区字体最小一档的墨迹已经占到边长的 62% 以上，
 * 摆进按 52% 对齐的那一列就是明显大一号（{@link PlayerQueueIcons} 里那三枚）。矢量按盒子现算，
 * 要多大多大，笔画端点还是圆头。</p>
 *
 * <p><b>为什么留一个字体兜底。</b>{@link FoliaLucideIcons#draw} 在 Skia 帧外、或路径表里没有这一枚
 * 时返回 {@code false} 而不是抛异常。图标画成一块空白比画成一个略不对味的字形糟糕得多，所以退回
 * 构造时交给它的那枚字体字形 —— 这一套与 {@link ThemedTextureIconWidget} 完全同构：贴图/矢量优先，
 * 字体兜底。</p>
 */
public class LucideIconWidget extends AbstractWidget<LucideIconWidget> {

    /**
     * 画给 {@link FoliaLucideIcons#draw} 的那格边长相对控件盒子的比例。
     *
     * <p>{@code x} 这类图标的墨迹在 24 格网里占 12 格，加上两端各半个圆头笔画（笔画宽 2）就是
     * 14/24 ≈ .583。取 .9 是为了让墨迹落在盒子边长的 ~52% 上（.9 × .583 ≈ .525），也就是
     * {@link PlayerQueueIcons} 里那三枚控件图标的同一档视觉尺寸。换一枚笔画更满的图标（比如
     * 实心盘类）时要按它自己的墨迹比例调，别指望这个数万能。</p>
     */
    private static final double DEFAULT_DRAW_SCALE = .9;

    private final Supplier<String> nameSupplier;
    private final Supplier<String> fallbackGlyphSupplier;
    private final CFontRenderer fallbackFont;

    private double drawScale = DEFAULT_DRAW_SCALE;
    /** 描边宽度，单位为 24 格网里的格数；留 0 表示用图标库自带那一档。 */
    private double strokeWidth;
    private float hoverAlpha;
    private float clickAlpha;
    private boolean clicked;

    public LucideIconWidget(Supplier<String> nameSupplier, Supplier<String> fallbackGlyphSupplier,
                            CFontRenderer fallbackFont,
                            double x, double y, double width, double height) {
        this.nameSupplier = nameSupplier;
        this.fallbackGlyphSupplier = fallbackGlyphSupplier;
        this.fallbackFont = fallbackFont;
        this.setBounds(x, y, width, height);
        this.setShouldOverrideMouseCursor(true);
    }

    /** 改那一格相对盒子的比例，用于墨迹填满程度和 {@code x} 明显不同的图标。 */
    public LucideIconWidget setDrawScale(double scale) {
        this.drawScale = Math.max(.05, scale);
        return this;
    }

    /** 指定描边宽度（24 格网里的格数）。≤0 时沿用图标库那一档。 */
    public LucideIconWidget setStrokeWidth(double strokeWidth) {
        this.strokeWidth = strokeWidth;
        return this;
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        api.getGLStateManager().disableAlpha();
        renderInteractionFeedback();

        double size = Math.min(this.getWidth(), this.getHeight()) * this.drawScale;
        String name = nameSupplier == null ? null : nameSupplier.get();
        if (name != null && !name.isEmpty() && FoliaLucideIcons.draw(name,
                this.getX() + this.getWidth() * .5, this.getY() + this.getHeight() * .5,
                size, this.getHexColor(), this.strokeWidth)) {
            return;
        }
        drawFallbackGlyph();
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

    private void drawFallbackGlyph() {
        if (this.fallbackFont == null || this.fallbackGlyphSupplier == null) {
            return;
        }
        String glyph = this.fallbackGlyphSupplier.get();
        if (glyph == null || glyph.isEmpty()) {
            return;
        }
        int width = this.fallbackFont.getStringWidth(glyph);
        double height = this.fallbackFont.getFontHeight();
        this.fallbackFont.drawString(glyph,
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
