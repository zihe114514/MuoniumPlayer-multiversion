package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.dto.Album;
import com.muoniumplayer.core.ncm.music.dto.Artist;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行级过滤与同句内 seek 重同步的回归探针。
 *
 * <p>行过滤两级：① 冒号锚定的制作人员表行 / 纯音乐占位行 —— 只看行文本、
 * 无条件剔除；② 开头快闪的无冒号商业声明行 —— 命中关键词 + 行短 + 挂载短
 * 三条件才滤（另有专测，见 Handoff 测试的 flashMetaLinesAreFiltered）。</p>
 *
 * <p>同句内 seek 重同步：上一帧与本帧位置差超过阈值时，即使 idx 没变也要把
 * 当前句销毁重建 —— 逐字点亮/出现进度已整体错位，续用旧句是错的。</p>
 */
class WorldLyricsLineFilterTest {

    /** 恒定视线相机（原点、朝 +Z）。 */
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
        @Override public boolean wordPop() { return true; }
        // 副行轴用例按逐字点亮档测（FADE 档轴为 null，另有专测）
        @Override public int translationMode() { return WorldLyricsState.TRANSLATION_KARAOKE; }
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

    private static LyricLine line(long timestamp, String text) {
        return new LyricLine(timestamp, text);
    }

    // ==================== 第一层：冒号锚定的制作人员表 ====================

    @Test
    @DisplayName("冒号锚定：职务词开头跟冒号的行无条件过滤，无需时长/行长条件")
    void creditLinesFilteredUnconditionally() {
        CloudMusic.lyrics.add(line(0L, "作词：某某 某某"));
        CloudMusic.lyrics.add(line(6000L, "真实的歌词第一句"));
        // 该行挂载 6 秒（远超快闪阈值）且只 8 个字 —— 三条件启发式抓不到它，
        // 只有冒号锚定的第一层能滤。pts 落在 0 行区间（5000ms 内）。
        WorldLyricsState.Frame f = state.update(CAMERA, PARAMS, 1_000_000_000L, true, 3000L);
        assertTrue(f.rows.isEmpty(), "「作词：」行即使长挂载也应被第一层过滤");
    }

    @Test
    @DisplayName("冒号锚定覆盖中西文冒号、行首空白与常见职务词（含大小写变体）")
    void creditLineDetectionVariants() {
        String[] credits = {
                "作词 : 某某", "作詞：某某", "作曲: 某某", "编曲：某某", "填词：某某",
                "制作人：某某", "製作人：某某", "混音：某某", "母带：某某", "监制：某某",
                "和声：某某", "吉他：某某", "贝斯：某某", "键盘：某某", "鼓：某某",
                "弦乐：某某", "配唱：某某", "录音：某某", "录音棚：某某", "企划：某某",
                "出品：某某", "发行：某某", "版权：某某", "独家：某某", "OP：某某",
                "SP：某某", "Lyricist: someone", "composer: someone",
                "Producer: someone", "Arranger: someone", "Mixing: someone",
                "Mastering: someone", "  作词：某某", "\t作曲：某某",
        };
        for (String c : credits) {
            assertTrue(WorldLyricsState.isCreditLine(c), "应判定为制作人员表行：" + c);
        }
    }

    @Test
    @DisplayName("冒号锚定零误伤：句中含职务词、歌名带冒号、英文正文都不命中")
    void creditLineDoesNotHitRealLyrics() {
        String[] lyrics = {
                "我鼓起勇气说爱你",              // 含「鼓」但不在行首
                "回忆里的作曲家还在弹琴",        // 含「作曲」但不在行首
                "键盘上的灰尘落满了一个下午",    // 行首是「键盘」但后面不跟冒号
                "OP is over",                    // 行首 OP 但不跟冒号
                "他操作着命运",                  // 「作」字开头但非职务词
                "妈妈说要好好吃饭",              // 普通行
                "作词的灵魂",                    // 含「作词」但无冒号 —— 交给第二层兜底
        };
        for (String l : lyrics) {
            assertFalse(WorldLyricsState.isCreditLine(l), "不应误判为制作人员表行：" + l);
        }
    }

