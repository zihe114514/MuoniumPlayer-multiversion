package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * YouTube 音源的协议回归测试：只验纯逻辑与源码里的协议不变量，不起游戏也不访问网络。
 *
 * <p>钉住的都是 2026-09-23 那轮实测里踩到过或差点踩到的东西（逐条对应
 * 选流档位、歌单/电台的前缀、以及 InnerTube 响应里那几个渲染器的取法。
 * 这些写错时上游往往不报错——它照常回 200，只是回一份空面板或者另一档流。</p>
 */
class YouTubeStaticTest {

    /** 读的是源码本身，所以从 java 根往下按完整包路径定位，别再用 ../ 逐级跳。 */
    private static final Path PKG = ProjectPaths.sharedJava().resolve("com/muoniumplayer");

    private static JsonObject json(String text) {
        JsonElement parsed = JsonParser.parseString(text);
        assertTrue(parsed.isJsonObject(), "测试夹具本身就不是合法 JSON");
        return parsed.getAsJsonObject();
    }

    private static JsonArray array(String text) {
        JsonElement parsed = JsonParser.parseString(text);
        assertTrue(parsed.isJsonArray(), "测试夹具本身就不是合法 JSON 数组");
        return parsed.getAsJsonArray();
    }

    // ════════════════════════════ 登录：SAPISIDHASH ════════════════════════════

    @Test
    @DisplayName("SAPISIDHASH 按 时间戳 + 空格 + sapisid + 空格 + origin 求 sha1")
    void sapisidHashFormula() {
        // 期望值由 python 独立算出，不是从实现里抄回来的：
        //   sha1("1700000000 abc123 https://music.youtube.com")
        assertEquals("SAPISIDHASH 1700000000_597a8411c1c01f9c14cea84b6ec377b76d93be6e",
                YouTubeSessionStore.sapisidHash("abc123", "https://music.youtube.com", 1_700_000_000L));
        // 三段顺序都不能少：少 origin 或与时间戳粘连，Google 一律回 401。
        assertFalse(YouTubeSessionStore.sapisidHash("abc123", "https://music.youtube.com", 1_700_000_000L)
                .equals(YouTubeSessionStore.sapisidHash("abc123", "https://www.youtube.com", 1_700_000_000L)),
                "origin 参与摘要，换 origin 必须换出不同的哈希");
        assertEquals("", YouTubeSessionStore.sapisidHash("", "https://music.youtube.com", 1L),
                "没有 sapisid 时不该编出一个看起来像样的头");
    }

    @Test
    @DisplayName("Cookie 粘贴接受四种常见形态，并都能取到必需那枚")
    void cookieInputIsTolerant() {
        String required = YouTubeSessionStore.REQUIRED_COOKIE;

        Map<String, String> bare = YouTubeSessionStore.parseCookieInput(
                "NID=a=b; " + required + "=SECRETVALLUE; SID=xyz");
        assertEquals("SECRETVALLUE", bare.get(required), "裸的 a=1; b=2 形态");
        assertEquals("a=b", bare.get("NID"), "值里带等号时只能按第一个等号切");

        assertEquals("V", YouTubeSessionStore.parseCookieInput(
                "Cookie: SAPISID=s; " + required + "=V").get(required), "DevTools 里的 Cookie: 前缀");

        assertEquals("V", YouTubeSessionStore.parseCookieInput(
                "document.cookie = \"NID=q; " + required + "=V\"").get(required), "控制台里整行贴过来");

        assertEquals("V", YouTubeSessionStore.parseCookieInput(
                "[{\"name\":\"NID\",\"value\":\"q\"},{\"name\":\"" + required + "\",\"value\":\"V\"}]")
                .get(required), "Cookie 导出插件的数组形态");

        assertEquals("V", YouTubeSessionStore.parseCookieInput(
                required + "=\"V\"").get(required), "值上的引号要去掉，否则哈希永远算不对");
    }

    @Test
    @DisplayName("缺必需 Cookie 的粘贴内容必须解析不成登录态")
    void cookieWithoutSapisidIsRejected() {
        Map<String, String> parsed = YouTubeSessionStore.parseCookieInput("NID=q; PREF=hl=zh-CN");
        assertTrue(parsed.get(YouTubeSessionStore.REQUIRED_COOKIE) == null,
                "没导全就要当成没登录，不能给一个残缺会话去撞 401");
    }

    // ════════════════════════════ 取流：选档 ════════════════════════════

