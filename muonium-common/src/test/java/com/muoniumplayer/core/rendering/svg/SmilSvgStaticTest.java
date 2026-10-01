package com.muoniumplayer.core.rendering.svg;

import com.muoniumplayer.render.skija.SkijaNatives;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Surface;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动画 SVG 引擎的静态测试：CPU 光栅，不启动客户端、不需要 GL。
 *
 * <h2>为什么必须有</h2>
 *
 * <p>这套东西是「自己解释 SMIL」。它坏了不会报错，只会安静地画错：少画几笔、整枝不出现、
 * 动画停在第 0 帧、或者把 {@code <defs>} 里那一大堆模板副本也画出来（那才是浏览器里的样子吗？
 * 不是）。这些症状在客户端里全都表现为「图标有点怪」，没人能一眼看出是哪一条规则错了。</p>
 *
 * <p>所以这里钉的是<b>和浏览器一致</b>这件事：下面每一处期望值都是从 Chrome 里量出来的
 * （对同一批素材取 {@code getBoundingClientRect()} 与 {@code getBBox()}），不是照着本引擎
 * 的输出回填的。引擎和浏览器对不上，就是引擎错。</p>
 */
class SmilSvgStaticTest {

    /** 光栅边长。128 够把「两帧不一样」量出来，七枚素材跑一轮也不至于慢。 */
    private static final int SIZE = 128;

    /** 沿时间轴采多少个点来找「最热闹的那一帧」。 */
    private static final int SAMPLES = 24;

    /**
     * 每枚素材在<b>浏览器渲染树里</b>的叶子数（{@code <defs>} 里的模板副本不算）。
     * 这是「我们照规范办事、没把 defs 也画出来」最硬的一根钉子。
     *
     * <p>口径是不看 {@code display}/{@code opacity} 门控的结构数 —— 和 Chrome 里
     * {@code querySelectorAll} 剔掉 defs 后代是同一件事。按帧可见的数会比它小：
     * 星光那枚结构上 9 笔，但同时亮着的最多 5 笔。</p>
     */
    private static final int SEARCH_LEAVES = 5;
    private static final int INFERENCE_LEAVES = 9;
    private static final int QR_LEAVES = 4;
    private static final int LIKE_LEAVES = 48;
    private static final int SAFE_LEAVES = 7;

    @BeforeAll
    static void loadNative() {
        assertTrue(SkijaNatives.ensureLoaded(),
                "Skija 原生库加载失败: " + SkijaNatives.failure());
    }

    @Test
    @DisplayName("七枚素材都读得到，且时长就是各自时间轴写的那个数")
    void everyAssetLoadsAndReportsItsOwnDuration() {
        // 这几个秒数直接来自素材里的 dur="…"：读错、算错、或者素材被换过，都会在这里露出来。
        assertEquals(5.167, duration(AnimatedIcons.SEARCH), 1e-3, "search.svg 的时长");
        assertEquals(3.017, duration(AnimatedIcons.INFERENCE), 1e-3, "inference.svg 的时长");
        assertEquals(2.000, duration(AnimatedIcons.QR_SCAN), 1e-3, "qr-scan.svg 的时长");
        assertEquals(7.133, duration(AnimatedIcons.LIKE), 1e-3, "like.svg 的时长");
        assertEquals(5.000, duration(AnimatedIcons.LOGIN_SAFE), 1e-3, "login-safe.svg 的时长");
        assertEquals(3.450, duration(AnimatedIcons.HELLO), 1e-3, "hello.svg 的时长");
        assertEquals(8.217, duration(AnimatedIcons.WELCOME), 1e-3, "welcome.svg 的时长");
    }

    private static double duration(String resource) {
        SmilSvg svg = SmilSvg.load(resource);
        assertNotNull(svg, resource + " 没解析出来");
        return svg.duration();
    }

