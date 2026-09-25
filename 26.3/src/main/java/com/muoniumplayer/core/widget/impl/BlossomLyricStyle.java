package com.muoniumplayer.core.widget.impl;

import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.PlaybackPulse;
import com.muoniumplayer.core.rendering.StencilClipManager;
import com.muoniumplayer.core.rendering.animation.spring.SpringAnimation;
import com.muoniumplayer.core.rendering.animation.spring.SpringParams;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.render.skija.SkijaEffects;
import com.muoniumplayer.render.skija.SkijaShapes;

import io.github.humbleui.skija.Shader;

import java.awt.Color;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 桌面歌词的「Blossom」样式：把 modernMP 的浮动歌词挂件整套搬过来。
 *
 * <h2>它和经典样式的区别</h2>
 * 经典样式是「一块透明画布上的几行字」，靠边缘淡出与逐字发光撑住层次。Blossom 是一张
 * <b>毛玻璃卡片</b>：背景实时模糊 + 深色底 + 可选的踩点描边，字则完全左对齐、行槽固定，
 * 靠<b>弹簧级联滚动</b>、<b>整行缩放</b>、<b>逐字扫光</b>、<b>逐字上浮</b>与<b>字母膨胀</b>
 * 五层动画表达节奏。它<b>不画任何光晕</b>——mp 的浮动挂件刻意没有，卡片本身已经提供了对比度，
 * 再加光会糊成一团。
 *
 * <h2>为什么参数一个不改</h2>
 * 下面的常量全部是 mp 的字面值（它们又来自 AMLL / Apple Music Specs 的逆向结果）。改动任何
 * 一个都会破坏那套互相咬合的时序：级联延迟配的是 100/18 那组弹簧、羽化宽度配的是 2.2 倍行槽、
 * 膨胀的 1.5 秒门槛配的是 0.05 秒级联步长。所以这里只做<b>坐标系与绘制后端</b>的适配。
 *
 * <h2>与 mp 的三处刻意差异</h2>
 * <ol>
 *   <li>mp 的盒子宽度固定 380、高度 = 行槽 × 可见行数；Mu 的 HUD 盒子由用户拖拽调整，
 *       所以行槽反过来由 {@code 盒高 / 可见行数} 得出，留白与圆角按盒子等比缩放。这样面板
 *       永远正好填满用户画的框。</li>
 *   <li>mp 逐字扫光是自己写的片元着色器，按四边形顶点烘焙每个字形的渐变；这里用 Skia 的
 *       水平线性渐变直接喂给字形填充，<b>整个字词一次绘制</b>就得到逐像素羽化。字母膨胀
 *       那一路才需要逐字形绘制。</li>
 *   <li>mp 的英文行换 SF Pro；Mu 没有这套字体，全用同一个字族。</li>
 * </ol>
 */
public final class BlossomLyricStyle implements SharedConstants, SharedRenderingConstants {

    // ── 排版：mp BASE_W=380 / BASE_PAD_X=10 的比例口径 ─────────────────────────
    /** 文字列左右留白占盒宽的比例。 */
    private static final double PAD_X_RATIO = 10.0 / 380.0;
    /** 一行超过文字列宽的这个比例就折行。 */
    private static final double WRAP_RATIO = .70;
    /** 折行后相邻物理行的行距，字高的倍数。 */
    private static final double MAIN_STEP_RATIO = 1.15;
    /** 行槽是字高的这个倍数。mp 的 2.2，整套动画的时序都按它调过。 */
    private static final double ROW_SLOT_RATIO = 2.2;
    /** 面板圆角占盒高的比例。mp 用的是 M3 Shape.XL = 28，那是配它 144 高的盒子。 */
    private static final double CORNER_RATIO = .20;
    private static final double CORNER_MAX = 28.0;
    private static final double CORNER_MIN = 8.0;
    // ── 动画常量：mp / AMLL / Apple Music Specs 的字面值 ────────────────────────
    /** 当前行提前多久开始亮起。 */
    private static final long ACTIVE_FADE_IN_MS = 600L;
    /** 唱完之后多久淡回普通行（Specs lineFinishProgressAnimationDuration）。 */
    private static final long ACTIVE_FADE_OUT_MS = 250L;
    /** AMLL SCALE_ASPECT：播放中非当前行缩到 0.97。 */
    private static final double SCALE_ASPECT = .97;
    /** 逐字上浮的峰值位移，像素。 */
    private static final double LIFT_PEAK_PX = 1.5;
    /** Specs liftSpringTimingParameters（mass 1 / stiffness 14 / damping 7）解出的 omega0 与 zeta。 */
    private static final double LIFT_OMEGA0 = 3.7416574;
    private static final double LIFT_ZETA = .9354143;
    /** 填涂前沿的羽化半宽，像素。mp 实际用的是它的 1.6 倍。 */
    private static final double SWEEP_FADE_PX = 14.0;
    /** 还没唱到的部分保留的亮度比例。 */
    private static final double DARK_MASK_ALPHA = .36;
    /** 字母膨胀的门槛：只有一行的首/末词停留够久才算。 */
    private static final long EMPHASIZE_MIN_DWELL_MS = 1500L;
    /** 级联延迟的步长与衰减（AMLL base/index.ts）。 */
    private static final double LINE_DELAY_S = .05;
    private static final double LINE_DELAY_DECAY = 1.05;
    /** 非当前行的底透明度，以及任何行的下限。 */
    private static final double IDLE_ALPHA = .20;
    private static final double MIN_ALPHA = .12;
    /** 上浮位移的追随系数：往上快、回落慢，mp 原样。 */
    private static final double LIFT_RISE_K = .55;
    private static final double LIFT_FALL_K = .10;

