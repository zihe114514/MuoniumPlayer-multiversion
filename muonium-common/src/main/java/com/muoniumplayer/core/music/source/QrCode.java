package com.muoniumplayer.core.music.source;

/**
 * 扫码会话。自 自原 music-api-0.1.1.jar 手翻，2026-11 吸收进仓（原包 top.fpsmaster.music，现为 muonium 私有部分）。
 *
 * <p>{@code qrContent} 可能是 {@code data:image/...} 形态（微信只给图片不给码文本）。</p>
 */
public final class QrCode {
    private final String key;
    private final String qrContent;

    public QrCode(String key, String qrContent) {
        this.key = key;
        this.qrContent = qrContent;
    }

    public String getKey() { return key; }

    public String getQrContent() { return qrContent; }
}
