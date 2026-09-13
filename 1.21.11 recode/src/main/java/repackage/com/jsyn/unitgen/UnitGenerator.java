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
import repackage.com.jsyn.engine.SynthesisEngine;
import repackage.com.jsyn.ports.ConnectableInput;
import repackage.com.jsyn.ports.UnitInputPort;
import repackage.com.jsyn.ports.UnitOutputPort;
import repackage.com.jsyn.ports.UnitPort;
import repackage.com.softsynth.shared.time.TimeStamp;

import java.io.PrintStream;
import java.util.Collection;
import java.util.LinkedHashMap;

/**
 * 所有单元生成器的基类。
 *
 * @author Phil Burk (C) 2009 Mobileer Inc
 */
public abstract class UnitGenerator {

    protected static final double VERY_SMALL_FLOAT = 1.0e-26;

    // 一些常见的端口名称。
    public static final String PORT_NAME_INPUT = "Input";
    public static final String PORT_NAME_OUTPUT = "Output";
    public static final String PORT_NAME_PHASE = "Phase";
    public static final String PORT_NAME_FREQUENCY = "Frequency";
    public static final String PORT_NAME_FREQUENCY_SCALER = "FreqScaler";
    public static final String PORT_NAME_AMPLITUDE = "Amplitude";
    public static final String PORT_NAME_PAN = "Pan";
    public static final String PORT_NAME_TIME = "Time";
    public static final String PORT_NAME_CUTOFF = "Cutoff";
    public static final String PORT_NAME_PRESSURE = "Pressure";
    public static final String PORT_NAME_TIMBRE = "Timbre";

    public static final double FALSE = 0.0;
    public static final double TRUE = 1.0;
    protected SynthesisEngine synthesisEngine;
    private final LinkedHashMap<String, UnitPort> ports = new LinkedHashMap<>();
    private Circuit circuit;
    private long lastFrameCount;
    private boolean enabled = true;
    private static int nextId;
    private final int id = nextId++;

    public int getId() {
        return id;
    }

    public int getFrameRate() {
        // return frameRate ；
        return synthesisEngine.getFrameRate();
    }

    public double getFramePeriod() {
        // return framePeriod;//TODO -如果我使用这个，为什么OldJSynTestSuite会失败！
        return synthesisEngine.getFramePeriod();
    }

    public void addPort(UnitPort port) {
        port.setUnitGenerator(this);
        // 按名称存入哈希表。
        ports.put(port.getName().toLowerCase(), port);
    }

    public void addPort(UnitPort port, String name) {
        port.setName(name);
        addPort(port);
    }

    /**
     * 按名称搜索端口时不区分大小写。
     * @param portName
     * @return 匹配端口或空
     */
    public UnitPort getPortByName(String portName) {
        return ports.get(portName.toLowerCase());
    }

    public Collection<UnitPort> getPorts() {
        return ports.values();
    }

    /**
     * 执行必要的合成功能。
     *
     * @param start 端口缓冲区中的偏移
     * @param limit 极限偏移到环路端口缓冲区
     */
    public abstract void generate(int start, int limit);

    /**
     * 生成一个完整块。
     */
    public void generate() {
        generate(0, Synthesizer.FRAMES_PER_BLOCK);
    }

    /**
     * @return synthesisEngine
     */
    public SynthesisEngine getSynthesisEngine() {
        return synthesisEngine;
    }

    /**
     * @return 合成器
     */
    public Synthesizer getSynthesizer() {
        return synthesisEngine;
    }

    /**
     * @param synthesisEngine 要设置的合成引擎
     */
    public void setSynthesisEngine(SynthesisEngine synthesisEngine) {
        if ((this.synthesisEngine != null) && (this.synthesisEngine != synthesisEngine)) {
            throw new RuntimeException("Unit synthesisEngine already set.");
        }
        this.synthesisEngine = synthesisEngine;
    }

    public UnitGenerator getTopUnit() {
        UnitGenerator unit = this;
        // 爬升到电路层次结构的顶部。
        while (unit.circuit != null) {
            unit = unit.circuit;
        }
        return unit;
    }

    protected void autoStop() {
        synthesisEngine.autoStopUnit(getTopUnit());
    }

    /** 根据指数衰减的半衰期计算信号。*/
    public double convertHalfLifeToMultiplier(double halfLife) {
        if (halfLife < (2.0 * getFramePeriod())) {
            return 1.0;
        } else {
            return 1.0 - Math.pow(0.5, 1.0 / (halfLife * getSynthesisEngine().getFrameRate()));
        }
    }

    protected double incrementWrapPhase(double currentPhase, double phaseIncrement) {
        currentPhase += phaseIncrement;

        if (currentPhase >= 1.0) {
            currentPhase -= 2.0;
        } else if (currentPhase < -1.0) {
            currentPhase += 2.0;
        }
        return currentPhase;
    }