    // ── 面板 ──────────────────────────────────────────────────────────────────
    /** mp 的面板底色 Color(19, 20, 25)。 */
    private static final int SURFACE_RGB = 0x131419;
    /** 底色在不透明度 1.0 时的 alpha。mp 就是按 220 而不是 255 打的底。 */
    private static final int SURFACE_MAX_ALPHA = 220;
    /**
     * 背景模糊的高斯 sigma，GUI 单位。
     *
     * <p>mp 走的是 Kawase 双滤波（迭代 2、偏移 3 个物理像素）。Kawase 是用若干次带偏移的
     * 四点采样逼近高斯，等效 sigma 约等于偏移乘迭代次数的平方和开方，落在 6~8 之间；
     * 这里取 7 作为视觉等价值。用 GUI 单位而不是物理像素：面板本身的尺寸也是 GUI 单位，
     * 换 GUI 缩放时模糊与卡片的比例才不变。</p>
     */
    private static final float BACKDROP_SIGMA = 7f;
    /** 踩点描边的包络时间常数：冲上去快、落下来慢。 */
    private static final double BEAT_ATTACK_TAU = .035;
    private static final double BEAT_RELEASE_TAU = .16;
    /** 描边的 alpha 与线宽随脉冲变化的口径，mp 原样。 */
    private static final double BORDER_BASE_ALPHA = 28.0;
    private static final double BORDER_PULSE_ALPHA = 210.0;
    private static final double BORDER_BASE_WIDTH = 1.0;
    private static final double BORDER_PULSE_WIDTH = 1.5;

    /** 行位移弹簧：Specs lineChangeSpringTimingParameters（mass 1 / damping 18 / stiffness 100）。 */
    private static final SpringParams LINE_SPRING = new SpringParams(1.0, 18.0, 100.0, false);
    /** 整行缩放弹簧：AMLL scaleSpringParams，zeta 约 0.88，够顺又不回弹。 */
    private static final SpringParams SCALE_SPRING = new SpringParams(2.0, 25.0, 100.0, false);
    // ── 每行状态 ──────────────────────────────────────────────────────────────
    /** 行位移弹簧，一行一个，级联才能各自错开。 */
    private SpringAnimation[] lineSpring = new SpringAnimation[0];
    private boolean lineSpringInit;
    /** 整行缩放弹簧，以及它们各自的当前目标。 */
    private SpringAnimation[] scaleSpring = new SpringAnimation[0];
    private double[] scaleTarget = new double[0];
    private boolean scaleSpringInit;
    private int springAnchorPrev = Integer.MIN_VALUE;
    private long springLastNs;
    private double lastRestOffset = Double.NaN;

    /** 单行模式专用的缩放弹簧，走自己的时钟。 */
    private final SpringAnimation singleScaleSpring = new SpringAnimation(SCALE_SPRING);
    private boolean singleScaleInit;
    private double singleScaleTarget = Double.NaN;
    private long singleScaleLastNs;

    // ── 缓存 ──────────────────────────────────────────────────────────────────
    private final Map<LyricLine, LineLayout> layoutCache = new IdentityHashMap<LyricLine, LineLayout>();
    private final Map<LyricLine, float[]> wordWidthCache = new IdentityHashMap<LyricLine, float[]>();
    private final Map<LyricLine, float[]> wordLiftCache = new IdentityHashMap<LyricLine, float[]>();
    /** 排版缓存是按这套几何建的；任何一项变了就整批作废。 */
    private double cachedWrapW = Double.NaN;
    private double cachedRowH = Double.NaN;
    private double cachedGlyphH = Double.NaN;
    /** 上一次排版时的歌词归属键。换歌要清缓存并重新播种弹簧。 */
    private String lastTrackKey;

    // 逐帧复用的暂存数组（只增不减），避免每帧分配
    private double[] lineTops = new double[0];
    private double[] lineHeights = new double[0];
    private double[] lineCenter = new double[0];
    private double[] wordX = new double[0];
    private int[] wordRow = new int[0];
    private double[] wordLift = new double[0];

    private float beatPulse;
    private long beatLastNs;

    /** 一行的折行结果。译文不参与——mp 的浮动挂件不显示译文。 */
    private static final class LineLayout {
        /** 每个物理行起始的字词下标，长度 = 行数 + 1；行级歌词为 null。 */
        int[] rowStart;
        /** 行级歌词折行后的每一行文本；逐字歌词为 null。 */
        String[] plainRows;
        int mainRows;
        /** 这一行占掉的总纵向高度。 */
        double height;
        /** 主文本块的垂直中心，滚动锚点与缩放锚点都用它。 */
        double mainCenter;
    }

    private static final String[] EMPTY_ROWS = new String[0];

    /** 当前样式是否是 Blossom。 */
    public static boolean isActive() {
        return HudConfig.osdLyricStyle == 1;
    }

    /** 设置界面里显示的当前样式名。 */
    public static String currentStyleName() {
        return HudConfig.osdLyricStyle == 1 ? "Blossom" : "经典";
    }

    /** 循环到下一个样式。索引在这里取模，越界的旧配置因此也能被拉回合法范围。 */
    public static void cycleStyle() {
        int count = HudConfig.OSD_LYRIC_STYLE_MAX + 1;
        HudConfig.osdLyricStyle = (Math.max(0, HudConfig.osdLyricStyle) + 1) % count;
    }

    /**
     * 可选字号阶梯，从小到大。字号只能取这几档：字形图集是 2 倍超采样后精确缩半，
     * 任何别的倍率都要重采样，字会发虚。
     */
    private static CFontRenderer[] fontLadder() {
        return new CFontRenderer[] { FontManager.pf20bold, FontManager.pf25bold,
                FontManager.pf28bold, FontManager.pf34bold, FontManager.pf40bold,
                FontManager.pf50bold };
    }

    /**
     * 按行槽高度挑字号：取「字高 × 2.2 仍然装得进行槽」的最大一档。
     *
     * <p>mp 是反过来的——它固定 22px 字号，然后让盒子高度等于 {@code 字高 × 2.2 × 行数}。
     * Mu 的盒子由用户拖拽决定，所以只能反解字号。2.2 这个比例必须守住：羽化宽度、上浮位移、
     * 级联步长全是按它调的，字塞满行槽的话上下两行会贴在一起，留太多空又会显得空旷。</p>
     */
    private static CFontRenderer fontForRow(double rowH) {
        CFontRenderer[] ladder = fontLadder();
        CFontRenderer picked = ladder[0];
        for (int i = 0; i < ladder.length; i++) {
            CFontRenderer candidate = ladder[i];
            if (candidate == null || candidate.getHeight() <= 0) {
                continue;
            }
            if (candidate.getHeight() * ROW_SLOT_RATIO <= rowH) {
                picked = candidate;
            }
        }
        return picked;
    }

