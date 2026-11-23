package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.testsupport.RequiresSkijaNative;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Picture;
import io.github.humbleui.skija.PictureRecorder;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

@RequiresSkijaNative
class SonnetTextTransitionsTest {
    @Test void blurAndMonochromeTearFilterRealRecordedArtworkWithoutTouchingAnExternalBackground() {
        SonnetTextMotion.Transition frame = new SonnetTextMotion.Transition();
        try (SonnetTextTransitions filters = new SonnetTextTransitions();
             SonnetFrameSurface raster = new SonnetFrameSurface()) {
            int[] original = render(filters, frame, raster);
            assertNull(filters.filter(frame, 160, 90));
            SonnetTextMotion.transition(0, true, 0.55, 17, frame);
            int[] blurred = render(filters, frame, raster);
            assertFalse(Arrays.equals(original, blurred));
            SonnetTextMotion.transition(1, true, 0.6, 123, frame);
            int[] torn = render(filters, frame, raster);
            assertFalse(Arrays.equals(original, torn));
            assertArrayEquals(torn, render(filters, frame, raster));
            for (int pixel : torn) {
                // A white-only fixture must remain monochrome; no channel dispersion is invented.
                if ((pixel >>> 24) < 10) continue;
                assertEquals(pixel & 255, pixel >> 8 & 255, 1);
                assertEquals(pixel & 255, pixel >> 16 & 255, 1);
            }
        }
    }

    private int[] render(SonnetTextTransitions filters, SonnetTextMotion.Transition frame,
                         SonnetFrameSurface raster) {
        try (PictureRecorder recorder = new PictureRecorder();
             Paint layer = new Paint(); Paint white = new Paint().setColor(0xFFFFFFFF);
             ImageFilter filter = filters.filter(frame, 160, 90)) {
            Canvas canvas = recorder.beginRecording(Rect.makeWH(160, 90));
            canvas.clear(0);
            layer.setImageFilter(filter);
            canvas.saveLayer(Rect.makeWH(160, 90), layer);
            for (int i = 0; i < 7; i++) canvas.drawRect(Rect.makeXYWH(20 + i * 18, 12, 5, 65), white);
            canvas.restore();
            layer.setImageFilter(null);
            try (Picture picture = recorder.finishRecordingAsPicture()) {
                return raster.rasterize(picture, 160, 90).clone();
            }
        }
    }
}