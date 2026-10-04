package com.muoniumplayer.core.rendering.texture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/** One reusable staging buffer. Access is confined to the Minecraft render thread. */
final class TextureUploadBuffer {
    private IntBuffer pixels;

    IntBuffer pack(int[] source, int offset, int count) {
        if (pixels == null || pixels.capacity() < count) {
            pixels = ByteBuffer.allocateDirect(Math.multiplyExact(count, Integer.BYTES))
                    .order(ByteOrder.nativeOrder()).asIntBuffer();
        }
        pixels.clear();
        pixels.put(source, offset, count);
        pixels.flip();
        return pixels;
    }
}
