package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.io.File;

/**
 * 「播放时压掉原版游戏音乐」的门控，给各版本树的 {@code MusicManager} mixin 当判据。
 *
 * <h2>为什么压的是 MusicManager.tick 而不是音量或声道</h2>
 * <ul>
 *   <li><b>压音量</b>（把 music 类别拉到 0）：用户自己的音量设置被改写，退出播放还得恢复原值，
 *       中途用户手动调音乐滑条会打架；而且 {@code MusicManager} 会照常推进倒计时、照常起播，
 *       只是听不见 —— 恢复的一瞬间可能已经切了两首。</li>
 *   <li><b>暂停全部声道</b>（SoundEngine.pause）：把音效、环境音、脚步声一起压掉了，过度。</li>
 *   <li><b>拦 tick</b>：本 mod 在放歌时 {@code tick()} 直接短路 —— 不起播、不推进 {@code nextSongDelay}、
 *       已在响的那首由 mixin 主动 stop 一次。本 mod 停止后 tick 恢复原样，倒计时从头走，
 *       原版音乐自己回来。只影响「游戏音乐」这一件事；唱片机不挂在 MusicManager 上，不受影响。</li>
 * </ul>
 *
 * <h2>判据</h2>
 * <p>问的是<b>本 mod 的音频引擎是否在出声</b>：{@code AudioPlayer.isPlaying()}（引擎线程态）
 * 加 {@code isPlayRequested()}（命令已下、声音未出的那一小段 —— 不算上的话每首歌开头都会
 * 漏进半句原版音乐）。外部播放器镜像（SMTC）<b>不</b>算：那条路里游戏里没有我们引擎的声音，
 * 压不压原版音乐由用户自己按系统音量决定，不该替他静掉游戏。一起听房间里被房间接管播放时
 * 引擎就是出声方，天然覆盖，无需单列。</p>
 *
 * <p>所有调用点必须<b>不抛</b>：mixin 的 HEAD 每一次原版 tick 都会走到，这里任何一条链路炸了
 * 都会把原版声音系统整个带走 —— 所以每一问都单独包 Throwable，任何异常一律视为「不压」。</p>
 *
 * <h2>持久化</h2>
 * <p>开关落盘在 {@code config/muonium/vanilla_music_ducker.json}（照 {@code AutomixSettings} 的
 * 模式：懒加载、锁内读写、写失败静默）。mixin 在每次原版 tick 上读这个开关，所以读取路径
 * 必须<b>零 IO</b>：{@code loaded} 置位后只剩一次锁内布尔读。</p>
 */
public final class VanillaMusicDucker {

    private static final Gson GSON = new GsonBuilder().create();
    private static final Object LOCK = new Object();

    private static boolean loaded;
    /** 开关（默认开）。落盘见类注释。 */
    private static boolean enabled = true;

    private VanillaMusicDucker() {
    }

    /** 本 mod 正在出声：原版音乐该让路。 */
    public static boolean shouldDuck() {
        try {
            AudioPlayer player = CloudMusic.player;
            if (player == null) return false;
            // 引擎线程态在响，或命令已下、声音还没出来的那一小段，都算。
            if (player.isPlaying() || player.isPlayRequested()) return true;
        } catch (Throwable ignored) {
            // 播放器实例正在换歌/关闭的瞬间可能处于半初始化态，读它出异常一律按「不压」。
        }
        return false;
    }

    public static boolean isEnabled() {
        load();
        synchronized (LOCK) {
            return enabled;
        }
    }

    public static void setEnabled(boolean value) {
        load();
        synchronized (LOCK) {
            if (enabled == value) return;
            enabled = value;
            saveLocked();
        }
    }

    /** 门控合成：开关开着且本 mod 在出声。mixin 只问这一个。 */
    public static boolean active() {
        // 不加锁：mixin 每拍都来，锁竞争没有必要。volatile 语义由「只在锁内写」+
        // JIT 不会把单布尔读提出循环的现有保证兜着，最坏读到上一拍的开与关。
        return enabled && shouldDuck();
    }

    private static void load() {
        synchronized (LOCK) {
            if (loaded) return;
            loaded = true;
            try {
                File file = ConfigPaths.VANILLA_MUSIC_DUCKER;
                if (!file.isFile()) return;
                JsonObject root = JsonConfigStorage.readObject(file, GSON);
                if (root == null) return;
                if (root.has("enabled") && !root.get("enabled").isJsonNull()) {
                    enabled = root.get("enabled").getAsBoolean();
                }
            } catch (Throwable ignored) {
                // 配置坏了用默认值，不能影响播放。
            }
        }
    }

    private static void saveLocked() {
        try {
            File parent = ConfigPaths.VANILLA_MUSIC_DUCKER.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) return;
            JsonObject root = new JsonObject();
            root.addProperty("enabled", enabled);
            JsonConfigStorage.writeObject(ConfigPaths.VANILLA_MUSIC_DUCKER, GSON, root);
        } catch (Throwable ignored) {
        }
    }
}
