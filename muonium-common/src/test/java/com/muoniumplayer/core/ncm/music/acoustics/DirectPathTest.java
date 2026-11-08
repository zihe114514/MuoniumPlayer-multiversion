package com.muoniumplayer.core.ncm.music.acoustics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 世界声源共享件的判定表:直射链纯数学({@link DirectPath})对表 + 状态
 * 与持久化({@link SpeakerState}/{@code SpeakerStore})的防御性往返。
 */
class DirectPathTest {

    @Test
    @DisplayName("距离衰减:8m 内全增益,之后按 inverse 模型平滑下滑")
    void distanceAttenuationTable() {
        assertEquals(1.0, DirectPath.distanceAttenuation(0.0), 1e-9);
        assertEquals(1.0, DirectPath.distanceAttenuation(8.0), 1e-9);
        // 24m:over=2 → 1/5
        assertEquals(0.20, DirectPath.distanceAttenuation(24.0), 1e-3);
        // 48m:over=5 → 1/26
        assertEquals(1.0 / 26.0, DirectPath.distanceAttenuation(48.0), 1e-3);
        // 96m:over=11 → 1/122 ≈ 0.0082(-41.7dB)
        assertTrue(DirectPath.distanceAttenuation(96.0) < 0.01,
                "96m=" + DirectPath.distanceAttenuation(96.0));
        // 单调下降,无突变(相邻格差有界)
        double prev = DirectPath.distanceAttenuation(0);
        for (int d = 1; d <= 96; d++) {
            double cur = DirectPath.distanceAttenuation(d);
            assertTrue(cur <= prev + 1e-12, "d=" + d);
            prev = cur;
        }
    }

    @Test
    @DisplayName("遮挡:无遮挡全带宽;一块实心墙 fc≈1kHz;两块起步即 200Hz 下限")
    void occlusionCutoffTable() {
        assertEquals(DirectPath.FC_OPEN, DirectPath.totalCutoffHz(0.0, 1.0), 1e-6);
        // 一块实心墙:ratio=exp(-3)≈0.0498 → fc≈996Hz
        final double one = DirectPath.totalCutoffHz(1.0, 1.0);
        assertEquals(20000.0 * Math.exp(-3.0), one, 5.0);
        // 两块:exp(-6)≈0.00248 → 496Hz,已贴 200Hz 下限之上
        final double two = DirectPath.totalCutoffHz(2.0, 1.0);
        assertTrue(two <= 500.0 && two >= 200.0, "两块墙=" + two);
        // 三块以上:夹到 200Hz 下限
        assertEquals(200.0, DirectPath.totalCutoffHz(3.0, 1.0), 1e-6);
        // 非实心遮挡(栅栏/玻璃 0.15):fc ≈ 12.7kHz,轻闷
        final double fence = DirectPath.totalCutoffHz(0.15, 1.0);
        assertTrue(fence > 10_000.0, "栅栏=" + fence);
    }

    @Test
    @DisplayName("空气吸收:近场不染色;96m 高频被吃(与 SceneAcoustics 同曲线)")
    void airAbsorption() {
        assertEquals(DirectPath.FC_OPEN, DirectPath.airAbsorptionFc(5.0), 1e-6);
        // 96m:18k·e^(-90/16)≈93 → 夹到 500Hz 下限
        assertEquals(500.0, DirectPath.airAbsorptionFc(96.0), 1e-6);
        // 30m:18k·e^(-24/16)≈3.86kHz,明显变闷
        assertTrue(DirectPath.airAbsorptionFc(30.0) > 3_000.0
                && DirectPath.airAbsorptionFc(30.0) < 5_000.0,
                "30m=" + DirectPath.airAbsorptionFc(30.0));
    }

    @Test
    @DisplayName("总截止取 min(遮挡,空气);总增益 = 距离 × 遮挡,范围 [0,1]")
    void combinedChain() {
        // 96m 无遮挡:空气吸收主导(fc=500)
        assertEquals(500.0, DirectPath.totalCutoffHz(0.0, 96.0), 1e-6);
        // 5m 一块墙:遮挡主导(fc≈1kHz)
        assertEquals(20000.0 * Math.exp(-3.0), DirectPath.totalCutoffHz(1.0, 5.0), 5.0);
        // 总增益:近场无遮挡 = 1;远处有遮挡 < 距离衰减
        assertEquals(1.0, DirectPath.directGain(5.0, 0.0), 1e-9);
        // 一块实心方块:按物理基本不透声(−40dB ≈ ×0.01,再乘距离衰减)
        final double occluded = DirectPath.directGain(5.0, 1.0);
        assertEquals(Math.pow(10.0, -40.0 / 20.0), occluded, 1e-9);
        assertTrue(DirectPath.directGain(100.0, 3.0) >= 0.0
                && DirectPath.directGain(100.0, 3.0) <= 1.0);
    }

