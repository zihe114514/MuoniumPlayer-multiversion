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

import repackage.com.jsyn.data.FloatSample;
import repackage.com.jsyn.data.ShortSample;
import repackage.com.jsyn.ports.UnitOutputPort;

/**
 * This reader can play any SequentialData and will interpolate between adjacent values. It can play
 * {@link FloatSample samples}.
 *
 * <pre><code>
	// 将一个包络入队到 dataQueue 端口。
	ampEnv.dataQueue.queue(ampEnvelope);
</code> </pre>
 *
 * @author Phil Burk (C) 2009 Mobileer Inc
 * @see FloatSample
 * @see ShortSample
 */
public class VariableRateMonoReader extends VariableRateDataReader {
    private double phase; // 取值范围为 0.0 到 1.0
    private double baseIncrement;
    private double source;
    private double current;
    private double target;
    private boolean starved;
    private boolean ranout;

    public VariableRateMonoReader() {
        super();
        addPort(output = new UnitOutputPort("Output"));
        starved = true;
        baseIncrement = 1.0;
    }

    @Override
    public void generate(int start, int limit) {
        double[] amplitudes = amplitude.getValues();
        double[] rates = rate.getValues();
        double[] outputs = output.getValues();

        for (int i = start; i < limit; i++) {
            // 递减相位并在已入队数据中前进，直到相位回到
            // 范围内。
            if (phase >= 1.0) {
                while (phase >= 1.0) {
                    source = target;
                    phase -= 1.0;
                    baseIncrement = advanceToNextFrame();
                }
            } else if ((i == 0) && (starved || !dataQueue.isTargetValid())) {
                // 饥饿的状况只能在
                // 块。
                source = target = current;
                phase = 0.0;
                baseIncrement = advanceToNextFrame();
            }

            // 沿线段插值。
            current = ((target - source) * phase) + source;
            outputs[i] = current * amplitudes[i];

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
        // 在获取下一个值之前触发回调，因为我们已经获得了
        // 之前的目标值。
        dataQueue.firePendingCallbacks();
        if (dataQueue.hasMore()) {
            starved = false;
            target = dataQueue.readNextMonoDouble(getFramePeriod());

            // 计算相位增量；
            return getFramePeriod() * dataQueue.getNormalizedRate();
        } else {
            starved = true;
            ranout = true;
            phase = 0.0;
            return 0.0;
        }
    }

}
