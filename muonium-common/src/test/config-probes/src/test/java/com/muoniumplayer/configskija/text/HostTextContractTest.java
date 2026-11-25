package com.muoniumplayer.configskija.text;

import com.muoniumplayer.configskija.host.text.HostTextRenderer;
import com.muoniumplayer.configskija.ui.SkijaPainter;
import com.muoniumplayer.configskija.ui.hud.HudRuntime;

import java.nio.file.*;

/** Ownership, artifact and public-contract guard; does not initialize Skija. */
public final class HostTextContractTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path project = Path.of(args[0]), output = Path.of(args[1]);
        Class<?> type =
                Class.forName(
                        HostTextRenderer.class.getName(),
                        false,
                        HostTextContractTest.class.getClassLoader());
        check(SkijaPainter.Text.class.isAssignableFrom(type), "real painter contract");
        check(HudRuntime.TextRenderer.class.isAssignableFrom(type), "real runtime contract");
        check(AutoCloseable.class.isAssignableFrom(type), "explicit ownership");
        byte[] cls =
                Files.readAllBytes(
                        output.resolve(
                                "com/muoniumplayer/configskija/host/text/HostTextRenderer.class"));
        check(((cls[6] & 255) << 8 | (cls[7] & 255)) == 61, "production bytecode Java 17");
        String code =
                Files.readString(
                        project.resolve(
                                "src/main/java/com/muoniumplayer/configskija/host/text/HostTextRenderer.java"));
        for (String forbidden :
                new String[] {
                    "Surface.make",
                    "new Surface(",
                    "DirectContext.make",
                    "new DirectContext(",
                    "Library.load(",
                    "System.load(",
                    "ensureLoaded(",
                    "new SkijaHost(",
                    "FontManager.loadFonts(",
                    "FontManager.pf32",
                    "FontManager.pf34",
                    "net.minecraft.",
                    "net.fabricmc.",
                    "net.neoforged.",
                    "setAccessible("
                })
            check(!code.contains(forbidden), "no private/native-host duplication: " + forbidden);
        check(!code.contains("drawPlainTextWithPaintOn("), "no unreleased host paint API linkage");
        check(code.contains("drawGlyphsOn("), "published host fill path");
        check(code.contains("drawGlyphsOutlineOn("), "published host outline path");
        check(code.contains("deriveAtSize("), "real-size host derivation");
        check(code.contains("metricsRevision()"), "public revision invalidation");
        check(!Files.exists(project.resolve("src/main/resources/assets/muoniumconfigskija/fonts/minecraft/Minecraft-Regular.otf"))
                        && !Files.exists(project.resolve("src/main/resources/assets/muoniumconfigskija/fonts/minecraft/Minecraft-Bold.otf")),
                "no separate settings-center font assets");
        check(code.contains("FontManager.builtinTextMain(") && code.contains("SkijaText.typefaceFor("),
                "settings text uses registered host fonts");
        System.out.println("HostTextContractTest: " + checks + " assertions passed");
    }
}
