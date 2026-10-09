package com.muoniumplayer.core.viper;

import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.viper.dsp.ViPER;
import com.muoniumplayer.core.viper.dsp.ViPERParams;
import com.muoniumplayer.core.viper.dsp.WavReader;

import repackage.com.jsyn.engine.SynthesisEngine;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ViPER 主链的实时处理器：驱动 {@link ViPER} 全链引擎（ViPERDSP 的 Java 移植）。
 *
 * <p><b>处理时机</b>：由 JSyn 引擎线程在写完一个缓冲、写下设备<b>之前</b>调用
 * （{@link SynthesisEngine.OutputProcessor}）。调用把交织 double 缓冲转成 float、
 * 交给引擎（冻结的立体声链），再写回——所有 DSP（包括最后一环的双软件限幅器）都在引擎内完成。</p>
 *
 * <p><b>线程模型</b>：控制线程（{@link ViperRuntime} 重载）只做三件事——映射参数、
 * 读磁盘资源（卷积核 WAV / DDC 系数文件）、把 {@link Pending} 通过原子引用发布；
 * 音频线程在每个缓冲开头消费 {@link Pending}（装载核/DDC、applyParams）、按需更新采样率。
 * 处理函数实时安全：稳态零分配、无锁、无日志；参数变化在下个缓冲生效。</p>
 *
 * <p><b>失败语义</b>：处理链一旦抛异常即整体置为直通（{@code active=false}）并打印一次，
 * 之后不再重试——保证音效任何故障都不会影响出声。</p>
 */
public final class ViperMasterProcessor implements SynthesisEngine.OutputProcessor {

    /** 音频线程的 float 转换缓冲初始容量（帧 × 2 声道；不足时一次性扩容）。 */
    private static final int INITIAL_BUFFER_FLOATS = 8192;

    private final ViPER engine = new ViPER();

    private volatile boolean active;
    private int lastSampleRate = -1;
    private float[] floatBuffer = new float[INITIAL_BUFFER_FLOATS];

    /** 控制线程侧记录（防止重复读盘/重复装载）。 */
    private String loadedKernelKey = "";
    private String loadedDdcKey = "";

    private final AtomicReference<Pending> pending = new AtomicReference<>();

    /** 控制线程预构建、音频线程消费的一次性变更集合。 */
    private static final class Pending {
        ViPERParams params;

        boolean removeKernel;
        float[] kernelSamples;
        int kernelFrames;
        int kernelChannels;
        int kernelId;

        boolean removeDdc;
        float[] ddc44100;
        float[] ddc48000;
        int ddcCount;
    }

    /**
     * 装载（或卸载）一条预设：映射全量参数，预读卷积核/DDC 资源，发布到音频线程。
     *
     * @param enabled 总开关；为 false 时不处理任何样本
     * @param preset  当前预设；为 {@code null} 时不处理
     */
    public void configure(boolean enabled, ViperPreset preset) {
        if (!enabled || preset == null) {
            active = false;
            pending.set(null);
            return;
        }

        ViperParamMapper.Mapped mapped = ViperParamMapper.map(preset);
        Pending next = new Pending();
        next.params = mapped.params;

        // —— 卷积核（仅当卷积器启用且文件名非空；键变化才重新装载） ——
        String kernel = mapped.convolverKernelFile == null ? "" : mapped.convolverKernelFile.trim();
        if (mapped.params.convolver.enable && !kernel.isEmpty()) {
            if (!kernel.equals(loadedKernelKey)) {
                File file = resolveKernelFile(kernel);
                WavReader.WavData wav = new WavReader.WavData();
                if (file != null && WavReader.readWavFile(file.getAbsolutePath(), wav)
                        && wav.frameCount >= 16) {
                    next.kernelSamples = wav.samples;
                    next.kernelFrames = wav.frameCount;
                    next.kernelChannels = wav.channels;
                    next.kernelId = kernel.hashCode();
                    loadedKernelKey = kernel;
                }
            }
        } else if (!loadedKernelKey.isEmpty() && kernel.isEmpty()) {
            next.removeKernel = true;
            loadedKernelKey = "";
        }

        // —— DDC（仅当 DDC 启用且设备名非空；键变化才重新装载） ——
        String ddc = mapped.ddcDevice == null ? "" : mapped.ddcDevice.trim();
        if (mapped.params.ddc.enable && !ddc.isEmpty()) {
            if (!ddc.equals(loadedDdcKey)) {
                File file = resolveDdcFile(ddc);
                ViperDdcFile.Vdc vdc = file == null ? null : ViperDdcFile.parse(file);
                if (vdc != null) {
                    next.ddc44100 = vdc.sections44100;
                    next.ddc48000 = vdc.sections48000;
                    next.ddcCount = vdc.sectionCount();
                    loadedDdcKey = ddc;
                }
            }
        } else if (!loadedDdcKey.isEmpty() && ddc.isEmpty()) {
            next.removeDdc = true;
            loadedDdcKey = "";
        }

        pending.set(next);
        active = true;
    }

