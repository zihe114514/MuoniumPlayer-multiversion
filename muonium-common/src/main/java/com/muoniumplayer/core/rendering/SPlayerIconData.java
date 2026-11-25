package com.muoniumplayer.core.rendering;

import java.util.HashMap;
import java.util.Map;

/**
 * SPlayer 风格主界面用的 <b>lucide 矢量图标表</b>（数据来自 {@code @iconify-json/lucide 1.2.146}）。
 *
 * <p>本文件由 SPlayer 改造期间的生成脚本 {@code make_splayer_icons.py} 产出，不要手改：每个图元
 * （path/circle/rect/line/poly*）都已折算成 SVG path 字符串，运行期由 {@link SPlayerIcons}
 * 交给 Skija 在 GPU 画布上描边/填充，不经过任何 CPU 位图。</p>
 *
 * <p>图标数据来自 lucide（ISC 许可，Copyright (c) Lucide Icons and Contributors）；
 * {@code sp-play-order}（顺序播放）是四段直线，按 SPlayer 界面里的同名图标形状重画。</p>
 */
final class SPlayerIconData {

    /** 图标名 → 描边图元。 */
    static final Map<String, String[]> STROKE = new HashMap<>();
    /** 图标名 → 填充图元（只有带实心部分的图标才有）。 */
    static final Map<String, String[]> FILL = new HashMap<>();
    /** 不在 24 网格上的图标的网格边长。 */
    static final Map<String, Double> GRID = new HashMap<>();

    private SPlayerIconData() {
    }

    static {
        part0();
        part1();
        part2();
        part3();
    }

