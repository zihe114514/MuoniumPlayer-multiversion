package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.compat.gl.GL11;
import com.muoniumplayer.core.MuoniumPlayerExtension;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.AudioPlayer;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.rendering.*;
import com.muoniumplayer.core.rendering.Image;
import com.muoniumplayer.core.rendering.animation.Easing;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.font.TextGlow;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.render.skija.SkijaEffects;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.core.rendering.texture.ITextureObject;
import com.muoniumplayer.core.rendering.ui.widgets.IconWidget;
import com.muoniumplayer.core.settings.ClientSettings;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.cursor.CursorUtils;
import com.muoniumplayer.core.utils.math.Mth;
import com.muoniumplayer.core.utils.network.HttpUtils;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.core.utils.timing.Timer;
import com.muoniumplayer.core.widget.impl.MusicLyricsWidget;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * @author MuoniumPlayer Contributors
 * Date: 2025/10/17 21:56
 */
public class MusicLyricsPanel implements SharedRenderingConstants, SharedConstants {
    static double scrollOffset, scrollTarget;

    float musicBgAlpha = 1.0f;
    static ITextureObject prevBg = null, prevCover;
    static Music prevMusic = null;

    float alpha = 0f;
    boolean closing = false;
    /**
     * 退场时低于这个不透明度就不再绘制整层内容。
     *
     * <p>原来是 0.09：那时 {@code alpha} 走指数逼近、永远到不了 0，只能靠一个「肉眼分辨不出」的
     * 阈值提前收工。现在 {@code alpha} 由 {@link CoverHeroTransition} 的时钟给，退场末段本身就
     * 严格等于 0，于是这个常数退化成「已经全透明了就别再画」的守卫，贴着 0 取即可。
     *
     * <p>它也不再决定「什么时候算关掉」—— 那件事归 {@link #shouldClose()}，由转场时钟说话：
     * 接力封面要飞回控制栏，比这一层的内容消失得晚。</p>
     */
    private static final float CLOSE_ALPHA_EPSILON = 0.002f;


    Timer scrollOffsetResetTimer = new Timer();

    /**
     * 封面在「没出声」时缩到最小。问 {@link CloudMusic#isAudibleNow()} 而不是
     * {@code player == null || player.isPausing()}：系统播放器镜像态下我们的音频引擎
     * 根本没参与，按旧写法封面会一直停在最小档。
     */
    double coverSize = CloudMusic.isAudibleNow() ? this.getCoverSizeMax() : this.getCoverSizeMin();
    float coverAlpha = 1f;

    boolean progressBarDragging = false;
    double progressBarProgressOverride = 0;
    double progressBarHeight = 8, volumeBarHeight = 8;
    private final VolumeControl volumeControl = new VolumeControl();

    boolean prevMouse = false;

    /** 左上角「A-Z」逐字特效快捷面板；只影响全屏歌词页的两项配置，实时预览。 */
    private final LyricStyleQuickPanel styleQuickPanel = new LyricStyleQuickPanel();
    /** 「隐现」背景的音频响应状态；旧模糊封面背景不会触碰它。 */
    private final LatentLyricBackground latentBackground = new LatentLyricBackground();
    /**
     * 「逐行滚动」的滚动动效：换行用什么弹簧、每行晚多久出发、seek 怎么收尾都在里面。
     * 只有经典样式走它，别的样式各自有自己的一套运动。
     */
    private final LyricScrollMotion scrollMotion = new LyricScrollMotion();
    /** 每帧算出的文档坐标，按行数复用；这是渲染热路径，不值得每帧新建一个数组。 */
    private double[] lyricDocTop = new double[0];
    /** qplayer 组模型：首行、末行（排他结束约定在此为包含）。 */
    private int[] qplayerGroupStart = new int[0];
    private int[] qplayerGroupEnd = new int[0];
    private int[] qplayerLineToGroup = new int[0];
    private double[] qplayerInterludeBefore = new double[0];
    private int qplayerGroupCount;
    private boolean qplayerInInterlude;
    private int qplayerInterludeNextGroup = -1;
    private long qplayerInterludeStartMs;
    /** qplayer 缓存塑形后的行；这是我们逐字放置的等价缓存。 */
    private final java.util.IdentityHashMap<LyricLine, WordPlacement> qplayerPlacementCache =
            new java.util.IdentityHashMap<>();
    private final java.util.IdentityHashMap<LyricLine, double[]> qplayerPlacementGeometry =
            new java.util.IdentityHashMap<>();
    /**
     * 除「逐行滚动」以外的各套样式的渲染器，下标与 {@link FullscreenLyricStyle} 的序号一一对应
     * （{@code CLASSIC} 那一位固定为 {@code null}，它走下面经典样式的整套流程）。
     *
     * <p>为什么全部提前 new 好：这些对象只持有布局缓存与几个动画量，构造代价可以忽略，
     * 而按需创建就得在渲染热路径上做判空 + 赋值，还会在切样式的那一帧多一次 GC 压力。</p>
     */
    private final FullscreenLyricRenderer[] styleRenderers = buildStyleRenderers();

    private static FullscreenLyricRenderer[] buildStyleRenderers() {
        FullscreenLyricRenderer[] renderers =
                new FullscreenLyricRenderer[FullscreenLyricStyle.values().length];
        renderers[FullscreenLyricStyle.CADENZA.ordinal()] = new CadenzaLyricRenderer();
        renderers[FullscreenLyricStyle.FUME.ordinal()] = new FumeLyricRenderer();
        renderers[FullscreenLyricStyle.LUMINOUS.ordinal()] = new LuminousLyricRenderer();
        renderers[FullscreenLyricStyle.PARTITA.ordinal()] = new PartitaLyricRenderer();
        renderers[FullscreenLyricStyle.CAPPELLA.ordinal()] = new CappellaLyricRenderer();
        renderers[FullscreenLyricStyle.TILT.ordinal()] = new TiltLyricRenderer();
        renderers[FullscreenLyricStyle.DIORAMA.ordinal()] = new DioramaLyricRenderer();
        renderers[FullscreenLyricStyle.PENDOLO.ordinal()] = new PendoloLyricRenderer();
        renderers[FullscreenLyricStyle.APPLE.ordinal()] = new AppleLyricRenderer();
        renderers[FullscreenLyricStyle.LUMINOUS_CLASSIC.ordinal()] = new LuminousClassicLyricRenderer();
        return renderers;
    }

    /** 当前样式对应的渲染器；{@code null} 表示走经典的滚动歌词。 */
    private FullscreenLyricRenderer currentStyleRenderer() {
        int index = FullscreenLyricStyle.current().ordinal();
        if (index < 0 || index >= styleRenderers.length) return null;
        return styleRenderers[index];
    }

    /**
     * 封面下方的曲目信息块（曲名 / 副标题 / 标签 / 艺人 / 专辑）。
     *
     * <p>连同曲名一起搬进去了：这五行的纵向让位要一起算，拆在两个地方就没法保证"收行之后
     * 仍然不压到进度条"。</p>
     */
    private final NowPlayingInfo nowPlaying = new NowPlayingInfo();
    IconWidget playPauseButton = new IconWidget("G", FontManager.music40, 0, 0, 24, 24);
    IconWidget prev = new IconWidget("E", FontManager.music40, 0, 0, 32, 32);
    IconWidget next = new IconWidget("H", FontManager.music40, 0, 0, 32, 32);


    /**
     * 传送带（进度条 / 时间 / 三枚控制键 / 音量条）的最小垂直区间。
     *
     * <p>三键是在进度条与音量条正中间的，区间再小控制键就会压到时间那一行上：
     * 8 像素进度条 + 12 像素时间行，再加三键自身 32 像素、居中后上下各占 16。
     * 平时按面板高度取 17.5%，为的是在大面板上保持和改版前一样的疏密。</p>
     */
    private static final double TRANSPORT_ZONE_MIN = 88.0;

    /** 信息块底边到进度条的呼吸量。取 8 是为了和改版前"艺人行到进度条"的间距一模一样。 */
    private static final double INFO_BAR_GAP = 8.0;

    private double lastViewportLyricWidth = -1;
    private double lastViewportAnchorY = Double.NaN;

    /**
     * 所有歌词层的唯一权威视口。它每帧从
     * 播放器的实时边界推导，因此文本、KTV 合成和模糊都跟随缩放。
     */
    private static final class LyricViewport {
        final double left;
        final double top;
        final double width;
        final double height;

        LyricViewport(double left, double top, double width, double height) {
            this.left = left;
            this.top = top;
            this.width = Math.max(0.0, width);
            this.height = Math.max(0.0, height);
        }

        double right() {
            return left + width;
        }

        double bottom() {
            return top + height;
        }
    }

    private final Music music;
    public MusicLyricsPanel(Music music) {
        this.music = music;
        // 兜底启动进场时钟。本层的 alpha 现在完全由转场给，少了这一句、又没走小封面那条入口的话，
        // 面板会永远停在完全透明。
        CoverHeroTransition.ensureOpen();
        updateLyricPositionsImmediate(getCurrentLyricViewportWidth());
    }

    public static void resetProgress(float progress) {
        CloudMusic.updateCurrentLyric(progress);
        CloudMusic.resetLyricPositionUpdate();
        updateLyricPositionsImmediate(getCurrentLyricViewportWidth());
    }

    public static double getLyricWidthFactor() {
        return .48;
    }

    /**
     * 在动画歌词层淡出期间把它保持在播放器内。
     *
     * 旧的关闭动画用最大 1.10 缩放，使
     * 背景、模糊和字形四边形超出动态尺寸的播放器。
     * 全不透明时内容仍用确切的播放器尺寸；在
     * 过渡期间它只收缩，因此缩放播放器不会暴露一个
     * 更大的第二歌词视口。
     */
    private static double getContentScale(float alpha) {
        return LyricsPanelGeometry.contentScale(alpha);
    }

    /** 平滑 KTV 扫光，而不把它与实际音频位置解耦。 */
    private static double smoothKaraokeProgress(double value) {
        return LyricsPanelGeometry.smoothKaraokeProgress(value);
    }

    /** 单个字符在短暂的级联波中渐入激活态。 */
    private static double getCharacterKaraokeProgress(double wordProgress, int characterIndex) {
        return LyricsPanelGeometry.characterKaraokeProgress(wordProgress, characterIndex);
    }

