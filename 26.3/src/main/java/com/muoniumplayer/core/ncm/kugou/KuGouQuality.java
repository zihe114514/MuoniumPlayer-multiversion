package com.muoniumplayer.core.ncm.kugou;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 酷狗自己的音质档位。
 *
 * <h3>为什么不在这里引用宿主的 {@code Quality}</h3>
 * <p>{@code core.ncm.kugou} 这一层只认识酷狗的协议，音质映射（网易云的八个档位怎么落到酷狗这
 * 几档）属于适配层，放在 {@code KuGouMusicService} 里。反过来依赖会让「酷狗包」开始假设宿主
 * 的档位集合，而那个集合是会随网易云接口变的。</p>
 *
 * <h3>档位名是协议字面量</h3>
 * <p>{@link #key()} 就是 {@code /v5/url} 的 {@code quality} 参数与 {@code get_res_privilege/lite}
 * 的 {@code qualities} 数组元素，取值来自参考实现的 {@code SongQuality} 类型定义
 * （{@code interface.d.ts}）。<b>改名等于断链</b>：上游对未知音质不报错，只是不给地址。</p>
 *
 * <h3>每一档要配它自己的 hash</h3>
 * <p>与本类无关但必须一起记住的约束：{@code /v5/url} 按 {@code (hash, quality)} 成对授权，
 * 拿 128 的 hash 要 {@code flac} 会直接 {@code status=2}。所以取流时要用
 * {@link #hashFieldNames()} 里那些字段名对应的 hash，而不是顶层那一个。</p>
 */
public enum KuGouQuality {

    /**
     * 标准 128kbps，所有账号（含匿名）都能拿到。
     *
     * <p>{@code FileHash} 是搜索端点那份，{@code hash} 是老 mobilecdn 与大多数现代端点那份。</p>
     */
    STANDARD("128", "标准", 0, "128", new String[]{"hash", "hash_128", "FileHash"}),

    /** 高品质 320kbps。搜索响应里它是 {@code HQ.Hash}，老接口里是 {@code 320hash}。 */
    HIGH("320", "高品质", 1, "hq", new String[]{"hash_320", "320hash", "HQ.Hash"}),

    /** 无损 FLAC。搜索响应里它是 {@code SQ.Hash}，很多歌没有这一档。 */
    FLAC("flac", "无损", 2, "flac", new String[]{"hash_flac", "sqhash", "SQ.Hash"}),

    /**
     * 高品无损（24bit）。
     *
     * <p>与 {@link #FLAC} 是两个不同的 {@code quality} 取值。这一档<b>没有</b>可靠的通用字段名：
     * 列表类端点通常只给到无损那一档，更高的要靠 {@code get_res_privilege/lite} 现问，
     * 所以候选表只留几个见过的位置。</p>
     */
    HI_RES("high", "高品无损", 3, "flac", new String[]{"hash_high", "high_hash", "HI.Hash"}),

    /**
     * 母带（DSD / 超级会员音质）。
     *
     * <p>只有少数曲目有，且必须超级会员。搜索响应里它是 {@code Res.Hash}。
     * 取不到时按阶梯降级，不要当成故障。</p>
     */
    SUPER("super", "母带", 6, "flac", new String[]{"hash_super", "Res.Hash"}),

    /** 臻品全景声（空间音频）。hash 只能靠 {@code get_res_privilege/lite} 问出来。 */
    ATMOS("viper_atmos", "臻品全景声", 4, "flac", new String[]{"hash_viper_atmos", "atmos_hash"}),

    /** 臻品超清。同上，hash 主要来自权限探测。 */
    CLEAR("viper_clear", "臻品超清", 5, "flac", new String[]{"hash_viper_clear", "clear_hash"}),

    /** 臻品母带（磁带模拟音效，与 {@link #SUPER} 不是一档）。 */
    TAPE("viper_tape", "臻品母带", 7, "flac", new String[]{"hash_viper_tape", "tape_hash"});

    /** 档位阶梯里最顶的那一档，用于「向上问不到就逐档降级」的起点。 */
    private static final int TOP_RANK = 7;

    private final String key;
    private final String label;
    private final int rank;
    /** 老 {@code trackercdn.kugou.com/i/v2/} 兜底链路用的 {@code br} 取值。 */
    private final String legacyBr;
    private final String[] hashFieldNames;

    KuGouQuality(String key, String label, int rank, String legacyBr, String[] hashFieldNames) {
        this.key = key;
        this.label = label;
        this.rank = rank;
        this.legacyBr = legacyBr;
        this.hashFieldNames = hashFieldNames;
    }

    /** 协议字面量（{@code quality} 参数、{@code qualities} 数组元素）。 */
    public String key() {
        return key;
    }

    /** 给人看的档位名。 */
    public String label() {
        return label;
    }

    /** 越大越高。只在本枚举内部可比，与宿主音质的对应关系在适配层。 */
    public int rank() {
        return rank;
    }

    public String legacyBr() {
        return legacyBr;
    }

    /** 上游各端点里「这一档的 hash」可能用的字段名，按可信度排序。 */
    public List<String> hashFieldNames() {
        return Collections.unmodifiableList(java.util.Arrays.asList(hashFieldNames));
    }

    /**
     * 由协议字面量还原档位。
     *
     * <p>未识别的取值（包括 {@code null} 和上游新加的档位名）返回 {@code null} 而不是抛异常：
     * 上游扩档位是常态，认不出来时调用方要的是「按最接近的已知档处理」，不是整页失败。</p>
     */
    public static KuGouQuality fromKey(String value) {
        String key = KuGouSign.nonNull(value).trim().toLowerCase(Locale.ROOT);
        for (KuGouQuality quality : values()) {
            if (quality.key.equals(key)) return quality;
        }
        return null;
    }

    /** {@code get_res_privilege/lite} 的 {@code qualities} 数组：参考实现里那一份固定顺序。 */
    public static List<String> privilegeProbeKeys() {
        List<String> keys = new ArrayList<String>();
        keys.add(STANDARD.key);
        keys.add(HIGH.key);
        keys.add(FLAC.key);
        keys.add(HI_RES.key);
        keys.add(ATMOS.key);
        keys.add(TAPE.key);
        keys.add(CLEAR.key);
        keys.add(SUPER.key);
        // 多轨（伴奏分离）不是音质档位，但上游那份数组里有它，缺了会影响响应的对应关系。
        keys.add("multitrack");
        return keys;
    }

    /**
     * 从某一档开始的降级链（含它自己，从高到低）。
     *
     * <p>取流必须成对尝试 {@code (hash, quality)}，而「这一档有没有」只有请求完才知道，
     * 所以调用方拿这条链逐档试。终点总是 {@link #STANDARD}：它一定存在，否则整首曲子放弃。</p>
     */
    public static List<KuGouQuality> ladderFrom(KuGouQuality requested) {
        int ceiling = requested == null ? STANDARD.rank : requested.rank;
        List<KuGouQuality> ladder = new ArrayList<KuGouQuality>();
        for (int rank = ceiling; rank >= STANDARD.rank; rank--) {
            for (KuGouQuality quality : values()) {
                // 同一个 rank 出现多次也不会重复添加：本枚举的 rank 是唯一的。
                if (quality.rank == rank) ladder.add(quality);
            }
        }
        if (ladder.isEmpty()) ladder.add(STANDARD);
        return ladder;
    }

    /** 阶梯的最高一档，用于「要最好的、不确定有什么」的场景。 */
    public static KuGouQuality top() {
        KuGouQuality best = STANDARD;
        for (KuGouQuality quality : values()) {
            if (quality.rank > best.rank) best = quality;
        }
        return best;
    }

    /** 阶梯里有没有比 {@code requested} 更高的档（用于决定要不要继续向上探测）。 */
    public static boolean hasAbove(KuGouQuality requested) {
        return requested != null && requested.rank < TOP_RANK;
    }

    // ── 魔法音频（人声/伴奏分离等） ──────────────────────────────────────────

    /**
     * 魔法音频类型，不是音质档位。
     *
     * <p>参考实现把它们与音质共用同一个 {@code quality} 参数，但要加 {@code magic_} 前缀
     * （{@code module/song_url.js} 开头那段白名单）。只有部分曲目有，取不到是常态。</p>
     */
    public static final List<String> MAGIC_TYPES = Collections.unmodifiableList(
            java.util.Arrays.asList("piano", "acappella", "subwoofer", "ancient", "surnay", "dj"));

    /**
     * 魔法音频对应的 {@code quality} 取值。
     *
     * @param type {@link #MAGIC_TYPES} 之一
     * @return {@code magic_<type>}；类型不在白名单里时返回空串（调用方按「不支持」处理，
     *         不要把未知类型原样拼上去——上游会静默不给地址）
     */
    public static String magicQuality(String type) {
        String name = KuGouSign.nonNull(type).trim().toLowerCase(Locale.ROOT);
        return MAGIC_TYPES.contains(name) ? "magic_" + name : "";
    }
}
