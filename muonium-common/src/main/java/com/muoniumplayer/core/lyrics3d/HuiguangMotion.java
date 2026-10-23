package com.muoniumplayer.core.lyrics3d;

/**
 * 「绘光」动效数学 —— 照 本项目 lumiere（MIT）文字子系统的纯函数核心移植：
 * 换句时整行字从上一句的位置沿各自的三次贝塞尔曲线飞来重新排列组合，
 * 飞行途中拖出云室粒子式的径迹（回溯重算，非历史缓冲），唱到的字按
 * 卡拉OK式连续渐变点亮（未唱时冷色半亮）。
 *
 * <p>与 lumiere 的对应关系（引用为 folia 仓库 lumiere/ 下的文件）：</p>
 * <ul>
 *   <li>逐字飞行曲线 —— windowLines.ts（控制点甩出 1.6~4.8 / 回卷 1.0~3.6 字号，
 *       第二控制点转半圈 ±0.4rad；delay+duration ≤ 1 保证窗口结束前全部到位）；</li>
 *   <li>径迹 —— lyricWindow.ts drawTracks（过去 TRACK_TIME 秒均匀 21 个采样时刻
 *       重算字的位置连成折线；路径 3 个字号长饱和亮度；位移不足不留痕；
 *       2% 字号的确定性正弦垂直抖动）；</li>
 *   <li>点亮 —— reveal.ts + lyricWindow.ts（卡拉OK渐变最短 LIGHT_UP 毫秒，
 *       未唱字半亮冷色、飞行加亮）；</li>
 *   <li>坐标系换算 —— windowGeometry.ts toWorld 的逆：世界点投回公告牌平面
 *       （本仓库的公告牌 = 一句歌词，换句交接的「上一句位置」用它求得）。</li>
 * </ul>
 *
 * <p>全部无状态纯函数（Flight 的字段是构建期算好的常量），同一输入永远同一输出
 * —— Seek/暂停/回拖免费正确，与 {@link CadenzaMotion} 同一设计纪律。
 * 本类只在渲染线程调用（内部 scratch 数组不线程安全）。</p>
 */
public final class HuiguangMotion {

    private HuiguangMotion() {
    }

    // ==================== 时间常量（folia windowTiming.ts 的本仓库换算） ====================

    /** 逐字飞行窗口：一次换句的飞行总时长（folia SLIDE=1.5s，本仓库换句无提前量，收紧到 1s）。 */
    public static final float ENTRY_WINDOW_MS = 700f;
    /** 径迹回看窗口（folia TRACK_TIME=0.45s）：也是飞行结束后径迹收拢的等待期。 */
    public static final float TRACK_TIME_S = 0.45f;
    /** 径迹采样数（folia TRACK_SAMPLES=20 → 21 个点）。 */
    public static final int TRACK_SAMPLES = 20;
    /** 卡拉OK点亮的最短渐变（folia LIGHT_UP=0.3s）：字再短也不跳变。 */
    public static final float LIGHT_UP_MS = 300f;
    /** 点亮逐字渐变兜底窗口：无下一字时刻可取时的点亮时长。 */
    private static final float LIT_FALLBACK_MS = 400f;

    // ==================== 观感常量（folia lyricWindow.ts 的数值） ====================

    /** 径迹基础不透明度（folia 0.55），渲染层再乘 strength 与字形 alpha。 */
    public static final float TRAIL_ALPHA = 0.55f;
    /** 径迹亮度饱和：路径长达到几个字号后不再变亮（folia heroPx×3）。 */
    public static final float TRAIL_SATURATION_FONTS = 3f;
    /** 径迹最小强度：路径位移不足（≈0.15 字号）不留痕（folia strength>0.05）。 */
    public static final float TRAIL_MIN_STRENGTH = 0.05f;
    /** 径迹垂直抖动幅度（字号比例，folia 0.02；y 折减 0.6 —— 云室粒子的斜向雾感）。 */
    public static final float TRAIL_WOBBLE = 0.02f;
    /** 径迹线宽（设计像素；folia 1.2 逻辑px / 40px 字号 ≈ 3% 字高，本仓库字号 10px 取一半）。 */
    public static final float TRAIL_WIDTH_PX = 0.32f;
    /** 飞行中字的缩小量（folia pulse = 1 − 0.28×flying）。 */
    public static final float FLIGHT_SHRINK = 0.28f;
    /** 飞行自旋上限（folia ±1.2 rad）。 */
    public static final float SPIN_MAX_RAD = 1.2f;
    /** 未唱字的不透明度（folia unlitOpacity 0.22；本仓库背景是世界不是暗场，略提）。 */
    public static final float UNLIT_ALPHA = 0.3f;
    /** 未唱字的亮度乘数（渲染层据此选烘焙色档；暗档发光同样压到 0.35 档）。 */
    public static final float UNLIT_DIM = 0.45f;

