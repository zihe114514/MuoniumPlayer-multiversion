package com.muoniumplayer.compat.mc;

import com.mojang.blaze3d.platform.NativeImage;
import com.muoniumplayer.testsupport.RequiresSkijaNative;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@RequiresSkijaNative
class WorldBillboardCanvasPixelsTest {
    @Test
    void bulkRowsMatchPerPixelWritesAndDoNotCopySpareArrayCapacity() {
        try (NativeImage actual = new NativeImage(8, 4, true);
             NativeImage expected = new NativeImage(8, 4, true)) {
            int sentinel = 0xA0402010;
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 8; x++) {
                    actual.setPixelABGR(x, y, sentinel);
                    expected.setPixelABGR(x, y, sentinel);
                }
            }
            int[] pixels = {0x80402010, 0xFFFFFFFF, 0, 0xFF112233, 0x00010203, 0x400080FF,
                    0xDEADBEEF, 0xDEADBEEF};
            WorldBillboard.writeCanvasPixels(actual, pixels, 3, 2);
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 3; x++) expected.setPixelABGR(x, y, pixels[y * 3 + x]);
            }
            assertArrayEquals(expected.getPixelsABGR(), actual.getPixelsABGR());
            ByteBuffer bytes = actual.getPixelBytes();
            assertEquals(0x10, bytes.get(0) & 255);
            assertEquals(0x20, bytes.get(1) & 255);
            assertEquals(0x40, bytes.get(2) & 255);
            assertEquals(0x80, bytes.get(3) & 255);

            WorldBillboard.writeCanvasPixels(actual, new int[32], 2, 1);
            assertEquals(0, actual.getPixel(0, 0));
            assertEquals(0, actual.getPixel(1, 0));
            assertEquals(0x80402010, expected.getPixelsABGR()[0]);
            assertEquals(sentinel, actual.getPixelsABGR()[31]);
        }
    }

    @Test
    void invalidRectanglesAreRejectedBeforeNativeMemoryIsWritten() {
        try (NativeImage image = new NativeImage(8, 4, true)) {
            int[] before = image.getPixelsABGR();
            int[] oversized = new int[100];
            Arrays.fill(oversized, 0xFFFFFFFF);
            assertThrows(IllegalArgumentException.class, () -> WorldBillboard.writeCanvasPixels(image, oversized, 9, 1));
            assertThrows(IllegalArgumentException.class, () -> WorldBillboard.writeCanvasPixels(image, oversized, 1, 5));
            assertThrows(IllegalArgumentException.class, () -> WorldBillboard.writeCanvasPixels(image, new int[5], 3, 2));
            assertThrows(IllegalArgumentException.class, () -> WorldBillboard.writeCanvasPixels(image, oversized, -1, 2));
            assertThrows(IllegalArgumentException.class, () -> WorldBillboard.writeCanvasPixels(image, oversized, Integer.MAX_VALUE, 2));
            assertArrayEquals(before, image.getPixelsABGR());
        }
    }
}
