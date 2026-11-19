package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.core.ncm.music.ImportedPlaylistStore;
import com.muoniumplayer.core.ncm.music.PlaylistImportService;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.TextFieldWidget;
import com.muoniumplayer.core.utils.KeyboardUtils;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.party.ui.PartyIcons;

/**
 * 全渠道歌单导入面板（侧栏「导入歌单」标题行的「＋」打开）。
 *
 * 输入框可自由编辑，Ctrl+V 原生粘贴、回车即导入，另有一颗「粘贴」按钮把剪贴板内容填进来
 * （长链接里常带空格/中文文案，先看见再确认比盲读剪贴板稳妥）。</p>
 *
 * <p>导入全程匿名：解析走 {@link PlaylistImportService}，结果只落在
 * {@link ImportedPlaylistStore} 的本地 JSON，不读写任何账号凭据。</p>
 */
public final class PlaylistImportOverlay extends NcmSheet {

    private static final double INSET = 24.0;
    private static final double TABS_H = 50.0;
    private static final double FIELD_H = 64.0;
    private static final double FOOTER_H = 94.0;
    private static final double BUTTON_GAP = 12.0;

    /** 状态行语气：0 中性说明、1 成功、2 失败、3 进行中。 */
    private static final int TONE_IDLE = 0;
    private static final int TONE_OK = 1;
    private static final int TONE_BAD = 2;
    private static final int TONE_BUSY = 3;

    private static final String IDLE_TEXT = "自动识别平台 · 匿名导入 · 只保存在本机";

    private final NcmExpressiveLayout layout = new NcmExpressiveLayout();
    private TextFieldWidget input;
    /** 后台解析进行中：挡住重复提交，不因关闭面板而取消。 */
    private volatile boolean running;
    private String statusText = IDLE_TEXT;
    private int statusTone = TONE_IDLE;
    private boolean folderMode;
    /** 复用建单页的系统选择器与扫描链路，不另起一套文件夹导入。 */
    private final PlaylistCreateSheet folderImporter = new PlaylistCreateSheet();

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
        beginPage();

        final Panel dialog = NcmExpressive.shell(this, 660, () -> 624,
                () -> presentation, this::dispose);
        NcmExpressive.header(dialog, PartyIcons.LINK, () -> "我的音乐资料库",
                () -> "导入歌单", this::dispose);
        buildSegments(dialog);

        final Panel content = body(dialog, () -> NcmExpressive.headerHeight(dialog) + 80,
                () -> footerHeight(dialog));
        final ScrollPanel list = feed(content);
        // 状态变化只切换同一棵控件树；链接、焦点和 SheetClock 都不重建。
        list.setBeforeRenderCallback(() -> {
            list.setBounds(Math.max(1, content.getWidth()), Math.max(1, content.getHeight()));
            list.setPosition(0, 0);
            linkBody.setHidden(folderMode || running);
            folderBody.setHidden(!folderMode || running);
            loadingBody.setHidden(!running);
        });
        linkBody = strip(list, () -> 284 + errorDetailsHeight());
        folderBody = strip(list, 274);
        loadingBody = strip(list, 284);
        buildLinkBody(linkBody);
        buildFolderBody(folderBody);
        buildLoadingBody(loadingBody);

