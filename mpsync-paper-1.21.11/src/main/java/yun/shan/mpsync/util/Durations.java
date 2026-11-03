package yun.shan.mpsync.util;

import java.util.Locale;

/**
 * 时长文本解析，给 {@code /mp ban <玩家> <原因> <时长>} 用。
 *
 * <p>刻意宽松：管理员在聊天栏里现敲，写 {@code 30m}、{@code 2h}、{@code 7d}、{@code 30分钟}、
 * {@code 永久} 都应该能懂。纯数字按<b>分钟</b>解释 —— 秒级封禁没有意义，而小时级的人会写单位。
 */
public final class Durations {

    private Durations() {}

    /** 表示永久。 */
    public static final long PERMANENT = 0L;
    /** 无法解析。 */
    public static final long INVALID = -1L;

    /**
     * @return 毫秒数；{@link #PERMANENT} 表示永久；{@link #INVALID} 表示不是一个时长
     */
    public static long parseMs(String raw) {
        if (raw == null) return INVALID;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return INVALID;
        if (s.equals("永久") || s.equals("perm") || s.equals("permanent")
                || s.equals("forever") || s.equals("0")) {
            return PERMANENT;
        }

        // 数字部分与单位部分分开取，中间允许空格：「30 分钟」也要能过。
        int i = 0;
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
        if (i == 0) return INVALID;
        double value;
        try {
            value = Double.parseDouble(s.substring(0, i));
        } catch (Throwable ignored) {
            return INVALID;
        }
        if (value < 0d) return INVALID;
        String unit = s.substring(i).trim();

        long ms;
        switch (unit) {
            case "":
            case "m": case "min": case "mins": case "minute": case "minutes":
            case "分": case "分钟":
                ms = (long) (value * 60_000d); break;
            case "s": case "sec": case "secs": case "second": case "seconds":
            case "秒":
                ms = (long) (value * 1000d); break;
            case "h": case "hr": case "hrs": case "hour": case "hours":
            case "时": case "小时":
                ms = (long) (value * 3_600_000d); break;
            case "d": case "day": case "days":
            case "天": case "日":
                ms = (long) (value * 86_400_000d); break;
            case "w": case "week": case "weeks":
            case "周": case "星期":
                ms = (long) (value * 604_800_000d); break;
            case "mo": case "month": case "months":
            case "月":
                ms = (long) (value * 2_592_000_000d); break;
            default:
                return INVALID;
        }
        return ms <= 0L ? PERMANENT : ms;
    }

    /** 这段文本看起来像时长吗（用来兼容「原因 时长」和「时长 原因」两种语序）。 */
    public static boolean looksLikeDuration(String raw) {
        return parseMs(raw) != INVALID;
    }

    /** 毫秒 → 中文短描述。 */
    public static String label(long ms) {
        if (ms <= 0L) return "永久";
        long m = ms / 60_000L;
        if (m < 60L) return Math.max(1L, m) + " 分钟";
        long h = m / 60L;
        if (h < 48L) return h + " 小时";
        return (h / 24L) + " 天";
    }
}