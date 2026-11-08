package com.muoniumplayer.core.preset;

import com.muoniumplayer.core.preset.domain.PresetDomain;
import com.muoniumplayer.core.preset.domain.PresetDomains;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.KeyPolicy;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import static org.junit.jupiter.api.Assertions.*;

/** Fixed PR#13 published MPP2 prefixes. Append-only changes must leave these exact fingerprints untouched. */
class PresetShareKeysFreezeTest {
    private static final class Frozen {
        final int count; final String sha256Prefix;
        Frozen(int count, String sha256Prefix) { this.count = count; this.sha256Prefix = sha256Prefix; }
    }
    private static final Map<String, Frozen> FROZEN = new HashMap<String, Frozen>();
    static {
        FROZEN.put("hud", new Frozen(166, "7c93be7afcd9ab86"));
        FROZEN.put("theme", new Frozen(2, "7c0e005032b3e66f"));
        FROZEN.put("foliaBg", new Frozen(45, "25b4e17a614327fc"));
        FROZEN.put("font", new Frozen(1, "a56145270ce6b3be"));
        FROZEN.put("transparent", new Frozen(3, "83f0c19e23d1bf9f"));
        FROZEN.put("cine", new Frozen(18, "0f5c875bb10c950a"));
        FROZEN.put("acoustics", new Frozen(5, "a96f36ff99b93dc2"));
        FROZEN.put("viper", new Frozen(3, "3bb30cd03b42e41e"));
        // spin 域 2026-11-08 随陀螺模式加入：enabled/mode/range/step 四键首发即冻结。
        FROZEN.put("spin", new Frozen(4, "4fefeb9135d17b47"));
    }
    @Test void 已发布的键表前缀一个字都没动() throws Exception {
        for (PresetDomain domain : PresetDomains.all()) {
            Frozen frozen = FROZEN.get(domain.id()); assertNotNull(frozen);
            List<String> keys = domain.shareKeys(); assertTrue(keys.size() >= frozen.count);
            assertEquals(frozen.sha256Prefix, sha256(String.join("\n", keys.subList(0, frozen.count))).substring(0, 16), domain.id());
        }
    }
    @Test void 每个可分享的键都有编号() {
        for (PresetDomain domain : PresetDomains.all()) {
            KeyPolicy policy = domain.policy(); Set<String> numbered = new HashSet<>(domain.shareKeys());
            assertEquals(domain.shareKeys().size(), numbered.size()); Set<String> missing = new TreeSet<>();
            for (String key : policy.allKeys()) if (!policy.rule(key).excluded() && !numbered.contains(key)) missing.add(key);
            assertTrue(missing.isEmpty(), domain.id() + ": " + missing);
        }
    }
    @Test void 配置域与分面的编号唯一() {
        Set<Integer> domains = new HashSet<>(), facets = new HashSet<>();
        for (PresetDomain domain : PresetDomains.all()) { assertTrue(domain.shareCode() >= 1); assertTrue(domains.add(domain.shareCode())); }
        for (Facet facet : Facet.values()) { assertTrue(facet.code() >= 1 && facet.code() <= 63); assertTrue(facets.add(facet.code())); }
    }
    private static String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(); for (byte b : digest) hex.append(String.format("%02x", b & 0xFF)); return hex.toString();
    }
}
