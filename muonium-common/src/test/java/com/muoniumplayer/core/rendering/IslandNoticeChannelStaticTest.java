package com.muoniumplayer.core.rendering;

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
 * 灵动岛通知通道的离线契约测试。
 *
 * <p>这里守住通知类型与界面调用点的契约。抢占、恢复、并发发布等运行时行为
 * 另由 IslandNoticeQueueTest 验证，避免只靠源码字符串断言调度正确性。</p>
 */
class IslandNoticeChannelStaticTest {

    private static final Path JAVA = ProjectPaths.sharedJava();
    private static final Path ISLAND = ProjectPaths.sourceFile(Paths.get("com", "muoniumplayer", "core", "rendering", "DownloadDynamicIsland.java"));

    /** 状态镜像：报「现在是什么」，只认最新那一份。 */
    private static final String[] STATE_MIRRORS = {
            "IslandNoticeType.THEME",
            "IslandNoticeType.STYLE",
            "IslandNoticeType.SCALE",
            "IslandNoticeType.VOLUME",
            "IslandNoticeType.QUALITY",
            "IslandNoticeType.PLAYBACK_TOGGLE"
    };

    /** 事件：发生了 N 件就该看见 N 件，一条都不许被后来的顶掉。 */
    private static final String[] EVENTS = {
            "REFRESHING", "REFRESH_SUCCESS", "REFRESH_ERROR",
            "PLAYLIST_TRACK_ADDING", "PLAYLIST_TRACK_ADD_SUCCESS", "PLAYLIST_TRACK_ALREADY_EXISTS",
            "PLAYLIST_TRACK_ADD_ERROR", "TRANSCODE_SUCCESS", "PLAYBACK_ERROR",
            "NETWORK_SUCCESS", "NETWORK_ERROR", "NETEASE_UPLOADING",
            "RECENT_PLAY_SUCCESS", "RECENT_PLAY_ERROR",
            "LISTENING_DURATION_SUCCESS", "LISTENING_DURATION_ERROR",
            "GD_SOURCE_LOADING", "GD_SOURCE_SUCCESS", "GD_SOURCE_HEALTHY", "GD_SOURCE_ERROR",
            "AUTOMIX", "AUTOMIX_BLENDING", "MODEL_INFERENCE", "MODEL_INFERENCE_SUCCESS",
            "MODEL_INFERENCE_FAILURE", "PLAY_NEXT"
    };

    @Test
    @DisplayName("状态镜像允许即时抢占：只合并同类旧值，不绕过高优先级事件")
    void stateMirrorsReplaceInsteadOfQueueing() throws IOException {
        String source = read(ISLAND);
        String instant = body(source, "private static boolean isInstantStateChannel(IslandNoticeType type)");

        for (String channel : STATE_MIRRORS) {
            assertTrue(instant.contains(channel), "即时通道少了 " + channel + "：它报的是当前值，不该排队");
        }

        // 即时状态仍会在优先级规则允许时抢占，但它们只清理自己的同类副本，
        // 不会把高优先级事件或其他状态通道一并吞掉。
        String publish = body(source, "boolean persistent, long ttlMillis, String channelKey)");
        assertTrue(publish.contains("boolean latestOnly = isInstantStateChannel(safeType)"));
        assertTrue(publish.contains("dropQueuedNoticesOfTypeLocked(safeType)"));
        assertTrue(publish.contains("IslandNoticeRules.preempts"));
        assertTrue(publish.contains("protectedResume"));
    }

    @Test
    @DisplayName("事件类通知一条都不许进即时通道")
    void eventsNeverJoinTheInstantChannel() throws IOException {
        String instant = body(read(ISLAND),
                "private static boolean isInstantStateChannel(IslandNoticeType type)");
        for (String event : EVENTS) {
            assertFalse(instant.contains("IslandNoticeType." + event),
                    "即时通道里出现了 " + event + "：那是一次发生一件事，排到队尾去");
        }
    }

    @Test
    @DisplayName("界面上那几个会被连点的按钮，报的就是这些即时类型")
    void theClickableStateChannelsPublishInstantTypes() throws IOException {
        String source = read(ISLAND);
        assertPublishes(source, "public static void showTheme(String themeName)", "IslandNoticeType.THEME");
        assertPublishes(source, "public static void showPlayerScale(int scalePercent)", "IslandNoticeType.SCALE");
        assertPublishes(source, "public static void showVolume(int percent)", "IslandNoticeType.VOLUME");
        assertPublishes(source, "public static void showPlaybackQuality(String quality, String format)",
                "IslandNoticeType.QUALITY");
        assertPublishes(source, "public static void showPlaybackPaused(String trackName)",
                "IslandNoticeType.PLAYBACK_TOGGLE");
        assertPublishes(source, "public static void showPlaybackResumed(String trackName)",
                "IslandNoticeType.PLAYBACK_TOGGLE");
        assertPublishes(source, "public static void cycleStyle()", "IslandNoticeType.STYLE");

        // 主题与播放器大小的按钮在导航栏上，改到别处去发就没人再报当前值了。
        String navigate = read(ProjectPaths.sourceFile(Paths.get("com", "muoniumplayer", "core", "screens",
                "ncm", "panels", "NavigateBar.java")));
        assertTrue(navigate.contains("DownloadDynamicIsland.showTheme(NCMTheme.getCurrentName())"),
                "主题按钮必须报切换之后那一个的名字，报旧名等于报假消息");
        assertTrue(navigate.contains("DownloadDynamicIsland.showPlayerScale(NCMPlayerConfig.getPlayerScalePercent())"),
                "播放器大小按钮同上：先改档再报新值");
    }

    private static void assertPublishes(String source, String signature, String type) {
        assertTrue(body(source, signature).contains(type),
                signature + " 不再发 " + type + "：这条通道会失去即时性");
    }

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

    private static String read(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
