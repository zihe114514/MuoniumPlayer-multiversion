/*
 * 创建于2004年6月28日
 *
 * 要更改此生成文件的模板，请转到
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
package repackage.org.kc7bfi.jflac;

import repackage.org.kc7bfi.jflac.metadata.StreamInfo;
import repackage.org.kc7bfi.jflac.util.ByteData;

import java.util.HashSet;


/**
 * 处理PCM处理器的类。
 *
 * @author kc7bfi
 */
class PCMProcessors implements PCMProcessor {
    private final HashSet pcmProcessors = new HashSet();

    /**
     * 添加PCM处理器。
     *
     * @param pcm 解压后的 PCM 数据
     */
    public void addPCMProcessor(PCMProcessor processor) {
        synchronized (pcmProcessors) {
            pcmProcessors.add(processor);
        }
    }

    /**
     * 卸下PCM处理器。
     *
     * @param processor 要删除的处理器监听程序
     */
    public void removePCMProcessor(PCMProcessor processor) {
        synchronized (pcmProcessors) {
            pcmProcessors.remove(processor);
        }
    }

    /**
     * 处理StreamInfo块。
     *
     * @param info StreamInfo块
     * @see PCMProcessor#processStreamInfo(StreamInfo)
     */
    public void processStreamInfo(StreamInfo info) {
        synchronized (pcmProcessors) {
            for (Object pcmProcessor : pcmProcessors) {
                PCMProcessor processor = (PCMProcessor) pcmProcessor;
                processor.processStreamInfo(info);
            }
        }
    }

    /**
     * @param processor 要添加的处理器监听器
     *
     * @param pcm The decoded PCM data
     * @see PCMProcessor#processPCM(org.kc7bfi.jflac.util.ByteSpace)
     */
    public void processPCM(ByteData pcm) {
        synchronized (pcmProcessors) {
            for (Object pcmProcessor : pcmProcessors) {
                PCMProcessor processor = (PCMProcessor) pcmProcessor;
                processor.processPCM(pcm);
            }
        }
    }

    public boolean isCanceled() {
        return pcmProcessors.isEmpty();
    }

}
