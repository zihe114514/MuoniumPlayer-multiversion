package com.muoniumplayer.compat.mc;

import com.muoniumplayer.core.ncm.music.ambient.EnvSample;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * 环境选曲的采样 shim:把当前游戏状态翻译成 common 侧的 {@link EnvSample}。
 * 与场景声学的 {@code ListenerProbe} 同一分层 —— 世界状态住在树侧,common 只认纯数据。
 *
 * <p>采的内容(继承 modernMP 的特征表):维度、群系类标志(tag + key 路径)、方块
 * 线索(幽匿/海晶/石砖/黑石/工作站 —— 客户端拿不到服务端的结构数据,用局部方块
 * 调色板推断结构)、16 格内的敌对/动物/村民计数、天气与时刻、头顶开阔度、几何
 * 围合的「室内」判定。判定规则在 common 的 {@code MoodDecider},这里只采数。</p>
 *
 * <h2>性能契约(主线程每 tick 一次,不能顿)</h2>
 * <ul>
 *   <li>方块线索只扫脚/头/头顶上方四层的 3×3,开阔度扫 3×3×5,室内判定是
 *     5 条 5~6 格的短线 —— 都是十几次方块读,量级与 ListenerProbe 的 24 条射线
 *     相当或更小。</li>
 *   <li>实体计数一次 {@code getEntities} 遍历(16 格盒),不按类型查三次。</li>
 * </ul>
 *
 * <p>调用约束:必须在<b>主线程</b>上跑(client tick 即是);采不到(主菜单、
 * 无玩家)返回 {@code null},控制器会当作「无世界」清状态。</p>
 */
public final class AmbientProbe {

    private AmbientProbe() {
    }