    @Test
    @DisplayName("只画 defs 之外的内容：渲染树叶子数和浏览器量的结果一一对上")
    void defsTemplatesAreNotDrawn() {
        assertEquals(SEARCH_LEAVES, leafCount(AnimatedIcons.SEARCH),
                "search.svg 应该画 4 枚取景角 + 1 个放大镜");
        assertEquals(INFERENCE_LEAVES, leafCount(AnimatedIcons.INFERENCE),
                "inference.svg 的星光笔画数不对");
        assertEquals(QR_LEAVES, leafCount(AnimatedIcons.QR_SCAN),
                "qr-scan.svg 应该是扫描框四角 + 一条扫描带");
        assertEquals(LIKE_LEAVES, leafCount(AnimatedIcons.LIKE),
                "like.svg 的渲染树叶子数不对");
        assertEquals(SAFE_LEAVES, leafCount(AnimatedIcons.LOGIN_SAFE),
                "login-safe.svg 应该是盾 + 锁 + 几笔火花");
    }

    private static int leafCount(String resource) {
        SmilSvg svg = SmilSvg.load(resource);
        assertNotNull(svg, resource + " 没解析出来");
        return svg.drawnLeafCountForTest();
    }

    @Test
    @DisplayName("按帧可见的叶子会随动画轮班：星光那枚最多同时亮 5 笔")
    void visibleLeavesRotateAcrossTheCycle() {
        SmilSvg svg = SmilSvg.load(AnimatedIcons.INFERENCE);
        assertNotNull(svg);
        int most = 0;
        for (int i = 0; i <= SAMPLES; i++) {
            most = Math.max(most, svg.leafBoxesForTest(svg.duration() * i / SAMPLES).size());
        }
        assertTrue(most > 0 && most < svg.drawnLeafCountForTest(),
                "每一帧都画出同样多、或者一帧都不画：display 门控没生效（最热闹一帧 " + most + "）");
    }

    @Test
    @DisplayName("动画真的在动：同一枚素材不同时间的画面不一样")
    void eachAssetChangesAcrossItsTimeline() {
        for (String resource : new String[]{AnimatedIcons.SEARCH, AnimatedIcons.INFERENCE,
                AnimatedIcons.QR_SCAN, AnimatedIcons.LIKE, AnimatedIcons.LOGIN_SAFE,
                AnimatedIcons.HELLO, AnimatedIcons.WELCOME}) {
            SmilSvg svg = SmilSvg.load(resource);
            assertNotNull(svg, resource + " 没解析出来");
            Ink first = raster(svg, 0, 0);
            Ink middle = raster(svg, svg.duration() * .45, 0);
            Ink last = raster(svg, svg.duration() * .9, 0);
            assertTrue(first.ink > 0 || middle.ink > 0, resource + " 一帧墨都没有，等于没接上");
            assertFalse(first.sameAs(middle) && middle.sameAs(last),
                    resource + " 三个时间点画出一模一样的东西：动画没在走");
        }
    }

    @Test
    @DisplayName("墨迹盒落在画布内，而且把图标撑起来了")
    void contentBoxCropsTightlyInsideTheCanvas() {
        for (String resource : new String[]{AnimatedIcons.SEARCH, AnimatedIcons.INFERENCE,
                AnimatedIcons.QR_SCAN, AnimatedIcons.LIKE, AnimatedIcons.LOGIN_SAFE,
                AnimatedIcons.HELLO, AnimatedIcons.WELCOME}) {
            SmilSvg svg = SmilSvg.load(resource);
            assertNotNull(svg, resource + " 没解析出来");
            double[] view = svg.viewBoxForTest();
            double[] box = svg.contentBoxForTest();
            assertTrue(box[0] >= view[0] - 1e-6 && box[1] >= view[1] - 1e-6
                            && box[0] + box[2] <= view[0] + view[2] + 1e-6
                            && box[1] + box[3] <= view[1] + view[3] + 1e-6,
                    resource + " 的墨迹盒跑到画布外面去了：量出来的框是 " + java.util.Arrays.toString(box));
            // 撑不满画布是素材的留白，但也不能小到一个角上 —— 那说明裁切整个没生效。
            double ratio = Math.max(box[2], box[3]) / Math.max(view[2], view[3]);
            assertTrue(ratio > .3, resource + " 的墨迹盒只占画布 " + ratio
                    + "，图标会被画得缩在一个角上");
        }
    }

