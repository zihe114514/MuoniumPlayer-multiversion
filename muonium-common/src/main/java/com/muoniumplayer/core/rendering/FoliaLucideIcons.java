package com.muoniumplayer.core.rendering;

import com.muoniumplayer.render.skija.SkijaGfx;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintStrokeCap;
import io.github.humbleui.skija.PaintStrokeJoin;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.types.Rect;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Folia 设置界面用的 <b>lucide 矢量图标</b>。
 *
 * <p>本文件由 {@code tools/make_lucide_icons.py} 从 lucide 1.31.0 生成，不要手改：重跑脚本会
 * 整文件覆盖。本项目 的设置弹窗渲染的就是这批字形（它锁的是同版本的 lucide-react），
 * 所以这里的图标与它是同一套几何，而不是「看着像」。</p>
 *
 * <p>lucide 的设计网格是 24x24，默认描边 2、圆头圆角、只描边不填充。绘制时按目标边长等比
 * 缩放，描边宽度也跟着缩放（{@code 2 * size / 24}）—— 这正是网页上 CSS 的行为，写死成
 * 2px 会让大图标明显偏细。</p>
 *
 * <p>图标数据来自 lucide（ISC 许可，Copyright (c) Lucide Icons and Contributors），
 * 按 ISC 的要求在此保留版权声明。</p>
 */
public final class FoliaLucideIcons {

    /** lucide 的设计网格边长。 */
    public static final double GRID = 24.0;

    /** lucide 的默认描边宽度，落在 {@link #GRID} 网格里。 */
    public static final double STROKE_WIDTH = 2.0;

    /** 「基础」「调整」分组：推子/参数调整（lucide {@code settings-2}）。 */
    public static final String TUNE = "settings-2";
    static final String[] D_TUNE = {
        "M14 17H5",
        "M19 7h-9",
        "M14 17 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0",
        "M4 7 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0",
    };

    /** 「时序与动画」「过渡模式」：速率（lucide {@code gauge}）。 */
    public static final String SPEED = "gauge";
    static final String[] D_SPEED = {
        "m12 14 4-4",
        "M3.34 19a10 10 0 1 1 17.32 0",
    };

    /** 「特效」相关分组（lucide {@code sparkles}）。 */
    public static final String SPARKLE = "sparkles";
    static final String[] D_SPARKLE = {
                    "M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1."
            +             "966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0"
            +             "-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594z",
        "M20 2v4",
        "M22 4h-4",
        "M2 20 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0",
    };

    /** 「音频缓存」：清理（lucide {@code eraser}）。 */
    public static final String SWEEP = "eraser";
    static final String[] D_SWEEP = {
                    "M21 21H8a2 2 0 0 1-1.42-.587l-3.994-3.999a2 2 0 0 1 0-2.828l10-10a2 2 0 0 1 2.829 0l5.999 6a"
            +             "2 2 0 0 1 0 2.828L12.834 21",
        "m5.082 11.09 8.828 8.828",
    };

    /** 「存储」「分析模型」（lucide {@code database}）。 */
    public static final String STORAGE = "database";
    static final String[] D_STORAGE = {
        "M3 5V19A9 3 0 0 0 21 19V5",
        "M3 12A9 3 0 0 0 21 12",
    };

    /** 「摆放」「大小与换行」「窗口缩放」（lucide {@code ruler}）。 */
    public static final String RULER = "ruler";
    static final String[] D_RULER = {
                    "M21.3 15.3a2.4 2.4 0 0 1 0 3.4l-2.6 2.6a2.4 2.4 0 0 1-3.4 0L2.7 8.7a2.41 2.41 0 0 1 0-3.4l2."
            +             "6-2.6a2.41 2.41 0 0 1 3.4 0Z",
        "m14.5 12.5 2-2",
        "m11.5 9.5 2-2",
        "m8.5 6.5 2-2",
        "m17.5 15.5 2-2",
    };

