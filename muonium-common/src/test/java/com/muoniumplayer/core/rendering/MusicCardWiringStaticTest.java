package com.muoniumplayer.core.rendering;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class MusicCardWiringStaticTest {
    private static String read(String relative) throws Exception {
        return Files.readString(ProjectPaths.sourceFile(Paths.get("com", "muoniumplayer", relative)));
    }

    @Test
    void sharedHudDelegatesToOneScreenOwnerRatherThanDrawingASecondCard() throws Exception {
        String handler = read("core/MuoniumPlayerEventHandler.java");
        String island = read("core/rendering/DownloadDynamicIsland.java");
        assertTrue(handler.contains("DownloadDynamicIsland.renderHudOverlay();"));
        int from = island.indexOf("public static void renderHudOverlay()");
        int to = island.indexOf("public static void render()", from);
        String hud = island.substring(from, to);
        assertTrue(hud.contains("!IslandVisibilityGate.capture().anyScreenOpen"));
        assertTrue(hud.contains("maintainMusicCardContext();"));
    }

    @Test
    void transitionNeverDrawsAnUnconditionalMusicRowAndUsesOneUniformContentScale() throws Exception {
        String renderer = read("core/rendering/MusicCardTransitionSurface.java");
        String island = read("core/rendering/DownloadDynamicIsland.java");
        assertFalse(renderer.contains("drawRow("));
        assertFalse(island.contains("drawExpandedMusicCard("));
        String card = read("core/rendering/MusicCapsuleCard.java");
        assertTrue(renderer.contains("MusicCapsuleCard.INSTANCE.drawCardContent(frame, alpha, snapshot)"));
        assertTrue(card.contains("SkijaGfx.scale(frame.contentScale(), frame.contentScale())"));
        assertTrue(renderer.contains("canvas.clipPath(shape, true)"));
        assertTrue(renderer.contains("canvas.saveLayer(bounds, paint)"));
        assertTrue(renderer.contains("canvas.restoreToCount(layer)"));
        assertTrue(island.contains("MusicCapsuleCard.showCapsuleRow("));
        assertTrue(island.contains("MusicCapsuleCard.presentationReady()"));
        assertTrue(island.contains("musicCardInteraction.animationActive()"), "tail must finish before ownership is released");
    }

    @Test
    void collapsedMusicRowGrowsUntilItsMaximumAndUsesOneCenteredLineWithoutAProgressRail() throws Exception {
        String card = read("core/rendering/MusicCapsuleCard.java");
        int width = card.indexOf("public static double rowTargetWidth()");
        int next = card.indexOf("static int motionTrailColor()", width);
        assertTrue(width > 0 && next > width);
        String sizing = card.substring(width, next);
        assertTrue(sizing.contains("DynamicIslandLayoutCalculator.musicRowWidth(HudConfig.dynamicIslandMaxWidth"));
        assertTrue(sizing.contains("rowTextWidth()"), "short lyrics grow naturally before reaching the ceiling");
        assertTrue(sizing.contains("HudConfig.islandMusicCapsuleMaxWidth"));
        int row = card.indexOf("void drawRow(double x, double y, double width, double height, float alpha,");
        int surface = card.indexOf("public void drawSurface(", row);
        String content = card.substring(row, surface);
        assertTrue(content.contains("ROW_COVER_SIZE"));
        assertTrue(content.contains("textLeft, centerY, textMax"));
        assertFalse(content.contains("MusicCardProgress.draw("));
        assertFalse(content.contains("drawRowPlaybackInfo("));
        assertTrue(content.contains("frameSnapshot.positionMs + (double) CloudMusic.getLyricOffsetMillis()"));
        assertTrue(content.contains("ROW_LYRICS.offset(textMax"));
        assertFalse(content.contains("marqueeOffset("));
        assertFalse(content.contains("System.nanoTime()"));
        assertTrue(card.contains("new float[6]"));
        assertTrue(card.contains("ROW_BARS_WIDTH = 22.0"));
        assertTrue(card.contains("FontManager.pf20"));
        assertTrue(content.contains("font.getGlyphBounds(text)"));
    }

    @Test
    void modelInferenceUsesAllStyleActivityBorderAndCompletionBubble() throws Exception {
        String island = read("core/rendering/DownloadDynamicIsland.java");
        String rules = read("core/rendering/IslandNoticeRules.java");
        assertTrue(island.contains("boolean inferenceBorderActive = !cardOwnsFrame && inferenceStatusActive"));
        assertTrue(island.contains("boolean downloadBorderActive = !cardOwnsFrame && downloadInProgress"));
        assertTrue(island.contains("boolean activityBorderActive = IslandNoticeRules.activityBorderActive("));
        int publisher = island.indexOf("private static void publishNotice(IslandNoticeType type,String title,String value,");
        int inferenceGate = island.indexOf("boolean modelInferenceStatusEnabled", publisher);
        int publishLock = island.indexOf("long now = System.currentTimeMillis();", inferenceGate);
        assertTrue(publisher >= 0 && inferenceGate > publisher && publishLock > inferenceGate);
        assertFalse(island.substring(inferenceGate, publishLock).contains("getStyle()"),
                "inference status is independent of the selected island style");
        int draw = island.indexOf("private void drawIsland(");
        int drawEnd = island.indexOf("private double tickIslandExpansion(", draw);
        String drawBody = island.substring(draw, drawEnd);
        int bounceStart = drawBody.indexOf("if (bounce && mixFrame == null)");
        int mixStart = drawBody.indexOf("if (mixFrame != null)", bounceStart);
        int cardTransitionStart = drawBody.indexOf("if (cardTransitionActive && musicCapsule)", mixStart);
        assertTrue(bounceStart >= 0 && mixStart > bounceStart && cardTransitionStart > mixStart);
        String bounceBranch = drawBody.substring(bounceStart, mixStart);
        String mixBranch = drawBody.substring(mixStart, cardTransitionStart);
        String standardBranch = drawBody.substring(cardTransitionStart);
        assertTrue(bounceBranch.contains("if (activityBorderActive) drawInferenceCapsuleMarquee("));
        assertTrue(bounceBranch.contains("if (completionBubble) drawCapsuleCompletionBubble("));
        assertTrue(mixBranch.contains("if (activityBorderActive) drawInferenceCapsuleMarquee("));
        assertTrue(mixBranch.contains("if (completionBubble) drawCapsuleCompletionBubble("));
        assertTrue(standardBranch.contains("if (activityBorderActive) drawInferenceCapsuleMarquee("));
        assertTrue(standardBranch.contains("if (completionBubble) drawCapsuleCompletionBubble("));
        assertTrue(island.contains("activeNotice && sourceNoticeType == IslandNoticeType.MODEL_INFERENCE"));
        assertTrue(island.contains("IslandNoticeRules.bubblePresentationReady(completionBubble"));
        assertTrue(island.contains("MODEL_INFERENCE_TIMEOUT_MILLIS = 240_000L"));
        assertTrue(island.contains("\"下一首就绪 · \""));
        assertTrue(rules.contains("modelInferenceEnabled"));
        assertTrue(rules.contains("MODEL_INFERENCE_SUCCESS"));
    }

    @Test
    void selectedMusicCapsuleKeepsItsSurfaceWhenNoSongRowExists() throws Exception {
        String island = read("core/rendering/DownloadDynamicIsland.java");
        int musicSurface = island.indexOf("if (musicCapsule && !capsuleRow)", island.indexOf("private void drawIsland("));
        int genericPlate = island.indexOf("boolean frosted = !liquidGlass && !musicCapsule", musicSurface);
        assertTrue(musicSurface > 0 && genericPlate > musicSurface);
        assertTrue(island.substring(musicSurface, genericPlate).contains("drawMusicCapsuleSurface("));
        assertTrue(island.substring(genericPlate, genericPlate + 500).contains("if (liquidGlass)"));
        assertTrue(island.substring(genericPlate, genericPlate + 1_000).contains("else if (!musicCapsule)"),
                "music capsule surface must not fall through to the generic island plate");
    }

    @Test
    void automaticTrackCardsRequireHudOrChatAndAreDroppedOnOtherScreens() throws Exception {
        String gate = read("core/rendering/IslandVisibilityGate.java");
        String island = read("core/rendering/DownloadDynamicIsland.java");
        assertTrue(gate.contains("allowTrackNoticeOverlay(Scene scene, boolean chatOpen, boolean transitioning)"));
        assertTrue(gate.contains("!scene.anyScreenOpen"));
        assertTrue(island.contains("IslandVisibilityGate.allowTrackNoticeOverlay("));
        assertTrue(island.contains("dismissEvent()"));
        assertTrue(island.contains("if (!chat && !INSTANCE.musicCardInteraction.eventLocked())"));
        int detector = island.indexOf("private static void startTrackNoticeIfNeeded()");
        int context = island.indexOf("public static void maintainMusicCardContext()", detector);
        assertFalse(island.substring(detector, context).contains("suspendEvent()"), "new track cannot erase current pose");
        int endContext = island.indexOf("private static boolean musicCardClaimsSurface()", context);
        assertFalse(island.substring(context, endContext).contains("trackNoticeSession = Long.MIN_VALUE"),
                "dropped track identity must survive surface reset to avoid catch-up popups");
    }

    @Test
    void ownershipEdgeCapturesIslandBoundsBeforeTheDetachedCardMoves() throws Exception {
        String island = read("core/rendering/DownloadDynamicIsland.java");
        int draw = island.indexOf("private void drawIsland(");
        int active = island.indexOf("boolean cardTransitionActive =", draw);
        int capture = island.indexOf("boolean ownershipChanged = cardTransitionActive != previousCardTransitionActive;", active);
        int card = island.indexOf("MusicCardTransitionSurface.draw(cardFrame", capture);
        assertTrue(draw >= 0 && active > draw && capture > active && card > capture);
        assertTrue(island.substring(capture, card).contains("liveAnchorLocalWidth"));
        assertTrue(island.substring(capture, card).contains("liveAnchorLocalHeight"));
        assertTrue(island.substring(capture, card).contains("previousCardTransitionActive = cardTransitionActive"));
        assertTrue(island.contains("boolean cardTransitionActive = cardOwnsFrame && musicCardInteraction.animationActive()"),
                "surface priority must not force a second blank transition at an idle closed endpoint");
        assertTrue(island.substring(capture, card).contains("DynamicIslandMath.lerp(cardReturnFromWidth, width, returnBlend)"));
        assertFalse(island.contains("lerp(cardBaseWidth, width, returnBlend)"),
                "return anchor is sampled once, not recursively advanced by draw calls");
        assertFalse(island.contains("lerp(targetExpandedWidth, compactWidth, islandHandoff)"),
                "ordinary island dimensions must track the true return endpoint, not a second shrinking filter");
        assertTrue(island.contains("if (!preview) previousCardTransitionActive = cardTransitionActive"),
                "each closed edge must rearm the next ownership capture");
        assertTrue(island.contains("anchorOriginY"), "centered anchor deformation and the card must share the same origin");
        assertTrue(island.contains("musicCardInteraction.contentReveal()"), "closing content uses its own early vacate phase");
    }

    @Test
    void presentationAndInputUseTheSameHighestPriorityMusicCardOwner() throws Exception {
        String island = read("core/rendering/DownloadDynamicIsland.java");
        int gate = island.indexOf("private static boolean musicCardSurfaceBlockedNow()");
        int input = island.indexOf("public static boolean handleMusicCardMouseButton", gate);
        String blockers = island.substring(gate, input);
        assertFalse(blockers.contains("isDownloadActive()"));
        assertFalse(blockers.contains("noticePriority("));
        assertFalse(blockers.contains("AutomixTransitionAnimation.islandFrame()"));
        assertTrue(blockers.contains("IslandVisibilityGate.allowTrackNoticeOverlay("));
        assertTrue(island.contains("!cardOwnsFrame && enabled && !activeDownload && !holdingCompletion"));
        assertTrue(island.contains("bounce && (!musicCardOwnsSurface || preview)"),
                "queued notices must not change the bounce anchor under an active music card");
        assertTrue(island.contains("if (!musicCardClaimsSurface()) AutomixTransitionAnimation.render();"));
        assertTrue(island.contains("musicCardInteraction.recordPresentation(renderedCardRevision, cardPresented"));
        assertTrue(island.contains("!MusicCapsuleCard.trackTransitionActive()"));
        int draw = island.indexOf("private void drawIsland(");
        int flip = island.indexOf("MusicCapsuleCard.tickTrackTransition(");
        assertTrue(flip > draw, "cover flip belongs to the visible card pass");
    }

    @Test
    void reboundCanBeReopenedThroughTheVisibleAnchorRatherThanTheTransparentCardDot() throws Exception {
        String island = read("core/rendering/DownloadDynamicIsland.java");
        int capture = island.indexOf("MUSIC_CARD_ANCHOR_BOUNDS = anchorFrame");
        int cover = island.indexOf("System.arraycopy(bounds, 0, LIVE_ANCHOR_BOUNDS", capture);
        assertTrue(capture >= 0 && cover > capture);
        assertTrue(island.substring(capture, cover).contains("LIVE_ANCHOR_BOUNDS.clone()"));
        assertTrue(island.substring(capture, cover).contains("cardTransition > MusicCardMotion.JOIN_OPENNESS"));
        int input = island.indexOf("public static boolean handleMusicCardMouseButton");
        int expand = island.indexOf("interaction.expand();", input);
        assertTrue(island.substring(input, expand).contains("!interaction.canInteract() ? MUSIC_CARD_ANCHOR_BOUNDS"));
        assertTrue(island.substring(input, expand).contains("anchorBounds[2], anchorBounds[3], anchorBounds[4]"));
    }

    @Test
    void releasedCardKeepsItsReturnCopyUntilHiddenOrRealContentTakesOver() throws Exception {
        String island = read("core/rendering/DownloadDynamicIsland.java");
        int draw = island.indexOf("private void drawIsland(");
        int capture = island.indexOf("boolean returnContentAvailable =", draw);
        int layout = island.indexOf("IslandLayout layout =", capture);
        assertTrue(capture > draw && layout > capture);
        String handoff = island.substring(capture, layout);
        assertTrue(handoff.contains("!isDownloadActive() && !completing"));
        assertTrue(handoff.contains("cardReleased && returnContentAvailable"));
        assertTrue(handoff.contains("if (returningIsland) ambient = musicCardReturnAmbient"));
        assertFalse(handoff.contains("240L"), "slow configured exits cannot outlive a hard-coded return-copy timeout");
        assertTrue(island.contains("boolean idleWithoutContent = !preview && !musicCapsule && !hasAmbient && returnContentAvailable"));
        assertTrue(island.contains("idleWithoutContent || cardOwnsFrame && !returningAmbient"),
                "an empty restored island may not show a fake downloader spinner");
    }

    @Test
    void closingTailUsesOneClockAndTransfersTheDisplayedAnchorWithoutASecondShrink() throws Exception {
        String state = read("core/rendering/MusicCardInteraction.java");
        String motion = read("core/rendering/MusicCardMotion.java");
        String island = read("core/rendering/DownloadDynamicIsland.java");
        assertFalse(state.contains("REBOUND_DURATION_MILLIS"), "a second tail clock must not outlive the closing geometry");
        assertFalse(state.contains("reboundAt("));
        assertFalse(motion.contains("anchorWidth *="), "closing geometry cannot reverse with a separate impact wave");
        assertFalse(motion.contains("anchorHeight *="));
        assertTrue(island.contains("cardOwnsFrame ? IslandAmbientStatus.expansionTarget(true, true, alwaysOn && !ambient.isEmpty())"),
                "background island expansion must already target the resting state before ownership releases");
        assertTrue(island.contains("expandedWidth = liveAnchorLocalWidth"));
        assertTrue(island.contains("expandedHeight = liveAnchorLocalHeight"));
        assertTrue(island.replace("\r\n", "\n").contains("if (returningIsland) {\n            width = expandedWidth;\n            height = expandedHeight;"),
                "released anchor must not be shrunk again by the generic expansion filter");
        assertTrue(island.contains("preview || !primed || returningIsland || cardTransitionActive"),
                "bounce style cannot layer its spring onto the music-card restoration");
    }

    @Test
    void preparingCardsOpenBeforeArtworkAndUseOnePresentationIdentityWithoutOldTrackControls() throws Exception {
        String state = read("core/rendering/MusicCardInteraction.java");
        String card = read("core/rendering/MusicCapsuleCard.java");
        String island = read("core/rendering/DownloadDynamicIsland.java");
        String cloud = read("core/ncm/music/CloudMusic.java");
        assertFalse(state.contains("!trackCoverReady && openness"), "cover readiness must not gate initial geometry");
        assertTrue(state.contains("!trackReady || openness < .995"), "hold still waits for a ready visible track");
        assertFalse(island.contains("!blocked && visibility > .75f"), "card cannot wait on a second island-entry threshold");
        assertTrue(island.contains("INSTANCE.trackNoticeRevision != MusicCapsuleCard.noticeRevision()"));
        assertTrue(card.contains("CloudMusic.getIslandSnapshot()"));
        assertTrue(card.contains("!snapshot.preparing && !frameSnapshot.preparing"));
        assertTrue(card.contains("if (frameSnapshot.preparing || snapshot.durationMs <= 0L"));
        assertTrue(card.contains("正在准备播放"));
        assertTrue(cloud.contains("this.positionMs = preparing ? 0L : positionMs"));
        assertTrue(cloud.contains("this.player = preparing ? null : player"));
        assertTrue(cloud.replace("\r\n", "\n").contains("finally {\n                preparingSong = false"));
    }

    @Test
    void transitionContentIsTraversedOnceAndUsesTheOuterSurfaceClip() throws Exception {
        String renderer = read("core/rendering/MusicCardTransitionSurface.java");
        String card = read("core/rendering/MusicCapsuleCard.java");
        assertTrue(renderer.contains("motion.blurMix() * .20"));
        int moving = renderer.indexOf("drawDirectionalContent(canvas, frame, contentAlpha");
        int branchEnd = renderer.indexOf("} finally {", moving);
        assertFalse(renderer.substring(moving, branchEnd).contains("drawContent(frame"),
                "moving content must not be traversed again for the sharp pass");
        assertTrue(renderer.contains("MusicCapsuleCard.INSTANCE.drawCardContent(frame, alpha, snapshot)"));
        assertTrue(card.contains("MusicCardSurface.paint(canvas, shape"), "borrow the existing path instead of rebuilding it");
    }

    @Test
    void cardOwnershipIsAppliedAfterTheCurrentFrameStateUpdate() throws Exception {
        String island = read("core/rendering/DownloadDynamicIsland.java");
        int tick = island.indexOf("tickMusicCardState(surfaceBlocked");
        assertTrue(island.contains("boolean shown = cardOwnsFrame"));
        assertTrue(tick >= 0 && tick < island.indexOf("final boolean cardOwnsFrame =", tick));
        assertFalse(island.contains("boolean shouldShow ="), "stale music-card ownership must not decide visibility");
    }
}
