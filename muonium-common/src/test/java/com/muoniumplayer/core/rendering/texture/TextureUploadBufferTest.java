package com.muoniumplayer.core.rendering.texture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TextureUploadBufferTest {
    @Test void packedPixelsAreDirectNativeOrderAndUnmodified() {
        int[] source = {0x00123456, 0x7F102030, 0xFFABCDEF, 0x80010203};
        IntBuffer packed = new TextureUploadBuffer().pack(source, 1, 2);
        assertTrue(packed.isDirect());
        assertEquals(ByteOrder.nativeOrder(), packed.order());
        assertEquals(0, packed.position());
        assertEquals(2, packed.remaining());
        assertEquals(source[1], packed.get());
        assertEquals(source[2], packed.get());
    }

    @Test void bufferIsReusedAcrossFramesAndSmallerChunks() {
        TextureUploadBuffer buffer = new TextureUploadBuffer();
        IntBuffer first = buffer.pack(new int[]{1, 2, 3, 4}, 0, 4);
        first.position(3);
        IntBuffer second = buffer.pack(new int[]{7, 8, 9}, 1, 2);
        assertSame(first, second);
        assertEquals(0, second.position());
        assertEquals(2, second.limit());
        assertEquals(8, second.get());
        assertEquals(9, second.get());
    }

    @Test void capacityOnlyGrowsWhenNecessary() {
        TextureUploadBuffer buffer = new TextureUploadBuffer();
        IntBuffer small = buffer.pack(new int[]{1}, 0, 1);
        IntBuffer large = buffer.pack(new int[2000], 0, 2000);
        assertNotSame(small, large);
        assertSame(large, buffer.pack(new int[]{4, 5}, 0, 2));
        assertEquals(2000, large.capacity());
    }

    @Test void chunkPackingDoesNotMutateSource() {
        int[] source = {1, 2, 3, 4, 5, 6, 7, 8};
        int[] before = source.clone();
        TextureUploadBuffer buffer = new TextureUploadBuffer();
        for (int offset = 0; offset < source.length; offset += 2) {
            IntBuffer packed = buffer.pack(source, offset, 2);
            assertEquals(source[offset], packed.get());
            assertEquals(source[offset + 1], packed.get());
        }
        assertArrayEquals(before, source);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MUONIUM_GL_UPLOAD_TEST", matches = "1")
    void actualGlUploadPreservesRgbaIncludingAlphaAcrossChunks() throws Exception {
        // 26.3 uses SDL and does not ship GLFW. Keep the shared suite compilable
        // without adding a legacy window-system dependency to that version.
        Class<?> glfw;
        try {
            glfw = Class.forName("org.lwjgl.glfw.GLFW");
        } catch (ClassNotFoundException unavailable) {
            assumeTrue(false, "GLFW unavailable; real GPU validation needs an SDL/game context on this version");
            return;
        }
        assertTrue((boolean) glfw.getMethod("glfwInit").invoke(null));
        long window = 0;
        int texture = 0;
        try {
            glfw.getMethod("glfwDefaultWindowHints").invoke(null);
            glfw.getMethod("glfwWindowHint", int.class, int.class).invoke(null,
                    glfw.getField("GLFW_VISIBLE").getInt(null), glfw.getField("GLFW_FALSE").getInt(null));
            window = (long) glfw.getMethod("glfwCreateWindow", int.class, int.class, CharSequence.class,
                    long.class, long.class).invoke(null, 16, 16, "upload-regression", 0L, 0L);
            assertNotEquals(0, window);
            glfw.getMethod("glfwMakeContextCurrent", long.class).invoke(null, window);
            GL.createCapabilities();
            texture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 3, 2, 0,
                    GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, (IntBuffer) null);
            int[] source = {0x00123456, 0x7F102030, 0xFFABCDEF, 0x80010203, 0xFFFFFFFF, 0x1000FF00};
            TextureUploadBuffer buffer = new TextureUploadBuffer();
            for (int row = 0; row < 2; row++) {
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, row, 3, 1,
                        GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buffer.pack(source, row * 3, 3));
            }
            ByteBuffer rgba = ByteBuffer.allocateDirect(source.length * 4);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba);
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
            for (int i = 0; i < source.length; i++) {
                assertEquals((source[i] >>> 16) & 255, rgba.get(i * 4) & 255);
                assertEquals((source[i] >>> 8) & 255, rgba.get(i * 4 + 1) & 255);
                assertEquals(source[i] & 255, rgba.get(i * 4 + 2) & 255);
                assertEquals(source[i] >>> 24, rgba.get(i * 4 + 3) & 255);
            }
        } finally {
            if (texture != 0) GL11.glDeleteTextures(texture);
            if (window != 0) {
                glfw.getMethod("glfwMakeContextCurrent", long.class).invoke(null, 0L);
                glfw.getMethod("glfwDestroyWindow", long.class).invoke(null, window);
            }
            GL.setCapabilities(null);
            glfw.getMethod("glfwTerminate").invoke(null);
        }
    }
}