    /** 「主题」分类（lucide {@code palette}）。 */
    public static final String PALETTE = "palette";
    static final String[] D_PALETTE = {
                    "M12 22a1 1 0 0 1 0-20 10 9 0 0 1 10 9 5 5 0 0 1-5 5h-2.25a1.75 1.75 0 0 0-1.4 2.8l.3.4a1.75 "
            +             "1.75 0 0 1-1.4 2.8z",
        "M13 6.5 a0.5 0.5 0 1 0 1 0 a0.5 0.5 0 1 0 -1 0",
        "M17 10.5 a0.5 0.5 0 1 0 1 0 a0.5 0.5 0 1 0 -1 0",
        "M6 12.5 a0.5 0.5 0 1 0 1 0 a0.5 0.5 0 1 0 -1 0",
        "M8 7.5 a0.5 0.5 0 1 0 1 0 a0.5 0.5 0 1 0 -1 0",
    };
    /** 同一枚图标里需要填充的那几个图元。 */
    static final String[] FD_PALETTE = {
        "M13 6.5 a0.5 0.5 0 1 0 1 0 a0.5 0.5 0 1 0 -1 0",
        "M17 10.5 a0.5 0.5 0 1 0 1 0 a0.5 0.5 0 1 0 -1 0",
        "M6 12.5 a0.5 0.5 0 1 0 1 0 a0.5 0.5 0 1 0 -1 0",
        "M8 7.5 a0.5 0.5 0 1 0 1 0 a0.5 0.5 0 1 0 -1 0",
    };

    /** 「桌面面板」「逐字效果」「信息标签」（lucide {@code captions}）。 */
    public static final String CAPTION = "captions";
    static final String[] D_CAPTION = {
        "M5 5 H19 A2 2 0 0 1 21 7 V17 A2 2 0 0 1 19 19 H5 A2 2 0 0 1 3 17 V7 A2 2 0 0 1 5 5 Z",
        "M7 15h4M15 15h2M7 11h2M13 11h4",
    };

    /** 「普通歌词」分类（lucide {@code subtitles}）。 */
    public static final String SUBTITLES = "subtitles";
    static final String[] D_SUBTITLES = {
        "M5 5 H19 A2 2 0 0 1 21 7 V17 A2 2 0 0 1 19 19 H5 A2 2 0 0 1 3 17 V7 A2 2 0 0 1 5 5 Z",
        "M7 15h4M15 15h2M7 11h2M13 11h4",
    };

    /** 「灵动岛」分类（lucide {@code bell}）。 */
    public static final String ISLAND = "bell";
    static final String[] D_ISLAND = {
        "M10.268 21a2 2 0 0 0 3.464 0",
                    "M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 "
            +             "8c0 4.499-1.411 5.956-2.738 7.326",
    };

    /** 「封面」「动态封面」（lucide {@code disc-3}）。 */
    public static final String ALBUM = "disc-3";
    static final String[] D_ALBUM = {
        "M2 12 a10 10 0 1 0 20 0 a10 10 0 1 0 -20 0",
        "M6 12c0-1.7.7-3.2 1.8-4.2",
        "M10 12 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0",
        "M18 12c0 1.7-.7 3.2-1.8 4.2",
    };

    /** 「播放器界面」分类（lucide {@code music-2}）。 */
    public static final String NOTE = "music-2";
    static final String[] D_NOTE = {
        "M4 18 a4 4 0 1 0 8 0 a4 4 0 1 0 -8 0",
        "M12 18V2l7 4",
    };

    /** 「显示」分组（lucide {@code eye}）。 */
    public static final String VISIBILITY = "eye";
    static final String[] D_VISIBILITY = {
        "M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0",
        "M9 12 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0",
    };

    /** 「常驻显示」分组（lucide {@code timer}）。 */
    public static final String TIMER = "timer";
    static final String[] D_TIMER = {
        "M10 2 L14 2",
        "M12 14 L15 11",
        "M4 14 a8 8 0 1 0 16 0 a8 8 0 1 0 -16 0",
    };

    /** 「当前歌词」分类（lucide {@code mic}）。 */
    public static final String MIC = "mic";
    static final String[] D_MIC = {
        "M12 19v3",
        "M19 10v2a7 7 0 0 1-14 0v-2",
        "M12 2 H12 A3 3 0 0 1 15 5 V12 A3 3 0 0 1 12 15 H12 A3 3 0 0 1 9 12 V5 A3 3 0 0 1 12 2 Z",
    };

    /** 「HUD 位置」分类（lucide {@code move}）。 */
    public static final String MOVE = "move";
    static final String[] D_MOVE = {
        "M12 2v20",
        "m15 19-3 3-3-3",
        "m19 9 3 3-3 3",
        "M2 12h20",
        "m5 9-3 3 3 3",
        "m9 5 3-3 3 3",
    };

