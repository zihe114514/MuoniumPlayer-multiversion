package repackage.com.jsyn.data;

/**
 * 把 >2 声道的流式采样实时降混成立体声的只读装饰器。
 *
 * <h3>为什么要有这一层</h3>
 * <p>播放引擎只认 1/2 声道：{@code AudioSample.initiatePlayer()} 只装 mono/stereo 两种读头，
 * 非 2 声道的样本落进 {@code VariableRateMonoReader}，它的 dataQueue 端口恒为 1 声道
 * （{@code UnitDataQueuePort.numChannels = 1}），入队即抛
 * {@code ChannelMismatchException: Tried to queue 6 channel data to a 1 channel port}。
 * 而 FLAC 与 WAV 的流式解码器把文件头的声道数原样透传（5.1 环绕声就是 6），全链路
 * （伸缩层、人声素材层）都不降混——多声道文件必然「下载正常、解码正常、播放失败」。</p>
 *
 * <h3>装饰器语义（与 WsolaStretchSample / VocalMaterialSample 同款）</h3>
 * <ul>
 *   <li>对外报告 2 声道：读头按立体声装，端口校验（声道数相等）通过；</li>
 *   <li>{@code readDouble} 收到的是<b>采样下标</b>（读头逐采样拉，不乘声道），内部换算成
 *       帧+通道后向内层读对应原始声道，按 5.1 标准折算系数合成左右；</li>
 *   <li>降混在包装层做，内层流式 reader（RAF 滑窗 / FLAC 缓冲）一行不改，窗口机制原样工作；</li>
 *   <li>帧数、采样率、时长不变——进度条与 automix 分析看到的还是同一首歌。</li>
 * </ul>
 *
 * <p>1/2 声道的样本不该包（直通零开销），由工厂方法 {@link #stereoOrSame} 判断。</p>
 */
public final class DownmixSample extends FloatSample {

    /** 常量功率混响系数（ITU/5.1 折算惯例）：中央与环绕声道按 0.707 衰减进左右。 */
    private static final double SURROUND_ATTENUATION = 0.7071067811865476;

    private final FloatSample inner;
    /** 内层每帧采样数（原始声道数）。 */
    private final int innerChannels;

    private DownmixSample(FloatSample inner, int innerChannels) {
        this.inner = inner;
        this.innerChannels = innerChannels;
        // 与 WsolaStretchSample 同一套字段写法：setNumFrames() 会被 allocate() 留下的
        // maxFrames 夹成 0，而 AudioSample 又遮住了 numFrames 字段，只有直接写字段能被读到。
        this.maxFrames = inner.getNumFrames();
        this.numFrames = inner.getNumFrames();
        setChannelsPerFrame(2);
        setFrameRate(inner.getFrameRate());
    }

    /**
     * 需要降混就包一层，1/2 声道原样归还。
     *
     * @return 永不为 {@code null}（{@code inner} 为 {@code null} 时原样返回）
     */
    public static FloatSample stereoOrSame(FloatSample inner) {
        if (inner == null) return null;
        int channels = inner.getChannelsPerFrame();
        if (channels <= 2 || inner.getNumFrames() <= 0) return inner;
        try {
            return new DownmixSample(inner, channels);
        } catch (Throwable ignored) {
            // 降混包装失败不该变成一次曲目失败：退回原样本，
            // 让后续的端口校验按既有行为报它自己的错。
            return inner;
        }
    }

    @Override
    public double readDouble(int index) {
        // 读头（readNextMonoDouble）与素材提取都逐采样拉，index 是采样下标。
        int frame = index / 2;
        int outChannel = index % 2;
        int base = frame * innerChannels;
        if (outChannel == 0) {
            return mixLeft(base);
        }
        return mixRight(base);
    }

    private double mixLeft(int base) {
        // 5.1 标准折算：L = FL + 0.707·C + 0.707·SL（front-left = ch0, center = ch2,
        // surround-left = ch4）；声道布局不足的项按 0 补。
        double value = sampleAt(base);
        if (innerChannels > 2) value += SURROUND_ATTENUATION * sampleAt(base + 2);
        if (innerChannels > 4) value += SURROUND_ATTENUATION * sampleAt(base + 4);
        return value;
    }

    private double mixRight(int base) {
        // R = FR + 0.707·C + 0.707·SR（front-right = ch1, surround-right = ch5）。
        double value = sampleAt(base + 1);
        if (innerChannels > 2) value += SURROUND_ATTENUATION * sampleAt(base + 2);
        if (innerChannels > 5) value += SURROUND_ATTENUATION * sampleAt(base + 5);
        return value;
    }

    private double sampleAt(int index) {
        return index < 0 ? 0.0 : inner.readDouble(index);
    }

    @Override
    public boolean isIndexOverflow(int index) {
        return index >= inner.getNumFrames() * 2;
    }

    @Override
    public void writeDouble(int index, double value) {
        // 只读装饰器；写进来的值没有去处，静默忽略比抛异常安全（与 WsolaStretchSample 同）。
    }

    @Override
    public void allocate(int numFrames, int channelsPerFrame) {
        // 不持有缓冲区：内层是流式 reader，这里 new 一次就是一首歌的白扔。
    }

    @Override
    public void read(int startFrame, float[] data, int startIndex, int numFrames) {
        // 裁到内层帧数为止：流式内层越界只是返回 0，内存型内层却会直接炸下标。
        // 调用方（AudioSample.read）按本层的 frames() 分配数组，正常不该越界；
        // 裁剪是给「批量读 + 边界帧」这类组合兜底。
        int safeStart = Math.max(0, Math.min(startFrame, inner.getNumFrames()));
        int availableFrames = Math.max(0, Math.min(numFrames, inner.getNumFrames() - safeStart));
        int total = availableFrames * 2;
        int base = safeStart * 2;
        for (int i = 0; i < total; i++) {
            data[startIndex + i] = (float) readDouble(base + i);
        }
        // 越界部分补 0，保持对调用方数组的完整覆盖语义。
        java.util.Arrays.fill(data, startIndex + total, startIndex + numFrames * 2, 0.0f);
    }

    @Override
    public double interpolate(double fractionalIndex) {
        int index = (int) fractionalIndex;
        double phase = fractionalIndex - index;
        double source = readDouble(index);
        double target = readDouble(index + 1);
        return ((target - source) * phase) + source;
    }

    @Override
    public void cleanUp() {
        try {
            inner.cleanUp();
        } catch (Throwable ignored) {
        }
    }
}
