package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.testsupport.ProjectPaths;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LiteralTree;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parse-only lifecycle guards; quoted/commented examples cannot satisfy executable-call assertions. */
class PlaylistFeedbackStaticTest {
    private static final String NCM = "com/muoniumplayer/core/screens/ncm/";

    @Test
    void cloudSubmissionsKeepWaitingAndMarshalTheirRealResultsToTheClientThread() throws IOException {
        Source add = screen("AddToPlaylistOverlay");
        MethodTree submit = add.method("submitToPlaylist");
        String gate = compact(branches(submit).get(0).getCondition());
        assertTrue(gate.contains("closing") && gate.contains("submitting") && gate.contains("terminalSuccess()"),
                "Closed, pending and terminal overlays must not start a duplicate operation");
        for (String method : new String[]{"submitToPlaylist", "submitKuGou"}) {
            MethodTree entry = add.method(method);
            assertFalse(hasCall(entry, "requestClose") || hasCall(entry, "requestDismiss"),
                    method + " must not return to the player before its result");
            assertTrue(compact(entry).contains("++requestSequence"), "Each retry must own a new request ticket");
            assertTrue(compact(entry).contains("submitting=true;"));
            assertTrue(calls(entry).stream().anyMatch(call -> name(call).equals("showStatus")
                    && compact(call).contains("FeedbackState.PROCESSING")));
            assertFalse(calls(entry).stream().anyMatch(call -> name(call).equals("showStatus")
                    && compact(call).contains("FeedbackState.SUCCESS")), "Waiting must never fake success");
            MethodInvocationTree worker = one(entry, "runAsync");
            MethodInvocationTree publish = one(worker, "runOnMainThread");
            String callback = method.equals("submitKuGou") ? "handleKuGouResult" : "handleSubmitResult";
            MethodInvocationTree result = one(publish, callback);
            assertEquals("operation", compact(result.getArguments().get(0)));
            assertEquals("playlist", compact(result.getArguments().get(1)));
            assertTrue(hasCall(worker, method.equals("submitKuGou") ? "addTrackToPlaylist" : "addToListWithResult"),
                    "Keep the actual platform operation and callback, not a visual timer conclusion");
        }
    }

    @Test
    void staleOperationOrTargetCannotPublishAndOnlyVerifiedNeteaseResultsCanSucceed() throws IOException {
        Source add = screen("AddToPlaylistOverlay");
        for (String name : new String[]{"handleSubmitResult", "handleKuGouResult"}) {
            MethodTree handler = add.method(name);
            Tree first = handler.getBody().getStatements().get(0);
            assertTrue(first instanceof IfTree, "Reject a stale callback before any state or island mutation");
            IfTree guard = (IfTree) first;
            assertTrue(compact(guard.getCondition()).contains("operation!=requestSequence")
                    && compact(guard.getCondition()).contains("playlist!=selectedPlaylist"));
            assertTrue(compact(guard.getThenStatement()).contains("return;"));
            assertTrue(compact(handler).contains("submitting=false;"), "A matching failure must unlock retries");
        }
        MethodTree result = add.method("handleSubmitResult");
        for (String getter : new String[]{"isSuccess", "isAlreadyExists"}) {
            assertTrue(branches(result).stream().anyMatch(branch -> hasCall(branch.getCondition(), getter)
                    && hasCall(branch.getCondition(), "isVerified")
                    && compact(branch.getCondition()).contains("result!=null")),
                    getter + " requires actual verification and a non-null DTO");
        }
        assertTrue(hasCall(result, "getMessage") && hasCall(result, "showPlaylistTrackAddFailure"));
        assertFalse(hasCall(result, "requestClose"), "The result should start a hold, not immediately erase feedback");
    }

