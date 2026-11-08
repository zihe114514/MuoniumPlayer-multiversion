package com.muoniumplayer.core.rendering;

import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.AudioSpectrumAnalyzer;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.texture.ITextureObject;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.screens.ncm.PlayerIconAssets;
import com.muoniumplayer.core.screens.ncm.PlayerIslandTransition;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.render.skija.SkijaGfx;

import java.awt.Color;

/**
 * 音乐胶囊（第九款灵动岛样式）的共享 artwork 与几何：收起的歌词行、悬停展开的迷你卡，
 * 以及与绘制<b>共用同一套坐标常量</b>的点击映射。
 *
 * <p>迷你卡按 296×130 设计空间排版、整体缩放（{@link HudConfig#islandMiniCardScale}），
 * 点击映射直接复用绘制常量（{@link #PROGRESS_X} / {@link #CONTROLS_Y}），缩放卡片上
 * 命中永远对齐——临摹自 NeoRaven 的 {@code MusicIsland}：它把 click 与 draw 做成同一套
 * 坐标，这个思路原样搬过来，视觉语言则换成岛自己的深色卡 + 强调色那一套。</p>
 *
 * <p>播放状态全部走 {@link CloudMusic#getSnapshot()}：内置播放器、系统镜像（SMTC）与
 * Spotify Connect 在同一份快照里汇合，胶囊因此不需要自己的数据源。暂停满
 * {@link #PAUSE_TIMEOUT_MILLIS} 后行内容判定为退场（「不给停了的歌打广告」），由岛的
 * 常驻机制接管显示。</p>
 */
public final class MusicCapsuleCard implements SharedRenderingConstants {

    // ------------------------------------------------------------------ 设计空间常量

    /** 迷你卡设计宽度。绘制与点击共用；所有卡内坐标都在这个空间里。 */
    public static final double CARD_WIDTH = 296.0;
    /** 迷你卡设计高度。 */
    public static final double CARD_HEIGHT = 130.0;
    /** 进度条左端（右端 = 宽度 - 同值）。点击映射按同一条带判定。 */
    public static final double PROGRESS_X = 45.0;
    /** 进度条中心线高度。 */
    public static final double PROGRESS_Y = 72.0;
    /** 传输控制行的中心线高度。 */
    public static final double CONTROLS_Y = 104.0;
    /** 卡面圆角（设计单位；实际乘卡缩放）。 */
    public static final double CARD_RADIUS = 24.0;
    /** 封面砖边长与左上角位置。点它打开全屏播放器。 */
    public static final double COVER_X = 14.0;
    public static final double COVER_Y = 14.0;
    public static final double COVER_SIZE = 44.0;

    // 收起行的几何（岛的绘制缩放之外，这里全按 1:1 设计单位）。
    /** 收起胶囊行高。 */
    public static final double ROW_HEIGHT = 22.0;
    /** 行内文本左边界（音符托盘右缘 + 呼吸位）。 */
    static final double ROW_TEXT_LEFT = 24.0;
    /** 行内文本最大宽度，超出截断；歌词行可以比歌名多吃一点宽度。 */
    static final double ROW_TEXT_MAX = 150.0;
    static final double ROW_LYRIC_TEXT_MAX = 175.0;
    /** 行右缘留白 + 三根频谱条合计宽。 */
    static final double ROW_RIGHT_PAD = 8.0;
    static final double ROW_BARS_WIDTH = 10.0;

    /** 暂停退休：停这么久后行内容退场，岛让回常驻/空闲内容。 */
    static final long PAUSE_TIMEOUT_MILLIS = 10000L;

    /** 单例：形状门面全是 default 方法，得有实例才调得到。 */
    public static final MusicCapsuleCard INSTANCE = new MusicCapsuleCard();

    /** 用户设置的迷你卡缩放档位（0.5–2.0）；读取异常回 1.0。 */
    public static float cardScale() {
        float scale = HudConfig.islandMiniCardScale;
        return Float.isFinite(scale) ? Math.max(.5f, Math.min(2.0f, scale)) : 1.0f;
    }

    private MusicCapsuleCard() {
    }

    // ------------------------------------------------------------------ 播放状态

    /** 当前曲暂停的起始时刻；0 表示在播或没歌。每帧由岛打点一次，便宜且幂等。 */
    private static volatile long pausedSinceMs;

