package com.muoniumplayer.core.ncm.music.audio;

import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.EXTThreadLocalContext;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.openal.SOFTLoopback;
import org.lwjgl.openal.SOFTHRTF;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * 世界声源的 OpenAL 渲染总线:湿声不再自研,交给 OpenAL Soft 原生的
 * EAX Reverb 效果器 —— 参数(密度/扩散/RT60/早期晚期反射/回声)由声学探针
 * 的射线场推导(照 USS-Shenzhou/Channel 的参数化思路),AL 自己做距离衰减、
 * 3D 声像与混响渲染,渲染结果经 {@code ALC_SOFT_loopback} 读回 PCM,
 * 由引擎尾槽混进输出 —— <b>与实际输出设备无关</b>(JavaSound 主路径同样有效)。
 *
 * <h2>设备纪律(血泪约定)</h2>
 * <p>这是本进程的<b>第二个 ALC 设备</b>(第一个是 {@link OpenALAudioDevice} 的
 * 兜底设备,第三个是 MC 自己的)。loopback 设备不占声卡,实测安卓(FCL)可正常创建。
 * 在部分启动器的 libopenal 上是 SIGSEGV(见 OpenAlAudioDeviceStaticTest 的类注释),
 * loopback 设备不占真实
 * 声卡,与 MC 的输出设备天然隔离。</p>
 *
 * <h2>信号流</h2>
 * <pre>
 * 引擎尾槽(SceneAcoustics)每缓冲:
 *   mono 干声(直流阻断后) ──→ alBufferData → source(3D:AL_POSITION=音箱位,
 *                              AL_DIRECT_FILTER=遮挡低通,AL_AUXILIARY_SEND→EAX)
 *   alcRenderSamplesSOFT ←── 立体声渲染结果(直射 3D + 混响)
 *   加回引擎输出(与 SceneAcoustics 的直射 M/S 链分工:这条总线管「湿声世界」)
 * </pre>
 *
 * <p>音频线程独占:write/参数更新全部发生在 JSyn 音频线程(尾槽回调),
 * AL 上下文用 {@code alcSetThreadContext} 绑<b>线程局部</b> —— 绝不碰
 * {@code alcMakeContextCurrent}(2026-11-04 用户实机复盘:2.4.0 在 init 里先清空
 * 再绑进程级指针,失败路径不恢复,MC 声音线程从此拿不到当前上下文,一首歌放完
 * 游戏全哑;主界面 Watermedia 的 ALEngine 同因建不出来,视频背景丢失)。
 * 全程 try/Throwable,任何失败永久降级到自研湿声链,绝不带崩播放;
 * {@code -Dmuonium.acoustics.eax=false} 可一键下线总线(照 muonium.audio.openal 先例)。</p>
 */
public final class OpenAlReverbBus {

    private static final String TAG = "[MuoniumPlayer/ReverbBus] ";

    /** 单例;null = 不可用(未初始化失败/loopback 扩展缺失)。 */
    private static volatile OpenAlReverbBus instance;

    private long device;
    private long context;
    private int source;
    private int directFilter;
    private int reverbSendFilter;
    private int reverbEffect;
    private int auxSlot;
    /** 立体声直连源:世界声源激活时承载干声的 S 分量(宽度),AL_ROLLOFF=0。 */
    private int sideSource;
    private int sideBuffers;
    private int sideBufferHead;
    private int[] sideBufferIds = new int[0];
    /** 读回的交错立体声(short,引擎采样率)。 */
    private ShortBuffer renderBuf;
    private int framesPerBuffer;
    /** render 首次喂入时启动 source(队列式播放,启动一次)。 */
    private boolean started;
    private boolean broken;

    private OpenAlReverbBus() {
    }

    /** 初始化;失败/不支持返回 null。 */
    public static OpenAlReverbBus createIfAvailable(int sampleRate, int framesPerBuffer) {
        if (instance != null) {
            return instance;
        }
        // 急停开关:哪台机器上总线另有古怪,-Dmuonium.acoustics.eax=false 一键退自研链,
        // 不用等发版(照 AudioPlayer 里 muonium.audio.openal 的先例)。
        if ("false".equalsIgnoreCase(System.getProperty("muonium.acoustics.eax", "true"))) {
            System.out.println(TAG + "已被 -Dmuonium.acoustics.eax=false 显式关闭,湿声走自研链");
            return null;
        }
        try {
            OpenAlReverbBus bus = new OpenAlReverbBus();
            if (!bus.init(sampleRate, framesPerBuffer)) {
                return null;
            }
            instance = bus;
            System.out.println(TAG + "EAX Reverb 总线就绪(loopback 设备, " + sampleRate + "Hz)");
            return bus;
        } catch (Throwable t) {
            System.out.println(TAG + "初始化失败,湿声回退自研链: " + t);
            return null;
        }
    }

