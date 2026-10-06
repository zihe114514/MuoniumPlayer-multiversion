package com.muoniumplayer.core.ncm.music.audio;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.EXTThreadLocalContext;

import repackage.com.jsyn.devices.AudioDeviceInputStream;
import repackage.com.jsyn.devices.AudioDeviceManager;
import repackage.com.jsyn.devices.AudioDeviceOutputStream;

/**
 * JSyn 的 OpenAL 输出设备——安卓上的音频兜底（自备设备 + 线程局部上下文）。
 *
 * <h2>为什么必须用线程局部上下文（真机两轮换来的结论）</h2>
 * 安卓 JRE 的 {@code javax.sound.sampled} 没有任何 Mixer，启动器又不都提供 MioJSound
 * 这类桥，所以模组要自己开 OpenAL 输出。自己开设备这件事本身没问题——参照工程
 * modernMP 在同一台手机上就是这么干的——<b>坑在上下文绑定</b>：
 * 这类启动器的 libopenal 里 {@code alcMakeContextCurrent} 是<b>进程级全局指针</b>，
 * 我们把它指到自己的上下文之后，Minecraft 的声音线程会把它抢回它自己的上下文
 * （modernMP 的原话：「alcMakeContextCurrent lets Minecraft's sound thread steal
 * the process-wide context」）。于是我们的下一次 {@code alBufferData} 在
 * <b>MC 的上下文</b>里查我们刚生成的缓冲——句柄表里根本没有，LookupBuffer 空指针，
 * 整个游戏 SIGSEGV（hs_err_pid7862：崩在我们 flushOne 里的 alBufferData，
 * si_addr=0x10，正是空结构体偏移）。
 *
 * <p>解法是 modernMP 验证过的组合：
 * <ol>
 *   <li>{@code ALC.createCapabilities(device)} + {@code AL.createCapabilities(alcCaps)}
 *       —— LWJGL 的规定动作，之前漏了；</li>
 *   <li>设备支持 {@code ALC_EXT_thread_local_context} 时用
 *       {@code alcSetThreadContext}——线程局部绑定，MC 抢不到；</li>
 *   <li>不支持该扩展时退回 {@code alcMakeContextCurrent}，并且<b>每次用之前都重绑</b>
 *       （modernMP：「safe to call often」），把被抢走的窗口压到最小。</li>
 * </ol>
 *
 * <h2>线程与节拍</h2>
 * JSyn 的合成线程（EngineThread）是唯一的音频线程：start/write/stop 都在它上面，
 * 每次进函数先 bind。write 里 double→PCM16 转换与 {@code JavaSoundAudioDevice}
 * 逐字同口径；缓冲池「先取后还」，池空才等消费——那个等待就是合成线程的时钟，
 * 丢掉它 JSyn 会极速空转、听感变快放。
 */
public final class OpenALAudioDevice implements AudioDeviceManager {

    private static final String TAG = "[MuoniumPlayer/Audio] ";

    /** 流缓冲个数：4 个 × 2048 帧 ≈ 185ms 的排队深度，够 JVM 抖动而不至于延迟可闻。 */
    private static final int STREAM_BUFFERS = 4;
    private static final int FRAMES_PER_STREAM_BUFFER = 2048;
    /**
     * 环形等待里「队列迟迟不消费」的容忍上限；超时多半是音频设备没了（拔蓝牙耳机
     * 是最常见的触发方式），丢弃排队缓冲重试，把「永久卡死」降级成「咔哒一声后续播」。
     */
    private static final long FORCE_RECOVER_AFTER_NANOS = 5_000_000_000L;

    private final long device;
    private final long context;
    private final boolean useThreadLocalContext;

    private OpenALAudioDevice(long device, long context, boolean useThreadLocalContext) {
        this.device = device;
        this.context = context;
        this.useThreadLocalContext = useThreadLocalContext;
    }

