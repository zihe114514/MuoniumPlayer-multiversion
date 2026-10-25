package com.muoniumplayer.core.screens.ncm;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.core.input.TouchGestures;
import com.muoniumplayer.core.settings.TouchSettings;

import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.LyricManualMemory;
import com.muoniumplayer.core.ncm.music.LyricSourceMemory;
import com.muoniumplayer.core.rendering.animation.Easing;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.screens.ncm.foliabg.FoliaBackgroundConfig;
import com.muoniumplayer.core.screens.ncm.foliabg.FoliaBackgroundOptions;
import com.muoniumplayer.core.settings.HudSetting;
import com.muoniumplayer.core.utils.cursor.CursorUtils;

/**
 * 全屏歌词页左上角的「A-Z」快捷面板：歌词样式、逐字特效、以及「律动词云」的背景配色。
 *
 * <p>为什么单独做一个入口：这些项只影响全屏歌词页，边调边看才调得准。内置 HUD 编辑器里也有同样的
 * 滑块（{@link HudSetting#FULLSCREEN_GLOW} / {@link HudSetting#CURRENT_WORD_SCALE}），但那是另一个
 * 界面，看不到全屏歌词的实际效果。</p>
 *
 * <p>实时预览不需要任何额外机制：渲染器每帧直接读 {@link HudConfig} 的静态字段，拖动滑块的同一帧
 * 就会生效。落盘只在松开鼠标（或点一下开关）时发生一次，避免拖动过程中反复写文件。</p>
 *
 * <h3>行布局是一张表，不是一串偏移量</h3>
 * <p>面板的行数会随「当前是哪种歌词样式」「配色是不是自定义」变化，早先那种「每个 rowY 各自用常量
 * 累加算」的写法每加一行都要同步改 {@code panelHeight()} 和后面所有行的偏移，漏一处就是「看得见
 * 但点不到」。现在改成 {@link #layout()} 每帧顺序摆一次行、把位置与高度记进 {@link #rows}，
 * 渲染与命中<b>共用同一张表</b>，加行只需要在 {@code layout()} 里插一句。</p>
 *
 * <h3>装不下就分列，不是把整块缩小</h3>
 * <p>行数一多（QPlayer 流体背景参数、加上自定义配色，仍可能超过视口高度），
 * 一列排下来必然高过视口。此前的唯一手段是 {@link #updateUiScale()}：把面板连同文字一起等比
 * 缩小，缩到 {@link #MIN_UI_SCALE} 就停手——可 0.62 只是「比原来好看一点」，一列八百多像素
 * 对上半屏的可视高度，缩到底也塞不进去，结果是<b>字小得看不清 + 底部还要滚</b>。</p>
 * <p>现在先分列：列数由 {@link LyricsPanelGeometry#packQuickPanel} 按「缩放需求量最小」选出来，
 * 一列装得下就还是原来那一列、一个字都不缩；装不下就两列、三列，面板变宽变矮而字号保持 1.0。
 * 加宽有硬上限——不许越过 {@link LyricsPanelGeometry#lyricColumnLeft}，那是歌词正文的左边界，
 * 面板宁可继续缩也绝不压到歌词上。缩放只是最后兜底（窗口小到列数顶满还放不下时）。</p>
 */
final class LyricStyleQuickPanel implements SharedRenderingConstants, SharedConstants {

    /** 面板里的通用浮点滑块，顺序即显示顺序。 */
    private static final HudSetting[] SETTINGS = {
            HudSetting.FULLSCREEN_GLOW,
            HudSetting.CURRENT_WORD_SCALE
    };

    /**
     * 「背景」：六档循环（QPlayer 流体 / 模糊封面 / 四种 Folia 样式）。
     *
     * <p>一行同时承担「切哪一套」与「这一套用哪种」，下面紧接着展开当前档位的参数 ——
     * 用户点一下就换画面、同时换配置项。</p>
     */
    private static final int KIND_BACKGROUND_KIND = 25;
    /** 当前档位下 Folia 的参数行，内容由 {@link #foliaSpecs()} 决定。 */
    private static final int KIND_FOLIA_SPEC = 26;

    private static final int KIND_STYLE = 0;
    private static final int KIND_SLIDER = 1;
    private static final int KIND_SECTION = 2;
    private static final int KIND_DECOR_MODE = 3;
    private static final int KIND_BEATS = 4;
    private static final int KIND_COLOR_COUNT = 5;
    private static final int KIND_SWATCHES = 6;
    private static final int KIND_HSB = 7;
    /** 当前样式专属的浮点滑块。 */
    private static final int KIND_STYLE_SLIDER = 8;
    /** 当前样式专属的开关 / 枚举，点一下换下一档。 */
    private static final int KIND_STYLE_TOGGLE = 9;
    /** 「未到行模糊」全局开关：未唱到的行渐进高斯模糊，正在唱的行保持清晰（qplayer edgeBlur）。 */
    private static final int KIND_EDGE_BLUR = 22;
    /** 背景层叠的层开关：值是位掩码里的位索引（bit0 = Latent 抖动层，bit1 = Sora 星空层）。 */
    private static final int KIND_LAYER_TOGGLE = 23;
    /** 背景层叠的层不透明度滑杆：值同样是位索引。 */
    private static final int KIND_LAYER_OPACITY = 24;
    /** QPlayer fluid animation mode. */
    private static final int KIND_BACKGROUND = 10;
    /** 播放器颜色来源：当前预设或当前歌曲封面。 */
    private static final int KIND_THEME_SOURCE = 11;
    /** 手动重取歌词（左键重新走一遍解析链）。 */
    private static final int KIND_LYRIC_RELOAD = 12;
    /** 歌词时间偏移滑块；左右各 ±10 秒，右键归零。 */
    private static final int KIND_LYRIC_OFFSET = 13;
    /** QPlayer fluid setting slider. */
    private static final int KIND_FLUID_SLIDER = 17;
    /** QPlayer 三种流体算法选择。 */
    private static final int KIND_FLUID_STYLE = 20;
    /**
     * 歌词来源：自动，或指定某一个源。
     *
     * <p>与 {@link #KIND_LYRIC_RELOAD} 是两件事，故意分成两行。自动匹配错了的时候，「重新匹配」
     * 是没用的——它把同一条确定性链路原样再跑一遍，输入没变、结果必然一样。能脱困的只有换源，
     * 所以「换哪个库」必须有自己的一行，不能让用户在一次「重取」里猜为什么毫无变化。</p>
     */
    private static final int KIND_LYRIC_SOURCE = 18;

    /**
     * 「手动选词」：打开歌词搜索弹窗，给正在放的这首歌挑一份人确认过的歌词。
     *
     * <p>排在「歌词来源」之后、「重新匹配」之前，是按出问题的先后放的：先看这份词是哪个源给的
     * （来源行），不对就自己挑一条（这一行），最后才轮到「重新匹配」——那一条只是把同一条确定性
     * 链路原样再跑一遍，挑错了词的时候它连结果都不会变。</p>
     *
     * <p>右键是「取消钉住」。钉住之后「歌词来源」那一行就不再决定屏幕上出现哪份词了，所以退路
     * 必须写在这一行上，否则用户只能永远停在自己上一次的选择里。</p>
     */
    private static final int KIND_LYRIC_SEARCH = 19;

    /** 偏移滑块的两端（毫秒）。与 {@code LyricOffsetMemory} 的夹取范围保持一致。 */
    private static final long OFFSET_SPAN_MILLIS = 10_000L;

    // ---- 各样式专属参数表 ----
    // 做成 static final 而不是每次现算：layout() 每帧都会跑一遍，没必要每帧 new 一堆小数组。

    private static final HudSetting[] NO_SETTINGS = new HudSetting[0];
    private static final int[] NO_TOGGLES = new int[0];

    // 「歌词字号」放在每套样式参数的第一条：它对这几套样式都有效，但对「逐行滚动」「律动词云」无效
    // （那两套有自己的字号机制），所以不能塞进上面那个通用的 SETTINGS 里 —— 否则会出现「调了没反应」。
    private static final HudSetting[] LUMINOUS_SETTINGS = {
            HudSetting.LYRIC_FONT_SCALE,
            HudSetting.LUMINOUS_WORD_SPACING, HudSetting.LUMINOUS_FLOAT
    };
    private static final HudSetting[] PARTITA_SETTINGS = {
            HudSetting.LYRIC_FONT_SCALE,
            HudSetting.PARTITA_STAGGER_MIN, HudSetting.PARTITA_STAGGER_MAX
    };
    private static final HudSetting[] FUME_SETTINGS = {
            HudSetting.LYRIC_FONT_SCALE,
            HudSetting.FUME_TEXT_HOLD, HudSetting.FUME_CAMERA_SPEED, HudSetting.FUME_HERO_SCALE
    };
    private static final HudSetting[] CAPPELLA_SETTINGS = {
            HudSetting.LYRIC_FONT_SCALE,
            HudSetting.CAPPELLA_BUBBLE_WIDTH, HudSetting.CAPPELLA_EMOJI_CHANCE
    };
    private static final HudSetting[] TILT_SETTINGS = {
            HudSetting.LYRIC_FONT_SCALE,
            HudSetting.TILT_SPLIT, HudSetting.TILT_STYLE
    };
    private static final HudSetting[] DIORAMA_SETTINGS = {
            HudSetting.LYRIC_FONT_SCALE,
            HudSetting.DIORAMA_DEPTH
    };
    private static final HudSetting[] PENDOLO_SETTINGS = {
            HudSetting.LYRIC_FONT_SCALE,
            HudSetting.PENDOLO_ARC_RADIUS, HudSetting.PENDOLO_ARC_ANGLE,
            HudSetting.PENDOLO_ACTIVE_SCALE
    };

    private static final int TOGGLE_LUMINOUS_ROTATION = 1;
    private static final int TOGGLE_PARTITA_GUIDES = 2;
    private static final int TOGGLE_FUME_BACKGROUND = 3;
    private static final int TOGGLE_CAPPELLA_AVATAR = 4;
    private static final int TOGGLE_TILT_COLORS = 5;
    private static final int TOGGLE_DIORAMA_DUST = 6;
    private static final int TOGGLE_PENDOLO_GEARS = 7;
    private static final int TOGGLE_CAPPELLA_EMOJI = 8;

    private static final int[] LUMINOUS_TOGGLES = {TOGGLE_LUMINOUS_ROTATION};
    private static final int[] PARTITA_TOGGLES = {TOGGLE_PARTITA_GUIDES};
    private static final int[] FUME_TOGGLES = {TOGGLE_FUME_BACKGROUND};
    private static final int[] CAPPELLA_TOGGLES = {TOGGLE_CAPPELLA_AVATAR, TOGGLE_CAPPELLA_EMOJI};
    private static final int[] TILT_TOGGLES = {TOGGLE_TILT_COLORS};
    private static final int[] DIORAMA_TOGGLES = {TOGGLE_DIORAMA_DUST};
    private static final int[] PENDOLO_TOGGLES = {TOGGLE_PENDOLO_GEARS};

    // ---- QPlayer 全屏歌词流体参数 ----
    private static final int FLUID_SATURATION_SLIDER = 0;
    private static final int FLUID_AUDIO_SLIDER = 1;

