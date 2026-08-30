package com.muoniumplayer.compat.mc;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 1.8.9 {@code net.minecraft.client.gui.GuiTextField} 的兼容替身。
 *
 * <p>编辑逻辑不自己写：继承 26.2 的 {@link EditBox}，复用原版的光标移动、
 * 选区、Ctrl+A/C/V/X、maxLength 截断等行为，只在外面套一层 1.8.9 的方法名与字段名，
 * 这样 {@code GuiHudEditor} / {@code GuiHudEditorPro} 的调用点一行都不用改。
 *
 * <p>{@code width} / {@code height} 在原版的 AbstractWidget 里是 protected，
 * 而 1.8.9 的调用点是直接赋值（{@code hexColorInput.width = ...}）。这里用同名 public
 * 字段遮蔽父类字段，并在每次进入原版逻辑前 {@link #syncGeometry()} 同步给父类，
 * 保证命中判定与光标定位用的是调用方刚写进来的几何。
 *
 * <p>两个编辑器都注明「vanilla draw pass is bypassed」——文本框只用来处理键盘与光标，
 * 绘制由编辑器自己完成。因此本类从不调用原版的 render state 采集。
 */
public class GuiTextField extends EditBox {

    /** 1.8.9 的控件 id，仅供调用方自己区分，原版逻辑不用。 */
    public final int id;

    public int xPosition;
    public int yPosition;
    /** 遮蔽 AbstractWidget#width，见类注释。 */
    public int width;
    /** 遮蔽 AbstractWidget#height，见类注释。 */
    public int height;

    public GuiTextField(int id, FontRenderer fontRenderer, int x, int y, int width, int height) {
        super(fontRenderer.delegate(), x, y, width, height, Component.empty());
        this.id = id;
        this.xPosition = x;
        this.yPosition = y;
        this.width = width;
        this.height = height;
    }

    private void syncGeometry() {
        setX(xPosition);
        setY(yPosition);
        super.width = this.width;
        super.height = this.height;
    }

    /** 1.8.9 名字：关掉自带的黑底白框。26.2 里这项叫 bordered。 */
    public void setEnableBackgroundDrawing(boolean enable) {
        setBordered(enable);
    }

    /** 1.8.9 名字：最大字符数。 */
    public void setMaxStringLength(int length) {
        setMaxLength(length);
    }

    // 1.21.11 的 Yarn 名恰好也叫 getText/setText，所以那一版可以直接继承；
    // 26.2 的官方名是 getValue/setValue，这里补两个转发方法，
    // 让 GuiHudEditor / GuiHudEditorPro 的十余处调用点保持不变。
    /** 1.8.9 名字：取当前文本。 */
    public String getText() {
        return getValue();
    }

    /** 1.8.9 名字：设置当前文本（会走原版的 maxLength 截断与 responder 回调）。 */
    public void setText(String text) {
        setValue(text);
    }

    // 1.8.9 的 setTextColor 在 26.2 里同名同义（EditBox#setTextColor），
    // 所以这里不再需要转发方法：直接继承。

    /**
     * 1.8.9 的单一入口：一次事件同时带 char 和 keyCode。
     * 原版拆成了 {@code keyPressed}（只有键码）+ {@code charTyped}（只有字符），
     * 调用方（GuiScreen 兼容层）会分别以 {@code keyCode != 0} / {@code typedChar != 0}
     * 两种形态转发进来，这里按哪个有效就走哪条原版路径。
     *
     * @return true 表示这次输入被文本框消费掉了
     */
    public boolean textboxKeyTyped(char typedChar, int keyCode) {
        if (!isFocused()) {
            return false;
        }
        syncGeometry();

        if (keyCode != 0) {
            int glfwKey = Keyboard.toGlfw(keyCode);
            if (glfwKey != GLFW.GLFW_KEY_UNKNOWN) {
                // 26.2 把散装形参打包成 KeyEvent(key, scancode, modifiers)。
                // scancode 与 modifiers 传 0：原版 EditBox 判断 Ctrl/Shift 走的是
                // InputWithModifiers 的默认方法，而那些方法在 modifiers 为 0 时会回落到
                // Screen.hasControlDown()/hasShiftDown()（直接问 GLFW），不看这个参数；
                // scancode 它根本不用。
                if (keyPressed(new KeyEvent(glfwKey, 0, 0))) {
                    return true;
                }
            }
        }

        if (typedChar != 0) {
            return charTyped(new CharacterEvent(typedChar));
        }
        return false;
    }

    /**
     * 1.8.9 的 int 版本；与父类的 {@code mouseClicked(MouseButtonEvent, boolean)} 是重载关系。
     *
     * <p>26.2 把鼠标事件打包成 {@code MouseButtonEvent(x, y, MouseButtonInfo(button, modifiers))}。
     * {@code doubleClick} 传 false：1.8.9 的调用点没有双击语义，传 true 会让原版
     * 按双击选词处理，行为就变了。
     */
    public boolean mouseClicked(int mouseX, int mouseY, int mouseButton) {
        syncGeometry();
        return super.mouseClicked(new MouseButtonEvent(
                mouseX, mouseY,
                new MouseButtonInfo(mouseButton, 0)), false);
    }
}
