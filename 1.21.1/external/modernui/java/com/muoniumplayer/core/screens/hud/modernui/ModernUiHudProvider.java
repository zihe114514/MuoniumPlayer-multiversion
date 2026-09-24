package com.muoniumplayer.core.screens.hud.modernui;

import icyllis.modernui.mc.MuiModApi;

/**
 * ModernUI 版 HUD 设置面板的入口。
 *
 * <p>共享源码里的 {@code ModernUiHudBridge} 探测到这个类就算「本构建编入了面板」，
 * 然后把打开动作转交过来。为什么要有这么一层：ModernUI 的入口
 * {@link MuiModApi#openScreen(icyllis.modernui.fragment.Fragment)} 收的是 Fragment 实例，
 * 子类必须编译期存在，反射造不出来 —— 所以面板只能留在拿得到 ModernUI 构件的版本树里，
 * 共享部分保持零依赖。</p>
 *
 * <p>失败不报错：任何异常都只让 {@link #open()} 返回 false，调用方按既有语义回落到内置界面，
 * 与 OneConfig 那条桥的做法一致。</p>
 */
public final class ModernUiHudProvider {

    /** 诊断用：桥每 tick 都会问一次，只在第一次问的时候留一行日志。 */
    private static boolean announced;

    private ModernUiHudProvider() {
    }

    /** @return 是否真的把 ModernUI 面板开起来了；false 表示调用方应当回落到内置界面 */
    public static boolean open() {
        try {
            MuiModApi.openScreen(new HudSettingsFragment());
            return true;
        } catch (Throwable failure) {
            ActionCenterTab.dlog("openScreen(HudSettingsFragment) 失败，回落内置界面：" + failure);
            return false;
        }
    }

    /**
     * 把面板挂成 ModernUI 控制中心的一个页签。
     *
     * <p>控制中心没有对外注册口，这件事只能靠运行时注入，细节与代价写在 {@link ActionCenterTab}。
     * 幂等：重复调用只会挂一个监听。</p>
     *
     * @return 注册成功（不代表页签一定出现得了 —— 结构对不上时它会自己放弃）
     */
    public static boolean registerActionCenterTab() {
        boolean result = ActionCenterTab.register();
        if (!announced) {
            announced = true;
            ActionCenterTab.dlog("桥已经把这个动作交给我们了（结果 " + result + "）");
        }
        return result;
    }
}
