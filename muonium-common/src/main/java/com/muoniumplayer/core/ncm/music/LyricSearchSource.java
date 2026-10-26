package com.muoniumplayer.core.ncm.music;

/**
 * 歌词检索的四个来源，顺序与标签文案都按
 * {@code LYRIC_MATCH_SOURCES = ['netease', 'amll', 'qq', 'kugou']}。
 *
 * <p>为什么用枚举而不是复用 {@link LyricSourceMemory} 的那套 int：两者钉的东西不同。
 * {@code LyricSourceMemory} 钉的是「去哪个<b>库</b>拿」，这里钉的是「拿这一<b>首</b>」，
 * 而且这里的 netease 档是行级官方歌词，和 {@code LyricSourceMemory.PLATFORM} 那一档
 * 语义也不完全重合。混用一套编号会让两边的配置文件互相读不懂。</p>
 *
 * <p>{@link #AMLL} 的 {@link #supportsManualSearch()} 是 {@code false}，这不是漏实现：
 * AMLL 词库是人工校对的 TTML 集合，它<b>没有</b>「按歌名模糊搜出一批歌」这种玩法 ——
 * folia 那一档做的是「拿用户输入去网易云 + QQ 搜候选，再逐个回词库里探有没有 TTML」，
 * 词库自己不接受自由检索词，所以界面上这一档不给搜索框。</p>
 */
public enum LyricSearchSource {

    NETEASE("netease", "网易云音乐", true),
    AMLL("amll", "AMLLDB", false),
    QQ("qq", "QQ 音乐", true),
    KUGOU("kugou", "酷狗音乐", true);

    /** 配置文件里用的稳定 id。改名会让已有的手动钉词全部失效，所以只能是常量。 */
    public final String id;
    /** 界面上标签页显示的名字。 */
    public final String label;
    /** 是否允许用户改写检索词（见类注释里 AMLL 的例外）。 */
    private final boolean manualSearch;

    LyricSearchSource(String id, String label, boolean manualSearch) {
        this.id = id;
        this.label = label;
        this.manualSearch = manualSearch;
    }

    public boolean supportsManualSearch() {
        return manualSearch;
    }

    /** 未知 id 折回 {@link #NETEASE}，与 {@code LyricSourceMemory.clamp} 同样的「宁可回到默认」立场。 */
    public static LyricSearchSource fromId(String id) {
        if (id != null) {
            for (LyricSearchSource source : values()) {
                if (source.id.equals(id.trim())) return source;
            }
        }
        return NETEASE;
    }
}
