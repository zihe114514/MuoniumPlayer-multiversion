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

import repackage.com.jsyn.engine.SynthesisEngine;
import repackage.com.jsyn.unitgen.VariableRateMonoReader;
import repackage.com.jsyn.unitgen.VariableRateStereoReader;
import repackage.com.jsyn.util.SampleLoader;

/**
 * 将多通道短音频数据存储在交错缓冲区中。
 * 
 * @author Phil Burk (C) 2010 Mobileer Inc
 * @see SampleLoader
 * @see VariableRateMonoReader
 * @see VariableRateStereoReader
 */
public class ShortSample extends AudioSample {
    private short[] buffer;

    public ShortSample() {
    }

    public ShortSample(int numFrames, int channelsPerFrame) {
        allocate(numFrames, channelsPerFrame);
    }

    /** 具有数据的单样本的构造函数。*/
    public ShortSample(short[] data) {
        this(data.length, 1);
        write(data);
    }

    /** 带数据的多声道采样构造函数。 */
    public ShortSample(short[] data, int channelsPerFrame) {
        this(data.length / channelsPerFrame, channelsPerFrame);
        write(data);
    }

    @Override
    public void allocate(int numFrames, int channelsPerFrame) {
        buffer = new short[numFrames * channelsPerFrame];
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
    public void write(int startFrame, short[] data, int startIndex, int numFrames) {
        int numSamplesToWrite = numFrames * channelsPerFrame;
        int firstSampleIndexToWrite = startFrame * channelsPerFrame;
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
    public void read(int startFrame, short[] data, int startIndex, int numFrames) {
        int numSamplesToRead = numFrames * channelsPerFrame;
        int firstSampleIndexToRead = startFrame * channelsPerFrame;
        System.arraycopy(buffer, firstSampleIndexToRead, data, startIndex, numSamplesToRead);
    }

    public void write(short[] data) {
        write(0, data, 0, data.length);
    }

    public void read(short[] data) {
        read(0, data, 0, data.length);
    }

    public short readShort(int index) {
        return buffer[index];
    }

    public void writeShort(int index, short value) {
        buffer[index] = value;
    }

    /** 读取一个采样并转换为 double，取值范围为 -1.0 到接近 1.0。 */
    @Override
    public double readDouble(int index) {
        return SynthesisEngine.convertShortToDouble(buffer[index]);
    }

    /**
     * 写一个双精度数，将被裁剪到-1.0到近1.0的范围并转换为短数。
     */
    @Override
    public void writeDouble(int index, double value) {
        buffer[index] = SynthesisEngine.convertDoubleToShort(value);
    }

}
