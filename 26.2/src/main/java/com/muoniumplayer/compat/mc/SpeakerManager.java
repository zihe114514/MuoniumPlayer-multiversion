package com.muoniumplayer.compat.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import com.muoniumplayer.core.ncm.music.acoustics.SpeakerState;
import com.muoniumplayer.core.ncm.music.acoustics.SpeakerStore;

/**
 * 世界声源(「虚拟音箱」)的放置/移除与逐 tick 降级判定。纯客户端:锚点只是
 * 一个世界坐标,不加方块、不碰服务端 —— 世界渲染一个线框标记
 * ({@link SpeakerRenderBridge}),声学上让 {@link ListenerProbe} 把射线原点
 * 切过来,音乐就从那里发出。
 *
 * <h2>交互(单键智能切换)</h2>
 * <ul>
 *   <li>瞄准空处按键 → 放置:锚点 = 准星命中面外浮 {@code 0.5} 格(悬浮于
 *       表面,防嵌墙导致射线场从方块内部出发);</li>
 *   <li>瞄准已有声源(命中点距锚点 &lt;2.5 格)按键 → 移除;</li>
 *   <li>没有准星命中(望天)→ 提示需要一个落点。</li>
 * </ul>
 *
 * <h2>降级状态机(每 tick 判定,状态沿提示)</h2>
 * <p>状态只在本类逐 tick 推导({@link #tick}),树侧持有完整的世界语义
 * (维度串、chunk 加载);共享层 {@link SpeakerState} 只存锚点数据。降级
 * 种类:超射程 / 维度不匹配 / 区块卸载 —— 音频侧对降级的响应是目标量
 * 平滑淡出(SceneAcoustics 收敛),这里只负责判定与聊天栏提示(只报变化,
 * 不刷屏)。</p>
 *
 * <h2>持久化</h2>
 * <p>锚点落盘({@code SpeakerStore},speaker.json):退出重进后自动恢复;
 * 恢复的锚点先过 sanitize,坏数据按无锚点处理。</p>
 */
public final class SpeakerManager {

    /** 命中点距锚点多近算「瞄准了声源」(格):移除的判定窗口。 */
    private static final double REMOVE_RADIUS = 2.5;
    /** 锚点悬浮于命中面之外的距离(格):防射线原点嵌进墙里。 */
    private static final double SURFACE_OFFSET = 0.5;

    /** 当前降级状态(仅游戏线程读写;渲染桥/探针也读它)。 */
    private static volatile SpeakerState.Status status = SpeakerState.Status.ACTIVE;
    /** 上一次提示的状态(null = 从未提示):状态沿触发,不刷屏。 */
    private static SpeakerState.Status lastAnnounced;

    private SpeakerManager() {
    }

    /** mod 初始化时调用:读回上次放置的锚点(坏数据按无锚点处理)。 */
    public static void init() {
        final SpeakerState loaded = SpeakerStore.load();
        if (loaded != null) {
            SpeakerState.set(loaded);
            status = SpeakerState.Status.ACTIVE;
        }
    }

    /** 当前降级状态(渲染桥取色用)。 */
    public static SpeakerState.Status currentStatus() {
        return status;
    }

    /** 有没有放着音箱。 */
    public static boolean hasSpeaker() {
        return SpeakerState.active();
    }

    /**
     * 探针的采样原点请求:锚点存在、在本维度、在射程内且区块已加载时返回
     * 锚点,否则 null(探针退回「玩家即声源」旧语义)。在后台采样线程上调用
     * —— 只读 volatile 与传入的 world,无方块访问(区块判定由 tick 预先算好)。
     */
    public static SpeakerState samplingOrigin(Level world) {
        final SpeakerState s = SpeakerState.get();
        if (s == null || world == null) {
            return null;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return null;
        }
        if (status != SpeakerState.Status.ACTIVE) {
            return null;
        }
        return s;
    }

    /** 单键智能切换:由按键 tick 桥调用(仅游戏线程)。 */
    public static void toggle() {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        final SpeakerState existing = SpeakerState.get();
        if (existing != null) {
            final HitResult hit = mc.hitResult;
            if (hit != null && hit.getType() == HitResult.Type.BLOCK
                    && hit.getLocation().distanceToSqr(existing.x(), existing.y(), existing.z())
                            < REMOVE_RADIUS * REMOVE_RADIUS) {
                remove(mc);
                return;
            }
        }
        place(mc);
    }