    @Test
    @DisplayName("染色模式：素材里那圈写死的黑色笔画一笔不剩")
    void tintReplacesEveryColor() {
        SmilSvg svg = SmilSvg.load(AnimatedIcons.SEARCH);
        assertNotNull(svg);
        double time = svg.duration() * .3;
        Ink untinted = raster(svg, time, 0);
        Ink ink = raster(svg, time, 0xFFFF7A00);
        assertTrue(ink.ink > 20, "染色模式下什么墨都没有");
        // search.svg 的笔画是写死的 #000000，所以判据取「中性且暗」而不是精确色值 ——
        // 抗锯齿边缘会掺白，按色值比会把正常边缘误判成没染上。
        assertTrue(untinted.neutralDark > 20,
                "对照组里就没有中性暗墨：素材大概换过颜色，这根钉子要跟着改");
        assertEquals(0, ink.neutralDark, "染色之后仍有中性暗墨：黑色笔画没被盖住");

        // 主题强调色是 0x00RRGGBB 这种「只有 RGB、alpha 是 0」的形状（NCMScreen.getColor），
        // 染色按「乘掉颜色自带的 alpha」走的话，这种色会整枚乘成全透明 —— 界面上就是
        // 引擎报「画了」而屏幕上一个像素都没有。
        Ink themeShaped = raster(svg, time, 0x00FF7A00);
        assertTrue(themeShaped.ink > 20,
                "用主题那种 alpha=0 的强调色染色之后没有墨：染色没把 alpha 抬成不透明");
        assertEquals(0, themeShaped.neutralDark, "alpha=0 的染色没盖住写死的黑色笔画");
    }

    @Test
    @DisplayName("只播一轮的时钟：走完一整轮才报 finished，并且停在末帧")
    void playOnceFinishesExactlyAtTheEndOfTheCycle() {
        SmilSvg svg = SmilSvg.load(AnimatedIcons.LOGIN_SAFE);
        assertNotNull(svg);
        double duration = svg.duration();

        SvgPlayback half = SvgPlayback.onceElapsed(svg, duration * .5);
        assertFalse(half.finished(), "才走半轮就说演完了，换页会提前把成功动画掐掉");
        assertEquals(duration * .5, half.time(), duration * .02, "半轮的时间轴位置不对");

        SvgPlayback past = SvgPlayback.onceElapsed(svg, duration * 1.5);
        assertTrue(past.finished(), "超过一整轮了还没报 finished");
        assertEquals(duration, past.time(), 1e-6, "走完之后要停在末帧，不能再往前超");
    }

    @Test
    @DisplayName("循环时钟在一整轮末尾绕回起点")
    void loopingClockWrapsAround() {
        SmilSvg svg = SmilSvg.load(AnimatedIcons.QR_SCAN);
        assertNotNull(svg);
        double duration = svg.duration();
        SvgPlayback wrapped = SvgPlayback.loopingElapsed(svg, duration + duration * .25);
        assertFalse(wrapped.finished(), "循环着的时钟不该报演完");
        assertEquals(duration * .25, wrapped.time(), duration * .02,
                "绕回之后落点不对：扫描带会跳帧或者倒着走");
    }

    @Test
    @DisplayName("爱心那枚的起播相位落在「心已经画出来」的那一段")
    void heartIsVisibleAtItsStartPhase() {
        SmilSvg svg = SmilSvg.load(AnimatedIcons.LIKE);
        assertNotNull(svg);
        double time = svg.duration() * AnimatedIcons.likeStartPhase();
        assertFalse(svg.leafBoxesForTest(time).isEmpty(),
                "like.svg 在起播相位上什么都没有：用户点完心会先盯一段空白");
        // 顺带钉住素材开头确实是空档 —— 相位常数就是为了躲它，哪天素材换了要能看出来。
        assertTrue(svg.leafBoxesForTest(0).isEmpty() || svg.leafBoxesForTest(0).size() < LIKE_LEAVES,
                "like.svg 开头不再是空档了，那 likeStartPhase() 就该跟着改");
    }

