package com.muoniumplayer.core.widget.impl;

import com.muoniumplayer.api.enums.EnumModuleCategory;
import com.muoniumplayer.api.features.ExtensionModule;
import com.muoniumplayer.api.features.ExtensionWidget;
import com.muoniumplayer.api.interfaces.EventHandler;
import com.muoniumplayer.api.interfaces.modules.values.BooleanValue;
import com.muoniumplayer.api.interfaces.modules.values.NumberValue;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.AutomixCueMarker;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.PlaybackPresentation;
import com.muoniumplayer.core.screens.ncm.TrackSelectionMotion;
import com.muoniumplayer.core.screens.ncm.SheetClock;
import com.muoniumplayer.core.ncm.music.ExternalPlayback;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.rendering.AutomixCuePainter;
import com.muoniumplayer.core.rendering.RGBA;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.entities.impl.ScrollText;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.texture.ITextureObject;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.Tuple;
import com.muoniumplayer.core.utils.WidgetWrapper;

import java.awt.*;
import java.time.Duration;

/**
 */
public class MusicInfoWidget extends ExtensionModule implements SharedConstants, SharedRenderingConstants, EventHandler {

    public BooleanValue turnComposerIntoLyric = api.getValueManager().createBoolean("Turn Composer Into Lyric", false);

    /** 网易云动态封面(需要 ffmpeg 抽帧)。关掉后桌面歌曲信息与全屏歌词页一律用静态封面。 */
    public BooleanValue animatedCover = api.getValueManager().createBoolean("Animated Cover", true);

    public NumberValue volume = api.getValueManager().createDouble("Volume", 0.1, 0.0, 1.0, 0.01);

    public ExtensionWidget widget;
    WidgetWrapper.WidgetPosSizeInterface posInterface;

    public MusicInfoWidget() {
        super("Music Info", "Shows music info.", EnumModuleCategory.VISUAL);
        Tuple<ExtensionWidget, WidgetWrapper.WidgetPosSizeInterface> wrapper = WidgetWrapper.createWrapper(this, this::onRender);
        this.widget = wrapper.getA();
        this.posInterface = wrapper.getB();

        volume.setHiddenPredicate(() -> true);
        volume.setValueCallback(val -> {
            if (val == null) return;
            // 镜像时这根音量管的是外部播放器在音量合成器里的那一格，不该去碰内部播放器。
            if (ExternalPlayback.writeVolume(val.floatValue())) return;
            // 内部这条路保持与改之前逐字一致：只喂正在播的播放器，不写持久化配置。
            if (CloudMusic.player != null)
                CloudMusic.player.setVolume(val.floatValue());
        });
        
        // 动态封面开关的真实来源是 HudConfig(HUD 编辑器"封面 → 动态封面"同一状态),这里只是另一处入口。
        animatedCover.setValueCallback(val -> {
            if (val == null || HudConfig.animatedCoverEnabled == val) return;
            HudConfig.animatedCoverEnabled = val;
            HudConfig.save();
        });

        this.addValues(this.turnComposerIntoLyric, this.animatedCover, this.volume);
        this.setEventHandler(this);
    }

    float alpha = 0.0f;

    ScrollText musicName = new ScrollText();
    ScrollText artists = new ScrollText();

    public double downloadProgHeight = 0;
    public boolean downloading = false;
    public double downloadProgress = 0;
    public String downloadSpeed = "0 b/s";
    float downloadPanelAlpha = 0.0f;

    float musicBgAlpha = 0.0f;
    /** 封面小图自己的淡入系数：与糊底（musicBgAlpha）解耦，糊图慢/失败不拖累封面。 */
    float coverFadeAlpha = 0.0f;
    ITextureObject prevBlurredBg = null;
    ITextureObject prevBg = null;
    Music prevMusic = null;

    /** automix 接缝记号的平滑与换歌记号，见 {@link AutomixCueMarker.Glide}。 */
    private final AutomixCueMarker.Glide cueGlide = new AutomixCueMarker.Glide();
    /** HUD 编辑器预览态：为 true 时 onRender 不再压 correction（编辑器外层已带）。 */
    private boolean editorPreviewActive;
    private final TrackSelectionMotion selectionMotion = new TrackSelectionMotion();
    private long cueSongId = Long.MIN_VALUE;

