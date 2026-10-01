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

import repackage.com.softsynth.shared.time.TimeStamp;

import java.io.PrintStream;

/**
 * 用于将值传递到UnitGenerator的端口。
 *
 * @author Phil Burk 2009 Mobileer Inc
 */
public class UnitInputPort extends UnitBlockPort implements ConnectableInput, SettablePort {
    private double minimum = 0.0;
    private double maximum = 1.0;
    private double defaultValue = 0.0;
    private double[] setValues;
    private boolean valueAdded = false;

    /**
     * @param numParts 通常为 1，立体声端口用 2
     * 可以在GUI中使用的@param name 名称
     * @param defaultValue
     */
    public UnitInputPort(int numParts, String name, double defaultValue) {
        super(numParts, name, defaultValue);
        setDefault(defaultValue);
        setValues = new double[numParts];
        for (int i = 0; i < numParts; i++) {
            setValues[i] = defaultValue;
        }
    }

    public UnitInputPort(String name, double defaultValue) {
        this(1, name, defaultValue);
    }

    public UnitInputPort(String name) {
        this(1, name, 0.0);
    }

    public UnitInputPort(int numParts, String name) {
        this(numParts, name, 0.0);
    }

    @Override
    protected void makeParts(int numParts, double defaultValue) {
        parts = new InputMixingBlockPart[numParts];
        for (int i = 0; i < numParts; i++) {
            parts[i] = new InputMixingBlockPart(this, defaultValue);
        }
    }

    /**
     * 这由SynthesisEngine内部使用，以根据其连接执行单元。
     *
     * @param frameCount
     * @param start
     * @param limit
     */
    @Override
    public void pullData(long frameCount, int start, int limit) {
        for (PortBlockPart part : parts) {
            part.pullData(frameCount, start, limit);
        }
    }

    @Override
    protected void setValueInternal(int partNum, double value) {
        super.setValueInternal(partNum, value);
        setValues[partNum] = value;
    }

    public void set(double value) {
        set(0, value);
    }

    public void set(final int partNum, final double value) {
        // 如果超出范围，立即触发异常。
        setValues[partNum] = value;
        queueCommand(() -> setValueInternal(partNum, value));
    }

    public void set(double value, TimeStamp time) {
        set(0, value, time);
    }

    public void set(double value, double time) {
        set(0, value, time);
    }

    public void set(int partNum, double value, double time) {
        set(partNum, value, new TimeStamp(time));
    }

    @Override
    public void set(final int partNum, final double value, TimeStamp timeStamp) {
        // 如果超出范围，立即触发异常。
        getValue(partNum);
        scheduleCommand(timeStamp, () -> setValueInternal(partNum, value));
    }

    /**
     * 基于set()调用的端口值。不受已连接端口的影响。
     *
     * @param partNum
     * @return 值为设定值
     */
    @Override
    public double get(int partNum) {
        return setValues[partNum];
    }

    public double getMaximum() {
        return maximum;
    }

    /**
     * 最小值和最大值仅在配置旋钮或其他控制系统时使用。
     * 内部数值不会被裁剪到此范围。
     *
     * @param maximum
     */
    public void setMaximum(double maximum) {
        this.maximum = maximum;
    }

    public double getMinimum() {
        return minimum;
    }

    public void setMinimum(double minimum) {
        this.minimum = minimum;
    }

    public double getDefault() {
        return defaultValue;
    }

    public void setDefault(double defaultValue) {
        this.defaultValue = defaultValue;
    }

    /**
     * 为端口设置上下限的便捷函数。这些限制值是建议值，用于
     * 设置GUI。可以将端口设置为超出这些限制的值。
     *
     * @param minimum
     * @param value 默认值，将被裁剪到 min/max
     * @param maximum
     */
    public void setup(double minimum, double value, double maximum) {
        setMinimum(minimum);
        setMaximum(maximum);
        setDefault(value);
        set(value);
    }

    // 从另一个端口抓取最小值、最大值和默认值。
    public void setup(UnitInputPort other) {
        setup(other.getMinimum(), other.getDefault(), other.getMaximum());
    }

    public boolean isValueAdded() {
        return valueAdded;
    }

    /**
     * 如果设置为false ，则当其他端口连接到此端口时， set()值将被忽略。
     * 将使用连接的端口值的总和。
     *
     * 若设为 true，则 set() 的值会加到所有已连接端口值的总和上。
     * 当你想对 set 的值进行调制时很有用。
     *
     * =ד 1. array_chunk() =ד
     *
     * @param valueAdded
     */
    public void setValueAdded(boolean valueAdded) {
        this.valueAdded = valueAdded;
    }

    public void connect(int thisPartNum, UnitOutputPort otherPort, int otherPartNum,
            TimeStamp timeStamp) {
        otherPort.connect(otherPartNum, this, thisPartNum, timeStamp);
    }

    /** 将输入端口连接到输出端口。*/
    public void connect(int thisPartNum, UnitOutputPort otherPort, int otherPartNum) {
        // 通常从输出到输入进行连接，因为它
        // 更直观。
        otherPort.connect(otherPartNum, this, thisPartNum);
    }

    public void connect(UnitOutputPort otherPort) {
        connect(0, otherPort, 0);
    }

    @Override
    public void connect(ConnectableOutput other) {
        other.connect(this);
    }

    public void disconnect(int thisPartNum, UnitOutputPort otherPort, int otherPartNum) {
        otherPort.disconnect(otherPartNum, this, thisPartNum);
    }

    @Override
    public PortBlockPart getPortBlockPart() {
        return parts[0];
    }

    public ConnectableInput getConnectablePart(int i) {
        return parts[i];
    }

    @Override
    public void disconnect(ConnectableOutput other) {
        other.disconnect(this);
    }

    public void printConnections(PrintStream out, int level) {
        for (PortBlockPart part : parts) {
            ((InputMixingBlockPart) part).printConnections(out, level);
        }
    }

}
