package com.muoniumplayer.core.ncm.music.ambient;

/**
 * 环境选曲的一帧环境快照（纯数据）。
 *
 * <p>世界状态住在树侧（各树 {@code compat/mc/AmbientProbe} 负责填这份数据），
 * 共享层只认字段，不认任何 Minecraft 类型 —— 与场景声学的 {@code ListenerSample}
 * 同一分层。字段语义与 modernMP 原版一致，判定规则见 {@link MoodDecider}。</p>
 *
 * <p>树侧采样保证「永不抛、永不返回 null」：采不到时给一份全默认的实例，
 * 决策树在默认值上自然落到兜底心情（休憩）。</p>
 */
public final class EnvSample {

    /** 维度：0 主世界、1 下界、2 末地。 */
    public int dimension;
    /** 方块线索：0 无、1 深暗（幽匿）、2 海底神殿（海晶）、3 要塞（石砖）、4 堡垒（黑石）、5 村庄（工作站）。 */
    public int blockClue;
    /** 玩家 Y。 */
    public double y;
    /** 光照 0..15。 */
    public int lightLevel;
    /** 16 格内敌对生物数。 */
    public int enemyCount;
    /** 16 格内动物数。 */
    public int neutralCount;
    /** 16 格内村民/流浪商人数。 */
    public int villagerCount;
    /** 一天内的时刻 0..24000（诊断口径；26.x 天色判定主要看 {@link #skyLightLevel}）。 */
    public long timeOfDay;
    /** 天光等级 0..15（已折入时刻与天气；&lt;4 视为夜）。26.x 由环境属性直读。 */
    public float skyLightLevel = 15f;
    public boolean raining;
    public boolean thundering;
    /** 开阔度：头顶 3×3×5 柱子里非实心方块占比，0 密封 .. 1 露天。 */
    public float openness;
    /** 血量比例 0..1。 */
    public float healthPct;
    public boolean inWater;
    /** 室内：头上有顶且至少三个水平方向五格内有墙。 */
    public boolean inBuilding;

    // ── 群系类标志（树侧按 tag / key 派生好，决策树不再碰世界） ──────────────
    public boolean biomeForest, biomePlains, biomeOcean, biomeRiver,
                   biomeMountain, biomeDesert, biomeJungle, biomeBeach;
    /** 群系 key 路径（如 {@code minecraft:plains} → {@code plains}），诊断输出用。 */
    public String biomeKeyPath = "unknown";

    /** 一段人类可读的诊断文本，打日志用。 */
    public String describe() {
        String[] dims = {"主世界", "下界", "末地"};
        String[] clues = {"无", "深暗(幽匿)", "海底(海晶)", "要塞(石砖)", "堡垒(黑石)", "村庄(工作站)"};
        return String.format(
                "维度=%s 群系=%s 方块线索=%s%n"
              + "Y=%.0f 天光=%.0f 开阔度=%.2f 血量=%.0f%%%n"
              + "敌人=%d 中立=%d 村民=%d%n"
              + "时间=%d(%s) 雨=%b 雷=%b 水中=%b 室内=%b",
                dims[Math.min(dimension, 2)], biomeKeyPath,
                clues[Math.min(blockClue, 5)],
                y, skyLightLevel, openness, healthPct * 100f,
                enemyCount, neutralCount, villagerCount,
                timeOfDay, timeOfDay < 12000 ? "昼" : "夜", raining, thundering, inWater, inBuilding);
    }
}