    /** 当前帧选中的字体。每帧在 {@link #render} 开头定下来，本类其它方法都读它。 */
    private CFontRenderer font = FontManager.pf28bold;
    /**
     * 画一帧。调用方负责已经把 HUD 的位置与缩放矩阵压好。
     *
     * @param baseRgb        普通歌词颜色（未唱部分）
     * @param highRgb        当前歌词颜色（已唱部分），也是踩点描边的强调色
     * @param singleLineMode 挂件的「单行模式」开关；为真时等价于 mp 的可见行数 = 1
     */
    public void render(double rx, double ry, double bw, double bh,
                       int baseRgb, int highRgb, boolean singleLineMode) {
        // 与经典样式同一个锁。换歌是「清空再重填」同一个列表实例，读到一半被清就是下标越界，
        // 而这是每帧都跑的渲染路径。
        synchronized (CloudMusic.lyrics) {
            renderLocked(rx, ry, bw, bh, baseRgb, highRgb, singleLineMode);
        }
    }

    private void renderLocked(double rx, double ry, double bw, double bh,
                              int baseRgb, int highRgb, boolean singleLineMode) {
        List<LyricLine> lyrics = CloudMusic.lyrics;
        int n = lyrics.size();
        if (n <= 0 || bw <= 1 || bh <= 1) {
            return;
        }
        int vis = singleLineMode ? 1 : clampInt(HudConfig.blossomVisibleLines, 1, 5);
        double rowH = bh / vis;
        font = fontForRow(rowH);
        double glyphH = font.getHeight();
        if (glyphH <= 0) {
            return;
        }

        final double corner = Math.min(CORNER_MAX, Math.max(CORNER_MIN, bh * CORNER_RATIO));
        drawPanel(rx, ry, bw, bh, corner, highRgb);

        invalidateOnTrackChange();

        long posMs = CloudMusic.positionMsNow();
        double px = bw * PAD_X_RATIO;
        double wrapW = Math.max(1.0, (bw - px * 2.0) * WRAP_RATIO);
        double mainStep = glyphH * MAIN_STEP_RATIO;
        invalidateOnGeometryChange(wrapW, rowH, glyphH);

        // 当前行 = 第一条「还没唱完」的行。按结束时间而不是下一行的开始时间切换，
        // 两行之间有间隙时才不会出现一段谁都不亮的空窗。
        int activeIdx = n - 1;
        for (int i = 0; i < n; i++) {
            if (posMs < endMs(lyrics.get(i))) {
                activeIdx = i;
                break;
            }
        }

        final double clipX = rx;
        final double clipY = ry;
        final double clipW = bw;
        final double clipH = bh;
        StencilClipManager.beginClip(new Runnable() {
            public void run() {
                SkijaShapes.roundedRect(clipX, clipY, clipW, clipH, corner, -1);
            }
        });
        try {
            if (vis == 1) {
                drawSingleLine(lyrics, activeIdx, posMs, rx, ry, bw, bh, px, wrapW, rowH,
                        mainStep, baseRgb, highRgb);
            } else {
                drawList(lyrics, activeIdx, vis, posMs, rx, ry, bw, bh, px, wrapW, rowH,
                        mainStep, baseRgb, highRgb);
            }
        } finally {
            StencilClipManager.endClip();
        }
    }

    /**
     * 毛玻璃卡片本体：背景模糊 → 深色底 → 可选的踩点描边。
     *
     * <p>三层的顺序不能换。模糊必须第一层：它读的是「图层下方已经画好的像素」，
     * 底色一压上去就只能模糊到自己。</p>
     */
    private void drawPanel(double x, double y, double w, double h, double corner, int accentRgb) {
        float blur = (float) clamp01(HudConfig.blossomBackdropBlur);
        if (blur > .004f) {
            SkijaEffects.backdropBlurRounded(x, y, w, h, corner, BACKDROP_SIGMA, blur);
        }

        int surfaceAlpha = (int) Math.round(SURFACE_MAX_ALPHA * clamp01(HudConfig.blossomPanelOpacity));
        if (surfaceAlpha > 0) {
            roundedRect(x, y, w, h, corner, (surfaceAlpha << 24) | SURFACE_RGB);
        }

        double strength = clamp01(HudConfig.blossomBorderStrength);
        if (!HudConfig.blossomRhythmBorder || strength <= 0) {
            // 描边关掉时连节拍都不读：PlaybackPulse 会往音频图上挂一个包络跟随器，
            // 没人要这条数据就不该挂着。
            beatPulse = 0f;
            beatLastNs = 0L;
            return;
        }
        updateBeatPulse();
        double pulse = beatPulse;
        int alpha = (int) Math.round((BORDER_BASE_ALPHA + BORDER_PULSE_ALPHA * pulse) * strength);
        if (alpha <= 0) {
            return;
        }
        double width = BORDER_BASE_WIDTH + BORDER_PULSE_WIDTH * pulse * strength;
        roundedOutline(x, y, w, h, corner, width, new Color((accentRgb >> 16) & 0xFF,
                (accentRgb >> 8) & 0xFF, accentRgb & 0xFF, Math.min(255, alpha)));
    }
    /**
     * 踩点脉冲的指数包络。
     *
     * <p>直接把 {@link PlaybackPulse#beat()} 画到描边上会一帧一跳：那条量本身是逐帧的能量冲击。
     * 攻击时间常数比释放小一个数量级，鼓点因此「一亮一收」而不是抖动。</p>
     */
    private void updateBeatPulse() {
        long now = System.nanoTime();
        double dt = beatLastNs == 0L ? .016 : (now - beatLastNs) / 1_000_000_000.0;
        beatLastNs = now;
        if (dt < 0) {
            dt = 0;
        }
        if (dt > .05) {
            dt = .05;
        }
        float target = PlaybackPulse.isAvailable() ? (float) clamp01(PlaybackPulse.beat()) : 0f;
        double tau = target > beatPulse ? BEAT_ATTACK_TAU : BEAT_RELEASE_TAU;
        beatPulse += (target - beatPulse) * (1f - (float) Math.exp(-dt / tau));
        if (Math.abs(target - beatPulse) < .001f) {
            beatPulse = target;
        }
    }