    // ==================== 飞行定义与求值 ====================

    /**
     * 单字的飞行定义：起止点 + 三次贝塞尔控制点 + 归一化时序。
     * 构建期一次算定，之后每帧对任意时刻求值（纯函数）。
     */
    public static final class Flight {
        public float fromX, fromY;
        public float toX, toY;
        public float c1x, c1y, c2x, c2y;
        /** 归一化到飞行窗口的起播延迟与时长（delay+duration ≤ 1，folia 同约束）。 */
        public float delay, duration;
        /** 飞行途中自旋（rad，乘 flying 包络）。 */
        public float spin;
        /** 径迹垂直抖动的每字固定相位。 */
        public float wobblePhase;
        /** 出场飞行用 easeIn（加速离场）；入场用 easeInOutCubic（folia）。 */
        public boolean exitEase;
        public boolean valid;
    }

    /**
     * 构建一次飞行的定义（folia windowLines.ts 的控制点抽取，确定性随机来自
     * {@link WorldLyricsState#hashRand}）：同一 (k, seed) 永远同一曲线。
     *
     * @param k       字形下标（含空白，与 buildGlyphs 同轴）
     * @param seed    句种子（行文本 hashCode）
     * @param fontPx  字号（控制点模长的单位）
     * @param exitEase true = 出场飞行（easeIn 加速离场）
     */
    public static Flight flight(int k, int seed,
                                float fromX, float fromY, float toX, float toY,
                                float fontPx, boolean exitEase) {
        Flight f = new Flight();
        f.fromX = fromX; f.fromY = fromY;
        f.toX = toX; f.toY = toY;
        // folia windowLines.ts：a1 随机方向甩出，模长 1.6~4.8 字号；
        // c2 相对终点、方向转过约半圈（±0.4rad 抖动）→ 弧线或 S 形打卷，模长 1.0~3.6 字号。
        float a1 = WorldLyricsState.hashRand(k, 0L, 11, seed) * (float) Math.PI;
        float n1 = WorldLyricsState.hashRand(k, 0L, 13, seed) * 0.5f + 0.5f;
        float m1 = fontPx * (1.6f + n1 * 3.2f);
        float half = WorldLyricsState.hashRand(k, 0L, 17, seed) < 0f ? 0.5f : 1.5f;
        float a2 = a1 + (float) Math.PI * half + WorldLyricsState.hashRand(k, 0L, 19, seed) * 0.4f;
        float n2 = WorldLyricsState.hashRand(k, 0L, 23, seed) * 0.5f + 0.5f;
        float m2 = fontPx * (1.0f + n2 * 2.6f);
        f.c1x = fromX + (float) Math.cos(a1) * m1;
        f.c1y = fromY + (float) Math.sin(a1) * m1;
        f.c2x = toX + (float) Math.cos(a2) * m2;
        f.c2y = toY + (float) Math.sin(a2) * m2;
        // 时序：duration 0.45~0.85（归一化），delay 吃掉剩余量 —— 窗口结束前全部到位。
        float d = 0.45f + (WorldLyricsState.hashRand(k, 0L, 29, seed) * 0.5f + 0.5f) * 0.4f;
        f.duration = d;
        // delay 分布偏前（×0.6）：本仓库换句从行起点起飞（无 folia 的 −1.2s 预摆），
        // 让大部分字在首个点亮时刻前落位，削弱「边飞边亮」的持续闹感（差距分析 D7）
        f.delay = (WorldLyricsState.hashRand(k, 0L, 31, seed) * 0.5f + 0.5f) * (1f - d) * 0.6f;
        f.spin = WorldLyricsState.hashRand(k, 0L, 37, seed) * SPIN_MAX_RAD;
        f.wobblePhase = WorldLyricsState.hashRand(k, 0L, 41, seed) * (float) Math.PI;
        f.exitEase = exitEase;
        f.valid = true;
        return f;
    }

