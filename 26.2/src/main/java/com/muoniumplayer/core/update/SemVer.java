package com.muoniumplayer.core.update;

/**
 * 版本号比较。
 *
 * <p>只服务于「远端清单里的 latest 是否比本地版本新」这一个问题，所以刻意不实现完整的 SemVer 规范：
 * 主段按点拆开逐段比数字（缺的段当 0，所以 {@code 1.3} 与 {@code 1.3.0} 相等），
 * {@code -} 或 {@code +} 之后的部分算预发布标记，同主段时预发布小于正式版。</p>
 *
 * <p>为什么不能直接用 {@code String#compareTo}：{@code "1.10.0".compareTo("1.9.0") < 0}，
 * 字符串比较会认为 1.10.0 比 1.9.0 旧，于是版本一过 9 就再也提示不出来。</p>
 */
public final class SemVer {

    private SemVer() {
    }

    /** candidate 是否严格新于 current。任一侧解析不出数字时按 0 处理，不抛异常。 */
    public static boolean isNewer(String candidate, String current) {
        return compare(candidate, current) > 0;
    }

    /** 负数表示 left 更旧，0 表示同版本，正数表示 left 更新。 */
    public static int compare(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);

        int core = compareCore(mainPart(a), mainPart(b));
        if (core != 0) {
            return core;
        }

        String preA = preRelease(a);
        String preB = preRelease(b);
        if (preA.isEmpty() && preB.isEmpty()) {
            return 0;
        }
        // 1.4.0 > 1.4.0-rc1：正式版永远新于同号预发布版。
        if (preA.isEmpty()) {
            return 1;
        }
        if (preB.isEmpty()) {
            return -1;
        }
        int cmp = preA.compareToIgnoreCase(preB);
        return cmp == 0 ? 0 : (cmp < 0 ? -1 : 1);
    }

    /** 本地版本带预发布标记（{@code -dev} / {@code -rc1} / {@code +local}）时不做更新检查。 */
    public static boolean isPreRelease(String version) {
        return !preRelease(normalize(version)).isEmpty();
    }

    private static String normalize(String version) {
        if (version == null) {
            return "";
        }
        String trimmed = version.trim();
        if (trimmed.startsWith("v") || trimmed.startsWith("V")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed;
    }

    private static String mainPart(String version) {
        int cut = firstIndexOf(version, '-', '+');
        return cut < 0 ? version : version.substring(0, cut);
    }

    private static String preRelease(String version) {
        int cut = firstIndexOf(version, '-', '+');
        return cut < 0 ? "" : version.substring(cut + 1);
    }

    private static int firstIndexOf(String value, char first, char second) {
        int a = value.indexOf(first);
        int b = value.indexOf(second);
        if (a < 0) {
            return b;
        }
        if (b < 0) {
            return a;
        }
        return Math.min(a, b);
    }

    private static int compareCore(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        int segments = Math.max(a.length, b.length);
        for (int index = 0; index < segments; index++) {
            long va = index < a.length ? leadingNumber(a[index]) : 0L;
            long vb = index < b.length ? leadingNumber(b[index]) : 0L;
            if (va != vb) {
                return va < vb ? -1 : 1;
            }
        }
        return 0;
    }

    /** 取一段开头的连续数字；整段都不是数字（例如空串）时当 0。 */
    private static long leadingNumber(String segment) {
        int end = 0;
        while (end < segment.length() && segment.charAt(end) >= '0' && segment.charAt(end) <= '9') {
            end++;
        }
        if (end == 0) {
            return 0L;
        }
        try {
            return Long.parseLong(segment.substring(0, end));
        } catch (NumberFormatException overflow) {
            return 0L;
        }
    }
}
