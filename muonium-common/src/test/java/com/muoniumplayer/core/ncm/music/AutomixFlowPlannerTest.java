package com.muoniumplayer.core.ncm.music;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进阶模式「一键排列」的数值契约。
 *
 * <p>排列改的是<b>用户的队列</b>，出错会直接看见（歌少了、顺序乱了、按两次给两个结果），
 * 所以这里钉的全是结构不变量：全排列、没量过的不动、确定性、以及速度比 1:2 免费这条
 * 反直觉但关键的约定。</p>
 */
class AutomixFlowPlannerTest {

    private static AutomixFlowPlanner.Feature feature(double tempo, double headLevel, double tailLevel) {
        AutomixFlowPlanner.Feature feature = new AutomixFlowPlanner.Feature();
        feature.tempo = tempo;
        feature.headTempo = tempo;
        feature.tailTempo = tempo;
        feature.level = Math.max(headLevel, tailLevel);
        feature.headLevel = headLevel;
        feature.tailLevel = tailLevel;
        feature.tone = .5;
        feature.seconds = 200.0;
        return feature;
    }

    /** 带着身份标记的特征：排列之后还能认得出「原来那一首」去了哪儿。 */
    private static AutomixFlowPlanner.Feature tagged(int identity, double tempo, double level) {
        AutomixFlowPlanner.Feature feature = feature(tempo, level, level);
        feature.seconds = identity;
        return feature;
    }

    private static boolean isPermutation(int[] order) {
        boolean[] seen = new boolean[order.length];
        for (int index : order) {
            if (index < 0 || index >= order.length || seen[index]) return false;
            seen[index] = true;
        }
        return true;
    }

    @Test
    @DisplayName("结果永远是全排列，没量过的那几格原位不动")
    void outputIsAlwaysAPermutationAndUnmeasuredTracksStayPut() {
        AutomixFlowPlanner.Feature[] features = {
                feature(120, -20, -20), null, feature(140, -18, -18),
                feature(100, -24, -24), null, feature(128, -21, -19),
        };

        int[] order = AutomixFlowPlanner.plan(features);

        assertEquals(features.length, order.length, "长度变了就是凭空少了歌");
        assertTrue(isPermutation(order), "不是全排列：" + java.util.Arrays.toString(order));
        assertEquals(1, order[1], "没特征的一首必须留在自己的格子里");
        assertEquals(4, order[4], "没特征的一首必须留在自己的格子里");
    }

    @Test
        @DisplayName("整批都没量过时给恒等排列，绝不返回半截排布")
    void identityWhenNothingIsMeasurable() {
        AutomixFlowPlanner.Feature[] features = {null, null, null};
        int[] order = AutomixFlowPlanner.plan(features);
        assertEquals(0, order[0]);
        assertEquals(1, order[1]);
        assertEquals(2, order[2]);

        assertEquals(0, AutomixFlowPlanner.plan(null).length);
        // 只有一首可排时无从交换，恒等排列就是正确答案——第 0 格还是第 0 首。
        assertEquals(0, AutomixFlowPlanner.plan(new AutomixFlowPlanner.Feature[]{feature(120, -20, -20)})[0]);
    }

    @Test
    @DisplayName("倒着摆的列表要被排回来：越往后越燃")
    void sortsTowardsTheRisingArc() {
        // 身份 0 最平静，身份 3 最燃；喂进去的顺序是反的。
        AutomixFlowPlanner.Feature[] features = {
                tagged(3, 150, -8), tagged(2, 135, -12), tagged(1, 120, -16), tagged(0, 105, -20),
        };

        int[] order = AutomixFlowPlanner.plan(features);

        assertTrue(isPermutation(order));
        int[] placed = identities(features, order);
        assertEquals(0, placed[0], "第一格该是最平静的那首，实际顺序 " + java.util.Arrays.toString(placed));
        assertEquals(3, placed[3], "最后一格该是最燃的那首，实际顺序 " + java.util.Arrays.toString(placed));
        assertTrue(placed[1] < placed[2], "中间两格也要有坡度，实际 " + java.util.Arrays.toString(placed));
    }