    @Test
    @DisplayName("遮挡增益分段:非实心轻质透声,实心整块逐块 −24dB,并单调不增")
    void occlusionGainPiecewise() {
        // 无遮挡
        assertEquals(1.0, DirectPath.occlusionGain(0.0), 1e-12);
        // 单层玻璃/树叶(0.15):能听见,轻微衰减
        final double glass = DirectPath.occlusionGain(0.15);
        assertTrue(glass > 0.9 && glass < 1.0, "glass=" + glass);
        // 一块实心:−40dB
        assertEquals(Math.pow(10.0, -40.0 / 20.0), DirectPath.occlusionGain(1.0), 1e-9);
        // 两块实心:−80dB(完全不可闻)
        assertTrue(DirectPath.occlusionGain(2.0) < 1e-3,
                "两块实心应完全不透声: " + DirectPath.occlusionGain(2.0));
        // 单调不增
        double prev = Double.MAX_VALUE;
        for (double o = 0.0; o <= 3.0; o += 0.05) {
            final double g = DirectPath.occlusionGain(o);
            assertTrue(g <= prev + 1e-12, "遮挡量增加时增益不该回升: o=" + o
                    + " g=" + g + " prev=" + prev);
            prev = g;
        }
    }

    @Test
    @DisplayName("等功率声像:pan=0 恒等(gL=gR=1·√2/2·√2),全偏单耳 ×√2")
    void equalPowerPanning() {
        assertEquals(1.0, DirectPath.gainL(0f) * Math.sqrt(2.0), 1e-9);
        assertEquals(1.0, DirectPath.gainR(0f) * Math.sqrt(2.0), 1e-9);
        assertEquals(Math.sqrt(2.0), DirectPath.gainR(1f) * Math.sqrt(2.0), 1e-9);
        assertEquals(0.0, DirectPath.gainR(-1f), 1e-9);
        // NaN/越界夹回
        assertEquals(0f, DirectPath.clampPan(Float.NaN), 1e-9);
        assertEquals(1f, DirectPath.clampPan(9f), 1e-9);
        assertEquals(-1f, DirectPath.clampPan(-9f), 1e-9);
    }

    @Test
    @DisplayName("SpeakerState:放置拒绝 NaN;往返距离;sanitize 拒越界与坏维度串")
    void speakerStateDefensive() {
        assertNull(SpeakerState.place(Double.NaN, 0, 0, "minecraft:overworld"));
        assertNull(SpeakerState.place(0, Double.POSITIVE_INFINITY, 0, "minecraft:overworld"));
        assertNotNull(SpeakerState.place(1.5, -3.25, 64.0, "minecraft:overworld"));

        final SpeakerState s = SpeakerState.sanitize(10.0, 64.0, -5.0, "minecraft:overworld");
        assertNotNull(s);
        // (10,64,-5) → (13,64,-5):纯 X 差 3.0
        assertEquals(3.0, s.distanceTo(13.0, 64.0, -5.0), 1e-9);
        // 超世界边界、空/超长维度串 → null
        assertNull(SpeakerState.sanitize(4.0e7, 0, 0, "d"));
        assertNull(SpeakerState.sanitize(0, 0, 0, null));
        assertNull(SpeakerState.sanitize(0, 0, 0, ""));
        assertNull(SpeakerState.sanitize(0, 0, 0, "x".repeat(300)));

        // volatile 交接
        SpeakerState.set(s);
        assertTrue(SpeakerState.active());
        assertEquals(s, SpeakerState.get());
        SpeakerState.set(null);
        assertFalse(SpeakerState.active());
    }

    // ---- 一阶绕射(遮挡边缘) ----

