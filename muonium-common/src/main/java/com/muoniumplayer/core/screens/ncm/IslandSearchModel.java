package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 灵动岛快捷搜索的<em>纯逻辑</em>层：状态机、形变时钟、防抖与竞态防护。
 *
 * <p>刻意不 import 任何 Minecraft / Skija 类 —— 状态推进是墙钟时间的纯函数，
 * 可以离线断言（{@code IslandSearchStaticTest}）。绘制与转场钩子的接线在
 * {@link IslandSearchTransition}（几何）与 {@link IslandSearchOverlay}（Screen 与控件树）。</p>
 *
 * <p>与 {@link PlayerIslandTransition} 同一条时间驱动思路：只有一个 {@code t ∈ [0,1]} 的时钟，
 * 几何都是 t 的纯函数；中途反向（正在展开时按 Esc）原路折回，不跳变。</p>
 */
public final class IslandSearchModel {

    private static final int IDLE = 0, OPENING = 1, OPEN = 2, CLOSING = 3;

    /** 展开 / 收回各占的墙钟时长（ms）。比播放器转场短：搜索胶囊比整屏表面小得多。 */
    static final double OPEN_MS = 420.0;
    static final double CLOSE_MS = 320.0;

    /** 「输入即搜」的防抖：停笔这么久才发网络请求（联想词另有自己的 180ms 防抖）。 */
    public static final long SEARCH_DEBOUNCE_MILLIS = 300L;
    /** 空关键词不发请求；清空输入框时结果区直接清空。 */
    static final int KEYWORD_MIN_LENGTH = 1;

    private static int state = IDLE;
    /** 1 = 完全展开。一帧只在 {@link #tick(long)} 里推进一次。 */
    private static double t = 0.0;

    /** 单调时钟（{@code System.nanoTime()} 域），掉帧时钳 {@link #MAX_FRAME_DELTA_MS}。 */
    private static long lastTickNanos;

    private static long lastInputChangedAt;
    /** 已经发出去的请求对应的关键词；防抖到期时比较它，避免同词重复发。 */
    private static String searchedKeyword = "";
    private static String pendingKeyword = "";

    /** 与 NCMScreen 的 NavigationRequestGate 同构：新请求使旧结果失效。包内共享一份。 */
    private static final NavigationRequestGate REQUEST_GATE = new NavigationRequestGate();

    /** 结果列表。搜索结果语义（{@code searchResultList}）让 MusicWidget 的三键行为全部生效。 */
    private static final PlayList RESULT_PLAYLIST = createResultPlaylist();

    /** 最新一批结果的引用（与 RESULT_PLAYLIST.musics 同一内容，供 Overlay 决定要不要重建行）。 */
    private static volatile List<Music> results = new CopyOnWriteArrayList<>();
    /** true = 已发出请求但结果未落地（Overlay 借它显示加载态）。 */
    private static volatile boolean awaitingResults;
    /** 结果落地的代次：每落地一次 +1。Overlay 比对代次决定是否重建控件树。 */
    private static volatile long resultsRevision;

    private IslandSearchModel() {
    }

    // ------------------------------------------------------------------ 生命周期

    /** 从灵动岛锚点开始展开。幂等：已打开时再调不重置时钟。 */
    static void beginOpen() {
        if (state == OPEN || state == OPENING) return;
        if (state == IDLE) {
            t = 0.0;
        }
        lastTickNanos = System.nanoTime();
        state = OPENING;
    }

    /** 开始收回。展开中按下 Esc 立即反向；{@code t} 保留，原路折回。 */
    static void beginClose() {
        if (state == OPENING || state == OPEN) {
            lastTickNanos = System.nanoTime();
            state = CLOSING;
        }
    }

    /** 外部直接换屏（例如搜索打开期间用户开了播放器）：立即复位，不播动画。 */
    static void reset() {
        state = IDLE;
        t = 0.0;
        lastTickNanos = 0L;
        lastInputChangedAt = 0L;
        pendingKeyword = "";
        searchedKeyword = "";
        REQUEST_GATE.invalidate();
        awaitingResults = false;
        clearResults();
    }

    /** 每帧推进一次时钟。返回推进后的状态。 */
    static void tick(long nowNanos) {
        double deltaMs = lastTickNanos == 0L ? 0.0 : (nowNanos - lastTickNanos) / 1_000_000.0;
        lastTickNanos = nowNanos;
        // 与 PlayerIslandTransition 同一条规矩（含 50ms 上限）：这条动画是用户盯着看的，
        // t 一帧跳太多读起来就是「跳过了动画」；真冻结宁可尾巴在墙上时钟上多走几十毫秒。
        deltaMs = Math.min(50.0, Math.max(0.0, deltaMs));

        if (state == OPENING) {
            t = Math.min(1.0, t + deltaMs / OPEN_MS);
            if (t >= 1.0) {
                t = 1.0;
                state = OPEN;
            }
        } else if (state == CLOSING) {
            t = Math.max(0.0, t - deltaMs / CLOSE_MS);
            if (t <= 0.0) {
                t = 0.0;
                state = IDLE;
            }
        }
    }

    public static boolean isOpen() {
        return state == OPEN;
    }

    public static boolean isAnimating() {
        return state == OPENING || state == CLOSING;
    }

    public static boolean isIdle() {
        return state == IDLE;
    }

    /** 形变时钟 0..1。几何都是它的纯函数。 */
    public static double progress() {
        return t;
    }

    // ------------------------------------------------------------------ 防抖

