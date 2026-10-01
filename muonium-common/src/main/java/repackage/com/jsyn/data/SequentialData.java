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

import repackage.com.jsyn.unitgen.VariableRateMonoReader;
import repackage.com.jsyn.unitgen.VariableRateStereoReader;

/**
 * 可通过索引读取和/或写入的对象的接口。索引未存储
 * 在内部处理，以便多个读取器共享。
 *
 * @author Phil Burk (C) 2010 Mobileer Inc
 * @see VariableRateMonoReader
 * @see VariableRateStereoReader
 */
public interface SequentialData {
    /**
     * 在给定的索引处写入一个值。
     *
     * @param index 样本索引为((frameIndex * channelsPerFrame) + channelIndex)
     * @param value 要写入的值
     */
    void writeDouble(int index, double value);

    /**
     * 从采样中读取一个值，与内部存储格式无关。
     *
     * @param index 样本索引为((frameIndex * channelsPerFrame) + channelIndex)
     */
    double readDouble(int index);

    /***
     * @return 延音循环的起始位置；若无循环则为 -1。
     */
    int getSustainBegin();

    /**
     * SustainEnd值是刚刚经过循环末尾的帧的帧索引。
     * 循环包含的帧数为 (SustainEnd - SustainBegin)。
     *
     * @return 延音循环的结束位置；若无循环则为 -1。
     */
    int getSustainEnd();

    /***
     * @return 释放循环开始，如果没有循环则为-1。
     */
    int getReleaseBegin();

    /***
     * @return 释放循环的结束位置，若无循环则为 -1。
     */
    int getReleaseEnd();

    /**
     * 获取速率以播放数据。在信封中，这对应于框架的倒数
     * 持续时间，并且会因帧而异。对于音频示例，它是1.0。
     *
     * @param index
     * @param synthesisRate
     * @return 速率以缩放播放速度。
     */
    double getRateScaler(int index, double synthesisRate);

    /**
     * @return 对于立体声采样，返回2。
     */
    int getChannelsPerFrame();

    /**
     * @return 可读取的有效帧数。
     */
    int getNumFrames();
}
