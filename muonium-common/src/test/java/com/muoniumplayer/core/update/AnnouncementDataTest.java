package com.muoniumplayer.core.update;

import com.google.gson.JsonObject;
import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Offline data/policy tests: never invoke networking or the real ConfigPaths registry. */
class AnnouncementDataTest {

    @TempDir Path temp;

    @Test
    void richSectionsAreIndependentAndLegacyFieldsSurvive() {
        String json = "{\"schema\":99,\"contentRevision\":17,\"unknown\":{},"
                + "\"fallback\":{\"latest\":\"2.6.0\"},"
                + "\"announcements\":[{\"id\":\"same\",\"title\":\"a\",\"link\":\"https://github.com/x\","
                + "\"linkText\":\"legacy\",\"image\":\"https://raw.githubusercontent.com/x/q.png\","
                + "\"imageAlt\":\" QR \",\"imageFit\":\"contain\",\"badge\":\" new \","
                + "\"actions\":[{\"text\":\"Open\",\"url\":\"https://afdian.com/a/example\"}]}],"
                + "\"sponsorships\":[{\"id\":\"same\",\"title\":\"s\"},{\"id\":\"same\",\"title\":\"dup\"}],"
                + "\"communities\":[{\"id\":\"same\",\"body\":\"c\"}]}";
        UpdateManifest manifest = UpdateManifest.parse(json);
        assertNotNull(manifest);
        assertEquals(99, manifest.schema());
        assertEquals(17L, manifest.contentRevision());
        assertEquals(1, manifest.announcements().size());
        assertEquals("s", manifest.sponsorships().get(0).title());
        assertEquals("c", manifest.communities().get(0).body());
        Announcement card = manifest.announcements().get(0);
        assertEquals("https://github.com/x", card.link());
        assertEquals("legacy", card.linkText());
        assertEquals("https://raw.githubusercontent.com/x/q.png", card.image());
        assertEquals("QR", card.imageAlt());
        assertEquals("contain", card.imageFit());
        assertEquals("new", card.badge());
        assertEquals("Open", card.actions().get(0).text());
        assertEquals("https://afdian.com/a/example", card.actions().get(0).url());
        assertThrows(UnsupportedOperationException.class, () -> card.actions().clear());
        assertThrows(UnsupportedOperationException.class, () -> manifest.sponsorships().clear());
        assertThrows(UnsupportedOperationException.class, () -> manifest.communities().clear());
    }

    @Test
    void passiveOnlyManifestsAreUsableButDoNotFabricateReleaseEntries() {
        for (String section : Arrays.asList("sponsorships", "communities")) {
            UpdateManifest manifest = UpdateManifest.parse("{\"" + section
                    + "\":[{\"id\":\"x\",\"title\":\"t\"}]}" );
            assertNotNull(manifest);
            assertTrue(manifest.announcements().isEmpty());
            assertNull(manifest.entryFor("26.3", "fabric"));
        }
        UpdateManifest legacy = UpdateManifest.parse("{\"schema\":1,\"fallback\":{\"latest\":\"1.0\"}}");
        assertTrue(legacy.sponsorships().isEmpty());
        assertTrue(legacy.communities().isEmpty());
        assertEquals(0L, legacy.contentRevision());
    }

    @Test
    void badOptionalFieldsCannotPoisonUsableCards() {
        UpdateManifest manifest = UpdateManifest.parse("{\"sponsorships\":[{\"id\":\"x\",\"title\":\"t\","
                + "\"image\":{},\"imageAlt\":1,\"imageFit\":false,\"badge\":[],\"actions\":{},"
                + "\"link\":null}],\"communities\":\"bad\"}");
        assertNotNull(manifest);
        Announcement card = manifest.sponsorships().get(0);
        assertEquals("", card.image());
        assertEquals("", card.imageAlt());
        assertEquals("contain", card.imageFit());
        assertEquals("", card.badge());
        assertEquals("", card.link());
        assertTrue(card.actions().isEmpty());
        assertTrue(manifest.communities().isEmpty());
        assertNull(UpdateManifest.parse("{\"announcements\":[{\"id\":1,\"title\":true}]}"));
    }

