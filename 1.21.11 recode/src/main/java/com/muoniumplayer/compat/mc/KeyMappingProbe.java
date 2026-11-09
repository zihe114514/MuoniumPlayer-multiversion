package com.muoniumplayer.compat.mc;

import net.minecraft.client.KeyMapping;

/**
 * 「这个按键当前绑成了什么」—— 各版本一份的 MC 触点垫片。
 *
 * <p>共享层（{@code core/screens/hud/MuoniumKeysBridge}）只调
 * {@link #displayName(KeyMapping)} 这一个名字；各树按自己的 MC 版本实现。
 * 七份必须是<b>同名同签名</b>，否则共享层编不过 —— 这是仓库铁律 2。</p>
 *
 * <h2>为什么需要垫片</h2>
 * {@code KeyMapping} 是 MC 类，共享层不碰 MC 类型（那会让七棵树各自编出一套语义）。
 * 「怎么把按键变成人话」在 1.20.1 ~ 26.3 之间改过名字，也必须按版本分开写。
 */
public final class KeyMappingProbe {

    private KeyMappingProbe() {
    }

    /**
     * 按键的显示名，取「选项 → 控制」里给玩家看的那个名字。
     *
     * <p>语义要求（七份必须一致）：</p>
     * <ul>
     *   <li>玩家改过绑定 → 返回改过之后的键名（绝不用默认值）；</li>
     *   <li>未绑定 / 传 null / 任何异常 → 返回空串，<b>不返回 null</b>，也不返回 "未绑定"；
     *       调用方靠空串决定"这一句不提"，而不是靠字符串匹配。</li>
     * </ul>
     */
    public static String displayName(KeyMapping mapping) {
        if (mapping == null) {
            return "";
        }
        try {
            if (mapping.isUnbound()) {
                return "";
            }
            return mapping.getTranslatedKeyMessage().getString();
        } catch (Throwable broken) {
            // 极早期（键盘映射还没构造完）或运行时的意外：静默降级成"不提这个键"。
            return "";
        }
    }
}
