package com.muoniumplayer.core.rendering.ui.widgets;

import com.muoniumplayer.core.rendering.svg.SmilSvg;
import com.muoniumplayer.core.rendering.svg.SvgPlayback;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;

import java.util.function.Supplier;

/**
 * 一层动画 SVG：按控件自己的矩形逐帧画，控件的 alpha 直接乘上去。
 *
 * <p>存在的理由和别的图标控件一样 —— 站点只管「摆在哪、多大、要不要跟着主题色」，
 * 时钟与解析都收进 {@link SmilSvg} 与 {@link SvgPlayback}。二维码遮罩、爱心这两处
 * 都是把它 {@code addChild} 进面板再 {@code setBounds}，不自己碰画布。</p>
 *
 * <p>素材读不到（资源缺失、XML 坏了）时 {@link #onRender} 什么都不画、也不抛，
 * 由调用方保留原来的静态图标作兜底。</p>
 */
public class AnimatedSvgWidget extends AbstractWidget<AnimatedSvgWidget> {

    private final Supplier<SmilSvg> source;
    private SvgPlayback playback;

    private AnimatedSvgWidget(Supplier<SmilSvg> source) {
        this.source = source == null ? () -> null : source;
        this.playback = SvgPlayback.looping(this.source.get());
        this.setClickable(false);
    }

    public AnimatedSvgWidget(SmilSvg svg) {
        this(() -> svg);
    }

    /** 换掉时钟：从循环改成「只播一轮」，或者反过来。 */
    public AnimatedSvgWidget setPlayback(SvgPlayback playback) {
        this.playback = playback == null ? SvgPlayback.looping(source.get()) : playback;
        return this;
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        SmilSvg svg = source.get();
        if (svg == null || playback == null) {
            return;
        }
        // 染色口子留在这里但默认不开：这几枚素材各自带色（红心、紫星光、红扫描框），
        // 染成单色反而难看；真要跟着主题色，走 PanelLoadingWidget 那条按强调色染的路。
        svg.drawIn(this.getX(), this.getY(), this.getWidth(), this.getHeight(),
                playback.time(), this.getAlpha(), 0);
    }
}