    private static void part0() {
        STROKE.put("house", new String[] {
            "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8",
            "M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1"
                + "-2-2z",
        });
        STROKE.put("music", new String[] {
            "M9 18V5l12-2v13",
            "M3 18 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0",
            "M15 16 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0",
        });
        STROKE.put("user", new String[] {
            "M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2",
            "M8 7 a4 4 0 1 0 8 0 a4 4 0 1 0 -8 0",
        });
        STROKE.put("user-round", new String[] {
            "M7 8 a5 5 0 1 0 10 0 a5 5 0 1 0 -10 0",
            "M20 21a8 8 0 0 0-16 0",
        });
        STROKE.put("disc-3", new String[] {
            "M2 12 a10 10 0 1 0 20 0 a10 10 0 1 0 -20 0",
            "M6 12c0-1.7.7-3.2 1.8-4.2",
            "M10 12 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0",
            "M18 12c0 1.7-.7 3.2-1.8 4.2",
        });
        STROKE.put("disc", new String[] {
            "M2 12 a10 10 0 1 0 20 0 a10 10 0 1 0 -20 0",
            "M10 12 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0",
        });
        STROKE.put("folder", new String[] {
            "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0"
                + "-2 2v13a2 2 0 0 0 2 2Z",
        });
        STROKE.put("folder-open", new String[] {
            "m6 14l1.5-2.9A2 2 0 0 1 9.24 10H20a2 2 0 0 1 1.94 2.5l-1.54 6a2 2 0 0 1-1.95 1.5H4a2 2 0 0 1-2-2"
                + "V5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.81 1.2a2 2 0 0 0 1.67.9H18a2 2 0 0 1 2 2v2",
        });
        STROKE.put("folder-plus", new String[] {
            "M12 10v6m-3-3h6m5 7a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 "
                + "3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z",
        });
        STROKE.put("chart-pie", new String[] {
            "M21 12c.552 0 1.005-.449.95-.998a10 10 0 0 0-8.953-8.951c-.55-.055-.998.398-.998.95v8a1 1 0 0 0 "
                + "1 1z",
            "M21.21 15.89A10 10 0 1 1 8 2.83",
        });
        STROKE.put("library", new String[] {
            "m16 6l4 14M12 6v14M8 8v12M4 4v16",
        });
        STROKE.put("heart", new String[] {
            "M2 9.5a5.5 5.5 0 0 1 9.591-3.676a.56.56 0 0 0 .818 0A5.49 5.49 0 0 1 22 9.5c0 2.29-1.5 4-3 5.5l-"
                + "5.492 5.313a2 2 0 0 1-3 .019L5 15c-1.5-1.5-3-3.2-3-5.5",
        });
        STROKE.put("heart-off", new String[] {
            "M10.5 4.893a5.5 5.5 0 0 1 1.091.931a.56.56 0 0 0 .818 0A5.49 5.49 0 0 1 22 9.5c0 1.872-1.002 3.3"
                + "56-2.187 4.655m-2.846 2.812l-3.459 3.346a2 2 0 0 1-3 .019L5 15c-1.5-1.5-3-3.2-3-5.5a5.5 5.5 0 0 "
                + "1 2.747-4.761M2 2l20 20",
        });
        STROKE.put("star", new String[] {
            "M11.525 2.295a.53.53 0 0 1 .95 0l2.31 4.679a2.12 2.12 0 0 0 1.595 1.16l5.166.756a.53.53 0 0 1 .2"
                + "94.904l-3.736 3.638a2.12 2.12 0 0 0-.611 1.878l.882 5.14a.53.53 0 0 1-.771.56l-4.618-2.428a2.12 "
                + "2.12 0 0 0-1.973 0L6.396 21.01a.53.53 0 0 1-.77-.56l.881-5.139a2.12 2.12 0 0 0-.611-1.879L2.16 9"
                + ".795a.53.53 0 0 1 .294-.906l5.165-.755a2.12 2.12 0 0 0 1.597-1.16z",
        });
        STROKE.put("history", new String[] {
            "M3 12a9 9 0 1 0 9-9a9.75 9.75 0 0 0-6.74 2.74L3 8",
            "M3 3v5h5m4-1v5l4 2",
        });
        STROKE.put("download", new String[] {
            "M12 15V3m9 12v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4",
            "m7 10l5 5l5-5",
        });
        STROKE.put("cloud", new String[] {
            "M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9",
        });
        STROKE.put("cloud-download", new String[] {
            "M12 13v8l-4-4m4 4l4-4",
            "M4.393 15.269A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.436 8.284",
        });
        STROKE.put("cloud-upload", new String[] {
            "M12 13v8m-8-6.101A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.242",
            "m8 17l4-4l4 4",
        });
        STROKE.put("list-music", new String[] {
            "M16 5H3m8 7H3m8 7H3m18-3V5",
            "M15 16 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0",
        });
        STROKE.put("list-plus", new String[] {
            "M16 5H3m8 7H3m13 7H3M18 9v6m3-3h-6",
        });
        STROKE.put("list-minus", new String[] {
            "M16 5H3m8 7H3m13 7H3m18-7h-6",
        });
        STROKE.put("list-end", new String[] {
            "M16 5H3m13 7H3m6 7H3m13-3l-3 3l3 3",
            "M21 5v12a2 2 0 0 1-2 2h-6",
        });
        STROKE.put("list-checks", new String[] {
            "M13 5h8m-8 7h8m-8 7h8M3 17l2 2l4-4M3 7l2 2l4-4",
        });
        STROKE.put("list", new String[] {
            "M3 5h.01M3 12h.01M3 19h.01M8 5h13M8 12h13M8 19h13",
        });
        STROKE.put("plus", new String[] {
            "M5 12h14m-7-7v14",
        });
        STROKE.put("minus", new String[] {
            "M5 12h14",
        });
        STROKE.put("x", new String[] {
            "M18 6L6 18M6 6l12 12",
        });
        STROKE.put("check", new String[] {
            "M20 6L9 17l-5-5",
        });
        STROKE.put("chevron-down", new String[] {
            "m6 9l6 6l6-6",
        });
        STROKE.put("chevron-up", new String[] {
            "m18 15l-6-6l-6 6",
        });
        STROKE.put("chevron-left", new String[] {
            "m15 18l-6-6l6-6",
        });
        STROKE.put("chevron-right", new String[] {
            "m9 18l6-6l-6-6",
        });
        STROKE.put("arrow-left", new String[] {
            "m12 19l-7-7l7-7m7 7H5",
        });
        STROKE.put("arrow-right", new String[] {
            "M5 12h14m-7-7l7 7l-7 7",
        });
        STROKE.put("arrow-up-right", new String[] {
            "M7 7h10v10M7 17L17 7",
        });
        STROKE.put("arrow-up-down", new String[] {
            "m21 16l-4 4l-4-4m4 4V4M3 8l4-4l4 4M7 4v16",
        });
        STROKE.put("search", new String[] {
            "m21 21l-4.34-4.34",
            "M3 11 a8 8 0 1 0 16 0 a8 8 0 1 0 -16 0",
        });
        STROKE.put("settings", new String[] {
            "M9.671 4.136a2.34 2.34 0 0 1 4.659 0a2.34 2.34 0 0 0 3.319 1.915a2.34 2.34 0 0 1 2.33 4.033a2.34"
                + " 2.34 0 0 0 0 3.831a2.34 2.34 0 0 1-2.33 4.033a2.34 2.34 0 0 0-3.319 1.915a2.34 2.34 0 0 1-4.659"
                + " 0a2.34 2.34 0 0 0-3.32-1.915a2.34 2.34 0 0 1-2.33-4.033a2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1"
                + " 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915",
            "M9 12 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0",
        });
        STROKE.put("settings-2", new String[] {
            "M14 17H5M19 7h-9",
            "M14 17 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0",
            "M4 7 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0",
        });
    }

