package com.muoniumplayer.core.screens.ncm.foliabg;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Image;

import java.awt.image.BufferedImage;

/**
 * 把 Skia {@link Image} 读回成 {@link BufferedImage}，供取色与莫奈的逐像素烘焙使用。
 *
 * <p>封面在播放器里是 GL 纹理（{@link com.muoniumplayer.render.skija.SkijaImages} 借给 Skia），
 * 取色与「水洗」都需要 CPU 侧像素，所以只在这一步读回一次；结果按调用方缓存，不在每帧重复。</p>
 */
final class FoliaCoverSampler {

    private FoliaCoverSampler() {
    }

    /** 读回整张图；失败返回 {@code null}。 */
    static BufferedImage sample(Image image) {
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) return null;
        int width = image.getWidth();
        int height = image.getHeight();
        try (Bitmap bitmap = new Bitmap()) {
            if (!bitmap.allocN32Pixels(width, height, false)) return null;
            if (!image.readPixels(bitmap, 0, 0)) return null;
            BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    out.setRGB(x, y, bitmap.getColor(x, y));
                }
            }
            return out;
        } catch (Throwable failure) {
            return null;
        }
    }
}
