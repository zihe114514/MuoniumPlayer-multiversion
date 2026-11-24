package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.testsupport.ProjectPaths;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt wiring contracts, not geometry/copy snapshots. Parse only: no symbol resolution,
 * Minecraft/GL initialization, network calls, configuration writes or installer execution.
 * Comments and quoted examples cannot satisfy call/field assertions.
 */
class ExpressivePromptsStaticTest {
    private static final String NCM = "com/muoniumplayer/core/screens/ncm/";
    private static final String MUSIC = "com/muoniumplayer/core/ncm/music/";
    private static final String[] PROMPTS = {"ConfirmationOverlay", "AddToPlaylistOverlay", "UpdateNoticeOverlay",
            "FfmpegNoticeOverlay", "ModelInstallNoticeOverlay", "PlaylistImportOverlay", "PlaylistCreateSheet"};

    @Test
    void promptsUseSharedComponentsWithoutGuessedHoverApiOrTinyBodyFont() throws IOException {
        for (String name : PROMPTS) {
            Source source = screen(name);
            assertTrue(calls(source.type).stream().anyMatch(call -> call.getMethodSelect().toString().startsWith("NcmExpressive.")),
                    name + " must use the shared expressive components");
            assertNoLegacySymbols(source.type, name);
        }
        assertNoLegacySymbols(screen("NcmExpressive").type, "NcmExpressive");
    }

    @Test
    void semanticStatusInkIsAdjustedAgainstTheSurfaceActuallyDrawn() throws IOException {
        MethodTree status = screen("NcmExpressive").method("status");
        // Distinguish the component factory from palette.surface(), which is a color getter.
        MethodInvocationTree surface = calls(status).stream().filter(call ->
                call.getMethodSelect().toString().equals("surface")
                        || call.getMethodSelect().toString().equals("NcmExpressive.surface")).findFirst().orElseThrow();
        String supplier = compact(surface.getArguments().get(surface.getArguments().size() - 1));
        assertTrue(compact(status).contains("NcmExpressivePalette.mix("), "Semantic feedback must use the real mixed surface");
        for (String component : new String[]{"icon", "label"}) {
            MethodInvocationTree paint = oneCall(status, component);
            Tree ink = paint.getArguments().get(paint.getArguments().size() - 1);
            MethodInvocationTree readable = oneCall(ink, "readableColor");
            assertEquals(supplier + ".getAsInt()", compact(readable.getArguments().get(1)),
                    "Status " + component + " must test contrast against its actual background supplier");
        }
    }

    @Test
    void parserIgnoresQuotedAndCommentedForbiddenExamples() throws IOException {
        Source example = parse("class Example { String text = \"getHovering() FontManager.pf12\"; "
                + "/* image.setHidden(true); */ void draw() { boolean hovering = isHovering(); } }", "Example");
        assertNoLegacySymbols(example.type, "Example");
        assertArtworkIsNotSelfHidden(example.type, "Example");
        assertEquals(1, calls(example.type).size(), "Quoted/commented calls must not be mistaken for executable code");
    }

    @Test
    void sharedBodyWrappingMeasuresCandidateWidthWithoutDroppingCodePoints() throws IOException {
        MethodTree wrap = screen("NcmExpressive").method("wrap");
        String code = compact(wrap);
        // A punctuation-reserve measurement is also valid; locate the actual candidate-width guard.
        MethodInvocationTree measured = calls(wrap).stream().filter(call -> callName(call).equals("getStringWidthD")
                        && call.getArguments().size() == 1
                        && compact(call.getArguments().get(0)).equals("line.substring(start,next)"))
                .findFirst().orElseThrow(() -> new AssertionError("Measure the full next candidate before accepting it"));
        assertTrue(ifs(wrap).stream().anyMatch(branch -> calls(branch.getCondition()).contains(measured)
                        && compact(branch.getCondition()).contains(">width")
                        && branch.getThenStatement().getKind() == Tree.Kind.BREAK),
                "An overwide candidate must break to a new line, not get ellipsized by the label");
        assertTrue(code.contains("Character.charCount(line.codePointAt(end))"), "Never split a UTF-16 surrogate pair");
        assertTrue(code.contains("lines.add(line.substring(start,end))") && code.contains("start=end;"),
                "Emit every accepted character and resume at the exact end, with no skipped punctuation");
        assertFalse(calls(wrap).stream().anyMatch(call -> List.of("trim", "replace", "replaceAll", "remove").contains(callName(call))),
                "The shared wrap seam must not discard text to make a line fit");
        for (String name : new String[]{"ConfirmationOverlay", "UpdateNoticeOverlay", "FfmpegNoticeOverlay",
                "ModelInstallNoticeOverlay", "PlaylistImportOverlay", "PlaylistCreateSheet"}) {
            Source source = screen(name);
            assertTrue(calls(source.type).stream().anyMatch(call -> call.getMethodSelect().toString().equals("NcmExpressive.wrap")),
                    name + " must route body/error wrapping through the measured no-drop seam");
            assertFalse(calls(source.type).stream().anyMatch(call -> callName(call).equals("fitWidth")),
                    name + " must not bypass the punctuation-width correction");
        }
    }

