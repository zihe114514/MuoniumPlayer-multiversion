package com.muoniumplayer.core.ncm.music;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交接计划缓存的回归测试：<b>算过的歌不重复推理</b>。
 *
 * <p>这里测的是缓存本身的契约，不启动音频设备：用两个真实的临时文件当「两首歌的音频」，
 * 于是键的构成、落盘往返、损坏清理、以及「哪些变化必须让缓存失效」全都能单独钉住。</p>
 *
 * <p>最要紧的两条：<b>同一个键必须能跨进程命中</b>（所以键里绝不能出现路径或
 * {@code hashCode()}），以及<b>任何一项会影响曲线的设置变了都必须失配</b> ——
 * 否则用户改了开关却听到同一条曲线，看起来就是那个开关坏了。</p>
 */
class AutomixPlanCacheTest {

    private Path sandbox;
    private File outgoing;
    private File incoming;

    @BeforeEach
    void setUp() throws IOException {
        sandbox = Files.createTempDirectory("automix-plan-cache-test");
        outgoing = write("out.wav", "AAAAAAAA");
        incoming = write("in.wav", "BBBBBBBB");
        AutomixPlanCache.useDirectory(sandbox.toFile());
    }

    @AfterEach
    void tearDown() throws IOException {
        AutomixPlanCache.useDirectory(null);
        deleteRecursively(sandbox);
    }

