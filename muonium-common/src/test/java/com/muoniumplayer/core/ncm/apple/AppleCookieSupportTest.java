package com.muoniumplayer.core.ncm.apple;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Netscape cookies.txt 解析的协议回归；纯函数，零网络零磁盘（不触碰 importCookies 的落盘路径）。
 *
 * <p>解析错一行，就是「cookies 明明有效却导入失败」或「过期 cookies 被当成有效」——
 * 两种都只会在用户端表现为「登录了却没反应」，没有日志就是无头案。</p>
 */
class AppleCookieSupportTest {

    private static final String SAMPLE = String.join("\n",
            "# Netscape HTTP Cookie File",
            "# This is a generated file! Do not edit.",
            "",
            ".apple.com\tTRUE\t/\tTRUE\t1893456000\tmedia-user-token\tabc.def.ghi",
            ".apple.com\tTRUE\t/\tTRUE\t1893456000\titspod\t42",
            "#HttpOnly_.apple.com\tTRUE\t/\tTRUE\t1893456000\titctx\thello",
            ".music.apple.com\tTRUE\t/\tFALSE\t0\tgeo\tCN");

    @Test
    @DisplayName("media-user-token 从正确列提取；注释行与空行跳过；#HttpOnly_ 前缀仍是有效行")
    void parsesMediaUserToken() {
        AppleCookieSupport.Parsed parsed = AppleCookieSupport.parse(SAMPLE);
        assertEquals("abc.def.ghi", parsed.mediaUserToken);
        assertEquals(4, parsed.cookieCount, "#HttpOnly_ 前缀行是有效 cookie，注释行不是");
    }

    @Test
    @DisplayName("没有 media-user-token 的 cookies 是游客态：token 为空但行数正常")
    void guestCookiesHaveEmptyToken() {
        AppleCookieSupport.Parsed parsed = AppleCookieSupport.parse(String.join("\n",
                ".apple.com\tTRUE\t/\tTRUE\t1893456000\titspod\t42",
                ".music.apple.com\tTRUE\t/\tFALSE\t0\tgeo\tCN"));
        assertEquals("", parsed.mediaUserToken);
        assertEquals(2, parsed.cookieCount);
        assertTrue(AppleCookieSupport.looksLikeCookiesFile(
                String.join("\n",
                        ".apple.com\tTRUE\t/\tTRUE\t1893456000\titspod\t42",
                        ".music.apple.com\tTRUE\t/\tFALSE\t0\tgeo\tCN",
                        ".apple.com\tTRUE\t/\tTRUE\t1893456000\titest\t1")));
    }

    @Test
    @DisplayName("形状校验：少于 3 行 cookie 的内容判为不像 cookies.txt（拦截粘错的东西）")
    void shapeValidation() {
        assertFalse(AppleCookieSupport.looksLikeCookiesFile(null));
        assertFalse(AppleCookieSupport.looksLikeCookiesFile(""));
        assertFalse(AppleCookieSupport.looksLikeCookiesFile("# just a comment"));
        assertFalse(AppleCookieSupport.looksLikeCookiesFile(
                "https://music.apple.com/cn/playlist/x/pl.u-abc"), "链接粘贴进来不是 cookies");
        assertTrue(AppleCookieSupport.looksLikeCookiesFile(SAMPLE));
    }

    @Test
    @DisplayName("短行（字段数 <7）跳过而不是崩溃：用户复制一半的行不该打爆导入")
    void truncatedLinesAreSkipped() {
        AppleCookieSupport.Parsed parsed = AppleCookieSupport.parse(String.join("\n",
                ".apple.com\tTRUE\t/",
                ".apple.com\tTRUE\t/\tTRUE\t1893456000\tmedia-user-token\ttok",
                "",
                ".apple.com"));
        assertEquals("tok", parsed.mediaUserToken);
        assertEquals(1, parsed.cookieCount);
    }

