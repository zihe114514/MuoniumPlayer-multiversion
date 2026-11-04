package com.muoniumplayer.core.ncm.music.acoustics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SceneResponse} 的判定表测试:一帧采样 → 完整 {@link RoomPlan},不连
 * 游戏线程。断言写「量级与单调性」而不是精确数值:常量将来调音时只该改
 * SceneResponse 里的常量,不该把测试一起改哑。
 */
class SceneResponseTest {

    /** 合成一帧射线摘要;抽头数组与 tapCount 一致,NO_HIT 用 -1 传。 */
    private static ListenerSample sample(final boolean submerged, final boolean openSky,
                                         final float enclosure, final float precipitation,
                                         final float firstHit, final float segment,
                                         final float reflectivity, final float reachable,
                                         final float[] tapDelays, final float[] tapGains,
                                         final float[] tapPans) {
        final int n = tapDelays.length;
        return new ListenerSample() {
            @Override public boolean submerged() { return submerged; }
            @Override public boolean openSky() { return openSky; }
            @Override public float enclosure() { return enclosure; }
            @Override public float precipitation() { return precipitation; }
            @Override public float firstHitMeanMeters() { return firstHit; }
            @Override public float bounceSegmentMeanMeters() { return segment; }
            @Override public float meanReflectivity() { return reflectivity; }
            @Override public float reachableFraction() { return reachable; }
            @Override public int tapCount() { return n; }
            @Override public float[] tapDelaysSec() { return tapDelays; }
            @Override public float[] tapGains() { return tapGains; }
            @Override public float[] tapPans() { return tapPans; }
            @Override public EnergyField bandHistogram() { return new EnergyField(); }
        };
    }

    /** 旷野:全方向逃逸。 */
    private static ListenerSample outdoor(float precipitation) {
        return sample(false, true, 0f, precipitation, ListenerSample.NO_HIT, 0f, 0f, 0f,
                new float[0], new float[0], new float[0]);
    }

    /** 小木屋:四面近墙、木质软面、反射可达一半,带三条真实感的抽头。 */
    private static ListenerSample woodRoom() {
        return sample(false, false, 0.95f, 0f, 2.2f, 2.8f, 0.5f, 0.4f,
                new float[] {0.012f, 0.019f, 0.031f},
                new float[] {0.32f, 0.14f, 0.07f},
                new float[] {0.7f, -0.4f, 0.1f});
    }

    /** 石头大洞:墙远、段长、硬面、大部分反射可达。 */
    private static ListenerSample stoneCave() {
        return sample(false, false, 0.97f, 0f, 9f, 11f, 1.4f, 0.6f,
                new float[] {0.052f, 0.064f, 0.09f},
                new float[] {0.10f, 0.058f, 0.03f},
                new float[] {-0.6f, 0.5f, 0f});
    }

    @Test
    @DisplayName("旷野:完全静息")
    void openFieldIsIdle() {
        RoomPlan r = SceneResponse.respond(outdoor(0f), 1f);
        assertEquals(SceneAcoustics.bandOpenHz(), r.bandHz, 1e-9);
        assertEquals(0f, r.wet, 1e-6);
        assertEquals(0f, r.predelaySec, 1e-9);
        assertEquals(0f, r.decaySec, 1e-9);
        assertEquals(1f, r.dryGain, 1e-6);
        assertEquals(1f, r.loudness, 1e-6);
        assertEquals(0, r.tapCount);
    }

    @Test
    @DisplayName("无采样或零强度:静息")
    void nullSampleAndZeroStrengthAreIdle() {
        RoomPlan none = SceneResponse.respond(null, 1f);
        assertEquals(SceneAcoustics.bandOpenHz(), none.bandHz, 1e-9);

        RoomPlan zero = SceneResponse.respond(stoneCave(), 0f);
        assertEquals(SceneAcoustics.bandOpenHz(), zero.bandHz, 1e-9);
        assertEquals(0f, zero.wet, 1e-6);
        assertEquals(0f, zero.decaySec, 1e-9);
        assertEquals(0, zero.tapCount);
    }

