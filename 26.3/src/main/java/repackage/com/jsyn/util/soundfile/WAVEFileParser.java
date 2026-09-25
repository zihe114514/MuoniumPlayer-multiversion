/*
 * Copyright 2009 Phil Burk, Mobileer Inc
 *
 * 依据 Apache 许可证 2.0 版（下称“许可证”）授权；
 * 除非符合许可规定，否则您不得使用此文件。
 * 您可以通过以下网址获取许可证副本：
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * 除非适用法律要求或书面同意，在许可证
 * 根据许可证分发是“按原样”分发的，
 * 在任何情况下，无论明示或默示，不提供任何担保或条件。
 * 有关许可证项下的权限，请参见许可证；
 * 许可项下的限制。
 */

package repackage.com.jsyn.util.soundfile;

import repackage.com.jsyn.data.FloatSample;
import repackage.com.jsyn.data.SampleMarker;
import repackage.com.jsyn.util.SampleLoader;

import java.io.EOFException;
import java.io.IOException;

public class WAVEFileParser extends AudioFileParser implements ChunkHandler {

    public static final short WAVE_FORMAT_PCM = 1;
    public static final short WAVE_FORMAT_IEEE_FLOAT = 3;
    public static final short WAVE_FORMAT_EXTENSIBLE = (short) 0xFFFE;

    public static final byte[] KSDATAFORMAT_SUBTYPE_IEEE_FLOAT = {
            3, 0, 0, 0, 0, 0, 16, 0, -128, 0, 0, -86, 0, 56, -101, 113
    };
    public static final byte[] KSDATAFORMAT_SUBTYPE_PCM = {
            1, 0, 0, 0, 0, 0, 16, 0, -128, 0, 0, -86, 0, 56, -101, 113
    };

    public static final int WAVE_ID = ('W' << 24) | ('A' << 16) | ('V' << 8) | 'E';
    public static final int FMT_ID = ('f' << 24) | ('m' << 16) | ('t' << 8) | ' ';
    public static final int DATA_ID = ('d' << 24) | ('a' << 16) | ('t' << 8) | 'a';
    public static final int CUE_ID = ('c' << 24) | ('u' << 16) | ('e' << 8) | ' ';
    public static final int FACT_ID = ('f' << 24) | ('a' << 16) | ('c' << 8) | 't';
    public static final int SMPL_ID = ('s' << 24) | ('m' << 16) | ('p' << 8) | 'l';
    public static final int LTXT_ID = ('l' << 24) | ('t' << 16) | ('x' << 8) | 't';
    public static final int LABL_ID = ('l' << 24) | ('a' << 16) | ('b' << 8) | 'l';

    public int samplesPerBlock = 0;
    public int blockAlign = 0;
    public int numFactSamples = 0;
    public short format;

    public WAVEFileParser() {
    }

    @Override
    public FloatSample finish() throws IOException {
        if ((byteData == null)) {
            throw new IOException("No data found in audio sample.");
        }
        float[] floatData = new float[numFrames * samplesPerFrame];
        if (bitsPerSample == 16) {
            SampleLoader.decodeLittleI16ToF32(byteData, 0, byteData.length, floatData, 0);
        } else if (bitsPerSample == 24) {
            SampleLoader.decodeLittleI24ToF32(byteData, 0, byteData.length, floatData, 0);
        } else if (bitsPerSample == 32) {
            if (format == WAVE_FORMAT_IEEE_FLOAT) {
                SampleLoader.decodeLittleF32ToF32(byteData, 0, byteData.length, floatData, 0);
            } else if (format == WAVE_FORMAT_PCM) {
                SampleLoader.decodeLittleI32ToF32(byteData, 0, byteData.length, floatData, 0);
            } else {
                throw new IOException("WAV: Unsupported format = " + format);
            }
        } else {
            throw new IOException("WAV: Unsupported bitsPerSample = " + bitsPerSample);
        }

        return makeSample(floatData);
    }

    // typedef struct {
    // long dwIdentifier;
    // long dwPosition;
    // ID fccChunk;
    // long dwChunkStart;
    // long dwBlockStart;
    // long dwSampleOffset;
    // } CuePoint;

