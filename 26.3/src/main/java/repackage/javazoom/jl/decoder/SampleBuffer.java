/*
 * 2004年11月19日1.0移至LGPL。
 *
 * 12/12/99  Initial Version based on FileObuffer.    mdm@techie.com.
 *
 * FileObuffer：
 * 1999年2月15日E.B.转换Java ， javalayer@javazoom.net
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

package repackage.javazoom.jl.decoder;

/**
 * <code> SampleBuffer </code>类实现了输出缓冲区
 * 为固定大小的采样块提供存储。
 */
public class SampleBuffer extends Obuffer {

    private final short[] buffer;
    private final int[] bufferP;
    private final int channels;
    private final int frequency;

    /**
     * 构造函数
     */
    public SampleBuffer(int sample_frequency, int number_of_channels) {
        buffer = new short[OBUFFERSIZE];
        bufferP = new int[MAXCHANNELS];
        channels = number_of_channels;
        frequency = sample_frequency;

        for (int i = 0; i < number_of_channels; ++i)
            bufferP[i] = (short) i;
    }

    public int getChannelCount() {
        return this.channels;
    }

    public int getSampleFrequency() {
        return this.frequency;
    }

    public short[] getBuffer() {
        return this.buffer;
    }

    public int getBufferLength() {
        return bufferP[0];
    }

    /**
     * 获取16位PCM样本。
     */
    @Override
    public void append(int channel, short value) {
        buffer[bufferP[channel]] = value;
        bufferP[channel] += channels;
    }

    @Override
    public void appendSamples(int channel, float[] f) {
        int pos = bufferP[channel];

        short s;
        float fs;
        for (int i = 0; i < 32; ) {
            fs = f[i++];
            fs = (fs > 32767.0f ? 32767.0f
                    : (Math.max(fs, -32767.0f)));

            s = (short) fs;
            buffer[pos] = s;
            pos += channels;
        }

        bufferP[channel] = pos;
    }

    /**
     * 将样本写入文件（随机访问）。
     */
    @Override
    public void writeBuffer(int val) {
    }

    @Override
    public void close() {
    }

    /**
     *
     */
    @Override
    public void clearBuffer() {
        for (int i = 0; i < channels; ++i)
            bufferP[i] = (short) i;
    }

    /**
     *
     */
    @Override
    public void setStopFlag() {
    }
}
