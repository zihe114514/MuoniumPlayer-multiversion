package com.muoniumplayer.core.rendering.ui.widgets;

import lombok.Getter;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.entities.impl.ScrollText;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.utils.Lazy;

import java.util.function.Supplier;

/**
 * @author MuoniumPlayer Contributors
 * Date: 2025/7/8 20:59
 */
public class LabelWidget extends AbstractWidget<LabelWidget> {

    Supplier<String> label = () -> "点击输入文字";
    @Getter
    CFontRenderer font = FontManager.pf18;

    @Getter
    double maxWidth = -1;

    @Getter
    private WidthLimitType widthLimitType = WidthLimitType.SCROLL;

    public enum WidthLimitType {
        SCROLL,
        TRIM_TO_WIDTH
    }

    Lazy<ScrollText> scrollText = Lazy.of(ScrollText::new);

    public LabelWidget(String label, CFontRenderer font) {
        this.setLabel(label);
        this.setFont(font);
    }

    public LabelWidget(Supplier<String> label, CFontRenderer font) {
        this.setLabel(label);
        this.setFont(font);
    }

    public LabelWidget(String label) {
        this.setLabel(label);
    }

    public LabelWidget(Supplier<String> label) {
        this.setLabel(label);
    }

    public LabelWidget() {

    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        boolean widthNotLimited = this.getMaxWidth() == -1;
        String lbl = this.getLabel();
        CFontRenderer activeFont = this.font != null ? this.font : FontManager.pf18;

        // 标签广泛与异步/状态驱动的 Supplier 配合使用。
        // 在页面重建期间 provider 可能临时返回 null，而且
        // 在第一帧初始化期间渲染器也可能不可用。
        // 绝不让这两种瞬时状态中断整个 NCMScreen 树。
        if (activeFont == null) {
            this.setBounds(0, 0);
            return;
        }

        if (widthNotLimited) {
            activeFont.drawString(lbl, this.getX(), this.getY(), this.getHexColor());
        } else if (this.getMaxWidth() <= 0.0) {
            // 折叠的父容器或过渡中的布局可能请求负宽度。
            // 该空间内没有可绘制的有效文本；而是保持组件存活。
            this.setBounds(0, Math.max(0, activeFont.getStringHeight(lbl)));
            return;
        } else if (this.widthLimitType == WidthLimitType.SCROLL) {
            this.scrollText.getValue().render(activeFont, lbl, this.getX(), this.getY(),
                    this.getMaxWidth(), this.getHexColor());
        } else {
            activeFont.drawString(activeFont.trim(lbl, this.getMaxWidth()), this.getX(), this.getY(), this.getHexColor());
        }

        double stringWidth = activeFont.getStringWidthD(lbl);
        double width = widthNotLimited ? stringWidth : Math.min(this.getMaxWidth(), stringWidth);
        this.setBounds(Math.max(0, width), Math.max(0, activeFont.getStringHeight(lbl)));
    }

    public LabelWidget setMaxWidth(double maxWidth) {
        // -1 是文档规定的"无限"哨兵值。其他任何负数或非有限值
        // 都来自瞬时布局计算，并被归一化为 0，以便 onRender 可以
        // 安全地跳过它，而不是转发给 Skia。
        if (maxWidth == -1.0) {
            this.maxWidth = -1.0;
        } else {
            this.maxWidth = Double.isFinite(maxWidth) ? Math.max(0.0, maxWidth) : 0.0;
        }
        return this;
    }

    public LabelWidget setWidthLimitType(WidthLimitType widthLimitType) {
        this.widthLimitType = widthLimitType;
        return this;
    }

    public LabelWidget setFont(CFontRenderer font) {
        // 让 null 值可恢复：构造组件时 FontManager.pf18 可能尚未就绪，
        // 因此 onRender 会在之后解析回退字体。
        this.font = font;
        return this;
    }

    public String getLabel() {
        Supplier<String> supplier = this.label;
        if (supplier == null) {
            return "";
        }
        try {
            String value = supplier.get();
            return value == null ? "" : value;
        } catch (RuntimeException ignored) {
            // 瞬时的 provider 失败绝不能导致所有面板渲染崩溃。
            return "";
        }
    }

    public LabelWidget setLabel(String label) {
        this.setLabel(() -> label == null ? "" : label);
        return this;
    }

    public LabelWidget setLabel(Supplier<String> label) {
        this.label = label == null ? () -> "" : label;
        return this;
    }
}
