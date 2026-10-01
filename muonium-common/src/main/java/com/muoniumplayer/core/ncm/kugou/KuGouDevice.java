package com.muoniumplayer.core.ncm.kugou;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.settings.ConfigPaths;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * 酷狗的设备标识与登录凭证的持久化。
 *
 * <p>设备 GUID 与登录 token 放在同一个文件里，因为它们必须一起保持稳定：token 是绑在账号上的，
 * 但 {@code mid} 参与每一次签名，换了 GUID 会让所有已发出的请求签名失效。</p>
 *
 * <h3>{@code dfid} 为什么是本地生成的</h3>
 * <p>参考实现里的 {@code /v5/url} 直接用一个<b>随机 24 位串</b>当 {@code dfid}
 * （见 {@code module/song_url.js} 的 {@code randomString(24)}），实测 {@code "-"} 也照样能取流。
 * 它只是风控的一个输入，不是一个必须先向服务端「注册」的身份。</p>
 *
 * <p>本项目旧实现走的是另一条路：调 {@code r_register_dev} 用 AES + RSA 换一个服务端签发的
 * {@code dfid}。这条链路在当前环境下<b>恒失败</b>（各种 {@code kg-tid} 都回 {@code error_code=20006}），
 * 于是每次取流都要白白等一次超时，还把 {@code dfid} 留成 {@code "-"}。既然它既不必要又不可用，
 * 就换成本地生成——少一整条会失效的链路，还省掉每次冷启动的一次失败请求。</p>
 *
 * <h3>会员状态为什么要落盘</h3>
 * <p>{@code vipType} 决定「这首歌取不到」要不要提示用户开会员。它是登录时才有的一次网络查询，
 * 但账号面板可能在离线时打开，所以顺手写在凭证文件里，避免每次开面板都打一次请求。</p>
 */
public final class KuGouDevice {

    private static final Object LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DFID_ALPHABET = "1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private static volatile boolean loaded;
    private static volatile String guid = "";
    private static volatile String mid = "";
    private static volatile String dfid = "";
    private static volatile String token = "";
    private static volatile String userId = "";
    private static volatile String nickname = "";
    private static volatile String avatarUrl = "";
    /** 上游 {@code vip_type}：0 非会员，非 0 表示有会员等级（6 是常见的超级会员）。 */
    private static volatile int vipType;
    /** 会员到期时间原文，仅用于展示。 */
    private static volatile String vipExpiresAt = "";
    /**
     * 当前凭证属于哪个客户端身份（完整版 / 概念版）。
     *
     * <p>记在设备而不是记成进程开关：{@code appid}、签名盐、取流盐、扫码二维码里嵌的 appid
     * 四者必须与<b>签发这张 token 的身份</b>一致，而一台机器上先后登录过两种账号是正常情况。
     * 默认 {@link KuGouProfile#STANDARD}，因为本仓历史上所有实测凭证与旧凭证都在标准版下签发。</p>
     */
    private static volatile KuGouProfile profile = KuGouProfile.STANDARD;

    private KuGouDevice() {
    }

    public static String mid() {
        ensureLoaded();
        return mid;
    }

    public static String dfid() {
        ensureLoaded();
        return dfid;
    }

    /**
     * 持久化的设备 GUID（带连字符的标准 UUID 形式，32 位十六进制 + 4 个连字符）。
     *
     * <p>CSCC 上报要求一个 32 位纯十六进制的 {@code uuid}，调用方自己去掉连字符——
     * 这里不提前剥掉，是因为 {@code mid} 的推导和凭证文件里存的都是带连字符的形式。</p>
     */
    public static String guid() {
        ensureLoaded();
        return guid;
    }

    public static String token() {
        ensureLoaded();
        return token;
    }

    public static String userId() {
        ensureLoaded();
        return userId;
    }

    public static String nickname() {
        ensureLoaded();
        return nickname;
    }

    public static String avatarUrl() {
        ensureLoaded();
        return avatarUrl;
    }

    public static int vipType() {
        ensureLoaded();
        return vipType;
    }

    public static String vipExpiresAt() {
        ensureLoaded();
        return vipExpiresAt;
    }

    public static boolean isVip() {
        ensureLoaded();
        return vipType != 0;
    }

    public static boolean isLoggedIn() {
        ensureLoaded();
        return !KuGouSign.isBlank(token) && !KuGouSign.isBlank(userId) && !"0".equals(userId.trim());
    }

    /**
     * 当前凭证的客户端身份。
     *
     * <p>未登录时也有意义：匿名请求同样要带 {@code appid}/{@code clientver}，
     * 而签名盐跟着它走，所以这是「请求以谁的身份发出」而不只是「登录用的哪个客户端」。</p>
     */
    public static KuGouProfile profile() {
        ensureLoaded();
        return profile;
    }

    /**
     * 切换客户端身份并落盘。
     *
     * @param persist {@code false} 时只改内存（用于启动阶段从凭证文件回填，避免自写自读）
     */
    public static void setProfile(KuGouProfile newProfile, boolean persist) {
        ensureLoaded();
        KuGouProfile next = newProfile == null ? KuGouProfile.STANDARD : newProfile;
        synchronized (LOCK) {
            if (profile == next) return;
            profile = next;
            // 换身份等于换客户端，旧的会员结论不再成立，清掉等下一次查询回填。
            vipType = 0;
            vipExpiresAt = "";
            if (persist) persist();
        }
    }

