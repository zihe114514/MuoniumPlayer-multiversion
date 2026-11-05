package com.muoniumplayer.core.preset.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * 模板预设的「分面」：玩家在套用 / 保存 / 分享时勾选的粒度。
 *
 * <p>分面可以跨配置域：「主题配色」既有 theme.json 里的主题与调色，也有 hud.json 里那几项颜色来源；
 * 一个落盘键只属于一个分面（归属写在各配置域的 {@code KeyPolicy} 里，守卫测试钉着）。</p>
 *
 * <p><b>{@link #id} 写进预设文件，永不改名</b>。要拆分 / 合并分面，就新增一个 id 并在读取时做迁移，
 * 而不是改旧 id —— 否则已经分享出去的预设会整段读不出来。</p>
 */
public enum Facet {

    // ---- 外观 ----
    VISIBILITY("visibility", "显示开关", Group.APPEARANCE, false,
            "桌面歌词、信息卡、灵动岛、3D 歌词与 HUD 频谱是否显示"),
    LYRICS("lyrics", "歌词样式", Group.APPEARANCE, false,
            "桌面歌词与全屏歌词的字号、颜色、发光、逐字效果与各样式参数"),
    BACKGROUND("background", "全屏背景", Group.APPEARANCE, false,
            "全屏歌词页的流体背景与 Folia 背景"),
    WORLD("world", "3D 歌词", Group.APPEARANCE, false,
            "挂在游戏世界里的 3D 歌词外观"),
    ISLAND("island", "灵动岛", Group.APPEARANCE, false,
            "灵动岛的样式、常驻内容与动画速度"),
    PANEL("panel", "卡片与玻璃", Group.APPEARANCE, false,
            "Blossom 卡片、真玻璃材质与信息卡底板"),
    THEME("theme", "主题配色", Group.APPEARANCE, false,
            "播放器主题、自定义配色与配色来源"),
    SPECTRUM("spectrum", "频谱", Group.APPEARANCE, false,
            "音乐频谱的样式、柱数与配色"),
    FONT("font", "界面字体", Group.APPEARANCE, false,
            "播放器界面字体（本机没有的字体会跳过）"),
    MOTION("motion", "动效", Group.APPEARANCE, false,
            "界面淡入与切换速度、通透模式"),
    CAMERA("camera", "卡点运镜", Group.APPEARANCE, false,
            "闲置时随节拍自动运镜的节奏与镜头参数"),
    LAYOUT("layout", "布局位置", Group.APPEARANCE, true,
            "HUD 元素的位置与尺寸——和屏幕有关，默认不套用"),
    PERFORMANCE("performance", "画质性能", Group.APPEARANCE, true,
            "玻璃采样、3D 歌词画质档等与机器性能有关的项，默认不套用"),

    // ---- 音效 ----
    VIPER("viper", "蝰蛇音效", Group.AUDIO, false,
            "蝰蛇音效开关与当前预设（含 JamesDSP 混响），预设内容随分享一起带走"),
    ACOUSTICS("acoustics", "场景声学", Group.AUDIO, false,
            "场景声学开关、分项与强度");

    /** 分组：「只分享外观 / 只分享音效」的快捷勾选按它走。 */
    public enum Group {
        APPEARANCE("外观"),
        AUDIO("音效");

        private final String displayName;

        Group(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    private final String id;
    private final String displayName;
    private final Group group;
    private final boolean optIn;
    private final String description;

    Facet(String id, String displayName, Group group, boolean optIn, String description) {
        this.id = id;
        this.displayName = displayName;
        this.group = group;
        this.optIn = optIn;
        this.description = description;
    }

    /** 写进预设文件的稳定 id。 */
    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public Group group() {
        return group;
    }

    /**
     * 需要玩家主动勾选才套用 / 才导出的分面（布局、画质）：它们取决于对方的屏幕与机器，
     * 照搬别人的值多半是帮倒忙。
     */
    public boolean optIn() {
        return optIn;
    }

    public String description() {
        return description;
    }

    /** 按 id 查分面；认不出返回 {@code null}（新版本加的分面在旧版本里就是认不出）。 */
    public static Facet byId(String id) {
        if (id == null) {
            return null;
        }
        for (Facet facet : values()) {
            if (facet.id.equals(id)) {
                return facet;
            }
        }
        return null;
    }

    /** 某一组里的全部分面。 */
    public static Set<Facet> ofGroup(Group group) {
        EnumSet<Facet> result = EnumSet.noneOf(Facet.class);
        for (Facet facet : values()) {
            if (facet.group == group) {
                result.add(facet);
            }
        }
        return result;
    }

    /** 默认勾选：给定集合里去掉需要主动勾选的那几项。 */
    public static Set<Facet> withoutOptIn(Set<Facet> facets) {
        EnumSet<Facet> result = EnumSet.noneOf(Facet.class);
        for (Facet facet : facets) {
            if (!facet.optIn) {
                result.add(facet);
            }
        }
        return result;
    }
}