    /**
     * 流体算法的底层档位：显示名 + 渲染层风格索引两列并行。渲染层的
     * SkijaFluidBackground 里 8/9 是叠加层内部代号，不进这个轮换表，
     * 所以这里用显式映射而不是直接拿下标当风格值——改渲染层常量时
     * 只动 STYLE_INDEXES 这一列。
     *
     * <p><b>只剩 QPlayer 自己的三支</b>：原先混在这里的「隐现 / 星空 / 像素 / 无 / 隐现网格 /
     * 像素抖动 / 半调网点」是 folia 背景的旧移植，已经从这一组撤掉 —— Folia 的背景现在走
     * 本面板「背景」那一行（{@code FoliaBackgroundOptions}），存档在
     * {@code folia_lyrics_background.json}，与 QPlayer 的 {@code fullscreenLyricFluidStyle}
     * 没有任何关系。旧配置里指向那几档的数值在渲染层统一折回 0。</p>
     */
    private static final String[] FLUID_STYLE_NAMES = {
            "Pixi 四层", "AMLL 网格", "Classic warp"
    };
    /** 上表每档对应的 SkijaFluidBackground 风格常量值（顺序严格一致）。 */
    private static final int[] FLUID_STYLE_INDEXES = {
            0, 1, 2
    };

    /**
     * 渲染层风格值 → 轮换表下标。找不到（理论上只有叠加层内部代号 8/9 会落到
     * 这里，而它们不该被存进配置）时退回第一档，轮换从那里继续。
     */
    private static int displayIndexForStyle(int style) {
        for (int i = 0; i < FLUID_STYLE_INDEXES.length; i++) {
            if (FLUID_STYLE_INDEXES[i] == style) return i;
        }
        return 0;
    }

    /** 叠加层的两个位索引：bit0 = Latent 抖动层，bit1 = Sora 星空层。 */
    private static final int LAYER_BIT_LATENT = 0;
    private static final int LAYER_BIT_SORA = 1;

    /** 叠加层不透明度滑杆的下标（进 {@link #LAYER_OPACITY_*} 三张表）。 */
    private static final int LAYER_OPACITY_LATENT = 0;
    private static final int LAYER_OPACITY_SORA = 1;
    private static final String[] LAYER_OPACITY_LABELS = {"抖动层", "星空层"};
    private static final float[] LAYER_OPACITY_MIN = {0f, 0f};
    private static final float[] LAYER_OPACITY_MAX = {100f, 100f};
    private static final String[] FLUID_SLIDER_LABELS = {"流体饱和度", "律动强度"};
    private static final float[] FLUID_SLIDER_MIN = {0f, 0f};
    private static final float[] FLUID_SLIDER_MAX = {200f, 200f};
    private static final String[] TILT_COLOR_NAMES = {"默认", "互换", "全强调", "全主色"};

    /** 第 0 档是「左侧歌曲封面 + 右侧登录账号头像」，未登录时右侧自动退回封面。 */
    private static final String[] CAPPELLA_AVATAR_NAMES = {"封面+我的头像", "主题色", "内置头像"};

    private static HudSetting[] styleSettings() {
        switch (FullscreenLyricStyle.current()) {
            case LUMINOUS:
            case LUMINOUS_CLASSIC: return LUMINOUS_SETTINGS;
            case PARTITA: return PARTITA_SETTINGS;
            case FUME: return FUME_SETTINGS;
            case CAPPELLA: return CAPPELLA_SETTINGS;
            case TILT: return TILT_SETTINGS;
            case DIORAMA: return DIORAMA_SETTINGS;
            case PENDOLO: return PENDOLO_SETTINGS;
            default: return NO_SETTINGS;
        }
    }

    private static int[] styleToggles() {
        switch (FullscreenLyricStyle.current()) {
            case LUMINOUS:
            case LUMINOUS_CLASSIC: return LUMINOUS_TOGGLES;
            case PARTITA: return PARTITA_TOGGLES;
            case FUME: return FUME_TOGGLES;
            case CAPPELLA: return CAPPELLA_TOGGLES;
            case TILT: return TILT_TOGGLES;
            case DIORAMA: return DIORAMA_TOGGLES;
            case PENDOLO: return PENDOLO_TOGGLES;
            default: return NO_TOGGLES;
        }
    }

    private static String toggleLabel(int id) {
        switch (id) {
            case TOGGLE_LUMINOUS_ROTATION: return "字词旋转";
            case TOGGLE_PARTITA_GUIDES: return "指示线";
            case TOGGLE_FUME_BACKGROUND: return "背景几何体";
            case TOGGLE_CAPPELLA_AVATAR: return "头像来源";
            case TOGGLE_CAPPELLA_EMOJI: return "表情气泡";
            case TOGGLE_TILT_COLORS: return "配色方案";
            case TOGGLE_DIORAMA_DUST: return "浮尘";
            case TOGGLE_PENDOLO_GEARS: return "齿轮装饰";
            default: return "";
        }
    }

    private static String toggleValue(int id) {
        switch (id) {
            case TOGGLE_LUMINOUS_ROTATION: return HudConfig.luminousWordRotation ? "开" : "关";
            case TOGGLE_PARTITA_GUIDES: return HudConfig.partitaGuideLines ? "开" : "关";
            case TOGGLE_FUME_BACKGROUND: return HudConfig.fumeGeometricBackground ? "开" : "关";
            case TOGGLE_CAPPELLA_AVATAR:
                return CAPPELLA_AVATAR_NAMES[Math.max(0, Math.min(CAPPELLA_AVATAR_NAMES.length - 1,
                        HudConfig.cappellaAvatarSource))];
            case TOGGLE_CAPPELLA_EMOJI: return HudConfig.cappellaEmojiEnabled ? "开" : "关";
            case TOGGLE_TILT_COLORS:
                return TILT_COLOR_NAMES[Math.max(0, Math.min(TILT_COLOR_NAMES.length - 1,
                        HudConfig.tiltColorScheme))];
            case TOGGLE_DIORAMA_DUST: return HudConfig.dioramaDust ? "开" : "关";
            case TOGGLE_PENDOLO_GEARS: return HudConfig.pendoloGearDecor ? "开" : "关";
            default: return "";
        }
    }

    /** 换下一档。{@code back} 为 true 表示往回退一档（多档枚举才有区别）。 */
    private static void cycleToggle(int id, boolean back) {
        switch (id) {
            case TOGGLE_LUMINOUS_ROTATION:
                HudConfig.luminousWordRotation = !HudConfig.luminousWordRotation;
                break;
            case TOGGLE_PARTITA_GUIDES:
                HudConfig.partitaGuideLines = !HudConfig.partitaGuideLines;
                break;
            case TOGGLE_FUME_BACKGROUND:
                HudConfig.fumeGeometricBackground = !HudConfig.fumeGeometricBackground;
                break;
            case TOGGLE_CAPPELLA_AVATAR: {
                int span = CAPPELLA_AVATAR_NAMES.length;
                int next = HudConfig.cappellaAvatarSource + (back ? -1 : 1);
                HudConfig.cappellaAvatarSource = ((next % span) + span) % span;
                break;
            }
            case TOGGLE_CAPPELLA_EMOJI:
                HudConfig.cappellaEmojiEnabled = !HudConfig.cappellaEmojiEnabled;
                break;
            case TOGGLE_TILT_COLORS: {
                int span = TILT_COLOR_NAMES.length;
                int next = HudConfig.tiltColorScheme + (back ? -1 : 1);
                HudConfig.tiltColorScheme = ((next % span) + span) % span;
                break;
            }
            case TOGGLE_DIORAMA_DUST:
                HudConfig.dioramaDust = !HudConfig.dioramaDust;
                break;
            case TOGGLE_PENDOLO_GEARS:
                HudConfig.pendoloGearDecor = !HudConfig.pendoloGearDecor;
                break;
            default:
                return;
        }
        HudConfig.save();
    }

    private static float fluidSliderValue(int index) {
        if (index == FLUID_SATURATION_SLIDER) return HudConfig.fullscreenLyricFluidSaturation;
        if (index == FLUID_AUDIO_SLIDER) return HudConfig.fullscreenLyricFluidAudio;
        return 0f;
    }

    private static void setFluidSliderValue(int index, float value) {
        if (index == FLUID_SATURATION_SLIDER) {
            HudConfig.fullscreenLyricFluidSaturation = Math.max(0f, Math.min(200f, value));
        } else if (index == FLUID_AUDIO_SLIDER) {
            HudConfig.fullscreenLyricFluidAudio = Math.max(0f, Math.min(200f, value));
        }
    }

    private static String fluidSliderText(int index, float value) {
        return index == FLUID_SATURATION_SLIDER || index == FLUID_AUDIO_SLIDER
                ? Math.round(value) + "%" : String.format("%.2f", value);
    }

    /**
     * 背景层叠的不透明度助手。滑杆下标 0/1 对应位索引 bit0/bit1——这里「下标」与「位」刻意合一，
     * 命中行拿到的 index 既能进表查标签，也能直接当位掩码的位用。
     */
    private static float layerOpacityValue(int index) {
        if (index == LAYER_OPACITY_LATENT) return HudConfig.fullscreenLyricLayerLatentDither;
        if (index == LAYER_OPACITY_SORA) return HudConfig.fullscreenLyricLayerSora;
        return 0f;
    }

    private static void setLayerOpacityValue(int index, float value) {
        if (index == LAYER_OPACITY_LATENT) {
            HudConfig.fullscreenLyricLayerLatentDither = Math.max(0f, Math.min(100f, value));
        } else if (index == LAYER_OPACITY_SORA) {
            HudConfig.fullscreenLyricLayerSora = Math.max(0f, Math.min(100f, value));
        }
    }

    /** 第 {@code bit} 位（bit0=Latent 抖动层，bit1=Sora 星空层）当前有没有开。 */
    private static boolean layerEnabled(int bit) {
        return (HudConfig.fullscreenLyricBackgroundLayers & (1 << bit)) != 0;
    }
    private static final double MARGIN = 12.0;
    private static final double BUTTON_SIZE = 24.0;
    private static final double PANEL_WIDTH = 178.0;
    private static final double PANEL_PADDING = 10.0;
    private static final double TITLE_HEIGHT = 18.0;
    private static final double ROW_HEIGHT = 30.0;
    /** 分列时列与列之间的空隙。 */
    private static final double COLUMN_GAP = 8.0;
    /** 分列的上限。再多列会让面板横向铺满左栏，也会把「一列变两列」这件简单的事变复杂。 */
    private static final int MAX_COLUMNS = 3;
    /** 面板右边缘与歌词列之间留的空隙，防止贴到歌词上。 */
    private static final double PANEL_RIGHT_GAP = 8.0;
    private static final double TRACK_HEIGHT = 4.0;
    /** 整行按钮的高度（样式切换、配色模式、颜色数量），不需要滑块那么高。 */
    private static final double PICK_ROW_HEIGHT = 26.0;
    /** 分组小标题。 */
    private static final double SECTION_HEIGHT = 17.0;
    /** 色块行。 */
    private static final double SWATCH_ROW_HEIGHT = 26.0;
    private static final double SWATCH_HEIGHT = 18.0;
    private static final double SWATCH_GAP = 4.0;
    /** 色相 / 饱和度 / 明度三条紧凑滑块。 */
    private static final double HSB_ROW_HEIGHT = 20.0;
    private static final double HSB_TRACK_HEIGHT = 6.0;
    /** HSB 行左侧标签占的宽度。 */
    private static final double HSB_LABEL_WIDTH = 34.0;

    /**
     * 面板整体的最小缩放。
     *
     * <p>分列之后 {@link #updateUiScale()} 已经很少触发了，这里只是最后一档兜底：窗口小到
     * {@link #MAX_COLUMNS} 列都排不下（可用宽度不够分列、可用高度又太矮）时，面板才整体缩小，
     * 缩到这个下限就不再缩（再小文字就没法看了；到那一档为止都还塞得进最小的视口）。</p>
     */
    private static final double MIN_UI_SCALE = .62;

    /** 色相条画成多少段。段数够多就看不出台阶，又不至于为了一条 158 像素的轨道画上百个矩形。 */
    private static final int HUE_SEGMENTS = 26;
    private static final int RAMP_SEGMENTS = 16;

    private static final String[] HSB_LABELS = {"色相", "饱和", "明度"};