    /** 当前总线;null = 不可用。 */
    public static OpenAlReverbBus get() {
        return instance;
    }

    private boolean init(int sampleRate, int framesPerBuffer) {
        // loopback 设备:不占声卡,渲染结果只能 alcRenderSamplesSOFT 读回。
        device = SOFTLoopback.alcLoopbackOpenDeviceSOFT((ByteBuffer) null);
        if (device == 0) {
            System.out.println(TAG + "alcLoopbackOpenDeviceSOFT 不可用(非 OpenAL Soft?)");
            return false;
        }
        // loopback 的输出格式在 createContext 的 attribs 里一次给全(LWJGL 3.3.x
        // 没有 alcDeviceAttributeSOFT 便捷口):频率/通道布局/采样类型 + 源数量。
        // HRTF(= MC 设置里的『定向音频』,ALC_HRTF_SOFT):OpenAL Soft 的 mhr
        // 频谱库给 3D source 的直射加上耳廓滤波 —— 前后/上下的音色区分,
        // 『真 3D』与『左右 pan』的分水岭。默认开(游戏同名设置默认关,
        // 我们的声学系统以空间感为核心卖点)。
        IntBuffer attrs = BufferUtils.createIntBuffer(18);
        attrs.put(ALC10.ALC_FREQUENCY).put(sampleRate)
                .put(SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT).put(SOFTLoopback.ALC_STEREO_SOFT)
                .put(SOFTLoopback.ALC_FORMAT_TYPE_SOFT).put(SOFTLoopback.ALC_SHORT_SOFT)
                .put(ALC11.ALC_MONO_SOURCES).put(1)
                .put(ALC11.ALC_STEREO_SOURCES).put(1)
                .put(SOFTHRTF.ALC_HRTF_SOFT).put(1)
                .put(0);
        attrs.flip();
        context = ALC10.alcCreateContext(device, attrs);
        if (context == 0) {
            ALC10.alcCloseDevice(device);
            device = 0;
            System.out.println(TAG + "loopback context 创建失败(格式不被支持)");
            return false;
        }
        ALCCapabilities alcCaps = ALC.createCapabilities(device);
        // 绑定纪律(2026-11-04 实机事故的唯一解):只走 alcSetThreadContext 线程局部,
        // 绝不碰 alcMakeContextCurrent —— 那是进程级全局指针,2.4.0 的 init 在这里
        // 先把它清 0 再绑 loopback,中间任何一步失败都不恢复;而 MC 的专设
        // Sound engine 线程正是靠那个进程级回退拿上下文,被清之后每一声
        // alGenSources 都是 AL_INVALID_OPERATION(实机日志 1154 条
        // 「Allocate new source: Invalid operation」,游戏全哑),主界面
        // Watermedia 的视频背景同因建不出 ALEngine。缺扩展就放弃总线。
        if (!alcCaps.ALC_EXT_thread_local_context) {
            releaseDevice();
            System.out.println(TAG + "缺 ALC_EXT_thread_local_context,EAX 总线下线(湿声走自研链)");
            return false;
        }
        long prevCtx = EXTThreadLocalContext.alcGetThreadContext();
        if (!EXTThreadLocalContext.alcSetThreadContext(context)) {
            releaseDevice();
            System.out.println(TAG + "alcSetThreadContext 失败,EAX 总线下线(湿声走自研链)");
            return false;
        }
        boolean ok = false;
        try {
            // AL 函数指针的解析需要「本线程有当前上下文」,必须排在绑定之后。
            AL.createCapabilities(alcCaps);
            if (!SOFTLoopback.alcIsRenderFormatSupportedSOFT(device, sampleRate,
                    SOFTLoopback.ALC_STEREO_SOFT, SOFTLoopback.ALC_SHORT_SOFT)) {
                System.out.println(TAG + "渲染格式(立体声 short @" + sampleRate + "Hz)不受支持");
                return false;
            }
            ok = initLocked(framesPerBuffer);
            return ok;
        } finally {
            // init 收尾把线程局部还原成进入前的状态(桌面通常是 0;安卓上同一根
            // JSyn 线程可能还持着 OpenALAudioDevice 的输出上下文,它每次 write
            // 会自己重绑)。渲染与参数口每次进入都会重绑,这里不留绑定态。
            EXTThreadLocalContext.alcSetThreadContext(prevCtx);
            if (!ok) {
                releaseDevice();
            }
        }
    }

