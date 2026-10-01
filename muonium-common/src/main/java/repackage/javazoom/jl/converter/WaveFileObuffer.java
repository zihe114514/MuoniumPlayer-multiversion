/*
 * 2004年11月19日1.0移至LGPL。
 *
 * 1999年12月12日0.0.7重命名类，其他构造函数参数
 *             以及更大的写缓冲区。mdm@techie.com。
 *
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

package repackage.javazoom.jl.converter;

import repackage.javazoom.jl.decoder.Obuffer;


/**
 * 通过将数据写入
 * rIFF波格式的文件。
 *
 * @since 0.0
 */
public class WaveFileObuffer extends Obuffer {

    private final short[] buffer;
    private final short[] bufferP;
    private final int channels;
    private final WaveFile outWave;

    /**
     * 创建新的WareFileObuffer实例。
     *
     * @param number_of_channels 音频数据的声道数
     *                           此缓冲区将收到。
     * @param freq 缓冲液中样品的采样频率。
     * @param fileName           写入数据的文件名。
     */
    public WaveFileObuffer(int number_of_channels, int freq, String fileName) {
        if (fileName == null)
            throw new NullPointerException("fileName");

        buffer = new short[OBUFFERSIZE];
        bufferP = new short[MAXCHANNELS];
        channels = number_of_channels;

        for (int i = 0; i < number_of_channels; ++i)
            bufferP[i] = (short) i;

        outWave = new WaveFile();

        int rc = outWave.openForWrite(fileName, freq, (short) 16, (short) channels);
    }

    /**
     * 获取16位PCM样本。
     */
    @Override
    public void append(int channel, short value) {
        buffer[bufferP[channel]] = value;
        bufferP[channel] += (short) channels;
    }

    /**
     * 将采样写入文件（随机访问）。
     */
    short[] myBuffer = new short[2];

    @Override
    public void writeBuffer(int val) {

        int rc = outWave.writeData(buffer, bufferP[0]);
        // 审阅：处理RiffFile错误。
        for (int i = 0; i < channels; ++i) bufferP[i] = (short) i;
    }

    @Override
    public void close() {
        outWave.close();
    }

    /**
     *
     */
    @Override
    public void clearBuffer() {
    }

    /**
     *
     */
    @Override
    public void setStopFlag() {
    }

//    /**
//     *创建标准输出缓冲区
//     */
//    public static Obuffer create_stdout_obuffer(MPEG_Args maplay_args) {
//        Obuffer thebuffer = null;
//        int mode = maplay_args.MPEGheader.mode();
//        int whichChannels = maplay_args.whichC;
//        if (mode == Header.single_channel || whichChannels != MPEG_Args.both)
//            thebuffer = new FileObuffer(1, maplay_args.outputFilename);
//        else thebuffer = new FileObuffer(2, maplay_args.outputFilename);
//        return (thebuffer);
//    }
}
