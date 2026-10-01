/*
 * 09/26/08抛出子带分配异常错误： Christopher G. Jennings(cjennings@acm.org)
 * 11/19/04        1.0 moved to LGPL.
 * 1999年12月12日初始版本。mdm@techie.com
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
 * FrameDecoder的实现负责解码
 * MPEG 音频帧。
 * <p>
 * 评审：目前接口太薄，应该有
 * 方法来指定输出缓冲区，合成过滤器和
 * 可能是解码器使用的其他对象。
 */
public interface FrameDecoder {

    /**
     * 解码一帧MPEG音频。
     */
    void decodeFrame() throws DecoderException;
}
