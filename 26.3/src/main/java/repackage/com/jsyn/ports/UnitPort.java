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

import repackage.com.jsyn.engine.SynthesisEngine;
import repackage.com.jsyn.unitgen.UnitGenerator;
import repackage.com.softsynth.shared.time.ScheduledCommand;
import repackage.com.softsynth.shared.time.TimeStamp;

/**
 * JSyn 单元生成器的基础音频端口。
 * 
 * @author Phil Burk (C) 2009 Mobileer Inc
 */
public class UnitPort {
    private String name;
    private UnitGenerator unit;

    public UnitPort(String name) {
        this.name = name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setUnitGenerator(UnitGenerator unit) {
        // 如果端口在电路中，那么我们只想使用较低的级别
        // 用于实例化电路的单元。
        if (this.unit == null) {
            this.unit = unit;
        }
    }

    public UnitGenerator getUnitGenerator() {
        return unit;
    }

    SynthesisEngine getSynthesisEngine() {
        if (unit == null) {
            return null;
        }
        return unit.getSynthesisEngine();
    }

    public int getNumParts() {
        return 1;
    }

    public void scheduleCommand(TimeStamp timeStamp, ScheduledCommand scheduledCommand) {
        if (getSynthesisEngine() == null) {
            scheduledCommand.run();
        } else {
            getSynthesisEngine().scheduleCommand(timeStamp, scheduledCommand);
        }
    }

    public void queueCommand(ScheduledCommand scheduledCommand) {
        if (getSynthesisEngine() == null) {
            scheduledCommand.run();
        } else {
            getSynthesisEngine().scheduleCommand(getSynthesisEngine().createTimeStamp(),
                    scheduledCommand);
        }
    }

}