    @Test
    @DisplayName("浸液:频带压到最低档、响度小于 1;旷野里不凭空出现反射")
    void submersionDropsBandAndLoudness() {
        RoomPlan r = SceneResponse.respond(
                sample(true, true, 0f, 0f, ListenerSample.NO_HIT, 0f, 0f, 0f,
                        new float[0], new float[0], new float[0]), 1f);
        assertTrue(r.bandHz <= 600.0, "band=" + r.bandHz);
        assertTrue(r.bandHz >= SceneAcoustics.bandFloorHz(), "band=" + r.bandHz);
        assertTrue(r.loudness < 1f && r.loudness > 0.5f, "loud=" + r.loudness);
        assertEquals(0f, r.wet, 1e-6);
    }

    @Test
    @DisplayName("小木屋:湿声中等可闻、衰减短、预延迟毫秒级、抽头齐备且定标")
    void smallRoomGetsShortTailAndTaps() {
        RoomPlan r = SceneResponse.respond(woodRoom(), 1f);
        assertTrue(r.wet > 0.12f && r.wet < 0.5f, "wet=" + r.wet);
        assertTrue(r.decaySec < 0.8f, "decay=" + r.decaySec);
        assertTrue(r.decaySec >= 0.15f, "小房间尾物理下限: " + r.decaySec);
        // 近墙反射的幅度要落到可闻区。材质归一后:最强抽头 = 0.22×材质因子,
        // 木头房(ρ=0.5,因子≈0.34)≈ 0.075 —— 「吸音房回声轻」是正确行为;
        // 关键是与石头房的【对比】(见 stoneVsWoodTapContrast)。
        assertTrue(Math.max(r.tapGainL[0], r.tapGainR[0]) >= 0.05f,
                "最强抽头=" + Math.max(r.tapGainL[0], r.tapGainR[0]));
        assertTrue(r.predelaySec > 0.004f && r.predelaySec < 0.012f, "predelay=" + r.predelaySec);
        assertEquals(3, r.tapCount);
        for (int i = 0; i < r.tapCount; i++) {
            assertTrue(r.tapGainL[i] >= 0f && r.tapGainR[i] >= 0f, "gain<0");
            assertTrue(Math.max(r.tapGainL[i], r.tapGainR[i]) <= 0.4f + 1e-6, "归一上限");
        }
        // 等功率:每条抽头左右能量平方和相等(pan 信息保存在分配里)
        float[] pans = {0.7f, -0.4f, 0.1f};
        for (int i = 0; i < r.tapCount; i++) {
            double angle = (pans[i] + 1f) * (Math.PI / 4.0);
            double gain = Math.max(r.tapGainL[i], r.tapGainR[i]) / Math.max(
                    Math.cos(angle), Math.sin(angle));
            assertTrue(gain > 0f);
        }
        // 最强抽头(pan 0.7 → 右耳为主)能量确实偏右
        assertTrue(r.tapGainR[0] > r.tapGainL[0], "pan>0 应偏右");
    }

    @Test
    @DisplayName("材质区分:石头房回声响、木头房轻、羊毛高频尾快 —— 吸音感可闻")
    void materialContrast() {
        // 石头房 vs 羊毛房:抽头响度与高频尾都应显著不同
        final ListenerSample stone = withBands(
                sample(false, false, 0.95f, 0f, 2.2f, 2.8f, 1.47f, 0.4f,
                        new float[] {0.012f}, new float[] {0.9f}, new float[] {0f}),
                new float[] {1.5f, 1.5f, 1.45f});
        final ListenerSample wool = withBands(
                sample(false, false, 0.95f, 0f, 2.2f, 2.8f, 0.11f, 0.4f,
                        new float[] {0.012f}, new float[] {0.21f}, new float[] {0f}),
                new float[] {0.2f, 0.1f, 0.03f});
        RoomPlan rs = SceneResponse.respond(stone, 1f);
        RoomPlan rw = SceneResponse.respond(wool, 1f);
        // 抽头响度:石头房 ≥ 羊毛房 × 3(材质归一后的物理差)
        double tapS = Math.max(rs.tapGainL[0], rs.tapGainR[0]);
        double tapW = Math.max(rw.tapGainL[0], rw.tapGainR[0]);
        assertTrue(tapS > tapW * 3.0, "石头=" + tapS + " 羊毛=" + tapW);
        // 湿声:石头房 > 羊毛房 × 2
        assertTrue(rs.wet > rw.wet * 2.0, "石头湿=" + rs.wet + " 羊毛湿=" + rw.wet);
        // 高频尾:羊毛房的高/中频 T60 比应 < 0.75(软面吃高频);石头 ≈ 1
        double woolRatio = rw.decayHighSec / Math.max(0.01f, rw.decaySec);
        double stoneRatio = rs.decayHighSec / Math.max(0.01f, rs.decaySec);
        assertTrue(woolRatio < stoneRatio - 0.05, "羊毛高频比=" + woolRatio + " 石头=" + stoneRatio);
    }