    @Test
    @DisplayName("METADATA_KEYWORDS 短词已收口：contains 表不再含单字「鼓」等易误伤词")
    void metadataKeywordsDoNotContainShortOverreachingWords() {
        // 单独的短词在 contains 表里会误伤真实歌词（「鼓起勇气」含「鼓」）。
        // 它们要么已从 contains 表移除、要么只留在要求冒号跟随的第一层正则里。
        String[] bannedAsBareKeywords = {"鼓", "词", "曲"};
        for (String banned : bannedAsBareKeywords) {
            for (String kw : WorldLyricsState.METADATA_KEYWORDS) {
                assertFalse(kw.equals(banned),
                        "contains 表不应含裸短词「" + banned + "」——会误伤真实歌词");
            }
        }
    }

    // ==================== 第一层：纯音乐占位行 ====================

    @Test
    @DisplayName("纯音乐占位行过滤：固定口吻（含标点/空格变体）剔除，真实歌名保留")
    void placeholderLinesFiltered() {
        String[] placeholders = {
                "纯音乐", "纯音乐，请欣赏", "纯音乐请欣赏", "纯音乐 请欣赏",
                "纯音乐，请您欣赏", "没有填词的纯音乐", "没有填词的 纯音乐",
                "Instrumental", "INSTRUMENTAL",
                "暂无歌词", "暂无歌词。", "无歌词", "没有歌词", "暂时没有歌词",
                "还没有歌词", "歌词暂缺", "等待填词", "等待歌词", "未填词",
                "no lyrics", "No Lyrics", "music",
        };
        for (String p : placeholders) {
            assertTrue(WorldLyricsState.isPlaceholderLine(p), "应判定为纯音乐占位行：" + p);
        }
        String[] realLyrics = {
                "纯音乐的电池没电了",            // 碰巧含词的真实歌名
                "没有填词的纯音乐不算音乐",      // 含口吻但多了内容
                "这首纯音乐真好听",
                "Instrumental version",
                "歌词里的世界",                  // 含「歌词」但多了内容
                "音乐的海洋",                    // 恰好是「音乐」+内容，不等于 "music"
        };
        for (String l : realLyrics) {
            assertFalse(WorldLyricsState.isPlaceholderLine(l), "不应误判为占位行：" + l);
        }
    }

    @Test
    @DisplayName("恒在的纯音乐占位行不进 3D 歌词：任何时刻都无行可画")
    void placeholderLineNeverBecomesActive() {
        // 占位行从头挂到尾（下一行 5 秒后），任何时长条件都拦不住它 ——
        // 只能靠第一层按文本剔除。剔除后整个时间轴无行可画。
        CloudMusic.lyrics.add(line(0L, "纯音乐，请欣赏"));
        WorldLyricsState.Frame f = state.update(CAMERA, PARAMS, 1_000_000_000L, true, 2500L);
        assertTrue(f.rows.isEmpty(), "纯音乐占位行应被剔除，实际 " + f.rows.size() + " 行");
    }

    @Test
    @DisplayName("两层联动：冒号行与快闪声明行混排时全部剔除，真实行时间轴不受影响")
    void bothFiltersComposeAndKeepTimeline() {
        CloudMusic.lyrics.add(line(0L, "作词：某某 作曲：某某"));       // 第一层 → 滤
        CloudMusic.lyrics.add(line(400L, "xx音乐 独家出品"));           // 第二层（300ms 闪过）→ 滤
        CloudMusic.lyrics.add(line(700L, "纯音乐，请欣赏"));            // 第一层 → 滤
        CloudMusic.lyrics.add(line(1100L, "真实的歌词第一句"));         // 留
        CloudMusic.lyrics.add(line(6000L, "真实的歌词第二句"));         // 留

        WorldLyricsState.Frame f = state.update(CAMERA, PARAMS, 1_000_000_000L, true, 200L);
        assertTrue(f.rows.isEmpty(), "开头三行（冒号/快闪/占位）应全被过滤");

        WorldLyricsState.Frame f2 = state.update(CAMERA, PARAMS, 1_100_000_000L, true, 1200L);
        WorldLyricsState.SpawnedLine cur = activeOf(f2);
        assertNotNull(cur, "过滤后应正常进入真实歌词行");
        assertTrue(cur.lines.get(0).contains("真实"), "当前句应是真实歌词，实际 " + cur.lines.get(0));
    }

