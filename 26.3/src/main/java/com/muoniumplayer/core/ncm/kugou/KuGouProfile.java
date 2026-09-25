package com.muoniumplayer.core.ncm.kugou;

/**
 * 酷狗客户端身份（标准版 / 概念版）。
 *
 * <h3>为什么这是一条「轴」而不是两套代码</h3>
 * <p>酷狗同一个网关同时服务两个客户端：完整版（{@code appid=1005}）与<b>概念版</b>
 * （{@code appid=3116}）。它们的差别不是「走哪个域名」，而是<b>同一批端点上四组常量必须成套换</b>：
 * 身份参数、签名盐、取流 key 盐、取流的页面标识。参考实现里这是一个全局开关
 * （{@code process.env.platform === 'lite'}，见 {@code util/helper.js}），
 * 本仓把它做成一个显式对象，因为宿主允许一台机器上存在两种来源的凭证。</p>
 *
 * <h3>盐值绝不能只换一个</h3>
 * <p>历史上本仓出过一次事故：把概念版的取流盐 {@code 185672dd…} 当成「现代接口的盐值」用在了
 * 标准版的 {@code appid=1005} 上，结果是<b>每次取流都拿不到地址</b>，而签名本身仍然算得出来、
 * 上游也不报错，只是静默不给数据。所以这四组值只能从同一个枚举实例里取，
 * 不接受「单独传个盐值进来」的用法。</p>
 *
 * <h3>token 与身份是绑定的</h3>
 * <p>扫码登录时二维码里嵌的 {@code appid}（{@link #qrTextAppId()}）决定了这张 token 是给哪个
 * 客户端签发的，之后所有请求都得用同一身份，否则会员状态与取流权限会对不上。
 * 因此当前身份记在 {@link KuGouDevice} 里、随凭证一起落盘，而不是一个进程级开关。</p>
 *
 * <p>概念版是第三方逆向得到的协议事实（对照 {@code MakcRe/KuGouMusicApi}，
 */
public enum KuGouProfile {

    /**
     * 酷狗音乐（完整版 / Android 主客户端）。
     *
     * <p>本仓历史上实测用的就是这一套：签名盐、{@code /v5/url} 的 {@code page_id} 与
     * {@code pid=2}、以及 {@code 57ae12eb…} 那把取流 key 盐，全部在这一档下验证过。</p>
     */
    STANDARD("standard", "酷狗音乐", "1005", "20489",
            "OIlwieks28dk2k092lksi2UIkp", "57ae12eb6890223e355ccfcb74edf70d",
            "151369488", "463467626,350369493,788954147", "2"),

    /**
     * 酷狗概念版（参考实现里的 {@code lite}）。
     *
     * {@code process.env.platform = 'lite'}）。四个与身份绑定的常量必须一起换：
     * {@code appid}/{@code clientver}、Android 签名盐（同时用于 {@code paramsKey}）、
     * 取流 key 盐、{@code /v5/url} 的 {@code page_id}/{@code ppage_id}/{@code pid}。</p>
     */
    CONCEPT("concept", "酷狗概念版", "3116", "11440",
            "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA", "185672dd44712f60bb1736df5a377e82",
            "967177915", "356753938,823673182,967485191", "411");

    /** 落盘与日志里用的稳定键；改这个值等于让已有凭证的身份失效，只能新增不能改名。 */
    private final String key;
    /** 给人看的名字，用在账号面板。 */
    private final String label;
    private final String appId;
    private final String clientVer;
    /** Android 签名盐值，同时也是 {@code paramsKey} 的盐值。 */
    private final String signSalt;
    /** {@code /v5/url} 的 {@code key} 参数盐值（参考实现里的 {@code signKey}）。 */
    private final String streamKeySalt;
    private final String streamPageId;
    private final String streamPageIdChain;
    private final String streamPid;

    KuGouProfile(String key, String label, String appId, String clientVer, String signSalt,
                 String streamKeySalt, String streamPageId, String streamPageIdChain,
                 String streamPid) {
        this.key = key;
        this.label = label;
        this.appId = appId;
        this.clientVer = clientVer;
        this.signSalt = signSalt;
        this.streamKeySalt = streamKeySalt;
        this.streamPageId = streamPageId;
        this.streamPageIdChain = streamPageIdChain;
        this.streamPid = streamPid;
    }

    /** 当前生效的身份——<b>由凭证决定</b>，所以从 {@link KuGouDevice} 读。 */
    public static KuGouProfile active() {
        return KuGouDevice.profile();
    }

    /**
     * 由落盘键还原身份。
     *
     * <p>读不到、值不认识都回 {@link #STANDARD}：凭证文件里根本没有 {@code profile} 字段
     * 是老版本安装的正常状态，那些账号是在标准版下登录的，默认标准版才正确。</p>
     */
    public static KuGouProfile fromKey(String value) {
        String key = KuGouSign.nonNull(value).trim().toLowerCase(java.util.Locale.ROOT);
        for (KuGouProfile profile : values()) {
            if (profile.key.equals(key)) return profile;
        }
        return STANDARD;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    /** {@code appid} 查询参数（1005 / 3116）。 */
    public String appId() {
        return appId;
    }

    /** {@code clientver} 查询参数（20489 / 11440）。注意取流端点另有 {@code 11430}。 */
    public String clientVer() {
        return clientVer;
    }

    public String signSalt() {
        return signSalt;
    }

    public String streamKeySalt() {
        return streamKeySalt;
    }

    public String streamPageId() {
        return streamPageId;
    }

    public String streamPageIdChain() {
        return streamPageIdChain;
    }

    public String streamPid() {
        return streamPid;
    }

    /**
     * 扫码二维码内容里嵌的 {@code appid}。
     *
     * <p>参考实现 {@code module/login_qr_key.js} 用的是当前身份的 appid（{@code appid} 变量在
     * lite 下就是 3116），而<b>请求参数</b>的 {@code appid} 固定是 {@code 1001}——
     * 那两个是不同的东西，混用会得到一张扫码成功但取不到会员状态的 token。</p>
     */
    public String qrTextAppId() {
        return appId;
    }

    /** 二维码请求参数里的 {@code appid}，与内容里嵌的那个不是一回事。 */
    public static final String QR_REQUEST_APP_ID = "1001";
}
