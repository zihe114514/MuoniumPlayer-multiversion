package com.muoniumplayer.core.audio.ffmpeg;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avformat.AVInputFormat;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swresample.*;

/** Runtime enumeration of the libraries actually loaded, suitable for trim acceptance tests. */
public record NativeCapabilities(String version, Map<String, Integer> libraryVersions,
                                 Map<String, String> buildConfigurations,
                                 Set<String> audioDecoders, Set<String> demuxers,
                                 Set<String> inputProtocols) {
    public NativeCapabilities {
        libraryVersions = Map.copyOf(libraryVersions);
        buildConfigurations = Map.copyOf(buildConfigurations);
        audioDecoders = Set.copyOf(audioDecoders);
        demuxers = Set.copyOf(demuxers);
        inputProtocols = Set.copyOf(inputProtocols);
    }
    public static NativeCapabilities query() throws IOException {
        FfmpegRuntime.ensureLoaded();
        Set<String> codecs = new TreeSet<>();
        Set<String> formats = new TreeSet<>();
        Set<String> protocols = new TreeSet<>();
        try (PointerPointer<Pointer> state = new PointerPointer<>(1)) {
            state.put(0, (Pointer) null);
            AVCodec codec;
            while ((codec = av_codec_iterate(state)) != null && !codec.isNull()) {
                if (codec.type() == AVMEDIA_TYPE_AUDIO && av_codec_is_decoder(codec) != 0) codecs.add(codec.name().getString());
            }
            state.put(0, (Pointer) null);
            AVInputFormat format;
            while ((format = av_demuxer_iterate(state)) != null && !format.isNull()) {
                formats.addAll(Set.of(format.name().getString().split(",")));
            }
            state.put(0, (Pointer) null);
            BytePointer protocol;
            while ((protocol = avio_enum_protocols(state, 0)) != null && !protocol.isNull()) protocols.add(protocol.getString());
        }
        // Enumeration results and version/configuration strings are borrowed static pointers.
        return new NativeCapabilities(av_version_info().getString(),
                Map.of("avutil", avutil_version(), "avcodec", avcodec_version(),
                        "avformat", avformat_version(), "swresample", swresample_version()),
                Map.of("avutil", avutil_configuration().getString(), "avcodec", avcodec_configuration().getString(),
                        "avformat", avformat_configuration().getString(), "swresample", swresample_configuration().getString()),
                codecs, formats, protocols);
    }
}
