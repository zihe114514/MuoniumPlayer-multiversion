package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.QishuiMusicService;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.TextFieldWidget;
import com.muoniumplayer.core.screens.ncm.panels.PlaylistPanel;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.party.ui.PartyIcons;

/**
 * 新建 / 重命名一张<b>汽水云歌单</b>：写操作走本机签名后端（见
 * {@link QishuiMusicService#createCloudPlaylist}），落在上游账号里。
 *
 * <p>与 {@link PlaylistCreateSheet}（本机歌单）排版同款但生命周期独立：那个 final 类
 * 的提交语义长在本机存储上，硬塞一个云分支进去会让两边的失败文案互相污染。写请求
 * 是联网操作，提交后按钮钉成「正在…」防止连点——与音源浮层里 Cookie 验证同一套礼仪。</p>
 *
 * <p>改名模式不预填旧名字（与本地表单同一条理由：刚打开时焦点会被点开的点击丢掉，
 * 预填表现成一个「看着是空的」输入框），旧名字放在占位文案里。</p>
 */
public final class QishuiCloudPlaylistSheet extends NcmSheet {

    private static final double HEADER_H = 64.0;
    private static final double FIELD_H = 38.0;
    private static final double FOOTER_H = 46.0;
    private static final double BUTTON_H = 32.0;
    private static final double BUTTON_GAP = 8.0;
    private static final double CANCEL_W = 88.0;
    private static final int NAME_LIMIT = 40;

    private static final String IDLE_TEXT = "落在你的汽水账号里 · 手机客户端同步可见";
    private static final String IDLE_TEXT_RENAME = "改名写回汽水 · 曲目一首不动";

    private TextFieldWidget input;
    /** 非空即「重命名这一张」模式；为 null 时是新建。 */
    private final PlayList renaming;
    private volatile String statusText = IDLE_TEXT;
    private volatile boolean failed;
    /** 写请求在路上的标记：期间把确认按钮钉死，不让连点打出两张一样的歌单。 */
    private volatile boolean busy;

    public QishuiCloudPlaylistSheet() {
        this(null);
    }

    public QishuiCloudPlaylistSheet(PlayList renaming) {
        this.renaming = renaming;
        if (renaming != null) this.statusText = IDLE_TEXT_RENAME;
    }

    @Override
    public void onInit() {
        beginPage();

        final Panel dialog = sheet(452, () -> HEADER_H + FIELD_H + 8 + 26 + FOOTER_H + 16);
        header(dialog, renaming == null ? PartyIcons.ALBUM : PartyIcons.TEXT_FORMAT,
                renaming == null ? "新建汽水云歌单" : "重命名汽水歌单",
                () -> renaming == null ? IDLE_TEXT : IDLE_TEXT_RENAME,
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));

        final RoundedRectWidget field = new RoundedRectWidget();
        dialog.addChild(field);
        field.setClickable(false);
        field.setRadius(9);
        field.setBeforeRenderCallback(() -> {
            field.setBounds(Math.max(1, dialog.getWidth() - (PAD + CARD_INSET) * 2), FIELD_H);
            field.setPosition(PAD + CARD_INSET, HEADER_H + 4);
            field.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
        });

        final CFontRenderer font = FontManager.pf12;
        input = new TextFieldWidget(font) {
            @Override
            public boolean onKeyTyped(char character, int keyCode) {
                if (isFocused() && !busy && (keyCode == Keyboard.KEY_RETURN
                        || keyCode == Keyboard.KEY_NUMPADENTER)) {
                    submit();
                    return true;
                }
                return super.onKeyTyped(character, keyCode);
            }
        };
        dialog.addChild(input);
        input.getTextField().setMaxStringLength(NAME_LIMIT);
        input.setEnabled(true).setFocused(true);
        input.setPlaceholder(renaming == null
                ? "歌单名，比如「跑步」「睡前」「待听」"
                : "当前「" + (renaming.getName() == null ? "" : renaming.getName()) + "」· 输入新名字");
        input.drawUnderline(false);
        input.setBeforeRenderCallback(() -> {
            input.setBounds(Math.max(1, field.getWidth() - 20), Math.max(1, field.getHeight() - 10));
            input.setPosition(field.getRelativeX() + 10, field.getRelativeY() + 5);
            input.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            input.setDisabledTextColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
        });
        stroke(field, 9.0, () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                () -> input.isFocused() ? .7 : 0.0);

        tip(dialog, () -> failed ? PartyIcons.CLOSE : PartyIcons.INFO,
                () -> statusText,
                () -> failed ? BAD_COLOR : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                () -> HEADER_H + FIELD_H + 14);

