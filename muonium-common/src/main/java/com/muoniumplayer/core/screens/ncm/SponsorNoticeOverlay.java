package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.update.AnnouncementChecker;
import com.muoniumplayer.core.update.AnnouncementImageCache;
import com.muoniumplayer.core.update.SponsorNotice;
import com.muoniumplayer.core.utils.KeyboardUtils;
import com.muoniumplayer.core.utils.other.BrowserUtils;
import com.muoniumplayer.party.ui.PartyIcons;
import com.muoniumplayer.render.skija.SkijaGfx;
import io.github.humbleui.types.Rect;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** 自愿赞助提示；启动计数归 SponsorNotice，图片随本浮层的真实生命周期释放。 */
public final class SponsorNoticeOverlay extends NCMPanel {
    public static final String AFDIAN_URL = "https://afdian.com/a/xinshibaobao";
    public static final String WECHAT_URL = "https://melodifymc.cc.cd/sponsor.html#wechat";
    public static final String AFDIAN_IMAGE = "/assets/muonium/textures/sponsor/afdian-qr.png";
    public static final String WECHAT_IMAGE = "/assets/muonium/textures/sponsor/wechat-qr.jpg";
    private static final double INSET = 24;
    private static final double HEADER = 98;
    private static final double GAP = 16;
    private static final String BODY = "MuoniumPlayer 是纯公益、免费的音乐 MOD。\n如果它让你的游戏时光更好听，可以自愿支持开发与日常维护。";

    private final long launches;
    private final boolean repeat;
    private final Runnable later;
    private final Runnable forever;
    private final Consumer<String> linkOpener;
    private final AnnouncementImageCache images;
    private final SheetClock clock = SheetClock.cascade();
    private final NcmExpressiveLayout layout = new NcmExpressiveLayout();
    private final List<LabelWidget> bodyLabels = new ArrayList<>();
    private final Channel[] channels = {new Channel("爱发电", "扫码前往作者的发电页", AFDIAN_IMAGE, AFDIAN_URL),
            new Channel("微信赞赏", "收款方：心事抱抱 · 自愿赞赏", WECHAT_IMAGE, WECHAT_URL)};
    private Panel dialog;
    private ScrollPanel viewport;
    private Panel content;
    private Panel footer;
    private RoundedButtonWidget neverButton;
    private double presentation;
    private double phase;
    private boolean closing;
    private boolean resolved;
    private boolean released;
    private boolean initialized;
    private int enlarged = -1;
    private String status = "不赞助，功能一个都不少。谢谢你愿意听到这里～";
    private String[] bodyLines = new String[0];
    private CFontRenderer wrappedFont;
    private long wrappedRevision = -1;
    private double wrappedWidth = -1;
    private double introHeight;
    private double cardsTop;
    private double cardWidth;
    private double cardHeight;
    private double imageSize;
    private double contentHeight;
    private boolean stacked;

    public SponsorNoticeOverlay(long launches, boolean repeat) {
        this(launches, repeat, SponsorNotice::remindLater, SponsorNotice::neverShow, null,
                new AnnouncementImageCache());
    }

    /** 离线探针注入动作，不修改用户配置、不打开浏览器；仍绘制同一份控件与图片。 */
    public SponsorNoticeOverlay(long launches, boolean repeat, Runnable later, Runnable forever,
                                Consumer<String> linkOpener, AnnouncementImageCache images) {
        this.launches = Math.max(0, launches);
        this.repeat = repeat;
        this.later = Objects.requireNonNull(later);
        this.forever = Objects.requireNonNull(forever);
        this.linkOpener = linkOpener;
        this.images = Objects.requireNonNull(images);
    }

    @Override protected boolean isDismissing() { return closing; }
    public boolean shouldClose() { return clock.isGone(); }

    public void handleEscape() {
        if (enlarged >= 0 && !closing) {
            enlarged = -1;
            viewport.scrollToOffsetImmediately(0);
        } else {
            dismiss(false);
        }
    }

    /** 宿主被直接替换时也按暂缓处理；退场动画路径则等 shouldClose 后才回收图片。 */
    public void onHostClosed() {
        try {
            dismiss(false);
        } finally {
            releaseResources();
        }
    }

    public void releaseResources() {
        if (released) return;
        released = true;
        images.close();
    }

