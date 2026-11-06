package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.core.ncm.music.acoustics.AcousticsSettings;
import com.muoniumplayer.core.settings.HudConfig;

/**
 * 往 MuoniumConfig 主界面的「常用」页填 MuoniumPlayer 的高频开关。
 *
 * <p>问题背景：玩家打开设置界面十次有九次是来开关「桌面歌词 / 歌曲信息卡 / 频谱 /
 * 灵动岛 / 场景声学」这几样东西，但它们原本散在 MuoniumPlayer 自己的多个设置页里，
 * 而 MuoniumConfig 的首页是模组卡片列表，对单一宿主的场景没有意义。</p>
 *
 * <p>做法：MuoniumConfig 留了一棵空树（{@code FavoritesTree}），宿主往里 put 属性即可。
 * 这里用反射是为了维持「不编译期依赖 MuoniumConfig」的约束（它是可选组件）。</p>
 */
public final class MuoniumFavorites {

    // 与 NormalizedHudProvider 同一个 jar（modules/hud）。放在 internal 模块会因
    // Fabric 的每 jar 独立类加载器而 ClassNotFoundException —— 宿主拿得到 provider
    // 却拿不到那棵树。
    private static final String FAVORITES_TREE =
            "org.polyfrost.oneconfig.api.hud.v1.FavoritesTree";
    private static final String PROPERTIES = "org.polyfrost.oneconfig.api.config.v1.Properties";
    private static final String NODE = "org.polyfrost.oneconfig.api.config.v1.Node";
    private static final String VISUALIZER = "org.polyfrost.oneconfig.api.config.v1.Visualizer";

    private static boolean registered;
    private static boolean warned;

    private MuoniumFavorites() {
    }

    /** 填充并登记「常用」页。重复调用安全；MuoniumConfig 不在时静默返回。 */
    public static synchronized void register() {
        if (registered) return;
        try {
            ClassLoader loader = MuoniumFavorites.class.getClassLoader();
            Class<?> favoritesType = Class.forName(FAVORITES_TREE, true, loader);
            Class<?> treeType = Class.forName("org.polyfrost.oneconfig.api.config.v1.Tree", true, loader);
            Class<?> propertiesType = Class.forName(PROPERTIES, true, loader);
            Class<?> nodeType = Class.forName(NODE, true, loader);

            Object tree = favoritesType.getMethod("favoriteTree").invoke(null);
            java.lang.reflect.Method put = treeType.getMethod("put", nodeType);
            java.lang.reflect.Method functional = propertiesType.getMethod("functional",
                    java.util.function.Supplier.class, java.util.function.Consumer.class,
                    String.class, Object.class, Object.class, Class.class);
            java.lang.reflect.Method addMetadata = nodeType.getMethod("addMetadata", String.class, Object.class);

            // ---- 5 个组件的总开关，顺序按玩家使用频率 ----
            addSwitch(put, functional, addMetadata, "muonium.fav.lyrics", "桌面歌词",
                    "屏幕下方的滚动歌词。关掉后不再绘制。",
                    "HUD 叠加层",
                    () -> HudConfig.musicLyricsEnabled,
                    v -> setLyrics(v));

            addSwitch(put, functional, addMetadata, "muonium.fav.info", "歌曲信息卡",
                    "左上角的歌名、封面与进度条。",
                    "HUD 叠加层",
                    () -> HudConfig.musicInfoEnabled,
                    v -> setInfo(v));

            addSwitch(put, functional, addMetadata, "muonium.fav.island", "灵动岛",
                    "屏幕顶部的胶囊通知：切歌、音量、下载进度。",
                    "HUD 叠加层",
                    () -> HudConfig.dynamicIslandEnabled,
                    v -> { HudConfig.dynamicIslandEnabled = v; HudConfig.save(); });

            addSwitch(put, functional, addMetadata, "muonium.fav.spectrum", "音符频谱",
                    "跟着音乐跳动的柱状频谱。",
                    "HUD 叠加层",
                    () -> HudConfig.spectrumEnabled,
                    v -> { HudConfig.spectrumEnabled = v; HudConfig.save(); });

            addSwitch(put, functional, addMetadata, "muonium.fav.acoustics", "场景声学",
                    "按所处环境（水下、洞穴、雨天）给音乐加混响。",
                    "HUD 叠加层",
                    AcousticsSettings::isEnabled,
                    AcousticsSettings::setEnabled);

            // ---- 高频调节项 ----
            addSlider(put, functional, addMetadata, "muonium.fav.lyric.font", "歌词字号",
                    "桌面歌词的基础字号。", 0.60f, 1.80f, 0.01f,
                    () -> HudConfig.lyricFontScale,
                    v -> { HudConfig.lyricFontScale = v; HudConfig.save(); });

            addSlider(put, functional, addMetadata, "muonium.fav.island.scale", "灵动岛大小",
                    "顶部胶囊的缩放。", 0.60f, 1.35f, 0.01f,
                    () -> HudConfig.dynamicIslandScale,
                    v -> { HudConfig.dynamicIslandScale = v; HudConfig.save(); });

            addSlider(put, functional, addMetadata, "muonium.fav.spectrum.opacity", "频谱不透明度",
                    "柱子有多显眼。", 0.10f, 1.0f, 0.01f,
                    () -> HudConfig.spectrumOpacity,
                    v -> { HudConfig.spectrumOpacity = v; HudConfig.save(); });

            registered = true;
        } catch (Throwable t) {
            // 不区分异常类型：类还没进加载器时反射会抛 InvocationTargetException
            // 包着 ClassNotFoundException，逐层剥不可靠。
            // 保持 registered=false，下一次 ensureRegistered() 会重试；
            // 真的不兼容也只是这一页不出现，不刷屏（失败只打一次）。
            if (!warned) {
                warned = true;
                System.out.println("[MuoniumPlayer/Favorites] 「常用」页注册暂未成功，将在后续重试：" + t);
            }
        }
    }