    /* 解析WAV文件中遇到的各种块。*/
    public void parseCueChunk(IFFParser parser, int ckSize) throws IOException {
        int numCuePoints = parser.readIntLittle();
        if ((ckSize - 4) != (6 * 4 * numCuePoints))
            throw new EOFException("Cue chunk too short!");
        for (int i = 0; i < numCuePoints; i++) {
            int dwName = parser.readIntLittle(); /* dwName */
            int position = parser.readIntLittle(); // dwPosition
            parser.skip(3 * 4); // fccChunk, dwChunkStart, dwBlockStart
            int sampleOffset = parser.readIntLittle(); // dwPosition
            SampleMarker cuePoint = findOrCreateCuePoint(dwName);
            cuePoint.position = position;
        }
    }

    public void parseLablChunk(IFFParser parser, int ckSize) throws IOException {
        int dwName = parser.readIntLittle();
        int textLength = (ckSize - 4) - 1; // 不读取NUL终止符
        String text = parseString(parser, textLength);
        SampleMarker cuePoint = findOrCreateCuePoint(dwName);
        cuePoint.name = text;
    }

    public void parseLtxtChunk(IFFParser parser, int ckSize) throws IOException {
        int dwName = parser.readIntLittle();
        int dwSampleLength = parser.readIntLittle();
        parser.skip(4 + (4 * 2)); // 通过代码页达到目的
        int textLength = (ckSize - ((4 * 4) + (4 * 2))) - 1; // 不要读取 NUL
                                                             // 终止符
        if (textLength > 0) {
            String text = parseString(parser, textLength);
            SampleMarker cuePoint = findOrCreateCuePoint(dwName);
            cuePoint.comment = text;
        }
    }

    void parseFmtChunk(IFFParser parser, int ckSize) throws IOException {
        format = parser.readShortLittle();
        samplesPerFrame = parser.readShortLittle();
        frameRate = parser.readIntLittle();
        parser.readIntLittle(); /* skip dwAvgBytesPerSec */
        blockAlign = parser.readShortLittle();
        bitsPerSample = parser.readShortLittle();
        bytesPerFrame = blockAlign;
        bytesPerSample = bytesPerFrame / samplesPerFrame;
        samplesPerBlock = (8 * blockAlign) / bitsPerSample;

        if (format == WAVE_FORMAT_EXTENSIBLE) {
            int extraSize = parser.readShortLittle();
            short validBitsPerSample = parser.readShortLittle();
            int channelMask = parser.readIntLittle();
            byte[] guid = new byte[16];
            parser.read(guid);
            if (matchBytes(guid, KSDATAFORMAT_SUBTYPE_IEEE_FLOAT)) {
                format = WAVE_FORMAT_IEEE_FLOAT;
            } else if (matchBytes(guid, KSDATAFORMAT_SUBTYPE_PCM)) {
                format = WAVE_FORMAT_PCM;
            }
        }
        if ((format != WAVE_FORMAT_PCM) && (format != WAVE_FORMAT_IEEE_FLOAT)) {
            throw new IOException(
                    "Only WAVE_FORMAT_PCM and WAVE_FORMAT_IEEE_FLOAT supported. format = " + format);
        }
        if ((bitsPerSample != 16) && (bitsPerSample != 24) && (bitsPerSample != 32)) {
            throw new IOException(
                    "Only 16 and 24 bit PCM or 32-bit float WAV files supported. width = "
                            + bitsPerSample);
        }
    }

    protected boolean matchBytes(byte[] bar1, byte[] bar2) {
        if (bar1.length != bar2.length)
            return false;
        for (int i = 0; i < bar1.length; i++) {
            if (bar1[i] != bar2[i])
                return false;
        }
        return true;
    }

    private int convertByteToFrame(int byteOffset) throws IOException {
        if (blockAlign == 0) {
            throw new IOException("WAV file has bytesPerBlock = zero");
        }
        if (samplesPerFrame == 0) {
            throw new IOException("WAV file has samplesPerFrame = zero");
        }
        return (samplesPerBlock * byteOffset) / (samplesPerFrame * blockAlign);
    }

