package com.muoniumplayer.compat.lwjgl2;

import com.mojang.blaze3d.Blaze3D;

import java.net.URI;

/**
 * LWJGL 2 {@code org.lwjgl.Sys} 里本项目唯一用到的 {@code openURL}。
 * LWJGL3 删掉了 Sys；26.2 的等价物是 {@code Util.getPlatform().openUri(url)}。
 * 26.3 起 {@code Util#getPlatform()} 还在（返回 {@code Util$OS}），但 OS 上的
 * {@code openUri(String)} 被删掉了 —— 开链接的入口改成静态的
 * {@link Blaze3D#openUri(URI)}，而且只收 URI，不再收 String。
 */
public final class Sys {

    private Sys() {
    }

    /** @return 是否成功交给系统处理（异常即视为失败，调用方 GuiFfmpegNotice 会退到剪贴板方案） */
    public static boolean openURL(String url) {
        try {
            Blaze3D.openUri(URI.create(url));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
