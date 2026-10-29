package com.muoniumplayer.core.ncm.apple;

import com.muoniumplayer.core.settings.ConfigPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Netscape cookies.txt 的解析、校验与落盘（gamdl 的凭据入口）。
 *
 * <p><b>为什么 cookies.txt 是唯一凭据入口</b>：gamdl 的账号与播放请求都从浏览器导出的
 * cookies 文件取身份，里面真正起作用的是 {@code media-user-token}（AMP API 的账号态）
 * 和若干会话 cookie。本类把它拆成两份用途——API 侧只留 media-user-token（进
 * {@link AppleConfigStore}），gamdl 侧把原文写到
 * {@link ConfigPaths#APPLE_MUSIC_DIR}/cookies.txt（它要文件路径）。</p>
 *
 * <p><b>敏感等级</b>：这是整份明文浏览器 cookie，等价于 Apple ID 的音乐会话。只落盘在
 * 本机 config 目录、只传给本机的 gamdl 进程，日志与异常信息绝不带 cookie 内容。</p>
 */
public final class AppleCookieSupport {

    public static final String COOKIES_FILE_NAME = "cookies.txt";

    /** 解析结果。mediaUserToken 为空 = 这份 cookies 里没有账号态（游客 cookies，只能拿公开目录）。 */
    public static final class Parsed {
        public final String mediaUserToken;
        public final int cookieCount;

        Parsed(String mediaUserToken, int cookieCount) {
            this.mediaUserToken = mediaUserToken;
            this.cookieCount = cookieCount;
        }
    }

    private AppleCookieSupport() {
    }

    /** 纯解析，不落盘。UI 校验粘贴内容是否像一份 cookies.txt 时用它。 */
    public static Parsed parse(String cookiesTxt) {
        if (cookiesTxt == null) return new Parsed("", 0);
        String token = "";
        int count = 0;
        for (String rawLine : cookiesTxt.split("\r?\n")) {
            String line = rawLine.trim();
            // Netscape 格式：# 开头是注释/HttpOnly 前缀（#HttpOnly_ 前缀行仍是有效 cookie）。
            if (line.isEmpty()) continue;
            if (line.startsWith("#") && !line.startsWith("#HttpOnly_")) continue;
            String[] fields = line.split("\t");
            if (fields.length < 7) continue;
            count++;
            if ("media-user-token".equals(fields[5].trim()) && token.isEmpty()) {
                token = fields[6].trim();
            }
        }
        return new Parsed(token, count);
    }

    /** 内容形状校验：至少要有几条 cookie 行，否则大概率粘错东西。 */
    public static boolean looksLikeCookiesFile(String cookiesTxt) {
        return parse(cookiesTxt).cookieCount >= 3;
    }

    /**
     * 导入一份 cookies.txt：落盘给 gamdl + 把 media-user-token 存进配置。
     *
     * <p>落盘前做<b>列序归一化</b>：Netscape 第 2 列是「是否包含子域」，CPython 的
     * MozillaCookieJar（gamdl 用它读文件）断言「该列必须与域首点一致」—— Apple 网页
     * 真实导出的 media-user-token 行常是「域带首点 + 列为 FALSE」，gamdl 3.9.1 直接
     * AssertionError 拒载。导入即修复，用户看到的就是「一次成功」。（真实 cookies
     * 实测踩中，见 E2E 记录。）</p>
     *
     * @return null = 内容不像 cookies.txt；否则返回解析到的 media-user-token（可能为空 = 游客 cookies）。
     */
    public static String importCookies(String cookiesTxt) throws IOException {
        if (!looksLikeCookiesFile(cookiesTxt)) return null;
        java.io.File file = cookiesFile();
        if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) {
            throw new IOException("无法创建伴生目录: " + file.getParentFile());
        }
        Files.write(file.toPath(), normalizeNetscapeColumnOrder(cookiesTxt).getBytes(StandardCharsets.UTF_8));
        String token = parse(cookiesTxt).mediaUserToken;
        AppleConfigStore.saveMediaUserToken(token);
        return token;
    }

    /**
     * Netscape 列序归一化：第 2 列（include-subdomains）强制与「域是否以点开头」一致。
     * 只动这一列，其余字段与行序原样保留；解析（{@link #parse}）对两列语义不敏感，
     * 所以提取逻辑不受影响。
     */
    static String normalizeNetscapeColumnOrder(String cookiesTxt) {
        if (cookiesTxt == null || cookiesTxt.isEmpty()) return cookiesTxt;
        StringBuilder out = new StringBuilder(cookiesTxt.length() + 64);
        // split(-1) 保留尾随空串：末行有没有换行由此可知，原样还原（不凭空补 \n）。
        String[] lines = cookiesTxt.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            String eol = "";
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
                eol = "\r";
            }
            if (index < lines.length - 1) eol = eol + "\n";
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("$")) {
                String[] fields = trimmed.split("\t");
                if (fields.length == 7 && !fields[0].isEmpty()) {
                    fields[1] = fields[0].startsWith(".") ? "TRUE" : "FALSE";
                    line = String.join("\t", fields);
                }
            }
            if (index < lines.length - 1) {
                out.append(line).append(eol);
            } else if (!line.isEmpty() || !eol.isEmpty()) {
                // 末段：非空才写（空串 = 原文以换行结尾，无需补）。
                out.append(line).append(eol);
            }
        }
        return out.toString();
    }

    /** gamdl 用的 cookies 文件路径；不存在时返回 null（调用方给「未导入凭据」的提示）。 */
    public static java.io.File cookiesFileOrNull() {
        java.io.File file = cookiesFile();
        return file.isFile() && file.length() > 0 ? file : null;
    }

    /** 有 cookies 文件且有账号态才算登录（纯游客 cookies 没有「我的歌单」可言）。 */
    public static boolean isLoggedIn() {
        return AppleConfigStore.snapshot().loggedIn() && cookiesFileOrNull() != null;
    }

    /** 退出登录：删 cookies 文件 + 清配置里的账号态。 */
    public static void clear() {
        try {
            Files.deleteIfExists(cookiesFile().toPath());
        } catch (IOException throwable) {
            System.err.println("[Music/Apple] cookies 删除失败: " + throwable);
        }
        AppleConfigStore.clearAccount();
    }

    public static java.io.File cookiesFile() {
        return new java.io.File(ConfigPaths.APPLE_MUSIC_DIR, COOKIES_FILE_NAME);
    }

    /** 供日志/设置页展示的一句话：文件在不在、有没有账号态。不输出任何 cookie 内容。 */
    public static String describe() {
        java.io.File file = cookiesFile();
        if (!file.isFile()) return "未导入 cookies";
        AppleConfigStore.Config config = AppleConfigStore.snapshot();
        if (!config.loggedIn()) {
            return String.format(Locale.ROOT, "已导入 cookies（游客：无 media-user-token，无本人歌单）");
        }
        return "已导入 cookies（含 media-user-token）";
    }
}