    @Test
    void dismissedOrReplacedOverlayGetsIslandFeedbackWithoutRevivingItsUi() throws IOException {
        Source add = screen("AddToPlaylistOverlay");
        String guard = compact(add.method("canPresentResult"));
        assertTrue(guard.contains("!closing") && guard.contains("getContentHostGeneration()")
                && guard.contains("hostGeneration") && guard.contains("host.addToPlaylistOverlay==this"),
                "Result presentation requires a live host generation and matching overlay identity");
        for (String method : new String[]{"handleSubmitResult", "handleKuGouResult"}) {
            MethodTree result = add.method(method);
            int[] guarded = {0};
            new TreeScanner<Void, Boolean>() {
                @Override public Void visitIf(IfTree branch, Boolean allowed) {
                    scan(branch.getCondition(), allowed);
                    scan(branch.getThenStatement(), Boolean.TRUE.equals(allowed)
                            || compact(branch.getCondition()).equals("(canPresentResult())"));
                    scan(branch.getElseStatement(), allowed);
                    return null;
                }
                @Override public Void visitMethodInvocation(MethodInvocationTree call, Boolean allowed) {
                    if (name(call).equals("showStatus")) {
                        assertTrue(Boolean.TRUE.equals(allowed), "Closed callback must not mutate presentation: " + call);
                        guarded[0]++;
                    }
                    if (name(call).startsWith("showPlaylistTrack")) {
                        assertFalse(Boolean.TRUE.equals(allowed), "The island must still receive a closed pending result");
                    }
                    return super.visitMethodInvocation(call, allowed);
                }
            }.scan(result, false);
            assertTrue(guarded[0] >= 2);
            for (String forbidden : new String[]{"onInit", "restart", "rebuildPlaylistList", "setHidden", "setCurrentPanel", "openAddToPlaylist"}) {
                assertFalse(hasCall(result, forbidden), "Result callback cannot rebuild/reopen UI: " + forbidden);
            }
        }
        MethodTree close = add.method("requestClose");
        assertTrue(compact(close).contains("if(closing)return;"));
        assertTrue(compact(close).contains("closing=true;") && hasCall(close, "requestDismiss"));
        assertFalse(hasCall(close, "cancel") || hasCall(close, "interrupt"), "Closing must not cancel the submitted operation");
    }

    @Test
    void terminalHoldStartsAtTheRealResultWithNanoTimeAndPendingOrErrorNeverAutoCloses() throws IOException {
        Source add = screen("AddToPlaylistOverlay");
        MethodTree show = add.method("showStatus");
        String code = compact(show);
        assertTrue(code.contains("feedbackStartedNanos=System.nanoTime();"));
        assertTrue(code.contains("closeAfterMillis=terminalSuccess()?feedbackStartedNanos+RESULT_HOLD_MILLIS*1000000L:-1L;"),
                "Hold deadline must be derived from the received result in nanoseconds, never a fake elapsed success");
        assertEquals(1400L, ((Number) ((LiteralTree) add.field("RESULT_HOLD_MILLIS").getInitializer()).getValue()).longValue());
        String terminal = compact(add.method("terminalSuccess"));
        assertTrue(terminal.contains("FeedbackState.SUCCESS") && terminal.contains("FeedbackState.ALREADY_EXISTS"));
        assertFalse(terminal.contains("FeedbackState.PROCESSING") || terminal.contains("FeedbackState.ERROR"));
        MethodTree init = add.method("onInit");
        assertTrue(branches(init).stream().anyMatch(branch -> compact(branch.getCondition()).contains("!submitting")
                && compact(branch.getCondition()).contains("now>=closeAfterMillis")
                && hasCall(branch.getThenStatement(), "requestClose")), "Frames must honor the real hold deadline");
        assertTrue(compact(init).contains("longnow=System.nanoTime();"));
        assertFalse(hasCall(init, "currentTimeMillis"));
        assertTrue(hasCall(add.method("shouldClose"), "isGone"), "Reclaim only after the real exit animation finishes");
    }

    @Test
    void kugouBooleanConclusionNeverClaimsNeteaseVerificationOrAlreadyExists() throws IOException {
        Source add = screen("AddToPlaylistOverlay");
        MethodTree handler = add.method("handleKuGouResult");
        assertTrue(handler.getParameters().get(2).getType().toString().equals("boolean"));
        assertFalse(hasCall(handler, "isVerified") || hasCall(handler, "isAlreadyExists"));
        assertFalse(compact(handler).contains("FeedbackState.ALREADY_EXISTS"));
        assertTrue(branches(handler).stream().anyMatch(branch -> compact(branch.getCondition()).equals("(accepted)")
                && hasCall(branch.getThenStatement(), "showPlaylistTrackAddSuccess")
                && hasCall(branch.getElseStatement(), "showPlaylistTrackAddFailure")));
        for (Tree tree : List.of(add.method("submitKuGou"), handler)) {
            assertFalse(creations(tree).stream().anyMatch(creation -> creation.getIdentifier().toString().endsWith("PlaylistTrackOperationResult")),
                    "A KuGou boolean must not be wrapped in a fabricated verified Netease result");
        }
    }

