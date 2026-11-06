package com.muoniumplayer.core.ncm.music.acoustics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.muoniumplayer.testsupport.ProjectPaths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段一/二的调度契约:自适应采样 + 跨房间快收敛。
 *
 * <p>这两项都改在「多快到达目标」而不是「目标是什么」,所以断言写的是
 * 结构存在性与参数区间 —— 声学习性由 {@link SceneAcousticsTest} 与
 * {@link SceneResponseTest} 守。</p>
 */
class RoomTransitionStaticTest {

    private static String shared(String relative) throws IOException {
        Path path = ProjectPaths.sharedJava().resolve(relative);
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }

    @Test
    @DisplayName("采样调度:自适应两档周期 + latest-wins 待办位(不再是 busy-skip)")
    void adaptiveSamplingAndLatestWins() throws IOException {
        final String runtime = shared("com/muoniumplayer/core/ncm/music/acoustics/AcousticsRuntime.java");
        assertTrue(runtime.contains("INTERVAL_MOVING_MS"), "缺移动档周期");
        assertTrue(runtime.contains("INTERVAL_SETTLED_MS"), "缺站定档周期");
        assertTrue(runtime.contains("SETTLE_AFTER_MS"), "缺站定判据");
        assertTrue(runtime.contains("PENDING"),
                "缺 latest-wins 待办位:采样在飞时必须记一笔,而不是直接丢掉本轮");
        // busy-skip 的典型写法:在飞就直接 return,一个字节都不留。
        assertFalse(runtime.contains("上一轮还没跑完:本轮跳过"),
                "仍是 busy-skip:移动时采样周期会翻倍");
    }

    /** 七棵版本树 —— 任何「树内独占文件」的功能都必须在这七处齐全。 */
    private static final String[] TREES = {
            "1.20.1 recode", "1.21.1", "1.21.10", "1.21.11 recode", "26.1.2", "26.2", "26.3",
    };

