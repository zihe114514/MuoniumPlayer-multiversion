package com.muoniumplayer.core.settings;

/**
 * 描述一个已持久化的浮点 HUD 外观设置。
 *
 * <p>元数据、校验和字段访问都位于设置层，使 UI 表面不必
 * 复制基于 switch 到 {@link HudConfig} 的映射。</p>
 */
public enum HudSetting {
    CURRENT_LINE_SCALE("整行缩放", 0.95f, 1.16f),
    CURRENT_WORD_SCALE("逐字放大", 0.00f, 0.28f),
    CURRENT_GLOW("当前行发光", 0.00f, 1.00f),
    FULLSCREEN_GLOW("全屏逐字发光", 0.00f, 1.00f),
    /**
     * 光晕铺多远。所有歌词共用一个倍率而不是各留一个像素半径：sigma 按字号取比例，
     * 14px 的桌面歌词和 32.5px 的全屏歌词用同一个像素数只会一边糊一边看不见。
     */
    LYRIC_GLOW_SPREAD("发光扩散", 0.50f, 1.80f),
    OSD_TRANSITION("OSD填涂过渡", 4.00f, 32.00f),
    OSD_PULSE("OSD逐字放大", 0.00f, 0.15f),
    OSD_SMOOTHNESS("OSD动画平滑", 0.00f, 1.00f),
    OSD_DUET_SPREAD("OSD对唱分列", 0.00f, 1.00f),
    OSD_WIDTH("桌面歌词宽度", 225.0f, 900.0f),
    OSD_HEIGHT("桌面歌词高度", 60.0f, 480.0f),
    NORMAL_OPACITY("普通透明度", 0.15f, 1.00f),
    NORMAL_SCALE("普通缩放", 0.85f, 1.05f),
    NORMAL_GLOW("其它行发光", 0.00f, 0.65f),
    NORMAL_SPACING("歌词间距", 4.00f, 22.00f),
    EDGE_FADE("淡出高度", 10.00f, 80.00f),
    SCROLL_SMOOTHNESS("滚动柔和", 0.00f, 1.00f),
    SECONDARY_OPACITY("副歌词透明", 0.30f, 1.00f),
    DYNAMIC_ISLAND_SCALE("灵动岛大小", 0.60f, 1.35f),
    DYNAMIC_ISLAND_TEXT_SCALE("灵动岛字体", 0.82f, 1.18f),
    DYNAMIC_ISLAND_MAX_WIDTH("灵动岛基础宽度", 160.0f, 720.0f),
    DYNAMIC_ISLAND_PROGRESS_HEIGHT("进度条粗细", 0.75f, 4.00f),
    DYNAMIC_ISLAND_COMPLETION_HOLD("完成停留", 0.50f, 6.00f),
    DYNAMIC_ISLAND_QUEUE_INTERVAL("排队间隔", 0.50f, 6.00f),
    DYNAMIC_ISLAND_EXPAND_SPEED("弹出速度", 0.40f, 2.50f),
    DYNAMIC_ISLAND_COLLAPSE_SPEED("收起速度", 0.40f, 2.50f),
    DYNAMIC_ISLAND_CONTENT_SPEED("内容切换速度", 0.40f, 2.50f),
    DYNAMIC_ISLAND_ENTRANCE_DURATION("入场时长(ms)", 160.0f, 1200.0f),
    DYNAMIC_ISLAND_OVERSHOOT("入场回弹", 0.00f, 2.00f),
    DYNAMIC_ISLAND_SPINNER_SPEED("加载转速", 0.30f, 3.00f),
    DYNAMIC_ISLAND_AMBIENT_STATUS("常驻状态时长(s)", 2.00f, 30.00f),
    DYNAMIC_ISLAND_AMBIENT_NOW_PLAYING("常驻歌曲时长(s)", 2.00f, 30.00f),

    /** 播放器界面打开/关闭那层整屏淡入淡出的速度倍率。1.0 = 历史默认速度。 */
    PLAYER_FADE_SPEED("界面淡入淡出速度", 0.30f, 3.00f),

    /**
     * 播放器界面内部「栏目切换」类过渡的速度倍率：内容页淡入缩放与侧栏选中指示条的滑动。
     * 1.25 = 出厂默认（比历史速度略快一档），1.0 = 历史速度。
     */
    PLAYER_UI_SWITCH_SPEED("界面切换动画速度", 0.30f, 3.00f),

