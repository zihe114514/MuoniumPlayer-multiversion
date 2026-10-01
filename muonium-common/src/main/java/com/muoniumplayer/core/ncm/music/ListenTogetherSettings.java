package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.io.File;

/**
 * 一起听的行为开关。
 *
 * <p>三项：
 * <ol>
 *   <li>{@code skipVipWhenRoomHasNonVip} —— 网易云官方一起听：房间里有非会员时，
 *       是否在起播前跳过「VIP 专享」曲目；</li>
 *   <li>{@code partyContributeVip} —— 服务端房间（mpsync）：是否愿意用本家会员身份替别人解析；</li>
 *   <li>{@code partyContributeLx} —— 服务端房间：是否愿意用本机 LX 自定义源替别人解析。</li>
 * </ol>
 *
 * <p>第一项默认开启 ——
 * 官方客户端在这种情况下会让整房一起切歌，不跳过就会出现"房主接着放、房员 30 秒后已经换歌"
 * 的脱队。关掉它就回到"各放各的"。</p>
 */
public final class ListenTogetherSettings {

    private static final Object LOCK = new Object();
    private static final Gson GSON = new Gson();

    private static final String KEY_SKIP_VIP = "skipVipWhenRoomHasNonVip";
    private static final String KEY_PARTY_CONTRIBUTE_VIP = "partyContributeVip";
    private static final String KEY_PARTY_CONTRIBUTE_LX = "partyContributeLx";

    private static volatile boolean loaded;
    private static boolean skipVipWhenRoomHasNonVip = true;
    private static boolean partyContributeVip;
    private static boolean partyContributeLx = true;

    private ListenTogetherSettings() {
    }

    public static void load() {
        synchronized (LOCK) {
            if (loaded) return;
            loaded = true;
            JsonObject root = readRootLocked();
            skipVipWhenRoomHasNonVip = flagOf(root, KEY_SKIP_VIP, true);
            partyContributeVip = flagOf(root, KEY_PARTY_CONTRIBUTE_VIP, false);
            partyContributeLx = flagOf(root, KEY_PARTY_CONTRIBUTE_LX, true);
        }
    }

    public static boolean isSkipVipWhenRoomHasNonVip() {
        load();
        synchronized (LOCK) {
            return skipVipWhenRoomHasNonVip;
        }
    }

    public static void setSkipVipWhenRoomHasNonVip(boolean value) {
        load();
        synchronized (LOCK) {
            if (skipVipWhenRoomHasNonVip == value) return;
            skipVipWhenRoomHasNonVip = value;
            saveLocked();
        }
    }

    /**
     * 服务端房间（mpsync 一起听）里，是否愿意替别人解析会员曲。
     *
     * <p>打开后<b>只</b>上报一个「我有这个能力」的标记；真要解析时是本机解析出一条带签名、
     * 有时效的临时直链再上传。<b>cookie 永远不离开这台机器</b>，服务端和其他玩家都拿不到账号。
     *
     * <p>默认关：这件事会消耗你的账号配额、也会让别人听到你解析出来的音频，必须显式同意。
     */
    public static boolean isPartyContributeVip() {
        load();
        synchronized (LOCK) {
            return partyContributeVip;
        }
    }

    public static void setPartyContributeVip(boolean value) {
        load();
        synchronized (LOCK) {
            if (partyContributeVip == value) return;
            partyContributeVip = value;
            saveLocked();
        }
    }

    /**
     * 服务端房间（mpsync 一起听）里，是否愿意用<b>本机导入的 LX 自定义源</b>替别人解歌。
     *
     * <p>与 {@link #isPartyContributeVip()} 是两件独立的事：那一格交出去的是「我这个平台的会员
     * 身份能解出来的流」，这一格交出去的是「我那个脚本能解出来的流」。后者不动用任何账号会员权益，
     * 但会把脚本所依赖的第三方接口的调用量记到这台机器头上，所以单独给一个可以关的门。</p>
     *
     * <p>默认开：房间里没人贡献会员时，这是让 VIP 曲还能整房播完的唯一一路；不想贡献的人
     * 在房间面板里关掉那一格即可。关掉它不影响本机自己播放时使用自定义源取流。</p>
     */
    public static boolean isPartyContributeLx() {
        load();
        synchronized (LOCK) {
            return partyContributeLx;
        }
    }

    public static void setPartyContributeLx(boolean value) {
        load();
        synchronized (LOCK) {
            if (partyContributeLx == value) return;
            partyContributeLx = value;
            saveLocked();
        }
    }

    private static JsonObject readRootLocked() {
        try {
            File file = ConfigPaths.LISTEN_TOGETHER;
            if (!file.isFile()) return null;
            return JsonConfigStorage.readObject(file, GSON);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean flagOf(JsonObject root, String key, boolean def) {
        try {
            if (root == null || !root.has(key) || root.get(key).isJsonNull()) return def;
            return root.get(key).getAsBoolean();
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static void saveLocked() {
        try {
            File parent = ConfigPaths.LISTEN_TOGETHER.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) return;
            JsonObject root = new JsonObject();
            root.addProperty(KEY_SKIP_VIP, skipVipWhenRoomHasNonVip);
            root.addProperty(KEY_PARTY_CONTRIBUTE_VIP, partyContributeVip);
            root.addProperty(KEY_PARTY_CONTRIBUTE_LX, partyContributeLx);
            JsonConfigStorage.writeObject(ConfigPaths.LISTEN_TOGETHER, GSON, root);
        } catch (Throwable ignored) {
        }
    }
}