    private static void part1() {
        STROKE.put("play", new String[] {
            "M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z",
        });
        STROKE.put("pause", new String[] {
            "M15 3 H18 A1 1 0 0 1 19 4 V20 A1 1 0 0 1 18 21 H15 A1 1 0 0 1 14 20 V4 A1 1 0 0 1 15 3 Z",
            "M6 3 H9 A1 1 0 0 1 10 4 V20 A1 1 0 0 1 9 21 H6 A1 1 0 0 1 5 20 V4 A1 1 0 0 1 6 3 Z",
        });
        STROKE.put("skip-back", new String[] {
            "M17.971 4.285A2 2 0 0 1 21 6v12a2 2 0 0 1-3.029 1.715l-9.997-5.998a2 2 0 0 1-.003-3.432zM3 20V4",
        });
        STROKE.put("skip-forward", new String[] {
            "M21 4v16M6.029 4.285A2 2 0 0 0 3 6v12a2 2 0 0 0 3.029 1.715l9.997-5.998a2 2 0 0 0 .003-3.432z",
        });
        STROKE.put("repeat", new String[] {
            "m17 2l4 4l-4 4",
            "M3 11v-1a4 4 0 0 1 4-4h14M7 22l-4-4l4-4",
            "M21 13v1a4 4 0 0 1-4 4H3",
        });
        STROKE.put("repeat-1", new String[] {
            "m17 2l4 4l-4 4",
            "M3 11v-1a4 4 0 0 1 4-4h14M7 22l-4-4l4-4",
            "M21 13v1a4 4 0 0 1-4 4H3m8-8h1v4",
        });
        STROKE.put("repeat-2", new String[] {
            "m2 9l3-3l3 3",
            "M13 18H7a2 2 0 0 1-2-2V6m17 9l-3 3l-3-3",
            "M11 6h6a2 2 0 0 1 2 2v10",
        });
        STROKE.put("shuffle", new String[] {
            "m18 14l4 4l-4 4m0-20l4 4l-4 4",
            "M2 18h1.973a4 4 0 0 0 3.3-1.7l5.454-8.6a4 4 0 0 1 3.3-1.7H22M2 6h1.972a4 4 0 0 1 3.6 2.2M22 18h-"
                + "6.041a4 4 0 0 1-3.3-1.8l-.359-.45",
        });
        STROKE.put("infinity", new String[] {
            "M6 16c5 0 7-8 12-8a4 4 0 0 1 0 8c-5 0-7-8-12-8a4 4 0 1 0 0 8",
        });
        STROKE.put("volume", new String[] {
            "M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 "
                + "0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z",
        });
        STROKE.put("volume-x", new String[] {
            "M11 4.702a.7.7 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 "
                + "1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.7.7 0 0 0 11 19.298zm5.5 9.798l5-5m-5 0l5 5",
        });
        STROKE.put("volume-1", new String[] {
            "M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 "
                + "0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298zM16 9a5 5 0 0 1 0 6",
        });
        STROKE.put("volume-2", new String[] {
            "M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 "
                + "0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298zM16 9a5 5 0 0 1 0 6m3.36"
                + "4 3.364a9 9 0 0 0 0-12.728",
        });
        STROKE.put("captions", new String[] {
            "M5 5 H19 A2 2 0 0 1 21 7 V17 A2 2 0 0 1 19 19 H5 A2 2 0 0 1 3 17 V7 A2 2 0 0 1 5 5 Z",
            "M7 15h4m4 0h2M7 11h2m4 0h4",
        });
        STROKE.put("radio", new String[] {
            "M16.247 7.761a6 6 0 0 1 0 8.478m2.828-11.306a10 10 0 0 1 0 14.134m-14.15 0a10 10 0 0 1 0-14.134m"
                + "2.828 11.306a6 6 0 0 1 0-8.478",
            "M10 12 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0",
        });
        STROKE.put("ellipsis", new String[] {
            "M11 12 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
            "M18 12 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
            "M4 12 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
        });
        STROKE.put("ellipsis-vertical", new String[] {
            "M11 12 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
            "M11 5 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
            "M11 19 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
        });
        STROKE.put("headphones", new String[] {
            "M3 14h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a9 9 0 0 1 18 0v7a2 2 0 0 1-2 2h-1a2 2 0"
                + " 0 1-2-2v-3a2 2 0 0 1 2-2h3",
        });
        STROKE.put("calendar", new String[] {
            "M8 2v3m8-3v3",
            "M5 3 H19 A2 2 0 0 1 21 5 V19 A2 2 0 0 1 19 21 H5 A2 2 0 0 1 3 19 V5 A2 2 0 0 1 5 3 Z",
            "M3 9h18",
        });
        STROKE.put("calendar-days", new String[] {
            "M8 2v3m8-3v3",
            "M5 3 H19 A2 2 0 0 1 21 5 V19 A2 2 0 0 1 19 21 H5 A2 2 0 0 1 3 19 V5 A2 2 0 0 1 5 3 Z",
            "M3 9h18M8 13h.01M12 13h.01M16 13h.01M8 17h.01M12 17h.01M16 17h.01",
        });
        STROKE.put("hourglass", new String[] {
            "M5 22h14M5 2h14m-2 20v-4.172a2 2 0 0 0-.586-1.414L12 12l-4.414 4.414A2 2 0 0 0 7 17.828V22M7 2v4"
                + ".172a2 2 0 0 0 .586 1.414L12 12l4.414-4.414A2 2 0 0 0 17 6.172V2",
        });
        STROKE.put("globe", new String[] {
            "M2 12 a10 10 0 1 0 20 0 a10 10 0 1 0 -20 0",
            "M12 2a14.5 14.5 0 0 0 0 20a14.5 14.5 0 0 0 0-20M2 12h20",
        });
        STROKE.put("hard-drive", new String[] {
            "M10 16h.01m-7.798-4.423a2 2 0 0 0-.212.896V18a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-5.527a2 2 0 0 0-.2"
                + "12-.896L18.55 5.11A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11zm19.734.436H2.054M6 16h.01",
        });
        STROKE.put("trash-2", new String[] {
            "M10 11v6m4-6v6m5-11v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 "
                + "2v2",
        });
        STROKE.put("copy", new String[] {
            "M10 8 H20 A2 2 0 0 1 22 10 V20 A2 2 0 0 1 20 22 H10 A2 2 0 0 1 8 20 V10 A2 2 0 0 1 10 8 Z",
            "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2",
        });
        STROKE.put("pencil", new String[] {
            "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.62"
                + "2l4.353-1.32a2 2 0 0 0 .83-.497zM15 5l4 4",
        });
        STROKE.put("square-pen", new String[] {
            "M12 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7",
            "M18.375 2.625a1 1 0 0 1 3 3l-9.013 9.014a2 2 0 0 1-.853.505l-2.873.84a.5.5 0 0 1-.62-.62l.84-2.8"
                + "73a2 2 0 0 1 .506-.852z",
        });
        STROKE.put("log-in", new String[] {
            "m10 17l5-5l-5-5m5 5H3m12-9h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4",
        });
        STROKE.put("log-out", new String[] {
            "m16 17l5-5l-5-5m5 5H9m0 9H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4",
        });
        STROKE.put("users", new String[] {
            "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M16 3.128a4 4 0 0 1 0 7.744M22 21v-2a4 4 0 0 0-3-3.87",
            "M5 7 a4 4 0 1 0 8 0 a4 4 0 1 0 -8 0",
        });
        STROKE.put("user-plus", new String[] {
            "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2",
            "M5 7 a4 4 0 1 0 8 0 a4 4 0 1 0 -8 0",
            "M19 8v6m3-3h-6",
        });
        STROKE.put("sliders-horizontal", new String[] {
            "M10 5H3m9 14H3M14 3v4m2 10v4m5-9h-9m9 7h-5m5-14h-7m-6 5v4m0-2H3",
        });
        STROKE.put("refresh-cw", new String[] {
            "M3 12a9 9 0 0 1 9-9a9.75 9.75 0 0 1 6.74 2.74L21 8",
            "M21 3v5h-5m5 4a9 9 0 0 1-9 9a9.75 9.75 0 0 1-6.74-2.74L3 16",
            "M8 16H3v5",
        });
        STROKE.put("rotate-ccw", new String[] {
            "M3 12a9 9 0 1 0 9-9a9.75 9.75 0 0 0-6.74 2.74L3 8",
            "M3 3v5h5",
        });
        STROKE.put("palette", new String[] {
            "M12 22a1 1 0 0 1 0-20a10 9 0 0 1 10 9a5 5 0 0 1-5 5h-2.25a1.75 1.75 0 0 0-1.4 2.8l.3.4a1.75 1.75"
                + " 0 0 1-1.4 2.8z",
        });
        FILL.put("palette", new String[] {
            "M13 6.5 a0.5 0.5 0 1 0 1 0 a0.5 0.5 0 1 0 -1 0",
            "M17 10.5 a0.5 0.5 0 1 0 1 0 a0.5 0.5 0 1 0 -1 0",
            "M6 12.5 a0.5 0.5 0 1 0 1 0 a0.5 0.5 0 1 0 -1 0",
            "M8 7.5 a0.5 0.5 0 1 0 1 0 a0.5 0.5 0 1 0 -1 0",
        });
        STROKE.put("type", new String[] {
            "M12 4v16M4 7V5a1 1 0 0 1 1-1h14a1 1 0 0 1 1 1v2M9 20h6",
        });
        STROKE.put("a-large-small", new String[] {
            "m15 16l2.536-7.328a1.02 1.02 1 0 1 1.928 0L22 16m-6.303-2h5.606M2 16l4.039-9.69a.5.5 0 0 1 .923 "
                + "0L11 16m-7.696-3h6.392",
        });
        STROKE.put("external-link", new String[] {
            "M15 3h6v6m-11 5L21 3m-3 10v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6",
        });
        STROKE.put("square-arrow-out-up-right", new String[] {
            "M21 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h6m10 0l-9 9m3-9h6v6",
        });
        STROKE.put("loader-circle", new String[] {
            "M21 12a9 9 0 1 1-6.219-8.56",
        });
    }

