package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.screens.ncm.LyricLine;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * 3D 世界歌词语义层 —— 行切换/暂停/seek 的状态机与逐字布局的纯函数核心。
 *
 * <p>职责划分：</p>
 * <ul>
 *   <li>{@link #updateClock} —— 播放时钟的推进与换轨检测；</li>
 *   <li>{@link #update} —— 行切换/暂停/恢复的判定与句的升降级；</li>
 *   <li>{@link SpawnedLine#spawn} —— 新句在视线前方的落位与逐字出现时刻
 *       （{@link SpawnedLine#charTimesOf}）；</li>
 *   <li>{@link #splitWords}/{@link #wrap} —— 拆词与按像素宽度换行；</li>
 *   <li>{@link Row#resolve} —— 面向玩家 / keep away / 尺寸公式的帧布局；</li>
 *   <li>{@link LyricGlyphs#build} —— 逐字出现 + smoothstep 的字形布局；</li>
 *   <li>{@link #seededJitter} —— 入场动画的确定性抖动（渲染层消费）；</li>
 *   <li>{@link #resumeFadeOf}/{@link #fadeAlphaOf} —— 恢复播放淡入与淡出系数。</li>
 * </ul>
 *
 * <p>时钟口径：统一取 {@code CloudMusic.getSnapshot()}（含歌词偏移的唯一时钟）；
 * 字形观感由 {@code LyricGlyphAtlas} 图集烘焙承担，发光由 TextGlow 烘焙。</p>
 */
public final class WorldLyricsState {

    // ==================== 布局与动效常量 ====================

    /** 歌词文字颜色（默认纯白；取色器可自定义）。 */
    public static final int TEXT_RGB = 0xFFFFFF;
    /** 平滑字体的字号（和 MC 字体大写字高差不多）。 */
    public static final float LYRIC_SIZE = 10f;
    /** 多行换行的行高。 */
    public static final float LINE_H = 11f;
    /** 换行最大像素宽（wrap(text, 200) 口径）。 */
    public static final float WRAP_WIDTH = 200f;
    /** 逐字出现的总时长上限：min(字符数×55ms, 行时长×0.6)，下限 250ms。 */
    private static final long REVEAL_PER_CHAR_MS = 55L;
    private static final long REVEAL_MIN_MS = 250L;
    private static final double REVEAL_LINE_RATIO = 0.6;
    /** 单个字的淡入时长（/180f）。 */
    private static final float CHAR_FADE_MS = 180f;
    /** 逐字出现时的上浮距离（(1-a)*5f）。 */
    private static final float CHAR_LIFT_PX = 5f;
    /** 淡出时长与上浮速度（0.4s、lift=t*0.4）。 */
    public static final float FADE_SECONDS = 0.4f;
    public static final float FADE_LIFT = 0.4f;
    /** 恢复播放淡入（0.3s）。 */
    public static final double RESUME_FADE_SECONDS = 0.3;
    /** 暂停多久内算「暂停」而不是「停了」（3 秒）。 */
    private static final double SILENCE_QUIT_SECONDS = 3.0;
    /** keep away 的最小距离（distance*0.6）。 */
    public static final double KEEP_AWAY_MIN_RATIO = 0.6;
    /** spawn 的 pitch 下沉（pitch-3f-random*10f，夹 -60..30）。 */
    private static final float SPAWN_PITCH_DROP = 3f;
    private static final float SPAWN_PITCH_RANDOM = 10f;
    /** 世界缩放公式（0.03*scale*(distance/6.5)）。 */
    public static final float PX_TO_WORLD = 0.03f;
    public static final float DISTANCE_NORM = 6.5f;

    // ==================== 帧间状态 ====================

    /** 正在播放的一句。单数 —— 一句一句出。 */
    private SpawnedLine active;
    /** 淡出中的句子。 */
    private final List<SpawnedLine> fading = new ArrayList<>();
    /** 当前歌曲 key（netease:{id}），换歌检测。 */
    private String currentKey;
    private long lastSeenNanos;
    /** 换行检测：上一句的行下标。 */
    private int lastIndex = -1;
    /** 当前 active 句对应的行下标：同句恢复判定用（去抖窗口内换行才重 spawn）。 */
    private int lastSpawnedIdx = -1;
    /** 暂停检测。 */
    private boolean wasPaused;
    /**
     * 上一帧的播放位置（毫秒）：同句内 seek 检测用。暂停帧也照记 —— 暂停期间
     * 位置不变，恢复后第一帧的「位置差」不会误报；恢复本身走 wasPaused 链路，
     * 与这里的重同步互不干扰。
     */
    private long lastFramePosMs = Long.MIN_VALUE;
    /** 同句内 seek 的判定阈值（毫秒）：逐帧正常推进远小于它，拖进度条必然超过。 */
    static final long SEEK_RESYNC_THRESHOLD_MS = 300L;
    /** 上一帧墙钟（纳秒）：把「位置跳幅」与「帧间隔」分开判，掉帧不误报 seek。 */
    private long lastFrameNanos;
    /**
     * 淡出去抖：最近一次看到「在播」的时间。歌曲自然结束时引擎的
     * {@code isFinished()} 在解码收尾边界可能抖动几帧（true/false 交替），
     * 没有去抖的话同一句会走「retire→同句重 spawn→再 retire」的循环 ——
     * 表现就是歌词结束时闪烁一下才消失。
     */
    private static final long PAUSE_DEBOUNCE_NANOS = 150_000_000L; // 150ms
    private long lastPlayingNanos;
    private final Random random = new Random();

    // ==================== 数据模型 ====================

    /**
     * 一句漂浮的歌词（位置 spawn 后不变）。
     */
    public static final class SpawnedLine {
        public final List<String> lines;      // wrap 拆行后的文本
        public final long startMs;
        public final long revealMs;           // 逐字出现的总时长
        /** 时间线里的行下标：去抖恢复（takeFadingIfSameLine）按它认回同一句。 */
        final int lineIndex;
        public double x, y, z;
        public double spawnYawDeg, spawnPitchDeg;
        /** 回屏保持弹簧的角速度（度/秒）。 */
        public double vYaw, vPitch;
        /** 每个非空白字符出现的时间（相对这句开始，毫秒）——word pop 用。 */
        public final long[] charTimes;

        SpawnedLine(List<String> lines, long startMs, long revealMs,
                 int lineIndex,
                 double x, double y, double z, double spawnYawDeg, double spawnPitchDeg,
                 long[] charTimes) {
            this.lines = lines;
            this.startMs = startMs;
            this.revealMs = revealMs;
            this.lineIndex = lineIndex;
            this.x = x;
            this.y = y;
            this.z = z;
            this.spawnYawDeg = spawnYawDeg;
            this.spawnPitchDeg = spawnPitchDeg;
            this.charTimes = charTimes;
        }

        /** 测试等直接构造入口：不带时间线行下标（去抖恢复按 -1 视作不可认回）。 */
        public SpawnedLine(List<String> lines, long startMs, long revealMs,
                        double x, double y, double z, double spawnYawDeg, double spawnPitchDeg,
                        long[] charTimes) {
            this(lines, startMs, revealMs, -1, x, y, z, spawnYawDeg, spawnPitchDeg, charTimes);
        }

        // ---- 心象模式缓存（句身持有：分块与词云布局都是行的纯函数，一次算好多帧复用；
        //      摄像机在 WorldLyricsState 实例上逐帧推进）----
        List<CadenzaMotion.Token> cadenzaTokens;
        CadenzaMotion.Stage cadenzaStage;
        /** 布局所依据的整句渲染宽（量宽变化时布局失效重建）。 */
        float cadenzaLayoutWidth = -1f;

        // ---- 绘光动效的换句交接（见 HuiguangMotion）----
        /** 入场飞行的来源句：换句瞬间的上一句（null = 无上一句，落位入场）。 */
        SpawnedLine huiguangPrev;
        /** 交接瞬间来源句的公告牌几何（世界坐标 + 朝向 + 缩放快照）。 */
        BillboardSnapshot huiguangPrevGeo;
        /** 交接瞬间本句的公告牌几何（起飞点投影的参照平面）。 */
        BillboardSnapshot huiguangOwnGeo;
        /** 入场逐字飞行定义（按非空白字符序缓存；行的纯函数，首帧构建）。 */
        HuiguangMotion.Flight[] huiguangEntry;
        /** 入场飞行定义所依据的整句渲染宽（量宽漂移 >1px 时缓存失效重建，对齐 cadenza）。 */
        float huiguangEntryLayoutWidth = -1f;
        /** 排版与词级字号倍率缓存（与 entry 同一失效守卫；每帧重算是 D11 的量宽 O(n) 热点）。 */
        float[] huiguangLayoutCache;
        float[] huiguangLayoutScalesCache;
        /** 绘光余光：换句淡出走「先停在 0.75 再熄灭」的长曲线（folia passedDim 的
         *  3D 等价；普通模式仍 0.4s 一刀切）。 */
        boolean huiguangLinger;
        /** 绘光十字爆闪触发表 {charIdx, timeMs, sizeMul, dy}（spawn 时按种子定格；
         *  null = 本句不爆闪）。 */
        float[] huiguangBursts;
        /** 绘光预摆（folia 邻行槽位）：本句作为「下一句」提前出现时的居中偏移与
         *  缩放（spawn 时按种子定格；null = 未进入预摆）。预摆位在当前句平面
         *  下方（folia 横排 next 槽 0.735 字号间隙），换句时字从这里飞到中心。 */
        float huiguangPresetDx, huiguangPresetDy, huiguangPresetScale = -1f;
        /** 预摆飞入是否已完成（false = 仍在预摆预览态）。 */
        boolean huiguangPresetDone;
        /** 转正瞬间定格的预摆几何（世界坐标 + 朝向 + 缩放）—— 起飞点投影的来源面。 */
        BillboardSnapshot huiguangPresetGeo;
        /** 转正瞬间定格的本句几何（落点面）。 */
        BillboardSnapshot huiguangOwnPresetGeo;
        /** 预摆跟随：转正滑移的起点（预摆跟随位）与目标（视线正前位），及转正时刻。 */
        double huiguangSlideFromX, huiguangSlideFromY, huiguangSlideFromZ;
        double huiguangSlideToX, huiguangSlideToY, huiguangSlideToZ;
        long huiguangPromoteNanos;
        /** 预摆跟随的世界下移量（方块）—— spawn 时按槽位字面偏移换算定格。 */
        float huiguangPresetGapBlocks;
        /** 关键字匹配缓存（渲染层写读；spec 串引用变化时失效）。 */
        String cachedKeywordSpecRef;
        int[] cachedKeywordResult;

        long fadeStartNanos;
        long resumedAtNanos;
    }

    /** 相机输入。宿主每帧提供，避免 common 直接碰 MC 类。 */
    public interface CameraView {
        double eyeX();
        double eyeY();
        double eyeZ();
        double lookX();
        double lookY();
        double lookZ();
        /** 视线 yaw（度，MC 口径：-180..180，0 朝 +Z）。 */
        double yawDeg();
        /** 视线 pitch（度，MC 口径：-90..90）。 */
        double pitchDeg();

        /** 视场角（度，整画面垂直 FOV；Stay on screen 算视野边界用）。默认 70。 */
        default double fovDeg() { return 70.0; }

        /** 窗口/帧缓冲宽度像素（算水平视野的宽高比用）。默认 0 = 未知（按 16:9 兜底）。 */
        default int windowWidth() { return 0; }

        /** 窗口/帧缓冲高度像素。默认 0 = 未知。 */
        default int windowHeight() { return 0; }
    }

    /** 渲染参数快照（设置门面）。 */
    public interface Params {
        double distance();
        boolean splitLines();
        double spreadDeg();
        double scale();
        boolean facePlayer();
        boolean keepAway();
        boolean wordPop();
        /** 心象动效模式（词云布局 + 弹簧镜头 + 块出入场）。 */
        default boolean cadenza() { return false; }
        /** 绘光动效模式（换句飞行重排 + 径迹拖尾 + 卡拉OK点亮）。 */
        default boolean huiguang() { return false; }

        /** 回屏保持（stayOnScreen）：转开视角时歌词滑回视野边缘。 */
        default boolean stayOnScreen() { return false; }

        /** Above ground：歌词中心不低于眼睛下方 0.5 格（不沉进地面）。 */
        default boolean aboveGround() { return false; }

        /**
         * 更激进的适配：光影包激活时歌词推迟到 HUD 阶段（光影后处理链之后）用原版
         * 着色器直画 —— 光影的打光/雾/色调映射/后处理完全碰不到歌词。只在树侧的
         * 绘制编排里读，状态机不感知。
         */
        default boolean hudDraw() { return false; }
    }

    /** 一行歌词这一帧的最终落点与字形状态，渲染层只读。 */
    public static final class Row {
        public final SpawnedLine floating;
        /** 锚点（世界坐标）。facePlayer/keepAway 处理后的最终位置。 */
        public final double x, y, z;
        /** 本帧相机世界位置（渲染层换算镜头相对坐标用）。 */
        public final double camX, camY, camZ;
        /** 字面朝向（Face player=朝向镜头的反算角；否则 spawn 冻结角）。 */
        public final double yawDeg, pitchDeg;
        /** 世界缩放（公告牌 push 的 scale 参数）。 */
        public final float scale;
        /** 0..1 显示透明度。 */
        public final float alpha;
        /** 淡出上浮（方块）。 */
        public final float lift;
        public final boolean active;
        /** 绘光保护压暗（folia PROTECT_FLOOR=0.4）：附近有他句时本句整体乘的
         *  暗度（1 = 无压暗）。注意压暗已在 State 组帧时乘进 alpha —— 此字段仅
         *  存档供调试，渲染层不应再乘（二次相乘会过度压暗）。 */
        public final float protectDim;

        Row(SpawnedLine floating, double x, double y, double z,
            double camX, double camY, double camZ,
            double yawDeg, double pitchDeg, float scale, float alpha, float lift, boolean active) {
            this(floating, x, y, z, camX, camY, camZ, yawDeg, pitchDeg, scale, alpha, lift, active, 1f);
        }

        Row(SpawnedLine floating, double x, double y, double z,
            double camX, double camY, double camZ,
            double yawDeg, double pitchDeg, float scale, float alpha, float lift,
            boolean active, float protectDim) {
            this.floating = floating;
            this.x = x; this.y = y; this.z = z;
            this.camX = camX; this.camY = camY; this.camZ = camZ;
            this.yawDeg = yawDeg;
            this.pitchDeg = pitchDeg;
            this.scale = scale;
            this.alpha = alpha;
            this.lift = lift;
            this.active = active;
            this.protectDim = protectDim;
        }
    }

    /**
     * 公告牌几何快照（绘光交接用）：换句瞬间把双方的「牌子在世界里的摆放」定格，
     * 之后 facePlayer/keepAway 怎么动都不影响已构建的飞行曲线起止点。
     * {@link #scale} 同 {@code resolve} 的世界缩放公式。
     */
    public static final class BillboardSnapshot {
        public final double x, y, z;
        public final double yawDeg, pitchDeg;
        public final float scale;

        BillboardSnapshot(double x, double y, double z, double yawDeg, double pitchDeg, float scale) {
            this.x = x; this.y = y; this.z = z;
            this.yawDeg = yawDeg; this.pitchDeg = pitchDeg;
            this.scale = scale;
        }

        /** 定格一句的当前摆放（keep away 不推进 —— 快照只要当时的实际位置）。 */
        static BillboardSnapshot capture(SpawnedLine f, CameraView camera, Params params) {
            double rx = f.x - camera.eyeX();
            double ry = f.y - camera.eyeY();
            double rz = f.z - camera.eyeZ();
            double len = Math.sqrt(rx * rx + ry * ry + rz * rz);
            double yaw;
            double pitch;
            if (params.facePlayer() && len > 1e-4) {
                yaw = Math.toDegrees(Math.atan2(-rx, rz));
                pitch = Math.toDegrees(Math.atan2(-ry, Math.sqrt(rx * rx + rz * rz)));
            } else {
                yaw = f.spawnYawDeg;
                pitch = f.spawnPitchDeg;
            }
            float scale = (float) (PX_TO_WORLD * params.scale() * (len / DISTANCE_NORM));
            return new BillboardSnapshot(f.x, f.y, f.z, yaw, pitch, scale);
        }

        /** 本句平面坐标 (px, py) 对应的世界坐标（push 正变换的展开）。
         * push = translate(b)·Ry(−yaw)·Rx(pitch)·S(−s,−s,s)，对 (px,py,0) 展开：
         * X = b·x + s·(−px·cosYaw + py·sinPitch·sinYaw)
         * Y = b·y − s·(py·cosPitch)
         * Z = b·z − s·(px·sinYaw + py·sinPitch·cosYaw) */
        public double[] planePointToWorld(float px, float py) {
            double p = Math.toRadians(pitchDeg);
            double cp = Math.cos(p), sp = Math.sin(p);
            double yaw = Math.toRadians(yawDeg);
            double cy = Math.cos(yaw), sy = Math.sin(yaw);
            double wx = -cy * px + (sy * sp) * py;
            double wy = -cp * py;
            double wz = -sy * px - (cy * sp) * py;
            return new double[] {x + wx * scale, y + wy * scale, z + wz * scale};
        }
    }

    /**
     * 逐字形状态：每个要画的字的位置、透明度、
     * 下标（确定性随机相位与出场级联的键）与 pop 缩放。由渲染层按此直接画。
     *
     * <p>心象模式下额外携带块的姿态四量与墨色（简洁模式全零 + 行统一墨色）：
     * {@code rot/dx/dy/scale} 由 {@link CadenzaMotion} 按块的出入场动画求值，
     * {@code cadenzaGlow} 是块的发光脉冲（0..1，渲染层据此选发光烘焙档）。</p>
     */
    public static final class LyricGlyph {
        public final char c;
        public final float x, y;   // 字的顶部（与 MC 字体一致；入场落位已含在内）
        /** 0..1。入场淡入给出初值；整行出场时由出场级联就地缩（非 final）。 */
        public float alpha;
        public final int k;        // 非空白字符序号（确定性随机/出场级联的相位键）
        /** 恒生效的弹性缩放（入场飞入回弹 / word pop 回弹 / 出场收拢），1 = 无。 */
        public float pop;
        /** 心象模式：块姿态（度 / 设计像素 / 乘法项），简洁模式全零。 */
        public float rot, dx, dy, scale;
        /** 心象模式：块墨色（0xRRGGBB 已带 alpha，简洁模式 0 = 用行统一墨色）。 */
        public int tokenRgb;
        /** 心象模式：块的发光脉冲 0..1（简洁模式 0）。 */
        public float cadenzaGlow;
        /** 心象模式：块正在被唱（提亮用；简洁模式恒 false）。 */
        public boolean tokenActive;
        /** 绘光模式：卡拉OK点亮进度 0..1（渲染层据此混冷暖色档；简洁/心象模式 0）。 */
        public float huiguangLit;
        /** 绘光模式：点亮瞬间四芒闪点的包络 0..1（渲染层据此画星芒；其他模式 0）。 */
        public float huiguangFlash;
        /** 绘光模式：字正在飞行（渲染层据此白化发光档）。 */
        public boolean flyingWhite;
        /** 绘光模式：关键字色（0xRRGGBB；0 = 无关键字，走全局墨色）。 */
        public int keywordRgb;

        LyricGlyph(char c, float x, float y, float alpha, int k, float pop) {
            this.c = c;
            this.x = x;
            this.y = y;
            this.alpha = alpha;
            this.k = k;
            this.pop = pop;
        }
    }

    /** 一句歌词的全部字形布局（相对平面原点，单位：文字像素）。 */
    public static final class LyricGlyphs {
        public final List<LyricGlyph> glyphs = new ArrayList<>();
        /** 与 glyphs 一一对应的入场相位（歌曲内 ms）——出场级联按它排「先唱先走」。 */
        public final List<Float> appearAts = new ArrayList<>();
        public final int seed;
        public final float totalHeight;
        /** 绘光模式：与 glyphs 一一对应的入场飞行定义（null 元素 = 该字无飞行）。 */
        public final List<HuiguangMotion.Flight> huiguangFlights = new ArrayList<>();
        /** 绘光模式：本句 elapsed（songNowMs − startMs），径迹回溯求值用。 */
        public long huiguangElapsedMs;
        /** 绘光模式：本句恒定绕行偏移（字心口径；径迹点也要加上它才能连住字）。 */
        public float huiguangDriftX, huiguangDriftY;
        /** 绘光模式：行级呼吸缩放（乘法项）与摇摆旋转（度）—— folia 整行脉动。 */
        public float huiguangLineScale = 1f, huiguangLineSwayDeg;
        /** 绘光模式：本句排版（与 buildGlyphs 的 huiguangLayout 同引用），光斑取排版路径用。 */
        public float[] huiguangLayout;
        /** 绘光模式：排版逐字词级字号倍率（与 glyphs 平行；光斑尺寸/位置换算用）。 */
        public float[] huiguangLayoutScales;
        /** 绘光模式：十字爆闪触发表（句身快照转发；null = 不爆闪）。 */
        public float[] huiguangBursts;
        /** 绘光模式：逐字关键字色（0xRRGGBB 或 0=无；与 glyphs 平行）。 */
        public int[] huiguangKeywordRgbs;

        LyricGlyphs(int seed, float totalHeight) {
            this.seed = seed;
            this.totalHeight = totalHeight;
        }
    }

    // ==================== 行切换 / 暂停 / 时钟 ====================

    /**
     * 推进一帧。返回本帧要画的所有行（active + fading）。
     * {@code snapshotPlaying}/{@code snapshotPositionMs} 由宿主从 CloudMusic.getSnapshot() 取好传入。
     */
    public Frame update(CameraView camera, Params params, long nowNanos,
                        boolean snapshotPlaying, long snapshotPositionMs) {
        updateClock(snapshotPlaying, nowNanos);
        presetNowMs = snapshotPositionMs;

        boolean paused = currentKey != null && !snapshotPlaying
                && (nowNanos - lastPlayingNanos) > PAUSE_DEBOUNCE_NANOS;
        if (snapshotPlaying) lastPlayingNanos = nowNanos;

        // 同句内 seek 重同步：上一帧位置与本帧差得远（拖了进度条）且仍在播放时，
        // 位置虽落在同一行的开唱区间内（idx 没变），但逐字点亮/出现进度已经错位
        // —— 必须把这句销毁重建（走「真·换行」同一条路径：旧句转淡出、同句重新
        // spawn，绘制交接让字从旧位飞来重排）。lastFramePosMs 每帧无条件更新
        //（含暂停帧），暂停期间位置不动、不会攒出差值；恢复播放走 wasPaused 链路，
        // 暂停帧后的恢复帧也不误报。seek 造成的交接与真实换句一致 —— active 淡出、
        // 当前行重新 spawn 是预期行为。
        boolean seekResync = false;
        if (snapshotPlaying && lastFramePosMs != Long.MIN_VALUE) {
            long posDelta = Math.abs(snapshotPositionMs - lastFramePosMs);
            // 墙钟门限：帧间隔本身 ≥ 阈值（重负载/掉帧）时，位置差即使超过
            // 阈值也可能是正常推进 —— 只有「位置跳幅明显大于墙钟间隔」才是
            // seek（拖进度条时间轴瞬移，与花了多久渲染无关）。
            long wallGap = Math.max(0L, nowNanos - lastFrameNanos) / 1_000_000L;
            seekResync = posDelta > SEEK_RESYNC_THRESHOLD_MS && posDelta > wallGap * 2;
        }
        lastFrameNanos = nowNanos;
        lastFramePosMs = snapshotPositionMs;

        // 行切换 / 暂停 / 恢复：
        // idx 即使暂停也算（indexAt 按时间戳二分），恢复时 idx==lastIndex 才算「恢复」而不是「换行」。
        List<LyricLine> lines = effectiveLines(params.splitLines());
        int idx = indexAtTimestamp(lines, snapshotPositionMs);
        if (paused) {
            retireActive(nowNanos);
            wasPaused = true;
        } else if (idx != lastIndex || wasPaused || seekResync) {
            boolean resumed = wasPaused && idx == lastIndex && !seekResync;
            // seek 帧的「同句」判定必须失效：同句但逐字进度已整体错位，走重建。
            boolean sameLine = !seekResync && idx == lastSpawnedIdx && active != null;
            wasPaused = false;
            lastIndex = idx;
                if (!sameLine) {
                    // 真·换行（或去抖窗口内同句已被 retire 且 playing 恢复，或同句内
                    // seek 重同步）。先把现句转淡出（满 alpha 的 active 硬切消失 =
                    // 闪帧；retire 后它至少还有一帧渐隐），再试把刚转淡的同句原样
                    // 捞回来 —— 捞回时现句一定已安全进入 fading，不存在被覆盖丢弃。
                    // seek 帧同句捞回是错的：句的逐字进度已整体错位，必须真正重建
                    //（takeFadingIfSameLine 只在恢复帧把同句认回 —— resumed 已在
                    // seek 帧被强制为 false，认回分支自然跳过，落到下面重新 spawn）。
                    SpawnedLine replaced = active;
                    retireActive(nowNanos);
                    // 预摆句优先转正（folia 换位语义）：LEAD 窗口里提前出现的
                    // 下一句正是本 idx —— 直接转正，字从预摆位飞到中心重排，
                    // 不走「旧句位置散花」。过期预摆句就地转淡出。
                    if (preset != null && preset.lineIndex == idx) {
                        if (replaced != null) {
                            replaced.huiguangLinger = true;   // 旧句让位滑开（R5 P2-7）
                        }
                        active = preset;
                        lastSpawnedIdx = idx;
                        // 入位滑移（folia 新行滑到窗口中心的 3D 等价）：
                        // from = 预摆跟随位（当前句下方），to = 视线正前位 ——
                        // 0.7s easeInOutCubic 与字面逐字飞行同窗完成。
                        // 起飞面定格：预摆跟随位（字面飞行 from 投影来源）。
                        active.huiguangSlideFromX = active.x;
                        active.huiguangSlideFromY = active.y;
                        active.huiguangSlideFromZ = active.z;
                        double lx = camera.lookX(), ly = camera.lookY(), lz = camera.lookZ();
                        double ll = Math.sqrt(lx * lx + ly * ly + lz * lz);
                        if (ll < 1e-4) { lx = 0; ly = 0; lz = 1; }
                        double dist = params.distance();
                        active.huiguangSlideToX = camera.eyeX() + lx / ll * dist;
                        active.huiguangSlideToY = camera.eyeY() + ly / ll * dist;
                        active.huiguangSlideToZ = camera.eyeZ() + lz / ll * dist;
                        active.huiguangPromoteNanos = nowNanos;
                        active.huiguangOwnPresetGeo = BillboardSnapshot.capture(active, camera, params);
                        active.huiguangPresetGeo = active.huiguangOwnPresetGeo;
                        active.huiguangPresetDone = true;
                        active.huiguangEntry = null;   // 飞行从预摆位重建
                        preset = null;
                        if (resumed) active.resumedAtNanos = nowNanos;
                        // 转正后 revive/spawn 分支都不得再跑 —— 否则下面会重新
                        // spawn 一个全新 SpawnedLine 把转正句顶掉（R5 期间实测踩坑）
                    } else {
                        if (preset != null) {
                            preset.fadeStartNanos = nowNanos;
                            preset.huiguangLinger = true;
                            fading.add(preset);
                            preset = null;
                        }
                    SpawnedLine revived = seekResync ? null : takeFadingIfSameLine(idx, nowNanos);
                    if (revived != null) {
                        active = revived;
                        lastSpawnedIdx = idx;
                        // 认回的句子走恢复淡入（resumeFadeOf 消费 resumedAtNanos）。
                        if (resumed) revived.resumedAtNanos = nowNanos;
                    } else if (idx >= 0 && idx < lines.size()) {
                        LyricLine line = lines.get(idx);
                        if (line != null && !line.isBreakLine && line.lyric != null && !line.lyric.isEmpty()) {
                            active = WorldLyricsState.spawn(line, lines, idx, camera, params, random);
                            lastSpawnedIdx = idx;
                            if (resumed) active.resumedAtNanos = nowNanos;
                        } else {
                            lastSpawnedIdx = idx;
                        }
                    } else {
                        lastSpawnedIdx = idx;
                    }
                    } // else（预摆未转正路径）结束
                    // 绘光交接（HuiguangMotion）：active 落定后把换句双方连同几何快照
                    // 互相记录 —— 飞行/径迹据此「从上一句的位置飞来重新排列组合」。
                    // 几何在交接瞬间定格（facePlayer 会让 Row 逐帧变，快照保证曲线
                    // 起止点稳定）。replaced 为 null 只出现在「此前无 active」的间隙
                    // / 首句 —— 此时回退取最近一句 fading（若还看得到）当飞行源，
                    // 对齐「永远从上一个窗口飞来」；都没有才退化为落位入场。
                    // 时间轴大跳（跨句 seek）与同句内 seek 重同步同样形成交接，
                    // 字从被跳过的旧句位置（或同句旧位）飞来。
                    // 预摆转正句不走「从旧句飞」交接：它的飞行来源是预摆位
                    if (active != null && active.huiguangPresetDone) {
                        // 跳过（huiguangOwnGeo/PrevGeo 均不设，buildGlyphs 走预摆来源）
                    } else if (active != null && active.huiguangPrev == null
                            && (active != replaced || replaced == null)) {
                        SpawnedLine source = replaced != null && active != replaced ? replaced
                                : (fading.isEmpty() ? null : fading.get(fading.size() - 1));
                        if (source != null && source != active) {
                            source.huiguangLinger = true;
                            BillboardSnapshot own = BillboardSnapshot.capture(active, camera, params);
                            active.huiguangPrev = source;
                            active.huiguangOwnGeo = own;
                            active.huiguangPrevGeo = BillboardSnapshot.capture(source, camera, params);
                            active.huiguangEntry = null;
                            // 断开 source 的上游引用链：否则 active → N → N−1 → … →
                            // 首句整条历史 SpawnedLine 被钉到换轨才释放。source 自己
                            // fading 期间画径迹只用 huiguangEntry（已构建的纯函数
                            // 快照），不再需要 geo/prev。
                            source.huiguangPrev = null;
                            source.huiguangPrevGeo = null;
                            source.huiguangOwnGeo = null;
                        }
                    }
                } else if (resumed) {
                active.resumedAtNanos = nowNanos;
            }
        }

        // 绘光预摆已摘除：不再有「下一句提前出现跟随视角」的预摆句，
        // 也就没有预摆跟随与转正滑移（见 ensurePresetSpawned 的注释）。
        if (huiguangOn(params) && !paused && active != null) {
            // 转正滑移兜底：历史会话里可能残留 huiguangPromoteNanos 的句
            //（保存/恢复边界），滑移推进保持原语义、终点为视线正前位
            SpawnedLine promoted = active.huiguangPresetDone
                    && active.huiguangPromoteNanos != 0L ? active : null;
            if (promoted != null) {
                float st = (nowNanos - promoted.huiguangPromoteNanos) / 1.0E9f;
                float slideT = Math.min(1f, st / 0.7f);
                float e = HuiguangMotion.easeInOutCubic(slideT);
                promoted.x = promoted.huiguangSlideFromX
                        + (promoted.huiguangSlideToX - promoted.huiguangSlideFromX) * e;
                promoted.y = promoted.huiguangSlideFromY
                        + (promoted.huiguangSlideToY - promoted.huiguangSlideFromY) * e;
                promoted.z = promoted.huiguangSlideFromZ
                        + (promoted.huiguangSlideToZ - promoted.huiguangSlideFromZ) * e;
            }
        }

        // 生命线防线（传送缺口）：跨维度传送/远距传送瞬间，句锚点还留在旧维度的
        // 世界坐标上，与相机相减是几十万格的巨量差值 —— 回屏/避让弹簧一步就把锚点
        // 推出发散区，之后 atan2/asin 全 NaN 且永不退出（顶点 NaN 被 GPU 剔除 =
        // 歌词「永久消失」，重进服务器也不恢复，因为 SpawnedLine 是进程级单例）。
        // 帧头扫描：锚点非有限、或离相机超过「距离上限×4 + 64」的句子直接重锚到
        // 当前视线下方（与新生成的句子同位），角速度清零，下一帧正常。
        float anchorMaxLen = (float) (params.distance() * 4.0 + 64.0);
        if (active != null) checkAnchorSanity(active, camera, params, anchorMaxLen);
        for (SpawnedLine f : fading) checkAnchorSanity(f, camera, params, anchorMaxLen);

        // 回屏保持 / 不沉入地面（stayOnScreen / aboveGround）：
        // 在画之前把越界句拉回视野（fading 句也 follow —— 与上游同序）
        clampIntoView(camera, params);

        // 淡出推进（0.4 秒后移除）
        Iterator<SpawnedLine> it = fading.iterator();
        while (it.hasNext()) {
            SpawnedLine f = it.next();
            if ((nowNanos - f.fadeStartNanos) / 1.0E9f > fadeDurationOf(f)) it.remove();
        }

        // 组帧。fading 在前、active 在后 —— 渲染层按下标顺序画，淡出中的句子先画
        // （下层），当前句后画（上层）：同屏时 active 盖在 fading 之上
        // （淡出层在先、在场层在后；深度测试已关，顺序即层级）。
        // 双向保护压暗（folia windowProtection 的 3D 等价）：两牌中心 < 8 格起
        // 渐入、< 3 格到 PROTECT_FLOOR=0.4 —— active 与 fading 双方都压（R5 审查
        // P2-7：单向压暗会出现「旧句比新句亮」的主次颠倒）。
        List<Row> out = new ArrayList<>(1 + fading.size());
        float curH = active != null ? active.lines.size() * LINE_H : 0f;
        for (SpawnedLine f : fading) {
            float a = fadeAlphaOf(f, nowNanos);
            if (a > 0.01f) {
                float t = (nowNanos - f.fadeStartNanos) / 1.0E9f;
                // resolveFrozen：淡出句不走 keepAway 硬推（spawn 位置冻结不被污染）
                float lift = t * FADE_LIFT;
                Row r = WorldLyricsState.resolveFrozen(f, camera, params, a, false, lift);
                float dim = 1f;
                if (active != null) {
                    dim = protectDimBetween(f, active);
                    if (dim < 0.999f) a *= dim;
                }
                // 旧句让位（folia 换位到上一行槽位）：绘光余光句淡出期间沿 y
                // 滑开「两行厚度一半 + 间隙」的距离，确保与新句不重叠；
                // 1.2s 内 easeOutSine 滑完（MC 世界 y 向上 → 旧句向上让位）
                if (f.huiguangLinger && active != null) {
                    float slide = (curH + f.lines.size() * LINE_H) / 2f
                            + PRESET_GAP_FONTS * LYRIC_SIZE;
                    float up = Math.min(1f, t / 1.2f);
                    up = (float) Math.sin(up * Math.PI / 2);
                    r = new Row(r.floating, r.x, r.y + slide * up * r.scale, r.z,
                            r.camX, r.camY, r.camZ, r.yawDeg, r.pitchDeg,
                            r.scale, a, lift, r.active, r.protectDim);
                    out.add(r);
                } else {
                    out.add(WorldLyricsState.resolveFrozen(f, camera, params, a, false, lift));
                }
            }
        }
        // 预摆句组帧行已随预摆系统一并摘除（preset 恒 null —— 见
        // ensurePresetSpawned 的注释），下一句唱到时正常 spawn 入场。
        if (active != null) {
            Row r = WorldLyricsState.resolve(active, camera, params,
                    resumeFadeOf(active, nowNanos), true, 0f);
            float dim = 1f;
            for (SpawnedLine f : fading) {
                dim = Math.min(dim, protectDimBetween(f, active));
            }
            if (dim < 0.999f) {
                r = new Row(r.floating, r.x, r.y, r.z,
                        r.camX, r.camY, r.camZ, r.yawDeg, r.pitchDeg, r.scale,
                        r.alpha * dim, r.lift, r.active, dim);
            }
            out.add(r);
        }
        return new Frame(out, currentKey != null);
    }

    /** 绘光模式是否生效（预摆系统只在绘光下启用）。 */
    private static boolean huiguangOn(Params params) {
        return params.huiguang();
    }

    // ---- 绘光预摆系统（folia 邻行槽位完整还原）----
    /** LEAD：下一句在开始唱前多少毫秒进入预摆（folia windowTiming LEAD=1.2s）。 */
    static final long PRESET_LEAD_MS = 1200L;
    /** 预摆缩放（folia 横排 nearScale 0.52）。 */
    static final float PRESET_SCALE = 0.52f;
    /** 预摆透明度（folia 上一行 0.9 档；下一行用 nextAlpha 0.95 的保守近邻值）。 */
    static final float PRESET_ALPHA = 0.9f;
    /** 预摆槽位偏移（字面 y 方向：当前行厚度一半 + 0.735 字号间隙 + 本行厚度一半，folia 同式）。 */
    static final float PRESET_GAP_FONTS = 0.735f;

    /** 处于预摆预览态的下一句（null = 无；同一时刻至多一句，随 active 换句交接）。 */
    private SpawnedLine preset;

    /**
     * 下一句提前 spawn 成预摆句（folia 邻行槽位）：当前句的剩余时间进入 LEAD
     * 窗口且下一句是实句时，把它提前 spawn 到「当前句平面下方 0.52 缩放」的
     * 整齐位置（字面不散花 —— 聚合/散点入场只在无预摆的 fallback 走）。
     * 预摆句与 active 平级持有：active 换句时预摆句转正、其余照常淡出。
     */
    private void ensurePresetSpawned(List<LyricLine> lines, int idx, CameraView camera,
                                     Params params, long nowNanos) {
        // 预摆句已整体摘除（用户反馈：下一句提前出现并跟随玩家视角影响观感）。
        // 下一句唱到时经正常 spawn 入场 —— 绘光交接链路（huiguangPrevGeo）
        // 会让字从上一句的位置带拖尾飞来重排，出场特效不受影响。
        //
        // 保留 huiguangPresetDone/PresetGeo 等字段与转正分支：revive 认回等
        // 路径仍可能读到（恒 false/null → 走旧句来源），字段与分支是死代码
        // 但删除牵连面大，风险大于收益。
    }

    /** 预摆窗口判定的当前歌曲毫秒（与 update 的快照同源）。 */
    private long presetNowMs;

    private long snapshotPositionMsForPreset() {
        return presetNowMs;
    }

    /** 时钟推进（超 3 秒没在放就清掉）。 */
    private void updateClock(boolean playing, long nowNanos) {
        String key = CloudMusic.lyricsTrackKey;
        boolean hasTrack = key != null && CloudMusic.lyricsMatchCurrentTrack();
        if (!hasTrack) {
            if (currentKey != null && (nowNanos - lastSeenNanos) / 1.0E9 > SILENCE_QUIT_SECONDS) {
                currentKey = null;
                retireActive(nowNanos);
                lastIndex = -1;
                lastSpawnedIdx = -1;
            }
            return;
        }
        lastSeenNanos = nowNanos;
        // 用 equals 而不是 !=（引用比较会误判）：trackKey 内容相同
        // 但实例不同（重新提交的同一首歌）不应触发换歌重置。
        if (!java.util.Objects.equals(key, currentKey)) {
            currentKey = key;
            retireActive(nowNanos);
            lastIndex = -1;
            lastSpawnedIdx = -1;
            // 换歌后位置从新歌的起点重新起算：旧歌的位置基线作废，
            // 否则新歌第一帧就会被误判成 seek。
            lastFramePosMs = Long.MIN_VALUE;
        }
    }

    /** 最后一句开始时间 &lt;= pos 的下标（二分查找，供暂停时也算 idx）。 */
    private static int indexAtTimestamp(List<LyricLine> lines, long positionMs) {
        if (lines == null || lines.isEmpty()) return -1;
        int lo = 0, hi = lines.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            LyricLine l = lines.get(mid);
            if (l != null && l.timestamp <= positionMs) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    // ==================== 回屏保持 / 不沉入地面（stayOnScreen / aboveGround） ====================

    /** Above ground：歌词中心最低在眼睛下面多少格（站着时离地还有一格多）。 */
    static final double GROUND_MARGIN = 0.5;

    /** Stay on screen 回屏弹簧的快慢（越大跟得越紧；12 ≈ 0.3s 回到位，临界阻尼）。 */
    static final double STAY_SPRING = 12.0;

    /** Above ground 的最低高度，平滑地跟着镜头走（NaN = 未启用）。 */
    private double groundFloor = Double.NaN;

    private long keepNanos;

    /**
     * 单句锚点的生命线检查：坐标非有限、或离相机远超合理范围（传送/维度切换的
     * 残留锚点）时，把句子重锚到当前视线的生成位（等价于这句话刚生成），
     * 角速度一并清零 —— 弹簧从静止状态重新收敛。
     */
    private void checkAnchorSanity(SpawnedLine f, WorldLyricsState.CameraView camera,
                                   Params params, float maxLen) {
        double rx = f.x - camera.eyeX();
        double ry = f.y - camera.eyeY();
        double rz = f.z - camera.eyeZ();
        boolean insane = !Double.isFinite(rx) || !Double.isFinite(ry) || !Double.isFinite(rz)
                || (rx * rx + ry * ry + rz * rz) > (double) maxLen * maxLen;
        if (!insane) return;
        double d = params.distance();
        double yaw = camera.yawDeg();
        double pitch = Math.max(-60.0, Math.min(30.0, camera.pitchDeg() - 5.0));
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        f.x = camera.eyeX() - Math.sin(yr) * Math.cos(pr) * d;
        f.y = camera.eyeY() - Math.sin(pr) * d;
        f.z = camera.eyeZ() + Math.cos(yr) * Math.cos(pr) * d;
        f.spawnYawDeg = (float) yaw;
        f.spawnPitchDeg = (float) pitch;
        f.vYaw = 0;
        f.vPitch = 0;
    }

    /** 角度归一到 -180..180。 */
    private static double wrapDegrees(double d) {
        double v = d % 360.0;
        if (v >= 180.0) v -= 360.0;
        if (v < -180.0) v += 360.0;
        return v;
    }

    /**
     * Above ground：换算成距离 d 处最多能往下偏的 pitch（度；MC pitch 向下为正）。
     * 歌词中心最低只到眼睛下面 {@link #GROUND_MARGIN} 格。
     */
    static double maxPitchFor(double d) {
        double s = Math.max(0.0, Math.min(1.0, GROUND_MARGIN / Math.max(1e-6, d)));
        return Math.toDegrees(Math.asin(s));
    }

    /**
     * Stay on screen：歌词（连同字宽）超出视野时，沿离视线最近的方向滑回屏幕边上。
     * 视野按设置的 FOV 与窗口宽高比算；离镜头距离不变。回屏用临界阻尼弹簧
     * （有速度、平滑加减速；子步长 1/240s，低帧率也稳定）。Above ground 的
     * 最低高度同样折算成边界交给同一个弹簧；地板高度用指数插值平滑跟镜头
     * （上慢 0.3s / 下快 0.15s，跳跃时不抽动）。
     */
    private void clampIntoView(WorldLyricsState.CameraView camera, Params params) {
        long now = System.nanoTime();
        double dt = keepNanos == 0L ? 0.016 : Math.max(0.0, Math.min(0.1, (now - keepNanos) / 1.0E9));
        keepNanos = now;
        boolean stay = params.stayOnScreen();
        boolean ground = params.aboveGround();
        if (!stay) {
            if (!ground) {
                groundFloor = Double.NaN;
                for (SpawnedLine f : fading) { f.vYaw = 0; f.vPitch = 0; }
                if (active != null) { active.vYaw = 0; active.vPitch = 0; }
            }
            return;
        }

        if (ground) {
            double target = camera.eyeY() - GROUND_MARGIN;
            groundFloor = Double.isNaN(groundFloor) ? target
                    : groundFloor + (target - groundFloor) * (1.0 - Math.exp(-dt / (target > groundFloor ? 0.3 : 0.15)));
        } else {
            groundFloor = Double.NaN;
        }

        double vHalf = Math.max(1.0, camera.fovDeg()) / 2.0;
        int wPx = camera.windowWidth();
        int hPx = camera.windowHeight();
        double aspect = (wPx > 0 && hPx > 0) ? (double) wPx / Math.max(1, hPx) : 16.0 / 9.0;
        double hHalf = Math.toDegrees(Math.atan(Math.tan(Math.toRadians(vHalf)) * aspect));
        double s = PX_TO_WORLD * params.scale() * (params.distance() / DISTANCE_NORM);
        double playerYaw = camera.yawDeg();
        double playerPitch = camera.pitchDeg();

        // active 与 fading 都 follow（上游同序；fading 句被 turn away 时也要回屏）
        double targetLen = params.distance();
        if (active != null) springTowardView(active, camera, targetLen, s, vHalf, hHalf, playerYaw, playerPitch, dt);
        for (SpawnedLine f : fading) springTowardView(f, camera, targetLen, s, vHalf, hHalf, playerYaw, playerPitch, dt);
    }

    /** 单句的回屏弹簧步进。dt 是本帧秒数。 */
    private void springTowardView(SpawnedLine f, WorldLyricsState.CameraView camera,
                                double targetLen, double s, double vHalf, double hHalf,
                                double playerYaw, double playerPitch, double dt) {
        double rx = f.x - camera.eyeX();
        double ry = f.y - camera.eyeY();
        double rz = f.z - camera.eyeZ();
        double len = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (len < 1e-4) return;

        // 距离回中：玩家走远/走近后，句子还钉在旧的世界锚点上，越挂越远
        //（实测手机档 48~56 格，又小又淡像"位置错了"）。回屏保持的语义是
        // "跟着玩家屏幕"—— 沿视线方向把句子平滑拉回/推回设定距离（0.5s
        // 时间常数），1 格以内不动（走路晃动不触发）。转向弹簧只管方向，
        // 距离以前没人管，就是这个缺口。
        if (Math.abs(len - targetLen) > 1.0) {
            double newLen = len + (targetLen - len) * (1.0 - Math.exp(-dt / 0.5));
            double k = newLen / len;
            rx *= k;
            ry *= k;
            rz *= k;
            f.x = camera.eyeX() + rx;
            f.y = camera.eyeY() + ry;
            f.z = camera.eyeZ() + rz;
            len = newLen;
        }

        // 字本身占的角度：留一半宽/高，再多留 2 度边距
        float maxW = 0f;
        for (String line : f.lines) {
            float w = 0f;
            for (int i = 0; i < line.length(); i++) {
                float adv = LyricGlyphAtlas.designAdvance(line.charAt(i));
                w += adv > 0 ? adv : pixelWidthOfFallback(line.charAt(i));
            }
            maxW = Math.max(maxW, w);
        }
        double halfW = Math.toDegrees(Math.atan2(maxW * s / 2.0, len));
        double halfH = Math.toDegrees(Math.atan2(f.lines.size() * LINE_H * s / 2.0, len));
        double hLim = Math.max(0.0, hHalf - halfW - 2.0);
        double vLim = Math.max(0.0, vHalf - halfH - 2.0);

        double curYaw = Math.toDegrees(Math.atan2(-rx, rz));
        double curPitch = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, -ry / len))));
        double dYaw = wrapDegrees(curYaw - playerYaw);
        double dPitch = curPitch - playerPitch;

        // 往下最多到哪：屏幕下边 vs（Above ground）地面高度，哪个更靠上用哪个
        double lowLim = vLim;
        if (!Double.isNaN(groundFloor)) {
            double floorPitch = Math.toDegrees(Math.asin(Math.max(-1.0,
                    Math.min(1.0, (camera.eyeY() - groundFloor) / len))));
            lowLim = Math.min(lowLim, floorPitch - playerPitch);
        }
        // 回屏目标收进边缘内侧 4°：钳制目标若正好是视野边缘，视野外的句子会被
        // 拉到边缘「停住」——用户看到的是它停在画面边缘线上而不是回到画面里。
        // 收进内侧让它明显回到视野中，之后由正常的边缘钳制接管。
        double innerH = Math.max(0.0, hLim - 4.0);
        double innerV = Math.max(0.0, vLim - 4.0);
        double tYaw = Math.max(-innerH, Math.min(innerH, dYaw));
        double tPitch = lowLim >= -innerV
                ? Math.max(-innerV, Math.min(lowLim, dPitch))
                : lowLim;

        // 偏出去多少（边界以内就是 0）
        double eYaw = dYaw - tYaw;
        double ePitch = dPitch - tPitch;
        if (eYaw == 0.0 && ePitch == 0.0 && Math.abs(f.vYaw) < 0.05 && Math.abs(f.vPitch) < 0.05) {
            f.vYaw = 0;
            f.vPitch = 0;
            return;
        }

        // 临界阻尼弹簧，子步 1/240s 积分（帧率低时也稳定）
        double moveYaw = 0, movePitch = 0;
        double left = dt;
        while (left > 1e-6) {
            double h = Math.min(left, 1.0 / 240.0);
            left -= h;
            f.vYaw += (-STAY_SPRING * STAY_SPRING * (eYaw - moveYaw) - 2.0 * STAY_SPRING * f.vYaw) * h;
            f.vPitch += (-STAY_SPRING * STAY_SPRING * (ePitch - movePitch) - 2.0 * STAY_SPRING * f.vPitch) * h;
            moveYaw += f.vYaw * h;
            movePitch += f.vPitch * h;
        }

        double newYaw = curYaw + moveYaw;
        double newPitch = Math.max(-89.0, Math.min(89.0, curPitch + movePitch));
        double yr = Math.toRadians(newYaw);
        double pr = Math.toRadians(newPitch);
        f.x = camera.eyeX() - Math.sin(yr) * Math.cos(pr) * len;
        f.y = camera.eyeY() - Math.sin(pr) * len;
        f.z = camera.eyeZ() + Math.cos(yr) * Math.cos(pr) * len;
        // 非朝向玩家模式下，字的朝向跟着一起转，免得斜着看
        f.spawnYawDeg = (float) (f.spawnYawDeg + wrapDegrees(newYaw - curYaw));
        f.spawnPitchDeg = (float) Math.max(-90.0, Math.min(90.0,
                f.spawnPitchDeg + (newPitch - curPitch)));
    }

    /** 量宽兜底（designAdvance 不可用时按像素字近似：全角 9 / 半角 5）。 */
    private static float pixelWidthOfFallback(char c) {
        return c >= 0x2E80 ? 9f : 5f;
    }

    /** 当前句转淡出。 */
    private void retireActive(long nowNanos) {
        if (active != null) {
            active.fadeStartNanos = nowNanos;
            fading.add(active);
            active = null;
        }
    }

    /**
     * 淡出列表里认回指定时间线行的句子（有则从 fading 移除、复位淡出时间戳），
     * 没有返回 null。调用方必须已把现 active retire 过（见换行分支）：认回与
     * retire 的顺序保证任何句子要么在 active、要么在 fading，绝无第三种状态，
     * 也就不存在「捞回时把满 alpha 的现句直接覆盖丢弃」的硬切。
     */
    private SpawnedLine takeFadingIfSameLine(int idx, long nowNanos) {
        if (idx < 0) return null;
        for (Iterator<SpawnedLine> it = fading.iterator(); it.hasNext(); ) {
            SpawnedLine f = it.next();
            if (f.lineIndex == idx) {
                it.remove();
                f.fadeStartNanos = 0L;
                f.resumedAtNanos = nowNanos;
                return f;
            }
        }
        return null;
    }

    /** 恢复播放淡入系数。 */
    public static float resumeFadeOf(SpawnedLine f, long nowNanos) {
        return f.resumedAtNanos == 0L ? 1f
                : (float) clamp01((nowNanos - f.resumedAtNanos) / 1.0E9 / RESUME_FADE_SECONDS);
    }

    /** 淡出系数（1 - t/0.4）。绘光余光句走
     *  「前 0.7s 停在 0.75 → 再 0.5s 熄灭」的长曲线（folia passedDim 的 3D 等价）。 */
    public static float fadeAlphaOf(SpawnedLine f, long nowNanos) {
        float t = (nowNanos - f.fadeStartNanos) / 1.0E9f;
        if (f.huiguangLinger) {
            // 余光 0.7s：唱完后旧句停在 0.75（folia passedDim 的 3D 等价），
            // 0.7s 后 0.5s 熄灭 —— 旧句短暂与新句同屏后让位
            if (t <= 0.7f) return 0.75f;
            return (float) clamp01(0.75 * (1.0 - (t - 0.7) / 0.5));
        }
        return (float) clamp01(1.0 - t / FADE_SECONDS);
    }

    /** 淡出总时长（移除时机与余光曲线共用）。 */
    static float fadeDurationOf(SpawnedLine f) {
        return f.huiguangLinger ? 1.2f : FADE_SECONDS;
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    /** 绘光飞行的姿态 scratch（渲染线程单线程使用）。 */
    private static final float[] HUIGUANG_POSE = new float[4];
    /** 行级绕行 scratch（每句求值一次、逐字复用；渲染线程单线程）。 */
    private static final float[] LINE_ORBIT_SCRATCH = new float[2];
    /** 逐字呼吸的复用输出（glyphBreathInto；渲染线程单线程）。 */
    private static final float[] BREATH_SCRATCH = new float[2];

    /** yaw/pitch（度，MC 口径）→ 视线单位向量。spawn 与测试共用。 */
    static double[] yawPitchDirectionStatic(double yawDeg, double pitchDeg) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        return new double[] {
                -Math.sin(yaw) * Math.cos(pitch),
                -Math.sin(pitch),
                Math.cos(yaw) * Math.cos(pitch)
        };
    }

    // ==================== 一句歌词（spawn / charTimes / wrap） ====================

    /**
     * spawn：把这句钉在「当时视线前方」——
     * yaw 带 spread 随机偏角、pitch 下沉 3~13°，之后位置不再变。
     */
    static SpawnedLine spawn(LyricLine line, List<LyricLine> lines, int lineIndex, CameraView camera,
                          Params params, Random random) {
        List<String> wrapped = wrap(line.lyric, WRAP_WIDTH);
        long nextMs = nextLineStartMs(lines, lineIndex);
        long[] charTimes = charTimesOf(line, nextMs);

        long chars = 0;
        for (String s : wrapped) chars += s.length();
        long lineDur = Math.max(400L, nextMs - line.timestamp);
        long reveal = (long) Math.min(
                Math.max(REVEAL_MIN_MS, chars * REVEAL_PER_CHAR_MS),
                lineDur * REVEAL_LINE_RATIO);

        double d = params.distance();
        // 散开角随距离收缩：spreadDeg 是为默认 10.5 格标定的（±30° 在近处都在视野内），
        // 同样的角度放到 48 格外字占的视角小一半以上，再叠 ±30° 就有一半句子生成在
        // 视野边缘之外 —— 回屏弹簧只能把它们拉到视野边缘停住（钳制目标就是边缘），
        // 看起来就是「字生成在背后/拉不回来」。这里把散开角钳到「该距离下可见锥内」：
        // 视野半角 − 字占半角 − 2° 边距，下限 4°（贴着视线也有散落感）。
        double vHalf = Math.max(1.0, camera.fovDeg()) / 2.0;
        double aspect = camera.windowWidth() > 0 && camera.windowHeight() > 0
                ? (double) camera.windowWidth() / Math.max(1, camera.windowHeight())
                : 16.0 / 9.0;
        double hHalf = Math.toDegrees(Math.atan(Math.tan(Math.toRadians(vHalf)) * aspect));
        float maxW = 0f;
        for (String s2 : wrapped) {
            float w = 0f;
            for (int i = 0; i < s2.length(); i++) {
                float adv = LyricGlyphAtlas.designAdvance(s2.charAt(i));
                w += adv > 0 ? adv : 9f;
            }
            maxW = Math.max(maxW, w);
        }
        double sPx = PX_TO_WORLD * params.scale() * (d / DISTANCE_NORM);
        double halfW = Math.toDegrees(Math.atan2(maxW * sPx / 2.0, d));
        // 散开角再乘一道「等视觉散布」钳制：spreadDeg 是为默认 10.5 格标定的观感
        //（横向 ±5.25 格），距离拉远后同样的角度会散出 ±24 格 —— 在宽屏上就是
        // 「字生成在玩家左右侧」。横向偏移钳到 10.5 格基线的量级。
        double lateralCap = Math.toDegrees(Math.asin(Math.min(1.0,
                10.5 * Math.sin(Math.toRadians(params.spreadDeg())) / d)));
        double spreadLimit = Math.max(2.0, Math.min(hHalf - halfW - 2.0, lateralCap));
        double yaw = camera.yawDeg()
                + (random.nextDouble() * 2.0 - 1.0) * Math.min(params.spreadDeg(), spreadLimit);
        // pitch = player.pitch − 3 − rnd×10（不下沉时随视线，整体略低），
        // 再夹 -60..30。之前把 player.pitch 也取了负 —— 平视时看不出，抬头/低头时歌词会跑到另一侧。
        double vLimSpawn = Math.max(4.0, vHalf - 2.0);
        double pitchDrop = Math.min(SPAWN_PITCH_DROP
                + random.nextDouble() * SPAWN_PITCH_RANDOM, vLimSpawn);
        double pitch = clampPitch(camera.pitchDeg() - pitchDrop);
        double[] dir = yawPitchDirectionStatic(yaw, pitch);
        // 不沉入地面（aboveGround）：生成时就不让歌词沉到地面下，
        // 之后的回屏弹簧继续把约束维持住
        if (params.aboveGround()) {
            pitch = (float) Math.min(pitch, maxPitchFor(d));
            dir = yawPitchDirectionStatic(yaw, pitch);
        }
        SpawnedLine f = new SpawnedLine(wrapped, line.timestamp, reveal, lineIndex,
                camera.eyeX() + dir[0] * d, camera.eyeY() + dir[1] * d, camera.eyeZ() + dir[2] * d,
                yaw, pitch, charTimes);
        // 十字爆闪规划（folia planBursts sung 模式的 3D 版）：spawn 时按种子定格
        int nonWs = 0;
        for (String w : wrapped) {
            for (int i = 0; i < w.length(); i++) {
                if (!Character.isWhitespace(w.charAt(i))) nonWs++;
            }
        }
        f.huiguangBursts = HuiguangMotion.planBursts(lineIndex, charTimes, nonWs,
                line.lyric != null ? line.lyric.hashCode() : lineIndex, 0.32f, 2);
        return f;
    }

    /** 中西文标点集（词字号压小档用）。 */
    private static final String PUNCTUATION_CHARS = "!?,.;:()[]{}<>-—…\"'‘’“”";
    /** 虚词/语气字（folia wordStyle 的功能词压小档；中文常用集）。 */
    private static final String HUIGUANG_PARTICLES = "的了着吗呢吧啊呀哦嘛么之乎者也把被和与或是";
    /** 词字号档位（folia wordStyle.ts）：重点词 1.45、虚词/标点 0.62、其余 0.85+rng×0.35。 */
    private static final float HUIGUANG_HERO_SCALE = 1.45f;
    private static final float HUIGUANG_PARTICLE_SCALE = 0.62f;

    /**
     * 绘光排版：把一句歌词的非空白字符排进公告牌平面（横排，与主循环同轴），
     * 带 folia wordStyle 的词级字号差异与错落 —— 重点词（句内最长实词）1.45、
     * 虚词/标点 0.62、其余 0.85~1.2；小字沿基线下沉 (1−scale)×0.32 字号并带
     * 错落抖动（folia 同式），行距放宽到 1.3 字号。
     *
     * @return 每非空白字符 {x, y, rotDeg, scale}（字顶坐标 + 姿态角 + 字号倍率；
     *         中心原点、消费侧按下标 hwN×4 取）
     */
    static float[] huiguangLayoutChars(List<String> lines, PixelWidthOf widthOf, int seed) {
        int count = 0;
        for (String line : lines) {
            for (int i = 0; i < line.length(); i++) {
                if (!Character.isWhitespace(line.charAt(i))) count++;
            }
        }
        float[] out = new float[Math.max(1, count) * 4];
        float rowPitch = LYRIC_SIZE * 1.3f;
        int i4 = 0;
        float y = -(lines.size() * rowPitch) / 2f;
        for (String line : lines) {
            // 每行抽词（splitWords 口径），分配词级字号
            List<String> words = splitWords(line);
            float[] wordScale = new float[words.size()];
            int hero = -1;
            int heroLen = 1;
            for (int w = 0; w < words.size(); w++) {
                String word = words.get(w);
                boolean particle = word.length() == 1
                        && (HUIGUANG_PARTICLES.indexOf(word.charAt(0)) >= 0
                            || isPunctuation(word.charAt(0)));
                if (particle) {
                    wordScale[w] = HUIGUANG_PARTICLE_SCALE;
                } else {
                    wordScale[w] = 0.85f + (seededJitter(w, 0L, 81, seed + line.hashCode()) * 0.5f + 0.5f) * 0.35f;
                    if (word.length() > heroLen) {
                        heroLen = word.length();
                        hero = w;
                    }
                }
            }
            if (hero >= 0) wordScale[hero] = HUIGUANG_HERO_SCALE;
            // 逐字落位：advance 与基线按词倍率缩放，小字下沉 + 错落
            float x = 0f;
            for (int w = 0; w < words.size(); w++) {
                String word = words.get(w);
                float scale = wordScale[w];
                for (int i = 0; i < word.length(); i++) {
                    char c = word.charAt(i);
                    if (Character.isWhitespace(c)) continue;
                    float adv = widthOf.widthOf(String.valueOf(c)) * scale;
                    // 错落：重点词不动，其余按种子上下抖（folia 小字 ±0.32/其余 ±0.2 字号）
                    float jagAmp = scale >= HUIGUANG_HERO_SCALE - 0.01f ? 0f
                            : (scale <= HUIGUANG_PARTICLE_SCALE + 0.01f ? 0.32f : 0.2f);
                    float jag = seededJitter(i4, 0L, 83, seed + line.hashCode()) * jagAmp * LYRIC_SIZE;
                    out[i4++] = x - widthOf.widthOf(line) * 0f; // 占位（x 基准在下方统一回填）
                    out[i4++] = y + (1f - scale) * LYRIC_SIZE * 0.32f + jag;
                    out[i4++] = 0f;
                    out[i4++] = scale;
                    x += adv;
                }
                x += widthOf.widthOf(" ") * 0.6f * scale; // 词间距
            }
            y += rowPitch;
        }
        // x 统一居中回填：按每行实际占用宽（含词距）算，去掉上面占位写法
        i4 = 0;
        for (String line : lines) {
            // 重走一遍该行的词与倍率（与上一循环同种子 → 同倍率）
            List<String> words = splitWords(line);
            float[] wordScale = new float[words.size()];
            int hero = -1;
            int heroLen = 1;
            for (int w = 0; w < words.size(); w++) {
                String word = words.get(w);
                boolean particle = word.length() == 1
                        && (HUIGUANG_PARTICLES.indexOf(word.charAt(0)) >= 0
                            || isPunctuation(word.charAt(0)));
                if (particle) {
                    wordScale[w] = HUIGUANG_PARTICLE_SCALE;
                } else {
                    wordScale[w] = 0.85f + (seededJitter(w, 0L, 81, seed + line.hashCode()) * 0.5f + 0.5f) * 0.35f;
                    if (word.length() > heroLen) {
                        heroLen = word.length();
                        hero = w;
                    }
                }
            }
            if (hero >= 0) wordScale[hero] = HUIGUANG_HERO_SCALE;
            float lineW = 0f;
            for (int w = 0; w < words.size(); w++) {
                for (int i = 0; i < words.get(w).length(); i++) {
                    if (!Character.isWhitespace(words.get(w).charAt(i))) {
                        lineW += widthOf.widthOf(String.valueOf(words.get(w).charAt(i)))
                                * wordScale[w];
                    }
                }
                if (w < words.size() - 1) lineW += widthOf.widthOf(" ") * 0.6f * wordScale[w];
            }
            float x = -lineW / 2f;
            for (int w = 0; w < words.size(); w++) {
                for (int i = 0; i < words.get(w).length(); i++) {
                    char c = words.get(w).charAt(i);
                    if (Character.isWhitespace(c)) continue;
                    // x 回填：只覆盖第一通道（y/rot/scale 已写好）
                    out[i4 * 4] = x + 0f;
                    x += widthOf.widthOf(String.valueOf(c)) * out[i4 * 4 + 3];
                    i4++;
                }
                if (w < words.size() - 1) x += widthOf.widthOf(" ") * 0.6f * wordScale[w];
            }
        }
        return out;
    }

    /** 中西文标点判定（词字号压小档用）。 */
    private static boolean isPunctuation(char c) {
        return (c >= 0x2000 && c <= 0x206F) || (c >= 0x3000 && c <= 0x303F)
                || (c >= 0xFF00 && c <= 0xFF65) || PUNCTUATION_CHARS.indexOf(c) >= 0;
    }

    private static long nextLineStartMs(List<LyricLine> lines, int lineIndex) {
        if (lines != null && lineIndex + 1 < lines.size()) {
            LyricLine next = lines.get(lineIndex + 1);
            if (next != null) return next.timestamp;
        }
        LyricLine cur = lines != null && lineIndex >= 0 && lineIndex < lines.size()
                ? lines.get(lineIndex) : null;
        return cur != null ? cur.timestamp + 5000L : 0L;
    }

    private static double clampPitch(double deg) {
        // 夹取：coerceIn(-60f, 30f)
        return Math.max(-60.0, Math.min(30.0, deg));
    }

    /**
     * 每个非空白字符的出现时间（charTimes）：
     * 有逐字歌词用真实时间，没有就把一句平均分给每个词（每词 330ms、最多占行 75%）。
     */
    static long[] charTimesOf(LyricLine line, long nextMs) {
        List<Long> times = new ArrayList<>();
        if (line.hasTimedWords() && !line.words.isEmpty()) {
            for (LyricLine.Word w : line.words) {
                if (w == null || w.word == null) continue;
                long at = Math.max(0L, w.timestamp - line.timestamp);
                // 词内逐字均摊（folia graphemeTiming）：把 [start, start+duration]
                // 摊到词内每个非空白字符 —— 卡拉OK从「逐词跳亮」变「逐字扫掠」，
                // 追字光斑也随之跟手。最短 80ms/字防一闪而过。
                int chars = 0;
                for (int i = 0; i < w.word.length(); i++) {
                    if (!Character.isWhitespace(w.word.charAt(i))) chars++;
                }
                long dur = Math.max(80L * Math.max(1, chars), w.duration);
                int ci = 0;
                for (int i = 0; i < w.word.length(); i++) {
                    char c = w.word.charAt(i);
                    if (Character.isWhitespace(c)) continue;
                    times.add(at + (chars > 1 ? dur * ci / (chars - 1) : 0L));
                    ci++;
                }
            }
        } else {
            List<String> toks = splitWords(line.lyric);
            long dur = Math.max(300L, nextMs - line.timestamp);
            double span = Math.min(dur * 0.75, toks.size() * 330.0);
            for (int i = 0; i < toks.size(); i++) {
                long at = (long) (span * i / Math.max(1, toks.size()));
                for (int c = 0; c < toks.get(i).length(); c++) times.add(at);
            }
        }
        long[] out = new long[times.size()];
        for (int i = 0; i < out.length; i++) out[i] = times.get(i);
        return out;
    }

    /** 拆词（splitWords）：英文按空格，中日韩每两个字一组。 */
    static List<String> splitWords(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                if (sb.length() > 0) {
                    out.add(sb.toString());
                    sb.setLength(0);
                }
                cjk = 0;
            } else if (c >= 0x2E80) {
                if (sb.length() > 0 && cjk == 0) {
                    out.add(sb.toString());
                    sb.setLength(0);
                }
                sb.append(c);
                cjk++;
                if (cjk >= 2) {
                    out.add(sb.toString());
                    sb.setLength(0);
                    cjk = 0;
                }
            } else {
                if (cjk > 0) {
                    if (sb.length() > 0) {
                        out.add(sb.toString());
                        sb.setLength(0);
                    }
                    cjk = 0;
                }
                sb.append(c);
            }
        }
        if (sb.length() > 0) out.add(sb.toString());
        return out;
    }

    /** 按像素宽度换行（wrap）。宽度用 MC 字体在渲染层换算，这里按字符宽 6px 估。 */
    static List<String> wrap(String text, float maxWidth) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int lastSpace = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(c);
            if (c == ' ') lastSpace = sb.length() - 1;
            if (estimatedWidth(sb.toString()) > maxWidth && sb.length() > 1) {
                if (lastSpace > 0) {
                    out.add(sb.substring(0, lastSpace).trim());
                    String rest = sb.substring(lastSpace + 1);
                    sb.setLength(0);
                    sb.append(rest);
                } else {
                    out.add(sb.substring(0, sb.length() - 1).trim());
                    sb.setLength(0);
                    sb.append(c);
                }
                lastSpace = -1;
            }
        }
        if (!sb.toString().trim().isEmpty()) out.add(sb.toString().trim());
        out.removeIf(String::isEmpty);
        return out;
    }

    /** MC 像素字估宽：全角 9px、半角 5px（1.21.1 原版字体全角字形宽 8~9px；精确值渲染层量）。 */
    private static float estimatedWidth(String s) {
        float w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += s.charAt(i) >= 0x2E80 ? 9f : 5f;
        }
        return w;
    }

    // ==================== 帧布局 ====================

    /** 一次世界渲染要画的全部内容。 */
    public static final class Frame {
        public final List<Row> rows;
        public final boolean available;

        Frame(List<Row> rows, boolean available) {
            this.rows = rows;
            this.available = available;
        }
    }

    /** 预摆行：位置已由 spawn 锚定（active 下方槽位），这里只把整行 scale 缩到预摆档。 */
    private static Row offsetRow(Row r, float dx, float dy, float scale) {
        return new Row(r.floating, r.x, r.y, r.z,
                r.camX, r.camY, r.camZ, r.yawDeg, r.pitchDeg,
                r.scale * scale, r.alpha, r.lift, r.active, r.protectDim);
    }

    /**
     * 两句公告牌中心的保护压暗系数：距离 ≥8 格 = 1（不压）、≤3 格 = 0.4
     * （folia PROTECT_FLOOR），中间线性渐入。
     */
    private static float protectDimBetween(SpawnedLine a, SpawnedLine b) {
        double dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d >= 8.0) return 1f;
        float k = (float) Math.max(0.0, Math.min(1.0, (d - 3.0) / 5.0));
        return 0.4f + 0.6f * k;
    }

    /** 绘光交接用的世界缩放快照（同一缩放公式，不修改句位置）。 */
    static double resolveScale(SpawnedLine f, CameraView camera, Params params) {
        double rx = f.x - camera.eyeX();
        double ry = f.y - camera.eyeY();
        double rz = f.z - camera.eyeZ();
        double len = Math.sqrt(rx * rx + ry * ry + rz * rz);
        return PX_TO_WORLD * params.scale() * (len / DISTANCE_NORM);
    }

    /**
     * 由 SpawnedLine 组装渲染行：
     * keep away 硬推、face player 从镜头→歌词方向反算朝向、尺寸随实际距离。
     */
    static Row resolve(SpawnedLine f, CameraView camera, Params params,
                       float alpha, boolean isActive, float lift) {
        return resolveInternal(f, camera, params, alpha, isActive, lift, true);
    }

    /** 淡出中的句子用：不推进 keepAway（冻结 spawn 位置，滑开让位不被污染）。 */
    static Row resolveFrozen(SpawnedLine f, CameraView camera, Params params,
                             float alpha, boolean isActive, float lift) {
        return resolveInternal(f, camera, params, alpha, isActive, lift, false);
    }

    private static Row resolveInternal(SpawnedLine f, CameraView camera, Params params,
                                       float alpha, boolean isActive, float lift,
                                       boolean allowKeepAway) {        double rx = f.x - camera.eyeX();
        double ry = f.y - camera.eyeY();
        double rz = f.z - camera.eyeZ();
        double len = Math.sqrt(rx * rx + ry * ry + rz * rz);

        // keep away：太近就沿方向推回最小距离，位置从此改变
        double minD = params.distance() * KEEP_AWAY_MIN_RATIO;
        if (allowKeepAway && params.keepAway() && len > 1e-4 && len < minD) {
            double k = minD / len;
            f.x = camera.eyeX() + rx * k;
            f.y = camera.eyeY() + ry * k;
            f.z = camera.eyeZ() + rz * k;
            rx *= k; ry *= k; rz *= k;
            len = minD;
        }

        double yaw;
        double pitch;
        if (params.facePlayer() && len > 1e-4) {
            // 朝向镜头：从「镜头 → 歌词」方向反算
            yaw = Math.toDegrees(Math.atan2(-rx, rz));
            pitch = Math.toDegrees(Math.atan2(-ry, Math.sqrt(rx * rx + rz * rz)));
        } else {
            yaw = f.spawnYawDeg;
            pitch = f.spawnPitchDeg;
        }

        // 尺寸：0.03 × scale × (distance/6.5)
        float scale = (float) (PX_TO_WORLD * params.scale() * (len / DISTANCE_NORM));

        return new Row(f, f.x, f.y + lift, f.z,
                camera.eyeX(), camera.eyeY(), camera.eyeZ(),
                yaw, pitch, scale, alpha, lift, isActive);
    }

    // ==================== 逐字形布局（drawAnimated 的精化版） ====================

    /** 入场动画时长（ms）：淡入 + 上浮 + 回弹共用这一条时间轴。 */
    private static final float ENTER_MS = 320f;
    /** 入场上浮距离（设计像素）：从下方 8px 处落位，比旧 5px 更有「飞入」感。 */
    private static final float ENTER_LIFT_PX = 8f;
    /** 入场回弹的过冲幅度（ease-out-back，c1=1.70158 的标准曲线）。 */
    private static final float ENTER_BACK = 1.70158f;
    /** 出场逐字级联的窗口（ms）：行开始淡出后，先唱的字先走。 */
    private static final float EXIT_STAGGER_MS = 220f;
    /** 出场缩小下限：字缩到 0.85 再随行 alpha 一起消失。 */
    private static final float EXIT_SCALE_MIN = 0.85f;

    /** ease-out-back：先过冲再落回，比 smoothstep 多一分弹性与华丽感。 */
    private static float easeOutBack(float t) {
        // c3 = c1 + 1
        float u = t - 1f;
        return 1f + (ENTER_BACK + 1f) * u * u * u + ENTER_BACK * u * u;
    }

    /**
     * 构建一句的字形布局与动画状态。
     *
     * <h2>入场（每个字一条 ENTER_MS 时间轴）</h2>
     * <ul>
     *   <li>相位：有逐字轴时用逐字时刻（词被唱到的瞬间起舞），否则按字序均分
     *       reveal 窗口，再叠 ±20ms 的 seededJitter 抖动 —— 群像错落，不像阅兵；</li>
     *   <li>位置：从下方 {@link #ENTER_LIFT_PX}px 处按 ease-out-back 落位
     *       （过冲 2px 再回落），alpha 前 60% 时间用 smoothstep 亮起来；</li>
     *   <li>缩放：0.6 → 1 的 ease-out-back（字从「远处飞来」），落到 1 后由
     *       word pop 的回弹接管（有逐字轴时）。</li>
     * </ul>
     *
     * <h2>出场（整行淡出 0.4s 的精化）</h2>
     * 行 alpha 统一淡出的同时，每个字按自身序号级联提前退场（先唱的先走，
     * 窗口 {@link #EXIT_STAGGER_MS}），并缩到 {@link #EXIT_SCALE_MIN} ——
     * 行的消失变成一串涟漪而不是一整块突然熄灭。
     *
     * <p>坐标以平面中心为原点、单位是文字像素（渲染层乘 scale 进世界）。
     * pop 语义：恒生效的弹性缩放系数，宿主绕字中心应用。</p>
     *
     * @param pixelWidthOf 单行像素宽（与绘制通道同源）
     * @param exiting      该行正在整行淡出（出场级联只在这一态生效；
     *                     恢复播放的整行淡入 alphaMul 同样 <1,不能误触发退场）
     * @param cadenza      心象动效模式（现代化引擎的可选开关）：整行分块、按块的
     *                     出入场动画/呼吸/墨色画（见 {@link CadenzaMotion}），
     *                     简洁模式的落位飞入与出场级联在该模式下不参与
     * @param cameraOffset 心象镜头位移 {x, y}（设计像素，{@code update} 里弹簧推进的
     *                     当前位置；null = 无位移）。渲染层把整块公告牌按它平移 ——
     *                     等价于镜头跟着唱到的块走
     */
    public static LyricGlyphs buildGlyphs(SpawnedLine f, long songNowMs, float alphaMul,
                                          boolean wordPop, boolean exiting, PixelWidthOf pixelWidthOf,
                                          boolean cadenza) {
        return buildGlyphs(f, songNowMs, alphaMul, wordPop, exiting, pixelWidthOf, cadenza, false, null);
    }

    public static LyricGlyphs buildGlyphs(SpawnedLine f, long songNowMs, float alphaMul,
                                          boolean wordPop, boolean exiting, PixelWidthOf pixelWidthOf,
                                          boolean cadenza, boolean huiguang, int[] keywordMatch) {
        List<String> lines = f.lines;
        long elapsedMs = songNowMs - f.startMs;
        // 逐字轴在 → 逐字点亮（卡拉OK扫掠）。原门控是 wordPop（弹跳设置），
        // 默认关 —— 用户实证：关掉 Word Pop 后整句 250ms 内一起点亮（均分
        // appearAt + 300ms 窗口取代了真实逐字时刻）。逐字点亮是卡拉OK的核心
        // 观感，不该挂在弹跳开关下：有轴即启用。wordPop 只管「唱到的字弹一下」
        //（1558 行的 pop 回弹），两件事彻底解耦。
        boolean flashMode = f.charTimes != null && f.charTimes.length > 0;
        boolean lingerFade = huiguang && exiting && f.huiguangLinger;
        int total = 0;
        for (String s : lines) total += s.length();
        total = Math.max(1, total);
        int seed = lines.hashCode();

        // 心象分块与词云布局：都是行的纯函数，缓存于句身（量宽变化时布局重建）。
        // 分块按「时刻变化」切词（charTimes 词内共享时刻），行界即块界；布局把块
        // 散布到「行的文字像素平面」上（心象 assignAnchors/place 一比一），放不下时
        // 舞台横向扩张 —— 3D 公告牌没有 2D 视口裁剪，扩舞台就是让牌子更宽。
        List<CadenzaMotion.Token> cadenzaTokens = null;
        CadenzaMotion.Stage cadenzaStage = null;
        float layoutWidth = 0f;
        if (cadenza) {
            for (String line : lines) {
                layoutWidth += pixelWidthOf.widthOf(line);
            }
            if (f.cadenzaTokens == null || Math.abs(f.cadenzaLayoutWidth - layoutWidth) > 1.0f) {
                List<CadenzaMotion.Token> tokens = new ArrayList<>();
                int nonWsCursor = 0;
                for (String line : lines) {
                    for (CadenzaMotion.Token t : CadenzaMotion.splitTokens(line, f.charTimes, line)) {
                        tokens.add(shiftToken(t, nonWsCursor));
                    }
                    for (int i = 0; i < line.length(); i++) {
                        if (!Character.isWhitespace(line.charAt(i))) nonWsCursor++;
                    }
                }
                CadenzaMotion.PixelMetrics metrics = c -> pixelWidthOf.widthOf(String.valueOf(c));
                f.cadenzaTokens = tokens;
                f.cadenzaStage = CadenzaMotion.layout(tokens, metrics, LINE_H,
                        Math.max(layoutWidth, 1.0));
                f.cadenzaLayoutWidth = layoutWidth;
            }
            cadenzaTokens = f.cadenzaTokens;
            cadenzaStage = f.cadenzaStage;
        }
        int cadenzaIndex = 0;

        // ---- 绘光交接的起飞点（本句平面坐标）：上一句的落点投到本句平面。
        //      投影依赖交接瞬间定格的几何快照，首帧算好缓存在句身 —— 之后
        //      facePlayer 转动也不改变已起飞的曲线；无上一句时为 null（落位入场）。
        //      缓存带量宽失效守卫（对齐 cadenza 的 cadenzaLayoutWidth 惯例）：
        //      字体风格切换（现代↔原版）会换 PixelWidthOf，旧缓存的飞行终点
        //      是旧布局的字心，不失效就会飞到错位处。
        HuiguangMotion.Flight[] entryFlights = null;
        float[] huiguangLayout = null;
        float[] huiguangLayoutScales = null;
        if (huiguang) {
            // 本句排版（含词级字号差异）：飞行终点与落位都用它。
            // 缓存随 entry 同一守卫 —— 每帧 O(n) 次量宽回调是 D11 的热点。
            // 一次 Σ widthOf 供 layout 与 entry 两个缓存守卫共用（原每帧算两遍）
            float layoutWidthNow = 0f;
            for (String line : lines) layoutWidthNow += pixelWidthOf.widthOf(line);
            boolean layoutStale = f.huiguangLayoutCache == null
                    || Math.abs(f.huiguangEntryLayoutWidth - layoutWidthNow) > 1.0f;
            if (layoutStale) {
                f.huiguangLayoutCache = huiguangLayoutChars(lines, pixelWidthOf, seed);
                f.huiguangLayoutScalesCache = new float[f.huiguangLayoutCache.length / 4];
                for (int i = 0; i < f.huiguangLayoutScalesCache.length; i++) {
                    f.huiguangLayoutScalesCache[i] = f.huiguangLayoutCache[i * 4 + 3];
                }
            }
            huiguangLayout = f.huiguangLayoutCache;
            huiguangLayoutScales = f.huiguangLayoutScalesCache;
            // 消费与构建解耦：entry 已缓存就直接用（纯函数快照，消费不需要 geo）——
            // 短句被换下时仍在飞行窗口内，fading 期间继续沿曲线飞完 + 拖尾收拢，
            // 而不是因 geo 被截断（引用链防线）瞬移回落位姿态。
            float entryLayoutWidth = layoutWidthNow; // 与 layout 守卫同值（合并 Σ）
            if (f.huiguangEntry == null || layoutStale) {
                // layout 缓存已在上文按同一守卫刷新，直接用
                // 每句必有飞行：有交接几何 → 从上一句对应字的位置飞来（重排）；
                // 无几何（首句/seek）或投影退化 → 从四周散点聚合飞入（folia gather
                // 的加强版：gather 也拖径迹）。量宽漂移才重建，fading 句消费缓存。
                int cap = Math.max(1, total);
                HuiguangMotion.Flight[] flights = new HuiguangMotion.Flight[cap];
                int kk = 0;
                // 预摆来源（folia 换位语义）：预摆几何在 → 起飞点 = 预摆位的对应字
                //（整句缩 0.52、位于下方槽位 —— 字面整齐，重排时从预摆位飞到中心）；
                // 旧句来源次之（换句交接）；散点聚合仅 seek/首句兜底（幅度收紧）。
                // 预摆来源（出场特效核心）：转正句的起飞点 = 预摆位字面坐标
                //（本句排版整体缩 PRESET_SCALE、下移槽位，中心原点口径）——
                // 纯字面坐标，无需世界投影（牌滑移由渲染层的入位滑移承担）
                boolean presetSrc = f.huiguangPresetDone && f.huiguangPresetScale > 0f;
                // 上一句排版 hoist 到逐字循环外（原 projectFromPrev 每字全句重排
                // 一次 = O(n²) 排版 + 数千 String 分配的换句尖峰；每句只算一次）
                float[] prevLayout = (!presetSrc && f.huiguangPrev != null
                        && f.huiguangOwnGeo != null && f.huiguangPrevGeo != null)
                        ? huiguangLayoutChars(f.huiguangPrev.lines, pixelWidthOf,
                                f.huiguangPrev.lines.hashCode()) : null;
                for (String line : lines) {
                    for (int i = 0; i < line.length(); i++) {
                        char c = line.charAt(i);
                        if (!Character.isWhitespace(c)) {
                            // 落点：本句排版的字心。控制点/终点都是「字心」口径 ——
                            // drawSmoothGlyph 的姿态四量 (dx,dy) 是字顶坐标的平移量、
                            // 绕字中心应用；落位后转换：顶点 = 字心 − 半宽/半高。
                            float cScale = huiguangLayout[kk * 4 + 3];
                            float tx = huiguangLayout[kk * 4]
                                    + pixelWidthOf.widthOf(String.valueOf(c)) * cScale * 0.5f;
                            float ty = huiguangLayout[kk * 4 + 1] + LYRIC_SIZE * 0.5f;
                            float fx, fy;
                            if (presetSrc) {
                                // 预摆位对应字的字心：本句排版整体缩 PRESET_SCALE、
                                // 下移槽位（字面坐标，中心原点口径 —— 与 to 同轴）
                                fx = huiguangLayout[kk * 4] * PRESET_SCALE
                                        + f.huiguangPresetDx
                                        + pixelWidthOf.widthOf(String.valueOf(c)) * cScale
                                                * PRESET_SCALE * 0.5f;
                                fy = huiguangLayout[kk * 4 + 1] * PRESET_SCALE
                                        + LYRIC_SIZE * (PRESET_SCALE - 1f) * 0.32f
                                        + f.huiguangPresetDy + LYRIC_SIZE * 0.5f;
                            } else {
                                float[] fp = (prevLayout != null)
                                        ? projectFromPrev(f, kk, pixelWidthOf, prevLayout) : null;
                                if (fp != null) {
                                    fx = fp[0];
                                    fy = fp[1];
                                } else {
                                    // seek/首句兜底：紧凑散点（1.2~2.2 字号，随缘聚拢）
                                    float ang = seededJitter(kk, 0L, 59, seed) * 2f * (float) Math.PI;
                                    float rad = (1.2f + (seededJitter(kk, 0L, 61, seed) + 1f) * 0.5f)
                                            * LYRIC_SIZE;
                                    fx = tx + (float) Math.cos(ang) * rad;
                                    fy = ty + (float) Math.sin(ang) * rad;
                                }
                            }
                            flights[kk] = HuiguangMotion.flight(kk, seed,
                                    fx, fy, tx, ty, LYRIC_SIZE, false);
                            kk++;
                        }
                    }
                }
                f.huiguangEntry = flights;
                f.huiguangEntryLayoutWidth = entryLayoutWidth;
            }
            entryFlights = f.huiguangEntry;
        }

        LyricGlyphs out = new LyricGlyphs(seed, lines.size() * LINE_H);
        out.huiguangElapsedMs = elapsedMs;
        float orbitPhase = seededJitter(0, 0L, 73, seed) * 2f * (float) Math.PI;
        if (huiguang) {
            out.huiguangLayout = huiguangLayout;
            out.huiguangLayoutScales = huiguangLayoutScales;
            out.huiguangBursts = f.huiguangBursts;
            out.huiguangKeywordRgbs = keywordMatch;
            // 行级绕行对整句所有字同值 —— 每句算一次：填 scratch（渲染层径迹用）
            // 并同值落 driftX/driftY（一次求值，删掉重复的第二遍调用）
            float[] orbPre = HuiguangMotion.lineOrbit(elapsedMs / 1000f, LYRIC_SIZE, orbitPhase);
            LINE_ORBIT_SCRATCH[0] = orbPre[0];
            LINE_ORBIT_SCRATCH[1] = orbPre[1];
            // 本句恒定绕行（folia 永不停止的小圆漂移）：渲染层把径迹点也加上它，
            // 拖尾才能一直连着正在漂移的字
            out.huiguangDriftX = orbPre[0];
            out.huiguangDriftY = orbPre[1];
            // 行级呼吸缩放 + 摇摆旋转（folia windowGeometry 整行脉动，一比一）
            float t = elapsedMs / 1000f;
            out.huiguangLineScale = 1f + 0.025f * (float) Math.sin(t * 0.43f + orbitPhase);
            out.huiguangLineSwayDeg = (float) Math.toDegrees(
                    0.03f * (float) Math.sin(t * 0.23f + orbitPhase * 0.7f));
        }
        float y = -(lines.size() * LINE_H) / 2f;
        int k = 0;      // 字形下标（含空白）
        int n = 0;      // 非空白字符序号（仅 flashMode 推进 —— 与既有语义一致）
        int hwN = 0;    // 绘光自己的非空白计数器（飞行定义按它索引；见 huiguang 分支）
        for (String line : lines) {
            float w = pixelWidthOf.widthOf(line);
            float x = -w / 2f;
            float rowLineLeftX = x;   // 行内原排版的最左 x（心象块内偏移的基准）
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (!Character.isWhitespace(c)) {
                    // ---- 入场相位 ----
                    float appearAt;
                    if (flashMode) {
                        appearAt = f.charTimes[Math.min(n, f.charTimes.length - 1)];
                        n++;
                    } else {
                        appearAt = (float) ((double) k / total * f.revealMs);
                    }
                    // ±20ms 确定性抖动：同句群像错落（发光层与本层同帧取同值）
                    appearAt += seededJitter(k, 0L, 7, seed) * 20f;

                    if (huiguang) {
                        // ---- 绘光模式：换句飞行重排 + 卡拉OK点亮 + 冷暖双色 ----
                        // 预摆期（尚未开唱）文字隐藏：槽位静默占位，等转正后
                        // 字才从预摆位飞出（出场即特效，不提前露字）
                        if (!f.huiguangPresetDone && f.huiguangOwnPresetGeo != null && elapsedMs < 0f) {
                            x += pixelWidthOf.widthOf(String.valueOf(c));
                            k++;
                            continue;
                        }
                        // 飞行定义按「非空白字符序」对齐（构建侧 projectFromPrev/flight
                        // 都以非空白序编号）。消费侧必须用自己的计数器 hwN：n 只在
                        // flashMode 下推进，普通 LRC 歌词（无逐字轴）里 n 恒 0，
                        // 用 n 索引会让所有字挤在同一条飞行曲线上。
                        HuiguangMotion.Flight fl = entryFlights != null && entryFlights.length > hwN
                                ? entryFlights[hwN] : null;
                        // 本句排版的落位：layout 数组与飞行终点同源（含词级字号缩放）
                        float layoutX = huiguangLayout != null && huiguangLayout.length > hwN * 4
                                ? huiguangLayout[hwN * 4] : x;
                        float layoutY = huiguangLayout != null && huiguangLayout.length > hwN * 4
                                ? huiguangLayout[hwN * 4 + 1] : y;
                        float layoutRot = huiguangLayout != null && huiguangLayout.length > hwN * 4
                                ? huiguangLayout[hwN * 4 + 2] : 0f;
                        float layoutScale = huiguangLayout != null && huiguangLayout.length > hwN * 4
                                ? huiguangLayout[hwN * 4 + 3] : 1f;
                        hwN++;
                        // 存活门与渲染层 trailAlive 对齐（ENTRY + TRACK_TIME）：窗口后的
                        // 450ms 收拢期里字位已钉在终点（poseAt 钳到 s=1），但径迹还在
                        // 按强度自然衰减滑出 —— flights 若在此刻置 null，晚落地的字
                        // 会带着可观强度整条硬切消失（视觉 pop），收拢语义就落空了。
                        // poseAt 超窗恒返回终点，落位姿态不受影响。
                        boolean flying = fl != null && fl.valid
                                && elapsedMs <= HuiguangMotion.ENTRY_WINDOW_MS
                                        + HuiguangMotion.TRACK_TIME_S * 1000f;
                        float gx = layoutX;
                        float gy = layoutY;
                        float rot = layoutRot;
                        float scale = 1f;
                        if (flying) {
                            // 逐字贝塞尔飞行（含甩出弧线与途中自旋）—— folia poseAt 同构。
                            // 曲线全程是「字心」口径（构建侧起止点都取字心），而 g.x/g.y
                            // 与姿态四量 dx/dy 是「字顶」口径 —— 落位时把字心换回字顶。
                            float[] ps = HUIGUANG_POSE;
                            HuiguangMotion.poseAt(fl, elapsedMs, HuiguangMotion.ENTRY_WINDOW_MS, ps);
                            gx = ps[0] - pixelWidthOf.widthOf(String.valueOf(c)) * layoutScale * 0.5f;
                            gy = ps[1] - LYRIC_SIZE * 0.5f;
                            rot = layoutRot + (float) Math.toDegrees(ps[2]);
                            scale = (1f - HuiguangMotion.FLIGHT_SHRINK * ps[3]) * layoutScale;
                        } else {
                            scale = layoutScale;
                        }
                        // ---- 永不停止的微动（folia 漂移/绕行/呼吸/崩解的 3D 版）----
                        float elapsedSec = elapsedMs / 1000f;
                        float breathPhase = fl != null && fl.valid
                                ? fl.wobblePhase
                                : seededJitter(k, 0L, 79, seed) * (float) Math.PI;
                        float[] orbit = LINE_ORBIT_SCRATCH;
                        float[] breath = BREATH_SCRATCH;
                        HuiguangMotion.glyphBreathInto(elapsedSec, LYRIC_SIZE, breathPhase, breath);
                        gx += orbit[0] + breath[0];
                        gy += orbit[1] + breath[1];
                        // 崩解：点亮 delay 秒后沿各自方向缓慢漂离（多数向上）、
                        // 可见自转（folia rotation += spin×amount×0.18 的度数口径）
                        // 逐字速度倍率（folia drift.speed 0.6~1.5）——散开有快有慢
                        float litAgeSec = elapsedSec - (appearAt + seededJitter(k, 0L, 65, seed)
                                * HuiguangMotion.DECAY_DELAY_MAX_S * 1000f) / 1000f;
                        float decaySpeed = 0.6f + (seededJitter(k, 0L, 69, seed) * 0.5f + 0.5f) * 0.9f;
                        float decay = HuiguangMotion.decayAmount(litAgeSec) * decaySpeed;
                        if (decay > 0.001f) {
                            float ang = -(float) Math.PI / 2f + seededJitter(k, 0L, 63, seed) * 1.1f;
                            gx += (float) Math.cos(ang) * decay * LYRIC_SIZE;
                            gy += (float) Math.sin(ang) * decay * LYRIC_SIZE;
                            rot += (float) Math.toDegrees(
                                    seededJitter(k, 0L, 67, seed) * decay * 0.18f);
                        }
                        // 行级呼吸缩放/摇摆：整句统一脉动（folia 整行脉动一比一）
                        scale *= out.huiguangLineScale;
                        rot += out.huiguangLineSwayDeg;
                        // 点亮：卡拉OK渐变（无逐字轴时按均分时刻同款渐变）；
                        // 未唱 = 冷色半亮（渲染层走暗色烘焙档），已唱 = 暖白满亮。
                        // litAt 取「本字」的下标：flashMode 时 n 已在上方自增过，回退 1。
                        float lit = HuiguangMotion.litAt(f.charTimes, Math.max(0, n - 1),
                                flashMode, elapsedMs, appearAt);
                        // 四芒闪点包络（folia flashEnvelope）：点亮瞬间爆一下再指数衰减。
                        // 出场增强：转正句的逐字飞行落位也爆一下（先唱到才点亮 ——
                        // 落位闪与点亮闪共用包络，取较大者，出场更有节奏感）
                        float flash = HuiguangMotion.flashEnvelope(elapsedMs - appearAt);
                        if (fl != null && fl.valid) {
                            float landingAt = (fl.delay + fl.duration) * HuiguangMotion.ENTRY_WINDOW_MS;
                            float landingAge = elapsedMs - landingAt;
                            if (landingAge > 0f && elapsedMs < appearAt) {
                                float landingFlash = HuiguangMotion.flashEnvelope(landingAge);
                                flash = Math.max(flash, landingFlash * 0.8f);
                            }
                        }
                        // 预摆句（尚未开唱，elapsed<0）：整句以满亮整齐停在预摆位
                        //（folia 邻行 0.9 档不散花），等唱到时才起飞重排
                        float enterFade;
                        if (elapsedMs < 0f) {
                            enterFade = 1f;
                        } else {
                            enterFade = Math.max(HuiguangMotion.UNLIT_ALPHA,
                                    HuiguangMotion.smooth01((elapsedMs - appearAt) / CHAR_FADE_MS + 0.35f));
                        }
                        // 飞行加亮（folia revealed += flying×0.4）：字在空中比落定后亮
                        float flyingGlow = flying ? HUIGUANG_POSE[3] * 0.45f : 0f;
                        float a = Math.max(enterFade, flyingGlow) * alphaMul;
                        if (a > 0.02f) {
                            LyricGlyph g = new LyricGlyph(c, gx, gy, a, k, scale);
                            g.rot = rot;
                            g.pop = scale;
                            g.huiguangLit = lit;
                            g.huiguangFlash = flash;
                            g.flyingWhite = flying && flyingGlow > 0.15f;
                            g.keywordRgb = keywordMatch != null && n - 1 >= 0
                                    && n - 1 < keywordMatch.length ? keywordMatch[n - 1] : 0;
                            out.glyphs.add(g);
                            out.appearAts.add(appearAt);
                            out.huiguangFlights.add(flying ? fl : null);
                        }
                        x += pixelWidthOf.widthOf(String.valueOf(c));
                        k++;
                        continue;
                    }

                    if (cadenza) {
                        // ---- 心象模式：块姿态 + 词云落点 + 镜头位移 ----
                        CadenzaMotion.Token token = currentToken(cadenzaTokens, cadenzaIndex, n, k);
                        if (token != null) {
                            CadenzaMotion.Pose pose = new CadenzaMotion.Pose();
                            CadenzaMotion.pose(token, pose, elapsedMs, alphaMul, exiting, LYRIC_SIZE);
                            float a = (float) (pose.enterAlpha * (1.0 - pose.exitAlpha)) * alphaMul;
                            if (a > 0.02f) {
                                // 字的基线位置 = 块词云落点（舞台坐标，中心原点）
                                //   + 块内字符横向偏移（本字 x − 块首字 x，与简洁模式
                                //     同一条逐字推进）。镜头不跟随（公告牌整体不动）。
                                float inBlockX = x - rowLineLeftX;
                                float gx = (float) (token.homeX - token.width * .5)
                                        + inBlockX;
                                // 纵向 = 行基线 y + 块落点的纵向散布（舞台坐标）。
                                float gy = y + (float) (token.homeY);
                                LyricGlyph g = new LyricGlyph(c, gx, gy, a, k,
                                        (float) pose.scale);
                                g.rot = (float) pose.rotation;
                                g.dx = (float) pose.dx;
                                g.dy = (float) pose.dy;
                                g.tokenRgb = CadenzaMotion.tokenColor(token,
                                        heroIndexOf(cadenzaTokens), line.hashCode(), TEXT_RGB);
                                g.cadenzaGlow = (float) CadenzaMotion.glowPulse(token, elapsedMs);
                                g.tokenActive = elapsedMs >= token.startMs
                                        && elapsedMs <= token.endMs;
                                out.glyphs.add(g);
                                out.appearAts.add(appearAt);
                            }
                        }
                        x += pixelWidthOf.widthOf(String.valueOf(c));
                        k++;
                        continue;
                    }

                    float enterT = (elapsedMs - appearAt) / ENTER_MS;

                    if (enterT > 0f) {
                        // ---- 入场曲线 ----
                        float landed = (float) clamp01(enterT);                       // 0..1 时间进度
                        float back = easeOutBack(landed);                             // 过冲再回落的位置/缩放曲线
                        float fadeT = (float) clamp01(enterT / 0.6f);                 // alpha 用前 60% 时间
                        float fade = fadeT * fadeT * (3f - 2f * fadeT);               // smoothstep

                        // 位置：从下 8px 落位（ease-out-back 过冲 2px 再回来）
                        float gy = y + (1f - back) * ENTER_LIFT_PX;
                        // 缩放：0.6 → 过冲 1.06 → 1.0
                        float scale = 0.6f + 0.4f * back;
                        // word pop（有逐字轴时）：入场完成后由唱到时刻的回弹接管。
                        // 弹跳仍归 wordPop 开关门控（与逐字点亮解耦 —— 那是有轴
                        // 即启用；这里尊重用户的弹跳开关，关了就不弹）
                        if (flashMode && wordPop) {
                            float popT = (elapsedMs - appearAt) / 160f;
                            float back01 = (float) clamp01(1f - popT);
                            scale *= 1f + 0.55f * back01 * back01;
                        }
                        float a = fade * alphaMul;
                        if (a > 0.02f) {
                            out.glyphs.add(new LyricGlyph(c, x, gy, a, k, scale));
                            out.appearAts.add(appearAt);
                        }
                    }
                }
                x += pixelWidthOf.widthOf(String.valueOf(c));
                k++;
            }
            y += LINE_H;
        }

        // ---- 出场级联：行 alpha 已经是统一淡出系数（alphaMul），
        //      这里按「真实出现序」把退场提前量摊进 EXIT_STAGGER_MS —— 先唱的字先走。
        //      出现序不是排版序 k：逐字歌词（flashMode）的时间戳可能非单调（副歌重叠），
        //      级联必须按每字自己的 appearAt 归一，否则退场顺序和演唱顺序会拧着。
        //      心象模式不走级联（块自己的退场动画接手）。
        if (!cadenza && !lingerFade && exiting && alphaMul < 1f && !out.glyphs.isEmpty() && !out.appearAts.isEmpty()) {
            float first = out.appearAts.get(0);
            float last = out.appearAts.get(out.appearAts.size() - 1);
            float span = Math.max(1f, last - first);
            for (int i = 0; i < out.glyphs.size(); i++) {
                LyricGlyph g = out.glyphs.get(i);
                // 出现序归一 0..1 → 级联延迟 0..EXIT_STAGGER_MS
                float stagger = (out.appearAts.get(i) - first) / span * EXIT_STAGGER_MS;
                float exitT = (1f - alphaMul) * (FADE_SECONDS * 1000f) - stagger;
                if (exitT > 0f) {
                    float exitFade = (float) clamp01(1f - exitT / (FADE_SECONDS * 1000f * 0.75f));
                    g.alpha *= exitFade * exitFade * (3f - 2f * exitFade);
                    // 退场缩小：向 EXIT_SCALE_MIN 收拢（乘在 pop 上，宿主同一入口）
                    float exitShrink = EXIT_SCALE_MIN + (1f - EXIT_SCALE_MIN) * exitFade;
                    g.pop *= exitShrink;
                }
            }
        }
        return out;
    }

    /**
     * 绘光入场起飞点：上一句第 n 个非空白字符的字心，经两步投影落到本句平面 ——
     * 上一句平面坐标 → 世界（prevGeo 正变换）→ 本句平面（ownGeo 逆变换）。
     * 任何一侧退化（scale 过小）返回 null，调用方退回落位入场。
     */
    /** 上一句排版（prevLayout）由调用方 hoist 出逐字循环 —— 本方法只做投影。 */
    private static float[] projectFromPrev(SpawnedLine f, int n, PixelWidthOf widthOf,
                                           float[] pl) {
        WorldLyricsState.BillboardSnapshot prev = f.huiguangPrevGeo;
        WorldLyricsState.BillboardSnapshot own = f.huiguangOwnGeo;
        if (prev == null || own == null || f.huiguangPrev == null) return null;
        // 上一句的第 n 个非空白字符落位（与构建侧同轴；pl 已由调用方备好）
        int prevCount = pl.length / 4;
        float cx, cy;
        if (n < prevCount) {
            // 字心 = 字顶 + 半宽×倍率/半高（与构建侧飞行终点同一口径，词缩放同源）
            char c = nonWsCharAt(f.huiguangPrev.lines, n);
            cx = pl[n * 4] + widthOf.widthOf(String.valueOf(c)) * pl[n * 4 + 3] * 0.5f;
            cy = pl[n * 4 + 1] + LYRIC_SIZE * 0.5f;
        } else {
            // 上一句比本句短：取上一句整面的随机散点（字面中心 ± 行高），仍成「从上一句飞来」
            cx = 0f;
            cy = (seededJitter(n, 0L, 43, f.huiguangPrev.lines.hashCode()) * 0.5f)
                    * Math.max(2, f.huiguangPrev.lines.size()) * LINE_H;
        }
        double[] world = prev.planePointToWorld(cx, cy);
        float[] local = HuiguangMotion.projectToPlane(world[0], world[1], world[2],
                own.x, own.y, own.z, own.yawDeg, own.pitchDeg, own.scale);
        if (local == null) return null;
        // 两牌相距很远时起飞点会落到本句平面极远处：按比例把偏移缩到半径 260px 内
        //（保持方向、等比缩距 —— clamp 逐轴夹取会改变方向让相邻字的起飞点挤在同一条
        // 边线上，等比缩放则整组起飞点形状不变，只是整体变近）。
        float lim = 260f;
        float d2 = local[0] * local[0] + local[1] * local[1];
        if (d2 > lim * lim) {
            float k = lim / (float) Math.sqrt(d2);
            local[0] *= k;
            local[1] *= k;
        }
        return local;
    }

    /** 句文本里第 n 个非空白字符（n 越界返回 0，调用方保证 n < 非空白总数）。 */
    private static char nonWsCharAt(List<String> lines, int n) {
        int idx = 0;
        for (String line : lines) {
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (!Character.isWhitespace(c)) {
                    if (idx == n) return c;
                    idx++;
                }
            }
        }
        return '\0';
    }

    /**
     * 心象块查找：给定非空白字符序号 n（渲染流中的第 n 个非空白字符），返回它所属的块。
     * 分块按行顺序排列，charOffset/charCount 是块在非空白字符流里的位置 —— 用 n 对齐。
     */
    private static CadenzaMotion.Token currentToken(List<CadenzaMotion.Token> tokens,
                                                    int cadenzaIndex, int n, int k) {
        if (tokens == null || tokens.isEmpty()) return null;
        // 线性推进：buildGlyphs 每帧按 k 升序遍历，从上次命中下标起扫即可 O(1)。
        for (int i = cadenzaIndex; i < tokens.size(); i++) {
            CadenzaMotion.Token t = tokens.get(i);
            if (n >= t.charOffset && n < t.charOffset + t.charCount) return t;
            if (n < t.charOffset) return tokens.get(Math.max(0, i - 1));
        }
        return tokens.get(tokens.size() - 1);
    }

    /** 把块的非空白偏移平移到整句字符流（多行 wrap 时逐行累加行长）。 */
    private static CadenzaMotion.Token shiftToken(CadenzaMotion.Token t, int nonWsShift) {
        if (nonWsShift == 0) return t;
        return new CadenzaMotion.Token(t.text, t.startMs, t.endMs, t.hero,
                t.enter, t.exit, t.direction, t.angle,
                t.charOffset + nonWsShift, t.charCount);
    }

    /** 心象 hero 块下标（渲染层配色用同一函数，保持一致）。 */
    private static int heroIndexOf(List<CadenzaMotion.Token> tokens) {
        if (tokens == null) return 0;
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).hero) return i;
        }
        return 0;
    }

    /** 宿主量像素宽的回调（MC 字体或纹理缓存的度量）。 */
    public interface PixelWidthOf {
        float widthOf(String s);
    }

    // ==================== seededJitter（入场动画相位用） ====================

    /**
     * 由 (字序号, 第几帧, 种类, 种子) 得到一个固定的 -1..1 随机数
     * （确定性哈希）：同一帧里取同一个值。
     */
    public static float seededJitter(int k, long frame, int salt, int seed) {
        int h = k * 73856093 ^ (int) (frame * 19349663L) ^ (salt * 83492791) ^ seed;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return (h & 0xFFFF) / 32767.5f - 1f;
    }

    // ==================== 入口 ====================


    /** 拆句开关生效后的行列表，按歌词版本缓存。 */
    private List<LyricLine> cachedSplitLines;
    private long cachedSplitRevision = -1;

    static List<LyricLine> effectiveLines(boolean splitLines) {
        List<LyricLine> raw = CloudMusic.lyrics;
        if (raw == null || raw.isEmpty()) return raw;
        if (!splitLines) return filteredLines(raw);
        WorldLyricsState s = INSTANCE;
        long revision = CloudMusic.lyricsRevision;
        if (s.cachedSplitLines != null && s.cachedSplitRevision == revision) {
            return s.cachedSplitLines;
        }
        List<LyricLine> out = new ArrayList<>(raw.size());
        for (LyricLine l : raw) {
            if (l == null) continue;
            out.addAll(SplitLines.split(l, true));
        }
        s.cachedSplitLines = out;
        s.cachedSplitRevision = revision;
        return filteredLines(out);
    }

    // ==================== 商业元数据行过滤（歌曲开头的制作人员表等） ====================

    /**
     * 冒号锚定的制作人员表行：一行以「作词：」「作曲：」「Lyricist:」这类
     * <b>职务词开头且紧跟冒号</b>的，无论挂载多久、行长多少都直接剔除。
     * 这是过滤的<b>第一层</b>——无条件、零误伤：真实歌词行几乎不会以
     * 「作词：」开头，因此不需要时长/行长这些保守条件。行首锚定 + 必须跟
     * 冒号是双保险：句中出现「回：「作曲」」或唱到「吉他」这类词都不命中。
     */
    private static final Pattern CREDIT_LINE = Pattern.compile(
            "^\\s*(作词|作詞|作曲|编曲|編曲|填詞|填词|制作人|製作人|混音|母带|母帶|監製|监制"
                    + "|和声|和聲|吉他|贝斯|貝斯|键盘|鍵盤|鼓手|录音师|錄音師|作词人|作詞人|作词|作詞|作曲"
                    + "|编曲|編曲|填词|填詞|鼓|弦乐|弦樂|配唱|录音|錄音|录混|錄混"
                    + "|录音棚|錄音棚|混音师|混音師|母带师|母帶師|企划|企劃|出品|发行|發行|版权|版權"
                    + "|独家|獨家|OP|SP|Lyricist|Composer|Producer|Arranger|Mixing|Mastering)"
                    + "\\s*[:：]",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
                    | Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * 判定一行是不是「冒号锚定的制作人员表」（如「作词：某某」）。
     * 纯函数：只看行文本本身，与挂载时长无关 —— 供 filteredLines 第一层与测试用。
     */
    static boolean isCreditLine(String text) {
        if (text == null || text.isEmpty()) return false;
        return CREDIT_LINE.matcher(text).find();
    }

    /**
     * 纯音乐占位行：没填词的歌会被平台塞进「纯音乐，请欣赏」这类恒在的提示行
     * （与快闪的商业行不同 —— 它从头挂到尾，永远占据「当前行」）。判据是
     * <b>去掉全部非字母数字字符并归一小写后</b>恰好等于这些固定口吻之一：
     * 加了标点/空格/全半角变体的提示词也能命中，而「纯音乐的电池」这类碰巧
     * 含词的真实歌名不会。
     */
    static boolean isPlaceholderLine(String text) {
        if (text == null) return false;
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        if (sb.length() == 0) return false;
        String t = sb.toString();
        return t.equals("纯音乐") || t.equals("纯音乐请欣赏") || t.equals("纯音乐请您欣赏")
                || t.equals("没有填词的纯音乐") || t.equals("instrumental")
                || t.equals("没有填词的纯音乐") || t.equals("instrumental");
    }

    /**
     * 常见商业附加信息行（「xxx平台独家」「作词/作曲/编曲：…」「出品人/监制…」
     * 一整张制作人员表）—— 通常挤在歌曲开头 0.1~0.5s 一行地快速闪过。绘光模式
     * 下每行都是一次「整句字形烘焙风暴 + 飞入重排」，连续快闪就是连续掉帧尖峰，
     * 且观感是满屏字影乱闪。
     *
     * <p>特征识别（宁缺毋滥：三个条件都满足才过滤）：① 命中制作人员/平台运营的
     * 关键词表；② 行极短（≤30 字，正常歌词行很少整行都是职务表）；③ 该行挂载
     * 时长极短（&lt;1.2s，按下一行时间戳差算 —— 商业行总是一扫而过）。纯歌词行
     * 即使偶尔含「回：「作曲」」这类字眼，靠 ②③ 双保险不会误伤。这是过滤的
     * <b>第二层</b>：专抓「xx平台独家」这类<b>无冒号</b>的声明行 —— 冒号锚定的
     * 职务表已由 {@link #CREDIT_LINE} 第一层无条件处理。</p>
     */
    static final String[] METADATA_KEYWORDS = {
            "作词", "作詞", "作曲", "编曲", "編曲", "填詞", "填词",
            "出品", "发行", "發行", "企划", "企劃", "监制", "監製", "制作人", "製作人",
            "混音", "母带", "母帶", "录音", "錄音", "和声", "和聲", "吉他", "贝斯",
            "键盘", "弦乐", "弦樂", "配唱", "录音棚", "錄音棚", "混音师", "母带师",
            "独家", "獨家", "版权", "版權", "未经许可", "未經許可", "禁止翻唱",
            "音乐平台", "音樂平台", "云音乐", "雲音樂", "官方渠道", "正版授权", "正版的音樂",
    };

    /** 判定一行是不是「快速闪过的商业附加信息」。lineIndex 用于取下一行时间戳。 */
    static boolean isFlashMetaLine(List<LyricLine> lines, int lineIndex) {
        LyricLine l = lines.get(lineIndex);
        if (l == null || l.lyric == null) return false;
        String text = l.lyric;
        if (text.isEmpty() || text.length() > 30) return false;
        boolean hit = false;
        for (String kw : METADATA_KEYWORDS) {
            if (text.contains(kw)) {
                hit = true;
                break;
            }
        }
        if (!hit) return false;
        long next = nextLineStartMs(lines, lineIndex);
        long dur = Math.max(0L, next - l.timestamp);
        return dur < 1200L;
    }

    /**
     * 过滤后的行表（带 revision 缓存 —— 与 split 缓存同惯例）。两级过滤：
     * 第一层 {@link #isCreditLine}/{@link #isPlaceholderLine} 只看行文本、
     * 无条件剔除（制作人员表的冒号锚定行 / 纯音乐恒在占位行）；第二层
     * {@link #isFlashMetaLine} 带时长条件（开头快闪的无冒号商业声明行）。
     * 过滤只发生在「开头一串快闪商业行 / 纯音乐占位」的场景，正常歌曲零改动。
     */
    static List<LyricLine> filteredLines(List<LyricLine> raw) {
        WorldLyricsState s = INSTANCE;
        long revision = CloudMusic.lyricsRevision;
        if (s.cachedFilteredLines != null && s.cachedFilteredRevision == revision) {
            return s.cachedFilteredLines;
        }
        List<LyricLine> out = null;
        for (int i = 0; i < raw.size(); i++) {
            LyricLine l = raw.get(i);
            if (l == null) continue;
            if (isCreditLine(l.lyric) || isPlaceholderLine(l.lyric)) {
                if (out == null) {
                    out = new ArrayList<>(raw.size());
                    for (int j = 0; j < i; j++) out.add(raw.get(j));
                }
                continue;
            }
            if (isFlashMetaLine(raw, i)) {
                if (out == null) {
                    out = new ArrayList<>(raw.size());
                    for (int j = 0; j < i; j++) out.add(raw.get(j));
                }
                continue;
            }
            if (out != null) out.add(l);
        }
        List<LyricLine> result = out != null ? out : raw;
        s.cachedFilteredLines = result;
        s.cachedFilteredRevision = revision;
        return result;
    }

    private List<LyricLine> cachedFilteredLines;
    private long cachedFilteredRevision = -1;

    private WorldLyricsState() {
    }

    /** 单例：3D 歌词是全局唯一的常驻渲染。 */
    private static final WorldLyricsState INSTANCE = new WorldLyricsState();

    /** 诊断用（TRACE）：当前句的世界锚点；无活动句返回空串。 */
    public static String activeLineDebugPos() {
        SpawnedLine a = INSTANCE.active;
        return a == null ? "" : (a.x + "," + a.y + "," + a.z);
    }

    public static WorldLyricsState get() {
        return INSTANCE;
    }

    /** 歌词不可用时整体隐藏（无词、无歌）。供渲染层快速短路。 */
    public static boolean hasLyrics() {
        return CloudMusic.lyrics != null && !CloudMusic.lyrics.isEmpty()
                && CloudMusic.lyricsMatchCurrentTrack();
    }

    /** 供测试：重置全部帧间状态。 */
    void resetForTest() {
        active = null;
        preset = null;
        fading.clear();
        currentKey = null;
        lastSeenNanos = 0;
        lastIndex = -1;
        lastSpawnedIdx = -1;
        lastPlayingNanos = 0;
        wasPaused = false;
        lastFramePosMs = Long.MIN_VALUE;
        cachedSplitLines = null;
        cachedSplitRevision = -1;
        cachedFilteredLines = null;
        cachedFilteredRevision = -1;
    }
}
