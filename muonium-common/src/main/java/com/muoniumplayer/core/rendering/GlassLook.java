package com.muoniumplayer.core.rendering;

import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.screens.ncm.PlayerIslandTransition;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.render.skija.GlassBackdrop;
import com.muoniumplayer.render.skija.GlassEdgeBatch;
import com.muoniumplayer.render.skija.GlassRefraction;
import com.muoniumplayer.render.skija.GlassLiquidSurface;
import com.muoniumplayer.render.skija.SkijaEffects;

/**
 * 真玻璃的一层「该不该上、上多浓」的判断，四处界面共用同一个口径。
 *
 * <h2>为什么单独立一个类</h2>
 * 播放器面板、灵动岛、桌面歌词卡片、三套 HUD 编辑器的面板，都要问同样三件事：
 * 主题是不是琉璃、这一面的开关有没有开、背景糊多浓 / 底色压多薄。判据散在四处，
 * 迟早出现「岛上有玻璃、面板上没有」这种一半生效的怪相；参数换算散在四处，
 * 滑块就会变成只对某一个界面有效。所以这里只留一份口径，调用点各剩两三行。
 *
 * <h2>两个维度不能混</h2>
 * <ul>
 *   <li><b>主题</b>那一维是<b>动画中的强度</b>（{@link NCMTheme#getTrueGlassAmount()}，
 *       0→1 平滑过渡），不是「当前是不是琉璃」的布尔。切走时要能淡出，
 *       而淡出这 0.95 秒里背景还得继续采（见 {@code GlassBackdrop.wanted()}）。</li>
 *   <li><b>生效面</b>那一维是用户开关，硬开硬关：关掉就是不要这一块玻璃，没有什么过渡可言。</li>
 * </ul>
 * 两者相乘就是 {@link #amount(Surface)}：为 0 时 {@link #paintFrost} 直接返回 {@code false}，
 * 调用点于是原样走它自己那套渐变高光。默认那十四套主题下这个类不会画出任何东西。
 *
 * <h2>浓度滑块为什么作用在底色上</h2>
 * 「玻璃浓度」不是给背景加一层灰，而是决定<b>主题底色还剩多少</b>：
 * {@link #tintedAlpha} / {@link #tintedArgb} 按浓度把面板底色压薄。于是
 * 浓度 = 1 恰好等于压薄系数 = 1，也就是完全退回改动之前那张实色底——
 * 用户把滑块推到顶就一定看得见「原来的样子」，这比任何说明都有用。
 *
 * <h2>根底与内层是两档，不是一档</h2>
 * 一块面板的深色从来不只一层：根底之上还有内容区底、侧栏底、曲目行底、输入框底……
 * 它们每一张都按 {@link #tintedAlpha} 压一次，等于把同一份浓度乘上好几遍，
 * 叠到最后仍然是一块不透的深色（2026-09-30 玩家反馈「页面内部有暗色部分」的根因：
 * 面板边缘透出了糊过的天空，页面内部那些层根本没接玻璃，还是 100% 实色）。
 * 所以内层走 {@link #washAlpha}：它比根底薄得多，让「整页有多暗」由根底那一层
 * 说了算，内层只留下「这一块是另一个区域」的那点相对差。
 */
public final class GlassLook {

    /** 四处可以各自决定要不要用真背景的界面。 */
    public enum Surface {
        /** 播放器主面板（含它自己的二级弹窗，因为它们共用同一块底）。 */
        PLAYER,
        /**
         * 顶部灵动岛。八款样式里有七款吃这一档（经典胶囊、通透玻璃、紧凑状态、浮层卡片、
         * 系统通知、音乐聚焦共用 {@code drawIsland} 那一步铺底，液态玻璃在自己的 surface 里
         * 铺同一档）；回弹胶囊除外——那是作者另一份稿子的 1:1 复刻，纯色平面是它造型的一部分。
         */
        ISLAND,
        /** 桌面歌词卡片（Blossom 那套毛玻璃面板）。 */
        DESKTOP_LYRIC,
        /** 三套 HUD 编辑器自己的面板。 */
        EDITORS
    }

