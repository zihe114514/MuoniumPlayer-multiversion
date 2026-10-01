package repackage.processing.sound;

import repackage.com.jsyn.data.FloatSample;

/**
 * 给一路 deck 的播放素材加一层「人声可以按位置减掉」。
 *
 * <p>automix 交接期要让进来的那首先当器乐垫，人声随后再长回来。原先这件事只能靠
 * {@code VocalDuckFilter} 做带限的中央声道抵消：它只能压住正中间那部分，偏一点的人声、
 * 混响尾巴、以及中频以外的歌情人声都动不了。这一层改从<b>素材</b>上做：离线分离出来的
 * 那份人声按帧对齐摆在这里，读头每读一个采样就顺手把它减掉 {@code 1 - level} 份。</p>
 *
 * <h3>为什么装在 WSOLA 之下</h3>
 * <p>交接时进场方可能正在做变速不变调，那条链路是「读头 → 伸缩层 → 素材」。素材混合必须发生在
 * <b>伸缩之前</b>：伸缩层往后要拿这份已经减过人声的信号去做 overlap-add，两处顺序反过来，
 * 减下去的人声会被 WSOLA 的接缝搬来搬去，残差位置每次都不一样。所以 {@code SoundFile} 里的
 * 组装顺序是 {@code raw → 这一层 → }{@link WsolaStretchSample}{@code → 读头}。</p>
 *
 * <h3>混合式子与「不激活时必须逐采样一致」</h3>
 * <p>输出 {@code = raw + vocal * ratio * (level - 1)}。取 {@code level = 1} 时第二项恒为 0，
 * 于是这一层原封不动地把内层还回来 —— 这不是省一点 CPU 的问题：正常听歌、旧计划、没跑过
 * 分离模型的歌曲都必须和这个类不存在时<b>逐采样相同</b>，所以 {@code level} 回到 1（或者素材
 * 根本没接上）时直接走直通分支，一次都不读素材数组。</p>
 *
 * <p>{@code ratio} 是离线量出来的投影系数：分离模型给的并不是「混音里那一份人声」的严格副本，
 * 差一点就要减不干净（留一条人声的幽灵）或者把伴奏一起削掉。它只在这里乘一次，缓存里存的是
 * 模型的原始输出。</p>
 *
 * <h3>线程</h3>
 * <p>{@link #attach} 与 {@link #detach} 由预备（arm）线程调用，{@link #setLevel} 由音频线程或
 * 监督 tick 调用，{@link #readDouble} 跑在音频线程上。三个状态各自一个 {@code volatile}：素材装在
 * 一个不可变持手里整体发布，所以不会出现「左声道用新素材、右声道用旧素材」以外的中间态；而
 * 装载时 {@code level} 一定被复位到 1，也就是说装载那一刻这层是恒等的，无论读头走到哪里都不会
 * 听出来。越界的帧下标一律按直通处理，素材窗口只覆盖歌曲开头一段，往后自然就没有素材可用。</p>
 */
public final class VocalMaterialSample extends FloatSample {

    /** 小于这个偏差视为「人声已经全部回来了」，走逐采样一致的直通分支。 */
    private static final double IDLE_EPSILON = 1e-4;

    private final FloatSample inner;

    /** 一次装载的全部素材状态；不可变，靠整块替换发布。 */
    private static final class Material {
        final float[] samples;
        final long firstFrame;
        final double ratio;

        Material(float[] samples, long firstFrame, double ratio) {
            this.samples = samples;
            this.firstFrame = firstFrame;
            this.ratio = ratio;
        }
    }

    private volatile Material material;
    private volatile double level = 1.0;

    VocalMaterialSample(FloatSample inner) {
        this.inner = inner;
        // 与 WsolaStretchSample 同一套字段写法：setNumFrames() 会被 allocate() 留下的
        // maxFrames 夹成 0，而 AudioSample 又遮住了 numFrames 字段，只有直接写字段能被读到。
        this.maxFrames = inner.getNumFrames();
        this.numFrames = inner.getNumFrames();
        setChannelsPerFrame(inner.getChannelsPerFrame());
        setFrameRate(inner.getFrameRate());
    }