    /**
     * 一行控件的位置与显示状态。
     *
     * <p>{@code x / y / height} 是<b>这一帧真正画在哪</b>——{@link #followLayout()} 每帧把目标值追过去的
     * 结果；{@code targetX / targetY} 是 {@link #layout()} 这一帧算出来的目标。渲染与命中都读显示值，
     * 所以「看到的位置」和「点得到的位置」永远是同一处，过渡动画进行到一半时也不例外。</p>
     */
    private static final class Row {
        int kind;
        int index;
        /** 所在列的左边缘；不分列时恒为 {@code anchorX}。 */
        double x;
        /** 屏幕上的纵向位置（已经扣掉本帧的滚动量，见 {@link #appliedScroll}）。 */
        double y;
        double height;
        double targetX;
        double targetY;
        /** 显示透明度：新出现的行从 0 淡入，被删掉的行淡到看不见才真正离开行表。 */
        float alpha = 1f;
        float targetAlpha = 1f;
        /** 这一行在<b>本帧的目标表</b>里还活着；只在一帧之内有效。 */
        boolean present;
    }

    /**
     * 目标行表：{@link #layout()} 每帧重写一遍，{@link #followLayout()} 拿它去认领显示行。
     *
     * <p>和显示行表分开是有意的。切换样式 / 背景 / 配色模式会让行数整块变化（最多的一档三十一行），
     * 只维护一份行表的话就只能「瞬间换掉」——那正是「切换时大小突变、栏目凭空增减」的来源。分成两份
     * 之后，新行可以从透明淡入、消失的行可以留在原地淡出，其余行平滑挪到新位置。</p>
     */
    /**
     * 把 QPlayer 的那两个字段登记给 {@code FoliaBackgroundOptions}。
     *
     * <p>{@code HudConfig} 是按版本分叉的类（见 {@code SHARE-MANIFEST.md}），共享层的
     * options 类不能直接 import 它；这里用一个静态块登记实现，取代「options 里反射读字段」。</p>
     */
    static {
        com.muoniumplayer.core.screens.ncm.foliabg.FoliaBackgroundOptions.installHudBridge(
                new com.muoniumplayer.core.screens.ncm.foliabg.FoliaBackgroundOptions.HudBridge() {
                    @Override
                    public void setBackgroundMode(int mode) {
                        HudConfig.fullscreenLyricBackgroundMode = mode;
                        HudConfig.save();
                    }

                    @Override
                    public int backgroundMode() {
                        return HudConfig.fullscreenLyricBackgroundMode;
                    }

                    @Override
                    public String fluidStyleName() {
                        int index = displayIndexForStyle(HudConfig.fullscreenLyricFluidStyle);
                        return FLUID_STYLE_NAMES[Math.max(0, Math.min(FLUID_STYLE_NAMES.length - 1, index))];
                    }
                });
    }

    /** 当前档位下要显示的行；每帧算一次（档位或子选项变了就变）。 */
    private static java.util.List<com.muoniumplayer.core.screens.ncm.foliabg.FoliaBackgroundOptions.Spec>
            foliaSpecs() {
        return com.muoniumplayer.core.screens.ncm.foliabg.FoliaBackgroundOptions
                .rowsFor(com.muoniumplayer.core.screens.ncm.foliabg.FoliaBackgroundOptions.currentKind());
    }

    private final List<Row> targetRows = new ArrayList<Row>();
    private int targetCount;
    /** 显示行表；渲染与命中共用。 */
    private final List<Row> rows = new ArrayList<Row>();
    /** {@link #followLayout()} 组装新行表时的暂存，省得每帧新建一个列表。 */
    private final List<Row> mergedRows = new ArrayList<Row>();

    /** 目标内容高度（{@link #layout()} 写）。 */
    private double layoutHeight;
    /** 面板目标宽度；分列后会宽于 {@link #PANEL_WIDTH}。 */
    private double layoutWidth = PANEL_WIDTH;
    /** 正在画的面板宽高：追着目标值走，所以分列与增删行都是「长出来」而不是瞬间跳。 */
    private double shownWidth = -1;
    private double shownHeight = -1;

    /**
     * 布局跟随速度，交给 {@link Interpolations#interpolate} 用。
     *
     * <p>与 {@code expandAnimation} 的 {@code .22f} 同一个量纲（乘帧时间），越大追得越快。取值下大约
     * 八到十帧走完：切换样式时看得出是「长过去」的，又不会慢到让人以为没反应。</p>
     *
     * <p>刻意声明成 {@code float}：行透明度是 float，用它才能命中 {@code interpolate(float, float,
     * float)} 这个重载；位置那几个 double 传进去会自动提升，不影响精度。</p>
     */
    private static final float LAYOUT_FOLLOW = .38f;

    /**
     * 面板的整体缩放，1 表示原尺寸。
     *
     * <p>布局与命中判定全部在<b>未缩放</b>的坐标系里做，只有绘制时套一次
     * {@code scaleAtPos(anchorX, panelTop, uiScale)}；鼠标坐标在进入面板逻辑之前用
     * {@link #toLayoutX}/{@link #toLayoutY} 反变换回去。这样「点得到的地方」和「画出来的地方」
     * 永远是同一处，不需要在每个命中框上重复乘一遍缩放，缩放有过渡动画时也不会错位。</p>
     */
    private double uiScale = 1.0;

    /** 目标缩放（{@link #updateUiScale()} 写）；{@link #uiScale} 追着它走。 */
    private double targetUiScale = 1.0;

    /** 面板底部最多能画到哪（绝对坐标），由调用方给的视口高度算出。 */
    private double panelSpaceBottom = Double.NaN;

    /** 歌词页视口的宽度，用来算「面板最宽能到哪」（见 {@link #availableWidth()}）。 */
    private double viewWidth = Double.NaN;

    private boolean expanded;
    private float expandAnimation;
    private boolean pendingSave;

    /** 正在拖的是哪一行；{@code dragKind < 0} 表示没在拖。 */
    private int dragKind = -1;
    private int dragIndex = -1;

    /** 流体背景与歌词参数超出可用高度时，面板内部滚动。 */
    private double scrollOffset;
    private double scrollTarget;

    /**
     * 上一帧已经扣掉的滚动量。
     *
     * <p>行位置要平滑跟随目标布局，而滚动是「整块平移、必须跟手」的——两者直接叠在一起的话，滚动会
     * 被位置的平滑拖出一段黏滞。所以每帧先把上一次扣掉的滚动量退回去、平滑到目标之后再扣上这一次的：
     * 位置动画照旧平滑，滚动依旧是即时的。</p>
     */
    private double appliedScroll;

    /** 正在编辑调色板的第几槽。 */
    private int editSlot;
    /** 上一次同步过的槽颜色，用来发现「颜色被别处改了」而重算 HSB。 */
    private int editRgbCache = -1;
    private float editHue;
    private float editSat;
    private float editBri;

    /** 上一帧的按钮/面板位置，供 {@code consumeClick} 复用（点击链路拿不到布局参数）。 */
    private double anchorX = Double.NaN;
    private double anchorY = Double.NaN;
    private float lastAlpha;

    /**
     * @param viewWidth  歌词页视口的宽度，用来算面板最宽能到哪（见 {@link #availableWidth()}）
     * @param viewHeight 歌词页视口的高度，用来决定分几列、以及要不要整体缩小（见 {@link #MIN_UI_SCALE}）
     */
    void render(double mouseX, double mouseY, double posX, double posY,
                double viewWidth, double viewHeight, float alpha) {
        if (alpha <= .004f) return;
        this.anchorX = posX + MARGIN;
        this.anchorY = posY + MARGIN;
        this.lastAlpha = alpha;
        this.viewWidth = viewWidth;
        this.panelSpaceBottom = posY + viewHeight - MARGIN;

        // 面板收起时不再跟随鼠标，拖动状态也要清掉，否则收起后仍然会改数值。
        if (!expanded && dragKind >= 0) {
            releaseDrag();
        }

        // 收起且动画已停时只需要画 A-Z 按钮。此前每帧仍然重建整张行布局，
        // 在低配机上给全屏歌词转场额外叠加了一串对象分配。
        if (!expanded && expandAnimation <= .004f) {
            renderButton(mouseX, mouseY, alpha);
            return;
        }

        layout();
        updateUiScale();
        // 滚动先算：followLayout() 要靠本帧的滚动量把「上一帧扣掉的那一份」退回去。
        updateScroll();
        followLayout();
        syncEditColor();
        updateDrag(mouseX, mouseY);

        expandAnimation = Interpolations.interpolate(expandAnimation, expanded ? 1f : 0f, .22f);

        renderPanel(toLayoutX(mouseX), toLayoutY(mouseY), alpha);
        // 按钮画在缩放之外，所以用未变换的鼠标坐标。
        renderButton(mouseX, mouseY, alpha);
    }

    /**
     * 分列之后还是装不下时，才把整块面板等比缩小。
     *
     * <p>尺寸都是分列后的真实尺寸：{@link #layoutWidth} 可能已经宽过 {@link #PANEL_WIDTH}，
     * {@link #layoutHeight} 是最高那一列的高度。所以这一档现在只在「窗口小到列数顶满还排不下」
     * 时生效；只要排得下，{@code uiScale} 就是 1，字号与加这段之前完全一致。</p>
     *
     * <p>这里只算目标值，真正的缩放由 {@link #followLayout()} 平滑过去，窗口拉伸时不会一帧跳档。</p>
     */
    private void updateUiScale() {
        double available = availableHeight();
        if (Double.isNaN(anchorY) || layoutHeight <= 0 || available <= 8) {
            targetUiScale = 1.0;
            return;
        }
        double scale = 1.0;
        if (layoutHeight > available) {
            scale = available / layoutHeight;
        }
        double room = availableWidth();
        if (layoutWidth > room) {
            scale = Math.min(scale, room / layoutWidth);
        }
        targetUiScale = Math.max(MIN_UI_SCALE, scale);
    }

    /** 面板顶部到视口底部可用的高度；视口高度未知时返回 {@link Double#MAX_VALUE}（等于不限制）。 */
    private double availableHeight() {
        if (Double.isNaN(panelSpaceBottom)) return Double.MAX_VALUE;
        return Math.max(0.0, panelSpaceBottom - panelTop());
    }

    /**
     * 面板左边缘到歌词列之间可用的宽度。
     *
     * <p>上限是 {@link LyricsPanelGeometry#lyricColumnLeft} 再往前留 {@link #PANEL_RIGHT_GAP}：
     * 面板可以变宽，但绝不许压到歌词正文上。宽度未知时返回 {@link Double#MAX_VALUE}，此时列数
     * 仍受 {@link #MAX_COLUMNS} 限制。</p>
     */
    private double availableWidth() {
        if (Double.isNaN(viewWidth)) return Double.MAX_VALUE;
        double limit = LyricsPanelGeometry.lyricColumnLeft(anchorX - MARGIN, viewWidth) - PANEL_RIGHT_GAP;
        return Math.max(PANEL_WIDTH, limit - anchorX);
    }

    /**
     * 面板内容超过可见高度时，在内层滚轮滚动。返回 true 表示这次滚轮已被面板吃掉。
     */
    boolean handleScroll(int wheel, double mouseX, double mouseY) {
        if (!expanded || wheel == 0 || Double.isNaN(anchorX)) return false;
        double layoutX = toLayoutX(mouseX);
        double layoutY = toLayoutY(mouseY);
        if (!isInside(layoutX, layoutY, anchorX, panelTop(), shownWidth, panelHeight())) {
            return false;
        }
        double max = Math.max(0.0, contentHeight() - panelHeight());
        scrollTarget = Math.max(0.0, Math.min(max, scrollTarget - wheel * 24.0));
        return true;
    }

    /** 本轮纵向拖动是否由本面板认领。 */
    private boolean quickTouchDragging;

    /** 甩动的最小速度（GUI 像素/ms）：低于它的松手只停住，不给惯性。 */
    private static final double QUICK_FLING_MIN_VELOCITY = 0.05;

