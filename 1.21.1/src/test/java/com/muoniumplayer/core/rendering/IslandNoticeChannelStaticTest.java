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
 * <p>队列的语义全在 {@code DownloadDynamicIsland} 那几 private 个判定里，而这个类一加载就要
 * 建实例、碰渲染，跑不起来。偏偏这里的错法不会崩、只会难看：把一条状态镜像当成事件排进队列，
 * 用户连点五次主题就是 5 × 2.1 秒把五个早已作废的主题名挨个放出来（回弹胶囊那款还会叠成五行），
 * 而「谁在什么时候把哪条通道挪错了」正是这种没人会去读第二遍的代码。所以用读源码的方式钉住
 * 这张决策表。</p>
 */
class IslandNoticeChannelStaticTest {

    private static final Path JAVA = ProjectPaths.sharedJava();
    private static final Path ISLAND = JAVA.resolve(
            Paths.get("com", "muoniumplayer", "core", "rendering", "DownloadDynamicIsland.java"));

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
    @DisplayName("状态镜像走即时通道：顶掉当前卡，队列里同类的过期副本当场作废")
    void stateMirrorsReplaceInsteadOfQueueing() throws IOException {
        String source = read(ISLAND);
        String instant = body(source, "private static boolean isInstantStateChannel(IslandNoticeType type)");

        for (String channel : STATE_MIRRORS) {
            assertTrue(instant.contains(channel), "即时通道少了 " + channel + "：它报的是当前值，不该排队");
        }

        // 即时 = 不进队列 + 抢占。这两条都挂在 bypassesNoticeQueue 上，缺一条就退化成排队。
        String bypass = body(source, "private static boolean bypassesNoticeQueue(IslandNoticeType type, boolean persistent)");
        assertTrue(bypass.contains("isInstantStateChannel(type)"),
                "即时通道没接进 bypassesNoticeQueue，等于还在排队");

        // 只有「顶掉」还不够：当前那张是另一条即时通道时，新来的这条会先排上队（见 publishNotice
        // 里 bypassesNoticeQueue(noticeType) 那一支），所以同类的旧副本必须清掉。
        // 注意取的是那条五参重载：三个同名重载里只有它带 ttlMillis，用方法名去截会截到
        // 上面那条四参的转发体，那里当然什么判定都没有。
        String publish = body(source, "boolean persistent, long ttlMillis)");
        int guard = publish.indexOf("if (isInstantStateChannel(safeType))");
        int drop = publish.indexOf("dropQueuedNoticesOfTypeLocked(safeType)");
        assertTrue(guard >= 0 && drop > guard && drop - guard < 600,
                "publishNotice 没在即时通道进来时清掉队列里的同类过期副本");
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
        String navigate = read(JAVA.resolve(Paths.get("com", "muoniumplayer", "core", "screens",
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