    /**
     * 绑定态下的实体创建(效果/源/滤波/缓冲)。只允许 {@link #init} 在
     * alcSetThreadContext 之后调用;返回 false = 渲染格式不受支持等可预期下线。
     */
    private boolean initLocked(int framesPerBuffer) {
        IntBuffer hrtfState = BufferUtils.createIntBuffer(1);
        ALC10.alcGetIntegerv(device, SOFTHRTF.ALC_HRTF_STATUS_SOFT, hrtfState);
        System.out.println(TAG + "HRTF 状态: " + hrtfState.get(0)
                + " (0=未启用 1=启用 2=被拒绝 3=必须格式不支持)");

        // EAX Reverb 效果 + 辅助槽
        reverbEffect = EXTEfx.alGenEffects();
        EXTEfx.alEffecti(reverbEffect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_EAXREVERB);
        auxSlot = EXTEfx.alGenAuxiliaryEffectSlots();
        EXTEfx.alAuxiliaryEffectSloti(auxSlot, EXTEfx.AL_EFFECTSLOT_EFFECT, reverbEffect);

        // 湿声发送源:mono,3D 定位在音箱位
        source = AL10.alGenSources();
        AL10.alSourcef(source, AL10.AL_GAIN, 1.0f);
        // 距离模型:INVERSE_DISTANCE_CLAMPED + ref=8m + rolloff=0.75 ——
        // 8m 内全增益(音箱本体的近场),之后 −4.5dB/倍距(游戏惯例,比
        // 纯 1/d 的 −6dB 缓一档):16m=0.57、32m=0.31、64m=0.16、96m=0.11
        // —— 96m 的音箱依然 -19dB 清晰可闻,160m 淡出。
        AL10.alDistanceModel(AL10.AL_INVERSE_DISTANCE_CLAMPED);
        AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0.75f);
        AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, 8.0f);
        AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_FALSE);
        // 直路低通(遮挡闷音)与湿路发送低通
        directFilter = EXTEfx.alGenFilters();
        EXTEfx.alFilteri(directFilter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
        EXTEfx.alFilterf(directFilter, EXTEfx.AL_LOWPASS_GAIN, 1f);
        EXTEfx.alFilterf(directFilter, EXTEfx.AL_LOWPASS_GAINHF, 1f);
        AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, directFilter);
        reverbSendFilter = EXTEfx.alGenFilters();
        EXTEfx.alFilteri(reverbSendFilter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
        EXTEfx.alFilterf(reverbSendFilter, EXTEfx.AL_LOWPASS_GAIN, 1f);
        AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, auxSlot, 0, reverbSendFilter);

        // 立体声直连源:承载干声 S 分量(宽度)。与 M 源同款距离模型 ——
        // 宽度也从音箱位置发出,必须吃同一份 1/d 衰减(此前 rolloff=0 零
        // 衰减,远距时 S 以 width 音量直连 = 「离很远还有伴奏底音」)。
        // rolloff=0 只在玩家贴头模式(setSourceRolloff 切)。
        sideSource = AL10.alGenSources();
        AL10.alSourcef(sideSource, AL10.AL_GAIN, 1.0f);
        AL10.alSourcef(sideSource, AL10.AL_ROLLOFF_FACTOR, 0.75f);
        AL10.alSourcef(sideSource, AL10.AL_REFERENCE_DISTANCE, 8.0f);
        AL10.alSourcei(sideSource, AL10.AL_LOOPING, AL10.AL_FALSE);
        AL10.alSourcei(sideSource, EXTEfx.AL_DIRECT_FILTER, 0);
        sideBuffers = 4;
        sideBufferIds = new int[sideBuffers];
        AL10.alGenBuffers(sideBufferIds);

        this.framesPerBuffer = framesPerBuffer;
        renderBuf = BufferUtils.createShortBuffer(framesPerBuffer * 2);
        return true;
    }

    /** 湿声总量(混响发送):0 = 静默,1 = 满发送。 */
    public void setSendGain(float gain) {
        if (broken || !bind()) {
            return;
        }
        try {
            EXTEfx.alFilterf(reverbSendFilter, EXTEfx.AL_LOWPASS_GAIN,
                    Math.max(0f, Math.min(1f, gain)) * 3f);
            AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, auxSlot, 0, reverbSendFilter);
        } catch (Throwable t) {
            broken("setSendGain", t);
        } finally {
            unbind();
        }
    }

    /** 3D 源参数:世界坐标(音箱)+ 直路遮挡增益/HF。 */
    public void setSourcePose(double x, double y, double z, float directGain, float directHf) {
        if (broken || !bind()) {
            return;
        }
        try {
            AL10.alSource3f(source, AL10.AL_POSITION, (float) x, (float) y, (float) -z);
            float g = Math.max(0f, Math.min(1f, directGain));
            EXTEfx.alFilterf(directFilter, EXTEfx.AL_LOWPASS_GAIN, g);
            EXTEfx.alFilterf(directFilter, EXTEfx.AL_LOWPASS_GAINHF,
                    Math.max(0f, Math.min(1f, directHf)));
            AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, directFilter);
        } catch (Throwable t) {
            broken("setSourcePose", t);
        } finally {
            unbind();
        }
    }

    /**
     * EAX Reverb 参数包(探针每 500ms 推一轮;NaN = 该项维持原值)。
     * 语义与 OpenAL Soft 的 EAXREVERB 一致。
     */
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
                EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_DECAY_TIME, clamp(rt60, 0.05f, 20f));
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
            EXTEfx.alAuxiliaryEffectSloti(auxSlot, EXTEfx.AL_EFFECTSLOT_EFFECT, reverbEffect);
        } catch (Throwable t) {
            broken("setReverbParams", t);
        } finally {
            unbind();
        }
    }

    /**
     * 听者位姿(世界坐标 + 朝向/上向):AL 距离衰减/声像/HRTF 的基准。
     * 音频线程每缓冲调一次。
     */
    public void setListener(double ex, double ey, double ez,
                            float fx, float fy, float fz,
                            float ux, float uy, float uz) {
        if (broken || !bind()) {
            return;
        }
        try {
            // MC→AL 手性转换:MC 的「面南 yaw=0」把前方指到 +Z,而 AL(右手系,
            // 前方惯例 -Z、+X 向右)对同一向量的叉乘得出与 MC 玩家右手相反的
            // 「右」—— 实机表现就是声像左右颠倒。坐标统一在总线边界做一次
            // Z 取反(位置与朝向、上向量都要),内部一切用 AL 语义。
            AL10.alListener3f(AL10.AL_POSITION, (float) ex, (float) ey, (float) -ez);
            AL10.alListenerfv(AL10.AL_ORIENTATION, new float[] {fx, fy, -fz, ux, uy, -uz});
        } catch (Throwable t) {
            broken("setListener", t);
        } finally {
            unbind();
        }
    }

    /** 3D 源的距离模型:1=物理线性(音箱),0=零衰减(玩家贴头)。 */
    public void setSourceRolloff(float rolloff) {
        if (broken || !bind()) {
            return;
        }
        try {
            AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, Math.max(0f, rolloff));
            // S 源同步(M 与 S 是同一音箱的两路,距离模型必须一致)
            AL10.alSourcef(sideSource, AL10.AL_ROLLOFF_FACTOR, Math.max(0f, rolloff));
        } catch (Throwable t) {
            broken("setSourceRolloff", t);
        } finally {
            unbind();
        }
    }

    /** S 直连源的位置(与 M 同一音箱;玩家模式=听者头部)。 */
    public void setSidePose(double x, double y, double z) {
        if (broken || !bind()) {
            return;
        }
        try {
            AL10.alSource3f(sideSource, AL10.AL_POSITION, (float) x, (float) y, (float) -z);
        } catch (Throwable t) {
            broken("setSidePose", t);
        } finally {
            unbind();
        }
    }

    /** S 直连源的音量(宽度收缩系数;0 = 关)。 */
    public void setSideGain(float gain) {
        if (broken || !bind()) {
            return;
        }
        try {
            AL10.alSourcef(sideSource, AL10.AL_GAIN, Math.max(0f, Math.min(1f, gain)));
        } catch (Throwable t) {
            broken("setSideGain", t);
        } finally {
            unbind();
        }
    }

    /**
     * 喂一缓冲湿声输入(mono short)+ 可选立体声直连输入(short 交错),
     * 读回完整渲染(直射 3D + S 直连 + 混响)到 {@code outLR}
     * (float,-1..1,长度 = frames×2)。调用方是音频线程。
     *
     * <p>同步契约:queue 后<b>循环渲染直到读满</b> —— alcRenderSamplesSOFT
     * 每次只推混器的一个步长,不保证一次给齐;EAX 网络是零延迟 IIR,喂多少
     * 最终读多少,AL 内部的混响延迟线状态跨调用保持。</p>
     */
    public void render(short[] monoIn, short[] sideStereoIn, float[] outLR) {
        if (broken || monoIn == null || outLR == null || renderBuf == null) {
            return;
        }
        if (!bind()) {
            return;
        }
        try {
            int frames = Math.min(monoIn.length, framesPerBuffer);
            int buf = AL10.alGenBuffers();
            renderBuf.clear();
            renderBuf.put(monoIn, 0, frames).flip();
            AL10.alBufferData(buf, AL10.AL_FORMAT_MONO16, renderBuf, contextRate());
            AL10.alSourceQueueBuffers(source, buf);
            // S 直连:立体声交错喂 sideSource 的环形缓冲池
            if (sideStereoIn != null && sideBufferIds.length > 0) {
                int sb = sideBufferIds[sideBufferHead];
                sideBufferHead = (sideBufferHead + 1) % sideBufferIds.length;
                renderBuf.clear();
                renderBuf.put(sideStereoIn, 0, frames * 2).flip();
                AL10.alBufferData(sb, AL10.AL_FORMAT_STEREO16, renderBuf, contextRate());
                AL10.alSourceQueueBuffers(sideSource, sb);
            }
            if (!started) {
                AL10.alSourcePlay(source);
                if (sideStereoIn != null) {
                    AL10.alSourcePlay(sideSource);
                }
                started = true;
            }
            int produced = 0;
            ShortBuffer out = BufferUtils.createShortBuffer(frames * 2);
            while (produced < frames) {
                out.clear();
                SOFTLoopback.alcRenderSamplesSOFT(device, out, frames - produced);
                int got = out.position() == 0 ? frames - produced : out.position() / 2;
                out.flip();
                for (int i = 0; i < got && produced + i < frames; i++) {
                    outLR[(produced + i) * 2] = out.get(i * 2) / 32768f;
                    outLR[(produced + i) * 2 + 1] = out.get(i * 2 + 1) / 32768f;
                }
                produced += got;
                if (got <= 0) {
                    break; // 渲染器拒绝推进:丢弃本帧剩余,下个缓冲继续
                }
            }
            // 回收已消费的队列缓冲(mono 源的临时缓冲直接删;side 池循环复用)
            int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
            while (processed-- > 0) {
                int done = AL10.alSourceUnqueueBuffers(source);
                AL10.alDeleteBuffers(done);
            }
            int sideProcessed = AL10.alGetSourcei(sideSource, AL10.AL_BUFFERS_PROCESSED);
            while (sideProcessed-- > 0) {
                AL10.alSourceUnqueueBuffers(sideSource);
            }
        } catch (Throwable t) {
            broken("render", t);
        } finally {
            unbind();
        }
    }

    private int contextRate() {
        IntBuffer buf = BufferUtils.createIntBuffer(1);
        ALC10.alcGetIntegerv(device, ALC10.ALC_FREQUENCY, buf);
        return Math.max(8000, buf.get(0));
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 任何失败 = 总线永久下线,湿声回退自研链(与 OutputChain 的熔断同纪律)。 */
    private void broken(String where, Throwable t) {
        if (!broken) {
            broken = true;
            System.out.println(TAG + where + " 失败,总线永久下线: " + t);
        }
    }

    /**
     * 本线程线程局部绑上我们的上下文;每个 AL 入口先调。失败 = 总线永久下线。
     * 绝不碰 alcMakeContextCurrent:那是进程级全局指针,MC 的 Sound engine
     * 线程靠它拿上下文(2026-11-04 实机事故),线程局部绑定 MC 抢不走、我们也
     * 不动 MC 的。OpenALAudioDevice 同款纪律。
     */
    private boolean bind() {
        if (EXTThreadLocalContext.alcSetThreadContext(context)) {
            return true;
        }
        if (!broken) {
            broken = true;
            System.out.println(TAG + "alcSetThreadContext 失败,总线永久下线");
        }
        return false;
    }

    /**
     * 用完解绑:同一根 JSyn 音频线程还要继续喂主输出(OpenALAudioDevice 路径,
     * 它每次 write 自会重绑),不留越权绑定。
     */
    private void unbind() {
        EXTThreadLocalContext.alcSetThreadContext(0);
    }

    /** 销毁设备与上下文;只允许在本线程已解绑(或从未绑定)后调用。 */
    private void releaseDevice() {
        if (context != 0) {
            ALC10.alcDestroyContext(context);
            context = 0;
        }
        if (device != 0) {
            ALC10.alcCloseDevice(device);
            device = 0;
        }
    }
}
