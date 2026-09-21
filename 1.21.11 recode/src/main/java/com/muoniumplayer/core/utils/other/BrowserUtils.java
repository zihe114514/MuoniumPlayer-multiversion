package com.muoniumplayer.core.utils.other;

import java.net.URI;

/**
 * 把一个地址交给系统浏览器。
 *
 * <p>两条路依次试：先反射走 {@code java.awt.Desktop}（与原版 {@code GuiScreen.openWebLink} 同一条路，
 * 用反射是为了不在错误的时机把 AWT 类加载进来），打不开再退到 LWJGL 的 {@code Sys.openURL}。
 * 两条都失败时返回 {@code false} —— 调用方应当把地址放进剪贴板，而不是什么都不说。</p>
 *
 * <p>这里<b>不</b>校验地址是否可信：白名单是各功能自己的策略（{@code UpdateChecker.isAllowedPage}），
 * 把两件事混在一个方法里，调用方就看不出「地址被拒」和「浏览器打不开」是两种不同的失败。</p>
 */
public final class BrowserUtils {

    private BrowserUtils() {
    }

    /** @return 是否成功把地址交给了浏览器 */
    public static boolean open(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        String target = url.trim();
        try {
            Class<?> desktopClass = Class.forName("java.awt.Desktop");
            Object desktop = desktopClass.getMethod("getDesktop").invoke(null);
            desktopClass.getMethod("browse", URI.class).invoke(desktop, new URI(target));
            return true;
        } catch (Throwable ignored) {
            // 无桌面环境、headless、或 URI 不合规范：换下一条路。
        }
        try {
            return com.muoniumplayer.compat.lwjgl2.Sys.openURL(target);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