    /**
     * 输入变化时登记。 {@code nowMillis} 是 {@code System.currentTimeMillis()} 域。
     * 空关键词立即清结果（不占防抖窗口）。
     */
    public static void onInputChanged(String text, long nowMillis) {
        lastInputChangedAt = nowMillis;
        pendingKeyword = text == null ? "" : text.trim();
        if (pendingKeyword.length() < KEYWORD_MIN_LENGTH) {
            searchedKeyword = "";
            REQUEST_GATE.invalidate();
            awaitingResults = false;
            clearResults();
        }
    }

    /**
     * 防抖到期判定。返回要搜的关键词，{@code null} = 这一帧不用发请求。
     *
     * <p>同一关键词不重复发（防抖到期但词没变 → null）；发出去的瞬间登记为已搜。</p>
     */
    public static String consumeDueSearch(long nowMillis) {
        if (pendingKeyword.length() < KEYWORD_MIN_LENGTH) return null;
        if (nowMillis - lastInputChangedAt < SEARCH_DEBOUNCE_MILLIS) return null;
        if (pendingKeyword.equals(searchedKeyword)) return null;
        searchedKeyword = pendingKeyword;
        return pendingKeyword;
    }

    /** 当前正在展示的关键词（最后一个已发出的请求）。 */
    public static String searchedKeyword() {
        return searchedKeyword;
    }

    /** 输入框里的最新词（可能还没发出去）。联想面板拿它决定显隐。 */
    public static String pendingKeyword() {
        return pendingKeyword;
    }

    /** 回车立即搜：绕过防抖窗口（词相同也重发）。返回要搜的关键词。 */
    public static String consumeImmediateSearch(String text) {
        String keyword = text == null ? "" : text.trim();
        if (keyword.length() < KEYWORD_MIN_LENGTH) return null;
        pendingKeyword = keyword;
        searchedKeyword = keyword;
        return keyword;
    }

    // ------------------------------------------------------------------ 结果

    /** 搜索结果列表的固定外壳。行控件直接复用 MusicWidget（三键行为白得）。 */
    public static PlayList resultPlaylist() {
        return RESULT_PLAYLIST;
    }

    /** 请求 ticket。与 NCMScreen.beginContentRequest 同语义。 */
    static long beginRequest() {
        awaitingResults = true;
        return REQUEST_GATE.begin();
    }

    static boolean isRequestCurrent(long ticket) {
        return REQUEST_GATE.isCurrent(ticket);
    }

    /** 结果落地（主线程）。旧请求回来时 ticket 已失效，直接丢弃。 */
    static void deliverResults(long ticket, List<Music> search) {
        if (!REQUEST_GATE.isCurrent(ticket)) return;
        awaitingResults = false;
        results = search == null ? new CopyOnWriteArrayList<>() : new CopyOnWriteArrayList<>(search);
        RESULT_PLAYLIST.musics.clear();
        RESULT_PLAYLIST.musics.addAll(results);
        resultsRevision++;
    }

    /** 关闭 / 清空时把结果一并撤下。 */
    static void clearResults() {
        results = new CopyOnWriteArrayList<>();
        RESULT_PLAYLIST.musics.clear();
        resultsRevision++;
        awaitingResults = false;
    }

    public static List<Music> results() {
        return results;
    }

    public static boolean isAwaitingResults() {
        return awaitingResults;
    }

    /** 结果代次。Overlay 每帧比对，变了才重建行（MusicWidget 树的创建是贵的）。 */
    public static long resultsRevision() {
        return resultsRevision;
    }

    // ------------------------------------------------------------------ 工厂

    /**
     * 与 NavigateBar.submitSearch 同构的搜索结果外壳：
     * {@code searchMode} 表示曲目已在内存、{@code searchResultList} 让
     * {@code CloudMusic.playFromSearchSelection} 的「起播后接最近播放」行为生效。
     *
     * <p>{@code PlayList} 是 {@code @Data} + 8 个 final 字段，Lombok 生成的必填构造器
     * 就是 PersonalFmPanel.fmPlaylist 用的那个 8 参形态；这里的参数照 PersonalFmPanel
     * 的「虚拟单」惯例填零值，反正搜索结果的可见信息全在每一行的 {@code Music} 上。</p>
     */
    private static PlayList createResultPlaylist() {
        PlayList playList = new PlayList(-900002L, "搜索", "", 0, 0L, null, "", 0L);
        playList.setSearchMode(true);
        playList.setSearchResultList(true);
        playList.musics = new CopyOnWriteArrayList<>();
        return playList;
    }

    // ------------------------------------------------------------------ 纯函数（测试钉）

    /** 防抖窗口判定。package-visible 供测试直接断言。 */
    static boolean isSearchDue(long nowMillis, long inputChangedAt, String keyword, long debounce) {
        if (keyword == null || keyword.trim().length() < KEYWORD_MIN_LENGTH) return false;
        return nowMillis - inputChangedAt >= debounce;
    }

    /** 形变几何插值：ease 曲线之外的线性部分。矩形五元组按中心对齐插值。 */
    static double[] lerpBounds(double[] from, double[] to, double fraction) {
        double f = Math.max(0.0, Math.min(1.0, fraction));
        double width = from[2] + (to[2] - from[2]) * f;
        double height = from[3] + (to[3] - from[3]) * f;
        double centerX = from[0] + from[2] * .5 + (to[0] + to[2] * .5 - (from[0] + from[2] * .5)) * f;
        double centerY = from[1] + from[3] * .5 + (to[1] + to[3] * .5 - (from[1] + from[3] * .5)) * f;
        double radius = from[4] + (to[4] - from[4]) * f;
        return new double[]{centerX - width * .5, centerY - height * .5, width, height, radius};
    }
}