    /** 扫码成功后写入，身份沿用当前凭证的。 */
    public static void saveLogin(String newToken, String newUserId, String newNickname, String newAvatarUrl) {
        saveLogin(newToken, newUserId, newNickname, newAvatarUrl, null);
    }

    /**
     * 扫码 / token 登录成功后写入。
     *
     * <p>{@code newNickname} 与 {@code newAvatarUrl} 可以为空，随后由账号面板补齐。</p>
     *
     * @param newProfile 签发这张 token 的客户端身份；{@code null} 表示沿用当前的
     */
    public static void saveLogin(String newToken, String newUserId, String newNickname, String newAvatarUrl,
                                 KuGouProfile newProfile) {
        ensureLoaded();
        synchronized (LOCK) {
            token = KuGouSign.nonNull(newToken).trim();
            userId = KuGouSign.nonNull(newUserId).trim();
            if (!KuGouSign.isBlank(newNickname)) nickname = newNickname.trim();
            if (!KuGouSign.isBlank(newAvatarUrl)) avatarUrl = newAvatarUrl.trim();
            if (newProfile != null) profile = newProfile;
            // 换了账号，旧的会员状态一定不再适用，先清掉再让调用方去查。
            vipType = 0;
            vipExpiresAt = "";
            persist();
        }
    }

    public static void saveProfile(String newNickname, String newAvatarUrl) {
        ensureLoaded();
        synchronized (LOCK) {
            if (!KuGouSign.isBlank(newNickname)) nickname = newNickname.trim();
            if (!KuGouSign.isBlank(newAvatarUrl)) avatarUrl = newAvatarUrl.trim();
            persist();
        }
    }

    /** 记录会员状态。{@code vipType} 为 0 表示查到了「不是会员」，与「还没查」在展示上不同。 */
    public static void saveVip(int newVipType, String expiresAt) {
        ensureLoaded();
        synchronized (LOCK) {
            if (vipType == newVipType && KuGouSign.nonNull(vipExpiresAt).equals(KuGouSign.nonNull(expiresAt))) {
                return;
            }
            vipType = newVipType;
            vipExpiresAt = KuGouSign.nonNull(expiresAt);
            persist();
        }
    }

    /** 退出登录只清凭证，<b>保留</b> GUID：设备身份继续沿用，下次登录不会被当成新设备。 */
    public static void logout() {
        ensureLoaded();
        synchronized (LOCK) {
            token = "";
            userId = "";
            nickname = "";
            avatarUrl = "";
            vipType = 0;
            vipExpiresAt = "";
            persist();
        }
    }

    private static void ensureLoaded() {
        if (loaded) return;
        synchronized (LOCK) {
            if (loaded) return;
            try {
                File file = ConfigPaths.KUGOU_AUTH;
                if (file.isFile()) {
                    String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                    JsonObject root = new JsonParser().parse(text).getAsJsonObject();
                    guid = readString(root, "guid");
                    dfid = readString(root, "dfid");
                    token = readString(root, "token");
                    userId = readString(root, "userid");
                    nickname = readString(root, "nickname");
                    avatarUrl = readString(root, "avatar");
                    vipType = readInt(root, "vip_type");
                    vipExpiresAt = readString(root, "vip_expires_at");
                    // 旧凭证文件没有这个字段，fromKey 的空值兜底就是标准版——那正是它们签发时的身份。
                    profile = KuGouProfile.fromKey(readString(root, "profile"));
                }
            } catch (Throwable throwable) {
                System.err.println("[Music/KuGou] 凭证读取失败，按未登录处理: " + throwable.getMessage());
            }
            if (KuGouSign.isBlank(guid)) guid = UUID.randomUUID().toString();
            mid = KuGouSign.mid(guid);
            if (KuGouSign.isBlank(dfid)) dfid = randomDfid();
            loaded = true;
            persist();
        }
    }

    /** 随机 24 位 {@code dfid}，与参考实现在取流时的做法一致。 */
    private static String randomDfid() {
        StringBuilder result = new StringBuilder(24);
        for (int index = 0; index < 24; index++) {
            result.append(DFID_ALPHABET.charAt(RANDOM.nextInt(DFID_ALPHABET.length())));
        }
        return result.toString();
    }

    private static void persist() {
        try {
            File file = ConfigPaths.KUGOU_AUTH;
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                return;
            }
            JsonObject root = new JsonObject();
            root.addProperty("guid", guid);
            root.addProperty("dfid", dfid);
            root.addProperty("token", token);
            root.addProperty("userid", userId);
            root.addProperty("nickname", nickname);
            root.addProperty("avatar", avatarUrl);
            root.addProperty("vip_type", vipType);
            root.addProperty("vip_expires_at", vipExpiresAt);
            root.addProperty("profile", profile.key());
            Files.write(file.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable throwable) {
            System.err.println("[Music/KuGou] 凭证写入失败: " + throwable.getMessage());
        }
    }

    private static int readInt(JsonObject root, String name) {
        try {
            return root != null && root.has(name) && !root.get(name).isJsonNull()
                    ? root.get(name).getAsInt() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String readString(JsonObject root, String name) {
        try {
            return root != null && root.has(name) && !root.get(name).isJsonNull()
                    ? root.get(name).getAsString() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }
}