    /**
     * 每 tick 调一次，直到成功。
     *
     * <p>MuoniumConfig 是独立 mod，它的类只在它自己初始化之后才进入类加载器；
     * 而本类的注册挂在 client init 上，那一刻它的嵌套 jar 可能还没打开 ——
     * 直接注册会 {@code ClassNotFoundException}。用 tick 做重试是最省事且不影响启动的做法：
     * 成功一次之后就是空判断。</p>
     */
    public static void ensureRegistered() {
        if (registered) return;
        register();
    }

    private static void setLyrics(boolean enabled) {
        HudConfig.musicLyricsEnabled = enabled;
        try {
            com.muoniumplayer.core.MuoniumPlayerExtension ext =
                    com.muoniumplayer.core.MuoniumPlayerExtension.getInstance();
            if (ext != null) ext.musicLyrics.setEnabled(enabled);
        } catch (Throwable ignored) {
        }
        HudConfig.save();
    }

    private static void setInfo(boolean enabled) {
        HudConfig.musicInfoEnabled = enabled;
        try {
            com.muoniumplayer.core.MuoniumPlayerExtension ext =
                    com.muoniumplayer.core.MuoniumPlayerExtension.getInstance();
            if (ext != null) ext.musicInfo.setEnabled(enabled);
        } catch (Throwable ignored) {
        }
        HudConfig.save();
    }

    private static void addSwitch(java.lang.reflect.Method put, java.lang.reflect.Method functional,
                                  java.lang.reflect.Method addMetadata, String id, String label,
                                  String description, String group,
                                  java.util.function.Supplier<Boolean> getter,
                                  java.util.function.Consumer<Boolean> setter) throws Exception {
        Object prop = functional.invoke(null, getter, setter, id, label, description, Boolean.class);
        addMetadata.invoke(prop, "visualizer", visualizer("SwitchVisualizer"));
        addMetadata.invoke(prop, "default", getter.get());
        addMetadata.invoke(prop, "category", group);
        addMetadata.invoke(prop, "subcategory", "显示开关");
        put.invoke(currentTree(), prop);
    }

    private static void addSlider(java.lang.reflect.Method put, java.lang.reflect.Method functional,
                                  java.lang.reflect.Method addMetadata, String id, String label,
                                  String description, float min, float max, float step,
                                  java.util.function.Supplier<Float> getter,
                                  java.util.function.Consumer<Float> setter) throws Exception {
        Object prop = functional.invoke(null, getter, setter, id, label, description, Float.class);
        addMetadata.invoke(prop, "visualizer", visualizer("SliderVisualizer"));
        addMetadata.invoke(prop, "min", min);
        addMetadata.invoke(prop, "max", max);
        addMetadata.invoke(prop, "step", step);
        addMetadata.invoke(prop, "default", getter.get());
        addMetadata.invoke(prop, "category", "HUD 叠加层");
        addMetadata.invoke(prop, "subcategory", "常用调节");
        put.invoke(currentTree(), prop);
    }

    private static Object currentTree() throws Exception {
        Class<?> favoritesType = Class.forName(FAVORITES_TREE, true, MuoniumFavorites.class.getClassLoader());
        return favoritesType.getMethod("favoriteTree").invoke(null);
    }

    private static Object visualizer(String simpleName) throws Exception {
        Class<?> type = Class.forName(VISUALIZER + "$" + simpleName, true,
                MuoniumFavorites.class.getClassLoader());
        return type.getDeclaredConstructor().newInstance();
    }
}
