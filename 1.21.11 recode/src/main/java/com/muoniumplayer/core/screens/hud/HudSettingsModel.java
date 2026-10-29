package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.core.MuoniumPlayerExtension;
import com.muoniumplayer.core.ncm.music.AutomixSettings;
import com.muoniumplayer.core.ncm.music.ExternalPlayback;
import com.muoniumplayer.core.ncm.music.FoliaModelManager;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.MusicAudioCache;
import com.muoniumplayer.core.rendering.AutomixTransitionAnimation;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.HudSettingsIcons;
import com.muoniumplayer.core.screens.ncm.NCMPlayerConfig;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.settings.HudSetting;
import com.muoniumplayer.core.screens.GuiAnnouncements;
import com.muoniumplayer.core.update.AnnouncementChecker;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.core.widget.impl.BlossomLyricStyle;
import com.muoniumplayer.core.widget.impl.MusicLyricsWidget;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * HUD 设置界面的内容声明：分类 → 分组 → 条目。
 *
 * <p>为什么要单独一层：内置 HUD 设置界面（{@link GuiHudEditorPro}）与外部镜像
 * （OneConfig 桥）展示的必须是同一批设置项。如果各界面各自把条目硬编码一遍，
 * 加一个开关就得改两处，漏改的那一边会静默地少一项。把「有哪些项、每项怎么读怎么写、
 * 数值怎么格式化」集中到这里，界面只负责画和命中测试。</p>
 *
 * <p>条目的取值全部走 {@code Supplier} / {@code Runnable} 而不是快照：HUD 设置是实时预览的，
 * 声明必须每帧都反映最新状态。也因此这些声明是无状态的，可以安全地缓存成静态常量。</p>
 */
public final class HudSettingsModel {

    private HudSettingsModel() {
    }

    /** 条目类型。界面按这个决定右侧画什么控件、点击时做什么。 */
    public enum Kind {
        /** 连续数值，拖动轨道。 */
        SLIDER,
        /** 布尔开关。 */
        TOGGLE,
        /** 颜色，点击打开取色器。 */
        COLOR,
        /** 在若干预设之间循环，点击进下一个。 */
        CHOICE,
        /** 主题色卡网格，占一整行且高度由预设数量决定。 */
        THEME_GRID,
        /** 一次性动作按钮。 */
        ACTION
    }

    /** 颜色条目的身份。取色器要知道自己在编辑哪一个，才能把 HSB 写回正确的字段。 */
    public enum ColorTarget {
        NONE,
        /** 当前演唱行的颜色。 */
        CURRENT,
        /** 其余歌词行的颜色。 */
        NORMAL,
        /** 3D 世界歌词的文字颜色（存 HudConfig.worldLyricsColorRgb，无 OneConfig 值对象）。 */
        WORLD_LYRICS,
        /** 3D 世界歌词的发光颜色（存 HudConfig.worldLyricsGlowColorRgb，同上直写）。 */
        WORLD_LYRICS_GLOW
    }

    public static final class Entry {
        public final Kind kind;
        private final Supplier<String> label;
        /** 一句话说明这项到底影响什么。现代设置界面里这行副标题比标签本身更有用。 */
        public final String description;
        /** SLIDER 专用。 */
        public final HudSetting setting;
        private final BooleanSupplier state;
        private final Runnable action;
        private final Supplier<String> value;
        /** CHOICE 的可选文案；供 OneConfig 这类外部 UI 生成下拉框。 */
        private final String[] choices;
        /** CHOICE 的直写入口；没有时退化为循环点击。 */
        private final Consumer<String> choiceSetter;
        public final ColorTarget colorTarget;
        /** 可选的运行时可见性钩子；旧版与 Folia 暴露不同的控件。 */
        private final BooleanSupplier visibleWhen;
        /**
         * ACTION 的两种形，语义区别不是像素而是「这行是什么」：
         * false = folia {@code StorageSettingsSection} 那种独立大行（自己就是一块，带图标底板
         * 与 ChevronRight）；true = 一组命令按钮里的一个，画成紧凑药丸与同伴并排换行
         * （{@code flex flex-wrap gap-2}，如「从本机查找 / 更改位置 / 删除所有模型」）。
         */
        public final boolean inlineButton;
        /** CHOICE 每个选项各自的一句话说明，与 {@link #choices} 同序；没有时为空数组。 */
        private final String[] choiceHints;

        private Entry(Kind kind, Supplier<String> label, String description, HudSetting setting,
                      BooleanSupplier state, Runnable action, Supplier<String> value,
                      ColorTarget colorTarget) {
            this(kind, label, description, setting, state, action, value, colorTarget, null, null, null);
        }

        private Entry(Kind kind, Supplier<String> label, String description, HudSetting setting,
                      BooleanSupplier state, Runnable action, Supplier<String> value,
                      ColorTarget colorTarget, String[] choices, Consumer<String> choiceSetter) {
            this(kind, label, description, setting, state, action, value, colorTarget, choices,
                    choiceSetter, null);
        }

        private Entry(Kind kind, Supplier<String> label, String description, HudSetting setting,
                      BooleanSupplier state, Runnable action, Supplier<String> value,
                      ColorTarget colorTarget, String[] choices, Consumer<String> choiceSetter,
                      BooleanSupplier visibleWhen) {
            this(kind, label, description, setting, state, action, value, colorTarget, choices,
                    choiceSetter, visibleWhen, false, null);
        }

        private Entry(Kind kind, Supplier<String> label, String description, HudSetting setting,
                      BooleanSupplier state, Runnable action, Supplier<String> value,
                      ColorTarget colorTarget, String[] choices, Consumer<String> choiceSetter,
                      BooleanSupplier visibleWhen, boolean inlineButton, String[] choiceHints) {
            this.kind = kind;
            this.label = label;
            this.description = description;
            this.setting = setting;
            this.state = state;
            this.action = action;
            this.value = value;
            this.choices = choices;
            this.choiceSetter = choiceSetter;
            this.colorTarget = colorTarget;
            this.visibleWhen = visibleWhen;
            this.inlineButton = inlineButton;
            this.choiceHints = choiceHints;
        }

        public boolean isVisible() {
            if (visibleWhen == null) return true;
            try { return visibleWhen.getAsBoolean(); } catch (RuntimeException ignored) { return false; }
        }

        /**
         * 可选的「本行在当前环境锁死」钩子：命中时行为置灰、点击无效。
         *
         * <p>手机档（{@code GlassMobileTier}）把「真玻璃材质」整组锁死——玻璃主题在安卓
         * 上整体禁用后（见 {@code NCMTheme.isSelectable}），这些材质参数只是摆设，留着可点
         * 会让人以为改了有用。置灰画在渲染层，这里的 {@link #isLocked()} 是行为闸。</p>
         */
        private BooleanSupplier lockedWhen;

        /** 链式标记本行为条件锁死；返回自身便于构建处内联包裹。 */
        public Entry lockedWhen(BooleanSupplier when) {
            this.lockedWhen = when;
            return this;
        }

        /** 本行当前是否锁死：锁死行点击不生效，渲染层应把它画成灰的。 */
        public boolean isLocked() {
            if (lockedWhen == null) return false;
            try { return lockedWhen.getAsBoolean(); } catch (RuntimeException ignored) { return false; }
        }

        public String getLabel() {
            try {
                String text = label == null ? null : label.get();
                return text == null ? "" : text;
            } catch (RuntimeException ignored) {
                return "";
            }
        }

        /** TOGGLE 的当前状态；其它类型恒为 false。 */
        public boolean isOn() {
            if (state == null) return false;
            try {
                return state.getAsBoolean();
            } catch (RuntimeException ignored) {
                return false;
            }
        }

        /** CHOICE 右侧显示的当前取值；其它类型返回空串。 */
        public String getValueText() {
            if (value == null) return "";
            try {
                String text = value.get();
                return text == null ? "" : text;
            } catch (RuntimeException ignored) {
                return "";
            }
        }

        /** CHOICE 的可选文案；非 CHOICE 或旧条目返回空数组。 */
        public String[] getChoices() {
            return choices == null ? new String[0] : choices.clone();
        }

        /**
         * CHOICE 每个选项各自那句说明，与 {@link #getChoices()} 同序；没有配置时返回空数组。
         *
         * <p>长度可能短于选项数（只给前几项写了说明），取用时要按下标判界。</p>
         */
        public String[] getChoiceHints() {
            return choiceHints == null ? new String[0] : choiceHints.clone();
        }

        /** 有没有配逐项说明。渲染每帧都要问，别为它拷一遍数组。 */
        public boolean hasChoiceHints() {
            return choiceHints != null && choiceHints.length > 0;
        }

        /** 外部 UI 直写 CHOICE；没有直写实现时沿用循环动作。 */
        public void setChoice(String selected) {
            if (isLocked()) return;
            if (choiceSetter != null) {
                try {
                    choiceSetter.accept(selected);
                } catch (RuntimeException ignored) {
                }
                return;
            }
            activate();
        }

        /**
         * 触发这一项的默认动作：开关取反、选项进下一个、动作执行。
         *
         * <p>SLIDER 与 COLOR 没有默认动作（它们靠拖动），调用是空操作。任何异常都被吞掉：
         * 一次点击失败不该把设置界面整个带崩。</p>
         */
        public void activate() {
            if (isLocked() || action == null) return;
            try {
                action.run();
            } catch (RuntimeException ignored) {
            }
        }

        /** COLOR 专用：当前颜色。取不到时给一个中性灰，避免界面出现 null。 */
        public Color getColor() {
            MusicLyricsWidget lyrics = lyrics();
            if (lyrics == null) return new Color(0x9AA6B8);
            try {
                if (colorTarget == ColorTarget.CURRENT) return lyrics.currentLyricColor.getValue();
                if (colorTarget == ColorTarget.NORMAL) return lyrics.lyricColor.getValue();
                if (colorTarget == ColorTarget.WORLD_LYRICS) return new Color(HudConfig.worldLyricsColorRgb);
                if (colorTarget == ColorTarget.WORLD_LYRICS_GLOW) return new Color(HudConfig.worldLyricsGlowColorRgb);
            } catch (RuntimeException ignored) {
            }
            return new Color(0x9AA6B8);
        }

        /**
         * COLOR 专用：写入颜色。
         *
         * <p>刻意只改内存不落盘：取色器是拖着改的，每帧写一次 JSON 会明显卡顿。界面在鼠标松开时
         * 统一 {@code HudConfig.save()}。</p>
         */
        public void setColor(Color selected) {
            if (selected == null) return;
            if (colorTarget == ColorTarget.WORLD_LYRICS) {
                // 3D 歌词没有 OneConfig 值对象，直写 HudConfig（alpha 位剥掉，存 RGB 低 24 位）。
                // 「改过色」必须单独记旗：0xFFFFFF 本身是合法的自定义色（纯白），
                // 拿值当旗标会让「选白色」被回落逻辑替换成暖白肉色。
                HudConfig.worldLyricsColorRgb = selected.getRGB() & 0xFFFFFF;
                HudConfig.worldLyricsColorCustom = true;
                return;
            }
            if (colorTarget == ColorTarget.WORLD_LYRICS_GLOW) {
                HudConfig.worldLyricsGlowColorRgb = selected.getRGB() & 0xFFFFFF;
                return;
            }
            MusicLyricsWidget lyrics = lyrics();
            if (lyrics == null) return;
            try {
                if (colorTarget == ColorTarget.CURRENT) {
                    lyrics.currentLyricColor.setValue(selected);
                    HudConfig.currentLyricColorRgb = selected.getRGB();
                } else if (colorTarget == ColorTarget.NORMAL) {
                    lyrics.lyricColor.setValue(selected);
                    HudConfig.lyricColorRgb = selected.getRGB();
                }
            } catch (RuntimeException ignored) {
            }
        }

        /** 条目是否命中搜索关键字。标签与说明都参与匹配。 */
        public boolean matches(String lowerQuery) {
            if (!isVisible()) return false;
            if (lowerQuery == null || lowerQuery.isEmpty()) return true;
            if (getLabel().toLowerCase().contains(lowerQuery)) return true;
            if (description != null && description.toLowerCase().contains(lowerQuery)) return true;
            return setting != null && setting.name().toLowerCase().contains(lowerQuery);
        }
    }