    @Test
    void boundsDoNotTruncateUrlsOrCollideReadIds() {
        String hugeUrl = "https://github.com/" + "x".repeat(Announcement.MAX_URL_CHARS);
        Announcement card = new Announcement("id", null, null,
                "t".repeat(400), "b".repeat(20000), hugeUrl, null,
                hugeUrl, null, "COVER", null, null);
        assertEquals(Announcement.MAX_TITLE_CHARS, card.title().length());
        assertEquals(Announcement.MAX_BODY_CHARS, card.body().length());
        assertEquals("", card.link());
        assertEquals("", card.image());
        assertEquals("cover", card.imageFit());
        assertNull(UpdateManifest.parse("{\"announcements\":[{\"id\":\""
                + "i".repeat(Announcement.MAX_ID_CHARS + 1) + "\",\"title\":\"t\"}]}"));
        assertNull(UpdateManifest.parse(" ".repeat(UpdateManifest.MAX_MANIFEST_CHARS + 1)));
        Announcement old = new Announcement("id", null, null, "title", null, null, null);
        assertEquals("contain", old.imageFit());
        assertTrue(old.actions().isEmpty());
    }

    @Test
    void actionsAreBoundedOrderedAndDefensivelyCopied() {
        UpdateManifest manifest = UpdateManifest.parse("{\"announcements\":[{\"id\":\"x\",\"title\":\"t\","
                + "\"actions\":[null,{},5,{\"url\":false},{\"text\":\"first\",\"url\":\"https://github.com/a\"},"
                + "{\"url\":\"https://github.com/a\"},{\"url\":\"https://qq.com/b\"},"
                + "{\"url\":\"https://afdian.net/c\"},{\"url\":\"https://gitee.com/d\"}]}]}");
        assertNotNull(manifest);
        List<Announcement.Action> parsed = manifest.announcements().get(0).actions();
        assertEquals(Announcement.MAX_ACTIONS, parsed.size());
        assertEquals("first", parsed.get(0).text());
        assertEquals("https://afdian.net/c", parsed.get(2).url());
        List<Announcement.Action> input = new ArrayList<>(parsed);
        Announcement card = new Announcement("x", "", "", "t", "", "", "", "", "", "", "", input);
        input.clear();
        assertEquals(3, card.actions().size());
    }

    @Test
    void httpsActionsReuseCentralPolicyAndRejectCredentialsAndPorts() {
        for (String url : Arrays.asList("https://qm.qq.com/example", "https://qq.com:443/a",
                "https://afdian.com/a/example", "https://afdian.net/a/example",
                " HTTPS://GITHUB.COM/a ", "https://www.melodifymc.cc.cd/download")) {
            assertTrue(UpdateChecker.isAllowedPage(url), url);
            assertEquals(UpdateChecker.isAllowedPage(url), AnnouncementChecker.isLinkAllowed(url));
        }
        for (String url : Arrays.asList("http://qq.com/a", "https://qq.com:80/a", "https://qq.com:444/a",
                "https://qq.com:/a", "https://qq.com:notaport/a", "https://qq.com:443:80/a",
                "https://user@qq.com/a", "https://qq.com@evil.com/a", "https://qq.com.evil.com/a",
                "https://notqq.com/a", "https://qq.com\\@evil.com/a", "https://qq.com/has space",
                "https://qq.com/\na", "https://qq.com/%zz", "javascript:alert(1)",
                "file:///tmp/a.png", "data:image/png;base64,AA", "https://localhost/a")) {
            assertFalse(UpdateChecker.isAllowedPage(url), url);
            assertFalse(AnnouncementChecker.isLinkAllowed(url), url);
        }
    }