    /**
     * 对任意时刻求飞行姿态。out[0]=x, out[1]=y, out[2]=rot(rad), out[3]=flying(0..1)。
     * elapsed 超过窗口后钉在终点、flying=0（folia 的换位进度 clamp 语义）。
     */
    public static void poseAt(Flight f, float elapsedMs, float windowMs, float[] out) {
        float phase = clamp01(elapsedMs / Math.max(1f, windowMs));
        float s = clamp01((phase - f.delay) / Math.max(1e-4f, f.duration));
        float p = f.exitEase ? s * s * s : easeInOutCubic(s);
        float u = 1f - p;
        // 标准三次贝塞尔 B(s) = (1−s)³P0 + 3(1−s)²s·P1 + 3(1−s)s²·P2 + s³P3
        float bx = u * u * u * f.fromX + 3f * u * u * p * f.c1x + 3f * u * p * p * f.c2x + p * p * p * f.toX;
        float by = u * u * u * f.fromY + 3f * u * u * p * f.c1y + 3f * u * p * p * f.c2y + p * p * p * f.toY;
        float flying = (float) Math.sin(Math.PI * s);
        out[0] = bx;
        out[1] = by;
        out[2] = f.spin * flying;
        out[3] = flying;
    }

    /**
     * 径迹采样（folia drawTracks 的回溯重算）：对过去 {@link #TRACK_TIME_S} 秒内
     * 均匀 {@link #TRACK_SAMPLES}+1 个时刻重算飞行位置，连成折线并叠加云室抖动。
     * 点数恒为 {@link #TRACK_SAMPLES}+1；xs/ys 需至少该容量。
     */
    public static int trailPoints(Flight f, float elapsedMs, float windowMs,
                                  float fontPx, float[] xs, float[] ys) {
        if (f == null || !f.valid) return 0;
        int n = TRACK_SAMPLES;
        for (int j = 0; j <= n; j++) {
            float te = elapsedMs - TRACK_TIME_S * 1000f * (1f - (float) j / n);
            if (te < 0f) te = 0f;
            poseAt(f, te, windowMs, POSE);
            // folia：sin(i·0.9 + 每字相位 + s·6)，幅度 2% 字号，x/y 按 +1/−0.6 斜向分配
            float w = (float) Math.sin(j * 0.9f + f.wobblePhase + te * 0.006f) * fontPx * TRAIL_WOBBLE;
            xs[j] = POSE[0] + w;
            ys[j] = POSE[1] - 0.6f * w;
        }
        return n + 1;
    }

    /** 径迹强度：按无 wobble 的 pose 折线累计长度归一（folia strength = min(1, len/heroPx×3)）。
     *  静止字恒 0（wobble 只用于绘制顶点、不计入强度 —— 否则抖动底噪让已停的字永远过 0.05 门限，
     *  违反 folia「位移不足不留痕」）；飞行结束后回溯窗口滑出飞行段，强度自然衰减归零。 */
    public static float trailPoseStrength(Flight f, float elapsedMs, float windowMs, float fontPx) {
        if (f == null || !f.valid) return 0f;
        float len = 0f;
        float px = 0f, py = 0f;
        for (int j = 0; j <= TRACK_SAMPLES; j++) {
            float te = elapsedMs - TRACK_TIME_S * 1000f * (1f - (float) j / TRACK_SAMPLES);
            if (te < 0f) te = 0f;
            poseAt(f, te, windowMs, POSE);
            if (j > 0) {
                float dx = POSE[0] - px;
                float dy = POSE[1] - py;
                len += (float) Math.sqrt(dx * dx + dy * dy);
            }
            px = POSE[0];
            py = POSE[1];
        }
        return Math.min(1f, len / (fontPx * TRAIL_SATURATION_FONTS));
    }