    @Test
    void playlistRowsUseRealNamesCountsAndProactivelyLoadTheSameCoverKey() throws IOException {
        Source source = screen("AddToPlaylistOverlay");
        ClassTree row = source.nested("PlaylistRow");
        String rowCode = compact(row);
        assertTrue(rowCode.contains("getPlaylistName(playlist)"), "The row must display the selected DTO's name");
        assertTrue(rowCode.contains("playlist.getCount()") && rowCode.contains("playlist.musics.size()"),
                "Cloud counts and actual local track counts must both remain represented");
        assertTrue(compact(source.method("getPlaylistName")).contains("returnplaylist.getName();"));
        assertTrue(calls(row).stream().anyMatch(call -> callName(call).equals("requestPlaylistCover")),
                "Cover requests must originate when rows are built, not after visiting another playlist page");
        assertTrue(creations(row, "RoundedImageWidget").stream().anyMatch(creation ->
                creation.getArguments().stream().anyMatch(arg -> compact(arg).equals("playlist.getCoverLocation()"))),
                "The image must use the real playlist cover cache key");
        MethodTree request = source.method("requestPlaylistCover");
        String code = compact(request);
        assertTrue(code.contains("playlist.getCoverUrl()") && code.contains("playlist.getCoverLocation()"));
        assertTrue(code.contains("coverUrl==null") && code.contains("coverUrl.trim().isEmpty()"));
        assertTrue(code.contains("LocalPlaylistStore.isLocal(playlist)"), "Local covers must not become HTTP requests");
        assertTrue(code.contains("getTexture(location)") && code.contains("coverRequests.add(location)"),
                "Loaded and in-flight cover keys must be deduplicated");
        MethodInvocationTree download = oneCall(request, "downloadTextureAndLoadAsync");
        assertEquals("Textures.downloadTextureAndLoadAsync", download.getMethodSelect().toString());
        assertEquals(2, download.getArguments().size());
        assertEquals("location", compact(download.getArguments().get(1)), "Request and displayed artwork must share the DTO key");
        assertTrue(code.contains("coverUrl.replace(\"http://\",\"https://\")"), "Use the secure URL, not a synthetic cover asset");
        assertTrue(code.contains("MusicPlatform.QQ") && code.contains("param=256y256") && code.contains("secureUrl.contains(\"?\")"),
                "Keep platform-aware URL sizing without adding a second question mark");
        for (String name : PROMPTS) assertArtworkIsNotSelfHidden(screen(name).type, name);
    }

    @Test
    void lazyPlaylistRefreshRebuildsOnlyTheListOnTheMainThread() throws IOException {
        Source source = screen("AddToPlaylistOverlay");
        MethodTree init = source.method("onInit");
        List<MethodInvocationTree> ensures = calls(init).stream()
                .filter(call -> callName(call).equals("ensureUserPlaylistsAsync")).toList();
        assertEquals(3, ensures.size(), "酷狗、波点与 Spotify 云歌单必须各自懒加载，不能互相覆盖");
        assertTrue(ensures.stream().anyMatch(call -> call.getMethodSelect().toString()
                        .equals("KuGouMusicService.ensureUserPlaylistsAsync"))
                        && ensures.stream().anyMatch(call -> call.getMethodSelect().toString()
                        .equals("BodianMusicService.ensureUserPlaylistsAsync"))
                        && ensures.stream().anyMatch(call -> call.getMethodSelect().toString()
                        .equals("SpotifyMusicService.ensureUserPlaylistsAsync")),
                "Cloud-playlist providers must be wired independently");
        for (MethodInvocationTree ensure : ensures) {
            assertTrue(calls(ensure).stream().anyMatch(call -> callName(call).equals("runOnMainThread")),
                    "The service completion callback must marshal widget mutation to the client thread");
            assertTrue(calls(ensure).stream().anyMatch(call -> callName(call).equals("rebuildPlaylistList")));
            assertTrue(compact(ensure).contains("!closing"),
                    "A delayed cover/list callback must not revive a dismissed overlay");
        }
        String initCode = compact(init);
        assertTrue(initCode.contains("!kuGouCallbackPending") && initCode.contains("kuGouCallbackPending=true;"),
                "The KuGou one-request-per-overlay latch must prevent duplicated callbacks");
        assertTrue(initCode.contains("!bodianCallbackPending") && initCode.contains("bodianCallbackPending=true;"),
                "The Bodian one-request-per-overlay latch must prevent duplicated callbacks");
        assertTrue(compact(init).contains("areUserPlaylistsLoading()") && compact(init).contains("areUserPlaylistsLoaded()"),
                "Observe service state too: a loading service can discard an added callback");
        assertFalse(calls(source.type).stream().anyMatch(call -> callName(call).equals("onInit")),
                "Refreshing lazy account playlists must not reinitialize the whole modal or its clock");
        assertTrue(compact(source.method("rebuildPlaylistList")).contains("playlistList.getChildren().clear()"));
        for (MethodInvocationTree call : calls(source.type)) {
            if (callName(call).equals("clear") && compact(call).contains("getChildren()")) {
                assertEquals("playlistList.getChildren().clear()", compact(call),
                        "Lazy refresh may clear list rows, never the overlay/dialog tree");
            }
        }
    }

