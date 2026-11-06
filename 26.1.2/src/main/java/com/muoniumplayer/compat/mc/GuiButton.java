package com.muoniumplayer.compat.mc;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/**
 * 1.8.9 {@code net.minecraft.client.gui.GuiButton} 的兼容替身。
 *
 * <p>继承 26.2 的 {@link AbstractButton} 以复用原版按钮贴图与 hover/disabled 三态渲染
 * （GuiFfmpegNotice 明确要求「用原版控件，不依赖 Mod 自己的渲染栈」）。
 * 1.8.9 的 {@code enabled} / {@code visible} / {@code displayString} 等公开字段
 * 在每次渲染前同步给父类的 {@code active} / {@code visible} / message。
 */
public class GuiButton extends AbstractButton {

    public final int id;
    public int xPosition;
    public int yPosition;
    /** 遮蔽 AbstractWidget#width（父类里是 protected，1.8.9 调用点直接读写）。 */
    public int width;
    /** 遮蔽 AbstractWidget#height。 */
    public int height;
    public String displayString;
    public boolean enabled = true;
    public boolean visible = true;

    private GuiScreen owner;

    public GuiButton(int id, int x, int y, String displayString) {
        this(id, x, y, 200, 20, displayString);
    }

    public GuiButton(int id, int x, int y, int width, int height, String displayString) {
        super(x, y, width, height, Component.literal(displayString));
        this.id = id;
        this.xPosition = x;
        this.yPosition = y;
        this.width = width;
        this.height = height;
        this.displayString = displayString;
    }

    void attach(GuiScreen screen) {
        this.owner = screen;
    }

    void syncFromLegacyFields() {
        setX(xPosition);
        setY(yPosition);
        super.width = this.width;
        super.height = this.height;
        this.active = this.enabled;
        super.visible = this.visible;
        if (displayString != null && !displayString.equals(getMessage().getString())) {
            setMessage(Component.literal(displayString));
        }
    }

    // 26.2 的 onPress 带着触发它的输入事件（键盘还是鼠标、带哪些修饰键）。
    // 1.8.9 的 actionPerformed 没有这个概念，直接忽略。
    @Override
    public void onPress(InputWithModifiers input) {
        if (owner != null) {
            owner.dispatchActionPerformed(this);
        }
    }

    /**
     * 26.2 的 {@link AbstractButton} 把「按钮里画什么」抽成了这个方法（父类的
     * {@code extractWidgetRenderState} 是 final，只负责调它）。
     *
     * <p>这里照抄原版 {@code Button.Plain} 的实现：先追加底板精灵，再追加默认标签。
     * 1.8.9 的 GuiButton 只有文字标签、没有图标位，所以这两句就是全部内容。
     */
    @Override
    protected void extractContents(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float delta) {
        extractDefaultSprite(extractor);
        extractDefaultLabel(extractor.textRendererForWidget(
                this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
    }

    /** 1.8.9 的绘制入口名；实际交给原版的 render state 采集。 */
    public void drawButton(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float delta) {
        syncFromLegacyFields();
        if (!this.visible) {
            return;
        }
        extractRenderState(extractor, mouseX, mouseY, delta);
    }

    /** 1.8.9 的命中判定；仅在 enabled && visible 时为真，与原版一致。 */
    public boolean mousePressed(int mouseX, int mouseY) {
        syncFromLegacyFields();
        return this.enabled && this.visible
                && mouseX >= xPosition && mouseY >= yPosition
                && mouseX < xPosition + width && mouseY < yPosition + height;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