    public static final class Group {
        public final String title;
        /** 分组标题左侧的图标码位，取 {@link HudSettingsIcons} 的常量。 */
        public final String icon;
        public final Entry[] entries;

        private Group(String title, String icon, Entry... entries) {
            this.title = title;
            this.icon = icon;
            this.entries = entries;
        }
    }

    public static final class Category {
        public final String title;
        public final String subtitle;
        /** 分类的标识色。侧边栏色点、分组标题与选中态都用它。 */
        public final int accent;
        /** 侧边栏与标题上代表这一分类的图标码位，取 {@link HudSettingsIcons} 的常量。 */
        public final String icon;
        public final Group[] groups;

        private Category(String title, String subtitle, int accent, String icon,
                         Group... groups) {
            this.title = title;
            this.subtitle = subtitle;
            this.accent = accent;
            this.icon = icon;
            this.groups = groups;
        }

        public int entryCount() {
            int total = 0;
            for (Group group : groups) for (Entry entry : group.entries) if (entry.isVisible()) total++;
            return total;
        }
    }

    // ==================== 构造工具 ====================

    private static Entry slider(HudSetting setting, String description) {
        final HudSetting target = setting;
        return new Entry(Kind.SLIDER, new Supplier<String>() {
            public String get() {
                return target.getLabel();
            }
        }, description, setting, null, null, null, ColorTarget.NONE);
    }

    private static Entry slider(HudSetting setting, String description, BooleanSupplier visibleWhen) {
        final HudSetting target = setting;
        return new Entry(Kind.SLIDER, new Supplier<String>() {
            public String get() { return target.getLabel(); }
        }, description, setting, null, null, null, ColorTarget.NONE, null, null, visibleWhen);
    }

    private static Entry toggle(final String label, String description,
                                BooleanSupplier state, Runnable action) {
        return new Entry(Kind.TOGGLE, new Supplier<String>() {
            public String get() {
                return label;
            }
        }, description, null, state, action, null, ColorTarget.NONE);
    }

    private static Entry toggle(Supplier<String> label, String description,
                                BooleanSupplier state, Runnable action) {
        return new Entry(Kind.TOGGLE, label, description, null, state, action, null, ColorTarget.NONE);
    }
    private static Entry toggle(final String label, String description,
                                BooleanSupplier state, Runnable action, BooleanSupplier visibleWhen) {
        return new Entry(Kind.TOGGLE, new Supplier<String>() {
            public String get() { return label; }
        }, description, null, state, action, null, ColorTarget.NONE, null, null, visibleWhen);
    }


    // ---- 绘光关键字着色预置方案（关键字染色的 MC 化：choice 循环方案） ----
    private static final String KW_PRESET_OFF = "关闭";
    private static final String KW_PRESET_SAKURA = "樱红";
    private static final String KW_PRESET_AZURE = "苍蓝";
    private static final String KW_PRESET_VERDANT = "翠绿";
    private static final String KW_PRESET_AMBER = "琥珀";

    private static String[] keywordPresetChoices() {
        return new String[] {KW_PRESET_OFF, KW_PRESET_SAKURA, KW_PRESET_AZURE,
                KW_PRESET_VERDANT, KW_PRESET_AMBER};
    }

    // ==================== 3D 歌词渲染画质档（烘焙超采样） ====================

    private static final String[] QUALITY_CHOICES = {"自动", "流畅", "标准", "高清", "极致"};

    /** 当前档名（0=自动按帧缓冲高度分档，4~7=手动烘焙倍率）。 */
    private static String qualityTierLabel() {
        return QUALITY_CHOICES[qualityChoiceIndex(HudConfig.worldLyricsQualityTier)];
    }

    private static int qualityChoiceIndex(int tier) {
        return switch (tier) {
            case 4 -> 1;
            case 5 -> 2;
            case 6 -> 3;
            case 7 -> 4;
            default -> 0;
        };
    }

    private static void cycleQualityTier() {
        int idx = (qualityChoiceIndex(HudConfig.worldLyricsQualityTier) + 1) % QUALITY_CHOICES.length;
        setQualityTier(idx);
    }

    /** Consumer&lt;String&gt; 口径（choice 的 setter 契约）：按当前档名反查下标再切。 */
    private static void setQualityTier(String selected) {
        for (int i = 0; i < QUALITY_CHOICES.length; i++) {
            if (QUALITY_CHOICES[i].equals(selected)) {
                setQualityTier(i);
                return;
            }
        }
    }

    private static void setQualityTier(int choiceIdx) {
        // choice 循环切换：0=自动（按帧缓冲高度一次性定档，改分辨率/窗口后下次
        // 进设置重估）；4~7 = 手动强制对应烘焙倍率。切档经 setBakeScale 全图集重建。
        int tier = switch (choiceIdx) {
            case 1 -> 4;
            case 2 -> 5;
            case 3 -> 6;
            case 4 -> 7;
            default -> 0;
        };
        HudConfig.worldLyricsQualityTier = tier;
        applyQualityTierNow(tier);
        saveNow();
    }

    /** 解析设置档（0=自动按帧缓冲高度）并落到图集。 */
    private static void applyQualityTierNow(int tier) {
        int scale = tier >= 4 ? tier : autoBakeScale();
        com.muoniumplayer.core.lyrics3d.LyricGlyphAtlas.setBakeScale(scale);
    }

    /** 启动/字体初始化后的档位同步：把存档里的画质档解析落到图集（幂等）。 */
    public static void syncWorldLyricsQuality() {
        applyQualityTierNow(HudConfig.worldLyricsQualityTier);
    }

    /** 自动档解析（≤1080p→标准 5、1440p→高清 6、≥2160p→极致 7）。 */
    private static int autoBakeScale() {
        try {
            int h = net.minecraft.client.Minecraft.getInstance().getWindow().getHeight();
            if (h >= 2000) return 7;
            if (h >= 1300) return 6;
            return 5;
        } catch (Throwable t) {
            return 5;
        }
    }

    private static String[] engineChoices() {
        AutomixSettings.Engine[] engines = AutomixSettings.Engine.values();
        String[] labels = new String[engines.length];
        for (int i = 0; i < engines.length; i++) labels[i] = engines[i].getLabel();
        return labels;
    }

    private static void setEngineChoice(String value) {
        AutomixSettings.setEngine(AutomixSettings.Engine.fromStored(value));
    }
    private static String keywordPresetLabel() {
        return keywordPresetOf(HudConfig.worldLyricsKeywordColors);
    }

    private static void cycleKeywordPreset() {
        setKeywordPreset(keywordPresetLabel());
    }

    private static void setKeywordPreset(String selected) {
        HudConfig.worldLyricsKeywordColors = keywordTableOf(selected);
        saveNow();
    }

    private static String keywordPresetOf(String table) {
        if (table == null || table.isEmpty()) return KW_PRESET_OFF;
        // 表里存的是裸 hex（与 keywordTableOf 写入口径一致；R5 审查 P1-3：
        // 原来用 "0xFF5A8A" 反推永不命中，界面恒显「关闭」）
        if (table.contains("FF5A8A")) return KW_PRESET_SAKURA;
        if (table.contains("6FB9FF")) return KW_PRESET_AZURE;
        if (table.contains("7FE0A0")) return KW_PRESET_VERDANT;
        if (table.contains("FFC45A")) return KW_PRESET_AMBER;
        return KW_PRESET_OFF;
    }

    private static String keywordTableOf(String preset) {
        if (KW_PRESET_SAKURA.equals(preset)) return "花火:FF5A8A,星:FF5A8A,月:FF5A8A,夜:FF5A8A,梦:FF5A8A";
        if (KW_PRESET_AZURE.equals(preset)) return "光:6FB9FF,闪:6FB9FF,雷:6FB9FF,风:6FB9FF,翼:6FB9FF";
        if (KW_PRESET_VERDANT.equals(preset)) return "草:7FE0A0,风:7FE0A0,森:7FE0A0,叶:7FE0A0,夏:7FE0A0";
        if (KW_PRESET_AMBER.equals(preset)) return "日:FFC45A,炎:FFC45A,夏:FFC45A,阳:FFC45A,梦:FFC45A";
        return "";
    }

    private static Entry color(final String label, String description, ColorTarget target) {
        return new Entry(Kind.COLOR, new Supplier<String>() {
            public String get() {
                return label;
            }
        }, description, null, null, null, null, target);
    }

    private static final String[] SPECTRUM_BAR_COUNT_CHOICES = {
            "64 根", "96 根", "128 根", "160 根", "192 根", "自定义"
    };

    private static String spectrumBarCountPresetLabel() {
        int preset = HudConfig.spectrumBarCountPreset;
        return preset >= 0 && preset < SPECTRUM_BAR_COUNT_CHOICES.length
                ? SPECTRUM_BAR_COUNT_CHOICES[preset] : SPECTRUM_BAR_COUNT_CHOICES[0];
    }

    private static void setSpectrumBarCountPreset(String selected) {
        for (int i = 0; i < SPECTRUM_BAR_COUNT_CHOICES.length; i++) {
            if (SPECTRUM_BAR_COUNT_CHOICES[i].equals(selected)) {
                HudConfig.spectrumBarCountPreset = i;
                saveNow();
                return;
            }
        }
    }

    private static Entry choice(final String label, String description,
                                Supplier<String> value, Runnable next) {
        return choice(label, description, value, next, null, null);
    }

    private static Entry choice(final String label, String description,
                                Supplier<String> value, Runnable next,
                                String[] choices, Consumer<String> setter) {
        return new Entry(Kind.CHOICE, new Supplier<String>() {
            public String get() {
                return label;
            }
        }, description, null, null, next, value, ColorTarget.NONE, choices, setter);
    }
    private static Entry choice(final String label, String description,
                                Supplier<String> value, Runnable next,
                                String[] choices, Consumer<String> setter,
                                BooleanSupplier visibleWhen) {
        return new Entry(Kind.CHOICE, new Supplier<String>() {
            public String get() { return label; }
        }, description, null, null, next, value, ColorTarget.NONE, choices, setter, visibleWhen);
    }

    /** 带逐项说明的那档瓦片：见 {@link Entry#getChoiceHints()}。 */
    private static Entry choice(final String label, String description,
                                Supplier<String> value, Runnable next,
                                String[] choices, Consumer<String> setter, String[] hints) {
        return new Entry(Kind.CHOICE, new Supplier<String>() {
            public String get() { return label; }
        }, description, null, null, next, value, ColorTarget.NONE, choices, setter, null,
                false, hints);
    }

    /** 带可见性条件 + 逐项说明的 choice（3D 渲染画质档：只在现代化渲染开启时可见）。 */
    private static Entry choice(final String label, String description,
                                Supplier<String> value, Runnable next,
                                String[] choices, Consumer<String> setter,
                                BooleanSupplier visibleWhen, String[] hints) {
        return new Entry(Kind.CHOICE, new Supplier<String>() {
            public String get() { return label; }
        }, description, null, null, next, value, ColorTarget.NONE, choices, setter, visibleWhen,
                false, hints);
    }

    private static Entry themeGrid() {
        return new Entry(Kind.THEME_GRID, new Supplier<String>() {
            public String get() {
                return "配色预设";
            }
        }, "点击色卡立即切换；左上角是页面底色，中间是强调色。", null, null, null, null, ColorTarget.NONE);
    }

    private static Entry action(final String label, String description, Runnable run) {
        return new Entry(Kind.ACTION, new Supplier<String>() {
            public String get() {
                return label;
            }
        }, description, null, null, run, null, ColorTarget.NONE);
    }

    private static Entry action(Supplier<String> label, String description, Runnable run) {
        return new Entry(Kind.ACTION, label, description, null, null, run, null, ColorTarget.NONE);
    }
    private static Entry action(Supplier<String> label, String description, Runnable run,
                                BooleanSupplier visibleWhen) {
        return new Entry(Kind.ACTION, label, description, null, null, run, null, ColorTarget.NONE,
                null, null, visibleWhen);
    }

    private static Entry action(final String label, String description, Runnable run,
                                BooleanSupplier visibleWhen) {
        return new Entry(Kind.ACTION, new Supplier<String>() {
            public String get() { return label; }
        }, description, null, null, run, null, ColorTarget.NONE, null, null, visibleWhen);
    }

