package com.muoniumplayer.core.preset.codec;

/**
 * 预设读不进来。{@link #getMessage()} 直接是给玩家看的一句中文，界面原样显示。
 */
public final class PresetFormatException extends Exception {

    private static final long serialVersionUID = 1L;

    public PresetFormatException(String message) {
        super(message);
    }

    public PresetFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