    @Test
    @DisplayName("重复的 media-user-token 取第一条，不因多值行崩溃")
    void duplicateTokenTakesFirst() {
        AppleCookieSupport.Parsed parsed = AppleCookieSupport.parse(String.join("\n",
                ".apple.com\tTRUE\t/\tTRUE\t1893456000\tmedia-user-token\tfirst",
                ".apple.com\tTRUE\t/\tTRUE\t1893456000\tmedia-user-token\tsecond"));
        assertEquals("first", parsed.mediaUserToken);
    }

    @Test
    @DisplayName("列序归一化：include-subdomains 列与域首点对齐（gamdl 的 MozillaCookieJar 严格断言）")
    void normalizesIncludeSubdomainsColumn() {
        // Apple 网页真实导出的形态：域带首点但第 2 列 FALSE —— 直接喂给 gamdl 会
        // AssertionError 拒载（真实 cookies E2E 踩中）。导入时必须归一化。
        String appleExport = String.join("\n",
                "# Netscape HTTP Cookie File",
                ".music.apple.com\tTRUE\t/\tTRUE\t1893456000\twosid\tabc",
                ".music.apple.com\tFALSE\t/\tTRUE\t1893456000\tmedia-user-token\ttok",
                "music.apple.com\tFALSE\t/\tFALSE\t1893456000\tpltvcid\tcid");
        String normalized = AppleCookieSupport.normalizeNetscapeColumnOrder(appleExport);
        String[] lines = normalized.split("\n");
        assertEquals("TRUE", lines[1].split("\t")[1], "域带首点 → 列必须 TRUE");
        assertEquals("TRUE", lines[2].split("\t")[1], "media-user-token 行同上（这是踩中的那一行）");
        assertEquals("FALSE", lines[3].split("\t")[1], "域无首点 → 列必须 FALSE");
        // 归一化不改变解析结果。
        assertEquals("tok", AppleCookieSupport.parse(normalized).mediaUserToken);
        // CPython MozillaCookieJar 的断言语义：domain_specified == domain.startsWith(".")
        for (String line : lines) {
            if (line.startsWith("#") || line.isBlank()) continue;
            String[] f = line.split("\t");
            assertEquals(f[0].startsWith("."), f[1].equals("TRUE"), line);
        }
    }

    @Test
    @DisplayName("归一化保留 CRLF 与注释行，空输入原样返回")
    void normalizationKeepsStructure() {
        assertEquals("", AppleCookieSupport.normalizeNetscapeColumnOrder(""));
        String comment = "# Netscape HTTP Cookie File\r\n";
        assertEquals(comment, AppleCookieSupport.normalizeNetscapeColumnOrder(comment));
        String mixed = ".apple.com\tFALSE\t/\tTRUE\t1\ta\tb\r\n.apple.com\tTRUE\t/\tTRUE\t1\tc\td\n";
        String out = AppleCookieSupport.normalizeNetscapeColumnOrder(mixed);
        assertTrue(out.contains(".apple.com\tTRUE\t/\tTRUE\t1\ta\tb\r\n"),
                "CRLF 行归一化后仍带 CRLF");
        assertTrue(out.endsWith("\n"), "末行换行不丢");
    }

    @Test
    @DisplayName("describe 不泄露 cookie 内容：只说文件在不在与有没有账号态")
    void describeNeverLeaksContent() throws IOException {
        // 无磁盘形态（不落 importCookies）：文件不存在的分支。
        String text = AppleCookieSupport.describe();
        if (!AppleCookieSupport.cookiesFile().isFile()) {
            assertEquals("未导入 cookies", text);
        }
        // 无论走哪个分支，描述里都不该出现 SAMPLE 的任何真实 cookie 值。
        assertFalse(text.contains("abc.def.ghi"), "描述绝不携带 cookie 值");
        Files.deleteIfExists(AppleCookieSupport.cookiesFile().toPath());
    }
}