    /** 归一化后的逐字发光上限；为 0 表示整条发光链路完全关闭。 */
    /**
     * 逐字上浮的最短动画时长（毫秒）。照搬 modernMP {@code LIFT_FLOAT_MIN_MS}，源头是 AMLL
     * {@code initFloatAnimation} 的 {@code max(1000ms, wordDuration)}。
     *
     * <p>这个下限就是上浮丝滑与否的全部关键。原先每个字的上浮跟着"字符在词内均分出的时长"
     * 走，一个 400ms 的双字词里每个字只有 200ms；而播放位置来自音频时钟，它按解码缓冲大约
     * 90ms 才前进一次，于是 200ms 的动画全程只有两三个不同的取值，再叠一层帧间指数逼近就成了
     * "猛推一下、缓一下、再推一下"——那正是肉眼看到的卡顿。拉到 1000ms 之后，同样 90ms 的时钟
     * 粒度只对应 0.17 像素的位移，纯时间函数也不需要追赶，动画才是连续的。</p>
     */
    private static final double LIFT_FLOAT_MIN_MS = 1000.0;
    /** 逐字上浮的峰值位移（像素）。与 modernMP 的 {@code 36px × 0.05em} 同量级。 */
    private static final double LIFT_FLOAT_PX = 1.85;

    /**
     * 一个字的上浮位移。纯时间函数：只由播放位置决定，不含任何帧间状态，所以掉帧、卡顿、
     * 甚至跳帧都不会让它偏离该有的形状；到顶后保持不回落（AMLL 的 fill-forwards）。
     */
    private static double characterLift(LyricLine.Word word, int characterIndex,
                                        int characterCount, double songProgress) {
        if (characterCount <= 0) return 0.0;
        double duration = Math.max(word.duration, 0L);
        // 字与字之间仍按词内均分错开出发，逐字错落的观感不变；变的只有每个字自己走多久。
        double startMs = word.timestamp + duration * (characterIndex / (double) characterCount);
        double effectiveDuration = Math.max(LIFT_FLOAT_MIN_MS, duration);
        return LIFT_FLOAT_PX * easeOutCssBezier((songProgress - startMs) / effectiveDuration);
    }

    /**
     * CSS {@code ease-out} = {@code cubic-bezier(0, 0, 0.58, 1)}，AMLL 用在逐字上浮上的那条曲线。
     * 先用牛顿法从 x 反解贝塞尔参数 s，再取它的 y。
     */
    private static double easeOutCssBezier(double x) {
        if (x <= 0.0) return 0.0;
        if (x >= 1.0) return 1.0;
        final double x2 = 0.58, y2 = 1.0;
        double s = x;
        for (int iteration = 0; iteration < 8; iteration++) {
            double bx = cubicBezier1d(s, 0.0, x2);
            double derivative = cubicBezierDeriv1d(s, 0.0, x2);
            if (Math.abs(derivative) < 1e-6) break;
            s -= (bx - x) / derivative;
            s = Math.max(0.0, Math.min(1.0, s));
        }
        return cubicBezier1d(s, 0.0, y2);
    }

    /** 三次贝塞尔的一个分量，两端固定为 P0=0、P3=1。 */
    private static double cubicBezier1d(double s, double c1, double c2) {
        double u = 1 - s;
        return 3 * u * u * s * c1 + 3 * u * s * s * c2 + s * s * s;
    }

    private static double cubicBezierDeriv1d(double s, double c1, double c2) {
        double u = 1 - s;
        return 3 * u * u * c1 + 6 * u * s * (c2 - c1) + 3 * s * s * (1 - c2);
    }

    private static double karaokeGlowStrength() {
        if (!HudConfig.currentLyricEffectsEnabled) {
            return 0.0;
        }
        return Math.max(0f, Math.min(1f, HudConfig.fullscreenGlowStrength));
    }

    /**
     * 逐字发光在 KTV 纹理四周需要的留白（逻辑像素，整数）。
     *
     * <p>发光是围绕字形向外扩的，如果 FBO 不留白就会被纹理边界切成一个方块。留白必须是整数，
     * 这样 {@code fbWidth / 2}、{@code fbHeight / 2} 仍然是整数，合成四边形与纹理保持一一对应、
     * 不会引入额外的重采样。发光关闭时返回 0，此时整条链路与加发光之前完全等价。</p>
     */
    private static int karaokeGlowPadding() {
        if (karaokeGlowStrength() <= .004) {
            return 0;
        }
        // 留白不够，图层边界就会把光晕切成方块——换成真高斯之后最容易踩的就是这个坑：
        // sigma 比原先那个偏移半径大得多，原来写死的 6px 远远不够。
        return TextGlow.padding(FontManager.pf65bold, HudConfig.lyricGlowSpread);
    }

    /**
     * 一个字的发光，亮度只取决于"这个字已经唱了多久"。
     *
     * <p>{@code progress} 是该字自己的演唱进度（0 = 还没唱到，1 = 唱完），所以同一个字词里
     * 靠前的字先亮、靠后的字后亮，整体是一道跟着人声推进的光。上限由
     * {@link HudConfig#fullscreenGlowStrength} 控制，光晕的形状与混合方式见 {@link TextGlow}。</p>
     */
    private void drawKaraokeCharacterGlow(String text, double x, double y,
                                          double progress, float alpha) {
        if (FullscreenLyricStage.transitionFastPath()) return;
        TextGlow.draw(FontManager.pf65bold, text, x, y, 0xFFFFFF, alpha,
                karaokeGlowStrength() * progress, HudConfig.lyricGlowSpread);
    }

    private static double getLyricLineSpacing() {
        return LyricsPanelGeometry.lyricLineSpacing();
    }

    private static double lyricFraction() {
        return LyricsPanelGeometry.lyricAnchorFraction();
    }

    /**
     * 歌词列刻意使用当前播放器边界，而非全局
     * 屏幕比例。右侧和垂直内边距让动效字形、其光晕和
     * 模糊核在每个配置的播放器缩放下都保持在实时播放器窗口内。
     */
    private static LyricViewport createLyricViewport(double posX, double posY, double width, double height) {
        LyricsPanelGeometry.Viewport viewport = LyricsPanelGeometry.createViewport(posX, posY, width, height,
                NCMScreen.getInstance().getPlayerBorderThickness());
        return new LyricViewport(viewport.left, viewport.top, viewport.width, viewport.height);
    }

    public static void updateLyricPositionsImmediate(double width) {
        layoutLyricPositionsImmediately(width, getCurrentViewportAnchorY(), CloudMusic.currentLyric);
    }

    public static void updateLyricPositionsImmediate(double width, double playbackProgress) {
        layoutLyricPositionsImmediately(width, getCurrentViewportAnchorY(), CloudMusic.findCurrentLyric(playbackProgress));
    }

    /**
     * 拖动进度条时用的硬吸附。阅读位置必须由调用方按<b>本帧实际绘制的面板矩形</b>给出，而不是
     * 走 {@link #getCurrentViewportAnchorY()}：那个读的是 {@code NCMScreen} 算出来的目标矩形，
     * 与面板 widget 的动画值不是同一个数。两者差一点，这里按 A 硬吸附、每帧滚动按 B 拉回，
     * 每帧一来一回就是肉眼可见的抖动。
     */
    private static void updateLyricPositionsImmediate(double width, double anchorY, double playbackProgress) {
        layoutLyricPositionsImmediately(width, anchorY, CloudMusic.findCurrentLyric(playbackProgress));
    }

    private static double getCurrentLyricViewportWidth() {
        NCMScreen screen = NCMScreen.getInstance();
        return createLyricViewport(screen.getPanelX(), screen.getPanelY(),
                screen.getPanelWidth(), screen.getPanelHeight()).width;
    }

    private static double getCurrentViewportAnchorY() {
        NCMScreen screen = NCMScreen.getInstance();
        return screen.getPanelY() + screen.getPanelHeight() * lyricFraction();
    }

    /**
     * 围绕一条锚点行重建完整歌词布局。seek 可一次改变活动行
     * 多个条目，因此每行必须使用同一锚点，而非
     * 留下新旧播放位置坐标的混合。
     */
    private static void layoutLyricPositionsImmediately(double width, double anchorY, LyricLine anchorLyric) {
        if (anchorLyric == null) return;

        synchronized (CloudMusic.lyrics) {
            int anchorIndex = CloudMusic.lyrics.indexOf(anchorLyric);
            if (anchorIndex < 0 || anchorIndex >= CloudMusic.lyrics.size()) return;

            // 高度影响其后所有坐标。在分配
            //位置之前先计算高度，使折行/翻译行不会与其邻居重叠。
            for (LyricLine lyric : CloudMusic.lyrics) {
                // 对唱行按自己那一列的宽度算高度，否则折行数与渲染时不一致，行与行会叠在一起。
                lyric.computeHeight(qplayerWrapWidth(LyricDuetGroups.columnWidth(width, lyric.duetSlot)));
            }

            // 对唱作为一个块锚定：其首行领先，块被上移
            //额外行数的一半，使这对歌手横跨锚点，而非把
            //第二位歌手推出阅读位置。
            int groupEnd = LyricDuetGroups.groupEnd(CloudMusic.lyrics, anchorIndex);
            anchorIndex = LyricDuetGroups.groupStart(CloudMusic.lyrics, anchorIndex);
            anchorY += groupAnchorLift(anchorIndex, groupEnd);

            setLyricPosition(CloudMusic.lyrics.get(anchorIndex), anchorY);

            double offsetY = anchorY;
            for (int i = anchorIndex - 1; i >= 0; i--) {
                LyricLine lyric = CloudMusic.lyrics.get(i);
                offsetY -= lyric.height + getLyricLineSpacing();
                setLyricPosition(lyric, offsetY);
            }

            offsetY = anchorY + CloudMusic.lyrics.get(anchorIndex).height + getLyricLineSpacing();
            for (int i = anchorIndex + 1; i < CloudMusic.lyrics.size(); i++) {
                LyricLine lyric = CloudMusic.lyrics.get(i);
                setLyricPosition(lyric, offsetY);
                offsetY += lyric.height + getLyricLineSpacing();
            }
        }
    }

    /**
     * 把一个对唱组整体抬起的偏移量：组内除第一行以外的行占掉多少高度，就抬起其一半，让整组
     * 以锚点为中心，而不是把第二位歌手挤到阅读位置之外。独唱行返回 0。
     */
    private static double groupAnchorLift(int startIndex, int endIndex) {
        double extra = 0.0;
        for (int i = startIndex + 1; i <= endIndex && i < CloudMusic.lyrics.size(); i++) {
            extra += CloudMusic.lyrics.get(i).height + getLyricLineSpacing();
        }
        return -extra * .5;
    }

    /**
     * 一行歌词里每个字词的落点：{@code x} 是绝对横坐标，{@code row} 是它落在这一行的第几个折行。
     * 预先整行算好而不是边画边推进，是右对齐（对唱右列）能成立的前提。
     */
    private static final class WordPlacement {
        final double[] x;
        final int[] row;

        private WordPlacement(double[] x, int[] row) {
            this.x = x;
            this.row = row;
        }
    }

