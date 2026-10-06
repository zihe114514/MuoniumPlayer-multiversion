package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonObject;
import com.muoniumplayer.party.MpRoomPrefetch;
import com.muoniumplayer.party.MpTrack;
import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 房间队列提前下载（{@code MpRoomPrefetch} 加 {@code CloudMusic} 那两个公开静态入口）与
 * automix 单曲 profile 预热的离线回归。
 *
 * <p>钉住的全是「退化之后没有任何现象」的那几件事：</p>
 * <ul>
 *   <li>提前下载与实播<b>共用同一条落盘序列</b>。缓存键是「曲目稳定键 + 音质」，只要有第二处
 *       自己拼一遍，预下载就永远命中不上实播 —— 不报错、不打日志，只是每首歌都下两遍。</li>
 *   <li>没有直链的条目必须被跳过，预热路径绝不出现取流解析。替房间解析一条没人授权的流是
 *       「代解析」那条链路的权力，不是预热的。</li>
 *   <li>预下载不许挤 {@code BackgroundWork}（那条池是 automix 预备的生命线），也不许用无界的
 *       cached pool（一次刷新把十几条并发拉满就是打爆上行）。</li>
 *   <li>容量与磁盘闸门、in-flight 去重、淘汰置顶、模型齐 + 引擎开着才预热 —— 缺哪一条，症状
 *       都只是「偶尔还是硬切」或者「磁盘被写满」，事后查不到原因。</li>
 * </ul>
 *
 * <p>不启动 Minecraft、音频设备或网络。真跑起来的只有 {@link MpRoomPrefetch} 里那几个纯函数，
 * 其余按源码文本钉住；扫源码时一律先去注释、再把 CRLF 归一成 LF，因为共享源树里两种换行混着放。
 * 注释里的散文会提到 {@code BackgroundWork} 这类「代码里不许出现」的名字，所以先剥注释再断言。</p>
 */
class RoomPrefetchStaticTest {

    private static final Path JAVA = ProjectPaths.sharedJava();
    private static final Path PARTY = ProjectPaths.versionRoot().getParent()
            .resolve(Paths.get("mpparty-common", "src", "main", "java", "com", "muoniumplayer", "party"));

    // ════════════════════════ 一条序列，一个缓存键 ════════════════════════

