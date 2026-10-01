package repackage.processing.sound;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import repackage.com.jsyn.data.FloatSample;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交接期「人声走素材淡入」那层装饰器的回归测试。
 *
 * <p>这层坐在播放素材的读头下面：读头每拉一个采样，它就顺手把离线分离出来的人声按位置减掉
 * {@code 1 - level} 份。它同时站在<b>所有</b>歌曲的取源链上（正常听歌也经过它），所以最要紧的
 * 一条性质是「不干活时必须逐采样等于没有这一层」，其次才是减得对不对。</p>
 *
 * <p>这一组用例全部是真跑：拿一个内存里的 {@link Tap} 当原始素材，读它、比对采样值，不启动
 * Minecraft、不放声音、不联网。投影系数（{@code ratio}）在这些用例里都取 1，只有一条例外专门
 * 验它被乘在哪一边。</p>
 */
class VocalMaterialSampleTest {

    private static final int RATE = 44_100;

    /** 一块可预测的原始素材：每个采样的值就是它的下标，读没读过、读的是哪个位置一目了然。 */
    private static final class Tap extends FloatSample {
        private final float[] values;
        private final int channels;
        int reads;

        Tap(int frames, int channels) {
            this.values = new float[frames * channels];
            this.channels = channels;
            for (int i = 0; i < values.length; i++) values[i] = i * 0.001f;
            allocate(frames, channels);
            write(values);
            setFrameRate(RATE);
        }

        @Override
        public double readDouble(int index) {
            reads++;
            return index >= 0 && index < values.length ? values[index] : 0.0;
        }

        float value(int index) {
            return values[index];
        }
    }

    /** 一份「人声」：所有帧都是同一个幅度，减掉之后应当正好是 0。 */
    private static float[] vocal(int frames, float amplitude) {
        float[] stem = new float[frames];
        java.util.Arrays.fill(stem, amplitude);
        return stem;
    }

    @Test
    @DisplayName("没接素材时逐采样直通，一次都不读素材")
    void idleIsBitExactPassThrough() {
        Tap raw = new Tap(8, 2);
        VocalMaterialSample layer = new VocalMaterialSample(raw);
        for (int i = 0; i < 16; i++) assertEquals(raw.value(i), layer.readDouble(i), 0.0);
        assertEquals(16, raw.reads);
        assertFalse(layer.hasMaterial());
        assertEquals(1.0, layer.getLevel(), 0.0);
    }

    @Test
    @DisplayName("元数据照抄内层：帧数、声道数、采样率一个都不能变")
    void metadataMirrorsInner() {
        Tap raw = new Tap(12, 2);
        VocalMaterialSample layer = new VocalMaterialSample(raw);
        assertEquals(raw.getNumFrames(), layer.getNumFrames());
        assertEquals(raw.getChannelsPerFrame(), layer.getChannelsPerFrame());
        assertEquals(raw.getFrameRate(), layer.getFrameRate(), 0.0);
        assertEquals(raw.isIndexOverflow(24), layer.isIndexOverflow(24));
        assertTrue(layer.isIndexOverflow(25));
    }

    @Test
    @DisplayName("接上素材、level 仍是 1 时也必须逐采样一致")
    void attachedButIdleIsStillIdentity() {
        Tap raw = new Tap(8, 2);
        VocalMaterialSample layer = new VocalMaterialSample(raw);
        assertTrue(layer.attach(vocal(8, .5f), 0L, RATE, 1.0));
        assertTrue(layer.hasMaterial());
        for (int i = 0; i < 16; i++) assertEquals(raw.value(i), layer.readDouble(i), 0.0);
    }

