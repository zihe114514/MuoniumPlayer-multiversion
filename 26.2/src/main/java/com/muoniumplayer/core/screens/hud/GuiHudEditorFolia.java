package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.compat.mc.Gui;
import com.muoniumplayer.compat.mc.GuiScreen;
import com.muoniumplayer.compat.mc.GuiTextField;
import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.compat.gl.GL11;

import com.muoniumplayer.core.MuoniumPlayerExtension;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.FoliaLucideIcons;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.HudSettingsIcons;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.font.GlyphInkMetrics;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.screens.ncm.WindowAdaptiveScale;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.settings.HudSetting;
import com.muoniumplayer.core.widget.impl.MusicInfoWidget;
import com.muoniumplayer.core.widget.impl.MusicLyricsWidget;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import com.muoniumplayer.render.skija.SkijaClip;
import com.muoniumplayer.render.skija.SkijaEffects;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaHost;
import com.muoniumplayer.render.skija.SkijaShapes;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Point;
import io.github.humbleui.types.RRect;

/**
 * HUD 设置的 Folia 样式界面。
 *
 * <p>视觉与交互规格移植自 本项目 的设置弹窗（作者同为本模组开发成员，许可上无障碍）。
 * 图标不是仿制：直接内联 lucide 1.31.0（folia 锁的同一版）的 path 数据，见
 * {@link com.muoniumplayer.core.rendering.FoliaLucideIcons}，所以与它渲染的是同一套几何。
 * 对应关系：
 * 遮罩淡入 + 面板 {@code y+20 / scale 0.98} 上浮入场（240ms easeOut）、居中大窗
 * （900 × 85vh、24px 圆角、不透明 {@code #18181B} 底、shadow-2xl 多层软阴影）、
 * 两枚按主题强调色染的径向光斑（裁进面板圆角）、左侧「分组标签 → 分区 → 锚点 TOC」双级目录
 * （选中分区下方展开 TOC，2px 强调色指示条按透明度切换）、内容列的锚点分组卡片
 * （12px 圆角、white/5 表面层）、切分区时整页从 ±60px 横向滑入、平滑滚动 + scrollspy
 * （24% 判定线、落点上方 12px、点击后 800ms 锁）、CHOICE 的下拉菜单（∓8px / 0.96 / 150ms）、
 * 以及「按下与松开都落在遮罩上才关闭」的关闭判定与 200ms 退场动画。</p>
 *
 * <p>与经典（{@link GuiHudEditor}）、现代（{@link GuiHudEditorPro}）共用
 * {@link HudSettingsModel} 的条目声明，三套界面永不少同一项。配色按用户选定的策略接模组主题：
 * 面板表面层保持 folia 的 zinc 深灰 + 白色半透明层，强调色（指示条 / 开关 / 选中描边 / 光斑 /
 * 滑块填充）取 {@link NCMTheme} 当前色，文字色取主题的主要/次要文字。</p>
 *
 * <p>沿用 Pro 版定下的三条工程约束：渲染与命中测试共用同一份 {@link Row} 行表；面板矩形只能从
 * {@link #panelBounds()} 拿；动画只改颜色 / 透明度 / 偏移，不改命中矩形（滚动与切页位移被折进
 * {@link #displayScroll} 与绘制矩阵，绘制与命中读同一个值）。</p>
 */
public class GuiHudEditorFolia extends GuiScreen {

    // ==================== 面板几何（全部是 CSS px，浏览器实测值） ====================
    //
    // 这一节的每个数字都在 .scratch/folia_ref/settings_ref.html 里量过：那份页面用的是
    // folia 源码逐字抄下来的 className + 真 Tailwind v4，getBoundingClientRect 给什么就是什么。
    // 靠读类名推出来的东西错过三处（面板 900 其实被 max-w-4xl 夹成 896、text-2xl 的行高是 32
    // 不是 40、侧栏按钮实测 50 而不是 12+20+12=44），所以以后改这里请连那份参照页一起看。

    /** Tailwind 的 md 断点：宽屏才有左栏 + 32 内衬那一套。 */
    private static final int BREAKPOINT_MD = 768;
    /** sm 断点：遮罩内缩从 16/32 变 20/48。 */
    private static final int BREAKPOINT_SM = 640;
    /** {@code md:w-[900px]} 再被 {@code md:max-w-4xl} 夹住 —— 实际生效的是 896。 */
    private static final int PANEL_MAX_W = 896;
    private static final int PANEL_RADIUS = 24;
    /**
     * 遮罩横向内缩：{@code px-4 sm:px-5}。纵向那一档（{@code py-8 sm:py-12}）用不上 ——
     * 面板写的是 {@code h-[90vh] md:h-[85vh]}，定高在交叉轴上不收缩，遮罩的内衬只会溢出，
     * 不会把面板压小。实测 785 高的视口下面板 667.25 = 85% 整。
     */
    private static final int MASK_PAD_X_NARROW = 16;
    private static final int MASK_PAD_X = 20;
    /** {@code p-6 md:p-8}。 */
    private static final int PAD = 32;
    private static final int PAD_NARROW = 24;
    /**
     * 面板那圈 {@code border} 是 1px，绝对定位与常规流的所有子元素都从**边框之内**起算 ——
     * 实测 tab 行、侧栏、内容列的顶端全是 {@code 1 + 32 = 33}。所以这一档并进 {@link #pad()}，
     * 一处改全站生效。
     */
    private static final int PANEL_BORDER = 1;
    /** {@code w-1/3 max-w-[264px]}，含右侧 12px 的 {@code pr-3}。 */
    private static final int SIDEBAR_W = 264;
    private static final int SIDEBAR_PR = 12;
    /** 侧栏容器自己的 {@code pb-4}。 */
    private static final int SIDEBAR_PB = 16;
    /** 主列 gap：{@code gap-4 md:gap-6}。 */
    private static final int SIDEBAR_GAP = 24;
    private static final int SIDEBAR_GAP_NARROW = 16;
    /** 内容列的 {@code pl-1 md:pl-2 pr-2 md:pr-4 pb-4}，滚动条再从右边占掉 8。 */
    private static final int CONTENT_PL = 8;
    private static final int CONTENT_PL_NARROW = 4;
    private static final int CONTENT_PR = 16;
    private static final int CONTENT_PR_NARROW = 8;
    private static final int CONTENT_PB = 16;
    private static final int SCROLLBAR_W = 8;
    /**
     * 分区按钮实测 50：{@code p-3}(12+12) + 内容行盒 24 + {@code border}(1+1)。
     *
     * <p>内容那一格不是 18 —— 图标是 inline SVG，和文字排在同一条行盒里，被 {@code leading} 顶到
     * 24（{@code text-sm} 本身只有 20）。图标按这一格的中心对齐，实测它比几何中心低 1.6px，
     * 那是浏览器排 inline 基线的余量，不是设计档位，不抄。</p>
     */
    private static final int NAV_CAT_H = 50;
    /** 同一分组内 label↔按钮、按钮↔按钮都是 {@code gap-1}。 */
    private static final int NAV_CAT_GAP = 4;
    /** 分组之间是 root 的 {@code gap-5}。 */
    private static final int NAV_GROUP_GAP = 20;
    /** 分组标签：text-xs(16 行高) + {@code pb-1}；字距 {@code tracking-widest} = .1em。 */
    private static final int NAV_LABEL_H = 20;
    private static final float NAV_LABEL_TRACKING = 0.10f;
    /** TOC 块靠自身 {@code mt-1} 顶开，条目之间 {@code gap-0.5}。 */
    private static final int NAV_TOC_MT = 4;
    private static final int NAV_TOC_GAP = 2;
    /** TOC 条目 {@code py-1.5 pl-3 pr-2 text-xs} → 6+16+6。 */
    private static final int NAV_TOC_H = 28;
    private static final int NAV_TOC_INDENT = 26;
    /** 强调条：{@code inset-y-1 left-0 w-[2px] rounded-full}。 */
    private static final int TOC_BAR_INSET = 4;
    private static final int TOC_BAR_W = 2;

    /** 顶部标签行：{@code text-2xl}(24 号 / **32 行高**) + {@code pb-2}(8) + 整块 {@code mb-6}(24)。 */
    private static final int TAB_LINE_H = 32;
    private static final int TAB_PB = 8;
    private static final int TAB_GAP = 24;
    private static final int TAB_MB = 24;
    private static final int TAB_UNDERLINE_H = 3;
    private static final int HEADER_BLOCK_H = TAB_LINE_H + TAB_PB + TAB_MB;
    /**
     * 关闭钮：{@code top-4 right-4} + {@code p-1}(4) 包一个 20px 的 X。
     *
     * <p>实测盒是 28×**32** 不是 28×28：X 是 inline SVG，落在按钮那条 24px 的行盒里，
     * 于是纵向多出 4px。{@code rounded-full} 在 28×32 上被夹成 14，是个竖着的胶囊。</p>
     */
    private static final int CLOSE_INSET = 16;
    private static final int CLOSE_ICON = 20;
    private static final int CLOSE_PAD = 4;
    private static final int CLOSE_BOX = CLOSE_ICON + CLOSE_PAD * 2;
    private static final int CLOSE_LINE_H = 24;
    private static final int CLOSE_H = CLOSE_PAD * 2 + CLOSE_LINE_H;
    /** 日/夜钮：{@code h-9 w-9 rounded-full} 包一枚 17px 的 Sun/Moon。 */
    private static final int DAYLIGHT_BOX = 36;
    private static final int DAYLIGHT_ICON = 17;

    /**
     * 分区头（{@code SettingsSectionHeader}）：标题 28 + 描述 {@code mt-1}(4)+16 +
     * {@code pb-3 md:pb-4} + 1px 线 + {@code mb-4 md:mb-6}。它在滚动容器**里面**，跟着内容一起滚走。
     */
    private static final int HEAD_TITLE_H = 28;
    private static final int HEAD_DESC_MT = 4;
    private static final int HEAD_DESC_H = 16;
    /** 分区标题行（{@code SettingsSectionHeading}）：text-sm(20 行高) + {@code mb-4}(16)。 */
    private static final int GROUP_HEADER_H = 36;
    private static final int GROUP_HEADING_ICON = 14;
    private static final int GROUP_HEADING_GAP = 8;
    private static final float GROUP_HEADING_TRACKING = 0.05f;
    /** 卡内文字行盒：标题 {@code text-sm}(14/20)，说明 {@code text-xs}(12/16) 与 {@code space-y-1}(4)。 */
    private static final int ROW_LABEL_H = 20;
    private static final int ROW_DESC_MT = 4;
    private static final int ROW_DESC_H = 16;
    /** 行与行之间那条 {@code border-t}：它属于后一行的盒子，是实打实多出来的 1px。 */
    private static final int ROW_DIVIDER = 1;
    /** 卡片的 {@code border} 把里面的行往内顶 1px（实测行左边缘在 {@code contentX + 1}）。 */
    private static final int CARD_BORDER = 1;
    /** 锚点之间：子视图根的 {@code space-y-6}。 */
    private static final int CARD_GAP = 24;
    /** 锚点内部各块之间：{@code space-y-3}。 */
    private static final int CARD_BLOCK_GAP = 12;
    /** 卡片内衬：一张内衬卡自己带 {@code p-4}(16)，里面的子块不再各自带内衬。 */
    private static final int CARD_PAD = 16;
    private static final int CARD_RADIUS = 12;
    /** 内衬卡里内容的起点：卡自己的 1px 边 + {@code p-4}(16)。实测标签块顶在卡顶之下 17px。 */
    private static final int PADDED_INSET = CARD_BORDER + CARD_PAD;
    /**
     * 内衬卡里子块与子块之间：{@code space-y-4}(16)。实测 408+56=464 与下一块 480 之间正是 16，
     * 与锚点之间那档 {@code space-y-3}(12) 不是一回事。
     */
    private static final int CARD_INNER_GAP = 16;
    /**
     * 两个「左标签右控件」的行之间再多出来的那一段：后一行自己带 {@code pt-4}(16) 加一条
     * {@code border-t}。实测 MotionReduction 那张卡里两行内容相距 56+16+1+16。
     */
    private static final int ROW_SEP_PT = 16;
    /** 说明文字一行 16，folia 是**换行**而不是省略号（{@code max-w-[360px]} / {@code [420px]}）。 */
    private static final int DESC_LINE_H = 16;
    private static final int DESC_MAX_W = 420;
    private static final int DESC_MAX_LINES = 2;
    /** 分段按钮 / 选项瓦片：{@code gap-2}(8)、{@code gap-3}(12)、{@code p-3}(12)。 */
    private static final int TILE_GAP = 8;
    private static final int TILE_GAP_WIDE = 12;
    private static final int TILE_PAD = 12;
    private static final int TILE_RADIUS = 8;
    /** 分段按钮：{@code px-3 py-2 rounded-lg text-xs font-semibold} → 8+16+8+2 = 34。 */
    private static final int SEGMENT_H = 34;
    /** 选项瓦片：{@code px-3 py-3} + 标题 20 + {@code mt-1}(4) + 说明 16 → 66。 */
    private static final int TILE_H = 66;
    /** 大瓦片里标题与说明各自的行盒：{@code text-base/24} 与 {@code text-xs/20}。 */
    private static final int TILE_TITLE_H = 24;
    private static final int TILE_DESC_H = 20;
    /** 大瓦片圆角是 {@code rounded-xl}(12)，分段条那档才是 {@code rounded-lg}(8)。 */
    private static final int TILE_RADIUS_RICH = 12;
    /** 「使用中」角标：实测 18 高、{@code rounded-full}、左右内衬 6，贴在瓦片右上角内 12。 */
    private static final String USE_CHIP_TEXT = "使用中";
    private static final int USE_CHIP_H = 18;
    private static final int USE_CHIP_PAD_X = 6;
    private static final int USE_CHIP_BG_DARK = 0x1AFFFFFF;
    private static final int USE_CHIP_BG_LIGHT = 0x14000000;
    private int useChipBg() { return daylight() ? USE_CHIP_BG_LIGHT : USE_CHIP_BG_DARK; }
    /** 主题预设那一格：{@code p-3} + 24 圆点 + {@code gap-2}(8) + 标签 16 + 上下边框 → 74。 */
    private static final int THEME_TILE_H = 74;
    private static final int THEME_DOT = 24;
    /** 滑条那一格：{@code space-y-3} 里标题行 20 + 12 + 轨道 16。 */
    private static final int SLIDER_HEAD_GAP = 12;

    /**
     * 原生滑条：folia 用的是 {@code input[type=range]} 加 {@code accent-current}，不是自绘控件。
     *
     * <p>尺寸是在参照页上按 4 倍缩放逐像素量出来的：盒子铺满、高 16；轨道是一条 8px 高的胶囊
     * （6px 色带 + 上下各 1px 的 {@code #ACACAC} 描边）；已过半程是强调色往白上混出来的浅色，
     * 未过是 {@code #3B3B3B}；旋钮 16px 实心圆，压在轨道正中，左右各留半个旋钮的行程。</p>
     */
    private static final int RANGE_H = 16;
    private static final int RANGE_TRACK_H = 8;
    private static final int RANGE_OUTLINE = 0xFFACACAC;
    private static final int RANGE_REST = 0xFF3B3B3B;
    /** 已过半程：实测 {@code #7B9CFF} 出来是 {@code #B3C6FF}，即往白上混 45%。 */
    private static final float RANGE_FILL_MIX = 0.45f;
    private static final int RANGE_THUMB = 16;
    /** 开关：{@code w-12 h-6 p-1} + {@code w-4 h-4} 旋钮 + {@code translate-x-6}。 */

    private static final int SWITCH_W = 48;
    private static final int SWITCH_H = 24;
    private static final int SWITCH_TRAVEL = 24;
    /** 色块 {@code h-10 w-10 rounded-xl}。 */
    private static final int SWATCH_W = 40;
    private static final int SWATCH_H = 40;
    private static final int SWATCH_RADIUS = 12;
    /** select 触发器 {@code px-4 py-3 text-sm border} → 12+20+12+2。 */
    private static final int PILL_H = 46;
    private static final int PILL_PAD_X = 16;
    private static final int PILL_CHEVRON = 16;

    /** 主题预设格：未选中 {@code border-white/10} + {@code bg-white/8}（内联样式，不是 class）。 */
    private static final int THEME_TILE_EDGE = 0x1AFFFFFF;
    private static final int THEME_TILE_REST = 0x14FFFFFF;

    /**
     * 取色浮层：外壳 {@code rounded-[24px] border border-white/10 p-4}，
     * 里面那块取色区是自己的 {@code rounded-[22px] border border-white/10 bg-black/10 p-3} 内衬。
     */
    private static final int PICKER_W = 244;
    private static final int PICKER_H = 256;
    private static final int PICKER_RADIUS = 24;
    private static final int PICKER_PAD = 16;
    private static final int PICKER_LINER_RADIUS = 22;
    private static final int PICKER_LINER_PAD = 12;
    /** 内衬的 {@code bg-black/10} 也是写死的黑，不随日/夜翻（源码如此）。 */
    private static final int LINER_BG = 0x1A000000;
    private static final int PICKER_INNER_W = PICKER_W - PICKER_PAD * 2;
    private static final int PICKER_BLOCK_GAP = 12;
    private static final int SV_W = PICKER_INNER_W - PICKER_LINER_PAD * 2;
    private static final int SV_H = 88;
    private static final int HUE_H = 10;
    /** HEX 输入：{@code rounded-2xl border border-white/10 bg-white/5 px-4 py-3 font-mono text-sm}。 */
    private static final int HEX_INPUT_W = 86;
    private static final int HEX_INPUT_H = 46;
    private static final int HEX_INPUT_RADIUS = 16;

    /** 菜单行 {@code px-3 py-2.5 text-sm} → 10+20+10，行之间 {@code space-y-0.5}。 */
    private static final int MENU_ITEM_H = 40;
    private static final int MENU_ITEM_GAP = 2;
    private static final int MENU_ITEM_PITCH = MENU_ITEM_H + MENU_ITEM_GAP;
    /** {@code p-1.5}：上下各 6。 */
    private static final int MENU_PAD = 6;
    private static final int MENU_ITEM_PAD_X = 12;
    private static final int MENU_CHECK = 14;
    private static final int MENU_MAX_H = 240;
    private static final int MENU_GAP = 4;
    private static final int MENU_GUTTER = 8;
    /** 下方连这个高度都放不下才翻到上方（folia 的 MIN_PREFERRED_HEIGHT）。 */
    private static final int MENU_MIN_PREFERRED_H = 160;
    /** 一格的滚轮量（LWJGL 给的 120）。 */
    private static final int WHEEL_NOTCH = 120;

    /**
     * folia 的统一外壳动效时长：进 240ms、出也 240ms —— 源码里 exit 用的是同一个
     * {@code shellTransition = {duration: 0.24, ease: 'easeOut'}}，并没有另配一个更短的退场。
     */
    private static final int INTRO_MS = 240;
    private static final int OUTRO_MS = 240;
    /** CSS 默认过渡时长（Tailwind v4 的 {@code --default-transition-duration}）。 */
    private static final int TRANSITION_MS = 150;
    /** select 箭头的 {@code transition-transform duration-200}。 */
    private static final int CHEVRON_MS = 200;
    /** 下拉菜单的 {@code {duration: 0.15, ease: 'easeOut'}}。 */
    private static final int MENU_MS = 150;
    private static final long CLICK_LOCK_MS = 800L;
    private static final double ACTIVE_LINE_RATIO = 0.24;
    /** 落到一个锚点时上方留的空白（源码里的 {@code SCROLL_TOP_PADDING}）。 */
    private static final int SCROLL_TOP_PADDING = 12;

    // ==================== 表面色（folia 暗色档；强调与文字接模组主题） ====================

    /** 面板底：folia 硬编码 #18181b / 白，**不跟随主题**（§6 特别点出的那一处）（日/夜两档，走 {@link #daylight()}）。 */
    private static final int PANEL_BG_DARK = 0xFF18181B;
    private static final int PANEL_BG_LIGHT = 0xFFFFFFFF;
    private int panelBg() { return daylight() ? PANEL_BG_LIGHT : PANEL_BG_DARK; }

    /** 面板边 white/10 · black/5（日/夜两档，走 {@link #daylight()}）。 */
    private static final int PANEL_EDGE_DARK = 0x1AFFFFFF;
    private static final int PANEL_EDGE_LIGHT = 0x0D000000;
    private int panelEdge() { return daylight() ? PANEL_EDGE_LIGHT : PANEL_EDGE_DARK; }

    /** 卡片底 white/5 · black/2.5%（日/夜两档，走 {@link #daylight()}）。 */
    private static final int CARD_BG_DARK = 0x0DFFFFFF;
    private static final int CARD_BG_LIGHT = 0x06000000;
    private int cardBg() { return daylight() ? CARD_BG_LIGHT : CARD_BG_DARK; }

    /** 卡片边 white/5 · black/10（日/夜两档，走 {@link #daylight()}）。 */
    private static final int CARD_EDGE_DARK = 0x0DFFFFFF;
    private static final int CARD_EDGE_LIGHT = 0x1A000000;
    private int cardEdge() { return daylight() ? CARD_EDGE_LIGHT : CARD_EDGE_DARK; }

    /** 卡片 hover 底：folia 在这些独立行卡上写的是 {@code hover:bg-white/8}。 */
    private static final int CARD_HOVER_DARK = 0x14FFFFFF;
    private static final int CARD_HOVER_LIGHT = 0x0D000000;
    private int cardHover() { return daylight() ? CARD_HOVER_LIGHT : CARD_HOVER_DARK; }

    /** 图标底板 {@code settingsIconClass}：{@code p-2 rounded-lg bg-white/8 border-white/10}，整块 opacity-60。 */
    private static final int ICON_CHIP_BG_DARK = 0x14FFFFFF;
    private static final int ICON_CHIP_BG_LIGHT = 0x0A000000;
    private int iconChipBg() { return daylight() ? ICON_CHIP_BG_LIGHT : ICON_CHIP_BG_DARK; }

    private static final int ICON_CHIP_EDGE_DARK = 0x1AFFFFFF;
    private static final int ICON_CHIP_EDGE_LIGHT = 0x1A000000;
    private int iconChipEdge() { return daylight() ? ICON_CHIP_EDGE_LIGHT : ICON_CHIP_EDGE_DARK; }

    /** 「会去做一件事」那一行：底板 {@code p-2}(8+8) 包一枚 16 的图标。 */
    private static final int ACTION_CHIP = 32;
    private static final int ACTION_ICON = 16;

    /**
     * 命令药丸（folia 实测，见「分析模型」下面那排按钮）：{@code inline-flex items-center gap-1.5
     * rounded-lg border px-2 py-1.5 text-xs font-medium}，高 30、圆角 8、左右内衬 8、
     * 图标 14 与文字之间 6；底是透明的，只有一圈 white/10 边，hover 才提一层白。
     * 一组按钮之间 {@code gap-2}(8) 并排换行。
     */
    private static final int BUTTON_H = 30;
    /** 卡内那层动作子卡之间的间距：folia 用的是 {@code space-y-3}(12)，不是卡里默认的 16。 */
    private static final int NESTED_ACTION_GAP = 12;
    private static final int BUTTON_PAD_X = 8;
    private static final int BUTTON_ICON = 14;
    private static final int BUTTON_ICON_GAP = 6;
    private static final int BUTTON_FLOW_GAP = 8;
    private static final int BUTTON_RADIUS = 8;
    private static final int PILL_EDGE_DARK = 0x1AFFFFFF;
    private static final int PILL_EDGE_LIGHT = 0x1A000000;
    private int pillEdge() { return daylight() ? PILL_EDGE_LIGHT : PILL_EDGE_DARK; }