    /**
     * 渲染与游戏中相同的信息卡路径，但位于 HUD 编辑器中选择的位置。
     * 持久化的 HudConfig 位置不被此预览修改（与 MusicLyricsWidget.renderEditorPreview 同款）。
     */
    public void renderEditorPreview(float x, float y, float scale) {
        editorPreviewActive = true;
        this.editorPreviewX = x;
        this.editorPreviewY = y;
        this.editorPreviewScale = scale;
        try {
            onRender();
        } finally {
            editorPreviewActive = false;
        }
    }

    private float editorPreviewX;
    private float editorPreviewY;
    private float editorPreviewScale;

    public void onRender() {

        // HUD 位置 + 缩放（HudConfig，由 GuiHudEditor 拖拽/滚轮调整）。
        // 位置公式与参考实现一致：pixel = pos * (screen - baseSize * scale)；缩放围绕左上锚点等比进行。
        // 跨度取布局轴（与 GUI Scale 无关），理由同 MusicLyricsWidget。
        float hudScale = editorPreviewActive ? editorPreviewScale : HudConfig.infoScale;
        float hudX = editorPreviewActive ? editorPreviewX
                : (float) (HudConfig.infoX * (RenderSystem.getLayoutWidth() - 230 * hudScale));
        float hudY = editorPreviewActive ? editorPreviewY
                : (float) (HudConfig.infoY * (RenderSystem.getLayoutHeight() - 56 * hudScale));
        posInterface.setX(hudX);
        posInterface.setY(hudY);

        // 布局轴 → 当前 canvas 的 GUI 逻辑坐标（NCMScreen 同款方向）。这一层保证
        // 「每布局单位的物理像素」恒定：GUI 倍率变化不再改变控件的物理占屏比。
        // 编辑器预览路径不压：编辑器 HUD 段外层矩阵已带 correction，再乘就是双乘，
        // 预览选框和实物会差一个 correction 因子（见 MusicLyricsWidget 同位置注释）。
        boolean needCorrection = !editorPreviewActive;
        if (needCorrection) {
            api.getGLStateManager().pushMatrix();
            api.getGLStateManager().scale(com.muoniumplayer.core.screens.ncm.WindowAdaptiveScale.correctionX(),
                    com.muoniumplayer.core.screens.ncm.WindowAdaptiveScale.correctionY(), 1);
        }
        api.getGLStateManager().pushMatrix();
        api.getGLStateManager().translate(hudX, hudY, 0);
        api.getGLStateManager().scale(hudScale, hudScale, 1);
        api.getGLStateManager().translate(-hudX, -hudY, 0);

        double width = 230;
        double height = 56;

        PlaybackPresentation presentation = CloudMusic.playbackPresentation();
        Music playingMusic = presentation.displayTrack;
        selectionMotion.update(presentation.displayKey(), presentation.requestNanos,
                presentation.preparing, System.nanoTime(), SheetClock.speed());

        boolean playing = playingMusic != null && (presentation.preparing || CloudMusic.hasLiveTrack());

        alpha = Interpolations.interpolate(alpha, playing ? 1 : 0, playing ? 0.15f : 0.2f);

        this.downloadProgHeight = Interpolations.interpolate(this.downloadProgHeight, this.downloading ? (playing ? 26 : -26) : 0, 0.2f);
        this.downloadPanelAlpha = Interpolations.interpolate(this.downloadPanelAlpha, this.downloading ? 1.0f : 0.0f, 0.4f);

        if (playingMusic != null) {

            // 动态封面就绪时优先用它;没有(或用户关掉)时仍是静态小封面。
            Location cover = CloudMusic.preferredCoverLocation(playingMusic, playingMusic.getSmallCoverLocation());
            ITextureObject texture = TextureManager.getInstance().getTexture(cover);

            double imgSpacing = 4;

            double imgX = posInterface.getX() + imgSpacing;

            float y = (float) (posInterface.getY() + downloadProgHeight);
            double imgY = y + imgSpacing;

            double imgSize = height - imgSpacing * 2;

            double coverRound = 6;
            double bgRound = coverRound * 1.75;

            api.getGLStateManager().pushMatrix();

            api.getGLStateManager().translate(posInterface.getX() + posInterface.getWidth() * .5, posInterface.getY() + posInterface.getHeight() * .5, 0);
            double scale = .98 + (alpha * .02);
            api.getGLStateManager().scale(scale, scale, 1);
            api.getGLStateManager().translate(-(posInterface.getX() + posInterface.getWidth() * .5), -(posInterface.getY() + posInterface.getHeight() * .5), 0);

            api.getShaderUtil().drawWithBloom(() -> {
                api.getGLStateManager().pushMatrix();

                api.getGLStateManager().translate(posInterface.getX() + posInterface.getWidth() * .5, posInterface.getY() + posInterface.getHeight() * .5, 0);
                api.getGLStateManager().scale(scale, scale, 1);
                api.getGLStateManager().translate(-(posInterface.getX() + posInterface.getWidth() * .5), -(posInterface.getY() + posInterface.getHeight() * .5), 0);

                if (!transparentBackdrop()) {
                    this.roundedRect(posInterface.getX(), posInterface.getY(), width, height + downloadProgHeight, bgRound, 1, 0, 0, 0, alpha * 0.7f);
                }

                api.getGLStateManager().popMatrix();
            });

            // 「液态玻璃」档：与灵动岛 LIQUID_GLASS 款同一套表面（renderLiquidGlassSurface
            // 的移植版）。真玻璃铺得上就直接整面返回；铺不上（主题没开真玻璃/手机档）走
            // 同款多层假玻璃回退，而不是一层均匀的半透明灰底。
            if (liquidGlassBackdrop()) {
                renderLiquidGlassCard(posInterface.getX(), posInterface.getY(),
                        width, height + downloadProgHeight, bgRound, alpha);
            }
            // 状态更新无条件执行：prevBg/musicBgAlpha 同时喂给下面的封面小图绘制，
            // 透明/玻璃档跳过它们会让封面永久消失（musicBgAlpha 卡在 0）。
            double posX = posInterface.getX();
            double posY = posInterface.getY();

            // 用上面那个已经判过空的局部量，别再读一遍 CloudMusic.currentlyPlaying：
            // 「先判空再取值」这种写法在停播那一帧就会读到 null。
            // 镜像中且外部播放器给了封面时，这张底由那张图现算（CloudMusic 那一处统一分叉）。
            Location musicCoverBlurred = CloudMusic.preferredBlurredCoverLocation(playingMusic);

            TextureManager textureManager = TextureManager.getInstance();
            ITextureObject texBg = textureManager.getTexture(musicCoverBlurred);

            // 换歌检测与状态推进无条件执行（不依赖糊图纹理就绪）：prevBg 供封面小图
            // 交叉淡入，musicBgAlpha 同时驱动糊底与封面小图的淡入系数。把这段绑在
            // 「糊图已装出来」之下会让封面跟着糊图的异步加载一起卡死——糊图没就绪时
            // musicBgAlpha 恒 0，封面小图（乘同一系数）就永远透明，切歌后看不到新封面。
            if (playingMusic != prevMusic) {
                // 用 stableKey 而不是对象引用：播放器切页/换源重查询会 new 出内容相同的
                // Music，引用比较会把它当成「同一首」跳过重置，封面淡入就被卡住。
                boolean sameTrack = playingMusic.getStableKey().equals(
                        prevMusic == null ? "" : prevMusic.getStableKey());
                if (!sameTrack) {
                    prevBlurredBg = prevMusic == null ? null
                            : textureManager.getTexture(CloudMusic.preferredBlurredCoverLocation(prevMusic));
                    // 取上一首「当时实际在显示」的那张（可能是动态封面），而不是死板地查
                    // 静态图——否则动态封面切静态图时旧封面会闪没。
                    prevBg = prevMusic == null ? null
                            : textureManager.getTexture(CloudMusic.preferredCoverLocation(
                                    prevMusic, prevMusic.getSmallCoverLocation()));
                    prevMusic = playingMusic;
                    musicBgAlpha = 0.0f;
                    coverFadeAlpha = 0.0f;
                }
            }

            double v = (height) / width;

            // 默认档才画糊底。液态玻璃档的折射底在上面已经铺过，糊图叠上去会把它整个盖死；
            // 完全透明档什么都不垫。
            boolean drawBlurredBackdrop = !transparentBackdrop() && !liquidGlassBackdrop();
            if (drawBlurredBackdrop && prevBlurredBg != null && musicBgAlpha < 0.99f) {
                api.getGLStateManager().bindTexture(prevBlurredBg.getGlTextureId());
                prevBlurredBg.linearFilter();
                this.roundedRectTextured(posX, posY, width, height + downloadProgHeight, 0, v, 1, v, bgRound, 1, alpha);
            }

            if (drawBlurredBackdrop && texBg != null) {
                this.musicBgAlpha = Interpolations.interpolate(this.musicBgAlpha, 1.0f, 0.3f);
                api.getGLStateManager().bindTexture(texBg.getGlTextureId());
                texBg.linearFilter();
                this.roundedRectTextured(posX, posY, width, height + downloadProgHeight, 0, .5 - v * .5, 1, v, bgRound, 1, this.musicBgAlpha * alpha);
            }

            this.roundedRect(posInterface.getX(), posInterface.getY(), width, height + downloadProgHeight,
                    bgRound, 1, 0, 0, 0, transparentBackdrop() ? 0f : alpha * 0.25f);

            // 渲染下载面板

            if (this.downloading) {

                double offsetY = posInterface.getY() + imgSpacing;

                CFontRenderer fr = FontManager.pf18bold;

                fr.drawString("Downloading...", imgX, offsetY, new Color(1, 1, 1, downloadPanelAlpha).getRGB());
                fr.drawString(downloadSpeed, imgX + width - imgSpacing * 2 - fr.getWidth(downloadSpeed), offsetY, new Color(1, 1, 1, downloadPanelAlpha).getRGB());

                this.roundedRect(imgX, offsetY + fr.getHeight() + 4, width - imgSpacing * 2, 6, 2, 1, 1, 1, downloadPanelAlpha * 0.25f);

                double downloadFillWidth = (width - imgSpacing * 2) * downloadProgress;
                if (downloadFillWidth > 0)
                    this.roundedRect(imgX, offsetY + fr.getHeight() + 4, downloadFillWidth, 6, Math.min(2, downloadFillWidth * .5), 1, 1, 1, downloadPanelAlpha);
            }

            // 封面小图交叉淡入。淡入系数独立于糊底（coverAlpha）：糊图是异步解码的大图，
            // 慢/失败都不该拖累封面——旧图淡出、新图淡进，糊图什么时候就绪什么时候参与。
            float coverAlpha = Interpolations.interpolate(this.coverFadeAlpha,
                    texture != null ? 1.0f : 0.0f, 0.25f);
            this.coverFadeAlpha = coverAlpha;
            if (prevBg != null && coverAlpha < 0.99f) {
                api.getGLStateManager().bindTexture(prevBg.getGlTextureId());
                prevBg.linearFilter();
                double exp = 0;
                this.roundedRectTextured(imgX - exp, imgY - exp, imgSize + exp * 2, imgSize + exp * 2, coverRound, (1f - coverAlpha) * alpha);
            }

            if (texture != null) {
                api.getGLStateManager().bindTexture(texture.getGlTextureId());
                texture.linearFilter();
                double exp = 0;
                this.roundedRectTextured(imgX - exp, imgY - exp, imgSize + exp * 2, imgSize + exp * 2, coverRound, coverAlpha * alpha);
            }

            String secondaryText = presentation.preparing
                    ? "准备播放 · " + playingMusic.getArtistsName() : playingMusic.getArtistsName();

            if (!presentation.preparing && this.turnComposerIntoLyric.getValue() && CloudMusic.hasPlaybackClock()) {
                LyricLine currentDisplaying = CloudMusic.currentLyric;
                LyricLine next = null;

                if (!CloudMusic.lyrics.isEmpty()) {
                    int currentIndex = CloudMusic.lyrics.indexOf(currentDisplaying);
                    if (currentIndex >= 0 && currentIndex < CloudMusic.lyrics.size() - 1) {
                        next = CloudMusic.lyrics.get(currentIndex + 1);
                    }
                }

                if (currentDisplaying != null) {
                    secondaryText = currentDisplaying.getLyric();
                    artists.setWaitTime(100L);
                    artists.setOneShot(true);

                    if (next != null) {
                        // 行间隔小于 500ms（或开口后 500ms 内算剩余时间）会算出负值，
                        // 负 duration 交给滚动动画是未定义行为，钳到 0（立即滚动）。
                        artists.anim.setDuration(Duration.ofMillis(Math.max(0L,
                                next.timestamp - currentDisplaying.timestamp - 500)));
                    } else {
                        artists.anim.setDuration(Duration.ofMillis(Math.max(0L,
                                CloudMusic.positionMsNow() - currentDisplaying.timestamp - 500)));
                    }

                } else {
                    artists.setWaitTime(2000L);
                    artists.setOneShot(false);
                    artists.anim.setDuration(Duration.ofMillis(0));
                }
            } else {
                artists.setWaitTime(2000L);
                artists.setOneShot(false);
                artists.anim.setDuration(Duration.ofMillis(0));
            }

            double progressBarWidth = width - (imgSize + imgSpacing * 3.25);

            String name1 = playingMusic.getName();

            double musicNameY = imgY + 3 + selectionMotion.offset() * .5;
            musicName.render(FontManager.pf25bold, name1, imgX + imgSize + imgSpacing, musicNameY, progressBarWidth, new Color(1f, 1f, 1f, alpha).getRGB());

            double progressBarOffsetY = y + height - imgSpacing - 3 - FontManager.pf14bold.getFontHeight() - 8;

            artists.render(FontManager.pf20, secondaryText, imgX + imgSize + imgSpacing, musicNameY + FontManager.pf25bold.getFontHeight() + (progressBarOffsetY - (musicNameY + FontManager.pf25bold.getFontHeight())) * .5 - FontManager.pf20.getFontHeight() * .5, progressBarWidth, new Color(1f, 1f, 1f, alpha * 0.8f).getRGB());

            this.roundedRect(imgX + imgSize + imgSpacing, progressBarOffsetY, progressBarWidth, 5, 1, 1f, 1f, 1f, alpha * 0.3f);

            if (presentation.preparing) {
                double travel = .5 - .5 * Math.cos(selectionMotion.travel() * Math.PI * 2);
                double span = progressBarWidth * .24;
                this.roundedRect(imgX + imgSize + imgSpacing + (progressBarWidth - span) * travel,
                        progressBarOffsetY, span, 5, 1, 1f, 1f, 1f, alpha * .85f);
                FontManager.pf14bold.drawString("准备中 · 原曲继续播放", imgX + imgSize + imgSpacing,
                        progressBarOffsetY + 9, RGBA.color(255, 255, 255, (int) (alpha * 180)));
            } else if (CloudMusic.hasPlaybackClock()) {
                double totalTimeMillis = CloudMusic.durationMsNow();
                double positionMillis = CloudMusic.positionMsNow();
                double playbackFillWidth = 0.0;
                if (totalTimeMillis > 0.0) {
                    double progress = Math.max(0.0,
                            Math.min(1.0, positionMillis / totalTimeMillis));
                    playbackFillWidth = progressBarWidth * progress;
                }
                if (playbackFillWidth > 0)
                    this.roundedRect(imgX + imgSize + imgSpacing, progressBarOffsetY, playbackFillWidth, 5, Math.min(1, playbackFillWidth * .5), 233, 233, 233, (int) (alpha * 255));

                drawAutomixCue(imgX + imgSize + imgSpacing, progressBarWidth, progressBarOffsetY, 5, alpha, totalTimeMillis <= 0.0 ? -1.0 : positionMillis / totalTimeMillis);

                int cMin = (int) (positionMillis / 1000.0 / 60);
                int cSec = (int) (positionMillis / 1000.0 - cMin * 60);
                String currentTime = (cMin < 10 ? "0" + cMin : cMin) + ":" + (cSec < 10 ? "0" + cSec : cSec);
                int tMin = (int) (totalTimeMillis / 1000.0 / 60);
                int tSec = (int) (totalTimeMillis / 1000.0 - tMin * 60);
                String totalTime = (tMin < 10 ? "0" + tMin : tMin) + ":" + (tSec < 10 ? "0" + tSec : tSec);

                int textColor = RGBA.color(255, 255, 255, (int) (alpha * 128));
                double playbackTimeY = progressBarOffsetY + 9;
                FontManager.pf14bold.drawString(currentTime, imgX + imgSize + imgSpacing, playbackTimeY, textColor);
                FontManager.pf14bold.drawString(totalTime, imgX + imgSize + imgSpacing + progressBarWidth - FontManager.pf14bold.getStringWidthD(totalTime), playbackTimeY, textColor);

            }

            api.getGLStateManager().popMatrix();
        }

        posInterface.setWidth((float) width);
        posInterface.setHeight((float) (height + downloadProgHeight));

        api.getGLStateManager().popMatrix();
        if (needCorrection) {
            // 弹掉真实 HUD 路径压入的 correction 层（与上面 pushMatrix 配对）。
            api.getGLStateManager().popMatrix();
        }
    }