    @Test
    @DisplayName("level 0 把人声整个减掉；素材覆盖之外的帧原样还回来")
    void levelZeroSubtractsTheVocal() {
        Tap raw = new Tap(8, 2);
        VocalMaterialSample layer = new VocalMaterialSample(raw);
        // 帧 2..5 有素材，其余没有：越界的帧必须走直通而不是把 0 减进去。
        assertTrue(layer.attach(vocal(4, 0.25f), 2L, RATE, 1.0));
        layer.setLevel(0.0);
        assertEquals(raw.value(0), layer.readDouble(0), 0.0);          // 帧 0：素材之外
        assertEquals(raw.value(4) - 0.25, layer.readDouble(4), 1e-6);  // 帧 2 左声道
        assertEquals(raw.value(11) - 0.25, layer.readDouble(11), 1e-6);// 帧 5 右声道
        assertEquals(raw.value(14), layer.readDouble(14), 0.0);        // 帧 7：素材之外
    }

    @Test
    @DisplayName("立体声两个声道读同一帧的同一个素材采样")
    void bothChannelsShareOneFrameOfMaterial() {
        Tap raw = new Tap(4, 2);
        VocalMaterialSample layer = new VocalMaterialSample(raw);
        assertTrue(layer.attach(vocal(1, 0.1f), 1L, RATE, 1.0));
        layer.setLevel(0.0);
        assertEquals(raw.value(2) - 0.1, layer.readDouble(2), 1e-6);   // 帧 1 左
        assertEquals(raw.value(3) - 0.1, layer.readDouble(3), 1e-6);   // 帧 1 右
    }

    @Test
    @DisplayName("单声道内层按下标直取帧，不做声道拆分")
    void monoInnerMapsIndexStraightToFrame() {
        Tap raw = new Tap(6, 1);
        VocalMaterialSample layer = new VocalMaterialSample(raw);
        assertTrue(layer.attach(vocal(6, 0.5f), 0L, RATE, 1.0));
        layer.setLevel(0.0);
        assertEquals(raw.value(3) - 0.5, layer.readDouble(3), 1e-6);
    }

    @Test
    @DisplayName("半开时线性插值：level 0.5 就只减一半")
    void partialLevelIsLinear() {
        Tap raw = new Tap(4, 1);
        VocalMaterialSample layer = new VocalMaterialSample(raw);
        assertTrue(layer.attach(vocal(4, 1.0f), 0L, RATE, 1.0));
        layer.setLevel(0.5);
        assertEquals(raw.value(2) - 0.5, layer.readDouble(2), 1e-6);
    }

    @Test
    @DisplayName("投影系数只乘在素材上，原始素材一个采样都不动")
    void ratioScalesOnlyTheMaterial() {
        Tap raw = new Tap(4, 1);
        VocalMaterialSample layer = new VocalMaterialSample(raw);
        assertTrue(layer.attach(vocal(4, 1.0f), 0L, RATE, 1.25));
        layer.setLevel(0.0);
        assertEquals(raw.value(1) - 1.25, layer.readDouble(1), 1e-6);
        // 系数不管多大，level=1 那条恒等分支都不能被它影响。
        layer.setLevel(1.0);
        assertEquals(raw.value(1), layer.readDouble(1), 0.0);
    }

    @Test
    @DisplayName("level 收进 0..1：NaN、越界都退回安全值")
    void levelIsClamped() {
        Tap raw = new Tap(4, 1);
        VocalMaterialSample layer = new VocalMaterialSample(raw);
        assertTrue(layer.attach(vocal(4, 1.0f), 0L, RATE, 1.0));
        layer.setLevel(Double.NaN);
        assertEquals(1.0, layer.getLevel(), 0.0);
        layer.setLevel(3.0);
        assertEquals(1.0, layer.getLevel(), 0.0);
        layer.setLevel(-1.0);
        assertEquals(0.0, layer.getLevel(), 0.0);
    }