    private GlassLook() {
    }

    /**
     * 这一面此刻该用多浓的真玻璃。0 表示不用——调用方保留自己原来的画法。
     *
     * <p>不在这里读 {@code SkijaHost} 有没有背景：有没有是每帧变的事（第一次采集要等
     * 一帧），判据放这儿会把「主题没开」和「这一帧还没采到」混成一个值。
     * {@link #paintFrost} 会把后者作为 {@code false} 返回给调用点。</p>
     *
     * <h2>帧率吃紧时也不再摘玻璃</h2>
     * 真玻璃恒按主题强度画。历史上的「帧率不够自动摘玻璃」档（含 250ms 斜坡）随帧率
     * 分档一起移除了：玻璃一摘，整块面板就从「糊过的亮画面」切回「原来的实色深底」，
     * 2026-09-30 玩家报的「有的时候会闪烁，表现为暗一下然后恢复」正是那个亮度台阶；
     * 而它省下的帧预算，如今由各条链路的几何门槛（转场内容 alpha 门槛、交接窗、
     * 采集节流、预糊档复用）更精准地省出来了——不碰观感，只砍看不见的重活。
     *
     * <p>每帧的 blit 拷贝不在这一档里（那是微秒级的缩小复制，且淡回来时必须有画面可用）；
     * 贵的是逐面板与逐元素那一层模糊，正是各条几何门槛掐掉的东西。</p>
     */
    public static float amount(Surface surface) {
        // 手机档：真玻璃整体禁用（见 GlassMobileTier 类注释——折射透镜在 GL 翻译层下扛不住，
        // 降参数组合也不稳）。返回 0 后各面走回自己原来的假玻璃 / 实色画法，
        // 压薄 / 融合 / 边缘光这些 amount 的消费方也一并归零，不需要各自再判。
        if (GlassMobileTier.active()) {
            return 0f;
        }
        if (!toggleOf(surface)) {
            return 0f;
        }
        float theme = NCMTheme.getTrueGlassAmount();
        return theme > 0.001f && GlassLiquidSurface.available() ? theme : 0f;
    }
    // 「帧率不够时自动摘玻璃」（HudConfig.glassAutoDegrade）的自动判定者——按帧率分三档
    // 的降载系统——已整体移除：帧率分档被证明管错了对象（摘玻璃的闪比它省下的帧更扎眼），
    // 它的降载职责已由各条链路的几何门槛（转场内容 alpha 门槛、交接窗、采集节流）接管。
    // 该开关的存档字段与设置项保留只是兼容旧存档，不再有任何读取方。

    /**
     * 灵动岛交接专用的低成本玻璃表面。
     *
     * <p>交接时不使用液态折射 shader，而是直接贴已经预糊好的背景纹理。这样保留
     * 玻璃的明暗和背景透入效果，但不把整面播放器交给高成本的逐像素折射。</p>
     *
     * <p>唯一调用方是 {@code PlayerIslandTransition#renderSurface()}：转场全程与落定后的
     * 材质交接窗内它都以<b>全浓度</b>画在播放器透镜的下面（覆盖式交接 —— 透镜按
     * {@code PlayerIslandTransition#surfaceHandoff()} 淡入、铺满后自然盖住这一层），
     * handoff 走完后整个 renderSurface 停画，这里随之停用。</p>
     */
    public static boolean paintPlayerTransitionSurface(double x, double y,
                                                        double width, double height,
                                                        double radius, float opacityScale) {
        float amount = amount(Surface.PLAYER) * Math.max(0f, Math.min(1f, opacityScale));
        if (amount <= 0.001f) {
            return false;
        }
        // 预糊贴图的成本远低于液态折射；限制 sigma 避免转场期间临时创建过大的模糊档位。
        float sigma = Math.min(blurSigma(Surface.PLAYER), 12f);
        return SkijaEffects.frostedBackdropCached(x, y, width, height, radius, sigma, amount);
    }
    /**
     * 兼容的历史方法名；现在绘制整面透镜折射，不再绘制磨砂底。
     *
     * @return 真的铺上了；{@code false} = 这一面不该有玻璃、或者这一帧根本没有背景图，
     *         调用方应当紧接着画自己原来那套假玻璃
     */
    public static boolean paintFrost(Surface surface, double x, double y,
                                     double width, double height, double radius) {
        return paintFrost(surface, x, y, width, height, radius, 1.0f);
    }