    /**
     * 换歌时重新播种。
     *
     * <p>{@code CloudMusic.lyrics} 是个 final 的列表实例，换歌是清空重填而不是换引用，
     * 所以不能像 mp 那样比较列表身份，要比 {@link CloudMusic#lyricsTrackKey}。不重置的话
     * 弹簧会停在上一首的偏移上，新歌开头每一行都被滚出卡片外面。</p>
     */
    private void invalidateOnTrackChange() {
        String key = CloudMusic.lyricsTrackKey;
        if (key == null ? lastTrackKey == null : key.equals(lastTrackKey)) {
            return;
        }
        lastTrackKey = key;
        layoutCache.clear();
        wordWidthCache.clear();
        wordLiftCache.clear();
        lineSpringInit = false;
        scaleSpringInit = false;
        singleScaleInit = false;
        springAnchorPrev = Integer.MIN_VALUE;
        lastRestOffset = Double.NaN;
    }

    /** 盒子被拖过、行数被改过或字体换过档，折行结果就不再成立。 */
    private void invalidateOnGeometryChange(double wrapW, double rowH, double glyphH) {
        if (Math.abs(wrapW - cachedWrapW) < .01 && Math.abs(rowH - cachedRowH) < .01
                && Math.abs(glyphH - cachedGlyphH) < .01) {
            return;
        }
        cachedWrapW = wrapW;
        cachedRowH = rowH;
        cachedGlyphH = glyphH;
        layoutCache.clear();
        wordWidthCache.clear();
    }

    // ── 多行列表 ──────────────────────────────────────────────────────────────

    private void drawList(List<LyricLine> lyrics, int activeIdx, int vis, long posMs,
                          double rx, double ry, double bw, double bh, double px,
                          double wrapW, double rowH, double mainStep, int baseRgb, int highRgb) {
        int n = lyrics.size();
        ensureLineArrays(n);
        for (int i = 0; i < n; i++) {
            LineLayout lay = layoutFor(lyrics.get(i), wrapW, rowH, mainStep);
            lineHeights[i] = lay.height;
            lineCenter[i] = lay.mainCenter;
        }
        // 起点留 0.7 个盒高：第一行也要能从卡片下缘滑进来，而不是一开始就贴在中线上。
        double cum = bh * .7;
        for (int i = 0; i < n; i++) {
            lineTops[i] = cum;
            cum += lineHeights[i];
        }

        double centerY = ry + bh * .5;
        double restOffset = centerY - (lineTops[activeIdx] + lineCenter[activeIdx]);
        ensureSprings(n);

        long nowNs = System.nanoTime();
        double springDt = springLastNs == 0L ? 0.0 : (nowNs - springLastNs) / 1_000_000_000.0;
        springLastNs = nowNs;
        if (springDt < 0) {
            springDt = 0;
        }
        if (springDt > .05) {
            springDt = .05;
        }

        retarget(lyrics, activeIdx, restOffset, ry, rowH);

        // 每一根都要走，包括画不到的：它们的排队延迟得继续跑，滑进来时才对得上相位。
        if (springDt > 0) {
            for (int i = 0; i < n; i++) {
                lineSpring[i].update(springDt);
                scaleSpring[i].update(springDt);
            }
        }
        updateScaleTargets(n, activeIdx);

        int half = (vis - 1) / 2;
        int lo = Math.max(0, activeIdx - half - 1);
        int hi = Math.min(n - 1, activeIdx + (vis - 1 - half) + 1);
        for (int i = lo; i <= hi; i++) {
            double drawY = lineSpring[i].getCurrentPosition();
            if (drawY + lineHeights[i] < ry || drawY > ry + bh) {
                continue;
            }
            LyricLine line = lyrics.get(i);
            LineLayout lay = layoutFor(line, wrapW, rowH, mainStep);
            long prevEnd = i == 0 ? Long.MIN_VALUE / 2 : endMs(lyrics.get(i - 1));
            double activeK = computeActiveK(line, posMs, prevEnd);
            double baseAlpha = Math.max(MIN_ALPHA, IDLE_ALPHA + (1.0 - IDLE_ALPHA) * activeK);
            drawScaledLine(line, lay, rx + px, drawY, posMs, activeK, baseAlpha, mainStep,
                    baseRgb, highRgb, scaleSpring[i].getCurrentPosition());
        }
    }
    private void ensureLineArrays(int n) {
        if (lineTops.length == n) {
            return;
        }
        lineTops = new double[n];
        lineHeights = new double[n];
        lineCenter = new double[n];
    }

    private void ensureSprings(int n) {
        if (lineSpring.length != n) {
            lineSpring = new SpringAnimation[n];
            for (int i = 0; i < n; i++) {
                lineSpring[i] = new SpringAnimation(LINE_SPRING);
            }
            lineSpringInit = false;
        }
        if (scaleSpring.length != n) {
            scaleSpring = new SpringAnimation[n];
            scaleTarget = new double[n];
            for (int i = 0; i < n; i++) {
                scaleSpring[i] = new SpringAnimation(SCALE_SPRING);
                scaleTarget[i] = Double.NaN;
            }
            scaleSpringInit = false;
        }
    }

