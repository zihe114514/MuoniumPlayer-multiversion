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

package repackage.com.jsyn.engine;

import repackage.com.jsyn.JSyn;
import repackage.com.jsyn.Synthesizer;
import repackage.com.jsyn.devices.AudioDeviceFactory;
import repackage.com.jsyn.devices.AudioDeviceInputStream;
import repackage.com.jsyn.devices.AudioDeviceManager;
import repackage.com.jsyn.devices.AudioDeviceOutputStream;
import repackage.com.jsyn.unitgen.UnitGenerator;
import repackage.com.softsynth.shared.time.ScheduledCommand;
import repackage.com.softsynth.shared.time.ScheduledQueue;
import repackage.com.softsynth.shared.time.TimeStamp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

//TODO 解决HearDAHDSR的问题，其中“Rate” port.set未反映在旋钮中。发动机未运行。
//TODO 在网站上新增教程和文档
//DAHDSR上的TODO 自动停止
//TODO 测试/示例SequentialData queueOn和queueOff

//TODO 抽象设备界面，文件设备！
//TODO 测量线程切换同步，多核合成性能。使用4核专业版。
//TODO 优化正弦振荡器相位调制
//TODO 更多电路。
//TODO 直流阻断器
//TODO 摆动视野镜探头UI ，自动测距

/**
 * JSyn Synthesizer的内部实现。公共API位于Synthesizer接口中。此
 * 该类可能在内部被直接使用。
 *
 * @author Phil Burk (C) 2009 Mobileer Inc
 * @see Synthesizer
 */
public class SynthesisEngine implements Synthesizer {

    /** Read-only output observer. The observer must not block or mutate the supplied PCM buffer. */
    public interface OutputObserver {
        void observe(double[] interleaved, int channels, int sampleRate);
    }
    private volatile OutputObserver outputObserver;
    public void setOutputObserver(OutputObserver observer) { outputObserver = observer; }

    private void observeOutput() {
        OutputObserver observer = outputObserver;
        if (observer == null || outputBuffer == null) return;
        try {
            observer.observe(outputBuffer.interleavedBuffer, outputBuffer.blockBuffers.length, getFrameRate());
        } catch (Throwable failure) {
            // A visualizer must never reach the playback loop's error path or silence music.
            outputObserver = null;
        }
    }


    private final static int BLOCKS_PER_BUFFER = 8;
    private final static int FRAMES_PER_BUFFER = Synthesizer.FRAMES_PER_BLOCK * BLOCKS_PER_BUFFER;
    // 我实测 JavaSound 关闭设备耗时 1200 毫秒。
    private static final int MAX_THREAD_STOP_TIME = 2000;

    public static final int DEFAULT_FRAME_RATE = 44100;

    private final AudioDeviceManager audioDeviceManager;
    private EngineThread engineThread;
    private final ScheduledQueue<ScheduledCommand> commandQueue = new ScheduledQueue<>();

    private InterleavingBuffer inputBuffer;
    private InterleavingBuffer outputBuffer;
    private double inverseNyquist;
    private long frameCount;
    private boolean pullDataEnabled = true;
    private boolean useRealTime = true;
    private boolean started;
    private int frameRate = DEFAULT_FRAME_RATE;
    private double framePeriod = 1.0 / frameRate;

    // 添加到合成器的所有单元的列表。
    private final ArrayList<UnitGenerator> allUnitList = new ArrayList<>();
    // 运行单元列表。
    private final ArrayList<UnitGenerator> runningUnitList = new ArrayList<>();
    // 由于自动停止而停止的设备列表。
    private final ArrayList<UnitGenerator> stoppingUnitList = new ArrayList<>();

    // private int numOutputChannels;
    // private int numInputChannels;
    private final CopyOnWriteArrayList<Runnable> audioTasks = new CopyOnWriteArrayList<>();
    private double mOutputLatency;
    private double mInputLatency;
    /** 精确对应于-96 dB的分数。*/
    public static final double DB96 = (1.0 / 63095.73444801943);
    /** 约等于 -90.3 dB 的小数。定义为 S16 的 1 个比特。 */
    public static final double DB90 = (1.0 / (1 << 15));

