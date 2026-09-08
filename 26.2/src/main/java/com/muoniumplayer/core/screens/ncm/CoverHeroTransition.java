package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.texture.ITextureObject;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.render.skija.SkijaEffects;

/**
 * 控制栏小封面 到 全屏歌词页大封面 的接力转场（shared element / hero transition）。
 *
 * <p>整条转场是<b>时间驱动</b>的：只有一个 {@code t ∈ [0,1]} 的时钟，所有几何与不透明度都是
 * {@code t} 的纯函数。这两点都是刻意的，不是风格问题：
 * <ul>
 *   <li>原来那套 {@code Interpolations.interpolate} 是「指数逼近 + 靠 min/max 夹住终点」，
 *       起步和收尾一样软、还跟帧率挂钩，非线性曲线根本无从谈起，观感就是一层匀速淡入；</li>
 *   <li>纯函数意味着<b>中途反向是连续的</b>：进场没播完就按 ESC，封面从当前位置原路折回，
 *       不会因为退场换了另一组曲线而跳一下。退场的「更利落」由更短的时长给，不靠换曲线。</li>
 * </ul>
 *
 * <p>分层节奏（进场自下而上依次到场，退场因为是同一组纯函数而自动反序）：
 * 页面底色 → 接力封面 → 面板内容 → 左栏信息与传送带 → 歌词。四层各有自己的时间窗。
 *
 * <p>封面为什么必须拿到面板外面画：它要同时跨越「控制栏坐标系」和「歌词页坐标系」，还要盖在
 * 压暗层之上；留在 {@code MusicLyricsPanel} 里既跨不过去，也会被面板自己的 {@code contentScale}
 * 二次缩放。因此飞行期间面板那张封面让位（{@link #suppressPanelCover()}）、控制栏那张隐藏
 * （{@link #hidesSmallCover()}），全程屏幕上只有一张封面。
 */
public final class CoverHeroTransition implements SharedRenderingConstants, SharedConstants {

    /** 形状绘制是接口上的 default 方法，静态调用需要一个实例；除此之外这个对象没有状态。 */
    private static final CoverHeroTransition GFX = new CoverHeroTransition();

    private CoverHeroTransition() {
    }

    private static final int IDLE = 0, OPENING = 1, OPEN = 2, CLOSING = 3;

    /**
     * 进场比退场慢一档：展开要让人看清封面走的那条弧，收起只要交代清楚「回去了」。
     *
     * <p>这是<b>历史基准值</b>，实际时长再除以用户配置的「界面切换动画速度」倍率
     * （{@link HudConfig#playerUiSwitchSpeed}，当前默认 3.0）：默认观感即 520/3 ≈ 173ms
     * 进场、120ms 退场；想要老节奏把倍率调回 1.0 即可。</p>
     */
    private static final double OPEN_MS = 520.0;
    private static final double CLOSE_MS = 360.0;

    private static int state = IDLE;

    /** 1 = 完全展开。全部曲线都读它，一帧只推进一次（{@link #tick(boolean)}）。 */
    private static double t = 0.0;

    private static boolean hasHero = false;
    private static double startX, startY, startSize, startRadius;
    private static double targetX, targetY, targetSize, targetRadius;

    /** 本帧曲线缓存。tick 一次，多个图层复用，避免同一帧重复反解贝塞尔。 */
    private static double cachedMotion = 0.0;
    private static double cachedMotionLagging = 0.0;
    private static double cachedScrimAlpha = 0.0;
    private static double cachedContentAlpha = 0.0;
    private static double cachedChromeFade = 0.0;
    private static double cachedLyricsFade = 0.0;
    private static double cachedMainScale = 1.0;

    /** 接力封面在这一首歌内的纹理缓存，避免每帧查两次 ConcurrentHashMap。 */
    private static Music cachedTextureMusic;
    private static ITextureObject cachedTexture;

