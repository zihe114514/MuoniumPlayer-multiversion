package com.muoniumplayer.core.ncm.music;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 歌词候选的加权匹配打分。
 *
 * <p>为什么不能沿用"曲名对上就算命中"的布尔判断:同一个曲名下会挂着原曲、Live、Remix、翻唱、
 * 各种精编版,时间轴各不相同。一旦贴错版本,逐字歌词会比没有歌词更难用——扫光动画、发光、
 * 逐字高亮全部错位。所以这里给每个候选算一个 0~100 的分,只有越过 {@link #MIN_ACCEPT_SCORE}
 * 才采用,<b>宁可退回行级歌词,也不给错歌的逐字歌词</b>。</p>
 *
 * <p>模型(参数取自 本项目 的 {@code utils/lyrics/matchScore.ts}):</p>
 * <pre>
 * 身份分 = 曲名相似度 × 45 + 歌手相似度 × 25 + 专辑得分 × 30
 * 最终分 = 身份分 × 时长乘数          （上限 100,四舍五入）
 *
 * 曲名或"歌手/专辑"都没对上时,身份分封顶 74 —— 必然低于 75 的门槛,直接被拒
 *
 * 时长乘数:差 ≤1s ×1.00 / ≤3s ×0.95 / ≤5s ×0.75 / ≤10s ×0.35 / 更多 ×0.10
 * </pre>
 *
 * <p>三处与 Folia 不同的取舍,都是为了不把本来正确的候选误杀:</p>
 * <ol>
 *   <li><b>专辑缺失算满分。</b>Folia 在"本地有专辑、候选没给专辑"时给 0 分。AMLL 词库的专辑名是
 *       Apple Music 的写法,和本地曲库的专辑名经常对不上,照抄会把一批正确候选压到门槛之下。</li>
 *   <li><b>专辑不一致只扣一半权重。</b>专辑是用来区分同名作品的,不是独立否决项:跨平台的
 *       精选集、引进版命名差异很常见。</li>
 *   <li><b>候选没有时长时乘数 0.9。</b>AMLL 搜索结果不带时长,不奖不罚,只是比有确切时长时略保守。</li>
 * </ol>
 *
 * <p>本类只做纯计算,不做网络请求,任何输入都不抛异常。</p>
 */
final class LyricMatchScore {

    /** 接受门槛。低于它的候选一律放弃,继续交给后面的兜底链路。 */
    static final int MIN_ACCEPT_SCORE = 75;

    private static final double TITLE_WEIGHT = 45.0;
    private static final double ARTIST_WEIGHT = 25.0;
    private static final double ALBUM_WEIGHT = 30.0;
    /** 双方都有专辑但明显不一致时的专辑得分比例。 */
    private static final double ALBUM_MISMATCH_WEIGHT_RATIO = 0.5;

    /** 身份分未命中时的封顶值:必然低于 {@link #MIN_ACCEPT_SCORE}。 */
    private static final double IDENTITY_MISS_CAP = 74.0;

    private static final double TITLE_MATCH_MIN_SIMILARITY = 0.65;
    private static final double ARTIST_MATCH_MIN_SIMILARITY = 0.50;
    private static final double ALBUM_MATCH_MIN_SIMILARITY = 0.65;

    /** 候选没有时长信息时的乘数。 */
    private static final double UNKNOWN_DURATION_MULTIPLIER = 0.9;

    private LyricMatchScore() {
    }

    /** 本地歌曲这一侧。时长单位为毫秒,{@code <= 0} 表示未知。 */
    static final class Target {
        final String title;
        final String artist;
        final String album;
        final long durationMillis;

        Target(String title, String artist, String album, long durationMillis) {
            this.title = title == null ? "" : title;
            this.artist = artist == null ? "" : artist;
            this.album = album == null ? "" : album;
            this.durationMillis = Math.max(0L, durationMillis);
        }
    }

    /**
     * 候选这一侧。曲名与歌手都是列表:同一首歌在不同平台会有多个写法(AMLL 的
     * {@code musicNames} 会同时收录正式名和"某某剧主题曲"这种说明),取相似度最高的一个。
     */
    static final class Candidate {
        final List<String> titles;
        final List<String> artists;
        final String album;
        final long durationMillis;

        Candidate(List<String> titles, List<String> artists, String album, long durationMillis) {
            this.titles = titles == null ? Collections.<String>emptyList() : titles;
            this.artists = artists == null ? Collections.<String>emptyList() : artists;
            this.album = album == null ? "" : album;
            this.durationMillis = Math.max(0L, durationMillis);
        }

        static Candidate of(String title, List<String> artists, String album, long durationMillis) {
            List<String> titles = new ArrayList<String>(1);
            if (title != null && !title.trim().isEmpty()) titles.add(title);
            return new Candidate(titles, artists, album, durationMillis);
        }
    }

    /** 打分结果。除了总分还带上各分量,调用方可以打一行可读的日志。 */
    static final class Result {
        final int score;
        final boolean titleMatched;
        final boolean artistMatched;
        /** {@code null} 表示无法判断(本地或候选缺专辑)。 */
        final Boolean albumMatched;
        final double titleSimilarity;
        final double artistSimilarity;
        final double albumSimilarity;
        final double durationMultiplier;

        private Result(int score, boolean titleMatched, boolean artistMatched, Boolean albumMatched,
                       double titleSimilarity, double artistSimilarity, double albumSimilarity,
                       double durationMultiplier) {
            this.score = score;
            this.titleMatched = titleMatched;
            this.artistMatched = artistMatched;
            this.albumMatched = albumMatched;
            this.titleSimilarity = titleSimilarity;
            this.artistSimilarity = artistSimilarity;
            this.albumSimilarity = albumSimilarity;
            this.durationMultiplier = durationMultiplier;
        }

        boolean accepted() {
            return score >= MIN_ACCEPT_SCORE;
        }

        /** 一行式的拒绝原因,便于在日志里一眼看出候选是怎么被否掉的。 */
        String describe() {
            return "score=" + score
                    + " title=" + round(titleSimilarity)
                    + " artist=" + round(artistSimilarity)
                    + " album=" + (albumSimilarity < 0 ? "n/a" : String.valueOf(round(albumSimilarity)))
                    + " duration=x" + durationMultiplier;
        }

        private static double round(double value) {
            return Math.round(value * 100.0) / 100.0;
        }
    }

    static Result evaluate(Target target, Candidate candidate) {
        if (target == null || candidate == null) {
            return new Result(0, false, false, null, 0.0, 0.0, -1.0, UNKNOWN_DURATION_MULTIPLIER);
        }

        double titleSimilarity = bestTitleSimilarity(target.title, candidate.titles);
        double artistSimilarity = LyricMatchText.artistSimilarity(target.artist, candidate.artists);

        boolean hasTargetAlbum = !LyricMatchText.isBlank(target.album);
        boolean hasCandidateAlbum = !LyricMatchText.isBlank(candidate.album);
        double albumSimilarity = -1.0;
        if (hasTargetAlbum && hasCandidateAlbum) {
            albumSimilarity = LyricMatchText.similarity(target.album, candidate.album, true);
        }

        double albumScore;
        if (albumSimilarity < 0.0) {
            albumScore = ALBUM_WEIGHT;                    // 无法判断,不奖不罚
        } else if (albumSimilarity >= ALBUM_MATCH_MIN_SIMILARITY) {
            albumScore = ALBUM_WEIGHT;                    // 确认一致
        } else {
            albumScore = ALBUM_WEIGHT * ALBUM_MISMATCH_WEIGHT_RATIO;
        }

        boolean titleMatched = titleSimilarity >= TITLE_MATCH_MIN_SIMILARITY;
        boolean artistMatched = LyricMatchText.isBlank(target.artist)
                || artistSimilarity >= ARTIST_MATCH_MIN_SIMILARITY;
        Boolean albumMatched = !hasTargetAlbum
                ? null
                : (hasCandidateAlbum ? Boolean.valueOf(albumSimilarity >= ALBUM_MATCH_MIN_SIMILARITY) : null);

        double identity = titleSimilarity * TITLE_WEIGHT
                + artistSimilarity * ARTIST_WEIGHT
                + albumScore;
        // 曲名或身份(歌手/专辑)有一头没对上,就把身份分压到门槛之下。这是"宁可没有歌词"的兜底。
        if (!titleMatched || !(artistMatched || Boolean.TRUE.equals(albumMatched))) {
            identity = Math.min(identity, IDENTITY_MISS_CAP);
        }

        double multiplier = durationMultiplier(target.durationMillis, candidate.durationMillis);
        double finalScore = identity * multiplier;
        int score = (int) Math.round(Math.min(100.0, Math.max(0.0, finalScore)));

        return new Result(score, titleMatched, artistMatched, albumMatched,
                titleSimilarity, artistSimilarity, albumSimilarity, multiplier);
    }

    /** 候选取多个写法时,只要有一个能对上就算对上。 */
    private static double bestTitleSimilarity(String target, List<String> candidates) {
        if (LyricMatchText.isBlank(target)) return 0.0;
        double best = 0.0;
        for (String candidate : candidates) {
            double value = LyricMatchText.similarity(target, candidate, true);
            if (value > best) best = value;
        }
        return best;
    }

    private static double durationMultiplier(long targetMillis, long candidateMillis) {
        if (targetMillis <= 0L || candidateMillis <= 0L) return UNKNOWN_DURATION_MULTIPLIER;
        long difference = Math.abs(targetMillis - candidateMillis);
        if (difference <= 1_000L) return 1.00;      // 基本可认定是同一个版本
        if (difference <= 3_000L) return 0.95;      // 前奏/尾奏裁剪
        if (difference <= 5_000L) return 0.75;
        if (difference <= 10_000L) return 0.35;     // 多半是 Live / 剪辑版
        return 0.10;                                // 原曲与 Remix/加长版差得就是这么多
    }
}
