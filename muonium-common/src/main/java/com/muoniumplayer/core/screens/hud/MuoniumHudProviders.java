package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.core.settings.HudConfig;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 把 MUSIC 的两块 HUD 叠加层接进 MuoniumConfig 的 HUD 编辑器。
 *
 * <p>MuoniumConfig 是 OneConfig 的精简分支（同级目录 {@code E:\oneconfig lite\MuoniumConfig-src}）。
 * 它的编辑器要的是「屏幕像素坐标 + 宿主自己负责绘制的外框」；MUSIC 这边的坐标是
 * {@code [0,1]} 归一化比例（0 = 贴左/上，1 = 贴右/下，分母是屏幕减去自身尺寸后的可移动余量）。
 * 两边换算各写一份的话，拖动会漂移、预设切换会错位，且错得很隐蔽 —— 所以换算统一由
 * MuoniumConfig 的 {@code NormalizedHudProvider} 做，本类只把 {@link HudConfig} 的字段接上去。</p>
 *
 * <p><b>为什么走反射而不是编译期依赖：</b>MuoniumConfig 是可选依赖。编译期挂上去，未安装它的
 * 玩家会在加载阶段因 {@code NoClassDefFoundError} 起不来；而本仓纪律是「可选集成必须能优雅退化成
 * 内置界面」（见 {@code docs/oneconfig-integration.md}）。反射的代价是一次类名查找，换来装不装都能启动。</p>
 *
 * <p>位置与缩放的持久化交给 MuoniumConfig：它按 profile 存进
 * {@code profiles/<name>/compat-snapshots.json}，所以切换预设时 HUD 位置天然各套独立。
 * {@link Hooks#persist()} 仍然调 {@code HudConfig.save()}，是为了 MUSIC 自己的 {@code hud.json}
 * 不至于和屏幕上看到的差太远。</p>
 *
 * <p>只注册两块：歌词卡与信息卡。灵动岛与频谱没有位置字段，编辑器里不参与拖动，
 * 与 {@link HudOverlayStage} 的口径一致。</p>
 */
public final class MuoniumHudProviders {

    /** 信息卡基准尺寸，与 {@code GuiHudEditorPro} / {@code MConfigHudScreen} 同源。 */
    public static final int INFO_BASE_W = 230;
    public static final int INFO_BASE_H = 56;

    public static final String LYRICS_ID = "muonium.lyrics";
    public static final String LYRICS_NAME = "桌面歌词";
    public static final String INFO_ID = "muonium.info";
    public static final String INFO_NAME = "歌曲信息卡";

    private static final String PROVIDER_CLASS =
            "org.polyfrost.oneconfig.api.hud.v1.NormalizedHudProvider";

    private static Boolean available;
    private static boolean registered;
    private static boolean failed;
    private static Object lyricsProvider;
    private static Object infoProvider;

    private MuoniumHudProviders() {
    }

    /** MuoniumConfig 是否装载了 HUD 接入 API。缓存结果，第一次调用后再问不再走反射。 */
    public static boolean isAvailable() {
        if (available != null) return available;
        try {
            Class.forName(PROVIDER_CLASS, false, MuoniumHudProviders.class.getClassLoader());
            available = Boolean.TRUE;
        } catch (Throwable absent) {
            available = Boolean.FALSE;
        }
        return available;
    }

    /**
     * 注册两块 HUD。重复调用安全；MuoniumConfig 不在时静默返回 false。
     *
     * @return 是否真的注册上了（或本就已经注册过）
     */
    public static synchronized boolean registerAll() {
        if (registered) return true;
        if (failed) return false;
        if (!isAvailable()) {
            failed = true;
            return false;
        }

        try {
            configureUiFont();
            lyricsProvider = createProvider(new LyricsHooks(), LYRICS_ID, LYRICS_NAME);
            infoProvider = createProvider(new InfoHooks(), INFO_ID, INFO_NAME);
            registered = true;
            return true;
        } catch (Throwable t) {
            failed = true;
            return false;
        }
    }

    /**
     * 把播放器的字体目录交给 MuoniumConfig，让它的设置界面用同一套字。
     *
     * <p>MuoniumConfig 不再打包字体（原先 8.1 MB 的 Poppins + unifont）：改为运行时读宿主
     * 这一份，宿主那份已含 CJK，界面不必再背一遍。</p>
     *
     * <p>必须在 MuoniumConfig 装配界面字体之前完成，所以放在这里 —— 本方法由 client init
     * 调用，早于它的 {@code McFontService.init()}。晚于那一步只影响下次启动。</p>
     *
     * <p>失败不影响 HUD 注册：字体只是锦上添花，缺了界面退回 Compose 自带字形。</p>
     */
    private static void configureUiFont() {
        try {
            Class<?> files = Class.forName(
                    "org.polyfrost.oneconfig.internal.ui.font.UiFontFiles", true,
                    MuoniumHudProviders.class.getClassLoader());
            files.getMethod("set", java.io.File.class, String.class, String.class)
                    .invoke(null,
                            com.muoniumplayer.core.settings.ConfigPaths.FONT_DIR,
                            "pf_normal.ttf",
                            "pf_middleblack.ttf");
        } catch (Throwable ignored) {
            // MuoniumConfig 版本对不上、或字体目录缺失：交给它自己回退
        }
    }

    /**
     * 宿主字段的读写钩子。用它把 {@link HudConfig} 的字段名留在本类里，
     * 反射那侧只按方法名转发，不必把字段名写成字符串。
     */
    private interface Hooks {
        float getNormalizedX();

        void setNormalizedX(float value);

        float getNormalizedY();

        void setNormalizedY(float value);

        float getWidth();

        float getHeight();

        float getScale();

        void setScale(float value);

        /**
         * 拖拽手柄给出的屏幕像素宽度。
         *
         * <p>MUSIC 存的是「未缩放」的 {@code lyricWidth}，屏幕上看到的是它乘上
         * {@code lyricScale}。编辑器给的是后者，所以这里要除掉缩放再写回，
         * 否则缩放不为 1 时拖一下尺寸就会飞。</p>
         */
        void setWidth(float value);

        void setHeight(float value);

        /** 编辑器里那个眼睛图标的状态。true = 玩家不希望这块 HUD 出现在画面上。 */
        boolean isHidden();

        /**
         * 设置显隐。
         *
         * <p>必须真的动渲染层的开关（{@code ExtensionModule.setEnabled}），不能只记个标记：
         * HUD 是从宿主自己的渲染循环画出去的，MuoniumConfig 那边收不到「不要画」的通知。</p>
         */
        void setHidden(boolean value);

        void persist();

        /** 编辑器右侧面板里的快捷配置项；没有就返回空列表。 */
        java.util.List<Object> linkedProperties();
    }

    /**
     * 用 {@link Proxy} 实现 {@code NormalizedHudProvider}（它被刻意做成接口，就是为了这一步），
     * 再调它的静态 {@code register(NormalizedHudProvider)}。
     */
    private static Object createProvider(Hooks hooks, String id, String name) throws Exception {
        ClassLoader loader = MuoniumHudProviders.class.getClassLoader();
        Class<?> providerType = Class.forName(PROVIDER_CLASS, true, loader);

        Object provider = Proxy.newProxyInstance(
                providerType.getClassLoader(),
                new Class<?>[]{providerType},
                (proxy, method, args) -> invoke(hooks, id, name, proxy, method, args));

        Method register = providerType.getMethod("register", providerType);
        Object ok = register.invoke(null, provider);
        if (!Boolean.TRUE.equals(ok)) {
            throw new IllegalStateException("MuoniumConfig refused to register " + id);
        }
        return provider;
    }

    private static Object invoke(Hooks hooks, String id, String name, Object proxy,
                                 Method method, Object[] args) throws Throwable {
        switch (method.getName()) {
            case "getId":
                return id;
            case "getName":
                return name;
            case "getModId":
                return "muonium";
            case "getSupportsScale":
                return Boolean.TRUE;
            case "getNormalizedX":
                return hooks.getNormalizedX();
            case "setNormalizedX":
                hooks.setNormalizedX((Float) args[0]);
                return null;
            case "getNormalizedY":
                return hooks.getNormalizedY();
            case "setNormalizedY":
                hooks.setNormalizedY((Float) args[0]);
                return null;
            case "getWidth":
                return hooks.getWidth();
            case "getHeight":
                return hooks.getHeight();
            case "setWidth":
                hooks.setWidth((Float) args[0]);
                return null;
            case "setHeight":
                hooks.setHeight((Float) args[0]);
                return null;
            case "getScale":
                return hooks.getScale();
            case "setScale":
                hooks.setScale((Float) args[0]);
                return null;
            case "isHidden":
                return hooks.isHidden();
            case "setHidden":
                hooks.setHidden(Boolean.TRUE.equals(args[0]));
                return null;
            case "persist":
                hooks.persist();
                return null;
            case "getLinkedProperties":
                java.util.List<Object> props = hooks.linkedProperties();
                System.out.println("[MuoniumPlayer/HudProps] getLinkedProperties -> " + props.size() + " 项");
                return props;
            case "register":
                // 由 createProvider 统一调静态 register，这里不重复注册
                return Boolean.TRUE;
            case "hashCode":
                return System.identityHashCode(hooks);
            case "equals":
                return args != null && args.length == 1 && args[0] == hooks;
            case "toString":
                return "MuoniumHudProvider[" + id + "]";
            default:
                // getAbsoluteX / setAbsoluteX / getAbsoluteY / setAbsoluteY / onEditStart /
                // onEditEnd 都是接口 default 方法，必须交给 JDK 跑真实实现，不能一律回 null ——
                // 回 null 会让 float 返回值拆箱时抛 NPE（编辑器据此判定 HUD 故障并禁用）。
                if (method.isDefault()) {
                    return InvocationHandler.invokeDefault(proxy, method, args);
                }
                return null;
        }
    }

    /** 歌词卡。屏幕尺寸 = lyricWidth/lyricHeight × lyricScale。 */
    private static final class LyricsHooks implements Hooks {
        @Override
        public float getNormalizedX() {
            return HudConfig.lyricX;
        }

        @Override
        public void setNormalizedX(float value) {
            HudConfig.lyricX = value;
        }

        @Override
        public float getNormalizedY() {
            return HudConfig.lyricY;
        }

        @Override
        public void setNormalizedY(float value) {
            HudConfig.lyricY = value;
        }

        @Override
        public float getWidth() {
            return HudConfig.lyricWidth * HudConfig.lyricScale;
        }

        @Override
        public float getHeight() {
            return HudConfig.lyricHeight * HudConfig.lyricScale;
        }

        @Override
        public float getScale() {
            return HudConfig.lyricScale;
        }

        @Override
        public void setScale(float value) {
            HudConfig.lyricScale = value;
        }

        @Override
        public void setWidth(float value) {
            // 编辑器给的是屏幕像素，HudConfig 存的是未缩放值
            float scale = HudConfig.lyricScale;
            HudConfig.lyricWidth = scale > 0.001f ? value / scale : value;
        }

        @Override
        public void setHeight(float value) {
            float scale = HudConfig.lyricScale;
            HudConfig.lyricHeight = scale > 0.001f ? value / scale : value;
        }

        @Override
        public void persist() {
            HudConfig.save();
        }

        @Override
        public boolean isHidden() {
            return !HudConfig.musicLyricsEnabled;
        }

        @Override
        public void setHidden(boolean value) {
            setHudVisible(true, !value);
        }

        @Override
        public java.util.List<Object> linkedProperties() {
            // 编辑器右侧面板的「快捷配置」：把拖位置时最常顺手调的几项放在这里，
            // 全量 89 项仍在 MuoniumPlayer 自己的设置页。
            // 两套样式各有专属项：经典走「逐字填充」那套参数，Blossom 是卡片式，
            // 有毛玻璃背景、可见行数、踩点描边。用 visibleWhen 让面板只显示当前样式
            // 真正生效的栏目 —— 否则玩家在 Blossom 下调「填涂过渡」不会有任何反应。
            java.util.function.BooleanSupplier classic = () -> HudConfig.osdLyricStyle == 0;
            java.util.function.BooleanSupplier blossom = () -> HudConfig.osdLyricStyle == 1;
            // 总开关自身的状态：特效组关掉后，组内那些滑条就没有调节对象了，一并收起
            java.util.function.BooleanSupplier currentFx = () -> HudConfig.currentLyricEffectsEnabled;
            java.util.function.BooleanSupplier normalFx = () -> HudConfig.normalLyricEffectsEnabled;
            java.util.function.BooleanSupplier karaoke = () -> HudConfig.osdKaraokeEmphasisEnabled;

            return MuoniumHudProperties.set()
                    // ---- 显示开关：整块 HUD 的显隐，排在最前 ----
                    .toggle(new MuoniumHudProperties.Toggle(
                            "muonium.lyrics.visible", "显示桌面歌词",
                            "关掉后这块 HUD 完全不再绘制。与「隐藏」不同：隐藏只影响编辑器里的框。",
                            () -> HudConfig.musicLyricsEnabled,
                            v -> setHudVisible(true, v)))
                    // ---- 样式：两档，0 经典 / 1 Blossom（顺序与 HudConfig 的注释一致） ----
                    .choice(new MuoniumHudProperties.Choice(
                            "muonium.lyric.style", "歌词样式",
                            "经典：一行行滚动的纯文字。Blossom：带毛玻璃卡片的歌词卡片。",
                            new String[]{"经典", "Blossom"},
                            () -> clamp(HudConfig.osdLyricStyle, HudConfig.OSD_LYRIC_STYLE_MAX),
                            v -> { HudConfig.osdLyricStyle = v; HudConfig.save(); }))
                    // ---- 两套样式共用 ----
                    .slider(new MuoniumHudProperties.Slider(
                            "muonium.lyric.font", "歌词字号", "桌面歌词的基础字号。",
                            0.60f, 1.80f, 0.01f,
                            () -> HudConfig.lyricFontScale,
                            v -> { HudConfig.lyricFontScale = v; HudConfig.save(); }))
                    .slider(new MuoniumHudProperties.Slider(
                            "muonium.lyric.width", "歌词宽度", "未缩放前的宽度，乘上缩放才是屏幕尺寸。",
                            225.0f, 900.0f, 5.0f,
                            () -> HudConfig.lyricWidth,
                            v -> { HudConfig.lyricWidth = v; HudConfig.save(); }))
                    .slider(new MuoniumHudProperties.Slider(
                            "muonium.lyric.height", "歌词高度", "未缩放前的高度，决定显示几行。",
                            60.0f, 480.0f, 5.0f,
                            () -> HudConfig.lyricHeight,
                            v -> { HudConfig.lyricHeight = v; HudConfig.save(); }))
                    // ---- 特效总开关（组内子项跟着显隐） ----
                    .toggle(new MuoniumHudProperties.Toggle(
                            "muonium.lyric.current.fx", "当前歌词特效",
                            "唱到的字放大、发光。关掉就只剩换色，下面整组调节项一并收起。",
                            () -> HudConfig.currentLyricEffectsEnabled,
                            v -> { HudConfig.currentLyricEffectsEnabled = v; HudConfig.save(); }))
                    .toggle(new MuoniumHudProperties.Toggle(
                            "muonium.lyric.normal.fx", "普通歌词特效",
                            "没唱到的行也发一点光，方便看清位置。关掉就是纯文字。",
                            () -> HudConfig.normalLyricEffectsEnabled,
                            v -> { HudConfig.normalLyricEffectsEnabled = v; HudConfig.save(); }))
                    // ---- 经典专属 ----
                    .slider(new MuoniumHudProperties.Slider(
                            "muonium.lyric.glow", "当前行发光", "正在唱的那一行外发光强度。",
                            0.0f, 1.0f, 0.01f,
                            () -> HudConfig.currentGlowStrength,
                            v -> { HudConfig.currentGlowStrength = v; HudConfig.save(); })
                            .visibleWhen(() -> classic.getAsBoolean() && currentFx.getAsBoolean()))
                    .slider(new MuoniumHudProperties.Slider(
                            "muonium.lyric.spread", "发光扩散", "光晕铺开的范围。歌词越小越该收窄。",
                            0.50f, 1.80f, 0.01f,
                            () -> HudConfig.lyricGlowSpread,
                            v -> { HudConfig.lyricGlowSpread = v; HudConfig.save(); })
                            .visibleWhen(() -> classic.getAsBoolean() && currentFx.getAsBoolean()))
                    .slider(new MuoniumHudProperties.Slider(
                            "muonium.lyric.normal.glow", "其它行发光", "未唱到的行是否也带一层弱发光。",
                            0.0f, 0.65f, 0.01f,
                            () -> HudConfig.normalGlowStrength,
                            v -> { HudConfig.normalGlowStrength = v; HudConfig.save(); })
                            .visibleWhen(() -> classic.getAsBoolean() && normalFx.getAsBoolean()))
                    .slider(new MuoniumHudProperties.Slider(
                            "muonium.lyric.other.opacity", "其它行透明度", "未唱到的歌词的透明度。",
                            0.15f, 1.0f, 0.01f,
                            () -> HudConfig.normalOpacity,
                            v -> { HudConfig.normalOpacity = v; HudConfig.save(); })
                            .visibleWhen(() -> classic.getAsBoolean() && normalFx.getAsBoolean()))
                    .slider(new MuoniumHudProperties.Slider(
                            "muonium.lyric.spacing", "歌词间距", "行与行之间的距离。",
                            4.0f, 22.0f, 0.5f,
                            () -> HudConfig.normalLineSpacing,
                            v -> { HudConfig.normalLineSpacing = v; HudConfig.save(); }).visibleWhen(classic))
                    .slider(new MuoniumHudProperties.Slider(
                            "muonium.lyric.edge.fade", "边缘淡出", "顶部与底部渐隐的高度。",
                            10.0f, 80.0f, 1.0f,
                            () -> HudConfig.edgeFadeSize,
                            v -> { HudConfig.edgeFadeSize = v; HudConfig.save(); }).visibleWhen(classic))
                    .slider(new MuoniumHudProperties.Slider(
                            "muonium.lyric.karaoke.width", "填涂过渡", "逐字填充时，一个字从暗到亮用多宽过渡。",
                            4.0f, 32.0f, 0.5f,
                            () -> HudConfig.osdKaraokeTransitionWidth,
                            v -> { HudConfig.osdKaraokeTransitionWidth = v; HudConfig.save(); })
                            .visibleWhen(() -> classic.getAsBoolean() && karaoke.getAsBoolean()))
                    .slider(new MuoniumHudProperties.Slider(
                            "muonium.lyric.karaoke.pulse", "逐字放大", "当前字的小幅弹出，0 = 不动。",
                            0.0f, 0.15f, 0.005f,
                            () -> HudConfig.osdKaraokePulseStrength,
                            v -> { HudConfig.osdKaraokePulseStrength = v; HudConfig.save(); })
                            .visibleWhen(() -> classic.getAsBoolean() && karaoke.getAsBoolean()))
                    .slider(new MuoniumHudProperties.Slider(
                            "muonium.lyric.karaoke.smooth", "动画平滑", "逐字进度的跟随速度，越大越迟钝。",
                            0.0f, 1.0f, 0.01f,
                            () -> HudConfig.osdKaraokeSmoothing,
                            v -> { HudConfig.osdKaraokeSmoothing = v; HudConfig.save(); })
                            .visibleWhen(() -> classic.getAsBoolean() && karaoke.getAsBoolean()))
                    .toggle(new MuoniumHudProperties.Toggle(
                            "muonium.lyric.follow.cover", "颜色跟随封面",
                            "歌词文字颜色随当前封面取色，关掉就用固定色。",
                            () -> HudConfig.osdLyricColorFollowCover,
                            v -> { HudConfig.osdLyricColorFollowCover = v; HudConfig.save(); }).visibleWhen(classic))
                    .toggle(new MuoniumHudProperties.Toggle(
                            "muonium.lyric.edge.fade.enabled", "边缘渐隐",
                            "上下边缘是否渐隐，关掉后歌词直接切边。",
                            () -> HudConfig.lyricEdgeFadeEnabled,
                            v -> { HudConfig.lyricEdgeFadeEnabled = v; HudConfig.save(); }).visibleWhen(classic))
                    // ---- Blossom 专属 ----
                    .slider(new MuoniumHudProperties.Slider(
                            "muonium.lyric.blossom.blur", "背景模糊度", "卡片背后实时模糊的强度。",
                            0.0f, 1.0f, 0.01f,
                            () -> HudConfig.blossomBackdropBlur,
                            v -> { HudConfig.blossomBackdropBlur = v; HudConfig.save(); }).visibleWhen(blossom))
                    .slider(new MuoniumHudProperties.Slider(
                            "muonium.lyric.blossom.opacity", "卡片底色深浅", "卡片深色底的不透明度。",
                            0.0f, 1.0f, 0.01f,
                            () -> HudConfig.blossomPanelOpacity,
                            v -> { HudConfig.blossomPanelOpacity = v; HudConfig.save(); }).visibleWhen(blossom))
                    .slider(new MuoniumHudProperties.Slider(
                            "muonium.lyric.blossom.lines", "可见行数", "卡片里同时显示几行歌词。",
                            1.0f, 7.0f, 1.0f,
                            () -> (float) HudConfig.blossomVisibleLines,
                            v -> { HudConfig.blossomVisibleLines = Math.round(v); HudConfig.save(); }).visibleWhen(blossom))
                    .toggle(new MuoniumHudProperties.Toggle(
                            "muonium.lyric.blossom.rhythm", "踩点描边",
                            "卡片边框跟着鼓点闪。需要开启逐字高亮才有节拍。",
                            () -> HudConfig.blossomRhythmBorder,
                            v -> { HudConfig.blossomRhythmBorder = v; HudConfig.save(); }).visibleWhen(blossom))
                    .slider(new MuoniumHudProperties.Slider(
                            "muonium.lyric.blossom.border", "描边强度", "边框闪多明显。",
                            0.0f, 1.0f, 0.01f,
                            () -> HudConfig.blossomBorderStrength,
                            v -> { HudConfig.blossomBorderStrength = v; HudConfig.save(); })
                            .visibleWhen(() -> HudConfig.osdLyricStyle == 1 && HudConfig.blossomRhythmBorder))
                    .build();
        }
    }
    /**
     * 写「显示开关」，同时更新配置与真正渲染的那个 widget。
     *
     * <p>只写 {@code HudConfig.musicXxxEnabled} 不够：渲染层用的是
     * {@code ExtensionModule.isEnabled()}，两边要一起改，否则会出现「配置里关掉了、
     * 画面上还画着」这种自相矛盾的状态。</p>
     */
    private static void setHudVisible(boolean lyrics, boolean enabled) {
        if (lyrics) {
            HudConfig.musicLyricsEnabled = enabled;
        } else {
            HudConfig.musicInfoEnabled = enabled;
        }
        try {
            com.muoniumplayer.core.MuoniumPlayerExtension ext =
                    com.muoniumplayer.core.MuoniumPlayerExtension.getInstance();
            if (ext != null) {
                if (lyrics) ext.musicLyrics.setEnabled(enabled);
                else ext.musicInfo.setEnabled(enabled);
            }
        } catch (Throwable ignored) {
            // 扩展还没装配好（启动早期）：配置已经写了，下次启动会按它生效
        }
        HudConfig.save();
    }

    /** 把存进配置的序号夹回合法范围：老配置文件里可能留着越界值。 */
    private static int clamp(int value, int max) {
        return value < 0 ? 0 : Math.min(value, max);
    }

    /** 信息卡。没有独立宽高，缩放直接是 infoScale。 */
    private static final class InfoHooks implements Hooks {
        @Override
        public float getNormalizedX() {
            return HudConfig.infoX;
        }

        @Override
        public void setNormalizedX(float value) {
            HudConfig.infoX = value;
        }

        @Override
        public float getNormalizedY() {
            return HudConfig.infoY;
        }

        @Override
        public void setNormalizedY(float value) {
            HudConfig.infoY = value;
        }

        @Override
        public float getWidth() {
            return INFO_BASE_W * HudConfig.infoScale;
        }

        @Override
        public float getHeight() {
            return INFO_BASE_H * HudConfig.infoScale;
        }

        @Override
        public float getScale() {
            return HudConfig.infoScale;
        }

        @Override
        public void setScale(float value) {
            HudConfig.infoScale = value;
        }

        @Override
        public void setWidth(float value) {
            // 信息卡是固定长宽比的卡片：只按宽度推缩放，高度跟着比例走。
            // 两个方向都写会互相覆盖 —— 拖角时编辑器会先给宽后给高，后到的那个赢，
            // 结果就是拖右下角时尺寸只认纵向位移，横向怎么拉都不动。
            if (INFO_BASE_W > 0.001f) HudConfig.infoScale = value / INFO_BASE_W;
        }

        @Override
        public void setHeight(float value) {
            // 只拖上下边时才走这里，换算方式与宽度一致，保证卡片不变形。
            if (INFO_BASE_H > 0.001f) HudConfig.infoScale = value / INFO_BASE_H;
        }

        @Override
        public void persist() {
            HudConfig.save();
        }

        @Override
        public boolean isHidden() {
            return !HudConfig.musicInfoEnabled;
        }

        @Override
        public void setHidden(boolean value) {
            setHudVisible(false, !value);
        }

        @Override
        public java.util.List<Object> linkedProperties() {
            return MuoniumHudProperties.set()
                    // ---- 显示开关：整块 HUD 的显隐，排在最前 ----
                    .toggle(new MuoniumHudProperties.Toggle(
                            "muonium.info.visible", "显示歌曲信息卡",
                            "关掉后这块 HUD 完全不再绘制。与「隐藏」不同：隐藏只影响编辑器里的框。",
                            () -> HudConfig.musicInfoEnabled,
                            v -> setHudVisible(false, v)))
                    // ---- 背景样式：三档，序号存进 infoCardBackdrop ----
                    // 0 默认（封面糊底 + 压暗）/ 1 液态玻璃（真背景折射）/ 2 完全透明。
                    // 顺序与 HudConfig.INFO_CARD_BACKDROP_MAX 的注释一致，中间不能插项。
                    .choice(new MuoniumHudProperties.Choice(
                            "muonium.info.backdrop", "背景样式",
                            "经典：封面模糊铺底再压暗。液态玻璃：折射真实背景。完全透明：只留文字与封面。",
                            new String[]{"经典", "液态玻璃", "完全透明"},
                            () -> clamp(HudConfig.infoCardBackdrop, HudConfig.INFO_CARD_BACKDROP_MAX),
                            v -> { HudConfig.infoCardBackdrop = v; HudConfig.save(); }))
                    // 尺寸不在这里重复提供：编辑器的 SCALE 控件已经绑到 getScale/setScale，
                    // 同一块再放一个「信息卡缩放」滑条，两个控件写同一个 infoScale，
                    // 改哪个另一个都不跟着动（各自持有自己的本地状态），很难解释。
                    // ---- 内容开关 ----
                    .toggle(new MuoniumHudProperties.Toggle(
                            "muonium.info.animated.cover", "动态封面",
                            "封面随播放转动。关掉就是静态封面图。",
                            () -> HudConfig.animatedCoverEnabled,
                            v -> { HudConfig.animatedCoverEnabled = v; HudConfig.save(); }))
                    // ---- 液态玻璃细节：只在选了液态玻璃时有意义 ----
                    .toggle(new MuoniumHudProperties.Toggle(
                            "muonium.info.glass", "启用真玻璃",
                            "总开关。关掉后即使背景样式选了液态玻璃，也退回经典画法。",
                            () -> HudConfig.glassSurfaceDesktopLyric,
                            v -> { HudConfig.glassSurfaceDesktopLyric = v; HudConfig.save(); }))
                    .slider(new MuoniumHudProperties.Slider(
                            "muonium.info.glass.blur", "玻璃模糊度",
                            "液态玻璃的模糊强度。0 = 只做折射不模糊。",
                            0.0f, 2.0f, 0.05f,
                            () -> HudConfig.glassBlurDesktopLyric,
                            v -> { HudConfig.glassBlurDesktopLyric = v; HudConfig.save(); }))
                    .build();
        }
    }
}
