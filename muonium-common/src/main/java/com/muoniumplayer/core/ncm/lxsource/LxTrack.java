package com.muoniumplayer.core.ncm.lxsource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 交给脚本的 {@code info.musicInfo}。
 *
 * <p>字段名不是随便起的，是 lx-music-desktop 的
 * {@code src/common/utils/tools.ts#toOldMusicInfo} 的输出：那边每次调用自定义源之前都会把
 * 内部的新结构转成这套<b>扁平的旧结构</b>，所以脚本里读到的一直是 {@code musicInfo.songmid}、
 * {@code musicInfo.hash} 这些，而不是文档里那套 {@code musicInfo.meta.songId}。
 * 已用官方参考源（lx-music-source）实测过：它取的就是 {@code songmid}。</p>
 *
 * <p>额外多给一个 {@code meta} 子对象（内容与新结构一致）。上游没有它，但按文档写的脚本会读，
 * 多给不会让任何按旧结构写的脚本行为变样。</p>
 */
public final class LxTrack {

    /** LX 的源 key：kw / kg / tx / wy / mg / local。 */
    private String source = "wy";
    private String songmid = "";
    private String name = "";
    private String singer = "";
    private String albumName = "";
    private String albumId = "";
    private String img = "";
    /** 格式化后的时长，例：03:55。上游是这个格式，不是毫秒。 */
    private String interval = "";
    /** 酷狗：取流按文件 hash 定位。 */
    private String hash = "";
    /** QQ 音乐的三个附加主键。 */
    private String strMediaMid = "";
    private String albumMid = "";
    private String songId = "";
    /** 咪咕。 */
    private String copyrightId = "";
    private final List<String> qualitys = new ArrayList<>();

    public LxTrack source(String value) {
        this.source = nonNull(value);
        return this;
    }

    public LxTrack songmid(String value) {
        this.songmid = nonNull(value);
        return this;
    }

    public LxTrack name(String value) {
        this.name = nonNull(value);
        return this;
    }

    public LxTrack singer(String value) {
        this.singer = nonNull(value);
        return this;
    }

    public LxTrack albumName(String value) {
        this.albumName = nonNull(value);
        return this;
    }

    public LxTrack albumId(String value) {
        this.albumId = nonNull(value);
        return this;
    }

    public LxTrack img(String value) {
        this.img = nonNull(value);
        return this;
    }

    /** @param millis 歌曲时长毫秒；0 表示未知，会写成空串（上游对未知时长也是空/null） */
    public LxTrack durationMillis(long millis) {
        if (millis <= 0L) {
            this.interval = "";
            return this;
        }
        long totalSeconds = millis / 1000L;
        this.interval = String.format(Locale.ROOT, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L);
        return this;
    }

    public LxTrack hash(String value) {
        this.hash = nonNull(value);
        return this;
    }

    public LxTrack qq(String strMediaMid, String albumMid, String songId) {
        this.strMediaMid = nonNull(strMediaMid);
        this.albumMid = nonNull(albumMid);
        this.songId = nonNull(songId);
        return this;
    }

    public LxTrack copyrightId(String value) {
        this.copyrightId = nonNull(value);
        return this;
    }

    /** 曲目自身宣称可用的音质（LX 口径：128k/320k/flac/flac24bit），进 types/_types。 */
    public LxTrack qualitys(List<String> values) {
        this.qualitys.clear();
        if (values != null) this.qualitys.addAll(values);
        return this;
    }

    public String getSource() {
        return source;
    }

    public String getSongmid() {
        return songmid;
    }

    public String getName() {
        return name;
    }

    public String getSinger() {
        return singer;
    }

    /** 组装成 JSON 文本，由引导脚本 {@code JSON.parse} 还原成 JS 对象。 */
    public String toMusicInfoJson() {
        StringBuilder sb = new StringBuilder("{");
        field(sb, "name", name).append(',');
        field(sb, "singer", singer).append(',');
        field(sb, "source", source).append(',');
        field(sb, "songmid", songmid).append(',');
        field(sb, "interval", interval).append(',');
        field(sb, "albumName", albumName).append(',');
        field(sb, "albumId", albumId).append(',');
        field(sb, "img", img).append(',');
        // typeUrl 上游固定给一个空对象（它是应用侧缓存用的，脚本只会读不会写）。
        sb.append("\"typeUrl\":{},");
        sb.append("\"types\":[");
        for (int i = 0; i < qualitys.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"type\":");
            quote(sb, qualitys.get(i));
            sb.append(",\"size\":null}");
        }
        sb.append("],\"_types\":{");
        for (int i = 0; i < qualitys.size(); i++) {
            if (i > 0) sb.append(',');
            quote(sb, qualitys.get(i)).append(":{\"size\":null}");
        }
        sb.append('}');
        if (!hash.isEmpty()) {
            sb.append(',');
            field(sb, "hash", hash);
        }
        if (!strMediaMid.isEmpty() || !albumMid.isEmpty() || !songId.isEmpty()) {
            sb.append(',');
            field(sb, "strMediaMid", strMediaMid).append(',');
            field(sb, "albumMid", albumMid).append(',');
            field(sb, "songId", songId);
        }
        if (!copyrightId.isEmpty()) {
            sb.append(',');
            field(sb, "copyrightId", copyrightId);
        }
        // id 与 meta 是给「按文档写」的脚本准备的补充，上游的 toOldMusicInfo 里没有这两项。
        sb.append(',');
        field(sb, "id", source + '_' + songmid).append(",\"meta\":{");
        field(sb, "songId", songmid).append(',');
        field(sb, "albumName", albumName).append(',');
        field(sb, "picUrl", img);
        if (!hash.isEmpty()) {
            sb.append(',');
            field(sb, "hash", hash);
        }
        if (!copyrightId.isEmpty()) {
            sb.append(',');
            field(sb, "copyrightId", copyrightId);
        }
        if (!strMediaMid.isEmpty()) {
            sb.append(',');
            field(sb, "strMediaMid", strMediaMid);
        }
        sb.append('}');
        return sb.append('}').toString();
    }

    private static StringBuilder field(StringBuilder sb, String key, String value) {
        quote(sb, key).append(':');
        return quote(sb, value);
    }

    private static StringBuilder quote(StringBuilder sb, String text) {
        sb.append('"');
        String value = text == null ? "" : text;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"');
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}