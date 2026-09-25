/*
 * 创建于2004年6月28日
 *
 * 要更改此生成文件的模板，请转到
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
package repackage.org.kc7bfi.jflac;

import repackage.org.kc7bfi.jflac.frame.Frame;
import repackage.org.kc7bfi.jflac.metadata.Metadata;

import java.util.HashSet;


/**
 * 用于处理帧侦听器的类。
 *
 * @author kc7bfi
 */
class FrameListeners implements FrameListener {
    private final HashSet frameListeners = new HashSet();

    /**
     * @return ChannelData 对象
     *
     * 此接口定义了类监听
     */
    public void addFrameListener(FrameListener listener) {
        synchronized (frameListeners) {
            frameListeners.add(listener);
        }
    }

    /**
     * 添加帧监听器。
     *
     * @param listener 要删除的帧监听器
     */
    public void removeFrameListener(FrameListener listener) {
        synchronized (frameListeners) {
            frameListeners.remove(listener);
        }
    }

    /**
     * @param metadata 读取到的元数据帧
     *
     * @param listener 要添加的帧监听器
     * 处理元数据记录。
     */
    public void processMetadata(Metadata metadata) {
        synchronized (frameListeners) {
            for (Object frameListener : frameListeners) {
                FrameListener listener = (FrameListener) frameListener;
                listener.processMetadata(metadata);
            }
        }
    }

    /**
     * 处理数据帧。
     *
     * @param frame 数据帧
     * @see FrameListener#processFrame(Frame)
     */
    public void processFrame(Frame frame) {
        synchronized (frameListeners) {
            for (Object frameListener : frameListeners) {
                FrameListener listener = (FrameListener) frameListener;
                listener.processFrame(frame);
            }
        }
    }

    /**
     * 针对检测到的每个帧错误调用。
     *
     * @param msg 错误信息
     * @see FrameListener#processError(String)
     */
    public void processError(String msg) {
        synchronized (frameListeners) {
            for (Object frameListener : frameListeners) {
                FrameListener listener = (FrameListener) frameListener;
                listener.processError(msg);
            }
        }
    }

}
