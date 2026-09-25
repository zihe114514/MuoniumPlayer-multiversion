package com.muoniumplayer.core.screens.ncm.panels;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.svg.SmilSvg;
import com.muoniumplayer.core.rendering.svg.SvgPlayback;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.screens.ncm.NCMScreen;

import java.util.function.Supplier;

/**
 * 播放器内容区通用的「正在加载」指示：12 段 iOS/macOS 风格转子 + 一行说明文字。
 *
 * <p>刻意<b>不画底板</b>：内容区背后是主题背景与液态玻璃，压一层半透明底板会让它看起来像个
 * 阻塞式对话框，而这里想要的是"页面自己在忙"。</p>
 *
 * <p>这份实现原来是 {@code PlaylistPanel} 的私有内部类，只服务歌单页。主页、发现页、
 * 以及导航栏那几个"点了要等网络"的入口都需要同一个东西，所以提到这里 —— 三处各画一份转子，
 * 迟早会在段数、转速、配色上分叉。</p>
 *
 * <h3>两种用法</h3>
 * <ul>
 *   <li>作为控件加进面板：布局照常走 {@code setBounds} / {@code setAlpha}，{@link #onRender} 自己画；</li>
 *   <li>不进控件树、由外部直接画（{@code NCMScreen} 的导航等待遮罩）：持有一个实例，
 *       每帧调 {@link #paint}。转子角度存在实例上，所以两种用法都是与帧率无关的匀速旋转。</li>
 * </ul>
 */
public final class PanelLoadingWidget extends AbstractWidget<PanelLoadingWidget> {

    private static final int SEGMENT_COUNT = 12;

    /** 转子中心相对控件中心上移这么多，给下面那行文字腾位置。 */
    private static final double SPINNER_CENTER_LIFT = 12.0;

    /** 文字基线相对转子中心下移这么多。 */
    private static final double TEXT_BASELINE_DROP = 17.0;

    /**
     * 换成动画图标时，图标方格取多大。
     *
     * <p>这里量的是<b>素材自己的比例</b>，不是"转子多大我就多大"。{@code search.svg} 的墨迹盒
     * 有 230 个文档单位，而笔画只有 2 个单位 —— 塞进 26 的方格，笔画就剩 0.23 设备像素，
     * 加上素材自身 26%~55% 的淡入淡出，实测是什么都看不见的（引擎确实画了，画布上什么都没有）。
     * 64 是实拍看过之后定的：笔画回到约 0.56 设备像素，抗锯齿之后仍然连得成线。</p>
     */
    private static final double ANIMATED_ICON_BOX = 64.0;

    /** 动画图标下面留这么多给那一行字：插画自己有留白，不用再抬 12。 */
    private static final double TEXT_DROP_BELOW_ICON = 16.0;

    private final Supplier<String> text;
    private float rotation;
    private boolean spinning = true;
    private SmilSvg icon;
    private boolean iconTinted = true;
    private SvgPlayback iconPlayback;

    public PanelLoadingWidget(String text) {
        final String fixed = text == null ? "" : text;
        this.text = () -> fixed;
    }

    /** 文案会变时用这个重载（例如导航等待遮罩要显示点的是哪一项）。 */
    public PanelLoadingWidget(Supplier<String> text) {
        this.text = text == null ? () -> "" : text;
    }

    /**
     * 关掉转子、只留那一行字。用于「获取失败」这类终态提示 —— 转子的含义是"还在忙"，
     * 配在失败文案后面等于自相矛盾。
     */
    public PanelLoadingWidget setSpinning(boolean spinning) {
        this.spinning = spinning;
        return this;
    }

    /**
     * 把那一圈转子换成一枚<b>动画矢量图标</b>。
     *
     * <p>现在只有搜索结果页在用（{@code search.svg} 的取景框 + 放大镜）。别的页面照旧走转子：
     * 这份控件是主页、发现页、导航等待遮罩、一起听共用的，一处改动会一起变，不能顺手全换。</p>
     *
     * @param tint true 时整枚图标染成主题强调色 —— {@code search.svg} 的笔画是写死的黑色，
     *             不染的话在深色主题下就是一块看不见的东西。
     */
    public PanelLoadingWidget setAnimatedIcon(SmilSvg icon, boolean tint) {
        this.icon = icon;
        this.iconTinted = tint;
        this.iconPlayback = icon == null ? null : SvgPlayback.looping(icon);
        return this;
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        paint(this.getX(), this.getY(), this.getWidth(), this.getHeight(), this.getAlpha(), this.spinning);
    }