    @Test
    @DisplayName("提前下载与实播共用唯一那一条落盘序列，缓存键全文件只算一次")
    void prefetchSharesTheOneAndOnlyCacheKeyImplementation() throws IOException {
        String source = cloudMusic();
        String sequence = region(source,
                "static File cachedAudioFile(String playUrl",
                "static File resolvePlayableAudioFile(");
        String delegate = region(source,
                "private File getCachedOrTempFile(",
                "static File cachedAudioFile(String playUrl");

        // 「audioCacheKey(」= 一处声明 + 一处调用，那一处调用必须就在共用的那条序列里。
        assertEquals(2, count(source, "audioCacheKey("),
                "缓存键的声明与调用合计只能两处；出现第三处就是有人又拼了一遍键，预下载将永远命中不上");
        assertEquals(1, count(sequence, "audioCacheKey(song, quality.getQuality())"),
                "唯一的调用点必须在共用的落盘序列里");
        assertEquals(0, count(delegate, "audioCacheKey("), "委托方法不碰键");

        // 音质是传进来的参数，不是就地读的全局：整条序列自始至终只有调用方取走的那一档。
        assertTrue(sequence.contains(
                        "static File cachedAudioFile(String playUrl, String type, Music song, Quality quality,"),
                "共用序列必须显式收音质，否则提前下载与实播可能各按一档算键");

        // 磁盘查找同理：只许出现在那一条序列里。
        assertEquals(1, count(source, "findCachedAudioFile("), "查盘入口只能有一份");
        assertTrue(sequence.contains("findCachedAudioFile(musicCacheDir, cacheKey, type)"),
                "查盘必须用序列里算出来的那个 cacheKey");

        // 实播的实例方法只剩委托：不下载、不碰缓存账本，只多做 AAC→WAV 那一步解析。
        assertTrue(delegate.contains("cachedAudioFile(playUrl, type, song, quality, true)"),
                "getCachedOrTempFile 必须只是委托：共用的序列变了，这里要第一时间跟着变");
        assertTrue(delegate.contains("resolvePlayableAudioFile("), "实播那一条路仍然要解析成可播文件");
        assertFalse(delegate.contains("downloadMusic("), "委托里不许留下第二份下载");
        assertFalse(delegate.contains("MusicAudioCache."), "委托里不许留下第二份缓存账本");

        // 那一条序列本身：必须复用的六件事一件都不能少。
        assertTrue(sequence.contains("downloadMusic(playUrl, temporaryMusic, song)"), "少了下载就不是同一条序列");
        assertTrue(sequence.contains("cacheKey + \".download\""),
                "半成品必须走 .download，别把下载了一半的文件暴露给解码器");
        assertTrue(sequence.contains("QishuiMusicService.decryptDownloadedAudio"),
                "汽水 DRM 解密必须在同一条序列里");
        assertTrue(sequence.contains("String actualType = AudioContainerSupport.detectContainer(temporaryMusic)"),
                "落盘文件名必须按真实字节探测容器，而不是按上报的类型");
        assertTrue(sequence.contains("removeOtherQualityCaches(musicCacheDir, song, quality.getQuality())"),
                "同一首其余音质的清理必须跟着走，而且用的就是调用方传进来的那一档");
        assertTrue(sequence.contains("MusicAudioCache.touch(cachedMusic)"),
                "命中也要记账，否则常听但下载得早的歌会被当成最冷的删掉");
        assertTrue(sequence.contains("MusicAudioCache.touch(music)"), "下完也要记账");
        assertTrue(sequence.contains("MusicAudioCache.enforceLimitAsync()"), "容量兜底必须挂在同一条序列上");
        assertFalse(sequence.contains("resolvePlayableAudioFile("),
                "共用序列不做转码：那是「能不能出声」的一步，提前下载没有资格顺手解码");

        // 两个公开入口都调这一条序列，只是 downloadIfMissing 那一张脸不同。
        String prefetch = region(source, "public static boolean prefetchRoomTrackAudio(",
                "public static File cachedAudioFileFor(");
        String probe = region(source, "public static File cachedAudioFileFor(",
                "public static long audioCacheUsedBytes(");
        assertTrue(prefetch.contains("song, selected, true)"), "提前下载走 downloadIfMissing = true 那一张脸");
        assertTrue(probe.contains("PlayThread.cachedAudioFile(null, null, song, quality, false)"),
                "探测走 downloadIfMissing = false：只问磁盘，且复用同一个键实现，不另拼一份");
        assertFalse(prefetch.contains("getPlayUrl("), "提前下载绝不解析：直链由调用方给");
        assertTrue(prefetch.contains("playUrl.trim().isEmpty()"), "没有可用直链就地拒绝，不去换链接");
    }

    // ══════════════════════════════ 两个上限 ══════════════════════════════

    @Test
    @DisplayName("房间预热上限 10 首、profile 预热上限 5 首：写死在常量里，并且真的用在挑人那一步")
    void bothCapsAreHardcodedConstants() throws IOException {
        String prefetch = codeOf(roomPrefetch());

        assertEquals(10, integerLiteral(prefetch, "PREFETCH_LIMIT = "),
                "当前曲之后最多提前下载几首：10 是定的，再多就是拿别人的上行去赌命中率");
        assertEquals(5, integerLiteral(prefetch, "PROFILE_LIMIT = "),
                "最多预热几首的模型 profile：5 是定的，一次 Python 要 120–180 秒");
        assertEquals(2, integerLiteral(prefetch, "DOWNLOAD_CONCURRENCY = "),
                "预下载并发恒为 2，且必须显式写死");
        assertEquals(1, count(prefetch, "PREFETCH_LIMIT = "), "上限只声明一次");
        assertEquals(1, count(prefetch, "PROFILE_LIMIT = "), "上限只声明一次");

        assertTrue(prefetch.contains("PREFETCH_LIMIT, System.currentTimeMillis()"),
                "挑人那一步要按 PREFETCH_LIMIT 截断，常量不能只是装饰");
        assertTrue(prefetch.contains("i < PROFILE_LIMIT"), "profile 预热要按 PROFILE_LIMIT 截断");
    }

