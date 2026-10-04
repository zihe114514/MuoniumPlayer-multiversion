package com.muoniumplayer.core.screens.ncm;

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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 播放器二级浮层动效的离线契约测试。
 *
 * <p>和 {@link PanelTransitionStaticTest} 同一条理由：这些改动改坏了不会崩，只会难看，而且是最
 * 难说清的那种难看 —— 打开有动画、关掉啪一下没了；遮罩比卡片先到全场最黑；每点进一级整个界面
 * 重新淡一遍；退场途中那点输入被吃掉。这些都得靠读源码钉住，因为类一加载就要建 GL 上下文。</p>
 *
 * <p>断言只认结构不认数值：窗口开在 {@code t} 的第几成是观感问题，但「进度是不是真时钟」「退场
 * 是不是真的播完才回收」是性质问题。</p>
 */
class SheetMotionStaticTest {

    private static final Path NCM = ProjectPaths.sharedJava().resolve(Paths.get(
            "com", "muoniumplayer", "core", "screens", "ncm"));

    /** 走 {@code NcmSheet} 骨架的浮层。 */
    private static final String[] SHEETS = {
            "MusicSourceOverlay.java", "AccountManagerOverlay.java", "FontPickerOverlay.java",
            "PlaylistImportOverlay.java", "AnnouncementOverlay.java", "LyricSearchOverlay.java",
            "ListenTogetherOverlay.java"};

    /** 各自继承 {@code NCMPanel}、但同样接上 {@link SheetClock} 的那几层。 */
    private static final String[] STANDALONE = {
            "ConfirmationOverlay.java", "AddToPlaylistOverlay.java", "ThemePaletteOverlay.java",
            "FfmpegNoticeOverlay.java", "ModelInstallNoticeOverlay.java",
            "UpdateNoticeOverlay.java"};

    /**
     * 自己声明了 {@code shouldClose()} 的那几层：六个走骨架的浮层是从 {@code NcmSheet} 继承的，
     * 在它们自己的文件里找不到这个方法。
     */
    private static final String[] OWN_CLOSURE = own("NcmSheet.java");

    /** 自己手写过一层 {@code RectWidget mask} 的那几层；其余走 {@code NcmSheet.sheet()}。 */
    private static final String[] OWN_SCRIM = own("NcmSheet.java", "LyricSearchOverlay.java");

    // ──────────────────────── A：进出成对 ────────────────────────

    @Test
    @DisplayName("退场要播完才回收：shouldClose 读的是时钟而不是关闭请求")
    void everySheetWaitsForItsExitAnimation() throws IOException {
        List<String> names = new ArrayList<String>(Arrays.asList(OWN_CLOSURE));
        names.addAll(Arrays.asList(STANDALONE));
        for (String name : names) {
            String source = read(NCM.resolve(name));
            String shouldClose = body(source, "public boolean shouldClose()");
            assertTrue(shouldClose.contains("clock.isGone()"),
                    name + " 又回到「一请求关闭就回收」：中间那一段退场动画一帧都播不出来");

            // 关闭请求必须把时钟也带上，否则 isGone() 永远不为真，这一层就再也关不掉了。
            assertTrue(source.contains("clock.requestDismiss()"),
                    name + " 收到关闭请求时没有让时钟开始倒着走");
        }

        // 走骨架的那六个不许各自偷偷改回来 —— 一处收口，六层跟着。
        for (String name : SHEETS) {
            assertFalse(read(NCM.resolve(name)).contains("public boolean shouldClose()"),
                    name + " 又自己声明了 shouldClose，绕开了 NcmSheet 那一条退场规矩");
        }

        String sheet = read(ProjectPaths.sourceFile(Paths.get("com/muoniumplayer/core/screens/ncm", "NcmSheet.java")));
        assertTrue(body(sheet, "public void dispose()").contains("page.requestDismiss()"),
                "整层在退场、内容那一档却不跟着收，退场就只剩一块空底板在淡");
    }

