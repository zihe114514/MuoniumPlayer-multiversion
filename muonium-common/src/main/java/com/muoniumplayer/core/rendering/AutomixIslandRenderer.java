package com.muoniumplayer.core.rendering;

import com.muoniumplayer.core.ncm.music.AutomixTransitionCurve;
import com.muoniumplayer.core.ncm.music.AutomixTransitionCurve.Band;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.PaintStrokeCap;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.RRect;

/**
 * 灵动岛内的两条滚动交接包络：白色退场、主题色接入。
 *
 * <p>横轴是围绕当前混音时刻移动的时间窗，纵轴是同一计划的增益；较淡的伴线
 * 叠加真实低频 shelf 衰减。不是 FFT，也没有用正弦波冒充音频。整个绘制只读帧，
 * 跟随岛自己的矩阵、圆角裁剪与退场动画，不持有播放器。</p>
 */
final class AutomixIslandRenderer {
    static final double TARGET_WIDTH = 152.0;
    static final double TARGET_HEIGHT = 30.0;
    static final double MAX_WIDTH = 176.0;
    static final double MAX_HEIGHT = 36.0;
    static final double PLAYHEAD = .52;
    private static final double WINDOW = .78;
    private static final int POINTS = 72;

    static final class Frame {
        final AutomixTransitionCurve curve;
        final double progress;
        final double seconds;
        final double opacity;

        Frame(AutomixTransitionCurve curve, double progress, double seconds, double opacity) {
            this.curve = curve;
            this.progress = clamp01(progress);
            this.seconds = Double.isFinite(seconds) ? Math.max(0.0, seconds) : 0.0;
            this.opacity = clamp01(opacity);
        }
    }

    private AutomixIslandRenderer() { }

    /** 小屏与缩放时也不越界；大屏不会把两根线拉成一张大卡片。 */
    static double targetWidth(double screenWidth, double configuredScale) {
        double scale = Math.max(.60, configuredScale);
        double available = (screenWidth - 16.0) / scale;
        return Math.max(1.0, Math.min(TARGET_WIDTH, available));
    }

    static double sampleProgress(double progress, double x) {
        return clamp01(progress + (x - PLAYHEAD) * WINDOW);
    }

    /** Gain 与低频处理来自真实快照；UI 不产生另一套音频控制曲线。 */
    static double level(Frame frame, boolean incoming, boolean bass, double x) {
        double progress = sampleProgress(frame.progress, x);
        double gain = frame.curve.value(incoming ? Band.INCOMING_GAIN : Band.OUTGOING_GAIN, progress);
        if (bass) {
            double db = frame.curve.value(incoming ? Band.INCOMING_BASS : Band.OUTGOING_BASS, progress);
            gain *= Math.pow(10.0, db / 20.0);
        }
        return Double.isFinite(gain) ? Math.max(0.0, gain) : 0.0;
    }

    static double gainCeiling(AutomixTransitionCurve curve) {
        double peak = 1.0;
        for (int index = 0; index < curve.samples(); index++) {
            peak = Math.max(peak, curve.value(Band.OUTGOING_GAIN, index));
            peak = Math.max(peak, curve.value(Band.INCOMING_GAIN, index));
        }
        return peak;
    }

