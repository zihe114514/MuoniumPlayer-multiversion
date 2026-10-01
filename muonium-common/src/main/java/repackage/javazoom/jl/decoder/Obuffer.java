/*
 * 2004年11月19日1.0移至LGPL。
 * 1999年12月12日为提高效率添加了appendSamples()方法。MDM。
 * 1999年2月15日， E.B的Java转换， ebsp@iname.com ， JavaLayer
 *
 *   输出缓冲区的声明，包含操作系统
 *   虚拟Obuffer的实现。可选例程
 *   启用由Jeff Tsay添加的搜索和停靠点。
 *
 *  @ (#) obuffer.h 1.8 ，最后编辑： 1994年6月15日16:51:56
 *  @ (#)版权所有(C) 1993, 1994 Tobias Bading(bading@cs.tu-berlin.de)
 *  @ (#)柏林理工大学
 *
 *  通过以下方式快速降采样的u-law输出的想法和第一次实现：
 *  Jim Boucher （ jboucher@flash.bu.edu ）
 *
 *  LinuxObuffer类编写者
 *  Louis P. Kruger(lpkruger@phoenix.princeton.edu)
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
 * 音频输出的基类。
 */
public abstract class Obuffer {

    /** 每帧最多 2 * 1152 个采样 */
    public static final int OBUFFERSIZE = 2 * 1152;
    /** 通道数*/
    public static final int MAXCHANNELS = 2;

    /**
     * 获取16位PCM样本。
     */
    public abstract void append(int channel, short value);

    /**
     * 接受32个新的PCM样品。
     */
    public void appendSamples(int channel, float[] f) {
        short s;
        for (int i = 0; i < 32; ) {
            s = clip(f[i++]);
            append(channel, s);
        }
    }

    /**
     * 将采样裁剪到 16 位
     */
    private short clip(float sample) {
        return ((sample > 32767.0f) ? 32767 :
                ((sample < -32768.0f) ? -32768 :
                        (short) sample));
    }

    /**
     * 将采样写入文件或直接写入音频硬件。
     */
    public abstract void writeBuffer(int val);

    public abstract void close();

    /**
     * 清除缓冲区中的所有数据（用于查找）。
     */
    public abstract void clearBuffer();

    /**
     * 通知缓冲区用户已停止流。
     */
    public abstract void setStopFlag();
}
