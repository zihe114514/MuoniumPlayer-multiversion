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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 绘光换句交接的状态机回归：跑真实 {@link WorldLyricsState#update}，断言
 * prev/own/prevGeo 快照的落位、fading 回退源、引用链截断与 revive 续飞。
 */
class WorldLyricsHuiguangHandoffTest {

    private static final WorldLyricsState.CameraView CAMERA = new WorldLyricsState.CameraView() {
        @Override public double eyeX() { return 0; }
        @Override public double eyeY() { return 64; }
        @Override public double eyeZ() { return 0; }
        @Override public double lookX() { return 0; }
        @Override public double lookY() { return 0; }
        @Override public double lookZ() { return 1; }
        @Override public double yawDeg() { return 0; }
        @Override public double pitchDeg() { return 0; }
    };

    private static final WorldLyricsState.Params PARAMS = new WorldLyricsState.Params() {
        @Override public double distance() { return 6.5; }
        @Override public boolean splitLines() { return false; }
        @Override public double spreadDeg() { return 0; }
        @Override public double scale() { return 1; }
        @Override public boolean facePlayer() { return true; }
        @Override public boolean keepAway() { return false; }
        @Override public boolean wordPop() { return false; }
        @Override public boolean huiguang() { return true; }
    };

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


    /** 帧组装时 fading 在前、active 在后 —— 取 active 行的句子。 */
    private static WorldLyricsState.SpawnedLine activeOf(WorldLyricsState.Frame frame) {
        for (WorldLyricsState.Row r : frame.rows) {
            if (r.active) return r.floating;
        }
        return frame.rows.isEmpty() ? null : frame.rows.get(frame.rows.size() - 1).floating;
    }

    private static LyricLine line(long timestamp, String text) {
        return new LyricLine(timestamp, text);
    }

    @Test
    void lineChangeWiresPrevAndCutsUpstreamChain() {
        // 三句歌词。逐句推进：每次换行 active 句都应拿到 prev（上一句）与几何快照；
        // 被换下的句子的上游引用必须被截断（防 active→N→N−1→… 引用链）。
        CloudMusic.lyrics.add(line(0L, "第一句"));
        CloudMusic.lyrics.add(line(4000L, "第二句"));
        CloudMusic.lyrics.add(line(8000L, "第三句"));

        WorldLyricsState.Frame f1 = state.update(CAMERA, PARAMS, 1_000_000_000L, true, 100L);
        assertEquals(1, f1.rows.size());
        WorldLyricsState.SpawnedLine s1 = activeOf(f1);
        assertNull(s1.huiguangPrev, "首句无上一句");

        WorldLyricsState.Frame f2 = state.update(CAMERA, PARAMS, 1_100_000_000L, true, 4100L);
        WorldLyricsState.SpawnedLine s2 = activeOf(f2);
        assertNotNull(s2.huiguangPrev, "换句后新句应有 prev");
        assertEquals(s1, s2.huiguangPrev, "prev 应是刚被换下的那句");
        assertNotNull(s2.huiguangOwnGeo, "应有本句几何快照");
        assertNotNull(s2.huiguangPrevGeo, "应有上一句几何快照");
        // 引用链截断：s1 自己的 prev 必须是 null（首句本来就没有），geo 断开
        assertNull(s1.huiguangPrevGeo, "被换下句子的上游快照应截断");
        assertNull(s1.huiguangOwnGeo, "被换下句子的自身快照应截断");

        WorldLyricsState.Frame f3 = state.update(CAMERA, PARAMS, 1_200_000_000L, true, 8100L);
        WorldLyricsState.SpawnedLine s3 = activeOf(f3);
        assertEquals(s2, s3.huiguangPrev, "第三句的 prev 应是第二句");
        assertNull(s2.huiguangPrev, "第二句的上游链应截断（不再钉住 s1）");
        assertNull(s2.huiguangOwnGeo);
    }

    @Test
    void gapBeforeNextLineFallsBackToFadingSource() {
        // 两句之间隔一个空行（break 行）：active 短暂为 null，下一句应从
        // 最近一句 fading 飞来（R1 观察项：间隙句不丢飞行源）。
        CloudMusic.lyrics.add(line(0L, "前一句"));
        LyricLine brk = line(4000L, "");
        brk.isBreakLine = true;
        CloudMusic.lyrics.add(brk);
        CloudMusic.lyrics.add(line(8000L, "后一句"));

        WorldLyricsState.Frame f1 = state.update(CAMERA, PARAMS, 1_000_000_000L, true, 100L);
        assertEquals(1, f1.rows.size());
        WorldLyricsState.SpawnedLine s1 = activeOf(f1);

        // 落到 break 行：active 置 null、s1 转淡出
        state.update(CAMERA, PARAMS, 1_100_000_000L, true, 4100L);
        // 再落到后一句：replaced 为 null（active 上一帧已是 null），应回退 fading 源
        WorldLyricsState.Frame f3 = state.update(CAMERA, PARAMS, 1_200_000_000L, true, 8100L);
        WorldLyricsState.SpawnedLine s3 = activeOf(f3);
        assertNotNull(s3, "空行后的实句应正常 spawn");
        assertEquals(s1, s3.huiguangPrev, "间隙后应回退最近一句 fading 当飞行源");
        assertNotNull(s3.huiguangOwnGeo);
        assertNotNull(s3.huiguangPrevGeo);
    }

    @Test
    void reviveInsideFlightWindowContinuesNotReflies() {
        // 同句 retire→revive（暂停去抖恢复）不重建飞行定义：缓存的 huiguangEntry
        // 保持不变（字从空中续飞，不是重飞）。
        CloudMusic.lyrics.add(line(0L, "第一句"));
        CloudMusic.lyrics.add(line(4000L, "第二句"));

        state.update(CAMERA, PARAMS, 1_000_000_000L, true, 100L);
        WorldLyricsState.Frame f2 = state.update(CAMERA, PARAMS, 1_100_000_000L, true, 4100L);
        WorldLyricsState.SpawnedLine s2 = activeOf(f2);
        assertNotNull(s2.huiguangPrev);
        // update() 不调 buildGlyphs（那是渲染层的活）—— 手动构建一次把
        // 飞行定义缓存出来，revive 后再 build 比较数组实例。update 传 null
        // 宽度回调不行，用与句宽恒定的 lambda。
        WorldLyricsState.PixelWidthOf width = s -> 10f;
        WorldLyricsState.buildGlyphs(s2, 4300L, 1f, false, false, width, false, true, null);
        HuiguangMotion.Flight[] before = s2.huiguangEntry;
        assertNotNull(before, "首绘应已构建飞行定义（此前该断言因未构建而空洞）");

        // 同句 revive（走 takeFadingIfSameLine 路径要先 retire——用暂停窗口模拟：
        // 持续暂停让它转淡出，再恢复同 idx）
        state.update(CAMERA, PARAMS, 1_300_000_000L, false, 4300L);
        WorldLyricsState.Frame back = state.update(CAMERA, PARAMS, 1_400_000_000L, true, 4350L);
        assertEquals(s2, activeOf(back), "同句应被认回（不重建）");
        WorldLyricsState.buildGlyphs(s2, 4350L, 1f, false, false, width, false, true, null);
        assertTrue(s2.huiguangEntry == before,
                "revive 不得重建飞行定义（同一数组实例 = 续飞）");
    }

    @Test
    void seekJumpStillFormsHandoffFromSkippedLine() {
        // 时间轴大跳（seek）：被跳过的旧句就是飞行源 —— folia 式退化。
        CloudMusic.lyrics.add(line(0L, "第一句"));
        CloudMusic.lyrics.add(line(10000L, "远处的句子"));

        WorldLyricsState.Frame f1 = state.update(CAMERA, PARAMS, 1_000_000_000L, true, 100L);
        WorldLyricsState.SpawnedLine s1 = activeOf(f1);

        WorldLyricsState.Frame f2 = state.update(CAMERA, PARAMS, 1_100_000_000L, true, 10100L);
        WorldLyricsState.SpawnedLine s2 = activeOf(f2);
        assertEquals(s1, s2.huiguangPrev, "seek 大跳同样形成交接，源是被跳过的句子");
    }

    @Test
    void karaokeLitPerCharEvenWithWordPopOff() {
        // 逐字点亮（卡拉OK扫掠）曾被门在 wordPop（弹跳设置，默认关）后面：
        // 关掉弹跳 → flashMode=false → 真实逐字时刻被忽略，整句 250ms 内
        // 一起点亮（用户实证的「整句点亮」回归）。有逐字轴就应逐字点亮，
        // wordPop 只管「唱到的字弹一下」。
        LyricLine l = new LyricLine(0L, "黑色毛衣");
        l.words.add(new LyricLine.Word("黑色", 0L, 600L));
        l.words.add(new LyricLine.Word("毛衣", 600L, 600L));
        CloudMusic.lyrics.add(l);

        WorldLyricsState.Frame f = state.update(CAMERA, PARAMS, 1_000_000_000L, true, 100L);
        WorldLyricsState.SpawnedLine cur = activeOf(f);
        assertNotNull(cur, "首句应 spawn");

        // PARAMS.wordPop()=false：t=400ms 时第一个词点亮、第二个词未亮
        WorldLyricsState.LyricGlyphs g = WorldLyricsState.buildGlyphs(
                cur, 400L, 1f, false, false, s -> 10f, false, true, null);
        assertEquals(4, g.glyphs.size());
        assertTrue(g.glyphs.get(0).huiguangLit > 0.5f,
                "首字 400ms 应已扫掠过半，实际 " + g.glyphs.get(0).huiguangLit);
        assertTrue(g.glyphs.get(2).huiguangLit < 0.2f,
                "第二个词（600ms 起）在 400ms 应未点亮，实际 "
                        + g.glyphs.get(2).huiguangLit);

        // t=900ms：前一个词满亮、后一个词扫掠中
        WorldLyricsState.LyricGlyphs g2 = WorldLyricsState.buildGlyphs(
                cur, 900L, 1f, false, false, s -> 10f, false, true, null);
        assertEquals(1f, g2.glyphs.get(0).huiguangLit, 0.02f);
        assertTrue(g2.glyphs.get(2).huiguangLit > 0.2f && g2.glyphs.get(2).huiguangLit < 1f,
                "600ms 起的词在 900ms 应扫掠中，实际 " + g2.glyphs.get(2).huiguangLit);
    }

    @Test
    void flashMetaLinesAreFiltered() {
        // 歌曲开头的商业附加信息（制作人员表/平台声明）通常 0.1~0.5s 一行地
        // 快速闪过 —— 绘光下每行都是一次字形烘焙风暴 + 飞入重排，连续快闪
        // 就是连续掉帧尖峰。命中关键词 + 行短 + 挂载时长短的行不进 3D 歌词。
        CloudMusic.lyrics.add(line(0L, "作词 : 某某 作曲 : 某某"));          // 400ms 闪过 → 滤
        CloudMusic.lyrics.add(line(400L, "xx音乐 独家出品"));               // 300ms 闪过 → 滤
        CloudMusic.lyrics.add(line(700L, "未经许可 不得翻唱"));             // 400ms 闪过 → 滤
        CloudMusic.lyrics.add(line(1100L, "真实的歌词第一句"));             // 正常行 → 留
        CloudMusic.lyrics.add(line(6000L, "真实的歌词第二句"));             // 正常行 → 留

        WorldLyricsState.Frame f = state.update(CAMERA, PARAMS, 1_000_000_000L, true, 200L);
        // pos=200ms：原表里 0/400/700ms 三行都是「当前行」候选，过滤后当前行
        // 只能是 1100ms 的真实歌词……但 200ms 还没到 1100，应该无行可画
        assertTrue(f.rows.isEmpty(), "开头三行商业附加信息应被过滤，实际 "
                + f.rows.size() + " 行");
        // 唱到 1200ms：当前行是「真实的歌词第一句」
        WorldLyricsState.Frame f2 = state.update(CAMERA, PARAMS, 1_100_000_000L, true, 1200L);
        WorldLyricsState.SpawnedLine cur = activeOf(f2);
        assertNotNull(cur, "过滤后应正常进入真实歌词行");
        assertTrue(cur.lines.get(0).contains("真实"), "当前句应是真实歌词，实际 "
                + cur.lines.get(0));

        // 长挂载的「疑似元数据」行不误伤：一句 5 秒的歌词即使含「作词」字眼也保留
        CloudMusic.lyrics.clear();
        CloudMusic.lyrics.add(line(0L, "我把作词写进了歌里这是一句很长的真实歌词"));
        CloudMusic.lyrics.add(line(5000L, "下一句"));
        WorldLyricsState.Frame f3 = state.update(CAMERA, PARAMS, 1_200_000_000L, true, 100L);
        WorldLyricsState.SpawnedLine cur3 = activeOf(f3);
        assertNotNull(cur3, "长挂载的含关键词正常歌词行不应被误过滤");
    }

    @Test
    void presetDoesNotSpawnAnymore() {
        CloudMusic.lyrics.add(line(0L, "当前句"));
        CloudMusic.lyrics.add(line(3000L, "下一句不再提前出现"));

        state.update(CAMERA, PARAMS, 1_000_000_000L, true, 100L);
        // 下一句开始前 1.2s = 1800ms；pos=1900 原本是 LEAD 窗口。
        // 100→1900ms 的位置差超 seek 阈值：同句重同步走销毁重建，帧里
        // 除淡出的旧句外只有新 spawn 的当前句 —— 没有预摆句。
        WorldLyricsState.Frame f = state.update(CAMERA, PARAMS, 1_100_000_000L, true, 1900L);
        WorldLyricsState.SpawnedLine active = activeOf(f);
        assertNotNull(active, "LEAD 窗口内当前句应在场（seek 重同步后重新 spawn）");
        for (WorldLyricsState.Row r : f.rows) {
            if (r.floating == active) {
                assertTrue(r.active, "当前句应是 active 态");
            } else {
                assertFalse(r.active, "LEAD 窗口内不应有预摆句，淡出的只有重同步前的旧句");
            }
        }

        // 换句瞬间：新句正常转正（非预摆来源），从旧句位置交接飞入
        WorldLyricsState.Frame f2 = state.update(CAMERA, PARAMS, 1_200_000_000L, true, 3100L);
        WorldLyricsState.SpawnedLine cur = activeOf(f2);
        assertNotNull(cur, "换句后新句应 spawn");
        assertFalse(cur.huiguangPresetDone, "不再有预摆转正路径");
    }

    @Test
    void oldLineSlidesUpDuringLinger() {
        // 旧句让位：余光淡出期间旧句向上滑开（y 减小），确保与新句不重叠
        CloudMusic.lyrics.add(line(0L, "第一句"));
        CloudMusic.lyrics.add(line(4000L, "第二句"));

        state.update(CAMERA, PARAMS, 1_000_000_000L, true, 100L);
        WorldLyricsState.Frame f1 = state.update(CAMERA, PARAMS, 1_100_000_000L, true, 4100L);
        WorldLyricsState.SpawnedLine s1 = activeOf(f1);
        double oldY = f1.rows.get(0).y;

        // 换句 + 1.0s 后（滑开已基本完成：up = sin(1/1.2·π/2) ≈ 0.97）
        WorldLyricsState.Frame f2 = state.update(CAMERA, PARAMS, 1_800_000_000L, true, 5000L);
        WorldLyricsState.Row oldRow = null;
        WorldLyricsState.Row newRow = null;
        for (WorldLyricsState.Row r : f2.rows) {
            if (r.floating == s1) oldRow = r;
            else newRow = r;
        }
        assertNotNull(oldRow, "旧句应仍在余光中");
        assertNotNull(newRow);
        // 让位 = 旧句与新句的空间距离被拉开（滑开 ≈0.9 格 + lift）；
        // 旧版 MC 的 facePlayer 投影会让 y 分量缩放 —— 用 3D 欧氏距离更稳
        double dist = Math.sqrt(Math.pow(newRow.x - oldRow.x, 2)
                + Math.pow(newRow.y - oldRow.y, 2)
                + Math.pow(newRow.z - oldRow.z, 2));
        assertTrue(dist > 0.05, "旧句应向上让位（两行空间距离应拉开），实际 " + dist);
    }

    @Test
    void lingerDoesNotProtectDimIncomingLine() {
        // 换句交接（余光窗口）里，旧句的 huiguangLinger 不得触发双向保护压暗：
        // 生成钳制（散开角收进可见锥 / 横向钳到 10.5 格基线）让相邻两句锚点
        // 常驻 8 格内，保护压暗会在整个 1.2s 余光窗口把新句摁在 0.4~0.7 档 ——
        // 新句的逐字点亮/闪点/光晕全被压灭，观感即「上一句没消失前新句不点亮、
        // 全是暗字」（用户实证的长期缺陷）。余光句自带熄灭曲线 + 让位滑开，
        // 重叠由交接动效自己管理；跳过保护也不会出现 P2-7 的主次颠倒
        //（旧句 0.75 < 新句 1.0）。本测试 PARAMS 用 spreadDeg=0（相邻锚点几乎
        // 重合，旧代码下保护压暗必触发 0.4 地板档）来锁死该回归。
        CloudMusic.lyrics.add(line(0L, "第一句"));
        CloudMusic.lyrics.add(line(4000L, "第二句"));

        state.update(CAMERA, PARAMS, 1_000_000_000L, true, 100L);
        WorldLyricsState.Frame f2 = state.update(CAMERA, PARAMS, 1_100_000_000L, true, 4100L);
        WorldLyricsState.SpawnedLine s2 = activeOf(f2);
        assertNotNull(s2.huiguangPrev, "换句应建立交接（旧句进余光）");

        // 余光窗口内（换句后 0.1s，旧句 0.75 档还在）：新句不得被压暗
        WorldLyricsState.Frame f3 = state.update(CAMERA, PARAMS, 1_200_000_000L, true, 4400L);
        WorldLyricsState.Row newRow = null;
        WorldLyricsState.Row oldRow = null;
        for (WorldLyricsState.Row r : f3.rows) {
            if (r.floating == s2) newRow = r;
            else oldRow = r;
        }
        assertNotNull(newRow, "新句应在场");
        assertNotNull(oldRow, "余光旧句应在场");
        assertEquals(1f, newRow.protectDim, 0.001f,
                "余光句不得触发对新句的保护压暗，实际 " + newRow.protectDim);
        assertEquals(1f, newRow.alpha, 0.001f,
                "新句 alpha 不应被余光句压暗，实际 " + newRow.alpha);
        // 旧句只走自己的余光曲线（0.75 档），不被双向压暗再乘一道
        assertEquals(0.75f, oldRow.alpha, 0.02f,
                "余光旧句应保持自身熄灭曲线的 0.75 档，实际 " + oldRow.alpha);
    }
}