    @Test
    void playlistScrollChildrenHavePositiveBoundsBeforeTheirFirstLayout() throws IOException {
        Source source = screen("AddToPlaylistOverlay");
        MethodTree constructor = new Source(source.nested("PlaylistRow")).method("<init>");
        assertSeededBounds(source, constructor, "this", null);
        assertSeededBounds(source, source.method("addTrackPreview"), "preview", "playlistList");
        assertSeededBounds(source, source.method("addTrackPreview"), "heading", "playlistList");
        assertSeededBounds(source, source.method("rebuildPlaylistList"), "empty", "playlistList");
        assertTrue(calls(source.method("rebuildPlaylistList")).stream().anyMatch(call -> callName(call).equals("addTrackPreview")),
                "The seeded preview and heading must actually be inserted on list rebuild");
    }

    @Test
    void selfSizingPlaylistChildrenCannotSilentlyOptIntoCachedLayout() throws IOException {
        Source source = screen("AddToPlaylistOverlay");
        boolean childrenSelfSize = calls(source.type).stream().filter(call -> callName(call).equals("setBeforeRenderCallback"))
                .filter(call -> !receiver(call).equals("playlistList") && !receiver(call).equals("dialog"))
                .anyMatch(call -> calls(call).stream().anyMatch(size -> callName(size).equals("setBounds")));
        boolean usesCachedLayout = calls(source.type).stream().anyMatch(call -> receiver(call).startsWith("playlistList")
                && (callName(call).equals("invalidateLayout") || callName(call).equals("setIndexedVerticalLayout")));
        if (childrenSelfSize && usesCachedLayout) {
            assertTrue(hasCompletePrelayoutSizingAndInvalidation(source.method("onInit"), "playlistList"),
                    "Dynamic child sizing cannot enable ScrollPanel's signature fast path. Before layout, size every child "
                            + "with explicit width AND height, then invalidate after later size/width changes. "
                            + "Copying row.getHeight() is not a complete prelayout size contract.");
        }
    }

    @Test
    void prelayoutGuardRejectsRetainedHeightsAndInvalidationBeforeSizing() throws IOException {
        String prefix = "class Example { void onInit() { playlistList.setBeforeRenderCallback(() -> { ";
        String complete = "for (Widget row : playlistList.getChildren()) { row.setBounds(playlistList.getWidth(), ROW_HEIGHT); }";
        String suffix = " }); } }";
        assertFalse(hasCompletePrelayoutSizingAndInvalidation(parse(prefix
                + "for (Widget row : playlistList.getChildren()) { row.setBounds(playlistList.getWidth(), row.getHeight()); }"
                + "playlistList.invalidateLayout();" + suffix, "Example").method("onInit"), "playlistList"));
        assertFalse(hasCompletePrelayoutSizingAndInvalidation(parse(prefix
                + "playlistList.invalidateLayout();" + complete + suffix, "Example").method("onInit"), "playlistList"));
        assertTrue(hasCompletePrelayoutSizingAndInvalidation(parse(prefix
                + complete + "playlistList.invalidateLayout();" + suffix, "Example").method("onInit"), "playlistList"));
    }