    /** 切到现代界面（lucide {@code panels-top-left}）。 */
    public static final String MODERN_UI = "panels-top-left";
    static final String[] D_MODERN_UI = {
        "M5 3 H19 A2 2 0 0 1 21 5 V19 A2 2 0 0 1 19 21 H5 A2 2 0 0 1 3 19 V5 A2 2 0 0 1 5 3 Z",
        "M3 9h18",
        "M9 21V9",
    };

    /** 切到经典界面（lucide {@code list}）。 */
    public static final String CLASSIC_UI = "list";
    static final String[] D_CLASSIC_UI = {
        "M3 5h.01",
        "M3 12h.01",
        "M3 19h.01",
        "M8 5h13",
        "M8 12h13",
        "M8 19h13",
    };

    /** 切到 Folia 界面（lucide {@code sparkle}）。 */
    public static final String FOLIA_UI = "sparkle";
    static final String[] D_FOLIA_UI = {
                    "M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1."
            +             "966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0"
            +             "-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594z",
    };

    /** 「全屏歌词」分组（lucide {@code maximize}）。 */
    public static final String FULLSCREEN = "maximize";
    static final String[] D_FULLSCREEN = {
        "M8 3H5a2 2 0 0 0-2 2v3",
        "M21 8V5a2 2 0 0 0-2-2h-3",
        "M3 16v3a2 2 0 0 0 2 2h3",
        "M16 21h3a2 2 0 0 0 2-2v-3",
    };

    /** 关闭弹窗、清空输入（lucide {@code x}）。 */
    public static final String CLOSE = "x";
    static final String[] D_CLOSE = {
        "M18 6 6 18",
        "m6 6 12 12",
    };

    /** 下拉菜单选中态、色卡已选（lucide {@code check}）。 */
    public static final String CHECK = "check";
    static final String[] D_CHECK = {
        "M20 6 9 17l-5-5",
    };

    /** 下拉展开（200ms rotate-180）（lucide {@code chevron-down}）。 */
    public static final String CHEVRON_DOWN = "chevron-down";
    static final String[] D_CHEVRON_DOWN = {
        "m6 9 6 6 6-6",
    };

    /** 面板收起（lucide {@code chevron-up}）。 */
    public static final String CHEVRON_UP = "chevron-up";
    static final String[] D_CHEVRON_UP = {
        "m18 15-6-6-6 6",
    };

    /** 二级面板返回（lucide {@code chevron-left}）。 */
    public static final String CHEVRON_LEFT = "chevron-left";
    static final String[] D_CHEVRON_LEFT = {
        "m15 18-6-6 6-6",
    };

    /** 选项行「点一下进下一个」、进入子面板大条尾部（lucide {@code chevron-right}）。 */
    public static final String CHEVRON_RIGHT = "chevron-right";
    static final String[] D_CHEVRON_RIGHT = {
        "m9 18 6-6-6-6",
    };

    /** 恢复默认（lucide {@code rotate-ccw}）。 */
    public static final String RESTORE = "rotate-ccw";
    static final String[] D_RESTORE = {
        "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8",
        "M3 3v5h5",
    };

    /** 重新检测（lucide {@code refresh-cw}）。 */
    public static final String REFRESH = "refresh-cw";
    static final String[] D_REFRESH = {
        "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8",
        "M21 3v5h-5",
        "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16",
        "M8 16H3v5",
    };

    /** 搜索框（lucide {@code search}）。 */
    public static final String SEARCH = "search";
    static final String[] D_SEARCH = {
        "m21 21-4.34-4.34",
        "M3 11 a8 8 0 1 0 16 0 a8 8 0 1 0 -16 0",
    };

    /** 「更改位置」这类挑目录的按钮（lucide {@code folder-open}）。 */
    public static final String FOLDER = "folder-open";
    static final String[] D_FOLDER = {
                    "m6 14 1.5-2.9A2 2 0 0 1 9.24 10H20a2 2 0 0 1 1.94 2.5l-1.54 6a2 2 0 0 1-1.95 1.5H4a2 2 0 0 1"
            +             "-2-2V5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.81 1.2a2 2 0 0 0 1.67.9H18a2 2 0 0 1 2 2v2",
    };

    /** 「删除所有模型」这类破坏性按钮（lucide {@code trash-2}）。 */
    public static final String TRASH = "trash-2";
    static final String[] D_TRASH = {
        "M10 11v6",
        "M14 11v6",
        "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6",
        "M3 6h18",
        "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2",
    };

