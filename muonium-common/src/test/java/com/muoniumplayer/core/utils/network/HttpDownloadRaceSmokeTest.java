package com.muoniumplayer.core.utils.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真联网冒烟：对真实镜像链跑一次竞速，验证冠军耗时、进度单调、文件正确。
 * 需要 network；常态构建不跑它（tag 掉，用 -Dgroups 手动触发）。
 */
class HttpDownloadRaceSmokeTest {

    private static final List<String> ZSTD_MIRRORS = List.of(
            "https://ghfast.top/https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/libs/zstd-jni-1.5.6-9-forge189-slim.jar",
            "https://gh-proxy.com/https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/libs/zstd-jni-1.5.6-9-forge189-slim.jar",
            "https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/libs/zstd-jni-1.5.6-9-forge189-slim.jar",
            "https://ghproxy.net/https://raw.githubusercontent.com/zihe114514/MuoniumPlayer-updates/main/libs/zstd-jni-1.5.6-9-forge189-slim.jar",
            "https://gcore.jsdelivr.net/gh/zihe114514/MuoniumPlayer-updates@main/libs/zstd-jni-1.5.6-9-forge189-slim.jar",
            "https://melodifymc.cc.cd/dl/libs/zstd-jni-1.5.6-9-forge189-slim.jar");
    private static final long ZSTD_BYTES = 1_991_396L;
    private static final String ZSTD_SHA256 = "1b65c7c44e2483864389f0a8a221c877b617f5ce4a1e86a9fe9604fd60ab499c";

    @Test
    @DisplayName("真联网竞速拉 zstd：冠军产出、进度单调、文件字节与 sha256 全对")
    void raceDownloadsZstdJar() throws Exception {
        File target = Files.createTempFile("race-smoke", ".jar").toFile();
        assertTrue(target.delete(), "先删掉占位文件，竞速要自己建");

        AtomicInteger callbacks = new AtomicInteger();
        AtomicLong lastPercent = new AtomicLong(-1);
        AtomicInteger regressions = new AtomicInteger();
        long t0 = System.currentTimeMillis();
        HttpDownloadRace.Winner winner = HttpDownloadRace.fetch(
                ZSTD_MIRRORS, ZSTD_BYTES, ZSTD_SHA256, target, percent -> {
                    callbacks.incrementAndGet();
                    if (percent < lastPercent.get()) {
                        regressions.incrementAndGet();
                    }
                    lastPercent.set(percent);
                });
        long elapsed = System.currentTimeMillis() - t0;

        assertEquals(ZSTD_BYTES, target.length(), "冠军文件字节数必须与钉死值一致");
        assertEquals(ZSTD_BYTES, winner.bytes());
        assertNotNull(winner.url());
        assertTrue(elapsed < 120_000, "冠军应在两分钟内产生（实际 " + elapsed + " ms）");
        assertEquals(0, regressions.get(), "进度百分比必须单调：不允许回跳（共 "
                + callbacks.get() + " 次回调）");
        assertTrue(target.getName().contains("race-smoke"));

        for (int slot = 0; slot < ZSTD_MIRRORS.size(); slot++) {
            File part = new File(target.getAbsolutePath() + ".race" + slot);
            assertTrue(!part.exists(), "败者的临时文件必须被清理：" + part);
        }
        Files.deleteIfExists(target.toPath());
    }

    @Test
    @DisplayName("全部线路指向不存在的域 → 整轮失败、不留临时文件")
    void allSourcesDeadFailsCleanly() {
        List<String> dead = new ArrayList<String>();
        for (int index = 1; index <= 3; index++) {
            dead.add("https://dead.invalid/mirror" + index + "/file.bin");
        }
        File target = new File(System.getProperty("java.io.tmpdir"), "race-dead-smoke.bin");
        try {
            HttpDownloadRace.fetch(dead, 1024,
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    target, null);
            throw new AssertionError("全死链必须抛 IOException");
        } catch (IOException expected) {
            assertTrue(String.valueOf(expected.getMessage()).contains("都失败"),
                    "失败消息要说明是全部线路失败：" + expected.getMessage());
        }
        for (int slot = 0; slot < dead.size(); slot++) {
            assertTrue(!new File(target.getAbsolutePath() + ".race" + slot).exists(),
                    "败者临时文件必须被清理");
        }
        assertTrue(!target.isFile(), "全败时不得留下正式文件");
    }
}