    /**
     * 重新给每一行下达目标位置，并按 AMLL 的规则从上往下累加级联延迟。
     *
     * <p>只在<b>锚点行换了</b>、<b>排版真的移动了</b>或<b>发生大跳</b>时才做。每帧都下达目标会把
     * 解析解的时钟每帧归零，动画就永远走不出第一帧。</p>
     *
     * <p>大跳（超过三个行槽）判定为拖进度而不是正常播放：这时不走弹簧而直接吸附，否则每一行
     * 都要花几秒钟从卡片外面荡回来。</p>
     */
    private void retarget(List<LyricLine> lyrics, int activeIdx, double restOffset,
                          double ry, double rowH) {
        int n = lyrics.size();
        boolean anchorMoved = activeIdx != springAnchorPrev;
        boolean layoutMoved = Double.isNaN(lastRestOffset)
                || Math.abs(restOffset - lastRestOffset) > .5;
        boolean bigJump = lineSpringInit
                && Math.abs(restOffset + lineTops[0] - lineSpring[0].getCurrentPosition()) > rowH * 3.0;
        if (!anchorMoved && !layoutMoved && !bigJump) {
            return;
        }

        // 换行时按「上一行留下多大的空隙、它的字词有多稀」自适应放松弹簧：接得紧就保持
        // 100/18 那组硬基线，中间空一大段就软下来，听觉上的呼吸和视觉上的滑动才同步。
        if (anchorMoved && activeIdx > 0 && activeIdx < n) {
            double relax = lineChangeRelax(lyrics, activeIdx);
            double stiff = 100.0 - 45.0 * relax;
            double zeta = .90 - .28 * relax;
            SpringParams relaxed = new SpringParams(1.0, zeta * 2.0 * Math.sqrt(stiff), stiff, false);
            for (int i = 0; i < n; i++) {
                lineSpring[i].setParams(relaxed);
            }
        }
        springAnchorPrev = activeIdx;
        lastRestOffset = restOffset;

        double delay = 0.0;
        double baseDelay = LINE_DELAY_S;
        for (int i = 0; i < n; i++) {
            double restTop = restOffset + lineTops[i];
            if (lineSpringInit && !bigJump) {
                lineSpring[i].setTargetPosition(restTop, delay);
            } else {
                lineSpring[i].setPosition(restTop);
            }
            // 只有还在卡片里（或下方）的行才累加延迟；越过锚点之后步长收紧，下面的行因此抱团。
            if (restTop >= ry) {
                delay += baseDelay;
                if (i >= activeIdx) {
                    baseDelay /= LINE_DELAY_DECAY;
                }
            }
        }
        lineSpringInit = true;
    }

    /**
     * 整行缩放的目标：当前行放大到「整行缩放」的读数，其它播放中的行 0.97，暂停时全部 1.0。
     *
     * <p>目标没变就不重新下达。Mu 的弹簧在 {@code setTargetPosition} 里无条件重建解析解，
     * 每帧调用等于每帧把时钟归零。</p>
     */
    private void updateScaleTargets(int n, int activeIdx) {
        boolean playing = CloudMusic.isAudibleNow();
        double emphasis = HudConfig.currentLineScale;
        for (int i = 0; i < n; i++) {
            double target = !playing ? 1.0 : (i == activeIdx ? emphasis : SCALE_ASPECT);
            if (!scaleSpringInit) {
                scaleSpring[i].setPosition(target);
                scaleTarget[i] = target;
            } else if (target != scaleTarget[i]) {
                scaleSpring[i].setTargetPosition(target);
                scaleTarget[i] = target;
            }
        }
        scaleSpringInit = true;
    }

    // ── 单行模式 ──────────────────────────────────────────────────────────────

    /**
     * 只画当前行，垂直居中，没有滚动也没有级联。
     *
     * <p>可见行数为 1 时那套级联/滚动弹簧只会产生一次没人要的上飘：本来就没有别的行可以滚。</p>
     */
    private void drawSingleLine(List<LyricLine> lyrics, int activeIdx, long posMs,
                                double rx, double ry, double bw, double bh, double px,
                                double wrapW, double rowH, double mainStep,
                                int baseRgb, int highRgb) {
        LyricLine line = lyrics.get(activeIdx);
        LineLayout lay = layoutFor(line, wrapW, rowH, mainStep);
        double glyphH = font.getHeight();
        double blockH = (lay.mainRows - 1) * mainStep + glyphH;
        double drawY = ry + (bh - blockH) * .5;

        long prevEnd = activeIdx == 0 ? Long.MIN_VALUE / 2 : endMs(lyrics.get(activeIdx - 1));
        double activeK = computeActiveK(line, posMs, prevEnd);
        double baseAlpha = Math.max(MIN_ALPHA, IDLE_ALPHA + (1.0 - IDLE_ALPHA) * activeK);

        boolean playing = CloudMusic.isAudibleNow();
        double target = playing ? HudConfig.currentLineScale : 1.0;
        if (!singleScaleInit) {
            singleScaleSpring.setPosition(target);
            singleScaleTarget = target;
            singleScaleInit = true;
        } else if (target != singleScaleTarget) {
            singleScaleSpring.setTargetPosition(target);
            singleScaleTarget = target;
        }
        long nowNs = System.nanoTime();
        double dt = singleScaleLastNs == 0L ? 0.0 : (nowNs - singleScaleLastNs) / 1_000_000_000.0;
        singleScaleLastNs = nowNs;
        if (dt > 0) {
            singleScaleSpring.update(Math.min(dt, .05));
        }

        // 中心锚点用整块的一半而不是 lay.mainCenter：单行模式是整块居中，行槽的上半空白不算。
        drawScaledLine(line, lay, rx + px, drawY, posMs, activeK, baseAlpha, mainStep,
                baseRgb, highRgb, singleScaleSpring.getCurrentPosition(), blockH * .5);

        // 之后切回多行必须从零重新播种，否则弹簧接着单行模式的旧位置往下走。
        lineSpringInit = false;
        scaleSpringInit = false;
        springAnchorPrev = Integer.MIN_VALUE;
        lastRestOffset = Double.NaN;
    }
    // ── 单行绘制 ──────────────────────────────────────────────────────────────

    /** 填涂状态：整词已唱 / 整词未唱 / 前沿正压在这个词上。 */
    private static final int LIT = 0;
    private static final int DARK = 1;
    private static final int SWEEPING = 2;

