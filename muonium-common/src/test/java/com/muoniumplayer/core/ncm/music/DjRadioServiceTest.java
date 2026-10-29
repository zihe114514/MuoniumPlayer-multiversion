package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.ncm.music.dto.DjRadio;
import com.muoniumplayer.core.ncm.music.dto.Music;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 电台/播客解析层的离线探针：纯 CPU 逻辑，不联网、不要 GL 或客户端实例。
 *
 * <p>把「各端点的响应形状都能落到同一张卡」「节目必须取到 mainSong 的真实 songId」
 * 「缺 mainSong 的条目跳过而不是报错」「新旧歌曲字段名都要能解析」四类行为钉成断言。</p>
 */
class DjRadioServiceTest {

    private static JsonObject json(String raw) {
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    // ── 电台卡片解析 ─────────────────────────────────────────────────────────

    @Test
    void parseRadiosReadsStandardDjradiosArray() {
        JsonObject root = json("""
                {"djRadios":[
                  {"id":336355127,"name":"代码时间","picUrl":"http://p1.music.126.net/abc.jpg",
                   "category":"科技科学","subCount":1234,"programCount":56,"playCount":7890,
                   "desc":"聊聊编程","subscribed":true,
                   "dj":{"userId":1,"nickname":"binaryify"}}
                ]}""");

        List<DjRadio> radios = DjRadioService.parseRadios(root);
        assertEquals(1, radios.size());
        DjRadio radio = radios.get(0);
        assertEquals(336355127L, radio.getId());
        assertEquals("代码时间", radio.getName());
        assertEquals("binaryify", radio.getDjName());
        assertEquals("科技科学", radio.getCategoryName());
        assertEquals(1234L, radio.getSubCount());
        assertEquals(56L, radio.getProgramCount());
        assertTrue(radio.isSubscribed());
        assertTrue(radio.coverUrl().startsWith("https://"), "http 封面要升级成 https");
        assertEquals("binaryify · 科技科学 · 56 期", radio.subtitle());
    }

    @Test
    void parseRadiosUnwrapsDataAndDjRadioWrappers() {
        // 最近收听（play-record）条目把电台包在 djRadio 里，列表外再包一层 data。
        JsonObject root = json("""
                {"code":200,"data":{"list":[
                  {"playTime":1700000000000,"djRadio":{"id":9,"name":"深夜电台","picUrl":"https://x/y.jpg"}}
                ]}}""");

        List<DjRadio> radios = DjRadioService.parseRadios(root);
        assertEquals(1, radios.size());
        assertEquals(9L, radios.get(0).getId());
        assertEquals("深夜电台", radios.get(0).getName());
        assertEquals(1700000000000L, radios.get(0).getLastUpdateTime(), "playTime 要落到 lastUpdateTime");
    }

    @Test
    void parseRadiosSkipsBrokenEntries() {
        JsonObject root = json("""
                {"djRadios":[
                  {"name":"没有 id"},
                  {"id":0,"name":"id 非法"},
                  {"id":7,"name":"  "}
                ]}""");

        // id 合法但名字为空的条目仍保留（displayName 会兜底），缺 id 的直接丢弃。
        List<DjRadio> radios = DjRadioService.parseRadios(root);
        assertEquals(1, radios.size());
        assertEquals(7L, radios.get(0).getId());
        assertEquals("未命名电台", radios.get(0).displayName());
    }

    // ── 节目 → Music ─────────────────────────────────────────────────────────

    @Test
    void parseProgramsUsesMainSongRealId() {
        JsonObject root = json("""
                {"code":200,"count":2,"programs":[
                  {"id":111,"name":"第 1 期 · 聊聊编译器","coverUrl":"https://p1/cover.jpg",
                   "duration":1800000,"createTime":1700000000000,
                   "dj":{"nickname":"binaryify"},
                   "mainSong":{"id":90001,"name":"第 1 期 · 聊聊编译器",
                     "ar":[{"id":1,"name":"binaryify"}],
                     "al":{"id":2,"name":"代码时间","picUrl":"https://p1/song.jpg"},
                     "dt":1800000,"fee":8}},
                  {"id":222,"name":"坏数据，没有 mainSong"}
                ]}""");

        List<Music> programs = DjRadioService.parsePrograms(root);
        // 没有 mainSong 也没有 mainSongId 的第二条被跳过，而不是让整页报错。
        assertEquals(1, programs.size());
        Music music = programs.get(0);
        assertEquals(90001L, music.getId(), "必须用 mainSong 的真实 songId");
        assertEquals("binaryify", music.getArtistsName());
        assertEquals(1800000L, music.getDuration());
        // 节目一律带 djProgram 标记：换源闸门（自动 + 手动）据此对它短路。
        assertTrue(music.isDjProgram(), "节目必须打 djProgram 标以禁用换源");
    }

    @Test
    void programWithOnlyMainSongIdStillGetsFlag() {
        // mainSong 缺形但显式给了 mainSongId 的兜底路径同样要打标。
        JsonObject program = json("""
                {"id":444,"mainSongId":90003,"name":"兜底节目","duration":30000,
                 "dj":{"nickname":"小主播"}}""");

        Music music = DjRadioService.programToMusic(program);
        assertNotNull(music);
        assertEquals(90003L, music.getId());
        assertTrue(music.isDjProgram());
    }

    @Test
    void difmTracksKeepCrossSourceEligibility() {
        // DIFM 曲目是电台策展的普通歌曲（真歌名、真歌手），保留换源资格。
        JsonObject root = json("""
                {"code":200,"data":{"tracks":[
                  {"id":70001,"name":"Moon River","artists":[{"id":9,"name":"Andy Williams"}],
                   "duration":150000}
                ]}}""");

        List<Music> tracks = DjRadioService.parseDifmTracks(root);
        assertEquals(1, tracks.size());
        assertTrue(!tracks.get(0).isDjProgram(), "DIFM 普通歌曲不应被打成节目");
    }

    @Test
    void programToMusicFallsBackToLegacyFieldNames() {
        // 部分端点给的是移动端字段名 artists/album/duration，归一化后必须照样解析。
        JsonObject program = json("""
                {"id":333,"name":"老字段节目",
                 "mainSong":{"id":90002,"name":"老字段节目",
                   "artists":[{"id":1,"name":"老主播"}],
                   "album":{"id":3,"name":"老专辑","picUrl":"https://p1/old.jpg"},
                   "duration":60000}}""");

        Music music = DjRadioService.programToMusic(program);
        assertNotNull(music);
        assertEquals(90002L, music.getId());
        assertEquals("老主播", music.getArtistsName());
        assertEquals(60000L, music.getDuration());
    }

    @Test
    void programToMusicMinimalFallbackWhenMainSongMissing() {
        // mainSong 缺形但带 mainSongId 时，兜底成最小 Music（真 id 优先）。
        JsonObject program = json("""
                {"id":444,"mainSongId":90003,"name":"兜底节目","duration":30000,
                 "dj":{"nickname":"小主播"}}""");

        Music music = DjRadioService.programToMusic(program);
        assertNotNull(music);
        assertEquals(90003L, music.getId());
        assertEquals("兜底节目", music.getName());
        assertEquals("小主播", music.getArtistsName());

        // 连 id 都没有：只能放弃这一条。
        assertNull(DjRadioService.programToMusic(json("{\"name\":\"什么都没有\"}")));
    }

    // ── DIFM / 广播 / 分类 ───────────────────────────────────────────────────

    @Test
    void parseDifmChannelsReadsChannelList() {
        JsonObject root = json("""
                {"code":200,"data":{"channelList":[
                  {"id":1012,"name":"午夜爵士","coverUrl":"https://x/z.jpg"},
                  {"id":1013,"name":"巴洛克时钟","coverUrl":"https://x/w.jpg"}
                ]}}""");

        List<DjRadio> channels = DjRadioService.parseDifmChannels(root);
        assertEquals(2, channels.size());
        assertEquals(1012L, channels.get(0).getId());
        assertEquals(DjRadio.Kind.DIFM_CHANNEL, channels.get(0).getKind());
        assertEquals("午夜爵士", channels.get(0).getName());
    }

    @Test
    void parseBroadcastChannelsUnwrapsContentWrapper() {
        // 收藏列表条目把频道包在 content 里。
        JsonObject root = json("""
                {"code":200,"data":{"list":[
                  {"contentId":5,"channelName":"交通广播","coverUrl":"https://x/b.jpg"},
                  {"channel":{"id":6,"name":"音乐之声","picUrl":"https://x/c.jpg"}}
                ]}}""");

        List<DjRadio> channels = DjRadioService.parseBroadcastChannels(root);
        assertEquals(2, channels.size());
        assertEquals(DjRadio.Kind.BROADCAST_CHANNEL, channels.get(0).getKind());
        assertEquals("交通广播", channels.get(0).getName());
        assertEquals(6L, channels.get(1).getId());
        assertEquals("音乐之声", channels.get(1).getName());
    }

    @Test
    void parseCategoriesReadsIdNamePairs() {
        JsonObject root = json("""
                {"code":200,"categories":[
                  {"id":2001,"name":"创作|翻唱"},
                  {"id":10002,"name":"3D|电子"},
                  {"name":"没有 id 的会被跳过"}
                ]}""");

        List<String[]> categories = DjRadioService.parseCategories(root);
        assertEquals(2, categories.size());
        assertEquals("2001", categories.get(0)[0]);
        assertEquals("创作|翻唱", categories.get(0)[1]);
    }

    @Test
    void broadcastNowPlayingTextFindsProgramName() {
        assertEquals("早间新闻", DjRadioService.broadcastNowPlayingText(json("""
                {"code":200,"data":{"currentProgram":{"name":"早间新闻"}}}""")));
        assertEquals("", DjRadioService.broadcastNowPlayingText(json("{\"code\":200}")),
                "找不到节目信息就返回空串，由 UI 给降级提示");
    }
}
