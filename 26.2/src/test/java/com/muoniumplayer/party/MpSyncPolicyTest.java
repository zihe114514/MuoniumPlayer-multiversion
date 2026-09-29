package com.muoniumplayer.party;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 房间对时策略里那两道「这一刻量不出脱节」的闸 —— 一起听开 automix 时<b>强制拉进度、把交叠
 * 听感截断</b>那个问题的判据本体。
 *
 * <p>症状是怎么来的：服务端在交接点把房间时钟切到新的一首并广播换歌帧，本机这一刻正在淡入。
 * 而本机开始淡入的时刻天然比房间晚几秒 —— 帧延迟、一条本来就落在软档里没纠的漂移、加上新
 * 播放牌的缓冲。心跳静默窗只按服务端的配置提前量开（默认 4000 → 抬到 5000），Folia 那种
 * 二十秒的长尾奏盖不住；窗一过，两发心跳就量出「超过硬阈值」，于是对<b>刚叠上的那一条</b>
 * 播放头做一次 {@code jump()}，混音被薅断。</p>
 *
 * <p>这里钉三条：</p>
 * <ol>
 *   <li>静默窗能按<b>实际交叠长度</b>开（{@link MpSyncPolicy#markCommand(long)}），
 *       而不是被 {@code graceMs} 那个固定值卡死；同时有个上限，别让一段离谱的读数把房间
 *       永久关掉校准。</li>
 *   <li>播放头冻住 = 没取到数据，不等于落后（{@link MpSyncPolicy#isStalled}）。跳过去只是
 *       重开解码器、让下一发量出更大的偏差 —— 正反馈。只有连冻约 6 秒才放行那一次自救。</li>
 *   <li>两道闸都不许把「真的脱节」也一起挡掉：窗过了、流在走的时候，硬跳照常发生。</li>
 * </ol>
 *
 * <p>窗口长度用毫秒级的小数值测（{@code configure} 能把 {@code graceMs} 压到几十毫秒），
 * 卡流判定只能按真实采样间隔睡 850ms —— 最长的一条用例约 3.5 秒，是整个类里唯一等墙钟的地方。</p>
 */
class MpSyncPolicyTest {

    /** 软阈值 / 硬阈值 / 冷却 / 确认次数 / 静默窗：除第 5 项之外都是普通纠偏参数。 */
    private static void thresholds(long soft, long hard, long cooldown, int hits, long grace) {
        MpSyncPolicy.configure(soft, hard, cooldown, hits, grace);
        MpSyncPolicy.reset();
    }

    @BeforeEach
    void begin() {
        MpSyncPolicy.reset();
    }

    @AfterEach
    void end() {
        MpSyncPolicy.reset();
        // 默认值与服务端 MpConfig 一致；不还原的话，后面任何用例都可能踩在本用例改过的阈值上。
        MpSyncPolicy.configure(2500L, 6000L, 10000L, 2, 4000L);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // ════════════════ 一、静默窗要盖得住整段交叠 ════════════════

    @Test
    @DisplayName("protectMs 比配置窗长时按 protectMs 开：淡入没结束就不许判漂移")
    void theWindowFollowsTheBlendNotTheConfiguredGrace() {
        thresholds(200L, 2000L, 1000L, 2, 50L);       // 配置窗只有 50ms，模拟「固定窗盖不住长交叠」

        MpSyncPolicy.markCommand(1200L);               // 交叠还剩 1200ms（用例里当作一段长淡入）
        sleep(300L);                                   // 早已越过 50ms 的配置窗
        assertTrue(MpSyncPolicy.inGrace(),
                "配置窗过了不等于能判定：淡入还在进行，此刻本机比房间晚几秒开始是设计如此");
        assertFalse(MpSyncPolicy.shouldHardSeek(9_999L),
                "窗内的偏差一律不批硬跳 —— 这一条批准了就是用户听到的「交叠被截断」");

        sleep(1000L);                                  // 1300ms > 1200ms：窗结束
        assertFalse(MpSyncPolicy.shouldHardSeek(9_999L), "第一发越线只算一次确认，可能只是抖动");
        assertTrue(MpSyncPolicy.shouldHardSeek(9_999L),
                "窗过了、也连续命中了，纠偏必须照常工作 —— 保护窗不是把脱节藏起来");
    }

    @Test
    @DisplayName("保护窗有上限：再长的读数也不能把房间的校准永久关掉")
    void theWindowIsCapped() {
        thresholds(200L, 2000L, 1000L, 2, 4000L);
        MpSyncPolicy.markCommand(120_000L);            // 一份把 overlap 报成一分钟的坏计划
        long left = MpSyncPolicy.graceRemainingMs();
        assertTrue(left > 25_000L && left <= 30_000L,
                "窗要足够盖住任何一次真实混音，但必须夹在上限内，实际剩余 " + left + "ms");
    }

    @Test
    @DisplayName("protectMs 比配置窗短（或没有过渡）时退回配置窗，不缩窗")
    void shortProtectionNeverShrinksTheConfiguredWindow() {
        thresholds(200L, 2000L, 1000L, 2, 600L);

        MpSyncPolicy.markCommand();                    // 普通命令帧
        long plain = MpSyncPolicy.graceRemainingMs();
        assertTrue(plain > 0L && plain <= 600L, "普通命令按配置窗开，实际 " + plain + "ms");

        MpSyncPolicy.markCommand(100L);                // 一段很短的过渡
        long extended = MpSyncPolicy.graceRemainingMs();
        assertTrue(extended > 100L,
                "过渡比配置窗短时不许把窗缩掉 —— 开流那几秒照样在缓冲，实际 " + extended + "ms");
    }

    // ════════════════ 二、卡流不是落后 ════════════════

    @Test
    @DisplayName("位置冻住：前两发当缓冲，连冻三轮才放行那一次自救的硬跳")
    void frozenHeadIsBufferingThenRelease() {
        thresholds(200L, 2000L, 1000L, 2, 4000L);

        assertFalse(MpSyncPolicy.isStalled(1000L), "第一发没有参照，不判");
        sleep(850L);
        assertTrue(MpSyncPolicy.isStalled(1010L), "850ms 只走了 10ms：这是没取到数据，不是脱节");
        sleep(850L);
        assertTrue(MpSyncPolicy.isStalled(1020L), "还冻着：继续给缓冲让路，别去重开解码器");
        sleep(850L);
        assertFalse(MpSyncPolicy.isStalled(1030L),
                "连冻三轮（约 6 秒完全没出声）就不该再等 —— 此时重开这条流是唯一自救手段");
        sleep(850L);
        assertFalse(MpSyncPolicy.isStalled(1040L),
                "判死之后要一直是 false：否则每轮释放前又拿缓冲当借口重开静默窗，死流永远等不到救");
    }

    @Test
    @DisplayName("播放头重新走起来之后，卡流判定复位（下一次真缓冲还得重新数三轮）")
    void advancingClearsTheRelease() {
        thresholds(200L, 2000L, 1000L, 2, 4000L);

        MpSyncPolicy.isStalled(1000L);
        sleep(850L);
        assertFalse(MpSyncPolicy.isStalled(30_000L), "位置正常推进：这条流是活的");
        sleep(850L);
        assertFalse(MpSyncPolicy.isStalled(59_000L), "一直在走就永远不该判成卡流");

        sleep(850L);
        assertTrue(MpSyncPolicy.isStalled(59_010L), "现在开始缓冲：要重新数够三轮才放硬跳");
    }

    @Test
    @DisplayName("往回跳是拖进度或换曲，不是卡住")
    void backwardsJumpIsNotAStall() {
        thresholds(200L, 2000L, 1000L, 2, 4000L);

        MpSyncPolicy.isStalled(50_000L);
        sleep(850L);
        assertFalse(MpSyncPolicy.isStalled(200L),
                "房主把进度拖回去了：位置倒退不是「没取到数据」");
        sleep(850L);
        assertTrue(MpSyncPolicy.isStalled(205L), "倒退之后的那一次计数要作废，从这里重新数");
    }

    @Test
    @DisplayName("换房间／断线把卡流的账一并清掉，别把上一台服务器的样本带过来")
    void resetClearsTheStallSample() {
        thresholds(200L, 2000L, 1000L, 2, 4000L);
        MpSyncPolicy.isStalled(1000L);
        sleep(850L);
        assertTrue(MpSyncPolicy.isStalled(1005L), "测试前提：此刻判为缓冲");

        MpSyncPolicy.reset();
        assertFalse(MpSyncPolicy.isStalled(1005L), "没有上一发的参照就不该接着判缓冲");
    }

    // ════════════════ 三、普通纠偏不能被两道闸顺手挡死 ════════════════

    @Test
    @DisplayName("窗为零时：连续命中硬阈值照样批硬跳，命令帧则把计数清回去")
    void ordinaryCorrectionStillWorks() {
        thresholds(200L, 2000L, 10000L, 2, 0L);        // 静默窗 0：这一条只判阈值与确认次数

        assertFalse(MpSyncPolicy.shouldHardSeek(5000L), "单发越线只算一轮确认，可能只是抖动");
        MpSyncPolicy.markCommand();
        assertFalse(MpSyncPolicy.shouldHardSeek(5000L),
                "命令帧要把那一发抹掉：换歌／拖进度刚下去时的落后是「还没开起来」，不是脱节");
        assertTrue(MpSyncPolicy.shouldHardSeek(5000L),
                "重新数够两轮就该跳 —— 保护窗不是把真脱节藏起来");
        assertFalse(MpSyncPolicy.shouldHardSeek(50_000L),
                "刚跳过就进冷却 —— 网络差的时候这一条是「来回跳」的最后一道闸");
    }

    @Test
    @DisplayName("没越过硬阈值时把确认计数重新归零，不做任何扯动")
    void softBandStillChangesNothing() {
        thresholds(200L, 2000L, 1000L, 2, 0L);
        assertFalse(MpSyncPolicy.shouldHardSeek(5000L));   // 命中一次
        assertFalse(MpSyncPolicy.shouldHardSeek(500L), "软档以内什么都不做");
        assertFalse(MpSyncPolicy.shouldHardSeek(5000L),
                "中间那一发没越线就要把计数打回：连着两轮越线才算脱节");
    }
}
