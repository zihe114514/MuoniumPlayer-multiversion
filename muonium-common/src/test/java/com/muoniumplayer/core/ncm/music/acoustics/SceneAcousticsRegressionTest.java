package com.muoniumplayer.core.ncm.music.acoustics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import com.muoniumplayer.testsupport.ProjectPaths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneAcousticsRegressionTest {

    private static final int RATE = 48000;

    @AfterEach
    void reset() {
        SceneAcoustics.clearTarget();
        SceneAcoustics.flush();
        AcousticsDebugTuning.clear();
        pose(0, 0, 0, 0, 0, 1);
    }

    private static RoomPlan direct(float gain, double cutoff, float pan, float distance) {
        return speaker(gain, cutoff, pan, distance, distance, 0, 0, distance);
    }

    private static RoomPlan speaker(float gain, double cutoff, float pan, float distance, float path,
                                    double x, double y, double z) {
        return new RoomPlan(20000, 0, 1, 1, 0, 0, 0.28f,
                0, 0, 1, new float[0], new float[0], new float[0],
                gain, cutoff, pan, distance, false, 1, 0.5f,
                true, x, y, z, 0f, path > distance, path);
    }

    private static void pose(double x, double y, double z, float fx, float fy, float fz) {
        SceneAcoustics.setListenerPose(x, y, z, fx, fy, fz, 0, 1, 0);
    }

    @Test
    @DisplayName("立体声 side 也吃同一份直射衰减,不能隔墙仍贴头响")
    void sideCannotBypassAttenuation() {
        SceneAcoustics.applyTarget(direct(0.02f, DirectPath.FC_OPEN, 0, 0));
        SceneAcoustics.flush();
        final double[] pcm = new double[1024 * 2];
        for (int f = 0; f < 1024; f++) {
            pcm[f * 2] = 0.4;
            pcm[f * 2 + 1] = -0.4;
        }
        SceneAcoustics.render(pcm, 2, RATE);
        double peak = 0;
        for (double v : pcm) peak = Math.max(peak, Math.abs(v));
        assertTrue(peak < 0.01, "side 必须随声源衰减: " + peak);
    }

    @Test
    @DisplayName("立体声 side 也吃遮挡滤波,不留清晰的非定位旁路")
    void sideCannotBypassOcclusionFilter() {
        SceneAcoustics.applyTarget(direct(1f, 600, 0, 0));
        SceneAcoustics.flush();
        final double[] pcm = new double[4096 * 2];
        for (int f = 0; f < 4096; f++) {
            final double sample = 0.2 * Math.sin(2 * Math.PI * 6000 * f / RATE);
            pcm[f * 2] = sample;
            pcm[f * 2 + 1] = -sample;
        }
        SceneAcoustics.render(pcm, 2, RATE);
        double energy = 0;
        for (int f = 512; f < 4096; f++) {
            energy += pcm[f * 2] * pcm[f * 2];
        }
        assertTrue(energy < (4096 - 512) * 0.02 * 0.03,
                "6kHz side 不能绕过600Hz低通: " + energy);
    }

    @Test
    @DisplayName("正前方近场音箱仍有传播时间,不能被中性 pan 的静息闸跳过")
    void centeredSpeakerStillHasPropagation() {
        SceneAcoustics.applyTarget(direct(1f, DirectPath.FC_OPEN, 0, 4));
        SceneAcoustics.flush();
        int first = -1;
        for (int block = 0; block < 16; block++) {
            final double[] pcm = new double[128];
            if (block == 0) pcm[0] = pcm[1] = 0.5;
            SceneAcoustics.render(pcm, 2, RATE);
            for (int f = 0; f < 64; f++) {
                if (Math.abs(pcm[f * 2]) > 0.05 && first < 0) first = block * 64 + f;
            }
        }
        assertTrue(first > 0, "近场正前也必须走直射链");
        assertTrue(Math.abs(first - 4.0 / 343 * RATE) <= 1,
                "传播时间应为4m/343: " + first);
    }

    @Test
    @DisplayName("side 与 mid 同步传播,不能先听见伴奏再听见声源")
    void sideAndMidSharePropagation() {
        SceneAcoustics.applyTarget(direct(0.5f, DirectPath.FC_OPEN, 0, 8));
        SceneAcoustics.flush();
        int first = -1;
        for (int block = 0; block < 32; block++) {
            final double[] pcm = new double[128];
            if (block == 0) { pcm[0] = 0.8; pcm[1] = -0.8; }
            SceneAcoustics.render(pcm, 2, RATE);
            for (int f = 0; f < 64; f++) {
                if (Math.abs(pcm[f * 2]) > 0.005 && first < 0) first = block * 64 + f;
            }
        }
        assertTrue(Math.abs(first - 8.0 / 343 * RATE) <= 1,
                "side 必须与mid同延迟: " + first);
    }

    @Test
    @DisplayName("反射不复用直射遮挡后的信号:直射闷不意味着绕墙反射也被低通两次")
    void reflectionsHaveIndependentExcitation() {
        AcousticsDebugTuning.tailMakeup = 0;
        SceneAcoustics.applyTarget(new RoomPlan(20000, 0.5f, 0, 1,
                0.004f, 0.3f, 0.28f, 0.3f, 0.2f, 1,
                new float[] {0.010f}, new float[] {0.3f}, new float[] {0.3f},
                0.001f, 200, 0, 0, false, 1, 0.5f, true, 0, 64, 0));
        SceneAcoustics.flush();
        final double[] pcm = new double[2048 * 2];
        pcm[0] = pcm[1] = 0.5;
        SceneAcoustics.render(pcm, 2, RATE);
        double peak = 0;
        for (int f = 475; f < 500; f++) peak = Math.max(peak, Math.abs(pcm[f * 2]));
        assertTrue(peak > 0.06, "反射应该由原始源信号激励,不是200Hz穿墙直射: " + peak);
    }

    @Test
    @DisplayName("玩家声源的早期反射不再被场景 wet 二次压低")
    void playerReflectionsKeepTheirPhysicalAmplitude() {
        final RoomPlan lowWet = new RoomPlan(20000, 0.05f, 1f, 1f, 0.004f, 0.3f, 0.28f,
                0.3f, 0.2f, 1f,
                new float[] {0.010f}, new float[] {0.3f}, new float[] {0.3f},
                1f, DirectPath.FC_OPEN, 0f, 0f,
                false, 1f, 0.5f, false, 0, 64, 0);
        SceneAcoustics.applyTarget(lowWet);
        SceneAcoustics.flush();
        for (int block = 0; block < 120; block++) {
            SceneAcoustics.render(new double[128], 2, RATE);
        }
        final double[] pulse = new double[2048 * 2];
        pulse[0] = pulse[1] = 0.5;
        SceneAcoustics.render(pulse, 2, RATE);
        double peak = 0;
        for (int f = 470; f < 510; f++) {
            peak = Math.max(peak, Math.abs(pulse[f * 2]));
        }
        assertTrue(peak > 0.08, "ER 不应再次乘低 wet: " + peak);
    }

    @Test
    @DisplayName("音箱移动时距离变化不使用逐样本变速读头")
    void movingSpeakerUsesCrossfadedFixedDelayReads() throws java.io.IOException {
        final Path source = ProjectPaths.sharedJava().resolve(
                "com/muoniumplayer/core/ncm/music/acoustics/SceneAcoustics.java");
        final String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        assertTrue(text.contains("directDelayOld") && text.contains("directDelayNew"));
        assertTrue(text.contains("delayCrossfade"));
        assertTrue(!text.contains("directDelayN + (nextDirectDelay - directDelayN) * fraction"),
                "距离延迟不能逐样本插值读头,否则会产生 Doppler 变调");
        assertTrue(!text.contains("wetDelayN + (nextWetDelay - wetDelayN) * fraction"),
                "湿声传播延迟不能逐样本插值读头");
    }

    @Test
    @DisplayName("清除水下目标后快速恢复带宽,不会拖着闷响数秒")
    void bandRecoversAfterLeavingWater() {
        SceneAcoustics.applyTarget(new RoomPlan(420, 0, 0.82f, 0.82f, 0, 0, 0.28f,
                0, 0, 1, new float[0], new float[0], new float[0]));
        SceneAcoustics.flush();
        SceneAcoustics.render(new double[128], 2, RATE);
        SceneAcoustics.clearTarget();
        for (int block = 0; block < 700; block++) SceneAcoustics.render(new double[128], 2, RATE);
        final double[] pcm = new double[4096 * 2];
        double inputEnergy = 0;
        for (int f = 0; f < 4096; f++) {
            final double v = 0.2 * Math.sin(2 * Math.PI * 6000 * f / RATE);
            pcm[f * 2] = pcm[f * 2 + 1] = v;
            if (f >= 256) inputEnergy += v * v;
        }
        SceneAcoustics.render(pcm, 2, RATE);
        double outputEnergy = 0;
        for (int f = 256; f < 4096; f++) outputEnergy += pcm[f * 2] * pcm[f * 2];
        assertTrue(outputEnergy > inputEnergy * 0.94,
                "退出水下后6kHz必须恢复: out=" + outputEnergy + " in=" + inputEnergy);
    }

    @Test
    @DisplayName("声道从 mono 换回 stereo 时第二声道也有独立低通,不能透过高频")
    void channelLayoutChangeRebuildsBandState() {
        SceneAcoustics.applyTarget(new RoomPlan(420, 0, 1, 1, 0, 0, 0.28f,
                0, 0, 1, new float[0], new float[0], new float[0]));
        SceneAcoustics.flush();
        SceneAcoustics.render(new double[64], 1, RATE);
        final double[] pcm = new double[2048 * 2];
        for (int f = 0; f < 2048; f++) {
            final double v = 0.2 * Math.sin(2 * Math.PI * 6000 * f / RATE);
            pcm[f * 2] = pcm[f * 2 + 1] = v;
        }
        SceneAcoustics.render(pcm, 2, RATE);
        for (int f = 0; f < 2048; f++) assertEquals(pcm[f * 2], pcm[f * 2 + 1], 1e-9);
    }

    @Test
    @DisplayName("flush 清空双耳与传播历史,新歌不能读到旧歌样本")
    void flushClearsStereoPropagation() {
        SceneAcoustics.applyTarget(direct(0.5f, DirectPath.FC_OPEN, 0.3f, 96));
        SceneAcoustics.flush();
        final double[] impulse = new double[128];
        impulse[0] = 0.6; impulse[1] = -0.2;
        SceneAcoustics.render(impulse, 2, RATE);
        SceneAcoustics.flush();
        double peak = 0;
        for (int block = 0; block < 400; block++) {
            final double[] silence = new double[128];
            SceneAcoustics.render(silence, 2, RATE);
            for (double v : silence) peak = Math.max(peak, Math.abs(v));
        }
        assertTrue(peak < 1e-8, "清态后不能出现旧样本: " + peak);
    }

    @Test
    void fallbackUsesLiveHeadPoseAndChosenArrivalRatherThanSampledPan() {
        pose(0, 0, 0, 0, 0, 1);
        // The selected edge is to the right, even though the older sampled pan says left.
        SceneAcoustics.applyTarget(speaker(1f, DirectPath.FC_OPEN, -1f, 4, 7, -4, 0, 0));
        SceneAcoustics.flush();
        final double[] right = impulse(0.3, 0.3);
        assertEquals(7.0 / 343.0 * RATE, firstArrival(right, 1), 1);
        assertTrue(firstArrival(right, 0) - firstArrival(right, 1) >= 28);
        assertTrue(energy(right, 1, 0) > 2 * energy(right, 0, 0));

        // No new world plan and no flush: tick pose should flip the ITD and the shadow.
        pose(0, 0, 0, 0, 0, -1);
        for (int block = 0; block < 30; block++) SceneAcoustics.render(new double[128], 2, RATE);
        final double[] left = impulse(0.3, 0.3);
        assertEquals(7.0 / 343.0 * RATE, firstArrival(left, 0), 1);
        assertTrue(firstArrival(left, 1) - firstArrival(left, 0) >= 28);
        assertTrue(energy(left, 0, 0) > 2 * energy(left, 1, 0));
    }

    @Test
    void fallbackSpectralCuesDifferAboveBelowAndBehind() {
        final double front = directionalBrightness(0, 0, 4);
        final double back = directionalBrightness(0, 0, -4);
        final double up = directionalBrightness(0, 4, 0);
        final double down = directionalBrightness(0, -4, 0);
        assertTrue(front > back * 1.10, "front=" + front + " back=" + back);
        assertTrue(up > down * 1.20, "up=" + up + " down=" + down);
    }

    @Test
    void nearDistanceChangesLevelWithoutDarkeningOpenPath() {
        final double[] input = tone(8192, 6000, 0.20);
        final double[] close = renderTone(input, direct((float) DirectPath.distanceAttenuation(2),
                DirectPath.totalCutoffHz(0, 2), 0, 2));
        final double[] far = renderTone(input, direct((float) DirectPath.distanceAttenuation(7),
                DirectPath.totalCutoffHz(0, 7), 0, 7));
        final double[] wall = renderTone(input, direct((float) DirectPath.directGain(7, 1),
                DirectPath.totalCutoffHz(1, 7), 0, 7));
        final int start = 2048;
        final double farGain = DirectPath.distanceAttenuation(7);
        assertTrue(energy(close, 0, start) > 2 * energy(far, 0, start));
        assertTrue(energy(far, 0, start) > 0.85 * farGain * farGain * energy(input, 0, start));
        assertTrue(energy(wall, 0, start) < 0.00002 * energy(far, 0, start));
    }

    @Test
    void oppositeChannelsKeepAWorldCenteredDirectSignalAtDistance() {
        pose(0, 0, 0, 0, 0, 1);
        final RoomPlan plan = speaker(1f, DirectPath.FC_OPEN, 0f, 32, 32, -32, 0, 0);
        final double[] input = tone(9216, 3600, 0.3);
        for (int frame = 0; frame < input.length / 2; frame++) input[frame * 2 + 1] *= -1;
        final double[] right = renderTone(input, plan);
        pose(0, 0, 0, 0, 0, -1);
        final double[] left = renderTone(input, plan);
        assertTrue(energy(right, 1, 5120) > 0.02 * energy(input, 1, 5120));
        assertTrue(energy(right, 1, 5120) > 1.8 * energy(right, 0, 5120));
        assertTrue(energy(left, 0, 5120) > 1.8 * energy(left, 1, 5120));
    }

    @Test
    void antiPhaseExcitesIndependentReflectionEvenWhenDirectPathIsMuted() {
        AcousticsDebugTuning.tailMakeup = 0;
        SceneAcoustics.applyTarget(new RoomPlan(20000, 0.5f, 0, 1,
                0.004f, 0.3f, 0.28f, 0.3f, 0.2f, 1,
                new float[] {0.01f}, new float[] {0.4f}, new float[] {0.4f},
                0f, 200, 0, 2, false, 1, 0.5f, true, 0, 0, 2));
        SceneAcoustics.flush();
        final double[] reflected = impulse(0.8, -0.8);
        assertEquals(2.0 / 343.0 * RATE + 480, firstArrival(reflected, 0), 1.0);
        assertTrue(energy(reflected, 0, 0) > 0.0008);
    }

    @Test
    void inFlightDistanceFadeUsesTwoFixedTapsAndLatchesLatestAfterward() {
        // Exact integral propagation delays and a center mono source isolate transport.
        final RoomPlan initial = direct(1, DirectPath.FC_OPEN, 0, metersForFrames(48));
        final RoomPlan next = direct(1, DirectPath.FC_OPEN, 0, metersForFrames(336));
        final RoomPlan latest = direct(1, DirectPath.FC_OPEN, 0, metersForFrames(672));
        final int change = 1024;
        final int fadeFrames = 1200;
        final double[] in = tone(4096, 715, 0.35);
        final double[] out = new double[in.length];
        SceneAcoustics.applyTarget(initial);
        SceneAcoustics.flush();
        for (int base = 0; base < in.length / 2; base += 64) {
            if (base == change) SceneAcoustics.applyTarget(next);
            if (base == change + 128) SceneAcoustics.applyTarget(latest);
            final double[] pcm = Arrays.copyOfRange(in, base * 2, (base + 64) * 2);
            SceneAcoustics.render(pcm, 2, RATE);
            System.arraycopy(pcm, 0, out, base * 2, pcm.length);
        }
        for (int frame = change; frame < change + fadeFrames; frame++) {
            final double fade = (frame - change + 1.0) / fadeFrames;
            final double expected = in[(frame - 48) * 2] * (1.0 - fade) + in[(frame - 336) * 2] * fade;
            assertEquals(expected, out[frame * 2], 1e-6, "fixed taps at frame " + frame);
        }
        for (int frame = 3500; frame < in.length / 2; frame++) {
            assertEquals(in[(frame - 672) * 2], out[frame * 2], 1e-6, "latest tap at frame " + frame);
        }
    }

    private static float metersForFrames(int frames) {
        return (float) (frames * 343.0 / RATE);
    }

    private static double directionalBrightness(double x, double y, double z) {
        final RoomPlan plan = speaker(1, DirectPath.FC_OPEN, 0, 4, 4, x, y, z);
        final double[] lows = renderTone(tone(4096, 300, 0.2), plan);
        final double[] highs = renderTone(tone(4096, 8000, 0.2), plan);
        return energy(highs, 0, 1024) / energy(lows, 0, 1024);
    }

    private static double[] impulse(double l, double r) {
        final double[] output = new double[2048 * 2];
        for (int base = 0; base < output.length / 2; base += 64) {
            final double[] pcm = new double[128];
            if (base == 0) { pcm[0] = l; pcm[1] = r; }
            SceneAcoustics.render(pcm, 2, RATE);
            System.arraycopy(pcm, 0, output, base * 2, pcm.length);
        }
        return output;
    }

    private static int firstArrival(double[] pcm, int channel) {
        for (int frame = 0; frame < pcm.length / 2; frame++) {
            if (Math.abs(pcm[frame * 2 + channel]) > 0.004) return frame;
        }
        return -1;
    }

    private static double[] renderTone(double[] input, RoomPlan plan) {
        SceneAcoustics.applyTarget(plan);
        SceneAcoustics.flush();
        final double[] out = input.clone();
        SceneAcoustics.render(out, 2, RATE);
        return out;
    }

    private static double[] tone(int frames, int hz, double amplitude) {
        final double[] input = new double[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            input[frame * 2] = input[frame * 2 + 1] = amplitude * Math.sin(2.0 * Math.PI * hz * frame / RATE);
        }
        return input;
    }

    private static double energy(double[] pcm, int channel, int startFrame) {
        double sum = 0;
        for (int i = startFrame * 2 + channel; i < pcm.length; i += 2) sum += pcm[i] * pcm[i];
        return sum;
    }
}
