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

import repackage.com.jsyn.unitgen.UnitSink;
import repackage.com.softsynth.shared.time.TimeStamp;

/**
 * 单元写入其输出端口块。其他多个已连接的输入端口从中读取。
 * 
 * @author Phil Burk (C) 2009 Mobileer Inc
 */

public class UnitOutputPort extends UnitBlockPort implements ConnectableOutput, GettablePort {
    public UnitOutputPort() {
        this("Output");
    }

    public UnitOutputPort(String name) {
        this(1, name, 0.0);
    }

    public UnitOutputPort(int numParts, String name) {
        this(numParts, name, 0.0);
    }

    public UnitOutputPort(int numParts, String name, double defaultValue) {
        super(numParts, name, defaultValue);
    }

    public void flatten() {
        for (PortBlockPart part : parts) {
            part.flatten();
        }
    }

    public void connect(int thisPartNum, UnitInputPort otherPort, int otherPartNum) {
        PortBlockPart source = parts[thisPartNum];
        PortBlockPart destination = otherPort.parts[otherPartNum];
        source.connect(destination);
    }

    public void connect(int thisPartNum, UnitInputPort otherPort, int otherPartNum,
            TimeStamp timeStamp) {
        PortBlockPart source = parts[thisPartNum];
        PortBlockPart destination = otherPort.parts[otherPartNum];
        source.connect(destination, timeStamp);
    }

    public void connect(UnitInputPort input) {
        connect(0, input, 0);
    }

    @Override
    public void connect(ConnectableInput input) {
        parts[0].connect(input);
    }

    public void connect(UnitSink sink) {
        connect(0, sink.getInput(), 0);
    }

    public void disconnect(int thisPartNum, UnitInputPort otherPort, int otherPartNum) {
        PortBlockPart source = parts[thisPartNum];
        PortBlockPart destination = otherPort.parts[otherPartNum];
        source.disconnect(destination);
    }

    public void disconnect(int thisPartNum, UnitInputPort otherPort, int otherPartNum,
            TimeStamp timeStamp) {
        PortBlockPart source = parts[thisPartNum];
        PortBlockPart destination = otherPort.parts[otherPartNum];
        source.disconnect(destination, timeStamp);
    }

    public void disconnect(UnitInputPort otherPort) {
        disconnect(0, otherPort, 0);
    }

    @Override
    public void disconnect(ConnectableInput input) {
        parts[0].disconnect(input);
    }

    public ConnectableOutput getConnectablePart(int i) {
        return parts[i];
    }

}
