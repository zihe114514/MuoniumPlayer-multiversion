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
import repackage.com.jsyn.engine.SynthesisEngine;
import repackage.com.softsynth.shared.time.TimeStamp;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * 多部分端口的一部分，例如，立体声端口的左侧。
 *
 * @author Phil Burk (C) 2009 Mobileer Inc
 */
public class PortBlockPart implements ConnectableOutput, ConnectableInput {

    private double[] values = new double[Synthesizer.FRAMES_PER_BLOCK];
    private ArrayList<PortBlockPart> connections = new ArrayList<>();
    private UnitBlockPort unitBlockPort;

    protected PortBlockPart(UnitBlockPort unitBlockPort, double defaultValue) {
        this.unitBlockPort = unitBlockPort;
        setValue(defaultValue);
    }

    public double[] getValues() {
        return values;
    }

    public double getValue() {
        return values[0];
    }

    public double get() {
        return values[0];
    }

    protected void setValue(double value) {
        Arrays.fill(values, value);
    }

    protected boolean isConnected() {
        return (!connections.isEmpty());
    }

    private void addConnection(PortBlockPart otherPart) {
        if (!connections.contains(otherPart)) {
            connections.add(otherPart);
        }
    }

    private void removeConnection(PortBlockPart otherPart) {
        connections.remove(otherPart);
    }

    private void connectNow(PortBlockPart otherPart) {
        addConnection(otherPart);
        otherPart.addConnection(this);
    }

    private void disconnectNow(PortBlockPart otherPart) {
        removeConnection(otherPart);
        otherPart.removeConnection(this);
    }

    private void disconnectAllNow() {
        for (PortBlockPart part : connections) {
            part.removeConnection(this);
        }
        connections.clear();
    }

    public PortBlockPart getConnection(int i) {
        return connections.get(i);
    }

    public int getConnectionCount() {
        return connections.size();
    }

    /** 将所有值设置为最后一个值。*/
    protected void flatten() {
        double lastValue = values[values.length - 1];
        for (int i = 0; i < values.length - 1; i++) {
            values[i] = lastValue;
        }
    }

    protected UnitBlockPort getPort() {
        return unitBlockPort;
    }

    private void checkConnection(PortBlockPart destination) {
        SynthesisEngine sourceSynth = unitBlockPort.getSynthesisEngine();
        SynthesisEngine destSynth = destination.unitBlockPort.getSynthesisEngine();
        if ((sourceSynth != destSynth) && (sourceSynth != null) && (destSynth != null)) {
            throw new RuntimeException("Connection between units on different synths.");
        }
    }

    protected void connect(final PortBlockPart destination) {
        checkConnection(destination);
        unitBlockPort.queueCommand(() -> connectNow(destination));
    }

    protected void connect(final PortBlockPart destination, TimeStamp timeStamp) {
        unitBlockPort.scheduleCommand(timeStamp, () -> connectNow(destination));
    }

    protected void disconnect(final PortBlockPart destination) {
        unitBlockPort.queueCommand(() -> disconnectNow(destination));
    }

    protected void disconnect(final PortBlockPart destination, TimeStamp timeStamp) {
        unitBlockPort.scheduleCommand(timeStamp, () -> disconnectNow(destination));
    }

    protected void disconnectAll() {
        unitBlockPort.queueCommand(this::disconnectAllNow);
    }

    @Override
    public void connect(ConnectableInput other) {
        connect(other.getPortBlockPart());
    }

    @Override
    public void connect(ConnectableOutput other) {
        other.connect(this);
    }

    @Override
    public void disconnect(ConnectableOutput other) {
        other.disconnect(this);
    }

    @Override
    public void disconnect(ConnectableInput other) {
        disconnect(other.getPortBlockPart());
    }

    /** 为实现 ConnectableInput */
    @Override
    public PortBlockPart getPortBlockPart() {
        return this;
    }

    @Override
    public void pullData(long frameCount, int start, int limit) {
        for (int i = 0; i < getConnectionCount(); i++) {
            PortBlockPart part = getConnection(i);
            part.getPort().getUnitGenerator().pullData(frameCount, start, limit);
        }
    }

}
