package com.muoniumplayer.core.lyrics3d;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LyricGlyphAtlasLifecycleTest {

    private static final class FakeHost implements LyricGlyphAtlas.Host {
        final List<Object> deleted = new ArrayList<>();

        @Override
        public Object createPage(int width, int height) {
            return new Object();
        }

        @Override
        public void uploadCell(Object page, int x, int y, int w, int h, int[] pixelsArgb) {
        }

        @Override
        public void deletePage(Object page) {
            deleted.add(page);
        }
    }

    @Test
    @DisplayName("延迟提交下图集页失效不会在当前帧释放纹理")
    void retiredPagesWaitForNextFrameBoundary() throws Exception {
        FakeHost host = new FakeHost();
        LyricGlyphAtlas.setHost(host);
        try {
            LyricGlyphAtlas.invalidateAll();
            LyricGlyphAtlas.beginFrame();

            Field pagesField = LyricGlyphAtlas.class.getDeclaredField("PAGES");
            pagesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<LyricGlyphAtlas.Page> pages =
                    (List<LyricGlyphAtlas.Page>) pagesField.get(null);
            Object handle = new Object();
            pages.add(new LyricGlyphAtlas.Page(handle, 256, 256));

            LyricGlyphAtlas.invalidateAll();
            assertEquals(0, host.deleted.size(), "当前帧仍可能有延迟几何引用旧页");

            LyricGlyphAtlas.beginFrame();
            assertEquals(1, host.deleted.size(), "下一帧边界才释放退休页");
            assertEquals(handle, host.deleted.get(0));
        } finally {
            LyricGlyphAtlas.invalidateAll();
            LyricGlyphAtlas.beginFrame();
            LyricGlyphAtlas.setHost(null);
        }
    }
}