    /**
     * 贪心折行并算出每个字词的落点。
     *
     * <p>折行规则与改动前完全一致（累计宽度加上下一个字词就超过列宽时换行），所以独唱行的
     * 排版结果不变；差别只在于右对齐的行会整体推到列右边界。</p>
     */
    private static WordPlacement layoutWords(List<LyricLine.Word> words, double columnLeft,
                                             double columnWidth, boolean rightAligned) {
        int count = words.size();
        double[] widths = new double[count];
        int[] rows = new int[count];
        double[] rowWidths = new double[count + 1];
        int row = 0;
        double cursor = 0.0;
        for (int i = 0; i < count; i++) {
            LyricLine.Word word = words.get(i);
            widths[i] = FontManager.pf65bold.getStringWidthD(word.word);
            if (cursor > 0.0 && cursor + widths[i] > columnWidth) {
                rowWidths[row] = cursor;
                row++;
                cursor = 0.0;
            }
            rows[i] = row;
            cursor += widths[i];
        }
        rowWidths[row] = cursor;

        double[] xs = new double[count];
        double[] rowCursor = new double[row + 1];
        for (int i = 0; i <= row; i++) {
            // 右列按行宽右对齐；左列（含独唱）一律从列左边界开始，和改动前逐字推进的结果一致。
            rowCursor[i] = rightAligned
                    ? columnLeft + columnWidth - Math.min(rowWidths[i], columnWidth)
                    : columnLeft;
        }
        for (int i = 0; i < count; i++) {
            xs[i] = rowCursor[rows[i]];
            rowCursor[rows[i]] += widths[i];
        }
        return new WordPlacement(xs, rows);
    }

    private static void setLyricPosition(LyricLine lyric, double positionY) {
        lyric.posY = positionY;
        // setPosition 也清除弹簧速度/排队目标。seek 需要这样，
        //否则一行可能继续从旧时间线插值。
        lyric.spring.setPosition(positionY);
    }

    private static void resetLyricStatus() {
        CloudMusic.resetLyricStatus();
    }

    public void onInit() {
        resetLyricStatus();
        qplayerPlacementCache.clear();
        qplayerPlacementGeometry.clear();
        // 上一首的锚点与布局指纹留着的话，新歌第一帧会被当成一次"布局变了"，整屏歌词飞一次。
        scrollMotion.reset();
        // 换歌重开歌词页时清掉各样式的上一行状态，否则会看到上一首的内容退场一次。
        for (int i = 0; i < styleRenderers.length; i++) {
            if (styleRenderers[i] != null) styleRenderers[i].reset();
        }
    }

    public void close() {
        closing = true;
        CoverHeroTransition.beginClose();
    }

    /**
     * 由转场时钟宣布结束，而不是等本层 alpha 跌破阈值：内容消失之后接力封面还要飞回控制栏，
     * 提前把面板置空会让封面在半空中断掉。
     */
    public boolean shouldClose() {
        return closing && CoverHeroTransition.isFinished();
    }

    public void onRender(double mouseX, double mouseY, double posX, double posY, double width, double height, int dWheel) {

        if (prevMouse && !Mouse.isButtonDown(0)) prevMouse = false;

        // 本层不透明度不再自己插值，而是读转场时钟：整条「播放器主页 到 全屏歌词页」的进退场由
        // CoverHeroTransition 统一编排（页面底色、接力封面、本层内容、左栏、歌词各有时间窗），
        // 这里自己算一份就会和接力封面的落地时刻错开，交接那一帧看得出跳变。
        alpha = (float) CoverHeroTransition.contentAlpha();
        // 进场前 16% 与退场末段本层都是全透明的。此前只有退场会跳过，进场仍会完整画一遍
        // 背景、控制栏与歌词；低配机上这就是开头连续掉帧的来源之一。
        if (alpha <= CLOSE_ALPHA_EPSILON) return;

        // 面板尺寸动画过程中按真实视口重排。阈值可避免亚像素变化导致每帧重建全部歌词。
        LyricViewport lyricViewport = createLyricViewport(posX, posY, width, height);
        double viewportLyricWidth = lyricViewport.width;
        double viewportAnchorY = posY + height * lyricFraction();
        if (Math.abs(viewportLyricWidth - this.lastViewportLyricWidth) >= 2.0
                || Double.isNaN(this.lastViewportAnchorY)
                || Math.abs(viewportAnchorY - this.lastViewportAnchorY) >= 1.0) {
            layoutLyricPositionsImmediately(viewportLyricWidth, viewportAnchorY, CloudMusic.currentLyric);
            this.lastViewportLyricWidth = viewportLyricWidth;
            this.lastViewportAnchorY = viewportAnchorY;
        }

        // 渲染一帧只获取一次播放快照：本帧歌词、进度条、逐字高亮、时间文字共享同一 positionMs/durationMs
        CloudMusic.PlaybackSnapshot snapshot = CloudMusic.getSnapshot();

        api.getGLStateManager().pushMatrix();
        double contentScale = getContentScale(alpha);
        scaleAtPos(posX + width * .5, posY + height * .5, contentScale);

        this.renderBackground(posX, posY, width, height, alpha, snapshot);
        this.renderControlsPart(mouseX, mouseY, posX, posY, width, height, alpha, snapshot);

        // 歌词整列在转场里最后入场，除了 alpha 还带一小段自下而上的浮起：位移与 lyricsFade
        // 读同一个时钟，稳态严格归零。只影响绘制矩阵，不动任何交互矩形；模糊区域与文字在
        // 同一矩阵里，一起平移，合成不会错位。快捷面板是浮层，不跟着走。
        double lyricRise = (1.0 - CoverHeroTransition.lyricsFade()) * height * .014;
        int lyricWheel = this.styleQuickPanel.handleScroll(dWheel, mouseX, mouseY) ? 0 : dWheel;
        api.getGLStateManager().pushMatrix();
        api.getGLStateManager().translate(0, lyricRise, 0);
        this.renderLyrics(mouseX, mouseY, posX, posY, width, height, lyricWheel, alpha, snapshot);
        api.getGLStateManager().popMatrix();
        // 快捷面板画在最后：它是浮层，必须盖在封面与歌词之上。
        if (!TransitionPerformanceBudget.skipLyricQuickPanel()) {
            this.styleQuickPanel.render(mouseX, mouseY, posX, posY, width, height, alpha);
        }
        api.getGLStateManager().popMatrix();
    }

