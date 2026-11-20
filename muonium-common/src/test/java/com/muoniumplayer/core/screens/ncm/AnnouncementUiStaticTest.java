package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 公告中心离线结构契约；不初始化界面、HTTP、原生图片或游戏宿主。 */
class AnnouncementUiStaticTest {
    private static final String[] TREES = {
            "1.20.1 recode", "1.21.1", "1.21.10", "1.21.11 recode", "26.1.2", "26.2", "26.3"};
    private static final String SCREENS = "com/muoniumplayer/core/screens/";
    private static final Path NCM = ProjectPaths.sharedJava().resolve(SCREENS + "ncm");

    @Test
    @DisplayName("三个专栏各读不可变快照，不把赞助或交流群混进未读公告")
    void threeSectionsHaveTheirOwnSnapshotLists() throws IOException {
        String source = overlay();
        assertTrue(source.contains("extends NCMPanel"));
        assertFalse(source.contains("extends NcmSheet"));
        assertTrue(Pattern.compile("TABS\\s*=\\s*\\{\\s*\"公告\"\\s*,\\s*\"赞助\"\\s*,\\s*\"交流群\"\\s*}")
                .matcher(source).find(), "三个专栏及顺序是内容中心的公开界面契约");
        String entries = body(source, "private List<Announcement> entriesFor(");
        for (String list : new String[]{"snapshot.announcements()", "snapshot.sponsorships()", "snapshot.communities()"}) {
            assertTrue(entries.contains(list), "专栏缺少数据入口: " + list);
        }
        assertTrue(source.contains("AnnouncementChecker.snapshot()"));
        assertTrue(source.contains("motion.select(section)"));
    }

    @Test
    @DisplayName("浮层只请求图片缓存，HTTP 与文件 IO 不进渲染界面")
    void overlayDoesNotPerformNetworkOrFileIo() throws IOException {
        String source = overlay();
        for (String forbidden : new String[]{"HttpUtils", "HttpURLConnection", "URLConnection",
                "HttpClient", "java.net.", "java.nio.file.", "FileInputStream", "Image.makeFromEncoded(",
                ".openConnection(", ".openStream("}) {
            assertFalse(source.contains(forbidden), "界面不能自行取源/解码: " + forbidden);
        }
        assertTrue(source.contains("AnnouncementImageCache images = new AnnouncementImageCache()"));
        assertTrue(source.contains("images.request(") && source.contains("images.draw("));
        assertTrue(source.contains("AnnouncementChecker.isLinkAllowed("));
        String cardLayout = body(source, "private void measureCard(");
        assertTrue(cardLayout.contains("view.hasImage = art"));
        assertTrue(cardLayout.contains("view.textRail.setHidden(art)"));
        assertTrue(cardLayout.contains("view.art.setHidden(!art)"));
        assertTrue(cardLayout.contains("view.bodyTop = art ?"));
        assertFalse(cardLayout.contains("? 220 : 174"), "media height must follow available width");
        assertTrue(source.contains("DESKTOP_ASPECT = 4.0 / 3.0"));
        String dialogLayout = body(source, "private void measure()");
        assertTrue(dialogLayout.contains("Math.min(DESKTOP_ASPECT, maxWidth / maxHeight)"));
        assertTrue(dialogLayout.contains("width / aspect"));
        assertTrue(source.contains("BrowserUtils.open(") && source.contains("KeyboardUtils.setClipboardString("));
    }

    @Test
    @DisplayName("图片归浮层稳定拥有，退场请求不销毁，实际移除才关闭")
    void imagesStayOwnedAcrossSectionChangesAndDismissAnimation() throws IOException {
        String source = overlay();
        assertEquals(1, count(source, "new AnnouncementImageCache("), "每个浮层只有一个图片拥有者");
        assertTrue(source.contains("private final AnnouncementImageCache images"));
        String dispose = body(source, "public void dispose()");
        assertTrue(dispose.contains("AnnouncementChecker.markAllRead()"));
        assertTrue(dispose.contains("clock.requestDismiss()"));
        assertFalse(dispose.contains("images.close()") || dispose.contains("releaseResources()"),
                "收起仍会绘制图片，不能在退场请求时销毁");
        String release = body(source, "public void releaseResources()");
        assertTrue(release.contains("if (released) return") && release.contains("released = true"));
        assertTrue(release.contains("images.close()"));
        assertEquals(1, count(source, "images.close()"));
        for (String signature : new String[]{"private void measure()", "private void selectSection(", "private void measureCard("}) {
            String method = body(source, signature);
            assertFalse(method.contains("images.close()") || method.contains("releaseResources()"),
                    "排版/切专栏不能替换或释放图片拥有者: " + signature);
        }
        assertTrue(body(source, "private void ensureCards(").contains("while (cards.size() < count)"));
        assertFalse(body(source, "private void measure()").contains("cards.clear()"),
                "每帧重建卡片会丢掉图片渐入与稳定组件身份");
    }

