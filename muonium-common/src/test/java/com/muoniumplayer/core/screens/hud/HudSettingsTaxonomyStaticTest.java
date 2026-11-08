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
            "常用", "歌词样式", "面板与卡片", "灵动岛", "信息栏与封面", "界面与主题", "播放", "3D 歌词", "模板预设", "缓存与公告");
    /**
     * 3D 页条目数按树分支：26.x 的 3D 页有「封面代表色」四色板与「封面渐变」
     * （取色 8 条），1.20.1~1.21.11 的面板还没搬这组（取色只有文字色/发光色 2 条），
     * 3D 页 23 条。共享测试一份源跑五棵树 —— 基准随树取，禁止悄悄改某一个数的口径
     * 与 26.x 版注释同款。
     *
     * <p>2026-11-05 「发光」组新增「发光形态」choice（经典 / 辉光），三页里
     * 只有 3D 歌词页 +1（30 → 31）；其余页不变。</p>
     */
    private static final List<Integer> COUNTS =
            Arrays.asList(12, 22, 9, 28, 4, 33, 48, 31, 4, 3);

    /**
     * 「常用」页只在 26.3 起存在。
     *
     * <p>其余六棵树还没搬这次分类重构（它们的 {@code HudSettingsModel} 是各自目录下的副本）。
     * 共享测试一份源跑七棵树，所以预期值必须按树取 —— 否则要么 26.3 过不了、要么其余六棵全红。
     * 等分类重构下沉到其余树时删掉这个开关，把 TITLES/COUNTS 收回单一口径。</p>
     */
    private static boolean hasFavoritesPage() {
        return "26.3".equals(versionName());
    }

    private static List<String> expectedTitles() {
        return hasFavoritesPage()
                ? TITLES
                // 其余六树的旧结构（2026-11-06 之前）
                : Arrays.asList("歌词样式", "3D 歌词", "面板与卡片", "灵动岛",
                        "播放", "界面与主题", "信息栏与封面", "模板预设", "缓存与公告");
    }

    private static List<Integer> expectedCounts() {
        return hasFavoritesPage()
                ? COUNTS
                : Arrays.asList(28, 31, 9, 25, 48, 33, 6, 4, 3);
    }

    private static int expectedTotal() {
        return hasFavoritesPage() ? TOTAL : 187;
    }

    private static String versionName() {
        return ProjectPaths.versionRoot().getFileName().toString();
    }

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
     *  实际没有增减，滑条唯一性不受影响：引用的六条全是 CHOICE）。
     *  2026-10-30 「3D 歌词」页放置组新增「显示翻译」（主句下方小号暗色副行，
     *  与 2D 界面同一数据口；心象模式不画）—— 3D 歌词页 20 → 21 条，145 → 146。
     *  同日「显示翻译」升三档 choice（关/整行淡入/逐字点亮，条目数不变），
     *  放置组新增「翻译副行特效」（仅逐字点亮档可见）—— 3D 歌词页 21 → 22 条，
     *  146 → 147。
     *  2026-10-31 「无缝切换」分类随语义改名为「播放」（交接 + 输出修饰），其下新增
     *  「场景声学」组 5 条（总开关 / 水面之下 / 封闭空间 / 降水天气 / 强度四档 choice；
     *  细调滑条在音效调音台的场景声学子页）—— 播放页 21 → 26 条，147 → 152。
     *  2026-11-01 「3D 歌词」页发光组新增「发光颜色」（跟随字色/自定义 两档 choice；
     *  晕/墨分离发光改造的配套条目，取色器条目在自定义档外锁定不变）——
     *  3D 歌词页 28 → 29 条，152 → 153。
     *  同日「封面代表色」（关/自动提取/手动三档 choice）+「封面渐变」toggle +
     *  手动色板 4 个取色条目同步到五树（此前 26.x 独有的组完成回迁）——
     *  3D 歌词页 29 → 35 条（取色 8 条），153 → 159；按树分支的
     *  LEGACY_PALETTE_ABSENT 旧口径随之删除。
     *  同日发光组新增「发光质量」choice（Standard/High/Ultra 三档分层管线，默认 High）——
     *  3D 歌词页 +1 条。
     *  2026-11-03 「播放」页新增「切出游戏时」组 2 条（失焦压低音乐 toggle +
     *  压低到 四档 choice，后者仅在开关打开时可见）—— 播放页 26 → 28 条，160 → 162。
     *  同日「播放」页新增「通透模式」组 3 条（"通透模式" toggle + 压缩幅度 / 变宽幅度
     *  两条滑条；开关与幅度存共享层 TransparentMode 自己的配置文件，不经 HudConfig）——
     *  播放页 28 → 31 条，162 → 165。
     *  2026-11-05 「播放」页新增「卡点运镜」组 10 条（总开关 + 挂机多久启动/切换频率/
     *  最短镜头/运镜速度/转场效果五个 choice + 隐藏 HUD/电影黑边/追踪生物/鼓点变焦
     *  四个 toggle；开关与全部参数存共享层 CineSettings 自己的 cine.json，不经
     *  HudConfig；后九条仅在总开关打开时可见）—— 播放页 31 → 41 条，165 → 175；
     *  2026-11-05 3D 歌词页「发光形态」+1，175 → 176。
     *  2026-11-06 「常用」提升为顶层第 1 页（12 条：6 个组件总开关 + 6 个常用样式档位，
     *  后六条与各自原位是同一批 Entry 对象的引用）。同时清掉两处重复：
     *  「歌词样式」页顶部的「常用」组 6 条随提升移出（28 → 22）；
     *  「信息栏与封面」页的「歌曲信息栏 / 桌面歌词」两个 toggle 移入新页
     *  （6 → 4）。总数 176 → 180：净增 4 条来自新页多出来的四个总开关
     *  （灵动岛 / 音乐频谱 / 3D 歌词 / 场景声学）—— 它们原先只存在于各自分类里，
     *  现在是同一对象的引用，两个位置都可操作。
     *  分类从 8 页变 9 页，排序改由 {@code CATEGORY_ORDER} 表决定（声明顺序不再等于展示顺序）。
     *  2026-11-08 「播放」页「通透模式」组新增「陀螺模式」4 条（toggle + 转给谁看/转谁
     *  两个 choice + 每拍转角滑条；开关与档位存共享层 MusicSpin 自己的 music_spin.json，
     *  不经 HudConfig；choice/滑条仅在开关打开时可见）—— 播放页 41 → 45 条，187 → 191。
     *  同日组内再添「头乱晃」toggle + 「头部动作」choice（乱晃/低头/360°转头；头部
     *  动作仅在两个开关都打开时可见；值存同一 music_spin.json）—— 播放页 +2。
     *  同日七树「播放」页新增「语音广播」toggle（26.3 是独立组，其余六树接在
     *  「切出游戏时」组尾；状态存共享层 VoicechatBroadcast 自己的 voicechat_broadcast.json，
     *  不经 HudConfig）—— 播放页 45 → 46 条，191 → 192；其余六树旧口径 184 → 185。 */
    // 独立模板预设栏目只增加四条 ACTION，不回搬各树原有分类或设置条目。
    private static final int TOTAL = 194;

    @Test
    @DisplayName("分类顺序、每页条目数和独立模板栏目不许悄悄变")
    void categoriesAndCountsArePinned() {
        HudSettingsModel.Category[] categories = HudSettingsModel.categories();
        List<String> titles = new ArrayList<String>();
        List<Integer> counts = new ArrayList<Integer>();
        for (HudSettingsModel.Category category : categories) {
            titles.add(category.title);
            counts.add(count(category));
        }
        assertEquals(expectedTitles(), titles, "分类名或顺序变了：改结构可以，但要连同本测试的预期一起改，"
                + "并确认没有条目在搬运中丢失");
        assertEquals(expectedCounts(), counts, "某一页的条目数变了");
        assertEquals(expectedTotal(), sum(counts), "条目总数不再是 " + expectedTotal() + " 条：重新分类不该增减条目");
    }

    @Test
    @DisplayName("分类总数封顶：分类条是横排，页太多会被窗口裁掉")
    void categoryCountStaysWithinSidebarBudget() {
        // 独立「模板预设」+1：保留各树原来的分类分流，不把 26.3 的常用页倒灌到老树。
        int cap = hasFavoritesPage() ? 10 : 9;
        assertTrue(HudSettingsModel.categories().length <= cap,
                "分类页超过 " + cap + " 个就又开始「侧栏一半是空页」了；再加页先确认各界面分类条放得下");
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
        // 2026-11-03 通透模式新增「压缩幅度 / 变宽幅度」两条滑条（值存共享层
        // TransparentMode 的配置文件，不经 HudConfig），54 → 56；
        // 2026-11-08 音乐胶囊迷你卡新增「迷你卡缩放」滑条（仅 26.3 的 HudSetting 收了
        // 这一条，其余六树等回打），56 → 57。
        // 同日「陀螺模式」新增「每拍转角」滑条（值存共享层 MusicSpin 的配置文件，不经
        // HudConfig），57 → 58（仅 26.3 口径；其余六树按 57 起算加一）。
        assertEquals(hasFavoritesPage() ? 58 : 57, settings.size(), "滑块条目数变了：生效设置应恰好出现一次；旧 GLASS_BLOOM / GLASS_FUSION 仅保留读档兼容；频谱自定义柱数、透明度与高度都是有效设置");
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
        // 3D 页的取色：文字色 + 发光色 + 「封面代表色」手动色板 4 色（封面渐变
        // 端点）—— 五树同构（发光颜色的 choice 条目不是 COLOR kind，不计入）。
        List<String> expected3D = Arrays.asList("3D 歌词", "3D 歌词",
                "3D 歌词", "3D 歌词", "3D 歌词", "3D 歌词");
        List<String> expectedColorPages = new ArrayList<String>(
                Arrays.asList("歌词样式", "歌词样式"));
        expectedColorPages.addAll(expected3D);
        assertEquals(expectedColorPages, colorPages,
                "桌面歌词的两个取色条目同页（一个地方配完），3D 歌词的文字色/发光色/手动色板取色在自己那一页");
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