    @Test
    @DisplayName("时长是真时钟：按毫秒推进并能原路退回，而不是每帧走剩下的一成二")
    void progressComesFromARealClock() throws IOException {
        String clock = read(ProjectPaths.sourceFile(Paths.get("com/muoniumplayer/core/screens/ncm", "SheetClock.java")));
        assertTrue(clock.contains("System.nanoTime()"), "进度不是按墙上时间推进的");
        assertTrue(clock.contains("dismissing ? closeMs : openMs"),
                "进场和退场不再是同一条 t 的两个方向：中途反向会先跳回全亮再淡出");
        assertTrue(clock.contains("Math.max(.30, Math.min(3.00, HudConfig.playerUiSwitchSpeed))"),
                "浮层时长没有跟着「界面切换动画速度」那一档走，和换页转场就不是一个节奏");

        // 谁都不许再把进度写成指数逼近。
        String sheet = read(ProjectPaths.sourceFile(Paths.get("com/muoniumplayer/core/screens/ncm", "NcmSheet.java")));
        assertFalse(body(sheet, "protected Panel sheet(").contains("Interpolations.interpolate(presentation"),
                "弹窗入场又回到指数逼近：它没有时长概念，尾巴还永远到不了 1");
        for (String name : SHEETS) {
            String source = read(NCM.resolve(name));
            assertFalse(source.contains("presentation = Interpolations.interpolate"),
                    name + " 还在自己渐近 presentation，没跟上基类那条时钟");
        }
        for (String name : STANDALONE) {
            String source = read(NCM.resolve(name));
            assertTrue(source.contains("TransitionCurveCache.MOTION.at(clock.tick())"),
                    name + " 的进度不再由 SheetClock 推");
        }
    }

    @Test
    @DisplayName("遮罩跟卡片同一条进度：不许再写死一个 alpha")
    void scrimFadesWithTheDialog() throws IOException {
        List<String> names = new ArrayList<String>(Arrays.asList(OWN_SCRIM));
        names.addAll(Arrays.asList(STANDALONE));
        for (String name : names) {
            String source = read(NCM.resolve(name));
            int mask = source.indexOf("RectWidget mask");
            assertTrue(mask >= 0, name + " 里找不到遮罩那一格");
            String region = source.substring(mask, Math.min(source.length(), mask + 900));
            assertFalse(region.contains("setAlpha(.4") || region.contains("setAlpha(.5"),
                    name + " 的遮罩又被写死了：卡片还没出现整屏先黑，关掉时卡片啪一下没了、遮罩还黑着");
            assertTrue(region.contains("presentation *"), name + " 的遮罩不再跟弹窗同一条进度");
        }
    }

    @Test
    @DisplayName("退场途中那一层不吃输入：既不穿透到下层，也不给自己翻新一页")
    void dismissingOverlaySwallowsInput() throws IOException {
        String panel = read(ProjectPaths.sourceFile(Paths.get("com/muoniumplayer/core/screens/ncm", "NCMPanel.java")));
        assertTrue(body(panel, "public void onMouseClickReceived").contains("if (isDismissing()) return"),
                "退场中的浮层又开始吃点击了：那一下可能正落在下层的歌曲行上");
        assertTrue(body(panel, "public boolean onKeyTypedReceived").contains("return false"),
                "退场中按键要返回 false，让宿主那串「ESC 抢在控件树之前」的分支照样把它收掉");

        // 规则收在基类里，这一层族里只许有一份。
        assertFalse(read(ProjectPaths.sourceFile(Paths.get("com/muoniumplayer/core/screens/ncm", "NcmSheet.java"))).contains("public void onMouseClickReceived"),
                "NcmSheet 又自己写了一遍吞输入：那条规则在 NCMPanel");

        // 翻页会清空子组件：只剩一块正在淡出的空底板是更难看的那种收场。
        assertTrue(body(read(ProjectPaths.sourceFile(Paths.get("com/muoniumplayer/core/screens/ncm", "NcmSheet.java"))), "protected void beginPage()")
                .contains("if (closing) return"), "退场途中还能翻页，会把子组件清空");
    }

    // ──────────────────────── C：内容编排 ────────────────────────

