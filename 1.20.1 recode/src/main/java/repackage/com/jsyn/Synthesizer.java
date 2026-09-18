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

package repackage.com.jsyn;

import repackage.com.jsyn.devices.AudioDeviceManager;
import repackage.com.jsyn.unitgen.ChannelOut;
import repackage.com.jsyn.unitgen.UnitGenerator;
import repackage.com.softsynth.shared.time.ScheduledCommand;
import repackage.com.softsynth.shared.time.TimeStamp;

/**
 * 供 JSyn 用来生成音频的合成器。该合成器执行一组单元生成器网络，
 * 生成器来创建音频信号。
 *
 * @author Phil Burk (C) 2010 Mobileer Inc
 */
public interface Synthesizer {

    int FRAMES_PER_BLOCK = 8;

    /**
     * 启动后台线程，以默认帧速率 44100 和双声道生成音频。
     * 立体声输出通道。
     */
    void start();

    /**
     * 启动使用指定帧速率和两个立体声生成音频的背景线程
     * å³å£°éåé¨æ¬å£°å¨
     *
     * @param frameRate 单位为赫兹
     */
    void start(int frameRate);

    /**
     * 使用指定的音频设备启动合成器。
     * <p>
     * 注意，使用超过 2 个声道很可能需要借助 JPortAudio，因为
     * JavaSound 目前不支持超过两个声道。
     * JPortAudio可在
     * <a href="http://www.softsynth.com/jsyn/developers/download.php"> http://www.softsynth.com/jsyn/developers/download.php</a>.
     * <p>
     * 或{@link ChannelOut} ，它可以与任何索引通道相关联。
     *
     * @param frameRate 单位为赫兹
     * 从{@link AudioDeviceManager}或PASS获得的@param inputDeviceID
     *            AudioDeviceManager.USE_DEFAULT_DEVICE
     * @param numInputChannels 0 表示无输入，1 为单声道，2 为立体声，依此类推
     * 从AudioDeviceManager或PASS获取的@param ouputDeviceID
     *            AudioDeviceManager.USE_DEFAULT_DEVICE
     * @param numOutputChannels 0 表示无输出，1 为单声道，2 为立体声，依此类推
     */
    void start(int frameRate, int inputDeviceID, int numInputChannels, int ouputDeviceID,
               int numOutputChannels);

    /** @return JSyn版本为字符串*/
    String getVersion();

    /** @return 版本为始终增加的整数*/
    int getVersionCode();

    /** 停止生成音频的后台线程。 */
    void stop();

    /**
     * AudioDeviceManager是音频硬件的接口。它可以通过以下方式实现：
     * JavaSound或PortAudio周围的包装器。
     *
     * @return 合成器当前使用的音频设备管理器。
     */
    AudioDeviceManager getAudioDeviceManager();

    /** @return 以采样每秒为单位的帧速率*/
    int getFrameRate();

    /**
     * 将单位生成器添加到合成器，以便可以播放。这在开始之前是必需的
     * 或将单元生成器接入网络。
     *
     * @param ugen 由合成器执行的单元发生器
     */
    void add(UnitGenerator ugen);

    /** 移除通过 add() 添加的单元生成器。 */
    void remove(UnitGenerator ugen);

    /** @return 当前音频时间（以秒为单位）*/
    double getCurrentTime();

    /**
     * 在指定时间启动单元生成器。若某个单元生成器的输出
     * 已连接到其他单元，则无需此调用。通常你只需启动没有输出的单元，例如
     * 示例LineOut或ChannelOut。
     */
    void startUnit(UnitGenerator unit, double time);

    void startUnit(UnitGenerator unit, TimeStamp timeStamp);

    /**
     * StartUnit和stopUnit方法主要供内部使用。
     * 请改为调用 unit.start() 或 unit.stop()。
     * @param unit
     */
    void startUnit(UnitGenerator unit);

    void stopUnit(UnitGenerator unit, double time);

    void stopUnit(UnitGenerator unit, TimeStamp timeStamp);

    /**
     * StartUnit和stopUnit方法主要供内部使用。
     * 请改为调用 unit.start() 或 unit.stop()。
     * @param unit
     */
    void stopUnit(UnitGenerator unit);

    /**
     * 睡眠，直到达到指定的音频时间。在非实时模式下，这将启用
     * 合成器运行。
     */
    void sleepUntil(double time) throws InterruptedException;

    /**
     * 睡眠指定的音频时长。在非实时模式下，这将使
     * 合成器运行。
     */
    void sleepFor(double duration) throws InterruptedException;

    /**
     * 如果设置为true ，则合成器将实时生成音频。将其设置为真直播
     * audio。如果为false ，则JSyn将在非实时模式下运行。这可用于生成音频
     * 写入文件。默认值为true。
     *
     * @param realTime
     */
    void setRealTime(boolean realTime);

    /** JSyn是否在实时模式下运行？*/
    boolean isRealTime();

    /** 用当前音频时间创建 TimeStamp。 */
    TimeStamp createTimeStamp();

    /** @return 当前 CPU 占用率，取值介于 0.0 与 1.0 之间 */
    double getUsage();

    /** @return frameRate 的倒数，以避免开销较大的除法 */
    double getFramePeriod();

    /**
     * 如果您停止并重新启动，此计数不会重置。
     *
     * @return 已合成的帧数
     */
    long getFrameCount();

    /** 在后台音频线程中的特定时间将要处理的命令排入队列。*/
    void scheduleCommand(TimeStamp timeStamp, ScheduledCommand command);

    /** 在后台音频线程中的特定时间将要处理的命令排入队列。*/
    void scheduleCommand(double time, ScheduledCommand command);

    /** 将要在后台音频线程中尽快处理的命令排入队列。*/
    void queueCommand(ScheduledCommand command);

    /**
     * 清除队列中的所有计划命令。
     * 命令将被丢弃。
     */
    void clearCommandQueue();

    /**
     * 如果合成器已启动，则@return 为true
     */
    boolean isRunning();

    /**
     * 添加将在音频线程生成每个新音频块之前在音频线程上重复运行的任务。
     * 该任务必须非常快，不应执行任何阻塞操作。若你不确定
     * 是否真的需要音频速率任务，就不要使用它。
     *
     * @param task
     */
    void addAudioTask(Runnable task);

    void removeAudioTask(Runnable task);

}