    @Test
    @DisplayName("采样率不一致、空素材、越界起点、坏系数一律拒接")
    void attachRefusesAnythingThatCouldMisalign() {
        Tap raw = new Tap(4, 2);
        VocalMaterialSample layer = new VocalMaterialSample(raw);
        assertFalse(layer.attach(vocal(4, .5f), 0L, 48_000, 1.0));     // 减错位的素材比不减更糟
        assertFalse(layer.attach(new float[0], 0L, RATE, 1.0));
        assertFalse(layer.attach(null, 0L, RATE, 1.0));
        assertFalse(layer.attach(vocal(4, .5f), -1L, RATE, 1.0));
        assertFalse(layer.attach(vocal(4, .5f), 0L, RATE, 0.0));
        assertFalse(layer.attach(vocal(4, .5f), 0L, RATE, Double.NaN));
        assertFalse(layer.hasMaterial());
    }

    @Test
    @DisplayName("卸下之后恢复直通，并把 level 归位")
    void detachRestoresIdentity() {
        Tap raw = new Tap(4, 1);
        VocalMaterialSample layer = new VocalMaterialSample(raw);
        assertTrue(layer.attach(vocal(4, 1.0f), 0L, RATE, 1.0));
        layer.setLevel(0.0);
        assertEquals(raw.value(1) - 1.0, layer.readDouble(1), 1e-6);
        layer.detach();
        assertFalse(layer.hasMaterial());
        assertEquals(1.0, layer.getLevel(), 0.0);
        assertEquals(raw.value(1), layer.readDouble(1), 0.0);
    }

    @Test
    @DisplayName("整块读取与插值都走混合后的素材，而不是绕过这一层")
    void bulkReadAndInterpolateGoThroughTheBlend() {
        Tap raw = new Tap(4, 2);
        VocalMaterialSample layer = new VocalMaterialSample(raw);
        assertTrue(layer.attach(vocal(4, 0.5f), 0L, RATE, 1.0));
        layer.setLevel(0.0);
        float[] out = new float[4];
        layer.read(1, out, 0, 2);                        // 帧 1..2，立体声共 4 个采样
        assertEquals(raw.value(2) - 0.5, out[0], 1e-6);
        assertEquals(raw.value(3) - 0.5, out[1], 1e-6);
        assertEquals(raw.value(4) - 0.5, out[2], 1e-6);
        // interpolate 也必须落在混合后的值上：读头在非 1.0 速率时走的就是这条路。
        assertEquals((raw.value(2) - 0.5 + raw.value(3) - 0.5) * 0.5, layer.interpolate(2.5), 1e-6);
    }

    @Test
    @DisplayName("素材层短到伸缩层接不住时，素材照样在链路里生效")
    void materialStillWorksWhenTheStretchLayerDeclines() {
        Tap raw = new Tap(4_000, 2);
        VocalMaterialSample layer = new VocalMaterialSample(raw);
        assertTrue(layer.attach(vocal(4_000, 0.25f), 0L, RATE, 1.0));
        // WSOLA 要求素材长到能装下两个序列加一个搜索窗（一首完整歌曲的量级），短样例接不住，
        // wrap 原样还回来 —— 还回来的必须正是这一层，否则 SoundFile 的组装就把素材层丢掉了。
        FloatSample stacked = WsolaStretchSample.wrap(layer);
        assertSame(layer, stacked);
        layer.setLevel(0.0);
        assertEquals(raw.value(100) - 0.25, stacked.readDouble(100), 1e-6);
    }

    @Test
    @DisplayName("不持有缓冲区：allocate 是空的、写操作不炸、cleanUp 把素材交回去")
    void holdsNoBufferOfItsOwn() {
        Tap raw = new Tap(4, 2);
        VocalMaterialSample layer = new VocalMaterialSample(raw);
        assertTrue(layer.attach(vocal(4, .5f), 0L, RATE, 1.0));
        layer.allocate(999, 2);                 // 不该 new 任何东西，也不该改帧数
        assertEquals(4, layer.getNumFrames());
        layer.writeDouble(3, 1.0);              // 只读装饰器：静默忽略
        assertEquals(raw.value(3), layer.readDouble(3), 0.0);   // level=1，本来就没动
        layer.cleanUp();
        assertFalse(layer.hasMaterial());
    }
}
