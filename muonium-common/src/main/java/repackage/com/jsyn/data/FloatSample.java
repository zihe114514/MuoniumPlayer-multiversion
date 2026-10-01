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

import lombok.Getter;
import repackage.com.jsyn.unitgen.VariableRateMonoReader;
import repackage.com.jsyn.unitgen.VariableRateStereoReader;
import repackage.com.jsyn.util.SampleLoader;

/**
 * 将多声道浮点音频数据存储在交错缓冲区中。数值以
 * 32位浮点数。可以使用其中一个读取器播放示例，例如VariableRateMonoReader。
 *
 * @author Phil Burk (C) 2010 Mobileer Inc
 * @see SampleLoader
 * @see VariableRateMonoReader
 * @see VariableRateStereoReader
 */
public class FloatSample extends AudioSample {
    @Getter
    public float[] buffer;

    public int writeIndex = 0;

    public FloatSample() {
    }

    /** 单样本的构造函数。*/
    public FloatSample(int numFrames) {
        this(numFrames, 1);
    }

    /** 具有数据的单样本的构造函数。*/
    public FloatSample(float[] data) {
        this(data.length, 1);
        write(data);
    }

    /** 带数据的多声道采样构造函数。 */
    public FloatSample(float[] data, int channelsPerFrame) {
        this(data.length / channelsPerFrame, channelsPerFrame);
        write(data);
    }

    /**
     * 创建一个静音采样，并分配足够内存存放音频数据。采样
     * 数组中的数字将是numFrames * channelsPerFrame。
     *
     * @param numFrames 采样组数。一个立体声帧包含 2 个采样。
     * @param channelsPerFrame 1 为单声道，2 为立体声
     */
    public FloatSample(int numFrames, int channelsPerFrame) {
        allocate(numFrames, channelsPerFrame);
    }

    /**
     * 分配内存以保存音频数据。数组中的样本编号数量将为
     * numFrames*channelsPerFrame.
     *
     * @param numFrames 采样组数。一个立体声帧包含 2 个采样。
     * @param channelsPerFrame 1 为单声道，2 为立体声
     */
    @Override
    public void allocate(int numFrames, int channelsPerFrame) {
        buffer = new float[numFrames * channelsPerFrame];
        this.numFrames = numFrames;
        this.channelsPerFrame = channelsPerFrame;
    }

    /**
     * 注意，在立体声采样中，一个帧有两个值。
     *
     * 样本中框架的@param startFrame 索引
     * @param data 待写入的数据
     * 数组中第一个值的@param startIndex 索引
     * @param numFrames
     */
    public void write(int startFrame, float[] data, int startIndex, int numFrames) {
        int numSamplesToWrite = numFrames * channelsPerFrame;
        int firstSampleIndexToWrite = startFrame * channelsPerFrame;
        writeIndex = firstSampleIndexToWrite + numSamplesToWrite;
        System.arraycopy(data, startIndex, buffer, firstSampleIndexToWrite, numSamplesToWrite);
    }

    /**
     * 注意，在立体声采样中，一个帧有两个值。
     *
     * 样本中框架的@param startFrame 索引
     * @param data 数组接收样本数据
     * 要开始填充的数组中第一个位置的@param startIndex 索引
     * @param numFrames
     */
    public void read(int startFrame, float[] data, int startIndex, int numFrames) {
        int numSamplesToRead = numFrames * channelsPerFrame;
        int firstSampleIndexToRead = startFrame * channelsPerFrame;
        System.arraycopy(buffer, firstSampleIndexToRead, data, startIndex, numSamplesToRead);
    }

    /**
     * 将整个数组写入采样。采样数据必须已通过
     * 足够的空间来容纳数据。
     *
     * @param data
     */
    public void write(float[] data) {
        write(0, data, 0, data.length / getChannelsPerFrame());
    }

    public void read(float[] data) {
        read(0, data, 0, data.length / getChannelsPerFrame());
    }

    @Override
    public double readDouble(int index) {

        if (buffer == null)
            return .0;

        return buffer[index];
    }

    public boolean isIndexOverflow(int index) {
        return index >= buffer.length;
    }

    @Override
    public void writeDouble(int index, double value) {

        if (index > buffer.length - 1) {
            return;
        }

        writeIndex = index;
        buffer[index] = (float) value;
    }

    /**
     * 在两个相邻样品之间进行插值。
     * 请注意，这仅适用于单声道样本。
     *
     * @param fractionalIndex 必须> = 0且< (size-1)
     */
    public double interpolate(double fractionalIndex) {
        int index = (int) fractionalIndex;
        float phase = (float) (fractionalIndex - index);
        float source = buffer[index];
        float target = buffer[index + 1];
        return ((target - source) * phase) + source;
    }


    public void cleanUp() {
        this.buffer = null;
    }
}
