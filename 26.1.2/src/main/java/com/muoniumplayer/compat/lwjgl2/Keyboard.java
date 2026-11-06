package com.muoniumplayer.compat.lwjgl2;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * LWJGL 2 {@code org.lwjgl.input.Keyboard} 的兼容替身（1.8.9 Forge -> 1.20.1 Fabric 移植用）。
 *
 * <p>为什么保留 LWJGL2 的键码空间而不是直接换成 GLFW 键码：全项目有 60+ 处
 * {@code Keyboard.KEY_*} 比较，且 {@code KeyboardUtils} 里直接写了裸数字
 * （{@code isKeyDown(29)} = LCONTROL、{@code 42} = LSHIFT、{@code 56} = LMENU 等），
 * HudConfig 里的快捷键也按这套码值持久化。改键码空间等于改行为，属移植红线。
 * 因此：常量值 = LWJGL2 原值（本文件由 lwjgl-2.9.3-sources.jar 的 Keyboard.java 逐条抽取生成），
 * 只在真正查询硬件状态时翻译成 GLFW 键码。
 *
 * <p>{@link #enableRepeatEvents(boolean)} 在 1.20.1 是空实现：1.8.9 需要它才能让
 * GuiTextField 收到按住重复；1.20.1 的 {@code Screen#keyPressed} 由 GLFW 直接投递
 * repeat 事件，不需要开关。
 */
public final class Keyboard {

    private Keyboard() {
    }
    public static final int KEY_NONE = 0x00;
    public static final int KEY_ESCAPE = 0x01;
    public static final int KEY_1 = 0x02;
    public static final int KEY_2 = 0x03;
    public static final int KEY_3 = 0x04;
    public static final int KEY_4 = 0x05;
    public static final int KEY_5 = 0x06;
    public static final int KEY_6 = 0x07;
    public static final int KEY_7 = 0x08;
    public static final int KEY_8 = 0x09;
    public static final int KEY_9 = 0x0A;
    public static final int KEY_0 = 0x0B;
    public static final int KEY_MINUS = 0x0C;
    public static final int KEY_EQUALS = 0x0D;
    public static final int KEY_BACK = 0x0E;
    public static final int KEY_TAB = 0x0F;
    public static final int KEY_Q = 0x10;
    public static final int KEY_W = 0x11;
    public static final int KEY_E = 0x12;
    public static final int KEY_R = 0x13;
    public static final int KEY_T = 0x14;
    public static final int KEY_Y = 0x15;
    public static final int KEY_U = 0x16;
    public static final int KEY_I = 0x17;
    public static final int KEY_O = 0x18;
    public static final int KEY_P = 0x19;
    public static final int KEY_LBRACKET = 0x1A;
    public static final int KEY_RBRACKET = 0x1B;
    public static final int KEY_RETURN = 0x1C;
    public static final int KEY_LCONTROL = 0x1D;
    public static final int KEY_A = 0x1E;
    public static final int KEY_S = 0x1F;
    public static final int KEY_D = 0x20;
    public static final int KEY_F = 0x21;
    public static final int KEY_G = 0x22;
    public static final int KEY_H = 0x23;
    public static final int KEY_J = 0x24;
    public static final int KEY_K = 0x25;
    public static final int KEY_L = 0x26;
    public static final int KEY_SEMICOLON = 0x27;
    public static final int KEY_APOSTROPHE = 0x28;
    public static final int KEY_GRAVE = 0x29;
    public static final int KEY_LSHIFT = 0x2A;
    public static final int KEY_BACKSLASH = 0x2B;
    public static final int KEY_Z = 0x2C;
    public static final int KEY_X = 0x2D;
    public static final int KEY_C = 0x2E;
    public static final int KEY_V = 0x2F;
    public static final int KEY_B = 0x30;
    public static final int KEY_N = 0x31;
    public static final int KEY_M = 0x32;
    public static final int KEY_COMMA = 0x33;
    public static final int KEY_PERIOD = 0x34;
    public static final int KEY_SLASH = 0x35;
    public static final int KEY_RSHIFT = 0x36;
    public static final int KEY_MULTIPLY = 0x37;
    public static final int KEY_LMENU = 0x38;
    public static final int KEY_SPACE = 0x39;
    public static final int KEY_CAPITAL = 0x3A;
    public static final int KEY_F1 = 0x3B;
    public static final int KEY_F2 = 0x3C;
    public static final int KEY_F3 = 0x3D;
    public static final int KEY_F4 = 0x3E;
    public static final int KEY_F5 = 0x3F;
    public static final int KEY_F6 = 0x40;
    public static final int KEY_F7 = 0x41;
    public static final int KEY_F8 = 0x42;
    public static final int KEY_F9 = 0x43;
    public static final int KEY_F10 = 0x44;
    public static final int KEY_NUMLOCK = 0x45;
    public static final int KEY_SCROLL = 0x46;
    public static final int KEY_NUMPAD7 = 0x47;
    public static final int KEY_NUMPAD8 = 0x48;
    public static final int KEY_NUMPAD9 = 0x49;
    public static final int KEY_SUBTRACT = 0x4A;
    public static final int KEY_NUMPAD4 = 0x4B;
    public static final int KEY_NUMPAD5 = 0x4C;
    public static final int KEY_NUMPAD6 = 0x4D;
    public static final int KEY_ADD = 0x4E;
    public static final int KEY_NUMPAD1 = 0x4F;
    public static final int KEY_NUMPAD2 = 0x50;
    public static final int KEY_NUMPAD3 = 0x51;
    public static final int KEY_NUMPAD0 = 0x52;
    public static final int KEY_DECIMAL = 0x53;
    public static final int KEY_F11 = 0x57;
    public static final int KEY_F12 = 0x58;
    public static final int KEY_F13 = 0x64;
    public static final int KEY_F14 = 0x65;
    public static final int KEY_F15 = 0x66;
    public static final int KEY_F16 = 0x67;
    public static final int KEY_F17 = 0x68;
    public static final int KEY_F18 = 0x69;
    public static final int KEY_KANA = 0x70;
    public static final int KEY_F19 = 0x71;
    public static final int KEY_CONVERT = 0x79;
    public static final int KEY_NOCONVERT = 0x7B;
    public static final int KEY_YEN = 0x7D;
    public static final int KEY_NUMPADEQUALS = 0x8D;
    public static final int KEY_CIRCUMFLEX = 0x90;
    public static final int KEY_AT = 0x91;
    public static final int KEY_COLON = 0x92;
    public static final int KEY_UNDERLINE = 0x93;
    public static final int KEY_KANJI = 0x94;
    public static final int KEY_STOP = 0x95;
    public static final int KEY_AX = 0x96;
    public static final int KEY_UNLABELED = 0x97;
    public static final int KEY_NUMPADENTER = 0x9C;
    public static final int KEY_RCONTROL = 0x9D;
    public static final int KEY_SECTION = 0xA7;
    public static final int KEY_NUMPADCOMMA = 0xB3;
    public static final int KEY_DIVIDE = 0xB5;
    public static final int KEY_SYSRQ = 0xB7;
    public static final int KEY_RMENU = 0xB8;
    public static final int KEY_FUNCTION = 0xC4;
    public static final int KEY_PAUSE = 0xC5;
    public static final int KEY_HOME = 0xC7;
    public static final int KEY_UP = 0xC8;
    public static final int KEY_PRIOR = 0xC9;
    public static final int KEY_LEFT = 0xCB;
    public static final int KEY_RIGHT = 0xCD;
    public static final int KEY_END = 0xCF;
    public static final int KEY_DOWN = 0xD0;
    public static final int KEY_NEXT = 0xD1;
    public static final int KEY_INSERT = 0xD2;
    public static final int KEY_DELETE = 0xD3;
    public static final int KEY_CLEAR = 0xDA;
    public static final int KEY_LMETA = 0xDB;
    public static final int KEY_LWIN = KEY_LMETA;
    public static final int KEY_RMETA = 0xDC;
    public static final int KEY_RWIN = KEY_RMETA;
    public static final int KEY_APPS = 0xDD;
    public static final int KEY_POWER = 0xDE;
    public static final int KEY_SLEEP = 0xDF;


    /** LWJGL2 键码 -> GLFW 键码。索引即 LWJGL2 键码，值为 GLFW 键码，未映射为 GLFW_KEY_UNKNOWN。 */
    private static final int[] TO_GLFW = new int[256];
    /** GLFW 键码 -> LWJGL2 键码，供 MC 回调边界（Screen#keyPressed 收到的是 GLFW 键码）反向翻译。 */
    private static final java.util.Map<Integer, Integer> FROM_GLFW = new java.util.HashMap<>();

    private static void map(int lwjgl2, int glfw) {
        TO_GLFW[lwjgl2] = glfw;
        FROM_GLFW.putIfAbsent(glfw, lwjgl2);
    }

    static {
        java.util.Arrays.fill(TO_GLFW, GLFW.GLFW_KEY_UNKNOWN);
        TO_GLFW[KEY_NONE] = GLFW.GLFW_KEY_UNKNOWN;

        map(KEY_ESCAPE, GLFW.GLFW_KEY_ESCAPE);
        map(KEY_1, GLFW.GLFW_KEY_1);
        map(KEY_2, GLFW.GLFW_KEY_2);
        map(KEY_3, GLFW.GLFW_KEY_3);
        map(KEY_4, GLFW.GLFW_KEY_4);
        map(KEY_5, GLFW.GLFW_KEY_5);
        map(KEY_6, GLFW.GLFW_KEY_6);
        map(KEY_7, GLFW.GLFW_KEY_7);
        map(KEY_8, GLFW.GLFW_KEY_8);
        map(KEY_9, GLFW.GLFW_KEY_9);
        map(KEY_0, GLFW.GLFW_KEY_0);
        map(KEY_MINUS, GLFW.GLFW_KEY_MINUS);
        map(KEY_EQUALS, GLFW.GLFW_KEY_EQUAL);
        map(KEY_BACK, GLFW.GLFW_KEY_BACKSPACE);
        map(KEY_TAB, GLFW.GLFW_KEY_TAB);
        map(KEY_Q, GLFW.GLFW_KEY_Q);
        map(KEY_W, GLFW.GLFW_KEY_W);
        map(KEY_E, GLFW.GLFW_KEY_E);
        map(KEY_R, GLFW.GLFW_KEY_R);
        map(KEY_T, GLFW.GLFW_KEY_T);
        map(KEY_Y, GLFW.GLFW_KEY_Y);
        map(KEY_U, GLFW.GLFW_KEY_U);
        map(KEY_I, GLFW.GLFW_KEY_I);
        map(KEY_O, GLFW.GLFW_KEY_O);
        map(KEY_P, GLFW.GLFW_KEY_P);
        map(KEY_LBRACKET, GLFW.GLFW_KEY_LEFT_BRACKET);
        map(KEY_RBRACKET, GLFW.GLFW_KEY_RIGHT_BRACKET);
        map(KEY_RETURN, GLFW.GLFW_KEY_ENTER);
        map(KEY_LCONTROL, GLFW.GLFW_KEY_LEFT_CONTROL);
        map(KEY_A, GLFW.GLFW_KEY_A);
        map(KEY_S, GLFW.GLFW_KEY_S);
        map(KEY_D, GLFW.GLFW_KEY_D);
        map(KEY_F, GLFW.GLFW_KEY_F);
        map(KEY_G, GLFW.GLFW_KEY_G);
        map(KEY_H, GLFW.GLFW_KEY_H);
        map(KEY_J, GLFW.GLFW_KEY_J);
        map(KEY_K, GLFW.GLFW_KEY_K);
        map(KEY_L, GLFW.GLFW_KEY_L);
        map(KEY_SEMICOLON, GLFW.GLFW_KEY_SEMICOLON);
        map(KEY_APOSTROPHE, GLFW.GLFW_KEY_APOSTROPHE);
        map(KEY_GRAVE, GLFW.GLFW_KEY_GRAVE_ACCENT);
        map(KEY_LSHIFT, GLFW.GLFW_KEY_LEFT_SHIFT);
        map(KEY_BACKSLASH, GLFW.GLFW_KEY_BACKSLASH);
        map(KEY_Z, GLFW.GLFW_KEY_Z);
        map(KEY_X, GLFW.GLFW_KEY_X);
        map(KEY_C, GLFW.GLFW_KEY_C);
        map(KEY_V, GLFW.GLFW_KEY_V);
        map(KEY_B, GLFW.GLFW_KEY_B);
        map(KEY_N, GLFW.GLFW_KEY_N);
        map(KEY_M, GLFW.GLFW_KEY_M);
        map(KEY_COMMA, GLFW.GLFW_KEY_COMMA);
        map(KEY_PERIOD, GLFW.GLFW_KEY_PERIOD);
        map(KEY_SLASH, GLFW.GLFW_KEY_SLASH);
        map(KEY_RSHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT);
        map(KEY_MULTIPLY, GLFW.GLFW_KEY_KP_MULTIPLY);
        map(KEY_LMENU, GLFW.GLFW_KEY_LEFT_ALT);
        map(KEY_SPACE, GLFW.GLFW_KEY_SPACE);
        map(KEY_CAPITAL, GLFW.GLFW_KEY_CAPS_LOCK);
        map(KEY_F1, GLFW.GLFW_KEY_F1);
        map(KEY_F2, GLFW.GLFW_KEY_F2);
        map(KEY_F3, GLFW.GLFW_KEY_F3);
        map(KEY_F4, GLFW.GLFW_KEY_F4);
        map(KEY_F5, GLFW.GLFW_KEY_F5);
        map(KEY_F6, GLFW.GLFW_KEY_F6);
        map(KEY_F7, GLFW.GLFW_KEY_F7);
        map(KEY_F8, GLFW.GLFW_KEY_F8);
        map(KEY_F9, GLFW.GLFW_KEY_F9);
        map(KEY_F10, GLFW.GLFW_KEY_F10);
        map(KEY_NUMLOCK, GLFW.GLFW_KEY_NUM_LOCK);
        map(KEY_SCROLL, GLFW.GLFW_KEY_SCROLL_LOCK);
        map(KEY_NUMPAD7, GLFW.GLFW_KEY_KP_7);
        map(KEY_NUMPAD8, GLFW.GLFW_KEY_KP_8);
        map(KEY_NUMPAD9, GLFW.GLFW_KEY_KP_9);
        map(KEY_SUBTRACT, GLFW.GLFW_KEY_KP_SUBTRACT);
        map(KEY_NUMPAD4, GLFW.GLFW_KEY_KP_4);
        map(KEY_NUMPAD5, GLFW.GLFW_KEY_KP_5);
        map(KEY_NUMPAD6, GLFW.GLFW_KEY_KP_6);
        map(KEY_ADD, GLFW.GLFW_KEY_KP_ADD);
        map(KEY_NUMPAD1, GLFW.GLFW_KEY_KP_1);
        map(KEY_NUMPAD2, GLFW.GLFW_KEY_KP_2);
        map(KEY_NUMPAD3, GLFW.GLFW_KEY_KP_3);
        map(KEY_NUMPAD0, GLFW.GLFW_KEY_KP_0);
        map(KEY_DECIMAL, GLFW.GLFW_KEY_KP_DECIMAL);
        map(KEY_F11, GLFW.GLFW_KEY_F11);
        map(KEY_F12, GLFW.GLFW_KEY_F12);
        map(KEY_F13, GLFW.GLFW_KEY_F13);
        map(KEY_F14, GLFW.GLFW_KEY_F14);
        map(KEY_F15, GLFW.GLFW_KEY_F15);
        map(KEY_F16, GLFW.GLFW_KEY_F16);
        map(KEY_F17, GLFW.GLFW_KEY_F17);
        map(KEY_F18, GLFW.GLFW_KEY_F18);
        map(KEY_F19, GLFW.GLFW_KEY_F19);
        map(KEY_NUMPADEQUALS, GLFW.GLFW_KEY_KP_EQUAL);
        map(KEY_NUMPADENTER, GLFW.GLFW_KEY_KP_ENTER);
        map(KEY_RCONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL);
        map(KEY_DIVIDE, GLFW.GLFW_KEY_KP_DIVIDE);
        map(KEY_SYSRQ, GLFW.GLFW_KEY_PRINT_SCREEN);
        map(KEY_RMENU, GLFW.GLFW_KEY_RIGHT_ALT);
        map(KEY_PAUSE, GLFW.GLFW_KEY_PAUSE);
        map(KEY_HOME, GLFW.GLFW_KEY_HOME);
        map(KEY_UP, GLFW.GLFW_KEY_UP);
        map(KEY_PRIOR, GLFW.GLFW_KEY_PAGE_UP);
        map(KEY_LEFT, GLFW.GLFW_KEY_LEFT);
        map(KEY_RIGHT, GLFW.GLFW_KEY_RIGHT);
        map(KEY_END, GLFW.GLFW_KEY_END);
        map(KEY_DOWN, GLFW.GLFW_KEY_DOWN);
        map(KEY_NEXT, GLFW.GLFW_KEY_PAGE_DOWN);
        map(KEY_INSERT, GLFW.GLFW_KEY_INSERT);
        map(KEY_DELETE, GLFW.GLFW_KEY_DELETE);
        map(KEY_LMETA, GLFW.GLFW_KEY_LEFT_SUPER);
        map(KEY_RMETA, GLFW.GLFW_KEY_RIGHT_SUPER);
        map(KEY_APPS, GLFW.GLFW_KEY_MENU);
        // 以下 LWJGL2 键位在 GLFW 无对应（日文键盘/电源键等），保持 GLFW_KEY_UNKNOWN：
        // KEY_KANA KEY_CONVERT KEY_NOCONVERT KEY_YEN KEY_CIRCUMFLEX KEY_AT KEY_COLON
        // KEY_UNDERLINE KEY_KANJI KEY_STOP KEY_AX KEY_UNLABELED KEY_SECTION KEY_NUMPADCOMMA
        // KEY_FUNCTION KEY_CLEAR KEY_POWER KEY_SLEEP
    }

    /** LWJGL2 键码 -> GLFW 键码；无映射返回 {@link GLFW#GLFW_KEY_UNKNOWN}。 */
    public static int toGlfw(int lwjgl2KeyCode) {
        if (lwjgl2KeyCode < 0 || lwjgl2KeyCode >= TO_GLFW.length) {
            return GLFW.GLFW_KEY_UNKNOWN;
        }
        return TO_GLFW[lwjgl2KeyCode];
    }

    /** GLFW 键码 -> LWJGL2 键码；无映射返回 {@link #KEY_NONE}。 */
    public static int fromGlfw(int glfwKeyCode) {
        return FROM_GLFW.getOrDefault(glfwKeyCode, KEY_NONE);
    }

    /**
     * 等价于 LWJGL2 的 {@code Keyboard.isKeyDown}：直接查硬件当前状态，与事件队列无关。
     *
     * <p>26.2 的等价物是 {@code InputConstants.isKeyDown(Window, glfwKey)}
     * （Yarn 里叫 {@code InputUtil.isKeyPressed}）。拿不到 Window 时按「没按下」处理，
     * 与拿不到句柄时的旧行为一致。
     */
    public static boolean isKeyDown(int lwjgl2KeyCode) {
        int glfw = toGlfw(lwjgl2KeyCode);
        if (glfw == GLFW.GLFW_KEY_UNKNOWN) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        Window window = client == null ? null : client.getWindow();
        if (window == null) {
            return false;
        }
        return InputConstants.isKeyDown(window, glfw);
    }

    /**
     * 1.8.9 用它开启按住重复以驱动文本框；现在的 keyPressed 天然带 GLFW repeat，无需开关。
     * 保留空实现，避免修改 8 处调用点。
     */
    public static void enableRepeatEvents(boolean enable) {
        // no-op：GLFW 的 repeat 事件本来就会到 keyPressed
    }

    /** 1.8.9 里没有被真正用到（仅注释提到），此处显式不支持，防止今后误用事件队列语义。 */
    public static boolean next() {
        throw new UnsupportedOperationException(
                "LWJGL2 键盘事件队列在 26.2 不存在；请改用 Screen#keyPressed 或 MusicHotkeys 的按键计数。");
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }
}
