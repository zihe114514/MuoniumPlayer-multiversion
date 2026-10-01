package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.core.settings.HudConfig;

/**
 * MuoniumPlayer 的 HUD 设置界面到 ModernUI 的插座。
 *
 * <p>本类刻意不在编译期依赖 ModernUI。ModernUI 按 Minecraft 版本发版，本项目四棵树里只有
 * 一部分拿得到构件（截至 26.2 没有对应的 ModernUI 构建），而共享源码必须四棵树都能编译，
 * 所以这里只做两件事：探测 ModernUI 在不在，以及把「打开」这个动作转交给可能存在的 provider。</p>
 *
 * <p>真正的 ModernUI 面板不在这里。它的入口 {@code MuiModApi.openScreen} 收的是一个
 * {@code Fragment} 实例，而 Fragment 必须是编译期存在的子类 —— 反射造不出来。因此面板实现放在
 * {@code com.muoniumplayer.core.screens.hud.modernui.ModernUiHudProvider}，
 * 只有拿到 ModernUI 构件的那棵版本树才会把它编进来。本树没有它时 {@link #open()} 返回
 * {@code false}，调用方按既有语义回落到内置界面，玩家不会看到失败弹窗。</p>
 *
 * @see OneConfigHudBridge 同样的「外部界面」形状，那一个桥的是 OneConfig
 */
public final class ModernUiHudBridge {

    /** ModernUI 的 modding API 门面。上游与补小版本的 mVUS 分支都有这一个类名。 */
    private static final String MUI_API_CLASS = "icyllis.modernui.mc.MuiModApi";

    /** 本构建是否编入了 ModernUI 面板实现。 */
    private static final String PROVIDER_CLASS =
            "com.muoniumplayer.core.screens.hud.modernui.ModernUiHudProvider";

    private static Boolean installed;
    private static Boolean panel;
    private static boolean actionCenterHooked;

    private ModernUiHudBridge() {
    }

    /** 玩家是否装了 ModernUI（含 {@code ModernUI-MC} 与补小版本缺口的分支构建）。 */
    public static boolean isInstalled() {
        if (installed != null) return installed;
        installed = Boolean.valueOf(find(MUI_API_CLASS) != null);
        return installed;
    }

    /** 当前这棵版本树里有没有编入面板实现（取决于构建时能否拿到 ModernUI 构件）。 */
    public static boolean hasPanel() {
        if (panel != null) return panel;
        panel = Boolean.valueOf(find(PROVIDER_CLASS) != null);
        return panel;
    }

    /** 装了 ModernUI 且本树编入了面板，才真的能接管 HUD 设置。 */
    public static boolean isAvailable() {
        return isInstalled() && hasPanel();
    }

    /** 用户是否选中了 ModernUI 这一层。 */
    public static boolean isSelected() {
        return HudConfig.HUD_EDITOR_UI_MODERNUI.equals(HudConfig.hudEditorUi);
    }

    /** 转交给面板实现。任何一环不成立都返回 {@code false}，由调用方回落内置界面。 */
    public static boolean open() {
        if (!isAvailable()) return false;
        try {
            Class<?> provider = Class.forName(PROVIDER_CLASS, true, loader());
            Object result = provider.getMethod("open").invoke(null);
            return Boolean.TRUE.equals(result);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 请求把面板挂成 ModernUI 控制中心里的一个页签。每 tick 问一次，闸门在内部：
     * 没装、本树没编入面板、或者已经挂过，都是一次布尔比较。
     *
     * <p>之所以要有这一条：控制中心是 ModernUI 自己的配置页，而它对第三方 mod 没有注册口，
     * 只能由面板实现那边运行时注入。共享这一侧只负责问一句「你那边挂得上吗」。</p>
     */
    public static boolean registerActionCenterTab() {
        if (actionCenterHooked || !isAvailable()) return actionCenterHooked;
        try {
            Class<?> provider = Class.forName(PROVIDER_CLASS, true, loader());
            Object result = provider.getMethod("registerActionCenterTab").invoke(null);
            actionCenterHooked = Boolean.TRUE.equals(result);
            return actionCenterHooked;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 给设置界面那一行用的现状文案。之所以要分开说：玩家装了 ModernUI 却看到界面没换，
     * 真实原因是「这棵版本树没有对应构件、所以面板没编进来」，不是「没检测到 ModernUI」。
     */
    public static String statusLabel() {
        if (!isInstalled()) return "未安装";
        if (!hasPanel()) return "已安装·本构建未编入面板";
        return "可用";
    }

    private static Class<?> find(String name) {
        try {
            return Class.forName(name, false, loader());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ClassLoader loader() {
        return ModernUiHudBridge.class.getClassLoader();
    }
}