    /**
     * Folia 那套 HUD 设置界面的整体缩放倍率。1.0 = 一单位一个物理像素（与 dpr=1 的浏览器一致），
     * 越大面板与文字一起放大。见 {@link HudConfig#foliaUiScale}。
     */
    FOLIA_UI_SCALE("设置界面缩放", 0.80f, 2.50f),
    /** 歌词搜索弹窗那一页的整体缩放倍率，与 {@link #FOLIA_UI_SCALE} 同档区间、同一个意思。 */
    LYRIC_SEARCH_UI_SCALE("弹窗界面缩放", 0.80f, 2.50f),

    // 移植自 本项目 的各套「文字 PV」全屏歌词样式参数。区间取参考实现的可调范围。
    LYRIC_FONT_SCALE("歌词字号", 0.60f, 1.80f),
    LUMINOUS_WORD_SPACING("词距", 0.20f, 1.60f),
    LUMINOUS_FLOAT("浮动幅度", 0.00f, 2.00f),
    PARTITA_STAGGER_MIN("错落下限", 0.00f, 90.00f),
    PARTITA_STAGGER_MAX("错落上限", 20.00f, 220.00f),
    FUME_TEXT_HOLD("余韵留存", 0.40f, 2.50f),
    FUME_CAMERA_SPEED("镜头速度", 0.40f, 2.50f),
    FUME_HERO_SCALE("主行字号", 0.70f, 1.50f),
    CAPPELLA_BUBBLE_WIDTH("气泡宽度", 0.50f, 0.95f),
    CAPPELLA_EMOJI_CHANCE("表情频率", 0.00f, 0.45f),
    TILT_SPLIT("分段概率", 0.00f, 1.00f),
    TILT_STYLE("倾斜概率", 0.00f, 1.00f),
    DIORAMA_DEPTH("景深间隔", 0.40f, 2.00f),
    PENDOLO_ARC_RADIUS("弧半径", 0.25f, 0.70f),
    PENDOLO_ARC_ANGLE("弧张角", 60.00f, 160.00f),
    PENDOLO_ACTIVE_SCALE("当前行放大", 1.00f, 1.60f),

    // ---- 桌面歌词 Blossom 样式 ----
    BLOSSOM_LINES("可见行数", 1.00f, 5.00f),
    BLOSSOM_BLUR("面板模糊", 0.00f, 1.00f),
    BLOSSOM_OPACITY("面板底色", 0.00f, 1.00f),
    BLOSSOM_BORDER("踩点描边", 0.00f, 1.00f),
    // 真玻璃材质三项。区间与 HudConfig 读档时的钳位一致，改一边要改另一边。
    /** 背后画面糊多狠，单位 GUI 像素的 sigma；0 就是不糊，只压暗。 */
    GLASS_BLUR("玻璃模糊", 0.00f, 32.00f),
    /** 玻璃自身颜色多浓：0 完全透出背景，1 基本看不见背景。 */
    GLASS_TINT("玻璃浓度", 0.00f, 1.00f),
    /** 边缘那圈光的强度。 */
    GLASS_EDGE_GLOW("玻璃边缘光", 0.00f, 1.50f),
    // 四处界面各自的模糊倍率：乘在 GLASS_BLUR 那一档之上，1 = 与全局同档。
    /** 播放器主面板的模糊倍率。 */
    GLASS_BLUR_PLAYER("面板模糊倍率", 0.25f, 2.00f),
    /** 灵动岛的模糊倍率。岛小，默认比面板轻。 */
    GLASS_BLUR_ISLAND("灵动岛模糊倍率", 0.25f, 2.00f),
    /** 桌面歌词卡片的模糊倍率。 */
    GLASS_BLUR_LYRIC("歌词卡模糊倍率", 0.25f, 2.00f),
    /** 三套 HUD 编辑器的模糊倍率。 */
    GLASS_BLUR_EDITORS("编辑器模糊倍率", 0.25f, 2.00f),
    /** 亮部溢出：玻璃后面那团亮的东西往面内晕开多少。0 = 整条链路一格都不画。 */
    GLASS_BLOOM("玻璃亮部溢出", 0.00f, 1.00f),
    /**
     * 相邻玻璃的融合半径，画布局部像素。0 = 每块各画各的边。
     *
     * <p>接在枚举<em>末尾</em>：这一套条目的 id 有按序号落盘的历史，插在中间会让老配置的
     * 滑块对错位，症状是「用户没动过的设置变了值」。</p>
     */
    GLASS_FUSION("玻璃融合半径", 0.00f, 32.00f),
    SPECTRUM_OPACITY("频谱透明度", 0.15f, 0.85f),
    SPECTRUM_HEIGHT("频谱高度", 0.50f, 1.50f),
    SPECTRUM_BAR_COUNT_CUSTOM("自定义频谱柱数", 48.0f, 256.0f),
    // ---- 3D 世界歌词（接在末尾：这套条目的 id 有按序号落盘的历史，插中间会让老配置错位）----
    WORLD_LYRICS_DISTANCE("3D歌词 距离", 3.0f, 48.0f),
    WORLD_LYRICS_SPREAD("3D歌词 散开角", 0.0f, 60.0f),
    WORLD_LYRICS_SCALE("3D歌词 缩放", 0.4f, 6.0f),
    WORLD_LYRICS_GLOW_STRENGTH("3D歌词 发光强度", 0.00f, 1.00f),
    WORLD_LYRICS_GLOW_SPREAD("3D歌词 发光扩散", 0.50f, 1.80f);