        final Panel footer = new Panel();
        dialog.addChild(footer);
        footer.setBeforeRenderCallback(() -> {
            footer.setBounds(dialog.getWidth(), footerHeight(dialog));
            footer.setPosition(0, Math.max(0, dialog.getHeight() - footer.getHeight()));
            confirmAction.setHidden(running || folderMode);
            folderAction.setHidden(running || !folderMode);
        });
        NcmExpressive.action(footer, "", () -> running ? "关闭" : "取消",
                NcmExpressive.ActionStyle.TONAL, () -> INSET, () -> 20,
                () -> cancelWidth(footer),
                () -> NcmExpressive.BUTTON_HEIGHT, this::dispose);
        confirmAction = NcmExpressive.action(footer, PartyIcons.CHECK,
                () -> statusTone == TONE_BAD ? "重新导入" : "导入歌单",
                NcmExpressive.ActionStyle.PRIMARY,
                () -> footer.getWidth() - INSET - primaryWidth(footer),
                () -> primaryY(footer),
                () -> primaryWidth(footer), () -> NcmExpressive.BUTTON_HEIGHT, this::submit);
        folderAction = NcmExpressive.action(footer, PartyIcons.FOLDER_OPEN, () -> "选择文件夹",
                NcmExpressive.ActionStyle.PRIMARY,
                () -> footer.getWidth() - INSET - primaryWidth(footer),
                () -> primaryY(footer),
                () -> primaryWidth(footer), () -> NcmExpressive.BUTTON_HEIGHT, this::openFolder);
    }

    private Panel linkBody;
    private Panel folderBody;
    private Panel loadingBody;
    private RoundedRectWidget confirmAction;
    private RoundedRectWidget folderAction;

    private void buildSegments(Panel dialog) {
        final Panel tabs = new Panel();
        dialog.addChild(tabs);
        tabs.setBeforeRenderCallback(() -> {
            tabs.setBounds(Math.max(1, dialog.getWidth() - INSET * 2), TABS_H);
            tabs.setPosition(INSET, NcmExpressive.headerHeight(dialog) + 14);
            tabs.setClickable(!running);
        });
        NcmExpressive.surface(tabs, 0, 0, tabs::getWidth, () -> TABS_H, TABS_H * .5,
                () -> NcmExpressivePalette.live().container());
        final RoundedRectWidget linkTab = NcmExpressive.hitSurface(tabs, () -> 4, () -> 4,
                () -> Math.max(1, (tabs.getWidth() - 8) * .5), () -> TABS_H - 8, 21,
                () -> folderMode ? NcmExpressivePalette.live().container()
                        : NcmExpressivePalette.live().primaryContainer(), () -> selectMode(false));
        final RoundedRectWidget folderTab = NcmExpressive.hitSurface(tabs,
                () -> 4 + (tabs.getWidth() - 8) * .5, () -> 4,
                () -> Math.max(1, (tabs.getWidth() - 8) * .5), () -> TABS_H - 8, 21,
                () -> folderMode ? NcmExpressivePalette.live().primaryContainer()
                        : NcmExpressivePalette.live().container(), () -> selectMode(true));
        NcmExpressive.centered(linkTab, () -> "歌单链接", NcmExpressive.labelFont(),
                () -> linkTab.getWidth() * .5, () -> linkTab.getHeight() * .5,
                () -> linkTab.getWidth() - 20, () -> segmentInk(false));
        NcmExpressive.centered(folderTab, () -> "本地文件夹", NcmExpressive.labelFont(),
                () -> folderTab.getWidth() * .5, () -> folderTab.getHeight() * .5,
                () -> folderTab.getWidth() - 20, () -> segmentInk(true));
    }

    private int segmentInk(boolean folder) {
        NcmExpressivePalette palette = NcmExpressivePalette.live();
        return NcmExpressivePalette.readableColor(folderMode == folder ? palette.text() : palette.muted(),
                folderMode == folder ? palette.primaryContainer() : palette.container());
    }

    private void buildLinkBody(Panel host) {
        NcmExpressive.label(host, () -> "粘贴歌单分享链接", NcmExpressive.labelFont(),
                () -> INSET, () -> 25, () -> host.getWidth() - INSET * 2 - 100,
                () -> NcmExpressivePalette.live().text());
        NcmExpressive.action(host, PartyIcons.LINK, () -> "粘贴", NcmExpressive.ActionStyle.NEUTRAL,
                () -> host.getWidth() - INSET - 92, () -> 4, () -> 92, () -> 42,
                this::fillFromClipboard);
        final RoundedRectWidget field = NcmExpressive.surface(host, INSET, 58,
                () -> host.getWidth() - INSET * 2, () -> FIELD_H, 17,
                () -> NcmExpressivePalette.live().input());
        stroke(field, 17, () -> NcmExpressivePalette.live().outline(), () -> .7);
        stroke(field, 17, () -> statusTone == TONE_BAD ? NcmExpressivePalette.live().error()
                        : NcmExpressivePalette.live().primary(),
                () -> statusTone == TONE_BAD || input.isFocused() ? 1 : 0);
        NcmExpressive.icon(field, PartyIcons.LINK, ICON_L, () -> 27, () -> FIELD_H * .5,
                () -> statusTone == TONE_BAD ? NcmExpressivePalette.live().error()
                        : NcmExpressivePalette.live().muted());

        // 保留原输入控件：原生粘贴、选择、光标与 IME 路径不换成装饰性文字。
        final CFontRenderer font = NcmExpressive.bodyFont();
        input = new TextFieldWidget(font) {
            @Override
            public boolean onKeyTyped(char character, int keyCode) {
                if (keyCode == Keyboard.KEY_TAB) {
                    setFocused(true);
                    return true;
                }
                if (isFocused() && (keyCode == Keyboard.KEY_RETURN
                        || keyCode == Keyboard.KEY_NUMPADENTER)) {
                    submit();
                    return true;
                }
                return super.onKeyTyped(character, keyCode);
            }
        };
        field.addChild(input);
        input.getTextField().setMaxStringLength(4096);
        input.setEnabled(true).setFocused(true);
        input.setPlaceholder("输入分享链接 / 数字 ID");
        input.drawUnderline(false);
        input.setBeforeRenderCallback(() -> {
            input.setBounds(Math.max(1, field.getWidth() - 70), FIELD_H - 16);
            input.setPosition(52, 8);
            NcmExpressivePalette palette = NcmExpressivePalette.live();
            input.setColor(palette.text());
            input.setDisabledTextColor(palette.muted());
        });
        NcmExpressive.label(host, () -> "保存为 Mu 本地歌单引用，不会下载全部歌曲。",
                NcmExpressive.bodyFont(), () -> INSET, () -> 152,
                () -> host.getWidth() - INSET * 2, () -> NcmExpressivePalette.live().muted());
        NcmExpressive.label(host, () -> "网易云 / QQ / 酷狗 / B 站 / 汽水 / YouTube / Apple Music",
                NcmExpressive.captionFont(), () -> INSET, () -> 184,
                () -> host.getWidth() - INSET * 2, () -> NcmExpressivePalette.live().muted());
        NcmExpressive.status(host, this::statusGlyph, () -> statusText,
                () -> INSET, () -> 218, () -> host.getWidth() - INSET * 2, this::statusColor);
        final Panel details = new Panel();
        host.addChild(details);
        final long[] renderedRevision = {-1};
        details.setClickable(false).setBeforeRenderCallback(() -> {
            details.setBounds(host.getWidth(), errorDetailsHeight());
            details.setPosition(0, 280);
            if (renderedRevision[0] == errorLayoutRevision) return;
            renderedRevision[0] = errorLayoutRevision;
            details.getChildren().clear();
            CFontRenderer caption = NcmExpressive.captionFont();
            double centerY = errorLineHeight() * .5;
            for (String line : errorLines) {
                final String text = line;
                final double y = centerY;
                NcmExpressive.label(details, () -> text, caption, () -> INSET, () -> y,
                        () -> host.getWidth() - INSET * 2, () -> NcmExpressivePalette.live().error());
                centerY += errorLineHeight();
            }
        });
    }

    private void buildFolderBody(Panel host) {
        NcmExpressive.surface(host, INSET, 4, () -> host.getWidth() - INSET * 2,
                () -> 184, NcmExpressive.CARD_RADIUS,
                () -> NcmExpressivePalette.live().primaryContainer());
        NcmExpressive.medallion(host, PartyIcons.FOLDER_OPEN, () -> host.getWidth() * .5,
                () -> 51, () -> 54, () -> NcmExpressivePalette.live().surface(),
                () -> NcmExpressivePalette.live().primary());
        NcmExpressive.centered(host, () -> "把本地收藏带进播放器", NcmExpressive.dialogTitleFont(),
                () -> host.getWidth() * .5, () -> 111, () -> host.getWidth() - INSET * 2 - 24,
                () -> NcmExpressivePalette.readableColor(NcmExpressivePalette.live().text(),
                        NcmExpressivePalette.live().primaryContainer()));
        NcmExpressive.centered(host, () -> "只扫描这一层的音频，不递归子目录", NcmExpressive.captionFont(),
                () -> host.getWidth() * .5, () -> 151, () -> host.getWidth() - INSET * 2 - 24,
                () -> NcmExpressivePalette.readableColor(NcmExpressivePalette.live().muted(),
                        NcmExpressivePalette.live().primaryContainer()));
        NcmExpressive.label(host, () -> "使用系统文件夹选择器 · 保留原文件，不复制音频",
                NcmExpressive.bodyFont(), () -> INSET, () -> 221,
                () -> host.getWidth() - INSET * 2, () -> NcmExpressivePalette.live().muted());
        NcmExpressive.label(host, () -> "支持 mp3 / flac / wav / m4a / aac",
                NcmExpressive.captionFont(), () -> INSET, () -> 253,
                () -> host.getWidth() - INSET * 2, () -> NcmExpressivePalette.live().muted());
    }

    private void buildLoadingBody(Panel host) {
        NcmExpressive.loading(host, () -> host.getWidth() * .5, () -> 84,
                () -> 64, () -> NcmExpressivePalette.live().primary());
        NcmExpressive.centered(host, () -> "正在读取歌单", NcmExpressive.titleFont(),
                () -> host.getWidth() * .5, () -> 149, () -> host.getWidth() - INSET * 2,
                () -> NcmExpressivePalette.live().text());
        NcmExpressive.centered(host, () -> "识别平台并读取歌单信息，不下载音频", NcmExpressive.bodyFont(),
                () -> host.getWidth() * .5, () -> 190, () -> host.getWidth() - INSET * 2,
                () -> NcmExpressivePalette.live().muted());
        NcmExpressive.status(host, () -> PartyIcons.INFO, () -> "关闭后，后台导入仍会继续",
                () -> INSET, () -> 230, () -> host.getWidth() - INSET * 2,
                () -> NcmExpressivePalette.live().primary());
    }

    private String[] errorLines = new String[0];
    private String wrappedError = "";
    private double wrappedErrorWidth = -1;
    private CFontRenderer wrappedErrorFont;
    private long wrappedErrorMetrics = -1;
    private long errorLayoutRevision;

    private double errorDetailsHeight() {
        String reason = statusTone == TONE_BAD && statusText != null ? statusText : "";
        double width = Math.max(1, linkBody.getWidth() - INSET * 2);
        CFontRenderer caption = NcmExpressive.captionFont();
        long metrics = caption == null ? -1 : caption.metricsRevision();
        if (!reason.equals(wrappedError) || Math.abs(width - wrappedErrorWidth) >= .5
                || caption != wrappedErrorFont || metrics != wrappedErrorMetrics) {
            wrappedError = reason;
            wrappedErrorWidth = width;
            wrappedErrorFont = caption;
            wrappedErrorMetrics = metrics;
            java.util.List<String> lines = new java.util.ArrayList<>();
            if (!reason.isEmpty() && caption != null
                    && (reason.indexOf('\n') >= 0 || reason.indexOf('\r') >= 0
                    || caption.getStringWidthD(reason) > width - 44)) {
                for (String paragraph : reason.split("\\R", -1)) {
                    java.util.Collections.addAll(lines, NcmExpressive.wrap(caption, paragraph, width));
                }
            }
            errorLines = lines.toArray(new String[0]);
            errorLayoutRevision++;
        }
        return errorLines.length == 0 ? 0 : errorLines.length * errorLineHeight() + 8;
    }

    private static double errorLineHeight() {
        CFontRenderer caption = NcmExpressive.captionFont();
        return (caption == null ? 12.5 : caption.getFontHeight()) + 8;
    }

    private static double footerHeight(Panel dialog) {
        return dialog.getWidth() < 360 ? FOOTER_H + 62 : FOOTER_H;
    }

    private double cancelWidth(Panel footer) {
        if (footer.getWidth() < 360 && !running) return Math.max(1, footer.getWidth() - INSET * 2);
        return Math.min(140, Math.max(1, (footer.getWidth() - INSET * 2 - BUTTON_GAP) * .34));
    }

    private static double primaryY(Panel footer) {
        return footer.getWidth() < 360 ? 82 : 20;
    }

    private static double primaryWidth(Panel footer) {
        if (footer.getWidth() < 360) return Math.max(1, footer.getWidth() - INSET * 2);
        return Math.min(214, Math.max(1, (footer.getWidth() - INSET * 2 - BUTTON_GAP) * .48));
    }

    private void selectMode(boolean folder) {
        if (running || closing || folderMode == folder) return;
        folderMode = folder;
        input.setFocused(!folder);
    }

    private void openFolder() {
        if (running || closing) return;
        folderImporter.openLocalFolderImport(this::dispose);
    }

    @Override
    public boolean onKeyTyped(char character, int keyCode) {
        if (keyCode == Keyboard.KEY_TAB && !running && !closing) {
            input.setFocused(!folderMode);
            return true;
        }
        if (folderMode && !running && (keyCode == Keyboard.KEY_RETURN
                || keyCode == Keyboard.KEY_NUMPADENTER)) {
            openFolder();
            return true;
        }
        return super.onKeyTyped(character, keyCode);
    }

    public void handleEscape() {
        dispose();
    }

    /** 把剪贴板内容填进输入框而不是直接导入：用户能先看到要导入的到底是什么。 */
    private void fillFromClipboard() {
        if (running || folderMode || closing) return;
        String text = KeyboardUtils.getClipboardString();
        if (text == null || text.trim().isEmpty()) {
            statusText = "剪贴板是空的：先复制一份歌单分享链接或纯数字 ID";
            statusTone = TONE_BAD;
            return;
        }
        input.setText(text.trim());
        statusText = "已填入剪贴板内容，可继续编辑，回车或点「导入」";
        statusTone = TONE_OK;
    }

    private void submit() {
        if (running || folderMode || closing) return;
        final String pasted = input.getText() == null ? "" : input.getText().trim();
        if (pasted.isEmpty()) {
            statusText = "先输入或粘贴歌单链接 / ID";
            statusTone = TONE_BAD;
            return;
        }
        running = true;
        statusText = "正在识别平台并匿名拉取歌单信息…";
        statusTone = TONE_BUSY;
        MultiThreadingUtil.runAsync(() -> {
            final PlayList imported;
            try {
                imported = PlaylistImportService.importPlaylist(pasted);
            } catch (IllegalArgumentException badInput) {
                finish(badInput.getMessage(), false);
                return;
            } catch (Throwable failure) {
                String reason = failure.getMessage() == null || failure.getMessage().trim().isEmpty()
                        ? "网络异常" : failure.getMessage().trim();
                finish("导入失败：" + reason, false);
                return;
            }
            MultiThreadingUtil.runOnMainThread(() -> {
                try {
                    boolean isNew = ImportedPlaylistStore.add(imported);
                    NCMScreen.getInstance().markDirty();
                    DownloadDynamicIsland.showModelOperationResult("导入歌单",
                            (isNew ? "已导入「" : "已更新「") + imported.getName() + "」", true);
                    dispose();
                } catch (Throwable storeFailure) {
                    finish("保存失败：" + storeFailure.getMessage(), false);
                }
            });
        });
    }

    /** 失败/异常收尾：回到主线程清进度标志并把原因显示在状态行上，面板保持打开供修改重试。 */
    private void finish(String message, boolean ok) {
        MultiThreadingUtil.runOnMainThread(() -> {
            running = false;
            statusText = message == null || message.isEmpty() ? "导入失败" : message;
            statusTone = ok ? TONE_OK : TONE_BAD;
        });
    }

    private String statusGlyph() {
        switch (statusTone) {
            case TONE_OK:
                return PartyIcons.CHECK;
            case TONE_BAD:
                return PartyIcons.CLOSE;
            case TONE_BUSY:
                return PartyIcons.HOURGLASS;
            default:
                return PartyIcons.INFO;
        }
    }

    private int statusColor() {
        NcmExpressivePalette palette = NcmExpressivePalette.live();
        switch (statusTone) {
            case TONE_OK:
                return palette.success();
            case TONE_BAD:
                return palette.error();
            case TONE_BUSY:
                return palette.primary();
            default:
                return palette.primary();
        }
    }
}
