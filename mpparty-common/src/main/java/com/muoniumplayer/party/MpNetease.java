package com.muoniumplayer.party;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.RequestUtil;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.Quality;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.utils.json.JsonUtils;

import java.util.Collections;
import java.util.Locale;

/**
 * 一起听需要的两个网易云查询，全部走播放器自己那份已登录的会话。
 *
 * <p>服务端从不碰网易云：链接由点歌的客户端解析，服务端只负责把
 * 「歌曲元数据 + 取流链接」转发给全房。因此这里解析出来的链接就是整房共用的那一条 ——
 * 会员账号解析出的完整音频，非会员成员也能听（{@link #resolve} 会把试听片段的
 * 时长一并报上去，由服务端拒绝）。
 */
public final class MpNetease {

    private MpNetease() {
    }

    /** 一条解析结果：链接 + 容器类型 + 链接实际提供的音频长度。 */
    public static final class Resolved {
        public final String url;
        public final String type;
        public final long servedMs;

        Resolved(String url, String type, long servedMs) {
            this.url = url;
            this.type = type;
            this.servedMs = servedMs;
        }
    }

    /** 按 id 拉一首歌的完整元数据（含封面、时长、会员标记）。 */
    public static Music songById(long id) {
        if (id <= 0L) return null;
        try {
            RequestUtil.RequestAnswer answer = CloudMusicApi.songDetail(Collections.singletonList(id));
            JsonObject root = answer == null ? null : answer.toJsonObject();
            JsonArray songs = root != null && root.has("songs") && root.get("songs").isJsonArray()
                    ? root.getAsJsonArray("songs") : null;
            if (songs == null || songs.size() == 0 || !songs.get(0).isJsonObject()) return null;

            JsonObject songObject = songs.get(0).getAsJsonObject();
            Music music = JsonUtils.parse(songObject, Music.class);
            if (music == null || music.getId() <= 0L) return null;

            JsonArray privileges = root.has("privileges") && root.get("privileges").isJsonArray()
                    ? root.getAsJsonArray("privileges") : null;
            JsonObject privilege = privileges != null && privileges.size() > 0
                    && privileges.get(0).isJsonObject() ? privileges.get(0).getAsJsonObject() : null;
            music.applyNeteaseMetadata(songObject, privilege);
            return music;
        } catch (Throwable throwable) {
            System.err.println("[MpParty] 获取歌曲信息失败 " + id + "：" + throwable);
            return null;
        }
    }

    /**
     * 解一条取流链接：先按用户选的音质，失败再退标准 MP3。
     *
     * <p>刻意不走 {@code Music#getPlayUrl()}：那条链路会在官方源失败后接 Cadence / 解灰等
     * 第三方源，那些链接对别人的机器不一定可用，而房间里所有人共用同一条链接。
     */
    public static Resolved resolve(long id) {
        if (id <= 0L) return null;
        Quality quality = CloudMusic.quality == null ? Quality.LOSSLESS : CloudMusic.quality;
        Resolved preferred = read(safeSongUrl(id, quality.getQuality().toLowerCase(Locale.ROOT)));
        if (preferred != null) return preferred;
        return read(safeStandardMp3(id));
    }

    private static RequestUtil.RequestAnswer safeSongUrl(long id, String level) {
        try {
            return CloudMusicApi.songUrlV1(id, level);
        } catch (Throwable throwable) {
            System.err.println("[MpParty] songUrlV1 失败 " + id + "：" + throwable);
            return null;
        }
    }

    private static RequestUtil.RequestAnswer safeStandardMp3(long id) {
        try {
            return CloudMusicApi.songUrlStandardMp3(id);
        } catch (Throwable throwable) {
            System.err.println("[MpParty] songUrlStandardMp3 失败 " + id + "：" + throwable);
            return null;
        }
    }

    /** 解析 {@code data[0]}：{@code url} / {@code type} / {@code time}（链接实际时长）。 */
    private static Resolved read(RequestUtil.RequestAnswer answer) {
        try {
            if (answer == null || answer.getStatus() != 200) return null;
            JsonObject root = answer.toJsonObject();
            if (root == null || !root.has("data") || !root.get("data").isJsonArray()
                    || root.getAsJsonArray("data").size() == 0) {
                return null;
            }
            JsonObject data = root.getAsJsonArray("data").get(0).getAsJsonObject();
            if (data.has("code") && !data.get("code").isJsonNull() && data.get("code").getAsInt() != 200) {
                return null;
            }
            String url = MpProto.str(data, "url", "");
            if (url.trim().isEmpty()) return null;
            String type = MpProto.str(data, "type", "mp3");
            if (type.trim().isEmpty()) type = "mp3";
            return new Resolved(url, type.toLowerCase(Locale.ROOT), MpProto.num(data, "time", 0L));
        } catch (Throwable throwable) {
            System.err.println("[MpParty] 解析取流响应失败：" + throwable);
            return null;
        }
    }
}
