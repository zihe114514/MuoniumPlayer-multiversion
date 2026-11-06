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

    @Test
    @DisplayName("采样原点由树侧提供:接口有 default,七树 ListenerProbe 都覆盖")
    void originMetersProvidedByEveryTree() throws IOException {
        final String runtime = shared("com/muoniumplayer/core/ncm/music/acoustics/AcousticsRuntime.java");
        assertTrue(runtime.contains("default double[] originMeters()"),
                "采样原点必须是 default 方法:不覆盖的树要能继续用固定周期");

        final String[] trees = {
                "1.20.1 recode", "1.21.1", "1.21.10", "1.21.11 recode", "26.1.2", "26.2", "26.3",
        };
        final Path root = ProjectPaths.repoRoot();
        for (String tree : trees) {
            final Path probe = root.resolve(tree)
                    .resolve("src/main/java/com/muoniumplayer/compat/mc/ListenerProbe.java");
            assertTrue(Files.exists(probe), "缺文件: " + probe);
            final String src = new String(Files.readAllBytes(probe), StandardCharsets.UTF_8);
            assertTrue(src.contains("public double[] originMeters()"),
                    tree + " 没覆盖 originMeters:该树的自适应采样不会生效");
            assertTrue(src.contains("RAY_COUNT_MOVING") && src.contains("RAY_COUNT_SETTLED"),
                    tree + " 缺射线数自适应两档");
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
