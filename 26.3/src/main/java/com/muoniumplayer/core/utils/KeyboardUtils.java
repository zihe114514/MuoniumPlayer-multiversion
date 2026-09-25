package com.muoniumplayer.core.utils;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import net.minecraft.client.Minecraft;
import org.apache.commons.lang3.StringUtils;

/**
 * 键盘组合键判定 + 系统剪贴板读写。
 *
 * <h2>为什么剪贴板不能用 AWT</h2>
 * 这里原本是 {@code Toolkit.getDefaultToolkit().getSystemClipboard()}，四个版本上全都是空操作
 * ——「一起听」里的复制链接 / 复制邀请码按钮会亮起「已复制」，但剪贴板里什么都没有。
 *
 * <p>原因是 Minecraft 自己在 {@code net.minecraft.client.main.Main} 的静态初始化里写了
 * {@code System.setProperty("java.awt.headless", "true")}（1.20.1 / 1.21.1 / 1.21.11 / 26.2
 * 四个版本的 Main.class 里都能反编译出这三条字节码）。headless 模式下
 * {@code Toolkit#getSystemClipboard()} 必抛 {@code HeadlessException}，而原来的实现是
 * {@code catch (Exception) {}} 全吞，于是失败得毫无痕迹：按钮的「已复制」提示由调用方自己点亮，
 * 根本不看写入结果。
 *
 * <p>正确做法是走原版自己的剪贴板通道 {@code Minecraft#keyboardHandler}
 * （{@code getClipboard()} / {@code setClipboard(String)}，内部是
 * {@code GLFW.glfwGetClipboardString} / {@code glfwSetClipboardString}）。
 * 这两个方法名与 {@code Minecraft#keyboardHandler} 字段名在四个版本上完全一致（已 javap 核实），
 * 所以共享源码里只需要一份实现。
 *
 * <h2>线程</h2>
 * GLFW 的剪贴板函数只能在主线程（渲染线程）调用。写入时如果当前不在渲染线程，
 * 就丢给 {@code Minecraft#execute} 排到下一帧；读取没法这样异步化，
 * 非渲染线程直接返回空串并打一条日志，而不是悄悄给出错误结果。
 */
public class KeyboardUtils {

    private static final String TAG = "[MuoniumPlayer/Clipboard] ";

    /** 失败日志只打一次：这些调用点在按键处理里，出问题会每帧刷屏。 */
    private static boolean failureLogged;

    /**
     * 读系统剪贴板。读不到时返回空串（所有调用方都按「空串 = 没内容」处理）。
     */
    public static String getClipboardString() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.keyboardHandler == null) {
            return "";
        }
        if (!mc.isSameThread()) {
            logFailureOnce("读剪贴板必须在渲染线程上，当前线程是 " + Thread.currentThread().getName());
            return "";
        }
        try {
            String text = mc.keyboardHandler.getClipboard();
            return text == null ? "" : text;
        } catch (Throwable t) {
            logFailureOnce("读剪贴板失败: " + t);
            return "";
        }
    }

    /**
     * 写系统剪贴板。空串与 null 直接忽略（GLFW 允许写空串，但调用方一律把它当「没东西可复制」）。
     */
    public static void setClipboardString(String copyText) {
        if (StringUtils.isEmpty(copyText)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.keyboardHandler == null) {
            return;
        }
        if (mc.isSameThread()) {
            writeClipboard(mc, copyText);
        } else {
            mc.execute(() -> writeClipboard(mc, copyText));
        }
    }

    private static void writeClipboard(Minecraft mc, String copyText) {
        try {
            mc.keyboardHandler.setClipboard(copyText);
        } catch (Throwable t) {
            logFailureOnce("写剪贴板失败: " + t);
        }
    }

    private static void logFailureOnce(String message) {
        if (failureLogged) {
            return;
        }
        failureLogged = true;
        System.out.println(TAG + message);
    }

    /**
     * 如果任一 Windows ctrl 键按下或任一 mac meta 键按下则返回 true
     */
    public static boolean isCtrlKeyDown() {
        return Keyboard.isKeyDown(29) || Keyboard.isKeyDown(157);
    }

    /**
     * 如果任一 shift 键按下则返回 true
     */
    public static boolean isShiftKeyDown() {
        return Keyboard.isKeyDown(42) || Keyboard.isKeyDown(54);
    }

    /**
     * 如果任一 alt 键按下则返回 true
     */
    public static boolean isAltKeyDown() {
        return Keyboard.isKeyDown(56) || Keyboard.isKeyDown(184);
    }

    public static boolean isKeyComboCtrlX(int keyID) {
        return keyID == 45 && isCtrlKeyDown() && !isShiftKeyDown() && !isAltKeyDown();
    }

    public static boolean isKeyComboCtrlV(int keyID) {
        return keyID == 47 && isCtrlKeyDown() && !isShiftKeyDown() && !isAltKeyDown();
    }

    public static boolean isKeyComboCtrlC(int keyID) {
        return keyID == 46 && isCtrlKeyDown() && !isShiftKeyDown() && !isAltKeyDown();
    }

    public static boolean isKeyComboCtrlA(int keyID) {
        return keyID == 30 && isCtrlKeyDown() && !isShiftKeyDown() && !isAltKeyDown();
    }

    public static boolean isKeyComboCtrl(int pressedKey, int comboKey) {
        return pressedKey == comboKey && isCtrlKeyDown() && !isShiftKeyDown() && !isAltKeyDown();
    }

}