    /**
     * 把 automix 的接缝画在这条进度条上。
     *
     * <p>位置取本帧那份已经算好的计划（{@link CloudMusic#automixCueNow()}），画不出来的时候
     * 就是真画不出来：没预备、预备作废、系统播放器镜像态、或设置里关掉了。这里<b>不</b>自己
     * 推一条「大概在这儿切」——未预备的场合下一次切换根本不会交叠，报一个位置等于骗人。</p>
     *
     * <p>记号经过一层平滑：交接之前计划会被换掉（包络到货的回头重算、拖过切点的顶住重算，
     * 一共三次预算），直接落最新值会让线在歌中间瞬移。换了一首歌则立刻吸附，不做跨越两首的
     * 补间。</p>
     */
    private void drawAutomixCue(double barX, double barWidth, double barTop, double barHeight, float alpha, double playbackFraction) {
        AutomixCueMarker cue = CloudMusic.automixCueNow();
        Music track = CloudMusic.currentlyPlaying;
        long songId = track == null ? Long.MIN_VALUE : track.getId();
        if (songId != cueSongId) {
            cueSongId = songId;
            cueGlide.reset();
        }
        if (cue == null) {
            cueGlide.reset();
            return;
        }
        AutomixCuePainter.paint(barX, barTop, barWidth, barHeight,
                cueGlide.update(cue), cue.fading, alpha, playbackFraction);
    }

