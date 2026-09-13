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
import repackage.com.jsyn.engine.SynthesisEngine;
import repackage.com.jsyn.exceptions.ChannelMismatchException;
import repackage.com.softsynth.shared.time.ScheduledCommand;
import repackage.com.softsynth.shared.time.TimeStamp;

import java.util.LinkedList;

/**
 * 用于 SequentialData、采样或包络的队列
 *
 * @author Phil Burk (C) 2009 Mobileer Inc
 */
public class UnitDataQueuePort extends UnitPort {
    private final LinkedList<QueuedBlock> blocks = new LinkedList<>();
    private QueueDataCommand currentBlock;
    private int frameIndex;
    private int numChannels = 1;
    private double normalizedRate;
    private long framesMoved;
    private boolean autoStopPending;
    private boolean targetValid;
    private QueueDataCommand finishingBlock;
    private QueueDataCommand loopingBlock;
    public static final int LOOP_IF_LAST = -1;

    public UnitDataQueuePort(String name) {
        super(name);
    }

    /** 保持对样品部分的引用。*/
    @SuppressWarnings("serial")
    private class QueuedBlock extends QueueDataCommand {

        public QueuedBlock(SequentialData queueableData, int startFrame, int numFrames) {
            super(UnitDataQueuePort.this, queueableData, startFrame, numFrames);
        }

        @Override
        public void run() {
            synchronized (blocks) {
                // 若最后一块可跳过则将其移除。
                if (!blocks.isEmpty()) {
                    QueueDataEvent lastBlock = blocks.getLast();
                    if (lastBlock.isSkipIfOthers()) {
                        blocks.removeLast();
                    }
                }

                // 若正在交叉淡入，则确定从何处开始交叉淡入
                // 开始。
                if (getCrossFadeIn() > 0) {
                    if (isImmediate()) {
                        // 队列将被清除，因此从当前淡入。
                        if (currentBlock != null) {
                            setupCrossFade(currentBlock, frameIndex, this);
                        }
                        // 否则没有任何在播放，故不做交叉淡入。
                    } else {
                        QueueDataCommand endBlock = getEndBlock();
                        if (endBlock != null) {
                            setupCrossFade(endBlock,
                                    endBlock.getStartFrame() + endBlock.getNumFrames(), this);
                        }
                    }
                }

                if (isImmediate()) {
                    clearQueue();
                }

                if (!this.shouldStop) {
                    blocks.add(this);
                }
            }
        }
    }

    // FIXME 在块之间的任何切换或回环时确定交叉淡入。

    protected void setupCrossFade(QueueDataCommand sourceCommand, int sourceStartIndex,
            QueueDataCommand targetCommand) {
        int crossFrames = targetCommand.getCrossFadeIn();
        SequentialData sourceData = sourceCommand.getCurrentData();
        SequentialData targetData = targetCommand.getCurrentData();
        int remainingSource = sourceData.getNumFrames() - sourceStartIndex;
        // 裁剪到源的末尾
        if (crossFrames > remainingSource)
            crossFrames = remainingSource;
        if (crossFrames > 0) {
            // SequentialDataCrossfade应继续到目标的末尾
            // 这样我们就可以从它交叉淡入淡出到目标。
            int remainingTarget = targetData.getNumFrames() - targetCommand.getStartFrame();
            targetCommand.crossfadeData.setup(sourceData, sourceStartIndex, crossFrames,
                    targetData, targetCommand.getStartFrame(), remainingTarget);
            targetCommand.currentData = targetCommand.crossfadeData;
            targetCommand.startFrame = 0;
        }
    }

    public QueueDataCommand createQueueDataCommand(SequentialData queueableData) {
        return createQueueDataCommand(queueableData, 0, queueableData.getNumFrames());
    }

    public QueueDataCommand createQueueDataCommand(SequentialData queueableData, int startFrame,
            int numFrames) {
        if (queueableData.getChannelsPerFrame() != UnitDataQueuePort.this.numChannels) {
            throw new ChannelMismatchException("Tried to queue "
                    + queueableData.getChannelsPerFrame() + " channel data to a " + numChannels
                    + " channel port.");
        }
        return new QueuedBlock(queueableData, startFrame, numFrames);
    }

