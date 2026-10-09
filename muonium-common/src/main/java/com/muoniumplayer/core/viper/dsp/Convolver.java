// 逐行移植自 ViPERDSP/viper/effects/Convolver.{h,cpp}（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
// 上游为 IR 卷积效果的外壳：持有 MatrixConvolver，管理核装载/CRC 校验/湿干混合/串扰与输出增益。
// 数值类型严格镜像上游（float 保持 float，uint32_t 以 Java int 承载；采样率/帧数等均为非负量）。
package com.muoniumplayer.core.viper.dsp;

import java.util.Objects;

public final class Convolver {

    /** 上游 namespace 常量 kMaxKernelSamples = 4U * 1024U * 1024U。 */
    private static final int K_MAX_KERNEL_SAMPLES = 4 * 1024 * 1024;

    /** 上游 constants.h 的 VIPER_DEFAULT_SAMPLING_RATE。 */
    private static final int K_DEFAULT_SAMPLING_RATE = 44100;

    /** 上游 kernel_file_path_[256] 的容量（strncpy 最多写入 255 字符 + 结尾 NUL）。 */
    private static final int K_KERNEL_PATH_LENGTH = 256;

    private boolean enable_;
    private int sampling_rate_;
    private int kernel_id_;
    private int expected_size_;
    private int current_size_;
    private int channel_count_;
    private int current_kernel_buffer_crc_;

    private float cross_channel_;
    private float wet_;
    private float output_gain_;
    private MatrixConvolver.MatrixRouting routing_;
    private float cross_delay_ms_;

    /** 等价替代上游 char kernel_file_path_[256]（空串表示未设置）。 */
    private String kernel_file_path_;
    private float[] kernel_buffer_;
    private final MatrixConvolver matrix_;

    /**
     * 复用的 mix 暂存结构：上游在 Process 中以栈上聚合体构造 MatrixConvolverMix，
     * 为满足「process 内不得分配」的要求，此处改为成员变量并逐字段更新。
     */
    private final MatrixConvolver.MatrixConvolverMix mix_;

    public Convolver() {
        enable_ = false;
        sampling_rate_ = K_DEFAULT_SAMPLING_RATE;
        kernel_id_ = 0;
        expected_size_ = 0;
        current_size_ = 0;
        channel_count_ = 0;
        current_kernel_buffer_crc_ = 0;
        cross_channel_ = 0.0F;
        wet_ = 1.0F;
        output_gain_ = 1.0F;
        routing_ = MatrixConvolver.MatrixRouting.DIRECT_AND_CROSS;
        cross_delay_ms_ = 0.3125F;
        kernel_file_path_ = "";
        kernel_buffer_ = null;
        matrix_ = new MatrixConvolver();
        mix_ = new MatrixConvolver.MatrixConvolverMix();
        mix_.routing = MatrixConvolver.MatrixRouting.DIRECT_AND_CROSS;
    }

    /**
     * 等价替代 {@code uint32_t Convolver::Process(const float *source, float *dest, uint32_t frame_size)}。
     *
     * <p>语义（逐字照抄上游）：
     * <ul>
     *   <li>source/dest 为空或 frame_size == 0 时返回 0，不做任何处理。</li>
     *   <li>未启用或矩阵卷积不可用（未加载核/仅装载了空核）时：仅当 source != dest 时按
     *       frame_size*2 个 float 原样拷到 dest，并返回 frame_size（即直通、无延迟）。</li>
     *   <li>否则把 cross_channel_/wet_/output_gain_/routing_ 与由 cross_delay_ms_ 换算出
     *       的 cross_delay_frames 组成 MatrixConvolverMix 交给 matrix_.Process，返回其返回帧数
     *       （正常路径恒为 frame_size）。</li>
     * </ul>
     *
     * @param source     输入，交错立体声（左、右），长度 >= frame_size*2
     * @param dest       输出，交错立体声，长度 >= frame_size*2
     * @param frame_size 帧数
     * @return 实际处理的帧数
     */
    public int process(final float[] source, final float[] dest, final int frame_size) {
        if (source == null || dest == null || frame_size == 0) {
            return 0;
        }
        if (!enable_ || !matrix_.isUsable()) {
            if (source != dest) {
                System.arraycopy(source, 0, dest, 0, frame_size * 2);
            }
            return frame_size;
        }
        mix_.cross = cross_channel_;
        mix_.wet = wet_;
        mix_.outputGain = output_gain_;
        mix_.routing = routing_;
        mix_.crossDelayFrames = (int) Math.round(
                (double) cross_delay_ms_ * sampling_rate_ / 1000.0);
        return matrix_.process(source, dest, frame_size, mix_);
    }

