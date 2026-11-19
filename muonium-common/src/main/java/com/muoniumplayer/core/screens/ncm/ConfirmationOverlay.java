package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.party.ui.PartyIcons;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 模态确认：确认之前不执行操作，取消与退场都不触发待执行回调。 */
public final class ConfirmationOverlay extends NCMPanel {
    private static final int MAX_MESSAGE_LINES = 8;
    private static final double MESSAGE_INSET = 40.0;
    private static final double MESSAGE_TOP = 156.0;

    private final String title;
    private final String message;
    private final String confirmText;
    private final Runnable onConfirm;
    private final boolean consent;
    private boolean resolved;
    private final NcmExpressiveLayout layout = new NcmExpressiveLayout();
    private final SheetClock clock = new SheetClock();
    private double presentation;
    private String[] messageLines = new String[0];
    private double wrappedForWidth = -1.0;
    private double messageBlockHeight = 48.0;
    private boolean wrappedWithLoadedGlyphs;
    private long wrappedFontRevision = -1;

    public ConfirmationOverlay(String title, String message, String confirmText, Runnable onConfirm) {
        this.title = title == null ? "请确认操作" : title;
        this.message = message == null ? "此操作无法直接撤销。" : message;
        this.confirmText = confirmText == null ? "确认" : confirmText;
        this.onConfirm = onConfirm;
        this.consent = "我已了解，开启".equals(this.confirmText);
    }

    @Override
    protected boolean isDismissing() {
        return clock.isDismissing();
    }

    public boolean shouldClose() {
        return clock.isGone();
    }

    public void cancel() {
        resolve(false);
    }

    @Override
    public void renderWidget(double mouseX, double mouseY, int dWheel) {
        layout.render(this, mouseX, mouseY, dWheel, (x, y, wheel) -> {
            super.renderWidget(x, y, 0);
            if (wheel != 0 && !isDismissing()) super.onDWheelReceived(x, y, wheel);
        });
    }

    @Override
    public void onMouseClickReceived(double mouseX, double mouseY, int button) {
        layout.dispatch(this, mouseX, mouseY, button, (x, y, input) -> super.onMouseClickReceived(x, y, input));
    }

    @Override
    public void onDWheelReceived(double mouseX, double mouseY, int dWheel) {
        layout.dispatch(this, mouseX, mouseY, dWheel, (x, y, input) -> super.onDWheelReceived(x, y, input));
    }

