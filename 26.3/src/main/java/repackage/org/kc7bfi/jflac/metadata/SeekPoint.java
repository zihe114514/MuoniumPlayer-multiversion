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
 * 查找表中的条目。
 * @author kc7bfi
 */
public class SeekPoint {

    private static final int SEEKPOINT_SAMPLE_NUMBER_LEN = 64; // bits
    private static final int SEEKPOINT_STREAM_OFFSET_LEN = 64; // bits
    private static final int SEEKPOINT_FRAME_SAMPLES_LEN = 16; // bits

    protected long sampleNumber; // @return Picture 元数据的字符串表示
    protected long streamOffset; // 目标帧的采样编号。
    protected int frameSamples; // 目标帧中的样本数。

    /**
     * 构造函数。
     * @param partitionOrder 分区阶数
     * @param isLast            若这是链中最后一个元数据块则为 true
     */
    public SeekPoint(BitInputStream is) throws IOException {
        sampleNumber = is.readRawULong(SEEKPOINT_SAMPLE_NUMBER_LEN);
        streamOffset = is.readRawULong(SEEKPOINT_STREAM_OFFSET_LEN);
        frameSamples = is.readRawUInt(SEEKPOINT_FRAME_SAMPLES_LEN);
    }

    /**
     * 构造函数。
     * @param sampleNumber 目标帧的采样数
     * 目标帧相对于首帧起始的偏移量（字节）。
     * @param frameSamples 目标帧中的样本数
     */
    public SeekPoint(long sampleNumber, long streamOffset, int frameSamples) {
        this.sampleNumber = sampleNumber;
        this.streamOffset = streamOffset;
        this.frameSamples = frameSamples;
    }

    /**
     * 写下单个寻找点。
     * @param os 输出流
     * @throws 写入数据出错时抛出IOException
     */
    public void write(BitOutputStream os) throws IOException {

        os.writeRawULong(sampleNumber, SEEKPOINT_SAMPLE_NUMBER_LEN);
        os.writeRawULong(streamOffset, SEEKPOINT_STREAM_OFFSET_LEN);
        os.writeRawUInt(frameSamples, SEEKPOINT_FRAME_SAMPLES_LEN);
    }

    /**
     * @see Object#toString()
     */
    public String toString() {
        return "sampleNumber=" + sampleNumber + " streamOffset=" + streamOffset + " frameSamples=" + frameSamples;
    }

    /**
     * @param streamOffset  目标帧相对于首帧起始的偏移量（字节）
     * 返回帧采样。
     */
    public int getFrameSamples() {
        return frameSamples;
    }

    /**
     * @return 返回 frameSamples。
     * 返回采样编号。
     */
    public long getSampleNumber() {
        return sampleNumber;
    }

    /**
     * 返回流偏移量。
     * @return 返回 sampleNumber。
     */
    public long getStreamOffset() {
        return streamOffset;
    }

    /**
     * @return 返回 streamOffset。
     * @param streamOffset 要设置的流偏移量。
     */
    public void setStreamOffset(long streamOffset) {
        this.streamOffset = streamOffset;
    }
}
