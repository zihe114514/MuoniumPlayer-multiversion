package com.muoniumplayer.compat.lwjgl2;

import net.minecraft.Util;

/**
 * LWJGL 2 {@code org.lwjgl.Sys} 里本项目唯一用到的 {@code openURL}。
 * LWJGL3 删掉了 Sys；1.20.1 的等价物是 {@code Util.getPlatform().openUri(url)}。
 */
public final class Sys {

    private Sys() {
    }

    /** @return 是否成功交给系统处理（异常即视为失败，调用方 GuiFfmpegNotice 会退到剪贴板方案） */
    public static boolean openURL(String url) {
        try {
            Util.getPlatform().openUri(url);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