    @Test
    void contentRevisionIsNonnegativeIntegerAndFutureSchemaIsTolerated() {
        for (String invalid : Arrays.asList("-1", "1.5", "true", "null", "{}", "[]",
                "9223372036854775808", "\"oops\"")) {
            UpdateManifest manifest = UpdateManifest.parse("{\"schema\":99,\"contentRevision\":" + invalid
                    + ",\"fallback\":{\"latest\":\"1\"}}");
            assertNotNull(manifest);
            assertEquals(0L, manifest.contentRevision(), invalid);
        }
        assertEquals(Long.MAX_VALUE, UpdateManifest.parse("{\"contentRevision\":9223372036854775807,"
                + "\"fallback\":{\"latest\":\"1\"}}").contentRevision());
    }

    @Test
    void sameVersionRevisionSelectsWholeManifestNotSplicedSections() {
        String stale = body("1.10.0", 2, "old");
        String fresh = body("1.10", 3, "new");
        assertEquals(fresh, AnnouncementChecker.pickNewestBody(stale, fresh));
        assertEquals(fresh, AnnouncementChecker.pickNewestBody(fresh, stale));
        assertEquals("new", UpdateManifest.parse(AnnouncementChecker.pickNewestBody(stale, fresh))
                .sponsorships().get(0).title());
        String lower = body("1.9.0", 99, "lower");
        assertEquals(fresh, AnnouncementChecker.pickNewestBody(lower, fresh));
        assertEquals(stale, AnnouncementChecker.pickNewestBody(stale, body("1.10", 2, "tie")));
        assertEquals(fresh, AnnouncementChecker.pickNewestBody(null, "<html>", fresh));
        assertNull(AnnouncementChecker.pickNewestBody((String[]) null));
        assertNull(AnnouncementChecker.pickNewestBody("bad", null));
        String passiveOld = "{\"contentRevision\":1,\"sponsorships\":[{\"id\":\"s\",\"title\":\"old\"}]}";
        String passiveNew = passiveOld.replace(":1,", ":2,").replace("old", "new");
        assertEquals(passiveNew, AnnouncementChecker.pickNewestBody(passiveOld, passiveNew));
    }

    @Test
    void snapshotIsFinalImmutableAndSingleVolatilePublication() throws Exception {
        Announcement card = new Announcement("x", "", "", "t", "", "", "");
        List<Announcement> list = new ArrayList<>(Collections.singletonList(card));
        AnnouncementChecker.Snapshot snapshot = new AnnouncementChecker.Snapshot(list, list, list);
        list.clear();
        assertEquals(1, snapshot.announcements().size());
        assertEquals(1, snapshot.sponsorships().size());
        assertEquals(1, snapshot.communities().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.announcements().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.sponsorships().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.communities().clear());
        assertTrue(Modifier.isFinal(AnnouncementChecker.Snapshot.class.getModifiers()));
        for (Field field : AnnouncementChecker.Snapshot.class.getDeclaredFields()) {
            assertTrue(Modifier.isFinal(field.getModifiers()), field.getName());
        }
        Field published = field("currentSnapshot");
        assertTrue(Modifier.isVolatile(published.getModifiers()));
        assertFalse(new AnnouncementChecker.Snapshot(null, null, null).hasContent());
        assertTrue(new AnnouncementChecker.Snapshot(null, snapshot.sponsorships(), null).hasContent());
    }

    @Test
    void invalidRefreshRetainsSnapshotAndPassiveCardsDoNotBecomeUnread() throws Exception {
        Field published = field("currentSnapshot");
        Field resolved = field("resolved");
        Field shown = field("shownThisSession");
        Object previous = published.get(null);
        Object oldResolved = resolved.get(null);
        Object oldShown = shown.get(null);
        Method apply = AnnouncementChecker.class.getDeclaredMethod("apply", String.class);
        apply.setAccessible(true);
        try {
            shown.set(null, false);
            assertEquals(true, apply.invoke(null, "{\"sponsorships\":[{\"id\":\"s\",\"title\":\"t\"}]}"));
            AnnouncementChecker.Snapshot current = AnnouncementChecker.snapshot();
            assertTrue(AnnouncementChecker.hasContent());
            assertTrue(AnnouncementChecker.isResolved());
            assertTrue(AnnouncementChecker.all().isEmpty());
            assertEquals(0, AnnouncementChecker.unreadCount());
            assertFalse(AnnouncementChecker.shouldShow());
            assertEquals(false, apply.invoke(null, "<html>bad response</html>"));
            assertSame(current, AnnouncementChecker.snapshot());
            assertTrue(AnnouncementChecker.isResolved());
        } finally {
            published.set(null, previous);
            resolved.set(null, oldResolved);
            shown.set(null, oldShown);
        }
    }

