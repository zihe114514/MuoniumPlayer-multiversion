package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.dto.Music;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 酷我内置搜索的纯解析面：全部喂实测过的响应形状（.scratch/kw_live.json 的字段结构），
 * 不发任何网络。
 */
public class KuwoMusicServiceStaticTest {

    @Test
    public void decodeNameHandlesNumericAndNamedEntities() {
        assertEquals("钢", KuwoMusicService.decodeName("&#38050;"));
        assertEquals("钢", KuwoMusicService.decodeName("&#x94A2;"));
        assertEquals("A&B", KuwoMusicService.decodeName("A&amp;B"));
        assertEquals("it's <ok> \"x\"", KuwoMusicService.decodeName("it&#39;s &lt;ok&gt; &quot;x&quot;"));
        // 解不出来的实体原样保留，不吞字；空值安全。
        assertEquals("&weird;", KuwoMusicService.decodeName("&weird;"));
        assertEquals("", KuwoMusicService.decodeName(null));
        assertEquals("", KuwoMusicService.decodeName(""));
    }

    @Test
    public void formatSingerReplacesAmpersandWithIdeographicComma() {
        assertEquals("周杰伦、温岚", KuwoMusicService.formatSinger("周杰伦&温岚"));
        assertEquals("", KuwoMusicService.formatSinger(null));
    }

    @Test
    public void formatPlayTimeMatchesUpstreamEdgeCases() {
        assertEquals("03:46", KuwoMusicService.formatPlayTime(226));
        assertEquals("--/--", KuwoMusicService.formatPlayTime(0));
        assertEquals("--/--", KuwoMusicService.formatPlayTime(-1));
        assertEquals("67:20", KuwoMusicService.formatPlayTime(4040));
    }

    @Test
    public void handleResultBuildsPlayableMusicFromLiveShape() {
        JsonArray abslist = new JsonArray();
        abslist.add(kuwoItem("MUSIC_118980", "夜曲", "周杰伦", "14466", "十一月的萧邦", "226",
                "level:ff,bitrate:2000,format:flac,size:25.45Mb;level:h,bitrate:320,format:mp3,size:8.61Mb"
                        + ";level:l,bitrate:128,format:mp3,size:3.62Mb"));
        List<Music> result = KuwoMusicService.handleResult(abslist);
        assertEquals(1, result.size());
        Music night = result.get(0);
        // MUSICRID 的 MUSIC_ 前缀被剥掉，主键进 sourceId；纯数字直接当稳定 id。
        assertEquals(MusicPlatform.KUWO, night.getSource());
        assertEquals("118980", night.getSourceId());
        assertEquals(118980L, night.getId());
        assertEquals("夜曲", night.getName());
        assertEquals(226_000L, night.getDuration());
        // 上游固定 img: null —— 酷我搜索不给封面，不该有外链封面。
        assertEquals("", night.getCoverUrl(300));
    }

    @Test
    public void missingNMinvoidsWholePageLikeUpstream() {
        JsonArray abslist = new JsonArray();
        JsonObject item = kuwoItem("MUSIC_1", "无档", "某人", "2", "专辑", "200", "");
        abslist.add(item);
        // 上游：任何一条缺 N_MINFO 直接 `return null`，由 search 整页重试。
        assertNull(KuwoMusicService.handleResult(abslist));
    }

    @Test
    public void qualitiesFollowUpstreamBitrateMapAndReverse() {
        // 上游 regExps.mInfo 的 format:(\w) 只吃单个字符 —— 现网酷我 N_MINFO 全是
        // format:flac/ogg/mp3，所以上游对真实数据解出的档位表本来就是空的（.scratch/kw_live.json
        // 实测）。这里连同这个怪癖一起照抄：真实串 → 空；合成单字符格式串 → 走映射。
        assertTrue(KuwoMusicService.qualitiesOf(
                "level:ff,bitrate:2000,format:flac,size:25.45Mb;level:h,bitrate:320,format:mp3,size:8.61Mb")
                .isEmpty());
        List<String> qualities = KuwoMusicService.qualitiesOf(
                "level:zz,bitrate:4000,format:a,size:60.00Mb;level:ff,bitrate:2000,format:f,size:25.45Mb"
                        + ";level:h,bitrate:320,format:m,size:8.61Mb;level:l,bitrate:128,format:m,size:3.62Mb");
        // 4000→flac24bit、2000→flac、320→320k、128→128k，并且 types.reverse() 后低档在前。
        assertEquals(java.util.Arrays.asList("128k", "320k", "flac", "flac24bit"), qualities);
        assertTrue(KuwoMusicService.qualitiesOf("level:x,bitrate:999,format:w,size:1Mb").isEmpty());
    }

    @Test
    public void pickPicUrlKeepsUpstreamHttpPrefixGuard() {
        // 上游 kw/pic.js：/^http/.test(body) ? body : null —— 响应体本身就是直链文本。
        String real = "http://img1.kwcdn.kuwo.cn/star/albumcover/500/s4s11/89/774616642.jpg";
        assertEquals(real, KuwoMusicService.pickPicUrl(real));
        assertEquals(real, KuwoMusicService.pickPicUrl(real + "\n"));
        assertEquals("", KuwoMusicService.pickPicUrl(null));
        assertEquals("", KuwoMusicService.pickPicUrl("<html>error</html>"));
        assertEquals("", KuwoMusicService.pickPicUrl(""));
    }

    private static JsonObject kuwoItem(String musicRid, String songName, String artist,
                                       String albumId, String album, String duration, String nMinfo) {
        JsonObject item = new JsonObject();
        item.addProperty("MUSICRID", musicRid);
        item.addProperty("SONGNAME", songName);
        item.addProperty("ARTIST", artist);
        item.addProperty("ALBUMID", albumId);
        item.addProperty("ALBUM", album);
        item.addProperty("DURATION", duration);
        if (!nMinfo.isEmpty()) item.addProperty("N_MINFO", nMinfo);
        return item;
    }
}
