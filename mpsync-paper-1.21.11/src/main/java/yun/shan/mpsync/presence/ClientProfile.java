package yun.shan.mpsync.presence;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import yun.shan.mpsync.net.Proto;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 一个玩家的客户端自报信息（{@link Proto#C_PROFILE}）。
 *
 * <p>为什么必须由客户端自报、服务端不能自己查：这三件事全在客户端进程里 —— 装的是哪个
 * 播放器 mod、哪些平台已经登录（因此能代解析会员曲）、以及此刻正在听什么。服务端一个平台
 * 会话都没有，也看不到别人的本地播放器状态。
 *
 * <p>没上报过的玩家（装的是老版本客户端，或压根没装播放器）会被当成「未知客户端」，
 * 在成员列表里显示为「未知」而不是假装有 —— 猜一个比说不知道更糟。
 */
public final class ClientProfile {

    /** 客户端标识：{@code melodify}（modernMP）/ {@code muonium}（MuoniumPlayer）/ 其它。 */
    public String mod = "";
    /** 客户端自报的展示名；为空时由 {@link #displayName()} 从 {@link #mod} 推。 */
    public String modName = "";
    public String modVersion = "";
    /** 客户端声明的协议版本，用来判断它能不能显示 v2 的那些页。 */
    public int proto;

    /**
     * 已登录、因此有能力解析出完整音频的平台集合。
     *
     * <p>刻意叫 VIP 而不是「已登录」：房间里真正关心的是「这个人能不能把会员曲解析成完整音频」，
     * 而客户端能给出的最可靠信号就是「这个平台的会话在」。客户端上报的是它自己 {@code
     * MusicProvider.isLoggedIn()} 为真的平台。</p>
     */
    public final Set<String> vipSources = new LinkedHashSet<>();

    /**
     * 客户端自报的<b>真会员</b>平台令牌集合（报文里的 {@code member}）。
     *
     * <p>只放客户端确定为真会员的；本机查不到档位的平台干脆不出现 —— 这里宁可少报也不能谎报，
     * 谎报会重新制造这次要修的假信号（把人误算进「愿意贡献 VIP」，等来一条试听片段）。</p>
     */
    public final Set<String> memberSources = new LinkedHashSet<>();

    /**
     * 本机现在能给出<b>完整版</b>音频的平台令牌集合（报文里的 {@code resolvable}）。
     *
     * <p>含两路：「这个平台本来不卖会员」（买了也不能更完整，登录即完整版）和「靠 LX 脚本能解」。
     * 它是「这台机器有没有希望答上」（{@link #canResolve}）的依据，与「是不是会员」无关。</p>
     */
    public final Set<String> resolvableSources = new LinkedHashSet<>();

    /**
     * {@link #resolvableSources} 的子集：只有靠本机导入的 LX 自定义源才解得出的平台（{@code viaLx}）。
     *
     * <p>单独成集是因为裁决要按它分先后：本家会员解出来的直链更稳，而且动用 LX 会消耗
     * 第三方接口额度 —— 能先问本家就先问本家。</p>
     */
    public final Set<String> viaLxSources = new LinkedHashSet<>();

    /** 玩家是否愿意替别人解析（贡献 VIP）。默认关，必须显式打开。 */
    public boolean contributeVip;

    /** 是否愿意用 LX 脚本替房间解歌。与 {@link #contributeVip} 完全独立，各开各的。 */
    public boolean contributeLx;

    /**
     * 这台客户端是否报过 {@code member} / {@code resolvable} / {@code viaLx} 里的任意一个。
     *
     * <p>贡献判据必须区分「报了空集」（真的不是会员、也没有完整版路）和「老客户端压根不发」
     * —— 两者在线报文里长得一模一样，但前者的贡献资格该剥掉、后者不该。没有这一位，
     * 改动会把所有没升级客户端的贡献者资格悄悄清零。</p>
     */
    public boolean capabilityKnown;

    /**
     * 这台客户端能不能跑「房间交接计划」的音频分析（{@code RoomAutomix}）。
     *
     * <p>房间里换歌点必须全房一致，所以只能由一台机器算完上报、服务端裁决广播。问题是
     * <b>「哪一台能算」跟「谁是房主」无关</b>：算力落在谁手上取决于客户端有没有这套代码、
     * 有没有装模型、音频能不能解码。modernMP 压根没有这个概念，它永远算不出 —— 但它同样
     * 要听房间里的无缝交接，于是它得借别人的机器。
     *
     * <p>所以这一位由客户端自报，服务端据此做<b>指派</b>（见 {@code Room#assignAnalyzer}）：
     * 优先点歌人 → 其次房主 → 再次房间里任何一个能算的人。为 false 的人永远不会被指派，
     * 也就不会白跑一次最重的后台任务。</p>
     */
    public boolean automixCapable;

    /**
     * 这台客户端<b>有没有</b>跨平台换源那套流水线（报文里的 {@code switchRescue}）。
     *
     * <p>全层落空之后服务端会单播问点歌人「能不能换到别的平台的同一首歌」，那一次要跑十几秒的
     * 搜索与验证，而且服务端为此得把总闸再放宽一个救援窗。对根本没有这条流水线的客户端
     * （modernMP 只有各平台自己的解析链路，没有跨平台匹配）发这一句，结果是<b>纯亏</b>：
     * 它什么都答不上，那一首却要多卡 12 秒才被跳过。</p>
     *
     * <p>所以这一位由客户端自报，为 false（含老客户端不发）时服务端压根不问这一手，
     * 总闸也不延长 —— 与改造前逐字一致。真正的执行判据（换源开关开没开、有没有够得着的候选平台）
     * 仍在客户端手里，它跑不动就什么都不发。</p>
     */
    public boolean switchRescue;

    /**
     * 分析能力档位：{@code model} = 装了模型（Folia 推理，算得出 stem 交接、过渡族、频段倾斜）；
     * {@code dsp} = 只有内置信号处理；空串 = 不能分析。
     *
     * <p>它只影响<b>指派顺序</b>（同一优先级里先问档位高的），不参与计划本体的校验 ——
     * 计划里自己带着过渡族与曲线参数，执行端照着跑就行，不需要知道是哪种引擎算的。</p>
     */
    public String automixEngine = "";

    /** 本地正在听的曲目（不在房间里时也上报，全服在线态要用）。 */
    public String nowPlaying = "";
    public String nowArtist = "";
    public boolean localPlaying;

    public long updatedAt = System.currentTimeMillis();

    public boolean hasVip() { return !vipSources.isEmpty(); }

    /**
     * 这台机器<b>有希望</b>答上：登录了该平台、能给出完整版音频、或靠 LX 解得出，三条并集。
     *
     * <p>老客户端只报 {@code vip}，其余两集恒空，判据自然退化为改动前的「登录了这个平台」。</p>
     */
    public boolean canResolve(String source) {
        if (source == null) return false;
        String s = source.trim().toLowerCase(Locale.ROOT);
        return vipSources.contains(s) || resolvableSources.contains(s) || viaLxSources.contains(s);
    }

    /**
     * 「贡献 VIP」档：愿意贡献，且该平台是真会员、或不经 LX 就有完整版路
     * （「本来不卖会员」的那类因此也算 —— 它给得出完整版，只是没有会员可买）。
     *
     * <p>只登录、非会员、又没有 LX 路的人<b>不</b>算：他答出来的是试听片段，会把整首歌
     * 截短给全房 —— 这正是这次要修的假信号。老客户端没报过新数组时按改动前判据
     * （愿意 + 登录了该平台）处理，行为一字不变。</p>
     */
    public boolean contributesVip(String source) {
        if (!contributeVip || source == null) return false;
        String s = source.trim().toLowerCase(Locale.ROOT);
        if (!capabilityKnown) return vipSources.contains(s);
        return memberSources.contains(s)
                || (resolvableSources.contains(s) && !viaLxSources.contains(s));
    }

    /**
     * 这个平台上<b>真会员</b>。只认客户端报进 {@code member} 的那些 —— 查不到档位的平台
     * （QQ 的上游就没有会员字段）宁可这里恒 false，也不拿「登录了」猜一个真会员。
     *
     * <p>老客户端没报过新数组（{@code capabilityKnown} 为假）时这里就是 false：它只报了
     * {@code vip}，而 {@code vip} 是登录态，不能当会员用。那一档由分层里的第 3 层接住。</p>
     */
    public boolean isMember(String source) {
        if (source == null || !capabilityKnown) return false;
        return memberSources.contains(source.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 这台机器靠 LX 脚本覆盖<b>几个平台</b>。
     *
     * <p>一个脚本通常同时声明 wy/tx/kg/kw/mg 好几路，而 {@code viaLx} 是按平台令牌逐个记的
     * （客户端那边 {@code lxCovers(platform)} 一条一条判过），所以这个数就是「他那个脚本能吃下
     * 几个渠道」。分层里拿它排序：渠道多的那台更可能答上，也更可能答上<b>之后</b>的每一首。</p>
     */
    public int lxChannelCount() { return viaLxSources.size(); }

    /**
     * 本机现在能出<b>完整版</b>的平台数（{@code resolvable} 的大小）。
     *
     * <p>跨平台匹配是一台机器挨个平台去搜索，够得着的平台越多越可能命中，所以这一位就是
     * 派单的首要权重。没报过新数组的人（老客户端）这里是 0 —— 它们也没有那条流水线，
     * 本来就进不了候选表。</p>
     */
    public int resolvableCount() { return resolvableSources.size(); }

    /** 「贡献 LX」档：愿意用 LX 脚本替房间解歌，且该平台确实只有靠 LX 才解得出。 */
    public boolean contributesLx(String source) {
        if (!contributeLx || source == null) return false;
        return viaLxSources.contains(source.trim().toLowerCase(Locale.ROOT));
    }

    /** 愿意并且有能力替房间解析这个平台的曲子：上面两档的并集。 */
    public boolean contributes(String source) {
        return contributesVip(source) || contributesLx(source);
    }

    /** 展示名：客户端报了就用它，否则按已知标识翻译，都不认识就叫「未知客户端」。 */
    public String displayName() {
        if (modName != null && !modName.isBlank()) return modName;
        switch (mod == null ? "" : mod.toLowerCase(Locale.ROOT)) {
            case "melodify": case "modernmp": return "modernMP";
            case "muonium":  case "muoniumplayer": return "MuoniumPlayer";
            default: return "未知客户端";
        }
    }

    /**
     * 两个字母的角标：MP = modernMP / Melodify，Mu = MuoniumPlayer，其余「??」。
     *
     * <p>这是需求里点名要的「MP 和 Mu」——成员列表一行放不下完整 mod 名，角标能一眼看出
     * 同一个房间里混着哪两种客户端（它们的对时行为必须一致，混房时这一列很有用）。
     */
    public String badge() {
        switch (mod == null ? "" : mod.toLowerCase(Locale.ROOT)) {
            case "melodify": case "modernmp": return "MP";
            case "muonium":  case "muoniumplayer": return "Mu";
            default: return "??";
        }
    }

    /** 吸收一帧 {@code profile}（或带这些字段的 {@code hello}）。只覆盖真的带过来的字段。 */
    public void apply(JsonObject o) {
        if (o == null) return;
        String id = Proto.str(o, "client", Proto.str(o, "mod", mod));
        if (id != null && !id.isBlank()) mod = id.trim().toLowerCase(Locale.ROOT);
        String name = Proto.str(o, "modName", null);
        if (name != null && !name.isBlank()) modName = clip(name);
        String ver = Proto.str(o, "modVersion", null);
        if (ver != null && !ver.isBlank()) modVersion = clip(ver);
        int p = Proto.i32(o, "proto", proto);
        if (p > 0) proto = p;
        // 只覆盖报文里真带来的平台集合；老客户端不发就保持原值，别把缺字段当成空集。
        applySources(o, "vip", vipSources);
        // 「报过任意一个新数组」和「老客户端压根不发」必须能区分开，贡献判据靠它选新旧语义。
        if (o.has("member") || o.has("resolvable") || o.has("viaLx")) capabilityKnown = true;
        applySources(o, "member", memberSources);
        applySources(o, "resolvable", resolvableSources);
        applySources(o, "viaLx", viaLxSources);
        if (o.has("contributeVip")) contributeVip = Proto.bool(o, "contributeVip", contributeVip);
        if (o.has("contributeLx")) contributeLx = Proto.bool(o, "contributeLx", contributeLx);
        // 房间交接计划的分析能力。老客户端不发这两项，于是 automixCapable 保持上次的值
        // （默认 false）—— 它们不会被指派，房间照常按「没人算得出」处理。
        if (o.has("automixCapable")) automixCapable = Proto.bool(o, "automixCapable", automixCapable);
        // 跨平台救援同理：不发这一位的客户端（含全部老版本与 modernMP）永远收不到救援帧，
        // 服务端也不为它把总闸放宽 —— 保持改造前的跳过时机。
        if (o.has("switchRescue")) switchRescue = Proto.bool(o, "switchRescue", switchRescue);
        if (o.has("automixEngine")) {
            String engine = Proto.str(o, "automixEngine", "");
            automixEngine = engine == null ? "" : engine.trim().toLowerCase(Locale.ROOT);
            if (automixEngine.length() > 16) automixEngine = automixEngine.substring(0, 16);
        }
        if (o.has("nowPlaying")) nowPlaying = clip(Proto.str(o, "nowPlaying", ""));
        if (o.has("nowArtist")) nowArtist = clip(Proto.str(o, "nowArtist", ""));
        if (o.has("localPlaying")) localPlaying = Proto.bool(o, "localPlaying", localPlaying);
        updatedAt = System.currentTimeMillis();
    }

    /** 在听的曲目一行展示；没有就返回空串（调用方自己决定显示「未在听」）。 */
    public String listeningLabel() {
        if (nowPlaying == null || nowPlaying.isBlank()) return "";
        return nowArtist == null || nowArtist.isBlank() ? nowPlaying : nowPlaying + " - " + nowArtist;
    }

    public JsonObject toJson(UUID uuid, String name) {
        JsonObject o = new JsonObject();
        if (uuid != null) o.addProperty("uuid", uuid.toString());
        o.addProperty("name", name == null ? "" : name);
        o.addProperty("mod", mod);
        o.addProperty("modName", displayName());
        o.addProperty("modBadge", badge());
        o.addProperty("modVersion", modVersion);
        o.addProperty("proto", proto);
        o.addProperty("vip", hasVip());
        JsonArray a = new JsonArray();
        for (String s : vipSources) a.add(s);
        o.add("vipSources", a);
        o.addProperty("contributeVip", contributeVip);
        o.addProperty("automixCapable", automixCapable);
        o.addProperty("automixEngine", automixEngine == null ? "" : automixEngine);
        o.addProperty("nowPlaying", nowPlaying == null ? "" : nowPlaying);
        o.addProperty("nowArtist", nowArtist == null ? "" : nowArtist);
        o.addProperty("localPlaying", localPlaying);
        return o;
    }

    /**
     * 吸收一个平台令牌数组：缺 key 时整集保持原值（老客户端不发 = 不知道，不是「为空」），
     * 来了才整体替换。服务端对令牌不透明，只校验格式；一条坏元素不该让整份上报作废。
     */
    private static void applySources(JsonObject o, String key, Set<String> out) {
        if (!o.has(key)) return;
        out.clear();
        JsonArray a = Proto.arr(o, key);
        if (a == null) return;
        for (int i = 0; i < a.size() && out.size() < 24; i++) {
            try {
                String s = a.get(i).getAsString();
                if (s != null && Proto.isValidSource(s.trim().toLowerCase(Locale.ROOT))) {
                    out.add(s.trim().toLowerCase(Locale.ROOT));
                }
            } catch (Throwable ignored) {
                // 半路坏元素只丢掉这一条，后面的照收。
            }
        }
    }

    private static String clip(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() <= 80 ? t : t.substring(0, 80);
    }
}