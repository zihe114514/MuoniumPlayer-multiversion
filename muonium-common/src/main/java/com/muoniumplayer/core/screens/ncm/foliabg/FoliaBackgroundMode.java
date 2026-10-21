package com.muoniumplayer.core.screens.ncm.foliabg;

/**
 * 本项目 的全屏歌词背景样式清单。
 *
 * <p>顺序即面板里的轮换顺序。{@link #storedId()} 是写进
 * {@code folia_lyrics_background.json} 的稳定标识，<b>不再用数组下标</b> ——
 * 早先存的是下标，移除「空 / 莫奈」两档时老配置会整体错位一格，读回来就变成另一种背景。
 * 现在改成存字符串 id：删掉一种样式，别人的选择最多回落到默认，绝不会串到别的样式上。</p>
 *
 * <p>每一项对应 folia 的 {@code src/components/visualizer/backgrounds/<id>/entry.tsx}：</p>
 *
 * <ul>
 *   <li>{@link #COMMON} —— 通用：封面色流体 + 几何氛围层（含暗角）</li>
 *   <li>{@link #LATENT} —— 隐现：MeshGradient 流体 + 4x4 Bayer 抖动，封面色驱动</li>
 *   <li>{@link #NOMAND} —— 漫游：图像抖动 / 纹理玻璃 / 纸张 / 半调网点 / 镜头畸变</li>
 *   <li>{@link #EMBED} —— 嵌入：folia 原版是网页框，这里等价为网络图片源</li>
 * </ul>
 *
 * <p>「空（Sora）」与「莫奈（Monet）」已按用户要求移除：前者只有一个「完全空白」开关，
 * 后者是一张烘焙图 + 漂移，两者都与歌词主体的观感关系不大，却各自占了整块参数面板。</p>
 */
public enum FoliaBackgroundMode {

    COMMON("common", "通用"),
    LATENT("latent", "隐现"),
    NOMAND("nomand", "漫游"),
    EMBED("embed", "嵌入");

    private final String storedId;
    private final String displayName;

    FoliaBackgroundMode(String storedId, String displayName) {
        this.storedId = storedId;
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** 写进配置文件的稳定标识。改名字可以，改这个值等于把老配置作废。 */
    public String storedId() {
        return storedId;
    }

    /** 索引越界时退回「隐现」——它是 folia 的默认背景。 */
    public static FoliaBackgroundMode byIndex(int index) {
        FoliaBackgroundMode[] values = values();
        if (index < 0 || index >= values.length) return LATENT;
        return values[index];
    }

    /**
     * 老配置的迁移表：旧下标 → 现存样式。
     *
     * <p>旧顺序是 common / latent / sora / nomand / monet / embed，
     * 现在是 common / latent / nomand / embed。被删掉的两档回落到「隐现」。</p>
     */
    public static FoliaBackgroundMode fromLegacyIndex(int legacyIndex) {
        switch (legacyIndex) {
            case 0:
                return COMMON;
            case 3:
                return NOMAND;
            case 5:
                return EMBED;
            case 1:
            case 2:
            case 4:
            default:
                // latent 原样，sora(2) 与 monet(4) 已移除 → 回落默认
                return LATENT;
        }
    }

    /** 从稳定标识解析；未知（含已删除的 sora / monet）与空值都回落到 {@link #LATENT}。 */
    public static FoliaBackgroundMode byStoredId(String id) {
        if (id != null) {
            for (FoliaBackgroundMode mode : values()) {
                if (mode.storedId.equalsIgnoreCase(id)) return mode;
            }
        }
        return LATENT;
    }

    public static FoliaBackgroundMode current() {
        return byStringId(FoliaBackgroundConfig.mode);
    }

    /** 用户配置里的稳定 id → 样式；未知 id 回落到默认。 */
    public static FoliaBackgroundMode byStringId(String id) {
        return byStoredId(id);
    }
}