    @Test
    void importAndCreateKeepEditableInputsAndExistingPersistenceRoutes() throws IOException {
        Source imported = screen("PlaylistImportOverlay");
        Source created = screen("PlaylistCreateSheet");
        for (Source source : List.of(imported, created)) {
            assertFalse(creations(source.type, "TextFieldWidget").isEmpty(), "Keep real text fields with native paste/IME");
            assertTrue(compact(source.type).contains("Keyboard.KEY_RETURN") && compact(source.type).contains("Keyboard.KEY_NUMPADENTER"));
            assertTrue(calls(source.type).stream().anyMatch(call -> callName(call).equals("submit")));
        }
        MethodTree paste = imported.method("fillFromClipboard");
        assertTrue(calls(paste).stream().anyMatch(call -> callName(call).equals("setText")));
        assertFalse(calls(paste).stream().anyMatch(call -> callName(call).equals("submit") || callName(call).equals("importPlaylist")),
                "The paste action must fill editable input, not silently import");
        MethodTree submit = imported.method("submit");
        assertTrue(compact(submit).contains("running||folderMode||closing"), "Block duplicate or wrong-mode submissions");
        assertTrue(calls(submit).stream().anyMatch(call -> callName(call).equals("runAsync")));
        MethodInvocationTree worker = oneCall(submit, "runAsync");
        assertTrue(calls(worker).stream().anyMatch(call -> call.getMethodSelect().toString().equals("PlaylistImportService.importPlaylist")));
        MethodInvocationTree publish = oneCall(worker, "runOnMainThread");
        assertTrue(compact(publish).contains("ImportedPlaylistStore.add(imported)") && compact(publish).contains("markDirty()"));
        assertTrue(compact(imported.method("openFolder")).contains("folderImporter.openLocalFolderImport"),
                "Link/folder mode must reuse the actual local folder importer");
        assertTrue(compact(created.method("submit")).contains("LocalPlaylistStore.create(name)"));
        String rename = compact(created.method("renameTo"));
        assertTrue(rename.contains("LocalPlaylistStore.rename(renaming,name)") && rename.contains("replaceCurrentPanel("),
                "Renaming must retain existing tracks and replace, not stack, the current page");
        assertFalse(calls(created.method("renameTo")).stream().anyMatch(call -> callName(call).equals("setCurrentPanel")));
        // The create page also hosts QQ and Bodian cloud branches; keep this
        // privacy assertion scoped to credential storage, not service wiring.
        assertFalse(compact(imported.type).contains("AccountSessionStore"),
                "Anonymous import must not acquire account credentials");
        assertFalse(compact(created.type).contains("AccountSessionStore"),
                "Local creation must not acquire account credentials");
    }

    @Test
    void accountConsentCopyAndDeferredEnablingAreUntouched() throws IOException {
        Source account = screen("AccountManagerOverlay");
        // Intentional privacy contract, not a layout/title snapshot: never soften the original disclosure.
        assertEquals("开启后会上报数据到网易云", stringValue(account.field("SYNC_CONSENT_TITLE").getInitializer()));
        assertEquals("开启后会收集你的电脑唯一标识符，包括但不限于 CPU、系统 OS、网卡、硬盘序列号、"
                        + "IP 地址等信息。信息全部用于上报网易云客户端。",
                stringValue(account.field("SYNC_CONSENT_BODY").getInitializer()));
        assertEquals("我已了解，开启", stringValue(account.field("SYNC_CONSENT_CONFIRM").getInitializer()));
        for (String method : new String[]{"historySyncAction", "maybePromptListeningHistorySync"}) {
            MethodTree entry = account.method(method);
            MethodInvocationTree confirmation = oneCall(entry, "openConfirmation");
            assertEquals(4, confirmation.getArguments().size());
            assertEquals("SYNC_CONSENT_TITLE", compact(confirmation.getArguments().get(0)));
            assertEquals("SYNC_CONSENT_BODY", compact(confirmation.getArguments().get(1)));
            assertEquals("SYNC_CONSENT_CONFIRM", compact(confirmation.getArguments().get(2)));
            Tree callback = confirmation.getArguments().get(3);
            assertTrue(callback instanceof LambdaExpressionTree, "Enabling sync must remain a deferred consent callback");
            assertTrue(compact(callback).contains("applyListeningHistorySync(true)"));
            assertEquals(1L, calls(entry).stream().filter(call -> compact(call).equals("applyListeningHistorySync(true)")).count(),
                    "No second immediate enabling call may accompany the consent dialog");
        }
    }

    @Test
    void confirmationResolvesOnceBeforeCallbackAndCancelNeverConfirms() throws IOException {
        Source source = screen("ConfirmationOverlay");
        MethodTree resolve = source.method("resolve");
        String code = compact(resolve);
        assertTrue(code.contains("if(resolved)return;"));
        assertBefore(code, "resolved=true;", "onConfirm.run()", "Mark resolved before invoking a possibly reentrant callback");
        assertBefore(code, "clock.requestDismiss()", "onConfirm.run()", "Dismiss even if the action throws");
        assertTrue(code.contains("if(confirmed&&onConfirm!=null)onConfirm.run()"));
        assertEquals("resolve(false)", compact(oneCall(source.method("cancel"), "resolve")));
        assertEquals(1L, calls(source.type).stream().filter(call -> compact(call).equals("onConfirm.run()")).count(),
                "No constructor, cancellation or rendering path may execute the pending action");
        assertTrue(compact(source.method("onInit")).contains("resolve(true)"), "Only the confirm action takes the true path");
        assertTrue(compact(source.method("onInit")).contains("this::cancel"), "Outside click and neutral action must cancel");
        assertTrue(compact(source.method("shouldClose")).contains("clock.isGone()"), "Let dismissal finish before disposal");
    }

