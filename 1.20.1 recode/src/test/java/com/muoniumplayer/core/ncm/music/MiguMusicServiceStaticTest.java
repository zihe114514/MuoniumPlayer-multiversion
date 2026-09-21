package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.ncm.music.dto.Music;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 咪咕内置搜索的纯解析面：喂 .scratch/mg_live.json（实测 code=000000 的 v3 searchAll
 * 响应）的形状，签名用固定时间戳钉死。不发任何网络。
 */
public class MiguMusicServiceStaticTest {

    @Test
    public void signatureIsDeterministicAndSaltedLikeUpstream() {
        // createSignature = MD5(text + 6cdc…c73 + yyapp2d…fd50 + deviceId + time)。
        // 期望值由同一算法的独立实现（Python hashlib）对同一输入算出。
        assertEquals("c9c072e589d7164ee0d94e8521fd0fda",
                MiguMusicService.createSignature("1700000000000", "夜曲").sign);
        assertEquals("e3279650384ff9e3f75738e09d19bdc7",
                MiguMusicService.createSignature("1699999999999", "晴天 Jay Chou").sign);
        assertEquals(MiguMusicService.DEVICE_ID,
                MiguMusicService.createSignature("1700000000000", "夜曲").deviceId);
    }

    @Test
    public void filterDataParsesLiveGroupedShapeAndDedupesByCopyrightId() {
        JsonArray group = new JsonArray();
        group.add(miguItem("2643", "60054701947", "夜曲", 227, "十一月的萧邦", "8591",
                "/data/oss/resource/00/41/zg/7229b.webp", "周杰伦"));
        // 同 copyrightId 的第二次出现必须被丢掉（上游 ids Set 去重）。
        group.add(miguItem("9999", "60054701947", "夜曲 重复", 227, "十一月的萧邦", "8591",
                "/x.webp", "周杰伦"));
        // 缺 copyrightId 的项直接不要（脚本按它取流，没了它就什么都解不出）。
        JsonObject noCopy = miguItem("1234", "", "缺主键", 200, "", "", "", "");
        group.add(noCopy);
        JsonArray resultList = new JsonArray();
        resultList.add(group);

        List<Music> list = MiguMusicService.filterData(resultList);
        assertEquals(1, list.size());
        Music night = list.get(0);
        assertEquals(MusicPlatform.MIGU, night.getSource());
        assertEquals("2643", night.getSourceId());
        // copyrightId 进 sourceMid —— lxTrack 的 mg 分支只从那里读它。
        assertEquals("60054701947", night.getSourceMid());
        assertEquals("夜曲", night.getName());
        assertEquals(227_000L, night.getDuration());
        assertEquals("周杰伦", night.getArtistsName());
        // 相对封面被补上前缀；getCoverUrl 对无尺寸的外链把 http 升成 https。
        assertEquals("https://d.musicapp.migu.cn/data/oss/resource/00/41/zg/7229b.webp",
                night.getCoverUrl(300));
    }

    @Test
    public void absoluteCoverUrlIsKeptAsIs() {
        JsonArray group = new JsonArray();
        group.add(miguItem("1", "c1", "歌", 100, "", "", "https://cdn.migu.cn/a.webp", "歌手"));
        JsonArray resultList = new JsonArray();
        resultList.add(group);
        assertEquals("https://cdn.migu.cn/a.webp",
                MiguMusicService.resolveImg(group.get(0).getAsJsonObject()));
    }

    @Test
    public void singerListJoinUsesUpstreamSeparator() {
        JsonArray singers = new JsonArray();
        JsonObject a = new JsonObject();
        a.addProperty("name", "周杰伦");
        JsonObject b = new JsonObject();
        b.addProperty("name", "费玉清");
        JsonObject blank = new JsonObject();
        blank.addProperty("name", "");
        singers.add(a);
        singers.add(b);
        singers.add(blank);
        assertEquals("周杰伦、费玉清", MiguMusicService.formatSingerName(singers));
        assertEquals("", MiguMusicService.formatSingerName(null));
    }

    @Test
    public void liveFixtureFirstSongParsesEndToEnd() {
        // 用真机抓来的 .scratch/mg_live.json 走一遍完整解析链（从测试工作目录向上找，
        // 找不到 —— 比如别的版本树或干净机器 —— 就安静跳过）。
        java.io.File fixture = findUpwards(".scratch/mg_live.json");
        if (fixture == null) return;
        try {
            JsonObject root = JsonParser.parseString(
                    java.nio.file.Files.readString(fixture.toPath(),
                            java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("000000", root.get("code").getAsString());
            List<Music> list = MiguMusicService.filterData(
                    root.getAsJsonObject("songResultData").get("resultList"));
            assertTrue(list.size() > 0);
            assertEquals("60054701947", list.get(0).getSourceMid()); // 夜曲 · 周杰伦
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static java.io.File findUpwards(String relative) {
        java.io.File dir = new java.io.File("").getAbsoluteFile();
        for (int depth = 0; dir != null && depth < 6; depth++, dir = dir.getParentFile()) {
            java.io.File candidate = new java.io.File(dir, relative);
            if (candidate.isFile()) return candidate;
        }
        return null;
    }

    private static JsonObject miguItem(String songId, String copyrightId, String name, int duration,
                                       String album, String albumId, String img3, String singer) {
        JsonObject item = new JsonObject();
        if (!songId.isEmpty()) item.addProperty("songId", songId);
        if (!copyrightId.isEmpty()) item.addProperty("copyrightId", copyrightId);
        item.addProperty("name", name);
        item.addProperty("duration", duration);
        item.addProperty("album", album);
        item.addProperty("albumId", albumId);
        item.addProperty("img3", img3);
        JsonArray singers = new JsonArray();
        if (!singer.isEmpty()) {
            JsonObject one = new JsonObject();
            one.addProperty("name", singer);
            singers.add(one);
        }
        item.add("singerList", singers);
        return item;
    }
}
