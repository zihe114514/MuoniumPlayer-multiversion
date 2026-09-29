package com.muoniumplayer.core.screens.onboarding;

import com.muoniumplayer.core.rendering.svg.AnimatedIcons;
import com.muoniumplayer.core.rendering.svg.SmilSvg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖层那两枚招呼字的时间轴：来回放、满字端停两秒、hello 与 welcome 轮着来、
 * 且大帧不许把动画带跑、开头那两颗圆头点不许露出来，还有收层那道 skip 门禁。
 *
 * <p>门禁放在这里一起钉，是因为它读的就是这枚字自己的秒数（{@link
 * OnboardingGreeting#elapsedSeconds()}）：「等满 5 秒」等的是<b>动画</b>，不是墙上时钟。
 * 一条纯函数（{@code OnboardingOverlay.gateRemainingSeconds}）加一条文案函数
 * （{@code gateHintText}），跟 Skija 和界面都没有关系，正好能在游戏外钉死。</p>
 *
 * <h2>为什么必须有</h2>
 *
 * <p>这枚字报过三次同样的病：{@code 打开时 Hello 动画已经放了一半}、{@code 没按任何键它自己就消失了}、
 * {@code 首帧错误展示起始点}。前两条的原因是它过去挂在 {@code SvgPlayback} 那条<b>墙上时钟</b>上，
 * 而同页的淡入、模糊、压暗全走 {@code Interpolations} 的<b>按帧步进</b>（那里写着「帧间隔超过
 * 200 ms 把步长压成 0」）——两条时钟在「打开界面那一下」行为完全相反，实测那一局被钳掉的最大帧
 * 是 1738 ms，而描一轮总共 3447 ms。第三条是另一件事：素材开头 {@code stroke-dasharray="0 100"}
 * 配 {@code stroke-linecap="round"}，Skia 会在两个零长划上各补一个圆头，于是字还没写就先落两滴墨。</p>
 *
 * <p>这些映射都写成了静态纯函数，于是可以在<b>游戏外</b>钉死：两头停留各多长、周期是否严格闭合、
 * 曲线连不连续、一枚字什么时候交棒给下一枚、哪一帧该是一个像素都不画。真机截图只能证明截到的
 * 那一帧对不对，这里证明的是整条时间轴。</p>
 */
class OnboardingGreetingStaticTest {

    /** 一个好算的假素材长度（秒）。真实长度另有一根钉子。 */
    private static final double SPAN = 3.0;
    /** 玩家定的口径：满字那一端停 2 秒。这个数字被产品钉在这里，改代码不改口径就红。 */
    private static final double HOLD = 2.0;
    /** 空白那一端的停留。现在是 0（擦干净立刻写下一枚字），要改成 2 也只动这一个数。 */
    private static final double TURN = 0.0;

    private static double timeline(double elapsed) {
        return OnboardingGreeting.timelineSeconds(elapsed, SPAN, HOLD, TURN);
    }

    /** 一枚字来回一圈。和 {@code OnboardingGreeting.Word#cycle} 同一个式子。 */
    private static double cycleOf(double span) {
        return 2.0 * span + HOLD + TURN;
    }

    @Test
    @DisplayName("来回放：正描 → 停在满字 → 倒着擦回空白 → 再正描")
    void bouncesBetweenTheTwoEnds() {
        // 起表那一帧画的是空白：玩家看到的第一个像素必须是第一笔。
        assertEquals(0.0, timeline(0), 1e-9);
        assertEquals(SPAN * .25, timeline(SPAN * .25), 1e-9);
        assertEquals(SPAN * .5, timeline(SPAN * .5), 1e-9);
        assertEquals(SPAN, timeline(SPAN), 1e-9);

        // 过了停留窗口就开始往回擦，擦到空白。
        assertEquals(SPAN * .5, timeline(SPAN * 1.5 + HOLD), 1e-9,
                "倒着擦回去时画面应该在半路，不是还在满字那一帧");
        assertEquals(0.0, timeline(2 * SPAN + HOLD), 1e-9,
                "擦回去应该恰好落在空白那一帧，不能擦进素材回卷之后的假区间");
        // 第二圈从头描。
        assertEquals(SPAN * .3, timeline(cycleOf(SPAN) + SPAN * .3), 1e-9,
                "第二个来回没有从头描：周期闭合出了问题");
    }

    @Test
    @DisplayName("满字那一端停整整 2 秒，一秒不多、一秒不少")
    void holdsAreExactlyTwoSeconds() {
        assertEquals(2.0, OnboardingGreeting.HOLD_SECONDS, 1e-9, "玩家要的是每次停顿 2 秒");
        assertEquals(TURN, OnboardingGreeting.TURN_SECONDS, 1e-9,
                "空白端的口径变了：现在擦干净就立刻落下一枚字的笔");

        for (int i = 0; i <= 40; i++) {
            double t = SPAN + HOLD * i / 40.0;
            assertEquals(SPAN, timeline(t), 1e-9,
                    "第 " + Math.round((t - SPAN) * 1000) + " ms 就动了，这段停留不够长");
        }
        // 窗口外一毫秒就该接着走，否则停留变成了「卡住」。
        assertTrue(Math.abs(timeline(2 * SPAN + HOLD + .001) - SPAN) > 1e-6,
                "停留结束之后时间轴仍然没动：停 2 秒被实现成了永久停住");
    }

    @Test
    @DisplayName("时间轴永不越过素材说的画满帧，也永不为负")
    void neverLeavesTheDesignedRange() {
        for (double t = 0; t < 600.0; t += .017) {
            double time = timeline(t);
            assertTrue(time >= 0 && time <= SPAN + 1e-9,
                    "第 " + t + " 秒问素材要的是 " + time + " 秒，跑出了 0.." + SPAN + " 这段有效画面");
        }
        // 顶点取的是素材自己说的画满相位，不是整轮：走到 dur 会回卷成「一笔都不画」。
        SmilSvg hello = AnimatedIcons.hello();
        SmilSvg welcome = AnimatedIcons.welcome();
        assertNotNull(hello, "hello.svg 读不到");
        assertNotNull(welcome, "welcome.svg 读不到");
        assertTrue(SPAN < hello.duration(), "测试用的假长度已经不小于 hello 那一轮了，这根钉子失效");
        assertTrue(SPAN < welcome.duration(), "测试用的假长度已经不小于 welcome 那一轮了，这根钉子失效");
    }

    @Test
    @DisplayName("周期严格闭合：连着放十分钟也不会越走越偏")
    void loopIsExactlyPeriodic() {
        double period = cycleOf(SPAN);
        for (double t = 0; t < period; t += .137) {
            assertEquals(timeline(t), timeline(t + period * 7.0), 1e-6,
                    "第 " + t + " 秒和七个来回之后的同一秒画的不是一帧");
        }
    }

    @Test
    @DisplayName("连续：一帧之间最多只能走一帧的量，画面不许跳")
    void motionIsContinuous() {
        double previous = timeline(0);
        for (double t = .016; t < 120.0; t += .016) {
            double now = timeline(t);
            assertTrue(Math.abs(now - previous) <= .016 + 1e-9,
                    "从 " + previous + " 跳到 " + now + "：这一帧跨掉了描字的一笔");
            previous = now;
        }
    }

    @Test
    @DisplayName("大帧只让动画慢一帧：1.7 秒的卡顿不许吃掉半条描字")
    void bigFramesStallInsteadOfSkipping() {
        // 打开引导那一下的真实形状：截图回读实测 544 ms，一局里被钳掉的最大帧是 1738 ms。
        // 按墙上时钟这就是「白走了半秒动画」；按这里只走一帧的上限。
        assertEquals(OnboardingGreeting.MAX_FRAME_SECONDS,
                OnboardingGreeting.clampStep(1.7), 1e-12,
                "大帧被照单全收了：玩家会看到字已经描了一半");
        assertEquals(OnboardingGreeting.MAX_FRAME_SECONDS,
                OnboardingGreeting.clampStep(60.0), 1e-12);
        // 正常帧原样通过，别把动画拖成慢放。
        assertEquals(1.0 / 60.0, OnboardingGreeting.clampStep(1.0 / 60.0), 1e-12);
        assertEquals(0.0, OnboardingGreeting.clampStep(-3.0), 1e-12,
                "nanoTime 倒挂（换时钟源/休眠唤醒）时时间轴会倒退");
        // 口径本身：40ms ≈ 25fps 以下宁可变慢，也不许跳帧。
        assertEquals(.040, OnboardingGreeting.MAX_FRAME_SECONDS, 1e-12);
    }

    @Test
    @DisplayName("hello 描完交给 welcome，welcome 擦完再交给 hello，节拍不漂")
    void wordsTakeTurnsWithoutDrifting() {
        double hello = cycleOf(3.4);
        double welcome = cycleOf(3.2);
        double[] cycles = {hello, welcome};
        double pair = hello + welcome;

        assertEquals(0, OnboardingGreeting.wordIndexAt(0, cycles), "起表该由 hello 落笔");
        assertEquals(1, OnboardingGreeting.wordIndexAt(hello + .001, cycles),
                "hello 擦干净之后该由 welcome 接上");
        assertEquals(0, OnboardingGreeting.wordIndexAt(pair + .001, cycles),
                "welcome 之后该换回 hello");
        // 交棒那一刻各自的时间轴都从 0 起算，不然第二枚字会「一出现就描了一半」。
        assertEquals(0.0, OnboardingGreeting.localElapsedAt(hello, cycles), 1e-9);
        assertEquals(0.0, OnboardingGreeting.localElapsedAt(pair, cycles), 1e-9);
        assertEquals(.5, OnboardingGreeting.localElapsedAt(hello + .5, cycles), 1e-9,
                "welcome 内部的时间轴没从它自己落笔那刻起算");
        assertEquals(welcome - .001, OnboardingGreeting.localElapsedAt(hello + welcome - .001, cycles),
                1e-6, "welcome 快擦完时该走到自己那一圈的最后，不是继续累加两枚字的秒数");

        for (int lap = 0; lap < 400; lap++) {
            double t = lap * pair + hello * .5;
            assertEquals(0, OnboardingGreeting.wordIndexAt(t, cycles),
                    "第 " + lap + " 轮 hello 那一格被挤走了：周期在累加误差里漂了");
            assertEquals(1, OnboardingGreeting.wordIndexAt(t + hello + .5, cycles),
                    "第 " + lap + " 轮 welcome 那一格被挤走了");
        }
    }

    @Test
    @DisplayName("开头与空白端一个像素都不画：那两颗圆头点不许露出来")
    void blankEndsDrawNothing() {
        double firstInk = .25;
        assertFalse(OnboardingGreeting.visibleFrame(0, firstInk),
                "时间轴在 0 那一帧被判定为「有东西」：素材开头 dasharray=\"0 100\" 会在两个端点上"
                        + "各画一颗圆头点，玩家看到的就是「字还没写先落两滴墨」");
        assertFalse(OnboardingGreeting.visibleFrame(firstInk - .001, firstInk),
                "第一笔落地之前那一帧还是只有两颗点");
        assertTrue(OnboardingGreeting.visibleFrame(firstInk, firstInk),
                "第一笔落地那一帧也不画：描字凭空少了开头一笔");

        // 起表那一帧、以及一枚字擦干净交棒的那一帧，都落在上面这条「不画」里。
        double[] cycles = {cycleOf(SPAN), cycleOf(SPAN)};
        assertEquals(0.0, OnboardingGreeting.timelineSeconds(
                OnboardingGreeting.localElapsedAt(0, cycles), SPAN, HOLD, TURN), 1e-9);
        assertEquals(0.0, OnboardingGreeting.timelineSeconds(
                OnboardingGreeting.localElapsedAt(cycles[0], cycles), SPAN, HOLD, TURN), 1e-9);
    }

    @Test
    @DisplayName("提示行只淡入一次：字擦回空白的那一轮，两行小字不许跟着闪")
    void hintProgressSaturatesOnTheFirstPass() {
        assertEquals(0.0, OnboardingGreeting.firstPassProgress(0, SPAN), 1e-9);
        assertEquals(.5, OnboardingGreeting.firstPassProgress(SPAN * .5, SPAN), 1e-9);
        assertEquals(1.0, OnboardingGreeting.firstPassProgress(SPAN, SPAN), 1e-9);
        // 这一秒画面上是一个字都没有（已经擦回去并交棒给下一枚），但提示行必须还满着。
        assertEquals(1.0, OnboardingGreeting.firstPassProgress(2 * SPAN + HOLD, SPAN), 1e-9);
        assertEquals(1.0, OnboardingGreeting.firstPassProgress(9999.0, SPAN), 1e-9);
        assertEquals(0.0, OnboardingGreeting.firstPassProgress(1.0, 0.0), 1e-9,
                "素材读不到（一遍长度是 0）时进度该停在 0，不是除出个 NaN");
        assertFalse(Double.isNaN(OnboardingGreeting.timelineSeconds(1.0, 0.0, HOLD, TURN)),
                "除零还是漏了出来");
    }

    @Test
    @DisplayName("真实素材上的预算：两枚字各三秒多，一轮 hello+welcome 约十七秒")
    void realAssetBudget() {
        SmilSvg hello = AnimatedIcons.hello();
        SmilSvg welcome = AnimatedIcons.welcome();
        assertNotNull(hello, "hello.svg 读不到，覆盖层只留文字不描字");
        assertNotNull(welcome, "welcome.svg 读不到，覆盖层只剩一枚字在轮");

        double helloSpan = hello.duration() * AnimatedIcons.helloEndPhase();
        double welcomeSpan = welcome.duration() * AnimatedIcons.welcomeEndPhase();
        assertTrue(helloSpan > 3.0 && helloSpan < 4.0,
                "hello 描一遍要 " + helloSpan + " 秒，和素材那一轮（" + hello.duration() + " 秒）对不上");
        assertTrue(welcomeSpan > 3.0 && welcomeSpan < 4.0,
                "welcome 描一遍要 " + welcomeSpan + " 秒：它 3.27 秒就该画满，"
                        + "再往后是作者自己塞的那一次擦除，节奏和 hello 对不上，不该用");

        double helloInk = hello.duration() * AnimatedIcons.helloFirstInkPhase();
        double welcomeInk = welcome.duration() * AnimatedIcons.welcomeFirstInkPhase();
        assertTrue(helloInk > 0 && helloInk < helloSpan,
                "hello 第一笔落在 " + helloInk + " 秒，画满却在 " + helloSpan + " 秒：这两道闸反了");
        assertTrue(welcomeInk > 0 && welcomeInk < welcomeSpan,
                "welcome 第一笔落在 " + welcomeInk + " 秒，画满却在 " + welcomeSpan + " 秒：这两道闸反了");
        // 两枚字的开头都是 0.25 秒（导出器留的那一截空白），所以描出来的速度看着是一致的。
        assertTrue(Math.abs(helloInk - welcomeInk) < .05,
                "两枚字「第一笔落地」差了 " + Math.abs(helloInk - welcomeInk)
                        + " 秒：轮着放的时候两枚字的起笔手感会不一样，得重挑相位");

        // 来回一圈走的是「有笔画那一截」（画满 − 第一笔），不是整段素材时间：
        // 开头那 0.25 秒空白已经从映射里折掉了，所以周期比按 span 算短半秒。
        double pair = cycleOf(helloSpan - helloInk) + cycleOf(welcomeSpan - welcomeInk);
        assertTrue(pair > 15.5 && pair < 19.0,
                "一轮 hello+welcome 要 " + pair + " 秒：两枚字的节奏差太多或整体太赶都得重挑参数");
    }

    @Test
    @DisplayName("起表第一帧就有笔画：素材开头那 0.25 秒空白已经折出时间轴")
    void firstFrameAlreadyHasInk() {
        final double ink = .25;                       // 第一笔落地
        final double full = 3.45;                     // 画满
        assertEquals(ink, OnboardingGreeting.assetTime(0.0, ink, full, HOLD, TURN), 1e-9,
                "落笔第 0 秒问素材要的不是「第一笔刚落地」那一帧——玩家又要看到「界面好了、字还没开始」");
        assertEquals(full, OnboardingGreeting.assetTime(full - ink, ink, full, HOLD, TURN), 1e-9,
                "走完有笔画那一截时没停在画满帧");

        // 连着放三圈：取值必须一直夹在 [ink, full] 里。低下去会露出起点那颗圆头点，
        // 高上去就走进素材自己那次擦除／回卷的那一截（第五刀为 welcome 挑 .397566 就是躲它）。
        double cycle = cycleOf(full - ink);
        for (double t = 0.0; t <= cycle * 3.0; t += .017) {
            double asset = OnboardingGreeting.assetTime(t, ink, full, HOLD, TURN);
            assertTrue(asset >= ink - 1e-9 && asset <= full + 1e-9,
                    "第 " + t + " 秒取到素材的 " + asset + " 秒，跑出「有笔画那一截」了");
        }
        // 起墨相位登记得比画满还晚（素材改坏了 / 相位手抖）时不许抖出负长度：停在画满那一帧。
        assertEquals(full, OnboardingGreeting.assetTime(1.0, full, full, HOLD, TURN), 1e-9);
        assertEquals(full, OnboardingGreeting.assetTime(1.0, full + .5, full, HOLD, TURN), 1e-9,
                "firstInk > span 时该老实停在画满帧，不该倒着减出个负的一截");
    }

    // ==================================================================
    // skip 门禁：招呼没放够这么久之前，四条输入一律不让层收掉
    // ==================================================================

    @Test
    @DisplayName("门禁按动画秒数放行：第 5 秒整开，之前的输入全不吃掉也不放行")
    void gateOpensAtFiveAnimationSeconds() {
        assertEquals(5.0, OnboardingOverlay.gateRemainingSeconds(0.0), 1e-9,
                "刚起表还差整整 5 秒");
        assertEquals(2.0, OnboardingOverlay.gateRemainingSeconds(3.0), 1e-9);
        assertTrue(OnboardingOverlay.gateRemainingSeconds(4.999) > 0,
                "4.999 秒就放行，等于口径没落实");
        assertEquals(0.0, OnboardingOverlay.gateRemainingSeconds(5.0), 1e-9,
                "第 5 秒整必须已经放开，不该再多等一帧");
        assertEquals(0.0, OnboardingOverlay.gateRemainingSeconds(9999.0), 1e-9,
                "放开之后剩余秒数必须停在 0，不许跟着时间轴往下变负");
        // 还没起表（覆盖层淡入到看得见之前时间轴压根不走）时 elapsedSeconds 是 0，
        // 负数只可能来自哪天有人改了起表逻辑；这里当 0 处理，不许比 5 秒还长。
        assertEquals(5.0, OnboardingOverlay.gateRemainingSeconds(-1.0), 1e-9);
    }

    @Test
    @DisplayName("等待那行读数只数到 0.1 秒，且换小数点不分语言")
    void gateHintCountsDownToTheFrameItOpens() {
        assertEquals("还有 5.0 秒可跳过", OnboardingOverlay.gateHintText(5.0));
        assertEquals("还有 2.4 秒可跳过", OnboardingOverlay.gateHintText(2.37),
                "向上取整到十分之一秒：2.37 该显示 2.4，不许四舍五入成 2.3 后又停在 2.4 不动");
        assertEquals("还有 0.1 秒可跳过", OnboardingOverlay.gateHintText(0.001));
        // 到点这一帧必须整句换回正常那句。「还有 0.0 秒可跳过」是一句自相矛盾的话，
        // 而它恰好会在门禁打开的那一帧出现，如果这里用四舍五入而不是向上取整。
        assertNull(OnboardingOverlay.gateHintText(0.0));
        assertNull(OnboardingOverlay.gateHintText(-1.0));

        // 逐帧走一遍：闸和读数必须由同一份判据决定，而且不许出现「0.0 秒」，
        // 也不许因为默认 Locale 把小数点打成逗号（String.format("%.1f") 在某些语言下就是逗号，
        // 而这行字是给中文玩家看的）。
        for (double e = 0.0; e < 5.0; e += .01) {
            double left = OnboardingOverlay.gateRemainingSeconds(e);
            String text = OnboardingOverlay.gateHintText(left);
            assertEquals(!OnboardingOverlay.gateWaiting(left), text == null,
                    "第 " + e + " 秒：闸还没开却没有等待读数，或者闸开了还在报还剩几秒（剩余 "
                            + left + " 秒）——这两件事必须同进同退");
            if (text == null) {
                continue;
            }
            assertTrue(text.matches("还有 \\d+\\.\\d 秒可跳过"),
                    "等待读数的形状不对：" + text);
            assertFalse(text.contains("0.0 秒"), "显示了一个「还要等 0.0 秒」：" + text);
        }
        // 帧累积出来的秒数从来不是整的：走到 5 秒那一帧常见的是 4.999999999999938。
        // 这一格正是「闸没开、读数没了」的现场，钉的就是上面那条同进同退。
        double almostFive = 4.999999999999938;
        assertEquals(OnboardingOverlay.gateWaiting(
                        OnboardingOverlay.gateRemainingSeconds(almostFive)),
                OnboardingOverlay.gateHintText(
                        OnboardingOverlay.gateRemainingSeconds(almostFive)) != null);
    }

    @Test
    @DisplayName("门禁开的那一刻，屏幕上正是写满的第一枚字；等待期间那行字已经看得见")
    void gateOpensInsideTheFirstWordSFullHold() {
        SmilSvg hello = AnimatedIcons.hello();
        assertNotNull(hello, "素材读不到时这道闸无从判断");
        // 门禁对的是「有笔画那一截」：开头那 0.25 秒空白已经折出时间轴（见 firstFrameAlreadyHasInk），
        // 所以这一枚字从落笔到擦回去一共走 revealSpan，满字那一端再停 HOLD。
        double reveal = hello.duration() * AnimatedIcons.helloEndPhase()
                - hello.duration() * AnimatedIcons.helloFirstInkPhase();
        double gate = OnboardingOverlay.SKIP_GATE_SECONDS;

        // 太早：字还没描完就让人跳，等于没有招呼。
        assertTrue(gate >= reveal,
                "门禁 " + gate + " 秒早于 hello 描完的 " + reveal + " 秒：放开时屏幕上是一笔没画完的字");
        // 太晚：已经越过「停在满字」那 2 秒、开始往回擦了，玩家会以为这层在无限循环、跳不掉。
        assertTrue(gate <= reveal + OnboardingGreeting.HOLD_SECONDS,
                "门禁 " + gate + " 秒晚于 hello 停满字的 " + (reveal + OnboardingGreeting.HOLD_SECONDS)
                        + " 秒：放开那一刻字已经开始被擦掉");
        // 等待读数得看得见：提示行淡入得比门禁早，否则玩家面对的是「点了没反应」四个字都没有。
        assertTrue(OnboardingOverlay.HINT_FROM * reveal < gate,
                "提示行到第 " + (OnboardingOverlay.HINT_FROM * reveal) + " 秒才淡入，比门禁放开还晚，"
                        + "整个等待期屏幕上一行字都没有");
    }
}
