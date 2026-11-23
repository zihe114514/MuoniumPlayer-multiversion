package com.muoniumplayer.core.lyrics3d;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Picture;
import io.github.humbleui.skija.PictureRecorder;
import io.github.humbleui.types.Rect;

/** One bounded offscreen surface; the same Picture is used for GPU and raster presentation. */
final class SonnetFrameSurface implements AutoCloseable {
    private final PictureRecorder recorder = new PictureRecorder();
    private Bitmap raster;
    private Canvas rasterCanvas;
    private int rasterWidth, rasterHeight;
    private int[] pixels;

    Canvas begin(int width, int height) {
        if (width < 1 || height < 1 || width > 1600 || height > 1600) {
            throw new IllegalArgumentException("Sonnet surface outside its pixel budget");
        }
        Canvas canvas = recorder.beginRecording(Rect.makeWH(width, height));
        canvas.clear(0);
        return canvas;
    }

    Picture finish() { return recorder.finishRecordingAsPicture(); }

    boolean present(WorldLyricsRenderer.Host host, Picture picture, int width, int height,
                    float designWidth, float designHeight, float alpha, int slot) {
        if (host.supportsGpuTrailCanvas()) {
            try {
                if (host.drawGpuTrailCanvas(picture, width, height,
                        -designWidth / 2, -designHeight / 2, designWidth, designHeight, alpha, slot)) return true;
            } catch (Throwable failure) {
                // GPU acceleration is optional; raster replays the very same commands below.
            }
        }
        int[] rgba = rasterize(picture, width, height);
        return rgba != null && host.drawTrailCanvas(rgba, width, height,
                -designWidth / 2, -designHeight / 2, designWidth, designHeight, alpha, slot);
    }

    int[] rasterize(Picture picture, int width, int height) {
        if (raster == null || rasterWidth != width || rasterHeight != height) {
            closeRaster();
            raster = new Bitmap();
            if (!raster.allocPixels(ImageInfo.makeN32Premul(width, height))) {
                closeRaster();
                return null;
            }
            rasterCanvas = new Canvas(raster);
            rasterWidth = width;
            rasterHeight = height;
            pixels = new int[width * height];
        }
        rasterCanvas.clear(0);
        rasterCanvas.drawPicture(picture);
        byte[] bytes = raster.readPixels();
        if (bytes == null || bytes.length < width * height * 4) return null;
        for (int i = 0, at = 0; i < width * height; i++, at += 4) {
            int b = bytes[at] & 255, g = bytes[at + 1] & 255;
            int r = bytes[at + 2] & 255, a = bytes[at + 3] & 255;
            if (a > 0 && a < 255) {
                r = Math.min(255, (r * 255 + a / 2) / a);
                g = Math.min(255, (g * 255 + a / 2) / a);
                b = Math.min(255, (b * 255 + a / 2) / a);
            }
            pixels[i] = (a << 24) | (b << 16) | (g << 8) | r;
        }
        return pixels;
    }

    private void closeRaster() {
        if (rasterCanvas != null) rasterCanvas.close();
        if (raster != null) raster.close();
        rasterCanvas = null;
        raster = null;
        pixels = null;
        rasterWidth = rasterHeight = 0;
    }

    @Override public void close() {
        recorder.close();
        closeRaster();
    }
}