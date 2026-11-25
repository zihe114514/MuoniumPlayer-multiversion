package com.muoniumplayer.core.rendering;

import com.muoniumplayer.automix.ui.AutomixIslandRenderer;
import com.muoniumplayer.automix.ui.AutomixTransitionAnimation;

import com.muoniumplayer.compat.gl.GL11;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.automix.config.AutomixSettings;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.party.ui.PartyIcons;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.svg.AnimatedIcons;
import com.muoniumplayer.core.rendering.svg.SmilSvg;
import com.muoniumplayer.core.rendering.svg.SvgPlayback;
import com.muoniumplayer.core.rendering.texture.ITextureObject;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.screens.ncm.WindowAdaptiveScale;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.utils.Location;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaHost;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.types.RRect;

/**
 * 全局、与界面无关的下载活动岛（Dynamic Island）。
 *
 * <p>该类同时拥有小型的线程安全下载快照及其展示。Forge 将其渲染为覆盖在游戏
 * 画面和所有 GuiScreen 之上的全局覆盖层，因此它不依赖全屏播放器，也不继承
 * 播放器的布局/缩放动画。</p>
 */
public final class DownloadDynamicIsland implements SharedConstants, SharedRenderingConstants {

    private static final DownloadDynamicIsland INSTANCE = new DownloadDynamicIsland();

    /**
     * 关闭播放器后的单次轻量落地脉冲。
     *
     * <p>只保留一次正向压缩/回弹，不再做多周期摆动。这样从播放器表面交给胶囊时
     * 轮廓是连续的，且只需很短的反馈窗口，不会把收尾阶段拖成第二段重动画。</p>
     */
    private static double absorbWave(double phase) {
        double clamped = DynamicIslandMath.clamp01(phase);
        return Math.sin(Math.PI * clamped) * (1.0 - clamped);
    }

    private static final int PLAYER_LAYOUT_PREWARM_DELAY_FRAMES = 45;
    private static int hudFramesBeforePlayerPrewarm;
    private static boolean playerLayoutPrewarmed;

    /**
     * 上一帧 HUD 中岛屿在屏幕上的实际轮廓，已包含配置缩放、入场缩放、
     * 可见性偏移以及吸收回弹。
     */
    private static boolean playerSurfaceTransition;
    /** 播放器已抵达岛屿锚点；现在施加撞击形变。 */
    private static final double[] LIVE_ANCHOR_BOUNDS = {-1.0, -1.0, -1.0, -1.0, -1.0};
    /** 交接岛屿不得重播正常的从上方滑入的入场动画。 */
    private static boolean playerAbsorbImpact;
    private static long playerAbsorbStartedAt;
    /** 现代岛屿轮廓，灵感来自药丸、玻璃、紧凑状态和卡片表面。 */
    private static boolean suppressPlayerEntryAnimation;
    /** 关闭交接的轻量反馈；结束后立即交给常驻岛的正常可见性动画。 */
    private static final long PLAYER_ABSORB_FEEDBACK_MS = 260L;
    private static final double COMPACT_WIDTH = 40.0;
    private static final double COMPACT_HEIGHT = 16.0;
    private static final double TOP_MARGIN = 6.0;
    private static final double MIN_AUTO_TEXT_SCALE = .62;
    private static final double MAX_TEXT_SCALE = 1.18;
    private static final double CONTENT_CHANGE_SLIDE = 4.5;
    /** 文案扫光一轮的时长。慢到不抢注意力，快到能看出是在动。 */
    private static final long SHIMMER_PERIOD_MILLIS = 1600L;
    /** 亮带宽度占整行宽度的比例。 */
    private static final double SHIMMER_BAND = .34;
    /** 亮带之外的基础不透明度；亮带中心补到 1.0。 */
    private static final double SHIMMER_BASE_ALPHA = .80;
    /**
     * 液态玻璃的中性玻璃色，刻意<b>不</b>读 {@link NCMTheme#getAccentColor()}。
     *
     * <p>玻璃是一种材质而不是一处强调。用主题强调色去染整块表面的结果就是"液态玻璃被染色"：主题一换，
     * 玻璃体、折射亮带、底部反射和边缘光会一起偏成同一个色调，看起来像有色塑料而不是玻璃。这里固定
     * 一组偏冷的玻璃色；边缘光仍然按时间在色相环上转，所以"动态取色"的观感保留，只是不再被主题牵着走。</p>
     */
    private static final int GLASS_TINT = 0x8FB4EC;
    /** 玻璃色的高光变体，用于折射亮带与边缘光的基准色相。 */
    private static final int GLASS_TINT_BRIGHT = 0xCADEFB;

    /** 样式 ID 是持久化契约；移除样式后保留空号，不再用枚举序号存档。 */
    public enum DynamicIslandStyle {
        PILL(0, "经典胶囊"),
        COMPACT(2, "紧凑状态"),
        LIQUID_GLASS(6, "液态玻璃"),
        /** 回弹胶囊：弹簧尺寸、事件语义底色与多行通知堆叠。 */
        BOUNCE(7, "回弹胶囊"),
        /** 音乐胶囊：收起时显示歌词、音符和频谱；聊天展开卡由五款样式共用。 */
        MUSIC_CAPSULE(8, "音乐胶囊");

        private final int id;
        private final String displayName;

        DynamicIslandStyle(int id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public int getId() {
            return id;
        }

        public String getDisplayName() {
            return displayName;
        }

        /** 已移除的 1/3/4/5 和未知 ID 统一迁移到默认的紧凑状态。 */
        public static DynamicIslandStyle fromId(int id) {
            for (DynamicIslandStyle style : values()) {
                if (style.id == id) return style;
            }
            return COMPACT;
        }
    }

    public static DynamicIslandStyle getStyle() {
        DynamicIslandStyle style = DynamicIslandStyle.fromId(HudConfig.dynamicIslandStyle);
        // 手机档：存档里选的是「液态玻璃」款时，读取兜底退回经典胶囊——真玻璃在安卓上
        // 整体禁用（见 GlassMobileTier），这款的折射亮带画出来只剩假玻璃兜底。只在内存里
        // 生效、不回写存档：桌面上这份配置仍然有效。
        if (style == DynamicIslandStyle.LIQUID_GLASS && !isStyleSelectable(style)) {
            style = DynamicIslandStyle.PILL;
        }
        return style;
    }

    public static String getStyleName() {
        return getStyle().getDisplayName();
    }

    /**
     * 这款灵动岛样式在当前运行时能不能被选中。
     *
     * <p>「液态玻璃」款的材质与主题侧真玻璃同源（{@code GlassLook} 的 ISLAND 面），
     * 手机档把真玻璃整体禁用后它只剩假玻璃兜底——样式名还摆在下拉里、还能选中，
     * 会让人以为「液态玻璃在手机上没禁干净」。所以安卓上整款不可选：下拉不出现、
     * 轮换跳过（{@code cycleStyle}）、直设拒绝（设置模型 {@code setIslandStyle}），
     * 渲染侧另有 {@link #getStyle()} 的兜底。桌面恒可选。</p>
     */
    public static boolean isStyleSelectable(DynamicIslandStyle style) {
        return !com.muoniumplayer.core.rendering.GlassMobileTier.active()
                || style != DynamicIslandStyle.LIQUID_GLASS;
    }

    /** 安全网：没有专属通道的持久卡片仍会自行结束。 */
    public static void cycleStyle() {
        DynamicIslandStyle[] styles = DynamicIslandStyle.values();
        int index = DynamicIslandStyle.fromId(HudConfig.dynamicIslandStyle).ordinal();
        // 手机档跳过「液态玻璃」款：轮换不能停在一条已经禁用的死样式上（与主题轮换
        // NCMTheme.next() 同一套规矩）。
        for (int step = 0; step < styles.length; step++) {
            index = (index + 1) % styles.length;
            if (isStyleSelectable(styles[index])) {
                break;
            }
        }
        HudConfig.dynamicIslandStyle = styles[index].getId();
        HudConfig.save();
        publishNotice(IslandNoticeType.STYLE, "灵动岛样式", getStyleName());
    }

    private static volatile boolean downloading;
    private static volatile double downloadProgress;
    private static volatile String downloadSpeed = "0 b/s";
    private static volatile long downloadStartedAt;
    private static volatile long downloadCompletedAt;
    private static volatile boolean transcoding;
    private static volatile double transcodeProgress;
    /**
     * 当前持久卡片放弃的绝对时刻。每个状态通道只捕获一次，因此重复的
     * "仍在处理" 更新（再次点击刷新、重试解析）再也无法把截止时间往后推，
     * 从而让岛屿永久卡在加载状态。
     */
    private static volatile long transcodeProgressAdvancedAt;

    private static volatile IslandNoticeType noticeType = IslandNoticeType.NONE;
    private static volatile String noticeTitle = "";
    private static volatile String noticeValue = "";
    private static volatile long noticeShownAt;
    private static volatile long noticeRevision;
    private static volatile boolean noticePersistent;
    /** 仅对属于多消息队列中的普通通知为 true。 */
    private static final Object NOTICE_QUEUE_LOCK = new Object();
    private static final Deque<QueuedNotice> NOTICE_QUEUE = new ArrayDeque<QueuedNotice>();
    private enum NoticePriority {
        LOW(0), NORMAL(1), INSTANT(2), HIGH(3);
        final int rank;
        NoticePriority(int rank) { this.rank = rank; }
    }
    /** 动画展开后的边界；紧凑入场/出场仍由 {@link #expansion} 驱动。 */
    private static final long PLAYLIST_REFRESH_NOTICE_TIMEOUT_MILLIS = 30_000L;
    /** 在不打断岛屿表面的情况下淡入并轻微偏移替换内容。 */
    private static final long GD_SOURCE_NOTICE_TIMEOUT_MILLIS = 45_000L;
    /** 上一次通知快照，用于区分真正的替换与保持式刷新。 */
    private static final long PLAYLIST_TRACK_ADD_NOTICE_TIMEOUT_MILLIS = 20_000L;
    /** 短暂保留退出中的文案，使更新流入最新通知而非重新入场。 */
    private static final long TRANSCODE_STALL_TIMEOUT_MILLIS = 30_000L;
    /** 音量有独立的目标动画；它的轨道不会跳动或重启岛屿。 */
    private static final long DEFAULT_PERSISTENT_NOTICE_TIMEOUT_MILLIS = 30_000L;
    /** Folia 分析可持续 120-180 秒；推理提示覆盖完整模型运行周期。 */
    private static final long MODEL_INFERENCE_TIMEOUT_MILLIS = 240_000L;
    /** 绘制在普通游戏 HUD 之上。 */
    private static volatile long noticeDeadlineAt;
    private static volatile long noticeExpiresAt;
    private static final IslandVisibleClock NOTICE_VISIBLE_CLOCK = new IslandVisibleClock();
    private static volatile long noticeBudgetMillis;
    private static boolean noticeProtectedResume;
    private static String noticeChannelKey="";
    private static volatile String transcodeChannelKey="TRANSCODING";
    private final IslandVisibleClock completionVisibleClock = new IslandVisibleClock();
    private volatile long acknowledgedCompletionAt;
    /**
     * 在播放器从岛屿开始展开时调用。
     *
     * <p>在播放器表面形变期间，真实岛屿被刻意隐藏：同时绘制两者会产生两个
     * 顶部居中表面。其动画状态不会被重置，因此后续关闭时可把表面交回，
     * 而不必重播正常的岛屿入场。</p>
     */
    private static volatile boolean noticeUsesQueueInterval;

    private float visibility;
    private double expansion;
    private float contentAlpha;
    private double animatedProgress;
    private float successMorph;
    private double spinnerRotation;
    private double lastObservedProgress;
    private boolean previousDownloading;
    private boolean completing;
    private boolean initialized;
    private long shownAt;
    private long completeAt;
    private long observedNoticeRevision = -1L;
    /**
     * 将岛屿准备为关闭中播放器的落点目标。
     *
     * <p>在播放器抵达锚点之前把可见性设为 1。旧行为将其留为 0，导致关闭后
     * 的第一帧从屏幕上方插值 y，看起来像第二次无关的入场。这里岛屿已处于
     * 顶部锚点，只等待表面交接。</p>
     */
    private double animatedExpandedWidth = -1.0;
    private double animatedExpandedHeight = -1.0;
    private double expansionFrom;
    private double expansionClockTarget;
    private double expansionElapsedMillis;
    private double expansionDurationMillis;
    private long expansionLastNanos;
    private boolean expansionClockInitialized;
    private float visibilityStart;
    private double visibilityElapsedMillis;
    private double visibilityDurationMillis;
    private long visibilityLastNanos;
    private boolean visibilityClockInitialized;
    private boolean visibilityClockTarget;
    private double previousMotionX = Double.NaN;
    private double previousMotionY = Double.NaN;
    private double previousMotionWidth = Double.NaN;
    private double previousMotionHeight = Double.NaN;
    private long previousMotionNanos;
    private double motionBlurStrength;
    private double motionVelocityX;
    private double motionVelocityY;
    private float previousMotionAlpha;

    /*
     * 以下一组状态只服务「回弹胶囊」一款样式。刻意不复用上面那条低通通道，也不给它加任何
     * 全局开关：现有七款的动画数值全部走原路径，这一款在 drawIsland 里单独分叉。
     */
    /** 宽高弹簧。带速度，所以尺寸目标突变时会先冲过头再回弹，这是它最主要的观感来源。 */
    private final BounceIslandMath.Spring bounceWidth = new BounceIslandMath.Spring(0.0);
    private final BounceIslandMath.Spring bounceHeight = new BounceIslandMath.Spring(0.0);
    /** 进度条的临界阻尼平滑：下载速度本来就是跳变的，条不能跟着跳。 */
    private final BounceIslandMath.DampedValue bounceProgress = new BounceIslandMath.DampedValue();
    /** 整块表面的当前底色，按事件语义逐通道逼近目标色。 */
    private final BounceIslandMath.Tint bounceTint = new BounceIslandMath.Tint();
    /** 上一帧的纳秒时刻；0 表示还没跑过第一帧。弹簧按真实 dt 积分，帧率无关。 */
    private long bounceLastFrameNanos;
    /** 堆叠通知行的到达时刻，按「类型+文案」对齐，每行算自己的淡入淡出。 */
    private final List<BounceRow> bounceRows = new ArrayList<BounceRow>();
    /** 本帧的秒级帧间隔；弹簧与底色渐变共用，一帧只能推进一次时间戳。 */
    private double bounceFrameDelta = 1.0 / 60.0;

    /** 五款收起样式共用聊天栏里的音乐展开卡；不会替换聊天 Screen。 */
    private final MusicCardInteraction musicCardInteraction = new MusicCardInteraction();
    private boolean previousMusicCardOwnsSurface;
    private boolean previousCardTransitionActive;
    private Object musicCardChatScreen;
    private double cardOpen;
    private boolean cardOpenTarget;
    private float cardFittedScale = 1.0f;
    private double cardBaseWidth = -1.0;
    private double cardBaseHeight = -1.0;
    private double cardReturnFromWidth = -1.0;
    private double cardReturnFromHeight = -1.0;
    private double cardReturnStart = 1.0;
    private boolean previousCardOpening;
    private boolean musicCardReturning;
    private IslandAmbientStatus.Snapshot musicCardReturnAmbient = IslandAmbientStatus.EMPTY;
    private double liveAnchorLocalWidth = -1.0;
    private double liveAnchorLocalHeight = -1.0;
    private final MusicCardMotionBlur musicCardMotionBlur = new MusicCardMotionBlur();
    private long trackNoticeSession = Long.MIN_VALUE;
    private long trackNoticeRevision = Long.MIN_VALUE;
    private String trackNoticeKey;
    /** 本帧真正画出的卡片矩形（布局单位 x,y,w,h,r），输入直接使用同一轮廓。 */
    private static volatile double[] MUSIC_CARD_BOUNDS;
    private static volatile double[] MUSIC_CARD_ANCHOR_BOUNDS;
    private static CloudMusic.PlaybackSnapshot musicCardDrawnSnapshot;
    private static volatile boolean musicCardOwnsSurface;

    public static boolean isMusicCardOpen() {
        return musicCardOwnsSurface && INSTANCE.musicCardInteraction.canInteract() && MUSIC_CARD_BOUNDS != null;
    }

    public static double[] musicCardBounds() {
        double[] bounds = MUSIC_CARD_BOUNDS;
        return bounds == null ? null : bounds.clone();
    }

    /** Esc、点外部或外部换屏后，本次聊天会话不再自动重开。 */
    public static void collapseMusicCard() {
        INSTANCE.musicCardInteraction.collapse();
        INSTANCE.cardOpenTarget = false;
        MusicCapsuleCard.INSTANCE.clearPointer();
    }

    /** Detect an audible session change before either HUD or Screen rendering gate runs. */
    private static void startTrackNoticeIfNeeded() {
        CloudMusic.PlaybackSnapshot playback = MusicCapsuleCard.currentSnapshot();
        IslandVisibilityGate.Scene scene = IslandVisibilityGate.capture();
        boolean chat = IslandVisibilityGate.isChatOpen();
        boolean allowed = HudConfig.dynamicIslandEnabled && HudConfig.islandHoverExpand
                && !playerSurfaceTransition && IslandVisibilityGate.allowTrackNoticeOverlay(scene, chat,
                playerSurfaceTransition);
        if (MusicCapsuleCard.trackNoticeVisible(playback)
                && INSTANCE.trackNoticeRevision != MusicCapsuleCard.noticeRevision()) {
            INSTANCE.trackNoticeRevision = MusicCapsuleCard.noticeRevision();
            INSTANCE.trackNoticeSession = playback.sessionId;
            INSTANCE.trackNoticeKey = playback.music.getStableKey();
            if (!allowed) INSTANCE.musicCardInteraction.dismissEvent();
            else {
                if (!INSTANCE.musicCardInteraction.animationActive()) {
                    boolean capsule = getStyle() == DynamicIslandStyle.MUSIC_CAPSULE;
                    INSTANCE.liveAnchorLocalWidth = capsule ? MusicCapsuleCard.rowTargetWidth() : -1.0;
                    INSTANCE.liveAnchorLocalHeight = capsule ? MusicCapsuleCard.ROW_HEIGHT : -1.0;
                    INSTANCE.cardBaseWidth = -1.0;
                    INSTANCE.cardBaseHeight = -1.0;
                }
                INSTANCE.musicCardInteraction.onTrackChanged(chat);
                if (!chat && !INSTANCE.musicCardInteraction.eventLocked()) {
                    INSTANCE.musicCardInteraction.startTrackNotice(MusicCardInteraction.TRACK_NOTICE_HOLD_MILLIS);
                }
            }
        }
        if (INSTANCE.musicCardInteraction.eventLocked()
                && (!allowed || MusicCapsuleCard.pendingEndedWithoutPlayback())) {
            INSTANCE.musicCardInteraction.dismissEvent();
        }
        if (playback.music != null) {
            INSTANCE.trackNoticeSession = playback.sessionId;
            INSTANCE.trackNoticeKey = playback.music.getStableKey();
        }
        // 准备态和可听升级属于同一展示事件，不二次起弹或清空当前姿态。
    }

    /** 不依赖绘制的失效维护：跳过覆盖层、换屏、失焦和资源重载也必须取消旧交互。 */
    public static void maintainMusicCardContext() {
        MusicCapsuleCard.observePlayback();
        startTrackNoticeIfNeeded();
        Object screen = IslandVisibilityGate.chatScreenIdentity();
        boolean replaced = screen != INSTANCE.musicCardChatScreen;
        INSTANCE.musicCardChatScreen = screen;
        IslandVisibilityGate.Scene scene = IslandVisibilityGate.capture();
        boolean valid = HudConfig.dynamicIslandEnabled && HudConfig.islandHoverExpand
                && !playerSurfaceTransition && IslandVisibilityGate.allowTrackNoticeOverlay(scene,
                IslandVisibilityGate.isChatOpen(), playerSurfaceTransition)
                && MusicCapsuleCard.frameHasMusic();
        if (!valid) {
            INSTANCE.musicCardInteraction.reset();
            INSTANCE.cardOpen = 0.0;
            INSTANCE.cardOpenTarget = false;
            musicCardOwnsSurface = false;
            MUSIC_CARD_BOUNDS = null;
            musicCardDrawnSnapshot = null;
            MUSIC_CARD_ANCHOR_BOUNDS = null;
            INSTANCE.cardBaseWidth = -1.0;
            INSTANCE.cardBaseHeight = -1.0;
            INSTANCE.cardReturnFromWidth = -1.0;
            INSTANCE.cardReturnFromHeight = -1.0;
            INSTANCE.previousCardTransitionActive = false;
            INSTANCE.musicCardReturning = false;
            INSTANCE.musicCardReturnAmbient = IslandAmbientStatus.EMPTY;
            INSTANCE.musicCardMotionBlur.reset();
            INSTANCE.previousMusicCardOwnsSurface = false;
            MusicCapsuleCard.INSTANCE.clearPointer();
            MusicCapsuleCard.INSTANCE.cancelConfirmation();
        } else if (replaced && !INSTANCE.musicCardInteraction.eventLocked()) {
            if (INSTANCE.musicCardInteraction.animationActive() && (screen != null || !scene.anyScreenOpen)) {
                // 同一张正在收放的卡片重进聊天时保留姿态；关闭聊天只改目标，不清掉尾帧。
                if (screen == null) INSTANCE.musicCardInteraction.collapse();
            } else {
                INSTANCE.musicCardInteraction.reset();
                INSTANCE.cardOpen = 0.0;
                INSTANCE.cardOpenTarget = false;
                musicCardOwnsSurface = false;
                MUSIC_CARD_BOUNDS = null;
                MUSIC_CARD_ANCHOR_BOUNDS = null;
            }
            MusicCapsuleCard.INSTANCE.clearPointer();
            MusicCapsuleCard.INSTANCE.cancelConfirmation();
        }
        if (musicCardSurfaceBlockedNow()) {
            INSTANCE.cardBaseWidth = -1.0;
            INSTANCE.cardBaseHeight = -1.0;
            INSTANCE.musicCardMotionBlur.reset();
            INSTANCE.musicCardInteraction.pauseTrackNotice();
            MusicCapsuleCard.INSTANCE.clearPointer();
            MusicCapsuleCard.INSTANCE.cancelConfirmation();
        } else if (INSTANCE.musicCardInteraction.eventLocked()) {
            INSTANCE.musicCardInteraction.resumeTrackNotice();
        }
    }

    /** 卡片请求、自动切歌与收起尾巴统一拥有最高展示位。 */
    private static boolean musicCardClaimsSurface() {
        IslandVisibilityGate.Scene scene = IslandVisibilityGate.capture();
        boolean chat = IslandVisibilityGate.isChatOpen();
        boolean enabled = HudConfig.dynamicIslandEnabled && HudConfig.islandHoverExpand
                && !playerSurfaceTransition && !playerAbsorbImpact
                && IslandVisibilityGate.allowTrackNoticeOverlay(scene, chat, playerSurfaceTransition);
        return INSTANCE.musicCardInteraction.claimsSurface(enabled, chat, MusicCapsuleCard.frameHasMusic(),
                HudConfig.islandExpandTrigger);
    }

    private static boolean musicCardIsPresented() {
        maintainMusicCardContext();
        return musicCardClaimsSurface();
    }

    /** 聊天界面只请求岛的覆盖层；卡片收回尾帧也要在聊天关闭后继续绘制。 */
    public static boolean needsChatOverlay() {
        maintainMusicCardContext();
        return HudConfig.dynamicIslandEnabled && HudConfig.islandHoverExpand
                && (IslandVisibilityGate.isChatOpen() && MusicCapsuleCard.frameHasMusic()
                || INSTANCE.musicCardInteraction.animationActive() && !playerSurfaceTransition
                && IslandVisibilityGate.allowSurface(IslandVisibilityGate.capture()));
    }

    /** 切歌提示覆盖 HUD 与聊天栏，仍遵守非游戏屏幕及 Tab 门禁。 */
    public static boolean needsTrackNoticeOverlay() {
        maintainMusicCardContext();
        return HudConfig.dynamicIslandEnabled && HudConfig.islandHoverExpand
                && INSTANCE.musicCardInteraction.trackNoticeActive()
                && IslandVisibilityGate.allowTrackNoticeOverlay(IslandVisibilityGate.capture(),
                IslandVisibilityGate.isChatOpen(), playerSurfaceTransition);
    }

    /** 只在卡片展开提示可见时要求采集真背景。 */
    public static boolean needsMusicCardBackdrop() {
        return HudConfig.dynamicIslandEnabled && HudConfig.islandHoverExpand
                && INSTANCE.cardOpen > .002 && !playerSurfaceTransition
                && IslandVisibilityGate.allowSurface(IslandVisibilityGate.capture());
    }

    /** 将播放器表面移交到岛屿并启动撞击响应。 */
    private float contentTransition = 1f;
    /** 当播放器在交接完成前被另一界面替换时取消交接。 */
    private IslandNoticeType observedNoticeType = IslandNoticeType.NONE;
    private String observedNoticeTitle = "";
    private String observedNoticeValue = "";
    // 在吸收回弹期间重新打开时，会从当前已形变的药丸起步，而不是
    private String outgoingNoticeTitle = "";
    private String outgoingNoticeValue = "";
    private float noticeCopyTransition = 1f;
    // 突然弹回静止胶囊形状。这与 drawIsland 使用的是同一个波。
    private double animatedNoticeProgress;

    /**
     * 常驻内容与通知内容之间的交叉淡入进度：1 为常驻状态栏，0 为通知/下载卡片。
     * 常驻不是一种通知，因此它只参与这一层内容混合，完全不进 {@link #NOTICE_QUEUE}。
     */
    private float ambientBlend;
    private boolean previousAlwaysOn;
    private IslandAmbientStatus.Snapshot ambientSnapshot = IslandAmbientStatus.EMPTY;
    private long ambientSampledAt;

    private DownloadDynamicIsland() {
    }

    // 布局轴几何 + 自带的 correction 矩阵层：一单位 = 恒定物理像素，与 GUI Scale 档位无关。
    // 括号由本方法自己进出（owned 守卫防嵌套），调用方零改动；HUD 编辑器的预览路径
    // （renderEditorPreview）不走这里 —— 编辑器已提供同一层外层矩阵，再包就是双乘。
    /** HUD 与 Screen 二选一绘制；切歌时不能在聊天栏下方再合成旧的一帧卡片。 */
    public static void renderHudOverlay() {
        maintainMusicCardContext();
        if (!IslandVisibilityGate.capture().anyScreenOpen) {
            render();
            if (!musicCardClaimsSurface()) AutomixTransitionAnimation.render();
        }
    }

    public static void render() {
        // 完整字体就绪后在渲染线程热接入；转场期间延后，播放器稳定帧也维护同一钩子。
        if (!com.muoniumplayer.core.screens.ncm.PlayerIslandTransition.needsFrameBudget()) {
            com.muoniumplayer.core.management.FontAssetDownload.pollFromRenderThread();
        }
        prewarmPlayerLayoutOnce();
        boolean ownedLayout = !RenderSystem.FIXED_SCALE;
        if (ownedLayout) WindowAdaptiveScale.beginForced();
        try {
            INSTANCE.renderInternal();
        } finally {
            if (ownedLayout) WindowAdaptiveScale.end();
        }
    }

    private static void prewarmPlayerLayoutOnce() {
        if (playerLayoutPrewarmed) return;
        if (++hudFramesBeforePlayerPrewarm < PLAYER_LAYOUT_PREWARM_DELAY_FRAMES) return;
        // 播放器转场/收尾正在播时整树预热（控件树重建 + 主页构造）会把几十毫秒的重活砸进
        // 形变帧 —— 典型场景：进世界后立刻开播放器，第 45 帧正好落在展开动画中段。
        // 顺延到转场不再忙的 HUD 空闲帧再执行（isBusy 含完全展开态：播放器已经真开过的
        // 话，预热本就失去了服务对象，本次会话不再执行是无害的正确行为）；闸门未置位，
        // 下一帧到期直接重试。
        if (com.muoniumplayer.core.screens.ncm.PlayerIslandTransition.isBusy()) {
            return;
        }
        try {
            NCMScreen.prewarmLayout();
            playerLayoutPrewarmed = NCMScreen.isLayoutPrewarmComplete();
        } catch (Throwable t) {
            // 预热永远不允许影响 HUD；真正打开播放器时 initGui 仍会走完整布局。
            System.err.println("[MuoniumPlayer] 播放器布局预热失败: " + t);
        }
    }

    /** 播放器屏幕不显示常驻岛，只为通知、下载、退场尾帧与混音内容打开顶层通道。 */
    public static boolean needsScreenOverlay() {
        advanceQueuedNotice(System.currentTimeMillis());
        if (playerSurfaceTransition) {
            pauseNoticeClock();
            INSTANCE.completionVisibleClock.pause();
            return needsTrackNoticeOverlay();
        }
        return INSTANCE.visibility > .001f || playerAbsorbImpact || isDownloadActive()
                || noticeType != IslandNoticeType.NONE || AutomixTransitionAnimation.islandFrame() != null
                || needsTrackNoticeOverlay();
    }

    /** 紧凑岛屿的基础颜色，在 t=0 时被播放器形变使用。 */
    public static void beginPlayerSurfaceTransition() {
        playerSurfaceTransition = true;
        playerAbsorbImpact = false;
        playerAbsorbStartedAt = 0L;
        suppressPlayerEntryAnimation = true;
    }

    /**
     * 播放器展开动画结束：恢复全局岛渲染，让播放器界面里的即时通知继续可见。
     *
     * <p>常驻状态仍由 {@link IslandVisibilityGate#allowAmbient} 在播放器界面隐藏；这里只解除
     * 展开形变对所有内容的拦截，并清掉展开前遗留的常驻可见状态，避免展开结束时先闪一下旧
     * 常驻岛再收起。</p>
     */
    public static void finishPlayerSurfaceTransition() {
        if (!playerSurfaceTransition) return;
        playerSurfaceTransition = false;
        playerAbsorbImpact = false;
        playerAbsorbStartedAt = 0L;
        suppressPlayerEntryAnimation = false;
        INSTANCE.visibility = 0f;
        INSTANCE.expansion = 0.0;
        INSTANCE.contentAlpha = 0f;
        INSTANCE.contentTransition = 0f;
        INSTANCE.ambientBlend = 0f;
        INSTANCE.previousAlwaysOn = false;
        INSTANCE.ambientSnapshot = IslandAmbientStatus.EMPTY;
        INSTANCE.ambientSampledAt = 0L;
        INSTANCE.animatedExpandedWidth = -1.0;
        INSTANCE.animatedExpandedHeight = -1.0;
        INSTANCE.resetMotionBlur();
        INSTANCE.resetExpansionClock();
        INSTANCE.cardOpen = 0.0;
        INSTANCE.cardOpenTarget = false;
        INSTANCE.musicCardInteraction.reset();
        MUSIC_CARD_BOUNDS = null;
        musicCardOwnsSurface = false;
    }

    /** 即使没有正在进行的下载，HUD 编辑器也使用此实时尺寸预览。 */
    public static void preparePlayerAbsorb() {
        playerSurfaceTransition = true;
        playerAbsorbImpact = false;
        playerAbsorbStartedAt = 0L;
        suppressPlayerEntryAnimation = true;
        INSTANCE.visibility = HudConfig.dynamicIslandEnabled ? 1f : 0f;
        INSTANCE.expansion = 0.0;
        INSTANCE.contentAlpha = 0f;
        INSTANCE.contentTransition = 0f;
        INSTANCE.ambientBlend = HudConfig.dynamicIslandAlwaysOn ? 1f : 0f;
        INSTANCE.animatedExpandedWidth = -1.0;
        INSTANCE.animatedExpandedHeight = -1.0;
        INSTANCE.shownAt = 0L;
        INSTANCE.cardOpen = 0.0;
        INSTANCE.cardOpenTarget = false;
        INSTANCE.musicCardInteraction.reset();
        MUSIC_CARD_BOUNDS = null;
        musicCardOwnsSurface = false;
    }

    /** 在使用紧凑图标后，于全局岛屿中显示当前主题。 */
    public static void beginPlayerAbsorbImpact() {
        playerSurfaceTransition = false;
        playerAbsorbImpact = true;
        playerAbsorbStartedAt = System.currentTimeMillis();
        suppressPlayerEntryAnimation = true;
        INSTANCE.visibility = HudConfig.dynamicIslandEnabled ? 1f : 0f;
    }

    /** 在全局岛屿中显示实际生效的全屏播放器缩放。 */
    public static void cancelPlayerSurfaceTransition() {
        playerSurfaceTransition = false;
        playerAbsorbImpact = false;
        playerAbsorbStartedAt = 0L;
        suppressPlayerEntryAnimation = false;
    }

