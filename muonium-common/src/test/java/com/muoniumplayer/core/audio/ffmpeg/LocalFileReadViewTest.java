package com.muoniumplayer.core.audio.ffmpeg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class LocalFileReadViewTest {
    @TempDir Path temp;

    private Path file(byte[] bytes) throws Exception {
        Path path = temp.resolve("音频 file,with,commas.flac");
        Files.write(path, bytes);
        return path;
    }
    static byte[] withTag(byte[] audio, int count) {
        byte[] result = Arrays.copyOf(audio, audio.length + count);
        Arrays.fill(result, audio.length, result.length, (byte) ' ');
        System.arraycopy(new byte[]{'T', 'A', 'G'}, 0, result, audio.length, 3);
        return result;
    }
    @Test void boundedViewsRecognizeAllFourLegacyTagLengthsWithoutEditingSource() throws Exception {
        byte[] audio = AudioTestFixtures.constantFlac(16, 2, 32);
        for (int length : new int[]{24, 40, 44, 128}) {
            byte[] tagged = withTag(audio, length);
            Path path = file(tagged);
            var view = LocalFileReadView.excludingTrailingTag(path, new CancellationToken());
            assertNotNull(view);
            assertEquals(audio.length, view.endExclusive());
            assertEquals("subfile,,start,0,end," + audio.length + ",,:file:" + path.toAbsolutePath(), view.location());
            assertArrayEquals(tagged, Files.readAllBytes(path));
        }
    }
    @Test void completeId3v11AllowsTrackAndGenreBytesAndRealText() throws Exception {
        byte[] audio = AudioTestFixtures.constantFlac(16, 2, 32);
        byte[] tagged = withTag(audio, 128);
        byte[] title = "Synthetic title".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        System.arraycopy(title, 0, tagged, audio.length + 3, title.length);
        System.arraycopy(new byte[]{'2','0','2','6'}, 0, tagged, audio.length + 93, 4);
        tagged[audio.length + 125] = 0;
        tagged[audio.length + 126] = 7;
        tagged[audio.length + 127] = (byte) 255;
        var view = LocalFileReadView.excludingTrailingTag(file(tagged), new CancellationToken());
        assertNotNull(view);
        assertEquals(audio.length, view.endExclusive());
    }
    @Test void rejectsAmbiguousTagMarkersAndLeavesOtherBytesVisible() throws Exception {
        byte[] audio = AudioTestFixtures.constantFlac(16, 2, 32);
        assertNull(LocalFileReadView.excludingTrailingTag(file(audio), new CancellationToken()));
        byte[] shortTag = withTag(audio, 23);
        assertNull(LocalFileReadView.excludingTrailingTag(file(shortTag), new CancellationToken()));
        byte[] binaryTail = withTag(audio, 44);
        binaryTail[binaryTail.length - 1] = 1;
        assertNull(LocalFileReadView.excludingTrailingTag(file(binaryTail), new CancellationToken()));
        byte[] badYear = withTag(audio, 128);
        badYear[audio.length + 93] = 'X';
        assertNull(LocalFileReadView.excludingTrailingTag(file(badYear), new CancellationToken()));
        byte[] onlyTag = withTag(new byte[0], 128);
        assertNull(LocalFileReadView.excludingTrailingTag(file(onlyTag), new CancellationToken()));
    }
}
