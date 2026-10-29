package com.muoniumplayer.core.screens.hud;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.settings.HudSetting;
import com.muoniumplayer.testsupport.ProjectPaths;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
// glass-taxonomy: 真玻璃这组给「界面与主题」页加了 8 条（4 个生效面 + 采集分辨率 + 3 个滑块），HudSetting 加 3 个滑块

/**
 * HUD 设置分类结构的不变量。
 *
 * <p>为什么单独钉这个：分类只是呈现层的分组，条目本身读的是 {@code HudConfig} 的字段，
 * 所以「重新分类」在功能上应该是零风险的 —— 而它最容易出的事故恰恰是搬运时漏掉一条、
 * 或者把同一条塞进两个组。这两件事都不会让任何一套界面报错，只会让用户某天发现某个滑块不见了。
 * 于是这里把「有几页、每页几条、总共多少条」钉死：改结构时这几个数字要一起改，改的人会被迫
 * 想清楚自己动的是分组还是条目。</p>
 */
class HudSettingsTaxonomyStaticTest {

    private static final List<String> TITLES = Arrays.asList(
            "歌词样式", "3D 歌词", "面板与卡片", "灵动岛", "无缝切换", "界面与主题", "信息栏与封面", "缓存与公告");

    /** 与 {@link #TITLES} 同序的条目数；总和必须等于 {@link #TOTAL}。 */
    private static final List<Integer> COUNTS = Arrays.asList(28, 20, 9, 25, 21, 33, 6, 3);

    /** 「界面与主题」那页 2026-09-30 从 19 条长到 21 条：真玻璃材质组多了「实时背景采集」与
     *  「帧率不够时自动摘玻璃」两个开关；同日傍晚又长到 26 条：多了四面各自的模糊倍率
     *  与「玻璃亮部溢出」；夜里再到 27 条：多了「玻璃融合半径」。「无缝切换」那页 10-01
     *  从 20 条长到 21 条：多了「切点标记」；现在 22 条，加上「动画样式」；「界面与主题」新增全宽频谱柱数快捷档与自定义档。
     *  2026-10-16 新增第 2 页「3D 歌词」15 条（放置 6 / 字体与文本 3 / 发光 3 / 动效 3）；
     *  2026-10-17 「文字颜色跟随」toggle 升级为取色条目（数目不变），随后移除「文字抖动」
     *  —— 「3D 歌词」页 15 → 14 条（动效组剩 逐词弹出 / 节拍闪烁）。
     *  2026-10-23 动效组加回「心象动效」后又新增「绘光动效」—— 3D 歌词页 14 → 16 条。
     *  2026-10-24 移除经典/Folia 样式与死开关四条下架；条目整合：3D 动效三开关并成「动效风格」单选
     *  并新增「发光颜色」取色与「关键字染色」（绘光配套）；灵动岛新增「岛内搜索」与「动画速度」档位；
     *  无缝切换「引擎+表现+进阶」三合一成「处理方案」四档 —— 132 → 128。
     *  2026-10-27 「3D 歌词」页放置组新增「更激进的适配」（光影包激活时歌词推迟到
     *  HUD 阶段用原版着色器直画）—— 3D 歌词页 18 → 19 条，128 → 133（期间
     *  「界面与主题」页增长 5 条未被此文件记录，见 COUNTS）。
     *  2026-10-28 「灵动岛」页外观组新增「灵动岛位置」（Y 轴上下偏移滑条）—— 灵动岛页
     *  24 → 25 条，134 → 135。
     *  2026-10-29 「歌词样式」页当前行组新增「桌面歌词取色跟随封面」（仅经典样式），
     *  「信息栏与封面」页显示与摆放组新增「信息卡背景」（默认/液态玻璃/完全透明）——
     *  歌词样式页 21 → 22 条、信息栏与封面页 5 → 6 条，135 → 137。
     *  2026-10-29 常用前置重排：第一页顶部新增「常用」快捷组（桌面歌词样式/3D 动效
     *  风格/灵动岛样式/信息卡背景/播放器主题/频谱配色六条 CHOICE 的<b>同对象引用</b>，
     *  原位保留）—— 歌词样式页 22 → 28（+6 引用）；「3D 歌词」页独立「动效」组并入
     *  「放置」组（同对象引用两处计）19 → 20；「面板与卡片」页样式条目同时出现在
     *  「样式与大小」与「卡片样式」两组 8 → 9 —— 引用计数使总数 137 → 143（功能条目
     *  实际没有增减，滑条唯一性不受影响：引用的六条全是 CHOICE）。 */
    private static final int TOTAL = 145;