    @Test
    void forcedRefreshBypassesFreshCacheAndInFlightRefreshReturnsFalse() throws Exception {
        String[] names = {"loaded", "cachedAt", "cachedChannel", "cachedBody", "mcVersion", "loaderId", "fetching"};
        List<Object> old = new ArrayList<>();
        for (String name : names) old.add(field(name).get(null));
        Method cached = AnnouncementChecker.class.getDeclaredMethod("cachedBody", boolean.class);
        cached.setAccessible(true);
        try {
            field("loaded").set(null, true);
            field("mcVersion").set(null, "26.3");
            field("loaderId").set(null, "fabric");
            field("cachedChannel").set(null, "26.3-fabric");
            field("cachedAt").set(null, System.currentTimeMillis());
            field("cachedBody").set(null, "cached");
            assertEquals("cached", cached.invoke(null, false));
            assertNull(cached.invoke(null, true));
            field("cachedAt").set(null, System.currentTimeMillis() - 31L * 60L * 1000L);
            assertNull(cached.invoke(null, false));
            field("fetching").set(null, true);
            assertFalse(AnnouncementChecker.refresh());
        } finally {
            for (int index = 0; index < names.length; index++) field(names[index]).set(null, old.get(index));
        }
    }

    @Test
    void emptyAndUnreadableExistingOverrideNeverMeanNetworkFallback() throws Exception {
        Path file = temp.resolve("override.json");
        assertNull(AnnouncementChecker.localOverride(file.toFile()));
        Files.write(file, new byte[0]);
        assertEquals("", AnnouncementChecker.localOverride(file.toFile()));
        String text = "{\"announcements\":[{\"id\":\"x\",\"title\":\"t\"}]}";
        Files.writeString(file, text, StandardCharsets.UTF_8);
        assertEquals(text, AnnouncementChecker.localOverride(file.toFile()));
        File missingButReportedFile = new File(temp.resolve("missing.json").toString()) {
            @Override public boolean isFile() { return true; }
        };
        assertEquals("", AnnouncementChecker.localOverride(missingButReportedFile));
    }

    @Test
    void readMergePreservesCloseBeforeLoadAndKeepsBoundedRecentIds() {
        List<String> persisted = new ArrayList<>();
        for (int index = 0; index < 110; index++) persisted.add("old-" + index);
        List<String> merged = AnnouncementChecker.mergeReadIds(persisted, Arrays.asList("old-105", "new"));
        assertEquals(AnnouncementChecker.MAX_READ_IDS, merged.size());
        assertEquals("old-105", merged.get(merged.size() - 2));
        assertEquals("new", merged.get(merged.size() - 1));
        assertEquals(1, Collections.frequency(merged, "old-105"));
    }

