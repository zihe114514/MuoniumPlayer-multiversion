package yun.shan.mpsync.util;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 曲目入队前的校验闸门。
 *
 * <p>原来这个类叫 {@code NeteaseGuard}，只放网易云。现在的模型是「服务端只转发点歌人自己
 * 解析出来的直链」，平台对服务端没有意义 —— 它既不解析也不取流，所以平台限制被去掉了，
 * 留下的两条仍然必要：</p>
 *
 * <ul>
 *   <li>歌曲 id 得是一个能当键用的短令牌。各平台形态不同（网易云是数字、酷狗是文件 hash），
 *       所以只校验长度与字符集，不再要求纯数字。</li>
 *   <li>取流／封面链接必须是<b>公网</b> http(s) 地址。平台白名单已经取消（否则每加一个音乐
 *       平台都得改服务端），但环回／私网／链路本地地址必须挡住 —— 否则房主可以让全房成员的
 *       客户端去请求他指定的内网地址，房间会退化成一个探测器。服主如果想收窄到某几个平台，
 *       可以在 {@code mpsync.json} 的 {@code allowedHosts} 里填后缀。</li>
 * </ul>
 *
 * <p>这是防滥用闸门，不是安全边界 —— 客户端仍然可以在标题上说谎。</p>
 */
public final class SourceGuard {

    private SourceGuard() {}

    /** 网易云 CDN 后缀。老配置里存的就是这一份，用于识别「需要升级的旧配置」。 */
    public static final List<String> NETEASE_HOSTS = List.of(
            "music.126.net",
            "music.163.com",
            "126.net",
            "163.com",
            "netease.com"
    );

    /** QQ 音乐 CDN 后缀。 */
    public static final List<String> QQ_HOSTS = List.of(
            "qq.com",
            "qqmusic.qq.com",
            "stream.qqmusic.qq.com",
            "isure.stream.qqmusic.qq.com",
            "dl.stream.qqmusic.qq.com",
            "ws.stream.qqmusic.qq.com",
            "qpic.cn",
            "gtimg.cn"
    );

    /** 酷狗 CDN 后缀。 */
    public static final List<String> KUGOU_HOSTS = List.of(
            "kugou.com",
            "kgimg.com",
            "kglink.cn",
            "kugouimg.com"
    );

    /** GD 音乐台聚合源：它自己就是个代理，会给出第三方 CDN 链接。 */
    public static final List<String> GD_HOSTS = List.of(
            "gdstudio.xyz",
            "music-api.gdstudio.xyz"
    );

    /**
     * 哔哩哔哩音频与封面 CDN。音频直链由 {@code playurl} 动态返回，实测 host 在
     * {@code *.bilivideo.com}（另保留 {@code .cn} 备用域）；封面是 {@code *.hdslb.com}。
     */
    public static final List<String> BILIBILI_HOSTS = List.of(
            "bilivideo.com",
            "bilivideo.cn",
            "hdslb.com"
    );

    /** YouTube 音频直链在 {@code *.googlevideo.com}，封面在 {@code *.ytimg.com}。 */
    public static final List<String> YOUTUBE_HOSTS = List.of(
            "googlevideo.com",
            "ytimg.com"
    );

    /**
     * 常见平台 CDN 后缀的并集。<b>不再是默认值</b>（默认是「不限制，只要是公网地址」），
     * 保留它有两个用途：给服主一份想收窄时可以直接抄的参考，以及识别旧版写出来的
     * 「仅网易云」配置。
     */
    public static final List<String> DEFAULT_HOSTS = defaults();

    private static List<String> defaults() {
        List<String> all = new ArrayList<>();
        all.addAll(NETEASE_HOSTS);
        all.addAll(QQ_HOSTS);
        all.addAll(KUGOU_HOSTS);
        all.addAll(GD_HOSTS);
        all.addAll(BILIBILI_HOSTS);
        all.addAll(YOUTUBE_HOSTS);
        return List.copyOf(all);
    }

    /**
     * 歌曲 id 是否可用作键。
     *
     * <p>不再限定纯数字：酷狗按文件 hash 定位，GD 用「平台+id」，都不是数字。禁掉空白字符和
     * 冒号，因为 {@code source + ":" + songId} 是曲目的身份键，分隔符出现在 id 里会让两首歌
     * 撞成同一个键。</p>
     */
    public static boolean isValidSongId(String songId) {
        if (songId == null || songId.isBlank() || songId.length() > 64) return false;
        if (songId.equals("0")) return false;
        for (int i = 0; i < songId.length(); i++) {
            char c = songId.charAt(i);
            if (c <= ' ' || c == ':' || c == '"' || c == '\\') return false;
        }
        return true;
    }