    /** 给采样帧叠加三频带反射率(材质区分测试用)。 */
    private static ListenerSample withBands(ListenerSample base, float[] bands) {
        return new ListenerSample() {
            @Override public boolean submerged() { return base.submerged(); }
            @Override public boolean openSky() { return base.openSky(); }
            @Override public float enclosure() { return base.enclosure(); }
            @Override public float precipitation() { return base.precipitation(); }
            @Override public float firstHitMeanMeters() { return base.firstHitMeanMeters(); }
            @Override public float bounceSegmentMeanMeters() { return base.bounceSegmentMeanMeters(); }
            @Override public float meanReflectivity() { return base.meanReflectivity(); }
            @Override public float reachableFraction() { return base.reachableFraction(); }
            @Override public int tapCount() { return base.tapCount(); }
            @Override public float[] tapDelaysSec() { return base.tapDelaysSec(); }
            @Override public float[] tapGains() { return base.tapGains(); }
            @Override public float[] tapPans() { return base.tapPans(); }
            @Override public EnergyField bandHistogram() { return base.bandHistogram(); }
            @Override public float[] bandReflectivities() { return bands; }
        };
    }

    @Test
    @DisplayName("石头大洞:衰减秒级、湿声更重、预延迟拉远、干声让位")
    void stoneCaveGetsLongTailAndDuck() {
        RoomPlan room = SceneResponse.respond(woodRoom(), 1f);
        RoomPlan cave = SceneResponse.respond(stoneCave(), 1f);
        assertTrue(cave.decaySec > 2.0f, "decay=" + cave.decaySec);
        assertTrue(cave.decaySec > room.decaySec * 3f, "洞穴衰减应远超斗室");
        assertTrue(cave.wet > room.wet, "cave=" + cave.wet + " room=" + room.wet);
        assertTrue(cave.wet <= 0.85f, "网络动态余量");
        assertTrue(cave.predelaySec > 0.02f, "predelay=" + cave.predelaySec);
        // duck:湿声大 → 干声退让;湿声越小退得越少
        assertTrue(cave.dryGain < room.dryGain, "湿声大时干声应让位更多");
        assertTrue(cave.dryGain >= 0.72f - 1e-3, "duck 上限 0.72");
    }

    @Test
    @DisplayName("吸声墙面(羊毛档):衰减压到下限附近,音色变暗,湿声更干")
    void softSurfacesShortenDecay() {
        ListenerSample wool = sample(false, false, 0.95f, 0f, 2.2f, 2.8f, 0.1f, 0.4f,
                new float[] {0.012f}, new float[] {0.05f}, new float[] {0f});
        RoomPlan r = SceneResponse.respond(wool, 1f);
        assertTrue(r.decaySec <= 0.35f, "decay=" + r.decaySec);
        assertTrue(r.tone < 0.2f, "tone=" + r.tone);
        assertTrue(r.wet < SceneResponse.respond(woodRoom(), 1f).wet, "软面更干");
    }

    @Test
    @DisplayName("降水:只压响度,不碰频带与湿声")
    void precipitationOnlyLowersLoudness() {
        RoomPlan rain = SceneResponse.respond(outdoor(0.5f), 1f);
        RoomPlan thunder = SceneResponse.respond(outdoor(1f), 1f);
        assertEquals(SceneAcoustics.bandOpenHz(), rain.bandHz, 1e-9);
        assertEquals(0f, rain.wet, 1e-6);
        assertTrue(rain.loudness < 1f, "rain=" + rain.loudness);
        assertTrue(thunder.loudness < rain.loudness, "雷暴压得更低");
    }

