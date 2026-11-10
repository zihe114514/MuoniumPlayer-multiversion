package com.muoniumplayer.core.ncm.music;

/**
 * 按播放器个性调校时间轴的稳定器（镜像路径专用）。
 *
 * <p>SMTC 的时间轴质量因播放器而异，而且差别是结构性的，Lyricify 官方文档整页整页地
 * 写这些坑（docs.lyricify.app/lyricify-lite/supported-apps）。我们不做逐个特判的散装
 * if，把已知个性收敛成一张表，读数进来先过一遍这里：</p>
 *
 * <ul>
 *   <li><b>Apple Music (Win)</b>：时间轴精度极低、上报不稳定、会异常跳变——Lyricify
 *       的对策是 Timeline Stabilizer + 建议 -500~-400ms 会话延迟。我们对齐：读数延迟
 *       补偿 450ms + 冻结外推窗口放宽到 8 秒（它的上报间隔长且抖）。</li>
 *   <li><b>Spotify</b>：本机播放时间轴完美；Spotify Connect 接收端进度恒 0:00 是
 *       官方客户端多年已知 bug（community.spotify.com 多帖）。镜像踩到接收端场景时
 *       位置一路冻结在 0，这里放宽外推窗口让歌词别停摆；真 seek 之后播放器恢复上报
 *       就自然回到正常跟随。</li>
 *   <li><b>网易云 Win32</b>：原生无 SMTC（需 InfLink-rs 插件），插件补的时间轴也有
 *       缺失拍——放宽外推窗口。</li>
 *   <li><b>浏览器</b>：多数不实现 timeline（位置恒 0、seek 时 EndTime 瞬时归 0）。
 *       位置恒 0 不是外推能救的（没有锚点），交给曲库时长兜底；EndTime 归 0 已在
 *       助手侧做了迟滞。</li>
 *   <li><b>QQ音乐</b>：Lyricify 评级 Perfect，什么也不用做。</li>
 * </ul>
 *
 * <p>三条纪律：只影响镜像路径（内部播放与 Spotify Connect 镜像不走这里）；
 * 任何补偿都不能把读数推到曲库时长之外；判定只看 AUMID 的片段匹配，配置表查不到
 * 就原样放行——未知播放器宁可保守。</p>
 */
public final class SmtcTimelineStabilizer {

    /** Apple Music 的读数滞后补偿。Lyricify 建议 -500~-400ms，取中间偏保守的 450。 */
    static final long APPLE_MUSIC_DELAY_COMPENSATION_MS = 450L;
    /** Apple Music 的外推窗口：它上报间隔长且抖，短窗口会让歌词一顿一顿。 */
    static final long APPLE_MUSIC_MAX_EXTRAPOLATE_MS = 8_000L;
    /** 网易云（InfLink-rs）与 Spotify Connect 接收端的外推窗口。 */
    static final long NETEASE_MAX_EXTRAPOLATE_MS = 4_000L;
    /** 未知播放器维持引擎默认的 500ms：宁可保守也不猜。 */
    static final long DEFAULT_MAX_EXTRAPOLATE_MS = 500L;

    // ── 用户覆盖（HUD 设置页的滑条写这里）──────────────────────────────────
    //
    // 内置表来自 Lyricify 的文档与实测，但每台机器的声源/输出链路延迟不同，
    // 450ms 只是建议中值——最终以玩家自己的耳朵校准为准。覆盖值是全局的：
    // 作用于「当前镜像到的那台播放器」，镜像目标变了仍按内置表+覆盖的交集生效。

    /** 延迟补偿覆盖（毫秒）。null = 跟随内置表。 */
    private static volatile Long userDelayMs;
    /** 外推窗口覆盖（毫秒）。null = 跟随内置表。 */
    private static volatile Long userWindowMs;

    /** 设置页调用：写入覆盖值并持久化到 HudConfig。 */
    public static void setUserDelayMs(int value) {
        userDelayMs = (long) Math.max(0, Math.min(2_000, value));
        com.muoniumplayer.core.settings.HudConfig.smtcTimelineDelayMs = userDelayMs.intValue();
        com.muoniumplayer.core.settings.HudConfig.save();
    }

    public static void setUserWindowMs(int value) {
        userWindowMs = (long) Math.max(500, Math.min(30_000, value));
        com.muoniumplayer.core.settings.HudConfig.smtcTimelineWindowMs = userWindowMs.intValue();
        com.muoniumplayer.core.settings.HudConfig.save();
    }

    /** 从 HudConfig 恢复覆盖值（进世界/桥初始化时调一次）。 */
    static void restoreUserOverrides(int delayMs, int windowMs) {
        if (delayMs >= 0) userDelayMs = (long) Math.min(2_000, delayMs);
        if (windowMs >= 0) userWindowMs = (long) Math.max(500, Math.min(30_000, windowMs));
    }

    static boolean hasUserDelayOverride() {
        return userDelayMs != null;
    }

    static boolean hasUserWindowOverride() {
        return userWindowMs != null;
    }

    /** 当前生效的延迟补偿（含覆盖），给设置页与镜像路径用。 */
    static int effectiveDelayMs(String aumid) {
        return (int) delayCompensationMs(aumid);
    }

    /** 当前生效的外推窗口（含覆盖），给设置页与镜像路径用。 */
    static int effectiveWindowMs(String aumid) {
        return (int) maxExtrapolateMs(aumid);
    }

    private SmtcTimelineStabilizer() {
    }

    /** 某台播放器读数延迟补偿（毫秒，正值 = 读数比真实播放位置滞后这么多）。 */
    static long delayCompensationMs(String aumid) {
        if (userDelayMs != null) return userDelayMs;
        if (matches(aumid, "AppleMusic")) return APPLE_MUSIC_DELAY_COMPENSATION_MS;
        return 0L;
    }

    /** 某台播放器允许的冻结外推窗口（毫秒）。 */
    static long maxExtrapolateMs(String aumid) {
        if (userWindowMs != null) return userWindowMs;
        if (matches(aumid, "AppleMusic")) return APPLE_MUSIC_MAX_EXTRAPOLATE_MS;
        if (matches(aumid, "CloudMusic") || matches(aumid, "Netease")
                || matches(aumid, "netease")) return NETEASE_MAX_EXTRAPOLATE_MS;
        // Spotify 本机播放不需要；但无法从 AUMID 区分本机与 Connect 接收端，
        // 统一放宽——本机播放每秒都在推新位置，外推窗口根本用不到，不会误伤。
        if (matches(aumid, "Spotify")) return NETEASE_MAX_EXTRAPOLATE_MS;
        return DEFAULT_MAX_EXTRAPOLATE_MS;
    }

    /**
     * AUMID 片段匹配。AUMID 形如 {@code AppleMusic!AppleMusic}、
     * {@code {6D809377-...}\\Netease\\CloudMusic\\CloudMusic.exe}、{@code Spotify.exe}，
     * 片段小写化后做包含匹配——这与音量合成器那侧的 Tokens/MatchScore 同一族做法。
     */
    static boolean matches(String aumid, String token) {
        if (aumid == null || aumid.isEmpty()) return false;
        return aumid.toLowerCase(java.util.Locale.ROOT).contains(token.toLowerCase(java.util.Locale.ROOT));
    }
}
