package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.LocalPlaylistStore;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.TextFieldWidget;
import com.muoniumplayer.core.screens.ncm.panels.PlaylistPanel;
import com.muoniumplayer.party.ui.PartyIcons;

/**
 * 新建一张本地歌单：只要一个名字。
 *
 * <p>它同时是侧栏「＋」的落点，所以底部留了一颗「从链接导入」 —— 建单和导入是同一件事的两个入口
 * （「我要往这张单子里放歌」），把它们分成两处会让人找不到导入去哪了。点它先把自己回收，
 * 免得两层浮层叠在一起。</p>
 *
 * <p>换上一个 {@code renaming} 之后这一页变成「重命名」：字段、按钮排版都一样，只有落盘的动作不同
 * （{@link LocalPlaylistStore#rename} 换对象，因为 {@code PlayList.name} 是 final）。为这件事再开一份
 * 近乎抄一遍的表单元页不值当。</p>
 *
 * <p>建出来的是一张<b>空</b>单。空单在侧栏和详情页都要能正常活着：它没有封面、没有曲目，
 * 但表头、播放按钮、右键移除这些都得照常画出来而不是崩成空白页。</p>
 */
public final class PlaylistCreateSheet extends NcmSheet {

    private static final double HEADER_H = 64.0;
    private static final double FIELD_H = 38.0;
    private static final double FOOTER_H = 46.0;
    private static final double BUTTON_H = 32.0;
    private static final double BUTTON_GAP = 8.0;
    private static final double CANCEL_W = 88.0;
    private static final double IMPORT_W = 124.0;
    /** 歌单名上限。40 个汉字足够表达任何一张单子，再长侧栏那一行也放不下。 */
    private static final int NAME_LIMIT = 40;

    private static final String IDLE_TEXT = "只存在本机 · 可以收进任意音源的歌曲 · 不上传到任何平台";
    private static final String IDLE_TEXT_RENAME = "只改本机这一张的名字 · 里面的曲目与所属音源都不动";

    private TextFieldWidget input;
    /** 非空即「重命名这一张」模式；为 null 时是新建。 */
    private final PlayList renaming;
    private String statusText = IDLE_TEXT;
    private boolean failed;

    public PlaylistCreateSheet() {
        this(null);
    }

    public PlaylistCreateSheet(PlayList renaming) {
        this.renaming = renaming;
        if (renaming != null) this.statusText = IDLE_TEXT_RENAME;
    }