    @Test
    void updateMaskSkipAndForcedDownloadRemainDistinctExitPolicies() throws IOException {
        Source source = screen("UpdateNoticeOverlay");
        MethodTree init = source.method("onInit");
        MethodInvocationTree maskClick = calls(init).stream().filter(call ->
                call.getMethodSelect().toString().equals("setOnClickCallback")
                        || call.getMethodSelect().toString().equals("this.setOnClickCallback")).findFirst().orElseThrow();
        assertEquals(1, maskClick.getArguments().size());
        assertTrue(maskClick.getArguments().get(0) instanceof LambdaExpressionTree);
        assertEquals("true", compact(((LambdaExpressionTree) maskClick.getArguments().get(0)).getBody()),
                "Outside mask must consume clicks without dismissing or charging a skip");
        String dismiss = compact(source.method("dismissForNow"));
        assertTrue(dismiss.contains("if(forcedUpdate||resolved)return;"));
        assertTrue(dismiss.contains("if(!networkFailure)UpdateChecker.noteSkippedOnce();"));
        assertBefore(dismiss, "noteSkippedOnce()", "cancel()", "A user skip is recorded once, before the shared dismissal guard");
        assertEquals(1L, calls(source.type).stream().filter(call -> callName(call).equals("noteSkippedOnce")).count());
        String cancel = compact(source.method("cancel"));
        assertTrue(cancel.contains("if(forcedUpdate||resolved)return;"));
        assertBefore(cancel, "resolved=true;", "clock.requestDismiss()", "Latch the skip before dismissal frames can receive more ESC input");
        assertTrue(compact(source.method("handleEscape")).contains("dismissForNow()"));
        assertTrue(compact(source.method("blocksClosing")).contains("returnforcedUpdate;"));
        assertTrue(ifs(init).stream().anyMatch(branch -> compact(branch.getCondition()).equals("(!forcedUpdate)")
                        && compact(branch.getThenStatement()).contains("this::dismissForNow")),
                "No secondary skip action may be offered when locked");
        MethodTree download = source.method("openDownloadPage");
        assertTrue(ifs(download).stream().anyMatch(branch -> compact(branch.getCondition()).equals("(forcedUpdate)")
                        && compact(branch.getThenStatement()).contains("return;")
                        && calls(branch.getThenStatement()).stream().noneMatch(call -> callName(call).equals("cancel"))),
                "Opening a locked download page must not close the modal");
        assertTrue(calls(download).stream().anyMatch(call -> callName(call).equals("setClipboardString")), "Browser failure still supplies the link");
        assertTrue(compact(source.method("isAllowedAndBrowsable")).contains("UpdateChecker.isAllowedPage(page)"));
        assertFalse(compact(source.type).contains("escapeHatch"));
        assertFalse(calls(source.type).stream().anyMatch(call -> callName(call).equals("disable")));
    }

    @Test
    void retryWaitsForARealConclusionAndDoesNotOverlapChecks() throws IOException {
        Source source = screen("UpdateNoticeOverlay");
        String retry = compact(source.method("beginRetry"));
        assertTrue(retry.contains("if(resolved)return;"));
        assertTrue(retry.contains("retryPending||!UpdateChecker.retryCheck()"));
        assertBefore(retry, "UpdateChecker.retryCheck()", "retryPending=true;", "Only an accepted background check enters pending state");
        MethodTree poll = source.method("pollRetry");
        assertTrue(compact(poll).contains("if(!retryPending||resolved)return;"));
        assertTrue(ifs(poll).stream().anyMatch(branch -> compact(branch.getCondition()).contains("STATE_UNKNOWN")
                        && compact(branch.getThenStatement()).equals("return;")), "Unknown result must keep waiting");
        assertTrue(ifs(poll).stream().anyMatch(branch -> compact(branch.getCondition()).contains("STATE_CHECK_FAILED")
                        && compact(branch.getThenStatement()).contains("retryPending=false;")
                        && compact(branch.getThenStatement()).contains("return;")
                        && calls(branch.getThenStatement()).stream().noneMatch(call -> callName(call).equals("cancel"))),
                "A failed retry must stay open for another attempt");
        assertBefore(compact(poll), "UpdateChecker.allowNoticeAgain()", "cancel()", "Successful retry lets the host show its new conclusion");
        assertTrue(calls(source.type).stream().anyMatch(call -> callName(call).equals("pollRetry")), "Polling must actually be wired to frames");
    }

