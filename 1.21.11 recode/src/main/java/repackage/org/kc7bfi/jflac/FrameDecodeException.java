/*
 * Created on Jun 30, 2004
 *
 * 要更改此生成文件的模板，请转到
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
package repackage.org.kc7bfi.jflac;


/**
 * @author kc7bfi
 * <p>
 * To change the template for this generated type comment go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
public class FrameDecodeException extends Exception {

    /**
     * 制造商
     */
    public FrameDecodeException() {
        super();
    }

    /**
     * 制造商
     *
     * @param msg 错误消息
     */
    public FrameDecodeException(String msg) {
        super(msg);
    }

    /**
     * 制造商
     *
     * @param err 导致错误
     */
    public FrameDecodeException(Throwable err) {
        super(err);
    }

    /**
     * 制造商
     *
     * @param msg 错误信息
     * @param err 导致错误
     */
    public FrameDecodeException(String msg, Throwable err) {
        super(msg, err);
    }

}
