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

import repackage.com.jsyn.devices.javasound.JavaSoundAudioDevice;

/**
 * 创建适合平台的设备。
 * 
 * @author Phil Burk (C) 2010 Mobileer Inc
 */
public class AudioDeviceFactory {
    private static AudioDeviceManager instance;

    /**
     * 使用自定义设备界面。覆盖默认设备管理器的选择。
     * 
     * @param instance
     */
    public static void setInstance(AudioDeviceManager instance) {
        AudioDeviceFactory.instance = instance;
    }

    /**
     * 尝试加载JPortAudio或JavaSound设备。
     * 
     * 此平台支持@return A设备。
     */
    public static AudioDeviceManager createAudioDeviceManager() {
        loadJavaSoundDevice();
        return instance;
    }

    private static void loadJavaSoundDevice() {
        if (instance == null) {
            try {
                instance = new JavaSoundAudioDevice();
            } catch (Throwable e) {
                System.err.println("Could not load JavaSound device. " + e);
            }
        }
    }

}