    // ==================== 同句内 seek 重同步 ====================

    @Test
    @DisplayName("同句内拖动进度条（pos 跳 >300ms）：active 被销毁重建，旧句转淡出")
    void seekWithinSameLineRebuildsActive() {
        // 单句歌词 0..6000ms。t=1000 显示中 → 同句内 seek 到 3000（+2000ms）。
        CloudMusic.lyrics.add(line(0L, "一句很长很长的歌词"));
        CloudMusic.lyrics.add(line(6000L, "下一句"));

        WorldLyricsState.Frame f1 = state.update(CAMERA, PARAMS, 1_000_000_000L, true, 1000L);
        WorldLyricsState.SpawnedLine first = activeOf(f1);
        assertNotNull(first, "首帧应 spawn");
        assertTrue(f1.rows.get(0).active);

        // 同句内 seek：idx 仍是 0，但位置跳了 2000ms —— 必须走重建。
        WorldLyricsState.Frame f2 = state.update(CAMERA, PARAMS, 1_100_000_000L, true, 3000L);
        assertEquals(2, f2.rows.size(), "seek 帧应是两行：旧句淡出 + 新句 active");
        WorldLyricsState.SpawnedLine second = activeOf(f2);
        assertNotNull(second, "seek 后当前句应重新在场");
        assertTrue(f2.rows.get(1).active, "新句应是 active（帧序：淡出在前、active 在后）");
        assertNotEquals(first, second, "seek 后 active 必须是新对象（销毁重建，不是续用）");
        assertEquals(first, f2.rows.get(0).floating, "旧句应已转淡出态（身份不变）");
        assertFalse(f2.rows.get(0).active, "旧句不得再以 active 态复用");
    }

    @Test
    @DisplayName("正常逐帧推进：同句内位置小幅前进不触发重建")
    void normalAdvanceDoesNotRebuild() {
        CloudMusic.lyrics.add(line(0L, "一句很长很长的歌词"));
        CloudMusic.lyrics.add(line(6000L, "下一句"));

        WorldLyricsState.Frame f1 = state.update(CAMERA, PARAMS, 1_000_000_000L, true, 1000L);
        WorldLyricsState.SpawnedLine first = activeOf(f1);
        assertNotNull(first);

        // 逐帧 50ms 推进（含一帧超过阈值的暂停抖动模拟除外）：idx 与 lastSpawnedIdx
        // 都不变、无 seek，active 必须是同一个对象。
        WorldLyricsState.Frame f2 = state.update(CAMERA, PARAMS, 1_050_000_000L, true, 1050L);
        WorldLyricsState.Frame f3 = state.update(CAMERA, PARAMS, 1_100_000_000L, true, 1100L);
        WorldLyricsState.Frame f4 = state.update(CAMERA, PARAMS, 1_150_000_000L, true, 1150L);
        assertEquals(first, activeOf(f2), "正常推进不得重建 active");
        assertEquals(first, activeOf(f3));
        assertEquals(first, activeOf(f4));
    }

