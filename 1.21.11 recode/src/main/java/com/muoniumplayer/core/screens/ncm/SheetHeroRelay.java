package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;

import java.awt.Color;
import java.util.function.IntSupplier;

/**
 * 二级浮层的共享元素接力：一块圆角识别板从「你点的那个东西」飞到「浮层里和它对应的那一行」。
 *
 * <p>和 {@link PlaylistCoverHero} 是同一个想法的两种材料 —— 那边飞的是封面贴图，这边飞的是一张
 * 卡片底板。侧栏那枚来源按钮、账号项、歌曲行的「+」都不是图片，没有贴图可飞，能认得出「是它」
 * 的是它那一小块形状和颜色。</p>
 *
 * <p><b>为什么是纯函数、而且进度由浮层递进来。</b>接力自己不计时：这块板必须和落点那张卡同时
 * 落位。自己起一条时钟，哪怕只差一帧，落点就会留下一块悬在半空的板 —— {@code PlaylistCoverHero}
 * 立过同一条规矩。</p>
 *
 * <p><b>回程是同一条路径反放，不写第二套。</b>浮层那条 {@link SheetClock} 在关闭时会从 1 走回 0，
 * 于是这块板原路飞回那颗按钮。代价是浮层的进度必须是真毫秒驱动的：指数逼近没有「现在走到第几成」
 * 这个可逆的读数。</p>
 *
 * <p><b>不透明度是「{@code 1 -} 落点那张卡自己的入场进度」</b>：板飞到位置的同时，卡片自己的底板
 * 正在淡进来，两条互补。于是这里不需要 {@code PlaylistCoverHero} 那套「起落两侧都要让位」的协议 ——
 * 屏幕上任何时候都只有一块实心的板，而且没有一帧是两块都全亮。</p>
 */
public final class SheetHeroRelay implements SharedRenderingConstants, SharedConstants {

    /** 形状绘制是接口上的 default 方法，静态调用需要一个实例；除此之外这个对象没有状态。 */
    private static final SheetHeroRelay GFX = new SheetHeroRelay();

    private SheetHeroRelay() {
    }

    /**
     * 起点：触发它的那颗按钮，在点击那一刻量下的绝对坐标。
     *
     * <p>必须在 {@code openXxx()} <b>之前</b>量：换页会重建控件树，下一帧那个按钮可能已经不在、
     * 或者已经挪走了。</p>
     */
    public static final class Origin {
        private final double x, y, width, height, radius;
        private final IntSupplier tint;

        private Origin(final double x, final double y, final double width, final double height,
                       final double radius, final IntSupplier tint) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.radius = radius;
            this.tint = tint;
        }
    }

    /**
     * 交出一个起点。调用点在按下的那一刻，浮层此刻还没构造。
     *
     * @param tint 板子的颜色，每帧现取：主题色跟着用户走，不能把点击那一刻的值冻进矩形里
     */
    public static Origin from(final double x, final double y, final double width, final double height,
                              final double radius, final IntSupplier tint) {
        if (width <= .5 || height <= .5 || tint == null) return null;
        return new Origin(x, y, width, height, radius, tint);
    }

    /**
     * 画这一帧的飞行板。
     *
     * @param eased  浮层底板那一档的进度（已经缓动过），回程就是它往回走
     * @param landing 落点那张卡自己的入场进度：板子随它变实而淡出，两者互补
     */
    public static void render(final Origin from, final double targetX, final double targetY,
                              final double targetWidth, final double targetHeight, final double targetRadius,
                              final double eased, final double landing) {
        if (from == null) return;
        // 已经落位（或压根还没起来）就别画了：这一段本来就是卡片自己出场的时间。
        double alpha = 1.0 - landing;
        if (eased >= 1.0 || eased <= 0.0 || alpha <= .004) return;

        double m = TransitionCurveCache.MOTION_LAGGING.at(eased);
        double width = from.width + (targetWidth - from.width) * m;
        double height = from.height + (targetHeight - from.height) * m;
        double radius = from.radius + (targetRadius - from.radius) * m;
        // 按中心插值再回推左上角：直接插左上角的话，尺寸变化会被读成一半位移。
        double centerX = from.x + from.width * .5 + ((targetX + targetWidth * .5) - (from.x + from.width * .5)) * m;
        double centerY = from.y + from.height * .5 + ((targetY + targetHeight * .5) - (from.y + from.height * .5)) * m;

        // 末段再压一点不透明度：卡片自己已经在亮了，两块板叠在一起会有一次「比实更实」的鼓包。
        alpha *= TransitionCurveCache.MOTION.at(eased < .82 ? 1.0 : (1.0 - eased) / .18);
        GFX.roundedRect(centerX - width * .5, centerY - height * .5, width, height, radius,
                new Color(RenderSystem.reAlpha(from.tint.getAsInt(), (float) alpha), true));
    }
}
