package com.muoniumplayer.core.audio.ffmpeg;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** A seekable native file view excluding recognized trailing metadata, never a codec decoder. */
record LocalFileReadView(Path path, long endExclusive) {
    private static final int ID3V1_BYTES = 128;
    private static final int MIN_PADDED_TAG_BYTES = 24;

    /**
     * Recognizes complete ID3v1 text fields, or the legacy padding-only truncated TAG tail.
     * Other trailing bytes remain visible to FFmpeg and its strict decoding/error checks.
     * Called only after libavformat has identified the actual raw FLAC demuxer.
     */
    static LocalFileReadView excludingTrailingTag(Path path, CancellationToken cancellation) throws IOException {
        cancellation.throwIfCancelled();
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size <= MIN_PADDED_TAG_BYTES) return null;
            int count = (int) Math.min(size, ID3V1_BYTES);
            byte[] tail = new byte[count];
            ByteBuffer bytes = ByteBuffer.wrap(tail);
            long position = size - count;
            int emptyReads = 0;
            while (bytes.hasRemaining()) {
                cancellation.throwIfCancelled();
                int read = channel.read(bytes, position + bytes.position());
                if (read < 0) throw new EOFException("Audio file changed while reading trailing metadata");
                if (read == 0 && ++emptyReads > 3) throw new IOException("Cannot read audio trailing metadata");
                if (read > 0) emptyReads = 0;
            }
            if (channel.size() != size) throw new IOException("Audio file size changed while reading trailing metadata");
            cancellation.throwIfCancelled();
            if (size > ID3V1_BYTES && count == ID3V1_BYTES && marker(tail, 0) && completeTagFields(tail)) {
                return new LocalFileReadView(path, size - ID3V1_BYTES);
            }
            for (int offset = 0; offset <= count - MIN_PADDED_TAG_BYTES; offset++) {
                int length = count - offset;
                if (length < ID3V1_BYTES && marker(tail, offset) && paddingOnly(tail, offset + 3)) {
                    return position + offset > 0 ? new LocalFileReadView(path, position + offset) : null;
                }
            }
            return null;
        }
    }

    private static boolean marker(byte[] tail, int offset) {
        return tail[offset] == 'T' && tail[offset + 1] == 'A' && tail[offset + 2] == 'G';
    }
    private static boolean paddingOnly(byte[] tail, int offset) {
        for (int i = offset; i < tail.length; i++) if (tail[i] != 0 && tail[i] != ' ') return false;
        return true;
    }
    private static boolean completeTagFields(byte[] tail) {
        // 30-byte title/artist/album, four-byte year, 30-byte comment, one-byte genre.
        // The v1.1 comment terminator/track and the genre are allowed to be binary.
        for (int i = 3; i < 127; i++) {
            if (i == 126 && tail[125] == 0) continue;
            int value = tail[i] & 0xff;
            if (value != 0 && value < 0x20) return false;
            if (i >= 93 && i <= 96 && value != 0 && value != ' ' && (value < '0' || value > '9')) return false;
        }
        return true;
    }

    String location() {
        // FFmpeg 8.1.2 doc/protocols.texi and avio.c's subfile-only inline option parser.
        // Explicit file: keeps drive letters, spaces, commas and Unicode inside a file URL.
        return "subfile,,start,0,end," + endExclusive + ",,:file:" + path.toAbsolutePath();
    }
}
