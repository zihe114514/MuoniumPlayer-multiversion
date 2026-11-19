package com.muoniumplayer.core.ncm.music.audio;

import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;
import org.lwjgl.openal.ALCapabilities;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.openal.EXTThreadLocalContext;
import org.lwjgl.openal.SOFTHRTF;
import org.lwjgl.openal.SOFTLoopback;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 世界声源的独立 OpenAL Soft loopback 总线。
 *
 * <p>直射输入为 M 和交错的 (+S, -S)，由上游统一完成增益、低通和传播延迟。
 * M 使用中心 mono source；S 的两个声道分别送入音箱中心两侧的 mono source。
 * stereo source 默认不定位，而强制定位的 stereo source 会在非零距离合成 mono，
 * 都不能用来保留定位后的 S。三条源均关闭 AL 距离/空气衰减，避免重复扣减。</p>
 *
 * <p>创建可能加载原生 HRTF 数据，必须在后台调用。创建成功后原子发布；所有参数、
 * render、flush 和 close 由同一个音频 owner 串行调用。仅在确认旧 owner
 * 已终止后允许移交并清旧队列;旧实例不由创建线程销毁,
 * 调用方在音频缓冲边界交接并关闭自己持有的旧实例。</p>
 *
 * <p>只使用 ALC_EXT_thread_local_context；每次同时保存/恢复原生线程上下文及
 * LWJGL AL/ALC 能力表，绝不修改进程级 alcMakeContextCurrent。任何原生错误均
 * 熔断，调用方检查 isBroken() 后回退。安卓及显式急停仍不启用本总线。</p>
 */
public final class OpenAlReverbBus implements AutoCloseable {

    private static final String TAG = "[MuoniumPlayer/ReverbBus] ";
    private static final AtomicReference<OpenAlReverbBus> INSTANCE = new AtomicReference<>();
    private static final int SOURCE_COUNT = 3;
    private static final int BUFFERS_PER_SOURCE = 2;
    private static final double SIDE_HALF_WIDTH_METERS = 0.35;
    /** 已核对的 OpenAL Soft 直射 FIR/停止淡出/短整数输出管线的有界排空下限。 */
    private static final int HISTORY_DISCARD_MIN_FRAMES = 512;

    private final int sampleRate;
    private final int framesPerBuffer;
    private final AtomicReference<Thread> owner = new AtomicReference<>();
    private long device;
    private long context;
    private int source;
    private int sideLeftSource;
    private int sideRightSource;
    private int[] sources = new int[0];
    private int directFilter;
    private int reverbSendFilter;
    private int reverbEffect;
    private int auxSlot;
    private float sendGain;
    private boolean effectAttached;
    private int[][] bufferIds = new int[SOURCE_COUNT][0];
    private int[][] freeBuffers = new int[SOURCE_COUNT][0];
    private final int[] freeCounts = new int[SOURCE_COUNT];
    private ShortBuffer pcmBuf;
    private ShortBuffer renderBuf;
    private boolean started;
    private volatile boolean broken;
    private volatile boolean closed;
    private ALCCapabilities alcCaps;
    private ALCapabilities alCaps;
    private long previousThreadContext;
    private ALCCapabilities previousAlcCaps;
    private ALCapabilities previousAlCaps;
    private boolean bound;
    private double listenerX;
    private double listenerY;
    private double listenerZ;
    private final float[] listenerOrientation = new float[6];

    private OpenAlReverbBus(int sampleRate, int framesPerBuffer) {
        this.sampleRate = sampleRate;
        this.framesPerBuffer = framesPerBuffer;
    }

