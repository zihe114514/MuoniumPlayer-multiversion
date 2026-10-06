package com.muoniumplayer.core.rendering.ui.container;

import lombok.Getter;
import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.core.rendering.Rect;
import com.muoniumplayer.core.rendering.ScissorClipManager;
import com.muoniumplayer.core.rendering.StencilClipManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * 一个从上至下排列的可以滚动的容器
 * @author MuoniumPlayer Contributors
 * Date: 2025/7/8 21:45
 */
public class ScrollPanel extends AbstractWidget<ScrollPanel> {

    /** A fixed sentinel stays outside the viewport even when the panel is resized next frame. */
    private static final double OUTSIDE_VIEWPORT_Y = -1_000_000.0;

    @Getter
    private double spacing = 0;
    @Getter
    private double scrollStrength = 12;
    public double actualScrollOffset = 0, targetScrollOffset = 0;

    /** 最近一次滚轮输入，用于在快速滚动时跳过新条目的二级进场矩阵。 */
    private long lastScrollInputNanos;

    /**
     * 垂直等高列表的快速路径。歌曲行都是固定高度时，不需要每帧给几千个子项重新排版；
     * 只维护当前视口附近的子项，其余子项放到裁剪区外。
     */
    private double uniformVerticalItemHeight = -1.0;
    private int uniformFirstVisible = -1;
    private int uniformLastVisible = -1;
    /** Visible object identities let list replacement stay O(visible) without leaving old rows behind. */
    private final Set<AbstractWidget<?>> uniformVisibleChildren =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<AbstractWidget<?>> uniformNextVisibleChildren =
            Collections.newSetFromMap(new IdentityHashMap<>());

    @Getter
    private Alignment alignment = Alignment.VERTICAL;

    /**
     * 子组件的排列方式
     */
    public enum Alignment {
        /**
         * 仅垂直排列 (默认)
         */
        VERTICAL,
        /**
         * 仅水平排列
         */
        HORIZONTAL,
        /**
         * 垂直排列并水平填充
         */
        VERTICAL_WITH_HORIZONTAL_FILL
    }

    public ScrollPanel setAlignment(Alignment alignment) {

        if (alignment == null)
            throw new IllegalArgumentException("Alignment cannot be null!");

        this.alignment = alignment;
        this.resetUniformLayoutState();
        return this;
    }

    /**
     * 为固定高度的垂直条目启用排版快速路径。传入非正数可关闭。
     * 隐藏/筛选条目时应关闭此模式，因为隐藏项会改变实际行高。
     */
    public ScrollPanel setUniformVerticalItemHeight(double height) {
        this.uniformVerticalItemHeight = height > 0.0 ? height : -1.0;
        this.resetUniformLayoutState();
        if (this.uniformVerticalItemHeight > 0.0) {
            this.placeUniformChildrenOutsideViewport();
        }
        return this;
    }

    private boolean hasUniformVerticalLayout() {
        return this.alignment == Alignment.VERTICAL && this.uniformVerticalItemHeight > 0.0;
    }

    private void resetUniformLayoutState() {
        this.uniformFirstVisible = -1;
        this.uniformLastVisible = -1;
        this.uniformVisibleChildren.clear();
        this.uniformNextVisibleChildren.clear();
    }

    private double uniformContentLength() {
        int count = this.getChildren().size();
        if (count <= 0) return 0.0;
        return count * this.uniformVerticalItemHeight + Math.max(0, count - 1) * this.spacing;
    }

    private void placeUniformChildrenOutsideViewport() {
        this.placeUniformChildrenOutsideViewport(this.getChildren());
    }

    private void placeUniformChildrenOutsideViewport(List<AbstractWidget<?>> children) {
        for (AbstractWidget<?> child : children) {
            child.onViewportExit();
            child.setPosition(child.getRelativeX(), OUTSIDE_VIEWPORT_Y);
        }
    }

    private void placeUniformChildrenOutsideViewport(AbstractWidget<?>[] children) {
        for (AbstractWidget<?> child : children) {
            child.onViewportExit();
            child.setPosition(child.getRelativeX(), OUTSIDE_VIEWPORT_Y);
        }
    }


    @Override
    public void addChild(AbstractWidget<?>... children) {
        super.addChild(children);
        if (this.hasUniformVerticalLayout()) {
            this.placeUniformChildrenOutsideViewport(children);
        }
    }