    /** 网易云的数字 id，仅用于老客户端兼容路径的额外校验。 */
    public static boolean isNeteaseId(String songId) {
        if (songId == null || songId.isBlank() || songId.length() > 20) return false;
        for (int i = 0; i < songId.length(); i++) {
            if (songId.charAt(i) < '0' || songId.charAt(i) > '9') return false;
        }
        return !songId.equals("0");
    }

    /** 直链长度上限：帧上限是 512KB，一条 URL 没有理由超过这个数。 */
    public static final int MAX_URL_LEN = 2048;

    /**
     * 链接是否可以转发给房间。
     *
     * @param hosts 可选的 host 后缀白名单。<b>为空表示不限制平台</b>，此时只做公网校验 ——
     *              这是默认行为，因为「转发点歌人自己解析出来的直链」这个模型下，服务端根本
     *              不知道也不需要知道有哪些平台。
     */
    public static boolean isAllowedUrl(String url, List<String> hosts) {
        if (!isPublicHttpUrl(url)) return false;
        if (hosts == null || hosts.isEmpty()) return true;
        String host = hostOf(url);
        if (host == null) return false;
        for (String suffix : hosts) {
            if (suffix == null || suffix.isBlank()) continue;
            String s = suffix.toLowerCase(Locale.ROOT).trim();
            if (host.equals(s) || host.endsWith("." + s)) return true;
        }
        return false;
    }