    public void reset() {
        matrix_.reset();
    }

    public boolean getEnable() {
        return enable_;
    }

    public int getKernelID() {
        return kernel_id_;
    }

    public void setEnable(final boolean enable) {
        if (enable_ == enable) {
            return;
        }
        matrix_.reset();
        enable_ = enable;
    }

    /** 等价替代 {@code void SetKernel(const char *path)}：从 WAV 文件加载 IR。 */
    public void setKernel(final String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        if (path.equals(kernel_file_path_)) {
            return;
        }

        final WavReader.WavData wav = new WavReader.WavData();
        if (!WavReader.readWavFile(path, wav)) {
            return;
        }
        final boolean supported =
                wav.channels == 1 || wav.channels == 2 || wav.channels == 4;
        final boolean loaded = supported && wav.frameCount >= 16
                && matrix_.loadInterleaved(
                        wav.samples, wav.frameCount, wav.channels, sampling_rate_);
        if (!loaded) {
            return;
        }

        kernel_file_path_ = path.length() > K_KERNEL_PATH_LENGTH - 1
                ? path.substring(0, K_KERNEL_PATH_LENGTH - 1)
                : path;
        kernel_id_ = 0;
        current_kernel_buffer_crc_ = 0;
    }

    /** 等价替代 {@code void SetKernel(const float *buf, uint32_t size)}：单声道 float IR。 */
    public void setKernel(final float[] buf, final int size) {
        if (buf == null || size < 16 || size > K_MAX_KERNEL_SAMPLES) {
            return;
        }
        if (!matrix_.loadInterleaved(buf, size, 1, sampling_rate_)) {
            return;
        }
        kernel_id_ = 0;
        current_kernel_buffer_crc_ = 0;
        kernel_file_path_ = "";
    }

    /**
     * 等价替代 {@code void SetKernelBuffer(const float *buf, uint32_t size)}：
     * 向 PrepareKernelBuffer 预备的缓冲逐块追加数据（ViPER.cpp 分块调用）。
     */
    public void setKernelBuffer(final float[] buf, int size) {
        if (buf == null || size == 0 || kernel_buffer_ == null || expected_size_ == 0) {
            return;
        }
        if (current_size_ >= expected_size_) {
            return;
        }
        size = Math.min(size, expected_size_ - current_size_);
        System.arraycopy(buf, 0, kernel_buffer_, current_size_, size);
        current_size_ += size;
    }

    /**
     * 与 {@link #setKernelBuffer(float[], int)} 相同，但自 {@code offset} 起读取
     * （对应上游 {@code samples + written} 指针偏移的分块装载调用）。
     */
    public void setKernelBuffer(final float[] buf, final int offset, final int size) {
        if (buf == null || size == 0 || kernel_buffer_ == null || expected_size_ == 0) {
            return;
        }
        if (current_size_ >= expected_size_) {
            return;
        }
        final int count = Math.min(size, expected_size_ - current_size_);
        System.arraycopy(buf, offset, kernel_buffer_, current_size_, count);
        current_size_ += count;
    }

    /** 等价替代 {@code void SetKernelStereo(const float *ch_l, const float *ch_r, uint32_t frame_count)}。 */
    public void setKernelStereo(final float[] ch_l, final float[] ch_r, final int frame_count) {
        if (ch_l == null || ch_r == null || frame_count < 16
                || frame_count > K_MAX_KERNEL_SAMPLES / 2) {
            return;
        }

        final float[] interleaved = new float[frame_count * 2];
        for (int frame = 0; frame < frame_count; frame++) {
            interleaved[frame * 2] = ch_l[frame];
            interleaved[frame * 2 + 1] = ch_r[frame];
        }
        final boolean loaded = matrix_.loadInterleaved(
                interleaved, frame_count, 2, sampling_rate_);
        if (!loaded) {
            return;
        }

        kernel_id_ = 0;
        current_kernel_buffer_crc_ = 0;
        kernel_file_path_ = "";
    }

