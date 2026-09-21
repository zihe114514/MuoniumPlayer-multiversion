package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.core.screens.ncm.LyricParser;
import com.muoniumplayer.core.utils.json.JsonUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网络、内嵌标签和内置 YRC 的歌词解析。
 *
 * <p>结果刻意不在此处发布。CloudMusic 保留会话校验、
 * 跨线程状态发布和主线程时间线应用。</p>
 *
 * <p><b>优先级。</b>先问 AMLL TTML 数据库：其条目是人工逐字核对的
 * 时间线，胜过任何平台响应，因此一旦它应答就完全不再请求其他源。
 * QQ 音乐的 QRC 次之——仍是音节级，覆盖比任何开放数据库广得多——
 * 并以同样方式短路链中其余部分。两个逐字源之下的一切
 * （云盘歌词、内嵌标签、统一提供方
 * 模型、网易云歌词 API、内置 YRC 文件）都是行级兜底，且
 * 当前一步已产出真实逐字时间时，每一步仍会被跳过。</p>
 */
final class LyricLoadService {

    /**
     * 副歌区间的内存缓存。
     *
     * <p>一首歌只有一份，且在一次会话里最多问一次。用 {@code Map} + 上限而不是无界缓存：
     * 偏移量与结果都很小，但长播几小时也会攒上千首歌。</p>
     */
    private static final Object CHORUS_LOCK = new Object();
    private static final java.util.LinkedHashMap<Long, List<long[]>> CHORUS_RANGES =
            new java.util.LinkedHashMap<Long, List<long[]>>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<Long, List<long[]>> eldest) {
                    return size() > 512;
                }
            };

    /**
     * 全部可选来源，供 {@link #invalidateAll(Music)} 把这首歌的每一种来源变体都清干净。
     *
     * <p>缓存键带来源偏好，所以「清缓存」必须按<b>所有</b>来源各清一次——只清当前那个的话，
     * 用户在 AMLL 与自动之间来回切一次，就会命中上一次留在盘上的另一份结果。</p>
     */
    private static final int[] ALL_SOURCES = {
            LyricSourceMemory.AUTO,
            LyricSourceMemory.AMLL,
            LyricSourceMemory.QQ,
            LyricSourceMemory.KUGOU,
            LyricSourceMemory.QISHUI,
            LyricSourceMemory.PLATFORM,
    };

    /** 上一轮实际服务了这首歌的来源，只用于界面把「当前用的是哪个源」显示出来。 */
    private static final Map<String, Integer> SERVED = new ConcurrentHashMap<String, Integer>();

    private LyricLoadService() {
    }

    /**
     * 解析这首歌的歌词时间轴。
     *
     * <p>加了一层落盘缓存：命中就直接返回，整条网络链路都不用走。缓存键带上用户的音源偏好开关与
     * 这首歌指定的来源，否则关掉「优先 AMLL」、或手动把来源改到 QQ 之后，仍会命中上一次留下的旧结果。</p>
     */
    static LyricLoadResult load(Music music, long songId, long profileId, File embeddedLyricFile) {
        final String trackKey = music.getStableKey();
        final String cacheKey = cacheKey(trackKey);

        List<LyricLine> cached = LyricCacheStore.load(cacheKey);
        if (!cached.isEmpty()) {
            System.out.println("[Music/Cache] Loaded lyrics from disk cache for " + trackKey);
            return new LyricLoadResult(new JsonObject(), cached);
        }

        LyricLoadResult result = resolve(music, songId, profileId, embeddedLyricFile);
        if (result.lines != null && !result.lines.isEmpty()) {
            markChorus(music, songId, result.lines);
            LyricCacheStore.store(cacheKey, result.lines);
        }
        return result;
    }

    /** 缓存键：歌曲稳定键 + 影响解析结果的用户开关 + 这首歌指定的来源 + 手动钉住的那一条。 */
    static String cacheKey(String trackKey) {
        return cacheKeyFor(trackKey, LyricSourceMemory.read(trackKey));
    }

    private static String cacheKeyFor(String trackKey, int source) {
        return cacheKeyFor(trackKey, source, manualSuffix(trackKey));
    }

    private static String cacheKeyFor(String trackKey, int source, String manualSuffix) {
        return trackKey + "|a" + (AmllTtmlLyricService.isEnabled() ? 1 : 0)
                + "q" + (QQQrcLyricService.isEnabled() ? 1 : 0)
                + "s" + source + manualSuffix;
    }

    /**
     * 手动钉词那一档也要进缓存键。
     *
     * <p>{@link #load} 在 {@link #resolve} <b>之前</b>查盘，键里不带钉住的那一条时，用户保存完之后
     * 下一次播放命中的还是上一轮自动匹配留在盘上的结果——界面上看就是「改了没生效」。带上它之后，
     * 换一条候选就是一个新键，自动那份原样留在原地，取消钉住时不必重新联网。</p>
     */
    private static String manualSuffix(String trackKey) {
        LyricManualMemory.Pin pin = LyricManualMemory.read(trackKey);
        return pin == null ? "" : "|m" + pin.source.id + ':' + pin.trackId;
    }

    /**
     * 清掉这首歌<b>全部</b>来源的落盘缓存与各源的内存缓存。
     *
     * <p>手动「重取歌词」的第一步。只清这一首而不是整目录：用户要的是「这一首重新匹配」，
     * 顺手把几百首歌的缓存全删掉，只会让接下来半小时的播放都变慢。</p>
     *
     * <p>内存缓存也必须一起清，而且不能漏源。落盘缓存清干净、AMLL 却还留着上一次的命中时，
     * 重取会立刻拿回同一份结果——用户看到的就是「点了没反应」。所以这里逐个源清：</p>
     * <ul>
     *   <li>{@link AmllTtmlLyricService} / {@link QQQrcLyricService}：逐字源，各自有按曲内存缓存；</li>
     *   <li>{@link GdStudioMusicService}：统一提供方的歌词缓存，按小时计，漏掉它的话一次错误的
     *       兜底匹配会被缓存到用户重启为止；</li>
     *   <li>酷狗 / 汽水：本身不缓存歌词，无需处理。</li>
     * </ul>
     */
    static void invalidateAll(Music music) {
        if (music == null) return;
        final String trackKey = music.getStableKey();
        final String manual = manualSuffix(trackKey);
        for (int source : ALL_SOURCES) {
            // 钉住与未钉住两种键各清一遍:只清当前的那一种,取消钉住后就会命中另一份留在盘上的旧结果。
            LyricCacheStore.invalidate(cacheKeyFor(trackKey, source, ""));
            LyricCacheStore.invalidate(cacheKeyFor(trackKey, source, manual));
        }
        AmllTtmlLyricService.invalidate(music);
        QQQrcLyricService.invalidate(music);
        GdStudioMusicService.invalidateLyric(music.getGdPlatform(), music.getGdLyricId(), music.getName());
        // 这里刻意<b>不</b>清 {@link LyricManualMemory}：换来源、改偏好都不该把用户亲手挑中的那条结果
        // 抹掉。「重取」才是明确的丢弃动作，由 {@code CloudMusic.reloadLyrics} 自己清。
        SERVED.remove(trackKey);
    }

    /**
     * 上一轮实际服务这首歌的来源。
     *
     * @return {@link LyricSourceMemory#AMLL} / {@code QQ} / {@code KUGOU} / {@code QISHUI} /
     *         {@link LyricSourceMemory#PLATFORM}；<b>未知返回 {@code -1}</b>。
     *         「未知」与「自动」必须是两个状态：自动只是一个没指定来源的偏好值，从来不会有人
     *         「用自动这个源」把歌放出来，所以拿它当未知会让界面把「这次没解析过」误报成别的意思。
     */
    static int servedSource(String trackKey) {
        Integer value = trackKey == null ? null : SERVED.get(trackKey);
        return value == null ? -1 : value;
    }

    /**
     * 手动搜索用的那四个源，映射到界面「这一首用的是什么来源」显示的那套编号。
     *
     * <p>网易云那一档就是平台自带的行级歌词，对应 {@link LyricSourceMemory#PLATFORM}；其余三个
     * 与自动链路的同名源是同一批数据，直接复用。两套编号迟早该合并成一套，但在合并之前
     * 这里必须显式写清楚，省得日后加源时漏掉一项。</p>
     */
    private static int servedSourceOf(LyricSearchSource source) {
        if (source == LyricSearchSource.AMLL) return LyricSourceMemory.AMLL;
        if (source == LyricSearchSource.QQ) return LyricSourceMemory.QQ;
        if (source == LyricSearchSource.KUGOU) return LyricSourceMemory.KUGOU;
        return LyricSourceMemory.PLATFORM;
    }

    /**
     * 标记副歌段。
     *
     * <p>先用<b>零成本</b>的文本频次启发式扫一遍；只有它真的找到了重复句，才去问网易云的精确区间。
     * 顺序上「精确优先」——区间一旦拿到就把文本结果覆盖掉——但网络请求被文本结果<b>把关</b>：
     * 一句都不重复的歌本来就没有高潮段可言，为它多打一次请求只是白白拖慢歌词上屏。</p>
     */
    private static void markChorus(Music music, long songId, List<LyricLine> lines) {
        try {
            if (LyricChorusMarker.markByText(lines) == 0) return;
            if (music == null || !music.isNetease() || songId <= 0L) return;

            List<long[]> ranges = chorusRanges(songId);
            if (ranges.isEmpty()) return;

            for (LyricLine line : lines) {
                line.isChorus = false;
                line.chorusIndex = -1;
                line.chorusEffect = -1;
            }
            LyricChorusMarker.markByRanges(lines, ranges);
        } catch (Throwable failure) {
            System.err.println("[Music/Chorus] 副歌标记失败: " + failure.getMessage());
        }
    }

    /** 网易云副歌区间，带内存缓存。任何失败都返回空列表——副歌只是装饰，不值得让歌词加载失败。 */
    private static List<long[]> chorusRanges(long songId) {
        synchronized (CHORUS_LOCK) {
            List<long[]> cached = CHORUS_RANGES.get(songId);
            if (cached != null) return cached;
        }

        List<long[]> ranges = new ArrayList<>();
        try {
            ranges = LyricChorusMarker.parseRanges(CloudMusicApi.chorus(songId).toJsonObject());
        } catch (Throwable failure) {
            System.err.println("[Music/Chorus] 副歌区间请求失败 " + songId + ": " + failure.getMessage());
        }

        synchronized (CHORUS_LOCK) {
            CHORUS_RANGES.put(songId, ranges);
        }
        return ranges;
    }

    private static LyricLoadResult resolve(Music music, long songId, long profileId, File embeddedLyricFile) {
        final String trackKey = music.getStableKey();
        JsonObject rawJson = new JsonObject();
        List<LyricLine> parsed = Collections.emptyList();
        boolean cloudLyricsLoaded = false;

        // ── 0) 用户手动选定的那一条 ────────────────────────────────────────────
        // 人工确认过的结果压过一切自动判断:按钉住的源与候选 id 精确重取,不再走匹配、不再看别的源。
        // 取不回来(候选下架、凭据过期)时才落回下面的正常链路,并留一行日志说明发生了什么。
        final LyricManualMemory.Pin manualPin = LyricManualMemory.read(trackKey);
        if (manualPin != null) {
            List<LyricLine> pinnedLyrics = LyricSearchService.fetchLyrics(manualPin);
            if (pinnedLyrics != null && !pinnedLyrics.isEmpty()) {
                System.out.println("[Music/Manual] 使用手动钉住的歌词 " + manualPin.describe()
                        + " for " + trackKey);
                SERVED.put(trackKey, servedSourceOf(manualPin.source));
                return new LyricLoadResult(rawJson, pinnedLyrics);
            }
            System.out.println("[Music/Manual] 钉住的那一条这次取不回来,回落正常链路: "
                    + manualPin.describe() + " / " + trackKey);
        }

        // ── 1) 用户给这首歌指定的来源 ──────────────────────────────────────────
        // 「指定」是偏好而非硬绑定：那一个源这次没有逐字结果时，记一行日志然后照常走自动链路。
        // 宁可给一份别的源的歌词，也不要因为用户挑的那个源恰好缺条目而留一片空白。
        final int pinned = LyricSourceMemory.read(trackKey);
        if (pinned != LyricSourceMemory.AUTO && pinned != LyricSourceMemory.PLATFORM) {
            List<LyricLine> pinnedLyrics = wordSource(pinned, music);
            if (LyricParser.hasWordByWordTiming(pinnedLyrics)) {
                System.out.println("[Music/Source] " + LyricSourceMemory.displayName(pinned)
                        + " served word-by-word lyrics for " + trackKey + " (用户指定)");
                SERVED.put(trackKey, pinned);
                return new LyricLoadResult(rawJson, pinnedLyrics);
            }
            System.out.println("[Music/Source] 指定的 " + LyricSourceMemory.displayName(pinned)
                    + " 这次没有逐字结果,回落自动链路: " + trackKey);
        }

        // ── 2) 自动：逐字歌词永远优先于普通歌词 ────────────────────────────────
        // 指定「平台官方」时整段跳过——那正是用户点这一档想要的效果（逐字源匹配错了，
        // 就要一份平台自己的行级歌词，逐字再漂亮也是错的）。
        if (pinned != LyricSourceMemory.PLATFORM) {
            WordSource wordByWord = autoWordSource(music);
            if (LyricParser.hasWordByWordTiming(wordByWord.lines)) {
                SERVED.put(trackKey, wordByWord.source);
                return new LyricLoadResult(rawJson, wordByWord.lines);
            }
        }

        if (music.isCloudSong() && profileId > 0L) {
            try {
                JsonObject cloudJson = normalizeCloudLyricResponse(
                        CloudMusicApi.cloudLyricGet(profileId, songId).toJsonObject());
                List<LyricLine> cloudLyrics = LyricParser.parse(cloudJson);
                if (!cloudLyrics.isEmpty()) {
                    rawJson = cloudJson;
                    parsed = cloudLyrics;
                    cloudLyricsLoaded = true;
                    System.out.println("[Music] Loaded cloud-drive lyrics for " + trackKey);
                }
            } catch (Throwable throwable) {
                System.err.println("[Music] Cloud-drive lyric API failed for " + trackKey + ": "
                        + throwable.getMessage());
            }
        }

        if (!cloudLyricsLoaded && embeddedLyricFile != null) {
            try {
                String embeddedText = EmbeddedLyricsReader.read(embeddedLyricFile);
                if (!embeddedText.isEmpty()) {
                    JsonObject embeddedJson = createEmbeddedLyricJson(embeddedText);
                    // 走格式嗅探而不是只认 LRC:本地文件里除了普通 LRC，还可能是洛雪导出的
                    // `[awlrc:...]` 容器（带逐字轨）、卡拉OK 的 WEBVTT 字幕、或 awlrc 逐字轨。
                    List<LyricLine> embedded = LyricParser.parseLyricText(embeddedText, "", "");
                    if (!embedded.isEmpty()) {
                        rawJson = embeddedJson;
                        parsed = embedded;
                        cloudLyricsLoaded = true;
                        System.out.println("[Music] Loaded embedded lyrics for cloud song " + trackKey
                                + " from " + embeddedLyricFile.getName());
                    }
                }
            } catch (Throwable throwable) {
                System.err.println("[Music] Embedded lyric read failed for " + trackKey + ": "
                        + throwable.getMessage());
            }
        }

        if (parsed.isEmpty()) {
            try {
                top.fpsmaster.music.Lyric cadenceLyric = NeteaseMusicService.getLyric(music);
                if (cadenceLyric != null) {
                    parsed = LyricParser.fromCadence(cadenceLyric, music.getDuration(), false);
                }
            } catch (Throwable throwable) {
                System.err.println("[Music/Netease] Unified lyric conversion failed for " + trackKey + ": "
                        + throwable.getMessage());
            }
        }

        // 酷狗的普通歌词兜底:上面那条统一 provider 链路对酷狗返回 null,行级 LRC 只能从这里补。
        if (parsed.isEmpty() && music.isKuGou()) {
            try {
                String lrc = KuGouMusicService.getFallbackLrc(music);
                if (!lrc.isEmpty()) {
                    JsonObject lrcJson = createEmbeddedLyricJson(lrc);
                    // 同样走嗅探:酷狗偶尔返回的其实是一份带 <偏移,时长> 音节的逐字文本,
                    // 只按普通 LRC 解会把这些音节整段当正文丢掉。
                    List<LyricLine> lines = LyricParser.parseLyricText(lrc, "", "");
                    if (!lines.isEmpty()) {
                        rawJson = lrcJson;
                        parsed = lines;
                        System.out.println("[Music/KuGou] Loaded line-level LRC lyrics for " + trackKey);
                    }
                }
            } catch (Throwable throwable) {
                System.err.println("[Music/KuGou] LRC fallback failed for " + trackKey + ": "
                        + throwable.getMessage());
            }
        }

        if (music.isNetease() && !cloudLyricsLoaded && !LyricParser.hasRealWordTiming(parsed)) {
            try {
                String lyricResponse = CloudMusicApi.lyricNew(songId).toString();
                lyricResponse = lyricResponse.replaceAll("[ - ]", " ");
                rawJson = JsonUtils.toJsonObject(lyricResponse);
                List<LyricLine> fallback = LyricParser.parse(rawJson);
                if (LyricParser.hasRealWordTiming(fallback) || parsed.isEmpty()) {
                    parsed = fallback;
                }
            } catch (Throwable throwable) {
                System.err.println("[NCM] Legacy lyric fallback failed for " + trackKey + ": "
                        + throwable.getMessage());
            }
        }

        if (music.isNetease() && !cloudLyricsLoaded && !LyricParser.hasRealWordTiming(parsed)) {
            InputStream stream = LyricLoadService.class.getResourceAsStream("/muonium/yrc/" + songId + ".yrc");
            if (stream != null) {
                try {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    MusicDownloadService.writeTo(stream, output);
                    String yrc = new String(output.toByteArray(), StandardCharsets.UTF_8);
                    List<LyricLine> embedded = new ArrayList<>();
                    LyricParser.parseYrc(yrc, embedded);
                    if (LyricParser.hasRealWordTiming(embedded)) {
                        parsed = embedded;
                    }
                } catch (Throwable throwable) {
                    System.err.println("[NCM] Embedded YRC fallback failed for " + trackKey + ": "
                            + throwable.getMessage());
                } finally {
                    try {
                        stream.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        // ── 最后一级：自定义源（LX 脚本）给的歌词 ──────────────────────────────
        // 内置链路一条都没给出歌词时才问它，而且默认根本不问（宿主扩展开关，见
        // LxSourceService#isExtraActionsEnabled）。与取流相反 —— 取流那边自定义源排在
        // 最前面，因为用户导入它就是为了取流；歌词这边平台自己那份（带翻译）几乎总是更准。
        // rlyric 是罗马音，正好对上 parseLyricText 的第三个参数；lxlyric 是洛雪自家的
        // 逐字格式，本播放器没有解析器，交给 parseLyricText 的格式嗅探去认（认不出就按行级用）。
        if (parsed.isEmpty()) {
            try {
                com.muoniumplayer.core.ncm.lxsource.LxSourceService.Lyrics scriptLyrics = music.resolveLxLyrics();
                if (scriptLyrics != null && scriptLyrics.lyric != null && !scriptLyrics.lyric.trim().isEmpty()) {
                    List<LyricLine> fromScript = LyricParser.parseLyricText(scriptLyrics.lyric,
                            scriptLyrics.tlyric == null ? "" : scriptLyrics.tlyric,
                            scriptLyrics.rlyric == null ? "" : scriptLyrics.rlyric);
                    if (!fromScript.isEmpty()) {
                        rawJson = createEmbeddedLyricJson(scriptLyrics.lyric);
                        parsed = fromScript;
                        System.out.println("[Music/LX] 自定义源给出了歌词 for " + trackKey);
                    }
                }
            } catch (Throwable throwable) {
                System.err.println("[Music/LX] 自定义源歌词失败 for " + trackKey + ": "
                        + throwable.getMessage());
            }
        }

        // 走到这里的都是「不是逐字」的兜底结果（云盘 / 内嵌 / 统一提供方 / 官方 / 内置 YRC）。
        // 界面上统一报成「平台官方」：用户需要区分的是「逐字源」与「平台自己那份」，
        // 再往下细分到具体哪个兜底分支，对选源没有指导意义。
        SERVED.put(trackKey, LyricSourceMemory.PLATFORM);
        return new LyricLoadResult(rawJson, parsed);
    }

    /** 只问用户指定的那一个逐字源，别的源一个都不碰。 */
    private static List<LyricLine> wordSource(int source, Music music) {
        try {
            List<LyricLine> lines;
            switch (source) {
                case LyricSourceMemory.AMLL:
                    lines = AmllTtmlLyricService.resolve(music);
                    break;
                case LyricSourceMemory.QQ:
                    lines = QQQrcLyricService.resolve(music);
                    break;
                case LyricSourceMemory.KUGOU:
                    lines = KuGouMusicService.getWordByWordLyrics(music);
                    break;
                case LyricSourceMemory.QISHUI:
                    lines = QishuiMusicService.getWordByWordLyrics(music);
                    break;
                default:
                    return Collections.emptyList();
            }
            return lines == null ? Collections.<LyricLine>emptyList() : lines;
        } catch (Throwable failure) {
            System.err.println("[Music/Source] 指定来源解析失败: " + failure.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 自动链路的四个逐字源，按「人工核对的词库 → 覆盖面 → 平台专有」取第一个真的是逐字的。
     *
     * <p>AMLL 词库是人工投稿、覆盖率有限，但它胜过任何平台响应；QQ 的 QRC 覆盖面大得多，
     * 排在其后；酷狗与汽水是平台专有，各自只对自家曲目有货，放最后。</p>
     */
    private static WordSource autoWordSource(Music music) {
        final String trackKey = music.getStableKey();

        List<LyricLine> amllLyrics = AmllTtmlLyricService.resolve(music);
        if (LyricParser.hasWordByWordTiming(amllLyrics)) {
            System.out.println("[Music/AMLL] Loaded word-by-word TTML lyrics for " + trackKey);
            return new WordSource(LyricSourceMemory.AMLL, amllLyrics);
        }

        List<LyricLine> qqQrcLyrics = QQQrcLyricService.resolve(music);
        if (LyricParser.hasWordByWordTiming(qqQrcLyrics)) {
            System.out.println("[Music/QQ-QRC] Loaded word-by-word QRC lyrics for " + trackKey);
            return new WordSource(LyricSourceMemory.QQ, qqQrcLyrics);
        }

        List<LyricLine> kugouLyrics = KuGouMusicService.getWordByWordLyrics(music);
        if (LyricParser.hasWordByWordTiming(kugouLyrics)) {
            System.out.println("[Music/KuGou] Loaded word-by-word KRC lyrics for " + trackKey);
            return new WordSource(LyricSourceMemory.KUGOU, kugouLyrics);
        }

        List<LyricLine> qishuiLyrics = QishuiMusicService.getWordByWordLyrics(music);
        if (LyricParser.hasWordByWordTiming(qishuiLyrics)) {
            System.out.println("[Music/Qishui] Loaded word-by-word lyrics for " + trackKey);
            return new WordSource(LyricSourceMemory.QISHUI, qishuiLyrics);
        }

        return new WordSource(LyricSourceMemory.AUTO, Collections.<LyricLine>emptyList());
    }

    /** 逐字源的解析结果 + 它来自哪一个源。 */
    private static final class WordSource {
        final int source;
        final List<LyricLine> lines;

        WordSource(int source, List<LyricLine> lines) {
            this.source = source;
            this.lines = lines;
        }
    }

    static File resolveEmbeddedLyricFile(File playbackFile) {
        if (playbackFile == null || !playbackFile.isFile()) {
            return null;
        }
        String name = playbackFile.getName();
        final String decodedSuffix = ".decoded.wav";
        if (!name.endsWith(decodedSuffix)) {
            return playbackFile;
        }

        String baseName = name.substring(0, name.length() - decodedSuffix.length());
        String[] sourceExtensions = {"m4a", "aac", "mp3", "flac"};
        for (String extension : sourceExtensions) {
            File source = new File(playbackFile.getParentFile(), baseName + "." + extension);
            if (source.isFile()) {
                return source;
            }
        }
        return playbackFile;
    }

    private static JsonObject createEmbeddedLyricJson(String lyricText) {
        JsonObject root = new JsonObject();
        JsonObject lrc = new JsonObject();
        lrc.addProperty("lyric", lyricText);
        root.add("lrc", lrc);
        return root;
    }

    private static JsonObject normalizeCloudLyricResponse(JsonObject response) {
        if (response == null) return new JsonObject();
        if (hasLyricPayload(response)) return response;

        JsonElement data = response.get("data");
        if (data != null && data.isJsonObject() && hasLyricPayload(data.getAsJsonObject())) {
            return data.getAsJsonObject();
        }

        JsonElement result = response.get("result");
        if (result != null && result.isJsonObject() && hasLyricPayload(result.getAsJsonObject())) {
            return result.getAsJsonObject();
        }
        return response;
    }

    private static boolean hasLyricPayload(JsonObject object) {
        return object.has("lrc") || object.has("yrc") || object.has("ytlrc")
                || object.has("tlyric") || object.has("romalrc");
    }

    static final class LyricLoadResult {
        final JsonObject rawJson;
        final List<LyricLine> lines;

        private LyricLoadResult(JsonObject rawJson, List<LyricLine> lines) {
            this.rawJson = rawJson;
            this.lines = lines;
        }

        /** 加载彻底失败时的空结果：调用方仍然要提交一次，好让歌词时间轴的归属推进到当前歌曲。 */
        static LyricLoadResult empty() {
            return new LyricLoadResult(new JsonObject(), Collections.<LyricLine>emptyList());
        }
    }
}