/*
 * Copyright 2010 Phil Burk, Mobileer Inc
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

package repackage.com.jsyn.devices;

/**
 * 音频系统的接口。这可以使用JavaSound或本机设备实现
 * 包裹
 * 
 * @author Phil Burk (C) 2010 Mobileer Inc
 */
public interface AudioDeviceManager {
    /**
     * 将此值传递给start方法以请求默认设备ID。
     */
    int USE_DEFAULT_DEVICE = -1;

    /**
     * @return 可用设备的数量。
     */
    int getDeviceCount();

    /**
     * 获取音频设备的名称。
     * 
     * @param deviceID 取值在 0 到 deviceCount-1 之间的索引。
     * @return 可向用户显示的名称。
     */
    String getDeviceName(int deviceID);

    /**
     * @return 可显示给用户的设备管理器名称。
     */
    String getName();

    /**
     * 用户通常可以使用控制面板选择默认设备，该控制面板是
     * 操作系统。
     * 
     * @return 用户选择为默认的输入设备的ID。
     */
    int getDefaultInputDeviceID();

    /**
     * 用户通常可以使用控制面板选择默认设备，该控制面板是
     * 操作系统。
     * 
     * @return 用户默认选择的输出设备的ID。
     */
    int getDefaultOutputDeviceID();

    /**
     * @param deviceID
     * @return 该设备支持的最大声道数。
     */
    int getMaxInputChannels(int deviceID);

    /**
     * @param deviceID 介于0和numDevices-1之间的索引。
     * @return 该设备支持的最大声道数。
     */
    int getMaxOutputChannels(int deviceID);

    /**
     * 这是设备能够可靠支持的最低延迟。它应该用于
     * 要求低延迟的应用，例如吉他信号的实时处理。
     * 
     * @param deviceID 介于0和numDevices-1之间的索引。
     * @return 延迟（以秒为单位）。
     */
    double getDefaultLowInputLatency(int deviceID);

    /**
     * 这是设备可支持的最高延迟。建议将高延迟用于
     * 非时间敏感的应用，例如录音。
     * 
     * @param deviceID 介于0和numDevices-1之间的索引。
     * @return 延迟（以秒为单位）。
     */
    double getDefaultHighInputLatency(int deviceID);

    double getDefaultLowOutputLatency(int deviceID);

    double getDefaultHighOutputLatency(int deviceID);

    /**
     * 设置音频设备的延迟（以秒为单位）。如果设置为零，则DefaultLowLatency值
     * 将使用该设备。这只是一个建议，当
     * AudioDeviceInputStream已启动。
     **/
    int setSuggestedInputLatency(double latency);

    int setSuggestedOutputLatency(double latency);

    /**
     * 创建JSyn内部可用于输出音频数据的流。应用程序
     * 不应该直接调用这个。
     */
    AudioDeviceOutputStream createOutputStream(int deviceID, int frameRate, int numOutputChannels);

    /**
     * 创建JSyn内部可用于获取音频输入数据的流。
     * 应用程序不应直接调用此方法。
     */
    AudioDeviceInputStream createInputStream(int deviceID, int frameRate, int numInputChannels);

}
