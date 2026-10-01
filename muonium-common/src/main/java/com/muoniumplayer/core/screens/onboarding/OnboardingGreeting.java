package com.muoniumplayer.core.screens.onboarding;

import com.muoniumplayer.core.rendering.svg.AnimatedIcons;
import com.muoniumplayer.core.rendering.svg.SmilSvg;
import com.muoniumplayer.render.skija.SkijaEffects;
import com.muoniumplayer.render.skija.SkijaGfx;

/**
 * 覆盖层中央那两枚来回描出来又擦掉的招呼字（挂在 {@link OnboardingOverlay} 上）：
 * 先「hello」再「welcome」，一枚擦干净了下一枚落笔，像 Apple 那套手写招呼一样轮着来。
 *
 * <h2>真源是素材，不是代码里的一份路径</h2>
 *
 * <p>{@code muonium/svg/hello.svg} 与 {@code welcome.svg}（登记在 {@link AnimatedIcons}），
 * 由本 Mod 自己的 SMIL 解释器 {@link SmilSvg} 实时渲染，不在 Java 里另抄一份路径与色标。
 * 理由有两条：这两份稿子的动画全建立在 {@code pathLength="100"} 的 {@code stroke-dasharray} 上
 * （hello 一轮 3.45 秒、welcome 一轮 8.217 秒、七段 keySplines 缓动、笔画粗细 9→10→9 呼吸），
 * 手抄一遍等于把作者的时间轴翻译成另一种语言，改稿的人改不动；二是彩虹挂在
 * {@code stroke="url(#i0)"} 的十几段 stop 上，抄进代码就只能靠眼睛对色。</p>
 *
 * <p>为了让它们真的能播，{@link SmilSvg} 补了几处口子：认 {@code pathLength}（不认的话
 * {@code 0.877/99.123} 会被当成用户单位，画出来是锯齿不是笔画）、允许渐变挂在<b>描边</b>上
 * （原来只有填充支持，{@code stroke="url(#…)"} 的素材整条笔画都不出现）。稿子里那些
 * {@code <animate attributeName="d">} 与渐变坐标（{@code x1/y1/x2/y2}）仍然不播——
 * 解释器不认的属性一律整条丢掉，而不是猜成别的属性。</p>
 *
 * <h2>两枚字各说各的「画满在哪儿、第一笔从哪儿起」</h2>
 *
 * <p>welcome 那一轮 8.217 秒里，作者自己塞了一次擦除（3.27 秒画满、4.87 秒起虚线缩回 0），
 * 节奏和 hello 对不上。所以这里<b>只取每枚字「画满之前」那一段</b>
 * （{@link AnimatedIcons#welcomeEndPhase()}），擦与停统一交给下面这条来回放，两枚字的呼吸才是
 * 同一个节拍。实测墨迹量：hello 从 110 个像素（第 0 帧只剩起点那颗点，见 {@link #visibleFrame}）
 * 一路攒到 28441，welcome 从 122 攒到 20189，中间单调不减——这根曲线钉在 {@code SmilSvgStaticTest}。</p>
 *
 * <h2>为什么这里不用 {@code SvgPlayback}，而是自己按帧累加</h2>
 *
 * <p>{@link com.muoniumplayer.core.rendering.svg.SvgPlayback} 是<b>墙上时钟</b>：它把
 * {@code System.nanoTime()} 的差直接当成动画走过的秒数。这套语义对「爱心爆一下」「登录转圈」
 * 那类图标是对的，用在这一枚铺满屏幕中央的招呼字上就把玩家骗了——打开引导那几帧正是全场最堵的
 * 时候：{@link OnboardingBackdrop} 要在渲染线程上读回整张截图（实测最大一档回读 544 ms、写盘
 * 334 ms），字体图集与着色器也在这段时间预热。墙钟照跳，画面却没跟上，于是玩家看到的第一帧
 * 就是「字已经描了一半」。本 Mod 其余的动画都走 {@code Interpolations}——它按帧步进，而且
 * {@code calcFrameDelta()} 遇到超过 200 ms 的大帧会把步长压成 0，也就是<b>原地停住而不是跨过去</b>。
 * 这里沿用同一套语义（自己累加帧间隔并夹在 {@link #MAX_FRAME_SECONDS} 以内），于是描字速度和
 * 屏幕上真的画出来的帧数绑死：卡一下就是慢一点，不会是「跳过一半」。</p>
 *
 * <p>另一道闸是 {@link #START_PRESENCE}：覆盖层还淡入到看不见的时候<b>不起表</b>。
 * 「已经放了一半但动画还没展示出来」这句话的两半，一半靠按帧步进解决，另一半靠这道闸解决。</p>
 */
