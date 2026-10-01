package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.ncm.music.dto.Music;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把系统媒体会话报来的「曲名 + 歌手」换回一条真正的曲库曲目。
 *
 * <p>这一步是整个镜像功能值不值得做的关键。SMTC 只给身份与时钟，不给封面也不给歌词；
 * 而本模组已经有一套久经考验的链路：{@link CrossSourceSwitcher#searchPlatform} 按曲名歌手
 * 检索（自带「带艺人搜空了就退纯曲名再搜一次」的降级），拿回来的 {@link Music} 有真 songId，
 * 于是歌词（AMLL/TTML、KRC、逐字、手动选词）、静态与动态封面、收藏标记全部原样可用。
 * 换句话说镜像不是"另做一套显示"，而是把外部播放器变成我们曲库里一首歌的遥控器。</p>
 *
 * <p>判据里最关键的一条是<b>时长</b>：SMTC 给的时长是外部播放器实际解码出来的，硬证据。
 * 曲名歌手对上但时长差 15 秒以上的，几乎一定是 Live / Acoustic / 翻唱 / 剪辑版，
 * 那种歌的歌词时间轴跟正在播的这份不是一回事，宁可显示"没匹配到"也不要滚错词 ——
 * 外部那套 LyricSync 就是因为只按"搜到第一条"落地，才把整首歌的配错。</p>
 */
final class ExternalTrackMatcher {

    /** 采纳一条匹配所需的时长吻合容差。 */
    private static final long DURATION_TOLERANCE_MS = 15_000L;
    /**
     * 依次问哪几个平台。
     *
     * <p>网易云排第一是因为它的歌词与动态封面保真度最好，但实测<b>未登录时它的搜索直接
     * 返回空</b>（本机三个关键词全 0 命中，cookie 长度为 0）。镜像的匹配不能绑在登录上：
     * 玩家可能只是想看外部播放器那首歌的歌词，压根没打算登录网易云。所以后面还接酷狗
     * （匿名可搜，且我们本来就有它的逐字 KRC 链路）与 B 站。</p>
     *
     * <p>实测同一首《晴天》：酷狗首条 269000ms、B 站 270000ms，与外部报来的 269000ms
     * 都在容差内；而汽水首条是 75840ms 的片段、YouTube 是 319000ms 的 MV，都会被
     * {@link #durationAgrees} 拒掉 —— 这两条就没进列表。</p>
     */
    private static final MusicPlatform[] MATCH_ORDER = {
            MusicPlatform.NETEASE, MusicPlatform.KUGOU, MusicPlatform.BILIBILI};
    /** 没匹配上时的记性：同一首别反复去搜。 */
    private static final long NEGATIVE_TTL_MS = 10L * 60L * 1_000L;
    /** 命中项的存活时间。曲库里同一首歌不会变，但换版本、上下架要能跟得上。 */
    private static final long POSITIVE_TTL_MS = 6L * 60L * 60L * 1_000L;
    private static final int CACHE_LIMIT = 96;

    /** 一条曲目的解析结论。{@code music == null} 表示"搜过了，没有"。 */
    private static final class Entry {
        final Music music;
        final long resolvedAtMs;

        Entry(Music music, long resolvedAtMs) {
            this.music = music;
            this.resolvedAtMs = resolvedAtMs;
        }
    }

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<String, Entry>();

    /** 最近一次匹配是哪个平台答上的；只进日志与状态文案。 */
    private static volatile MusicPlatform matchedPlatform;

    static MusicPlatform lastMatchedPlatform() {
        return matchedPlatform;
    }

    private ExternalTrackMatcher() {
    }

    /**
     * 解析一条外部曲目。会联网，因此<b>只能在后台线程调用</b>。
     *
     * @return 匹配到的真曲目；确实搜不到时返回 {@code null}（调用方按"无词无封面"处理）
     */
    static Music resolve(ExternalSnapshot snapshot) {
        if (snapshot == null || !snapshot.hasSession()) return null;
        String title = snapshot.title().trim();
        if (title.isEmpty()) return null;

        String key = cacheKey(snapshot);
        long now = System.currentTimeMillis();
        Entry cached = CACHE.get(key);
        if (cached != null && isStillValid(cached, now)) return cached.music;

        Music matched = search(title, primaryArtist(snapshot.normalizedArtist()), snapshot.durationMs());
        if (CACHE.size() > CACHE_LIMIT) CACHE.clear();   // 缓存只是加速，宁可全丢也别无界增长
        CACHE.put(key, new Entry(matched, now));
        if (matched == null) {
            System.out.println("[Music/SMTC] 曲库里没匹配到: " + title + " / " + snapshot.artist()
                    + "（外部时长 " + snapshot.durationMs() + "ms，问过 "
                    + MATCH_ORDER.length + " 个平台）");
        } else {
            System.out.println("[Music/SMTC] 匹配到真曲目: " + title + " -> " + matched.getStableKey()
                    + "（" + (matchedPlatform == null ? "?" : matchedPlatform.getDisplayName()) + "）");
        }
        return matched;
    }

    private static boolean isStillValid(Entry entry, long now) {
        long age = now - entry.resolvedAtMs;
        return entry.music == null ? age <= NEGATIVE_TTL_MS : age <= POSITIVE_TTL_MS;
    }

    private static Music search(String title, String artist, long externalDurationMs) {
        String keyword = artist.isEmpty() ? title : title + " " + artist;
        for (MusicPlatform platform : MATCH_ORDER) {
            List<Music> results;
            try {
                results = CrossSourceSwitcher.searchPlatform(platform, keyword, title, artist);
            } catch (Throwable failure) {
                System.err.println("[Music/SMTC] " + platform + " 检索失败: " + failure);
                continue;
            }
            Music picked = pick(results, title, externalDurationMs);
            if (picked != null) {
                matchedPlatform = platform;
                return picked;
            }
        }
        matchedPlatform = null;
        return null;
    }

    /** 从一次搜索结果里挑出「同一首歌」：曲名必须真是那句，时长要过容差那一关。 */
    private static Music pick(List<Music> results, String title, long externalDurationMs) {
        if (results == null || results.isEmpty()) return null;
        String wantedTitle = normalize(title);
        for (Music candidate : results) {
            if (candidate == null) continue;
            // 曲名必须真的是同一句：搜索结果里同名不同歌的情况靠时长那一关挡不住。
            if (!normalize(candidate.getName()).equals(wantedTitle)) continue;
            if (durationAgrees(candidate.getDuration(), externalDurationMs)) return candidate;
        }
        // 外部播放器没给时长（少数播放器不填 timeline）时放宽到曲名相同的第一条。
        if (externalDurationMs <= 0L) {
            for (Music candidate : results) {
                if (candidate != null && normalize(candidate.getName()).equals(wantedTitle)) return candidate;
            }
        }
        return null;
    }

    static boolean durationAgrees(long libraryDurationMs, long externalDurationMs) {
        if (externalDurationMs <= 0L) return true;
        if (libraryDurationMs <= 0L) return false;
        return Math.abs(libraryDurationMs - externalDurationMs) <= DURATION_TOLERANCE_MS;
    }

    /**
     * 多艺人只取第一个。
     *
     * <p>SMTC 的艺人字段常是「A / B」或「A、B」，而曲库检索里带上第二个艺人反而会把
     * 正确的结果挤掉（搜索端的字段匹配比这严格得多）。这与外部那套 sidecar 的做法一致。</p>
     */
    static String primaryArtist(String normalizedArtist) {
        if (normalizedArtist == null || normalizedArtist.isEmpty()) return "";
        String[] separators = {" / ", "、", ",", "&", "/"};
        String value = normalizedArtist;
        for (String separator : separators) {
            int index = value.indexOf(separator);
            if (index > 0) value = value.substring(0, index);
        }
        return value.trim();
    }

    private static String cacheKey(ExternalSnapshot snapshot) {
        return snapshot.normalizedTitle() + "|" + primaryArtist(snapshot.normalizedArtist())
                + "|" + (snapshot.durationMs() / 1000L);
    }

    static String normalize(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT).replace('　', ' ').replaceAll("\\s+", " ");
    }

    /** 只给测试与诊断用：清掉全部记性。 */
    static void clearCache() {
        CACHE.clear();
    }
}