    /** 每帧打点暂停时刻。渲染线程专用。 */
    public static void observePlayback() {
        CloudMusic.PlaybackSnapshot snapshot = snapshot();
        if (snapshot.music == null || snapshot.playing) {
            pausedSinceMs = 0L;
        } else if (pausedSinceMs == 0L) {
            pausedSinceMs = System.currentTimeMillis();
        }
    }

    /** 收起行的内容是否该出现：有歌，且没在暂停退休窗口之外。 */
    public static boolean rowContentActive() {
        CloudMusic.PlaybackSnapshot snapshot = snapshot();
        if (snapshot.music == null) return false;
        long pausedSince = pausedSinceMs;
        return pausedSince == 0L || System.currentTimeMillis() - pausedSince < PAUSE_TIMEOUT_MILLIS;
    }

    /** 当前播放进度 0..1；总时长未知（直播）时恒 0。 */
    public static double progressFraction(CloudMusic.PlaybackSnapshot snapshot) {
        return snapshot.durationMs <= 0L ? 0.0
                : DynamicIslandMath.clamp01(snapshot.positionMs / (double) snapshot.durationMs);
    }

    private static CloudMusic.PlaybackSnapshot snapshot() {
        try {
            return CloudMusic.getSnapshot();
        } catch (Throwable t) {
            return EMPTY_SNAPSHOT;
        }
    }

    private static final CloudMusic.PlaybackSnapshot EMPTY_SNAPSHOT =
            new CloudMusic.PlaybackSnapshot(-1L, -1L, 0L, 0L, false, null, null);

    // ------------------------------------------------------------------ 行文本与交叉淡入

    private static String rowText = "";
    private static String rowPreviousText = "";
    private static float rowFade = 1f;
    private static double rowFrameDeltaMillis = 16.0;

    /**
     * 收起行（与迷你卡副标题上层）此刻该显示的文本：设置开了「显示歌词」且有当前行 → 歌词行，
     * 否则歌名 - 歌手。与 {@code CloudMusic#findCurrentLyric} 同一入口，歌词偏移在这里生效。
     */
    public static String rowText(CloudMusic.PlaybackSnapshot snapshot) {
        if (HudConfig.islandMusicShowsLyric) {
            LyricLine line = CloudMusic.findCurrentLyric(snapshot.positionMs);
            String text = line == null ? null : line.lyric;
            if (text != null && !text.trim().isEmpty()) return text.trim();
        }
        Music music = snapshot.music;
        if (music == null) return "";
        String name = music.getName() == null ? "" : music.getName().trim();
        String artist = music.getArtistsName() == null ? "" : music.getArtistsName().trim();
        return artist.isEmpty() ? name : name + " - " + artist;
    }

    /** 每帧推进行文本的交叉淡入（换歌词/换歌时 240ms 淡替）。岛的自定义路径调用。 */
    public static void tick(float deltaMillis) {
        rowFrameDeltaMillis = Math.max(1.0, Math.min(100.0, deltaMillis));
        CloudMusic.PlaybackSnapshot snapshot = snapshot();
        String next = snapshot.music == null ? "" : rowText(snapshot);
        if (!next.equals(rowText)) {
            rowPreviousText = rowText;
            rowText = next;
            rowFade = 0f;
        }
        if (rowFade < 1f) {
            rowFade = Math.min(1f, rowFade + (float) (rowFrameDeltaMillis / 240.0));
        }
        tickBars(snapshot);
    }

    /** 行文本此刻的显示宽度（已按上限截断），供岛的目标宽度计算与绘制共用。 */
    public static double rowTextWidth() {
        return Math.min(FontManager.pf14bold.getStringWidthD(rowText), currentRowTextMax());
    }

    private static double currentRowTextMax() {
        return HudConfig.islandMusicShowsLyric ? ROW_LYRIC_TEXT_MAX : ROW_TEXT_MAX;
    }

    /** 收起行的目标总宽（设计单位）。 */
    public static double rowTargetWidth() {
        if (FontManager.pf14bold == null) return 96.0;
        return ROW_TEXT_LEFT + rowTextWidth() + 6.0 + ROW_BARS_WIDTH + ROW_RIGHT_PAD;
    }

