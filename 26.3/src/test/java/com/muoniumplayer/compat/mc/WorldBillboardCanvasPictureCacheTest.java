package com.muoniumplayer.compat.mc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldBillboardCanvasPictureCacheTest {
    @Test
    void onlyReusesAnIdenticalPictureForTheSameTextureAndPixelExtent() {
        WorldBillboard.CanvasPictureCache cache = new WorldBillboard.CanvasPictureCache();
        Object picture = new Object();
        Object texture = new Object();

        assertFalse(cache.matches(picture, texture, 640, 360));
        cache.store(picture, texture, 640, 360);
        assertTrue(cache.matches(picture, texture, 640, 360));
        assertFalse(cache.matches(new Object(), texture, 640, 360));
        assertFalse(cache.matches(picture, new Object(), 640, 360));
        assertFalse(cache.matches(picture, texture, 800, 360));
        assertFalse(cache.matches(picture, texture, 640, 400));

        cache.invalidate();
        assertFalse(cache.matches(picture, texture, 640, 360));
    }
}