    private void drawScaledLine(LyricLine line, LineLayout lay, double x, double y, long posMs,
                                double activeK, double baseAlpha, double mainStep,
                                int baseRgb, int highRgb, double scale) {
        drawScaledLine(line, lay, x, y, posMs, activeK, baseAlpha, mainStep, baseRgb, highRgb,
                scale, lay.mainCenter);
    }

    private void drawScaledLine(LyricLine line, LineLayout lay, double x, double y, long posMs,
                                double activeK, double baseAlpha, double mainStep,
                                int baseRgb, int highRgb, double scale, double anchorOffsetY) {
        api.getGLStateManager().pushMatrix();
        scaleAtPos(x, y + anchorOffsetY, scale);
        try {
            if (lay.rowStart != null) {
                drawWordRow(line, x, y, posMs, activeK, baseAlpha, lay.rowStart, mainStep,
                        baseRgb, highRgb);
            } else if (lay.plainRows != null) {
                int argb = (alpha255(baseAlpha) << 24) | (baseRgb & 0xFFFFFF);
                for (int r = 0; r < lay.plainRows.length; r++) {
                    font.drawString(lay.plainRows[r], x, y + r * mainStep, argb);
                }
            }
        } finally {
            api.getGLStateManager().popMatrix();
        }
    }

    /**
     * 逐字歌词的一整行（可能折成多个物理行）。
     *
     * <p>填涂前沿是「第几个词 + 词内比例」，与折行无关。前沿所在行的上方各行整词点亮、下方
     * 整词压暗，只有前沿那一行拿到移动的羽化——所以每个词只需要在「亮 / 暗 / 正在扫」三个
     * 状态里选一个，前两者是一次纯色绘制，第三者才需要渐变。</p>
     *
     * <p>羽化用一条水平线性渐变喂给字形填充，Skia 按 CLAMP 平铺：渐变左端之外恒为已唱色、
     * 右端之外恒为未唱色，正好就是 mp 那个片元着色器的语义，而且一整个词只画一次。</p>
     */
    private void drawWordRow(LyricLine line, double x, double y, long posMs, double activeK,
                             double baseAlpha, int[] rowStart, double mainStep,
                             int baseRgb, int highRgb) {
        List<LyricLine.Word> words = line.words;
        int n = words.size();
        float[] ww = wordWidths(line, font);
        if (n <= 0 || ww.length != n) {
            return;
        }
        ensureWordArrays(n);

        int rows = rowStart.length - 1;
        for (int r = 0; r < rows; r++) {
            int from = rowStart[r];
            int to = rowStart[r + 1];
            double acc = x;
            for (int i = from; i < to && i < n; i++) {
                wordX[i] = acc;
                wordRow[i] = r;
                acc += ww[i];
            }
        }

        int sweepWord;
        double sweepFrac;
        long firstStart = words.get(0).timestamp;
        LyricLine.Word last = words.get(n - 1);
        long lastEnd = last.timestamp + Math.max(0L, last.duration);
        if (posMs < firstStart) {
            sweepWord = -1;
            sweepFrac = 0;
        } else if (posMs >= lastEnd) {
            sweepWord = n;
            sweepFrac = 0;
        } else {
            sweepWord = n;
            sweepFrac = 0;
            for (int s = 0; s < n; s++) {
                long start = words.get(s).timestamp;
                // 结束时间取下一个词的开头而不是自己的 duration：逐字歌词里 duration 常常留有
                // 空隙，按 duration 算前沿会在每个词之间停一下。
                long end = s + 1 < n ? words.get(s + 1).timestamp
                        : start + Math.max(0L, words.get(s).duration);
                if (end <= start) {
                    end = start + 1L;
                }
                if (posMs < start) {
                    sweepWord = s;
                    sweepFrac = 0;
                    break;
                }
                if (posMs < end) {
                    sweepWord = s;
                    sweepFrac = (posMs - start) / (double) (end - start);
                    break;
                }
            }
        }
        int sweepRow = sweepWord >= 0 && sweepWord < n ? wordRow[sweepWord] : -1;
        double headX = sweepWord >= 0 && sweepWord < n
                ? wordX[sweepWord] + ww[sweepWord] * sweepFrac : 0;
        double fadeW = SWEEP_FADE_PX * 1.6;
        double maskDark = 1.0 - (1.0 - DARK_MASK_ALPHA) * activeK;

        // 逐字上浮：目标是这个词已经唱了多久对应的弹簧解，往上追得快、回落得慢。
        float[] liftState = wordLifts(line, n);
        for (int i = 0; i < n; i++) {
            double target = -LIFT_PEAK_PX
                    * liftSpringK((posMs - words.get(i).timestamp) / 1000.0) * activeK;
            double k = target < liftState[i] ? LIFT_RISE_K : LIFT_FALL_K;
            liftState[i] += (float) ((target - liftState[i]) * k);
            wordLift[i] = liftState[i];
        }

        int litArgb = (alpha255(baseAlpha) << 24) | (highRgb & 0xFFFFFF);
        int darkArgb = (alpha255(baseAlpha * maskDark) << 24) | (baseRgb & 0xFFFFFF);
        Shader sweep = null;
        try {
            for (int i = 0; i < n; i++) {
                LyricLine.Word word = words.get(i);
                String text = word == null ? null : word.word;
                if (text == null || text.isEmpty()) {
                    continue;
                }
                double rowY = y + wordRow[i] * mainStep + wordLift[i];
                int state = sweepState(i, n, sweepWord, sweepRow, headX, fadeW, ww);
                if (state == SWEEPING && sweep == null) {
                    sweep = Shader.makeLinearGradient((float) (headX - fadeW), 0f, (float) headX, 0f,
                            new int[] { litArgb, darkArgb });
                }
                double emphasis = emphasizeAmount(word, i, n);
                if (emphasis > .001) {
                    drawBulgedWord(font, text, wordX[i], rowY, posMs, word, emphasis, state,
                            litArgb, darkArgb, sweep);
                } else if (state == SWEEPING) {
                    font.drawStringShaded(text, wordX[i], rowY, sweep);
                } else {
                    font.drawString(text, wordX[i], rowY, state == LIT ? litArgb : darkArgb);
                }
            }
        } finally {
            if (sweep != null) {
                sweep.close();
            }
        }
    }

