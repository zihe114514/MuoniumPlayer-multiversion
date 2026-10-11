package com.muoniumplayer.core.utils;

import java.lang.reflect.Method;

/**
 * IMBlocker（io.github.reserveword:imblocker）兼容桥。
 *
 * <h2>为什么需要它</h2>
 * IMBlocker 的本质是「窗口级 IME 开关」：Windows 上用 ImmAssociateContext 把整个
 * GLFW 窗口与系统输入法的关联挂上/摘掉，开关由它自己的焦点系统决定——只有它
 * Mixin 认识的控件（原版 EditBox、聊天屏以及其适配清单里的模组组件）能请求打开
 * IME，其余一律按「游戏界面」处理为关闭。我们的播放器是全自绘 GUI，
 * {@code TextField} 的焦点是私有字段，IMBlocker 对其完全不可见，于是打开播放器
 * 的那一刻它就把整个窗口的 IME 摘掉，搜索框等所有输入框都调不出输入法。
 *
 * <h2>桥接策略（镜像屏幕白名单语义）</h2>
 * 不实现它的焦点接口，而是模拟 screenWhitelist 的效果：播放器屏幕打开时、以及任一
 * 自绘输入框获得焦点时，反射调用 {@code IMManager.setState(true)} 把 IME 挂回来。
 * 它在 setScreen HEAD 处入队的 setState(false) 比我们早，主线程任务队列 FIFO，
 * 我们必胜。屏幕关闭后无需恢复——任何一次界面切换都会让 IMBlocker 重新评估并把
 * IME 关掉。
 *
 * <p>未安装 IMBlocker 时反射探测失败一次，此后每次调用只是一次布尔判断，零副作用。
 * 全反射、无编译依赖，五个版本分支共用这一份。
 */
public final class IMBlockerCompat {

    private static volatile boolean probed;
    private static volatile boolean available;
    private static Method setState;

    private IMBlockerCompat() {
    }

    /** 请求打开窗口级 IME；未安装 IMBlocker 时为空操作。 */
    public static void keepIMEOn() {
        if (!probed) {
            probe();
        }
        if (!available) {
            return;
        }
        try {
            setState.invoke(null, true);
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
