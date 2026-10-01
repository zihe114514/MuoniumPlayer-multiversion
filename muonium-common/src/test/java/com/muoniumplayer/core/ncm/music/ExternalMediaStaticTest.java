package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.settings.HudConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 系统播放器镜像（SMTC）里不依赖 Minecraft 运行期的那部分判据。
 *
 * <p>覆盖三样：助手那一行 JSON 怎么解成快照（解错一个字段，进度与歌词就整体错位）、
 * 外部曲名歌手到曲库的采纳判据（时长那一关是整个功能不唱错词的唯一保险）、
 * 以及模式开关的读写一致性。真正的取数（起子进程、SMTC 往返）与联网检索留给真机。</p>
 */
public class ExternalMediaStaticTest {

    private static final String PLAYING_LINE = "{\"event\":\"state\",\"session\":true,"
            + "\"app\":\"Chrome!Chrome\",\"title\":\"晴天\",\"artist\":\"周杰伦\","
            + "\"album\":\"叶惠美\",\"posMs\":12345,\"durMs\":269000,\"state\":\"Playing\","
            + "\"caps\":{\"play\":false,\"pause\":true,\"toggle\":true,"
            + "\"next\":false,\"prev\":false,\"seek\":true}}";

    // ── 快照解析 ────────────────────────────────────────────────────────────────

    @Test
    public void parsesAFullPlayingSnapshot() {
        ExternalSnapshot snapshot = ExternalSnapshot.parseState(PLAYING_LINE, 1_000L);

        assertTrue(snapshot.hasSession());
        assertTrue(snapshot.isPlaying());
        assertEquals("晴天", snapshot.title());
        assertEquals("周杰伦", snapshot.artist());
        assertEquals(12_345L, snapshot.positionMs());
        assertEquals(269_000L, snapshot.durationMs());
        assertTrue(snapshot.canPause(), "在播时能力位是 pause 可用");
        assertFalse(snapshot.canPlay(), "在播时 play 那一位应当是关的，界面据此决定图标");
        assertFalse(snapshot.canNext());
        assertTrue(snapshot.canSeek());
    }

    @Test
    public void noSessionMeansNoSessionAndNotAFakeTimeline() {
        // 助手在没会话时只回这一行。解成 hasSession()==false 是唯一正确答案：
        // 任何"看起来也能播"的替代值都会让玩家看到在滚却根本没读到的假歌词。
        ExternalSnapshot snapshot = ExternalSnapshot.parseState(
                "{\"event\":\"state\",\"session\":false}", 5_000L);

        assertFalse(snapshot.hasSession());
        assertFalse(snapshot.isPlaying());
        assertEquals(0L, snapshot.positionMs());
        assertEquals("", snapshot.title());
    }

    @Test
    public void malformedLinesDegradeToNothingInsteadOfThrowing() {
        // 助手输出经子进程管道而来，截断与乱码都可能出现；这里绝不能把异常抛进 tick。
        assertFalse(ExternalSnapshot.parseState("not json at all", 1L).hasSession());
        assertFalse(ExternalSnapshot.parseState("", 1L).hasSession());
        assertFalse(ExternalSnapshot.parseState("{}", 1L).hasSession());
        assertFalse(ExternalSnapshot.parseState("{\"event\":\"state\"}", 1L).hasSession());
        assertFalse(ExternalSnapshot.parseState(null, 1L).hasSession());
    }

    @Test
    public void missingCapabilityBlockDisablesEveryCommand() {
        // 老版本助手或助手被半路 kill 时可能回一条不带 caps 的状态：宁可全部不让点，
        // 也不要发一条外部播放器多半不接受的命令。
        ExternalSnapshot snapshot = ExternalSnapshot.parseState(
                "{\"event\":\"state\",\"session\":true,\"title\":\"x\",\"state\":\"Playing\"}", 1L);

        assertTrue(snapshot.hasSession());
        assertFalse(snapshot.canToggle());
        assertFalse(snapshot.canNext());
        assertFalse(snapshot.canSeek());
    }