    public boolean isActive() {
        return active;
    }

    @Override
    public void process(double[] interleaved, int channels, int sampleRate) {
        if (!active || interleaved == null || channels <= 0) {
            return;
        }
        try {
            // 采样率变化：设值 + 请求全效果复位（音频线程在下一次 process 开头执行复位）。
            if (sampleRate != lastSampleRate) {
                engine.setSampleRate(sampleRate);
                engine.requestEffectsReset();
                lastSampleRate = sampleRate;
            }

            Pending next = pending.getAndSet(null);
            if (next != null) {
                // 每次重载预设：先请求全效果复位（清空限幅器前瞻/延迟线等历史状态），
                // 再按官方 App 的推送顺序追加——全量参数，然后资源通道。
                engine.requestEffectsReset();
                engine.applySnapshot(next.params);
                if (next.removeKernel) {
                    engine.unloadConvolverKernel();
                } else if (next.kernelSamples != null) {
                    engine.loadConvolverKernel(
                            next.kernelSamples, next.kernelFrames, next.kernelChannels,
                            next.kernelId);
                }
                if (next.removeDdc) {
                    engine.unloadDdcCoefficients();
                } else if (next.ddc44100 != null) {
                    engine.loadDdcCoefficients(next.ddc44100, next.ddc48000, next.ddcCount);
                }
            }

            final int frames = interleaved.length / channels;
            if (frames == 0) {
                return;
            }
            final int needed = frames * 2;
            if (floatBuffer.length < needed) {
                floatBuffer = new float[needed];
            }
            final float[] buffer = floatBuffer;

            int index = 0;
            for (int frame = 0; frame < frames; frame++) {
                buffer[frame * 2] = (float) interleaved[index];
                buffer[frame * 2 + 1] = (float) interleaved[channels > 1 ? index + 1 : index];
                index += channels;
            }

            engine.process(buffer, frames);

            index = 0;
            for (int frame = 0; frame < frames; frame++) {
                interleaved[index] = buffer[frame * 2];
                if (channels > 1) {
                    interleaved[index + 1] = buffer[frame * 2 + 1];
                }
                index += channels;
            }
        } catch (Throwable failure) {
            // 音效故障绝不影响出声：整体切回直通。
            active = false;
            System.out.println("[MuoniumPlayer/ViPER] 处理链异常，已切换为直通：" + failure);
        }
    }

    /** 一行状态（供调试）。 */
    public String statusLine() {
        return "engine=" + (active ? "active" : "bypass")
                + ", rate=" + engine.getSampleRate()
                + ", frames=" + engine.getProcessedFrames()
                + ", kernel=" + (engine.getConvolverKernelID() != 0 ? "loaded" : "none");
    }

    // —— 资源路径解析（控制线程） ——

    /** 卷积核候选：绝对路径 → {@code viper/kernels/} → {@code viper/}。 */
    private static File resolveKernelFile(String name) {
        File direct = new File(name);
        if (direct.isAbsolute() && direct.isFile()) {
            return direct;
        }
        File inKernels = new File(new File(ConfigPaths.VIPER_DIR, "kernels"), name);
        if (inKernels.isFile()) {
            return inKernels;
        }
        File inViper = new File(ConfigPaths.VIPER_DIR, name);
        return inViper.isFile() ? inViper : null;
    }

    /** DDC 候选：绝对路径 → {@code viper/ddc/} → {@code viper/}（自动补 {@code .vdc}）。 */
    private static File resolveDdcFile(String name) {
        String fileName = name.endsWith(".vdc") ? name : name + ".vdc";
        File direct = new File(fileName);
        if (direct.isAbsolute() && direct.isFile()) {
            return direct;
        }
        File inDdc = new File(new File(ConfigPaths.VIPER_DIR, "ddc"), fileName);
        if (inDdc.isFile()) {
            return inDdc;
        }
        File inViper = new File(ConfigPaths.VIPER_DIR, fileName);
        return inViper.isFile() ? inViper : null;
    }
}
