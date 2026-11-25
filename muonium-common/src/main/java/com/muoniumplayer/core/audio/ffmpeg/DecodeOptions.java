package com.muoniumplayer.core.audio.ffmpeg;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable per-input settings; never changes FFmpeg's global configuration. */
public final class DecodeOptions {
    private final Map<String, String> headers;
    private final Map<String, String> options;
    private final Duration openTimeout;
    private final Duration readTimeout;
    private final boolean live;
    private final int streamIndex;

    private DecodeOptions(Builder b) {
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(b.headers));
        options = Collections.unmodifiableMap(new LinkedHashMap<>(b.options));
        openTimeout = b.openTimeout;
        readTimeout = b.readTimeout;
        live = b.live;
        streamIndex = b.streamIndex;
    }

    public static Builder builder() { return new Builder(); }
    public static DecodeOptions defaults() { return builder().build(); }
    public Map<String, String> headers() { return headers; }
    public Map<String, String> options() { return options; }
    public Duration openTimeout() { return openTimeout; }
    public Duration readTimeout() { return readTimeout; }
    public boolean live() { return live; }
    /** Absolute container stream index, or -1 for FFmpeg's best audio stream. */
    public int streamIndex() { return streamIndex; }

    public static final class Builder {
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final Map<String, String> options = new LinkedHashMap<>();
        private Duration openTimeout = Duration.ofSeconds(15);
        private Duration readTimeout = Duration.ofSeconds(30);
        private boolean live;
        private int streamIndex = -1;

        public Builder header(String name, String value) {
            Objects.requireNonNull(name, "header name");
            Objects.requireNonNull(value, "header value");
            if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || invalidValue(value)) {
                throw new IllegalArgumentException("Invalid HTTP header");
            }
            headers.put(name, value);
            return this;
        }
        /** Native demux/protocol options. Unconsumed options are exposed by AudioInfo. */
        public Builder option(String name, String value) {
            Objects.requireNonNull(name, "option name");
            Objects.requireNonNull(value, "option value");
            if (!name.matches("[A-Za-z0-9_]+") || invalidValue(value)) {
                throw new IllegalArgumentException("Invalid FFmpeg option");
            }
            if (name.equals("tls_verify") && !value.equals("1")) {
                throw new IllegalArgumentException("TLS certificate verification must remain enabled");
            }
            if (name.equals("headers")) {
                throw new IllegalArgumentException("Use header(name, value) for HTTP headers");
            }
            options.put(name, value);
            return this;
        }
        public Builder caFile(Path path) { return option("ca_file", Objects.requireNonNull(path).toAbsolutePath().toString()); }
        public Builder openTimeout(Duration timeout) { openTimeout = timeout(timeout); return this; }
        public Builder readTimeout(Duration timeout) { readTimeout = timeout(timeout); return this; }
        public Builder live(boolean value) { live = value; return this; }
        public Builder streamIndex(int index) {
            if (index < -1) throw new IllegalArgumentException("stream index < -1");
            streamIndex = index;
            return this;
        }
        public DecodeOptions build() { return new DecodeOptions(this); }
        private static boolean invalidValue(String value) {
            return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0;
        }
        private static Duration timeout(Duration value) {
            Objects.requireNonNull(value, "timeout");
            if (value.isNegative() || value.isZero() || value.compareTo(Duration.ofHours(1)) > 0) {
                throw new IllegalArgumentException("timeout must be > 0 and <= 1 hour");
            }
            return value;
        }
    }
}