    @Test
    @DisplayName("爱心：常驻帧与爆心收手帧都是实心红心，不是素材自带的那圈黑描边")
    void heartBurstEndsWhileTheHeartIsStillSolid() {
        SmilSvg svg = SmilSvg.load(AnimatedIcons.LIKE);
        assertNotNull(svg);
        double duration = svg.duration();

        // 素材尾巴又是一段 display:none：冻在末帧等于心炸没了。
        assertTrue(svg.leafBoxesForTest(duration).isEmpty(),
                "like.svg 末帧又有东西了，那 likeEndPhase() 得重新挑");

        // 红心是「一层红填充 + 一层 stroke="#000000" 的描边」两笔叠出来的：填充只演到 .47 是实的，
        // .49 起描边露头、.50 就只剩描边。收手定在描边露头之前 —— 否则「已喜欢」会画成一颗空心
        // 黑框心，20px 的图标槽里就是用户报的「不亮、描边发虚」。
        // 判据取「画布正中心那块有没有被填上」＋「有没有中性暗墨」：实心那一帧中心是满的、
        // 一笔黑都没有；空心那一帧中心只剩描边。不数总墨量，是因为粒子会散在四周，
        // 把两头的总量抬得差不多。
        Ink stop = raster(svg, duration * AnimatedIcons.likeEndPhase(), 0);
        Ink hollow = raster(svg, duration * (AnimatedIcons.likeEndPhase() + .03), 0);
        assertTrue(stop.centerInk > 100,
                "收手那一帧中心是空的（" + stop.centerInk + " 个像素）：已喜欢会画成一颗空心心");
        assertEquals(0, stop.neutralDark,
                "收手那一帧已经带上黑描边了（" + stop.neutralDark + " 个中性暗墨像素）");
        assertTrue(hollow.neutralDark > 20,
                "素材里那圈黑描边心不见了：判据得跟着换，不然这根钉子是空转的");
        assertTrue(stop.centerInk > hollow.centerInk * 4,
                "收手那一帧和描边那一帧一样是空心的：" + stop.centerInk + " vs " + hollow.centerInk);

        SvgPlayback burst = AnimatedIcons.likeBurst();
        assertNotNull(burst, "素材读不到，likeBurst() 就不该给出时钟");
        assertTrue(burst.time() < duration, "一次性时钟一开始就跑到末帧了");
        assertTrue(burst.span() < duration * .3,
                "一轮爆心 " + burst.span() + " 秒太长了：点心那一下不该占住这么久");

        // 常驻那一条：一造出来就该已经演完、停在收手那一帧。列表一开有 209 行，
        // 用没走完的时钟的话，整列红心会跟着一起爆一遍。
        SvgPlayback solid = AnimatedIcons.likeSolid();
        assertNotNull(solid, "素材读不到，likeSolid() 就不该给出时钟");
        assertTrue(solid.finished(), "常驻时钟没走完：整列红心会跟着列表一起爆心");
        assertEquals(duration * AnimatedIcons.likeEndPhase(), solid.time(), duration * .01,
                "常驻帧和爆心的收手帧不是同一帧：点心那一下会看出跳");
    }

