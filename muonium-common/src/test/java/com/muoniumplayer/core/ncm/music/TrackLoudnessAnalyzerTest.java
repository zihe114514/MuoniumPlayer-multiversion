package com.muoniumplayer.core.ncm.music;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TrackLoudnessAnalyzerTest {

    @TempDir
    Path tempDirectory;

    @Test
    void scansWavStereoWithoutDownmixCancellation() throws IOException {
        Path monoPath = tempDirectory.resolve("mono.wav");
        Path stereoPath = tempDirectory.resolve("stereo.wav");
        writePcm16Wav(monoPath, 1);
        writePcm16Wav(stereoPath, 2);

        IntegratedLoudnessMeter.Measurement mono = TrackLoudnessAnalyzer.analyze(monoPath.toFile());
        IntegratedLoudnessMeter.Measurement stereo = TrackLoudnessAnalyzer.analyze(stereoPath.toFile());

        assertNotNull(mono);
        assertNotNull(stereo);
        assertEquals(10.0 * Math.log10(2.0), stereo.integratedLufs - mono.integratedLufs, .03);
    }

    private static void writePcm16Wav(Path path, int channels) throws IOException {
        int sampleRate = 48_000;
        int frames = sampleRate * 2;
        int dataBytes = frames * channels * 2;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(44 + dataBytes);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeBytes("RIFF");
            littleInt(out, 36 + dataBytes);
            out.writeBytes("WAVEfmt ");
            littleInt(out, 16);
            littleShort(out, 1);
            littleShort(out, channels);
            littleInt(out, sampleRate);
            littleInt(out, sampleRate * channels * 2);
            littleShort(out, channels * 2);
            littleShort(out, 16);
            out.writeBytes("data");
            littleInt(out, dataBytes);
            for (int frame = 0; frame < frames; frame++) {
                short sample = (short) Math.round(0.2 * Short.MAX_VALUE
                        * Math.sin(2.0 * Math.PI * 997.0 * frame / sampleRate));
                littleShort(out, sample);
                if (channels == 2) littleShort(out, sample);
            }
        }
        Files.write(path, bytes.toByteArray());
    }

    private static void littleShort(DataOutputStream out, int value) throws IOException {
        out.writeByte(value & 0xff);
        out.writeByte((value >>> 8) & 0xff);
    }

    private static void littleInt(DataOutputStream out, int value) throws IOException {
        out.writeByte(value & 0xff);
        out.writeByte((value >>> 8) & 0xff);
        out.writeByte((value >>> 16) & 0xff);
        out.writeByte((value >>> 24) & 0xff);
    }
}