    private void dismiss(boolean never) {
        if (resolved || (never && !repeat)) return;
        resolved = true;
        closing = true;
        clock.requestDismiss();
        if (never) forever.run();
        else later.run();
    }

    @Override
    public void renderWidget(double mouseX, double mouseY, int dWheel) {
        layout.render(this, mouseX, mouseY, dWheel, (x, y, wheel) -> {
            super.renderWidget(x, y, 0);
            if (wheel != 0 && acceptsInput()) super.onDWheelReceived(x, y, wheel);
        });
    }

    @Override
    public void onMouseClickReceived(double mouseX, double mouseY, int button) {
        if (!acceptsInput()) return;
        layout.dispatch(this, mouseX, mouseY, button, (x, y, input) -> {
            if (!dispatchHit(getChildren(), x, y, input) && contains(this, x, y)) {
                onMouseClicked(x - getX(), y - getY(), input);
            }
        });
    }

    @Override
    public void onDWheelReceived(double mouseX, double mouseY, int wheel) {
        if (!acceptsInput()) return;
        layout.dispatch(this, mouseX, mouseY, wheel,
                (x, y, input) -> super.onDWheelReceived(x, y, input));
    }

    private boolean acceptsInput() { return !closing && phase == 1; }

    @Override
    public void onInit() {
        if (initialized) return;
        initialized = true;
        setBeforeRenderCallback(() -> {
            presentation = TransitionCurveCache.MOTION.at(phase = clock.tick());
        });
        dialog = NcmExpressive.shell(this, 820, () -> 660, () -> presentation, () -> dismiss(false));
        dialog.setBeforeRenderCallback(this::measure);
        NcmExpressive.medallion(dialog, PartyIcons.VIP, () -> 46, () -> 34, () -> 44,
                () -> NcmExpressivePalette.live().primaryContainer(), () -> NcmExpressivePalette.live().primary());
        NcmExpressive.label(dialog, () -> "让喜欢的音乐，继续陪伴", NcmExpressive::captionFont,
                () -> 80, () -> 34, () -> dialog.getWidth() - 164, () -> NcmExpressivePalette.live().muted());
        NcmExpressive.label(dialog, () -> enlarged < 0 ? "谢谢你和 Muonium 一起听歌" : channels[enlarged].title + " · 放大查看",
                NcmExpressive::dialogTitleFont, () -> INSET, () -> 77,
                () -> dialog.getWidth() - INSET * 2, () -> NcmExpressivePalette.live().text());
        NcmExpressive.hitSurface(dialog, () -> dialog.getWidth() - 66, () -> 13, () -> 42, () -> 42, 21,
                () -> NcmExpressivePalette.live().container(), () -> dismiss(false));
        NcmExpressive.icon(dialog, PartyIcons.CLOSE, NcmSheet.ICON_M, () -> dialog.getWidth() - 45,
                () -> 34, () -> NcmExpressivePalette.live().muted());

        viewport = new ScrollPanel() {
            @Override protected boolean acceptsTouchInput() { return acceptsInput(); }
            @Override protected boolean shouldClickChildren(double x, double y) {
                return acceptsInput() && testHovered(x, y);
            }
        };
        dialog.addChild(viewport);
        viewport.setSpacing(0).setScrollStrength(38).setIndicatorInsideViewport();
        viewport.setBeforeRenderCallback(() -> {
            viewport.setBounds(dialog.getWidth(), Math.max(1, dialog.getHeight() - HEADER - footerHeight() - readReserve()));
            viewport.setPosition(0, HEADER);
            content.setBounds(dialog.getWidth(), contentHeight);
            viewport.setAlpha((float) clock.layer(.10, .82));
        });
        content = new Panel() {
            @Override protected boolean shouldClickChildren(double x, double y) {
                return acceptsInput() && insideViewport(x, y);
            }
        };
        viewport.addChild(content);
        content.setClickable(false).setBeforeRenderCallback(() -> content.setBounds(dialog.getWidth(), contentHeight));
        NcmExpressive.surface(content, () -> INSET, () -> 0, () -> innerWidth(), () -> introHeight, 20,
                () -> NcmExpressivePalette.live().primaryContainer());
        NcmExpressive.label(content, () -> enlarged < 0 ? "已经累计启动游戏 " + launches + " 次了～" : "扫码前，请确认收款方为心事抱抱",
                NcmExpressive::labelFont, () -> INSET + 18, () -> 25,
                () -> innerWidth() - 36, () -> NcmExpressivePalette.live().containerInk());
        for (Channel channel : channels) buildChannel(channel);

        footer = new Panel();
        dialog.addChild(footer);
        footer.setOnClickCallback((x, y, button) -> true);
        footer.setBeforeRenderCallback(() -> {
            footer.setBounds(dialog.getWidth(), footerHeight());
            footer.setPosition(0, dialog.getHeight() - footerHeight());
            footer.setAlpha((float) clock.layer(.35, 1));
        });
        NcmExpressive.label(footer, () -> status, NcmExpressive::captionFont,
                () -> INSET, () -> 17, () -> innerWidth(), () -> NcmExpressivePalette.live().muted());
        neverButton = NcmExpressive.action(footer, "", () -> "永不展示", NcmExpressive.ActionStyle.NEUTRAL,
                () -> INSET, () -> 35, () -> repeat ? footerActionWidth() : 0,
                () -> repeat ? 48 : 0, () -> dismiss(true));
        neverButton.setHidden(!repeat);
        NcmExpressive.action(footer, "", () -> "下次一定～", NcmExpressive.ActionStyle.TONAL,
                () -> repeat ? dialog.getWidth() - INSET - footerActionWidth() : INSET,
                () -> 35, this::footerActionWidth, () -> 48, () -> dismiss(false));
        NcmExpressive.centered(footer, () -> "再启动 8 次后提醒 · 不影响播放与全部功能", NcmExpressive::captionFont,
                () -> dialog.getWidth() * .5, () -> 104, () -> innerWidth(),
                () -> NcmExpressivePalette.live().muted());
        NcmExpressive.action(dialog, PartyIcons.CHEVRON_LEFT, () -> enlarged >= 0 ? "返回渠道" : "上翻",
                NcmExpressive.ActionStyle.NEUTRAL, () -> INSET,
                () -> dialog.getHeight() - footerHeight() - 32, () -> needsReadControls() ? 94 : 0,
                () -> needsReadControls() ? 28 : 0, () -> {
                    if (enlarged >= 0) { enlarged = -1; viewport.scrollToOffsetImmediately(0); }
                    else viewport.scrollToOffsetImmediately(viewport.targetScrollOffset - viewport.getHeight() * .8);
                });
        NcmExpressive.action(dialog, PartyIcons.CHEVRON_RIGHT, () -> "下翻",
                NcmExpressive.ActionStyle.NEUTRAL, () -> dialog.getWidth() - INSET - 94,
                () -> dialog.getHeight() - footerHeight() - 32,
                () -> needsReadControls() && enlarged < 0 ? 94 : 0,
                () -> needsReadControls() && enlarged < 0 ? 28 : 0,
                () -> viewport.scrollToOffsetImmediately(viewport.targetScrollOffset + viewport.getHeight() * .8));
    }