    // ------------------------------------------------------------------ 频谱跳动条

    private static final int BAR_COUNT = 3;
    private static final float[] BAR_LEVELS = new float[BAR_COUNT];
    private static final float[] BAR_TARGETS = new float[BAR_COUNT];
    private static long lastBarsFrameNanos;
    /** 从低到高挑三个频段：低频踩鼓点、高频随人声闪，三根各自有节奏。 */
    private static final int[] BAR_BANDS = {2, 5, 9};

    private static void tickBars(CloudMusic.PlaybackSnapshot snapshot) {
        long now = System.nanoTime();
        double dt = lastBarsFrameNanos == 0L ? 1.0 / 60.0
                : Math.min(0.10, Math.max(0.0, (now - lastBarsFrameNanos) / 1e9));
        lastBarsFrameNanos = now;
        boolean audible = snapshot.player != null && snapshot.playing
                && snapshot.player.isUsable() && snapshot.player.isPlaying();
        if (audible) {
            try {
                AudioSpectrumAnalyzer.Frame frame = AudioSpectrumAnalyzer.requestFrame(now);
                boolean fresh = frame.capturedAt > 0L && now - frame.capturedAt < 250_000_000L;
                for (int i = 0; i < BAR_COUNT; i++) {
                    BAR_TARGETS[i] = fresh ? frame.band(BAR_BANDS[i]) : 0f;
                }
            } catch (Throwable t) {
                java.util.Arrays.fill(BAR_TARGETS, 0f);
            }
        } else {
            java.util.Arrays.fill(BAR_TARGETS, 0f);
            try {
                AudioSpectrumAnalyzer.suspend();
            } catch (Throwable ignored) {
            }
        }
        for (int i = 0; i < BAR_COUNT; i++) {
            BAR_LEVELS[i] += (float) ((BAR_TARGETS[i] - BAR_LEVELS[i]) * (1.0 - Math.exp(-dt * 14.0)));
        }
    }

    // ------------------------------------------------------------------ 收起行绘制

    /**
     * 画收起行的内容（音符托盘 + 文本交叉淡入 + 频谱条 + 底部进度描边）。
     *
     * <p>调用方（岛的 MUSIC_CAPSULE 分支）已画好深色卡面并处于岛自身的矩阵里；
     * x/y/width/height 是本帧动画出来的行矩形（布局单位，已含整体缩放）。</p>
     */
    public void drawRow(double x, double y, double width, double height, float alpha, int accentColor) {
        if (alpha <= .01f) return;
        double plateSize = Math.min(16.0, height - 6.0);
        double plateY = y + height * .5;
        // 左侧音符托盘：小圆底 + 音符图标。有封面也不换——收起行要的是一眼认出「这是音乐」。
        roundedRect(x + 6.0, plateY - plateSize * .5, plateSize, plateSize, plateSize * .5,
                RenderSystem.reAlpha(accentColor, alpha * .30f));
        com.muoniumplayer.core.rendering.FoliaLucideIcons.draw(
                com.muoniumplayer.core.rendering.FoliaLucideIcons.NOTE,
                x + 6.0 + plateSize * .5, plateY, plateSize * .58,
                RenderSystem.reAlpha(0xFFFFFF, alpha * .95f));

        // 文本：旧行淡出上行、新行淡入下行（NeoRaven 的歌词行交叉淡入）。
        double textX = x + ROW_TEXT_LEFT;
        double textMax = Math.max(12.0, width - ROW_TEXT_LEFT - ROW_BARS_WIDTH - 6.0 - ROW_RIGHT_PAD);
        float t = rowFade;
        if (t < .999f && !rowPreviousText.isEmpty()) {
            drawRowText(rowPreviousText, textX, plateY, textMax, alpha * (1f - t), (float) (-4.0 * t));
        }
        drawRowText(rowText, textX, plateY, textMax, alpha * t, (float) (4.0 * (1f - t)));

        // 三根频谱条：贴在文本右侧，随实测频段跳动；暂停时它们缩到底只剩一排小点。
        double barsX = textX + textMax + 6.0;
        for (int i = 0; i < BAR_COUNT; i++) {
            float level = (float) DynamicIslandMath.clamp01(BAR_LEVELS[i]);
            double barHeight = 2.5 + 9.5 * level;
            roundedRect(barsX + i * 4.0, plateY - barHeight * .5, 2.0, barHeight, 1.0,
                    RenderSystem.reAlpha(accentColor, alpha * (.55f + .45f * level)));
        }

        // 底部进度描边：贴着胶囊内缘走一圈细条（NeoRaven 同款位置，画法收敛成内缩条，
        // 不做沿轮廓裁剪——那里要为 1.5px 的视觉差引入一张 scissor，不值）。
        double trackY = y + height - 3.0;
        double trackWidth = Math.max(0.0, width - 12.0);
        CloudMusic.PlaybackSnapshot snapshot = snapshot();
        double fraction = progressFraction(snapshot);
        roundedRect(x + 6.0, trackY, trackWidth, 1.6, .8, hexColor(1f, 1f, 1f, alpha * .14f));
        double fillWidth = trackWidth * fraction;
        if (fillWidth > .2) {
            roundedRect(x + 6.0, trackY, fillWidth, 1.6, .8, RenderSystem.reAlpha(accentColor, alpha * .85f));
        }
    }