    private int sweepState(int i, int n, int sweepWord, int sweepRow, double headX,
                           double fadeW, float[] ww) {
        if (sweepWord >= n) {
            return LIT;
        }
        if (sweepWord < 0) {
            return DARK;
        }
        if (wordRow[i] < sweepRow) {
            return LIT;
        }
        if (wordRow[i] > sweepRow) {
            return DARK;
        }
        if (wordX[i] + ww[i] <= headX - fadeW) {
            return LIT;
        }
        if (wordX[i] >= headX) {
            return DARK;
        }
        return SWEEPING;
    }
    /**
     * 带字母膨胀的词：逐字形绘制，每个字形绕自己的中心放大并微微外推。
     *
     * <p>只有过了 {@link #emphasizeAmount} 那道门槛的词才走这条路——它比整词一次绘制贵得多。
     * 步进用「前缀宽度之差」而不是逐字宽度相加：后者会丢掉字距并沿词累积偏移，字形会和
     * 渐变前沿错开。</p>
     */
    private void drawBulgedWord(CFontRenderer font, String text, double x, double y, long posMs,
                                LyricLine.Word word, double amount, int state,
                                int litArgb, int darkArgb, Shader sweep) {
        int len = text.length();
        double em = font.sizePx;
        double glyphH = font.getHeight();
        double prefix = 0.0;
        int offset = 0;
        int index = 0;
        while (offset < len) {
            int next = text.offsetByCodePoints(offset, 1);
            double nextPrefix = font.getStringWidthD(text.substring(0, next));
            double cw = Math.max(0.0, nextPrefix - prefix);
            double ek = emphasizeK(word.timestamp, word.duration, index, len, posMs);
            double scale = 1.0 + .1 * ek * amount;
            double offX = -ek * .03 * amount * (len / 2.0 - index) * em;
            double offY = -ek * .025 * amount * em;
            double gx = x + prefix + offX;
            double gy = y + offY;
            String glyph = text.substring(offset, next);
            api.getGLStateManager().pushMatrix();
            scaleAtPos(gx + cw * .5, gy + glyphH * .5, scale);
            try {
                if (state == SWEEPING) {
                    font.drawStringShaded(glyph, gx, gy, sweep);
                } else {
                    font.drawString(glyph, gx, gy, state == LIT ? litArgb : darkArgb);
                }
            } finally {
                api.getGLStateManager().popMatrix();
            }
            prefix = nextPrefix;
            offset = next;
            index++;
        }
    }

    // ── 排版 ──────────────────────────────────────────────────────────────────

    /**
     * 一行的折行结果，按当前几何缓存。
     *
     * <p>折行阈值是文字列宽的 70% 而不是 100%：mp 刻意留出右侧空白，让「这一行还没结束」
     * 一眼就能看出来，而且当前行放大时不会顶到卡片边缘。</p>
     */
    private LineLayout layoutFor(LyricLine line, double wrapW, double rowH, double mainStep) {
        LineLayout cached = layoutCache.get(line);
        if (cached != null) {
            return cached;
        }
        LineLayout lay = new LineLayout();
        if (hasTimedWords(line)) {
            float[] ww = wordWidths(line, font);
            int count = ww.length;
            int[] starts = new int[count + 2];
            int used = 0;
            starts[used++] = 0;
            double acc = 0;
            for (int i = 0; i < count; i++) {
                if (acc > 0 && acc + ww[i] > wrapW) {
                    starts[used++] = i;
                    acc = 0;
                }
                acc += ww[i];
            }
            starts[used++] = count;
            lay.rowStart = java.util.Arrays.copyOf(starts, used);
            lay.mainRows = Math.max(1, used - 1);
        } else {
            String text = line.getLyric();
            lay.plainRows = text == null || text.isEmpty() ? EMPTY_ROWS : font.fitWidth(text, wrapW);
            lay.mainRows = Math.max(1, lay.plainRows.length);
        }
        lay.height = rowH + (lay.mainRows - 1) * mainStep;
        lay.mainCenter = rowH * .5 + (lay.mainRows - 1) * mainStep * .5;
        layoutCache.put(line, lay);
        return lay;
    }

    private static boolean hasTimedWords(LyricLine line) {
        return !CloudMusic.haveNoWords && line.words.size() > 1 && line.hasTimedWords();
    }

    private float[] wordWidths(LyricLine line, CFontRenderer font) {
        List<LyricLine.Word> words = line.words;
        int n = words.size();
        float[] cached = wordWidthCache.get(line);
        if (cached != null && cached.length == n) {
            return cached;
        }
        float[] widths = new float[n];
        for (int i = 0; i < n; i++) {
            LyricLine.Word word = words.get(i);
            widths[i] = word == null || word.word == null ? 0f
                    : (float) font.getStringWidthD(word.word);
        }
        wordWidthCache.put(line, widths);
        return widths;
    }

    /** 逐字上浮的位移要跨帧保留，所以按行存一份，不能每帧新建。 */
    private float[] wordLifts(LyricLine line, int n) {
        float[] cached = wordLiftCache.get(line);
        if (cached != null && cached.length == n) {
            return cached;
        }
        float[] fresh = new float[n];
        wordLiftCache.put(line, fresh);
        return fresh;
    }

    private void ensureWordArrays(int n) {
        if (wordX.length >= n) {
            return;
        }
        wordX = new double[n];
        wordRow = new int[n];
        wordLift = new double[n];
    }
    // ── 时间轴与曲线 ──────────────────────────────────────────────────────────

    private static long endMs(LyricLine line) {
        return line.timestamp + Math.max(0L, line.duration);
    }

