package com.muoniumplayer.core.screens.ncm.panels;

import com.muoniumplayer.api.enums.EnumChatColor;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.PlaybackPresentation;
import com.muoniumplayer.core.ncm.music.CrossSourceSwitcher;
import com.muoniumplayer.core.ncm.music.GdStudioMusicService;
import com.muoniumplayer.core.ncm.music.LocalPlaylistStore;
import com.muoniumplayer.core.ncm.music.QishuiMusicService;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.FoliaLucideIcons;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.PlayerQueueIcons;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.texture.Textures;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.widgets.IconWidget;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.LucideIconWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedImageWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.SheetHeroRelay;
import com.muoniumplayer.core.screens.ncm.SheetClock;
import com.muoniumplayer.core.screens.ncm.TrackSelectionMotion;
import com.muoniumplayer.core.utils.Location;

import java.awt.*;

/**
 */
public class MusicWidget extends RoundedRectWidget {

    /** 时长文本与行右边缘之间的留白。 */
    private static final double DURATION_RIGHT_MARGIN = 8.0;
    /** 右侧每一枚操作图标的边长。 */
    private static final double ACTION_ICON_SIZE = 20.0;
    /** 右侧控件彼此之间的间距。 */
    private static final double ACTION_GAP = 4.0;
    /** 一小时的毫秒数：到这个长度 {@link #formatDuration(long)} 才会多出小时位。 */
    private static final long ONE_HOUR_MILLIS = 3600_000L;

    private final TrackSelectionMotion selectionMotion = new TrackSelectionMotion();
    private PlaybackPresentation presentation = new PlaybackPresentation(null, null, 0L);
    private boolean displaySelected;
    private double selectionEmphasis;
    private long feedbackNanos;

    public PlayList playList;
    public Music music;
    boolean coverLoaded = false;
    /** 进场进度与那段落位矩阵，见 {@link PanelItemEntrance}。 */
    private final PanelItemEntrance entrance = new PanelItemEntrance();
    private double entranceEased = 1.0;
    /** 第一次被渲染时向它领一个错峰号；{@code null} 表示这一行不做进场。 */
    private PanelItemEntrance.RevealQueue entranceQueue;