        final RoundedButtonWidget cancel = action(dialog, () -> PartyIcons.CLOSE, () -> "取消",
                FontManager.pf12bold, () -> NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT),
                this::dismiss);
        cancel.setBeforeRenderCallback(() -> {
            cancel.setBounds(CANCEL_W, BUTTON_H);
            cancel.setPosition(PAD + CARD_INSET, dialog.getHeight() - BUTTON_H - 10);
            cancel.setColor(cancel.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });

        final RoundedButtonWidget confirm = action(dialog, () -> PartyIcons.CHECK,
                () -> busy ? (renaming == null ? "正在创建…" : "正在保存…")
                        : (renaming == null ? "新建" : "保存"),
                FontManager.pf12bold, () -> 0xFFFFFF, this::submit);
        confirm.setBeforeRenderCallback(() -> {
            confirm.setBounds(Math.max(1, dialog.getWidth() - (PAD + CARD_INSET) - CANCEL_W - BUTTON_GAP), BUTTON_H);
            confirm.setPosition(cancel.getRelativeX() + CANCEL_W + BUTTON_GAP,
                    dialog.getHeight() - BUTTON_H - 10);
            int accent = NCMScreen.getColor(NCMScreen.ColorType.ACCENT);
            confirm.setColor(mix(accent, 0xFFFFFF, confirm.isHovering() ? .16 : 0.0));
        });
    }

    public void handleEscape() {
        if (!busy) dismiss();
    }

    private void dismiss() {
        dispose();
    }

    private void submit() {
        if (busy) return;
        final String name = input.getText() == null ? "" : input.getText().trim();
        if (name.isEmpty()) {
            statusText = renaming == null ? "先给歌单起个名字" : "名字不能是空的";
            failed = true;
            return;
        }
        busy = true;
        failed = false;
        statusText = renaming == null ? "正在创建…" : "正在保存…";
        MultiThreadingUtil.runAsync(() -> {
            if (renaming != null) {
                renameInBackground(name);
            } else {
                createInBackground(name);
            }
        });
    }

    private void createInBackground(String name) {
        final PlayList created = QishuiMusicService.createCloudPlaylist(name);
        MultiThreadingUtil.runOnMainThread(() -> {
            busy = false;
            if (created == null) {
                failed = true;
                statusText = "建单失败：签名后端没通或上游拒绝（看一眼汽水源卡片的状态）";
                return;
            }
            NCMScreen.getInstance().markDirty();
            DownloadDynamicIsland.showModelOperationResult("汽水云歌单", "已新建「" + name + "」", true);
            dispose();
            // 建完直接进这张单：空列表配一句提示，与本地建单同一份顺流。
            NCMScreen.getInstance().setCurrentPanel(new PlaylistPanel(created));
        });
    }

    private void renameInBackground(String name) {
        final boolean ok = QishuiMusicService.updateCloudPlaylist(
                renaming.getPlatformPlaylistId(), name, "");
        if (!ok) {
            MultiThreadingUtil.runOnMainThread(() -> {
                busy = false;
                failed = true;
                statusText = "改名失败：签名后端没通或上游拒绝";
            });
            return;
        }
        // 上游改完名，侧栏快照还是旧的：强刷快照，在它的回调里拿新卡片换页——
        // forceRefresh 的回调本来就在后台线程，正好在这里做查找，别在调用点后面读竞态。
        QishuiMusicService.forceRefreshUserPlaylistsAsync(result -> {
            PlayList refreshed = null;
            for (PlayList candidate : QishuiMusicService.getUserPlaylistsSnapshot()) {
                if (candidate != null && candidate.getPlatformPlaylistId() != null
                        && candidate.getPlatformPlaylistId()
                        .equals(renaming.getPlatformPlaylistId())) {
                    refreshed = candidate;
                    break;
                }
            }
            final PlayList panelPlaylist = refreshed;
            MultiThreadingUtil.runOnMainThread(() -> {
                busy = false;
                NCMScreen.getInstance().markDirty();
                DownloadDynamicIsland.showModelOperationResult("汽水云歌单",
                        "已改名为「" + name + "」", true);
                dispose();
                // 快照刷到了就用新卡片替换详情页；没刷到（刷新在途/失败）至少整页重铺，
                // 别把旧名字留在标题上。
                if (panelPlaylist != null) {
                    NCMScreen.getInstance().replaceCurrentPanel(new PlaylistPanel(panelPlaylist));
                } else {
                    NCMScreen.getInstance().reloadCurrentPanel();
                }
            });
        });
    }
}
