package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.BilibiliMusicService;
import com.muoniumplayer.core.ncm.music.BilibiliPlaylistService;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.TextFieldWidget;
import com.muoniumplayer.core.screens.ncm.panels.PlaylistPanel;
import com.muoniumplayer.core.utils.KeyboardUtils;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.party.ui.PartyIcons;

import java.util.List;
import java.util.function.IntSupplier;

/**
 * B 站稿件导入面板（侧栏「导入稿件」的二级界面）。
 *
 * <p>替代原先"点一下直接读剪贴板"的一键流程：打开后是一个可自由输入的文本框，Ctrl+V 原生粘贴、
 * 回车即解析，另外给一颗「粘贴」按钮把剪贴板内容填进输入框 —— B 站的分享文案经常是一整段带
 * 表情和话题的中文，先看见解析的到底是哪条链接，比盲读剪贴板稳妥。</p>
 *
 * <p>与汽水的导入面板刻意不同：这里<b>不落盘</b>。B 站没有「导入歌单」这个对象，解析结果就是
 * 一条 {@code bvid} / {@code season} 的 playlist id，随时能从输入框再算一次；存下来只是多养
 * 一份会过期的副本（合集还会继续更新）。所以本面板只做「解析 → 打开」。</p>
 *
 * <p>解析在后台线程做，回到主线程才换面板；面板自己的每帧回调里不碰网络。</p>
 */
public final class BilibiliImportOverlay extends NcmSheet {

    private static final double HEADER_H = 64.0;
    /** 输入行的高度；与汽水导入面板、账号管理页的输入框保持同一视觉尺寸。 */
    private static final double FIELD_H = 38.0;
    /** 页脚按钮排的高度与留白：分隔线下方只放两颗按钮。 */
    private static final double FOOTER_H = 46.0;
    private static final double BUTTON_H = 32.0;
    /** 「粘贴」这颗次按钮的固定宽度，剩下的都给「解析并打开」。 */
    private static final double PASTE_W = 104.0;
    private static final double BUTTON_GAP = 8.0;

    /** 状态行语气：0 中性说明、1 成功、2 失败、3 进行中。 */
    private static final int TONE_IDLE = 0;
    private static final int TONE_OK = 1;
    private static final int TONE_BAD = 2;
    private static final int TONE_BUSY = 3;

    private TextFieldWidget input;
    /** 后台解析进行中：挡住重复提交，也让按钮文案换成「正在解析…」。 */
    private volatile boolean running;
    private String statusText = "支持 BV号、av号、整条稿件链接与 b23.tv 短链；链接带 ?p= 时定位到对应分P";
    private int statusTone = TONE_IDLE;

    @Override
    public void onInit() {
        beginPage();

        final Panel dialog = sheet(452, () -> HEADER_H + FIELD_H + 8 + 26 + FOOTER_H + 16);
        header(dialog, PartyIcons.SEARCH, "导入 B 站稿件",
                () -> "解析稿件所属的合集或分P · 不写入本地曲库",
                (IntSupplier) MusicPlatform.BILIBILI::getBrandColor);

        final RoundedRectWidget field = new RoundedRectWidget();
        dialog.addChild(field);
        field.setClickable(false);
        field.setRadius(9);
        field.setBeforeRenderCallback(() -> {
            field.setBounds(Math.max(1, dialog.getWidth() - (PAD + CARD_INSET) * 2), FIELD_H);
            field.setPosition(PAD + CARD_INSET, HEADER_H + 4);
            field.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
        });

        // 回车即解析：TextFieldWidget 聚焦后会吞掉所有按键，默认没人处理回车，
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
        input.setPlaceholder("输入或粘贴稿件链接 / BV号 / av号");
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
                () -> running ? PartyIcons.HOURGLASS : PartyIcons.FOLDER_OPEN,
                () -> running ? "正在解析…" : "解析并打开", FontManager.pf12bold,
                () -> 0xFFFFFF, this::submit);
        confirm.setBeforeRenderCallback(() -> {
            confirm.setBounds(Math.max(1, dialog.getWidth() - (PAD + CARD_INSET) * 2 - PASTE_W - BUTTON_GAP),
                    BUTTON_H);
            confirm.setPosition(paste.getRelativeX() + PASTE_W + BUTTON_GAP,
                    dialog.getHeight() - BUTTON_H - 10);
            confirm.setColor(mix(MusicPlatform.BILIBILI.getBrandColor(), 0xFFFFFF,
                    confirm.isHovering() ? .16 : 0.0));
        });
    }

    public void handleEscape() {
        dispose();
    }

    /** 把剪贴板内容填进输入框而不是直接解析：用户能先看到要解析的到底是什么。 */
    private void fillFromClipboard() {
        String text = KeyboardUtils.getClipboardString();
        if (text == null || text.trim().isEmpty()) {
            statusText = "剪贴板是空的：先复制 B 站稿件链接或 BV号";
            statusTone = TONE_BAD;
            return;
        }
        input.setText(text.trim());
        statusText = "已填入剪贴板内容，可继续编辑，回车或点「解析并打开」";
        statusTone = TONE_OK;
    }

    private void submit() {
        if (running) return;
        final String pasted = input.getText() == null ? "" : input.getText().trim();
        if (pasted.isEmpty()) {
            statusText = "先输入或粘贴一条稿件链接、BV号或 av号";
            statusTone = TONE_BAD;
            return;
        }
        running = true;
        statusText = "正在解析这条稿件…";
        statusTone = TONE_BUSY;
        MultiThreadingUtil.runAsync(() -> {
            final List<PlayList> playlists;
            try {
                // 先本地归一再发 view：粘贴的是 b23.tv 短链时，canonicalVideoId 会发一次展开请求，
                // 所以这一步连同下面的解析都在后台线程做 —— 放在点击回调里就是当帧卡住。
                // 归一后的 id 里不再有短链，playlistsOfVideo 内部的第二次归一是纯本地操作。
                final String videoId = BilibiliMusicService.canonicalVideoId(pasted);
                if (videoId.isEmpty()) {
                    finish("没认出稿件号：需要 BV号、av号、稿件链接或 b23.tv 短链", false);
                    return;
                }
                playlists = BilibiliPlaylistService.playlistsOfVideo(videoId);
            } catch (Throwable failure) {
                String reason = failure.getMessage() == null || failure.getMessage().trim().isEmpty()
                        ? "网络异常" : failure.getMessage().trim();
                finish("解析失败：" + reason, false);
                return;
            }
            if (playlists.isEmpty()) {
                // 稿件已失效，或者它就是一条既没合集也只有单个分P的单机视频 —— 上游对这两种
                // 情况都不报错（playlistsOfVideo 返回空表），所以只能并成一句提示。
                finish("没解析出歌单：确认它属于合集，或者有 2 个以上分P", false);
                return;
            }
            final PlayList playlist = playlists.get(0);
            MultiThreadingUtil.runOnMainThread(() -> {
                running = false;
                statusText = "已打开「" + playlist.getName() + "」";
                statusTone = TONE_OK;
                NCMScreen.getInstance().setCurrentPanel(new PlaylistPanel(playlist));
                DownloadDynamicIsland.showModelOperationResult("导入稿件",
                        "已打开「" + playlist.getName() + "」", true);
                dispose();
            });
        });
    }

    /** 失败/异常收尾：回到主线程清进度标志并把原因显示在状态行上，面板保持打开供修改重试。 */
    private void finish(String message, boolean ok) {
        MultiThreadingUtil.runOnMainThread(() -> {
            running = false;
            statusText = message == null || message.isEmpty() ? "解析失败" : message;
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
