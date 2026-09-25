package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「下一首的音频预取」这条链的离线回归 —— YouTube 秒开靠的就是它。
 *
 * <p>保护的东西很具体：YouTube 的字节由一台出口 IP 的余额配给（约一秒攒一秒播放量），一首四分钟的
 * 歌最坏要等接近曲长才取完。这个等待本身消不掉，能挪走 —— 挪到「上一首还在响」的时候花掉。
 * 一旦这条链悄悄退回去（忘了让路给 automix、或者预取走了另一套缓存键），用户听到的就又变成
 * 点了歌之后十几秒没声音，而这不会抛任何异常。</p>
 *
 * <p>只读源码加一个纯判定函数，不启动 Minecraft、不碰音频设备、更不发 YouTube 请求。</p>
 */
class YouTubePrefetchStaticTest {

    private static final Path JAVA = ProjectPaths.sharedJava();

    // ════════════════════════ 谁值得提前取 ════════════════════════

    @Test
    @DisplayName("预取只挑 YouTube，其余源的直链是普通 CDN")
    void onlyYouTubeIsWorthPrefetching() {
        Music youtube = Music.fromYouTubeMusicTrack(new YouTubeMusicService.Track(
                "bx1Bh8ZvH84", "Oasis - Wonderwall", "Oasis", 259_000L, "http://x/hq.jpg"));
        assertTrue(CloudMusic.worthAudioPrefetch(youtube),
                "YouTube 恰恰是那个「现取要等接近曲长」的源，不预取它这条链就没有存在意义");
        assertFalse(CloudMusic.worthAudioPrefetch(neteaseSong()),
                "网易这类直链一两秒就落盘，提前取只是白占用户的上行与缓存");
        assertFalse(CloudMusic.worthAudioPrefetch(null), "空对象不许炸");
    }

    private static Music neteaseSong() {
        return new Music("测试曲名", "测试曲名", "", 1L,
                new java.util.ArrayList<com.muoniumplayer.core.ncm.music.dto.Artist>(),
                java.util.Collections.emptyList(),
                new com.muoniumplayer.core.ncm.music.dto.Album(1L, "测试专辑", "",
                        java.util.Collections.emptyList()),
                259_000L, 0L, 0L, java.util.Collections.emptyList());
    }

    // ════════════════════════ 让路给 automix 那条链 ════════════════════════

    @Test
    @DisplayName("逐首预备正在管这一首时绝不预取：两条链不该同时下一样的东西")
    void prefetchYieldsToAutomixArming() throws IOException {
        String gate = region(cloudMusic(),
                "private void maybePrefetchNextTrackAudio(AudioPlayer activePlayer)",
                "private void prefetchTrackAudio(");

        assertTrue(gate.contains("prefetchingAudio || arming || armed != null"),
                "arm 已经接下这一首（在跑或牌已备好）时再来一次预取，就是同一首歌下两遍、白扣那份余额");
        assertTrue(gate.contains("AutomixSettings.isEnabled() && armAttempts < MAX_ARM_ATTEMPTS"),
                "automix 开着时先让 arm 把它那三份预算试完 —— 让路要有序，不然两条链会同时抢同一首");
        assertTrue(gate.contains("if (audioPrefetchAttempts >= MAX_AUDIO_PREFETCH_ATTEMPTS) return;"),
                "没有尝试预算的话，一个坏掉的源会被 10ms 的监督 tick 反复敲");
        assertTrue(gate.contains("dontAdd || awaitingPlaybackAction || manualSkipRequested"),
                "用户正在手动切歌 / 在等他有操作时不预备，不然就是给一条马上要被换掉的队列白烧余额");
        assertTrue(gate.contains("cachedAudioFileFor(song) != null"),
                "磁盘上已经有了还去取 = 白扣那份 IP 的字节余额");
        assertTrue(gate.contains("worthAudioPrefetch(song)"), "挑哪首的判定必须走那个纯函数，别在门里再写一遍");
        assertTrue(gate.contains("BackgroundWork.submit("),
                "整首取字节加整首解码是后台最重的一类活，绝不该跟渲染线程争 CPU");
        assertFalse(gate.contains("new AudioPlayer("),
                "预取只负责「落到磁盘」；持有第二张播放牌是 arm 的活，两张牌会让常驻 PCM 翻倍");
    }