    /**
     * 后台初始化。只串行化创建，不锁音频 render；不同采样率先建好新实例再发布。
     * 失败不替换当前实例；已发布旧实例由音频 owner 关闭。
     */
    public static synchronized OpenAlReverbBus createIfAvailable(int sampleRate, int framesPerBuffer) {
        OpenAlReverbBus current = INSTANCE.get();
        if (current != null && !current.isBroken() && current.sampleRate == sampleRate
                && current.framesPerBuffer == framesPerBuffer) {
            return current;
        }
        if (sampleRate <= 0 || framesPerBuffer <= 0 || framesPerBuffer > Integer.MAX_VALUE / 2) {
            System.out.println(TAG + "无效的采样率/缓冲容量，总线不可用");
            return null;
        }
        if (com.muoniumplayer.render.skija.SkijaNatives.mappedPlatform().startsWith("android")) {
            System.out.println(TAG + "安卓不启用 loopback，声学回退自研链");
            return null;
        }
        if ("false".equalsIgnoreCase(System.getProperty("muonium.acoustics.eax", "true"))) {
            System.out.println(TAG + "已被 -Dmuonium.acoustics.eax=false 显式关闭");
            return null;
        }
        OpenAlReverbBus bus = new OpenAlReverbBus(sampleRate, framesPerBuffer);
        try {
            if (!bus.init()) {
                return null;
            }
            INSTANCE.set(bus);
            System.out.println(TAG + "HRTF 定位总线就绪(loopback, " + sampleRate + "Hz)");
            return bus;
        } catch (Throwable t) {
            bus.broken("init", t);
            bus.releaseDevice();
            return null;
        }
    }

    /** 原子发布的当前实例；调用方还须核对采样率与熔断状态。 */
    public static OpenAlReverbBus get() {
        return INSTANCE.get();
    }

    public int sampleRate() {
        return sampleRate;
    }

    public boolean isBroken() {
        return broken || closed;
    }

    public void markBroken() {
        broken("外部判定失效", new IllegalStateException("readback silence"));
    }

    private boolean init() {
        ALCCapabilities savedAlc = ALC.getCapabilities();
        ALCapabilities savedAl = currentAlCapabilities();
        long prevCtx = 0;
        boolean threadBound = false;
        boolean ok = false;
        try {
            ALCCapabilities globalCaps = ALC.createCapabilities(0);
            if (!globalCaps.ALC_SOFT_loopback) {
                System.out.println(TAG + "缺 ALC_SOFT_loopback，回退自研链");
                return false;
            }
            device = SOFTLoopback.alcLoopbackOpenDeviceSOFT((ByteBuffer) null);
            if (device == 0) {
                System.out.println(TAG + "loopback 设备创建失败，回退自研链");
                return false;
            }
            alcCaps = ALC.createCapabilities(device);
            if (!alcCaps.ALC_EXT_thread_local_context || !alcCaps.ALC_SOFT_HRTF
                    || !alcCaps.ALC_EXT_EFX) {
                System.out.println(TAG + "缺必要扩展: thread-local=" + alcCaps.ALC_EXT_thread_local_context
                        + ", HRTF=" + alcCaps.ALC_SOFT_HRTF + ", EFX=" + alcCaps.ALC_EXT_EFX);
                return false;
            }
            if (!SOFTLoopback.alcIsRenderFormatSupportedSOFT(device, sampleRate,
                    SOFTLoopback.ALC_STEREO_SOFT, SOFTLoopback.ALC_SHORT_SOFT)) {
                System.out.println(TAG + "不支持立体声 short @" + sampleRate + "Hz，回退自研链");
                return false;
            }
            checkAlc("render format");
            IntBuffer attrs = BufferUtils.createIntBuffer(13);
            attrs.put(ALC10.ALC_FREQUENCY).put(sampleRate)
                    .put(SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT).put(SOFTLoopback.ALC_STEREO_SOFT)
                    .put(SOFTLoopback.ALC_FORMAT_TYPE_SOFT).put(SOFTLoopback.ALC_SHORT_SOFT)
                    .put(ALC11.ALC_MONO_SOURCES).put(SOURCE_COUNT)
                    .put(ALC11.ALC_STEREO_SOURCES).put(0)
                    .put(SOFTHRTF.ALC_HRTF_SOFT).put(ALC10.ALC_TRUE)
                    .put(0).flip();
            context = ALC10.alcCreateContext(device, attrs);
            checkAlc("create context");
            if (context == 0) {
                System.out.println(TAG + "loopback context 创建失败，回退自研链");
                return false;
            }
            prevCtx = EXTThreadLocalContext.alcGetThreadContext();
            if (!EXTThreadLocalContext.alcSetThreadContext(context)) {
                System.out.println(TAG + "alcSetThreadContext 失败，回退自研链");
                return false;
            }
            threadBound = true;
            alCaps = AL.createCapabilities(alcCaps);
            int hrtfEnabled = ALC10.alcGetInteger(device, SOFTHRTF.ALC_HRTF_SOFT);
            int hrtfStatus = ALC10.alcGetInteger(device, SOFTHRTF.ALC_HRTF_STATUS_SOFT);
            checkAlc("HRTF status");
            System.out.println(TAG + "HRTF: enabled=" + (hrtfEnabled == ALC10.ALC_TRUE)
                    + ", status=" + hrtfStatusName(hrtfStatus));
            // 请求只是 hint；REQUIRED/HEADPHONES 也属于启用，不能只认 status == 1。
            if (hrtfEnabled != ALC10.ALC_TRUE) {
                System.out.println(TAG + "HRTF 未实际启用，定位总线不可用，回退自研链");
                return false;
            }
            if (ALC10.alcGetInteger(device, ALC10.ALC_FREQUENCY) != sampleRate
                    || ALC10.alcGetInteger(device, SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT)
                    != SOFTLoopback.ALC_STEREO_SOFT
                    || ALC10.alcGetInteger(device, SOFTLoopback.ALC_FORMAT_TYPE_SOFT)
                    != SOFTLoopback.ALC_SHORT_SOFT) {
                throw new IllegalStateException("loopback format differs from requested PCM format");
            }
            checkAlc("context format");
            ok = initLocked();
        } finally {
            if (threadBound) {
                if (!EXTThreadLocalContext.alcSetThreadContext(prevCtx)) {
                    EXTThreadLocalContext.alcSetThreadContext(0);
                    ok = false;
                    broken("init restore", new IllegalStateException("cannot restore thread context"));
                }
                // 之前没有 native TLS context 时也清 Java TLS，恢复进程能力表的动态回退。
                AL.setCurrentThread(prevCtx == 0 ? null : savedAl);
            }
            ALC.setCapabilities(savedAlc);
            if (!ok) {
                releaseDevice();
            }
        }
        return ok;
    }