    @Test
    @DisplayName("分类就是这 7 页，顺序与每页条目数都不许悄悄变")
    void categoriesAndCountsArePinned() {
        HudSettingsModel.Category[] categories = HudSettingsModel.categories();
        List<String> titles = new ArrayList<String>();
        List<Integer> counts = new ArrayList<Integer>();
        for (HudSettingsModel.Category category : categories) {
            titles.add(category.title);
            counts.add(count(category));
        }
        assertEquals(TITLES, titles, "分类名或顺序变了：改结构可以，但要连同本测试的预期一起改，"
                + "并确认没有条目在搬运中丢失");
        assertEquals(COUNTS, counts, "某一页的条目数变了");
        assertEquals(TOTAL, sum(counts), "条目总数不再是 " + TOTAL + " 条：重新分类不该增减条目");
    }

    @Test
    @DisplayName("分类总数封顶：ModernUI 面板的分类条是横排，页多了第 7 格起会被窗口裁掉")
    void categoryCountStaysWithinSidebarBudget() {
        assertTrue(HudSettingsModel.categories().length <= 8,
                "分类页超过 8 个就又回到「侧栏一半是空页」的老问题了");
    }

    @Test
    @DisplayName("没有空的分组，也没有「基础」这种不知道在说什么的组名")
    void groupsAreNamedAndNotEmpty() {
        for (HudSettingsModel.Category category : HudSettingsModel.categories()) {
            assertTrue(category.groups.length >= 2, category.title + " 只剩一组，那这一组就不必存在");
            Set<String> seen = new HashSet<String>();
            for (HudSettingsModel.Group group : category.groups) {
                assertTrue(group.entries.length > 0, category.title + " / " + group.title + " 是空组");
                assertFalse("基础".equals(group.title),
                        category.title + " 里又有叫「基础」的组：三个分类各有一个「基础」就是这次要收掉的东西");
                assertTrue(seen.add(group.title), category.title + " 里组名重复：" + group.title);
            }
        }
    }

    @Test
    @DisplayName("滑块条目不重复，覆盖 54 个生效设置，不暴露退役的毛玻璃 Bloom / Fusion")
    void slidersAppearExactlyOnce() {
        List<String> settings = new ArrayList<String>();
        for (HudSettingsModel.Category category : HudSettingsModel.categories()) {
            for (HudSettingsModel.Group group : category.groups) {
                for (HudSettingsModel.Entry entry : group.entries) {
                    if (entry.kind == HudSettingsModel.Kind.SLIDER && entry.setting != null) {
                        settings.add(entry.setting.name());
                    }
                }
            }
        }
        // 2026-10-16 起 3D 歌词那页带 5 个滑块（距离/散开角/缩放/发光强度/发光扩散）；
        // 2026-10-24 「设置界面缩放」随 Folia 样式一起下架，54 → 53；
        // 2026-10-28 灵动岛新增「灵动岛位置」Y 偏移滑条，53 → 54。
        assertEquals(54, settings.size(), "滑块条目数变了：生效设置应恰好出现一次；旧 GLASS_BLOOM / GLASS_FUSION 仅保留读档兼容；频谱自定义柱数、透明度与高度都是有效设置");
        assertFalse(settings.contains("GLASS_BLOOM"), "退役的磨砂 bloom 不能作为无效设置暴露");
        assertFalse(settings.contains("GLASS_FUSION"), "参考透镜不使用旧融合描边");
        assertEquals(settings.size(), new HashSet<String>(settings).size(),
                "同一个 HudSetting 被塞进了两个组：" + duplicates(settings));
    }

