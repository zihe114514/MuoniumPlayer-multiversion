package com.muoniumplayer.core.rendering;

import com.muoniumplayer.core.ncm.music.AutomixCueMarker;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.render.skija.SkijaShapes;

/**
 * 把 automix 的接缝画在一条进度条上：一根竖线标出「从哪里开始切」，一段半透明带子标出
 * 「这一段是两首歌叠在一起」。
 *
 * <p>三处进度条（游戏内歌曲信息条、播放页控制栏、歌词页）共用这一个画法。之所以做成一个
 * 静态门面而不是各家自己画：这三条条的<b>几何</b>差别很大（5px 的细槽、可 hover 变粗的条、
 * 圆角槽），而记号的语义必须完全一致——同一个颜色、同一种宽窄关系、同一套钳位。玩家在游戏里
 * 和界面里看到的是同一件事，这才读得通。</p>
 *
 * <h2>为什么用两个矩形而不是裁剪</h2>
 *
 * <p>带子正好是「进度条上的一段」，最省事的做法是把进度条裁出来一块填上强调色。但歌词页那处
 * 早就写明过：MC 1.8.9 的主 framebuffer 没有 stencil，stencil 裁剪不生效（见
 * {@code MusicLyricsPanel} 里那句填充条的注释）。所以这里一律按分数算出左端与宽度，
 * 直接画两个独立矩形——不依赖任何裁剪能力。</p>
 *
 * <h2>为什么竖线要比条高</h2>
 *
 * <p>进度条本身只有 3–5px 高，一条同高的竖线落在里面读起来像「渲染坏了」而不是像记号。
 * 上下各出头 {@link #LINE_OVERHANG} 之后它才像一个插进条里的刻度；出头部分不受条的圆角影响，
 * 因为它画在条之后，且强调色半透明。</p>
 *
 * <h2>颜色</h2>
 *
 * <p>取主题强调色，与屏幕中央那个过渡圆环同一个来源（{@code AutomixTransitionAnimation}
 * 用的是 {@link NCMTheme#getAccentColor()} 加同一支兜底青色）。圆环只在交接<em>之中</em>出现，
 * 这条记号在交接<em>之前</em>就在；两者同色，玩家才会认出它们是同一件事。</p>
 */
public final class AutomixCuePainter {

    /** 主题色拿不到时用的兜底青，与「无缝切换」这一类设置项的分类色、过渡圆环的兜底一致。 */
    static final int FALLBACK_ACCENT = 0xFF5FD3E8;

    /** 竖线的宽度，像素（设计像素，与周围绘制同一口径）。 */
    static final double LINE_WIDTH = 1.5;

    /** 竖线在进度条上下各出头的长度，像素。 */
    static final double LINE_OVERHANG = 2.5;

    /** 交叠带比进度条上下各多出多少，像素。比条略厚，才不会被读成「填充的一部分」。 */
    static final double BAND_SPREAD = 0.75;

    /** 交叠带的常态透明度。交接之前它只是预告。 */
    static final double BAND_ALPHA_IDLE = 0.26;

    /** 交接进行中的交叠带透明度。此刻这段真的在响。 */
    static final double BAND_ALPHA_FADING = 0.44;

    /** 竖线的常态透明度。 */
    static final double LINE_ALPHA_IDLE = 0.68;

    /** 竖线在交接进行中的透明度。 */
    static final double LINE_ALPHA_FADING = 0.92;

    private AutomixCuePainter() {
    }

    /**
     * 画一处记号。
     *
     * @param barX      进度条左端的 x
     * @param barY      进度条顶边的 y
     * @param barWidth  进度条总宽，对应整首歌的时长
     * @param barHeight 进度条高
     * @param positions 本帧该画的位置（已过平滑，见 {@link AutomixCueMarker.Glide}）
     * @param fading    交接是否正在进行（决定亮度）
     * @param alpha     这一处界面自己的整体不透明度，记号跟着它淡
     */
    public static void paint(double barX, double barY, double barWidth, double barHeight,
                            AutomixCueMarker.Positions positions, boolean fading, float alpha) {
        if (positions == null || !positions.valid()) return;
        if (barWidth <= 0.0 || barHeight <= 0.0 || alpha <= 0.0f) return;

        // 一帧只问一次主题色。主题是随时间渐变的，取两次会让带子与竖线落在同一帧的两种颜色上；
        // 顺带把每帧两次取色（每次都要过一遍动画计算）压成一次。
        int accent = accentColor();
        double cue = positions.cueFraction;
        double bandWidth = positions.bandWidth();
        // 带子宽度按<b>宽度</b>判而不是按终点判：切点落在曲尾最后一段时终点会被钳到 1.0，
        // 那一段本来就所剩无几，宽度为 0 的圆角矩形画出来是一个点，不如不画。
        if (bandWidth > 0.0015) {
            double bandX = barX + cue * barWidth;
            double bandW = bandWidth * barWidth;
            SkijaShapes.roundedRect(bandX, barY - BAND_SPREAD, bandW,
                    barHeight + BAND_SPREAD * 2.0, Math.min(barHeight * .5 + BAND_SPREAD, bandW * .5),
                    scaleAlpha(accent, alpha * (fading ? BAND_ALPHA_FADING : BAND_ALPHA_IDLE)));
        }

        double lineX = barX + cue * barWidth - LINE_WIDTH * .5;
        SkijaShapes.roundedRect(lineX, barY - LINE_OVERHANG, LINE_WIDTH,
                barHeight + LINE_OVERHANG * 2.0, LINE_WIDTH * .5,
                scaleAlpha(accent, alpha * (fading ? LINE_ALPHA_FADING : LINE_ALPHA_IDLE)));
    }

    /** 记号用的强调色，ARGB。界面层只关心几何，颜色在这里定一处。 */
    public static int accentColor() {
        int accent = NCMTheme.getAccentColor();
        // 主题还没落地时 alpha 是 0：那是一支完全透明的色，画上去等于没画。
        return accent >>> 24 == 0 ? FALLBACK_ACCENT : accent;
    }

    /** 把一支不透明颜色的 alpha 换成 {@code sourceAlpha * fraction}，其余通道原样保留。 */
    private static int scaleAlpha(int argb, double fraction) {
        int sourceAlpha = (argb >>> 24) & 0xFF;
        double value = Math.max(0.0, Math.min(1.0, fraction));
        int alpha = (int) Math.round(sourceAlpha * value);
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }
}
