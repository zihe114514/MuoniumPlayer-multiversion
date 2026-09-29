package com.muoniumplayer.core.rendering.svg;

/**
 * 界面里用到的<b>动画</b>矢量图标，一处一份，路径都指向 {@code /muonium/svg/}。
 *
 * <p>和 {@code BrandVectorIcons}（音源品牌的静态矢量标识）是两回事：那边是把 {@code <path d>}
 * 抄进 Java 源码、一次归一化之后每帧只画一条路径；这边整份 SVG 文件带 SMIL 时间轴，
 * 必须按帧求值，所以素材走资源文件而不是内联字符串 —— {@code like.svg} 有 190 KB，
 * 抄进源码会让一个类比整个 ncm 包还大。</p>
 *
 * <p>文件名一律 ASCII：这些名字要进 jar、要过 {@code getResourceAsStream}，
 * 素材原始文件名是中文（{@code 搜索.svg} 那批），入库时已经换过。</p>
 */
public final class AnimatedIcons {

    /** 搜索：四角取景框 + 放大镜，给搜索结果页的加载指示用。笔画是写死的黑色，要染色。 */
    public static final String SEARCH = "/muonium/svg/search.svg";
    /** 推理中：紫色星光一组，给灵动岛的模型推理状态。素材自带颜色，不染。 */
    public static final String INFERENCE = "/muonium/svg/inference.svg";
    /** 扫码框：红色四角 + 一条走完整轮的渐变扫描带，叠在二维码盘上。素材自带颜色。 */
    public static final String QR_SCAN = "/muonium/svg/qr-scan.svg";
    /** 喜欢：红心 + 一圈粒子。素材自带颜色。 */
    public static final String LIKE = "/muonium/svg/like.svg";
    /** 登录安全确认：粒子收拢成一面带锁的盾。素材自带颜色。 */
    public static final String LOGIN_SAFE = "/muonium/svg/login-safe.svg";
    /**
     * 引导覆盖层那枚「hello」：七段彩虹描边，一轮 3.45 秒。
     *
     * <p>和五枚图标不同，它不是「界面角落的一枚指示」，而是占满屏幕中央的主体，
     * 所以尺寸与泛光由 {@code OnboardingGreeting} 决定，这里只管素材与它的时间轴怪癖。</p>
     */
    public static final String HELLO = "/muonium/svg/hello.svg";
    /**
     * 引导覆盖层那枚「welcome」：和 hello 同一套导出（{@code pathLength="100"} 的虚线描字 +
     * 挂在 stroke 上的彩虹渐变），一轮 8.217 秒。
     *
     * <p>它自己那一条时间轴比 hello 长，是因为作者在里面多留了两段：3.27 秒字就画满了，
     * 4.87 秒之后虚线反过来缩回 0（素材自带一次「擦掉」），最后 0.4 秒空着。
     * 覆盖层只取「画满」之前那一段，擦与停都交给外面那条按帧走的来回放，
     * 这样两枚字的节奏是一样的（见 {@code OnboardingGreeting}）。</p>
     */
    public static final String WELCOME = "/muonium/svg/welcome.svg";

    /**
     * 爆心的起播相位。素材一轮 7.133 秒，开头约 1.4 秒整枝是 {@code display:none}（导出器把这一段
     * 留给了别的分镜），从 0 起播的话用户点完心要先盯着一秒多的空白。取 .3 是心刚画全、
     * 粒子正要散开的那一段。
     */
    private static final double LIKE_START_PHASE = .3;
    /**
     * 爆心该在哪儿收手。红心只演到 .47 是实的（墨迹盒 275×281，正好和 favorite.png 那枚同尺寸），
     * .49 起红填充缩掉、素材底下那层 {@code stroke="#000000"} 的心形描边露出来，.50 就只剩描边了。
     * 再往后 .56–.70 是第二跳，同样在 .74 塌回描边、.76 整枝消失。已喜欢的常驻图标冻在这一帧，
     * 爆心也收在这一帧，见 {@link #likeSolid()}。
     */
    private static final double LIKE_END_PHASE = .47;

    /**
     * hello 该在哪儿收手。
     *
     * <p>素材四个动画（{@code stroke-dasharray} / {@code stroke-dashoffset} /
     * {@code stroke-width} / 一条不播的 {@code d}）一律写的是
     * {@code repeatCount="indefinite" + fill="freeze"}。按 SMIL，无限循环的动画永远不「结束」，
     * 所以 {@code fill="freeze"} 在这张稿子上是导出的模板话，不起作用——时间轴走到 {@code dur}
     * 就回卷到 0，而 0 那一帧的基底 {@code stroke-dasharray="0 100"} 是「一笔都不画」。
     * 症状：描完整字之后约一帧的工夫，字整条消失。</p>
     *
     * <p>.999 拿到的就是作者设计的那一帧（收尾是 99/1，本来也不画满 100），所以它既是「描完」
     * 也是「倒着擦回去的起点」——来回放的两个顶点都不许越过它。
     * 和爱心那枚的 {@code LIKE_END_PHASE} 是同一类口子：素材两头有空档时，起播与停住
     * 都由素材自己说，不交给解释器去猜。</p>
     */
    private static final double HELLO_END_PHASE = .999;