    /** 仅在本线程已绑定自身 context 后创建实体，后台线程不取得音频 owner。 */
    private boolean initLocked() {
        reverbEffect = EXTEfx.alGenEffects();
        EXTEfx.alEffecti(reverbEffect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_EAXREVERB);
        auxSlot = EXTEfx.alGenAuxiliaryEffectSlots();
        // 当前上游湿声自研；零发送时不激活 AL 混响网络。
        EXTEfx.alAuxiliaryEffectSloti(auxSlot, EXTEfx.AL_EFFECTSLOT_EFFECT, 0);
        directFilter = EXTEfx.alGenFilters();
        EXTEfx.alFilteri(directFilter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
        EXTEfx.alFilterf(directFilter, EXTEfx.AL_LOWPASS_GAIN, 1f);
        EXTEfx.alFilterf(directFilter, EXTEfx.AL_LOWPASS_GAINHF, 1f);
        reverbSendFilter = EXTEfx.alGenFilters();
        EXTEfx.alFilteri(reverbSendFilter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
        EXTEfx.alFilterf(reverbSendFilter, EXTEfx.AL_LOWPASS_GAIN, 0f);
        EXTEfx.alFilterf(reverbSendFilter, EXTEfx.AL_LOWPASS_GAINHF, 1f);
        checkAl("create EFX");

        source = AL10.alGenSources();
        sideLeftSource = AL10.alGenSources();
        sideRightSource = AL10.alGenSources();
        sources = new int[] {source, sideLeftSource, sideRightSource};
        AL10.alDistanceModel(AL10.AL_NONE);
        for (int i = 0; i < sources.length; i++) {
            int s = sources[i];
            AL10.alSourcef(s, AL10.AL_GAIN, 1f);
            AL10.alSourcef(s, AL10.AL_ROLLOFF_FACTOR, 0f);
            AL10.alSourcef(s, EXTEfx.AL_AIR_ABSORPTION_FACTOR, 0f);
            AL10.alSourcef(s, EXTEfx.AL_ROOM_ROLLOFF_FACTOR, 0f);
            AL10.alSourcei(s, EXTEfx.AL_DIRECT_FILTER_GAINHF_AUTO, AL10.AL_FALSE);
            AL10.alSourcei(s, EXTEfx.AL_AUXILIARY_SEND_FILTER_GAIN_AUTO, AL10.AL_FALSE);
            AL10.alSourcei(s, EXTEfx.AL_AUXILIARY_SEND_FILTER_GAINHF_AUTO, AL10.AL_FALSE);
            AL10.alSourcei(s, AL10.AL_LOOPING, AL10.AL_FALSE);
            AL10.alSourcei(s, EXTEfx.AL_DIRECT_FILTER, directFilter);
            bufferIds[i] = new int[BUFFERS_PER_SOURCE];
            freeBuffers[i] = new int[BUFFERS_PER_SOURCE];
            AL10.alGenBuffers(bufferIds[i]);
            System.arraycopy(bufferIds[i], 0, freeBuffers[i], 0, BUFFERS_PER_SOURCE);
            freeCounts[i] = BUFFERS_PER_SOURCE;
        }
        AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, auxSlot, 0, reverbSendFilter);
        checkAl("create mono sources/buffers");
        pcmBuf = BufferUtils.createShortBuffer(framesPerBuffer);
        renderBuf = BufferUtils.createShortBuffer(framesPerBuffer * 2);
        return true;
    }

    /** EFX 发送增益为幅度比例 0..1，不允许把大于 1 的值写进 lowpass gain。 */
    public void setSendGain(float gain) {
        if (broken || !bind()) {
            return;
        }
        try {
            float g = finiteGain(gain);
            EXTEfx.alFilterf(reverbSendFilter, EXTEfx.AL_LOWPASS_GAIN, g);
            AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, auxSlot, 0, reverbSendFilter);
            sendGain = g;
            updateEffectAttachment();
            checkAl("setSendGain");
        } catch (Throwable t) {
            broken("setSendGain", t);
        } finally {
            unbind();
        }
    }