    /** 颜色条目、取色器（lucide {@code pipette}）。 */
    public static final String COLORIZE = "pipette";
    static final String[] D_COLORIZE = {
                    "m12 9-8.414 8.414A2 2 0 0 0 3 18.828v1.344a2 2 0 0 1-.586 1.414A2 2 0 0 1 3.828 21h1.344a2 2"
            +             " 0 0 0 1.414-.586L15 12",
        "m18 9 .4.4a1 1 0 1 1-3 3l-3.8-3.8a1 1 0 1 1 3-3l.4.4 3.4-3.4a1 1 0 1 1 3 3z",
        "m2 22 .414-.414",
    };

    /** 日间模式切换（lucide {@code sun}）。 */
    public static final String SUN = "sun";
    static final String[] D_SUN = {
        "M8 12 a4 4 0 1 0 8 0 a4 4 0 1 0 -8 0",
        "M12 2v2",
        "M12 20v2",
        "m4.93 4.93 1.41 1.41",
        "m17.66 17.66 1.41 1.41",
        "M2 12h2",
        "M20 12h2",
        "m6.34 17.66-1.41 1.41",
        "m19.07 4.93-1.41 1.41",
    };

    /** 夜间模式切换（lucide {@code moon}）。 */
    public static final String MOON = "moon";
    static final String[] D_MOON = {
                    "M20.985 12.486a9 9 0 1 1-9.473-9.472c.405-.022.617.46.402.803a6 6 0 0 0 8.268 8.268c.344-.21"
            +             "5.825-.004.803.401",
    };

    /** 说明（lucide {@code info}）。 */
    public static final String INFO = "info";
    static final String[] D_INFO = {
        "M2 12 a10 10 0 1 0 20 0 a10 10 0 1 0 -20 0",
        "M12 16v-4",
        "M12 8h.01",
    };

    /** 恢复默认的确认弹窗（lucide {@code triangle-alert}）。 */
    public static final String WARNING = "triangle-alert";
    static final String[] D_WARNING = {
        "m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3",
        "M12 9v4",
        "M12 17h.01",
    };

    /** 快捷键相关（lucide {@code keyboard}）。 */
    public static final String KEYBOARD = "keyboard";
    static final String[] D_KEYBOARD = {
        "M10 8h.01",
        "M12 12h.01",
        "M14 8h.01",
        "M16 12h.01",
        "M18 8h.01",
        "M6 8h.01",
        "M7 16h10",
        "M8 12h.01",
        "M4 4 H20 A2 2 0 0 1 22 6 V18 A2 2 0 0 1 20 20 H4 A2 2 0 0 1 2 18 V6 A2 2 0 0 1 4 4 Z",
    };

    /** 可拖动的把手（lucide {@code grip}）。 */
    public static final String DRAG_HANDLE = "grip";
    static final String[] D_DRAG_HANDLE = {
        "M11 5 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
        "M18 5 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
        "M4 5 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
        "M11 12 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
        "M18 12 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
        "M4 12 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
        "M11 19 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
        "M18 19 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
        "M4 19 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
    };

    /** 标题栏的拖动区标记（lucide {@code menu}）。 */
    public static final String GRIP = "menu";
    static final String[] D_GRIP = {
        "M4 5h16",
        "M4 12h16",
        "M4 19h16",
    };

    /** 候选列表空态、封面缺图时的占位音符（lucide {@code music}）。 */
    public static final String MUSIC = "music";
    static final String[] D_MUSIC = {
        "M9 18V5l12-2v13",
        "M3 18 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0",
        "M15 16 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0",
    };

    /** 「搜索中」「匹配中」的旋转指示（ animate-spin ）（lucide {@code loader-2}）。 */
    public static final String LOADER = "loader-2";
    static final String[] D_LOADER = {
        "M21 12a9 9 0 1 1-6.219-8.56",
    };

    /** 歌词预览的「含翻译」「含罗马音」标签（lucide {@code languages}）。 */
    public static final String LANGUAGES = "languages";
    static final String[] D_LANGUAGES = {
        "m5 8 6 6",
        "m4 14 6-6 2-3",
        "M2 5h12",
        "M7 2h1",
        "m22 22-5-10-5 10",
        "M14 18h6",
    };

