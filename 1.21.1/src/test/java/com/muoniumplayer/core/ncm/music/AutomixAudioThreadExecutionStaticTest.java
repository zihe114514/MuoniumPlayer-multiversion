package com.muoniumplayer.core.ncm.music;

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
 * The audio-thread execution path for the crossfade curves.
 *
 * <p>Half of this file is the usual source scan, because the interesting contracts are about
 * <em>where</em> code is and <em>what it must not call</em>: an audio thread may not push commands
 * onto JSyn's queue, and it may not clear a filter's state once per output buffer. The other half is
 * a real numerical check on {@link AutomixPlan}: the one race this design cannot fully close is a
 * frame that lands just after the blend is finalised, and the answer is only sound if every
 * incoming-deck curve has returned to neutral by then.</p>
 */
class AutomixAudioThreadExecutionStaticTest {

    private static final Path JAVA = ProjectPaths.sharedJava();

    /**
     * The finalize race, closed by arithmetic rather than by locking.
     *
     * <p>{@code driveAutomixFade()} clears {@code activeFade} and then flattens the incoming deck.
     * A frame the audio task was already inside can still land after that, so the plan's own end state
     * has to <em>be</em> the flattened state - otherwise a stray write could leave a bass cut or a
     * vocal cancellation stuck on the deck the listener keeps.</p>
     */
    @Test
    @DisplayName("斜坡终点的曲线全部回到中性，晚到的一帧不可能把进场 deck 掀平")
    void lastFrameOfTheRampIsIndistinguishableFromTheReset() {
        AutomixPlan[] plans = {
                plan(FoliaTransitionStyle.BASS_SWAP, .62f, 1.02f, -2.5f, 1.5f),
                plan(FoliaTransitionStyle.TAIL_RIDE, .62f, .96f, 3f, -3f),
                plan(FoliaTransitionStyle.PLAIN_BLEND, 0f, 1f, 0f, 0f),
                plan(null, .4f, .94f, -1f, 2f),
        };

        for (AutomixPlan plan : plans) {
            assertEquals(1f, plan.incomingGain(1.0), .0001f,
                    "进场增益必须回到 1，否则混响或音量偏移会留在曲子里");
            assertEquals(0f, plan.incomingBassDb(1.0), .0001f,
                    "低频 shelf 必须回到 0 dB，否则新曲会一直听起来发薄");
            assertEquals(0f, plan.incomingVocalDepth(1.0), .0001f,
                    "人声消除必须释放干净，否则歌会一直缺主唱");
            assertEquals(0f, plan.incomingMidDb(1.0), .0001f, "中频补偿必须归零");
            assertEquals(0f, plan.incomingHighDb(1.0), .0001f, "高频补偿必须归零");
        }
    }

    /** One curve writer, two clocks: a fallback frame must be the same shape as an audio-thread one. */
    @Test
    @DisplayName("曲线只有一份实现，tick 与音频任务都走它")
    void everyCurveWriteGoesThroughTheSharedFrameWriter() throws IOException {
        String cloud = read(music("CloudMusic.java"));

        assertEquals(2, count(cloud, "applyAutomixFrame("),
                "曲线写入必须只有 writeTransitionFrame 里那两处（进场 / 出场），多一处就是有了第二个写入者");

        String tick = body(cloud, "private void driveAutomixFade()");
        assertTrue(tick.contains("writeTransitionFrame(fade, progress)"),
                "tick 也必须走同一份实现，不能自己再写一遍曲线");
        // The tick may still flatten the incoming deck once the blend is over; what it may not do is
        // sample the plan itself, which is what makes it a second writer with a second clock.
        for (String direct : new String[]{"setFadeGain(fade.plan", "setBassGainDb(fade.plan",
                "setVocalDuckDepth(fade.plan", "setMidGainDb(fade.plan", "setHighGainDb(fade.plan",
                "setSpeedAndPitch("}) {
            assertFalse(tick.contains(direct),
                    "driveAutomixFade 不能再自己采样计划写 " + direct + "，否则两个写入者会给出不同的曲线");
        }
    }

