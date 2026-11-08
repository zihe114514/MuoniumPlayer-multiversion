package com.muoniumplayer.core.preset.model;

import java.util.EnumSet;
import java.util.Set;

/** Stable facet ids and MPP2 codes. Published codes must never be reused. */
public enum Facet {
    VISIBILITY(1, "visibility", "显示开关", Group.APPEARANCE, false, "桌面歌词、信息卡、灵动岛、3D 歌词与 HUD 频谱是否显示"),
    LYRICS(2, "lyrics", "歌词样式", Group.APPEARANCE, false, "桌面歌词与全屏歌词的字号、颜色、发光、逐字效果与各样式参数"),
    BACKGROUND(3, "background", "全屏背景", Group.APPEARANCE, false, "全屏歌词页的流体背景与 Folia 背景"),
    WORLD(4, "world", "3D 歌词", Group.APPEARANCE, false, "挂在游戏世界里的 3D 歌词外观"),
    ISLAND(5, "island", "灵动岛", Group.APPEARANCE, false, "灵动岛的样式、常驻内容与动画速度"),
    PANEL(6, "panel", "卡片与玻璃", Group.APPEARANCE, false, "Blossom 卡片、真玻璃材质与信息卡底板"),
    THEME(7, "theme", "主题配色", Group.APPEARANCE, false, "播放器主题、自定义配色与配色来源"),
    SPECTRUM(8, "spectrum", "频谱", Group.APPEARANCE, false, "音乐频谱的样式、柱数与配色"),
    FONT(9, "font", "界面字体", Group.APPEARANCE, false, "界面字体（本机没有的字体会跳过；冷切换策略保持不变）"),
    MOTION(10, "motion", "动效", Group.APPEARANCE, false, "界面淡入与切换速度、通透模式、陀螺模式"),
    CAMERA(11, "camera", "卡点运镜", Group.APPEARANCE, false, "闲置时随节拍自动运镜的节奏与镜头参数"),
    LAYOUT(12, "layout", "布局位置", Group.APPEARANCE, true, "HUD 元素的位置与尺寸——和屏幕有关，默认不套用"),
    PERFORMANCE(13, "performance", "画质性能", Group.APPEARANCE, true, "与机器性能有关的画质项，默认不套用"),
    VIPER(14, "viper", "蝰蛇音效", Group.AUDIO, false, "蝰蛇音效开关与当前预设（含 JamesDSP 混响）"),
    ACOUSTICS(15, "acoustics", "场景声学", Group.AUDIO, false, "场景声学开关、分项与强度");

    public enum Group {
        APPEARANCE("外观"), AUDIO("音效");
        private final String displayName;
        Group(String displayName) { this.displayName = displayName; }
        public String displayName() { return displayName; }
    }
    private final int code;
    private final String id, displayName, description;
    private final Group group;
    private final boolean optIn;
    Facet(int code, String id, String displayName, Group group, boolean optIn, String description) {
        this.code = code;
        this.id = id;
        this.displayName = displayName;
        this.group = group;
        this.optIn = optIn;
        this.description = description;
    }
    public String id() { return id; }
    public int code() { return code; }
    public String displayName() { return displayName; }
    public Group group() { return group; }
    public boolean optIn() { return optIn; }
    public String description() { return description; }
    public static Facet byCode(int code) {
        for (Facet facet : values()) if (facet.code == code) return facet;
        return null;
    }
    public static Facet byId(String id) {
        for (Facet facet : values()) if (facet.id.equals(id)) return facet;
        return null;
    }
    public static Set<Facet> ofGroup(Group group) {
        EnumSet<Facet> result = EnumSet.noneOf(Facet.class);
        for (Facet facet : values()) if (facet.group == group) result.add(facet);
        return result;
    }
    public static Set<Facet> withoutOptIn(Set<Facet> facets) {
        EnumSet<Facet> result = EnumSet.noneOf(Facet.class);
        if (facets != null) for (Facet facet : facets) if (!facet.optIn) result.add(facet);
        return result;
    }
}