    /** 读某棵树的 ListenerProbe 源码。 */
    private static String probe(String tree) throws IOException {
        final Path path = ProjectPaths.repoRoot().resolve(tree)
                .resolve("src/main/java/com/muoniumplayer/compat/mc/ListenerProbe.java");
        assertTrue(Files.exists(path), "缺文件: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }

    @Test
    @DisplayName("采样原点由树侧提供:接口有 default,七树 ListenerProbe 都覆盖")
    void originMetersProvidedByEveryTree() throws IOException {
        final String runtime = shared("com/muoniumplayer/core/ncm/music/acoustics/AcousticsRuntime.java");
        assertTrue(runtime.contains("default double[] originMeters()"),
                "采样原点必须是 default 方法:不覆盖的树要能继续用固定周期");

        for (String tree : TREES) {
            final String src = probe(tree);
            assertTrue(src.contains("public double[] originMeters()"),
                    tree + " 没覆盖 originMeters:该树的自适应采样不会生效");
            assertTrue(src.contains("RAY_COUNT_MOVING") && src.contains("RAY_COUNT_SETTLED"),
                    tree + " 缺射线数自适应两档");
        }
    }

    @Test
    @DisplayName("遮挡边缘绕射:七树都实现新契约并真的调用搜索")
    void diffractionImplementedByEveryTree() throws IOException {
        for (String tree : TREES) {
            final String src = probe(tree);
            // 六个字段缺任一,共享层就会静默退化为纯穿透(功能「看起来装了」但不生效)
            for (String field : new String[] {
                    "public boolean hasDiffraction()",
                    "public float diffractionPathMeters()",
                    "public float diffractionExcessMeters()",
                    "public float diffractionBendDegrees()",
                    "public double diffractionEdgeX()",
                    "public float diffractionPan()"}) {
                assertTrue(src.contains(field), tree + " 缺绕射契约: " + field);
            }
            // 定义了不算数,必须真的在采集路径上调搜索;且必须有过遮挡闸
            assertTrue(src.contains("findDiffraction(world, origin, eye)"),
                    tree + " 没调用绕射搜索:契约有了但永远是 false");
            assertTrue(src.contains("if (directOccl >= 1.0f)"),
                    tree + " 缺「有实心遮挡才搜」的闸:开阔地会白跑 48 次体素投射");
        }
    }

    @Test
    @DisplayName("树内不得有共享层的同名副本 —— 否则旧版本会遮蔽共享实现")
    void noShadowingCopiesInTrees() throws IOException {
        final Path root = ProjectPaths.repoRoot();
        for (String tree : TREES) {
            final Path pkg = root.resolve(tree)
                    .resolve("src/main/java/com/muoniumplayer/core/ncm/music/acoustics");
            if (!Files.isDirectory(pkg)) {
                continue;   // 完全没有更干净
            }
            try (java.util.stream.Stream<Path> files = Files.list(pkg)) {
                final java.util.List<String> names = files
                        .filter(p -> p.toString().endsWith(".java"))
                        .map(p -> p.getFileName().toString())
                        .toList();
                assertTrue(names.isEmpty(),
                        tree + " 的树内 acoustics 包里有共享层副本,会遮蔽共享实现: " + names);
            }
        }
    }

    @Test
    @DisplayName("共享源码真的挂在七树的构建脚本上 —— 没挂载,共享层改了也不生效")
    void commonSourcesMountedInEveryTreeBuild() throws IOException {
        final Path root = ProjectPaths.repoRoot();
        for (String tree : TREES) {
            boolean mounted = false;
            for (String loader : new String[] {"fabric", "neoforge", "forge"}) {
                final Path gradle = root.resolve(tree).resolve(loader).resolve("build.gradle");
                if (!Files.exists(gradle)) {
                    continue;
                }
                final String src = new String(Files.readAllBytes(gradle), StandardCharsets.UTF_8);
                if (src.contains("muonium-common/src/main/java")) {
                    mounted = true;
                    break;
                }
            }
            assertTrue(mounted, tree + " 的任何加载器子工程都没挂 muonium-common/src/main/java");
        }
    }

    @Test
    @DisplayName("过渡时长:RoomPlan 有字段、SceneResponse 之后由运行时补齐、SceneAcoustics 真的用")
    void transitionSecFlowsEndToEnd() throws IOException {
        final String plan = shared("com/muoniumplayer/core/ncm/music/acoustics/RoomPlan.java");
        assertTrue(plan.contains("public final float transitionSec"), "RoomPlan 缺 transitionSec");

        final String runtime = shared("com/muoniumplayer/core/ncm/music/acoustics/AcousticsRuntime.java");
        assertTrue(runtime.contains("withTransition"), "运行时没给计划补过渡时长");
        assertTrue(runtime.contains("looksLikeRoomChange"), "缺跨房间判定");

        final String dsp = shared("com/muoniumplayer/core/ncm/music/acoustics/SceneAcoustics.java");
        assertTrue(dsp.contains("targetTransitionSec"), "SceneAcoustics 没接过渡时长");
        // 必须真的参与一阶收敛的系数计算,而不是只存着
        assertTrue(dsp.contains("Math.min(TRANSITION_MAX_SEC, tTransition)")
                        || dsp.contains("transitionSec"),
                "过渡时长没参与收敛系数");
    }

    @Test
    @DisplayName("过渡时长不得超出夹取区间:太快会瞬切咔哒,太慢像卡住")
    void transitionBoundsAreSane() throws IOException {
        final String dsp = shared("com/muoniumplayer/core/ncm/music/acoustics/SceneAcoustics.java");
        assertTrue(dsp.contains("TRANSITION_MIN_SEC") && dsp.contains("TRANSITION_MAX_SEC"),
                "缺夹取区间");
        // 读到常量值做区间校验(防止有人把下界调到 0 制造瞬切)
        final float min = staticFloat(dsp, "TRANSITION_MIN_SEC");
        final float max = staticFloat(dsp, "TRANSITION_MAX_SEC");
        assertTrue(min >= 0.05f, "过渡下界过低(会瞬切): " + min);
        assertTrue(max <= 2.0f, "过渡上界过高(会像卡住): " + max);
        assertTrue(min < max);
    }

    /** 从源码里抠一个 {@code static final ... NAME = <number>;} 的值。 */
    private static float staticFloat(String src, String name) {
        final int at = src.indexOf(name + " =");
        assertTrue(at >= 0, "找不到常量: " + name);
        int end = src.indexOf(';', at);
        final String raw = src.substring(at + name.length() + 2, end)
                .replace("f", "").replace("F", "").replace("d", "").replace("D", "").trim();
        // 允许简单的「数字 表达式」形态:取第一个数字
        final StringBuilder num = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            final char ch = raw.charAt(i);
            if (Character.isDigit(ch) || ch == '.' || (ch == '-' && num.length() == 0)) {
                num.append(ch);
            } else if (num.length() > 0) {
                break;
            }
        }
        assertTrue(num.length() > 0, "常量不是数值: " + name + " = " + raw);
        return Float.parseFloat(num.toString());
    }
}