    private void renderLyrics(double mouseX, double mouseY, double posX, double posY, double width, double height, int dWheel, float alphaIn, CloudMusic.PlaybackSnapshot snapshot) {
        float alpha = alphaIn * (float) CoverHeroTransition.lyricsFade();

        if (CloudMusic.lyrics.isEmpty()) return;
        if (!CloudMusic.lyricsMatchCurrentTrack()) return;

        // 「有没有可用的播放时钟」不能问 snapshot.player：镜像态下出声的是外部播放器，
        // player 恒为 null，而进度与歌词都要照常走。
        boolean playerNotReady = !CloudMusic.hasPlaybackClock();
        double totalTimeMillis = snapshot.durationMs;
        double overridePlaybackProgress = progressBarProgressOverride * totalTimeMillis;
        double songProgress = playerNotReady ? 0 : (progressBarDragging ? overridePlaybackProgress : snapshot.positionMs);

        LyricViewport lyricViewport = createLyricViewport(posX, posY, width, height);
        if (lyricViewport.width <= .5 || lyricViewport.height <= .5) return;

        double lyricsWidth = lyricViewport.width;
        LyricLine displayedCurrentLyric = qplayerVisualAnchorLyric(songProgress);

        FullscreenLyricRenderer styleRenderer = currentStyleRenderer();
        if (styleRenderer != null) {
            styleRenderer.render(lyricViewport.left, lyricViewport.top,
                    lyricViewport.width, lyricViewport.height, alpha, songProgress, displayedCurrentLyric);
            return;
        }

        boolean lyricManualScroll = Math.abs(scrollTarget) > 0.01 || Math.abs(scrollOffset) > 0.01;
        updateLyricPositions(posY, height, lyricsWidth, displayedCurrentLyric,
                songProgress, lyricManualScroll);

        boolean hoveringLyrics = isHovered(mouseX, mouseY, lyricViewport.left, lyricViewport.top,
                lyricViewport.width, lyricViewport.height);
        if (hoveringLyrics && dWheel != 0) {
            double strength = 24;
            if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)) strength *= 2;
            if (dWheel > 0) scrollTarget += strength;
            else scrollTarget -= strength;
            scrollOffsetResetTimer.reset();
        }
        if (scrollOffsetResetTimer.isDelayed(3000)) scrollTarget = 0;
        scrollOffset = Interpolations.interpolate(scrollOffset, scrollTarget, 0.25f);

        double lyricRenderOffsetX = lyricViewport.left;
        double lyricsViewportTop = lyricViewport.top;
        double lyricsViewportBottom = lyricViewport.bottom();

        StencilClipManager.beginClip(() -> Rect.draw(lyricViewport.left, lyricsViewportTop,
                lyricsWidth, lyricViewport.height, -1));
        ScissorClipManager.begin(lyricViewport.left, lyricsViewportTop, lyricsWidth, lyricViewport.height);
        boolean lyricScissorActive = true;
        try {
            List<LyricLine> lines = CloudMusic.lyrics;
            int anchorIndex = Math.max(0, lines.indexOf(displayedCurrentLyric));
            int anchorGroup = anchorIndex < lines.size() && qplayerLineToGroup[anchorIndex] >= 0
                    ? qplayerLineToGroup[anchorIndex] : 0;
            int renderStart = Math.max(0, qplayerGroupStart[anchorGroup] - 16);
            int renderEnd = Math.min(lines.size(), qplayerGroupEnd[anchorGroup] + 16 + 1);
            double fontHeight = FontManager.pf65bold.getHeight();
            double lineStep = fontHeight;

            for (int k = renderStart; k < renderEnd; k++) {
                LyricLine lyric = lines.get(k);
                if (lyric == null || lyric.isBreakLine) continue;
                if (lyric.posY + lyric.height + scrollOffset < lyricsViewportTop - 32.0) continue;
                if (lyric.posY + scrollOffset > lyricsViewportBottom + 32.0) continue;

                int groupIndex = qplayerLineToGroup[k];
                float activeK = groupIndex >= 0 ? qplayerActiveK(groupIndex, songProgress) : 0f;
                float qBaseAlpha = qplayerBaseAlpha(activeK);
                lyric.alpha = activeK;
                boolean isHovering = isHovered(mouseX, mouseY - scrollOffset,
                        lyricRenderOffsetX, lyric.posY, lyricsWidth, lyric.height);
                lyric.hoveringAlpha = Interpolations.interpolate(lyric.hoveringAlpha,
                        isHovering ? 1f : 0f, 0.2f);
                if (isHovering) CursorUtils.setOverride(CursorUtils.HAND);
                if (isHovering && Mouse.isButtonDown(0) && !prevMouse) {
                    prevMouse = true;
                    if (CloudMusic.hasPlaybackClock() && CloudMusic.seekToMillis(lyric.timestamp)) {
                        resetLyricStatus();
                    }
                    scrollTarget = 0;
                }

                double columnWidth = LyricDuetGroups.columnWidth(lyricsWidth, lyric.duetSlot);
                double columnLeft = LyricDuetGroups.columnLeft(lyricRenderOffsetX, lyricsWidth, lyric.duetSlot);
                boolean columnRightAligned = LyricDuetGroups.rightAligned(lyric.duetSlot);
                double renderBaseY = lyric.posY + scrollOffset;

                List<LyricLine.Word> words = lyric.words;
                WordPlacement placement = null;
                String[] plainRows = null;
                int rowCount;
                if (!words.isEmpty()) {
                    placement = qplayerPlacement(lyric, words, columnLeft,
                            qplayerWrapWidth(columnWidth), columnRightAligned);
                    rowCount = 1;
                    for (int wordIndex = 0; wordIndex < words.size(); wordIndex++) {
                        rowCount = Math.max(rowCount, placement.row[wordIndex] + 1);
                    }
                } else {
                    plainRows = FontManager.pf65bold.fitWidth(lyric.lyric, qplayerWrapWidth(columnWidth));
                    rowCount = Math.max(1, plainRows.length);
                }

                double mainTextHeight = lineStep * rowCount;
                double maxRowRightX = columnLeft;
                double lineScaleAnchorX = columnRightAligned ? columnLeft + columnWidth : columnLeft;
                double lineScaleAnchorY = renderBaseY + mainTextHeight * .5;
                double lineScale = qplayerLineScale(activeK,
                        posY + height * lyricFraction(), lineScaleAnchorY, mainTextHeight);
                api.getGLStateManager().pushMatrix();
                api.getGLStateManager().translate(lineScaleAnchorX, lineScaleAnchorY, 0);
                api.getGLStateManager().scale(lineScale, lineScale, 1);
                api.getGLStateManager().translate(-lineScaleAnchorX, -lineScaleAnchorY, 0);

                boolean animatePerToken = lyric.hasTimedWords();
                for (int row = 0; row < rowCount; row++) {
                    double rowRenderY = renderBaseY + row * lineStep;

                    if (placement != null) {
                        int first = -1;
                        int last = -1;
                        for (int wordIndex = 0; wordIndex < words.size(); wordIndex++) {
                            if (placement.row[wordIndex] != row) continue;
                            if (first < 0) first = wordIndex;
                            last = wordIndex;
                        }
                        if (first < 0) continue;

                        double rowLeft = placement.x[first];
                        double rowRight = placement.x[last]
                                + FontManager.pf65bold.getStringWidthD(words.get(last).word);
                        maxRowRightX = Math.max(maxRowRightX, rowRight);
                        double rowWidth = Math.max(1.0, rowRight - rowLeft);
                        double rowHScale = Math.min(1.0,
                                columnWidth / (rowWidth * Math.max(1.0, lineScale)));
                        boolean rowHGuard = rowHScale < .999;
                        if (rowHGuard) {
                            api.getGLStateManager().pushMatrix();
                            api.getGLStateManager().translate(lineScaleAnchorX, rowRenderY, 0);
                            api.getGLStateManager().scale(rowHScale, 1, 1);
                            api.getGLStateManager().translate(-lineScaleAnchorX, -rowRenderY, 0);
                        }
                        try {
                        if (!animatePerToken || activeK <= .001f) {
                            for (int wordIndex = first; wordIndex <= last; wordIndex++) {
                                String text = words.get(wordIndex).word;
                                FontManager.pf65bold.drawString(text, placement.x[wordIndex], rowRenderY + 2.0,
                                        hexColor(0, 0, 0, alpha * qBaseAlpha * .48f));
                                FontManager.pf65bold.drawString(text, placement.x[wordIndex], rowRenderY,
                                        qplayerTextColor(alpha * qBaseAlpha));
                            }
                            continue;
                        }

                        for (int wordIndex = first; wordIndex <= last; wordIndex++) {
                            LyricLine.Word word = words.get(wordIndex);
                            // 字形级提升:整词共用一个提升量时，长音节（"真"、"風"这类一拍唱很久的字）
                            // 只能整块一起弹；改成逐 char 用自己字形的起始时刻求提升，纹波才细到一个字。
                            // 对齐、时长归一化都交给共享的字形时间轴，这里只提供曲线。
                            final double wordLift = qplayerSyllableLift(word.timestamp, songProgress);
                            // 第五个参数是兜底：字形表覆盖不到的位置（理论上不该有）整格回落到按词求的提升量，
                            // 免得画出来一个 0 提升量的字孤零零杵在原地。
                            LyricGraphemeTimeline.fillCharLifts(lyric, wordIndex, word.emphasizes,
                                    start -> qplayerSyllableLift((long) start, songProgress), wordLift);
                        }

                        int glowPad = TextGlow.padding(FontManager.pf65bold, .034);
                        double layerLeft = rowLeft - 8.0 - glowPad;
                        double layerRight = rowRight + 8.0 + glowPad;
                        double layerTop = rowRenderY - 2.0 - glowPad;
                        double layerBottom = rowRenderY + fontHeight + 6.0 + glowPad;
                        io.github.humbleui.skija.Canvas ktvCanvas = SkijaGfx.canvas();
                        int ktvLayer = ktvCanvas == null ? -1 : ktvCanvas.saveLayer(
                                io.github.humbleui.types.Rect.makeLTRB(
                                        (float) layerLeft, (float) layerTop,
                                        (float) layerRight, (float) layerBottom), null);
                        try {
                            for (int wordIndex = first; wordIndex <= last; wordIndex++) {
                                LyricLine.Word word = words.get(wordIndex);
                                double x = placement.x[wordIndex];
                                char[] chars = word.word.toCharArray();
                                for (int charIndex = 0; charIndex < chars.length; charIndex++) {
                                    char c = chars[charIndex];
                                    double y = rowRenderY - word.emphasizes[Math.min(charIndex, word.emphasizes.length - 1)]
                                            - qplayerWordRibbonLift(word, songProgress, activeK);
                                    String glyph = String.valueOf(c);
                                    FontManager.pf65bold.drawString(glyph, x, y + 2.0,
                                            hexColor(0, 0, 0, alpha * qBaseAlpha * .48f));
                                    FontManager.pf65bold.drawString(glyph, x, y, qplayerTextColor(alpha * qBaseAlpha));
                                    x += FontManager.pf65bold.getCharWidth(c,
                                            charIndex + 1 < chars.length ? chars[charIndex + 1] : '\0');
                                }
                            }
                            if (ktvCanvas != null) {
                                double sweepX = qplayerSweepX(words, placement, first, last,
                                        rowLeft, rowRight, songProgress);
                                applyQplayerSweepMask(ktvCanvas, sweepX, layerLeft, layerRight,
                                        layerTop, layerBottom - layerTop, activeK);
                            }
                        } finally {
                            if (ktvLayer >= 0) ktvCanvas.restoreToCount(ktvLayer);
                        }

                        // 副歌强调：整行的持续音发光更足。曲线由段号（bar/circle/beam）决定，
                        // 非副歌行恒为 0，所以这里的系数退化成 1，行为与加这段之前一致。
                        float chorusK = (float) CloudMusic.chorusEmphasis(lyric, songProgress);
                        for (int wordIndex = first; wordIndex <= last; wordIndex++) {
                            LyricLine.Word word = words.get(wordIndex);
                            long wordEnd = word.timestamp + Math.max(0L, word.duration);
                            long wordDuration = wordEnd - word.timestamp;
                            if (wordDuration < 1500L) continue;
                            if (songProgress < word.timestamp || songProgress > wordEnd) continue;
                            double progress = (songProgress - word.timestamp) / (double) Math.max(1L, wordDuration);
                            float glowAlpha = activeK
                                    * smoothStepQplayer(progress / .18)
                                    * (1f - smoothStepQplayer((progress - .90) / .10))
                                    * (.55f * (1f + .9f * chorusK)) * qBaseAlpha;
                            if (glowAlpha <= .01f) continue;
                            drawQplayerWordGlow(word, placement.x[wordIndex],
                                    rowRenderY - qplayerWordRibbonLift(word, songProgress, activeK),
                                    alpha * glowAlpha);
                        }
                        } finally {
                            if (rowHGuard) api.getGLStateManager().popMatrix();
                        }
                    } else {
                        String text = plainRows[row];
                        double rowX = columnRightAligned
                                ? columnLeft + columnWidth - FontManager.pf65bold.getStringWidthD(text)
                                : columnLeft;
                        double rowWidth = Math.max(1.0, FontManager.pf65bold.getStringWidthD(text));
                        double rowHScale = Math.min(1.0,
                                columnWidth / (rowWidth * Math.max(1.0, lineScale)));
                        boolean rowHGuard = rowHScale < .999;
                        if (rowHGuard) {
                            api.getGLStateManager().pushMatrix();
                            api.getGLStateManager().translate(lineScaleAnchorX, rowRenderY, 0);
                            api.getGLStateManager().scale(rowHScale, 1, 1);
                            api.getGLStateManager().translate(-lineScaleAnchorX, -rowRenderY, 0);
                        }
                        try {
                        FontManager.pf65bold.drawString(text, rowX, rowRenderY + 2.0,
                                hexColor(0, 0, 0, alpha * qBaseAlpha * .48f));
                        FontManager.pf65bold.drawString(text, rowX, rowRenderY,
                                qplayerTextColor(alpha * qBaseAlpha));
                        maxRowRightX = Math.max(maxRowRightX,
                                rowX + FontManager.pf65bold.getStringWidthD(text));
                        } finally {
                            if (rowHGuard) api.getGLStateManager().popMatrix();
                        }
                    }
                }

                if (lyric.translationText != null && !lyric.translationText.trim().isEmpty()) {
                    double translationY = renderBaseY + (rowCount - 1) * lineStep
                            + fontHeight * .85 + 8.0;
                    String[] strings = FontManager.pf34bold.fitWidth(lyric.translationText, qplayerWrapWidth(columnWidth));
                    for (String string : strings) {
                        double translationX = columnRightAligned
                                ? columnLeft + columnWidth - FontManager.pf34bold.getStringWidthD(string)
                                : columnLeft;
                        FontManager.pf34bold.drawString(string, translationX, translationY + 2.0,
                                hexColor(0, 0, 0, alpha * qBaseAlpha * .75f * .48f));
                        FontManager.pf34bold.drawString(string, translationX, translationY,
                                qplayerTextColor(alpha * qBaseAlpha * .75f));
                        translationY += FontManager.pf34bold.getHeight() + 4;
                    }
                }

                api.getGLStateManager().popMatrix();
            }

            renderQplayerInterludeDots(lyricViewport.left, lyricsWidth,
                    fontHeight, fontHeight, songProgress, alpha);
        } finally {
            if (lyricScissorActive) ScissorClipManager.end();
            StencilClipManager.restoreActiveClip();
            StencilClipManager.endClip();
        }
    }

    // ── qplayer 全屏歌词动效 ───────────────────────────────────────

    /**
     * 为活动行的 1.14x 强调预留空间。没有这个除数，一个在 1.0x 下合适的
     * 行会在成为活动行时立即在右缘被裁剪。
     */
    private static double qplayerWrapWidth(double columnWidth) {
        return Math.max(1.0, columnWidth / 1.14);
    }

    private int qplayerTextColor(float alpha) {
        return hexColor(.902f, .902f, .902f, alpha);
    }

    private void qplayerBuildGroups() {
        List<LyricLine> lines = CloudMusic.lyrics;
        int size = lines.size();
        if (qplayerGroupStart.length != size || qplayerGroupEnd.length != size
                || qplayerLineToGroup.length != size || qplayerInterludeBefore.length != size) {
            qplayerGroupStart = new int[size];
            qplayerGroupEnd = new int[size];
            qplayerLineToGroup = new int[size];
            qplayerInterludeBefore = new double[size];
        }
        java.util.Arrays.fill(qplayerLineToGroup, -1);

        int groupCount = 0;
        int index = 0;
        while (index < size) {
            if (lines.get(index).isBreakLine) {
                index++;
                continue;
            }
            int start = index;
            int end = index;
            while (end + 1 < size && !lines.get(end + 1).isBreakLine
                    && LyricDuetGroups.sharesGroup(lines.get(start), lines.get(end + 1))) {
                end++;
            }
            qplayerGroupStart[groupCount] = start;
            qplayerGroupEnd[groupCount] = end;
            for (int i = start; i <= end; i++) qplayerLineToGroup[i] = groupCount;
            groupCount++;
            index = end + 1;
        }
        qplayerGroupCount = groupCount;
    }

    private long qplayerGroupStartMs(int groupIndex) {
        if (groupIndex < 0 || groupIndex >= qplayerGroupCount) return 0L;
        long value = Long.MAX_VALUE;
        for (int i = qplayerGroupStart[groupIndex]; i <= qplayerGroupEnd[groupIndex]; i++) {
            value = Math.min(value, CloudMusic.lyrics.get(i).timestamp);
        }
        return value == Long.MAX_VALUE ? 0L : value;
    }

    private long qplayerGroupEndMs(int groupIndex) {
        if (groupIndex < 0 || groupIndex >= qplayerGroupCount) return 0L;
        long value = Long.MIN_VALUE;
        for (int i = qplayerGroupStart[groupIndex]; i <= qplayerGroupEnd[groupIndex]; i++) {
            LyricLine line = CloudMusic.lyrics.get(i);
            value = Math.max(value, line.timestamp + Math.max(line.duration, 0L));
        }
        return value == Long.MIN_VALUE ? 0L : value;
    }

    private static long qplayerFadeInStart(long groupStartMs) {
        return groupStartMs - 600L + 150L;
    }

    private static float qplayerActiveK(long groupStartMs, long groupEndMs, double positionMs) {
        final long fadeInDuration = 600L;
        final long fadeOutDelay = 100L;
        final long fadeOutDuration = 350L;
        long fadeInStart = qplayerFadeInStart(groupStartMs);
        long fadeInEnd = fadeInStart + fadeInDuration;
        long fadeOutStart = groupEndMs + fadeOutDelay;
        long fadeOutEnd = fadeOutStart + fadeOutDuration;
        if (positionMs < fadeInStart || positionMs >= fadeOutEnd) return 0f;
        if (positionMs < fadeInEnd) {
            return smoothStepQplayer((positionMs - fadeInStart) / (double) fadeInDuration);
        }
        if (positionMs < fadeOutStart) return 1f;
        return 1f - smoothStepQplayer((positionMs - fadeOutStart) / (double) fadeOutDuration);
    }

    private float qplayerActiveK(int groupIndex, double positionMs) {
        if (groupIndex < 0 || groupIndex >= qplayerGroupCount) return 0f;
        return qplayerActiveK(qplayerGroupStartMs(groupIndex), qplayerGroupEndMs(groupIndex), positionMs);
    }

    /**
     * qplayer 在滚动锚点交给下一组时，是在它首次可见变亮、
     * 早于它演唱 450ms 时。退出组保持自己的淡出和扫光时间线。
     */
    private LyricLine qplayerVisualAnchorLyric(double positionMs) {
        qplayerBuildGroups();
        List<LyricLine> lines = CloudMusic.lyrics;
        if (lines.isEmpty() || qplayerGroupCount == 0) return CloudMusic.currentLyric;

        int resultGroup = 0;
        for (int group = 0; group < qplayerGroupCount; group++) {
            if (qplayerFadeInStart(qplayerGroupStartMs(group)) > positionMs) break;
            resultGroup = group;
        }
        return lines.get(qplayerGroupStart[resultGroup]);
    }

    /** qplayer 的欠阻尼音节抬升：2px 峰值，ω0=3.7416574，ζ=0.935414。 */
    private static double qplayerSyllableLift(long startMs, double positionMs) {
        double elapsed = (positionMs - startMs) / 1000.0;
        if (elapsed <= 0.0) return 0.0;

        final double omega0 = 3.7416574;
        final double zeta = .935414;
        double dampedFrequency = zeta * omega0;
        double frequency = omega0 * Math.sqrt(1.0 - zeta * zeta);
        double envelope = Math.exp(-dampedFrequency * elapsed);
        double value = 1.0 - envelope * (Math.cos(frequency * elapsed)
                + (dampedFrequency / frequency) * Math.sin(frequency * elapsed));
        return 2.0 * Math.max(0.0, value);
    }

    /** qplayer 为持续词额外加的 2px 词带抬升，由词进度驱动。 */
    private static double qplayerWordRibbonLift(LyricLine.Word word, double positionMs, float activeK) {
        long end = word.timestamp + Math.max(0L, word.duration);
        long duration = Math.max(1L, end - word.timestamp);
        if (end - word.timestamp < 1500L) return 0.0;
        double progress = Math.max(0.0, Math.min(1.0, (positionMs - word.timestamp) / (double) duration));
        return 2.0 * Math.sin(Math.PI * progress) * Math.max(0f, Math.min(1f, activeK));
    }

    /** 按行和视口几何缓存 qplayer 换行，避免逐帧分配。 */
    private WordPlacement qplayerPlacement(LyricLine lyric, List<LyricLine.Word> words,
                                           double columnLeft, double columnWidth, boolean rightAligned) {
        double[] geometry = qplayerPlacementGeometry.get(lyric);
        if (geometry == null || geometry.length != 2
                || Math.abs(geometry[0] - columnLeft) > .5
                || Math.abs(geometry[1] - columnWidth) > .5) {
            geometry = new double[]{columnLeft, columnWidth};
            qplayerPlacementGeometry.put(lyric, geometry);
            WordPlacement placement = layoutWords(words, columnLeft, columnWidth, rightAligned);
            qplayerPlacementCache.put(lyric, placement);
            return placement;
        }
        WordPlacement placement = qplayerPlacementCache.get(lyric);
        if (placement == null) {
            placement = layoutWords(words, columnLeft, columnWidth, rightAligned);
            qplayerPlacementCache.put(lyric, placement);
        }
        return placement;
    }

    /** qplayer 的行扫光前沿。在该行之外偏移两个羽化宽度。 */
    private static double qplayerSweepX(List<LyricLine.Word> words, WordPlacement placement,
                                        int first, int last, double rowLeft, double rowRight,
                                        double positionMs) {
        LyricLine.Word firstWord = words.get(first);
        LyricLine.Word lastWord = words.get(last);
        long lastEnd = lastWord.timestamp + Math.max(0L, lastWord.duration);
        if (positionMs < firstWord.timestamp) return rowLeft - 32.0;
        if (positionMs >= lastEnd) return rowRight + 32.0;

        for (int index = first; index <= last; index++) {
            LyricLine.Word word = words.get(index);
            long start = word.timestamp;
            long end = word.duration > 0L ? start + word.duration
                    : (index + 1 <= last ? words.get(index + 1).timestamp : start + 1L);
            if (end <= start) end = start + 1L;
            if (positionMs < start) return placement.x[index];

            double left = placement.x[index];
            double right = left + FontManager.pf65bold.getStringWidthD(word.word);
            if (positionMs < end) {
                return left + (right - left) * ((positionMs - start) / (double) (end - start));
            }
        }
        return rowRight;
    }

    /**
     * qplayer 的 16px DST_IN 扫光。渐变本身只有 16px 宽，但 DST_IN
     * 矩形覆盖整行层，正如 qplayer 平移的大矩形。
     */
    private static void applyQplayerSweepMask(io.github.humbleui.skija.Canvas canvas,
                                              double sweepX, double layerLeft, double layerRight,
                                              double layerTop, double layerHeight, float activeK) {
        float maskAlpha = 1f - (1f - .36f) * Math.max(0f, Math.min(1f, activeK));
        int dark = ((int) (maskAlpha * 255f) << 24) | 0x00FFFFFF;
        try (io.github.humbleui.skija.Shader gradient = io.github.humbleui.skija.Shader.makeLinearGradient(
                (float) (sweepX - 8.0), (float) layerTop,
                (float) (sweepX + 8.0), (float) layerTop,
                new int[]{0xFFFFFFFF, dark})) {
            io.github.humbleui.skija.Paint paint = SkijaGfx.fillShader(gradient);
            paint.setBlendMode(io.github.humbleui.skija.BlendMode.DST_IN);
            canvas.drawRect(io.github.humbleui.types.Rect.makeLTRB(
                    (float) layerLeft, (float) layerTop,
                    (float) layerRight, (float) (layerTop + layerHeight)), paint);
            paint.setShader(null);
            paint.setBlendMode(SkijaGfx.blendMode());
        }
    }

    /** qplayer 闲置/活动基础亮度：0.22 → 1.0，由组时间线驱动。 */
    private static float qplayerBaseAlpha(float activeK) {
        return .22f + .78f * Math.max(0f, Math.min(1f, activeK));
    }

    /** 适配到 MuoniumPlayer 原生主文本框的 qplayer 深度强调。 */
    private static double qplayerLineScale(float activeK, double readingCenterY,
                                           double lineCenterY, double referenceHeight) {
        double reference = Math.max(40.0, referenceHeight);
        double proximity = 1.0 - Math.min(1.0, Math.abs(lineCenterY - readingCenterY) / reference);
        double emphasis = Math.max(0f, Math.min(1f, activeK)) * proximity;
        return .97 + .17 * emphasis;
    }

    private static float smoothStepQplayer(double value) {
        double progress = Math.max(0.0, Math.min(1.0, value));
        return (float) (progress * progress * (3.0 - 2.0 * progress));
    }

    /** 项目原生副车绘制助手：原始顶部坐标语义和 +4 行距。 */
    private double drawQplayerSubline(String text, double columnLeft, double columnWidth,
                                      boolean rightAligned, double subY, float alpha) {
        String[] rows = FontManager.pf34bold.fitWidth(text, columnWidth);
        for (String row : rows) {
            double x = rightAligned
                    ? columnLeft + columnWidth - FontManager.pf34bold.getStringWidthD(row)
                    : columnLeft;
            FontManager.pf34bold.drawString(row, x, subY + 2.0,
                    hexColor(0, 0, 0, alpha * .48f));
            FontManager.pf34bold.drawString(row, x, subY, qplayerTextColor(alpha));
            subY += FontManager.pf34bold.getHeight() + 4;
        }
        return subY;
    }

    /** qplayer 的单个 2.2px 词光晕；仅持续 1.5s 的词才获得它。 */
    private static void drawQplayerWordGlow(LyricLine.Word word, double x, double drawY, float alpha) {
        if (alpha <= .01f) return;
        io.github.humbleui.skija.BlendMode previous = SkijaGfx.blendMode();
        SkijaGfx.setBlendMode(io.github.humbleui.skija.BlendMode.PLUS);
        try {
            FontManager.pf65bold.drawStringGlow(word.word, x, drawY,
                    ((int) (Math.max(0f, Math.min(1f, alpha)) * 255f) << 24) | 0xE6E6E6, 2.2);
        } finally {
            SkijaGfx.setBlendMode(previous);
        }
    }

    /**
     * qplayer 的间奏常量是为 28px 歌词字体调的。MuoniumPlayer 的 pf65 槽按
     * 32.5px 创建并报告不同的行框，因此缩放几何，而非使用
     * 原始像素常量。
     */
    private static double qplayerInterludeScale() {
        return Math.max(1.0, FontManager.pf65bold.getHeight() / 28.0);
    }

    /**
     * 原生歌词组已含 20px 行间距。从缩放后的 qplayer
     * 槽中减去它，使两个歌词块之间的总距离保持 qplayer 大小，而非
     * 在 MuoniumPlayer 的原生间距之上意外再加上 qplayer 的槽。
     */
    private static double qplayerInterludeSlotHeight() {
        return Math.max(0.0, 40.0 * qplayerInterludeScale() - getLyricLineSpacing());
    }

    private static double qplayerInterludeDotRadius() {
        return 6.8 * qplayerInterludeScale();
    }

    private static double qplayerInterludeDotSpacing() {
        return 27.0 * qplayerInterludeScale();
    }

    /**
     * 重建 qplayer 的文档布局。间奏槽是动态的，随播放头扩展/折叠，
     * 因此 lineTops 和 targetScroll 刻意每帧重算。
     */
    private void updateLyricPositions(double posY, double height, double width,
                                      LyricLine currentLyric, double playheadMs, boolean manualScroll) {
        if (currentLyric == null) return;
        List<LyricLine> lines = CloudMusic.lyrics;
        int size = lines.size();
        if (size == 0) return;

        synchronized (CloudMusic.lyrics) {
            qplayerBuildGroups();
            if (qplayerGroupCount == 0) return;
            if (lyricDocTop.length != size) lyricDocTop = new double[size];
            double[] docTop = lyricDocTop;

            for (int i = 0; i < size; i++) {
                LyricLine lyric = lines.get(i);
                if (!lyric.isBreakLine) {
                    lyric.computeHeight(qplayerWrapWidth(LyricDuetGroups.columnWidth(width, lyric.duetSlot)));
                }
            }

            for (int group = 0; group < qplayerGroupCount; group++) {
                long previousEnd = group == 0 ? 0L : qplayerGroupEndMs(group - 1);
                long currentStart = qplayerGroupStartMs(group);
                long effectiveEnd = currentStart - 250L;
                long gap = effectiveEnd - previousEnd;
                qplayerInterludeBefore[group] = gap >= 2000L
                        ? qplayerInterludeSlot(playheadMs, previousEnd, effectiveEnd, qplayerInterludeSlotHeight())
                        : 0.0;
            }

            double cursor = 0.0;
            for (int group = 0; group < qplayerGroupCount; group++) {
                double groupTop = cursor + qplayerInterludeBefore[group];
                for (int i = qplayerGroupStart[group]; i <= qplayerGroupEnd[group]; i++) {
                    docTop[i] = groupTop;
                    groupTop += lines.get(i).height + getLyricLineSpacing();
                }
                cursor = groupTop;
            }
            for (int i = 0; i < size; i++) {
                if (lines.get(i).isBreakLine) docTop[i] = cursor;
            }

            int anchorGroup = -1;
            for (int group = 0; group < qplayerGroupCount; group++) {
                if (qplayerFadeInStart(qplayerGroupStartMs(group)) > playheadMs) break;
                anchorGroup = group;
            }

            double targetScroll = 0.0;
            if (anchorGroup >= 0) {
                double blockTop = docTop[qplayerGroupStart[anchorGroup]];
                double groupBottom = docTop[qplayerGroupEnd[anchorGroup]]
                        + lines.get(qplayerGroupEnd[anchorGroup]).height;
                targetScroll = (blockTop + groupBottom) * .5;
                double topLimit = blockTop + height * lyricFraction() - 12.0;
                targetScroll = Math.min(targetScroll, topLimit);
                double bottomLimit = groupBottom - height * (1.0 - lyricFraction()) + 12.0;
                if (bottomLimit <= topLimit) {
                    targetScroll = Math.max(targetScroll, bottomLimit);
                }
            }

            qplayerInInterlude = false;
            qplayerInterludeNextGroup = -1;
            int timelineGroup = -1;
            for (int group = 0; group < qplayerGroupCount; group++) {
                if (qplayerGroupStartMs(group) > playheadMs) break;
                timelineGroup = group;
            }

            int nextGroup = -1;
            long gapStart = -1L;
            if (timelineGroup < 0 && qplayerGroupCount > 0) {
                nextGroup = 0;
                gapStart = 0L;
            } else if (timelineGroup >= 0 && timelineGroup + 1 < qplayerGroupCount
                    && playheadMs >= qplayerGroupEndMs(timelineGroup)) {
                nextGroup = timelineGroup + 1;
                gapStart = qplayerGroupEndMs(timelineGroup);
            }
            if (nextGroup >= 0) {
                long effectiveEnd = qplayerGroupStartMs(nextGroup) - 250L;
                long gap = effectiveEnd - gapStart;
                if (playheadMs < effectiveEnd && gap >= 2000L) {
                    qplayerInInterlude = true;
                    qplayerInterludeNextGroup = nextGroup;
                    qplayerInterludeStartMs = gapStart;
                    if (anchorGroup != nextGroup) {
                        double slotHeight = qplayerInterludeBefore[nextGroup];
                        targetScroll = docTop[qplayerGroupStart[nextGroup]]
                                - slotHeight + slotHeight * .5;
                    }
                }
            }

            int lastLine = qplayerGroupEnd[qplayerGroupCount - 1];
            double contentEnd = docTop[lastLine] + lines.get(lastLine).height;
            if (contentEnd > height) {
                double edgePad = height * .7;
                double minimum = height * lyricFraction() - edgePad;
                double maximum = contentEnd - height * (1.0 - lyricFraction()) + edgePad;
                targetScroll = Math.max(minimum, Math.min(maximum, targetScroll));
            }

            double centerY = posY + height * lyricFraction();
            double restOffset = centerY - targetScroll;
            scrollMotion.apply(lines, docTop, restOffset,
                    qplayerGroupStart[Math.max(0, anchorGroup)], playheadMs,
                    qplayerInInterlude, manualScroll);
        }
    }

    /** qplayer/AMLL 间奏槽：两端各 150ms smoothstep 展开。 */
    private static double qplayerInterludeSlot(double positionMs, long startMs, long endMs, double height) {
        if (positionMs < startMs || positionMs > endMs) return 0.0;
        double elapsed = positionMs - startMs;
        double progress;
        if (elapsed < 150.0) progress = elapsed / 150.0;
        else if (endMs - positionMs < 150.0) progress = (endMs - positionMs) / 150.0;
        else progress = 1.0;
        return height * smoothStepQplayer(progress);
    }

    private void renderQplayerInterludeDots(double left, double width, double firstRowHeight,
                                            double fontHeight, double positionMs, float alpha) {
        if (!qplayerInInterlude || qplayerInterludeNextGroup < 0
                || qplayerInterludeNextGroup >= qplayerGroupCount) return;
        List<LyricLine> lines = CloudMusic.lyrics;
        int nextLineIndex = qplayerGroupStart[qplayerInterludeNextGroup];
        if (nextLineIndex >= lines.size()) return;
        double slotHeight = qplayerInterludeBefore[qplayerInterludeNextGroup];
        if (slotHeight <= 4.0) return;

        LyricLine nextLine = lines.get(nextLineIndex);
        double nextTop = nextLine.posY + scrollOffset;
        // MuoniumPlayer 的行顶已是原生文本顶坐标。qplayer 的基线
        //偏移不得在此添加；那样做会把点推向下一首歌词。
        double totalGap = slotHeight + getLyricLineSpacing();
        double prevTextBottom = nextTop - totalGap;
        double nextTextTop = nextTop;
        double dotRadius = qplayerInterludeDotRadius();
        double dotSpacing = qplayerInterludeDotSpacing();
        double anchorY = (prevTextBottom + nextTextTop) * .5 - dotRadius;
        boolean dotsRight = LyricDuetGroups.rightAligned(nextLine.duetSlot);
        double dotsWidth = 2.0 * dotRadius + 2.0 * dotSpacing;
        double dotsX = dotsRight ? Math.max(left, left + width - dotsWidth) : left;

        long effectiveEnd = qplayerGroupStartMs(qplayerInterludeNextGroup) - 250L;
        long interludeDuration = effectiveEnd - qplayerInterludeStartMs;
        renderQplayerInterludeDots(dotsX, anchorY,
                (long) Math.floor(positionMs) - qplayerInterludeStartMs,
                interludeDuration, alpha);
    }

    private void renderQplayerInterludeDots(double left, double anchorY,
                                            long currentDuration, long interludeDuration, float alpha) {
        if (currentDuration < 0L || currentDuration > interludeDuration || interludeDuration <= 0L) return;

        long fadeInEnd = Math.min(600L, (long) (interludeDuration * 0.20));
        long scaleRamp = Math.min(1500L, (long) (interludeDuration * 0.35));
        long exitScale = Math.min(750L, (long) (interludeDuration * 0.20));
        long exitOpacity = Math.min(375L, (long) (interludeDuration * 0.10));
        if (fadeInEnd <= 0L) fadeInEnd = 1L;

        double breatheDuration = interludeDuration / Math.ceil(interludeDuration / 1500.0);
        double scale = 1.0 + Math.sin(1.5 * Math.PI - (currentDuration / breatheDuration) * 2.0) / 20.0;
        double globalOpacity = 1.0;
        if (currentDuration < fadeInEnd) globalOpacity *= currentDuration / (double) fadeInEnd;
        if (currentDuration < scaleRamp) scale *= 1.0 - Math.pow(2.0, -10.0 * currentDuration / (double) scaleRamp);
        long remaining = interludeDuration - currentDuration;
        if (remaining < exitScale) {
            scale *= 1.0 - easeInOutBackQplayer((exitScale - remaining) / (double) exitScale / 2.0);
        }
        if (remaining < exitOpacity) {
            globalOpacity *= Math.max(0.0, Math.min(1.0, remaining / (double) exitOpacity));
        }

        long dotsDuration = Math.max(1L, interludeDuration - exitScale);
        scale = Math.max(0.0, scale) * .7;
        if (scale < .01) return;

        double opacity0 = clampQplayer(.25, currentDuration * 3.0 / dotsDuration * .75, 1.0);
        double opacity1 = clampQplayer(.25, (currentDuration - dotsDuration / 3.0) * 3.0 / dotsDuration * .75, 1.0);
        double opacity2 = clampQplayer(.25, (currentDuration - dotsDuration * 2.0 / 3.0) * 3.0 / dotsDuration * .75, 1.0);

        double dotRadius = qplayerInterludeDotRadius();
        double dotSpacing = qplayerInterludeDotSpacing();
        double cx0 = left + dotRadius;
        double cy = anchorY + dotRadius;
        api.getGLStateManager().pushMatrix();
        api.getGLStateManager().translate(cx0 + dotSpacing, cy, 0);
        api.getGLStateManager().scale(scale, scale, 1);
        api.getGLStateManager().translate(-(cx0 + dotSpacing), -cy, 0);
        try {
            io.github.humbleui.skija.Canvas canvas = SkijaGfx.canvas();
            if (canvas == null) return;
            double[] opacities = {globalOpacity * opacity0, globalOpacity * opacity1, globalOpacity * opacity2};
            for (int i = 0; i < 3; i++) {
                float dotAlpha = (float) Math.max(0.0, Math.min(1.0, opacities[i])) * alpha;
                io.github.humbleui.skija.Paint paint = SkijaGfx.fill(0x00FFFFFF | ((int) (dotAlpha * 255f) << 24));
                paint.setAntiAlias(true);
                canvas.drawCircle((float) (cx0 + i * dotSpacing), (float) cy,
                        (float) dotRadius, paint);
            }
        } finally {
            api.getGLStateManager().popMatrix();
        }
    }

    private static double easeInOutBackQplayer(double value) {
        double c1 = 1.70158;
        double c2 = c1 * 1.525;
        return value < .5
                ? (Math.pow(2 * value, 2) * ((c2 + 1) * 2 * value - c2)) / 2
                : (Math.pow(2 * value - 2, 2) * ((c2 + 1) * (value * 2 - 2) + c2) + 2) / 2;
    }

    private static double clampQplayer(double minimum, double value, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private double getCoverSizeMax() {
        NCMScreen screen = NCMScreen.getInstance();
        return getCoverSizeMax(screen.getPanelWidth(), screen.getPanelHeight());
    }

    private double getCoverSizeMax(double width, double height) {
        return LyricsPanelGeometry.coverSizeMax(width, height);
    }

    private double getCoverSizeMin() {
        return getCoverSizeMax() * .8;
    }

    private void renderControlsPart(double mouseX, double mouseY, double posX, double posY, double width, double height, float alphaIn, CloudMusic.PlaybackSnapshot snapshot) {
        // 左栏这一簇（信息块 / 进度条 / 音量 / 控制键）比页面底色再晚一档到场：接力封面正飞在
        // 它们头上，一起冒出来会糊成一片。用局部量而不是就地改参数，理由同 renderLyrics ——
        // 封面阴影那个 bloom lambda 捕获了它。
        float alpha = alphaIn * (float) CoverHeroTransition.chromeFade();
        TextureManager textureManager = TextureManager.getInstance();

        // 播完最后一首、或者被停止之后 currentlyPlaying 就是 null，而这一层还在淡出、仍要画好几帧。
        // renderBackground 早就按 null 处理了，这里漏了整整三处 —— 于是「一起听最后一曲结束」直接
        // 崩渲染线程。没有曲目时封面与曲名整组让位（这也正是曲名本该消失的样子），进度条与控制键
        // 照常画：它们只依赖 snapshot 里的时间与 player，和曲目对象无关。
        Music music = snapshot.music;

        double coverSizeMax = this.getCoverSizeMax(width, height);
        double coverSizeMin = coverSizeMax * .8;
        double coverCenterX = posX + width * .24;
        double coverCenterY = posY + height * .30;
        coverSize = Interpolations.interpolate(coverSize, !CloudMusic.isAudibleNow() ? coverSizeMin : coverSizeMax, 0.2f);

        double coverSizePerc = coverSize / coverSizeMax;
        double coverRadius = 7;
        double coverLeft = coverCenterX - coverSize * .5;
        double coverTop = coverCenterY - coverSize * .575;

        // 把封面这一帧真正落在屏幕上的矩形回报给接力动画。接力封面画在 onRender 那层 contentScale
        // 之外，所以要先按面板中心把矩形换算过去，两边才在同一坐标空间里，落地才能严丝合缝。
        double coverContentScale = getContentScale(this.alpha);
        double panelCenterX = posX + width * .5;
        double panelCenterY = posY + height * .5;
        CoverHeroTransition.setTarget(
                panelCenterX + (coverLeft - panelCenterX) * coverContentScale,
                panelCenterY + (coverTop - panelCenterY) * coverContentScale,
                coverSize * coverContentScale,
                coverRadius * coverSizePerc * coverContentScale);

        // 飞行期间这张封面连同它的阴影一起让位，交给 CoverHeroTransition 在更外层画：
        // 不让位的话同一张封面会同时出现在两个位置。
        boolean heroOwnsCover = CoverHeroTransition.suppressPanelCover();
        if (alpha <= .004f) return;

        if (!heroOwnsCover) {
            api.getGLStateManager().pushMatrix();
            this.scaleAtPos(posX + width * .5, posY + height * .5, (.925 + (alpha * 0.075)));
            SkijaEffects.bloom(() -> {
                this.roundedRect(coverLeft, coverTop, coverSize, coverSize, coverRadius * coverSizePerc, -.5, 0, 0, 0, alpha * .4f);
            });
            api.getGLStateManager().popMatrix();
        }

        api.getGLStateManager().disableAlpha();

        // 没有新曲目时也不要留着上一张封面淡出：那是换歌交叉淡入用的，停播时它只会挂在原地。
        if (!heroOwnsCover && music != null && prevCover != null && coverAlpha <= .9f) {
            api.getGLStateManager().bindTexture(prevCover.getGlTextureId());
            this.roundedRectTextured(coverLeft, coverTop, coverSize, coverSize, coverRadius * coverSizePerc, alpha);
        }

        // 动态封面(网易云 MP4 抽帧)就绪后优先用它,否则保持静态封面。
        ITextureObject tex = music == null ? null : textureManager.getTexture(
                CloudMusic.preferredCoverLocation(music, music.getCoverLocation()));

        if (tex != null) {
            // coverAlpha 是换歌交叉淡入的进度，让位期间也得照常推进；停住的话转场一结束
            // 会从上一首的封面重新淡入一次。
            coverAlpha = Interpolations.interpolate(coverAlpha, 1.0f, 0.2f);
            if (!heroOwnsCover) {
                api.getGLStateManager().bindTexture(tex.getGlTextureId());
                tex.linearFilter();
                this.roundedRectTextured(coverLeft, coverTop, coverSize, coverSize, coverRadius * coverSizePerc, alpha * coverAlpha);
            }
        }

        double elementsXOffset = coverCenterX - coverSizeMax * .5;
        double elementsYOffset = coverCenterY + coverSizeMax * .45 + 8;

        // 传送带整块贴着底边算，位置与曲目信息无关：信息块的高度会随"这一首有没有别名、
        // 有没有专辑名"变化，让进度条跟着它排版就会每次换歌上下跳一下。
        double volumeBarYOffset = posY + height - Math.max(18, height * .08);
        double progressBarYOffset = volumeBarYOffset - Math.max(TRANSPORT_ZONE_MIN, height * .175);

        double infoBottom = nowPlaying.render(music, elementsXOffset, elementsYOffset, coverSizeMax,
                alpha, progressBarYOffset - INFO_BAR_GAP);
        // 面板矮到连"曲名 + 艺人"两行都塞不进去时让进度条让路：宁可挤了传送带，也不压住文字。
        progressBarYOffset = Math.max(progressBarYOffset, infoBottom + INFO_BAR_GAP);

        // progressbar 背景
        double progressBarWidth = coverSizeMax;

        roundedRect(elementsXOffset, progressBarYOffset - progressBarHeight * .5, progressBarWidth, progressBarHeight, (this.progressBarHeight / 8.0f) * 2.5, hexColor(1, 1, 1, alpha * .5f));

        double currentTimeMillis = snapshot.positionMs;
        double totalTimeMillis = snapshot.durationMs;
        // 「有没有可用的播放时钟」不能问 snapshot.player：镜像态下出声的是外部播放器，
        // player 恒为 null，而进度条会因此整条停在 0、拖动也没反应。
        boolean clockReady = CloudMusic.hasPlaybackClock();
        double perc = !clockReady ? 0 : (progressBarDragging ? progressBarProgressOverride : currentTimeMillis / Math.max(1.0, totalTimeMillis));

        // 播放进度条填充：MC 1.8.9 主 framebuffer 无 stencil，stencil 裁剪失效，改为按 perc 直接画 partial 宽度圆角矩形。
        double fillWidth = progressBarWidth * perc;
        if (fillWidth > 0)
            roundedRect(elementsXOffset, progressBarYOffset - progressBarHeight * .5, fillWidth, progressBarHeight, Math.min((this.progressBarHeight / 8.0f) * 2.5, fillWidth * .5), hexColor(1, 1, 1, alpha));

        boolean hoveringProgressBar = progressBarDragging || this.isHovered(mouseX, mouseY, elementsXOffset, progressBarYOffset - progressBarHeight * .5, progressBarWidth, 8);
        this.progressBarHeight = Interpolations.interpolate(this.progressBarHeight, hoveringProgressBar ? 8 : 5, 0.3f);

        boolean lmbDown = Mouse.isButtonDown(0);
        if (hoveringProgressBar && lmbDown && !prevMouse) {
            prevMouse = true;
            progressBarDragging = true;

            double xDelta = Math.max(0, Math.min(progressBarWidth, (mouseX - elementsXOffset)));
            this.progressBarProgressOverride = xDelta / progressBarWidth;
            updateLyricPositionsImmediate(createLyricViewport(posX, posY, width, height).width,
                    posY + height * lyricFraction(), progressBarProgressOverride * totalTimeMillis);
        }

        if (progressBarDragging) {
            if (!lmbDown) {
                progressBarDragging = false;

                double percent = this.progressBarProgressOverride;

                if (clockReady) {
                    // 与控制栏共用 CloudMusic.seekToMillis()：房间拦截与歌词对齐都在那里统一处理。
                    if (CloudMusic.seekToMillis((float) (percent * totalTimeMillis))) {
                        scrollTarget = scrollOffset = 0;
                    }
                }
            } else {
                double xDelta = Math.max(0, Math.min(progressBarWidth, (mouseX - elementsXOffset)));
                this.progressBarProgressOverride = xDelta / progressBarWidth;
                updateLyricPositionsImmediate(createLyricViewport(posX, posY, width, height).width,
                        posY + height * lyricFraction(), progressBarProgressOverride * totalTimeMillis);
            }
        }

        // curTime
        float curTime = progressBarDragging ? (float) (progressBarProgressOverride * totalTimeMillis) : (float) currentTimeMillis;
        FontManager.pf12bold.drawString(formatDuration(curTime), elementsXOffset, progressBarYOffset + 8, hexColor(1, 1, 1, alpha * .5f));
        String remainingTime = "-" + formatDuration((float) (totalTimeMillis - curTime));
        FontManager.pf12bold.drawString(remainingTime, elementsXOffset + progressBarWidth - FontManager.pf12bold.getStringWidthD(remainingTime), progressBarYOffset + 8, hexColor(1, 1, 1, alpha * .5f));

        double volumeBarWidth = coverSizeMax - FontManager.music40.getStringWidthD("I")
                - FontManager.music40.getStringWidthD("J");
        double volumeBarXOffset = elementsXOffset + FontManager.music40.getStringWidthD("I") - 2;
        // 两个播放器界面都委托到这个确切控件，使拖拽行为、主题颜色、
        //悬停动画和灵动岛反馈保持同步。
        boolean hoveringVolumeBar = volumeControl.render(mouseX, mouseY, elementsXOffset - 8,
                volumeBarYOffset, progressBarWidth + 12.0, alpha);

        if (hoveringProgressBar || hoveringVolumeBar) {
            CursorUtils.setOverride(CursorUtils.HAND);
        }
        playPauseButton.setAlpha(alpha);
        playPauseButton.setWidth(32);
        playPauseButton.setHeight(32);
        playPauseButton.setPosition(volumeBarXOffset + volumeBarWidth * .5 - playPauseButton.getWidth() * .5, progressBarYOffset + (volumeBarYOffset - progressBarYOffset) * .5 - playPauseButton.getHeight() * .5);
        playPauseButton.renderWidget(mouseX, mouseY, 0);
        playPauseButton.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));

        playPauseButton.setBeforeRenderCallback(() -> {
            if (!CloudMusic.isAudibleNow()) {
                playPauseButton.setIcon("G");
            } else {
                playPauseButton.setIcon("F");
            }
        });

        playPauseButton.setOnClickCallback((x, y, i) -> {

            if (i == 0) {
                // 与控制栏、全局快捷键共用 CloudMusic.togglePlayPause()（含一起听房间拦截）。
                CloudMusic.togglePlayPause();
            }

            return true;
        });

        playPauseButton.fontOffsetY = 0;
        prev.setAlpha(alpha);
        prev.setWidth(32);
        prev.setHeight(32);
        prev.setPosition(volumeBarXOffset + volumeBarWidth * .5 - playPauseButton.getWidth() * .5 - 16 - prev.getWidth(), playPauseButton.getY());
        prev.renderWidget(mouseX, mouseY, 0);
        prev.fr = FontManager.music40;
        prev.fontOffsetY = 0;
        prev.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));

        prev.setOnClickCallback((x, y, i) -> {

            if (i == 0) {
                if (CloudMusic.hasPlaybackClock() && CloudMusic.currentlyPlaying != null) CloudMusic.prev();
            }

            return true;
        });

        next.setAlpha(alpha);
        next.setWidth(32);
        next.setHeight(32);
        next.setPosition(volumeBarXOffset + volumeBarWidth * .5 + playPauseButton.getWidth() * .5 + 16, playPauseButton.getY());
        next.renderWidget(mouseX, mouseY, 0);
        next.fr = FontManager.music40;
        next.fontOffsetY = 0;
        next.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));

        next.setOnClickCallback((x, y, i) -> {

            if (i == 0) {
                if (CloudMusic.hasPlaybackClock() && CloudMusic.currentlyPlaying != null) CloudMusic.next();
            }

            return true;
        });
    }

    public void mouseClicked(double mouseX, double mouseY, int mouseButton) {
        // 浮层优先吃掉点击，避免穿透到下面的播放控件。
        if (this.styleQuickPanel.consumeClick(mouseX, mouseY, mouseButton)) {
            return;
        }
        playPauseButton.onMouseClickReceived(mouseX, mouseY, mouseButton);
        prev.onMouseClickReceived(mouseX, mouseY, mouseButton);
        next.onMouseClickReceived(mouseX, mouseY, mouseButton);
    }

    private String formatDuration(float totalMillis) {
        return LyricsPanelGeometry.formatDuration(totalMillis);
    }

    private void renderBackground(double posX, double posY, double width, double height, float alpha, CloudMusic.PlaybackSnapshot snapshot) {
        if (alpha <= .004f) return;
        Music current = snapshot.music;

        // 新背景是可选的；着色器不可用或用户选择旧模式时，原模糊封面路径完全保留。
        if (FullscreenLyricBackground.current() == FullscreenLyricBackground.LATENT
                && latentBackground.render(posX, posY, width, height, alpha,
                        current == null ? null : current.getCoverPalette())) {
            if (HudConfig.latentOverlayEnabled && HudConfig.latentOverlayOpacity > .001f) {
                int overlayColor = NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND);
                Rect.draw(posX, posY, width, height,
                        reAlpha(overlayColor, alpha * HudConfig.latentOverlayOpacity));
            }
            return;
        }

        TextureManager textureManager = TextureManager.getInstance();
        // 镜像中且外部播放器给了封面时，这张底由那张图现算（CloudMusic 那一处统一分叉）。
        Location musicCoverBlurred = CloudMusic.preferredBlurredCoverLocation(current);
        ITextureObject texBg = current == null ? null : textureManager.getTexture(musicCoverBlurred);

        if (current != null && current != prevMusic) {

            if (prevMusic != null) musicBgAlpha = 0.0f;

            prevBg = prevMusic == null ? null : textureManager.getTexture(prevMusic.getBlurredCoverLocation());
            prevCover = prevMusic == null ? null : textureManager.getTexture(prevMusic.getCoverLocation());
            prevMusic = current;
            coverAlpha = 0.0f;
        }

        // 模糊封面刻意超大，使裁剪在
        //播放器缩放时保持稳定。不要仅依赖父模板：辅助 FBO/着色器
        //过程可能暂时禁用它。一个投影 scissor 是硬边界，
        //使这个超大纹理在每个缩放下都保持在实时播放器矩形内。
        StencilClipManager.restoreActiveClip();
        ScissorClipManager.begin(posX, posY, width, height);
        try {
            if (texBg != null || prevBg != null) {
                api.getGLStateManager().pushMatrix();
                try {
                    double bgSize = Math.max(width, height);

                    boolean skipPreviousCover = texBg != null
                            && TransitionPerformanceBudget.skipLyricBackgroundCrossfade();
                    if (skipPreviousCover) musicBgAlpha = 1.0f;
                    if (prevBg != null && musicBgAlpha < 0.99f && !skipPreviousCover) {
                        int glTextureId = prevBg.getGlTextureId();
                        api.getGLStateManager().bindTexture(glTextureId);
                        prevBg.linearFilter();
                        api.getGLStateManager().color(1, 1, 1, alpha);
                        Image.draw(posX + width * .5 - bgSize * .5, posY + height * .5 - bgSize * .5,
                                bgSize, bgSize, Image.Type.NoColor);
                    }

                    if (texBg != null) {
                        this.musicBgAlpha = Interpolations.interpolate(this.musicBgAlpha, 1.0f,
                                prevBg == null ? 0.15f : 0.05f);
                        api.getGLStateManager().bindTexture(texBg.getGlTextureId());
                        texBg.linearFilter();
                        api.getGLStateManager().color(1, 1, 1, alpha * this.musicBgAlpha);
                        Image.draw(posX + width * .5 - bgSize * .5, posY + height * .5 - bgSize * .5,
                                bgSize, bgSize, Image.Type.NoColor);
                    }
                } finally {
                    api.getGLStateManager().popMatrix();
                }
            }

            Rect.draw(posX, posY, width, height, hexColor(0, 0, 0, alpha * .35f));
        } finally {
            ScissorClipManager.end();
            // 图像渲染改变原始 GL 状态；外层圆角播放器裁剪必须
            //对之后渲染的控件和歌词层保持有效。
            StencilClipManager.restoreActiveClip();
        }
    }


    /**
     * 横向把图层 alpha 从 1 渐变到 0，做出填涂前沿的羽化。
     *
     * <p>{@code DST_IN} 的效果是 {@code Da *= Sa}，而且只作用在实际画到的那一块上，
     * 所以羽化段与「还没唱到」那一段可以各画一次、互不干扰。
     */
    private static void applyKaraokeFeather(io.github.humbleui.skija.Canvas canvas,
                                            double x, double y, double width, double height) {
        try (io.github.humbleui.skija.Shader gradient =
                     io.github.humbleui.skija.Shader.makeLinearGradient(
                             (float) x, (float) y, (float) (x + width), (float) y,
                             new int[]{0xFFFFFFFF, 0x00FFFFFF})) {
            io.github.humbleui.skija.Paint paint = SkijaGfx.fillShader(gradient);
            paint.setBlendMode(io.github.humbleui.skija.BlendMode.DST_IN);
            canvas.drawRect(io.github.humbleui.types.Rect.makeXYWH(
                    (float) x, (float) y, (float) width, (float) height), paint);
            paint.setShader(null);
            paint.setBlendMode(SkijaGfx.blendMode());
        }
    }

    /** 把「还没唱到」的那一段图层 alpha 乘成 0。 */
    private static void eraseKaraokeTail(io.github.humbleui.skija.Canvas canvas,
                                         double x, double y, double width, double height) {
        io.github.humbleui.skija.Paint paint = SkijaGfx.fill(0x00000000);
        paint.setBlendMode(io.github.humbleui.skija.BlendMode.DST_IN);
        canvas.drawRect(io.github.humbleui.types.Rect.makeXYWH(
                (float) x, (float) y, (float) width, (float) height), paint);
        paint.setBlendMode(SkijaGfx.blendMode());
    }
}
