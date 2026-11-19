package repackage.processing.sound;

import repackage.com.jsyn.data.FloatSample;

/**
 * SoundFile#primeWindowAt 的逐层穿透逻辑，抽成无音频依赖的静态工具。
 *
 * <p>为什么要独立存在：SoundFile 构造器链（SoundObject → Engine.getEngine()）会在
 * 初始化时探测音频输出设备，CI 的 Linux 容器没有声卡直接
 * {@code RuntimeException("No default audio device found")}。预热穿透的守卫测试
 * 需要在无设备环境验证「装饰器链被逐层剥开」，所以这段类型分发不能留在
 * 只能随 SoundFile 实例走的实例方法里。</p>
 *
 * <p>实际类型链是 {@code WsolaStretchSample → VocalMaterialSample → (DownmixSample →)
 * RafFloatSample}，每层都是逐采样装饰器（VocalMaterialSample 构造时永远包上，
 * 见 SoundFile 构造器注释；多声道 WAV 再包一层降混）。首版 unwrap 只剥了 WSOLA 一层，
 * 被 VocalMaterialSample 挡住，预热在生产链路静默 no-op——现在逐层穿透，
 * Downmix 层还要把「降混后 2 声道交错下标」换算回「原始声道数的交错下标」，
 * 内层窗口按原始声道刻度读取。</p>
 */
public final class StreamedPrimeSupport {

    private StreamedPrimeSupport() {
    }

    /**
     * 逐层剥开装饰器链，把下标换算到内层刻度；到达非装饰器层即停。
     *
     * @param inner            当前链头
     * @param interleavedIndex 链头刻度的交错采样下标
     * @return 剥到内层后的样本与同刻度下标；{@code inner} 为 {@code null} 时返回 {@code null}
     */
    public static Unwrapped unwrap(FloatSample inner, int interleavedIndex) {
        int index = interleavedIndex;
        while (inner instanceof WsolaStretchSample
                || inner instanceof VocalMaterialSample
                || inner instanceof repackage.com.jsyn.data.DownmixSample) {
            if (inner instanceof WsolaStretchSample) {
                inner = ((WsolaStretchSample) inner).innerSample();
            } else if (inner instanceof VocalMaterialSample) {
                inner = ((VocalMaterialSample) inner).innerSample();
            } else {
                repackage.com.jsyn.data.DownmixSample downmix =
                        (repackage.com.jsyn.data.DownmixSample) inner;
                int innerChannels = Math.max(1, downmix.innerChannels());
                // 降混后的下标是 2 声道交错的帧号×2；内层是原始声道数交错。
                // 帧号换算后乘回内层声道数，才落在同一时间位置。
                int frame = index / Math.max(1, downmix.getChannelsPerFrame());
                index = (int) Math.min(Integer.MAX_VALUE, (long) frame * innerChannels);
                inner = downmix.innerSample();
            }
        }
        return inner == null ? null : new Unwrapped(inner, index);
    }

    /** 穿透结果：链尾样本与换算后的同刻度下标。 */
    public static final class Unwrapped {
        public final FloatSample sample;
        public final int index;

        Unwrapped(FloatSample sample, int index) {
            this.sample = sample;
            this.index = index;
        }
    }
}