    @Test
    @DisplayName("底板 → 页眉 → 卡片 → 页脚四层依次到场，且都在同一条页内进度上开窗")
    void contentLandsInTiersOnOnePhase() throws IOException {
        String sheet = read(ProjectPaths.sourceFile(Paths.get("com/muoniumplayer/core/screens/ncm", "NcmSheet.java")));
        String order = "CARD_FIRST HEADER_FIRST FOOTER_FIRST";
        assertTrue(sheet.contains("HEADER_FIRST") && sheet.contains("FOOTER_FIRST")
                && sheet.contains("CARD_FIRST"), "分层到场的三档时机被人删了: " + order);
        assertTrue(sheet.contains("cardGate(int slot)") || sheet.contains("cardGate(final int slot)"),
                "卡片闸门不再是按序号开窗，那一串瀑布就没了");
        assertTrue(sheet.contains("Math.min(slot, CARD_STAGGER_LIMIT)"),
                "错峰没有上限：长列表底部的卡片会永远排在窗口外面（公告页当年得专门钳一道绕开它）");
        assertTrue(sheet.contains("veil(dialog, this::headerGate)"),
                "页眉不再整组到场：图标盘和它的标题会一先一后冒出来");

        // 开窗的轴必须是「未经缓动的原始进度」，各层才互不失配。
        assertTrue(sheet.contains("pageFade = page.tick()"), "内容那一档不再走自己的时钟");
        assertTrue(sheet.contains("presentation = TransitionCurveCache.MOTION.at(clock.tick())"),
                "底板那档不再走进出场的时钟");
    }

    @Test
    @DisplayName("翻页只重排内容，不把整个界面重开一遍；方向由返回键那侧声明")
    void pageChangeOnlyRecascadesContent() throws IOException {
        String sheet = read(ProjectPaths.sourceFile(Paths.get("com/muoniumplayer/core/screens/ncm", "NcmSheet.java")));
        assertFalse(body(sheet, "protected Panel sheet(").contains("presentation = 0.0"),
                "每次翻页都把整层复位：连遮罩和底板都重新淡一遍，看着像界面又开了一次");
        assertFalse(body(sheet, "protected Panel sheet(").contains("pageFade = 0.0"),
                "内容进度不该在 sheet() 里复位，那一档归 beginPage 管");
        assertTrue(sheet.contains("protected Runnable back(final Runnable action)"),
                "翻页方向没人声明：前进和后退长得一样，返回键就只是装饰");
        assertTrue(body(sheet, "protected void beginPage()").contains("pageDirection ="),
                "beginPage 没有把方向读进位移量里");
        assertTrue(sheet.contains("pageSlide() * (1.0 - bg.reveal)"), "卡片没有沿翻页方向滑进来");

        // 声明点：侧栏来源面板与账号管理里的返回键。
        assertTrue(read(ProjectPaths.sourceFile(Paths.get("com/muoniumplayer/core/screens/ncm", "MusicSourceOverlay.java"))).contains("subHeader(dialog, back(this::showOverview)"),
                "音乐来源的返回键不再声明这是一趟回翻");
        assertTrue(read(ProjectPaths.sourceFile(Paths.get("com/muoniumplayer/core/screens/ncm", "AccountManagerOverlay.java"))).contains("back(() -> showDetail(MusicPlatform.KUGOU))"),
                "账号管理的回翻点丢了方向声明");
    }

