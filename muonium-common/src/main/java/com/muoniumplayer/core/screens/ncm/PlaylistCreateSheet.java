package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.LocalMusicLibrary;
import com.muoniumplayer.core.ncm.music.LocalPlaylistStore;
import com.muoniumplayer.core.ncm.music.dto.Music;
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

    /** 「导入本地文件夹」按钮的宽度。三个按钮一排，比链接导入那颗窄不了多少。 */
    private static final double LOCAL_IMPORT_W = 128.0;

    private TextFieldWidget input;
    /** 非空即「重命名这一张」模式；为 null 时是新建。 */
    private final PlayList renaming;
    private String statusText = IDLE_TEXT;
    private boolean failed;
    /** 「导入本地文件夹」的选择器在打开中；同一张建单页同一时刻只许一枚系统对话框。 */
    private boolean folderImportInFlight;

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

        // 本地文件夹是第三条路：扫磁盘上的音频文件灌成一张单。与「从链接导入」并列——
        // 两条路互不依赖，用户有本地音乐就该有一个直达入口，而不是先建一张空单再想办法。
        // 同样只在新建模式显示；重命名模式那颗「保存」按 importFromLink 隐藏后的位置排。
        final RoundedButtonWidget importFromDisk = action(dialog, () -> PartyIcons.ALBUM,
                () -> "导入本地文件夹", FontManager.pf12bold,
                () -> NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT), this::openLocalFolderImport);
        importFromDisk.setBeforeRenderCallback(() -> {
            importFromDisk.setBounds(LOCAL_IMPORT_W, BUTTON_H);
            importFromDisk.setPosition(importFromLink.getRelativeX() + IMPORT_W + BUTTON_GAP,
                    dialog.getHeight() - BUTTON_H - 10);
            importFromDisk.setColor(importFromDisk.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            importFromDisk.setHidden(renaming != null);
        });

        final RoundedButtonWidget confirm = action(dialog, () -> PartyIcons.CHECK,
                () -> renaming == null ? "新建" : "保存", FontManager.pf12bold,
                () -> 0xFFFFFF, this::submit);
        confirm.setBeforeRenderCallback(() -> {
            double left = renaming == null
                    ? importFromDisk.getRelativeX() + LOCAL_IMPORT_W + BUTTON_GAP
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

    /**
     * 「导入本地文件夹」：系统目录选择器挑一个磁盘目录，扫描其中的音频文件灌成一张新歌单。
     *
     * <p>先关掉自己再弹选择器：JFileChooser 是模态的系统对话框（{@code FoliaModelManager} 已有
     * 同款先例），底下压着一张浮层会让关掉选择器后的画面多一帧残影。扫描在后台线程上跑
     * （一个几百首的目录逐个读标签要几秒），完成后回主线程建单、灌曲、进详情页——
     * {@code LocalPlaylistStore} 的落盘在主线程做与现有写入路径同一约定。</p>
     *
     * <p>防重入：选择器开着时按钮还能被点到（系统对话框吞不到我们的鼠标事件），加一扇闸。</p>
     */
    private void openLocalFolderImport() {
        if (folderImportInFlight) return;
        folderImportInFlight = true;
        try {
            String initial = LocalMusicLibrary.directories().isEmpty()
                    ? new java.io.File("").getAbsolutePath() : LocalMusicLibrary.directories().get(0);
            javax.swing.JFileChooser chooser = new javax.swing.JFileChooser(initial);
            chooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("选择音乐文件夹");
            chooser.setApproveButtonText("导入这个文件夹");
            // 点击回调本身就跑在主线程上（addScheduledTask 有同线程 fast path，直接执行不排队）。
            // 必须经 withHeadful：原版 Main 的静态初始化把 java.awt.headless 写死成 true，
            // 直接 showOpenDialog 会抛 HeadlessException，表现就是「点了没反应」。
            Boolean approved = com.muoniumplayer.core.utils.SwingDialogs.withHeadful(
                    () -> chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION,
                    Boolean.FALSE);
            if (!approved) {
                return;
            }
            final java.io.File picked = chooser.getSelectedFile();
            if (picked == null || !picked.isDirectory()) {
                return;
            }
            final String directory = picked.getAbsolutePath();
            dispose();
            com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil.runAsync(() -> {
                try {
                    LocalMusicLibrary.addDirectory(directory);
                    java.util.List<com.muoniumplayer.core.ncm.music.dto.Music> tracks =
                            LocalMusicLibrary.scanDirectory(directory);
                    com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil
                            .runOnMainThread(() -> finishLocalFolderImport(directory, tracks));
                } catch (Throwable failure) {
                    com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil
                            .runOnMainThread(() -> DownloadDynamicIsland.showModelOperationResult(
                                    "本地音乐", "导入失败：" + failure.getMessage(), false));
                }
            });
        } finally {
            folderImportInFlight = false;
        }
    }

    /** 扫描完成后的主线程收尾：建单、灌曲、进详情页。空目录也要说话，不能静默吞掉。 */
    private void finishLocalFolderImport(String directory,
                                         java.util.List<com.muoniumplayer.core.ncm.music.dto.Music> tracks) {
        final java.io.File dirFile = new java.io.File(directory);
        final String name = dirFile.getName() == null || dirFile.getName().trim().isEmpty()
                ? "本地音乐" : dirFile.getName();
        if (tracks == null || tracks.isEmpty()) {
            DownloadDynamicIsland.showModelOperationResult("本地音乐",
                    "「" + name + "」里没有支持的音频文件（mp3 / flac / wav / m4a / aac）", false);
            return;
        }
        final PlayList created;
        try {
            created = LocalPlaylistStore.create(name);
        } catch (Throwable failure) {
            DownloadDynamicIsland.showModelOperationResult("本地音乐",
                    "建单失败：" + failure.getMessage(), false);
            return;
        }
        int added = 0;
        for (com.muoniumplayer.core.ncm.music.dto.Music track : tracks) {
            if (LocalPlaylistStore.addTrack(created, track)) added++;
        }
        // 目录列表变了，侧栏下一次展开要重读；与建单路径同一句 markDirty。
        NCMScreen.getInstance().markDirty();
        DownloadDynamicIsland.showModelOperationResult("本地音乐",
                "已导入 " + added + " 首 → 「" + created.getName() + "」", true);
        NCMScreen.getInstance().setCurrentPanel(new PlaylistPanel(created));
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
