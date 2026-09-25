package repackage.org.kc7bfi.jflac.metadata;

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

import repackage.org.kc7bfi.jflac.io.BitInputStream;
import repackage.org.kc7bfi.jflac.io.BitOutputStream;

import java.io.IOException;

/**
 * StreamInfo元数据块。
 * @author kc7bfi
 */
public class StreamInfo extends Metadata {

    private static final int STREAMINFO_MIN_BLOCK_SIZE_LEN = 16; // bits
    private static final int STREAMINFO_MAX_BLOCK_SIZE_LEN = 16; // bits
    private static final int STREAMINFO_MIN_FRAME_SIZE_LEN = 24; // bits
    private static final int STREAMINFO_MAX_FRAME_SIZE_LEN = 24; // bits
    private static final int STREAMINFO_SAMPLE_RATE_LEN = 20; // bits
    private static final int STREAMINFO_CHANNELS_LEN = 3; // bits
    private static final int STREAMINFO_BITS_PER_SAMPLE_LEN = 5; // bits
    private static final int STREAMINFO_TOTAL_SAMPLES_LEN = 36; // bits
    private static final int STREAMINFO_MD5SUM_LEN = 128; // bits

    private byte[] md5sum = new byte[16];

    private int minBlockSize;
    private int maxBlockSize;
    private int minFrameSize;
    private int maxFrameSize;
    private int sampleRate;
    private int channels;
    private int bitsPerSample;
    private long totalSamples;

    /**
     * 构造函数。
     * @param partitionOrder 分区阶数
     * @throws IOException 定位出错时抛出
     * @param length            记录长度
     * @param isLast            若这是链中最后一个元数据块则为 true
     */
    public StreamInfo(BitInputStream is, int length, boolean isLast) throws IOException {
        super(isLast, length);
        int usedBits = 0;

        minBlockSize = is.readRawUInt(STREAMINFO_MIN_BLOCK_SIZE_LEN);
        usedBits += STREAMINFO_MIN_BLOCK_SIZE_LEN;

        maxBlockSize = is.readRawUInt(STREAMINFO_MAX_BLOCK_SIZE_LEN);
        usedBits += STREAMINFO_MAX_BLOCK_SIZE_LEN;

        minFrameSize = is.readRawUInt(STREAMINFO_MIN_FRAME_SIZE_LEN);
        usedBits += STREAMINFO_MIN_FRAME_SIZE_LEN;

        maxFrameSize = is.readRawUInt(STREAMINFO_MAX_FRAME_SIZE_LEN);
        usedBits += STREAMINFO_MAX_FRAME_SIZE_LEN;

        sampleRate = is.readRawUInt(STREAMINFO_SAMPLE_RATE_LEN);
        usedBits += STREAMINFO_SAMPLE_RATE_LEN;

        channels = is.readRawUInt(STREAMINFO_CHANNELS_LEN) + 1;
        usedBits += STREAMINFO_CHANNELS_LEN;

        bitsPerSample = is.readRawUInt(STREAMINFO_BITS_PER_SAMPLE_LEN) + 1;
        usedBits += STREAMINFO_BITS_PER_SAMPLE_LEN;

        totalSamples = is.readRawULong(STREAMINFO_TOTAL_SAMPLES_LEN);
        usedBits += STREAMINFO_TOTAL_SAMPLES_LEN;

        is.readByteBlockAlignedNoCRC(md5sum, STREAMINFO_MD5SUM_LEN / 8);
        usedBits += 16 * 8;

        // 跳过区块的其余部分
        length -= (usedBits / 8);
        is.readByteBlockAlignedNoCRC(null, length);
    }