    static Logger logger = Logger.getLogger(SynthesisEngine.class.getName());

    public SynthesisEngine(AudioDeviceManager audioDeviceManager) {
        this.audioDeviceManager = audioDeviceManager;
    }

    public SynthesisEngine() {
        this(AudioDeviceFactory.createAudioDeviceManager());
    }

    @Override
    public String getVersion() {
        return JSyn.VERSION;
    }

    @Override
    public int getVersionCode() {
        return JSyn.VERSION_CODE;
    }

    @Override
    public String toString() {
        return "JSyn " + JSyn.VERSION_TEXT;
    }

    public boolean isPullDataEnabled() {
        return pullDataEnabled;
    }

    /**
     * 如果设置为true ，则音频数据将从连接设备的输出端口拉出
     * 生成器。单元树中最末端的单元需要调用 start()。
     *
     * @param pullDataEnabled
     */
    public void setPullDataEnabled(boolean pullDataEnabled) {
        this.pullDataEnabled = pullDataEnabled;
    }

    private void setupAudioBuffers(int numInputChannels, int numOutputChannels) {
        inputBuffer = new InterleavingBuffer(FRAMES_PER_BUFFER, Synthesizer.FRAMES_PER_BLOCK,
                numInputChannels);
        outputBuffer = new InterleavingBuffer(FRAMES_PER_BUFFER, Synthesizer.FRAMES_PER_BLOCK,
                numOutputChannels);
    }

    public void terminate() {
    }

    static class InterleavingBuffer {
        private final double[] interleavedBuffer;
        ChannelBlockBuffer[] blockBuffers;

        InterleavingBuffer(int framesPerBuffer, int framesPerBlock, int samplesPerFrame) {
            interleavedBuffer = new double[framesPerBuffer * samplesPerFrame];
            // 为合成输出的每个声道分配缓冲区。
            blockBuffers = new ChannelBlockBuffer[samplesPerFrame];
            for (int i = 0; i < blockBuffers.length; i++) {
                blockBuffers[i] = new ChannelBlockBuffer(framesPerBlock);
            }
        }

        int deinterleave(int inIndex) {
            for (int jf = 0; jf < Synthesizer.FRAMES_PER_BLOCK; jf++) {
                for (ChannelBlockBuffer buffer : blockBuffers) {
                    buffer.values[jf] = interleavedBuffer[inIndex++];
                }
            }
            return inIndex;
        }

        int interleave(int outIndex) {
            for (int jf = 0; jf < Synthesizer.FRAMES_PER_BLOCK; jf++) {
                for (ChannelBlockBuffer buffer : blockBuffers) {
                    interleavedBuffer[outIndex++] = buffer.values[jf];
                }
            }
            return outIndex;
        }

        public double[] getChannelBuffer(int i) {
            return blockBuffers[i].values;
        }

        public void clear() {
            for (ChannelBlockBuffer blockBuffer : blockBuffers) {
                blockBuffer.clear();
            }
        }
    }

    static class ChannelBlockBuffer {
        private final double[] values;

        ChannelBlockBuffer(int framesPerBlock) {
            values = new double[framesPerBlock];
        }

        void clear() {
            Arrays.fill(values, 0.0f);
        }
    }

    @Override
    public void start() {
        // TODO 使用常量。
        start(DEFAULT_FRAME_RATE, -1, 0, -1, 2);
    }

    @Override
    public void start(int frameRate) {
        // TODO 使用常量。
        start(frameRate, -1, 0, -1, 2);
    }

    @Override
    public synchronized void start(int frameRate, int inputDeviceID, int numInputChannels,
            int outputDeviceID, int numOutputChannels) {
        if (started) {
            return;
        }

        this.frameRate = frameRate;
        this.framePeriod = 1.0 / frameRate;

        setupAudioBuffers(numInputChannels, numOutputChannels);

        inverseNyquist = 2.0 / frameRate;

        if (useRealTime) {
            engineThread = new EngineThread(inputDeviceID, numInputChannels,
                    outputDeviceID, numOutputChannels);
            logger.fine("Synth thread old priority = " + engineThread.getPriority());
            int engineThreadPriority = Thread.MAX_PRIORITY;
            engineThread.setPriority(engineThreadPriority);
            engineThread.setDaemon(true);
            logger.fine("Synth thread new priority = " + engineThread.getPriority());
            engineThread.start();
        }

        started = true;
    }

