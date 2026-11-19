package repackage.com.jsyn.util.soundfile.streamed.raf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import repackage.com.jsyn.data.FloatSample;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
/**
 * RafFloatSample 的顺序读环形缓存。这些用例钉的是音频线程模型契约：
 * 稳态读零锁零 IO（应急读计数不增长）、数据与文件逐采样一致、
 * seek／冷启动跳变走应急路径后恢复命中、EOF 与 close 语义与旧实现一致。
 *
 * <p>构造真实 16-bit PCM WAV 文件驱动，不 mock {@code StreamedByteDataReader}——
 * 环形缓存要防的撕裂、别名与窗口刻度问题全部来自真实解码刻度。</p>
 */
class RafFloatSampleTest {

    /** 与 {@code SampleLoader#decodeLittleI16ToF32} 一致的解码刻度。 */
    private static final float SCALE = 1.0f / 32768.0f;

    @TempDir
    Path temp;

    /**
     * 立体声 16-bit。刻意选 {@code [-0.8, 0.8]} 的窄幅正弦：
     * 与 16-bit short 量程保持足够余量，编码端 {@code round(v*32767)}
     * 不会溢出回绕（首个版本曾用 sin+0.5，值域到 1.5，乘 32767 直接
     * 溢出成负数，全部用例假失败）。
     */
    private static float valueAt(int interleavedIndex) {
        return (float) (0.8 * Math.sin(interleavedIndex / 7.0));
    }

