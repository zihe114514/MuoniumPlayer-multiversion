package com.muoniumplayer.core.audio.ffmpeg;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** A file, or an HTTP(S) resource (including HLS playlists). No IO in factories. */
public final class AudioInput {
    private final String location;
    private final Path file;
    private final boolean network;
    private final DecodeOptions options;

    private AudioInput(String location, Path file, boolean network, DecodeOptions options) {
        this.location = location;
        this.file = file;
        this.network = network;
        this.options = Objects.requireNonNull(options);
    }
    public static AudioInput file(Path path) {
        Path absolute = Objects.requireNonNull(path).toAbsolutePath();
        return new AudioInput(absolute.toString(), absolute, false, DecodeOptions.defaults());
    }
    public static AudioInput uri(URI uri) {
        Objects.requireNonNull(uri, "uri");
        String scheme = Objects.requireNonNullElse(uri.getScheme(), "").toLowerCase(Locale.ROOT);
        if (scheme.equals("file")) return file(Path.of(uri));
        if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Expected a file or absolute HTTP(S) URI without fragment");
        }
        return new AudioInput(uri.toASCIIString(), null, true, DecodeOptions.defaults());
    }
    public AudioInput withOptions(DecodeOptions value) { return new AudioInput(location, file, network, value); }
    public DecodeOptions options() { return options; }
    public boolean network() { return network; }
    String location() { return location; }
    Path filePath() { return file; }
    // Avoid exposing signed URLs, credentials and headers in diagnostic messages.
    @Override public String toString() { return network ? "AudioInput[HTTP(S)]" : "AudioInput[file]"; }
}