    // ══════════════════════ 不解析、不挤池、不进渲染帧 ══════════════════════

    @Test
    @DisplayName("没有直链的条目被跳过；预热不解析、不占 BackgroundWork、不在主线程跑")
    void entriesWithoutUsableStreamUrlAreSkippedAndNothingGetsResolved() throws IOException {
        String prefetch = codeOf(roomPrefetch());

        assertFalse(prefetch.contains("getPlayUrl("),
                "预热路径出现 getPlayUrl 就是拿本机账号替房间解析：那是代解析那条链路的权力");
        assertFalse(prefetch.contains("resolveLxPlayUrl"), "同上，LX 自定义源也不能被预热惊动");
        assertFalse(prefetch.contains("MpNetease"), "预热不许自己查网易云要链接");
        assertFalse(prefetch.contains("songById"), "预热不许回平台重查曲目");
        assertFalse(prefetch.contains("BackgroundWork"),
                "预下载不许进 BackgroundWork：那条池只有 1–2 条线程，是 automix 预备的生命线");
        assertFalse(prefetch.contains("MultiThreadingUtil"),
                "runAsync 背后是无界 cached pool，十几首一起下会把上行和 CDN 连接一起打爆");
        assertFalse(prefetch.contains("runOnMainThread"), "预热一个回调都不许排进渲染帧");

        assertTrue(prefetch.contains("Thread.MIN_PRIORITY"), "自己的池必须是最低优先级");
        assertTrue(prefetch.contains("setDaemon(true)"), "预热线程必须是 daemon");
        assertTrue(prefetch.contains("\"mp-prefetch-\""),
                "线程名要带 mp-prefetch，否则事后从 jstack 里认不出是谁在占网络");

        // 「条目自带可用直链」这条判据本身。
        long now = 1_700_000_000_000L;
        assertFalse(MpRoomPrefetch.hasUsableStreamUrl(track("1", "", now + 60_000L), now),
                "没有直链 → 跳过，不解析");
        assertFalse(MpRoomPrefetch.hasUsableStreamUrl(track("1", "   ", now + 60_000L), now),
                "只有空白的直链等于没有");
        assertFalse(MpRoomPrefetch.hasUsableStreamUrl(track("1", "http://cdn/a.mp3", now - 1L), now),
                "已过期的直链跳过：下到一半也会断");
        assertTrue(MpRoomPrefetch.hasUsableStreamUrl(track("1", "http://cdn/a.mp3", 0L), now),
                "老服务端不带到期时间（0）时按可用处理");
        assertTrue(MpRoomPrefetch.hasUsableStreamUrl(track("1", "http://cdn/a.mp3", now + 60_000L), now));
        assertFalse(MpRoomPrefetch.hasUsableStreamUrl(null, now));
    }

    // ═══════════════════════ 容量闸门 / 去重 / 淘汰置顶 ═══════════════════════

