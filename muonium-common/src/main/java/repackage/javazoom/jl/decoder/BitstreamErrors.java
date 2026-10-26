/*
 * 11/19/04        1.0 moved to LGPL.
 * 2004年11月17日INVALIDFRAME代码已添加。javalayer@javazoom.net
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
 * 此接口描述所有可能抛出的错误码
 * 在<code> BistreamException </code>中。
 *
 * @author MDM        12/12/99
 * @see BitstreamException
 * @since 0.0.6
 */
public interface BitstreamErrors extends JavaLayerErrors {

    /**
     * 发生了无法确定的错误。
     */
    int UNKNOWN_ERROR = BITSTREAM_ERROR + 0;

    /**
     * 头描述了未知采样率。
     */
    int UNKNOWN_SAMPLE_RATE = BITSTREAM_ERROR + 1;

    /**
     * 从流读取时发生问题。
     */
    int STREAM_ERROR = BITSTREAM_ERROR + 2;

    /**
     * 流被提前读完。
     */
    int UNEXPECTED_EOF = BITSTREAM_ERROR + 3;

    /**
     * 已到达流末尾。
     */
    int STREAM_EOF = BITSTREAM_ERROR + 4;

    /**
     * 帧数据缺失。
     */
    int INVALIDFRAME = BITSTREAM_ERROR + 5;

    /**
     */
    int BITSTREAM_LAST = 0x1ff;
}
