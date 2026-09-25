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

package repackage.com.jsyn.unitgen;

import repackage.com.jsyn.Synthesizer;
import repackage.com.jsyn.ports.UnitInputPort;

/**
 * 提供对音频输出某个声道的访问。
 * 对于两个以上的频道，您必须致电
 * zZQ0ZZQ start()方法， numOutputChannels &gt; 2。
 *
 * @author Phil Burk (C) 2009 Mobileer Inc
 */
public class ChannelOut extends UnitGenerator {
    public UnitInputPort input;
    private int channelIndex;

    public ChannelOut() {
        addPort(input = new UnitInputPort("Input"));
    }

    public int getChannelIndex() {
        return channelIndex;
    }

    public void setChannelIndex(int channelIndex) {
        this.channelIndex = channelIndex;
    }

    /**
     * 除非你开始（ ） ，否则这个单位不会做任何事情。
     */
    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void generate(int start, int limit) {
        double[] inputs = input.getValues(0);
        double[] buffer = synthesisEngine.getOutputBuffer(channelIndex);
        for (int i = start; i < limit; i++) {
            buffer[i] += inputs[i];
        }
    }

}