    /**
     * 世界坐标与三条直射路一致的 EFX 幅度/HF 比例；directHf 不是 Hz 截止。
     * 上游已完成完整直射滤波时必须传 1/1。
     */
    public void setSourcePose(double x, double y, double z, float directGain, float directHf) {
        if (broken || !bind()) {
            return;
        }
        try {
            AL10.alSource3f(source, AL10.AL_POSITION,
                    (float) (x - listenerX), (float) (y - listenerY), (float) (z - listenerZ));
            EXTEfx.alFilterf(directFilter, EXTEfx.AL_LOWPASS_GAIN, finiteGain(directGain));
            EXTEfx.alFilterf(directFilter, EXTEfx.AL_LOWPASS_GAINHF, finiteGain(directHf));
            // EFX filter 是复制到 source 的参数，改 filter 后必须重新挂给每条路。
            for (int s : sources) {
                AL10.alSourcei(s, EXTEfx.AL_DIRECT_FILTER, directFilter);
            }
            checkAl("setSourcePose");
        } catch (Throwable t) {
            broken("setSourcePose", t);
        } finally {
            unbind();
        }
    }

    /** EAX 参数包；NaN 项保持原值，零发送时不启动 AL 湿声网络。 */
    public void setReverbParams(float density, float diffusion, float rt60, float decayHfRatio,
                                float erGain, float erDelay, float erPanX, float erPanY, float erPanZ,
                                float lrGain, float lrDelay, float lrPanX, float lrPanY, float lrPanZ,
                                float echoTime, float echoDepth) {
        if (broken || !bind()) {
            return;
        }
        try {
            if (!Float.isNaN(density)) {
                EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_DENSITY, clamp(density, 0f, 1f));
            }
            if (!Float.isNaN(diffusion)) {
                EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_DIFFUSION, clamp(diffusion, 0f, 1f));
            }
            if (!Float.isNaN(rt60)) {
                EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_DECAY_TIME, clamp(rt60, 0.1f, 20f));
            }
            if (!Float.isNaN(decayHfRatio)) {
                EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_DECAY_HFRATIO, clamp(decayHfRatio, 0.1f, 2f));
            }
            if (!Float.isNaN(erGain)) {
                EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_REFLECTIONS_GAIN, clamp(erGain, 0f, 3.16f));
            }
            if (!Float.isNaN(erDelay)) {
                EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_REFLECTIONS_DELAY, clamp(erDelay, 0f, 0.3f));
            }
            if (!Float.isNaN(erPanX)) {
                EXTEfx.alEffectfv(reverbEffect, EXTEfx.AL_EAXREVERB_REFLECTIONS_PAN,
                        new float[] {clamp(erPanX, -1f, 1f), clamp(erPanY, -1f, 1f), clamp(erPanZ, -1f, 1f)});
            }
            if (!Float.isNaN(lrGain)) {
                EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_LATE_REVERB_GAIN, clamp(lrGain, 0f, 10f));
            }
            if (!Float.isNaN(lrDelay)) {
                EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_LATE_REVERB_DELAY, clamp(lrDelay, 0f, 0.1f));
            }
            if (!Float.isNaN(lrPanX)) {
                EXTEfx.alEffectfv(reverbEffect, EXTEfx.AL_EAXREVERB_LATE_REVERB_PAN,
                        new float[] {clamp(lrPanX, -1f, 1f), clamp(lrPanY, -1f, 1f), clamp(lrPanZ, -1f, 1f)});
            }
            if (!Float.isNaN(echoTime)) {
                EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_ECHO_TIME, clamp(echoTime, 0.075f, 0.25f));
            }
            if (!Float.isNaN(echoDepth)) {
                EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_ECHO_DEPTH, clamp(echoDepth, 0f, 1f));
            }
            if (effectAttached) {
                EXTEfx.alAuxiliaryEffectSloti(auxSlot, EXTEfx.AL_EFFECTSLOT_EFFECT, reverbEffect);
            }
            checkAl("setReverbParams");
        } catch (Throwable t) {
            broken("setReverbParams", t);
        } finally {
            unbind();
        }
    }

    /** 直接使用 MC 世界轴和朝向;只反转 Z 会镜像 listener 的左右耳基。 */
    public void setListener(double ex, double ey, double ez,
                            float fx, float fy, float fz, float ux, float uy, float uz) {
        if (broken || !bind()) {
            return;
        }
        try {
            listenerX = ex;
            listenerY = ey;
            listenerZ = ez;
            listenerOrientation[0] = fx;
            listenerOrientation[1] = fy;
            listenerOrientation[2] = fz;
            listenerOrientation[3] = ux;
            listenerOrientation[4] = uy;
            listenerOrientation[5] = uz;
            // 在 double 中先以听者为原点,避免大世界坐标的 float 量化吞掉侧源间距。
            AL10.alListener3f(AL10.AL_POSITION, 0f, 0f, 0f);
            AL10.alListenerfv(AL10.AL_ORIENTATION, listenerOrientation);
            checkAl("setListener");
        } catch (Throwable t) {
            broken("setListener", t);
        } finally {
            unbind();
        }
    }

    /** 兼容入口：衰减由上游独占，参数不再开启 AL 的第二份距离衰减。 */
    public void setSourceRolloff(float rolloff) {
        if (broken || !bind()) {
            return;
        }
        try {
            for (int s : sources) {
                AL10.alSourcef(s, AL10.AL_ROLLOFF_FACTOR, 0f);
            }
            checkAl("setSourceRolloff");
        } catch (Throwable t) {
            broken("setSourceRolloff", t);
        } finally {
            unbind();
        }
    }

    /**
     * 两条 S mono 源位于中心两侧各 0.35m。使用源→耳水平向量的切线，
     * 水平距离近零时退世界 X 轴；转头只改变听者朝向，不旋转音箱端点。
     */
    public void setSidePose(double x, double y, double z) {
        if (broken || !bind()) {
            return;
        }
        try {
            double dx = listenerX - x;
            double dz = listenerZ - z;
            double distance = Math.hypot(dx, dz);
            double tx = distance > 1e-6 ? -dz / distance : 1.0;
            double tz = distance > 1e-6 ? dx / distance : 0.0;
            double ox = tx * SIDE_HALF_WIDTH_METERS;
            double oz = tz * SIDE_HALF_WIDTH_METERS;
            final double relativeX = x - listenerX;
            final double relativeY = y - listenerY;
            final double relativeZ = z - listenerZ;
            AL10.alSource3f(sideLeftSource, AL10.AL_POSITION,
                    (float) (relativeX + ox), (float) relativeY, (float) (relativeZ + oz));
            AL10.alSource3f(sideRightSource, AL10.AL_POSITION,
                    (float) (relativeX - ox), (float) relativeY, (float) (relativeZ - oz));
            checkAl("setSidePose");
        } catch (Throwable t) {
            broken("setSidePose", t);
        } finally {
            unbind();
        }
    }

    /** 两条 S 路同一幅度比例；上游已处理宽度/增益时传 1。 */
    public void setSideGain(float gain) {
        if (broken || !bind()) {
            return;
        }
        try {
            float g = finiteGain(gain);
            AL10.alSourcef(sideLeftSource, AL10.AL_GAIN, g);
            AL10.alSourcef(sideRightSource, AL10.AL_GAIN, g);
            checkAl("setSideGain");
        } catch (Throwable t) {
            broken("setSideGain", t);
        } finally {
            unbind();
        }
    }

    /** 兼容旧调用；复用大数组的调用方必须改用带真实 frames 的重载。 */
    public void render(short[] monoIn, short[] sideStereoIn, float[] outLR) {
        render(monoIn, sideStereoIn, outLR,
                monoIn == null ? 0 : Math.min(monoIn.length, framesPerBuffer));
    }

    /**
     * 输入的有效帧数必须显式匹配输出/输入容量，side 为交错 (+S, -S)，不再乘 2。
     * alcRenderSamplesSOFT 同步写满请求帧，LWJGL 不推进 NIO position，不能 flip
     * 或用 position 推测短读。保留一帧静音前置队列使 source 不在每块末尾 STOPPED，
     * 避免每块重启清 HRIR 历史；固定增加一采样帧延迟，无后续排队增长。
     */
    public void render(short[] monoIn, short[] sideStereoIn, float[] outLR, int frames) {
        if (frames < 0 || frames > framesPerBuffer || monoIn == null || monoIn.length < frames
                || outLR == null || outLR.length / 2 < frames
                || (sideStereoIn != null && sideStereoIn.length / 2 < frames)) {
            if (outLR != null) {
                Arrays.fill(outLR, 0f);
            }
            broken("render arguments", new IllegalArgumentException("invalid PCM frame count/capacity"));
            return;
        }
        Arrays.fill(outLR, 0, frames * 2, 0f);
        if (frames == 0 || broken || closed || !bind()) {
            return;
        }
        try {
            if (started) {
                for (int s : sources) {
                    if (AL10.alGetSourcei(s, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
                        flushLocked();
                        break;
                    }
                }
                checkAl("source state");
            }
            if (!started) {
                primeQueue();
            }
            for (int channel = 0; channel < sources.length; channel++) {
                pcmBuf.clear();
                for (int i = 0; i < frames; i++) {
                    pcmBuf.put(channel == 0 ? monoIn[i]
                            : sideStereoIn == null ? (short) 0 : sideStereoIn[i * 2 + channel - 1]);
                }
                pcmBuf.flip();
                queueBuffer(channel);
            }
            if (!started) {
                AL10.alSourcePlayv(sources);
                started = true;
            }
            checkAl("queue/play");
            renderBuf.clear().limit(frames * 2);
            SOFTLoopback.alcRenderSamplesSOFT(device, renderBuf, frames);
            checkAlc("render samples");
            checkAl("render samples");
            renderBuf.position(0).limit(frames * 2);
            for (int i = 0; i < frames * 2; i++) {
                outLR[i] = renderBuf.get(i) / 32768f;
            }
            recycleProcessed();
        } catch (Throwable t) {
            Arrays.fill(outLR, 0, frames * 2, 0f);
            broken("render", t);
        } finally {
            unbind();
        }
    }

    /**
     * 音频 owner 的切歌/seek 清态：停止并清空全部排队 PCM，断开 EFX 尾，
     * 有界丢弃至少 512 帧或 10ms 的原生直射历史。不会重建设备/context/HRTF。
     * 这不是任意厂商后端/任意非零混响尾的跨实现保证，需原生冲激回归验证。
     */
    public void flush() {
        if (broken || !bind()) {
            return;
        }
        try {
            flushLocked();
        } catch (Throwable t) {
            broken("flush", t);
        } finally {
            unbind();
        }
    }

    private void primeQueue() {
        updateEffectAttachment();
        for (int i = 0; i < sources.length; i++) {
            pcmBuf.clear();
            pcmBuf.put((short) 0).flip();
            queueBuffer(i);
        }
    }

    private void queueBuffer(int channel) {
        if (freeCounts[channel] == 0) {
            throw new IllegalStateException("loopback buffer pool exhausted");
        }
        int buffer = freeBuffers[channel][--freeCounts[channel]];
        AL10.alBufferData(buffer, AL10.AL_FORMAT_MONO16, pcmBuf, sampleRate);
        AL10.alSourceQueueBuffers(sources[channel], buffer);
        checkAl("buffer/queue");
    }

    private void recycleProcessed() {
        for (int i = 0; i < sources.length; i++) {
            int processed = AL10.alGetSourcei(sources[i], AL10.AL_BUFFERS_PROCESSED);
            while (processed-- > 0) {
                int buffer = AL10.alSourceUnqueueBuffers(sources[i]);
                if (buffer == 0 || freeCounts[i] >= BUFFERS_PER_SOURCE) {
                    throw new IllegalStateException("invalid recycled loopback buffer");
                }
                freeBuffers[i][freeCounts[i]++] = buffer;
            }
        }
        checkAl("unqueue processed");
    }

    private void flushLocked() {
        AL10.alSourceStopv(sources);
        checkAl("stop for flush");
        recycleProcessed();
        for (int s : sources) {
            if (AL10.alGetSourcei(s, AL10.AL_BUFFERS_QUEUED) != 0) {
                throw new IllegalStateException("PCM queue not empty after stop/unqueue");
            }
        }
        EXTEfx.alAuxiliaryEffectSloti(auxSlot, EXTEfx.AL_EFFECTSLOT_EFFECT, 0);
        effectAttached = false;
        checkAl("detach tail for flush");
        int remaining = Math.max(HISTORY_DISCARD_MIN_FRAMES, sampleRate / 100);
        while (remaining > 0) {
            int frames = Math.min(remaining, framesPerBuffer);
            renderBuf.clear().limit(frames * 2);
            SOFTLoopback.alcRenderSamplesSOFT(device, renderBuf, frames);
            checkAlc("discard history");
            remaining -= frames;
        }
        checkAl("flush history");
        started = false;
        pcmBuf.clear();
        renderBuf.clear();
    }

    private void updateEffectAttachment() {
        boolean attach = sendGain > 0f;
        if (attach != effectAttached) {
            EXTEfx.alAuxiliaryEffectSloti(auxSlot, EXTEfx.AL_EFFECTSLOT_EFFECT, attach ? reverbEffect : 0);
            effectAttached = attach;
        }
    }

    /** 由独占音频 owner 在缓冲边界关闭；不与 render 并发，不关闭其他实例或输出设备。 */
    @Override
    public void close() {
        if (closed || !bind(true)) {
            return;
        }
        try {
            if (sources.length > 0) {
                AL10.alSourceStopv(sources);
                AL10.alDeleteSources(sources);
            }
            if (auxSlot != 0) {
                EXTEfx.alDeleteAuxiliaryEffectSlots(auxSlot);
            }
            if (directFilter != 0) {
                EXTEfx.alDeleteFilters(directFilter);
            }
            if (reverbSendFilter != 0) {
                EXTEfx.alDeleteFilters(reverbSendFilter);
            }
            if (reverbEffect != 0) {
                EXTEfx.alDeleteEffects(reverbEffect);
            }
            for (int[] buffers : bufferIds) {
                if (buffers.length > 0) {
                    AL10.alDeleteBuffers(buffers);
                }
            }
            checkAl("close entities");
        } catch (Throwable t) {
            broken("close", t);
        } finally {
            unbind();
            closed = true;
            INSTANCE.compareAndSet(this, null);
            releaseDevice();
        }
    }

    private boolean bind() {
        return bind(false);
    }

    private boolean bind(boolean allowBroken) {
        if (closed || (!allowBroken && broken)) {
            return false;
        }
        Thread thread = Thread.currentThread();
        Thread previousOwner = owner.get();
        owner.compareAndSet(null, thread);
        final boolean transferred = previousOwner != null && previousOwner != thread
                && !previousOwner.isAlive() && owner.compareAndSet(previousOwner, thread);
        if (owner.get() != thread) {
            broken("audio owner", new IllegalStateException("OpenAL bus used by multiple audio threads"));
            return false;
        }
        try {
            previousAlcCaps = ALC.getCapabilities();
            previousAlCaps = currentAlCapabilities();
            ALC.setCapabilities(alcCaps);
            previousThreadContext = EXTThreadLocalContext.alcGetThreadContext();
            if (!EXTThreadLocalContext.alcSetThreadContext(context)) {
                ALC.setCapabilities(previousAlcCaps);
                broken("bind", new IllegalStateException("alcSetThreadContext failed"));
                return false;
            }
            bound = true;
            AL.setCurrentThread(alCaps);
            if (transferred && !allowBroken) {
                // isAlive=false 确认旧 owner 已终止,才允许接管;活线程始终不可并发使用。
                flushLocked();
            }
            return true;
        } catch (Throwable t) {
            if (bound) {
                unbind();
            } else if (previousAlcCaps != null) {
                ALC.setCapabilities(previousAlcCaps);
            }
            broken("bind", t);
            return false;
        }
    }

    private void unbind() {
        if (!bound) {
            return;
        }
        try {
            if (!EXTThreadLocalContext.alcSetThreadContext(previousThreadContext)) {
                EXTThreadLocalContext.alcSetThreadContext(0);
                broken("restore", new IllegalStateException("cannot restore thread context"));
            }
        } finally {
            AL.setCurrentThread(previousThreadContext == 0 ? null : previousAlCaps);
            ALC.setCapabilities(previousAlcCaps);
            previousAlCaps = null;
            previousAlcCaps = null;
            bound = false;
        }
    }

    /** 只在本线程不再绑定自身 context 时释放；必须使用该设备的 ALC 函数表。 */
    private void releaseDevice() {
        if (device == 0) {
            return;
        }
        ALCCapabilities saved = ALC.getCapabilities();
        try {
            if (alcCaps != null) {
                ALC.setCapabilities(alcCaps);
            }
            if (context != 0) {
                ALC10.alcDestroyContext(context);
                context = 0;
            }
            if (!ALC10.alcCloseDevice(device)) {
                System.out.println(TAG + "alcCloseDevice 失败，原生资源未确认释放");
            }
            device = 0;
        } catch (Throwable t) {
            broken("releaseDevice", t);
        } finally {
            ALC.setCapabilities(saved);
        }
    }

    private void checkAl(String operation) {
        int error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR) {
            throw new IllegalStateException(operation + ": AL error 0x" + Integer.toHexString(error));
        }
    }

    private void checkAlc(String operation) {
        int error = ALC10.alcGetError(device);
        if (error != ALC10.ALC_NO_ERROR) {
            throw new IllegalStateException(operation + ": ALC error 0x" + Integer.toHexString(error));
        }
    }

    private static ALCapabilities currentAlCapabilities() {
        try {
            return AL.getCapabilities();
        } catch (IllegalStateException absent) {
            return null;
        }
    }

    private static float finiteGain(float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("non-finite gain");
        }
        return clamp(value, 0f, 1f);
    }

    private static float clamp(float value, float lo, float hi) {
        if (Float.isNaN(value)) {
            return Math.max(lo, Math.min(hi, 0f));
        }
        return Math.max(lo, Math.min(hi, value));
    }

    private static String hrtfStatusName(int status) {
        switch (status) {
            case SOFTHRTF.ALC_HRTF_DISABLED_SOFT: return "disabled";
            case SOFTHRTF.ALC_HRTF_ENABLED_SOFT: return "enabled";
            case SOFTHRTF.ALC_HRTF_DENIED_SOFT: return "denied";
            case SOFTHRTF.ALC_HRTF_REQUIRED_SOFT: return "required";
            case SOFTHRTF.ALC_HRTF_HEADPHONES_DETECTED_SOFT: return "headphones";
            case SOFTHRTF.ALC_HRTF_UNSUPPORTED_FORMAT_SOFT: return "unsupported-format";
            default: return "unknown(" + status + ")";
        }
    }

    private void broken(String where, Throwable failure) {
        if (!broken) {
            broken = true;
            System.out.println(TAG + where + " 失败，定位总线下线: " + failure);
        }
    }
}
