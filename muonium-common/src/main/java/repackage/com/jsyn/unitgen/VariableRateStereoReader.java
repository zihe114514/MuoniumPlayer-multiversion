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

import repackage.com.jsyn.ports.UnitOutputPort;

/**
 * This reader can play any SequentialData and will interpolate between adjacent values. It can play
 * 包络和采样均可。
 * 
 * @author Phil Burk (C) 2009 Mobileer Inc
 */
public class VariableRateStereoReader extends VariableRateDataReader {
    private double phase;
    private double baseIncrement;
    private double source0;
    private double current0;
    private double target0;
    private double source1;
    private double current1;
    private double target1;
    private boolean starved;
    private boolean ranout;

    public VariableRateStereoReader() {
        dataQueue.setNumChannels(2);
        addPort(output = new UnitOutputPort(2, "Output"));
        starved = true;
        baseIncrement = 1.0;
    }

    @Override
    public void generate(int start, int limit) {
        double[] amplitudes = amplitude.getValues();
        double[] rates = rate.getValues();
        double[] output0s = output.getValues(0);
        double[] output1s = output.getValues(1);

        for (int i = start; i < limit; i++) {
            // 递减相位并在已入队数据中前进，直到相位回到
            // 范围内。
            if (phase >= 1.0) {
                while (phase >= 1.0) {
                    source0 = target0;
                    source1 = target1;
                    phase -= 1.0;
                    baseIncrement = advanceToNextFrame();
                }
            } else if ((i == 0) && (starved || !dataQueue.isTargetValid())) {
                // 饥饿状态只能在格挡开始时治愈。
                source0 = target0 = current0;
                source1 = target1 = current1;
                phase = 0.0;
                baseIncrement = advanceToNextFrame();
            }

            // 沿线段插值。
            current0 = ((target0 - source0) * phase) + source0;
            output0s[i] = current0 * amplitudes[i];
            current1 = ((target1 - source1) * phase) + source1;
            output1s[i] = current1 * amplitudes[i];

            double phaseIncrement = baseIncrement * rates[i];
            phase += limitPhaseIncrement(phaseIncrement);
        }

        if (ranout) {
            ranout = false;
            if (dataQueue.testAndClearAutoStop()) {
                autoStop();
            }
        }
    }

    public double limitPhaseIncrement(double phaseIncrement) {
        return phaseIncrement;
    }

    private double advanceToNextFrame() {
        dataQueue.firePendingCallbacks();
        if (dataQueue.hasMore()) {
            starved = false;

            dataQueue.beginFrame(getFramePeriod());
            target0 = dataQueue.readCurrentChannelDouble(0);
            target1 = dataQueue.readCurrentChannelDouble(1);
            dataQueue.endFrame();

            // 计算相位增量；
            return synthesisEngine.getFramePeriod() * dataQueue.getNormalizedRate();
        } else {
            starved = true;
            ranout = true;
            phase = 0.0;
            return 0.0;
        }
    }

}