    /** 卡内行分隔 white/5 · black/5（日/夜两档，走 {@link #daylight()}）。 */
    private static final int DIVIDER_DARK = 0x0DFFFFFF;
    private static final int DIVIDER_LIGHT = 0x0D000000;
    private int divider() { return daylight() ? DIVIDER_LIGHT : DIVIDER_DARK; }

    /**
     * 结构性分隔线（分区头下沿、侧栏右缘）：folia 这两处写的是 {@code border-white/10}，
     * 不随日/夜换色，所以在浅色下几乎看不见。照抄 —— 这不是我们漏了分支。
     */
    private static final int STRUCTURAL_DIVIDER = 0x1AFFFFFF;
    private int structuralDivider() { return STRUCTURAL_DIVIDER; }

    /** 侧栏选中底 white/10 · white/80（日/夜两档，走 {@link #daylight()}）。 */
    private static final int SIDE_SELECTED_BG_DARK = 0x1AFFFFFF;
    private static final int SIDE_SELECTED_BG_LIGHT = 0xCCFFFFFF;
    private int sideSelectedBg() { return daylight() ? SIDE_SELECTED_BG_LIGHT : SIDE_SELECTED_BG_DARK; }

    /** 侧栏选中边 white/20 · zinc-300/70（日/夜两档，走 {@link #daylight()}）。 */
    private static final int SIDE_SELECTED_EDGE_DARK = 0x33FFFFFF;
    private static final int SIDE_SELECTED_EDGE_LIGHT = 0xB3D4D4D8;
    private int sideSelectedEdge() { return daylight() ? SIDE_SELECTED_EDGE_LIGHT : SIDE_SELECTED_EDGE_DARK; }

    /** 侧栏 hover white/5 · white/50（日/夜两档，走 {@link #daylight()}）。 */
    private static final int SIDE_HOVER_DARK = 0x0DFFFFFF;
    private static final int SIDE_HOVER_LIGHT = 0x80FFFFFF;
    private int sideHover() { return daylight() ? SIDE_HOVER_LIGHT : SIDE_HOVER_DARK; }

    /** TOC 选中底 white/[0.06] · black/[0.04]（日/夜两档，走 {@link #daylight()}）。 */
    private static final int TOC_BG_DARK = 0x0FFFFFFF;
    private static final int TOC_BG_LIGHT = 0x0A000000;
    private int tocBg() { return daylight() ? TOC_BG_LIGHT : TOC_BG_DARK; }

    /** TOC hover 底 white/[0.035] · black/[0.025]（日/夜两档，走 {@link #daylight()}）。 */
    private static final int TOC_HOVER_DARK = 0x09FFFFFF;
    private static final int TOC_HOVER_LIGHT = 0x06000000;
    private int tocHover() { return daylight() ? TOC_HOVER_LIGHT : TOC_HOVER_DARK; }

    /** 幽灵按钮底 white/5 · black/2.5%（日/夜两档，走 {@link #daylight()}）。 */
    private static final int GHOST_BG_DARK = 0x0DFFFFFF;
    private static final int GHOST_BG_LIGHT = 0x06000000;
    private int ghostBg() { return daylight() ? GHOST_BG_LIGHT : GHOST_BG_DARK; }

    /** 幽灵按钮 hover white/10 · black/5.5%（日/夜两档，走 {@link #daylight()}）。 */
    private static final int GHOST_HOVER_DARK = 0x1AFFFFFF;
    private static final int GHOST_HOVER_LIGHT = 0x0E000000;
    private int ghostHover() { return daylight() ? GHOST_HOVER_LIGHT : GHOST_HOVER_DARK; }

    /** 幽灵按钮边 white/10 · black/10（日/夜两档，走 {@link #daylight()}）。 */
    private static final int GHOST_EDGE_DARK = 0x1AFFFFFF;
    private static final int GHOST_EDGE_LIGHT = 0x1A000000;
    private int ghostEdge() { return daylight() ? GHOST_EDGE_LIGHT : GHOST_EDGE_DARK; }

    /** 开关关闭底色 white/10 · zinc-300/90（日/夜两档，走 {@link #daylight()}）。 */
    private static final int TOGGLE_OFF_DARK = 0x1AFFFFFF;
    private static final int TOGGLE_OFF_LIGHT = 0xE6D4D4D8;
    private int toggleOff() { return daylight() ? TOGGLE_OFF_LIGHT : TOGGLE_OFF_DARK; }

    /** 菜单底 rgba(24,24,27,.96) · rgba(255,255,255,.96)（日/夜两档，走 {@link #daylight()}）。 */
    private static final int MENU_BG_DARK = 0xF418181B;
    private static final int MENU_BG_LIGHT = 0xF4FFFFFF;
    private int menuBg() { return daylight() ? MENU_BG_LIGHT : MENU_BG_DARK; }

    /** 遮罩 black/.5 · black/.32，主弹窗这一层刻意不毛玻璃（日/夜两档）。 */
    private static final int MASK_DARK = 0x80000000;
    private static final int MASK_LIGHT = 0x52000000;
    private int maskArgb() { return daylight() ? MASK_LIGHT : MASK_DARK; }

    /** 菜单边 rgba(244,244,245,.14) · rgba(28,25,23,.14)（日/夜两档）。 */
    private static final int MENU_EDGE_DARK = 0x24F4F4F5;
    private static final int MENU_EDGE_LIGHT = 0x241C1917;
    private int menuEdge() { return daylight() ? MENU_EDGE_LIGHT : MENU_EDGE_DARK; }

    /** 菜单未选中项 hover white/[0.06] · black/[0.04]（日/夜两档）。 */
    private static final int MENU_ITEM_HOVER_DARK = 0x0FFFFFFF;
    private static final int MENU_ITEM_HOVER_LIGHT = 0x0A000000;
    private int menuItemHover() { return daylight() ? MENU_ITEM_HOVER_LIGHT : MENU_ITEM_HOVER_DARK; }

    /** 滚动条三档：暗 #18181b / #3f3f46 / #52525b，日 #cccbcc / #ececec / #fff（全部不透明）。 */
    private static final int SCROLL_TRACK_DARK = 0xFF18181B;
    private static final int SCROLL_TRACK_LIGHT = 0xFFCCCCCC;
    private int scrollTrack() { return daylight() ? SCROLL_TRACK_LIGHT : SCROLL_TRACK_DARK; }

    private static final int SCROLL_THUMB_DARK = 0xFF3F3F46;
    private static final int SCROLL_THUMB_LIGHT = 0xFFECECEC;
    private int scrollThumb() { return daylight() ? SCROLL_THUMB_LIGHT : SCROLL_THUMB_DARK; }

    private static final int SCROLL_THUMB_HOVER_DARK = 0xFF52525B;
    private static final int SCROLL_THUMB_HOVER_LIGHT = 0xFFFFFFFF;
    private int scrollThumbHover() { return daylight() ? SCROLL_THUMB_HOVER_LIGHT : SCROLL_THUMB_HOVER_DARK; }

    /**
     * 滑条的 {@code accent-color}：{@code accent-current} 取的是 currentColor，而
     * {@code rangeInputClass} 给的是暗 {@code text-white} / 日 {@code text-zinc-900} ——
     * 也就是说这根滑条**不跟主题强调色走**，暗色下整条是白的。
     */
    private static final int SLIDER_ACCENT_DARK = 0xFFFFFFFF;
    private static final int SLIDER_ACCENT_LIGHT = 0xFF181818;
    private int sliderAccent() { return daylight() ? SLIDER_ACCENT_LIGHT : SLIDER_ACCENT_DARK; }

    /**
     * 未选中档的底色（{@code unselectedOptionStyle}）：white/4 · zinc-900/3.5%。
     * 同一份 class 里的 {@code borderColor} 那一档没人用 —— 侧栏按钮与 chip 未选中时都是
     * {@code border-transparent}，所以这里只有底，没有边。
     */
    private static final int CHIP_BG_DARK = 0x0AFFFFFF;
    private static final int CHIP_BG_LIGHT = 0x0918181B;
    private int chipBg() { return daylight() ? CHIP_BG_LIGHT : CHIP_BG_DARK; }

    private static int textPrimary() {
        return 0xFF000000 | NCMTheme.getColor(NCMScreen.ColorType.PRIMARY_TEXT);
    }

    private static int textSecondary() {
        return 0xFF000000 | NCMTheme.getColor(NCMScreen.ColorType.SECONDARY_TEXT);
    }

    private static int accent() {
        return 0xFF000000 | NCMTheme.getColor(NCMScreen.ColorType.ACCENT);
    }

    /** {@code ${accent}18} / {@code ${accent}12}：强调色当底色用时的两档透明。 */
    private int accentTint() {
        return daylight() ? 0x12000000 | (accent() & 0xFFFFFF) : 0x18000000 | (accent() & 0xFFFFFF);
    }

    // ==================== 侧栏分组（folia: 分组标签 → 分区按钮） ====================

    /**
     * 侧栏上这一分类所属大块的标签。标签由模型带过来（{@link HudSettingsModel.Category#navGroup}）：
     * 这里以前是按分类下标区间硬编码四块，模型加一个分类就得回来数一遍下标 —— 数错一次不报错，
     * 那一块只是静默挂到别的标签下（旧的第 12 项「HUD 位置」就是这么掉进「其他设置」的）。
     * 渲染顺序仍然跟着 {@link HudSettingsModel#categories()} 的出现次序，所以同一块的分类要相邻摆放，
     * 这一点由 HudSettingsTaxonomyStaticTest 钉住。
     */
    private static String navGroupLabel(int categoryIndex) {
        HudSettingsModel.Category[] categories = HudSettingsModel.categories();
        if (categoryIndex < 0 || categoryIndex >= categories.length) return "";
        String label = categories[categoryIndex].navGroup;
        return label == null ? "" : label;
    }

    // ==================== 行表（渲染与命中共用的唯一坐标来源） ====================

    /**
     * 一条可见的行。{@code entry == null} 即分组标题行（锚点），{@code anchor >= 0} 标记可跳转锚点。
     *
     * <p>{@code padded} 表示这一格在**内衬卡**里（folia 的 {@code p-4 rounded-xl border}）：
     * 坐标已经是卡内衬之后的，行自己不再带内衬。{@code sep} 表示它上面那条
     * {@code border-t} —— 只有「左标签右控件」的两种行相邻时才有，控件铺满的那种没有。</p>
     */
    private static final class Row {
        final HudSettingsModel.Entry entry;
        final String title;
        final String icon;
        final int accent;
        final int x;
        final int y;
        final int w;
        final int h;
        final boolean sep;
        final boolean padded;
        final int anchor;

        Row(HudSettingsModel.Entry entry, String title, String icon, int accent,
            int x, int y, int w, int h, boolean sep, boolean padded, int anchor) {
            this.entry = entry;
            this.title = title;
            this.icon = icon;
            this.accent = accent;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.sep = sep;
            this.padded = padded;
            this.anchor = anchor;
        }

        boolean isGroupHeader() {
            return entry == null;
        }
    }

    private static final class Card {
        final int x;
        final int y;
        final int w;
        final int h;

        Card(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    /** 侧栏的一个可点行：kind 0 = 分区，1 = 锚点 TOC。 */
    private static final class NavItem {
        int kind;
        int index;
        int x;
        int y;
        int w;
        int h;
    }

    private final List<Row> rows = new ArrayList<Row>();
    private final List<Card> cards = new ArrayList<Card>();
    private final List<NavItem> navItems = new ArrayList<NavItem>();
    private int contentHeight;
    /** 锚点 → 整列里的顶端偏移。长度随模型分组数增长，别再用定长数组静默丢锚点。 */
    private int[] anchorTops = new int[32];
    private int lastMaxScroll;

    // ==================== 状态 ====================

    private int categoryIndex;
    /** 侧栏自己是 {@code overflow-y-auto}：条目总高超出可用高度时在层内滚，不压缩行高。 */
    private int navScroll;
    private long navContentHeight;
    /** 窄屏 chips 那条横排：横向偏移、拖拽起点与是否正在拖（folia 的 useDragToScroll）。 */
    private int chipScroll;
    private int chipDragStartX = Integer.MIN_VALUE;
    private int chipDragFromScroll;
    private boolean chipDragging;
    /** 按下时落在哪枚 chip 上，等到松开才选中；拖过阈值则这次点击作废。 */
    private int chipDragIndex = -1;
    private boolean chipHasDragged;
    /** 拖过 5px 就当成拖拽、不再当事件用（源码里的抑制阈值）。 */
    private static final int CHIP_DRAG_THRESHOLD = 5;
    /** 拖拽位移与鼠标位移的比例。 */
    private static final float CHIP_DRAG_FACTOR = 1.5f;
    /** 整列的滚动位置。folia 换分区就是把 scrollTop 归零，不记忆各分区滚到哪。 */
    private int scrollOffset;
    /** 下一帧把显示位置直接对齐到目标（滚轮、拖动、换分区用；点锚点不走这条）。 */
    private boolean snapScroll;
    private float glideFrom;
    private float glideTarget;
    private long glideStart;
    private int glideMs;
    private int activeAnchor = -1;
    private long clickLockUntil;
    /** 滚轮一格推多远（原实现是 30px 一格，这里照旧，行为与浏览器逐格滚一致）。 */
    private static final int WHEEL_STEP = 30;

    private HudSetting draggingSlider;
    private int draggingSliderTrackX;
    /** 拖拽期间轨道的宽：每行铺满，所以只能在按下那一刻记下。 */
    private int draggingSliderTrackW;
    private HudSettingsModel.ColorTarget editingColor = HudSettingsModel.ColorTarget.NONE;
    private float pickerHue;
    private float pickerSaturation;
    private float pickerBrightness;
    private boolean draggingSaturationBrightness;
    private boolean draggingHue;
    private GuiTextField hexColorInput;
    private boolean settingsDirty;

    /** CHOICE 下拉菜单：打开的条目、它上一帧的行矩形（portal 语义，画在内容裁剪之外）、入场进度。 */
    private HudSettingsModel.Entry openChoice;
    private int[] openChoiceRect;
    private long choiceOpenTime;
    private boolean choiceUp;
    private int choiceHoverIndex = -1;
    /** 选项多于可视高度时，在菜单这一层内部滚的偏移（folia 的 max-height 240 加纵向滚动）。 */
    private int menuScroll;

    /** 按下归属（folia：mousedown 与 click 都落在遮罩上才关闭）。 */
    private boolean pressOutsidePanel;

    private boolean introStarted;
    private long openTime;
    private boolean closing;
    private long closeStart;

    /** 切分区横向滑入的状态：方向由新旧分区下标决定。 */
    /** 换页滑动（{@code x±60}）属于 folia 的 Help↔Options Tab 层，本界面无该层，故不留状态。 */
    private int contentShownCategory = Integer.MIN_VALUE;

    // ==================== 动画状态（与 Pro 同一套机制） ====================

    /** 绘制时统一乘上的不透明度，见 Pro 版的说明：入场淡入与退场淡出都靠它，漏一处就是一个不跟着淡的破口。 */
    private float layerAlpha = 1f;

    /** 平滑滚动显示位置。只在 drawScreen 推进，mouseClicked 读到的是同一份，绘制与命中不会错帧。 */
    private float displayScroll;

    /**
     * 当前被按住的部件键。folia 的 {@code active:scale-95}／{@code active:scale-110→95}
     * 这些按下态全靠它 —— CSS 的 {@code :active} 是「鼠标按下且仍在这个元素上」，
     * 所以松开、移出都要清掉。
     */
    private String pressedTarget;
    private static final Map<CFontRenderer, GlyphInkMetrics> GRID_CACHE =
            new IdentityHashMap<CFontRenderer, GlyphInkMetrics>();

    private int savedAdaptiveWidth;
    private int savedAdaptiveHeight;

    // ==================== 生命周期 ====================

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        ensureHexColorInput();
        if (!introStarted) {
            introStarted = true;
            openTime = System.currentTimeMillis();
        }
    }

    @Override
    public void onGuiClosed() {
        if (settingsDirty) {
            HudConfig.save();
            settingsDirty = false;
        }
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ==================== 主绘制 ====================

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        SkijaGfx.pushAttrib();
        SkijaGfx.pushMatrix();
        boolean adaptive = false;
        try {
            RenderSystem.refreshResolution();
            CFontRenderer fr = FontManager.pf12;
            if (fr == null) return;

            if (closing) {
                float q = tween(closeStart, OUTRO_MS);
                if (q >= 1f) {
                    layerAlpha = 1f;
                    mc.gui.setScreen(null);
                    return;
                }
                layerAlpha = 1f - q;
            } else {
                layerAlpha = tween(openTime, INTRO_MS);
            }

            // 遮罩之下的 HUD 与遮罩本身都画在 **GUI 逻辑坐标**里，而且必须在换进设计空间之前：
            // widget 自己按 GUI 坐标摆放，跟着这里的矩阵缩放会跑到屏幕外；而遮罩要铺满整屏，
            // 不能跟着设计空间那套「除以倍率再取整」的轴走 —— 差半个单位就是一条没压暗的缝。
            drawHudUnderMask();
            drawMask();

            adaptive = beginAdaptiveLayout();
            if (adaptive) {
                mouseX = (int) Math.round(toUiX(mouseX));
                mouseY = (int) Math.round(toUiY(mouseY));
                float scale = uiScale();
                SkijaGfx.pushMatrix();
                SkijaGfx.scale(WindowAdaptiveScale.designCorrectionX() * scale,
                        WindowAdaptiveScale.designCorrectionY() * scale);
            }

            try {
                drawScreenUnguarded(fr, mouseX, mouseY, partialTicks);
            } finally {
                layerAlpha = 1f;
            }
            super.drawScreen(mouseX, mouseY, partialTicks);
        } finally {
            layerAlpha = 1f;
            endScissor();
            if (adaptive) {
                SkijaGfx.popMatrix();
            }
            restoreAdaptiveLayout(adaptive);
            SkijaGfx.popMatrix();
            SkijaGfx.popAttrib();
        }
    }

    private void drawScreenUnguarded(CFontRenderer fr, int mouseX, int mouseY, float partialTicks) {
        lightThisFrame = NCMTheme.isLightPreset();
        int[] panel = panelBounds();
        int panelX = panel[0];
        int panelY = panel[1];
        int panelW = panel[2];
        int panelH = panel[3];

        // 遮罩与遮罩之下的 HUD 已在 drawScreen 里、进入设计空间之前画完。
        int wheel = closing ? 0 : Mouse.getDWheel();
        if (openChoice != null && wheel != 0) {
            // 菜单是叠在主面板上的一层，滚轮先归它（folia 里菜单本身就是滚动容器）。
            int[] menu = choiceMenuRect();
            if (menu != null) {
                int total = openChoice.getChoices().length;
                int limit = Math.max(0, (total - menuVisibleRows(menu[3], total)) * MENU_ITEM_PITCH);
                menuScroll = Math.max(0, Math.min(limit,
                        menuScroll - (wheel / WHEEL_NOTCH) * MENU_ITEM_PITCH));
            }
            wheel = 0;
        }
        boolean overPanel = !closing && isInside(mouseX, mouseY, panelX, panelY, panelW, panelH);
        int[] nav = navRect();
        int[] content = contentRect();
        if (wheel != 0 && openChoice == null && overPanel
                && isInside(mouseX, mouseY, nav[0], nav[1], nav[2], nav[3])) {
            // 滚轮落在哪一档就滚哪一档：侧栏和内容列是两个各自独立的滚动容器。
            buildNav(nav[0], nav[1], nav[2], nav[3]);
            navScroll = Math.max(0, Math.min((int) Math.max(0, navContentHeight - nav[3]),
                    navScroll + (wheel > 0 ? -WHEEL_STEP : WHEEL_STEP)));
            wheel = 0;
        }

        // 面板整体做入场/退场的位移与缩放。矩阵只作用于绘制，命中测试仍走 panelBounds() 的最终矩形 ——
        // 240ms 内偏差不到 2% 缩放，不足以造成可感知的错点。
        float shell = closing
                ? tween(closeStart, OUTRO_MS)
                : tween(openTime, INTRO_MS);
        double rise = closing ? 16.0 * shell : 20.0 * (1.0 - shell);
        double scale = closing ? 1.0 - 0.015 * shell : 0.98 + 0.02 * shell;
        double cx = panelX + panelW / 2.0;
        double cy = panelY + panelH / 2.0;
        SkijaGfx.pushMatrix();
        SkijaGfx.translate(cx, cy + rise);
        SkijaGfx.scale(scale, scale);
        SkijaGfx.translate(-cx, -cy);
        try {
            updatePickerWhileDragging(mouseX, mouseY, panelX, panelY, panelW, panelH);
            updateSliderWhileDragging(mouseX);
            updateChipDrag(mouseX);

            drawPanelShadow(panelX, panelY, panelW, panelH);
            // 真玻璃：铺一层糊过的游戏画面，再把面板底色按「玻璃浓度」压薄。
            // 这一层画在设计空间里，外面还套着面板自己的入场位移与缩放，但都不必报给玻璃：
            // 采哪一块背景问的是画布当前矩阵，那两层缩放本来就在矩阵里。
            boolean frosted = GlassLook.paintFrost(GlassLook.Surface.EDITORS, panelX, panelY,
                    panelW, panelH, PANEL_RADIUS, layerAlpha);
            drawRoundedRect(panelX, panelY, panelW, panelH, PANEL_RADIUS,
                    frosted ? GlassLook.tintedArgb(GlassLook.Surface.EDITORS, panelBg()) : panelBg());
            if (frosted) {
                GlassLook.paintEdge(GlassLook.Surface.EDITORS, panelX, panelY, panelW, panelH,
                        PANEL_RADIUS, layerAlpha);
            }
            drawBlobs(panelX, panelY, panelW, panelH);
            drawRoundedOutline(panelX, panelY, panelW, panelH, PANEL_RADIUS, 1, panelEdge());

            drawPanelHeader(fr, panelX, panelY, panelW, mouseX, mouseY);

            buildNav(nav[0], nav[1], nav[2], nav[3]);
            drawNav(fr, nav[0], nav[1], nav[2], nav[3], mouseX, mouseY);
            drawContent(fr, content[0], content[1], content[2], content[3], wheel,
                    overPanel && isInside(mouseX, mouseY, content[0], content[1],
                            content[2], content[3]),
                    mouseX, mouseY);

            if (editingColor != HudSettingsModel.ColorTarget.NONE) {
                drawColorPicker(fr, panelX, panelY, panelW, panelH, mouseX, mouseY);
            }
            if (openChoice != null) {
                drawChoiceMenu(fr, mouseX, mouseY);
            }
        } finally {
            SkijaGfx.popMatrix();
        }
    }

    /**
     * 遮罩：folia 主弹窗没有 backdrop-blur，就是一层 50% 黑（日/夜两档）。
     *
     * <p>刻意画在 GUI 逻辑坐标而不是设计空间里：Skia 画布的基准变换就是「GUI → 像素」，
     * 这一格不乘任何额外矩阵，{@code (0,0,guiWidth,guiHeight)} 天然就是整屏。换成设计空间那根
     * 「物理宽 ÷ 缩放倍率再取整」的轴，就会在边上留下没压暗的缝，看着像一层重影。</p>
     */
    private void drawMask() {
        SkijaShapes.rect(0, 0, SkijaHost.guiWidth(), SkijaHost.guiHeight(),
                fade(maskArgb(), layerAlpha));
    }

