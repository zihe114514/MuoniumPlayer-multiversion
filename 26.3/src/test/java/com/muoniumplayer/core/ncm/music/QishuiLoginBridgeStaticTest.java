package com.muoniumplayer.core.ncm.music;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 汽水外部取码工具那套 {@code state.json} 协议的解析测试。
 *
 * <p>协议是跨进程契约：字段改名、枚举多一个值、时间戳换了单位，都不会有人在一侧编译报错，
 * 只会表现为「游戏里一直显示等待扫码」。所以这里把解析与存活性判断钉死，且<b>不打盘</b>——
 * 直接喂 JSON 字符串，避免测试去动 {@code config/muonium}。</p>
 */
class QishuiLoginBridgeStaticTest {

    private static QishuiLoginBridge.State parse(String phase, long updatedAt) {
        return QishuiLoginBridge.parseState("{\"protocol\":2,\"phase\":\"" + phase + "\","
                + "\"token\":\"T1\",\"scanUrl\":\"https://bff-pc.qishui.com/light/invoke/scan_login?token=T1\","
                + "\"cookie\":\"sessionid=abc; sid_tt=def\",\"message\":\"提示\","
                + "\"errorCode\":2046,\"updatedAt\":" + updatedAt + ",\"pid\":4242}");
    }

    @Test
    @DisplayName("state.json 的字段逐一对上：phase / token / scanUrl / cookie / 提示")
    void parsesStateFields() {
        QishuiLoginBridge.State state = parse("confirmed", System.currentTimeMillis());
        assertNotNull(state, "state.json 解析失败");
        assertEquals(QishuiLoginBridge.Phase.CONFIRMED, state.phase);
        assertEquals("T1", state.token);
        assertEquals("https://bff-pc.qishui.com/light/invoke/scan_login?token=T1", state.scanUrl);
        assertEquals("sessionid=abc; sid_tt=def", state.cookie);
        assertEquals("提示", state.message);
        assertEquals(2046, state.errorCode);
    }

    @Test
    @DisplayName("认不出的 phase 落 UNKNOWN，绝不静默当成「等待扫码」")
    void unknownPhaseIsNotWaiting() {
        assertEquals(QishuiLoginBridge.Phase.UNKNOWN, parse("whatever", System.currentTimeMillis()).phase);
        assertEquals(QishuiLoginBridge.Phase.UNKNOWN, parse("", System.currentTimeMillis()).phase);
    }

    @Test
    @DisplayName("风控那一档要单独有枚举值：它是「人在游戏外处理」，不是失败也不是等待")
    void mfaIsItsOwnPhase() {
        assertEquals(QishuiLoginBridge.Phase.MFA, parse("mfa", System.currentTimeMillis()).phase);
    }

    @Test
    @DisplayName("空闲那一档也要单独有：它是「这个实例可以复用」，不是失败")
    void idleIsItsOwnPhase() {
        assertEquals(QishuiLoginBridge.Phase.IDLE, parse("idle", System.currentTimeMillis()).phase);
    }

    @Test
    @DisplayName("pid 要读得出来：复用判据靠它认出上一次会话留下的实例")
    void pidIsParsed() {
        assertEquals(4242L, parse("idle", System.currentTimeMillis()).pid, "pid 丢了就复用不了旧实例");
    }

    @Test
    @DisplayName("空闲中的实例仍按新鲜度判活：它还在心跳才算能复用")
    void idleStillNeedsFreshness() {
        long now = System.currentTimeMillis();
        assertTrue(parse("idle", now).isAlive(), "还在心跳的空闲实例可以复用");
        assertFalse(parse("idle", now - 60000L).isAlive(), "断更一分钟的空闲实例不该被复用");
    }

    @Test
    @DisplayName("坏 JSON 返回 null（等于「还没状态」），不抛异常打断轮询")
    void brokenJsonIsNull() {
        assertNull(QishuiLoginBridge.parseState("{oops"), "半截 JSON 应被当成没有状态");
        assertNull(QishuiLoginBridge.parseState(""), "空文件应被当成没有状态");
    }

    @Test
    @DisplayName("存活性：等待中久不刷新 = 工具没了；终态即使不再刷新也算活着")
    void alivenessDistinguishesStaleFromTerminal() {
        long now = System.currentTimeMillis();
        assertTrue(parse("waiting", now).isAlive(), "刚刷新过的等待状态是活的");
        assertFalse(parse("waiting", now - 60000L).isAlive(), "一分钟没刷新就该当工具已经没了");
        assertTrue(parse("confirmed", now - 600000L).isAlive(), "终态不需要再刷新，别判成超时");
        assertTrue(parse("expired", now - 600000L).isAlive(), "过期也是终态");
        assertFalse(parse("mfa", now - 60000L).isAlive(), "卡在验证里又断更，是真出事了");
    }
}
