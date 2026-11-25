package com.muoniumplayer.configskija.avatar;

import com.muoniumplayer.configskija.host.avatar.*;

import java.nio.file.*;
import java.util.*;

/** Scoped source/API guard. No native image, game profile, actual network or graphics context initialization. */
public final class AvatarContractTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]), classes = Path.of(args[1]);
        List<String> lanes =
                List.of(
                        "1.20.1 recode",
                        "1.21.1",
                        "1.21.10",
                        "1.21.11 recode",
                        "26.1.2",
                        "26.2",
                        "26.3");
        Path common = root.resolve("muonium-common/src/main/java/com/muoniumplayer/configskija/host/avatar");
        try (var files = Files.list(common)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                for (String forbidden :
                        List.of(
                                "getAccessToken",
                                "getSessionId",
                                "getProperties()",
                                "setAccessible(",
                                "Surface.make",
                                "Library.load(",
                                "SkijaNatives",
                                "NativeImage.read(",
                                "Files.createTempFile",
                                "Thread.sleep("))
                    check(!text.contains(forbidden), file.getFileName() + " excludes " + forbidden);
                byte[] cls =
                        Files.readAllBytes(
                                classes.resolve(
                                        "com/muoniumplayer/configskija/host/avatar/"
                                                + file.getFileName()
                                                        .toString()
                                                        .replace(".java", ".class")));
                check(((cls[6] & 255) << 8 | (cls[7] & 255)) == 61, "shared class targets Java17");
            }
        }
        for (String lane : lanes) {
            Path source =
                    root.resolve(
                            lane
                                    + "/src/main/java/com/muoniumplayer/configskija/host/avatar");
            String pixels = Files.readString(source.resolve("AvatarTextureAccess.java")),
                    skin = Files.readString(source.resolve("AvatarSkinAccess.java"));
            check(!pixels.contains(".close()"), "borrowed game image not closed: " + lane);
            check(
                    !pixels.contains("new NativeImage"),
                    "no native image allocation escapes client: " + lane);
            check(
                    skin.replaceAll("\\s+", "").contains("mc.isSameThread()")
                            && skin.replaceAll("\\s+", "").contains("mc.submit(task)"),
                    "client-thread access: " + lane);
            boolean old = lane.equals("1.20.1 recode") || lane.equals("1.21.1");
            check(
                    pixels.contains(old ? "getPixelRGBA" : "pixels.getPixel("),
                    "correct pixel namespace: " + lane);
            if (old) check(!skin.contains("mc.getSkinManager()"), "no invented legacy skin future");
            else
                check(
                        skin.replaceAll("\\s+", "")
                                .contains("mc.getSkinManager().get(mc.getGameProfile())"),
                        "actual modern skin future: " + lane);
            for (String loader :
                    List.of("fabric", lane.equals("1.20.1 recode") ? "forge" : "neoforge")) {
                check(
                        Files.isRegularFile(
                                root.resolve(
                                        "muonium-common/src/test/config-probes/build/host/"
                                                + lane
                                                + "/"
                                                + loader
                                                + "/compile-classpath.txt")),
                        "actual combination classpath " + lane + loader);
            }
        }
        String web = Files.readString(common.resolve("AvatarWeb.java"));
        check(
                web.contains("setInstanceFollowRedirects(false)"),
                "web fallback cannot broaden endpoint via redirects");
        check(!web.contains("Authorization") && !web.contains("Cookie"), "no login headers");
        check(
                AvatarLoader.Timing.original().skinMillis() == 2000
                        && AvatarLoader.Timing.original().clientMillis() == 5000,
                "source async deadlines");
        System.out.println("AvatarContractTest: " + checks + " assertions passed");
    }
}