    /**
     * 一组命令按钮里的一个：紧凑药丸，与同伴并排换行。
     *
     * <p>与 {@link #action} 的分别不是像素而是语义 —— {@code action} 是「这一行自己做一件事」
     * （独立大行，图标底板 + ChevronRight），{@code button} 是「一组按钮里的一个」，
     * 像 folia 分析模型下面那排「从本机查找 / 更改位置 / 删除所有模型」。</p>
     */
    private static Entry button(final String label, String description, Runnable run) {
        return new Entry(Kind.ACTION, new Supplier<String>() {
            public String get() {
                return label;
            }
        }, description, null, null, run, null, ColorTarget.NONE, null, null, null, true, null);
    }

    private static Entry button(final String label, String description, Runnable run,
                                BooleanSupplier visibleWhen) {
        return new Entry(Kind.ACTION, new Supplier<String>() {
            public String get() {
                return label;
            }
        }, description, null, null, run, null, ColorTarget.NONE, null, null, visibleWhen,
                true, null);
    }

    private static Entry button(Supplier<String> label, String description, Runnable run,
                                BooleanSupplier visibleWhen) {
        return new Entry(Kind.ACTION, label, description, null, null, run, null,
                ColorTarget.NONE, null, null, visibleWhen, true, null);
    }