    @Test
    @DisplayName("全文折行不封顶，折叠只影响显示行，不破坏完整正文")
    void fullBodyIsWrappedBeforeTheFoldAndNeverCapped() throws IOException {
        String source = overlay();
        assertFalse(Pattern.compile("\\bMAX_(?:BODY_)?LINES\\b").matcher(source).find(),
                "展开全文不许再受旧十四行上限截断");
        String measure = compact(body(source, "private void measureCard("));
        assertTrue(measure.contains("NcmExpressive.wrap(font,data.body(),view.textWidth)"),
                "必须对真实完整正文调用共享分段折行");
        assertTrue(measure.contains("view.expanded?view.full.length:Math.min(FOLDED_LINES,view.full.length)"));
        assertTrue(measure.contains("newString[count]") && measure.contains("System.arraycopy(view.full,0,view.lines,0,count)"));
        assertTrue(source.contains("展开全文") && source.contains("收起正文"));
        assertTrue(body(source, "private void ensureLines(").contains("while (view.labels.size() < count)"));
        for (String cacheKey : new String[]{"data.body().equals(view.source)", "font != view.font",
                "revision != view.revision", "view.wrappedWidth != view.textWidth"}) {
            assertTrue(body(source, "private void measureCard(").contains(cacheKey), "正文折行缓存缺失失效维度: " + cacheKey);
        }
    }

    @Test
    @DisplayName("绘制与点击成对走实际矩阵导出的 Expressive 变换")
    void renderAndDispatchUseTheSameLayoutTransform() throws IOException {
        String source = overlay();
        assertTrue(body(source, "public void renderWidget(").contains("layout.render(this, mouseX, mouseY, dWheel"));
        assertTrue(body(source, "public void onMouseClickReceived(").contains("layout.dispatch(this, mouseX, mouseY, button"));
        String layout = read(NCM.resolve("NcmExpressiveLayout.java"));
        String render = body(layout, "public void render(");
        assertTrue(render.contains("getLocalToDeviceAsMatrix33()"), "密度来自真实画布矩阵，不猜 GUI 档位");
        assertTrue(render.contains("SkijaGfx.scale(scale, scale)"));
        assertTrue(render.contains("toLogicalX(mouseX)") && render.contains("toLogicalY(mouseY)"));
        assertTrue(render.contains("withCoordinateSpace(originX, originY, scale"));
        assertTrue(render.contains("finally") && render.contains("SkijaGfx.popMatrix()"));
        String dispatch = body(layout, "public void dispatch(");
        assertTrue(dispatch.contains("if (!rendered) return") && dispatch.contains("toLogicalX(mouseX)")
                && dispatch.contains("toLogicalY(mouseY)"));
        assertFalse(dispatch.contains("getLocalToDeviceAsMatrix33") || dispatch.contains("getGuiScale"),
                "事件阶段必须复用上次绘制映射，不能没有矩阵还重新推密度");
    }

    @Test
    @DisplayName("专栏切换只在零透明度换数据，过渡时内容点击与滚轮均禁用")
    void sectionInputCannotMutateOutgoingOrIncomingContent() throws IOException {
        String source = overlay();
        String measure = body(source, "private void measure()");
        int tick = measure.indexOf("motion.tick(");
        int entries = measure.indexOf("entries = entriesFor(motion.visible())");
        assertTrue(tick >= 0 && entries > tick, "旧内容收完后才把新专栏数据交给排版");
        assertTrue(source.contains("!closing && motion.acceptsInput()"));
        assertTrue(measure.contains("content.setClickable(motion.acceptsInput())"));
        String wheel = body(source, "public void onDWheelReceived(");
        assertTrue(wheel.contains("closing || !motion.acceptsInput()"));
        assertTrue(body(source, "private void openLink(").contains("closing || !motion.acceptsInput()"));
        assertTrue(source.contains("content.setAlpha((float) motion.alpha())"));
    }

