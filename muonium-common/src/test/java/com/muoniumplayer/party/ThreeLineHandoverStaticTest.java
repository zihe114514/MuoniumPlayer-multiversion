package com.muoniumplayer.party;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三线让步（直连房间 / 局域网一起听 / 服务器房间）关键状态机的<b>静态接线</b>。
 *
 * <p>三线共用 {@code MpParty} 引擎单例，让位与回收的每一步都是「少写一句就静默退化」：
 * 挂载参数忘记、代次忘推进、迟到帧漏闸、建房帧漏发——全都不抛异常，只是某个场景下
 * 一起听悄悄不可用或凭空多出个房间。这里把三条最脆的时序钉成源码断言（离线，不碰
 * Minecraft），任何一条被重构掉就红。</p>
 *
 * <p>三个维度对应三次审查确认过的时序依赖：
 * ① LAN 引擎让位/回收（{@code heldByDirect} 全生命周期）；
 * ② 直连会话的代次推进与消费配对（僵尸会话防线）；
 * ③ onSessionClosed / close / resetForDirectSession 内部的语句顺序
 *   （顺序颠倒即复现「重挂被跳过」「C_CREATE 漏到 MC 通道」「离房帧发错通道」）。</p>
 */
class ThreeLineHandoverStaticTest {

    private static String read(String first, String... more) throws IOException {
        Path p = ProjectPaths.repoRoot();
        for (String segment : Paths.get(first, more).toString().replace('\\', '/').split("/")) {
            p = p.resolve(segment);
        }
        assertTrue(Files.isRegularFile(p), "文件不存在: " + p);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }

    private static String lan() throws IOException {
        return read("mpparty-common", "src", "main", "java", "com", "muoniumplayer",
                "party", "lan", "LanMpParty.java");
    }

    private static String direct() throws IOException {
        return read("mpparty-common", "src", "main", "java", "com", "muoniumplayer",
                "party", "direct", "MpDirect.java");
    }

    private static String client() throws IOException {
        return read("mpparty-common", "src", "main", "java", "com", "muoniumplayer",
                "party", "MpPartyClient.java");
    }

    /** 取某个方法体内的文本（按出现顺序第一个匹配的方法声明到下一个方法/类结束标记）。 */
    private static String methodBody(String src, String signature, String nextSignature) {
        int start = src.indexOf(signature);
        assertTrue(start >= 0, "找不到方法声明: " + signature);
        int end = nextSignature == null ? src.length() : src.indexOf(nextSignature, start);
        if (end < 0) end = src.length();
        return src.substring(start, end);
    }

    // ════════════════ ① heldByDirect 全生命周期 ════════════════

    @Test
    @DisplayName("onServerStarting 让位分支必须记新世界参数并置 heldByDirect")
    void yieldingBranchRemembersNewWorld() throws IOException {
        String src = lan();
        int start = src.indexOf("public static void onServerStarting");
        int stop = src.indexOf("public static void onPlayerJoin", start);
        String body = src.substring(start, stop);
        // 让位分支（isHosting 命中）里，return 之前两件事都要做：置让位旗 + 记本次参数。
        int hostingAt = body.indexOf("MpDirect.isHosting()");
        assertTrue(hostingAt > 0, "让位判定丢失");
        String branch = body.substring(hostingAt, body.indexOf("return;", hostingAt));
        assertTrue(branch.contains("heldByDirect = true"),
                "让位时不置 heldByDirect，onDirectReleased 的 !active && !heldByDirect 会挡住重挂");
        assertTrue(branch.contains("rememberMount("),
                "让位时不记参数，托管中换存档后直连释放拿的是旧世界的死参数");
    }

    @Test
    @DisplayName("onServerStopped 非 active 分支必须把挂载参数整套作废")
    void deadWorldInvalidatesMountParams() throws IOException {
        String src = lan();
        int start = src.indexOf("public static void onServerStopped");
        int stop = src.indexOf("/** C2S 帧入口", start);
        String body = src.substring(start, stop);
        int inactiveAt = body.indexOf("if (!active) {");
        assertTrue(inactiveAt > 0, "非 active 分支丢失");
        String branch = body.substring(inactiveAt, body.indexOf("return;", inactiveAt));
        assertTrue(branch.contains("lastPlatform = null")
                        && branch.contains("lastConfigDir = null")
                        && branch.contains("heldByDirect = false"),
                "死世界的挂载参数三件套必须一起清，否则 onDirectReleased 会把引擎重挂到已停的世界");
    }

    @Test
    @DisplayName("onDirectReleased 成功路径必须同时翻 active 与 heldByDirect 两面旗")
    void releaseFlipsBothFlags() throws IOException {
        String src = lan();
        String body = methodBody(src, "public static void onDirectReleased()", null);
        int init = body.indexOf("MpParty.init(lastPlatform, lastConfigDir)");
        assertTrue(init > 0, "重挂调用丢失");
        String tail = body.substring(init);
        assertTrue(tail.indexOf("active = true") >= 0 && tail.indexOf("active = true") < tail.indexOf("} catch"),
                "重挂成功后不置 active，LAN 桥的 tick/帧入口永远静默");
        assertTrue(tail.indexOf("heldByDirect = false") >= 0,
                "重挂成功后不清 heldByDirect，下次世界启停的旗面就名不副实");
        assertTrue(body.contains("!active && !heldByDirect"),
                "释放守卫缺 !heldByDirect（托管中换存档场景只有它能放行）");
    }

