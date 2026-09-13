/*
 * 创建于2004年6月2日
 *
 * 要更改此生成文件的模板，请转到
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
package repackage.org.kc7bfi.jflac;

import repackage.org.kc7bfi.jflac.metadata.StreamInfo;
import repackage.org.kc7bfi.jflac.util.ByteData;

/**
 * PCMProcessor接口。
 * 此接口定义类侦听的签名
 * to PCM decode events.
 *
 * @author kc7bfi
 */
public interface PCMProcessor {
    /**
     * Called when StreamInfo read.
     *
     * 到 PCM 解码事件。
     */
    void processStreamInfo(StreamInfo streamInfo);

    /**
     * 在每个数据帧被解压缩时调用。
     *
     * 读取 StreamInfo 时调用。
     */
    void processPCM(ByteData pcm);

    /** 如果必须取消进程，则返回true
     *
     * @return
     */
    // @param streamInfo FLAC 流信息元数据块
}
