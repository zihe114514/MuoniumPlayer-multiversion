package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「人声走素材淡入」这条新路的<b>接法</b>必须钉住。
 *
 * <p>混合本身对不对由 {@code repackage.processing.sound.VocalMaterialSampleTest} 真跑采样值证；
 * 这一类管的是另一件事：这条路接在链路的哪一格、谁有权改这条 deck 的人声控制、以及关掉之后
 * 是不是真的什么都不发生。这几条在单测里跑不出真实播放路径，所以读源码。</p>
 *
 * <p>三条最贵的性质，按代价排：</p>
 * <ol>
 *   <li><b>不激活时逐采样一致</b> —— 这一层挂在每一首歌的取源链上，正常听歌也经过它；</li>
 *   <li><b>接了素材就把带限抵消器归零</b> —— 两条同时全开会在那条人声上减两遍，是一段中空；</li>
 *   <li><b>素材必须在伸缩层之内、曲线之前</b> —— 顺序反过来，WSOLA 的接缝会把残差搬着走。</li>
 * </ol>
 */
class VocalMaterialStaticTest {

    private static final Path JAVA = ProjectPaths.sharedJava();
    private static final Path RESOURCE = ProjectPaths.sharedResources();

    @Test
    @DisplayName("取源链的组装顺序：原始素材 → 素材层 → 伸缩层 → 读头")
    void materialLayerSitsUnderTheStretchLayer() throws Exception {
        String sound = read(JAVA.resolve(Paths.get("repackage", "processing", "sound", "SoundFile.java")));
        int material = sound.indexOf("new VocalMaterialSample(SampleLoader.loadStreamedFloatSample");
        assertTrue(material >= 0, "素材层要直接包住刚加载的原始素材，中间不能再接别的层");
        int stretched = sound.indexOf("WsolaStretchSample.wrap(this.vocalMaterial)");
        assertTrue(stretched > material, "伸缩层要在素材层之上：变速要作用在已经减过人声的素材上");
    }

    @Test
    @DisplayName("不激活时这一层是恒等的，而且一次都不读素材")
    void idleBranchIsPassThrough() throws Exception {
        String layer = read(JAVA.resolve(Paths.get("repackage", "processing", "sound",
                "VocalMaterialSample.java")));
        String body = between(layer, "public double readDouble(int index)", "@Override");
        // 直通分支必须在读原始采样之后、混合之前返回，且判据是「没有素材 或 level 回到 1」。
        assertTrue(body.indexOf("if (held == null || voice >= 1.0 - IDLE_EPSILON) return raw;") > 0,
                "不激活时必须直接还内层的值：这一层站在每一首歌的取源链上");
        assertTrue(body.indexOf("return raw;") < body.indexOf("held.samples[(int) position]"),
                "混合只能发生在直通判据之后，否则每首歌每个采样都要读一遍素材数组");
    }

    @Test
    @DisplayName("接了素材就把抵消器归零，三条发布路径一条都不能漏")
    void materialTakesOverTheDuckOnEveryPublishPath() throws Exception {
        String player = read(music("AudioPlayer.java"));
        String route = between(player, "private float routeVocalDepth(float depth)",
                "public boolean attachVocalMaterial(FoliaVocalStem stem)");
        assertTrue(route.contains("!deck.hasVocalMaterial()) return depth;"),
                "没接素材时这条路由必须整个不动，退回今天的抵消器");
        assertTrue(route.contains("deck.setVocalMaterialLevel(1.0 - depth)"), "深度按补数交给素材");
        assertTrue(route.contains("return 0f"), "素材接管时滤波链深度必须是 0，两条不能同时全开");

        // 三条把深度落到信号图上的路：单次 setter、每帧曲线、以及 prepareStart 里的链复位。
        String setter = between(player, "public void setVocalDuckDepth(float depth)",
                "public float getVocalDuckDepth()");
        assertTrue(setter.contains("chain.duckDepth(routeVocalDepth(safe))"), "setter 那条要过路由");
        String frame = between(player, "void applyAutomixFrame(float fade", "淡出 deck 的音频线程版本");
        assertTrue(frame.contains("chain.setTargetsNow(this.bassGainDb, routeVocalDepth(this.vocalDuckDepth)"),
                "每帧曲线那条要过路由");
        String attach = between(player, "private void attachAutomixChain()", "private int channelCount()");
        assertTrue(attach.contains("resetTo(this.bassGainDb, routeVocalDepth(this.vocalDuckDepth))"),
                "预卷那条会 resetTo 字段值：不过路由就会在 prepareStart 时把抵消器重新按上去，减两遍");
    }