    @Test
    public void onlyPlayingCountsAsPlaying() {
        assertFalse(stateWithStatus("Paused").isPlaying());
        assertFalse(stateWithStatus("Stopped").isPlaying());
        assertFalse(stateWithStatus("Changing").isPlaying());
        assertTrue(stateWithStatus("playing").isPlaying(), "大小写不该影响判定");
        assertTrue(stateWithStatus("Paused").isPlaying() == false
                && !stateWithStatus("Paused").isStopped(), "暂停不是停止：歌词要停在原行而不是清空");
        assertTrue(stateWithStatus("Stopped").isStopped());
    }

    @Test
    public void staleSnapshotsStopDrivingTheClock() {
        ExternalSnapshot snapshot = ExternalSnapshot.parseState(PLAYING_LINE, 10_000L);

        assertTrue(snapshot.isFresh(12_000L, 3_000L));
        assertFalse(snapshot.isFresh(60_000L, 3_000L), "助手 3 秒没回话就该按失联处理");
        assertFalse(ExternalSnapshot.NONE.isFresh(10_000L, 3_000L));
    }

    // ── 换歌判定 ────────────────────────────────────────────────────────────────

    @Test
    public void trackKeyIgnoresCaseAndSurroundingWhitespace() {
        assertEquals(snapshotOf("晴天", "周杰伦", "Chrome!Chrome").trackKey(),
                snapshotOf("  晴天  ", "周杰伦", "Chrome!Chrome").trackKey());
        assertEquals(snapshotOf("QING TIAN", "Jay", "Chrome!Chrome").trackKey(),
                snapshotOf("qing tian", "JAY", "Chrome!Chrome").trackKey());
    }

    @Test
    public void trackKeySeparatesTwoPlayersPlayingTheSameSong() {
        // 「Chrome 里这首」与「网易云客户端里这首」对用户是两件事：换播放器要重新走一次
        // 匹配与歌词提交，否则状态行还挂着上一个应用的名字。
        assertFalse(snapshotOf("晴天", "周杰伦", "Chrome!Chrome").trackKey()
                .equals(snapshotOf("晴天", "周杰伦", "NeteaseMusic!app").trackKey()));
    }

    // ── 曲库采纳判据 ────────────────────────────────────────────────────────────

    @Test
    public void durationMustAgreeWithinFifteenSeconds() {
        assertTrue(ExternalTrackMatcher.durationAgrees(269_000L, 269_000L));
        assertTrue(ExternalTrackMatcher.durationAgrees(269_000L, 262_000L));
        assertFalse(ExternalTrackMatcher.durationAgrees(269_000L, 240_000L),
                "差 29 秒多半是 Live / 剪辑版，那种词的时间轴跟正在播的这份不是一回事");
    }

    @Test
    public void missingExternalDurationFallsBackToTitleOnlyMatching() {
        // 少数播放器不填 timeline。没有时长这条硬证据时退回"曲名相同即可"，
        // 而不是要求相等 —— 否则这些播放器整个用不了。
        assertTrue(ExternalTrackMatcher.durationAgrees(269_000L, 0L));
        assertFalse(ExternalTrackMatcher.durationAgrees(0L, 269_000L),
                "曲库那条没时长、外部有时长时不采纳：没有可比的东西");
    }

    @Test
    public void primaryArtistTakesTheFirstNameOnly() {
        assertEquals("周杰伦", ExternalTrackMatcher.primaryArtist("周杰伦"));
        assertEquals("a", ExternalTrackMatcher.primaryArtist("a / b"));
        assertEquals("a", ExternalTrackMatcher.primaryArtist("a、b"));
        assertEquals("a", ExternalTrackMatcher.primaryArtist("a, b"));
        assertEquals("a", ExternalTrackMatcher.primaryArtist("a&b"));
        assertEquals("", ExternalTrackMatcher.primaryArtist(""));
    }

    // ── 应用名缩写 ──────────────────────────────────────────────────────────────

