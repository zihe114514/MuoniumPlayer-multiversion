package com.muoniumplayer.core.music.source;

/**
 * QQ musicu 请求的 g_tk 哈希。自 自原 music-api-0.1.1.jar 手翻，2026-11 吸收进仓（原包 top.fpsmaster.music，现为 muonium 私有部分）。
 */
public final class MuHash33 {
    private MuHash33() {
    }

    public static int hash33(String s, int seed) {
        int h = seed;
        if (s == null) return h & 0x7fffffff;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            h = (h << 5) + h + c;
        }
        return h & 0x7fffffff;
    }
}
