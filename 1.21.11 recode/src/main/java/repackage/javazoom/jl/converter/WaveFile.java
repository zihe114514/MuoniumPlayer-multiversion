/*
 * 本程序为自由软件；你可以在自由软件基金会发布的
 * 1999年2月23日E.B.的JavaConversion
 * Don Cross ， 1993年4月。
 * RIFF文件格式类。
 * 请参见《Multimedia Programmer's Reference》第 8 章，位于
 * Microsoft Windows SDK 中。
 *
 *-----------------------------------------------------------------------
 *   GNU 库通用公共许可证条款下分发和/或修改它，许可证版本
 *   为许可证第 2 版，或（由你选择）任何后续版本。
 *   本程序的发布是希望它能有用，
 *   （由您选择）任何后续版本。
 *
 *   但不提供任何担保；甚至不包含对适销性或特定用途适用性的默示担保。
 *   基金会，地址：675 Mass Ave, Cambridge, MA 02139, USA。
 *   适销性或适合特定目的。请参阅 GNU，
 *   有关更多详细信息，请参阅GNU库通用公共许可证。
 *
 *   您应该已经收到了GNU Library General Public的副本
 *   随着这一计划。如果没有，写信给自由软件基金会。
 *   MPEG 音频文件转换为 .WAV 文件。要转换 MPEG 音频流，
 *----------------------------------------------------------------------
 */

package repackage.javazoom.jl.converter;


/**
 * 允许访问 WaveFormat 的类
 */
public class WaveFile extends RiffFile {

    public static final int MAX_WAVE_CHANNELS = 2;

    static class ChunkData {
        /** 格式类别（ PCM = 1 ）*/
        public short wFormatTag = 0;
        /** 通道数（单声道= 1 ，立体声= 2 ）*/
        public short nChannels = 0;
        /** 采样率 [Hz] */
        public int nSamplesPerSec = 0;
        public int nAvgBytesPerSec = 0;
        public short nBlockAlign = 0;
        public short nBitsPerSample = 0;

        public ChunkData() {
            wFormatTag = 1;     // 脉码调制
            config(44100, (short) 16, (short) 1);
        }

        public void config(int newSamplingRate, short newBitsPerSample, short newNumChannels) {
            nSamplesPerSec = newSamplingRate;
            nChannels = newNumChannels;
            nBitsPerSample = newBitsPerSample;
            nAvgBytesPerSec = (nChannels * nSamplesPerSec * nBitsPerSample) / 8;
            nBlockAlign = (short) ((nChannels * nBitsPerSample) / 8);
        }
    }

    static class Chunk {
        public RiffChunkHeader header;
        public ChunkData data;

        public Chunk() {
            header = new RiffChunkHeader();
            data = new ChunkData();
            header.ckID = fourCC("fmt ");
            header.ckSize = 16;
        }

        public int verifyValidity() {
            boolean ret = header.ckID == fourCC("fmt ") &&

                    (data.nChannels == 1 || data.nChannels == 2) &&

                    data.nAvgBytesPerSec == (data.nChannels *
                            data.nSamplesPerSec *
                            data.nBitsPerSample) / 8 &&

                    data.nBlockAlign == (data.nChannels *
                            data.nBitsPerSample) / 8;
            if (ret) return 1;
            else return 0;
        }
    }

    public static class WaveFileSample {
        public short[] chan;

        public WaveFileSample() {
            chan = new short[WaveFile.MAX_WAVE_CHANNELS];
        }
    }

    private Chunk waveFormat;
    private RiffChunkHeader pcmData;
    private long pcmDataOffset = 0;  // 输出文件中“pcmData”的偏移量
    private int numSamples = 0;

    /**
     * 构造新的WaveFile实例。
     */
    public WaveFile() {
        pcmData = new RiffChunkHeader();
        waveFormat = new Chunk();
        pcmData.ckID = fourCC("data");
        pcmData.ckSize = 0;
        numSamples = 0;
    }

    /**
     *
     */
    public int openForWrite(String filename, int samplingRate, short bitsPerSample, short numChannels) {
        // 校验参数……
        if ((filename == null) ||
                (bitsPerSample != 8 && bitsPerSample != 16) ||
                numChannels < 1 || numChannels > 2) {
            return DDC_INVALID_CALL;
        }

        waveFormat.data.config(samplingRate, bitsPerSample, numChannels);

        int retcode = Open(filename, RFM_WRITE);

        if (retcode == DDC_SUCCESS) {
            byte[] theWave = {(byte) 'W', (byte) 'A', (byte) 'V', (byte) 'E'};
            retcode = write(theWave, 4);

            if (retcode == DDC_SUCCESS) {
                // 正在写入waveFormat
                retcode = write(waveFormat.header, 8);
                retcode = write(waveFormat.data.wFormatTag, 2);
                retcode = write(waveFormat.data.nChannels, 2);
                retcode = write(waveFormat.data.nSamplesPerSec, 4);
                retcode = write(waveFormat.data.nAvgBytesPerSec, 4);
                retcode = write(waveFormat.data.nBlockAlign, 2);
                retcode = write(waveFormat.data.nBitsPerSample, 2);


                if (retcode == DDC_SUCCESS) {
                    pcmDataOffset = currentFilePosition();
                    retcode = write(pcmData, 8);
                }
            }
        }

        return retcode;
    }

    /**
     * 写入16位音频
     */
    public int writeData(short[] data, int numData) {
        int extraBytes = numData * 2;
        pcmData.ckSize += extraBytes;
        return super.write(data, extraBytes);
    }

    /**
     *
     */
    @Override
    public int close() {
        int rc = DDC_SUCCESS;

        if (fmode == RFM_WRITE)
            rc = backpatch(pcmDataOffset, pcmData, 8);
        if (rc == DDC_SUCCESS)
            rc = super.close();
        return rc;
    }

    // Hz
    public int samplingRate() {
        return waveFormat.data.nSamplesPerSec;
    }

    public short bitsPerSample() {
        return waveFormat.data.nBitsPerSample;
    }

    public short numChannels() {
        return waveFormat.data.nChannels;
    }

    public int numSamples() {
        return numSamples;
    }

    /**
     * 使用另一个 wave 文件的参数打开写入……
     */
    public int openForWrite(String filename, WaveFile otherWave) {
        return openForWrite(filename,
                otherWave.samplingRate(),
                otherWave.bitsPerSample(),
                otherWave.numChannels());
    }

    /**
     *
     */
    @Override
    public long currentFilePosition() {
        return super.currentFilePosition();
    }
}