    /** 「完全透明」档：不画任何底（bloom 底、封面糊底、压暗层全部跳过）。 */
    private static boolean transparentBackdrop() {
        return HudConfig.infoCardBackdrop == 2;
    }

    /**
     * 「液态玻璃」档。GlassLook.amount 内部已含手机档闸与主题真玻璃强度，闸关着时
     * paintFrost 返回 false，由 {@link #renderLiquidGlassCard} 走假玻璃回退层。
     */
    private static boolean liquidGlassBackdrop() {
        return HudConfig.infoCardBackdrop == 1;
    }

    // 玻璃色与灵动岛液态玻璃款同源（DownloadDynamicIsland.GLASS_TINT/.GLASS_TINT_BRIGHT）：
    // 取色一律来自玻璃色而不是主题强调色，玻璃不该跟着封面/主题偏色跑。
    private static final int CARD_GLASS_TINT = 0x8FB4EC;
    private static final int CARD_GLASS_TINT_BRIGHT = 0xCADEFB;

    private static int clamp255(float value) {
        return Math.max(0, Math.min(255, Math.round(value)));
    }

    /**
     * 歌曲信息卡的液态玻璃表面 —— 灵动岛「液态玻璃」款（renderLiquidGlassSurface）的
     * 1:1 移植，只在两处不同：生效面是 {@link GlassLook.Surface#INFO_CARD}，色值用
     * 本类自己的 CARD_GLASS_TINT 常量。层次与岛完全一致：真玻璃镜铺得上时整面即返
     * （不许再叠实色底）；铺不上时按同款回退 —— 深色基底 + 竖向体积渐变 + 横向色散
     * + 折射亮带 + 边缘折射 + 随时间转动的四角边缘光。
     */
    private void renderLiquidGlassCard(double x, double y, double width, double height,
                                       double radius, float alpha) {
        double seconds = System.currentTimeMillis() / 1000.0;
        int accentBright = CARD_GLASS_TINT_BRIGHT;
        double innerRadius = Math.max(1.0, radius - 1.0);

        boolean frosted = GlassLook.paintFrost(GlassLook.Surface.INFO_CARD, x, y, width, height,
                radius, alpha);
        float thin = frosted ? GlassLook.tintedAlpha(GlassLook.Surface.INFO_CARD, 1.0f) : 1.0f;
        if (frosted) {
            // 参考透镜已提供整面 + 方向性增益 + 边缘行为，上面不许再叠实色底。
            return;
        }
        float glow = GlassLook.edgeGlow(GlassLook.Surface.INFO_CARD);

        // 玻璃本体：偏冷的深色基底压住背景，再叠一层玻璃色的竖向渐变当作体积感。
        roundedRect(x, y, width, height, radius, hexColor(.043f, .055f, .078f, alpha * .82f * thin));
        roundedRectGradientVertical(x, y, width, height, radius,
                new Color((CARD_GLASS_TINT_BRIGHT & 0xFFFFFF) | clamp255(alpha * .17f * thin * 255) << 24, true),
                new Color((CARD_GLASS_TINT & 0xFFFFFF) | clamp255(alpha * .07f * thin * 255) << 24, true));
        // 冷白到紫蓝的横向色散。
        roundedRectGradientHorizontal(x, y, width, height, radius,
                new Color(222, 241, 255, clamp255(alpha * 34f)),
                new Color(122, 152, 240, clamp255(alpha * 26f)));

        // 折射：上半部亮带 + 底部回弹的强调色反射。
        roundedRectGradientVertical(x + 1.0, y + 1.0, Math.max(1.0, width - 2.0),
                Math.max(1.0, height * .52), innerRadius,
                new Color(255, 255, 255, clamp255(alpha * 46f)),
                new Color(255, 255, 255, 0));
        roundedRectGradientVertical(x + 1.0, y + height * .60, Math.max(1.0, width - 2.0),
                Math.max(1.0, height * .38), innerRadius,
                new Color(255, 255, 255, 0),
                new Color((accentBright & 0xFFFFFF) | clamp255(alpha * .13f * 255) << 24, true));

        // 真玻璃的边缘折射：背后画面沿轮廓法线掰一下再采，画在底色与亮带之后。
        GlassLook.paintEdge(GlassLook.Surface.INFO_CARD, x, y, width, height, radius, alpha);

        // 边缘动态取色：四角取自玻璃色的四个色相相位，整圈随时间缓慢转动。
        // （岛那边用 DynamicIslandMath.shiftHue，本类在另一个包够不到，逻辑原样内联。）
        float phase = (float) ((seconds * 26.0) % 360.0);
        roundedOutlineGradient(x, y, width, height, radius, 1.0,
                new Color((shiftHueForCard(accentBright, phase) & 0xFFFFFF) | clamp255(alpha * .80f * glow * 255) << 24, true),
                new Color((shiftHueForCard(accentBright, phase + 70f) & 0xFFFFFF) | clamp255(alpha * .92f * glow * 255) << 24, true),
                new Color((shiftHueForCard(accentBright, phase + 140f) & 0xFFFFFF) | clamp255(alpha * .74f * glow * 255) << 24, true),
                new Color((shiftHueForCard(accentBright, phase + 210f) & 0xFFFFFF) | clamp255(alpha * .90f * glow * 255) << 24, true));
        roundedOutline(x + 1.0, y + 1.0, Math.max(1.0, width - 2.0), Math.max(1.0, height - 2.0),
                innerRadius, .55, new Color(255, 255, 255, clamp255(alpha * 30f * glow)));
    }

    /**
     * 把颜色沿色相环旋转指定角度，并保证饱和度与明度下限 —— 玻璃边缘的动态取色光。
     * 玻璃色本身可能很浅，直接旋转会得到一条看不见的边；与岛那边的 shiftHue 同参数。
     */
    private static int shiftHueForCard(int color, float degrees) {
        float[] hsb = Color.RGBtoHSB((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, null);
        float hue = (float) ((hsb[0] + degrees / 360.0f) % 1.0);
        if (hue < 0f) hue += 1.0f;
        float saturation = Math.max(.42f, Math.min(1f, hsb[1]));
        float brightness = Math.max(.82f, Math.min(1f, hsb[2]));
        return Color.HSBtoRGB(hue, saturation, brightness) & 0xFFFFFF;
    }

}