    /** The higher-resolution writer exists only while there is something for it to write. */
    @Test
    @DisplayName("音频任务随一次淡化注册，两条结束路径都注销")
    void audioTaskLivesExactlyAsLongAsTheBlend() throws IOException {
        String cloud = read(music("CloudMusic.java"));

        assertTrue(body(cloud, "private void registerAutomixAudioTask()")
                        .contains("Engine.addAudioTask(automixAudioTask)"),
                "注册必须落到引擎的音频任务列表上");
        assertTrue(body(cloud, "private void unregisterAutomixAudioTask()")
                        .contains("Engine.removeAudioTask(automixAudioTask)"),
                "注销必须走对应的移除接口");
        assertTrue(cloud.contains("registerAutomixAudioTask();"),
                "淡化开始时必须注册");
        assertTrue(body(cloud, "private void driveAutomixFade()").contains("unregisterAutomixAudioTask();"),
                "自然跑完的那条路径必须注销，否则已结束的会话会被捕获引用一直持有");
        assertTrue(body(cloud, "private void finishAutomixFade()").contains("unregisterAutomixAudioTask();"),
                "提前结束（取消 / 手动切歌 / 会话拆除）那条路径也必须注销");
    }

    /**
     * Everything the audio thread reads has to be visible to it, and everything the tick reads back
     * has to be visible to the tick.
     */
    @Test
    @DisplayName("跨线程共享的淡化状态都是 volatile")
    void sharedFadeStateIsPublishedToBothThreads() throws IOException {
        String cloud = read(music("CloudMusic.java"));
        String fade = body(cloud, "private static final class AutomixFade {");

        assertTrue(fade.contains("private volatile long startedAt;"),
                "音频任务要采样 startedAt，没有 volatile 就可能一直读到旧值");
        assertTrue(fade.contains("private volatile double pausedProgress = -1.0;"),
                "暂停进度是 tick 写给音频任务的，必须是 volatile");
        assertTrue(fade.contains("private volatile long audioDrivenAt;"),
                "写入权交接靠这个字段，必须是 volatile");
        assertTrue(cloud.contains("private volatile AutomixFade activeFade;"),
                "淡化对象本身的发布也必须是 volatile");
    }

    /** High resolution may never turn a working transition into a stuck one. */
    @Test
    @DisplayName("音频任务不报数时 tick 自动收回写入权")
    void theTickTakesTheWritesBackWhenTheAudioThreadGoesQuiet() throws IOException {
        String cloud = read(music("CloudMusic.java"));
        String tick = body(cloud, "private void driveAutomixFade()");
        String task = body(cloud, "private void writeTransitionFrameOnAudioThread()");

        assertTrue(cloud.contains("private static final long AUDIO_DRIVEN_WINDOW_MILLIS = 40L;"),
                "兜底窗口应当是几个音频缓冲的量级");
        assertTrue(tick.contains("now - fade.audioDrivenAt >= AUDIO_DRIVEN_WINDOW_MILLIS"),
                "tick 必须显式检查音频任务的存活，而不是无条件让位");
        int write = task.indexOf("writeTransitionFrame(fade, progress);");
        int mark = task.indexOf("fade.audioDrivenAt = now;");
        assertTrue(write >= 0 && mark > write,
                "只有在写入成功之后才能登记这一帧，否则一次抛异常就等于永久让出了写入权");
        assertTrue(task.contains("catch (Throwable ignored)"),
                "音频线程上的异常必须被吞掉，绝不能把异常抛回合成线程");
        assertTrue(task.contains("fade.pausedProgress")
                        && task.contains("held >= 0.0 ? held : progressAt(fade, now)"),
                "暂停期间必须按住进度，不能让墙钟在两次 tick 之间继续走");
    }

    /** The whole reason the {@code *Now} family exists: port writes that do not queue a command. */
    @Test
    @DisplayName("音频线程的 setter 直写端口，不排 JSyn 命令")
    void audioThreadSettersWriteThePortDirectly() throws IOException {
        String soundFile = read(repackage("processing", "sound", "SoundFile.java"));

        assertTrue(body(soundFile, "public void setAmplitudeNow(float amp)")
                        .contains("this.player.amplitude.setValueInternal(amp)"),
                "setAmplitudeNow 必须走 setValueInternal；用 set() 的话命令要到下一个块才生效");
        assertTrue(body(soundFile, "public void setRateNow(float rate)")
                        .contains("this.player.rate.setValueInternal(this.sampleRate() * rate)"),
                "setRateNow 同样必须直写读头端口");
        String both = body(soundFile, "public boolean setRateAndStretchNow(float rate, float stretch)");
        assertTrue(both.contains("setRateNow(rate)") && both.contains("timeStretch(stretch)"),
                "速率与伸缩合成一个方法是为了固定顺序：读头先落到音高上，再让伸缩按解耦比值跑");
    }

