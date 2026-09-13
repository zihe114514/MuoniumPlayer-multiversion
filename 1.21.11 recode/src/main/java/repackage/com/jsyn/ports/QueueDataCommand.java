/*
 * Copyright 2009 Phil Burk, Mobileer Inc
 *
 * 依据 Apache 许可证 2.0 版（下称“许可证”）授权；
 * 除非符合许可规定，否则您不得使用此文件。
 * 您可以通过以下网址获取许可证副本：
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * 除非适用法律要求或书面同意，在许可证
 * 根据许可证分发是“按原样”分发的，
 * 在任何情况下，无论明示或默示，不提供任何担保或条件。
 * 有关许可证项下的权限，请参见许可证；
 * 许可项下的限制。
 */

package repackage.com.jsyn.ports;

import repackage.com.jsyn.data.SequentialData;
import repackage.com.softsynth.shared.time.ScheduledCommand;

/**
 * 可用于将 SequentialData 入队到 UnitDataQueuePort 的命令。示例：
 * 使用此命令使用回调对数据进行排队。
 *
 * <pre>
 * <code>
 * 	//使用完成回调对信封进行排队。
 * 	QueueDataCommand command = envelopePlayer.dataQueue.createQueueDataCommand( envelope, 0,
 * 			envelope.getNumFrames());
 * 	//创建要在队列数据完成时调用的对象。
 * 	TestQueueCallback callback = new TestQueueCallback();
 * 	command.setCallback(callback);
 * 	command.setNumLoops(2);
 * 	envelopePlayer.rate.set( 0.2 );
 * 	synth.queueCommand(command);
 *  </code>
 * </pre>
 *
 * 回调将收到 QueueDataEvent。
 *
 * <pre>
 * <code>
 * 	testQueueCallback类实现UnitDataQueueCallback
 * 	{
 * 		public void已启动（ QueueDataEvent事件）
 * 		{
 * 			LOGGER.debug("回调：信封已启动。");
 * 		}
 *
 * 		public void循环（ QueueDataEvent事件）
 * 		{
 * 			LOGGER.debug("回调：信封已循环。");
 * 		}
 *
 * 		public void finished( QueueDataEvent event )
 * 		{
 * 			LOGGER.debug("CALLBACK: Envelope finished.");
 * 		}
 * 	}
 * </code>
 * </pre>
 *
 * @author Phil Burk 2009 Mobileer Inc
 */
public abstract class QueueDataCommand extends QueueDataEvent implements ScheduledCommand {

    protected SequentialDataCrossfade crossfadeData;
    protected SequentialData currentData;

    private static final long serialVersionUID = -1185274459972359536L;
    private UnitDataQueueCallback callback;

    public QueueDataCommand(UnitDataQueuePort port, SequentialData sequentialData, int startFrame,
            int numFrames) {
        super(port);

        if ((startFrame + numFrames) > sequentialData.getNumFrames()) {
            throw new IllegalArgumentException("tried to queue past end of data, " + (startFrame + numFrames));
        } else if (startFrame < 0) {
            throw new IllegalArgumentException("tried to queue before start of data, " + startFrame);
        }
        this.sequentialData = sequentialData;
        this.currentData = sequentialData;
        crossfadeData = new SequentialDataCrossfade();
        this.startFrame = startFrame;
        this.numFrames = numFrames;
    }

    @Override
    public abstract void run();

    /**
     * 如果为true ，则如果其他项目在其后排队，则将跳过此项目。此标志允许
     * 你排队很多小声音，而不会让队列很长。
     *
     * @param skipIfOthers
     */
    public void setSkipIfOthers(boolean skipIfOthers) {
        this.skipIfOthers = skipIfOthers;
    }

    /**
     * 如果为true ，则队列将被清除，此项目将立即启动。
     * 使用此标志比从应用程序中清除队列更好，因为可能会有一个
     * 在下一项可用之前的间隔。与
     * setCrossFadeIn().
     *
     * @param immediate
     */
    public void setImmediate(boolean immediate) {
        this.immediate = immediate;
    }

    public UnitDataQueueCallback getCallback() {
        return callback;
    }

    public void setCallback(UnitDataQueueCallback callback) {
        this.callback = callback;
    }

    public SequentialDataCrossfade getCrossfadeData() {
        return crossfadeData;
    }

    public void setCrossfadeData(SequentialDataCrossfade crossfadeData) {
        this.crossfadeData = crossfadeData;
    }

    public SequentialData getCurrentData() {
        return currentData;
    }

    public void setCurrentData(SequentialData currentData) {
        this.currentData = currentData;
    }

    /**
     * 此命令完成后，停止包含此端口的设备。
     *
     * @param autoStop
     */
    public void setAutoStop(boolean autoStop) {
        this.autoStop = autoStop;
    }

    /**
     * 设置第一次后应重复块的次数。例如，如果您设置
     * numLoops到零时，区块将仅播放一次。如果将numLoops设置为1 ，
     * 将播放两次。
     *
     * @param numLoops 回送次数
     */
    public void setNumLoops(int numLoops) {
        this.numLoops = numLoops;
    }

    /**
     * 从上一块交叉淡入到本块的帧数。可用于
     * 在进行突然转换时避免弹出。在结束之后必须有可用的帧
     * 交叉淡入所使用的前一块。交叉淡入是线性的。
     *
     * @param size
     */
    public void setCrossFadeIn(int size) {
        this.crossFadeIn = size;
    }

}