    /** 径迹强度：路径累计长度归一（含 wobble 的折线版本；强度判定请用 {@link #trailPoseStrength}）。 */
    public static float trailStrength(float[] xs, float[] ys, int count, float fontPx) {
        float len = 0f;
        for (int i = 1; i < count; i++) {
            float dx = xs[i] - xs[i - 1];
            float dy = ys[i] - ys[i - 1];
            len += (float) Math.sqrt(dx * dx + dy * dy);
        }
        return Math.min(1f, len / (fontPx * TRAIL_SATURATION_FONTS));
    }

    // ==================== 卡拉OK点亮 ====================

    /**
     * 字的点亮进度 0..1（folia lit = smooth((t−start)/max(end−start, LIGHT_UP))）：
     * 卡拉OK式连续渐变、最短 {@link #LIGHT_UP_MS}，绝不跳变。
     * 无逐字轴时按均分出现时刻 + 兜底窗口渐变。
     */
    public static float litAt(long[] charTimes, int n, boolean flashMode,
                              float elapsedMs, float appearAt) {
        float window;
        float start;
        if (flashMode && charTimes != null && charTimes.length > 0) {
            int idx = Math.min(n, charTimes.length - 1);
            start = charTimes[idx];
            long end = n + 1 < charTimes.length ? charTimes[n + 1] : (long) (start + LIT_FALLBACK_MS);
            window = Math.max(LIGHT_UP_MS, end - start);
        } else {
            start = appearAt;
            window = LIGHT_UP_MS;
        }
        return smooth01((elapsedMs - start) / window);
    }

    // ==================== 换句交接：世界点 → 公告牌平面 ====================

    /**
     * 把一个世界坐标点投到公告牌平面上（pushBillboard 正变换的逆）：
     * translate → rotateY(−yaw) → rotateX(pitch) → scale(−s,−s,s) 的求逆。
     * 返回 [lx, ly]（文字像素坐标，平面中心为原点）；scale 退化时返回 null。
     *
     * <p>换句交接用：上一句的公告牌中心投到新句平面 → 新句各字的起飞点；
     * 新句中心投到旧句平面 → 旧句各字的离场汇聚点。</p>
     */
    public static float[] projectToPlane(double wx, double wy, double wz,
                                         double bx, double by, double bz,
                                         double yawDeg, double pitchDeg, float scale) {
        if (Math.abs(scale) < 1e-5f) return null;
        double qx = wx - bx;
        double qy = wy - by;
        double qz = wz - bz;
        double yaw = Math.toRadians(yawDeg);
        double cy = Math.cos(yaw), sy = Math.sin(yaw);
        // 逆 rotateY(−yaw) = rotateY(+yaw)：x' = x·cos + z·sin, z' = −x·sin + z·cos
        double x1 = qx * cy + qz * sy;
        double z1 = -qx * sy + qz * cy;
        double p = Math.toRadians(pitchDeg);
        double cp = Math.cos(p), sp = Math.sin(p);
        // 逆 rotateX(pitch) = rotateX(−pitch)：y' = y·cos + z·sin
        double y1 = qy * cp + z1 * sp;
        return new float[] {(float) (-x1 / scale), (float) (-y1 / scale)};
    }

    /** 按通道缩放/提亮颜色（提亮 >1 时钳到 255，防通道溢出污染相邻字节）。 */
    public static int dimRgb(int rgb, float factor) {
        int r = Math.min(255, Math.max(0, (int) (((rgb >> 16) & 0xFF) * factor)));
        int g = Math.min(255, Math.max(0, (int) (((rgb >> 8) & 0xFF) * factor)));
        int b = Math.min(255, Math.max(0, (int) ((rgb & 0xFF) * factor)));
        return (r << 16) | (g << 8) | b;
    }

    // ==================== 四芒闪点 / 崩解 / 恒定微动 ====================

