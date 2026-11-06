package com.muoniumplayer.core.preset.domain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.model.KeyPolicy;

import java.util.List;
import java.util.Map;

/**
 * 一个配置域在模板预设里的接缝：一个落盘设置的拥有者（{@code HudConfig}、{@code NCMTheme}…）
 * 通过它被快照、被重置、被套用。
 *
 * <p><b>取值校验不在这里写第二份</b>：{@link #apply} 拿到的是「当前完整快照 ⊕ 预设片段」，交给设置类原有的
 * 读档逻辑（钳位、旧键迁移、副作用）去处理，等价于「手改配置文件后重载」。这里只负责：</p>
 * <ul>
 *   <li>{@link #policy()}：哪些键能进预设、各属于哪个分面；</li>
 *   <li>{@link #sanitize}：外来值里引用本地资源 / 路径 / 网络的那几项（字体文件、卷积核、自定义图片）；</li>
 *   <li>{@link #apply} 之后的刷新：让已经缓存了旧值的组件（HUD 模块开关、歌词颜色值对象）跟上。</li>
 * </ul>
 *
 * <p>所有方法都在客户端主线程上调用。</p>
 */
public interface PresetDomain {

    /** 写进预设文件的稳定 id，永不改名。 */
    String id();

    /** 给玩家看的名字（出现在「××套用失败」这类提示里）。 */
    String displayName();

    /** 本域片段的格式版本；键改名时 +1，并在 {@link #migrate} 里把旧键翻译成新键。 */
    int fragmentVersion();

    /** 分享码里的配置域编号（1 起），一经发布永不改、永不复用。 */
    int shareCode();

    /**
     * 分享码键表：第 i 个（从 1 数）就是键编号 i。类似 protobuf 的字段号 ——
     * <b>只许在末尾追加，已发布的位置永不改、永不复用</b>（{@code PresetShareKeysFreezeTest} 钉着已发布的前缀）。
     *
     * <p>编号表达的是「哪一个设置」而不是名字：键改名时原位改名字，旧码照样解到新键；
     * 不再分享的键留着占位（解码时会被白名单挡掉），绝不删除、不让后面的编号前移。</p>
     */
    List<String> shareKeys();

    /**
     * 分享码里把大块的值压小（例如 ViPER 预设体只留非默认字段），解码后由 {@link #apply} 照常还原。
     * 默认原样返回。必须是纯函数。
     */
    default JsonElement compactValue(String key, JsonElement value) {
        return value;
    }

    KeyPolicy policy();

    /** 当前完整状态（形状与落盘文件 / 视图相同）。 */
    JsonObject snapshot();

    /** 出厂状态（形状同 {@link #snapshot()}）。 */
    JsonObject defaults();

    /** 套用一份完整状态并落盘、刷新相关组件。遇到「做不到」的项写进 {@code report}，不要抛。 */
    void apply(JsonObject state, CompatReport report);

    /**
     * 外来值的最后一道闸：返回要写入的值，返回 {@code null} 表示丢弃（必须同时在 {@code report} 里说明）。
     * 预检与套用都会调用，<b>不许有副作用</b>。
     */
    default JsonElement sanitize(String key, JsonElement value, CompatReport report) {
        return value;
    }

    /** 旧片段（版本低于 {@link #fragmentVersion()}）的键迁移；拿到的是拍平后的键值。 */
    default Map<String, JsonElement> migrate(Map<String, JsonElement> flat, int fromVersion) {
        return flat;
    }
}
