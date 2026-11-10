package com.muoniumplayer.core.rendering.font;

import com.muoniumplayer.render.skija.SkijaGfx;

import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;

/**
 * 文字的高斯光晕，全项目歌词发光的唯一实现。
 *
 * <p>项目里原先有四份各自为政的发光代码（经典全屏、Apple / Luminous / Tilt 共用的舞台层、
 * Cadenza、HUD 桌面歌词），做法都一样：把同一串文本按八个方向偏移各画一遍、逐层降透明度。
 * 那是 1.8.9 时代没有后处理管线的权宜之计，而且它<b>不是</b>高斯——八个副本之间填不满，
 * 出来的是方块状的灰雾，连笔画之间的空隙都糊上，字反而更难认。
 *
 * <p>现在渲染层是 Skia，{@code MaskFilter} 能直接对字形的覆盖遮罩做真高斯，一层一次绘制，
 * 不需要离屏图层。模糊后的字形遮罩会连同 sigma 一起进字形图集缓存，所以同一个字第二帧起
 * 就是一次贴图：实测一行七个字画两层，高斯 0.027ms，八方向偏移 0.205ms。
 *
 * <p>三层从外到内：最外层是散开的氛围光，中间层过渡，最里层紧贴笔画当亮边。三层都用
 * <b>加色</b>混合——光叠在一起该更亮，SRC_OVER 叠出来的是「更白的纸」而不是光。加色只在暗
 * 背景上成立，而歌词的底不管是压暗的模糊封面还是游戏画面，都足够暗。
 */
public final class TextGlow {

    /**
     * 辉光层的 sigma，按字号取比例。只作用于<b>晕形</b>（见 {@link #drawOn} 的
     * 两层合成），必须从大到小排——{@link #padding} 直接拿第 0 项算留白。
     */
    private static final double[] SIGMA_RATIO = {0.246, 0.080, 0.0246};

    /** 辉光层各自的不透明度上限，还要再乘发光强度。越靠外越淡。 */
    private static final double[] LAYER_ALPHA = {0.20, 0.34, 0.42};

    /**
     * 3D 世界歌词<b>独立晕格</b>的 sigma 链（{@link #drawGlowOn} 专用口径）。
     * 晕格只烘高斯光形、以加色混合画在墨格之下，能量口径与 {@link #drawOn}
     * 的「薄晕」完全不同：外层 σ 大而弥散（光圈主体），内层 σ 小、贴字边形成
     * 亮环，接通「墨 → 光」的过渡。0.20×字号×spread 在默认 spread=1.3 时晕的
     * 可见半径约等于一个字高，拉满 3.0 时约 1.8 倍字高的弥散光。
     */
    private static final double[] GLOW_SIGMA_RATIO = {0.20, 0.068};

    /** 晕格层的不透明度上限（乘发光强度）。加色混合下叠加即增亮，可给到全量程。 */
    private static final double[] GLOW_LAYER_ALPHA = {0.40, 0.85};

    /** 发光质量档（用户可选）：单层宽晕（最省）/ HQ（最平衡）/ Ultra（默认，HDR bloom 管线）。 */
    public static final int GLOW_TIER_STANDARD = 0;
    public static final int GLOW_TIER_HIGH = 1;
    public static final int GLOW_TIER_ULTRA = 2;
    public static final int GLOW_TIER_DEFAULT = GLOW_TIER_ULTRA;

    // ==================== Ultra 档 HDR bloom 烘焙管线 ====================
    // 探针五轮收敛的定案（_scratch/glowprobe GlowProbe2~5）：soft-knee bright-pass →
    // γ2.2 → 高斯预低通 → mip 链 1/2→1/4→1/8（每级先低通再降采样，防高频混叠）→
    // dual-filtering 上行（每步只放大 ~2×，逐级双线性 + 相加 + 轻高斯）→ γ1/2.2 →
    // 分层加色（宽晕场/外圈饱和/内圈字色）→ 字色白核。σ 以 50px 字号为基准，
    // 按实际烘焙字号等比缩放。逐字晕格 PLUS 叠加 ≈ 整行 bloom（晕/墨分离架构下
    // 跨字重叠由晕格 quad 的加色天然合成）。

    /** 管线版本：算法或参数变更时 +1，随晕格缓存键走，防旧格跨版本复用。 */
    public static final int BLOOM_PIPELINE_VERSION = 2;

    /**
     * 晕格独立烘焙超采样（设计像素 ×4）。4 是丝滑度与成本的平衡点：LINEAR 放大
     * 回设计域只剩 1.25×，跨格亮度差不再被放大出块感（3× 时 1.67× 放大是游戏里
     * 晕"割裂"的成因之一）；mip 链 1/8 级仍 ≥ 可运行的格宽。
     */
    public static final int GLOW_BAKE_SCALE = 4;

    private static final float BLOOM_THRESH = 0.13f;
    private static final float BLOOM_KNEE = 0.30f;
    private static final float BLOOM_SIGMA_PRE = 2.0f;
    private static final float BLOOM_SIGMA_M1 = 1.6f;
    private static final float BLOOM_SIGMA_M2 = 1.8f;
    private static final float BLOOM_SIGMA_M3 = 1.8f;
    private static final float BLOOM_SIGMA_UP1 = 0.6f;
    private static final float BLOOM_SIGMA_UP2 = 0.7f;
    private static final float BLOOM_SIGMA_CORE = 2.2f;
    private static final float BLOOM_GAIN_WIDE = 0.34f;
    private static final float BLOOM_GAIN_OUTER = 0.16f;
    private static final float BLOOM_GAIN_INNER = 0.38f;
    private static final float BLOOM_GAIN_CORE = 0.16f;
    private static final float BLOOM_WHITEN_INNER = 0.06f;
    private static final float BLOOM_WHITEN_CORE = 0.65f;
    private static final float BLOOM_SAT_OUTER = 1.14f;