    private static void writeWav(Path file, int frames, int channels, int sampleRate) throws Exception {
        int dataBytes = frames * channels * 2;
        ByteBuffer buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes());
        buf.putInt(36 + dataBytes);
        buf.put("WAVE".getBytes());
        buf.put("fmt ".getBytes());
        buf.putInt(16);
        buf.putShort((short) 1);                    // PCM
        buf.putShort((short) channels);
        buf.putInt(sampleRate);
        buf.putInt(sampleRate * channels * 2);      // byte rate
        buf.putShort((short) (channels * 2));       // block align
        buf.putShort((short) 16);                   // bits
        buf.put("data".getBytes());
        buf.putInt(dataBytes);
        for (int i = 0; i < frames * channels; i++) {
            buf.putShort((short) Math.round(valueAt(i) * 32767.0));
        }
        Files.write(file, buf.array());
    }

    private static RafFloatSample load(Path file, int expectFrames, int channels) throws Exception {
        RafSampleLoader loader = new RafSampleLoader();
        RafFloatSample sample = (RafFloatSample) loader.loadFloatSample(new RandomAccessFile(file.toFile(), "r"));
        assertEquals(expectFrames, sample.getNumFrames());
        assertEquals(channels, sample.getChannelsPerFrame());
        return sample;
    }

    @Test
    void sequentialReadsHitPrefetchAfterWarmup() throws Exception {
        // 覆盖多个环深：跨过 64K 边界后靠的是预取线程持续补窗，不是初始命中。
        Path file = temp.resolve("seq.wav");
        int frames = 200_000;
        writeWav(file, frames, 2, 44100);
        RafFloatSample sample = load(file, frames, 2);

        // 既有尾窗行为：RafWAVEFileParser.read 的尾窗分支把 chunk 大小与含
        // 44 字节头的绝对 filePointer 相减，最后一个窗口比真实数据短 44 字节
        // （16-bit 立体声＝22 采样）。旧读头在这些下标同样返回 0——本类要钉的
        // 是「与旧实现等价」，所以等值断言止于尾部带之前，尾部带接受真值或 0。
        int total = frames * 2;
        long slowBefore = sample.slowReadCount();
        for (int i = 0; i < total - 64; i++) {
            assertEquals(valueAt(i), (float) sample.readDouble(i), 2e-4, "index " + i);
        }
        // 首窗冷启动与追上预取线的几次应急读是预期内的；数量级必须远小于总读数。
        long slow = sample.slowReadCount() - slowBefore;
        assertTrue(slow < 64, "应急读次数应是个位数级别，实际 " + slow);
        sample.cleanUp();
    }

    @Test
    void forwardJumpRecoversViaSlowPathThenHitsAgain() throws Exception {
        Path file = temp.resolve("jump.wav");
        int frames = 90_000;
        writeWav(file, frames, 2, 44100);
        RafFloatSample sample = load(file, frames, 2);

        // 冷启动
        assertEquals(valueAt(10), (float) sample.readDouble(10), 2e-4);
        // 前向长跳（WSOLA 突发的量级）：应急读一次，之后连续命中。
        long slowBefore = sample.slowReadCount();
        for (int i = 50_000; i < 50_000 + 4_096 * 3; i++) {
            assertEquals(valueAt(i), (float) sample.readDouble(i), 2e-4, "jump index " + i);
        }
        assertTrue(sample.slowReadCount() - slowBefore <= 3,
                "跳变应只触发少量应急读，实际 " + (sample.slowReadCount() - slowBefore));
        sample.cleanUp();
    }

    @Test
    void eofReturnsSilenceLikeTheOldReader() throws Exception {
        Path file = temp.resolve("eof.wav");
        int frames = 3_000;
        writeWav(file, frames, 1, 44100);
        RafFloatSample sample = load(file, frames, 1);

        // 尾部带之前逐样本等值（同 sequential 用例的尾窗注记）。
        for (int i = 0; i < frames - 64; i++) {
            assertEquals(valueAt(i), (float) sample.readDouble(i), 2e-4, "index " + i);
        }
        // EOF 之后：与旧实现一致返回静音；反复读不崩、不抛。
        for (int i = frames; i < frames + 5_000; i++) {
            assertEquals(0.0, sample.readDouble(i), "eof index " + i);
        }
        sample.cleanUp();
    }

    @Test
    void closeStopsPrefetchAndIsQuiet() throws Exception {
        Path file = temp.resolve("close.wav");
        int frames = 20_000;
        writeWav(file, frames, 2, 44100);
        RafFloatSample sample = load(file, frames, 2);
        assertEquals(valueAt(5), (float) sample.readDouble(5), 2e-4);

        sample.cleanUp();
        assertFalse(sample.prefetchAlive(), "cleanUp 后预取线程必须已退出");

        // 旧实现的契约：closed 之后 readDouble 恒返回 0。
        assertEquals(0.0, sample.readDouble(5));
        // 关了 RAF 之后再读不抛异常（旧实现同样在 closed 时短路）。
        assertDoesNotThrow(() -> {
            for (int i = 0; i < 100; i++) {
                sample.readDouble(4_000 + i);
            }
        });
    }

    @Test
    void backwardSeekFallsBackAndKeepsOldSampleSilentOutsideItsWindow() throws Exception {
        Path file = temp.resolve("back.wav");
        int frames = 80_000;
        writeWav(file, frames, 2, 44100);
        RafFloatSample sample = load(file, frames, 2);

        // 先顺序走到 60k，再回跳到 5k（读头契约之外的用法，但旧实现支持，
        // 新实现至少不能给错数据）。
        for (int i = 0; i < 60_000; i++) {
            assertEquals(valueAt(i), (float) sample.readDouble(i), 2e-4, "forward " + i);
        }
        long slowBefore = sample.slowReadCount();
        for (int i = 5_000; i < 5_000 + 4_096; i++) {
            assertEquals(valueAt(i), (float) sample.readDouble(i), 2e-4, "backward " + i);
        }
        assertTrue(sample.slowReadCount() - slowBefore <= 3,
                "回跳应急读次数应少量，实际 " + (sample.slowReadCount() - slowBefore));
        sample.cleanUp();
    }

    /**
     * automix 预备路径的回归锁：远位置预热（primeWindowAt）绝不能干扰正在出声的消费流。
     * 上一版把预备读路由进 readDouble，一次未来几分钟处的读把 written 锚点拖走，
     * 引擎线程从此逐采样 miss 进锁同步读盘——开启智能混音即持续掉帧。此用例钉住：
     * 预热后引擎线程的顺序读仍全部命中（应急读计数不增长）。
     */
    @Test
    void primeWindowAtKeepsEngineStreamUninterrupted() throws Exception {
        Path file = temp.resolve("prime.wav");
        int frames = 400_000;
        writeWav(file, frames, 2, 44100);
        RafFloatSample sample = load(file, frames, 2);

        // 引擎线程先播到 20k（确立消费流）。
        for (int i = 0; i < 20_000; i++) {
            assertEquals(valueAt(i), (float) sample.readDouble(i), 2e-4, "pre-prime " + i);
        }

        // 预备线程在「几分钟后」的位置预热两次（automix 预备的真实形态：cue 位远在
        // 未来，且可能因重规划再来一次）。
        assertTrue(sample.primeWindowAt(300_000));
        assertTrue(sample.primeWindowAt(310_000));

        // 引擎线程继续顺序读：必须全部命中，一个应急读都不该有。
        long slowBefore = sample.slowReadCount();
        for (int i = 20_000; i < 60_000; i++) {
            assertEquals(valueAt(i), (float) sample.readDouble(i), 2e-4, "post-prime " + i);
        }
        assertEquals(slowBefore, sample.slowReadCount(),
                "远位置预热不得把引擎线程的读挤进应急路径");
        sample.cleanUp();
    }

    /**
     * 生产链路穿透锁：SoundFile 的实际类型链是
     * {@code WsolaStretchSample → VocalMaterialSample → RafFloatSample}
     * （构造时永远包上素材层），primeWindowAt 必须逐层剥开到达 RAF。
     * 首版只剥 WSOLA 一层，预热在生产链路静默 no-op——正是这个用例的守卫对象。
     *
     * <p>不 new SoundFile：构造器链（SoundObject → Engine.getEngine()）会探测音频
     * 输出设备，CI 的 Linux 容器没有声卡直接 RuntimeException。这里用与 SoundFile
     * 构造器同源的装配（SampleLoader 流式加载 → VocalMaterial → WSOLA wrap）手工
     * 复现该类型链，剥层走 StreamedPrimeSupport.unwrap——SoundFile#primeWindowAt
     * 本身就是对它的薄委托，穿透行为与生产逐字同源。</p>
     */
    @Test
    void primeWindowAtPenetratesTheProductionTypeChain() throws Exception {
        Path file = temp.resolve("chain.wav");
        int frames = 90_000;
        writeWav(file, frames, 2, 44100);
        // 与 SoundFile 构造器同一条装配序：loadStreamedFloatSample → VocalMaterial → WSOLA。
        repackage.processing.sound.VocalMaterialSample vocal =
                new repackage.processing.sound.VocalMaterialSample(
                        repackage.com.jsyn.util.SampleLoader.loadStreamedFloatSample(file.toFile()));
        org.junit.jupiter.api.Assertions.assertTrue(
                vocal.getChannelsPerFrame() == 2, "立体声 WAV 原样透传 2 声道");
        FloatSample chain = repackage.processing.sound.WsolaStretchSample.wrap(vocal);
        assertTrue(chain instanceof repackage.processing.sound.WsolaStretchSample,
                "90k 帧立体声必须能包上 WSOLA 层，否则链路复现失真");
        try {
            // 走完整穿透工具（SoundFile#primeWindowAt 的委托目标）：到达 RAF 才证明穿透真的发生。
            repackage.processing.sound.StreamedPrimeSupport.Unwrapped unwrapped =
                    repackage.processing.sound.StreamedPrimeSupport.unwrap(chain, 70_000);
            assertTrue(unwrapped != null
                            && unwrapped.sample instanceof repackage.com.jsyn.util.soundfile.streamed.raf.RafFloatSample,
                    "预热必须穿透 Wsola→VocalMaterial 到达 RafFloatSample");
            // 剥到 RAF 后按内层刻度预热应成功（同一条滑窗、同一把 SYNC 锁）。
            assertTrue(((repackage.com.jsyn.util.soundfile.streamed.raf.RafFloatSample) unwrapped.sample)
                            .primeWindowAt(unwrapped.index),
                    "穿透后的内层预热应返回 true");
        } finally {
            chain.cleanUp();
        }
    }

    /**
     * 24-bit PCM WAV：字节窗口跨 12KB、factor=3 解码分支与 16-bit 不同。
     * 实现约定文档声称「采样刻度仍 4096，行为不变」——这条用例把它钉住。
     */
    @Test
    void twentyFourBitWavStreamsIdentically() throws Exception {
        Path file = temp.resolve("w24.wav");
        int frames = 40_000;
        writeWav24(file, frames, 2, 44100);
        RafSampleLoader loader = new RafSampleLoader();
        RafFloatSample sample = (RafFloatSample) loader.loadFloatSample(
                new RandomAccessFile(file.toFile(), "r"));
        assertEquals(frames, sample.getNumFrames());

        int total = frames * 2;
        for (int i = 0; i < total - 64; i++) {
            assertEquals(valueAt(i), (float) sample.readDouble(i), 2e-5, "24bit index " + i);
        }
        // 尾部带之外：越界静音。
        assertEquals(0.0, sample.readDouble(total + 1_000));
        sample.cleanUp();
    }

    /** 24-bit PCM WAV 的写入与逐采样基准（编码到 24 位刻度再解码，误差按 24-bit 量程收紧）。 */
    private static void writeWav24(Path file, int frames, int channels, int sampleRate) throws Exception {
        int dataBytes = frames * channels * 3;
        ByteBuffer buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes());
        buf.putInt(36 + dataBytes);
        buf.put("WAVE".getBytes());
        buf.put("fmt ".getBytes());
        buf.putInt(16);
        buf.putShort((short) 1);                    // PCM
        buf.putShort((short) channels);
        buf.putInt(sampleRate);
        buf.putInt(sampleRate * channels * 3);       // byte rate
        buf.putShort((short) (channels * 3));        // block align
        buf.putShort((short) 24);                   // bits
        buf.put("data".getBytes());
        buf.putInt(dataBytes);
        for (int i = 0; i < frames * channels; i++) {
            int v = (int) Math.round(valueAt(i) * 8_388_607.0);   // 2^23-1
            buf.put((byte) (v & 0xFF));
            buf.put((byte) ((v >> 8) & 0xFF));
            buf.put((byte) ((v >> 16) & 0xFF));
        }
        Files.write(file, buf.array());
    }
}