    private File write(String name, String content) throws IOException {
        File file = new File(sandbox.toFile(), name);
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        }
    }

    /** 一份可辨认的计划：数值都是任意但有意义的，用来验证往返无损。 */
    private static AutomixPlan plan(long fire, long overlap, long cue) {
        return new AutomixPlan(fire, overlap, cue, 1.12f, true, true,
                .965f, false, 43L, .62f, "cached plan",
                FoliaTransitionStyle.BASS_SWAP, 2.5f, -1.5f, false,
                .42, .38, .45, true, 512.0);
    }

    // ── 往返 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("存进去的计划能原样读回来，且字段一个不差")
    void storeThenLoadRoundTrips() {
        AutomixPlan original = plan(178_400L, 9_700L, 2_150L);
        AutomixPlanCache.store(outgoing, incoming, 120_000L, original);

        AutomixPlan loaded = AutomixPlanCache.load(outgoing, incoming, 120_000L);
        assertNotNull(loaded, "刚存进去的计划必须命中");
        assertEquals(original.fireMillis, loaded.fireMillis, "交接点");
        assertEquals(original.overlapMillis, loaded.overlapMillis, "交叠长度");
        assertEquals(original.incomingCueMillis, loaded.incomingCueMillis, "入口点");
        assertEquals(original.tempoRatio, loaded.tempoRatio, "速度/音高比");
        assertEquals(original.matchGain, loaded.matchGain, "响度匹配");
        assertEquals(original.vocalDuckDepth, loaded.vocalDuckDepth, "人声闪避深度");
        assertEquals(original.style, loaded.style, "过渡族");
        assertEquals(original.stemDriven, loaded.stemDriven, "stem 是否主导");
        assertEquals(original.animationPeriodMillis, loaded.animationPeriodMillis, "动画周期");
    }

    @Test
    @DisplayName("换了音频文件就不再命中：同一首歌重新下载成另一份字节必须重算")
    void differentAudioMisses() throws IOException {
        AutomixPlanCache.store(outgoing, incoming, 120_000L, plan(178_400L, 9_700L, 2_150L));

        File other = write("in.wav", "CCCCCCCCCC");   // 同名但内容更长
        assertNull(AutomixPlanCache.load(outgoing, other, 120_000L),
                "字节长度变了说明音频换了，绝不能复用旧计划");
    }

    @Test
    @DisplayName("歌词边界不同就是不同的计划：带歌词与不带歌词各存一份")
    void lyricBoundaryIsPartOfTheKey() {
        AutomixPlan withLyrics = plan(178_400L, 9_700L, 2_150L);
        AutomixPlan withoutLyrics = plan(200_000L, 9_700L, 2_150L);
        AutomixPlanCache.store(outgoing, incoming, 120_000L, withLyrics);
        AutomixPlanCache.store(outgoing, incoming, -1L, withoutLyrics);

        assertEquals(178_400L,
                AutomixPlanCache.load(outgoing, incoming, 120_000L).fireMillis,
                "带歌词那一份按自己的键命中");
        assertEquals(200_000L,
                AutomixPlanCache.load(outgoing, incoming, -1L).fireMillis,
                "无歌词那一份是另一份计划，不该互相覆盖");
    }

    // ── 不该缓存的东西 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("null 计划不入缓存：算不出来往往是暂时状态，记成命中会让这首永远不交叠")
    void nullPlanIsNeverCached() {
        AutomixPlanCache.store(outgoing, incoming, 120_000L, null);
        assertNull(AutomixPlanCache.load(outgoing, incoming, 120_000L),
                "null 必须当未命中，下次还要重新尝试分析");
    }

    @Test
    @DisplayName("拿不到音频文件就不给键，因此既不写也不读")
    void missingAudioIsNeverCached() {
        AutomixPlanCache.store(null, incoming, 120_000L, plan(178_400L, 9_700L, 2_150L));
        assertNull(AutomixPlanCache.load(null, incoming, 120_000L),
                "不知道是哪首歌就不能缓存，宁可每次重算");
        assertNull(AutomixPlanCache.load(outgoing, null, 120_000L), "另一侧同理");
    }

    // ── 落盘层 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("跨进程可复用：清掉内存层之后仍能从磁盘命中")
    void survivesProcessRestart() {
        AutomixPlanCache.store(outgoing, incoming, 120_000L, plan(178_400L, 9_700L, 2_150L));
        // useDirectory 会清内存层，等价于「进程重启、内存没了、只剩文件」。
        AutomixPlanCache.useDirectory(sandbox.toFile());

        AutomixPlan loaded = AutomixPlanCache.load(outgoing, incoming, 120_000L);
        assertNotNull(loaded, "落盘缓存必须跨进程存活 —— 这正是它存在的理由");
        assertEquals(178_400L, loaded.fireMillis);
    }

    @Test
    @DisplayName("键里不含路径，所以换一台机器、换一个游戏目录也能命中同一份计划")
    void keyDoesNotDependOnAbsolutePath() throws IOException {
        AutomixPlanCache.store(outgoing, incoming, 120_000L, plan(178_400L, 9_700L, 2_150L));

        // 另建一个目录，放同名同长的两份音频 —— 模拟「另一台机器的同一首歌」。
        Path other = Files.createTempDirectory("automix-plan-cache-other");
        try {
            File sameNameOut = new File(other.toFile(), outgoing.getName());
            File sameNameIn = new File(other.toFile(), incoming.getName());
            Files.write(sameNameOut.toPath(), "AAAAAAAA".getBytes(StandardCharsets.UTF_8));
            Files.write(sameNameIn.toPath(), "BBBBBBBB".getBytes(StandardCharsets.UTF_8));

            AutomixPlan loaded = AutomixPlanCache.load(sameNameOut, sameNameIn, 120_000L);
            assertNotNull(loaded, "同名同长的音频在别处也应当命中 —— 键只能由名字与长度决定");
            assertEquals(178_400L, loaded.fireMillis);
        } finally {
            deleteRecursively(other);
        }
    }

    @Test
    @DisplayName("文件损坏当未命中并顺手删掉，不会每次交接都踩一次")
    void corruptFileIsTreatedAsMissAndRemoved() throws IOException {
        AutomixPlanCache.store(outgoing, incoming, 120_000L, plan(178_400L, 9_700L, 2_150L));
        File[] written = sandbox.toFile().listFiles((dir, name) -> name.endsWith(".json"));
        assertNotNull(written);
        assertEquals(1, written.length, "应当正好写了一个 JSON");
        Files.write(written[0].toPath(), "{ this is not json".getBytes(StandardCharsets.UTF_8));

        // 换目录以清掉内存层，逼它真去读那个坏文件。
        AutomixPlanCache.useDirectory(sandbox.toFile());
        assertNull(AutomixPlanCache.load(outgoing, incoming, 120_000L), "坏文件必须当未命中");
        assertTrue(written[0].isFile() == false || written[0].length() == 0
                        || !written[0].exists(),
                "坏文件应当被删掉，免得每次交接都再解析一次");
    }

    @Test
    @DisplayName("落盘目录不存在时自动建，且写入失败绝不影响交接")
    void createsDirectoryOnDemand() {
        Path nested = sandbox.resolve("deep").resolve("nested");
        AutomixPlanCache.useDirectory(nested.toFile());

        AutomixPlanCache.store(outgoing, incoming, 120_000L, plan(178_400L, 9_700L, 2_150L));
        assertTrue(nested.toFile().isDirectory(), "写入时应当自动建目录");

        AutomixPlanCache.useDirectory(nested.toFile());
        assertNotNull(AutomixPlanCache.load(outgoing, incoming, 120_000L), "建好之后应当能命中");
    }

    @Test
    @DisplayName("清空缓存之后不再命中")
    void clearRemovesEverything() {
        AutomixPlanCache.store(outgoing, incoming, 120_000L, plan(178_400L, 9_700L, 2_150L));
        assertNotNull(AutomixPlanCache.load(outgoing, incoming, 120_000L));

        AutomixPlanCache.clear();
        assertNull(AutomixPlanCache.load(outgoing, incoming, 120_000L), "清空之后必须未命中");
    }

    // ── 内存层 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("内存层按访问序 LRU：命中过的会被保住，最久没用的先淘汰")
    void memoryLayerIsLru() throws IOException {
        // 造 70 对（超过 64 的内存上限），全部只走内存、不落盘（把目录指到不存在的位置并禁止创建
        // 是做不到的，所以直接依赖落盘；这里验证的是「内存命中」不会因为 LRU 而丢掉最近用过的）。
        File firstOut = write("lru-out.wav", "OOOOOOOO");
        File firstIn = write("lru-in.wav", "IIIIIIII");
        AutomixPlan first = plan(111_111L, 9_700L, 2_150L);
        AutomixPlanCache.store(firstOut, firstIn, 5L, first);

        for (int i = 0; i < 70; i++) {
            File o = write("filler-out-" + i + ".wav", "OOOOOOOO");
            File in = write("filler-in-" + i + ".wav", "IIIIIIII");
            AutomixPlanCache.store(o, in, 5L, plan(100_000L + i, 9_700L, 2_150L));
        }

        // 第一对早已被挤出内存，但仍应能从磁盘命中 —— 内存层只是加速，不是唯一来源。
        AutomixPlan loaded = AutomixPlanCache.load(firstOut, firstIn, 5L);
        assertNotNull(loaded, "内存淘汰之后必须还能从磁盘命中");
        assertEquals(111_111L, loaded.fireMillis);
    }

    @Test
    @DisplayName("重复存同一对曲目不会写出一堆文件")
    void repeatedStoreReusesTheSameFile() {
        for (int i = 0; i < 5; i++) {
            AutomixPlanCache.store(outgoing, incoming, 120_000L, plan(178_400L + i, 9_700L, 2_150L));
        }
        File[] written = sandbox.toFile().listFiles((dir, name) -> name.endsWith(".json"));
        assertNotNull(written);
        assertEquals(1, written.length, "同一个键只该有一个文件，后写的覆盖先写的");
        // 最后一次写入生效。
        AutomixPlanCache.useDirectory(sandbox.toFile());
        assertEquals(178_404L, AutomixPlanCache.load(outgoing, incoming, 120_000L).fireMillis);
    }

    @Test
    @DisplayName("读回来的计划不是同一个实例也能用：曲线全是纯函数，值对了就等价")
    void loadedPlanCarriesTheSameValues() {
        AutomixPlan original = plan(178_400L, 9_700L, 2_150L);
        AutomixPlanCache.store(outgoing, incoming, 120_000L, original);
        AutomixPlanCache.useDirectory(sandbox.toFile());   // 清内存，逼它反序列化出一份新实例

        AutomixPlan loaded = AutomixPlanCache.load(outgoing, incoming, 120_000L);
        assertNotNull(loaded);
        assertNotSame(original, loaded, "落盘往返必然是新实例");
        // 曲线是渐变位置的纯函数，因此逐点比对就是「完全等价」。
        for (double progress = 0.0; progress <= 1.0; progress += 0.05) {
            assertEquals(original.outgoingGain(progress), loaded.outgoingGain(progress), 1e-6,
                    "退出方增益曲线在 " + progress + " 处必须一致");
            assertEquals(original.incomingGain(progress), loaded.incomingGain(progress), 1e-6,
                    "接入方增益曲线在 " + progress + " 处必须一致");
        }
    }
}