    /**
     * 触屏拖动滚动：认领/跟手/甩动，与 {@link #handleScroll} 同一套命中判定与符号
     * （{@code scrollTarget - dy}，偏移 0 = 顶）。由 MusicLyricsPanel 每帧驱动——
     * 本面板没有自己的每帧入口，歌词页是它唯一的宿主。
     */
    void advanceTouchDrag() {
        TouchGestures gestures = TouchGestures.get();
        if (!TouchSettings.ENABLED || !expanded || Double.isNaN(anchorX)) {
            this.quickTouchDragging = false;
            return;
        }
        if (this.quickTouchDragging) {
            if (gestures.isVerticalDrag() && gestures.claimedBy() == this) {
                double dy = gestures.dy();
                if (dy != 0) {
                    double max = Math.max(0.0, contentHeight() - panelHeight());
                    scrollTarget = Math.max(0.0, Math.min(max, scrollTarget - dy));
                    scrollOffset = scrollTarget;
                }
                return;
            }
            this.quickTouchDragging = false;
            if (!gestures.isPressed()) {
                double velocity = gestures.velocityY();
                if (Math.abs(velocity) > QUICK_FLING_MIN_VELOCITY) {
                    double distance = Math.min(TouchSettings.FLING_MAX_DISTANCE,
                            (velocity * velocity) / (2.0 * TouchSettings.FLING_DECEL));
                    double max = Math.max(0.0, contentHeight() - panelHeight());
                    scrollTarget = Math.max(0.0, Math.min(
                            max, scrollTarget - Math.signum(velocity) * distance));
                }
            }
            return;
        }
        if (gestures.isVerticalDrag()
                && gestures.claimedBy() == null
                && contentHeight() - panelHeight() > 0.0
                && isInside(toLayoutX(gestures.startX()), toLayoutY(gestures.startY()),
                        anchorX, panelTop(), shownWidth, panelHeight())) {
            this.quickTouchDragging = gestures.claim(this);
        }
    }

    private void updateScroll() {
        double max = Math.max(0.0, contentHeight() - panelHeight());
        if (scrollTarget > max) scrollTarget = max;
        if (scrollOffset > max) scrollOffset = max;
        scrollOffset += (scrollTarget - scrollOffset) * .30;
        if (Math.abs(scrollTarget - scrollOffset) < .1) scrollOffset = scrollTarget;
    }

