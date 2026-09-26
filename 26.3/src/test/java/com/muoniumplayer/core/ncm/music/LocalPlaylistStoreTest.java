package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.ncm.music.dto.Music;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地歌单的曲目存档往返：<b>一首歌必须带着「该去哪个平台、用哪个键取流」回来</b>。
 *
 * <p>这里刻意不碰磁盘：{@link LocalPlaylistStore} 的落盘路径是一个 static final 的
 * {@code File}，测试里去读写它会污染用户真实的 {@code local_playlists.json}。被钉住的是
 * 那段序列化本身 —— 往返全在内存里跑，也就只测它。</p>
 *
 * <p>最要紧的一条是 {@code serializeTrack} 里那一小包 {@code _x}：{@code Music} 上
 * {@code source}/{@code sourceId}/酷狗三个 hash/GD 三个 id 全是 {@code transient}，而 Gson
 * 默认<b>跳过 transient 字段</b>。少了那一包，存出来的 JSON 看着完整、读回来却没有任何平台信息，
 * 每一首非网易云的歌曲都会退化成「按网易云那个数字 id 去取」—— 放出另一首歌，还是不报错的那种。</p>
 */
class LocalPlaylistStoreTest {

    /**
     * 造一首歌。{@code Music} 的核心字段（歌名、id、时长）是 {@code final}，所以只能走 Lombok 那个
     * 必填构造器；专辑这里给 {@code null} —— 存档往返测的是平台取流键那一路，不是封面与艺人。
     */
    private static Music track(String name, long id, long duration) {
        return new Music(name, name, "", id, Collections.emptyList(), Collections.emptyList(),
                null, duration, 0L, 0L, Collections.emptyList());
    }

    private static Music kuGouTrack() {
        Music music = track("海阔天空", 0L, 327_000L);
        music.setSource(MusicPlatform.KUGOU);
        music.setSourceId("1234567890");
        music.setSourceMid("Mid:abcdef");
        music.setExternalCoverUrl("http://img.kuwo.cn/a.jpg");
        music.setKugouHash("0123456789abcdef0123456789abcdef");
        music.setKugouHash320("aa320");
        music.setKugouHashLossless("bb000");
        music.setKugouAlbumId("album-7");
        music.setKugouAlbumAudioId("audio-7");
        music.setKugouCoverTemplate("tpl");
        return music;
    }

    private static Music roundTrip(Music music) {
        JsonObject stored = LocalPlaylistStore.serializeTrack(music);
        // 那一小包 _x 就是 transient 字段唯一的去处；它不在，下面读回来就是一首「网易云的歌」。
        assertTrue(stored.has("_x"), "平台取流键全在 _x 里，没写进去等于什么都没存");
        Music back = LocalPlaylistStore.deserializeTrack(stored);
        assertNotNull(back);
        return back;
    }

    @Test
    @DisplayName("酷狗那首歌的取流键原样回来")
    void sourceKeysSurviveTheRoundTrip() {
        Music back = roundTrip(kuGouTrack());

        assertEquals(MusicPlatform.KUGOU, back.getSource());
        assertEquals("1234567890", back.getSourceId());
        assertEquals("Mid:abcdef", back.getSourceMid());
        assertEquals("0123456789abcdef0123456789abcdef", back.getKugouHash());
        assertEquals("aa320", back.getKugouHash320());
        assertEquals("bb000", back.getKugouHashLossless());
        assertEquals("audio-7", back.getKugouAlbumAudioId());
        assertEquals("tpl", back.getKugouCoverTemplate());
        // 歌名与时长走的是 Gson 自己的那条路（它们是普通字段），这里只确认没被那一包 _x 顶掉。
        assertEquals("海阔天空", back.getName());
        assertEquals(327_000L, back.getDuration());

        // 反过来：只有数字 id 的那类（网易云官方载荷就是这样）不能把兜底串存成 sourceId，
        // 否则读回来变成「它有 sourceId」，歌词/封面那些先按 sourceId 问的链路会拿着这个数字去问别家。
        Music netease = track("旧网易云的一首", 185811L, 0L);
        netease.setSource(MusicPlatform.NETEASE);
        assertFalse(LocalPlaylistStore.serializeTrack(netease).getAsJsonObject("_x")
                .has("sourceId"), "兜底出来的数字不是 sourceId");
    }

    @Test
    @DisplayName("GD 那三个 id 与网易云云端歌曲标记也存得住")
    void otherSourcePrivateKeysSurvive() {
        Music gd = track("翻唱", 0L, 0L);
        gd.setSource(MusicPlatform.GD);
        gd.setSourceId("gd-track-1");
        gd.setGdPlatform("kuwo");
        gd.setGdPicId("pic-1");
        gd.setGdLyricId("lyric-1");

        Music backGd = roundTrip(gd);
        assertEquals(MusicPlatform.GD, backGd.getSource());
        assertEquals("kuwo", backGd.getGdPlatform());
        assertEquals("pic-1", backGd.getGdPicId());
        assertEquals("lyric-1", backGd.getGdLyricId());

        Music cloud = track("本地上传", 99887766L, 0L);
        cloud.setSource(MusicPlatform.NETEASE);
        cloud.setCloudSong(true);
        assertTrue(roundTrip(cloud).isCloudSong(), "云端歌曲标记丢了就会被当普通网易云歌曲去取流");
    }