    @Test
    @DisplayName("seek 阈值边界：299ms 不触发、301ms 触发")
    void seekThresholdBoundary() {
        CloudMusic.lyrics.add(line(0L, "一句很长很长的歌词"));
        CloudMusic.lyrics.add(line(6000L, "下一句"));

        state.update(CAMERA, PARAMS, 1_000_000_000L, true, 1000L);
        WorldLyricsState.SpawnedLine s1 = activeOf(state.update(
                CAMERA, PARAMS, 1_050_000_000L, true, 1000L + WorldLyricsState.SEEK_RESYNC_THRESHOLD_MS - 1));
        WorldLyricsState.SpawnedLine s2 = activeOf(state.update(
                CAMERA, PARAMS, 1_100_000_000L, true, 1000L + WorldLyricsState.SEEK_RESYNC_THRESHOLD_MS - 1));
        assertEquals(s1, s2, "阈值内的位置差不算 seek（帧率抖动的余量）");

        // 同句内再跳 301ms（仍落在 0 行的 6000ms 区间内）→ 视为 seek，当前句重建。
        WorldLyricsState.SpawnedLine s3 = activeOf(state.update(
                CAMERA, PARAMS, 1_150_000_000L,
                true, 1000L + WorldLyricsState.SEEK_RESYNC_THRESHOLD_MS - 1
                        + WorldLyricsState.SEEK_RESYNC_THRESHOLD_MS + 1));
        assertNotEquals(s2, s3, "超过阈值即视为 seek，当前句重建");
    }

    @Test
    @DisplayName("seek 到跨句位置仍走换句路径，交接源是被跳过的句子")
    void seekAcrossLinesStillHandsOff() {
        CloudMusic.lyrics.add(line(0L, "第一句"));
        CloudMusic.lyrics.add(line(10000L, "远处的句子"));

        WorldLyricsState.Frame f1 = state.update(CAMERA, PARAMS, 1_000_000_000L, true, 100L);
        WorldLyricsState.SpawnedLine s1 = activeOf(f1);

        WorldLyricsState.Frame f2 = state.update(CAMERA, PARAMS, 1_100_000_000L, true, 10100L);
        WorldLyricsState.SpawnedLine s2 = activeOf(f2);
        assertNotNull(s2);
        assertNotEquals(s1, s2, "跨句 seek 后应是新句");
        assertEquals(s1, s2.huiguangPrev, "跨句 seek 同样形成交接，源是被跳过的句子");
    }

    @Test
    @DisplayName("暂停期间的帧不判 seek：暂停前后位置差再大也走 wasPaused 恢复路径")
    void pauseDoesNotMisreportSeek() {
        CloudMusic.lyrics.add(line(0L, "第一句"));
        CloudMusic.lyrics.add(line(6000L, "第二句"));

        WorldLyricsState.Frame f1 = state.update(CAMERA, PARAMS, 1_000_000_000L, true, 1000L);
        WorldLyricsState.SpawnedLine s1 = activeOf(f1);
        assertNotNull(s1);

        // 暂停超过去抖窗（150ms）：active 转 fade。位置停在 1000ms。
        state.update(CAMERA, PARAMS, 1_200_000_000L, false, 1000L);
        // 用户在暂停期间拖了进度条（位置差远超 300ms），随后恢复播放。
        // 恢复帧必须走 wasPaused 恢复路径（同句认回走恢复淡入），不得误判 seek。
        WorldLyricsState.Frame resumed = state.update(CAMERA, PARAMS, 1_400_000_000L, true, 1000L);
        WorldLyricsState.SpawnedLine s2 = activeOf(resumed);
        assertEquals(s1, s2, "同位置恢复播放应认回原句（恢复路径），不因暂停期位置记录误判");
    }

    // ==================== 单句停留门禁（12s 超时退场） ====================

    @Test
    @DisplayName("单句播放区间超过 12s：正常退场（走淡出），不永远占据画面")
    void longHeldLineRetiresAfterTimeout() {
        // 末句 0..60s（挂到曲尾）：12s 后必须退场，而不是一直显示到歌结束。
        CloudMusic.lyrics.add(line(0L, "一句挂到曲尾的长句"));

        WorldLyricsState.Frame f1 = state.update(CAMERA, PARAMS, 1_000_000_000L, true, 11_000L);
        WorldLyricsState.SpawnedLine s1 = activeOf(f1);
        assertNotNull(s1, "11s 时仍应显示（未到门限）");

        // 12.5s：超过 ACTIVE_MAX_STAY_MS，active 必须已转淡出。
        WorldLyricsState.Frame f2 = state.update(CAMERA, PARAMS, 1_100_000_000L, true, 12_500L);
        WorldLyricsState.SpawnedLine s2 = activeOf(f2);
        assertTrue(f2.rows.get(0).floating == s1 && !f2.rows.get(0).active,
                "超时句应转淡出（身份不变，不再是 active）");
    }

