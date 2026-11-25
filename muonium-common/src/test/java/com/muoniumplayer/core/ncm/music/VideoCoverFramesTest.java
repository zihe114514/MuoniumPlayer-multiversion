package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.audio.ffmpeg.CoverVideoTestFixtures;
import com.muoniumplayer.core.audio.ffmpeg.FfmpegCoverDecoder;
import com.muoniumplayer.core.audio.ffmpeg.FfmpegRuntime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VideoCoverFramesTest {
    @TempDir Path temp;
    @BeforeAll static void videoNativeRequired() throws IOException { FfmpegRuntime.ensureVideoLoaded(); }

    @Test void preservesPackageContractAndUsesRealInProcessFrames() throws Exception {
        assertEquals(256, VideoCoverFrames.FRAME_SIZE);
        assertEquals(10, VideoCoverFrames.FRAMES_PER_SECOND);
        assertEquals(50, VideoCoverFrames.MAX_FRAMES);
        assertEquals(FfmpegCoverDecoder.FRAME_SIZE, VideoCoverFrames.FRAME_SIZE);
        assertEquals(FfmpegCoverDecoder.FRAMES_PER_SECOND, VideoCoverFrames.FRAMES_PER_SECOND);
        assertEquals(FfmpegCoverDecoder.MAX_FRAMES, VideoCoverFrames.MAX_FRAMES);
        assertEquals(100L, VideoCoverFrames.frameDurationMillis());
        Path video = temp.resolve("cover.mp4");
        Files.write(video, CoverVideoTestFixtures.h264());
        var frames = VideoCoverFrames.decode(video.toFile());
        assertEquals(12, frames.size());
        assertEquals(0, frames.get(0).getRGB(128, 0));
        Files.delete(video);
    }

    @Test void invalidOrUnsupportedVideoKeepsStaticFallback() throws Exception {
        assertTrue(VideoCoverFrames.decode(null).isEmpty());
        assertTrue(VideoCoverFrames.decode(temp.resolve("missing.mp4").toFile()).isEmpty());
        Path corrupt = temp.resolve("corrupt.mp4");
        Files.write(corrupt, new byte[] {1, 4, 0, 12, 9});
        assertTrue(VideoCoverFrames.decode(corrupt.toFile()).isEmpty());
        Files.delete(corrupt);
    }

    @Test void interruptedDecodeKeepsStaticCoverAndPreservesInterrupt() throws Exception {
        Path video = temp.resolve("cover.mp4"); Files.write(video, CoverVideoTestFixtures.h264());
        Thread.currentThread().interrupt();
        try {
            assertTrue(VideoCoverFrames.decode(video.toFile()).isEmpty());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test void errorsAreNeverConvertedToSuccessfulStaticFallback() throws Exception {
        Path video = temp.resolve("error-cover.mp4"); Files.write(video, CoverVideoTestFixtures.h264());
        File error = new File(video.toString()) {
            @Override public String getAbsolutePath() { throw new OutOfMemoryError("sentinel"); }
        };
        assertThrows(OutOfMemoryError.class, () -> VideoCoverFrames.decode(error));
    }
}
