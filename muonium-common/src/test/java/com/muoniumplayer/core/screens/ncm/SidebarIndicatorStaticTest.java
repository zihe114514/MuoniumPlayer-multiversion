package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 侧栏高亮条（选中的那一栏旁边那条强调色块）动效的离线契约测试。
 *
 * <p>和 {@link SheetMotionStaticTest}、{@link SegmentControlStaticTest} 同一条理由：这条东西改坏了
 * 不会崩，只会难看 —— 而它难看的形态恰好是用户报过的那两种：拖着一尾巴黏到位，或者干脆瞬移
 * 让人抓不到「选中态是从哪一格过来的」。</p>
 *
 * <p>曲线那一段是<b>真跑</b>：过冲曲线必须是「中段越过 1、终点精确回到 1」，这个数不靠记忆。
 * 其余各条只能读源码，因为侧栏一加载就要建 GL 上下文。</p>
 */
class SidebarIndicatorStaticTest {

    private static final Path NCM = ProjectPaths.sharedJava().resolve(Paths.get(
            "com", "muoniumplayer", "core", "screens", "ncm"));
    private static final Path NAVIGATE = NCM.resolve("panels").resolve("NavigateBar.java");

    @Test
    @DisplayName("过冲曲线中段越过 1、终点精确落回 1")
    void springCurveOvershootsAndSettles() {
        double peak = TransitionCurveCache.SPRING_LAND.at(.6);
        assertTrue(peak > 1.05,
                "前缘曲线在 60% 时长处只走到 " + peak + "：越过幅度不到 5%，橡皮筋根本看不出来");
        assertTrue(peak < 1.25,
                "越过幅度 " + peak + " 已经过火，长距离跳转时会像没停住");
        assertTrue(TransitionCurveCache.SPRING_LAND.at(.3) > .85,
                "前缘曲线 30% 时长还没走完大半行程：那就不是「弹过去钉住」而是「漂过去停下」");
        // 终点必须精确等于 1，否则条子会停在过冲上，永远比行高截一截。
        assertTrue(Math.abs(TransitionCurveCache.SPRING_LAND.at(1.0) - 1.0) < 1.0e-6,
                "过冲曲线没有精确落回 1：高亮条会停在越过目标的那一格");
        // 后缘不许过冲，否则两条边一起越过，条子只是在原地抖。
        assertTrue(TransitionCurveCache.MOTION_LAGGING.at(.6) < 1.0,
                "后缘曲线也带过冲：拉伸与回弹互相抵消，形变又看不见了");
    }

    @Test
    @DisplayName("高亮条走真毫秒时钟，不许留帧耦合指数")
    void indicatorRunsOnARealClock() throws IOException {
        String body = slice(read(NAVIGATE), "private void layoutSelectionIndicator()");
        assertFalse(body.contains("Interpolations.interpolate"),
                "高亮条又回到帧耦合指数：没有时长也没有终点，尾巴无限逼近就是「黏」");
        assertTrue(body.contains("System.nanoTime"), "没有自己的真实时间来源");
        assertTrue(body.contains("INDICATOR_MAX_FRAME_MS"),
                "没有单帧上限：掉一帧就跨过小半条曲线，整段变成一顿一顿");
        // 倍率只该有一份 clamp，第二份迟早和 HUD 编辑器里那档对不上。
        assertTrue(body.contains("SheetClock.speed()"), "时长没有过「动画速度」倍率");
        assertFalse(body.contains("playerUiSwitchSpeed"),
                "侧栏自己写了一份 playerUiSwitchSpeed：该复用 SheetClock.speed() 那一份");
    }

    @Test
    @DisplayName("两条边读同一条进度、各走各的曲线")
    void edgesShareTheLegButSplitLeadFromTrail() throws IOException {
        String body = read(NAVIGATE);
        String layout = slice(body, "private void layoutSelectionIndicator()");
        assertTrue(layout.contains("SPRING_LAND.at(indicatorLegT)")
                        && layout.contains("MOTION_LAGGING.at(indicatorLegT)"),
                "前缘与后缘没有分曲线：条子不再被拽长，切换就只剩位移");
        // 方向决定哪条边是前缘，所以两条边必须各自按方向挑一次。
        assertTrue(layout.contains("indicatorLeadBottom ? trail : lead")
                        && layout.contains("indicatorLeadBottom ? lead : trail"),
                "上下边沿用了同一条曲线：朝上切换和朝下切换的拉伸方向会反");
        assertTrue(body.contains("INDICATOR_OVERSHOOT_CAP"),
                "过冲没有按像素封顶：跨四行跳转时 9.7% 的过冲就是十几像素的过火");
        // 淡入淡出与位移同源：位移期间不许还在慢慢变亮。
        assertTrue(layout.contains("indicatorPresence"),
                "亮度又独立于位移走一条自己的指数：条子到位了还在继续变亮");
    }

    @Test
    @DisplayName("换行才起新趟，滚动只是跟着目标走")
    void scrollIsNotTreatedAsARowSwitch() throws IOException {
        String body = read(NAVIGATE);
        String layout = slice(body, "private void layoutSelectionIndicator()");
        assertTrue(layout.contains("selected != indicatorOwner"),
                "换行判定又用几何差：列表一滚就被误判成换行，每帧重起一趟，条子永远追不上那一行");
        assertFalse(layout.contains("targetTop > indicatorTop"),
                "方向又改成拿几何差判：同一行被滚走时它也会翻转");
        // 起点必须是条子此刻的位置，不是上一行的位置。
        assertTrue(layout.contains("residualTop = indicatorTop - targetTop"),
                "残差不从当前位置起算：连点两行会先跳回上一行再走，那一下闪动就是「先窜后糊」");
    }

    /** 从签名处截到下一个方法之前，够覆盖整段实现。 */
    private static String slice(final String source, final String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, "找不到 " + signature);
        return source.substring(at, Math.min(source.length(), at + 3600));
    }

    /**
     * 读源码并<b>剥掉注释</b>。
     *
     * <p>这些断言钉的是「不许出现某种写法」，而实现自己的 javadoc 里正好写着
     * {@code Interpolations.interpolate} 与 {@code targetTop > indicatorTop} —— 那是解释为什么
     * 不用它们。不剥注释就会自己把自己钉红。</p>
     */
    private static String read(final Path path) throws IOException {
        String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//[^\n]*", "");
    }
}
