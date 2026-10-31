package com.muoniumplayer.core.thirdparty;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gnetum 共存兼容（{@link GnetumCompat}）的行为与接线不变量。
 *
 * <p>这一层会<b>改写另一个 mod 的配置文件</b>，写错了不是难看是事故（把用户的
 * gnetum.json 写成半残、或者把无关元素一并关掉），所以两路都钉：
 * JSON 改写逻辑用真对象跑（{@link #patchElementMap} 是纯函数、不落盘），
 * 闸门与五树接线用源码形状钉（与 GameRestart / 公告同一路数）。</p>
 */
class GnetumCompatStaticTest {

    /** 五棵版本树（各树 MuoniumPlayerClient 的 tick 钩子是本兼容唯一的执行入口）。 */
    private static final String[] TREES = {
            "1.20.1 recode", "1.21.1", "1.21.11 recode", "26.2", "26.3"};

    // ==================== JSON 改写逻辑（真对象跑） ====================

    @Test
    @DisplayName("patchElementMap：muonium 键 AUTO → OFF；已是 OFF 幂等返回 null")
    void patchesOwnElementAndIsIdempotent() {
        JsonObject root = parse("""
                {"enabled": {"value": "ON"},
                 "map": {"muonium": {"enabled": {"value": "AUTO"}}}}
                """);
        assertEquals("muonium", GnetumCompat.patchElementMap(root));
        assertEquals("OFF", valueOf(root, "muonium"), "AUTO 必须被改写成 OFF（Gnetum 语义 = 不缓存、每帧渲染）");

        // 第二遍：已经是 OFF，必须原样返回 null —— 重复启动不重写文件、不发聊天
        assertNull(GnetumCompat.patchElementMap(root));
        assertEquals("OFF", valueOf(root, "muonium"));
    }

    @Test
    @DisplayName("patchElementMap：NeoForge 解析不出归属时落到 _unknown_listeners_ 兜底键")
    void fallsBackToUnknownListenersKey() {
        JsonObject root = parse("""
                {"map": {"_unknown_listeners_": {"enabled": {"value": "AUTO"}}}}
                """);
        assertEquals("_unknown_listeners_", GnetumCompat.patchElementMap(root));
        assertEquals("OFF", valueOf(root, "_unknown_listeners_"));
    }

    @Test
    @DisplayName("patchElementMap：元素表里没有我们时新建 muonium 键，不碰别人的条目")
    void createsOwnEntryWithoutTouchingOthers() {
        JsonObject root = parse("""
                {"map": {"sodium": {"enabled": {"value": "AUTO"}},
                         "journeymap": {"enabled": {"value": "ON"}}}}
                """);
        assertEquals("muonium", GnetumCompat.patchElementMap(root));
        assertEquals("OFF", valueOf(root, "muonium"));
        assertEquals("AUTO", valueOf(root, "sodium"), "不许动无关元素的缓存设置");
        assertEquals("ON", valueOf(root, "journeymap"));
    }

    @Test
    @DisplayName("patchElementMap：enabled 字段缺失/形状不对时补建或放弃，绝不写半残文件")
    void toleratesBrokenShapes() {
        // 元素在但 enabled 缺失：补一个 enabled.value=OFF
        JsonObject missingEnabled = parse("""
                {"map": {"muonium": {}}}
                """);
        assertEquals("muonium", GnetumCompat.patchElementMap(missingEnabled));
        assertEquals("OFF", valueOf(missingEnabled, "muonium"));

        // 没有 map：放弃（返回 null），不瞎建结构
        JsonObject noMap = parse("{\"enabled\": {\"value\": \"ON\"}}");
        assertNull(GnetumCompat.patchElementMap(noMap));
        assertFalse(noMap.has("map"), "没有元素表时不许凭空造一个");
    }

    @Test
    @DisplayName("gnetumEnabled：总开关 OFF 时不接管；缺失按开对待（Gnetum 默认就是开）")
    void respectsGnetumMasterSwitch() {
        assertTrue(GnetumCompat.gnetumEnabled(parse("{\"enabled\": {\"value\": \"ON\"}}")));
        assertFalse(GnetumCompat.gnetumEnabled(parse("{\"enabled\": {\"value\": \"OFF\"}}")),
                "Gnetum 总开关关着时不存在闪烁，整个补丁必须静默跳过");
        assertTrue(GnetumCompat.gnetumEnabled(parse("{}")), "字段缺失按开对待");
    }

    // ==================== 源码形状（闸门 / 探测 / 接线） ====================

    @Test
    @DisplayName("共享层铁律：零加载器符号；探测与 reload 只走类路径反射")
    void noLoaderSymbolsAndReflectionOnly() throws IOException {
        String src = read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "core", "thirdparty", "GnetumCompat.java")));

        assertFalse(src.contains("net.fabricmc") || src.contains("net.neoforged")
                        || src.contains("net.minecraftforge") || src.contains("cpw.mods"),
                "muonium-common 不许出现加载器符号（铁律 1）");

        // 探测：类路径 Class.forName，不走 ModList / FabricLoader
        assertTrue(src.contains("me.decce.gnetum.Gnetum\""), "探测目标必须是 Gnetum 主类");
        assertTrue(src.contains("Class.forName("), "探测要走类路径反射");
        assertFalse(src.contains("isModLoaded") || src.contains("ModList") || src.contains("FabricLoader"),
                "装没装 Gnetum 由 Class.forName 回答，不许引入加载器 API");

        // 即时生效：反射调 GnetumConfig.reload()（public static），失败降级为「重启后生效」
        assertTrue(src.contains("me.decce.gnetum.GnetumConfig"), "reload 反射目标类");
        assertTrue(src.contains("getMethod(\"reload\")"), "必须反射 reload 让修改即时生效");

        // 配置文件路径与 Gnetum 自身逐字节同源：CWD 相对路径，不是加载器 configDir
        assertTrue(src.contains("Paths.get(\"config\", \"gnetum.json\")"),
                "Gnetum 自己就用 CWD 相对 config/gnetum.json，路径必须同源");
        assertFalse(src.contains("getConfigDir") || src.contains("FMLPaths"),
                "不许用加载器 configDir——Gnetum 读的是 CWD 相对路径，两者会指向不同文件");

        // 写入值：两处写入点（新建条目 + 改写既有条目），目标值都只能是 OFF
        assertEquals(2, countOf(src, "addProperty(\"value\", \"OFF\")"),
                "写入点恰好两处、值都是 OFF；出现第三处说明改写逻辑分叉了");
        assertTrue(src.contains("patchedKey == null"), "没有改动就不许发聊天/写文件（幂等闸）");
    }

    @Test
    @DisplayName("闸门：本次启动只查一次；异常整体兜底不重试")
    void oncePerLaunchGateIsPinned() throws IOException {
        String src = read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "core", "thirdparty", "GnetumCompat.java")));

        // 先落闸再跑：checked = true 出现在 run(api) 之前，失败也不会每 tick 重试刷屏
        int gate = src.indexOf("checked = true;");
        int run = src.indexOf("run(api);");
        assertTrue(gate >= 0 && run > gate, "必须先把「只查一次」的闸落下再执行检查");

        assertTrue(src.contains("if (checked || !inGame || api == null)"),
                "未进世界 / api 未就绪 / 已查过，都只是一次布尔判断");
        assertTrue(src.contains("catch (Throwable t)"), "整体兜底，任何异常不影响游戏");
    }

    @Test
    @DisplayName("接线：五棵树 tick 都挂上，进世界判据同一条")
    void wiringIsInPlaceOnEveryTree() throws IOException {
        for (String tree : TREES) {
            Path client = ProjectPaths.repoRoot().resolve(Paths.get(
                    tree, "src", "main", "java", "com", "muoniumplayer", "MuoniumPlayerClient.java"));
            assertTrue(Files.isRegularFile(client), "树缺 MuoniumPlayerClient: " + client);
            String text = new String(Files.readAllBytes(client), StandardCharsets.UTF_8);
            assertTrue(text.contains("GnetumCompat.clientTick("),
                    tree + ": 未挂 Gnetum 共存兼容 tick 钩子");
            assertTrue(text.contains("mc.player != null && mc.level != null, this.api"),
                    tree + ": 进世界判据必须是 player+level 同源写法");
        }
    }

    // ==================== 帮手 ====================

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static String valueOf(JsonObject root, String key) {
        return root.getAsJsonObject("map").getAsJsonObject(key)
                .getAsJsonObject("enabled").get("value").getAsString();
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        int at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) {
            count++;
            at += needle.length();
        }
        return count;
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
