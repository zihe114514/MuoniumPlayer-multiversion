package com.muoniumplayer.core.ncm.music;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 每首歌「歌词来源」记忆的离线探针。
 *
 * <p>这一层存在的理由只有一个：自动匹配错了的时候，「重新匹配」是没用的——同一条确定性链路再跑
 * 一遍，输入没变、结果必然一样。能脱困的只有换源，而换源必须<b>跨切歌与重启</b>活着，否则用户
 * 每听一遍都要重挑一次。所以用例用反射把静态缓存清掉来模拟一次重启，真的去读文件。</p>
 *
 * <p>用例会真的落到 {@code config/muonium/lyric_sources.json}（相对测试工作目录）。每个用例结束
 * 时把键写回 {@code AUTO}——那是"忘掉"的语义，所以不会往文件里留垃圾。</p>
 */
class LyricSourceMemoryProbeTest {

    /** 把静态缓存清掉，模拟一次进程重启。 */
    private static void forgetInMemoryCache() throws Exception {
        Field field = LyricSourceMemory.class.getDeclaredField("cache");
        field.setAccessible(true);
        field.set(null, null);
    }

    @Test
    void chosenSourceSurvivesAProcessRestart() throws Exception {
        String key = "probe:source:restart";
        try {
            LyricSourceMemory.write(key, LyricSourceMemory.QQ);
            assertEquals(LyricSourceMemory.QQ, LyricSourceMemory.read(key));

            forgetInMemoryCache();
            assertEquals(LyricSourceMemory.QQ, LyricSourceMemory.read(key), "重启后来源必须还在");
        } finally {
            LyricSourceMemory.write(key, LyricSourceMemory.AUTO);
        }
    }

    @Test
    void autoMeansForgetSoTheFileDoesNotGrowForever() throws Exception {
        String key = "probe:source:forget";
        try {
            LyricSourceMemory.write(key, LyricSourceMemory.KUGOU);
            forgetInMemoryCache();
            assertEquals(LyricSourceMemory.KUGOU, LyricSourceMemory.read(key));

            // 设回自动 = 从记忆里删掉，而不是留一条 "song": 0。
            LyricSourceMemory.write(key, LyricSourceMemory.AUTO);
            forgetInMemoryCache();
            assertEquals(LyricSourceMemory.AUTO, LyricSourceMemory.read(key));
        } finally {
            LyricSourceMemory.write(key, LyricSourceMemory.AUTO);
        }
    }

    @Test
    void unknownValuesFallBackToAutoInsteadOfAnUnreachableBranch() {
        // 配置文件被手改坏（负数、越界、别的东西）都不能让加载链走进没人处理的分支。
        assertEquals(LyricSourceMemory.AUTO, LyricSourceMemory.clamp(-1));
        assertEquals(LyricSourceMemory.AUTO, LyricSourceMemory.clamp(6));
        assertEquals(LyricSourceMemory.AUTO, LyricSourceMemory.clamp(9999));
        assertEquals(LyricSourceMemory.AMLL, LyricSourceMemory.clamp(LyricSourceMemory.AMLL));
        assertEquals(LyricSourceMemory.PLATFORM, LyricSourceMemory.clamp(LyricSourceMemory.PLATFORM));
    }

    @Test
    void cyclingWrapsAroundInBothDirections() {
        assertEquals(LyricSourceMemory.QQ, LyricSourceMemory.next(LyricSourceMemory.AMLL));
        // 最后一档的下一个是自动，不是越界。
        assertEquals(LyricSourceMemory.AUTO, LyricSourceMemory.next(LyricSourceMemory.PLATFORM));
        // 自动往前一档是最后一档，不是负数。
        assertEquals(LyricSourceMemory.PLATFORM, LyricSourceMemory.previous(LyricSourceMemory.AUTO));
        for (int source = 0; source < 6; source++) {
            assertEquals(source, LyricSourceMemory.next(LyricSourceMemory.previous(source)),
                    "前后各走一档必须回到原处");
        }
    }

    @Test
    void blankKeysAreIgnoredInsteadOfWritingAnUnusableEntry() {
        LyricSourceMemory.write("", LyricSourceMemory.AMLL);
        LyricSourceMemory.write("   ", LyricSourceMemory.AMLL);
        LyricSourceMemory.write(null, LyricSourceMemory.AMLL);

        assertEquals(LyricSourceMemory.AUTO, LyricSourceMemory.read(""));
        assertEquals(LyricSourceMemory.AUTO, LyricSourceMemory.read("   "));
        assertEquals(LyricSourceMemory.AUTO, LyricSourceMemory.read(null));
    }

    @Test
    void songsRememberTheirOwnSourceIndependently() throws Exception {
        String first = "probe:source:song-a";
        String second = "probe:source:song-b";
        try {
            LyricSourceMemory.write(first, LyricSourceMemory.PLATFORM);
            LyricSourceMemory.write(second, LyricSourceMemory.QISHUI);

            forgetInMemoryCache();

            // 切歌时各捡各的，不能串味。
            assertEquals(LyricSourceMemory.PLATFORM, LyricSourceMemory.read(first));
            assertEquals(LyricSourceMemory.QISHUI, LyricSourceMemory.read(second));
            assertEquals(LyricSourceMemory.AUTO, LyricSourceMemory.read("probe:source:song-c"));
        } finally {
            LyricSourceMemory.write(first, LyricSourceMemory.AUTO);
            LyricSourceMemory.write(second, LyricSourceMemory.AUTO);
        }
    }

    @Test
    void diskCacheKeyFollowsTheChosenSource() {
        String key = "probe:source:cache-key";
        try {
            String automatic = LyricLoadService.cacheKey(key);

            LyricSourceMemory.write(key, LyricSourceMemory.AMLL);
            String pinnedToAmll = LyricLoadService.cacheKey(key);
            assertNotEquals(automatic, pinnedToAmll,
                    "换了来源就必须换缓存键，否则会命中上一次留在盘上的另一份结果");

            LyricSourceMemory.write(key, LyricSourceMemory.QQ);
            assertNotEquals(pinnedToAmll, LyricLoadService.cacheKey(key));
        } finally {
            LyricSourceMemory.write(key, LyricSourceMemory.AUTO);
        }
    }

    @Test
    void servedSourceIsUnknownRatherThanAutomaticBeforeAnyResolve() {
        // 「未知」与「自动」必须分开：自动只是没指定来源的偏好值，从来不会有人「用自动这个源」
        // 把歌放出来。混在一起，界面就会把"这次还没解析过"显示成别的意思。
        assertEquals(-1, LyricLoadService.servedSource("probe:source:never-resolved"));
        assertEquals(-1, LyricLoadService.servedSource(null));
    }

    @Test
    void displayNamesCoverEveryOption() {
        assertEquals("自动", LyricSourceMemory.displayName(LyricSourceMemory.AUTO));
        assertEquals("AMLL", LyricSourceMemory.displayName(LyricSourceMemory.AMLL));
        assertEquals("QQ 音乐", LyricSourceMemory.displayName(LyricSourceMemory.QQ));
        assertEquals("酷狗", LyricSourceMemory.displayName(LyricSourceMemory.KUGOU));
        assertEquals("汽水", LyricSourceMemory.displayName(LyricSourceMemory.QISHUI));
        assertEquals("平台官方", LyricSourceMemory.displayName(LyricSourceMemory.PLATFORM));
    }
}
