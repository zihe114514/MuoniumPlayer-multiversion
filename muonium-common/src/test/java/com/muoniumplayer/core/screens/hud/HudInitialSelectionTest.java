package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.core.settings.HudConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudInitialSelectionTest {


    @Test
    void defaultsToMuoniumConfigOrBuiltinWhenOneConfigIsInstalled() {
        assertEquals(HudConfig.HUD_EDITOR_UI_MCONFIG,
                HudInitialSelection.choose(false, false, HudConfig.HUD_EDITOR_UI_MCONFIG));
        assertEquals(HudConfig.HUD_EDITOR_UI_MCONFIG,
                HudInitialSelection.choose(true, false, HudConfig.HUD_EDITOR_UI_MCONFIG));
        assertEquals(HudConfig.HUD_EDITOR_UI_BUILTIN,
                HudInitialSelection.choose(true, true, HudConfig.HUD_EDITOR_UI_MCONFIG));
        assertEquals(HudConfig.HUD_EDITOR_UI_BUILTIN,
                HudInitialSelection.choose(true, true, HudConfig.HUD_EDITOR_UI_BUILTIN));
    }

    @Test
    void downloadGateWaitsForDetectionAndRespectsSelection() {
        assertFalse(HudInitialSelection.wantsMuoniumConfig(true, true));
        assertFalse(HudInitialSelection.wantsMuoniumConfig(false, false));
        assertTrue(HudInitialSelection.wantsMuoniumConfig(false, true));
    }

    @Test
    void installedOneConfigPreventsMuoniumConfigDownloadEvenWhenSelected() {
        String previous = HudConfig.hudEditorUi;
        try {
            HudInitialSelection.setOneConfigInstalled(true);
            HudConfig.hudEditorUi = HudConfig.HUD_EDITOR_UI_MCONFIG;
            assertFalse(MuoniumConfigDownload.ensureDownloaded("26.3", "fabric"));
            assertFalse(MuoniumConfigDownload.shouldRetry());
        } finally {
            HudConfig.hudEditorUi = previous;
            HudInitialSelection.setOneConfigInstalled(false);
        }
    }

    @Test
    void initializeDoesNotSwitchWhenOneConfigIsNotInstalled(@TempDir Path mods) {
        String previous = HudConfig.hudEditorUi;
        try {
            HudInitialSelection.setOneConfigInstalled(false);
            HudConfig.hudEditorUi = HudConfig.HUD_EDITOR_UI_MCONFIG;
            HudInitialSelection.initialize(mods.toFile());
            assertEquals(HudConfig.HUD_EDITOR_UI_MCONFIG, HudConfig.hudEditorUi);
        } finally {
            HudConfig.hudEditorUi = previous;
        }
    }

    @Test
    void initializeDoesNotOverrideExplicitBuiltinChoice(@TempDir Path mods) {
        String previous = HudConfig.hudEditorUi;
        try {
            HudInitialSelection.setOneConfigInstalled(true);
            HudConfig.hudEditorUi = HudConfig.HUD_EDITOR_UI_BUILTIN;

            HudInitialSelection.initialize(mods.toFile());

            assertEquals(HudConfig.HUD_EDITOR_UI_BUILTIN, HudConfig.hudEditorUi);
        } finally {
            HudInitialSelection.setOneConfigInstalled(false);
            HudInitialSelection.initialize(mods.toFile());
            HudConfig.hudEditorUi = previous;
        }
    }

    @Test
    void switchNoticeUsesExistingDownloadNoticeFacade(@TempDir Path mods) throws Exception {
        String previous = HudConfig.hudEditorUi;
        java.lang.reflect.Field pending =
                HudInitialSelection.class.getDeclaredField("noticePending");
        java.lang.reflect.Field shownAt =
                HudInitialSelection.class.getDeclaredField("noticeShownAt");
        pending.setAccessible(true);
        shownAt.setAccessible(true);
        boolean previousPending = pending.getBoolean(null);
        long previousShownAt = shownAt.getLong(null);
        try {
            HudConfig.hudEditorUi = HudConfig.HUD_EDITOR_UI_BUILTIN;
            HudInitialSelection.setOneConfigInstalled(true);
            HudInitialSelection.initialize(mods.toFile());
            assertTrue(MuoniumConfigDownload.noticeVisible());
            assertTrue(String.join(" ", MuoniumConfigDownload.noticeLines()).contains("OneConfig"));
            assertTrue(String.join(" ", MuoniumConfigDownload.noticeLines()).contains("内置"));
            assertTrue(MuoniumConfigDownload.noticeLines().get(0).contains("致命崩溃警告"));
            shownAt.setLong(null, System.nanoTime() - 11_000_000_000L);
            assertFalse(HudInitialSelection.noticeVisible());
        } finally {
            HudInitialSelection.setOneConfigInstalled(false);
            HudInitialSelection.initialize(mods.toFile());
            HudConfig.hudEditorUi = previous;
            pending.setBoolean(null, previousPending);
            shownAt.setLong(null, previousShownAt);
        }
    }

    @Test
    void detectsDeclaredUiModsAndIgnoresDependenciesAndDisabledJars(@TempDir Path mods) throws IOException {
        writeJar(mods.resolve("renamed.jar"), "fabric.mod.json",
                """
                {"depends":{"oneconfig":"*"},"id":"modernui"}
                """);
        writeJar(mods.resolve("forge.jar"), "META-INF/mods.toml",
                """
                [[mods]]
                modId = "unrelated"
                [[mods]]
                modId = "oneconfig"
                """);
        writeJar(mods.resolve("muoniumconfig.jar"), "META-INF/neoforge.mods.toml",
                """
                [[mods]]
                modId = "muoniumconfigv1"
                [[dependencies.muoniumconfigv1]]
                modId = "oneconfig"
                """);
        writeJar(mods.resolve("disabled.jar.disabled"), "fabric.mod.json",
                """
                {"id":"disabled_ui"}
                """);
        Files.writeString(mods.resolve("broken.jar"), "not a ZIP");

        Set<String> monitored = Set.of("modernui", "oneconfig", "muoniumconfigv1", "disabled_ui");
        assertEquals(Set.of("modernui", "oneconfig", "muoniumconfigv1"),
                MuoniumConfigJarProbe.conflictingModIds(mods.toFile(), monitored));
        Files.delete(mods.resolve("forge.jar"));
        assertEquals(Set.of("modernui"),
                MuoniumConfigJarProbe.conflictingModIds(mods.toFile(), Set.of("modernui", "oneconfig")));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"modernui,Modern UI", "reglass,ReGlass"})
    void conflictingUiBlocksDownloadEvenIfRequestedAgain(String modId, String name, @TempDir Path mods) throws IOException {
        Path modernUi = mods.resolve("any-name.jar");
        writeJar(modernUi, "META-INF/neoforge.mods.toml", """
                [[mods]]
                modId = "%s"
                """.formatted(modId));
        String previous = HudConfig.hudEditorUi;
        try {
            HudConfig.hudEditorUi = HudConfig.HUD_EDITOR_UI_BUILTIN;
            HudInitialSelection.initialize(mods.toFile());
            assertTrue(HudInitialSelection.isMuoniumConfigBlocked());
            assertEquals(HudConfig.HUD_EDITOR_UI_BUILTIN,
                    HudInitialSelection.allowedUi(HudConfig.HUD_EDITOR_UI_MCONFIG));
            assertTrue(MuoniumConfigDownload.noticeLines().get(1).contains(name));

            HudConfig.hudEditorUi = HudConfig.HUD_EDITOR_UI_MCONFIG;
            assertFalse(MuoniumConfigDownload.ensureDownloaded("26.3", "neoforge"));
            assertFalse(MuoniumConfigDownload.shouldRetry());
        } finally {
            Files.deleteIfExists(modernUi);
            HudInitialSelection.initialize(mods.toFile());
            HudConfig.hudEditorUi = previous;
        }
    }

    private static void writeJar(Path output, String entry, String metadata) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(metadata.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