    @Test
    @DisplayName("容量与磁盘余量闸门会停下并说明原因；同一首有 in-flight 去重，房间曲目在淘汰账本上置顶")
    void capacityGateStopsWithAReasonAndDownloadsAreDeduplicated() throws IOException {
        String prefetch = codeOf(roomPrefetch());
        String source = cloudMusic();

        // 纯函数本身：目录总量闸、磁盘余量闸、放行。
        assertNotNull(MpRoomPrefetch.capacityStopReason(9_000_000_000L, 8_000_000_000L, 1L << 40),
                "缓存总量已达上限必须停下");
        assertTrue(MpRoomPrefetch.capacityStopReason(9_000_000_000L, 8_000_000_000L, 1L << 40).contains("上限"),
                "停下来的原因要能读懂");
        assertNotNull(MpRoomPrefetch.capacityStopReason(1L, 0L, 1024L),
                "磁盘快满也要停：上限设成「不限制」不等于可以把用户的盘写满");
        assertTrue(MpRoomPrefetch.capacityStopReason(1L, 0L, 1024L).contains("分区"));
        assertNull(MpRoomPrefetch.capacityStopReason(1L, 0L, 1L << 40), "两道闸都放行时不许拦");
        assertNull(MpRoomPrefetch.capacityStopReason(7_000_000_000L, 8_000_000_000L, 1L << 40),
                "还没到上限就继续");

        // 闸门的三个数都取自既有机制，不另立一套预算。
        assertTrue(prefetch.contains("CloudMusic.audioCacheUsedBytes()"), "目录总量要读 MusicAudioCache 那本账");
        assertTrue(prefetch.contains("CloudMusic.audioCacheLimitBytes()"), "上限要读用户设置，不能自己定一个");
        assertTrue(prefetch.contains("CloudMusic.audioCacheFreeSpaceBytes()"), "磁盘余量闸");
        assertTrue(prefetch.contains("System.out.println(\"[MpPrefetch] \" + stop)"),
                "停下来必须打一行日志说明为什么：静默不预热只会让人去猜为什么又硬切了");
        assertTrue(source.contains("MusicAudioCache.totalBytes()"));
        assertTrue(source.contains("NCMPlayerConfig.getMusicCacheLimitBytes()"));
        assertTrue(source.contains("getUsableSpace()"));

        // in-flight 去重两层：CloudMusic 一层保证同一首同时只跑一份，MpRoomPrefetch 一层保证刷新幂等。
        assertTrue(source.contains("PREFETCH_IN_FLIGHT.add(slot)"), "同一首并发去重的 in-flight 集合");
        assertTrue(source.contains("PREFETCH_IN_FLIGHT.remove(slot)"), "跑完要放位，否则失败的那首永远排不回来");
        assertTrue(prefetch.contains("attempted.add(slotOf(track))"),
                "队列每刷新一次不能把整份清单重排一遍：已下过/正在下的直接跳过");
        assertTrue(prefetch.contains("invalidate()"), "换房/退房/队列清空要能作废在排的任务");
        assertTrue(prefetch.contains("token != generation.get()"), "在排的任务开工前对号，对不上就放弃");
        assertTrue(prefetch.contains("warmed.contains("), "同一首的 profile 也不许每刷新一次重跑一遍 Python");

        // 房间那几首不能被容量淘汰顺手删掉：靠「查盘即 touch」在最近使用账本上置顶。
        assertTrue(prefetch.contains("pinRoomTracks("), "房间队列里的曲目要在淘汰里置顶");
        assertTrue(prefetch.contains("CloudMusic.cachedAudioFileFor(song)"),
                "置顶复用那唯一的查盘入口（命中即 touch），不另写一份键");
    }

    // ════════════════════ 模型齐 + 引擎开着，且只算单曲 profile ════════════════════