    /**
     * 开自己的 ALC 设备与上下文；任何一步失败都返回 null，调用方退回原有失败路径
     * （「无音频设备」），绝不抛出去炸掉播放线程。
     *
     * <p>注意：这一步<b>不做</b> makeCurrent——绑定是每个使用线程自己的事，
     * 见 {@link #bindAlContext()}。
     */
    public static AudioDeviceManager createIfAvailable() {
        try {
            long device = ALC10.alcOpenDevice((ByteBuffer) null);
            if (device == 0) {
                System.out.println(TAG + "alcOpenDevice 返回空设备，OpenAL 兜底不可用");
                return null;
            }
            long context = ALC10.alcCreateContext(device, (IntBuffer) null);
            if (context == 0) {
                System.out.println(TAG + "alcCreateContext 失败，OpenAL 兜底不可用");
                ALC10.alcCloseDevice(device);
                return null;
            }
            // LWJGL 的规定动作：先 ALC 后 AL，各层的函数指针就位。
            // 漏掉 AL.createCapabilities 时若 MC 已为自己装过，指针恰好能用——
            // 但这是巧合不是契约，两条都要做。
            ALCCapabilities alcCaps = ALC.createCapabilities(device);
            AL.createCapabilities(alcCaps);
            boolean threadLocal = alcCaps.ALC_EXT_thread_local_context;
            System.out.println(TAG + "OpenAL 输出设备就绪（独立设备/上下文，绑定模式: "
                    + (threadLocal ? "thread-local" : "process-wide + 每次重绑") + "）");
            return new OpenALAudioDevice(device, context, threadLocal);
        } catch (Throwable t) {
            System.out.println(TAG + "OpenAL 设备创建失败: " + t);
            return null;
        }
    }

    /**
     * 把我们的上下文绑定为「当前」。必须在使用 AL 之前调，而且<b>每次都调</b>：
     * 走 makeCurrent 回退时，MC 的声音线程随时会把进程级指针抢回去。
     */
    private void bindAlContext() {
        if (useThreadLocalContext) {
            EXTThreadLocalContext.alcSetThreadContext(context);
        } else {
            ALC10.alcMakeContextCurrent(context);
        }
    }

    // ------------------------------------------------------------------
    // AudioDeviceManager
    // ------------------------------------------------------------------

    @Override
    public int getDeviceCount() {
        return 1;
    }

    @Override
    public String getDeviceName(int deviceID) {
        return "OpenAL (MuoniumPlayer)";
    }

    @Override
    public String getName() {
        return "OpenAL";
    }

    @Override
    public int getDefaultInputDeviceID() {
        return -1;
    }

    @Override
    public int getDefaultOutputDeviceID() {
        return 0;
    }

    @Override
    public int getMaxInputChannels(int deviceID) {
        return 0;
    }

    @Override
    public int getMaxOutputChannels(int deviceID) {
        return 2;
    }

    @Override
    public double getDefaultLowInputLatency(int deviceID) {
        return 0.10;
    }

    @Override
    public double getDefaultHighInputLatency(int deviceID) {
        return 0.20;
    }

    @Override
    public double getDefaultLowOutputLatency(int deviceID) {
        return 0.04;
    }

    @Override
    public double getDefaultHighOutputLatency(int deviceID) {
        return 0.20;
    }

    @Override
    public int setSuggestedInputLatency(double latency) {
        return 0;
    }

    @Override
    public int setSuggestedOutputLatency(double latency) {
        return 0;
    }

    @Override
    public AudioDeviceOutputStream createOutputStream(int deviceID, int frameRate,
                                                      int samplesPerFrame) {
        if (samplesPerFrame != 1 && samplesPerFrame != 2) {
            throw new IllegalArgumentException("OpenAL 设备只支持 1/2 声道，收到 " + samplesPerFrame);
        }
        return new Stream(frameRate, samplesPerFrame);
    }

    @Override
    public AudioDeviceInputStream createInputStream(int deviceID, int frameRate,
                                                    int samplesPerFrame) {
        throw new UnsupportedOperationException("MuoniumPlayer 的 OpenAL 设备只支持输出，没有录音");
    }