    /**
     * The one thing the audio thread must not do to the chain.
     *
     * <p>{@code resetTo} / {@code resetDepthTo} / {@code resetToneTo} clear filter state, which is
     * only correct for a deck that has not started sounding yet. Running them per output buffer would
     * zero the biquad history hundreds of times a second and the band canceller would never
     * converge - and it would be inaudible in a unit test, which is exactly why it is pinned here.</p>
     */
    @Test
    @DisplayName("每帧发布目标值不清滤波器状态")
    void deckTargetsArePublishedWithoutClearingFilterState() throws IOException {
        String chain = read(repackage("processing", "sound", "AutomixDeckChain.java"));
        String targets = body(chain, "public void setTargetsNow(float bassDb, float duckDepth, float midDb, float highDb)");

        for (String target : new String[]{"gainDb(bassDb)", "duckDepth(duckDepth)",
                "midGainDb(midDb)", "highGainDb(highDb)"}) {
            assertTrue(targets.contains(target), "setTargetsNow 必须发布 " + target);
        }
        for (String reset : new String[]{"resetTo(", "resetDepthTo(", "resetToneTo("}) {
            assertFalse(targets.contains(reset),
                    "setTargetsNow 不得调用 " + reset + "：那是给还没出声的 deck 用的，每帧调用会把双二阶状态清零");
        }
    }

    /** The deck side of the handshake. */
    @Test
    @DisplayName("AudioPlayer 的音频线程帧用直写路径推四组参数与速度音高")
    void audioPlayerFrameUsesTheNonQueueingPath() throws IOException {
        String player = read(music("AudioPlayer.java"));
        String frame = body(player, "void applyAutomixFrame(float fade, float bassDb, float vocalDepth, float midDb, float highDb)");

        assertTrue(frame.contains("this.player.setAmplitudeNow(this.volume * this.fadeGain)"),
                "振幅必须直写，否则交叉淡化会晚一块生效");
        assertTrue(frame.contains("chain.setTargetsNow("),
                "四个 DSP 目标必须一次性发布");
        String speed = body(player, "private void applySpeedAndPitchNow(float tempo, float pitch)");
        assertTrue(speed.contains("this.player.setRateAndStretchNow(safePitch, safeTempo / safePitch)"),
                "有伸缩层时必须把速度与音高解耦");
        assertTrue(speed.contains("this.player.setRateNow(combined)"),
                "没有伸缩层时必须退回单个重采样倍率");
    }

    /** The hook the task is registered on. */
    @Test
    @DisplayName("Engine 暴露音频线程钩子并指向 JSyn 的任务列表")
    void engineExposesTheAudioThreadHook() throws IOException {
        String engine = read(repackage("processing", "sound", "Engine.java"));

        assertTrue(body(engine, "public static void addAudioTask(Runnable task)")
                        .contains("Engine.getEngine().synth.addAudioTask(task)"), "任务必须注册在合成器上");
        assertTrue(body(engine, "public static void removeAudioTask(Runnable task)")
                        .contains("synth.removeAudioTask(task)"), "移除必须走同一个合成器");
        assertTrue(body(engine, "public static double audioTimeSeconds()")
                        .contains("getCurrentTime()"), "音频时间必须取合成器时钟");
    }

    private static AutomixPlan plan(FoliaTransitionStyle style, float duck, float tempo,
                                    float midTiltDb, float highTiltDb) {
        return new AutomixPlan(60_000L, 8_000L, 0L, 1f,
                true, true, tempo, false, 0L, duck, "test", style,
                midTiltDb, highTiltDb, false, .42, .42, .45, false, 500.0);
    }

    private static int count(String haystack, String needle) {
        int total = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            total++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return total;
    }

    /**
     * The text of one member, found by signature and cut at its matching closing brace.
     *
     * <p>Braces inside string literals are not a concern for the members this file inspects, and a
     * scanner that tried to handle them would be more likely to be wrong than this is.</p>
     */
    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "源码里找不到: " + signature);
        int open = source.indexOf('{', start);
        assertTrue(open > start, "签名后面没有方法体: " + signature);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return source.substring(open, i + 1);
            }
        }
        throw new AssertionError("方法体没有闭合: " + signature);
    }

    private static Path music(String file) {
        return JAVA.resolve(Paths.get("com", "muoniumplayer", "core", "ncm", "music", file));
    }

    private static Path repackage(String a, String b, String file) {
        return JAVA.resolve(Paths.get("repackage", a, b, file));
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