    /**
     * 两条缓动。括号里的数字是<b>规范值</b>（{@code MOTION} = Material 3 emphasized，
     * {@code MOTION_LAGGING} = Material 2 standard），不要凭手感改：项目里其它动画迟早要统一到
     * 同一套口径上，改了这里就散了。
     *
     * <p>{@code MOTION} 承担绝大多数通道；{@code MOTION_LAGGING} 只用在接力封面的水平轴上，
     * 它在前半程明显慢一档（t=0.25 处 0.24 对 0.61），同一时刻两者的差值就是弧线的弯度来源。
     * 之所以换曲线而不是简单地给 X 轴加一段延迟，是因为延迟会造出一段"X 完全不动"的死区，
     * 起步那一下看起来像卡了一帧。
     */
    private static final TransitionCurveCache.CachedBezier MOTION = TransitionCurveCache.MOTION;
    private static final TransitionCurveCache.CachedBezier MOTION_LAGGING = TransitionCurveCache.MOTION_LAGGING;

    // ------------------------------------------------------------------ 生命周期

    /**
     * 从控制栏小封面开始一次接力。必须在 {@code new MusicLyricsPanel(...)} <b>之前</b>调用：
     * 面板构造函数会兜底启动时钟，起点得先就位。
     *
     * @param size 小封面这一帧的边长（正方形）；{@code <= 0.5} 视为拿不到起点，退化成纯淡入
     */
    public static void open(double x, double y, double size, double radius) {
        startX = x;
        startY = y;
        startSize = size;
        startRadius = radius;
        hasHero = size > .5;
        // 第一帧还没有人回报过落点（面板要等 renderControlsPart 才算得出来），这里先按几何公式
        // 自己推一遍。少了这一步，进场第一帧封面会朝上一次转场留下的旧落点飞。
        captureTarget();
        // 点击入口永远是「用户要一次新的进场」。走到这里还停在 OPEN 只可能是整屏被丢弃后的残留
        // 终态 —— 面板全展开时控制栏根本不渲染、点不到这里 —— 此时 t 卡在 1，不重置的话这次
        // 打开会直接跳到终态、没有动画。CLOSING 途中重开则保留 t，封面从当前位置折返。
        if (state == OPEN) t = 0.0;
        beginOpen();
        refreshFrameCache();
    }

    /**
     * 没有接力起点时的兜底：只走分层淡入，不画飞行封面。由 {@code MusicLyricsPanel} 构造函数
     * 调用，保证任何创建路径都有进场时钟 —— 面板的 alpha 现在完全由这里给，缺了它会永远是 0。
     */
    public static void ensureOpen() {
        if (state == IDLE) hasHero = false;
        beginOpen();
    }

    private static void beginOpen() {
        // OPEN 是终态，重复调用不该把 t 打回去。CLOSING 途中重开则保留 t，封面从当前位置折返。
        if (state != OPEN) state = OPENING;
    }

    public static void beginClose() {
        if (state == OPENING || state == OPEN) state = CLOSING;
    }

    /**
     * 每帧推进一次时钟，必须在本帧任何一层读曲线之前调用。
     *
     * @param panelAlive 歌词页对象这一帧还在不在。不在了却停在展开态，说明面板被别的路径丢掉了
     *                   （换屏、异常），转场自己收尾；否则这些 static 状态会卡在「已展开」，
     *                   下次打开会直接是终态、看不到接力动画。
     */
    public static void tick(boolean panelAlive) {
        if (!panelAlive && (state == OPENING || state == OPEN)) state = CLOSING;

        // 倍率与内容页切换同源（「界面切换动画速度」）；下界钳在配置区间下限，防止
        // 手改配置文件把时长除成 0。
        double speed = Math.max(0.30, HudConfig.playerUiSwitchSpeed);
        double openMs = OPEN_MS / speed;
        double closeMs = CLOSE_MS / speed;

        if (state == OPENING) {
            t += step() / openMs;
            if (t >= 1.0) {
                t = 1.0;
                state = OPEN;
            }
        } else if (state == CLOSING) {
            t -= step() / closeMs;
            if (t <= 0.0) {
                t = 0.0;
                state = IDLE;
            }
        }
        refreshFrameCache();
    }

    /**
     * 本帧步进的毫秒数。
     *
     * <p>{@code getFrameDeltaTime()} 的单位是 10ms（见 {@code Interpolations#calcFrameDelta}），
     * 并且它在帧间隔超过 200ms 时会把值压到 0.01 —— 掉大帧的那一瞬间动画原地停住而不是跳过去，
     * 正是要的行为。这里再夹一道 40ms 上限：25fps 以下宁可让转场整体变慢，也不要一帧跨掉
     * 小半条曲线（那就是「一顿一顿」的由来）。
     */
    private static double step() {
        return Math.min(40.0, Math.max(0.0, RenderSystem.getFrameDeltaTime() * 10.0));
    }

