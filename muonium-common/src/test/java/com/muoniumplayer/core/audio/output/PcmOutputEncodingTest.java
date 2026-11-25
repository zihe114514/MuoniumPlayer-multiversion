package com.muoniumplayer.core.audio.output;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

class PcmOutputEncodingTest {
    @Test void pcm16ClipsRoundsAndSanitizes() {
        assertEquals(-32768, PcmOutputEncoding.pcm16(-1f));
        assertEquals(32767, PcmOutputEncoding.pcm16(1f));
        assertEquals(-32768, PcmOutputEncoding.pcm16(-100f));
        assertEquals(32767, PcmOutputEncoding.pcm16(100f));
        assertEquals(16384, PcmOutputEncoding.pcm16(0.5f));
        assertEquals(-16384, PcmOutputEncoding.pcm16(-0.5f));
        assertEquals(0, PcmOutputEncoding.pcm16(Float.NaN));
        assertEquals(0, PcmOutputEncoding.pcm16(Float.POSITIVE_INFINITY));
        assertEquals(0, PcmOutputEncoding.pcm16(Float.NEGATIVE_INFINITY));
    }

    @Test void floatPreservesSubPcm16PrecisionAndHeadroom() {
        ByteBuffer bytes = ByteBuffer.allocate(28).order(ByteOrder.nativeOrder());
        float quiet = 0x1.0p-22f;
        PcmOutputEncoding.encode(new float[] {quiet, -quiet, 2, -2, Float.NaN, -0f, 0.5f}, 7, 1, false, true, bytes);
        assertEquals(Float.floatToRawIntBits(quiet), bytes.getInt());
        assertEquals(Float.floatToRawIntBits(-quiet), bytes.getInt());
        assertEquals(2f, bytes.getFloat());
        assertEquals(-2f, bytes.getFloat());
        assertEquals(0f, bytes.getFloat());
        assertEquals(Float.floatToRawIntBits(-0f), bytes.getInt());
        assertEquals(0.5f, bytes.getFloat());
    }

    @Test void monoDuplicationAndSevenPointOneOrderAreExact() {
        ByteBuffer bytes = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder());
        PcmOutputEncoding.encode(new float[] {0.25f, -0.5f}, 2, 1, true, false, bytes);
        assertEquals(8192, bytes.getShort()); assertEquals(8192, bytes.getShort());
        assertEquals(-16384, bytes.getShort()); assertEquals(-16384, bytes.getShort());
        float[] channels = {0.1f,0.2f,0.3f,0.4f,0.5f,0.6f,0.7f,0.8f};
        PcmOutputEncoding.encode(channels, 1, 8, false, true, bytes);
        for (float channel : channels) assertEquals(channel, bytes.getFloat());
        assertFalse(bytes.hasRemaining());
    }
}
