package com.muoniumplayer.core.ncm.lxsource;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 音质口径转换：MuoniumPlayer 的 8 档 ↔ LX 的 4 档。
 *
 * <p>LX 只有 {@code 128k / 320k / flac / flac24bit} 四档，这是<b>协议</b>而不是实现细节：
 * 脚本在 inited 里声明的、宿主在 request 里传的，都只能是这四个字符串之一
 * （见 preload 的 {@code supportQualitys}）。所以本机的 HiRes / 母带 / 沉浸声这些档次
 * 只能并到 flac24bit，反过来脚本给不出高档时也要能一档一档退下来。</p>
 */
public final class LxQuality {

    /** 从高到低，降级就沿着这个顺序走。 */
    private static final List<String> LADDER = Arrays.asList("flac24bit", "flac", "320k", "128k");

    private LxQuality() {
    }

    /**
     * @param muQuality {@code Quality} 枚举的名字（STANDARD / EXHIGH / LOSSLESS / HIRES ...）
     */
    public static String fromMuQuality(String muQuality) {
        String name = muQuality == null ? "" : muQuality.trim().toUpperCase(Locale.ROOT);
        switch (name) {
            case "STANDARD":
                return "128k";
            case "HIGHER":
            case "EXHIGH":
            case "SKY":
                // 192k 与「沉浸环绕声」在 LX 里都没有对应档位，归到有损最高的 320k。
                return "320k";
            case "LOSSLESS":
                return "flac";
            case "HIRES":
            case "JYEFFECT":
            case "JYMASTER":
                return "flac24bit";
            default:
                return "flac";
        }
    }

    /**
     * 在脚本声明支持的音质里挑一个：优先不超过请求档位的最高档，都超了就取脚本最低的那一档。
     *
     * @return null 表示脚本对这个源没有声明任何音质（调用方应当放弃）
     */
    public static String pick(String requested, List<String> declared) {
        if (declared == null || declared.isEmpty()) return null;
        int from = LADDER.indexOf(requested);
        if (from < 0) from = 0;
        for (int i = from; i < LADDER.size(); i++) {
            if (declared.contains(LADDER.get(i))) return LADDER.get(i);
        }
        // 请求的档位比脚本支持的所有档位都低（例如只声明了 flac 而这里要 128k）：
        // 宁可给更高音质也不要放弃，用户点的是「能听」。
        for (int i = LADDER.size() - 1; i >= 0; i--) {
            if (declared.contains(LADDER.get(i))) return LADDER.get(i);
        }
        return null;
    }

    /**
     * 容器猜测。链接后缀可信时优先用后缀，否则按音质推。
     *
     * <p>需要这一步是因为宿主的 {@code normalizePlayUrl} 认不出容器就会整条丢弃，
     * 而脚本返回的签名直链经常没有扩展名。</p>
     */
    public static String formatFor(String url, String lxQuality) {
        String extension = extensionOf(url);
        if (isKnownContainer(extension)) return extension;
        return "flac".equals(lxQuality) || "flac24bit".equals(lxQuality) ? "flac" : "mp3";
    }

    /**
     * LX 档位 → 宓主 {@code Quality} 枚举名的小写形式。
     *
     * <p>宓主的「最高音质」徒标与播放音质判定读的都是网易云的 level 口径
     * （lossless / exhigh / ...）。直接把容器名喂进去的话，320k 的 mp3 会被归成「标准」，
     * 徒标就比实际低一档。</p>
     */
    public static String muLevelFor(String lxQuality) {
        if ("flac24bit".equals(lxQuality)) return "hires";
        if ("flac".equals(lxQuality)) return "lossless";
        if ("320k".equals(lxQuality)) return "exhigh";
        return "standard";
    }

    private static String extensionOf(String url) {
        String clean = url == null ? "" : url.toLowerCase(Locale.ROOT);
        int query = clean.indexOf('?');
        if (query >= 0) clean = clean.substring(0, query);
        int fragment = clean.indexOf('#');
        if (fragment >= 0) clean = clean.substring(0, fragment);
        int dot = clean.lastIndexOf('.');
        return dot >= 0 && dot < clean.length() - 1 ? clean.substring(dot + 1) : "";
    }

    private static boolean isKnownContainer(String extension) {
        return "mp3".equals(extension) || "flac".equals(extension) || "wav".equals(extension)
                || "aac".equals(extension) || "m4a".equals(extension) || "mp4".equals(extension);
    }
}