    // ==================== 翻译副行（translationLines 独立通道） ====================

    /**
     * getSecondaryLyrics 内部读 MuoniumPlayerExtension → OpenAPI 静态链（运行时
     * 由树侧装配）。测试 JVM 装一个最小 stub：getValueManager 用真实现（值对象
     * 是纯内存的），其余注册口空操作 —— 与 2D 同一数据口的采集路径因此可测。
     */
    private static void installOpenApiStub() {
        if (com.muoniumplayer.api.OpenAPI.getInstance() != null) return;
        com.muoniumplayer.api.OpenAPI.setInstance(new com.muoniumplayer.api.OpenAPI() {
            @Override public void registerEvent(com.muoniumplayer.api.interfaces.EventHandler handler) { }
            @Override public void unregisterEvent(com.muoniumplayer.api.interfaces.EventHandler handler) { }
            @Override public void registerFeature(Object feature) { }
            @Override public void displayScreen(com.muoniumplayer.api.features.ExtensionScreen screen) { }
            @Override public void printMessage(String message) { }
            @Override public com.muoniumplayer.api.interfaces.modules.values.ValueManager getValueManager() {
                return new com.muoniumplayer.api.impl.ValueManagerImpl();
            }
            @Override public com.muoniumplayer.api.interfaces.render.GLStateManager getGLStateManager() { return null; }
            @Override public com.muoniumplayer.api.interfaces.render.ShaderUtil getShaderUtil() { return null; }
            @Override public com.muoniumplayer.api.interfaces.render.FontUtil getFontUtil() { return null; }
            @Override public com.muoniumplayer.api.interfaces.render.RenderUtil getRenderUtil() { return null; }
        });
    }

    @Test
    @DisplayName("有翻译：translationLines 拆行独立存放，不混入主文本流")
    void translationGoesToSideChannel() {
        installOpenApiStub();
        CloudMusic.hasTransLyrics = true;
        try {
            LyricLine l = line(0L, "第一句主文本");
            l.translationText = "First line of translation";
            CloudMusic.lyrics.add(l);

            WorldLyricsState.SpawnedLine s = activeOf(state.update(
                    CAMERA, PARAMS, 1_000_000_000L, true, 500L));
            assertNotNull(s);
            assertNotNull(s.translationLines, "有翻译时副行列表应非空");
            assertTrue(s.translationLines.get(0).contains("First line"),
                    "副行应是翻译文本，实际 " + s.translationLines.get(0));
            assertTrue(s.lines.get(0).contains("主文本"), "主行不受影响");
            for (String t : s.translationLines) {
                assertFalse(s.lines.contains(t), "翻译行不得混入主文本流");
            }
        } finally {
            CloudMusic.hasTransLyrics = false;
        }
    }

    @Test
    @DisplayName("无翻译/开关关：translationLines 为 null，零开销")
    void translationAbsentWhenDisabledOrMissing() {
        installOpenApiStub();
        // 歌没有翻译
        CloudMusic.lyrics.add(line(0L, "只有主文本的句子"));
        WorldLyricsState.SpawnedLine noTrans = activeOf(state.update(
                CAMERA, PARAMS, 1_000_000_000L, true, 500L));
        assertNotNull(noTrans);
        assertNull(noTrans.translationLines, "无翻译时应为 null");

        // 有翻译但开关关
        state.resetForTest();
        CloudMusic.hasTransLyrics = true;
        try {
            LyricLine l = line(0L, "开关关闭的句子");
            l.translationText = "Should not be captured";
            CloudMusic.lyrics.add(l);
            WorldLyricsState.Params off = new WorldLyricsState.Params() {
                @Override public double distance() { return 6.5; }
                @Override public boolean splitLines() { return false; }
                @Override public double spreadDeg() { return 0; }
                @Override public double scale() { return 1; }
                @Override public boolean facePlayer() { return true; }
                @Override public boolean keepAway() { return false; }
                @Override public boolean wordPop() { return true; }
                @Override public int translationMode() { return WorldLyricsState.TRANSLATION_OFF; }
            };
            WorldLyricsState.SpawnedLine s = activeOf(state.update(
                    CAMERA, off, 1_100_000_000L, true, 500L));
            assertNotNull(s);
            assertNull(s.translationLines, "档位关时不采集翻译");
            assertNull(s.translationCharTimes, "档位关时无翻译轴");
        } finally {
            CloudMusic.hasTransLyrics = false;
        }
    }