    /** 2026-09-23 实测 ANDROID {@code player} 回来的音频档，原样缩编成夹具。 */
    private static JsonArray measuredAudioFormats() {
        return array("["
                + "{\"itag\":139,\"mimeType\":\"audio/mp4; codecs=\\\"mp4a.40.5\\\"\","
                + "\"bitrate\":50180,\"audioQuality\":\"AUDIO_QUALITY_LOW\",\"url\":\"http://x/139\"},"
                + "{\"itag\":140,\"mimeType\":\"audio/mp4; codecs=\\\"mp4a.40.2\\\"\","
                + "\"bitrate\":130623,\"audioQuality\":\"AUDIO_QUALITY_MEDIUM\",\"url\":\"http://x/140\"},"
                + "{\"itag\":249,\"mimeType\":\"audio/webm; codecs=\\\"opus\\\"\","
                + "\"bitrate\":57224,\"audioQuality\":\"AUDIO_QUALITY_LOW\",\"url\":\"http://x/249\"},"
                + "{\"itag\":251,\"mimeType\":\"audio/webm; codecs=\\\"opus\\\"\","
                + "\"bitrate\":151037,\"audioQuality\":\"AUDIO_QUALITY_MEDIUM\",\"url\":\"http://x/251\"},"
                + "{\"itag\":599,\"mimeType\":\"audio/mp4; codecs=\\\"mp4a.40.5\\\"\","
                + "\"bitrate\":32181,\"audioQuality\":\"AUDIO_QUALITY_ULTRALOW\",\"url\":\"http://x/599\"},"
                + "{\"itag\":18,\"mimeType\":\"video/mp4; codecs=\\\"avc1.42001E, mp4a.40.2\\\"\","
                + "\"bitrate\":314275,\"audioQuality\":\"AUDIO_QUALITY_LOW\",\"url\":\"http://x/18\"}"
                + "]");
    }

    @Test
    @DisplayName("默认档取 itag 140；Opus 与视频流一概不选")
    void losslessPicksMediumAac() {
        JsonObject picked = YouTubeMusicService.selectAudioFormat(
                measuredAudioFormats(), Quality.LOSSLESS);
        assertNotNull(picked, "这组实测数据里明明有可播的 AAC");
        assertEquals(140, picked.get("itag").getAsInt(), "码率最高的 audio/mp4 才是 140");
    }

    @Test
    @DisplayName("服务端没声明长度时退回按音质档：STANDARD 仍不许落到 32 kbps")
    void standardKeepsAFloor() {
        JsonObject picked = YouTubeMusicService.selectAudioFormat(
                measuredAudioFormats(), Quality.STANDARD);
        assertNotNull(picked);
        int itag = picked.get("itag").getAsInt();
        assertFalse(itag == 599,
                "旧实现「取 audio/mp4 里码率最低的」会选到 599=32kbps，那不是标准档而是最低档");
        assertEquals(140, itag, "音质标签 MEDIUM 应该赢过 LOW 与 ULTRALOW");
    }

    /**
     * 同一组实测格式，但每条都带上服务端声明的 contentLength（秒开判定靠它）。
     *
     * <p>这里故意把 {@code codecs} 抹掉：这条测试要单独钉「段数优先」这一条规则，
     * 编码闸的分支由 {@link #heAacNeverSelected} 那组带真实编码的夹具去钉。
     * 真机上 YouTube 是每条都标 {@code codecs} 的，见上面那份 09-24 的表。</p>
     */
    private static JsonArray measuredFormatsWithLength() {
        return array("["
                + "{\"itag\":139,\"mimeType\":\"audio/mp4\",\"bitrate\":50180,"
                + "\"audioQuality\":\"AUDIO_QUALITY_LOW\",\"contentLength\":1900000,\"url\":\"http://x/139\"},"
                + "{\"itag\":140,\"mimeType\":\"audio/mp4\",\"bitrate\":130623,"
                + "\"audioQuality\":\"AUDIO_QUALITY_MEDIUM\",\"contentLength\":4496340,\"url\":\"http://x/140\"},"
                + "{\"itag\":599,\"mimeType\":\"audio/mp4\",\"bitrate\":32181,"
                + "\"audioQuality\":\"AUDIO_QUALITY_ULTRALOW\",\"contentLength\":983040,\"url\":\"http://x/599\"}"
                + "]");
    }

    @Test
    @DisplayName("秒开优先：一段窗口装得下的那条赢，哪怕它码率最低")
    void instantFirstTakesTheSingleWindowStream() {
        // 配额约每 64 秒放行 1 MiB，所以「几段」就是「等几分钟」：140 要 5 段，599 一段就完。
        JsonArray formats = measuredFormatsWithLength();
        assertEquals(599, YouTubeMusicService.selectAudioFormat(
                formats, Quality.LOSSLESS, true).get("itag").getAsInt());
        assertEquals(5, YouTubeMusicService.streamWindows(4496340L));
        assertEquals(1, YouTubeMusicService.streamWindows(983040L));
        // 关掉这条策略就回到「按音质档选」，高码率优先、接受等待。
        assertEquals(140, YouTubeMusicService.selectAudioFormat(
                formats, Quality.LOSSLESS, false).get("itag").getAsInt());
    }

