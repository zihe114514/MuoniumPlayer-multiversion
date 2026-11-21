package com.muoniumplayer.core.ncm.music.audio;

import com.muoniumplayer.core.ncm.music.acoustics.DirectPath;
import com.muoniumplayer.core.ncm.music.acoustics.RoomPlan;
import com.muoniumplayer.core.ncm.music.acoustics.SceneAcoustics;
import com.muoniumplayer.render.skija.SkijaNatives;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCapabilities;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.openal.EXTThreadLocalContext;
import org.lwjgl.openal.SOFTHRTF;
import org.lwjgl.openal.SOFTLoopback;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实无声卡 loopback 回归，不以源码字符串或能编译代替原生行为。
 * 缺扩展/格式/HRTF 时先独立探测并明确报告 assumption；有能力而总线失败必须失败。
 * 测试只拥有自己的设备/context，保存并恢复进入前的线程上下文与能力表。
 */
class OpenAlReverbBusTest {

    private static final int RATE = 48000;
    private static final int CAPACITY = 512;
    /** short loopback 可能有 ±1 LSB 的原生 dither，不把它当切歌残音。 */
    private static final double SILENCE_PEAK = 2.0 / 32768.0;

    @Test
    @DisplayName("loopback 同步读满有效帧；64/128/256/512 帧连续播放不因 NIO 或 STOPPED 熔断")
    void nativeOutputUsesValidFramesAndKeepsSourcesPlaying() throws Exception {
        try (Fixture fixture = new Fixture(RATE, CAPACITY, false)) {
            for (int frames : new int[] {64, 128, 256, 512}) {
                fixture.bus.flush();
                short[] mid = new short[CAPACITY];
                short[] side = new short[CAPACITY * 2];
                float[] out = new float[CAPACITY * 2];
                double energy = 0;
                for (int block = 0; block < 12; block++) {
                    Arrays.fill(mid, (short) 30000);
                    Arrays.fill(side, (short) 30000);
                    Arrays.fill(side, 0, frames * 2, (short) 0);
                    for (int i = 0; i < frames; i++) {
                        mid[i] = (short) (6000 * Math.sin((block * frames + i) * 0.17));
                    }
                    Arrays.fill(out, Float.NaN);
                    fixture.bus.render(mid, side, out, frames);
                    assertFalse(fixture.bus.isBroken(), "render must not silently fall back: frames=" + frames);
                    for (int i = 0; i < frames * 2; i++) {
                        assertTrue(Float.isFinite(out[i]), "native output must be initialized");
                        energy += out[i] * out[i];
                    }
                    for (int i = frames * 2; i < out.length; i++) {
                        assertTrue(Float.isNaN(out[i]), "must not write outside the valid frame range");
                    }
                }
                assertTrue(energy > 0.001, "supported loopback must actually render nonzero PCM");
                try (Binding ignored = Binding.forBus(fixture.bus)) {
                    for (int source : sources(fixture.bus)) {
                        assertEquals(AL10.AL_PLAYING, AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE));
                        assertEquals(1, AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED),
                                "exactly one guard frame's buffer remains, not a growing PCM backlog");
                    }
                    assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
                }
            }
        }
    }

    @Test
    void sceneKeepsNativeHrtfForOversizedCallbackAndShortRemainder() throws Exception {
        final int capacity = 192;
        try (Fixture fixture = new Fixture(RATE, capacity, false)) {
            final double worldOrigin = 29_999_900.0;
            SceneAcoustics.setListenerPose(worldOrigin, 0, worldOrigin, 0, 0, 1, 0, 1, 0);
            SceneAcoustics.applyTarget(scenePlan(1f, 1f, worldOrigin - 4, 0, worldOrigin));
            final double[] input = new double[(capacity * 5 + 73) * 2];
            for (int frame = 0; frame < input.length / 2; frame++) {
                input[frame * 2] = input[frame * 2 + 1] = 0.25 * Math.sin(frame * 0.41);
            }

            primeScene();
            final double[] large = input.clone();
            SceneAcoustics.render(large, 2, RATE);
            assertFalse(fixture.bus.isBroken());
            try (Binding ignored = Binding.forBus(fixture.bus)) {
                for (int source : sources(fixture.bus)) {
                    assertEquals(AL10.AL_PLAYING, AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE));
                    assertEquals(1, AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED));
                }
                assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
            }

            primeScene();
            final double[] split = new double[input.length];
            for (int offset = 0; offset < input.length; offset += capacity * 2) {
                final double[] block = Arrays.copyOfRange(input, offset, Math.min(input.length, offset + capacity * 2));
                SceneAcoustics.render(block, 2, RATE);
                System.arraycopy(block, 0, split, offset, block.length);
            }
            double error = 0;
            double signalL = 0;
            double signalR = 0;
            // Only compare the partial last chunk, which must also use the already running HRIR.
            for (int frame = capacity * 5; frame < input.length / 2; frame++) {
                final double dl = large[frame * 2] - split[frame * 2];
                final double dr = large[frame * 2 + 1] - split[frame * 2 + 1];
                error += dl * dl + dr * dr;
                signalL += split[frame * 2] * split[frame * 2];
                signalR += split[frame * 2 + 1] * split[frame * 2 + 1];
            }
            assertTrue(signalR > 0.03 && signalR > signalL * 1.2, "MC right source must favor the right ear");
            assertTrue(error < (signalL + signalR) * 1e-4,
                    "oversized output must match native output in the partial chunk: " + error);
        } finally {
            clearScene();
        }
    }

    @Test
    void sceneNativePcmHasHeadroomBeforeFinalLinkedLimiter() throws Exception {
        try (Fixture fixture = new Fixture(RATE, CAPACITY, false)) {
            SceneAcoustics.setListenerPose(0, 0, 0, 0, 0, 1, 0, 1, 0);
            SceneAcoustics.applyTarget(scenePlan(1f, 0.25f, 0, 0, 1));
            final double quiet = sceneToneEnergy(0.2, false);
            final double loud = sceneToneEnergy(1.6, false);
            assertFalse(fixture.bus.isBroken());
            assertTrue(quiet > 0.1);
            assertEquals(8.0, Math.sqrt(loud / quiet), 0.1,
                    "loud dry PCM must stay linear before the final linked limiter");
        } finally {
            clearScene();
        }
    }

    @Test
    void sceneKeepsNativeOutputForOpposedChannelsEvenWhenSidePositionsCoincide() throws Exception {
        try (Fixture fixture = new Fixture(RATE, CAPACITY, false)) {
            SceneAcoustics.setListenerPose(0, 0, 0, 0, 0, 1, 0, 1, 0);
            SceneAcoustics.applyTarget(scenePlan(1f, 1f, 0, 4, 0));
            final double inPhase = sceneToneEnergy(0.30, false);
            final double opposed = sceneToneEnergy(0.30, true);
            assertFalse(fixture.bus.isBroken());
            assertTrue(inPhase > 0.1);
            assertTrue(opposed > inPhase * 0.02 && opposed < inPhase * 0.10,
                    "coincident side ends should collapse to an audible, centered fold");
        } finally {
            clearScene();
        }
    }

    private static RoomPlan scenePlan(float gain, float dryGain, double x, double y, double z) {
        final float distance = 4f;
        return new RoomPlan(20000, 0, dryGain, 1, 0, 0, 0.28f, 0, 0, 1,
                null, null, null, gain, DirectPath.FC_OPEN, 0, distance,
                true, 1, 0.5f, true, x, y, z);
    }

    private static void primeScene() {
        SceneAcoustics.flush();
        SceneAcoustics.render(new double[4096 * 2], 2, RATE);
    }

    private static void clearScene() {
        SceneAcoustics.clearTarget();
        SceneAcoustics.setListenerPose(0, 0, 0, 0, 0, 1, 0, 1, 0);
        SceneAcoustics.flush();
        SceneAcoustics.render(new double[128], 2, RATE);
    }

    private static double sceneToneEnergy(double amplitude, boolean opposed) {
        primeScene();
        double total = 0;
        final double[] pcm = new double[64 * 2];
        for (int block = 0; block < 180; block++) {
            for (int frame = 0; frame < pcm.length / 2; frame++) {
                final double sample = amplitude * Math.sin((block * 64 + frame) * (2 * Math.PI / 32));
                pcm[frame * 2] = sample;
                pcm[frame * 2 + 1] = opposed ? -sample : sample;
            }
            SceneAcoustics.render(pcm, 2, RATE);
            if (block >= 32) {
                for (double value : pcm) total += value * value;
            }
        }
        return total;
    }

    @Test
    @DisplayName("有效帧短于数组容量时不读旧 PCM 尾，也不读取无效 side 尾")
    void shorterFramesDoNotReadStaleArrayTail() throws Exception {
        try (Fixture fixture = new Fixture(RATE, CAPACITY, false)) {
            short[] mid = new short[CAPACITY];
            short[] side = new short[CAPACITY * 2];
            Arrays.fill(mid, 64, mid.length, (short) 30000);
            Arrays.fill(side, 128, side.length, (short) 30000);
            float[] out = new float[128];
            for (int i = 0; i < 12; i++) {
                fixture.bus.render(mid, side, out, 64);
                assertFalse(fixture.bus.isBroken());
                assertTrue(peak(out) <= SILENCE_PEAK, "unused array tail must never enter OpenAL");
            }
        }
    }

    @Test
    @DisplayName("实际启用 HRTF 后左右、前后、上下的 mono 冲激响应有可测差异")
    void monoHrtfDistinguishesDirections() throws Exception {
        try (Fixture fixture = new Fixture(RATE, CAPACITY, false)) {
            // MC yaw=0 面南(+Z),右手是西(-X),不能沿用AL默认朝-Z时的左右命名。
            float[] left = response(fixture.bus, 4, 0, 0, false, 0, 0, 1, 12000);
            float[] right = response(fixture.bus, -4, 0, 0, false, 0, 0, 1, 12000);
            float[] front = response(fixture.bus, 0, 0, 4, false, 0, 0, 1, 12000);
            float[] back = response(fixture.bus, 0, 0, -4, false, 0, 0, 1, 12000);
            float[] up = response(fixture.bus, 0, 4, 0, false, 0, 0, 1, 12000);
            float[] down = response(fixture.bus, 0, -4, 0, false, 0, 0, 1, 12000);
            assertTrue(channelEnergy(left, 0) > channelEnergy(left, 1) * 1.2,
                    "a source to the player's left must favor the left ear");
            assertTrue(channelEnergy(right, 1) > channelEnergy(right, 0) * 1.2,
                    "a source to the player's right must favor the right ear");
            assertTrue(relativeDifference(front, back) > 0.03, "front/back must not be ordinary pan only");
            assertTrue(relativeDifference(up, down) > 0.03, "up/down must have different HRIRs");
        }
    }

    @Test
    @DisplayName("MC 全 yaw 方位的右手轴与 HRTF 右耳一致,不因单独 Z 反号镜像")
    void worldAxesAgreeWithMinecraftListenerRight() throws Exception {
        try (Fixture fixture = new Fixture(RATE, CAPACITY, false)) {
            for (int degrees : new int[] {0, 45, 90, 180, 270}) {
                final double yaw = Math.toRadians(degrees);
                final float fx = (float) -Math.sin(yaw);
                final float fz = (float) Math.cos(yaw);
                final double rightX = -Math.cos(yaw);
                final double rightZ = -Math.sin(yaw);
                final float[] right = response(fixture.bus, rightX * 4, 0, rightZ * 4,
                        false, fx, 0, fz, 12000);
                final float[] left = response(fixture.bus, -rightX * 4, 0, -rightZ * 4,
                        false, fx, 0, fz, 12000);
                assertTrue(channelEnergy(right, 1) > channelEnergy(right, 0) * 1.2,
                        "MC right must favor right ear: yaw=" + degrees);
                assertTrue(channelEnergy(left, 0) > channelEnergy(left, 1) * 1.2,
                        "MC left must favor left ear: yaw=" + degrees);
            }
        }
    }

    @Test
    @DisplayName("+S/-S 分别定位为 mono；side-only 不抵消且随源位和转头变化")
    void sideOnlyHasFiniteWorldWidthAndRespondsToHeadTurn() throws Exception {
        try (Fixture fixture = new Fixture(RATE, CAPACITY, false)) {
            float[] front = response(fixture.bus, 0, 0, 4, true, 0, 0, 1, 12000);
            float[] right = response(fixture.bus, 4, 0, 0, true, 0, 0, 1, 12000);
            assertTrue(energy(front) > 1e-6, "spatialized S must not cancel as a down-mixed stereo source");
            assertTrue(energy(right) > 1e-6, "side source must remain audible off-axis");
            assertTrue(relativeDifference(front, right) > 0.05, "S must not stay head-locked");
            pose(fixture.bus, 0, 0, 4, 0, 0, 1);
            float[][] before = sidePositions(fixture.bus);
            float[] turned = response(fixture.bus, 0, 0, 4, true, 1, 0, 0, 12000);
            float[][] after = sidePositions(fixture.bus);
            assertArrayEquals(before[0], after[0], 1e-6f, "head turn must not rotate physical endpoints");
            assertArrayEquals(before[1], after[1], 1e-6f, "head turn must not rotate physical endpoints");
            assertTrue(relativeDifference(front, turned) > 0.05, "turning the head must change the HRIR mix");
            // Source (0,0,+4), listener origin: source-to-ear tangent is +X in MC.
            assertEquals(0.35f, before[0][0], 1e-6f, "+S follows the positive tangent endpoint");
            assertEquals(-0.35f, before[1][0], 1e-6f, "-S follows the negative tangent endpoint");
        }
    }

    @Test
    @DisplayName("三条源增益/HF 对齐，rolloff 始终零；已处理的直射不再重复距离衰减")
    void gainAndDistanceContractsAreConsistentAcrossSources() throws Exception {
        try (Fixture fixture = new Fixture(RATE, CAPACITY, false)) {
            fixture.bus.setSourceRolloff(0.75f);
            try (Binding ignored = Binding.forBus(fixture.bus)) {
                assertEquals(AL10.AL_NONE, AL10.alGetInteger(AL10.AL_DISTANCE_MODEL));
                for (int source : sources(fixture.bus)) {
                    assertEquals(1f, AL10.alGetSourcef(source, AL10.AL_GAIN), 1e-6f);
                    assertEquals(0f, AL10.alGetSourcef(source, AL10.AL_ROLLOFF_FACTOR), 1e-6f);
                    assertEquals(0f, AL10.alGetSourcef(source, EXTEfx.AL_AIR_ABSORPTION_FACTOR), 1e-6f);
                }
                int filter = intField(fixture.bus, "directFilter");
                assertEquals(1f, EXTEfx.alGetFilterf(filter, EXTEfx.AL_LOWPASS_GAIN), 1e-6f);
                assertEquals(1f, EXTEfx.alGetFilterf(filter, EXTEfx.AL_LOWPASS_GAINHF), 1e-6f);
                assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
            }
            float[] near = response(fixture.bus, 0, 0, 4, false, 0, 0, 1, 12000);
            float[] far = response(fixture.bus, 0, 0, 32, false, 0, 0, 1, 12000);
            assertEquals(energy(near), energy(far), energy(near) * 0.05,
                    "distance attenuation is already owned by the PCM producer");
            float[] quarter = response(fixture.bus, 0, 0, 4, false, 0, 0, 1, 3000);
            assertEquals(0.25, Math.sqrt(energy(quarter) / energy(near)), 0.02,
                    "preprocessed amplitude must be preserved once, not squared");
        }
    }

    @Test
    @DisplayName("EFX send gain 使用真实 0..1 范围，原生错误不能被 Throwable 外壳掩盖")
    void sendGainStaysInNativeRange() throws Exception {
        try (Fixture fixture = new Fixture(RATE, CAPACITY, false)) {
            for (float gain : new float[] {0.9f, 0.1f, 1f, 0f}) {
                fixture.bus.setSendGain(gain);
                assertFalse(fixture.bus.isBroken(), "valid EFX gain must not produce AL_INVALID_VALUE");
                try (Binding ignored = Binding.forBus(fixture.bus)) {
                    int filter = intField(fixture.bus, "reverbSendFilter");
                    assertEquals(gain, EXTEfx.alGetFilterf(filter, EXTEfx.AL_LOWPASS_GAIN), 1e-6f);
                    assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
                }
            }
        }
    }

    @Test
    @DisplayName("flush 清排队最后一帧与旧 HRIR/延迟；后续静音不含上首冲激")
    void flushDropsQueuedPcmAndNativeTail() throws Exception {
        try (Fixture fixture = new Fixture(RATE, CAPACITY, false)) {
            short[] mid = new short[CAPACITY];
            short[] side = new short[CAPACITY * 2];
            float[] out = new float[CAPACITY * 2];
            mid[CAPACITY - 1] = 24000;
            side[(CAPACITY - 1) * 2] = 16000;
            side[(CAPACITY - 1) * 2 + 1] = -16000;
            fixture.bus.render(mid, side, out, CAPACITY);
            assertFalse(fixture.bus.isBroken());
            fixture.bus.flush();
            assertFalse(fixture.bus.isBroken());
            try (Binding ignored = Binding.forBus(fixture.bus)) {
                for (int source : sources(fixture.bus)) {
                    assertEquals(0, AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED));
                }
                assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
            }
            Arrays.fill(mid, (short) 0);
            Arrays.fill(side, (short) 0);
            for (int i = 0; i < 4; i++) {
                fixture.bus.render(mid, side, out, CAPACITY);
                assertFalse(fixture.bus.isBroken());
                assertTrue(peak(out) <= SILENCE_PEAK, "flush must not carry the last queued sample/old HRIR");
            }
            mid[0] = 12000;
            fixture.bus.render(mid, side, out, CAPACITY);
            assertFalse(fixture.bus.isBroken());
            assertTrue(energy(out) > 1e-4, "flush must allow the next track to restart normally");
        }
    }

    @Test
    @DisplayName("后台可创建 ready 总线，每个音频入口及 close 恢复其他线程局部上下文/能力表")
    void backgroundCreationAndEntriesRestoreAnotherContext() throws Exception {
        try (NativeContext sentinel = NativeContext.open(RATE)) {
            long beforeProcessOrThread = ALC10.alcGetCurrentContext();
            ALCCapabilities beforeAlc = ALC.getCapabilities();
            ALCapabilities beforeAl = AL.getCapabilities();
            try (Fixture fixture = new Fixture(RATE, CAPACITY, true)) {
                assertEquals(beforeProcessOrThread, ALC10.alcGetCurrentContext());
                assertSame(beforeAlc, ALC.getCapabilities());
                assertSame(beforeAl, AL.getCapabilities());
                fixture.bus.setSendGain(0f);
                fixture.bus.setReverbParams(0.5f, 0.5f, 0.1f, 1f,
                        0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.1f, 0f);
                pose(fixture.bus, 0, 0, 4, 0, 0, 1);
                fixture.bus.setSourceRolloff(1f);
                fixture.bus.setSideGain(1f);
                response(fixture.bus, 0, 0, 4, false, 0, 0, 1, 12000);
                fixture.bus.flush();
                fixture.bus.close();
                assertEquals(beforeProcessOrThread, ALC10.alcGetCurrentContext());
                assertEquals(sentinel.context, EXTThreadLocalContext.alcGetThreadContext());
                assertSame(beforeAlc, ALC.getCapabilities());
                assertSame(beforeAl, AL.getCapabilities());
            }
        }
    }

    @Test
    @DisplayName("不同采样率原子 ready 交接；关闭旧总线不清新引用也不跨线程关设备")
    void replacingRateLeavesOldBusOwnedUntilBoundaryClose() throws Exception {
        try (Fixture old = new Fixture(RATE, CAPACITY, false)) {
            try (NativeContext ignored = NativeContext.open(44100)) {
                // 独立证实第二采样率有 HRTF，不能以原采样率的通过代证。
            }
            OpenAlReverbBus next = createInBackground(44100, CAPACITY);
            assertNotNull(next);
            try {
                assertSame(next, OpenAlReverbBus.get());
                assertEquals(44100, next.sampleRate());
                response(old.bus, 0, 0, 4, false, 0, 0, 1, 12000);
                assertFalse(old.bus.isBroken(), "creator must not destroy the audio owner's previous bus");
                old.bus.close();
                assertSame(next, OpenAlReverbBus.get(), "old close must not clear a newer ready publication");
                pose(next, 0, 0, 4, 0, 0, 1);
                next.setSendGain(0f);
                next.setSideGain(1f);
                assertTrue(energy(response(next, 0, 0, 4, false, 0, 0, 1, 12000)) > 1e-4);
            } finally {
                next.close();
            }
        }
    }

    @Test
    @DisplayName("大世界平移不吞掉侧源0.7m间距,双耳响应与原点相同")
    void listenerRelativePositionsKeepPrecision() throws Exception {
        try (Fixture fixture = new Fixture(RATE, CAPACITY, false)) {
            final float[] near = responseAtOffset(fixture.bus, 0);
            final float[] far = responseAtOffset(fixture.bus, 20_000_000.5);
            final float[][] endpoints = sidePositions(fixture.bus);
            assertEquals(0.7f, Math.abs(endpoints[0][0] - endpoints[1][0]), 1e-5);
            assertTrue(energy(far) > 1e-6, "反相side不能因为坐标舍入而抵消");
            assertTrue(relativeDifference(near, far) < 0.01,
                    "世界平移不能改变HRIR混合");
        }
    }

    @Test
    @DisplayName("旧音频owner终止后可以接管并清旧队列,活线程拥有权不放宽")
    void terminatedAudioOwnerCanHandOff() throws Exception {
        try (NativeContext ignored = NativeContext.open(RATE)) {
            // 本机能力已独立确认。
        }
        final OpenAlReverbBus bus = createInBackground(RATE, CAPACITY);
        assertNotNull(bus);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread firstOwner = new Thread(() -> {
            try {
                pose(bus, 0, 0, 4, 0, 0, 1);
                final short[] mid = new short[CAPACITY];
                mid[CAPACITY - 1] = 24000;
                bus.render(mid, new short[CAPACITY * 2], new float[CAPACITY * 2], CAPACITY);
                assertFalse(bus.isBroken());
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "OpenAL-test-first-owner");
        firstOwner.start();
        firstOwner.join(20000);
        assertFalse(firstOwner.isAlive());
        assertNull(failure.get());
        try {
            pose(bus, 0, 0, 4, 0, 0, 1);
            assertFalse(bus.isBroken(), "已终止owner不能永久锁住同rate总线");
            final float[] silence = new float[CAPACITY * 2];
            bus.render(new short[CAPACITY], new short[CAPACITY * 2], silence, CAPACITY);
            assertTrue(peak(silence) <= SILENCE_PEAK, "交接不能留旧owner排队音频");
            assertTrue(energy(response(bus, 0, 0, 4, false, 0, 0, 1, 12000)) > 1e-4);
        } finally {
            bus.close();
        }
        assertNotSame(bus, OpenAlReverbBus.get(), "新owner必须可以释放旧context/device");
    }

    private static float[] responseAtOffset(OpenAlReverbBus bus, double offset) {
        bus.flush();
        bus.setListener(offset, 0, offset, 0, 0, 1, 0, 1, 0);
        bus.setSourcePose(offset, 0, offset + 4, 1, 1);
        bus.setSidePose(offset, 0, offset + 4);
        final short[] mid = new short[CAPACITY];
        final short[] side = new short[CAPACITY * 2];
        side[0] = 12000;
        side[1] = -12000;
        final float[] out = new float[CAPACITY * 2];
        final float[] result = new float[CAPACITY * 4];
        bus.render(mid, side, out, CAPACITY);
        assertFalse(bus.isBroken());
        System.arraycopy(out, 0, result, 0, out.length);
        Arrays.fill(side, (short) 0);
        bus.render(mid, side, out, CAPACITY);
        assertFalse(bus.isBroken());
        System.arraycopy(out, 0, result, out.length, out.length);
        return result;
    }

    @Test
    @DisplayName("无效有效帧数显式熔断并清输出，不把越界或旧输出伪装成正常 readback")
    void invalidFrameCountFailsClosed() throws Exception {
        try (Fixture fixture = new Fixture(RATE, CAPACITY, false)) {
            float[] out = new float[CAPACITY * 2];
            Arrays.fill(out, 1f);
            fixture.bus.render(new short[CAPACITY], new short[CAPACITY * 2], out, CAPACITY + 1);
            assertTrue(fixture.bus.isBroken());
            assertEquals(0, peak(out));
        }
    }

    private static float[] response(OpenAlReverbBus bus, double x, double y, double z,
                                    boolean sideOnly, float fx, float fy, float fz, int amplitude) {
        bus.flush();
        pose(bus, x, y, z, fx, fy, fz);
        short[] mid = new short[CAPACITY];
        short[] side = new short[CAPACITY * 2];
        if (sideOnly) {
            side[0] = (short) amplitude;
            side[1] = (short) -amplitude;
        } else {
            mid[0] = (short) amplitude;
        }
        float[] out = new float[CAPACITY * 2];
        float[] response = new float[CAPACITY * 4];
        bus.render(mid, side, out, CAPACITY);
        assertFalse(bus.isBroken());
        System.arraycopy(out, 0, response, 0, out.length);
        Arrays.fill(mid, (short) 0);
        Arrays.fill(side, (short) 0);
        bus.render(mid, side, out, CAPACITY);
        assertFalse(bus.isBroken());
        System.arraycopy(out, 0, response, out.length, out.length);
        return response;
    }

    private static void pose(OpenAlReverbBus bus, double x, double y, double z,
                             float fx, float fy, float fz) {
        bus.setListener(0, 0, 0, fx, fy, fz, 0, 1, 0);
        bus.setSourcePose(x, y, z, 1f, 1f);
        bus.setSidePose(x, y, z);
        bus.setSourceRolloff(0f);
        assertFalse(bus.isBroken());
    }

    private static double energy(float[] values) {
        double result = 0;
        for (float value : values) {
            result += value * value;
        }
        return result;
    }

    private static double channelEnergy(float[] values, int channel) {
        double result = 0;
        for (int i = channel; i < values.length; i += 2) {
            result += values[i] * values[i];
        }
        return result;
    }

    private static double peak(float[] values) {
        double result = 0;
        for (float value : values) {
            result = Math.max(result, Math.abs(value));
        }
        return result;
    }

    private static double relativeDifference(float[] first, float[] second) {
        double difference = 0;
        for (int i = 0; i < first.length; i++) {
            double delta = first[i] - second[i];
            difference += delta * delta;
        }
        return Math.sqrt(difference / Math.max(1e-12, energy(first) + energy(second)));
    }

    private static Field field(String name) throws Exception {
        Field field = OpenAlReverbBus.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static int intField(OpenAlReverbBus bus, String name) throws Exception {
        return field(name).getInt(bus);
    }

    private static int[] sources(OpenAlReverbBus bus) throws Exception {
        return (int[]) field("sources").get(bus);
    }

    private static float[][] sidePositions(OpenAlReverbBus bus) throws Exception {
        try (Binding ignored = Binding.forBus(bus)) {
            float[][] positions = new float[2][3];
            int[] sources = sources(bus);
            for (int i = 0; i < 2; i++) {
                float[] x = new float[1];
                float[] y = new float[1];
                float[] z = new float[1];
                AL10.alGetSource3f(sources[i + 1], AL10.AL_POSITION, x, y, z);
                positions[i] = new float[] {x[0], y[0], z[0]};
            }
            assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
            return positions;
        }
    }

    private static OpenAlReverbBus createInBackground(int rate, int capacity) throws Exception {
        AtomicReference<OpenAlReverbBus> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread creator = new Thread(() -> {
            try {
                result.set(OpenAlReverbBus.createIfAvailable(rate, capacity));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "OpenAL-test-creator");
        creator.start();
        creator.join(20000);
        assertFalse(creator.isAlive(), "native context preparation must finish");
        assertNull(failure.get(), "native context creator failed");
        return result.get();
    }

    private static final class Fixture implements AutoCloseable {
        final OpenAlReverbBus bus;

        Fixture(int rate, int capacity, boolean background) throws Exception {
            if (SkijaNatives.mappedPlatform().startsWith("android")) {
                unavailable("Android deliberately does not create a second loopback device");
            }
            if ("false".equalsIgnoreCase(System.getProperty("muonium.acoustics.eax", "true"))) {
                unavailable("muonium.acoustics.eax=false deliberately disables this backend");
            }
            try (NativeContext ignored = NativeContext.open(rate)) {
                // 独立能力探测；有支持时 createIfAvailable 的 null/broken 是失败而非 skip。
            }
            bus = background ? createInBackground(rate, capacity)
                    : OpenAlReverbBus.createIfAvailable(rate, capacity);
            assertNotNull(bus, "supported native HRTF loopback must create the real bus");
            assertFalse(bus.isBroken());
            assertEquals(rate, bus.sampleRate());
            pose(bus, 0, 0, 4, 0, 0, 1);
            bus.setSideGain(1f);
            bus.setSendGain(0f);
        }

        @Override
        public void close() {
            bus.close();
        }
    }

    /** 仅在不支持的环境标记可见的边界，不吞有能力时的业务失败。 */
    private static void unavailable(String reason) {
        System.out.println("[OpenAlReverbBusTest] native loopback unavailable: " + reason);
        assumeTrue(false, reason);
    }

    private static ALCapabilities currentAl() {
        try {
            return AL.getCapabilities();
        } catch (IllegalStateException absent) {
            return null;
        }
    }

    private static final class Binding implements AutoCloseable {
        final ALCCapabilities previousAlc = ALC.getCapabilities();
        final ALCapabilities previousAl = currentAl();
        final long previousContext;

        Binding(long context, ALCCapabilities alcCaps, ALCapabilities alCaps) {
            ALC.setCapabilities(alcCaps);
            previousContext = EXTThreadLocalContext.alcGetThreadContext();
            assertTrue(EXTThreadLocalContext.alcSetThreadContext(context));
            AL.setCurrentThread(alCaps);
        }

        static Binding forBus(OpenAlReverbBus bus) throws Exception {
            return new Binding(field("context").getLong(bus),
                    (ALCCapabilities) field("alcCaps").get(bus), (ALCapabilities) field("alCaps").get(bus));
        }

        @Override
        public void close() {
            assertTrue(EXTThreadLocalContext.alcSetThreadContext(previousContext));
            AL.setCurrentThread(previousContext == 0 ? null : previousAl);
            ALC.setCapabilities(previousAlc);
        }
    }

    /** 带独立 HRTF 能力探测的原生上下文；不调用进程级 makeCurrent。 */
    private static final class NativeContext implements AutoCloseable {
        final ALCCapabilities previousAlc;
        final ALCapabilities previousAl;
        long previousContext;
        long device;
        long context;
        ALCCapabilities alcCaps;
        boolean bound;

        private NativeContext() {
            previousAlc = ALC.getCapabilities();
            previousAl = currentAl();
        }

        static NativeContext open(int rate) {
            NativeContext nativeContext;
            try {
                nativeContext = new NativeContext();
            } catch (LinkageError unavailableNative) {
                if (System.getProperty("os.name", "").startsWith("Windows")) {
                    throw unavailableNative;
                }
                unavailable("native OpenAL library not installed: " + unavailableNative);
                throw unavailableNative;
            }
            boolean success = false;
            try {
                ALCCapabilities global = ALC.createCapabilities(0);
                if (!global.ALC_SOFT_loopback) {
                    unavailable("ALC_SOFT_loopback is missing");
                }
                nativeContext.device = SOFTLoopback.alcLoopbackOpenDeviceSOFT((ByteBuffer) null);
                assertNotEquals(0, nativeContext.device, "advertised loopback extension must open a device");
                nativeContext.alcCaps = ALC.createCapabilities(nativeContext.device);
                ALCCapabilities caps = nativeContext.alcCaps;
                if (!caps.ALC_EXT_thread_local_context || !caps.ALC_SOFT_HRTF || !caps.ALC_EXT_EFX) {
                    unavailable("extensions: TLS=" + caps.ALC_EXT_thread_local_context
                            + ", HRTF=" + caps.ALC_SOFT_HRTF + ", EFX=" + caps.ALC_EXT_EFX);
                }
                if (!SOFTLoopback.alcIsRenderFormatSupportedSOFT(nativeContext.device, rate,
                        SOFTLoopback.ALC_STEREO_SOFT, SOFTLoopback.ALC_SHORT_SOFT)) {
                    unavailable("stereo PCM16 render format unsupported at " + rate + "Hz");
                }
                int[] attributes = {ALC10.ALC_FREQUENCY, rate,
                        SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT, SOFTLoopback.ALC_STEREO_SOFT,
                        SOFTLoopback.ALC_FORMAT_TYPE_SOFT, SOFTLoopback.ALC_SHORT_SOFT,
                        SOFTHRTF.ALC_HRTF_SOFT, ALC10.ALC_TRUE, 0};
                nativeContext.context = ALC10.alcCreateContext(nativeContext.device, attributes);
                assertNotEquals(0, nativeContext.context, "supported format must create a context");
                nativeContext.previousContext = EXTThreadLocalContext.alcGetThreadContext();
                assertTrue(EXTThreadLocalContext.alcSetThreadContext(nativeContext.context));
                nativeContext.bound = true;
                AL.createCapabilities(caps);
                int enabled = ALC10.alcGetInteger(nativeContext.device, SOFTHRTF.ALC_HRTF_SOFT);
                int status = ALC10.alcGetInteger(nativeContext.device, SOFTHRTF.ALC_HRTF_STATUS_SOFT);
                assertEquals(ALC10.ALC_NO_ERROR, ALC10.alcGetError(nativeContext.device));
                if (enabled != ALC10.ALC_TRUE) {
                    unavailable("HRTF request not honored: rate=" + rate + ", status=" + status);
                }
                System.out.println("[OpenAlReverbBusTest] native " + AL10.alGetString(AL10.AL_VERSION)
                        + ", rate=" + rate + ", HRTF=" + enabled + ", status=" + status);
                success = true;
                return nativeContext;
            } finally {
                if (!success) {
                    nativeContext.close();
                }
            }
        }

        @Override
        public void close() {
            try {
                if (alcCaps != null) {
                    ALC.setCapabilities(alcCaps);
                }
                if (bound) {
                    assertTrue(EXTThreadLocalContext.alcSetThreadContext(previousContext));
                    AL.setCurrentThread(previousContext == 0 ? null : previousAl);
                    bound = false;
                }
                if (context != 0) {
                    ALC10.alcDestroyContext(context);
                    context = 0;
                }
                if (device != 0) {
                    assertTrue(ALC10.alcCloseDevice(device));
                    device = 0;
                }
            } finally {
                ALC.setCapabilities(previousAlc);
            }
        }
    }
}
