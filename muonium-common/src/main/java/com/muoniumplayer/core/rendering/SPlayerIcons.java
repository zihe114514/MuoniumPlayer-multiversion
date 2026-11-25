package com.muoniumplayer.core.rendering;

import com.muoniumplayer.render.skija.SkijaGfx;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintStrokeCap;
import io.github.humbleui.skija.PaintStrokeJoin;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * SPlayer 风格主界面的矢量图标入口。
 *
 * <p>几何数据在 {@link SPlayerIconData}（lucide 生成表）；这里只负责解析一次、缓存 Skija
 * {@link Path}，每帧直接在 GPU 画布上描边/填充。与 {@link FoliaLucideIcons} 同一套绘制口径：
 * 按目标边长等比缩放整张 24 网格，描边宽度也随之缩放，观感等同网页上的 {@code stroke-width:2}。</p>
 */
public final class SPlayerIcons {

    public static final String HOME = "house";
    public static final String MUSIC = "music";
    public static final String USER = "user";
    public static final String USER_ROUND = "user-round";
    public static final String DISC = "disc-3";
    public static final String FOLDER = "folder";
    public static final String CHART_PIE = "chart-pie";
    public static final String LIBRARY = "library";
    public static final String HEART = "heart";
    public static final String HEART_OFF = "heart-off";
    public static final String STAR = "star";
    public static final String HISTORY = "history";
    public static final String DOWNLOAD = "download";
    public static final String CLOUD = "cloud";
    public static final String LIST_MUSIC = "list-music";
    public static final String LIST_PLUS = "list-plus";
    public static final String PLUS = "plus";
    public static final String MINUS = "minus";
    public static final String CLOSE = "x";
    public static final String CHECK = "check";
    public static final String CHEVRON_DOWN = "chevron-down";
    public static final String CHEVRON_UP = "chevron-up";
    public static final String CHEVRON_LEFT = "chevron-left";
    public static final String CHEVRON_RIGHT = "chevron-right";
    public static final String SEARCH = "search";
    public static final String SETTINGS = "settings";
    public static final String PLAY = "play";
    public static final String PAUSE = "pause";
    public static final String SKIP_BACK = "skip-back";
    public static final String SKIP_FORWARD = "skip-forward";
    public static final String REPEAT = "repeat";
    public static final String REPEAT_ONE = "repeat-1";
    public static final String SHUFFLE = "shuffle";
    public static final String INFINITY = "infinity";
    public static final String VOLUME_X = "volume-x";
    public static final String VOLUME_1 = "volume-1";
    public static final String VOLUME_2 = "volume-2";
    public static final String CAPTIONS = "captions";
    public static final String RADIO = "radio";
    public static final String ELLIPSIS = "ellipsis";
    public static final String ELLIPSIS_VERTICAL = "ellipsis-vertical";
    public static final String HEADPHONES = "headphones";
    public static final String CALENDAR = "calendar";
    public static final String CALENDAR_DAYS = "calendar-days";
    public static final String HOURGLASS = "hourglass";
    public static final String GLOBE = "globe";
    public static final String HARD_DRIVE = "hard-drive";
    public static final String TRASH = "trash-2";
    public static final String PENCIL = "pencil";
    public static final String USERS = "users";
    public static final String USER_PLUS = "user-plus";
    public static final String SLIDERS = "sliders-horizontal";
    public static final String REFRESH = "refresh-cw";
    public static final String PALETTE = "palette";
    public static final String TYPOGRAPHY = "a-large-small";
    public static final String EXTERNAL = "square-arrow-out-up-right";
    public static final String LOADER = "loader-circle";
    public static final String MEGAPHONE = "megaphone";
    public static final String TROPHY = "trophy";
    public static final String FLAME = "flame";
    public static final String CLOCK = "clock";
    public static final String PODCAST = "podcast";
    public static final String SPARKLES = "sparkles";
    public static final String CROWN = "crown";
    public static final String LINK = "link";
    public static final String IMPORT = "import";
    public static final String DICES = "dices";
    public static final String ALBUM = "album";
    public static final String TV = "tv";
    public static final String SMARTPHONE = "smartphone";
    public static final String AUDIO_LINES = "audio-lines";
    public static final String WAND = "wand-sparkles";
    public static final String GAUGE = "gauge";
    public static final String MIC_VOCAL = "mic-vocal";
    public static final String PLAY_ORDER = "sp-play-order";
    public static final String HEART_MODE = "heart-pulse";
    public static final String PIPETTE = "pipette";
    public static final String CIRCLE_PLAY = "circle-play";