    /** 四芒闪点的攻击时长（folia flashEnvelope attack 0.15s smoothstep）。 */
    public static final float FLASH_ATTACK_MS = 150f;
    /** 四芒闪点的指数衰减时长（folia decay 0.45s）。 */
    public static final float FLASH_DECAY_MS = 450f;
    /** 崩解漂离的封顶（字号数）：3D 长句防文字散到不可读（folia 无封顶，靠行尾淘汰）。 */
    public static final float DECAY_CAP_FONTS = 1.1f;
    /** 崩解起漂的逐字延迟（秒，folia decay.delay 按设置；此处确定性随机 0~0.8s）。 */
    public static final float DECAY_DELAY_MIN_S = 0.8f;
    public static final float DECAY_DELAY_MAX_S = 1.4f;
    /** 恒定绕行幅度（字号比；folia ORBIT=0.03 窗口高 ≈ 0.25 字号）。 */
    public static final float ORBIT_FONTS = 0.35f;
    /** 落位字的呼吸幅度（字号比，folia 0.022）。 */
    public static final float BREATH_FONTS = 0.02f;

    /**
     * 四芒闪点包络（folia reveal.ts flashEnvelope 一比一）：
     * attack 0.15s smoothstep 升起 → 指数衰减（tau 0.45s）。age&lt;0 恒 0。
     */
    public static float flashEnvelope(float ageMs) {
        if (ageMs <= 0f) return 0f;
        float attack = smooth01(ageMs / FLASH_ATTACK_MS);
        float over = Math.max(0f, ageMs - FLASH_ATTACK_MS);
        return attack * (float) Math.exp(-over / FLASH_DECAY_MS);
    }

    /**
     * 崩解漂离量（字号数，folia decay: amount = 0.08 × age^1.5，本仓库封顶
     * {@link #DECAY_CAP_FONTS} 防长句散到不可读）。age 秒 = 点亮后经过的秒数。
     */
    public static float decayAmount(float ageSec) {
        if (ageSec <= 0f) return 0f;
        return Math.min(DECAY_CAP_FONTS, 0.08f * (float) Math.pow(ageSec, 1.5));
    }

    /**
     * 换句整体的恒定绕行（folia lineTransform 的 orbit：永不停的小圆漂移）。
     * 幅度 {@link #ORBIT_FONTS}×fontPx，xy 相位差半圈 —— 返回 [x, y] 偏移。
     */
    public static float[] lineOrbit(float elapsedSec, float fontPx, float phase) {
        float ax = ORBIT_FONTS * fontPx;
        return new float[] {
                (float) Math.sin(elapsedSec * 0.52f + phase) * ax,
                (float) Math.cos(elapsedSec * 0.41f + phase * 1.3f) * ax * 0.7f
        };
    }

    /**
     * 落位字的呼吸晃动（folia glyph breath：sin/cos 双频，幅度
     * {@link #BREATH_FONTS}×fontPx）—— 返回 [x, y] 偏移。
     */
    public static float[] glyphBreath(float elapsedSec, float fontPx, float phase) {
        float b = BREATH_FONTS * fontPx;
        return new float[] {
                (float) Math.sin(elapsedSec * 0.9f + phase) * b,
                (float) Math.cos(elapsedSec * 1.13f + phase * 1.7f) * b
        };
    }

    // ==================== 十字爆闪（folia crossBurst 完整版） ====================

    /** 同时存在的小十字上限（folia MAX_BURSTS=14）。 */
    public static final int MAX_BURSTS = 14;
    /** 一个小十字持续多久（秒，folia BURST_DURATION）。 */
    public static final float BURST_DURATION_S = 0.6f;