    private static void part2() {
        STROKE.put("circle-arrow-up", new String[] {
            "M2 12 a10 10 0 1 0 20 0 a10 10 0 1 0 -20 0",
            "m16 12l-4-4l-4 4m4 4V8",
        });
        STROKE.put("monitor", new String[] {
            "M4 3 H20 A2 2 0 0 1 22 5 V15 A2 2 0 0 1 20 17 H4 A2 2 0 0 1 2 15 V5 A2 2 0 0 1 4 3 Z",
            "M8 21h8m-4-4v4",
        });
        STROKE.put("sun", new String[] {
            "M8 12 a4 4 0 1 0 8 0 a4 4 0 1 0 -8 0",
            "M12 2v2m0 16v2M4.93 4.93l1.41 1.41m11.32 11.32l1.41 1.41M2 12h2m16 0h2M6.34 17.66l-1.41 1.41M19."
                + "07 4.93l-1.41 1.41",
        });
        STROKE.put("moon", new String[] {
            "M20.985 12.486a9 9 0 1 1-9.473-9.472c.405-.022.617.46.402.803a6 6 0 0 0 8.268 8.268c.344-.215.82"
                + "5-.004.803.401",
        });
        STROKE.put("scaling", new String[] {
            "M12 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7",
            "M14 15H9v-5m7-7h5v5m0-5L9 15",
        });
        STROKE.put("megaphone", new String[] {
            "M11 6a13 13 0 0 0 8.4-2.8A1 1 0 0 1 21 4v12a1 1 0 0 1-1.6.8A13 13 0 0 0 11 14H5a2 2 0 0 1-2-2V8a"
                + "2 2 0 0 1 2-2z",
            "M6 14a12 12 0 0 0 2.4 7.2a2 2 0 0 0 3.2-2.4A8 8 0 0 1 10 14M8 6v8",
        });
        STROKE.put("bell", new String[] {
            "M10.268 21a2 2 0 0 0 3.464 0m-10.47-5.674A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 "
                + "12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326",
        });
        STROKE.put("gauge", new String[] {
            "m12 14l4-4M3.34 19a10 10 0 1 1 17.32 0",
        });
        STROKE.put("audio-waveform", new String[] {
            "M2 13a2 2 0 0 0 2-2V7a2 2 0 0 1 4 0v13a2 2 0 0 0 4 0V4a2 2 0 0 1 4 0v13a2 2 0 0 0 4 0v-4a2 2 0 0"
                + " 1 2-2",
        });
        STROKE.put("audio-lines", new String[] {
            "M2 10v3m4-7v11m4-14v18m4-13v7m4-10v13m4-8v3",
        });
        STROKE.put("cast", new String[] {
            "M2 8V6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6M2 12a9 9 0 0 1 8 8m-8-4a5 5 0 0 1 4 4m"
                + "-4 0h.01",
        });
        STROKE.put("smartphone", new String[] {
            "M7 2 H17 A2 2 0 0 1 19 4 V20 A2 2 0 0 1 17 22 H7 A2 2 0 0 1 5 20 V4 A2 2 0 0 1 7 2 Z",
            "M12 18h.01",
        });
        STROKE.put("radio-tower", new String[] {
            "M4.9 16.1C1 12.2 1 5.8 4.9 1.9m2.9 2.8a6.14 6.14 0 0 0-.8 7.5",
            "M10 9 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0",
            "M16.2 4.8c2 2 2.26 5.11.8 7.47M19.1 1.9a9.96 9.96 0 0 1 0 14.1m-9.6 2h5M8 22l4-11l4 11",
        });
        STROKE.put("trophy", new String[] {
            "M10 14.66V17a1 1 0 0 1-1 1a2 2 0 0 0-2 2v2m7-7.34V17a1 1 0 0 0 1 1a2 2 0 0 1 2 2v2m.916-12H19.5A"
                + "2.5 2.5 0 0 0 22 7.5V5a1 1 0 0 0-1-1h-3M4 22h16",
            "M6 9a6 6 0 0 0 12 0V3a1 1 0 0 0-1-1H7a1 1 0 0 0-1 1z",
            "M6.084 10H4.5A2.5 2.5 0 0 1 2 7.5V5a1 1 0 0 1 1-1h3",
        });
        STROKE.put("flame", new String[] {
            "M12 3q1 4 4 6.5t3 5.5a1 1 0 0 1-14 0a5 5 0 0 1 1-3a1 1 0 0 0 5 0c0-2-1.5-3-1.5-5q0-2 2.5-4",
        });
        STROKE.put("clock", new String[] {
            "M2 12 a10 10 0 1 0 20 0 a10 10 0 1 0 -20 0",
            "M12 6v6l4 2",
        });
        STROKE.put("mic-vocal", new String[] {
            "m11 7.601l-5.994 8.19a1 1 0 0 0 .1 1.298l.817.818a1 1 0 0 0 1.314.087L15.09 12",
            "M16.5 21.174C15.5 20.5 14.372 20 13 20c-2.058 0-3.928 2.356-6 2s-2.775-3.369-1.5-4.5",
            "M11 7 a5 5 0 1 0 10 0 a5 5 0 1 0 -10 0",
        });
        STROKE.put("podcast", new String[] {
            "M16.85 18.58a9 9 0 1 0-9.7 0",
            "M8 14a5 5 0 1 1 8 0",
        });
        FILL.put("podcast", new String[] {
            "M13 17a1 1 0 1 0-2 0l.5 4.5a.5.5 0 0 0 1 0z",
            "M11 11 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
        });
        STROKE.put("share-2", new String[] {
            "M15 5 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0",
            "M3 12 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0",
            "M15 19 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0",
            "m8.59 13.51l6.83 3.98m-.01-10.98l-6.82 3.98",
        });
        STROKE.put("message-circle", new String[] {
            "M2.992 16.342a2 2 0 0 1 .094 1.167l-1.065 3.29a1 1 0 0 0 1.236 1.168l3.413-.998a2 2 0 0 1 1.099."
                + "092a10 10 0 1 0-4.777-4.719",
        });
        STROKE.put("sparkles", new String[] {
            "M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l"
                + "-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-1"
                + ".594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594zM20 2v4m2-2h-4",
            "M2 20 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0",
        });
        STROKE.put("crown", new String[] {
            "M11.562 3.266a.5.5 0 0 1 .876 0L15.39 8.87a1 1 0 0 0 1.516.294L21.183 5.5a.5.5 0 0 1 .798.519l-2"
                + ".834 10.246a1 1 0 0 1-.956.734H5.81a1 1 0 0 1-.957-.734L2.02 6.02a.5.5 0 0 1 .798-.519l4.276 3.6"
                + "64a1 1 0 0 0 1.516-.294zM5 21h14",
        });
        STROKE.put("link", new String[] {
            "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71",
            "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71",
        });
        STROKE.put("import", new String[] {
            "M12 3v12m-4-4l4 4l4-4",
            "M8 5H4a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-4",
        });
        STROKE.put("file-music", new String[] {
            "M11.65 22H18a2 2 0 0 0 2-2V8a2.4 2.4 0 0 0-.706-1.706l-3.588-3.588A2.4 2.4 0 0 0 14 2H6a2 2 0 0 "
                + "0-2 2v10.35",
            "M14 2v5a1 1 0 0 0 1 1h5M8 20v-7l3 1.474",
            "M4 20 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0",
        });
        STROKE.put("dices", new String[] {
            "M4 10 H12 A2 2 0 0 1 14 12 V20 A2 2 0 0 1 12 22 H4 A2 2 0 0 1 2 20 V12 A2 2 0 0 1 4 10 Z",
            "m17.92 14l3.5-3.5a2.24 2.24 0 0 0 0-3l-5-4.92a2.24 2.24 0 0 0-3 0L10 6M6 18h.01M10 14h.01M15 6h."
                + "01M18 9h.01",
        });
        STROKE.put("wand-sparkles", new String[] {
            "m21.64 3.64l-1.28-1.28a1.21 1.21 0 0 0-1.72 0L2.36 18.64a1.21 1.21 0 0 0 0 1.72l1.28 1.28a1.2 1."
                + "2 0 0 0 1.72 0L21.64 5.36a1.2 1.2 0 0 0 0-1.72M14 7l3 3M5 6v4m14 4v4M10 2v2M7 8H3m18 8h-4M11 3H9",
        });
        STROKE.put("eye-off", new String[] {
            "M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575a1 1 0 0 1 0 .696a10.8 10.8 0 0 1-1.444 2.49m-6.41"
                + "-.679a3 3 0 0 1-4.242-4.242",
            "M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151a1 1 0 0 1 0-.696a10.75 10.75 0 0 1 4.446-5.143M2 2"
                + "l20 20",
        });
        STROKE.put("thumbs-up", new String[] {
            "M15 5.88L14 10h5.83a2 2 0 0 1 1.92 2.56l-2.33 8A2 2 0 0 1 17.5 22H4a2 2 0 0 1-2-2v-8a2 2 0 0 1 2"
                + "-2h2.76a2 2 0 0 0 1.79-1.11L12 2a3.13 3.13 0 0 1 3 3.88M7 10v12",
        });
        STROKE.put("info", new String[] {
            "M2 12 a10 10 0 1 0 20 0 a10 10 0 1 0 -20 0",
            "M12 16v-4m0-4h.01",
        });
        STROKE.put("triangle-alert", new String[] {
            "m21.73 18l-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3M12 9v4m0 4h.01",
        });
        STROKE.put("gamepad-2", new String[] {
            "M6 11h4M8 9v4m7-1h.01M18 10h.01m-.69-5H6.68a4 4 0 0 0-3.978 3.59l-.017.152C2.604 9.416 2 14.456 "
                + "2 16a3 3 0 0 0 3 3c1 0 1.5-.5 2-1l1.414-1.414A2 2 0 0 1 9.828 16h4.344a2 2 0 0 1 1.414.586L17 18"
                + "c.5.5 1 1 2 1a3 3 0 0 0 3-3c0-1.545-.604-6.584-.685-7.258q-.01-.075-.017-.151A4 4 0 0 0 17.32 5",
        });
        STROKE.put("party-popper", new String[] {
            "M5.8 11.3L2 22l10.7-3.79M4 3h.01M22 8h.01M15 2h.01M22 20h.01M22 2l-2.24.75a2.9 2.9 0 0 0-1.96 3."
                + "12c.1.86-.57 1.63-1.45 1.63h-.38c-.86 0-1.6.6-1.76 1.44L14 10m8 3l-.82-.33c-.86-.34-1.82.2-1.98 "
                + "1.11c-.11.7-.72 1.22-1.43 1.22H17M11 2l.33.82c.34.86-.2 1.82-1.11 1.98c-.7.1-1.22.72-1.22 1.43V7",
            "M11 13c1.93 1.93 2.83 4.17 2 5s-3.07-.07-5-2s-2.83-4.17-2-5s3.07.07 5 2",
        });
        STROKE.put("tv", new String[] {
            "m17 2l-5 5l-5-5",
            "M4 7 H20 A2 2 0 0 1 22 9 V20 A2 2 0 0 1 20 22 H4 A2 2 0 0 1 2 20 V9 A2 2 0 0 1 4 7 Z",
        });
        STROKE.put("layout-grid", new String[] {
            "M4 3 H9 A1 1 0 0 1 10 4 V9 A1 1 0 0 1 9 10 H4 A1 1 0 0 1 3 9 V4 A1 1 0 0 1 4 3 Z",
            "M15 3 H20 A1 1 0 0 1 21 4 V9 A1 1 0 0 1 20 10 H15 A1 1 0 0 1 14 9 V4 A1 1 0 0 1 15 3 Z",
            "M15 14 H20 A1 1 0 0 1 21 15 V20 A1 1 0 0 1 20 21 H15 A1 1 0 0 1 14 20 V15 A1 1 0 0 1 15 14 Z",
            "M4 14 H9 A1 1 0 0 1 10 15 V20 A1 1 0 0 1 9 21 H4 A1 1 0 0 1 3 20 V15 A1 1 0 0 1 4 14 Z",
        });
        STROKE.put("trending-up", new String[] {
            "M16 7h6v6",
            "m22 7l-8.5 8.5l-5-5L2 17",
        });
        STROKE.put("album", new String[] {
            "M5 3 H19 A2 2 0 0 1 21 5 V19 A2 2 0 0 1 19 21 H5 A2 2 0 0 1 3 19 V5 A2 2 0 0 1 5 3 Z",
            "M11 3v8l3-3l3 3V3",
        });
        STROKE.put("keyboard", new String[] {
            "M10 8h.01M12 12h.01M14 8h.01M16 12h.01M18 8h.01M6 8h.01M7 16h10m-9-4h.01",
            "M4 4 H20 A2 2 0 0 1 22 6 V18 A2 2 0 0 1 20 20 H4 A2 2 0 0 1 2 18 V6 A2 2 0 0 1 4 4 Z",
        });
        STROKE.put("menu", new String[] {
            "M4 5h16M4 12h16M4 19h16",
        });
        STROKE.put("grip-vertical", new String[] {
            "M8 12 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
            "M8 5 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
            "M8 19 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
            "M14 12 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
            "M14 5 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
            "M14 19 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",
        });
    }