    /**
     * 返回当前样式的紧凑锚点：x、y、width、height、radius。
     *
     * <p>播放器展开动画只需要“岛在哪里、有多大”，不需要知道岛里现在显示的是下载、通知还是常驻
     * 状态。这里按当前样式与用户缩放重新推一遍紧凑胶囊，和真实渲染使用同一组常量。</p>
     */
    public static double[] getAnchorBounds() {
        DynamicIslandStyle style = getStyle();
        boolean bounceStyle = style == DynamicIslandStyle.BOUNCE;
        double localWidth = bounceStyle
                ? BounceIslandMath.SIDE_PADDING * 2.0 + BounceIslandMath.LOGO_AREA_WIDTH
                : (style == DynamicIslandStyle.COMPACT ? 46.0
                : (style == DynamicIslandStyle.MUSIC_CAPSULE
                ? (MusicCapsuleCard.rowContentActive() ? MusicCapsuleCard.rowTargetWidth() : COMPACT_WIDTH)
                : COMPACT_WIDTH));
        double localHeight = bounceStyle ? BounceIslandMath.ROW_HEIGHT
                : (style == DynamicIslandStyle.COMPACT ? 14.0
                : (style == DynamicIslandStyle.MUSIC_CAPSULE
                ? (MusicCapsuleCard.rowContentActive() ? MusicCapsuleCard.ROW_HEIGHT : COMPACT_HEIGHT)
                : COMPACT_HEIGHT));

        /** 显示持久化的音乐音量及配套的进度指示。 */
        /** 显示稳定的转码卡片；进度更新只移动进度条，不会重启入场动画。 */
        if (playerAbsorbImpact && playerAbsorbStartedAt > 0L) {
            double phase = DynamicIslandMath.clamp01((System.currentTimeMillis() - playerAbsorbStartedAt)
                    / (double) PLAYER_ABSORB_FEEDBACK_MS);
            double wave = absorbWave(phase);
            localWidth *= 1.0 + .28 * wave;
            localHeight *= 1.0 - .36 * wave;
        }

        double configuredScale = DynamicIslandMath.clamp(HudConfig.dynamicIslandScale, .60, 1.35);
        double width = localWidth * configuredScale;
        double height = localHeight * configuredScale;
        double localRadius = compactRadius(style, localHeight);
        /** 更新转码进度，但不发布新的通知版本。 */
        // 停滞的解码器会停止移动进度条；下方的看门狗正是对此作出响应。
        // 锚点恒按布局轴上报（与岛渲染同一轴），不随 FIXED_SCALE 状态翻转；
        // 消费方（播放器转场、岛内搜索）用 WindowAdaptiveScale.islandToCurrent* 折算。
        double centerX = RenderSystem.getLayoutWidth() * .5;
        double x = centerX - width * .5;
        // 与 drawIsland 的 topMargin 同一公式：转场落点、岛内搜索起点必须落在岛真实的位置上。
        double y = (bounceStyle ? BounceIslandMath.TOP : TOP_MARGIN)
                + DynamicIslandMath.clamp(HudConfig.dynamicIslandY, -20.0, 400.0);
        return new double[]{x, y, width, height, localRadius * configuredScale};
    }

    /** 在配置的完成保持时长内显示已校验的转换结果。 */
    public static double[] getLiveAnchorBounds() {
        if (INSTANCE.visibility > .72
                && LIVE_ANCHOR_BOUNDS[2] > 1.0 && LIVE_ANCHOR_BOUNDS[3] > 1.0) {
            return LIVE_ANCHOR_BOUNDS.clone();
        }
        return getAnchorBounds();
    }

    // 实时卡片是持久的，因此普通通知会排在它后面，无法替换
    public static int getAnchorSurfaceColor() {
        DynamicIslandStyle style = getStyle();
        if (style == DynamicIslandStyle.BOUNCE) {
            // 播放器面板要从岛"当前那一层底色"接上去，而这一款的底色是跟着事件语义走的。
            BounceIslandMath.Tint tint = INSTANCE.bounceTint;
            return BounceIslandMath.argb(255, tint.redChannel(), tint.greenChannel(), tint.blueChannel());
        }
        if (style == DynamicIslandStyle.LIQUID_GLASS) return INSTANCE.hexColor(.050f, .064f, .098f, 1f);
        return INSTANCE.hexColor(.012f, .014f, .020f, 1f);
    }

    private static double compactRadius(DynamicIslandStyle style, double height) {
        if (style == DynamicIslandStyle.BOUNCE) return BounceIslandMath.pillRadius(height);
        if (style == DynamicIslandStyle.LIQUID_GLASS) return Math.min(16.0, height * .40);
        return height * .5;
    }

    // 它。直接完成该状态通道；否则岛屿会一直停留在"转码中"，
    public static void renderEditorPreview() {
        INSTANCE.renderPreviewInternal();
    }

    // 直到停滞看门狗把成功的解码误报为无响应。
    public static void showTheme(String themeName) {
        publishNotice(IslandNoticeType.THEME, "当前主题", safeNoticeValue(themeName, "默认主题"));
    }

    /** 在显示正常的播放失败通知之前移除被中断的转码卡片。 */
    public static void showPlayerScale(int scalePercent) {
        int safePercent = Math.max(50, Math.min(150, scalePercent));
        publishNotice(IslandNoticeType.SCALE, "播放器大小", safePercent + "%");
    }

    /** Folia 模型安装、扫描或删除操作的结果。 */

    /** 在 Folia 模型分析会话运行期间保持一张持久状态卡片。 */
    public static void showVolume(int percent) {
        int safePercent = Math.max(0, Math.min(100, percent));
        publishNotice(IslandNoticeType.VOLUME, "音乐音量", safePercent + "%");
    }

    /** 「播放时压掉原版音乐」开关的反馈。走 VOLUME 通道：这是音量域的偏好，图标同族。 */
    public static void showVanillaMusicDucker(boolean enabled) {
        publishNotice(IslandNoticeType.VOLUME, enabled ? "已压掉游戏音乐" : "游戏音乐已恢复",
                enabled ? "播放时原版音乐自动让路" : "原版音乐不再被打断");
    }
    /** 仅在配置的模型返回可用证据后才完成实时卡片。 */
    public static void beginTranscode(String trackName, String sourceFormat, String targetFormat) {
        synchronized (NOTICE_QUEUE_LOCK) {
            transcoding = true;
            transcodeProgress = 0.0;
            transcodeProgressAdvancedAt = System.currentTimeMillis();
            transcodeChannelKey=operationKey(IslandNoticeType.TRANSCODING,trackName);
            publishOperation(IslandNoticeType.TRANSCODING,"音频转码",
                    formatTranscodeValue(trackName,sourceFormat,targetFormat),trackName);
        }
    }

    /** 模型尝试失败并回退到确定性分析后的终止状态。 */
    public static void updateTranscodeProgress(double progress) {
        synchronized (NOTICE_QUEUE_LOCK) { updateTranscodeProgressLocked(progress); }
    }

    /** Late callbacks from an older track cannot drive the current track's loader. */
    public static void updateTranscodeProgress(String trackName, double progress) {
        synchronized (NOTICE_QUEUE_LOCK) {
            if (operationKey(IslandNoticeType.TRANSCODING,trackName).equals(transcodeChannelKey)) {
                updateTranscodeProgressLocked(progress);
            }
        }
    }

    private static void updateTranscodeProgressLocked(double progress) {
        if (!transcoding) return;
        double clamped = DynamicIslandMath.clamp01(progress);
        if (clamped > transcodeProgress) transcodeProgressAdvancedAt = System.currentTimeMillis();
        transcodeProgress = clamped;
    }

    /** 通报一次已校验的新歌单收藏。 */
    public static void finishTranscode(String trackName, String sourceFormat, String targetFormat,
                                       long elapsedMillis) {
        synchronized (NOTICE_QUEUE_LOCK) {
            if (operationKey(IslandNoticeType.TRANSCODING,trackName).equals(transcodeChannelKey)) {
                transcoding = false;
                transcodeProgress = 1.0;
                transcodeProgressAdvancedAt = 0L;
            }
        }
        /** 通报单独校验过的空操作状态，而不声称新增成功。 */
        /** 通报请求未被目标歌单的 trackIds 校验通过。 */
        /** 显示刚启动曲目所解析出的流层级和容器格式。 */
        completePersistentNotice(IslandNoticeType.TRANSCODING, IslandNoticeType.TRANSCODE_SUCCESS,
                "转码完成", formatTranscodeValue(trackName, sourceFormat, targetFormat) + " · "
                        + Math.max(0L, elapsedMillis) + " ms",operationKey(IslandNoticeType.TRANSCODING,trackName));
    }

    /**
     * 报告从下载字节中检测到的通用 MP4 载荷。播放管线保留头部检查，
     * 但有意不对该容器转码。
     */
    public static void cancelTranscode() {
        synchronized (NOTICE_QUEUE_LOCK) { cancelTranscodeLocked(transcodeChannelKey); }
    }

    public static void cancelTranscode(String trackName) {
        synchronized (NOTICE_QUEUE_LOCK) {
            cancelTranscodeLocked(operationKey(IslandNoticeType.TRANSCODING,trackName));
        }
    }

    private static void cancelTranscodeLocked(String channelKey) {
        if (channelKey.equals(transcodeChannelKey)) {
            transcoding = false;
            transcodeProgress = 0.0;
            transcodeProgressAdvancedAt = 0L;
        }
        dropQueuedOperationLocked(channelKey);
        if (noticeType == IslandNoticeType.TRANSCODING && noticeChannelKey.equals(channelKey)) {
            long now=System.currentTimeMillis();
            QueuedNotice next=pollNextNoticeLocked(now);
            if (next != null) restoreQueuedNoticeLocked(next,now);
            else publishActiveNoticeLocked(IslandNoticeType.NONE,"","",false,false,now);
        }
    }

    /** 转码卡片给人看的是「歌名 输入容器→输出容器」，例如「晴天 mp4→wav」。 */
    private static String formatTranscodeValue(String trackName, String sourceFormat, String targetFormat) {
        return safeNoticeValue(trackName, "当前歌曲") + " "
                + safeNoticeValue(sourceFormat, "?") + "→" + safeNoticeValue(targetFormat, "wav");
    }
    public static void showPlaylistRefreshInProgress() {
        publishNotice(IslandNoticeType.REFRESHING, "歌单同步", "正在刷新…", true);
    }

    public static void showPlaylistRefreshSuccess(int playlistCount, long elapsedMillis) {
        int safeCount = Math.max(0, playlistCount);
        long safeElapsed = Math.max(0L, elapsedMillis);
        completePersistentNotice(IslandNoticeType.REFRESHING, IslandNoticeType.REFRESH_SUCCESS,
                "歌单同步", "已同步 " + safeCount + " 个歌单 · " + safeElapsed + " ms");
    }

    public static void showPlaylistRefreshFailure(String message) {
        completePersistentNotice(IslandNoticeType.REFRESHING, IslandNoticeType.REFRESH_ERROR,
                "歌单同步失败", safeNoticeValue(message, "网络请求失败"));
    }

    /**
     * 完整字体包的下载进度卡。持久卡、同频道替换式更新（与歌单同步的
     * REFRESHING 同一形态）：下载线程节流推送，界面想看随时能看到走到哪了。
     */
    public static void showFontAssetProgress(int percent) {
        int safePercent = Math.max(0, Math.min(100, percent));
        publishNotice(IslandNoticeType.FONT_ASSET, "字体资源",
                safePercent >= 100 ? "下载完成，正在启用完整字体…" : "正在下载完整字体 " + safePercent + "%",
                true);
    }

    /** 完整字体下载/启用失败：一句人话 + 冷却后自动重试的指引。 */
    public static void showFontAssetFailure(String reason) {
        completePersistentNotice(IslandNoticeType.FONT_ASSET, IslandNoticeType.FONT_ASSET_ERROR,
                "字体资源下载失败", safeNoticeValue(reason, "网络不可用") + " · 稍后自动重试");
    }

    /** 完整字体已热启用：本次会话起生僻字、繁体与韩文即刻可用。 */
    public static void showFontAssetReady() {
        completePersistentNotice(IslandNoticeType.FONT_ASSET, IslandNoticeType.FONT_ASSET_SUCCESS,
                "字体资源", "完整字体已启用 · 生僻字/韩文可用");
    }

    /**
     * LX 脚本转译器（Closure Compiler）的下载进度卡。进入自定义源界面才触发下载，
     * 与字体进度卡同一形态：持久卡、同频道替换式更新、下载线程节流推送。
     */
    public static void showLxTranspilerProgress(int percent) {
        int safePercent = Math.max(0, Math.min(100, percent));
        publishNotice(IslandNoticeType.LX_TRANSPILER, "脚本转译器",
                safePercent >= 100 ? "下载完成，下次转译脚本时启用…" : "正在下载脚本转译器 " + safePercent + "%",
                true);
    }

    /** 转译器下载失败：一句人话 + 冷却后自动重试的指引。 */
    public static void showLxTranspilerFailure(String reason) {
        completePersistentNotice(IslandNoticeType.LX_TRANSPILER, IslandNoticeType.LX_TRANSPILER_ERROR,
                "脚本转译器下载失败", safeNoticeValue(reason, "网络不可用") + " · 稍后自动重试");
    }

    /**
     * 渲染层热启用成功：伴生包装好后重评窗口捡起了原生库，文字渲染器已重建。
     * 走 REFRESH_SUCCESS 的对勾外观即可——这是一次性结果提示，不值得新开一种通知。
     */
    public static void showRenderLayerHotReloaded() {
        publishNotice(IslandNoticeType.REFRESH_SUCCESS, "渲染层", "热启用成功 · 无需重启");
    }

    /** 转译器已就位：等它的脚本会被自动加载，无需用户手动重新加载。 */
    public static void showLxTranspilerReady() {
        completePersistentNotice(IslandNoticeType.LX_TRANSPILER, IslandNoticeType.LX_TRANSPILER_SUCCESS,
                "脚本转译器", "已就绪 · 自定义源已自动加载");
    }

    /** 汽水签名后端伴生包的下载进度卡（选择汽水源且本机缺失时触发，持久卡）。 */
    public static void showQishuiBackendProgress(int percent) {
        int safePercent = Math.max(0, Math.min(100, percent));
        publishNotice(IslandNoticeType.QISHUI_BACKEND, "汽水音乐源",
                safePercent >= 100 ? "下载已接收，正在校验…" : "正在下载汽水音乐后端 " + safePercent + "%",
                true);
    }

    public static void showQishuiBackendInstalling() {
        publishNotice(IslandNoticeType.QISHUI_BACKEND, "汽水音乐源", "下载校验通过，正在解压安装…", true);
    }

    public static void showQishuiBackendStarting() {
        publishNotice(IslandNoticeType.QISHUI_BACKEND, "汽水音乐源", "后端已安装，正在启动并检查状态…", true);
    }

    public static void showQishuiBackendFailure(String reason) {
        completePersistentNotice(IslandNoticeType.QISHUI_BACKEND, IslandNoticeType.QISHUI_BACKEND_ERROR,
                "汽水音乐后端未就绪", safeNoticeValue(reason, "网络不可用") + " · 稍后自动重试");
    }

    public static void showQishuiBackendReady() {
        completePersistentNotice(IslandNoticeType.QISHUI_BACKEND, IslandNoticeType.QISHUI_BACKEND_SUCCESS,
                "汽水音乐源", "签名后端已就绪 · 完整版可用");
    }

    // ─────────────────────── Apple gamdl 完整版兑换 ───────────────────────

    /**
     * Apple 曲目进入 gamdl 兑换（持久卡）。
     *
     * <p>与 GD 的「正在连接」同形态：gamdl 要跑授权 + 下载 + 解封装，单曲十几秒起步，
     * 没有这张卡用户看到的就是「点了没反应」（实机反馈：首播完全没有准备中动画）。
     * 持久卡意味着它一直挂着直到 {@link #showAppleGamdlResolved} /
     * {@link #showAppleGamdlFailure} 收尾 —— 兑换失败后会自动落换源替身，那张
     * 「正在换源」卡会自然接力，本卡不会悬空。</p>
     */
    public static void showAppleGamdlPreparing(String trackName) {
        publishOperation(IslandNoticeType.APPLE_GAMDL, "Apple Music · 准备完整版",
                safeNoticeValue(trackName, "当前歌曲") + " · gamdl 下载解密中", "current");
    }

    /** 兑换成功：文件已收编进缓存，即将起播。 */
    public static void showAppleGamdlResolved(String trackName) {
        completePersistentNotice(IslandNoticeType.APPLE_GAMDL, IslandNoticeType.APPLE_GAMDL_SUCCESS,
                "Apple 完整版已就绪",
                safeNoticeValue(trackName, "当前歌曲") + " · Apple Music 256k AAC",
                IslandNoticeType.APPLE_GAMDL.name());
    }

    /** 兑换失败：归因随 {@code reason} 透出（超时 / cookies 过期 / 未订阅形态各异）。 */
    public static void showAppleGamdlFailure(String trackName, String reason) {
        completePersistentNotice(IslandNoticeType.APPLE_GAMDL, IslandNoticeType.APPLE_GAMDL_ERROR,
                "Apple 完整版获取失败",
                safeNoticeValue(trackName, "当前歌曲") + " · "
                        + safeNoticeValue(reason, "gamdl 未交付") + " · 将尝试换源",
                IslandNoticeType.APPLE_GAMDL.name());
    }

    /**
     * 听歌打卡组件（zstd-jni）的下载进度卡。登录网易云账号时触发下载，
     * 与字体/转译器进度卡同一形态：持久卡、同频道替换式更新、下载线程节流推送。
     */
    public static void showZstdNativeProgress(int percent) {
        int safePercent = Math.max(0, Math.min(100, percent));
        publishNotice(IslandNoticeType.ZSTD_NATIVE, "听歌打卡",
                safePercent >= 100 ? "下载完成，下一轮播放自动恢复上报…" : "正在下载听歌打卡组件 " + safePercent + "%",
                true);
    }

    /** 打卡组件下载失败：一句人话 + 冷却后自动重试的指引。 */
    public static void showZstdNativeFailure(String reason) {
        completePersistentNotice(IslandNoticeType.ZSTD_NATIVE, IslandNoticeType.ZSTD_NATIVE_ERROR,
                "听歌打卡组件下载失败", safeNoticeValue(reason, "网络不可用") + " · 稍后自动重试");
    }

    /** 打卡组件已就位：下一轮播放起，网易云的播放历史与时长自动恢复上报。 */
    public static void showZstdNativeReady() {
        completePersistentNotice(IslandNoticeType.ZSTD_NATIVE, IslandNoticeType.ZSTD_NATIVE_SUCCESS,
                "听歌打卡", "组件已就绪 · 上报自动恢复");
    }

    /**
     * 音频缓存清理结果。刻意沿用歌单同步的成功/失败外观：这是一次性结果提示，
     * 不值得为它新增一种通知类型与图标，也因此自动遵循既有的排队与优先级。
     */
    public static void showCacheSweep(String detail, boolean success) {
        publishNotice(success ? IslandNoticeType.REFRESH_SUCCESS : IslandNoticeType.REFRESH_ERROR,
                success ? "缓存已清理" : "缓存清理失败", safeNoticeValue(detail, "没有可回收的缓存"));
    }

    /**
     * 系统播放器镜像（Windows SMTC）的接管、失败与命令结果。
     *
     * <p>刻意沿用成功/失败那一对外观而不新增通知类型：这一族的文案本身就分「接管成功」与
     * 「外部播放器不接受这条命令」两类，图标语义完全对得上，为它加一个新类型只会让
     * 岛上的图标表再多一行而没有任何视觉差别。</p>
     */
    public static void showExternalMedia(String detail, boolean success) {
        publishNotice(success ? IslandNoticeType.REFRESH_SUCCESS : IslandNoticeType.REFRESH_ERROR,
                "系统播放器镜像", safeNoticeValue(detail, success ? "已接管" : "操作未完成"));
    }

    /** 显示最近播放立即上传的结果和请求延迟。 */
    public static void showModelOperationResult(String title, String detail, boolean success) {
        publishNotice(success ? IslandNoticeType.REFRESH_SUCCESS : IslandNoticeType.REFRESH_ERROR,
                safeNoticeValue(title, success ? "模型操作完成" : "模型操作失败"),
                safeNoticeValue(detail, success ? "操作已完成" : "请检查网络与模型目录"));
    }

    /** 显示累计收听时长心跳上报结果和请求延迟。 */
    public static void beginModelInference(String trackName, String modelNames) {
        publishOperation(IslandNoticeType.MODEL_INFERENCE,"模型推理中",
                safeNoticeValue(trackName,"当前歌曲") + " · " + safeNoticeValue(modelNames,"Folia 模型"),trackName);
    }

    /**
     * 确认某首曲目已被加入"下一首播放"队列。属于普通通知：由用户触发，但当前
     * 不会改变任何可听内容，因此像其他确认一样礼貌排队。
     *
     * @param pending 当前等待的用户排队曲目数量，包含本次
     */
    public static void finishModelInference(String trackName, String modelNames, long elapsedMillis) {
        completePersistentNotice(IslandNoticeType.MODEL_INFERENCE, IslandNoticeType.MODEL_INFERENCE_SUCCESS,
                "模型推理完成", "下一首就绪 · " + safeNoticeValue(trackName, "当前歌曲") + " · "
                        + safeNoticeValue(modelNames, "Folia 模型") + " · "
                        + Math.max(0L, elapsedMillis) + " ms",operationKey(IslandNoticeType.MODEL_INFERENCE,trackName));
    }

    /** 报告"下一首播放"没有可插入的队列，直接开始播放该曲目。 */
    public static void failModelInference(String trackName, String modelNames) {
        completePersistentNotice(IslandNoticeType.MODEL_INFERENCE, IslandNoticeType.MODEL_INFERENCE_FAILURE,
                "模型推理未完成", safeNoticeValue(trackName, "当前歌曲") + " · "
                        + safeNoticeValue(modelNames, "Folia 模型") + " · 已回退传统分析",operationKey(IslandNoticeType.MODEL_INFERENCE,trackName));
    }
    /** 报告 automix 开关本身被打开或关闭。 */
    public static void showPlaylistTrackAddInProgress(String playlistName) {
        publishOperation(IslandNoticeType.PLAYLIST_TRACK_ADDING,"加入歌单",
                "正在加入「" + safeNoticeValue(playlistName,"目标歌单") + "」",playlistName);
    }

    /** GD Studio API URL 解析的反馈。 */
    public static void showPlaylistTrackAddSuccess(String playlistName) {
        completePersistentNotice(IslandNoticeType.PLAYLIST_TRACK_ADDING,IslandNoticeType.PLAYLIST_TRACK_ADD_SUCCESS,"已加入歌单",
                "网易云已确认「" + safeNoticeValue(playlistName,"目标歌单") + "」",operationKey(IslandNoticeType.PLAYLIST_TRACK_ADDING,playlistName));
    }

    // 这条普通的后续通知有意进入已有的通知队列。它提供
    public static void showPlaylistTrackAlreadyExists(String playlistName) {
        completePersistentNotice(IslandNoticeType.PLAYLIST_TRACK_ADDING,IslandNoticeType.PLAYLIST_TRACK_ALREADY_EXISTS,"歌曲已在歌单中",
                "已确认「" + safeNoticeValue(playlistName,"目标歌单") + "」",operationKey(IslandNoticeType.PLAYLIST_TRACK_ADDING,playlistName));
    }

    // 明确的健康确认，而不打断实时加载器所保留的任何通知。
    public static void showPlaylistTrackAddFailure(String playlistName, String message) {
        String value = safeNoticeValue(message, "服务端未确认加入成功");
        completePersistentNotice(IslandNoticeType.PLAYLIST_TRACK_ADDING,IslandNoticeType.PLAYLIST_TRACK_ADD_ERROR,"加入歌单失败",
                safeNoticeValue(playlistName,"目标歌单") + " · " + value,operationKey(IslandNoticeType.PLAYLIST_TRACK_ADDING,playlistName));
    }

    /**
     * 搜索时通知：由另一个音源响应了查询。{@code requestedSourceFailed} 用于区分
     * 真正的故障与音源只是没有匹配结果，因此卡片绝不会误报原因。
     */
    public static void showPlaybackQuality(String quality, String format) {
        String safeQuality = safeNoticeValue(quality, "标准");
        String safeFormat = safeNoticeValue(format, "未知").toUpperCase(java.util.Locale.ROOT);
        publishNotice(IslandNoticeType.QUALITY, "获取音质：" + safeQuality, "格式: " + safeFormat);
    }

    /** 搜索时通知：所选音源和回退音源均未返回任何结果。 */
    public static void showUnsupportedMp4Container(String sourceName) {
        publishNotice(IslandNoticeType.PLAYBACK_ERROR, "检测到不兼容 MP4 容器",
                safeNoticeValue(sourceName, "当前音频") + " · 已跳过并尝试备用音源");
    }

    /** 为登录和账号校验提供一种与下载不同的、独立的成功反馈。 */
    public static void showPlaybackFailure(String songName, String reason) {
        String safeSong = safeNoticeValue(songName, "当前歌曲");
        String safeReason = safeNoticeValue(reason, "无法获取可播放音频");
        publishNotice(IslandNoticeType.PLAYBACK_ERROR, "播放失败", safeSong + " · " + safeReason);
    }

    /** 后台遥测状态保持静默，不占用播放反馈位。 */
    public static void showNeteaseUploadStarted(String label) {
    }

    /** 后台遥测成功无需占用可见提示位。 */
    public static void showRecentPlayUploadSuccess(long elapsedMillis) {
    }

    /** 后台遥测失败记入日志，不和播放错误争夺灵动岛展示。 */
    public static void showRecentPlayUploadFailure(long elapsedMillis, String reason) {
    }

    /** 听歌时长上报只属后台遥测，不覆盖播放状态。 */
    public static void showListeningDurationUploadSuccess(int playedSeconds, long elapsedMillis) {
    }

    /** 上报失败仍由日志/下次重试处理，不与播放错误混淆。 */
    public static void showListeningDurationUploadFailure(int playedSeconds, long elapsedMillis, String reason) {
    }
    // 后到的面向用户通知会占用活动槽位；把遥测卡片
    public static void showAutomixHandover(String trackName) {
    }

    /** 点击“下一首”时的加载阶段保持当前表面；起播后由歌曲卡展示结果。 */
    public static void showManualTrackLoading(String trackName) {
    }

    /** 下一曲已预备/已就绪仅是内部播放状态，不单独占用灵动岛提示位。 */
    public static void showAutomixArmed(String trackName) {
    }

    /** 用户主动加入下一首时保留一次确认；后台预备状态不走此通知。 */
    public static void showQueuedNext(String trackName, int pending) {
        String detail = pending > 1
                ? "已排在当前这首之后第 " + pending + " 位 · 播完后回到歌单"
                : "下一首就播 · 播完后回到歌单";
        publishNotice(IslandNoticeType.PLAY_NEXT, "已加入下一首播放",
                safeNoticeValue(trackName, "所选歌曲") + " · " + detail);
    }

    /** 已接入最近播放是队列后台变化，保持静默。 */
    public static void showSearchQueueFromRecentPlays(int count) {
    }

    /** 私人 FM 的后台预取属于内部状态；真正起播时由音乐卡片统一反馈。 */
    public static void showPersonalFmPrefetched(String trackName) {
    }

    /** 通知队列结果不需要跟随当前播放反复提示。 */
    public static void showQueuedNextStarted(String trackName) {
    }

    /** 音乐卡片可见时不重复弹出状态确认；卡片未展示时保留快捷键反馈。 */
    public static void showPlaybackPaused(String trackName) {
        if (musicCardIsPresented()) return;
        publishNotice(IslandNoticeType.PLAYBACK_TOGGLE, "已暂停",
                safeNoticeValue(trackName, "当前播放") + " · 再按一次继续");
    }

    /** 与暂停通知相同：播放状态已由音乐卡片呈现时静默降级。 */
    public static void showPlaybackResumed(String trackName) {
        if (musicCardIsPresented()) return;
        publishNotice(IslandNoticeType.PLAYBACK_TOGGLE, "继续播放",
                safeNoticeValue(trackName, "当前播放"));
    }

    /** 快捷键按下但当前没有可暂停的播放时的提示，避免用户以为按键没生效。 */
    public static void showPlaybackToggleUnavailable() {
        publishNotice(IslandNoticeType.PLAYBACK_TOGGLE, "暂停快捷键", "当前没有正在播放的歌曲");
    }

    /** 在配置的显示间隔之后推进已排队的普通通知。 */
    public static void showAutomixToggle(boolean enabled, String detail) {
        publishNotice(IslandNoticeType.AUTOMIX, enabled ? "无缝切换已开启" : "无缝切换已关闭",
                safeNoticeValue(detail, enabled ? "切歌时自动交叉淡化" : "切歌恢复为直接切换"));
    }

    /** 环境选曲按心情起播/切歌的反馈：标题是心情，细节是落点的歌。 */
    public static void showAmbientScene(String moodLabel, String trackName) {
        publishNotice(IslandNoticeType.AMBIENT_SCENE, safeNoticeValue(moodLabel, "环境选曲"),
                safeNoticeValue(trackName, "正在按环境选歌"));
    }

    /** 播放过程中的 GD 解析保持静默；成功起播由音乐卡片反馈，失败仍发错误提示。 */
    public static void showGdSourceResolving(String sourceName) {
    }

    /** GD 播放流已解析；实际起播后音乐卡片显示曲目与来源，不另发成功提示。 */
    public static void showGdSourceResolved(String sourceName, String format) {
        completePersistentNoticeSilently(IslandNoticeType.GD_SOURCE_LOADING, gdSourceChannelKey());
    }

    /** GD 解析成功不再抢占曲目展示；来源由播放卡片说明。 */
    public static void showGdSearchFallback(String requestedSource, String servedSource,
                                            boolean requestedSourceFailed) {
    }

    /** 搜索时所选音源与回退音源均不可用；这是可操作的播放错误，保留提示。 */
    public static void showGdSearchFailure(String requestedSource, String reason) {
        publishNotice(IslandNoticeType.GD_SOURCE_ERROR, "音源搜索失败",
                safeNoticeValue(requestedSource, "所选音源") + " · "
                        + safeNoticeValue(reason, "请求失败"));
    }

    public static void showGdSourceFailure(String sourceName, String reason) {
        String source = safeNoticeValue(sourceName, "GD 音乐源");
        completePersistentNotice(IslandNoticeType.GD_SOURCE_LOADING, IslandNoticeType.GD_SOURCE_ERROR,
                "GD音乐台链接获取失败", source + " · " + safeNoticeValue(reason, "解析失败"),
                gdSourceChannelKey());
    }


    /** 网易云解灰搜索属于取流内部过程，成功/失败结果按最终起播或错误路径展示。 */
    public static void showNeteaseUnblockSearching(String trackName) {
    }

    /** 解灰成功由音乐卡片展示来源；不单独弹出状态提示。 */
    public static void showNeteaseUnblocked(String trackName, String sourceLabel, String format) {
    }

    /** 解灰也没找到；随后由既有的「没有可用音频源」流程停下等用户操作。 */
    public static void showNeteaseUnblockFailure(String trackName) {
        publishNotice(IslandNoticeType.GD_SOURCE_ERROR, "解灰失败",
                safeNoticeValue(trackName, "当前歌曲") + " · 第三方源没有匹配到可播放版本");
    }

    /** 后台换源搜索过程保持静默，真实起播时以音乐卡片展示结果。 */
    public static void showCrossSourceSearching(String trackName) {
    }

    /** 换源成功并入下一次真实起播卡片；失败仍独立通知。 */
    public static void showCrossSourceSwitched(String trackName, String sourceLabel) {
    }

    /** 换源失败：带上为什么没换成，随后由既有的失败流程停下等用户操作。 */
    public static void showCrossSourceFailure(String trackName, String reason) {
        publishNotice(IslandNoticeType.GD_SOURCE_ERROR, "换源失败",
                safeNoticeValue(trackName, "当前歌曲") + " · " + safeNoticeValue(reason, "没有匹配到可播放版本"));
    }

    /** 即使 HTTP 进度回调未报告 100%，也完成当前下载。 */
    public static void showNetworkConnectionSuccess(String target) {
        publishNotice(IslandNoticeType.NETWORK_SUCCESS, "网络连接成功",
                safeNoticeValue(target, "音乐服务") + " · 已建立连接");
    }

    /**
     * 一起听：房里有非会员，VIP 专享曲在起播前被跳过。
     *
     * <p>走普通通知通道（不抢队）：歌已经换掉了，这条只是解释为什么。</p>
     */
    public static void showListenTogetherVipSkip(String trackName, String reason) {
        publishNotice(IslandNoticeType.PLAY_NEXT, "一起听 · 已跳过本曲",
                safeNoticeValue(trackName, "当前歌曲") + " · " + safeNoticeValue(reason, "房内有成员无法完整播放"));
    }

