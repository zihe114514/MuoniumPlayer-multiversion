package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.texture.ITextureObject;
import com.muoniumplayer.core.utils.Location;

/**
 * 歌单封面的英雄式接力（shared element transition）：主页网格那张卡片封面和歌单详情页左上角的
 * 大封面之间飞一次，去程回程都飞。
 *
 * <p>和 {@link CoverHeroTransition} 一样，全部几何都是进度 {@code t} 的纯函数 —— 但这里的
 * {@code t} <b>直接取自换页转场那条时钟</b>（{@code NCMScreen} 每帧把 {@code transitionEased}
 * 递进来），因为接力封面必须和新页同时落地：两条独立时钟哪怕只差一帧，也会在落点留下一张
 * 悬在空中的封面，或者让页面上的封面和飞行封面同时出现一次。
 *
 * <p>飞行期间起落两点的封面都必须让位（{@link #coversYield(Object)}），屏幕上自始至终只有一张
 * 封面。让位用「把纹理供给换成 {@code null}」而不是 {@code setHidden}：
 * {@code AbstractWidget#renderWidget} 在 hidden 时直接 return，控件自己的回调就再也不会跑，
 * 也就没人把它取消隐藏。
 *
 * <p>去程的落点主人（那张详情页面板）在点击时就已构造出来，直接点名；回程的落点是主页里
 * <b>同一个歌单</b>的那张卡片，而主页要到换页之后才排版，所以只能按歌单身份认领。两条路都
 * 要求 {@code owner != 起点主人}，否则上一页会替自己抢下落点。
 *
 * <p>没有起点、没有落点、或者封面纹理还没下完，就整趟放弃：换页退化成 A 方案那套交叉位移。
 * 放弃必须<b>在第一帧就判掉</b> —— 起点那侧的封面已经让位了，再拖几帧就是一个透明的洞。
 *
 * <p>起落两点还必须<b>完整落在各自被裁剪的那块视口里</b>（{@code viewport} 参数）。接力封面画在
 * 整棵控件树之外，只被内容区裁一刀；而卡片被 {@code ScrollPanel} 裁在列表视口里，两者之间那条
 * 页眉带只有接力封面会画到。卡片滚出视口一半还照它的绝对矩形起飞，就会看见一条盖住页眉的巨型
 * 封面被内容区上沿齐根切平 —— 那不是「封面飞出来」，是「封面漏出去了」。视口外的卡片没有诚实的
 * 起点矩形，直接走交叉位移。
 */
public final class PlaylistCoverHero implements SharedRenderingConstants, SharedConstants {

    /** 形状绘制是接口上的 default 方法，静态调用需要一个实例；除此之外这个对象没有状态。 */
    private static final PlaylistCoverHero GFX = new PlaylistCoverHero();

    /**
     * 悬停时封面会向四周胀出半个 {@code COVER_HOVER_EXPANSION}，那点溢出是设计如此，
     * 不该把接力判死。留 3 逻辑像素的容差（胀量上限的一半多一点）。
     */
    private static final double VISIBILITY_TOLERANCE = 3.0;

    private PlaylistCoverHero() {
    }

    /** 起飞的那一侧（一张卡片，或一张详情页面板）。只用来比对身份，不读它的内容。 */
    private static Object sourceOwner;
    /** 落点的那一侧。去程在 {@link #begin} 就点名，回程由卡片按歌单身份认领后置上。 */
    private static Object targetOwner;
    /** 回程用来认落点的身份：同一个 {@code PlayList} 实例。 */
    private static Object targetElement;
    /** 终点帧画完后，下一帧由目标控件无缝接管，供其跳过自己的 fadeIn。 */
    private static Object handoffOwner;

    private static Location coverLocation;
    private static ITextureObject cachedTexture;

    private static double startX, startY, startSize, startRadius;
    private static double targetX, targetY, targetSize, targetRadius;
    private static boolean hasTarget;

    /**
     * 请求一次接力。
     *
     * @param sourceOwner   起点那一侧（卡片或面板），飞行期间它自己的封面要让位
     * @param targetOwner   去程的落点面板；回程此刻还没有落点，传 {@code null}，由拿着同一个
     *                      {@code targetElement} 的那张卡片来认领
     * @param size          起点封面的边长；拿不到封面纹理时整条接力直接放弃
     * @param viewport      起点自己被套在里面的裁剪容器（网格是 {@code ScrollPanel}），起点矩形
     *                      不完全落在它里头就不飞；起点本来不被任何视口裁（详情页大封面）传
     *                      {@code null}
     */
    public static void begin(Object sourceOwner, Object targetOwner, Object targetElement,
                             Location cover, double x, double y, double size, double radius,
                             AbstractWidget<?> viewport) {
        end();
        handoffOwner = null;
        if (sourceOwner == null || cover == null || size <= .5) return;
        // 封面还没下完就飞，会先飞出一个空白圆角块。让页面自己那张淡入进来就够了。
        if (TextureManager.getInstance().getTexture(cover) == null) return;
        if (!fullyInside(viewport, x, y, size)) return;

        PlaylistCoverHero.sourceOwner = sourceOwner;
        PlaylistCoverHero.targetOwner = targetOwner;
        PlaylistCoverHero.targetElement = targetElement;
        coverLocation = cover;
        startX = x;
        startY = y;
        startSize = size;
        startRadius = radius;
    }

