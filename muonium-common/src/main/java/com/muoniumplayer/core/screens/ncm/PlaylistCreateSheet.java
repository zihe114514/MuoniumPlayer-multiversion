package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.core.ncm.music.LocalMusicLibrary;
import com.muoniumplayer.core.ncm.music.LocalPlaylistStore;
import com.muoniumplayer.core.ncm.music.BodianMusicService;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.NeteaseMusicService;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
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

    private static final double INSET = 24.0;
    private static final double FIELD_H = 64.0;
    private static final double FOOTER_H = 94.0;
    private static final double BUTTON_GAP = 12.0;
    /** 歌单名上限。40 个汉字足够表达任何一张单子，再长侧栏那一行也放不下。 */
    private static final int NAME_LIMIT = 40;

    private static final String IDLE_TEXT = "只存在本机 · 不上传到任何平台";
    private static final String IDLE_TEXT_RENAME = "只改名字 · 曲目与所属音源都不动";

    private final NcmExpressiveLayout layout = new NcmExpressiveLayout();
    private TextFieldWidget input;
    /** 非空即「重命名这一张」模式；为 null 时是新建。 */
    private final PlayList renaming;
    private String statusText = IDLE_TEXT;
    private boolean failed;
    /** 「导入本地文件夹」的选择器在打开中；同一张建单页同一时刻只许一枚系统对话框。 */
    private boolean folderImportInFlight;

    private boolean isQQCloudMode() {
        return NeteaseMusicService.getCurrentPlatform() == MusicPlatform.QQ
                && NeteaseMusicService.isLoggedIn(MusicPlatform.QQ);
    }

    private boolean isBodianCloudMode() {
        return NeteaseMusicService.getCurrentPlatform() == MusicPlatform.BODIAN
                && NeteaseMusicService.isLoggedIn(MusicPlatform.BODIAN);
    }

    private boolean isCloudPlaylistMode() {
        return isQQCloudMode() || isBodianCloudMode();
    }

    public PlaylistCreateSheet() {
        this(null);
    }

    public PlaylistCreateSheet(PlayList renaming) {
        this.renaming = renaming;
        if (renaming != null) this.statusText = IDLE_TEXT_RENAME;
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
        beginPage();

        final Panel dialog = NcmExpressive.shell(this, 660, () -> renaming == null ? 686 : 472,
                () -> presentation, this::dismiss);
        NcmExpressive.header(dialog, renaming == null ? PartyIcons.ALBUM : PartyIcons.TEXT_FORMAT,
                () -> isQQCloudMode() ? "QQ 音乐歌单" : isBodianCloudMode() ? "波点音乐歌单" : "Mu 本地歌单",
                () -> renaming == null
                        ? (isQQCloudMode() ? "新建 QQ 音乐歌单" : isBodianCloudMode() ? "新建波点音乐歌单" : "新建本地歌单")
                        : (isQQCloudMode() ? "重命名 QQ 音乐歌单" : isBodianCloudMode() ? "重命名波点音乐歌单" : "重命名本地歌单"),
                this::dismiss);
        final Panel content = body(dialog, () -> NcmExpressive.headerHeight(dialog) + 14,
                () -> footerHeight(dialog));
        final ScrollPanel list = feed(content);
        nameForm = strip(list, () -> (renaming == null ? 438 : 224) + errorDetailsHeight());
        buildNameForm(nameForm);
        if (renaming == null && !isCloudPlaylistMode()) buildImportEntries(nameForm);

        final Panel footer = new Panel();
        dialog.addChild(footer);
        footer.setBeforeRenderCallback(() -> {
            footer.setBounds(dialog.getWidth(), footerHeight(dialog));
            footer.setPosition(0, Math.max(0, dialog.getHeight() - footer.getHeight()));
        });
        NcmExpressive.action(footer, "", () -> "取消", NcmExpressive.ActionStyle.TONAL,
                () -> INSET, () -> 20,
                () -> cancelWidth(footer),
                () -> NcmExpressive.BUTTON_HEIGHT, this::dismiss);
        NcmExpressive.action(footer, PartyIcons.CHECK, () -> renaming == null ? "新建" : "保存",
                NcmExpressive.ActionStyle.PRIMARY,
                () -> footer.getWidth() - INSET - confirmWidth(footer),
                () -> footer.getWidth() < 360 ? 82 : 20,
                () -> confirmWidth(footer), () -> NcmExpressive.BUTTON_HEIGHT, this::submit);
    }

    private void buildNameForm(Panel host) {
        NcmExpressive.label(host, () -> renaming == null ? "给歌单起个名字" : "输入新的歌单名",
                NcmExpressive.labelFont(), () -> INSET, () -> 24,
                () -> host.getWidth() - INSET * 2, () -> NcmExpressivePalette.live().text());
        final RoundedRectWidget field = NcmExpressive.surface(host, INSET, 54,
                () -> host.getWidth() - INSET * 2, () -> FIELD_H, 17,
                () -> NcmExpressivePalette.live().input());
        stroke(field, 17, () -> NcmExpressivePalette.live().outline(), () -> .7);
        stroke(field, 17, () -> failed ? NcmExpressivePalette.live().error()
                        : NcmExpressivePalette.live().primary(),
                () -> failed || input.isFocused() ? 1 : 0);
        NcmExpressive.icon(field, renaming == null ? PartyIcons.ALBUM : PartyIcons.TEXT_FORMAT,
                ICON_L, () -> 27, () -> FIELD_H * .5,
                () -> failed ? NcmExpressivePalette.live().error() : NcmExpressivePalette.live().muted());

        // 同一张表单保留建单与改名的输入、回车和原生剪贴板路径。
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
        input.getTextField().setMaxStringLength(NAME_LIMIT);
        input.setEnabled(true).setFocused(true);
        // 保留空输入的新名字语义；旧名字只在占位说明中显示，不触碰既有歌单对象。
        input.setPlaceholder(renaming == null ? "比如「跑步」「睡前」「待听」"
                : "当前「" + (renaming.getName() == null ? "" : renaming.getName()) + "」· 输入新名字");
        input.drawUnderline(false);
        input.setBeforeRenderCallback(() -> {
            input.setBounds(Math.max(1, field.getWidth() - 70), FIELD_H - 16);
            input.setPosition(52, 8);
            NcmExpressivePalette palette = NcmExpressivePalette.live();
            input.setColor(palette.text());
            input.setDisabledTextColor(palette.muted());
        });
        NcmExpressive.label(host,
                () -> renaming == null ? "建好后，可从任意音源把喜欢的歌加进来。" : "这里只换名字，不会移除或重新下载曲目。",
                NcmExpressive.bodyFont(), () -> INSET, () -> 146,
                () -> host.getWidth() - INSET * 2, () -> NcmExpressivePalette.live().muted());
        NcmExpressive.status(host, () -> failed ? PartyIcons.CLOSE : PartyIcons.INFO, () -> statusText,
                () -> INSET, () -> 176, () -> host.getWidth() - INSET * 2,
                () -> failed ? NcmExpressivePalette.live().error() : NcmExpressivePalette.live().primary());
        final Panel details = new Panel();
        host.addChild(details);
        final long[] renderedRevision = {-1};
        details.setClickable(false).setBeforeRenderCallback(() -> {
            details.setBounds(host.getWidth(), errorDetailsHeight());
            details.setPosition(0, 224);
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

    private void buildImportEntries(Panel host) {
        final Panel entries = new Panel();
        host.addChild(entries);
        entries.setBeforeRenderCallback(() -> {
            entries.setBounds(host.getWidth(), 214);
            entries.setPosition(0, 224 + errorDetailsHeight());
        });
        NcmExpressive.label(entries, () -> "已有收藏？也可以直接导入", NcmExpressive.labelFont(),
                () -> INSET, () -> 22, () -> entries.getWidth() - INSET * 2,
                () -> NcmExpressivePalette.live().text());
        buildImportEntry(entries, 50, PartyIcons.LINK, "从链接导入", "保存歌单引用，不下载全部歌曲", this::openImport);
        buildImportEntry(entries, 136, PartyIcons.FOLDER_OPEN, "导入本地文件夹", "系统选择器 · 只扫描这一层，不递归", this::openLocalFolderImport);
    }

    private void buildImportEntry(Panel host, double top, String glyph, String title,
                                  String detail, Runnable onClick) {
        final RoundedRectWidget entry = NcmExpressive.hitSurface(host, () -> INSET, () -> top,
                () -> host.getWidth() - INSET * 2, () -> 76, NcmExpressive.CARD_RADIUS,
                () -> NcmExpressivePalette.live().container(), onClick);
        NcmExpressive.icon(entry, glyph, ICON_XL, () -> 32, () -> 38,
                () -> NcmExpressivePalette.live().primary());
        NcmExpressive.label(entry, () -> title, NcmExpressive.labelFont(), () -> 62, () -> 25,
                () -> entry.getWidth() - 90, () -> NcmExpressivePalette.live().text());
        NcmExpressive.label(entry, () -> detail, NcmExpressive.captionFont(), () -> 62, () -> 52,
                () -> entry.getWidth() - 90, () -> NcmExpressivePalette.live().muted());
        NcmExpressive.icon(entry, PartyIcons.CHEVRON_RIGHT, ICON_M,
                () -> entry.getWidth() - 20, () -> 38, () -> NcmExpressivePalette.live().muted());
    }

    private Panel nameForm;
    private String[] errorLines = new String[0];
    private String wrappedError = "";
    private double wrappedErrorWidth = -1;
    private CFontRenderer wrappedErrorFont;
    private long wrappedErrorMetrics = -1;
    private long errorLayoutRevision;

    private double errorDetailsHeight() {
        String reason = failed && statusText != null ? statusText : "";
        double width = Math.max(1, nameForm.getWidth() - INSET * 2);
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

    private static double cancelWidth(Panel footer) {
        if (footer.getWidth() < 360) return Math.max(1, footer.getWidth() - INSET * 2);
        return Math.min(154, Math.max(1, (footer.getWidth() - INSET * 2 - BUTTON_GAP) * .38));
    }

    private static double confirmWidth(Panel footer) {
        if (footer.getWidth() < 360) return Math.max(1, footer.getWidth() - INSET * 2);
        return Math.min(214, Math.max(1, (footer.getWidth() - INSET * 2 - BUTTON_GAP) * .50));
    }

    @Override
    public boolean onKeyTyped(char character, int keyCode) {
        if (keyCode == Keyboard.KEY_TAB && !closing) {
            input.setFocused(true);
            return true;
        }
        return super.onKeyTyped(character, keyCode);
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
     * <p>选择器仍沿用既有主线程模态调用，有效选择后关闭浮层，再在后台线程上扫描。
     * 目录逐个读标签可能耗时数秒，完成后回主线程建单、灌曲、进详情页；
     * {@code LocalPlaylistStore} 的落盘保持现有主线程写入约定。</p>
     *
     * <p>防重入：选择器开着时按钮还能被点到（系统对话框吞不到我们的鼠标事件），加一扇闸。</p>
     */
    private void openLocalFolderImport() {
        openLocalFolderImport(this::dispose);
    }

    /** 链接/文件夹导入页复用同一条选择与扫描路径；有效选择后才关闭调用方浮层。 */
    void openLocalFolderImport(Runnable onAccepted) {
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
            onAccepted.run();
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
            if (isQQCloudMode() && renaming.getPlatform() == MusicPlatform.QQ) {
                renameQQ(name);
                return;
            }
            if (isBodianCloudMode() && renaming.getPlatform() == MusicPlatform.BODIAN) {
                renameBodian(name);
                return;
            }
            renameTo(name);
            return;
        }
        if (isQQCloudMode()) {
            createQQ(name);
            return;
        }
        if (isBodianCloudMode()) {
            createBodian(name);
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

    private void createQQ(String name) {
        try {
            long tid = NeteaseMusicService.createQQPlaylist(name);
            NeteaseMusicService.forceRefreshQQAccountAsync(null);
            PlayList created = new PlayList(tid, name, "", 0, 0L, null,
                    "QQ 音乐歌单", 0L);
            created.setPlatform(MusicPlatform.QQ);
            created.setPlatformPlaylistId(String.valueOf(tid));
            DownloadDynamicIsland.showModelOperationResult("QQ 音乐歌单", "已新建「" + name + "」", true);
            dispose();
            NCMScreen.getInstance().setCurrentPanel(new PlaylistPanel(created));
        } catch (Throwable failure) {
            statusText = "QQ 歌单创建失败：" + (failure.getMessage() == null ? "未确认写入" : failure.getMessage());
            failed = true;
        }
    }

    private void renameQQ(String name) {
        try {
            boolean ok = NeteaseMusicService.renameQQPlaylist(renaming.getPlatformPlaylistId(), name);
            if (!ok) throw new IllegalStateException("写后核对未通过");
            NeteaseMusicService.forceRefreshQQAccountAsync(null);
            PlayList renamed = new PlayList(renaming.getId(), name, renaming.getCoverUrl(),
                    renaming.getCount(), renaming.getPlayCount(), renaming.getCreator(),
                    renaming.getDescription(), renaming.getCreateTime());
            renamed.setPlatform(MusicPlatform.QQ);
            renamed.setPlatformPlaylistId(renaming.getPlatformPlaylistId());
            DownloadDynamicIsland.showModelOperationResult("QQ 音乐歌单", "已改名为「" + name + "」", true);
            dispose();
            NCMScreen.getInstance().replaceCurrentPanel(new PlaylistPanel(renamed));
        } catch (Throwable failure) {
            statusText = "QQ 歌单改名失败：" + (failure.getMessage() == null ? "未确认写入" : failure.getMessage());
            failed = true;
        }
    }

    private void createBodian(String name) {
        try {
            PlayList created = BodianMusicService.createPlaylist(name);
            if (created == null) throw new IllegalStateException("服务端未返回新歌单");
            BodianMusicService.forceRefreshUserPlaylistsAsync(null);
            NCMScreen.getInstance().markDirty();
            DownloadDynamicIsland.showModelOperationResult("波点音乐歌单", "已新建「" + name + "」", true);
            dispose();
            NCMScreen.getInstance().setCurrentPanel(new PlaylistPanel(created));
        } catch (Throwable failure) {
            statusText = "波点歌单创建失败：" + (failure.getMessage() == null ? "未确认写入" : failure.getMessage());
            failed = true;
        }
    }

    private void renameBodian(String name) {
        try {
            boolean ok = BodianMusicService.renamePlaylist(renaming.getPlatformPlaylistId(), name);
            if (!ok) throw new IllegalStateException("服务端未确认改名");
            BodianMusicService.forceRefreshUserPlaylistsAsync(null);
            PlayList renamed = new PlayList(renaming.getId(), name, renaming.getCoverUrl(),
                    renaming.getCount(), renaming.getPlayCount(), renaming.getCreator(),
                    renaming.getDescription(), renaming.getCreateTime());
            renamed.setPlatform(MusicPlatform.BODIAN);
            renamed.setPlatformPlaylistId(renaming.getPlatformPlaylistId());
            DownloadDynamicIsland.showModelOperationResult("波点音乐歌单", "已改名为「" + name + "」", true);
            dispose();
            NCMScreen.getInstance().replaceCurrentPanel(new PlaylistPanel(renamed));
        } catch (Throwable failure) {
            statusText = "波点歌单改名失败：" + (failure.getMessage() == null ? "未确认写入" : failure.getMessage());
            failed = true;
        }
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