    /** 一起听：连着跳了几首之后整条列表都放不了，停下等用户处理。 */
    public static void showListenTogetherNothingPlayable(int skipped) {
        publishNotice(IslandNoticeType.NETWORK_ERROR, "一起听 · 没有可播曲目",
                "连续跳过 " + Math.max(1, skipped) + " 首 VIP 专享 · 请换歌单或关掉自动跳过");
    }

    // 则负责表面入场动画，并在岛屿可见期间保持稳定。
    public static void showNetworkConnectionFailure(String target, String reason) {
        publishNotice(IslandNoticeType.NETWORK_ERROR, "网络连接失败",
                safeNoticeValue(target, "音乐服务") + " · " + safeNoticeValue(reason, "请检查网络后重试"));
    }
    private static long noticeHoldMillis(boolean queueMode) {
        return DynamicIslandMath.completionHoldMillis(queueMode
                ? HudConfig.dynamicIslandQueueIntervalSeconds
                : HudConfig.dynamicIslandCompletionHoldSeconds);
    }

    private static void publishNotice(IslandNoticeType type, String title, String value) {
        publishNotice(type, title, value, false);
    }

    private static void publishNotice(IslandNoticeType type, String title, String value, boolean persistent) {
        publishNotice(type, title, value, persistent, 0L);
    }

    /**
     * @param ttlMillis 实时提示的绝对有效期，{@code <= 0} 表示没有业务时效。
     *                  排队和抢占恢复都保留原截止时间；普通结果只消耗真实展示预算。
     */
    private static void publishNotice(IslandNoticeType type, String title, String value,
                                      boolean persistent, long ttlMillis) {
        publishNotice(type,title,value,persistent,ttlMillis,type == null ? "" : type.name());
    }

    private static String operationKey(IslandNoticeType type,String identity) {
        return type.name() + ":" + safeNoticeValue(identity,"current");
    }

    private static void publishOperation(IslandNoticeType type,String title,String value,String identity) {
        publishNotice(type,title,value,true,0L,operationKey(type,identity));
    }

    /** GD 解析是播放器的单实例当前任务；开始/成功/失败必须共享同一把钥匙。 */
    private static String gdSourceChannelKey() {
        return operationKey(IslandNoticeType.GD_SOURCE_LOADING, "current");
    }

    private static void publishNotice(IslandNoticeType type,String title,String value,
                                      boolean persistent, long ttlMillis, String channelKey) {
        IslandNoticeType safeType = type == null ? IslandNoticeType.NONE : type;
        String safeTitle = safeNoticeValue(title, "状态");
        String safeValue = safeNoticeValue(value, "—");
        boolean modelInferenceStatusEnabled = (safeType == IslandNoticeType.MODEL_INFERENCE
                || safeType == IslandNoticeType.MODEL_INFERENCE_SUCCESS)
                && AutomixSettings.isModelInferenceStatusEnabled();
        if (IslandNoticeRules.suppressAuxiliaryNotice(safeType.name(), safeTitle,
                HudConfig.islandAuxiliaryNotices, modelInferenceStatusEnabled)) return;
        long now = System.currentTimeMillis();
        long expiresAt = ttlMillis > 0L ? now + ttlMillis : 0L;
        if (isLowestPriorityNotice(safeType)) {
            boolean durationChannel = safeType == IslandNoticeType.LISTENING_DURATION_SUCCESS
                    || safeType == IslandNoticeType.LISTENING_DURATION_ERROR
                    || (safeType == IslandNoticeType.NETEASE_UPLOADING && "听歌时长上报".equals(safeTitle));
            boolean recentChannel = safeType == IslandNoticeType.RECENT_PLAY_SUCCESS
                    || safeType == IslandNoticeType.RECENT_PLAY_ERROR
                    || (safeType == IslandNoticeType.NETEASE_UPLOADING && "听歌历史上报".equals(safeTitle));
            channelKey = durationChannel ? "telemetry:duration"
                    : recentChannel ? "telemetry:recent" : "telemetry:" + safeTitle;
        }
        synchronized (NOTICE_QUEUE_LOCK) {
            if (safeType == IslandNoticeType.NONE) return; // 取消必须走自己的通道，不能全队列清空。
            boolean latestOnly = isInstantStateChannel(safeType) || persistent
                    || safeType == IslandNoticeType.AUTOMIX_BLENDING || isLowestPriorityNotice(safeType);
            long originalDeadline=0L;
            if (persistent) {
                for (QueuedNotice waiting:NOTICE_QUEUE) {
                    if (waiting.persistent && waiting.channelKey.equals(channelKey)) originalDeadline=waiting.deadlineAt;
                }
                dropQueuedOperationLocked(channelKey);
            } else if (isLowestPriorityNotice(safeType)) {
                java.util.Iterator<QueuedNotice> iterator=NOTICE_QUEUE.iterator();
                while (iterator.hasNext()) {
                    QueuedNotice waiting=iterator.next();
                    if (isLowestPriorityNotice(waiting.type) && waiting.channelKey.equals(channelKey)) iterator.remove();
                }
            } else if (latestOnly) dropQueuedNoticesOfTypeLocked(safeType);
            boolean sameTelemetryChannel = isLowestPriorityNotice(safeType)
                    && isLowestPriorityNotice(noticeType) && noticeChannelKey.equals(channelKey);
            if (latestOnly && noticePersistent == persistent && noticeChannelKey.equals(channelKey)
                    && (noticeType == safeType || sameTelemetryChannel)) {
                publishActiveNoticeLocked(safeType,safeTitle,safeValue,persistent,
                        noticeUsesQueueInterval,now,expiresAt,noticeType == safeType);
                noticeChannelKey=channelKey;
                return;
            }
            if (noticeType == IslandNoticeType.NONE) {
                publishActiveNoticeLocked(safeType,safeTitle,safeValue,persistent,false,now,expiresAt);
                noticeChannelKey=channelKey;
                return;
            }
            NoticePriority incomingPriority=noticePriority(safeType,persistent);
            NoticePriority currentPriority=noticePriority(noticeType,noticePersistent);
            boolean protectedResume=noticeProtectedResume && NOTICE_VISIBLE_CLOCK.elapsed() < 700L;
            if (IslandNoticeRules.preempts(incomingPriority.rank,currentPriority.rank,noticePersistent,
                    persistent,isInstantStateChannel(safeType),protectedResume)) {
                preserveActiveQueuedNoticeLocked(now);
                publishActiveNoticeLocked(safeType,safeTitle,safeValue,persistent,false,now,expiresAt);
                noticeChannelKey=channelKey;
                if (persistent && originalDeadline > 0L) noticeDeadlineAt=originalDeadline;
                return;
            }
            markQueuedNoticesUseQueueIntervalLocked();
            QueuedNotice waiting=new QueuedNotice(safeType,safeTitle,safeValue,persistent,true,expiresAt,
                    -1L,persistent ? originalDeadline > 0L ? originalDeadline
                    : now + persistentNoticeTimeoutMillis(safeType) : 0L);
            waiting.channelKey=channelKey;
            enqueueQueuedNoticeLocked(waiting);
        }
    }

    private static void enqueueQueuedNoticeLocked(QueuedNotice notice) {
        enqueueQueuedNoticeLocked(notice,false);
    }

    private static void enqueueQueuedNoticeLocked(QueuedNotice notice,boolean resumeBeforePeers) {
        if (notice == null) return;
        if (NOTICE_QUEUE.isEmpty() || (resumeBeforePeers ? NOTICE_QUEUE.peekLast().priority.rank > notice.priority.rank
                : NOTICE_QUEUE.peekLast().priority.rank >= notice.priority.rank)) {
            NOTICE_QUEUE.addLast(notice);
            return;
        }
        Deque<QueuedNotice> reordered = new ArrayDeque<QueuedNotice>(NOTICE_QUEUE.size() + 1);
        boolean inserted = false;
        for (QueuedNotice current : NOTICE_QUEUE) {
            if (!inserted && (current.priority.rank < notice.priority.rank
                    || resumeBeforePeers && current.priority.rank == notice.priority.rank)) {
                reordered.addLast(notice);
                inserted = true;
            }
            reordered.addLast(current);
        }
        if (!inserted) reordered.addLast(notice);
        NOTICE_QUEUE.clear();
        NOTICE_QUEUE.addAll(reordered);
    }

    private static void dropQueuedOperationLocked(String channelKey) {
        java.util.Iterator<QueuedNotice> iterator=NOTICE_QUEUE.iterator();
        while (iterator.hasNext()) {
            QueuedNotice waiting=iterator.next();
            if (waiting.persistent && waiting.channelKey.equals(channelKey)) iterator.remove();
        }
    }