    /**
     * 同上，只多一个「这一面自己淡到哪儿了」的系数。
     *
     * <p>坐标一律是<b>调用点此刻的画布局部单位</b>——灵动岛的尺寸缩放、编辑器的设计空间、
     * 播放器的窗口自适应布局都不用在这里报倍率：玻璃采哪一块背景，问的是 Skia 当前矩阵
     * （见 {@code GlassBackdrop.mapping(Canvas)}）。以前这些倍率是各调用点各自算着传的，
     * 传错的症状是「透出屏幕别处的画面」，而底图糊着，截图上读不出来。</p>
     *
     * @param opacityScale 这一面此刻的<b>自身</b>淡入系数（转场、面板出场动画、界面 layerAlpha）。
     *        主题那一维的强度已经含在 {@link #amount} 里了，两者相乘才是最终浓度。不乘的话
     *        编辑器一入场就会先看到一整块不透明的磨砂底，晚它 0.2 秒才落下来的面板反而盖在上面。
     */
    public static boolean paintFrost(Surface surface, double x, double y,
                                     double width, double height, double radius,
                                     float opacityScale) {
        // 灵动岛交接期间只画轻量的实色表面。液态玻璃会触发整面背景采样/折射，
        // 其成本远高于普通圆角矩形，而且在移动中的裁剪窗口里观感收益很小。
        if (PlayerIslandTransition.isRunning()) {
            return false;
        }
        float amount = amount(surface) * Math.max(0f, Math.min(1f, opacityScale));
        if (amount <= 0.001f) {
            return false;
        }
        boolean topmost = surface == Surface.ISLAND;
        boolean previousTopmost = topmost ? GlassBackdrop.pushTopmost() : false;
        try {
            // The topmost Screen capture is already a downscaled, linear-filtered snapshot.
            // Do not rebuild the full-screen blur cache while alternating base and island passes.
            float sigma = topmost ? 0f : blurSigma(surface);
            return SkijaEffects.liquidBackdrop(x, y, width, height, radius, sigma, amount);
        } finally {
            if (topmost) {
                GlassBackdrop.popTopmost(previousTopmost);
            }
        }
    }

    /**
     * 弹窗那一档：贴<em>压暗过的</em>真背景。
     *
     * <p>与 {@link #paintFrost} 同一套开关与主题强度，只是多一层黑纱——那张玻璃背后是
     * 我们自己的面板加一层遮罩，而这两样都不在采集里，不压一下就会比周围还亮。
     * 为什么要有这一档、纱的浓度跟着谁，都写在
     * {@code SkijaEffects.dimmedBackdrop} 的注释里。</p>
     */
    public static boolean paintSheetFrost(Surface surface, double x, double y,
                                          double width, double height, double radius,
                                          float opacityScale) {
        // 灵动岛交接期间只画轻量的实色表面。液态玻璃会触发整面背景采样/折射，
        // 其成本远高于普通圆角矩形，而且在移动中的裁剪窗口里观感收益很小。
        if (PlayerIslandTransition.isRunning()) {
            return false;
        }
        float amount = amount(surface) * Math.max(0f, Math.min(1f, opacityScale));
        if (amount <= 0.001f) {
            return false;
        }
        boolean topmost = surface == Surface.ISLAND;
        boolean previousTopmost = topmost ? GlassBackdrop.pushTopmost() : false;
        try {
            float sigma = topmost ? 0f : blurSigma(surface);
            return SkijaEffects.dimmedLiquidBackdrop(x, y, width, height, radius, sigma, amount);
        } finally {
            if (topmost) {
                GlassBackdrop.popTopmost(previousTopmost);
            }
        }
    }

    /** Compatibility call: directional edges are already inside the full-surface lens. */
    public static void paintEdge(Surface surface, double x, double y, double width, double height,
                                 double radius, float opacityScale) {
        // Retained for callers: the full-surface lens owns the edge now.
    }

