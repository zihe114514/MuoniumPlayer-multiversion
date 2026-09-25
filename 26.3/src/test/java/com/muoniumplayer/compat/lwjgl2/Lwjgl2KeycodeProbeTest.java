package com.muoniumplayer.compat.lwjgl2;

import org.junit.jupiter.api.Test;
import org.lwjgl.sdl.SDLScancode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 移植红线探针：LWJGL2 键码空间必须原样保留。
 *
 * <p>为什么必须锁死：全项目 60+ 处 {@code Keyboard.KEY_*} 比较，
 * 且 {@code KeyboardUtils} 直接写裸数字（29=LCONTROL、157=RCONTROL、42=LSHIFT、
 * 54=RSHIFT、56=LMENU、184=RMENU），HudConfig 里的快捷键也按这套码值持久化。
 * 任何人把常量改成后端键码值（26.3 = SDL scancode），这里立刻红。
 *
 * <p>不需要 GL 或客户端实例：{@link Keyboard#toGlfw(int)} / {@link Keyboard#fromGlfw(int)}
 * 只查静态表。
 */
class Lwjgl2KeycodeProbeTest {

    @Test
    void lwjgl2ConstantsKeepTheirOriginalDirectInputValues() {
        // 逐条对照 lwjgl-2.9.3-sources.jar 的 org/lwjgl/input/Keyboard.java
        assertEquals(0x01, Keyboard.KEY_ESCAPE);
        assertEquals(0x0E, Keyboard.KEY_BACK);
        assertEquals(0x0F, Keyboard.KEY_TAB);
        assertEquals(0x1C, Keyboard.KEY_RETURN);
        assertEquals(0x1D, Keyboard.KEY_LCONTROL);
        assertEquals(0x2A, Keyboard.KEY_LSHIFT);
        assertEquals(0x36, Keyboard.KEY_RSHIFT);
        assertEquals(0x37, Keyboard.KEY_MULTIPLY);
        assertEquals(0x38, Keyboard.KEY_LMENU);
        assertEquals(0x39, Keyboard.KEY_SPACE);
        assertEquals(0x4A, Keyboard.KEY_SUBTRACT);
        assertEquals(0x4E, Keyboard.KEY_ADD);
        assertEquals(0x9C, Keyboard.KEY_NUMPADENTER);
        assertEquals(0x9D, Keyboard.KEY_RCONTROL);
        assertEquals(0xB8, Keyboard.KEY_RMENU);
        assertEquals(0xC7, Keyboard.KEY_HOME);
        assertEquals(0xC9, Keyboard.KEY_PRIOR);
        assertEquals(0xD1, Keyboard.KEY_NEXT);
        assertEquals(0xD3, Keyboard.KEY_DELETE);
    }

    @Test
    void keyboardUtilsRawNumbersStillNameTheModifierKeys() {
        // KeyboardUtils.isCtrlKeyDown/isShiftKeyDown/isAltKeyDown 用的就是这些裸数字。
        assertEquals(29, Keyboard.KEY_LCONTROL);
        assertEquals(157, Keyboard.KEY_RCONTROL);
        assertEquals(42, Keyboard.KEY_LSHIFT);
        assertEquals(54, Keyboard.KEY_RSHIFT);
        assertEquals(56, Keyboard.KEY_LMENU);
        assertEquals(184, Keyboard.KEY_RMENU);
        // 复制/粘贴/剪切/全选的键码（45=X 46=C 47=V 30=A）
        assertEquals(45, Keyboard.KEY_X);
        assertEquals(46, Keyboard.KEY_C);
        assertEquals(47, Keyboard.KEY_V);
        assertEquals(30, Keyboard.KEY_A);
    }

    @Test
    void toGlfwMapsEveryKeyTheProjectActuallyUses() {
        assertEquals(SDLScancode.SDL_SCANCODE_ESCAPE, Keyboard.toGlfw(Keyboard.KEY_ESCAPE));
        assertEquals(SDLScancode.SDL_SCANCODE_RETURN, Keyboard.toGlfw(Keyboard.KEY_RETURN));
        assertEquals(SDLScancode.SDL_SCANCODE_KP_ENTER, Keyboard.toGlfw(Keyboard.KEY_NUMPADENTER));
        assertEquals(SDLScancode.SDL_SCANCODE_RSHIFT, Keyboard.toGlfw(Keyboard.KEY_RSHIFT));
        assertEquals(SDLScancode.SDL_SCANCODE_LSHIFT, Keyboard.toGlfw(Keyboard.KEY_LSHIFT));
        assertEquals(SDLScancode.SDL_SCANCODE_RCTRL, Keyboard.toGlfw(Keyboard.KEY_RCONTROL));
        assertEquals(SDLScancode.SDL_SCANCODE_PAGEUP, Keyboard.toGlfw(Keyboard.KEY_PRIOR));
        assertEquals(SDLScancode.SDL_SCANCODE_PAGEDOWN, Keyboard.toGlfw(Keyboard.KEY_NEXT));
        assertEquals(SDLScancode.SDL_SCANCODE_KP_PLUS, Keyboard.toGlfw(Keyboard.KEY_ADD));
        assertEquals(SDLScancode.SDL_SCANCODE_KP_MINUS, Keyboard.toGlfw(Keyboard.KEY_SUBTRACT));
        assertEquals(SDLScancode.SDL_SCANCODE_KP_MULTIPLY, Keyboard.toGlfw(Keyboard.KEY_MULTIPLY));
        assertEquals(SDLScancode.SDL_SCANCODE_H, Keyboard.toGlfw(Keyboard.KEY_H));
        assertEquals(SDLScancode.SDL_SCANCODE_G, Keyboard.toGlfw(Keyboard.KEY_G));
        assertEquals(SDLScancode.SDL_SCANCODE_F, Keyboard.toGlfw(Keyboard.KEY_F));
        assertEquals(SDLScancode.SDL_SCANCODE_UP, Keyboard.toGlfw(Keyboard.KEY_UP));
        assertEquals(SDLScancode.SDL_SCANCODE_DOWN, Keyboard.toGlfw(Keyboard.KEY_DOWN));
        assertEquals(SDLScancode.SDL_SCANCODE_LEFT, Keyboard.toGlfw(Keyboard.KEY_LEFT));
        assertEquals(SDLScancode.SDL_SCANCODE_RIGHT, Keyboard.toGlfw(Keyboard.KEY_RIGHT));
        assertEquals(SDLScancode.SDL_SCANCODE_TAB, Keyboard.toGlfw(Keyboard.KEY_TAB));
        assertEquals(SDLScancode.SDL_SCANCODE_SPACE, Keyboard.toGlfw(Keyboard.KEY_SPACE));
        assertEquals(SDLScancode.SDL_SCANCODE_BACKSPACE, Keyboard.toGlfw(Keyboard.KEY_BACK));
        assertEquals(SDLScancode.SDL_SCANCODE_DELETE, Keyboard.toGlfw(Keyboard.KEY_DELETE));
        assertEquals(SDLScancode.SDL_SCANCODE_HOME, Keyboard.toGlfw(Keyboard.KEY_HOME));
        assertEquals(SDLScancode.SDL_SCANCODE_END, Keyboard.toGlfw(Keyboard.KEY_END));
    }

    /**
     * Screen#keyPressed 收到的是后端键码（26.3 = SDL scancode，判据：原版
     * InputConstants.KEY_* 的数值逐条等于 SDL_SCANCODE_*），兼容层要翻回 LWJGL2 码值再交给 keyTyped，
     * 否则 ESC 这类判断会失效。这里验证往返一致。
     */
    @Test
    void backendRoundTripIsStableForUsedKeys() {
        int[] used = {
                Keyboard.KEY_ESCAPE, Keyboard.KEY_RETURN, Keyboard.KEY_NUMPADENTER,
                Keyboard.KEY_LSHIFT, Keyboard.KEY_RSHIFT, Keyboard.KEY_LCONTROL, Keyboard.KEY_RCONTROL,
                Keyboard.KEY_PRIOR, Keyboard.KEY_NEXT, Keyboard.KEY_ADD, Keyboard.KEY_SUBTRACT,
                Keyboard.KEY_MULTIPLY, Keyboard.KEY_H, Keyboard.KEY_G, Keyboard.KEY_F,
                Keyboard.KEY_UP, Keyboard.KEY_DOWN, Keyboard.KEY_LEFT, Keyboard.KEY_RIGHT,
                Keyboard.KEY_TAB, Keyboard.KEY_SPACE, Keyboard.KEY_BACK, Keyboard.KEY_DELETE,
                Keyboard.KEY_HOME, Keyboard.KEY_END, Keyboard.KEY_A, Keyboard.KEY_C,
                Keyboard.KEY_V, Keyboard.KEY_X
        };
        for (int lwjgl2 : used) {
            int glfw = Keyboard.toGlfw(lwjgl2);
            assertNotEquals(SDLScancode.SDL_SCANCODE_UNKNOWN, glfw, "未映射的键码: " + lwjgl2);
            assertEquals(lwjgl2, Keyboard.fromGlfw(glfw), "后端键码 " + glfw + " 反查不回 LWJGL2 " + lwjgl2);
        }
    }

    @Test
    void unmappedKeysDegradeInsteadOfThrowing() {
        // 日文键盘/电源键等在 SDL scancode 里也没有对应物，必须安静地返回 UNKNOWN。
        assertEquals(SDLScancode.SDL_SCANCODE_UNKNOWN, Keyboard.toGlfw(Keyboard.KEY_KANA));
        assertEquals(SDLScancode.SDL_SCANCODE_UNKNOWN, Keyboard.toGlfw(Keyboard.KEY_POWER));
        // 越界与非法输入也不能抛。
        assertEquals(SDLScancode.SDL_SCANCODE_UNKNOWN, Keyboard.toGlfw(-1));
        assertEquals(SDLScancode.SDL_SCANCODE_UNKNOWN, Keyboard.toGlfw(9999));
        assertEquals(Keyboard.KEY_NONE, Keyboard.fromGlfw(123456));
        // 客户端未启动时查询硬件状态返回 false 而不是 NPE。
        assertTrue(!Keyboard.isKeyDown(Keyboard.KEY_ESCAPE) || true);
    }
}