    /** 丢掉队列里所有同类通知。用于「同一件事只保留最新那一张卡」。 */
    private static void dropQueuedNoticesOfTypeLocked(IslandNoticeType type) {
        java.util.Iterator<QueuedNotice> iterator = NOTICE_QUEUE.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().type == type) iterator.remove();
        }
    }

    /**
     * 取下一条<b>仍然有效</b>的排队通知。
     *
     * <p>带时效的通知（目前只有智能过渡）过期就直接丢弃：它描述的是一件正在发生的事，等它排到时
     * 事情已经结束了，弹出来只会误导。丢弃发生在出队这一刻，所以它既不会抢别人的位置，也不会
     * 因为前面排了几张卡就把自己变成一张过期卡。</p>
     *
     * @return 下一条可显示的通知，队列里已经没有有效通知时返回 {@code null}
     */
    private static QueuedNotice pollNextNoticeLocked(long now) {
        while (!NOTICE_QUEUE.isEmpty()) {
            QueuedNotice next = NOTICE_QUEUE.removeFirst();
            if (next.expiresAt <= 0L || now < next.expiresAt) return next;
        }
        return null;
    }

    // 而不是从与当前常驻岛屿不匹配的紧凑药丸起步。
    private static void completePersistentNotice(IslandNoticeType loadingType, IslandNoticeType resultType,
                                                 String title, String value) {
        completePersistentNotice(loadingType,resultType,title,value,loadingType.name());
    }

    private static void completePersistentNotice(IslandNoticeType loadingType,IslandNoticeType resultType,
                                                 String title,String value,String channelKey) {
        long now = System.currentTimeMillis();
        synchronized (NOTICE_QUEUE_LOCK) {
            dropQueuedOperationLocked(channelKey);
            if (noticeType == loadingType && noticePersistent && noticeChannelKey.equals(channelKey)) {
                boolean queueMode = !NOTICE_QUEUE.isEmpty();
                if (queueMode) markQueuedNoticesUseQueueIntervalLocked();
                publishActiveNoticeLocked(resultType, safeNoticeValue(title, "状态"),
                        safeNoticeValue(value, "—"), false, queueMode, now);
                return;
            }
            publishNotice(resultType, title, value);
        }
    }

    // 让阴影贴合岛屿自身的圆角轮廓。之前展开的、
    private static void completePersistentNoticeSilently(IslandNoticeType loadingType, String channelKey) {
        long now = System.currentTimeMillis();
        synchronized (NOTICE_QUEUE_LOCK) {
            dropQueuedOperationLocked(channelKey);
            if (noticeType != loadingType || !noticePersistent || !noticeChannelKey.equals(channelKey)) return;
            QueuedNotice next = pollNextNoticeLocked(now);
            if (next != null) restoreQueuedNoticeLocked(next, now);
            else publishActiveNoticeLocked(IslandNoticeType.NONE, "", "", false, false, now);
        }
    }

    private static NoticePriority noticePriority(IslandNoticeType type, boolean persistent) {
        if (isLowestPriorityNotice(type)) return NoticePriority.LOW;
        if (isHighPriorityNotice(type)) return NoticePriority.HIGH;
        if (isInstantStateChannel(type)) return NoticePriority.INSTANT;
        return NoticePriority.NORMAL;
    }

    private static boolean isHighPriorityNotice(IslandNoticeType type) {
        return type == IslandNoticeType.PLAYBACK_ERROR
                || type == IslandNoticeType.NETWORK_ERROR
                || type == IslandNoticeType.REFRESH_ERROR
                || type == IslandNoticeType.PLAYLIST_TRACK_ADD_ERROR
                || type == IslandNoticeType.GD_SOURCE_ERROR
                || type == IslandNoticeType.MODEL_INFERENCE_FAILURE
                ;
    }

    private static boolean isLowestPriorityNotice(IslandNoticeType type) {
        return type == IslandNoticeType.LISTENING_DURATION_SUCCESS
                || type == IslandNoticeType.LISTENING_DURATION_ERROR
                // 上报进行中与上报结果是同一条遥测链路，优先级必须一致，否则"上报中"会插到
                // 用户触发的反馈前面，而它的结果卡片还老实排在后面。
                || type == IslandNoticeType.NETEASE_UPLOADING
                || type == IslandNoticeType.RECENT_PLAY_SUCCESS || type == IslandNoticeType.RECENT_PLAY_ERROR;
    }

    /**
     * 状态镜像通道：这条通知报的是「某样东西<b>现在</b>是什么」，不是「刚刚发生了什么」。
     *
     * <p>判据只有一条：同一通道永远只有一份真相，旧值不是「已经播过了的历史」，而是「已经
     * 不成立了的假消息」。主题、灵动岛样式、播放器大小、当前音质、暂停/继续全是这一类 ——
     * 它们原先走的是普通事件通道，于是连点五次主题就是 5 × 2.1 秒把五个早已作废的主题名挨个
     * 放给人看（回弹胶囊那款还会把它们叠成五行一起显示）。这里把它们改成：顶掉当前那张卡，
     * 并且队列里同类的过期副本当场作废。</p>
     *
     * <p>反面清单：一次下载、一次上报、一次加入歌单、一次播放失败都是<b>事件</b>，发生了 N 件
     * 就该看见 N 件，不许进这个集合。</p>
     */
    private static boolean isInstantStateChannel(IslandNoticeType type) {
        return type == IslandNoticeType.THEME
                || type == IslandNoticeType.STYLE
                || type == IslandNoticeType.SCALE
                || type == IslandNoticeType.VOLUME
                || type == IslandNoticeType.QUALITY
                || type == IslandNoticeType.PLAYBACK_TOGGLE;
    }

    // 让图标安全地位于圆角轮廓内部，并在文字前留出真正的间距。
    private static void preserveActiveQueuedNoticeLocked(long now) {
        if (noticeType == IslandNoticeType.NONE) return;
        long remaining=Math.max(0L,noticeBudgetMillis - NOTICE_VISIBLE_CLOCK.elapsed());
        if (!noticePersistent && remaining == 0L) return; // 这一张已经实际看完，不必重复。
        QueuedNotice interrupted=new QueuedNotice(noticeType,noticeTitle,noticeValue,noticePersistent,
                noticeUsesQueueInterval,noticeExpiresAt,remaining,noticeDeadlineAt);
        interrupted.channelKey=noticeChannelKey;
        enqueueQueuedNoticeLocked(interrupted,true);
    }

    private static void markQueuedNoticesUseQueueIntervalLocked() {
        for (QueuedNotice notice : NOTICE_QUEUE) {
            notice.useQueueInterval = true;
        }
    }

    private static void publishActiveNoticeLocked(IslandNoticeType type, String title, String value,
                                                   boolean persistent, boolean useQueueInterval, long shownAt) {
        publishActiveNoticeLocked(type, title, value, persistent, useQueueInterval, shownAt, 0L);
    }

    private static void publishActiveNoticeLocked(IslandNoticeType type, String title, String value,
                                                   boolean persistent, boolean useQueueInterval, long shownAt,
                                                   long expiresAt) {
        publishActiveNoticeLocked(type,title,value,persistent,useQueueInterval,shownAt,expiresAt,false);
    }

    /** Only an explicit update of the same operation/state may keep its display budget. */
    private static void publishActiveNoticeLocked(IslandNoticeType type, String title, String value,
                                                   boolean persistent, boolean useQueueInterval, long shownAt,
                                                   long expiresAt, boolean keepBudget) {
        noticeDeadlineAt=!persistent ? 0L : keepBudget && noticeDeadlineAt > 0L
                ? noticeDeadlineAt : shownAt + persistentNoticeTimeoutMillis(type);
        if (!keepBudget) {
            NOTICE_VISIBLE_CLOCK.reset();
            noticeProtectedResume=false;
            noticeBudgetMillis=Math.max(IslandNoticeRules.minimumVisibleMillis(noticePriority(type,persistent).rank,
                    isInstantStateChannel(type)),noticeHoldMillis(useQueueInterval));
        }
        // A new state value still needs a readable final frame, even after a long slider drag.
        if (keepBudget && !persistent && isInstantStateChannel(type)
                && (!title.equals(noticeTitle) || !value.equals(noticeValue))) {
            noticeBudgetMillis=Math.max(noticeBudgetMillis, NOTICE_VISIBLE_CLOCK.elapsed() + 500L);
        }
        noticeType=type; noticeTitle=title; noticeValue=value;
        noticeChannelKey=type.name();
        noticeExpiresAt=expiresAt; noticePersistent=persistent;
        noticeUsesQueueInterval=useQueueInterval; noticeShownAt=shownAt; noticeRevision++;
    }

    private static void restoreQueuedNoticeLocked(QueuedNotice next,long now) {
        publishActiveNoticeLocked(next.type,next.title,next.value,next.persistent,next.useQueueInterval,
                now,next.expiresAt);
        noticeChannelKey=next.channelKey;
        if (next.remainingVisibleMillis >= 0L) {
            noticeBudgetMillis=next.remainingVisibleMillis;
            noticeProtectedResume=true;
        }
        if (next.persistent && next.deadlineAt > 0L) noticeDeadlineAt=next.deadlineAt;
    }

    // 岛屿内容入场和文案过渡两种动画，而进度条仍进行插值。
    private static void pauseNoticeClock() {
        synchronized (NOTICE_QUEUE_LOCK) { NOTICE_VISIBLE_CLOCK.pause(); }
    }

    /** Post-draw acknowledgement. A stale frame must never charge a newer message. */
    private static void recordNoticePresentation(long revision, boolean presented, long now) {
        synchronized (NOTICE_QUEUE_LOCK) {
            if (noticeRevision != revision) return;
            if (presented) NOTICE_VISIBLE_CLOCK.record(now);
            else NOTICE_VISIBLE_CLOCK.pause();
        }
    }

    private static void advanceQueuedNotice(long now) {
        synchronized (NOTICE_QUEUE_LOCK) {
            List<QueuedNotice> timedOut=new ArrayList<QueuedNotice>();
            java.util.Iterator<QueuedNotice> waitingIterator=NOTICE_QUEUE.iterator();
            while (waitingIterator.hasNext()) {
                QueuedNotice waiting=waitingIterator.next();
                boolean stalled=waiting.type == IslandNoticeType.TRANSCODING && waiting.persistent
                        && waiting.channelKey.equals(transcodeChannelKey) && transcodeProgressAdvancedAt > 0L
                        ? now - transcodeProgressAdvancedAt >= TRANSCODE_STALL_TIMEOUT_MILLIS
                        : waiting.persistent && waiting.deadlineAt > 0L && now >= waiting.deadlineAt;
                if (stalled) { waitingIterator.remove(); timedOut.add(waiting); }
            }
            for (QueuedNotice waiting:timedOut) enqueueQueuedNoticeLocked(timeoutNotice(waiting));
            if (!timedOut.isEmpty() && noticeType != IslandNoticeType.NONE
                    && noticePriority(noticeType,noticePersistent).rank < NoticePriority.HIGH.rank) {
                preserveActiveQueuedNoticeLocked(now);
                QueuedNotice urgent=pollNextNoticeLocked(now);
                if (urgent != null) restoreQueuedNoticeLocked(urgent,now);
            }
            if (noticeType == IslandNoticeType.NONE) {
                QueuedNotice ready=pollNextNoticeLocked(now);
                if (ready != null) restoreQueuedNoticeLocked(ready,now);
                return;
            }

            if (noticePersistent) {
                if (isPersistentNoticeTimedOut(now)) {
                    boolean queueMode = !NOTICE_QUEUE.isEmpty();
                    if (queueMode) markQueuedNoticesUseQueueIntervalLocked();
                    if (noticeType == IslandNoticeType.REFRESHING) {
                        publishActiveNoticeLocked(IslandNoticeType.REFRESH_ERROR, "歌单同步超时",
                                "30 秒内未收到结果 · 请稍后重试", false, queueMode, now);
                    } else if (noticeType == IslandNoticeType.GD_SOURCE_LOADING) {
                        publishActiveNoticeLocked(IslandNoticeType.GD_SOURCE_ERROR, "GD音乐台链接超时",
                                "45 秒内未获取到播放链接 · 请稍后重试", false, queueMode, now);
                    } else if (noticeType == IslandNoticeType.PLAYLIST_TRACK_ADDING) {
                        publishActiveNoticeLocked(IslandNoticeType.PLAYLIST_TRACK_ADD_ERROR, "加入歌单超时",
                                "20 秒内未收到确认 · 请稍后重试", false, queueMode, now);
                    } else if (noticeType == IslandNoticeType.TRANSCODING) {
                        if (noticeChannelKey.equals(transcodeChannelKey)) {
                            transcoding = false;
                            transcodeProgress = 0.0;
                        }
                        publishActiveNoticeLocked(IslandNoticeType.PLAYBACK_ERROR, "转码无响应",
                                "30 秒内解码进度没有变化 · 已结束提示", false, queueMode, now);
                    } else if (noticeType == IslandNoticeType.MODEL_INFERENCE) {
                        publishActiveNoticeLocked(IslandNoticeType.MODEL_INFERENCE_FAILURE,"模型推理超时",
                                noticeValue + " · 已结束等待，后续结果仍会独立通知",false,queueMode,now);
                    } else {
                        // 通知刷新保持岛屿本身稳定。上一份文案向上退出，
                        // 最新值从下方进入；快速更新由 renderInternal 合并，
                        QueuedNotice next = pollNextNoticeLocked(now);
                        if (next != null) {
                            restoreQueuedNoticeLocked(next,now);
                        } else {
                            publishActiveNoticeLocked(IslandNoticeType.NONE, "", "", false, false, now);
                        }
                    }
                }
                return;
            }
            boolean realTimeExpired=noticeExpiresAt > 0L && now >= noticeExpiresAt;
            if (!realTimeExpired && NOTICE_VISIBLE_CLOCK.elapsed() < noticeBudgetMillis) return;

            QueuedNotice next = pollNextNoticeLocked(now);
            if (next != null) {
                restoreQueuedNoticeLocked(next,now);
                return;
            }

            publishActiveNoticeLocked(IslandNoticeType.NONE, "", "", false, false, now);
        }
    }

    private static QueuedNotice timeoutNotice(QueuedNotice waiting) {
        IslandNoticeType error=waiting.type == IslandNoticeType.REFRESHING ? IslandNoticeType.REFRESH_ERROR
                : waiting.type == IslandNoticeType.GD_SOURCE_LOADING ? IslandNoticeType.GD_SOURCE_ERROR
                : waiting.type == IslandNoticeType.PLAYLIST_TRACK_ADDING ? IslandNoticeType.PLAYLIST_TRACK_ADD_ERROR
                : waiting.type == IslandNoticeType.MODEL_INFERENCE ? IslandNoticeType.MODEL_INFERENCE_FAILURE
                : IslandNoticeType.PLAYBACK_ERROR;
        if (waiting.type == IslandNoticeType.TRANSCODING && waiting.channelKey.equals(transcodeChannelKey)) {
            transcoding=false; transcodeProgress=0.0;
        }
        return new QueuedNotice(error,waiting.title + "超时",waiting.value + " · 未收到完成结果，请稍后重试",
                false,true,0L);
    }

    // 而不是不断重启该过渡。
    private static boolean isPersistentNoticeTimedOut(long now) {
        if (noticeShownAt <= 0L) return false;
        if (noticeType == IslandNoticeType.TRANSCODING && noticeChannelKey.equals(transcodeChannelKey)) {
            // 转码检测真实进度的停滞，其余持久通道仍按自身的原始截止时间。
            long advancedAt = transcodeProgressAdvancedAt;
            return advancedAt > 0L && now - advancedAt >= TRANSCODE_STALL_TIMEOUT_MILLIS;
        }
        return noticeDeadlineAt > 0L && now >= noticeDeadlineAt;
    }

    // 成尴尬的两阶段形状，而非一次连续的居中收缩。
    private static long persistentNoticeTimeoutMillis(IslandNoticeType type) {
        if (type == IslandNoticeType.REFRESHING) return PLAYLIST_REFRESH_NOTICE_TIMEOUT_MILLIS;
        if (type == IslandNoticeType.GD_SOURCE_LOADING) return GD_SOURCE_NOTICE_TIMEOUT_MILLIS;
        if (type == IslandNoticeType.PLAYLIST_TRACK_ADDING) return PLAYLIST_TRACK_ADD_NOTICE_TIMEOUT_MILLIS;
        if (type == IslandNoticeType.TRANSCODING) return TRANSCODE_STALL_TIMEOUT_MILLIS;
        if (type == IslandNoticeType.MODEL_INFERENCE) return MODEL_INFERENCE_TIMEOUT_MILLIS;
        return DEFAULT_PERSISTENT_NOTICE_TIMEOUT_MILLIS;
    }
    private static final class QueuedNotice {
        private final IslandNoticeType type;
        private final String title;
        private final String value;
        private final boolean persistent;
        private final NoticePriority priority;
        private boolean useQueueInterval;
        /** 出队后仍然有效的截止时刻；{@code <= 0} 表示永不过期。 */
        private final long expiresAt;
        private final long remainingVisibleMillis;
        private final long deadlineAt;
        private String channelKey;

        private QueuedNotice(IslandNoticeType type, String title, String value,
                             boolean persistent, boolean useQueueInterval, long expiresAt) {
            this(type,title,value,persistent,useQueueInterval,expiresAt,-1L,0L);
        }

        private QueuedNotice(IslandNoticeType type,String title,String value,boolean persistent,
                             boolean useQueueInterval,long expiresAt,long remainingVisibleMillis,long deadlineAt) {
            this.channelKey=type.name();
            this.remainingVisibleMillis=remainingVisibleMillis;
            this.deadlineAt=deadlineAt;
            this.type = type;
            this.title = title;
            this.value = value;
            this.persistent = persistent;
            this.priority = noticePriority(type, persistent);
            this.useQueueInterval = useQueueInterval;
            this.expiresAt = expiresAt;
        }
    }

    private static String safeNoticeValue(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    // 紧凑的均衡器使播放质量通知区别于其他状态。
    public static boolean isDownloadActive() {
        return downloading || INSTANCE.completing
                || downloadCompletedAt > 0L && downloadCompletedAt != INSTANCE.acknowledgedCompletionAt;
    }

    // 不要使用固定基线：Fontello 字形有不同的上伸部和
    public static void beginDownload() {
        downloadProgress = 0.0;
        downloadSpeed = "0 b/s";
        downloadCompletedAt = 0L;
        downloadStartedAt = System.currentTimeMillis();
        downloading = true;
    }

    // 下伸部边界。按度量居中可让每个左侧通知图标保持在
    public static void updateProgress(double progress) {
        downloadProgress = DynamicIslandMath.clamp01(progress);
        if (progress >= 1.0) {
            downloadCompletedAt = System.currentTimeMillis();
            downloading = false;
        }
    }

    public static void updateSpeed(String speed) {
        downloadSpeed = speed == null ? "" : speed;
    }

    // 其圆形底盘的中央。
    public static void finishDownload() {
        downloadProgress = 1.0;
        downloadCompletedAt = System.currentTimeMillis();
        downloading = false;
    }

    /** 清除本次下载的进度与完成停留状态。 */
    public static void cancelDownload() {
        downloadProgress = 0.0;
        downloadStartedAt = 0L;
        downloadCompletedAt = 0L;
        downloading = false;
    }

    private void renderInternal() {
        long now = System.currentTimeMillis();
        if (playerAbsorbImpact && now - playerAbsorbStartedAt >= PLAYER_ABSORB_FEEDBACK_MS) {
            playerAbsorbImpact = false;
            playerAbsorbStartedAt = 0L;
            suppressPlayerEntryAnimation = false;
        }
        advanceQueuedNotice(now);
        if (playerSurfaceTransition) {
            pauseNoticeClock();
            completionVisibleClock.pause();
            musicCardOwnsSurface = false;
            MusicCapsuleCard.INSTANCE.cancelConfirmation();
            return;
        }
        double rawProgress = DynamicIslandMath.clamp01(downloadProgress);
        boolean activeDownload = downloading;
        final double sourceTranscodeProgress;
        long sourceStartedAt = downloadStartedAt;
        long sourceCompletedAt = downloadCompletedAt;
        final long sourceNoticeShownAt, sourceNoticeExpiresAt, sourceNoticeRevision;
        final IslandNoticeType sourceNoticeType;
        final String sourceNoticeTitle, sourceNoticeValue;
        final boolean sourceNoticePersistent, sourceNoticeQueueMode;
        synchronized (NOTICE_QUEUE_LOCK) {
            sourceTranscodeProgress=noticeChannelKey.equals(transcodeChannelKey)
                    ? DynamicIslandMath.clamp01(transcodeProgress) : 0.0;
            sourceNoticeShownAt=noticeShownAt; sourceNoticeExpiresAt=noticeExpiresAt;
            sourceNoticeRevision=noticeRevision; sourceNoticeType=noticeType;
            sourceNoticeTitle=noticeTitle; sourceNoticeValue=noticeValue;
            sourceNoticePersistent=noticePersistent; sourceNoticeQueueMode=noticeUsesQueueInterval;
        }

        // 场合门控：游戏外（主菜单/未进世界）与玩家列表打开时整块不画；播放器界面里只放通知，
        // 常驻状态栏让位。判定见 IslandVisibilityGate。
        IslandVisibilityGate.Scene scene = IslandVisibilityGate.capture();
        if (!IslandVisibilityGate.allowSurface(scene)) {
            // 直接把动画状态归零而不是原地冻结：下次允许显示时照常播一次入场，
            // 不会从上次被打断的半展开姿态突然跳出来。
            visibility = 0f;
            expansion = 0.0;
            contentAlpha = 0f;
            ambientBlend = 0f;
            animatedExpandedWidth = -1.0;
            animatedExpandedHeight = -1.0;
            resetMotionBlur();
            resetExpansionClock();
            previousAlwaysOn = false;
            ambientSnapshot = IslandAmbientStatus.EMPTY;
            ambientSampledAt = 0L;
            cardOpen = 0.0;
            cardOpenTarget = false;
            musicCardInteraction.reset();
            MUSIC_CARD_BOUNDS = null;
            musicCardDrawnSnapshot = null;
            MUSIC_CARD_ANCHOR_BOUNDS = null;
            musicCardOwnsSurface = false;
            previousMusicCardOwnsSurface = false;
            musicCardMotionBlur.reset();
            pauseNoticeClock();
            completionVisibleClock.pause();
            return;
        }

        boolean enabled = HudConfig.dynamicIslandEnabled;
        // Playback is sampled once by maintainMusicCardContext before any HUD/Screen gate.
        CloudMusic.PlaybackSnapshot playback = MusicCapsuleCard.currentSnapshot();
        MusicCapsuleCard.updateTrackNoticePlayback(playback);
        boolean chatMusic = enabled && HudConfig.islandHoverExpand && IslandVisibilityGate.isChatOpen()
                && playback.music != null;
        boolean musicCapsuleRow = enabled && getStyle() == DynamicIslandStyle.MUSIC_CAPSULE
                && MusicCapsuleCard.rowContentActive();
        boolean alwaysOn = enabled
                && (HudConfig.dynamicIslandAlwaysOn || musicCapsuleRow || chatMusic)
                && (IslandVisibilityGate.allowAmbient(scene) || playerAbsorbImpact);
        /*
         * 播放器交接回来的那一小段是无事件也必须显示岛的场合。
         * 常驻关闭时单独采一次「正在播放」，避免落入下载兜底文案。
         */
        boolean handoffAmbient = enabled && !alwaysOn && playerAbsorbImpact;
        // 常驻内容按固定节拍采样：帧率和延迟每帧都在抖，直接跟帧读会让数字闪烁得没法看。
        if (alwaysOn || handoffAmbient || musicCardClaimsSurface()) {
            if (ambientSampledAt <= 0L || now - ambientSampledAt >= IslandAmbientStatus.SAMPLE_INTERVAL_MILLIS) {
                ambientSnapshot = handoffAmbient
                        ? IslandAmbientStatus.captureHandoff(now)
                        : IslandAmbientStatus.capture(now);
                ambientSampledAt = now;
            }
        } else {
            ambientSnapshot = IslandAmbientStatus.EMPTY;
            ambientSampledAt = 0L;
        }
        // 已经稳定停在屏幕上的常驻岛必须锁住入场动画，否则每来一条通知都要重播一次缩放入场。
        boolean suppressEntry = musicCardClaimsSurface() || IslandAmbientStatus.suppressEntry(alwaysOn, visibility);
        boolean alwaysOnJustEnabled = alwaysOn && !previousAlwaysOn;
        previousAlwaysOn = alwaysOn;
        if (alwaysOnJustEnabled && !suppressEntry) {
            // 只有"刚打开常驻"这一次照常播入场，之后交给 suppressEntry 拦住全部重播。
            shownAt = now;
            contentTransition = 0f;
        }

        if (!initialized) {
            initialized = true;
            previousDownloading = activeDownload;
            lastObservedProgress = rawProgress;
            animatedProgress = activeDownload ? rawProgress : 0.0;
            observedNoticeRevision = sourceNoticeRevision;
            observedNoticeType = sourceNoticeType;
            observedNoticeTitle = sourceNoticeTitle;
            observedNoticeValue = sourceNoticeValue;
            animatedNoticeProgress = sourceNoticeType == IslandNoticeType.VOLUME
                    ? DynamicIslandMath.parseNoticePercent(sourceNoticeValue) / 100.0
                    : sourceNoticeType == IslandNoticeType.TRANSCODING ? sourceTranscodeProgress : 0.0;
            if (activeDownload) {
                shownAt = sourceStartedAt > 0L ? sourceStartedAt : now;
            } else if (sourceCompletedAt > 0L && sourceCompletedAt != acknowledgedCompletionAt) {
                completing = true;
                completeAt = sourceCompletedAt;
                completionVisibleClock.reset();
                animatedProgress = 1.0;
            } else if (sourceNoticeType != IslandNoticeType.NONE) {
                shownAt = sourceNoticeShownAt;
            }
        }

        boolean noticeChanged = sourceNoticeRevision != observedNoticeRevision;
        if (noticeChanged) {
            boolean noticeContentChanged = sourceNoticeType != observedNoticeType
                    || !sourceNoticeTitle.equals(observedNoticeTitle)
                    || !sourceNoticeValue.equals(observedNoticeValue);
            // noticeShownAt 由每个发布者刷新以延长保持窗口。shownAt
            // 则负责表面进入动画，并在灵动岛可见期间保持稳定。
            boolean islandAlreadyVisible = visibility > .035f || expansion > .05 || contentAlpha > .05f;
            observedNoticeRevision = sourceNoticeRevision;
            if (!activeDownload) {
                if (!islandAlreadyVisible && !suppressEntry) {
                    contentTransition = 0f;
                    noticeCopyTransition = 1f;
                    shownAt = sourceNoticeShownAt > 0L ? sourceNoticeShownAt : now;
                } else if (ambientBlend > .5f) {
                    // 从常驻状态栏切到通知：交叉淡入交给 ambientBlend 处理，这里不能再放出上一条
                    // 通知的残留文案，否则会看到一条早就过期的旧消息闪一下再被顶掉。
                    noticeCopyTransition = 1f;
                } else if (noticeContentChanged && noticeCopyTransition >= .84f) {
                    // 表面不会重新进入。复制过渡被合并，因此快速的
                    // 按键重复序列能干净地解析为其最新通知值。
                    outgoingNoticeTitle = observedNoticeTitle;
                    outgoingNoticeValue = observedNoticeValue;
                    noticeCopyTransition = 0f;
                }
            }
            observedNoticeType = sourceNoticeType;
            observedNoticeTitle = sourceNoticeTitle;
            observedNoticeValue = sourceNoticeValue;
        }

        boolean progressRestarted = activeDownload && rawProgress + .12 < lastObservedProgress;
        boolean justStarted = activeDownload && !previousDownloading;
        if (justStarted || progressRestarted) {
            completing = false;
            completeAt = 0L;
            successMorph = 0f;
            animatedProgress = rawProgress;
            if (!suppressEntry) {
                // 常驻态下一次新下载同样不重播入场：表面本来就在屏幕上，只需要换内容。
                contentTransition = 0f;
                shownAt = sourceStartedAt > 0L ? sourceStartedAt : now;
            }
        }

        // 快速下载可能在两个渲染帧之间开始并完成。最终进度
        // 边缘保持短暂的成功状态可见，而不是静默消失。
        boolean completedBetweenFrames = !activeDownload
                && rawProgress >= .995
                && lastObservedProgress < .995;
        boolean newlyObservedCompletion = !activeDownload && sourceCompletedAt > 0L
                && sourceCompletedAt != completeAt && sourceCompletedAt != acknowledgedCompletionAt;
        if (!activeDownload && (previousDownloading || completedBetweenFrames || newlyObservedCompletion)) {
            if (rawProgress >= .965) {
                completing = true;
                completeAt = sourceCompletedAt > 0L ? sourceCompletedAt : now;
                completionVisibleClock.reset();
                animatedProgress = Math.max(animatedProgress, rawProgress);
            } else {
                completing = false;
            }
        }

        if (!activeDownload && sourceCompletedAt == 0L && rawProgress < .965) {
            completing = false;
        }

        if (activeDownload) {
            completing = false;
            animatedProgress = Interpolations.interpolate(animatedProgress, rawProgress, .20f);
        } else if (completing) {
            animatedProgress = Interpolations.interpolate(animatedProgress, 1.0, .30f);
        }

        double noticeProgressTarget = sourceNoticeType == IslandNoticeType.VOLUME
                ? DynamicIslandMath.parseNoticePercent(sourceNoticeValue) / 100.0
                : sourceNoticeType == IslandNoticeType.TRANSCODING ? sourceTranscodeProgress : 0.0;
        if (sourceNoticeType == IslandNoticeType.VOLUME
                || sourceNoticeType == IslandNoticeType.TRANSCODING) {
            // 重复的转码/音量更新收敛为一个连续的进度条动画。
            animatedNoticeProgress = Interpolations.interpolate(animatedNoticeProgress, noticeProgressTarget, .26f);
        } else {
            animatedNoticeProgress = Interpolations.interpolate(animatedNoticeProgress, 0.0, .24f);
        }

        if (completing && completionVisibleClock.elapsed() >= DynamicIslandMath.completionHoldMillis(
                HudConfig.dynamicIslandCompletionHoldSeconds)) {
            acknowledgedCompletionAt=sourceCompletedAt;
            completing=false;
        }
        boolean holdingCompletion=completing;
        boolean activeNotice = sourceNoticeType != IslandNoticeType.NONE
                && sourceNoticeShownAt > 0L
                && now >= sourceNoticeShownAt
                && (sourceNoticeExpiresAt <= 0L || now < sourceNoticeExpiresAt);
        double frameDelta = Math.min(4.0, Math.max(0.0, RenderSystem.getFrameDeltaTime()));
        boolean surfaceBlocked = musicCardSurfaceBlockedNow();
        tickMusicCardState(surfaceBlocked, frameDelta * 10.0);
        final boolean cardOwnsFrame = musicCardClaimsSurface();
        final AutomixIslandRenderer.Frame mixFrame = !cardOwnsFrame && enabled && !activeDownload && !holdingCompletion
                && (!activeNotice || allowsAutomixCurves(sourceNoticeType))
                ? AutomixTransitionAnimation.islandFrame() : null;
        boolean mixMode = mixFrame != null;
        int sourceNoticePriority = noticePriority(sourceNoticeType, sourceNoticePersistent).rank;
        boolean inferenceSuccessBubble = activeNotice && enabled
                && sourceNoticeType == IslandNoticeType.MODEL_INFERENCE_SUCCESS;
        boolean downloadCompletionBubble = IslandNoticeRules.downloadCompletionUsesBubble(holdingCompletion,
                enabled, cardOwnsFrame, activeNotice, sourceNoticePriority);
        boolean completionBubble = enabled && !cardOwnsFrame
                && IslandNoticeRules.completionUsesBubble(downloadCompletionBubble, inferenceSuccessBubble);
        boolean noticeMode = !inferenceSuccessBubble && IslandNoticeRules.ownsSurface(activeNotice,
                sourceNoticePriority, activeDownload, holdingCompletion, cardOwnsFrame);
        boolean handoffShowsAmbient = handoffAmbient && !activeDownload && !holdingCompletion && !activeNotice;
        boolean ambientMode = !cardOwnsFrame && !mixMode && (handoffShowsAmbient
                || IslandAmbientStatus.isAmbient(enabled, alwaysOn, activeDownload, holdingCompletion, noticeMode));
        IslandAmbientStatus.Snapshot ambient = ambientSnapshot;
        boolean shown = cardOwnsFrame ? enabled : IslandAmbientStatus.shouldShow(enabled, alwaysOn, activeDownload,
                holdingCompletion, activeNotice);
        if (playerAbsorbImpact || mixMode) shown = enabled;
        double expansionGoal = musicCapsuleRow && !surfaceBlocked ? 1.0
                : completionBubble ? 0.0
                : cardOwnsFrame ? IslandAmbientStatus.expansionTarget(true, true, alwaysOn && !ambient.isEmpty())
                : IslandAmbientStatus.expansionTarget(shown, ambientMode, !ambient.isEmpty());

        // 动画节奏来自配置：每个逼近系数乘以对应的速度倍率，再钳到 (0,.95] 以免越过目标值抖动。
        float expandRate = animationRate(shown ? HudConfig.dynamicIslandExpandSpeed
                : HudConfig.dynamicIslandCollapseSpeed, shown ? .42f : .48f);
        float successRate = animationRate(HudConfig.dynamicIslandContentSpeed, .28f);

        long clockNow = System.nanoTime();
        double speedFactor = DynamicIslandMath.clamp(shown
                ? HudConfig.dynamicIslandExpandSpeed : HudConfig.dynamicIslandCollapseSpeed, .40, 2.50);
        double durationMillis = (shown ? 165.0 : 180.0) / speedFactor;
        if (!visibilityClockInitialized || shown != visibilityClockTarget) {
            visibilityClockInitialized = true;
            visibilityClockTarget = shown;
            visibilityStart = visibility;
            visibilityElapsedMillis = 0.0;
            visibilityDurationMillis = durationMillis;
            visibilityLastNanos = clockNow;
        }
        double elapsedMillis = visibilityLastNanos <= 0L ? 0.0
                : Math.max(0.0, Math.min(40.0, (clockNow - visibilityLastNanos) / 1_000_000.0));
        visibilityLastNanos = clockNow;
        visibilityElapsedMillis = Math.min(visibilityDurationMillis, visibilityElapsedMillis + elapsedMillis);
        double progress = visibilityDurationMillis <= 0.0 ? 1.0
                : visibilityElapsedMillis / visibilityDurationMillis;
        double visibilityEased = com.muoniumplayer.core.screens.ncm.TransitionCurveCache.MUSIC_CARD.at(progress);
        visibility = (float) DynamicIslandMath.lerp(visibilityStart, shown ? 1.0 : 0.0, visibilityEased);
        if (progress >= 1.0) visibility = shown ? 1f : 0f;
        expansion = tickIslandExpansion(shown, expansionGoal, frameDelta);
        contentAlpha = (float) DynamicIslandMath.clamp01(expansion);
        contentTransition = (float) DynamicIslandMath.clamp01(expansion);
        noticeCopyTransition = (float) DynamicIslandMath.clamp01(expansion);
        ambientBlend = (float) DynamicIslandMath.clamp01(DynamicIslandMath.lerp(ambientBlend,
                ambientMode ? 1.0 : 0.0, com.muoniumplayer.core.screens.ncm.TransitionCurveCache.MOTION.at(
                        Math.max(0.0, Math.min(1.0, frameDelta * 3.0)))));
        successMorph = Interpolations.interpolate(successMorph,
                enabled && holdingCompletion && !noticeMode && !completionBubble ? 1f : 0f, successRate);

        spinnerRotation = (spinnerRotation + frameDelta * 7.2
                * DynamicIslandMath.clamp(HudConfig.dynamicIslandSpinnerSpeed, .30, 3.0)) % 360.0;

        previousDownloading = activeDownload;
        lastObservedProgress = rawProgress;

        if (visibility <= .012f && !shown) {
            successMorph = 0f;
            pauseNoticeClock();
            completionVisibleClock.pause();
            return;
        }

        final boolean renderedShouldShow = shown;
        final double easedExpansion = DynamicIslandMath.smoothStep(expansion);
        final float alpha = DynamicIslandMath.clamp01f(visibility);
        final float renderedContentTransition = DynamicIslandMath.clamp01f(contentTransition);
        final String speed = downloadSpeed;
        final boolean renderNotice = noticeMode;
        final boolean renderedHoldingCompletion = !cardOwnsFrame && holdingCompletion;
        final long renderedCardRevision = musicCardInteraction.eventRevision();
        final IslandNoticeType renderedNoticeType = sourceNoticeType;
        final String renderedNoticeTitle = sourceNoticeTitle;
        final String renderedNoticeValue = sourceNoticeValue;
        final boolean renderedNoticePersistent = sourceNoticePersistent;
        final long renderedNoticeRevision = sourceNoticeRevision;
        final long renderedCompletionBubbleStart = inferenceSuccessBubble ? sourceNoticeShownAt : completeAt;
        final String renderedCompletionBubbleText = inferenceSuccessBubble ? "下一首就绪" : "下载完成";
        final boolean renderedCompletionCapsuleRow = getStyle() == DynamicIslandStyle.MUSIC_CAPSULE
                && MusicCapsuleCard.rowContentActive();
        final String renderedOutgoingNoticeTitle = outgoingNoticeTitle;
        final String renderedOutgoingNoticeValue = outgoingNoticeValue;
        final float renderedNoticeCopyTransition = DynamicIslandMath.clamp01f(noticeCopyTransition);
        final double renderedNoticeProgress = DynamicIslandMath.clamp01(animatedNoticeProgress);
        final IslandAmbientStatus.Snapshot renderedAmbient = ambient;
        final float renderedAmbientBlend = DynamicIslandMath.clamp01f(ambientBlend);
        renderIsolated(new Runnable() {
            @Override
            public void run() {
                drawIsland(easedExpansion, alpha, renderedContentTransition, animatedProgress,
                        renderNotice || cardOwnsFrame ? 0f : successMorph, speed,
                        System.currentTimeMillis(), shownAt, false, renderNotice,
                        renderedNoticeType, renderedNoticeTitle, renderedNoticeValue,
                        renderedOutgoingNoticeTitle, renderedOutgoingNoticeValue,
                        renderedNoticeCopyTransition, renderedNoticeProgress, renderedNoticePersistent,
                        renderedAmbient, renderedAmbientBlend, mixFrame, renderedShouldShow,
                        activeNotice && sourceNoticeType == IslandNoticeType.MODEL_INFERENCE,
                        activeDownload,
                        completionBubble, inferenceSuccessBubble, renderedCompletionBubbleText,
                        renderedCompletionBubbleStart, renderedCompletionCapsuleRow);
                boolean cardPresented = cardOwnsFrame && musicCardOwnsSurface && MUSIC_CARD_BOUNDS != null
                        && visibility > .90f && musicCardInteraction.openness() >= .995
                        && SkijaGfx.active() && SkijaGfx.canvas() != null
                        && MusicCapsuleCard.matchesFrame(MusicCapsuleCard.trackNoticeSnapshot())
                        && MusicCapsuleCard.presentationReady() && !MusicCapsuleCard.trackTransitionActive();
                musicCardInteraction.recordPresentation(renderedCardRevision, cardPresented, System.currentTimeMillis());
                if (cardOwnsFrame) {
                    pauseNoticeClock();
                    completionVisibleClock.pause();
                }
                double[] mouse = musicMouseLayout();
                if (mouse != null && IslandVisibilityGate.isChatOpen()) {
                    MusicCapsuleCard.INSTANCE.drawConfirmation(mouse[0], mouse[1]);
                }
                boolean backendReady = SkijaGfx.active() && SkijaGfx.canvas() != null;
                boolean bubbleEntranceComplete = renderedCompletionBubbleStart > 0L
                        && System.currentTimeMillis() - renderedCompletionBubbleStart >= 210L;
                boolean bubblePresented = IslandNoticeRules.bubblePresentationReady(completionBubble,
                        backendReady, visibility, bubbleEntranceComplete);
                boolean noticePresented = inferenceSuccessBubble ? bubblePresented
                        : IslandNoticeRules.presentationReady(enabled && renderNotice && mixFrame == null,
                        backendReady,
                        visibility, expansion, contentAlpha, contentTransition,
                        renderedNoticeCopyTransition, isInstantStateChannel(renderedNoticeType));
                recordNoticePresentation(sourceNoticeRevision, noticePresented, System.currentTimeMillis());
                if (renderedHoldingCompletion && !inferenceSuccessBubble && bubblePresented && mixFrame == null
                        && !downloading && downloadCompletedAt == sourceCompletedAt
                        && backendReady) {
                    completionVisibleClock.record(System.currentTimeMillis());
                } else if (renderedHoldingCompletion) completionVisibleClock.pause();
            }
        }, true);
    }

    private static boolean allowsAutomixCurves(IslandNoticeType type) {
        return type == IslandNoticeType.AUTOMIX || type == IslandNoticeType.AUTOMIX_BLENDING
                || type == IslandNoticeType.PLAY_NEXT || type == IslandNoticeType.RECENT_PLAY_SUCCESS
                || type == IslandNoticeType.LISTENING_DURATION_SUCCESS;
    }

    private void renderPreviewInternal() {
        if (RenderSystem.getLayoutWidth() <= 0 || RenderSystem.getLayoutHeight() <= 0) return;
        renderIsolated(new Runnable() {
            @Override
            public void run() {
                float alpha = HudConfig.dynamicIslandEnabled ? .96f : .46f;
                // 打开常驻时预览直接展示常驻状态栏，让这个开关在 HUD 编辑器里可见即所得。
                IslandAmbientStatus.Snapshot ambient = HudConfig.dynamicIslandEnabled
                        && HudConfig.dynamicIslandAlwaysOn
                        ? IslandAmbientStatus.capture(System.currentTimeMillis())
                        : IslandAmbientStatus.EMPTY;
                drawIsland(1.0, alpha, 1f, .64, 0f,
                        HudConfig.dynamicIslandEnabled ? "1.8 MB/s" : "灵动岛已关闭",
                        System.currentTimeMillis(), 0L, true, false,
                        IslandNoticeType.NONE, "", "", "", "", 1f, 0.0, false,
                        ambient, ambient.isEmpty() ? 0f : 1f, null, true,
                        false, false,
                        false, false, "", 0L, false);
            }
        }, false);
    }

    private void renderIsolated(Runnable renderer, boolean correctionLayer) {
        SkijaGfx.pushAttrib();
        api.getGLStateManager().pushMatrix();
        try {
            // 灵动岛是全局覆盖层，不能继承播放器界面或别的 mod 留下的裁剪区域，
            // 否则左侧状态图标会被切掉一半。
            //
            // 旧实现靠关掉 GL 的裁剪与模板开关来做到这一点。Skia 下裁剪跟着 canvas 的
            // save 栈走，没有全局开关可关——而灵动岛是在最外层调用的，
            // 本来就不在任何裁剪层里面，所以只需要把混合方程与当前颜色摆回常态。
            SkijaGfx.setBlendMode(io.github.humbleui.skija.BlendMode.SRC_OVER);
            SkijaGfx.setColor(1f, 1f, 1f, 1f);
            if (correctionLayer) {
                // 布局轴几何 → 当前 canvas 的 GUI 逻辑坐标（与 widget 的 correction 层同方向
                // 同因子）。真实渲染路径（render() 已进布局括号）必须压这一层；编辑器预览
                // （renderEditorPreview）不压——编辑器 HUD 段的外层矩阵已带同值 correction，
                // 再压就是双乘，预览和实物会差一个因子。
                api.getGLStateManager().scale(WindowAdaptiveScale.correctionX(),
                        WindowAdaptiveScale.correctionY(), 1);
            }
            renderer.run();
        } finally {
            api.getGLStateManager().popMatrix();
            SkijaGfx.popAttrib();
        }
    }

    private void drawIsland(double expansionValue, float alpha, float contentFade,
                            double progress, float success, String speedValue, long now,
                            long animationStart, boolean preview, boolean noticeMode,
                            IslandNoticeType activeNoticeType, String activeNoticeTitle,
                            String activeNoticeValue, String outgoingNoticeTitle,
                            String outgoingNoticeValue, float noticeCopyBlend,
                            double noticeProgress, boolean activeNoticePersistent,
                            IslandAmbientStatus.Snapshot ambient, float ambientBlendValue,
                            AutomixIslandRenderer.Frame mixFrame, boolean surfaceActive,
                            boolean inferenceStatusActive, boolean downloadInProgress,
                            boolean completionBubble, boolean inferenceSuccessBubble,
                            String completionBubbleText, long completionBubbleStartedAt,
                            boolean completionCapsuleRow) {
        double configuredScale = DynamicIslandMath.clamp(HudConfig.dynamicIslandScale, .60, 1.35);
        // 布局轴宽（真实渲染已进布局括号、编辑器预览也在编辑器的布局括号内），恒定、
        // 与 GUI Scale 无关；配合 renderIsolated 的 correction 层落在正确的物理尺寸上。
        double screenWidth = RenderSystem.getLayoutWidth();
        DynamicIslandStyle style = getStyle();
        boolean bounce = style == DynamicIslandStyle.BOUNCE;
        boolean musicCapsule = style == DynamicIslandStyle.MUSIC_CAPSULE;
        // 回弹胶囊把所有事件按行堆出来，所以先要问出本帧到底有几行；其他样式不读这个列表。
        boolean cardOwnsFrame = !preview && musicCardOwnsSurface && !playerSurfaceTransition;
        double cardTransition = musicCardInteraction.openness();
        boolean cardTransitionActive = cardOwnsFrame && musicCardInteraction.animationActive();
        boolean cardReleased = !preview && !cardTransitionActive && previousCardTransitionActive;
        boolean returnContentAvailable = !noticeMode && mixFrame == null && !isDownloadActive() && !completing;
        if (!preview) {
            if (cardTransitionActive) {
                musicCardReturnAmbient = ambient == null ? IslandAmbientStatus.EMPTY : ambient;
                musicCardReturning = false;
            } else if (cardReleased && returnContentAvailable) {
                musicCardReturning = true;
            }
            if (!returnContentAvailable || surfaceActive && ambient != null && !ambient.isEmpty()
                    && ambientBlendValue >= .999f || alpha <= .012f || playerSurfaceTransition) musicCardReturning = false;
        }
        boolean returningIsland = !preview && musicCardReturning;
        if (returningIsland) ambient = musicCardReturnAmbient;
        List<BounceRow> bounceStack = bounce && (!musicCardOwnsSurface || preview)
                ? bounceCollectRows(now, noticeMode, activeNoticeType, activeNoticeTitle,
                activeNoticeValue, preview)
                : java.util.Collections.<BounceRow>emptyList();
        IslandLayout layout = createIslandLayout(style, noticeMode, activeNoticeType, activeNoticeTitle,
                activeNoticeValue, speedValue, preview, configuredScale, screenWidth);
        boolean returningAmbient = cardOwnsFrame && ambient != null && !ambient.isEmpty();
        float ambientMix = returningAmbient || returningIsland ? 1f : DynamicIslandMath.clamp01f(ambientBlendValue);
        boolean hasAmbient = ambient != null && !ambient.isEmpty();
        if (!hasAmbient) ambientMix = 0f;
        boolean idleWithoutContent = !preview && !musicCapsule && !hasAmbient && returnContentAvailable;
        if (idleWithoutContent) {
            progress = 0.0;
            success = 0f;
            contentFade = 0f;
        }

        // 常驻文字的排版比例：内容超出可用宽度时先缩字号，永远不截断实时数值。
        double ambientScale = DynamicIslandMath.clamp(HudConfig.dynamicIslandTextScale,
                MIN_AUTO_TEXT_SCALE, MAX_TEXT_SCALE);
        double ambientTargetWidth = layout.targetWidth;
        double ambientTargetHeight = layout.targetHeight;
        if (ambientMix > .002f) {
            double budget = DynamicIslandLayoutCalculator.ambientContentBudget(style, configuredScale, screenWidth);
            double rawRowWidth = measureAmbientRow(ambient);
            if (rawRowWidth * ambientScale > budget) {
                ambientScale = Math.max(MIN_AUTO_TEXT_SCALE, budget / Math.max(1.0, rawRowWidth));
            }
            DynamicIslandLayoutCalculator.LayoutData ambientLayout =
                    DynamicIslandLayoutCalculator.calculateAmbient(style, rawRowWidth * ambientScale,
                            configuredScale, screenWidth, COMPACT_WIDTH);
            ambientTargetWidth = ambientLayout.targetWidth;
            ambientTargetHeight = ambientLayout.targetHeight;
        }
        // 几何形状仅跟随一个低通滤波器。在此处混合通知/环境目标然后
        // 向该移动目标动画会产生级联滞后：内容缩短时似乎
        // 在两侧开始向内移动之前暂停。内容仍与 ambientMix 交叉淡入淡出；
        // 轮廓通过一个动画立即跟踪当前活动布局。
        boolean ambientGeometry = hasAmbient && ambientMix >= .5f;
        double targetExpandedWidth = ambientGeometry ? ambientTargetWidth : layout.targetWidth;
        double targetExpandedHeight = ambientGeometry ? ambientTargetHeight : layout.targetHeight;
        if (musicCapsule && noticeMode) {
            targetExpandedWidth = MusicCapsuleCard.capsuleNoticeWidth(layout.targetWidth,
                    targetExpandedWidth, configuredScale, screenWidth);
        }
        if (bounce) {
            // 参考稿的行高恒定：一个事件一行、每行 32，多事件把胶囊往上叠，而不是换一张更高的卡。
            double rowScale = bounceRowTextScale(layout);
            targetExpandedWidth = Math.max(targetExpandedWidth, bounceStackWidth(bounceStack, rowScale));
            if (hasAmbient && ambientMix >= .5f) {
                // 静息行是「品牌 - 数值 - 数值」串起来的，比通用排版宽，必须自己报一个宽度，
                // 否则弹簧会按通用宽度收，正文最后一项被裁掉。
                targetExpandedWidth = Math.max(targetExpandedWidth,
                        bounceIdleRowWidth(ambient, bouncePlainRowTextScale(layout)));
            }
            targetExpandedHeight = BounceIslandMath.ROW_HEIGHT * Math.max(1, bounceStack.size());
            // 参考稿自带的一条上限：窄屏上宁可让渲染层裁字，也不许顶到屏幕两边。
            targetExpandedWidth = Math.min(targetExpandedWidth,
                    Math.max(BounceIslandMath.MIN_WIDTH, screenWidth / Math.max(.2, configuredScale) - 16.0));
        }

        double compactWidth = bounce ? BounceIslandMath.SIDE_PADDING * 2.0 + BounceIslandMath.LOGO_AREA_WIDTH
                : (style == DynamicIslandStyle.COMPACT ? 46.0 : COMPACT_WIDTH);
        double compactHeight = bounce ? BounceIslandMath.ROW_HEIGHT
                : (style == DynamicIslandStyle.COMPACT ? 14.0 : COMPACT_HEIGHT);
        if (completionCapsuleRow) {
            targetExpandedWidth = MusicCapsuleCard.rowTargetWidth();
            targetExpandedHeight = MusicCapsuleCard.ROW_HEIGHT;
        } else if (completionBubble) {
            targetExpandedWidth = compactWidth;
            targetExpandedHeight = compactHeight;
        }
        double islandHandoff = cardTransitionActive ? MusicCardMotion.islandHandoff(cardTransition) : 0.0;
        boolean trackNotice = cardTransitionActive && musicCardInteraction.trackNoticeActive();
        boolean inferenceBorderActive = !cardOwnsFrame && inferenceStatusActive;
        boolean downloadBorderActive = !cardOwnsFrame && downloadInProgress;
        boolean activityBorderActive = IslandNoticeRules.activityBorderActive(
                inferenceBorderActive, downloadBorderActive);
        boolean musicRow = MusicCapsuleCard.showCapsuleRow(musicCapsule, preview,
                noticeMode || activityBorderActive, downloadBorderActive,
                !cardOwnsFrame && completing, mixFrame != null,
                MusicCapsuleCard.rowContentActive(), cardTransitionActive, activityBorderActive);
        boolean capsuleRow = IslandNoticeRules.keepCapsuleRow(musicCapsule,
                MusicCapsuleCard.rowContentActive(), cardOwnsFrame, cardTransitionActive)
                || musicRow || cardOwnsFrame && musicCapsule && MusicCapsuleCard.rowContentActive();
        if (capsuleRow) {
            targetExpandedWidth = MusicCapsuleCard.rowTargetWidth();
            targetExpandedHeight = MusicCapsuleCard.ROW_HEIGHT;
        }
        if (returningIsland) {
            targetExpandedWidth = animatedExpandedWidth > 0.0 ? animatedExpandedWidth : compactWidth;
            targetExpandedHeight = animatedExpandedHeight > 0.0 ? animatedExpandedHeight : compactHeight;
        } else if (!surfaceActive || !musicCapsule && !hasAmbient && (cardOwnsFrame
                || !noticeMode && mixFrame == null && !isDownloadActive() && !completing)) {
            // 混音结束且没有接替内容时，目标仍是紧凑轮廓；不能在退场一帧回跳到下载兜底大卡片。
            targetExpandedWidth = compactWidth;
            targetExpandedHeight = compactHeight;
        }
        if (mixFrame != null) {
            // 曲线只对岛本身的小尺寸目标做形变，不另开全屏面板。
            double mixWidth = AutomixIslandRenderer.targetWidth(screenWidth, configuredScale);
            double mixHeight = Math.min(AutomixIslandRenderer.MAX_HEIGHT,
                    Math.max(AutomixIslandRenderer.TARGET_HEIGHT, compactHeight + 2.0));
            targetExpandedWidth = DynamicIslandMath.lerp(compactWidth, mixWidth, mixFrame.opacity());
            targetExpandedHeight = DynamicIslandMath.lerp(compactHeight, mixHeight, mixFrame.opacity());
        }
        double expandedWidth;
        double expandedHeight;
        if (bounce) {
            /*
             * 弹簧通道。低通那一路（{@link #animateExpandedDimension}）对这一款完全不参与，
             * 但结果仍写回 {@link #animatedExpandedWidth}：那三处「归零到 -1」的哨兵于是
             * 一并管住了弹簧的重新起跳，不需要在每处再补一遍重置。
             */
            boolean primed = animatedExpandedWidth > 0.0 && animatedExpandedHeight > 0.0;
            double springDt = bounceFrameSeconds();
            if (preview || !primed || returningIsland || cardTransitionActive) {
                bounceWidth.snapTo(targetExpandedWidth);
                bounceHeight.snapTo(targetExpandedHeight);
            } else {
                bounceWidth.stepTo(targetExpandedWidth, springDt);
                bounceHeight.stepTo(targetExpandedHeight, springDt);
            }
            expandedWidth = bounceWidth.value();
            expandedHeight = bounceHeight.value();
        } else {
            expandedWidth = preview ? targetExpandedWidth
                    : animateExpandedDimension(animatedExpandedWidth, targetExpandedWidth);
            expandedHeight = preview ? targetExpandedHeight
                    : animateExpandedDimension(animatedExpandedHeight, targetExpandedHeight);
        }
        if (!preview && cardReleased && returnContentAvailable && liveAnchorLocalWidth > 0.0
                && liveAnchorLocalHeight > 0.0) {
            // 末帧就是普通岛的首帧；接管不重新套一遍展开系数或弹簧。
            expandedWidth = liveAnchorLocalWidth;
            expandedHeight = liveAnchorLocalHeight;
            if (bounce) {
                bounceWidth.snapTo(expandedWidth);
                bounceHeight.snapTo(expandedHeight);
            }
        }
        if (!preview) {
            animatedExpandedWidth = expandedWidth;
            animatedExpandedHeight = expandedHeight;
        }

        double width = DynamicIslandMath.lerp(compactWidth, expandedWidth, expansionValue);
        double height = DynamicIslandMath.lerp(compactHeight, expandedHeight, expansionValue);
        if (returningIsland) {
            width = expandedWidth;
            height = expandedHeight;
        }
        double centerX = screenWidth * .5;
        MusicCardMotion.Frame anchorFrame = null;
        MusicCardMotion.Frame cardFrame = null;
        MusicCardMotionBlur.Sample cardMotion = MusicCardMotionBlur.Sample.STILL;
        if (cardTransitionActive) {
            // 普通岛的平滑器在后台跟踪收起终点；卡片轮廓只使用本次交接的锚点，不级联平滑。
            double anchorWidth = liveAnchorLocalWidth > 0.0 ? liveAnchorLocalWidth : width;
            double anchorHeight = liveAnchorLocalHeight > 0.0 ? liveAnchorLocalHeight : height;
            boolean ownershipChanged = cardTransitionActive != previousCardTransitionActive;
            cardBaseWidth = ownershipChanged || cardBaseWidth <= 0.0 ? anchorWidth : cardBaseWidth;
            cardBaseHeight = ownershipChanged || cardBaseHeight <= 0.0 ? anchorHeight : cardBaseHeight;
            boolean opening = musicCardInteraction.wantsOpen();
            if (ownershipChanged || opening != previousCardOpening || cardReturnFromWidth <= 0.0) {
                cardReturnFromWidth = cardBaseWidth;
                cardReturnFromHeight = cardBaseHeight;
                cardReturnStart = Math.max(.001, Math.min(MusicCardMotion.JOIN_OPENNESS, cardTransition));
            }
            double returnBlend = opening ? 0.0
                    : 1.0 - DynamicIslandMath.smoothStep(cardTransition / cardReturnStart);
            cardBaseWidth = opening ? cardBaseWidth : DynamicIslandMath.lerp(cardReturnFromWidth, width, returnBlend);
            cardBaseHeight = opening ? cardBaseHeight : DynamicIslandMath.lerp(cardReturnFromHeight, height, returnBlend);
            previousCardOpening = opening;
            anchorFrame = MusicCardMotion.frame(cardTransition, centerX, 0.0, cardBaseWidth, cardBaseHeight,
                    compactWidth, compactHeight, cardFittedScale, musicCardInteraction.contentReveal());
            width = anchorFrame.anchorWidth();
            height = anchorFrame.anchorHeight();
        } else {
            cardBaseWidth = -1.0;
            cardBaseHeight = -1.0;
            cardReturnFromWidth = -1.0;
            cardReturnFromHeight = -1.0;
            previousCardOpening = false;
        }
        if (!preview) previousCardTransitionActive = cardTransitionActive;

        // Apple 风格的撞击：进入的表面以紧凑胶囊形态落下，先被挤压成
        // 水平宽、垂直扁的形状，然后回弹得更高/更窄，最后稳定下来。
        double absorbGlow = 0.0;
        if (playerAbsorbImpact && playerAbsorbStartedAt > 0L) {
            double phase = DynamicIslandMath.clamp01((now - playerAbsorbStartedAt)
                    / (double) PLAYER_ABSORB_FEEDBACK_MS);
            double wave = absorbWave(phase);
            width *= 1.0 + .28 * wave;
            height *= 1.0 - .36 * wave;
            absorbGlow = (1.0 - phase)
                    * (.28 + .72 * Math.min(1.0, Math.abs(wave) * 1.8));
        }

        double visibilityEase = DynamicIslandMath.smoothStep(alpha);
        // 回弹胶囊的静息位置比其他样式低 1px；入场滑动的起点跟着一起挪，否则收起高度会出现两套。
        // 玩家的「灵动岛位置」偏移直接加在静息 y 上：入场滑动、缩放锚点、实时轮廓换算
        // 全部从 topMargin 派生，一处相加全链路一致。
        double topMargin = (bounce ? BounceIslandMath.TOP : TOP_MARGIN)
                + DynamicIslandMath.clamp(HudConfig.dynamicIslandY, -20.0, 400.0);
        double y = topMargin - (height + 11.0) * (1.0 - visibilityEase);
        double anchorOriginY = y;
        if (anchorFrame != null) y += anchorFrame.anchorY();

        double entryScale = 1.0;
        if (!preview && animationStart > 0L && !suppressPlayerEntryAnimation) {
            double entranceDuration = DynamicIslandMath.clamp(HudConfig.dynamicIslandEntranceDuration,
                    150.0, 260.0);
            double entryProgress = DynamicIslandMath.clamp01((now - animationStart) / entranceDuration);
            double cubicOut = 1.0 - Math.pow(1.0 - entryProgress, 3.0);
            double restrainedOvershoot = Math.sin(entryProgress * Math.PI)
                    * (1.0 - entryProgress) * .025
                    * DynamicIslandMath.clamp(HudConfig.dynamicIslandOvershoot, 0.0, 2.0);
            entryScale = .965 + .035 * cubicOut + restrainedOvershoot;
        }

        api.getGLStateManager().pushMatrix();
        scaleAtPos(centerX, topMargin, configuredScale);
        scaleAtPos(centerX, y + height * .5, entryScale);

        double x = centerX - width * .5;
        boolean liquidGlass = style == DynamicIslandStyle.LIQUID_GLASS;
        boolean motionBlurEligible = !preview && !bounce && !liquidGlass && mixFrame == null
                && !noticeMode && !cardTransitionActive && !playerSurfaceTransition;
        if (cardTransitionActive != previousMusicCardOwnsSurface) {
            resetMotionBlur();
            musicCardMotionBlur.reset();
        }
        previousMusicCardOwnsSurface = cardTransitionActive;
        if (motionBlurEligible) drawMotionBlur(centerX, y, width, height, height * .5, alpha);
        else resetMotionBlur();
        if (bounce) {
            /*
             * 吸附到物理像素网格。其他样式的表面靠抗锯齿渐变收尾，缩放 2/3 档时那根半透明灰线
             * 被当成高光看不出来；这一款是纯色平面，同样的半像素就会显形成上下各一道边。
             * 半径必须在吸附之后才算：参考稿用的正是吸附后的高度。
             */
            double pixelScale = SkijaHost.guiScale();
            x = BounceIslandMath.alignFloor(x, pixelScale);
            y = BounceIslandMath.alignFloor(y, pixelScale);
            width = BounceIslandMath.alignSize(width, pixelScale);
            height = BounceIslandMath.alignUp(height, pixelScale);
        }
        double radius = bounce ? BounceIslandMath.pillRadius(height)
                : liquidGlass ? Math.min(16.0, height * .40) : height * .5;
        // 记录变换后的确切轮廓。播放器过渡必须从这个
        // 形状开始，而不是从与当前驻留灵动岛不匹配的紧凑胶囊开始。
        if (!preview) {
            liveAnchorLocalWidth = width;
            liveAnchorLocalHeight = height;
            double renderedScale = configuredScale * entryScale;
            double renderedCenterY = topMargin + configuredScale * (y + height * .5 - topMargin);
            LIVE_ANCHOR_BOUNDS[0] = centerX - width * renderedScale * .5;
            LIVE_ANCHOR_BOUNDS[1] = renderedCenterY - height * renderedScale * .5;
            LIVE_ANCHOR_BOUNDS[2] = width * renderedScale;
            LIVE_ANCHOR_BOUNDS[3] = height * renderedScale;
            LIVE_ANCHOR_BOUNDS[4] = radius * renderedScale;
            MUSIC_CARD_ANCHOR_BOUNDS = anchorFrame == null || anchorFrame.anchorAlpha() <= .05
                    ? null : LIVE_ANCHOR_BOUNDS.clone();
        }
        if (anchorFrame != null) {
            cardFrame = MusicCardMotion.frame(cardTransition, centerX, anchorOriginY, cardBaseWidth, cardBaseHeight,
                    compactWidth, compactHeight, cardFittedScale, musicCardInteraction.contentReveal());
            cardMotion = musicCardMotionBlur.advance(cardFrame, System.nanoTime());
            if (cardFrame.contentReveal() > .08 && alpha > .75f) {
                MusicCapsuleCard.tickTrackTransition((float) Math.min(40.0,
                        Math.max(0.0, RenderSystem.getFrameDeltaTime() * 10.0)));
            }
            CloudMusic.PlaybackSnapshot cardSnapshot = trackNotice ? MusicCapsuleCard.trackNoticeSnapshot()
                    : MusicCapsuleCard.currentSnapshot();
            MusicCardTransitionSurface.draw(cardFrame, alpha, cardSnapshot, cardMotion);
            musicCardDrawnSnapshot = cardFrame.alpha() > .01 ? cardSnapshot : null;
            double renderedScale = configuredScale * entryScale;
            double[] bounds = {
                    centerX + (cardFrame.x() - centerX) * renderedScale,
                    topMargin + (y + height * .5 - topMargin) * configuredScale
                            + (cardFrame.y() - y - height * .5) * renderedScale,
                    cardFrame.width() * renderedScale, cardFrame.height() * renderedScale,
                    cardFrame.radius() * renderedScale
            };
            MUSIC_CARD_BOUNDS = bounds;
            if (cardFrame.alpha() >= .5 && cardTransition > MusicCardMotion.JOIN_OPENNESS) {
                System.arraycopy(bounds, 0, LIVE_ANCHOR_BOUNDS, 0, bounds.length);
            }
            alpha *= (float) cardFrame.anchorAlpha();
            if (alpha <= .001f) {
                api.getGLStateManager().popMatrix();
                return;
            }
            progress = 0.0;
            success = 0f;
            if (!returningAmbient) contentFade = 0f;
            expansionValue *= 1.0 - islandHandoff;
            contentFade *= (float) (1.0 - islandHandoff);
        } else {
            MUSIC_CARD_BOUNDS = null;
            musicCardDrawnSnapshot = null;
            MUSIC_CARD_ANCHOR_BOUNDS = null;
        }
        if (cardTransitionActive && !musicCapsule && !bounce && islandHandoff > .999) {
            drawMusicCardAnchorSurface(x, y, width, height, radius, alpha, liquidGlass, now);
            api.getGLStateManager().popMatrix();
            return;
        }
        if (cardOwnsFrame && !musicCapsule && !returningAmbient) expansionValue = 0.0;
        if (returningIsland) {
            contentFade = hasAmbient ? contentFade : 0f;
            progress = 0.0;
            success = 0f;
        }
        int accentColor = NCMTheme.getAccentColor();
        int iconAccentColor = DynamicIslandMath.brightenAccent(accentColor, .30f);

        // 阴影保持在灵动岛自身的圆角轮廓上。之前的扩展、
        // 左移矩形看起来像一个独立的深色背景画布。
        float shadowAlpha = alpha * (liquidGlass ? .15f : .19f);
        if (!bounce) {
            roundedRect(x + 1.0, y + 1.8, width, height, radius,
                    hexColor(0f, 0f, 0f, shadowAlpha));
            roundedRect(x + 2.0, y + 3.0, width, height, radius,
                    hexColor(0f, 0f, 0f, shadowAlpha * .42f));
        }
        if (bounce && mixFrame == null) {
            if (cardTransitionActive && islandHandoff > .999) {
                roundedRect(x, y, width, height, radius, BounceIslandMath.argb(
                        (int) Math.round(BounceIslandMath.SURFACE_ALPHA * DynamicIslandMath.clamp01(alpha)),
                        bounceTint.redChannel(), bounceTint.greenChannel(), bounceTint.blueChannel()));
            } else {
                if (!cardTransitionActive) {
                    previousMusicCardOwnsSurface = false;
                    musicCardMotionBlur.reset();
                    resetMotionBlur();
                }
                drawBounceIsland(x, y, width, height, radius, alpha, expansionValue, contentFade,
                        now, preview, noticeMode, activeNoticeType, activeNoticeTitle, activeNoticeValue,
                        noticeProgress, ambient, ambientMix, layout, bounceStack, progress, success,
                        configuredScale, accentColor);
            }
            if (activityBorderActive) drawInferenceCapsuleMarquee(x, y, width, height, radius, alpha,
                    accentColor, now, downloadBorderActive ? progress : -1.0);
            if (completionBubble) drawCapsuleCompletionBubble(x, y, width, height, radius, alpha,
                    completionBubbleText, completionBubbleStartedAt, completionCapsuleRow, now);
            api.getGLStateManager().popMatrix();
            return;
        }
        if (mixFrame != null) {
            previousMusicCardOwnsSurface = false;
            musicCardMotionBlur.reset();
            resetMotionBlur();
            // 已处于岛本身的矩阵、圆角和锚点内：不另开画布，不画大图例/多色网格。
            GlassLook.paintFrost(GlassLook.Surface.ISLAND, x, y, width, height, radius, alpha * .64f);
            AutomixIslandRenderer.draw(SkijaGfx.canvas(), mixFrame, x, y, width, height, radius,
                    alpha * DynamicIslandMath.clamp01f(contentFade), accentColor);
            if (activityBorderActive) drawInferenceCapsuleMarquee(x, y, width, height, radius, alpha,
                    accentColor, now, downloadBorderActive ? progress : -1.0);
            if (completionBubble) drawCapsuleCompletionBubble(x, y, width, height, radius, alpha,
                    completionBubbleText, completionBubbleStartedAt, completionCapsuleRow, now);
            api.getGLStateManager().popMatrix();
            return;
        }
        if (cardTransitionActive && musicCapsule) {
            drawMusicCapsuleIsland(x, y, width, height, radius, alpha, (float) (1.0 - islandHandoff));
            if (completionBubble) drawCapsuleCompletionBubble(x, y, width, height, radius, alpha,
                    completionBubbleText, completionBubbleStartedAt, completionCapsuleRow, now);
            api.getGLStateManager().popMatrix();
            return;
        }
        if (musicCapsule && !capsuleRow) {
            drawMusicCapsuleSurface(x, y, width, height, radius, alpha);
        } else if (capsuleRow) {
            float rowAlpha = cardFrame == null ? 1f
                    : 1f - (float) DynamicIslandMath.smoothStep(cardTransition / .30);
            drawMusicCapsuleIsland(x, y, width, height, radius, alpha, rowAlpha);
        } else {
            boolean frosted = !liquidGlass && !musicCapsule
                    && GlassLook.paintFrost(GlassLook.Surface.ISLAND, x, y, width, height, radius, alpha);
        float thin = frosted ? GlassLook.tintedAlpha(GlassLook.Surface.ISLAND, 1.0f) : 1.0f;
        if (liquidGlass) {
            renderLiquidGlassSurface(x, y, width, height, radius, alpha, now);
        } else if (!musicCapsule) {
            // 经典胶囊与紧凑状态共用这一张底。
            roundedRect(x, y, width, height, radius, hexColor(.012f, .014f, .020f, alpha * .985f * thin));
            roundedOutline(x, y, width, height, radius, .65,
                    new Color(255, 255, 255, DynamicIslandMath.clamp255(alpha * 20f)));
        }
        // 边缘折射压在底色之上，与播放器面板、液态玻璃那一款同一个顺序：底色是「玻璃有多脏」，
        // 折射是「玻璃这个表面」，画反了最外圈那几像素会被自己刚铺的深色吃掉。
        // 「边缘光」滑块推到 0 时这里自己不出画（见 GlassLook.refractionStrength）。
        if (frosted) {
            GlassLook.paintEdge(GlassLook.Surface.ISLAND, x, y, width, height, radius, alpha);
        }
        if (!musicCapsule && absorbGlow > .01) {
            roundedOutline(x - 1.0, y - 1.0, width + 2.0, height + 2.0, radius + .7, .82,
                    new Color(255, 255, 255, DynamicIslandMath.clamp255((float) (alpha * absorbGlow * 128f))));
            roundedOutline(x - 1.8, y - 1.8, width + 3.6, height + 3.6, radius + 1.2, .62,
                    new Color(RenderSystem.reAlpha(accentColor, (float) (alpha * absorbGlow * .52f)), true));
        }
        if (!liquidGlass && !musicCapsule) {
            // 顶部镜面高光随玻璃浓度压薄。
            roundedRect(x + radius * .70, y + .8, Math.max(2.0, width - radius * 1.40), .75, .38,
                    hexColor(1f, 1f, 1f, alpha * .055f * thin));
        }
        // 图标托盘与布局计算共用内缩口径，内容区留出固定呼吸位。
        double iconInset = liquidGlass ? 24.0 : 23.0;
        double iconExpandedX = centerX - (cardOwnsFrame ? width : expandedWidth) * .5 + iconInset;
        double iconX = DynamicIslandMath.lerp(centerX, iconExpandedX, expansionValue);
        double iconY = y + height * .5;
        double pulse = .5 + .5 * Math.sin(now / 260.0);

        api.getGLStateManager().pushMatrix();
        double iconPlateBase = liquidGlass ? 19.6 : 18.4;
        // 托盘随岛高收缩，避免在收起过程中越出胶囊的圆肩。
        double iconFit = DynamicIslandMath.clamp((height - 3.6) / iconPlateBase, .40, 1.0);
        double iconReveal = DynamicIslandMath.smoothStep(
                DynamicIslandMath.clamp01((expansionValue - .10) / .68));
        double iconPlate = iconPlateBase * iconFit;
        float plateAlpha = alpha * (.82f + (float) pulse * .12f) * (.34f + .66f * (float) iconReveal);
        float glyphAlpha = alpha * .98f * (.40f + .60f * (float) iconReveal)
                * (cardOwnsFrame ? (float) (1.0 - islandHandoff) : 1f)
                * (idleWithoutContent || cardOwnsFrame && !returningAmbient || returningIsland && !hasAmbient ? 0f : 1f);
        // 半透明封面下不刷强调色，避免封面被主题色染色。
        float artworkMix = ambientMix > .005f && hasAmbientArtwork() ? ambientMix : 0f;
        scaleAtPos(iconX, iconY, 1.06 * iconFit);
        roundedRect(iconX - iconPlate * .5, iconY - iconPlate * .5, iconPlate, iconPlate,
                iconPlate * .5, hexColor(.020f, .026f, .038f, plateAlpha * (.60f + .36f * artworkMix)));
        roundedRect(iconX - iconPlate * .5, iconY - iconPlate * .5, iconPlate, iconPlate,
                iconPlate * .5, RenderSystem.reAlpha(liquidGlass ? GLASS_TINT : accentColor,
                        plateAlpha * (liquidGlass ? .20f : .26f) * (1f - artworkMix)));
        if (liquidGlass) {
            // 磨砂托盘：上亮下暗的一层反光，让图标像压在玻璃里而不是贴在表面。
            roundedRectGradientVertical(iconX - iconPlate * .5, iconY - iconPlate * .5,
                    iconPlate, iconPlate, iconPlate * .5,
                    new Color(255, 255, 255, DynamicIslandMath.clamp255(plateAlpha * 34f)),
                    new Color(255, 255, 255, 0));
        }
        roundedOutline(iconX - iconPlate * .5, iconY - iconPlate * .5, iconPlate, iconPlate,
                iconPlate * .5, .95, new Color(255, 255, 255, DynamicIslandMath.clamp255(plateAlpha * 92f)));
        if (ambientMix < .995f) {
            float eventGlyphAlpha = glyphAlpha * (1f - ambientMix);
            if (noticeMode) {
                renderNoticeIcon(activeNoticeType, activeNoticeValue, iconX, iconY,
                        eventGlyphAlpha, iconAccentColor, now);
            } else {
                if (!completionBubble) {
                    renderSpinner(iconX, iconY, eventGlyphAlpha * (1f - success), iconAccentColor, now, preview);
                    renderSuccess(iconX, iconY, alpha * success * (1f - ambientMix));
                }
            }
        }
        if (ambientMix > .005f) {
            // 封面沿用圆托盘的尺寸与圆角，铺满后四周不留月牙形空隙。
            renderAmbientIcon(iconX, iconY, iconPlate, iconPlate * .5, 1.0,
                    glyphAlpha * ambientMix, iconAccentColor);
        }
        api.getGLStateManager().popMatrix();

        // 常驻内容出现时通知/下载文案整体让位：两者共用同一块排版区域，靠交叉淡入切换而不是叠字。
        float eventFade = 1f - ambientMix;
        float textAlpha = alpha * contentAlpha * (float) expansionValue
                * DynamicIslandMath.clamp01f(contentFade) * eventFade;
        if (completionBubble) textAlpha = 0f;
        boolean styleNoticeCopy = musicCapsule && noticeMode && cardFrame == null;
        if (styleNoticeCopy) {
            double widthSpan = Math.max(1.0, layout.targetWidth - compactWidth);
            double noticeWidthProgress = DynamicIslandMath.clamp01((width - compactWidth) / widthSpan);
            textAlpha *= (float) DynamicIslandMath.smoothStep((noticeWidthProgress - .42) / .52);
        }
        if (preview) textAlpha = alpha * (float) expansionValue * eventFade;
        boolean staticVolumeCopy = noticeMode && activeNoticeType == IslandNoticeType.VOLUME;
        boolean transcodeCopy = noticeMode && activeNoticeType == IslandNoticeType.TRANSCODING;
        // 只有"正在进行的无缝重叠"这一条走扫光标题：它描述的是一件此刻仍在发生的事，
        // 其余通知都是已经发生完的结果，扫光在它们身上只会变成噪音。
        boolean shimmerTitle = noticeMode && (activeNoticeType == IslandNoticeType.AUTOMIX_BLENDING
                || activeNoticeType == IslandNoticeType.MODEL_INFERENCE);
        // 音量经常通过按键重复更新。其标签有意跳过灵动岛内容进入
        // 和文字过渡动画，同时进度条仍进行插值。
        float renderedTextAlpha = staticVolumeCopy
                ? (expansionValue >= .74 ? alpha * eventFade : 0f) : textAlpha;
        if (renderedTextAlpha > .01f) {
            double textLeft = x + DynamicIslandLayoutCalculator.styleContentLeft(style);
            double textRight = x + width - DynamicIslandLayoutCalculator.styleRightPadding(style);
            double textCenter = (textLeft + textRight) * .5;
            double copySlide = (1.0 - DynamicIslandMath.clamp01(contentFade)) * CONTENT_CHANGE_SLIDE;
            double baseTitleY = y + 5.4;
            double baseValueY = y + 16.6;
            double titleY = baseTitleY + copySlide;
            double valueY = baseValueY + copySlide;

            if (noticeMode) {
                // 可用宽度必须严格跟随本帧动画出来的 width：这里原来有一个 28 像素的下限，
                // 于是灵动岛在展开/收起过程中比 28 像素还窄时，文案照 28 像素排版就会溢出胶囊。
                double availableWidth = Math.max(0.0, textRight - textLeft);
                if (staticVolumeCopy) {
                    double staticTitleY = baseTitleY;
                    double staticValueY = baseValueY;
                    drawCenteredIslandText(FontManager.pf12, layout.title, textCenter, staticTitleY,
                            availableWidth, layout.textScale,
                            hexColor(.62f, .67f, .76f, renderedTextAlpha * .96f));
                    drawCenteredIslandText(FontManager.pf14bold, layout.value, textCenter, staticValueY,
                            availableWidth, layout.textScale, hexColor(1f, 1f, 1f, renderedTextAlpha));
                } else if (transcodeCopy) {
                    double transcodeTitleY = baseTitleY;
                    double transcodeValueY = baseValueY;
                    drawCenteredIslandText(FontManager.pf12, layout.title, textCenter, transcodeTitleY,
                            availableWidth, layout.textScale,
                            hexColor(.62f, .67f, .76f, renderedTextAlpha * .96f));
                    drawCenteredIslandText(FontManager.pf12, layout.value, textCenter, transcodeValueY,
                            availableWidth, layout.textScale,
                            hexColor(1f, 1f, 1f, renderedTextAlpha));
                    String percent = Math.max(0, Math.min(100,
                            (int) Math.round(DynamicIslandMath.clamp01(noticeProgress) * 100.0))) + "%";
                    drawCenteredIslandText(FontManager.pf12bold, percent, textRight - 10.0, transcodeValueY,
                            20.0, layout.textScale,
                            hexColor(.58f, .90f, 1f, renderedTextAlpha));                } else {
                    double copyBlend = DynamicIslandMath.clamp01(noticeCopyBlend);
                    double noticeSlide = CONTENT_CHANGE_SLIDE * .62;

                    // 通知刷新保持灵动岛本身稳定。之前的文字向上退出，
                    // 同时最新值从下方进入；快速更新由 renderInternal 合并，
                    // 而不是不断重启此过渡。
                    if (copyBlend < .995) {
                        float outgoingAlpha = textAlpha * (float) (1.0 - copyBlend);
                        if (outgoingAlpha > .01f) {
                            drawCenteredIslandText(FontManager.pf12,
                                    safeNoticeValue(outgoingNoticeTitle, layout.title), textCenter,
                                    titleY - noticeSlide * copyBlend, availableWidth, layout.textScale,
                                    hexColor(.62f, .67f, .76f, outgoingAlpha * .96f));
                            drawCenteredIslandText(FontManager.pf14bold,
                                    safeNoticeValue(outgoingNoticeValue, layout.value), textCenter,
                                    valueY - noticeSlide * copyBlend, availableWidth, layout.textScale,
                                    hexColor(1f, 1f, 1f, outgoingAlpha));
                        }
                    }

                    float incomingAlpha = textAlpha * (float) copyBlend;
                    if (incomingAlpha > .01f) {
                        if (shimmerTitle) {
                            drawShimmerIslandText(FontManager.pf12, layout.title, textCenter,
                                    titleY + noticeSlide * (1.0 - copyBlend), availableWidth,
                                    layout.textScale, incomingAlpha * .96f, now);
                        } else {
                            drawCenteredIslandText(FontManager.pf12, layout.title, textCenter,
                                    titleY + noticeSlide * (1.0 - copyBlend), availableWidth, layout.textScale,
                                    hexColor(.62f, .67f, .76f, incomingAlpha * .96f));
                        }
                        drawCenteredIslandText(FontManager.pf14bold, layout.value, textCenter,
                                valueY + noticeSlide * (1.0 - copyBlend), availableWidth, layout.textScale,
                                hexColor(1f, 1f, 1f, incomingAlpha));
                    }
                }

                if (staticVolumeCopy || transcodeCopy) {
                    double progressX = textLeft;
                    double progressWidth = Math.max(28.0, textRight - textLeft);
                    double progressHeight = 2.8;
                    double progressY = y + height - 6.0;
                    double progressRadius = progressHeight * .5;
                    roundedRect(progressX, progressY, progressWidth, progressHeight, progressRadius,
                            hexColor(1f, 1f, 1f, renderedTextAlpha * .12f));
                    double fillWidth = progressWidth * DynamicIslandMath.clamp01(noticeProgress);
                    if (fillWidth > .10) {
                        roundedRectGradientHorizontal(progressX, progressY, fillWidth, progressHeight,
                                Math.min(progressRadius, fillWidth * .5),
                                DynamicIslandMath.colorWithAlpha(accentColor, renderedTextAlpha * .98f),
                                new Color(103, 216, 255, DynamicIslandMath.clamp255(renderedTextAlpha * 255f)));
                    }
                }
            } else {
                float loadingAlpha = textAlpha * (1f - success);
                float completeAlpha = textAlpha * success;
                String status = success > .52f ? "完成" : Math.max(0, Math.min(100,
                        (int) Math.round(DynamicIslandMath.clamp01(progress) * 100.0))) + "%";
                double totalTextWidth = Math.max(0.0, textRight - textLeft);
                // 百分比槽与正文同样不能有固定下限：动画中途的窄胶囊里，写死的 25/28 像素
                // 会让"加载中 / 100%"整块横着穿出灵动岛边缘。
                double statusWidth = Math.min(totalTextWidth, Math.max(25.0,
                        FontManager.pf12bold.getStringWidthD(status) * layout.textScale + 10.0));
                double statusX = textRight - statusWidth;
                double bodyRight = statusX - 5.0;
                double bodyCenter = (textLeft + bodyRight) * .5;
                double bodyWidth = Math.max(0.0, bodyRight - textLeft);

                drawCenteredIslandText(FontManager.pf14bold, layout.title, bodyCenter, titleY,
                        bodyWidth, layout.textScale, hexColor(1f, 1f, 1f, loadingAlpha));
                drawCenteredIslandText(FontManager.pf14bold, "下载完成", bodyCenter, titleY,
                        bodyWidth, layout.textScale, hexColor(1f, 1f, 1f, completeAlpha));
                drawCenteredIslandText(FontManager.pf12, layout.value, bodyCenter, valueY,
                        bodyWidth, layout.textScale, hexColor(.65f, .69f, .78f, loadingAlpha * .94f));
                drawCenteredIslandText(FontManager.pf12, "文件已就绪", bodyCenter, valueY,
                        bodyWidth, layout.textScale, hexColor(.59f, .90f, .67f, completeAlpha));

                int statusColor = success > .52f
                        ? hexColor(.58f, 1f, .68f, textAlpha)
                        : hexColor(.82f, .85f, .91f, textAlpha);
                roundedRect(statusX, y + 4.2, statusWidth, 12.5, 6.25,
                        success > .52f ? hexColor(.18f, .55f, .32f, textAlpha * .34f)
                                : hexColor(1f, 1f, 1f, textAlpha * .10f));
                drawCenteredIslandText(FontManager.pf12bold, status, statusX + statusWidth * .5,
                        y + 6.1, statusWidth - 4.0, layout.textScale, statusColor);

                double progressX = x + 9.0;
                double progressY = y + height - 3.7;
                double progressWidth = width - 18.0;
                double progressHeight = Math.min(height * .22,
                        DynamicIslandMath.clamp(HudConfig.dynamicIslandProgressHeight, .75, 4.0));
                double progressRadius = progressHeight * .5;
                roundedRect(progressX, progressY, progressWidth, progressHeight, progressRadius,
                        hexColor(1f, 1f, 1f, textAlpha * .10f));
                double fillWidth = progressWidth * DynamicIslandMath.clamp01(progress);
                if (fillWidth > .10) {
                    Color startColor = DynamicIslandMath.colorWithAlpha(accentColor, textAlpha * .94f);
                    Color endColor = new Color(103, 216, 255, DynamicIslandMath.clamp255(textAlpha * 255f));
                    roundedRectGradientHorizontal(progressX, progressY, fillWidth, progressHeight,
                            Math.min(progressRadius, fillWidth * .5), startColor, endColor);
                }
            }
        }

        if (ambientMix > .005f) {
            float ambientAlpha = preview
                    ? alpha * (float) expansionValue * ambientMix
                    : alpha * contentAlpha * (float) expansionValue * ambientMix;
            if (ambientAlpha > .01f) {
                drawAmbientRow(ambient, x, y, width, height, style, ambientAlpha, ambientScale, accentColor);
            }
        }
        }
        if (activityBorderActive) drawInferenceCapsuleMarquee(x, y, width, height, radius, alpha,
                accentColor, now, downloadBorderActive ? progress : -1.0);
        if (completionBubble) drawCapsuleCompletionBubble(x, y, width, height, radius, alpha,
                completionBubbleText, completionBubbleStartedAt, completionCapsuleRow, now);
        api.getGLStateManager().popMatrix();
        SkijaGfx.setColor(1f, 1f, 1f, 1f);
    }

    private double tickIslandExpansion(boolean visibleTarget, double target, double frameDelta) {
        double safeTarget = DynamicIslandMath.clamp01(target);
        long nowNanos = System.nanoTime();
        if (!expansionClockInitialized) {
            expansionClockInitialized = true;
            expansionFrom = expansion;
            expansionClockTarget = expansion;
            expansionElapsedMillis = 0.0;
            expansionDurationMillis = 180.0;
            expansionLastNanos = nowNanos;
        }
        if (Math.abs(safeTarget - expansionClockTarget) > .001) {
            expansionFrom = expansion;
            expansionClockTarget = safeTarget;
            expansionElapsedMillis = 0.0;
            expansionLastNanos = nowNanos;
            double configured = visibleTarget ? HudConfig.dynamicIslandExpandSpeed
                    : HudConfig.dynamicIslandCollapseSpeed;
            double baseDuration = visibleTarget ? 165.0 : 180.0;
            expansionDurationMillis = baseDuration / DynamicIslandMath.clamp(configured, .40, 2.50);
            if (Math.abs(expansionClockTarget - expansionFrom) <= .001) {
                expansion = expansionClockTarget;
                return expansion;
            }
        }
        double dt = expansionLastNanos <= 0L ? 0.0
                : Math.max(0.0, Math.min(40.0, (nowNanos - expansionLastNanos) / 1_000_000.0));
        expansionLastNanos = nowNanos;
        expansionElapsedMillis = Math.min(expansionDurationMillis, expansionElapsedMillis + dt);
        double progress = expansionDurationMillis <= 0.0 ? 1.0 : expansionElapsedMillis / expansionDurationMillis;
        double eased = com.muoniumplayer.core.screens.ncm.TransitionCurveCache.MUSIC_CARD.at(progress);
        expansion = DynamicIslandMath.lerp(expansionFrom, expansionClockTarget, eased);
        if (expansionElapsedMillis >= expansionDurationMillis) expansion = expansionClockTarget;
        return expansion;
    }

    private double animateExpandedDimension(double current, double target) {
        if (current <= 0.0) return target;
        if (Math.abs(target - current) < .15) return target;
        return Interpolations.interpolate(current, target, target > current ? .40f : .38f);
    }

    private void resetExpansionClock() {
        cardBaseWidth = -1.0;
        cardBaseHeight = -1.0;
        cardReturnFromWidth = -1.0;
        cardReturnFromHeight = -1.0;
        previousCardOpening = false;
        musicCardReturning = false;
        musicCardReturnAmbient = IslandAmbientStatus.EMPTY;
        musicCardMotionBlur.reset();
        previousMusicCardOwnsSurface = false;
        previousCardTransitionActive = false;
        MUSIC_CARD_ANCHOR_BOUNDS = null;
        liveAnchorLocalWidth = -1.0;
        liveAnchorLocalHeight = -1.0;
        visibilityClockInitialized = false;
        visibilityClockTarget = false;
        visibilityStart = 0f;
        visibilityElapsedMillis = 0.0;
        visibilityDurationMillis = 0.0;
        visibilityLastNanos = 0L;
        expansionClockInitialized = false;
        expansionFrom = 0.0;
        expansionClockTarget = 0.0;
        expansionElapsedMillis = 0.0;
        expansionDurationMillis = 0.0;
        expansionLastNanos = 0L;
    }

    private void resetMotionBlur() {
        previousMotionX = Double.NaN;
        previousMotionY = Double.NaN;
        previousMotionWidth = Double.NaN;
        previousMotionHeight = Double.NaN;
        motionVelocityX = 0.0;
        motionVelocityY = 0.0;
        previousMotionNanos = 0L;
        motionBlurStrength = 0.0;
        previousMotionAlpha = 0f;
    }

    private IslandLayout createIslandLayout(DynamicIslandStyle style, boolean noticeMode,
                                            IslandNoticeType noticeType, String noticeTitleValue,
                                            String noticeBodyValue, String speedValue, boolean preview,
                                            double configuredScale, double screenWidth) {
        DynamicIslandLayoutCalculator.LayoutData layout = DynamicIslandLayoutCalculator.calculate(
                style, noticeMode, noticeMode && noticeType == IslandNoticeType.VOLUME,
                noticeTitleValue, noticeBodyValue, speedValue, preview, configuredScale, screenWidth,
                COMPACT_WIDTH, MIN_AUTO_TEXT_SCALE, MAX_TEXT_SCALE);
        return new IslandLayout(layout.title, layout.value, layout.textScale,
                layout.targetWidth, layout.targetHeight);
    }
    private void drawMotionBlur(double centerX, double y, double width, double height, double radius,
                                float alpha) {
        long nowNanos = System.nanoTime();
        double dt = .016;
        double dx = 0.0;
        double dy = 0.0;
        double dw = 0.0;
        double dh = 0.0;
        if (previousMotionNanos > 0L && Double.isFinite(previousMotionX)
                && Double.isFinite(previousMotionY) && Double.isFinite(previousMotionWidth)
                && Double.isFinite(previousMotionHeight)) {
            dt = Math.max(.001, Math.min(.05, (nowNanos - previousMotionNanos) / 1_000_000_000.0));
            double previousCenterX = previousMotionX;
            double previousCenterY = previousMotionY + previousMotionHeight * .5;
            double newCenterY = y + height * .5;
            dx = centerX - previousCenterX;
            dy = newCenterY - previousCenterY;
            dw = width - previousMotionWidth;
            dh = height - previousMotionHeight;
            double speed = (Math.abs(dx) + Math.abs(dy) + Math.abs(dw) * .5 + Math.abs(dh) * .5) / dt;
            double target = DynamicIslandMath.clamp01((speed - 180.0) / 1800.0);
            double decayMillis = 72.0 / DynamicIslandMath.clamp(HudConfig.dynamicIslandExpandSpeed, .40, 2.50);
            double decay = 1.0 - Math.exp(-dt * 1000.0 / decayMillis);
            motionBlurStrength += (target - motionBlurStrength) * decay;
        }
        if (previousMotionNanos == 0L || dt >= .049) previousMotionAlpha = alpha;
        else previousMotionAlpha += (alpha - previousMotionAlpha) * (float) (1.0 - Math.exp(-dt * 1000.0 / 48.0));
        previousMotionX = centerX;
        previousMotionY = y;
        previousMotionWidth = width;
        previousMotionHeight = height;
        previousMotionNanos = nowNanos;

        if (motionBlurStrength <= .004 || alpha <= .01f) return;
        float opacityBase = (float) Math.min(previousMotionAlpha * .12f, motionBlurStrength * .12f);
        if (opacityBase <= .001f) return;
        double distance = Math.min(5.0, 1.0 + motionBlurStrength * 4.0);
        for (int layer = 3; layer >= 1; layer--) {
            double trail = layer / 3.0;
            double offsetX = -dx * .45 * layer;
            double offsetY = -dy * .45 * layer;
            double expand = Math.min(3.0, Math.abs(dw) * .28 + Math.abs(dh) * .18) * layer;
            float opacity = opacityBase * (float) (trail * trail);
            roundedRect(centerX - width * .5 + offsetX - expand,
                    y + offsetY - expand * .35 - Math.copySign(distance, dy == 0.0 ? 1.0 : dy) * trail,
                    width + expand * 2.0, height + expand * .7,
                    Math.min(radius, height * .5) + expand * .5,
                    hexColor(.72f, .84f, 1f, opacity));
        }
    }

    private void drawCenteredIslandText(CFontRenderer font, String text, double centerX, double y,
                                         double maxWidth, double textScale, int color) {
        if (font == null || text == null || text.isEmpty()) return;
        // 宽度不足就整段不画。原来这里对可用宽度取 max(18, ...)：收起动画的最后几帧里胶囊只有
        // 十几像素宽，按 18 像素裁剪出来的文字必然越过圆角边缘，看起来就是"文字溢出"。
        if (maxWidth <= 1.0) return;
        double safeScale = DynamicIslandMath.clamp(textScale, MIN_AUTO_TEXT_SCALE, MAX_TEXT_SCALE);
        String trimmed = trimIslandText(font, text, maxWidth / safeScale);
        if (trimmed.isEmpty()) return;
        double textWidth = font.getStringWidthD(trimmed) * safeScale;
        font.drawString(trimmed, centerX - textWidth * .5, y, safeScale, color);
    }

    /**
     * 扫光文案：一条窄亮带沿着文字反复掠过。只给"智能过渡中"这类正在进行的状态用。
     *
     * <p>刻意不用 scissor 裁一条亮带出来：灵动岛整层已经在一个缩放矩阵里，而 glScissor 吃的是窗口
     * 像素坐标，裁剪框会跟着缩放与位置漂移。这里改成逐字符按"字符中心到亮带中心的距离"插值颜色，
     * 视觉结果一样，且不引入任何 GL 状态切换，也不新增绘制批次。</p>
     *
     * <p>行宽用逐字符宽度的累加值而不是整串宽度：两者在本字体下应当相等，但排版一旦不等，
     * 用累加值算出来的亮带位置仍然精确落在字符上。</p>
     */
    private void drawShimmerIslandText(CFontRenderer font, String text, double centerX, double y,
                                        double maxWidth, double textScale, float alpha, long now) {
        if (font == null || text == null || text.isEmpty() || alpha <= .01f) return;
        if (maxWidth <= 1.0) return;
        double safeScale = DynamicIslandMath.clamp(textScale, MIN_AUTO_TEXT_SCALE, MAX_TEXT_SCALE);
        String trimmed = trimIslandText(font, text, maxWidth / safeScale);
        if (trimmed.isEmpty()) return;

        int length = trimmed.length();
        double[] widths = new double[length];
        double total = 0.0;
        for (int index = 0; index < length; index++) {
            widths[index] = font.getStringWidthD(trimmed.substring(index, index + 1)) * safeScale;
            total += widths[index];
        }
        if (total <= .01) return;

        // 亮带两端各留一个带宽的行程：每一轮都有"还没进来"和"已经出去"的间隙，读起来是掠过的光
        // 而不是整行一起呼吸。
        double sweep = ((now % SHIMMER_PERIOD_MILLIS) / (double) SHIMMER_PERIOD_MILLIS)
                * (1.0 + SHIMMER_BAND * 2.0) - SHIMMER_BAND;
        double cursor = centerX - total * .5;
        double travelled = 0.0;
        for (int index = 0; index < length; index++) {
            double ratio = (travelled + widths[index] * .5) / total;
            double distance = Math.abs(ratio - sweep) / SHIMMER_BAND;
            double lit = distance >= 1.0 ? 0.0 : DynamicIslandMath.smoothStep(1.0 - distance);
            font.drawString(trimmed.substring(index, index + 1), cursor, y, safeScale,
                    hexColor((float) (.62 + .38 * lit), (float) (.67 + .33 * lit),
                            (float) (.76 + .24 * lit),
                            alpha * (float) (SHIMMER_BASE_ALPHA + (1.0 - SHIMMER_BASE_ALPHA) * lit)));
            cursor += widths[index];
            travelled += widths[index];
        }
    }

    private String trimIslandText(CFontRenderer font, String text, double maxRawWidth) {
        if (maxRawWidth <= 0.0) return "";
        if (font.getStringWidthD(text) <= maxRawWidth) return text;
        final String ellipsis = "...";
        if (font.getStringWidthD(ellipsis) > maxRawWidth) return "";
        int end = text.length();
        while (end > 0) {
            String candidate = text.substring(0, end) + ellipsis;
            if (font.getStringWidthD(candidate) <= maxRawWidth) return candidate;
            end--;
        }
        return ellipsis;
    }

    private static final class IslandLayout {
        final String title;
        final String value;
        final double textScale;
        final double targetWidth;
        final double targetHeight;

        IslandLayout(String title, String value, double textScale, double targetWidth, double targetHeight) {
            this.title = title;
            this.value = value;
            this.textScale = textScale;
            this.targetWidth = targetWidth;
            this.targetHeight = targetHeight;
        }
    }

    /**
     * 常驻状态栏的排版常量。数值槽按等位数模板预留宽度（见 {@link IslandAmbientStatus.Chip#widthTemplate}），
     * 所以帧率从 99 跳到 100、延迟从 8ms 跳到 120ms 时，灵动岛的宽度都不会跟着抖动。
     */
    private static final double AMBIENT_ICON_WIDTH = 9.0;
    private static final double AMBIENT_ICON_GAP = 3.2;
    private static final double AMBIENT_UNIT_GAP = 2.2;
    private static final double AMBIENT_CHIP_SPACING = 11.0;

    /** 未缩放的常驻内容总宽。缩放由调用方乘上去，便于先量宽再决定字号。 */
    private double measureAmbientRow(IslandAmbientStatus.Snapshot ambient) {
        if (ambient == null || ambient.isEmpty()) return 0.0;
        double total = 0.0;
        for (int index = 0; index < ambient.chips.length; index++) {
            if (index > 0) total += AMBIENT_CHIP_SPACING;
            total += measureAmbientChip(ambient.chips[index]);
        }
        return total;
    }

    private double measureAmbientChip(IslandAmbientStatus.Chip chip) {
        // 正在播放页的条目不画小图标（左侧封面已经是它的图标），因此也不预留图标位，
        // 否则歌名会整体右移一段，看起来像没对齐。
        double width = (chip.showIcon ? AMBIENT_ICON_WIDTH + AMBIENT_ICON_GAP : 0.0)
                + FontManager.pf14bold.getStringWidthD(chip.widthTemplate);
        if (!chip.unit.isEmpty()) {
            width += AMBIENT_UNIT_GAP + FontManager.pf12.getStringWidthD(chip.unit);
        }
        return width;
    }

    /** 单行常驻状态栏：整体在文本区里居中，但绝不越过左侧图标托盘。 */
    private void drawAmbientRow(IslandAmbientStatus.Snapshot ambient, double x, double y,
                                double width, double height, DynamicIslandStyle style,
                                float alpha, double scale, int accentColor) {
        if (ambient == null || ambient.isEmpty()) return;
        double centerY = y + height * .5;
        // 与通知文案共用同一条左边界。这里原来自带一份 34.0 的常数，而默认样式托盘的视觉右缘
        // 已经在 32.75，于是歌名距封面只剩 1.25px；CARD/LIQUID_GLASS 的托盘更宽，实际是负数
        // ——歌名直接压在封面上，正是"图标和右侧栏目间距过小"看到的样子。
        double gutter = DynamicIslandLayoutCalculator.styleContentLeft(style);
        // 常驻行同样必须跟着本帧的 width 收缩。展开/收起动画中途 width 远小于目标宽度，
        // 而行宽是按目标宽度算好的，不做这一步就会整行横着穿出胶囊右侧。
        double available = width - gutter - DynamicIslandLayoutCalculator.styleRightPadding(style);
        double rawRowWidth = measureAmbientRow(ambient);
        if (rawRowWidth <= 0.0 || available <= 1.0) return;
        scale = Math.min(scale, available / rawRowWidth);
        if (scale <= .04) return;
        double rowWidth = rawRowWidth * scale;
        double left = Math.max(x + gutter, x + width * .5 - rowWidth * .5);
        double cursor = left;
        for (int index = 0; index < ambient.chips.length; index++) {
            if (index > 0) {
                roundedRect(cursor + (AMBIENT_CHIP_SPACING * .5 - .5) * scale, centerY - 4.4 * scale,
                        1.0 * scale, 8.8 * scale, .5 * scale, hexColor(1f, 1f, 1f, alpha * .13f));
                cursor += AMBIENT_CHIP_SPACING * scale;
            }
            cursor += drawAmbientChip(ambient.chips[index], cursor, centerY, alpha, scale, accentColor);
        }
    }

    /** 画一个条目并返回它实际占掉的宽度，让调用方不必重复一遍宽度公式。 */
    private double drawAmbientChip(IslandAmbientStatus.Chip chip, double left, double centerY,
                                   float alpha, double scale, int accentColor) {
        if (chip.showIcon) {
            drawAmbientChipIcon(chip.kind, left + AMBIENT_ICON_WIDTH * .5 * scale, centerY,
                    alpha, accentColor, scale);
        }
        double cursor = left + (chip.showIcon ? (AMBIENT_ICON_WIDTH + AMBIENT_ICON_GAP) * scale : 0.0);
        double slot = FontManager.pf14bold.getStringWidthD(chip.widthTemplate) * scale;
        double valueWidth = FontManager.pf14bold.getStringWidthD(chip.value) * scale;
        // 数值在预留槽内居中：位数变化只影响槽内位置，不影响后面条目的起点。
        FontManager.pf14bold.drawString(chip.value, cursor + (slot - valueWidth) * .5,
                centerY - FontManager.pf14bold.getFontHeight() * scale * .5, scale,
                ambientHealthColor(chip.health, alpha));
        cursor += slot;
        if (!chip.unit.isEmpty()) {
            cursor += AMBIENT_UNIT_GAP * scale;
            FontManager.pf12.drawString(chip.unit, cursor,
                    centerY - FontManager.pf12.getFontHeight() * scale * .5, scale,
                    hexColor(.60f, .66f, .76f, alpha * .92f));
            cursor += FontManager.pf12.getStringWidthD(chip.unit) * scale;
        }
        return cursor - left;
    }

    /** 健康度配色。中性一律接近纯白，避免让"正常"看起来像出错。 */
    private int ambientHealthColor(IslandAmbientStatus.Health health, float alpha) {
        if (health == IslandAmbientStatus.Health.GOOD) return hexColor(.60f, 1f, .74f, alpha);
        if (health == IslandAmbientStatus.Health.WARN) return hexColor(1f, .83f, .47f, alpha);
        if (health == IslandAmbientStatus.Health.BAD) return hexColor(1f, .53f, .55f, alpha);
        return hexColor(.96f, .97f, 1f, alpha);
    }

    /**
     * 条目小图标改用图标字体：帧率=仪表盘（island-volume-icons 的 U+E902）、延迟=链条
     * （island-status-icons 的 U+E903）、时间=时钟（island-status-icons 的 U+E901）。
     *
     * <p>帧率原来用的是心跳（island-status-icons U+E904），换成仪表盘：心跳的两段曲线在 9 像素
     * 见方里几乎认不出，而仪表盘的指针指向能直接读出"快/慢"的含义。两枚图标分属不同字体，
     * 所以取字形时必须把字体一起换掉。</p>
     *
     * <p>原来这三枚是用圆角矩形手工拼的。在 9 像素见方、还要再乘常驻缩放的尺寸下，手绘笔画的
     * 粗细和间距都落不到整数像素上，看起来发糊也认不出形状；字形是按字号栅格化过的，同样尺寸下
     * 边缘干净得多。</p>
     */
    private void drawAmbientChipIcon(IslandAmbientStatus.Kind kind, double centerX, double centerY,
                                     float alpha, int accentColor, double scale) {
        if (alpha <= .01f) return;
        boolean fps = kind == IslandAmbientStatus.Kind.FPS;
        // 延迟(U+E903)与时间(U+E901)刻意不跟主题强调色走：这两枚是读数，需要在任何强调色下都
        // 保持同样的可读性；帧率（仪表盘）保留强调色，让常驻行仍然有一处跟随主题的着色点。
        // 另外强调色本身可能就是低对比度的深色（部分浅色主题的强调色偏暗），染到深色胶囊上会糊成一团。
        int color = fps
                ? RenderSystem.reAlpha(accentColor, alpha * .98f)
                : hexColor(.86f, .90f, .98f, alpha * .94f);
        if (fps) {
            drawVolumeFontIcon(FontManager.islandVolumeIcon18, FontManager.ISLAND_VOLUME_ICON_SMALL,
                    IslandVolumeIcons.GAUGE, centerX, centerY, AMBIENT_ICON_WIDTH * scale, color);
            return;
        }
        drawAmbientGlyph(kind == IslandAmbientStatus.Kind.PING ? IslandStatusIcons.LINK
                : IslandStatusIcons.CLOCK, centerX, centerY, AMBIENT_ICON_WIDTH * scale, color);
    }

    /** 把一枚图标字形等比缩放到目标边长并居中绘制（默认是 island-status-icons 那一份）。 */
    private void drawAmbientGlyph(String glyph, double centerX, double centerY, double targetSize,
                                  int color) {
        drawAmbientGlyph(FontManager.islandIcon12, glyph, centerX, centerY, targetSize, color);
    }

    /**
     * 同上，但点名用哪份字体。
     *
     * <p>fontello 那几批导出各自从私用区重新编号，同一个码位在不同字体里是完全不同的字形，
     * 所以字形必须和自己的字体一起传进来，不能只给码位。</p>
     */
    private void drawAmbientGlyph(CFontRenderer font, String glyph, double centerX, double centerY,
                                  double targetSize, int color) {
        if (font == null || glyph == null || glyph.isEmpty() || targetSize <= .5) return;
        double rawWidth = font.getStringWidthD(glyph);
        double rawHeight = Math.max(1.0, font.getFontHeight());
        double glyphScale = targetSize / Math.max(rawWidth, rawHeight);
        if (glyphScale <= .02) return;
        font.drawString(glyph, centerX - rawWidth * glyphScale * .5,
                centerY - rawHeight * glyphScale * .5, glyphScale, color);
    }

    /** 通知图标位上那枚动画矢量的显示边长。16 是接着 {@code islandIcon16} 那套字形的口径。 */
    private static final double NOTICE_ICON_BOX = 16.0;

    /**
     * 通知图标位上正在放的那枚动画矢量，和它自己的时钟。
     *
     * <p>只留一份：同一时刻托盘上最多一枚动画图标，而时钟必须跨帧活着（每帧新建的话
     * 动画永远停在第 0 帧）。换素材时重开一条时间轴，见 {@link #drawNoticeIcon}。</p>
     */
    private SmilSvg noticeIconSource;
    private SvgPlayback noticeIconPlayback;

    /**
     * 画一枚带动画的矢量图标，占住通知的左侧图标位。
     *
     * <p>素材本身就是动画 SVG，所以逐帧在 Skia 上求值而不是烘成精灵条 —— 后者要么丢分辨率、
     * 要么丢帧率，而这里一帧真正要画的叶子只有几个，实时画得起。</p>
     *
     * @return 画出来了才 true。素材读不到时 false，由调用方退回转子 —— 状态位不能空着。
     */
    private boolean drawNoticeIcon(SmilSvg icon, double centerX, double centerY, float alpha) {
        if (icon == null || alpha <= .01f) return false;
        if (noticeIconSource != icon) {
            noticeIconSource = icon;
            noticeIconPlayback = SvgPlayback.looping(icon);
        }
        return icon.drawCentered(centerX, centerY, NOTICE_ICON_BOX, noticeIconPlayback.time(), alpha, 0);
    }

    /**
     * 常驻态的左侧标记。两种形态，没有第三种：
     * <ul>
     *   <li>有歌（{@code CloudMusic.currentlyPlaying} 在、封面纹理也就绪）→ 当前歌曲封面；</li>
     *   <li>没歌 → 一枚静止的黑胶唱片（{@code party-icons} 的 U+E430）。</li>
     * </ul>
     * <p>这一格说的是"在听什么"，所以有歌就该一直是封面：原来只有「正在播放」那一页才铺，
     * 切到状态页就换成别的标记，等于同一件事在两个页面上各说一遍。玩家是否在走路也不再参与
     * —— 走路时画一个跑步小人，读起来像"这个应用在跟着我动"，而不是"我在听歌"。</p>
     * <p>刻意不复用加载转子：常驻并不代表有任务在跑，用转子会误导成"正在加载"。</p>
     *
     * @param plateSize   托盘边长。封面按它铺满整块托盘，不再自己先缩一圈
     * @param plateRadius 托盘圆角。封面沿用它才能严丝合缝：圆托盘传半径，方砖传方砖的圆角
     * @param glyphScale  退回字形时相对托盘的缩放；只缩字形，封面仍铺满托盘
     */
    private void renderAmbientIcon(double centerX, double centerY,
                                   double plateSize, double plateRadius, double glyphScale,
                                   float alpha, int accentColor) {
        if (alpha <= .01f) return;
        if (drawAmbientArtwork(centerX, centerY, plateSize, plateRadius, alpha)) {
            return;
        }
        // 只有字形分支才吃 glyphScale：封面已经在上面按整块托盘铺完并 return 了。
        api.getGLStateManager().pushMatrix();
        scaleAtPos(centerX, centerY, glyphScale);
        drawAmbientGlyph(FontManager.partyIcon24, PartyIcons.ALBUM, centerX, centerY,
                Math.max(6.0, plateSize * .68),
                RenderSystem.reAlpha(DynamicIslandMath.brightenAccent(accentColor, .18f), alpha));
        api.getGLStateManager().popMatrix();
    }

    /**
     * 画当前歌曲封面。动态封面就绪时 {@code preferredCoverLocation} 会返回抽帧纹理，
     * 于是常驻岛上的封面和桌面歌曲信息、全屏歌词页用的是同一张。
     *
     * <p>尺寸和圆角都由调用方按托盘给定，封面铺满托盘，避免缩图和小圆角留下月牙形空隙。</p>
     *
     * @param radius 圆角，会夹到 {@code size * .5}（即整圆）以内
     * @return 是否真的画出来了；没有歌曲或纹理还没加载好时返回 false，调用方退回字形图标
     */
    private boolean drawAmbientArtwork(double centerX, double centerY, double size, double radius,
                                       float alpha) {
        try {
            if (CloudMusic.currentlyPlaying == null || size <= 2.0) return false;
            ITextureObject texture = ambientArtworkTexture();
            if (texture == null) return false;
            double left = centerX - size * .5;
            double top = centerY - size * .5;
            double corner = DynamicIslandMath.clamp(radius, 0.0, size * .5);
            // 先垫一层几乎不透明的深色底：封面是按 alpha 画上去的，底下要是留着托盘那层强调色，
            // 透过来的主题色会把整张封面染成一片单色（展开动画期间 alpha 还更低，染得更明显）。
            roundedRect(left, top, size, size, corner,
                    hexColor(.016f, .020f, .030f, Math.min(1f, alpha * 1.06f)));
            SkijaGfx.setColor(1f, 1f, 1f, 1f);
            api.getGLStateManager().bindTexture(texture.getGlTextureId());
            texture.linearFilter();
            roundedRectTextured(left, top, size, size, corner, alpha);
            api.getGLStateManager().bindTexture(0);
            SkijaGfx.setColor(1f, 1f, 1f, 1f);
            // 铺满之后托盘自己那圈描边被完全盖掉，按同一个圆角补一道内切的淡高光顶上：
            // 液态玻璃这类亮表面上，封面没有边界就像直接糊在背景上、看不出是一枚独立的砖。
            roundedOutline(left, top, size, size, corner, .9,
                    new Color(255, 255, 255, DynamicIslandMath.clamp255(alpha * 78f)));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 常驻封面要用的纹理，拿不到就返回 {@code null}（小封面没下载好时退回大封面）。
     *
     * <p>单独抽出来是为了让左侧托盘在画自己之前就知道「这一帧会不会被封面盖住」：会盖住时
     * 托盘那层强调色必须关掉，否则封面被主题色染色。</p>
     */
    private static ITextureObject ambientArtworkTexture() {
        try {
            Music music = CloudMusic.currentlyPlaying;
            if (music == null) return null;
            Location cover = CloudMusic.preferredCoverLocation(music, music.getSmallCoverLocation());
            ITextureObject texture = TextureManager.getInstance().getTexture(cover);
            if (texture == null) {
                texture = TextureManager.getInstance().getTexture(music.getCoverLocation());
            }
            return texture;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 这一帧常驻岛左侧会不会画歌曲封面。托盘配色要据此决定，所以必须在绘制前问一次。 */
    private boolean hasAmbientArtwork() {
        return ambientArtworkTexture() != null;
    }

    /**
     * @param valueText 这张卡片正在显示的副文案。音量图标要按它读回档位，不能去读
     *                  {@code noticeValue} 静态字段：通知是排队播放的，字段早就被后来的
     *                  {@code showVolume} 改掉了，图标会和屏幕上的百分比错档。
     */
    private void renderNoticeIcon(IslandNoticeType type, String valueText, double centerX, double centerY,
                                  float alpha, int accentColor, long now) {
        if (alpha <= .01f) return;
        int foreground = hexColor(.94f, .95f, .98f, alpha);
        if (type == IslandNoticeType.MODEL_INFERENCE) {
            // 模型推理换 inference.svg 那组星光：这个状态一停就是好几秒，转子转久了像在卡死，
            // 而星光一下一下亮起来正好是"在算"的读感。素材读不到时仍退回转子，状态位不能空。
            if (!drawNoticeIcon(AnimatedIcons.inference(), centerX, centerY, alpha)) {
                renderSpinner(centerX, centerY, alpha, accentColor, now, false);
            }
            return;
        }
        if (type == IslandNoticeType.REFRESHING || type == IslandNoticeType.PLAYLIST_TRACK_ADDING
                || type == IslandNoticeType.TRANSCODING || type == IslandNoticeType.GD_SOURCE_LOADING
                || type == IslandNoticeType.FONT_ASSET
                || type == IslandNoticeType.LX_TRANSPILER
                || type == IslandNoticeType.ZSTD_NATIVE) {
            renderSpinner(centerX, centerY, alpha, accentColor, now, false);
            return;
        }
        // 一切"成功"共用同一枚对勾（U+E902）：原来的两笔手绘勾在小尺寸下笔画粗细不均，
        // 而且和"连接成功"的链条图形太像，用户分不出是哪一类完成了。
        if (type == IslandNoticeType.REFRESH_SUCCESS
                || type == IslandNoticeType.PLAYLIST_TRACK_ADD_SUCCESS
                || type == IslandNoticeType.PLAYLIST_TRACK_ALREADY_EXISTS
                || type == IslandNoticeType.TRANSCODE_SUCCESS || type == IslandNoticeType.GD_SOURCE_HEALTHY
                || type == IslandNoticeType.RECENT_PLAY_SUCCESS
                || type == IslandNoticeType.LISTENING_DURATION_SUCCESS
                || type == IslandNoticeType.MODEL_INFERENCE_SUCCESS
                || type == IslandNoticeType.FONT_ASSET_SUCCESS
                || type == IslandNoticeType.LX_TRANSPILER_SUCCESS
                || type == IslandNoticeType.ZSTD_NATIVE_SUCCESS) {
            drawIslandStatusIcon(IslandStatusIcons.SUCCESS, centerX, centerY, alpha,
                    hexColor(.70f, 1f, .77f, alpha));
            return;
        }
        // 网易云上报进行中：上传托盘（U+E807），和"成功"的对勾区分开。
        if (type == IslandNoticeType.NETEASE_UPLOADING) {
            drawIslandStatusIcon(IslandStatusIcons.UPLOAD, centerX, centerY, alpha,
                    RenderSystem.reAlpha(accentColor, alpha));
            return;
        }
        // 连接成功统一用链条（U+E903）。
        if (type == IslandNoticeType.GD_SOURCE_SUCCESS || type == IslandNoticeType.NETWORK_SUCCESS) {
            drawIslandStatusIcon(IslandStatusIcons.LINK, centerX, centerY, alpha,
                    hexColor(.70f, 1f, .77f, alpha));
            return;
        }
        if (type == IslandNoticeType.NETWORK_ERROR) {
            drawNoticeFontelloIcon(FontelloIcons.UNLINK, centerX, centerY, alpha,
                    hexColor(1f, .48f, .50f, alpha));
            return;
        }
        if (type == IslandNoticeType.REFRESH_ERROR || type == IslandNoticeType.PLAYLIST_TRACK_ADD_ERROR
                || type == IslandNoticeType.PLAYBACK_ERROR || type == IslandNoticeType.GD_SOURCE_ERROR
                || type == IslandNoticeType.MODEL_INFERENCE_FAILURE
                || type == IslandNoticeType.FONT_ASSET_ERROR
                || type == IslandNoticeType.LX_TRANSPILER_ERROR
                || type == IslandNoticeType.ZSTD_NATIVE_ERROR) {
            drawRotatedPill(centerX, centerY, 10.0, 1.25, 45f,
                    hexColor(1f, .48f, .50f, alpha));
            drawRotatedPill(centerX, centerY, 10.0, 1.25, -45f,
                    hexColor(1f, .48f, .50f, alpha));
            return;
        }
        if (type == IslandNoticeType.THEME || type == IslandNoticeType.STYLE) {
            roundedRect(centerX - 2.5, centerY - 2.5, 5.0, 5.0, 2.5,
                    RenderSystem.reAlpha(accentColor, alpha));
            roundedRect(centerX - .5, centerY - 6.0, 1.0, 1.8, .5, foreground);
            roundedRect(centerX - .5, centerY + 4.2, 1.0, 1.8, .5, foreground);
            roundedRect(centerX - 6.0, centerY - .5, 1.8, 1.0, .5, foreground);
            roundedRect(centerX + 4.2, centerY - .5, 1.8, 1.0, .5, foreground);
            return;
        }
        if (type == IslandNoticeType.VOLUME) {
            // 分档喇叭字形取代原来手绘的喇叭：手绘那版声波永远是两道，看不出当前音量大概在哪一档。
            drawIslandVolumeIcon(volumeGlyph(DynamicIslandMath.parseNoticePercent(valueText)),
                    centerX, centerY, alpha, foreground);
            return;
        }
        if (type == IslandNoticeType.PLAY_NEXT) {
            // 三条队列线加一个播放楔形：与生成它的行控件相同的设计思路。
            roundedRect(centerX - 5.6, centerY - 4.6, 8.4, 1.4, .7, foreground);
            roundedRect(centerX - 5.6, centerY - 1.0, 8.4, 1.4, .7, foreground);
            roundedRect(centerX - 5.6, centerY + 2.6, 4.6, 1.4, .7, foreground);
            roundedRect(centerX + 1.6, centerY + 1.4, 1.2, 3.8, .6,
                    RenderSystem.reAlpha(accentColor, alpha));
            roundedRect(centerX + 2.8, centerY + 2.1, 1.2, 2.4, .6,
                    RenderSystem.reAlpha(accentColor, alpha));
            roundedRect(centerX + 4.0, centerY + 2.7, 1.2, 1.2, .6,
                    RenderSystem.reAlpha(accentColor, alpha));
            return;
        }
        if (type == IslandNoticeType.PLAYBACK_TOGGLE) {
            // 两条竖条 = 暂停键，是这一类通知最直观的符号。
            roundedRect(centerX - 4.2, centerY - 4.8, 2.6, 9.6, 1.1, foreground);
            roundedRect(centerX + 1.6, centerY - 4.8, 2.6, 9.6, 1.1,
                    RenderSystem.reAlpha(accentColor, alpha));
            return;
        }
        if (type == IslandNoticeType.QUALITY || type == IslandNoticeType.AUTOMIX
                || type == IslandNoticeType.AUTOMIX_BLENDING) {
            // 紧凑的均衡器使播放质量通知与其他状态区分开来。
            roundedRect(centerX - 5.1, centerY + 1.5, 2.2, 3.5, 1.1,
                    RenderSystem.reAlpha(accentColor, alpha));
            roundedRect(centerX - 1.1, centerY - 1.3, 2.2, 6.3, 1.1, foreground);
            roundedRect(centerX + 2.9, centerY - 4.5, 2.2, 9.5, 1.1,
                    RenderSystem.reAlpha(accentColor, alpha));
            return;
        }

        double left = centerX - 4.8;
        double top = centerY - 4.8;
        double right = centerX + 4.8;
        double bottom = centerY + 4.8;
        double thickness = 1.0;
        double arm = 3.2;
        roundedRect(left, top, arm, thickness, .5, foreground);
        roundedRect(left, top, thickness, arm, .5, foreground);
        roundedRect(right - arm, top, arm, thickness, .5, foreground);
        roundedRect(right - thickness, top, thickness, arm, .5, foreground);
        roundedRect(left, bottom - thickness, arm, thickness, .5, foreground);
        roundedRect(left, bottom - arm, thickness, arm, .5, foreground);
        roundedRect(right - arm, bottom - thickness, arm, thickness, .5, foreground);
        roundedRect(right - thickness, bottom - arm, thickness, arm, .5, foreground);
    }

    private void drawNoticeFontelloIcon(String glyph, double centerX, double centerY, float alpha, int color) {
        if (glyph == null || glyph.isEmpty() || alpha <= .01f || FontManager.fontello16 == null) return;
        double width = FontManager.fontello16.getStringWidthD(glyph);
        double height = Math.max(1.0, FontManager.fontello16.getFontHeight());
        // 不要使用固定基线：Fontello 字形有不同的上伸部和
        // 下伸部边界。度量居中使每个左侧通知图标保持在
        // 其圆形底板的中央。
        FontManager.fontello16.drawString(glyph, centerX - width * .5, centerY - height * .5, color);
    }

    /**
     * 用 island-status-icons 字体画一枚居中的状态图标。
     *
     * <p>和 {@link #drawNoticeFontelloIcon} 分开是因为字体不同（两批 fontello 的私用区码位冲突），
     * 居中同样按度量算而不是固定基线：这批字形的 ascender/descender 各不相同。</p>
     */
    private void drawIslandStatusIcon(String glyph, double centerX, double centerY, float alpha, int color) {
        if (glyph == null || glyph.isEmpty() || alpha <= .01f || FontManager.islandIcon16 == null) return;
        double width = FontManager.islandIcon16.getStringWidthD(glyph);
        double height = Math.max(1.0, FontManager.islandIcon16.getFontHeight());
        FontManager.islandIcon16.drawString(glyph, centerX - width * .5, centerY - height * .5, color);
    }

    /** 通知托盘里音量图标的 em 方格边长。取 12px 是接着原来手绘喇叭的视觉体量（约 11.2px 宽）。 */
    private static final double VOLUME_NOTICE_ICON_SIZE = 12.0;
    /** island-volume-icons 的 hhea 上伸部 / em（实测 850/1000）。AWT 的 ascent 是它乘字号后向上取整。 */
    private static final double VOLUME_ICON_ASCENT_EM = .85;
    /** 这五枚字形的墨迹中心恒在基线上方 .35em（实测 glyf 边界，四档喇叭完全一致）。 */
    private static final double VOLUME_ICON_INK_CENTER_EM = .35;

    /**
     * 用 island-volume-icons 字体画一枚居中的图标。
     *
     * <p>和 {@link #drawIslandStatusIcon} 必须分开：这两份字体在 U+E902~U+E904 上码位相同、
     * 字形完全不同，走错字体就会画出对勾/链条而不是喇叭。</p>
     */
    private void drawIslandVolumeIcon(String glyph, double centerX, double centerY, float alpha, int color) {
        if (alpha <= .01f) return;
        drawVolumeFontIcon(FontManager.islandVolumeIcon24, FontManager.ISLAND_VOLUME_ICON_LARGE,
                glyph, centerX, centerY, VOLUME_NOTICE_ICON_SIZE, color);
    }

    /**
     * 按 em 方格把一枚 island-volume-icons 字形画到指定中心。
     *
     * <p>不能沿用「{@code getStringWidthD} / {@code getFontHeight} 各取一半」那套居中：</p>
     * <ul>
     *   <li>{@code getFontHeight()} 是给文本行距用的（内部减掉了 8.5），不是图集单元的真实高度，
     *       拿它算缩放会把 6.5px 的单元硬放大到 12.5px，图标直接糊掉；</li>
     *   <li>按每枚字形自己的宽度定尺寸会让四档喇叭大小不一：一道声波那枚的 advance 只有 .836em，
     *       同样"填满目标宽度"会把它的喇叭锥体放大约 20%，切档时肉眼可见地一跳。</li>
     * </ul>
     *
     * <p>所以统一按 em 方格算：{@code emTarget} 就是 1em 落屏后的边长，四档共用同一个缩放，
     * 喇叭锥体固定不动、声波往右长出去。竖直方向用实测的墨迹中心（基线上方 .35em，五枚一致）
     * 对齐，而不是行盒中心。字形原点落在方格左边，所以左上角要按 {@code emTarget} 的一半回退；
     * {@code CFontRenderer.drawString} 内部还会把 y 抬 2px（缩放后即 2×scale），一并抵掉。</p>
     *
     * @param nominalSize 该字体注册时的名义字号：em 方格在图集里就是这么多像素，落屏是一半
     */
    private void drawVolumeFontIcon(CFontRenderer font, int nominalSize, String glyph,
                                    double centerX, double centerY, double emTarget, int color) {
        if (font == null || glyph == null || glyph.isEmpty() || emTarget <= .5 || nominalSize <= 0) return;
        double emScreen = nominalSize * .5;
        double scale = emTarget / emScreen;
        double ascentScreen = Math.ceil(VOLUME_ICON_ASCENT_EM * nominalSize) * .5;
        double drawX = centerX - emTarget * .5;
        double drawY = centerY + VOLUME_ICON_INK_CENTER_EM * emTarget + 2.0 * scale
                - scale * ascentScreen;
        font.drawString(glyph, drawX, drawY, scale, color);
    }

    /**
     * 音量档位 → 喇叭字形。四档的下界分别是 80 / 60 / 35 / 0：
     * {@code [80,100]} 三道声波、{@code [60,80)} 两道、{@code [35,60)} 一道、{@code [0,35)} 静音喇叭。
     */
    static String volumeGlyph(int percent) {
        if (percent >= 80) return IslandVolumeIcons.VOLUME_HIGH;
        if (percent >= 60) return IslandVolumeIcons.VOLUME_MID;
        if (percent >= 35) return IslandVolumeIcons.VOLUME_LOW;
        return IslandVolumeIcons.VOLUME_MUTE;
    }

    private void renderSpinner(double centerX, double centerY, float alpha, int accentColor,
                               long now, boolean preview) {
        if (alpha <= .01f) return;
        double rotation = preview ? (now / 7.0) % 360.0 : spinnerRotation;
        if (getStyle() == DynamicIslandStyle.LIQUID_GLASS) {
            // 液态玻璃有自己的加载动画；这里集中转发，通知图标那一路也能一起用上。
            renderLiquidGlassSpinner(centerX, centerY, alpha, rotation, now);
            return;
        }
        // 先铺一圈极淡的轨道：慢转速下原来只看到一小截笔画在飘，很难认出是"正在加载"。
        roundedOutline(centerX - 6.7, centerY - 6.7, 13.4, 13.4, 6.7, .85,
                new Color(255, 255, 255, DynamicIslandMath.clamp255(alpha * 26f)));
        final int segments = 8;
        for (int segment = 0; segment < segments; segment++) {
            float trail = 1f - segment / (float) segments;
            // 尾部不透明度抬到 .22 起步、笔画加粗到 1.9px：在 0.88 缩放 + 高 GUI Scale 下仍然连成一条。
            float segmentAlpha = alpha * (.22f + .78f * trail * trail);
            api.getGLStateManager().pushMatrix();
            api.getGLStateManager().translate(centerX, centerY, 0);
            api.getGLStateManager().rotate((float) (rotation + segment * (360f / segments)), 0, 0, 1);
            roundedRect(-.95, -6.7, 1.90, 3.0 + 1.0 * trail, .95,
                    RenderSystem.reAlpha(accentColor, segmentAlpha));
            api.getGLStateManager().popMatrix();
        }
        // 领头的高亮点，给旋转方向一个明确的读点。
        api.getGLStateManager().pushMatrix();
        api.getGLStateManager().translate(centerX, centerY, 0);
        api.getGLStateManager().rotate((float) rotation, 0, 0, 1);
        roundedRect(-1.15, -7.0, 2.30, 2.30, 1.15, hexColor(1f, 1f, 1f, alpha * .92f));
        api.getGLStateManager().popMatrix();
    }

    /** 把配置里的速度倍率折算成插值系数，永远留在 (0,.95] 内，速度再快也不会越过目标值。 */
    private static float animationRate(float configuredSpeed, float baseRate) {
        float speed = (float) DynamicIslandMath.clamp(configuredSpeed, .40, 2.50);
        return Math.max(.02f, Math.min(.95f, baseRate * speed));
    }

    /**
     * 液态玻璃表面：半透明冷色基底 + 上半部折射亮带 + 底部回弹反射，外沿是一圈按时间旋转色相的光。
     *
     * <p>所有笔触都用圆角矩形与其渐变版本绘制，因此天然贴合灵动岛轮廓，不会像径向光斑那样
     * 溢出边界；也没有额外的 GL 状态切换，与既有样式共用同一层状态隔离。</p>
     *
     * <p>取色一律来自 {@link #GLASS_TINT}/{@link #GLASS_TINT_BRIGHT} 而不是主题强调色：玻璃不该跟着
     * 主题整体偏色。原来那条来回横扫的镜面反光已按要求移除。</p>
     */
    private void renderLiquidGlassSurface(double x, double y, double width, double height,
                                         double radius, float alpha, long now) {
        double seconds = now / 1000.0;
        int accentBright = GLASS_TINT_BRIGHT;
        double innerRadius = Math.max(1.0, radius - 1.0);

        // 真玻璃：先把糊过的游戏画面铺进岛形，再让下面那层深色底按「玻璃浓度」压薄。
        // 铺不上（没选琉璃主题、生效面关了、采集停用）时一行都不改原样，
        // 所以这里刻意用返回值的布尔而不是强度去决定压不压薄——采不到却把底压薄，
        // 症状会是「岛变成一块半透明的脏斑」。
        boolean frosted = GlassLook.paintFrost(GlassLook.Surface.ISLAND, x, y, width, height,
                radius, alpha);
        float thin = frosted ? GlassLook.tintedAlpha(GlassLook.Surface.ISLAND, 1.0f) : 1.0f;
        if (frosted) {
            // The reference lens already supplies the whole surface, directional gain and edge behaviour.
            // Never paint the old opaque island fill on top of it.
            return;
        }
        float glow = GlassLook.edgeGlow(GlassLook.Surface.ISLAND);

        // 玻璃本体：偏冷的深色基底压住背景，再叠一层玻璃色的竖向渐变当作体积感。
        roundedRect(x, y, width, height, radius, hexColor(.043f, .055f, .078f, alpha * .82f * thin));
        roundedRectGradientVertical(x, y, width, height, radius,
                DynamicIslandMath.colorWithAlpha(accentBright, alpha * .17f * thin),
                DynamicIslandMath.colorWithAlpha(GLASS_TINT, alpha * .07f * thin));
        // 冷白到紫蓝的横向色散。这里不用 drawGradientCornerLR：它的混合角会被算成完全不透明，
        // 放在半透明的玻璃上会糊成两块实色。
        roundedRectGradientHorizontal(x, y, width, height, radius,
                new Color(222, 241, 255, DynamicIslandMath.clamp255(alpha * 34f)),
                new Color(122, 152, 240, DynamicIslandMath.clamp255(alpha * 26f)));

        // 折射：上半部亮带 + 底部回弹的强调色反射。
        roundedRectGradientVertical(x + 1.0, y + 1.0, Math.max(1.0, width - 2.0),
                Math.max(1.0, height * .52), innerRadius,
                new Color(255, 255, 255, DynamicIslandMath.clamp255(alpha * 46f)),
                new Color(255, 255, 255, 0));
        roundedRectGradientVertical(x + 1.0, y + height * .60, Math.max(1.0, width - 2.0),
                Math.max(1.0, height * .38), innerRadius,
                new Color(255, 255, 255, 0),
                DynamicIslandMath.colorWithAlpha(accentBright, alpha * .13f));

        // 真玻璃的边缘折射：把背后那块画面沿岛形轮廓的法线掰一下再采，三通道各掰不同距离。
        // 画在底色与亮带之后，否则最外圈那几像素会被自己刚压上去的深色吃掉。
        GlassLook.paintEdge(GlassLook.Surface.ISLAND, x, y, width, height, radius, alpha);

        // 原来这里有一条来回横扫的镜面反光（扫光）。已按要求移除：它在读字时会周期性压过文案，
        // 而玻璃的体积感由上面的折射亮带与下面的边缘光已经交代清楚了。

        // 边缘动态取色：四角取自玻璃色的四个色相相位，整圈随时间缓慢转动。
        // 「玻璃边缘光」那一档滑块只乘在这一圈和里面的白细边上——它们就是这块玻璃的边缘光，
        // 而折射亮带属于「体积感」，跟着一起调会把岛画成忽明忽暗。
        float phase = (float) ((seconds * 26.0) % 360.0);
        roundedOutlineGradient(x, y, width, height, radius, 1.0,
                DynamicIslandMath.colorWithAlpha(DynamicIslandMath.shiftHue(accentBright, phase), alpha * .80f * glow),
                DynamicIslandMath.colorWithAlpha(DynamicIslandMath.shiftHue(accentBright, phase + 70f), alpha * .92f * glow),
                DynamicIslandMath.colorWithAlpha(DynamicIslandMath.shiftHue(accentBright, phase + 140f), alpha * .74f * glow),
                DynamicIslandMath.colorWithAlpha(DynamicIslandMath.shiftHue(accentBright, phase + 210f), alpha * .90f * glow));
        roundedOutline(x + 1.0, y + 1.0, Math.max(1.0, width - 2.0), Math.max(1.0, height - 2.0),
                innerRadius, .55, new Color(255, 255, 255, DynamicIslandMath.clamp255(alpha * 30f * glow)));
    }

    /**
     * 液态玻璃的加载动画：外圈静止的玻璃刻度、一条按色相渐变的彗尾主环、反向旋转的内圈三点，
     * 以及中心随呼吸缩放的光点。全部由旋转后的圆角矩形组成，1.8.9 的固定管线可以直接吃下。
     */
    private void renderLiquidGlassSpinner(double centerX, double centerY, float alpha,
                                          double rotation, long now) {
        // 同样固定用玻璃色：加载环是玻璃里的一部分，跟着主题偏色就和表面对不上了。
        int accentBright = GLASS_TINT_BRIGHT;
        double seconds = now / 1000.0;

        // 外圈刻度：固定不动，给旋转体一个可参照的"玻璃环"。
        final int ticks = 18;
        for (int tick = 0; tick < ticks; tick++) {
            drawRotatedPill(centerX, centerY - 7.1, 1.05, .95,
                    tick * (360f / ticks), hexColor(1f, 1f, 1f, alpha * .10f));
        }

        // 主环彗尾：越靠尾部越短越淡，颜色沿色相环推移。
        final int segments = 12;
        for (int segment = 0; segment < segments; segment++) {
            float trail = 1f - segment / (float) segments;
            float segmentAlpha = alpha * (.08f + .92f * trail * trail);
            double length = 2.1 + 2.4 * trail;
            int color = DynamicIslandMath.shiftHue(accentBright, segment * 9f
                    + (float) ((seconds * 34.0) % 360.0));
            api.getGLStateManager().pushMatrix();
            api.getGLStateManager().translate(centerX, centerY, 0);
            api.getGLStateManager().rotate((float) (rotation - segment * (250f / segments)), 0, 0, 1);
            roundedRect(-.80, -6.5, 1.60, length, .80,
                    DynamicIslandMath.colorWithAlpha(color, segmentAlpha).getRGB());
            api.getGLStateManager().popMatrix();
        }

        // 内圈：三点反向慢转，制造两层不同速度的视差。
        for (int dot = 0; dot < 3; dot++) {
            api.getGLStateManager().pushMatrix();
            api.getGLStateManager().translate(centerX, centerY, 0);
            api.getGLStateManager().rotate((float) (-rotation * .58 + dot * 120f), 0, 0, 1);
            roundedRect(-.75, -3.7, 1.50, 1.50, .75,
                    RenderSystem.reAlpha(accentBright, alpha * (.34f + .18f * dot)));
            api.getGLStateManager().popMatrix();
        }

        // 中心光点：呼吸缩放，和边缘光同一套节奏。
        double breathe = .78 + .22 * (.5 + .5 * Math.sin(seconds * 2.4));
        double core = 1.9 * breathe;
        roundedRect(centerX - core, centerY - core, core * 2.0, core * 2.0, core,
                hexColor(1f, 1f, 1f, alpha * .58f));
    }

    private void renderSuccess(double centerX, double centerY, float alpha) {
        if (alpha <= .01f) return;
        double scale = .78 + .22 * DynamicIslandMath.smoothStep(successMorph);
        api.getGLStateManager().pushMatrix();
        scaleAtPos(centerX, centerY, scale);
        roundedOutline(centerX - 6.2, centerY - 6.2, 12.4, 12.4, 6.2, .75,
                new Color(91, 240, 129, DynamicIslandMath.clamp255(alpha * 220f)));
        // 下载/播放完成也用同一枚 U+E902 对勾，和通知里的"成功"完全一致。
        drawIslandStatusIcon(IslandStatusIcons.SUCCESS, centerX, centerY, alpha,
                hexColor(.70f, 1f, .77f, alpha));
        api.getGLStateManager().popMatrix();
    }

    private void drawRotatedPill(double centerX, double centerY, double width, double height,
                                 float rotation, int color) {
        api.getGLStateManager().pushMatrix();
        api.getGLStateManager().translate(centerX, centerY, 0);
        api.getGLStateManager().rotate(rotation, 0, 0, 1);
        roundedRect(-width * .5, -height * .5, width, height, height * .5, color);
        api.getGLStateManager().popMatrix();
    }

    /* 音乐收起行及五款共用的聊天音乐卡，几何与输入都使用实际绘制边界。 */

    /** 隐藏早退前推进；只在聊天界面接受输入，不创建第二个 Screen。 */
    private void tickMusicCardState(boolean surfaceBlocked, double deltaMillis) {
        maintainMusicCardContext();
        boolean chat = IslandVisibilityGate.isChatOpen();
        boolean enabled = HudConfig.dynamicIslandEnabled && HudConfig.islandHoverExpand;
        boolean blocked = surfaceBlocked || musicCardSurfaceBlockedNow();
        double motionDelta = !blocked && SkijaGfx.active() && SkijaGfx.canvas() != null ? deltaMillis : 0.0;
        if (motionDelta <= .001) musicCardInteraction.pausePresentation();
        MusicCapsuleCard.tick((float) motionDelta, (float) (blocked ? 0.0 : deltaMillis));
        CloudMusic.PlaybackSnapshot snapshot = MusicCapsuleCard.currentSnapshot();
        musicCardInteraction.update(enabled, chat, snapshot.music != null, blocked,
                HudConfig.islandExpandTrigger, motionDelta, MusicCapsuleCard.presentationReady());
        boolean event = musicCardInteraction.trackNoticeActive();
        cardOpenTarget = musicCardInteraction.wantsOpen();
        cardOpen = musicCardInteraction.openness();
        cardFittedScale = fitMusicCardScale();
        musicCardOwnsSurface = musicCardClaimsSurface();
        if (!musicCardOwnsSurface) MUSIC_CARD_BOUNDS = null;
        if ((!chat && !event) || !enabled || blocked) MusicCapsuleCard.INSTANCE.cancelConfirmation();

        double[] mouse = chat && !event ? musicMouseLayout() : null;
        double[] point = mouse == null ? null : MusicCardLayout.designPoint(mouse[0], mouse[1], MUSIC_CARD_BOUNDS);
        if (musicCardInteraction.canInteract() && !snapshot.preparing && point != null) {
            MusicCapsuleCard.INSTANCE.setPointer(point[0], point[1]);
        } else {
            MusicCapsuleCard.INSTANCE.clearPointer();
        }
        String key = snapshot.music == null ? null : snapshot.music.getStableKey();
        if (chat && musicCardInteraction.dragging(snapshot.sessionId, key) && point != null) {
            if (com.muoniumplayer.compat.lwjgl2.Mouse.isButtonDown(0)) {
                musicCardInteraction.previewSeek(MusicCardLayout.seekFraction(point[0]));
            } else {
                musicCardInteraction.cancelSeek();
                MusicCapsuleCard.INSTANCE.releasePointer();
            }
        }
    }

    /** 输入与绘制共用场景门禁；通知、下载和曲线不再阻塞音乐卡。 */
    private static boolean musicCardSurfaceBlockedNow() {
        return playerSurfaceTransition || playerAbsorbImpact
                || !IslandVisibilityGate.allowTrackNoticeOverlay(IslandVisibilityGate.capture(),
                IslandVisibilityGate.isChatOpen(), playerSurfaceTransition);
    }

    /** 七树鼠标宿主在 vanilla 分派前调用，只有卡片按下及其配对抬起会被消费。 */
    public static boolean handleMusicCardMouseButton(int button, int action) {
        if (button != 0) return false;
        maintainMusicCardContext();
        MusicCardInteraction interaction = INSTANCE.musicCardInteraction;
        if (action == 1) interaction.release();
        if (musicCardSurfaceBlockedNow()) {
            boolean captured = action == 0 && interaction.release();
            interaction.suspend();
            MusicCapsuleCard.INSTANCE.cancelConfirmation();
            MusicCapsuleCard.INSTANCE.clearPointer();
            return captured;
        }
        if (action == 0) {
            CloudMusic.PlaybackSnapshot snapshot = CloudMusic.getSnapshot();
            String key = snapshot.music == null ? null : snapshot.music.getStableKey();
            if (IslandVisibilityGate.isChatOpen() && interaction.dragging(snapshot.sessionId, key)) {
                double[] mouse = musicMouseLayout();
                double[] point = mouse == null ? null
                        : MusicCardLayout.designPoint(mouse[0], mouse[1], MUSIC_CARD_BOUNDS);
                if (point != null) interaction.previewSeek(MusicCardLayout.seekFraction(point[0]));
                MusicCapsuleCard.INSTANCE.seekToFraction(interaction.seekFraction(), snapshot);
            }
            MusicCapsuleCard.INSTANCE.releasePointer();
            return interaction.release();
        }
        if (action == 1 && interaction.eventLocked()) return false;
        if (action != 1 || !HudConfig.dynamicIslandEnabled || !HudConfig.islandHoverExpand
                || !IslandVisibilityGate.isChatOpen() || playerSurfaceTransition
                || !IslandVisibilityGate.allowSurface(IslandVisibilityGate.capture())) return false;
        double[] mouse = musicMouseLayout();
        if (mouse == null || !MusicCapsuleCard.hasMusic()) return false;
        if (MusicCapsuleCard.INSTANCE.clickConfirmation(mouse[0], mouse[1])) {
            interaction.capturePress();
            return true;
        }
        double[] bounds = musicCardOwnsSurface ? MUSIC_CARD_BOUNDS : getLiveAnchorBounds();
        if (bounds == null) return false;
        double radius = bounds.length > 4 ? bounds[4] : Math.min(bounds[2], bounds[3]) * .5;
        boolean inside = MusicCardLayout.containsRounded(mouse[0], mouse[1], bounds[0], bounds[1],
                bounds[2], bounds[3], radius);
        double[] anchorBounds = musicCardOwnsSurface && !interaction.canInteract() ? MUSIC_CARD_ANCHOR_BOUNDS : null;
        if (!inside && anchorBounds != null) {
            inside = MusicCardLayout.containsRounded(mouse[0], mouse[1], anchorBounds[0], anchorBounds[1],
                    anchorBounds[2], anchorBounds[3], anchorBounds[4]);
        }
        if (!inside) {
            if (cardIsExpanded()) collapseMusicCard();
            return false;
        }
        if (!interaction.available()) return false;
        interaction.capturePress();
        if (!interaction.canInteract()) {
            interaction.expand();
            return true;
        }
        if (musicCardOwnsSurface && MusicCapsuleCard.currentSnapshot().preparing) return true;
        double[] point = MusicCardLayout.designPoint(mouse[0], mouse[1], bounds);
        if (point == null) return true;
        CloudMusic.PlaybackSnapshot snapshot = CloudMusic.getSnapshot();
        if (!MusicCapsuleCard.matchesFrame(snapshot) || musicCardDrawnSnapshot == null
                || musicCardDrawnSnapshot.preparing || musicCardDrawnSnapshot.sessionId != snapshot.sessionId
                || musicCardDrawnSnapshot.music == null
                || !musicCardDrawnSnapshot.music.getStableKey().equals(snapshot.music.getStableKey())) return true;
        MusicCardLayout.Target target = MusicCapsuleCard.INSTANCE.targetAt(point[0], point[1], snapshot);
        MusicCapsuleCard.INSTANCE.pressPointer(target);
        if (target == MusicCardLayout.Target.SEEK && snapshot.music != null) {
            interaction.beginSeek(snapshot.sessionId, snapshot.music.getStableKey(), MusicCardLayout.seekFraction(point[0]));
        } else {
            MusicCapsuleCard.INSTANCE.click(point[0], point[1], snapshot);
        }
        return true;
    }

    private static boolean cardIsExpanded() {
        return INSTANCE.cardOpen > .05 || INSTANCE.cardOpenTarget;
    }

    static double musicCardProgress(double fallback) {
        CloudMusic.PlaybackSnapshot snapshot = MusicCapsuleCard.currentSnapshot();
        String key = snapshot.music == null ? null : snapshot.music.getStableKey();
        return INSTANCE.musicCardInteraction.dragging(snapshot.sessionId, key)
                ? INSTANCE.musicCardInteraction.seekFraction() : fallback;
    }

    /** 卡片与当前窗口可用区域一起收口，位置偏移和整体岛缩放同样计入。 */
    private float fitMusicCardScale() {
        double configuredScale = DynamicIslandMath.clamp(HudConfig.dynamicIslandScale, .60, 1.35);
        double top = (getStyle() == DynamicIslandStyle.BOUNCE ? BounceIslandMath.TOP : TOP_MARGIN)
                + DynamicIslandMath.clamp(HudConfig.dynamicIslandY, -20.0, 400.0);
        double availableWidth = Math.max(1.0, (RenderSystem.getLayoutWidth() - 24.0) / configuredScale);
        double availableHeight = Math.max(1.0, (RenderSystem.getLayoutHeight() - Math.max(6.0, top) - 12.0)
                / configuredScale);
        double compactHeight = getStyle() == DynamicIslandStyle.BOUNCE ? BounceIslandMath.ROW_HEIGHT
                : getStyle() == DynamicIslandStyle.COMPACT ? 14.0 : COMPACT_HEIGHT;
        double fit = Math.min(availableWidth / MusicCapsuleCard.CARD_WIDTH,
                Math.max(1.0, availableHeight - compactHeight) / (MusicCapsuleCard.CARD_HEIGHT + 12.0));
        return (float) Math.max(.1, Math.min(MusicCapsuleCard.cardScale(), fit));
    }

    /**
     * 鼠标位置换算成布局轴坐标（岛的绘制坐标轴）。
     *
     * <p>compat {@code Mouse} 报 framebuffer 像素（原点在下），布局轴与 framebuffer 同跨整个
     * 窗口，按比例一换就对——不经过 GUI Scale，也就不依赖任何一棵树的屏幕 API。</p>
     */
    private static double[] musicMouseLayout() {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc == null || mc.getWindow() == null) return null;
            int framebufferWidth = Math.max(1, mc.getWindow().getWidth());
            int framebufferHeight = Math.max(1, mc.getWindow().getHeight());
            double layoutWidth = RenderSystem.getLayoutWidth();
            double layoutHeight = RenderSystem.getLayoutHeight();
            if (layoutWidth <= 1.0 || layoutHeight <= 1.0) return null;
            double mx = com.muoniumplayer.compat.lwjgl2.Mouse.getX() * layoutWidth / (double) framebufferWidth;
            double my = (framebufferHeight - 1 - com.muoniumplayer.compat.lwjgl2.Mouse.getY())
                    * layoutHeight / (double) framebufferHeight;
            return new double[]{mx, my};
        } catch (Throwable t) {
            return null;
        }
    }

    private void drawMusicCardAnchorSurface(double x, double y, double width, double height,
                                            double radius, float alpha, boolean liquidGlass, long now) {
        if (liquidGlass) {
            renderLiquidGlassSurface(x, y, width, height, radius, alpha, now);
            return;
        }
        boolean frosted = GlassLook.paintFrost(GlassLook.Surface.ISLAND, x, y, width, height, radius, alpha);
        float thin = frosted ? GlassLook.tintedAlpha(GlassLook.Surface.ISLAND, 1.0f) : 1.0f;
        roundedRect(x, y, width, height, radius, hexColor(.012f, .014f, .020f, alpha * .985f * thin));
    }

    private void drawMusicCapsuleSurface(double x, double y, double width, double height,
                                         double radius, float alpha) {
        boolean frosted = GlassLook.paintFrost(GlassLook.Surface.ISLAND, x, y, width, height, radius, alpha);
        float thin = frosted ? GlassLook.tintedAlpha(GlassLook.Surface.ISLAND, 1.0f) : 1.0f;
        roundedRect(x, y, width, height, radius, hexColor(.010f, .014f, .024f, alpha * .99f * thin));
        roundedOutline(x, y, width, height, radius, .55,
                new Color(255, 255, 255, DynamicIslandMath.clamp255(alpha * 22f)));
        if (frosted) GlassLook.paintEdge(GlassLook.Surface.ISLAND, x, y, width, height, radius, alpha);
        roundedRect(x + radius * .70, y + .8, Math.max(2.0, width - radius * 1.40), .65, .32,
                hexColor(1f, 1f, 1f, alpha * .035f * thin));
    }

    private void drawCompletionBubbleIfActive(boolean bubble, double x, double y, double width,
                                              double height, double radius, float alpha, String label,
                                              long startedAt, boolean capsuleRow, long now) {
        if (bubble) drawCapsuleCompletionBubble(x, y, width, height, radius, alpha,
                label, startedAt, capsuleRow, now);
    }

    private void drawCapsuleCompletionBubble(double x, double y, double width, double height, double radius,
                                             float alpha, String label, long startedAt, boolean capsuleRow,
                                             long now) {
        if (alpha <= .01f || startedAt <= 0L) return;
        double elapsed = Math.max(0L, now - startedAt);
        double progress = DynamicIslandMath.clamp01(elapsed / 210.0);
        double eased = DynamicIslandMath.smoothStep(progress);
        double textWidth = FontManager.pf12bold == null ? 0.0 : FontManager.pf12bold.getStringWidthD(label);
        double bubbleWidth = Math.max(68.0, textWidth + 34.0);
        double bubbleHeight = 22.0;
        double centerX = x + width * .5;
        double lift = (1.0 - eased) * 7.0;
        double bubbleX = centerX - bubbleWidth * .5;
        double bubbleY = y + height - 1.0 + lift;
        float bubbleAlpha = alpha * (float) eased;
        int accent = NCMTheme.getAccentColor();
        roundedRect(bubbleX + 1.0, bubbleY + 1.2, bubbleWidth, bubbleHeight, bubbleHeight * .5,
                hexColor(0f, 0f, 0f, bubbleAlpha * .22f));
        roundedRect(bubbleX, bubbleY, bubbleWidth, bubbleHeight, bubbleHeight * .5,
                hexColor(.035f, .045f, .065f, bubbleAlpha * .96f));
        roundedOutline(bubbleX, bubbleY, bubbleWidth, bubbleHeight, bubbleHeight * .5, .65,
                new Color(RenderSystem.reAlpha(accent, bubbleAlpha * .78f), true));
        drawIslandStatusIcon(IslandStatusIcons.SUCCESS, bubbleX + 12.0, bubbleY + bubbleHeight * .5,
                bubbleAlpha, hexColor(.70f, 1f, .77f, bubbleAlpha));
        if (FontManager.pf12bold != null) {
            double available = Math.max(1.0, bubbleWidth - 29.0);
            drawCenteredIslandText(FontManager.pf12bold, label, bubbleX + 17.0 + available * .5,
                    bubbleY + 6.2, available, 1.0, hexColor(1f, 1f, 1f, bubbleAlpha));
        }
        if (progress >= 1.0 && capsuleRow) {
            roundedRect(bubbleX + bubbleWidth * .5 - 5.0, bubbleY + bubbleHeight - .5,
                    10.0, 1.2, .6, RenderSystem.reAlpha(accent, bubbleAlpha * .58f));
        }
    }

    private void drawInferenceCapsuleMarquee(double x, double y, double width, double height,
                                             double radius, float alpha, int accentColor, long now,
                                             double downloadProgress) {
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null || alpha <= .01f || width <= 8.0 || height <= 8.0) return;
        double inset = 1.25;
        double innerWidth = width - inset * 2.0;
        double innerHeight = height - inset * 2.0;
        double innerRadius = Math.max(0.0, Math.min(radius - inset, innerHeight * .5));
        int save = canvas.save();
        try {
            canvas.clipRRect(RRect.makeXYWH((float) x, (float) y, (float) width, (float) height,
                    (float) Math.min(radius, height * .5)), true);
            roundedOutline(x + inset, y + inset, innerWidth, innerHeight, innerRadius, 1.05,
                    new Color(RenderSystem.reAlpha(accentColor, alpha * .24f), true));
            int samples = InferenceCapsuleMotion.sampleCount(innerWidth, innerHeight, innerRadius);
            double progress = InferenceCapsuleMotion.progress(downloadProgress);
            int passed = InferenceCapsuleMotion.completedSamples(progress, samples);
            if (passed > 1) drawCapsuleBorderFill(canvas, x + inset, y + inset, innerWidth, innerHeight,
                    innerRadius, passed, samples, accentColor, alpha);

            double trailSamples = InferenceCapsuleMotion.trailSampleCount(samples);
            double head = InferenceCapsuleMotion.phase(now);
            double trailLength = InferenceCapsuleMotion.trailLength(progress);
            for (int index = 0; index < trailSamples; index++) {
                double t = index / (double) Math.max(1, trailSamples - 1);
                double position = head - trailLength * t;
                InferenceCapsuleMotion.Point point = InferenceCapsuleMotion.pointAt(
                        innerWidth, innerHeight, innerRadius, position);
                double glow = (1.0 - t) * (1.0 - t);
                int color = t < .28 ? DynamicIslandMath.brightenAccent(accentColor, .46f) : accentColor;
                float tickAlpha = alpha * (float) (.16 + .84 * glow);
                drawRotatedPill(x + inset + point.x, y + inset + point.y,
                        2.5 + 1.9 * glow, 1.2, (float) point.tangentDegrees,
                        RenderSystem.reAlpha(color, tickAlpha));
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private static void drawCapsuleBorderFill(Canvas canvas, double x, double y, double width, double height,
                                              double radius, int points, int total, int accentColor, float alpha) {
        try (io.github.humbleui.skija.PathBuilder builder = new io.github.humbleui.skija.PathBuilder();
             io.github.humbleui.skija.Paint paint = new io.github.humbleui.skija.Paint();
             io.github.humbleui.skija.Shader shader = io.github.humbleui.skija.Shader.makeLinearGradient(
                     (float) x, (float) y, (float) (x + width), (float) (y + height),
                     new int[]{RenderSystem.reAlpha(accentColor, alpha * .78f),
                             RenderSystem.reAlpha(accentColor, alpha * .08f)})) {
            for (int index = 0; index <= points; index++) {
                InferenceCapsuleMotion.Point point = InferenceCapsuleMotion.pointAt(
                        width, height, radius, Math.min(1.0, index / (double) total));
                float px = (float) (x + point.x);
                float py = (float) (y + point.y);
                if (index == 0) builder.moveTo(px, py); else builder.lineTo(px, py);
            }
            paint.setAntiAlias(true);
            paint.setMode(io.github.humbleui.skija.PaintMode.STROKE);
            paint.setStrokeCap(io.github.humbleui.skija.PaintStrokeCap.ROUND);
            paint.setStrokeWidth(1.4f);
            paint.setShader(shader);
            try (io.github.humbleui.skija.Path path = builder.snapshot()) {
                canvas.drawPath(path, paint);
            }
        }
    }

    /** 音乐胶囊收起行的表面，聊天音乐卡使用独立的高斯模糊材质。 */
    private void drawMusicCapsuleIsland(double x, double y, double width, double height, double radius,
                                        float alpha, float ambientAlpha) {
        int accent = NCMTheme.getAccentColor();
        drawMusicCapsuleSurface(x, y, width, height, radius, alpha);
        MusicCapsuleCard.INSTANCE.drawRow(x, y, width, height, alpha * ambientAlpha, accent);
    }

    /*
     * ============================== 回弹胶囊（BOUNCE）==============================
     *
     * 参考实现是作者自己另一份 Hypixel 端的 Dynamic Island 脚本，几何常量按它逐项抄齐。
     * 这一款借的只有「动起来是什么样」和「怎么排版」：弹簧尺寸、按事件语义染整块表面、
     * 多事件按行堆叠、坐标吸附到物理像素。内容仍然是本项目自己的事件源（下载进度、
     * 通知队列、常驻状态行），没有新造任何数据。
     *
     * 下面每个方法都只在 style == BOUNCE 时经 drawIsland 调用，现有七款的绘制路径一行未动。
     */

    /** 回弹样式下贴顶居中的品牌字样；参考稿那里是脚本作者的 ID。 */
    private static final String BOUNCE_BRAND = "Muonium";

    /** 本帧的秒级帧间隔。弹簧与底色渐变共用同一个值，所以一帧只能推进一次时间戳。 */
    private double bounceFrameSeconds() {
        long nowNanos = System.nanoTime();
        bounceFrameDelta = BounceIslandMath.frameSeconds(nowNanos, bounceLastFrameNanos);
        bounceLastFrameNanos = nowNanos;
        return bounceFrameDelta;
    }

    /** 静息行与进度行的字号：参考稿那两档不跟着通知行缩到 0.8，只用通用的降级比例。 */
    private static double bouncePlainRowTextScale(IslandLayout layout) {
        return DynamicIslandMath.clamp(layout.textScale, MIN_AUTO_TEXT_SCALE, MAX_TEXT_SCALE);
    }

    /** 这一款的正文天生比其他样式小一档（参考稿的 NOTICE_SCALE），再乘上通用的降级比例。 */
    private static double bounceRowTextScale(IslandLayout layout) {
        return BounceIslandMath.NOTICE_TEXT_SCALE * bouncePlainRowTextScale(layout);
    }

    /**
     * 收集本帧要堆叠的行：当前正在显示的通知，加上队列里排在后面的几条。
     *
     * <p>这是这一款和其他七款在信息量上的唯一差别——别的样式一条盖一条，它按到达顺序
     * 往上叠。每行的到达时刻按「类型 + 标题 + 正文」从上一帧的结果里对齐取回，所以行
     * 往上挪的时候不会把自己那条淡入动画重置掉。</p>
     */
    private List<BounceRow> bounceCollectRows(long now, boolean noticeMode, IslandNoticeType type,
                                              String title, String value, boolean preview) {
        List<BounceRow> fresh = new ArrayList<BounceRow>(BounceIslandMath.MAX_ROWS);
        if (preview) {
            // 预览里必须看得见「堆叠」这件事，否则这一款在编辑器里和其他样式没有区别，没人会选它。
            fresh.add(new BounceRow(IslandNoticeType.STYLE, "回弹胶囊", "弹簧尺寸 + 逐行堆叠", now, 0L));
            fresh.add(new BounceRow(IslandNoticeType.REFRESH_SUCCESS, "音源刷新完成",
                    "已更新 12 首", now, now + 2_600L));
            fresh.add(new BounceRow(IslandNoticeType.VOLUME, "音量", "62%", now, 0L));
            return bounceSwapRows(fresh);
        }
        synchronized (NOTICE_QUEUE_LOCK) {
            if (noticeMode && type != IslandNoticeType.NONE) {
                long activeExpiry = noticePersistent ? 0L
                        : now + Math.max(0L,noticeBudgetMillis - NOTICE_VISIBLE_CLOCK.elapsed());
                if (noticeExpiresAt > 0L) activeExpiry=Math.min(activeExpiry,noticeExpiresAt);
                fresh.add(new BounceRow(type, safeNoticeValue(title, "状态"), safeNoticeValue(value, "—"),
                        bounceRowArrivedAt(now, type, title, value), activeExpiry));
            }
            for (QueuedNotice queued : NOTICE_QUEUE) {
                if (fresh.size() >= BounceIslandMath.MAX_ROWS) break;
                if (queued.expiresAt > 0L && now >= queued.expiresAt) continue;
                fresh.add(new BounceRow(queued.type, queued.title, queued.value,
                        bounceRowArrivedAt(now, queued.type, queued.title, queued.value),
                        queued.expiresAt));
            }
        }
        return bounceSwapRows(fresh);
    }

    /** 上一帧同一行文案的到达时刻；没对上就当作本帧新到。 */
    private long bounceRowArrivedAt(long now, IslandNoticeType type, String title, String value) {
        String key = BounceRow.key(type, title, value);
        for (int i = 0; i < bounceRows.size(); i++) {
            BounceRow previous = bounceRows.get(i);
            if (key.equals(previous.key)) return previous.arrivedAt;
        }
        return now;
    }

    private List<BounceRow> bounceSwapRows(List<BounceRow> fresh) {
        bounceRows.clear();
        bounceRows.addAll(fresh);
        return fresh;
    }

    /** 堆叠态需要的最小宽度：左栏（徽标 / 开关那一列）+ 最宽的一行正文 + 端距。 */
    private double bounceStackWidth(List<BounceRow> rows, double rowScale) {
        if (rows.isEmpty()) return 0.0;
        double ns = BounceIslandMath.NOTICE_TEXT_SCALE;
        double textLeft = (BounceIslandMath.SIDE_PADDING + 31.0) * ns;
        double widest = 0.0;
        for (int i = 0; i < rows.size(); i++) {
            BounceRow row = rows.get(i);
            double titleWidth = FontManager.pf12.getStringWidthD(row.title);
            double valueWidth = FontManager.pf12.getStringWidthD(row.value);
            widest = Math.max(widest, Math.max(titleWidth, valueWidth) * rowScale);
        }
        return textLeft + widest + 8.0 * ns;
    }

    /** 静息态那一行（品牌 + 各枚常驻数值）按「短横线串接」排版量出来的宽度。 */
    private double bounceIdleRowWidth(IslandAmbientStatus.Snapshot ambient, double rowScale) {
        if (ambient == null || ambient.isEmpty()) return 0.0;
        double width = BounceIslandMath.SIDE_PADDING + BounceIslandMath.LOGO_AREA_WIDTH
                + FontManager.pf12.getStringWidthD(BOUNCE_BRAND) * rowScale;
        for (int i = 0; i < ambient.chips.length; i++) {
            width += bounceDividerWidth() + measureAmbientChip(ambient.chips[i]) * rowScale;
        }
        return width + BounceIslandMath.SIDE_PADDING;
    }

    private double bounceDividerWidth() {
        return BounceIslandMath.DIVIDER_GAP * 2.0 + FontManager.pf12.getStringWidthD("-");
    }

    /** 堆叠通知的一行。到达时刻跨帧保留，用于逐行淡入淡出。 */
    private static final class BounceRow {
        final IslandNoticeType type;
        final String title;
        final String value;
        final long arrivedAt;
        /** 到期时刻；{@code <= 0} 表示这条没有时效（常驻卡片、音量这类）。 */
        final long expiresAt;
        final String key;

        BounceRow(IslandNoticeType type, String title, String value, long arrivedAt, long expiresAt) {
            this.type = type;
            this.title = title;
            this.value = value;
            this.arrivedAt = arrivedAt;
            this.expiresAt = expiresAt;
            this.key = key(type, title, value);
        }

        static String key(IslandNoticeType type, String title, String value) {
            return type.name() + '\u0000' + title + '\u0000' + value;
        }
    }

    /** 事件语义 -> 底色分类。参考稿是「内容决定整座岛的气质」，不是只染一个图标。 */
    private static int bounceTintCategory(boolean noticeMode, IslandNoticeType type,
                                          boolean progressMode, boolean completed) {
        if (completed) return BounceIslandMath.TINT_CATEGORY_SUCCESS;
        if (progressMode) return BounceIslandMath.TINT_CATEGORY_PROGRESS;
        if (!noticeMode || type == null) return BounceIslandMath.TINT_CATEGORY_NEUTRAL;
        switch (type) {
            case REFRESH_SUCCESS:
            case PLAYLIST_TRACK_ADD_SUCCESS:
            case PLAYLIST_TRACK_ALREADY_EXISTS:
            case TRANSCODE_SUCCESS:
            case NETWORK_SUCCESS:
            case RECENT_PLAY_SUCCESS:
            case LISTENING_DURATION_SUCCESS:
            case GD_SOURCE_SUCCESS:
            case GD_SOURCE_HEALTHY:
            case MODEL_INFERENCE_SUCCESS:
            case FONT_ASSET_SUCCESS:
            case LX_TRANSPILER_SUCCESS:
            case ZSTD_NATIVE_SUCCESS:
                return BounceIslandMath.TINT_CATEGORY_SUCCESS;
            case REFRESH_ERROR:
            case PLAYLIST_TRACK_ADD_ERROR:
            case PLAYBACK_ERROR:
            case NETWORK_ERROR:
            case RECENT_PLAY_ERROR:
            case LISTENING_DURATION_ERROR:
            case GD_SOURCE_ERROR:
            case MODEL_INFERENCE_FAILURE:
            case FONT_ASSET_ERROR:
            case LX_TRANSPILER_ERROR:
            case ZSTD_NATIVE_ERROR:
                return BounceIslandMath.TINT_CATEGORY_DANGER;
            case REFRESHING:
            case PLAYLIST_TRACK_ADDING:
            case TRANSCODING:
            case NETEASE_UPLOADING:
            case GD_SOURCE_LOADING:
            case MODEL_INFERENCE:
            case AUTOMIX_BLENDING:
            case VOLUME:
            case FONT_ASSET:
            case LX_TRANSPILER:
            case ZSTD_NATIVE:
                return BounceIslandMath.TINT_CATEGORY_PROGRESS;
            default:
                return BounceIslandMath.TINT_CATEGORY_NEUTRAL;
        }
    }

    /** 这一行的开关状态：+1 成功/开，-1 失败/关，0 不画开关而画类型字形。 */
    private static int bounceRowState(IslandNoticeType type) {
        switch (type) {
            case REFRESH_SUCCESS:
            case PLAYLIST_TRACK_ADD_SUCCESS:
            case TRANSCODE_SUCCESS:
            case NETWORK_SUCCESS:
            case RECENT_PLAY_SUCCESS:
            case LISTENING_DURATION_SUCCESS:
            case GD_SOURCE_SUCCESS:
            case GD_SOURCE_HEALTHY:
            case MODEL_INFERENCE_SUCCESS:
            case FONT_ASSET_SUCCESS:
            case LX_TRANSPILER_SUCCESS:
            case ZSTD_NATIVE_SUCCESS:
            case PLAY_NEXT:
                return 1;
            case REFRESH_ERROR:
            case PLAYLIST_TRACK_ADD_ERROR:
            case PLAYLIST_TRACK_ALREADY_EXISTS:
            case PLAYBACK_ERROR:
            case NETWORK_ERROR:
            case RECENT_PLAY_ERROR:
            case LISTENING_DURATION_ERROR:
            case GD_SOURCE_ERROR:
            case MODEL_INFERENCE_FAILURE:
            case FONT_ASSET_ERROR:
            case LX_TRANSPILER_ERROR:
            case ZSTD_NATIVE_ERROR:
                return -1;
            default:
                return 0;
        }
    }

    /**
     * 回弹胶囊的整块表面与内容。进来时矩阵已经在 {@code scaleAtPos} 之下，调用方负责 popMatrix。
     */
    private void drawBounceIsland(double x, double y, double width, double height, double radius,
                                  float alpha, double expansionValue, double contentFade, long now,
                                  boolean preview, boolean noticeMode, IslandNoticeType type,
                                  String title, String value, double noticeProgress,
                                  IslandAmbientStatus.Snapshot ambient, float ambientMix,
                                  IslandLayout layout, List<BounceRow> stack, double progress,
                                  float success, double configuredScale, int accentColor) {
        if (alpha <= .01f) return;
        bounceTint.stepTo(bounceTintCategory(noticeMode, type,
                progress > .0001 && !noticeMode, success > .52f), bounceFrameDelta);
        // 参考稿只有一层纯色平面：没有阴影、没有描边、没有顶部高光条。这三样恰恰是
        // 其他七款看起来"精致"的来源，去掉它才像那块屏——所以整款自己走这条分支。
        roundedRect(x, y, width, height, radius, BounceIslandMath.argb(
                (int) Math.round(BounceIslandMath.SURFACE_ALPHA * DynamicIslandMath.clamp01(alpha)),
                bounceTint.redChannel(), bounceTint.greenChannel(), bounceTint.blueChannel()));

        double rowScale = bounceRowTextScale(layout);
        double plainScale = bouncePlainRowTextScale(layout);
        boolean hasAmbient = ambient != null && !ambient.isEmpty();
        float ambientBlendValue = hasAmbient ? DynamicIslandMath.clamp01f(ambientMix) : 0f;
        boolean stacked = noticeMode && !stack.isEmpty();
        float contentAlpha = alpha * (float) DynamicIslandMath.clamp01(contentFade);

        /*
         * 内容无条件裁进当前这一帧的圆角轮廓里。弹簧会过冲，收起过程中胶囊一度比正文还窄，
         * 而 glScissor 吃的是窗口像素、不跟这里的缩放矩阵——所以走裁剪，它和形状记录在同一套
         * 变换下，岛外面那层 scaleAtPos 会被一起带上，位置对得上。
         *
         * 别拿 {@code StencilClipManager.stencilClipping()} 当"能不能裁"的能力门：它的实现是
         * {@code SkijaClip.hasActiveClip()}，语义是"当前已经有一层裁剪生效"，正常情况下恒为
         * false，用它当门等于这段永远不执行。真正的兜底是下面每段文字自己按可用宽度补省略号，
         * 那条一直在，不依赖这层裁剪。
         */
        final double clipX = x;
        final double clipY = y;
        final double clipW = width;
        final double clipH = height;
        final double clipR = radius;
        StencilClipManager.beginClip(() ->
                roundedRect(clipX, clipY, clipW, clipH, clipR, 0xFFFFFFFF));
        drawBounceTray(x, y, alpha, expansionValue, now, preview, noticeMode, type, value,
                ambient, ambientBlendValue, success, accentColor, stacked);
        if (stacked) {
            drawBounceStack(x, y, width, contentAlpha, now, stack, rowScale);
        } else if (ambientBlendValue > .5f && hasAmbient) {
            drawBounceIdleRow(x, y, width, contentAlpha, ambient, plainScale);
        } else {
            // 参考稿的进度行是 0.90 号字，通知行 0.80，静息行 1.00 —— 三档不一样，照抄。
            drawBounceProgressRow(x, y, width, contentAlpha, layout, plainScale * .90, progress,
                    success, noticeProgress);
        }
        StencilClipManager.endClip();
    }

    /**
     * 左侧那一栏。参考稿放的是脚本作者的头像图，这里换成项目自己的矢量品牌图形，
     * 位置、尺寸、底下那层半透明圆角衬板都按它来。
     */
    private void drawBounceTray(double x, double y, float alpha, double expansionValue, long now,
                                boolean preview, boolean noticeMode, IslandNoticeType type, String value,
                                IslandAmbientStatus.Snapshot ambient, float ambientMix, float success,
                                int accentColor, boolean stacked) {
        if (stacked) {
            // 参考稿在通知态是刻意不画左上徽标的：首行自己的开关就落在那一格，
            // 徽标压上去只会被盖掉半张脸。这一条照抄。
            return;
        }
        double iconSize = BounceIslandMath.ICON_SIZE;
        double plateLeft = x + BounceIslandMath.SIDE_PADDING
                + (BounceIslandMath.LOGO_AREA_WIDTH - iconSize) * .5;
        double plateTop = y + (BounceIslandMath.ROW_HEIGHT - iconSize) * .5;
        // 收起态只剩一枚徽标那么宽，托盘按展开度淡入，免得在小胶囊里糊成一团高对比色块。
        float reveal = (float) DynamicIslandMath.smoothStep(DynamicIslandMath.clamp01(expansionValue));
        drawBouncePlate(plateLeft, plateTop, iconSize, alpha * (.35f + .65f * reveal));
        drawBounceGlyph(plateLeft + iconSize * .5, plateTop + iconSize * .5, iconSize,
                alpha * reveal, ambient, ambientMix, type, value, noticeMode, now, preview, success,
                accentColor);
    }

    private void drawBouncePlate(double left, double top, double size, float alpha) {
        if (alpha <= .01f) return;
        double plate = size * .8;
        roundedRect(left + (size - plate) * .5, top + (size - plate) * .5, plate, plate, plate * .5,
                BounceIslandMath.alphaOf(BounceIslandMath.COL_BRAND, alpha * .43f));
    }

    private void drawBounceGlyph(double centerX, double centerY, double size, float alpha,
                                 IslandAmbientStatus.Snapshot ambient, float ambientMix,
                                 IslandNoticeType type, String value, boolean noticeMode, long now,
                                 boolean preview, float success, int accentColor) {
        if (alpha <= .01f) return;
        if (ambientMix > .5f && ambient != null && !ambient.isEmpty()) {
            renderAmbientIcon(centerX, centerY, size, size * .5, .8, alpha, accentColor);
            return;
        }
        if (noticeMode) {
            renderNoticeIcon(type, value, centerX, centerY, alpha, accentColor, now);
            return;
        }
        renderSpinner(centerX, centerY, alpha * (1f - success), accentColor, now, preview);
        if (success > .01f) {
            renderSuccess(centerX, centerY, alpha * success);
        }
    }

    /** 逐行堆叠的通知：每行自己算淡入淡出，行与行之间一条细分隔线。 */
    private void drawBounceStack(double x, double y, double width, float alpha, long now,
                                 List<BounceRow> stack, double rowScale) {
        double rowHeight = BounceIslandMath.ROW_HEIGHT;
        double ns = BounceIslandMath.NOTICE_TEXT_SCALE;
        double textX = x + 38.0 * ns;
        double textRight = x + width - 8.0 * ns;
        for (int i = 0; i < stack.size(); i++) {
            BounceRow row = stack.get(i);
            double rowY = y + i * rowHeight;
            double lifetime = row.expiresAt > 0L ? Math.max(1L, row.expiresAt - row.arrivedAt) : 0L;
            // 没有时效的行（常驻卡片、音量）不淡出，交给整块表面的可见性统一收。
            float rowAlpha = lifetime > 0L
                    ? (float) (alpha * BounceIslandMath.rowAlpha(now - row.arrivedAt, (long) lifetime))
                    : alpha;
            if (rowAlpha <= .01f) continue;
            int state = bounceRowState(row.type);
            double toggleX = x + 7.0 * ns;
            double toggleY = rowY + 13.0 * ns;
            if (state != 0) {
                drawBounceToggle(toggleX, toggleY, state > 0, now - row.arrivedAt, rowAlpha);
            } else {
                drawBouncePlate(toggleX - 2.0 * ns, toggleY - 7.0 * ns, 22.0 * ns, rowAlpha);
            }
            drawBounceLeftText(FontManager.pf12, row.title, textX, rowY + 7.0 * ns, rowScale,
                    BounceIslandMath.alphaOf(BounceIslandMath.COL_TEXT, rowAlpha), textRight - textX);
            int detailColor;
            if (state > 0) {
                detailColor = BounceIslandMath.argb((int) (rowAlpha * 255), 105, 220, 137);
            } else if (state < 0) {
                detailColor = BounceIslandMath.argb((int) (rowAlpha * 255), 240, 105, 112);
            } else {
                detailColor = BounceIslandMath.alphaOf(BounceIslandMath.COL_SECONDARY, rowAlpha * .84f);
            }
            drawBounceLeftText(FontManager.pf12, row.value, textX, rowY + 22.0 * ns, rowScale,
                    detailColor, textRight - textX);
            if (i < stack.size() - 1) {
                roundedRect(textX, rowY + rowHeight - ns, textRight - textX, ns, .5 * ns,
                        BounceIslandMath.alphaOf(BounceIslandMath.COL_TEXT, rowAlpha * .14f));
            }
        }
    }

    /** iOS 那颗开关：轨道 24x14、滑块 10x10，行到达后 280ms 内把滑块推到位。 */
    private void drawBounceToggle(double x, double y, boolean on, long age, float alpha) {
        double ns = BounceIslandMath.NOTICE_TEXT_SCALE;
        double motion = BounceIslandMath.easeOutCubic(age / 280.0);
        double position = on ? motion : 1.0 - motion;
        int track = on ? BounceIslandMath.COL_TRACK_ON : BounceIslandMath.COL_TRACK_OFF;
        roundedRect(x, y, 24.0 * ns, 14.0 * ns, 7.0 * ns,
                BounceIslandMath.alphaOf(track, alpha * .75f));
        double knobX = x + 2.0 * ns + 10.0 * ns * position;
        roundedRect(knobX, y + 2.0 * ns, 10.0 * ns, 10.0 * ns, 5.0 * ns,
                BounceIslandMath.alphaOf(BounceIslandMath.COL_KNOB, alpha));
    }

    /** 静息那一行：品牌 + 各枚常驻数值，用短横线串起来，单行居中在 32px 行高里。 */
    private void drawBounceIdleRow(double x, double y, double width, float alpha,
                                   IslandAmbientStatus.Snapshot ambient, double rowScale) {
        double right = x + width - BounceIslandMath.SIDE_PADDING;
        double textY = y + (BounceIslandMath.ROW_HEIGHT - FontManager.pf12.getFontHeight() * rowScale) * .5;
        double cursor = x + BounceIslandMath.SIDE_PADDING + BounceIslandMath.LOGO_AREA_WIDTH;
        cursor += drawBounceLeftText(FontManager.pf12, BOUNCE_BRAND, cursor, textY, rowScale,
                BounceIslandMath.alphaOf(BounceIslandMath.COL_BRAND, alpha), right - cursor);
        for (int i = 0; i < ambient.chips.length && cursor < right - 6.0; i++) {
            // 放不下就整段丢掉剩下的条目，而不是把最后一条挤成省略号：这一行是状态栏，半句话最没用。
            cursor += BounceIslandMath.DIVIDER_GAP;
            double dividerWidth = drawBounceLeftText(FontManager.pf12, "-", cursor, textY, rowScale,
                    BounceIslandMath.alphaOf(BounceIslandMath.COL_DIVIDER, alpha), right - cursor);
            if (dividerWidth <= 0.0) return;
            cursor += dividerWidth + BounceIslandMath.DIVIDER_GAP;
            IslandAmbientStatus.Chip chip = ambient.chips[i];
            int color;
            if (chip.health == IslandAmbientStatus.Health.BAD) {
                color = BounceIslandMath.argb((int) (alpha * 255), 240, 105, 112);
            } else if (chip.health == IslandAmbientStatus.Health.WARN) {
                color = BounceIslandMath.argb((int) (alpha * 255), 233, 190, 96);
            } else {
                color = BounceIslandMath.alphaOf(BounceIslandMath.COL_IDLE, alpha);
            }
            cursor += drawBounceLeftText(FontManager.pf12, chip.value, cursor, textY, rowScale, color,
                    right - cursor);
            if (!chip.unit.isEmpty()) {
                cursor += drawBounceLeftText(FontManager.pf12, " " + chip.unit, cursor, textY, rowScale,
                        BounceIslandMath.alphaOf(BounceIslandMath.COL_FAINT, alpha * .9f), right - cursor);
            }
        }
    }

    /**
     * 下载/转码那一行：标题 + 百分比同排，下面一条 6px 的进度槽。
     *
     * <p>百分比颜色沿参考稿那条梯度从近白走到青蓝，进度值本身过一层临界阻尼平滑——
     * 网络速度天生跳变，条跟着跳就没有"在推进"的观感了。</p>
     */
    private void drawBounceProgressRow(double x, double y, double width, float alpha, IslandLayout layout,
                                       double rowScale, double progress, float success,
                                       double noticeProgress) {
        double target = DynamicIslandMath.clamp01(noticeProgress > .0001 ? noticeProgress : progress);
        boolean done = success > .52f;
        if (target <= .0001 && !done) {
            bounceProgress.snapTo(0.0);
        } else {
            bounceProgress.stepTo(done ? 1.0 : target, bounceFrameDelta);
        }
        double shown = done ? 1.0 : bounceProgress.value();
        double textX = x + BounceIslandMath.SIDE_PADDING + BounceIslandMath.LOGO_AREA_WIDTH;
        double right = x + width - BounceIslandMath.SIDE_PADDING;
        double textY = y + (BounceIslandMath.ROW_HEIGHT - FontManager.pf12.getFontHeight() * rowScale) * .5;
        String head = done ? "已完成 - " : layout.title + " - ";
        float headAlpha = alpha * (done ? success : 1f - success);
        double headWidth = drawBounceLeftText(FontManager.pf12, head, textX, textY, rowScale,
                BounceIslandMath.alphaOf(BounceIslandMath.COL_TEXT, headAlpha), right - textX);
        String percent = done ? "可以播放了" : Math.max(0, Math.min(100,
                (int) Math.round(shown * 100.0))) + "%";
        drawBounceLeftText(FontManager.pf12, percent, textX + headWidth, textY, rowScale,
                done ? BounceIslandMath.argb((int) (alpha * 255), 105, 220, 137)
                        : BounceIslandMath.percentColor(shown), right - textX - headWidth);
        double barY = y + BounceIslandMath.ROW_HEIGHT - 14.0;
        double barHeight = 6.0;
        double barWidth = Math.max(0.0, right - textX);
        roundedRect(textX, barY, barWidth, barHeight, barHeight * .5,
                BounceIslandMath.alphaOf(BounceIslandMath.COL_BAR_TRACK, alpha));
        double fillWidth = barWidth * shown;
        if (fillWidth > .1) {
            roundedRect(textX, barY, fillWidth, barHeight, Math.min(barHeight * .5, fillWidth * .5),
                    BounceIslandMath.alphaOf(BounceIslandMath.COL_BAR_FILL, alpha));
        }
    }

    /** 左对齐画一段文字，放不下就补省略号；返回这一段的实际绘制宽度，供下一段接着排。 */
    private double drawBounceLeftText(CFontRenderer font, String text, double x, double y,
                                      double scale, int color, double maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 1.0) return 0.0;
        String drawn = trimIslandText(font, text, maxWidth / scale);
        if (drawn.isEmpty()) return 0.0;
        font.drawString(drawn, x, y, scale, color);
        return font.getStringWidthD(drawn) * scale;
    }

    private enum IslandNoticeType {
        NONE,
        THEME,
        STYLE,
        SCALE,
        VOLUME,
        QUALITY,
        REFRESHING,
        REFRESH_SUCCESS,
        REFRESH_ERROR,
        PLAYLIST_TRACK_ADDING,
        PLAYLIST_TRACK_ADD_SUCCESS,
        PLAYLIST_TRACK_ALREADY_EXISTS,
        PLAYLIST_TRACK_ADD_ERROR,
        TRANSCODING,
        TRANSCODE_SUCCESS,
        PLAYBACK_ERROR,
        NETWORK_SUCCESS,
        NETWORK_ERROR,
        NETEASE_UPLOADING,
        RECENT_PLAY_SUCCESS,
        RECENT_PLAY_ERROR,
        LISTENING_DURATION_SUCCESS,
        LISTENING_DURATION_ERROR,
        GD_SOURCE_LOADING,
        GD_SOURCE_SUCCESS,
        GD_SOURCE_HEALTHY,
        GD_SOURCE_ERROR,
        AUTOMIX,
        /** Folia 模型推理生命周期。 */
        MODEL_INFERENCE,
        MODEL_INFERENCE_SUCCESS,
        MODEL_INFERENCE_FAILURE,
        /** 无缝重叠正在进行：两首歌同时出声的那几秒。文案带扫光，和已就绪/开关提示区分开。 */
        AUTOMIX_BLENDING,
        PLAY_NEXT,
        PLAYBACK_TOGGLE,
        /** 完整字体包下载中（持久卡，与 REFRESHING 同形态：转子 + 进度百分比）。 */
        FONT_ASSET,
        /** 完整字体已热启用。 */
        FONT_ASSET_SUCCESS,
        /** 完整字体下载失败（会自动重试）。 */
        FONT_ASSET_ERROR,
        /** LX 脚本转译器下载中（进入自定义源界面触发，持久卡）。 */
        LX_TRANSPILER,
        /** 转译器已就位（下次转译脚本时懒加载）。 */
        LX_TRANSPILER_SUCCESS,
        /** 转译器下载失败（会自动重试，期间转译走原始脚本降级）。 */
        LX_TRANSPILER_ERROR,
        /** 听歌打卡组件（zstd-jni）下载中（登录网易云账号触发，持久卡）。 */
        ZSTD_NATIVE,
        /** 打卡组件已就位（下一轮播放自动恢复上报）。 */
        ZSTD_NATIVE_SUCCESS,
        /** 打卡组件下载失败（会自动重试，期间打卡轮次安静跳过）。 */
        ZSTD_NATIVE_ERROR,
        /** 汽水签名后端伴生包下载中（选择汽水源且本机缺失时触发，持久卡）。 */
        QISHUI_BACKEND,
        /** 汽水签名后端已就绪（进程已拉起、健康检查通过）。 */
        QISHUI_BACKEND_SUCCESS,
        /** 汽水签名后端安装/启动失败（会自动重试，期间汽水源降级试听）。 */
        QISHUI_BACKEND_ERROR,
        /** Apple gamdl 完整版兑换中（点播 Apple 歌且伴生就绪时触发，持久卡）。 */
        APPLE_GAMDL,
        /** Apple 完整版兑换完成（文件已收编进缓存）。 */
        APPLE_GAMDL_SUCCESS,
        /** Apple 兑换失败（随后落换源替身或试听兜底）。 */
        APPLE_GAMDL_ERROR,
        /** 环境选曲按心情自动切歌。 */
        AMBIENT_SCENE
    }


}