    // ------------------------------------------------------------------
    // 输出流：一个流 = 一个 AL source + 一圈排队缓冲，全部 AL 调用先 bind
    // ------------------------------------------------------------------

    private final class Stream implements AudioDeviceOutputStream {

        private final int frameRate;
        private final int samplesPerFrame;
        private final int streamBufferSamples;
        private final IntBuffer queueScratch = BufferUtils.createIntBuffer(1);
        private final IntBuffer scratch = BufferUtils.createIntBuffer(1);
        /** PCM16 暂存。必须是 direct buffer：这类构建上 heap buffer 传 JNI 不可靠。 */
        private final ShortBuffer pcmBuffer = BufferUtils.createShortBuffer(FRAMES_PER_STREAM_BUFFER * 2);
        /** 跨调用累加的 PCM16 交错采样（引擎每次写入不足一个流缓冲）。 */
        private short[] staging = new short[FRAMES_PER_STREAM_BUFFER * 2];
        private int stagedSamples;

        /** 空闲缓冲池：非负之前 freeCount 永远指向下一个可取的位置（栈）。 */
        private final int[] freeBuffers = new int[STREAM_BUFFERS];
        private int freeCount;
        private int queuedBuffers;

        /** AL 的 source/buffer 句柄是 int（ALC 的设备与上下文才是指针 long）。 */
        private int source;
        private long stallNanos;
        /** AL 侧彻底不可用之后置位：write 从此直接丢弃输入，不能无限增长。 */
        private boolean broken;
        private boolean stallLogged;

        Stream(int frameRate, int samplesPerFrame) {
            this.frameRate = frameRate;
            this.samplesPerFrame = samplesPerFrame;
            // 字段初始化器拿不到构造参数，streamBufferSamples 必须在构造体里算。
            this.streamBufferSamples = FRAMES_PER_STREAM_BUFFER * samplesPerFrame;
        }

        @Override
        public void start() {
            bindAlContext();
            if (source == 0) {
                int[] sources = new int[1];
                AL10.alGenSources(sources);
                source = sources[0];
                int[] buffers = new int[STREAM_BUFFERS];
                AL10.alGenBuffers(buffers);
                freeCount = 0;
                for (int b : buffers) {
                    freeBuffers[freeCount++] = b;
                }
                AL10.alSourcef(source, AL10.AL_GAIN, 1.0f);
            }
            AL10.alSourceStop(source);
            drainQueued();
            queuedBuffers = 0;
        }

        @Override
        public void write(double value) {
            // 效率极低，仅补齐接口；引擎实际只走数组版本。
            write(new double[] {value}, 0, 1);
        }

        @Override
        public void write(double[] buffer) {
            write(buffer, 0, buffer.length);
        }