    @Test
    @DisplayName("秒开不等于认最低档：140 也能一段装下时，选码率高的那条")
    void instantFirstStillPrefersBitrateWithinOneWindow() {
        JsonArray shortClip = array("["
                + "{\"itag\":140,\"mimeType\":\"audio/mp4\",\"bitrate\":130623,\"contentLength\":900000,"
                + "\"audioQuality\":\"AUDIO_QUALITY_MEDIUM\",\"url\":\"http://x/140\"},"
                + "{\"itag\":599,\"mimeType\":\"audio/mp4\",\"bitrate\":32181,\"contentLength\":400000,"
                + "\"audioQuality\":\"AUDIO_QUALITY_ULTRALOW\",\"url\":\"http://x/599\"}"
                + "]");
        assertEquals(140, YouTubeMusicService.selectAudioFormat(
                shortClip, Quality.STANDARD, true).get("itag").getAsInt(),
                "都是一段就能拿完，为什么要听 32 kbps");
    }

    @Test
    @DisplayName("服务端没声明长度时不许插队，退回按音质档选")
    void unknownLengthNeverCountsAsFastest() {
        assertEquals(YouTubeMusicService.MAX_STREAM_WINDOWS, YouTubeMusicService.streamWindows(0L));
        assertEquals(YouTubeMusicService.MAX_STREAM_WINDOWS, YouTubeMusicService.streamWindows(-5L));
        assertEquals(2, YouTubeMusicService.streamWindows(1024 * 1024 + 1));
        // 没有 contentLength 的那组实测格式：全部并列成「最慢」，于是码率说话。
        assertEquals(140, YouTubeMusicService.selectAudioFormat(
                measuredAudioFormats(), Quality.STANDARD, true).get("itag").getAsInt());
    }

    @Test
    @DisplayName("ULTRALOW 的排序必须低于 LOW")
    void ultraLowRanksBelowLow() {
        // 子串陷阱："AUDIO_QUALITY_ULTRALOW" 里也含 "LOW"，先判 LOW 就会把最低档当成低档。
        int ultraLow = YouTubeMusicService.audioQualityRank(
                json("{\"audioQuality\":\"AUDIO_QUALITY_ULTRALOW\",\"bitrate\":32181}"));
        int low = YouTubeMusicService.audioQualityRank(
                json("{\"audioQuality\":\"AUDIO_QUALITY_LOW\",\"bitrate\":50180}"));
        int medium = YouTubeMusicService.audioQualityRank(
                json("{\"audioQuality\":\"AUDIO_QUALITY_MEDIUM\",\"bitrate\":130623}"));
        assertTrue(ultraLow < low, "ULTRALOW 必须低于 LOW");
        assertTrue(low < medium, "LOW 必须低于 MEDIUM");
    }

    @Test
    @DisplayName("只剩 Opus 流时宁可不播，也不给一个解不了的容器")
    void refusesWebmOnly() {
        JsonArray onlyOpus = array("[{\"itag\":251,\"mimeType\":\"audio/webm; codecs=\\\"opus\\\"\","
                + "\"bitrate\":151037,\"url\":\"http://x/251\"}]");
        assertEquals(null, YouTubeMusicService.selectAudioFormat(onlyOpus, Quality.LOSSLESS),
                "宿主管线没有 WebM demuxer，给出去只会变成一个下载后放不出来的文件");
    }

    /**
     * 2026-09-24 实测 {@code KLVq0IAzh1A}（Sting – Fields Of Gold，218 秒）的完整音频表，
     * 连 {@code codecs} 与 {@code contentLength} 一起照抄 —— 这一组是「无法播放」那个 bug 的现场。
     */
    private static JsonArray measuredFormsWithCodecsAndLength() {
        return array("["
                + "{\"itag\":139,\"mimeType\":\"audio/mp4; codecs=\\\"mp4a.40.5\\\"\","
                + "\"bitrate\":50063,\"audioQuality\":\"AUDIO_QUALITY_LOW\","
                + "\"contentLength\":1329292,\"url\":\"http://x/139\"},"
                + "{\"itag\":140,\"mimeType\":\"audio/mp4; codecs=\\\"mp4a.40.2\\\"\","
                + "\"bitrate\":130639,\"audioQuality\":\"AUDIO_QUALITY_MEDIUM\","
                + "\"contentLength\":3524977,\"url\":\"http://x/140\"},"
                + "{\"itag\":249,\"mimeType\":\"audio/webm; codecs=\\\"opus\\\"\","
                + "\"bitrate\":58694,\"audioQuality\":\"AUDIO_QUALITY_LOW\","
                + "\"contentLength\":1424452,\"url\":\"http://x/249\"},"
                + "{\"itag\":251,\"mimeType\":\"audio/webm; codecs=\\\"opus\\\"\","
                + "\"bitrate\":148392,\"audioQuality\":\"AUDIO_QUALITY_MEDIUM\","
                + "\"contentLength\":3734053,\"url\":\"http://x/251\"},"
                + "{\"itag\":599,\"mimeType\":\"audio/mp4; codecs=\\\"mp4a.40.5\\\"\","
                + "\"bitrate\":32132,\"audioQuality\":\"AUDIO_QUALITY_ULTRALOW\","
                + "\"contentLength\":839415,\"url\":\"http://x/599\"},"
                + "{\"itag\":600,\"mimeType\":\"audio/webm; codecs=\\\"opus\\\"\","
                + "\"bitrate\":39480,\"audioQuality\":\"AUDIO_QUALITY_ULTRALOW\","
                + "\"contentLength\":963335,\"url\":\"http://x/600\"}"
                + "]");
    }