    @Test
    @DisplayName("profile 预热只在模型引擎开着、运行时与两套权重齐时跑；只算单曲 profile，且给真人交接让路")
    void profileWarmupNeedsTheModelEngineAndNeverBurnsAPairPlan() throws IOException {
        String source = cloudMusic();

        String ready = region(source, "public static boolean automixProfileWarmupReady(",
                "public static boolean automixAnalysisBusy(");
        assertTrue(ready.contains("AutomixSettings.isEnabled()"), "用户关了无缝切换就没有任何东西会用这份 profile");
        assertTrue(ready.contains("AutomixSettings.isFoliaEngine()"), "引擎必须是模型那一档");
        assertTrue(ready.contains("FoliaModelManager.Kind.BEAT_THIS"), "节拍栅格权重缺一不可");
        assertTrue(ready.contains("FoliaModelManager.Kind.HTDEMUCS"), "stem 分离权重缺一不可");
        assertTrue(ready.contains("FoliaModelManager.runtimeReady()"),
                "运行组件（Python 或 ONNX，二选一）缺一不可");
        assertFalse(ready.contains("downloadAsync"), "预热没有资格顺手下载模型");

        String warm = region(source, "public static boolean warmAutomixProfile(",
                "public static void writeTo(InputStream");
        assertTrue(warm.contains("if (!automixProfileWarmupReady()) return false;"),
                "前提不满足直接不跑，一行日志都不该留");
        assertTrue(warm.contains("cachedAudioFileFor(song)"), "只预热已经下载好的：查不到文件就返回");
        assertFalse(warm.contains("prefetchRoomTrackAudio"), "profile 预热不许顺手下载");
        assertTrue(warm.contains("FoliaTrackProfiler.loadOrAnalyse(deck, false)"),
                "单曲 profile：这一首将来是「进来」的那一首");
        assertFalse(warm.contains("AutomixPlanner.plan("),
                "配对计划绝不在预热里算：AutomixPlanCache.keyOf 带着 lastVocalEndMillis 与缓存文件名，"
                        + "歌词没加载时必然算不命中，白烧一次 Python");
        assertFalse(warm.contains("AutomixPlanCache.store("), "同上");
        assertFalse(warm.contains("RoomAutomix.planWith("), "同上");
        assertTrue(warm.contains("new AudioPlayer(playable)"), "预热用自己的临时播放牌");
        assertTrue(warm.contains("deck.close()"), "用完必须关掉：第二个常驻 PCM 会让内存翻倍");
        assertFalse(warm.contains("CloudMusic.player ="), "预热的播放牌不许挂成活动播放器");
        assertTrue(warm.contains("AutomixSettings.maxDeckBytes()"), "与预备同一道体积闸");
        assertTrue(warm.contains("PlayThread.resolvePlayableAudioFile(cached, song)"),
                "profile 键含音频文件绝对路径：必须建在实播会用到的那个文件上（AAC 是 .decoded.wav 边车），"
                        + "否则预热写出去的 profile 真交接时命不中");

        // 让路：真人那一次分析在跑时不启动新的一首。
        assertTrue(source.contains("AUTOMIX_ANALYSIS_BUSY.set(true)"), "armTrack 开工置位");
        assertTrue(source.contains("AUTOMIX_ANALYSIS_BUSY.set(false)"), "armTrack 收工复位（在 finally 里）");

        String prefetch = codeOf(roomPrefetch());
        assertTrue(prefetch.contains("CloudMusic.automixAnalysisBusy()"), "预热要让路，不抢");
        assertTrue(prefetch.contains("CloudMusic.automixProfileWarmupReady()"),
                "每一首开动前重问一次前提：中途关引擎 / 删模型就该停");
        assertTrue(prefetch.contains("newPool(1, \"mp-prefetch-profile-\")"),
                "profile 预热另起一条并发 1 的 daemon：Python 串行，且不占着只有 2 条线程的下载池");
    }

    // ════════════════════════ 真实行为：三条目的队列 ════════════════════════

