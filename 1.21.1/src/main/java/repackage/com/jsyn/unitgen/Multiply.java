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

import repackage.com.jsyn.ports.UnitInputPort;
import repackage.com.jsyn.ports.UnitOutputPort;

/**
 * 此单元将其两个输入相乘。<br>
 * 
 * <pre>
 * output = inputA * inputB
 * </pre>
 * 
 * <br>
 * 注意，有些单元有 amplitude 端口，它控制一个内部乘法。因此你可能不必
 * 需要这个单元。
 * 
 * @author Phil Burk (C) 2009 Mobileer Inc
 * @version 016
 */
public class Multiply extends UnitBinaryOperator {
    public Multiply() {
    }

    /** 将 a 接到 inputA，b 接到 inputB。 */
    public Multiply(UnitOutputPort a, UnitOutputPort b) {
        a.connect(inputA);
        b.connect(inputB);
    }

    /** 将 a 接到 inputA，b 接到 inputB，并将 output 接到 c。 */
    public Multiply(UnitOutputPort a, UnitOutputPort b, UnitInputPort c) {
        this(a, b);
        output.connect(c);
    }

    @Override
    public void generate(int start, int limit) {
        double[] aValues = inputA.getValues();
        double[] bValues = inputB.getValues();
        double[] outputs = output.getValues();
        for (int i = start; i < limit; i++) {
            outputs[i] = aValues[i] * bValues[i];
        }
    }

}