    private void drawRowText(String text, double x, double centerY, double maxWidth,
                             float alpha, float slide) {
        if (alpha <= .01f) return;
        String trimmed = FontManager.pf14bold.trim(text, maxWidth);
        FontManager.pf14bold.drawString(trimmed, x, centerY - FontManager.pf14bold.getHeight() * .5 + slide,
                1.0, RenderSystem.reAlpha(0xFFFFFF, alpha * .96f));
    }

    // ------------------------------------------------------------------ 迷你卡绘制

    /**
     * 画迷你卡内容（封面 + 歌词/副标题 + 播放模式徽章 + 进度与时间 + 传输控制行）。
     *
     * <p>296×130 设计空间、按 {@code scale} 整体缩放；调用方已画好卡面底。
     * {@code cardOpen} 由调用方传入本帧展开度，卡内容整体乘它淡入。</p>
     */
    public void drawCard(double x, double y, float scale, float alpha, boolean preview) {
        if (alpha <= .01f) return;
        CloudMusic.PlaybackSnapshot snapshot = snapshot();
        Music music = snapshot.music;
        if (music == null) return;
        api().getGLStateManager().pushMatrix();
        try {
            api().getGLStateManager().translate(x, y, 0);
            api().getGLStateManager().scale(scale, scale, 1);
            int accent = NCMTheme.getAccentColor();

            drawCover(COVER_X, COVER_Y, COVER_SIZE, COVER_SIZE * .24f, alpha);

            // 主文本：设置开了歌词就显示当前行，否则歌名；副标题恒为「歌名 - 歌手」。
            String headline = HudConfig.islandMusicShowsLyric ? rowText : "";
            if (headline.isEmpty()) headline = music.getName() == null ? "" : music.getName();
            String sub = music.getArtistsName() == null || music.getArtistsName().isEmpty()
                    ? music.getName() == null ? "" : music.getName()
                    : music.getName() + " - " + music.getArtistsName();
            String head = FontManager.pf14bold.trim(headline, CARD_WIDTH - 84.0 - 30.0);
            FontManager.pf14bold.drawString(head, 68.0, 21.0, 1.0,
                    RenderSystem.reAlpha(0xFFFFFF, alpha * .97f));
            String subText = FontManager.pf12.trim(sub, CARD_WIDTH - 84.0);
            FontManager.pf12.drawString(subText, 68.0, 45.0, 1.0,
                    hexColor(.62f, .67f, .76f, alpha * .92f));

            // 右上角播放模式徽章：强调色圆底 + 模式贴图，点击循环切换。
            roundedRect(CARD_WIDTH - 38.0, COVER_Y, 24.0, 24.0, 7.0, RenderSystem.reAlpha(accent, alpha * .88f));
            drawModeGlyph(CARD_WIDTH - 38.0, COVER_Y, 24.0, 24.0, alpha);

            // 进度条与两端时间。
            double trackX = PROGRESS_X;
            double trackWidth = CARD_WIDTH - PROGRESS_X * 2.0;
            double fraction = progressFraction(snapshot);
            roundedRect(trackX, PROGRESS_Y - 3.0, trackWidth, 6.0, 3.0, hexColor(1f, 1f, 1f, alpha * .14f));
            double fillWidth = trackWidth * fraction;
            if (fillWidth > .5) {
                roundedRect(trackX, PROGRESS_Y - 3.0, fillWidth, 6.0, 3.0,
                        RenderSystem.reAlpha(accent, alpha * .92f));
            }
            FontManager.pf10bold.drawString(formatTime(snapshot.positionMs), COVER_X, PROGRESS_Y - 3.0, 1.0,
                    hexColor(.62f, .67f, .76f, alpha * .95f));
            String total = formatTime(snapshot.durationMs);
            FontManager.pf10bold.drawString(total, CARD_WIDTH - PROGRESS_X - FontManager.pf10bold.getStringWidthD(total),
                    PROGRESS_Y - 3.0, 1.0, hexColor(.62f, .67f, .76f, alpha * .95f));

            // 传输控制行：循环模式 / 上一首 / 播放暂停 / 下一首 / 打开播放器。
            boolean playing = snapshot.playing;
            roundedRect(CARD_WIDTH * .5 - 18.0, CONTROLS_Y - 18.0, 36.0, 36.0, 18.0,
                    hexColor(1f, 1f, 1f, alpha * .08f));
            drawTransportGlyph(playing ? "A" : "B", CARD_WIDTH * .5, CONTROLS_Y, 22.0,
                    RenderSystem.reAlpha(0xFFFFFF, alpha * .96f));
            drawTransportGlyph("H", CARD_WIDTH * .5 - 50.0, CONTROLS_Y, 17.0,
                    RenderSystem.reAlpha(0xFFFFFF, alpha * .88f));
            drawTransportGlyph("E", CARD_WIDTH * .5 + 50.0, CONTROLS_Y, 17.0,
                    RenderSystem.reAlpha(0xFFFFFF, alpha * .88f));
            drawTransportGlyph("\ue144", CARD_WIDTH - 30.0, CONTROLS_Y, 16.0,
                    hexColor(.62f, .67f, .76f, alpha * .92f));
            drawModeGlyph(30.0 - 10.0, CONTROLS_Y - 10.0, 20.0, 20.0, alpha * .9f);
        } finally {
            api().getGLStateManager().popMatrix();
        }
    }

