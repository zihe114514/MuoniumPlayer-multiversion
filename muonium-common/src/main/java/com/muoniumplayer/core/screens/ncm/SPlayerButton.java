package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.rendering.SPlayerIcons;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * SPlayer 的 {@code SButton}：圆形图标按钮与圆角胶囊按钮两种形态，四种外观变体。
 *
 * <ul>
 *   <li>{@link Variant#GHOST}：无底，悬停 {@code bg-on-surface/8}（播放栏图标、侧栏小按钮）。</li>
 *   <li>{@link Variant#TERTIARY}：{@code bg-on-surface/5}，悬停 /10（顶栏后退/前进、设置）。</li>
 *   <li>{@link Variant#SECONDARY}：{@code bg-primary/16}，悬停 /22（播放键、「添加到队列」）。</li>
 *   <li>{@link Variant#PRIMARY}：实心主色，文字 onPrimary（「立即播放」）。</li>
 * </ul>
 *
 * <p>宽高由调用方每帧给出；圆形按钮传相同宽高即可。整枚按钮在 Skija 画布上直接绘制。</p>
 */
public class SPlayerButton extends AbstractWidget<SPlayerButton> {

    public enum Variant { GHOST, TERTIARY, SECONDARY, PRIMARY }

    private final Supplier<String> icon;
    private final Supplier<String> label;
    private Variant variant;
    private CFontRenderer font = SPlayerStyle.textSm(false);
    private double iconSize = SPlayerStyle.px(20);
    private boolean primaryTint;
    private BooleanSupplier active = () -> false;
    private BooleanSupplier disabled = () -> false;
    private BooleanSupplier badge = () -> false;
    private Supplier<Boolean> solidIcon = () -> false;
    private float hover;
    private float press;

    public SPlayerButton(Supplier<String> icon, Supplier<String> label, Variant variant) {
        this.icon = icon == null ? () -> null : icon;
        this.label = label == null ? () -> null : label;
        this.variant = variant;
        this.setShouldOverrideMouseCursor(true);
    }

    /** 圆形图标按钮。 */
    public static SPlayerButton icon(String icon, Variant variant) {
        return new SPlayerButton(() -> icon, null, variant);
    }

    public static SPlayerButton icon(Supplier<String> icon, Variant variant) {
        return new SPlayerButton(icon, null, variant);
    }

    /** 带文字的胶囊按钮。 */
    public static SPlayerButton pill(String icon, String label, Variant variant) {
        return new SPlayerButton(() -> icon, () -> label, variant);
    }

    public SPlayerButton font(CFontRenderer font) {
        this.font = font;
        return this;
    }

    public SPlayerButton iconSize(double iconSize) {
        this.iconSize = iconSize;
        return this;
    }

    /** 图标/文字走主色（SButton 的 {@code type="primary"}），而不是 onSurface。 */
    public SPlayerButton primaryTint(boolean primaryTint) {
        this.primaryTint = primaryTint;
        return this;
    }

    /** 激活态（如队列已展开）：GHOST 变体按 tertiary 的底色画。 */
    public SPlayerButton active(BooleanSupplier active) {
        this.active = active;
        return this;
    }

    public SPlayerButton disabled(BooleanSupplier disabled) {
        this.disabled = disabled;
        return this;
    }

    /** 右上角未读小圆点。 */
    public SPlayerButton badge(BooleanSupplier badge) {
        this.badge = badge;
        return this;
    }

    public SPlayerButton solidIcon(Supplier<Boolean> solidIcon) {
        this.solidIcon = solidIcon;
        return this;
    }

    public SPlayerButton variant(Variant variant) {
        this.variant = variant;
        return this;
    }

    /** 胶囊按钮按内容需要的宽度（{@code px-4} 内边距、图标与文字 {@code gap-1.5}）。 */
    public double preferredWidth(double height) {
        String text = this.label.get();
        String glyph = this.icon.get();
        if (text == null || text.isEmpty()) return height;
        double width = SPlayerStyle.px(16) * 2 + this.font.getStringWidthD(text);
        if (glyph != null) width += this.iconSize + SPlayerStyle.px(6);
        return width;
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        boolean off = this.disabled.getAsBoolean();
        this.setClickable(!off);
        this.hover = SPlayerStyle.approach(this.hover, this.isHovering() && !off ? 1f : 0f);
        this.press = SPlayerStyle.approach(this.press,
                this.isHovering() && !off && com.muoniumplayer.compat.lwjgl2.Mouse.isButtonDown(0) ? 1f : 0f);
        float alpha = this.getAlpha() * (off ? .4f : 1f);
        double x = this.getX();
        double y = this.getY();
        double width = this.getWidth();
        double height = this.getHeight();
        double radius = height * .5;

        int onSurface = SPlayerStyle.onSurface();
        int primary = SPlayerStyle.primary();
        int ink = this.primaryTint ? primary : onSurface;
        boolean isActive = this.active.getAsBoolean();
        switch (this.variant) {
            case GHOST: {
                double base = isActive ? (this.primaryTint ? .10 : .08) : 0.0;
                double level = base + .08 * this.hover + .06 * this.press;
                SPlayerStyle.fill(x, y, width, height, radius,
                        SPlayerStyle.a(this.primaryTint ? primary : onSurface, level, alpha));
                break;
            }
            case TERTIARY: {
                double level = .05 + .05 * this.hover + .06 * this.press;
                SPlayerStyle.fill(x, y, width, height, radius,
                        SPlayerStyle.a(this.primaryTint ? primary : onSurface,
                                this.primaryTint ? level + .03 : level, alpha));
                break;
            }
            case SECONDARY: {
                double level = .16 + .06 * this.hover + .06 * this.press;
                SPlayerStyle.fill(x, y, width, height, radius, SPlayerStyle.a(primary, level, alpha));
                ink = primary;
                break;
            }
            case PRIMARY: {
                int fill = SPlayerStyle.mix(primary, onSurface, .12 * this.hover);
                SPlayerStyle.fill(x, y, width, height, radius, SPlayerStyle.a(fill, 1.0, alpha));
                ink = SPlayerStyle.onPrimary();
                break;
            }
            default:
                break;
        }

        String glyph = this.icon.get();
        String text = this.label.get();
        int inkColor = SPlayerStyle.a(ink, 1.0, alpha);
        boolean solid = Boolean.TRUE.equals(this.solidIcon.get());
        if (text == null || text.isEmpty()) {
            if (glyph != null) {
                SPlayerIcons.draw(glyph, x + width * .5, y + height * .5, this.iconSize, inkColor, solid);
            }
        } else {
            double content = this.font.getStringWidthD(text) + (glyph != null ? this.iconSize + SPlayerStyle.px(6) : 0);
            double cursor = x + Math.max(SPlayerStyle.px(10), (width - content) * .5);
            if (glyph != null) {
                SPlayerIcons.draw(glyph, cursor + this.iconSize * .5, y + height * .5, this.iconSize, inkColor, solid);
                cursor += this.iconSize + SPlayerStyle.px(6);
            }
            SPlayerStyle.text(this.font, text, cursor, y + height * .5, x + width - cursor, inkColor);
        }

        if (this.badge.getAsBoolean()) {
            double dot = SPlayerStyle.px(7);
            SPlayerStyle.circle(x + width - dot * .9, y + dot * .9, dot * .5 + SPlayerStyle.px(1.5),
                    SPlayerStyle.a(SPlayerStyle.panel(), 1.0, alpha));
            SPlayerStyle.circle(x + width - dot * .9, y + dot * .9, dot * .5, SPlayerStyle.a(0xE05B66, 1.0, alpha));
        }
    }
}
