package com.muoniumplayer.core.rendering;

/** 展开音乐卡的设计空间；绘制、鼠标命中和进度拖动共用。 */
public final class MusicCardLayout {
    public static final double WIDTH = 336.0;
    public static final double HEIGHT = 148.0;
    public static final double RADIUS = 28.0;
    public static final double COVER_X = 16.0;
    public static final double COVER_Y = 16.0;
    public static final double COVER_SIZE = 48.0;
    public static final double TEXT_X = 78.0;
    public static final double BADGE_X = WIDTH - 42.0;
    public static final double BADGE_SIZE = 25.0;
    public static final double PROGRESS_X = 47.0;
    public static final double PROGRESS_Y = 78.0;
    public static final double PROGRESS_WIDTH = WIDTH - PROGRESS_X * 2.0;
    public static final double CONTROLS_Y = 113.0;
    public static final double PLAY_DISC_RADIUS = 22.0;
    public static final double PLAY_GLYPH_SIZE = 20.0;
    public static final double SKIP_GLYPH_SIZE = 16.5;
    public static final double MODE_GLYPH_SIZE = 18.0;
    public static final double HEART_GLYPH_SIZE = 21.5;
    /** favorite.png 的有效墨迹宽52，画布宽96；尺寸按墨迹而非整张贴图校准。 */
    public static final double HEART_TEXTURE_SIZE = HEART_GLYPH_SIZE * 96.0 / 52.0;

    public enum Target {
        NONE, PLAYER, SEEK, LIKE, PREVIOUS, PLAY_PAUSE, NEXT, MODE
    }

    private MusicCardLayout() {
    }

    /** 两端各保留一个槽位，红心显隐不改变传输三键和播放模式的位置。 */
    public static double controlX(Target target) {
        switch (target) {
            case LIKE: return 38.0;
            case PREVIOUS: return 104.0;
            case PLAY_PAUSE: return WIDTH * .5;
            case NEXT: return WIDTH - 104.0;
            case MODE: return WIDTH - 38.0;
            default: return Double.NaN;
        }
    }

    public static double controlHitRadius(Target target) {
        return target == Target.PLAY_PAUSE ? PLAY_DISC_RADIUS + 3.0 : 21.0;
    }

    /** 圆角外的透明区域不截获聊天链接。 */
    public static boolean contains(double x, double y) {
        return containsRounded(x, y, 0.0, 0.0, WIDTH, HEIGHT, RADIUS);
    }

    static boolean containsRounded(double x, double y, double left, double top,
                                   double width, double height, double radius) {
        return MusicCardShape.contains(x, y, left, top, width, height, radius);
    }

    public static Target hit(double x, double y, boolean showLike, boolean seekable) {
        if (!contains(x, y)) return Target.NONE;
        if (seekable && Math.abs(y - PROGRESS_Y) <= 10.0
                && x >= PROGRESS_X && x <= PROGRESS_X + PROGRESS_WIDTH) return Target.SEEK;
        Target[] controls = {Target.LIKE, Target.PREVIOUS, Target.PLAY_PAUSE, Target.NEXT, Target.MODE};
        for (Target target : controls) {
            if (target == Target.LIKE && !showLike) continue;
            double dx = x - controlX(target);
            double dy = y - CONTROLS_Y;
            double radius = controlHitRadius(target);
            if (dx * dx + dy * dy <= radius * radius) return target;
        }
        return Target.PLAYER;
    }

    public static double seekFraction(double designX) {
        if (!Double.isFinite(designX)) return 0.0;
        return Math.max(0.0, Math.min(1.0, (designX - PROGRESS_X) / PROGRESS_WIDTH));
    }

    /** 布局坐标转设计坐标；宽高分别换算，避免展开中暂时不同的宽高比造成命中漂移。 */
    public static double[] designPoint(double x, double y, double[] bounds) {
        if (bounds == null || bounds.length < 4 || bounds[2] <= 0.0 || bounds[3] <= 0.0
                || !Double.isFinite(bounds[2]) || !Double.isFinite(bounds[3])) return null;
        return new double[]{(x - bounds[0]) * WIDTH / bounds[2], (y - bounds[1]) * HEIGHT / bounds[3]};
    }
}