    // ------------------------------------------------------------------ 状态查询

    public static boolean isRunning() {
        return state == OPENING || state == CLOSING;
    }

    /** 有接力封面正在飞：此时小封面与面板封面都必须让位，否则同一张封面会同时出现在两处。 */
    public static boolean isFlying() {
        return hasHero && isRunning();
    }

    public static boolean isFinished() {
        return state == IDLE;
    }

    public static boolean hidesSmallCover() {
        return isFlying();
    }

    public static boolean suppressPanelCover() {
        return isFlying();
    }
    // ------------------------------------------------------------------ 分层不透明度

    /** 歌词页底色。领先所有内容一档：先把主界面压下去，内容才好在暗底上浮现。 */
    public static double scrimAlpha() {
        return cachedScrimAlpha;
    }

    /** 歌词页整体内容（含模糊背景）的不透明度，也就是 {@code MusicLyricsPanel.alpha}。 */
    public static double contentAlpha() {
        return cachedContentAlpha;
    }

    /** 左栏信息块 / 进度条 / 音量 / 控制键：等封面快落地了再到场，免得和它糊在一起。 */
    public static double chromeFade() {
        return cachedChromeFade;
    }

    /** 歌词：整条转场最后一层。 */
    public static double lyricsFade() {
        return cachedLyricsFade;
    }

    /**
     * 播放器主界面在转场期间的缩放。
     *
     * <p>是<b>放大</b>而不是缩小，这一点反直觉但必须如此：Stencil 裁的是面板矩形，把主界面缩小
     * 会在圆角外框内侧漏出一圈游戏画面（面板底色自带液态玻璃透明度，补不回来）。放大只会被裁掉，
     * 永远不漏。观感上它和歌词页的 {@code contentScale}（.952 到 1）连成一次「穿过图层往里推」
     * 的镜头运动，比缩小更贴合「小封面胀成大封面」这件事。
     */
    public static double mainScale() {
        return cachedMainScale;
    }

    private static void refreshFrameCache() {
        cachedMotion = MOTION.at(t);
        cachedMotionLagging = MOTION_LAGGING.at(t);
        cachedScrimAlpha = MOTION.at(sub(t, .05, 1.0));
        cachedContentAlpha = MOTION.at(sub(t, .16, 1.0));
        cachedChromeFade = MOTION.at(sub(t, .36, .98));
        cachedLyricsFade = MOTION.at(sub(t, .48, 1.0));
        cachedMainScale = 1.0 + .016 * cachedMotion;
    }

    // ------------------------------------------------------------------ 接力封面

    /**
     * 面板每帧回报大封面这一帧真正落在屏幕上的矩形（<b>已经乘过 contentScale</b>：接力封面画在
     * 面板的缩放之外，两边必须在同一个坐标空间里才对得上）。
     */
    public static void setTarget(double x, double y, double size, double radius) {
        targetX = x;
        targetY = y;
        targetSize = size;
        targetRadius = radius;
    }

