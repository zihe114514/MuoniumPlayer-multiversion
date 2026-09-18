package com.muoniumplayer.core.ncm.music.dto;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import com.muoniumplayer.core.ncm.RequestUtil;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.NeteaseMusicService;
import com.muoniumplayer.core.ncm.music.KuGouMusicService;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.QishuiMusicService;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.json.JsonUtils;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 歌单对象
 */
@Data
public class PlayList {

    @SerializedName("id")
    private final long id;

    @SerializedName("name")
    private final String name;

    @SerializedName(value = "coverImgUrl")
    private final String coverUrl;

    @SerializedName("trackCount")
    private final int count;

    @SerializedName(value = "playCount")
    private final long playCount;

    @SerializedName("creator")
    private final User creator;

    @SerializedName("description")
    private final String description;

    @SerializedName("subscribed")
    private boolean subscribed;

    @SerializedName("createTime")
    private final long createTime;

    // 特有字段
    public transient List<Music> musics;
    private transient boolean searchMode = false;
    /**
     * 真正来自搜索框的结果列表。
     *
     * <p>{@code searchMode} 被发现页（热搜/榜单/专辑/最近播放）复用来表示"曲目已经在内存里，不要再去
     * 拉歌单详情"，所以它区分不出"这是搜索结果"。起播行为只对搜索结果特殊处理，因此单独一个标记。</p>
     */
    private transient boolean searchResultList = false;
    /**
     * 歌单来源。旧网易云对象没有该字段时保持 NETEASE，QQ 歌单则由
     * NeteaseMusicService 在适配时显式写入。
     */
    private transient MusicPlatform platform = MusicPlatform.NETEASE;
    /** QQ 的 dissid/tid 原始字符串，避免把跨平台标识强行压缩成网易云 long id。 */
    private transient String platformPlaylistId;
    /** 当为私人 FM session 所拥有的临时队列时返回 true。 */
    private transient boolean personalFm;
    public transient volatile boolean musicsQueried = false, musicsLoaded = false;

    public final Location getCoverLocation() {
        String source = getPlatform().name().toLowerCase(java.util.Locale.ROOT);
        return Location.of("muonium/textures/playlist/" + source + "/" + this.id + "/cover.png");
    }

    public MusicPlatform getPlatform() {
        return platform == null ? MusicPlatform.NETEASE : platform;
    }

    public String getPlatformPlaylistId() {
        if (platformPlaylistId != null && !platformPlaylistId.trim().isEmpty()) {
            return platformPlaylistId;
        }
        return String.valueOf(id);
    }

    public List<Music> getMusics() {
        boolean startQuery = false;
        List<Music> currentMusics;

        synchronized (this) {
            ensureMusicsList();
            currentMusics = this.musics;
            if (!this.musicsQueried && !searchMode) {
                this.musicsQueried = true;
                startQuery = true;
            }
        }

        if (startQuery) {
            startMusicQuery();
        }
        return currentMusics;
    }

    /**
     * 为当前加载注册一个监听器，包括已经由 {@link #getMusics()}
     * 启动的加载。这可避免面板在大型播放列表仍在下载时打开而
     * 错过加载结果。
     */
    public void loadMusicsWithCallback(MusicsLoadedCallback callback) {
        if (callback == null) {
            return;
        }

        boolean startQuery = false;
        List<Music> alreadyLoaded = null;
        synchronized (this) {
            ensureMusicsList();
            if (this.musicsLoaded || (searchMode && !this.musics.isEmpty())) {
                alreadyLoaded = this.musics;
            } else {
                getMusicLoadCallbacks().add(callback);
                if (!this.musicsQueried && !searchMode) {
                    this.musicsQueried = true;
                    startQuery = true;
                }
            }
        }

        if (alreadyLoaded != null) {
            callback.onMusicsLoaded(alreadyLoaded);
        } else if (startQuery) {
            startMusicQuery();
        }
    }

    private transient List<MusicsLoadedCallback> musicLoadCallbacks;

    private void ensureMusicsList() {
        if (this.musics == null) {
            this.musics = new CopyOnWriteArrayList<>();
        }
    }

    private List<MusicsLoadedCallback> getMusicLoadCallbacks() {
        if (this.musicLoadCallbacks == null) {
            this.musicLoadCallbacks = new ArrayList<>();
        }
        return this.musicLoadCallbacks;
    }

    private void startMusicQuery() {
        MultiThreadingUtil.runAsync(() -> {
            queryMusics();
            notifyMusicLoadCallbacks();
        });
    }