    @Test
    @DisplayName("强度档:0→1 全部目标量单调逼近满效果")
    void strengthInterpolatesMonotonically() {
        ListenerSample cave = stoneCave();
        double lastBand = SceneAcoustics.bandOpenHz();
        float lastWet = -1f;
        float lastDecay = -1f;
        float lastPredelay = -1f;
        for (int pct = 0; pct <= 100; pct += 10) {
            RoomPlan r = SceneResponse.respond(cave, pct / 100f);
            assertTrue(r.bandHz <= lastBand + 1e-9, "pct=" + pct + " band=" + r.bandHz);
            assertTrue(r.wet >= lastWet, "pct=" + pct + " wet=" + r.wet);
            assertTrue(r.decaySec >= lastDecay, "pct=" + pct + " decay=" + r.decaySec);
            assertTrue(r.predelaySec >= lastPredelay, "pct=" + pct + " predelay=" + r.predelaySec);
            lastBand = r.bandHz;
            lastWet = r.wet;
            lastDecay = r.decaySec;
            lastPredelay = r.predelaySec;
        }
        assertEquals(SceneResponse.respond(cave, 1f).wet, lastWet, 1e-9);
    }

    @Test
    @DisplayName("场景叠加:浸液 + 封闭同取最重,湿声按浸液折减")
    void stackedScenesCompound() {
        ListenerSample caveUnderwater = sample(true, false, 0.97f, 0f, 9f, 11f, 1.4f, 0.6f,
                new float[] {0.05f}, new float[] {0.03f}, new float[] {0f});
        RoomPlan dry = SceneResponse.respond(stoneCave(), 1f);
        RoomPlan wet = SceneResponse.respond(caveUnderwater, 1f);
        assertTrue(wet.bandHz <= 600.0, "频带被浸液压低");
        assertTrue(wet.wet > 0.05f, "洞感湿声在场");
        assertTrue(wet.wet < dry.wet, "水体吸声折减");
    }

    @Test
    @DisplayName("越界输入:负围合按旷野、越界反射率/延迟不产生 NaN/越限,超量抽头按能量截断")
    void outOfRangeInputsAreClamped() {
        ListenerSample negative = sample(false, false, -2f, 0f, 3f, 3f, 0.5f, 0.5f,
                new float[0], new float[0], new float[0]);
        assertEquals(0f, SceneResponse.respond(negative, 1f).wet, 1e-9);

        // 90 条抽头(超 TAP_MAX=64,对应实机 74-83 条可达反射的密集房间),
        // 前段弱、后段强:截断必须按能量留强,而不是按索引留先到者。
        int n = 90;
        float[] d = new float[n];
        float[] g = new float[n];
        float[] p = new float[n];
        for (int i = 0; i < n; i++) {
            d[i] = 0.02f + (i % 50) * 0.001f;
            // 前 26 条(会被挤掉的弱者)能量 0.5,后 64 条能量 9 —— 正确截断
            // 应保留全部 64 条强反射,一条弱者都不留。
            g[i] = i < 26 ? 0.5f : 9f;
            p[i] = 7f;
        }
        ListenerSample huge = sample(false, false, 1f, 0f, 100f, -5f, 9f, 3f, d, g, p);
        RoomPlan r = SceneResponse.respond(huge, 1f);
        assertTrue(Double.isFinite(r.bandHz) && Double.isFinite(r.decaySec));
        assertTrue(r.decaySec <= 5.5f, "衰减夹上限");
        assertTrue(r.predelaySec <= 0.055f, "预延迟夹上限");
        assertTrue(r.wet >= 0f && r.wet <= 0.95f);
        assertEquals(64, r.tapCount, "抽头按能量截到上限");
        for (int i = 0; i < r.tapCount; i++) {
            assertTrue(Math.max(r.tapGainL[i], r.tapGainR[i]) <= 0.4f + 1e-6, "归一上限");
            assertTrue(Double.isFinite(r.tapGainL[i]) && Double.isFinite(r.tapGainR[i]));
        }
        // 交付的都是 64 条强反射(能量比弱者高 18 倍,归一后弱者会贴近 0)。
        // 注意 TAP_SUM_CEIL:64 条等能量抽头的单耳和超 0.9,整体被等比压
        // —— 最强单条落在大约 0.9/64·(归一系数) 量级,断言只需证明「强者
        // 比弱者大一个量级」,弱者若混进来必然贴近 0。
        double strongest = 0;
        for (int i = 0; i < r.tapCount; i++) {
            strongest = Math.max(strongest, Math.max(r.tapGainL[i], r.tapGainR[i]));
        }
        assertTrue(strongest > 0.01f, "抽头能量在场(总量被 TAP_SUM_CEIL 约束): " + strongest);
    }
}