    private static void part3() {
        STROKE.put("image", new String[] {
            "M5 3 H19 A2 2 0 0 1 21 5 V19 A2 2 0 0 1 19 21 H5 A2 2 0 0 1 3 19 V5 A2 2 0 0 1 5 3 Z",
            "M7 9 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0",
            "m21 15l-3.086-3.086a2 2 0 0 0-2.828 0L6 21",
        });
        STROKE.put("database", new String[] {
            "M3 5 a9 3 0 1 0 18 0 a9 3 0 1 0 -18 0",
            "M3 5v14a9 3 0 0 0 18 0V5",
            "M3 12a9 3 0 0 0 18 0",
        });
        STROKE.put("server", new String[] {
            "M4 2 H20 A2 2 0 0 1 22 4 V8 A2 2 0 0 1 20 10 H4 A2 2 0 0 1 2 8 V4 A2 2 0 0 1 4 2 Z",
            "M4 14 H20 A2 2 0 0 1 22 16 V20 A2 2 0 0 1 20 22 H4 A2 2 0 0 1 2 20 V16 A2 2 0 0 1 4 14 Z",
            "M6 6h.01M6 18h.01",
        });
        STROKE.put("mic", new String[] {
            "M12 19v3m7-12v2a7 7 0 0 1-14 0v-2",
            "M12 2 H12 A3 3 0 0 1 15 5 V12 A3 3 0 0 1 12 15 H12 A3 3 0 0 1 9 12 V5 A3 3 0 0 1 12 2 Z",
        });
        STROKE.put("signal", new String[] {
            "M2 20h.01M7 20v-4m5 4v-8m5 8V8m5-4v16",
        });
        STROKE.put("maximize-2", new String[] {
            "M15 3h6v6m0-6l-7 7M3 21l7-7m-1 7H3v-6",
        });
        STROKE.put("minimize-2", new String[] {
            "m14 10l7-7m-1 7h-6V4M3 21l7-7m-6 0h6v6",
        });
        STROKE.put("lock", new String[] {
            "M5 11 H19 A2 2 0 0 1 21 13 V20 A2 2 0 0 1 19 22 H5 A2 2 0 0 1 3 20 V13 A2 2 0 0 1 5 11 Z",
            "M7 11V7a5 5 0 0 1 10 0v4",
        });
        STROKE.put("heart-pulse", new String[] {
            "M2 9.5a5.5 5.5 0 0 1 9.591-3.676a.56.56 0 0 0 .818 0A5.49 5.49 0 0 1 22 9.5c0 2.29-1.5 4-3 5.5l-"
                + "5.492 5.313a2 2 0 0 1-3 .019L5 15c-1.5-1.5-3-3.2-3-5.5",
            "M3.22 13H9.5l.5-1l2 4.5l2-7l1.5 3.5h5.27",
        });
        STROKE.put("pipette", new String[] {
            "m12 9l-8.414 8.414A2 2 0 0 0 3 18.828v1.344a2 2 0 0 1-.586 1.414A2 2 0 0 1 3.828 21h1.344a2 2 0 "
                + "0 0 1.414-.586L15 12",
            "m18 9l.4.4a1 1 0 1 1-3 3l-3.8-3.8a1 1 0 1 1 3-3l.4.4l3.4-3.4a1 1 0 1 1 3 3zM2 22l.414-.414",
        });
        STROKE.put("list-ordered", new String[] {
            "M11 5h10m-10 7h10m-10 7h10M4 4h1v5M4 9h2m.5 11H3.4c0-1 2.6-1.925 2.6-3.5a1.5 1.5 0 0 0-2.6-1.02",
        });
        STROKE.put("music-2", new String[] {
            "M4 18 a4 4 0 1 0 8 0 a4 4 0 1 0 -8 0",
            "M12 18V2l7 4",
        });
        STROKE.put("circle-play", new String[] {
            "M9 9.003a1 1 0 0 1 1.517-.859l4.997 2.997a1 1 0 0 1 0 1.718l-4.997 2.997A1 1 0 0 1 9 14.996z",
            "M2 12 a10 10 0 1 0 20 0 a10 10 0 1 0 -20 0",
        });
        STROKE.put("chevrons-up-down", new String[] {
            "m7 15l5 5l5-5M7 9l5-5l5 5",
        });
        STROKE.put("arrow-down-up", new String[] {
            "m3 16l4 4l4-4m-4 4V4m14 4l-4-4l-4 4m4-4v16",
        });
        STROKE.put("shuffle", new String[] {
            "m18 14l4 4l-4 4m0-20l4 4l-4 4",
            "M2 18h1.973a4 4 0 0 0 3.3-1.7l5.454-8.6a4 4 0 0 1 3.3-1.7H22M2 6h1.972a4 4 0 0 1 3.6 2.2M22 18h-"
                + "6.041a4 4 0 0 1-3.3-1.8l-.359-.45",
        });
        STROKE.put("repeat-1", new String[] {
            "m17 2l4 4l-4 4",
            "M3 11v-1a4 4 0 0 1 4-4h14M7 22l-4-4l4-4",
            "M21 13v1a4 4 0 0 1-4 4H3m8-8h1v4",
        });
        STROKE.put("sp-play-order", new String[] {
            "M2 6h16",
            "m18 2 4 4-4 4",
            "M2 18h16",
            "m18 14 4 4-4 4",
        });
    }
}
