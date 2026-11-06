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

        void persist();
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
            case "getScale":
                return hooks.getScale();
            case "setScale":
                hooks.setScale((Float) args[0]);
                return null;
            case "isHidden":
                return Boolean.FALSE;
            case "setHidden":
                return null;
            case "persist":
                hooks.persist();
                return null;
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
        public void persist() {
            HudConfig.save();
        }
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
        public void persist() {
            HudConfig.save();
        }
    }
}