    @Test
    @DisplayName("七树保留强制布局轴和现有 MC 宿主调用，触屏推进先于渲染与点按")
    void allSevenHostsKeepTheirPairedForcedTransformAndProjectTouchBridge() throws IOException {
        for (String tree : TREES) {
            String host = host(tree);
            Path versionJava = ProjectPaths.repoRoot().resolve(tree).resolve("src/main/java");
            String compatScreen = read(versionJava.resolve("com/muoniumplayer/compat/mc/GuiScreen.java"));
            String scroll = body(compatScreen, "public boolean mouseScrolled(");
            if (tree.equals("26.3")) {
                assertFalse(scroll.contains("Mouse.pushScreenScrollNotches"), tree + " uses SDL MouseHandler mixin instead");
                String mouseMixins = Files.readString(ProjectPaths.repoRoot().resolve(tree)
                        .resolve("fabric/src/main/resources/muonium.mixins.json"), StandardCharsets.UTF_8);
                assertTrue(mouseMixins.contains("\"MouseScrollMixin\""), "26.3 Fabric must retain its scroll source Mixin");
            } else {
                assertTrue(scroll.contains("Mouse.pushScreenScrollNotches"), tree + " GLFW Screen callback must feed the wheel fallback");
                String mouse = read(versionJava.resolve("com/muoniumplayer/compat/lwjgl2/Mouse.java"));
                String callback = body(mouse, "private static void installScrollHookIfNeeded()");
                assertTrue(callback.contains("WHEEL_CALLBACK_GUARD.forwardToScreen("), tree + " original callback path must suppress fallback duplication");
                assertTrue(body(mouse, "public static void pushScreenScrollNotches(").contains("shouldAccumulateScreenFallback()"), tree);
            }
            String draw = body(host, "public void drawScreen(");
            assertTrue(draw.contains("WindowAdaptiveScale.beginForced()") && draw.contains("WindowAdaptiveScale.end()"), tree);
            assertTrue(draw.contains("SkijaGfx.scale(sx, sy)") && draw.contains("SkijaGfx.popMatrix()"), tree);
            assertTrue(draw.contains("Math.round(mouseX / sx)") && draw.contains("Math.round(mouseY / sy)"), tree);
            assertTrue(draw.contains("overlay.renderWidget(") && draw.contains("Mouse.getDWheel()"), tree);
            assertTrue(draw.contains("mouseX / sx, mouseY / sy, Mouse.isButtonDown(0)"), tree);
            String click = body(host, "protected void mouseClicked(");
            assertTrue(click.contains("TouchSettings.ENABLED && mouseButton == 0")
                    && click.contains("pendingClickX = x") && click.contains("pendingClickY = y"), tree);
            assertTrue(click.contains("Math.round(mouseX / sx)") && click.contains("Math.round(mouseY / sy)"), tree);
            String release = body(host, "protected void mouseReleased(");
            int advance = release.indexOf("TouchGestures.get().advance(");
            int consume = release.indexOf("TouchGestures.get().consumeTap()");
            assertTrue(advance >= 0 && consume > advance && release.indexOf("overlay.onMouseClickReceived(") > consume, tree);
            assertTrue(release.contains("mouseX / sx, mouseY / sy, false"), tree);
            assertFalse(host.contains("Mouse.getX()") || host.contains("Mouse.getY()"), tree + " 不猜裸 framebuffer 鼠标轴");
            assertFalse(host.contains("doesGuiPauseGame()") || host.contains("isPauseScreen()"), tree + " 保留基类暂停语义");
            assertFalse(host.contains("fontRendererObj") || host.contains("drawRect(") || host.contains("new GuiButton("), tree + " 宿主必须保持薄壳");
            String setScreen = tree.equals("26.2") || tree.equals("26.3") ? "this.mc.gui.setScreen(this.parent)" : "this.mc.setScreen(this.parent)";
            assertTrue(body(host, "private void dismiss()").contains(setScreen), tree + " 不改已独立适配的 MC 调用");
        }
    }

