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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫：分享码 v2 的编号一经发布永不改。
 *
 * <p>码里只写编号不写名字，所以「第 12 号是哪个设置」必须永远不变 —— 把已发布的键表重排、删一项、
 * 在中间插一项，已经发出去的分享码就会把值套到错的设置上，而且不报任何错。这里钉住每个配置域
 * <b>已发布前缀</b>的指纹：只在末尾追加不会动它，动了中间就红。</p>
 *
 * <p>发新版时（确认要把新追加的键「发布」出去后）可以把计数与指纹更新到当前长度；平时不用动。</p>
 */
class PresetShareKeysFreezeTest {

    private static final class Frozen {
        final int count;
        final String sha256Prefix;

        Frozen(int count, String sha256Prefix) {
            this.count = count;
            this.sha256Prefix = sha256Prefix;
        }
    }

    /**
     * 分享码 v2 首发时的键表。2026-11-06 定稿；发布前并入上游 dev 时 hud 末尾追加了
     * {@code worldLyricsGlowMode}（3D 歌词发光形态，165 → 166），前 165 个的指纹未变。
     */
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
    }

    @Test
    void 已发布的键表前缀一个字都没动() throws Exception {
        for (PresetDomain domain : PresetDomains.all()) {
            Frozen frozen = FROZEN.get(domain.id());
            assertTrue(frozen != null, "新配置域 " + domain.id() + " 要在 FROZEN 里登记首发键表");
            List<String> keys = domain.shareKeys();
            assertTrue(keys.size() >= frozen.count, domain.id() + " 的键表变短了：已发布的编号只能占位，不能删除");
            String hash = sha256(String.join("\n", keys.subList(0, frozen.count))).substring(0, 16);
            assertEquals(frozen.sha256Prefix, hash, domain.id() + " 的键表已发布部分被改动（只许在末尾追加）"
                    + "—— 已经分享出去的码会把值套到错的设置上");
        }
    }

    @Test
    void 每个可分享的键都有编号() {
        for (PresetDomain domain : PresetDomains.all()) {
            KeyPolicy policy = domain.policy();
            Set<String> numbered = new HashSet<String>(domain.shareKeys());
            assertEquals(domain.shareKeys().size(), numbered.size(), domain.id() + " 的键表有重复");
            Set<String> missing = new TreeSet<String>();
            for (String key : policy.allKeys()) {
                if (!policy.rule(key).excluded() && !numbered.contains(key)) {
                    missing.add(key);
                }
            }
            assertTrue(missing.isEmpty(), domain.id() + " 有可分享但没编号的键：" + missing
                    + " —— 在 SHARE_KEYS 末尾追加，别插在中间");
        }
    }

    @Test
    void 配置域与分面的编号唯一() {
        Set<Integer> domainCodes = new HashSet<Integer>();
        for (PresetDomain domain : PresetDomains.all()) {
            assertTrue(domain.shareCode() >= 1, domain.id());
            assertTrue(domainCodes.add(domain.shareCode()), "配置域编号重复：" + domain.shareCode());
        }
        Set<Integer> facetCodes = new HashSet<Integer>();
        for (Facet facet : Facet.values()) {
            assertTrue(facet.code() >= 1 && facet.code() <= 63, facet.id());
            assertTrue(facetCodes.add(facet.code()), "分面编号重复：" + facet.code());
        }
    }

    private static String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b & 0xFF));
        }
        return hex.toString();
    }
}