    public MusicWidget(Music music, PlayList playList, int index) {
        super(0, 0, 0, 30);
        this.music = music;
        this.playList = playList;

        RoundedRectWidget rrHoverIndicator = new RoundedRectWidget();
        this.addChild(rrHoverIndicator);
        rrHoverIndicator
                .setAlpha(0f)
                .setClickable(false);
        rrHoverIndicator.setBeforeRenderCallback(() -> rrHoverIndicator
                .setMargin(0)
                .setRadius(this.getRadius())
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)));

        RoundedRectWidget rrPlayingIndicator = new RoundedRectWidget() {
            @Override public void onRender(double mouseX, double mouseY) {
                float opacity = getAlpha();
                if (opacity < .01f) return;
                int accent = NCMScreen.getColor(NCMScreen.ColorType.ACCENT);
                double glow = selectionMotion.burst() * .10 + selectionMotion.settledGlow() * .12;
                roundedRect(getX(), getY(), getWidth(), getHeight(), 3,
                        reAlpha(accent, (float) (opacity * (.17 + glow))));
                roundedOutline(getX() + .5, getY() + .5, getWidth() - 1, getHeight() - 1, 3, .6,
                        new Color(reAlpha(accent, opacity * .35f), true));
                // 左侧光轨是持续的选中标记，不再用一整块高饱和蓝色遮住玻璃与文本。
                roundedRect(getX() + 1, getY() + 6, 2, getHeight() - 12, 1,
                        reAlpha(accent, opacity * .95f));
                // 只在点击后扫过一次；所有光片夹在本行内部，不溢出滚动区。
                double sweep = selectionMotion.sweep();
                if (sweep < 1.0) {
                    double head = (getWidth() + 100) * sweep - 100;
                    for (int part = 0; part < 12; part++) {
                        double left = Math.max(4, head + part * 7);
                        double right = Math.min(getWidth() - 4, head + part * 7 + 7);
                        if (right > left) roundedRect(getX() + left, getY() + 2, right - left,
                                getHeight() - 4, 0, reAlpha(accent,
                                (float) (opacity * .13 * Math.sin((part + 1) / 13.0 * Math.PI) * (1 - sweep))));
                    }
                }
                // 序号位让给状态动画：准备中为三颗呼吸点，起播后为三根均衡器音柱。
                boolean moving = CloudMusic.isAudibleNow();
                for (int bar = 0; bar < 3; bar++) {
                    double wave = selectionMotion.pulse(bar);
                    double height = presentation.preparing ? 2.5 : moving ? 3 + wave * 7 : 3;
                    roundedRect(getX() + 16 + bar * 3.5, getY() + (getHeight() - height) * .5,
                            2, height, 1, reAlpha(accent,
                            (float) (opacity * (presentation.preparing ? .4 + wave * .6 : 1))));
                }
            }
        };
        this.addChild(rrPlayingIndicator);
        rrPlayingIndicator.setClickable(false);
        rrPlayingIndicator.setBeforeRenderCallback(() -> rrPlayingIndicator
                .setMargin(0).setRadius(3));

        this.setBeforeRenderCallback(() -> {

            // 只在这个 music 被渲染的时候才加载封面；GD 封面未就绪时下一帧重试
            if (!coverLoaded) {
                coverLoaded = this.loadCover();
            }

            this.setBounds(this.getParentWidth(), 30);
            this.setColor(NCMScreen.getColor(index % 2 == 0 ? NCMScreen.ColorType.ELEMENT_BACKGROUND : NCMScreen.ColorType.GENERIC_BACKGROUND));
            // 行底是压在内容区底之上的第三张底，而且是这一页面积最大的一块——它原本是全不透明，
            // 于是整片列表在玻璃主题下仍然是一块实心的深色。走 setFillAlpha：歌名、歌手、
            // 徽标与右侧图标都是子控件，setAlpha 会把它们连同进场动画一起淡掉。
            this.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
            // 每一行都是<em>一块自己的玻璃</em>：贴预糊背景、压本行底色、绕一圈薄折射与边缘高光。
            // 这一页面积最大的就是这些行，它们不成玻璃，整页就还是一张贴图。
            this.setGlassPlate(GlassLook.Surface.PLAYER);
            this.updateEntranceAnimation();

            presentation = CloudMusic.playbackPresentation();
            boolean selected = presentation.isSelected(music);
            long now = System.nanoTime();
            double elapsed = feedbackNanos == 0 ? 16 : Math.min(100, Math.max(0, (now - feedbackNanos) / 1_000_000.0));
            feedbackNanos = now;
            if (selected) {
                selectionMotion.update(presentation.displayKey(), presentation.requestNanos,
                        presentation.preparing, now, SheetClock.speed());
                // 点击第一帧就亮，而不是等若干帧指数逼近才知道自己选中了。
                if (!displaySelected) selectionEmphasis = Math.max(.72, selectionEmphasis);
            }
            displaySelected = selected;
            double target = selected ? 1 : 0;
            selectionEmphasis = target + (selectionEmphasis - target) * Math.exp(-elapsed / 80.0);
            rrPlayingIndicator.setAlpha((float) selectionEmphasis);
            // 两条状态独立收尾：鼠标仍悬停在旧行时，旧行也必须退掉选中高光。
            float hoverTarget = !selected && this.isHovering() ? 1f : 0f;
            rrHoverIndicator.setAlpha(Interpolations.interpolate(rrHoverIndicator.getWidgetAlpha(), hoverTarget, .3f));
            rrHoverIndicator.setHidden(false);

            this.setRadius(2);
        });

        this.setOnClickCallback((x, y, i) -> {

            if (i == 0) {
                CloudMusic.currentPlaylistContext = playList.isSearchMode() ? null : playList;
                if (playList.isPersonalFm()) {
                    CloudMusic.playFm(playList.getMusics(), index);
                } else if (playList.isSearchResultList()) {
                    // 搜索结果不顺着搜索排序往下播，改为接用户的"最近播放"。
                    CloudMusic.playFromSearchSelection(this.music, playList.getMusics(), index);
                } else {
                    CloudMusic.play(playList.getMusics(), index);
                }
            } else if (i == 1) {
                // 右键 = 手动换源。流水线全在后台线程跑，结果回投主线程替换列表里的这一格；
                // 同一首歌在结果回来之前的重复右键会被服务层的 in-flight 账吃掉。
                CrossSourceSwitcher.requestManualSwitch(this.music, playList, index);
            } else if (i == 2) {
                // 中键 = 给这一行手动选词。右键已经被换源占了，这套 UI 框架里也没有行级上下文菜单，
                // 中键是唯一一处不会和任何已有手势打架的按键（鼠标按键 0/1 之外没人用过）。
                // 弹窗只认这一行的这首歌：预览时钟停住、钉词也只写它的 stableKey。
                NCMScreen.getInstance().openLyricSearch(this.music);
            }

            return true;
        });

        RoundedImageWidget cover = new RoundedImageWidget(this.music.getSmallCoverLocation(), 0, 0, 0, 0);
        this.addChild(cover);
        cover.fadeIn();
        cover.setLinearFilter(true);
        cover.setBeforeRenderCallback(() -> {
            cover.setRadius(2);
            cover.setBounds(24, 24);
            cover.centerVertically();
            cover.setPosition(30, cover.getRelativeY());
        });
        cover.setClickable(false);

        LabelWidget lblMusicIndex = new LabelWidget(String.valueOf(index + 1), FontManager.pf14bold);
        this.addChild(lblMusicIndex);

        lblMusicIndex.setBeforeRenderCallback(() -> {
            lblMusicIndex.setAlpha(displaySelected ? 0f : (float) (1.0 - selectionEmphasis));
            if (displaySelected)
                lblMusicIndex.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            else
                lblMusicIndex.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            lblMusicIndex.centerVertically();
            lblMusicIndex.setPosition(cover.getRelativeX() - 4 - lblMusicIndex.getWidth(), lblMusicIndex.getRelativeY());
        });

        lblMusicIndex.setClickable(false);

        boolean musicDirty = music.isDirty();
        double dirtyIndicatorSize = 8;
        boolean digitalAlbumTrack = music.isDigitalAlbumTrack();
        // 购买数字专辑是一种与仅 VIP 流媒体收听不同的权益；
        // 显示一枚清晰的徽标，而不是冗余的 "VIP + 专辑" 组合。
        boolean vipRestricted = music.hasVipRestriction() && !digitalAlbumTrack;
        boolean cloudSong = music.isCloudSong();
        String highestQuality = music.getHighestQualityLabel();
        boolean showHighestQuality = highestQuality != null && !highestQuality.isEmpty();
        String gdSourceLabel = music.isGd() ? GdStudioMusicService.displayName(music.getGdPlatform()) : "";
        boolean showGdSource = music.isGd() && !gdSourceLabel.isEmpty();
        double gdBadgeWidth = showGdSource ? Math.max(26.0, gdSourceLabel.length() * 7.0 + 10.0) : 0.0;
        // 音效标签：网易云 mark 位里的杜比全景声与纯伴奏。两个位一直有定义却没有任何 UI 用过。
        // 刻意不展示 STEREO(8192)：那一位的确切含义在上游没有可靠说明，宁可不标也不要标错。
        boolean dolbyAtmos = music.isDolbyAtmos();
        boolean instrumental = music.isInstrumental();
        double dolbyBadgeWidth = dolbyAtmos ? 34.0 : 0.0;
        double instrumentalBadgeWidth = instrumental ? 27.0 : 0.0;
        double accessBadgeReserve = (vipRestricted ? 24.0 : 0.0) + (digitalAlbumTrack ? 28.0 : 0.0)
                + (music.isQishui() && music.hasVipRestriction() ? 28.0 : 0.0)
                + (cloudSong ? 28.0 : 0.0) + (showHighestQuality ? 38.0 : 0.0) + gdBadgeWidth
                + (dolbyAtmos ? dolbyBadgeWidth + 3.0 : 0.0)
                + (instrumental ? instrumentalBadgeWidth + 3.0 : 0.0);
        // 解灰标签是播放之后才知道结果的，所以它占的宽度必须逐帧算，否则标签出现时会压在长标题上。
        final double[] unblockBadgeReserve = {0.0};

        String translatedNames = music.getTranslatedNames();

        LabelWidget lblMusicName = new LabelWidget(music.getName() + (translatedNames.isEmpty() ? "" : EnumChatColor.GRAY + " (" + translatedNames + ")"), FontManager.pf14bold);
        this.addChild(lblMusicName);

        lblMusicName
                .setWidthLimitType(LabelWidget.WidthLimitType.SCROLL)
                .setBeforeRenderCallback(() -> {
                    lblMusicName.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                    lblMusicName.centerVertically();
                    lblMusicName.setPosition(cover.getRelativeX() + cover.getWidth() + 4, lblMusicName.getRelativeY() - lblMusicName.getHeight() * .5 - 2);
                    // 为时长/操作按钮和版权徽章预留空间，确保没有徽章会与被截断的标题重叠。
                    lblMusicName.setMaxWidth(Math.max(1.0, this.getWidth() - (cover.getRelativeX() + cover.getWidth() + 4
                            + rightActionsReserve() + (musicDirty ? (dirtyIndicatorSize + 4) : 0)
                            + accessBadgeReserve + unblockBadgeReserve[0])));
                });
        lblMusicName.setClickable(false);

        double nextAccessBadgeX = 0.0;
        if (vipRestricted) {
            RoundedRectWidget vipBadge = new RoundedRectWidget(0, 0, 21, 10);
            this.addChild(vipBadge);
            vipBadge.setClickable(false);
            vipBadge.setBeforeRenderCallback(() -> {
                vipBadge.setRadius(2.5);
                vipBadge.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
                vipBadge.setAlpha(.92f);
                vipBadge.setPosition(lblMusicName.getRelativeX() + lblMusicName.getWidth() + 3,
                        lblMusicName.getRelativeY() + lblMusicName.getHeight() * .5 - vipBadge.getHeight() * .5);
            });
            LabelWidget vipText = new LabelWidget("VIP", FontManager.pf12bold);
            vipBadge.addChild(vipText);
            vipText.setClickable(false);
            vipText.setBeforeRenderCallback(() -> {
                vipText.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                vipText.center();
            });
            nextAccessBadgeX = 24.0;
        }

        // 汽水「试听」标：与 VIP 同款胶囊、同一条推进线。判定是「曲目要求 × 账号能力」的
        // 对照（见 QishuiMusicService.vipContentRestrictedToTrial）：VIP 曲目要完整版需要
        // 登录 + VIP 账号 + 本机签名后端三样齐，缺一播前就能断定只拿得到试听——这是判定
        // 不是猜测，所以标签准确。播放过的曲目由播放层点亮的 isQishuiTrialResolve 兜底
        // （覆盖权益内仍拿不到完整版的罕见情形，如地区限制）。状态每帧可变，显隐用 alpha
        // 收放而非 setHidden（隐藏组件的回调不再执行，自己没法复活）。
        boolean trialDependingOnState = music.isQishui() && music.hasVipRestriction();
        if (trialDependingOnState) {
            final double trialOffset = nextAccessBadgeX;
            LabelWidget trialText = new LabelWidget("试听", FontManager.pf12bold);
            RoundedRectWidget trialBadge = new RoundedRectWidget(0, 0, 25, 10);
            this.addChild(trialBadge);
            trialBadge.setClickable(false);
            trialBadge.setBeforeRenderCallback(() -> {
                if (!QishuiMusicService.vipContentRestrictedToTrial()
                        && !music.isQishuiTrialResolve()) {
                    trialBadge.setAlpha(0f);
                    trialText.setAlpha(0f);
                    return;
                }
                trialBadge.setAlpha(.92f);
                trialText.setAlpha(1f);
                trialBadge.setRadius(2.5);
                trialBadge.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER));
                trialBadge.setPosition(lblMusicName.getRelativeX() + lblMusicName.getWidth() + 3 + trialOffset,
                        lblMusicName.getRelativeY() + lblMusicName.getHeight() * .5 - trialBadge.getHeight() * .5);
            });
            trialBadge.addChild(trialText);
            trialText.setClickable(false);
            trialText.setBeforeRenderCallback(() -> {
                trialText.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                trialText.center();
            });
            nextAccessBadgeX += 28.0;
        }

        if (digitalAlbumTrack) {
            final double albumBadgeOffset = nextAccessBadgeX;
            RoundedRectWidget albumBadge = new RoundedRectWidget(0, 0, 25, 10);
            this.addChild(albumBadge);
            albumBadge.setClickable(false);
            albumBadge.setBeforeRenderCallback(() -> {
                albumBadge.setRadius(2.5);
                albumBadge.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER));
                albumBadge.setAlpha(.95f);
                albumBadge.setPosition(lblMusicName.getRelativeX() + lblMusicName.getWidth() + 3 + albumBadgeOffset,
                        lblMusicName.getRelativeY() + lblMusicName.getHeight() * .5 - albumBadge.getHeight() * .5);
            });
            LabelWidget albumText = new LabelWidget("专辑", FontManager.pf12bold);
            albumBadge.addChild(albumText);
            albumText.setClickable(false);
            albumText.setBeforeRenderCallback(() -> {
                albumText.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                albumText.center();
            });
        }

        // 让标题徽章保持在同一推进行中：VIP / 数字专辑 / 云盘 / 最高等级。
        nextAccessBadgeX += digitalAlbumTrack ? 28.0 : 0.0;
        if (cloudSong) {
            final double cloudBadgeOffset = nextAccessBadgeX;
            RoundedRectWidget cloudBadge = new RoundedRectWidget(0, 0, 25, 10);
            this.addChild(cloudBadge);
            cloudBadge.setClickable(false);
            cloudBadge.setBeforeRenderCallback(() -> {
                cloudBadge.setRadius(2.5);
                cloudBadge.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER));
                cloudBadge.setAlpha(.95f);
                cloudBadge.setPosition(lblMusicName.getRelativeX() + lblMusicName.getWidth() + 3 + cloudBadgeOffset,
                        lblMusicName.getRelativeY() + lblMusicName.getHeight() * .5 - cloudBadge.getHeight() * .5);
            });
            LabelWidget cloudText = new LabelWidget("网盘", FontManager.pf12bold);
            cloudBadge.addChild(cloudText);
            cloudText.setClickable(false);
            cloudText.setBeforeRenderCallback(() -> {
                cloudText.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                cloudText.center();
            });
            nextAccessBadgeX += 28.0;
        }

        if (showHighestQuality) {
            final double qualityBadgeOffset = nextAccessBadgeX;
            final double qualityBadgeWidth = "Hi-Res".equals(highestQuality) ? 34.0 : 27.0;
            RoundedRectWidget qualityBadge = new RoundedRectWidget(0, 0, qualityBadgeWidth, 10);
            this.addChild(qualityBadge);
            qualityBadge.setClickable(false);
            qualityBadge.setBeforeRenderCallback(() -> {
                qualityBadge.setRadius(2.5);
                qualityBadge.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
                qualityBadge.setAlpha(.82f);
                qualityBadge.setPosition(lblMusicName.getRelativeX() + lblMusicName.getWidth() + 3 + qualityBadgeOffset,
                        lblMusicName.getRelativeY() + lblMusicName.getHeight() * .5 - qualityBadge.getHeight() * .5);
            });
            LabelWidget qualityText = new LabelWidget(highestQuality, FontManager.pf12bold);
            qualityBadge.addChild(qualityText);
            qualityText.setClickable(false);
            qualityText.setBeforeRenderCallback(() -> {
                qualityText.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                qualityText.center();
            });
            nextAccessBadgeX += qualityBadgeWidth + 3.0;
        }

        if (showGdSource) {
            final double gdBadgeOffset = nextAccessBadgeX;
            RoundedRectWidget gdBadge = new RoundedRectWidget(0, 0, gdBadgeWidth, 10);
            this.addChild(gdBadge);
            gdBadge.setClickable(false);
            gdBadge.setBeforeRenderCallback(() -> {
                gdBadge.setRadius(2.5);
                gdBadge.setColor(0x3D5AFE);
                gdBadge.setAlpha(.92f);
                gdBadge.setPosition(lblMusicName.getRelativeX() + lblMusicName.getWidth() + 3 + gdBadgeOffset,
                        lblMusicName.getRelativeY() + lblMusicName.getHeight() * .5 - gdBadge.getHeight() * .5);
            });
            LabelWidget gdText = new LabelWidget(gdSourceLabel, FontManager.pf12bold);
            gdBadge.addChild(gdText);
            gdText.setClickable(false);
            gdText.setBeforeRenderCallback(() -> {
                gdText.setColor(0xFFFFFF);
                gdText.center();
            });
            nextAccessBadgeX += gdBadgeWidth + 3.0;
        }

        if (dolbyAtmos) {
            final double dolbyBadgeOffset = nextAccessBadgeX;
            RoundedRectWidget dolbyBadge = new RoundedRectWidget(0, 0, dolbyBadgeWidth, 10);
            this.addChild(dolbyBadge);
            dolbyBadge.setClickable(false);
            dolbyBadge.setBeforeRenderCallback(() -> {
                dolbyBadge.setRadius(2.5);
                dolbyBadge.setColor(0x7B4DFF);
                dolbyBadge.setAlpha(.92f);
                dolbyBadge.setPosition(lblMusicName.getRelativeX() + lblMusicName.getWidth() + 3 + dolbyBadgeOffset,
                        lblMusicName.getRelativeY() + lblMusicName.getHeight() * .5 - dolbyBadge.getHeight() * .5);
            });
            LabelWidget dolbyText = new LabelWidget("全景声", FontManager.pf12bold);
            dolbyBadge.addChild(dolbyText);
            dolbyText.setClickable(false);
            dolbyText.setBeforeRenderCallback(() -> {
                dolbyText.setColor(0xFFFFFF);
                dolbyText.center();
            });
            nextAccessBadgeX += dolbyBadgeWidth + 3.0;
        }

        if (instrumental) {
            final double instrumentalBadgeOffset = nextAccessBadgeX;
            RoundedRectWidget instrumentalBadge = new RoundedRectWidget(0, 0, instrumentalBadgeWidth, 10);
            this.addChild(instrumentalBadge);
            instrumentalBadge.setClickable(false);
            instrumentalBadge.setBeforeRenderCallback(() -> {
                instrumentalBadge.setRadius(2.5);
                instrumentalBadge.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER));
                instrumentalBadge.setAlpha(.95f);
                instrumentalBadge.setPosition(
                        lblMusicName.getRelativeX() + lblMusicName.getWidth() + 3 + instrumentalBadgeOffset,
                        lblMusicName.getRelativeY() + lblMusicName.getHeight() * .5
                                - instrumentalBadge.getHeight() * .5);
            });
            LabelWidget instrumentalText = new LabelWidget("伴奏", FontManager.pf12bold);
            instrumentalBadge.addChild(instrumentalText);
            instrumentalText.setClickable(false);
            instrumentalText.setBeforeRenderCallback(() -> {
                instrumentalText.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                instrumentalText.center();
            });
            nextAccessBadgeX += instrumentalBadgeWidth + 3.0;
        }

        final double dirtyBadgeOffset = nextAccessBadgeX;
        if (musicDirty) {
            RoundedRectWidget dirtyIndicator = new RoundedRectWidget(0, 0, dirtyIndicatorSize, dirtyIndicatorSize);
            this.addChild(dirtyIndicator);
            dirtyIndicator
                    .setRadius(1.5)
                    .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));

            dirtyIndicator.setBeforeRenderCallback(() -> {
//                dirtyIndicator.centerVertically();
                dirtyIndicator.setPosition(lblMusicName.getRelativeX() + lblMusicName.getWidth() + 2 + dirtyBadgeOffset, lblMusicName.getRelativeY() + lblMusicName.getHeight() * .5 - dirtyIndicatorSize * .5);
            });

            dirtyIndicator.setClickable(false);

            LabelWidget lblDirty = new LabelWidget("E", FontManager.pf12bold);
            dirtyIndicator.addChild(lblDirty);
            lblDirty.setBeforeRenderCallback(() -> {
                lblDirty.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                lblDirty.center();
            });
        }

        // 解灰 / 灰色标签。它的文案在点播之后才确定，所以文本、宽度、显隐全部逐帧算。
        final double unblockBadgeOffset = nextAccessBadgeX + (musicDirty ? dirtyIndicatorSize + 3.0 : 0.0);
        RoundedRectWidget unblockBadge = new RoundedRectWidget(0, 0, 0, 10);
        this.addChild(unblockBadge);
        unblockBadge.setClickable(false);
        LabelWidget unblockText = new LabelWidget(() -> unblockBadgeText(this.music), FontManager.pf12bold);
        unblockBadge.addChild(unblockText);
        unblockText.setClickable(false);
        unblockText.setBeforeRenderCallback(() -> {
            unblockText.setColor(this.music.getUnblockedSourceLabel().isEmpty()
                    ? NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT) : 0xFFFFFF);
            unblockText.center();
        });
        // 注意：这里不能用 unblockBadge.setHidden(true) 来隐藏自己 ——
        // AbstractWidget.renderWidget 在 isHidden() 时直接 return，连 beforeRenderCallback 都不跑，
        // 一旦自己把自己隐藏掉就再也没有机会恢复。改为收成 0 尺寸 + alpha 0，并只隐藏子文本。
        unblockBadge.setBeforeRenderCallback(() -> {
            String label = unblockBadgeText(this.music);
            if (label.isEmpty()) {
                unblockBadgeReserve[0] = 0.0;
                unblockBadge.setBounds(0, 0);
                unblockBadge.setAlpha(0f);
                unblockText.setHidden(true);
                return;
            }
            double width = Math.max(18.0, FontManager.pf12bold.getStringWidthD(label) + 8.0);
            unblockBadgeReserve[0] = width + 3.0;
            unblockText.setHidden(false);
            unblockBadge.setBounds(width, 10);
            unblockBadge.setRadius(2.5);
            boolean unblocked = !this.music.getUnblockedSourceLabel().isEmpty();
            unblockBadge.setColor(unblocked ? 0x2E9E5B : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER));
            unblockBadge.setAlpha(.95f);
            unblockBadge.setPosition(lblMusicName.getRelativeX() + lblMusicName.getWidth() + 3 + unblockBadgeOffset,
                    lblMusicName.getRelativeY() + lblMusicName.getHeight() * .5 - unblockBadge.getHeight() * .5);
        });

        String albumName = music.getAlbum() == null || music.getAlbum().getName() == null
                ? "未知专辑" : music.getAlbum().getName();
        LabelWidget lblMusicArtist = new LabelWidget(music.getArtistsName() + " - " + albumName, FontManager.pf14);
        this.addChild(lblMusicArtist);

        lblMusicArtist
                .setWidthLimitType(LabelWidget.WidthLimitType.SCROLL)
                .setBeforeRenderCallback(() -> {
                    if (displaySelected)
                        lblMusicArtist.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                    else
                        lblMusicArtist.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
                    lblMusicArtist.centerVertically();
                    lblMusicArtist.setPosition(cover.getRelativeX() + cover.getWidth() + 4, lblMusicArtist.getRelativeY() + lblMusicArtist.getHeight() * .5 + 2);
                    lblMusicArtist.setMaxWidth(this.getWidth() - (cover.getRelativeX() + cover.getWidth() + 4 + rightActionsReserve()));
                });

        lblMusicArtist.setClickable(false);

        // 第三方源在搜索响应中不返回时长；时长是在
        // 解码过程中测量的，因此标签实时读取该值，而不是在构建时固定为 00:00。
        LabelWidget lblMusicDuration = new LabelWidget(
                () -> formatDuration(music.getDuration()), FontManager.pf14bold);
        this.addChild(lblMusicDuration);
        lblMusicDuration.setBeforeRenderCallback(() -> {
            if (displaySelected)
                lblMusicDuration.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            else
                lblMusicDuration.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            lblMusicDuration.centerVertically();
            lblMusicDuration.setPosition(this.getWidth() - 8 - lblMusicDuration.getWidth(), lblMusicDuration.getRelativeY());
        });
        lblMusicDuration.setClickable(false);

        // ===== 收藏：歌曲喜欢/取消喜欢 + 加入歌单 =====
        // 酷狗也走这两个按钮。原先它们被写死成 music.isNetease()，所以切到酷狗后整排收藏控件
        // 直接消失——不是"点了没反应"，是根本没画出来。现在改成"当前源是否支持收藏"。
        final boolean likeable = LikeButton.canFavourite(music);
        final boolean playlistAddable = canAddToPlaylist(music);
        // 红心那一颗（描边与实心红心两态、爆心、取消收藏的二次确认）整个交给 {@link LikeButton}，
        // 私人 FM 的 hero 卡用的是同一份。这里只报位置：挂在固定的时长槽位左边缘上，而不是时长
        // 标签的左边缘 —— 后者宽度随数字变化，会让每一行的图标列各自漂移，上下两行对不齐。
        final LikeButton like = new LikeButton(this, music,
                () -> actionsAnchorX(lblMusicDuration) - ACTION_GAP - LikeButton.BOX);

        // 图标走字体（player-action-icons U+E113 方框加号）而不是 playlist.png：字形按实际字号
        // 运行时生成，不再受离线烘图的尺寸/裁切影响，也和相邻的「下一首播放」共用同一套墨迹对齐。
        IconWidget btnAddToPlaylist = PlayerQueueIcons.newAddToPlaylistButton(20);
        this.addChild(btnAddToPlaylist);
        btnAddToPlaylist.setShouldOverrideMouseCursor(true);
        btnAddToPlaylist.setHidden(!playlistAddable);
        btnAddToPlaylist.setClickable(playlistAddable);
        btnAddToPlaylist.setBeforeRenderCallback(() -> {
            btnAddToPlaylist.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            btnAddToPlaylist.centerVertically();
            btnAddToPlaylist.setPosition(like.getRelativeX() - ACTION_GAP - btnAddToPlaylist.getWidth(),
                    btnAddToPlaylist.getRelativeY());
        });
        btnAddToPlaylist.setOnClickCallback((x, y, button) -> {
            if (button != 0 || !playlistAddable)
                return false;
            // 这块板从「+」飞到弹窗顶部那行歌名上：它要说的是「你要动的是这一首」，
            // 而那枚「+」本身就是「给这一首加进去」—— 两者是同一个操作的两端。
            NCMScreen.getInstance().openAddToPlaylist(music, SheetHeroRelay.from(
                    btnAddToPlaylist.getX(), btnAddToPlaylist.getY(),
                    btnAddToPlaylist.getWidth(), btnAddToPlaylist.getHeight(), 5.0,
                    () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT)));
            return true;
        });

        // ===== 下一首播放：把这首歌插到当前歌曲之后，按点击顺序排队 =====
        // 对所有源都可用，而不仅限于网易云：它只重排本地播放队列，因此它
        // 不需要任何账户或 API。
        // 图标走字体（player-queue-icons U+E309）而不是贴图：烘成 PNG 的那一版把字形放大并裁掉了
        // 一角，且每个播放器缩放下都要重新采样。
        IconWidget btnPlayNext = PlayerQueueIcons.newPlayNextButton(20);
        this.addChild(btnPlayNext);
        btnPlayNext.setShouldOverrideMouseCursor(true);
        btnPlayNext.setBeforeRenderCallback(() -> {
            boolean queued = CloudMusic.isQueuedNext(music);
            btnPlayNext.setColor(queued ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                    : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            btnPlayNext.centerVertically();
            // 图标列从右往左是「时长槽位 ← 收藏 ← 加入歌单 ← 下一首」。取最靠右那个可见控件当锚点：
            // 隐藏的控件位置不可靠（B 站就只有收藏没有加入歌单，全不支持的源两个都没有），
            // 回退到固定的时长槽位会让下一首图标压到红心身上。
            double anchorX = playlistAddable ? btnAddToPlaylist.getRelativeX()
                    : likeable ? like.getRelativeX()
                    : actionsAnchorX(lblMusicDuration);
            btnPlayNext.setPosition(anchorX - ACTION_GAP - btnPlayNext.getWidth(), btnPlayNext.getRelativeY());
        });
        btnPlayNext.setOnClickCallback((x, y, button) -> {
            if (button != 0)
                return false;
            CloudMusic.playNext(music);
            return true;
        });

        // ===== 从这张本地单里移除 =====
        // 只在当前这张单是本机自建时出现：账号歌单的「移除」要写回平台云端，那件事归详情页和收藏
        // 按钮管，不该在这里冒充能做。本地单没有这个问题 —— 删的就是本机那份 JSON 里的一行。
        if (playList != null && playList.isLocalPlaylist()) {
            LucideIconWidget btnRemoveFromList = new LucideIconWidget(() -> FoliaLucideIcons.CLOSE,
                    () -> "✕", FontManager.pf14bold, 0, 0, ACTION_ICON_SIZE, ACTION_ICON_SIZE);
            this.addChild(btnRemoveFromList);
            btnRemoveFromList.setBeforeRenderCallback(() -> {
                btnRemoveFromList.centerVertically();
                btnRemoveFromList.setPosition(btnPlayNext.getRelativeX() - ACTION_GAP
                        - btnRemoveFromList.getWidth(), btnRemoveFromList.getRelativeY());
                // 只在悬停时提亮：常驻一枚深色叉号排在行尾，会被读成「这一行有什么不对」。
                btnRemoveFromList.setColor(NCMScreen.getColor(btnRemoveFromList.isHovering()
                        ? NCMScreen.ColorType.PRIMARY_TEXT : NCMScreen.ColorType.SECONDARY_TEXT));
            });
            btnRemoveFromList.setOnClickCallback((x, y, button) -> {
                if (button != 0) return false;
                if (!LocalPlaylistStore.removeTrack(playList, music)) return true;
                NCMScreen.getInstance().markDirty();
                DownloadDynamicIsland.showModelOperationResult("本地歌单",
                        "已从「" + playList.getName() + "」移除《" + music.getName() + "」", true);
                // 行不会自己消失：曲目列表是建页时一次性铺出来的，只能整页重铺一次。
                NCMScreen.getInstance().reloadCurrentPanel();
                return true;
            });
        }
    }

    /**
     * 「加入歌单」该不该画出来。
     *
     * <p>弹的是网易云歌单选择框，一首 B 站稿件塞不进去，所以只有落在网易云曲库里的源才开放。
     * 例外是本机自建歌单：它的曲目存在本地、取流按逐曲的 {@code source} 分支走，任何源的歌都放得进 ——
     * 所以只要用户手上有一张本地单，这颗按钮对他就是有效的，不该因为当前源不支持云端歌单就藏掉。</p>
     */
    private static boolean canAddToPlaylist(Music music) {
        return music != null && (music.isNetease() || music.isKuGou() || LocalPlaylistStore.hasAny());
    }

    /**
     * 让曲目行有一次进场：淡入 + 从下方落进来 + 微微放大。
     *
     * <p>动作本身在 {@link PanelItemEntrance} 里（主页的歌单卡片用的是同一套）。位移与缩放全走
     * 矩阵，不改动它们真实的布局边界（从而保持滚动和点击检测的稳定）。</p>
     *
     * <p>这里只挂发号器，不立刻起算：{@code ScrollPanel} 不渲染视口外的行，所以本控件第一次被
     * 渲染就是它刚滚进视口的那一帧。建页时就把号发完的话，下面的行在你滚到它之前早演完了。</p>
     */
    public MusicWidget setEntranceAnimation(PanelItemEntrance.RevealQueue queue) {
        this.entranceQueue = queue;
        this.entranceEased = 0.0;
        this.setAlpha(0f);
        this.setTransformations(() -> PanelItemEntrance.applyTransform(this.entranceEased,
                this.getX() + this.getWidth() * .5, this.getY() + this.getHeight() * .5));
        return this;
    }

    private void updateEntranceAnimation() {
        if (!this.entrance.isStarted()) {
            if (this.entranceQueue == null) return;
            this.entrance.begin(System.currentTimeMillis(), this.entranceQueue.nextDelayMillis());
        }
        if (entrance.isFinished()) {
            return;
        }

        this.entranceEased = entrance.tick();
        this.setAlpha((float) this.entranceEased);
        if (entrance.isFinished()) {
            // 摘掉变换：一首 200 行的歌单不该为已经停住的行一直付一次 pushMatrix。
            this.setTransformations(null);
        }
    }

    private void finishEntranceAnimation() {
        this.entrance.finishImmediately();
        this.entranceEased = 1.0;
        this.setAlpha(1f);
        this.setTransformations(null);
    }

    /** Do not bring a half-transparent row back when fast scrolling reverses direction. */
    @Override
    public void onViewportExit() {
        if (!this.entrance.isFinished()) {
            this.finishEntranceAnimation();
        }
    }

    /** Finish the row before its parent page is dropped by an interrupted navigation. */
    @Override
    public void finishTransientAnimations() {
        this.finishEntranceAnimation();
        this.entranceQueue = null;
        super.finishTransientAnimations();
    }

    /**
     * 时长文本占用的固定槽位宽度。
     *
     * <p>时长用的是比例字体：pf14bold 里 {@code '1'} 只有 3.5 逻辑像素，{@code '0'} / {@code '8'} 是 5.0，
     * 于是同样五个字符的 {@code "11:11"} 量出来 16.0、{@code "00:00"} 量出来 22.0，宽度能差 6 个逻辑像素。
     * 右侧那几枚图标原来是一路挂在时长标签左边缘上的，因此每一行的图标列都跟着自己那首歌的数字宽度
     * 左右漂移，上下相邻两行就对不齐——尤其是列表末尾几行凑在一起时最明显。</p>
     *
     * <p>这里按"用最宽的那个数字铺满模板"算出一个固定槽位：图标挂在槽位左边缘，列因此是死的；时长文本
     * 本身仍然右对齐贴着行的右边缘（数字按右边缘对齐，与主流播放器一致）。宽度是从字体现量的，以后换
     * 字体或改字号都会自己跟着变，不留经验常数。</p>
     *
     * <p>是否留出小时位按这一行自己的时长决定。一个歌单里"有小时"基本是全有或全无（普通歌单一首都没有，
     * 混音 / 播客歌单全都有），所以两种常见情况下都是完美对齐；极少数混排歌单里也只是退回到原来的行为，
     * 不会比现在更差，而给每一行都无条件留出小时位会白扔 12 个逻辑像素。</p>
     *
     * <p>刻意做成包级可见而不是私有：这条算式的正确性完全取决于字体的真实度量，需要能离线量。</p>
     */
    static double durationSlotWidth(boolean withHours) {
        CFontRenderer fr = FontManager.pf14bold;
        double digit = 0.0;
        for (char c = '0'; c <= '9'; c++) {
            digit = Math.max(digit, fr.getCharWidth(c, '\0'));
        }
        double colon = fr.getCharWidth(':', '\0');
        return digit * (withHours ? 6 : 4) + colon * (withHours ? 2 : 1);
    }

    private double durationSlotWidth(LabelWidget lblMusicDuration) {
        // 再拿标签自己的实测宽度兜一层底：字形是懒加载的，首帧 getCharWidth 会返回 0；万一以后
        // formatDuration 的格式变了，槽位也不会窄到把文本压进图标底下。
        return Math.max(durationSlotWidth(this.music.getDuration() >= ONE_HOUR_MILLIS),
                lblMusicDuration.getWidth());
    }

    /** 右侧图标列的锚点，也就是时长槽位的左边缘。 */
    private double actionsAnchorX(LabelWidget lblMusicDuration) {
        return this.getWidth() - DURATION_RIGHT_MARGIN - durationSlotWidth(lblMusicDuration);
    }

    /**
     * 右侧那一组控件（时长 + 图标）实际占掉的宽度，标题与艺术家按它留白。
     *
     * <p>原先这里写的是固定 {@code 80 / 40}。那是「加入歌单」与「下一首播放」两枚图标加进来之前的数字，
     * 现在实际占用是 102 / 54，于是长标题会一直滑到图标底下（标题用的是滚动式限宽，看起来就是文字从图标
     * 下面穿过去）。改成和图标锚点同一套算式之后，两边永远一致。</p>
     */
    private double rightActionsReserve() {
        double slot = durationSlotWidth(this.music.getDuration() >= ONE_HOUR_MILLIS);
        // 「下一首」人人都有，另外几枚按各自的开关数。之前这里写死成 isNetease() ? 3 : 1，
        // 于是酷狗/B 站画了三枚却只留一枚的宽度，长标题会滑到图标底下。
        int iconCount = 1 + (canAddToPlaylist(this.music) ? 1 : 0) + (LikeButton.canFavourite(this.music) ? 1 : 0)
                + (this.playList != null && this.playList.isLocalPlaylist() ? 1 : 0);
        return DURATION_RIGHT_MARGIN + slot + iconCount * (ACTION_GAP + ACTION_ICON_SIZE);
    }

    /**
     * 换源 / 解灰 / 灰色标签。
     *
     * <p>一枚徽标三种念法，前缀说的是<b>换了什么</b>：别的平台换进来的曲目 →
     * 「已换源 · 落点」，本平台自己解出来的第三方流 → 「解灰 · 源名」，其中脚本流的源名带
     * <b>渠道键</b>（「野花·kw」），换源落到某平台官方流时落点是那个平台的显示名（「网易云」）；
     * 官方链路取不到流且「音乐是否可用」判定为不可用 → 「灰」。合成一枚而不是三枚，是因为
     * 这些状态互斥，分开占位会在长标题上白白吃掉一段宽度。</p>
     *
     * <p>「落到哪家」必须由徽标说清楚：换源成功与换源失败在这一行上原本长得一模一样，
     * 而玩家判断「我的会员能不能听到原曲」看的正是这一位。</p>
     */
    static String unblockBadgeText(Music music) {
        if (music == null) return "";
        String source = music.getUnblockedSourceLabel();
        if (!source.isEmpty()) {
            // 换进来的是「另一首歌」，解灰换的只是「一条取流地址」，两个词不能混用。
            return (music.isCrossSourceSwitched() ? "已换源 · " : "解灰 · ") + source;
        }
        return music.isGreyDetected() ? "灰" : "";
    }

    static String formatDuration(long totalMillis) {
        long totalSeconds = totalMillis / 1000;

        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();

        if (hours > 0) {
            sb.append(String.format("%02d:", hours));
        }

        sb.append(String.format("%02d:", minutes));
        sb.append(String.format("%02d", seconds));

        return sb.toString();
    }

    private boolean loadCover() {
        TextureManager textureManager = TextureManager.getInstance();
        Location coverLoc = this.music.getSmallCoverLocation();
        if (textureManager.getTexture(coverLoc) != null)
            return true;

        String url = music.getCoverUrl(64);
        if (url == null || url.trim().isEmpty()) {
            // GD 封面仍在异步预取中：返回 false，下一帧重新尝试，避免对空串发起无效下载。
            return false;
        }
        Textures.downloadTextureAndLoadAsync(url, coverLoc);
        return true;
    }

}