    /**
     * 在给定矩形的中心画一枚转子和一行文字，并推进转子角度。
     *
     * <p>设过 {@link #setAnimatedIcon} 且真画得出来时，画的是那幅动画插画而不是转子，
     * 文字同时落到插画下沿之外 —— 见 {@link #ANIMATED_ICON_BOX}。</p>
     *
     * <p>角度增量取自 {@link RenderSystem#getFrameDeltaTime()}（约 10 ms 一个单位），
     * 不是"每帧固定加几度"：掉帧时转子走大步而不是变慢。</p>
     *
     * @param alpha    0..1；调用方负责淡入淡出，{@code <= .015} 时连角度都不推进（不可见就别转）
     * @param spinning {@code false} 只画那一行字。失败提示不该配着转子 ——
     *                 转子的含义是"还在忙"，配在"获取失败"后面等于自相矛盾
     */
    public void paint(double x, double y, double width, double height, float alpha, boolean spinning) {
        if (alpha <= .015f) return;

        double centerX = x + width * .5;
        double centerY = y + height * .5 - SPINNER_CENTER_LIFT;
        double textBaseline = centerY + TEXT_BASELINE_DROP;

        if (spinning) {
            int accent = NCMScreen.getColor(NCMScreen.ColorType.ACCENT);
            // 动画图标画不出来（素材缺失、这一帧不在 Skija 里）就退回转子：
            // 「正在加载」这件事不能因为一枚图标而整个看不见。
            if (drawAnimatedIcon(centerX, y + height * .5, alpha, accent)) {
                // 插画占的是整块中央，文字得落在它的下沿之外，不然两样叠在一起。
                textBaseline = y + height * .5 + ANIMATED_ICON_BOX * .5 + TEXT_DROP_BELOW_ICON;
            } else {
                drawRotor(centerX, centerY, alpha, accent);
            }
        }

        String label = text.get();
        if (label == null || label.isEmpty()) return;
        double textWidth = FontManager.pf14.getStringWidthD(label);
        FontManager.pf14.drawString(label, centerX - textWidth * .5, textBaseline,
                RenderSystem.reAlpha(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT), alpha));
    }

    /** @return 真的画了才 true；没设图标或画不出来都 false，交给转子兜底。 */
    private boolean drawAnimatedIcon(double centerX, double centerY, float alpha, int accent) {
        if (icon == null || iconPlayback == null) {
            return false;
        }
        return icon.drawIn(centerX - ANIMATED_ICON_BOX * .5, centerY - ANIMATED_ICON_BOX * .5,
                ANIMATED_ICON_BOX, ANIMATED_ICON_BOX, iconPlayback.time(), alpha, iconTinted ? accent : 0);
    }

    /** 那圈 12 段 iOS/macOS 风格转子。角度存在实例上，所以掉帧时走大步而不是变慢。 */
    private void drawRotor(double centerX, double centerY, float alpha, int accent) {
        rotation = (float) ((rotation + RenderSystem.getFrameDeltaTime() * 5.0) % 360.0);
        for (int segment = 0; segment < SEGMENT_COUNT; segment++) {
            // 尾迹：越靠后的段越淡，平方一下让"头部"更集中，转起来才有方向感。
            float trail = 1f - segment / (float) SEGMENT_COUNT;
            float segmentAlpha = alpha * (.14f + .86f * trail * trail);

            api.getGLStateManager().pushMatrix();
            api.getGLStateManager().translate(centerX, centerY, 0);
            api.getGLStateManager().rotate(rotation + segment * (360f / SEGMENT_COUNT), 0, 0, 1);
            this.roundedRect(-1.65, -12, 3.3, 6.2, 1.65, RenderSystem.reAlpha(accent, segmentAlpha));
            api.getGLStateManager().popMatrix();
        }
    }
}