    @Test
    @DisplayName("分层浮层单独一档时长：整条瀑布只有 153ms 时四层等于同时到场")
    void cascadedSheetsGetTheirOwnLongerClock() throws IOException {
        String clock = read(ProjectPaths.sourceFile(Paths.get("com/muoniumplayer/core/screens/ncm", "SheetClock.java")));
        double plainOpen = constant(clock, "OPEN_MS");
        double cascadeOpen = constant(clock, "CASCADE_OPEN_MS");
        double cascadeClose = constant(clock, "CASCADE_CLOSE_MS");

        // 窗口是按 t 的百分比开的，所以「错峰看不看得出来」完全由总时长决定：
        // 相邻两张卡差 CARD_STEP(0.04) 这一档，153ms 的总长下只有 6ms。
        assertTrue(cascadeOpen >= plainOpen * 2.0,
                "分层那一档又缩回整层那一档了：一条瀑布至少要两倍长才数得出先后");
        assertTrue(cascadeClose < cascadeOpen, "退场不该比进场还磨蹭");

        // 两条时钟必须同档：接力板的不透明度是「1 − 落点那张卡的 reveal」，两条互补。
        String sheet = read(ProjectPaths.sourceFile(Paths.get("com/muoniumplayer/core/screens/ncm", "NcmSheet.java")));
        assertTrue(sheet.contains("SheetClock clock = SheetClock.cascade()"),
                "整层那一条退回默认时长：接力会先收手，卡片后到场，中间空一帧");
        assertTrue(sheet.contains("SheetClock page = SheetClock.cascade()"),
                "内容那一条退回默认时长，同上");

        // 只走单块淡入的那几层不该跟着变慢：确认框淡 383ms 就是拖沓。
        for (String name : STANDALONE) {
            assertTrue(read(NCM.resolve(name)).contains("new SheetClock()"),
                    name + " 不该用分层那一档的时长，它没有瀑布");
        }

        // 时长放开之后，尾部那个「窗口右端超出 1.0」的洞就藏不住了。
        String gate = body(sheet, "protected double cardGate(final int slot)");
        assertTrue(gate.contains("Math.min(from + CARD_TRAVEL, 1.0)"),
                "第 12 档的窗口右端是 1.28，pageFade 只走到 1.0，那一档会永久停在八成不透明度");
    }

    /** 从源码里读一个 {@code private static final double NAME = 值;} 常量。 */
    private static double constant(String source, String name) {
        String head = "double " + name + " =";
        int at = source.indexOf(head);
        assertTrue(at >= 0, "找不到常量 " + name);
        int end = source.indexOf(';', at);
        return Double.parseDouble(source.substring(at + head.length(), end).trim());
    }

    // ──────────────────────── B：触发点接力 ────────────────────────

    @Test
    @DisplayName("接力板是浮层进度的纯函数，画在所有子组件之上，并与落点那张卡互补淡出")
    void heroRelayRunsOnTheSheetClock() throws IOException {
        String relay = read(ProjectPaths.sourceFile(Paths.get("com/muoniumplayer/core/screens/ncm", "SheetHeroRelay.java")));
        assertFalse(relay.contains("getFrameDeltaTime") || relay.contains("nanoTime"),
                "接力自己起了一条时钟：它必须和落点同源，否则会在半空留下一块板");
        assertTrue(relay.contains("double alpha = 1.0 - landing"),
                "接力不再随落点那张卡变实而淡出：会有一帧两块板都全亮");
        assertTrue(relay.contains("eased >= 1.0 || eased <= 0.0"),
                "落位与还没起飞时它还在画，等于给已经在屏幕上的卡片再垫一块板");

        String sheet = read(ProjectPaths.sourceFile(Paths.get("com/muoniumplayer/core/screens/ncm", "NcmSheet.java")));
        // 取最后一次出现：NcmSheet 的 body() 里还有一格匿名 Panel 的 afterRenderChildren，
        // 那一格只关剪刀，拿它来判会假红。
        int at = sheet.lastIndexOf("protected void afterRenderChildren(double mouseX, double mouseY)");
        assertTrue(at > 0, "NcmSheet 不再自己画接力板");
        String hook = sheet.substring(at, Math.min(sheet.length(), at + 500));
        // 起点那颗按钮本来就在遮罩底下，板子必须压得住遮罩；画在子组件之前就会被自己的底板吃掉。
        assertTrue(hook.contains("super.afterRenderChildren") && hook.contains("SheetHeroRelay.render"),
                "接力板不再画在子组件之上");
        assertTrue(hook.indexOf("super.afterRenderChildren") < hook.indexOf("SheetHeroRelay.render"),
                "接力板画在子组件之前，会被弹窗底板整块盖住");

        // 起点在按下那一刻量，落点由卡片自己认领。
        String nav = read(NCM.resolve("panels").resolve("NavigateBar.java"));
        assertTrue(nav.contains("openMusicSourceManager(SheetHeroRelay.from(sourceMenu.getX()"),
                "来源按钮不再交出起点：接力整趟放弃");
        assertTrue(read(ProjectPaths.sourceFile(Paths.get("com/muoniumplayer/core/screens/ncm", "MusicSourceOverlay.java"))).contains("hero(card)"),
                "音乐来源没有标出落点卡片");
        assertTrue(read(ProjectPaths.sourceFile(Paths.get("com/muoniumplayer/core/screens/ncm", "AccountManagerOverlay.java"))).contains("hero(card)"),
                "账号管理没有标出落点卡片");
        assertTrue(read(NCM.resolve("panels").resolve("MusicWidget.java"))
                        .contains("openAddToPlaylist(music, SheetHeroRelay.from("),
                "歌曲行的「+」不再交出起点");

        // 起点必须在 onInit 之前交出去 —— 那时弹窗骨架还没建。
        String screen = read(ProjectPaths.sourceFile(Paths.get("com/muoniumplayer/core/screens/ncm", "NCMScreen.java")));
        for (String opener : new String[]{"openAccountManager(SheetHeroRelay.Origin heroFrom)",
                "openMusicSourceManager(SheetHeroRelay.Origin heroFrom)",
                "openAddToPlaylist(Music music, SheetHeroRelay.Origin heroFrom)"}) {
            String body = body(screen, "public void " + opener);
            int claim = body.indexOf("claimHero(heroFrom)");
            int init = body.indexOf("onInit()");
            assertTrue(claim >= 0 && (init < 0 || claim < init),
                    opener + " 没有在建骨架之前交出起点");
        }
    }

