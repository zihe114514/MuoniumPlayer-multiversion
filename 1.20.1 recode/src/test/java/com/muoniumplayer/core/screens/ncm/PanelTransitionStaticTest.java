package com.muoniumplayer.core.screens.ncm;

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
 * 换页转场的离线契约测试。
 *
 * <p>转场这套东西改坏了不会崩，只会难看，而且是最难说清的那种难看：两页串行淡入淡出会露一帧空底、
 * 指数逼近的 alpha 在默认速度下两三帧吃光等于没有动画、封面不让位会同时出现两张、飞行封面自己
 * 走一条时钟会和落点差一帧。这些都得靠读源码钉住 —— 类一加载就要建 GL 上下文，跑不起来。</p>
 *
 * <p>断言刻意只认「结构」不认「数值」：常量怎么调都是观感问题，但「两页是否同时画」「时长是不是
 * 真时钟」是性质问题，性质一旦丢了就再也回不来。</p>
 */
class PanelTransitionStaticTest {

    private static final Path JAVA = ProjectPaths.sharedJava();
    private static final Path NCM = JAVA.resolve(Paths.get("com", "muoniumplayer", "core", "screens", "ncm"));
    private static final Path PANELS = NCM.resolve("panels");

    /** 换页那一段渲染：从推进时钟到画完新页。 */
    private static String transitionRegion(String screen) {
        int start = screen.indexOf("if (this.prevAnimatingPanel != null) {");
        assertTrue(start >= 0, "找不到换页转场的时钟段");
        int end = screen.indexOf("// 导航等待遮罩", start);
        assertTrue(end > start, "换页转场段后面找不到收尾锚点，八成是渲染被挪走了");
        return screen.substring(start, end);
    }

    @Test
    @DisplayName("两页同时画：旧页淡出的同一帧新页就在淡入，中间不许有空底")
    void bothPagesRenderOnTheSameFrame() throws IOException {
        String region = transitionRegion(read(NCM.resolve("NCMScreen.java")));

        // 旧实现是 if / else if：旧页淡完才轮到新页，必然露一帧空底。
        assertFalse(region.contains("} else if (this.currentPanel != null)"),
                "两页又变回串行淡入了：旧页淡完才画新页，中间那一帧是空底");
        assertTrue(region.contains("if (this.prevAnimatingPanel != null)"), "旧页不再被绘制");
        assertTrue(region.contains("if (this.currentPanel != null)"), "新页不再被绘制");

        // 两页读同一条 eased 曲线，才谈得上「交叉」；各走各的就成了两段独立动画。
        assertTrue(region.contains("transitionEased"), "转场没有共享的缓动进度");
        assertTrue(region.contains("1.0 - transitionEased"), "旧页没有按同一条进度淡出");
    }

    @Test
    @DisplayName("时长是真时钟：按帧间隔累加到 1，而不是指数逼近")
    void durationComesFromAFrameDeltaClock() throws IOException {
        String screen = read(NCM.resolve("NCMScreen.java"));
        String region = transitionRegion(screen);

        assertFalse(region.contains("Interpolations.interpolate"),
                "转场 alpha 又回到指数逼近：它没有时长概念，默认速度下两三帧就吃光");
        assertTrue(region.contains("panelTransitionStepMillis() / panelTransitionMillis()"),
                "进度不是「本帧毫秒 / 总毫秒」，就不是时间驱动");

        // 总时长跟着用户的「界面切换动画速度」走；步进按帧间隔，并且钳住大帧。
        assertTrue(screen.contains("PANEL_TRANSITION_MS / Math.max(.30, HudConfig.playerUiSwitchSpeed)"),
                "转场时长没和界面切换速度倍率挂钩");
        assertTrue(screen.contains("RenderSystem.getFrameDeltaTime() * 10.0"),
                "步进不是帧间隔，掉帧时动画会整段跳掉");
        assertTrue(screen.contains("Math.min(40.0"), "大帧没夹住：一帧跨掉小半条曲线就是一顿一顿");
    }

    @Test
    @DisplayName("位移有方向：返回时镜像，两页都裁在内容区里")
    void slideIsDirectionalAndClipped() throws IOException {
        String screen = read(NCM.resolve("NCMScreen.java"));
        String region = transitionRegion(screen);

        assertTrue(region.contains("this.panelTransitionBackward ? -1.0 : 1.0"),
                "位移没有前进/后退方向：返回键看起来像又前进了一页");
        assertTrue(region.contains("-slideDistance * transitionEased"), "旧页没有往反方向推出去");
        assertTrue(region.contains("slideDistance * (1.0 - transitionEased)"), "新页没有从侧面推进来");
        assertTrue(screen.contains("this.pendingNavigationBackward = true"), "navigateBack 没有声明方向");

        // 一位移就得出界：不裁的话旧页会糊到左侧导航栏上。两段 beginClip 都必须是内容区。
        long clips = java.util.stream.Stream.of(region.split("StencilClipManager.beginClip\\(")).skip(1).count();
        assertTrue(clips >= 2, "两页不再各自裁剪");
        assertTrue(region.contains("this.currentPanelBg.getX(), this.currentPanelBg.getY()"),
                "裁剪区不是内容区");
    }