    @Override
    public void addChild(List<AbstractWidget<?>> children) {
        super.addChild(children);
        if (this.hasUniformVerticalLayout()) {
            this.placeUniformChildrenOutsideViewport(children);
        }
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        // 以前这一帧会为 clamp、actual 再 clamp、align 各自扫描一遍全部子项。
        // 歌单几百/几千行时，滚轮期间这类重复 O(n) 会和可见行绘制叠在一起。
        // 等高歌曲行走固定高度快速路径，只计算数量并更新视口附近的行。
        if (this.hasUniformVerticalLayout()) {
            double maximumScrollOffset = Math.max(0.0, this.uniformContentLength() - this.getHeight());
            this.clampScrollOffset(maximumScrollOffset);
            this.actualScrollOffset = Interpolations.interpolate(this.actualScrollOffset, this.targetScrollOffset, 1f);
            this.actualScrollOffset = Math.max(0, Math.min(this.actualScrollOffset, maximumScrollOffset));
            this.alignUniformChildren();
            return;
        }

        // 非固定高度容器每帧只计算一次内容高度，再复用结果完成偏移修正与排版。
        double maximumScrollOffset = this.getMaximumScrollOffset();
        this.clampScrollOffset(maximumScrollOffset);
        this.actualScrollOffset = Interpolations.interpolate(this.actualScrollOffset, this.targetScrollOffset, 1f);
        this.actualScrollOffset = Math.max(0, Math.min(this.actualScrollOffset, maximumScrollOffset));

        // 设置子组件的垂直位置
        this.alignChildren(maximumScrollOffset);
    }

    @Override
    protected void renderDebugLayout() {
        super.renderDebugLayout();
        Rect.draw(this.getX(), this.getY(), this.getWidth(), this.getHeight(), reAlpha(0x00F50EE8, this.getAlpha() * .05f));
    }

    public ScrollPanel setSpacing(double spacing) {
        this.spacing = spacing;
        return this;
    }

    /**
     * 设置每个滚轮刻度移动的距离。内容较长的面板可以提高该值，避免用户
     * 需要滚动大量次数才能到达末尾。
     */
    public ScrollPanel setScrollStrength(double scrollStrength) {
        this.scrollStrength = Math.max(1, scrollStrength);
        return this;
    }

    @Override
    public boolean onDWheel(double mouseX, double mouseY, int dWheel) {

        // 垂直滑动
        if (dWheel != 0) this.lastScrollInputNanos = System.nanoTime();
        this.performScroll(dWheel);
        super.onDWheel(mouseX, mouseY, dWheel);

        return true;
    }

    /** 在滚轮后的短窗口内认为用户正在快速浏览，不为刚进入视口的条目叠加动画。 */
    public boolean isScrollActive() {
        return this.lastScrollInputNanos != 0L
                && System.nanoTime() - this.lastScrollInputNanos < 140_000_000L;
    }

    private void performScroll(int dWheel) {

        if (dWheel != 0) {

            double strength = this.scrollStrength;

            if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT))
                strength *= 2;

