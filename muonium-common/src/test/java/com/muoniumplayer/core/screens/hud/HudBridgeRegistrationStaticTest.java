package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.core.settings.HudConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OneConfig 桥的注册策略回归测试。
 *
 * <p>钉的是 2026-11-08 修掉的那类事故：<b>注册时按「当前可见/可用」筛条目</b>。
 * 3D 歌词总开关出厂为关，它的二十多个子项全挂着 {@code visibleWhen(3D 歌词开)}，
 * 于是首次打开 MuoniumConfig 时整页只剩总开关与三个不带条件的项；而树只在启动时
 * 注册一次、之后不重建，用户把 3D 打开也补不回来 —— 界面「严重缺项」就是这么来的。</p>
 *
 * <p>现在所有条目都进树，可见与锁死改由宿主 {@code Display.HIDDEN / DISABLED} 表达，
 * 值一变就重算。这组断言不依赖 OneConfig：注册策略被抽成
 * {@link OneConfigHudBridge#shouldRegister} 与
 * {@link OneConfigHudBridge#displayStateOf} 两个纯函数，直接对真实模型求值。</p>
 */
class HudBridgeRegistrationStaticTest {

    private static final String WORLD_LYRICS_CATEGORY = "3D 歌词";

    /** 3D 栏目在出厂状态下的条目：这些必须全部进树，一个都不能少。 */
    private static List<HudSettingsModel.Entry> worldLyricsEntries() {
        List<HudSettingsModel.Entry> out = new ArrayList<>();
        for (HudSettingsModel.Category category : HudSettingsModel.categories()) {
            if (!WORLD_LYRICS_CATEGORY.equals(category.title)) continue;
            for (HudSettingsModel.Group group : category.groups) {
                for (HudSettingsModel.Entry entry : group.entries) out.add(entry);
            }
        }
        return out;
    }

    @Test
    @DisplayName("3D 歌词栏目出厂默认关闭时，每一条仍然全部注册进树")
    void worldLyricsEntriesAreAllRegisteredEvenWhileDisabled() {
        boolean previous = HudConfig.worldLyricsEnabled;
        HudConfig.worldLyricsEnabled = false;
        try {
            List<HudSettingsModel.Entry> entries = worldLyricsEntries();
            assertFalse(entries.isEmpty(), "没找到「3D 歌词」分类：分类改名了就要同步这里的常量");

            List<String> skipped = new ArrayList<>();
            for (HudSettingsModel.Entry entry : entries) {
                if (!OneConfigHudBridge.shouldRegister(entry)) skipped.add(entry.getLabel());
            }
            assertTrue(skipped.isEmpty(),
                    "3D 总开关关闭时这些条目被挡在树外，用户打开总开关后也永远看不到它们：" + skipped);
        } finally {
            HudConfig.worldLyricsEnabled = previous;
        }
    }

    @Test
    @DisplayName("总开关关闭时子项一个都不许消失，只许置灰")
    void dependentEntriesStayVisibleButDisabled() {
        boolean previous = HudConfig.worldLyricsEnabled;
        HudConfig.worldLyricsEnabled = false;
        try {
            int hidden = 0;
            int disabled = 0;
            for (HudSettingsModel.Entry entry : worldLyricsEntries()) {
                OneConfigHudBridge.DisplayState state = OneConfigHudBridge.displayStateOf(entry);
                if (state == OneConfigHudBridge.DisplayState.HIDDEN) hidden++;
                if (state == OneConfigHudBridge.DisplayState.DISABLED) disabled++;
            }
            assertEquals(0, hidden,
                    "有 " + hidden + " 条被隐藏了。用户要求首次打开就看到全部选项，"
                            + "前置条件没满足的项应当置灰而不是消失");
            assertTrue(disabled >= 20,
                    "出厂关闭状态下只有 " + disabled + " 条处于置灰，与「二十多个子项挂着"
                            + "visibleWhen(3D 歌词开)」的预期不符：条件谓词可能被改了，"
                            + "本测试的防护面随之失效");
        } finally {
            HudConfig.worldLyricsEnabled = previous;
        }
    }

    @Test
    @DisplayName("打开 3D 总开关后，原来置灰的子项会转为可用（条件跟着值走）")
    void enablingWorldLyricsUnlocksTheDependentEntries() {
        boolean previous = HudConfig.worldLyricsEnabled;
        try {
            HudConfig.worldLyricsEnabled = false;
            int disabledBefore = countDisabled(worldLyricsEntries());
            HudConfig.worldLyricsEnabled = true;
            int disabledAfter = countDisabled(worldLyricsEntries());

            assertTrue(disabledBefore > disabledAfter,
                    "打开 3D 总开关后置灰条目数没有减少（" + disabledBefore + " → " + disabledAfter
                            + "）：置灰条件没接到实时状态上");
        } finally {
            HudConfig.worldLyricsEnabled = previous;
        }
    }

    @Test
    @DisplayName("全模型只有色卡网格被排除；其余每一条都进树")
    void onlyThemeGridIsExcludedFromRegistration() {
        int total = 0;
        int registered = 0;
        List<String> grids = new ArrayList<>();
        for (HudSettingsModel.Category category : HudSettingsModel.categories()) {
            for (HudSettingsModel.Group group : category.groups) {
                for (HudSettingsModel.Entry entry : group.entries) {
                    total++;
                    if (OneConfigHudBridge.shouldRegister(entry)) {
                        registered++;
                    } else if (entry.kind == HudSettingsModel.Kind.THEME_GRID) {
                        grids.add(category.title + " / " + group.title);
                    } else {
                        throw new AssertionError("「" + entry.getLabel() + "」不是色卡网格却被排除在树外");
                    }
                }
            }
        }
        assertTrue(total > 0, "模型是空的");
        assertEquals(1, grids.size(),
                "色卡网格应恰好一个（同组的播放器主题下拉是它的等价入口）；实际：" + grids);
        assertEquals(total - 1, registered, "除色卡网格外每一条都该注册进树");
    }

    @Test
    @DisplayName("锁死条目显示为 DISABLED，而不是消失")
    void lockedEntriesBecomeDisabledNotHidden() {
        boolean previous = HudConfig.worldLyricsEnabled;
        HudConfig.worldLyricsEnabled = true;
        try {
            // 手动色板只在「封面代表色 = 手动」时解锁；出厂是「关」，四条色板因此锁死。
            HudConfig.worldLyricsCoverColorMode = 0;
            int disabled = 0;
            for (HudSettingsModel.Entry entry : worldLyricsEntries()) {
                if (OneConfigHudBridge.displayStateOf(entry) == OneConfigHudBridge.DisplayState.DISABLED) {
                    disabled++;
                }
            }
            assertTrue(disabled >= 4,
                    "封面代表色为「关」时手动色板应锁死并置灰（厂商承诺「看得到但用不了」），"
                            + "实际只有 " + disabled + " 条 DISABLED");
        } finally {
            HudConfig.worldLyricsEnabled = previous;
        }
    }

    private static int countDisabled(List<HudSettingsModel.Entry> entries) {
        int disabled = 0;
        for (HudSettingsModel.Entry entry : entries) {
            if (OneConfigHudBridge.displayStateOf(entry) == OneConfigHudBridge.DisplayState.DISABLED) {
                disabled++;
            }
        }
        return disabled;
    }
}