    @Test
    @DisplayName("绕射衰减:额外出程越长、折角越大,衰减越重(Maekawa 标准式)")
    void diffractionGainMonotonic() {
        // 贴边绕过(0 额外出程、0 折角)= 无损失
        assertEquals(1.0, DirectPath.diffractionGain(0.0, 0.0), 1e-6);
        // 额外出程单调
        final double near = DirectPath.diffractionGain(0.5, 30.0);
        final double mid = DirectPath.diffractionGain(2.0, 30.0);
        final double far = DirectPath.diffractionGain(6.0, 30.0);
        assertTrue(near > mid, "near=" + near + " mid=" + mid);
        assertTrue(mid > far, "mid=" + mid + " far=" + far);
        // 折角单调(同一出程)
        final double straight = DirectPath.diffractionGain(1.0, 0.0);
        final double bent = DirectPath.diffractionGain(1.0, 90.0);
        final double wrapped = DirectPath.diffractionGain(1.0, 180.0);
        assertTrue(straight > bent, "straight=" + straight + " bent=" + bent);
        assertTrue(bent > wrapped, "bent=" + bent + " wrapped=" + wrapped);

        // Maekawa 对表:N = 2δ/λ,IL = 10·log10(3+20N),上限 25dB。
        // δ=1m @1kHz → N=5.83 → IL=20.8dB → ×0.091(不含折角)
        final double n1 = 2.0 * 1.0 / 0.343;
        final double expected = Math.pow(10.0,
                -10.0 * Math.log10(3.0 + 20.0 * n1) / 20.0);
        assertEquals(expected, DirectPath.diffractionGain(1.0, 0.0), 1e-9);

        // 量级:贴边绕墙角(1m 出程、90°)应仍明显可闻(>0.03)
        assertTrue(DirectPath.diffractionGain(1.0, 90.0) > 0.03,
                "贴边绕墙角不该被压到听不见: " + DirectPath.diffractionGain(1.0, 90.0));
    }

    @Test
    @DisplayName("绕射衰减:输出恒在 0..1,负值/NaN 不越界")
    void diffractionGainStaysBounded() {
        for (double excess : new double[] {-5.0, 0.0, 0.1, 50.0, Double.NaN}) {
            for (double bend : new double[] {-90.0, 0.0, 180.0, 400.0, Double.NaN}) {
                final double g = DirectPath.diffractionGain(excess, bend);
                assertTrue(g >= 0.0 && g <= 1.0,
                        "excess=" + excess + " bend=" + bend + " → " + g);
            }
        }
    }

    @Test
    @DisplayName("绕射低通:2m 内不染色,之后下滑;下限高于遮挡的 200Hz")
    void diffractionCutoffBehavior() {
        assertEquals(DirectPath.FC_OPEN, DirectPath.diffractionCutoffHz(0.0), 1e-9);
        assertEquals(DirectPath.FC_OPEN, DirectPath.diffractionCutoffHz(2.0), 1e-9);
        final double far = DirectPath.diffractionCutoffHz(8.0);
        assertTrue(far < DirectPath.FC_OPEN, "far=" + far);
        // 绕射比穿墙保留更多中频:同一「闷度」下,绕射截止不低于遮挡下限
        assertTrue(far >= 400.0 - 1e-9, "绕射下限应 ≥400Hz: " + far);
        // 单调下降
        assertTrue(DirectPath.diffractionCutoffHz(4.0) > DirectPath.diffractionCutoffHz(10.0));
    }

    @Test
    @DisplayName("有效增益/截止:实心墙几乎不透声,绕射成为主路径;两条路径不相加")
    void diffractionWinsOverPenetration() {
        // 一块实心墙:按物理基本不透声(−24dB ≈ ×0.063),再乘距离衰减
        final double throughWall = DirectPath.directGain(10.0, 1.0);
        assertTrue(throughWall < 0.08,
                "实心墙不该还有明显穿透: " + throughWall);
        // 贴边绕墙角:出程 1m、折角 90°
        final double around = DirectPath.directGain(10.0, 1.0, true, 11.0, 1.0, 90.0);
        assertTrue(around > throughWall * 2.0,
                "绕射应成为主路径: around=" + around + " through=" + throughWall);
        // 不相加:结果不超过单独绕射路径的增益
        final double diffractedOnly = DirectPath.distanceAttenuation(11.0)
                * DirectPath.diffractionGain(1.0, 90.0);
        assertTrue(around <= diffractedOnly + 1e-9,
                "不该把两条路径相加: around=" + around + " diffractedOnly=" + diffractedOnly);
        // 没找到绕射时,与旧行为逐值一致
        assertEquals(throughWall, DirectPath.directGain(10.0, 1.0, false, 0.0, 0.0, 0.0), 1e-12);

        // 非实心(玻璃/树叶 0.15)仍应明显透声:这是「能听见但发闷」
        final double throughGlass = DirectPath.directGain(10.0, 0.15);
        assertTrue(throughGlass > 0.8, "玻璃不该被当成石墙: " + throughGlass);

        // 截止同理:绕射路径的高频保留更多
        final double cutStraight = DirectPath.totalCutoffHz(1.0, 10.0);
        final double cutAround = DirectPath.totalCutoffHz(1.0, 10.0, true, 1.0);
        assertTrue(cutAround >= cutStraight, "绕射截止应不低于穿墙: "
                + cutAround + " vs " + cutStraight);
    }
}