    @Test
    @DisplayName("三条目的队列（含一条无直链）：不抛异常、不去解析，只排自带可用直链的那一首")
    void threeEntryQueuePicksOnlyTheTrackThatAlreadyHasAStreamUrl() {
        long now = 1_700_000_000_000L;
        MpTrack playable = track("101", "http://cdn/101.mp3", now + 10 * 60_000L);
        MpTrack noUrl = track("102", "", 0L);
        MpTrack expired = track("103", "http://cdn/103.mp3", now - 1L);
        List<MpTrack> queue = Arrays.asList(playable, noUrl, expired);

        // 收帧线程上只会发生「复制引用 + 提交」：这里压根没进房间，必须就地作废而不是抛。
        MpRoomPrefetch.onQueue(queue);
        MpRoomPrefetch.onQueue(null);
        MpRoomPrefetch.onQueue(Collections.<MpTrack>emptyList());

        List<MpTrack> picked = MpRoomPrefetch.selectPrefetchTargets(queue, null, 10, now);
        assertEquals(1, picked.size(), "三条里只有「自带可用直链」的那一条该被排上");
        assertSame(playable, picked.get(0));

        // 从当前那首之后才开始算。
        MpTrack current = track("100", "http://cdn/100.mp3", now + 10 * 60_000L);
        List<MpTrack> withCurrent = new ArrayList<MpTrack>(Arrays.asList(current, playable, noUrl, expired));
        assertEquals(Collections.singletonList(playable),
                MpRoomPrefetch.selectPrefetchTargets(withCurrent, current, 10, now),
                "当前曲本身与它之前的都不算");

        // 一起听的 queue 是「待播」列表，正播那一首根本不在里面 —— 认错这个口径的后果是「一首都不排」。
        MpTrack elsewhere = track("999", "http://cdn/999.mp3", now + 10 * 60_000L);
        assertEquals(Collections.singletonList(playable),
                MpRoomPrefetch.selectPrefetchTargets(queue, elsewhere, 10, now),
                "当前曲没列进队列时，整份队列都在它之后");

        // 上限真的截断，且顺序就是队列顺序。
        List<MpTrack> twelve = new ArrayList<MpTrack>();
        for (int i = 0; i < 12; i++) {
            twelve.add(track("2" + i, "http://cdn/2" + i + ".mp3", now + 10 * 60_000L));
        }
        List<MpTrack> capped = MpRoomPrefetch.selectPrefetchTargets(twelve, null, 10, now);
        assertEquals(10, capped.size());
        assertSame(twelve.get(0), capped.get(0), "队列顺序就是预热顺序：最靠近当前曲的那首最先下");
        assertSame(twelve.get(9), capped.get(9));

        // 同一首重复登记只取第一次。
        List<MpTrack> doubled = new ArrayList<MpTrack>(Arrays.asList(playable, playable));
        assertEquals(1, MpRoomPrefetch.selectPrefetchTargets(doubled, null, 10, now).size());

        assertTrue(MpRoomPrefetch.selectPrefetchTargets(null, null, 10, now).isEmpty());
        assertTrue(MpRoomPrefetch.selectPrefetchTargets(queue, null, 0, now).isEmpty());
    }

    // ───────────────────────────── 工具 ─────────────────────────────

    /** 造一条队列条目：走协议那份 JSON，和收帧时构造出来的一模一样。 */
    private static MpTrack track(String songId, String url, long urlExpiresAt) {
        JsonObject o = new JsonObject();
        o.addProperty("source", "netease");
        o.addProperty("songId", songId);
        o.addProperty("name", "曲目 " + songId);
        o.addProperty("artist", "歌手");
        o.addProperty("duration", 180_000L);
        if (url != null && !url.isEmpty()) {
            o.addProperty("url", url);
            o.addProperty("urlExpiresAt", urlExpiresAt);
        }
        MpTrack track = MpTrack.fromJson(o);
        assertNotNull(track, "队列条目造不出来：" + songId);
        return track;
    }

