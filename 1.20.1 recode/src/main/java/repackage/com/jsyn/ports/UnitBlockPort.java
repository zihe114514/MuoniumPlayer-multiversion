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

import lombok.Getter;

/**
 * 包含具有数据块的多个部分的端口。
 * 
 * @author Phil Burk 2009 Mobileer Inc
 */
public class UnitBlockPort extends UnitPort {
    @Getter
    PortBlockPart[] parts;

    public UnitBlockPort(int numParts, String name, double defaultValue) {
        super(name);
        makeParts(numParts, defaultValue);
    }

    public UnitBlockPort(String name) {
        this(1, name, 0.0);
    }

    protected void makeParts(int numParts, double defaultValue) {
        parts = new PortBlockPart[numParts];
        for (int i = 0; i < numParts; i++) {
            parts[i] = new PortBlockPart(this, defaultValue);
        }
    }

    @Override
    public int getNumParts() {
        return parts.length;
    }

    /**
     * 拨打方便电话即可获得(0)。
     * 
     * 设置第0部分的@return 值
     */
    public double get() {
        return get(0);
    }

    public double getValue() {
        return getValue(0);
    }

    /**
     * 供 UnitGenerator 内部使用，用于获取某个端口的当前值。无论
     * 端口是否已连接。
     * 
     * @return
     */
    public double[] getValues() {
        return parts[0].getValues();
    }

    /** 仅适用于实现UnitGenerators时的音频线程。*/
    public double[] getValues(int partNum) {
        return parts[partNum].getValues();
    }

    /** 获取端口的即时当前值。*/
    public double getValue(int partNum) {
        return parts[partNum].getValue();
    }

    public double get(int partNum) {
        return parts[partNum].get();
    }

    /** 仅适用于实现UnitGenerators时的音频线程。*/
    protected void setValueInternal(int partNum, double value) {
        parts[partNum].setValue(value);
    }

    /** 仅适用于实现UnitGenerators时的音频线程。*/
    public void setValueInternal(double value) {
        setValueInternal(0, value);
    }

    public boolean isConnected() {
        return isConnected(0);
    }

    public boolean isConnected(int partNum) {
        return parts[partNum].isConnected();
    }

    public void disconnectAll(int partNum) {
        parts[partNum].disconnectAll();
    }

    public void disconnectAll() {
        disconnectAll(0);
    }
}
