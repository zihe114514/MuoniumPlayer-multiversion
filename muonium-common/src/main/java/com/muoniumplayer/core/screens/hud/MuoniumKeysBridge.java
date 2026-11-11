package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.compat.mc.KeyMappingProbe;

/**
 * 把主项目的按键绑定讲给 MuoniumConfig 界面的桥。
 *
 * <h2>为什么需要它</h2>
 * MuoniumConfig 的欢迎通知（进入世界时右下角弹出）原本只说「按 X 打开界面」。
 * 但玩家的桌面上其实有两个界面：MuoniumConfig 的设置界面，和播放器自己的主界面。
 * 只说一个键，玩家会以为另一个也能打开，或者根本不知道播放器还有个快捷键 ——
 * 这条通知是唯一一次能把两件事同时讲清楚的机会。
 *
 * <h2>为什么走反射</h2>
 * MuoniumConfig 是运行时下载到 mods/ 的独立 mod，编译期看不到本仓库；反过来本仓库也
 * 不依赖它。两边只在运行时用类名/方法名对话 —— 与 {@link MuoniumHudProperties} 等
 * 其余几个桥同一套契约。<b>改本类的名字或方法签名 = 破坏契约</b>，表现是那条通知里
 * 少一句话（静默降级，不会崩）。
 *
 * <h2>按键名必须是当前值，不是默认值</h2>
 * 玩家可能把「打开音乐界面」从默认的右 Shift 改成别的键。这里取的是
 * {@code KeyMapping.getTranslatedKeyMessage()}，也就是「选项 → 控制」里显示的那个名字
 * —— 玩家改过就跟着变。绝不硬编码 "右 Shift"。
 */
public final class MuoniumKeysBridge {

    private MuoniumKeysBridge() {
    }

    /**
     * 「打开播放器主界面」当前绑定的按键显示名，例如 {@code 右 Shift} / {@code H}。
     *
     * @return 可直接拼进文案的按键名；未绑定或拿不到时返回空串（绝不返回 null）
     */
    public static String openPlayerKeyName() {
        try {
            return KeyMappingProbe.displayName(com.muoniumplayer.MuoniumPlayerClient.keyOpenMusic);
        } catch (Throwable broken) {
            // keyOpenMusic 还没构造（极早期调用）或该版本少了字段：静默降级。
            return "";
        }
    }

    /**
     * 「编辑 HUD 位置」当前绑定的按键显示名。
     *
     * <p>与 MuoniumConfig 自己的界面键是两回事，分开暴露，免得文案把两者混为一谈。</p>
     *
     * @return 可直接拼进文案的按键名；未绑定或拿不到时返回空串
     */
    public static String editHudKeyName() {
        try {
            return KeyMappingProbe.displayName(com.muoniumplayer.MuoniumPlayerClient.keyEditHud);
        } catch (Throwable broken) {
            return "";
        }
    }
}