    /**
     * 一个引爆点的包络求值（folia crossBurst update 的参数逐一对齐）。
     * out = [envelope, verticalLen, horizontalLen, flashSize, ringSize, ringAlpha, spread]：
     * 竖柱从字脚下拉到上方（拉丁十字比例）、80ms easeOutExpo 拉满；横条晚 20ms、
     * 交点在 0.42L 上段、100ms 展开 0.62L；白闪 exp(−age/0.07) 急灭；
     * 冲击环 0.4s easeOutCubic 扩散、alpha 反比消失。
     */
    public static void burstPose(float ageSec, float lengthPx, float[] out) {
        if (ageSec < 0f || ageSec > BURST_DURATION_S) {
            for (int i = 0; i < 7; i++) out[i] = 0f;
            return;
        }
        float envelope = Math.min(1f, ageSec / 0.03f)
                * (float) Math.exp(-Math.max(0f, ageSec - 0.03f) / 0.16f);
        float spread = 1f + ageSec * 2f;
        // 竖柱：easeOutExpo(age/0.08)
        float e80 = ageSec / 0.08f;
        float vertEase = e80 >= 1f ? 1f : 1f - (float) Math.pow(2, -10 * e80);
        float verticalLen = lengthPx * vertEase;
        // 横条：easeOutExpo((age−0.02)/0.1)，长度 0.62L
        float eH = (ageSec - 0.02f) / 0.1f;
        float horizEase = eH <= 0f ? 0f : (eH >= 1f ? 1f : 1f - (float) Math.pow(2, -10 * eH));
        float horizontalLen = lengthPx * 0.62f * horizEase;
        float flashSize = lengthPx * (0.35f + ageSec * 0.8f);
        // 冲击环：easeOutCubic(age/0.4)
        float rT = Math.min(1f, ageSec / 0.4f);
        float ring = 1f - (float) Math.pow(1f - rT, 3f);
        out[0] = envelope;
        out[1] = verticalLen;
        out[2] = horizontalLen;
        out[3] = flashSize;
        out[4] = lengthPx * 0.9f * ring;
        out[5] = 0.35f * (1f - ring) * Math.min(1f, ageSec / 0.03f);
        out[6] = spread;
    }

    /** 一个爆闪给整句光晕层的一下提亮（folia burstLightBoost；叠加封顶 0.45 由调用方做）。 */
    public static float burstLightBoost(float ageSec) {
        if (ageSec < 0f) return 0f;
        return 0.18f * (float) Math.exp(-ageSec / 0.1f);
    }

    /**
     * 按句选爆闪字与时刻（folia planBursts 的 3D 版，sung 模式）：密度按种子挑字、
     * 至少一个，返回触发表 {charIdx, timeMs, sizeMul, dy}（4 浮点步长；charIdx 为
     * 非空白字符序，timeMs 为本句 elapsed 上的引爆时刻）。
     *
     * @param charStarts 每非空白字符的点亮时刻（本句 elapsed ms，charTimes 同轴）
     * @param every      每隔几句一批（1 = 每句）
     */
    public static float[] planBursts(int lineIndex, long[] charStarts, int charCount,
                                     int seed, float density, int every) {
        if (charCount <= 0 || density <= 0f) return new float[0];
        if (lineIndex < 0 || (lineIndex % Math.max(1, every)) != 0) return new float[0];
        java.util.ArrayList<Integer> chosen = new java.util.ArrayList<>();
        for (int i = 0; i < charCount; i++) {
            if (WorldLyricsState.hashRand(i, 0L, 87, seed) * 0.5f + 0.5f < density) chosen.add(i);
        }
        if (chosen.isEmpty()) {
            chosen.add((int) ((WorldLyricsState.hashRand(0, 0L, 89, seed) * 0.5f + 0.5f) * charCount) % charCount);
        }
        // 不做单句封顶（folia 并发由渲染端 MAX_BURSTS 控制）：砍尾会让长句爆闪
        // 全挤在句首（R5 审查 P1-4）。渲染层按 age 窗口独立求值，无并发问题。
        float[] out = new float[chosen.size() * 4];
        int o = 0;
        for (int idx : chosen) {
            float time = charStarts != null && idx < charStarts.length ? charStarts[idx] : 0f;
            out[o++] = idx;
            out[o++] = time;
            out[o++] = 0.7f + (WorldLyricsState.hashRand(idx, 0L, 91, seed) * 0.5f + 0.5f) * 0.6f;
            out[o++] = WorldLyricsState.hashRand(idx, 0L, 93, seed) * 0.45f;
        }
        return out;
    }

    // ==================== 缓动与工具 ====================

    /** easeInOutCubic（folia flightProgress 同款）。 */
    public static float easeInOutCubic(float t) {
        return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3f) / 2f;
    }

    /** smoothstep 0..1（folia smooth 同款）。 */
    public static float smooth01(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        return t * t * (3f - 2f * t);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** 姿态/采样的线程内 scratch（渲染线程单线程使用）。 */
    private static final float[] POSE = new float[4];
}
