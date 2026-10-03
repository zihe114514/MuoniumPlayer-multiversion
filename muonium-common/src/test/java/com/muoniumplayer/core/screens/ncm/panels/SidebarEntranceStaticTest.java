package com.muoniumplayer.core.screens.ncm.panels;

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
 * 侧栏进场的离线契约测试。
 *
 * <p>侧栏不是就地更新的：账号歌单异步落地、换音源，走的都是 {@code NCMScreen#markDirty} →
 * {@code NCMScreen#layout} → {@code new NavigateBar()}，整棵控件树重建。所以「没有入场动画」
 * 其实不是漏了动画，而是<b>新行天生第一帧就画满</b>。修法是把「哪些行是新的」记下来，
 * 只给新行装 {@link PanelItemEntrance}。这条链上任何一环被「顺手简化」都会退化成两种老毛病：
 * 整列重亮（看着像闪），或者干脆又不演了。</p>
 *
 * <p>全部读源码：侧栏一装配就要建 GL 上下文，跑不起来。</p>
 */
class SidebarEntranceStaticTest {

    private static final Path NAVIGATE = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "screens", "ncm", "panels", "NavigateBar.java"));

    @Test
    @DisplayName("每一行都从 addSidebarRow 进侧栏，没有第二道后门")
    void everyRowGoesThroughTheSigningHelper() throws IOException {
        String body = read(NAVIGATE);
        assertFalse(body.contains("playlistPanel.addChild(new PlaylistItem"),
                "还有一行是直接 addChild 的：它没被记签名，也就永远不会演进场");
        // 行和栏目标题都经过统一的装配 helper；列表容器只在 helper 内接收控件。
        assertEquals(2, count(body, "this.playlistPanel.addChild("),
                "侧栏里出现了新的直接 addChild：新增一行必须走统一装配 helper");
        assertTrue(body.contains("this.playlistPanel.addChild(item)"), "行装配 helper 本身不见了");
        assertTrue(body.contains("this.playlistPanel.addChild(widget)"), "栏目装配 helper 本身不见了");
    }

    @Test
    @DisplayName("只演新出现的行，常驻行不重亮")
    void onlyNewRowsAnimate() throws IOException {
        String body = read(NAVIGATE);
        String helper = between(body, "private PlaylistItem addSidebarRow", "private void retireRows");
        assertTrue(helper.contains("rowsAnnounced && !announcedRows.containsKey(key)"),
                "进场没有按「上一次有没有这一行」来分：要么整列重亮，要么谁都不亮");
        assertTrue(helper.contains("this.sidebarRows.put(key, item)"),
                "这一行没被记下来，下一次重建既认不出它是老朋友，也不知道它该不该退场");
        // 第一次装配（prewarm 那一棵）不演：用户还没见过任何一行，而打开播放器自己有转场。
        assertTrue(body.contains("rowsAnnounced = true"), "没有「已经装配过一次」这个闸门");
    }

    @Test
    @DisplayName("被换下去的行留住退场，且不占列表槽位")
    void leavingRowsAreKeptAndFaded() throws IOException {
        String body = read(NAVIGATE);
        String retire = between(body, "private void retireRows", "private void advanceRetiringRows");
        assertTrue(retire.contains("this.retirementLayer.addChild(row)"),
                "退场的行没有被挂回侧栏：整树重建之后旧行直接消失，这就是「没有退场动画」");
        assertTrue(retire.contains("this.retirementLayer.addChild(row)"),
                "为了插到指定位置绕过了 addChild，却没换父亲：getY() 还按旧列表容器算，幽灵整批跑出侧栏");
        assertFalse(retire.contains("playlistPanel.addChild"),
                "退场的行挂进了列表容器：列表是自动堆叠的，多挂一行就会把新行往下顶一格");
        assertTrue(retire.contains("row.entranceQueue = null"),
                "旧进场回调不能在退场期间把透明度写回，造成动画竞争");
        assertTrue(retire.contains("row.setClickable(false)"),
                "退场中的行还在吃点击：按到一行已经不存在的东西上");
        // 装配这一趟里侧栏自己的绝对 y 还不可信（basePanel 要到渲染回调才 center()），
        // 所以只留绝对值、每帧再换算成相对位置 —— 否则整批幽灵会往下错位一百多像素。
        assertTrue(retire.contains("row.retireAbsY = row.getY();"),
                "退场时拿装配中的 this.getY() 去算相对位置：那一行的原点还没摆正，幽灵会画到列表外面");
        assertFalse(retire.contains("row.getY() - this.getY()"),
                "装配期间就换算了相对 y：这一刻的 this.getY() 不是它渲染时真正所在的位置");
        assertTrue(retire.contains("PanelItemEntrance.applyTransform"),
                "退场另写了一套位移：和进场不是同一个动作，两条会互相打架");
        // 上下没有余地：新一列正占着同一格。只淡不移位，两行字会正正叠成一片认不出的糊字。
        assertTrue(retire.contains("-RETIRE_SLIDE_X * row.retireOut"),
                "退场没有往左带出这一格：幽灵行和新行同格同 x，淡出途中叠成认不出的字");
        assertTrue(!retire.contains("RETIRE_SLIDE_X * (1.0 - row.entranceEased)"),
                "左移按缓动值算：还画满的那头两帧恰好一点没挪开，等于没有");
        assertTrue(body.contains("if (rowsAnnounced)"),
                "第一次装配也会去退场：那时 announcedRows 里根本没有任何用户见过的行");
    }

    @Test
    @DisplayName("退场有人推进、演完就摘、连点不叠加")
    void retiringBatchIsDrivenAndBounded() throws IOException {
        String body = read(NAVIGATE);
        String advance = between(body, "private void advanceRetiringRows", "private static String playlistRowKey");
        assertTrue(advance.contains("this.retirementLayer.getChildren().remove(row)"),
                "演完没从树上摘掉：幽灵行会永远挂在侧栏上，越攒越多");
        assertTrue(advance.contains("System.currentTimeMillis()"), "退场没有真实时间来源");
        assertTrue(advance.contains("row.setPosition(4, row.retireAbsY - this.retirementLayer.getY())"),
                "退场没有每帧重新钉住那一格：幽灵会停在装配那一刻算出来的错位置上");
        // 不透明度要「先掉后缓」：线性会在头两三帧还接近满的，那正是和新列叠在一起的时候。
        assertTrue(advance.contains("row.retireOut = Math.pow(Math.max(0.0, progress), .35)"),
                "退场按缓动值线性淡出：最该让位的那几帧它还画满，新一列被压在下面糊成两行字");
        assertTrue(advance.contains("row.setAlpha((float) (row.retireAlpha * (1.0 - row.retireOut)))"),
                "淡出没有跟着那条先掉后缓的进度走");
        assertTrue(body.contains("this.advanceRetiringRows();"),
                "退场没挂在侧栏自己的每帧回调上：它们不属于任何列表，没人推进就会停在半透明上");
        String retire = between(body, "private void retireRows", "private void advanceRetiringRows");
        assertTrue(retire.contains("this.retiringRows.clear()"),
                "连点两次换音源会把两批幽灵叠在一起，最后满屏是透明的行");
    }

    @Test
    @DisplayName("歌单签名带上当前音源，换音源才认得出是换了一台")
    void rowKeysCarryThePlatform() throws IOException {
        String body = read(NAVIGATE);
        String key = between(body, "private static String playlistRowKey", "private void layout()");
        assertTrue(key.contains("currentSourceIdentity()") || key.contains("NeteaseMusicService.getCurrentPlatform()"),
                "签名里没有当前音源：网易云和酷狗撞同一个数字 id 时换音源就演不出来");
        assertFalse(key.contains("playlist.getPlatform()") && !key.contains("local"),
                "用的是 playlist 自己那个 transient 平台字段，各家服务填得不一定齐");
        // 账号歌单、本地单、导入引用、收藏四类都要各归各的 kind，撞名才会互相顶掉签名。
        for (String kind : new String[]{"\"local\"", "\"imported\"", "\"account\"", "\"subscribed\""}) {
            assertTrue(body.contains("playlistRowKey(" + kind), "少了一类歌单的签名：" + kind);
        }
    }

    @Test
    @DisplayName("错峰号在第一次被看见时领，不在建页时发好")
    void staggerIsClaimedOnFirstRender() throws IOException {
        String body = read(NAVIGATE);
        assertTrue(between(body, "public void onRender", "public boolean onMouseClicked")
                        .contains("this.entranceReveal.beginFrame()"),
                "侧栏没有每帧重发错峰号：视口外的行会提前把号用掉，滚进去时什么动画都看不见");
        String entrance = between(body, "private void updateEntrance", "private List<PlayList> searchNeteasePlaylists");
        assertTrue(entrance.contains("this.entranceQueue.nextDelayMillis()"),
                "错峰延迟不是在这里领的");
        assertFalse(between(body, "PlaylistItem armEntrance", "private void updateEntrance")
                        .contains("nextDelayMillis"),
                "建页时就发了号：和曲目列表那一条规则相反");
    }

    @Test
    @DisplayName("动作复用曲目行那一套，演完摘掉矩阵")
    void entranceReusesTheSharedMotion() throws IOException {
        String body = read(NAVIGATE);
        assertTrue(body.contains("PanelItemEntrance.applyTransform"),
                "侧栏自己另写了一套位移/缩放：和曲目行、首页卡片不会是同一个节奏");
        assertTrue(body.contains("this.setTransformations(null)"),
                "演完没摘变换：侧栏是常驻的一列，不该为已经停住的行一直付 pushMatrix");
        assertTrue(body.contains("this.setAlpha((float) this.entranceEased)"), "没有淡入");
    }

    @Test
    @DisplayName("签名在全部装配做完之后才交还")
    void keysArePublishedAfterTheWholeBuild() throws IOException {
        String body = read(NAVIGATE);
        int published = body.indexOf("announcedRows = Collections.unmodifiableMap");
        assertTrue(published > body.indexOf("playlistRowKey(\"subscribed\""),
                "签名交还得太早：收藏歌单那批还没记上，下一次重建会把它们当新行重亮");
        assertTrue(published < body.indexOf("this.buildSuggestionPanel();"),
                "签名交还得太晚：联想面板也在同一次装配里，顺序倒了容易漏");
    }

    private static int count(final String source, final String needle) {
        int total = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + needle.length())) {
            total++;
        }
        return total;
    }

    private static String between(final String source, final String from, final String until) {
        int start = source.indexOf(from);
        assertTrue(start >= 0, "找不到起点 " + from);
        int end = source.indexOf(until, start);
        assertTrue(end > start, "找不到终点 " + until);
        return source.substring(start, end);
    }

    /** 读源码并<b>剥掉注释</b>：实现自己的注释里就写着被禁的写法，不剥会自己把自己钉红。 */
    private static String read(final Path path) throws IOException {
        String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//[^\n]*", "");
    }
}