            if (dWheel > 0)
                this.targetScrollOffset -= strength;
            else
                this.targetScrollOffset += strength;
        }

        this.clampScrollOffset();
    }

    private void clampScrollOffset() {
        this.clampScrollOffset(this.getMaximumScrollOffset());
    }

    private void clampScrollOffset(double maximumScrollOffset) {
        this.targetScrollOffset = Math.max(0, Math.min(this.targetScrollOffset, maximumScrollOffset));
    }

    private double getMaximumScrollOffset() {
        if (this.hasUniformVerticalLayout()) {
            return Math.max(0.0, this.uniformContentLength() - this.getHeight());
        }

        double contentLength;
        switch (this.alignment) {
            case HORIZONTAL:
                contentLength = this.getChildrenWidthSum();
                return Math.max(0, contentLength - this.getWidth());
            case VERTICAL_WITH_HORIZONTAL_FILL:
                contentLength = this.getChildrenHeightSumHorizontalFill();
                return Math.max(0, contentLength - this.getHeight());
            case VERTICAL:
            default:
                contentLength = this.getChildrenHeightSum();
                return Math.max(0, contentLength - this.getHeight());
        }
    }

    public void scrollToEnd() {
        switch (this.alignment) {
            case VERTICAL: {
                double childrenHeightSum = this.getChildrenHeightSum();
                if (childrenHeightSum > this.getHeight())
                    this.targetScrollOffset = childrenHeightSum - this.getHeight();
                else
                    this.targetScrollOffset = 0;
                break;
            }
            case HORIZONTAL: {
                double childrenWidthSum = this.getChildrenWidthSum();
                if (childrenWidthSum > this.getWidth())
                    this.targetScrollOffset = childrenWidthSum - this.getWidth();
                else
                    this.targetScrollOffset = 0;
                break;
            }
            case VERTICAL_WITH_HORIZONTAL_FILL: {
                double childrenHeightSum = this.getChildrenHeightSumHorizontalFill();
                if (childrenHeightSum > this.getHeight())
                    this.targetScrollOffset = childrenHeightSum - this.getHeight();
                else
                    this.targetScrollOffset = 0;
                break;
            }
        }
    }

    public void scrollToEndImmediately() {
        switch (this.alignment) {
            case VERTICAL: {
                double childrenHeightSum = this.getChildrenHeightSum();
                if (childrenHeightSum > this.getHeight())
                    this.actualScrollOffset = childrenHeightSum - this.getHeight();
                else
                    this.actualScrollOffset = 0;
                break;
            }
            case HORIZONTAL: {
                double childrenWidthSum = this.getChildrenWidthSum();
                if (childrenWidthSum > this.getWidth())
                    this.actualScrollOffset = childrenWidthSum - this.getWidth();
                else
                    this.actualScrollOffset = 0;
                break;
            }
            case VERTICAL_WITH_HORIZONTAL_FILL: {
                double childrenHeightSum = this.getChildrenHeightSumHorizontalFill();
                if (childrenHeightSum > this.getHeight())
                    this.actualScrollOffset = childrenHeightSum - this.getHeight();
                else
                    this.actualScrollOffset = 0;
                break;
            }
        }
    }

    public boolean isScrolledToEnd() {
        if (this.alignment == Alignment.VERTICAL) {
            double childrenHeightSum = this.getChildrenHeightSum();
            if (childrenHeightSum > this.getHeight())
                return this.targetScrollOffset >= childrenHeightSum - this.getHeight();
            else
                return this.targetScrollOffset == 0;
        } else if (this.alignment == Alignment.HORIZONTAL) {
            double childrenWidthSum = this.getChildrenWidthSum();
            if (childrenWidthSum > this.getWidth())
                return this.targetScrollOffset >= childrenWidthSum - this.getWidth();
            else
                return this.targetScrollOffset == 0;
        } else {
            double childrenHeightSum = this.getChildrenHeightSumHorizontalFill();
            if (childrenHeightSum > this.getHeight())
                return this.targetScrollOffset >= childrenHeightSum - this.getHeight();
            else
                return this.targetScrollOffset == 0;
        }
    }

    public void alignChildren() {
        if (this.hasUniformVerticalLayout()) {
            this.alignUniformChildren();
            return;
        }
        this.alignChildren(this.getMaximumScrollOffset());
    }

    private void alignUniformChildren() {
        List<AbstractWidget<?>> children = this.getChildren();
        int count = children.size();
        if (count == 0) {
            this.resetUniformLayoutState();
            return;
        }

        double stride = this.uniformVerticalItemHeight + this.spacing;
        int first = Math.max(0, (int) Math.floor(this.actualScrollOffset / stride) - 1);
        int last = Math.min(count - 1,
                (int) Math.ceil((this.actualScrollOffset + Math.max(0.0, this.getHeight())) / stride) + 1);

        // Build the next visible identity set first. This handles fast jumps and arbitrary list
        // replacement without scanning every child or relying on stale integer indices.
        this.uniformNextVisibleChildren.clear();
        for (int index = first; index <= last; index++) {
            this.uniformNextVisibleChildren.add(children.get(index));
        }

        for (AbstractWidget<?> child : this.uniformVisibleChildren) {
            if (!this.uniformNextVisibleChildren.contains(child)) {
                child.onViewportExit();
                child.setPosition(child.getRelativeX(), OUTSIDE_VIEWPORT_Y);
            }
        }

        for (int index = first; index <= last; index++) {
            AbstractWidget<?> child = children.get(index);
            child.setPosition(child.getRelativeX(), index * stride - this.actualScrollOffset);
        }

        this.uniformVisibleChildren.clear();
        this.uniformVisibleChildren.addAll(this.uniformNextVisibleChildren);
        this.uniformNextVisibleChildren.clear();
        this.uniformFirstVisible = first;
        this.uniformLastVisible = last;
    }

    private void alignChildren(double maximumScrollOffset) {
        double offsetX = 0;
        double offsetY = 0;
        double rowHeight = 0;

        if (this.alignment == Alignment.VERTICAL || this.alignment == Alignment.VERTICAL_WITH_HORIZONTAL_FILL)
            offsetY = -this.actualScrollOffset;
        else
            offsetX = -this.actualScrollOffset;

        // maxScrollOffset 已经在本帧计算过，避免在这里再次扫描全部子项。
        this.targetScrollOffset = Math.max(0, Math.min(this.targetScrollOffset, maximumScrollOffset));

        for (AbstractWidget<?> child : this.getChildren()) {

            double width = child.getWidth();
            double height = child.getHeight();

            if (child.isHidden())
                continue;

            switch (this.alignment) {
                case VERTICAL: {
                    child.setPosition(child.getRelativeX(), offsetY);
                    offsetY += height + spacing;
                    break;
                }
                case HORIZONTAL: {
                    child.setPosition(offsetX, child.getRelativeY());
                    offsetX += width + spacing;
                    break;
                }
                case VERTICAL_WITH_HORIZONTAL_FILL: {
                    // 当子组件比极小视口更宽时，不要创建空的第一行。
                    // 对于混合大小的控件，按已完成行中最高的项
                    // 推进，而不是按恰好换行的那个项。
                    if (offsetX > 0 && offsetX + width > this.getWidth()) {
                        offsetX = 0;
                        offsetY += rowHeight + spacing;
                        rowHeight = 0;
                    }

                    child.setPosition(offsetX, offsetY);
                    offsetX += width + spacing;
                    rowHeight = Math.max(rowHeight, height);
                    break;
                }
            }
        }
    }

    protected double getChildrenHeightSum() {
        double result = 0;

        List<AbstractWidget<?>> children = this.getChildren();

        for (AbstractWidget<?> child : children) {

            double width = child.getWidth();
            double height = child.getHeight();

            if (child.isHidden())
                continue;

            result += height + this.spacing;
        }

        if (result > 0)
            result -= this.spacing;

        return result;
    }

    protected double getChildrenWidthSum() {
        double result = 0;

        List<AbstractWidget<?>> children = this.getChildren();

        for (AbstractWidget<?> child : children) {

            double width = child.getWidth();
            double height = child.getHeight();

            if (child.isHidden())
                continue;

            result += width + this.spacing;
        }

        if (result > 0)
            result -= this.spacing;

        return result;
    }

    protected double getChildrenHeightSumHorizontalFill() {
        double result = 0;
        double offsetX = 0;
        double rowHeight = 0;

        List<AbstractWidget<?>> children = this.getChildren();

        for (AbstractWidget<?> child : children) {

            double width = child.getWidth();
            double height = child.getHeight();

            if (child.isHidden())
                continue;

            if (offsetX > 0 && offsetX + width > this.getWidth()) {
                result += rowHeight + spacing;
                offsetX = 0;
                rowHeight = 0;
            }

            offsetX += width + spacing;
            rowHeight = Math.max(rowHeight, height);
        }

        if (rowHeight > 0) {
            result += rowHeight;
        }

        return result;
    }

    @Override
    protected void beforeRenderChildren(double mouseX, double mouseY) {
        // 模板缓冲提供普通的组件裁剪，而投影裁剪矩形是一个
        // 硬屏幕空间边界。圆角封面渲染可能打开嵌套模板
        // 层级，但它无法再逃逸到标题栏上方或控制栏下方。
        ScissorClipManager.begin(this.getX(), this.getY(), this.getWidth(), this.getHeight());
        StencilClipManager.beginClip(() ->
                Rect.draw(this.getX(), this.getY(), this.getWidth(), this.getHeight(), -1));
    }

    @Override
    protected void afterRenderChildren(double mouseX, double mouseY) {
        try {
            StencilClipManager.endClip();
        } finally {
            ScissorClipManager.end();
        }
    }

    @Override
    protected boolean shouldRenderChildren(AbstractWidget<?> child, double mouseX, double mouseY) {

        switch (this.getAlignment()) {
            case VERTICAL:
            case VERTICAL_WITH_HORIZONTAL_FILL:
                if (child.getRelativeY() + child.getHeight() < 0)
                    return false;

                if (child.getRelativeY() > this.getHeight())
                    return false;
                break;
            case HORIZONTAL:
                if (child.getRelativeX() + child.getWidth() < 0)
                    return false;

                if (child.getRelativeX() > this.getWidth())
                    return false;
                break;
        }

        return true;
    }

    @Override
    protected boolean shouldClickChildren(double mouseX, double mouseY) {
//        System.out.println(this.testHovered(mouseX, mouseY));
        return this.testHovered(mouseX, mouseY);
    }

    @Override
    public boolean canBeScrolled() {
        return true;
    }
}