    /** 消费一次终点交接，让目标控件跳过自己的淡入并从完整不透明度接管。 */
    public static boolean consumeHandoff(Object owner) {
        if (owner == null || handoffOwner != owner) return false;
        handoffOwner = null;
        return true;
    }

    /**
     * 谁把大封面画在哪儿，每帧回报一次。
     *
     * <p>去程只接受被点名的那张详情页面板；回程接受「拿着同一个歌单」的那张卡片。两种情况下
     * 起点那一侧都不许认领 —— 它自己就是飞走的那张封面。
     *
     * @param viewport 落点被套在里面的裁剪容器，理由同 {@link #begin}。不认领时 {@code hasTarget}
     *                 保持 false，{@link #render} 会在第一帧自己收手，正好就是「落点不存在」那条
     *                 既有退路。
     */
    public static void setTarget(Object owner, Object element, double x, double y, double size,
                                 double radius, AbstractWidget<?> viewport) {
        if (owner == null || owner == sourceOwner) return;
        if (targetOwner != null ? owner != targetOwner : element == null || element != targetElement) return;
        if (!fullyInside(viewport, x, y, size)) return;

        targetOwner = owner;
        targetX = x;
        targetY = y;
        targetSize = size;
        targetRadius = radius;
        hasTarget = true;
    }

    /** 矩形是否完整落在裁剪容器里。容器为 null 表示这一侧根本不被裁剪，自然恒真。 */
    private static boolean fullyInside(AbstractWidget<?> viewport, double x, double y, double size) {
        if (viewport == null) return true;
        final double t = VISIBILITY_TOLERANCE;
        return x >= viewport.getX() - t && y >= viewport.getY() - t
                && x + size <= viewport.getX() + viewport.getWidth() + t
                && y + size <= viewport.getY() + viewport.getHeight() + t;
    }

    /** 这一侧的封面这一帧该不该让位：飞起来的那一张就是它，同时画两张会穿帮。 */
    public static boolean coversYield(Object owner) {
        return owner != null && (owner == sourceOwner || owner == targetOwner);
    }

    /**
     * 画这一帧的飞行封面，并推进生命周期。
     *
     * @param eased 换页转场已经缓动过的进度，和两页的位移/不透明度同源
     */
    public static void render(double eased) {
        if (sourceOwner == null) return;
        // 一帧都没人认领落点也立刻收手 —— 落点不存在（例如返回到一个把这张卡片滚出视口的主页），
        // 而起点那侧已经让位了，继续留着就是一个透明的洞。注意不能再把
        // eased >= 1.0 || !hasTarget 作为同一个提前返回条件：终点帧仍要先画接力封面。
        if (!hasTarget) {
            end();
            return;
        }

        ITextureObject texture = texture();
        if (texture == null) {
            end();
            return;
        }

        // 终点帧也必须先画一次再收尾。原来在 eased >= 1 时直接 end，
        // 但落点控件本帧仍处于 coversYield 状态，于是会出现一个空帧，表现为封面接力结束时闪烁一下。
        double m = TransitionCurveCache.MOTION.at(Math.min(1.0, Math.max(0.0, eased)));
        double size = startSize + (targetSize - startSize) * m;
        double radius = startRadius + (targetRadius - startRadius) * m;
        // 按中心插值再回推左上角：直接插左上角的话，尺寸变化会被读成一半位移。
        double centerX = startX + startSize * .5 + ((targetX + targetSize * .5) - (startX + startSize * .5)) * m;
        double centerY = startY + startSize * .5 + ((targetY + targetSize * .5) - (startY + startSize * .5)) * m;

        // 全程不透明：起点封面本来就是不透明的，落点封面到了 t=1 也是，中途再淡一下只会露底。
        api.getGLStateManager().color(1, 1, 1, 1);
        api.getGLStateManager().disableAlpha();
        api.getGLStateManager().bindTexture(texture.getGlTextureId());
        texture.linearFilter();
        GFX.roundedRectTextured(centerX - size * .5, centerY - size * .5, size, size, radius, 1f);

        // 让目标控件从下一帧开始重新接管封面，避免「英雄层」与页面封面同时画一帧。
        if (eased >= 1.0) {
            handoffOwner = targetOwner;
            end();
        }
    }

    public static void end() {
        sourceOwner = null;
        targetOwner = null;
        targetElement = null;
        hasTarget = false;
        coverLocation = null;
        cachedTexture = null;
    }

    private static ITextureObject texture() {
        if (cachedTexture != null) return cachedTexture;
        if (coverLocation == null) return null;
        cachedTexture = TextureManager.getInstance().getTexture(coverLocation);
        return cachedTexture;
    }
}
