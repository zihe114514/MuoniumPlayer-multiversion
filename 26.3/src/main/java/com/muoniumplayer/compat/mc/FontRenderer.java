package com.muoniumplayer.compat.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * 1.8.9 {@code net.minecraft.client.gui.FontRenderer} 的兼容替身（只覆盖本项目用到的成员）。
 *
 * <p>26.2 的 {@code Font} 不能脱离本帧的 GUI 采集器画字（文字要作为 render state 追加到
 * {@code GuiRenderState}），而 1.8.9 的 {@code fontRendererObj.drawString} 是随时可调的。
 * 为此本类持有「当前帧的 {@link GuiGraphicsExtractor}」：{@link GuiScreen} 在
 * {@code extractRenderState} 入口 {@link #beginFrame(GuiGraphicsExtractor)}、出口
 * {@link #endFrame()}。帧外调用 drawString 会被静默丢弃而不是抛异常
 * （1.8.9 在帧外调用只是画到不该画的地方，不崩）。
 *
 * <p>注意 {@link #FONT_HEIGHT} 保持 public final int 的形态，因为调用点是字段访问
 * （{@code fontRendererObj.FONT_HEIGHT}）而不是方法调用。26.2 的 {@code Font#lineHeight}
 * 同样是 9，但这里仍从 Font 读，不写死。
 */
public final class FontRenderer {

    /** 与 1.8.9 同名同语义：一行文字的高度（含行距），原版为 9。 */
    public final int FONT_HEIGHT;

    private final Font delegate;
    private GuiGraphicsExtractor currentContext;

    public FontRenderer(Font delegate) {
        this.delegate = delegate;
        this.FONT_HEIGHT = delegate == null ? 9 : delegate.lineHeight;
    }

    public static FontRenderer vanilla() {
        Minecraft client = Minecraft.getInstance();
        return new FontRenderer(client == null ? null : client.font);
    }

    /** 暴露底层 Font，供需要原版原生 API 的地方（例如构造 EditBox）使用。 */
    public Font delegate() {
        return delegate;
    }

    void beginFrame(GuiGraphicsExtractor context) {
        this.currentContext = context;
    }

    void endFrame() {
        this.currentContext = null;
    }

    public int getStringWidth(String text) {
        if (delegate == null || text == null) {
            return 0;
        }
        return delegate.width(text);
    }

    /**
     * 对应 1.8.9 的 {@code listFormattedStringToWidth}：按像素宽度折行。
     * 26.2 的等价物是 {@code Font#split}，返回 FormattedCharSequence，
     * 这里还原成 String 列表以保持调用点不变。
     */
    public List<String> listFormattedStringToWidth(String text, int wrapWidth) {
        List<String> result = new ArrayList<>();
        if (delegate == null || text == null) {
            return result;
        }
        for (FormattedCharSequence line : delegate.split(Component.literal(text), wrapWidth)) {
            StringBuilder sb = new StringBuilder();
            line.accept((index, style, codePoint) -> {
                sb.appendCodePoint(codePoint);
                return true;
            });
            result.add(sb.toString());
        }
        return result;
    }

    // 26.2 的 GuiGraphicsExtractor.text 返回 void（1.20.1 的 drawText 曾返回推进后的 x）。
    // 1.8.9 的调用点期望拿到「绘制后的 x」，所以自己按文字宽度算一遍。
    // 这与旧返回值同义：原版当年返回的就是 x + 该串的像素宽度。

    /** 无阴影绘制，返回值与 1.8.9 一致（绘制后的 x 推进量）。帧外调用为 no-op。 */
    public int drawString(String text, int x, int y, int color) {
        if (delegate == null || currentContext == null || text == null) {
            return x;
        }
        currentContext.text(delegate, text, x, y, color, false);
        return x + getStringWidth(text);
    }

    /** 带阴影绘制，对应 1.8.9 的 {@code drawStringWithShadow}。 */
    public int drawStringWithShadow(String text, float x, float y, int color) {
        if (delegate == null || currentContext == null || text == null) {
            return (int) x;
        }
        currentContext.text(delegate, text, (int) x, (int) y, color, true);
        return (int) x + getStringWidth(text);
    }
}