    @Override
    public void onInit() {
        setBeforeRenderCallback(() -> {
            presentation = TransitionCurveCache.MOTION.at(clock.tick());
            wrapMessage(Math.max(1, NcmExpressive.dialogWidth(this, 560) - MESSAGE_INSET * 2));
        });
        Panel dialog = NcmExpressive.shell(this, 560,
                () -> MESSAGE_TOP + messageBlockHeight + actionReserve(NcmExpressive.dialogWidth(this, 560)) + 20,
                () -> presentation, this::cancel);
        NcmExpressive.header(dialog, consent ? PartyIcons.SYNC : PartyIcons.WARNING,
                () -> consent ? "账号与隐私" : "操作确认", () -> title, null);

        // 正文可滚动，矮视口下也能读完授权说明；按钮区始终独立留在底部。
        ScrollPanel body = new ScrollPanel();
        dialog.addChild(body);
        body.setAlignment(ScrollPanel.Alignment.VERTICAL).setScrollStrength(28).setIndicatorInsideViewport();
        body.setBeforeRenderCallback(() -> {
            double top = NcmExpressive.headerHeight(dialog) + 14;
            double available = Math.max(1, dialog.getHeight() - top - actionReserve(dialog.getWidth()));
            body.setBounds(Math.max(1, dialog.getWidth() - 48),
                    Math.max(1, available - (messageBlockHeight > available ? 40 : 0)));
            body.setPosition(24, top);
        });
        Panel content = new Panel();
        body.addChild(content);
        content.setBeforeRenderCallback(() -> content.setBounds(Math.max(1, body.getWidth()), messageBlockHeight));
        NcmExpressive.surface(content, 0, 0,
                content::getWidth, () -> messageBlockHeight, 20, () -> NcmExpressivePalette.live().container());
        for (int index = 0; index < MAX_MESSAGE_LINES; index++) {
            final int lineIndex = index;
            NcmExpressive.label(content,
                    () -> lineIndex < messageLines.length ? messageLines[lineIndex] : "", FontManager.pf32,
                    () -> 16, () -> 20 + lineIndex * lineHeight(), () -> content.getWidth() - 32,
                    () -> NcmExpressivePalette.live().containerInk());
        }
        Panel readControls = new Panel() {
            @Override
            protected boolean shouldClickChildren(double mouseX, double mouseY) {
                return getWidgetAlpha() > 0;
            }
        };
        dialog.addChild(readControls);
        readControls.setBeforeRenderCallback(() -> {
            boolean overflow = messageBlockHeight > body.getHeight() + 1;
            readControls.setPosition(Math.max(0, dialog.getWidth() - 108),
                    Math.max(0, dialog.getHeight() - actionReserve(dialog.getWidth()) - 36));
            readControls.setBounds(84, 36);
            readControls.setAlpha(overflow ? 1 : 0);
            readControls.setClickable(overflow);
        });
        NcmExpressive.hitSurface(readControls, () -> 0, () -> 0, () -> 36, () -> 36, 18,
                () -> NcmExpressivePalette.live().container(),
                () -> body.scrollToOffsetImmediately(body.targetScrollOffset - Math.max(24, body.getHeight() * .75)));
        NcmExpressive.icon(readControls, PartyIcons.CHEVRON_LEFT, NcmSheet.ICON_M,
                () -> 18, () -> 18, () -> NcmExpressivePalette.live().primary());
        NcmExpressive.hitSurface(readControls, () -> 44, () -> 0, () -> 36, () -> 36, 18,
                () -> NcmExpressivePalette.live().container(),
                () -> body.scrollToOffsetImmediately(body.targetScrollOffset + Math.max(24, body.getHeight() * .75)));
        NcmExpressive.icon(readControls, PartyIcons.CHEVRON_RIGHT, NcmSheet.ICON_M,
                () -> 62, () -> 18, () -> NcmExpressivePalette.live().primary());
        NcmExpressive.rule(dialog, () -> 24, () -> dialog.getHeight() - actionReserve(dialog.getWidth()) + 8,
                () -> dialog.getWidth() - 48, () -> NcmExpressivePalette.live().outline());
        NcmExpressive.action(dialog, null,
                () -> "取消收藏歌曲？".equals(title) ? "保留收藏" : "取消", NcmExpressive.ActionStyle.NEUTRAL,
                () -> 24, () -> dialog.getWidth() < 420 ? dialog.getHeight() - 132 : dialog.getHeight() - 74,
                () -> dialog.getWidth() < 420 ? dialog.getWidth() - 48 : (dialog.getWidth() - 60) * .40,
                () -> NcmExpressive.BUTTON_HEIGHT, this::cancel);
        NcmExpressive.action(dialog, consent ? PartyIcons.SYNC : null, () -> confirmText,
                consent ? NcmExpressive.ActionStyle.PRIMARY : NcmExpressive.ActionStyle.DANGER,
                () -> dialog.getWidth() < 420 ? 24 : 36 + (dialog.getWidth() - 60) * .40,
                () -> dialog.getHeight() - 74,
                () -> dialog.getWidth() < 420 ? dialog.getWidth() - 48 : (dialog.getWidth() - 60) * .60,
                () -> NcmExpressive.BUTTON_HEIGHT, () -> resolve(true));
    }

    private static double actionReserve(double width) {
        return width < 420 ? 156 : 98;
    }

    private static double lineHeight() {
        return Math.max(24, (FontManager.pf32 == null ? 16 : FontManager.pf32.getFontHeight()) + 6);
    }

    private void wrapMessage(double contentWidth) {
        boolean glyphsReady = FontManager.pf32 != null && FontManager.pf32.areGlyphsLoaded(message);
        long revision = FontManager.pf32 == null ? -1 : FontManager.pf32.metricsRevision();
        if (!wrappedWithLoadedGlyphs || wrappedFontRevision != revision || Math.abs(contentWidth - wrappedForWidth) >= .5) {
            wrappedForWidth = contentWidth;
            wrappedWithLoadedGlyphs = glyphsReady;
            wrappedFontRevision = revision;
            List<String> lines = new ArrayList<>();
            Collections.addAll(lines, NcmExpressive.wrap(FontManager.pf32, message, contentWidth));
            if (lines.isEmpty()) lines.add("");
            if (lines.size() > MAX_MESSAGE_LINES) {
                lines = new ArrayList<>(lines.subList(0, MAX_MESSAGE_LINES));
                String last = lines.get(MAX_MESSAGE_LINES - 1);
                lines.set(MAX_MESSAGE_LINES - 1, (FontManager.pf32 == null ? last
                        : FontManager.pf32.trim(last, Math.max(1, contentWidth - 16))) + "…");
            }
            messageLines = lines.toArray(new String[0]);
        }
        messageBlockHeight = Math.max(48, messageLines.length * lineHeight() + 16);
    }

    private void resolve(boolean confirmed) {
        if (resolved) return;
        resolved = true;
        clock.requestDismiss();
        if (confirmed && onConfirm != null) onConfirm.run();
    }
}