    private final String label;
    private final float min;
    private final float max;

    HudSetting(String label, float min, float max) {
        this.label = label;
        this.min = min;
        this.max = max;
    }

    public String getLabel() {
        return label;
    }

    public float getMin() {
        return min;
    }

    public float getMax() {
        return max;
    }

    public float getValue() {
        switch (this) {
            case CURRENT_LINE_SCALE: return HudConfig.currentLineScale;
            case CURRENT_WORD_SCALE: return HudConfig.currentWordScale;
            case CURRENT_GLOW: return HudConfig.currentGlowStrength;
            case FULLSCREEN_GLOW: return HudConfig.fullscreenGlowStrength;
            case LYRIC_GLOW_SPREAD: return HudConfig.lyricGlowSpread;
            case OSD_TRANSITION: return HudConfig.osdKaraokeTransitionWidth;
            case OSD_PULSE: return HudConfig.osdKaraokePulseStrength;
            case OSD_SMOOTHNESS: return HudConfig.osdKaraokeSmoothing;
            case OSD_DUET_SPREAD: return HudConfig.osdDuetSpread;
            case OSD_WIDTH: return HudConfig.lyricWidth;
            case OSD_HEIGHT: return HudConfig.lyricHeight;
            case NORMAL_OPACITY: return HudConfig.normalOpacity;
            case NORMAL_SCALE: return HudConfig.normalScale;
            case NORMAL_GLOW: return HudConfig.normalGlowStrength;
            case NORMAL_SPACING: return HudConfig.normalLineSpacing;
            case EDGE_FADE: return HudConfig.edgeFadeSize;
            case SCROLL_SMOOTHNESS: return HudConfig.scrollSmoothness;
            case SECONDARY_OPACITY: return HudConfig.secondaryOpacity;
            case DYNAMIC_ISLAND_SCALE: return HudConfig.dynamicIslandScale;
            case DYNAMIC_ISLAND_TEXT_SCALE: return HudConfig.dynamicIslandTextScale;
            case DYNAMIC_ISLAND_MAX_WIDTH: return HudConfig.dynamicIslandMaxWidth;
            case DYNAMIC_ISLAND_PROGRESS_HEIGHT: return HudConfig.dynamicIslandProgressHeight;
            case DYNAMIC_ISLAND_COMPLETION_HOLD: return HudConfig.dynamicIslandCompletionHoldSeconds;
            case DYNAMIC_ISLAND_QUEUE_INTERVAL: return HudConfig.dynamicIslandQueueIntervalSeconds;
            case DYNAMIC_ISLAND_EXPAND_SPEED: return HudConfig.dynamicIslandExpandSpeed;
            case DYNAMIC_ISLAND_COLLAPSE_SPEED: return HudConfig.dynamicIslandCollapseSpeed;
            case DYNAMIC_ISLAND_CONTENT_SPEED: return HudConfig.dynamicIslandContentSpeed;
            case DYNAMIC_ISLAND_ENTRANCE_DURATION: return HudConfig.dynamicIslandEntranceDuration;
            case DYNAMIC_ISLAND_OVERSHOOT: return HudConfig.dynamicIslandOvershoot;
            case DYNAMIC_ISLAND_SPINNER_SPEED: return HudConfig.dynamicIslandSpinnerSpeed;
            case DYNAMIC_ISLAND_AMBIENT_STATUS: return HudConfig.dynamicIslandAmbientStatusSeconds;
            case DYNAMIC_ISLAND_AMBIENT_NOW_PLAYING: return HudConfig.dynamicIslandAmbientNowPlayingSeconds;
            case PLAYER_FADE_SPEED: return HudConfig.playerFadeSpeed;
            case PLAYER_UI_SWITCH_SPEED: return HudConfig.playerUiSwitchSpeed;
            case FOLIA_UI_SCALE: return HudConfig.foliaUiScale;
            case LYRIC_SEARCH_UI_SCALE: return HudConfig.lyricSearchUiScale;
            case LYRIC_FONT_SCALE: return HudConfig.lyricFontScale;
            case LUMINOUS_WORD_SPACING: return HudConfig.luminousWordSpacing;
            case LUMINOUS_FLOAT: return HudConfig.luminousFloatAmount;
            case PARTITA_STAGGER_MIN: return HudConfig.partitaStaggerMin;
            case PARTITA_STAGGER_MAX: return HudConfig.partitaStaggerMax;
            case FUME_TEXT_HOLD: return HudConfig.fumeTextHoldRatio;
            case FUME_CAMERA_SPEED: return HudConfig.fumeCameraSpeed;
            case FUME_HERO_SCALE: return HudConfig.fumeHeroScale;
            case CAPPELLA_BUBBLE_WIDTH: return HudConfig.cappellaBubbleWidth;
            case CAPPELLA_EMOJI_CHANCE: return HudConfig.cappellaEmojiChance;
            case TILT_SPLIT: return HudConfig.tiltSplitProbability;
            case TILT_STYLE: return HudConfig.tiltStyleProbability;
            case DIORAMA_DEPTH: return HudConfig.dioramaDepthSpread;
            case PENDOLO_ARC_RADIUS: return HudConfig.pendoloArcRadius;
            case PENDOLO_ARC_ANGLE: return HudConfig.pendoloArcAngle;
            case PENDOLO_ACTIVE_SCALE: return HudConfig.pendoloActiveScale;
            case BLOSSOM_LINES: return HudConfig.blossomVisibleLines;
            case BLOSSOM_BLUR: return HudConfig.blossomBackdropBlur;
            case BLOSSOM_OPACITY: return HudConfig.blossomPanelOpacity;
            case BLOSSOM_BORDER: return HudConfig.blossomBorderStrength;
            case GLASS_BLUR: return HudConfig.glassBlurSigma;
            case GLASS_TINT: return HudConfig.glassTintStrength;
            case GLASS_EDGE_GLOW: return HudConfig.glassEdgeGlow;
            case GLASS_BLUR_PLAYER: return HudConfig.glassBlurPlayer;
            case GLASS_BLUR_ISLAND: return HudConfig.glassBlurIsland;
            case GLASS_BLUR_LYRIC: return HudConfig.glassBlurDesktopLyric;
            case GLASS_BLUR_EDITORS: return HudConfig.glassBlurEditors;
            case GLASS_BLOOM: return HudConfig.glassBloom;
            case GLASS_FUSION: return HudConfig.glassFusion;
            case SPECTRUM_OPACITY: return HudConfig.spectrumOpacity;
            case SPECTRUM_HEIGHT: return HudConfig.spectrumHeight;
            case SPECTRUM_BAR_COUNT_CUSTOM: return HudConfig.spectrumCustomBarCount;
            case WORLD_LYRICS_DISTANCE: return HudConfig.worldLyricsDistance;
            case WORLD_LYRICS_SPREAD: return HudConfig.worldLyricsSpreadDeg;
            case WORLD_LYRICS_SCALE: return HudConfig.worldLyricsScale;
            case WORLD_LYRICS_GLOW_STRENGTH: return HudConfig.worldLyricsGlowStrength;
            case WORLD_LYRICS_GLOW_SPREAD: return HudConfig.worldLyricsGlowSpread;
            default: throw new IllegalStateException("Unknown HUD setting: " + this);
        }
    }