    /**
     * 是否是一个能安全下发给所有成员的公网 http(s) 地址。
     *
     * <p>刻意<b>不做 DNS 解析</b>：解析会阻塞主线程，而且服务端自己从不取流 —— 真正去请求这条
     * URL 的是每个成员的客户端。这里挡的是「房主把内网地址下发给全房」这一类滥用，按字面判断
     * 就够，也不会因为 DNS 抖动误杀正常 CDN。</p>
     */
    public static boolean isPublicHttpUrl(String url) {
        if (url == null || url.isBlank() || url.length() > MAX_URL_LEN) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Throwable ignored) {
            return false;
        }
        // user@host 形式容易被拿来骗人眼（真正的 host 在 @ 后面），直接拒。
        if (uri.getUserInfo() != null) return false;
        String host = uri.getHost();
        if (host == null || host.isBlank()) return false;
        host = host.toLowerCase(Locale.ROOT);
        for (String bad : BLOCKED_HOST_SUFFIXES) {
            if (host.equals(bad) || host.endsWith("." + bad)) return false;
        }
        return !isPrivateAddressLiteral(host);
    }

    /** 一定指向本机或内网的名字（不含 IP 字面量，那部分交给 isPrivateAddressLiteral）。 */
    private static final List<String> BLOCKED_HOST_SUFFIXES = List.of(
            "localhost", "local", "internal", "intranet", "lan", "home.arpa", "localdomain");

    /**
     * host 是否是指向环回／私网／链路本地的 IP 字面量。域名一律返回 false。
     *
     * <p>覆盖 IPv4 的 127/8、10/8、172.16/12、192.168/16、169.254/16、0/8、100.64/10，
     * 以及 IPv6 的 ::1、::、fc00::/7、fe80::/10，另外把 IPv4 映射地址（::ffff:10.0.0.1
     * 与 ::ffff:7f00:1 两种写法）也一起判掉。</p>
     *
     * <p>IPv4 字面量按 inet_aton 的历史写法解析（十进制整数 {@code 2130706433}、十六进制
     * {@code 0x7f000001}、八进制 {@code 017700000001}、少于四段的 {@code 127.1}、结尾点
     * {@code 127.0.0.1.}）—— Java 的 InetAddress、curl、浏览器都认这些等价写法，只认规范点分
     * 十进制的话，上面每一种都能绕过这道闸把全房客户端指向环回。</p>
     */
    private static boolean isPrivateAddressLiteral(String host) {
        // URI.getHost() 对 IPv6 会带方括号，去掉后再看。
        String h = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
        if (h.indexOf(':') >= 0) return isPrivateIpv6(h);
        int[] v4 = parseIpv4(h);
        return v4 != null && isPrivateIpv4(v4);
    }

    private static boolean isPrivateIpv6(String h) {
        String s = h.toLowerCase(Locale.ROOT);
        int zone = s.indexOf('%');
        if (zone >= 0) s = s.substring(0, zone);   // fe80::1%eth0
        if (s.equals("::1") || s.equals("::")) return true;
        // IPv4 映射／兼容地址，两种写法都要判：
        //   ::ffff:127.0.0.1 —— 末段点分十进制
        //   ::ffff:7f00:1    —— 末两组十六进制拼起来就是那 4 个字节
        int lastColon = s.lastIndexOf(':');
        if (lastColon >= 0 && s.indexOf('.', lastColon) > 0) {
            int[] v4 = parseIpv4(s.substring(lastColon + 1));
            if (v4 != null && isPrivateIpv4(v4)) return true;
        } else if (s.startsWith("::ffff:")) {
            String[] g = s.split(":");
            if (g.length >= 2) {
                try {
                    int hi = Integer.parseInt(g[g.length - 2], 16);
                    int lo = Integer.parseInt(g[g.length - 1], 16);
                    int[] v4 = {(hi >> 8) & 0xFF, hi & 0xFF, (lo >> 8) & 0xFF, lo & 0xFF};
                    if (isPrivateIpv4(v4)) return true;
                } catch (Throwable ignored) {
                    // 末两组不是十六进制 —— 当它不是映射地址。
                }
            }
        }
        // 不是以 :: 开头的地址才有一个真正的首组可供判前缀（::xxxx 的首组是空的）。
        String head = s.split(":")[0];
        if (!head.isEmpty()) {
            try {
                int first = Integer.parseInt(head, 16);
                if ((first & 0xFE00) == 0xFC00) return true;    // fc00::/7 唯一本地
                if ((first & 0xFFC0) == 0xFE80) return true;    // fe80::/10 链路本地
            } catch (Throwable ignored) {
                // 首组不是十六进制 —— 不是我们要判的那两个前缀。
            }
        }
        return false;
    }

    private static boolean isPrivateIpv4(int[] p) {
        if (p[0] == 127 || p[0] == 0 || p[0] == 10) return true;
        if (p[0] == 172 && p[1] >= 16 && p[1] <= 31) return true;
        if (p[0] == 192 && p[1] == 168) return true;
        if (p[0] == 169 && p[1] == 254) return true;
        if (p[0] == 100 && p[1] >= 64 && p[1] <= 127) return true;   // CGNAT
        return p[0] >= 224;                                          // 组播／保留
    }

    /**
     * 把一个 IPv4 字面量解析成 4 段 0-255，兼容 inet_aton 的历史写法（见
     * {@link #isPrivateAddressLiteral}）。真正的域名（含字母、或某一段不是数字）一律返回 null。
     *
     * @return 4 段 0-255，或 null（不是 IPv4 字面量）。
     */
    private static int[] parseIpv4(String host) {
        if (host == null || host.isEmpty()) return null;
        String h = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;   // 结尾点
        if (h.isEmpty()) return null;
        String[] parts = h.split("\\.", -1);
        if (parts.length < 1 || parts.length > 4) return null;
        long[] vals = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            long v = parseIpv4Part(parts[i]);
            if (v < 0) return null;
            vals[i] = v;
        }
        // inet_aton：最后一段吃掉剩余所有低位字节，前面每段各占一个字节。
        long addr;
        switch (parts.length) {
            case 1:  addr = vals[0]; break;
            case 2:
                if (vals[0] > 0xFF || vals[1] > 0xFFFFFF) return null;
                addr = (vals[0] << 24) | vals[1];
                break;
            case 3:
                if (vals[0] > 0xFF || vals[1] > 0xFF || vals[2] > 0xFFFF) return null;
                addr = (vals[0] << 24) | (vals[1] << 16) | vals[2];
                break;
            default:
                if (vals[0] > 0xFF || vals[1] > 0xFF || vals[2] > 0xFF || vals[3] > 0xFF) return null;
                addr = (vals[0] << 24) | (vals[1] << 16) | (vals[2] << 8) | vals[3];
                break;
        }
        if (addr < 0 || addr > 0xFFFFFFFFL) return null;
        return new int[] {
                (int) ((addr >> 24) & 0xFF),
                (int) ((addr >> 16) & 0xFF),
                (int) ((addr >> 8) & 0xFF),
                (int) (addr & 0xFF)
        };
    }

    /** 单段：十进制 / {@code 0x} 十六进制 / {@code 0} 开头八进制。@return 值，或 -1（非法）。 */
    private static long parseIpv4Part(String s) {
        if (s == null || s.isEmpty() || s.length() > 12) return -1;
        try {
            String t = s.toLowerCase(Locale.ROOT);
            long v;
            if (t.startsWith("0x")) {
                if (t.length() == 2) return -1;
                v = Long.parseLong(t.substring(2), 16);
            } else if (t.charAt(0) == '0' && t.length() > 1) {
                v = Long.parseLong(t.substring(1), 8);
            } else {
                v = Long.parseLong(t, 10);
            }
            return (v < 0 || v > 0xFFFFFFFFL) ? -1 : v;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String hostOf(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? null : h.toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