    @Test
    void ffmpegCloses2600msAfterVerifiedSuccessWithoutCancellingItsWorker() throws IOException {
        Source source = screen("FfmpegNoticeOverlay");
        String watchdog = compact(source.method("tickInstallWatchdog"));
        assertTrue(watchdog.contains("FfmpegInstaller.state()!=FfmpegInstaller.SUCCESS"));
        assertTrue(watchdog.contains("successSeenAt=System.currentTimeMillis()"));
        assertTrue(watchdog.contains("System.currentTimeMillis()-successSeenAt>=2600L"));
        assertTrue(calls(source.method("tickInstallWatchdog")).stream().anyMatch(call -> callName(call).equals("cancel")));
        assertTrue(calls(source.method("onInit")).stream().anyMatch(call -> callName(call).equals("tickInstallWatchdog")));
        assertTrue(compact(source.method("startInstall")).contains("successSeenAt=-1L") && compact(source.method("startInstall")).contains("FfmpegInstaller.start()"));
        assertCloseIsUiOnly(source);
        Source installer = read(MUSIC + "FfmpegInstaller.java", "FfmpegInstaller");
        MethodTree worker = installer.method("runInstall");
        assertBefore(compact(worker), "FfmpegPackageDownload.unpack(", "FfmpegSupport.forceReprobe()", "Reprobe the unpacked executable, not package existence");
        assertTrue(ifs(worker).stream().anyMatch(branch -> compact(branch.getCondition()).contains("FfmpegSupport.forceReprobe()!=null")
                        && compact(branch.getThenStatement()).contains("finish(true,null)")
                        && compact(branch.getElseStatement()).contains("finish(false,")), "Only a successful real reprobe may publish success");
        assertTrue(compact(installer.method("start")).contains("thread.setDaemon(true)"));
    }

    @Test
    void modelInstallRemainsMissingOnlySequentialAndIndependentOfClosing() throws IOException {
        Source source = screen("ModelInstallNoticeOverlay");
        String begin = compact(source.method("installMissing"));
        assertTrue(begin.contains("if(installing)return;"));
        assertTrue(begin.contains("FoliaModelNotice.missing().isEmpty()"));
        assertBefore(begin, "installing=true;", "installNext()", "Mark busy before beginning the single-component queue");
        MethodTree next = source.method("installNext");
        assertTrue(compact(next).contains("FoliaModelNotice.missing()"), "Re-read actual missing items after each completed download");
        MethodInvocationTree download = oneCall(next, "downloadAsync");
        assertEquals("pending.get(0)", compact(download.getArguments().get(0)), "Start only the first missing component, not every displayed kind");
        Tree callback = download.getArguments().get(1);
        assertTrue(callback instanceof LambdaExpressionTree);
        assertTrue(compact(callback).contains("if(ok)installNext();elseinstalling=false;"), "Advance only after success; failure must allow retry");
        assertFalse(compact(next).contains("closing") || compact(next).contains("resolved"), "Dismissal must not abort the background queue");
        assertEquals(1L, calls(source.type).stream().filter(call -> callName(call).equals("downloadAsync")).count());
        assertCloseIsUiOnly(source);
    }

    private static void assertSeededBounds(Source source, MethodTree method, String widget, String list) {
        List<MethodInvocationTree> synchronous = callsOutsideCallbacks(method.getBody());
        MethodInvocationTree bounds = synchronous.stream().filter(call -> callName(call).equals("setBounds")
                        && receiver(call).equals(widget)).findFirst()
                .orElseThrow(() -> new AssertionError(widget + " must be sized synchronously, not only in its render callback"));
        assertTrue(bounds.getArguments().size() == 2 || bounds.getArguments().size() == 4);
        int width = bounds.getArguments().size() - 2;
        assertTrue(positiveConstant(source, bounds.getArguments().get(width))
                        && positiveConstant(source, bounds.getArguments().get(width + 1)),
                widget + " needs positive initial width and height before ScrollPanel can measure/visit it");
        if (list != null) {
            MethodInvocationTree attach = synchronous.stream().filter(call -> callName(call).equals("addChild")
                    && receiver(call).equals(list) && call.getArguments().stream().anyMatch(arg -> compact(arg).equals(widget)))
                    .findFirst().orElseThrow(() -> new AssertionError(widget + " is not attached to the scroll list"));
            assertTrue(synchronous.indexOf(bounds) < synchronous.indexOf(attach), "Seed " + widget + " bounds before attachment");
        }
    }

