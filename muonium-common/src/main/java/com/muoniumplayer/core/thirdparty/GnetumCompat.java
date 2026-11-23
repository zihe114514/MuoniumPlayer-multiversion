package com.muoniumplayer.core.thirdparty;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.api.OpenAPI;
import com.muoniumplayer.api.enums.EnumChatColor;
import com.muoniumplayer.core.utils.logging.LogManager;
import com.muoniumplayer.core.utils.logging.Logger;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * Gnetum（HUD 分帧缓存 mod，mod id {@code gnetum}）共存兼容。
 *
 * <h2>为什么需要这一层</h2>
 * Gnetum 把每个 HUD 元素（含各 mod 的 HUD 回调）当成可缓存元素，按 pass 分帧调用：
 * 轮不到的帧<b>根本不调用回调</b>，靠它 front framebuffer 里缓存的 HUD 贴图顶替。
 * 走原版 GUI 管线的 HUD 被跳帧时缓存里有像素，只是降帧；而本 mod 的 HUD 是
 * Skija 离屏画布 + 帧末合成（1.21.x 挂 {@code RenderTarget#blitToScreen} TAIL、
 * 26.x 挂 {@code GlSurface.present} 入口）的<b>旁路渲染</b>，Gnetum 的缓存 FBO 里
 * 从来没有过我们的像素，而我们的合成又只在「回调真的跑过」的帧发生 ——
 * 被跳帧就是整块 HUD 消失，下个 pass 帧又出现，表现为 HUD 界面剧烈闪烁。
 *
 * <h2>修法：替用户把 Gnetum 对我们元素的缓存关掉</h2>
 * Gnetum 的配置 {@code config/gnetum.json}（相对<b>工作目录</b>——Gnetum 自己就是
 * 这么定位的，不是加载器 configDir）里 {@code map.<键>.enabled.value} 为
 * {@code "OFF"} 时该元素每帧照常渲染。键的取值：
 * <ul>
 *   <li>Fabric：有序 HUD 注册使用完整元素 ID，早期回调使用 {@code "muonium"}；</li>
 *   <li>NeoForge：解析不出监听器归属时落到 {@code "_unknown_listeners_"} 兜底键。</li>
 * </ul>
 * 提供完整元素 ID 的宿主直接更新对应缓存对象的开关，保留 Gnetum 的渲染计时状态。
 * 旧宿主沿用 {@code GnetumConfig.reload()}。即时应用失败时，文件修改在重启后生效。
 *
 * <h2>闸门</h2>
 * <ul>
 *   <li>本次启动只查一次（进世界后的第一个客户端 tick；主菜单里 HUD 本就不渲染，不闪）；</li>
 *   <li>没装 Gnetum、总开关关着、配置文件还没落盘、目标键已经是 OFF —— 全部静默跳过，
 *       只有<b>真的改了文件</b>才发聊天提示；</li>
 *   <li>整体 try/catch 兜底：任何异常只记一次日志，绝不影响游戏。</li>
 * </ul>
 *
 * <p>纯 Java + Gson + {@link OpenAPI}，零加载器符号；五树共用这一份实现，
 * 各树只在 {@code MuoniumPlayerClient.onClientTick} 里加一行调用。</p>
 */
public final class GnetumCompat {

    private static final Logger logger = LogManager.getLogger("MuoniumPlayer/GnetumCompat");

    private static final String GNETUM_MAIN_CLASS = "me.decce.gnetum.Gnetum";
    private static final String GNETUM_CONFIG_CLASS = "me.decce.gnetum.GnetumConfig";

    /** 与 Gnetum 自身的读写路径逐字节一致（GnetumConfig 静态块用的就是 CWD 相对路径）。 */
    private static final Path CONFIG_PATH = Paths.get("config", "gnetum.json");

    /** Gnetum 元素表里本 mod HUD 回调的键（Fabric 按回调类 modid 解析）。 */
    private static final String KEY_MUONIUM = "muonium";
    /** NeoForge 解析不出监听器归属时的兜底键（Gnetum {@code Constants.UNKNOWN_LISTENERS}）。 */
    private static final String KEY_UNKNOWN_LISTENERS = "_unknown_listeners_";

    /** 本次启动是否已经查过（无论结论如何都不再查第二次）。 */
    private static boolean checked;

    private GnetumCompat() {
    }

    /**
     * 各树 {@code MuoniumPlayerClient.onClientTick} 每 tick 调。
     *
     * @param inGame 本 tick 是否已在世界里（{@code mc.player != null && mc.level != null}）
     * @param api    客户端 OpenAPI（聊天提示走 {@link OpenAPI#printMessage}）
     */
    public static void clientTick(boolean inGame, OpenAPI api) {
        clientTick(inGame, api, null);
    }

    /**
     * @param elementId Gnetum 元素表里的完整 ID，提供时按该 ID 单独改写和应用
     */
    public static void clientTick(boolean inGame, OpenAPI api, String elementId) {
        if (checked || !inGame || api == null) {
            return;
        }
        checked = true; // 先落闸再跑：失败也不重试刷屏，下次启动再查
        try {
            run(api, elementId);
        } catch (Throwable t) {
            logger.warn("Gnetum 共存检查失败（不影响游戏运行）: " + t);
        }
    }

    private static void run(OpenAPI api, String elementId) throws Exception {
        if (!isGnetumInstalled()) {
            return;
        }
        if (!Files.exists(CONFIG_PATH)) {
            // 理论不该发生（Gnetum 在 onGameLoadFinished 就 gather + save，早于进世界）；
            // 万一撞上（首次启动竞态），本次跳过，下个启动补上。
            logger.info("已安装 Gnetum 但 " + CONFIG_PATH + " 还不存在，本次跳过");
            return;
        }
        JsonObject root = JsonParser.parseString(Files.readString(CONFIG_PATH)).getAsJsonObject();

        if (!gnetumEnabled(root)) {
            return; // Gnetum 总开关关着：HUD 没被它接管，不存在闪烁，别打扰用户
        }

        String patchedKey = patchElementMap(root, elementId);
        if (patchedKey == null) {
            return; // 已经是 OFF（或形状读不出来）：什么都不做也不说话（幂等，重复启动不刷屏）
        }

        Files.writeString(CONFIG_PATH,
                new GsonBuilder().setPrettyPrinting().create().toJson(root),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);

        boolean instant = false;
        try {
            if (elementId != null) {
                instant = disableLiveElement(elementId);
            } else {
                Class.forName(GNETUM_CONFIG_CLASS, false, GnetumCompat.class.getClassLoader())
                        .getMethod("reload")
                        .invoke(null);
                instant = true;
            }
        } catch (Throwable t) {
            logger.info("未能即时更新 Gnetum HUD 缓存，修改将在重启后生效: " + t);
        }

        api.printMessage(EnumChatColor.YELLOW
                + "检测到 Gnetum：已在 config/gnetum.json 中关闭 " + patchedKey + " 的 HUD 缓存，"
                + "避免 MuoniumPlayer 的 HUD 界面闪烁。");
        api.printMessage(EnumChatColor.GRAY + (instant
                ? "已即时生效，无需重启。"
                : "重启游戏后生效。") + "想改回去：在 Gnetum 视频设置中重新开启该元素的缓存。");
    }

    private static boolean disableLiveElement(String elementId) throws ReflectiveOperationException {
        Class<?> gnetum = Class.forName(GNETUM_MAIN_CLASS, false, GnetumCompat.class.getClassLoader());
        Object config = gnetum.getField("config").get(null);
        Map<?, ?> elements = (Map<?, ?>) config.getClass().getField("map").get(config);
        Object element = elements.get(elementId);
        if (element == null) {
            return false;
        }
        // 与 Gnetum 的 Sodium 设置相同：改写现有元素的值，保留计时数组和 pass。
        Object enabled = element.getClass().getField("enabled").get(element);
        Field value = enabled.getClass().getField("value");
        value.set(enabled, value.getType().getField("OFF").get(null));
        return true;
    }

    /**
     * 就地改写 Gnetum 的元素表：把我们的键的缓存关成 {@code OFF}。
     *
     * @return 被修改（或新建）的元素键；无需修改（已是 OFF / 形状读不出来）返回 {@code null}
     */
    static String patchElementMap(JsonObject root, String elementId) {
        JsonElement mapRaw = root.get("map");
        if (mapRaw == null || !mapRaw.isJsonObject()) {
            return null;
        }
        JsonObject map = mapRaw.getAsJsonObject();

        String key = elementId;
        if (key == null) {
            // 旧宿主按 modid 查找，NeoForge 解析不出归属时才落到未知监听器兜底键。
            key = map.has(KEY_MUONIUM) ? KEY_MUONIUM
                    : map.has(KEY_UNKNOWN_LISTENERS) ? KEY_UNKNOWN_LISTENERS : null;
        } else if (!map.has(key)) {
            return null;
        }
        if (key == null) {
            // 元素表里还没有我们（极早期边缘）。补一个键是安全的：
            // Gnetum gather 重建元素表时对已有键只拷贝 enabled、不会覆盖。
            key = KEY_MUONIUM;
            JsonObject element = new JsonObject();
            JsonObject enabled = new JsonObject();
            enabled.addProperty("value", "OFF");
            element.add("enabled", enabled);
            map.add(key, element);
            return key;
        }

        JsonElement elementRaw = map.get(key);
        if (!elementRaw.isJsonObject()) {
            return null; // 形状不对（手工改坏了）：宁可不碰也别把文件写成半残
        }
        JsonObject element = elementRaw.getAsJsonObject();
        JsonElement enabledRaw = element.get("enabled");
        JsonObject enabled;
        if (enabledRaw == null || !enabledRaw.isJsonObject()) {
            enabled = new JsonObject();
            element.add("enabled", enabled);
        } else {
            enabled = enabledRaw.getAsJsonObject();
        }
        JsonElement valueRaw = enabled.get("value");
        if (valueRaw != null && valueRaw.isJsonPrimitive()
                && "OFF".equals(valueRaw.getAsString())) {
            return null; // 已经关了：幂等
        }

        enabled.addProperty("value", "OFF");
        return key;
    }

    /** 探测 Gnetum 是否安装：类在类路径上即视为已装（零加载器符号，五树通用）。 */
    private static boolean isGnetumInstalled() {
        try {
            Class.forName(GNETUM_MAIN_CLASS, false, GnetumCompat.class.getClassLoader());
            return true;
        } catch (Throwable notInstalledOrBroken) {
            // ClassNotFoundException = 没装；NoClassDefFoundError 等链接问题 = 按「不可用」对待，
            // 宁可少管也不要误伤
            return false;
        }
    }

    /**
     * Gnetum 总开关（{@code enabled.value}）是否处于打开状态。
     * 字段缺失按打开对待（保守：Gnetum 默认就是开）。
     */
    static boolean gnetumEnabled(JsonObject root) {
        JsonElement raw = root.get("enabled");
        if (raw == null || !raw.isJsonObject()) {
            return true;
        }
        JsonElement value = raw.getAsJsonObject().get("value");
        return !(value != null && value.isJsonPrimitive() && "OFF".equals(value.getAsString()));
    }
}
