package com.muoniumplayer.core.ncm.spotify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OAuth PKCE 授权流的协议回归；全部纯函数，零网络零磁盘零端口。
 *
 * <p>授权 URL 里的参数名拼错、challenge 算错、回调 query 解析错，都表现为
 * Spotify 端一句笼统的 {@code INVALID_CLIENT} / {@code invalid_grant}，用户侧只剩
 * 「授权失败」四个字。所以这里用 RFC 7636 官方向量把 PKCE 钉死，用参数名清单把
 * 授权 URL 钉死 —— 这些字符串就是协议本身。</p>
 */
class SpotifyOAuthStaticTest {

    @AfterEach
    void tearDown() {
        SpotifyConfigStore.clearForTest();
        SpotifyOAuthFlow.cancel();
    }

    @Test
    @DisplayName("PKCE S256 challenge 与 RFC 7636 附录 B 官方向量一致")
    void pkceChallengeMatchesRfcVector() {
        // RFC 7636 Appendix B：verifier → BASE64URL(SHA256(ASCII(verifier)))
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                SpotifyOAuthFlow.pkceChallenge(verifier));
    }

    @Test
    @DisplayName("授权 URL 携带协议必需的全部参数，且值经 URL 编码")
    void authorizeUrlCarriesRequiredParameters() {
        SpotifyConfigStore.putForTest("client-id-for-test", "US");
        String url = SpotifyOAuthFlow.buildAuthorizeUrl("state-xyz", "verifier-abc");

        assertTrue(url.startsWith(SpotifyConfigStore.ACCOUNTS_BASE + SpotifyConfigStore.AUTHORIZE_PATH + "?"),
                "授权端点必须是 accounts.spotify.com/authorize");
        assertTrue(url.contains("response_type=code"), "授权码流");
        assertTrue(url.contains("client_id=client-id-for-test"));
        assertTrue(url.contains("redirect_uri=" + percentEncode(SpotifyConfigStore.REDIRECT_URI)),
                "redirect_uri 必须与开发者后台登记的逐字一致（编码后）");
        assertTrue(url.contains("code_challenge_method=S256"), "PKCE 必须声明 S256 方法");
        assertTrue(url.contains("code_challenge=" + percentEncode(
                        SpotifyOAuthFlow.pkceChallenge("verifier-abc"))),
                "challenge 与 verifier 必须配套");
        assertTrue(url.contains("state=state-xyz"));
        for (String scope : SpotifyConfigStore.SCOPES) {
            assertTrue(url.contains(percentEncode(scope)),
                    "scope 清单里的每一项都要出现在授权 URL：" + scope);
        }
        // 反向钉一下：URL 里绝不允许出现裸空格（scope 是拼进 query 的，漏编码只会拼出一个坏 URL）。
        assertFalse(url.contains(" "), "授权 URL 不允许出现未编码空格");
    }

    @Test
    @DisplayName("回调 query 解析：标准形态与缺参形态")
    void callbackQueryParsing() {
        Map<String, String> ok = SpotifyOAuthFlow.parseQuery("code=abc123&state=st%20ate");
        assertEquals("abc123", ok.get("code"));
        assertEquals("st ate", ok.get("state"));

        Map<String, String> denied = SpotifyOAuthFlow.parseQuery("error=access_denied&state=s");
        assertEquals("access_denied", denied.get("error"));

        assertTrue(SpotifyOAuthFlow.parseQuery(null).isEmpty());
        assertTrue(SpotifyOAuthFlow.parseQuery("").isEmpty());
        // 没有等号的碎片直接丢掉，不抛不挂。
        assertTrue(SpotifyOAuthFlow.parseQuery("junk&code=c").get("code").equals("c"));
    }

    @Test
    @DisplayName("回调端口与重定向地址必须一致")
    void callbackPortMatchesRedirectUri() {
        assertTrue(SpotifyConfigStore.REDIRECT_URI.endsWith(":" + SpotifyOAuthFlow.callbackPort() + "/callback"),
                "REDIRECT_URI 与回调服务端口各写了一份，必须同步改");
    }

    private static String percentEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