    @Test
    @DisplayName("连点打断从当前不透明度接着淡，不许闪回全亮")
    void interruptedNavigationContinuesWhereItWas() throws IOException {
        String screen = read(NCM.resolve("NCMScreen.java"));
        String switchBody = body(screen, "private void innerSetCurrentPanel(NCMPanel panel, boolean shouldCallInit)");

        assertTrue(switchBody.contains("this.currentPanel.getAlpha()"),
                "被换下去的页没有记下自己当前的不透明度，中途再切会先闪回全亮");
        assertTrue(switchBody.contains("prevAnimatingPanelAlpha <= .02f"),
                "上一页几乎还没露脸时应当直接跳过转场，而不是硬凑一次");
        assertTrue(transitionRegion(screen).contains("prevAnimatingPanelAlpha * (float) (1.0 - transitionEased)"),
                "旧页不是从记录下的那个值往下淡");
    }

    @Test
    @DisplayName("封面接力双向：去程点名落点面板，回程按歌单认领卡片，没人认领就整趟收手")
    void coverHeroRelayRunsBothWaysOnTheNavigationClock() throws IOException {
        String screen = read(NCM.resolve("NCMScreen.java"));
        String home = read(PANELS.resolve("HomePanel.java"));
        String playlist = read(PANELS.resolve("PlaylistPanel.java"));
        String heroSource = read(NCM.resolve("PlaylistCoverHero.java"));

        // 去程：起点必须在换页之前交出（那一刻卡片还在自己的位置上），落点则当场点名。
        String click = body(home, "this.setOnClickCallback((relativeX, relativeY, mouseButton) -> {");
        int begin = click.indexOf("PlaylistCoverHero.begin(");
        int navigate = click.indexOf("setCurrentPanel(");
        assertTrue(begin >= 0 && navigate > begin,
                "去程的起点必须在 setCurrentPanel 之前交出，否则矩形是上一帧的");
        assertTrue(click.substring(begin, navigate).contains("begin(this, panel, playList"),
                "去程的落点没有被点名：让先回报的一方算赢，上一页的卡片会抢下落点");

        // 回程：起点是详情页那张大封面，落点还没排版，只能按歌单身份认领。
        assertTrue(body(playlist, "public void handCoverBack()").contains("begin(this, null, this.playList"),
                "回程必须把落点留空、用歌单身份认领，否则永远等不到落点");
        assertTrue(body(screen, "private void handCoverBackIfNeeded(NCMPanel from)")
                        .contains("from instanceof PlaylistPanel && this.currentPanel instanceof HomePanel"),
                "回程只在「详情页 → 主页」这一趟才交起点，别的组合没有落点可飞");

        // 两侧都要每帧回报落点、并在飞行期间让出自己的封面。
        assertTrue(playlist.contains("PlaylistCoverHero.setTarget(this, this.playList"),
                "详情页不再回报落点，飞行封面会朝上一个位置飞去");
        assertTrue(home.contains("PlaylistCoverHero.setTarget(this, this.playList"),
                "主页卡片不再回报落点，回程就没有人认领");
        for (String panelFile : new String[]{"HomePanel.java", "PlaylistPanel.java"}) {
            String source = read(PANELS.resolve(panelFile));
            assertTrue(source.contains("PlaylistCoverHero.coversYield(this) ? null"),
                    panelFile + " 不再用「纹理供给换成 null」让位");
        }

        // 起点那一侧自己不许认领落点，否则飞出去的那张封面会把自己当成落点。
        assertTrue(heroSource.contains("owner == sourceOwner"),
                "setTarget 没排除起点：飞行封面会朝自己飞去");
        // 一帧都没人认领就收手 —— 起点已经让位了，再留着就是一个透明的洞。
        assertTrue(heroSource.contains("eased >= 1.0 || !hasTarget"),
                "没有落点时接力没有立刻收手，起点那侧会留下一个空洞");
        assertFalse(heroSource.contains("getFrameDeltaTime"),
                "接力自己起了一条时钟：它必须和换页同源，否则落地时对不上");
        assertTrue(transitionRegion(screen).contains("PlaylistCoverHero.render(transitionEased)"),
                "飞行封面没有跟着转场进度走，它和落点会差一帧");
        assertTrue(transitionRegion(screen).lastIndexOf("currentPanel.renderWidget")
                < transitionRegion(screen).indexOf("PlaylistCoverHero.render"), "飞行封面没有压在两页之上");
    }

