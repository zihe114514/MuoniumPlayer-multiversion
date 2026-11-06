package com.muoniumplayer.party;

import com.google.gson.JsonObject;
import com.muoniumplayer.party.lan.LanMpParty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yun.shan.mpsync.MpParty;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.platform.MpPlatform;
import yun.shan.mpsync.room.Room;
import yun.shan.mpsync.room.RoomManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 局域网 / 单人世界里，世界主人就是服务器管理员。
 *
 * <p>现场：只装客户端 mod 的玩家在单人世界里进了「服务器频道」，拖进度被拦、按「下一首」变成投票、
 * 人数不足 2 人又投不了 —— 集成服务端上没注册 {@code /mp}，引擎里从没有人被登记成管理员，
 * 而频道只听管理员的。这组用例直接驱动 {@link LanMpParty}（与各树桥接层调用的是同一组入口），
 * 钉住：第一个加入集成服务端的人被认作主人、握手里拿到 {@code serverAdmin}、能控制频道；
 * 别人不受影响；换世界重新认；远程服务器不认；直连释放引擎重挂后仍然是他。</p>
 */
class LanHostAdminTest {

    /** 假的集成服务端：记下发出去的帧；execute 同步执行（引擎单线程，测试里不必真的切线程）。 */
    private static final class FakeServer implements MpPlatform {
        final Map<UUID, String> online = new LinkedHashMap<>();
        final List<Object[]> sent = new ArrayList<>();

        UUID player(String name) {
            UUID u = UUID.randomUUID();
            online.put(u, name);
            return u;
        }

        JsonObject lastHelloTo(UUID who) {
            JsonObject last = null;
            for (Object[] s : sent) {
                JsonObject f = (JsonObject) s[1];
                if (who.equals(s[0]) && Proto.S_HELLO.equals(Proto.str(f, Proto.OP, ""))) last = f;
            }
            return last;
        }

        @Override public String nameOf(UUID uuid) { return online.get(uuid); }
        @Override public UUID uuidByName(String name) {
            for (Map.Entry<UUID, String> e : online.entrySet()) if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
            return null;
        }
        @Override public Collection<UUID> onlinePlayers() { return new ArrayList<>(online.keySet()); }
        @Override public boolean canSend(UUID uuid) { return true; }
        @Override public void sendRaw(UUID uuid, byte[] payload) {
            JsonObject f = Proto.decode(payload);
            if (f != null) sent.add(new Object[]{uuid, f});
        }
        @Override public void chat(UUID uuid, String text) { }
        @Override public void execute(Runnable task) { task.run(); }
    }

    @AfterEach
    void tearDown() {
        LanMpParty.onServerStopped();
    }

    private static void hello(UUID who) {
        LanMpParty.onC2sFrame(who, Proto.encode(Proto.msg(Proto.C_HELLO)));
    }

    @Test
    @DisplayName("第一个加入集成服务端的人是主人：握手里就是管理员，客人不是")
    void theWorldOwnerIsTheServerAdminFromTheHandshake(@TempDir Path dir) {
        FakeServer server = new FakeServer();
        UUID host = server.player("Host");
        UUID guest = server.player("Guest");
        LanMpParty.onServerStarting(true, server, dir);
        LanMpParty.onPlayerJoin(host);
        LanMpParty.onPlayerJoin(guest);
        assertEquals(host, LanMpParty.hostUuid());

        hello(host);
        hello(guest);
        JsonObject toHost = server.lastHelloTo(host);
        JsonObject toGuest = server.lastHelloTo(guest);
        assertNotNull(toHost);
        assertNotNull(toGuest);
        assertTrue(Proto.bool(toHost, "serverAdmin", false),
                "客户端只在握手时读一次 serverAdmin：主人在这里就得是 true，界面才是管理员视角");
        assertFalse(Proto.bool(toGuest, "serverAdmin", true), "后来加入的客人不是管理员");
    }

    @Test
    @DisplayName("主人能控制服务器频道（拖进度、切歌不必投票），客人不能")
    void theWorldOwnerControlsTheChannel(@TempDir Path dir) {
        FakeServer server = new FakeServer();
        UUID host = server.player("Host");
        UUID guest = server.player("Guest");
        LanMpParty.onServerStarting(true, server, dir);
        LanMpParty.onPlayerJoin(host);
        LanMpParty.onPlayerJoin(guest);
        RoomManager manager = MpParty.manager();
        Room channel = manager.joinChannel(host);
        manager.joinChannel(guest);
        assertTrue(channel.channel());
        assertTrue(channel.controls(host));
        assertFalse(channel.controls(guest));
    }

    @Test
    @DisplayName("换一个世界重新认主人：上一个世界的主人不带过来")
    void eachServerSessionHasItsOwnOwner(@TempDir Path dir) {
        FakeServer first = new FakeServer();
        UUID a = first.player("A");
        LanMpParty.onServerStarting(true, first, dir.resolve("w1"));
        LanMpParty.onPlayerJoin(a);
        assertEquals(a, LanMpParty.hostUuid());
        LanMpParty.onServerStopped();
        assertNull(LanMpParty.hostUuid(), "世界关了，主人随之作废");

        FakeServer second = new FakeServer();
        UUID b = second.player("B");
        second.online.put(a, "A");
        LanMpParty.onServerStarting(true, second, dir.resolve("w2"));
        LanMpParty.onPlayerJoin(b);
        LanMpParty.onPlayerJoin(a);
        assertEquals(b, LanMpParty.hostUuid());
        assertTrue(MpParty.manager().isServerAdmin(b));
        assertFalse(MpParty.manager().isServerAdmin(a), "在别人的世界里，A 只是客人");
    }

    @Test
    @DisplayName("不是本机的集成服务端：不认主人，引擎也不挂")
    void aRemoteServerHasNoOwnerHere(@TempDir Path dir) {
        FakeServer server = new FakeServer();
        UUID someone = server.player("Someone");
        LanMpParty.onServerStarting(false, server, dir);
        LanMpParty.onPlayerJoin(someone);
        assertNull(LanMpParty.hostUuid());
        assertFalse(LanMpParty.isActive());
    }

    @Test
    @DisplayName("直连释放后引擎重挂（全新的管理员登记）：主人还在线就重新登记上")
    void theOwnerIsStillAdminAfterTheEngineIsRemounted(@TempDir Path dir) {
        FakeServer server = new FakeServer();
        UUID host = server.player("Host");
        LanMpParty.onServerStarting(true, server, dir);
        LanMpParty.onPlayerJoin(host);
        assertTrue(MpParty.manager().isServerAdmin(host));

        LanMpParty.onDirectReleased();     // MpDirect 没在托管：按原装配参数重挂一个全新的引擎
        assertTrue(MpParty.manager().isServerAdmin(host), "重挂后的引擎也得认他");
    }
}
