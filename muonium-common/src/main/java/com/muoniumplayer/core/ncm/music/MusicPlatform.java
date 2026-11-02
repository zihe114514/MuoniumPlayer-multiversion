package com.muoniumplayer.core.ncm.music;

/**
 * 当前播放器所使用的音乐平台。该枚举属于宿主 MOD，避免 UI 直接依赖内置音源层的实现类型。
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
     * <p>它不是一个可取流的平台 —— {@link #isMuSourceBacked()} 为假，播放列表与取流链路都不该
     * 认它。存在意义是「身份与来源标记」：稳定键前缀、徽标显示、以及设置页能说清这一首
     * 是从外面进来的。<b>追加在末尾</b>：稳定键里带着 {@code name()}，插在中间会让已经
     * 落盘的歌词与封面缓存整体错行。</p>
     */
    SMTC("系统播放器", 0x8E6BD6),
    /**
     * Spotify：<b>元数据源，不是播放源</b>。官方 Web API 没有任何音频出口（2024-11 起
     * {@code preview_url} 也从响应里下线了），它的行只提供搜索、本人歌单与账号资料；点播时
     * {@code Music#getPlayUrl()} 返回空，由 {@code CrossSourceSwitcher} 按同名同歌手在其余
     * 平台找替身。也正因如此它<b>绝不能</b>进换源候选表 {@code CANDIDATE_ORDER} ——
     * 它没有流可以给别人换。凭据是每个用户自建的 Client ID + OAuth PKCE（见
     * {@code core.ncm.spotify}）。
     *
     * <p>同样<b>追加在末尾</b>，理由同 {@link #SMTC}：稳定键按 {@code name()} 落盘。</p>
     */
    SPOTIFY("Spotify", 0x1DB954),
    /**
     * Apple Music：<b>元数据源 + 本机引擎完整播放</b>。官方 AMP API 的目录能力（搜索、
     * 歌单、榜单、歌词）是公开的；但完整音轨是 FairPlay DRM 保护的，官方接口只有 30 秒
     * preview。点播顺序：本机引擎（{@code AppleNativeEngine}，用户自备订阅 cookies）
     * 拉完整版 → 拿不到就走 {@code CrossSourceSwitcher} 换源替身 →
     * 还不行才落 30 秒 preview 兜底（试听角标，配置可关）。与 Spotify 同理，它<b>绝不能</b>
     * 进换源候选表 {@code CANDIDATE_ORDER} —— 它没有完整流可以给别人换。
     *
     * <p><b>追加在末尾</b>，理由同 {@link #SMTC}：稳定键按 {@code name()} 落盘。</p>
     */
    APPLE("Apple Music", 0xFA243C),
    /**
     * 本地文件：用户磁盘上的音频文件（mp3 / flac / wav / m4a / aac），经「导入本地文件夹」
     * 灌进本地歌单。取流是<b>零网络</b>的 —— {@code Music#getPlayUrl()} 给一条
     * {@code muonium-local://} 伪 URL，由 {@code CloudMusic.cachedAudioFileLocked} 的分派点
     * 兑换成磁盘原文件（不下载、不占音频缓存容量）；元数据（标题/歌手/专辑/封面/嵌入歌词）
     * 由 {@code LocalAudioMetadata} 从文件标签里读。
     *
     * <p>与 Spotify 同理它<b>绝不能</b>进换源候选表 {@code CANDIDATE_ORDER}——本地文件没有
     * 「流」可以被别的平台换走；反过来 LOCAL 行自己取流永远成功（文件在就在，不在报错），
     * 也不需要别人替它换。歌词与封面按 stableKey（路径哈希）落盘，与在线平台同一套缓存键位。</p>
     *
     * <p><b>追加在末尾</b>，理由同 {@link #SMTC}：稳定键按 {@code name()} 落盘，
     * 插进中间会让已落盘的歌词与封面缓存整体错行。</p>
     */
    LOCAL("本地音乐", 0x6C7A92);

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
     * 该平台是否由内置音源层（吸收自原 music-api jar）承载。
     *
     * <p>酷狗、YouTube、哔哩哔哩与 GD 都是宿主自己实现的，{@link #toMuSource()} 对它们只能给出一个不正确的兜底值；
     * 任何要走内置音源层的调用点都必须先问这个方法，而不是直接转换。</p>
     */
    public boolean isMuSourceBacked() {
        return this == NETEASE || this == QQ;
    }

    /**
     * @deprecated 只对 {@link #isMuSourceBacked()} 为真的平台有意义；其余平台会落到 NETEASE 兜底。
     */
    public com.muoniumplayer.core.music.source.MusicSource toMuSource() {
        return this == QQ ? com.muoniumplayer.core.music.source.MusicSource.QQ : com.muoniumplayer.core.music.source.MusicSource.NETEASE;
    }

    public static MusicPlatform fromMuSource(com.muoniumplayer.core.music.source.MusicSource source) {
        return source == com.muoniumplayer.core.music.source.MusicSource.QQ ? QQ : NETEASE;
    }
}