    /** 侧边栏「字体选择」按钮（lucide {@code a-large-small}）。 */
    public static final String TYPOGRAPHY = "a-large-small";
    static final String[] D_TYPOGRAPHY = {
        "m15 16 2.536-7.328a1.02 1.02 1 0 1 1.928 0L22 16",
        "M15.697 14h5.606",
        "m2 16 4.039-9.69a.5.5 0 0 1 .923 0L11 16",
        "M3.304 13h6.392",
    };

    /** 侧边栏「调整大小」按钮（lucide {@code scaling}）。 */
    public static final String SCALING = "scaling";
    static final String[] D_SCALING = {
        "M12 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7",
        "M14 15H9v-5",
        "M16 3h5v5",
        "M21 3 9 15",
    };

    /** 侧边栏「公告中心」按钮（lucide {@code megaphone}）。 */
    public static final String MEGAPHONE = "megaphone";
    static final String[] D_MEGAPHONE = {
                    "M11 6a13 13 0 0 0 8.4-2.8A1 1 0 0 1 21 4v12a1 1 0 0 1-1.6.8A13 13 0 0 0 11 14H5a2 2 0 0 1-2-"
            +             "2V8a2 2 0 0 1 2-2z",
        "M6 14a12 12 0 0 0 2.4 7.2 2 2 0 0 0 3.2-2.4A8 8 0 0 1 10 14",
        "M8 6v8",
    };

    /** 音质·标准（lucide {@code signal-low}）。 */
    public static final String SIGNAL_LOW = "signal-low";
    static final String[] D_SIGNAL_LOW = {
        "M2 20h.01",
        "M7 20v-4",
    };

    /** 音质·高品质（lucide {@code signal-medium}）。 */
    public static final String SIGNAL_MEDIUM = "signal-medium";
    static final String[] D_SIGNAL_MEDIUM = {
        "M2 20h.01",
        "M7 20v-4",
        "M12 20v-8",
    };

    /** 音质·极高（lucide {@code signal-high}）。 */
    public static final String SIGNAL_HIGH = "signal-high";
    static final String[] D_SIGNAL_HIGH = {
        "M2 20h.01",
        "M7 20v-4",
        "M12 20v-8",
        "M17 20V8",
    };

    /** 音质·无损（lucide {@code signal}）。 */
    public static final String SIGNAL = "signal";
    static final String[] D_SIGNAL = {
        "M2 20h.01",
        "M7 20v-4",
        "M12 20v-8",
        "M17 20V8",
        "M22 4v16",
    };

    /** 音质·Hi-Res（lucide {@code audio-lines}）。 */
    public static final String AUDIO_LINES = "audio-lines";
    static final String[] D_AUDIO_LINES = {
        "M2 10v3",
        "M6 6v11",
        "M10 3v18",
        "M14 8v7",
        "M18 5v13",
        "M22 10v3",
    };

    /** 音质·高清环绕（lucide {@code waves}）。 */
    public static final String WAVES = "waves";
    static final String[] D_WAVES = {
        "M2 12q2.5 2 5 0t5 0 5 0 5 0",
        "M2 19q2.5 2 5 0t5 0 5 0 5 0",
        "M2 5q2.5 2 5 0t5 0 5 0 5 0",
    };

    /** 音质·沉浸音质（lucide {@code orbit}）。 */
    public static final String ORBIT = "orbit";
    static final String[] D_ORBIT = {
        "M20.341 6.484A10 10 0 0 1 10.266 21.85",
        "M3.659 17.516A10 10 0 0 1 13.74 2.152",
        "M9 12 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0",
        "M17 5 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0",
        "M3 19 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0",
    };

    /** 音质·母带（lucide {@code crown}）。 */
    public static final String CROWN = "crown";
    static final String[] D_CROWN = {
                    "M11.562 3.266a.5.5 0 0 1 .876 0L15.39 8.87a1 1 0 0 0 1.516.294L21.183 5.5a.5.5 0 0 1 .798.51"
            +             "9l-2.834 10.246a1 1 0 0 1-.956.734H5.81a1 1 0 0 1-.957-.734L2.02 6.02a.5.5 0 0 1 .798-.519l4"
            +             ".276 3.664a1 1 0 0 0 1.516-.294z",
        "M5 21h14",
    };

