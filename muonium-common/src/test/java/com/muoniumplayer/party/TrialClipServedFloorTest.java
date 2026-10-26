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
 * 试听片段判定的<b>实测路绝对豁免</b>（两端同源）。
 *
 * <p>根因：servedMs（取流接口报的「这条链接实际服务多长」）与 trackMs（点歌人上传的
 * 曲目元数据时长）来自<b>两个不同的接口</b>，同名不同版本可以差出 20% 以上。纯比例判据
 * （0.8）会把一条 3 分 55 秒的完整版对着 5 分钟的元数据误判成试听 —— 误判是确定性的，
 * 房间里每个会员都在同一个判定上弃权，于是「全员 VIP」的房间照样全层落空，再被换源
 * 救援改成别的平台版本（用户表现为「莫名其妙换成哔哩哔哩」）。修复是加一条绝对豁免线：
 * 真实试听固定 30/60 秒量级，实测流超过 90 秒必然是完整歌。</p>
 *
 * <p>两端（服务端 {@code Track.isTrialClip} 与客户端 {@code MpPartyClient.isTrialClip}）
 * 必须同源：客户端放行、服务端拒收的话，答案会交上去再被丢掉，全房白等一轮。</p>
 */
class TrialClipServedFloorTest {

    private static final long FLOOR_MS = 90_000L;

    // ═══════════════ 服务端：真实判据（可加载，mpserver-core 不碰 Minecraft） ═══════════════

    private static final Path SERVER_TRACK = ProjectPaths.repoRoot().resolve(Paths.get(
            "mpserver-core", "src", "main", "java", "yun", "shan", "mpsync", "room", "Track.java"));

    private static yun.shan.mpsync.config.MpConfig cfg() {
        return new yun.shan.mpsync.config.MpConfig();
    }

    @Test
    @DisplayName("实测路：90 秒以上的流不判试听（版本错位豁免）")
    void serverMeasuredStreamAboveFloorPasses() {
        // 元数据 5 分钟、实际流 3:55 —— 比例 0.783 < 0.8,修复前误判为试听。
        assertFalse(yun.shan.mpsync.room.Track.isTrialClip(
                        235_000L, 300_000L, cfg()),
                "完整版流被元数据版本错位误杀,全房会员会集体弃权");
        assertTrue(yun.shan.mpsync.room.Track.isTrialClip(
                        60_000L, 300_000L, cfg()),
                "60 秒真试听必须照旧拦下");
        assertTrue(yun.shan.mpsync.room.Track.isTrialClip(
                        30_000L, 240_000L, cfg()),
                "30 秒真试听必须照旧拦下");
    }

    @Test
    @DisplayName("估算路:豁免线不适用,阈值维持更宽的 0.5")
    void serverEstimatedPathKeepsLooseRatio() {
        // 估算路不享受豁免:码率猜错时 90 秒的截断流能被估成三分钟。
        assertTrue(yun.shan.mpsync.room.Track.isTrialClip(
                        100_000L, 300_000L, cfg(), true),
                "估算 100s/300s(0.33)仍是试听,豁免只给实测路");
        assertFalse(yun.shan.mpsync.room.Track.isTrialClip(
                        200_000L, 300_000L, cfg(), true),
                "估算 200s/300s(0.67)过 0.5 阈值,照旧放行");
    }

    @Test
    @DisplayName("未知时长照旧放行,不因豁免改动而变严")
    void serverUnknownDurationsStillPass() {
        assertFalse(yun.shan.mpsync.room.Track.isTrialClip(0L, 300_000L, cfg()));
        assertFalse(yun.shan.mpsync.room.Track.isTrialClip(60_000L, 0L, cfg()));
    }

    // ═══════════════ 两端同源:豁免线与判定顺序必须成对出现 ═══════════════

    @Test
    @DisplayName("客户端 MpPartyClient.isTrialClip 与服务端同一条豁免线")
    void clientCarriesSameFloor() throws IOException {
        String client = Files.readString(ProjectPaths.repoRoot().resolve(Paths.get(
                "mpparty-common", "src", "main", "java", "com", "muoniumplayer",
                "party", "MpPartyClient.java")), StandardCharsets.UTF_8);
        assertTrue(client.contains("TRIAL_CLIP_SERVED_FLOOR_MS = 90_000L"),
                "客户端缺 90s 豁免线,或与服务端数值不一致 —— 两端劈叉会出现"
                        + "「客户端放行、服务端拒收」的全房白等");
        assertTrue(client.contains("servedMs > TRIAL_CLIP_SERVED_FLOOR_MS"),
                "客户端豁免判据丢失");
    }

    @Test
    @DisplayName("服务端 Track 豁免线存在且只豁免实测路")
    void serverFloorGuardsEstimatedPath() throws IOException {
        String track = Files.readString(SERVER_TRACK, StandardCharsets.UTF_8);
        assertTrue(track.contains("TRIAL_CLIP_SERVED_FLOOR_MS = 90_000L"),
                "服务端缺 90s 豁免线,或与客户端数值不一致");
        // 豁免必须先判 !estimated:估算路照旧走放宽的 0.5。
        int guard = track.indexOf("!estimated && servedMs > TRIAL_CLIP_SERVED_FLOOR_MS");
        assertTrue(guard >= 0, "豁免没有限定实测路(估算路的真试听会被放进房间)");
        int ratio = track.indexOf("cfg.estimatedStreamDurationRatio");
        assertTrue(ratio > guard, "估算路的比值判定必须仍在豁免之后生效");
    }
}