    public static void paintEdge(Surface surface, double x, double y, double width, double height,
                                 double radius) {
        paintEdge(surface, x, y, width, height, radius, 1.0f);
    }

    /**
     * 边缘折射的强度，读的还是「玻璃边缘光」那一档：折射与高光在物理上就是同一件事
     * ——都是贴着轮廓的那圈——所以不给它单开滑块（多一个滑块就多一处「用户不知道哪个管哪」）。
     * 推到 0 时假玻璃高光与折射一起没有，推到 1 以上只加高光不加折射（强度封顶 1）。
     */
    public static float refractionStrength(Surface surface) {
        float glow = HudConfig.glassEdgeGlow;
        if (!(glow > 0f)) {
            return 0f;   // NaN 与负数都当关掉
        }
        return amount(surface) > 0.001f ? Math.min(1f, glow) : 0f;
    }

    /** 可选的背景柔化强度；0 仍然保留折射，默认只做亚像素级柔化。 */
    public static float blurSigma() {
        return GlassBackdrop.blurSigma();
    }

    /**
     * <b>这一面</b>该糊多狠 = 全局档 × 这一面的倍率。
     *
     * <p>倍率读的是 {@code HudConfig.glassBlur*} 那四档，理由与默认值不等都写在那儿。
     * 关键是<b>贴背景与折边缘必须问同一个值</b>：两头各读一次配置就会出现
     * 「面板糊成一片、元素边缘却折着清晰画面」这种一半生效的怪相（历史上真出现过，
     * 见 {@code GlassBackdrop.blurSigma()} 的注释）。</p>
     */
    public static float blurSigma(Surface surface) {
        return blurFor(surface, GlassBackdrop.blurSigma());
    }

    /**
     * {@link #blurSigma(Surface)} 的那一步乘法，单独拎出来是因为钳位要能在 JVM 里钉：
     * 倍率被写坏（0、负数、NaN、手改 json 写成 9）时的症状是「这一面整层没有玻璃」
     * 或者「糊到认不出背后是什么」，两种都不该出现。
     *
     * <p>全局档为 0 时结果就是 0，<b>倍率再大也不糊</b>——那是有意的：「背景模糊」推到 0
     * 是用户明确说了不要糊，四面的倍率不能翻案。</p>
     */
    static float blurFor(Surface surface, float globalSigma) {
        if (!(globalSigma > 0f)) {
            return 0f;   // 含 NaN
        }
        float factor;
        switch (surface) {
            case ISLAND:
                factor = HudConfig.glassBlurIsland;
                break;
            case DESKTOP_LYRIC:
                factor = HudConfig.glassBlurDesktopLyric;
                break;
            case EDITORS:
                factor = HudConfig.glassBlurEditors;
                break;
            case PLAYER:
            default:
                factor = HudConfig.glassBlurPlayer;
                break;
        }
        if (!(factor > 0f)) {
            factor = 1f;   // NaN / 0 / 负数：退回与全局同档，而不是把这一面的玻璃整层弄没
        }
        float sigma = globalSigma * Math.min(2f, factor);
        return sigma <= 64f ? sigma : 64f;
    }