    /**
     * 这一行的「活动度」0..1：提前 600ms 淡入、唱完 250ms 淡出。
     *
     * <p>淡入的起点被上一行的结束时间挡住，否则上一行还在唱，下一行就已经亮起来了。</p>
     */
    private static double computeActiveK(LyricLine line, long posMs, long prevEnd) {
        long start = line.timestamp;
        long end = endMs(line);
        if (end <= start) {
            return 0;
        }
        long fadeIn = Math.max(start - ACTIVE_FADE_IN_MS, prevEnd);
        long fadeOut = end + ACTIVE_FADE_OUT_MS;
        if (posMs < fadeIn || posMs >= fadeOut) {
            return 0;
        }
        if (posMs < start) {
            long lead = start - fadeIn;
            return lead <= 0 ? 1.0 : (posMs - fadeIn) / (double) lead;
        }
        if (posMs < end) {
            return 1.0;
        }
        return 1.0 - (posMs - end) / (double) ACTIVE_FADE_OUT_MS;
    }

    /**
     * 换行时的放松系数 0..1（0 = 硬基线，1 = 最软最弹）。
     *
     * <p>前一行留下的空隙越大、它的字词越稀（平均每词占的时间越长），弹簧就越软；一句接一句
     * 咬得很紧时保持硬基线。这一项让「间奏后进来的第一句」和「连唱的下一句」滑动手感不同。</p>
     */
    private static double lineChangeRelax(List<LyricLine> lyrics, int idx) {
        if (idx <= 0 || idx >= lyrics.size()) {
            return 0;
        }
        LyricLine prev = lyrics.get(idx - 1);
        long gap = Math.max(0L, lyrics.get(idx).timestamp - endMs(prev));
        double gapK = Math.min(1.0, gap / 2000.0);
        int wordCount = Math.max(1, prev.words.size());
        long prevDuration = Math.max(1L, endMs(prev) - prev.timestamp);
        double durationK = Math.min(1.0, prevDuration / (double) wordCount / 600.0);
        return clamp01(.6 * gapK + .4 * durationK);
    }

    /**
     * 上浮位移的归一化进度：Specs liftSpringTimingParameters 的阶跃响应。
     *
     * <p>近临界阻尼（zeta 约 0.935），所以几乎不过冲；2.5 秒之后已经完全到位，直接返回 1
     * 省掉两个三角函数。</p>
     */
    private static double liftSpringK(double seconds) {
        if (seconds <= 0) {
            return 0;
        }
        if (seconds > 2.5) {
            return 1.0;
        }
        double zw = LIFT_ZETA * LIFT_OMEGA0;
        double wd = LIFT_OMEGA0 * Math.sqrt(1.0 - LIFT_ZETA * LIFT_ZETA);
        double envelope = Math.exp(-zw * seconds);
        return Math.max(0.0,
                1.0 - envelope * (Math.cos(wd * seconds) + zw / wd * Math.sin(wd * seconds)));
    }

    /**
     * 字母膨胀的幅度：峰值缩放是 {@code 1 + 0.1 * 幅度}。
     *
     * <p>三道门槛都照 mp：只有一行的<b>首词或末词</b>、停留<b>不短于 1.5 秒</b>、而且是<b>拉丁词</b>
     * 才做。中日韩一个词就是一个字形，没有可以铺开的字母串，膨胀只会看成抖动。末词额外乘 1.6，
     * 因为句尾拖长音时那个词是听觉重心。</p>
     */
    private static double emphasizeAmount(LyricLine.Word word, int index, int count) {
        if (!HudConfig.osdKaraokeEmphasisEnabled) {
            return 0;
        }
        if (index != 0 && index != count - 1) {
            return 0;
        }
        if (word.duration < EMPHASIZE_MIN_DWELL_MS || !isLatinWord(word.word)) {
            return 0;
        }
        double duration = Math.max(1000.0, word.duration);
        double amount = duration / 2000.0;
        amount = amount > 1.0 ? Math.sqrt(amount) : amount * amount * amount;
        amount *= .6;
        if (index == count - 1) {
            amount *= 1.6;
        }
        return Math.min(1.2, amount);
    }

    /** 第 {@code index} 个字母的膨胀进度，从左往右依次错开。 */
    private static double emphasizeK(long startMs, long duration, int index, int length, long posMs) {
        double effective = Math.max(1000.0, duration);
        double delay = effective / 2.5 / Math.max(1, length) * index;
        return empEasing(clamp01((posMs - startMs - delay) / effective));
    }

    /** AMLL 的膨胀曲线：到中点冲到 1，再回到 0。 */
    private static double empEasing(double x) {
        if (x < .5) {
            return cssBezier(clamp01(x / .5), .2, .4, .58, 1.0);
        }
        return 1.0 - cssBezier(clamp01((x - .5) / .5), .3, .0, .58, 1.0);
    }

    /** CSS cubic-bezier 求值：牛顿迭代解出参数 s，再取 y。八次足够收敛到肉眼无差。 */
    private static double cssBezier(double p, double x1, double y1, double x2, double y2) {
        if (p <= 0) {
            return 0;
        }
        if (p >= 1) {
            return 1;
        }
        double s = p;
        for (int i = 0; i < 8; i++) {
            double bx = bezier1d(s, x1, x2);
            double d = bezierDeriv1d(s, x1, x2);
            if (Math.abs(d) < 1e-6) {
                break;
            }
            s = Math.max(0.0, Math.min(1.0, s - (bx - p) / d));
        }
        return bezier1d(s, y1, y2);
    }

    private static double bezier1d(double s, double c1, double c2) {
        double u = 1 - s;
        return 3 * u * u * s * c1 + 3 * u * s * s * c2 + s * s * s;
    }

    private static double bezierDeriv1d(double s, double c1, double c2) {
        double u = 1 - s;
        return 3 * u * u * c1 + 6 * u * s * (c2 - c1) + 3 * s * s * (1 - c2);
    }

    /** 纯拉丁词判定。撇号、连字符与词内空格算在内，出现任何别的字符就不算。 */
    private static boolean isLatinWord(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        boolean hasLetter = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
                hasLetter = true;
                continue;
            }
            if (c == '\'' || c == '-' || c == ' ' || c == '.' || c == ',') {
                continue;
            }
            return false;
        }
        return hasLetter;
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    private static int clampInt(int v, int min, int max) {
        return v < min ? min : v > max ? max : v;
    }

    private static int alpha255(double alpha) {
        return (int) Math.round(255.0 * clamp01(alpha));
    }
}