    @Test
    void localBooleanIsImmediateAndFalseDoesNotPretendToBeVerifiedAlreadyExists() throws IOException {
        MethodTree local = screen("AddToPlaylistOverlay").method("submitToLocal");
        IfTree branch = branches(local).stream().filter(candidate -> hasCall(candidate.getCondition(), "addTrack")).findFirst().orElseThrow();
        assertTrue(compact(branch.getThenStatement()).contains("FeedbackState.SUCCESS"));
        assertTrue(compact(branch.getElseStatement()).contains("FeedbackState.ERROR"));
        assertFalse(compact(branch.getElseStatement()).contains("FeedbackState.ALREADY_EXISTS"));
        assertFalse(hasCall(local, "runAsync") || hasCall(local, "runOnMainThread") || hasCall(local, "requestClose"));
        assertTrue(hasCall(branch.getThenStatement(), "showPlaylistTrackAddSuccess"));
        assertTrue(hasCall(branch.getElseStatement(), "showPlaylistTrackAddFailure"));
    }

    @Test
    void feedbackWidgetsUseActualStateTimedSuccessAndExistingRetryRoute() throws IOException {
        Source add = screen("AddToPlaylistOverlay");
        MethodTree panel = add.method("buildFeedbackPanel");
        for (String component : new String[]{"loading", "successMark", "progress", "action"}) assertTrue(hasCall(panel, component));
        assertTrue(compact(panel).contains("NcmPromptMotion.reveal(feedbackStartedNanos,System.nanoTime(),380)"));
        assertTrue(calls(panel).stream().anyMatch(call -> name(call).equals("action")
                && compact(call).contains("FeedbackState.ERROR") && hasCall(call, "submitToPlaylist")),
                "Retry is visible only on a real failure and reuses the duplicate-protected submission route");
        String frames = compact(add.method("onInit"));
        assertTrue(frames.contains("heightMotion.update(") && frames.contains("feedbackMotion.update("));
        assertTrue(frames.contains("playlistList.setHidden(feedbackState!=FeedbackState.IDLE)")
                && frames.contains("feedbackPanel.setHidden(feedbackState==FeedbackState.IDLE)"));
        assertTrue(hasCall(add.method("wrapFeedback"), "wrap") && hasCall(add.method("wrapFeedback"), "metricsRevision"));
    }

    @Test
    void lazyListLoadingIsDrawnFromServiceStateWithoutReopeningTheOverlay() throws IOException {
        Source add = screen("AddToPlaylistOverlay");
        MethodTree rebuild = add.method("rebuildPlaylistList");
        assertTrue(compact(rebuild).contains("if(closing||playlistList==null)return;"));
        MethodInvocationTree spinner = one(rebuild, "loading");
        assertTrue(compact(spinner).contains("isAccountPlaylistLoading()"));
        String loading = compact(add.method("isAccountPlaylistLoading"));
        assertTrue(loading.contains("music.isKuGou()") && loading.contains("KuGouMusicService.areUserPlaylistsLoading()")
                        && loading.contains("music.isBodian()") && loading.contains("BodianMusicService.areUserPlaylistsLoading()"),
                "The empty-list loading state must cover both account playlist services");
        assertTrue(hasCall(add.method("onInit"), "areUserPlaylistsLoaded"));
        assertTrue(hasCall(add.method("onInit"), "areUserPlaylistsLoading"));
        List<MethodInvocationTree> ensures = calls(add.method("onInit")).stream()
                .filter(call -> name(call).equals("ensureUserPlaylistsAsync")).toList();
        assertEquals(2, ensures.size(), "酷狗与波点账号歌单必须分别懒加载");
        assertTrue(ensures.stream().anyMatch(call -> call.getMethodSelect().toString()
                        .equals("KuGouMusicService.ensureUserPlaylistsAsync"))
                        && ensures.stream().anyMatch(call -> call.getMethodSelect().toString()
                        .equals("BodianMusicService.ensureUserPlaylistsAsync")),
                "Both account playlist services must be wired");
        for (MethodInvocationTree ensure : ensures) {
            assertTrue(hasCall(ensure, "runOnMainThread") && branches(ensure).stream().anyMatch(branch ->
                    compact(branch.getCondition()).contains("!closing") && compact(branch.getCondition()).contains("canPresentResult()")
                            && hasCall(branch.getThenStatement(), "rebuildPlaylistList")),
                    "Lazy completion must marshal a closing-guarded list refresh to the client thread");
        }
        assertFalse(hasCall(rebuild, "onInit") || hasCall(rebuild, "restart"));
    }

