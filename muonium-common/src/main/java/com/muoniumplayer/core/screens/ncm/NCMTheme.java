package com.muoniumplayer.core.screens.ncm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.awt.Color;
import java.io.File;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.ncm.music.CoverThemePalette;

/** 播放器主题：读取主题色时实时生效，切换时使用平滑颜色过渡。 */
public final class NCMTheme {

    private static final File FILE = ConfigPaths.THEME;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long TRANSITION_DURATION_NANOS = 950_000_000L;

    private static ThemePreset current = ThemePreset.VELVET_ROSE;
    private static boolean loaded;
    private static int[] transitionFromColors;
    private static float transitionFromGlass;
    /** 真玻璃强度的动画起点，与 {@link #transitionFromGlass} 同一条时钟。 */
    private static float transitionFromTrueGlass;
    private static long transitionStartedAt = -1L;
    /** 每套预设各自保存一份用户调色覆盖，切换主题时不会互相污染。 */
    private static final Map<String, EnumMap<NCMScreen.ColorType, Integer>> CUSTOM_BY_THEME = new HashMap<String, EnumMap<NCMScreen.ColorType, Integer>>();
    /** 「封面跟随」生成的目标色；关闭该模式后不再参与任何读取。 */
    private static int[] dynamicThemeColors;
    private static int dynamicThemeVersion = -1;