    /** 静态工厂:主菜单 / 无玩家 / 无世界时返回 {@code null}。 */
    public static AmbientProbe of(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            return null;
        }
        return new AmbientProbe();
    }

    /**
     * 一步到位:采不到就回 {@code null},采得到就当场采一帧。
     * 给树的共享 tick 桥用 —— 控制器只认 {@link EnvSample},不认识探针。
     */
    public static EnvSample captureOrNull(Minecraft client) {
        AmbientProbe probe = of(client);
        return probe == null ? null : probe.capture();
    }

    /** 从当前游戏状态采一帧环境。永不抛;采不到的部分给安全默认值。 */
    public EnvSample capture() {
        EnvSample f = new EnvSample();
        final Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.level == null) {
            return f;   // 全默认,永不 null
        }
        final Level world = client.level;

        BlockPos pos = client.player.blockPosition();

        // 1. 维度
        f.dimension = world.dimensionTypeRegistration().unwrapKey()
                .map(key -> {
                    String path = key.identifier().getPath();
                    return path.contains("nether") ? 1 : path.contains("the_end") ? 2 : 0;
                })
                .orElse(0);

        // 2. 群系类标志(tag 优先,plains/desert 无原版 tag 按 key 路径匹配)
        try {
            Holder<Biome> biome = world.getBiome(pos);
            f.biomeForest   = biome.is(BiomeTags.IS_FOREST);
            f.biomeOcean    = biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN);
            f.biomeRiver    = biome.is(BiomeTags.IS_RIVER);
            f.biomeMountain = biome.is(BiomeTags.IS_MOUNTAIN) || biome.is(BiomeTags.IS_HILL);
            f.biomeJungle   = biome.is(BiomeTags.IS_JUNGLE);
            f.biomeBeach    = biome.is(BiomeTags.IS_BEACH);
            biome.unwrapKey().ifPresent(k -> f.biomeKeyPath = k.identifier().getPath());
            f.biomePlains = f.biomeKeyPath.contains("plains") || f.biomeKeyPath.contains("meadow");
            f.biomeDesert = f.biomeKeyPath.contains("desert");
        } catch (Throwable ignored) {
            // 保持安全默认值
        }

        // 3. 方块线索(替代结构类型)
        f.blockClue = sniffBlockClue(world, pos);

        // 4. Y
        f.y = client.player.getY();

        // 5. 光照 —— 诊断字段,决策树只看 skyLightLevel(见 MoodDecider P4)
        try {
            f.lightLevel = world.getRawBrightness(pos, 0);
        } catch (Throwable ignored) {
        }

        // 6-8. 16 格盒内的生物计数。客户端有同步实体,计数可靠;一次遍历分三类。
        try {
            AABB box = new AABB(pos).inflate(16.0, 16.0, 16.0);
            for (var e : world.getEntities(
                    net.minecraft.world.level.entity.EntityTypeTest.forClass(LivingEntity.class),
                    box, LivingEntity::isAlive)) {
                if (e instanceof Monster) f.enemyCount++;
                else if (e instanceof AbstractVillager) f.villagerCount++;
                else if (e instanceof Animal) f.neutralCount++;
            }
        } catch (Throwable ignored) {
        }

        // 9-11. 天色 + 天气。26.x 的时钟体系(WorldClocks)没有旧版 getDayTime 的
        // 0..24000 口径,改读环境属性 SKY_LIGHT_LEVEL —— 它已把时刻、天气、天光
        // 折成一个 0..15 的整读数,「昼/夜」判定正好用它,不用碰新时钟。
        try {
            float skyLight = world.environmentAttributes()
                    .getValue(net.minecraft.world.attribute.EnvironmentAttributes.SKY_LIGHT_LEVEL, pos);
            f.skyLightLevel = skyLight;
            f.timeOfDay = skyLight < 4 ? 18000L : 6000L;   // 暗=夜,亮=昼(仅诊断口径)
            f.raining    = world.isRaining();
            f.thundering = world.isThundering();
        } catch (Throwable ignored) {
        }

        // 12. 开阔度 —— 头顶 3×3×5 柱子里非实心方块占比
        f.openness = computeOpenness(world, pos);

        // 13. 血量
        try {
            float maxH = client.player.getMaxHealth();
            f.healthPct = maxH > 0f ? client.player.getHealth() / maxH : 1f;
        } catch (Throwable ignored) {
        }

        // 14. 水
        f.inWater = client.player.isInWater() || client.player.isUnderWater();

        // 15. 室内 —— 几何围合,与材质无关
        f.inBuilding = computeInBuilding(world, pos);

        return f;
    }

    // ── 方块线索调色板(按 Blocks 身份判,这些块共享普通 Block 类) ────────────
    private static final Block[] DEEP_DARK = {
            Blocks.SCULK, Blocks.SCULK_CATALYST, Blocks.SCULK_SENSOR,
            Blocks.SCULK_SHRIEKER, Blocks.SCULK_VEIN, Blocks.REINFORCED_DEEPSLATE};
    private static final Block[] MONUMENT = {
            Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE, Blocks.SEA_LANTERN};
    private static final Block[] STRONGHOLD = {
            Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS,
            Blocks.CHISELED_STONE_BRICKS, Blocks.INFESTED_STONE_BRICKS};
    private static final Block[] BASTION = {
            Blocks.BLACKSTONE, Blocks.POLISHED_BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICKS,
            Blocks.GILDED_BLACKSTONE, Blocks.CHISELED_POLISHED_BLACKSTONE, Blocks.BASALT,
            Blocks.POLISHED_BASALT};
    private static final Block[] VILLAGE = {
            Blocks.CRAFTING_TABLE, Blocks.CARTOGRAPHY_TABLE, Blocks.SMOKER, Blocks.FLETCHING_TABLE,
            Blocks.LOOM, Blocks.BLAST_FURNACE, Blocks.GRINDSTONE, Blocks.STONECUTTER,
            Blocks.SMITHING_TABLE, Blocks.BELL, Blocks.COMPOSTER, Blocks.BARREL};

    /** 扫脚/头/头顶上方的 3×3 找签名方块;先中先赢。 */
    private static int sniffBlockClue(Level world, BlockPos center) {
        // dy 扫:脚下一格、脚、头上两格 —— 站在上面和站在里面都盖住。
        for (int dy : new int[]{-1, 0, 2, 3}) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Block b;
                    try {
                        b = world.getBlockState(center.offset(dx, dy, dz)).getBlock();
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (contains(DEEP_DARK, b))  return 1;
                    if (contains(MONUMENT, b))   return 2;
                    if (contains(STRONGHOLD, b)) return 3;
                    if (contains(BASTION, b))    return 4;
                    if (contains(VILLAGE, b))    return 5;
                }
            }
        }
        return 0;
    }

    private static boolean contains(Block[] palette, Block b) {
        for (Block p : palette) {
            if (p == b) return true;
        }
        return false;
    }

    /** 头顶 3 列、头之上 5 格;开阔度 = 非实心占比。 */
    private static float computeOpenness(Level world, BlockPos center) {
        int total = 0, open = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 2; dy <= 6; dy++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    total++;
                    if (isOpenAt(world, p)) open++;
                }
            }
        }
        return total == 0 ? 1f : (float) open / total;
    }

    private static boolean isOpenAt(Level world, BlockPos p) {
        try {
            BlockState st = world.getBlockState(p);
            return st.isAir() || !st.canOcclude();
        } catch (Throwable ignored) {
            return true;   // 读不到当开阔,不至于把露天判成洞
        }
    }

    /**
     * 围合判定(与材质无关):「室内」= 头上有顶,且四个水平方向里至少三个在
     * 五格内被实心方块挡住。不按方块材质判「人造」,否则恶地整片陶瓦会被误判
     * 成室内(modernMP 踩过的坑);只看空间有没有被围住,露天沙漠自然不算。
     */
    private static boolean computeInBuilding(Level world, BlockPos center) {
        BlockPos head = center.above();
        if (!blockedWithin(world, head, Direction.UP, 6)) return false;   // 6 格内没顶 → 露天
        int walls = 0;
        if (blockedWithin(world, head, Direction.NORTH, 5)) walls++;
        if (blockedWithin(world, head, Direction.SOUTH, 5)) walls++;
        if (blockedWithin(world, head, Direction.EAST, 5))  walls++;
        if (blockedWithin(world, head, Direction.WEST, 5))  walls++;
        return walls >= 3;                                        // 大部分方向被围住
    }

    /** 从原点沿方向步进 1..maxSteps,撞上实心整块(能遮挡视线的)返回 true。 */
    private static boolean blockedWithin(Level world, BlockPos origin, Direction dir, int maxSteps) {
        for (int i = 1; i <= maxSteps; i++) {
            BlockPos p = origin.relative(dir, i);
            try {
                if (world.getBlockState(p).canOcclude()) return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }
}
