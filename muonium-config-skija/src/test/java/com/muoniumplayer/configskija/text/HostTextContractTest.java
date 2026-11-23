package com.muoniumplayer.configskija.text;

import com.muoniumplayer.configskija.host.text.HostTextRenderer;
import com.muoniumplayer.configskija.ui.SkijaPainter;
import com.muoniumplayer.configskija.ui.hud.HudRuntime;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

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
        check(code.contains("deriveAtSize("), "real-size host derivation");
        check(code.contains("metricsRevision()"), "public revision invalidation");
        Path assets =
                project.resolve("src/main/resources/assets/muoniumconfigskija/fonts/minecraft");
        String[][] expected = {
            {
                "Minecraft-Regular.otf",
                "c5bcd562c9151e23deb35ce8fb069bf3f8d22e1b71f0e2b70e253ee3925c2174"
            },
            {
                "Minecraft-Bold.otf",
                "62a3173d3d0fb1e7fd836dc08b2c00fab9d0724b77d8a2a0c8341f1b84c1db28"
            }
        };
        for (String[] font : expected) {
            String hash =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(Files.readAllBytes(assets.resolve(font[0]))));
            check(hash.equals(font[1]), "original OTF bytes: " + font[0]);
        }
        System.out.println("HostTextContractTest: " + checks + " assertions passed");
    }
}
