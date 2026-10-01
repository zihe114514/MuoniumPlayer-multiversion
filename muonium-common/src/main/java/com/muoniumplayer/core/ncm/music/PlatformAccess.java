package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.ncm.kugou.KuGouDevice;

/**
 * 「这台机器在某个平台上到底是什么身份」的唯一事实层。
 *
 * <p>存在的理由是一件事：房间里的「谁能替别人解出这首歌」以前是用<b>登录态</b>当会员态用的
 * （{@code NeteaseMusicService.isLoggedIn} 的真名就叫 {@code vip}，代码注释里自己承认了这是猜）。
 * 登录了但不是会员的人会被服务端当成最可能答上的人问到，答出来的又必然是试听片段，
 * 于是整房的求助窗口白等一轮。这里把三件事拆开，各答各的：</p>
 *
 * <ol>
 *   <li>{@link #loggedIn} —— 这个平台的会话在不在；</li>
 *   <li>{@link #memberTier} —— 是不是<b>真会员</b>；查不到的平台返回 {@code null}，
 *       也就是「本机说不准」，绝不返回一个猜出来的 true/false；</li>
 *   <li>{@link #needsMembership} —— 这个平台是不是「不开会员也听得到完整版」。</li>
 * </ol>
 *
 * <p>三个方法都必须<b>零网络</b>：它们会在房间心跳（{@code C_PROFILE}，每 5 秒一次）里被逐个
 * 平台问一遍，任何一次查询都不允许发请求。所以档位一律读登录时就落在内存/凭证里的快照
 * （网易云 {@code profile.vipType}、酷狗凭证 {@code vip_type}、汽水/B 站的会话快照），
 * 不去调任何「查会员」接口。</p>
 */
public final class PlatformAccess {

    private PlatformAccess() {
    }

    /** 这个平台的会话在不在本机。任何异常都当「不在」，不许把心跳带崩。 */
    public static boolean loggedIn(MusicPlatform platform) {
        if (platform == null) return false;
        try {
            switch (platform) {
                case KUGOU:
                    return KuGouDevice.isLoggedIn();
                case QISHUI:
                    return QishuiMusicService.isLoggedIn();
                case BILIBILI:
                    return BilibiliMusicService.isLoggedIn();
                case KUWO:
                case MIGU:
                    // 这两个渠道本机没有本家会话：曲目来自宿主搜索，取流全交给 LX 脚本。
                    return false;
                case GD:
                    // GD 音乐台是聚合直链，没有账号概念。
                    return false;
                case SMTC:
                    // 系统媒体会话只是「外部播放器正在播什么」，本机没有它的任何账号态。
                    // 不写这一条会掉进 default 去问 Cadence 的登录态，那答案是错的。
                    return false;
                default:
                    return NeteaseMusicService.isLoggedIn(platform);
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 这个平台是不是<b>真会员</b>。
     *
     * @return {@code TRUE} 确定是；{@code FALSE} 确定不是（含没登录）；{@code null} 本机查不到档位
     */
    public static Boolean memberTier(MusicPlatform platform) {
        if (platform == null) return Boolean.FALSE;
        try {
            switch (platform) {
                case NETEASE:
                    // profile 只有登录了才有；没登录就是确定没有会员。
                    return CloudMusic.profile == null ? Boolean.FALSE
                            : Boolean.valueOf(CloudMusic.profile.isVipMember());
                case KUGOU:
                    return KuGouDevice.isLoggedIn()
                            ? Boolean.valueOf(KuGouDevice.vipType() > 0) : Boolean.FALSE;
                case QISHUI:
                    return QishuiMusicService.isLoggedIn()
                            ? Boolean.valueOf(QishuiMusicService.isVipAccount()) : Boolean.FALSE;
                case BILIBILI:
                    return BilibiliMusicService.isLoggedIn()
                            ? Boolean.valueOf(BilibiliMusicService.isVipAccount()) : Boolean.FALSE;
                case QQ:
                    // 登录包与 QQUserInfo 里都没有会员字段可查（上游就没给）。登录了也只能说「说不准」，
                    // 是否绿钻交给播放时的试听检测那一级去拦，这里不替它猜。
                    return NeteaseMusicService.isLoggedIn(MusicPlatform.QQ) ? null : Boolean.FALSE;
                case YOUTUBE:
                    // YTM Premium 状态本机没有可靠只读来源，同上：登录了也不谎报。
                    return NeteaseMusicService.isLoggedIn(MusicPlatform.YOUTUBE) ? null : Boolean.FALSE;
                case KUWO:
                case MIGU:
                case GD:
                    return Boolean.FALSE;
                default:
                    return null;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 这个平台是否「不开会员也拿得到完整版」。
     *
     * <p>GD 音乐台给的是聚合直链、B 站是音乐区音频池，两家都不卖音乐会员，所以「不是会员」
     * 对它们不构成「答不上」的理由。酷我 / 咪咕反过来：本机没有本家取流链路，完整版只能靠
     * LX 脚本，所以这里返回 true，让上层去问 {@link CrossSourceSwitcher#lxCovers}。</p>
     */
    public static boolean needsMembership(MusicPlatform platform) {
        if (platform == null) return true;
        return platform != MusicPlatform.GD && platform != MusicPlatform.BILIBILI;
    }

    /**
     * 本机能否<b>不靠会员身份、也不靠 LX 脚本</b>就给出这一首的完整版 —— 目前就是「这个平台不卖会员」。
     *
     * <p>给上报用：服务端要区分「他是会员所以能答」和「这个平台本来就不要会员」。</p>
     */
    public static boolean openAccess(MusicPlatform platform) {
        return !needsMembership(platform);
    }
}