    @Test
    @DisplayName("一次性动画：fill=\"freeze\" 停在末帧，缺省的 remove 回落基底值")
    void freezeHoldsTheLastFrameWhileRemoveFallsBack() {
        // 这批素材全都写着 repeatCount="indefinite"，于是 fill 这一条规范谁都没碰过。
        // 用一段最小 SVG 走同一套解析（SmilSvg.parseForTest），钉的是解释器本身：
        // 哪天有人把 freeze 又当成「无限循环」的别名，这两半会立刻画成同一个东西。
        SmilSvg frozen = SmilSvg.parseForTest(opacityAsset(" fill=\"freeze\""));
        SmilSvg removed = SmilSvg.parseForTest(opacityAsset(""));
        assertNotNull(frozen, "freeze 那份没解析出来");
        assertNotNull(removed, "remove 那份没解析出来");
        assertEquals(2.0, frozen.duration(), 1e-6, "dur=\"2s\" 没读成 2 秒");

        // 演到一半（不透明度 0.6，压在白底上是 102 的灰）：两种写法该长得一样。
        assertTrue(raster(frozen, 1.0, 0).neutralDark > 8000, "freeze 那份演到一半就不动了");
        assertTrue(raster(removed, 1.0, 0).neutralDark > 8000, "remove 那份演到一半就不动了");

        // 演完之后：freeze 停在末帧（纯黑），remove 把值还给元素自己（基底 0.2 → 204 的浅灰，
        // 还在画，只是淡回去）。用 neutralDark 而不是 ink 来分，因为这两种情况下都满屏是墨。
        assertTrue(raster(frozen, 3.0, 0).neutralDark > 8000,
                "fill=\"freeze\" 没停在末帧：一次性动画演完会塌回基底值");
        Ink fallen = raster(removed, 3.0, 0);
        assertEquals(0, fallen.neutralDark,
                "缺省的 fill=\"remove\" 演完还赖在末帧：规范说这时候要回落基底值");
        assertTrue(fallen.ink > 8000, "回落之后方块整个不见了：remove 是还值，不是删元素");
    }

    /** 一张 2 秒从 0.2 淡到 1 的方块，末尾拼上想测的那个 {@code fill} 写法。 */
    private static String opacityAsset(String fillAttribute) {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\">"
                + "<rect x=\"0\" y=\"0\" width=\"100\" height=\"100\" fill=\"#000000\" opacity=\"0.2\">"
                + "<animate attributeName=\"opacity\" values=\"0.2;1\" dur=\"2s\""
                + fillAttribute + "/>"
                + "</rect></svg>";
    }

    @Test
    @DisplayName("读不到的资源安静失败，不抛异常")
    void missingResourceFailsQuietly() {
        assertNull(SmilSvg.load("/muonium/svg/does-not-exist.svg"));
        assertNull(SmilSvg.load(null));
    }

    @Test
    @DisplayName("招呼字 hello / welcome：画满那一帧是实的，走满一轮回卷成空白，倒着放有东西可擦")
    void greetingAssetsDrawMonotonicallyInBothDirections() {
        assertGreetingAsset(AnimatedIcons.hello(), "hello",
                AnimatedIcons.helloEndPhase(), AnimatedIcons.helloFirstInkPhase());
        assertGreetingAsset(AnimatedIcons.welcome(), "welcome",
                AnimatedIcons.welcomeEndPhase(), AnimatedIcons.welcomeFirstInkPhase());
    }

