package com.muoniumplayer.core.rendering;

import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;

/** 直边与圆角以零曲率相接；每个角的两段三次曲线在中点保持二阶连续。 */
public final class MusicCardShape {
    private static final double SHOULDER = 1.45;

    private MusicCardShape() {
    }

    static double extent(double width, double height, double radius) {
        return Math.max(0.0, Math.min(Math.max(0.0, radius) * SHOULDER, Math.min(width, height) * .5));
    }

    /** 返回值归调用方所有；绘制、裁剪和描边共用此路径，用完立即关闭，不持有原生缓存。 */
    public static Path outline(double x, double y, double width, double height, double radius) {
        double e = extent(width, height, radius);
        try (PathBuilder path = new PathBuilder()) {
            path.moveTo((float) (x + e), (float) y);
            path.lineTo((float) (x + width - e), (float) y);
            corner(path, x + width - e, y, e, 1, 0, 0, 1);
            path.lineTo((float) (x + width), (float) (y + height - e));
            corner(path, x + width, y + height - e, e, 0, 1, -1, 0);
            path.lineTo((float) (x + e), (float) (y + height));
            corner(path, x + e, y + height, e, -1, 0, 0, -1);
            path.lineTo((float) x, (float) (y + e));
            corner(path, x, y + e, e, 0, -1, 1, 0);
            path.closePath();
            return path.snapshot();
        }
    }

    private static void corner(PathBuilder path, double x, double y, double e,
                               double ux, double uy, double vx, double vy) {
        path.cubicTo((float) (x + ux * e * .2), (float) (y + uy * e * .2),
                (float) (x + ux * e * .6), (float) (y + uy * e * .6),
                (float) (x + (ux * .8 + vx * .2) * e), (float) (y + (uy * .8 + vy * .2) * e));
        path.cubicTo((float) (x + (ux + vx * .4) * e), (float) (y + (uy + vy * .4) * e),
                (float) (x + (ux + vx * .8) * e), (float) (y + (uy + vy * .8) * e),
                (float) (x + (ux + vx) * e), (float) (y + (uy + vy) * e));
    }

    public static boolean contains(double x, double y, double left, double top,
                                   double width, double height, double radius) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || width <= 0.0 || height <= 0.0
                || x < left || x > left + width || y < top || y > top + height) return false;
        double e = extent(width, height, radius);
        double dx = Math.min(x - left, left + width - x);
        double dy = Math.min(y - top, top + height - y);
        if (e <= 0.0 || dx >= e || dy >= e) return true;
        double u = 1.0 - dx / e;
        return dy / e + 1e-7 >= cornerY(u);
    }

    /** 以单调 x 反解同一条三次曲线，透明角上的输入不另用圆弧近似。 */
    static double cornerY(double x) {
        double u = Math.max(0.0, Math.min(1.0, x));
        if (u > .8) {
            double low = 0.0, high = 1.0;
            for (int i = 0; i < 24; i++) {
                double t = (low + high) * .5;
                if (cubic(.8, 1.0, 1.0, 1.0, t) < u) low = t;
                else high = t;
            }
            return cubic(.2, .4, .8, 1.0, (low + high) * .5);
        }
        double low = 0.0, high = 1.0;
        for (int i = 0; i < 24; i++) {
            double t = (low + high) * .5;
            if (cubic(0.0, .2, .6, .8, t) < u) low = t;
            else high = t;
        }
        return cubic(0.0, 0.0, 0.0, .2, (low + high) * .5);
    }

    private static double cubic(double a, double b, double c, double d, double t) {
        double s = 1.0 - t;
        return s * s * s * a + 3.0 * s * s * t * b + 3.0 * s * t * t * c + t * t * t * d;
    }
}
