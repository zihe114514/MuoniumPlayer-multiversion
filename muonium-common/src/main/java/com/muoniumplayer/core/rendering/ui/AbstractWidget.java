package com.muoniumplayer.core.rendering.ui;

import lombok.Getter;

import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.RGBA;
import com.muoniumplayer.core.rendering.Rect;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.settings.ClientSettings;
import com.muoniumplayer.core.utils.cursor.CursorUtils;

import java.awt.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * 组件 base.
 * @author MuoniumPlayer Contributors
 * Date: 2025/7/8 19:42
 */
public abstract class AbstractWidget<SELF extends AbstractWidget<SELF>> implements SharedRenderingConstants, SharedConstants {

    public interface OnClickCallback {
        boolean onClick(double relativeX, double relativeY, int mouseButton);
    }

    public interface OnKeyTypedCallback {
        boolean onKeyTyped(char character, int keyCode);
    }

    public interface OnDWheelCallback {
        boolean onDWheel(double mouseX, double mouseY, int dWheel);
    }

    public interface RenderCallback {
        void onRender();
    }

    // 左上
    @Getter
    AbstractWidget<?> parent = null;

    // 右上
    @Getter
    List<AbstractWidget<?>> children = new CopyOnWriteArrayList<>();

    public static class Bounds {
        // 左下
        private double x, y;
        private double width, height;
    }

    // 右下
    @Getter
    private final Bounds bounds = new Bounds();

    // 边界检查
    private RenderCallback beforeRenderCallback = () -> {};

    // 后添加的子控件渲染在更上层，因此必须优先接收输入。
    @Getter
    private boolean clickable = true;

    /**
     * 鼠标是否在组件内
     */
    @Getter
    private boolean hovering = false;

    /**
     * 组件是否被隐藏
     */
    @Getter
    private boolean hidden = false;
    /**
     * 是否应在被指向时更改鼠标指针类型
     */
    @Getter
    private boolean shouldOverrideMouseCursor = false;

    /**
     * 悬浮说明：悬停约半秒后在控件上方浮出一行小字。null（默认）表示这块控件没有说明；
     * Supplier 每次画之前重取，「播放顺序：列表循环」「刷新QQ音乐歌单」这类跟着状态走的
     * 文案直接换内容就行，不用重建控件。
     *
     * <p>画它的是<b>父组件</b>（在所有子控件渲染完之后，见 {@link #renderWidget}），
     * 而不是控件自己的 onRender：说明浮在控件上方，而后加进来的兄弟（播放条上正是
     * 进度条、时间标签这些）画在更上层，跟在控件自己的渲染里画会被它们盖住。</p>
     */
    private Supplier<String> tooltip;

    /**
     * 这一轮悬停开始的时间戳；0 表示当前没悬停。移开立即清零，回来重新计时。
     * 被 {@code setHidden} 跳过整帧渲染的控件收不到清零，重新出现时若仍悬停会立刻
     * 显示说明——比永远显示不出来安全，与侧栏按钮同一取舍。
     */
    private long tooltipHoverSince;

    /** 悬浮说明要停够这么久才出现：鼠标扫过一排按钮时每颗只停留几十毫秒，立刻弹会连环闪烁。 */
    private static final long TOOLTIP_DELAY_MS = 450L;

    /** 停够之后多久淡满。 */
    private static final long TOOLTIP_FADE_MS = 140L;

    /**
     * 颜色
     */
    private Color color = Color.BLACK;

    /**
     * 点击回调
     */
    protected OnClickCallback clickCallback = null;

    /**
     * 键盘输入回调
     */
    private OnKeyTypedCallback keyTypedCallback = null;

    private OnDWheelCallback dWheelCallback = null;

    private Runnable transformations = null, onTick = null;

    /**
     * 组件的半透明度
     */
    private float alpha = 1.0f;

    /**
     * 只有本控件<em>自己那一层填充</em>的不透明度，默认 1（即完全不参与）。
     *
     * <p>与 {@link #setAlpha} 的区别是这条存在的唯一理由：{@code alpha} 会顺父子关系往下乘
     * （{@link #getAlpha()}），所以拿它压一块按钮底，按钮上的文字、图标、徽标会跟着一起淡。
     * 液态玻璃要压的恰恰只是<em>那一层深色底</em>——字必须保持原样，否则一整页会糊成灰雾。
     * 它只作用于 {@link #getHexColor()} 这一次取色，因此对文字类控件、图标类控件毫无影响。</p>
     */
    private float fillAlpha = 1.0f;

    /**
     * 在此处编写组件的渲染代码.
     */
    public abstract void onRender(double mouseX, double mouseY);

    /**
     * 返回是否应该渲染指定的子组件.
     * @param child 子组件
     * @param mouseX 鼠标 X 坐标
     * @param mouseY 鼠标 Y 坐标
     * @return 返回 true 表示应该渲染该子组件, 返回 false 表示应该跳过该子组件.
     */
    protected boolean shouldRenderChildren(AbstractWidget<?> child, double mouseX, double mouseY) {
        return true;
    }