    private static boolean positiveConstant(Source source, Tree value) {
        if (value instanceof LiteralTree literal && literal.getValue() instanceof Number number) return number.doubleValue() > 0;
        if (value instanceof IdentifierTree identifier) {
            for (Tree member : source.type.getMembers()) {
                if (member instanceof VariableTree field && field.getName().contentEquals(identifier.getName())) {
                    return field.getInitializer() instanceof LiteralTree literal && literal.getValue() instanceof Number number
                            && number.doubleValue() > 0;
                }
            }
        }
        return false;
    }

    /** Conservative opt-in proof: an unconditional parent-frame loop sizes all children, then invalidates. */
    private static boolean hasCompletePrelayoutSizingAndInvalidation(MethodTree init, String list) {
        for (MethodInvocationTree callback : calls(init)) {
            if (!callName(callback).equals("setBeforeRenderCallback") || !receiver(callback).equals(list)) continue;
            if (callback.getArguments().size() != 1 || !(callback.getArguments().get(0) instanceof LambdaExpressionTree lambda)
                    || !(lambda.getBody() instanceof BlockTree frame)) continue;
            List<? extends Tree> statements = frame.getStatements();
            for (int index = 0; index < statements.size(); index++) {
                if (!(statements.get(index) instanceof EnhancedForLoopTree loop)
                        || !compact(loop.getExpression()).equals(list + ".getChildren()")) continue;
                String child = loop.getVariable().getName().toString();
                List<? extends Tree> body = loop.getStatement() instanceof BlockTree block
                        ? block.getStatements() : List.of(loop.getStatement());
                boolean completeSize = body.stream().filter(statement -> statement instanceof ExpressionStatementTree)
                        .map(statement -> ((ExpressionStatementTree) statement).getExpression())
                        .anyMatch(expression -> expression instanceof MethodInvocationTree size
                                && callName(size).equals("setBounds") && receiver(size).equals(child)
                                && size.getArguments().size() == 2
                                && size.getArguments().stream().noneMatch(argument -> calls(argument).stream().anyMatch(read ->
                                        receiver(read).equals(child) && (callName(read).equals("getHeight") || callName(read).equals("getWidth")))));
                if (!completeSize) continue;
                for (int later = index + 1; later < statements.size(); later++) {
                    if (statements.get(later) instanceof ExpressionStatementTree statement
                            && statement.getExpression() instanceof MethodInvocationTree invalidate
                            && callName(invalidate).equals("invalidateLayout") && receiver(invalidate).equals(list)) return true;
                }
            }
        }
        return false;
    }

    private static String receiver(MethodInvocationTree call) {
        if (!(call.getMethodSelect() instanceof MemberSelectTree select)) return "this";
        String value = compact(select.getExpression());
        return value.startsWith("this.") ? value.substring(5) : value;
    }