    @Test
    @DisplayName("七树实际移除钩子释放图片，外部换屏不漏收，resize 不重建拥有者")
    void allSevenHostsReleaseAfterRemovalIncludingExternalScreenReplacement() throws IOException {
        for (String tree : TREES) {
            String host = host(tree);
            String compat = read(versionJava(tree).resolve("com/muoniumplayer/compat/mc/GuiScreen.java"));
            assertTrue(compat.contains("public void onGuiClosed()"), tree + " 必须确有项目生命周期桥");
            assertTrue(body(compat, "public void removed()").contains("onGuiClosed()"), tree + " 外部换屏必须经过桥");
            assertFalse(host.contains("public void removed()"), tree + " 保留 compat 的 IME/SDL 移除清理");
            String removed = body(host, "public void onGuiClosed()");
            assertTrue(removed.contains("overlay.dispose()") && removed.contains("finally")
                    && removed.contains("overlay.releaseResources()"), tree);
            String dismiss = body(host, "private void dismiss()");
            assertTrue(dismiss.indexOf("setScreen(") < dismiss.indexOf("overlay.releaseResources()"), tree + " 先移除再释放");
            assertTrue(body(host, "public void onClose()").contains("overlay.dispose()"), tree);
            assertFalse(body(host, "protected void keyTyped(").contains("releaseResources()"), tree + " ESC 请求不提前释放");
            assertTrue(body(host, "public void initGui()").contains("if (overlayBuilt)"), tree);
            assertEquals(1, count(host, "new AnnouncementOverlay()"), tree + " resize 不能创建新拥有者");
        }
    }

    @Test
    @DisplayName("各车道仅从自己的鼠标事件来源取得滚轮且 GLFW 回退不重复计数")
    void wheelFallbackMatchesEachVersionInputBackend() throws IOException {
        for (String tree : TREES) {
            Path versionJava = versionJava(tree);
            String compat = read(versionJava.resolve("com/muoniumplayer/compat/mc/GuiScreen.java"));
            String scroll = body(compat, "public boolean mouseScrolled(");
            if (tree.equals("26.3")) {
                assertFalse(scroll.contains("Mouse.pushScreenScrollNotches"), "26.3 SDL 不走 GLFW Screen 回退");
                Path mixin = versionJava.resolve("com/muoniumplayer/mixin/MouseScrollMixin.java");
                String source = read(mixin);
                assertTrue(source.contains("Mouse.pushScrollNotches(yoffset)"), "26.3 原生 onScroll 注入是唯一滚轮源");
                continue;
            }
            assertTrue(scroll.contains("Mouse.pushScreenScrollNotches("), tree + " Screen 事件须作 GLFW 累加器兜底");
            String mouse = read(versionJava.resolve("com/muoniumplayer/compat/lwjgl2/Mouse.java"));
            assertTrue(body(mouse, "public static void pushScreenScrollNotches(")
                    .contains("WHEEL_CALLBACK_GUARD.shouldAccumulateScreenFallback()"), tree);
            assertTrue(body(mouse, "private static void installScrollHookIfNeeded()")
                    .contains("WHEEL_CALLBACK_GUARD.forwardToScreen("), tree + " 同步链回原版回调须避免二次累计");
            if (tree.equals("1.20.1 recode")) {
                assertTrue(scroll.contains("mouseScrolled(double mouseX, double mouseY, double amount)"), tree);
            } else {
                assertTrue(scroll.contains("mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount)"), tree);
            }
        }
    }

    private static String overlay() throws IOException {
        return read(NCM.resolve("AnnouncementOverlay.java"));
    }

    private static Path versionJava(String tree) {
        return ProjectPaths.repoRoot().resolve(tree).resolve("src/main/java");
    }

    private static String host(String tree) throws IOException {
        return read(versionJava(tree).resolve(SCREENS + "GuiAnnouncements.java"));
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), "文件不存在: " + path);
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n")
                .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
    }

    private static String compact(String source) {
        return source.replaceAll("\\s+", "");
    }

    private static int count(String source, String token) {
        int count = 0;
        for (int at = 0; (at = source.indexOf(token, at)) >= 0; at += token.length()) count++;
        return count;
    }

    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "源码里找不到: " + signature);
        int open = source.indexOf('{', start);
        assertTrue(open > start, "签名后面没有方法体: " + signature);
        int depth = 0;
        for (int at = open; at < source.length(); at++) {
            char c = source.charAt(at);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return source.substring(open, at + 1);
        }
        throw new AssertionError("方法体没有闭合: " + signature);
    }
}