public final class OnboardingGreeting implements AutoCloseable {

    private static final String TAG = "[MuoniumPlayer/Greeting] ";

    /** 调试：{@code -Dmuonium.debug.greeting=true} 时打印起表时机与被钳掉的大帧。 */
    private static final boolean DEBUG = Boolean.getBoolean("muonium.debug.greeting");

    /**
     * 一枚都没画出来就兜底收层的时限。
     *
     * <p>覆盖层现在只由玩家输入收（见 {@link OnboardingOverlay#dismiss()}），所以「画不出来」
     * 必须有出口：素材缺失、Skija 那一路整个没起来、或者界面根本没在渲染。15 秒是给首帧之前
     * 那一段（切屏、单人存档、抓帧回读）留的余量，正常路径走不到这里。</p>
     */
    private static final long STRANDED_NANOS = 15_000_000_000L;

    /** 泛光那一遍的不透明度。参考图的光晕是氛围，不是第二层笔画，压到三成以下才不会糊成一团。 */
    private static final float GLOW_ALPHA = .34f;

    /**
     * 一枚字描满之后停多久（秒）。
     *
     * <p>玩家定的口径：「倒着来回重复放，每次停顿 2 秒」。停的是<b>满字</b>那一帧——那是这枚字
     * 最好看的时候，也是唯一值得盯着看的两秒。</p>
     */
    static final double HOLD_SECONDS = 2.0;

    /**
     * 一枚字擦回空白之后停多久（秒）。写 0 就是擦干净立刻落下一枚字的笔。
     *
     * <p>只有 hello 一枚字的时候这里也留了整整 2 秒（那时玩家要的就是「两头各停 2 秒」）。
     * 现在两枚字轮着来，中间那两秒屏幕上<b>一个像素都没有</b>（{@link #visibleFrame} 把素材开头
     * 那两颗圆头点挡掉了），看着不像呼吸、像卡住。所以空白端收成 0，停顿全留给满字那一端。
     * 想换回「写完一个、歇两秒、再写下一个」的节拍，只改这一个数，其余一行都不用动。</p>
     */
    static final double TURN_SECONDS = 0.0;

    /**
     * 一帧最多推进这么多秒。
     *
     * <p>和 {@code CoverHeroTransition#step()} 同一个取值：25fps 以下宁可让整段描字变慢，
     * 也不要一帧跨掉小半条曲线。首屏那几个 200 ms 级的卡顿帧正是被这一条吃掉的——
     * 按墙上时钟它们是「动画白走了五分之一」，按这里它们是「慢了一帧」。</p>
     */
    static final double MAX_FRAME_SECONDS = .040;

    /**
     * 起表门槛：覆盖层自己淡入到看得见（presence 到这儿）才让时间轴开始走。
     *
     * <p>没到门槛的帧一个字都不画，于是玩家看到的第一个像素就是第一笔，而不是「已经描到中间」。</p>
     */
    static final float START_PRESENCE = .5f;

    /** 一枚招呼字：素材、画满那一帧的秒数、第一笔落地的秒数、真正有笔画的那一截、来回一圈的秒数。 */
    private static final class Word {
        final SmilSvg svg;
        final double span;
        final double firstInk;
        /**
         * 有笔画的那一截 = 画满时刻 − 第一笔时刻。
         *
         * <p>导出器在每一轮开头留了 0.25 秒「什么都不画」的空白（{@code dasharray} 一直是
         * {@code "0 200"}），来回放如果按整条 {@code [0, span]} 走，这 0.25 秒就被认真地等了一遍——
         * 而首屏那几帧动辄几百毫秒、又被 {@link #MAX_FRAME_SECONDS} 钳成 40 ms 一格，
         * 玩家看到的正是「界面都糊好了，字却过一两秒才落第一笔」。所以时间轴改成<b>只在有笔画的
         * 那一截上来回</b>，开头那截空白直接从映射里折掉，见 {@link #assetTime}。</p>
         */
        final double revealSpan;
        final double cycle;

        Word(SmilSvg svg, double endPhase, double firstInkPhase) {
            this.svg = svg;
            double duration = svg.duration();
            this.span = duration * endPhase;
            this.firstInk = Math.min(span, duration * firstInkPhase);
            this.revealSpan = Math.max(.001, span - firstInk);
            this.cycle = 2.0 * revealSpan + HOLD_SECONDS + TURN_SECONDS;
        }
    }

