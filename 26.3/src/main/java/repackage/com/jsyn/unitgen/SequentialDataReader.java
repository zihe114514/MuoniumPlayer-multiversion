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

import repackage.com.jsyn.ports.UnitDataQueuePort;
import repackage.com.jsyn.ports.UnitInputPort;
import repackage.com.jsyn.ports.UnitOutputPort;

/**
 * 用于读取样本或信封的基类。
 * 
 * @author Phil Burk (C) 2009 Mobileer Inc
 */
public abstract class SequentialDataReader extends UnitGenerator {
    public UnitDataQueuePort dataQueue;
    public UnitInputPort amplitude;
    public UnitOutputPort output;

    public static final double DEFAULT_FREQUENCY = 440.0;
    public static final double DEFAULT_AMPLITUDE = 1.0;

    /* 定义connect()和set()使用的单位端口。*/
    public SequentialDataReader() {
        addPort(dataQueue = new UnitDataQueuePort("Data"));
        addPort(amplitude = new UnitInputPort("Amplitude", DEFAULT_AMPLITUDE));
    }
}