    @Test
    @DisplayName("排列确实让代价变低——这用同一条式子比对，不许只靠观感")
    void thePlanActuallyLowersTheObjective() {
        AutomixFlowPlanner.Feature[] features = {
                tagged(3, 150, -8), tagged(2, 135, -12), tagged(1, 120, -16), tagged(0, 105, -20),
        };
        int[] identity = {0, 1, 2, 3};
        int[] order = AutomixFlowPlanner.plan(features);

        double before = AutomixFlowPlanner.costOf(features, identity);
        double after = AutomixFlowPlanner.costOf(features, order);

        assertTrue(after < before, "排完之后代价必须真的更低：" + after + " vs " + before);
    }

    @Test
    @DisplayName("速度翻倍不算偏差：120→240 免费，120→150 才收钱")
    void octaveTempoRatioIsFree() {
        AutomixFlowPlanner.Feature slow = feature(120, -20, -20);
        AutomixFlowPlanner.Feature doubleTime = feature(240, -20, -20);
        AutomixFlowPlanner.Feature slight = feature(150, -20, -20);

        assertEquals(0.0, AutomixFlowPlanner.transitionCost(slow, doubleTime), 1e-9);
        assertTrue(AutomixFlowPlanner.transitionCost(slow, slight) > 10.0,
                "25 % 的速度差要计入代价，实际 " + AutomixFlowPlanner.transitionCost(slow, slight));
        // 反过来减半同样免费：判据比的是离 1 和离 2 哪个近，不是「不能快过自己」。
        assertEquals(0.0, AutomixFlowPlanner.transitionCost(doubleTime, slow), 1e-9);
    }

    @Test
    @DisplayName("量不出速度时那一项退出分母，不当成「速度为 0」")
    void unmeasurableTempoIsNeutralNotZero() {
        AutomixFlowPlanner.Feature noTempo = new AutomixFlowPlanner.Feature();
        noTempo.level = -18.0;
        noTempo.headLevel = -18.0;
        noTempo.tailLevel = -18.0;
        noTempo.tone = .4;
        noTempo.seconds = 180.0;
        assertTrue(!noTempo.isSortable(), "没有速度的歌不能算「可排」，它要原位不动");

        AutomixFlowPlanner.Feature[] features = {feature(120, -20, -20), noTempo, feature(126, -19, -19)};
        int[] order = AutomixFlowPlanner.plan(features);

        assertTrue(isPermutation(order));
        assertEquals(1, order[1], "量不出速度的那一首要留在原位");
    }

    @Test
    @DisplayName("同样的输入两次必须给同样的顺序")
    void planningIsDeterministic() {
        AutomixFlowPlanner.Feature[] features = {
                tagged(5, 128, -11), tagged(2, 96, -22), tagged(4, 140, -9),
                tagged(1, 118, -17), tagged(3, 124, -14),
        };
        int[] first = AutomixFlowPlanner.plan(features);
        int[] second = AutomixFlowPlanner.plan(features);
        assertEquals(java.util.Arrays.toString(first), java.util.Arrays.toString(second));
    }

    @Test
    @DisplayName("平手时取原下标较小的那个——这是确定性唯一的来源")
    void tiesBreakToTheEarlierTrack() {
        AutomixFlowPlanner.Feature[] features = {
                tagged(7, 120, -15), tagged(8, 120, -15), tagged(9, 120, -15),
        };
        int[] order = AutomixFlowPlanner.plan(features);
        assertEquals(0, order[0], "三首完全一样时不该重排，实际 " + java.util.Arrays.toString(order));
        assertEquals(1, order[1]);
        assertEquals(2, order[2]);
    }

    private static int[] identities(AutomixFlowPlanner.Feature[] features, int[] order) {
        int[] out = new int[order.length];
        for (int i = 0; i < order.length; i++) {
            AutomixFlowPlanner.Feature feature = features[order[i]];
            out[i] = feature == null ? -1 : (int) feature.seconds;
        }
        return out;
    }
}
