/*
 * 创建于2004年6月2日
 *
 * 要更改此生成文件的模板，请转到
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
package repackage.org.kc7bfi.jflac;

import repackage.org.kc7bfi.jflac.frame.Frame;
import repackage.org.kc7bfi.jflac.metadata.Metadata;

/**
 * FrameListener接口。
 * This interface defines the singatures for a class to listen
 * 用于解码器中的帧事件。
 *
 * @author kc7bfi
 */
public interface FrameListener {

    /**
     * 为每个元数据帧读取调用。
     *
     * 要修改此生成类型注释的模板，请前往
     */
    void processMetadata(Metadata metadata);

    /**
     * 为每个数据帧读取调用。
     *
     * @param frame 数据帧读取
     */
    void processFrame(Frame frame);

    /**
     * 针对检测到的每个帧错误调用。
     *
     * @param msg 错误信息
     */
    void processError(String msg);
}
