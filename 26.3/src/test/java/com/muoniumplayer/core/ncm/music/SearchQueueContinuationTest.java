package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.ncm.music.dto.Album;
import com.muoniumplayer.core.ncm.music.dto.Artist;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 搜索起播续队 + 「下一首播放」接线的回归测试。
 *
 * <p>分两半，与仓库里 {@code PersonalFmModeStaticTest} 一个套路。<b>纯函数</b>那一半钉
 * {@link NeteaseRecentPlaysService#buildTail} ：队头由起播那一刻装好，这里只交尾部，去重按来源身份。
 * <b>接线</b>那一半静态扫源码，钉住三条容易被下一次改动拆掉的数据流：</p>
 *
 * <ul>
 *   <li>接不接「最近播放」由<b>曲目自己的来源 + 网易云登录态</b>当场判，判不过就退回普通歌单语义，
 *       一次网络都不发。这里曾经无条件去拉网易云历史，于是在酷狗/汽水/B 站的搜索结果里点一首歌，
 *       队列变成「1 首外部源 + 上百首网易云听歌历史」。</li>
 *   <li>那张「已接入最近播放」的卡带时效。它说的是一次已经过去的点击，迟了好几秒再冒出来就是
 *       用户眼里的「莫名其妙弹一张」。</li>
 *   <li>待播队列不再持有任何绝对下标，而播放指针回到队首（列表循环 / 随机重洗）时账本必须作废。</li>
 * </ul>
 *
 * <p>不启动 Minecraft、不联网。</p>
 */
class SearchQueueContinuationTest {

    @Test
    @DisplayName("尾部只交「点的那首之外」的历史，顺序照接口返回，重复只留第一 occurrence")
    void buildTailDropsTheSelectedTrackAndKeepsOrder() {
        Music clicked = song(1L);
        Music a = song(2L);
        Music b = song(3L);

        assertEquals(Arrays.asList(a, b),
                NeteaseRecentPlaysService.buildTail(clicked, Arrays.asList(clicked, a, b)));
        assertEquals(Arrays.asList(a, b),
                NeteaseRecentPlaysService.buildTail(clicked, Arrays.asList(a, clicked, b, a)),
                "历史里重复出现的那首要留着第一次出现的位置");
        assertTrue(NeteaseRecentPlaysService.buildTail(clicked, Collections.<Music>emptyList()).isEmpty());
        assertTrue(NeteaseRecentPlaysService.buildTail(clicked, null).isEmpty());

        List<Music> withNulls = new ArrayList<>(Arrays.asList(a, null, b));
        assertEquals(Arrays.asList(a, b), NeteaseRecentPlaysService.buildTail(null, withNulls),
                "空曲目不许混进队列，否则每帧读待播列表的界面要拿 null 去比身份");
    }

    @Test
    @DisplayName("接不接最近播放先判来源与登录态；判不过就是普通歌单那一条路")
    void continuationIsGatedOnSourceAndLogin() throws IOException {
        String selection = region(cloudMusic(),
                "public static void playFromSearchSelection(Music selected", "这次起播有没有");
        String gate = region(cloudMusic(), "private static boolean hasRecentPlayContext", "普通歌单语义下");

        int gateCheck = selection.indexOf("if (!hasRecentPlayContext(selected))");
        int fetch = selection.indexOf("fetchRecentSongs");
        assertTrue(gateCheck >= 0, "找不到音源/登录态那道门");
        assertTrue(fetch >= 0, "网易云那条路仍然要去拉一次最近播放");
        assertTrue(gateCheck < fetch, "门必须排在网络请求前面：判不过就不该发这一趟");
        assertTrue(selection.indexOf("play(results,", gateCheck) < fetch,
                "退回的那条路就是普通 play，不能偷偷再挂一个异步追加");
        assertTrue(gate.contains("selected.isNetease()"), "判据用曲目自己的来源");
        assertTrue(gate.contains("NeteaseMusicService.isLoggedIn(MusicPlatform.NETEASE)"),
                "未登录时接口只回空历史，必须当场判掉");
        assertFalse(gate.contains("getCurrentPlatform()"),
                "当前音源是全局设置，挂着网易云音源点一张汽水列表时会判错");
        assertFalse(selection.contains("buildQueue"), "队头已经由起播装好，这里只要尾部");
    }

    @Test
    @DisplayName("退回普通歌单语义时，队列就是「从这一首往后到列表末尾」，一格不多一格不少")
    void fallbackTailMirrorsPlainPlaylistOrder() throws IOException {
        String tail = region(cloudMusic(),
                "private static List<Music> tailAfterSelected(List<Music> results", "接到活队列上");
        assertTrue(tail.contains("selectedIndex + 1"), "起点是这一首的下一格");
        assertTrue(tail.contains("results.size()"), "终点是列表末尾：旧的写法把整份结果都接上，"
                + "等于把用户点的那一首之前的歌排到了它后面");
        // 整份结果被接上时，用户从搜索结果第 30 行点一首歌，队列会变成「30 首之后才轮到它后面那首」。
        assertTrue(tail.contains("return Collections.emptyList()"), "点的是最后一行就没有尾巴，别硬造");
    }

    @Test
    @DisplayName("「已加入下一首播放」那句位次要说清是在当前这首之后第几位")
    void queuedNextCardWordingIsRelativeToCurrentTrack() throws IOException {
        String island = read(ProjectPaths.sharedJava().resolve(Paths.get(
                "com", "muoniumplayer", "core", "rendering", "DownloadDynamicIsland.java")));
        String card = region(island, "public static void showQueuedNext(String trackName, int pending)",
                "搜索结果起播后");
        assertTrue(card.contains("当前这首之后第"),
                "位次是相对当前播放那一首算的（插入点在待播段末尾），写成行号会让人永远差一格");
    }

    @Test
    @DisplayName("那张解释性卡片带时效，排队排太久就作废而不是迟到弹出")
    void continuationNoticeCarriesTtl() throws IOException {
        String island = read(ProjectPaths.sharedJava().resolve(Paths.get(
                "com", "muoniumplayer", "core", "rendering", "DownloadDynamicIsland.java")));
        String notice = region(island, "public static void showSearchQueueFromRecentPlays(int count)",
                "私人 FM 已经把下一首推荐预备进队列的提示");
        assertTrue(notice.contains("SEARCH_QUEUE_NOTICE_TTL_MILLIS"),
                "publishNotice 少传了时效参数，卡片就会在任何一次排队之后迟到弹出");
        long ttl = longLiteral(island, "SEARCH_QUEUE_NOTICE_TTL_MILLIS = ");
        assertTrue(ttl > 0L, "时效必须是正数");
        assertTrue(ttl <= 15_000L, "时效超过十五秒就已经不是「解释刚才那一下」了");
    }

    @Test
    @DisplayName("搜索结果那条路由曲行保留，且只有一条路能改动队列")
    void rowRoutingStillReachesThePlayLayer() throws IOException {
        String widget = read(ProjectPaths.sharedJava().resolve(Paths.get(
                "com", "muoniumplayer", "core", "screens", "ncm", "panels", "MusicWidget.java")));
        assertTrue(widget.contains("CloudMusic.playFromSearchSelection(this.music, playList.getMusics(), index)"),
                "曲行不再把「点的是哪一首、它在列表第几格」交出去，播放层就没法判退路");
        assertTrue(widget.contains("playList.isSearchResultList()"),
                "曲行要先问这一条列表是不是搜索结果，才有「退退回普通歌单语义」这一步可言");
        assertTrue(widget.indexOf("playFromSearchSelection") < widget.indexOf("CloudMusic.play(playList.getMusics()"),
                "搜索分支必须排在通用 play 前面，否则那道路由门形同虚设");
    }

    @Test
    @DisplayName("待播队列不持有绝对下标，指针回队的两处都要作废账本")
    void ledgerHasNoAbsoluteIndex() throws IOException {
        String cloud = cloudMusic();
        assertFalse(cloud.contains("playNextTail"),
                "绝对尾下标一回来，列表循环和随机重洗就会把它读成上百首待播");
        String count = region(cloud, "public static int getQueuedNextCount()", "该曲目是否是当前用户队列");
        assertTrue(count.contains("pendingRun"), "读数必须来自现算的连续段");
        assertFalse(count.contains("- current"), "旧病根就是这一句减法");

        String random = region(cloud, "if (playMode == PlayMode.Random) {", "PlayMode.LoopInList && curIdx");
        assertTrue(random.contains("clearQueuedNext()"), "重洗完列表必须忘掉那几格的承诺");
        String loop = region(cloud, "if (playMode == PlayMode.LoopInList && curIdx >= playList.size()) {",
                "private void sleep(int millis)");
        assertTrue(loop.contains("clearQueuedNext()"), "回到队首就是新一轮歌单");

        // 两处独立起播路径要销账：普通起播与无缝交接（另外一处是这个方法本身）。
        assertEquals(3, occurrences(cloud, "noteQueuedTrackStarted("),
                "少一处，播过的歌就会一直挂在账上");
        assertFalse(region(cloud, "private boolean adoptCrossSourceReplacement",
                        "CoverThemePalette.set(replacement.music.getCoverPalette())")
                        .contains("noteQueuedTrackStarted("),
                "正在播的这一格被替身顶上时不许动账：二次换源的 oldSong 可能是上一轮替身，"
                        + "而那个身份说不定正是用户排在后面待播的那首");

        // 预备期换源会就地改写队列里那一格，而那一格很可能就是用户「下一首播放」排出来的位置：
        // 替身必须继承待播身份，否则待播段断在这里，用户看到的就是「换一次源把待播列表清空了」。
        String rescue = region(cloud, "private void armCrossSourceRescue", "showCrossSourceSwitched");
        int migrate = rescue.indexOf("replacePendingIdentity(song, replacement.music)");
        int swap = rescue.indexOf("queue.set(index, replacement.music)");
        assertTrue(migrate >= 0, "预备期换源没有迁移待播身份");
        assertTrue(swap > migrate, "迁移必须发生在改写那一格之前，之后旧身份已经从队列里消失了");
    }

    // ════════════════════════ 共用工具 ════════════════════════

    private static String cloudMusic() throws IOException {
        return read(music("CloudMusic.java"));
    }

    private static Path music(String name) {
        return ProjectPaths.sharedJava().resolve(Paths.get(
                "com", "muoniumplayer", "core", "ncm", "music", name));
    }

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

    private static int occurrences(String source, String needle) {
        int count = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    private static Music song(long id) {
        return new Music("测试曲 " + id, "测试曲 " + id, "", id,
                new ArrayList<Artist>(), Collections.<String>emptyList(),
                new Album(id, "测试专辑 " + id, "", Collections.<String>emptyList()),
                200_000L, 0L, 0L, Collections.<String>emptyList());
    }
}
