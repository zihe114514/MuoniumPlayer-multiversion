package com.muoniumplayer.core.audio.ffmpeg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.bytedeco.ffmpeg.global.avutil.av_strerror;

final class NativeErrors {
    private NativeErrors() { }
    static IOException failure(String operation, int code) {
        byte[] text = new byte[256];
        int result = av_strerror(code, text, text.length);
        int end = 0;
        while (end < text.length && text[end] != 0) end++;
        String message = result < 0 ? "unknown native error" : new String(text, 0, end, StandardCharsets.UTF_8);
        return new IOException(operation + " failed: " + message + " (FFmpeg " + code + ")");
    }
    static void check(String operation, int result) throws IOException {
        if (result < 0) throw failure(operation, result);
    }
}
