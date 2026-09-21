package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonObject;

/**
 * 歌词搜索列表里的一条候选。
 *
 * <p>它同时服务三件事，所以字段是分两组的：</p>
 * <ul>
 *   <li><b>界面</b>：{@link #title} {@link #artists} {@link #album} {@link #coverUrl} {@link #score}
 *       —— 照 本项目 的候选卡片，一行封面 + 歌名 + 匹配分 + 歌手 + 专辑；</li>
 *   <li><b>取词</b>：{@link #source} {@link #trackId} {@link #platform} {@link #payload}
 *       —— 各家接口拿歌词要的坐标不一样，{@code payload} 就是上游返回的那一条候选原始 JSON，
 *       酷狗靠它拿 {@code id + accesskey}，AMLL 靠它拿 {@code musicNames}；</li>
 *   <li><b>钉住</b>：整条原样序列化进 {@link LyricManualMemory}，下次加载直接照它精确重取。</li>
 * </ul>
 *
 * <p>本类是不可变的数据壳，不做任何网络与解析。</p>
 */
public final class LyricSearchHit {

    public final LyricSearchSource source;
    /** 该源内定位这首歌的 id。可能是网易云/QQ 的数字 id，也可能是酷狗歌词条目 id。 */
    public final String trackId;
    /** 仅 {@link LyricSearchSource#AMLL} 有意义：这条词库条目挂在 ncm 还是 qq 的 id 下。其余为空串。 */
    public final String platform;
    public final String title;
    public final String artists;
    public final String album;
    /** 毫秒；{@code 0} 表示上游没给（AMLL 搜索结果就不带时长）。 */
    public final long durationMillis;
    /** 可直接加载的封面地址；没有就是空串，界面退回音符占位。 */
    public final String coverUrl;
    /** {@link LyricMatchScore} 的 0~100 分。列表按它降序排。 */
    public final int score;
    /** 时长是否落在「基本可以认定同一版本」的那一档（差 ≤3s），对应 folia 的 {@code durationMatched}。 */
    public final boolean durationMatched;
    /** 上游候选原始 JSON，可能为 {@code null}（能靠 id 直接取词的源不需要它）。 */
    public final JsonObject payload;

    public LyricSearchHit(LyricSearchSource source, String trackId, String platform, String title,
                          String artists, String album, long durationMillis, String coverUrl,
                          int score, boolean durationMatched, JsonObject payload) {
        this.source = source == null ? LyricSearchSource.NETEASE : source;
        this.trackId = nonNull(trackId);
        this.platform = nonNull(platform);
        this.title = nonNull(title);
        this.artists = nonNull(artists);
        this.album = nonNull(album);
        this.durationMillis = Math.max(0L, durationMillis);
        this.coverUrl = https(nonNull(coverUrl));
        this.score = Math.max(0, Math.min(100, score));
        this.durationMatched = durationMatched;
        this.payload = payload;
    }

    /** 钉给 {@link LyricManualMemory} 用；{@code payload} 为 null 时退化成空串。 */
    public LyricManualMemory.Pin toPin() {
        return new LyricManualMemory.Pin(source, trackId, platform, title, artists, album,
                durationMillis, payload == null ? "" : payload.toString());
    }

    /** 界面上一行显示多少秒；时长未知返回空串。 */
    public String durationText() {
        if (durationMillis <= 0L) return "";
        long totalSeconds = Math.round(durationMillis / 1000.0);
        return (totalSeconds / 60) + ":" + String.format(java.util.Locale.ROOT, "%02d", totalSeconds % 60);
    }

    private static String nonNull(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 封面地址统一升到 https。
     *
     * <p>Minecraft 客户端本身不校验混合内容，但网易云与酷狗的图床对 http 会 301 到 https，
     * 少一次跳转就少一次失败半径；folia 那边也是同一处理（{@code matchResult.ts} 里
     * {@code coverUrl.replace('http:', 'https:')}）。</p>
     */
    private static String https(String value) {
        return value.startsWith("http:") ? "https:" + value.substring(5) : value;
    }
}
