package com.muoniumplayer.core.audio.ffmpeg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class FfmpegNativeStagingTest {
    @TempDir Path temp;
    @Test void stagesWholeCohortAndRepairsCorruptCopyWithoutChangingSource() throws Exception {
        Path source = Files.createDirectory(temp.resolve("installed"));
        Files.write(source.resolve("libavutil.so"), new byte[] {1, 2, 3});
        Files.write(source.resolve("libjniavutil.so"), new byte[] {4, 5, 6});
        Path blocked = Files.write(temp.resolve("not-a-directory"), new byte[] {9});
        Path actual = FfmpegNativeStaging.stage(source, List.of(blocked, temp.resolve("private")), "android-arm64");
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(actual.resolve("libavutil.so")));
        Files.write(actual.resolve("libavutil.so"), new byte[] {7});
        assertEquals(actual, FfmpegNativeStaging.stage(source, List.of(temp.resolve("private")), "android-arm64"));
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(actual.resolve("libavutil.so")));
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(source.resolve("libavutil.so")));
    }
    @Test void namespaceCandidatesPreferMappedJvmAndExcludeExternalStorage() {
        List<Path> roots = FfmpegNativeStaging.candidateRoots(Map.of("java.io.tmpdir", "/storage/emulated/0/tmp"),
                Map.of("FCL_NATIVEDIR", "/data/user/0/launcher/native"), "/data/user/0/launcher/jvm/lib/server");
        assertEquals(Path.of("/data/user/0/launcher/jvm/lib/server").toAbsolutePath(), roots.get(0));
        assertEquals(2, roots.size());
        assertEquals("/data/user/0/jre/lib/server", FfmpegNativeStaging.jvmDirectory("1-2 r-xp 0 0 0 /data/user/0/jre/lib/server/libjvm.so"));
    }
    @Test void desktopKeepsVerifiedInstallDirectory() throws Exception {
        assertEquals(temp, FfmpegNativeStaging.prepare(temp, "linux-x86_64"));
    }
}