    @Test
    @DisplayName("桌面歌词取色两项在歌词页、3D 歌词取色在 3D 页、主题色卡在界面与主题页")
    void specialEntriesStayOnTheirPage() {
        List<String> colorPages = new ArrayList<String>();
        List<String> gridPages = new ArrayList<String>();
        Set<HudSettingsModel.ColorTarget> targets = new HashSet<HudSettingsModel.ColorTarget>();
        for (HudSettingsModel.Category category : HudSettingsModel.categories()) {
            for (HudSettingsModel.Group group : category.groups) {
                for (HudSettingsModel.Entry entry : group.entries) {
                    if (entry.kind == HudSettingsModel.Kind.COLOR) {
                        colorPages.add(category.title);
                        targets.add(entry.colorTarget);
                    }
                    if (entry.kind == HudSettingsModel.Kind.THEME_GRID) {
                        gridPages.add(category.title);
                    }
                }
            }
        }
        assertEquals(Arrays.asList("歌词样式", "歌词样式", "3D 歌词", "3D 歌词"), colorPages,
                "桌面歌词的两个取色条目同页（一个地方配完），3D 歌词的文字色与发光色取色在自己那一页");
        assertTrue(targets.contains(HudSettingsModel.ColorTarget.CURRENT)
                        && targets.contains(HudSettingsModel.ColorTarget.NORMAL),
                "当前行与普通行的颜色入口少了一个");
        assertTrue(targets.contains(HudSettingsModel.ColorTarget.WORLD_LYRICS),
                "3D 歌词的文字颜色取色入口缺失");
        assertEquals(Arrays.asList("界面与主题"), gridPages, "主题色卡的位置决定侧栏要不要为它单开一页");
    }

    @Test
    @DisplayName("全宽频谱柱数提供多档预设，并仅在自定义时展示精细滑块")
    void spectrumBarCountPresetsExposeConditionalCustomSlider() {
        HudSettingsModel.Entry preset = null;
        HudSettingsModel.Entry custom = null;
        for (HudSettingsModel.Category category : HudSettingsModel.categories()) {
            for (HudSettingsModel.Group group : category.groups) {
                for (HudSettingsModel.Entry entry : group.entries) {
                    if ("全宽频谱柱数".equals(entry.getLabel())) preset = entry;
                    if (entry.setting == HudSetting.SPECTRUM_BAR_COUNT_CUSTOM) custom = entry;
                }
            }
        }
        assertTrue(preset != null, "缺少全宽频谱柱数快捷档");
        assertArrayEquals(new String[]{"64 根", "96 根", "128 根", "160 根", "192 根", "自定义"},
                preset.getChoices());
        assertTrue(custom != null, "缺少自定义柱数滑块");
        int previousPreset = HudConfig.spectrumBarCountPreset;
        try {
            HudConfig.spectrumBarCountPreset = 0;
            assertFalse(custom.isVisible(), "预设档位下不应显示无效的自定义滑块");
            HudConfig.spectrumBarCountPreset = 5;
            assertTrue(custom.isVisible(), "切到自定义后必须立即显示精细调节滑块");
            int previousCount = HudConfig.spectrumCustomBarCount;
            try {
                HudSetting.SPECTRUM_BAR_COUNT_CUSTOM.setValue(999f);
                assertEquals(256, HudConfig.spectrumCustomBarCount, "自定义上限必须限制在可读且可批量绘制的范围");
                HudSetting.SPECTRUM_BAR_COUNT_CUSTOM.setValue(48f);
                assertEquals(48, HudConfig.spectrumCustomBarCount, "自定义下限应保持合理的柱间距");
            } finally {
                HudConfig.spectrumCustomBarCount = previousCount;
            }
        } finally {
            HudConfig.spectrumBarCountPreset = previousPreset;
        }
    }

    private static int count(HudSettingsModel.Category category) {
        int total = 0;
        for (HudSettingsModel.Group group : category.groups) total += group.entries.length;
        return total;
    }

    private static int sum(List<Integer> values) {
        int total = 0;
        for (int value : values) total += value;
        return total;
    }

    private static Set<String> duplicates(List<String> values) {
        Set<String> seen = new HashSet<String>();
        Set<String> dup = new HashSet<String>();
        for (String value : values) {
            if (!seen.add(value)) dup.add(value);
        }
        return dup;
    }
}
