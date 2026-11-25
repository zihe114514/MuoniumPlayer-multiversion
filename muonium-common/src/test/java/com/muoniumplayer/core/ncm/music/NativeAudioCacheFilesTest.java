package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

class NativeAudioCacheFilesTest {
    @TempDir Path directory;

    @Test void localSidecarDoesNotWriteIntoUserDirectoryAndKeepsFullSourceIdentity() throws Exception {
        Path first = directory.resolve("first/same.wav");
        Path second = directory.resolve("second/same.wav");
        Files.createDirectories(first.getParent());
        Files.createDirectories(second.getParent());
        Files.write(first, NativeAudioPreparationTest.wave(22050, 1, new float[]{0.1f, 0.2f}));
        Files.write(second, Files.readAllBytes(first));
        Path a = AudioCacheFiles.getDecodedWavFile(first.toFile()).toPath().toAbsolutePath();
        Path b = AudioCacheFiles.getDecodedWavFile(second.toFile()).toPath().toAbsolutePath();
        assertFalse(a.startsWith(directory));
        assertFalse(b.startsWith(directory));
        assertNotEquals(a, b);
        assertTrue(a.startsWith(ConfigPaths.DECODED_WAV_DIR.toPath().toAbsolutePath()));
    }

    @Test void rejectedManagedPreviewInvalidatesOriginalAndPcm() throws Exception {
        Path managed = managedDirectory();
        try {
            Path source = managed.resolve("preview.mp3");
            Files.write(source, NativeAudioPreparationTest.wave(22050, 1, new float[]{0.1f, 0.2f}));
            Path decoded = AudioCacheFiles.getDecodedWavFile(source.toFile()).toPath();
            NativeAudioPreparation.decodeToWav(source.toFile(), decoded.toFile(), null);
            assertTrue(AudioCacheFiles.isReusableDecodedWav(source.toFile(), decoded.toFile()));
            AudioCacheFiles.invalidatePlayableAudio(decoded.toFile());
            assertFalse(Files.exists(source));
            assertFalse(Files.exists(decoded));
            assertNull(AudioCacheFiles.findCachedAudioFile(managed.toFile(), "preview", "mp3"));
        } finally { removeTestDirectory(managed); }
    }

    @Test void explicitRetryKeepsValidatedReplacementDownloadWhileRemovingBadOldGroup() throws Exception {
        Path managed = managedDirectory();
        try {
            Path rejected = managed.resolve("song.m4a");
            Path oldPcm = managed.resolve("song.m4a.native-f32.decoded.wav");
            Path replacement = managed.resolve("song.download");
            Path other = managed.resolve("another.mp3");
            Files.writeString(rejected, "rejected payload");
            Files.writeString(oldPcm, "old cache");
            Files.write(replacement, NativeAudioPreparationTest.wave(22050, 1, new float[]{0.1f, 0.2f}));
            Files.writeString(other, "other song");
            AudioCacheFiles.invalidateCacheKey(managed.toFile(), "song", replacement.toFile());
            assertFalse(Files.exists(rejected));
            assertFalse(Files.exists(oldPcm));
            assertTrue(Files.exists(replacement));
            assertTrue(Files.exists(other));
            AudioCacheFiles.moveCacheFile(replacement.toFile(), managed.resolve("song.wav").toFile());
            assertEquals(managed.resolve("song.wav").toFile(),
                    AudioCacheFiles.findCachedAudioFile(managed.toFile(), "song", "mp3"));
        } finally { removeTestDirectory(managed); }
    }

    @Test void localRawSourceIsNeverDeletedByPreviewOrRetryInvalidation() throws Exception {
        Path local = directory.resolve("song.wav");
        byte[] original = NativeAudioPreparationTest.wave(22050, 1, new float[]{0.1f, 0.2f});
        Files.write(local, original);
        AudioCacheFiles.invalidatePlayableAudio(local.toFile());
        AudioCacheFiles.invalidateCacheKey(directory.toFile(), "song");
        assertArrayEquals(original, Files.readAllBytes(local));
    }

    @Test void nativeProbeNormalizesAnIncorrectCacheSuffix() throws Exception {
        Path wrong = directory.resolve("song.mp3");
        Files.write(wrong, NativeAudioPreparationTest.wave(22050, 1, new float[]{0.1f, 0.2f}));
        assertEquals(directory.resolve("song.wav").toFile(),
                AudioCacheFiles.findCachedAudioFile(directory.toFile(), "song", "mp3"));
        assertFalse(Files.exists(wrong));
    }

    @Test void cloudAlternateUrlExplicitlyBypassesCacheAndPreviewDeletesTheWholeIdentity() throws Exception {
        String cloud = Files.readString(ProjectPaths.sharedJava().resolve("com/muoniumplayer/core/ncm/music/CloudMusic.java"));
        assertTrue(cloud.contains("normalizeReportedContainer(standardPlayUrl.getB()), song, true)"));
        assertTrue(cloud.contains("File cachedMusic = forceReload ? null"));
        assertTrue(cloud.contains("invalidateCacheKey(musicCacheDir, cacheKey, temporaryMusic)"));
        assertEquals(3, cloud.split("AudioCacheFiles\\.invalidatePlayableAudio\\(", -1).length - 1);
        assertFalse(cloud.contains("sourceFile.delete()"));
    }

    private static Path managedDirectory() throws IOException {
        Files.createDirectories(ConfigPaths.CACHE_DIR.toPath());
        return Files.createTempDirectory(ConfigPaths.CACHE_DIR.toPath(), "native-audio-test-").toAbsolutePath();
    }
    private static void removeTestDirectory(Path directory) throws IOException {
        // Only the directory created by this test, never a shared cache root.
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