    @Test
    @DisplayName("上一次点播发生了什么，一律不进存档")
    void runtimeStateIsNotPersisted() {
        Music music = kuGouTrack();
        music.setPlaybackQuality(Music.PlaybackQuality.LOSSLESS);
        music.setGreyDetected(true);
        music.setPartyStreamUrl("http://127.0.0.1:1/leak.ogg");
        music.setQishuiPlayAuth("one-time-auth");
        music.setLxCoverUrl("http://lx/cover.jpg");
        music.setDecodedDurationMillis(1234L);

        String json = LocalPlaylistStore.serializeTrack(music).toString();
        for (String runtimeKey : new String[]{"playbackQuality", "greyDetected", "partyStreamUrl",
                "qishuiPlayAuth", "lxCoverUrl", "decodedDurationMillis", "coverPalette"}) {
            assertFalse(json.contains('"' + runtimeKey + '"'),
                    runtimeKey + " 描述的是上一次点播，落进存档就是下一次启动照着旧结果播");
        }
    }

    @Test
    @DisplayName("同一首歌在两张单里是同一个键，不同平台同名 id 不算同一首")
    void identityIsScopedToItsPlatform() {
        Music a = kuGouTrack();
        Music sameDifferentObject = kuGouTrack();
        assertEquals(LocalPlaylistStore.identityOf(a), LocalPlaylistStore.identityOf(sameDifferentObject),
                "去重靠的是这个键，两个对象算出不同的话同一首能排两遍");

        // 同一个编号在两个平台上是两个意思：各平台的 id 是自己体系里的编号，跨平台撞上太常见了。
        Music kugouNoMid = kuGouTrack();
        kugouNoMid.setSourceMid("");
        Music neteaseWithSameId = track("同名的一首", 0L, 0L);
        neteaseWithSameId.setSource(MusicPlatform.NETEASE);
        neteaseWithSameId.setSourceId("1234567890");
        assertEquals("kugou:1234567890", LocalPlaylistStore.identityOf(kugouNoMid));
        assertNotEquals(LocalPlaylistStore.identityOf(kugouNoMid),
                LocalPlaylistStore.identityOf(neteaseWithSameId));

        // 有的源只给数字 id，有的连 id 都不填：退到 mid，再退到「歌名 + 时长」。
        Music onlyMid = track("稿件", 0L, 0L);
        onlyMid.setSource(MusicPlatform.BILIBILI);
        onlyMid.setSourceMid("BV1xx411c7mD");
        assertEquals("bilibili:mid:BV1xx411c7mD", LocalPlaylistStore.identityOf(onlyMid));

        Music bare = track("没有 id 的一首", 0L, 61_000L);
        bare.setSource(MusicPlatform.GD);
        assertEquals("gd:name:没有 id 的一首:61000", LocalPlaylistStore.identityOf(bare),
                "没键可退之后按歌名+时长兜底；注意绝不能算成「平台:0」，那是所有无键歌曲共用的一个值");

        // 这条钉住的是 getSourceId() 那个「空就退化成 String.valueOf(id)」的兜底：两首都拿不到
        // 真键的歌如果都算出「平台:0」，第二首就永远加不进这张单。
        Music first = track("第一首", 0L, 61_000L);
        first.setSource(MusicPlatform.GD);
        Music second = track("第二首", 0L, 61_000L);
        second.setSource(MusicPlatform.GD);
        assertNotEquals(LocalPlaylistStore.identityOf(first), LocalPlaylistStore.identityOf(second),
                "两首没键的歌算出同一个 identity，就是后一首再也加不进去");
    }

    @Test
    @DisplayName("坏掉的一条曲目返回 null，而不是把整份存档带崩")
    void malformedTrackIsSkipped() {
        assertNull(LocalPlaylistStore.deserializeTrack(JsonParser.parseString("3")));
        assertNull(LocalPlaylistStore.deserializeTrack(JsonParser.parseString("\"一首歌\"")));

        // 一份无 _x 的历史遗留 JSON：普通字段仍要读得出来，来源退回默认那档而不是抛异常。
        JsonObject legacy = new JsonObject();
        legacy.addProperty("id", 185811L);
        legacy.addProperty("name", "旧版存的一首");
        Music back = LocalPlaylistStore.deserializeTrack(legacy);
        assertNotNull(back);
        assertEquals(185811L, back.getId());
        assertEquals(MusicPlatform.NETEASE, back.getSource());
    }
}