    @Test
    @DisplayName("提前下载与预热不许污染「正在播的那一首」的下载/转码界面")
    void backgroundPrefetchStaysOffThePlayingTrackUi() throws IOException {
        String cloud = cloudMusic();
        String downloader = read(ProjectPaths.sourceFile(Paths.get("com", "muoniumplayer", "core", "ncm",
                "music", "MusicDownloadService.java")));

        assertTrue(cloud.contains("private static final ThreadLocal<Boolean> QUIET_DOWNLOAD"),
                "静默位必须按线程记：预热线程与播放线程会同时在下载不同的歌，共用一个全局布尔"
                        + "就会把真人那一次的进度条一起吞掉，那比多弹一张卡更糟");
        // 三处：房间队列的提前下载、automix profile 预热、以及下一首的音频预取
        // （CloudMusic#prefetchTrackAudio，YouTube 秒开靠它）。这个数字是会涨的哨兵，
        // 每加一条后台取流都要同时保证下面那个 remove() 一起涨。
        assertEquals(3, count(cloud, "QUIET_DOWNLOAD.set(Boolean.TRUE);"),
                "提前下载、profile 预热、下一首音频预取各自置位（前两者会触发 AAC→WAV 转码卡片）");
        assertEquals(3, count(cloud, "QUIET_DOWNLOAD.remove();"),
                "置了几次就必须清几次：线程池会复用线程，漏清等于把静默带给下一次实播");

        int asked = downloader.indexOf("CloudMusic.quietDownload()");
        int writesState = downloader.indexOf("musicInfo.downloading = true");
        assertTrue(asked >= 0, "下载服务根本没问过是不是后台预取");
        assertTrue(writesState > asked,
                "必须先判静默再写 musicInfo.downloading —— 顺序倒了就等于没判，"
                        + "MusicInfoWidget 会给正在播的歌长出下载进度面板");
        assertTrue(count(downloader, "if (!quiet)") >= 4,
                "开始/进度/速度/收尾四处发布点都要问一遍，漏一处就会在实播卡片上串台");

        String resolve = region(cloud, "static File resolvePlayableAudioFile(File sourceFile, Music song) {",
                "/** 取缓存文件的后缀作为转码卡片上的格式标签");
        assertTrue(resolve.contains("final boolean quietTranscode = quietDownload();"),
                "预热走的也是同一条解析序列，转码卡片同样要免扰");
        assertEquals(4, count(resolve, "if (!quietTranscode)"),
                "beginTranscode / 进度 / cancel / finish 四处都要挡住");
    }

    private static String cloudMusic() throws IOException {
        return read(ProjectPaths.sourceFile(Paths.get("com", "muoniumplayer", "core", "ncm", "music", "CloudMusic.java")));
    }

    private static String roomPrefetch() throws IOException {
        return read(PARTY.resolve("MpRoomPrefetch.java"));
    }

    /** 去掉注释，只留代码：注释里的散文会提到「代码里不许出现」的名字。 */
    private static String codeOf(String source) {
        return source.replaceAll("/\\*[\\s\\S]*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    /** 取 {@code from} 到 {@code to} 之间的那段源码（含 from，不含 to）。 */
    private static String region(String source, String from, String to) {
        int start = source.indexOf(from);
        assertTrue(start >= 0, "找不到起点：" + from);
        int end = source.indexOf(to, start);
        assertTrue(end > start, "找不到终点：" + to + "（起点之后）");
        return source.substring(start, end);
    }

    private static int count(String source, String needle) {
        int total = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + needle.length())) {
            total++;
        }
        return total;
    }

    /** 读一个 {@code PREFETCH_LIMIT = 10} 形式的整型字面量。 */
    private static int integerLiteral(String source, String marker) {
        int at = source.indexOf(marker);
        assertTrue(at >= 0, "找不到常量：" + marker);
        int start = at + marker.length();
        int end = start;
        while (end < source.length() && (Character.isDigit(source.charAt(end)) || source.charAt(end) == '_')) {
            end++;
        }
        String text = source.substring(start, end).replace("_", "");
        assertFalse(text.isEmpty(), "常量为空：" + marker);
        return Integer.parseInt(text);
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
