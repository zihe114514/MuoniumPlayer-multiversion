/*
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
 * 抛出<code> BitstreamException </code>实例
 * 当<code>比特流</code>上的操作失败时。
 * <p>
 * 异常提供了异常条件的详细信息
 * 有两种方式：
 * <ol><li>
 *        作为描述错误性质的错误码
 * </li><br></br><li>
 *        作为抛出的<code>可抛出的</code>实例（如果有）
 *        指示发生了异常情况。
 * </li></ol></p>
 *
 * @author MDM    12/12/99
 * @since 0.0.6
 */
public class BitstreamException extends JavaLayerException implements BitstreamErrors {

    private int errorCode = UNKNOWN_ERROR;

    public BitstreamException(String msg, Throwable t) {
        super(msg, t);
    }

    public BitstreamException(int errorCode, Throwable t) {
        this(getErrorString(errorCode), t);
        this.errorCode = errorCode;
    }

    public int getErrorCode() {
        return errorCode;
    }

    static public String getErrorString(int errorcode) {
        // REVIEW：使用资源束把错误码映射
        // 到本地化字符串。

        return "Bitstream errorCode " + Integer.toHexString(errorcode);
    }
}