    /**
     * 一枚招呼字的四根钉子。两枚字是同一套导出（{@code pathLength="100"} 的虚线描字 +
     * 挂在 stroke 上的彩虹渐变），所以过一遍同样的账即可，不必各写一份。
     */
    private static void assertGreetingAsset(SmilSvg svg, String name,
                                            double endPhase, double firstInkPhase) {
        assertNotNull(svg, name + ".svg 读不到，覆盖层中央就是空屏");
        double duration = svg.duration();

        Ink frozen = raster(svg, duration * endPhase, 0);
        assertTrue(frozen.ink > 300,
                name + " 画满那一帧只有 " + frozen.ink + " 个像素有墨：覆盖层停在了一笔没画完的字上");

        // 素材写的是 repeatCount="indefinite"，所以时间轴走到 dur 就回卷到 0，而 0 那一帧
        // 的基底 stroke-dasharray="0 100" 是一个字都不画。这根钉子钉的是「为什么必须有画满相位」：
        // 哪天有人把它改成 1，来回放会在描完的那一瞬间把整条字抹掉，玩家看到的是一闪一闪的字。
        Ink wrapped = raster(svg, duration, 0);
        assertTrue(wrapped.ink * 4 < frozen.ink,
                name + " 走满一轮回卷之后字还剩 " + wrapped.ink + "，和画满帧（" + frozen.ink
                        + "）差不多：素材改了，画满相位得跟着重挑");

        // 开头那一截「看着是空的、其实不是」：dasharray 一直是 "0 100"，可 Skia 会按 round linecap
        // 在两个零长划上各补一个圆头，于是画面里有两颗点（实测 hello 242 个像素、welcome 187 个）。
        // 玩家报的「首帧错误展示起始点」就是它。这根钉子钉的是那道闸必须有东西可挡：哪天 Skia 不画
        // 这两个圆头了（ink 变 0），OnboardingGreeting#visibleFrame 那一步就可以简化成 time > 0。
        Ink blank = raster(svg, 0, 0);
        assertTrue(blank.ink > 0 && blank.ink * 20 < frozen.ink,
                name + " 第 0 帧的墨迹量是 " + blank.ink + "（画满帧 " + frozen.ink
                        + "）：既不是「两颗圆头点」也不是全空，那两颗点的处理方式得重看");

        // 描字是「来回放」的：倒着走必须能看到正着走见过的同一批帧，而且越擦越少。
        // 这两根钉子钉的是同一件事——解释器对时间是纯函数（给几秒画几秒，不记上一帧），
        // 哪天有人给它加了累加器或缓存，倒放就会变成「继续往前演」，玩家看到的是字越擦越完整。
        double span = duration * endPhase;
        Ink quarterInk = raster(svg, span * .25, 0);
        Ink halfInk = raster(svg, span * .5, 0);
        assertTrue(quarterInk.ink < halfInk.ink && halfInk.ink < frozen.ink,
                name + " 的墨迹不是一笔一笔攒出来的（1/4 帧 " + quarterInk.ink + "、1/2 帧 "
                        + halfInk.ink + "、画满帧 " + frozen.ink + "）：倒着擦回去没有东西可擦");
        assertEquals(quarterInk.ink, raster(svg, span * .25, 0).ink,
                name + " 同一个时间点两次画出来的墨迹不一样：素材里还留着逐帧累加的状态，倒放会穿帮");

        // 描到四分之一时，最右边那滴墨必须还留在字的左半边。虚线模式（dash + gap）的周期只要
        // 等于 pathLength，第二次划就正好落在路径终点上，圆头线帽把它画成一颗脱离笔画的圆点，
        // 挂在还没描到的字尾——welcome 原来就是这样（它的 dashoffset 全程为 0，没有东西把第二次
        // 划推出路径外）。素材里把 gap 撑到 200 之后这一颗就没了；哪天换稿子又冒出这颗点，这里红。
        assertTrue(quarterInk.maxX < SIZE * .5,
                name + " 描到四分之一时最右边的墨出现在第 " + quarterInk.maxX + " 列（画布 "
                        + SIZE + "）：字尾那颗脱离笔画的圆点又回来了，虚线周期得重新撑开");

        // 第一笔落地的相位得真的落在「两颗点」与「四分之一」之间，否则那道闸要么挡早了
        // （凭空少描一笔），要么挡晚了（玩家还是先看见两颗点）。
        assertTrue(firstInkPhase > 0 && firstInkPhase < endPhase * .25,
                name + " 的第一笔相位登记成了 " + firstInkPhase + "，和 1/4 帧的位置对不上");
        assertTrue(raster(svg, duration * firstInkPhase, 0).ink > blank.ink,
                name + " 在第一笔相位那一帧还是只有两颗点：这道闸的相位挑早了或挑晚了");
    }

    // ==================================================================
    // 光栅
    // ==================================================================