    @Test
    public void winrtAppIdsBecomeAReadableName() {
        // SMTC 的 SourceAppUserModelId 长这样，直接显示会吃掉整条状态行的宽度。
        assertEquals("Chrome", ExternalMediaService.appLabel("Chrome!Chrome"));
        assertEquals("CloudMusic", ExternalMediaService.appLabel(
                "{6D809377-6AF0-444B-8957-A311F677BC3E}\\Netease\\网易云音乐\\CloudMusic.exe"));
        assertEquals("QQMusic", ExternalMediaService.appLabel("C:\\Tencent\\QQMusic\\QQMusic.exe"));
        assertEquals("外部播放器", ExternalMediaService.appLabel(""));
        assertEquals("外部播放器", ExternalMediaService.appLabel(null));
    }

    @Test
    public void overlongAppNamesGetTruncatedNotWrapped() {
        String label = ExternalMediaService.appLabel("SomeVeryLongPlayerNameThatGoesOnForever.exe");
        assertTrue(label.length() <= 19, "状态行只有一行的宽度: " + label);
    }

    // ── 模式开关 ────────────────────────────────────────────────────────────────

    @Test
    public void mirrorOnlyEnablesOnWindows() {
        String previous = HudConfig.playbackFeed;
        try {
            HudConfig.playbackFeed = HudConfig.HUD_PLAYBACK_FEED_MIRROR;
            assertEquals(ExternalPlayback.supportsPlatform(), ExternalPlayback.enabled());
        } finally {
            HudConfig.playbackFeed = previous;
        }
    }

    @Test
    public void mirrorNeverImpliesInternalAudioIsRunning() {
        // 镜像不产生声音：没在接管时 hasPlaybackClock() 必须只跟着内部引擎走。
        // （接管中的那条真机验，这里验的是没接管时不误报。）
        assertFalse(ExternalPlayback.isMirroring());
    }

    // ── 外部播放器那张封面 ──────────────────────────────────────────────────────

    @Test
    public void stateLineWithoutArtFieldMeansNoExternalCover() {
        ExternalSnapshot snapshot = ExternalSnapshot.parseState(PLAYING_LINE, 1_000L);
        assertFalse(snapshot.hasArtwork(), "老版助手不报 art 字段，必须解成「没有封面」而不是报错");
        assertEquals("", snapshot.artFile());
    }

    @Test
    public void stateLineCarriesTheArtFileName() {
        ExternalSnapshot snapshot = ExternalSnapshot.parseState(
                lineWithArt("art-80dd9d5d452c454b.jpg"), 1_000L);
        assertTrue(snapshot.hasArtwork());
        assertEquals("art-80dd9d5d452c454b.jpg", snapshot.artFile());
    }

    @Test
    public void onlyTheHelpersOwnFileNamesAreAccepted() {
        // 这个名字是从外部进程的输出里来的，会拿去拼路径读文件，所以只收助手那一种写法。
        assertEquals("art-0123456789abcdef.png", ExternalSnapshot.safeArtFile("art-0123456789abcdef.png"));
        assertEquals("art-0123456789abcdef.bmp", ExternalSnapshot.safeArtFile("art-0123456789abcdef.bmp"));
        assertEquals("", ExternalSnapshot.safeArtFile("../../windows/win.ini"));
        assertEquals("", ExternalSnapshot.safeArtFile("..\\..\\evil.jpg"));
        assertEquals("", ExternalSnapshot.safeArtFile("art-0123456789abcdef.exe"));
        assertEquals("", ExternalSnapshot.safeArtFile("art-ZZZZZZZZZZZZZZZZ.png"));
        assertEquals("", ExternalSnapshot.safeArtFile("cover.png"));
        assertEquals("", ExternalSnapshot.safeArtFile(""));
        assertEquals("", ExternalSnapshot.safeArtFile(null));
        assertFalse(ExternalSnapshot.parseState(lineWithArt("../hud"), 1_000L).hasArtwork());
    }

    @Test
    public void externalCoverIsNeverOfferedWhileNotMirroring() {
        assertFalse(ExternalPlayback.isMirroring());
        assertFalse(ExternalArtwork.hasArtwork());
        assertNull(ExternalArtwork.coverLocation(null));
        assertNull(ExternalArtwork.blurredLocation(null));
        // 背景那条也一样：没在镜像时，CloudMusic 的分叉必须原样交出曲目自己的模糊封面位置。
        assertNull(CloudMusic.preferredBlurredCoverLocation(null));
    }

