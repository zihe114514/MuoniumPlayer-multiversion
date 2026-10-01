package com.muoniumplayer.core.ncm.music;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 每首歌的歌词偏移记忆的离线探针（P1#9）。
 *
 * <p>这一层的价值全在「跨进程还在」上：只存在内存里的话，用户每次开播放器都得重调一遍。
 * 所以用例用反射把静态缓存清掉来模拟一次重启，真的去读文件。</p>
 *
 * <p>用例会真的落到 {@code config/muonium/lyric_offsets.json}（相对测试工作目录）。每个用例
 * 结束时把键写回 0——{@code 0} 的语义是"忘掉"，所以不会往文件里留垃圾。</p>
 */
class LyricOffsetMemoryProbeTest {

    /** 把静态缓存清掉，模拟一次进程重启。 */
    private static void forgetInMemoryCache() throws Exception {
        Field field = LyricOffsetMemory.class.getDeclaredField("cache");
        field.setAccessible(true);
        field.set(null, null);
    }

    @Test
    void offsetSurvivesAProcessRestart() throws Exception {
        String key = "probe:offset:restart";
        try {
            LyricOffsetMemory.write(key, 320L);
            assertEquals(320L, LyricOffsetMemory.read(key));

            forgetInMemoryCache();
            assertEquals(320L, LyricOffsetMemory.read(key), "重启后偏移必须还在");
        } finally {
            LyricOffsetMemory.write(key, 0L);
        }
    }

    @Test
    void zeroMeansForgetSoTheFileDoesNotGrowForever() throws Exception {
        String key = "probe:offset:forget";
        try {
            LyricOffsetMemory.write(key, 800L);
            forgetInMemoryCache();
            assertEquals(800L, LyricOffsetMemory.read(key));

            // 归零 = 从记忆里删掉，而不是留一条 "song": 0。
            LyricOffsetMemory.write(key, 0L);
            forgetInMemoryCache();
            assertEquals(0L, LyricOffsetMemory.read(key));
        } finally {
            LyricOffsetMemory.write(key, 0L);
        }
    }

    @Test
    void offsetIsClampedToPlusMinusTenSeconds() {
        // 再大就不是"对不齐"而是"换错歌"了。
        assertEquals(10_000L, LyricOffsetMemory.clamp(50_000L));
        assertEquals(-10_000L, LyricOffsetMemory.clamp(-50_000L));
        assertEquals(10_000L, LyricOffsetMemory.clamp(10_000L));
        assertEquals(1200L, LyricOffsetMemory.clamp(1200L));
        assertEquals(0L, LyricOffsetMemory.clamp(0L));
    }

    @Test
    void writeClampsBeforePersisting() throws Exception {
        String key = "probe:offset:clamped";
        try {
            LyricOffsetMemory.write(key, 99_000L);
            forgetInMemoryCache();
            assertEquals(10_000L, LyricOffsetMemory.read(key));
        } finally {
            LyricOffsetMemory.write(key, 0L);
        }
    }

    @Test
    void blankKeysAreIgnoredInsteadOfWritingAnUnusableEntry() {
        LyricOffsetMemory.write("", 500L);
        LyricOffsetMemory.write("   ", 500L);
        LyricOffsetMemory.write(null, 500L);

        assertEquals(0L, LyricOffsetMemory.read(""));
        assertEquals(0L, LyricOffsetMemory.read("   "));
        assertEquals(0L, LyricOffsetMemory.read(null));
    }

    @Test
    void songsRememberTheirOwnOffsetIndependently() throws Exception {
        String first = "probe:offset:song-a";
        String second = "probe:offset:song-b";
        try {
            LyricOffsetMemory.write(first, -420L);
            LyricOffsetMemory.write(second, 250L);

            forgetInMemoryCache();

            // 切歌时各捡各的，不能串味。
            assertEquals(-420L, LyricOffsetMemory.read(first));
            assertEquals(250L, LyricOffsetMemory.read(second));
            assertEquals(0L, LyricOffsetMemory.read("probe:offset:song-c"));
        } finally {
            LyricOffsetMemory.write(first, 0L);
            LyricOffsetMemory.write(second, 0L);
        }
    }
}
