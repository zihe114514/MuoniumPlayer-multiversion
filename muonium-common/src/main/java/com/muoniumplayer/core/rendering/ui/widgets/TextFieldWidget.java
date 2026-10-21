package com.muoniumplayer.core.rendering.ui.widgets;

import lombok.Getter;
import com.muoniumplayer.core.rendering.entities.impl.TextField;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.utils.cursor.CursorUtils;

/**
 * @author MuoniumPlayer Contributors
 * Date: 2025/11/8 13:28
 */
public class TextFieldWidget extends AbstractWidget<TextFieldWidget> {

    @Getter
    private final TextField textField;

    public TextFieldWidget(CFontRenderer fontRenderer) {
        this.textField = new TextField(0, 0, 0, 0, 0);
        this.textField.setFontRenderer(fontRenderer);
        this.setShouldOverrideMouseCursor(true);
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        this.textField.xPosition = (float) this.getX();
        this.textField.yPosition = (float) this.getY();
        this.textField.width = (float) this.getWidth();
        this.textField.height = (float) this.getHeight();
        this.textField.enabledColor = this.getHexColor();
        this.textField.setWholeAlpha(this.getAlpha());
        this.textField.drawTextBox((int) mouseX, (int) mouseY);
    }

    @Override
    public long getHoveringCursorType() {
        return CursorUtils.TEXT;
    }

    @Override
    public boolean onKeyTyped(char character, int keyCode) {
        // ESC 不吃：聚焦的输入框以前对任何键都返回 true，模态浮层（音源/账号管理）的
        // handleEscape 排在控件树之后，结果是 ESC 被输入框吞掉、浮层关不掉。
        // 放行 ESC 让浮层自己决定；输入框不需要对 ESC 做任何事。
        if (keyCode == com.muoniumplayer.compat.lwjgl2.Keyboard.KEY_ESCAPE) {
            return false;
        }
        if (this.textField.isFocused()) {
            this.textField.textboxKeyTyped(character, keyCode);
            super.onKeyTyped(character, keyCode);
            return true;
        }

        return super.onKeyTyped(character, keyCode);
    }

    @Override
    public boolean onMouseClicked(double relativeX, double relativeY, int mouseButton) {
        return this.textField.mouseClicked(this.getX() + relativeX, this.getY() + relativeY, mouseButton);
    }

    public TextFieldWidget setDisabledTextColor(int color) {
        this.textField.setDisabledTextColour(color);
        return this;
    }

    public TextFieldWidget setFocused(boolean focused) {
        this.textField.setFocused(focused);
        return this;
    }

    public boolean isFocused() {
        return this.textField.isFocused();
    }

    public TextFieldWidget setEnabled(boolean enabled) {
        this.textField.setEnabled(enabled);
        return this;
    }

    public boolean isEnabled() {
        return this.textField.isEnabled();
    }

    public String getText() {
        return this.textField.getText();
    }

    public TextFieldWidget setText(String text) {
        this.textField.setText(text);
        return this;
    }

    public TextFieldWidget setPlaceholder(String placeholder) {
        this.textField.setPlaceholder(placeholder);
        return this;
    }

    public TextFieldWidget drawUnderline(boolean drawUnderline) {
        this.textField.setDrawLineUnder(drawUnderline);
        return this;
    }

    public TextFieldWidget setTextChangedCallback(TextField.TextChangedCallback callback) {
        this.textField.setCallback(callback);
        return this;
    }
}