    /**
     * 开一组<b>融合边缘</b>：从这里到 {@link #endFusion()} 之间画的所有玻璃边缘，
     * 收尾时由一趟着色器按<em>一整张距离场</em>画完，于是挨得近的两块会在中间鼓出一道颈
     * （两滴水银碰到一起那个样子）。
     *
     * <p>为什么要调用点自己括组，而不是整帧一批：我们的边缘必须压在自己那块底色之上、
     * 别人那块底色之下。攒到帧末统一画，后落下来的弹窗就会被先画的按钮边缘光打穿。
     * 组里各块控件<em>可以</em>各自带进场位移与缩放——每条登记都烘回开组那一刻那套坐标系
     * （见 {@code GlassEdgeBatch#bakeIntoGroup}），取样平移则一组一份，两件事的代价与取舍
     * 都写在那边的类注释里。</p>
     *
     * <p>没开成功（这一面没玻璃、这一帧没背景、矩阵带旋转）时什么都不做，
     * 中间的 {@link #paintEdge} 与 {@link #paintElementEdge} 自动退回逐矩形各画各的。</p>
     *
     * @param smoothing 融合半径，画布局部单位：两块间隙小于它才连得起来。0 等于不开融合。
     */
    public static void beginFusion(Surface surface, float smoothing) {
        if (PlayerIslandTransition.isRunning()) {
            GlassEdgeBatch.clear();
            return;
        }
        if (amount(surface) <= 0.001f || refractionStrength(surface) <= 0.002f
                || !(smoothing > 0f)) {
            GlassEdgeBatch.clear();
            return;
        }
        io.github.humbleui.skija.Canvas canvas = com.muoniumplayer.render.skija.SkijaGfx.canvas();
        GlassBackdrop.Mapping map = canvas == null ? null : GlassBackdrop.mapping(canvas);
        if (map == null) {
            GlassEdgeBatch.clear();
            return;
        }
        GlassEdgeBatch.begin(smoothing, map.offsetX / map.scaleX, map.offsetY / map.scaleY,
                deviceMatrix(canvas));
    }

    /** 开组这一刻的画布矩阵（行主序 9 位）。读不到就返回 null，融合退回「组内不换矩阵」那条老路。 */
    private static float[] deviceMatrix(io.github.humbleui.skija.Canvas canvas) {
        try {
            return canvas.getLocalToDeviceAsMatrix33().getMat();
        } catch (Throwable failure) {
            return null;
        }
    }

    /**
     * 融合半径，单位是<em>画布局部像素</em>。0 = 不融合，各块玻璃各画各的边。
     *
     * <p>两块之间的间隙要小于它两倍才连得出颈（soft-min 把表面往外拉 {@code k/4}），
     * 所以歌曲列表那种「行与行不留缝」的排布也得推到十几才看得见效果；
     * 推太大则整排控件会糊成一坨，这一档故意留给用户自己拧。</p>
     *
     * <p>只有一档而不是每面一档：融合只在<em>挨着的一排</em>上有意义，而哪几排该融合是
     * 接线时逐个容器点名的（见 {@code AbstractWidget#setGlassFusion}），生效面开关与主题
     * 强度也在那一刻由各面自己判。再给每一面配一个半径，就成了「四个滑块里两个永远用不上」。</p>
     */
    public static float fusionRadius() {
        if (PlayerIslandTransition.isRunning()) {
            return 0f;
        }
        float radius = HudConfig.glassFusion;
        if (!(radius > 0f)) {
            return 0f;   // NaN 与负数都当关掉
        }
        return radius > 32f ? 32f : radius;
    }

    /** 收尾这一组并画掉。没开着时什么都不做。 */
    public static void endFusion() {
        GlassEdgeBatch.flush();
    }

    /** 现在有没有开着的一组（给调用点与探针看）。 */
    public static boolean fusing() {
        return GlassEdgeBatch.isOpen();
    }

    public static void paintElementEdge(Surface surface, double x, double y,
                                        double width, double height, double radius,
                                        float opacityScale) {
        // Retained for callers: the full-surface lens owns the edge now.
    }

    /** No additional solid white border over the reference lens. */
    public static float elementRim(Surface surface, float opacityScale) {
        return 0f; // No additional frame painted over the lens.

    }

    /** 玻璃浓度：0 = 底色完全让位给真画面，1 = 完全退回原来的实色面板。 */
    public static float tint() {
        return clamp01(HudConfig.glassTintStrength);
    }

    /** 边缘高光倍率。只在真玻璃真正铺上来的时候才吃这个滑块，别的主题保持历史强度。 */
    public static float edgeGlow(Surface surface) {
        float amount = amount(surface);
        if (amount <= 0.001f) {
            return 1f;
        }
        float glow = HudConfig.glassEdgeGlow;
        if (!(glow >= 0f) || glow > 3f) {
            glow = 0.65f;   // NaN / 手写负数都退回出厂值
        }
        return 1f + (glow - 1f) * amount;
    }

