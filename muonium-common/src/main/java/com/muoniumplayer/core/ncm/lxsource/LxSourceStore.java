package com.muoniumplayer.core.ncm.lxsource;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * 已导入的自定义源脚本，以及当前启用的是哪一个。
 *
 * <p>脚本正文按上游的格式存：{@code "gz_" + base64(deflate(utf8))}
 * （见 lx-music-desktop 的 {@code userApi/utils.ts}）。照抄这个格式不是为了省空间，
 * 是为了让两边的存档可以互相搬 —— 用户从 LX 的配置里拷一段过来，这边能直接读。</p>
 */
public final class LxSourceStore {

    private static final Object LOCK = new Object();
    private static final Gson GSON = new Gson();

    /**
     * 内置源的固定 ID（jar 里 {@code /muonium/lx/builtin_source.js} 那一份）。
     * 与用户导入的 {@code user_api_*} 随机 ID 区分开：升级换脚本时按它找到旧条目整条替换。
     */
    public static final String BUILTIN_ID = "builtin_wildgrass";
    /** jar 内置脚本的资源路径。 */
    private static final String BUILTIN_RESOURCE = "/muonium/lx/builtin_source.js";

    private static boolean loaded;
    private static String activeId = "";
    /**
     * 宿主扩展：要不要放开上游动作白名单之外的 {@code lyric} / {@code pic} / {@code search}。
     *
     * <p>放在这份存档里而不是 {@code player.json}：它是自定义源自己的口径，跟着脚本走；
     * 而且 {@code player.json} 每改一次设置都要整份重写，这个开关不该被卷进去。</p>
     */
    private static boolean extraActions;
    private static final List<Entry> ENTRIES = new ArrayList<>();

    private LxSourceStore() {
    }

    /** 一条已导入的脚本记录。{@code script} 是压缩后的正文，不直接给外部用。 */
    public static final class Entry {
        public final String id;
        public final LxScriptInfo info;
        public final boolean allowUpdateAlert;
        private final String storedScript;

        Entry(String id, LxScriptInfo info, boolean allowUpdateAlert, String storedScript) {
            this.id = id;
            this.info = info;
            this.allowUpdateAlert = allowUpdateAlert;
            this.storedScript = storedScript;
        }

        public String getSource() {
            return inflateScript(storedScript);
        }
    }

    public static List<Entry> list() {
        load();
        synchronized (LOCK) {
            return Collections.unmodifiableList(new ArrayList<>(ENTRIES));
        }
    }

    public static String getActiveId() {
        load();
        synchronized (LOCK) {
            return activeId;
        }
    }

    public static Entry find(String id) {
        load();
        synchronized (LOCK) {
            for (Entry entry : ENTRIES) {
                if (entry.id.equals(id)) return entry;
            }
            return null;
        }
    }

    public static Entry getActive() {
        String id = getActiveId();
        return id.isEmpty() ? null : find(id);
    }

    /** 空字符串表示关闭自定义源。 */
    public static void setActiveId(String id) {
        load();
        synchronized (LOCK) {
            activeId = id == null ? "" : id;
            save();
        }
    }

    public static boolean isExtraActionsEnabled() {
        load();
        synchronized (LOCK) {
            return extraActions;
        }
    }

    public static void setExtraActions(boolean enabled) {
        load();
        synchronized (LOCK) {
            if (extraActions == enabled) return;
            extraActions = enabled;
            save();
        }
    }

    /**
     * @throws IllegalArgumentException 头注释缺失，或正文与已导入的某个脚本完全相同
     */
    public static Entry importScript(String rawSource) {
        LxScriptInfo info = LxScriptInfo.parse(rawSource);
        String stored = deflateScript(rawSource);
        load();
        synchronized (LOCK) {
            for (Entry entry : ENTRIES) {
                if (entry.storedScript.equals(stored)) {
                    throw new IllegalArgumentException("导入失败：内容与已有的源「" + entry.info.getName() + "」相同");
                }
            }
            String id = "user_api_" + Integer.toString((int) (Math.random() * 900 + 100)) + "_" + System.currentTimeMillis();
            Entry entry = new Entry(id, info, true, stored);
            ENTRIES.add(entry);
            save();
            return entry;
        }
    }

    public static void remove(String id) {
        load();
        synchronized (LOCK) {
            for (int i = ENTRIES.size() - 1; i >= 0; i--) {
                if (ENTRIES.get(i).id.equals(id)) ENTRIES.remove(i);
            }
            if (activeId.equals(id)) activeId = "";
            save();
        }
    }

    public static void setAllowUpdateAlert(String id, boolean allow) {
        load();
        synchronized (LOCK) {
            for (int i = 0; i < ENTRIES.size(); i++) {
                Entry entry = ENTRIES.get(i);
                if (!entry.id.equals(id)) continue;
                ENTRIES.set(i, new Entry(entry.id, entry.info, allow, entry.storedScript));
            }
            save();
        }
    }

    // ════════════════════════════ 内置源装配 ════════════════════════════

    /**
     * 首次使用（存档里一条脚本都没有）时把 jar 内置源导入并启用，让自定义源开箱即用；
     * 用户已经导入过任何脚本时一个字节都不动 —— 存档是用户的，内置只在空场时铺地毯。
     * 内置脚本随版本更新（正文变了）时按 {@link #BUILTIN_ID} 把旧条目整条替换，
     * 但不抢回启用权：用户换成别的脚本或关闭了，保持用户的选择。
     */
    private static void provisionBuiltin() {
        provisionBuiltin(true);
    }

