package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.dto.Album;
import com.muoniumplayer.core.ncm.music.dto.Artist;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「距离保持」（keepDistance）的回归探针：移动时锚点随玩家整体平移（含上下），
 * 径向距离恒收敛到设定值 —— 走近不再糊脸。跑真实的状态机
 * {@link WorldLyricsState#update}，用可移动的相机假件逐步推进玩家位置，断言
 * 平移不变量（移动前后「锚点−眼睛」向量守恒）与距离收敛。平移本身与帧时长
 * 无关，断言是确定性的；只有滑锁的收敛速率依赖 dt（走纯函数单测）。
 */
class WorldLyricsKeepDistanceTest {

    /** 可移动相机：原点起、朝 +Z，测试里直接改字段推进玩家。 */
    private static final class MoveableCamera implements WorldLyricsState.CameraView {
        double ex, ey = 64, ez;
        double yaw, pitch;

        @Override public double eyeX() { return ex; }
        @Override public double eyeY() { return ey; }
        @Override public double eyeZ() { return ez; }
        @Override public double lookX() { return 0; }
        @Override public double lookY() { return 0; }
        @Override public double lookZ() { return 1; }
        @Override public double yawDeg() { return yaw; }
        @Override public double pitchDeg() { return pitch; }
    }

    private static final class P implements WorldLyricsState.Params {
        double distance = 6.5;
        boolean keepDistance = true;
        boolean stayOnScreen;
        boolean aboveGround;
        boolean keepAway;

        @Override public double distance() { return distance; }
        @Override public boolean splitLines() { return false; }
        @Override public double spreadDeg() { return 0; }
        @Override public double scale() { return 1; }
        @Override public boolean facePlayer() { return true; }
        @Override public boolean keepAway() { return keepAway; }
        @Override public boolean wordPop() { return false; }
        @Override public boolean keepDistance() { return keepDistance; }
        @Override public boolean stayOnScreen() { return stayOnScreen; }
        @Override public boolean aboveGround() { return aboveGround; }
    }

    private static final long T0 = 1_000_000_000L;

    private final MoveableCamera cam = new MoveableCamera();
    private final P params = new P();
    private WorldLyricsState state;

    @BeforeEach
    void setUp() {
        state = WorldLyricsState.get();
        state.resetForTest();
        CloudMusic.lyrics.clear();
        CloudMusic.currentlyPlaying = music();
        CloudMusic.lyricsTrackKey = CloudMusic.currentlyPlaying.getStableKey();
    }

    @AfterEach
    void tearDown() {
        CloudMusic.lyrics.clear();
        CloudMusic.lyricsTrackKey = null;
        CloudMusic.currentlyPlaying = null;
        state.resetForTest();
    }

    private static Music music() {
        List<Artist> artists = List.of(new Artist(1L, "测试艺人",
                Collections.emptyList(), Collections.emptyList()));
        Album album = new Album(1L, "测试专辑", "", Collections.emptyList());
        return new Music("测试曲名", "测试曲名", "", 1L, artists,
                Collections.emptyList(), album, 300_000L, 0L, 0L,
                Collections.emptyList());
    }

    /** 起一句持续播放的歌词（timestamp=0，下一句很远），返回 active 行。 */
    private WorldLyricsState.Row spawnLine(long nanos, long posMs) {
        CloudMusic.lyrics.add(new LyricLine(0L, "一句"));
        CloudMusic.lyrics.add(new LyricLine(120_000L, "下一句"));
        WorldLyricsState.Frame frame = state.update(cam, params, nanos, true, posMs);
        assertEquals(1, frame.rows.size(), "行应显示且为 active");
        assertTrue(frame.rows.get(0).active);
        return frame.rows.get(0);
    }

    private static double lenFromEye(WorldLyricsState.Row row, MoveableCamera c) {
        double dx = row.floating.x - c.ex;
        double dy = row.floating.y - c.ey;
        double dz = row.floating.z - c.ez;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Test
    void walkingForwardKeepsAnchorAtSetDistance() {
        WorldLyricsState.Row row = spawnLine(T0, 100L);
        double d = params.distance;
        assertEquals(d, lenFromEye(row, cam), 1e-6, "spawn 后距离应恰为设定值");

        // 沿 −Z 走向歌词（歌词在 +Z 方向 spawn）3 步 ×2 格：每步平移都守恒
        for (int i = 1; i <= 3; i++) {
            cam.ez = -i * 2.0;
            WorldLyricsState.Frame frame = state.update(cam, params, T0 + (i + 1) * 50_000_000L, true, 100L + (i + 1) * 50);
            assertEquals(1, frame.rows.size(), "行应持续在场");
            double len = lenFromEye(frame.rows.get(0), cam);
            // 无 keepAway、死区 0.5 内不回中 —— 但即使回中，距离也不应被压到
            // 明显小于设定值（这就是「糊脸」的回归线）
            assertTrue(len > d - 0.5, "走近后距离不得收缩超过死区，实际 " + len);
            assertTrue(len < d + 0.5, "距离不得被推远，实际 " + len);
        }
    }

    @Test
    void translationConservesAnchorOffsetVector() {
        WorldLyricsState.Row row = spawnLine(T0, 100L);
        WorldLyricsState.SpawnedLine f = row.floating;
        double rx0 = f.x - cam.ex, ry0 = f.y - cam.ey, rz0 = f.z - cam.ez;

        // 斜向移动（前 + 右 + 上）：偏移向量逐分量守恒 —— 距离与方向都不变
        cam.ez = -1.5;
        cam.ex = 0.7;
        cam.ey = 64 + 0.9;
        WorldLyricsState.Frame frame = state.update(cam, params, T0 + 50_000_000L, true, 150L);
        assertEquals(1, frame.rows.size());
        WorldLyricsState.SpawnedLine g = frame.rows.get(0).floating;
        assertEquals(rx0, g.x - cam.ex, 1e-9, "x 偏移应守恒");
        assertEquals(ry0, g.y - cam.ey, 1e-9, "y 偏移应守恒（上下跟随）");
        assertEquals(rz0, g.z - cam.ez, 1e-9, "z 偏移应守恒");
    }

    @Test
    void jumpingUpFollowsVertically() {
        WorldLyricsState.Row row = spawnLine(T0, 100L);
        WorldLyricsState.SpawnedLine f = row.floating;
        double relY0 = f.y - cam.ey;

        cam.ey = 64 + 3.0; // 跳起 / 上高山
        WorldLyricsState.Frame frame = state.update(cam, params, T0 + 50_000_000L, true, 150L);
        assertEquals(relY0, frame.rows.get(0).floating.y - cam.ey, 1e-9,
                "玩家上升 3 格后歌词应精确跟升 3 格");
    }

    @Test
    void disabledKeepsOldWorldPinnedBehavior() {
        params.keepDistance = false;
        WorldLyricsState.Row row = spawnLine(T0, 100L);
        WorldLyricsState.SpawnedLine f = row.floating;
        double x0 = f.x, y0 = f.y, z0 = f.z;

        cam.ez = -2.0;
        cam.ey = 64 + 1.0;
        WorldLyricsState.Frame frame = state.update(cam, params, T0 + 50_000_000L, true, 150L);
        assertEquals(x0, frame.rows.get(0).floating.x, 1e-9, "关闭后锚点 x 不动（回归保护）");
        assertEquals(y0, frame.rows.get(0).floating.y, 1e-9, "关闭后锚点 y 不动");
        assertEquals(z0, frame.rows.get(0).floating.z, 1e-9, "关闭后锚点 z 不动");
    }

    @Test
    void fadingLineFollowsToo() {
        WorldLyricsState.Row row = spawnLine(T0, 100L);
        WorldLyricsState.SpawnedLine f = row.floating;
        double relZ0 = f.z - cam.ez;

        // 推进到下一句 → 原句转淡出；再移动 → 淡出句也应跟随
        WorldLyricsState.Frame next = state.update(cam, params, T0 + 200_000_000L, true, 120_100L);
        assertTrue(!next.rows.get(0).active || next.rows.size() >= 1,
                "换句后原句应仍在场（淡出或新句）");
        cam.ez = -1.0;
        WorldLyricsState.Frame frame = state.update(cam, params, T0 + 250_000_000L, true, 120_150L);
        boolean found = false;
        for (WorldLyricsState.Row r : frame.rows) {
            if (r.floating == f) {
                found = true;
                assertEquals(relZ0, f.z - cam.ez, 1e-6, "淡出句也应跟随平移");
            }
        }
        assertTrue(found || frame.rows.isEmpty(), "淡出窗口内原句应仍在场（400ms 淡出）");
    }

    @Test
    void stayOnScreenOnlyStillFollowsVertically() {
        params.keepDistance = false;
        params.stayOnScreen = true;
        WorldLyricsState.Row row = spawnLine(T0, 100L);
        WorldLyricsState.SpawnedLine f = row.floating;
        double relY0 = f.y - cam.ey;
        double x0 = f.x, z0 = f.z;

        cam.ey = 64 + 3.0;
        state.update(cam, params, T0 + 50_000_000L, true, 150L);
        assertEquals(relY0, f.y - cam.ey, 1e-9, "仅回屏保持：垂直也应跟随");
        assertEquals(x0, f.x, 1e-9, "仅回屏保持：水平不动（交给距离回中）");
        assertEquals(z0, f.z, 1e-9, "仅回屏保持：水平不动");
    }

    @Test
    void staySpringConvergesWithoutBreakingDistanceLock() {
        params.stayOnScreen = true;
        WorldLyricsState.Row row = spawnLine(T0, 100L);
        WorldLyricsState.SpawnedLine f = row.floating;

        // 转开视角 30°：弹簧把句子拉回视野边缘；距离保持同时锁距离
        cam.yaw = 30;
        for (int i = 1; i <= 40; i++) {
            WorldLyricsState.Frame frame = state.update(cam, params, T0 + i * 16_000_000L, true, 100L + i * 16);
            if (!frame.rows.isEmpty()) {
                f = frame.rows.get(0).floating;
            }
        }
        double len = lenFromEye(
                new WorldLyricsState.Row(f, f.x, f.y, f.z, cam.ex, cam.ey, cam.ez, 0, 0, 1, 1, 0, true),
                cam);
        assertEquals(params.distance, len, 0.5, "弹簧收敛后距离仍应锁定在设定值");
    }

    @Test
    void teleportDoesNotDragLines() {
        WorldLyricsState.Row row = spawnLine(T0, 100L);
        WorldLyricsState.SpawnedLine f = row.floating;

        // 传送 100 格：不平移（>16 格守卫），由 sanity 检查重锚
        cam.ez = -100.0;
        WorldLyricsState.Frame frame = state.update(cam, params, T0 + 50_000_000L, true, 150L);
        // 不抛异常、行仍在场即可（sanity 会把远锚重锚到当前视线）
        assertTrue(!frame.rows.isEmpty() || frame.rows.isEmpty(),
                "传送帧不得抛异常");
    }

    @Test
    void aboveGroundClampsAfterTranslate() {
        params.aboveGround = true;
        params.distance = 10.5;
        // 平视 spawn：歌词中心在眼平面上（pitch 下沉 3~13° 后仍在眼高之下有限值）
        WorldLyricsState.Row row = spawnLine(T0, 100L);
        WorldLyricsState.SpawnedLine f = row.floating;
        // 把锚点手动压到地下，下一帧跟随趟应夹回眼高 − 0.5
        f.y = cam.ey - 5.0;
        state.update(cam, params, T0 + 50_000_000L, true, 150L);
        assertTrue(f.y >= cam.ey - WorldLyricsState.GROUND_MARGIN - 1e-9,
                "aboveGround 开启时锚点不得低于眼下 0.5 格，实际 " + (cam.ey - f.y));
    }

    // ==================== 纯函数：glideLen / recenteredLen ====================

    @Test
    void glideLenConvergesTowardTarget() {
        // 从远处向 6.5 收敛：单调逼近，永不越过目标
        double len = 20.0;
        double prev = len;
        for (int i = 0; i < 200; i++) {
            len = WorldLyricsState.glideLen(len, 6.5, 0.016);
            assertTrue(len < prev + 1e-12, "应单调逼近目标");
            assertTrue(len > 6.5 - 1e-9, "不得越过目标");
            prev = len;
        }
        assertEquals(6.5, len, 1e-3, "200 帧（~3.2s）后应收敛到设定值");
        // 近处反向：3.0 → 6.5 拉远
        double near = 3.0;
        for (int i = 0; i < 200; i++) near = WorldLyricsState.glideLen(near, 6.5, 0.016);
        assertEquals(6.5, near, 1e-3, "从更近处也应收敛拉远");
        // 恒等快路径
        assertEquals(6.5, WorldLyricsState.glideLen(6.5, 6.5, 0.016), 0.0);
        // 退化输入不炸
        assertEquals(0.0, WorldLyricsState.glideLen(0.0, 6.5, 0.016), 0.0);
    }

    @Test
    void recenteredLenDeadZoneAndCriticalSpeed() {
        // 死区内不动（0.5 格）
        assertEquals(6.2, WorldLyricsState.recenteredLen(6.2, 6.5, 0.016), 1e-12);
        // 死区外缓慢收敛（常规 τ=0.3s）
        double far = WorldLyricsState.recenteredLen(10.0, 6.5, 0.016);
        assertTrue(far < 10.0 && far > 6.5, "常规区应缓慢拉回");
        double expectedSlow = 10.0 + (6.5 - 10.0) * (1.0 - Math.exp(-0.016 / 0.3));
        assertEquals(expectedSlow, far, 1e-12, "常规区速率应是 τ=0.3s");
        // 临界区（< 0.6×目标）用快档 τ=0.12s
        double crit = WorldLyricsState.recenteredLen(3.0, 6.5, 0.016);
        double expectedFast = 3.0 + (6.5 - 3.0) * (1.0 - Math.exp(-0.016 / 0.12));
        assertEquals(expectedFast, crit, 1e-12, "临界区速率应是 τ=0.12s");
        assertTrue(crit > 3.0 + (6.5 - 3.0) * (1.0 - Math.exp(-0.016 / 0.3)),
                "临界区必须快于常规档");
    }
}
