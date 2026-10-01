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

import java.util.EventObject;

/**
 * 播放队列中的元素时传递给UnitDataQueueCallback的事件。
 * 
 * @author Phil Burk 2009 Mobileer Inc
 */
public class QueueDataEvent extends EventObject {
    private static final long serialVersionUID = 176846633064538053L;
    protected SequentialData sequentialData;
    protected int startFrame;
    protected int numFrames;
    protected int numLoops;
    protected int loopsLeft;
    protected int crossFadeIn;
    protected boolean skipIfOthers;
    protected boolean autoStop;
    protected boolean immediate;
    protected boolean shouldStop;

    public QueueDataEvent(Object arg0) {
        super(arg0);
    }

    public boolean isSkipIfOthers() {
        return skipIfOthers;
    }

    public boolean isImmediate() {
        return immediate;
    }

    public SequentialData getSequentialData() {
        return sequentialData;
    }

    public int getCrossFadeIn() {
        return crossFadeIn;
    }

    public int getStartFrame() {
        return startFrame;
    }

    public int getNumFrames() {
        return numFrames;
    }

    public int getNumLoops() {
        return numLoops;
    }

    public int getLoopsLeft() {
        return loopsLeft;
    }

    public boolean isAutoStop() {
        return autoStop;
    }

    public boolean isShouldStop() {
        return shouldStop;
    }

    public void setShouldStop(boolean shouldStop) {
        this.shouldStop = shouldStop;
    }
}
