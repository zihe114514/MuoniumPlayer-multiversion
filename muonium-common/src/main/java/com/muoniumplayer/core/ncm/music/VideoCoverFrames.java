package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.audio.ffmpeg.FfmpegCoverDecoder;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/** Bounded worker-side video decoding. A failed animation leaves the static cover in place. */
final class VideoCoverFrames {
    static final int FRAME_SIZE = 256;
    static final int FRAMES_PER_SECOND = 10;
    static final int MAX_FRAMES = 50;

    private VideoCoverFrames() { }

    static long frameDurationMillis() {
        return Math.max(40L, 1000L / FRAMES_PER_SECOND);
    }

    /** No native handles escape; ordinary decode failures discard the complete animation. */
    static List<BufferedImage> decode(File video) {
        if (video == null || !video.isFile() || video.length() <= 0) return Collections.emptyList();
        try {
            return FfmpegCoverDecoder.decode(video);
        } catch (IOException | RuntimeException failure) {
            String message = failure.getMessage();
            if (message == null || message.isBlank()) message = failure.getClass().getSimpleName();
            message = message.replace('\r', ' ').replace('\n', ' ');
            System.err.println("[Music/Cover] Animated cover decode failed: " + message.substring(0, Math.min(180, message.length())));
            return Collections.emptyList();
        }
    }
}
