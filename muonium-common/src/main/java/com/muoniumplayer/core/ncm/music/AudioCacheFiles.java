package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.settings.ConfigPaths;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Managed audio-cache paths and explicit group invalidation; local music is always read-only. */
final class AudioCacheFiles {
    private AudioCacheFiles() {}

    static File getDecodedWavFile(File source) {
        File parent = source.getAbsoluteFile().getParentFile();
        String name = source.getName() + ".native-f32.decoded.wav";
        if (!isManagedCacheDirectory(parent)) {
            return new File(new File(ConfigPaths.DECODED_WAV_DIR, stableSidecarKey(source)), name);
        }
        return new File(parent, name);
    }

    private static boolean isManagedCacheDirectory(File directory) {
        if (directory == null) return false;
        try {
            return directory.getCanonicalFile().toPath().startsWith(ConfigPaths.CACHE_DIR.getCanonicalFile().toPath());
        } catch (IOException failure) {
            return false;
        }
    }
    static boolean isManagedCacheFile(File file) {
        if (file == null) return false;
        try {
            return isManagedCacheDirectory(file.getCanonicalFile().getParentFile());
        } catch (IOException failure) { return false; }
    }
    private static String stableSidecarKey(File source) {
        try {
            byte[] identity = source.getCanonicalPath().getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity));
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to resolve audio cache identity", failure);
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    static boolean isReusableDecodedWav(File source, File decoded) {
        return NativeAudioPreparation.isReusable(source, decoded);
    }

    static File findCachedAudioFile(File directory, String key, String reportedType) {
        List<String> types = new ArrayList<>();
        String reported = AudioContainerSupport.normalizeReportedContainer(reportedType);
        if (reported != null) types.add(reported);
        for (String type : AudioContainerSupport.getSupportedContainers()) {
            if (!types.contains(type)) types.add(type);
        }
        for (String type : types) {
            File candidate = new File(directory, key + "." + type);
            if (!candidate.isFile()) continue;
            String actual = AudioContainerSupport.detectContainer(candidate);
            // A failed native load/probe must not turn cache lookup into destructive cleanup.
            if (!AudioContainerSupport.isSupportedContainer(actual)) continue;
            File normalized = new File(directory, key + "." + actual);
            if (candidate.equals(normalized)) return candidate;
            if (normalized.isFile() && actual.equals(AudioContainerSupport.detectContainer(normalized))) {
                return normalized;
            }
            moveCacheFile(candidate, normalized);
            return normalized;
        }
        return null;
    }

    /** Explicit retry / rejected preview invalidates raw source and all products of this managed key. */
    static void invalidateCacheKey(File directory, String key) {
        invalidateCacheKey(directory, key, null);
    }

    static void invalidateCacheKey(File directory, String key, File keep) {
        if (!isManagedCacheDirectory(directory) || key == null || key.isEmpty()
                || key.indexOf('/') >= 0 || key.indexOf('\\') >= 0) return;
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (!file.equals(keep) && file.isFile()
                    && file.getName().startsWith(key + ".") && isManagedCacheFile(file)) {
                try { Files.deleteIfExists(file.toPath()); }
                catch (IOException failure) {
                    throw new IllegalStateException("Unable to invalidate rejected audio cache: " + file.getName(), failure);
                }
            }
        }
    }

    static void invalidatePlayableAudio(File playable) {
        if (!isManagedCacheFile(playable)) return;
        File original = NativeAudioPreparation.originalSource(playable);
        if (original != null && !isManagedCacheFile(original)) {
            // A local user's source is never removed, even when its decoded copy is rejected.
            try { Files.deleteIfExists(playable.toPath()); }
            catch (IOException failure) { throw new IllegalStateException("Unable to invalidate local decoded cache", failure); }
            return;
        }
        File parent = playable.getAbsoluteFile().getParentFile();
        String name = playable.getName();
        int dot = name.indexOf('.');
        if (dot > 0) invalidateCacheKey(parent, name.substring(0, dot));
    }

    static void moveCacheFile(File source, File destination) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to finalize music cache file: " + source.getName(), failure);
        }
    }
}
