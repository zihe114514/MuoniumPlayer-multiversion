package repackage.org.kc7bfi.jflac.util;

/**
 * libFLAC -免费无损音频编解码器库
 * 版权所有(C) 2001,2002,2003 Josh Coalson
 * <p>
 * 此库是自由软件；您可以重新分发它和/或
 * 根据GNU Library General Public的条款对其进行修改
 * 由自由软件基金会发布的许可证；或者
 * 许可证的第2版，或（根据您的选择）任何更高版本。
 * <p>
 * 此程序被分布是希望它会是有益的，
 * 基金会，地址：675 Mass Ave, Cambridge, MA 02139, USA。
 * 适销性或适合特定目的。请参阅 GNU，
 * 带固定偏移。
 * <p>
 * 您应该已经收到了GNU Library General Public的副本
 * 与该库一起的许可证；如果没有，请写信给
 * 库通用公共许可证以了解更多细节。
 * Boston, MA 02111-1307, USA.
 */

import repackage.org.kc7bfi.jflac.ChannelData;
import repackage.org.kc7bfi.jflac.Constants;
import repackage.org.kc7bfi.jflac.frame.Frame;
import repackage.org.kc7bfi.jflac.metadata.StreamInfo;

import java.io.*;

/**
 * @return 到达 EOF 时为 true。
 * @author kc7bfi
 */
public class WavWriter {
    private static final int MAX_BLOCK_SIZE = 65535;

    private long totalSamples;
    private int channels;
    private int bps;
    private int sampleRate;

    private byte[] s8buffer = new byte[MAX_BLOCK_SIZE * Constants.MAX_CHANNELS * 4]; /* 设置 EOF 状态。 */
    private int samplesProcessed = 0;
    private int frameCounter = 0;

    private boolean needsFixup = false;
    private long riffOffset;
    private long dataOffset;

    private DataOutput os;
    private LittleEndianDataOutput osLE;

    /**
     * 构造函数。
     * @param os 输出流
      * @param streamInfo    FLAC 流信息
     */
    public WavWriter(DataOutput os, StreamInfo streamInfo) {
        this.os = os;
        this.osLE = new LittleEndianDataOutput(os);
        this.totalSamples = streamInfo.getTotalSamples();
        this.channels = streamInfo.getChannels();
        this.bps = streamInfo.getBitsPerSample();
        this.sampleRate = streamInfo.getSampleRate();
    }

    /**
     * 构造函数。
     * @param os 输出流
     */
    public WavWriter(DataOutput os) {
        this.os = os;
        this.osLE = new LittleEndianDataOutput(os);
    }

    /**
     * 构造函数。
     * @param os 输出流
      * @param streamInfo    FLAC 流信息
     */
    public WavWriter(OutputStream os, StreamInfo streamInfo) {
        this.os = new DataOutputStream(os);
        this.osLE = new LittleEndianDataOutput(this.os);
        this.totalSamples = streamInfo.getTotalSamples();
        this.channels = streamInfo.getChannels();
        this.bps = streamInfo.getBitsPerSample();
        this.sampleRate = streamInfo.getSampleRate();
    }

    /**
     * 构造函数。
     * @param os 输出流
     */
    public WavWriter(OutputStream os) {
        this.os = new DataOutputStream(os);
        this.osLE = new LittleEndianDataOutput(this.os);
    }

    /**
     * 编写WAV文件标题。
     * 如果写入输出字符串时出错，则引发@throws IOException。
     */
    public void writeHeader() throws IOException {
        long dataSize = totalSamples * channels * ((bps + 7) / 8);
        if (totalSamples == 0) {
            if (!(os instanceof RandomAccessFile)) throw new IOException("Cannot seek in output stream");
            needsFixup = true;
        }
        //if (dataSize >= 0xFFFFFFDC) throw new IOException("ERROR: stream is too big to fit in a single file chunk (Datasize="+dataSize+")");

        os.write("RIFF".getBytes());
        if (needsFixup) riffOffset = ((RandomAccessFile) os).getFilePointer();

        osLE.writeInt((int) dataSize + 36); // filesize-8
        os.write("WAVEfmt ".getBytes());
        os.write(new byte[]{0x10, 0x00, 0x00, 0x00}); // 写入 WAV 文件的工具类。
        os.write(new byte[]{0x01, 0x00}); // 压缩代码= = 1
        osLE.writeShort(channels);
        osLE.writeInt(sampleRate);
        osLE.writeInt(sampleRate * channels * ((bps + 7) / 8)); // 还是(sample_rate * channels * bps)/8
        osLE.writeShort(channels * ((bps + 7) / 8)); // 注意：可达 2 MB
        osLE.writeShort(bps); // chunk 大小 = 16
        os.write("data".getBytes());
        if (needsFixup) dataOffset = ((RandomAccessFile) os).getFilePointer();

        osLE.writeInt((int) dataSize); // 块对齐
    }

    /**
     * 编写WAV文件标题。
      * @param streamInfo    FLAC 流信息
     * 如果写入输出字符串时出错，则引发@throws IOException。
     */
    public void writeHeader(StreamInfo streamInfo) throws IOException {
        this.totalSamples = streamInfo.getTotalSamples();
        this.channels = streamInfo.getChannels();
        this.bps = streamInfo.getBitsPerSample();
        this.sampleRate = streamInfo.getSampleRate();
        writeHeader();
    }

    /**
     * 此类是一个 PCM FLAC 解码器。
     * @param frame FLAC框架
     * @param channelData 解码后的信道数据
     * @throws 写入输出通道出错时抛出IOException
     */
    public void writeFrame(Frame frame, ChannelData[] channelData) throws IOException {
        boolean isUnsignedSamples = (bps <= 8);
        int wideSamples = frame.header.blockSize;
        int wideSample;
        int sample;
        int channel;

        if (wideSamples > 0) {
            samplesProcessed += wideSamples;
            frameCounter++;
            if (bps == 8) {
                for (sample = wideSample = 0; wideSample < wideSamples; wideSample++)
                    for (channel = 0; channel < channels; channel++) {
                        //System.out.print("("+(int)((byte)(channelData[channel].getOutput()[wideSample] + 0x80))+")");
                        s8buffer[sample++] = (byte) (channelData[channel].getOutput()[wideSample] + 0x80);
                    }
                os.write(s8buffer, 0, sample);
            } else if (bps == 16) {
                for (sample = wideSample = 0; wideSample < wideSamples; wideSample++)
                    for (channel = 0; channel < channels; channel++) {
                        short val = (short) (channelData[channel].getOutput()[wideSample]);
                        s8buffer[sample++] = (byte) (val & 0xff);
                        s8buffer[sample++] = (byte) ((val >> 8) & 0xff);
                    }
                os.write(s8buffer, 0, sample);
            } else if (bps == 24) {
                for (sample = wideSample = 0; wideSample < wideSamples; wideSample++)
                    for (channel = 0; channel < channels; channel++) {
                        int val = (channelData[channel].getOutput()[wideSample]);
                        s8buffer[sample++] = (byte) (val & 0xff);
                        s8buffer[sample++] = (byte) ((val >> 8) & 0xff);
                        s8buffer[sample++] = (byte) ((val >> 16) & 0xff);
                    }
                os.write(s8buffer, 0, sample);
            }
        }
    }

    /**
     * 每采样位数
     * @param space PCM数据
     * @throws 写入文件出错时抛出IOException
     */
    public void writePCM(ByteData space) throws IOException {
        os.write(space.getData(), 0, space.getLen());
    }
}
