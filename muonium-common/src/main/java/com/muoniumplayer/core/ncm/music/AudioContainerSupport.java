package com.muoniumplayer.core.ncm.music;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** Native audio-track probe shared by download validation and file preparation. */
public final class AudioContainerSupport {
    // Cache naming aliases only. File capability is proved by a successful native audio probe.
    private static final List<String> SUPPORTED_CONTAINERS = List.of(
            "flac", "wav", "mp3", "aac", "m4a", "mp4", "ogg", "opus", "ape", "aiff",
            "asf", "wma", "mkv", "webm", "wv", "ac3", "eac3", "dts", "amr", "caf", "au",
            "ts", "audio");

    private AudioContainerSupport() {}

    public static boolean isSupportedContainer(String container) {
        return container != null && SUPPORTED_CONTAINERS.contains(container);
    }
    public static List<String> getSupportedContainers() { return SUPPORTED_CONTAINERS; }

    static String normalizeReportedContainer(String reportedType) {
        if (reportedType == null) return null;
        String type = reportedType.trim().toLowerCase(Locale.ROOT);
        int parameter = type.indexOf(';');
        if (parameter >= 0) type = type.substring(0, parameter).trim();
        if (type.startsWith("audio/")) type = type.substring(6);
        if (type.startsWith("video/")) type = type.substring(6);
        type = switch (type) {
            case "mpeg", "mpga", "x-mp3" -> "mp3";
            case "wave", "x-wav" -> "wav";
            case "x-flac" -> "flac";
            case "adts", "x-aac" -> "aac";
            case "mp4a", "x-m4a" -> "m4a";
            case "x-ms-wma" -> "asf";
            case "x-aiff", "aif", "aifc" -> "aiff";
            case "x-matroska", "matroska" -> "mkv";
            default -> type;
        };
        return isSupportedContainer(type) ? type : null;
    }

    /** No extension or hand-written signature can establish audio decodability. */
    public static String detectContainer(File source) {
        if (source == null || !source.isFile() || source.length() == 0) return null;
        try {
            String demuxer = NativeAudioPreparation.probe(source).container();
            if (demuxer == null) return null;
            List<String> names = List.of(demuxer.toLowerCase(Locale.ROOT).split(","));
            if (names.contains("mov") || names.contains("mp4")) return "mp4";
            if (names.contains("matroska") || names.contains("webm")) return "mkv";
            if (names.contains("mpegts")) return "ts";
            for (String name : names) {
                if (isSupportedContainer(name)) return name;
            }
            // A successful audio-track/decoder probe may expose an uncommon enabled demuxer.
            return "audio";
        } catch (IOException | LinkageError failure) {
            return null;
        }
    }
}
