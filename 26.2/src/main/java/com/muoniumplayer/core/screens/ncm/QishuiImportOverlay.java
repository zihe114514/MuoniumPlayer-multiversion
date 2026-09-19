package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.QishuiLocalPlaylistStore;
import com.muoniumplayer.core.ncm.music.QishuiMusicService;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.TextFieldWidget;
import com.muoniumplayer.core.utils.KeyboardUtils;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.party.ui.PartyIcons;

import java.util.function.IntSupplier;

/**
 * 汽水歌单导入面板（侧栏「导入歌单」的二级界面）。
 *
 * <p>替代原先"点一下直接读剪贴板"的一键流程：打开后是一个可自由输入的文本框，
 * Ctrl+V 原生粘贴、回车即导入，另外给一颗「粘贴」按钮把剪贴板内容填进输入框
 * （长链接里常带空格/中文文案，先看见再确认比盲读剪贴板稳妥）。</p>
 *
 * <p>导入全程匿名：解析与拉取都走 {@link QishuiMusicService#importPlaylist}（空 Cookie），
 * 结果只落在 {@link QishuiLocalPlaylistStore} 的本地 JSON，不碰任何账号凭据文件。</p>
 */
public final class QishuiImportOverlay extends NcmSheet {

    private static final double HEADER_H = 64.0;
    /** 输入行的高度；与账号管理页的 Cookie 输入保持同一视觉尺寸。 */
    private static final double FIELD_H = 38.0;
    /** 页脚按钮排的高度与留白：分隔线下方只放两颗按钮。 */
    private static final double FOOTER_H = 46.0;
    private static final double BUTTON_H = 32.0;
    /** 「粘贴」这颗次按钮的固定宽度，剩下的都给「导入」。 */
    private static final double PASTE_W = 104.0;
    private static final double BUTTON_GAP = 8.0;

    /** 状态行语气：0 中性说明、1 成功、2 失败、3 进行中。 */
    private static final int TONE_IDLE = 0;
    private static final int TONE_OK = 1;
    private static final int TONE_BAD = 2;
    private static final int TONE_BUSY = 3;

    private TextFieldWidget input;
    /** 后台解析进行中：挡住重复提交，也让按钮文案换成「正在导入…」。 */
    private volatile boolean running;
    private String statusText = "支持分享链接、?playlist_id= 参数、抖音短链或直接输入数字 ID";
    private int statusTone = TONE_IDLE;

    @Override
    public void onInit() {
        beginPage();

        final Panel dialog = sheet(452, () -> HEADER_H + FIELD_H + 8 + 26 + FOOTER_H + 16);
        header(dialog, PartyIcons.LINK, "导入汽水歌单",
                () -> "匿名导入 · 无需登录 · 只保存在本机",
                (IntSupplier) MusicPlatform.QISHUI::getBrandColor);

        final RoundedRectWidget field = new RoundedRectWidget();
        dialog.addChild(field);
        field.setClickable(false);
        field.setRadius(9);
        field.setBeforeRenderCallback(() -> {
            field.setBounds(Math.max(1, dialog.getWidth() - (PAD + CARD_INSET) * 2), FIELD_H);
            field.setPosition(PAD + CARD_INSET, HEADER_H + 4);
            field.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
        });

        // 回车即导入：TextFieldWidget 聚焦后会吞掉所有按键，默认没人处理回车，
        // 所以在这里子类化补上——输入框里改完链接不用再挪鼠标去点按钮。
        final CFontRenderer font = FontManager.pf12;
        input = new TextFieldWidget(font) {
            @Override
            public boolean onKeyTyped(char character, int keyCode) {
                if (isFocused() && (keyCode == Keyboard.KEY_RETURN
                        || keyCode == Keyboard.KEY_NUMPADENTER)) {
                    submit();
                    return true;
                }
                return super.onKeyTyped(character, keyCode);
            }
        };
        dialog.addChild(input);
        input.getTextField().setMaxStringLength(4096);
        input.setEnabled(true).setFocused(true);
        input.setPlaceholder("输入或粘贴歌单分享链接 / 数字 ID");
        input.drawUnderline(false);
        input.setBeforeRenderCallback(() -> {
            input.setBounds(Math.max(1, field.getWidth() - 20), Math.max(1, field.getHeight() - 10));
            input.setPosition(field.getRelativeX() + 10, field.getRelativeY() + 5);
            input.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            input.setDisabledTextColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
        });
        stroke(field, 9.0, () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                () -> input.isFocused() ? .7 : 0.0);

        tip(dialog, this::statusGlyph, () -> statusText, this::statusColor, () -> HEADER_H + FIELD_H + 14);

        final RoundedButtonWidget paste = action(dialog, () -> PartyIcons.LINK, () -> "粘贴",
                FontManager.pf12bold, () -> NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT),
                this::fillFromClipboard);
        paste.setBeforeRenderCallback(() -> {
            paste.setBounds(PASTE_W, BUTTON_H);
            paste.setPosition(PAD + CARD_INSET, dialog.getHeight() - BUTTON_H - 10);
            paste.setColor(paste.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });

        final RoundedButtonWidget confirm = action(dialog,
                () -> running ? PartyIcons.HOURGLASS : PartyIcons.CHECK,
                () -> running ? "正在导入…" : "导入", FontManager.pf12bold, () -> 0xFFFFFF, this::submit);
        confirm.setBeforeRenderCallback(() -> {
            confirm.setBounds(Math.max(1, dialog.getWidth() - (PAD + CARD_INSET) * 2 - PASTE_W - BUTTON_GAP),
                    BUTTON_H);
            confirm.setPosition(paste.getRelativeX() + PASTE_W + BUTTON_GAP,
                    dialog.getHeight() - BUTTON_H - 10);
            confirm.setColor(mix(MusicPlatform.QISHUI.getBrandColor(), 0xFFFFFF,
                    confirm.isHovering() ? .16 : 0.0));
        });
    }

    public void handleEscape() {
        dispose();
    }

    /** 把剪贴板内容填进输入框而不是直接导入：用户能先看到要导出的到底是什么。 */
    private void fillFromClipboard() {
        String text = KeyboardUtils.getClipboardString();
        if (text == null || text.trim().isEmpty()) {
            statusText = "剪贴板是空的：先复制汽水歌单分享链接或纯数字 ID";
            statusTone = TONE_BAD;
            return;
        }
        input.setText(text.trim());
        statusText = "已填入剪贴板内容，可继续编辑，回车或点「导入」";
        statusTone = TONE_OK;
    }

    private void submit() {
        if (running) return;
        final String pasted = input.getText() == null ? "" : input.getText().trim();
        if (pasted.isEmpty()) {
            statusText = "先输入或粘贴歌单链接 / ID";
            statusTone = TONE_BAD;
            return;
        }
        running = true;
        statusText = "正在匿名拉取歌单信息…";
        statusTone = TONE_BUSY;
        MultiThreadingUtil.runAsync(() -> {
            final PlayList imported;
            try {
                imported = QishuiMusicService.importPlaylist(pasted);
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
                    boolean isNew = QishuiLocalPlaylistStore.add(imported);
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
        switch (statusTone) {
            case TONE_OK:
                return OK_COLOR;
            case TONE_BAD:
                return BAD_COLOR;
            case TONE_BUSY:
                return NCMScreen.getColor(NCMScreen.ColorType.ACCENT);
            default:
                return NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT);
        }
    }
}