    /** 向导「连接账号」条目、完成页账号摘要（lucide {@code circle-user}）。 */
    public static final String USER = "circle-user";
    static final String[] D_USER = {
        "M2 12 a10 10 0 1 0 20 0 a10 10 0 1 0 -20 0",
        "M9 10 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0",
        "M7 20.662V19a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v1.662",
    };

    /** 向导「选择音源」条目、完成页音源摘要（lucide {@code radio}）。 */
    public static final String SOURCES = "radio";
    static final String[] D_SOURCES = {
        "M16.247 7.761a6 6 0 0 1 0 8.478",
        "M19.075 4.933a10 10 0 0 1 0 14.134",
        "M4.925 19.067a10 10 0 0 1 0-14.134",
        "M7.753 16.239a6 6 0 0 1 0-8.478",
        "M10 12 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0",
    };

    /** 向导「Automix 模型」条目（lucide {@code brain-circuit}）。 */
    public static final String AUTOMIX = "brain-circuit";
    static final String[] D_AUTOMIX = {
        "M12 5a3 3 0 1 0-5.997.125 4 4 0 0 0-2.526 5.77 4 4 0 0 0 .556 6.588A4 4 0 1 0 12 18Z",
        "M9 13a4.5 4.5 0 0 0 3-4",
        "M6.003 5.125A3 3 0 0 0 6.401 6.5",
        "M3.477 10.896a4 4 0 0 1 .585-.396",
        "M6 18a4 4 0 0 1-1.967-.516",
        "M12 13h4",
        "M12 18h6a2 2 0 0 1 2 2v1",
        "M12 8h8",
        "M16 8V5a2 2 0 0 1 2-2",
        "M15.5 13 a0.5 0.5 0 1 0 1 0 a0.5 0.5 0 1 0 -1 0",
        "M17.5 3 a0.5 0.5 0 1 0 1 0 a0.5 0.5 0 1 0 -1 0",
        "M19.5 21 a0.5 0.5 0 1 0 1 0 a0.5 0.5 0 1 0 -1 0",
        "M19.5 8 a0.5 0.5 0 1 0 1 0 a0.5 0.5 0 1 0 -1 0",
    };

    /** 向导「设置完成」条目（lucide {@code check-check}）。 */
    public static final String DONE = "check-check";
    static final String[] D_DONE = {
        "M18 6 7 17l-5-5",
        "m22 10-7.5 7.5L13 16",
    };

    /** 账号页那条登录/未登录状态（lucide {@code shield-check}）。 */
    public static final String SHIELD = "shield-check";
    static final String[] D_SHIELD = {
                    "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1."
            +             "2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z",
        "m9 12 2 2 4-4",
    };

    /** Automix·Beat This!（节拍与乐段）（lucide {@code activity}）。 */
    public static final String BEAT = "activity";
    static final String[] D_BEAT = {
                    "M22 12h-2.48a2 2 0 0 0-1.93 1.46l-2.35 8.36a.25.25 0 0 1-.48 0L9.24 2.18a.25.25 0 0 0-.48 0l"
            +             "-2.35 8.36A2 2 0 0 1 4.49 12H2",
    };

    /** Automix·HTDemucs（人声与伴奏）（lucide {@code wand-sparkles}）。 */
    public static final String SEPARATION = "wand-sparkles";
    static final String[] D_SEPARATION = {
                    "m21.64 3.64-1.28-1.28a1.21 1.21 0 0 0-1.72 0L2.36 18.64a1.21 1.21 0 0 0 0 1.72l1.28 1.28a1.2"
            +             " 1.2 0 0 0 1.72 0L21.64 5.36a1.2 1.2 0 0 0 0-1.72",
        "m14 7 3 3",
        "M5 6v4",
        "M19 14v4",
        "M10 2v2",
        "M7 8H3",
        "M21 16h-4",
        "M11 3H9",
    };

    /** Automix·必要运行组件（本机推理）（lucide {@code cpu}）。 */
    public static final String RUNTIME = "cpu";
    static final String[] D_RUNTIME = {
        "M12 20v2",
        "M12 2v2",
        "M17 20v2",
        "M17 2v2",
        "M2 12h2",
        "M2 17h2",
        "M2 7h2",
        "M20 12h2",
        "M20 17h2",
        "M20 7h2",
        "M7 20v2",
        "M7 2v2",
        "M6 4 H18 A2 2 0 0 1 20 6 V18 A2 2 0 0 1 18 20 H6 A2 2 0 0 1 4 18 V6 A2 2 0 0 1 6 4 Z",
        "M9 8 H15 A1 1 0 0 1 16 9 V15 A1 1 0 0 1 15 16 H9 A1 1 0 0 1 8 15 V9 A1 1 0 0 1 9 8 Z",
    };


