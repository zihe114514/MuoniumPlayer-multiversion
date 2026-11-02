package com.muoniumplayer.core.ncm.music;

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
 * 播放记忆的接线断言：源码级钉住「谁在什么时机调了谁」。
 *
 * <p>这一功能最怕的就是接线悄悄断掉：捕获点少一个，快照就停在旧位置；
 * restore 挂错位置（在 onStop 之前），恢复结果会被登录链路整个清掉——
 * 编译器对这两类错误都无感，只有源码断言能钉。断言粒度刻意保守：
 * 只钉「存在 + 相对顺序」，不钉行号与措辞。</p>
 */
class PlaybackResumeWiringStaticTest {

    private static String shared(String packageName, String fileName) throws IOException {
        String[] parts = packageName.split("\\.");
        String[] relative = new String[parts.length + 1];
        System.arraycopy(parts, 0, relative, 0, parts.length);
        relative[parts.length] = fileName;
        Path path = ProjectPaths.sourceFile(Paths.get("", relative));
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }

    @Test
    @DisplayName("restore 挂在 initNCM 的 warmLikedSongsAsync 之后（在登录收尾 onStop 之后执行）")
    void restoreHookComesAfterLoginCleanup() throws Exception {
        String cloud = shared("com.muoniumplayer.core.ncm.music", "CloudMusic.java");
        int warm = cloud.indexOf("KuGouMusicService.warmLikedSongsAsync();");
        int restore = cloud.indexOf("restoreLastSession();");
        assertTrue(warm >= 0, "找不到 warmLikedSongsAsync 锚点（initNCM 重构过？同步本测试）");
        assertTrue(restore > warm,
                "restoreLastSession() 必须挂在 initNCM 最末（warmLikedSongsAsync 之后）——"
                        + "放它前面会被 loadNCM 的 onStop() 清掉恢复结果");
    }

    @Test
    @DisplayName("六处捕获点全部接线：切歌、推进、暂停/恢复、seek 去抖、心跳、关闭钩子")
    void capturePointsWired() throws Exception {
        String cloud = shared("com.muoniumplayer.core.ncm.music", "CloudMusic.java");
        assertTrue(cloud.contains("PlaybackResumeStore.noteTrackEvent("),
                "终态事件（切歌/暂停/关闭钩子）要经 noteTrackEvent 落盘");
        assertTrue(cloud.contains("PlaybackResumeStore.noteSeekThrottled(") || cloud.contains("noteSeekForResume();"),
                "seek 要有去抖落盘入口");
        assertTrue(cloud.contains("CloudMusic.noteHeartbeatForResume();")
                        && cloud.contains("noteHeartbeatForResume()"),
                "播放线程 tick 里要有 15s 心跳");
        int shutdown = cloud.indexOf("addShutdownHook");
        int saveInHook = cloud.indexOf("currentPlaybackPositionMillis()", shutdown);
        assertTrue(shutdown >= 0 && saveInHook > shutdown,
                "关闭钩子里要在收尾播放器之前补最后一次位置快照");
        assertTrue(cloud.contains("PlaybackResumeStore.clear();"),
                "stopPlaybackAndClear 必须联动清快照——不复活用户亲手清掉的队列");
    }

    @Test
    @DisplayName("续播偏移：restore 写入、startNewPlayThread 一次性转交、startPlayback 消费 playFrom")
    void resumeOffsetConsumedExactlyOnce() throws Exception {
        String cloud = shared("com.muoniumplayer.core.ncm.music", "CloudMusic.java");
        int restoreWrite = cloud.indexOf("pendingResumeOffsetMillis = Math.max(0L, snapshot.positionMillis);");
        int transfer = cloud.indexOf("playThread = new PlayThread(songs, startIdx, Math.max(0L, resumeOffset));");
        int consume = cloud.indexOf("activePlayer.playFrom(target);");
        assertTrue(restoreWrite >= 0, "restore 必须写入续播偏移");
        assertTrue(transfer > restoreWrite, "startNewPlayThread 必须把偏移一次性转交给 PlayThread");
        assertTrue(consume > transfer, "startPlayback 必须用 playFrom 消费偏移（automix 同款路径）");
        // 用户主动动作作废偏移
        assertTrue(cloud.contains("discardResumeOffset();"),
                "prev/next/重启选歌要作废续播偏移");
        // 偏移 clamp 到曲长，不许照单全收
        assertTrue(cloud.contains("Math.min((float) offset"), "起播偏移必须 clamp 到曲长");
    }

    @Test
    @DisplayName("restore 零副作用：不起线程、不发网络、快照只读不写")
    void restoreIsSideEffectFree() throws Exception {
        String cloud = shared("com.muoniumplayer.core.ncm.music", "CloudMusic.java");
        int start = cloud.indexOf("private static void restoreLastSession()");
        int end = cloud.indexOf("private static String getCookieFromFileOrOptions()", start);
        if (end < 0) end = Math.min(cloud.length(), start + 5000);
        String body = start >= 0 ? cloud.substring(start, end) : "";
        assertTrue(start >= 0, "找不到 restoreLastSession 方法");
        assertFalse(body.contains("startNewPlayThread") || body.contains("startPlaybackList"),
                "恢复绝不起播——那是「自动续播出声」的边界，v1 明确不做");
        assertFalse(body.contains("getPlayUrl()"), "恢复绝不解析 URL——直链每次播放现取，快照里也不存");
    }

    @Test
    @DisplayName("定位接线：PlaylistPanel 按行高亮同一条判据找行，自动滚有只一次闸")
    void locateWired() throws Exception {
        String panel = shared("com.muoniumplayer.core.screens.ncm.panels", "PlaylistPanel.java");
        assertTrue(panel.contains("CloudMusic.playbackPresentation()"),
                "定位必须用 playbackPresentation 的展示键——与行高亮同一条判据，跨源替身才不脱队");
        assertTrue(panel.contains("scrollToOffsetImmediately("),
                "定位滚动走 ScrollPanel 现成的立即定位（NcmSheet 同款）");
        assertTrue(panel.contains("autoScrolledToPlaying"),
                "打开时自动滚必须有「只一次」闸，不能每帧都拽用户的滚动位置");
        assertTrue(panel.contains("currentPlaylistContext"),
                "自动滚只在「打开的就是当前队列来源那张单」时触发");
    }

    @Test
    @DisplayName("store 自身防御：save 里有 FM/房间两道闸，全 catch")
    void storeDefenses() throws Exception {
        String store = shared("com.muoniumplayer.core.ncm.music", "PlaybackResumeStore.java");
        int save = store.indexOf("private static void save(");
        assertTrue(save >= 0);
        String saveBody = store.substring(save, Math.min(store.length(), save + 3000));
        assertTrue(saveBody.contains("personalFmActive()") && saveBody.contains("MpPartyClient.inRoom()"),
                "save 内部必须自带 FM 与一起听房间两道拒写闸——不能依赖调用点记得判");
        assertTrue(saveBody.contains("catch (Throwable"), "save 必须 catch Throwable，写失败不上抛");
        assertTrue(store.contains("Files.deleteIfExists"), "clear 要真删文件");
    }
}