    @Test
    @DisplayName("闸、装载时机、退料：三处都在该在的位置")
    void gateAttachTimingAndRelease() throws Exception {
        String cloud = read(music("CloudMusic.java"));
        String helper = between(cloud, "private FoliaVocalStem attachVocalMaterial(AudioPlayer deck",
                "把后台算好的新计划装上");
        assertTrue(helper.contains("if (!AutomixSettings.isAdvancedModeEnabled()) return null;"),
                "进阶模式关掉时这条 deck 必须保持今天的信号图");

        // 装载要在进场方第一次设深度之前：深度一设下去就按「有没有素材」分派了，反过来会漏一个音节。
        String arm = between(cloud, "track.vocalMaterial = attachVocalMaterial(deck, track.plan);",
                "deck.prepareStart(track.plan.incomingCueMillis - track.preRollMillis);");
        assertTrue(arm.contains("deck.setVocalDuckDepth(track.plan.incomingVocalDepth(0.0))"),
                "装载与设深度之间只剩这一步，顺序不能倒");

        // 重算换了入点，素材可能就不盖住接缝了：盖不住必须整个退掉。
        String replan = between(cloud, "private void installReplannedPlan(ArmedTrack track",
                "用户按下的");
        assertTrue(replan.contains("deck.releaseVocalMaterial();"), "出了窗口要退料");
        assertTrue(replan.indexOf("deck.releaseVocalMaterial();")
                        < replan.indexOf("deck.setVocalDuckDepth(fresh.incomingVocalDepth(0.0))"),
                "退料要在设新深度之前，否则这一次设的深度还是按「素材接管」发给滤波链的 0");

        String player = read(music("AudioPlayer.java"));
        String reset = between(player, "public void resetAutomixProcessing()", "private void releaseAutomixChain");
        assertTrue(reset.indexOf("setVocalDuckDepth(0.0f)") < reset.indexOf("releaseVocalMaterial()"),
                "先归位（level 回 1）再退料：反过来的话滤波链会接到一次素材留下的旧深度");
    }

    @Test
    @DisplayName("采样率对不上就不接；素材窗口盖不住接缝前半段也不接")
    void refusesWhenItCannotAlignOrDoesNotCover() throws Exception {
        String stem = read(music("FoliaVocalStem.java"));
        assertTrue(stem.contains("if (deckRate != FoliaStemProfile.MATERIAL_RATE)"),
                "两次插值叠加会留下错位的残差，比不启用更糟 —— 只能同率相减");
        assertTrue(stem.contains("!stems.hasVocalMaterial()"), "投影系数不可信时整条路放弃");
        assertTrue(stem.contains("file.length() != frames * FLOAT_BYTES"),
                "半截素材当好的用，减下去的位置就是错的");
        String covers = between(stem, "boolean covers(long cueMillis, long overlapMillis, int deckRate)", "分块读");
        assertTrue(covers.contains("overlapMillis) / 2L <= endMillis(deckRate)"),
                "只要求盖住接缝前半段：后半段人声本来就要回来，前半段越出去才是两个人一起唱");
    }

    @Test
    @DisplayName("素材只给进场侧写，且缓存有上限")
    void materialIsWrittenForIncomingOnlyAndCapped() throws Exception {
        String profiler = read(music("FoliaTrackProfiler.java"));
        assertTrue(profiler.contains("outgoing ? null : stemFile(profileKey)"),
                "出场侧只要时间戳，那份素材没人读，别为它占 5 MB");
        assertTrue(profiler.contains("private static final long MATERIAL_CACHE_BYTES"),
                "素材是这份缓存里唯一大的东西，不封顶就等于往配置目录里塞几个 G");
        String trim = between(profiler, "private static void trimVocalMaterial()", "private static boolean hasBeatModel()");
        assertTrue(trim.contains(".voc.f32") && trim.contains("file.delete()"), "按最旧的先删");

        String separator = read(music("FoliaStemSeparator.java"));
        assertTrue(separator.contains("materialPart == null ? \"-\" : materialPart.getAbsolutePath()"),
                "不要素材时要把占位符传给脚本，不能少一个参数");
        assertTrue(separator.contains("part.length() != profile.materialFrames * FLOAT_BYTES"),
                "长度对不上就不发布：宁缺勿错位");
        assertFalse(separator.contains("return profile.isUsable() ? profile : null;"),
                "usable 也不能直接返回：素材要么落盘要么把 .part 清掉");
    }

    @Test
    @DisplayName("脚本端确实把素材和投影系数发出来")
    void runnerEmitsMaterialAndProjection() throws Exception {
        String script = read(RESOURCE.resolve(Paths.get("muonium", "automix", "folia_htdemucs.py")));
        assertTrue(script.contains("material_path = sys.argv[4]"), "第四个参数是素材输出路径");
        assertTrue(script.contains("def write_vocal_material("), "素材写的是单声道 float32");
        assertTrue(script.contains("\"vocal_projection\""), "投影系数要进 JSON");
        assertTrue(script.indexOf("write_vocal_material(material_path, vocal)") > 0);
        // 投影系数只统计有人唱的时间格，否则会被静音段拉向 0。
        String body = between(script, "def vocal_projection(", "def main():");
        assertTrue(body.contains("if env[index] < threshold"), "静音格不能进这份系数");
        assertTrue(body.contains("numerator / denominator"), "最小二乘投影，不是拍一个常数");
    }

    private static Path music(String name) {
        return JAVA.resolve(Paths.get("com", "muoniumplayer", "core", "ncm", "music", name));
    }

    /**
     * 取 {@code from} 到 {@code to} 之间的源码（含头不含尾）。找不到标记就直接说找不到哪一个，
     * 不要让人去猜那句 StringIndexOutOfBounds。
     */
    private static String between(String source, String from, String to) {
        int start = source.indexOf(from);
        assertTrue(start >= 0, "源码里找不到开头：" + from);
        int end = source.indexOf(to, start + from.length());
        assertTrue(end > start, "找不到结尾（在 " + from + " 之后）：" + to);
        return source.substring(start, end);
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