    /**
     * @param firstUse 存档文件本身不存在（真正的首次启动）；删过内置源的老存档传 false
     */
    private static void provisionBuiltin(boolean firstUse) {
        String builtin = readBuiltinResource();
        if (builtin.isEmpty()) return;
        boolean hasUserScript = false;
        Entry builtinEntry = null;
        for (Entry entry : ENTRIES) {
            if (BUILTIN_ID.equals(entry.id)) builtinEntry = entry;
            else hasUserScript = true;
        }
        if (hasUserScript) return;
        // 非首次但既无内置条目也无用户脚本：用户删过内置源，尊重这个选择不复活。
        // （builtinEntry != null 时是「随版本更新」，不管 firstUse 都要往下走换正文。）
        if (builtinEntry == null && (!firstUse || !ENTRIES.isEmpty())) return;
        LxScriptInfo info;
        try {
            info = LxScriptInfo.parse(builtin);
        } catch (RuntimeException broken) {
            // jar 里的内置脚本坏了不该阻断播放器启动，静默跳过。
            return;
        }
        String stored = deflateScript(builtin);
        if (builtinEntry != null) {
            if (builtinEntry.storedScript.equals(stored)) return; // 正文没变，不重写存档
            ENTRIES.remove(builtinEntry);
        }
        boolean activate = activeId.isEmpty();
        ENTRIES.add(new Entry(BUILTIN_ID, info, true, stored));
        if (activate) activeId = BUILTIN_ID;
        save();
    }

    private static String readBuiltinResource() {
        try (java.io.InputStream stream = LxSourceStore.class.getResourceAsStream(BUILTIN_RESOURCE)) {
            if (stream == null) return "";
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) >= 0) out.write(chunk, 0, read);
            return out.toString("UTF-8");
        } catch (Throwable broken) {
            return "";
        }
    }

    // ════════════════════════════ 持久化 ════════════════════════════

    private static void load() {
        synchronized (LOCK) {
            if (loaded) return;
            loaded = true;
            try {
                File file = ConfigPaths.LX_SOURCES;
                if (!file.isFile()) {
                    provisionBuiltin(true);
                    return;
                }
                JsonObject root = JsonConfigStorage.readObject(file, GSON);
                if (root == null) {
                    provisionBuiltin(true);
                    return;
                }
                if (root.has("activeId") && !root.get("activeId").isJsonNull()) {
                    activeId = root.get("activeId").getAsString();
                }
                // 缺这一项时保持默认（关）：老存档不该因为升级就悄悄放宽白名单。
                if (root.has("extraActions") && !root.get("extraActions").isJsonNull()) {
                    extraActions = root.get("extraActions").getAsBoolean();
                }
                JsonArray scripts = root.getAsJsonArray("scripts");
                if (scripts == null) scripts = new JsonArray();
                for (JsonElement element : scripts) {
                    JsonObject item = element.getAsJsonObject();
                    String stored = string(item, "script");
                    if (stored.isEmpty()) continue;
                    String id = string(item, "id");
                    if (id.isEmpty()) continue;
                    // 名字等元信息重新从正文解析：脚本更新后头注释可能变了，
                    // 而落盘的那份是旧的，以正文为准才不会在列表里显示成过期的版本号。
                    LxScriptInfo info;
                    try {
                        info = LxScriptInfo.parse(inflateScript(stored));
                    } catch (RuntimeException broken) {
                        continue;
                    }
                    boolean allow = !item.has("allowUpdateAlert") || item.get("allowUpdateAlert").getAsBoolean();
                    ENTRIES.add(new Entry(id, info, allow, stored));
                }
                provisionBuiltin(false);
            } catch (Throwable ignored) {
                // 配置读坏了就当没有导入过任何源，不能因此阻断播放器启动。
                provisionBuiltin(true);
            }
        }
    }

    private static void save() {
        try {
            File parent = ConfigPaths.LX_SOURCES.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) return;
            JsonObject root = new JsonObject();
            root.addProperty("activeId", activeId);
            root.addProperty("extraActions", extraActions);
            JsonArray scripts = new JsonArray();
            for (Entry entry : ENTRIES) {
                JsonObject item = new JsonObject();
                item.addProperty("id", entry.id);
                item.addProperty("name", entry.info.getName());
                item.addProperty("description", entry.info.getDescription());
                item.addProperty("version", entry.info.getVersion());
                item.addProperty("author", entry.info.getAuthor());
                item.addProperty("homepage", entry.info.getHomepage());
                item.addProperty("allowUpdateAlert", entry.allowUpdateAlert);
                item.addProperty("script", entry.storedScript);
                scripts.add(item);
            }
            root.add("scripts", scripts);
            JsonConfigStorage.writeObject(ConfigPaths.LX_SOURCES, GSON, root);
        } catch (Throwable ignored) {
            // 写失败只影响下次启动能不能记住，不该把当前操作也一起失败掉。
        }
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private static String deflateScript(String raw) {
        return "gz_" + Base64.getEncoder().encodeToString(
                LxUtils.deflate(raw == null ? "" : raw).getBytes());
    }

    private static String inflateScript(String stored) {
        if (stored == null) return "";
        if (!stored.startsWith("gz_")) return stored;
        try {
            byte[] compressed = Base64.getDecoder().decode(stored.substring(3));
            return new String(LxUtils.inflate(LxBuffer.of(compressed)).getBytes(), StandardCharsets.UTF_8);
        } catch (Exception broken) {
            return "";
        }
    }
}