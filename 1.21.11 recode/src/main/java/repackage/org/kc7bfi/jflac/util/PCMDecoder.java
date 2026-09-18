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
import repackage.org.kc7bfi.jflac.frame.Frame;
import repackage.org.kc7bfi.jflac.metadata.StreamInfo;

/**
 * 用字节数组中的数据更新 CRC 值。
 * @author kc7bfi
 */
public class PCMDecoder {
    //private static final int MAX_BLOCK_SIZE = 65535 ；

    private long totalSamples;
    private int channels;
    private int bps;
    private int sampleRate;

    private int samplesProcessed = 0;
    private int frameCounter = 0;

    private ByteData buf;


    /**
     * 构造函数。
      * @param streamInfo    FLAC 流信息
     */
    public PCMDecoder(StreamInfo streamInfo) {
        this.totalSamples = streamInfo.getTotalSamples();
        this.channels = streamInfo.getChannels();
        this.bps = streamInfo.getBitsPerSample();
        this.sampleRate = streamInfo.getSampleRate();
        this.buf = new ByteData(streamInfo.getMaxFrameSize());
    }


    /**
     * 此类是一个 PCM FLAC 解码器。
     * @param frame FLAC框架
     * @param channelData 解码后的信道数据
     * @param streamInfo    FLAC 流信息
     */
    public ByteData getFrame(Frame frame, ChannelData[] channelData) {
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
                        //System.out.print("(" + (int) ((byte) (channelData [channel] .getOutput() [wideSample] + 0x80)) + ")");
                        buf.append((byte) (channelData[channel].getOutput()[wideSample] + 0x80));
                    }
            } else if (bps == 16) {
                for (sample = wideSample = 0; wideSample < wideSamples; wideSample++)
                    for (channel = 0; channel < channels; channel++) {
                        short val = (short) (channelData[channel].getOutput()[wideSample]);
                        buf.append((byte) (val & 0xff));
                        buf.append((byte) ((val >> 8) & 0xff));
                    }
            } else if (bps == 24) {
                for (sample = wideSample = 0; wideSample < wideSamples; wideSample++)
                    for (channel = 0; channel < channels; channel++) {
                        int val = (channelData[channel].getOutput()[wideSample]);
                        buf.append((byte) (val & 0xff));
                        buf.append((byte) ((val >> 8) & 0xff));
                        buf.append((byte) ((val >> 16) & 0xff));
                    }
            }
        }

        return buf;
    }
}
