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
import com.muoniumplayer.core.rendering.entities.impl.ScrollText;
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
     * 退场时低于这个不透明度就直接停止绘制并宣告关闭。
     *
     * <p>0.09 不是随手取的：{@code NCMScreen} 用 {@code (int)(alpha*255)} 画压暗层，0.09 对应
     * 23/255，叠在已经恢复绘制的播放器界面上肉眼分辨不出；再低只是白白多画几帧整层内容。</p>
     */
    private static final float CLOSE_ALPHA_EPSILON = 0.09f;


    Timer scrollOffsetResetTimer = new Timer();

    double coverSize = (CloudMusic.player == null || CloudMusic.player.isPausing()) ? this.getCoverSizeMin() : this.getCoverSizeMax();
    float coverAlpha = 1f;

    boolean progressBarDragging = false;
    double progressBarProgressOverride = 0;
    double progressBarHeight = 8, volumeBarHeight = 8;
    private final VolumeControl volumeControl = new VolumeControl();

    boolean prevMouse = false;

    /** 左上角「A-Z」逐字特效快捷面板；只影响全屏歌词页的两项配置，实时预览。 */
    private final LyricStyleQuickPanel styleQuickPanel = new LyricStyleQuickPanel();
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
        return renderers;
    }

    /** 当前样式对应的渲染器；{@code null} 表示走经典的滚动歌词。 */
    private FullscreenLyricRenderer currentStyleRenderer() {
        int index = FullscreenLyricStyle.current().ordinal();
        if (index < 0 || index >= styleRenderers.length) return null;
        return styleRenderers[index];
    }

    ScrollText stMusicName = new ScrollText(), stArtists = new ScrollText();
    IconWidget playPauseButton = new IconWidget("G", FontManager.music40, 0, 0, 24, 24);
    IconWidget prev = new IconWidget("E", FontManager.music40, 0, 0, 32, 32);
    IconWidget next = new IconWidget("H", FontManager.music40, 0, 0, 32, 32);

    private double lastViewportLyricWidth = -1;
    private double lastViewportAnchorY = Double.NaN;

    /**
     * The single authoritative viewport for all lyric layers. It is derived from the
     * player''s live bounds each frame, so text, KTV composition and blur all follow resize.
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
     * Keeps the animated lyric layer inside the player while it fades out.
     *
     * The old close animation used a maximum scale of 1.10, which made the
     * background, blur and glyph quads exceed the dynamically sized player.
     * At full opacity the content still uses the exact player size; during the
     * transition it only contracts, so resizing the player cannot expose a
     * second, larger lyric viewport.
     */
    private static double getContentScale(float alpha) {
        return LyricsPanelGeometry.contentScale(alpha);
    }

    /** Smooths the KTV sweep without decoupling it from the actual audio position. */
    private static double smoothKaraokeProgress(double value) {
        return LyricsPanelGeometry.smoothKaraokeProgress(value);
    }

    /** Individual characters ease into the active state in a short cascading wave. */
    private static double getCharacterKaraokeProgress(double wordProgress, int characterIndex) {
        return LyricsPanelGeometry.characterKaraokeProgress(wordProgress, characterIndex);
    }

    /** 归一化后的逐字发光上限；为 0 表示整条发光链路完全关闭。 */
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
        return karaokeGlowStrength() <= .004 ? 0 : 6;
    }

    /**
     * 一个字的发光，亮度只取决于"这个字已经唱了多久"。
     *
     * <p>{@code progress} 是该字自己的演唱进度（0 = 还没唱到，1 = 唱完），所以同一个字词里
     * 靠前的字先亮、靠后的字后亮，整体是一道跟着人声推进的光。上限由
     * {@link HudConfig#fullscreenGlowStrength} 控制。</p>
     *
     * <p>1.8.9 没有可用的后处理管线，这里沿用项目里其它发光的做法：把同一个字形按环形偏移
     * 多画几遍、逐层降低透明度，得到一个免着色器的柔和光晕。</p>
     */
    private void drawKaraokeCharacterGlow(String text, double x, double y,
                                          double progress, float alpha) {
        double strength = karaokeGlowStrength() * Math.max(0.0, Math.min(1.0, progress));
        if (strength <= .004 || alpha <= .004f) {
            return;
        }

        double radius = 1.0 + 2.0 * strength;
        drawKaraokeGlowRing(text, x, y, radius, (float) (alpha * strength * .34));
        drawKaraokeGlowRing(text, x, y, radius * 1.7, (float) (alpha * strength * .15));
    }

    private void drawKaraokeGlowRing(String text, double x, double y, double radius, float ringAlpha) {
        if (ringAlpha <= .002f) {
            return;
        }
        int cardinal = hexColor(1f, 1f, 1f, ringAlpha);
        int diagonal = hexColor(1f, 1f, 1f, ringAlpha * .72f);
        double diagonalRadius = radius * .7071;
        FontManager.pf65bold.drawString(text, x - radius, y, cardinal);
        FontManager.pf65bold.drawString(text, x + radius, y, cardinal);
        FontManager.pf65bold.drawString(text, x, y - radius, cardinal);
        FontManager.pf65bold.drawString(text, x, y + radius, cardinal);
        FontManager.pf65bold.drawString(text, x - diagonalRadius, y - diagonalRadius, diagonal);
        FontManager.pf65bold.drawString(text, x + diagonalRadius, y - diagonalRadius, diagonal);
        FontManager.pf65bold.drawString(text, x - diagonalRadius, y + diagonalRadius, diagonal);
        FontManager.pf65bold.drawString(text, x + diagonalRadius, y + diagonalRadius, diagonal);
    }

    private static double getLyricLineSpacing() {
        return LyricsPanelGeometry.lyricLineSpacing();
    }

    private static double lyricFraction() {
        return LyricsPanelGeometry.lyricAnchorFraction();
    }

    /**
     * The lyric column deliberately uses the current player bounds rather than a global
     * screen fraction.  The right and vertical padding keep animated glyphs, their glow and
     * the blur kernel inside the live player window at every configured player scale.
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
     * Rebuilds the complete lyric layout around one anchor line. Seeking can change the
     * active line by many entries at once, so every line must use the same anchor instead
     * of leaving a mixture of coordinates from the old and new playback positions.
     */
    private static void layoutLyricPositionsImmediately(double width, double anchorY, LyricLine anchorLyric) {
        if (anchorLyric == null) return;

        synchronized (CloudMusic.lyrics) {
            int anchorIndex = CloudMusic.lyrics.indexOf(anchorLyric);
            if (anchorIndex < 0 || anchorIndex >= CloudMusic.lyrics.size()) return;

            // Heights affect all following coordinates. Calculate them before assigning
            // positions so a wrapped/translated line cannot overlap its neighbour.
            for (LyricLine lyric : CloudMusic.lyrics) {
                // 对唱行按自己那一列的宽度算高度，否则折行数与渲染时不一致，行与行会叠在一起。
                lyric.computeHeight(LyricDuetGroups.columnWidth(width, lyric.duetSlot));
            }

            // A duet is anchored as one block: its first line leads, and the block is lifted by
            // half of the extra rows so the pair straddles the anchor instead of pushing the
            // second singer out of the reading position.
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
        // setPosition also clears the spring velocity/queued target. This is required for
        // seeks, otherwise a line can keep interpolating from the previous timeline.
        lyric.spring.setPosition(positionY);
    }

    /**
     * The progress bar is dragged continuously. Keeping this as an immediate layout avoids
     * blending the old anchor positions with the preview anchor positions during a seek.
     */
    public static void updateLyricPositions(double width, double playbackProgress) {
        updateLyricPositionsImmediate(width, playbackProgress);
    }
    private static long getLyricInterpolationWaitTimeMillis() {
        return 75;
    }

    private static void resetLyricStatus() {
        CloudMusic.resetLyricStatus();
    }

    public void onInit() {
        resetLyricStatus();
        // 换歌重开歌词页时清掉各样式的上一行状态，否则会看到上一首的内容退场一次。
        for (int i = 0; i < styleRenderers.length; i++) {
            if (styleRenderers[i] != null) styleRenderers[i].reset();
        }
    }

    public void close() {
        closing = true;
    }

    public boolean shouldClose() {
        return closing && alpha <= CLOSE_ALPHA_EPSILON;
    }

    public void onRender(double mouseX, double mouseY, double posX, double posY, double width, double height, int dWheel) {

        if (prevMouse && !Mouse.isButtonDown(0)) prevMouse = false;

        // 退出比进入快一档：0.30 的逼近系数要十几帧才跌到原来的 0.02 关闭阈值，那段时间里
        // 播放器主界面已经露出来了（NCMScreen 在 alpha<=.9 就恢复绘制底层），而这一层还在画
        // 压暗遮罩、进度条与分隔线，看起来就是"退出后遮罩和线条延迟消失"。
        alpha = Interpolations.interpolate(alpha, closing ? 0.0f : 1f, closing ? 0.45f : 0.3f);
        // 剩下这几个百分点已经看不见了，但仍然会整层绘制一遍。alpha 继续衰减（shouldClose 靠它
        // 判定），但不再画任何东西，于是"残留"被彻底掐掉而不是靠肉眼分辨不出来。
        if (closing && alpha <= CLOSE_ALPHA_EPSILON) return;

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
        this.renderLyrics(mouseX, mouseY, posX, posY, width, height, dWheel, alpha, snapshot);
        // 快捷面板画在最后：它是浮层，必须盖在封面与歌词之上。
        this.styleQuickPanel.render(mouseX, mouseY, posX, posY, height, alpha);
        api.getGLStateManager().popMatrix();
    }

    private void renderLyrics(double mouseX, double mouseY, double posX, double posY, double width, double height, int dWheel, float alpha, CloudMusic.PlaybackSnapshot snapshot) {

        if (CloudMusic.lyrics.isEmpty()) return;
        // 切歌后歌词还没回来时，列表里躺着的是上一首的时间轴。宁可这一瞬空着，也不要用新歌的
        // 进度去画旧歌的行 —— 那才是用户看到的"切歌歌词不同步"。
        if (!CloudMusic.lyricsMatchCurrentTrack()) return;

        boolean playerNotReady = snapshot.player == null;
        double totalTimeMillis = snapshot.durationMs;
        double overridePlaybackProgress = progressBarProgressOverride * totalTimeMillis;
        double songProgress = playerNotReady ? 0 : (progressBarDragging ? overridePlaybackProgress : snapshot.positionMs);

        LyricViewport lyricViewport = createLyricViewport(posX, posY, width, height);
        if (lyricViewport.width <= .5 || lyricViewport.height <= .5) return;

        double lyricsWidth = lyricViewport.width;
        LyricLine displayedCurrentLyric = progressBarDragging ? CloudMusic.findCurrentLyric(overridePlaybackProgress) : CloudMusic.currentLyric;

        // 除「逐行滚动」以外的样式都是独立的绘制方式（不滚动、不做 KTV 填涂），
        // 因此在这里就整段接管，下面经典样式的滚动、模糊、译文与 FBO 合成一律不执行。
        FullscreenLyricRenderer styleRenderer = currentStyleRenderer();
        if (styleRenderer != null) {
            styleRenderer.render(lyricViewport.left, lyricViewport.top,
                    lyricViewport.width, lyricViewport.height, alpha, songProgress, displayedCurrentLyric);
            return;
        }
        this.updateLyricPositions(posY, height, lyricsWidth, displayedCurrentLyric);

        List<Runnable> blurRects = new ArrayList<>();

        boolean hoveringLyrics = isHovered(mouseX, mouseY, lyricViewport.left, lyricViewport.top,
                lyricViewport.width, lyricViewport.height);

        if (hoveringLyrics && dWheel != 0) {

            double strength = 24;

            if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)) strength *= 2;

            if (dWheel > 0) scrollTarget += strength;
            else scrollTarget -= strength;

            scrollOffsetResetTimer.reset();

//            this.scrollTarget = Math.min(this.scrollTarget, 0);
        }

        if (scrollOffsetResetTimer.isDelayed(3000)) {
            scrollTarget = 0;
        }

        scrollOffset = Interpolations.interpolate(scrollOffset, scrollTarget, 0.25f);

        double lyricRenderOffsetX = lyricViewport.left;
        double lyricsViewportTop = lyricViewport.top;
        double lyricsViewportBottom = lyricViewport.bottom();
        double lyricsViewportHeight = lyricViewport.height;
        double lyricsViewportLeft = lyricViewport.left;
        double lyricsViewportRight = lyricViewport.right();

        // The parent player clip is rounded; this nested clip is the hard text viewport.
        // It remains on the Minecraft framebuffer stack and is restored after every KTV FBO pass.
        StencilClipManager.beginClip(() -> Rect.draw(lyricsViewportLeft, lyricsViewportTop,
                lyricsWidth, lyricsViewportHeight, -1));
        // The KTV renderer temporarily binds small word-sized framebuffers.  Stencil is
        // intentionally disabled there, so keep a screen-space clip active for every
        // direct lyric draw on the Minecraft framebuffer as the non-negotiable boundary.
        ScissorClipManager.begin(lyricsViewportLeft, lyricsViewportTop, lyricsWidth, lyricsViewportHeight);
        boolean lyricScissorActive = true;
        try {
            for (int k = 0; k < CloudMusic.lyrics.size(); k++) {
            LyricLine lyric = CloudMusic.lyrics.get(k);

            if (lyric.posY + lyric.height + getLyricLineSpacing() + scrollOffset < lyricsViewportTop) {
                continue;
            }

            if (lyric.posY + scrollOffset > lyricsViewportBottom) {
                break;
            }

            LyricLine currentLyric = displayedCurrentLyric;
            int indexOf = CloudMusic.lyrics.indexOf(currentLyric);

            // 对唱段落里同组的行一起算作当前行：两位歌手同时在唱，只点亮一行会让另一行看起来
            // 像已经唱完或还没开始。
            boolean isCurrentLyric = LyricDuetGroups.isActive(lyric, currentLyric);
            lyric.alpha = Interpolations.interpolate(lyric.alpha, isCurrentLyric ? 1f : 0f, isCurrentLyric ? 0.1f : .05f);
            boolean isHovering = isHovered(mouseX, mouseY - scrollOffset, lyricRenderOffsetX, lyric.posY, lyricsWidth, lyric.height);
            lyric.hoveringAlpha = Interpolations.interpolate(lyric.hoveringAlpha, isHovering ? 1f : 0f, 0.2f);
            // 失焦模糊按到"活跃块"的距离算，而不是到单一当前行的距离：对唱组内的行距离为 0，
            // 否则同时点亮的另一位歌手会被糊掉。
            int groupFirst = LyricDuetGroups.groupStart(CloudMusic.lyrics, indexOf);
            int groupLast = LyricDuetGroups.groupEnd(CloudMusic.lyrics, indexOf);
            int activeDistance = k < groupFirst ? groupFirst - k : (k > groupLast ? k - groupLast : 0);
            lyric.blurAlpha = Interpolations.interpolate(lyric.blurAlpha, !hoveringLyrics ? Math.min(1f, activeDistance * .85f) : 0f, 0.05f);

            if (isHovering) {
                CursorUtils.setOverride(CursorUtils.HAND);
            }

            if (isHovering && Mouse.isButtonDown(0) && !prevMouse) {
                prevMouse = true;
                if (snapshot.player != null) {
                    // 点歌词行跳转也走 CloudMusic.seekToMillis()：一起听房间里这一步会变成房间指令，
                    // 普通成员则被挡住，不会一个人从全房进度里脱队。
                    if (CloudMusic.seekToMillis(lyric.timestamp)) {
                        resetLyricStatus();
                    }
                }

                scrollTarget = 0;
            }

            // 对唱行分左右两列：偶数序号靠左、奇数序号靠右，两位歌手同时点亮时一眼能分出是谁在唱。
            // 独唱行的列宽 == 整个歌词区，几何与改动之前完全一致。
            double columnWidth = LyricDuetGroups.columnWidth(lyricsWidth, lyric.duetSlot);
            double columnLeft = LyricDuetGroups.columnLeft(lyricRenderOffsetX, lyricsWidth, lyric.duetSlot);
            boolean columnRightAligned = LyricDuetGroups.rightAligned(lyric.duetSlot);

            if (lyric.hoveringAlpha >= .02f)
                roundedRect(columnLeft - 4, lyric.posY + scrollOffset + lyric.reboundAnimation, columnWidth + lyric.reboundAnimation, lyric.height + 2, 8, 4 + 2 * Easing.EASE_IN_OUT_QUAD.getFunction().apply((double) lyric.hoveringAlpha), 1, 1, 1, alpha * lyric.hoveringAlpha * .15f);

            double lineStep = FontManager.pf65bold.getHeight() * .85 + 4;
            double renderX = columnLeft + lyric.reboundAnimation;
            double renderBaseY = lyric.posY + lyric.reboundAnimation + scrollOffset;
            double renderY = renderBaseY;

            lyric.reboundAnimation = Interpolations.interpolate(lyric.reboundAnimation, isCurrentLyric ? 2f : 0f, 0.1f);

            List<LyricLine.Word> words = lyric.words;
            if (!words.isEmpty()) {
                // 折行与每个字词的落点先整行算好，再逐个绘制。原来是边画边判断"超出右边界就换行"，
                // 那套增量逻辑没法做右对齐（右对齐需要先知道整行有多宽），而且判定用的是 >=，
                // 右对齐时行末字词恰好压在边界上会被误判成又要换行。
                WordPlacement placement = layoutWords(words, columnLeft, columnWidth, columnRightAligned);
                for (int wordIndex = 0; wordIndex < words.size(); wordIndex++) {
                    LyricLine.Word word = words.get(wordIndex);
                    double wordWidth = FontManager.pf65bold.getStringWidthD(word.word);

                    renderX = placement.x[wordIndex] + lyric.reboundAnimation;
                    renderY = renderBaseY + placement.row[wordIndex] * lineStep;

                    if (!lyric.renderEmphasizes) Arrays.fill(word.emphasizes, 2);

                    double emphasizeWholeWord = word.emphasizes[0];

                    char[] charArray = word.word.toCharArray();

                    double emphasizeTarget = 1;
                    double emphasizeSpeed = 0.05;

                    /*
                     * 逐字放大必须让「字形本体」和「KTV 填涂遮罩」共用同一个变换。
                     *
                     * 旧实现只给 FBO 合成出来的高亮层套上 karaokeScale，未唱底层和唱完后的
                     * 文本仍以原始尺寸直接画在 Minecraft framebuffer 上。于是同一个字同时存在
                     * 两份大小不同的拷贝（重影）；而且这个字未唱的部分来自没放大的底层、已唱的
                     * 部分来自放大后的合成层，两者交界处必然对不上（脱节）。
                     *
                     * 因此缩放倍率与锚点在字词开头就一次算好，本字词后面每一次直接绘制都套用
                     * 同一份变换。布局推进量 renderX / wordWidth 仍然是未放大的，换行不会抖动。
                     */
                    double progress = word.getProgress(songProgress);
                    double easedProgress = smoothKaraokeProgress(progress);
                    double stringWidthD = wordWidth;
                    // 宽度向上取整：直接截断会削掉最后一个字形的右边缘，也会让 KTV 前沿
                    // (progress * stringWidthD) 超出纹理宽度。
                    double karaokeQuadWidth = Math.max(2, Math.ceil(stringWidthD));
                    double karaokePulse = Math.sin(Math.PI * easedProgress);
                    // 逐字放大程度直接取配置值（HudConfig 已把它夹在 0~0.28）。这里原先又写死了
                    // 0.14 的上限，于是"逐字放大"滑块拖过一半之后完全没有反应。
                    double karaokeScale = 1.0
                            + Math.max(0.0, HudConfig.currentWordScale) * karaokePulse;
                    // 锚点默认在字词中心，但要约束在歌词视口内：行首字词的左边缘正好压在视口
                    // 裁剪边界上，按中心放大会有一半放大量被裁掉（首字缺一块），因此贴边时把
                    // 锚点收拢到边界，只向视口内侧生长。
                    double karaokeAnchorX = LyricsPanelGeometry.clampScaleAnchorX(renderX, karaokeQuadWidth,
                            karaokeScale, lyricsViewportLeft, lyricsViewportRight);
                    double karaokeAnchorY = renderY + FontManager.pf65bold.getHeight() * .5;
                    boolean karaokeScaled = karaokeScale > 1.0001;

                    if (karaokeScaled) {
                        api.getGLStateManager().pushMatrix();
                        api.getGLStateManager().translate(karaokeAnchorX, karaokeAnchorY, 0);
                        api.getGLStateManager().scale(karaokeScale, karaokeScale, 1);
                        api.getGLStateManager().translate(-karaokeAnchorX, -karaokeAnchorY, 0);
                    }
                    try {
                        if (isCurrentLyric) {
                            if (charArray.length > 1) {
                                double x = renderX;
                                for (int j = 0; j < charArray.length; j++) {
                                    char c = charArray[j];

                                    FontManager.pf65bold.drawString(String.valueOf(c), x, renderY - word.emphasizes[j], hexColor(1, 1, 1, alpha * .5f));
                                    x += FontManager.pf65bold.getCharWidth(c, j + 1 < charArray.length ? charArray[j + 1] : '\0');
                                }
                            } else {
                                FontManager.pf65bold.drawString(word.word, renderX, renderY - word.emphasizes[0], hexColor(1, 1, 1, alpha * .5f));
                            }
                        } else {
                            FontManager.pf65bold.drawString(word.word, renderX, renderY, hexColor(1, 1, 1, alpha * .5f));
                        }
                    } finally {
                        if (karaokeScaled) {
                            api.getGLStateManager().popMatrix();
                        }
                    }

                    if (isCurrentLyric || CloudMusic.lyrics.indexOf(currentLyric) - k <= 1) {
                        boolean shouldClip = progress > .001 && progress < .999;

                        if (progress >= .999) {
                            // 唱完后仍保留抬升，并且和上面的底层、下面的 KTV 合成层
                            // 共用同一个缩放变换，避免任何一层单独放大造成错位。
                            if (karaokeScaled) {
                                api.getGLStateManager().pushMatrix();
                                api.getGLStateManager().translate(karaokeAnchorX, karaokeAnchorY, 0);
                                api.getGLStateManager().scale(karaokeScale, karaokeScale, 1);
                                api.getGLStateManager().translate(-karaokeAnchorX, -karaokeAnchorY, 0);
                            }
                            try {
                                double x = renderX;
                                for (int j = 0; j < charArray.length; j++) {
                                    char c = charArray[j];
                                    if (lyric.renderEmphasizes) {
                                        word.emphasizes[j] = Interpolations.interpolate(word.emphasizes[j], 1.85, .12);
                                    }
                                    // 唱完的字词不再走 KTV 纹理合成，发光要在这里以满亮度补上，
                                    // 否则字词唱完的那一帧发光会突然消失。
                                    drawKaraokeCharacterGlow(String.valueOf(c), x,
                                            renderY - word.emphasizes[j], 1.0, alpha * lyric.alpha);
                                    FontManager.pf65bold.drawString(String.valueOf(c), x,
                                            renderY - word.emphasizes[j], hexColor(1, 1, 1, alpha * lyric.alpha));
                                    x += FontManager.pf65bold.getCharWidth(c, j + 1 < charArray.length ? charArray[j + 1] : '\0');
                                }
                            } finally {
                                if (karaokeScaled) {
                                    api.getGLStateManager().popMatrix();
                                }
                            }
                        }

                        if (shouldClip) {
                            // glowPad 是逐字发光需要的四周留白：发光围绕字形向外扩，
                            // 不留白就会被图层边界切成方块。发光关闭时留白为 0，
                            // 此时图层尺寸、遮罩坐标与合成区和加发光之前完全一致（零行为变化）。
                            int glowPad = karaokeGlowPadding();
                            int quadWidth = (int) karaokeQuadWidth;
                            double layerWidth = quadWidth + glowPad * 2;
                            double layerHeight = FontManager.pf65bold.getHeight() + 6 + glowPad * 2;

                            // A wider alpha feather makes the colour sweep melt into the
                            // base lyric instead of switching colour at a sharp boundary.
                            double gradientWidth = Math.min(Math.max(10.0, FontManager.pf65bold.getHeight() * .42),
                                    Math.max(10.0, stringWidthD * .28));

                            // 当前字词在演唱期间平滑放大，布局宽度保持不变，避免换行抖动。
                            // 倍率与锚点在字词开头就算好了（见上），未唱底层、唱完后的文本和
                            // 这里的 KTV 合成层共用同一份变换，因此不会出现「遮罩放大、字形没
                            // 放大」的重影与脱节。
                            api.getGLStateManager().pushMatrix();
                            api.getGLStateManager().translate(karaokeAnchorX, karaokeAnchorY, 0);
                            api.getGLStateManager().scale(karaokeScale, karaokeScale, 1);
                            api.getGLStateManager().translate(-karaokeAnchorX, -karaokeAnchorY, 0);

                            // 旧实现：把字形与发光画进一张离屏纹理、把填涂遮罩画进第二张离屏纹理，
                            // 再用自写的 stencil.frag 按 base.a * mask.a 合成。为此还要自己压一套
                            // 正交投影（离屏纹理是像素尺寸的）、挂起裁剪盒（屏幕空间的裁剪在离屏
                            // 坐标系里会把整张纹理裁空）、并在回到主画面后重新断言圆角裁剪。
                            //
                            // 换成 Skia 只需要一个「刚好这么大」的图层：字形与发光照原样画上去，
                            // 再用一条横向 alpha 渐变以 DST_IN 把填涂进度乘下去。
                            // 没有离屏纹理、不用自己搭投影、也不用挂起再恢复裁剪——
                            // 图层继承外层裁剪本来就是想要的行为。
                            double layerOriginX = renderX - glowPad;
                            double layerOriginY = renderY - 2 - glowPad;
                            io.github.humbleui.skija.Canvas ktvCanvas = SkijaGfx.canvas();
                            int ktvLayer = ktvCanvas == null ? -1 : ktvCanvas.saveLayer(
                                    io.github.humbleui.types.Rect.makeXYWH(
                                            (float) layerOriginX, (float) layerOriginY,
                                            (float) layerWidth, (float) layerHeight), null);
                            try {
                                // 字形与逐字发光。旧实现在离屏纹理的局部坐标里从 x = glowPad 起画，
                                // 而局部 glowPad 正对应屏幕的 renderX；局部 characterY = 2 + glowPad - lift
                                // 正对应屏幕的 renderY - lift。所以直接按屏幕坐标画，几何完全一致。
                                double x = renderX;
                                double characterTimeline = progress * charArray.length;
                                for (int j = 0; j < charArray.length; j++) {
                                    char c = charArray[j];
                                    double characterProgress = getCharacterKaraokeProgress(characterTimeline, j);
                                    if (lyric.renderEmphasizes) {
                                        double liftTarget = 1.85 * characterProgress;
                                        word.emphasizes[j] = Interpolations.interpolate(word.emphasizes[j], liftTarget, .16);
                                    }

                                    double characterAlpha = .70 + .30 * characterProgress;
                                    double characterY = renderY - word.emphasizes[j];
                                    // 发光画在字形底下，亮度只跟「这个字已经唱了多久」有关。
                                    // 它和字形在同一个图层里，最终会被 KTV 遮罩一起裁掉未唱的部分，
                                    // 所以发光是随着填涂前沿一个字一个字亮起来的。
                                    drawKaraokeCharacterGlow(String.valueOf(c), x, characterY,
                                            characterProgress, (float) (alpha * lyric.alpha));
                                    FontManager.pf65bold.drawString(String.valueOf(c), x, characterY,
                                            hexColor(1, 1, 1, (float) (alpha * lyric.alpha * characterAlpha)));
                                    x += FontManager.pf65bold.getCharWidth(c, j + 1 < charArray.length ? charArray[j + 1] : '\0');
                                }

                                // 填涂遮罩。严格以真实播放前沿裁剪，渐变只位于已播放区域内部。
                                // 左侧那一段 glowPad 留白一并放行，否则行首那个字向左扩散的发光会被切掉。
                                if (ktvCanvas != null) {
                                    double front = progress * stringWidthD;
                                    double activeGradientWidth = Math.min(gradientWidth, front);
                                    double solidWidth = Math.max(0.0, front - activeGradientWidth);
                                    double featherLeft = layerOriginX + glowPad + solidWidth;
                                    double featherRight = layerOriginX + glowPad + front;
                                    if (featherRight - featherLeft > .001) {
                                        applyKaraokeFeather(ktvCanvas, featherLeft, layerOriginY,
                                                featherRight - featherLeft, layerHeight);
                                    }
                                    double tailLeft = Math.max(featherRight, layerOriginX);
                                    double tailRight = layerOriginX + layerWidth;
                                    if (tailRight - tailLeft > .001) {
                                        eraseKaraokeTail(ktvCanvas, tailLeft, layerOriginY,
                                                tailRight - tailLeft, layerHeight);
                                    }
                                }
                            } finally {
                                if (ktvLayer >= 0) {
                                    ktvCanvas.restoreToCount(ktvLayer);
                                }
                            }
                            api.getGLStateManager().popMatrix();
                        }

                    } else {
                        FontManager.pf65bold.drawString(word.word, renderX, renderY - emphasizeWholeWord, hexColor(1, 1, 1, alpha * lyric.alpha));
                    }

                }
            } else {
                String[] strings = FontManager.pf65bold.fitWidth(lyric.lyric, columnWidth);

                for (String string : strings) {
                    double lineX = columnRightAligned
                            ? columnLeft + columnWidth - FontManager.pf65bold.getStringWidthD(string)
                            + lyric.reboundAnimation
                            : renderX;
                    FontManager.pf65bold.drawString(string, lineX, renderY, hexColor(1, 1, 1, alpha * ((lyric.alpha * .6f) + .4f)));
                    renderY += lineStep;
                }

                renderY -= lineStep;
            }

            if (lyric.translationText != null) {
                double translationY = renderY + FontManager.pf65bold.getHeight() * .85 + 8;

                String[] strings = FontManager.pf34bold.fitWidth(lyric.translationText, columnWidth);
                for (String string : strings) {
                    // 译文跟随本行所在列的对齐方式，否则对唱右列的歌词右对齐、译文却贴在左边。
                    double translationX = columnRightAligned
                            ? columnLeft + columnWidth - FontManager.pf34bold.getStringWidthD(string)
                            + lyric.reboundAnimation
                            : columnLeft + lyric.reboundAnimation;
                    FontManager.pf34bold.drawString(string, translationX, translationY, hexColor(1, 1, 1, alpha * .75f * ((lyric.alpha * .6f) + .4f)));
                    translationY += FontManager.pf34bold.getHeight() + 4;
                }
//                FontManager.pf34bold.drawString(lyric.translationText, translationX, translationY, hexColor(1, 1, 1, alpha * .75f * ((lyric.alpha * .6f) + .4f)));
            }

            // 先把源矩形与歌词视口求交，减少模糊输入在视口外的扩散。
            double blurLeft = Math.max(lyricsViewportLeft, lyricRenderOffsetX - 4);
            double blurTop = Math.max(lyricsViewportTop, lyric.posY + scrollOffset);
            double blurRight = Math.min(lyricsViewportRight, lyricRenderOffsetX + lyricsWidth);
            double blurBottom = Math.min(lyricsViewportBottom, lyric.posY + scrollOffset + lyric.height + 8);
            if (blurRight > blurLeft && blurBottom > blurTop) {
                double clippedBlurWidth = blurRight - blurLeft;
                double clippedBlurHeight = blurBottom - blurTop;
                // 旧实现把这个矩形当作「模糊遮罩」画进离屏 FBO，再由 blur.frag 按它的 alpha
                // 把全屏模糊结果混合回来。Skia 用带 backdrop 的图层一步做完同一件事，
                // 所以这里登记的就是最终那次就地模糊；矩形与 alpha 的算式一字未改。
                float blurStrength = (float) (alpha * lyric.blurAlpha);
                blurRects.add(() -> SkijaEffects.backdropBlur(blurLeft, blurTop,
                        clippedBlurWidth, clippedBlurHeight,
                        SkijaEffects.LYRIC_BLUR_SIGMA, blurStrength));
            }
        }

        // The blur input/output framebuffers use the full game resolution.  Keep the same
        // lyric viewport scissor active through both passes and final composition; do not
        // cancel the content transform here, otherwise the projected scissor no longer
        // matches the dynamically scaled player bounds.
        // 依然要等所有歌词都画完再统一模糊：模糊读的是「下方已有像素」，
        // 提前执行会漏掉后面几行的内容。
        blurRects.forEach(Runnable::run);
        // The blur pass returns from a full-screen auxiliary framebuffer.
        // Re-enable the parent rounded-player clip before any later panel content is rendered.
        StencilClipManager.restoreActiveClip();
        } finally {
            if (lyricScissorActive) {
                ScissorClipManager.end();
            }
            // Blur/FBO rendering can toggle raw stencil state. Restore the lyric viewport
            // before leaving it, then pop only the clip introduced by this method.
            StencilClipManager.restoreActiveClip();
            StencilClipManager.endClip();
        }
    }

    private void updateLyricPositions(double posY, double height, double width, LyricLine currentLyric) {

        if (currentLyric == null) return;

        int idxCurrent = CloudMusic.lyrics.indexOf(currentLyric);

        if (idxCurrent < 0 || idxCurrent >= CloudMusic.lyrics.size()) return;

        // 对唱组以第一行为锚，整组抬高半个额外行高；组内各行的高度必须先算出来，否则抬升量为 0。
        int idxGroupEnd = LyricDuetGroups.groupEnd(CloudMusic.lyrics, idxCurrent);
        idxCurrent = LyricDuetGroups.groupStart(CloudMusic.lyrics, idxCurrent);
        for (int i = idxCurrent; i <= idxGroupEnd && i < CloudMusic.lyrics.size(); i++) {
            CloudMusic.lyrics.get(i).computeHeight(width);
        }
        double anchorLift = groupAnchorLift(idxCurrent, idxGroupEnd);

        double offsetY = posY + height * lyricFraction() + anchorLift;

        synchronized (CloudMusic.lyrics) {
            List<LyricLine> subList = CloudMusic.lyrics.subList(0, idxCurrent);
            double frameDeltaTime = RenderSystem.getFrameDeltaTime() * .0125;
            for (int i = subList.size() - 1; i >= 0; i--) {
                LyricLine lyric = subList.get(i);

                lyric.computeHeight(width);
                offsetY -= lyric.height + getLyricLineSpacing();

                if ((scrollTarget == 0 && (subList.size() - 1 - i) >= 3) && lyric.posY + lyric.height + getLyricLineSpacing() + 2 + scrollOffset < posY)
                    break;

//                lyric.posY = Interpolations.interpolate(lyric.posY, offsetY, fraction);
                lyric.spring.setTargetPosition(offsetY);
                lyric.spring.update(frameDeltaTime);
                lyric.posY = lyric.spring.getCurrentPosition();
            }

            offsetY = posY + height * lyricFraction() + anchorLift;
            List<LyricLine> list = CloudMusic.lyrics.subList(idxCurrent, CloudMusic.lyrics.size());
            int oobCounter = 0;
            for (LyricLine lyric : list) {
                int j = CloudMusic.lyrics.indexOf(lyric);

//                Rect.draw(RenderSystem.getWidth() * .5 + lyric.reboundAnimation, lyric.posY, width, lyric.height, 0x80FFFFFF);

                lyric.computeHeight(width);

                LyricLine prev = j > 0 ? CloudMusic.lyrics.get(j - 1) : null;

                if (prev != null) {
                    if (prev.delayTimer.isDelayed(getLyricInterpolationWaitTimeMillis()))
                        lyric.shouldUpdatePosition = true;
//                    if (lyric.posY - (prev.posY) >= prev.height * 1.5)
//                        lyric.shouldUpdatePosition = true;
                }

//                if (prev != null && lyric.posY - (prev.posY + prev.height) < 0) {
//                    updateLyricPositionsImmediate(width);
//                    break;
//                }

                if (prev != null && !lyric.shouldUpdatePosition) {
                    lyric.delayTimer.reset();
                    break;
                }

                if (prev == null && !lyric.delayTimer.isDelayed(getLyricInterpolationWaitTimeMillis())) break;

                lyric.spring.setTargetPosition(offsetY);
                lyric.spring.update(frameDeltaTime);
                lyric.posY = lyric.spring.getCurrentPosition();

                if (offsetY + scrollOffset > posY + height) {
                    oobCounter += 1;

                    if (oobCounter >= 4 && scrollTarget == 0) break;
                }

                offsetY += lyric.height + getLyricLineSpacing();
            }
        }

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

    private void renderControlsPart(double mouseX, double mouseY, double posX, double posY, double width, double height, float alpha, CloudMusic.PlaybackSnapshot snapshot) {
        TextureManager textureManager = TextureManager.getInstance();

        AudioPlayer player = snapshot.player;

        double coverSizeMax = this.getCoverSizeMax(width, height);
        double coverSizeMin = coverSizeMax * .8;
        double coverCenterX = posX + width * .24;
        double coverCenterY = posY + height * .36;
        coverSize = Interpolations.interpolate(coverSize, player == null || player.isPausing() ? coverSizeMin : coverSizeMax, 0.2f);

        double coverSizePerc = coverSize / coverSizeMax;
        double coverRadius = 7;

        api.getGLStateManager().pushMatrix();
        this.scaleAtPos(posX + width * .5, posY + height * .5, (.925 + (alpha * 0.075)));
        SkijaEffects.bloom(() -> {
            this.roundedRect(coverCenterX - coverSize * .5, coverCenterY - coverSize * .575, coverSize, coverSize, coverRadius * coverSizePerc, -.5, 0, 0, 0, alpha * .4f);
        });
        api.getGLStateManager().popMatrix();

        api.getGLStateManager().disableAlpha();

        if (prevCover != null && coverAlpha <= .9f) {
            api.getGLStateManager().bindTexture(prevCover.getGlTextureId());
            this.roundedRectTextured(coverCenterX - coverSize * .5, coverCenterY - coverSize * .575, coverSize, coverSize, coverRadius * coverSizePerc, alpha);
        }

        // 动态封面(网易云 MP4 抽帧)就绪后优先用它,否则保持静态封面。
        ITextureObject tex = textureManager.getTexture(
                CloudMusic.preferredCoverLocation(snapshot.music, snapshot.music.getCoverLocation()));

        if (tex != null) {
            coverAlpha = Interpolations.interpolate(coverAlpha, 1.0f, 0.2f);
            api.getGLStateManager().bindTexture(tex.getGlTextureId());
            tex.linearFilter();
            this.roundedRectTextured(coverCenterX - coverSize * .5, coverCenterY - coverSize * .575, coverSize, coverSize, coverRadius * coverSizePerc, alpha * coverAlpha);
        }

        double elementsXOffset = coverCenterX - coverSizeMax * .5;
        double elementsYOffset = coverCenterY + coverSizeMax * .45 + 8;

        stMusicName.render(FontManager.pf28bold, snapshot.music.getName(), elementsXOffset, elementsYOffset, coverSizeMax, RGBA.color((float) 1, (float) 1, (float) 1, alpha));
        stArtists.render(FontManager.pf20bold, snapshot.music.getArtistsName(), elementsXOffset, elementsYOffset + FontManager.pf20bold.getHeight() + 8, coverSizeMax, RGBA.color((float) 1, (float) 1, (float) 1, alpha * .8f));

        // progressbar 背景
        double progressBarYOffset = elementsYOffset + FontManager.pf20bold.getHeight() + 8 + FontManager.pf20bold.getHeight() + 8;
        double progressBarWidth = coverSizeMax;

        roundedRect(elementsXOffset, progressBarYOffset - progressBarHeight * .5, progressBarWidth, progressBarHeight, (this.progressBarHeight / 8.0f) * 2.5, hexColor(1, 1, 1, alpha * .5f));

        double currentTimeMillis = snapshot.positionMs;
        double totalTimeMillis = snapshot.durationMs;
        double perc = player == null ? 0 : (progressBarDragging ? progressBarProgressOverride : currentTimeMillis / Math.max(1.0, totalTimeMillis));

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
            updateLyricPositionsImmediate(createLyricViewport(posX, posY, width, height).width, progressBarProgressOverride * totalTimeMillis);
        }

        if (progressBarDragging) {
            if (!lmbDown) {
                progressBarDragging = false;

                double percent = this.progressBarProgressOverride;

                if (player != null) {
                    // 与控制栏共用 CloudMusic.seekToMillis()：房间拦截与歌词对齐都在那里统一处理。
                    if (CloudMusic.seekToMillis((float) (percent * totalTimeMillis))) {
                        scrollTarget = scrollOffset = 0;
                    }
                }
            } else {
                double xDelta = Math.max(0, Math.min(progressBarWidth, (mouseX - elementsXOffset)));
                this.progressBarProgressOverride = xDelta / progressBarWidth;
                updateLyricPositions(createLyricViewport(posX, posY, width, height).width, progressBarProgressOverride * totalTimeMillis);
            }
        }

        // curTime
        float curTime = progressBarDragging ? (float) (progressBarProgressOverride * totalTimeMillis) : (float) currentTimeMillis;
        FontManager.pf12bold.drawString(formatDuration(curTime), elementsXOffset, progressBarYOffset + 8, hexColor(1, 1, 1, alpha * .5f));
        String remainingTime = "-" + formatDuration((float) (totalTimeMillis - curTime));
        FontManager.pf12bold.drawString(remainingTime, elementsXOffset + progressBarWidth - FontManager.pf12bold.getStringWidthD(remainingTime), progressBarYOffset + 8, hexColor(1, 1, 1, alpha * .5f));

        double volumeBarYOffset = posY + height - Math.max(18, height * .08);
        double volumeBarWidth = coverSizeMax - FontManager.music40.getStringWidthD("I")
                - FontManager.music40.getStringWidthD("J");
        double volumeBarXOffset = elementsXOffset + FontManager.music40.getStringWidthD("I") - 2;
        // Both player surfaces delegate to this exact control so drag behavior, theme colors,
        // hover animation and Dynamic Island feedback stay synchronized.
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
            if (CloudMusic.player == null || CloudMusic.player.isPausing()) {
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
                if (CloudMusic.player != null && CloudMusic.currentlyPlaying != null) CloudMusic.prev();
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
                if (CloudMusic.player != null && CloudMusic.currentlyPlaying != null) CloudMusic.next();
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
        TextureManager textureManager = TextureManager.getInstance();
        Music current = snapshot.music;
        Location musicCoverBlurred = current == null ? null : current.getBlurredCoverLocation();
        ITextureObject texBg = current == null ? null : textureManager.getTexture(musicCoverBlurred);

        if (current != null && current != prevMusic) {

            if (prevMusic != null) musicBgAlpha = 0.0f;

            prevBg = prevMusic == null ? null : textureManager.getTexture(prevMusic.getBlurredCoverLocation());
            prevCover = prevMusic == null ? null : textureManager.getTexture(prevMusic.getCoverLocation());
            prevMusic = current;
            coverAlpha = 0.0f;
        }

        // The blurred cover is intentionally oversized to keep its crop stable while the
        // player resizes.  Do not rely solely on the parent stencil: auxiliary FBO/shader
        // passes may temporarily disable it.  A projected scissor is the hard boundary that
        // keeps this oversized texture inside the live player rectangle at every scale.
        StencilClipManager.restoreActiveClip();
        ScissorClipManager.begin(posX, posY, width, height);
        try {
            if (texBg != null || prevBg != null) {
                api.getGLStateManager().pushMatrix();
                try {
                    double bgSize = Math.max(width, height);

                    if (prevBg != null && musicBgAlpha < 0.99f) {
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
            // Image rendering changes raw GL state; the enclosing rounded-player clip must
            // remain valid for controls and lyric layers rendered afterwards.
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
