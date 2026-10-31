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
 * 私人 FM 这一页的离线契约测试。
 *
 * <p>这一页的版式是被数据形状逼出来的：一次只拉一首（{@code PersonalFmManager.BATCH_SIZE}），
 * 所以它不是一张歌单。老版照歌单页摆，顶上四组共 44 枚标签堆成一面墙、底下一行曲子，
 * 大半屏是空的。现在当前这首当主体，标签每组一行横向滚，被换下去的留在「刚才推荐过」。</p>
 *
 * <p>这里钉的是<b>下一次改动最容易顺手拆掉</b>的几处：位置逐帧算（面板与宽度在装配那一刻还没被
 * {@code NCMScreen} 摆好）、收起只压 alpha（{@code setHidden} 之后没有任何东西能把它翻回来）、
 * 红心只有一份（在 {@link LikeButton} 里）、时长一律过 {@code SheetClock.speed()}。</p>
 *
 * <p>全部读源码：这一页一装配就要建 GL 上下文，跑不起来。</p>
 */
class PersonalFmLayoutStaticTest {

    private static final Path PANEL = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "screens", "ncm", "panels", "PersonalFmPanel.java"));
    private static final Path LIKE = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "screens", "ncm", "panels", "LikeButton.java"));
    private static final Path MANAGER = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "ncm", "music", "PersonalFmManager.java"));

    @Test
    @DisplayName("标签每组只占一行，不再随窗口宽度换行堆高")
    void eachSceneGroupIsOneScrollingRow() throws IOException {
        String body = read(PANEL);
        // 老的换行算法整段还在的话，窄窗口下四组会堆成一面墙，hero 卡又被挤回屏幕底下。
        assertFalse(body.contains("groupHeight("), "还留着按宽度算分组高度的老逻辑");
        assertFalse(body.contains("scenePosition("), "还留着逐枚算换行位置的老逻辑");
        assertTrue(body.contains("ScrollPanel.Alignment.HORIZONTAL"),
                "标签行不是横向滚的：一行放不下就只能堆第二行");
        assertTrue(body.contains("SCENE_ROW"), "分组行高没有集中成一个数，hero 卡的位置就还是算不出来");
        // 四组是「一个循环」而不是「四段代码」，所以钉的是循环按分组数走、每组只建一行。
        assertTrue(body.contains("for (int group = 0; group < SCENE_GROUP_LABELS.length; group++)"),
                "分组数没有跟着 PersonalFmManager 的分组走：上游加一组这里就少一行");
        assertEquals(1, count(body, "addSceneSegment(viewport, slot)"), "标签行建了不止一遍");
    }

    @Test
    @DisplayName("收起标签区只压 alpha，不 setHidden")
    void collapsedRowsStayRecoverable() throws IOException {
        String body = read(PANEL);
        String rows = between(body, "private void addSceneRows", "private SegmentControl addSceneSegment");
        assertFalse(rows.contains("setHidden("),
                "收起用了 setHidden：renderWidget 在隐藏态连 beforeRenderCallback 都不跑，"
                        + "这一区收起来就再也翻不回来了");
        assertTrue(rows.contains("setAlpha((float) eased)"), "收起没有按展开进度压透明度");
        assertTrue(rows.contains("groupReveal(slot)"),
                "四组共用一个进度：展开时就是一整块亮灯，不是依次到场");
        // 透明的行还在渲染，不 gate 点击就是「点到看不见的东西」。
        assertTrue(body.contains("control.setEnabled(sceneReveal > .5)"),
                "收起中的标签还能按：会点到一行已经看不见的内容");
    }

    @Test
    @DisplayName("hero 卡与历史列的位置每帧算，不留装配时的快照")
    void heroGeometryIsRecomputedEveryFrame() throws IOException {
        String body = read(PANEL);
        assertTrue(body.contains("private double heroTop()") && body.contains("private double heroWidth()"),
                "hero 卡的位置或宽度回到了装配时算的局部变量");
        // 面板与它的宽度都是 NCMScreen 在渲染回调里才摆好的，装配那一刻 getWidth() 还是 1。
        assertFalse(body.contains("double width = Math.max(1.0, getWidth() - MARGIN * 2.0);\r\n"),
                "在装配时就取了面板宽度：那一刻它还是 1，整张卡会宽 1 像素");
        assertFalse(body.contains("double top = SCENE_TOP + sceneHeight()"),
                "hero 卡的顶在装配时算了一次：标签区正在展开的那两百毫秒里卡片不会跟着走");
        assertTrue(body.contains("heroTop() + (HERO_H - COVER_BOX)"),
                "封面没跟着卡片一起走");
        assertTrue(body.contains("private double coverTop()"),
                "封面槽位没有收成一个逐帧问的式子");
        // 装配那一刻取一次 heroTop() 再塞进 final 变量，是这一页最难自己看出来的一处错：
        // 重新进入这一页时标签区从零展开，卡片往下走 96 逻辑像素，封面留在原地压在那四行标签上。
        assertFalse(body.contains("final double coverTop") || body.contains("final double staleTop"),
                "封面的顶又变成了装配时算好的常量：播放中切走再切回来，封面会留在标签行上");
        assertFalse(body.contains("double textLimit = heroTextWidth(textX)"),
                "文字列的限宽在装配时算了一次：那一刻面板宽度还不是本帧的，曲名会被裁到看不见");
        assertTrue(count(body, ".setMaxWidth(heroTextWidth(textX))") == 3,
                "三行文字不是每帧各取一次限宽");
        assertTrue(body.contains("historyTop() + 18.0"), "「刚才推荐过」那一列没有跟着 hero 卡走");
    }

    @Test
    @DisplayName("这一页自己的两处动画都走真毫秒时钟并过动画速度倍率")
    void everyMotionUsesTheSharedClock() throws IOException {
        String body = read(PANEL);
        assertTrue(body.contains("System.nanoTime()"), "没有真实时间来源，动画会按帧率走");
        assertTrue(body.contains("MAX_FRAME_MS"), "单帧没有上限：切后台回来会一步跨过整段曲线");
        for (String divisor : new String[]{"SCENE_MILLIS / SheetClock.speed()",
                "HERO_SWAP_MILLIS / SheetClock.speed()"}) {
            assertTrue(body.contains(divisor), "这一处时长没接「界面切换动画速度」：" + divisor);
        }
        // 模式行那条滑块交给共享控件了，它的时钟与曲线在 SegmentControlStaticTest 里钉。
        assertFalse(body.contains("PILL_MILLIS"), "面板里还自己养一条滑块时钟：和搜索栏那条会走成两个节奏");
    }

    @Test
    @DisplayName("模式行就是搜索栏那一条共享分段控件，且滑块位置活过整棵重排")
    void selectionIsTheSharedSegmentControl() throws IOException {
        String body = read(PANEL);
        assertTrue(body.contains("SegmentControl.attach(this,"),
                "模式行又自己画了一条滑块：轨道、内缩、圆角、回弹、墨迹居中将各写一份");
        assertTrue(body.contains("spec.memory = new SegmentControl.SlideMemory()"),
                "没给滑块留跨重建的记忆：点一次模式就是一次整棵重排，新实例第一帧只能钉在目标上，"
                        + "用户看到的就是瞬移");
        assertTrue(body.contains("public void remember(double slide) { modeSlide = slide; }"),
                "记忆没写回类上：下一次重排又拿不到上一帧的位置");
        assertFalse(body.contains("this.modePill = new RoundedRectWidget()"), "那条自画的滑块还在");
    }

    @Test
    @DisplayName("hero 卡有可见卡底，徽标是实心小胶囊、动作行右对齐")
    void heroCardReadsAsACard() throws IOException {
        String body = read(PANEL);
        // 面板底色就是 GENERIC_BACKGROUND，同色同 alpha 的「卡」等于没画。
        assertTrue(body.contains("NCMScreen.ColorType.PRIMARY_TEXT), .055"),
                "卡底没有往文字色提亮：读不出这是一张浮起来的卡");
        // 卡底只许一层：外面再套一圈 BORDER 圆角矩形时，这一页上半屏（标题、刷新、模式行、
        // 四组标签）整片不画 —— 圆角矩形在 Skia 里同时是「裁剪形状的录制入口」，
        // 两层同尺寸的圆角矩形叠着画就会把裁剪录歪。客户端里量过一次才收敛回一层。
        assertEquals(1, count(body, "private void addHero(Music current, boolean stale)"), "hero 卡建了不止一遍");
        assertFalse(body.contains("ColorType.BORDER"), "卡底外面又套了一圈描边：两层圆角矩形会触发裁剪");
        // 同一页出现「实心小胶囊 VIP」和「裸文字 VIP」两种写法，就是原来那处不协调。
        assertTrue(body.contains("setRadius(2.5)"), "徽标回到了裸文字：和曲行上那几枚小胶囊不是一种东西");
        assertTrue(body.contains("name.getRelativeX() + name.getWidth() + 3"),
                "徽标没跟着曲名的右边缘排");
        assertTrue(body.contains("MARGIN + heroWidth() - HERO_PAD - width"),
                "动作行没有右对齐到卡片右边缘：卡片右半就是一片空的");
        assertFalse(body.contains(".setPosition(like.getWidth() + BUTTON_GAP + 2, 2)"),
                "那颗 +2 的凑数偏移还在：红心和两颗按钮不在同一水平线上");
    }

    @Test
    @DisplayName("换一首时封面与文字是同一趟，新封面没到位就先用上一张顶着")
    void relayIsOneClockAndHistoryDoesNotReplay() throws IOException {
        String body = read(PANEL);
        assertTrue(body.contains("private double lineReveal(int line)"),
                "文字行没有跟着封面走：会出现图已经换了、字还是上一首");
        assertTrue(body.contains("TransitionCurveCache.SPRING_LAND.at(heroSwapT)"),
                "新封面没有落位那一下：只剩一次淡入，读起来像换了张贴图");
        assertTrue(body.contains("heroPreviousCover != null && TextureManager.getInstance().getTexture(heroCurrentCover) == null"),
                "接力没等封面纹理就位：淡入一张还没下载完的封面，屏幕上就是一块白");
        // 那道闸只许在「真的有两张图要接」时生效，而且要有兜底：图一直下不来时，
        // 整张卡连文字都不能永远空着。
        assertTrue(body.contains("< 1200.0"),
                "等新封面的那道闸没有时限：封面下载失败时这一页就永久是空的");
        assertTrue(body.contains("if (fresh.contains(keys.get(index))) row.setEntranceAnimation(this.revealQueue)"),
                "进场又回到按整列指纹挂：刚点了一枚标签，底下十几行会一起重演一次逐条落进来");
        assertTrue(body.contains("historySeen.retainAll(keys)"),
                "没有把演过的收回到当前这一列：上限之外再也认不出谁是新的");
        assertTrue(body.contains("if (!fresh.isEmpty()) this.revealQueue.restart()"),
                "错峰队列不是只在新歌到的时候重启：新来的那一首会拿到一个已经排到末尾的序号");
    }

    @Test
    @DisplayName("页眉与别的页同一条线，重新进入这一页要重演进场")
    void headerSharesTheLineAndReentryReplays() throws IOException {
        String body = read(PANEL);
        // 主页、排行榜、热搜的标题都写在 y=12；这一页原来写在 31，侧栏点下来页眉会上下跳 20 像素。
        assertTrue(body.contains("HEADER_TOP = 12.0"),
                "页眉又回到自己那一档高度：切页时标题会上下跳");
        assertTrue(body.contains("setPosition(MARGIN, HEADER_TOP)"),
                "标题没按共用的那一档写：见上");
        // 返回 22 高、刷新 17 高，两颗都不各写各的 y，而是都对准标题那一行的中心。
        assertTrue(count(body, "headerCenter(") >= 3,
                "右上角那两颗按钮不再跟着标题对中：盒子的中心不对文字的中心，就是一行没对齐");
        assertFalse(body.contains(".setPosition(Math.max(MARGIN, getWidth() - MARGIN - refresh.getWidth()), 31)"),
                "刷新又回到自己那一行：它得挤在返回左边，和标题同一道光轴线");

        // 「切走了又切回来」和「数据到了原地重排」用的是同一个实例、同一个 onInit，
        // 只能靠上一次真的画出来是什么时候来分。
        assertTrue(body.contains("gap > 500.0"),
                "没有重新进入这一页的判据：进场只会演一次，之后再切回来就是一页死图");
        assertTrue(body.contains("this.lastVisibleNanos = System.nanoTime()"),
                "渲染回调没记下本帧时刻：那个判据就永远量不到「这一页不在屏幕上」");
        assertTrue(body.contains("sceneReveal = 0.0"),
                "重新进入时标签区没有收回零再展开：那一区的进场就演不出来");
        assertTrue(body.contains("beginHeroRelay()"),
                "换曲与进场不再是同一趟：进场那一趟底下没有上一张可接，得走同一条时钟才有落位那一下");
    }

    @Test
    @DisplayName("红心只有一份实现，hero 卡与曲行共用")
    void likeActionHasOneHome() throws IOException {
        String panel = read(PANEL);
        assertTrue(panel.contains("new LikeButton(actions, current"),
                "hero 卡没有那颗红心，或自己另写了一份");
        assertFalse(panel.contains("setFavourite") && panel.contains("likeTrack"),
                "面板里又写了一遍各平台的收藏分支 —— 那份逻辑在 LikeButton 里只该有一个家");

        String like = read(LIKE);
        assertTrue(like.contains("BilibiliFavoriteService.setFavourite"), "红心没接 B 站收藏写入");
        assertTrue(like.contains("openConfirmation"), "取消收藏没走二次确认");
        // 两态那三条坑各自都有注释说明为什么；这里只钉最容易被「顺手简化」的一条。
        assertFalse(like.contains("outline.setHidden(liked"),
                "已喜欢时把描边那枚 setHidden 了：它的回调不再跑，动画那枚就再没人翻回来");
    }

    @Test
    @DisplayName("当前这首是页面主体，换下去的留在历史列并带错峰进场")
    void currentTrackIsTheSubjectAndHistoryFollows() throws IOException {
        String panel = read(PANEL);
        assertTrue(panel.contains("new RoundedImageWidget"), "hero 卡没有大封面");
        assertTrue(panel.contains("FontManager.pf18bold"), "曲名还是列表行那个字号，撑不起主体");
        assertTrue(panel.contains("row.setEntranceAnimation(this.revealQueue)"),
                "历史行走的是老路子：第一帧就画满");
        assertTrue(panel.contains("this.revealQueue.beginFrame()"),
                "错峰号没人发：条目第一次被渲染时领不到号");

        String manager = read(MANAGER);
        assertTrue(manager.contains("retireToHistory()"), "换下去的那首没被留下，历史列永远是空的");
        // 四条换批路径：网易云、酷狗、汽水发现流（468d911 接入时漏更新了这里）、接进播放。
        assertEquals(4, count(manager, "retireToHistory();"),
                "有一处换批没压历史：网易云、酷狗、汽水发现流、接进播放四条路径都得记");
    }

    @Test
    @DisplayName("刷新中给转轮，不再只把按钮调暗")
    void loadingHasASpinner() throws IOException {
        String body = read(PANEL);
        assertTrue(body.contains("new PanelLoadingWidget"), "加载中没有任何进度指示");
        assertTrue(body.contains("setSpinning(loading)") || body.contains("setSpinning(PersonalFmManager.isLoading())"),
                "转轮的开关没接加载状态");
    }

    // ════════════════════════════ 小工具 ════════════════════════════

    @Test
    @DisplayName("四组场景标签用的是同一条轨道 + 滑块，不等宽、且只有一组有选中")
    void sceneRowsAreSegmentControls() throws IOException {
        String body = read(PANEL);
        assertTrue(body.contains("spec.cellWidth = index -> labelWidth(labels[index])"),
                "场景那一行又回到等分格子：标签按文字长短排，等宽会把长标签挤出去");
        assertTrue(body.contains("spec.positionsItself = false"),
                "轨道自己报了位置：ScrollPanel 刚按滚动偏移摆好的那点 x 会被写回零，横向滚不动");
        assertTrue(body.contains("sceneSlides[group] = slide") && body.contains("return sceneSlides[group]"),
                "滑块没有按组各记一份记忆：点一枚标签就是一次整棵重排，新实例会把滑块钉在目标上（瞬移）");
        assertTrue(body.contains("sceneSlides[group] = Double.NaN"),
                "记忆初始化成了 0：每次装配都会从第一格滑过来，而 0 恰好是「上一帧在第一格」");
        // 四组互斥：一次只有一枚子模式生效，另外三组没有选中可言。
        String selection = slice(body, "private static int sceneSelectionOf");
        assertTrue(selection.contains("return -1"),
                "不在这一组时也报了个下标：另外三组会各自点亮一枚没被选中的标签");
    }

    @Test
    @DisplayName("换栏目时先把上一首交出去，内容和骨架不会同时可见")
    void heroHandsOffBeforeTheNewSongLands() throws IOException {
        String body = read(PANEL);
        // 曲子是新的一首才认它是「这一栏目要来的」；栏目换了而曲子没换，就是残影。
        assertTrue(body.contains("if (!key.equals(heroSongKey))"),
                "没有把这一首和它是哪个栏目要来的对上号：换栏目后卡上还是上一栏目那一首");
        assertTrue(body.contains("return current == null || !selection.equals(heroSongStamp)"),
                "残影判定只看有没有曲子：栏目已经换了而请求还没回来时，它判不出该交出去");
        assertTrue(body.contains("this.advanceHandoff()"),
                "交接没有走面板那一格真毫秒时钟：它会跟标签区展开、封面接力各走各的拍子");
        assertTrue(body.contains(".setAlpha((float) handoffT)"),
                "骨架不是跟着交接进度淡入的：会有一帧两张都在，或者一帧两张都不在");
        // 残影闸只拦 stale：新歌已到位时必须照建内容，否则起播那一次重建（noteFmTrackStarted）
        // 会带着还停在 1 的 handoffT 进来，卡片空白到下一次重建（已预备下一首）才补上。
        assertTrue(body.contains("if (current == null || (stale && handoffT >= .995)) return;"),
                "交接闸又拦住了新歌已到位的重建：起播后大卡片会一直空白");
        assertFalse(body.contains("if (current == null || handoffT >= .995) return;"),
                "交接闸退回了不区分 stale 的老写法");
        // 淡出的是整张卡：封面、三行文字、徽标、动作行都得乘同一个系数。
        assertTrue(count(body, "* heroKeep()") >= 6,
                "只有部分内容跟着交接淡：骨架亮起来的时候卡上还压着上一首的某一样东西");
    }

    @Test
    @DisplayName("四行场景标签建一次就复用：重排不丢滚动位置，也不让滑块瞬间换行")
    void sceneRowsSurviveTheRebuild() throws IOException {
        String body = read(PANEL);
        assertTrue(body.contains("if (this.sceneHeaders == null)"),
                "四行又回到每次装配重建：滚出来才点得到的那一枚，点完整行弹回行首");
        assertTrue(body.contains("addChild(this.sceneHeaders[group])"),
                "复用没有把缓存重新挂回面板：这一区会整块消失");
        assertTrue(body.contains("buildSceneRow(group)"), "分组那一行的构造没有单独收拢，缓存与重建会分叉");
        // 只在真的看不见时才动，点一枚本来就看得见的标签不该带一次整行位移。
        String view = slice(body, "private void keepChipInView");
        assertTrue(view.contains("if (index < 0 || viewport == null) return"),
                "这一组没有选中项时也要挪视口：每次换栏目四行都会各跳一下");
        assertTrue(view.contains("viewport.targetScrollOffset"),
                "没有把选中的那一枚挪进视口：滚到行尾去点一枚，点完看不见它落在哪");
    }

    @Test
    @DisplayName("骨架要等过期持续一小段才亮，快请求不该闪一下")
    void skeletonWaitsForTheGracePeriod() throws IOException {
        String body = read(PANEL);
        assertTrue(body.contains("heroStaleSinceNanos"), "没有给骨架起算点：宽限无从量起");
        assertTrue(body.contains("> 180.0"),
                "过期一帧就亮骨架：请求一百毫秒就回来时是「闪一下骨架又闪回去」，比留着上一首更难看");
        assertTrue(body.contains("if (stale && !heroStale)"),
                "宽限的起点每帧都被推迟：慢请求就永远亮不起骨架，等于这道闸白加");
    }

    private static String slice(final String source, final String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, "找不到 " + signature);
        return source.substring(at, Math.min(source.length(), at + 900));
    }

    private static String read(Path path) throws IOException {
        // autocrlf=true 检出会把工作区转成 CRLF，逐字节源码断言按 LF 钳 —— 先归一
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String between(String body, String from, String to) {
        int start = body.indexOf(from);
        int end = body.indexOf(to);
        assertTrue(start >= 0, "找不到起点：" + from);
        assertTrue(end > start, "找不到终点或顺序错了：" + to);
        return body.substring(start, end);
    }

    private static int count(String haystack, String needle) {
        int total = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            total++;
        }
        return total;
    }
}
