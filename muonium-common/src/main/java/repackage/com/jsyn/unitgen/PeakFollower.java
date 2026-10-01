/*
 * 版权所有1997 Phil Burk, Mobileer Inc
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

import repackage.com.jsyn.ports.UnitInputPort;
import repackage.com.jsyn.ports.UnitOutputPort;
import repackage.com.jsyn.ports.UnitVariablePort;

/**
 * 跟踪输入信号的峰值。可用于监测
 * 信号。输出可用于驱动颜色器官、声码器、VUmeters等。输出下降
 * 当输入低于当前输出电平时呈指数级增长。输出接近零
 * 基于 halfLife 端口上的值。
 * 
 * @author (C) 1997-2009 Phil Burk, SoftSynth.com
 */
public class PeakFollower extends UnitGenerator {
    public UnitInputPort input;
    public UnitVariablePort current;
    public UnitInputPort halfLife;
    public UnitOutputPort output;

    private double previousHalfLife = -1.0;
    private double decayScalar = 0.99;

    /* 定义connect()和set()使用的单位端口。*/
    public PeakFollower() {
        addPort(input = new UnitInputPort("Input"));
        addPort(halfLife = new UnitInputPort(1, "HalfLife", 0.1));
        addPort(current = new UnitVariablePort("Current"));
        addPort(output = new UnitOutputPort("Output"));
    }

    @Override
    public void generate(int start, int limit) {
        double[] inputs = input.getValues();
        double[] outputs = output.getValues();
        double currentHalfLife = halfLife.getValues()[0];
        double currentValue = current.getValue();

        if (currentHalfLife != previousHalfLife) {
            decayScalar = this.convertHalfLifeToMultiplier(currentHalfLife);
            previousHalfLife = currentHalfLife;
        }

        double scalar = 1.0 - decayScalar;

        for (int i = start; i < limit; i++) {
            double inputValue = inputs[i];
            if (inputValue < 0.0) {
                inputValue = -inputValue; // 绝对值
            }

            if (inputValue >= currentValue) {
                currentValue = inputValue;
            } else {
                currentValue = currentValue * scalar;
            }

            outputs[i] = currentValue;
        }

        /*
         * 当电流接近零时，将电流设置为零以防止FP下溢，这可以
         * 会导致 'C' 语言中严重的性能下降。
         */
        if (currentValue < VERY_SMALL_FLOAT) {
            currentValue = 0.0;
        }

        current.setValue(currentValue);
    }
}