    /** 一层底色的 alpha 按浓度压薄；这一面没有玻璃时原样返回。 */
    public static float tintedAlpha(Surface surface, float baseAlpha) {
        float amount = amount(surface);
        if (amount <= 0.001f || baseAlpha <= 0f) {
            return baseAlpha;
        }
        return washed(baseAlpha, amount, rootFactor(tint()));
    }

    /**
     * <b>内层</b>底色（内容区、侧栏、列表行、输入框、按钮）压薄后的 alpha。
     *
     * <p>与 {@link #tintedAlpha} 的区别只有一件事：这一层是<b>叠在根底之上</b>的。
     * 根底那一层已经把浓度算进去了，内层再按同一个系数压一遍，两块叠起来的合成不透明度
     * 会迅速回到 0.9 以上——玩家看到的就是「外面是玻璃，页面里面一块块黑斑」。
     * 所以这里在根底那一档之上再乘 {@code 浓度²}：默认浓度下内层只剩一成多，区域之间靠
     * <em>相对</em>差分辨，而整页的明暗由根底那一层独控。</p>
     *
     * <p>它同样是个乘法：传进来的 base 是该层自己原本的 alpha（含进场动画、面板转场那些
     * 已经算好的淡入值），没有玻璃时原样返回，浓度推到顶时也原样返回。</p>
     */
    public static float washAlpha(Surface surface, float baseAlpha) {
        float amount = amount(surface);
        if (amount <= 0.001f || baseAlpha <= 0f) {
            return baseAlpha;
        }
        return washed(baseAlpha, amount, interiorFactor(tint()));
    }

    /**
     * 根底那一档的剩余系数。下限 0.035：浓度拉到 0 也还留一点底，
     * 否则白字直接压在亮画面上就读不出来了。浓度 = 1 时正好是 1，也就是完全退回实色。
     */
    static float rootFactor(float tint) {
        return 0.035f + 0.965f * tint * tint;
    }

    /**
     * 内层那一档的剩余系数 = {@link #rootFactor} 再乘浓度²。
     *
     * <p>为什么是乘上去而不是另写一条曲线：这样两档在浓度 = 1 处<em>同时</em>回到 1，
     * 「滑块推到顶就是原来的样子」这句话对整页成立，不是只对外圈那层成立。
     * 浓度 = 0 时内层归零、根底留 0.035，正是「底色让位给真画面，但字还读得出」。</p>
     */
    static float interiorFactor(float tint) {
        float root = rootFactor(tint);
        return root * tint * tint;
    }

    /**
     * {@link #tintedAlpha} 与 {@link #washAlpha} 里那一步插值，单独拎出来是因为它要能在 JVM 里钉：
     * {@link #amount} 读的是主题动画时钟，真机上取不到确定值，而这里错了的代价是
     * 「玻璃整页糊死」或「白字压在亮画面上读不出」。
     *
     * @param factor 压薄后的剩余系数，由 {@link #rootFactor} / {@link #interiorFactor} 决定
     */
    static float washed(float baseAlpha, float amount, float factor) {
        float thinned = baseAlpha * factor;
        return baseAlpha + (thinned - baseAlpha) * amount;
    }

    /** {@link #tintedAlpha} 的 ARGB 版：只动 alpha 字节，颜色一位都不碰。 */
    public static int tintedArgb(Surface surface, int argb) {
        float amount = amount(surface);
        if (amount <= 0.001f) {
            return argb;
        }
        float alpha = ((argb >>> 24) & 0xFF) / 255.0f;
        int next = Math.round(tintedAlpha(surface, alpha) * 255.0f);
        if (next < 0) next = 0;
        if (next > 255) next = 255;
        return (next << 24) | (argb & 0xFFFFFF);
    }

    private static boolean toggleOf(Surface surface) {
        switch (surface) {
            case PLAYER:
                return HudConfig.glassSurfacePlayer;
            case ISLAND:
                return HudConfig.glassSurfaceIsland;
            case DESKTOP_LYRIC:
                return HudConfig.glassSurfaceDesktopLyric;
            case EDITORS:
            default:
                return HudConfig.glassSurfaceEditors;
        }
    }

    private static float clamp01(float value) {
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }
}