    private void drawTransportGlyph(String glyph, double centerX, double centerY, double size, int color) {
        if (FontManager.icon30 == null) return;
        double width = FontManager.icon30.getStringWidthD(glyph);
        FontManager.icon30.drawString(glyph, centerX - width * .5,
                centerY - size * .5, size / 30.0, color);
    }

    /** 播放模式贴图（sequential/random/single-loop，与播放器控制栏同一组资产）。 */
    private void drawModeGlyph(double x, double y, double size, double box, float alpha) {
        try {
            Location location = PlayerIconAssets.forPlayMode(CloudMusic.playMode);
            if (location == null) return;
            ITextureObject texture = TextureManager.getInstance().getTexture(location);
            if (texture == null) return;
            double inset = box * .5 - size * .5;
            SkijaGfx.setColor(1f, 1f, 1f, 1f);
            api().getGLStateManager().bindTexture(texture.getGlTextureId());
            texture.linearFilter();
            roundedRectTextured(x + inset, y + inset, size, size, size * .5, alpha);
            api().getGLStateManager().bindTexture(0);
            SkijaGfx.setColor(1f, 1f, 1f, 1f);
        } catch (Throwable ignored) {
        }
    }

    /** 迷你卡封面：与常驻托盘同一取图链（动态封面优先），圆角裁剪。 */
    private void drawCover(double x, double y, double size, double radius, float alpha) {
        try {
            Music music = CloudMusic.currentlyPlaying;
            if (music == null || size <= 2.0) return;
            ITextureObject texture = coverTexture(music);
            if (texture == null) return;
            double corner = Math.min(radius, size * .5);
            roundedRect(x, y, size, size, corner, hexColor(.016f, .020f, .030f, Math.min(1f, alpha * 1.06f)));
            SkijaGfx.setColor(1f, 1f, 1f, 1f);
            api().getGLStateManager().bindTexture(texture.getGlTextureId());
            texture.linearFilter();
            roundedRectTextured(x, y, size, size, corner, alpha);
            api().getGLStateManager().bindTexture(0);
            SkijaGfx.setColor(1f, 1f, 1f, 1f);
            roundedOutline(x, y, size, size, corner, .9,
                    new Color(255, 255, 255, clamp255(alpha * 78f)));
        } catch (Throwable ignored) {
        }
    }