    /**
     * 合并目标行表，并把显示值平滑推向目标。
     *
     * <p>三件事一起做，顺序不能调：</p>
     * <ol>
     *   <li><b>认领</b>——目标表里每一条，按「同 kind 同 index」在上一帧的显示行里找它的前世。找不到
     *       就是新出现的行：直接落在目标位置、从透明淡入，不从别处飞过来（对用户来说它就是「这里多了
     *       一项」）。找到的行改写目标坐标，于是它自己滑过去，包括整行换列时横着挪一段。</li>
     *   <li><b>退场</b>——上一帧有、这一帧目标表里没有的行留在原地淡出，淡到看不见才从行表里删掉。
     *       直接删的话，切换样式时就是一排控件凭空消失；现在是它们先淡掉，后面几行紧接着补位。</li>
     *   <li><b>平滑</b>——行位置 / 行透明度 / 面板宽高 / 整体缩放一起追目标值。</li>
     * </ol>
     *
     * <p>已经认领过的行会打上 {@code present}，所以「同 key 出现两次」（理论上不会，layout() 里
     * kind + index 唯一）只会认领到最近的那个，不会两行共用一个对象。</p>
     */
    private void followLayout() {
        boolean first = shownWidth < 0;
        if (first) {
            // 面板第一次展开：直接落在目标尺寸上。出现的观感由 expandAnimation 负责，不必再叠一层。
            shownWidth = layoutWidth;
            shownHeight = layoutHeight;
            uiScale = targetUiScale;
        }

        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).present = false;
        }

        mergedRows.clear();
        for (int i = 0; i < targetCount; i++) {
            Row target = targetRows.get(i);
            Row row = claimRow(rows, target.kind, target.index);
            if (row == null) {
                row = new Row();
                row.kind = target.kind;
                row.index = target.index;
                row.x = target.targetX;
                row.y = target.targetY - scrollOffset;
                row.alpha = 0f;
            }
            row.present = true;
            row.height = target.height;
            row.targetX = target.targetX;
            row.targetY = target.targetY;
            row.targetAlpha = 1f;
            mergedRows.add(row);
        }
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.present) continue;
            // 退场：停在原地淡出，不跟着目标表的新位置跑。
            row.targetAlpha = 0f;
            row.targetX = row.x;
            row.targetY = row.y + appliedScroll;
            if (row.alpha > .015f) {
                mergedRows.add(row);
            }
        }
        rows.clear();
        rows.addAll(mergedRows);

        if (first) {
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                row.x = row.targetX;
                row.y = row.targetY - scrollOffset;
                row.alpha = row.targetAlpha;
            }
            appliedScroll = scrollOffset;
            return;
        }

        shownWidth = Interpolations.interpolate(shownWidth, layoutWidth, LAYOUT_FOLLOW);
        shownHeight = Interpolations.interpolate(shownHeight, layoutHeight, LAYOUT_FOLLOW);
        uiScale = Interpolations.interpolate(uiScale, targetUiScale, LAYOUT_FOLLOW);

        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            row.x = Interpolations.interpolate(row.x, row.targetX, LAYOUT_FOLLOW);
            // 先把上一帧扣掉的滚动量退回来再平滑，滚动因此不会被位置动画拖慢。
            row.y = Interpolations.interpolate(row.y + appliedScroll, row.targetY, LAYOUT_FOLLOW)
                    - scrollOffset;
            row.alpha = Interpolations.interpolate(row.alpha, row.targetAlpha, LAYOUT_FOLLOW);
        }
        appliedScroll = scrollOffset;
    }

    /** 在显示行表里找「同 kind 同 index」且这一帧还没被认领的行；没有就返回 null。 */
    private Row claimRow(List<Row> pool, int kind, int index) {
        for (int i = 0; i < pool.size(); i++) {
            Row row = pool.get(i);
            if (row.present || row.kind != kind || row.index != index) continue;
            return row;
        }
        return null;
    }

    /** 屏幕坐标 -> 布局坐标。缩放是绕（anchorX, panelTop）做的，所以反变换也绕这一点。 */
    private double toLayoutX(double mouseX) {
        return anchorX + (mouseX - anchorX) / Math.max(1.0e-3, uiScale);
    }

    private double toLayoutY(double mouseY) {
        double top = panelTop();
        return top + (mouseY - top) / Math.max(1.0e-3, uiScale);
    }

    /**
     * 按当前配置顺序摆一遍行，并决定分几列。结果写进 {@link #targetRows}，不直接改显示行表。
     *
     * <p>行只负责「顺序」和「高度」；放在第几列、列内什么位置由
     * {@link LyricsPanelGeometry#packQuickPanel} 统一分配。一列装得下时结果与不分列时逐像素一致。</p>
     *
     * <p>背景配色那几行只在「律动词云」样式下出现——它们只对那一种样式有效果，在经典样式下摆出来
     * 只会让人以为调了没反应。</p>
     */
    private void layout() {
        targetCount = 0;
        // ---- 1) 先按显示顺序把行收集起来 ----
        addRow(KIND_STYLE, 0, PICK_ROW_HEIGHT);
        // ---- 背景：一行切六档（QPlayer 流体 / 模糊封面 / 四种 Folia 样式），
        //      下面紧接着换成这一档自己的参数。切换即换配置项。----
        addRow(KIND_BACKGROUND_KIND, 0, PICK_ROW_HEIGHT);
        if (FoliaBackgroundOptions.isQPlayer(FoliaBackgroundOptions.currentKind())) {
            if (FoliaBackgroundOptions.currentKind() == FoliaBackgroundOptions.KIND_QPLAYER_FLUID) {
                addRow(KIND_SECTION, 3, SECTION_HEIGHT);
                addRow(KIND_FLUID_STYLE, 0, PICK_ROW_HEIGHT);
                addRow(KIND_BACKGROUND, 0, PICK_ROW_HEIGHT);
                addRow(KIND_FLUID_SLIDER, FLUID_SATURATION_SLIDER, ROW_HEIGHT);
                addRow(KIND_FLUID_SLIDER, FLUID_AUDIO_SLIDER, ROW_HEIGHT);
            }
        } else {
            List<FoliaBackgroundOptions.Spec> specs = foliaSpecs();
            for (int i = 0; i < specs.size(); i++) {
                addRow(KIND_FOLIA_SPEC, i, FoliaBackgroundOptions.rowHeight(specs.get(i)));
            }
        }
        // 全局效果开关，与用哪套背景无关，紧挨背景组方便一起够到。
        addRow(KIND_EDGE_BLUR, 0, PICK_ROW_HEIGHT);
        addRow(KIND_THEME_SOURCE, 0, PICK_ROW_HEIGHT);

        for (int i = 0; i < SETTINGS.length; i++) {
            addRow(KIND_SLIDER, i, ROW_HEIGHT);
        }
        // 歌词同步：跟样式无关，只跟「这首歌的歌词对不对」有关，所以排在通用项之后、样式专属参数
        // 之前。刻意不放到面板最底下 —— 底部在「最小视口 + 律动词云自定义配色」那一档会被整体
        // 缩放裁掉，摆在那里会让这个功能在那种配置下直接够不到。
        addRow(KIND_SECTION, 2, SECTION_HEIGHT);
        addRow(KIND_LYRIC_SOURCE, 0, PICK_ROW_HEIGHT);
        addRow(KIND_LYRIC_SEARCH, 0, PICK_ROW_HEIGHT);
        addRow(KIND_LYRIC_RELOAD, 0, PICK_ROW_HEIGHT);
        addRow(KIND_LYRIC_OFFSET, 0, ROW_HEIGHT);
        HudSetting[] styleSliders = styleSettings();
        int[] toggles = styleToggles();
        if (styleSliders.length > 0 || toggles.length > 0) {
            addRow(KIND_SECTION, 1, SECTION_HEIGHT);
            for (int i = 0; i < styleSliders.length; i++) {
                addRow(KIND_STYLE_SLIDER, i, ROW_HEIGHT);
            }
            for (int i = 0; i < toggles.length; i++) {
                addRow(KIND_STYLE_TOGGLE, i, PICK_ROW_HEIGHT);
            }
        }
        if (FullscreenLyricStyle.current() == FullscreenLyricStyle.CADENZA) {
            addRow(KIND_SECTION, 0, SECTION_HEIGHT);
            addRow(KIND_DECOR_MODE, 0, PICK_ROW_HEIGHT);
            if (HudConfig.cadenzaDecorColorMode == 1) {
                addRow(KIND_BEATS, 0, ROW_HEIGHT);
                addRow(KIND_COLOR_COUNT, 0, PICK_ROW_HEIGHT);
                addRow(KIND_SWATCHES, 0, SWATCH_ROW_HEIGHT);
                for (int i = 0; i < HSB_LABELS.length; i++) {
                    addRow(KIND_HSB, i, HSB_ROW_HEIGHT);
                }
            }
        }

        // ---- 2) 交给纯几何层分列：装不下就加列，而不是把字缩小 ----
        int count = targetCount;
        double[] heights = new double[count];
        boolean[] keepWithNext = new boolean[count];
        for (int i = 0; i < count; i++) {
            Row row = targetRows.get(i);
            heights[i] = row.height;
            // 分节小标题必须和它管的第一行同列，否则标题孤零零挂在列尾。
            keepWithNext[i] = row.kind == KIND_SECTION;
        }
        LyricsPanelGeometry.ColumnPacking packing = LyricsPanelGeometry.packQuickPanel(
                heights, keepWithNext, availableWidth(), availableHeight(),
                PANEL_PADDING * 2 + TITLE_HEIGHT, PANEL_WIDTH, COLUMN_GAP, MAX_COLUMNS);

        // ---- 3) 按分列结果定位；列内高度各自累加，滚动照旧整块平移 ----
        double contentTop = panelTop() + PANEL_PADDING + TITLE_HEIGHT;
        double[] columnUsed = new double[packing.columns];
        for (int i = 0; i < count; i++) {
            Row row = targetRows.get(i);
            int column = packing.columnOf[i];
            row.targetX = columnLeft(column);
            row.targetY = contentTop + columnUsed[column];
            columnUsed[column] += row.height;
        }
        this.layoutWidth = packing.width;
        this.layoutHeight = PANEL_PADDING * 2 + TITLE_HEIGHT + packing.maxColumnHeight;
    }

    private void addRow(int kind, int index, double height) {
        Row row = targetRow(targetCount++);
        row.kind = kind;
        row.index = index;
        row.height = height;
    }

    /** 目标表里第 {@code i} 行。行对象在这里复用，稳态下不再分配。 */
    private Row targetRow(int i) {
        while (targetRows.size() <= i) {
            targetRows.add(new Row());
        }
        return targetRows.get(i);
    }

    /** 第 {@code column} 列的左边缘。 */
    private double columnLeft(int column) {
        return anchorX + column * (PANEL_WIDTH + COLUMN_GAP);
    }

    /**
     * @return true 表示这次点击已被本面板吃掉，调用方不应再往下派发
     */
    boolean consumeClick(double mouseX, double mouseY, int mouseButton) {
        if (Double.isNaN(anchorX) || lastAlpha <= .5f) {
            return false;
        }

        if (isInside(mouseX, mouseY, anchorX, anchorY, BUTTON_SIZE, BUTTON_SIZE)) {
            if (mouseButton == 0) {
                expanded = !expanded;
                if (!expanded) {
                    releaseDrag();
                }
            }
            return true;
        }

        if (!expanded) {
            return false;
        }

        // 面板内部的一切判定都在布局坐标系里做，先把鼠标反变换过去。
        double layoutX = toLayoutX(mouseX);
        double layoutY = toLayoutY(mouseY);

        if (!isInside(layoutX, layoutY, anchorX, panelTop(), shownWidth, panelHeight())) {
            // 点面板外面就收起来，并且这一下不吃掉：让它照常落到下面的控件上。
            expanded = false;
            releaseDrag();
            return false;
        }

        if (mouseButton == 0 || mouseButton == 1) {
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                // 正在淡入 / 淡出的行不接受点击：看得见一点点却点得到，比点不到更难理解。
                if (row.alpha <= .5f) continue;
                if (!isInside(layoutX, layoutY, rowLeft(row), row.y - 2, trackWidth(), row.height)) {
                    continue;
                }
                handleRowClick(row, layoutX, mouseButton);
                break;
            }
        }
        return true;
    }

    private void handleRowClick(Row row, double mouseX, int mouseButton) {
        boolean right = mouseButton == 1;
        if (row.kind == KIND_BACKGROUND_KIND) {
            // 左键下一档、右键上一档；切完立刻重排，下面的参数整块换成新档位的。
            int kind = FoliaBackgroundOptions.currentKind();
            FoliaBackgroundOptions.applyKind(kind + (right ? -1 : 1));
            scrollTarget = 0;
            return;
        }
        if (row.kind == KIND_FOLIA_SPEC) {
            List<FoliaBackgroundOptions.Spec> specs = foliaSpecs();
            if (row.index >= 0 && row.index < specs.size()) {
                handleFoliaSpec(specs.get(row.index), right);
            }
            return;
        }

        if (row.kind == KIND_EDGE_BLUR) {
            // 布尔开关：左键切换，右键无效；下一帧立即生效（渲染状态每帧从配置读起）。
            if (!right) {
                HudConfig.fullscreenLyricEdgeBlur = !HudConfig.fullscreenLyricEdgeBlur;
                HudConfig.save();
            }
            return;
        }
        if (row.kind == KIND_STYLE) {
            // 右键往回退一档：十种样式只能单向轮换的话，想回上一种得点九下。
            if (right) FullscreenLyricStyle.previous();
            else FullscreenLyricStyle.next();
            return;
        }
        if (row.kind == KIND_BACKGROUND) {
            HudConfig.fullscreenLyricFluidStatic = !HudConfig.fullscreenLyricFluidStatic;
            HudConfig.save();
            return;
        }
        if (row.kind == KIND_THEME_SOURCE) {
            if (right) ThemeColorSource.previous();
            else ThemeColorSource.next();
            return;
        }
        if (row.kind == KIND_LYRIC_SOURCE) {
            // 左键往后一档、右键往前一档，与上面「样式 / 背景 / 配色来源」几条完全一致。
            // 切换本身就等于重取，所以这一行不需要再有第二个动作。
            CloudMusic.cycleLyricSource(!right);
            return;
        }
        if (row.kind == KIND_LYRIC_SEARCH) {
            if (right) {
                // 右键只有「取消钉住」这一件事：没钉过时 clearPinnedLyric() 自己会直接返回。
                CloudMusic.clearPinnedLyric();
                return;
            }
            NCMScreen screen = NCMScreen.getInstance();
            if (screen != null) screen.openLyricSearch();
            return;
        }
        if (row.kind == KIND_LYRIC_RELOAD) {
            // 只认左键：右键留在「重置偏移」那条上，这两件事都很容易误触，分开更安全。
            if (!right) CloudMusic.reloadLyrics();
            return;
        }
        if (row.kind == KIND_LYRIC_OFFSET) {
            if (right) {
                CloudMusic.resetLyricOffset();
                return;
            }
            dragKind = row.kind;
            dragIndex = row.index;
            applyDrag(mouseX, rowLeft(row));
            return;
        }
        if (row.kind == KIND_DECOR_MODE) {
            if (!right) {
                HudConfig.cadenzaDecorColorMode = HudConfig.cadenzaDecorColorMode == 1 ? 0 : 1;
                HudConfig.save();
            }
            return;
        }
        if (row.kind == KIND_COLOR_COUNT) {
            // 右键往回退一档：只能往前加的话想从 6 回到 2 得点四下。
            int count = HudConfig.cadenzaColorCount() + (right ? -1 : 1);
            int span = HudConfig.CADENZA_PALETTE_SLOTS - HudConfig.CADENZA_COLOR_COUNT_MIN + 1;
            int normalized = ((count - HudConfig.CADENZA_COLOR_COUNT_MIN) % span + span) % span;
            HudConfig.cadenzaDecorColorCount = HudConfig.CADENZA_COLOR_COUNT_MIN + normalized;
            if (editSlot >= HudConfig.cadenzaColorCount()) {
                editSlot = HudConfig.cadenzaColorCount() - 1;
                editRgbCache = -1;
            }
            HudConfig.save();
            return;
        }
        if (row.kind == KIND_SWATCHES) {
            int slot = swatchAt(mouseX, row);
            if (slot < 0) return;
            if (right) {
                // 右键把这一槽恢复出厂色：调砸了以后总得有条退路。
                HudConfig.setCadenzaSlotColor(slot, HudConfig.cadenzaDefaultSlotColor(slot));
                if (slot == editSlot) editRgbCache = -1;
                HudConfig.save();
                return;
            }
            if (slot != editSlot) {
                editSlot = slot;
                editRgbCache = -1;
            }
            return;
        }
        if (row.kind == KIND_STYLE_TOGGLE) {
            int[] toggles = styleToggles();
            if (row.index >= 0 && row.index < toggles.length) {
                cycleToggle(toggles[row.index], right);
            }
            return;
        }
        if (row.kind == KIND_FLUID_STYLE) {
            int current = displayIndexForStyle(HudConfig.fullscreenLyricFluidStyle);
            int next = current + (right ? -1 : 1);
            next = ((next % FLUID_STYLE_NAMES.length) + FLUID_STYLE_NAMES.length)
                    % FLUID_STYLE_NAMES.length;
            HudConfig.fullscreenLyricFluidStyle = FLUID_STYLE_INDEXES[next];
            HudConfig.save();
            return;
        }
        if (row.kind == KIND_LAYER_TOGGLE) {
            // 直接翻转位掩码里的对应位：只有开/关两态，左键切换，右键无意义。
            if (!right) {
                HudConfig.fullscreenLyricBackgroundLayers ^= (1 << row.index);
                HudConfig.save();
            }
            return;
        }
        if (right) {
            return;
        }
        if (row.kind == KIND_SLIDER || row.kind == KIND_BEATS || row.kind == KIND_HSB
                || row.kind == KIND_STYLE_SLIDER || row.kind == KIND_FLUID_SLIDER
                || row.kind == KIND_LAYER_OPACITY) {
            dragKind = row.kind;
            dragIndex = row.index;
            applyDrag(mouseX, rowLeft(row));
        }
    }

    private void updateDrag(double mouseX, double mouseY) {
        if (dragKind < 0) {
            return;
        }
        if (!Mouse.isButtonDown(0)) {
            releaseDrag();
            return;
        }
        // 纵向手势已锁定 = 用户在滚面板列表：横向的偏移微调让位，已起的拖动收掉。
        if (TouchGestures.get().isVerticalDrag()) {
            releaseDrag();
            return;
        }
        applyDrag(toLayoutX(mouseX), dragRowLeft());
    }

    /**
     * 正在拖的那一行落在哪一列的左边缘。
     *
     * <p>每帧重新查，不缓存按下时的横坐标：拖动过程中开关会增删行，布局可能整块重排（列数变化、
     * 行换列）。用上一帧的坐标会让滑块突然跳到别处。</p>
     */
    private double dragRowLeft() {
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.kind == dragKind && row.index == dragIndex) {
                return rowLeft(row);
            }
        }
        // 行已经不在了（比如拖动中把这一段收起来了）：退回第一列，至少不算错。
        return anchorX + PANEL_PADDING;
    }

    private void applyDrag(double mouseX, double left) {
        if (dragKind == KIND_FOLIA_SPEC) {
            java.util.List<FoliaBackgroundOptions.Spec> specs = foliaSpecs();
            if (dragIndex < 0 || dragIndex >= specs.size()) return;
            FoliaBackgroundOptions.Spec spec = specs.get(dragIndex);
            if (spec.kind != FoliaBackgroundOptions.KIND_SLIDER) return;
            double fraction = fractionAt(mouseX, left, trackWidth());
            float value = (float) (spec.min + (spec.max - spec.min) * fraction);
            if (spec.step > 0f) {
                value = spec.min + Math.round((value - spec.min) / spec.step) * spec.step;
            }
            FoliaBackgroundOptions.writeFloat(spec.id,
                    Math.max(spec.min, Math.min(spec.max, value)));
            pendingSave = true;
            return;
        }
        if (dragKind == KIND_SLIDER) {
            if (dragIndex < 0 || dragIndex >= SETTINGS.length) return;
            HudSetting setting = SETTINGS[dragIndex];
            double fraction = fractionAt(mouseX, left, trackWidth());
            setting.setValue((float) (setting.getMin() + (setting.getMax() - setting.getMin()) * fraction));
            pendingSave = true;
            return;
        }
        if (dragKind == KIND_STYLE_SLIDER) {
            HudSetting[] sliders = styleSettings();
            if (dragIndex < 0 || dragIndex >= sliders.length) return;
            HudSetting setting = sliders[dragIndex];
            double fraction = fractionAt(mouseX, left, trackWidth());
            setting.setValue((float) (setting.getMin() + (setting.getMax() - setting.getMin()) * fraction));
            pendingSave = true;
            return;
        }
        if (dragKind == KIND_FLUID_SLIDER) {
            if (dragIndex < 0 || dragIndex >= FLUID_SLIDER_LABELS.length) return;
            double fraction = fractionAt(mouseX, left, trackWidth());
            float value = (float) (FLUID_SLIDER_MIN[dragIndex]
                    + (FLUID_SLIDER_MAX[dragIndex] - FLUID_SLIDER_MIN[dragIndex]) * fraction);
            setFluidSliderValue(dragIndex, value);
            pendingSave = true;
            return;
        }
        if (dragKind == KIND_LAYER_OPACITY) {
            // index 既是滑杆下标也是位掩码的位索引（见 layerOpacityValue），这里只用它进表取范围。
            if (dragIndex < 0 || dragIndex >= LAYER_OPACITY_LABELS.length) return;
            double fraction = fractionAt(mouseX, left, trackWidth());
            float value = (float) (LAYER_OPACITY_MIN[dragIndex]
                    + (LAYER_OPACITY_MAX[dragIndex] - LAYER_OPACITY_MIN[dragIndex]) * fraction);
            setLayerOpacityValue(dragIndex, value);
            pendingSave = true;
            return;
        }
        if (dragKind == KIND_BEATS) {
            double fraction = fractionAt(mouseX, left, trackWidth());
            int span = HudConfig.CADENZA_BEATS_PER_COLOR_MAX - HudConfig.CADENZA_BEATS_PER_COLOR_MIN;
            HudConfig.cadenzaBeatsPerColor = HudConfig.CADENZA_BEATS_PER_COLOR_MIN
                    + (int) Math.round(fraction * span);
            pendingSave = true;
            return;
        }
        if (dragKind == KIND_HSB) {
            // 先同步一次：刚换过槽或刚右键恢复过出厂色时，另外两个通道还是上一槽的值，
            // 直接写回去会把颜色写成别的东西（最糟的情况是饱和度与明度都是 0，写出纯黑）。
            // 渲染每帧都会同步，所以正常操作下这里是空转；但正确性不该依赖「点击之前一定渲染过」。
            syncEditColor();
            double fraction = fractionAt(mouseX, left + HSB_LABEL_WIDTH, hsbTrackWidth());
            if (dragIndex == 0) {
                editHue = (float) fraction;
            } else if (dragIndex == 1) {
                editSat = (float) fraction;
            } else {
                // 明度压不到 0：全黑的装饰等于把背景关掉，而这一层本来就只是叠光，
                // 用户看到的会是「调到底就没了」，比留一点底色更像 bug。
                editBri = (float) Math.max(.08, fraction);
            }
            writeEditColor();
        }
    }

    private static double fractionAt(double mouseX, double left, double width) {
        double fraction = (mouseX - left) / Math.max(1.0, width);
        return Math.max(0.0, Math.min(1.0, fraction));
    }

    private void releaseDrag() {
        if (dragKind == KIND_FOLIA_SPEC) {
            FoliaBackgroundConfig.save();
        }
        int finishedKind = dragKind;
        dragKind = -1;
        dragIndex = -1;
        if (finishedKind == KIND_LYRIC_OFFSET) {
            // 偏移的落盘与 HudConfig 分开：它记在每首歌的记忆表里，跟样式设置不是一回事。
            CloudMusic.commitLyricOffset();
        }
        if (pendingSave) {
            pendingSave = false;
            HudConfig.save();
        }
    }

    /** 把正在编辑的那一槽颜色读成 HSB。只有颜色真的变了才重算，否则拖色相时会被自己写回来的值抖掉。 */
    private void syncEditColor() {
        if (editSlot < 0 || editSlot >= HudConfig.CADENZA_PALETTE_SLOTS) {
            editSlot = 0;
            editRgbCache = -1;
        }
        int rgb = HudConfig.cadenzaSlotColor(editSlot);
        if (rgb == editRgbCache) {
            return;
        }
        editRgbCache = rgb;
        float[] hsb = Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
        editHue = hsb[0];
        editSat = hsb[1];
        editBri = hsb[2];
    }

    private void writeEditColor() {
        int rgb = Color.HSBtoRGB(editHue, editSat, editBri) & 0xFFFFFF;
        editRgbCache = rgb;
        HudConfig.setCadenzaSlotColor(editSlot, rgb);
        pendingSave = true;
    }

    private void renderButton(double mouseX, double mouseY, float alpha) {
        boolean hover = isInside(mouseX, mouseY, anchorX, anchorY, BUTTON_SIZE, BUTTON_SIZE);
        if (hover) {
            CursorUtils.setOverride(CursorUtils.HAND);
        }

        float backgroundAlpha = alpha * (expanded ? .34f : (hover ? .26f : .16f));
        roundedRect(anchorX, anchorY, BUTTON_SIZE, BUTTON_SIZE, 7, hexColor(1f, 1f, 1f, backgroundAlpha));

        String label = "A-Z";
        double textX = anchorX + (BUTTON_SIZE - FontManager.pf12bold.getStringWidthD(label)) * .5;
        double textY = anchorY + (BUTTON_SIZE - FontManager.pf12bold.getFontHeight()) * .5;
        FontManager.pf12bold.drawString(label, textX, textY,
                hexColor(1f, 1f, 1f, alpha * (hover || expanded ? 1f : .82f)));
    }

    private void renderPanel(double mouseX, double mouseY, float alpha) {
        if (expandAnimation <= .01f) {
            return;
        }

        double eased = Easing.EASE_IN_OUT_QUAD.getFunction().apply((double) expandAnimation);
        float panelAlpha = (float) (alpha * eased);
        double panelTop = panelTop();
        double height = panelHeight();
        // 画的是显示尺寸而不是目标尺寸：切换样式 / 分列时面板是「长过去」的，边框和内容一起动。
        double width = Math.max(PANEL_WIDTH, shownWidth);

        api.getGLStateManager().pushMatrix();
        // 从按钮左上角展开：只放大不平移，收起时看起来是被按钮"吸"回去的。
        // uiScale 是「装不下就整体缩小」，展开动画那一档只在动画期间偏离 1。
        scaleAtPos(anchorX, panelTop, uiScale * (.90 + .10 * eased));
        try {
            roundedRect(anchorX, panelTop, width, height, 10, hexColor(.06f, .07f, .09f, panelAlpha * .88f));
            roundedOutline(anchorX, panelTop, width, height, 10, .8,
                    new java.awt.Color(1f, 1f, 1f, Math.max(0f, Math.min(1f, panelAlpha * .16f))));

            FontManager.pf14bold.drawString("歌词显示", anchorX + PANEL_PADDING,
                    panelTop + PANEL_PADDING, hexColor(1f, 1f, 1f, panelAlpha));

            renderColumnDividers(panelTop, width, height, panelAlpha);

            double top = panelTop();
            double bottom = top + height;
            double contentBottom = bottom - PANEL_PADDING;
            double panelRight = anchorX + width;
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                if (row.y + row.height < top || row.y > bottom) continue;

                float rowAlpha = panelAlpha * row.alpha;
                // 面板还在变高时露头的行按「离内容区下沿多远」渐显，免得被硬切一刀；
                // 分列时右侧那一列也是同样处理，面板还没长宽过去就先淡着。
                double overflow = row.y + row.height - contentBottom;
                if (overflow > 0) {
                    rowAlpha *= (float) Math.max(0.0, 1.0 - overflow / 18.0);
                }
                double rightOverflow = row.x + PANEL_PADDING + trackWidth() - panelRight;
                if (rightOverflow > 0) {
                    rowAlpha *= (float) Math.max(0.0, 1.0 - rightOverflow / 12.0);
                }
                if (rowAlpha <= .004f) continue;

                // 淡出中的行把鼠标坐标推到无穷远：不画 hover 高亮、也不出手型光标，
                // 免得「看着已经没了却还有反应」。
                boolean live = row.alpha > .5f;
                renderRow(row, live ? mouseX : Double.NEGATIVE_INFINITY,
                        live ? mouseY : Double.NEGATIVE_INFINITY, rowAlpha);
            }
        } finally {
            api.getGLStateManager().popMatrix();
        }
    }

    /**
     * 分列时在两列之间画一道很淡的竖线。
     *
     * <p>没有这道线，两列的行会被读成同一条纵向流（尤其列首是两条滑块时），加一条线就能让人看出
     * 「左列读到底，再读右列」。</p>
     *
     * <p>分列的过渡动画期间宽度是从一列长到两列的，所以这条线按「面板右边缘越过它的程度」淡入，
     * 不然会在某一帧凭空冒出来。</p>
     */
    private void renderColumnDividers(double panelTop, double width, double height, float panelAlpha) {
        double contentTop = panelTop + PANEL_PADDING + TITLE_HEIGHT;
        double contentBottom = panelTop + height - PANEL_PADDING;
        double dividerHeight = Math.max(0.0, contentBottom - contentTop);
        if (dividerHeight <= 0) return;
        for (int column = 1; column * (PANEL_WIDTH + COLUMN_GAP) < width + COLUMN_GAP; column++) {
            double x = anchorX + column * (PANEL_WIDTH + COLUMN_GAP) - COLUMN_GAP * .5;
            double fade = Math.max(0.0, Math.min(1.0, (width - x) / (COLUMN_GAP * .5)));
            if (fade <= .01) continue;
            roundedRect(x - .5, contentTop, 1.0, dividerHeight, 0,
                    hexColor(1f, 1f, 1f, (float) (panelAlpha * .09f * fade)));
        }
    }

    // ──────────────────────────── Folia 参数行 ────────────────────────────

    /**
     * 画一条 Folia 参数行。
     *
     * <p>四种控件：滑杆拖动、开关翻转、枚举左右循环、动作点击。这里只负责画，
     * 读写与枚举语义全在 {@code FoliaBackgroundOptions}。</p>
     */
    private void renderFoliaSpec(Row row, FoliaBackgroundOptions.Spec spec,
                                 double mouseX, double mouseY, float alpha) {
        double left = rowLeft(row);
        if (spec.kind == FoliaBackgroundOptions.KIND_SECTION) {
            FontManager.pf12bold.drawString(spec.label, left, row.y + 3,
                    hexColor(1f, 1f, 1f, alpha * .52f));
            return;
        }
        if (spec.kind == FoliaBackgroundOptions.KIND_SLIDER) {
            double width = trackWidth();
            double trackY = row.y + 16;
            float value = FoliaBackgroundOptions.readFloat(spec.id);
            String text = FoliaBackgroundOptions.formatValue(spec, value);
            FontManager.pf12bold.drawString(spec.label, left, row.y,
                    hexColor(1f, 1f, 1f, alpha * .82f));
            FontManager.pf12bold.drawString(text,
                    left + width - FontManager.pf12bold.getStringWidthD(text), row.y,
                    hexColor(1f, 1f, 1f, alpha * .62f));
            double fraction = (value - spec.min) / Math.max(1.0e-4, spec.max - spec.min);
            boolean hover = (dragKind == KIND_FOLIA_SPEC && dragIndex == row.index)
                    || isInside(mouseX, mouseY, left, trackY - 6, width, TRACK_HEIGHT + 12);
            renderTrack(left, trackY, width, fraction, hover, alpha);
            return;
        }
        if (spec.kind == FoliaBackgroundOptions.KIND_TOGGLE) {
            renderPickRow(row, spec.label,
                    FoliaBackgroundOptions.readBoolean(spec.id) ? "开" : "关",
                    mouseX, mouseY, alpha);
            return;
        }
        if (spec.kind == FoliaBackgroundOptions.KIND_PICK) {
            renderPickRow(row, spec.label, FoliaBackgroundOptions.readOption(spec),
                    mouseX, mouseY, alpha);
            return;
        }
        renderPickRow(row, spec.label, FoliaBackgroundOptions.actionValue(spec),
                mouseX, mouseY, alpha);
    }

    /** 点击一条 Folia 参数行。 */
    private void handleFoliaSpec(FoliaBackgroundOptions.Spec spec, boolean right) {
        switch (spec.kind) {
            case FoliaBackgroundOptions.KIND_SLIDER:
                dragKind = KIND_FOLIA_SPEC;
                dragIndex = foliaSpecs().indexOf(spec);
                return;
            case FoliaBackgroundOptions.KIND_TOGGLE:
                if (right) return;
                FoliaBackgroundOptions.writeBoolean(spec.id,
                        !FoliaBackgroundOptions.readBoolean(spec.id));
                FoliaBackgroundConfig.save();
                return;
            case FoliaBackgroundOptions.KIND_PICK:
                FoliaBackgroundOptions.cyclePick(spec, right);
                FoliaBackgroundConfig.save();
                return;
            case FoliaBackgroundOptions.KIND_ACTION:
                if (right) return;
                FoliaBackgroundOptions.runAction(spec.id);
                FoliaBackgroundConfig.save();
                return;
            default:
        }
    }

    private void renderRow(Row row, double mouseX, double mouseY, float panelAlpha) {
        if (row.kind == KIND_BACKGROUND_KIND) {
            int kind = FoliaBackgroundOptions.currentKind();
            renderPickRow(row, "背景", FoliaBackgroundOptions.kindLabel(kind),
                    mouseX, mouseY, panelAlpha);
            return;
        }
        if (row.kind == KIND_FOLIA_SPEC) {
            List<FoliaBackgroundOptions.Spec> specs = foliaSpecs();
            if (row.index >= 0 && row.index < specs.size()) {
                renderFoliaSpec(row, specs.get(row.index), mouseX, mouseY, panelAlpha);
            }
            return;
        }

        if (row.kind == KIND_EDGE_BLUR) {
            renderPickRow(row, "未到行模糊", HudConfig.fullscreenLyricEdgeBlur ? "开" : "关",
                    mouseX, mouseY, panelAlpha);
            return;
        }
        if (row.kind == KIND_STYLE) {
            renderPickRow(row, "歌词样式", FullscreenLyricStyle.current().getDisplayName(),
                    mouseX, mouseY, panelAlpha);
            return;
        }
        if (row.kind == KIND_BACKGROUND) {
            renderPickRow(row, "流体模式",
                    HudConfig.fullscreenLyricFluidStatic ? "静态" : "动态",
                    mouseX, mouseY, panelAlpha);
            return;
        }
        if (row.kind == KIND_THEME_SOURCE) {
            renderPickRow(row, "主题配色", ThemeColorSource.current().getDisplayName(),
                    mouseX, mouseY, panelAlpha);
            return;
        }
        if (row.kind == KIND_SLIDER) {
            renderSettingRow(row, SETTINGS[row.index], mouseX, mouseY, panelAlpha);
            return;
        }
        if (row.kind == KIND_STYLE_SLIDER) {
            HudSetting[] sliders = styleSettings();
            if (row.index >= 0 && row.index < sliders.length) {
                renderSettingRow(row, sliders[row.index], mouseX, mouseY, panelAlpha);
            }
            return;
        }
        if (row.kind == KIND_STYLE_TOGGLE) {
            int[] toggles = styleToggles();
            if (row.index >= 0 && row.index < toggles.length) {
                int id = toggles[row.index];
                renderPickRow(row, toggleLabel(id), toggleValue(id), mouseX, mouseY, panelAlpha);
            }
            return;
        }
        if (row.kind == KIND_FLUID_STYLE) {
            int style = displayIndexForStyle(HudConfig.fullscreenLyricFluidStyle);
            renderPickRow(row, "流体算法", FLUID_STYLE_NAMES[style],
                    mouseX, mouseY, panelAlpha);
            return;
        }
        if (row.kind == KIND_FLUID_SLIDER) {
            renderFluidSettingRow(row, mouseX, mouseY, panelAlpha);
            return;
        }
        if (row.kind == KIND_LAYER_TOGGLE) {
            renderPickRow(row, LAYER_OPACITY_LABELS[row.index],
                    layerEnabled(row.index) ? "开" : "关", mouseX, mouseY, panelAlpha);
            return;
        }
        if (row.kind == KIND_LAYER_OPACITY) {
            renderLayerOpacityRow(row, mouseX, mouseY, panelAlpha);
            return;
        }
        if (row.kind == KIND_SECTION) {
            String title;
            if (row.index == 1) {
                title = FullscreenLyricStyle.current().getDisplayName() + " 参数";
            } else if (row.index == 2) {
                title = "歌词同步";
            } else if (row.index == 3) {
                title = "流体背景";
            } else if (row.index == 5) {
                title = "背景层叠";
            } else {
                title = "背景配色";
            }
            FontManager.pf12bold.drawString(title, rowLeft(row), row.y + 3,
                    hexColor(1f, 1f, 1f, panelAlpha * .52f));
            return;
        }
        if (row.kind == KIND_LYRIC_SOURCE) {
            renderPickRow(row, "歌词来源", lyricSourceValue(), mouseX, mouseY, panelAlpha);
            return;
        }
        if (row.kind == KIND_LYRIC_SEARCH) {
            // 右侧的值要跟着整首歌走，但一行放不下「歌手 - 歌名」那么长，所以按标签之后的余量裁。
            String value = lyricPinValue();
            double room = Math.max(24, trackWidth() - 12
                    - FontManager.pf12bold.getStringWidthD("手动选词"));
            renderPickRow(row, "手动选词", FontManager.pf12bold.trim(value, room),
                    mouseX, mouseY, panelAlpha);
            return;
        }
        if (row.kind == KIND_LYRIC_RELOAD) {
            renderPickRow(row, "重新匹配", "再取一次", mouseX, mouseY, panelAlpha);
            return;
        }
        if (row.kind == KIND_LYRIC_OFFSET) {
            renderLyricOffsetRow(row, mouseX, mouseY, panelAlpha);
            return;
        }
        if (row.kind == KIND_DECOR_MODE) {
            String value = HudConfig.cadenzaDecorColorMode == 1 ? "自定义" : "跟随主题";
            renderPickRow(row, "元素取色", value, mouseX, mouseY, panelAlpha);
            return;
        }
        if (row.kind == KIND_BEATS) {
            renderBeatsRow(row, mouseX, mouseY, panelAlpha);
            return;
        }
        if (row.kind == KIND_COLOR_COUNT) {
            renderPickRow(row, "颜色数量", HudConfig.cadenzaColorCount() + " 色",
                    mouseX, mouseY, panelAlpha);
            return;
        }
        if (row.kind == KIND_SWATCHES) {
            renderSwatchRow(row, mouseX, mouseY, panelAlpha);
            return;
        }
        if (row.kind == KIND_HSB) {
            renderHsbRow(row, mouseX, mouseY, panelAlpha);
        }
    }

    /** 整行按钮：左边标签、右边当前值，点一下换下一个。 */
    private void renderPickRow(Row row, String label, String value,
                               double mouseX, double mouseY, float panelAlpha) {
        double left = rowLeft(row);
        double width = trackWidth();
        double barHeight = row.height - 4;
        boolean hover = isInside(mouseX, mouseY, left, row.y, width, barHeight);
        if (hover) {
            CursorUtils.setOverride(CursorUtils.HAND);
        }

        roundedRect(left, row.y, width, barHeight, 6,
                hexColor(1f, 1f, 1f, panelAlpha * (hover ? .16f : .08f)));

        double textY = row.y + (barHeight - FontManager.pf12bold.getFontHeight()) * .5;
        FontManager.pf12bold.drawString(label, left + 6, textY,
                hexColor(1f, 1f, 1f, panelAlpha * .82f));

        int accent = NCMScreen.getColor(NCMScreen.ColorType.ACCENT);
        FontManager.pf12bold.drawString(value,
                left + width - 6 - FontManager.pf12bold.getStringWidthD(value), textY,
                reAlpha(accent, panelAlpha));
    }

    /**
     * 「歌词来源」那一行右侧的文本。
     *
     * <p>自动档下把<b>实际生效</b>的源也带上（{@code 自动 · AMLL}）：自动匹配错的时候，用户第一件
     * 想知道的事就是「这份歌词到底是从哪来的」——不知道是哪个源给的，也就无从知道该换到哪一档。
     * 而「换个源到底有没有生效」也正是这一行最需要给回执的地方。</p>
     *
     * <p>指定了来源、但那个源这次没货（加载链已回落）时标出「未命中」。否则界面会写着「AMLL」，
     * 屏幕上的歌词却来自别处，用户只会再来一次「点了没反应」。</p>
     */
    private String lyricSourceValue() {
        int pinned = CloudMusic.getLyricSource();
        int served = CloudMusic.getServedLyricSource();
        String pinnedName = LyricSourceMemory.displayName(pinned);
        if (pinned != LyricSourceMemory.AUTO) {
            if (served < 0 || served == pinned) return pinnedName;
            return pinnedName + " · 未命中";
        }
        return served < 0 ? pinnedName : pinnedName + " · " + LyricSourceMemory.displayName(served);
    }

    /**
     * 「手动选词」那一行右侧的文本。
     *
     * <p>钉过之后写的是<b>钉住了哪一条</b>（{@code 源 · 歌手 - 歌名}），不是「已钉住」四个字：
     * 这一行存在的意义就是让人确认自己挑的那条生效了没有，只报状态等于没回答。没钉过给
     * 「搜索匹配…」，它读起来是一个动作，正好是左键要做的事。</p>
     */
    private String lyricPinValue() {
        LyricManualMemory.Pin pin = CloudMusic.getPinnedLyric();
        if (pin == null) return "搜索匹配…";
        return pin.source.label + " · " + pin.describe();
    }

    private void renderSettingRow(Row row, HudSetting setting,
                                  double mouseX, double mouseY, float panelAlpha) {
        double left = rowLeft(row);
        double width = trackWidth();
        double trackY = row.y + 16;

        FontManager.pf12bold.drawString(setting.getLabel(), left, row.y,
                hexColor(1f, 1f, 1f, panelAlpha * .82f));

        String value = String.format("%.2f", setting.getValue());
        FontManager.pf12bold.drawString(value,
                left + width - FontManager.pf12bold.getStringWidthD(value), row.y,
                hexColor(1f, 1f, 1f, panelAlpha * .62f));

        double range = Math.max(1.0e-4, setting.getMax() - setting.getMin());
        double fraction = Math.max(0.0, Math.min(1.0, (setting.getValue() - setting.getMin()) / range));
        boolean hover = (dragKind == row.kind && dragIndex == row.index)
                || isInside(mouseX, mouseY, left, trackY - 6, width, TRACK_HEIGHT + 12);
        renderTrack(left, trackY, width, fraction, hover, panelAlpha);
    }

    private void renderFluidSettingRow(Row row, double mouseX, double mouseY, float panelAlpha) {
        if (row.index < 0 || row.index >= FLUID_SLIDER_LABELS.length) return;
        double left = rowLeft(row);
        double width = trackWidth();
        double trackY = row.y + 16;
        float value = fluidSliderValue(row.index);
        float min = FLUID_SLIDER_MIN[row.index];
        float max = FLUID_SLIDER_MAX[row.index];

        FontManager.pf12bold.drawString(FLUID_SLIDER_LABELS[row.index], left, row.y,
                hexColor(1f, 1f, 1f, panelAlpha * .82f));
        String valueText = fluidSliderText(row.index, value);
        FontManager.pf12bold.drawString(valueText,
                left + width - FontManager.pf12bold.getStringWidthD(valueText), row.y,
                hexColor(1f, 1f, 1f, panelAlpha * .62f));

        double fraction = Math.max(0.0, Math.min(1.0, (value - min) / Math.max(1.0e-4, max - min)));
        boolean hover = (dragKind == row.kind && dragIndex == row.index)
                || isInside(mouseX, mouseY, left, trackY - 6, width, TRACK_HEIGHT + 12);
        renderTrack(left, trackY, width, fraction, hover, panelAlpha);
    }

    /**
     * 背景层叠的不透明度行：与 {@link #renderFluidSettingRow} 同一款（label 左、百分比右、
     * renderTrack），只是数值来自位掩码层各自的字段，范围固定 0-100。
     */
    private void renderLayerOpacityRow(Row row, double mouseX, double mouseY, float panelAlpha) {
        if (row.index < 0 || row.index >= LAYER_OPACITY_LABELS.length) return;
        double left = rowLeft(row);
        double width = trackWidth();
        double trackY = row.y + 16;
        float value = layerOpacityValue(row.index);
        float min = LAYER_OPACITY_MIN[row.index];
        float max = LAYER_OPACITY_MAX[row.index];

        FontManager.pf12bold.drawString(LAYER_OPACITY_LABELS[row.index], left, row.y,
                hexColor(1f, 1f, 1f, panelAlpha * .82f));
        String valueText = Math.round(value) + "%";
        FontManager.pf12bold.drawString(valueText,
                left + width - FontManager.pf12bold.getStringWidthD(valueText), row.y,
                hexColor(1f, 1f, 1f, panelAlpha * .62f));

        double fraction = Math.max(0.0, Math.min(1.0, (value - min) / Math.max(1.0e-4, max - min)));
        boolean hover = (dragKind == row.kind && dragIndex == row.index)
                || isInside(mouseX, mouseY, left, trackY - 6, width, TRACK_HEIGHT + 12);
        renderTrack(left, trackY, width, fraction, hover, panelAlpha);
    }

    private void renderBeatsRow(Row row, double mouseX, double mouseY, float panelAlpha) {
        double left = rowLeft(row);
        double width = trackWidth();
        double trackY = row.y + 16;

        FontManager.pf12bold.drawString("换色节拍", left, row.y,
                hexColor(1f, 1f, 1f, panelAlpha * .82f));

        String value = "每 " + HudConfig.cadenzaBeatsPerColor() + " 拍";
        FontManager.pf12bold.drawString(value,
                left + width - FontManager.pf12bold.getStringWidthD(value), row.y,
                hexColor(1f, 1f, 1f, panelAlpha * .62f));

        int span = HudConfig.CADENZA_BEATS_PER_COLOR_MAX - HudConfig.CADENZA_BEATS_PER_COLOR_MIN;
        double fraction = (HudConfig.cadenzaBeatsPerColor() - HudConfig.CADENZA_BEATS_PER_COLOR_MIN)
                / (double) Math.max(1, span);
        boolean hover = dragKind == KIND_BEATS
                || isInside(mouseX, mouseY, left, trackY - 6, width, TRACK_HEIGHT + 12);
        renderTrack(left, trackY, width, fraction, hover, panelAlpha);
    }

    /** 时间偏移行：轨道中点就是 0，往左歌词提前、往右歌词延后，右键归零。 */
    private void renderLyricOffsetRow(Row row, double mouseX, double mouseY, float panelAlpha) {
        double left = rowLeft(row);
        double width = trackWidth();
        double trackY = row.y + 16;

        FontManager.pf12bold.drawString("时间偏移", left, row.y,
                hexColor(1f, 1f, 1f, panelAlpha * .82f));

        long offset = CloudMusic.getLyricOffsetMillis();
        String value = offset == 0L ? "0 ms"
                : (offset > 0L ? "+" : "-") + Math.abs(offset) + " ms";
        FontManager.pf12bold.drawString(value,
                left + width - FontManager.pf12bold.getStringWidthD(value), row.y,
                hexColor(1f, 1f, 1f, panelAlpha * .62f));

        double fraction = (offset / (double) OFFSET_SPAN_MILLIS + 1.0) * .5;
        boolean hover = dragKind == KIND_LYRIC_OFFSET
                || isInside(mouseX, mouseY, left, trackY - 6, width, TRACK_HEIGHT + 12);
        renderTrack(left, trackY, width, Math.max(0.0, Math.min(1.0, fraction)), hover, panelAlpha);
    }

    private void renderTrack(double left, double trackY, double width, double fraction,
                             boolean hover, float panelAlpha) {
        if (hover) {
            CursorUtils.setOverride(CursorUtils.HAND);
        }
        roundedRect(left, trackY, width, TRACK_HEIGHT, TRACK_HEIGHT * .5,
                hexColor(1f, 1f, 1f, panelAlpha * .18f));
        double fillWidth = width * Math.max(0.0, Math.min(1.0, fraction));
        if (fillWidth > .5) {
            int accent = NCMScreen.getColor(NCMScreen.ColorType.ACCENT);
            roundedRect(left, trackY, fillWidth, TRACK_HEIGHT,
                    Math.min(TRACK_HEIGHT * .5, fillWidth * .5),
                    reAlpha(accent, panelAlpha));
        }
        double knobRadius = hover ? 5.0 : 4.0;
        roundedRect(left + fillWidth - knobRadius, trackY + TRACK_HEIGHT * .5 - knobRadius,
                knobRadius * 2, knobRadius * 2, knobRadius, hexColor(1f, 1f, 1f, panelAlpha));
    }

    /**
     * 色块行：左键选中要编辑的槽，右键把那一槽恢复出厂色。
     *
     * <p>只画前 {@code cadenzaColorCount()} 槽——没参与轮换的槽画出来会让人以为它也在生效。</p>
     */
    private void renderSwatchRow(Row row, double mouseX, double mouseY, float panelAlpha) {
        int count = HudConfig.cadenzaColorCount();
        double cellWidth = swatchWidth(count);
        double left = rowLeft(row);
        double y = row.y + (row.height - SWATCH_HEIGHT) * .5;
        for (int i = 0; i < count; i++) {
            double x = left + i * (cellWidth + SWATCH_GAP);
            boolean hover = isInside(mouseX, mouseY, x, y, cellWidth, SWATCH_HEIGHT);
            if (hover) {
                CursorUtils.setOverride(CursorUtils.HAND);
            }
            int rgb = HudConfig.cadenzaSlotColor(i) | 0xFF000000;
            roundedRect(x, y, cellWidth, SWATCH_HEIGHT, 5, reAlpha(rgb, panelAlpha));
            boolean selected = i == editSlot;
            if (selected || hover) {
                float outlineAlpha = panelAlpha * (selected ? .95f : .45f);
                roundedOutline(x - 1, y - 1, cellWidth + 2, SWATCH_HEIGHT + 2, 6,
                        selected ? 1.2 : .8,
                        new Color(1f, 1f, 1f, Math.max(0f, Math.min(1f, outlineAlpha))));
            }
        }
    }

    /**
     * 色相 / 饱和度 / 明度三条紧凑滑块。
     *
     * <p>轨道本身就是分段实色画出来的预览条，所以拖之前就能看出往哪边是什么颜色。刻意不用
     * {@code roundedRectGradient*}：那条路径会丢掉颜色的 alpha（见 {@code CadenzaDecorLayer}
     * 里关于 {@code rqg.frag} 的注释），而这里的轨道是要跟着面板一起淡入淡出的。</p>
     */
    private void renderHsbRow(Row row, double mouseX, double mouseY, float panelAlpha) {
        double left = hsbTrackLeft(row);
        double width = hsbTrackWidth();
        double trackY = row.y + (row.height - HSB_TRACK_HEIGHT) * .5;

        FontManager.pf12bold.drawString(HSB_LABELS[row.index], rowLeft(row),
                row.y + (row.height - FontManager.pf12bold.getFontHeight()) * .5,
                hexColor(1f, 1f, 1f, panelAlpha * .62f));

        int segments = row.index == 0 ? HUE_SEGMENTS : RAMP_SEGMENTS;
        double segmentWidth = width / segments;
        for (int i = 0; i < segments; i++) {
            double t = segments == 1 ? 0 : i / (double) (segments - 1);
            int rgb;
            if (row.index == 0) {
                // 色相条固定用较高的饱和度与明度，否则调暗之后整条彩虹会看不出区别。
                rgb = Color.HSBtoRGB((float) t, Math.max(.45f, editSat), Math.max(.65f, editBri));
            } else if (row.index == 1) {
                rgb = Color.HSBtoRGB(editHue, (float) t, Math.max(.35f, editBri));
            } else {
                rgb = Color.HSBtoRGB(editHue, editSat, (float) t);
            }
            double x = left + i * segmentWidth;
            // 段与段之间多铺半像素，避免缩放后出现细缝。
            roundedRect(x, trackY, segmentWidth + .5, HSB_TRACK_HEIGHT, 0,
                    reAlpha(rgb | 0xFF000000, panelAlpha * .92f));
        }
        roundedOutline(left, trackY, width, HSB_TRACK_HEIGHT, HSB_TRACK_HEIGHT * .5, .8,
                new Color(1f, 1f, 1f, Math.max(0f, Math.min(1f, panelAlpha * .22f))));

        double fraction = row.index == 0 ? editHue : (row.index == 1 ? editSat : editBri);
        boolean hover = (dragKind == KIND_HSB && dragIndex == row.index)
                || isInside(mouseX, mouseY, left, trackY - 5, width, HSB_TRACK_HEIGHT + 10);
        if (hover) {
            CursorUtils.setOverride(CursorUtils.HAND);
        }
        double knobRadius = hover ? 4.5 : 3.6;
        double knobX = left + width * Math.max(0.0, Math.min(1.0, fraction));
        roundedRect(knobX - knobRadius, trackY + HSB_TRACK_HEIGHT * .5 - knobRadius,
                knobRadius * 2, knobRadius * 2, knobRadius, hexColor(1f, 1f, 1f, panelAlpha));
    }

    /** 鼠标横坐标落在第几个色块上；不在任何色块上返回 -1。 */
    private int swatchAt(double mouseX, Row row) {
        int count = HudConfig.cadenzaColorCount();
        double cellWidth = swatchWidth(count);
        double left = rowLeft(row);
        for (int i = 0; i < count; i++) {
            double x = left + i * (cellWidth + SWATCH_GAP);
            if (mouseX >= x && mouseX <= x + cellWidth) return i;
        }
        return -1;
    }

    private double swatchWidth(int count) {
        int safeCount = Math.max(1, count);
        return (trackWidth() - SWATCH_GAP * (safeCount - 1)) / safeCount;
    }

    private double panelTop() {
        return anchorY + BUTTON_SIZE + 6;
    }

    /**
     * 面板完整内容高度（= 最高那一列的高度 + 标题 + 上下内边距）。
     *
     * <p>取的是<b>显示高度</b>（{@link #followLayout()} 平滑过的那一份）而不是目标值：面板正在为增删
     * 行改变大小时，上下的滚动余量要跟着边框一起变，不然滚动范围会在动画期间和看到的框对不上。</p>
     */
    private double contentHeight() {
        return shownHeight > 0 ? shownHeight : PANEL_PADDING * 2 + TITLE_HEIGHT;
    }

    /** 当前可见高度；内容比视口高时截到视口底部，其余通过内部滚动查看。 */
    private double panelHeight() {
        double content = contentHeight();
        if (Double.isNaN(panelSpaceBottom)) return content;
        double available = Math.max(0.0, panelSpaceBottom - panelTop());
        return Math.min(content, available / Math.max(.01, uiScale));
    }

    /** 一行控件的左边缘 = 它所在列的左边缘 + 面板内边距。 */
    private double rowLeft(Row row) {
        return row.x + PANEL_PADDING;
    }

    private double trackWidth() {
        return PANEL_WIDTH - PANEL_PADDING * 2;
    }

    private double hsbTrackLeft(Row row) {
        return rowLeft(row) + HSB_LABEL_WIDTH;
    }

    private double hsbTrackWidth() {
        return trackWidth() - HSB_LABEL_WIDTH;
    }

    private boolean isInside(double mouseX, double mouseY, double x, double y, double width, double height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }
}