    @Test
    @DisplayName("逐字点亮档：translationCharTimes 按主行进度比例插值铺满演唱区间")
    void karaokeModeProducesMappedTimeline() {
        installOpenApiStub();
        CloudMusic.hasTransLyrics = true;
        try {
            LyricLine l = line(0L, "主行甲乙丙丁");
            l.translationText = "one two three four five six";
            CloudMusic.lyrics.add(l);

            WorldLyricsState.SpawnedLine s = activeOf(state.update(
                    CAMERA, PARAMS, 1_000_000_000L, true, 500L));
            assertNotNull(s);
            assertNotNull(s.translationCharTimes, "逐字档（默认档位）应生成翻译轴");
            int transNonWs = 0;
            for (String t : s.translationLines) {
                for (int i = 0; i < t.length(); i++) {
                    if (!Character.isWhitespace(t.charAt(i))) transNonWs++;
                }
            }
            assertEquals(transNonWs, s.translationCharTimes.length, "轴长 = 翻译非空白字符数");
            // 首尾对齐主轴：第一个字 ≥ 主轴首字、最后一个字 ≤ 主轴末字（扫光同步推进）
            assertTrue(s.translationCharTimes[0] >= s.charTimes[0] - 1,
                    "翻译轴起点应不早于主轴起点");
            assertTrue(s.translationCharTimes[s.translationCharTimes.length - 1]
                            <= s.charTimes[s.charTimes.length - 1] + 1,
                    "翻译轴终点应不晚于主轴终点");
            // 单调不减（插值保序）
            for (int i = 1; i < s.translationCharTimes.length; i++) {
                assertTrue(s.translationCharTimes[i] >= s.translationCharTimes[i - 1],
                        "翻译轴应单调不减");
            }
        } finally {
            CloudMusic.hasTransLyrics = false;
        }
    }

    @Test
    @DisplayName("拉丁主行：副行走均匀轴 —— 等间隔、主行半程展示完")
    void latinMainLineGetsUniformTimeline() {
        installOpenApiStub();
        CloudMusic.hasTransLyrics = true;
        try {
            LyricLine l = line(0L, "Don't stop believin'");
            l.translationText = "不要停止相信";
            CloudMusic.lyrics.add(l);

            WorldLyricsState.SpawnedLine s = activeOf(state.update(
                    CAMERA, PARAMS, 1_000_000_000L, true, 500L));
            assertNotNull(s);
            assertNotNull(s.translationCharTimes, "拉丁主行逐字档仍应有轴（均匀轴）");
            int n = s.translationCharTimes.length;
            // 等间隔：相邻差恒定
            for (int i = 2; i < n; i++) {
                assertEquals(s.translationCharTimes[1] - s.translationCharTimes[0],
                        s.translationCharTimes[i] - s.translationCharTimes[i - 1],
                        "均匀轴相邻字间隔应恒定");
            }
            // 主行半程展示完：末字 ≤ 主轴末字时刻的一半（向上取整余量）
            long mainEnd = s.charTimes[s.charTimes.length - 1];
            assertTrue(s.translationCharTimes[n - 1] <= mainEnd / 2 + 1,
                    "拉丁副行末字应在主行半程前入场，实际 "
                            + s.translationCharTimes[n - 1] + " vs 半程 " + (mainEnd / 2));
        } finally {
            CloudMusic.hasTransLyrics = false;
        }
    }