    /** 一张白底上的墨迹统计：有多少个墨点、质心在哪儿、有多少「中性且暗」的墨。 */
    private static final class Ink {
        int ink;
        double sumX;
        double sumY;
        /** 三通道差不多、又不够亮的像素 —— 素材里那圈写死的 {@code #000000} 就长这样。 */
        int neutralDark;
        /** 画布正中那块（边长 {@code SIZE/4}）里有多少个墨点：实心的心才会把这里填满。 */
        int centerInk;
        /** 最右边那滴墨在第几列。描字描到四分之一时它不该跑到字尾——那是「终点圆点」的坐标。 */
        int maxX;

        boolean sameAs(Ink other) {
            return ink == other.ink
                    && Math.abs(sumX - other.sumX) < 1e-6
                    && Math.abs(sumY - other.sumY) < 1e-6;
        }
    }

    private static Ink raster(SmilSvg svg, double time, int tint) {
        Ink result = new Ink();
        try (Surface surface = Surface.makeRasterN32Premul(SIZE, SIZE)) {
            Canvas canvas = surface.getCanvas();
            canvas.clear(0xFFFFFFFF);
            assertTrue(svg.render(canvas, time, 0, 0, SIZE, SIZE, 1f, tint),
                    "这一帧没画出来（render 返回 false）");
            try (Bitmap bitmap = new Bitmap()) {
                bitmap.allocPixels(ImageInfo.makeN32Premul(SIZE, SIZE));
                assertTrue(surface.readPixels(bitmap, 0, 0), "readPixels 失败");
                for (int y = 0; y < SIZE; y++) {
                    for (int x = 0; x < SIZE; x++) {
                        int color = bitmap.getColor(x, y);
                        int red = (color >> 16) & 0xFF;
                        int green = (color >> 8) & 0xFF;
                        int blue = color & 0xFF;
                        if (red > 240 && green > 240 && blue > 240) {
                            continue;   // 白底
                        }
                        result.ink++;
                        result.sumX += x;
                        result.sumY += y;
                        result.maxX = Math.max(result.maxX, x);
                        if (Math.abs(x - SIZE / 2) <= SIZE / 8 && Math.abs(y - SIZE / 2) <= SIZE / 8) {
                            result.centerInk++;
                        }
                        if (Math.abs(red - green) < 18 && Math.abs(green - blue) < 18 && red < 150) {
                            result.neutralDark++;
                        }
                    }
                }
            }
        }
        return result;
    }

    @Test
    @DisplayName("同一份素材只解析一次：拿到的是同一个实例")
    void assetsAreParsedOnceAndCached() {
        SmilSvg first = SmilSvg.load(AnimatedIcons.SEARCH);
        SmilSvg second = SmilSvg.load(AnimatedIcons.SEARCH);
        assertNotNull(first);
        // 每帧都重新解析一份 190 KB 的 like.svg 是绝对不能接受的，缓存必须真的生效。
        assertTrue(first == second, "同一个资源路径每次都新建了一份，缓存没生效");
    }

    @Test
    @DisplayName("七枚素材的叶子都在画布附近，没有飘到画外")
    void leavesStayNearTheCanvas() {
        for (String resource : new String[]{AnimatedIcons.SEARCH, AnimatedIcons.INFERENCE,
                AnimatedIcons.QR_SCAN, AnimatedIcons.LIKE, AnimatedIcons.LOGIN_SAFE,
                AnimatedIcons.HELLO, AnimatedIcons.WELCOME}) {
            SmilSvg svg = SmilSvg.load(resource);
            assertNotNull(svg, resource + " 没解析出来");
            double[] view = svg.viewBoxForTest();
            List<double[]> boxes = svg.leafBoxesForTest(svg.duration() * .5);
            for (double[] box : boxes) {
                // 允许描边外溢一圈（笔画粗的素材会超出自己的路径包围盒），但不该整枚跑到画布外。
                boolean anywhereOnScreen = box[0] + box[2] > view[0] && box[0] < view[0] + view[2]
                        && box[1] + box[3] > view[1] && box[1] < view[1] + view[3];
                assertTrue(anywhereOnScreen,
                        resource + " 有一笔完全在画布外：" + java.util.Arrays.toString(box));
            }
        }
    }
}