    @Test
    @DisplayName("HE-AAC 一条都不许选：秒开优先曾把三档音质全送到解不了的编码上")
    void heAacNeverSelected() {
        /*
         * 日志里的「FIL element overread: -756」就是这么来的：秒开优先只比「几段窗口取得完」，
         * 而 YouTube 上最短的恰好是 599（839 415 字节，一段就装下）与 139 —— 两条都是
         * mp4a.40.5。实测取满字节后生产解码器对 599 报 -756、对 139 报 -700，对 140 能解出
         * 43 MB WAV，截断文件报的却是「moof has no following mdat」（所以不是取流取坏了）。
         */
        for (Quality quality : new Quality[] {Quality.STANDARD, Quality.HIGHER, Quality.LOSSLESS}) {
            assertEquals(140, YouTubeMusicService.selectAudioFormat(
                    measuredFormsWithCodecsAndLength(), quality, true).get("itag").getAsInt(),
                    quality + " 档在秒开策略下也必须落到宿主解得了的那条");
            assertEquals(140, YouTubeMusicService.selectAudioFormat(
                    measuredFormsWithCodecsAndLength(), quality, false).get("itag").getAsInt(),
                    quality + " 档在按音质档选下同样只能是 140");
        }
    }

    @Test
    @DisplayName("codecs 从 mimeType 里摘：带引号、只写档名、缺失都算对")
    void codecsParameterParsing() {
        assertEquals("mp4a.40.2", YouTubeMusicService.codecsOf(
                json("{\"mimeType\":\"audio/mp4; codecs=\\\"mp4a.40.2\\\"\"}")));
        assertEquals("mp4a.40.5", YouTubeMusicService.codecsOf(
                json("{\"mimeType\":\"audio/mp4; codecs=mp4a.40.5\"}")), "引号不是必需的");
        assertEquals("", YouTubeMusicService.codecsOf(json("{\"mimeType\":\"audio/mp4\"}")),
                "有的客户端只给容器不给编码");
        // 编码闸本身：LC 放行，两个 HE 档拦下，未知（没有 codecs）按历史上一直在播处理。
        assertTrue(YouTubeMusicService.isDecodableAudioFormat(
                json("{\"mimeType\":\"audio/mp4; codecs=\\\"mp4a.40.2\\\"\"}")));
        assertFalse(YouTubeMusicService.isDecodableAudioFormat(
                json("{\"mimeType\":\"audio/mp4; codecs=\\\"mp4a.40.5\\\"\"}")), "HE-AAC v1");
        assertFalse(YouTubeMusicService.isDecodableAudioFormat(
                json("{\"mimeType\":\"audio/mp4; codecs=\\\"mp4a.40.29\\\"\"}")), "HE-AAC v2");
        assertTrue(YouTubeMusicService.isDecodableAudioFormat(json("{\"mimeType\":\"audio/mp4\"}")),
                "服务端没标编码时不该由这条闸拦下来");
    }

    @Test
    @DisplayName("整首歌只有 HE-AAC 时返回 null，让跨源换轨去别家拿")
    void refusesHeAacOnly() {
        JsonArray onlyHe = array("["
                + "{\"itag\":139,\"mimeType\":\"audio/mp4; codecs=\\\"mp4a.40.5\\\"\","
                + "\"bitrate\":50063,\"contentLength\":1329292,\"url\":\"http://x/139\"},"
                + "{\"itag\":599,\"mimeType\":\"audio/mp4; codecs=\\\"mp4a.40.5\\\"\","
                + "\"bitrate\":32132,\"contentLength\":839415,\"url\":\"http://x/599\"}"
                + "]");
        assertEquals(null, YouTubeMusicService.selectAudioFormat(onlyHe, Quality.HIGHER, true),
                "下载完再解码炸掉 = 白等一场还白扣字节余额，不如现在就判「这个源放不出来」");
    }

    // ════════════════════════════ 歌单与电台 ════════════════════════════

    @Test
    @DisplayName("歌单 id 能从链接、查询串与裸 id 里挑出来，且去掉 VL 前缀")
    void playlistIdExtraction() {
        assertEquals("PLcjYWKSdHkHXOkRnMwEKGiaLZEFetloK0", YouTubeMusicService.normalizePlaylistId(
                "看看这个 https://music.youtube.com/playlist?list=PLcjYWKSdHkHXOkRnMwEKGiaLZEFetloK0 分享"));
        assertEquals("RDCLAK5uy_mbdO3", YouTubeMusicService.normalizePlaylistId(
                "https://www.youtube.com/watch?v=bx1Bh8ZvH84&list=RDCLAK5uy_mbdO3&index=1"));
        assertEquals("PLabc123def456", YouTubeMusicService.normalizePlaylistId("VLPLabc123def456"),
                "已经带 VL 的要先剥掉，browse 时统一再加");
        assertEquals("PLabc123def456", YouTubeMusicService.normalizePlaylistId("PLabc123def456"));
        assertEquals("", YouTubeMusicService.normalizePlaylistId("https://music.youtube.com/"),
                "首页链接里没有歌单编号，必须回空串让调用方去问视频 id");
    }