    /**
     * 装上人声素材。
     *
     * @param samples    单声道人声素材，帧序从 {@code firstFrame} 起与播放素材一一对应
     * @param firstFrame {@code samples[0]} 落在播放素材的第几帧
     * @param frameRate  素材的采样率，必须与播放素材一致（不一致时相减留下的是错位的残差）
     * @param ratio      素材对回原曲人声音量的投影系数
     * @return 是否接上了；任何一条不成立都保持原样（直通）并还 {@code false}
     */
    public boolean attach(float[] samples, long firstFrame, double frameRate, double ratio) {
        if (samples == null || samples.length == 0 || firstFrame < 0L
                || !Double.isFinite(frameRate) || Math.round(frameRate) != Math.round(getFrameRate())
                || !Double.isFinite(ratio) || ratio <= 0.0 || ratio > 4.0
                || getChannelsPerFrame() < 1) return false;
        this.material = new Material(samples, firstFrame, ratio);
        // 装载时先把这层设成恒等：曲线要压人声得等到交接开始，而那之后的每一次写入都是
        // 从 1 连续走过来的。反过来（带着上一次交接留下的 0.3 装载）会让新歌开头就没声音。
        this.level = 1.0;
        return true;
    }

    /** 卸下素材、恢复直通，并把引用交还给调用方（一份 30 秒素材是几 MB，不该跟着 deck 活到下一首）。 */
    public void detach() {
        this.material = null;
        this.level = 1.0;
    }

    /** 是否已经装着素材。 */
    public boolean hasMaterial() {
        return this.material != null;
    }

    /**
     * 人声在这一刻占几份：{@code 1} 为原样（也是直通分支），{@code 0} 为完全减掉。
     *
     * <p>调用方是混音曲线，它给的是「抵消深度」，这里收的是它的补数。</p>
     */
    public void setLevel(double value) {
        this.level = !Double.isFinite(value) ? 1.0 : Math.max(0.0, Math.min(1.0, value));
    }

    public double getLevel() {
        return this.level;
    }

    @Override
    public double readDouble(int index) {
        double raw = inner.readDouble(index);
        Material held = this.material;
        double voice = this.level;
        if (held == null || voice >= 1.0 - IDLE_EPSILON) return raw;
        int channels = getChannelsPerFrame();
        int frame = channels > 1 ? index / channels : index;
        long position = (long) frame - held.firstFrame;
        if (position < 0L || position >= held.samples.length) return raw;
        return raw + held.samples[(int) position] * held.ratio * (voice - 1.0);
    }

    @Override
    public void writeDouble(int index, double value) {
        // 只读装饰器；写进来的值没有去处，静默忽略比抛异常安全（与 WsolaStretchSample 同）。
    }

    @Override
    public boolean isIndexOverflow(int index) {
        return inner.isIndexOverflow(index);
    }

    @Override
    public void allocate(int numFrames, int channelsPerFrame) {
        // 不持有缓冲区：素材是外面递进来的那一份，这里 new 一次就是一首 songs 的白扔。
    }

    @Override
    public void read(int startFrame, float[] data, int startIndex, int numFrames) {
        int channels = getChannelsPerFrame();
        int total = numFrames * channels;
        int base = startFrame * channels;
        for (int i = 0; i < total; i++) {
            data[startIndex + i] = (float) readDouble(base + i);
        }
    }

    @Override
    public double interpolate(double fractionalIndex) {
        // 走自己的 readDouble，这样即使这一层直接坐在读头下面（内层太短、伸缩层没接上），
        // 插值取到的也是混合之后的素材而不是原始素材。
        int index = (int) fractionalIndex;
        double phase = fractionalIndex - index;
        double source = readDouble(index);
        double target = readDouble(index + 1);
        return ((target - source) * phase) + source;
    }

    @Override
    public void cleanUp() {
        detach();
        try {
            inner.cleanUp();
        } catch (Throwable ignored) {
        }
    }
}
