package com.muoniumplayer.core.utils;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import net.minecraft.client.Minecraft;
import org.apache.commons.lang3.StringUtils;

/**
     * 若 Windows 上左右 ctrl 键任一按下，或 Mac 上左右 meta 键任一按下，则返回 true
     */
public class KeyboardUtils {

    private static final String TAG = "[MuoniumPlayer/Clipboard] ";

    /**
     * 若左右 shift 键任一按下则返回 true
     */
    private static boolean failureLogged;

    /**
     * 若左右 alt 键任一按下则返回 true
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