    @Test
    @DisplayName("视频 id 支持 watch?v=、youtu.be 短链与裸 id")
    void videoIdExtraction() {
        assertEquals("bx1Bh8ZvH84", YouTubeMusicService.normalizeVideoId(
                "https://www.youtube.com/watch?v=bx1Bh8ZvH84&t=42"));
        assertEquals("bx1Bh8ZvH84", YouTubeMusicService.normalizeVideoId("https://youtu.be/bx1Bh8ZvH84"));
        assertEquals("bx1Bh8ZvH84", YouTubeMusicService.normalizeVideoId("bx1Bh8ZvH84"));
        assertEquals("", YouTubeMusicService.normalizeVideoId("不是链接也不是 id"));
    }

    @Test
    @DisplayName("歌单行解析：标题 / 艺人 / 时长三列各归各位")
    void playlistRowParsing() {
        JsonObject row = json("{"
                + "\"playlistItemData\":{\"videoId\":\"hpSrLjc5SMs\"},"
                + "\"flexColumns\":["
                + "{\"musicResponsiveListItemFlexColumnRenderer\":{\"text\":{\"runs\":[{\"text\":\"Wonderwall (Remastered)\"}]}}},"
                + "{\"musicResponsiveListItemFlexColumnRenderer\":{\"text\":{\"runs\":[{\"text\":\"绿洲乐队\"},{\"text\":\" - \"},{\"text\":\"主题\"}]}}},"
                + "{\"musicResponsiveListItemFlexColumnRenderer\":{\"text\":{\"runs\":[{\"text\":\"4:19\"}]}}}"
                + "]}");
        YouTubeMusicService.Track track = YouTubeMusicService.parseListItem(row);
        assertNotNull(track, "一条正常的歌单行不该解析成 null");
        assertEquals("hpSrLjc5SMs", track.videoId);
        assertEquals("Wonderwall (Remastered)", track.title);
        assertEquals(259_000L, track.durationMillis, "4:19 应该是 259 秒");
        assertEquals("绿洲乐队", track.artist, "YouTube 的频道列会挂「 - 主题」，得剥掉");
        assertTrue(track.coverUrl.startsWith("http"), "缺封面时也要有 i.ytimg 兜底");
    }

    @Test
    @DisplayName("continuation token 要认 BROWSE 那一种")
    void continuationTokenParsing() {
        JsonObject response = json("{\"contents\":{\"continuationItemRenderer\":"
                + "{\"continuationEndpoint\":{\"continuationCommand\":{\"token\":\"ABC%3D\","
                + "\"request\":\"CONTINUATION_REQUEST_TYPE_BROWSE\"}}}}}");
        assertEquals("ABC%3D", YouTubeMusicService.findContinuationToken(response),
                "翻不了页就等于超过 100 首的歌单会静默缺尾");
    }

    @Test
    @DisplayName("歌词时间戳格式是 LRC 的 mm:ss.SSS")
    void lrcTimestamp() {
        assertEquals("00:00.000", YouTubeMusicService.timestamp(0L));
        assertEquals("00:09.200", YouTubeMusicService.timestamp(9_200L));
        assertEquals("04:19.750", YouTubeMusicService.timestamp(259_750L));
        assertEquals("60:00.000", YouTubeMusicService.timestamp(3_600_000L), "超一小时的现场版不该回头归零");
        assertEquals("00:00.000", YouTubeMusicService.timestamp(-5L), "负数要夹住，别生成 [-1] 这种非法行");
    }

    @Test
    @DisplayName("timedLyricsData 转成逐行 LRC")
    void timedLyricsBecomeLrc() {
        JsonObject response = json(
                "{\"contents\":{\"elementRenderer\":{\"newElement\":{\"type\":{\"componentType\":{\"model\":{\"timedLyricsModel\":{"
                + "\"lyricsData\":{\"sourceMessage\":\"Source: LyricFind\",\"timedLyricsData\":[{\"lyricLine\":\"I was a liar\",\"cu"
                + "eRange\":{\"startTimeMilliseconds\":\"9200\",\"endTimeMilliseconds\":\"10630\",\"metadata\":{\"id\":\"1\"}}},{\"lyri"
                + "cLine\":\"I gave in to the fire\",\"cueRange\":{\"startTimeMilliseconds\":\"10680\",\"endTimeMilliseconds\":\"12"
                + "540\",\"metadata\":{\"id\":\"2\"}}}]}}}}}}}}}");

        String lrc = YouTubeMusicService.buildLrc(response);
        assertEquals("[00:09.200]I was a liar\n[00:10.680]I gave in to the fire", lrc);

        JsonObject empty = json("{\"contents\":{\"singleColumnBrowseResultsRenderer\":{}}}");
        assertEquals("", YouTubeMusicService.buildLrc(empty),
                "匿名态拿到的就是这种空面板，绝不能编出一份「看起来有词」的结果");
    }

