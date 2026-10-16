package com.muoniumplayer.core.utils.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.muoniumplayer.testsupport.ProjectPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HttpDownloadRace} 的结构不变量。竞速本身要联网，静态测试只钉源码层
 * 「改不得」的规则；行为层由真联网冒烟验证（见 docs/实现约定-渲染原生库自动下载.md）。
 */
class HttpDownloadRaceStaticTest {

    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static String raceSource() throws Exception {
        Path path = ProjectPaths.sourceFile(Paths.get("com", "muoniumplayer", "core",
                "utils", "network", "HttpDownloadRace.java"));
        assertTrue(Files.exists(path), "文件不存在: " + path.toAbsolutePath());
        return stripComments(new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8)
                .replace("\r\n", "\n"));
    }

    @Test
    @DisplayName("竞速线程是守护线程、有独立命名、fetch 对每条线程 join 收尸")
    void threadsAreDaemonsAndJoined() throws Exception {
        String race = raceSource();
        assertTrue(race.contains("setDaemon(true)"), "竞速线程必须是守护线程，不能拖着游戏退不出去");
        assertTrue(race.contains("muonium-download-race-"), "线程名要能在线程栈里认出来");
        assertTrue(race.contains(".join()"), "fetch 必须对每条竞速线程 join 收尸，不许留下活线程");
        assertTrue(race.contains("Thread.currentThread().interrupt()"),
                "中断要恢复中断位，不能吞掉");
    }

    @Test
    @DisplayName("校验规则与旧 download() 同一套：超时、2xx、Content-Length、越界、sha256")
    void validationRulesPinned() throws Exception {
        String race = raceSource();
        assertEquals(HttpDownloadRace.CONNECT_TIMEOUT_MILLIS, 20_000, "连接超时 20 秒");
        assertEquals(HttpDownloadRace.READ_TIMEOUT_MILLIS, 60_000, "读空闲超时 60 秒");
        assertEquals(HttpDownloadRace.PART_SUFFIX, ".race", "临时文件后缀");
        assertTrue(race.contains("HTTP \" + code"), "非 2xx 必须拒收");
        assertTrue(race.contains("Content-Length 不符"), "Content-Length 与钉死值不符必须拒收");
        assertTrue(race.contains("下超了"), "下载中越界必须立刻抛");
        assertTrue(race.contains("digest.update(buffer, 0, read)"),
                "sha256 必须在接收时就地计算，不是收完再读一遍文件");
        assertTrue(race.contains("sha256 不符"), "收完必须做 sha256 终验");
        assertTrue(race.contains("ATOMIC_MOVE"), "冠军落点必须原子改名，不能出现半份正式文件");
    }

    @Test
    @DisplayName("进度只报全局领先路：leaderBytes 只增不减，落后路不推回调")
    void progressIsMonotonicFromLeader() throws Exception {
        String race = raceSource();
        assertTrue(race.contains("AtomicLong leaderBytes"), "全局领先字节数必须是共享原子量");
        assertTrue(race.contains("done > current") && race.contains("compareAndSet"),
                "进度刷新必须走 CAS：只有超过全局最大值才生效");
        // 回调在 CAS 成功分支里，天然只有领先路能到达。
        int casIndex = race.indexOf("compareAndSet(current, done)");
        int acceptIndex = race.indexOf("onProgress.accept", casIndex);
        assertTrue(casIndex >= 0 && acceptIndex > casIndex && acceptIndex - casIndex < 200,
                "onProgress 必须只在 CAS 成功（本路领先）分支里调用");
    }

    @Test
    @DisplayName("写入隔离：每路写自己的 .race<N> 临时文件，退出路径都删")
    void perSlotPartFiles() throws Exception {
        String race = raceSource();
        assertTrue(race.contains("PART_SUFFIX + slot"), "每路的临时文件必须带自己的槽位号");
        assertTrue(race.contains("deleteQuietly(part)"), "临时文件在 finally 里删，不许留垃圾");
    }

    @Test
    @DisplayName("开关只认显式 false；冠军判定用 CAS，只有一条路能改名")
    void gateAndSwitchSemantics() throws Exception {
        String race = raceSource();
        assertTrue(race.contains("PROP_ENABLED = \"muonium.downloadRace\""), "总开关的属性名");
        // 开关语义（源码级）：与其它下载器同款——null/空白/任意非 false 值都算开。
        assertTrue(race.contains("!\"false\".equalsIgnoreCase(value.trim())"),
                "只有显式 false 才关，其它值（含乱写）一律开");
        assertTrue(race.contains("settled.compareAndSet(false, true)"),
                "冠军判定必须是 CAS：任何时刻最多一条路改名");
    }

    @Test
    @DisplayName("A/B 分批：15 秒观察窗、A 档三前缀、放量绝不回收先行线程")
    void stagingSemantics() throws Exception {
        String race = raceSource();
        assertEquals(HttpDownloadRace.STAGING_DELAY_MILLIS, 15_000L, "观察窗 15 秒");
        assertTrue(HttpDownloadRace.isTierA("https://file.melodifymc.cc.cd/libs/x.jar"),
                "A 档含 R2（实测最快 2.25MB/s）");
        assertTrue(HttpDownloadRace.isTierA("https://gh-proxy.com/https://raw.githubusercontent.com/x/libs/x.jar"),
                "A 档含 gh-proxy（实测次快 1.75MB/s）");
        assertTrue(HttpDownloadRace.isTierA("http://muoniummc.cc.cd/dl/libs/x.jar"),
                "A 档含自建分发站（实测 1.11MB/s，独立托管面）");
        assertFalse(HttpDownloadRace.isTierA("https://ghfast.top/https://raw.githubusercontent.com/x/libs/x.jar"),
                "ghfast 高方差，B 档");
        assertFalse(HttpDownloadRace.isTierA("https://unknown.example.com/x.jar"),
                "未知线路保守归 B 档");
        // 放量不回收：红线是「不许 interrupt 别的竞速线程」。
        // Thread.currentThread().interrupt()（恢复中断位）与 connection.disconnect()（单路收尾）都合法，
        // 所以钉的是 worker 数组/列表上没有 interrupt 调用。
        assertTrue(race.contains("Thread.currentThread().interrupt()"),
                "中断只允许出现在恢复中断位的惯用法里");
        assertFalse(race.matches("(?s).*started\\.forEach\\([^)]*interrupt.*"),
                "不许对已启动线程列表批量 interrupt（原批不中断）");
        assertFalse(race.contains("allWorkers[index].interrupt()"),
                "不许对先行线程 interrupt（原批不中断）");
        assertTrue(race.contains("PROP_STAGE_MILLIS = \"muonium.downloadStageMillis\""),
                "观察窗覆盖开关的属性名");
        assertTrue(race.contains("stagingDelayMillis()"), "观察窗必须可经系统属性调整（0 = 全量竞速）");
        assertTrue(race.contains("started.add(allWorkers[index])") || race.contains("started.add(worker)"),
                "放量起的 B 档线程必须与 A 档统一收尸（同一个 started 列表）");
    }
}
