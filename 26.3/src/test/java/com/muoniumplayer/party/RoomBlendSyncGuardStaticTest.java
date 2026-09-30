package com.muoniumplayer.party;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一起听开 automix 时「强制拉进度把交叠听感截断」这件事，<b>接线</b>那一半。
 *
 * <p>判据本体由 {@code MpSyncPolicyTest} 直接测；这里静态扫源码，钉的是三件不会报错、只会
 * 让故障重新出现的事：</p>
 * <ol>
 *   <li>心跳纠偏真的问了「本机此刻在不在交叠里」「播放头是不是冻住了」—— 上一版这两句都
 *       没有，于是淡入没结束就被一次 {@code jump()} 薅断；</li>
 *   <li>两道闸排在静默窗与硬阈值<b>之前</b>。顺序反了就没意义：先 {@code inGrace()} 再问交叠，
 *       等于窗一开就再也看不见交叠还剩多久，窗一关又立刻拿旧读数去跳；</li>
 *   <li>剩余长度读的是渐变自己的进度，不是「墙钟减 startedAt」—— 暂停时渐变时钟被冻住，
 *       按墙钟算会把保护窗越推越长，而混音根本没在走。</li>
 * </ol>
 *
 * <p>状态优先于位置这一条也顺手钉住：房间在放而本机还暂停时，先把播放按下去、这一帧不动
 * 位置。这不是本轮新增的语义，但它是同一个方法里的邻居，重构时最容易被「统一成一个判定」。</p>
 */
class RoomBlendSyncGuardStaticTest {

    private static final Path PARTY = ProjectPaths.versionRoot()
            .getParent().resolve(Paths.get("mpparty-common", "src", "main", "java", "com", "muoniumplayer", "party"));

    @Test
    @DisplayName("纠偏前先过两道闸：交叠进行中、以及播放头冻住的卡流")
    void driftCheckIsGatedByTheBlendAndByBuffering() throws IOException {
        String source = read(PARTY.resolve("MpPlayback.java"));
        String body = region(source, "private static boolean decideSeek(",
                "每个心跳周期调一次");

        assertTrue(body.contains("CloudMusic.roomBlendRemainingMillis()"),
                "交叠进行中不许判漂移 —— 这一段本机与房间时钟「故意」差着几秒");
        assertTrue(body.contains("MpSyncPolicy.markCommand(blend + MpSyncPolicy.BLEND_SETTLE_MS)"),
                "静默窗要按剩下的淡入长度续，否则窗一过还是会把淡入薅断");
        assertTrue(body.contains("MpSyncPolicy.isStalled(local)"),
                "播放头冻住是没取到数据，不是落后：这一发不该计入确认次数");

        // 顺序：交叠 → 卡流 → 静默窗 → 硬阈值。任何一条挪到 inGrace 之后都等于没做。
        int blend = body.indexOf("roomBlendRemainingMillis()");
        int stalled = body.indexOf("MpSyncPolicy.isStalled(local)");
        int grace = body.indexOf("MpSyncPolicy.inGrace()");
        int hard = body.indexOf("MpSyncPolicy.shouldHardSeek(drift)");
        assertTrue(blend >= 0 && blend < grace && grace < hard,
                "交叠闸必须排在静默窗与硬阈值之前");
        assertTrue(stalled > blend && stalled < grace,
                "卡流闸同样要排在判定之前；排在 inGrace 之后就只是把一次白跳换成一次白返回");

        // 状态优先：这一条在两轮改动之间都不能塌。
        assertTrue(body.indexOf("if (pausing)") < blend,
                "房间在放而本机暂停时要先按下去，而不是先判交叠");
    }

    @Test
    @DisplayName("交接那一刻的保护窗按实际交叠长度开，而不是固定四秒")
    void handoverSizesItsQuietWindowByTheBlend() throws IOException {
        String source = read(PARTY.resolve("MpPlayback.java"));
        String body = region(source, "public static boolean handoverToRoomFrame(",
                "撤下房间的下一首与交叠状态");

        assertTrue(body.contains(
                        "MpSyncPolicy.markCommand(CloudMusic.roomBlendRemainingMillis() + MpSyncPolicy.BLEND_SETTLE_MS)"),
                "固定 4~5 秒盖不住 Folia 那种二十秒的长尾奏：淡入没结束心跳就恢复，截断就是这么来的");
        assertTrue(body.contains("MpSyncPolicy.graceRemainingMs()"),
                "这段保护到底盖了多久要能在真机日志里看见，否则事后无法确认是哪一段被薅断");
    }

    @Test
    @DisplayName("交叠剩余长度量的是渐变自己的进度：暂停时跟着冻住")
    void blendRemainingFollowsTheFadeClock() throws IOException {
        String source = read(ProjectPaths.sharedJava()
                .resolve(Paths.get("com", "muoniumplayer", "core", "ncm", "music", "CloudMusic.java")));

        String accessor = region(source, "public static long roomBlendRemainingMillis()",
                "返回归一化到");
        assertTrue(accessor.contains("blendRemainingMillis()"),
                "一起听那一层要能通过公开入口问到交叠还剩多久 —— 播放在播放线程上，字段不能直接读");

        String body = region(source, "long blendRemainingMillis()", "处理待处理的手动跳过");
        assertTrue(body.contains("progressAt(fade, System.currentTimeMillis())"),
                "必须用渐变自己的进度：startedAt 在暂停时会被平移，直接减墙钟会算出一条跑得更快的时钟");
        assertTrue(!body.contains("System.currentTimeMillis() - fade.startedAt"),
                "同上：这一句绕过了渐变时钟");
    }

    @Test
    @DisplayName("房间里的拖动要收尾本次混音，心跳纠偏不要")
    void userSeekEndsTheBlendWhileDriftCorrectionDoesNot() throws IOException {
        String source = read(PARTY.resolve("MpPlayback.java"));

        String seek = region(source, "public static void seek(final long posMs)", "房间停了（队列播完");
        assertTrue(seek.contains("CloudMusic.onSeekApplied()"),
                "房主拖了进度条，各端这次交叠都作废：不收尾就会让旧歌在新歌已经跑远的地方继续淡出");
        assertTrue(seek.indexOf("setPlaybackTime(posMs)") < seek.indexOf("onSeekApplied()"),
                "先动位置再通知——反过来 automix 读到的还是旧播放头");

        String time = region(source, "public static void time(final long posMs", "这一帧心跳要不要硬跳播放头");
        assertFalse(time.contains("onSeekApplied"),
                "纠偏是按偏差阈值触发的修正，交叠期本来就让路（见 decideSeek 那两道闸）。"
                        + "把它当成一次拖进度条，正在叠的接缝会被每一次心跳薅断——就是这一类故障的老根");
    }

    // ════════════════════════ 共用工具 ════════════════════════

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String region(String source, String from, String to) {
        int start = source.indexOf(from);
        assertTrue(start >= 0, "找不到起点：" + from);
        int end = source.indexOf(to, start);
        assertTrue(end > start, "找不到终点：" + to + "（起点之后）");
        return source.substring(start, end);
    }
}
