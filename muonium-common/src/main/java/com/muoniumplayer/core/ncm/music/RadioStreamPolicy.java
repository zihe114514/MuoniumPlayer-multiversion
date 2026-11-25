package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.audio.ffmpeg.AudioInput;
import com.muoniumplayer.core.audio.ffmpeg.DecodeOptions;
import com.muoniumplayer.core.audio.ffmpeg.OutputSpec;
import com.muoniumplayer.core.audio.transport.JvmTlsTrust;
import com.muoniumplayer.core.settings.ConfigPaths;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;

/** Radio input policy only: no download cache, playlist parser, account or native ownership. */
final class RadioStreamPolicy {
    static final int BLOCK_FRAMES = 4096;
    static final int MAX_CONSECUTIVE_FAILURES = 12;
    static final long RETRY_MILLIS = 1000L;
    static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    private static final Pattern GONE = Pattern.compile("(?:Server returned\\s+|HTTP(?:\\s+status)?\\s+|Response code is\\s+)(404|410)(?![0-9])", Pattern.CASE_INSENSITIVE);

    private RadioStreamPolicy() { }

    static URI uri(String value) {
        try {
            URI uri = new URI(value == null ? "" : value.trim());
            if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("广播需要有效的 HTTP(S) 地址");
            }
            return uri;
        } catch (URISyntaxException invalid) {
            // Signed URLs must not escape into notifications or exception diagnostics.
            throw new IllegalArgumentException("广播需要有效的 HTTP(S) 地址");
        }
    }

    /** Worker-only IO. HTTP can redirect to HTTPS, including nested HLS resources. */
    static AudioInput input(String value) throws IOException {
        URI uri = uri(value);
        Path ca = JvmTlsTrust.export(ConfigPaths.CACHE_DIR.toPath().resolve("audio-tls"));
        return input(uri, ca);
    }

    /** HLS stays an HTTP(S) URI; the common demuxer owns its manifests and segments. */
    static AudioInput input(URI uri, Path caFile) {
        DecodeOptions options = DecodeOptions.builder()
                .live(true)
                .openTimeout(Duration.ofSeconds(15))
                .readTimeout(Duration.ofSeconds(20))
                .header("User-Agent", USER_AGENT)
                .header("Icy-MetaData", "0")
                .option("icy", "0")
                // The radio worker owns retries; do not multiply native and application retries.
                .option("reconnect", "0")
                .caFile(caFile)
                .build();
        return AudioInput.uri(uri(uri.toASCIIString())).withOptions(options);
    }

    /** Explicit radio render format; multichannel music policy belongs to the main player. */
    static OutputSpec output() { return OutputSpec.stereo(48000); }

    static float volume(float value) {
        return Float.isFinite(value) ? Math.max(0f, Math.min(1f, value)) : 0f;
    }

    static boolean channelGone(Throwable failure) {
        for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            if (failure.getMessage() != null && GONE.matcher(failure.getMessage()).find()) return true;
        }
        return false;
    }

    static String failureReason(Throwable failure) {
        if (channelGone(failure)) return "这个频道已下线或暂时没有直播（HTTP 404/410）";
        if (failure instanceof LinkageError) return "直播音频原生库无法加载";
        if (failure instanceof IllegalArgumentException) return "直播地址或音频参数无效";
        return "流连接或解码持续失败";
    }
}