    /**
     * 在当前组件完成布局和自身绘制后、开始绘制子组件前调用。
     */
    protected void beforeRenderChildren(double mouseX, double mouseY) {
    }

    /**
     * 在当前组件的所有子组件绘制完成后调用。
     */
    protected void afterRenderChildren(double mouseX, double mouseY) {
    }

    /**
     * 渲染这个组件以及它的子组件.
     * @param mouseX 鼠标 X 坐标
     * @param mouseY 鼠标 Y 坐标
     * @param dWheel 滚轮
     */
    public void renderWidget(double mouseX, double mouseY, int dWheel) {

        if (this.isHidden())
            return;

        boolean shouldResetMatrixState = this.transformations != null;

        if (shouldResetMatrixState) {
            api.getGLStateManager().pushMatrix();
            this.transformations.run();
        }

        this.beforeRenderCallback.onRender();

        this.onRender(mouseX, mouseY);

        boolean debug = ClientSettings.SHOW_WIDGET_BOUNDARY;

        boolean childHovering = false;

        this.beforeRenderChildren(mouseX, mouseY);
        // 融合组的窗口开在 beforeRenderChildren 之后、收尾在 afterRenderChildren 之前：
        // ScrollPanel 那一类容器正是在这两个钩子里 begin/end 裁剪的，边缘必须落在视口<em>里面</em>
        // 画，否则滚出界的那几行会把一整排亮边盖到面板别处。
        // 半径在画的那一刻才读设置，用户拖滑块立刻生效。
        float fusionRadius = this.glassFusionSurface == null ? 0f
                : GlassLook.fusionRadius();
        if (fusionRadius > 0f) {
            // 嵌套时外层那一组先收掉再开我们的。不收的症状不是「少一道颈」而是「画到界外」：
            // 我们的边缘会被拖到外层的收尾去画，而那时我们这一层的裁剪已经收了。
            GlassLook.endFusion();
            GlassLook.beginFusion(this.glassFusionSurface, fusionRadius);
        }
        try {
            // 渲染所有子组件
            for (AbstractWidget<?> child : this.getChildren()) {

                if (child.isHidden())
                    continue;

                if (!this.shouldRenderChildren(child, mouseX, mouseY))
                    continue;

                child.renderWidget(mouseX, mouseY, dWheel);

                if (debug) {
                    child.renderDebugLayout();
                }

                if (!childHovering && child.isClickable() && child.testHovered(mouseX, mouseY)) {
                    childHovering = true;
                }
            }
        } finally {
            // 放在 finally：子控件中途抛异常时这一组也必须收掉，否则下一帧、下一个界面
            // 会把上一组的边缘接过来画（SkijaHost 那道帧末兜底只能丢掉整组）。
            if (fusionRadius > 0f) {
                GlassLook.endFusion();
            }
            this.afterRenderChildren(mouseX, mouseY);
        }

        // 子控件的悬浮说明画在所有子控件之后，理由见 drawTooltip：说明要盖住后渲染的兄弟。
        // 此刻每个子控件的本帧悬停状态都已由它自己的 renderWidget 更新过。
        // 这里与 drawTooltip 一律直取字段而不是 lombok 生成的访问器：渲染树深处逐控件走一遍，
        // 同类私有字段本就可见，也少一层调用。
        for (AbstractWidget<?> child : this.children) {
            if (child.hidden) continue;
            child.drawTooltip(this);
        }

        if (debug) {
            this.renderDebugLayout();
        }

        if (shouldResetMatrixState)
            api.getGLStateManager().popMatrix();

        // 更新组件悬停状态
        // 如果所有的子组件都没有被选中, 且这个组件被选中, 才设置这个组件的 hovering 为 true.
        this.hovering = !childHovering && this.testHovered(mouseX, mouseY);

        if (this.hovering && this.isShouldOverrideMouseCursor()) {
            CursorUtils.setOverride(this.getHoveringCursorType());
        }

        if (dWheel != 0)
            this.onDWheelReceived(mouseX, mouseY, dWheel);
    }

    public SELF setShouldOverrideMouseCursor(boolean shouldOverrideMouseCursor) {
        this.shouldOverrideMouseCursor = shouldOverrideMouseCursor;
        return (SELF) this;
    }

    public long getHoveringCursorType() {
        return CursorUtils.HAND;
    }

    /** 固定文案的悬浮说明，见 {@link #tooltip}。 */
    public SELF setTooltip(String tooltip) {
        return this.setTooltip(() -> tooltip);
    }

    /** 动态文案的悬浮说明（每次显示前重取），见 {@link #tooltip}。 */
    public SELF setTooltip(Supplier<String> tooltip) {
        this.tooltip = tooltip;
        return (SELF) this;
    }

