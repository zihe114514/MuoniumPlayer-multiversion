package com.muoniumplayer.render.skija;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住「打开 GUI 时玻璃背景是上一次的缓存」的修复：开场/收起转场期间液态透镜整面关闭，
 * 预糊档没有任何消费者刷新，转场借档若无年龄上限，借到的就是上一次开界面糊的那张旧截图，
 * 整段动画都透着它，直到转场结束才变成当前画面。
 */
class GlassTransitionBackdropStaticTest {

    private static String blurredCachedSource() throws Exception {
        Path path = ProjectPaths.sharedJava()
                .resolve(Paths.get("com", "muoniumplayer", "render", "skija", "GlassBackdrop.java"));
        String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        int start = source.indexOf("public static Image blurredCached(float sigma)");
        int end = source.indexOf("\n    }", start);
        assertTrue(start >= 0 && end > start, "blurredCached 必须还存在（转场专用预糊档入口）");
        return source.substring(start, end);
    }

    @Test
    @DisplayName("转场借档必须带年龄上限：只许借 STALE_BORROW_CAPTURES 代以内的旧档")
    void transitionBorrowIsAgeBounded() throws Exception {
        String body = blurredCachedSource();
        assertTrue(body.contains("blurLevels.nearestStale(key, captures, STALE_BORROW_CAPTURES)"),
                "借旧档必须过年龄闸；无上限借用会端出上一场界面的残影");
        assertFalse(body.contains("Long.MIN_VALUE"),
                "旧的「无年龄扫描全槽」写法不许回来");
    }

    @Test
    @DisplayName("旧档超龄时必须按本代数重糊，而不是继续贴旧图或裸图")
    void transitionRebuildsWhenTooStale() throws Exception {
        String body = blurredCachedSource();
        assertTrue(body.contains("rebuildBlur(raw, sigma, key, blurLevels.allocate(key, captures))"),
                "超龄旧档要现糊一遍：这正是全开状态每个采集代数都在付的价钱");
        assertTrue(body.contains("blurLevels.overBudget()"),
                "重建仍受每次采集的预算约束，不许转场独占");
    }
}