        @Override
        public void write(double[] buffer, int start, int count) {
            if (broken || count <= 0) {
                return;
            }
            bindAlContext();
            // 引擎每次写入不足一个流缓冲：先转 PCM16 累加进 staging，攒满才喂 OpenAL。
            if (stagedSamples + count > staging.length) {
                short[] grown = new short[Math.max(stagedSamples + count, streamBufferSamples * 2)];
                System.arraycopy(staging, 0, grown, 0, stagedSamples);
                staging = grown;
            }
            // 与 JavaSoundAudioDevice.write 同一条转换公式，逐采样 LittleEndian PCM16。
            for (int i = 0; i < count; i++) {
                double temp = (32767.0 * buffer[i + start]) + 32768.5;
                int sample = ((int) temp) - 32768;
                if (sample > Short.MAX_VALUE) {
                    sample = Short.MAX_VALUE;
                } else if (sample < Short.MIN_VALUE) {
                    sample = Short.MIN_VALUE;
                }
                staging[stagedSamples++] = (short) sample;
            }
            // 攒满一个流缓冲才交给 OpenAL；池空时等待已消费——那个等待是合成线程的节拍。
            while (stagedSamples >= streamBufferSamples) {
                if (freeCount == 0) {
                    long waitedNanos = 0;
                    while (AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED) == 0) {
                        if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                            giveUp();
                            return;
                        }
                        if (waitedNanos >= FORCE_RECOVER_AFTER_NANOS) {
                            forceRecover();
                            waitedNanos = 0;
                            continue;
                        }
                        try {
                            // 一个流缓冲 ≈ 46ms，最多等几个队列周期；interrupt（stop 请求）时让出。
                            Thread.sleep(2);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        waitedNanos += 2_000_000L;
                    }
                    scratch.clear();
                    AL10.alSourceUnqueueBuffers(source, scratch);
                    int recycled = scratch.get(0);
                    if (recycled == 0 || freeCount >= STREAM_BUFFERS) {
                        continue;
                    }
                    freeBuffers[freeCount++] = recycled;
                }
                int outBuffer = freeBuffers[--freeCount];
                pcmBuffer.clear();
                for (int i = 0; i < streamBufferSamples; i++) {
                    pcmBuffer.put(staging[i]);
                }
                pcmBuffer.flip();
                AL10.alBufferData(outBuffer, format(), pcmBuffer, frameRate);
                queueScratch.clear();
                queueScratch.put(outBuffer).flip();
                AL10.alSourceQueueBuffers(source, queueScratch);
                System.arraycopy(staging, streamBufferSamples, staging, 0,
                        stagedSamples - streamBufferSamples);
                stagedSamples -= streamBufferSamples;
                queuedBuffers++;
                // 攒到两个缓冲（≈93ms）才起播；被耗尽或经历恢复后自动续播。
                if (queuedBuffers >= 2
                        && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
                    AL10.alSourcePlay(source);
                }
            }
        }

        /**
         * 消费端长时间不吐缓冲：设备多半已经没了（拔蓝牙耳机是最常见的触发方式）。
         * 停掉 source 让所有在队缓冲变成「已消费」，全部收回池里，把「永久卡死」
         * 降级成「咔哒一声后尝试续播」。
         */
        private void forceRecover() {
            if (!stallLogged) {
                stallLogged = true;
                System.out.println(TAG + "OpenAL 输出长时间无消费（设备可能已断开），"
                        + "丢弃排队缓冲重试");
            }
            AL10.alSourceStop(source);
            int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
            for (int i = 0; i < processed; i++) {
                scratch.clear();
                AL10.alSourceUnqueueBuffers(source, scratch);
                int buffer = scratch.get(0);
                if (buffer != 0 && freeCount < STREAM_BUFFERS) {
                    freeBuffers[freeCount++] = buffer;
                    queuedBuffers--;
                }
            }
        }

        private void drainQueued() {
            int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
            for (int i = 0; i < processed; i++) {
                scratch.clear();
                AL10.alSourceUnqueueBuffers(source, scratch);
                int buffer = scratch.get(0);
                if (buffer != 0 && freeCount < STREAM_BUFFERS) {
                    freeBuffers[freeCount++] = buffer;
                    queuedBuffers--;
                }
            }
        }

        private void giveUp() {
            broken = true;
            if (!stallLogged) {
                stallLogged = true;
                System.out.println(TAG + "OpenAL 输出异常，本会话的音乐输出已停");
            }
        }

        @Override
        public void stop() {
            if (source == 0) {
                return;
            }
            bindAlContext();
            AL10.alSourceStop(source);
            drainQueued();
            queuedBuffers = 0;
        }

        @Override
        public double getLatency() {
            return (double) (queuedBuffers * FRAMES_PER_STREAM_BUFFER) / frameRate;
        }

        /** JavaSound 的 close 只关线不删对象，这里同理：流对象随引擎生命周期复用。 */
        @Override
        public void close() {
        }

        private int format() {
            return samplesPerFrame == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
        }
    }
}