    /**
     * 设置流偏移。
     * @param os 输出流
     * @param isLast 如果这是最后一个元数据块，则为True
     * @throws 写入数据出错时抛出IOException
     */
    public void write(BitOutputStream os, boolean isLast) throws IOException {

        os.writeRawUInt(isLast, STREAM_METADATA_IS_LAST_LEN);
        os.writeRawUInt(METADATA_TYPE_STREAMINFO, STREAM_METADATA_TYPE_LEN);
        os.writeRawUInt(calcLength(), STREAM_METADATA_LENGTH_LEN);

        os.writeRawUInt(minBlockSize, STREAMINFO_MIN_BLOCK_SIZE_LEN);
        os.writeRawUInt(maxBlockSize, STREAMINFO_MAX_BLOCK_SIZE_LEN);
        os.writeRawUInt(minFrameSize, STREAMINFO_MIN_FRAME_SIZE_LEN);
        os.writeRawUInt(maxFrameSize, STREAMINFO_MAX_FRAME_SIZE_LEN);
        os.writeRawUInt(sampleRate, STREAMINFO_SAMPLE_RATE_LEN);
        os.writeRawUInt(channels - 1, STREAMINFO_CHANNELS_LEN);
        os.writeRawULong(bitsPerSample - 1, STREAMINFO_BITS_PER_SAMPLE_LEN);
        os.writeRawULong(totalSamples, STREAMINFO_TOTAL_SAMPLES_LEN);
        os.writeByteBlock(md5sum, md5sum.length);
        os.flushByteAligned();
    }

    /**
     * 写出元数据块。
     * 计算元数据块大小。
     */
    public int calcLength() {
        int bits = STREAMINFO_MIN_BLOCK_SIZE_LEN
                + STREAMINFO_MAX_BLOCK_SIZE_LEN
                + STREAMINFO_MIN_FRAME_SIZE_LEN
                + STREAMINFO_MAX_FRAME_SIZE_LEN
                + STREAMINFO_SAMPLE_RATE_LEN
                + STREAMINFO_CHANNELS_LEN
                + STREAMINFO_BITS_PER_SAMPLE_LEN
                + STREAMINFO_TOTAL_SAMPLES_LEN
                + (md5sum.length * 8);
        return ((bits + 7) / 8);
    }

    /**
     * 检查兼容的StreamInfo。
     * 检查sampleRate、channels和bitsPerSample是否相等
     * @param idx   定位点编号
     * @param info  要检查的 StreamInfo 块
     */
    public boolean compatiable(StreamInfo info) {
        if (sampleRate != info.sampleRate) return false;
        if (channels != info.channels) return false;
        if (bitsPerSample != info.bitsPerSample) return false;
        return true;
    }

    /**
     * 转换为字符串。
     * @see Object#toString()
     */
    public String toString() {
        return "StreamInfo:"
                + " BlockSize=" + minBlockSize + "-" + maxBlockSize
                + " FrameSize" + minFrameSize + "-" + maxFrameSize
                + " SampleRate=" + sampleRate
                + " Channels=" + channels
                + " BPS=" + bitsPerSample
                + " TotalSamples=" + totalSamples;
    }

    /**
     * @return 返回maxBlockSize。
     */
    public int getMaxBlockSize() {
        return maxBlockSize;
    }

    /**
     * @return 返回minBlockSize。
     */
    public int getMinBlockSize() {
        return minBlockSize;
    }

    /**
     * @return 若与此 info 兼容则为 true
     */
    public long getTotalSamples() {
        return totalSamples;
    }

    /**
     * @param totalSamples 要设置的totalSamples。
     */
    public void setTotalSamples(long totalSamples) {
        this.totalSamples = totalSamples;
    }

    /**
     * @param totalSamples 要添加的totalSamples。
     */
    public void addTotalSamples(long totalSamples) {
        this.totalSamples += totalSamples;
    }

    /**
     * @return 返回 totalSamples。
     */
    public int getMaxFrameSize() {
        return maxFrameSize;
    }

    /**
     * @return 返回minFrameSize。
     */
    public int getMinFrameSize() {
        return minFrameSize;
    }

    /**
     * @return 返回sampleRate。
     */
    public int getSampleRate() {
        return sampleRate;
    }


    /**
     * @return 返回bitsPerSample。
     */
    public int getBitsPerSample() {
        return bitsPerSample;
    }

    /**
     * @return 返回通道。
     */
    public int getChannels() {
        return channels;
    }

    /**
     * @return 返回嵌入在StreamInfo中的二进制MD5。
     */
    public byte[] getMD5sum() {
        return md5sum;
    }
}