    private static ITextureObject coverTexture(Music music) {
        try {
            Location cover = CloudMusic.preferredCoverLocation(music, music.getSmallCoverLocation());
            ITextureObject texture = TextureManager.getInstance().getTexture(cover);
            if (texture == null) {
                texture = TextureManager.getInstance().getTexture(music.getCoverLocation());
            }
            return texture;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 点击映射

    /**
     * 迷你卡点击。x/y 为<b>设计空间</b>坐标（0..296 / 0..130），与绘制常量同轴；
     * 命中返回 true（调用方停止继续分派）。控制行为与播放器控制栏同源：
     * {@code CloudMusic#prev/next/togglePlayPause/cyclePlayMode}、{@code seekToMillis}。
     */
    public boolean click(double x, double y) {
        CloudMusic.PlaybackSnapshot snapshot = snapshot();
        if (snapshot.music == null) return false;
        if (x >= COVER_X && x <= COVER_X + COVER_SIZE && y >= COVER_Y && y <= COVER_Y + COVER_SIZE) {
            openPlayer();
            return true;
        }
        // 右上角播放模式徽章：与控制行那颗同效（绘制见 drawCard 的 primaryContainer 圆钮）。
        if (x >= CARD_WIDTH - 38.0 && x <= CARD_WIDTH - 14.0
                && y >= COVER_Y && y <= COVER_Y + 24.0) {
            try {
                CloudMusic.cyclePlayMode();
            } catch (Throwable ignored) {
            }
            return true;
        }
        if (Math.abs(y - PROGRESS_Y) <= 10.0 && x >= PROGRESS_X && x <= CARD_WIDTH - PROGRESS_X) {
            seekToFraction((x - PROGRESS_X) / (CARD_WIDTH - PROGRESS_X * 2.0));
            return true;
        }
        if (Math.abs(y - CONTROLS_Y) <= 20.0) {
            if (Math.abs(x - 30.0) < 18.0) {
                try {
                    CloudMusic.cyclePlayMode();
                } catch (Throwable ignored) {
                }
                return true;
            }
            if (Math.abs(x - (CARD_WIDTH * .5 - 50.0)) < 20.0) {
                try {
                    CloudMusic.prev();
                } catch (Throwable ignored) {
                }
                return true;
            }
            if (Math.abs(x - CARD_WIDTH * .5) < 22.0) {
                try {
                    CloudMusic.togglePlayPause();
                } catch (Throwable ignored) {
                }
                return true;
            }
            if (Math.abs(x - (CARD_WIDTH * .5 + 50.0)) < 20.0) {
                try {
                    CloudMusic.next();
                } catch (Throwable ignored) {
                }
                return true;
            }
            if (Math.abs(x - (CARD_WIDTH - 30.0)) < 18.0) {
                openPlayer();
                return true;
            }
        }
        return false;
    }

    /** 拖进度条：按比例 seek。总时长未知（直播）时忽略。 */
    public void seekToFraction(double fraction) {
        CloudMusic.PlaybackSnapshot snapshot = snapshot();
        if (snapshot.durationMs <= 0L) return;
        try {
            CloudMusic.seekToMillis((float) (DynamicIslandMath.clamp01(fraction) * snapshot.durationMs));
        } catch (Throwable ignored) {
        }
    }

    /** 点封面/队列按钮 → 从岛上长出全屏播放器（与播放器内的打开入口同一条形变链）。 */
    public static void openPlayer() {
        try {
            PlayerIslandTransition.openFromIslandCard();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 小工具

    private static com.muoniumplayer.api.OpenAPI api() {
        return com.muoniumplayer.core.interfaces.SharedConstants.api;
    }

    private static int clamp255(float value) {
        return (int) Math.max(0f, Math.min(255f, value));
    }

    /** m:ss；总时长未知（直播）显示已收听时长，负值钳 0。 */
    public static String formatTime(long millis) {
        long seconds = Math.max(0L, millis) / 1000L;
        return seconds / 60L + ":" + (seconds % 60L < 10 ? "0" : "") + seconds % 60L;
    }
}
