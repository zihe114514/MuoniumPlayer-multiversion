package com.muoniumplayer.core.lyrics3d;

/**
 * 3D 歌词「辉光后处理」的公共契约与开关 —— 渲染层（本包）与树侧宿主的对接面。
 *
 * <h2>管线（能量域加色，替代烘焙晕的 alpha 混合）</h2>
 * <ol>
 *   <li><b>mask</b>：把「字的形状」画进一张窗口分辨率离屏画布（不带阴影、不带特效、
 *       不带烘焙晕）—— 树侧按宿主能力走两条路：能采集 quad 的记进
 *       {@link MaskQuadCollector}（present 端用自有管线重画），不能采集的在
 *       mask 绑定窗口内直接调宿主的原有 submit 通路；</li>
 *   <li><b>bloom</b>：mask 逐级减半下采样（Kawase 双重核）7 级，再逐级放回叠加
 *       —— 小级别给贴字亮边、大级别给大范围柔晕；</li>
 *   <li><b>composite</b>：加色混合垫到最终画面上，指数色调映射
 *       {@code 1-exp(-x)} 让字芯过曝趋白、边缘保持光色；8bit 落地按概率进位
 *       抖动，光晕最外圈以颗粒淡出而不是被舍入成硬边。</li>
 * </ol>
 * 复合顺序永远是「晕垫底 → 清晰字画上层」：模糊只作用于 mask，本体第二遍
 * 原样再画，字不会被任何模糊碰到。
 *
 * <h2>开关与参数</h2>
 * {@link #configured} 由渲染层在每帧开头按设置推导（glowEnabled + glowStrength，
 * 且仅现代化风格生效）；树侧在安全时机读 {@link #take()} 一次性消费 ——
 * 返回 null = 本帧不跑后处理（开关关 / 强度过低 / 普通无后处理路径）。
 * 后处理跑在「光影合成链之后直画最终画面」的通路里（1.21.x 的 HUD 直画、
 * 26.x 的 present 兜底），无光影时的世界阶段路径不启用 —— 那里的 blend/深度
 * 状态由宿主管，且观感收益为零（本就有烘焙晕）。
 */
public final class GlowBloom {

    /** 本帧辉光参数（树侧 take() 消费；null = 本帧不跑）。 */
    public static final class Params {
        /** 光色（低 24 位）。 */
        public int tintRgb;
        /** 曝光：能量缩放，观测域 1-exp(-energy)。由发光强度换算。 */
        public float exposure;
        /** 扩散 0..1：越大光晕越宽越柔（控制升采样级强度与级数分配）。 */
        public float spread;
    }

    /** 树侧每帧 take 一次的本帧参数。 */
    private static volatile Params pending;

    /**
     * 本帧是否配置了辉光后处理（渲染层决定 mask 采集是否激活）。
     * 由 {@link WorldLyricsRenderer} 帧头调用 {@link #configure} 后为真。
     */
    public static boolean configured;

    /**
     * 渲染层帧头调用：按设置推导本帧辉光参数。strength=0 / 开关关 /
     * 非现代化风格 → 不配置（mask 采集零成本短路）。
     */
    public static void configure(WorldLyricsSettings settings, boolean styleModern) {
        Params p = null;
        if (settings != null && settings.glowEnabled && styleModern
                && settings.glowStrength > 0.02f) {
            p = new Params();
            p.tintRgb = settings.glowColorRgb & 0xFFFFFF;
            // 强度 → 曝光：量程按实机标定。合成亮度 = 1-exp(-0.33×覆盖率×级间
            // 强度×曝光)，3D 公告牌满句覆盖率 ~0.7%（上游 2D 大字 ~20% 的 1/30），
            // 曝光 14 实测晕外圈只有 ~1.3% 亮度增量（低于人眼阈值）—— 量程提到
            // 100：0.6 默认 ≈ 66（满句晕外圈 ~7%，对齐上游默认观感）、拉满 ≈ 100
            // （字芯溢白）。低段 s^0.85 保持滑杆低段不至于一拉就糊。
            double s = Math.max(0f, Math.min(1f, settings.glowStrength));
            p.exposure = (float) (100.0 * Math.pow(s, 0.85));
            p.spread = Math.max(0f, Math.min(1f, (settings.glowSpread - 0.5f) / 1.3f));
        }
        pending = p;
        configured = p != null;
    }

    /** 树侧在安全时机取走本帧参数；null = 不跑后处理。 */
    public static Params take() {
        Params p = pending;
        pending = null;
        configured = false;
        return p;
    }

    private GlowBloom() {
    }
}
