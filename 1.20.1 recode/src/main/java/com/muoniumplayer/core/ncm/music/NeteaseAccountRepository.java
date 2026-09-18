package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.OptionsUtil;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.ncm.music.dto.User;
import com.muoniumplayer.core.utils.json.JsonUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 网易云账号数据请求与响应解析。
 *
 * <p>该类刻意没有 GUI、播放或可变播放器状态。
 * {@link CloudMusic} 外观仍负责把返回数据发布到
 * 既有的静态字段，并保持当前刷新生命周期。</p>
 */
final class NeteaseAccountRepository {

    private NeteaseAccountRepository() {
    }

    static User getUserProfile() {
        JsonObject jsonObject = CloudMusicApi.loginStatus().toJsonObject();
        JsonObject data = jsonObject.getAsJsonObject("data");
        if ((!data.has("account") || data.get("account") instanceof JsonNull)
                || (!data.has("profile") || data.get("profile") instanceof JsonNull)) {
            OptionsUtil.setCookie("");
            return null;
        }
        return JsonUtils.parse(data.getAsJsonObject("profile"), User.class);
    }

    static List<PlayList> loadUserPlaylists(User user) {
        List<PlayList> userPlaylists = new ArrayList<>();
        int page = 0;
        while (true) {
            List<PlayList> pagePlaylists;
            try {
                pagePlaylists = user.playLists(page, 30);
            } catch (Exception ignored) {
                pagePlaylists = new ArrayList<>();
            }
            if (pagePlaylists.isEmpty()) {
                break;
            }
            userPlaylists.addAll(pagePlaylists);
            page++;
        }
        return userPlaylists;
    }

    static List<PlayList> loadUserPlaylistsStrict(User user) {
        List<PlayList> userPlaylists = new ArrayList<>();
        int page = 0;
        // 一个合理的上限防止畸形 API 响应造成死循环。
        while (page < 1000) {
            List<PlayList> pagePlaylists = user.playLists(page, 30);
            if (pagePlaylists == null || pagePlaylists.isEmpty()) {
                break;
            }
            userPlaylists.addAll(pagePlaylists);
            page++;
        }
        if (page >= 1000) {
            throw new IllegalStateException("歌单数量异常");
        }
        return userPlaylists;
    }

    static List<Long> loadLikeList(User user) {
        if (user == null) {
            return new ArrayList<>();
        }
        List<Long> list = new ArrayList<>();
        JsonObject json = CloudMusicApi.likeList(user.getId()).toJsonObject();
        JsonArray ids = json.getAsJsonArray("ids");
        if (ids == null) {
            return list;
        }
        for (JsonElement id : ids) {
            list.add(id.getAsLong());
        }
        return list;
    }

    /**
     * 单独从歌单详情加载云盘 ID。普通歌单响应并不可靠地包含
     * 云标记，即使当前账号上传了同一首歌。
     */
    static Set<Long> loadCloudSongIds() {
        final int pageSize = 200;
        final int maximumPages = 100;
        Set<Long> result = new HashSet<>();
        try {
            for (int page = 0; page < maximumPages; page++) {
                JsonObject response = CloudMusicApi.userCloudSongs(pageSize, page * pageSize).toJsonObject();
                JsonArray entries = extractCloudEntries(response);
                if (entries == null || entries.size() == 0) {
                    break;
                }
                for (JsonElement element : entries) {
                    if (element != null && element.isJsonObject()) {
                        long songId = extractCloudSongId(element.getAsJsonObject());
                        if (songId > 0L) {
                            result.add(songId);
                        }
                    }
                }
                boolean hasMore = response.has("hasMore") && !response.get("hasMore").isJsonNull()
                        && response.get("hasMore").getAsBoolean();
                if (!hasMore && entries.size() < pageSize) {
                    break;
                }
            }
            return Collections.unmodifiableSet(result);
        } catch (Throwable throwable) {
            System.err.println("[NCM] Cloud-drive marker load failed: " + throwable.getMessage());
            return null;
        }
    }

    static List<Music> searchSongs(String keyword) {
        List<Music> searchResults = new ArrayList<>();
        JsonObject searchResponse = CloudMusicApi.cloudSearch(keyword, CloudMusicApi.SearchType.Single).toJsonObject();
        JsonArray songs = extractSongsFromResponse(searchResponse);
        if (songs != null) {
            for (JsonElement song : songs) {
                searchResults.add(JsonUtils.parse(song.getAsJsonObject(), Music.class));
            }
        }
        return searchResults;
    }

    private static JsonArray extractSongsFromResponse(JsonObject searchResponse) {
        try {
            JsonObject result = searchResponse.getAsJsonObject("result");
            return result != null ? result.getAsJsonArray("songs") : null;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse search response", e);
        }
    }

    static String qrKey() {
        JsonObject json = CloudMusicApi.loginQrKey().toJsonObject();
        return json.getAsJsonObject("data").get("unikey").getAsString();
    }

    private static JsonArray extractCloudEntries(JsonObject response) {
        if (response == null) return null;
        JsonElement data = response.get("data");
        if (data != null && data.isJsonArray()) return data.getAsJsonArray();
        if (data != null && data.isJsonObject()) {
            JsonObject dataObject = data.getAsJsonObject();
            JsonElement songs = dataObject.get("songs");
            if (songs != null && songs.isJsonArray()) return songs.getAsJsonArray();
            JsonElement nestedData = dataObject.get("data");
            if (nestedData != null && nestedData.isJsonArray()) return nestedData.getAsJsonArray();
        }
        JsonElement songs = response.get("songs");
        return songs != null && songs.isJsonArray() ? songs.getAsJsonArray() : null;
    }

    private static long extractCloudSongId(JsonObject cloudEntry) {
        JsonObject simpleSong = cloudEntry.has("simpleSong") && cloudEntry.get("simpleSong").isJsonObject()
                ? cloudEntry.getAsJsonObject("simpleSong") : null;
        long id = readCloudSongId(simpleSong, "id");
        if (id > 0L) return id;
        JsonObject song = cloudEntry.has("song") && cloudEntry.get("song").isJsonObject()
                ? cloudEntry.getAsJsonObject("song") : null;
        id = readCloudSongId(song, "id");
        if (id > 0L) return id;
        id = readCloudSongId(cloudEntry, "songId");
        return id > 0L ? id : readCloudSongId(cloudEntry, "id");
    }

    private static long readCloudSongId(JsonObject object, String property) {
        if (object == null || !object.has(property) || object.get(property).isJsonNull()) {
            return 0L;
        }
        try {
            return object.get(property).getAsLong();
        } catch (Throwable ignored) {
            return 0L;
        }
    }
}