    public QueueDataCommand getEndBlock() {
        if (!blocks.isEmpty()) {
            return blocks.getLast();
        } else if (currentBlock != null) {
            return currentBlock;
        } else {
            return null;
        }
    }

    public void setCurrentBlock(QueueDataCommand currentBlock) {
        this.currentBlock = currentBlock;
    }

    public void firePendingCallbacks() {
        if (loopingBlock != null) {
            if (loopingBlock.getCallback() != null) {
                loopingBlock.getCallback().looped(currentBlock);
            }
            loopingBlock = null;
        }
        if (finishingBlock != null) {
            if (finishingBlock.getCallback() != null) {
                try {
                    finishingBlock.getCallback().finished(currentBlock); // FIXME 这里是否应传递
                    // finishingBlock?!
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            finishingBlock = null;
        }
    }

    public boolean hasMore() {
        return (currentBlock != null) || (!blocks.isEmpty());
    }

    private void checkBlock() {
        if (currentBlock == null) {
            synchronized (blocks) {
                setCurrentBlock(blocks.remove());
                frameIndex = currentBlock.getStartFrame();
                currentBlock.loopsLeft = currentBlock.getNumLoops();
                if (currentBlock.getCallback() != null) {
                    currentBlock.getCallback().started(currentBlock);
                }
            }
        }
    }

    private void advanceFrameIndex() {
        frameIndex += 1;
        framesMoved += 1;
        // 我们完成这个街区了吗？
        if (frameIndex >= (currentBlock.getStartFrame() + currentBlock.getNumFrames())) {
            // 我们是否应该根据计数器循环此块？
            if (currentBlock.loopsLeft > 0) {
                currentBlock.loopsLeft -= 1;
                loopToStart();
            }
            // 我们应该在这个街区永远循环吗？
            else if ((blocks.isEmpty()) && (currentBlock.loopsLeft < 0)) {
                loopToStart();
            }
            // 我們完了!
            else {
                if (currentBlock.isAutoStop()) {
                    autoStopPending = true;
                }
                finishingBlock = currentBlock;
                setCurrentBlock(null);
                // LOGGER.debug("advanceFrameIndex: currentBlock set null");
            }
        }
    }

    private void loopToStart() {
        if (currentBlock.getCrossFadeIn() > 0) {
            setupCrossFade(currentBlock, frameIndex, currentBlock);
        }
        frameIndex = currentBlock.getStartFrame();
        loopingBlock = currentBlock;
    }

    public double getNormalizedRate() {
        return normalizedRate;
    }

    public double readCurrentChannelDouble(int channelIndex) {
        return currentBlock.currentData.readDouble((frameIndex * numChannels) + channelIndex);
    }

    public void writeCurrentChannelDouble(int channelIndex, double value) {
        currentBlock.currentData.writeDouble((frameIndex * numChannels) + channelIndex, value);
    }

    public void beginFrame(double synthesisPeriod) {
        checkBlock();
        normalizedRate = currentBlock.currentData.getRateScaler(frameIndex, synthesisPeriod);
    }

    public void endFrame() {
        advanceFrameIndex();
        targetValid = true;
    }

    public double readNextMonoDouble(double synthesisPeriod) {
        beginFrame(synthesisPeriod);
        double value = currentBlock.currentData.readDouble(frameIndex);
        endFrame();
        return value;
    }

    /** 直接写入端口队列。这仅由单元测试调用！*/
    protected void addQueuedBlock(QueueDataEvent block) {
        blocks.add((QueuedBlock) block);
    }

    /** 清空队列。仅供内部使用。 */
    protected void clearQueue() {
        synchronized (blocks) {
            blocks.clear();
            setCurrentBlock(null);
            targetValid = false;
            autoStopPending = false;
        }
    }

    class ClearQueueCommand implements ScheduledCommand {
        @Override
        public void run() {
            clearQueue();
        }
    }

    /** 在将来将数据排队到端口。*/
    public void queue(SequentialData queueableData, int startFrame, int numFrames,
            TimeStamp timeStamp) {
        QueueDataCommand command = createQueueDataCommand(queueableData, startFrame, numFrames);
        scheduleCommand(timeStamp, command);
    }

    /**
     * 在未来某时刻将数据入队到端口。命令执行前会先清空队列。
     */
    public void queueImmediate(SequentialData queueableData, int startFrame, int numFrames,
            TimeStamp timeStamp) {
        QueueDataCommand command = createQueueDataCommand(queueableData, startFrame, numFrames);
        command.setImmediate(true);
        scheduleCommand(timeStamp, command);
    }

    /** 在将来将数据排队到端口。*/
    public void queueLoop(SequentialData queueableData, int startFrame, int numFrames,
            TimeStamp timeStamp) {
        queueLoop(queueableData, startFrame, numFrames, LOOP_IF_LAST, timeStamp);
    }

    /**
     * 在将来使用指定数量的循环将数据排队到端口。
     */
    public void queueLoop(SequentialData queueableData, int startFrame, int numFrames,
            int numLoops, TimeStamp timeStamp) {
        QueueDataCommand command = createQueueDataCommand(queueableData, startFrame, numFrames);
        command.setNumLoops(numLoops);
        scheduleCommand(timeStamp, command);
    }

    public QueueDataCommand queueLoopAutoUpdateFrames(SequentialData queueableData) {
        QueueDataCommand command = createQueueDataCommand(queueableData, 0, queueableData.getNumFrames());
        command.setNumLoops(LOOP_IF_LAST);
        queueCommand(command);
        return command;
    }

    /** 将整个数据对象入队以循环播放。 */
    public void queueLoop(SequentialData queueableData) {
        queueLoop(queueableData, 0, queueableData.getNumFrames());
    }

    /** 将数据排队到端口以便立即使用。*/
    public void queueLoop(SequentialData queueableData, int startFrame, int numFrames) {
        queueLoop(queueableData, startFrame, numFrames, LOOP_IF_LAST);
    }

    /**
     * 将数据排队到端口，以便立即使用指定数量的循环。
     */
    public void queueLoop(SequentialData queueableData, int startFrame, int numFrames, int numLoops) {
        QueueDataCommand command = createQueueDataCommand(queueableData, startFrame, numFrames);
        command.setNumLoops(numLoops);
        queueCommand(command);
    }

    /**
     * 在将来将数据排队到端口。当此块为时，请求设备停止
     * 已完成。
     */
    public void queueStop(SequentialData queueableData, int startFrame, int numFrames,
            TimeStamp timeStamp) {
        QueueDataCommand command = createQueueDataCommand(queueableData, startFrame, numFrames);
        command.setAutoStop(true);
        scheduleCommand(timeStamp, command);
//        queueCommand(command);
    }

    /** 尽快通过命令队列将数据入队到端口。 */
    public void queue(SequentialData queueableData, int startFrame, int numFrames) {
        QueueDataCommand command = createQueueDataCommand(queueableData, startFrame, numFrames);
        queueCommand(command);
    }

    public void queue(SequentialData queueableData, int startFrame, int numFrames, UnitDataQueueCallback callback) {
        QueueDataCommand command = createQueueDataCommand(queueableData, startFrame, numFrames);
        command.setCallback(callback);
        queueCommand(command);
    }

    /**
     * 在没有选项的情况下对整个数据量进行排队。
     *
     * @param queueableData
     */
    public void queue(SequentialData queueableData) {
        queue(queueableData, 0, queueableData.getNumFrames());
    }

    public void queueWithCallback(SequentialData queueableData, UnitDataQueueCallback callback) {
        queue(queueableData, 0, queueableData.getNumFrames(), callback);
    }

    /** 立即调度 queueOn！ */
    public void queueOn(SequentialData queueableData) {
        queueOn(queueableData, getSynthesisEngine().createTimeStamp());
    }

    /** 立即调度 queueOff！ */
    public void queueOff(SequentialData queueableData) {
        queueOff(queueableData, false);
    }

    /** 立即调度 queueOff！ */
    public void queueOff(SequentialData queueableData, boolean ifStop) {
        SynthesisEngine engine = getSynthesisEngine();

        queueOff(queueableData, ifStop, engine == null ? null : engine.createTimeStamp());
    }

    /**
     * 便捷方法，将某个 channelData 的起音部分和延音循环
     * （若存在）。可用于实现 NoteOn 方法。
     */
    public void queueOn(SequentialData queueableData, TimeStamp timeStamp) {

        if (queueableData.getSustainBegin() < 0) {
            // 无延音循环，处理释放
            if (queueableData.getReleaseBegin() < 0) {
                // 无循环
                queueImmediate(queueableData, 0, queueableData.getNumFrames(), timeStamp);
            } else {
                queueImmediate(queueableData, 0, queueableData.getReleaseEnd(), timeStamp);
                int size = queueableData.getReleaseEnd() - queueableData.getReleaseBegin();
                queueLoop(queueableData, queueableData.getReleaseBegin(), size, timeStamp);
            }
        } else {
            // 是维持循环
            if (queueableData.getSustainEnd() > 0) {
                int frontSize = queueableData.getSustainBegin();
                int loopSize = queueableData.getSustainEnd() - queueableData.getSustainBegin();
                // 延音循环之前是否有初始部分？
                if (frontSize > 0) {
                    queueImmediate(queueableData, 0, frontSize, timeStamp);
                }
                loopSize = queueableData.getSustainEnd() - queueableData.getSustainBegin();
                if (loopSize > 0) {
                    queueLoop(queueableData, queueableData.getSustainBegin(), loopSize, timeStamp);
                }
            }

        }
    }

    /**
     * 便捷方法，将 SequentialData 对象的衰减部分入队，或将间隙
     * 并释放循环部分（如果存在）。这可用于实现NoteOff方法。
     *
     * @param ifStop 若在无释放循环的情况下入队了释放部分，则 setAutostop(true)。这将
     *         停止执行设备。
     */
    public void queueOff(SequentialData queueableData, boolean ifStop, TimeStamp timeStamp) {
        if (queueableData.getSustainBegin() >= 0) /* 维持循环？*/
        {
            int relSize = queueableData.getReleaseEnd() - queueableData.getReleaseBegin();
            if (queueableData.getReleaseBegin() < 0) { /* 维持循环，无释放循环。*/
                int susEnd = queueableData.getSustainEnd();
                int size = queueableData.getNumFrames() - susEnd;
                // LOGGER.debug("queueOff: size =" + size);
                if (size <= 0) {
                    // 始终入队一些内容，以便我们能停止循环
                    // 20001117
                    size = 1;
                    susEnd = queueableData.getNumFrames() - 1;
                }
                if (ifStop) {
                    queueStop(queueableData, susEnd, size, timeStamp);
                } else {
                    queue(queueableData, susEnd, size, timeStamp);
                }
            } else if (queueableData.getReleaseBegin() > queueableData.getSustainEnd()) {
                // 入队延音与释放循环之间的间隙。
                queue(queueableData, queueableData.getSustainEnd(), queueableData.getReleaseEnd()
                        - queueableData.getSustainEnd(), timeStamp);
                if (relSize > 0)
                    queueLoop(queueableData, queueableData.getReleaseBegin(), relSize, timeStamp);
            } else if (relSize > 0) {
                // 维持和释放之间没有间隙。
                queueLoop(queueableData, queueableData.getReleaseBegin(), relSize, timeStamp);
            }
        }
        /* 如果没有维持循环，则无事可做。*/
    }

    public void clear(TimeStamp timeStamp) {
        ScheduledCommand command = new ClearQueueCommand();
        scheduleCommand(timeStamp, command);
    }

    public void clear() {
        ScheduledCommand command = new ClearQueueCommand();
        queueCommand(command);
    }

    public void writeNextDouble(double value) {
        checkBlock();
        currentBlock.currentData.writeDouble(frameIndex, value);
        advanceFrameIndex();
    }

    public long getFrameCount() {
        return framesMoved;
    }

    public boolean testAndClearAutoStop() {
        boolean temp = autoStopPending;
        autoStopPending = false;
        return temp;
    }

    public boolean isTargetValid() {
        return targetValid;
    }

    public void setNumChannels(int numChannels) {
        this.numChannels = numChannels;
    }

    public int getNumChannels() {
        return numChannels;
    }
}
