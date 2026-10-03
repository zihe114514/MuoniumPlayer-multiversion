package com.muoniumplayer.core.ncm.music;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class MusicDownloadCancellationTest {
    @AfterEach
    void clearInterrupt() { Thread.interrupted(); }

    @Test
    void normalTransferStillCopiesEveryByte() {
        byte[] data = new byte[150_123];
        Arrays.fill(data, (byte) 73);
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        MusicDownloadService.writeTo(new ByteArrayInputStream(data), target);
        assertArrayEquals(data, target.toByteArray());
    }

    @Test
    void cancelledTransferDoesNotReadOrWriteAnotherBlock() {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        ByteArrayInputStream source = new ByteArrayInputStream(new byte[64]);
        Thread.currentThread().interrupt();
        assertThrows(InterruptedIOException.class, () -> MusicDownloadService.writeTo(source, target));
        assertEquals(64, source.available());
        assertEquals(0, target.size());
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void cancellationDuringReadDoesNotPublishThatBlock() {
        ByteArrayInputStream source = new ByteArrayInputStream(new byte[64]) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                int result = super.read(bytes, offset, length);
                Thread.currentThread().interrupt();
                return result;
            }
        };
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        assertThrows(InterruptedIOException.class, () -> MusicDownloadService.writeTo(source, target));
        assertEquals(0, target.size());
    }
}
