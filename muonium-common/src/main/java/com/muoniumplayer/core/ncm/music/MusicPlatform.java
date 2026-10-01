package com.muoniumplayer.core.ncm.music;

/**
 * 当前播放器所使用的音乐平台。该枚举属于宿主 MOD，避免 UI 直接依赖 Cadence 的实现类型。
 */
public enum MusicPlatform {
    NETEASE("网易云", 0xE94747),
    QQ("QQ音乐", 0x31C27C),
    KUGOU("酷狗音乐", 0x1F9CF0),
    KUWO("酷我音乐", 0xFF7A1A),
    MIGU("咪咕音乐", 0x00B8A9),
    YOUTUBE("YouTube Music", 0xFF0033),
    BILIBILI("哔哩哔哩", 0xFB7299),
    QISHUI("汽水音乐", 0x1ED6C8),
    GD("GD音乐台", 0x3D5AFE),
    /**
     * 系统媒体会话（Windows SMTC）：外部播放器正在播的那首。
     *
     * <p>它不是一个可取流的平台 —— {@link #isCadenceBacked()} 为假，播放列表与取流链路都不该
     * 认它。存在意义是「身份与来源标记」：稳定键前缀、徽标显示、以及设置页能说清这一首
     * 是从外面进来的。<b>追加在末尾</b>：稳定键里带着 {@code name()}，插在中间会让已经
     * 落盘的歌词与封面缓存整体错行。</p>
     */
    SMTC("系统播放器", 0x8E6BD6);

    private final String displayName;
    private final int brandColor;

    MusicPlatform(String displayName, int brandColor) {
        this.displayName = displayName;
        this.brandColor = brandColor;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getBrandColor() {
        return brandColor;
    }

    /**
     * 该平台是否由 Cadence（{@code music-api} 库）承载。
     *
     * <p>酷狗、YouTube、哔哩哔哩与 GD 都是宿主自己实现的，{@link #toCadenceSource()} 对它们只能给出一个不正确的兜底值；
     * 任何要走 Cadence 的调用点都必须先问这个方法，而不是直接转换。</p>
     */
    public boolean isCadenceBacked() {
        return this == NETEASE || this == QQ;
    }

    /**
     * @deprecated 只对 {@link #isCadenceBacked()} 为真的平台有意义；其余平台会落到 NETEASE 兜底。
     */
    public top.fpsmaster.music.MusicSource toCadenceSource() {
        return this == QQ ? top.fpsmaster.music.MusicSource.QQ : top.fpsmaster.music.MusicSource.NETEASE;
    }

    public static MusicPlatform fromCadence(top.fpsmaster.music.MusicSource source) {
        return source == top.fpsmaster.music.MusicSource.QQ ? QQ : NETEASE;
    }
}