    /** 图标名 → 需要描边的图元路径。 */
    private static final Map<String, String[]> TABLE = new HashMap<>();
    /** 图标名 → 需要填充的图元路径；只有带填充图元的图标（如 palette）才在这里。 */
    private static final Map<String, String[]> FILL = new HashMap<>();
    /** 解析失败过的名字：每帧重试一次解析既不划算也不会成功。 */
    private static final Set<String> FAILED = new HashSet<>();
    private static final Map<String, Path> CACHE = new HashMap<>();
    private static final Map<String, Path> FILL_CACHE = new HashMap<>();

    static {
        TABLE.put("settings-2", D_TUNE);
        TABLE.put("gauge", D_SPEED);
        TABLE.put("sparkles", D_SPARKLE);
        TABLE.put("eraser", D_SWEEP);
        TABLE.put("database", D_STORAGE);
        TABLE.put("ruler", D_RULER);
        TABLE.put("palette", D_PALETTE);
        TABLE.put("captions", D_CAPTION);
        TABLE.put("subtitles", D_SUBTITLES);
        TABLE.put("bell", D_ISLAND);
        TABLE.put("disc-3", D_ALBUM);
        TABLE.put("music-2", D_NOTE);
        TABLE.put("eye", D_VISIBILITY);
        TABLE.put("timer", D_TIMER);
        TABLE.put("mic", D_MIC);
        TABLE.put("move", D_MOVE);
        TABLE.put("panels-top-left", D_MODERN_UI);
        TABLE.put("list", D_CLASSIC_UI);
        TABLE.put("sparkle", D_FOLIA_UI);
        TABLE.put("maximize", D_FULLSCREEN);
        TABLE.put("x", D_CLOSE);
        TABLE.put("check", D_CHECK);
        TABLE.put("chevron-down", D_CHEVRON_DOWN);
        TABLE.put("chevron-up", D_CHEVRON_UP);
        TABLE.put("chevron-left", D_CHEVRON_LEFT);
        TABLE.put("chevron-right", D_CHEVRON_RIGHT);
        TABLE.put("rotate-ccw", D_RESTORE);
        TABLE.put("refresh-cw", D_REFRESH);
        TABLE.put("search", D_SEARCH);
        TABLE.put("folder-open", D_FOLDER);
        TABLE.put("trash-2", D_TRASH);
        TABLE.put("pipette", D_COLORIZE);
        TABLE.put("sun", D_SUN);
        TABLE.put("moon", D_MOON);
        TABLE.put("info", D_INFO);
        TABLE.put("triangle-alert", D_WARNING);
        TABLE.put("keyboard", D_KEYBOARD);
        TABLE.put("grip", D_DRAG_HANDLE);
        TABLE.put("menu", D_GRIP);
        TABLE.put("music", D_MUSIC);
        TABLE.put("loader-2", D_LOADER);
        TABLE.put("languages", D_LANGUAGES);
        TABLE.put("a-large-small", D_TYPOGRAPHY);
        TABLE.put("scaling", D_SCALING);
        TABLE.put("megaphone", D_MEGAPHONE);
        TABLE.put("signal-low", D_SIGNAL_LOW);
        TABLE.put("signal-medium", D_SIGNAL_MEDIUM);
        TABLE.put("signal-high", D_SIGNAL_HIGH);
        TABLE.put("signal", D_SIGNAL);
        TABLE.put("audio-lines", D_AUDIO_LINES);
        TABLE.put("waves", D_WAVES);
        TABLE.put("orbit", D_ORBIT);
        TABLE.put("crown", D_CROWN);
        TABLE.put("circle-user", D_USER);
        TABLE.put("radio", D_SOURCES);
        TABLE.put("brain-circuit", D_AUTOMIX);
        TABLE.put("check-check", D_DONE);
        TABLE.put("shield-check", D_SHIELD);
        TABLE.put("activity", D_BEAT);
        TABLE.put("wand-sparkles", D_SEPARATION);
        TABLE.put("cpu", D_RUNTIME);
        FILL.put("palette", FD_PALETTE);
    }

    private FoliaLucideIcons() {
    }