    private static List<MethodInvocationTree> callsOutsideCallbacks(Tree tree) {
        List<MethodInvocationTree> found = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            @Override public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) { return null; }
            @Override public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                found.add(node);
                return super.visitMethodInvocation(node, unused);
            }
        }.scan(tree, null);
        return found;
    }

    private static void assertCloseIsUiOnly(Source source) {
        MethodTree close = source.method("cancel");
        assertTrue(compact(close).contains("clock.requestDismiss()"));
        assertFalse(calls(close).stream().anyMatch(call -> {
            String select = call.getMethodSelect().toString();
            return select.contains("Installer") || select.contains("ModelManager") || select.contains("Notice.")
                    || select.contains("Config") || callName(call).equals("interrupt");
        }), "Closing " + source.type.getSimpleName() + " must not stop installation, reset gates or change settings");
    }

    private static void assertNoLegacySymbols(Tree tree, String where) {
        new TreeScanner<Void, Void>() {
            @Override public Void visitIdentifier(IdentifierTree node, Void unused) {
                assertFalse(node.getName().toString().startsWith("pf12"), where + " uses an obsolete tiny font alias");
                return super.visitIdentifier(node, unused);
            }
            @Override public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                assertFalse(node.getIdentifier().contentEquals("getHovering") || node.getIdentifier().toString().startsWith("pf12"),
                        where + " uses a guessed hover API or obsolete tiny font");
                return super.visitMemberSelect(node, unused);
            }
            @Override public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                assertFalse(callName(node).equals("getHovering"), where + " uses a guessed hover API");
                return super.visitMethodInvocation(node, unused);
            }
        }.scan(tree, null);
    }

    private static void assertArtworkIsNotSelfHidden(Tree tree, String where) {
        List<String> images = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            @Override public Void visitVariable(VariableTree node, Void unused) {
                if (node.getType() != null && node.getType().toString().endsWith("RoundedImageWidget")) images.add(node.getName().toString());
                return super.visitVariable(node, unused);
            }
        }.scan(tree, null);
        for (MethodInvocationTree call : calls(tree)) {
            if (!callName(call).equals("setHidden")) continue;
            String select = compact(call.getMethodSelect());
            for (String image : images) assertFalse(select.startsWith(image + ".") || select.startsWith("this." + image + "."),
                    where + ": self-hidden artwork cannot run its before-render callback when the async texture arrives");
        }
    }

    private static List<NewClassTree> creations(Tree tree, String type) {
        List<NewClassTree> found = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            @Override public Void visitNewClass(NewClassTree node, Void unused) {
                if (node.getIdentifier().toString().endsWith(type)) found.add(node);
                return super.visitNewClass(node, unused);
            }
        }.scan(tree, null);
        return found;
    }

    private static List<MethodInvocationTree> calls(Tree tree) {
        List<MethodInvocationTree> found = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            @Override public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                found.add(node);
                return super.visitMethodInvocation(node, unused);
            }
        }.scan(tree, null);
        return found;
    }

    private static List<IfTree> ifs(Tree tree) {
        List<IfTree> found = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            @Override public Void visitIf(IfTree node, Void unused) {
                found.add(node);
                return super.visitIf(node, unused);
            }
        }.scan(tree, null);
        return found;
    }

    private static MethodInvocationTree oneCall(Tree tree, String name) {
        List<MethodInvocationTree> found = calls(tree).stream().filter(call -> callName(call).equals(name)).toList();
        assertEquals(1, found.size(), "Expected one real " + name + " call in " + (tree instanceof MethodTree method ? method.getName() : tree.getKind()));
        return found.get(0);
    }

    private static String callName(MethodInvocationTree call) {
        return call.getMethodSelect() instanceof MemberSelectTree select ? select.getIdentifier().toString() : call.getMethodSelect().toString();
    }

    private static String compact(Tree tree) { return tree == null ? "" : compact(tree.toString()); }
    private static String compact(String source) { return source.replaceAll("\\s+", ""); }

    private static void assertBefore(String code, String first, String second, String message) {
        int a = code.indexOf(first), b = code.indexOf(second);
        assertTrue(a >= 0 && b > a, message + " (" + first + " before " + second + ")");
    }

    private static String stringValue(Tree tree) {
        StringBuilder value = new StringBuilder();
        new TreeScanner<Void, Void>() {
            @Override public Void visitLiteral(LiteralTree node, Void unused) {
                if (node.getValue() instanceof String text) value.append(text);
                return super.visitLiteral(node, unused);
            }
        }.scan(tree, null);
        return value.toString();
    }

    private static Source screen(String name) throws IOException { return read(NCM + name + ".java", name); }

    private static Source read(String relativePath, String className) throws IOException {
        Path file = ProjectPaths.sourceFile(relativePath);
        assertTrue(Files.isRegularFile(file), "Missing source: " + file);
        return parse(Files.readString(file, StandardCharsets.UTF_8), className);
    }

    private static Source parse(String source, String className) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Static source contracts require the configured JDK, not a stripped JRE");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///" + className + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null, List.of(file));
            CompilationUnitTree unit = task.parse().iterator().next();
            assertTrue(diagnostics.getDiagnostics().stream().noneMatch(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR),
                    () -> "Cannot parse " + className + ": " + diagnostics.getDiagnostics());
            ClassTree type = unit.getTypeDecls().stream().filter(tree -> tree instanceof ClassTree candidate
                    && candidate.getSimpleName().contentEquals(className)).map(tree -> (ClassTree) tree).findFirst().orElseThrow();
            return new Source(type);
        }
    }

    private record Source(ClassTree type) {
        MethodTree method(String name) {
            return type.getMembers().stream().filter(tree -> tree instanceof MethodTree method && method.getName().contentEquals(name))
                    .map(tree -> (MethodTree) tree).findFirst().orElseThrow(() -> new AssertionError("Missing method " + type.getSimpleName() + "." + name));
        }
        VariableTree field(String name) {
            return type.getMembers().stream().filter(tree -> tree instanceof VariableTree field && field.getName().contentEquals(name))
                    .map(tree -> (VariableTree) tree).findFirst().orElseThrow(() -> new AssertionError("Missing field " + name));
        }
        ClassTree nested(String name) {
            return type.getMembers().stream().filter(tree -> tree instanceof ClassTree nested && nested.getSimpleName().contentEquals(name))
                    .map(tree -> (ClassTree) tree).findFirst().orElseThrow(() -> new AssertionError("Missing nested class " + name));
        }
    }
}
