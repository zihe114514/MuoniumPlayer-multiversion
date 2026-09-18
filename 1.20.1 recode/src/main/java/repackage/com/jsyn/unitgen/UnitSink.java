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
import repackage.com.softsynth.shared.time.TimeStamp;

/**
 * 具有输入的单元发生器的接口。
 * 
 * @author Phil Burk, (C) 2009 Mobileer Inc
 */
public interface UnitSink {
    UnitInputPort getInput();

    /**
     * 播放速率的缩放因子。标称值为 1.0。
     * 从它连接的端口。
     */
    void start();

    void start(TimeStamp timeStamp);

    void stop();

    void stop(TimeStamp timeStamp);

    UnitGenerator getUnitGenerator();
}
