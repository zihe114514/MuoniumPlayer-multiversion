package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.screens.ncm.LyricLine;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 歌词时间轴落盘缓存与预取的离线探针（P1#8）。
 *
 * <p>缓存是<b>纯收益</b>的东西：它不该有能力把歌词卡死。所以断言的重点不只是"存进去读得出来"，
 * 还有"读坏了必须当没有"——版本不认识、内嵌的键不是这首歌、文件半截，全部要回退到常规加载链。</p>
 *
 * <p>用例会真的落到 {@code config/muonium/cache/lyrics} 下（相对测试工作目录），每个用例结束都
 * 用 {@code invalidate} 把自己的那份删掉。</p>
 */
class LyricCacheStoreProbeTest {

    /** 拿私有 {@code fileFor} 得到缓存文件，避免在测试里重抄一遍 FNV 文件名推导。 */
    private static File cacheFileFor(String key) throws Exception {
        Method method = LyricCacheStore.class.getDeclaredMethod("fileFor", String.class);
        method.setAccessible(true);
        return (File) method.invoke(null, key);
    }

    private static void writeText(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void timelineRoundTripsThroughDisk() {
        String key = "probe:cache:roundtrip";
        LyricCacheStore.invalidate(key);
        try {
            assertTrue(LyricCacheStore.load(key).isEmpty(), "还没写过就该是未命中");

            List<LyricLine> lines = new ArrayList<>();

            LyricLine plain = new LyricLine(1000L, "Hello");
            plain.duration = 1500L;
            plain.translationText = "你好";
            plain.romanizationText = "hello";
            lines.add(plain);

            LyricLine interlude = new LyricLine(2500L, LyricTimelineSupport.INTERLUDE_TEXT);
            interlude.duration = 400L;
            interlude.isBreakLine = true;
            lines.add(interlude);

            LyricLine chorus = new LyricLine(3000L, "Chorus");
            chorus.duration = 2000L;
            chorus.isChorus = true;
            chorus.chorusIndex = 1;
            chorus.chorusEffect = 2;
            chorus.words.add(new LyricLine.Word("Cho", 3000L, 500L));
            LyricLine.Word harmony = new LyricLine.Word("rus", 3500L, 500L);
            harmony.background = true;
            chorus.words.add(harmony);
            lines.add(chorus);

            LyricCacheStore.store(key, lines);
            List<LyricLine> loaded = LyricCacheStore.load(key);

            assertEquals(3, loaded.size());
            // 时间轴按时间戳重排，输入顺序不影响结果。
            assertEquals(1000L, loaded.get(0).timestamp);
            assertEquals("Hello", loaded.get(0).lyric);
            assertEquals(1500L, loaded.get(0).duration);
            assertEquals("你好", loaded.get(0).translationText);
            assertEquals("hello", loaded.get(0).romanizationText);

            assertEquals(2500L, loaded.get(1).timestamp);
            assertTrue(loaded.get(1).isBreakLine, "间奏行必须原样活下来");

            // 副歌标记是渲染层读的，缓存漏一个字段就等于"重开播放器副歌效果消失"。
            assertTrue(loaded.get(2).isChorus);
            assertEquals(1, loaded.get(2).chorusIndex);
            assertEquals(2, loaded.get(2).chorusEffect);
            assertEquals(2, loaded.get(2).words.size());
            assertEquals(3000L, loaded.get(2).words.get(0).timestamp);
            assertTrue(loaded.get(2).words.get(1).background, "和声标记必须活下来");
        } finally {
            LyricCacheStore.invalidate(key);
        }
    }

    @Test
    void cacheMissForNeverWrittenKeyIsEmpty() {
        assertTrue(LyricCacheStore.load("probe:cache:never-written").isEmpty());
        assertTrue(LyricCacheStore.load("").isEmpty());
        assertTrue(LyricCacheStore.load(null).isEmpty());
    }

    @Test
    void storingNothingIsANoOpInsteadOfAnEmptyCacheHit() throws Exception {
        String key = "probe:cache:empty";
        LyricCacheStore.invalidate(key);
        try {
            LyricCacheStore.store(key, new ArrayList<LyricLine>());
            assertTrue(LyricCacheStore.load(key).isEmpty());
            // 空列表不该写出文件：否则一首没歌词的歌会留下一个"命中但空"的缓存，
            // 下次加载直接返回空列表，永远不会再去各处搜一遍。
            assertFalse(cacheFileFor(key).isFile());
        } finally {
            LyricCacheStore.invalidate(key);
        }
    }

    @Test
    void entryIsRejectedWhenItsEmbeddedKeyBelongsToAnotherSong() throws Exception {
        String key = "probe:cache:mismatch";
        File file = cacheFileFor(key);
        try {
            // 哈希撞了不要紧，但绝不能把别的歌的歌词交出去。
            writeText(file, "{\"version\":1,\"key\":\"other-song\","
                    + "\"lines\":[{\"t\":0,\"d\":10,\"x\":\"wrong\"}]}");

            assertTrue(LyricCacheStore.load(key).isEmpty());
        } finally {
            LyricCacheStore.invalidate(key);
        }
    }

    @Test
    void unknownFormatVersionIsTreatedAsAMissInsteadOfBeingBestEffortParsed() throws Exception {
        String key = "probe:cache:version";
        File file = cacheFileFor(key);
        try {
            writeText(file, "{\"version\":999,\"key\":\"" + key + "\","
                    + "\"lines\":[{\"t\":0,\"d\":10,\"x\":\"stale\"}]}");

            assertTrue(LyricCacheStore.load(key).isEmpty(),
                    "字段语义变过之后，旧格式必须当未命中，而不是尽力解析出一份错的");
        } finally {
            LyricCacheStore.invalidate(key);
        }
    }

    @Test
    void corruptedFileIsDroppedAndReportedAsAMiss() throws Exception {
        String key = "probe:cache:corrupt";
        File file = cacheFileFor(key);
        try {
            // 写到一半被杀留下的半截 JSON。
            writeText(file, "{\"version\":1,\"key\":\"" + key + "\",\"lines\":[{\"t\":0");

            assertTrue(LyricCacheStore.load(key).isEmpty());
            // 读坏过一次就顺手删掉，免得每次加载都踩同一个坑。
            assertFalse(file.isFile(), "坏缓存应当被顺手清掉");
        } finally {
            LyricCacheStore.invalidate(key);
        }
    }

    @Test
    void prefetchDoesNothingAtTheEndOfTheQueue() {
        List<com.muoniumplayer.core.ncm.music.dto.Music> queue =
                new ArrayList<>(Arrays.<com.muoniumplayer.core.ncm.music.dto.Music>asList(null, null));

        // 越界、null 队列、null 元素都必须安静返回，绝不抛进歌词加载那条链路。
        LyricCacheStore.prefetchNext(queue, 1);
        LyricCacheStore.prefetchNext(queue, 5);
        LyricCacheStore.prefetchNext(queue, -1);
        LyricCacheStore.prefetchNext(null, 0);
    }
}