    private static MusicLyricsWidget lyrics() {
        try {
            return MuoniumPlayerExtension.getInstance().musicLyrics;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 开关改完立刻落盘：开关是离散动作，不像拖动那样每帧都在变。 */
    private static void saveNow() {
        try {
            HudConfig.save();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 动态封面依赖外部 ffmpeg。已经探测过但没找到时把结论写进标签，否则用户只会看到「开着却没动画」。
     * 这里只读缓存结论，不会在渲染线程上真的去探测（那会创建子进程并卡帧）。
     */
    public static String animatedCoverLabel() {
        try {
            return CloudMusic.animatedCoverToolingState() < 0 ? "动态封面 · 未检测到 ffmpeg" : "动态封面";
        } catch (Throwable ignored) {
            return "动态封面";
        }
    }

    /** 让旧版模块包装器的尺寸值与持久化视口保持一致。 */
    public static void syncLyricViewportModuleValues() {
        try {
            MusicLyricsWidget lyrics = MuoniumPlayerExtension.getInstance().musicLyrics;
            lyrics.width.setValue((double) HudConfig.lyricWidth);
            lyrics.height.setValue((double) HudConfig.lyricHeight);
        } catch (Throwable ignored) {
            // 渲染不需要该组件；HudConfig 仍是权威。
        }
    }

    /** 模块面板里的 {@code Animated Cover} 只是同一状态的另一处显示，改完要立刻跟上。 */
    public static void syncAnimatedCoverModuleValue() {
        try {
            MuoniumPlayerExtension.getInstance().musicInfo.animatedCover
                    .setValue(HudConfig.animatedCoverEnabled);
        } catch (Throwable ignored) {
            // 值存储还没准备好时无所谓：HudConfig 才是真实来源。
        }
    }

    /** 把两块 HUD 放回出厂位置与缩放。 */
    public static void resetHudPlacement() {
        HudConfig.infoX = 0.02f;
        HudConfig.infoY = 0.02f;
        HudConfig.infoScale = 1.0f;
        HudConfig.lyricX = 0.5f;
        HudConfig.lyricY = 0.85f;
        HudConfig.lyricScale = 1.0f;
        HudConfig.lyricWidth = 450.0f;
        HudConfig.lyricHeight = 120.0f;
        syncLyricViewportModuleValues();
        saveNow();
    }

    // ==================== 声明 ====================

    private static Category[] cache;

    /**
     * 全部分类。结果被缓存：条目本身无状态（取值都走 Supplier），重复构造只是浪费。
     */
    public static Category[] categories() {
        if (cache == null) {
            cache = build();
            lockGlassGroup(cache);
        }
        return cache;
    }

    /**
     * 手机档把「真玻璃材质」整组锁死：条目置灰、点击无效。
     *
     * <p>玻璃主题本身已在 {@code NCMTheme} 挡住（真玻璃预设不可选），这里锁的是那些
     * 只剩摆设意义的材质参数。Mesa 真驱动渲染器的实验档（hardDisabled=false）不锁——
     * 用户要能点到「真玻璃（实验）」那个开关；桌面同样不锁。</p>
     */
    private static void lockGlassGroup(Category[] categories) {
        if (!com.muoniumplayer.core.rendering.GlassMobileTier.hardDisabled()) return;
        for (Category category : categories) {
            for (Group group : category.groups) {
                if (!"真玻璃材质".equals(group.title)) continue;
                for (Entry entry : group.entries) {
                    entry.lockedWhen(new BooleanSupplier() {
                        public boolean getAsBoolean() {
                            return true;
                        }
                    });
                }
            }
        }
    }

    private static Category[] build() {
        return new Category[] {
                new Category("歌词样式", "桌面歌词与全屏页的文字：颜色、发光、逐字强调与排版", 0xFF8C7CFF, HudSettingsIcons.MIC,
                        new Group("当前行", HudSettingsIcons.MIC,
                                color("当前歌词颜色", "逐字填涂之后那部分文字的颜色。", ColorTarget.CURRENT),
                                toggle("当前歌词特效", "关掉之后当前行只做普通描边，不再放大与发光。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.currentLyricEffectsEnabled;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.currentLyricEffectsEnabled = !HudConfig.currentLyricEffectsEnabled;
                                                saveNow();
                                            }
                                        }),
                                toggle("OSD 逐字强调", "桌面歌词是否随演唱进度逐字放大与发光。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.osdKaraokeEmphasisEnabled;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.osdKaraokeEmphasisEnabled = !HudConfig.osdKaraokeEmphasisEnabled;
                                                saveNow();
                                            }
                                        }),
                                slider(HudSetting.CURRENT_LINE_SCALE, "当前行整体比其它行大多少，桌面歌词与全屏页共用。")),
                        new Group("其它行", HudSettingsIcons.SUBTITLES,
                                color("普通歌词颜色", "非当前行的文字颜色。", ColorTarget.NORMAL),
                                toggle("普通歌词特效", "关掉之后其它行不再发光。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.normalLyricEffectsEnabled;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.normalLyricEffectsEnabled = !HudConfig.normalLyricEffectsEnabled;
                                                saveNow();
                                            }
                                        }),
                                slider(HudSetting.NORMAL_OPACITY, "非当前行的整体不透明度。"),
                                slider(HudSetting.NORMAL_SCALE, "非当前行的字号缩放。"),
                                slider(HudSetting.NORMAL_GLOW, "非当前行的发光强度，与当前行同一套光晕、只是更淡。")),
                        new Group("发光", HudSettingsIcons.SPARKLE,
                                slider(HudSetting.CURRENT_GLOW, "桌面歌词当前行的发光强度，读数就是唱到那个字时的亮度。"),
                                slider(HudSetting.FULLSCREEN_GLOW, "全屏歌词页逐字发光的强度，全部样式共用。"),
                                slider(HudSetting.LYRIC_GLOW_SPREAD, "光晕铺多远。桌面与全屏按各自字号成比例缩放。")),
                        new Group("逐字效果 (OSD)", HudSettingsIcons.CAPTION,
                                slider(HudSetting.OSD_TRANSITION, "桌面歌词填涂边缘的软化宽度。"),
                                slider(HudSetting.OSD_PULSE, "桌面歌词正在唱的那个字放大多少。需要上面的「OSD 逐字强调」开着。",
                                        () -> HudConfig.osdKaraokeEmphasisEnabled),
                                slider(HudSetting.OSD_SMOOTHNESS, "桌面歌词逐字动画的平滑程度。"),
                                slider(HudSetting.OSD_DUET_SPREAD, "对唱段落左右分列的间距，0 为完全居中。")),
                        new Group("排版与滚动", HudSettingsIcons.RULER,
                                slider(HudSetting.NORMAL_SPACING, "相邻两行之间的间距。"),
                                slider(HudSetting.SCROLL_SMOOTHNESS, "换行滚动的柔和程度，越大越慢越顺。"),
                                slider(HudSetting.SECONDARY_OPACITY, "译文/音译那一行的不透明度。"),
                                toggle("边缘淡出", "歌词滚动到上下边界时是否渐隐，而不是被硬切掉。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.lyricEdgeFadeEnabled;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.lyricEdgeFadeEnabled = !HudConfig.lyricEdgeFadeEnabled;
                                                saveNow();
                                            }
                                        }),
                                slider(HudSetting.EDGE_FADE, "上下边缘渐隐区域的高度。"))),

                new Category("3D 歌词", "把歌词作为公告牌画进 3D 世界：悬浮在玩家前方，转视角跟随", 0xFF7CC8FF, HudSettingsIcons.VISIBILITY,
                        new Group("放置", HudSettingsIcons.MOVE,
                                toggle("3D 歌词", "开启后歌词渲染在游戏三维世界中（关掉 HUD 设置即可在世界里看到），而不是屏幕 HUD。下面的参数都要先开它。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.worldLyricsEnabled;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.worldLyricsEnabled = !HudConfig.worldLyricsEnabled;
                                                saveNow();
                                            }
                                        }),
                                slider(HudSetting.WORLD_LYRICS_DISTANCE, "歌词悬浮在玩家视线前方多远（方块）。",
                                        () -> HudConfig.worldLyricsEnabled),
                                toggle("朝向玩家", "歌词平面始终正对你。关闭后歌词固定朝向生成时的方向，绕到侧面会被压扁。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.worldLyricsFacePlayer;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.worldLyricsFacePlayer = !HudConfig.worldLyricsFacePlayer;
                                                saveNow();
                                            }
                                        }, () -> HudConfig.worldLyricsEnabled),
                                toggle("自动避让", "走近歌词时它自动向后退开，防止穿过文字。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.worldLyricsKeepAway;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.worldLyricsKeepAway = !HudConfig.worldLyricsKeepAway;
                                                saveNow();
                                            }
                                        }, () -> HudConfig.worldLyricsEnabled),
                                slider(HudSetting.WORLD_LYRICS_SPREAD, "多行歌词左右铺开的最大角度。",
                                        () -> HudConfig.worldLyricsEnabled),
                                slider(HudSetting.WORLD_LYRICS_SCALE, "整体缩放。1.0 时一行约 0.9 方块高。",
                                        () -> HudConfig.worldLyricsEnabled)),
                        new Group("字体与文本", HudSettingsIcons.SUBTITLES,
                                toggle("回屏保持", "转开视角时，歌词沿离视线最近的方向平滑滑回视野边缘（临界阻尼弹簧，不会突然跳）。离镜头的距离不变。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.worldLyricsStayOnScreen;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.worldLyricsStayOnScreen = !HudConfig.worldLyricsStayOnScreen;
                                                saveNow();
                                            }
                                        }, () -> HudConfig.worldLyricsEnabled),
                                toggle("不沉入地面", "歌词中心最低只到眼睛下方半格，不会沉进地面。跳跃/飞行时地板高度平滑跟随。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.worldLyricsAboveGround;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.worldLyricsAboveGround = !HudConfig.worldLyricsAboveGround;
                                                saveNow();
                                            }
                                        }, () -> HudConfig.worldLyricsEnabled),
                                toggle("更激进的适配", "光影包激活时，歌词完全绕开光影管线：推迟到光影后处理链全部跑完之后，用原版着色器画在最终画面上 —— 观感与不开光影时一致（代价：歌词会画在 HUD 之下、且不再与云/玻璃等世界元素有前后关系）。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.worldLyricsHudDraw;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.worldLyricsHudDraw = !HudConfig.worldLyricsHudDraw;
                                                saveNow();
                                            }
                                        }, () -> HudConfig.worldLyricsEnabled),
                                toggle("现代化渲染", "开 = Skija 超采样矢量字形 + 高级发光烘焙，跟随界面字体设置；关 = 原版像素字体。动效组里的心象与绘光也依赖它。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.worldLyricsModernStyle;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.worldLyricsModernStyle = !HudConfig.worldLyricsModernStyle;
                                                saveNow();
                                            }
                                        }, () -> HudConfig.worldLyricsEnabled),
                                toggle("分割歌词行", "把「两句挤在一行」的歌词（分隔符 / ）拆成两行分别渲染。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.worldLyricsSplitLines;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.worldLyricsSplitLines = !HudConfig.worldLyricsSplitLines;
                                                saveNow();
                                            }
                                        }, () -> HudConfig.worldLyricsEnabled),
                                color("文字颜色", "3D 歌词的文字颜色，选白色就是纯白。点击用取色器自定。",
                                        ColorTarget.WORLD_LYRICS),
                                choice("关键字染色", "绘光动效的关键字着色方案：循环切换预置的词色组合，染色只作用于完整命中「词」的字。关闭时整句同色。",
                                        new Supplier<String>() {
                                            public String get() {
                                                return keywordPresetLabel();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                cycleKeywordPreset();
                                                saveNow();
                                            }
                                        }, keywordPresetChoices(), HudSettingsModel::setKeywordPreset,
                                        new String[] {"整句同色，不染色。",
                                                "花火/星/月/夜/梦 染樱红。",
                                                "光/闪/雷/风/翼 染苍蓝。",
                                                "草/风/森/叶/夏 染翠绿。",
                                                "日/炎/夏/阳/梦 染琥珀。"}),
                                choice("渲染画质", "字形烘焙的超采样倍率：越高，靠近歌词、4K 分辨率或望远镜放大时文字越精细（毛边/发虚越少）。自动按游戏渲染分辨率选档；切换会重建字形图集（下一句歌词的字会重新烘出）。",
                                        new Supplier<String>() {
                                            public String get() {
                                                return qualityTierLabel();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                cycleQualityTier();
                                                saveNow();
                                            }
                                        }, new String[] {"自动", "流畅", "标准", "高清", "极致"},
                                        HudSettingsModel::setQualityTier,
                                        () -> HudConfig.worldLyricsEnabled && HudConfig.worldLyricsModernStyle,
                                        new String[] {"按渲染分辨率自动：1080p 标准、2K 高清、4K 极致。",
                                                "4× 烘焙：低配省显存省 CPU，近看略糙。",
                                                "5× 烘焙：默认档。",
                                                "6× 烘焙：2K 屏推荐，烘焙成本 +44%。",
                                                "7× 烘焙：4K/望远镜党，烘焙成本 +96%，显存占用略增。"})),
                        new Group("发光", HudSettingsIcons.SPARKLE,
                                toggle("发光", "文字外发光（Deep Glow 风格），随纹理一起烘焙。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.worldLyricsGlowEnabled;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.worldLyricsGlowEnabled = !HudConfig.worldLyricsGlowEnabled;
                                                saveNow();
                                            }
                                        }, () -> HudConfig.worldLyricsEnabled),
                                slider(HudSetting.WORLD_LYRICS_GLOW_STRENGTH, "发光强度。",
                                        () -> HudConfig.worldLyricsEnabled && HudConfig.worldLyricsGlowEnabled),
                                slider(HudSetting.WORLD_LYRICS_GLOW_SPREAD, "光晕铺多远。",
                                        () -> HudConfig.worldLyricsEnabled && HudConfig.worldLyricsGlowEnabled),
                                color("发光颜色", "发光的颜色。默认白；配合深色环境可换暖色或主题色。",
                                        ColorTarget.WORLD_LYRICS_GLOW)),
                        new Group("动效", HudSettingsIcons.TUNE,
                                choice("动效风格", "换句与逐字的表现方式，同一时间只有一种生效。逐词弹出需要逐字歌词；心象与绘光需要「现代化渲染」开着，且绘光优先于心象。",
                                        new Supplier<String>() {
                                            public String get() {
                                                return worldEffectName();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                cycleWorldEffect();
                                            }
                                        }, new String[] {"无", "逐词弹出", "心象", "绘光"},
                                        HudSettingsModel::setWorldEffect,
                                        new String[] {"静态歌词，不加动效。",
                                                "Word Pop：词被唱到的瞬间弹一下。",
                                                "Cadenza：整行分块，每块随机出入场动画 + 呼吸 + 提亮脉冲。",
                                                "Huiguang：换句逐字飞行重排 + 发光径迹 + 卡拉OK点亮。"}))),

                new Category("面板与卡片", "桌面歌词的视口尺寸与 Blossom 毛玻璃卡片", 0xFFE087C0, HudSettingsIcons.CAPTION,
                        new Group("大小与换行", HudSettingsIcons.RULER,
                                slider(HudSetting.OSD_WIDTH, "桌面歌词视口的基础宽度。超出这一宽度的原文、译文和逐字歌词都会自动换行。"),
                                slider(HudSetting.OSD_HEIGHT, "桌面歌词视口的基础高度。对唱或长行换行后放不下时会优先保留原文。")),
                        new Group("卡片样式", HudSettingsIcons.PALETTE,
                                choice("桌面歌词样式", "在「经典」与「Blossom」之间切换。Blossom 是 modernMP 那套毛玻璃卡片：面板自带背景模糊，滚动走弹簧级联，逐字扫光而不画光晕。",
                                        new Supplier<String>() {
                                            public String get() {
                                                return BlossomLyricStyle.currentStyleName();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                BlossomLyricStyle.cycleStyle();
                                                saveNow();
                                            }
                                        }, blossomStyleChoices(), HudSettingsModel::setBlossomStyle,
                                        new String[] {"旧版逐行描边歌词。",
                                                "毛玻璃卡片，逐字扫光。"}),
                                slider(HudSetting.BLOSSOM_LINES, "Blossom 卡片里同时显示几行。为 1 行时不滚动，当前行整块居中。",
                                        () -> HudConfig.osdLyricStyle == 1)),
                        new Group("毛玻璃卡片", HudSettingsIcons.SPARKLE,
                                slider(HudSetting.BLOSSOM_BLUR, "卡片背后实时模糊的强度。归零就是一块普通的半透明底。",
                                        () -> HudConfig.osdLyricStyle == 1),
                                slider(HudSetting.BLOSSOM_OPACITY, "卡片深色底的不透明度。压得越低透出的背景越多。",
                                        () -> HudConfig.osdLyricStyle == 1),
                                toggle("踩点描边", "卡片边框随鼓点呼吸，颜色取当前歌词色。需要上面的「OSD 逐字强调」开着才有节拍数据。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.blossomRhythmBorder;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.blossomRhythmBorder = !HudConfig.blossomRhythmBorder;
                                                saveNow();
                                            }
                                        }),
                                slider(HudSetting.BLOSSOM_BORDER, "描边的强度，同时影响它的透明度与线宽。",
                                        () -> HudConfig.osdLyricStyle == 1 && HudConfig.blossomRhythmBorder))),

                new Category("灵动岛", "顶部胶囊的外观、常驻内容与动画时序", 0xFF65A9FF, HudSettingsIcons.ISLAND,
                        new Group("外观与尺寸", HudSettingsIcons.TUNE,
                                toggle("下载灵动岛", "总开关。关掉之后下载、通知与常驻都不再显示，下面这一页其余条目也随之收起。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.dynamicIslandEnabled;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.dynamicIslandEnabled = !HudConfig.dynamicIslandEnabled;
                                                saveNow();
                                            }
                                        }),
                                choice("灵动岛样式", "在紧凑、卡片、液态玻璃、系统卡片、音乐焦点、回弹胶囊之间循环。液态玻璃那一款不吃真玻璃材质开关。",
                                        new Supplier<String>() {
                                            public String get() {
                                                return DownloadDynamicIsland.getStyleName();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                DownloadDynamicIsland.cycleStyle();
                                            }
                                        }, islandStyleChoices(), HudSettingsModel::setIslandStyle,
                                        () -> HudConfig.dynamicIslandEnabled),
                                slider(HudSetting.DYNAMIC_ISLAND_SCALE, "整体大小。",
                                        () -> HudConfig.dynamicIslandEnabled),
                                slider(HudSetting.DYNAMIC_ISLAND_TEXT_SCALE, "岛内文字的字号缩放。",
                                        () -> HudConfig.dynamicIslandEnabled),
                                slider(HudSetting.DYNAMIC_ISLAND_MAX_WIDTH, "基础宽度。文案更长时仍会自动伸展。",
                                        () -> HudConfig.dynamicIslandEnabled),
                                slider(HudSetting.DYNAMIC_ISLAND_PROGRESS_HEIGHT, "下载进度条的粗细。",
                                        () -> HudConfig.dynamicIslandEnabled),
                                toggle("岛内搜索", "游戏内按 Ctrl+F（可在按键设置改）在灵动岛里直接搜歌。只在岛显示时出现。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.islandSearchEnabled;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.islandSearchEnabled = !HudConfig.islandSearchEnabled;
                                                saveNow();
                                            }
                                        }, () -> HudConfig.dynamicIslandEnabled)),
                        new Group("常驻内容", HudSettingsIcons.TIMER,
                                toggle("常驻灵动岛", "没有下载与通知时也留在屏幕顶部。通知到来只做内容淡入，不重播入场动画。下面的内容与时长都要先开它。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.dynamicIslandAlwaysOn;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.dynamicIslandAlwaysOn = !HudConfig.dynamicIslandAlwaysOn;
                                                saveNow();
                                            }
                                        }, () -> HudConfig.dynamicIslandEnabled),
                                toggle("常驻 · 帧率", "状态页显示当前帧率。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.dynamicIslandAmbientFps;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.dynamicIslandAmbientFps = !HudConfig.dynamicIslandAmbientFps;
                                                saveNow();
                                            }
                                        }, () -> HudConfig.dynamicIslandEnabled && HudConfig.dynamicIslandAlwaysOn),
                                toggle("常驻 · 延迟", "状态页显示服务器延迟。单机或未进服时显示两个短横。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.dynamicIslandAmbientPing;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.dynamicIslandAmbientPing = !HudConfig.dynamicIslandAmbientPing;
                                                saveNow();
                                            }
                                        }, () -> HudConfig.dynamicIslandEnabled && HudConfig.dynamicIslandAlwaysOn),
                                toggle("常驻 · 时间", "状态页显示系统时间（时:分）。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.dynamicIslandAmbientClock;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.dynamicIslandAmbientClock = !HudConfig.dynamicIslandAmbientClock;
                                                saveNow();
                                            }
                                        }, () -> HudConfig.dynamicIslandEnabled && HudConfig.dynamicIslandAlwaysOn),
                                toggle("常驻 · 正在播放", "把当前歌曲作为一页参与轮换：左侧换成封面，右侧显示歌名与歌手。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.dynamicIslandAmbientNowPlaying;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.dynamicIslandAmbientNowPlaying = !HudConfig.dynamicIslandAmbientNowPlaying;
                                                saveNow();
                                            }
                                        }, () -> HudConfig.dynamicIslandEnabled && HudConfig.dynamicIslandAlwaysOn),
                                toggle("常驻 · 轮换显示", "关掉之后固定停在状态页；状态三项全关时才固定显示正在播放页。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.dynamicIslandAmbientRotate;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.dynamicIslandAmbientRotate = !HudConfig.dynamicIslandAmbientRotate;
                                                saveNow();
                                            }
                                        }, () -> HudConfig.dynamicIslandEnabled && HudConfig.dynamicIslandAlwaysOn),
                                slider(HudSetting.DYNAMIC_ISLAND_AMBIENT_STATUS,
                                        "状态页（帧率/延迟/时间）每次停留多久。三个数字扫一眼就够，可以短一些。",
                                        () -> HudConfig.dynamicIslandEnabled && HudConfig.dynamicIslandAlwaysOn
                                                && HudConfig.dynamicIslandAmbientRotate),
                                slider(HudSetting.DYNAMIC_ISLAND_AMBIENT_NOW_PLAYING,
                                        "正在播放页每次停留多久。要读完歌名与歌手，通常需要比状态页更久。",
                                        () -> HudConfig.dynamicIslandEnabled && HudConfig.dynamicIslandAlwaysOn
                                                && HudConfig.dynamicIslandAmbientRotate)),
                        new Group("时序与动画", HudSettingsIcons.SPEED,
                                slider(HudSetting.DYNAMIC_ISLAND_COMPLETION_HOLD, "下载完成后结果停留多久再收起。",
                                        () -> HudConfig.dynamicIslandEnabled),
                                slider(HudSetting.DYNAMIC_ISLAND_QUEUE_INTERVAL, "排队的两条通知之间至少间隔多久。",
                                        () -> HudConfig.dynamicIslandEnabled),
                                choice("动画速度", "弹出 / 收起 / 内容切换三条动画共用一档速度；要各调各的就选自定义，下面才出现三条倍率。",
                                        new Supplier<String>() {
                                            public String get() {
                                                return islandSpeedName();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                cycleIslandSpeed();
                                            }
                                        }, new String[] {"慢", "标准", "快", "自定义"}, HudSettingsModel::setIslandSpeed,
                                        new String[] {"更从容的出入场，适合常盯着岛看。",
                                                "出厂节奏。",
                                                "更干脆的出入场。",
                                                "分别微调弹出 / 收起 / 内容切换三条倍率。"}),
                                slider(HudSetting.DYNAMIC_ISLAND_EXPAND_SPEED, "展开的速度倍率。",
                                        HudSettingsModel::islandSpeedCustom),
                                slider(HudSetting.DYNAMIC_ISLAND_COLLAPSE_SPEED, "收起的速度倍率。",
                                        HudSettingsModel::islandSpeedCustom),
                                slider(HudSetting.DYNAMIC_ISLAND_CONTENT_SPEED, "岛内内容交叉淡入的速度倍率。",
                                        HudSettingsModel::islandSpeedCustom),
                                slider(HudSetting.DYNAMIC_ISLAND_ENTRANCE_DURATION, "入场动画的时长。",
                                        () -> HudConfig.dynamicIslandEnabled),
                                slider(HudSetting.DYNAMIC_ISLAND_OVERSHOOT, "入场结束时的回弹幅度，0 为不回弹。",
                                        () -> HudConfig.dynamicIslandEnabled),
                                slider(HudSetting.DYNAMIC_ISLAND_SPINNER_SPEED, "加载转子的转速倍率。",
                                        () -> HudConfig.dynamicIslandEnabled))),

                new Category("无缝切换", "两首歌之间的交接方式与衔接处理", 0xFF5FD3E8, HudSettingsIcons.SPEED,
                        new Group("模式与开关", HudSettingsIcons.SPEED,
                                toggle("无缝切换", "打开后提前解码下一首并交叉淡化；关掉则切歌时先停下再重新下载解码，中间有静音间隙。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return AutomixSettings.isEnabled();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                AutomixSettings.setEnabled(!AutomixSettings.isEnabled());
                                            }
                                        }),
                                choice("处理方案", "原版＝等功率淡化与低架处理；Folia＝本地模型分析结构、节奏与调性后选切拍与交接方式。效果发生在队列自动切歌的交接瞬间。",
                                        new Supplier<String>() {
                                            public String get() { return AutomixSettings.getEngineLabel(); }
                                        }, new Runnable() {
                                            public void run() { AutomixSettings.cycleEngine(); }
                                        }, engineChoices(), HudSettingsModel::setEngineChoice,
                                        new String[] {"保留现有等功率淡化与低架处理。",
                                                "分析后挑切拍与交接方式；配合下面的「进阶模式」可达最深分析。"}),
                                toggle("进阶模式", "把交接点从曲尾这几秒放开到整首歌：扫完整曲包络后，在「从哪退、从哪进」的所有候选里按八项打分挑一对，分不够就不挑；同时打开队列的「排列」按钮与双侧人声处理。代价是每首初播要在后台多解一遍整曲。它是独立的分析深度开关——处理方案选原版或 Folia 都能开，模型/包络不可用时自动回落普通切歌。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() { return AutomixSettings.isAdvancedModeEnabled(); }
                                        }, new Runnable() {
                                            public void run() { AutomixSettings.setAdvancedModeEnabled(!AutomixSettings.isAdvancedModeEnabled()); }
                                        }),
                                toggle("过渡动画", "圆环保留中央交接提示；EQ 曲线在半透明灵动岛内滚动，衔接时小幅展开、结束后收回。曲线样式需要开启灵动岛；关闭动画不影响音频处理。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() { return AutomixSettings.isTransitionAnimationEnabled(); }
                                        }, new Runnable() {
                                            public void run() {
                                                boolean enabled = !AutomixSettings.isTransitionAnimationEnabled();
                                                AutomixSettings.setTransitionAnimationEnabled(enabled);
                                                if (enabled) AutomixTransitionAnimation.preview();
                                            }
                                        }),
                                choice("动画样式", "圆环保留原样；EQ 曲线收进灵动岛，以两条半透明滚动包络和轻量低频伴线展示真实交接，不再弹出大面板。",
                                        new Supplier<String>() {
                                            public String get() { return AutomixSettings.getTransitionAnimationStyleLabel(); }
                                        }, new Runnable() {
                                            public void run() {
                                                AutomixSettings.cycleTransitionAnimationStyle();
                                                if (AutomixSettings.isTransitionAnimationEnabled()) AutomixTransitionAnimation.preview();
                                            }
                                        }, new String[] {"圆环", "EQ 曲线"}, selected -> {
                                            AutomixSettings.setTransitionAnimationStyle(
                                                    "EQ 曲线".equals(selected) ? AutomixSettings.TransitionAnimationStyle.CURVE
                                                            : AutomixSettings.TransitionAnimationStyle.RING);
                                            if (AutomixSettings.isTransitionAnimationEnabled()) AutomixTransitionAnimation.preview();
                                        }),
                                toggle("切点标记", "在游戏内歌曲信息条、播放页与歌词页的进度条上画出这次交接的位置：竖线是「从哪里开始切」，半透明带子是「这一段两首歌叠在一起」。只在系统真的排出了交接时才显示——还没预备好、或者一起听那一份计划还没到，就什么都不画，不猜一个位置。接缝本身照常发生，关掉只是看不见。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() { return AutomixSettings.isCueMarkerEnabled(); }
                                        }, new Runnable() {
                                            public void run() { AutomixSettings.setCueMarkerEnabled(!AutomixSettings.isCueMarkerEnabled()); }
                                        }, AutomixSettings::isEnabled),
                                choice("原版重叠时长", "仅原版处理使用；Folia 会按拍数、结构与人声尾音动态决定重叠长度。",
                                        new Supplier<String>() {
                                            public String get() {
                                                return AutomixSettings.getOverlapLabel();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                AutomixSettings.cycleOverlapSeconds();
                                            }
                                        }, overlapChoices(), HudSettingsModel::setOverlapChoice, () -> !AutomixSettings.isFoliaEngine())),
                        new Group("衔接处理", HudSettingsIcons.SPARKLE,
                                toggle("跳过前奏", "打开后新歌从主体进来，跳掉没内容的前奏；关掉则一律从第 0 秒淡入，也就是旧版的平滑切换。前奏位置照样会被分析，关掉不影响对拍。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return AutomixSettings.isSkipIntroEnabled();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                AutomixSettings.setSkipIntroEnabled(!AutomixSettings.isSkipIntroEnabled());
                                            }
                                        }),
                                toggle("小节对齐", "把交接点挪到最近的小节线上，让两首歌的鼓点落在一起而不是互相错开。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return AutomixSettings.isBeatAlignEnabled();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                AutomixSettings.setBeatAlignEnabled(!AutomixSettings.isBeatAlignEnabled());
                                            }
                                        }),
                                toggle("低频交换", "重叠期间压掉一首的低频，两条鼓与贝斯叠在一起才不会糊成一团。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return AutomixSettings.isBassSwapEnabled();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                AutomixSettings.setBassSwapEnabled(!AutomixSettings.isBassSwapEnabled());
                                            }
                                        }),
                                toggle("去人声淡入", "新歌淡入的前半段压掉中置人声只留伴奏，避免两首歌的人声同时在唱。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return AutomixSettings.isVocalDuckEnabled();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                AutomixSettings.setVocalDuckEnabled(!AutomixSettings.isVocalDuckEnabled());
                                            }
                                        }),
                                toggle("节奏锁定", "用 WSOLA 微调旧歌速度去追上新歌的 BPM，只改变速度、不改变音高；误差过大时保持原速。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return AutomixSettings.isTempoLockEnabled();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                AutomixSettings.setTempoLockEnabled(!AutomixSettings.isTempoLockEnabled());
                                            }
                                        }),
                                toggle("音高跳跃", "仅原版处理使用的半音吸附；Folia 不会对整首歌做移调。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return AutomixSettings.isPitchMatchEnabled();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                AutomixSettings.setPitchMatchEnabled(!AutomixSettings.isPitchMatchEnabled());
                                            }
                                        }, () -> !AutomixSettings.isFoliaEngine())),
                        new Group("分析模型", HudSettingsIcons.STORAGE,
                                toggle("模型推理状态", "Folia 调用 Beat This! 或 HTDemucs 时，在动态岛显示推理中；成功后显示完成耗时，失败则提示已回退传统分析。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() { return AutomixSettings.isModelInferenceStatusEnabled(); }
                                        }, new Runnable() {
                                            public void run() { AutomixSettings.setModelInferenceStatusEnabled(!AutomixSettings.isModelInferenceStatusEnabled()); }
                                        }, AutomixSettings::isFoliaEngine),
                                action(() -> FoliaModelManager.statusLabel(FoliaModelManager.Kind.BEAT_THIS),
                                        "Beat This! 拍点/小节模型。Folia 模式会在已安装时优先使用它，未安装则回退传统分析。",
                                        () -> installModel(FoliaModelManager.Kind.BEAT_THIS), AutomixSettings::isFoliaEngine),
                                action(() -> FoliaModelManager.statusLabel(FoliaModelManager.Kind.HTDEMUCS),
                                        "HTDemucs 四轨分离模型。仅在开启表现模式时分析首尾窗口；关闭时不会推理，缺失时继续使用频段与增益处理。",
                                        () -> installModel(FoliaModelManager.Kind.HTDEMUCS), AutomixSettings::isFoliaEngine),
                                action(() -> FoliaModelManager.statusLabel(FoliaModelManager.runtimeKind()),
                                        "模型所需的运行组件（桌面是 Python，安卓是 ONNX 运行时）。安装 Beat This! 或 HTDemucs 前需要它。",
                                        () -> installModel(FoliaModelManager.runtimeKind()), AutomixSettings::isFoliaEngine),
                                button("从本机查找模型", "扫描下载目录、桌面和游戏目录，校验文件名与 SHA-256 后导入。", HudSettingsModel::scanModels, AutomixSettings::isFoliaEngine),
                                button(FoliaModelManager::directoryLabel, "当前模型与运行时位置；点击选择新目录，不改变已经下载的音频缓存。", () -> {
                                    String detail = FoliaModelManager.chooseDirectory();
                                    DownloadDynamicIsland.showCacheSweep(detail, true);
                                }, AutomixSettings::isFoliaEngine),
                                button("恢复默认模型位置", "恢复为 config/muonium/models。", () -> FoliaModelManager.resetDirectory(), AutomixSettings::isFoliaEngine),
                                button("删除所有模型", "删除 Beat This!、HTDemucs 和运行组件，不会删除歌曲缓存。", HudSettingsModel::deleteModels, AutomixSettings::isFoliaEngine))),

                new Category("界面与主题", "设置界面本身、外部播放器镜像、窗口缩放与配色", 0xFFE59AC8, HudSettingsIcons.NOTE,
                        new Group("音乐频谱", HudSettingsIcons.NOTE,
                                toggle("音乐频谱", "真实 FFT 频率分析；隐藏时停止采样与分析任务，音乐播放不受影响。",
                                        () -> HudConfig.spectrumEnabled,
                                        () -> { HudConfig.spectrumEnabled = !HudConfig.spectrumEnabled; saveNow(); }),
                                toggle("游戏内显示", "在游戏画面底部显示音乐频谱，不占用灵动岛通知位。",
                                        () -> HudConfig.spectrumInHud,
                                        () -> { HudConfig.spectrumInHud = !HudConfig.spectrumInHud; saveNow(); },
                                        () -> HudConfig.spectrumEnabled),
                                choice("频谱风格", "底部全宽：半透明渐变柱合并为一次路径绘制；两侧留空：保留快捷栏中央空间。仅影响游戏内显示。",
                                        () -> HudConfig.spectrumHudStyle == 1 ? "两侧留空" : "底部全宽",
                                        () -> { HudConfig.spectrumHudStyle = HudConfig.spectrumHudStyle == 1 ? 0 : 1; saveNow(); },
                                        new String[]{"底部全宽", "两侧留空"},
                                        value -> { HudConfig.spectrumHudStyle = "两侧留空".equals(value) ? 1 : 0; saveNow(); },
                                        () -> HudConfig.spectrumEnabled && HudConfig.spectrumInHud),
                                choice("全宽频谱柱数", "游戏内全宽样式支持 64–192 根常用档；选择「自定义」后可用滑块在 48–256 根间细调。高密度仍合并为单个路径批次，低分辨率下会自动限制密度以保留清晰间距。",
                                        () -> spectrumBarCountPresetLabel(),
                                        () -> { HudConfig.spectrumBarCountPreset = (HudConfig.spectrumBarCountPreset + 1) % SPECTRUM_BAR_COUNT_CHOICES.length; saveNow(); },
                                        SPECTRUM_BAR_COUNT_CHOICES, HudSettingsModel::setSpectrumBarCountPreset,
                                        () -> HudConfig.spectrumEnabled && HudConfig.spectrumInHud),
                                slider(HudSetting.SPECTRUM_BAR_COUNT_CUSTOM, "自定义柱数；在上方选择「自定义」时显示，拖动后立即预览、松开后保存。",
                                        () -> HudConfig.spectrumEnabled && HudConfig.spectrumInHud
                                                && HudConfig.spectrumBarCountPreset == 5),
                                toggle("播放器内显示", "在控制栏和全屏歌词播放器底部显示频谱，随界面进出场，并跟随当前主题或封面配色。",
                                        () -> HudConfig.spectrumInPlayer,
                                        () -> { HudConfig.spectrumInPlayer = !HudConfig.spectrumInPlayer; saveNow(); },
                                        () -> HudConfig.spectrumEnabled),
                                choice("全屏播放器频谱", "底部全宽：铺满播放器底边并使用更多频谱线；两侧铺开：沿用双侧线条，但在全屏页自适应铺满，不留快捷栏式空隙。",
                                        () -> HudConfig.spectrumFullscreenStyle == 1 ? "两侧铺开" : "底部全宽",
                                        () -> { HudConfig.spectrumFullscreenStyle = HudConfig.spectrumFullscreenStyle == 1 ? 0 : 1; saveNow(); },
                                        new String[]{"底部全宽", "两侧铺开"},
                                        value -> { HudConfig.spectrumFullscreenStyle = "两侧铺开".equals(value) ? 1 : 0; saveNow(); },
                                        () -> HudConfig.spectrumEnabled && HudConfig.spectrumInPlayer),
                                choice("频谱配色", "跟随主题会复用当前主题与封面主题的渐变；封面取色只影响频谱，不改播放器主题。",
                                        () -> HudConfig.spectrumColorSource == 1 ? "封面取色" : "跟随主题",
                                        () -> { HudConfig.spectrumColorSource = 1 - HudConfig.spectrumColorSource; saveNow(); },
                                        new String[]{"跟随主题", "封面取色"},
                                        value -> { HudConfig.spectrumColorSource = "封面取色".equals(value) ? 1 : 0; saveNow(); }),
                                slider(HudSetting.SPECTRUM_OPACITY, "控制频谱透明度；低值适合游戏内常驻。",
                                        () -> HudConfig.spectrumEnabled),
                                slider(HudSetting.SPECTRUM_HEIGHT, "仅调整频谱高度，不改变播放器或通知的尺寸。",
                                        () -> HudConfig.spectrumEnabled)),
                        new Group("设置界面", HudSettingsIcons.MODERN_UI,
                                choice("设置界面由谁渲染", "内置＝本项目自绘的界面；OneConfig / ModernUI＝交给已安装的外部界面。"
                                        + "选中的外部界面不可用时自动回退内置，不报错也不弹提示，切换在下次按 H 时生效。",
                                        new Supplier<String>() {
                                            public String get() {
                                                return uiLayerLabel();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                cycleUiLayer();
                                            }
                                        }, new String[] {"内置", "OneConfig", "ModernUI"},
                                                HudSettingsModel::setUiLayerChoice,
                                                new String[] {"自绘，不依赖任何 mod。",
                                                        "OneConfig 的 MuoniumPlayer HUD 页，需已安装 OneConfig v1。",
                                                        "ModernUI 的面板，需已安装 ModernUI 且本 Minecraft 版本有对应构件。"})),
                        new Group("外部播放器镜像", HudSettingsIcons.NOTE,
                                choice("播放态来源", "内置＝只播本模组自己队列里的歌（默认）。"
                                        + "系统镜像＝不发声，改由系统里正在播的外部播放器供态：曲名、进度、播放状态取自 Windows 的系统媒体控制，"
                                        + "歌词与封面则按曲名歌手回本模组的曲库匹配，匹配上就与平常听歌完全一样（含全屏歌词页与逐字）。"
                                        + "镜像开着时本模组队列不会起播。",
                                        new Supplier<String>() {
                                            public String get() {
                                                return playbackFeedLabel();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                cyclePlaybackFeed();
                                            }
                                        }, new String[] {"内置播放器", "系统播放器镜像"},
                                                HudSettingsModel::setPlaybackFeedChoice,
                                                new String[] {"只播本模组自己队列里的歌。",
                                                        "跟随 Windows 正在播的外部播放器，需要 Windows 10/11 与一个支持系统媒体控制的播放器。"}),
                                action(new Supplier<String>() {
                                            public String get() {
                                                return "系统媒体状态";
                                            }
                                        },
                                        "当前镜像取数链路的状态；异常时这里会写明原因（缺编译器 / 播放器没注册会话 / 助手反复退出）。",
                                        HudSettingsModel::probeExternalMedia,
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return ExternalPlayback.supportsPlatform();
                                            }
                                        })),
                        new Group("窗口与缩放", HudSettingsIcons.RULER,
                                toggle("窗口自适应缩放",
                                        "按物理游戏窗口尺寸计算连续缩放，播放器大小不随 GUI Scale 整数档位跳动；关闭后跟随游戏内 GUI 缩放。",
                                        NCMPlayerConfig::isWindowAdaptiveScaleEnabled,
                                        NCMPlayerConfig::toggleWindowAdaptiveScale),
                                slider(HudSetting.LYRIC_SEARCH_UI_SCALE,
                                        "手动选词那页弹窗的整体大小。它照抄的是网页的 CSS 像素，1.00× 就是「一个像素一个像素」地画，"
                                        + "正文只有 14 像素、比播放器其它界面小一半；放大这一倍率，弹窗、文字、卡片、间距一起等比走，"
                                        + "内部排版比例不变。注意超过约 1.5× 之后弹窗宽度会先撞上「面板的 85%」那一档，形状会更扁一些，"
                                        + "换来的是正文从 14 像素长到 21 像素。改完立刻生效，不用重开。")),
                        new Group("动画", HudSettingsIcons.SPEED,
                                slider(HudSetting.PLAYER_FADE_SPEED,
                                        "播放器界面打开与关闭那一下整屏淡入淡出的快慢，1.0 是默认速度。"
                                        + "只影响这层淡入淡出，界面内部各元素的动画节奏保持原样。"),
                                slider(HudSetting.PLAYER_UI_SWITCH_SPEED,
                                        "左侧导航换栏目时内容页的淡入缩放与选中指示条滑动的快慢。"
                                        + "1.25 是出厂默认（比早期版本略快），1.0 是历史速度，越大越快。")),
                        new Group("配色", HudSettingsIcons.PALETTE,
                                choice("播放器主题", "点击在全部预设之间依次切换。",
                                        new Supplier<String>() {
                                            public String get() {
                                                return NCMTheme.getCurrentName();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                applyTheme(NCMTheme.next());
                                            }
                                        }, themeChoices(), HudSettingsModel::setThemeChoice),
                                themeGrid()),
                        new Group("真玻璃材质", HudSettingsIcons.SPARKLE,
                                toggle("播放器面板", "实时采集游戏画面，用非线性透镜重采样还原液态玻璃；不再使用磨砂底。"
                                        + "关掉就退回原来的渐变高光。前提：播放器主题需选「液态玻璃 / 琉璃」类"
                                        + "（本页底部的主题色卡）；其余主题与手机档下这一整组不生效。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.glassSurfacePlayer;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.glassSurfacePlayer = !HudConfig.glassSurfacePlayer;
                                                saveNow();
                                            }
                                        }),
                                toggle("灵动岛", "顶部那颗岛也用真背景，八款样式里有七款吃这一档"
                                        + "（回弹胶囊是纯色平面，保持原样）。岛面积小，折射读不出来，"
                                        + "吃到的是背景透色与边缘光。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.glassSurfaceIsland;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.glassSurfaceIsland = !HudConfig.glassSurfaceIsland;
                                                saveNow();
                                            }
                                        }),
                                toggle("桌面歌词", "屏幕下方那张歌词卡片改用真背景。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.glassSurfaceDesktopLyric;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.glassSurfaceDesktopLyric = !HudConfig.glassSurfaceDesktopLyric;
                                                saveNow();
                                            }
                                        }),
                                toggle("HUD 设置界面", "三套设置界面自己的面板也改成真玻璃。"
                                        + "那三块面板平时是不透明的，这一项同时决定它们压不压薄。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.glassSurfaceEditors;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.glassSurfaceEditors = !HudConfig.glassSurfaceEditors;
                                                saveNow();
                                            }
                                        }),
                                toggle("真玻璃（实验·Mesa 渲染器）", "只在手机上用 Mesa 真驱动渲染器"
                                        + "（Zink / VirGL / Freedreno / Panfrost）时出现。"
                                        + "这些渲染器不是 GL 翻译层，真玻璃的采集与折射在语义上与桌面同族，"
                                        + "但性能未经真机标定，默认关闭——卡了就关。"
                                        + "MobileGlues / MobileGL 等翻译层渲染器上这一项无效。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.glassMobileExperimental;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.glassMobileExperimental = !HudConfig.glassMobileExperimental;
                                                com.muoniumplayer.core.rendering.GlassMobileTier
                                                        .setUserOptIn(HudConfig.glassMobileExperimental);
                                                saveNow();
                                            }
                                        }),
                                toggle("实时背景采集", "每一帧都重新采一次游戏画面，玻璃背后那一层就跟着画面一起走"
                                        + "——走位、转视角、水波与树叶都是连续的，而不是默认那样最多 30Hz 更新、"
                                        + "动起来偶尔跳一帧。<b>这是给好电脑的</b>：采集本身只花一毫秒上下，"
                                        + "贵的是它逼着背后那张图每帧重糊一遍全屏。低配机开着会觉得整体掉帧，"
                                        + "关掉即可，玻璃其它参数不受影响。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.glassRealtimeCapture;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.glassRealtimeCapture = !HudConfig.glassRealtimeCapture;
                                                saveNow();
                                            }
                                        }),
                                choice("背景采集分辨率", "每帧从游戏画面拷下来那张底按多大比例采。"
                                        + "越小越省，但放大回面板本身就带糊——低配机往小了调，"
                                        + "比把下面的模糊拉低更划算。",
                                        new Supplier<String>() {
                                            public String get() {
                                                return glassScaleName(HudConfig.glassBackdropScale);
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                setGlassScale(glassScaleName(HudConfig.glassBackdropScale == 1
                                                        ? 2 : (HudConfig.glassBackdropScale == 2 ? 4 : 1)));
                                            }
                                        }, GLASS_SCALE_CHOICES, new Consumer<String>() {
                                            public void accept(String value) {
                                                setGlassScale(value);
                                            }
                                        }),
                                slider(HudSetting.GLASS_BLUR, "折射背景的柔化强度，默认 0.75；0 为清晰透镜，不是关闭折射。"),
                                slider(HudSetting.GLASS_TINT, "玻璃自身颜色多浓。往 0 拖是干净透明，"
                                        + "往 1 拖背景基本看不见，字最好读。"),
                                slider(HudSetting.GLASS_EDGE_GLOW, "透镜边缘的方向性亮暗强度；推到 0 仍保留真实折射，不叠加白框。"),
                                slider(HudSetting.GLASS_BLUR_PLAYER, "播放器面板这一面的模糊倍率，乘在上面"
                                        + "「玻璃模糊」之上。1 就是与全局同档；这一面通常是最大的那块玻璃，"
                                        + "所以拿它当基准。只在这一面开着时生效。",
                                        () -> HudConfig.glassSurfacePlayer),
                                slider(HudSetting.GLASS_BLUR_ISLAND, "灵动岛的模糊倍率。岛只有几十像素高，"
                                        + "糊到与面板同档会把轮廓一起吃掉（边缘光与折射都读不出来），"
                                        + "所以默认比面板轻一档。只在这一面开着时生效。",
                                        () -> HudConfig.glassSurfaceIsland),
                                slider(HudSetting.GLASS_BLUR_LYRIC, "桌面歌词卡片的模糊倍率。卡片背后通常是"
                                        + "亮天空与树叶，糊得重一点字才立得住。只在这一面开着时生效。",
                                        () -> HudConfig.glassSurfaceDesktopLyric),
                                slider(HudSetting.GLASS_BLUR_EDITORS, "HUD 编辑器面板的模糊倍率。"
                                        + "编辑器里要看的是控件本身，往低了调会更清楚。只在这一面开着时生效。",
                                        () -> HudConfig.glassSurfaceEditors))),

                new Category("信息栏与封面", "桌面信息栏的显隐与摆放，以及封面与全屏页标签", 0xFFFFA971, HudSettingsIcons.ALBUM,
                        new Group("显示与摆放", HudSettingsIcons.VISIBILITY,
                                toggle("歌曲信息栏", "桌面左上角那块封面 + 歌名的卡片。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return infoEnabled();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                setInfoEnabled(!infoEnabled());
                                            }
                                        }),
                                toggle("桌面歌词", "屏幕下方的逐字歌词。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return lyricsEnabled();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                setLyricsEnabled(!lyricsEnabled());
                                            }
                                        }),
                                action("恢复默认位置与缩放",
                                        "把信息栏放回左上、歌词放回底部中间，缩放都回到 100%。在界面外的空白处直接拖动也可以调整位置，滚轮调缩放。",
                                        new Runnable() {
                                            public void run() {
                                                resetHudPlacement();
                                            }
                                        })),
                        new Group("封面与标签", HudSettingsIcons.ALBUM,
                                toggle(new Supplier<String>() {
                                    public String get() {
                                        return animatedCoverLabel();
                                    }
                                }, "打开后桌面歌曲信息、全屏歌词页与播放条使用 ffmpeg 抽帧的动态封面；关掉或没有 ffmpeg 时一律用静态封面。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.animatedCoverEnabled;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.animatedCoverEnabled = !HudConfig.animatedCoverEnabled;
                                                syncAnimatedCoverModuleValue();
                                                saveNow();
                                            }
                                        }),
                                choice("标签语言",
                                        "封面下方那排标签（音源 / 音质 / 歌词类型）用英文还是中文。点击切换。"
                                        + "英文在 10 像素高的胶囊里更清楚，中文更直观。",
                                        new Supplier<String>() {
                                            public String get() {
                                                return NCMPlayerConfig.getNowPlayingTagLanguageLabel();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                NCMPlayerConfig.toggleNowPlayingTagsInEnglish();
                                            }
                                        }, tagLanguageChoices(), HudSettingsModel::setTagLanguage,
                                        new String[] {"更直观。", "胶囊里更清楚。"}))),

                new Category("缓存与公告", "已下载音频的占用上限，以及远端公告", 0xFFD8A657, HudSettingsIcons.STORAGE,
                        new Group("音频缓存", HudSettingsIcons.SWEEP,
                                choice("缓存上限",
                                        "音频缓存目录（config/muonium/cache/music）最多占多少磁盘。超出后从最久没听的歌开始整首删除，正在播放与预备播放的歌不会被删。",
                                        new Supplier<String>() {
                                            public String get() {
                                                return NCMPlayerConfig.getMusicCacheLimitLabel();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                NCMPlayerConfig.cycleMusicCacheLimit();
                                                MusicAudioCache.enforceLimitAsync();
                                            }
                                        }, cacheLimitChoices(), HudSettingsModel::setCacheLimit),
                                action(new Supplier<String>() {
                                    public String get() {
                                        return cacheSweepLabel();
                                    }
                                }, "现在就按上限清一次，同时删掉中断的下载与转码残片。上限为「不限制」时只清残片。",
                                        new Runnable() {
                                            public void run() {
                                                sweepAudioCache();
                                            }
                                        })),
                        new Group("公告中心", HudSettingsIcons.INFO,
                                action(AnnouncementChecker::unreadLabel,
                                        "没读过的公告会在主菜单弹一次，看过之后就只能从这个入口进去回看。"
                                                + "公告与更新检查读同一份远端清单，最多 30 分钟问一次。",
                                        HudSettingsModel::openAnnouncements)))
        };
    }

    /**
     * 打开公告中心。
     *
     * <p>三套设置界面（Classic / Pro / Folia）与 OneConfig 都读同一份模型，所以这一行只在模型里
     * 写一次就三处都有。公告窗是一个独立 Screen 而不是浮层：设置界面自己就是 {@code Screen}，
     * 关掉时按 {@code parent} 回到打开它的那一屏。</p>
     */
    private static void openAnnouncements() {
        GuiAnnouncements.open(net.minecraft.client.Minecraft.getInstance().screen);
    }

    /** 「立即清理」那一行的标签，顺带把当前占用显示出来。 */
    public static String cacheSweepLabel() {
        try {
            return "立即清理 · 当前 " + MusicAudioCache.formatBytes(MusicAudioCache.cachedTotalBytes());
        } catch (Throwable ignored) {
            return "立即清理";
        }
    }

    /** 手动清理走后台线程：扫目录加删文件不能压在渲染线程上。结果用灵动岛回报。 */
    public static void sweepAudioCache() {
        MultiThreadingUtil.runAsync(new Runnable() {
            public void run() {
                try {
                    MusicAudioCache.Sweep result = MusicAudioCache.enforceLimit();
                    String detail = result.freedBytes <= 0L
                            ? "没有可回收的缓存 · 占用 " + MusicAudioCache.formatBytes(result.remainingBytes)
                            : "回收 " + MusicAudioCache.formatBytes(result.freedBytes)
                                    + " · 剩余 " + MusicAudioCache.formatBytes(result.remainingBytes);
                    DownloadDynamicIsland.showCacheSweep(detail, true);
                } catch (Throwable failure) {
                    DownloadDynamicIsland.showCacheSweep("无法访问缓存目录", false);
                }
            }
        });
    }

    private static String[] blossomStyleChoices() {
        return new String[] {"经典", "Blossom"};
    }

    private static void setBlossomStyle(String value) {
        int style = "Blossom".equalsIgnoreCase(value) ? 1 : 0;
        if (HudConfig.osdLyricStyle == style) return;
        HudConfig.osdLyricStyle = style;
        saveNow();
    }

    /** 背景采集倍率的三档显示名，顺序就是 {@code 1 / 2 / 4}。 */
    private static final String[] GLASS_SCALE_CHOICES = {"原始", "一半", "四分之一"};

    private static String glassScaleName(int scale) {
        if (scale <= 1) return GLASS_SCALE_CHOICES[0];
        return scale <= 2 ? GLASS_SCALE_CHOICES[1] : GLASS_SCALE_CHOICES[2];
    }

    /** 按显示名写回倍率；认不出的名字退回 2（出厂默认），不抛。 */
    private static void setGlassScale(String value) {
        int scale = GLASS_SCALE_CHOICES[0].equals(value) ? 1
                : (GLASS_SCALE_CHOICES[1].equals(value) ? 2
                : (GLASS_SCALE_CHOICES[2].equals(value) ? 4 : 2));
        if (HudConfig.glassBackdropScale == scale) return;
        HudConfig.glassBackdropScale = scale;
        saveNow();
    }

    private static String[] islandStyleChoices() {
        DownloadDynamicIsland.DynamicIslandStyle[] styles = DownloadDynamicIsland.DynamicIslandStyle.values();
        // 手机档剔除「液态玻璃」款：真玻璃在安卓上整体禁用，这个名字留在下拉里只会误导
        // （判据与渲染兜底见 DownloadDynamicIsland.isStyleSelectable / getStyle）。
        java.util.ArrayList<String> names = new java.util.ArrayList<String>(styles.length);
        for (int i = 0; i < styles.length; i++) {
            if (DownloadDynamicIsland.isStyleSelectable(styles[i])) {
                names.add(styles[i].getDisplayName());
            }
        }
        return names.toArray(new String[0]);
    }

    private static void setIslandStyle(String value) {
        for (DownloadDynamicIsland.DynamicIslandStyle style : DownloadDynamicIsland.DynamicIslandStyle.values()) {
            if (style.getDisplayName().equals(value)) {
                // 手机档：直设「液态玻璃」款一律拒绝（下拉里已不出现，这里挡 OneConfig 一类直写口）。
                if (!DownloadDynamicIsland.isStyleSelectable(style)) return;
                if (HudConfig.dynamicIslandStyle == style.ordinal()) return;
                HudConfig.dynamicIslandStyle = style.ordinal();
                saveNow();
                return;
            }
        }
    }

    private static void installModel(FoliaModelManager.Kind kind) {
        FoliaModelManager.downloadAsync(kind, (ok, detail) ->
                DownloadDynamicIsland.showModelOperationResult(ok ? "模型安装完成" : "模型安装失败", detail, ok));
    }

    private static void scanModels() {
        MultiThreadingUtil.runAsync(() -> {
            String detail = FoliaModelManager.scanKnownFolders();
            boolean found = !detail.startsWith("没有找到");
            DownloadDynamicIsland.showModelOperationResult(found ? "模型扫描完成" : "模型扫描未找到", detail, found);
        });
    }

    private static void deleteModels() {
        MultiThreadingUtil.runAsync(() -> {
            FoliaModelManager.deleteAll();
            DownloadDynamicIsland.showModelOperationResult("模型已删除", "Folia 模型与运行组件已删除", true);
        });
    }


    private static String[] overlapChoices() {
        return new String[] {"1.5 秒", "2 秒", "3 秒", "4 秒", "6 秒", "8 秒", "10 秒"};
    }

    private static void setOverlapChoice(String value) {
        if (value == null) return;
        String normalized = value.replace("秒", "").trim();
        try {
            AutomixSettings.setOverlapSeconds(Float.parseFloat(normalized));
        } catch (RuntimeException ignored) {
        }
    }

    private static String[] tagLanguageChoices() {
        return new String[] {"中文", "English"};
    }

    private static void setTagLanguage(String value) {
        NCMPlayerConfig.setNowPlayingTagsInEnglish("English".equalsIgnoreCase(value));
    }

    private static String[] cacheLimitChoices() {
        int[] options = NCMPlayerConfig.MUSIC_CACHE_LIMIT_OPTIONS;
        String[] labels = new String[options.length];
        for (int i = 0; i < options.length; i++) {
            labels[i] = options[i] <= 0 ? "不限制" : options[i] + " GB";
        }
        return labels;
    }

    private static void setCacheLimit(String value) {
        if (value == null) return;
        if (value.startsWith("不限制")) {
            NCMPlayerConfig.setMusicCacheLimitGigabytes(0);
            return;
        }
        try {
            NCMPlayerConfig.setMusicCacheLimitGigabytes(
                    Integer.parseInt(value.replace("GB", "").trim()));
        } catch (RuntimeException ignored) {
        }
    }

    // ==================== HUD 设置界面样式 ====================

    // ---------- 3D 歌词动效风格（四档单选，取代以前的三个独立开关） ----------

    private static final String[] WORLD_EFFECTS = { "无", "逐词弹出", "心象", "绘光" };

    /** 当前动效档位的中文名。绘光优先于心象（渲染端的既定语义），老的「两个都开」读到绘光。 */
    static String worldEffectName() {
        if (HudConfig.worldLyricsHuiguang) return WORLD_EFFECTS[3];
        if (HudConfig.worldLyricsCadenza) return WORLD_EFFECTS[2];
        if (HudConfig.worldLyricsWordPop) return WORLD_EFFECTS[1];
        return WORLD_EFFECTS[0];
    }

    private static void cycleWorldEffect() {
        for (int i = 0; i < WORLD_EFFECTS.length; i++) {
            if (WORLD_EFFECTS[i].equals(worldEffectName())) {
                setWorldEffect(WORLD_EFFECTS[(i + 1) % WORLD_EFFECTS.length]);
                return;
            }
        }
        setWorldEffect(WORLD_EFFECTS[0]);
    }

    /** 单选写入：先清三个布尔再点亮选中的那档 —— 互斥由这里保证，不再依赖用户自己记得「别同时开」。 */
    static void setWorldEffect(String value) {
        HudConfig.worldLyricsWordPop = WORLD_EFFECTS[1].equals(value);
        HudConfig.worldLyricsCadenza = WORLD_EFFECTS[2].equals(value);
        HudConfig.worldLyricsHuiguang = WORLD_EFFECTS[3].equals(value);
        saveNow();
    }

    // ---------- 灵动岛动画速度档位（弹出/收起/内容切换三条共用） ----------

    /** 三档预设对应的共用倍率；自定义 = 三条各是各的（或都改过但不成档）。 */
    private static final float[] ISLAND_SPEED_PRESETS = { 0.7f, 1.0f, 1.4f };
    private static final String[] ISLAND_SPEED_NAMES = { "慢", "标准", "快", "自定义" };

    static boolean islandSpeedCustom() {
        return islandSpeedPresetIndex() < 0;
    }

    private static int islandSpeedPresetIndex() {
        float expand = HudConfig.dynamicIslandExpandSpeed;
        float collapse = HudConfig.dynamicIslandCollapseSpeed;
        float content = HudConfig.dynamicIslandContentSpeed;
        for (int i = 0; i < ISLAND_SPEED_PRESETS.length; i++) {
            float preset = ISLAND_SPEED_PRESETS[i];
            if (Math.abs(expand - preset) < 0.02f
                    && Math.abs(collapse - preset) < 0.02f
                    && Math.abs(content - preset) < 0.02f) {
                return i;
            }
        }
        return -1;
    }

    static String islandSpeedName() {
        int preset = islandSpeedPresetIndex();
        return preset >= 0 ? ISLAND_SPEED_NAMES[preset] : ISLAND_SPEED_NAMES[3];
    }

    private static void cycleIslandSpeed() {
        int preset = islandSpeedPresetIndex();
        int next = preset >= 0 ? (preset + 1) % 3 : 0;
        setIslandSpeed(ISLAND_SPEED_NAMES[next]);
    }

    /** 选预设就把三条一起写；选「自定义」不动数值（用户接着自己调），只负责让三条滑条现出来。 */
    static void setIslandSpeed(String value) {
        for (int i = 0; i < ISLAND_SPEED_PRESETS.length; i++) {
            if (ISLAND_SPEED_NAMES[i].equals(value)) {
                HudConfig.dynamicIslandExpandSpeed = ISLAND_SPEED_PRESETS[i];
                HudConfig.dynamicIslandCollapseSpeed = ISLAND_SPEED_PRESETS[i];
                HudConfig.dynamicIslandContentSpeed = ISLAND_SPEED_PRESETS[i];
                saveNow();
                return;
            }
        }
        saveNow();
    }

    /**
     * 播放态来源的当前取值。
     *
     * <p>选了镜像时把取数状态一并写在值上，理由与 {@link #uiLayerLabel()} 一样：
     * 「开了却没效果」是最难自查的坏状态，而原因有三种完全不同的处理办法 ——
     * 系统里没有正在播放的媒体、播放器压根不注册系统媒体控制、缺编译器。</p>
     */
    public static String playbackFeedLabel() {
        if (!HudConfig.HUD_PLAYBACK_FEED_MIRROR.equals(HudConfig.playbackFeed)) return "内置播放器";
        if (!ExternalPlayback.supportsPlatform()) return "系统镜像（仅 Windows）";
        return "系统镜像 · " + ExternalPlayback.statusLabel();
    }

    /** 内置 ↔ 系统镜像。两档循环，没有第三种取值。 */
    private static void cyclePlaybackFeed() {
        setPlaybackFeedChoice(HudConfig.HUD_PLAYBACK_FEED_MIRROR.equals(HudConfig.playbackFeed)
                ? HudConfig.HUD_PLAYBACK_FEED_INTERNAL : HudConfig.HUD_PLAYBACK_FEED_MIRROR);
    }

    /**
     * 直写播放态来源，并立刻让镜像链路跟上。
     *
     * <p>认中文标签也认存储值：外部 UI（OneConfig / ModernUI 的下拉）递回来的是标签文案，
     * 而 {@code cyclePlaybackFeed()} 递回来的是常量。两种都要能用，否则外部那两层
     * 界面上选了没反应。</p>
     */
    private static void setPlaybackFeedChoice(String value) {
        if (value == null) return;
        boolean mirror = HudConfig.HUD_PLAYBACK_FEED_MIRROR.equalsIgnoreCase(value)
                || value.contains("镜像");
        HudConfig.playbackFeed = mirror
                ? HudConfig.HUD_PLAYBACK_FEED_MIRROR : HudConfig.HUD_PLAYBACK_FEED_INTERNAL;
        saveNow();
        ExternalPlayback.syncFromSettings();
        try {
            DownloadDynamicIsland.showExternalMedia(mirror
                    ? "已交给系统里正在播的外部播放器" : "已切回内置播放器", true);
        } catch (Throwable ignored) {
        }
    }

    /** 设置页那一行的动作：丢掉缓存的失败结论，重新探一遍系统媒体助手。 */
    private static void probeExternalMedia() {
        ExternalPlayback.retryProbe();
        try {
            DownloadDynamicIsland.showExternalMedia("正在重新探测系统媒体助手", true);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 当前由哪一层渲染 HUD 设置界面。选中的外部层不可用时把原因跟着写在值上：
     * 玩家看到「按了 H 界面没换」时，得能自己分清是「没装」还是「装了但这棵版本树没有对应构件」，
     * 这两种情况的处理办法完全不同。
     */
    public static String uiLayerLabel() {
        if (HudConfig.HUD_EDITOR_UI_ONECONFIG.equals(HudConfig.hudEditorUi)) {
            return OneConfigHudBridge.isAvailable() ? "OneConfig" : "OneConfig（未安装·回退内置）";
        }
        if (HudConfig.HUD_EDITOR_UI_MODERNUI.equals(HudConfig.hudEditorUi)) {
            String status = ModernUiHudBridge.statusLabel();
            return "可用".equals(status) ? "ModernUI" : "ModernUI（" + status + "·回退内置）";
        }
        return "内置";
    }

    /** 内置 → OneConfig → ModernUI 循环。不可用的那层照旧能选中，只是打开时回退并写明原因。 */
    private static void cycleUiLayer() {
        String current = HudConfig.hudEditorUi;
        if (HudConfig.HUD_EDITOR_UI_BUILTIN.equals(current)) {
            HudConfig.hudEditorUi = HudConfig.HUD_EDITOR_UI_ONECONFIG;
        } else if (HudConfig.HUD_EDITOR_UI_ONECONFIG.equals(current)) {
            HudConfig.hudEditorUi = HudConfig.HUD_EDITOR_UI_MODERNUI;
        } else {
            HudConfig.hudEditorUi = HudConfig.HUD_EDITOR_UI_BUILTIN;
        }
        saveNow();
    }

    private static void setUiLayerChoice(String value) {
        if (value == null) return;
        if ("OneConfig".equalsIgnoreCase(value)) {
            HudConfig.hudEditorUi = HudConfig.HUD_EDITOR_UI_ONECONFIG;
        } else if ("ModernUI".equalsIgnoreCase(value)) {
            HudConfig.hudEditorUi = HudConfig.HUD_EDITOR_UI_MODERNUI;
        } else {
            HudConfig.hudEditorUi = HudConfig.HUD_EDITOR_UI_BUILTIN;
        }
        saveNow();
    }

    private static String[] themeChoices() {
        NCMTheme.ThemePreset[] presets = NCMTheme.ThemePreset.values();
        // 手机档跳过真玻璃主题（液态玻璃/琉璃）：下拉里根本不出现，选不到。
        java.util.ArrayList<String> names = new java.util.ArrayList<String>(presets.length);
        for (int i = 0; i < presets.length; i++) {
            if (NCMTheme.isSelectable(presets[i])) names.add(presets[i].getDisplayName());
        }
        return names.toArray(new String[0]);
    }

    private static void setThemeChoice(String value) {
        for (NCMTheme.ThemePreset preset : NCMTheme.ThemePreset.values()) {
            if (preset.getDisplayName().equals(value)) {
                if (!NCMTheme.isSelectable(preset)) return;
                NCMTheme.setCurrent(preset);
                applyTheme(preset);
                return;
            }
        }
    }

    private static void applyTheme(NCMTheme.ThemePreset preset) {
        DownloadDynamicIsland.showTheme(preset == null ? NCMTheme.getCurrentName() : preset.getDisplayName());
    }

    private static boolean infoEnabled() {
        try {
            return MuoniumPlayerExtension.getInstance().musicInfo.isEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void setInfoEnabled(boolean enabled) {
        try {
            MuoniumPlayerExtension.getInstance().musicInfo.setEnabled(enabled);
            HudConfig.musicInfoEnabled = enabled;
            saveNow();
        } catch (Throwable ignored) {
        }
    }

    private static boolean lyricsEnabled() {
        try {
            return MuoniumPlayerExtension.getInstance().musicLyrics.isEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void setLyricsEnabled(boolean enabled) {
        try {
            MuoniumPlayerExtension.getInstance().musicLyrics.setEnabled(enabled);
            HudConfig.musicLyricsEnabled = enabled;
            saveNow();
        } catch (Throwable ignored) {
        }
    }

    // ==================== 数值格式化 ====================

    /**
     * 滑块右侧显示的数值文本。
     *
     * <p>放在模型里而不是各界面里：两个界面显示同一个值时必须写法一致，否则同一项在新旧界面
     * 会读出不同的单位。</p>
     */
    public static String formatValue(HudSetting setting, float value) {
        switch (setting) {
            case CURRENT_LINE_SCALE:
            case NORMAL_SCALE:
            case DYNAMIC_ISLAND_SCALE:
            case DYNAMIC_ISLAND_TEXT_SCALE:
            case CURRENT_WORD_SCALE:
            case CURRENT_GLOW:
            case FULLSCREEN_GLOW:
            case OSD_PULSE:
            case OSD_SMOOTHNESS:
            case OSD_DUET_SPREAD:
            case NORMAL_OPACITY:
            case NORMAL_GLOW:
            case SCROLL_SMOOTHNESS:
            case SECONDARY_OPACITY:
            case BLOSSOM_BLUR:
            case BLOSSOM_OPACITY:
            case BLOSSOM_BORDER:
                return Math.round(value * 100.0f) + "%";
            case DYNAMIC_ISLAND_MAX_WIDTH:
            case DYNAMIC_ISLAND_PROGRESS_HEIGHT:
                return String.format("%.1f px", value);
            case DYNAMIC_ISLAND_COMPLETION_HOLD:
            case DYNAMIC_ISLAND_QUEUE_INTERVAL:
            case DYNAMIC_ISLAND_AMBIENT_STATUS:
            case DYNAMIC_ISLAND_AMBIENT_NOW_PLAYING:
                return String.format("%.1f s", value);
            case DYNAMIC_ISLAND_ENTRANCE_DURATION:
                return Math.round(value) + " ms";
            case PLAYER_FADE_SPEED:
            case DYNAMIC_ISLAND_EXPAND_SPEED:
            case DYNAMIC_ISLAND_COLLAPSE_SPEED:
            case DYNAMIC_ISLAND_CONTENT_SPEED:
            case DYNAMIC_ISLAND_SPINNER_SPEED:
            case DYNAMIC_ISLAND_OVERSHOOT:
            case LYRIC_SEARCH_UI_SCALE:
                case LYRIC_GLOW_SPREAD:
                return String.format("%.2f×", value);
            case OSD_TRANSITION:
            case NORMAL_SPACING:
            case EDGE_FADE:
                return Math.round(value) + " px";
            case BLOSSOM_LINES:
                return Math.round(value) + " 行";
            case SPECTRUM_BAR_COUNT_CUSTOM:
                return Math.round(value) + " 根";
            default:
                return String.format("%.2f", value);
        }
    }

    /** 声明里出现过的全部 {@link HudSetting}。探针用它断言没有滑块被漏掉。 */
    public static List<HudSetting> declaredSettings() {
        List<HudSetting> found = new ArrayList<HudSetting>();
        for (Category category : categories()) {
            for (Group group : category.groups) {
                for (Entry entry : group.entries) {
                    if (entry.setting != null) found.add(entry.setting);
                }
            }
        }
        return found;
    }
}