    public void setValue(float value) {
        float clamped = Math.max(min, Math.min(max, value));
        switch (this) {
            case CURRENT_LINE_SCALE: HudConfig.currentLineScale = clamped; break;
            case CURRENT_WORD_SCALE: HudConfig.currentWordScale = clamped; break;
            case CURRENT_GLOW: HudConfig.currentGlowStrength = clamped; break;
            case FULLSCREEN_GLOW: HudConfig.fullscreenGlowStrength = clamped; break;
            case LYRIC_GLOW_SPREAD: HudConfig.lyricGlowSpread = clamped; break;
            case OSD_TRANSITION: HudConfig.osdKaraokeTransitionWidth = clamped; break;
            case OSD_PULSE: HudConfig.osdKaraokePulseStrength = clamped; break;
            case OSD_SMOOTHNESS: HudConfig.osdKaraokeSmoothing = clamped; break;
            case OSD_DUET_SPREAD: HudConfig.osdDuetSpread = clamped; break;
            case OSD_WIDTH: HudConfig.lyricWidth = clamped; break;
            case OSD_HEIGHT: HudConfig.lyricHeight = clamped; break;
            case NORMAL_OPACITY: HudConfig.normalOpacity = clamped; break;
            case NORMAL_SCALE: HudConfig.normalScale = clamped; break;
            case NORMAL_GLOW: HudConfig.normalGlowStrength = clamped; break;
            case NORMAL_SPACING: HudConfig.normalLineSpacing = clamped; break;
            case EDGE_FADE: HudConfig.edgeFadeSize = clamped; break;
            case SCROLL_SMOOTHNESS: HudConfig.scrollSmoothness = clamped; break;
            case SECONDARY_OPACITY: HudConfig.secondaryOpacity = clamped; break;
            case DYNAMIC_ISLAND_SCALE: HudConfig.dynamicIslandScale = clamped; break;
            case DYNAMIC_ISLAND_TEXT_SCALE: HudConfig.dynamicIslandTextScale = clamped; break;
            case DYNAMIC_ISLAND_MAX_WIDTH: HudConfig.dynamicIslandMaxWidth = clamped; break;
            case DYNAMIC_ISLAND_PROGRESS_HEIGHT: HudConfig.dynamicIslandProgressHeight = clamped; break;
            case DYNAMIC_ISLAND_COMPLETION_HOLD: HudConfig.dynamicIslandCompletionHoldSeconds = clamped; break;
            case DYNAMIC_ISLAND_QUEUE_INTERVAL: HudConfig.dynamicIslandQueueIntervalSeconds = clamped; break;
            case DYNAMIC_ISLAND_EXPAND_SPEED: HudConfig.dynamicIslandExpandSpeed = clamped; break;
            case DYNAMIC_ISLAND_COLLAPSE_SPEED: HudConfig.dynamicIslandCollapseSpeed = clamped; break;
            case DYNAMIC_ISLAND_CONTENT_SPEED: HudConfig.dynamicIslandContentSpeed = clamped; break;
            case DYNAMIC_ISLAND_ENTRANCE_DURATION: HudConfig.dynamicIslandEntranceDuration = clamped; break;
            case DYNAMIC_ISLAND_OVERSHOOT: HudConfig.dynamicIslandOvershoot = clamped; break;
            case DYNAMIC_ISLAND_SPINNER_SPEED: HudConfig.dynamicIslandSpinnerSpeed = clamped; break;
            case DYNAMIC_ISLAND_AMBIENT_STATUS: HudConfig.dynamicIslandAmbientStatusSeconds = clamped; break;
            case DYNAMIC_ISLAND_AMBIENT_NOW_PLAYING: HudConfig.dynamicIslandAmbientNowPlayingSeconds = clamped; break;
            case PLAYER_FADE_SPEED: HudConfig.playerFadeSpeed = clamped; break;
            case PLAYER_UI_SWITCH_SPEED: HudConfig.playerUiSwitchSpeed = clamped; break;
            case FOLIA_UI_SCALE: HudConfig.foliaUiScale = clamped; break;
            case LYRIC_SEARCH_UI_SCALE: HudConfig.lyricSearchUiScale = clamped; break;
            case LYRIC_FONT_SCALE: HudConfig.lyricFontScale = clamped; break;
            case LUMINOUS_WORD_SPACING: HudConfig.luminousWordSpacing = clamped; break;
            case LUMINOUS_FLOAT: HudConfig.luminousFloatAmount = clamped; break;
            // 上下限互不越过：拖上限低于下限会让「区间随机」退化成常量，看起来像随机失效。
            case PARTITA_STAGGER_MIN: HudConfig.partitaStaggerMin = Math.min(clamped, HudConfig.partitaStaggerMax); break;
            case PARTITA_STAGGER_MAX: HudConfig.partitaStaggerMax = Math.max(clamped, HudConfig.partitaStaggerMin); break;
            case FUME_TEXT_HOLD: HudConfig.fumeTextHoldRatio = clamped; break;
            case FUME_CAMERA_SPEED: HudConfig.fumeCameraSpeed = clamped; break;
            case FUME_HERO_SCALE: HudConfig.fumeHeroScale = clamped; break;
            case CAPPELLA_BUBBLE_WIDTH: HudConfig.cappellaBubbleWidth = clamped; break;
            case CAPPELLA_EMOJI_CHANCE: HudConfig.cappellaEmojiChance = clamped; break;
            case TILT_SPLIT: HudConfig.tiltSplitProbability = clamped; break;
            case TILT_STYLE: HudConfig.tiltStyleProbability = clamped; break;
            case DIORAMA_DEPTH: HudConfig.dioramaDepthSpread = clamped; break;
            case PENDOLO_ARC_RADIUS: HudConfig.pendoloArcRadius = clamped; break;
            case PENDOLO_ARC_ANGLE: HudConfig.pendoloArcAngle = clamped; break;
            case PENDOLO_ACTIVE_SCALE: HudConfig.pendoloActiveScale = clamped; break;
            // 行数是整数：滑块给的是浮点，四舍五入而不是截断，拖到 2.9 时读数与效果才一致。
            case BLOSSOM_LINES: HudConfig.blossomVisibleLines = Math.round(clamped); break;
            case BLOSSOM_BLUR: HudConfig.blossomBackdropBlur = clamped; break;
            case BLOSSOM_OPACITY: HudConfig.blossomPanelOpacity = clamped; break;
            case BLOSSOM_BORDER: HudConfig.blossomBorderStrength = clamped; break;
            case GLASS_BLUR: HudConfig.glassBlurSigma = clamped; break;
            case GLASS_TINT: HudConfig.glassTintStrength = clamped; break;
            case GLASS_EDGE_GLOW: HudConfig.glassEdgeGlow = clamped; break;
            case GLASS_BLUR_PLAYER: HudConfig.glassBlurPlayer = clamped; break;
            case GLASS_BLUR_ISLAND: HudConfig.glassBlurIsland = clamped; break;
            case GLASS_BLUR_LYRIC: HudConfig.glassBlurDesktopLyric = clamped; break;
            case GLASS_BLUR_EDITORS: HudConfig.glassBlurEditors = clamped; break;
            case GLASS_BLOOM: HudConfig.glassBloom = clamped; break;
            case GLASS_FUSION: HudConfig.glassFusion = clamped; break;
            case SPECTRUM_OPACITY: HudConfig.spectrumOpacity = clamped; break;
            case SPECTRUM_HEIGHT: HudConfig.spectrumHeight = clamped; break;
            case SPECTRUM_BAR_COUNT_CUSTOM:
                HudConfig.spectrumCustomBarCount = Math.max(48, Math.min(256, Math.round(clamped)));
                break;
            case WORLD_LYRICS_DISTANCE: HudConfig.worldLyricsDistance = clamped; break;
            case WORLD_LYRICS_SPREAD: HudConfig.worldLyricsSpreadDeg = clamped; break;
            case WORLD_LYRICS_SCALE: HudConfig.worldLyricsScale = clamped; break;
            case WORLD_LYRICS_GLOW_STRENGTH: HudConfig.worldLyricsGlowStrength = clamped; break;
            case WORLD_LYRICS_GLOW_SPREAD: HudConfig.worldLyricsGlowSpread = clamped; break;
            default: throw new IllegalStateException("Unknown HUD setting: " + this);
        }
    }
}