    // ════════════════ ② generation 推进与消费配对 ════════════════

    @Test
    @DisplayName("close 与 onSessionClosed 两个全量清理点都必须推进代次")
    void bothCleanupPointsBumpGeneration() throws IOException {
        String src = direct();
        String close = methodBody(src, "public static void close()",
                "static boolean onSessionClosed");
        assertTrue(close.contains("generation++"),
                "close 不推进代次，在途 join 线程落地时核对不到变化，僵尸会话回归");
        String sessionClosed = methodBody(src, "static boolean onSessionClosed", null);
        assertTrue(sessionClosed.contains("generation++"),
                "onSessionClosed 不推进代次，托管回环被动死亡后的在途 join 不被拦");
        // 早退守卫必须在推进之前：孤儿连接的迟到 close 不该推计数。
        int guard = sessionClosed.indexOf("if (!wasMember && !wasLoop) return false;");
        int bump = sessionClosed.indexOf("generation++");
        assertTrue(guard >= 0 && bump > guard,
                "代次推进必须排在身份核对之后，否则孤儿帧也会推计数");
    }

    @Test
    @DisplayName("joinRoom 在途线程的代次核对必须先于会话落地")
    void joinChecksGenerationBeforeLanding() throws IOException {
        String src = direct();
        String body = methodBody(src, "public static String joinRoom(", null);
        int check = body.indexOf("myGeneration != generation");
        int land = body.indexOf("client = c;");
        assertTrue(check > 0, "join 在途线程缺代次核对");
        assertTrue(land > 0 && check < land,
                "代次核对必须先于 client = c 落地，否则核对形同虚设");
    }

    @Test
    @DisplayName("建房线程的代次与会话核对必须先于 sendCreate")
    void createChecksSessionBeforeSending() throws IOException {
        String src = direct();
        String body = methodBody(src, "public static String hostRoom(", null);
        int send = body.indexOf("sendCreate();");
        assertTrue(send > 0, "建房调用丢失");
        String preamble = body.substring(0, send);
        assertTrue(preamble.contains("myGeneration != generation"),
                "建房线程缺代次核对：关房后 C_CREATE 会发到真实 MC 服务器凭空建房");
        assertTrue(preamble.contains("server != myServer"),
                "建房线程缺服务端引用核对：托管已换手时核对不到");
    }

    // ════════════════ ③ 关键语句顺序 ════════════════

    @Test
    @DisplayName("onSessionClosed 里 onDirectReleased 必须在 active=false 之前")
    void releaseBeforeActiveReset() throws IOException {
        String src = direct();
        String body = methodBody(src, "static boolean onSessionClosed", null);
        int release = body.indexOf("LanMpParty.onDirectReleased()");
        int reset = body.indexOf("active = false;");
        assertTrue(release > 0 && reset > 0 && release < reset,
                "顺序颠倒会让 LAN 桥的 active 已为 false 且 heldByDirect 未置，重挂被守卫挡死"
                        + "（正是三次审查钉住的那条时序）");
    }

    @Test
    @DisplayName("resetForDirectSession 的离房帧必须先于状态清理")
    void leaveFrameBeforeWipe() throws IOException {
        String src = client();
        String body = methodBody(src, "public static void resetForDirectSession()", null);
        int leave = body.indexOf("MpProto.msg(MpProto.C_LEAVE)");
        int wipe = body.indexOf("clearServerScopeState()");
        assertTrue(leave > 0 && wipe > 0 && leave < wipe,
                "离房帧必须在路由仍指向 MC 通道、且旧房间状态还在时发出，清理之后发就是空发");
    }

    @Test
    @DisplayName("onFrame 的直连迟到帧必须过会话存活校验")
    void lateDirectFramesCheckedAgainstLiveSession() throws IOException {
        String src = client();
        String body = methodBody(src, "public static void onFrame(", null);
        assertTrue(body.contains("frameSinkLive()"),
                "直连关闭瞬间的残余帧会穿透闸门，把刚清零的状态覆写成死房间快照");
    }

    @Test
    @DisplayName("直连活跃时服务器房间页的创建/加入必须被闸")
    void directSessionBlocksServerRoomWrites() throws IOException {
        String src = client();
        String create = methodBody(src, "public static void createRoom(",
                "public static void joinRoom(");
        String join = methodBody(src, "public static void joinRoom(",
                "与官方一起听（网易云/酷狗）互斥");
        assertTrue(create.contains("directRoomConflict()") && join.contains("directRoomConflict()"),
                "直连活跃时不挡创建/加入，会在本机直连引擎里凭空开出第二个房间");
    }
}
