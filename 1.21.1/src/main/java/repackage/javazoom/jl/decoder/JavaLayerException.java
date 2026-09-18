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

import java.io.PrintStream;


/**
 * JavaLayerException 是所有 API 层异常的基类，
 * javaLayer引发的异常。为了促进转换和
 * 来自其他域的异常的常见处理，类
 * 可以将某些功能委托给包含的Throwable实例。
 * <p>
 *
 * @author MDM
 */
public class JavaLayerException extends Exception {

    private Throwable exception;

    public JavaLayerException() {
    }

    public JavaLayerException(String msg) {
        super(msg);
    }

    public JavaLayerException(String msg, Throwable t) {
        super(msg);
        exception = t;
    }

    public Throwable getException() {
        return exception;
    }

    @Override
    public void printStackTrace() {
        printStackTrace(System.err);
    }

    @Override
    public void printStackTrace(PrintStream ps) {
        if (this.exception == null) {
            super.printStackTrace(ps);
        } else {
            exception.printStackTrace();
        }
    }
}