    /**
     * 画悬浮说明（悬停并停够 {@link #TOOLTIP_DELAY_MS} 之后）。只由父组件的
     * {@link #renderWidget} 在所有子控件渲染完之后调用，参数是父组件自己——
     * 说明要夹在父组件的范围里，探出去的部分会被后渲染的面板盖住，看着像断了一截。
     */
    private void drawTooltip(AbstractWidget<?> clampTo) {
        if (this.tooltip == null) return;
        if (!this.hovering) {
            this.tooltipHoverSince = 0L;
            return;
        }
        if (this.tooltipHoverSince == 0L) {
            this.tooltipHoverSince = System.currentTimeMillis();
            return;
        }
        long held = System.currentTimeMillis() - this.tooltipHoverSince;
        if (held < TOOLTIP_DELAY_MS) return;
        float reveal = Math.min(1f, (held - TOOLTIP_DELAY_MS) / (float) TOOLTIP_FADE_MS);
        float alpha = this.getAlpha() * reveal;
        if (alpha <= .01f) return;

        String text;
        try {
            text = this.tooltip.get();
        } catch (RuntimeException ignored) {
            // 瞬时的 provider 失败绝不能带崩整棵渲染树，与 LabelWidget 的兜底同一条规则。
            return;
        }
        CFontRenderer font = FontManager.pf11;
        if (text == null || text.trim().isEmpty() || font == null) return;

        double textWidth = font.getStringWidthD(text);
        double textHeight = font.getStringHeight(text);
        double padX = 7, padY = 3.5;
        double width = textWidth + padX * 2;
        double height = textHeight + padY * 2;
        double x = this.getX() + this.getWidth() * .5 - width * .5;
        // 只往上方放，不翻转：会挂提示的按钮全都住在播放器底部（侧栏操作条、播放条图标带），
        // 上方是内容区、仍在播放器的圆角裁剪之内。曾经加过「贴着父组件顶边就翻到按钮下方」，
        // 结果播放条整条就这么高，按钮又垂直居中——翻下去正好压在播放器底缘上，被圆角
        // 裁剪吞掉半截，比要避免的下场更糟。
        double y = this.getY() - height - 4;
        if (clampTo != null && clampTo.getWidth() - 4 >= width) {
            x = Math.max(clampTo.getX() + 2,
                    Math.min(x, clampTo.getX() + clampTo.getWidth() - 2 - width));
        }
        // 底板不透明：底下就是歌单行、进度条这些内容，半透明会把两层墨迹叠在一起读不清。
        roundedRect(x - 1, y - 1, width + 2, height + 2, 6,
                reAlpha(NCMScreen.getColor(NCMScreen.ColorType.BORDER), alpha));
        roundedRect(x, y, width, height, 5,
                reAlpha(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND), alpha));
        font.drawCenteredString(text, x + width * .5, y + padY,
                reAlpha(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT), alpha));
    }

    public SELF setOnTick(Runnable onTick) {
        this.onTick = onTick;
        return (SELF) this;
    }

    public void onTick() {

    }

    public void onTickReceived() {

        if (this.onTick != null) {
            this.onTick.run();
        }

        this.onTick();

        this.getChildren().forEach(AbstractWidget::onTickReceived);

    }

    /**
     * Finish visual entrance state in this subtree when its owner is about to stop rendering.
     * Concrete animated widgets override this method; ordinary widgets are unaffected.
     */
    public void finishTransientAnimations() {
        for (AbstractWidget<?> child : this.getChildren()) {
            child.finishTransientAnimations();
        }
    }

    /** Called when a virtualized container moves this widget out of its render viewport. */
    public void onViewportExit() {
    }

    /**
     * 添加子组件
     * @param children 子组件
     */
    public void addChild(AbstractWidget<?>... children) {
        for (AbstractWidget<?> child : children) {
            this.children.add(child);
            child.setParent(this);
        }
    }

    /**
     * 添加子组件
     * @param children 子组件
     */
    public void addChild(List<AbstractWidget<?>> children) {
        this.children.addAll(children);
        children.forEach(child -> child.setParent(this));
    }

    /**
     * 渲染 Debug 布局
     */
    protected void renderDebugLayout() {
        // 显示布局
        RenderSystem.drawOutLine(this.getX(), this.getY(), this.getWidth(), this.getHeight(), 0.5, reAlpha(0x00FF0000, this.getAlpha()));

        double lineLength = Math.min(8, Math.min(this.getWidth() * .25, this.getHeight() * .25));
        double lineSize = 1;
        int lineColor = reAlpha(0x000090FF, this.getAlpha());
        // 左上
        Rect.draw(this.getX(), this.getY(), lineLength, lineSize, lineColor);
        Rect.draw(this.getX(), this.getY(), lineSize, lineLength, lineColor);

        // 右上
        Rect.draw(this.getX() + this.getWidth() - lineLength, this.getY(), lineLength, lineSize, lineColor);
        Rect.draw(this.getX() + this.getWidth() - lineSize, this.getY(), lineSize, lineLength, lineColor);

        // 左下
        Rect.draw(this.getX(), this.getY() + this.getHeight() - lineLength, lineSize, lineLength, lineColor);
        Rect.draw(this.getX(), this.getY() + this.getHeight() - lineSize, lineLength, lineSize, lineColor);

        // 右下
        Rect.draw(this.getX() + this.getWidth() - lineLength, this.getY() + this.getHeight() - lineSize, lineLength, lineSize, lineColor);
        Rect.draw(this.getX() + this.getWidth() - lineSize, this.getY() + this.getHeight() - lineLength, lineSize, lineLength, lineColor);
    }

    /**
     * 设置这个组件相较于父组件的留边, 会直接修改组件的 x, y 坐标以及长宽.
     * @param margin 留边大小
     */
    public SELF setMargin(double margin) {
        return this.setMargin(margin, margin, margin, margin);
    }

    /**
     * 设置这个组件相较于父组件的留边, 会直接修改组件的 x, y 坐标以及长宽.
     * @param left 左边的留边大小
     * @param top 上面的留边大小
     * @param right 右边的留边大小
     * @param bottom 下面的留边大小
     */
    public SELF setMargin(double left, double top, double right, double bottom) {
        this.getBounds().x = left;
        this.getBounds().y = top;
        this.getBounds().width = this.getParentWidth() - left - right;
        this.getBounds().height = this.getParentHeight() - top - bottom;
        return (SELF) this;
    }

    /**
     * 扩大这个组件的边界
     * @param expand 边界大小
     */
    public SELF expand(double expand) {
        this.getBounds().x -= expand;
        this.getBounds().y -= expand;
        this.getBounds().width += expand * 2;
        this.getBounds().height += expand * 2;
        return (SELF) this;
    }

    /**
     * 测试该组件有没有被鼠标指向
     * @param mouseX 鼠标 X 坐标
     * @param mouseY 鼠标 Y 坐标
     * @return 该组件有没有被鼠标指向
     */
    protected boolean testHovered(double mouseX, double mouseY) {
        return this.isHovered(mouseX, mouseY, this.getX(), this.getY(), this.getWidth(), this.getHeight());
    }

    /**
     * 测试该组件有没有被鼠标指向
     * @param mouseX 鼠标 X 坐标
     * @param mouseY 鼠标 Y 坐标
     * @param expand 扩张范围
     * @return 该组件有没有被鼠标指向
     */
    protected boolean testHovered(double mouseX, double mouseY, double expand) {
        return this.isHovered(mouseX, mouseY, this.getX() - expand, this.getY() - expand, this.getWidth() + expand * 2, this.getHeight() + expand * 2);
    }

    /**
     * 实用方法, 检测鼠标有没有在一个矩形范围内.
     * @param mouseX 鼠标 X 坐标
     * @param mouseY 鼠标 Y 坐标
     * @param x 矩形 X 坐标
     * @param y 矩形 Y 坐标
     * @param width 矩形宽度
     * @param height 举行长度
     * @return 是否在范围内
     */
    public boolean isHovered(double mouseX, double mouseY, double x, double y, double width, double height) {

        // 边界检查
        if (width < 0) {
            width = -width;
            x -= width;
        }

        if (height < 0) {
            height = -height;
            y -= height;
        }

        return mouseX >= x && mouseY >= y && mouseX <= x + width && mouseY <= y + height;
    }

    protected boolean iterateChildrenMouseClick(List<AbstractWidget<?>> children, double mouseX, double mouseY, int mouseButton) {
        for (int childIndex = children.size() - 1; childIndex >= 0; childIndex--) {
            // 后添加的子组件渲染在更早的子组件之上，因此必须先接收输入。
            AbstractWidget<?> child = children.get(childIndex);

            if (child.isHidden()) {
                continue;
            }

            if (!child.shouldClickChildren(mouseX, mouseY))
                continue;

            if (!child.getChildren().isEmpty()) {
                if (this.iterateChildrenMouseClick(child.getChildren(), mouseX, mouseY, mouseButton)) {
                    return true;
                }
            }

            if (child.isHovering() && child.isClickable() && child.onMouseClicked(mouseX - child.getX(), mouseY - child.getY(), mouseButton)) {
                return true;
            }
        }

        return false;
    }

    protected boolean iterateChildrenDWheel(List<AbstractWidget<?>> children, double mouseX, double mouseY, int dWheel) {
        for (AbstractWidget<?> child : children) {

            if (child.isHidden()) {
                continue;
            }

            if (!child.shouldClickChildren(mouseX, mouseY))
                continue;

            if (!child.getChildren().isEmpty()) {
                if (this.iterateChildrenDWheel(child.getChildren(), mouseX, mouseY, dWheel)) {
                    return true;
                }
            }

            if ((child.isHovering() || child.canBeScrolled()) && child.isClickable() && child.onDWheel(mouseX - child.getX(), mouseY - child.getY(), dWheel)) {
                return true;
            }
        }

        return false;
    }

    public boolean canBeScrolled() {
        return false;
    }

    protected boolean shouldClickChildren(double mouseX, double mouseY) {
        return true;
    }

    public void onMouseClickReceived(double mouseX, double mouseY, int mouseButton) {

        if (!this.shouldClickChildren(mouseX, mouseY))
            return;

        // 先检测子组件
        // 如果子组件都没有响应点击事件, 则测试这个组件
        if (!this.iterateChildrenMouseClick(this.getChildren(), mouseX, mouseY, mouseButton)) {
            if (!this.isHidden() && this.isHovering()) {
                // 不需要在乎返回值.
                this.onMouseClicked(mouseX - this.getX(), mouseY - this.getY(), mouseButton);
            }
        }

    }

    public void onDWheelReceived(double mouseX, double mouseY, int dWheel) {
        if (!this.shouldClickChildren(mouseX, mouseY))
            return;

        if (!this.iterateChildrenDWheel(this.getChildren(), mouseX, mouseY, dWheel)) {
            if (!this.isHidden() && (this.isHovering() || this.canBeScrolled())) {
                // 不需要在乎返回值.
                this.onDWheel(mouseX - this.getX(), mouseY - this.getY(), dWheel);
            }
        }
    }

    /**
     * 当组件被点击时, 此方法被调用.
     * @param relativeX 被点击的在组件内的坐标 X
     * @param relativeY 被点击的在组件内的坐标 Y
     * @param mouseButton 鼠标按钮, 0 为鼠标左键, 1 为鼠标右键
     * @return 是否接收点击事件
     */
    public boolean onMouseClicked(double relativeX, double relativeY, int mouseButton) {
        return this.clickCallback != null && this.isClickable() && this.clickCallback.onClick(relativeX, relativeY, mouseButton);
    }

    protected boolean iterateChildrenKeyType(List<AbstractWidget<?>> children, char typedChar, int keyCode) {
        for (AbstractWidget<?> child : children) {

            if (child.isHidden()) {
                continue;
            }

            if (!child.getChildren().isEmpty()) {
                if (this.iterateChildrenKeyType(child.getChildren(), typedChar, keyCode)) {
                    return true;
                }
            }

            if (child.onKeyTyped(typedChar, keyCode))
                return true;
        }

        return false;
    }

    public boolean onKeyTypedReceived(char typedChar, int keyCode) {
        if (this.isHidden())
            return false;

        // 先检测子组件
        // 如果子组件都没有响应点击事件, 则测试这个组件
        boolean responded = this.iterateChildrenKeyType(this.getChildren(), typedChar, keyCode);
        if (!responded) {
            if (!this.isHidden()) {
                return this.onKeyTyped(typedChar, keyCode);
            }

            return false;
        } else {
            return true;
        }
    }

    /**
     * 当接收到键盘输入时, 此方法被调用.
     * @param character 字符
     * @param keyCode 键码
     */
    public boolean onKeyTyped(char character, int keyCode) {
        return this.keyTypedCallback != null && this.keyTypedCallback.onKeyTyped(character, keyCode);
    }

    public boolean onDWheel(double mouseX, double mouseY, int dWheel) {
        return this.dWheelCallback != null && this.dWheelCallback.onDWheel(mouseX, mouseY, dWheel);
    }

    public SELF setBeforeRenderCallback(RenderCallback beforeRenderCallback) {
        this.beforeRenderCallback = beforeRenderCallback;
        return (SELF) this;
    }

    public Color getColor() {
        return new Color(this.color.getRed(), this.color.getGreen(), this.color.getBlue(), (int) (this.getAlpha() * 255));
    }

    public SELF setColor(int color) {
        this.color = new Color(color);
        return (SELF) this;
    }

    public SELF setColor(Color color) {
        this.color = color;
        return (SELF) this;
    }

    public int getHexColor() {
        int red = this.color.getRed();
        int green = this.color.getGreen();
        int blue = this.color.getBlue();
        return RGBA.color(red, green, blue, (int) (this.getAlpha() * this.fillAlpha * 255));
    }

    public SELF setTransformations(Runnable transformations) {
        this.transformations = transformations;
        return (SELF) this;
    }

    public double getParentX() {
        if (this.getParent() != null)
            return this.getParent().getX();

        return 0;
    }

    public double getParentY() {
        if (this.getParent() != null)
            return this.getParent().getY();

        return 0;
    }

    public double getParentWidth() {

        if (this.getParent() != null)
            return this.getParent().getWidth();

        return RenderSystem.getWidth();
    }

    public double getParentHeight() {

        if (this.getParent() != null)
            return this.getParent().getHeight();

        return RenderSystem.getHeight();
    }

    public SELF setHidden(boolean hidden) {
        this.hidden = hidden;
        return (SELF) this;
    }

    /**
     * 获取用于渲染的 alpha.
     */
    public float getAlpha() {
        // 与父组件的 alpha 相乘
        if (this.getParent() != null)
            return this.getParent().getAlpha() * this.alpha;

        return this.alpha;
    }

    /**
     * 仅获取这个组件的 alpha.
     */
    public float getWidgetAlpha() {
        return this.alpha;
    }

    public SELF setAlpha(float alpha) {
        // 短路：值没变就不重建 Color。封面/卡片这类每帧渐显的控件收敛后不再每帧分配。
        if (alpha == this.alpha) return (SELF) this;
        this.alpha = alpha;
        this.color = new Color(this.color.getRed(), this.color.getGreen(), this.color.getBlue(), (int) (alpha * 255));
        return (SELF) this;
    }

    /**
     * 只压本控件自己那一层填充的不透明度，<b>不</b>传给子控件。
     *
     * <p>给「一块底要变薄、但压在它上面的字与图标不许跟着变薄」这种场合用：
     * 液态玻璃压的就是这种底（见 {@code GlassLook.washAlpha}）。传 NaN、负数或大于 1 按 1 处理，
     * 也就是当作没开——这类系数一旦被算错，宁可看见原来的实色底，也不要一块消失的按钮。</p>
     *
     * <p><b>0 是合法值，必须原样收下。</b>这条判据以前写的是 {@code > 0f}，于是「浓度=0」
     * 算出来的 0 被当成坏值顶回 1，玩家把「玻璃浓度」往左拖到底反而得到一片<em>全不透明</em>的
     * 深色底块：曲目行、搜索框、来源按钮、底栏一起变黑，而面板根底那一层走的是另一条
     * 带下限的曲线（{@code GlassLook.rootFactor} 最低 0.18）还留着透——症状就是
     * 「外面是玻璃，页面里面一块块黑斑，而且越调越黑」。2026-09-30 真机查出来的就是这一句。</p>
     */
    public SELF setFillAlpha(float fillAlpha) {
        this.fillAlpha = sanitizeFillAlpha(fillAlpha);
        return (SELF) this;
    }

    /**
     * {@link #setFillAlpha} 那一步判据，单独拎出来是因为它错了的症状（整页黑斑）比这里任何
     * 一行代码都贵，而它又完全不需要真机：喂几个数就能算清。
     *
     * <p>钉住的边界是 <b>0 必须原样收下</b>：它不是坏值，它是「玻璃浓度」推到 0 时
     * {@code GlassLook.washAlpha} 的正确结果。</p>
     */
    static float sanitizeFillAlpha(float fillAlpha) {
        return fillAlpha >= 0f && fillAlpha <= 1f ? fillAlpha : 1.0f;
    }

    public float getFillAlpha() {
        return this.fillAlpha;
    }

    /**
     * 这块控件要不要画成<em>一块自己的玻璃</em>，以及画成哪一种。
     *
     * <p>之所以要三档而不是两档：一块贴在面板上的按钮、一张盖在遮罩上的二级菜单、
     * 和那张菜单<em>里面</em>的一行卡片，它们背后的东西根本不是一回事。</p>
     * <ul>
     *   <li><b>PLATE</b>：背后就是这一帧采下来的游戏画面，直接贴上去对得上。</li>
     *   <li><b>SHEET</b>：背后是我们自己的面板加一层遮罩，这两样都不在采集里。所以贴真背景
     *       之前要先按遮罩那一档压暗（{@code SkijaEffects.dimmedBackdrop}）——不压的话
     *       等于在一张压暗的桌面上开一扇透着大晴天的窗，弹窗会比它周围<em>更亮</em>，
     *       读起来不像玻璃，像贴图穿帮。2026-09-30 玩家反馈「二级菜单没有液态玻璃」，
     *       当时的做法是干脆只给一道边光，那是把这一档整个跳过，不是解决。</li>
     *   <li><b>RIM</b>：已经在玻璃<em>里面</em>的小片（弹窗里的卡、胶囊、图标盘）。
     *       给它再贴一层真背景，等于在玻璃上再开一扇看同一处风景的窗——形状完全重合，
     *       只是把那块玻璃又擦干净一遍，读起来是「这块没上色」而不是「这是一块玻璃」。
     *       所以它只上自己那道边缘光。</li>
     * </ul>
     */
    public enum GlassMode {
        /** 不画。 */
        NONE,
        /** 完整一块小玻璃：贴预糊背景 + 自己那层底色 + 薄折射 + 边缘高光。 */
        PLATE,
        /** 弹窗那一张：贴<em>压暗过的</em>预糊背景 + 自己那层底色 + 边缘高光，不折。 */
        SHEET,
        /** 只上<em>表面</em>：一道边缘高光。给已经落在某块玻璃里的小片用。 */
        RIM
    }

    /** 与 {@link #glassMode} 配对：这块属于哪个生效面（开关与主题强度由 GlassLook 判）。 */
    private GlassLook.Surface glassSurface;
    private GlassMode glassMode = GlassMode.NONE;

    /** 元素级那一层贴的预糊背景占多少：1 = 完全把底下的面板画面顶掉，行就会亮成一片。 */
    private static final float ELEMENT_FROST_MIX = 0.55f;
    /**
     * 弹窗那一层贴的预糊背景占多少。比元素档高，是因为它后面还压一层黑纱
     * （{@code SkijaEffects.SHEET_VEIL}），一减一加之后透出来的量才和面板上那些行相当。
     */
    private static final float SHEET_FROST_MIX = 0.80f;

    /**
     * 把这块控件标成一块完整的玻璃（贴在面板上的行、按钮、输入框用这个）。
     *
     * <p>传的是「哪一面」而不是「要不要」：生效面开关与主题强度都由 {@code GlassLook} 判。</p>
     */
    public SELF setGlassPlate(GlassLook.Surface surface) {
        this.glassSurface = surface;
        this.glassMode = GlassMode.PLATE;
        return (SELF) this;
    }

    /**
     * 弹窗那一张玻璃：贴<em>压暗过的</em>真背景 + 一道边缘高光。
     *
     * <p>给盖在遮罩之上的二级菜单用。它不折边——折射吃的是背景本身，而这一块「背景」
     * 是压暗补出来的，绕一圈亮边会把那层纱顶穿，读回「透着晴天的窗」。</p>
     */
    public SELF setGlassSheet(GlassLook.Surface surface) {
        this.glassSurface = surface;
        this.glassMode = GlassMode.SHEET;
        return (SELF) this;
    }

    /** 只上玻璃<em>表面</em>那一圈高光，不贴背景。给弹窗里面的卡与胶囊用，理由见 {@link GlassMode}。 */
    public SELF setGlassRim(GlassLook.Surface surface) {
        this.glassSurface = surface;
        this.glassMode = GlassMode.RIM;
        return (SELF) this;
    }

    public GlassMode getGlassMode() {
        return this.glassMode;
    }

    /**
     * 这个容器的<em>子控件</em>们挨着的那些玻璃，收尾时按一整张距离场一起画（融合）。
     * null = 各块各画各的。只有 {@link com.muoniumplayer.core.rendering.GlassLook.Surface}
     * 为 PLAYER 一类的成片容器才该点名它。
     */
    private GlassLook.Surface glassFusionSurface;

    /**
     * 把这一块<em>容器</em>标成融合组：它画完所有子控件之后，子控件那些登记过的玻璃边缘
     * 由一趟着色器按一整张 soft-min 距离场画出来，于是挨着的两块中间会鼓出一道颈。
     *
     * <p>半径不在这里传，画的那一刻才读设置（{@code GlassLook.fusionRadius}），
     * 于是用户拖动滑块立刻生效，不必重建控件树。</p>
     *
     * <p>只有 <b>PLATE</b> 那一档的子控件会入组（见 {@code GlassRefraction#joined}）：
     * RIM 的小片本来就在别人那块玻璃<em>里面</em>，把它们也连起来等于在玻璃上又焊一层壳。
     * 所以点名一个容器之前要先数清它的子控件里有几块挨着的 PLATE，一块都没有就等于没接。</p>
     *
     * <p>可以嵌套，代价要说清：内层容器开组之前会把外层那一组<em>先收掉画完</em>，
     * 于是外层里排在内层之后的那些兄弟各自退回逐矩形画边（少几道颈，不会画错位置）。
     * 之所以不能让外层继续攒着——外层的收尾发生在内层的裁剪之外，
     * 症状是滚出视口的行把一整排亮边盖到面板别处。</p>
     */
    public SELF setGlassFusion(GlassLook.Surface surface) {
        this.glassFusionSurface = surface;
        return (SELF) this;
    }

    /**
     * 画在<em>自己的底色之前</em>：把预糊过的真背景按这一块的位置贴一层
     * （弹窗那一档再在其上压一层黑纱，见 {@code GlassLook.paintSheetFrost}）。
     *
     * <p>返回 false 表示这一层没铺上背景（没标、RIM 档、或这一帧根本没有背景），
     * {@link #glassPlateEdge} 据此决定要不要折那一圈。</p>
     */
    protected final boolean glassPlateBase(double x, double y, double width, double height,
                                           double radius) {
        if (this.glassMode == GlassMode.NONE || this.glassMode == GlassMode.RIM) {
            return false;
        }
        if (this.glassMode == GlassMode.SHEET) {
            return GlassLook.paintSheetFrost(this.glassSurface, x, y, width, height, radius,
                    this.getAlpha() * SHEET_FROST_MIX);
        }
        return GlassLook.paintFrost(this.glassSurface, x, y, width, height, radius,
                this.getAlpha() * ELEMENT_FROST_MIX);
    }


    /** 画在<em>自己的底色之后</em>：一圈薄折射（只有 PLATE 折）加一道边缘高光。 */
    protected final void glassPlateEdge(boolean frosted, double x, double y,
                                        double width, double height, double radius) {
        if (this.glassMode == GlassMode.NONE) {
            return;
        }
        // 折射只给 PLATE：它吃的是背景本身，而弹窗那一档的背景是「压暗补出来」的，绕一圈
        // 亮边会把那层纱顶穿，读回「透着晴天的窗」。RIM 那一档连背景都没有，更不折。
        if (frosted && this.glassMode == GlassMode.PLATE) {
            GlassLook.paintElementEdge(this.glassSurface, x, y, width, height, radius,
                    this.getAlpha());
        }
        float rim = GlassLook.elementRim(this.glassSurface, this.getAlpha());
        if (rim <= 0.002f || width <= 1.0 || height <= 1.0) {
            return;
        }
        int alpha = (int) Math.max(0, Math.min(255, rim * 52f));
        if (this.glassMode == GlassMode.SHEET || this.glassMode == GlassMode.RIM) {
            // 一张屏最多一个弹窗，给它一道「上亮下暗」的渐变描边：那是玻璃受光的样子，
            // 只有一圈均匀白边的话读起来更像描了个框。弹窗里面的卡与胶囊同一条画法。
            int cool = (int) (alpha * .42f);
            roundedOutlineGradient(x, y, width, height, radius, .7,
                    new Color(186, 216, 255, cool), new Color(255, 255, 255, alpha),
                    new Color(186, 216, 255, cool), new Color(255, 255, 255, alpha));
            return;
        }
        // 行与按钮一屏就是几十个，这里用单色描边（一次成型的实线），不走四角渐变那个 shader。
        roundedOutline(x, y, width, height, radius, .5, new Color(255, 255, 255, alpha));
    }

    public SELF setOnClickCallback(OnClickCallback callback) {
        this.clickCallback = callback;
        return (SELF) this;
    }

    public SELF setOnKeyTypedCallback(OnKeyTypedCallback callback) {
        this.keyTypedCallback = callback;
        return (SELF) this;
    }

    /**
     * @return 返回这个组件的屏幕 X 坐标.
     */
    public double getX() {

        // 如果父组件为空则直接返回 X 坐标
        if (this.getParent() == null)
            return this.getBounds().x;

        return this.getParent().getX() + this.getBounds().x;
    }

    /**
     * @return 返回这个组件的屏幕 Y 坐标.
     */
    public double getY() {

        // 如果父组件为空则直接返回 Y 坐标
        if (this.getParent() == null)
            return this.getBounds().y;

        return this.getParent().getY() + this.getBounds().y;
    }

    /**
     * @return 获取这个组件的偏移 X 坐标.
     */
    public double getRelativeX() {
        return this.getBounds().x;
    }

    /**
     * @return 获取这个组件的偏移 Y 坐标.
     */
    public double getRelativeY() {
        return this.getBounds().y;
    }

    /**
     * @return 获取这个组件的宽度.
     */
    public double getWidth() {
        return this.getBounds().width;
    }

    /**
     * @return 获取这个组件的高度.
     */
    public double getHeight() {
        return this.getBounds().height;
    }

    /**
     * 设置组件的宽度.
     * @param width 宽度
     */
    public SELF setWidth(double width) {
        this.getBounds().width = width;
        return (SELF) this;
    }

    /**
     * 设置组件的高度.
     * @param height 高度
     */
    public SELF setHeight(double height) {
        this.getBounds().height = height;
        return (SELF) this;
    }

    /**
     * 设置组件的偏移位置和大小.
     * @param x 相对于父组件的 X 偏移
     * @param y 相对于父组件的 Y 偏移
     * @param width 宽
     * @param height 长
     */
    public SELF setBounds(double x, double y, double width, double height) {
        this.getBounds().x = x;
        this.getBounds().y = y;
        this.getBounds().width = width;
        this.getBounds().height = height;
        return (SELF) this;
    }

    /**
     * 使组件居中.
     */
    public SELF center() {
        return this.setPosition(this.getParentWidth() * .5 - this.getWidth() * .5, this.getParentHeight() * .5 - this.getHeight() * .5);
    }

    /**
     * 使组件水平居中.
     */
    public SELF centerHorizontally() {
        return this.setPosition(this.getParentWidth() * .5 - this.getWidth() * .5, this.getRelativeY());
    }

    /**
     * 使组件垂直居中.
     */
    public SELF centerVertically() {
        return this.setPosition(this.getRelativeX(), this.getParentHeight() * .5 - this.getHeight() * .5);
    }

    /**
     * 设置子组件相对于父组件的坐标偏移
     * @param x X 坐标偏移
     * @param y Y 坐标偏移
     */
    public SELF setPosition(double x, double y) {
        this.getBounds().x = x;
        this.getBounds().y = y;
        return (SELF) this;
    }

    /**
     * 设置子组件渲染时的绝对坐标
     * @param x X 坐标
     * @param y Y 坐标
     */
    public SELF setPositionAbsolute(double x, double y) {
        this.getBounds().x = this.getBounds().y = 0;
        this.getBounds().x = x - this.getX();
        this.getBounds().y = y - this.getY();
        return (SELF) this;
    }

    /**
     * 设置组件的大小.
     */
    public SELF setBounds(double size) {
        return this.setBounds(size, size);
    }

    /**
     * 设置组件的大小.
     */
    public SELF setBounds(double width, double height) {
        this.getBounds().width = width;
        this.getBounds().height = height;
        return (SELF) this;
    }

    /**
     * 设置组件是否可点击.
     */
    public SELF setClickable(boolean clickable) {
        this.clickable = clickable;
        return (SELF) this;
    }

    /**
     * 设置组件的父组件.
     */
    public SELF setParent(AbstractWidget<?> parent) {
        this.parent = parent;
        return (SELF) this;
    }

}