    @Override
    public void onInit() {
        beginPage();

        final Panel dialog = sheet(452, () -> HEADER_H + FIELD_H + 8 + 26 + FOOTER_H + 16);
        header(dialog, renaming == null ? PartyIcons.ALBUM : PartyIcons.TEXT_FORMAT,
                renaming == null ? "新建本地歌单" : "重命名本地歌单",
                () -> renaming == null ? "起个名字就建好 · 之后从任意音源把歌加进来"
                        : "换一个新名字 · 曲目一首都不动",
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

        // 回车即建单：TextFieldWidget 聚焦后吞掉所有按键，默认没人处理回车。
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
        input.getTextField().setMaxStringLength(NAME_LIMIT);
        input.setEnabled(true).setFocused(true);
        // 重命名模式不预填旧名字：TextField 只在「聚焦或悬停」时才画已有文字，而这一层刚打开时
        // 焦点会被那次点开的点击丢掉（见 TextField#updateStyles 里那句 setFocused(false)），
        // 预填就会表现成一个「看着是空的、点进去字才冒出来」的输入框。旧名字改放在占位文案里说。
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

        // 导入是另一条路，但它和「新建」回答的是同一个问题，所以放在同一排而不是藏进二级菜单。
        // 重命名模式下这张页跟「往里放歌」无关，那颗按钮就没有存在的理由 —— 连同它占的那一段排位
        // 一起让给「保存」，不然右边会空出一格。
        final RoundedButtonWidget importFromLink = action(dialog, () -> PartyIcons.LINK,
                () -> "从链接导入", FontManager.pf12bold,
                () -> NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT), this::openImport);
        importFromLink.setBeforeRenderCallback(() -> {
            importFromLink.setBounds(IMPORT_W, BUTTON_H);
            importFromLink.setPosition(cancel.getRelativeX() + CANCEL_W + BUTTON_GAP,
                    dialog.getHeight() - BUTTON_H - 10);
            importFromLink.setColor(importFromLink.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            importFromLink.setHidden(renaming != null);
        });

        final RoundedButtonWidget confirm = action(dialog, () -> PartyIcons.CHECK,
                () -> renaming == null ? "新建" : "保存", FontManager.pf12bold,
                () -> 0xFFFFFF, this::submit);
        confirm.setBeforeRenderCallback(() -> {
            double left = renaming == null
                    ? importFromLink.getRelativeX() + IMPORT_W + BUTTON_GAP
                    : cancel.getRelativeX() + CANCEL_W + BUTTON_GAP;
            confirm.setBounds(Math.max(1, dialog.getWidth() - (PAD + CARD_INSET) - left), BUTTON_H);
            confirm.setPosition(left, dialog.getHeight() - BUTTON_H - 10);
            int accent = NCMScreen.getColor(NCMScreen.ColorType.ACCENT);
            confirm.setColor(mix(accent, 0xFFFFFF, confirm.isHovering() ? .16 : 0.0));
        });
    }

    public void handleEscape() {
        dismiss();
    }

    private void dismiss() {
        dispose();
    }

    private void openImport() {
        // 先把自己收掉：两层浮层叠着的话，导入面板关掉后会露出这张空白的建单页。
        dispose();
        NCMScreen.getInstance().openPlaylistImport();
    }

    private void submit() {
        final String name = input.getText() == null ? "" : input.getText().trim();
        if (name.isEmpty()) {
            statusText = renaming == null ? "先给歌单起个名字" : "名字不能是空的";
            failed = true;
            return;
        }
        if (renaming != null) {
            renameTo(name);
            return;
        }
        final PlayList created;
        try {
            created = LocalPlaylistStore.create(name);
        } catch (Throwable failure) {
            statusText = "建单失败：" + (failure.getMessage() == null || failure.getMessage().trim().isEmpty()
                    ? "本机写入出错" : failure.getMessage().trim());
            failed = true;
            return;
        }
        NCMScreen.getInstance().markDirty();
        DownloadDynamicIsland.showModelOperationResult("本地歌单", "已新建「" + name + "」", true);
        dispose();
        // 建完直接进这张单：空列表配一句提示，比让用户回到侧栏再找它一次要顺。
        NCMScreen.getInstance().setCurrentPanel(new PlaylistPanel(created));
    }

    /**
     * 重命名分支。换的是对象不是字段 —— {@code PlayList.name} 是 final（它是 Lombok 那个必填
     * 构造器的参数），所以 {@link LocalPlaylistStore#rename} 造一张新单、把同一份曲目列表接过来。
     */
    private void renameTo(String name) {
        if (name.equals(renaming.getName())) {
            // 名字没动就别改盘、也别换页：这一下点击就当取消。
            dispose();
            return;
        }
        final PlayList renamed;
        try {
            renamed = LocalPlaylistStore.rename(renaming, name);
        } catch (Throwable failure) {
            statusText = "改名失败：" + (failure.getMessage() == null || failure.getMessage().trim().isEmpty()
                    ? "本机写入出错" : failure.getMessage().trim());
            failed = true;
            return;
        }
        NCMScreen.getInstance().markDirty();
        DownloadDynamicIsland.showModelOperationResult("本地歌单",
                "已改名为「" + renamed.getName() + "」", true);
        dispose();
        // 用替换而不是压栈：用户是从这张详情页进来改名的，回去不该在历史里多出一页旧名字的。
        NCMScreen.getInstance().replaceCurrentPanel(new PlaylistPanel(renamed));
    }
}
