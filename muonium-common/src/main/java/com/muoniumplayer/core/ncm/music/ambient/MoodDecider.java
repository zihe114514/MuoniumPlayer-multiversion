package com.muoniumplayer.core.ncm.music.ambient;

/**
 * 优先级规则引擎：拿一份 {@link EnvSample} 快照，返回一个环境心情标签 —— 要么是单个
 * {@link Mood#display}，要么是两个都合届时的复合 {@code "A/B"}（播放侧把 {@code /}
 * 当 OR 处理，随机取一边）。
 *
 * <p>规则从高到低：战斗强制最高，其次维度覆盖，再方块线索的结构推断（替代客户端
 * 拿不到的结构数据），然后物理环境（水/深度/围合），再天气与时间，再群系 + Y 兜底，
 * 最后休憩保底 —— 结果永不为空。规则与阈值继承 modernMP 已调好的手感。</p>
 */
public final class MoodDecider {

    private MoodDecider() {
    }

    /** 判定当前环境的心情标签。永不返回 null 或空串。 */
    public static String decide(EnvSample f) {
        // ── P0: 强制战斗 —— 附近有敌对生物直接赢过一切 ──────────────────────
        if (f.enemyCount > 0) return Mood.COMBAT.display;

        // ── P1: 维度覆盖 ─────────────────────────────────────────────────────
        if (f.dimension == 1) {                                  // 下界
            if (f.blockClue == 4) return compound(Mood.NETHER, Mood.COMBAT);  // 堡垒
            return Mood.NETHER.display;
        }
        if (f.dimension == 2) {                                  // 末地
            return compound(Mood.END, Mood.EPIC);
        }

        // ── P2: 方块线索的结构推断（替代客户端拿不到的结构数据） ─────────────
        if (f.blockClue == 1) return Mood.EERIE.display;                       // 幽匿 → 深暗
        if (f.blockClue == 2) return compound(Mood.UNDERWATER, Mood.TENSE);    // 海晶 → 神殿
        if (f.blockClue == 3 && f.y < 16) return compound(Mood.TENSE, Mood.EXPLORE); // 石砖 + 低Y → 要塞
        if (f.blockClue == 5) return Mood.PASTORAL.display;                    // 工作站 → 村庄

        // ── P3: 物理环境（水 / 深度 / 围合） ────────────────────────────────
        if (f.inWater)                       return Mood.UNDERWATER.display;
        if (f.y < 0 && f.openness < 0.3f)    return compound(Mood.UNDERGROUND, Mood.TENSE); // 深洞穴
        // 围合（有顶有墙）是真房子 —— 必须先于「浅开阔度」的洞穴判定：带顶的房间
        // 头顶开阔度同样低，后判会被误读成洞。深洞穴（Y<0）在上面已经赢了。
        if (f.inBuilding)                    return compound(Mood.BUILD, Mood.REST);
        if (f.openness < 0.2f)               return Mood.UNDERGROUND.display;               // 浅洞/封闭

        // ── P4: 天气 + 天色 ──────────────────────────────────────────────────
        if (f.thundering) return compound(Mood.TENSE, Mood.EERIE);
        if (f.raining)    return Mood.CALM.display;
        // 天光等级已把时刻与天气折成一个读数（26.x 直读环境属性；旧口径由探针换算）。
        if (f.skyLightLevel < 4f) return Mood.NIGHT.display;      // 夜晚
        float t = f.timeOfDay / 24000f;                           // 0..1（诊断口径）
        if (t > 0.55f && t < 0.95f) return Mood.NIGHT.display;

        // ── P5: 群系 + Y 兜底（白天、露天、主世界） ─────────────────────────
        if (f.biomeMountain && f.y > 100)    return compound(Mood.EXPLORE, Mood.EPIC);
        if (f.biomeForest)                   return f.openness > 0.6f ? Mood.EXPLORE.display : Mood.CALM.display;
        if (f.biomeJungle)                   return Mood.EXPLORE.display;
        if (f.biomePlains)                   return Mood.CALM.display;
        if (f.biomeDesert)                   return Mood.EXPLORE.display;
        if (f.biomeOcean || f.biomeRiver || f.biomeBeach) return compound(Mood.UNDERWATER, Mood.CALM);

        // ── P6: 最终保底 ─────────────────────────────────────────────────────
        return Mood.REST.display;
    }

    /** 两个心情拼成 {@code "A/B"} 复合标签（播放侧按 OR 处理）。 */
    private static String compound(Mood a, Mood b) {
        return a.display + "/" + b.display;
    }
}