    /** 这张表里有哪些图标名，给单测和排查用。 */
    public static Set<String> names() {
        return TABLE.keySet();
    }

    /**
     * 把 {@code name} 这枚图标以 {@code (centerX, centerY)} 为中心画进边长 {@code size} 的方格。
     *
     * <p>描边宽度传的是网格内的那个数：Skia 会连描边一起按当前变换放大，于是观感与网页上
     * {@code stroke-width:2} 配 {@code width:20px} 完全一致。写死 2px 会让大图标明显偏细。</p>
     *
     * <p>有填充图元的图标先整体填充、再整体描边。SVG 是按文档顺序逐图元画的，两者只在
     * 「填充的那一小块与别的描边笔画重叠」时才可能有肉眼差别，而 lucide 这批图标里填充的
     * 都是些孤立的小圆点，不重叠。</p>
     *
     * @param strokeWidth 网格内的描边宽度，传 0 及以下用 lucide 默认的 {@link #STROKE_WIDTH}
     * @return 真的画了才返回 true。名字未知、不在 Skija 帧内、路径解析失败都返回 false，
     *         由调用方退回字形图标 —— 一枚图标画不出来绝不能把整个界面开崩。
     */
    public static boolean draw(String name, double centerX, double centerY, double size,
                              int argb, double strokeWidth) {
        if (!(size > 0) || !SkijaGfx.active()) {
            return false;
        }
        Path stroked = pathOf(name, TABLE, CACHE);
        Path filled = pathOf(name, FILL, FILL_CACHE);
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null || (stroked == null && filled == null)) {
            return false;
        }
        double grid = strokeWidth > 0 ? strokeWidth : STROKE_WIDTH;
        SkijaGfx.pushMatrix();
        try {
            SkijaGfx.translate(centerX - size * .5, centerY - size * .5);
            SkijaGfx.scale(size / GRID, size / GRID);
            if (filled != null) {
                canvas.drawPath(filled, SkijaGfx.fill(argb));
            }
            if (stroked != null) {
                Paint paint = SkijaGfx.stroke(argb, grid);
                paint.setStrokeCap(PaintStrokeCap.ROUND);
                paint.setStrokeJoin(PaintStrokeJoin.ROUND);
                canvas.drawPath(stroked, paint);
            }
        } catch (Throwable ignored) {
            return false;
        } finally {
            SkijaGfx.popMatrix();
        }
        return true;
    }

    /** 按 lucide 默认描边画。 */
    public static boolean draw(String name, double centerX, double centerY, double size, int argb) {
        return draw(name, centerX, centerY, size, argb, 0);
    }

    /**
     * 24 网格里的描边路径，只给测试用 —— 测试里没有正在画的一帧，走不了 {@link #draw}。
     *
     * <p><b>不要关闭返回的 {@link Path}</b>：那是缓存里的同一份，关掉之后所有界面都画不出来。</p>
     */
    public static Path pathForTest(String name) {
        return pathOf(name, TABLE, CACHE);
    }

    private static Path pathOf(String name, Map<String, String[]> table, Map<String, Path> cache) {
        if (name == null) {
            return null;
        }
        synchronized (cache) {
            Path cached = cache.get(name);
            if (cached == null && !FAILED.contains(name)) {
                cached = build(table.get(name));
                if (cached == null) {
                    if (cache == CACHE) {
                        FAILED.add(name);   // 只有描边集合缺席才算这枚图标整体不可用
                    }
                } else {
                    cache.put(name, cached);
                }
            }
            return cached;
        }
    }

    /**
     * 逐条 {@code d} 解析后并成一个路径。
     *
     * <p>合并子路径再描边，与 SVG 逐个图元描边的结果一致：lucide 的图元之间从不需要分别
     * 上色或分别填色。</p>
     */
    private static Path build(String[] paths) {
        if (paths == null || paths.length == 0) {
            return null;
        }
        PathBuilder builder = new PathBuilder();
        try {
            for (String d : paths) {
                try (Path raw = Path.makeFromSVGString(d)) {
                    builder.addPath(raw);
                }
            }
            Path merged = builder.snapshot();
            Rect bounds = merged.getBounds();
            if (bounds.getWidth() <= 0 && bounds.getHeight() <= 0) {
                merged.close();
                return null;
            }
            return merged;
        } catch (Throwable ignored) {
            return null;
        } finally {
            builder.close();
        }
    }
}
