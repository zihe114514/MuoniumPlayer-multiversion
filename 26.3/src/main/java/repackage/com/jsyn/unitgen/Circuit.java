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

import repackage.com.jsyn.engine.SynthesisEngine;
import repackage.com.jsyn.ports.UnitPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * 包含一起执行的单位列表。
 *
 * @author Phil Burk (C) 2009 Mobileer Inc
 */
public class Circuit extends UnitGenerator {
    private ArrayList<UnitGenerator> units = new ArrayList<>();

    private final LinkedHashMap<String, UnitPort> portAliases = new LinkedHashMap<>();

    @Override
    public void generate(int start, int limit) {
        for (UnitGenerator unit : units) {
            unit.generate(start, limit);
        }
    }

    /**
     * 对子单元调用flattenOutputs。扁平化输出端口，这样我们就不会输出变化的信号
     * 当停止时。
     */
    @Override
    public void flattenOutputs() {
        for (UnitGenerator unit : units) {
            unit.flattenOutputs();
        }
    }

    /**
     * 对子单元调用setEnabled。
     */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        for (UnitGenerator unit : units) {
            unit.setEnabled(enabled);
        }
    }

    /**
     * @deprecated 已忽略， frameRate来自SynthesisEngine
     * @param frameRate
     */
    @Deprecated
    @Override
    public void setFrameRate(int frameRate) {
        super.setFrameRate(frameRate);
        for (UnitGenerator unit : units) {
            unit.setFrameRate(frameRate);
        }
    }

    @Override
    public void setSynthesisEngine(SynthesisEngine engine) {
        super.setSynthesisEngine(engine);
        for (UnitGenerator unit : units) {
            unit.setSynthesisEngine(engine);
        }
    }

    /** 在电路中添加一个单元。*/
    public void add(UnitGenerator unit) {
        units.add(unit);
        unit.setCircuit(this);
        // 将电路属性向下传播到子单元。
        unit.setEnabled(isEnabled());
    }

    public void usePreset(int presetIndex) {
    }


    /**
     * 添加用于查找端口的备用名称。
     * @param port
     * @param alias
     */
    public void addPortAlias(UnitPort port, String alias) {
        // 用别名存入哈希表。
        portAliases.put(alias.toLowerCase(), port);
    }


    /**
     * 按名称或别名（不区分大小写）查找端口。
     * @param portName
     * @return 匹配端口或空
     */
    @Override
    public UnitPort getPortByName(String portName) {
        UnitPort port = super.getPortByName(portName);
        if (port == null) {
            port = portAliases.get(portName.toLowerCase());
        }
        return port;
    }

    /**
     * 保证各单元保持相同顺序
     * 正如它们被添加的那样。
     *
     * @return 已添加到此电路的单元阵列。
     */
    UnitGenerator[] getUnits() {
        return units.toArray(new UnitGenerator[0]);
    }
}
