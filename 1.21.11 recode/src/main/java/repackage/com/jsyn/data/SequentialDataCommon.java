/*
 * Copyright 2010 Phil Burk, Mobileer Inc
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

package repackage.com.jsyn.data;

/**
 * 增加维持和释放循环的信封和样本的抽象基类。
 *
 * @author Phil Burk (C) 2010 Mobileer Inc
 */
public abstract class SequentialDataCommon implements SequentialData {
    protected int numFrames;
    protected int maxFrames;
    private int sustainBegin = -1;
    private int sustainEnd = -1;
    private int releaseBegin = -1;
    private int releaseEnd = -1;

    @Override
    public abstract void writeDouble(int index, double value);

    @Override
    public abstract double readDouble(int index);

    @Override
    public abstract double getRateScaler(int index, double synthesisRate);

    @Override
    public abstract int getChannelsPerFrame();

    /**
     * @return 最大数据帧数。
     */
    public int getMaxFrames() {
        return maxFrames;
    }

    /**
     * 设置数据帧数。输入将被剪辑到maxFrames。这在以下情况下非常有用：
     * 改变采样或包络的内容时。
     */
    public void setNumFrames(int numFrames) {
        if (numFrames > maxFrames)
            numFrames = maxFrames;
        this.numFrames = numFrames;
    }

    @Override
    public int getNumFrames() {
        return numFrames;
    }

    // JavaDocs will be copied from SequentialData

    @Override
    public int getSustainBegin() {
        return this.sustainBegin;
    }

    @Override
    public int getSustainEnd() {
        return this.sustainEnd;
    }

    @Override
    public int getReleaseBegin() {
        return this.releaseBegin;
    }

    @Override
    public int getReleaseEnd() {
        return this.releaseEnd;
    }

    /**
     * 设置维持循环的开始。调用UnitDataQueuePort.queueOn()时，
     * 若设置了循环，则先将起音部分入队，随后是该延音
     * 使用queueLoop()的区域。
     * 循环中的帧数为 (SustainEnd - SustainBegin)。
     * <p>
     * 对于稳定的延音电平（如 ADSR 包络中），设置 SustainBegin 和
     * SustainEnd到同一帧。
     * <p>
     * 对于被调制的延音，在循环中包含两个或更多帧。
     *
     * @param sustainBegin
    */
    public void setSustainBegin(int sustainBegin) {
        this.sustainBegin = sustainBegin;
    }

    /**
     * SustainEnd 的值是循环结束位置之后那一帧的索引。
     * 循环包含的帧数为 (SustainEnd - SustainBegin)。
     *
     * @param sustainEnd
     */
    public void setSustainEnd(int sustainEnd) {
        this.sustainEnd = sustainEnd;
    }

    /**
     * 释放循环的行为类似于维持循环，但它被触发
     * 由UnitDataQueuePort.queueOff()。
     *
     * @param releaseBegin
     */
    public void setReleaseBegin(int releaseBegin) {
        this.releaseBegin = releaseBegin;
    }

    /**
     * ReleaseEnd值是刚刚经过循环末尾的帧的帧索引。
     * 循环中包含的帧数为（ ReleaseEnd - ReleaseBegin ）。
     *
     * @param releaseEnd
     */

    public void setReleaseEnd(int releaseEnd) {
        this.releaseEnd = releaseEnd;
    }

}
