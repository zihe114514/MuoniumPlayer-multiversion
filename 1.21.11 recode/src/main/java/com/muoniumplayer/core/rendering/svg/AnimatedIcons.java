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
}
