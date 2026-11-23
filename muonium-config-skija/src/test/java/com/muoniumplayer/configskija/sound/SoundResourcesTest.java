package com.muoniumplayer.configskija.sound;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Verifies the copied assets against the read-only source and its external ambience directory. */
public final class SoundResourcesTest {
    static void same(Path source, Path destination) throws Exception {
        byte[] original = Files.readAllBytes(source), copied = Files.readAllBytes(destination);
        if (!Arrays.equals(original, copied)) throw new AssertionError("Resource changed: " + destination);
        if (destination.toString().endsWith(".ogg") && (copied.length < 4 || copied[0] != 'O' || copied[1] != 'g' || copied[2] != 'g' || copied[3] != 'S')) throw new AssertionError("Missing Ogg signature: " + destination);
        System.out.println(destination.getFileName() + " " + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(copied)));
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected new project, original source project, external OneConfig resource directory");
        Path target = Path.of(args[0]).resolve("src/main/resources/assets/muoniumconfigskija");
        Path original = Path.of(args[1]).resolve("muoniumconfig-common/src/main/resources/assets/oneconfig");
        Path external = Path.of(args[2]);
        for (String event : List.of("open", "close", "click", "slider")) same(original.resolve("sounds/ui/" + event + ".ogg"), target.resolve("sounds/ui/" + event + ".ogg"));
        for (String event : List.of("trees", "moog_1", "moog_2", "lullaby")) same(external.resolve("sounds/ambient/" + event + ".ogg"), target.resolve("sounds/ambient/" + event + ".ogg"));
        same(external.resolve("sounds/ui/pad_loop.ogg"), target.resolve("sounds/ui/pad_loop.ogg"));
        same(original.resolve("images/head.png"), target.resolve("images/head.png"));
        String soundJson = Files.readString(original.resolve("sounds.json"), StandardCharsets.UTF_8).replace("oneconfig:", "muoniumconfigskija:");
        if (!Files.readString(target.resolve("sounds.json"), StandardCharsets.UTF_8).equals(soundJson)) throw new AssertionError("Sound manifest differs beyond namespace relocation");
        System.out.println("SoundResourcesTest PASS (10 byte-identical assets; only sounds.json namespaces relocated)");
    }
}
