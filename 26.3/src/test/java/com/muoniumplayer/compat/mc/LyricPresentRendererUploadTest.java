package com.muoniumplayer.compat.mc;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.nio.FloatBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class LyricPresentRendererUploadTest {
    @Test
    void stagingIncludesOnlyInitializedVerticesAndReusesItsStorage() {
        LyricPresentRenderer.Batch batch = new LyricPresentRenderer.Batch();
        Arrays.fill(batch.floats, Float.NaN);
        batch.quadCount = 2;
        int length = batch.quadCount * LyricPresentRenderer.FLOATS_PER_QUAD;
        for (int i = 0; i < length; i++) batch.floats[i] = i + .25f;
        FloatBuffer data = LyricPresentRenderer.uploadData(batch);
        assertTrue(data.isDirect());
        assertEquals(length, data.remaining());
        for (int i = 0; i < length; i++) assertEquals(batch.floats[i], data.get(i));

        batch.quadCount = 1;
        FloatBuffer smaller = LyricPresentRenderer.uploadData(batch);
        assertSame(data, smaller);
        assertEquals(LyricPresentRenderer.FLOATS_PER_QUAD, smaller.remaining());
        batch.quadCount = 0;
        assertEquals(0, LyricPresentRenderer.uploadData(batch).remaining());
        batch.quadCount = LyricPresentRenderer.MAX_QUADS + 1;
        assertThrows(IllegalArgumentException.class, () -> LyricPresentRenderer.uploadData(batch));
        batch.quadCount = -1;
        assertThrows(IllegalArgumentException.class, () -> LyricPresentRenderer.uploadData(batch));
    }

    @Test
    void alternatingBlendSegmentsDoNotRejectLongLyricsBeforeQuadCapacity() {
        Identifier page = Identifier.fromNamespaceAndPath("muonium", "test_page");
        LyricPresentRenderer.beginFrame();
        int rejected = LyricPresentRenderer.rejectedQuads();
        for (int i = 0; i < 600; i++) {
            LyricPresentRenderer.addQuad(page,
                    0, 0, -2, 0, 0,
                    0, 1, -2, 0, 1,
                    1, 1, -2, 1, 1,
                    1, 0, -2, 1, 0,
                    0xFFFFFFFF, (i & 1) == 0);
        }
        assertEquals(600, LyricPresentRenderer.quadCount());
        assertEquals(rejected, LyricPresentRenderer.rejectedQuads());
        assertTrue(LyricPresentRenderer.endFrame());
        LyricPresentRenderer.beginFrame();
        assertEquals(0, LyricPresentRenderer.quadCount());
    }
}