    /** 根据相位随时间从 0.0 到 1.0 的变化计算速率。 */
    protected double convertTimeToRate(double time) {
        double period2X = synthesisEngine.getInverseNyquist();
        if (time < period2X) {
            return 1.0;
        } else {
            return getFramePeriod() / time;
        }
    }

    /** 扁平化输出端口，以便在停止时不会输出变化的信号。*/
    public void flattenOutputs() {
        for (UnitPort port : ports.values()) {
            if (port instanceof UnitOutputPort) {
                ((UnitOutputPort) port).flatten();
            }
        }
    }

    public void setCircuit(Circuit circuit) {
        if ((this.circuit != null) && (circuit != null)) {
            throw new RuntimeException("Unit is already in a circuit.");
        }
        // LOGGER.info( "setCircuit in  unit " + this + " with circuit " + circuit );
        this.circuit = circuit;
    }

    public Circuit getCircuit() {
        return circuit;
    }

    public void pullData(long frameCount, int start, int limit) {
        // 如果路径合并，请勿生成两次。
        if (enabled && (frameCount > lastFrameCount)) {
            // 首先执行此操作，以便在存在反馈循环时阻止递归。
            lastFrameCount = frameCount;
            // 然后从上游的所有单元中拉出。
            for (UnitPort port : ports.values()) {
                if (port instanceof ConnectableInput) {
                    ((ConnectableInput) port).pullData(frameCount, start, limit);
                }
            }
            // 最后使用上游单元的输出生成。
            generate(start, limit);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 如果启用，则如果一个单元的输出连接到另一个单元，则该单元将执行
     * 执行。如果未启用，则它将不会执行，也不会从
     * 连接到其输入。在单元树的输出处禁用单元可用于
     * 关闭整个树，从而节省CPU周期。
     *
     * @param enabled
     * @see #start()
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            flattenOutputs();
        }
    }

    /**
     * 某些单元，例如LineOut和FixedRateMonoWriter ，仅在
     * 显式启动。其他单元将在下游单元启动时运行。
     *
     * 如果您应该为此单元调用start() ，则@return 为true
     */
    public boolean isStartRequired() {
        return false;
    }

    /**
     * 通过将其添加到合成中的单元的“运行列表”中，直接执行此单元
     * 引擎。此方法通常只对链中最末端的单元调用，例如
     * LineOut。当该末端单元执行时，它会从连接到其
     * 输入。然后，这些单元将提取其输入的数据，直到整个链被执行。如果
     * 单位被连接成一个圆圈，那么这将被检测到，无限递归将被
     * 被阻塞。
     *
     * @see #setEnabled(boolean)
     */
    public void start() {
        if (getSynthesisEngine() == null) {
            throw new RuntimeException("This " + this.getClass().getName()
                    + " was not add()ed to a Synthesizer.");
        }
        getSynthesisEngine().startUnit(this);
    }

    /**
     * 在指定时间启动单元。
     *
     * @param time
     * @see #start()
     */
    public void start(double time) {
        start(new TimeStamp(time));
    }

    /**
     * 在指定时间启动单元。
     *
     * @param timeStamp
     * @see #start()
     */
    public void start(TimeStamp timeStamp) {
        if (getSynthesisEngine() == null) {
            throw new RuntimeException("This " + this.getClass().getName()
                    + " was not add()ed to a Synthesizer.");
        }
        getSynthesisEngine().startUnit(this, timeStamp);
    }

    /**
     * 供 UnitVoice 使用
     *
     * @param time
     * @see #start()
     */
    public void stop(double time) {
        stop(new TimeStamp(time));
    }

    public void stop() {
        getSynthesisEngine().stopUnit(this);
    }

    public void stop(TimeStamp timeStamp) {
        getSynthesisEngine().stopUnit(this, timeStamp);
    }

    /**
     * @deprecated 已忽略， frameRate来自SynthesisEngine
     * 由合成器开始执行此单元。输入端会从任何输出
     */
    @Deprecated
    public void setFrameRate(int rate) {
    }

    /** UnitSink需要*/
    public UnitGenerator getUnitGenerator() {
        return this;
    }

    /** 用于拥有输出及关联 UnitGenerator 的对象的接口。 */
    public void setPort(String portName, double value, TimeStamp timeStamp) {
        UnitInputPort port = (UnitInputPort) getPortByName(portName);
        // LOGGER.debug("setPort" + port);
        if (port != null) {
            port.set(value, timeStamp);
        }
    }

    public void printConnections() {
        printConnections(System.out);
    }

    public void printConnections(PrintStream out) {
        printConnections(out, 0);
    }

    public void printConnections(PrintStream out, int level) {
        for (UnitPort port : getPorts()) {
            if (port instanceof UnitInputPort) {
                ((UnitInputPort) port).printConnections(out, level);
            }
        }
    }

}