    @Test
    public void backgroundBlurKeepsDimensionsAndCopiesFirst() {
        // 高斯是就地写的，所以 blurCover 必须先复制一遍：调用方手里那张还要拿去做取色。
        java.awt.image.BufferedImage source = new java.awt.image.BufferedImage(
                64, 40, java.awt.image.BufferedImage.TYPE_INT_RGB);
        source.setRGB(32, 20, 0xFF00FF00);
        java.awt.image.BufferedImage blurred = MusicCoverService.blurCover(source);
        org.junit.jupiter.api.Assertions.assertNotNull(blurred);
        assertEquals(64, blurred.getWidth());
        assertEquals(40, blurred.getHeight());
        assertEquals(java.awt.image.BufferedImage.TYPE_INT_ARGB, blurred.getType());
        // TYPE_INT_RGB 的 getRGB 会把 alpha 补成不透明，所以这里比的是同一个 0xFF00FF00。
        assertEquals(0xFF00FF00, source.getRGB(32, 20), "原图不能被卷积改过");
        assertNull(MusicCoverService.blurCover(null));
    }

    @Test
    public void externalCoverBytesDecodeAndScaleDown() throws Exception {
        // 助手落的是播放器给的原图（实测 800x800 JPEG），这里验的是「解得出来 + 不会撑爆纹理」。
        byte[] png = stillImage(900, 900);
        java.awt.image.BufferedImage decoded = MusicCoverService.decodeStillCover(png);
        org.junit.jupiter.api.Assertions.assertNotNull(decoded, "ImageIO 认得自己写出去的图，才认得播放器给的图");
        assertTrue(decoded.getWidth() <= 512, "超过 512 要缩下来，动态封面那条链就是这个上限");
        assertNull(MusicCoverService.decodeStillCover(new byte[0]), "空字节不是封面，返回 null 让调用方退回曲库");
        assertNull(MusicCoverService.decodeStillCover(null));
    }

    @Test
    public void progressReadersAreSafeWithNothingPlaying() {
        // 进度条、时间标签与封面都改问 CloudMusic 这几个 Now/clock 方法了。
        // 没在放任何东西时它们必须安静地给 0 / false，而不是 NPE —— 这是渲染线程每帧都要走的路径。
        assertFalse(CloudMusic.hasPlaybackClock());
        assertFalse(CloudMusic.isAudibleNow());
        assertEquals(0L, CloudMusic.positionMsNow());
        assertEquals(0L, CloudMusic.durationMsNow());
    }

    // ── 时长后落地时给合成曲目一次重解析 ──────────────────────────────────────

    @Test
    public void syntheticTrackIsResolvedAgainWhenDurationSettles() {
        // 21:47 真机踩到的那条：浏览器头一拍报的是音频自己的 40 秒，等元数据读全才报 269 秒，
        // 而 trackKey 只看曲名+歌手，所以不放开这一条就会整首挂着「暂无歌词」。
        assertTrue(ExternalPlayback.needsSettleRetry(true, 0, 40_000L, 269_000L));
    }

    @Test
    public void matchedTrackIsNeverResolvedAgain() {
        assertFalse(ExternalPlayback.needsSettleRetry(false, 0, 40_000L, 269_000L));
    }

    @Test
    public void durationJitterBelowTheStepDoesNotRetrigger() {
        // 轮询本身有 ±几十毫秒的抖动，播放器暂停/续播也会微调时长；这些都不该再打一次网络。
        assertFalse(ExternalPlayback.needsSettleRetry(true, 0, 269_000L, 269_500L));
        assertTrue(ExternalPlayback.needsSettleRetry(true, 0, 269_000L, 262_000L));
    }

    @Test
    public void settleRetryBudgetIsCapped() {
        assertTrue(ExternalPlayback.needsSettleRetry(true, ExternalPlayback.SETTLE_RETRY_LIMIT - 1,
                40_000L, 269_000L));
        assertFalse(ExternalPlayback.needsSettleRetry(true, ExternalPlayback.SETTLE_RETRY_LIMIT,
                40_000L, 269_000L));
    }