    private void notifyMusicLoadCallbacks() {
        final List<MusicsLoadedCallback> callbacks;
        final List<Music> result;
        synchronized (this) {
            callbacks = new ArrayList<>(getMusicLoadCallbacks());
            getMusicLoadCallbacks().clear();
            result = this.musics;
        }

        if (callbacks.isEmpty()) {
            return;
        }

        // UI 面板会在这些回调中创建控件，因此要把它们派发到
        // Minecraft 主线程上执行，而不是后台 HTTP 工作线程。
        MultiThreadingUtil.runOnMainThread(() -> {
            for (MusicsLoadedCallback callback : callbacks) {
                try {
                    callback.onMusicsLoaded(result);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void queryMusics() {
        try {
            List<Music> loadedMusics;
            if (getPlatform() == MusicPlatform.QQ) {
                // QQ 的歌单 id 是字符串 dissid/tid，并且曲目对象需要保留 QQ source/mid。
                loadedMusics = NeteaseMusicService.getQQPlaylistTracks(
                        getPlatformPlaylistId(), Math.max(100, count > 0 ? count : 100));
                if (loadedMusics == null) {
                    throw new IllegalStateException("QQ playlist track response is null");
                }
            } else if (getPlatform() == MusicPlatform.KUGOU) {
                // 酷狗有三种歌单（精选 specialid / 榜单 rankid / 用户 global_collection_id），
                // 取曲目的端点不同，前缀编码在 platformPlaylistId 里，由服务层分派。
                loadedMusics = com.muoniumplayer.core.ncm.music.KuGouMusicService
                        .playlistTracks(getPlatformPlaylistId());
                if (loadedMusics == null) {
                    throw new IllegalStateException("KuGou playlist track response is null");
                }
            } else if (getPlatform() == MusicPlatform.QISHUI) {
                String qishuiId = getPlatformPlaylistId();
                int requested = Math.max(100, count > 0 ? count : 100);
                loadedMusics = qishuiId.startsWith("radio:")
                        ? QishuiMusicService.radioTracks(qishuiId, requested)
                        : QishuiMusicService.playlistTracks(qishuiId, requested);
                if (loadedMusics == null) {
                    throw new IllegalStateException("Qishui playlist track response is null");
                }
            } else {
                RequestUtil.RequestAnswer requestAnswer = CloudMusicApi.playlistTrackAll(id, 8);
                JsonObject response = requestAnswer.toJsonObject();
                JsonArray songs = response == null ? null : response.getAsJsonArray("songs");
                JsonArray privileges = response == null ? null : response.getAsJsonArray("privileges");
                if (songs == null) {
                    throw new IllegalStateException("playlist song detail response does not contain songs");
                }

                // CopyOnWriteArrayList 在每次 add 时都会复制其底层数组。
                // 先解析，再用一次 addAll 调用发布完整结果。网易云
                // 在并行的权限数组中返回最高质量/权限数据。
                loadedMusics = new ArrayList<>(songs.size());
                for (int index = 0; index < songs.size(); index++) {
                    JsonObject songObject = songs.get(index).getAsJsonObject();
                    Music music = JsonUtils.parse(songObject, Music.class);
                    JsonObject privilege = privileges != null && index < privileges.size()
                            && privileges.get(index).isJsonObject()
                            ? privileges.get(index).getAsJsonObject() : null;
                    music.applyNeteaseMetadata(songObject, privilege);
                    loadedMusics.add(music);
                }
            }
            synchronized (this) {
                ensureMusicsList();
                this.musics.clear();
                this.musics.addAll(loadedMusics);
                this.musicsLoaded = true;
            }
        } catch (Exception e) {
            synchronized (this) {
                this.musicsQueried = false;
                this.musicsLoaded = false;
            }
            e.printStackTrace();
        }
    }
    public interface MusicsLoadedCallback {
        void onMusicsLoaded(List<Music> musics);
    }

    public void updPlayCount() {
        if (getPlatform() == MusicPlatform.NETEASE) {
            CloudMusicApi.playlistUpdatePlaycount(this.id);
        }
    }

    /**
     * 加入歌单并返回真实的服务端校验结果，供需要展示反馈的界面使用。
     */
    public CloudMusicApi.PlaylistTrackOperationResult addToListWithResult(long musicId) {
        return CloudMusicApi.addTrackToPlaylist(this.id, musicId);
    }

    /**
     * 兼容旧调用点：仍可直接提交加入请求，但不读取结果。
     */
    public void addToList(long musicId) {
        addToListWithResult(musicId);
    }

    /**
     * 收藏/取消收藏本歌单。本地状态由 Lombok 生成的 setSubscribed 维护，
     * 此方法仅负责调用原 API 链路。
     *
     * <p>两个源在这里分派：网易云走 {@code /weapi/playlist/subscribe|unsubscribe}；
     * 酷狗那边「收藏」和「取消收藏」是<b>两个不同的端点</b>
     * （{@code cloudlist.service/v5/add_list} 带 {@code type=1} 与 {@code cloudlist/v2/delete_list}），
     * 而且收藏时要带上原歌单的主键三元组，所以不能套用网易云那种「一个端点带布尔开关」的形态。</p>
     */
    public void subscribe(boolean subscribed) {
        if (getPlatform() == MusicPlatform.NETEASE) {
            CloudMusicApi.subscribe(this.id, subscribed);
            return;
        }
        if (getPlatform() == MusicPlatform.KUGOU) {
            if (subscribed) {
                KuGouMusicService.subscribePlaylist(this);
            } else {
                KuGouMusicService.unsubscribePlaylist(this);
            }
        }
    }

    public void removeFromList(long musicId) {
        if (getPlatform() == MusicPlatform.NETEASE) {
            CloudMusicApi.playlistTracks("del", this.id, String.valueOf(musicId));
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        PlayList playList = (PlayList) o;
        return id == playList.id && getPlatform() == playList.getPlatform();
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, getPlatform());
    }
}
