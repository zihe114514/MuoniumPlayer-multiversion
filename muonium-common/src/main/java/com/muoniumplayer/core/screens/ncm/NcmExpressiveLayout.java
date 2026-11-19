package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.input.TouchGestures;
import com.muoniumplayer.render.skija.SkijaGfx;
import io.github.humbleui.skija.Canvas;

/** 本轮提示独立使用设计像素；不改变播放器、原版 GUI 或主题的坐标轴。 */
public final class NcmExpressiveLayout {
    private double scale = 1;
    private double originX;
    private double originY;
    private double logicalWidth;
    private double logicalHeight;
    private boolean rendered;
    private boolean inLogicalSpace;

    @FunctionalInterface
    public interface Dispatch {
        void run(double mouseX, double mouseY, int input);
    }

    public static double scaleFor(double width, double height, double pixelsPerUnit) {
        if (!(pixelsPerUnit > 0) || !Double.isFinite(pixelsPerUnit)) pixelsPerUnit = 1;
        double available = Math.min(Math.max(1, width) * pixelsPerUnit / 960,
                Math.max(1, height) * pixelsPerUnit / 720);
        if (!Double.isFinite(available)) available = 1;
        double density = Math.max(.78, Math.min(1.10, available));
        return density / pixelsPerUnit;
    }

    public void render(NCMPanel host, double mouseX, double mouseY, int wheel, Dispatch draw) {
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) {
            draw.run(mouseX, mouseY, wheel);
            return;
        }
        float[] matrix = canvas.getLocalToDeviceAsMatrix33().getMat();
        double pixelsPerUnit = Math.max(Math.hypot(matrix[0], matrix[3]), Math.hypot(matrix[1], matrix[4]));
        scale = scaleFor(host.getWidth(), host.getHeight(), pixelsPerUnit);
        originX = host.getX();
        originY = host.getY();
        logicalWidth = host.getWidth() / scale;
        logicalHeight = host.getHeight() / scale;
        rendered = true;
        double width = host.getWidth(), height = host.getHeight();
        SkijaGfx.pushMatrix();
        try {
            SkijaGfx.translate(originX, originY);
            SkijaGfx.scale(scale, scale);
            SkijaGfx.translate(-originX, -originY);
            host.setBounds(logicalWidth, logicalHeight);
            inLogicalSpace = true;
            TouchGestures.get().withCoordinateSpace(originX, originY, scale,
                    () -> draw.run(toLogicalX(mouseX), toLogicalY(mouseY), wheel));
        } finally {
            inLogicalSpace = false;
            host.setBounds(width, height);
            SkijaGfx.popMatrix();
        }
    }

    /** 事件复用上一帧实际绘制的坐标，不在无渲染矩阵的输入阶段重新猜 GUI 倍率。 */
    public void dispatch(NCMPanel host, double mouseX, double mouseY, int input, Dispatch action) {
        if (inLogicalSpace) {
            action.run(mouseX, mouseY, input);
            return;
        }
        if (!rendered) return;
        double x = host.getRelativeX(), y = host.getRelativeY();
        double width = host.getWidth(), height = host.getHeight();
        try {
            host.setBounds(originX - host.getParentX(), originY - host.getParentY(), logicalWidth, logicalHeight);
            inLogicalSpace = true;
            action.run(toLogicalX(mouseX), toLogicalY(mouseY), input);
        } finally {
            inLogicalSpace = false;
            host.setBounds(x, y, width, height);
        }
    }

    public double toLogicalX(double value) { return originX + (value - originX) / scale; }
    public double toLogicalY(double value) { return originY + (value - originY) / scale; }
    public double scale() { return scale; }
}
