package com.muoniumplayer.core.ncm.music.acoustics;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.io.IOException;

/**
 * 世界声源锚点的落盘记忆:退出游戏重进,音箱还在原地。
 *
 * <p>按项目惯例(小状态独立文件,读失败回空):speaker.json 只存一组
 * {@code x/y/z/dimension},读写都尽力而为 —— 文件坏了/删了,代价只是
 * 「这次没有音箱」,绝不牵连别的配置。写盘在游戏线程的放置/移除时点,
 * 频率极低,无需异步。</p>
 *
 * <p>数据契约:文件里的数字永远是不可信输入,读出后必过
 * {@link SpeakerState#sanitize};坐标 NaN/超界、维度串损坏 → 按无锚点处理。</p>
 */
public final class SpeakerStore {

    private static final Gson GSON = new Gson();

    private SpeakerStore() {
    }

    /** 读锚点;无文件/损坏/字段缺失 → null。 */
    public static SpeakerState load() {
        try {
            final var file = ConfigPaths.SPEAKER;
            if (!file.isFile()) {
                return null;
            }
            final JsonObject root = JsonConfigStorage.readObject(file, GSON);
            if (root == null || !root.has("x") || !root.has("y") || !root.has("z")) {
                return null;
            }
            return SpeakerState.sanitize(
                    root.get("x").getAsDouble(),
                    root.get("y").getAsDouble(),
                    root.get("z").getAsDouble(),
                    root.has("dimension") && !root.get("dimension").isJsonNull()
                            ? root.get("dimension").getAsString() : null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 存锚点;state=null 删除文件。失败静默(下次放置会再写)。 */
    public static void save(SpeakerState state) {
        try {
            final var file = ConfigPaths.SPEAKER;
            if (state == null) {
                // noinspection ResultOfMethodCallIgnored
                file.delete();
                return;
            }
            final JsonObject root = new JsonObject();
            root.addProperty("x", state.x());
            root.addProperty("y", state.y());
            root.addProperty("z", state.z());
            root.addProperty("dimension", state.dimension());
            JsonConfigStorage.writeObject(file, GSON, root);
        } catch (Throwable ignored) {
            // 尽力而为:落盘失败不影响本局使用。
        }
    }

    /** 仅供测试:确认 IOException 不会往上抛的路径覆盖不了时手工触发。 */
    static void probeIo() throws IOException {
        if (Boolean.getBoolean("muonium.speaker.never")) {
            throw new IOException("probe");
        }
    }
}
