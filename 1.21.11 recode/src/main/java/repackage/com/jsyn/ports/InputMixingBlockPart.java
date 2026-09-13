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

package repackage.com.jsyn.ports;

import repackage.com.jsyn.Synthesizer;
import repackage.com.jsyn.unitgen.UnitGenerator;

import java.io.PrintStream;

/**
 * UnitInputPort有一个这些数组，每个部分一个。
 *
 * @author Phil Burk 2009 Mobileer Inc
 */

public class InputMixingBlockPart extends PortBlockPart {
    private double[] mixer = new double[Synthesizer.FRAMES_PER_BLOCK];
    private double current;
    private UnitInputPort unitInputPort;

    InputMixingBlockPart(UnitInputPort unitInputPort, double defaultValue) {
        super(unitInputPort, defaultValue);
        this.unitInputPort = unitInputPort;
    }

    @Override
    public double getValue() {
        return current;
    }

    @Override
    protected void setValue(double value) {
        current = value;
        super.setValue(value);
    }

    @Override
    public double[] getValues() {
        double[] result;
        int numConnections = getConnectionCount();
        // LOGGER.debug("numConnection =" + numConnections + "for" +
        // this );
        if (numConnections == 0) {
            // 没有连接，因此直接使用我们自己的数据。
            result = super.getValues();
        } else {
            // 混合所有连接的端口。
            double[] inputs;
            int jCon = 0;
            PortBlockPart otherPart;
            // 选择值以初始化混频器数组。
            if (unitInputPort.isValueAdded()) {
                inputs = super.getValues();  // 具有set()值的素数混合器
            } else {
                otherPart = getConnection(jCon);
                inputs = otherPart.getValues(); // 首次连接的灌注搅拌机
                jCon = 1;
            }
            System.arraycopy(inputs, 0, mixer, 0, mixer.length);
            // 现在混合剩余的输入。
            for (; jCon < numConnections; jCon++) {
                otherPart = getConnection(jCon);
                inputs = otherPart.getValues();
                for (int i = 0; i < mixer.length; i++) {
                    mixer[i] += inputs[i];
                }
            }
            result = mixer;
        }
        current = result[0];
        return result;
    }

    private void printIndentation(PrintStream out, int level) {
        for (int i = 0; i < level; i++) {
            out.print("    ");
        }
    }

    private String portToString(UnitBlockPort port) {
        UnitGenerator ugen = port.getUnitGenerator();
        return ugen.getClass().getSimpleName() + "." + port.getName();
    }

    public void printConnections(PrintStream out, int level) {
        for (int i = 0; i < getConnectionCount(); i++) {
            PortBlockPart part = getConnection(i);

            printIndentation(out, level);
            out.println(portToString(getPort()) + " <--- " + portToString(part.getPort()));

            part.getPort().getUnitGenerator().printConnections(out, level + 1);
        }
    }
}
