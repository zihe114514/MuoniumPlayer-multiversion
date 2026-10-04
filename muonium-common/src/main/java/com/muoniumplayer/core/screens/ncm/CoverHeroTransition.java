package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.texture.ITextureObject;
import com.muoniumplayer.core.settings.HudConfig;

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
 * <p>进场只使用一条连续曲线：页面与歌词逐渐显现，接力封面沿最短路径落位；退场沿同一条曲线反向返回。
 * 不叠加额外的弧线、过冲、分层延迟或整页缩放，优先保证低配机器上的帧时间稳定。
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

    /** 1 = 完全展开。所有动画通道共享这一条时间轴。 */
    private static double t = 0.0;

    private static boolean hasHero = false;
    private static double startX, startY, startSize, startRadius;
    private static double targetX, targetY, targetSize, targetRadius;

    /** 本帧唯一运动值。所有图层与 Hero 都复用它，避免分层时间轴造成视觉断层。 */
    private static double cachedMotion = 0.0;
    private static double cachedScrimAlpha = 0.0;
    private static double cachedContentAlpha = 0.0;
    private static double cachedChromeFade = 0.0;
    private static double cachedLyricsFade = 0.0;

    /** 接力封面在这一首歌内的纹理缓存，避免每帧查两次 ConcurrentHashMap。 */
    private static Music cachedTextureMusic;
    private static ITextureObject cachedTexture;

    /**
     * 转场只保留一条 emphasized cubic-bezier：起步柔和、中段连续、末尾自然收住。
     * Hero 的位置、尺寸、圆角、页面透明度全部读取同一个值，避免层级错位。
     */
    private static final TransitionCurveCache.CachedBezier MOTION = TransitionCurveCache.MOTION;

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

    /** 转场期间不再对整页做额外缩放，避免文字和玻璃层重复采样。 */
    public static double mainScale() {
        return 1.0;
    }

    private static void refreshFrameCache() {
        cachedMotion = MOTION.at(t);
        // 统一时间轴：少一组曲线查询，也让背景、控制区和歌词同时连续出现。
        cachedScrimAlpha = cachedMotion;
        cachedContentAlpha = cachedMotion;
        cachedChromeFade = cachedMotion;
        cachedLyricsFade = cachedMotion;
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

        // 单一 eased 直线：没有水平滞后、弧线和过冲，封面始终沿最短路径连续移动。
        double motion = cachedMotion;
        double size = lerp(startSize, targetSize, motion);
        double radius = lerp(startRadius, targetRadius, motion);
        double x = lerp(startX + startSize * .5, targetX + targetSize * .5, motion) - size * .5;
        double y = lerp(startY + startSize * .5, targetY + targetSize * .5, motion) - size * .5;

        // Hero 只绘制封面本体。转场期间不额外创建阴影、模糊或离屏层，
        // 让 GPU 只承担一次圆角纹理绘制，落地后再恢复页面自身的视觉效果。

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
        // 问的是「现在有没有在响」，不是「内部引擎在不在」：镜像态下出声的是外部播放器，
        // 照 player 判会让封面在外部正常播放时也缩在暂停位（内部播放时两种写法逐字等价）。
        boolean pausing = !CloudMusic.isAudibleNow();
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