    @Test
    @DisplayName("重写 dispose() 的浮层必须把关闭请求转给时钟，否则那一层永远关不掉")
    void everyDisposeOverrideKeepsTheClock() throws IOException {
        // 公告页原来就是这么坏的：它自己置 closing，绕开了「时钟开始倒着走」那一步，
        // 于是 shouldClose() 读的 clock.isGone() 永远不为真。
        for (String name : allOverlaySources()) {
            String source = read(NCM.resolve(name));
            int at = source.indexOf("public void dispose()");
            if (at < 0) continue;
            String body = source.substring(at, Math.min(source.length(), at + 400));
            body = body.substring(0, body.indexOf('}', body.indexOf('{')) + 1);
            assertTrue(body.contains("super.dispose()") || body.contains("clock.requestDismiss()"),
                    name + " 重写了 dispose() 却没把关闭请求交给时钟：这一层会再也关不掉");
        }
    }

    @Test
    @DisplayName("页眉字形仍挂在它自己的圆盘上：veil 只换透明度，不许顺手换坐标系")
    void headerGlyphStaysOnItsPlate() throws IOException {
        // veil 那一层是 setPosition(0,0) + 宿主尺寸的，所以「相对弹窗」的数搬进去不变；
        // 但页眉那个字形给的是圆盘内的中心 (17,17)，一旦父容器从 plate 换成 crown，
        // 这两个数就变成弹窗里的 (17,17) —— 字形跑到盘子左上方外面，账号菜单和音源菜单同时错位。
        String crown = body(read(ProjectPaths.sourceFile(Paths.get("com/muoniumplayer/core/screens/ncm", "NcmSheet.java"))), "protected void header(");
        assertTrue(crown.contains("icon(plate, glyph"),
                "页眉字形不再挂在圆盘上：那两个 17 是盘子的中心，换父容器就等于把它挪出盘子");
        assertFalse(crown.contains("icon(crown, glyph"),
                "页眉字形被搬到 crown 坐标系，会脱离它的 34x34 圆盘");
    }

    private static String[] allOverlaySources() {
        String[] all = new String[SHEETS.length + STANDALONE.length + 1];
        all[0] = "NcmSheet.java";
        System.arraycopy(SHEETS, 0, all, 1, SHEETS.length);
        System.arraycopy(STANDALONE, 0, all, 1 + SHEETS.length, STANDALONE.length);
        return all;
    }

    private static String[] own(final String... names) {
        return names;
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
