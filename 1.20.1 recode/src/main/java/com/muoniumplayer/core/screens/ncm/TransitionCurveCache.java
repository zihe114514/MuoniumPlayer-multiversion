package com.muoniumplayer.core.screens.ncm;

/**
 * 播放器两条转场共用的曲线缓存。
 *
 * <p>cubic-bezier 需要按 x 反解参数 u。原实现每读一次曲线都可能走牛顿法 + 二分，
 * 而一条转场一帧会读十几条通道；低配机器上这些小计算会和大量绘制调用叠在一起，
 * GC 与分支预测的代价都被放大。</p>
 *
 * <p>这里在类加载时把两条固定曲线各采样成 512 段的查找表，运行期只做一次数组插值。
 * 512 段的最大线性插值误差远小于 0.5/255，肉眼不可见；两张表合计不到 9 KB，常驻内存可以忽略。</p>
 */
final class TransitionCurveCache {

    private static final int SAMPLES = 513;

    /** Material 3 emphasized。承担绝大多数位移与不透明度通道。 */
    static final CachedBezier MOTION = new CachedBezier(0.2, 0.0, 0.0, 1.0);

    /** Material 2 standard。只承担水平轴的滞后通道。 */
    static final CachedBezier MOTION_LAGGING = new CachedBezier(0.4, 0.0, 0.2, 1.0);

    private TransitionCurveCache() {
    }

    static final class CachedBezier {
        private final float[] values = new float[SAMPLES];

        CachedBezier(double x1, double y1, double x2, double y2) {
            for (int i = 0; i < SAMPLES; i++) {
                double x = (double) i / (double) (SAMPLES - 1);
                values[i] = (float) axis(y1, y2, solve(x1, x2, x));
            }
        }

        double at(double time) {
            if (time <= 0.0) return 0.0;
            if (time >= 1.0) return 1.0;

            double position = time * (SAMPLES - 1);
            int index = (int) position;
            if (index >= SAMPLES - 1) return values[SAMPLES - 1];

            double fraction = position - index;
            return values[index] + (values[index + 1] - values[index]) * fraction;
        }

        private static double axis(double a, double b, double u) {
            double iu = 1.0 - u;
            return 3.0 * iu * iu * u * a + 3.0 * iu * u * u * b + u * u * u;
        }

        private static double slope(double a, double b, double u) {
            double iu = 1.0 - u;
            return 3.0 * a * iu * (1.0 - 3.0 * u)
                    + 3.0 * b * u * (2.0 - 3.0 * u)
                    + 3.0 * u * u;
        }

        private static double solve(double x1, double x2, double x) {
            double u = x;
            for (int i = 0; i < 8; i++) {
                double error = axis(x1, x2, u) - x;
                if (Math.abs(error) < 1.0e-6) return u;
                double derivative = slope(x1, x2, u);
                if (Math.abs(derivative) < 1.0e-6) break;
                u -= error / derivative;
                if (u < 0.0 || u > 1.0) break;
            }

            double low = 0.0, high = 1.0;
            u = x;
            for (int i = 0; i < 32; i++) {
                if (axis(x1, x2, u) < x) low = u;
                else high = u;
                u = (low + high) * .5;
            }
            return u;
        }
    }
}