    /** 轮着放的那几枚字。哪一枚素材没读到就少一枚，两枚都没有时覆盖层自己收（{@link #stranded()}）。 */
    private final Word[] words;
    private final double[] cycles;
    private final long createdAt = System.nanoTime();

    /** 时间轴起点：第一个真的画出去、而且覆盖层已经看得见的帧。 */
    private boolean anchored;
    /** 从锚点起按帧累加出来的时间轴秒数（不是墙钟，见类注释）。 */
    private double elapsed;
    private long lastFrameNanos;
    /** 锚点之前白等的帧数与最长的一次被钳掉的帧间隔，只为调试读数。 */
    private int framesBeforeAnchor;
    private double worstClampedNanos;

    public OnboardingGreeting() {
        Word hello = word(AnimatedIcons.hello(), AnimatedIcons.helloEndPhase(),
                AnimatedIcons.helloFirstInkPhase());
        Word welcome = word(AnimatedIcons.welcome(), AnimatedIcons.welcomeEndPhase(),
                AnimatedIcons.welcomeFirstInkPhase());
        if (hello != null && welcome != null) {
            this.words = new Word[]{hello, welcome};
        } else if (hello != null) {
            this.words = new Word[]{hello};
        } else if (welcome != null) {
            this.words = new Word[]{welcome};
        } else {
            // 两枚素材都读不出来只是少一句招呼，引导照样要能进。
            System.out.println(TAG + "hello.svg / welcome.svg 都没解析出来，覆盖层只留文字不描字");
            this.words = new Word[0];
        }
        this.cycles = new double[this.words.length];
        for (int i = 0; i < this.words.length; i++) {
            this.cycles[i] = this.words[i].cycle;
        }
    }

    private static Word word(SmilSvg svg, double endPhase, double firstInkPhase) {
        if (svg == null || !(svg.duration() > 0)) {
            return null;
        }
        return new Word(svg, endPhase, firstInkPhase);
    }

    /** 一共几枚字在轮着放（调试读数与测试用）。 */
    int wordCount() {
        return words.length;
    }

    /**
     * 从起表算过的<b>动画</b>秒数：按帧累加、大帧钳位，<b>不是</b>墙上时钟（见类注释），
     * 还没起表就是 0。
     *
     * <p>覆盖层那道 skip 门禁（{@link OnboardingOverlay#SKIP_GATE_SECONDS}）读的就是这一个数——
     * 玩家要等的是「招呼真的放出来 5 秒」，不是「墙上过了 5 秒」。首屏那几帧恰恰是两者差得最远的
     * 时候：实测被钳掉的最大一帧是 2506 ms，按墙钟它足够把门禁等满，按动画它只值 40 ms。</p>
     */
    double elapsedSeconds() {
        return elapsed;
    }

    /**
     * 这几枚字是不是已经没指望了：一枚素材都没读出来，或者过了兜底时限还没到得起表。
     *
     * <p>只有它为真时覆盖层才会自己收；正常情况下收层由玩家的输入决定。
     * 注意来回放的时钟<b>永远不会「播完」</b>，所以这里不存在「演完了就该退场」这条路径。</p>
     */
    public boolean stranded() {
        if (words.length == 0) {
            return true;
        }
        return !anchored && System.nanoTime() - createdAt > STRANDED_NANOS;
    }

    /**
     * 第一枚字描到哪儿了，0..1，<b>只往前走、描满之后一直返回 1</b>。
     *
     * <p>覆盖层用它决定那两行提示什么时候淡入。这里刻意不用「画面上现在有多少字」：
     * 来回放的时候那个数会周期性地掉回 0，提示行跟着每轮闪一次，比不写还扰人。
     * 分母取<b>有笔画的那一截</b>（{@code revealSpan}）而不是整段 {@code span}——开头那 0.25 秒
     * 空白已经从时间轴里折掉了，再拿它当分母会让提示行凭空晚半秒。</p>
     */
    public float firstPassProgress() {
        if (words.length == 0) {
            return 0f;
        }
        return (float) firstPassProgress(elapsed, words[0].revealSpan);
    }