    @Test
    void uiReadersAndCloseNeverPerformDiskIoAndEarlySaveIsDeferred() throws Exception {
        String source = Files.readString(ProjectPaths.sourceFile(
                "com/muoniumplayer/core/update/AnnouncementChecker.java"), StandardCharsets.UTF_8);
        String readers = source.substring(source.indexOf("public static boolean isRead("),
                source.indexOf("public static boolean hasUnread("));
        String close = source.substring(source.indexOf("public static void markAllRead("),
                source.indexOf("public static boolean isLinkAllowed("));
        assertFalse(readers.contains("load();"));
        assertFalse(close.contains("load();"));
        assertFalse(close.contains("JsonConfigStorage"));
        String load = source.substring(source.indexOf("private static void load("),
                source.indexOf("static void acceptSavedState("));
        int gateEnd = load.indexOf("loading = true;");
        assertTrue(gateEnd >= 0);
        assertTrue(load.indexOf("JsonConfigStorage.readObject") > load.indexOf("}", gateEnd));
        assertTrue(source.contains("STATE_WRITER.execute(() -> save(root))"));
        Field loaded = field("loaded");
        Field pending = field("savePending");
        Object oldLoaded = loaded.get(null);
        Object oldPending = pending.get(null);
        try {
            loaded.set(null, false);
            pending.set(null, false);
            Method save = AnnouncementChecker.class.getDeclaredMethod("saveLater");
            save.setAccessible(true);
            save.invoke(null);
            assertEquals(true, pending.get(null), "Do not overwrite unread disk state before load");
        } finally {
            loaded.set(null, oldLoaded);
            pending.set(null, oldPending);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void savedStateMergeKeepsRecentMarksDespiteMalformedCacheMetadata() throws Exception {
        Field idsField = field("readIds");
        List<String> ids = (List<String>) idsField.get(null);
        List<String> oldIds = new ArrayList<>(ids);
        String[] names = {"loaded", "loading", "savePending", "cachedAt", "cachedChannel", "cachedBody", "readSnapshot"};
        List<Object> old = new ArrayList<>();
        for (String name : names) old.add(field(name).get(null));
        try {
            ids.clear();
            ids.add("closed-while-loading");
            field("loaded").set(null, false);
            field("loading").set(null, true);
            field("savePending").set(null, false); // No real registry write in this offline merge test.
            JsonObject root = new JsonObject();
            com.google.gson.JsonArray persisted = new com.google.gson.JsonArray();
            persisted.add("older-read");
            persisted.add("older-read");
            persisted.add(7);
            root.add("readIds", persisted);
            root.add("cachedAt", new JsonObject());
            root.addProperty("cachedChannel", "26.3-fabric");
            root.addProperty("cachedBody", "cached text");
            AnnouncementChecker.acceptSavedState(root);
            assertEquals(Arrays.asList("older-read", "closed-while-loading"), ids);
            assertTrue(AnnouncementChecker.isRead("older-read"));
            assertTrue(AnnouncementChecker.isRead("closed-while-loading"));
            assertFalse(AnnouncementChecker.isRead("7"));
            assertEquals(0L, field("cachedAt").get(null));
            assertEquals("cached text", field("cachedBody").get(null));
            assertEquals(true, field("loaded").get(null));
            assertEquals(false, field("loading").get(null));
        } finally {
            ids.clear();
            ids.addAll(oldIds);
            for (int index = 0; index < names.length; index++) field(names[index]).set(null, old.get(index));
        }
    }

    @Test
    void persistenceFailureIsSilentAndManifestReadIsBoundedAndClosesStream() throws Exception {
        Path parentFile = temp.resolve("blocked-parent");
        Files.writeString(parentFile, "not a directory");
        assertDoesNotThrow(() -> AnnouncementChecker.save(parentFile.resolve("state.json").toFile(), new JsonObject()));
        assertFalse(Files.exists(parentFile.resolve("state.json")));
        String text = "{\"title\":\"中文\\nline\"}\n";
        assertEquals(text, AnnouncementChecker.readManifest(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))));
        byte[] oversized = new byte[UpdateManifest.MAX_MANIFEST_CHARS + 1];
        boolean[] closed = {false};
        ByteArrayInputStream stream = new ByteArrayInputStream(oversized) {
            @Override public void close() throws IOException { closed[0] = true; super.close(); }
        };
        assertThrows(IOException.class, () -> AnnouncementChecker.readManifest(stream));
        assertTrue(closed[0]);
    }

    private static Field field(String name) throws Exception {
        Field field = AnnouncementChecker.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static String body(String latest, long revision, String title) {
        return "{\"fallback\":{\"latest\":\"" + latest + "\"},\"contentRevision\":" + revision
                + ",\"announcements\":[{\"id\":\"a\",\"title\":\"" + title + "\"}],"
                + "\"sponsorships\":[{\"id\":\"s\",\"title\":\"" + title + "\"}],"
                + "\"communities\":[{\"id\":\"c\",\"title\":\"" + title + "\"}]}";
    }
}
