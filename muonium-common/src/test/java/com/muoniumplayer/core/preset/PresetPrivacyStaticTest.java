package com.muoniumplayer.core.preset;

import com.muoniumplayer.core.preset.domain.PresetDomain;
import com.muoniumplayer.core.preset.domain.PresetDomains;
import com.muoniumplayer.core.preset.model.KeyPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫：能进预设（会被分享出去）的键里不许出现账号、凭据、路径、网络地址这类东西。
 *
 * <p>按名字筛是兜底，不是全部防线：真正的防线是「账号 / 音源 / 缓存类配置根本不登记成配置域」，
 * 以及每个配置域的白名单。这里拦的是「有人往外观配置里塞了一个带路径或地址的新字段，顺手归进了分面」。
 * 真有合理的例外，在 {@link #ALLOWED} 里逐个写明理由。</p>
 */
class PresetPrivacyStaticTest {

    /**
     * 按「词」匹配：键名先按驼峰 / 点 / 下划线切成词。子串匹配会把 {@code fluid}、{@code guide} 里的
     * {@code uid} 当成账号 id，反而教人习惯性地往白名单里塞东西。
     */
    private static final String[] SENSITIVE = {
            "cookie", "token", "auth", "password", "passwd", "secret", "session", "account", "uid",
            "path", "dir", "directory", "folder", "file", "url", "uri", "proxy", "email", "phone",
            "license", "credential"
    };

    /** 名字撞了敏感词、但确实无害的键：「配置域.键 —— 理由」。 */
    private static final String[] ALLOWED = {
    };

    /** 这些名字的配置域一律不许出现：它们对应的是账号与音源，永远不进预设。 */
    private static final String[] FORBIDDEN_DOMAINS = {
            "netease", "qq", "kugou", "qishui", "spotify", "apple", "youtube", "bilibili", "lx",
            "auth", "account", "cookie", "source", "player", "cache"
    };

    @Test
    void 可导出键不含敏感字样() {
        List<String> hits = new ArrayList<String>();
        for (PresetDomain domain : PresetDomains.all()) {
            KeyPolicy policy = domain.policy();
            for (String key : policy.allKeys()) {
                KeyPolicy.Rule rule = policy.rule(key);
                if (rule.excluded() || isAllowed(domain.id() + "." + key)) {
                    continue;
                }
                for (String token : words(key)) {
                    for (String word : SENSITIVE) {
                        if (token.equals(word) || (word.length() >= 4 && token.startsWith(word))) {
                            hits.add(domain.id() + "." + key + "（含「" + word + "」）");
                        }
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(), "这些可导出键看起来像隐私或本地资源：" + hits
                + " —— 确实无害就加进 ALLOWED 并写明理由，否则 exclude 掉");
    }

    @Test
    void 账号与音源类配置不许登记成配置域() {
        for (PresetDomain domain : PresetDomains.all()) {
            String id = domain.id().toLowerCase(Locale.ROOT);
            for (String forbidden : FORBIDDEN_DOMAINS) {
                assertTrue(!id.equals(forbidden) && !id.startsWith(forbidden + "_"),
                        "配置域「" + domain.id() + "」不许进预设：账号 / 音源 / 缓存永不分享");
            }
        }
    }

    @Test
    void 排除项都写明了理由() {
        for (PresetDomain domain : PresetDomains.all()) {
            for (java.util.Map.Entry<String, String> entry : domain.policy().excluded().entrySet()) {
                assertTrue(entry.getValue() != null && entry.getValue().trim().length() >= 6,
                        domain.id() + "." + entry.getKey() + " 的排除理由太敷衍");
            }
        }
    }

    /** {@code worldLyricsCoverPaletteRgb0} → world / lyrics / cover / palette / rgb0。 */
    static List<String> words(String key) {
        List<String> words = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean separator = c == '.' || c == '_' || c == '-';
            if (separator || (Character.isUpperCase(c) && current.length() > 0)) {
                if (current.length() > 0) {
                    words.add(current.toString().toLowerCase(Locale.ROOT));
                    current.setLength(0);
                }
                if (separator) {
                    continue;
                }
            }
            current.append(c);
        }
        if (current.length() > 0) {
            words.add(current.toString().toLowerCase(Locale.ROOT));
        }
        return words;
    }

    @Test
    void 切词按驼峰与分隔符() {
        assertTrue(words("fullscreenLyricFluidStyle").contains("fluid"));
        assertTrue(!words("fullscreenLyricFluidStyle").contains("uid"));
        assertTrue(words("common.imageUrl").contains("url"));
        assertTrue(words("neteaseUid").contains("uid"));
    }

    private static boolean isAllowed(String qualifiedKey) {
        for (String allowed : ALLOWED) {
            if (allowed.startsWith(qualifiedKey + " ")) {
                return true;
            }
        }
        return false;
    }
}