    @Override
    public boolean isRunning() {
        Thread thread = engineThread;
        return (thread != null) && thread.isAlive();
    }

    @Override
    public synchronized void stop() {
        if (!started) {
            logger.info("JSyn already stopped.");
            return;
        }

        if (useRealTime) {
            // 停止音频合成及所有单元。
            if (engineThread != null) {
                try {
                    // 立即中断，否则音频线程将等待音频 I/O。
                    engineThread.requestStop();
                    engineThread.join(MAX_THREAD_STOP_TIME);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        synchronized (runningUnitList) {
            runningUnitList.clear();
        }
        started = false;
    }

    private class EngineThread extends Thread
    {
        private AudioDeviceOutputStream audioOutputStream;
        private AudioDeviceInputStream audioInputStream;
        private volatile boolean go = true;

        EngineThread(int inputDeviceID, int numInputChannels,
            int outputDeviceID, int numOutputChannels) {
            if (numInputChannels > 0) {
                audioInputStream = audioDeviceManager.createInputStream(inputDeviceID, frameRate,
                        numInputChannels);
            }
            if (numOutputChannels > 0) {
                audioOutputStream = audioDeviceManager.createOutputStream(outputDeviceID,
                        frameRate, numOutputChannels);
            }
        }

        public void requestStop() {
            go = false;
            interrupt();
        }

        @Override
        public void run() {
            logger.fine("JSyn synthesis thread starting.");

            if (audioInputStream != null) {
                logger.fine("JSyn synthesis thread trying to start audio INPUT!");
                audioInputStream.start();
                mInputLatency = audioInputStream.getLatency();
                String msg = String.format("Input Latency in = %5.1f msec",
                        1000 * mInputLatency);
                logger.fine(msg);
            }
            if (audioOutputStream != null) {
                logger.fine("JSyn synthesis thread trying to start audio OUTPUT!");
                audioOutputStream.start();
                mOutputLatency = audioOutputStream.getLatency();
                String msg = String.format("Output Latency = %5.1f msec",
                        1000 * mOutputLatency);
                logger.fine(msg);
                // 在我们填充缓冲区的同时争取一点时间。
                try {
                    audioOutputStream.write(outputBuffer.interleavedBuffer);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            while (go) {
                try {

                    boolean throttled = false;
                    if (audioInputStream != null) {
                        // 当输入为空时，此调用将被阻止。
                        audioInputStream.read(inputBuffer.interleavedBuffer);
                        throttled = true;
                    }

                    runAudioTasks();
                    generateNextBuffer();

                    if (audioOutputStream != null) {
                        // 当输出已满时，此调用将阻塞。
                        audioOutputStream.write(outputBuffer.interleavedBuffer);
                        throttled = true;
                    }
                    if (!throttled && isRealTime()) {
                        Thread.sleep(2); // 避免空转并消耗 CPU
                    }

                } catch (Throwable e) {
                    e.printStackTrace();
//                go = false;

                }
            }

            logger.info("JSyn synthesis thread in finally code.");
            // 音频系统
            if (audioInputStream != null) {
                audioInputStream.stop();
            }
            if (audioOutputStream != null) {
                audioOutputStream.stop();
            }
            logger.fine("JSyn synthesis thread exiting.");
        }
    }

    private void runAudioTasks() {
        for (Runnable task : audioTasks) {
            task.run();
        }
    }

    // TODO 我们需要像在 JSyn1 中那样实现一个 sharedSleeper。
    public void generateNextBuffer() {
        int outIndex = 0;
        int inIndex = 0;
        for (int i = 0; i < BLOCKS_PER_BUFFER; i++) {
            if (inputBuffer != null) {
                inIndex = inputBuffer.deinterleave(inIndex);
            }

            TimeStamp timeStamp = createTimeStamp();
            // 请尝试将此内容放在这里，以便收到带有时间戳的事件
            // 稍后计划
            processScheduledCommands(timeStamp);
            clearBlockBuffers();
            synthesizeBuffer();

            if (outputBuffer != null) {
                outIndex = outputBuffer.interleave(outIndex);
            }
            frameCount += Synthesizer.FRAMES_PER_BLOCK;
        }
        observeOutput();
    }

    @Override
    public double getCurrentTime() {
        return frameCount * framePeriod;
    }

    @Override
    public TimeStamp createTimeStamp() {
        return new TimeStamp(getCurrentTime());
    }

    private void processScheduledCommands(TimeStamp timeStamp) {
        List<ScheduledCommand> timeList = commandQueue.removeNextList(timeStamp);

        while (timeList != null) {
            while (!timeList.isEmpty()) {
                ScheduledCommand command = timeList.remove(0);
                logger.fine("repackage/processing " + command + ", at time " + timeStamp.getTime());
                command.run();
            }
            // 获取给定时刻的下一组命令。
            timeList = commandQueue.removeNextList(timeStamp);
        }
    }

    @Override
    public void scheduleCommand(TimeStamp timeStamp, ScheduledCommand command) {
        if ((Thread.currentThread() == engineThread) && (timeStamp.getTime() <= getCurrentTime())) {
            command.run();
        } else {
            logger.fine("scheduling " + command + ", at time " + timeStamp.getTime());
            commandQueue.add(timeStamp, command);
        }
    }

    @Override
    public void scheduleCommand(double time, ScheduledCommand command) {
        TimeStamp timeStamp = new TimeStamp(time);
        scheduleCommand(timeStamp, command);
    }

    @Override
    public void queueCommand(ScheduledCommand command) {
        TimeStamp timeStamp = createTimeStamp();
        scheduleCommand(timeStamp, command);
    }

    @Override
    public void clearCommandQueue() {
        commandQueue.clear();
    }

    private void clearBlockBuffers() {
        outputBuffer.clear();
    }

    private void synthesizeBuffer() {
        synchronized (runningUnitList) {
            try {
                for (UnitGenerator unit : runningUnitList) {
                    if (pullDataEnabled) {
                        unit.pullData(getFrameCount(), 0, Synthesizer.FRAMES_PER_BLOCK);
                    } else {
                        unit.generate(0, Synthesizer.FRAMES_PER_BLOCK);
                    }
                }
                // 移除所有已自动停止的单元。
                for (UnitGenerator ugen : stoppingUnitList) {
                    runningUnitList.remove(ugen);
                    ugen.flattenOutputs();
                }
            } catch (ConcurrentModificationException ignored) {

            }
        }
        stoppingUnitList.clear();
    }

    public double[] getInputBuffer(int i) {
        try {
            return inputBuffer.getChannelBuffer(i);
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new RuntimeException("Audio Input not configured in start() method.");
        }
    }

    public double[] getOutputBuffer(int i) {
        try {
            return outputBuffer.getChannelBuffer(i);
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new RuntimeException("Audio Output not configured in start() method.");
        }
    }

    private void internalStopUnit(UnitGenerator unit) {
        synchronized (runningUnitList) {
            runningUnitList.remove(unit);
        }
        unit.flattenOutputs();
    }

    public void autoStopUnit(UnitGenerator unitGenerator) {
        synchronized (stoppingUnitList) {
            stoppingUnitList.add(unitGenerator);
        }
    }

    @Override
    public void startUnit(UnitGenerator unit, double time) {
        startUnit(unit, new TimeStamp(time));
    }

    @Override
    public void stopUnit(UnitGenerator unit, double time) {
        stopUnit(unit, new TimeStamp(time));
    }

    @Override
    public void startUnit(final UnitGenerator unit, TimeStamp timeStamp) {
        // 若它是某个 circuit 中的组件，则不要启动，因为它会
        // 由电路执行。
        if (unit.getCircuit() == null) {
            scheduleCommand(timeStamp, () -> internalStartUnit(unit));
        }
    }

    @Override
    public void stopUnit(final UnitGenerator unit, TimeStamp timeStamp) {
        scheduleCommand(timeStamp, () -> internalStopUnit(unit));
    }

    @Override
    public void startUnit(UnitGenerator unit) {
        startUnit(unit, createTimeStamp());
    }

    @Override
    public void stopUnit(UnitGenerator unit) {
        stopUnit(unit, createTimeStamp());
    }

    private void internalStartUnit(UnitGenerator unit) {
        // logger.info( "internalStartUnit " + unit + " with circuit " +
        // unit.getCircuit() );
        if (unit.getCircuit() == null) {
            synchronized (runningUnitList) {
                if (!runningUnitList.contains(unit)) {
                    runningUnitList.add(unit);
                }
            }
        }
        // else
        // {
        // logger.info(
        // "internalStartUnit detected race condition !!!! from old JSyn" + unit
        // + " with circuit " + unit.getCircuit() );
        // }
    }

    public double getInverseNyquist() {
        return inverseNyquist;
    }

    public double convertTimeToExponentialScaler(double duration) {
        // 计算缩放因子，使 scaler^frames = target/source
        double numFrames = duration * getFrameRate();
        return Math.pow(DB90, (1.0 / numFrames));
    }

    @Override
    public long getFrameCount() {
        return frameCount;
    }

    /**
     * @return 帧速率
     */
    @Override
    public int getFrameRate() {
        return frameRate;
    }

    /**
     * @return 效率帧率的倒数
     */
    @Override
    public double getFramePeriod() {
        return framePeriod;
    }

    /** 将短值转换为-1.0到近1.0范围内的双精度值。*/
    public static double convertShortToDouble(short sdata) {
        return (sdata * (1.0 / Short.MAX_VALUE));
    }

    /**
     * 将-1.0到近1.0范围内的双精度值转换为短精度值。双值被裁剪
     * 在转换之前。
     */
    public static short convertDoubleToShort(double d) {
        final double maxValue = ((double) (Short.MAX_VALUE - 1)) / Short.MAX_VALUE;
        if (d > maxValue) {
            d = maxValue;
        } else if (d < -1.0) {
            d = -1.0;
        }
        return (short) (d * Short.MAX_VALUE);
    }

    @Override
    public void addAudioTask(Runnable blockTask) {
        audioTasks.add(blockTask);
    }

    @Override
    public void removeAudioTask(Runnable blockTask) {
        audioTasks.remove(blockTask);
    }

    @Override
    public double getUsage() {
        // 使用temp ，这样我们就不必同步
        return 0.0;
    }

    @Override
    public AudioDeviceManager getAudioDeviceManager() {
        return audioDeviceManager;
    }

    @Override
    public void setRealTime(boolean realTime) {
        useRealTime = realTime;
    }

    @Override
    public boolean isRealTime() {
        return useRealTime;
    }

    public double getOutputLatency() {
        return mOutputLatency;
    }

    public double getInputLatency() {
        return mInputLatency;
    }

    @Override
    public void add(UnitGenerator ugen) {
        ugen.setSynthesisEngine(this);
        allUnitList.add(ugen);
    }

    @Override
    public void remove(UnitGenerator ugen) {
        allUnitList.remove(ugen);
    }

    @Override
    public void sleepUntil(double time) throws InterruptedException {
        double timeToSleep = time - getCurrentTime();
        while (timeToSleep > 0.0) {
            if (useRealTime) {
                long msecToSleep = (long) (1000 * timeToSleep);
                if (msecToSleep <= 0) {
                    msecToSleep = 1;
                }
                Thread.sleep(msecToSleep);
            } else {

                generateNextBuffer();
            }
            timeToSleep = time - getCurrentTime();
        }
    }

    @Override
    public void sleepFor(double duration) throws InterruptedException {
        sleepUntil(getCurrentTime() + duration);
    }

    public void printConnections() {
        if (pullDataEnabled) {
            for (UnitGenerator unit : runningUnitList) {
                unit.printConnections();
            }
        }

    }

}