    private void buildChannel(Channel channel) {
        int index = channel == channels[0] ? 0 : 1;
        channel.plate = new Panel() {
            @Override protected boolean shouldClickChildren(double x, double y) {
                return acceptsInput() && insideViewport(x, y) && testHovered(x, y);
            }
        };
        content.addChild(channel.plate);
        channel.plate.setOnClickCallback((x, y, button) -> true);
        channel.plate.setBeforeRenderCallback(() -> {
            boolean zoom = enlarged == index;
            channel.plate.setBounds(zoom ? innerWidth() : cardWidth, cardHeight);
            channel.plate.setPosition(INSET + (stacked || zoom ? 0 : index * (cardWidth + GAP)),
                    cardsTop + (stacked && !zoom ? index * (cardHeight + GAP) : 0));
        });
        NcmExpressive.surface(channel.plate, 0, 0, channel.plate::getWidth, channel.plate::getHeight, 24,
                () -> NcmExpressivePalette.live().container());
        NcmExpressive.label(channel.plate, () -> channel.title, NcmExpressive::labelFont,
                () -> 18, () -> 26, () -> channel.plate.getWidth() - 36,
                () -> NcmExpressivePalette.live().containerInk());
        channel.image = new Panel() {
            @Override public void onRender(double x, double y) {
                images.requestResource(channel.resource);
                channel.state = images.resourceState(channel.resource);
                if (channel.state == AnnouncementImageCache.State.READY && channel.arrived == 0) channel.arrived = System.nanoTime();
                channel.loading.setHidden(channel.state != AnnouncementImageCache.State.LOADING);
                channel.hint.setHidden(channel.state == AnnouncementImageCache.State.READY);
            }
        };
        channel.plate.addChild(channel.image);
        channel.image.setBeforeRenderCallback(() -> {
            channel.image.setBounds(imageSize, imageSize);
            channel.image.setPosition((channel.plate.getWidth() - imageSize) * .5, 48);
        });
        channel.image.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            enlarged = index;
            viewport.scrollToOffsetImmediately(0);
            return true;
        });
        NcmExpressive.surface(channel.image, 0, 0, channel.image::getWidth, channel.image::getHeight, 14,
                () -> NcmExpressivePalette.live().input());
        Panel imageInk = new Panel() {
            @Override public void onRender(double x, double y) {
                if (channel.state != AnnouncementImageCache.State.READY) return;
                float reveal = (float) NcmPromptMotion.reveal(channel.arrived, System.nanoTime(), 300);
                images.drawResource(channel.resource, SkijaGfx.canvas(), Rect.makeXYWH((float) getX(), (float) getY(),
                        (float) getWidth(), (float) getHeight()), 6, true, getAlpha() * reveal,
                        channel == channels[1] && enlarged < 0);
            }
        };
        channel.image.addChild(imageInk);
        imageInk.setClickable(false).setBeforeRenderCallback(() -> imageInk.setBounds(6, 6, imageSize - 12, imageSize - 12));
        channel.loading = NcmExpressive.loading(channel.image, () -> imageSize * .5,
                () -> imageSize * .5 - 12, () -> 32, () -> NcmExpressivePalette.live().primary());
        channel.hint = NcmExpressive.centered(channel.image,
                () -> channel.state == AnnouncementImageCache.State.FAILED ? "图片暂不可用" : "正在加载图片…",
                NcmExpressive::captionFont, () -> imageSize * .5, () -> imageSize * .5 + 27,
                () -> imageSize - 20, () -> NcmExpressivePalette.live().muted());
        NcmExpressive.centered(channel.plate, () -> channel.detail, NcmExpressive::captionFont,
                () -> channel.plate.getWidth() * .5, () -> imageSize + 66,
                () -> channel.plate.getWidth() - 32, () -> NcmExpressivePalette.live().muted());
        NcmExpressive.action(channel.plate, PartyIcons.LINK, () -> index == 0 ? "前往爱发电" : "打开微信赞助说明",
                index == 0 ? NcmExpressive.ActionStyle.PRIMARY : NcmExpressive.ActionStyle.TONAL,
                () -> 16, () -> cardHeight - 60, () -> channel.plate.getWidth() - 32, () -> 44,
                () -> openLink(channel.url));
    }

    private void measure() {
        double width = Math.max(1, Math.min(820, getWidth() - 40));
        dialog.setBounds(width, dialog.getHeight());
        stacked = innerWidth() < 580;
        cardWidth = stacked ? innerWidth() : (innerWidth() - GAP) * .5;
        imageSize = enlarged >= 0 ? Math.max(1, Math.min(400, innerWidth() - 40))
                : Math.max(1, Math.min(220, cardWidth - 36));
        cardHeight = imageSize + 146;
        String source = enlarged < 0 ? BODY : "爱发电二维码打开赞助页；微信请使用扫一扫识别赞赏码。\n赞助金额由你决定，继续免费使用同样欢迎。";
        CFontRenderer font = NcmExpressive.bodyFont();
        long revision = font == null ? -1 : font.metricsRevision();
        double textWidth = Math.max(1, innerWidth() - 36);
        if (font != wrappedFont || revision != wrappedRevision || textWidth != wrappedWidth
                || !source.equals(wrappedSource) || font == null || !font.areGlyphsLoaded(source)) {
            bodyLines = NcmExpressive.wrap(font, source, textWidth);
            wrappedFont = font;
            wrappedRevision = revision;
            wrappedWidth = textWidth;
            wrappedSource = source;
        }
        introHeight = 52 + bodyLines.length * lineHeight();
        if (!stacked && enlarged < 0) {
            double availableArt = getHeight() - 32 - HEADER - introHeight - GAP - 146 - 18 - 124;
            imageSize = Math.min(imageSize, Math.max(160, availableArt));
            cardHeight = imageSize + 146;
        }
        cardsTop = introHeight + GAP;
        contentHeight = cardsTop + cardHeight * (stacked && enlarged < 0 ? 2 : 1)
                + (stacked && enlarged < 0 ? GAP : 0) + 18;
        for (int i = 0; i < channels.length; i++) channels[i].plate.setHidden(enlarged >= 0 && enlarged != i);
        double preferred = HEADER + contentHeight + footerHeight() + readReserve();
        dialog.setBounds(width, Math.max(1, Math.min(preferred, getHeight() - 32)));
        dialog.center();
        dialog.setPosition(dialog.getRelativeX(), dialog.getRelativeY() + (1 - presentation) * 18);
        dialog.setAlpha((float) presentation);
        while (bodyLabels.size() < bodyLines.length) {
            int index = bodyLabels.size();
            bodyLabels.add(NcmExpressive.label(content, () -> index < bodyLines.length ? bodyLines[index] : "",
                    NcmExpressive::bodyFont, () -> INSET + 18, () -> 47 + (index + .5) * lineHeight(),
                    () -> innerWidth() - 36, () -> NcmExpressivePalette.live().containerInk()));
        }
        for (int i = 0; i < bodyLabels.size(); i++) bodyLabels.get(i).setHidden(i >= bodyLines.length);
    }

    private String wrappedSource;
    private double innerWidth() { return Math.max(1, dialog.getWidth() - INSET * 2); }
    private double footerHeight() { return 124; }
    private double readReserve() { return needsReadControls() ? 36 : 0; }
    private double footerActionWidth() { return repeat ? (innerWidth() - 12) * .5 : innerWidth(); }
    private boolean needsReadControls() {
        return enlarged >= 0 || (dialog != null && contentHeight > Math.max(1, getHeight() - 32 - HEADER - 124));
    }
    private boolean dispatchHit(List<AbstractWidget<?>> children, double x, double y, int button) {
        for (int i = children.size() - 1; i >= 0; i--) {
            AbstractWidget<?> child = children.get(i);
            if (child.isHidden() || !contains(child, x, y)) continue;
            if (child == viewport && !insideViewport(x, y)) continue;
            if (dispatchHit(child.getChildren(), x, y, button)) return true;
            if (child.isClickable() && child.onMouseClicked(x - child.getX(), y - child.getY(), button)) return true;
        }
        return false;
    }

    private static boolean contains(AbstractWidget<?> widget, double x, double y) {
        return widget.getWidth() > 0 && widget.getHeight() > 0 && x >= widget.getX() && y >= widget.getY()
                && x <= widget.getX() + widget.getWidth() && y <= widget.getY() + widget.getHeight();
    }

    private boolean insideViewport(double x, double y) {
        return x >= viewport.getX() && x <= viewport.getX() + viewport.getWidth()
                && y >= viewport.getY() && y <= viewport.getY() + viewport.getHeight();
    }
    private static double lineHeight() {
        CFontRenderer font = NcmExpressive.bodyFont();
        return Math.max(24, (font == null ? 18 : font.getFontHeight()) + 6);
    }
    private void openLink(String url) {
        if (!acceptsInput() || !AnnouncementChecker.isLinkAllowed(url)) return;
        if (linkOpener != null) { linkOpener.accept(url); return; }
        if (BrowserUtils.open(url)) status = "已在浏览器打开 · 金额由你决定，感谢支持";
        else { KeyboardUtils.setClipboardString(url); status = "未能打开浏览器，赞助地址已复制"; }
    }

    private static final class Channel {
        final String title, detail, resource, url;
        Panel plate, image;
        AbstractWidget<?> loading;
        LabelWidget hint;
        AnnouncementImageCache.State state = AnnouncementImageCache.State.EMPTY;
        long arrived;
        Channel(String title, String detail, String resource, String url) {
            this.title = title; this.detail = detail; this.resource = resource; this.url = url;
        }
    }
}
