package com.muoniumplayer.compat.mc;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * 1.8.9 {@code net.minecraft.client.gui.GuiButton} 的兼容替身。
 *
 * <p>继承 1.20.1 的 {@link AbstractButton} 以复用原版按钮贴图与 hover/disabled 三态渲染
 * （GuiFfmpegNotice 明确要求「用原版控件，不依赖 Mod 自己的渲染栈」）。
 * 1.8.9 的 {@code enabled} / {@code visible} / {@code displayString} 等公开字段
 * 在每次渲染前同步给父类的 {@code active} / {@code visible} / message。
 */
public class GuiButton extends AbstractButton {

    public final int id;
    public int xPosition;
    public int yPosition;
    /** 遮蔽 ClickableWidget#width（父类里是 protected，1.8.9 调用点直接读写）。 */
    public int width;
    /** 遮蔽 ClickableWidget#height。 */
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

    @Override
    public void onPress() {
        if (owner != null) {
            owner.dispatchActionPerformed(this);
        }
    }

    /** 1.8.9 的绘制入口名；实际交给原版 renderButton。 */
    public void drawButton(GuiGraphics context, int mouseX, int mouseY, float delta) {
        syncFromLegacyFields();
        if (!this.visible) {
            return;
        }
        render(context, mouseX, mouseY, delta);
    }

    /** 1.8.9 的命中判定；仅在 enabled && visible 时为真，与原版一致。 */
    public boolean mousePressed(int mouseX, int mouseY) {
        syncFromLegacyFields();
        return this.enabled && this.visible
                && mouseX >= xPosition && mouseY >= yPosition
                && mouseX < xPosition + width && mouseY < yPosition + height;
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput builder) {
        defaultButtonNarrationText(builder);
    }
}
