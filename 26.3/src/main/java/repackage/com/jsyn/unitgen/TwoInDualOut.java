/*
 * 版权所有2004 Phil Burk, Mobileer Inc
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

/**
 * 该单元将两个离散输入组合成双（立体声）输出。
 * 
 * <pre>
 *  output [0] = inputA
 *  output [1] = inputB
 * </pre>
 * 
 * @author (C) 2004-2009 Phil Burk, SoftSynth.com
 */

public class TwoInDualOut extends UnitGenerator {
    public UnitInputPort inputA;
    public UnitInputPort inputB;
    public UnitOutputPort output;

    public TwoInDualOut() {
        addPort(inputA = new UnitInputPort("InputA"));
        addPort(inputB = new UnitInputPort("InputB"));
        addPort(output = new UnitOutputPort(2, "OutputB"));
    }

    @Override
    public void generate(int start, int limit) {
        double[] inputAs = inputA.getValues();
        double[] inputBs = inputB.getValues();
        double[] output0s = output.getValues(0);
        double[] output1s = output.getValues(1);

        for (int i = start; i < limit; i++) {
            output0s[i] = inputAs[i];
            output1s[i] = inputBs[i];
        }
    }
}
