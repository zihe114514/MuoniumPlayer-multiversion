package com.muoniumplayer.compat.lwjgl2;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLScancode;

/**
 * LWJGL 2 {@code org.lwjgl.input.Keyboard} 的兼容替身（1.8.9 Forge -> 1.20.1 Fabric 移植用）。
 *
 * <p>为什么保留 LWJGL2 的键码空间而不是直接换成后端键码（26.3 是 SDL scancode）：全项目有 60+ 处
 * {@code Keyboard.KEY_*} 比较，且 {@code KeyboardUtils} 里直接写了裸数字
 * （{@code isKeyDown(29)} = LCONTROL、{@code 42} = LSHIFT、{@code 56} = LMENU 等），
 * HudConfig 里的快捷键也按这套码值持久化。改键码空间等于改行为，属移植红线。
 * 因此：常量值 = LWJGL2 原值（本文件由 lwjgl-2.9.3-sources.jar 的 Keyboard.java 逐条抽取生成），
 * 只在真正查询硬件状态时翻译成后端键码。
 *
 * <p><b>26.3 特别之处：lwjgl-glfw 已经不在原版的依赖里了（换成了 lwjgl-sdl，
 * {@code Window#handle()} 现在是 SDL 窗口指针）。</b>本文件里的 TO_GLFW / FROM_GLFW /
 * {@link #toGlfw(int)} 这些名字里的「GLFW」是历史包袱，为了五棵树共用同一条 API 没改名；
 * 值的空间已从 GLFW 码整体搬到 SDL scancode——判据不是名字像，而是逐条对过：
 * 原版 {@code InputConstants.KEY_*} 可比的 89 条里 89 条等于 {@code SDL_SCANCODE_*}、
 * 只有 1 条等于 {@code SDLK_*}（例：KEY_F1=58=SDL_SCANCODE_F1，而 SDLK_F1=1073741882）。
 * 也就是说 26.3 的 {@code Screen#keyPressed} 收到的是 scancode，这张表必须按 scancode 填。
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


    /** LWJGL2 键码 -> 后端键码（26.3 = SDL scancode）。索引即 LWJGL2 键码，未映射为 SDL_SCANCODE_UNKNOWN。 */
    private static final int[] TO_GLFW = new int[256];
    /** 后端键码 -> LWJGL2 键码，供 MC 回调边界（Screen#keyPressed 收到的是 SDL scancode）反向翻译。 */
    private static final java.util.Map<Integer, Integer> FROM_GLFW = new java.util.HashMap<>();

    private static void map(int lwjgl2, int glfw) {
        TO_GLFW[lwjgl2] = glfw;
        FROM_GLFW.putIfAbsent(glfw, lwjgl2);
    }

    static {
        java.util.Arrays.fill(TO_GLFW, SDLScancode.SDL_SCANCODE_UNKNOWN);
        TO_GLFW[KEY_NONE] = SDLScancode.SDL_SCANCODE_UNKNOWN;

        map(KEY_ESCAPE, SDLScancode.SDL_SCANCODE_ESCAPE);
        map(KEY_1, SDLScancode.SDL_SCANCODE_1);
        map(KEY_2, SDLScancode.SDL_SCANCODE_2);
        map(KEY_3, SDLScancode.SDL_SCANCODE_3);
        map(KEY_4, SDLScancode.SDL_SCANCODE_4);
        map(KEY_5, SDLScancode.SDL_SCANCODE_5);
        map(KEY_6, SDLScancode.SDL_SCANCODE_6);
        map(KEY_7, SDLScancode.SDL_SCANCODE_7);
        map(KEY_8, SDLScancode.SDL_SCANCODE_8);
        map(KEY_9, SDLScancode.SDL_SCANCODE_9);
        map(KEY_0, SDLScancode.SDL_SCANCODE_0);
        map(KEY_MINUS, SDLScancode.SDL_SCANCODE_MINUS);
        map(KEY_EQUALS, SDLScancode.SDL_SCANCODE_EQUALS);
        map(KEY_BACK, SDLScancode.SDL_SCANCODE_BACKSPACE);
        map(KEY_TAB, SDLScancode.SDL_SCANCODE_TAB);
        map(KEY_Q, SDLScancode.SDL_SCANCODE_Q);
        map(KEY_W, SDLScancode.SDL_SCANCODE_W);
        map(KEY_E, SDLScancode.SDL_SCANCODE_E);
        map(KEY_R, SDLScancode.SDL_SCANCODE_R);
        map(KEY_T, SDLScancode.SDL_SCANCODE_T);
        map(KEY_Y, SDLScancode.SDL_SCANCODE_Y);
        map(KEY_U, SDLScancode.SDL_SCANCODE_U);
        map(KEY_I, SDLScancode.SDL_SCANCODE_I);
        map(KEY_O, SDLScancode.SDL_SCANCODE_O);
        map(KEY_P, SDLScancode.SDL_SCANCODE_P);
        map(KEY_LBRACKET, SDLScancode.SDL_SCANCODE_LEFTBRACKET);
        map(KEY_RBRACKET, SDLScancode.SDL_SCANCODE_RIGHTBRACKET);
        map(KEY_RETURN, SDLScancode.SDL_SCANCODE_RETURN);
        map(KEY_LCONTROL, SDLScancode.SDL_SCANCODE_LCTRL);
        map(KEY_A, SDLScancode.SDL_SCANCODE_A);
        map(KEY_S, SDLScancode.SDL_SCANCODE_S);
        map(KEY_D, SDLScancode.SDL_SCANCODE_D);
        map(KEY_F, SDLScancode.SDL_SCANCODE_F);
        map(KEY_G, SDLScancode.SDL_SCANCODE_G);
        map(KEY_H, SDLScancode.SDL_SCANCODE_H);
        map(KEY_J, SDLScancode.SDL_SCANCODE_J);
        map(KEY_K, SDLScancode.SDL_SCANCODE_K);
        map(KEY_L, SDLScancode.SDL_SCANCODE_L);
        map(KEY_SEMICOLON, SDLScancode.SDL_SCANCODE_SEMICOLON);
        map(KEY_APOSTROPHE, SDLScancode.SDL_SCANCODE_APOSTROPHE);
        map(KEY_GRAVE, SDLScancode.SDL_SCANCODE_GRAVE);
        map(KEY_LSHIFT, SDLScancode.SDL_SCANCODE_LSHIFT);
        map(KEY_BACKSLASH, SDLScancode.SDL_SCANCODE_BACKSLASH);
        map(KEY_Z, SDLScancode.SDL_SCANCODE_Z);
        map(KEY_X, SDLScancode.SDL_SCANCODE_X);
        map(KEY_C, SDLScancode.SDL_SCANCODE_C);
        map(KEY_V, SDLScancode.SDL_SCANCODE_V);
        map(KEY_B, SDLScancode.SDL_SCANCODE_B);
        map(KEY_N, SDLScancode.SDL_SCANCODE_N);
        map(KEY_M, SDLScancode.SDL_SCANCODE_M);
        map(KEY_COMMA, SDLScancode.SDL_SCANCODE_COMMA);
        map(KEY_PERIOD, SDLScancode.SDL_SCANCODE_PERIOD);
        map(KEY_SLASH, SDLScancode.SDL_SCANCODE_SLASH);
        map(KEY_RSHIFT, SDLScancode.SDL_SCANCODE_RSHIFT);
        map(KEY_MULTIPLY, SDLScancode.SDL_SCANCODE_KP_MULTIPLY);
        map(KEY_LMENU, SDLScancode.SDL_SCANCODE_LALT);
        map(KEY_SPACE, SDLScancode.SDL_SCANCODE_SPACE);
        map(KEY_CAPITAL, SDLScancode.SDL_SCANCODE_CAPSLOCK);
        map(KEY_F1, SDLScancode.SDL_SCANCODE_F1);
        map(KEY_F2, SDLScancode.SDL_SCANCODE_F2);
        map(KEY_F3, SDLScancode.SDL_SCANCODE_F3);
        map(KEY_F4, SDLScancode.SDL_SCANCODE_F4);
        map(KEY_F5, SDLScancode.SDL_SCANCODE_F5);
        map(KEY_F6, SDLScancode.SDL_SCANCODE_F6);
        map(KEY_F7, SDLScancode.SDL_SCANCODE_F7);
        map(KEY_F8, SDLScancode.SDL_SCANCODE_F8);
        map(KEY_F9, SDLScancode.SDL_SCANCODE_F9);
        map(KEY_F10, SDLScancode.SDL_SCANCODE_F10);
        map(KEY_NUMLOCK, SDLScancode.SDL_SCANCODE_NUMLOCKCLEAR);
        map(KEY_SCROLL, SDLScancode.SDL_SCANCODE_SCROLLLOCK);
        map(KEY_NUMPAD7, SDLScancode.SDL_SCANCODE_KP_7);
        map(KEY_NUMPAD8, SDLScancode.SDL_SCANCODE_KP_8);
        map(KEY_NUMPAD9, SDLScancode.SDL_SCANCODE_KP_9);
        map(KEY_SUBTRACT, SDLScancode.SDL_SCANCODE_KP_MINUS);
        map(KEY_NUMPAD4, SDLScancode.SDL_SCANCODE_KP_4);
        map(KEY_NUMPAD5, SDLScancode.SDL_SCANCODE_KP_5);
        map(KEY_NUMPAD6, SDLScancode.SDL_SCANCODE_KP_6);
        map(KEY_ADD, SDLScancode.SDL_SCANCODE_KP_PLUS);
        map(KEY_NUMPAD1, SDLScancode.SDL_SCANCODE_KP_1);
        map(KEY_NUMPAD2, SDLScancode.SDL_SCANCODE_KP_2);
        map(KEY_NUMPAD3, SDLScancode.SDL_SCANCODE_KP_3);
        map(KEY_NUMPAD0, SDLScancode.SDL_SCANCODE_KP_0);
        map(KEY_DECIMAL, SDLScancode.SDL_SCANCODE_KP_DECIMAL);
        map(KEY_F11, SDLScancode.SDL_SCANCODE_F11);
        map(KEY_F12, SDLScancode.SDL_SCANCODE_F12);
        map(KEY_F13, SDLScancode.SDL_SCANCODE_F13);
        map(KEY_F14, SDLScancode.SDL_SCANCODE_F14);
        map(KEY_F15, SDLScancode.SDL_SCANCODE_F15);
        map(KEY_F16, SDLScancode.SDL_SCANCODE_F16);
        map(KEY_F17, SDLScancode.SDL_SCANCODE_F17);
        map(KEY_F18, SDLScancode.SDL_SCANCODE_F18);
        map(KEY_F19, SDLScancode.SDL_SCANCODE_F19);
        map(KEY_NUMPADEQUALS, SDLScancode.SDL_SCANCODE_KP_EQUALS);
        map(KEY_NUMPADENTER, SDLScancode.SDL_SCANCODE_KP_ENTER);
        map(KEY_RCONTROL, SDLScancode.SDL_SCANCODE_RCTRL);
        map(KEY_DIVIDE, SDLScancode.SDL_SCANCODE_KP_DIVIDE);
        map(KEY_SYSRQ, SDLScancode.SDL_SCANCODE_PRINTSCREEN);
        map(KEY_RMENU, SDLScancode.SDL_SCANCODE_RALT);
        map(KEY_PAUSE, SDLScancode.SDL_SCANCODE_PAUSE);
        map(KEY_HOME, SDLScancode.SDL_SCANCODE_HOME);
        map(KEY_UP, SDLScancode.SDL_SCANCODE_UP);
        map(KEY_PRIOR, SDLScancode.SDL_SCANCODE_PAGEUP);
        map(KEY_LEFT, SDLScancode.SDL_SCANCODE_LEFT);
        map(KEY_RIGHT, SDLScancode.SDL_SCANCODE_RIGHT);
        map(KEY_END, SDLScancode.SDL_SCANCODE_END);
        map(KEY_DOWN, SDLScancode.SDL_SCANCODE_DOWN);
        map(KEY_NEXT, SDLScancode.SDL_SCANCODE_PAGEDOWN);
        map(KEY_INSERT, SDLScancode.SDL_SCANCODE_INSERT);
        map(KEY_DELETE, SDLScancode.SDL_SCANCODE_DELETE);
        map(KEY_LMETA, SDLScancode.SDL_SCANCODE_LGUI);
        map(KEY_RMETA, SDLScancode.SDL_SCANCODE_RGUI);
        map(KEY_APPS, SDLScancode.SDL_SCANCODE_MENU);
        // 以下 LWJGL2 键位在 SDL scancode 里也无对应（日文键盘/电源键等），保持 SDL_SCANCODE_UNKNOWN：
        // KEY_KANA KEY_CONVERT KEY_NOCONVERT KEY_YEN KEY_CIRCUMFLEX KEY_AT KEY_COLON
        // KEY_UNDERLINE KEY_KANJI KEY_STOP KEY_AX KEY_UNLABELED KEY_SECTION KEY_NUMPADCOMMA
        // KEY_FUNCTION KEY_CLEAR KEY_POWER KEY_SLEEP
    }

    /** LWJGL2 键码 -> 后端键码；无映射返回 {@link SDLScancode#SDL_SCANCODE_UNKNOWN}。 */
    public static int toGlfw(int lwjgl2KeyCode) {
        if (lwjgl2KeyCode < 0 || lwjgl2KeyCode >= TO_GLFW.length) {
            return SDLScancode.SDL_SCANCODE_UNKNOWN;
        }
        return TO_GLFW[lwjgl2KeyCode];
    }

    /** 后端键码 -> LWJGL2 键码；无映射返回 {@link #KEY_NONE}。 */
    public static int fromGlfw(int glfwKeyCode) {
        return FROM_GLFW.getOrDefault(glfwKeyCode, KEY_NONE);
    }

    /**
     * 等价于 LWJGL2 的 {@code Keyboard.isKeyDown}：直接查硬件当前状态，与事件队列无关。
     *
     * <p>26.2 的等价物是 {@code InputConstants.isKeyDown(Window, glfwKey)}
     * （Yarn 里叫 {@code InputUtil.isKeyPressed}）。<b>26.3 去掉了 Window 形参</b>，
     * 只剩 {@code isKeyDown(int)}：SDL 的键盘状态是全局的，不再按窗口问一遍。
     * 这里仍然先确认客户端与窗口已建好——拿不到时按「没按下」处理，与旧行为一致。
     */
    public static boolean isKeyDown(int lwjgl2KeyCode) {
        int glfw = toGlfw(lwjgl2KeyCode);
        if (glfw == SDLScancode.SDL_SCANCODE_UNKNOWN) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getWindow() == null) {
            return false;
        }
        return InputConstants.isKeyDown(glfw);
    }

    /**
     * 1.8.9 用它开启按住重复以驱动文本框；现在的 keyPressed 天然带后端的 repeat 事件，无需开关。
     * 保留空实现，避免修改 8 处调用点。
     */
    public static void enableRepeatEvents(boolean enable) {
        // no-op：SDL 的 repeat 事件本来就会到 keyPressed
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