    @Test
    public void unsetDurationIsNotTreatedAsAChange() {
        // 0 是「播放器没报时长」而不是「时长是 0」，两边任一为 0 都不构成重解析的理由。
        assertFalse(ExternalPlayback.needsSettleRetry(true, 0, 0L, 269_000L));
        assertFalse(ExternalPlayback.needsSettleRetry(true, 0, 269_000L, 0L));
    }

    // ── 外部播放器的音量（音量合成器会话，不是 SMTC） ──────────────────────────

    @Test
    public void volumeIsUnknownUntilTheHelperMatchesAProgram() {
        assertFalse(ExternalSnapshot.parseState(PLAYING_LINE, 1_000L).hasVolume(),
                "助手没报 volok 就是「不知道」，界面要回落到模组自己那格");

        ExternalSnapshot matched = ExternalSnapshot.parseState(lineWithVolume(
                ",\"volok\":true,\"vol\":0.8300,\"pid\":19520"), 1_000L);
        assertTrue(matched.hasVolume());
        assertEquals(0.83f, matched.volumeLevel(), .001f);
        assertEquals(19520, matched.volumePid());

        ExternalSnapshot unmatched = ExternalSnapshot.parseState(lineWithVolume(
                ",\"volok\":false"), 1_000L);
        assertFalse(unmatched.hasVolume(), "配不上那支程序时一个数字都不该给");
        assertEquals(-1f, unmatched.volumeLevel(), .001f);
    }

    @Test
    public void volumeOutsideZeroOneIsTreatedAsUnknown() {
        // 越界只能是助手或协议出了问题。当成「不知道」比当成 0 或 1 安全：
        // 后者会让滑块在界面上跳到底，玩家下次拖动就把外部播放器拧到另一个极端。
        assertFalse(ExternalSnapshot.parseState(lineWithVolume(
                ",\"volok\":true,\"vol\":1.5,\"pid\":7"), 1_000L).hasVolume());
        assertFalse(ExternalSnapshot.parseState(lineWithVolume(
                ",\"volok\":true,\"vol\":-3,\"pid\":7"), 1_000L).hasVolume());
    }

    @Test
    public void anUnparsableVolumeDoesNotCostTheRestOfTheSnapshot() {
        ExternalSnapshot snapshot = ExternalSnapshot.parseState(lineWithVolume(
                ",\"volok\":true,\"vol\":\"晴天\",\"pid\":7"), 1_000L);
        assertFalse(snapshot.hasVolume());
        assertEquals("晴天", snapshot.title(), "音量解不动，进度与曲名照旧要用");
        assertTrue(snapshot.isPlaying());
    }

    @Test
    public void aSecondOfDraggingIsMergedIntoAFewCommands() {
        // 拖动是每帧一次的，而一次写要跨进程枚举一遍会话；这里量一下合并的效果。
        long start = 1_000_000L;
        float sent = Float.NaN;
        long lastSend = start - ExternalPlayback.VOLUME_SEND_INTERVAL_MS;
        int commands = 0;
        for (int frame = 0; frame < 75; frame++) {
            long now = start + frame * 16L;               // 60fps，最后 15 帧停在 1.0 不动
            float wanted = Math.min(1f, frame / 59f);
            if (ExternalPlayback.shouldSendVolume(wanted, sent, now, lastSend)) {
                commands++;
                sent = wanted;
                lastSend = now;
            }
        }
        assertTrue(commands <= 14, "75 帧拖动被压成 " + commands + " 条命令，太多了");
        assertEquals(1f, sent, .001f, "最后一档必须发出去，不能只发拖到一半的值");
    }

    @Test
    public void nothingBeingDraggedSendsNothingAtAll() {
        assertFalse(ExternalPlayback.shouldSendVolume(Float.NaN, Float.NaN, 5_000L, 0L),
                "进镜像只读不写：没人在拖就一条命令都不该有");
        assertFalse(ExternalPlayback.shouldSendVolume(0.4f, 0.4f, 5_000L, 0L),
                "与已经发出去的是同一档，不必再写一遍");
        assertFalse(ExternalPlayback.shouldSendVolume(0.4f, Float.NaN, 50L, 0L),
                "离上一次发出去还不够久");
        assertTrue(ExternalPlayback.shouldSendVolume(0.4f, Float.NaN,
                ExternalPlayback.VOLUME_SEND_INTERVAL_MS, 0L));
    }