    /**
     * hello 第一笔落地之前有多长。
     *
     * <p>素材开头 {@code 0 → .060386} 那一段虚线一直是 {@code "0 100"}——一个像素都不该有。
     * 但 Skia 画零长虚线时按 {@code stroke-linecap="round"} 在<b>每一段零长划的位置补一个圆头</b>，
     * 于是 {@code pathLength="100"} 的那两个端点（0 与 100）上各冒出一颗点，
     * 看着像「字还没写，先落了两滴墨」。覆盖层在时间轴低于这一相位时干脆什么都不画。</p>
     */
    private static final double HELLO_FIRST_INK_PHASE = .072464;

    /**
     * welcome 画满那一帧。素材的 {@code stroke-dasharray} 走到 .397566 就是 {@code "100 0"}
     * （整条字全露出来），之后 .592292 起反过来缩回 {@code "0 100"}——那是作者自己留的一次擦除，
     * 但节奏和 hello 对不上，所以来回放只用它画满之前那一段。
     */
    private static final double WELCOME_END_PHASE = .397566;

    /** welcome 第一笔落地之前有多长，同 {@code HELLO_FIRST_INK_PHASE}：素材开头 {@code "0 100"} 会冒两颗圆头点。 */
    private static final double WELCOME_FIRST_INK_PHASE = .030426;

    private AnimatedIcons() {
    }

    public static SmilSvg search() {
        return SmilSvg.load(SEARCH);
    }

    public static SmilSvg inference() {
        return SmilSvg.load(INFERENCE);
    }

    public static SmilSvg qrScan() {
        return SmilSvg.load(QR_SCAN);
    }

    public static SmilSvg loginSafe() {
        return SmilSvg.load(LOGIN_SAFE);
    }

    public static SmilSvg like() {
        return SmilSvg.load(LIKE);
    }

    /** {@link #like()} 爆心的起播相位，理由见 {@code LIKE_START_PHASE}。 */
    public static double likeStartPhase() {
        return LIKE_START_PHASE;
    }

    /** {@link #like()} 爆心的收手相位，理由见 {@code LIKE_END_PHASE}。 */
    public static double likeEndPhase() {
        return LIKE_END_PHASE;
    }

    /**
     * 爱心那枚「点一次爆一轮」的时钟：从 {@link #likeStartPhase()} 起、到 {@link #likeEndPhase()}
     * 停。每次点心都要新造一条，它记的是自己那条墙上时钟的起点。
     */
    public static SvgPlayback likeBurst() {
        SmilSvg like = like();
        return like == null ? null
                : SvgPlayback.once(like, LIKE_START_PHASE, LIKE_END_PHASE);
    }

    /**
     * 爱心那枚的「常驻帧」：已经演完、冻在 {@link #likeEndPhase()} 那颗画全的实心红心上。
     *
     * <p>已喜欢的那一行平时就显示这一帧。列表一开有 209 行，用 {@link #likeBurst()} 的话它们会
     * 跟着一起爆心，所以这里给一条走完的时钟。收手相位是实心段的最后一帧，于是「爆完停在哪儿」
     * 和「平时是什么」是同一帧 —— 交接看不出来。</p>
     */
    public static SvgPlayback likeSolid() {
        SmilSvg like = like();
        return like == null ? null
                : SvgPlayback.settled(like, LIKE_START_PHASE, LIKE_END_PHASE);
    }

    /** {@link #HELLO}：引导覆盖层那枚描字。素材自带彩虹，不染色。 */
    public static SmilSvg hello() {
        return SmilSvg.load(HELLO);
    }

    /**
     * {@link #hello()} 描完一遍的终点（占整轮的比例），理由见 {@code HELLO_END_PHASE}。
     *
     * <p>引导覆盖层那枚字<b>不走</b> {@link SvgPlayback}：它按帧自己累加时间轴并来回放
     * （见 {@code OnboardingGreeting}），这里只负责回答「一遍有多长」。</p>
     */
    public static double helloEndPhase() {
        return HELLO_END_PHASE;
    }

    /** {@link #hello()} 第一笔落地的相位；低于它这一帧是一个像素都没有的（理由见字段注释）。 */
    public static double helloFirstInkPhase() {
        return HELLO_FIRST_INK_PHASE;
    }

    /** {@link #WELCOME}：引导覆盖层那枚「welcome」。素材自带彩虹，不染色。 */
    public static SmilSvg welcome() {
        return SmilSvg.load(WELCOME);
    }

    /** {@link #welcome()} 画满那一帧（占整轮的比例），之后素材自己开始擦回去。 */
    public static double welcomeEndPhase() {
        return WELCOME_END_PHASE;
    }

    /** {@link #welcome()} 第一笔落地的相位，同 {@link #helloFirstInkPhase()}。 */
    public static double welcomeFirstInkPhase() {
        return WELCOME_FIRST_INK_PHASE;
    }
}