    public void setCrossChannel(final float value) {
        cross_channel_ = clampFinite(value, 0.0F, 1.0F, 0.0F);
    }

    public void setWet(final float value) {
        wet_ = clampFinite(value, 0.0F, 1.0F, 1.0F);
    }

    public void setOutputGain(final float decibels) {
        final float clamped = clampFinite(decibels, -24.0F, 24.0F, 0.0F);
        output_gain_ = (float) Math.pow(10.0, clamped / 20.0);
    }

    public void setRouting(final MatrixConvolver.MatrixRouting routing) {
        routing_ = routing == MatrixConvolver.MatrixRouting.DIRECT_ONLY
                || routing == MatrixConvolver.MatrixRouting.CROSS_ONLY
                ? routing
                : MatrixConvolver.MatrixRouting.DIRECT_AND_CROSS;
    }

    public void setCrossDelay(final float milliseconds) {
        cross_delay_ms_ = clampFinite(milliseconds, 0.0F, 10.0F, 0.3125F);
    }

    public void setSampleRate(final int sampling_rate) {
        if (sampling_rate_ == sampling_rate) {
            return;
        }
        sampling_rate_ = sampling_rate;
        matrix_.reset();
    }

    /**
     * 等价替代 {@code void PrepareKernelBuffer(uint32_t buf_size, uint32_t ch_count, bool reset)}。
     * reset 为真时清空缓冲、CRC、矩阵核、文件路径与 kernel_id（卸载路径）；
     * 否则按声道路数与总 float 数分配新缓冲，等待 SetKernelBuffer 分块填充。
     */
    public void prepareKernelBuffer(final int buf_size, final int ch_count, final boolean reset) {
        if (reset) {
            clearKernelBuffer();
            current_kernel_buffer_crc_ = 0;
            matrix_.unload();
            kernel_file_path_ = "";
            kernel_id_ = 0;
            return;
        }

        final boolean supported = ch_count == 1 || ch_count == 2 || ch_count == 4;
        if (!supported || buf_size == 0 || buf_size > K_MAX_KERNEL_SAMPLES
                || buf_size % ch_count != 0) {
            return;
        }
        kernel_buffer_ = new float[buf_size];
        expected_size_ = buf_size;
        current_size_ = 0;
        channel_count_ = ch_count;
    }

    /**
     * 等价替代 {@code void CommitKernelBuffer(uint32_t expected_size, uint32_t expected_crc, uint32_t kernel_id)}。
     * 校验尺寸/CRC 后把缓冲交给 MatrixConvolver；无论成功与否都会清空临时缓冲。
     */
    public void commitKernelBuffer(
            final int expected_size, final int expected_crc, final int kernel_id) {
        if (kernel_buffer_ == null || expected_size_ != expected_size
                || current_size_ != expected_size_ || channel_count_ == 0
                || expected_size_ % channel_count_ != 0) {
            clearKernelBuffer();
            return;
        }

        final int calculated_crc = Crc32.crc32(kernel_buffer_, current_size_);
        if (calculated_crc != expected_crc || calculated_crc == current_kernel_buffer_crc_) {
            clearKernelBuffer();
            return;
        }

        final int frame_count = current_size_ / channel_count_;
        final boolean loaded = frame_count >= 16 && matrix_.loadInterleaved(
                kernel_buffer_, frame_count, channel_count_, sampling_rate_);
        if (loaded) {
            current_kernel_buffer_crc_ = calculated_crc;
            kernel_id_ = kernel_id;
            kernel_file_path_ = "";
        }
        clearKernelBuffer();
    }

    private void clearKernelBuffer() {
        kernel_buffer_ = null;
        expected_size_ = 0;
        current_size_ = 0;
        channel_count_ = 0;
    }

    /** 等价替代上游匿名命名空间的 ClampFinite：非有限值取 fallback，否则夹在 [min, max]。 */
    private static float clampFinite(
            final float value, final float minimum, final float maximum, final float fallback) {
        if (!Float.isFinite(value)) {
            return fallback;
        }
        return Math.min(Math.max(value, minimum), maximum);
    }

    @Override
    public String toString() {
        return "Convolver{enable=" + enable_ + ", kernelID=" + kernel_id_
                + ", path=" + Objects.toString(kernel_file_path_, "") + "}";
    }
}