    private NCMTheme() {
    }

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        if (!FILE.exists()) return;
        try {
            JsonObject object = JsonConfigStorage.readObject(FILE, GSON);
            if (object != null && object.has("theme")) {
                current = ThemePreset.fromId(object.get("theme").getAsString());
            }
            // 手机档：存档里是真玻璃主题也只在内存里退回默认主题（见 usableTheme）。
            // 刻意不回写文件——这台手机不该改写（未来可能共享/备份的）配置本体。
            current = usableTheme(current);
            if (object != null && object.has("custom") && object.get("custom").isJsonObject()) {
                readCustomColors(object.getAsJsonObject("custom"));
            }
        } catch (Throwable ignored) {
            current = ThemePreset.VELVET_ROSE;
            CUSTOM_BY_THEME.clear();
        }
    }

    public static synchronized ThemePreset getCurrent() {
        load();
        return current;
    }

    public static synchronized String getCurrentName() {
        return getCurrent().getDisplayName();
    }

    /**
     * 这款主题在当前运行时能不能被选中。
     *
     * <p>真玻璃主题（{@link ThemePreset#isTrueGlass()}：液态玻璃、琉璃）在手机档
     * （{@code GlassMobileTier}）上<b>整档不可选</b>：折射透镜在 GL 翻译层下编不过也跑不动，
     * 真玻璃画不出来时主题只剩深色实色兜底，看起来就是「一块黑」。禁用从源头做起——
     * 存档加载、轮换、直接设置三条路都过这道闸，设置界面的主题网格/下拉与引导页的
     * 主题卡按它把真玻璃项画成灰的。桌面恒可选。</p>
     */
    public static synchronized boolean isSelectable(ThemePreset preset) {
        load();
        return selectableLocked(preset);
    }

    private static boolean selectableLocked(ThemePreset preset) {
        return !com.muoniumplayer.core.rendering.GlassMobileTier.active()
                || preset == null || !preset.isTrueGlass();
    }

    /** 手机档把真玻璃主题退回默认主题；桌面原样返回。只在内存里生效，不回写存档。 */
    private static ThemePreset usableTheme(ThemePreset preset) {
        return selectableLocked(preset) ? preset : ThemePreset.VELVET_ROSE;
    }

    /** 切换至下一款预设主题，并保存当前视觉状态作为动画起点。 */
    public static synchronized ThemePreset next() {
        load();
        ThemePreset[] presets = ThemePreset.values();
        // 手机档跳过真玻璃主题：轮换按钮永远落在可选的那一款上，不会「转到一块黑」。
        for (int step = 1; step <= presets.length; step++) {
            ThemePreset candidate = presets[(current.ordinal() + step) % presets.length];
            if (selectableLocked(candidate)) {
                return setCurrent(candidate);
            }
        }
        return setCurrent(presets[(current.ordinal() + 1) % presets.length]);
    }

    /** 直接切换到指定预设主题；沿用与轮换一致的平滑过渡与持久化。 */
    public static synchronized ThemePreset setCurrent(ThemePreset preset) {
        load();
        if (preset == null || preset == current) {
            return current;
        }
        if (!selectableLocked(preset)) {
            return current; // 手机档：真玻璃主题选不了（UI 已置灰，这里是最后一道闸）。
        }
        beginTransitionLocked();
        current = preset;
        save();
        return current;
    }

    /** 以当前实际显示的颜色为起点开启一次过渡动画。 */
    private static void beginTransitionLocked() {
        long now = System.nanoTime();
        NCMScreen.ColorType[] colorTypes = NCMScreen.ColorType.values();
        int[] visualSnapshot = new int[colorTypes.length];
        for (NCMScreen.ColorType type : colorTypes) {
            visualSnapshot[type.ordinal()] = getAnimatedColorLocked(type, now);
        }
        float glassSnapshot = getLiquidGlassAmountLocked(now);
        float trueGlassSnapshot = getTrueGlassAmountLocked(now);
        transitionFromColors = visualSnapshot;
        transitionFromGlass = glassSnapshot;
        transitionFromTrueGlass = trueGlassSnapshot;
        transitionStartedAt = System.nanoTime();
    }

    public static synchronized int getColor(NCMScreen.ColorType colorType) {
        load();
        long now = System.nanoTime();
        syncDynamicThemeLocked(true);
        return getAnimatedColorLocked(colorType, now);
    }

    /** 液态玻璃材质权重；切入和切出主题时同样平滑变化。 */
    public static synchronized float getLiquidGlassAmount() {
        load();
        return getLiquidGlassAmountLocked(System.nanoTime());
    }

    /** 暴露主题强调色，而不要求全局 HUD 元素依赖 NCMScreen。 */
    public static int getAccentColor() {
        return getColor(NCMScreen.ColorType.ACCENT);
    }

    /**
     * 当前主题是否要真玻璃：面板背后那块画面是每帧从游戏画面采下来的，而不是渐变高光。
     *
     * <p>这是整条采集链路的总闸——{@code GlassBackdrop.wanted()} 读它，普通主题下
     * 一次纹理拷贝都不会发生。</p>
     */
    public static synchronized boolean isTrueGlass() {
        load();
        return current.isTrueGlass();
    }

    /**
     * 真玻璃此刻的<b>强度</b>（0~1），切换主题时与配色共用同一条 0.95 秒过渡。
     *
     * <p>渲染侧要的是这个而不是 {@link #isTrueGlass()}：布尔在切走的那一刻就翻了，
     * 玻璃会在玩家眼前硬消失，而背景采集也会当场停掉，淡出途中铺着的将是一帧过期画面。
     * 读这个数就能让「淡出」和「继续采」同时成立。</p>
     */
    public static synchronized float getTrueGlassAmount() {
        load();
        return getTrueGlassAmountLocked(System.nanoTime());
    }

    /** 当前预设在忽略用户调色覆盖时的原始颜色。 */
    public static synchronized int getPresetColor(NCMScreen.ColorType colorType) {
        load();
        return current.getColor(colorType);
    }

    /** 颜色的最终目标值：存在用户调色时返回调色结果，否则返回预设色。 */
    public static synchronized int getTargetColor(NCMScreen.ColorType colorType) {
        load();
        syncDynamicThemeLocked(true);
        return targetColorLocked(colorType);
    }

    /**
     * 切换主题颜色来源。切换前先记录当前画面颜色，随后仍走原有的 950ms 过渡。
     */
    public static synchronized void setColorSource(int source) {
        load();
        int safe = source == ThemeColorSource.COVER.ordinal()
                ? ThemeColorSource.COVER.ordinal() : ThemeColorSource.PRESET.ordinal();
        if (safe == HudConfig.themeColorSource) return;

        beginTransitionLocked();
        HudConfig.themeColorSource = safe;
        dynamicThemeVersion = -1;
        dynamicThemeColors = null;
        syncDynamicThemeLocked(false);
        HudConfig.save();
    }

    public static synchronized boolean hasCustomColor(NCMScreen.ColorType colorType) {
        load();
        EnumMap<NCMScreen.ColorType, Integer> map = CUSTOM_BY_THEME.get(current.getId());
        return map != null && colorType != null && map.containsKey(colorType);
    }

    public static synchronized boolean hasAnyCustomColor() {
        load();
        EnumMap<NCMScreen.ColorType, Integer> map = CUSTOM_BY_THEME.get(current.getId());
        return map != null && !map.isEmpty();
    }

    /**
     * 写入一项用户调色。为了让调色盘做到实时预览，这里不启动过渡动画，
     * 颜色立即生效；{@code persist} 为 {@code false} 时只改内存，供拖动过程使用。
     */
    public static synchronized void setCustomColor(NCMScreen.ColorType colorType, int rgb, boolean persist) {
        load();
        if (colorType == null) {
            return;
        }
        EnumMap<NCMScreen.ColorType, Integer> map = CUSTOM_BY_THEME.get(current.getId());
        if (map == null) {
            map = new EnumMap<NCMScreen.ColorType, Integer>(NCMScreen.ColorType.class);
            CUSTOM_BY_THEME.put(current.getId(), map);
        }
        map.put(colorType, Integer.valueOf(rgb & 0xFFFFFF));
        if (persist) {
            save();
        }
    }

    /** 撤销单项调色，回到预设色。 */
    public static synchronized void resetCustomColor(NCMScreen.ColorType colorType) {
        load();
        EnumMap<NCMScreen.ColorType, Integer> map = CUSTOM_BY_THEME.get(current.getId());
        if (map == null || colorType == null || map.remove(colorType) == null) {
            return;
        }
        if (map.isEmpty()) {
            CUSTOM_BY_THEME.remove(current.getId());
        }
        save();
    }

    /** 撤销当前主题的所有调色。 */
    public static synchronized void resetAllCustomColors() {
        load();
        if (CUSTOM_BY_THEME.remove(current.getId()) != null) {
            save();
        }
    }

    private static int effectiveColorLocked(ThemePreset preset, NCMScreen.ColorType colorType) {
        EnumMap<NCMScreen.ColorType, Integer> map = CUSTOM_BY_THEME.get(preset.getId());
        if (map != null) {
            Integer custom = map.get(colorType);
            if (custom != null) {
                return custom.intValue() & 0xFFFFFF;
            }
        }
        return preset.getColor(colorType);
    }

    private static void readCustomColors(JsonObject root) {
        CUSTOM_BY_THEME.clear();
        for (Map.Entry<String, com.google.gson.JsonElement> themeEntry : root.entrySet()) {
            if (!themeEntry.getValue().isJsonObject()) {
                continue;
            }
            EnumMap<NCMScreen.ColorType, Integer> map = new EnumMap<NCMScreen.ColorType, Integer>(NCMScreen.ColorType.class);
            for (Map.Entry<String, com.google.gson.JsonElement> colorEntry
                    : themeEntry.getValue().getAsJsonObject().entrySet()) {
                NCMScreen.ColorType type = parseColorType(colorEntry.getKey());
                if (type == null) {
                    continue;
                }
                try {
                    String raw = colorEntry.getValue().getAsString();
                    if (raw == null) {
                        continue;
                    }
                    raw = raw.trim();
                    if (raw.startsWith("#")) {
                        raw = raw.substring(1);
                    }
                    map.put(type, Integer.valueOf((int) (Long.parseLong(raw, 16) & 0xFFFFFFL)));
                } catch (Throwable ignored) {
                }
            }
            if (!map.isEmpty()) {
                CUSTOM_BY_THEME.put(themeEntry.getKey(), map);
            }
        }
    }

    private static JsonObject writeCustomColors() {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, EnumMap<NCMScreen.ColorType, Integer>> themeEntry : CUSTOM_BY_THEME.entrySet()) {
            if (themeEntry.getValue() == null || themeEntry.getValue().isEmpty()) {
                continue;
            }
            JsonObject colors = new JsonObject();
            for (Map.Entry<NCMScreen.ColorType, Integer> colorEntry : themeEntry.getValue().entrySet()) {
                colors.addProperty(colorEntry.getKey().name(),
                        String.format("#%06X", Integer.valueOf(colorEntry.getValue().intValue() & 0xFFFFFF)));
            }
            root.add(themeEntry.getKey(), colors);
        }
        return root;
    }

    private static NCMScreen.ColorType parseColorType(String name) {
        if (name == null) {
            return null;
        }
        for (NCMScreen.ColorType type : NCMScreen.ColorType.values()) {
            if (type.name().equalsIgnoreCase(name.trim())) {
                return type;
            }
        }
        return null;
    }

    private static void syncDynamicThemeLocked(boolean captureTransition) {
        if (ThemeColorSource.current() != ThemeColorSource.COVER) {
            dynamicThemeColors = null;
            dynamicThemeVersion = -1;
            return;
        }

        int version = CoverThemePalette.version();
        if (version == dynamicThemeVersion) return;
        int[] palette = CoverThemePalette.snapshot();
        if (palette.length < 2) return;
        int[] generated = CoverThemeGenerator.generate(palette, isLightPreset());
        if (generated == null) return;

        if (captureTransition) beginTransitionLocked();
        dynamicThemeColors = generated;
        dynamicThemeVersion = version;
    }

    /** 当前主题是不是浅色（按背景亮度判定）。HUD 设置界面用它挑暗/日两套表面 token。 */
    public static boolean isLightPreset() {
        int background = current.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND);
        return Color.RGBtoHSB((background >>> 16) & 0xFF, (background >>> 8) & 0xFF,
                background & 0xFF, null)[2] > .62f;
    }

    private static int targetColorLocked(NCMScreen.ColorType colorType) {
        if (ThemeColorSource.current() == ThemeColorSource.COVER
                && dynamicThemeColors != null
                && colorType != null
                && colorType.ordinal() < dynamicThemeColors.length) {
            return dynamicThemeColors[colorType.ordinal()];
        }
        return effectiveColorLocked(current, colorType);
    }

    private static int getAnimatedColorLocked(NCMScreen.ColorType colorType, long now) {
        int target = targetColorLocked(colorType);
        if (transitionFromColors == null) return target;

        float progress = getTransitionProgressLocked(now);
        int from = transitionFromColors[colorType.ordinal()];
        if (progress >= 1.0f) {
            transitionFromColors = null;
            transitionStartedAt = -1L;
            return target;
        }
        return lerpColor(from, target, smoothstep(progress));
    }

    private static float getLiquidGlassAmountLocked(long now) {
        float target = current.isLiquidGlass() ? 1.0f : 0.0f;
        if (transitionFromColors == null || transitionStartedAt < 0L) return target;
        return lerp(transitionFromGlass, target, smoothstep(getTransitionProgressLocked(now)));
    }

    /** 与上面同一条时钟，只是问的是「真玻璃」而不是「玻璃质感的高光」。 */
    private static float getTrueGlassAmountLocked(long now) {
        float target = current.isTrueGlass() ? 1.0f : 0.0f;
        if (transitionFromColors == null || transitionStartedAt < 0L) return target;
        return lerp(transitionFromTrueGlass, target, smoothstep(getTransitionProgressLocked(now)));
    }

    private static float getTransitionProgressLocked(long now) {
        if (transitionStartedAt < 0L) return 1.0f;
        return Math.max(0.0f, Math.min(1.0f,
                (float) (now - transitionStartedAt) / (float) TRANSITION_DURATION_NANOS));
    }

    private static float smoothstep(float value) {
        return value * value * (3.0f - 2.0f * value);
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    private static int lerpColor(int from, int to, float amount) {
        int r = Math.round(lerp((from >> 16) & 0xFF, (to >> 16) & 0xFF, amount));
        int g = Math.round(lerp((from >> 8) & 0xFF, (to >> 8) & 0xFF, amount));
        int b = Math.round(lerp(from & 0xFF, to & 0xFF, amount));
        return (r << 16) | (g << 8) | b;
    }

    private static void save() {
        try {
            JsonObject object = new JsonObject();
            object.addProperty("theme", current.getId());
            JsonObject custom = writeCustomColors();
            if (custom.entrySet().size() > 0) {
                object.add("custom", custom);
            }
            JsonConfigStorage.writeObject(FILE, GSON, object);

        } catch (Throwable ignored) {
        }
    }

    /** 深浅与氛围各不相同的播放器预设主题。 */
    public enum ThemePreset {
        OBSIDIAN_RED("obsidian_red", "曜石红", 0x1E1E1E, 0x252525, 0x383838, 0xFFFFFF, 0x9B9B9B, 0xD60017, 0xF04455, 0x292727, 0x191919, 0x454545, false),
        AURORA_TIDE("aurora_tide", "极光海", 0x0D1720, 0x142631, 0x203D4A, 0xEFFBFF, 0x91AAB5, 0x54D6C1, 0x7DE8D6, 0x10212B, 0x091118, 0x2B5160, false),
        VELVET_ROSE("velvet_rose", "丝绒玫瑰", 0x211821, 0x30212C, 0x493342, 0xFFF7FB, 0xC2AAB7, 0xE48AAB, 0xF4A9C4, 0x2A1D27, 0x1A1219, 0x604252, false),
        PORCELAIN_INK("porcelain_ink", "瓷白墨", 0xF4F0EA, 0xFFFCF7, 0xE7DDD1, 0x2A2723, 0x817970, 0xB85C38, 0xD37B54, 0xEEE7DF, 0xE7DED5, 0xD4C6B8, false),
        LIQUID_GLASS("liquid_glass", "液态玻璃", 0x182331, 0x26384B, 0x49637C, 0xF7FBFF, 0xB7C6D8, 0x64A8FF, 0x8BC1FF, 0x203044, 0x111C2A, 0x91A8C2, true),
        AURORA_FROST("aurora_frost", "极光霜蓝", 0x101827, 0x17263A, 0x2C4660, 0xF3F8FF, 0xAAB9CF, 0x7B9CFF, 0xA6B8FF, 0x1B2C44, 0x0C1423, 0x5576A6, true),
        MIDNIGHT_GRAPHITE("midnight_graphite", "午夜石墨", 0x111419, 0x1B2028, 0x323B49, 0xF5F7FA, 0xA3ACBA, 0x8FA8C7, 0xB4C8E5, 0x202731, 0x0C0F14, 0x4C5869, false),
        SUNSET_EMBER("sunset_ember", "暮色琥珀", 0x20151B, 0x2B1D25, 0x4B2D35, 0xFFF7F0, 0xCDB3A5, 0xF59A62, 0xFFC078, 0x352128, 0x171014, 0x70424A, false),
        SAGE_MIST("sage_mist", "鼠尾草雾", 0x121C1B, 0x1C2B29, 0x304742, 0xF1FAF5, 0xA8BDB4, 0x72C9A2, 0x9BE1BF, 0x203632, 0x0D1413, 0x4C7267, false),
        PEARL_LIGHT("pearl_light", "珍珠晨曦", 0xEEF2F6, 0xF9FBFD, 0xDCE5EF, 0x202832, 0x647386, 0x5E83C6, 0x789DDF, 0xE7EDF4, 0xDDE5EE, 0xB8C8D9, false),
        XIANGYUN_QINGCHUAN("xiangyun_qingchuan", "缃云晴川", 0xF7ECC0, 0xFDF7E2, 0xF0DFA8, 0x3A3320, 0x857A55, 0x2E7CA8, 0x79BEDF, 0xF3E7BF, 0xEFDFA6, 0xDCC580, false),
        CANGMING_YUYOU("cangming_yuyou", "沧溟玉釉", 0xFAF2E0, 0xFFFBF1, 0xF0E4CB, 0x16283C, 0x6E7C8B, 0x0E61AC, 0x1D7ACE, 0xF5EBD5, 0xF2E7CF, 0xDCCFB4, false),
        SHUANGWAN_XIUYAN("shuangwan_xiuyan", "霜纨岫烟", 0xFCF9E8, 0xFFFEF6, 0xF1EDD8, 0x1E2A2B, 0x6F7C7D, 0x00808C, 0x00B7C7, 0xF7F2DE, 0xF5F1DC, 0xDCD8C0, false),
        MEIGUI_YUEBAI("meigui_yuebai", "玫瑰月白", 0xE8F0F2, 0xF6FAFB, 0xDCE7EA, 0x262E31, 0x6B7A7F, 0xB44E60, 0xD87888, 0xE1EBEE, 0xDEE9EC, 0xC3D3D7, false),
        QINGLAN_YUNZHI("qinglan_yunzhi", "青岚云脂", 0xFBF1D7, 0xFFFAEA, 0xF0E4C4, 0x232A1D, 0x6F7A60, 0x4E7C36, 0x73AE52, 0xF6ECD0, 0xF4EACD, 0xDCD0AE, false),
        /** Real lens material with a cool palette; crystal id is retained for saved themes. */
        CRYSTAL("crystal", "琉璃", 0x141E2B, 0x1F2E42, 0x3A5570, 0xF5FAFF, 0xA9BCD2, 0x7FC4FF, 0xA8DAFF, 0x18293C, 0x0E1825, 0x9DB8D6, true);

        private final String id, displayName;
        private final int genericBackground, elementBackground, elementHover, primaryText, secondaryText;
        private final int accent, accentHover, inputBackground, navigationBackground, border;
        private final boolean liquidGlass;

        ThemePreset(String id, String displayName, int genericBackground, int elementBackground, int elementHover,
                    int primaryText, int secondaryText, int accent, int accentHover, int inputBackground,
                    int navigationBackground, int border, boolean liquidGlass) {
            this.id = id;
            this.displayName = displayName;
            this.genericBackground = genericBackground;
            this.elementBackground = elementBackground;
            this.elementHover = elementHover;
            this.primaryText = primaryText;
            this.secondaryText = secondaryText;
            this.accent = accent;
            this.accentHover = accentHover;
            this.inputBackground = inputBackground;
            this.navigationBackground = navigationBackground;
            this.border = border;
            this.liquidGlass = liquidGlass;
        }

        public String getId() { return id; }
        public String getDisplayName() { return displayName; }
        public boolean isLiquidGlass() { return liquidGlass; }

        /**
         * 这一款要不要<b>真</b>采样背后那块画面。
         *
         * <p>做成方法而不是第十三个构造参数，是为了不动上面那十五行配色——每加一个参数就要
         * 重排十五个预设的实参，那种改动读不出对错，也不值得为一款主题付。</p>
         */
        public boolean isTrueGlass() { return this == LIQUID_GLASS || this == CRYSTAL; }

        public int getColor(NCMScreen.ColorType type) {
            switch (type) {
                case GENERIC_BACKGROUND: return genericBackground;
                case ELEMENT_BACKGROUND: return elementBackground;
                case ELEMENT_HOVER: return elementHover;
                case PRIMARY_TEXT: return primaryText;
                case SECONDARY_TEXT: return secondaryText;
                case ACCENT: return accent;
                case ACCENT_HOVER: return accentHover;
                case INPUT_BACKGROUND: return inputBackground;
                case NAVIGATION_BACKGROUND: return navigationBackground;
                case BORDER: return border;
                default: return primaryText;
            }
        }

        private static ThemePreset fromId(String id) {
            if (id != null) {
                for (ThemePreset preset : values()) {
                    if (preset.id.equalsIgnoreCase(id)) return preset;
                }
            }
            return VELVET_ROSE;
        }
    }
}
