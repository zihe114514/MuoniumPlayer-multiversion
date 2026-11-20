package com.muoniumplayer.core.utils;

import java.lang.reflect.Method;

/**
 * IMBlocker（io.github.reserveword:imblocker）兼容桥。
 *
 * <h2>为什么需要它</h2>
 * IMBlocker 的本质是「窗口级 IME 开关」：Windows 上用 ImmAssociateContext 把整个
 * 游戏窗口与系统输入法的关联挂上/摘掉，开关由它自己的焦点系统决定——只有它
 * Mixin 认识的控件（原版 EditBox、聊天屏以及其适配清单里的模组组件）能请求打开
 * IME，其余一律按「游戏界面」处理为关闭。我们的播放器是全自绘 GUI，
 * {@code TextField} 的焦点是私有字段，IMBlocker 对其完全不可见，于是打开播放器
 * 的那一刻它就把整个窗口的 IME 摘掉，搜索框等所有输入框都调不出输入法。
 *
 * 播放器打开时请求开启输入法；通过宿主桥关闭播放器并确认已回到游戏后，
 * 请求关闭输入法。其他目标界面继续由 IMBlocker 管理。
 * 未安装时不执行任何操作；具体输入法状态仍由 IMBlocker 和系统决定。
 */
public final class IMBlockerCompat {

    private static volatile boolean probed;
    private static volatile boolean available;
    private static Method setState;

    private IMBlockerCompat() {
    }

    /** 请求打开窗口级 IME；未安装 IMBlocker 时为空操作。 */
    public static void keepIMEOn() {
        setIMEState(true);
    }

    /** 仅在播放器关闭且确认返回游戏时调用。 */
    public static void restoreGameIMEState() {
        setIMEState(false);
    }

    private static void setIMEState(boolean enabled) {
        if (!probed) {
            probe();
        }
        if (!available) {
            return;
        }
        try {
            setState.invoke(null, enabled);
        } catch (Throwable ignored) {
        }
    }

    private static void probe() {
        probed = true;
        try {
            Class<?> cls = Class.forName("io.github.reserveword.imblocker.common.IMManager");
            setState = cls.getMethod("setState", boolean.class);
            available = true;
        } catch (Throwable t) {
            available = false;
        }
    }
}
