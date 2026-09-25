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

import repackage.com.jsyn.ports.UnitDataQueuePort;
import repackage.com.jsyn.ports.UnitInputPort;

/**
 * 简单的立体声示例写入器。每个音频帧写入两个样本，没有插值。这可以是
 * 用于录制音频或构建延迟线。
 *
 * 注意，必须对该单元调用 start()，因为它没有用于拉取数据的输出。
 *
 * @author Phil Burk (C) 2009 Mobileer Inc
 */
public class FixedRateStereoWriterToMono extends SequentialDataWriter {

    public FixedRateStereoWriterToMono() {
        addPort(input = new UnitInputPort(2, "Input"));
        dataQueue.setNumChannels(1);
    }

    public UnitDataQueuePort dataQueue = new UnitDataQueuePort("Data");

    @Override
    public void generate(int start, int limit) {
        double[] input0s = input.getValues(0);
        double[] input1s = input.getValues(1);

        for (int i = start; i < limit; i++) {
            if (dataQueue.hasMore()) {
                double value = (input0s[i] + input1s[i]) * .5;
                dataQueue.writeNextDouble(value);
            } else {
                if (dataQueue.testAndClearAutoStop()) {
                    autoStop();
                }
            }
            dataQueue.firePendingCallbacks();
        }

    }

}
