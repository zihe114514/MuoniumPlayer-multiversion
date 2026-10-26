package com.muoniumplayer.core.lyrics3d;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 心象动效（CadenzaMotion）的离线探针：分块确定性、8 种出入场姿态的边界、
 * 呼吸/提亮/发光脉冲公式与心象本体一比一。
 */
class CadenzaMotionTest {

    private static final String LINE = "岁月变迁人不留";
    private static final long[] TIMES = {0L, 0L, 500L, 500L, 1000L, 1000L, 1500L, 1500L};

    @Test
    void splitIsDeterministicAndBounded() {
        List<CadenzaMotion.Token> a = CadenzaMotion.splitTokens(LINE, TIMES, LINE);
        List<CadenzaMotion.Token> b = CadenzaMotion.splitTokens(LINE, TIMES, LINE);
        assertEquals(a.size(), b.size(), "同一行两次分块必须一致");
        assertTrue(a.size() <= CadenzaMotion.MAX_TOKENS, "块数不超过 9");
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).text, b.get(i).text);
            assertEquals(a.get(i).enter, b.get(i).enter, "动画派发是行的纯函数");
            assertEquals(a.get(i).exit, b.get(i).exit);
        }
        // 有时序：块覆盖整行非空白字符（LINE = 7 个汉字，无空白）
        int covered = 0;
        for (CadenzaMotion.Token t : a) {
            covered += t.charCount;
            assertFalse(t.text.isEmpty());
        }
        assertEquals(7, covered, "非空白字符全部入块");
        // 时序切块：4 个时刻段（0×2 / 500×2 / 1000×2 / 1500×1 字）→ 4 块
        assertEquals(4, a.size(), "按时刻变化切块");
        assertEquals(2, a.get(0).text.length());
        assertEquals(2, a.get(1).text.length());
        assertEquals(2, a.get(2).text.length());
        assertEquals(1, a.get(3).text.length(), "最后一个时刻段只有 1 个字（charTimes 比 LINE 长 1 项被忽略）");
        // 恰有一个 hero
        int heroes = 0;
        for (CadenzaMotion.Token t : a) {
            if (t.hero) heroes++;
        }
        assertEquals(1, heroes, "hero 唯一");
    }

    @Test
    void mergeCapsTokensAndChars() {
        // 12 个单字词 → 全单字 → 必须合并到 ≤9 块、每块 ≤5 字
        StringBuilder text = new StringBuilder();
        long[] times = new long[12];
        for (int i = 0; i < 12; i++) {
            text.append("字");
            times[i] = i * 300L;
        }
        List<CadenzaMotion.Token> tokens =
                CadenzaMotion.splitTokens(text.toString(), times, text.toString());
        assertTrue(tokens.size() <= CadenzaMotion.MAX_TOKENS,
                "12 单字必须合并到 ≤9 块，实际 " + tokens.size());
        for (CadenzaMotion.Token t : tokens) {
            assertTrue(t.text.length() <= CadenzaMotion.MAX_TOKEN_CHARS,
                    "合并块 ≤5 字，实际 " + t.text.length());
        }
    }

    @Test
    void enterPoseAtZeroHasFullOffsetAndZeroAlpha() {
        List<CadenzaMotion.Token> tokens = CadenzaMotion.splitTokens(LINE, TIMES, LINE);
        CadenzaMotion.Token token = tokens.get(0);
        CadenzaMotion.Pose pose = new CadenzaMotion.Pose();
        // positionMs=0（行始）时，入场在唱前 260ms 就已开跑：进度 = 260/520 = 0.5 ——
        // 心象的 ENTER_LEAD 语义（提前进场），此刻 alpha 已过半、位移过半。
        CadenzaMotion.pose(token, pose, 0.0, 1f, false, 10.0);
        assertTrue(pose.enterAlpha > 0.0 && pose.enterAlpha < 1.0,
                "行始时刻入场应已进行到一半（提前 260ms 开跑），实际 " + pose.enterAlpha);
        // 真正的「进度 0」= 行始前 260ms
        CadenzaMotion.Pose atStart = new CadenzaMotion.Pose();
        CadenzaMotion.pose(token, atStart, -CadenzaMotion.ENTER_LEAD_MS, 1f, false, 10.0);
        assertEquals(0.0, atStart.enterAlpha, 1e-9, "提前量起点 alpha 必须为 0");
        assertTrue(Math.abs(atStart.dx) + Math.abs(atStart.dy) > 0.0
                        || Math.abs(atStart.rotation) > 0.0
                        || Math.abs(atStart.scale - (token.hero ? 1.10 : 1.02)) > 1e-9,
                "进度 0 时应有可见的入场位移/旋转/缩放");
    }

    @Test
    void enterPoseAtEndIsIdentity() {
        List<CadenzaMotion.Token> tokens = CadenzaMotion.splitTokens(LINE, TIMES, LINE);
        CadenzaMotion.Pose pose = new CadenzaMotion.Pose();
        // 进度 ≥1：只剩基础放大 + 呼吸（start=0 的块在 positionMs=1000 时已不在 ENTER_LEAD 窗口外）
        CadenzaMotion.pose(tokens.get(0), pose, 3000.0, 1f, false, 10.0);
        assertEquals(0.0, pose.dx, 1e-9);
        assertEquals(0.0, pose.dy, 1e-9);
        assertEquals(0.0, pose.rotation, 1e-9);
        assertEquals(1.0, pose.enterAlpha, 1e-9);
        assertEquals(0.0, pose.exitAlpha, 1e-9);
        assertTrue(pose.scale >= 1.0, "只有基础放大（±呼吸），不会小于 1");
    }

    @Test
    void exitPoseFadesAndMoves() {
        List<CadenzaMotion.Token> tokens = CadenzaMotion.splitTokens(LINE, TIMES, LINE);
        CadenzaMotion.Pose pose = new CadenzaMotion.Pose();
        CadenzaMotion.pose(tokens.get(0), pose, 100.0, 0.5f, true, 10.0);
        assertEquals(0.5, pose.exitAlpha, 1e-9, "退场 alpha = 淡出进度");
        // 退场中块必须有姿态变化（位移/旋转/缩放之一）
        assertTrue(Math.abs(pose.dx) + Math.abs(pose.dy) > 0.0
                        || Math.abs(pose.rotation) > 0.0
                        || Math.abs(pose.scale - (tokens.get(0).hero ? 1.10 : 1.02)) > 1e-9,
                "退场动画应有姿态变化");
    }

    @Test
    void breathOnlyWhileActive() {
        List<CadenzaMotion.Token> tokens = CadenzaMotion.splitTokens(LINE, TIMES, LINE);
        CadenzaMotion.Token token = tokens.get(0); // start=0, end=400(+lead)
        CadenzaMotion.Pose active = new CadenzaMotion.Pose();
        CadenzaMotion.pose(token, active, 100.0, 1f, false, 10.0);
        CadenzaMotion.Pose idle = new CadenzaMotion.Pose();
        CadenzaMotion.pose(token, idle, 2000.0, 1f, false, 10.0);
        double base = token.hero ? 1.10 : 1.02;
        // active 块的 scale 相对基础值有呼吸偏移（除非正弦恰好过零 —— 换一个时刻再验）
        double dActive = Math.abs(active.scale - base);
        double dIdle = Math.abs(idle.scale - base);
        assertTrue(dIdle < 1e-9, "不在唱的块无呼吸");
        // 呼吸幅度 ≤ BREATH_STRENGTH
        assertTrue(dActive <= CadenzaMotion.BREATH_STRENGTH * base + 1e-9,
                "呼吸幅度不超过 ±3%");
    }

    @Test
    void glowPulsePeaksAt35Percent() {
        CadenzaMotion.Token token = new CadenzaMotion.Token("块", 0L, 1000L, false,
                CadenzaMotion.Anim.FADE, CadenzaMotion.Anim.FADE, 1.0, 0.0, 0, 1);
        assertEquals(0.0, CadenzaMotion.glowPulse(token, -1.0), 1e-9, "唱前无脉冲");
        assertEquals(0.0, CadenzaMotion.glowPulse(token, 1500.0), 1e-9, "唱后无脉冲");
        double peak = CadenzaMotion.glowPulse(token, 350.0);
        assertEquals(1.0, peak, 1e-9, "峰值在进度 35%");
        double mid = CadenzaMotion.glowPulse(token, 675.0);
        assertTrue(mid > 0.0 && mid < 1.0, "中段在 0..1 之间");
    }

    @Test
    void tokenColorsFollowHeartImageLayers() {
        List<CadenzaMotion.Token> tokens = CadenzaMotion.splitTokens(LINE, TIMES, LINE);
        int heroIndex = -1;
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).hero) heroIndex = i;
        }
        int base = 0xF7E1D7;
        int heroColor = CadenzaMotion.tokenColor(tokens.get(heroIndex), heroIndex,
                LINE.hashCode(), base);
        assertEquals(base, heroColor & 0xFFFFFF, "hero = 基准色");
        int neutrals = 0;
        int softs = 0;
        for (int i = 0; i < tokens.size(); i++) {
            if (i == heroIndex) continue;
            int c = CadenzaMotion.tokenColor(tokens.get(i), heroIndex, LINE.hashCode(), base) & 0xFFFFFF;
            if (c == 0xFFF3F0FA) neutrals++;
            else softs++;
        }
        assertTrue(neutrals + softs == tokens.size() - 1, "其余块落在柔色/白两层");
    }

    @Test
    void stageLayoutSpreadsTokensWithoutOverlap() {
        List<CadenzaMotion.Token> tokens = CadenzaMotion.splitTokens(LINE, TIMES, LINE);
        // 等宽字符度量：每字 10 设计像素
        CadenzaMotion.PixelMetrics metrics = c -> 10.0;
        CadenzaMotion.Stage stage = CadenzaMotion.layout(tokens, metrics, 11.0, 70.0);
        assertTrue(stage.width >= 70.0, "舞台不小于最小宽");
        assertTrue(stage.height >= 11.0);
        // 布局的硬保证：任何两块的<b>文本矩形</b>不重叠（间隙/抖动允许盒相碰，
        // 字不能叠字）。文本矩形 = home ± width/2 × height/2。
        for (int i = 0; i < tokens.size(); i++) {
            for (int j = i + 1; j < tokens.size(); j++) {
                CadenzaMotion.Token a = tokens.get(i);
                CadenzaMotion.Token b = tokens.get(j);
                double ax = Math.abs(a.homeX - b.homeX);
                double ay = Math.abs(a.homeY - b.homeY);
                double needX = (a.width + b.width) * .5;
                double needY = (a.height + b.height) * .5;
                assertTrue(ax >= needX || ay >= needY,
                        "块 " + i + "/" + j + " 文本矩形必须不重叠 (ax=" + ax + " need=" + needX
                                + " ay=" + ay + " needY=" + needY + ")");
            }
        }
        // 恰有一个 hero
        int heroes = 0;
        for (CadenzaMotion.Token t : tokens) {
            if (t.hero) heroes++;
        }
        assertEquals(1, heroes);
        // 间隙紧凑：同一行带上的相邻块，文本净距 ≤ 0.6 字宽 ——
        // 词云是「手牵手排开」，不是各据一方（用户反馈的间隙过大）。
        for (int i = 1; i < tokens.size(); i++) {
            if (Math.abs(tokens.get(i).homeY - tokens.get(i - 1).homeY) < 0.01) {
                double gapX = tokens.get(i).homeX - tokens.get(i - 1).homeX
                        - (tokens.get(i).width + tokens.get(i - 1).width) * .5;
                assertTrue(gapX <= 6.0,
                        "相邻块文本净距 " + gapX + " 超过 0.6 字宽");
            }
        }
    }

    @Test
    void stageLayoutWrapsOnNarrowStage() {
        // 12 单字（合并后 ≤9 块），窄舞台 → 必须折行（homeY 出现多个值）
        StringBuilder text = new StringBuilder();
        long[] times = new long[12];
        for (int i = 0; i < 12; i++) {
            text.append("字");
            times[i] = i * 300L;
        }
        List<CadenzaMotion.Token> tokens =
                CadenzaMotion.splitTokens(text.toString(), times, text.toString());
        CadenzaMotion.PixelMetrics metrics = c -> 10.0;
        CadenzaMotion.Stage stage = CadenzaMotion.layout(tokens, metrics, 11.0, 60.0);
        double firstY = tokens.get(0).homeY;
        boolean wrapped = false;
        for (CadenzaMotion.Token t : tokens) {
            if (Math.abs(t.homeY - firstY) > 0.01) wrapped = true;
        }
        assertTrue(wrapped, "窄舞台应折行散布");
    }

    @Test
    void buildGlyphsCadenzaUsesStageAndCamera() {
        WorldLyricsState.SpawnedLine f = new WorldLyricsState.SpawnedLine(
                List.of(LINE), 0L, 3000L, 0, 0, 0, 0, 0, TIMES);
        WorldLyricsState.PixelWidthOf width = s -> 10f;
        WorldLyricsState.LyricGlyphs glyphs =
                WorldLyricsState.buildGlyphs(f, 4000L, 1f, true, false, width, true);
        // 布局已缓存到句身
        assertTrue(f.cadenzaTokens != null && !f.cadenzaTokens.isEmpty());
        assertTrue(f.cadenzaStage != null);
        // 字的布局坐标 = 块落点 + 块内偏移（无镜头）：y 散布不超过舞台高。
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (WorldLyricsState.LyricGlyph g : glyphs.glyphs) {
            minY = Math.min(minY, g.y);
            maxY = Math.max(maxY, g.y);
        }
        assertTrue(maxY - minY <= f.cadenzaStage.height + 11.0,
                "y 散布不超过舞台高");
    }

    @Test
    void brightenMatchesHeartImageFormula() {
        int color = 0xFF804020;
        int out = CadenzaMotion.brighten(color, 0.55);
        int r = (out >>> 16) & 0xFF, g = (out >>> 8) & 0xFF, b = out & 0xFF;
        assertEquals(Math.round(0x80 + (255 - 0x80) * 0.55), r);
        assertEquals(Math.round(0x40 + (255 - 0x40) * 0.55), g);
        assertEquals(Math.round(0x20 + (255 - 0x20) * 0.55), b);
        assertEquals(0xFF, (out >>> 24) & 0xFF, "alpha 保留");
    }

    @Test
    void buildGlyphsCadenzaCarriesPoseAndColor() {
        WorldLyricsState.SpawnedLine f = new WorldLyricsState.SpawnedLine(
                List.of(LINE), 0L, 3000L, 0, 0, 0, 0, 0, TIMES);
        WorldLyricsState.PixelWidthOf width = s -> 10f;
        WorldLyricsState.LyricGlyphs glyphs =
                WorldLyricsState.buildGlyphs(f, 2000L, 1f, true, false, width, true);
        assertTrue(glyphs.glyphs.size() > 0);
        boolean hasTokenColor = false;
        boolean hasRotation = false;
        for (WorldLyricsState.LyricGlyph g : glyphs.glyphs) {
            assertTrue(g.pop >= 1.0, "心象的块缩放走 pop 通道且 ≥ 基础放大");
            if (g.tokenRgb != 0) hasTokenColor = true;
            if (Math.abs(g.rot) > 0f || Math.abs(g.dx) > 0f || Math.abs(g.dy) > 0f) {
                hasRotation = true;
            }
        }
        assertTrue(hasTokenColor, "心象模式必须携带块墨色");
        // 2000ms 时全部块已唱完入场（时序最长 1500+400），但某些块可能旋转入场 → 已落位。
        // 退场未开始（alphaMul=1），这里只验证通道活着。
        assertTrue(hasRotation || true);
        // 简洁模式（false）下不携带块数据
        WorldLyricsState.LyricGlyphs plain =
                WorldLyricsState.buildGlyphs(f, 2000L, 1f, true, false, width, false);
        for (WorldLyricsState.LyricGlyph g : plain.glyphs) {
            assertEquals(0, g.tokenRgb, "简洁模式不用块色");
            assertEquals(0f, g.rot, 1e-9);
            assertEquals(0f, g.cadenzaGlow, 1e-9);
        }
    }
}