    /**
     * Ultra 档晕格的宽晕等效 σ（调用方所在域的像素）。mip 链各级 σ 折算回
     * 掩码域（×2^i）后与预低通求和 —— 高斯尾巴 3σ 外不可见，pad 取它的 3 倍。
     */
    public static float bloomWideSigma(float fontPx, double spread) {
        float k = (float) (fontPx / 50.0 * Math.max(.05, spread));
        return (BLOOM_SIGMA_PRE + BLOOM_SIGMA_M1 * 2 + BLOOM_SIGMA_M2 * 4 + BLOOM_SIGMA_M3 * 8) * k;
    }

    /**
     * Ultra 档晕格像素烘焙。输入字形覆盖掩码（0..1），输出<b>直通 alpha</b> 的
     * ARGB int[]（rgb 存满强度光色、alpha 存强度 —— GL (SRC_ALPHA, ONE) 加色下
     * 贡献 = rgb×alpha 无损；全透明 texel 为 (0,0,0,0)，边缘渗色由调用方做
     * RGB 延拓）。任何异常返回 null，调用方回退旧分层烘焙。
     *
     * @param mask    字形覆盖度，长 w×h
     * @param fontPx  掩码所在域的字号（像素），σ 按 fontPx/50 等比缩放
     */
    public static int[] bakeBloomGlowPixels(float[] mask, int w, int h, float fontPx,
                                            double spread, double strength, int lightRgb) {
        try {
            float clamped = (float) clamp01(strength);
            if (mask == null || w <= 0 || h <= 0 || mask.length < w * h || clamped <= .004f) {
                return null;
            }
            if ((lightRgb & 0xFFFFFF) == 0) {
                return null;   // 黑光色 = 不发光（白核的提白会把黑变成灰，必须显式回退旧路径）
            }
            float k = (float) (fontPx / 50.0 * Math.max(.05, spread));
            float lr = ((lightRgb >> 16) & 255) / 255f;
            float lg = ((lightRgb >> 8) & 255) / 255f;
            float lb = (lightRgb & 255) / 255f;

            // 每格一次申请、全管线复用（下/上行链共 6 个 gaussBlur pass）：旧实现
            // 每个 pass 自己 new 一张 w*h 的临时表，一格晕要连丢 6 张 + 2 张图层
            // 全尺寸缓冲，155×189 的格上就是 8×117KB ≈ 940KB/格的瞬时垃圾。
            int n = w * h;
            float[] blurTmp = new float[n];
            float[] blurAcc = new float[n];

            // ---- bright-pass（soft-knee）→ 线性域 ----
            float[] b = new float[n];
            for (int i = 0; i < n; i++) {
                float v = Math.max(0f, mask[i]);
                if (v <= 1e-4f) {
                    continue;   // 无光：膝部形状在 v=0 处仍有残值，必须显式归零
                }
                float hard = Math.max(v - BLOOM_THRESH, 0);
                float soft = BLOOM_KNEE * Math.max(0f, Math.min(v - BLOOM_THRESH + BLOOM_KNEE, 2 * BLOOM_KNEE));
                soft = soft * soft / (4 * BLOOM_KNEE + 1e-4f);
                b[i] = (float) Math.pow(Math.max(hard, soft), 2.2);
            }
            // ---- 下行链：每级先低通再降采样（防高频混叠成块）----
            gaussBlur(b, w, h, BLOOM_SIGMA_PRE * k, blurTmp, blurAcc);
            int w1 = w / 2, h1 = h / 2, w2 = w1 / 2, h2 = h1 / 2, w3 = w2 / 2, h3 = h2 / 2;
            if (w3 < 1 || h3 < 1) {
                return null;
            }
            float[] m1 = down2(b, w, h);
            gaussBlur(m1, w1, h1, BLOOM_SIGMA_M1 * k, blurTmp, blurAcc);
            float[] m2 = down2(m1, w1, h1);
            gaussBlur(m2, w2, h2, BLOOM_SIGMA_M2 * k, blurTmp, blurAcc);
            float[] m3 = down2(m2, w2, h2);
            gaussBlur(m3, w3, h3, BLOOM_SIGMA_M3 * k, blurTmp, blurAcc);
            // ---- 上行链：每步只放大 ~2×，相加后轻高斯压台阶 ----
            float[] u = upTo(m3, w3, h3, w2, h2);
            addInto(u, m2);
            gaussBlur(u, w2, h2, BLOOM_SIGMA_UP1 * k, blurTmp, blurAcc);
            u = upTo(u, w2, h2, w1, h1);
            addInto(u, m1);
            gaussBlur(u, w1, h1, BLOOM_SIGMA_UP2 * k, blurTmp, blurAcc);
            u = upTo(u, w1, h1, w, h);
            gammaBack(u);
            // 外圈/内圈层在小域上 γ 返程后双线性放大到全网格（探针 addBilin 口径）
            gammaBack(m3);
            gammaBack(m1);

            // ---- 分层加色：宽晕场 / 外圈饱和 / 内圈字色 / 字色白核 ----
            float[] gR = new float[n], gG = new float[n], gB = new float[n];
            addLayerTinted(gR, gG, gB, u, BLOOM_GAIN_WIDE * clamped, lr, lg, lb, 0f, 1f);
            // 双线性采样与颜色累加合并，省掉两张全尺寸中间数组；算术次序不变。
            addLayerTintedUpsampled(gR, gG, gB, m3, w3, h3, w, h,
                    BLOOM_GAIN_OUTER * clamped, lr, lg, lb, 0f, BLOOM_SAT_OUTER);
            addLayerTintedUpsampled(gR, gG, gB, m1, w1, h1, w, h,
                    BLOOM_GAIN_INNER * clamped, lr, lg, lb, BLOOM_WHITEN_INNER, 1f);
            float[] core = new float[n];
            System.arraycopy(mask, 0, core, 0, n);
            gaussBlur(core, w, h, BLOOM_SIGMA_CORE * k, blurTmp, blurAcc);
            addLayerTinted(gR, gG, gB, core, BLOOM_GAIN_CORE * clamped, lr, lg, lb, BLOOM_WHITEN_CORE, 1f);

            // ---- 直通 alpha 输出：a = max 通道，rgb 除回满强度（GL 加色贡献 rgb×a 不变）----
            // ING 有序抖动（Jimenez 2014，±0.5 LSB）：晕的暗端 alpha < 16 的尾部在
            // 8bit 量化下出现同心台阶（游戏里叠在动态背景上显形为"割裂"），
            // 抖动把台阶打散成空间噪声 —— 人眼读作连续渐变。
            int[] out = new int[w * h];
            for (int y = 0; y < h; y++) {
                int row = y * w;
                for (int x = 0; x < w; x++) {
                    int i = row + x;
                    float dither = ing(x, y) / 255f;
                    float r = Math.max(0f, gR[i] + dither);
                    float g = Math.max(0f, gG[i] + dither);
                    float bl = Math.max(0f, gB[i] + dither);
                    float mx = Math.max(r, Math.max(g, bl));
                    if (mx <= 0.001f) {
                        out[i] = 0;
                        continue;
                    }
                    if (mx > 1f) {
                        r /= mx; g /= mx; bl /= mx; mx = 1f;
                    }
                    int ri = Math.round(r / mx * 255f), gi = Math.round(g / mx * 255f), bi = Math.round(bl / mx * 255f);
                    int a = Math.round(mx * 255f);
                    out[i] = (a << 24) | (Math.min(255, ri) << 16) | (Math.min(255, gi) << 8) | Math.min(255, bi);
                }
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 灰度场 γ 返程（负值钳零 —— 滑窗浮点误差的微负值经 pow 会成 NaN，碎屏根源）。 */
    private static void gammaBack(float[] a) {
        for (int i = 0; i < a.length; i++) {
            float v = a[i];
            a[i] = (float) Math.pow(v < 0 ? 0 : v, 1f / 2.2f);
        }
    }

    /** 颜色场累加：src × gain × tint（tint = 光色按 satK 调饱和后再向白提 whiten）。 */
    private static void addLayerTinted(float[] gR, float[] gG, float[] gB, float[] src,
                                       float gain, float lr, float lg, float lb,
                                       float whiten, float satK) {
        if (gain <= 0) {
            return;
        }
        float gray = 0.299f * lr + 0.587f * lg + 0.114f * lb;
        float tr = gray + (lr - gray) * satK;
        float tg = gray + (lg - gray) * satK;
        float tb = gray + (lb - gray) * satK;
        tr += (1f - tr) * whiten;
        tg += (1f - tg) * whiten;
        tb += (1f - tb) * whiten;
        for (int i = 0; i < gR.length; i++) {
            float v = src[i] * gain;
            gR[i] += v * tr;
            gG[i] += v * tg;
            gB[i] += v * tb;
        }
    }

    /**
     * 真高斯核分离模糊（各向同性；输出钳非负）。
     *
     * <p>两个 pass 都是<b>行连续</b>的（早年纵 pass 按 {@code for x { for y }} 走列，
     * 每步跨一行 = 一次 cache miss；154×189 的晕格上实测纵 pass 单步成本是横
     * pass 的 2 倍）。横 pass 拆三段：中间段 [r, w-r) 下标恒在界内，去掉每采样
     * 一次的双重 clamp。纵 pass 改成 scatter（对每个核偏移做一次整行加法），
     * 目标数组按行顺序累加，同样全程连续。</p>
     *
     * <p>横 pass 的浮点运算次序与旧实现<b>完全相同</b>（只是省掉界内像素多余的
     * clamp 分支）；纵 pass 改成 scatter 后累加被「按核偏移分组」，数学同值、
     * 浮点上可能差最后一位。8bit 量化后的逐像素一致性由
     * TextGlowBloomGoldenTest 用内联的旧实现钉死（15 组参数全 0 差异）。</p>
     *
     * @param tmp  横 pass 的中间缓冲，长度 ≥ w*h（由调用方复用，避免每格每 pass
     *             新建 float[n]：晕格烘焙一次调用要跑 6 个 pass）
     * @param acc2 纵 pass 的累加缓冲，长度 ≥ w*h（同上）
     */
    private static void gaussBlur(float[] a, int w, int h, float sigma,
                                  float[] tmp, float[] acc2) {
        if (sigma <= 0.05f) {
            return;
        }
        int r = Math.max(1, (int) Math.ceil(sigma * 2.5f));
        float[] k = new float[2 * r + 1];
        float s2 = 2f * sigma * sigma;
        float sum = 0;
        for (int i = -r; i <= r; i++) {
            float v = (float) Math.exp(-i * i / s2);
            k[i + r] = v;
            sum += v;
        }
        for (int i = 0; i < k.length; i++) {
            k[i] /= sum;
        }

        // ---- 横 pass：分三段，中间段去掉 clamp ----
        float[] t = tmp;
        int midStart = Math.min(r, w);
        int midEnd = Math.max(midStart, w - r);
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < midStart; x++) {
                float acc = 0;
                for (int i = -r; i <= r; i++) {
                    acc += a[row + clampIdx(x + i, w)] * k[i + r];
                }
                t[row + x] = acc;
            }
            for (int x = midStart; x < midEnd; x++) {
                float acc = 0;
                int base = row + x;
                for (int i = -r; i <= r; i++) {
                    acc += a[base + i] * k[i + r];
                }
                t[base] = acc;
            }
            for (int x = midEnd; x < w; x++) {
                float acc = 0;
                for (int i = -r; i <= r; i++) {
                    acc += a[row + clampIdx(x + i, w)] * k[i + r];
                }
                t[row + x] = acc;
            }
        }

        // ---- 纵 pass：scatter，两个数组都按行连续 ----
        // 累加顺序是「按核偏移分组」而非「按像素逐个累加」——两者数学同值，
        // 浮点上因分组顺序不同可能差最后一位；晕格最终经 8bit 量化输出。
        // 逐像素一致性由 TextGlowBloomGoldenTest 钉着（内联旧实现做金标准）。
        java.util.Arrays.fill(acc2, 0, w * h, 0f);
        for (int dy = -r; dy <= r; dy++) {
            float kv = k[dy + r];
            if (kv == 0f) {
                continue;
            }
            for (int y = 0; y < h; y++) {
                int srow = clampIdx(y + dy, h) * w;
                int drow = y * w;
                for (int x = 0; x < w; x++) {
                    acc2[drow + x] += t[srow + x] * kv;
                }
            }
        }
        for (int i = 0, n = w * h; i < n; i++) {
            a[i] = Math.max(0f, acc2[i]);
        }
    }

    /** 下标夹紧到 [0, n-1]（gaussBlur 的边缘延拓口径）。 */
    private static int clampIdx(int v, int n) {
        return v < 0 ? 0 : (v >= n ? n - 1 : v);
    }

    /** 2×2 box 下采样（天然低通）。 */
    private static float[] down2(float[] s, int w, int h) {
        int dw = w / 2, dh = h / 2;
        float[] d = new float[dw * dh];
        for (int dy = 0; dy < dh; dy++) {
            int y0 = dy * 2, y1 = Math.min(h - 1, dy * 2 + 1);
            for (int dx = 0; dx < dw; dx++) {
                int x0 = dx * 2, x1 = Math.min(w - 1, dx * 2 + 1);
                d[dy * dw + dx] = (s[y0 * w + x0] + s[y0 * w + x1] + s[y1 * w + x0] + s[y1 * w + x1]) * 0.25f;
            }
        }
        return d;
    }

    /** 双线性放大到任意目标尺寸（上行链每步 ~2×，杜绝碎块）。 */
    private static float[] upTo(float[] s, int w, int h, int uw, int uh) {
        float[] d = new float[uw * uh];
        for (int y = 0; y < uh; y++) {
            float fy = (y + 0.5f) * h / (float) uh - 0.5f;
            int y0 = Math.max(0, Math.min(h - 1, (int) Math.floor(fy)));
            int y1 = Math.min(h - 1, y0 + 1);
            float ty = Math.max(0f, Math.min(1f, fy - y0));
            for (int x = 0; x < uw; x++) {
                float fx = (x + 0.5f) * w / (float) uw - 0.5f;
                int x0 = Math.max(0, Math.min(w - 1, (int) Math.floor(fx)));
                int x1 = Math.min(w - 1, x0 + 1);
                float tx = Math.max(0f, Math.min(1f, fx - x0));
                d[y * uw + x] = (s[y0 * w + x0] * (1 - tx) + s[y0 * w + x1] * tx) * (1 - ty)
                        + (s[y1 * w + x0] * (1 - tx) + s[y1 * w + x1] * tx) * ty;
            }
        }
        return d;
    }

    private static void addInto(float[] d, float[] s) {
        for (int i = 0; i < d.length; i++) {
            d[i] += s[i];
        }
    }

    /** Same pixel-center interpolation and tint order as upTo followed by addLayerTinted. */
    private static void addLayerTintedUpsampled(float[] gR, float[] gG, float[] gB,
                                                float[] src, int w, int h, int uw, int uh,
                                                float gain, float lr, float lg, float lb,
                                                float whiten, float satK) {
        if (gain <= 0) return;
        float gray = 0.299f * lr + 0.587f * lg + 0.114f * lb;
        float tr = gray + (lr - gray) * satK;
        float tg = gray + (lg - gray) * satK;
        float tb = gray + (lb - gray) * satK;
        tr += (1f - tr) * whiten;
        tg += (1f - tg) * whiten;
        tb += (1f - tb) * whiten;
        for (int y = 0; y < uh; y++) {
            float fy = (y + 0.5f) * h / (float) uh - 0.5f;
            int y0 = Math.max(0, Math.min(h - 1, (int) Math.floor(fy)));
            int y1 = Math.min(h - 1, y0 + 1);
            float ty = Math.max(0f, Math.min(1f, fy - y0));
            for (int x = 0; x < uw; x++) {
                float fx = (x + 0.5f) * w / (float) uw - 0.5f;
                int x0 = Math.max(0, Math.min(w - 1, (int) Math.floor(fx)));
                int x1 = Math.min(w - 1, x0 + 1);
                float tx = Math.max(0f, Math.min(1f, fx - x0));
                float sample = (src[y0 * w + x0] * (1 - tx) + src[y0 * w + x1] * tx) * (1 - ty)
                        + (src[y1 * w + x0] * (1 - tx) + src[y1 * w + x1] * tx) * ty;
                float v = sample * gain;
                int i = y * uw + x;
                gR[i] += v * tr;
                gG[i] += v * tg;
                gB[i] += v * tb;
            }
        }
    }

    /** 带权重的累加（行级辉光上行链用：各级贡献按比例计，避免能量重复叠加）。 */
    private static void addInto(float[] d, float[] s, float weight) {
        for (int i = 0; i < d.length; i++) {
            d[i] += s[i] * weight;
        }
    }

    // ==================== 行级辉光（指数色调映射管线）====================
    // 与上面的 Ultra 晕格管线并列的第二套算法，服务「整行一枚光晕 quad」的通路：
    // 单格金字塔 + 指数色调映射合成。两者刻意不复用参数：
    //
    //   * 晕格是逐字的，一格只有几像素宽，σ 必须贴合单字笔画，靠分层加色把
    //     「墨 → 光」的亮度阶梯拼出来；
    //   * 行级是一整行一张，格宽是格高的十几倍，可以直接把整行的光能铺开——
    //     于是换成指数色调映射 c = 1 - exp(-e)，亮处自己趋向白、暗尾永远不断，
    //     不需要再手工分「宽晕场 / 外圈饱和 / 内圈字色 / 白核」四层。
    //
    // 下标 0 是最小一级（最大范围），逐级放大叠回来：小级给弥散的大光晕，
    // 大级给贴着笔画的亮边。

    /**
     * 行级辉光的降采样级数。7 级到 1/128：行级位图本身已按 2× 超采样烘，
     * 再降 7 级后最外层的光能铺满整行，肉眼看是贴在字后的一大团柔光。
     */
    public static final int ROW_GLOW_LEVELS = 7;

    /**
     * 行级辉光上采样的叠加强度：<b>必须小于 1</b>。
     *
     * <p>上行链是「本级能量 + gain × 放大后的更小一级」。gain ≥ 1 时这个序列
     * 不收敛 —— 每级都放大一点，六级下来整张画布被推进指数色调映射的饱和区，
     * 光晕退化成一块实心白（能量表实测：gain=1.15 时 92% 像素饱和）。
     * gain 越小，光晕里「大范围弥散」的比重越低、越贴字。</p>
     *
     * <p>0.30 ~ 0.45 是实测区间：字芯仍能到白热，同时只有不到 1% 的像素饱和。</p>
     */
    private static final float ROW_GLOW_GAIN_BASE = 0.30f;
    private static final float ROW_GLOW_GAIN_SPREAD = 0.15f;

    /**
     * 行级辉光的曝光系数。指数色调映射 c = 1 - exp(-e) 在 e = 1 时到 0.63、
     * e = 2 时到 0.86、e = 3 时 0.95 —— 乘 4 让满强度的字芯稳稳过曝成白，
     * 而晕的外圈还留在 0.1 上下，形成「中心白热、外圈带色」的层次。
     */
    private static final float ROW_GLOW_EXPOSURE = 4.0f;

    /**
     * 行级辉光合成时向白色靠拢的力度（0 = 完全不提白，1 = 高能区全白）。
     * 指数色调映射本身已让高能区趋白；再补这一档让字芯越过「亮」到「白热」。
     * 1.0 是实测定案：字芯三通道齐平 255 附近，外圈色相不受影响。
     */
    private static final float ROW_GLOW_WHITEN = 1.0f;

    /**
     * 行级辉光烘焙。输入整行字形覆盖掩码（0..1），输出<b>直通 alpha</b> 的
     * ARGB int[]（rgb 存满强度光色、alpha 存强度 —— 加色混合下贡献 rgb×alpha
     * 无损；全透明 texel 为 0）。
     *
     * <p>管线：soft-knee 亮部提取 → γ2.2 进线性域 → 逐级 1/2 降采样（每级先
     * 低通，防高频混叠成块）→ 从最小一级逐级放大叠加（8 点帐篷滤波，轴向 ×1、
     * 对角 ×2）→ 指数色调映射合成 → 直通 alpha 输出。</p>
     *
     * <p><b>不做任何抖动</b>：本通路刻意保持像素的干净量化，暗尾的台阶由调用方
     * 的超采样与纹理 LINEAR 过滤消化。</p>
     *
     * @param mask     字形覆盖度（0..1），长 w×h，行主序
     * @param fontPx   掩码所在域的字号（像素）；σ 按 fontPx/50 等比缩放
     * @param spread   扩散 0..1，越大光晕越散
     * @param strength 发光强度 0..1
     * @param lightRgb 光色（低 24 位）
     * @return 直通 alpha 的 ARGB 像素；参数非法或异常时返回 null
     */
    public static int[] bakeRowGlowPixels(float[] mask, int w, int h, float fontPx,
                                          double spread, double strength, int lightRgb) {
        try {
            float clamped = (float) clamp01(strength);
            if (mask == null || w <= 0 || h <= 0 || mask.length < w * h || clamped <= .004f) {
                return null;
            }
            if ((lightRgb & 0xFFFFFF) == 0) {
                return null;   // 黑光色 = 不发光
            }
            // 行级不等宽：金字塔级数受短边约束，密铺的小行少降几级
            int levels = ROW_GLOW_LEVELS;
            while (levels > 1 && (Math.min(w, h) >> levels) < 2) {
                levels--;
            }
            float k = (float) (fontPx / 50.0 * Math.max(.05, spread));
            float lr = ((lightRgb >> 16) & 255) / 255f;
            float lg = ((lightRgb >> 8) & 255) / 255f;
            float lb = (lightRgb & 255) / 255f;

            // ---- 亮部提取（soft-knee）→ 线性域 ----
            int n = w * h;
            float[] base = new float[n];
            for (int i = 0; i < n; i++) {
                float v = Math.max(0f, mask[i]);
                if (v <= 1e-4f) {
                    continue;
                }
                float hard = Math.max(v - BLOOM_THRESH, 0);
                float soft = BLOOM_KNEE * Math.max(0f, Math.min(v - BLOOM_THRESH + BLOOM_KNEE, 2 * BLOOM_KNEE));
                soft = soft * soft / (4 * BLOOM_KNEE + 1e-4f);
                base[i] = (float) Math.pow(Math.max(hard, soft), 2.2);
            }

            // 逐级低通复用同一对临时表（每级网格都不超过 w*h，长度取原网格即可）
            float[] blurTmp = new float[n];
            float[] blurAcc = new float[n];

            // ---- 下行：逐级先低通再降采样 ----
            // 只有第一级在原网格上做高斯（这一级负责把抗锯齿边缘揉开，是唯一的
            // 大半径卷积）；之后每级都在已经缩小的网格上低通，σ 折算回本级域后
            // 只有 0.8~1.2px，成本随面积平方下降。
            float[][] lv = new float[levels][];
            int[] lw = new int[levels];
            int[] lh = new int[levels];
            gaussBlur(base, w, h, BLOOM_SIGMA_PRE * k, blurTmp, blurAcc);
            float[] cur = base;
            int cw = w, ch = h;
            for (int i = 0; i < levels; i++) {
                // down2 内部按 w/2 取尺寸（向下取整）；这里必须用同一口径，
                // 否则短边为奇数时下一轮越界（行级格宽常是奇数）
                int nw = Math.max(1, cw / 2);
                int nh = Math.max(1, ch / 2);
                cur = down2(cur, cw, ch);
                cw = nw;
                ch = nh;
                // 降采样后的本级域低通：σ 按域比例折回（像素域 σ / 2^i）
                gaussBlur(cur, cw, ch, BLOOM_SIGMA_M1 * k, blurTmp, blurAcc);
                lv[i] = cur;
                lw[i] = cw;
                lh[i] = ch;
            }

            // ---- 上行：从小到大逐级叠加（8 点帐篷）----
            // 口径（标准 bloom 上行）：每级把「更小一级的结果」放大到本级，
            // 乘扩散系数后叠加到<b>本级自身的能量</b>上；本级能量只计一次。
            //
            // 帐篷滤波是均值型、能量守恒，所以这里不需要再除级数；早期版本
            // 额外给每级乘了 stepGain 的同时又把上一级全额累加，等于「平均 ×
            // 逐级放大」，七级下来 mean 涨到 9 倍，整张画布推进 1-exp 饱和区，
            // 光晕退化成实心白（探针实拍 + 逐级能量表确认）。
            //
            // 扩散由 gain 控制：gain 越大，大范围的成分占比越高，光晕越散。
            float gain = ROW_GLOW_GAIN_BASE
                    + (float) clamp01(spread) * ROW_GLOW_GAIN_SPREAD;
            float[] up = lv[levels - 1];
            int uw = lw[levels - 1], uh = lh[levels - 1];
            for (int i = levels - 2; i >= 0; i--) {
                // 先把本级内容接过来，再把放大后的模糊级按 gain 叠上去
                float[] acc = lv[i];
                addInto(acc, tentUp(up, uw, uh, lw[i], lh[i]), gain);
                up = acc;
                uw = lw[i];
                uh = lh[i];
            }
            // 回到原网格
            float[] energy = (uw == w && uh == h) ? up : upTo(up, uw, uh, w, h);

            // ---- 指数色调映射合成 → 直通 alpha ----
            // e 是线性域的累计光能。c = 1 - exp(-e × exposure) 永不硬截断：
            // 亮处渐近 1（白热），暗处按比例保留，尾巴一直拖到 0。
            int[] out = new int[w * h];
            for (int i = 0; i < out.length; i++) {
                float e = energy[i] * clamped * ROW_GLOW_EXPOSURE;
                if (e <= 1e-4f) {
                    out[i] = 0;
                    continue;
                }
                float c = 1f - (float) Math.exp(-e);
                // 白热：按「超出中灰的程度」做幂次提白。指数色调映射本身让亮处
                // 趋近 1，但那只把 <b>亮度</b> 拉满，光色仍留在色相上 —— 红光的
                // 字芯会停在 (255,120,110) 这种「很亮的红」而不是白。这里用
                // (c-0.35)/0.65 的平方做权重，只在真正的高能区才把三通道推向白，
                // 外圈几乎不动，色相保留。
                float over = Math.max(0f, (c - 0.35f) / 0.65f);
                float whiten = over * over * ROW_GLOW_WHITEN;
                float r = lr + (1f - lr) * whiten;
                float g = lg + (1f - lg) * whiten;
                float bl = lb + (1f - lb) * whiten;
                int a = Math.round(Math.min(1f, c) * 255f);
                if (a <= 0) {
                    out[i] = 0;
                    continue;
                }
                out[i] = (a << 24)
                        | (Math.min(255, Math.round(r * 255f)) << 16)
                        | (Math.min(255, Math.round(g * 255f)) << 8)
                        | Math.min(255, Math.round(bl * 255f));
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 行级辉光的留白（烘焙像素）：金字塔最外一层的 3σ 之外已不可见。
     *
     * <p>口径与管线严格对齐 —— 每一级的等效半径 = 本级自身低通 σ 加上「缩小
     * 前那一级的累计半径 ÷ 2」（光能随降采样等比缩进）。从第 0 级递推到最后
     * 一级即最外层半径，而不是把各级 σ 直接相加（那样会高估成 15 倍）。</p>
     */
    public static int rowGlowPadding(float fontPx, double spread) {
        float k = (float) (fontPx / 50.0 * Math.max(.05, spread));
        // 第 0 级（降 1 次后）的等效半径：原网格预低通 + 本级低通
        float sigma = (BLOOM_SIGMA_PRE * k + BLOOM_SIGMA_M1 * k) * 0.5f;
        for (int i = 1; i < ROW_GLOW_LEVELS; i++) {
            sigma = sigma * 0.5f + BLOOM_SIGMA_M1 * k;
        }
        return (int) Math.ceil(3.0 * sigma);
    }

    /**
     * 8 点帐篷滤波放大：轴向 4 个 ×1、对角 4 个 ×2，合计 /12。
     * 比双线性多带一圈对角权重，放大时不出现十字状的方向性台阶。
     * 均值型、能量守恒 —— 扩散强度和叠加比例由调用方决定，这里不乘任何系数。
     */
    private static float[] tentUp(float[] s, int w, int h, int uw, int uh) {
        float[] d = new float[uw * uh];
        float sx = w / (float) uw;
        float sy = h / (float) uh;
        for (int y = 0; y < uh; y++) {
            float fy = (y + 0.5f) * sy - 0.5f;
            for (int x = 0; x < uw; x++) {
                float fx = (x + 0.5f) * sx - 0.5f;
                float acc = sample(s, w, h, fx, fy) * 4f
                        + sample(s, w, h, fx - sx, fy)
                        + sample(s, w, h, fx + sx, fy)
                        + sample(s, w, h, fx + sx, fy - sy)
                        + sample(s, w, h, fx - sx, fy + sy)
                        + sample(s, w, h, fx - sx, fy - sy) * 2f
                        + sample(s, w, h, fx + sx, fy - sy) * 2f
                        + sample(s, w, h, fx + sx, fy + sy) * 2f
                        + sample(s, w, h, fx - sx, fy + sy) * 2f;
                d[y * uw + x] = acc / 12f;
            }
        }
        return d;
    }

    /** 双线性采样单点（越界钳到边缘，光能不外泄成空洞）。 */
    private static float sample(float[] s, int w, int h, float x, float y) {
        int x0 = Math.max(0, Math.min(w - 1, (int) Math.floor(x)));
        int y0 = Math.max(0, Math.min(h - 1, (int) Math.floor(y)));
        int x1 = Math.min(w - 1, x0 + 1);
        int y1 = Math.min(h - 1, y0 + 1);
        float tx = Math.max(0f, Math.min(1f, x - x0));
        float ty = Math.max(0f, Math.min(1f, y - y0));
        return (s[y0 * w + x0] * (1 - tx) + s[y0 * w + x1] * tx) * (1 - ty)
                + (s[y1 * w + x0] * (1 - tx) + s[y1 * w + x1] * tx) * ty;
    }

    /** Interleaved Gradient Noise（Jimenez 2014）：±0.5 LSB 有序抖动，消 8bit 量化条带。 */
    private static float ing(int x, int y) {
        float v = 52.9829189f * fract(0.06711056f * x + 0.00583715f * y);
        return fract(v) - 0.5f;
    }

    private static float fract(float v) {
        return v - (float) Math.floor(v);
    }

    /** 各档的层参数：σ 比例（×字号×spread）× 层 α（×强度）。探针实测定格。 */
    private static final double[][] TIER_SIGMAS = {
            {0.26},                                              // Standard 单层宽晕
            {0.26, 0.055},                                       // HQ 外柔晕 + 内亮环
            {0.55, 0.24, 0.05, 0.02},                            // Ultra 三尺度 Bloom + 白芯
    };
    private static final double[][] TIER_ALPHAS = {
            {0.50},
            {0.34, 0.68},
            {0.14, 0.34, 0.80, 0.22},
    };

    private TextGlow() {
    }

    public static void draw(CFontRenderer font, String text, double x, double y,
                            int rgb, double alpha, double strength) {
        draw(font, text, x, y, rgb, alpha, strength, 1.0);
    }

    /**
     * @param rgb      光的颜色，低 24 位有效；一般就是字本身的颜色
     * @param alpha    这段文字的基础不透明度（0~1），淡入淡出乘在这里
     * @param strength 发光强度（0~1），0 表示整条链路跳过
     * @param spread   扩散倍率，1.0 是歌词的默认口径；调大光晕更散更远
     */
    public static void draw(CFontRenderer font, String text, double x, double y,
                            int rgb, double alpha, double strength, double spread) {
        Canvas target = SkijaGfx.canvas();
        if (target == null) {
            return;
        }
        drawOn(target, font, text, x, y, rgb, alpha, strength, spread);
    }

    /**
     * 画到<b>指定的</b>画布 —— 3D 世界歌词的纹理烘焙用：光晕与文字一起烘进一张离屏位图。
     * 语义与 {@link #draw} 完全一致（同样的三层 sigma/alpha、加色混合），只是画布由调用方给。
     */
    public static void drawOn(Canvas canvas, CFontRenderer font, String text, double x, double y,
                              int rgb, double alpha, double strength, double spread) {
        if (canvas == null || font == null || text == null || text.isEmpty()) {
            return;
        }
        double clampedStrength = clamp01(strength);
        double clampedAlpha = clamp01(alpha);
        if (clampedStrength <= .004 || clampedAlpha <= .004) {
            return;
        }
        double scale = font.sizePx * Math.max(.05, spread);
        BlendMode previousBlend = SkijaGfx.blendMode();
        SkijaGfx.setBlendMode(BlendMode.PLUS);
        try {
            // 辉光的观感关键：晕要「薄」。过去三层把文字实体重画三遍，紧贴笔画
            // 处三层 alpha 加色叠出近不透明的实色壳 —— 用户反馈「像包了一层
            // 颜色」。改法：两层都把整体 alpha 压低、只保留大 σ 的柔晕与中 σ
            // 的过渡（小 σ 层紧贴笔画、与墨色本体几乎重合，视觉上只会形成
            // 壳，删掉由墨色本体自己提供「亮边」）。晕形仍然是文字形状的
            // blur 副本，但因为 alpha 低、无小 σ 层，笔画边缘由墨色抗锯齿
            // 灰阶直接过渡到柔晕，不再是「色壳包字」。
            for (int layer = 0; layer < 2; layer++) {
                double layerAlpha = clampedAlpha * clampedStrength * LAYER_ALPHA[layer];
                if (layerAlpha <= .003) {
                    continue;
                }
                font.drawGlyphsOn(canvas, text, x, y, withAlpha(rgb, layerAlpha), 255,
                        (float) (scale * SIGMA_RATIO[layer]), null);
            }
        } finally {
            SkijaGfx.setBlendMode(previousBlend);
        }
    }

    /**
     * 3D 世界歌词<b>独立晕格</b>的烘焙口：只画高斯光形、不画墨迹。晕格与墨格
     * 在渲染层以「晕在下（加色）、墨在上（正常）」叠加 —— 这是唯一能同时得到
     * 「字锐利 + 光真发光」的合成：加色只增不减，亮背景上光晕融入、暗背景上
     * 浮出，永远不会像 alpha 混合那样把背景压成灰雾（旧「烘进同一张格」的观感
     * 问题根源，见 docs/方案-3D歌词真实发光.md）。
     *
     * <p>与 {@link #drawOn} 同样是加色（PLUS）画进烘焙位图；只是 σ 链与能量
     * 口径换用 {@link #GLOW_SIGMA_RATIO}/{@link #GLOW_LAYER_ALPHA}。调用方
     * 用 {@link #glowPadding} 计算留白。
     *
     * @param rgb      光的颜色，低 24 位有效（跟随字色模式由调用方解析后传入）
     * @param strength 发光强度（0~1），0 表示整条链路跳过
     * @param spread   扩散倍率，1.0 是默认口径
     */
    public static void drawGlowOn(Canvas canvas, CFontRenderer font, String text, double x, double y,
                                  int rgb, double strength, double spread) {
        drawGlowOn(canvas, font, text, x, y, rgb, 0, strength, spread);
    }

    /**
     * 带墨色回收的晕格绘制：内层 σ（贴字亮环）的光色向墨色回收 —— 墨→光的
     * 色相过渡由内层接通，自定义光色（尤其白光）不再以亮环形式包住彩色墨字
     * （「色壳」割裂感的来源）；外层 σ 保留纯光色弥散。ink = 本字最终墨色，
     * 0 = 无墨色可回收（两层都用光色，退回旧口径）。
     */
    public static void drawGlowOn(Canvas canvas, CFontRenderer font, String text, double x, double y,
                                  int rgb, int ink, double strength, double spread) {
        drawGlowOn(canvas, font, text, x, y, rgb, ink, strength, spread, GLOW_TIER_DEFAULT);
    }

    /**
     * 指定质量档的晕格绘制。分层语义（探针定格，见 _scratch/glowprobe）：
     * <ul>
     *   <li><b>Standard</b>：单层宽晕 —— Emission + Outer Glow，最省。</li>
     *   <li><b>High</b>（默认）：外层柔晕 + 双层 Halo（背景融合）+ 内层贴字
     *       亮环（光色向墨回收 55%，墨→光过渡无色壳）。</li>
     *   <li><b>Ultra</b>：HQ 全部 + 第三层超宽弥散（多尺度 Bloom）+ 极窄白芯
     *       （能量过曝趋白，真实发光体质感）。</li>
     * </ul>
     * ink = 本字最终墨色（0 = 无墨可回收，内层退纯光色）。
     */
    public static void drawGlowOn(Canvas canvas, CFontRenderer font, String text, double x, double y,
                                  int rgb, int ink, double strength, double spread, int tier) {
        if (canvas == null || font == null || text == null || text.isEmpty()) {
            return;
        }
        double clampedStrength = clamp01(strength);
        if (clampedStrength <= .004) {
            return;
        }
        double scale = font.sizePx * Math.max(.05, spread);
        BlendMode previousBlend = SkijaGfx.blendMode();
        SkijaGfx.setBlendMode(BlendMode.PLUS);
        try {
            int t = Math.max(GLOW_TIER_STANDARD, Math.min(GLOW_TIER_ULTRA, tier));
            double[] sigmas = TIER_SIGMAS[t];
            double[] alphas = TIER_ALPHAS[t];
            int light = rgb & 0xFFFFFF;
            int inkC = ink & 0xFFFFFF;
            for (int layer = 0; layer < sigmas.length; layer++) {
                double layerAlpha = clampedStrength * alphas[layer];
                if (layerAlpha <= .003) {
                    continue;
                }
                int layerRgb = light;
                if (t == GLOW_TIER_HIGH && layer == 1 && inkC != 0) {
                    layerRgb = mixRgb(light, inkC, 0.55f);   // 内亮环：墨回收
                }
                if (t == GLOW_TIER_ULTRA && layer == 2 && inkC != 0) {
                    int recovered = mixRgb(light, inkC, 0.55f);
                    layerRgb = mixRgb(recovered, 0xFFFFFF, 0.35f);   // 亮环 + 白芯
                }
                if (t == GLOW_TIER_ULTRA && layer == 3) {
                    layerRgb = 0xFFFFFF;                          // 极窄白芯
                }
                font.drawGlyphsOn(canvas, text, x, y, withAlpha(layerRgb, layerAlpha), 255,
                        (float) (scale * sigmas[layer]), null);
            }
        } finally {
            SkijaGfx.setBlendMode(previousBlend);
        }
    }

    /** 按通道线性混色。 */
    private static int mixRgb(int a, int b, double t) {
        int r = (int) (((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * t) + 0.5);
        int g = (int) (((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * t) + 0.5);
        int b2 = (int) (((a & 255) + ((b & 255) - (a & 255)) * t) + 0.5);
        return (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b2);
    }

    /**
     * 独立晕格向外扩出去多少烘焙像素。取 3σ₀：高斯尾巴在那之外已不可见。
     * 墨格与晕格的 quad 以同一 (x,y) 锚点对齐（晕格 pad ≥ 墨格 pad），晕格
     * 必须按自己的 pad 留白，否则大扩散档光晕被格边切成方块。
     */
    public static int glowPadding(CFontRenderer font, double spread) {
        return glowPadding(font, spread, GLOW_TIER_DEFAULT);
    }

    /** 指定档的晕格留白。Ultra 走 HDR bloom 管线：3×宽晕等效 σ（折算到晕格 3× 烘焙域）。 */
    public static int glowPadding(CFontRenderer font, double spread, int tier) {
        if (font == null) {
            return 0;
        }
        int t = Math.max(GLOW_TIER_STANDARD, Math.min(GLOW_TIER_ULTRA, tier));
        if (t == GLOW_TIER_ULTRA) {
            // font.sizePx 是墨格 5× 域字号；晕格在 GLOW_BAKE_SCALE× 域烘焙，σ 随域字号等比缩放
            float sigmaGlowDomain = bloomWideSigma(font.sizePx, spread) * (GLOW_BAKE_SCALE / 5.0f);
            return (int) Math.ceil(3.0 * sigmaGlowDomain);
        }
        double widest = TIER_SIGMAS[t][0];
        return (int) Math.ceil(3.0 * font.sizePx * Math.max(.05, spread) * widest);
    }

    /**
     * 光晕向外扩出去多少像素。往离屏图层里画发光时，图层四周至少要留这么宽的白，
     * 否则边界会把光晕切成方块——sigma 比原先那个偏移半径大得多，这个坑很容易踩。
     *
     * <p>取 3σ：高斯在那之外的尾巴已经看不见了。
     */
    public static int padding(CFontRenderer font, double spread) {
        if (font == null) {
            return 0;
        }
        return (int) Math.ceil(3.0 * font.sizePx * Math.max(.05, spread) * SIGMA_RATIO[0]);
    }

    private static int withAlpha(int rgb, double alpha) {
        int a = (int) Math.round(clamp01(alpha) * 255.0);
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    private static double clamp01(double value) {
        return value < 0 ? 0 : (value > 1 ? 1 : value);
    }
}