    @Test
    public void anEchoInsideTheDragGraceWindowIsIgnored() {
        long drag = 10_000L;
        assertFalse(ExternalPlayback.shouldAdoptEcho(true, 1f, 0.2f,
                drag + 300L, drag), "状态行是半秒一拍采的，那几拍带回来的还是写之前的旧值");
        assertTrue(ExternalPlayback.shouldAdoptEcho(true, 0.2f, 0.2f, drag + 10L, drag),
                "读数与玩家刚拖到的那档一致时没有理由不信");
        assertTrue(ExternalPlayback.shouldAdoptEcho(true, 0.55f, 0.2f,
                drag + ExternalPlayback.VOLUME_DRAG_GRACE_MS + 1L, drag),
                "过了窗口要采信，否则玩家在系统合成器里调音量模组永远跟不上");
        assertFalse(ExternalPlayback.shouldAdoptEcho(false, 0.5f, 0.2f, 1_000_000L, 0L),
                "助手没配上那支程序时，什么都不采信");
    }

    @Test
    public void theSliderFallsBackToTheModsOwnVolumeWhileNotMirroring() {
        assertEquals(0.35f, ExternalPlayback.displayedVolume(Float.NaN, Float.NaN, 0.35f), .001f);
        assertEquals(0.8f, ExternalPlayback.displayedVolume(Float.NaN, 0.8f, 0.35f), .001f);
        assertEquals(0.1f, ExternalPlayback.displayedVolume(0.1f, 0.8f, 0.35f), .001f,
                "正在拖的那一档优先，否则滑块不跟手");

        // 没在镜像时，写与读都必须原样交回内部那套 —— 音量的内部语义一行都不能变。
        assertFalse(ExternalPlayback.isMirroring());
        assertFalse(ExternalPlayback.writeVolume(0.5f));
        assertEquals(0.35f, ExternalPlayback.volumeOr(0.35f), .001f);
    }

    // ── 辅助 ───────────────────────────────────────────────────────────────────

    /** 在一条完整的状态行尾部接上音量那几个字段（助手就是这么做到的）。 */
    private static String lineWithVolume(String volumeFields) {
        return PLAYING_LINE.substring(0, PLAYING_LINE.length() - 1) + volumeFields + "}";
    }

    /** 现造一张纯色图当「播放器给的那张封面」，PNG 字节。 */
    private static byte[] stillImage(int width, int height) throws Exception {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        graphics.setColor(java.awt.Color.DARK_GRAY);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** 在一条完整的状态行上换掉 {@code art} 字段，用来验封面那条路。 */
    private static String lineWithArt(String artFile) {
        return "{\"event\":\"state\",\"session\":true,\"app\":\"Chrome!Chrome\","
                + "\"title\":\"晴天\",\"artist\":\"周杰伦\",\"album\":\"叶惠美\","
                + "\"posMs\":12345,\"durMs\":269000,\"state\":\"Playing\","
                + "\"caps\":{\"play\":false,\"pause\":true,\"toggle\":true,"
                + "\"next\":false,\"prev\":false,\"seek\":true},\"art\":\""
                + artFile.replace("\\", "\\\\") + "\"}";
    }

    private static ExternalSnapshot stateWithStatus(String status) {
        return ExternalSnapshot.parseState("{\"event\":\"state\",\"session\":true,"
                + "\"title\":\"x\",\"artist\":\"y\",\"posMs\":10,\"durMs\":100,\"state\":\""
                + status + "\"}", System.currentTimeMillis());
    }

    private static ExternalSnapshot snapshotOf(String title, String artist, String app) {
        String line = "{\"event\":\"state\",\"session\":true,\"app\":\"" + app
                + "\",\"title\":\"" + title + "\",\"artist\":\"" + artist
                + "\",\"album\":\"\",\"posMs\":1,\"durMs\":2,\"state\":\"Playing\","
                + "\"caps\":{\"play\":true,\"pause\":true,\"toggle\":true,"
                + "\"next\":true,\"prev\":true,\"seek\":true}}";
        return ExternalSnapshot.parseState(line, System.currentTimeMillis());
    }
}
