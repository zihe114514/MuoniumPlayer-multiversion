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
 * <p><b>优先级。</b>网易云官方接口（lyricNew）先取一次——一次请求同时带回 lrc / yrc /
 * 翻译轨三样东西。它自带的 yrc 是 songId 精确取回的逐字时间轴，存在就直接采用，连
 * 第三方源都不必问；只有平台没有逐字时，才按内容质量与匹配可靠性逐个尝试第三方逐字源：
 * QQ 的 QRC 与酷狗的 KRC 覆盖面最大、质量最高，排最前；AMLL 词库是人工投稿（覆盖率有限）
 * 排其后；汽水只对自家曲目有货，放最后。第三方源的匹配结果必须再过一道
 * {@link LyricContentCheck} 内容比对——用平台行级歌词当身份指纹，同名不同曲
 * （现场版、翻唱）在元数据打分擦边通过时在这里被拦下。两个逐字源之下的一切
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

    /**
     * 读这首的钉词，换源场景沿配对键兜底。
     *
     * <p>正在播的是替身而用户当初从原曲行钉的词时，替身的 stableKey 查不到钉账；
     * 顺着换源配对键找到同格的原曲侧再读一次。钉词描述的是「这首歌的词选了哪条」，
     * 原曲与替身是同一格的同一首歌，钉词跟着格走而不是跟着平台键走。</p>
     */
    private static LyricManualMemory.Pin firstReadablePin(Music music, String trackKey) {
        LyricManualMemory.Pin pin = LyricManualMemory.read(trackKey);
        if (pin != null) return pin;
        try {
            Music playing = CloudMusic.currentlyPlaying;
            if (playing != null && playing != music && playing.isCrossSourcePeerOf(music)) {
                return LyricManualMemory.read(playing.getStableKey());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static LyricLoadResult resolve(Music music, long songId, long profileId, File embeddedLyricFile) {
        final String trackKey = music.getStableKey();
        JsonObject rawJson = new JsonObject();
        List<LyricLine> parsed = Collections.emptyList();
        boolean cloudLyricsLoaded = false;

        // ── 0) 用户手动选定的那一条 ────────────────────────────────────────────
        // 人工确认过的结果压过一切自动判断:按钉住的源与候选 id 精确重取,不再走匹配、不再看别的源。
        // 取不回来(候选下架、凭据过期)时才落回下面的正常链路,并留一行日志说明发生了什么。
        // 换源兜底：正在播替身而用户当初从原曲行钉的词（本会话内 key 对不上）时，
        // 沿配对键找到原曲那份钉词 —— 用户视角「我钉过的词」不该因自动换源失灵。
        final LyricManualMemory.Pin manualPin = firstReadablePin(music, trackKey);
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

        // ── 1.6) YouTube 自己的歌词 ────────────────────────────────────────────
        // 排在自动逐字匹配<b>之前</b>：这是「正在放的这条视频」的官方歌词，而 AMLL / QQ QRC /
        // 酷狗 KRC 都是拿歌名加歌手去别的库里猜。内容对不对得上，比有没有逐字更要紧——
        // 现场版、翻唱与录音室版同名不同曲时，逐字匹配给出的是另一版的词。
        if (music.isYouTube()) {
            LyricLoadResult youtube = youTubeLyrics(music);
            if (youtube != null) {
                return youtube;
            }
        }

        // ── 1.7) Apple 自己的 TTML 歌词 ────────────────────────────────────────
        // 与 YouTube 早段同一哲学：Apple 行的 catalog id 精确对应一条官方逐词时间轴，
        // 拿 id 取词不存在「匹配错版本」的问题；AMLL 的按标题匹配只该给它兜空。
        // 账号态（media-user-token）在时拿到的是逐词/逐音节版，游客态经常为空 ——
        // 空串按「这一源没有结果」处理，照常落到 AMLL 兜底，不挡链路。
        if (music.isApple()) {
            LyricLoadResult apple = appleTtmlLyrics(music);
            if (apple != null) {
                return apple;
            }
        }

        // ── 1.8) 平台自己的歌词（网易云 lyricNew），一次请求三用 ─────────────────
        // 这一个请求同时带回 yrc（平台逐字）、lrc（行级正文）与翻译轨：
        //   · yrc 是 songId 精确取回的逐字时间轴——绝不匹配错，存在就直接采用，
        //     连第三方逐字源都不必问；
        //   · lrc 在平台没有逐字时当 LyricContentCheck 的身份指纹，去把关第三方源的匹配；
        //   · 都用不上时留到下面的行级兜底，原来的 lyricNew 请求就不必再发。
        // 钉在「平台官方」时整段跳过——那正是用户点这一档想要的效果（第三方逐字源匹配错了，
        // 就要一份平台自己的行级歌词，逐字再漂亮也是错的）。
        PlatformLyrics platform = null;
        boolean platformFetchFailed = false;
        if (music.isNetease() && songId > 0L && pinned != LyricSourceMemory.PLATFORM) {
            try {
                String lyricResponse = CloudMusicApi.lyricNew(songId).toString();
                // 上游偶尔夹 U+2000..U+200A 这类宽空格，解析器会把它当成正文的一部分。
                lyricResponse = lyricResponse.replaceAll("[\\u2000-\\u200a]", " ");
                JsonObject lyricJson = JsonUtils.toJsonObject(lyricResponse);
                if (lyricJson != null && hasLyricPayload(lyricJson)) {
                    List<LyricLine> platformLines = LyricParser.parse(lyricJson);
                    platform = new PlatformLyrics(lyricJson, platformLines);
                    if (LyricParser.hasRealWordTiming(platformLines)) {
                        System.out.println("[Music/Platform] Platform carries word-by-word (yrc), "
                                + "serving it before any third-party source for " + trackKey);
                        SERVED.put(trackKey, LyricSourceMemory.PLATFORM);
                        return new LyricLoadResult(lyricJson, platformLines);
                    }
                }
            } catch (Throwable throwable) {
                // 早取失败只影响这一首的指纹与短路，链路照常往下走；别在行级兜底再补一次请求——
                // 刚失败过一次的接口马上重试只会多等一个超时。
                platformFetchFailed = true;
                System.err.println("[Music/Platform] lyricNew fetch failed for " + trackKey + ": "
                        + throwable.getMessage());
            }
        }

        // ── 2) 自动：逐字歌词永远优先于普通歌词 ────────────────────────────────
        // 每个第三方源的结果都要过 LyricContentCheck：平台行级歌词（songId 精确取回，
        // 绝无匹配错）当身份指纹，拦下同名不同曲的擦边匹配。
        if (pinned != LyricSourceMemory.PLATFORM) {
            WordSource wordByWord = autoWordSource(music, platform);
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

        // ── 行级兜底：平台行级歌词 ─────────────────────────────────────────────
        // lyricNew 已经在 1.8 步提前取过（响应里 lrc/yrc/翻译同包），这里直接复用那份
        // 解析结果，不再发第二个请求——早取失败过一次的接口马上重试只会多等一个超时。
        if (music.isNetease() && !cloudLyricsLoaded && !LyricParser.hasRealWordTiming(parsed)) {
            if (platform != null && !platform.parsed.isEmpty()) {
                // 早取已解析过：真逐字已经短路过，走到这里的只有行级结果（或为空）。
                if (LyricParser.hasRealWordTiming(platform.parsed) || parsed.isEmpty()) {
                    rawJson = platform.rawJson;
                    parsed = platform.parsed;
                }
            } else if (platform == null && !platformFetchFailed) {
                // 没走到早取（理论上只剩 pinned==PLATFORM 或非网易云，前者已被门控挡住），
                // 保守补一次请求，行为与旧链路一致。
                try {
                    String lyricResponse = CloudMusicApi.lyricNew(songId).toString();
                    lyricResponse = lyricResponse.replaceAll("[\\u2000-\\u200a]", " ");
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
    /**
     * YouTube 官方歌词。
     *
     * <p>实测留了个前提：匿名态下那一跳拿到的全是「没有歌词」的占位面板（连参考实现自己
     * 测试用的 videoId 也一样），所以这里<b>未登录就直接跳过</b>，不去白打两个请求。
     * 用户粘好 Cookie 之后这条才真正亮起来。</p>
     *
     * @return 拿不到歌词时返回 null，调用方继续走正常链路
     */
    private static LyricLoadResult youTubeLyrics(Music music) {
        if (!YouTubeSessionStore.isLoggedIn()) return null;
        String videoId = music.getSourceId();
        if (videoId == null || videoId.trim().isEmpty()) return null;
        try {
            YouTubeMusicService.Lyrics lyrics = YouTubeMusicService.getLyrics(videoId);
            if (lyrics == null || lyrics.isEmpty()) return null;
            String text = lyrics.timed ? lyrics.lrc : lyrics.plainText;
            if (text == null || text.trim().isEmpty()) return null;
            JsonObject json = createEmbeddedLyricJson(text);
            List<LyricLine> lines = LyricParser.parse(json);
            if (lines.isEmpty()) return null;
            System.out.println("[Music/YouTube] 官方歌词 " + lines.size() + " 行（"
                    + (lyrics.timed ? "带时间戳" : "纯文本") + "）for " + music.getStableKey());
            return new LyricLoadResult(json, lines);
        } catch (Throwable throwable) {
            System.err.println("[Music/YouTube] 歌词取回失败 " + videoId + ": " + throwable);
            return null;
        }
    }

    /**
     * Apple 官方 TTML → 共享时间轴。
     *
     * <p>解析走 {@code LyricParser.parseTtml}（AMLL 同一条路），返回前用
     * {@code hasWordByWordTiming} 自检：Apple 游客态经常只给行级甚至空串，行级 TTML
     * 不算数 —— 按 null 处理，照常落到 AMLL 兜底，绝不拿一份没有逐字的时间轴去喂
     * 逐词渲染器。缓存 JSON 直接包 TTML 原文，与内嵌歌词同一形态。</p>
     */
    private static LyricLoadResult appleTtmlLyrics(Music music) {
        String songId = music.getSourceId();
        if (songId == null || songId.trim().isEmpty()) return null;
        try {
            String ttml = com.muoniumplayer.core.ncm.apple.AppleMusicService.ttml(songId);
            if (ttml == null || ttml.trim().isEmpty()) return null;
            List<LyricLine> parsed = new ArrayList<>();
            LyricParser.parseTtml(ttml, parsed);
            if (!LyricParser.hasWordByWordTiming(parsed)) return null;
            System.out.println("[Music/Apple] 官方 TTML 歌词 " + parsed.size() + " 行 for "
                    + music.getStableKey());
            return new LyricLoadResult(createEmbeddedLyricJson(ttml), parsed);
        } catch (Throwable throwable) {
            System.err.println("[Music/Lyric] Apple TTML 取回失败 " + music.getStableKey()
                    + ": " + throwable);
            return null;
        }
    }

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
     * 自动链路的四个逐字源，按「覆盖面 → 人工词库 → 平台专有」取第一个真的是逐字的。
     *
     * <p>QQ 的 QRC 与酷狗的 KRC 覆盖面最大、质量最高，排最前；AMLL 词库是人工投稿
     * （覆盖率有限，但条目质量极高）排其后，前两个源都空手时仍有机会；汽水只对自家曲目
     * 有货，放最后。每个源的结果除了「真的是逐字」之外，还要过
     * {@link LyricContentCheck}——拿平台行级歌词当身份指纹，把元数据打分擦边放行的
     * 同名不同曲（现场版、翻唱）拦下来；判否按「这个源这次没有逐字」处理，链路继续。</p>
     *
     * <p>{@code platform} 为 {@code null}（非网易云曲目、早取失败、钉了平台官方）时
     * 没有指纹可比，跳过内容比对——与旧行为一致，不能因为没有参考就把逐字全毙了。</p>
     */
    private static WordSource autoWordSource(Music music, PlatformLyrics platform) {
        final String trackKey = music.getStableKey();
        final List<LyricLine> reference = platform == null ? null : platform.parsed;

        WordSource accepted = acceptWordSource(LyricSourceMemory.QQ, QQQrcLyricService.resolve(music),
                reference, trackKey);
        if (accepted != null) return accepted;

        accepted = acceptWordSource(LyricSourceMemory.KUGOU, KuGouMusicService.getWordByWordLyrics(music),
                reference, trackKey);
        if (accepted != null) return accepted;

        accepted = acceptWordSource(LyricSourceMemory.AMLL, AmllTtmlLyricService.resolve(music),
                reference, trackKey);
        if (accepted != null) return accepted;

        accepted = acceptWordSource(LyricSourceMemory.QISHUI, QishuiMusicService.getWordByWordLyrics(music),
                reference, trackKey);
        if (accepted != null) return accepted;

        return new WordSource(LyricSourceMemory.AUTO, Collections.<LyricLine>emptyList());
    }

    /**
     * 采纳一个逐字源的结果：真的是逐字、且内容比对（有参考时）确认是同一首歌。
     * 不满足返回 {@code null}，调用方落到下一个源。
     */
    private static WordSource acceptWordSource(int source, List<LyricLine> lines,
                                               List<LyricLine> reference, String trackKey) {
        if (!LyricParser.hasWordByWordTiming(lines)) return null;

        if (reference != null) {
            LyricContentCheck.Result check = LyricContentCheck.check(reference, lines);
            if (!check.accepted) {
                System.out.println("[Music/ContentCheck] " + LyricSourceMemory.displayName(source)
                        + " word-by-word lyrics rejected for " + trackKey + ": " + check.describe());
                return null;
            }
            System.out.println("[Music/ContentCheck] " + LyricSourceMemory.displayName(source)
                    + " word-by-word lyrics verified for " + trackKey + ": " + check.describe());
        }

        System.out.println("[Music/" + LyricSourceMemory.displayName(source)
                + "] Loaded word-by-word lyrics for " + trackKey);
        return new WordSource(source, lines);
    }

    /** 平台歌词的早取结果：原始响应 + 解析出的行（可能带 yrc 逐字，也可能只有行级）。 */
    private static final class PlatformLyrics {
        final JsonObject rawJson;
        final List<LyricLine> parsed;

        PlatformLyrics(JsonObject rawJson, List<LyricLine> parsed) {
            this.rawJson = rawJson;
            this.parsed = parsed;
        }
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