    /** 纯函数版本，理由同 {@link #timelineSeconds}：给测试钉「提示行只淡入一次」用。 */
    static double firstPassProgress(double elapsedSeconds, double span) {
        if (span <= 0) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, elapsedSeconds / span));
    }

    /**
     * 纯函数：一帧真实间隔（秒）允许时间轴走多少。
     *
     * <p>大帧<b>钳</b>成 {@link #MAX_FRAME_SECONDS} 而不是照单全收，正是那轮「一出现就演了一半」
     * 的病灶：按墙上时钟，打开引导那一下的 200 ms 级卡顿会让描字白走五分之一；按这里只是慢了一帧。</p>
     */
    static double clampStep(double rawSeconds) {
        return Math.min(MAX_FRAME_SECONDS, Math.max(0.0, rawSeconds));
    }

    /**
     * 纯函数：从这一枚字落笔算过的秒数 → 该画它的哪一帧。正描 → 停 → 倒擦 → （可选）停 → 再正描。
     *
     * <p>写成静态纯函数是为了能在游戏外面钉死（见 {@code OnboardingGreetingStaticTest}）：
     * 这条映射的正确性只有「连续、满字端停够、且永远进不了素材的回卷区」三件事，跟 Skija 没关系。</p>
     */
    static double timelineSeconds(double elapsedSeconds, double span, double hold, double turn) {
        if (span <= 0 || hold < 0 || turn < 0) {
            return 0.0;
        }
        double cycle = 2.0 * span + hold + turn;
        double t = elapsedSeconds % cycle;
        if (t < 0) {
            t += cycle;
        }
        if (t < span) {
            return t;                        // 正描
        }
        t -= span;
        if (t < hold) {
            return span;                     // 满字停着不动
        }
        t -= hold;
        if (t < span) {
            return span - t;                 // 倒着擦回去
        }
        return 0.0;                          // 空白（turn 为 0 时这一格是空的）
    }

    /** 纯函数：起表后第 {@code elapsedSeconds} 秒轮到第几枚字。没有素材时为 -1。 */
    static int wordIndexAt(double elapsedSeconds, double[] cycles) {
        if (cycles.length == 0) {
            return -1;
        }
        double t = wrap(elapsedSeconds, total(cycles));
        for (int i = 0; i < cycles.length; i++) {
            if (t < cycles[i]) {
                return i;
            }
            t -= cycles[i];
        }
        return cycles.length - 1;
    }

    /** 纯函数：那一枚字自己的时间轴该走第几秒（从它落笔算起）。 */
    static double localElapsedAt(double elapsedSeconds, double[] cycles) {
        if (cycles.length == 0) {
            return 0;
        }
        double t = wrap(elapsedSeconds, total(cycles));
        for (double cycle : cycles) {
            if (t < cycle) {
                return t;
            }
            t -= cycle;
        }
        return 0;
    }

    /**
     * 纯函数：一枚字内部走到 {@code localSeconds} 秒 → 该问素材要哪一帧。
     *
     * <p>来回放跑在 {@code [0, revealSpan]} 这一段<b>有笔画</b>的时间上，再整体抬到素材的
     * {@code firstInk} 之上，所以第 0 秒拿到的就是「第一笔刚落地」那一帧，而不是导出器留的
     * 那 0.25 秒空白。值域因此被夹在 {@code [firstInk, span]}：既不会低到露出起点那颗圆头点，
     * 也不会高进素材自己那次擦除／回卷的那一截。</p>
     */
    static double assetTime(double localSeconds, double firstInk, double span,
                            double hold, double turn) {
        double reveal = span - firstInk;
        if (reveal <= 0) {
            return span;                     // 起墨相位登记得比画满还晚：停在画满那一帧，别乱抖
        }
        return firstInk + timelineSeconds(localSeconds, reveal, hold, turn);
    }

    private static double total(double[] cycles) {
        double total = 0;
        for (double cycle : cycles) {
            total += cycle;
        }
        return total;
    }

    private static double wrap(double value, double total) {
        if (total <= 0) {
            return 0;
        }
        double t = value % total;
        return t < 0 ? t + total : t;
    }

    /**
     * 纯函数：素材时间轴走到 {@code time} 这一帧，画面上到底有没有东西。
     *
     * <p>Skia 画零长虚线时会按 {@code stroke-linecap="round"} 在<b>每一段零长划的位置补一个圆头</b>，
     * 于是描字动画的两头各多出一颗不该存在的点：素材基底 {@code stroke-dasharray="0 100"} 在
     * {@code pathLength="100"} 上刚好铺满一轮，起点（s=0）与终点（s=100）各一颗 —— 玩家报的
     * 「首帧错误展示起始点」和「末尾还是有问题」是同一件事的两头。</p>
     *
     * <p>分两处治：<b>终点那颗改素材</b>（两份 svg 里每一对 dasharray 的「空」从 100 撑到 200，
     * 让花纹铺不满一轮，笔画值一个都没动，所以画满那一帧逐像素不变）；<b>起点那颗由这道闸挡</b>——
     * 「0 → 第一笔落地」之间本来就该是空的，既然素材在这一截只会画出个点，那就一个字都不发。
     * 擦回空白的那一端同样走这里。实测第 0 帧墨迹：hello 242 → 110、welcome 187 → 122，
     * 最右一列 511 → 7 / 14。</p>
     */
    static boolean visibleFrame(double time, double firstInk) {
        return time >= firstInk;
    }

    /**
     * 画这一帧的招呼字。
     *
     * @return 真的画了才 true；素材缺失、还没起表、这一帧本来就空、或不在 Skija 帧内都返回 false
     */
    public boolean render(double centerX, double centerY, double maxWidth, double maxHeight,
                          double alpha) {
        if (words.length == 0 || !(maxWidth > 0) || !(maxHeight > 0)
                || alpha <= .01 || !SkijaGfx.active()) {
            return false;
        }
        long now = System.nanoTime();
        if (!anchored) {
            if (alpha < START_PRESENCE) {
                framesBeforeAnchor++;
                return false;
            }
            anchor(now, alpha);
        } else {
            double rawNanos = now - lastFrameNanos;
            double step = clampStep(rawNanos / 1_000_000_000.0);
            if (step * 1_000_000_000.0 < rawNanos) {
                worstClampedNanos = Math.max(worstClampedNanos, rawNanos);   // 只为调试留个读数
            }
            elapsed += step;
            lastFrameNanos = now;
        }
        double local = localElapsedAt(elapsed, cycles);
        Word word = words[wordIndexAt(elapsed, cycles)];
        double time = assetTime(local, word.firstInk, word.span, HOLD_SECONDS, TURN_SECONDS);
        if (!visibleFrame(time, word.firstInk)) {
            // 正常情况下 assetTime 的值域就是 [firstInk, span]，这一道闸碰不到；
            // 留着是因为它钉的是「不许露那颗圆头点」这件事，映射哪天被改回去它得拦住。
            return false;
        }
        // drawIn 是「装进这个框并在框内居中」，所以左上角就是中心减掉半个框。
        // 这里以前用的是 min(maxWidth, maxHeight)：横宽的字恰好取到 maxHeight 所以看不出来，
        // 一旦框是竖的（窄窗口那几档），字就会被推到中心偏下，泛光与清晰两遍一起偏，没人能发现。
        double left = centerX - maxWidth * .5;
        double top = centerY - maxHeight * .5;
        // 先糊一遍再画清晰的：SkijaEffects.bloom 只负责「把里面的内容整体模糊」，
        // 所以两遍都得自己发。参照桌面歌词与灵动岛那两处对它的用法。
        SkijaEffects.bloom(() -> word.svg.drawIn(left, top, maxWidth, maxHeight, time,
                (float) (alpha * GLOW_ALPHA), 0));
        return word.svg.drawIn(left, top, maxWidth, maxHeight, time, (float) alpha, 0);
    }

    private void anchor(long now, double presence) {
        anchored = true;
        elapsed = 0.0;                        // 起表这一帧是空白的：第一笔留给下一帧
        lastFrameNanos = now;
        if (DEBUG) {
            StringBuilder budget = new StringBuilder();
            for (int i = 0; i < words.length; i++) {
                budget.append(i == 0 ? "第 " : "、第 ").append(i + 1).append(" 枚 有笔画 ")
                        .append(Math.round(words[i].revealSpan * 1000)).append(" ms")
                        .append("（素材那一截 ").append(Math.round(words[i].span * 1000))
                        .append(" ms，开头空白 ").append(Math.round(words[i].firstInk * 1000))
                        .append(" ms 已折掉，来回 ").append(Math.round(words[i].cycle * 1000))
                        .append(" ms）");
            }
            System.out.println(TAG + "起表：距构造 "
                    + Math.round((now - createdAt) / 1_000_000.0) + " ms，之前空等 "
                    + framesBeforeAnchor + " 帧，presence=" + presence + "，" + budget
                    + "；满字停 " + Math.round(HOLD_SECONDS * 1000)
                    + " ms，空白停 " + Math.round(TURN_SECONDS * 1000) + " ms");
        }
    }

    @Override
    public void close() {
        if (DEBUG && anchored) {
            System.out.println(TAG + "收层：时间轴走过 " + Math.round(elapsed * 1000)
                    + " ms（约 " + Math.round(elapsed / Math.max(1.0, total(cycles)))
                    + " 轮 hello+welcome），钳掉过 " + (worstClampedNanos > 0
                    ? "最多 " + Math.round(worstClampedNanos / 1_000_000.0) + " ms 的大帧"
                    : "没有大帧"));
        }
        // svg 是 SmilSvg 的进程级缓存，五枚常驻图标都共用它，这里不能关。
    }
}
