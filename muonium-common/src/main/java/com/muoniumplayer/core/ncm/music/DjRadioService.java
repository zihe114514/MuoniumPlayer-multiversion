package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.dto.Album;
import com.muoniumplayer.core.ncm.music.dto.Artist;
import com.muoniumplayer.core.ncm.music.dto.DjRadio;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.utils.json.JsonUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 电台 / 播客（djradio）的解析层。网易云把播客并进电台体系：播客就是 djradio，
 * 播客里的声音就是节目（program），而节目都带 {@code mainSong} —— 一个真实的网易云歌曲
 * 对象。因此节目一律转成 {@link Music}（真 songId、NETEASE 源），之后取流、歌词、红心、
 * 队列全部走既有管线，本类只负责"把上游 JSON 变成模型"这一件事。
 *
 * <p>上游响应形状在端点之间不一致（{@code djradios / djRadios / list / channels}，
 * 包一层或两层 {@code data / result}），这里统一走防御性嵌套查找：找不到就返回空列表，
 * 让上层展示空态，绝不抛异常打断 UI。</p>
 */
public final class DjRadioService {

    private DjRadioService() {
    }

    // ── 电台 / 播客条目 ──────────────────────────────────────────────────────

    /**
     * 从任意电台列表响应里解析电台卡片。覆盖热门/榜单/分类推荐/订阅列表/最近收听等
     * 所有列表端点；最近收听那种「{djRadio:{...}, playTime}」包装条目也在这一层剥开。
     */
    public static List<DjRadio> parseRadios(JsonObject root) {
        JsonArray radios = nestedArray(root, "djradios", "djRadios", "toplist", "list", "radios");
        if (radios == null) {
            // 个性推荐（/api/djradio/personalize/rcmd）的 data 本身就是电台数组，
            // 没有任何命名键包着 —— 直接把 data 数组当条目列表用。
            radios = array(root, "data");
        }
        if (radios == null) return Collections.emptyList();
        List<DjRadio> result = new ArrayList<>();
        for (JsonElement element : radios) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            // 最近收听条目把电台包在 djRadio/radio 里；普通列表端点没有这层。
            JsonObject radio = object(item, "djRadio");
            if (radio == null) radio = object(item, "radio");
            if (radio == null) radio = item;
            DjRadio parsed = parseRadio(radio);
            if (parsed != null) {
                if (parsed.getLastUpdateTime() <= 0L) parsed.setLastUpdateTime(number(item, "playTime"));
                result.add(parsed);
            }
        }
        return result;
    }

    /** 单个电台对象 → 卡片模型；缺 id 或名字的条目丢弃。 */
    public static DjRadio parseRadio(JsonObject radio) {
        if (radio == null) return null;
        long id = number(radio, "id");
        if (id <= 0L) return null;
        DjRadio card = new DjRadio();
        card.setId(id);
        card.setName(text(radio, "name"));
        card.setPicUrl(text(radio, "picUrl", "coverUrl", "coverImgUrl"));
        card.setDjName(text(object(radio, "dj"), "nickname", "name", "userName"));
        if (card.getDjName().isEmpty()) {
            JsonObject creator = object(radio, "creator");
            card.setDjName(text(creator, "nickname", "userName", "name"));
        }
        card.setCategoryName(text(radio, "category", "name"));
        // category 在部分端点是对象（{id, name}），部分端点是字符串；name 字段兜底读到了
        // 电台自己的名字时不覆盖成它，退回次级字段。
        if (card.getCategoryName().equals(card.getName())) {
            card.setCategoryName(text(object(radio, "category"), "name"));
        }
        card.setSubCount(number(radio, "subCount"));
        card.setProgramCount(number(radio, "programCount", "programCounts", "count"));
        card.setPlayCount(number(radio, "playCount"));
        card.setDesc(text(radio, "desc", "description", "rcmdtext"));
        card.setSubscribed(bool(radio, "subscribed"));
        card.setLastUpdateTime(number(radio, "lastProgramCreateTime", "createTime", "updateTime"));
        return card;
    }

    // ── 节目 → Music ─────────────────────────────────────────────────────────

    /**
     * 电台节目列表（{@code /api/dj/program/byradio}）→ 可播放的 {@link Music} 列表。
     * 没带 mainSong 的节目跳过 —— 没有 songId 就没有播放与歌词，留着只会点到报错。
     */
    public static List<Music> parsePrograms(JsonObject root) {
        JsonArray programs = nestedArray(root, "programs", "djPrograms");
        if (programs == null) return Collections.emptyList();
        List<Music> result = new ArrayList<>();
        for (JsonElement element : programs) {
            if (element == null || !element.isJsonObject()) continue;
            Music music = programToMusic(element.getAsJsonObject());
            if (music != null) result.add(music);
        }
        return result;
    }

    /**
     * 单个节目对象 → {@link Music}。取节目里的 {@code mainSong}（网易云标准歌曲 JSON，
     * 新旧字段名都可能），归一化后走与搜索结果相同的解析路径；封面用节目自己的 coverUrl
     * 兜底。没有可播放对象时返回 {@code null}。
     */
    public static Music programToMusic(JsonObject program) {
        if (program == null) return null;
        JsonObject song = object(program, "mainSong");
        if (song == null) song = object(program, "mainMusic");
        if (song != null) {
            song = normalizeSongPayload(song);
            try {
                Music music = JsonUtils.parse(song, Music.class);
                if (music != null && music.getId() > 0L) {
                    music.applyNeteaseMetadata(song, null);
                    // 电台节目禁止任何形式的换源匹配（节目名匹配不到"同一首歌"）。
                    music.setDjProgram(true);
                    // 节目封面通常就是 mainSong 的专辑封面；两者都没有时才补节目 coverUrl。
                    if ((music.getAlbum() == null || music.getAlbum().getPicUrl() == null
                            || music.getAlbum().getPicUrl().isEmpty())
                            && !text(program, "coverUrl").isEmpty()) {
                        music.setExternalCoverUrl(text(program, "coverUrl"));
                    }
                    return music;
                }
            } catch (Throwable ignored) {
            }
        }
        // mainSong 缺形但显式给了 mainSongId/mainMusicId 时兜底成最小 Music；连这个都没有
        // 就放弃这一条 —— 拿节目自身 id 充当 songId 只会得到一条必然取流失败的假歌。
        long songId = number(program, "mainSongId", "mainMusicId");
        if (songId <= 0L) return null;
        String djName = text(object(program, "dj"), "nickname", "name");
        if (djName.isEmpty()) djName = text(object(program, "radio"), "name");
        return minimalMusic(songId, text(program, "name"), djName, text(program, "coverUrl"),
                number(program, "duration"));
    }

    /** 按节目 id 取详情并转成 {@link Music}；失败返回 {@code null}（调用方给降级提示）。 */
    public static Music fetchProgramMusic(long programId) {
        try {
            JsonObject root = CloudMusicApi.djProgramDetail(programId).toJsonObject();
            JsonObject program = object(root, "program");
            if (program == null) program = object(object(root, "data"), "program");
            if (program == null) program = root;
            return programToMusic(program);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 电台详情（{@code /api/djradio/v2/get}）。响应是 {@code data} 包着的<b>单对象</b>，
     * 与列表端点不同形；这里剥开包装后走统一的单卡解析。
     */
    public static DjRadio fetchRadioDetail(long rid) {
        try {
            JsonObject root = CloudMusicApi.djDetail(rid).toJsonObject();
            JsonObject data = object(root, "data");
            return parseRadio(data != null ? data : root);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ── 搜索 ─────────────────────────────────────────────────────────────────

    /**
     * 电台分类目录（{@code /api/djradio/category/get}）。返回 {@code [id, name]} 二元组数组，
     * 顺序即上游顺序；「推荐」这类无 id 的虚拟项由 UI 自己加。
     *
     * <p>广播电台的分类/地区端点（{@code /api/voice/broadcast/category/region/get}）形状是
     * {@code data:{categoryList:[…], regionList:[…]}}，kind 传 BROADCAST 时读 categoryList
     * 或 regionList（看哪边有数据），与 djradio 分类共用 [id, name] 的返回形状。</p>
     */
    public static List<String[]> parseCategories(JsonObject root) {
        JsonArray categories = nestedArray(root, "categories", "list");
        if (categories == null) {
            JsonObject data = object(root, "data");
            categories = array(data, "categoryList");
        }
        if (categories == null) return Collections.emptyList();
        List<String[]> result = new ArrayList<>();
        for (JsonElement element : categories) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String id = text(item, "id");
            String name = text(item, "name");
            if (id.isEmpty() || name.isEmpty()) continue;
            result.add(new String[]{id, name});
        }
        return result;
    }

    /**
     * 广播电台的分类/地区目录。返回 {@code [id, name]} 对；想要地区列表时传 {@code true}。
     * 两个列表都空（理论上不会）时返回空列表，UI 落到「全部电台」。
     */
    public static List<String[]> parseBroadcastCategoryOrRegion(JsonObject root, boolean region) {
        JsonObject data = object(root, "data");
        JsonArray list = array(data, region ? "regionList" : "categoryList");
        if (list == null) return Collections.emptyList();
        List<String[]> result = new ArrayList<>();
        for (JsonElement element : list) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String id = text(item, "id");
            String name = text(item, "name");
            if (id.isEmpty() || name.isEmpty()) continue;
            result.add(new String[]{id, name});
        }
        return result;
    }

    /** 搜电台/播客（cloudsearch type=1009，取 {@code result.djRadios}）。 */
    public static List<DjRadio> searchRadios(String keyword) {
        try {
            JsonObject root = CloudMusicApi.cloudSearch(keyword, CloudMusicApi.SearchType.Radio).toJsonObject();
            return parseRadios(object(root, "result"));
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    /** 搜声音（cloudsearch type=2000）。声音条目与节目同构，都从 mainSong 取可播放对象。 */
    public static List<Music> searchVoices(String keyword) {
        try {
            JsonObject root = CloudMusicApi.cloudSearch(keyword, CloudMusicApi.SearchType.Sound).toJsonObject();
            JsonArray voices = nestedArray(object(root, "result"), "voices", "djRadios", "programs");
            if (voices == null) return Collections.emptyList();
            List<Music> result = new ArrayList<>();
            for (JsonElement element : voices) {
                if (element == null || !element.isJsonObject()) continue;
                Music music = programToMusic(element.getAsJsonObject());
                if (music != null) result.add(music);
            }
            return result;
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    // ── DIFM 频道 ────────────────────────────────────────────────────────────

    /**
     * DIFM 分类频道 / 我的收藏频道 → 卡片列表。
     *
     * <p>分类端点的真实形状是 {@code data:[{source, styles:[{channels:[…]}]}]} 两层嵌套
     * （styles 按曲风分组，channels 才是频道）；收藏端点是扁平的 {@code data:[…channel]}。
     * 两种形状都在这里处理。</p>
     */
    public static List<DjRadio> parseDifmChannels(JsonObject root) {
        JsonArray data = array(root, "data");
        List<DjRadio> result = new ArrayList<>();
        if (data != null && data.size() > 0 && data.get(0).isJsonObject()
                && data.get(0).getAsJsonObject().has("styles")) {
            // 分组形状：展开每一组的 styles[].channels[]。
            for (JsonElement group : data) {
                if (group == null || !group.isJsonObject()) continue;
                JsonArray styles = array(group.getAsJsonObject(), "styles");
                if (styles == null) continue;
                for (JsonElement style : styles) {
                    if (style == null || !style.isJsonObject()) continue;
                    JsonObject styleObj = style.getAsJsonObject();
                    appendDifmChannels(array(styleObj, "channels"), result);
                }
            }
            return result;
        }
        appendDifmChannels(data, result);
        if (result.isEmpty()) {
            appendDifmChannels(nestedArray(root, "channelList", "channels", "list"), result);
        }
        return result;
    }

    private static void appendDifmChannels(JsonArray channels, List<DjRadio> result) {
        if (channels == null) return;
        for (JsonElement element : channels) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            long id = number(item, "id", "channelId");
            String name = text(item, "name", "channelName", "title");
            // DIFM 频道名常是英文（Deep Progressive House），中文名在 chineseName：
            // 中文名存在时优先用它，括注原文方便对回 App。
            String chinese = text(item, "chineseName");
            if (id <= 0L || name.isEmpty()) continue;
            DjRadio card = new DjRadio();
            card.setId(id);
            card.setName(chinese.isEmpty() ? name : chinese + " · " + name);
            card.setPicUrl(text(item, "cover", "coverUrl", "picUrl"));
            card.setKind(DjRadio.Kind.DIFM_CHANNEL);
            card.setDesc(text(item, "description", "desc"));
            result.add(card);
        }
    }

    /**
     * DIFM 频道正在播的曲目列表 → {@link Music} 列表。DIFM 曲目是电台策展的普通歌曲
     * （真歌名、真歌手），保留正常换源资格，不打节目标记。
     */
    public static List<Music> parseDifmTracks(JsonObject root) {
        return parseSongArray(nestedArray(root, "tracks", "songs", "list"));
    }

    /** 收藏/取消收藏 DIFM 频道。 */
    public static boolean setDifmChannelSubscribed(long channelId, boolean subscribe) {
        try {
            return subscribe
                    ? CloudMusicApi.djDifmSubscribeChannel(channelId).toJsonObject() != null
                    : CloudMusicApi.djDifmUnsubscribeChannel(channelId).toJsonObject() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ── 广播电台 ─────────────────────────────────────────────────────────────

    /** 广播电台频道列表 / 我的收藏 → 卡片列表。 */
    public static List<DjRadio> parseBroadcastChannels(JsonObject root) {
        JsonArray channels = nestedArray(root, "channels", "list", "data");
        if (channels == null) return Collections.emptyList();
        List<DjRadio> result = new ArrayList<>();
        for (JsonElement element : channels) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            // 收藏列表条目可能把频道包在 channel/content 里。
            JsonObject channel = object(item, "channel");
            if (channel == null) channel = object(item, "content");
            if (channel == null) channel = item;
            long id = number(channel, "channelId", "id", "contentId");
            String name = text(channel, "name", "channelName", "title");
            if (id <= 0L || name.isEmpty()) continue;
            DjRadio card = new DjRadio();
            card.setId(id);
            card.setName(name);
            card.setPicUrl(text(channel, "coverUrl", "picUrl", "cover", "imgUrl"));
            card.setKind(DjRadio.Kind.BROADCAST_CHANNEL);
            card.setCategoryName(text(channel, "categoryName", "regionName"));
            card.setDesc(text(channel, "desc", "description"));
            result.add(card);
        }
        return result;
    }

    /**
     * 广播电台「正在播」信息的尽力提取。该端点的响应形状没有稳定文档，这里只把能找到的
     * 名称/标题串成一行给 UI 展示；找不到就返回空串，由页面给「暂无节目信息」。
     */
    public static String broadcastNowPlayingText(JsonObject root) {
        JsonObject data = object(root, "data");
        if (data == null) data = root;
        if (data == null) return "";
        JsonObject program = firstObject(data, "currentProgram", "program", "currentinfo", "currentInfo");
        if (program != null) {
            String name = text(program, "name", "title", "programName");
            if (!name.isEmpty()) return name;
        }
        String direct = text(data, "name", "title", "programName", "currentProgramName");
        return direct;
    }

    // ── 通用工具 ─────────────────────────────────────────────────────────────

    /**
     * 通用歌曲数组解析：新旧字段名都先归一化，再走 {@code JsonUtils.parse + applyNeteaseMetadata}
     * 的既有路径（与 {@code NeteaseDiscoveryPanel#parseSongs} 同一形状，但这里不依赖调用点）。
     */
    public static List<Music> parseSongArray(JsonArray songs) {
        if (songs == null) return Collections.emptyList();
        List<Music> result = new ArrayList<>();
        for (JsonElement element : songs) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject song = element.getAsJsonObject();
            JsonObject nested = object(song, "song");
            if (nested != null) song = nested;
            song = normalizeSongPayload(song);
            try {
                Music music = JsonUtils.parse(song, Music.class);
                if (music == null || music.getId() <= 0L) continue;
                music.applyNeteaseMetadata(song, null);
                result.add(music);
            } catch (Throwable ignored) {
                // 一条坏数据不能拖垮整页列表。
            }
        }
        return result;
    }

    /** 移动端字段名 artists/album/duration → 标准 DTO 映射的 ar/al/dt。 */
    private static JsonObject normalizeSongPayload(JsonObject source) {
        JsonObject normalized = new JsonObject();
        if (source == null) return normalized;
        for (java.util.Map.Entry<String, JsonElement> entry : source.entrySet()) {
            normalized.add(entry.getKey(), entry.getValue());
        }
        copyIfMissing(normalized, "ar", source, "artists");
        copyIfMissing(normalized, "al", source, "album");
        copyIfMissing(normalized, "dt", source, "duration");
        copyIfMissing(normalized, "alia", source, "alias");
        return normalized;
    }

    private static void copyIfMissing(JsonObject target, String targetKey, JsonObject source, String sourceKey) {
        if (!target.has(targetKey) && source.has(sourceKey) && !source.get(sourceKey).isJsonNull()) {
            target.add(targetKey, source.get(sourceKey));
        }
    }

    /** mainSong 缺失时的最小 {@link Music}：真 id 优先没有，就用节目自身 id 兜底。 */
    private static Music minimalMusic(long id, String name, String artist, String coverUrl, long duration) {
        List<Artist> artists = new ArrayList<>();
        artists.add(new Artist(0L, artist == null || artist.isEmpty() ? "未知主播" : artist,
                Collections.emptyList(), Collections.emptyList()));
        Music music = new Music(name == null || name.isEmpty() ? "未命名节目" : name,
                name == null || name.isEmpty() ? "未命名节目" : name, "", id, artists,
                Collections.emptyList(), new Album(0L, "", coverUrl == null ? "" : coverUrl,
                Collections.emptyList()),
                duration, 0L, 0L, Collections.emptyList());
        music.setExternalCoverUrl(coverUrl == null ? "" : coverUrl);
        // 最小兜底同样是节目，同样禁止换源。
        music.setDjProgram(true);
        return music;
    }

    /** 在 root 与它的 data / result 两层包装里找第一个存在的数组。 */
    public static JsonArray nestedArray(JsonObject root, String... names) {
        JsonArray direct = array(root, names);
        if (direct != null) return direct;
        JsonObject data = object(root, "data");
        direct = array(data, names);
        if (direct != null) return direct;
        JsonObject result = object(root, "result");
        return array(result, names);
    }

    private static JsonObject firstObject(JsonObject root, String... names) {
        if (root == null) return null;
        for (String name : names) {
            JsonObject found = object(root, name);
            if (found != null) return found;
        }
        return null;
    }

    private static JsonArray array(JsonObject object, String... names) {
        if (object == null || names == null) return null;
        for (String name : names) {
            try {
                if (name != null && object.has(name) && object.get(name).isJsonArray()) {
                    return object.getAsJsonArray(name);
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static JsonObject object(JsonObject object, String name) {
        if (object == null || name == null || !object.has(name) || !object.get(name).isJsonObject()) return null;
        return object.getAsJsonObject(name);
    }

    private static long number(JsonObject object, String... names) {
        if (object == null || names == null) return 0L;
        for (String name : names) {
            try {
                if (name != null && object.has(name) && !object.get(name).isJsonNull()) {
                    return object.get(name).getAsLong();
                }
            } catch (Throwable ignored) {
            }
        }
        return 0L;
    }

    private static String text(JsonObject object, String... names) {
        if (object == null || names == null) return "";
        for (String name : names) {
            try {
                if (name != null && object.has(name) && !object.get(name).isJsonNull()) {
                    String value = object.get(name).getAsString();
                    if (value != null && !value.trim().isEmpty()) return value.trim();
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private static boolean bool(JsonObject object, String name) {
        try {
            return object != null && object.has(name) && !object.get(name).isJsonNull()
                    && object.get(name).getAsBoolean();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
