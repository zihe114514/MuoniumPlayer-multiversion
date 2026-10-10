package com.muoniumplayer.core.widget.impl;

import com.muoniumplayer.api.enums.EnumModuleCategory;
import com.muoniumplayer.api.features.ExtensionModule;
import com.muoniumplayer.api.features.ExtensionWidget;
import com.muoniumplayer.api.interfaces.EventHandler;
import com.muoniumplayer.api.interfaces.modules.values.BooleanValue;
import com.muoniumplayer.api.interfaces.modules.values.NumberValue;
import com.muoniumplayer.core.interfaces.SharedConstants;
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
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.Tuple;
import com.muoniumplayer.core.utils.WidgetWrapper;

import java.awt.*;
import java.time.Duration;

/**
 * @author MuoniumPlayer Contributors
 * Date: 2025/2/16 14:19
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
    ITextureObject prevBlurredBg = null;
    ITextureObject prevBg = null;
    Music prevMusic = null;

    /** automix 接缝记号的平滑与换歌记号，见 {@link AutomixCueMarker.Glide}。 */
    private final AutomixCueMarker.Glide cueGlide = new AutomixCueMarker.Glide();
    private final TrackSelectionMotion selectionMotion = new TrackSelectionMotion();
    private long cueSongId = Long.MIN_VALUE;

    public void onRender() {

        // HUD 位置 + 缩放（HudConfig，由 GuiHudEditor 拖拽/滚轮调整）。
        // 位置公式与参考实现一致：pixel = pos * (screen - baseSize * scale)；缩放围绕左上锚点等比进行。
        float hudScale = HudConfig.infoScale;
        float hudX = (float) (HudConfig.infoX * (getWidth() - 230 * hudScale));
        float hudY = (float) (HudConfig.infoY * (getHeight() - 56 * hudScale));
        posInterface.setX(hudX);
        posInterface.setY(hudY);

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

                this.roundedRect(posInterface.getX(), posInterface.getY(), width, height + downloadProgHeight, bgRound, 1, 0, 0, 0, alpha * 0.7f);

                api.getGLStateManager().popMatrix();
            });

            {

                double posX = posInterface.getX();
                double posY = posInterface.getY();

                // 用上面那个已经判过空的局部量，别再读一遍 CloudMusic.currentlyPlaying：
                // 「先判空再取值」这种写法在停播那一帧就会读到 null。
                // 镜像中且外部播放器给了封面时，这张底由那张图现算（CloudMusic 那一处统一分叉）。
                Location musicCoverBlurred = CloudMusic.preferredBlurredCoverLocation(playingMusic);

                TextureManager textureManager = TextureManager.getInstance();
                ITextureObject texBg = textureManager.getTexture(musicCoverBlurred);

                if (texBg != null || prevBlurredBg != null) {

                    if (playingMusic != prevMusic) {
                        prevBlurredBg = prevMusic == null ? null : textureManager.getTexture(prevMusic.getBlurredCoverLocation());
                        prevBg = prevMusic == null ? null : textureManager.getTexture(prevMusic.getCoverLocation());
                        prevMusic = playingMusic;
                        musicBgAlpha = 0.0f;
                    }

                    double v = (height) / width;

                    if (prevBlurredBg != null && musicBgAlpha < 0.99f) {
                        api.getGLStateManager().bindTexture(prevBlurredBg.getGlTextureId());
                        prevBlurredBg.linearFilter();
                        this.roundedRectTextured(posX, posY, width, height + downloadProgHeight, 0, v, 1, v, bgRound, 1, alpha);
                    }

                    if (texBg != null) {
                        this.musicBgAlpha = Interpolations.interpolate(this.musicBgAlpha, 1.0f, 0.3f);
                        api.getGLStateManager().bindTexture(texBg.getGlTextureId());
                        texBg.linearFilter();
                        this.roundedRectTextured(posX, posY, width, height + downloadProgHeight, 0, .5 - v * .5, 1, v, bgRound, 1, this.musicBgAlpha * alpha);
                    }

                }
            }

            this.roundedRect(posInterface.getX(), posInterface.getY(), width, height + downloadProgHeight, bgRound, 1, 0, 0, 0, alpha * 0.25f);

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

            if (prevBg != null) {
                api.getGLStateManager().bindTexture(prevBg.getGlTextureId());
                prevBg.linearFilter();
                double exp = 0;
                this.roundedRectTextured(imgX - exp, imgY - exp, imgSize + exp * 2, imgSize + exp * 2, coverRound, alpha);
            }

            if (texture != null) {
                api.getGLStateManager().bindTexture(texture.getGlTextureId());
                texture.linearFilter();
                double exp = 0;
                this.roundedRectTextured(imgX - exp, imgY - exp, imgSize + exp * 2, imgSize + exp * 2, coverRound, this.musicBgAlpha * alpha);
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
                        artists.anim.setDuration(Duration.ofMillis(next.timestamp - currentDisplaying.timestamp - 500));
                    } else {
                        artists.anim.setDuration(Duration.ofMillis(CloudMusic.positionMsNow() - currentDisplaying.timestamp - 500));
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

}