    /** 放置:准星命中面外浮 0.5 格。 */
    private static void place(Minecraft mc) {
        final HitResult hit = mc.hitResult;
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            say(mc, "§e[声源] 需要一个落点:准星对准方块表面再按。");
            return;
        }
        final BlockHitResult blockHit = (BlockHitResult) hit;
        final Vec3 loc = blockHit.getLocation();
        final Vec3 normal = new Vec3(blockHit.getDirection().getStepX(),
                blockHit.getDirection().getStepY(), blockHit.getDirection().getStepZ());
        final Vec3 anchor = loc.add(normal.scale(SURFACE_OFFSET));
        final SpeakerState placed = SpeakerState.place(
                anchor.x, anchor.y, anchor.z, mc.level.dimension().toString());
        if (placed == null) {
            say(mc, "§c[声源] 坐标非法,放置失败。");
            return;
        }
        SpeakerState.set(placed);
        status = SpeakerState.Status.ACTIVE;
        lastAnnounced = null; // 新锚点:状态提示重新计
        SpeakerStore.save(placed);
        say(mc, String.format("§a[声源] 已放置在 (%.1f, %.1f, %.1f)。再按一次(瞄准它)移除,瞄准别处挪位。",
                anchor.x, anchor.y, anchor.z));
    }

    /** 移除。 */
    private static void remove(Minecraft mc) {
        SpeakerState.set(null);
        status = SpeakerState.Status.ACTIVE;
        lastAnnounced = null;
        SpeakerStore.save(null);
        say(mc, "§a[声源] 已移除,音乐回到随身(玩家即声源)。");
    }

    /**
     * 每 tick 降级判定与状态沿提示(仅游戏线程)。判定顺序:维度 → 射程 →
     * 区块;全部通过 = ACTIVE。音箱本身的世界读不到(区块卸载)也无需读
     * —— 判定全部用锚点坐标与玩家状态,零方块访问。
     */
    public static void tick() {
        final Minecraft mc = Minecraft.getInstance();
        final SpeakerState s = SpeakerState.get();
        if (s == null) {
            status = SpeakerState.Status.ACTIVE;
            lastAnnounced = null;
            return;
        }
        if (mc.player == null || mc.level == null) {
            // 主菜单:不清锚点(回到世界还能用),也不提示。
            return;
        }
        final SpeakerState.Status prev = status;
        if (!mc.level.dimension().toString().equals(s.dimension())) {
            status = SpeakerState.Status.DIMENSION_MISMATCH;
        } else {
            final Vec3 eye = mc.player.getEyePosition();
            final double dist = s.distanceTo(eye.x, eye.y, eye.z);
            if (dist > SpeakerState.RANGE_METERS) {
                status = SpeakerState.Status.OUT_OF_RANGE;
            } else if (!chunkLoaded(mc.level, s)) {
                status = SpeakerState.Status.CHUNK_UNLOADED;
            } else {
                status = SpeakerState.Status.ACTIVE;
            }
        }
        if (status != prev || (status != SpeakerState.Status.ACTIVE && lastAnnounced != status)) {
            announceStatus(mc, status);
        }
        lastAnnounced = status;
    }

    /** 区块已加载判定:锚点所在 chunk 在客户端 chunk 缓存里。 */
    private static boolean chunkLoaded(Level world, SpeakerState s) {
        final BlockPos pos = BlockPos.containing(s.x(), s.y(), s.z());
        return world.hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static void announceStatus(Minecraft mc, SpeakerState.Status st) {
        switch (st) {
            case OUT_OF_RANGE -> say(mc, "§e[声源] 已超出有效范围(" + (int) SpeakerState.RANGE_METERS
                    + " 格),音量淡出;走近自动恢复。");
            case DIMENSION_MISMATCH -> say(mc, "§e[声源] 音箱在其他维度,这里听不到。");
            case CHUNK_UNLOADED -> say(mc, "§e[声源] 音箱所在区块已卸载,声音降级;回到加载范围恢复。");
            case ACTIVE -> say(mc, "§a[声源] 信号恢复。");
        }
    }

    private static void say(Minecraft mc, String text) {
        try {
            mc.gui.hud.getChat().addClientSystemMessage(Component.literal(text));
        } catch (Throwable ignored) {
            // 聊天栏偶尔不可用(截图/加载屏):提示是锦上添花,静默。
        }
    }
}
