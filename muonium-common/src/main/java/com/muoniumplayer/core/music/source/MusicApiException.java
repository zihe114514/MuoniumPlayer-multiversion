package com.muoniumplayer.core.music.source;

/**
 * 内置音源层业务异常。自 自原 music-api-0.1.1.jar 手翻，2026-11 吸收进仓（原包 top.fpsmaster.music，现为 muonium 私有部分）。
 */
public final class MusicApiException extends RuntimeException {
    public MusicApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
