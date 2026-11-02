package com.muoniumplayer.core.music.source;

/**
 * 取流结果。自 自原 music-api-0.1.1.jar 手翻，2026-11 吸收进仓（原包 top.fpsmaster.music，现为 muonium 私有部分）。
 *
 * <p>{@code available=false} 时 {@code reason} 带人读文案；{@code trial*} 三字段只有
 * {@code trial=true} 时有意义。</p>
 *
 * <p>2026-11 补齐：新增 {@code resultCode}（上游 midurlinfo.result 原始码）与
 * {@code hitPrefix}（实际命中的文件前缀，如 F000/O801/M800）。旧构造器保持原样，
 * 新构造器供 QQ 取流链路使用。</p>
 */
public final class SongUrl {
    /** 上游取流 result 码语义（QQMusicApi docs/tutorial/download.md 码表）。 */
    public static final int RESULT_OK = 0;
    public static final int RESULT_NO_PERMISSION = 104003; // 无权限（未登录或等级不够）
    public static final int RESULT_VKEY_FAILED = 104004;   // VKey 获取失败
    public static final int RESULT_DEVICE_LIMIT = 104013;  // 播放设备受限

    private final String url;
    private final AudioQuality quality;
    private final String format;
    private final long sizeBytes;
    private final boolean trial;
    private final long trialStartMs;
    private final long trialEndMs;
    private final String reason;
    private final int resultCode;
    private final String hitPrefix;

    public SongUrl(String url, AudioQuality quality, String format, long sizeBytes,
                   boolean trial, long trialStartMs, long trialEndMs, String reason) {
        this(url, quality, format, sizeBytes, trial, trialStartMs, trialEndMs, reason,
                -1, null);
    }

    public SongUrl(String url, AudioQuality quality, String format, long sizeBytes,
                   boolean trial, long trialStartMs, long trialEndMs, String reason,
                   int resultCode, String hitPrefix) {
        this.url = url;
        this.quality = quality;
        this.format = format;
        this.sizeBytes = sizeBytes;
        this.trial = trial;
        this.trialStartMs = trialStartMs;
        this.trialEndMs = trialEndMs;
        this.reason = reason;
        this.resultCode = resultCode;
        this.hitPrefix = hitPrefix;
    }

    /** 不可用时的便捷构造（url 为空串）。 */
    public static SongUrl unavailable(AudioQuality quality, String reason) {
        return unavailable(quality, reason, -1);
    }

    /** 不可用 + 上游 result 码（归因分级用）。 */
    public static SongUrl unavailable(AudioQuality quality, String reason, int resultCode) {
        return new SongUrl("", quality, "", 0, false, 0, 0, reason, resultCode, null);
    }

    public String getUrl() { return url; }

    public AudioQuality getQuality() { return quality; }

    public String getFormat() { return format; }

    public long getSizeBytes() { return sizeBytes; }

    public boolean isTrial() { return trial; }

    public long getTrialStartMs() { return trialStartMs; }

    public long getTrialEndMs() { return trialEndMs; }

    public String getReason() { return reason; }

    /** 上游 result 码；-1 = 未取到（网络失败/响应缺字段）。 */
    public int getResultCode() { return resultCode; }

    /** 实际命中的文件前缀（F000/O801/M800...）；未命中为 null。 */
    public String getHitPrefix() { return hitPrefix; }

    public boolean getAvailable() { return url != null && !url.isEmpty(); }
}