    /** 面板之外（遮罩之下）的两块 HUD 实时预览：只画真实内容，不放占位卡，避免遮罩后出现幽灵边框。 */
    private void drawHudUnderMask() {
        MusicInfoWidget info = MuoniumPlayerExtension.getInstance().musicInfo;
        MusicLyricsWidget lyrics = MuoniumPlayerExtension.getInstance().musicLyrics;
        if (hasLiveInfoPreview()) {
            renderLive(info);
        }
        if (hasLiveLyricsPreview()) {
            int lyricW = (int) (HudConfig.lyricWidth * HudConfig.lyricScale);
            int lyricH = (int) (HudConfig.lyricHeight * HudConfig.lyricScale);
            int lyricX = (int) (HudConfig.lyricX * (width - lyricW));
            int lyricY = (int) (HudConfig.lyricY * (height - lyricH));
            renderLiveLyrics(lyrics, lyricX, lyricY);
        }
    }

    // ==================== 面板骨架 ====================

    /**
     * 这一套界面固定走「CSS px ÷ 缩放倍率」那档设计空间（不看玩家的窗口自适应开关）。
     *
     * <p>照抄过来的尺寸全是 CSS px：面板 896、遮罩内缩 20/48、行 72、正文 14。把它们直接当成
     * Minecraft 的 GUI 逻辑单位用，面板会从「占屏 47%」变成「占屏 94%」，形状也由接近正方形被
     * 压成一条横幅 —— 因为 GUI 逻辑宽本身就是物理宽除以 GUI Scale。所以这里强制进固定布局，
     * 并把播放器那半尺寸轴乘回 2，得到 1 单位 = 1 CSS px。</p>
     *
     * <p>再往下的除数是 {@link #uiScale()}：倍率 1 就是 dpr=1 的浏览器，像素级准确但在游戏窗口里
     * 偏小（Minecraft 的 GUI Scale 本来就是它的 dpr，自带文字是 9 个 GUI 像素）。倍率是往轴里除，
     * 不是往矩阵里乘 —— 面板、文字、间距、控件与断点判定会一起放大，folia 的排版比例一点不动。</p>
     */
    private boolean beginAdaptiveLayout() {
        RenderSystem.refreshResolution();
        if (RenderSystem.getWidth() <= 0 || RenderSystem.getHeight() <= 0) return false;
        WindowAdaptiveScale.beginForced();
        savedAdaptiveWidth = width;
        savedAdaptiveHeight = height;
        float scale = uiScale();
        width = (int) Math.round(WindowAdaptiveScale.designWidth() / scale);
        height = (int) Math.round(WindowAdaptiveScale.designHeight() / scale);
        return true;
    }

    /** 界面整体缩放倍率。见 {@link HudConfig#foliaUiScale}；区间由配置层夹住，这里不再兜底。 */
    private static float uiScale() {
        return HudConfig.foliaUiScale;
    }

    /** GUI 逻辑坐标 → 本界面的设计坐标（含倍率）。绘制与命中都只走这两个。 */
    private double toUiX(int guiX) {
        return WindowAdaptiveScale.toDesignX(guiX) / uiScale();
    }

    private double toUiY(int guiY) {
        return WindowAdaptiveScale.toDesignY(guiY) / uiScale();
    }

    private void restoreAdaptiveLayout(boolean active) {
        if (!active) return;
        width = savedAdaptiveWidth;
        height = savedAdaptiveHeight;
        WindowAdaptiveScale.end();
    }

    /** {@code p-6 md:p-8} 加面板自己那 1px 边框，见 {@link #PANEL_BORDER}。 */
    private int pad() {
        return (wideLayout() ? PAD : PAD_NARROW) + PANEL_BORDER;
    }

    /** 遮罩内缩：{@code px-4 py-8}，到 sm 变 {@code px-5 py-12}。 */
    private int maskPadX() {
        return width >= BREAKPOINT_SM ? MASK_PAD_X : MASK_PAD_X_NARROW;
    }

    /** 分区头里那条线的位置与整块占高（窄屏的 pb/mb 各降一档）。 */
    private int headLineY() {
        return HEAD_TITLE_H + HEAD_DESC_MT + HEAD_DESC_H + (wideLayout() ? 16 : 12);
    }

    private int contentHeadH() {
        return headLineY() + 1 + (wideLayout() ? TAB_MB : 16);
    }

    /**
     * 面板矩形唯一来源（约束 2）。
     *
     * <p>宽 {@code min(896, 屏宽 - 遮罩内缩)}：源码写的是 {@code w-full md:w-[900px]} 加
     * {@code md:max-w-4xl}，而它是遮罩那个 flex 容器的子项、默认可以缩 —— 实测 831px 视口下面板
     * 就是 791 宽。高 {@code 85vh}（窄屏 {@code 90vh}），主轴方向不收缩，所以只夹到屏高。</p>
     */
    private int[] panelBounds() {
        int panelW = Math.min(PANEL_MAX_W, width - maskPadX() * 2);
        int panelH = Math.min((int) Math.round(height * (wideLayout() ? 0.85 : 0.90)), height);
        panelW = Math.max(1, panelW);
        panelH = Math.max(1, panelH);
        int maxX = Math.max(0, width - panelW);
        int maxY = Math.max(0, height - panelH);
        return new int[] { maxX / 2, maxY / 2, panelW, panelH };
    }

    /** chip 盒高 = {@code p-2}(8) + text-sm 行高 20 + {@code p-2}(8)；chip 之间 {@code space-x-2}。 */
    private static final int CHIP_H = 36;
    private static final int CHIP_GAP = 8;
    /**
     * 窄屏那条横带的盒子高：chip(36) + {@code pb-3}(12) + {@code border-b}(1)。
     * 分隔线就画在这 49 的下缘。
     */
    private static final int CHIP_BAND_H = CHIP_H + 12 + 1;
    /** 横带与内容列之间：带自己的 {@code mb-2}(8) 再加父容器的 {@code gap-4}(16)。 */
    private static final int CHIP_BAND_GAP = 24;

    /**
     * 侧栏矩形：宽屏是左栏，窄屏是压在内容之上的一条横带（folia 的父容器是 {@code flex-col}）。
     *
     * <p>宽屏取 {@code w-1/3} 再被 {@code max-w-[264px]} 夹住。旧写法在窄屏只是把左栏挤到
     * 150px、两列照样并排 —— 那不是 folia 的另一套布局，是同一套布局被压扁。</p>
     */
    private int[] navRect() {
        int[] panel = panelBounds();
        int pad = pad();
        int x = panel[0] + pad;
        int y = panel[1] + pad + HEADER_BLOCK_H;
        int h = panel[3] - pad * 2 - HEADER_BLOCK_H;
        if (wideLayout()) {
            return new int[] { x, y, Math.min(SIDEBAR_W, (panel[2] - pad * 2) / 3), h };
        }
        return new int[] { x, y, panel[2] - pad * 2, CHIP_BAND_H };
    }

    /**
     * 内容列矩形：已经是**内边距之内**的那一格（{@code pl-1 md:pl-2}/{@code pr-2 md:pr-4} 与
     * 8px 滚动条都扣掉了），所以画文字和算命中都直接用它。列分隔线落在 {@code navRect()} 的右边界上。
     */
    private int[] contentRect() {
        int[] panel = panelBounds();
        int[] nav = navRect();
        int pad = pad();
        int gap = wideLayout() ? SIDEBAR_GAP : SIDEBAR_GAP_NARROW;
        int pl = wideLayout() ? CONTENT_PL : CONTENT_PL_NARROW;
        int pr = wideLayout() ? CONTENT_PR : CONTENT_PR_NARROW;
        int right = panel[0] + panel[2] - pad;
        if (wideLayout()) {
            int x = nav[0] + nav[2] + gap + pl;
            return new int[] { x, nav[1], right - SCROLLBAR_W - pr - x, nav[3] };
        }
        int y = nav[1] + nav[3] + CHIP_BAND_GAP;
        return new int[] { nav[0] + pl, y, right - SCROLLBAR_W - pr - nav[0] - pl,
                Math.max(1, panel[3] - pad * 2 - HEADER_BLOCK_H - nav[3] - CHIP_BAND_GAP) };
    }

    /** 阴影用的模糊滤镜按 sigma 缓存：每帧新建一次 ImageFilter 是白交的 JNI 钱。 */
    private static final Map<Float, ImageFilter> BLUR_FILTERS = new HashMap<>();

    private static ImageFilter blurFilter(float sigma) {
        Float key = Float.valueOf(sigma);
        ImageFilter filter = BLUR_FILTERS.get(key);
        if (filter == null) {
            // 必须是 DECAL，不能是 CLAMP。CLAMP 的语义是「源图边界之外按边界像素取值」，
            // 而这里的源就是一块实心形状、它的边界像素本身就是有值的 —— 于是卷积看到的是一片
            // 无限延伸的同值半平面，糊出来的是一张**四周齐裁的实心矩形**：面板外凭空多出一圈
            // 25% 黑的方框，边线笔直。DECAL 才是「边界外当透明」，也就是 CSS box-shadow 的观感。
            // （SkijaEffects 里 CLAMP 是对的，那儿的输入是整块背景的快照，边界外本就该续上。）
            filter = ImageFilter.makeBlur(sigma, sigma, FilterTileMode.DECAL);
            BLUR_FILTERS.put(key, filter);
        }
        return filter;
    }

    /**
     * 这套主题走浅色档：folia 的 {@code isDaylight}，决定表面 token 取日间的哪一套。
     *
     * <p>读的是每帧开头算好的那一次，不是每次都去查主题：一帧里表面色要问上百次，而
     * {@code NCMTheme.isLightPreset()} 背后是一次取色加一趟 HSB 换算。网页上读 CSS 变量
     * 是免费的，这里不是。</p>
     */
    private boolean daylight() {
        return lightThisFrame;
    }

    /** 本帧的明暗档，见 {@link #daylight()}。 */
    private boolean lightThisFrame;

    /**
     * CSS {@code box-shadow} 的等价物：把「按 spread 收缩过的形状」整体糊一层高斯。
     *
     * <p>CSS 的 blur 半径是 2 倍 sigma，负 spread 先把矩形往内收。这里用的是本工程已有的
     * 真模糊路子（挂 {@link ImageFilter} 的绘制，和 {@code SkijaEffects.backdropBlur} 同一类），
     * 不再叠三层外扩：副本之间填不满，出来的是方块状灰雾，和高斯的观感差一个量级。</p>
     *
     * @param offsetY CSS 的 offsetY
     * @param blur    CSS 的 blur 半径（内部换算成 sigma）
     * @param spread  CSS 的 spread，负数收缩
     */
    private void drawBoxShadow(double x, double y, double w, double h, double radius,
                               double offsetY, double blur, double spread, int argb) {
        if (w <= 0 || h <= 0 || ((argb >>> 24) & 0xFF) == 0) return;
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) return;
        RRect shape = SkijaShapes.rrect(x - spread, y + offsetY - spread,
                w + spread * 2, h + spread * 2, Math.max(0, radius + spread));
        try (Paint paint = new Paint()) {
            paint.setAntiAlias(true);
            paint.setColor(argb);
            paint.setImageFilter(blurFilter((float) (blur / 2.0)));
            paint.setBlendMode(SkijaGfx.blendMode());
            canvas.drawRRect(shape, paint);
        }
    }

    /** 主面板的 shadow-2xl：{@code 0 25px 50px -12px rgba(0,0,0,.25)}。 */
    private void drawPanelShadow(double x, double y, double w, double h) {
        drawBoxShadow(x, y, w, h, PANEL_RADIUS, 25, 50, -12,
                fade(0xFF000000, 0.25f * layerAlpha));
    }

    /** 浮层的 shadow-xl：{@code 0 20px 25px -5px .1} + {@code 0 8px 10px -6px .1}。 */
    private void drawFloatShadow(double x, double y, double w, double h, double radius) {
        drawBoxShadow(x, y, w, h, radius, 20, 25, -5, fade(0xFF000000, 0.10f * layerAlpha));
        drawBoxShadow(x, y, w, h, radius, 8, 10, -6, fade(0xFF000000, 0.10f * layerAlpha));
    }

    /**
     * 两枚装饰光斑（folia：256px 圆、{@code blur-[80px]}、暗 10% / 日 20%、对角外扩 -96px）。
     *
     * <p>位置照抄 CSS 那两行的几何：盒边长 256，右上那枚的盒原点落在 {@code (pw-256+96, -96)}，
     * 于是圆心在 {@code (pw-32, 32)}，另一枚对称到 {@code (32, ph-32)}。模糊半径 80 → sigma 40，
     * 光晕因此外溢到盒外约 240px —— 比原先那枚线性径向渐变的软球摊开得远得多、峰值也低得多，
     * 这正是「两个 80px 模糊的强调色光斑」看起来不像一块色斑的原因。</p>
     */
    private void drawBlobs(int panelX, int panelY, int panelW, int panelH) {
        final double radius = 128.0;
        final float alpha = (daylight() ? 0.20f : 0.10f) * layerAlpha;
        final int acc = accent();
        final int sec = textSecondary();
        final int px = panelX;
        final int py = panelY;
        final int pw = panelW;
        final int ph = panelH;
        SkijaClip.beginClip(new Runnable() {
            public void run() {
                SkijaShapes.roundedRect(px, py, pw, ph, PANEL_RADIUS, 0xFFFFFFFF);
            }
        });
        try {
            drawBlurredDisc(px + pw - 32, py + 32, radius, acc, alpha);
            drawBlurredDisc(px + 32, py + ph - 32, radius, sec, alpha);
        } finally {
            SkijaClip.endClip();
        }
    }

