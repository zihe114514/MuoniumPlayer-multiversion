package com.muoniumplayer.core.preset;

import com.muoniumplayer.core.preset.domain.PresetDomain;
import com.muoniumplayer.core.preset.domain.PresetDomains;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;

class PresetPrivacyStaticTest {
    private static final String[] SENSITIVE = {"cookie", "token", "auth", "password", "passwd", "secret", "session", "account", "uid",
            "path", "dir", "directory", "folder", "file", "url", "uri", "proxy", "email", "phone", "license", "credential"};
    private static final String[] ALLOWED = {};
    private static final String[] FORBIDDEN_DOMAINS = {"netease", "qq", "kugou", "qishui", "spotify", "apple", "youtube", "bilibili", "lx",
            "auth", "account", "cookie", "source", "player", "cache"};
    @Test void 可导出键不含敏感字样() {
        List<String> hits = new ArrayList<>();
        for (PresetDomain domain : PresetDomains.all()) for (String key : domain.policy().allKeys()) {
            if (domain.policy().rule(key).excluded() || isAllowed(domain.id() + "." + key)) continue;
            for (String token : words(key)) for (String word : SENSITIVE)
                if (token.equals(word) || (word.length() >= 4 && token.startsWith(word))) hits.add(domain.id() + "." + key);
        }
        assertTrue(hits.isEmpty(), hits.toString());
    }
    @Test void 账号与音源类配置不许登记成配置域() {
        for (PresetDomain domain : PresetDomains.all()) for (String forbidden : FORBIDDEN_DOMAINS) {
            String id = domain.id().toLowerCase(Locale.ROOT); assertFalse(id.equals(forbidden) || id.startsWith(forbidden + "_"));
        }
    }
    @Test void 排除项都写明了理由() {
        for (PresetDomain domain : PresetDomains.all()) for (String reason : domain.policy().excluded().values()) assertTrue(reason != null && reason.trim().length() >= 6);
    }
    static List<String> words(String key) {
        List<String> out = new ArrayList<>(); StringBuilder current = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i); boolean separator = c == '.' || c == '_' || c == '-';
            if (separator || (Character.isUpperCase(c) && current.length() > 0)) {
                if (current.length() > 0) { out.add(current.toString().toLowerCase(Locale.ROOT)); current.setLength(0); }
                if (separator) continue;
            }
            current.append(c);
        }
        if (current.length() > 0) out.add(current.toString().toLowerCase(Locale.ROOT)); return out;
    }
    @Test void 切词按驼峰与分隔符() {
        assertTrue(words("fullscreenLyricFluidStyle").contains("fluid")); assertFalse(words("fullscreenLyricFluidStyle").contains("uid"));
        assertTrue(words("common.imageUrl").contains("url")); assertTrue(words("neteaseUid").contains("uid"));
    }
    private static boolean isAllowed(String key) { for (String allowed : ALLOWED) if (allowed.startsWith(key + " ")) return true; return false; }
}