    private static final double GRID = 24.0;
    private static final double STROKE_WIDTH = 2.0;

    private static final Map<String, Path> STROKE_CACHE = new HashMap<>();
    private static final Map<String, Path> FILL_CACHE = new HashMap<>();
    private static final Set<String> FAILED = new HashSet<>();

    private SPlayerIcons() {
    }

    /** 这张表里有没有这枚图标。 */
    public static boolean has(String name) {
        return name != null && (SPlayerIconData.STROKE.containsKey(name) || SPlayerIconData.FILL.containsKey(name));
    }

    /** 按 lucide 默认描边、空心画。 */
    public static boolean draw(String name, double centerX, double centerY, double size, int argb) {
        return draw(name, centerX, centerY, size, argb, false);
    }

    /**
     * 把图标画进以 {@code (centerX, centerY)} 为中心、边长 {@code size} 的方格。
     *
     * @param solid true 时描边路径本身也整块填充（实心心形、实心播放键这类 SPlayer 的「激活」态）
     * @return 真的画了才返回 true；不在 Skija 帧内或名字未知时返回 false，由调用方决定是否降级
     */
    public static boolean draw(String name, double centerX, double centerY, double size, int argb, boolean solid) {
        if (!(size > 0) || !SkijaGfx.active() || (argb >>> 24) == 0) {
            return false;
        }
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) {
            return false;
        }
        Path stroked = pathOf(name, SPlayerIconData.STROKE, STROKE_CACHE);
        Path filled = pathOf(name, SPlayerIconData.FILL, FILL_CACHE);
        if (stroked == null && filled == null) {
            return false;
        }
        Double customGrid = SPlayerIconData.GRID.get(name);
        double grid = customGrid == null ? GRID : customGrid.doubleValue();
        SkijaGfx.pushMatrix();
        try {
            SkijaGfx.translate(centerX - size * .5, centerY - size * .5);
            SkijaGfx.scale(size / grid, size / grid);
            if (filled != null) {
                canvas.drawPath(filled, SkijaGfx.fill(argb));
            }
            if (stroked != null) {
                if (solid) {
                    canvas.drawPath(stroked, SkijaGfx.fill(argb));
                }
                Paint paint = SkijaGfx.stroke(argb, STROKE_WIDTH * grid / GRID);
                paint.setStrokeCap(PaintStrokeCap.ROUND);
                paint.setStrokeJoin(PaintStrokeJoin.ROUND);
                canvas.drawPath(stroked, paint);
            }
        } finally {
            SkijaGfx.popMatrix();
        }
        return true;
    }

    /** 全部图标名，给单测用。 */
    public static Set<String> namesForTest() {
        Set<String> names = new HashSet<>(SPlayerIconData.STROKE.keySet());
        names.addAll(SPlayerIconData.FILL.keySet());
        return names;
    }

    /** 网格边长，给单测用。 */
    public static double gridForTest(String name) {
        Double grid = SPlayerIconData.GRID.get(name);
        return grid == null ? GRID : grid.doubleValue();
    }

    /**
     * 描边（或纯填充图标的填充）路径，给单测用。<b>不要关闭返回值</b>：那是缓存里的同一份。
     */
    public static Path pathForTest(String name) {
        Path stroked = pathOf(name, SPlayerIconData.STROKE, STROKE_CACHE);
        return stroked != null ? stroked : pathOf(name, SPlayerIconData.FILL, FILL_CACHE);
    }

    private static Path pathOf(String name, Map<String, String[]> table, Map<String, Path> cache) {
        if (name == null) {
            return null;
        }
        synchronized (cache) {
            Path cached = cache.get(name);
            if (cached != null || FAILED.contains(table == SPlayerIconData.STROKE ? name : "fill:" + name)) {
                return cached;
            }
            String[] parts = table.get(name);
            if (parts == null) {
                return null;
            }
            cached = build(parts);
            if (cached == null) {
                FAILED.add(table == SPlayerIconData.STROKE ? name : "fill:" + name);
            } else {
                cache.put(name, cached);
            }
            return cached;
        }
    }

    private static Path build(String[] parts) {
        PathBuilder builder = new PathBuilder();
        try {
            for (String d : parts) {
                Path raw = Path.makeFromSVGString(d);
                if (raw == null) {
                    return null;
                }
                try {
                    builder.addPath(raw);
                } finally {
                    raw.close();
                }
            }
            return builder.snapshot();
        } finally {
            builder.close();
        }
    }
}