    private void drawBlurredDisc(double centerX, double centerY, double radius, int color, float alpha) {
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null || alpha <= 0.002f) return;
        try (Paint paint = new Paint()) {
            paint.setAntiAlias(true);
            paint.setColor(fade(color, alpha));
            paint.setImageFilter(blurFilter(40f));
            paint.setBlendMode(SkijaGfx.blendMode());
            canvas.drawCircle((float) centerX, (float) centerY, (float) radius, paint);
        }
    }

    /**
     * 顶部标签行 + 右上角的关闭钮。
     *
     * <p>folia 这一条就是「Help / Options」两枚 {@code text-2xl font-bold} 的字，选中那枚底下压一条
     * 3px 的强调色下划线；这里把三档编辑器外观（经典 / 现代 / OneConfig）放进同一套排版 —— 它们和
     * Help/Options 一样是「整块视图换一个」的开关。原来那行「音乐 HUD 设置」标题没有对应物，去掉。</p>
     */
    private void drawPanelHeader(CFontRenderer fr, int panelX, int panelY, int panelW,
                                 int mouseX, int mouseY) {
        CFontRenderer tf = headerTabFont();
        int active = activeHeaderTab();
        for (int i = 0; i < headerTabCount(); i++) {
            int[] box = tabRect(i);
            boolean hot = i != active && !closing
                    && isInside(mouseX, mouseY, box[0], box[1], box[2], box[3]);
            // transition-colors 150ms：opacity-40 → hover:opacity-80，选中是 100。
            float hover = transition("header.tab" + i, hot, TRANSITION_MS, true);
            float alpha = i == active ? 1f : 0.4f + 0.4f * hover;
            drawLineText(tf, tabLabel(i), box[0], box[1], TAB_LINE_H, fade(textPrimary(), alpha));
            if (i == active) {
                // 下划线随面板一起挂载：opacity 0→1 + scaleX .65→1，0.18s easeOut。
                float u = clamp01(tween(openTime, TAB_UNDERLINE_MS));
                float w = box[2] * (0.65f + 0.35f * u);
                drawRoundedRect(box[0] + (box[2] - w) * 0.5f, box[1] + box[3] - TAB_UNDERLINE_H,
                        w, TAB_UNDERLINE_H, TAB_UNDERLINE_H * 0.5f, fade(accent(), u));
            }
        }

        int[] close = closeButton(panelX, panelY, panelW);
        float cp = named("header.close", !closing
                && isInside(mouseX, mouseY, close[0], close[1], close[2], close[3]));
        // opacity-30 → hover:opacity-100 是整颗按钮的透明度，底色和 X 一起淡入。
        float ga = 0.3f + 0.7f * cp;
        drawRoundedRect(close[0], close[1], close[2], close[3], close[3] * 0.5f,
                fade(CLOSE_BG, ga * layerAlpha));
        drawLucide(FoliaLucideIcons.CLOSE, close[0] + close[2] / 2.0, close[1] + close[3] / 2.0,
                CLOSE_ICON, fade(textPrimary(), ga));
    }

    /** 下划线那档 {@code {duration: 0.18, ease: 'easeOut'}}。 */
    private static final int TAB_UNDERLINE_MS = 180;
    /** 关闭钮的 {@code bg-white/5} 是写死的，不随日/夜翻。 */
    private static final int CLOSE_BG = 0x0DFFFFFF;

    private static CFontRenderer headerTabFont() {
        return FontManager.pf24bold != null ? FontManager.pf24bold : FontManager.pf20bold;
    }

    private static int headerTabCount() {
        return OneConfigHudBridge.isAvailable() ? 3 : 2;
    }

    private static String tabLabel(int index) {
        return index == 0 ? "经典" : index == 1 ? "现代" : "OneConfig";
    }

    /** 这个界面自己是「现代」那一档。 */
    private static int activeHeaderTab() {
        return 1;
    }

    private int tabWidth(int index) {
        CFontRenderer tf = headerTabFont();
        return tf == null ? 0 : tf.getStringWidth(tabLabel(index));
    }

    /** 标签格的唯一坐标来源：绘制与命中共用（约束 1）。 */
    private int[] tabRect(int index) {
        int[] panel = panelBounds();
        int x = panel[0] + pad();
        for (int i = 0; i < index; i++) {
            x += tabWidth(i) + TAB_GAP;
        }
        return new int[] { x, panel[1] + pad(), tabWidth(index), TAB_LINE_H + TAB_PB };
    }

    private int[] closeButton(int panelX, int panelY, int panelW) {
        int inset = CLOSE_INSET + PANEL_BORDER;
        return new int[] { panelX + panelW - inset - CLOSE_BOX, panelY + inset,
                CLOSE_BOX, CLOSE_H };
    }

    // ==================== 侧栏：分组标签 → 分区 → 锚点 TOC ====================

    /**
     * 构建侧栏行表（绘制与命中同一份）。选中分区下方展开它的锚点 TOC；
     * 纵向空间不够时先丢 TOC，再把分区行压扁到能放下（Pro 的 navRowHeight 思路）。
     */

    /** 宽/窄布局的分界：folia 用 {@code useMediaQuery('(min-width: 768px)')}，切的是视口宽度。 */
    private boolean wideLayout() {
        return width >= BREAKPOINT_MD;
    }

    /**
     * 排侧栏条目。宽屏是「分组标签 → 分区按钮 → 该分区的 TOC」三级，窄屏是一张展平的横条。
     *
     * <p>间距完全照源码里的 class：组内 {@code gap-1}(4)、组间 {@code gap-5}(20)、
     * TOC 块 {@code mt-1}(4)、TOC 行 {@code gap-0.5}(2)。窄屏下分组**根本不渲染标签**
     * （{@code flattenSettingsNavItems} 把组展平），chip 之间是 {@code space-x-2}(8)。</p>
     *
     * <p>宽屏放不下时不再像旧版那样压缩行高，而是让侧栏自己滚（它本来就是
     * {@code overflow-y-auto}）—— 压行高会把 44px 的按钮压成 30px，那是设计里没有的一档。</p>
     */
    private void buildNav(int sideX, int sideY, int sideW, int sideH) {
        buildNavItems(sideX, sideY, sideW, wideItemWidth(sideW, sideH, false), sideH);
        if (!wideLayout()) return;
        int itemW = wideItemWidth(sideW, sideH, true);
        if (itemW != navItemsW) buildNavItems(sideX, sideY, sideW, itemW, sideH);
    }

    private int navItemsW = -1;

    /**
     * 侧栏条目的可用宽度：容器 {@code pr-3}(12) 与 1px 的 {@code border-r} 先扣掉，
     * 溢出时再扣掉 8px 滚动条。要不要扣这一档取决于溢出与否，而溢出与否只跟高度有关
     * （文字都是 truncate，换行不会发生），所以先按不扣算一次高度就能定下来。
     */
    private int wideItemWidth(int sideW, int sideH, boolean finalPass) {
        if (!wideLayout()) return sideW;
        int base = Math.max(40, sideW - SIDEBAR_PR - 1);
        if (!finalPass) return base;
        return navContentHeight > sideH ? Math.max(40, base - SCROLLBAR_W) : base;
    }

    private void buildNavItems(int sideX, int sideY, int sideW, int itemW, int sideH) {
        navItemsW = itemW;
        navItems.clear();
        HudSettingsModel.Category[] categories = HudSettingsModel.categories();
        int selected = clampCategory();
        if (!wideLayout()) {
            buildChips(sideX, sideY, sideW, categories);
            return;
        }
        int y = sideY - navScroll;
        String lastLabel = null;
        for (int i = 0; i < categories.length; i++) {
            String label = navGroupLabel(i);
            boolean newGroup = !label.equals(lastLabel);
            lastLabel = label;
            if (newGroup) {
                if (i > 0) y += NAV_GROUP_GAP;
                y += NAV_LABEL_H + NAV_CAT_GAP;   // 标签盒自身 + label↔第一个按钮的 gap-1
            } else {
                y += NAV_CAT_GAP;                 // 同一组里按钮↔按钮
            }
            NavItem item = new NavItem();
            item.kind = 0;
            item.index = i;
            item.x = sideX;
            item.y = y;
            item.w = itemW;
            item.h = NAV_CAT_H;
            navItems.add(item);
            y += NAV_CAT_H;
            boolean firstToc = true;
            for (int g = 0; g < categories[i].groups.length; g++) {
                if (i != selected || !groupHasVisibleEntry(categories[i].groups[g])) continue;
                // TOC 块靠 mt-1 顶开第一行，之后每行之间是 gap-0.5。
                y += firstToc ? NAV_TOC_MT : NAV_TOC_GAP;
                firstToc = false;
                NavItem toc = new NavItem();
                toc.kind = 1;
                toc.index = g;
                toc.x = sideX + NAV_TOC_INDENT;
                toc.y = y;
                toc.w = Math.max(40, itemW - NAV_TOC_INDENT);
                toc.h = NAV_TOC_H;
                navItems.add(toc);
                y += NAV_TOC_H;
            }
        }
        // 容器自己还有 pb-4(16) 的内衬，它算进 scrollHeight。
        navContentHeight = y + navScroll - sideY + SIDEBAR_PB;
        int overflow = (int) Math.max(0, navContentHeight - sideH);
        navScroll = Math.max(0, Math.min(navScroll, overflow));
    }

    /** 窄屏：分组展平成一条横向 chip，可拖拽横滚；不渲染分组标签（源码 flatten 掉了）。 */
    private void buildChips(int sideX, int sideY, int sideW, HudSettingsModel.Category[] categories) {
        CFontRenderer fr = FontManager.pf14 != null ? FontManager.pf14 : FontManager.pf12;
        int x = sideX - chipScroll;
        for (int i = 0; i < categories.length; i++) {
            int text = fr == null ? 0 : fr.getStringWidth(categories[i].title);
            // chip = p-2(8) + 图标 18 + gap-2(8) + 文字 + p-2(8)，高 p-2 + text-sm 行高 20 + p-2。
            int w = 8 + 18 + 8 + text + 8;
            NavItem item = new NavItem();
            item.kind = 0;
            item.index = i;
            item.x = x;
            item.y = sideY;
            item.w = w;
            item.h = CHIP_H;
            navItems.add(item);
            x += w + CHIP_GAP;
        }
        chipContentWidth = x + CHIP_GAP - (sideX - chipScroll);
        navContentHeight = CHIP_H;
    }

    private static boolean groupHasVisibleEntry(HudSettingsModel.Group group) {
        for (HudSettingsModel.Entry entry : group.entries) {
            if (entry.isVisible()) return true;
        }
        return false;
    }

    private void drawNav(CFontRenderer fr, int sideX, int sideY, int sideW, int sideH,
                         int mouseX, int mouseY) {
        if (!wideLayout()) {
            drawNavChips(fr, sideX, sideY, sideW, mouseX, mouseY);
            return;
        }
        // 列分隔就是侧栏容器自己的 border-r，贴着它的右边界、和容器一样高。
        drawRoundedRect(sideX + sideW - 1, sideY, 1, sideH, 1, structuralDivider());

        beginScissor(sideX, sideY, sideW - 1, sideH - SIDEBAR_PB);
        try {
            int labelHash = 0;
            for (int i = 0; i < navItems.size(); i++) {
                NavItem item = navItems.get(i);
                if (item.y + item.h < sideY || item.y > sideY + sideH) continue;
                if (item.kind == 0) {
                    String label = navGroupLabel(item.index);
                    if (label.hashCode() != labelHash) {
                        labelHash = label.hashCode();
                        // 分组标签：px-1 text-xs font-bold uppercase tracking-widest opacity-50。
                        // 标签是中文，uppercase 是空操作；tracking-widest 的 .1em 字距要自己加。
                        // 标签盒与按钮之间还有组内的 gap-1(4)，所以这一格的下缘在按钮上方 4px。
                        CFontRenderer labelFr = FontManager.pf12bold != null ? FontManager.pf12bold : fr;
                        int labelTop = item.y - NAV_CAT_GAP - NAV_LABEL_H;
                        drawTracked(labelFr, label, item.x + 4,
                                labelTop + (NAV_LABEL_H - labelFr.getFontHeight() * .5f) * .5f,
                                fade(textSecondary(), 0.5f), NAV_LABEL_TRACKING);
                    }
                    drawNavCategory(fr, item, HudSettingsModel.categories()[item.index],
                            item.index == clampCategory(), mouseX, mouseY);
                } else {
                    drawNavToc(fr, item, HudSettingsModel.categories()[clampCategory()],
                            mouseX, mouseY);
                }
            }
        } finally {
            endScissor();
        }

        // 侧栏自己是 overflow-y-auto，所以溢出时有自己的一档 8px 滚动条（folio 全局那一套）。
        // 轨道占满整列：{@code pb-4} 在滚动内容**之内**，不是可视区的一部分（内容高已经算进
        // navContentHeight 了）。
        if (navContentHeight > sideH) {
            int thumbH = Math.max(24, (int) ((double) sideH * sideH / navContentHeight));
            float thumbY = sideY + (sideH - thumbH)
                    * clamp01(navScroll / (float) Math.max(1, navContentHeight - sideH));
            int barX = sideX + sideW - 1 - SCROLLBAR_W;
            boolean over = isInside(mouseX, mouseY, barX, sideY, SCROLLBAR_W, sideH);
            drawRoundedRect(barX, sideY, SCROLLBAR_W, sideH, 0, scrollTrack());
            drawRoundedRect(barX, thumbY, SCROLLBAR_W, thumbH, 4,
                    named("nav.scrollbar", over) > 0.3f ? scrollThumbHover() : scrollThumb());
        }
    }

    /**
     * 窄屏那条横带：{@code border-b border-white/10 pb-3 mb-2}，chip 之间 {@code space-x-2}。
     * 源码挂着 {@code mobile-hide-scrollbar}，所以这条带在窄屏下**没有**可见滚动条，只能拖。
     */
    private void drawNavChips(CFontRenderer fr, int sideX, int sideY, int sideW,
                              int mouseX, int mouseY) {
        HudSettingsModel.Category[] categories = HudSettingsModel.categories();
        int selected = clampCategory();
        beginScissor(sideX, sideY, sideW, CHIP_H);
        try {
            for (NavItem item : navItems) {
                if (item.x + item.w < sideX || item.x > sideX + sideW) continue;
                drawNavChip(fr, item, categories[item.index], item.index == selected,
                        mouseX, mouseY);
            }
        } finally {
            endScissor();
        }
        drawRoundedRect(sideX, sideY + CHIP_H + 12, sideW, 1, 1, structuralDivider());
    }

    /** chip：{@code p-2 rounded-xl border gap-2}，图标恒 70% 透明、文字恒 100%，只底色/边随状态变。 */
    private void drawNavChip(CFontRenderer fr, NavItem item, HudSettingsModel.Category category,
                             boolean current, int mouseX, int mouseY) {
        boolean hovered = !closing && isInside(mouseX, mouseY, item.x, item.y, item.w, item.h);
        float hp = named("chip." + item.index, hovered && !current);
        drawRoundedRect(item.x, item.y, item.w, item.h, 12,
                current ? sideSelectedBg() : fade(sideHover(), hp));
        if (current) {
            drawRoundedOutline(item.x, item.y, item.w, item.h, 12, 1, sideSelectedEdge());
        }
        drawEntryIcon(FontManager.settingsIcon32, category.icon, item.x + 8 + 9,
                item.y + item.h / 2.0, 18, fade(textPrimary(), 0.7f));
        drawLineText(FontManager.pf14 != null ? FontManager.pf14 : fr, category.title,
                item.x + 8 + 18 + 8, item.y + 8, ROW_LABEL_H, textPrimary());
    }

    private void drawNavCategory(CFontRenderer fr, NavItem item, HudSettingsModel.Category category,
                                 boolean current, int mouseX, int mouseY) {
        boolean hovered = !closing && isInside(mouseX, mouseY, item.x, item.y, item.w, item.h);
        float hp = named("nav." + item.index, hovered && !current);
        if (current) {
            drawRoundedRect(item.x, item.y, item.w, item.h, 12, sideSelectedBg());
            drawRoundedOutline(item.x, item.y, item.w, item.h, 12, 1, sideSelectedEdge());
        } else if (hp > 0.02f) {
            drawRoundedRect(item.x, item.y, item.w, item.h, 12, fade(sideHover(), hp));
        }
        // 内容格：按钮自己的 1px 边 + {@code p-3}(12) 之后那 24px 高的行盒。
        int cellTop = item.y + 13;
        // folia 侧栏：图标 18px、单色 var(--text-primary)、opacity-70，不带分类自己的强调色。
        drawEntryIcon(FontManager.settingsIcon32, category.icon, item.x + 22, item.y + item.h / 2.0,
                18, current ? textPrimary() : fade(textPrimary(), 0.7f));
        // 文字是 text-sm font-medium（14/20），左边 1+12+18+12=43。folia 这行右侧什么都没有：
        // 按钮里只有一个 iconTextRow 子元素，justify-between 因此不产生任何东西。
        CFontRenderer name = FontManager.pf14 != null ? FontManager.pf14 : FontManager.pf12;
        drawLineText(name, name == null ? category.title
                        : name.trim(category.title, Math.max(20, item.w - 43 - 13)),
                item.x + 43, cellTop + 2, ROW_LABEL_H,
                current ? textPrimary() : mix(fade(textPrimary(), 0.7f), textPrimary(), hp));
    }

    private void drawNavToc(CFontRenderer fr, NavItem item, HudSettingsModel.Category category,
                            int mouseX, int mouseY) {
        HudSettingsModel.Group[] groups = category.groups;
        if (item.index < 0 || item.index >= groups.length) return;
        if (!wideLayout()) return;
        boolean active = activeAnchor == item.index;
        boolean hovered = !closing && isInside(mouseX, mouseY, item.x, item.y, item.w, item.h);
        // 底色：选中才是实底，未选中只有 hover 一档，两者不叠加（源码是三元分支不是混合）。
        float ap = named("toc.on." + item.index, active);
        float hp = named("toc.hv." + item.index, hovered && !active);
        if (ap > 0.02f) {
            drawRoundedRect(item.x, item.y, item.w, item.h, 8, fade(tocBg(), ap));
        } else if (hp > 0.02f) {
            drawRoundedRect(item.x, item.y, item.w, item.h, 8, fade(tocHover(), hp));
        }
        // 指示条只有 opacity 0↔1（150ms），没有位移动画：每行各画一条，绝不能做成滑动指示器。
        // inset-y-1 = 上下各 4px，left-0 贴着按钮自己的左边（也就是侧栏内容的 26px 处）。
        if (ap > 0.02f) {
            drawRoundedRect(item.x, item.y + TOC_BAR_INSET, TOC_BAR_W,
                    item.h - TOC_BAR_INSET * 2, TOC_BAR_W * 0.5f, fade(accent(), ap));
        }
        // 文字 opacity 恒为 0.55 / 0.95 两档（源码写在 style 里，跟 hover 无关）。
        // {@code py-1.5}(6) + text-xs 行盒 16，左偏移是按钮自己的 {@code pl-3}。
        drawLineText(fr, fr == null ? groups[item.index].title
                        : fr.trim(groups[item.index].title, Math.max(20, item.w - 12 - 8)),
                item.x + 12, item.y + 6, ROW_DESC_H,
                mix(fade(textPrimary(), 0.55f), fade(textPrimary(), 0.95f), ap));
    }

    // ==================== 内容列 ====================

    private void drawContent(CFontRenderer fr, int contentX, int listY, int contentW, int listH,
                             int wheel, boolean overContent, int mouseX, int mouseY) {
        HudSettingsModel.Category active = HudSettingsModel.categories()[clampCategory()];

        if (contentShownCategory != clampCategory()) {
            contentShownCategory = clampCategory();
            activeAnchor = -1;
        }
        layoutRows(contentX, listY, contentW, active);
        int maxScroll = Math.max(0, contentHeight - listH);
        lastMaxScroll = maxScroll;
        if (wheel != 0 && overContent && !closing) {
            // 滚轮是原生直接位移：不补间。顺带解掉点击锁（folia 在 wheel/touchstart/pointerdown
            // 上都是立刻释放，不是等 800ms 到点）。
            setScroll(scroll() + (wheel > 0 ? -WHEEL_STEP : WHEEL_STEP), maxScroll);
            clickLockUntil = 0L;
            snapScroll = true;
        } else {
            setScroll(scroll(), maxScroll);
        }
        int wanted = scroll();
        if (snapScroll) {
            snapScroll = false;
            displayScroll = wanted;
            glideFrom = wanted;
            glideTarget = wanted;
        } else if (wanted != glideTarget) {
            // 只有 programmatic 的跳转才补间（网页上是 behavior:'smooth'）。时长随距离走，
            // 夹在 150~500ms，量级取自原生平滑滚动。
            glideFrom = displayScroll;
            glideTarget = wanted;
            glideStart = System.currentTimeMillis();
            glideMs = Math.max(150, Math.min(500, 120 + Math.abs((int) (glideTarget - glideFrom)) / 3));
        }
        if (displayScroll != glideTarget) {
            float g = clamp01((System.currentTimeMillis() - glideStart) / (float) Math.max(1, glideMs));
            displayScroll = g >= 1f ? glideTarget : glideFrom + (glideTarget - glideFrom) * easeOut(g);
        }
        layoutRows(contentX, listY, contentW, active);

        // scrollspy：判定线就是容器顶 + 24% 容器高，**不掺落点留白**（那 12px 只属于落点，
        // 混进来会让高亮比 folia 早一格）。一条都没越过判定线时取第一条 —— 源码里
        // `let current = nodes[0]`，刚打开时第一节就是高亮的那一节，不是「谁都不高亮」。
        // 滚到底时取最后一个锚点，容差也不自加。
        if (System.currentTimeMillis() >= clickLockUntil) {
            int line = listY + (int) (listH * ACTIVE_LINE_RATIO);
            int found = -1;
            for (Row row : rows) {
                if (row.anchor < 0) continue;
                if (found < 0) found = row.anchor;
                if (row.y <= line) found = row.anchor;
            }
            if (displayScroll >= maxScroll - 2 && maxScroll > 0) {
                for (Row row : rows) {
                    if (row.anchor >= 0) found = row.anchor;
                }
            }
            if (found >= 0) activeAnchor = found;
        }

        // 裁剪就收在容器自己的右边界，原来多放的 10px 会让长说明文字压到面板内边距上。
        beginScissor(contentX, listY, contentW, listH);
        float outerAlpha = layerAlpha;
        try {
            SkijaGfx.pushMatrix();
            try {
                drawSectionHeader(fr, active, contentX, listY - Math.round(displayScroll),
                        contentW, mouseX, mouseY);
                for (Card card : cards) {
                    if (card.y + card.h < listY - 4 || card.y > listY + listH + 4) continue;
                    drawRoundedRect(card.x, card.y, card.w, card.h, CARD_RADIUS, cardBg());
                    drawRoundedOutline(card.x, card.y, card.w, card.h, CARD_RADIUS, 1, cardEdge());
                }
                for (Row row : rows) {
                    if (row.y + row.h < listY - 4 || row.y > listY + listH + 4) continue;
                    drawRow(fr, row, mouseX, mouseY);
                }
            } finally {
                SkijaGfx.popMatrix();
            }
        } finally {
            layerAlpha = outerAlpha;
            endScissor();
        }

        if (maxScroll > 0) {
            // folia 全局滚动条：8px 宽、thumb 4px 圆角，轨道/thumb/thumb-hover 三档都是实色。
            // 轨道贴容器右边缘，thumb 占满这 8px —— 原来那档「收细以免抢戏」是自作主张。
            int trackTop = listY;
            int trackH = listH;
            int thumbH = Math.max(24, trackH * listH / Math.max(1, contentHeight));
            float thumbY = trackTop + (trackH - thumbH) * clamp01(displayScroll / maxScroll);
            // 滚动条占的是**内容盒**右侧那 8px，也就是 `pr-4` 之内 —— 原来把它画到了内衬上，
            // 整条往右挪了 16px，压到面板内边距上。
            drawRoundedRect(contentX + contentW, trackTop, SCROLLBAR_W, trackH, 0,
                    scrollTrack());
            drawRoundedRect(contentX + contentW, thumbY, SCROLLBAR_W, thumbH, 4,
                    named("scrollbar", overContent) > 0.3f ? scrollThumbHover() : scrollThumb());
        }
    }

    /**
     * 分区头（{@code SettingsSectionHeader}）：标题 {@code text-xl}(28 行高) + 描述
     * {@code text-xs mt-1}(16) + {@code pb-4} + 一条 {@code border-white/10} + {@code mb-6}。
     * 带主题选择的那一类右侧多一枚 36px 圆钮，用来翻日/夜。
     */
    private void drawSectionHeader(CFontRenderer fr, HudSettingsModel.Category active,
                                   int contentX, int top, int contentW, int mouseX, int mouseY) {
        // {@code text-lg md:text-xl}：窄屏 18、宽屏 20，两档的行高都是 28，所以占位不变。
        CFontRenderer big = wideLayout() ? FontManager.pf20bold : FontManager.pf18bold;
        CFontRenderer title = big != null ? big : FontManager.pf20bold;
        if (title == null) title = fr;
        drawLineText(title, active.title, contentX, top, HEAD_TITLE_H, textPrimary());
        if (active.subtitle != null && !active.subtitle.isEmpty()) {
            drawLineText(fr, active.subtitle, contentX,
                    top + HEAD_TITLE_H + HEAD_DESC_MT, HEAD_DESC_H, fade(textSecondary(), 0.5f));
        }
        drawRoundedRect(contentX, top + headLineY(), contentW, 1, 1, structuralDivider());

        if (!showDaylightToggle(active)) return;
        int[] box = daylightButtonRect(contentX, top, contentW);
        float hover = named("header.daylight", !closing
                && isInside(mouseX, mouseY, box[0], box[1], box[2], box[3]));
        // utilityGhostButtonClass 的三档底色 + transition-colors 150ms。
        drawRoundedRect(box[0], box[1], box[2], box[3], box[3] * 0.5f,
                mix(ghostBg(), ghostHover(), hover));
        drawRoundedOutline(box[0], box[1], box[2], box[3], box[3] * 0.5f, 1, ghostEdge());
        drawLucide(daylight() ? FoliaLucideIcons.SUN : FoliaLucideIcons.MOON,
                box[0] + box[2] / 2.0, box[1] + box[3] / 2.0, DAYLIGHT_ICON,
                daylight() ? SUNSET_AMBER : FROST_BLUE);
    }

    /** {@code text-amber-500} / {@code text-blue-300}：日/夜钮自己的两枚色，不跟随主题。 */
    private static final int SUNSET_AMBER = 0xFFF59E0B;
    private static final int FROST_BLUE = 0xFF93C5FD;

    /** 日/夜钮：内容列右边缘往里 16px（{@code gap-4}），顶端与标题行盒对齐。 */
    private int[] daylightButtonRect(int contentX, int top, int contentW) {
        return new int[] { contentX + contentW - DAYLIGHT_BOX, top, DAYLIGHT_BOX, DAYLIGHT_BOX };
    }

    /** 只有摆了主题选择的那一类才配日/夜钮（folia 把它挂在 appearance 分区上）。 */
    private boolean showDaylightToggle(HudSettingsModel.Category category) {
        for (HudSettingsModel.Group group : category.groups) {
            for (HudSettingsModel.Entry entry : group.entries) {
                if (entry.kind == HudSettingsModel.Kind.THEME_GRID) return true;
            }
        }
        return false;
    }

    /** 上一档暗色主题：翻回日间时记住它，再翻回来不丢选择。 */
    private NCMTheme.ThemePreset lastDarkPreset = NCMTheme.ThemePreset.MIDNIGHT_GRAPHITE;

    private void toggleDaylight() {
        NCMTheme.ThemePreset current = NCMTheme.getCurrent();
        if (NCMTheme.isLightPreset()) {
            NCMTheme.setCurrent(lastDarkPreset);
        } else {
            if (current != null) lastDarkPreset = current;
            NCMTheme.setCurrent(NCMTheme.ThemePreset.PEARL_LIGHT);
        }
    }

    /** 把当前分类算成行表（约束 1：绘制与命中共用唯一坐标来源）。 */
    private void layoutRows(int contentX, int listY, int contentW,
                            HudSettingsModel.Category active) {
        rows.clear();
        cards.clear();
        int top = listY - Math.round(displayScroll);
        int cursor = top + contentHeadH();
        int bottom = cursor;

        int anchorIndex = 0;
        for (HudSettingsModel.Group group : active.groups) {
            List<HudSettingsModel.Entry> visible = new ArrayList<HudSettingsModel.Entry>();
            for (HudSettingsModel.Entry entry : group.entries) {
                if (entry.isVisible()) visible.add(entry);
            }
            if (visible.isEmpty()) continue;
            if (anchorIndex < anchorTops.length) anchorTops[anchorIndex] = cursor - top;
            rows.add(new Row(null, group.title, group.icon, active.accent,
                    contentX, cursor, contentW, GROUP_HEADER_H, false, false, anchorIndex));
            cursor += GROUP_HEADER_H;
            anchorIndex++;

            cursor = layoutBlocks(contentX, cursor, contentW, visible, group.icon);
            bottom = cursor;
            cursor += CARD_GAP;
        }
        contentHeight = bottom - top + CONTENT_PB;
    }

    /**
     * 一个锚点内部的若干块。以下形状是 {@code .scratch/本项目/dev/shot-settings.mjs}
     * 在真实浏览器里量出来的（不是读 className 推的）：
     *
     * <ul>
     * <li>一个锚点基本就是**一张内衬卡** {@code p-4 rounded-xl border}，子块按
     * {@code space-y-4}(16) 排 —— 实测卡顶 391、第一条内容 408（16 内衬 + 1 边框）、
     * 第二块 480（464 + 16）；</li>
     * <li>「左标签右控件」那两种行（开关、色块）相邻时，后一行自己再带一层
     * {@code border-t pt-4}，于是两行内容之间是 16 + 1 + 16；</li>
     * <li>滑条 / 选项 / 主题槽这类**控件铺满整行**的块：标签在上、控件在下，中间
     * {@code space-y-3}(12)，并且**不画**那条分隔线；</li>
     * <li>「进入子面板」那条大按钮不属于任何卡，是与卡并列的兄弟块；块与块之间
     * {@code space-y-3}(12)，最后一块后面不留；</li>
     * <li>一组**命令药丸**（{@code HudSettingsModel.Entry#inlineButton}）是卡内的最后一块：
     * {@code flex flex-wrap gap-2} 从左往右排，放不下才换行，行高就是药丸高。见 folia
     * 「分析模型」下面那排「从本机查找 / 更改位置 / 删除所有模型」。</li>
     * </ul>
     */
    private int layoutBlocks(int contentX, int cursor, int contentW,
                             List<HudSettingsModel.Entry> visible, String groupIcon) {
        int innerW = contentW - PADDED_INSET * 2;
        int innerLeft = contentX + PADDED_INSET;
        int cardTop = -1;
        boolean previousPlain = false;
        boolean any = false;
        // 药丸那一排：flowX<0 表示当前不在排里；换行只把 cursor 推到上一排的下沿。
        int flowX = -1;
        int flowLineTop = 0;
        for (int i = 0; i < visible.size(); i++) {
            HudSettingsModel.Entry entry = visible.get(i);
            if (entry.kind == HudSettingsModel.Kind.ACTION && entry.inlineButton) {
                if (cardTop < 0) {
                    if (any) cursor += CARD_BLOCK_GAP;
                    any = true;
                    cardTop = cursor;
                    cursor += PADDED_INSET;
                } else if (flowX < 0) {
                    cursor += CARD_INNER_GAP;
                }
                if (flowX < 0) {
                    flowX = innerLeft;
                    flowLineTop = cursor;
                }
                int w = Math.min(buttonPreferredWidth(entry.getLabel(), groupIcon), innerW);
                if (flowX > innerLeft && flowX + w > innerLeft + innerW) {
                    flowLineTop = flowLineTop + BUTTON_H + BUTTON_FLOW_GAP;
                    flowX = innerLeft;
                }
                rows.add(new Row(entry, entry.getLabel(), groupIcon, 0,
                        flowX, flowLineTop, w, BUTTON_H, false, true, -1));
                flowX += w + BUTTON_FLOW_GAP;
                previousPlain = false;
                if (!isButtonRunEnding(visible, i)) {
                    continue;
                }
                cursor = flowLineTop + BUTTON_H;
                flowX = -1;
                continue;
            }
            if (flowX >= 0) {
                cursor = flowLineTop + BUTTON_H;
                flowX = -1;
            }
            int h = entry.kind == HudSettingsModel.Kind.ACTION
                    ? rowHeight(entry, cardTop < 0 ? contentW : innerW)
                    : rowHeight(entry, contentW);
            if (entry.kind == HudSettingsModel.Kind.ACTION) {
                previousPlain = false;
                if (cardTop < 0) {
                    // 分组一上来就是动作行：folia {@code StorageSettingsSection} 那一档，
                    // 与卡并列的独立大块。
                    if (any) cursor += CARD_BLOCK_GAP;
                    any = true;
                    rows.add(new Row(entry, entry.getLabel(), groupIcon, 0,
                            contentX, cursor, contentW, h, false, false, -1));
                    cursor += h;
                    continue;
                }
                // 卡里已经有内容了，动作行就嵌在卡内当一层子卡 —— folia「分析模型」里那三行
                // 模型状态就是嵌在卡里的，外面还套着它们自己的那张卡。
                cursor += NESTED_ACTION_GAP;
                rows.add(new Row(entry, entry.getLabel(), groupIcon, 0,
                        innerLeft, cursor, innerW, h, false, true, -1));
                cursor += h;
                continue;
            }
            boolean plain = isPlainRow(entry.kind);
            if (cardTop < 0) {
                if (any) cursor += CARD_BLOCK_GAP;
                any = true;
                cardTop = cursor;
                cursor += PADDED_INSET;
            } else {
                cursor += CARD_INNER_GAP;
            }
            boolean sep = plain && previousPlain;
            if (sep) cursor += ROW_SEP_PT + ROW_DIVIDER;
            rows.add(new Row(entry, entry.getLabel(), null, 0,
                    innerLeft, cursor, innerW, h, sep, true, -1));
            cursor += h;
            previousPlain = plain;
        }
        if (flowX >= 0) {
            cursor = flowLineTop + BUTTON_H;
        }
        return closePaddedCard(contentX, contentW, cardTop, cursor);
    }

    /** 这一枚药丸是不是那一排的最后一枚。 */
    private static boolean isButtonRunEnding(List<HudSettingsModel.Entry> visible, int index) {
        if (index + 1 >= visible.size()) return true;
        HudSettingsModel.Entry next = visible.get(index + 1);
        return !(next.kind == HudSettingsModel.Kind.ACTION && next.inlineButton);
    }

    /** 药丸的宽度：左右内衬 + 图标 + 间隙 + 文字。挑不到图标时不留图标那一段。 */
    private int buttonPreferredWidth(String label, String groupGlyph) {
        CFontRenderer font = FontManager.pf12bold != null ? FontManager.pf12bold : FontManager.pf12;
        int text = font == null || label == null ? 0 : font.getStringWidth(label);
        int icon = buttonIcon(label, groupGlyph) == null ? 0 : BUTTON_ICON + BUTTON_ICON_GAP;
        return BUTTON_PAD_X * 2 + icon + text;
    }

    /**
     * 药丸左侧那枚图标：照标签里的动作词挑，挑不到退回分组图标。
     *
     * <p>folia 的按钮是逐个组件写死图标的，本工程的条目声明里没有这一项；与其给每个动作硬编
     * 一张表，不如按动词挑 —— 五个动词覆盖了现在全部的动作条目，也不会随分组变化而错位。</p>
     */
    private static String buttonIcon(String label, String groupGlyph) {
        if (label == null) return null;
        if (label.contains("删除")) return FoliaLucideIcons.TRASH;
        if (label.contains("查找") || label.contains("扫描")) return FoliaLucideIcons.SEARCH;
        if (label.contains("位置") || label.contains("目录")) return FoliaLucideIcons.FOLDER;
        if (label.contains("恢复")) return FoliaLucideIcons.RESTORE;
        if (label.contains("清理")) return FoliaLucideIcons.SWEEP;
        return groupGlyph == null ? null : LUCIDE_BY_GLYPH.get(groupGlyph);
    }

    /** 收掉正在累积的内衬卡：卡的下缘是最后一块的下缘再加一层 {@code p-4} + 边框。 */
    private int closePaddedCard(int contentX, int contentW, int cardTop, int cursor) {
        if (cardTop < 0) return cursor;
        cards.add(new Card(contentX, cardTop, contentW, cursor + PADDED_INSET - cardTop));
        return cursor + PADDED_INSET;
    }

    /** 「左标签右控件」那一形：开关与色块。它们相邻之间才有那条 {@code border-t}。 */
    private static boolean isPlainRow(HudSettingsModel.Kind kind) {
        return kind == HudSettingsModel.Kind.TOGGLE || kind == HudSettingsModel.Kind.COLOR;
    }

    /**
     * 标签块的高：标题 {@code text-sm}(20)，带说明再加 {@code space-y-1}(4) 与若干行
     * {@code text-xs}(16)。folia 的说明是**换行**不是省略号，所以行数按宽度算出来。
     */
    private int rowTextHeight(HudSettingsModel.Entry entry, int textW) {
        List<String> lines = descLines(entry, textW);
        return lines.isEmpty() ? ROW_LABEL_H : ROW_LABEL_H + ROW_DESC_MT + DESC_LINE_H * lines.size();
    }

    /** 说明文字按 {@code max-w-[420px]} 那一档折行，最多两行，装不下的最后一行以省略号收。 */
    private List<String> descLines(HudSettingsModel.Entry entry, int textW) {
        List<String> out = new ArrayList<String>(DESC_MAX_LINES);
        String description = entry.description;
        CFontRenderer fr = FontManager.pf12;
        if (description == null || description.isEmpty() || fr == null || textW <= 8) {
            return out;
        }
        if (entry.kind == HudSettingsModel.Kind.CHOICE && entry.hasChoiceHints()) {
            // 这一档的说明已经拆进每个选项格子里了（folia 的大瓦片就是标题 + 逐项说明），
            // 再在标题下面画一遍就是把同一句话摆两处。搜索与另外两套界面照旧读 description。
            return out;
        }
        int width = Math.min(DESC_MAX_W, textW);
        if (fr.getStringWidth(description) <= width) {
            out.add(description);
            return out;
        }
        int start = 0;
        while (out.size() < DESC_MAX_LINES) {
            int end = start;
            int lastBreak = -1;
            while (end < description.length()
                    && fr.getStringWidth(description.substring(start, end + 1)) <= width) {
                char ch = description.charAt(end);
                if (ch == ' ' || ch == '，' || ch == '、' || ch == '；' || ch == '。') {
                    lastBreak = end + 1;
                }
                end++;
            }
            if (end >= description.length()) {
                out.add(description.substring(start).trim());
                return out;
            }
            // 优先在标点/空格后断；中文句子常常没有，那就硬断在放不下的那个字之前。
            int cut = lastBreak > start ? lastBreak : Math.max(end, start + 1);
            out.add(description.substring(start, cut).trim());
            start = cut;
        }
        String last = out.remove(out.size() - 1);
        out.add(fr.trim(last + "…", width));
        return out;
    }

    /** 控件那一格的高（铺满型在它自己那一块里独占；并列型与标签同一行、右侧对齐）。 */
    private int blockControlHeight(HudSettingsModel.Entry entry) {
        switch (entry.kind) {
            case SLIDER:
                return RANGE_H;
            case CHOICE:
                return isSegmented(entry) ? choiceStackHeight(entry) : PILL_H;
            case COLOR:
                return SWATCH_H;
            case TOGGLE:
                return SWITCH_H;
            default:
                return 0;
        }
    }

    /**
     * 选项少到能一眼看完，就用 folia 的分段瓦片（{@code grid grid-cols-2 gap-2} 那一档）；
     * 多了才退回 {@code CustomSelect} 那根铺满的下拉触发器。
     */
    private static boolean isSegmented(HudSettingsModel.Entry entry) {
        String[] options = entry.getChoices();
        return options != null && options.length > 0 && options.length <= 4;
    }

    /**
     * 一块的高。并列型是「文字块」与「控件」取大；铺满型是文字块 + {@code space-y-3} + 控件。
     * 卡自己的 {@code p-4} 与边框由 {@link #layoutBlocks} 在卡的两端各加一次，不在这里。
     */
    private int rowHeight(HudSettingsModel.Entry entry, int contentW) {
        if (entry.kind == HudSettingsModel.Kind.ACTION) {
            if (entry.inlineButton) return BUTTON_H;
            // 独立小卡：p-3 两侧 + max(32 的图标底板, 文字块) + 上下边框。文字块要扣掉底板与
            // 尾巴上那枚 Chevron 占的宽度。
            int text = rowTextHeight(entry, Math.min(DESC_MAX_W,
                    contentW - CARD_PAD * 2 - ACTION_CHIP - 12 - 26));
            return CARD_PAD * 2 + Math.max(ACTION_CHIP, text) + CARD_BORDER * 2;
        }
        int textW = contentW - PADDED_INSET * 2;
        int text = rowTextHeight(entry, isPlainRow(entry.kind)
                ? plainTextWidth(entry, textW) : textW);
        if (isPlainRow(entry.kind)) {
            return Math.max(text, blockControlHeight(entry));
        }
        if (entry.kind == HudSettingsModel.Kind.THEME_GRID) {
            return text + SLIDER_HEAD_GAP + themeGridHeight(textW);
        }
        return text + SLIDER_HEAD_GAP + blockControlHeight(entry);
    }

    private void drawRow(CFontRenderer fr, Row row, int mouseX, int mouseY) {
        if (row.isGroupHeader()) {
            drawGroupHeading(fr, row);
            return;
        }

        boolean hovered = !closing && isInside(mouseX, mouseY, row.x, row.y, row.w, row.h);
        String rowKey = "row." + System.identityHashCode(row.entry);
        // 按下态只在真的按住且还在这一行时成立（CSS 的 :active 就是这个条件）。
        float press = named("press." + rowKey, rowKey.equals(pressedTarget) && hovered);

        if (row.entry.kind == HudSettingsModel.Kind.ACTION) {
            if (row.entry.inlineButton) {
                drawActionPill(row, rowKey, hovered);
            } else {
                drawActionStrip(fr, row, rowKey, hovered);
            }
            return;
        }
        if (row.sep) {
            // 后一行自己带 {@code border-t pt-4}：线在内容之上 16px 处，不是贴着内容。
            drawRoundedRect(row.x, row.y - ROW_SEP_PT - ROW_DIVIDER, row.w, ROW_DIVIDER,
                    ROW_DIVIDER, divider());
        }
        boolean plain = isPlainRow(row.entry.kind);
        int textW = plain ? plainTextWidth(row) : row.w;
        // 并列行是 {@code items-center}：文字块在行里垂直居中；铺满型是标签在上、控件在下。
        int top = plain ? row.y + (row.h - rowTextHeight(row.entry, textW)) / 2 : row.y;
        drawLabelStack(fr, row.entry, row.x, top, textW);

        int[] box = blockRect(row);
        switch (row.entry.kind) {
            case TOGGLE: {
                // 子视图那一档：轨道上色与旋钮位移都是 150ms，没有 hover 提亮、没有按下缩放。
                float knob = named("knob." + rowKey, row.entry.isOn());
                int[] sw = switchRect(row);
                drawSwitch(sw[0], sw[1], knob);
                break;
            }
            case COLOR:
                drawColorBlock(row, controlRect(row),
                        named("swatch." + rowKey, hovered), press);
                break;
            case SLIDER:
                drawSliderValue(fr, row, box);
                drawRangeControl(fr, row.entry.setting, box);
                break;
            case CHOICE: {
                boolean open = openChoice == row.entry;
                if (isSegmented(row.entry)) {
                    drawChoiceTiles(fr, row, mouseX, mouseY);
                } else {
                    // 边框/外环走默认 150ms，箭头单独是 duration-200 的 transform —— 两档时长
                    // 不同，合成一个进度就会有一边不对。
                    drawChoicePill(fr, row, named(rowKey + ".open", open),
                            transition(rowKey + ".chevron", open, CHEVRON_MS, true));
                }
                break;
            }
            case THEME_GRID:
                drawThemeChips(box[0], box[1], box[2], mouseX, mouseY);
                break;
            default:
                break;
        }
        // 手机档锁死行：整行罩一层底色纱（文字、控件一起变灰），点击在 activateRow 已挡。
        if (row.entry.isLocked()) {
            drawRoundedRect(row.x, row.y, row.w, row.h, 10, 0x96141A22);
        }
    }

    /** 标题 {@code text-sm font-medium} + 说明 {@code text-xs opacity-50}（{@code space-y-1}）。 */
    private void drawLabelStack(CFontRenderer fr, HudSettingsModel.Entry entry,
                                int x, int top, int textW) {
        drawLineText(FontManager.pf14, entry.getLabel(), x, top, ROW_LABEL_H, textPrimary());
        List<String> lines = descLines(entry, textW);
        CFontRenderer small = FontManager.pf12;
        for (int i = 0; i < lines.size(); i++) {
            drawLineText(small, lines.get(i), x, top + ROW_LABEL_H + ROW_DESC_MT
                    + i * DESC_LINE_H, DESC_LINE_H, fade(textSecondary(), 0.5f));
        }
    }

    /** 并列行里文字那一栏能占多宽：{@code justify-between} 加 {@code gap-4}，右边留给控件。 */
    private int plainTextWidth(Row row) {
        return plainTextWidth(row.entry, row.w);
    }

    private int plainTextWidth(HudSettingsModel.Entry entry, int rowW) {
        int control = blockControlHeight(entry);
        if (entry.kind == HudSettingsModel.Kind.COLOR) {
            control += 66;      // 色块左边还站着那串十六进制读数
        }
        return Math.max(20, Math.min(DESC_MAX_W, rowW - control - 16));
    }

    /**
     * 控件那一格的矩形（约束 1：绘制、命中、下拉定位共用）。标签与控件之间是
     * {@code space-y-3}(12)；并列型的控件不在这里，它贴着行的右边缘。
     */
    private int[] blockRect(Row row) {
        return new int[] { row.x, row.y + rowTextHeight(row.entry, row.w) + SLIDER_HEAD_GAP,
                row.w, blockControlHeight(row.entry) };
    }

    /** 锚点标题（{@code SettingsSectionHeading}）：14px 图标 + {@code gap-2} + 14 号加粗大字距。 */
    private void drawGroupHeading(CFontRenderer fr, Row row) {
        int boxTop = row.y;
        drawEntryIcon(FontManager.settingsIcon24, row.icon, row.x + GROUP_HEADING_ICON / 2.0,
                boxTop + ROW_LABEL_H / 2.0, GROUP_HEADING_ICON, fade(textSecondary(), 0.5f));
        CFontRenderer heading = FontManager.pf14bold != null ? FontManager.pf14bold : fr;
        drawTracked(heading, row.title, row.x + GROUP_HEADING_ICON + GROUP_HEADING_GAP,
                boxTop + (ROW_LABEL_H - heading.getFontHeight() * .5) * .5,
                fade(textSecondary(), 0.5f), GROUP_HEADING_TRACKING);
    }

    /**
     * 「会去做一件事」那一行（扫描模型、清理缓存、恢复默认位置）。
     *
     * <p>照 folia 的 {@code StorageSettingsSection}/{@code DeveloperSettingsSubview}：一张
     * {@code p-3 rounded-xl border} 的独立小卡，里面 {@code flex items-center gap-3} ——
     * 左边一枚 {@code p-2 rounded-lg} 的图标底板（{@code bg-white/8 border-white/10}、图标 16、
     * 整块 opacity-60），中间 {@code text-sm font-medium} + {@code text-[11px] opacity-50}，
     * 右边一个 {@code ChevronRight size=18 opacity-60}。hover 是整行 {@code hover:bg-white/8}。</p>
     *
     * <p>原先这里画的是 Appearance 那条 {@code border-2} 渐变描边大按钮 —— 但那条在 folia 里
     * 专指「进子页面」，本界面的这些行都是就地执行一件事，用它等于自创语义。</p>
     */
    private void drawActionStrip(CFontRenderer fr, Row row, String rowKey, boolean hovered) {
        float hover = named(rowKey + ".hover", hovered);
        drawRoundedRect(row.x, row.y, row.w, row.h, CARD_RADIUS,
                mix(cardBg(), cardHover(), hover));
        drawRoundedOutline(row.x, row.y, row.w, row.h, CARD_RADIUS, 1, cardEdge());

        int chip = ACTION_CHIP;
        int chipX = row.x + CARD_PAD;
        int chipY = row.y + (row.h - chip) / 2;
        drawRoundedRect(chipX, chipY, chip, chip, 8, iconChipBg());
        drawRoundedOutline(chipX, chipY, chip, chip, 8, 1, iconChipEdge());
        drawEntryIcon(FontManager.settingsIcon24, row.icon, chipX + chip / 2.0,
                row.y + row.h / 2.0, ACTION_ICON, fade(textPrimary(), 0.6f));

        int textX = chipX + chip + 12;
        int textRight = row.x + row.w - CARD_PAD - 18 - 8;
        drawLabelStack(fr, row.entry, textX, row.y + (row.h - rowTextHeight(row.entry,
                textRight - textX)) / 2, textRight - textX);
        drawLucide(FoliaLucideIcons.CHEVRON_RIGHT, row.x + row.w - CARD_PAD - 9,
                row.y + row.h / 2.0, 18, fade(textSecondary(), 0.6f));
    }

    /**
     * 命令药丸：{@code rounded-lg border border-white/10 px-2 py-1.5 text-xs font-medium}
     * 加一枚 14 的图标，与同伴在一排里 {@code gap-2} 并排、放不下才换行。
     *
     * <p>静止态<b>没有底色</b> —— 实测药丸内部与卡底同为 white/5，只有那一圈 white/10 的边；
     * hover 才提一层白。破坏性那条（删除）只有图标与文字换成强调色，边还是那圈白，
     * 照的是「删除所有模型」那枚的实测像素。</p>
     */
    private void drawActionPill(Row row, String rowKey, boolean hovered) {
        float hover = named(rowKey + ".hover", hovered);
        if (hover > 0.01f) {
            drawRoundedRect(row.x, row.y, row.w, row.h, BUTTON_RADIUS, fade(cardHover(), hover));
        }
        drawRoundedOutline(row.x, row.y, row.w, row.h, BUTTON_RADIUS, 1, pillEdge());
        String label = row.title == null ? row.entry.getLabel() : row.title;
        boolean danger = label != null && label.contains("删除");
        int ink = danger ? accent() : textPrimary();
        int textX = row.x + BUTTON_PAD_X;
        String icon = buttonIcon(label, row.icon);
        if (icon != null) {
            drawLucide(icon, row.x + BUTTON_PAD_X + BUTTON_ICON / 2.0,
                    row.y + row.h / 2.0, BUTTON_ICON, fade(ink, danger ? 0.9f : 0.7f));
            textX += BUTTON_ICON + BUTTON_ICON_GAP;
        }
        CFontRenderer font = FontManager.pf12bold != null ? FontManager.pf12bold : FontManager.pf12;
        if (font == null || label == null) return;
        String text = font.trim(label, row.x + row.w - BUTTON_PAD_X - textX);
        drawLineText(font, text, textX, row.y + (row.h - ROW_DESC_H) / 2, ROW_DESC_H, ink);
    }

    private void drawChoicePill(CFontRenderer fr, Row row, float openProgress, float chevron) {
        String value = row.entry.getValueText();
        // 触发器矩形与菜单定位、命中判定共用同一个来源（约束 1）。
        int[] box = choiceTriggerRect(row);
        int x = box[0];
        int y = box[1];
        int w = box[2];
        // CustomSelect 的触发器没有底色（--overlay-medium 在源码里从未定义，于是 background-color
        // 无效 → 透明），闭合时的 1px 边框同理落到 currentColor —— 就是主文字色。
        drawRoundedOutline(x, y, w, PILL_H, 12, 1, mix(textPrimary(), accent(), openProgress));
        if (openProgress > 0.02f) {
            // 展开再补一圈 {@code 0 0 0 1px} 的强调色外环。
            drawRoundedOutline(x - 1, y - 1, w + 2, PILL_H + 2, 13, 1, fade(accent(), openProgress));
        }
        drawLineText(fr, fr == null ? value : fr.trim(value, w - PILL_PAD_X - 6 - PILL_CHEVRON),
                x + PILL_PAD_X, y + 12, ROW_LABEL_H, textPrimary());
        // folia：Chevron 16px、opacity-70、展开时 rotate-180，transform 是 duration-200。
        drawLucideRotated(FoliaLucideIcons.CHEVRON_DOWN, x + w - PILL_PAD_X, y + PILL_H / 2.0,
                PILL_CHEVRON, fade(textPrimary(), 0.7f), 180f * chevron);
    }

    /**
     * 选项瓦片：folia 在「一眼数得完」的枚举上一律用 {@code grid grid-cols-2 gap-2} 的瓦片
     * 而不是下拉。两档：只有标题的是 {@code px-3 py-2 rounded-lg text-xs} 高 34、文字居中；
     * 配了逐项说明的是 {@code p-3 rounded-xl} 高 66、左上角对齐两行，选中那一枚右上角再多一枚
     * 「使用中」角标。选中态两档共用同一套内联强调色：边、inset 环、强调色底。
     */
    private void drawChoiceTiles(CFontRenderer fr, Row row, int mouseX, int mouseY) {
        String[] options = row.entry.getChoices();
        String current = row.entry.getValueText();
        String[] hints = row.entry.getChoiceHints();
        boolean rich = hints.length > 0;
        int[] box = blockRect(row);
        int cols = choiceColumns(options.length, rich);
        int w = (box[2] - (cols - 1) * TILE_GAP) / cols;
        int h = choiceTileHeight(row.entry);
        double radius = rich ? TILE_RADIUS_RICH : TILE_RADIUS;
        for (int i = 0; i < options.length; i++) {
            int x = box[0] + (i % cols) * (w + TILE_GAP);
            int y = box[1] + (i / cols) * (h + TILE_GAP);
            boolean selected = options[i].equals(current);
            boolean hovered = !closing && isInside(mouseX, mouseY, x, y, w, h);
            drawRoundedRect(x, y, w, h, radius,
                    selected ? accentTint() : (hovered ? tileHover() : THEME_TILE_REST));
            drawRoundedOutline(x, y, w, h, radius, 1,
                    selected ? accent() : THEME_TILE_EDGE);
            if (selected) {
                drawRoundedOutline(x + 1, y + 1, w - 2, h - 2,
                        Math.max(0, radius - 1), 1, accent());
            }
            if (rich) {
                drawRichTile(x, y, w, options[i], i < hints.length ? hints[i] : null, selected);
                continue;
            }
            CFontRenderer font = FontManager.pf12bold != null ? FontManager.pf12bold : fr;
            if (font == null || options[i] == null) continue;
            String label = font.trim(options[i], w - TILE_PAD * 2);
            drawLineText(font, label, x + (w - font.getStringWidth(label)) / 2.0,
                    y + (h - ROW_DESC_H) / 2, ROW_DESC_H, textPrimary());
        }
    }

    /** 大瓦片里那两行：{@code text-base/24} 加粗标题 + {@code text-xs/20} 淡色说明。 */
    private void drawRichTile(int x, int y, int w, String option, String hint, boolean selected) {
        CFontRenderer title = FontManager.pf14bold != null ? FontManager.pf14bold
                : FontManager.pf12bold;
        int left = x + TILE_PAD;
        int right = x + w - TILE_PAD;
        if (selected) {
            right -= drawUseChip(right, y + TILE_PAD + (TILE_TITLE_H - USE_CHIP_H) / 2) + 6;
        }
        int textW = Math.max(8, right - left);
        if (title != null && option != null) {
            drawLineText(title, title.trim(option, textW), left, y + TILE_PAD,
                    TILE_TITLE_H, textPrimary());
        }
        CFontRenderer small = FontManager.pf12;
        if (small != null && hint != null) {
            drawLineText(small, small.trim(hint, textW), left, y + TILE_PAD + TILE_TITLE_H,
                    TILE_DESC_H, fade(textSecondary(), 0.5f));
        }
    }

    /**
     * 「使用中」角标：{@code rounded-full bg-white/10 px-1.5 text-[11px] font-semibold}，
     * 实测 42×18。返回它占掉的宽度，好让标题提前避让。
     */
    private int drawUseChip(int right, int top) {
        CFontRenderer font = FontManager.pf10bold != null ? FontManager.pf10bold
                : FontManager.pf12bold;
        if (font == null) return 0;
        int w = font.getStringWidth(USE_CHIP_TEXT) + USE_CHIP_PAD_X * 2;
        drawRoundedRect(right - w, top, w, USE_CHIP_H, USE_CHIP_H / 2.0, useChipBg());
        drawLineText(font, USE_CHIP_TEXT, right - w + USE_CHIP_PAD_X, top, USE_CHIP_H,
                textPrimary());
        return w;
    }

    /**
     * 一到三个选项排一行，四个起两列两行（folia 的 {@code grid-cols-2 / grid-cols-3} 两档）。
     * 带逐项说明的大瓦片一律两列 —— 一行三格就把说明挤没了，folia 自己也是两列。
     */
    private static int choiceColumns(int count, boolean rich) {
        if (rich) return Math.min(2, Math.max(1, count));
        return count <= 3 ? Math.max(1, count) : 2;
    }

    /** 瓦片高：配了逐项说明的是 {@code px-3 py-3 rounded-xl} 那档 66，只有标题的是 34 的分段条。 */
    private static int choiceTileHeight(HudSettingsModel.Entry entry) {
        return entry.hasChoiceHints() ? TILE_H : SEGMENT_H;
    }

    private static int choiceStackHeight(HudSettingsModel.Entry entry) {
        String[] options = entry.getChoices();
        int count = options == null ? 0 : options.length;
        int cols = choiceColumns(count, entry.hasChoiceHints());
        int lines = Math.max(1, (count + cols - 1) / cols);
        int h = choiceTileHeight(entry);
        return lines * h + (lines - 1) * TILE_GAP;
    }

    /** 点在哪一格选项上；{@code -1} 表示没落在瓦片上。与绘制共用同一份格子算式。 */
    private int choiceTileIndexAt(int mouseX, int mouseY, Row row) {
        String[] options = row.entry.getChoices();
        if (options == null || options.length == 0) return -1;
        int[] box = blockRect(row);
        boolean rich = row.entry.hasChoiceHints();
        int cols = choiceColumns(options.length, rich);
        int w = (box[2] - (cols - 1) * TILE_GAP) / cols;
        int h = choiceTileHeight(row.entry);
        for (int i = 0; i < options.length; i++) {
            int x = box[0] + (i % cols) * (w + TILE_GAP);
            int y = box[1] + (i / cols) * (h + TILE_GAP);
            if (isInside(mouseX, mouseY, x, y, w, h)) return i;
        }
        return -1;
    }

    /** 瓦片 hover：folia 的未选中瓦片是 {@code hover:bg-white/8} 那一档提亮。 */
    private int tileHover() {
        return daylight() ? 0x0D000000 : 0x1AFFFFFF;
    }

    private int[] controlRect(Row row) {
        int w = row.entry.kind == HudSettingsModel.Kind.COLOR ? SWATCH_W : SWITCH_W;
        int h = blockControlHeight(row.entry);
        return new int[] { row.x + row.w - w, row.y + (row.h - h) / 2, w, h };
    }

    /** 开关那一格（{@code w-12 h-6}，贴在内容的右端）：绘制与命中同一份。 */
    private int[] switchRect(Row row) {
        return controlRect(row);
    }

    // ==================== 控件 ====================

    /**
     * 滑条读数：{@code flex items-center justify-between} 那条标题行的右端，
     * {@code font-mono text-xs opacity-60}。它和标题同一行，不在轨道那一格里 ——
     * folia 的滑条是**铺满整行**的，右边没有留给数字的位置。
     */
    private void drawSliderValue(CFontRenderer fr, Row row, int[] box) {
        if (fr == null || row.entry.setting == null) return;
        HudSetting setting = row.entry.setting;
        String value = HudSettingsModel.formatValue(setting, setting.getValue());
        int headTop = box[1] - SLIDER_HEAD_GAP - ROW_LABEL_H;
        drawLineText(fr, value, box[0] + box[2] - fr.getStringWidth(value),
                headTop, ROW_LABEL_H, fade(textSecondary(), 0.6f));
    }

    /**
     * 原生滑条：一根铺满的 {@code input[type=range]}。
     *
     * <p>轨道画成浏览器自己的样子（见 {@link #RANGE_H} 那段实测）：8px 胶囊、上下各 1px 灰描边、
     * 已过半程是浅色、剩下是 {@code #3B3B3B}，旋钮 16px 实心圆压在轨道上，左右各留半个旋钮的行程。
     * 原生控件没有 hover 放大，也没有阴影，所以这两样这里都不给。</p>
     */
    private void drawRangeControl(CFontRenderer fr, HudSetting setting, int[] box) {
        int centerY = box[1] + RANGE_H / 2;
        int outlineTop = centerY - RANGE_TRACK_H / 2;
        drawRoundedRect(box[0], outlineTop, box[2], RANGE_TRACK_H, RANGE_TRACK_H / 2.0,
                RANGE_OUTLINE);
        float span = setting.getMax() - setting.getMin();
        float progress = span <= 0f ? 0f : clamp01((setting.getValue() - setting.getMin()) / span);
        double innerY = outlineTop + 1;
        double innerH = RANGE_TRACK_H - 2;
        drawRoundedRect(box[0] + 1, innerY, box[2] - 2, innerH, innerH / 2.0, RANGE_REST);
        double thumbX = box[0] + RANGE_THUMB / 2.0
                + progress * Math.max(0, box[2] - RANGE_THUMB);
        double fillW = Math.max(0, thumbX - box[0]);
        drawRoundedRect(box[0] + 1, innerY, fillW, innerH, innerH / 2.0,
                mix(sliderAccent(), 0xFFFFFFFF, RANGE_FILL_MIX));
        drawRoundedRect(thumbX - RANGE_THUMB / 2.0, centerY - RANGE_THUMB / 2.0,
                RANGE_THUMB, RANGE_THUMB, RANGE_THUMB / 2.0, sliderAccent());
    }

    /** 轨道矩形：滑条那一格整幅铺满（读数在标题行，不占这里的宽度）。 */
    private static int[] rangeTrackRect(int[] box) {
        return new int[] { box[0], box[1], box[2], RANGE_H };
    }

    /** 某一行滑条的轨道矩形（命中与绘制共用）。 */
    private int[] sliderTrackRect(Row row) {
        return rangeTrackRect(blockRect(row));
    }

    /**
     * folia 的主流开关（规格 §4.2 的变体 B）：48×24 轨道、内衬 4、旋钮 16、行程 24。
     *
     * <p>仓库里三套几何、两套开色是混着用的，必须选边。这里整套取 B：它是子视图里绝对多数
     * 的那一档（121 处 vs 主视图 renderToggle 的几处），于是<b>开色是 {@code secondaryColor}
     * 而不是强调色</b>，位移/上色都是 150ms，且<b>没有 hover 提亮、也没有按下缩放</b> ——
     * 那两样属于变体 A。进度由调用方的定长补间给出，这里不再自己插值。</p>
     */
    private void drawSwitch(int x, int y, float progress) {
        drawRoundedRect(x, y, SWITCH_W, SWITCH_H, SWITCH_H * 0.5,
                mix(toggleOff(), textSecondary(), progress));
        double knobX = x + 4 + SWITCH_TRAVEL * progress;
        // 旋钮只有 shadow-sm：0 1px 2px 0 rgba(0,0,0,.05)。原来那层 0x40 的实心偏移副本
        // 比设计重了一个量级，白旋钮下面会压出一圈硬边。
        drawBoxShadow(knobX, y + 4, 16, 16, 8, 1, 2, 0, fade(0xFF000000, 0.05f * layerAlpha));
        drawRoundedRect(knobX, y + 4, 16, 16, 8, 0xFFFFFFFF);
    }

    /**
     * 色块那一格：40 的取色块贴在行的右端，十六进制读数在它左边（{@code gap-3}）。
     * folia 的取色行就是这个骨架（{@code input[type=color]} + {@code font-mono text-[11px]
     * opacity-50} 的色值）。
     */
    private void drawColorBlock(Row row, int[] box, float hover, float press) {
        Color color = row.entry.getColor();
        drawSwatch(box[0], box[1], color, editingColor == row.entry.colorTarget, hover, press);
        String hex = String.format("#%06X", (color == null ? 0xFF9AA6B8 : color.getRGB()) & 0xFFFFFF);
        CFontRenderer fr = FontManager.pf12;
        if (fr != null) {
            drawLineText(fr, hex, box[0] - 12 - fr.getStringWidth(hex),
                    box[1] + (box[3] - ROW_DESC_H) / 2, ROW_DESC_H, fade(textSecondary(), 0.5f));
        }
    }

    /**
     * 颜色条目那枚色块。folia 的 swatch：40×40、12px 圆角、
     * {@code hover:scale-110 active:scale-95}，150ms 只动 transform。
     *
     * <p>缩放用「改边长 + 保持中心」近似 —— 这里没有独立的 transform 通道，而色块是正方形，
     * 结果与 matrix 缩放一致。滴管只在 hover/编辑时浮出来，是本模组自己的取色入口，
     * folia 的色块上不画东西。</p>
     */
    private void drawSwatch(int x, int y, Color color, boolean editing,
                            float hover, float press) {
        int base = (color == null ? 0xFF9AA6B8 : color.getRGB()) | 0xFF000000;
        double grow = 1.0 + 0.10 * hover - 0.05 * press;
        double size = SWATCH_W * grow;
        double left = x + (SWATCH_W - size) * .5;
        double top = y + (SWATCH_H - size) * .5;
        drawRoundedRect(left, top, size, size, SWATCH_RADIUS, base);
        drawRoundedOutline(left, top, size, size, SWATCH_RADIUS, 1,
                editing ? fade(accent(), 0.95f) : mix(ghostEdge(), sideSelectedEdge(), hover));
        if (hover > 0.05f || editing) {
            drawLucide(FoliaLucideIcons.COLORIZE, left + size / 2.0, top + size / 2.0, 16,
                    fade(contrastOn(base), Math.max(hover, editing ? 1f : 0f)));
        }
    }

    // ==================== CHOICE 下拉菜单（folia CustomSelectMenu portal） ====================

    /**
     * 下拉菜单的唯一矩形（约束 1：绘制与命中同一份坐标来源）。旧写法在画图与点判两处各算
     * 一遍，内衬或行高一改就会错开半格。
     *
     * <p>整套照 {@code updateDropdownPosition}：菜单宽 == 触发器宽（不是按内容收！），
     * 与触发器隔 4、离视口边 8；先看上下两侧谁更宽松 —— 两边连 160 都放不下时改成贴视口居中，
     * 否则下方不足 160 而上方更宽才翻到上方；最后高度是 {@code min(240, 可用)} 再夹一个 72 的下限。</p>
     */
    private int[] choiceMenuRect() {
        if (openChoice == null || openChoiceRect == null) {
            return null;
        }
        String[] options = openChoice.getChoices();
        CFontRenderer fr = FontManager.pf14;
        if (options.length == 0 || fr == null) {
            return null;
        }
        int[] trigger = openChoiceRect;
        int spaceBelow = height - (trigger[1] + trigger[3]) - MENU_GAP - MENU_GUTTER;
        int spaceAbove = trigger[1] - MENU_GAP - MENU_GUTTER;
        choiceUp = spaceBelow < MENU_MIN_PREFERRED_H && spaceAbove > spaceBelow;
        boolean viewport = Math.max(spaceAbove, spaceBelow) < MENU_MIN_PREFERRED_H;
        if (viewport) choiceUp = false;

        int w = Math.min(trigger[2], width - MENU_GUTTER * 2);
        int x = Math.max(MENU_GUTTER, Math.min(trigger[0], width - w - MENU_GUTTER));
        int available = viewport ? height - MENU_GUTTER * 2
                : Math.min(MENU_MAX_H, choiceUp ? spaceAbove : spaceBelow);
        int maxH = Math.max(72, available);
        int natural = MENU_PAD * 2 + options.length * MENU_ITEM_PITCH - MENU_ITEM_GAP;
        int h = Math.min(maxH, natural);
        int y = viewport ? MENU_GUTTER
                : choiceUp ? trigger[1] - h - MENU_GAP : trigger[1] + trigger[3] + MENU_GAP;
        return new int[] { x, y, w, h };
    }

    /** 一屏放得下几行：可视区高度按 42 一行（行 40 + {@code space-y-0.5} 的 2）往下数。 */
    private int menuVisibleRows(int menuHeight, int total) {
        int rows = (menuHeight - MENU_PAD * 2 + MENU_ITEM_GAP + MENU_ITEM_PITCH - 1) / MENU_ITEM_PITCH;
        return Math.max(1, Math.min(total, rows));
    }

    private void drawChoiceMenu(CFontRenderer fr, int mouseX, int mouseY) {
        String[] options = openChoice.getChoices();
        int[] menu = choiceMenuRect();
        if (menu == null) {
            openChoice = null;
            return;
        }
        int x = menu[0];
        int y = menu[1];
        int w = menu[2];
        int h = menu[3];

        float t = tween(choiceOpenTime, MENU_MS);
        float dy = (1f - t) * (choiceUp ? 8f : -8f);
        float sc = 0.96f + 0.04f * t;

        float outerAlpha = layerAlpha;
        try {
            layerAlpha = outerAlpha * t;
            double cx = x + w / 2.0;
            double cy = y + h / 2.0;
            SkijaGfx.pushMatrix();
            SkijaGfx.translate(cx, cy + dy);
            SkijaGfx.scale(sc, sc);
            SkijaGfx.translate(-cx, -cy);
            try {
                drawFloatShadow(x, y, w, h, 12);
                // 菜单是 backdrop-blur-md：毛的是这块矩形背后的东西，而且要跟着圆角裁。
                SkijaClip.beginClip(new Runnable() {
                    public void run() {
                        SkijaShapes.roundedRect(x, y, w, h, 12, 0xFFFFFFFF);
                    }
                });
                try {
                    SkijaEffects.backdropBlurRounded(x, y, w, h, 12, 6f, 1f);
                    drawRoundedRect(x, y, w, h, 12, menuBg());
                    drawRoundedOutline(x, y, w, h, 12, 1, menuEdge());
                } finally {
                    SkijaClip.endClip();
                }
                String current = openChoice.getValueText();
                choiceHoverIndex = -1;
                int shown = menuVisibleRows(h, options.length);
                int first = menuScroll / MENU_ITEM_PITCH;
                CFontRenderer itemFont = FontManager.pf14 != null ? FontManager.pf14 : fr;
                for (int i = first; i < options.length && i < first + shown; i++) {
                    int iy = menuItemTop(menu, i);
                    boolean selected = options[i].equals(current);
                    boolean hovered = isInside(mouseX, mouseY, x + MENU_PAD, iy,
                            w - MENU_PAD * 2, MENU_ITEM_H);
                    if (hovered) choiceHoverIndex = i;
                    if (selected) {
                        // 选中只是换一层强调色底（暗 {@code ${accent}18}、日 {@code ${accent}12}），
                        // 文字颜色不动 —— 原来给选中项提亮是自创的。
                        drawRoundedRect(x + MENU_PAD, iy, w - MENU_PAD * 2, MENU_ITEM_H, 8,
                                accentTint());
                    } else if (hovered) {
                        drawRoundedRect(x + MENU_PAD, iy, w - MENU_PAD * 2, MENU_ITEM_H, 8,
                                menuItemHover());
                    }
                    float checkRoom = selected ? MENU_CHECK + 8 : 0f;
                    drawLineText(itemFont, itemFont.trim(options[i],
                                    w - MENU_PAD * 2 - MENU_ITEM_PAD_X * 2 - checkRoom),
                            x + MENU_PAD + MENU_ITEM_PAD_X, iy, MENU_ITEM_H, textPrimary());
                    if (selected) {
                        drawLucide(FoliaLucideIcons.CHECK,
                                x + w - MENU_PAD - MENU_ITEM_PAD_X - MENU_CHECK / 2.0,
                                iy + MENU_ITEM_H / 2.0, MENU_CHECK, accent());
                    }
                }
                if (shown < options.length) {
                    // 超出部分在这层里滚，和网页上那条 overflow-y-auto 一致；拇指表示下面还有。
                    int thumbH = Math.max(18, (h - MENU_PAD * 2) * shown / options.length);
                    int thumbY = y + MENU_PAD + (h - MENU_PAD * 2 - thumbH) * menuScroll
                            / Math.max(1, (options.length - shown) * MENU_ITEM_PITCH);
                    drawRoundedRect(x + w - 5, thumbY, 3, thumbH, 2, scrollThumb());
                }
            } finally {
                SkijaGfx.popMatrix();
            }
        } finally {
            layerAlpha = outerAlpha;
        }
    }

    /** 第 {@code index} 行在当前滚动位置下的屏幕 y（绘制与命中唯一来源）。 */
    private int menuItemTop(int[] menu, int index) {
        return menu[1] + MENU_PAD + index * MENU_ITEM_PITCH - menuScroll;
    }

    /** 触发器矩形：菜单的宽度与上下翻转全按它算，画图也走同一个。内衬卡里它铺满整行。 */
    private int[] choiceTriggerRect(Row row) {
        return blockRect(row);
    }

    private void openChoiceMenu(HudSettingsModel.Entry entry, Row row) {
        openChoice = entry;
        openChoiceRect = choiceTriggerRect(row);
        menuScroll = 0;
        choiceOpenTime = System.currentTimeMillis();
    }

    private boolean handleChoiceMenuClick(int mouseX, int mouseY) {
        if (openChoice == null) return false;
        String[] options = openChoice.getChoices();
        int[] menu = choiceMenuRect();
        if (menu == null) {
            openChoice = null;
            return true;
        }
        int x = menu[0];
        int w = menu[2];
        int h = menu[3];
        if (isInside(mouseX, mouseY, x, menu[1], w, h)) {
            int shown = menuVisibleRows(h, options.length);
            int first = menuScroll / MENU_ITEM_PITCH;
            for (int i = first; i < options.length && i < first + shown; i++) {
                if (isInside(mouseX, mouseY, x + MENU_PAD, menuItemTop(menu, i),
                        w - MENU_PAD * 2, MENU_ITEM_H)) {
                    openChoice.setChoice(options[i]);
                    settingsDirty = true;
                }
            }
        }
        // 落在菜单里还是菜单外都一样：这一层把事件吃掉。主面板不能因为「点在菜单外面」
        // 跟着一起关 —— 那正是 folia 里子层 stopPropagation 挡掉的事。
        openChoice = null;
        return true;
    }

    // ==================== 主题预设格 ====================

    /**
     * 列数。folia 那张卡是 {@code grid grid-cols-2 gap-3}，但它只有两三款预设；本模组十五款，
     * 两列要滚八屏，于是按同一份 tile 样式取 {@code grid-cols-2 sm:grid-cols-4} 那一档
     * （Playback 子视图的分段选择就是这四列）—— 样式没换，只是列数随宽度收。
     */
    private int themeGridColumns(int rowW) {
        return rowW >= 4 * 120 + 3 * TILE_GAP_WIDE ? 4 : 2;
    }

    private int themeGridHeight(int rowW) {
        int cols = themeGridColumns(rowW);
        int count = NCMTheme.ThemePreset.values().length;
        int lines = Math.max(1, (count + cols - 1) / cols);
        return lines * THEME_TILE_H + (lines - 1) * TILE_GAP_WIDE;
    }

    /** 第 {@code i} 格的盒子（绘制与命中同一来源）。 */
    private int[] themeTileRect(int i, int x, int y, int rowW) {
        int cols = themeGridColumns(rowW);
        int w = (rowW - (cols - 1) * TILE_GAP_WIDE) / cols;
        return new int[] { x + (i % cols) * (w + TILE_GAP_WIDE),
                y + (i / cols) * (THEME_TILE_H + TILE_GAP_WIDE), w, THEME_TILE_H };
    }

    private void drawThemeChips(int x, int y, int rowW, int mouseX, int mouseY) {
        NCMTheme.ThemePreset[] presets = NCMTheme.ThemePreset.values();
        NCMTheme.ThemePreset current = NCMTheme.getCurrent();
        CFontRenderer label = FontManager.pf12bold != null ? FontManager.pf12bold : FontManager.pf12;
        for (int i = 0; i < presets.length; i++) {
            int[] box = themeTileRect(i, x, y, rowW);
            drawThemeTile(box[0], box[1], box[2], box[3], presets[i], presets[i] == current, label);
        }
    }

    /**
     * 一格预设：{@code flex flex-col items-center gap-2 p-3 rounded-lg}，里面是一枚 24 的
     * 135° 渐变圆点（底色 → 强调色）加一行 {@code text-xs font-semibold} 的名字。
     *
     * <p>选中态在 folia 里是**内联样式**而不是 class：{@code borderColor=accent} 加
     * {@code boxShadow: inset 0 0 0 1px accent} 与一层强调色底（暗 {@code 18}、日 {@code 12}）。
     * 未选中是 white/10 边 + white/8 底。</p>
     */
    private void drawThemeTile(int x, int y, int w, int h, NCMTheme.ThemePreset preset,
                               boolean selected, CFontRenderer label) {
        boolean lockedTheme = !NCMTheme.isSelectable(preset);
        int bg = 0xFF000000 | preset.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND);
        int ac = 0xFF000000 | preset.getColor(NCMScreen.ColorType.ACCENT);
        drawRoundedRect(x, y, w, h, TILE_RADIUS, selected ? accentTint() : THEME_TILE_REST);
        drawRoundedOutline(x, y, w, h, TILE_RADIUS, 1, selected ? accent() : THEME_TILE_EDGE);
        if (selected) {
            drawRoundedOutline(x + 1, y + 1, w - 2, h - 2, Math.max(0, TILE_RADIUS - 1), 1, accent());
        }
        drawGradientFill(x + (w - THEME_DOT) / 2.0, y + TILE_PAD, THEME_DOT, THEME_DOT,
                THEME_DOT / 2.0, bg, ac, 135f);
        if (lockedTheme) {
            // 手机档：真玻璃主题瓦片压灰（见 NCMTheme.isSelectable），点击在 activateRow 已挡。
            drawRoundedRect(x, y, w, h, TILE_RADIUS, 0x96141A22);
        }
        if (label == null) return;
        String name = label.trim(preset.getDisplayName(), w - TILE_PAD * 2);
        drawLineText(label, name, x + (w - label.getStringWidth(name)) / 2.0,
                y + TILE_PAD + THEME_DOT + 8, ROW_DESC_H,
                lockedTheme ? fade(textPrimary(), 0.45f) : textPrimary());
    }

    /** 落在哪一格上：与 {@link #drawThemeChips} 共用同一份 {@link #themeTileRect}，不会错开半格。 */
    private int themeChipIndexAt(int mouseX, int mouseY, Row row) {
        NCMTheme.ThemePreset[] presets = NCMTheme.ThemePreset.values();
        int[] box = blockRect(row);
        for (int i = 0; i < presets.length; i++) {
            int[] tile = themeTileRect(i, box[0], box[1], box[2]);
            if (isInside(mouseX, mouseY, tile[0], tile[1], tile[2], tile[3])) return i;
        }
        return -1;
    }

    // ==================== 取色器（Pro 的 HSV 逻辑，folia 外壳） ====================

    private int pickerX(int panelX, int panelW) {
        return panelX + (panelW - PICKER_W) / 2;
    }

    private int pickerY(int panelY, int panelH) {
        return panelY + (panelH - PICKER_H) / 2;
    }

    private void drawColorPicker(CFontRenderer fr, int panelX, int panelY, int panelW, int panelH,
                                 int mouseX, int mouseY) {
        int x = pickerX(panelX, panelW);
        int y = pickerY(panelY, panelH);
        float t = named("picker.in", true);
        float outerAlpha = layerAlpha;
        try {
            layerAlpha = outerAlpha * t;
            drawFloatShadow(x, y, PICKER_W, PICKER_H, PICKER_RADIUS);
            drawRoundedRect(x, y, PICKER_W, PICKER_H, PICKER_RADIUS, panelBg());
            drawRoundedOutline(x, y, PICKER_W, PICKER_H, PICKER_RADIUS, 1, structuralDivider());

            drawLineText(FontManager.pf14,
                    pickerTitle(editingColor),
                    x + PICKER_PAD, y + PICKER_PAD, PICKER_HEADER_H, textPrimary());
            int[] close = pickerCloseButton(x, y);
            float cp = named("picker.close",
                    isInside(mouseX, mouseY, close[0], close[1], close[2], close[3]));
            drawRoundedRect(close[0], close[1], close[2], close[3], close[3] * 0.5f,
                    fade(CLOSE_BG, (0.3f + 0.7f * cp) * layerAlpha));
            drawLucide(FoliaLucideIcons.CLOSE, close[0] + close[2] / 2.0, close[1] + close[3] / 2.0,
                    CLOSE_ICON, fade(textPrimary(), 0.3f + 0.7f * cp));

            // 内衬那一格：rounded-[22px] + border-white/10 + bg-black/10 + p-3。
            int linerX = x + PICKER_PAD;
            int linerY = y + PICKER_PAD + PICKER_HEADER_H + PICKER_BLOCK_GAP;
            int linerW = PICKER_INNER_W;
            int linerH = PICKER_LINER_PAD * 2 + SV_H + PICKER_BLOCK_GAP + HUE_H;
            drawRoundedRect(linerX, linerY, linerW, linerH, PICKER_LINER_RADIUS, LINER_BG);
            drawRoundedOutline(linerX, linerY, linerW, linerH, PICKER_LINER_RADIUS, 1,
                    structuralDivider());

            int svX = pickerSvX(x);
            int svY = pickerSvY(y);
            drawSaturationBrightnessArea(svX, svY);
            drawHueSlider(svX, pickerHueY(y));

            Color preview = Color.getHSBColor(pickerHue, pickerSaturation, pickerBrightness);
            int hexY = pickerHexY(y);
            drawRoundedRect(x + PICKER_PAD, hexY, SWATCH_W, SWATCH_H, SWATCH_RADIUS,
                    preview.getRGB() | 0xFF000000);
            drawRoundedOutline(x + PICKER_PAD, hexY, SWATCH_W, SWATCH_H, SWATCH_RADIUS, 1,
                    0x1A000000);

            ensureHexColorInput();
            int inputX = x + PICKER_PAD + SWATCH_W + 12;
            boolean focused = hexColorInput.isFocused();
            drawRoundedRect(inputX, hexY, HEX_INPUT_W, HEX_INPUT_H, HEX_INPUT_RADIUS, chipBg());
            drawRoundedOutline(inputX, hexY, HEX_INPUT_W, HEX_INPUT_H, HEX_INPUT_RADIUS, 1,
                    focused ? 0x33FFFFFF : structuralDivider());
            hexColorInput.xPosition = inputX + PILL_PAD_X;
            hexColorInput.yPosition = hexY + (HEX_INPUT_H - 20) / 2;
            hexColorInput.width = HEX_INPUT_W - PILL_PAD_X * 2;
            hexColorInput.height = 20;
            drawLineText(fr, hexColorInput.getText(), inputX + PILL_PAD_X, hexY, HEX_INPUT_H,
                    textPrimary());
            if (focused) {
                int caretX = inputX + PILL_PAD_X + fr.getStringWidth(hexColorInput.getText());
                drawRoundedRect(caretX, hexY + (HEX_INPUT_H - 20) / 2, 1, 20, 1, 0xFFEAF3FF);
            }
            drawLineText(fr, "输入 HEX", inputX + HEX_INPUT_W + 12, hexY, HEX_INPUT_H,
                    fade(textSecondary(), 0.5f));
        } finally {
            layerAlpha = outerAlpha;
        }
    }

    private int[] pickerCloseButton(int pickerX, int pickerY) {
        // 与主面板那颗 X 同一档：p-1 包 20 的图标，贴着浮层右上 16 的内衬。
        return new int[] { pickerX + PICKER_W - PICKER_PAD - 28, pickerY + PICKER_PAD, 28, 28 };
    }

    /** 浮层标题那一格（{@code text-sm}(20 行高)），取色区从它下面隔 {@code space-y-3} 起算。 */
    private static final int PICKER_HEADER_H = 20;

    private int pickerSvX(int x) {
        return x + PICKER_PAD + PICKER_LINER_PAD;
    }

    private int pickerSvY(int y) {
        return y + PICKER_PAD + PICKER_HEADER_H + PICKER_BLOCK_GAP + PICKER_LINER_PAD;
    }

    private int pickerHueY(int y) {
        return pickerSvY(y) + SV_H + PICKER_BLOCK_GAP;
    }

    private int pickerHexY(int y) {
        return pickerHueY(y) + HUE_H + PICKER_LINER_PAD + PICKER_BLOCK_GAP;
    }

    /** SV 区：folia 的圆角卡用四角渐变一次画成（左上白 → 右上纯色，底下两角透明压黑）。 */
    private void drawSaturationBrightnessArea(int x, int y) {
        int hueRgb = Color.HSBtoRGB(pickerHue, 1.0f, 1.0f) | 0xFF000000;
        SkijaShapes.roundedRectCornerGradient(x, y, SV_W, SV_H, 12,
                new Color(0x000000, true), new Color(0xFFFFFFFF, true),
                new Color(0x000000, true), new Color(hueRgb, false),
                layerAlpha);
        drawPickerMarker(x + Math.round(pickerSaturation * (SV_W - 1)),
                y + Math.round((1.0f - pickerBrightness) * (SV_H - 1)));
    }

    private void drawHueSlider(int x, int y) {
        float[] stops = { 1f, 0f, 0f, 1f };
        for (int seg = 0; seg < 6; seg++) {
            int from = Color.HSBtoRGB(seg / 6f, 1f, 1f) | 0xFF000000;
            int to = Color.HSBtoRGB((seg + 1) / 6f, 1f, 1f) | 0xFF000000;
            SkijaShapes.gradientRect(x + seg * (SV_W / 6f), y,
                    x + (seg + 1) * (SV_W / 6f), y + HUE_H, from, to, true);
        }
        int markerX = x + Math.round(pickerHue * (SV_W - 1));
        Gui.drawRect(markerX - 1, y - 2, markerX + 2, y + HUE_H + 2, 0xFF000000);
        Gui.drawRect(markerX, y - 1, markerX + 1, y + HUE_H + 1, 0xFFFFFFFF);
    }

    private static void drawPickerMarker(int x, int y) {
        Gui.drawRect(x - 3, y - 3, x + 4, y - 2, 0xFF000000);
        Gui.drawRect(x - 3, y + 3, x + 4, y + 4, 0xFF000000);
        Gui.drawRect(x - 3, y - 3, x - 2, y + 4, 0xFF000000);
        Gui.drawRect(x + 3, y - 3, x + 4, y + 4, 0xFF000000);
        Gui.drawRect(x - 2, y - 2, x + 3, y - 1, 0xFFFFFFFF);
        Gui.drawRect(x - 2, y + 2, x + 3, y + 3, 0xFFFFFFFF);
        Gui.drawRect(x - 2, y - 2, x - 1, y + 3, 0xFFFFFFFF);
        Gui.drawRect(x + 2, y - 2, x + 3, y + 3, 0xFFFFFFFF);
    }

    // ==================== 鼠标 ====================

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        boolean adaptive = beginAdaptiveLayout();
        if (adaptive) {
            mouseX = (int) Math.round(toUiX(mouseX));
            mouseY = (int) Math.round(toUiY(mouseY));
        }
        try {
            mouseClickedImpl(mouseX, mouseY, button);
        } finally {
            restoreAdaptiveLayout(adaptive);
        }
    }

    private void mouseClickedImpl(int mouseX, int mouseY, int button) throws IOException {
        if (closing) return;
        pressOutsidePanel = false;
        pressedTarget = null;

        int[] panel = panelBounds();
        int panelX = panel[0];
        int panelY = panel[1];
        int panelW = panel[2];
        int panelH = panel[3];
        boolean inside = isInside(mouseX, mouseY, panelX, panelY, panelW, panelH);

        // 叠层顺序：菜单 > 取色器 > 主面板。最上面那一层先把这次按下吃掉，事件不漏下去
        //（folia 是每层一个 mousedown 归属 + 子层 stopPropagation，WeakSet 逐层记）。
        // 这一步必须排在「落在面板外」的判断之前：菜单开着时点遮罩，关的只是菜单，
        // 整个弹窗跟着一起关是最容易被当成「就是这样」的错。
        if (handleChoiceMenuClick(mouseX, mouseY)) return;
        if (editingColor != HudSettingsModel.ColorTarget.NONE
                && handlePickerClick(mouseX, mouseY, button, panelX, panelY, panelW, panelH)) {
            return;
        }

        if (!inside) {
            // 遮罩上的按下：是否关闭由 mouseReleased 判定（按下与松开都要落在遮罩上，复刻 folia）。
            pressOutsidePanel = true;
            return;
        }
        if (button != 0) {
            super.mouseClicked(mouseX, mouseY, button);
            return;
        }

        int[] close = closeButton(panelX, panelY, panelW);
        if (isInside(mouseX, mouseY, close[0], close[1], close[2], close[3])) {
            requestClose();
            return;
        }
        for (int i = 0; i < headerTabCount(); i++) {
            int[] tab = tabRect(i);
            if (!isInside(mouseX, mouseY, tab[0], tab[1], tab[2], tab[3]) || i == activeHeaderTab()) {
                continue;
            }
            if (i == 0) {
                switchStyle(HudConfig.HUD_EDITOR_STYLE_CLASSIC, new GuiHudEditor());
            } else if (i == 1) {
                switchStyle(HudConfig.HUD_EDITOR_STYLE_MODERN, new GuiHudEditorPro());
            } else {
                HudConfig.hudEditorUi = HudConfig.HUD_EDITOR_UI_ONECONFIG;
                HudConfig.save();
                if (OneConfigHudBridge.open()) mc.gui.setScreen(null);
            }
            return;
        }

        int[] nav = navRect();
        int[] content = contentRect();
        buildNav(nav[0], nav[1], nav[2], nav[3]);
        for (NavItem item : navItems) {
            if (!isInside(mouseX, mouseY, item.x, item.y, item.w, item.h)) continue;
            if (item.kind == 0) {
                if (!wideLayout()) {
                    // 窄屏那条横带是可以拖着横滚的，选中因此必须等到松开：按下就换分区的话，
                    // 一次拖拽会连着切过好几个分区（folia 是 onClick + hasDragged() 判定）。
                    chipDragging = true;
                    chipDragIndex = item.index;
                    chipHasDragged = false;
                    chipDragStartX = mouseX;
                    chipDragFromScroll = chipScroll;
                    return;
                }
                selectCategory(item.index);
            } else {
                scrollToAnchor(item.index, content[3]);
            }
            return;
        }

        if (!isInside(mouseX, mouseY, content[0], content[1], content[2], content[3])) return;
        // folia 把 release 挂在滚动容器的 pointerdown 上：按下即解除点击锁，不是等 800ms 到点。
        clickLockUntil = 0L;
        if (showDaylightToggle(HudSettingsModel.categories()[clampCategory()])) {
            int[] box = daylightButtonRect(content[0], content[1] - Math.round(displayScroll),
                    content[2]);
            if (isInside(mouseX, mouseY, box[0], box[1], box[2], box[3])) {
                toggleDaylight();
                return;
            }
        }
        layoutRows(content[0], content[1], content[2],
                HudSettingsModel.categories()[clampCategory()]);
        for (Row row : rows) {
            if (row.isGroupHeader()) continue;
            if (!isInside(mouseX, mouseY, row.x, row.y, row.w, row.h)) continue;
            // 记下按在谁身上，控件的 active:scale-95 那一档才有依据（CSS 的 :active 同理）。
            pressedTarget = pressKeyOf(row);
            activateRow(row, mouseX, mouseY);
            return;
        }
    }

    /** 一行的按下键：CHOICE 按在触发器上、ACTION 按在那枚按钮上，其余整行都算。 */
    private String pressKeyOf(Row row) {
        String base = "row." + System.identityHashCode(row.entry);
        return row.entry.kind == HudSettingsModel.Kind.ACTION ? base + ".action" : base;
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        boolean adaptive = beginAdaptiveLayout();
        if (adaptive) {
            mouseX = (int) Math.round(toUiX(mouseX));
            mouseY = (int) Math.round(toUiY(mouseY));
        }
        try {
            if (state == 0) {
                if (chipDragging) {
                    finishChipDrag(mouseX, mouseY);
                }
                if (pressOutsidePanel && !closing) {
                    int[] panel = panelBounds();
                    if (!isInside(mouseX, mouseY, panel[0], panel[1], panel[2], panel[3])) {
                        requestClose();
                    }
                }
                pressOutsidePanel = false;
                if (draggingSaturationBrightness || draggingHue || draggingSlider != null || settingsDirty) {
                    HudConfig.save();
                    settingsDirty = false;
                }
            }
            pressedTarget = null;
            draggingSaturationBrightness = false;
            draggingHue = false;
            draggingSlider = null;
            super.mouseReleased(mouseX, mouseY, state);
        } finally {
            restoreAdaptiveLayout(adaptive);
        }
    }

    /**
     * 一行的点击归属。folia 里每一类控件只吃自己那一格的点击 —— 点在标签、说明或卡片的内衬上
     * 什么都不发生，所以这里也按控件矩形判，而不是整行。
     */
    private void activateRow(Row row, int mouseX, int mouseY) {
        // 手机档锁死行（真玻璃材质整组）：点击与滑条拖动一律无效，渲染层把它画成灰的。
        if (row.entry.isLocked()) return;
        switch (row.entry.kind) {
            case SLIDER: {
                int[] track = sliderTrackRect(row);
                if (!isInside(mouseX, mouseY, track[0], track[1], track[2], RANGE_H)) break;
                draggingSlider = row.entry.setting;
                draggingSliderTrackX = track[0];
                draggingSliderTrackW = track[2];
                updateSliderWhileDragging(mouseX);
                break;
            }
            case TOGGLE: {
                int[] box = switchRect(row);
                if (isInside(mouseX, mouseY, box[0], box[1], box[2], box[3])) row.entry.activate();
                break;
            }
            case ACTION:
                row.entry.activate();
                break;
            case CHOICE: {
                if (isSegmented(row.entry)) {
                    // 瓦片是「点一下就定」，没有菜单那一层。
                    int index = choiceTileIndexAt(mouseX, mouseY, row);
                    if (index >= 0) {
                        row.entry.setChoice(row.entry.getChoices()[index]);
                        settingsDirty = true;
                    }
                    break;
                }
                int[] box = choiceTriggerRect(row);
                if (!isInside(mouseX, mouseY, box[0], box[1], box[2], box[3])) break;
                String[] options = row.entry.getChoices();
                if (options.length > 1) {
                    openChoiceMenu(row.entry, row);
                } else {
                    row.entry.activate();
                }
                break;
            }
            case COLOR: {
                int[] box = controlRect(row);
                if (!isInside(mouseX, mouseY, box[0], box[1], box[2], box[3])) break;
                openChoice = null;
                beginColorEdit(row.entry);
                break;
            }
            case THEME_GRID: {
                int chip = themeChipIndexAt(mouseX, mouseY, row);
                if (chip >= 0) {
                    NCMTheme.ThemePreset preset = NCMTheme.ThemePreset.values()[chip];
                    if (!NCMTheme.isSelectable(preset)) break;
                    DownloadDynamicIsland.showTheme(NCMTheme.setCurrent(preset) == null
                            ? NCMTheme.getCurrentName() : preset.getDisplayName());
                }
                break;
            }
            default:
                break;
        }
    }

    private void scrollToAnchor(int anchor, int bodyH) {
        if (anchor < 0 || anchor >= anchorTops.length) return;
        int maxScroll = Math.max(0, contentHeight - bodyH);
        // anchorTops 是从整列的内容原点量的（分区头也在滚动容器里，所以它算在内）。
        // 落点上方留 SCROLL_TOP_PADDING(12px) —— 这 12px 只属于落点，不属于判定线。
        int target = Math.max(0, Math.min(maxScroll, anchorTops[anchor] - SCROLL_TOP_PADDING));
        setScroll(target, maxScroll);
        activeAnchor = anchor;
        clickLockUntil = System.currentTimeMillis() + CLICK_LOCK_MS;
    }

    private void updateSliderWhileDragging(int mouseX) {
        if (draggingSlider != null && Mouse.isButtonDown(0)) {
            float progress = clamp01((mouseX - draggingSliderTrackX)
                    / (float) Math.max(1, draggingSliderTrackW));
            draggingSlider.setValue(draggingSlider.getMin()
                    + (draggingSlider.getMax() - draggingSlider.getMin()) * progress);
            settingsDirty = true;
        }
    }

    private boolean handlePickerClick(int mouseX, int mouseY, int button,
                                      int panelX, int panelY, int panelW, int panelH) {
        int x = pickerX(panelX, panelW);
        int y = pickerY(panelY, panelH);
        if (!isInside(mouseX, mouseY, x, y, PICKER_W, PICKER_H)) {
            // 和菜单同理：取色器是叠上来的一层，点在它外面只关它自己，不漏给主面板。
            closeColorPicker();
            return true;
        }
        int[] close = pickerCloseButton(x, y);
        if (isInside(mouseX, mouseY, close[0], close[1], close[2], close[3])) {
            closeColorPicker();
            return true;
        }
        int svX = pickerSvX(x);
        int svY = pickerSvY(y);
        int hueY = pickerHueY(y);
        int inputX = x + PICKER_PAD + SWATCH_W + 12;
        int inputY = pickerHexY(y);
        ensureHexColorInput();
        if (isInside(mouseX, mouseY, inputX, inputY, HEX_INPUT_W, HEX_INPUT_H)) {
            hexColorInput.setFocused(true);
            hexColorInput.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        hexColorInput.setFocused(false);
        if (isInside(mouseX, mouseY, svX, svY, SV_W, SV_H)) {
            draggingSaturationBrightness = true;
            applyPickerAt(mouseX, mouseY, panelX, panelY, panelW, panelH);
            return true;
        }
        if (isInside(mouseX, mouseY, svX, hueY, SV_W, HUE_H)) {
            draggingHue = true;
            applyPickerAt(mouseX, mouseY, panelX, panelY, panelW, panelH);
            return true;
        }
        return false;
    }

    /** 关掉取色器那一层，并顺手收了 hex 输入框的焦点。 */
    private void closeColorPicker() {
        editingColor = HudSettingsModel.ColorTarget.NONE;
        if (hexColorInput != null) {
            hexColorInput.setFocused(false);
        }
    }

    /**
     * 弹掉最上面那一层：菜单 → 取色器 → 主面板。
     *
     * <p>folia 的 Esc 就是「先关最上层的二级面板，否则关弹窗」；按下归属走的是同一套顺序，
     * 所以两处共用这一个方法，别再各写一份顺序 —— 顺序写两遍迟早会不一致。</p>
     */
    private void popTopLayer() {
        if (openChoice != null) {
            openChoice = null;
            return;
        }
        if (editingColor != HudSettingsModel.ColorTarget.NONE) {
            if (hexColorInput != null && hexColorInput.isFocused()) {
                hexColorInput.setFocused(false);
                return;
            }
            closeColorPicker();
        }
    }

    private void updatePickerWhileDragging(int mouseX, int mouseY,
                                           int panelX, int panelY, int panelW, int panelH) {
        if (!Mouse.isButtonDown(0)) return;
        if (draggingSaturationBrightness || draggingHue) {
            applyPickerAt(mouseX, mouseY, panelX, panelY, panelW, panelH);
        }
    }

    private void applyPickerAt(int mouseX, int mouseY, int panelX, int panelY, int panelW, int panelH) {
        int x = pickerX(panelX, panelW);
        int y = pickerY(panelY, panelH);
        int svX = pickerSvX(x);
        int svY = pickerSvY(y);
        int hueY = pickerHueY(y);
        if (draggingSaturationBrightness) {
            pickerSaturation = clamp01((mouseX - svX) / (float) (SV_W - 1));
            pickerBrightness = 1.0f - clamp01((mouseY - svY) / (float) (SV_H - 1));
        }
        if (draggingHue) {
            pickerHue = clamp01((mouseX - svX) / (float) (SV_W - 1));
        }
        Color selected = Color.getHSBColor(pickerHue, pickerSaturation, pickerBrightness);
        applySelectedColor(selected);
        if (hexColorInput != null && !hexColorInput.isFocused()) {
            hexColorInput.setText(toHex(selected));
        }
    }

    /** 取色器标题：每个 ColorTarget 一句。 */
    private static String pickerTitle(HudSettingsModel.ColorTarget target) {
        switch (target) {
            case CURRENT: return "当前歌词颜色";
            case WORLD_LYRICS: return "3D 歌词文字颜色";
            default: return "普通歌词颜色";
        }
    }

    private void beginColorEdit(HudSettingsModel.Entry entry) {
        editingColor = entry.colorTarget;
        Color color = entry.getColor();
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        pickerHue = hsb[0];
        pickerSaturation = hsb[1];
        pickerBrightness = hsb[2];
        ensureHexColorInput();
        hexColorInput.setText(toHex(color));
        hexColorInput.setFocused(false);
    }

    private void applySelectedColor(Color selected) {
        for (HudSettingsModel.Category category : HudSettingsModel.categories()) {
            for (HudSettingsModel.Group group : category.groups) {
                for (HudSettingsModel.Entry entry : group.entries) {
                    if (entry.kind == HudSettingsModel.Kind.COLOR && entry.colorTarget == editingColor) {
                        entry.setColor(selected);
                        settingsDirty = true;
                        return;
                    }
                }
            }
        }
    }

    // ==================== 键盘 ====================

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (closing) return;
        if (keyCode == Keyboard.KEY_ESCAPE) {
            boolean hadLayer = openChoice != null
                    || editingColor != HudSettingsModel.ColorTarget.NONE;
            popTopLayer();
            if (hadLayer) {
                return;
            }
            requestClose();
            return;
        }
        if (editingColor != HudSettingsModel.ColorTarget.NONE && hexColorInput != null
                && hexColorInput.isFocused()) {
            if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                hexColorInput.setFocused(false);
                if (settingsDirty) {
                    HudConfig.save();
                    settingsDirty = false;
                }
                return;
            }
            if (hexColorInput.textboxKeyTyped(typedChar, keyCode)) {
                applyHexColorInput();
            }
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void applyHexColorInput() {
        if (hexColorInput == null) return;
        Color selected = parseHexColor(hexColorInput.getText());
        if (selected == null) return;
        float[] hsb = Color.RGBtoHSB(selected.getRed(), selected.getGreen(), selected.getBlue(), null);
        pickerHue = hsb[0];
        pickerSaturation = hsb[1];
        pickerBrightness = hsb[2];
        applySelectedColor(selected);
    }

    private static Color parseHexColor(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.startsWith("#")) normalized = normalized.substring(1);
        if (!normalized.matches("[0-9a-fA-F]{6}")) return null;
        try {
            return new Color(Integer.parseInt(normalized, 16));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    // ==================== 关闭与样式切换 ====================

    private void requestClose() {
        if (closing) return;
        closing = true;
        closeStart = System.currentTimeMillis();
        openChoice = null;
        editingColor = HudSettingsModel.ColorTarget.NONE;
    }

    /** 换样式后立即换界面：记住选择，下次按 H 直接进这一套。 */
    private void switchStyle(String style, GuiScreen next) {
        HudConfig.hudEditorStyle = style;
        HudConfig.hudEditorUi = HudConfig.HUD_EDITOR_UI_BUILTIN;
        HudConfig.save();
        mc.gui.setScreen(next);
    }

    // ==================== 杂项 ====================

    private int clampCategory() {
        int count = HudSettingsModel.categories().length;
        return Math.max(0, Math.min(count - 1, categoryIndex));
    }

    private int scroll() {
        return scrollOffset;
    }

    private void setScroll(int value, int maxScroll) {
        scrollOffset = Math.max(0, Math.min(maxScroll, value));
    }

    /** 换分区：folia 是 {@code contentScrollRef.scrollTop = 0}，瞬时、不补间。 */
    private void resetScrollForCategory() {
        scrollOffset = 0;
        snapScroll = true;
        activeAnchor = -1;
    }

    /** 切到一个分区：叠层先收掉，滚动位置清零。 */
    private void selectCategory(int index) {
        categoryIndex = index;
        editingColor = HudSettingsModel.ColorTarget.NONE;
        openChoice = null;
        resetScrollForCategory();
    }

    /**
     * 窄屏 chips 的拖拽横滚（{@code useDragToScroll}）：位移乘 1.5 反向加到 scrollLeft 上，
     * 超过 5px 就把这次点击作废。拖到哪算哪，钳在整条带的可滚范围里。
     */
    private void updateChipDrag(int mouseX) {
        if (!chipDragging) return;
        int walk = Math.round((mouseX - chipDragStartX) * CHIP_DRAG_FACTOR);
        if (Math.abs(walk) > CHIP_DRAG_THRESHOLD) chipHasDragged = true;
        int[] nav = navRect();
        chipScroll = Math.max(0, Math.min(Math.max(0, chipContentWidth - nav[2]),
                chipDragFromScroll - walk));
    }

    /** chips 那条带的实际内容宽，由 {@link #buildChips} 每帧算好。 */
    private int chipContentWidth;

    /** 松开：拖过阈值就作废；否则要求按下与松开落在同一枚 chip 上（浏览器 onClick 的语义）。 */
    private void finishChipDrag(int mouseX, int mouseY) {
        chipDragging = false;
        int index = chipDragIndex;
        chipDragIndex = -1;
        if (chipHasDragged || index < 0) return;
        for (NavItem item : navItems) {
            if (item.kind == 0 && item.index == index
                    && isInside(mouseX, mouseY, item.x, item.y, item.w, item.h)) {
                selectCategory(index);
                return;
            }
        }
    }

    private void ensureHexColorInput() {
        if (hexColorInput == null) {
            hexColorInput = new GuiTextField(0, fontRendererObj, 0, 0,
                    HEX_INPUT_W - PILL_PAD_X * 2, 20);
            hexColorInput.setEnableBackgroundDrawing(false);
            hexColorInput.setMaxStringLength(7);
        }
    }

    private boolean hasLiveInfoPreview() {
        return CloudMusic.currentlyPlaying != null && CloudMusic.hasLiveTrack();
    }

    private boolean hasLiveLyricsPreview() {
        return CloudMusic.hasLiveTrack() && !CloudMusic.lyrics.isEmpty();
    }

    private void renderLive(MusicInfoWidget info) {
        SkijaGfx.pushAttrib();
        SkijaGfx.pushMatrix();
        try {
            SkijaGfx.setBlendMode(SkijaGfx.fromGlBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA));
            info.widget.render();
        } finally {
            SkijaGfx.popMatrix();
            SkijaGfx.popAttrib();
        }
    }

    private void renderLiveLyrics(MusicLyricsWidget lyrics, int x, int y) {
        SkijaGfx.pushAttrib();
        SkijaGfx.pushMatrix();
        try {
            SkijaGfx.setBlendMode(SkijaGfx.fromGlBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA));
            lyrics.renderEditorPreview(x, y, HudConfig.lyricScale);
        } finally {
            SkijaGfx.popMatrix();
            SkijaGfx.popAttrib();
        }
    }

    // ==================== 动画工具 ====================
    //
    // folia 的动效分两家：外壳与菜单是 framer-motion 显式指定的**定长 easeOut**
    // （cubic-bezier(0,0,.2,1)），其余大量 hover/颜色/位移是 CSS 默认的
    // **150ms cubic-bezier(.4,0,.2,1)**。两边都不是帧率相关的指数插值 —— 用指数插值
    // 出来的东西在高刷屏上更快、低刷屏上更慢，时长这个参数本身就是设计的一部分。

    /** 时间基准的定长补间，走 framer-motion 的 easeOut 曲线。 */
    private static float tween(long startMs, int durationMs) {
        return easeOut(clamp01((System.currentTimeMillis() - startMs) / (float) Math.max(1, durationMs)));
    }

    /** CSS 的 {@code ease-out} = {@code cubic-bezier(0, 0, 0.2, 1)}。 */
    private static float easeOut(float x) {
        return cubicBezier(x, 0f, 0f, 0.2f, 1f);
    }

    /** Tailwind v4 的默认过渡曲线 {@code cubic-bezier(.4, 0, .2, 1)}。 */
    private static float easeStandard(float x) {
        return cubicBezier(x, 0.4f, 0f, 0.2f, 1f);
    }

    /**
     * 用 Newton 迭代解三次贝塞尔：给定横进度 {@code x} 求纵值。
     *
     * <p>CSS 的缓动曲线是「时间 → 进度」，控制点的 x 必须单调才合法，这里假定合法输入
     * （folia 用到的两条都满足）。五次迭代够到肉眼无差，比建查找表省一次初始化分配。</p>
     */
    private static float cubicBezier(float x, float x1, float y1, float x2, float y2) {
        if (x <= 0f) return 0f;
        if (x >= 1f) return 1f;
        float t = x;
        for (int i = 0; i < 5; i++) {
            float slope = bezierSlope(t, x1, x2);
            if (Math.abs(slope) < 1e-5f) break;
            t = clamp01(t - (bezierUnit(t, x1, x2) - x) / slope);
        }
        return bezierUnit(t, y1, y2);
    }

    private static float bezierUnit(float t, float p1, float p2) {
        float u = 1f - t;
        return 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t;
    }

    private static float bezierSlope(float t, float p1, float p2) {
        float u = 1f - t;
        return 3f * u * u * p1 + 6f * u * t * (p2 - p1) + 3f * t * t * (1f - p2);
    }

    /** 一段定长过渡的状态：从哪个值起、到哪个值去、什么时候起、多长、用哪条曲线。 */
    private static final class Transition {
        private float from;
        private float to;
        private float current;
        private long startMs;
        private int durationMs;
        private boolean standard;
    }

    private final Map<String, Transition> transitions = new HashMap<String, Transition>();

    /** CSS 默认那一档：150ms {@code cubic-bezier(.4,0,.2,1)} 的过渡进度。 */
    private float named(String key, boolean active) {
        return transition(key, active, TRANSITION_MS, true);
    }

    /**
     * 定长过渡。方向中途反转时从<b>当前值</b>重新起一段完整的定长补间，
     * 这正是 CSS 的行为（旧值写进 transition 的起点，而不是接着剩余时间跑）。
     */
    private float transition(String key, boolean active, int durationMs, boolean standard) {
        long now = System.currentTimeMillis();
        Transition t = transitions.get(key);
        if (t == null) {
            t = new Transition();
            t.from = t.to = t.current = active ? 1f : 0f;
            t.startMs = now - durationMs;
            t.durationMs = durationMs;
            t.standard = standard;
            transitions.put(key, t);
        }
        float elapsed = clamp01((now - t.startMs) / (float) Math.max(1, t.durationMs));
        float shaped = t.standard ? easeStandard(elapsed) : easeOut(elapsed);
        t.current = t.from + (t.to - t.from) * shaped;
        float target = active ? 1f : 0f;
        if (target != t.to) {
            t.from = t.current;
            t.to = target;
            t.startMs = now;
            t.durationMs = durationMs;
            t.standard = standard;
        }
        return t.current;
    }

    private static int fade(int color, float alpha) {
        int base = (color >>> 24) & 0xFF;
        int a = Math.round(base * clamp01(alpha));
        return (Math.max(0, Math.min(255, a)) << 24) | (color & 0xFFFFFF);
    }

    private static int mix(int from, int to, float t) {
        float k = clamp01(t);
        int a = channel(from, 24) + Math.round((channel(to, 24) - channel(from, 24)) * k);
        int r = channel(from, 16) + Math.round((channel(to, 16) - channel(from, 16)) * k);
        int g = channel(from, 8) + Math.round((channel(to, 8) - channel(from, 8)) * k);
        int b = channel(from, 0) + Math.round((channel(to, 0) - channel(from, 0)) * k);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int channel(int color, int shift) {
        return (color >>> shift) & 0xFF;
    }

    private static int contrastOn(int background) {
        float luma = (channel(background, 16) * 0.299f
                + channel(background, 8) * 0.587f
                + channel(background, 0) * 0.114f) / 255f;
        return luma > 0.58f ? 0xFF101720 : 0xFFF4F7FB;
    }

    // ==================== 绘制原语 ====================

    /**
     * 模型条目那串字形图标 → 同一语义的 lucide 图标。
     *
     * <p>经典版与现代版共用 {@code HudSettingsModel} 里那串字形，Folia 界面必须换成 folia
     * 原生的 lucide 几何才谈得上 1:1，但模型不能为了这一套界面改掉（另外三套还要用）。
     * 所以在界面这侧做一次映射，映射不到的继续走字形。</p>
     */
    private static final Map<String, String> LUCIDE_BY_GLYPH = new HashMap<>();

    static {
        // 分类 / 分组图标
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.TUNE, FoliaLucideIcons.TUNE);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.SPEED, FoliaLucideIcons.SPEED);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.SPARKLE, FoliaLucideIcons.SPARKLE);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.SWEEP, FoliaLucideIcons.SWEEP);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.STORAGE, FoliaLucideIcons.STORAGE);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.RULER, FoliaLucideIcons.RULER);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.PALETTE, FoliaLucideIcons.PALETTE);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.CAPTION, FoliaLucideIcons.CAPTION);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.SUBTITLES, FoliaLucideIcons.SUBTITLES);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.ISLAND, FoliaLucideIcons.ISLAND);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.ALBUM, FoliaLucideIcons.ALBUM);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.NOTE, FoliaLucideIcons.NOTE);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.VISIBILITY, FoliaLucideIcons.VISIBILITY);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.TIMER, FoliaLucideIcons.TIMER);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.MIC, FoliaLucideIcons.MIC);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.MOVE, FoliaLucideIcons.MOVE);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.MODERN_UI, FoliaLucideIcons.MODERN_UI);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.CLASSIC_UI, FoliaLucideIcons.CLASSIC_UI);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.FULLSCREEN, FoliaLucideIcons.FULLSCREEN);
        // 控件图标
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.CLOSE, FoliaLucideIcons.CLOSE);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.CHECK, FoliaLucideIcons.CHECK);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.EXPAND, FoliaLucideIcons.CHEVRON_DOWN);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.COLLAPSE, FoliaLucideIcons.CHEVRON_UP);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.CHEVRON_RIGHT, FoliaLucideIcons.CHEVRON_RIGHT);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.CHEVRON_LEFT, FoliaLucideIcons.CHEVRON_LEFT);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.BACK, FoliaLucideIcons.CHEVRON_LEFT);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.RESTORE, FoliaLucideIcons.RESTORE);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.REFRESH, FoliaLucideIcons.REFRESH);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.SEARCH, FoliaLucideIcons.SEARCH);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.COLORIZE, FoliaLucideIcons.COLORIZE);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.INFO, FoliaLucideIcons.INFO);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.WARNING, FoliaLucideIcons.WARNING);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.DRAG_HANDLE, FoliaLucideIcons.DRAG_HANDLE);
        LUCIDE_BY_GLYPH.put(HudSettingsIcons.GRIP, FoliaLucideIcons.GRIP);
    }

    /**
     * 画一枚 lucide 矢量图标（folia 的原生图标体系），尺寸按网页上那一档传。
     *
     * <p>整层淡入时跟着面板一起淡：图标是面板表面的一部分，不能比它先实。</p>
     */
    private void drawLucide(String name, double centerX, double centerY, double size, int color) {
        int tinted = layerAlpha >= 0.999f ? color : fade(color, layerAlpha);
        if (((tinted >>> 24) & 0xFF) == 0) return;
        FoliaLucideIcons.draw(name, centerX, centerY, size, tinted);
    }

    /** 旋转着画一枚图标：下拉箭头的 {@code rotate-180} 用它，绕中心转。 */
    private void drawLucideRotated(String name, double centerX, double centerY, double size,
                                   int color, float degrees) {
        int tinted = layerAlpha >= 0.999f ? color : fade(color, layerAlpha);
        if (((tinted >>> 24) & 0xFF) == 0) return;
        SkijaGfx.pushMatrix();
        try {
            SkijaGfx.translate(centerX, centerY);
            SkijaGfx.rotateZ(degrees);
            FoliaLucideIcons.draw(name, 0, 0, size, tinted);
        } finally {
            SkijaGfx.popMatrix();
        }
    }

    /** 模型条目的字形图标，能映射到 lucide 就用矢量，映射不到退回字形。 */
    private void drawEntryIcon(CFontRenderer font, String glyph, double centerX, double centerY,
                               double size, int color) {
        String name = glyph == null ? null : LUCIDE_BY_GLYPH.get(glyph);
        if (name != null) {
            int tinted = layerAlpha >= 0.999f ? color : fade(color, layerAlpha);
            if (FoliaLucideIcons.draw(name, centerX, centerY, size, tinted)) {
                return;
            }
        }
        drawIcon(font, glyph, centerX, centerY, color);
    }

    private void drawIcon(CFontRenderer font, String glyph, double centerX, double centerY, int color) {
        if (font == null || glyph == null || glyph.isEmpty()) return;
        int tinted = layerAlpha >= 0.999f ? color : fade(color, layerAlpha);
        if (((tinted >>> 24) & 0xFF) == 0) return;
        GlyphInkMetrics square = emSquare(font);
        double x;
        double y;
        if (square != null) {
            x = square.alignCenterX(centerX);
            y = square.alignCenterY(centerY);
        } else {
            x = centerX - font.getStringWidthD(glyph) * .5;
            y = centerY - font.getFontHeight() * .5;
        }
        font.drawString(glyph, x, y, tinted);
    }

    private static GlyphInkMetrics emSquare(CFontRenderer font) {
        synchronized (GRID_CACHE) {
            if (GRID_CACHE.containsKey(font)) {
                return GRID_CACHE.get(font);
            }
        }
        GlyphInkMetrics measured = GlyphInkMetrics.of(font, HudSettingsIcons.LOCATOR.charAt(0));
        synchronized (GRID_CACHE) {
            GRID_CACHE.put(font, measured);
        }
        return measured;
    }

    private void drawRoundedRect(double x, double y, double w, double h, double radius, int color) {
        if (w <= 0 || h <= 0) return;
        float alpha = ((color >>> 24) & 0xFF) / 255.0F * layerAlpha;
        if (alpha <= 0.002f) return;
        SkijaShapes.roundedRect(x, y, w, h, Math.min(radius, Math.min(w, h) * 0.5),
                SkijaGfx.argb(((color >>> 16) & 0xFF) / 255.0F, ((color >>> 8) & 0xFF) / 255.0F,
                        (color & 0xFF) / 255.0F, alpha));
        SkijaGfx.setBlendMode(SkijaGfx.fromGlBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA));
    }

    /** 1px 描边。folia 的白/5、白/10 边框全靠它，不然不透明面板在世界上会糊成一整块。 */
    private void drawRoundedOutline(double x, double y, double w, double h,
                                    double radius, double thickness, int color) {
        if (w <= 0 || h <= 0) return;
        float alpha = ((color >>> 24) & 0xFF) / 255.0F * layerAlpha;
        if (alpha <= 0.002f) return;
        int argb = SkijaGfx.argb(((color >>> 16) & 0xFF) / 255.0F, ((color >>> 8) & 0xFF) / 255.0F,
                (color & 0xFF) / 255.0F, alpha);
        SkijaShapes.roundedOutline(x, y, w, h, Math.min(radius, Math.min(w, h) * 0.5), thickness, argb);
    }

    private void drawText(CFontRenderer font, String text, double x, double y, int color) {
        if (font == null || text == null || text.isEmpty()) return;
        int tinted = layerAlpha >= 0.999f ? color : fade(color, layerAlpha);
        if (((tinted >>> 24) & 0xFF) == 0) return;
        font.drawString(text, x, y, tinted);
    }

    /**
     * 把一行字放进 folia 的那一档 line-height 盒子里居中。
     *
     * <p>网页上的文字从来不是「顶端对齐到 y」，而是排在 {@code leading} 给的盒子里：Tailwind 的
     * {@code text-sm} 是 14px 字配 20px 盒子、{@code text-xs} 是 12 配 16。行盒由字体自己的
     * ascent/descent 决定（这里取 {@code getFontHeight() * .5}），差出来的一半就是顶端要留的空。</p>
     */
    private void drawLineText(CFontRenderer font, String text, double x, double boxTop, int boxH,
                              int color) {
        if (font == null) return;
        drawText(font, text, x, boxTop + (boxH - font.getFontHeight() * .5) * .5, color);
    }

    /**
     * CSS {@code linear-gradient(<deg>, a, b)} 的轴：0deg 朝上、顺时针增大，所以方向向量是
     * {@code (sin, -cos)}；渐变轴取盒子在该方向上的最长投影，两端各延伸一半 —— 否则 120° 这种
     * 斜向渐变在扁宽的盒子上会被压成一条竖线，两端的颜色各占一半。
     *
     * <p>返回的 {@link Shader} 由调用方关闭，但必须在把它从 Paint 上摘掉之后 —— 见
     * {@link #drawGradientFill}。
     */
    private Shader gradientShader(double x, double y, double w, double h, double deg,
                                  int startArgb, int endArgb) {
        double rad = Math.toRadians(deg);
        double dx = Math.sin(rad);
        double dy = -Math.cos(rad);
        double reach = (Math.abs(w * dx) + Math.abs(h * dy)) / 2.0;
        double cx = x + w / 2.0;
        double cy = y + h / 2.0;
        return Shader.makeLinearGradient(
                new Point((float) (cx - dx * reach), (float) (cy - dy * reach)),
                new Point((float) (cx + dx * reach), (float) (cy + dy * reach)),
                new int[] { scaleAlpha(startArgb), scaleAlpha(endArgb) }, new float[] { 0f, 1f });
    }

    /** 一整块渐变填充，对应 {@code background: linear-gradient(...)}。 */
    private void drawGradientFill(double x, double y, double w, double h, double radius,
                                  int startArgb, int endArgb, double deg) {
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null || w <= 0 || h <= 0) return;
        // fillShader/strokeShader 返回的是 SkijaGfx 里**复用**的那支 Paint：绝不能 close 它，
        // 也不能让它带着 shader 活过本方法 —— Skija 的 close 直接 delete 原生对象，
        // Paint 里的裸指针不会失效，下一次 setShader(null) 就是对已释放内存 unref，
        // 表现为 skija.dll 里的 ACCESS_VIOLATION。顺序固定为 绘制 → 摘 shader → 关 shader。
        try (Shader shader = gradientShader(x, y, w, h, deg, startArgb, endArgb)) {
            Paint paint = SkijaGfx.fillShader(shader);
            float r = (float) Math.min(radius, Math.min(w, h) * 0.5);
            canvas.drawRRect(RRect.makeLTRB((float) x, (float) y,
                    (float) (x + w), (float) (y + h), r), paint);
            paint.setShader(null);
        }
    }

    /**
     * 一圈线性渐变描边，对应打在 {@code border-box} 上的 {@code linear-gradient(<deg>, a, b)}。
     */
    private void drawGradientOutline(double x, double y, double w, double h, double radius,
                                     double thickness, int startArgb, int endArgb, double deg) {
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null || w <= 0 || h <= 0) return;
        try (Shader shader = gradientShader(x, y, w, h, deg, startArgb, endArgb)) {
            Paint paint = SkijaGfx.strokeShader(shader, thickness);
            float r = (float) Math.min(radius, Math.min(w, h) * 0.5);
            canvas.drawRRect(RRect.makeLTRB((float) x, (float) y,
                    (float) (x + w), (float) (y + h), r), paint);
            paint.setShader(null);
        }
    }

    private int scaleAlpha(int argb) {
        float alpha = ((argb >>> 24) & 0xFF) / 255.0F * layerAlpha;
        return SkijaGfx.argb(((argb >>> 16) & 0xFF) / 255.0F, ((argb >>> 8) & 0xFF) / 255.0F,
                (argb & 0xFF) / 255.0F, alpha);
    }

    /**
     * 带字距地画一行：folia 的 {@code tracking-widest}(.1em) 与 {@code tracking-wider}(.05em)
     * 都是给分组/分区标题用的，逐字前进一个「字号×字距」的额外量即可复刻。
     *
     * <p>只在标题这类短串上用：逐字画会丢掉整行的连字与批量绘制，正文长句代价不划算。</p>
     */
    private void drawTracked(CFontRenderer font, String text, double x, double y, int color,
                             float trackingEm) {
        if (font == null || text == null || text.isEmpty()) return;
        if (trackingEm <= 0f) {
            drawText(font, text, x, y, color);
            return;
        }
        float step = (float) (font.getFontHeight() * trackingEm);
        double cursor = x;
        for (int i = 0; i < text.length(); i++) {
            String one = String.valueOf(text.charAt(i));
            drawText(font, one, cursor, y, color);
            cursor += font.getStringWidthD(one) + step;
        }
    }

    private int scissorDepth;

    /**
     * {@code w/h <= 0} 表示「不裁剪」，但 Skia 那里没有 no-op clip，必须给一个覆盖整张画布的矩形。
     * 用 {@code width/height} 而不是 RenderSystem 的尺寸：裁剪是在当前矩阵下做的，而这一帧的矩阵
     * 可能已经被设计空间校正过，只有本屏的宽高坐标轴与它同域。
     */
    private void beginScissor(int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            SkijaClip.scissorBegin(0, 0, width, height);
        } else {
            SkijaClip.scissorBegin(x, y, w, h);
        }
        scissorDepth++;
    }

    private void endScissor() {
        if (scissorDepth > 0) {
            scissorDepth--;
            SkijaClip.scissorEnd();
        }
    }

    private static boolean isInside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static String toHex(Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }
}