    /** 由 DownloadDynamicIsland 在已经变换好的同一画布内调用。 */
    static void draw(Canvas canvas, Frame frame, double x, double y,
                     double width, double height, double radius, double alpha, int accent) {
        if (canvas == null || frame == null || frame.curve == null || width < 4.0 || height < 4.0) return;
        double opacity = clamp01(alpha) * frame.opacity;
        if (opacity <= .001) return;
        canvas.save();
        try {
            RRect bounds = RRect.makeXYWH((float) x, (float) y, (float) width, (float) height,
                    (float) Math.min(radius, height * .5));
            canvas.clipRRect(bounds, true);
            // 同一层半透明胶囊底；开启磨砂时岛会先在此轮廓内铺真实背景。
            try (Paint surface = new Paint()) {
                surface.setAntiAlias(true);
                surface.setColor(alpha(0xFF05070A, clamp01(alpha) * .40));
                canvas.drawRRect(bounds, surface);
                surface.setMode(PaintMode.STROKE);
                surface.setStrokeWidth(.55f);
                surface.setColor(alpha(0xFFFFFFFF, clamp01(alpha) * .07));
                canvas.drawRRect(bounds, surface);
            }
            double inset = Math.min(14.0, width * .12);
            double left = x + inset, right = x + width - inset;
            double top = y + height * .20, bottom = y + height * .80;
            double peak = gainCeiling(frame.curve);
            int outgoing = 0xFFE3EBF4;
            int incoming = 0xFF000000 | (accent & 0x00FFFFFF);
            // 轻量伴线只表示低频衰减，不再画八条多彩曲线/坐标网格/大图例。
            drawLine(canvas, frame, false, true, left, right, top, bottom, peak,
                    outgoing, opacity * .14, .70f);
            drawLine(canvas, frame, true, true, left, right, top, bottom, peak,
                    incoming, opacity * .19, .70f);
            drawLine(canvas, frame, false, false, left, right, top, bottom, peak,
                    outgoing, opacity * .50, 1.10f);
            drawLine(canvas, frame, true, false, left, right, top, bottom, peak,
                    incoming, opacity * .70, 1.25f);
            // 当前时刻的两个点随真实增益移动；窄光束沿线流动，曲线本身不被假波浪扭曲。
            drawHead(canvas, frame, false, PLAYHEAD, left, right, top, bottom, peak,
                    outgoing, opacity * .46, 1.25f);
            drawHead(canvas, frame, true, PLAYHEAD, left, right, top, bottom, peak,
                    incoming, opacity * .68, 1.40f);
            double travel = (frame.seconds * .18) % 1.0;
            drawHead(canvas, frame, false, travel, left, right, top, bottom, peak,
                    outgoing, opacity * .28, .90f);
            drawHead(canvas, frame, true, 1.0 - travel, left, right, top, bottom, peak,
                    incoming, opacity * .38, 1.0f);
        } finally {
            canvas.restore();
        }
    }

    private static void drawLine(Canvas canvas, Frame frame, boolean incoming, boolean bass,
                                 double left, double right, double top, double bottom, double peak,
                                 int color, double opacity, float width) {
        try (PathBuilder builder = new PathBuilder(); Paint paint = new Paint();
             Shader shader = Shader.makeLinearGradient((float) left, (float) top,
                     (float) right, (float) top, new int[]{alpha(color, 0.0),
                     alpha(color, opacity * .70), alpha(color, opacity),
                     alpha(color, opacity * .70), alpha(color, 0.0)})) {
            for (int index = 0; index < POINTS; index++) {
                double u = index / (double) (POINTS - 1);
                float px = (float) (left + (right - left) * u);
                float py = (float) (bottom - (bottom - top) * level(frame, incoming, bass, u) / peak);
                if (index == 0) builder.moveTo(px, py); else builder.lineTo(px, py);
            }
            paint.setAntiAlias(true);
            paint.setMode(PaintMode.STROKE);
            paint.setStrokeCap(PaintStrokeCap.ROUND);
            paint.setStrokeWidth(width);
            paint.setShader(shader);
            try (Path path = builder.snapshot()) {
                canvas.drawPath(path, paint);
            }
        }
    }

    private static void drawHead(Canvas canvas, Frame frame, boolean incoming, double u,
                                 double left, double right, double top, double bottom, double peak,
                                 int color, double opacity, float radius) {
        // 边缘淡隐，光点永远不会从胶囊的圆肩外跳出来。
        double edge = Math.min(1.0, Math.min(u, 1.0 - u) * 8.0);
        float x = (float) (left + (right - left) * u);
        float y = (float) (bottom - (bottom - top) * level(frame, incoming, false, u) / peak);
        try (Paint paint = new Paint()) {
            paint.setAntiAlias(true);
            paint.setColor(alpha(color, opacity * edge * .13));
            canvas.drawCircle(x, y, radius * 2.8f, paint);
            paint.setColor(alpha(color, opacity * edge));
            canvas.drawCircle(x, y, radius, paint);
        }
    }

    private static int alpha(int color, double opacity) {
        return ((int) Math.round(255.0 * clamp01(opacity)) << 24) | (color & 0x00FFFFFF);
    }

    private static double clamp01(double value) {
        return Double.isFinite(value) ? Math.max(0.0, Math.min(1.0, value)) : 0.0;
    }
}
