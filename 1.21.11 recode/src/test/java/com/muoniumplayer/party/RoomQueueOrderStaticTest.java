package com.muoniumplayer.party;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一起听房间里「点歌」与「下一首播放」是<b>两种排队意图</b>，不能塌成一条。
 *
 * <p>协议里只有一个 {@code enqueue} 消息，靠一个 {@code next} 位区分：服务端只对房主/管理员认这个位，
 * 认了就把这一格插到队首那段插队位的末尾，不认就排队尾。用户报的「新点的歌不是在已排的后面，
 * 而是下一首播放的」正是因为客户端把两条路都写成了 {@code queueNext} —— 于是房间里<b>每一次</b>点击
 * 一行播放都变成插队，把别人已经排好的歌全挤后面。</p>
 *
 * <p>参考实现 modernMP 也只把带 {@code next} 的那条挂在派对按钮上，普通行点击不插队。这里静态扫源码，
 * 把两条路的分界、以及「权限判定归服务端」这一条钉住：这两处塌掉都不会报错，只是房间里的顺序变得
 * 让人看不懂，所以只能靠断言。</p>
 */
class RoomQueueOrderStaticTest {

    private static final Path PARTY = ProjectPaths.versionRoot()
            .getParent().resolve(Paths.get("mpparty-common", "src", "main", "java", "com", "muoniumplayer", "party"));

    @Test
    @DisplayName("点一行播放 = 追加到队尾；曲行的「下一首播放」才带插队意图")
    void rowPlayAppendsWhilePlayNextJumps() throws IOException {
        String client = read(PARTY.resolve("MpPartyClient.java"));

        String start = region(client, "public static boolean interceptStartPlayback(List<Music> songs",
                "播放器的「下一首播放」");
        assertTrue(start.contains("enqueueInstead(song, false)"),
                "行点击必须走追加：它是「我想听这首」，不是「我要插队」");

        String next = region(client, "public static boolean interceptPlayNext(Music song)",
                "把一次本地起播/插播改成房间点歌");
        assertTrue(next.contains("enqueueInstead(song, true)"),
                "「下一首播放」要保住插队意图（服务端只对房主/管理员生效）");
    }

    @Test
    @DisplayName("两条路共用一个带标志的入口，不许再退回单一无参口子")
    void singleFlaggedEntry() throws IOException {
        String client = read(PARTY.resolve("MpPartyClient.java"));
        assertTrue(client.contains("private static boolean enqueueInstead(Music song, boolean playNext)"),
                "入口必须显式带 playNext：上一版正是没有这个参数，两条意图才塌成一条");

        String body = region(client, "private static boolean enqueueInstead(Music song, boolean playNext)",
                "表示「下一首」已被房间消化");
        assertTrue(body.contains("queueNext(song)"), "插队那一路要发给 queueNext");
        assertTrue(body.contains("requestSong(song)"), "追加那一路要发给 requestSong");
        assertTrue(body.indexOf("if (playNext)") < body.indexOf("requestSong(song)"),
                "两条路要按 playNext 分派，不是先后都发一遍（那会点两首）");
        // 权限归服务端：客户端自己判 hasControl 会跟着版本 skew，而服务端那一判同时管住房主与管理员。
        assertTrue(!body.contains("hasControl()"), "插不插队由服务端按权限决定，客户端不要自己判");
    }

    @Test
    @DisplayName("两个发送口的 next 位分别是 false / true，且都要先过通道这一关")
    void wireFlagsMatchIntent() throws IOException {
        String client = read(PARTY.resolve("MpPartyClient.java"));

        String append = region(client, "public static void requestSong(Music song)", "「下一首播放」");
        assertTrue(append.contains("upload(song, MpProto.C_ENQUEUE, false)"), "点歌这一路的 next 必须是 false");

        String jump = region(client, "public static void queueNext(Music song)", "房主/管理员的自由切歌");
        assertTrue(jump.contains("upload(song, MpProto.C_ENQUEUE, true)"), "插队这一路的 next 必须是 true");

        String send = region(client, "private static void sendTrack(String op, Music song, String url,",
                "把一次本地起播/插播改成房间点歌");
        assertTrue(send.contains("if (playNext) m.addProperty(\"next\", true)"),
                "只有插队才在消息里带 next，否则服务端会把每次点歌都当成插队");

        String body = region(client, "private static boolean enqueueInstead(Music song, boolean playNext)",
                "表示「下一首」已被房间消化");
        assertTrue(body.contains("requireChannel()"), "通道不通就别先报成功");
    }

    // ════════════════════════ 共用工具 ════════════════════════

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String region(String source, String from, String to) {
        int start = source.indexOf(from);
        assertTrue(start >= 0, "找不到起点：" + from);
        int end = source.indexOf(to, start);
        assertTrue(end > start, "找不到终点：" + to + "（起点之后）");
        return source.substring(start, end);
    }
}