    @Test
    void sharedDepthAndFineHoverPressAnimatePaintWithoutChangingHitBounds() throws IOException {
        Source expressive = screen("NcmExpressive");
        MethodTree shell = expressive.method("shell");
        assertTrue(hasCall(shell, "fillBlurred") && hasCall(shell, "drawRRect"),
                "Shared prompts must use the real depth drawing rather than a replacement mock panel");
        for (String method : new String[]{"action", "hitSurface"}) {
            MethodTree component = expressive.method(method);
            MethodTree paint = new TreeScanner<MethodTree, Void>() {
                @Override public MethodTree visitMethod(MethodTree node, Void ignored) {
                    return node.getName().contentEquals("onRender") ? node : super.visitMethod(node, ignored);
                }
                @Override public MethodTree reduce(MethodTree found, MethodTree previous) {
                    return previous == null ? found : previous;
                }
            }.scan(component.getBody(), null);
            assertNotNull(paint, "Fine feedback must run through the actual drawing callback");
            assertTrue(hasCall(paint, "nanoTime") && hasCall(paint, "update") && hasCall(paint, "isButtonDown"));
            assertFalse(hasCall(paint, "setBounds") || hasCall(paint, "setPosition"),
                    "Hover/press animation may change paint, not the input rectangle");
        }
        MethodTree loading = expressive.method("loading");
        assertTrue(hasCall(loading, "nanoTime"));
        assertFalse(hasCall(loading, "getFrameDeltaTime"), "Loading phase is monotonic wall time, not frames");
    }

    @Test
    void parserDoesNotTreatQuotedOrCommentedCallsAsFeedbackEvidence() throws IOException {
        Source example = parse("class Example { String text = \"requestClose() showStatus() isVerified()\"; "
                + "/* System.nanoTime(); */ void draw() { result.getMessage(); } }", "Example");
        assertEquals(1, calls(example.type).size());
        assertFalse(hasCall(example.type, "requestClose") || hasCall(example.type, "showStatus") || hasCall(example.type, "nanoTime"));
    }

    private static Source screen(String name) throws IOException {
        return parse(Files.readString(ProjectPaths.sourceFile(NCM + name + ".java"), StandardCharsets.UTF_8), name);
    }

    private static Source parse(String source, String name) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "The static guard needs a JDK parser");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject unit = new SimpleJavaFileObject(URI.create("string:///" + name + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null, List.of(unit));
            CompilationUnitTree parsed = task.parse().iterator().next();
            assertFalse(diagnostics.getDiagnostics().stream().anyMatch(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR),
                    () -> "Malformed source: " + diagnostics.getDiagnostics());
            ClassTree type = parsed.getTypeDecls().stream().filter(tree -> tree instanceof ClassTree)
                    .map(tree -> (ClassTree) tree).filter(tree -> tree.getSimpleName().contentEquals(name)).findFirst().orElseThrow();
            return new Source(type);
        }
    }

    private record Source(ClassTree type) {
        MethodTree method(String name) {
            return type.getMembers().stream().filter(tree -> tree instanceof MethodTree).map(tree -> (MethodTree) tree)
                    .filter(tree -> tree.getName().contentEquals(name)).findFirst().orElseThrow(() -> new AssertionError("Missing method " + name));
        }
        VariableTree field(String name) {
            return type.getMembers().stream().filter(tree -> tree instanceof VariableTree).map(tree -> (VariableTree) tree)
                    .filter(tree -> tree.getName().contentEquals(name)).findFirst().orElseThrow(() -> new AssertionError("Missing field " + name));
        }
    }

    private static List<MethodInvocationTree> calls(Tree tree) {
        List<MethodInvocationTree> result = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            @Override public Void visitMethodInvocation(MethodInvocationTree call, Void ignored) {
                result.add(call); return super.visitMethodInvocation(call, ignored);
            }
        }.scan(tree, null);
        return result;
    }
    private static List<IfTree> branches(Tree tree) {
        List<IfTree> result = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            @Override public Void visitIf(IfTree branch, Void ignored) { result.add(branch); return super.visitIf(branch, ignored); }
        }.scan(tree, null);
        return result;
    }
    private static List<NewClassTree> creations(Tree tree) {
        List<NewClassTree> result = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            @Override public Void visitNewClass(NewClassTree creation, Void ignored) { result.add(creation); return super.visitNewClass(creation, ignored); }
        }.scan(tree, null);
        return result;
    }
    private static String name(MethodInvocationTree call) {
        String select = call.getMethodSelect().toString(); return select.substring(select.lastIndexOf('.') + 1);
    }
    private static boolean hasCall(Tree tree, String name) { return calls(tree).stream().anyMatch(call -> name(call).equals(name)); }
    private static MethodInvocationTree one(Tree tree, String name) {
        List<MethodInvocationTree> matches = calls(tree).stream().filter(call -> name(call).equals(name)).toList();
        assertEquals(1, matches.size(), "Expected one executable " + name + " call"); return matches.get(0);
    }
    private static String compact(Tree tree) { return tree == null ? "" : tree.toString().replaceAll("\\s+", ""); }
}