    @Test
    @DisplayName("歌单自报的曲目数从 '50 首歌曲' 这类文本里取")
    void declaredCountParsing() {
        assertEquals(50, YouTubeMusicService.digitsOf("50 首歌曲"));
        assertEquals(1234, YouTubeMusicService.digitsOf("1,234 songs"));
        assertEquals(0, YouTubeMusicService.digitsOf("公开歌单"), "没有数字时是 0，不是猜一个");
    }

    // ════════════════════════════ 源码里的协议不变量 ════════════════════════════

    @Test
    @DisplayName("两端客户端分工不许被「统一一下」掉")
    void twoClientProfilesSurvive() throws Exception {
        String source = read("core/ncm/music/YouTubeMusicService.java");
        assertTrue(source.contains("https://www.youtube.com/youtubei/v1/"),
                "www 那一档不能删：取流与电台只有它给得出内容");
        assertTrue(source.contains("https://music.youtube.com/youtubei/v1/"),
                "music 那一档不能删：歌词 tab 与歌单详情只有它给");
        assertTrue(source.contains("ANDROID_MUSIC"), "逐字歌词依赖移动端音乐客户端");
        assertTrue(source.contains("RDAMVM"), "电台种子前缀");
        assertTrue(source.contains("\"VL\"") || source.contains("PLAYLIST_PREFIX + id"),
                "歌单 browse 要补 VL 前缀");
        assertTrue(source.contains("audio/mp4"), "只选宿主解得了的容器");
    }

    @Test
    @DisplayName("未登录时不许白打歌词那两跳")
    void lyricsSkipAnonymously() throws Exception {
        String source = read("core/ncm/music/LyricLoadService.java");
        int at = source.indexOf("youTubeLyrics");
        assertTrue(at > 0, "YouTube 官方歌词没接进歌词链路");
        assertTrue(source.contains("if (!YouTubeSessionStore.isLoggedIn()) return null"),
                "匿名实测一律回「没有歌词」，所以未登录就该直接跳过，不要占用户一次请求");
    }

    @Test
    @DisplayName("YouTube 的账号能力只走 Cookie，不走扫码")
    void cookieIsTheOnlyLogin() throws Exception {
        String dispatch = read("core/ncm/music/NeteaseMusicService.java");
        assertTrue(dispatch.contains("YouTubeSessionStore.isLoggedIn()"),
                "账号分派里 YouTube 必须看真实登录态，别再写死 return false");
        assertTrue(dispatch.contains("YouTube Music 用粘贴 Cookie 登录"),
                "扫码入口要指出正确的那条路，而不是回一句「不需要登录」骗人");
        String overlay = read("core/screens/ncm/MusicSourceOverlay.java");
        assertTrue(overlay.contains("loginWithCookie"), "界面上得真有点得到这个入口");
    }

