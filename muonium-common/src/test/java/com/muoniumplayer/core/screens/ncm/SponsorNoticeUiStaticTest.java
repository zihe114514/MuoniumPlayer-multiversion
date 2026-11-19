package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SponsorNoticeUiStaticTest {
    private static final Path JAVA = ProjectPaths.sharedJava();
    private static final Path NCM = JAVA.resolve("com/muoniumplayer/core/screens/ncm");

    @Test
    void startupCountsOnceThroughSharedExtensionNotPlayerOpenOrRender() throws IOException {
        String extension = Files.readString(JAVA.resolve("com/muoniumplayer/core/MuoniumPlayerExtension.java"));
        String screen = read("NCMScreen.java");
        assertTrue(body(extension, "public void init(OpenAPI api)").contains("SponsorNotice.beginSession()"));
        assertFalse(screen.contains("SponsorNotice.beginSession()"));
        assertFalse(body(screen, "public void initGui()").contains("new SponsorNoticeOverlay"));
    }

    @Test
    void modelPromptWinsAndSponsorWaitsForFullySettledIdlePlayer() throws IOException {
        String screen = read("NCMScreen.java");
        assertTrue(screen.indexOf("this.maybeShowModelNotice();") < screen.indexOf("this.maybeShowSponsorNotice();"));
        assertTrue(body(screen, "private void maybeShowModelNotice()").contains("this.sponsorDeferredForHost = true"));
        String mount = body(screen, "private void maybeShowSponsorNotice()");
        for (String gate : new String[]{"this.sponsorDeferredForHost", "isContentLayoutReady()", "this.dirty", "this.closing",
                "this.musicLyricsPanel != null", "isModalOverlayActive()", "this.prevAnimatingPanel != null",
                "this.panelSoloFadeActive", "this.navigationLoading.isVisible()", "this.themeTransitionRenderer.isActive()",
                "this.playQueuePanel.isVisible()", "this.pendingClickActive", "Mouse.isButtonDown(0)", "SponsorNotice.shouldShow()"}) {
            assertTrue(mount.contains(gate), "missing sponsor presentation gate: " + gate);
        }
        assertTrue(mount.indexOf("SponsorNotice.snapshot()") < mount.indexOf("SponsorNotice.markShownThisSession()"));
        assertTrue(mount.indexOf("overlay.onInit()") < mount.indexOf("SponsorNotice.markShownThisSession()"));
        assertTrue(mount.contains("snapshot.canHideForever()"));
    }

    @Test
    void sponsorOwnsAllModalInputAndIsReleasedOnlyAfterExitOrHostRemoval() throws IOException {
        String screen = read("NCMScreen.java");
        assertTrue(body(screen, "public boolean isModalOverlayActive()").contains("this.sponsorNoticeOverlay != null"));
        assertTrue(body(screen, "private boolean effectsWorkbenchOwnsInput()").contains("this.sponsorNoticeOverlay == null"));
        assertTrue(body(screen, "private void dispatchClick(").contains("this.sponsorNoticeOverlay.onMouseClickReceived"));
        assertTrue(body(screen, "public void keyTyped(").contains("this.sponsorNoticeOverlay.handleEscape()"));
        assertTrue(screen.contains("this.sponsorNoticeOverlay.renderWidget(mouseX, mouseY, dWheel)"));
        String removal = body(screen, "if (this.sponsorNoticeOverlay.shouldClose())");
        assertTrue(removal.contains("this.sponsorNoticeOverlay.releaseResources()"));
        assertTrue(removal.contains("this.pendingClickActive = false"));
        assertTrue(body(screen, "public void mouseReleased(").contains("clickOwner != this.sponsorNoticeOverlay"));
        // 2026-11-18 下载管理器与赞助弹窗共享同一个触摸独占作用域（模态层优先级：
        // downloadManager > sponsor）——断言改为校验 restrictTouchInputTo 的实参仍
        // 同时覆盖两者，防止后续重构把赞助弹窗从独占作用域里漏掉。
        assertTrue(screen.contains("ScrollPanel.restrictTouchInputTo("));
        assertTrue(screen.contains("this.downloadManagerOverlay != null ? this.downloadManagerOverlay : this.sponsorNoticeOverlay"));
        assertTrue(body(screen, "public void onGuiClosed()").contains("this.sponsorNoticeOverlay.onHostClosed()"));
        assertFalse(body(screen, "public void onGuiClosed()").contains("SponsorNotice.beginSession()"));
    }

    @Test
    void popupUsesSharedMotionAndPairedLayoutAndSafeScrollHitRegions() throws IOException {
        String overlay = read("SponsorNoticeOverlay.java");
        for (String contract : new String[]{"extends NCMPanel", "SheetClock.cascade()", "clock.isGone()", "clock.requestDismiss()",
                "NcmExpressive.shell(", "layout.render(", "layout.dispatch(", "insideViewport(x, y)",
                "return acceptsInput() && testHovered(x, y)", "if (!acceptsInput()) return;", "readReserve()"}) {
            assertTrue(overlay.contains(contract), "missing popup contract: " + contract);
        }
        assertTrue(overlay.contains("if (resolved || (never && !repeat)) return"));
        assertTrue(overlay.contains("neverButton.setHidden(!repeat)"));
        assertTrue(overlay.contains("if (never) forever.run();\n        else later.run();"));
    }

    @Test
    void imagesAreBundledContainAndLinksUseTheExistingWhitelistAndExplicitActions() throws IOException {
        String overlay = read("SponsorNoticeOverlay.java");
        assertTrue(overlay.contains("https://afdian.com/a/xinshibaobao"));
        assertTrue(overlay.contains("https://melodifymc.cc.cd/sponsor.html#wechat"));
        assertTrue(overlay.contains("images.requestResource(channel.resource)"));
        assertTrue(overlay.contains("images.drawResource(channel.resource"));
        assertTrue(overlay.contains("6, true, getAlpha() * reveal"));
        assertTrue(overlay.contains("AnnouncementChecker.isLinkAllowed(url)"));
        assertTrue(overlay.contains("BrowserUtils.open(url)"));
        assertTrue(overlay.contains("KeyboardUtils.setClipboardString(url)"));
        assertFalse(overlay.contains("images.prime("));
        assertFalse(overlay.contains("images.request("));
        assertFalse(overlay.contains("已付款"));
    }

    private static String read(String file) throws IOException { return Files.readString(NCM.resolve(file)); }
    private static String body(String source, String signature) {
        int signatureAt = source.indexOf(signature);
        assertTrue(signatureAt >= 0, "missing " + signature);
        int start = source.indexOf('{', signatureAt), depth = 1;
        for (int i = start + 1; i < source.length(); i++) {
            if (source.charAt(i) == '{') depth++;
            if (source.charAt(i) == '}' && --depth == 0) return source.substring(start + 1, i);
        }
        throw new AssertionError("unbalanced " + signature);
    }
}