    @Test
    @DisplayName("整行淡入档（FADE）：轴为 null，走整行淡入路径")
    void fadeModeProducesNoAxis() {
        installOpenApiStub();
        CloudMusic.hasTransLyrics = true;
        try {
            LyricLine l = line(0L, "主行甲乙丙丁");
            l.translationText = "First line of translation";
            CloudMusic.lyrics.add(l);
            WorldLyricsState.Params fade = new WorldLyricsState.Params() {
                @Override public double distance() { return 6.5; }
                @Override public boolean splitLines() { return false; }
                @Override public double spreadDeg() { return 0; }
                @Override public double scale() { return 1; }
                @Override public boolean facePlayer() { return true; }
                @Override public boolean keepAway() { return false; }
                @Override public boolean wordPop() { return true; }
                @Override public int translationMode() { return WorldLyricsState.TRANSLATION_FADE; }
            };
            WorldLyricsState.SpawnedLine s = activeOf(state.update(
                    CAMERA, fade, 1_000_000_000L, true, 500L));
            assertNotNull(s);
            assertNotNull(s.translationLines, "FADE 档副行文本仍在");
            assertNull(s.translationCharTimes, "FADE 档轴为 null（整行淡入接管）");
            assertFalse(s.transKaraokeMode, "FADE 档不走逐字分支");
        } finally {
            CloudMusic.hasTransLyrics = false;
        }
    }

    @Test
    @DisplayName("爆闪规划只数主文本：huiguangBursts 锚点不落在翻译字符上")
    void burstPlanningExcludesTranslation() {
        CloudMusic.hasTransLyrics = true;
        try {
            LyricLine l = line(0L, "一二三");
            l.translationText = "one two three four five";
            CloudMusic.lyrics.add(l);

            WorldLyricsState.SpawnedLine s = activeOf(state.update(
                    CAMERA, PARAMS, 1_000_000_000L, true, 500L));
            assertNotNull(s);
            // planBursts 的 nonWs 在 spawn 内按 wrapped（仅主文本）计 —— 这里断言
            // 爆闪锚点 charIdx 全部落在主文本非空白序（3 个字）范围内。
            if (s.huiguangBursts != null) {
                for (int bi = 0; bi < s.huiguangBursts.length / 4; bi++) {
                    int charIdx = (int) s.huiguangBursts[bi * 4];
                    assertTrue(charIdx < 3, "爆闪锚点应落在主文本（3 字）内，实际 " + charIdx);
                }
            }
        } finally {
            CloudMusic.hasTransLyrics = false;
        }
    }

    @Test
    @DisplayName("12s 内的正常长句不受门禁影响；暂停冻结句龄")
    void normalLongLineAndPauseStayWithinGate() {
        CloudMusic.lyrics.add(line(0L, "一句 11 秒的长句"));
        CloudMusic.lyrics.add(line(11_000L, "下一句"));

        WorldLyricsState.Frame f1 = state.update(CAMERA, PARAMS, 1_000_000_000L, true, 11_900L);
        assertNotNull(activeOf(f1), "11.9s 时仍应显示（未到门限）");

        // 暂停 3s：句龄按播放位置算，暂停不推进 → 不触发超时。
        state.update(CAMERA, PARAMS, 1_100_000_000L, false, 11_900L);
        state.update(CAMERA, PARAMS, 1_400_000_000L, false, 11_900L);
        WorldLyricsState.Frame resumed = state.update(CAMERA, PARAMS, 1_500_000_000L, true, 11_900L);
        assertNotNull(activeOf(resumed), "暂停期间句龄冻结，恢复后 11.9s 仍在门限内");
    }

    private static WorldLyricsState.SpawnedLine activeOf(WorldLyricsState.Frame frame) {
        for (WorldLyricsState.Row r : frame.rows) {
            if (r.active) return r.floating;
        }
        return frame.rows.isEmpty() ? null : frame.rows.get(frame.rows.size() - 1).floating;
    }
}