    private static String read(String relative) throws Exception {
        Path path = PKG.resolve(relative);
        assertTrue(Files.exists(path), "找不到被测源码：" + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("电台与歌单都归一成同一套曲目对象，界面不用再分叉")
    void tracksShareOneShape() {
        YouTubeMusicService.Track track = new YouTubeMusicService.Track(
                "bx1Bh8ZvH84", "Oasis - Wonderwall", "Oasis", 259_000L, "http://x/hq.jpg");
        assertNotNull(com.muoniumplayer.core.ncm.music.dto.Music.fromYouTubeMusicTrack(track));
        assertEquals("bx1Bh8ZvH84",
                com.muoniumplayer.core.ncm.music.dto.Music.fromYouTubeMusicTrack(track).getSourceId(),
                "视频 id 就是源 id，缓存与播放历史都靠它命中");
    }

    // ════════════════════════ 取流：googlevideo 的字节窗口 ════════════════════════

    @Test
    @DisplayName("取字节的时间常数钉在实测值上")
    void streamWindowMatchesMeasurement() {
        // 1 MiB 是「一个时间单位」而不是「单发上限」：实测一发 4 MiB 的要法当场给了整首
        // 1 631 333 字节、2.6 秒走完。所以这里只钉节奏相关的常数。
        assertEquals(1024 * 1024, YouTubeMusicService.STREAM_WINDOW_BYTES);
        assertEquals(512, YouTubeMusicService.MAX_STREAM_WINDOWS);
        // 配额按实时回血（1 MiB / ~64 秒 ≈ itag 140 的 16.3 KB/s），所以被拒之后要等而不是猛重试。
        assertEquals(20_000L, YouTubeMusicService.WINDOW_RETRY_DELAY_MS);
        assertEquals(9, YouTubeMusicService.MAX_WINDOW_WAITS);
        assertTrue(YouTubeMusicService.MAX_STREAM_WAIT_MS <= 4 * 60_000L,
                "整首的等待上限不能超过 4 分钟：再久就该让跨源换轨接管，而不是让用户干等");
    }

    @Test
    @DisplayName("顶档一次要完剩下的，被拒才逐档缩小")
    void windowLadderStartsBigAndShrinksBeforeItWaits() {
        // 实测一个从没请求过的视频：1 MiB 被拒时 64 KiB 与 256 KiB 当场 206 —— 卡的是余额，
        // 不是 request 形状。所以顶档直接要「剩下的全部」，把空闲后的一发拿满；被拒再逐档缩。
        assertEquals(Long.MAX_VALUE, YouTubeMusicService.windowSpanFor(0));
        assertEquals(4L * 1024 * 1024, YouTubeMusicService.windowSpanFor(1));
        assertEquals(1024L * 1024, YouTubeMusicService.windowSpanFor(2));
        assertEquals(262144L, YouTubeMusicService.windowSpanFor(3));
        assertEquals(65536L, YouTubeMusicService.windowSpanFor(4));
        assertEquals(65536L, YouTubeMusicService.windowSpanFor(99),
                "档位走完要停在最小档，不要越界");
        assertEquals(Long.MAX_VALUE, YouTubeMusicService.windowSpanFor(-3),
                "负数档位是调用方写错，按最大档处理而不是抛出来把播放线程炸掉");
    }

    @Test
    @DisplayName("段的右端点不能越过总长，也不能比起点还小")
    void windowHighStaysInsideTheTrack() {
        // 顶档：整首一发（今晚实测到的那条）
        assertEquals(1631332L, YouTubeMusicService.windowHighFor(0L, 1631333L, 0));
        assertEquals(4194303L, YouTubeMusicService.windowHighFor(0L, 4496340L, 1));
        assertEquals(1048575L, YouTubeMusicService.windowHighFor(0L, 4496340L, 2));
        assertEquals(262143L, YouTubeMusicService.windowHighFor(0L, 4496340L, 3));
        // 最后一段只剩 10 字节时，high 必须正好落在 total-1，多发一字节就是整发 403。
        assertEquals(4496339L, YouTubeMusicService.windowHighFor(4496330L, 4496340L, 0));
        // 顶档也有天花板：一次交换不收超过 32 MiB 的东西（防止 200 回整首时把堆撑爆）。
        assertEquals(32L * 1024 * 1024 - 1L,
                YouTubeMusicService.windowHighFor(0L, 40L * 1024 * 1024, 0));
        // 服务端没声明长度（total = 0）时按一个窗口要，而不是发出无界请求。
        assertEquals(1048575L, YouTubeMusicService.windowHighFor(0L, 0L, 0));
        assertEquals(1048576L, YouTubeMusicService.windowHighFor(1048576L, 1048577L, 9));
    }

    @Test
    @DisplayName("Content-Range 两个数都要读对，读错就把进度和停止条件一起搞坏")
    void contentRangeHeaderParsing() {
        assertEquals(4524128L, YouTubeMusicService.contentRangeTotal("bytes 0-1048575/4524128"));
        assertEquals(1048576L, YouTubeMusicService.contentRangeLength("bytes 0-1048575/4524128"));
        assertEquals(262144L, YouTubeMusicService.contentRangeLength("bytes 1048576-1310719/4657929"));
        assertEquals(0L, YouTubeMusicService.contentRangeTotal("*"));
        assertEquals(0L, YouTubeMusicService.contentRangeLength(""));
        assertEquals(0L, YouTubeMusicService.contentRangeLength(null));
    }

    @Test
    @DisplayName("available() 必须是「总长度」：进度分母读的就是它")
    void availableIsDeclaredTotalNotWindowBuffer() {
        // WrappedInputStream 的百分比 = 已读 / in.available()，HttpUtils 那条链路早就把
        // available() 定义成响应声明的总长度。分片流要是回当前窗口的缓冲量，第一口就读出大于 1。
        assertEquals(3990918, YouTubeMusicService.availableTotal(3990918L));
        assertEquals(0, YouTubeMusicService.availableTotal(0L));
        assertEquals(0, YouTubeMusicService.availableTotal(-1L));
        assertEquals(Integer.MAX_VALUE, YouTubeMusicService.availableTotal(Long.MAX_VALUE));
    }

    @Test
    @DisplayName("取字节不许再用「流式 body」：那条路没有响应体超时")
    void audioExchangeMustBeBounded() throws Exception {
        // 只看代码行：解释这件事的注释里当然会出现那个方法名，不然这条断言会被自己的文档绊倒。
        StringBuilder code = new StringBuilder();
        for (String line : read("core/ncm/music/YouTubeMusicService.java").split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) continue;
            code.append(line).append('\n');
        }
        String source = code.toString();
        assertFalse(source.contains("BodyHandlers.ofInputStream"),
                "ofInputStream 让 HttpRequest.timeout 只管到响应头，body 能无限期挂住播放线程"
                        + "（2026-09-23 实测换段那次挂了 388 秒，jstack 才看出来）");
        assertTrue(source.contains("BodyHandlers.ofByteArray"), "交换要整体收，截止才覆盖得到响应体");
        assertTrue(source.contains("future.cancel(true)"), "超时之后得把那次交换摘掉，不能留着那条连接");
        assertTrue(source.contains("sendAsync"), "整体收 + 可取消要靠异步那条 API");
    }

    @Test
    @DisplayName("分片要用的 itag 与 clen 就在链接的查询串里")
    void urlParamsCarryItagAndLength() {
        String url = "https://rr4---sn-a5msen7s.googlevideo.com/videoplayback?"
                + "expire=1790120615&ip=198.2.237.16&id=o-ABdPs&itag=140&source=youtube"
                + "&cps=828&mime=audio%2Fmp4&clen=4496340&dur=277.849";
        assertEquals("140", YouTubeMusicService.queryParam(url, "itag"));
        assertEquals("4496340", YouTubeMusicService.queryParam(url, "clen"));
        assertEquals("", YouTubeMusicService.queryParam(url, "nope"));
        assertEquals("", YouTubeMusicService.queryParam("https://x/y", "itag"));
    }

    @Test
    @DisplayName("开流缺 videoId 或缺链接就直接拒，不要半路才炸")
    void openStreamNeedsBothArgs() {
        assertThrows(IOException.class, () -> YouTubeMusicService.openStream(null, "http://x"));
        assertThrows(IOException.class, () -> YouTubeMusicService.openStream("  ", "http://x"));
        assertThrows(IOException.class, () -> YouTubeMusicService.openStream("bx1Bh8ZvH84", "  "));
        assertThrows(IOException.class, () -> YouTubeMusicService.openStream("bx1Bh8ZvH84",
                (YouTubeMusicService.ResolveResult) null));
    }

    @Test
    @DisplayName("ANDROID 那一档不许再发出没有 context 的请求")
    void androidPostBackfillsContext() throws Exception {
        String source = read("core/ncm/music/YouTubeMusicService.java");
        assertTrue(source.contains("if (!body.has(\"context\")) body.add(\"context\", clientContext());"),
                "电台那跳实测就是漏了 context 被回 HTTP 400 failedPrecondition，"
                        + "post() 里的兜底不能删");
    }

    @Test
    @DisplayName("YouTube 曲目的下载走分段流，Range 只有一处写")
    void youtubeDownloadUsesChunkedStream() throws Exception {
        String transport = read("core/ncm/music/MusicDownloadService.java");
        assertTrue(transport.contains("YouTubeMusicService.openStream(song.getSourceId(), playUrl)"),
                "YouTube 曲目必须走分段流，否则裸 GET 直接 403");
        assertTrue(transport.contains("song != null && song.isYouTube()"),
                "分段只针对 YouTube，别的源保持原来的单次 GET");
        String entry = read("core/ncm/music/CloudMusic.java");
        assertTrue(entry.contains("YouTubeProxySettings.createHttpProxy() : null, song)"),
                "分派处要把曲目本身交给下载实现，不然它拿不到 videoId");
        // Range 由分段逻辑独占：补头那处再写一枚就会和它互相覆盖。
        String headers = read("core/ncm/music/AudioStreamHeaders.java");
        assertFalse(headers.contains("\"Range\""), "Range 只能在 YouTubeMusicService 一处生成");
    }

    // ═══════════════════ 认证：Android 客户端要按 www 站签 ═══════════════════

    @Test
    @DisplayName("同一个 sapisid，www 与 music 两个 origin 签出两个值")
    void originIsPartOfTheSignature() {
        assertEquals("SAPISIDHASH 1700000000_9e5071f149fc514366f78b22d1a169786d40ed32",
                YouTubeSessionStore.sapisidHash("abc123", "https://www.youtube.com", 1_700_000_000L));
        assertEquals("SAPISIDHASH 1700000000_597a8411c1c01f9c14cea84b6ec377b76d93be6e",
                YouTubeSessionStore.sapisidHash("abc123", "https://music.youtube.com", 1_700_000_000L));
        assertEquals("https://www.youtube.com", YouTubeSessionStore.MOBILE_ORIGIN);
    }

    @Test
    @DisplayName("ANDROID_MUSIC 的认证头不许按 music 站签（实测两跳全 400）")
    void mobileMusicClientUsesWwwOrigin() throws Exception {
        String source = read("core/ncm/music/YouTubeMusicService.java");
        assertTrue(source.contains("YouTubeSessionStore.MOBILE_ORIGIN"),
                "移动端音乐客户端要换成 www origin，否则登录态下歌词那两跳直接 400 INVALID_ARGUMENT");
        assertTrue(source.contains("MOBILE_MUSIC_CLIENT_NAME.equals(clientName)"),
                "按客户端选 origin，别一刀切成同一个");
    }
}