    private int calculateNumFrames(int numBytes) throws IOException {
        int nFrames;
        if (numFactSamples > 0) {
            // nFrames = numFactSamples / samplesPerFrame;
            nFrames = numFactSamples; // FIXME 右
        } else {
            nFrames = convertByteToFrame(numBytes);
        }
        return nFrames;
    }

    // 读取 0 到 0xFFFFFFFF 范围内的小数，并
    // 转换到 0.0 到 1.0 范围。
    private double readFraction(IFFParser parser) throws IOException {
        // 将L放在末尾，否则我们得到-1。
        long maxFraction = 0x0FFFFFFFFL;
        // 获取无符号小数。必须能放进 long。
        long fraction = (parser.readIntLittle()) & maxFraction;
        return (double) fraction / (double) maxFraction;
    }

    public void parseSmplChunk(IFFParser parser, int ckSize) throws IOException {
        parser.readIntLittle(); // 制造商
        parser.readIntLittle(); // 产品
        parser.readIntLittle(); // 样品周期
        int unityNote = parser.readIntLittle();
        double pitchFraction = readFraction(parser);
        originalPitch = unityNote + pitchFraction;

        parser.readIntLittle(); // SMPTE 格式
        parser.readIntLittle(); // SMPTE偏移
        int numLoops = parser.readIntLittle();
        parser.readIntLittle(); // 采样器数据

        int lastCueID = Integer.MAX_VALUE;
        for (int i = 0; i < numLoops; i++) {
            int cueID = parser.readIntLittle();
            parser.readIntLittle(); // type
            int loopStartPosition = parser.readIntLittle();
            // 指向后面的样品。
            int loopEndPosition = parser.readIntLittle() + 1;
            // TODO 处理小数循环大小？
            double endFraction = readFraction(parser);
            parser.readIntLittle(); // playCount

            // 使用编号最小的 cue。
            if (cueID < lastCueID) {
                sustainBegin = loopStartPosition;
                sustainEnd = loopEndPosition;
            }
        }
    }

    public void parseFactChunk(IFFParser parser, int ckSize) throws IOException {
        numFactSamples = parser.readIntLittle();
    }

    public void parseDataChunk(IFFParser parser, int ckSize) throws IOException {
        long numRead;
        dataPosition = parser.getOffset();
        if (ifLoadData) {
            byteData = new byte[ckSize];
            numRead = parser.read(byteData);
        } else {
            numRead = parser.skip(ckSize);
        }
        if (numRead != ckSize) {
            throw new EOFException("WAV data chunk too short! Read " + numRead + " instead of "
                    + ckSize);
        }
        numFrames = calculateNumFrames(ckSize);
    }

    @Override
    public void handleForm(IFFParser parser, int ckID, int ckSize, int type) throws IOException {
        if ((ckID == IFFParser.RIFF_ID) && (type != WAVE_ID))
            throw new IOException("Bad WAV form type = " + IFFParser.IDToString(type));
    }

    /**
     * 由 parse() 方法调用，以 WAV 特有的方式处理块。
     *
     * @param ckID 四个字节的块ID ，如“data”
     * @param ckSize 块大小，单位字节
     * @return 块中剩余的字节数
     */
    @Override
    public void handleChunk(IFFParser parser, int ckID, int ckSize) throws IOException {
        switch (ckID) {
            case FMT_ID:
                parseFmtChunk(parser, ckSize);
                break;
            case DATA_ID:
                parseDataChunk(parser, ckSize);
                break;
            case CUE_ID:
                parseCueChunk(parser, ckSize);
                break;
            case FACT_ID:
                parseFactChunk(parser, ckSize);
                break;
            case SMPL_ID:
                parseSmplChunk(parser, ckSize);
                break;
            case LABL_ID:
                parseLablChunk(parser, ckSize);
                break;
            case LTXT_ID:
                parseLtxtChunk(parser, ckSize);
                break;
            default:
                break;
        }
    }

    /*
     * （非Javadoc ）
     * @see com.softsynth.javasonics.util.AudioSampleLoader#isLittleEndian()
     */
    boolean isLittleEndian() {
        return true;
    }

}