    @Test
    @DisplayName("进场按「第一次被看见」发号：往下滚露出来的行也有动画，不是只有建页那一批")
    void entranceRevealsOnFirstRenderNotAtBuildTime() throws IOException {
        String entrance = read(PANELS.resolve("PanelItemEntrance.java"));
        assertTrue(entrance.contains("1.0 - Math.pow(1.0 - progress, 3.0)"),
                "进场曲线不再是三次缓出：线性收尾会「啪」地停住");
        assertTrue(entrance.contains("DROP_DISTANCE") && entrance.contains("SCALE_FROM"),
                "进场只剩不透明度了：位移和缩放才是「落进来」的那部分");

        // 发号器：每帧重排一次，第一批比转场晚一档、滚进来的零散几行不留这一档。
        assertTrue(entrance.contains("class RevealQueue"), "错峰发号器不见了");
        assertTrue(entrance.contains("if (this.revealedThisFrame > 0) this.firstBatch = false"),
                "首屏那一档必须只给第一批，否则滚进来的行会先空 90ms");
        assertTrue(entrance.contains("Math.min(this.revealedThisFrame++, STAGGER_LIMIT)"),
                "错峰没有上限，长列表会永远在排队");

        // 行自己等第一次被渲染才起算。
        for (String panelFile : new String[]{"MusicWidget.java", "HomePanel.java"}) {
            String source = read(PANELS.resolve(panelFile));
            assertTrue(source.contains("entrance.isStarted()") && source.contains("nextDelayMillis()"),
                    panelFile + " 不再在第一次被渲染时起算进场");
        }

        // 面板每帧开头把头一批重新计数：onRender 跑在子控件之前。
        assertTrue(read(PANELS.resolve("PlaylistPanel.java")).contains("revealQueue.beginFrame()"),
                "歌单页不再每帧重排错峰号");
        assertTrue(read(PANELS.resolve("HomePanel.java")).contains("revealQueue.beginFrame()"),
                "主页不再每帧重排错峰号");

        // 整套机制的前提：ScrollPanel 不渲染视口外的行。它要是改成全都渲染，
        // 「第一次被渲染」就不再等于「滚进视口」，这里会先红给你看。
        String scroll = read(JAVA.resolve(Paths.get("com", "muoniumplayer", "core", "rendering",
                "ui", "container", "ScrollPanel.java")));
        String cull = body(scroll, "protected boolean shouldRenderChildren(AbstractWidget<?> child");
        assertTrue(cull.contains("return false"), "ScrollPanel 不再跳过视口外的行，滚动进场会失效");
    }

    @Test
    @DisplayName("起落点必须完整落在裁剪自己的视口里：卡片滚出一半就不飞，别把封面漏到页眉上")
    void relayRefusesEndpointsOutsideTheirClipViewport() throws IOException {
        String hero = read(NCM.resolve("PlaylistCoverHero.java"));
        String home = read(PANELS.resolve("HomePanel.java"));
        String playlist = read(PANELS.resolve("PlaylistPanel.java"));

        // 闸门装在接力内部，不装在调用点：卡片这一侧的矩形是绝对坐标，ScrollPanel 的裁剪对它
        // 毫无影响 —— 半张卡片滚出视口时，接力照那个矩形起飞，就会画出一条盖住页眉、被内容区
        // 上沿齐根切平的巨型封面。
        assertTrue(body(hero, "public static void begin(").contains("fullyInside(viewport"),
                "begin 不再检查起点是否完整可见，滚出一半的卡片还会起飞");
        assertTrue(body(hero, "public static void setTarget(").contains("fullyInside(viewport"),
                "setTarget 不再检查落点：回程会把封面飞回一张半出视口的卡片上");

        // 判据落在接力内部，所以「谁被什么裁住」必须由调用点交进来。
        assertTrue(call(home, "PlaylistCoverHero.begin(").contains("getParent()"),
                "主页卡片没把网格交给接力，去程的可见性检查形同虚设");
        assertTrue(call(home, "PlaylistCoverHero.setTarget(").contains("getParent()"),
                "主页卡片回报落点时没带视口，回程的可见性检查形同虚设");
        // getParent() 就是那块网格 —— 卡片是直接被 add 进 ScrollPanel 的。
        assertTrue(home.contains("scrollPanel.addChild(card)"),
                "卡片不再直接挂在 ScrollPanel 下，getParent() 已经不是裁剪它的那层视口了");

        // 不被任何视口裁的那一侧（详情页大封面直接挂在面板上）必须照飞不误。
        assertTrue(hero.contains("if (viewport == null) return true"),
                "视口为空不再等于「不受裁剪」，详情页那趟接力会被自己判死");
        assertTrue(call(playlist, "PlaylistCoverHero.setTarget(").endsWith(", null"),
                "详情页回报落点时没显式传空视口");
        assertTrue(call(playlist, "PlaylistCoverHero.begin(").endsWith(", null"),
                "详情页交起点时没显式传空视口");
        // 悬停时封面会向四周胀出一点，那点溢出是设计如此，不该把接力判死。
        assertTrue(hero.contains("VISIBILITY_TOLERANCE"),
                "可见性判据没有容差了：悬停胀出的半个像素会把接力判死");
    }

    /** 从调用点取到语句结尾，用来看实参 —— 只看「有没有出现」会被后面的注释糊过去。 */
    private static String call(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "源码里找不到调用: " + signature);
        int end = source.indexOf(");", start);
        assertTrue(end > start, "调用没有闭合: " + signature);
        return source.substring(start, end);
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