    public static void render() {
        if (!isFlying()) return;

        // 弧线运动：X 与 Y 走两条不同的曲线，两轴不同步才有弧。竖直轴用较快的 MOTION 先到位、
        // 水平轴用慢一档的 MOTION_LAGGING 跟上 —— 小封面在控制栏左下、大封面在面板上方，主位移
        // 是竖直方向，于是轨迹是「先抬起来再横过去」的弓形（典型面板下峰值离直线约 27px），
        // 符合 Material 对「向上跨越」的规定。退场时 t 反向递减，同一条弧自动变成「先横过去
        // 再落下来」，正是「向下跨越」该有的方向 —— 这就是坚持纯函数换来的便宜。
        // 尺寸跟竖直轴同一条曲线：封面是一边升一边长大，两者错开会看出「先飞后胀」。
        double fx = cachedMotionLagging;
        double fy = cachedMotion;
        double fs = fy;

        // 落地微过冲：两端严格为 0，峰值约 2.4% 落在 t≈0.68。那时尺寸曲线已经基本到位，读起来
        // 是「停住之后轻轻呼了一口气」，而不是一路弹过去。
        double bulge = 1.0 + Math.sin(Math.PI * t) * Math.pow(t, 1.7) * .055;

        double size = lerp(startSize, targetSize, fs) * bulge;
        double radius = lerp(startRadius, targetRadius, fs);
        // 按中心插值再回推左上角：直接插左上角的话，过冲会变成从左上角单向长出去。
        double x = lerp(startX + startSize * .5, targetX + targetSize * .5, fx) - size * .5;
        double y = lerp(startY + startSize * .5, targetY + targetSize * .5, fy) - size * .5;

        // 阴影：起点的小封面本来没有阴影，落点的面板封面是 alpha .4 的 bloom，所以要淡入。
        // 飞行中途额外抬高、外扩一点，这是「卡片离开桌面」的高度暗示；两个附加量在两端都归零，
        // 落地那一帧和面板自己画的那圈阴影完全重合，交接看不出来。
        // 转场快路径：飞行封面本体保留，阴影 bloom（一次 saveLayer + Gaussian blur）跳过。
        // 阴影只服务半秒钟的飞行过程；动画结束后面板自己的 bloom 会立即恢复完整观感。

        ITextureObject tex = texture();
        if (tex == null) return;

        // 全程 alpha 1：起点小封面本来是不透明的，落点面板封面在 t=1 时也是不透明的，
        // 中间再淡一下反而会露出底下的东西。
        api.getGLStateManager().color(1, 1, 1, 1);
        api.getGLStateManager().disableAlpha();
        api.getGLStateManager().bindTexture(tex.getGlTextureId());
        tex.linearFilter();
        GFX.roundedRectTextured(x, y, size, size, radius, 1f);
    }

    /**
     * 优先用大封面：飞行途中换分辨率会看到一次清晰度跳变。宁可全程拿大图缩小画 —— 开了
     * {@code linearFilter}，45px 的小封面和缩到 45px 的大封面肉眼无差。
     */
    private static ITextureObject texture() {
        Music music = CloudMusic.currentlyPlaying;
        if (music == null) return null;
        if (cachedTexture != null && music == cachedTextureMusic) return cachedTexture;

        TextureManager manager = TextureManager.getInstance();
        ITextureObject tex = manager.getTexture(
                CloudMusic.preferredCoverLocation(music, music.getCoverLocation()));
        if (tex == null) tex = manager.getTexture(music.getSmallCoverLocation());
        if (tex != null) {
            cachedTextureMusic = music;
            cachedTexture = tex;
        }
        return tex;
    }

    /**
     * 按几何公式自己推一遍大封面的落点，只为第一帧不飞错地方。
     *
     * <p>这段必须和 {@code MusicLyricsPanel#renderControlsPart} 里算封面矩形那几行保持一致；
     * 从第二帧起那边会用真实布局把结果盖掉（见 {@link #setTarget}），所以这里的误差不会累积。
     */
    private static void captureTarget() {
        NCMScreen screen = NCMScreen.getInstance();
        double width = screen.getPanelWidth();
        double height = screen.getPanelHeight();
        double max = LyricsPanelGeometry.coverSizeMax(width, height);
        boolean pausing = CloudMusic.player == null || CloudMusic.player.isPausing();
        double size = pausing ? max * .8 : max;
        // 面板回报的落点（setTarget）是绕面板中心乘过 contentScale 的。contentScale 现在恒为 1
        // （入场已定稿为只走不透明度），这步换算是恒等，但两边必须继续走同一个函数：
        // 将来谁恢复了整体缩放，落点也不会先错一帧。
        double scale = LyricsPanelGeometry.contentScale((float) contentAlpha());
        double panelCenterX = screen.getPanelX() + width * .5;
        double panelCenterY = screen.getPanelY() + height * .5;
        targetX = panelCenterX + (screen.getPanelX() + width * .24 - size * .5 - panelCenterX) * scale;
        targetY = panelCenterY + (screen.getPanelY() + height * .30 - size * .575 - panelCenterY) * scale;
        targetSize = size * scale;
        targetRadius = 7.0 * (size / max) * scale;
    }

    private static double lerp(double from, double to, double fraction) {
        return from + (to - from) * fraction;
    }

    /** 把 {@code t} 的一段子区间重新拉伸成 0..1，用来给各层排先后。 */
    private static double sub(double value, double from, double to) {
        if (value <= from) return 0.0;
        if (value >= to) return 1.0;
        return (value - from) / (to - from);
    }

}