    @Test
    @DisplayName("调用点排在 arm 之后，且预算随每首新曲目重开")
    void callSiteRunsAfterArmingAndBudgetResetsPerTrack() throws IOException {
        String source = cloudMusic();
        int arm = source.indexOf("maybeArmNextTrack(activePlayer);");
        int prefetch = source.indexOf("maybePrefetchNextTrackAudio(activePlayer);");
        assertTrue(arm >= 0 && prefetch > arm,
                "顺序本身就是「谁该跑」的说明：arm 先看见这一首，预取只补它不管的场合");

        String reset = region(source, "private void resetArmState() {", "private void maybePrefetchPersonalFm(");
        assertTrue(reset.contains("audioPrefetchAttempts = 0;")
                        && reset.contains("nextAudioPrefetchAt = 0L;"),
                "预取预算要按「一首一份」重开，否则交接之后那一首轮永远轮不到预取");
    }

    // ════════════════════════ 缓存键必须与实播同一份 ════════════════════════

    @Test
    @DisplayName("预取走实播那同一个下载＋解码入口，且不打扰界面")
    void prefetchReusesTheRealPlayChain() throws IOException {
        String body = region(cloudMusic(),
                "private void prefetchTrackAudio(Music song", "private boolean prefetchStillValid(");

        assertTrue(body.contains("QUIET_DOWNLOAD.set(Boolean.TRUE);"),
                "后台取流不许把下载卡片和 downloading 打到正在播的那一首上");
        assertTrue(body.contains("QUIET_DOWNLOAD.remove();"), "置了位就要在 finally 里清掉");
        assertTrue(body.contains("getMusicFile(playUrl, song)"),
                "缓存键只有曲目加音质这一份实现：绕开 getMusicFile 自己拼文件名，实播就永远命中不上");
        assertTrue(body.contains("prefetchStillValid(expectedGeneration, expectedRevision)"),
                "用户随时可能把下一首选成别的，取到一半也要问一句还要不要");
        assertTrue(body.contains("prefetchingAudio = false;"),
                "忘记复位就等于整场会话只剩一次预取机会");
        assertFalse(body.contains("playSong(") || body.contains("curIdx"),
                "预取没有资格动播放与队列 —— 它和播放线程唯一的交集应该是磁盘");
    }

    @Test
    @DisplayName("预取失败只记一行，绝不上抛")
    void failureIsLoggedNotThrown() throws IOException {
        String body = region(cloudMusic(),
                "private void prefetchTrackAudio(Music song", "private boolean prefetchStillValid(");
        assertTrue(body.contains("catch (Throwable failure)"),
                "提前取没取到的正确处理是「等真轮到它时按实播再走一遍完整链路」");
        assertFalse(body.contains("throw "),
                "预取替这首歌判定「不能播」会跳过实播那侧的换源兜底，那才是真的把歌弄丢了");
    }

    @Test
    @DisplayName("预取的时间常数：开播 3 秒起有资格，让路那份由 arm 的尝试预算决定")
    void timingConstantsMatchTheMeasuredWait() throws IOException {
        String source = cloudMusic();
        assertEquals(3_000L, longLiteral(source, "AUDIO_PREFETCH_AFTER_MILLIS = "),
                "这一条只挡「开播第一秒就来抢」；automix 开着时实际下限是 arm 那三份预算用完（3 × 20 秒）");
        assertEquals(60_000L, longLiteral(source, "AUDIO_PREFETCH_RETRY_MILLIS = "),
                "预取一首可能跑几分钟，冷却给得比 arm 宽");
        assertEquals(2, (int) longLiteral(source, "MAX_AUDIO_PREFETCH_ATTEMPTS = "));
        assertEquals(20_000L, longLiteral(source, "ARM_RETRY_MILLIS = "),
                "让路窗口的长度就是从它推出来的，动它要一并想这条");
    }

    // ════════════════════════════════ 取数辅助 ════════════════════════════════

    private static String cloudMusic() throws IOException {
        return read(music("CloudMusic.java"));
    }

    private static Path music(String file) {
        return JAVA.resolve(Paths.get("com", "muoniumplayer", "core", "ncm", "music", file));
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String region(String source, String from, String to) {
        int start = source.indexOf(from);
        assertTrue(start >= 0, "找不到起点：" + from);
        int end = source.indexOf(to, start);
        assertTrue(end > start, "找不到终点：" + to + "（起点之后）");
        return source.substring(start, end);
    }

    /** 取 {@code marker} 后面那个整数字面量，写成 {@code = 3_000L} 也要认。 */
    private static long longLiteral(String source, String marker) {
        int at = source.indexOf(marker);
        assertTrue(at >= 0, "找不到常量：" + marker);
        int start = at + marker.length();
        int end = start;
        while (end < source.length() && (Character.isDigit(source.charAt(end)) || source.charAt(end) == '_')) {
            end++;
        }
        assertTrue(end > start, "常量后面没有数字：" + marker);
        return Long.parseLong(source.substring(start, end).replace("_", ""));
    }
}
