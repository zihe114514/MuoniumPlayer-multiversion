package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.FoliaModelManager;
import com.muoniumplayer.core.ncm.music.FoliaModelNotice;
import com.muoniumplayer.core.rendering.FontelloIcons;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;

import java.util.List;
import java.util.function.Supplier;

/**
 * 「这台电脑还没装 Folia 分析模型」的模态提示。
 *
 * <p>触发时机和 ffmpeg 提示（{@code GuiFfmpegNotice} 在主菜单弹）不同：这个提示在<b>本次启动
 * 第一次打开播放器界面</b>时弹一次，因为用户是在这里才开始真正用到 automix。闸门与判定都在
 * {@link FoliaModelNotice} 里，本类只负责界面与安装动作。</p>
 *
 * <p>刻意做成播放器自己的模态浮层、而不是再压一个原版 {@code GuiScreen}：播放器界面本身就跑
 * 在模组的 Skija 渲染栈上，那套浮层体系（遮罩、主题色、随主题重着色、模态输入独占）本来就是
 * 为「盖在播放器上」准备的。同层的 {@link ConfirmationOverlay} 是最接近的先例。</p>
 *
 * <p>只是提示，不是拦路：不装模型照样能听歌，只是切歌时退回原版智能淡化。两个出口
 * （全部安装 / 稍后再说）与 ESC 都不改动任何已有设置。</p>
 */
public final class ModelInstallNoticeOverlay extends NCMPanel {

    /** 提示里逐行列出状态的组件，顺序即展示顺序。 */
    private static final FoliaModelManager.Kind[] KINDS = {
            FoliaModelManager.Kind.BEAT_THIS,
            FoliaModelManager.Kind.HTDEMUCS,
            FoliaModelManager.Kind.RUNTIME
    };

    /** 卡片正文：解释这些组件是干什么的、不装会怎样。 */
    private static final String INFO =
            "智能混音 需要用下面的组件在本机分析节拍、人声与乐段。"
                    + "不安装也能正常播放，只在切歌时退回原版智能淡化。";

    private static final String TITLE = "未安装 Folia 分析模型";
    private static final String LATER = "稍后再说";
    private static final String INSTALL = "全部安装";
    private static final String INSTALLING = "正在安装…";

    /** 卡片左右留白，正文、状态行、按钮共用同一套内缩，任何一处改动都不会各自跑偏。 */
    private static final double SIDE_INSET = 18.0;
    /** 徽标与标题所在的高度带。 */
    private static final double HEADER_CENTER_Y = 28.0;
    /** 三个状态行的起始高度与行高。 */
    private static final double ROWS_TOP = 56.0;
    private static final double ROW_HEIGHT = 30.0;
    private static final double ROW_GAP = 6.0;
    /** 状态行与下面正文之间的留白。 */
    private static final double INFO_GAP = 12.0;
    /** 底部分隔线到卡片底部的距离，即按钮区高度。 */
    private static final double ACTIONS_HEIGHT = 43.0;
    /** 正文最多显示的行数；再长会截断，卡片不允许长到顶出播放器。 */
    private static final int MAX_INFO_LINES = 4;

    /** 行首状态点与文字的直径、以及文字相对卡片左缘的偏移。 */
    private static final double DOT_SIZE = 8.0;
    private static final double DOT_LEFT = 12.0;
    private static final double ROW_TEXT_LEFT = 28.0;

    /** 未安装/进行中（琥珀）与已安装（绿）的状态色。 */
    private static final int PENDING_COLOR = 0xE0A030;
    private static final int READY_COLOR = 0x62C47A;
    /** 安装失败时状态行的颜色。 */
    private static final int ERROR_COLOR = 0xEC665D;

    private boolean closing;
    private boolean resolved;

    /** 安装是串行跑的，回调在后台线程上，所以这三个字段要和渲染线程共享，一律 volatile。 */
    private volatile boolean installing;
    private volatile boolean statusError;
    private volatile String status = "下载后会校验文件大小与 SHA-256，失败会自动切换镜像重试。";

    /** 按当前卡片宽度换行后的正文。 */
    private String[] infoLines = new String[0];
    /** 上一次换行用的宽度；宽度没变就不重复换行（换行要逐字累加字形宽度）。 */
    private double wrappedForWidth = -1.0;
    /** 上一次换行时字形是否已全部就绪；没就绪算出来的宽度是 0，必须下一帧重算。 */
    private boolean wrappedWithLoadedGlyphs;
    /** 标题是否已经降到小一号字体；只在需要变化时才换字体。 */
    private boolean titleCompact;

    public boolean shouldClose() {
        return closing;
    }

    /** 「稍后再说」、点遮罩、ESC 都走这里：收起提示，本次启动不再弹（由 FoliaModelNotice 记）。 */
    public void cancel() {
        if (resolved) return;
        resolved = true;
        closing = true;
    }

    public void handleEscape() {
        cancel();
    }

    @Override
    public void onInit() {
        RectWidget mask = new RectWidget();
        addChild(mask);
        mask.setClickable(false);
        mask.setColor(0x000000).setAlpha(.48f);
        mask.setBeforeRenderCallback(() -> mask.setMargin(0));

        // 点在卡片外面等价于「稍后再说」，并且必须被这里吃掉：否则会落到下面播放器的控件上，
        // 用户在读提示的时候把歌单点开了。
        setOnClickCallback((x, y, button) -> {
            if (button == 0) {
                cancel();
                return true;
            }
            return false;
        });

        Panel dialog = new Panel();
        addChild(dialog);
        dialog.setOnClickCallback((x, y, button) -> true);
        // 高度由正文行数决定，所以尺寸每帧在渲染前重算：窗口变窄会让正文多一行，卡片必须跟着长高，
        // 否则按钮会压在正文上。
        dialog.setBeforeRenderCallback(() -> layoutDialog(dialog));

        RoundedRectWidget background = new RoundedRectWidget();
        dialog.addChild(background);
        background.setClickable(false);
        background.setRadius(11);
        background.setColor(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND));
        background.setBeforeRenderCallback(() -> background.setMargin(0));

        RoundedRectWidget badge = new RoundedRectWidget();
        dialog.addChild(badge);
        badge.setClickable(false);
        badge.setRadius(12);
        badge.setColor(PENDING_COLOR);
        badge.setBeforeRenderCallback(() -> badge.setBounds(SIDE_INSET, 16, 24, 24));

        // Fontello 的警告字形随播放器内置，避免半角感叹号在缺字形时退化成方框。
        LabelWidget warningIcon = new LabelWidget(FontelloIcons.WARNING, FontManager.fontello18);
        dialog.addChild(warningIcon);
        warningIcon.setClickable(false);
        warningIcon.setBeforeRenderCallback(() -> {
            warningIcon.setColor(0xFFFFFF);
            warningIcon.setPosition(SIDE_INSET + 12 - warningIcon.getWidth() * .5,
                    HEADER_CENTER_Y - warningIcon.getHeight() * .5 + 1);
        });

        LabelWidget titleLabel = new LabelWidget(TITLE, FontManager.pf18bold);
        dialog.addChild(titleLabel);
        titleLabel.setClickable(false);
        titleLabel.setBeforeRenderCallback(() -> {
            double maxWidth = Math.max(1, dialog.getWidth() - SIDE_INSET - 52);
            // 窄窗口下宁可降一号字也不截断：标题就是这次提示的主语，看不全等于没提示。
            boolean compact = FontManager.pf18bold != null
                    && FontManager.pf18bold.getStringWidthD(TITLE) > maxWidth;
            if (compact != titleCompact) {
                titleCompact = compact;
                titleLabel.setFont(compact ? FontManager.pf14bold : FontManager.pf18bold);
            }
            titleLabel.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            titleLabel.setMaxWidth(maxWidth);
            titleLabel.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            titleLabel.setPosition(SIDE_INSET + 40, HEADER_CENTER_Y - titleLabel.getHeight() * .5);
        });

        // 三个组件各一行：状态文字直接取 FoliaModelManager 的实时标签，所以下载进度、
        // 成功与失败都会自己刷新，这里不需要额外的状态机。
        for (int index = 0; index < KINDS.length; index++) {
            final FoliaModelManager.Kind kind = KINDS[index];
            final int rowIndex = index;

            RoundedRectWidget rowSurface = new RoundedRectWidget();
            dialog.addChild(rowSurface);
            rowSurface.setClickable(false);
            rowSurface.setRadius(6);
            rowSurface.setBeforeRenderCallback(() -> {
                rowSurface.setBounds(SIDE_INSET, rowTop(rowIndex),
                        Math.max(1, dialog.getWidth() - SIDE_INSET * 2), ROW_HEIGHT);
                rowSurface.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
            });

            RoundedRectWidget dot = new RoundedRectWidget();
            dialog.addChild(dot);
            dot.setClickable(false);
            dot.setRadius(DOT_SIZE * .5);
            dot.setBeforeRenderCallback(() -> {
                dot.setBounds(SIDE_INSET + DOT_LEFT, rowTop(rowIndex) + (ROW_HEIGHT - DOT_SIZE) * .5,
                        DOT_SIZE, DOT_SIZE);
                dot.setColor(installedQuietly(kind) ? READY_COLOR : PENDING_COLOR);
            });

            LabelWidget rowLabel = new LabelWidget(new Supplier<String>() {
                @Override
                public String get() {
                    try {
                        return FoliaModelManager.statusLabel(kind);
                    } catch (Throwable ignored) {
                        // 标签只是展示：取不到就退回枚举名，绝不因为一行文字让提示打不开。
                        return kind.name();
                    }
                }
            }, FontManager.pf12);
            dialog.addChild(rowLabel);
            rowLabel.setClickable(false);
            rowLabel.setMaxWidth(Math.max(1, dialog.getWidth() - SIDE_INSET * 2 - ROW_TEXT_LEFT - 8));
            rowLabel.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            rowLabel.setBeforeRenderCallback(() -> {
                rowLabel.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                double textHeight = rowLabel.getHeight();
                rowLabel.setPosition(SIDE_INSET + ROW_TEXT_LEFT,
                        rowTop(rowIndex) + (ROW_HEIGHT - textHeight) * .5);
            });
        }

        // 正文每行一个 Label：这套字体渲染器没有多行绘制，换行结果由 wrapInfo 每次布局时算好。
        // 用不到的行取空字符串而不是 setHidden——被隐藏的组件不再执行自己的回调，就再也显示不回来。
        for (int index = 0; index < MAX_INFO_LINES; index++) {
            final int lineIndex = index;
            LabelWidget infoLabel = new LabelWidget(new Supplier<String>() {
                @Override
                public String get() {
                    return lineIndex < infoLines.length ? infoLines[lineIndex] : "";
                }
            }, FontManager.pf12);
            dialog.addChild(infoLabel);
            infoLabel.setClickable(false);
            infoLabel.setMaxWidth(Math.max(1, dialog.getWidth() - SIDE_INSET * 2));
            infoLabel.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            infoLabel.setBeforeRenderCallback(() -> {
                infoLabel.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
                infoLabel.setPosition(SIDE_INSET, infoTop() + lineIndex * lineHeight());
            });
        }

        // 模型目录：路径可能很长，按宽度截断而不是换行——它是排查用的信息，不是说明。
        LabelWidget pathLabel = new LabelWidget(new Supplier<String>() {
            @Override
            public String get() {
                try {
                    return FontManager.pf12 == null ? "" : FontManager.pf12.trim(
                            FoliaModelManager.directoryLabel(),
                            Math.max(1, dialog.getWidth() - SIDE_INSET * 2));
                } catch (Throwable ignored) {
                    return "";
                }
            }
        }, FontManager.pf12);
        dialog.addChild(pathLabel);
        pathLabel.setClickable(false);
        pathLabel.setMaxWidth(Math.max(1, dialog.getWidth() - SIDE_INSET * 2));
        pathLabel.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
        pathLabel.setBeforeRenderCallback(() -> {
            pathLabel.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            pathLabel.setAlpha(.72f);
            pathLabel.setPosition(SIDE_INSET, pathTop());
        });

        // 下载进度/结果行：装的是同一个东西，所以直接复用说明区下面的这一行。
        LabelWidget statusLabel = new LabelWidget(new Supplier<String>() {
            @Override
            public String get() {
                return status == null ? "" : status;
            }
        }, FontManager.pf12);
        dialog.addChild(statusLabel);
        statusLabel.setClickable(false);
        statusLabel.setMaxWidth(Math.max(1, dialog.getWidth() - SIDE_INSET * 2));
        statusLabel.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
        statusLabel.setBeforeRenderCallback(() -> {
            statusLabel.setColor(statusError ? ERROR_COLOR
                    : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            statusLabel.setPosition(SIDE_INSET, statusTop());
        });

        RectWidget divider = new RectWidget();
        dialog.addChild(divider);
        divider.setClickable(false);
        divider.setBeforeRenderCallback(() -> {
            divider.setBounds(SIDE_INSET, dialog.getHeight() - ACTIONS_HEIGHT,
                    Math.max(1, dialog.getWidth() - SIDE_INSET * 2), .5);
            divider.setColor(NCMScreen.getColor(NCMScreen.ColorType.BORDER));
        });

        RoundedButtonWidget laterButton = new RoundedButtonWidget(LATER, FontManager.pf12bold);
        dialog.addChild(laterButton);
        laterButton.setRadius(6);
        RoundedButtonWidget installButton = new RoundedButtonWidget(new Supplier<String>() {
            @Override
            public String get() {
                return installing ? INSTALLING : INSTALL;
            }
        }, FontManager.pf12bold);
        dialog.addChild(installButton);
        installButton.setRadius(6);

        // 两个按钮共用一次宽度计算，保证左右对齐、中间间隙一致。
        laterButton.setBeforeRenderCallback(() -> {
            layoutActions(dialog, laterButton, installButton);
            laterButton.setColor(laterButton.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            laterButton.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
        laterButton.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            cancel();
            return true;
        });

        installButton.setBeforeRenderCallback(() -> {
            layoutActions(dialog, laterButton, installButton);
            // 安装中按钮变暗：再点一次不会有第二条下载，但要让用户看出「已经在做了」。
            installButton.setColor(installing ? 0x8A6A33
                    : (installButton.isHovering() ? 0xE5B24E : 0xD2913A));
            installButton.setTextColor(installing ? 0xD8D8D8 : 0xFFFFFF);
        });
        installButton.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            installMissing();
            return true;
        });
    }

    private void layoutDialog(Panel dialog) {
        double width = Math.max(272, Math.min(360, getWidth() - 32));
        wrapInfo(Math.max(1, width - SIDE_INSET * 2));
        // 上限用「播放器高度 - 28」而不是写死的数：这样卡片永远落在播放器的裁剪区内。
        // 正常窗口下这个上限根本不会生效，只有极小的窗口才会压到正文行数。
        dialog.setBounds(width, Math.min(Math.max(180.0, contentHeight()),
                Math.max(180.0, getHeight() - 28)));
        dialog.center();
    }

    private static void layoutActions(Panel dialog, RoundedButtonWidget later, RoundedButtonWidget install) {
        double totalWidth = Math.max(1, dialog.getWidth() - SIDE_INSET * 2);
        double gap = 8;
        double installWidth = Math.max(104, Math.min(140, totalWidth * .56));
        double laterWidth = Math.max(1, totalWidth - gap - installWidth);
        double top = dialog.getHeight() - 34;
        later.setBounds(laterWidth, 26);
        later.setPosition(SIDE_INSET, top);
        install.setBounds(installWidth, 26);
        install.setPosition(SIDE_INSET + laterWidth + gap, top);
    }

    private static double rowTop(int index) {
        return ROWS_TOP + index * (ROW_HEIGHT + ROW_GAP);
    }

    private static double rowsHeight() {
        return KINDS.length * ROW_HEIGHT + (KINDS.length - 1) * ROW_GAP;
    }

    private double infoTop() {
        return ROWS_TOP + rowsHeight() + INFO_GAP;
    }

    private double infoBlockHeight() {
        return Math.max(lineHeight(), infoLines.length * lineHeight());
    }

    private double pathTop() {
        return infoTop() + infoBlockHeight() + 2.0;
    }

    private double statusTop() {
        return pathTop() + lineHeight() + 4.0;
    }

    /** 卡片为了装下三段状态行、正文、目录与状态行、以及按钮区所需的高度。 */
    private double contentHeight() {
        return statusTop() + lineHeight() + INFO_GAP + ACTIONS_HEIGHT;
    }

    private static double lineHeight() {
        return (FontManager.pf12 == null ? 9.0 : FontManager.pf12.getFontHeight()) + 3.0;
    }

    /**
     * 安装所有还缺的组件，一次只下一个。
     *
     * <p>串行而不是并发：三条连接同时抢带宽只会让每一个都更慢，而且模型加运行时将近 230 MB，
     * 用户看到的是灵动岛上一条稳定的进度，而不是三条互相拖拽的进度。</p>
     */
    private void installMissing() {
        if (installing) return;
        if (FoliaModelNotice.missing().isEmpty()) {
            // 可能在别处（引导页、HUD 设置）已经装好了，那就直接把提示收掉。
            status = "所有组件都已就绪。";
            statusError = false;
            cancel();
            return;
        }
        installing = true;
        statusError = false;
        status = "正在准备下载，请保持网络连接。";
        installNext();
    }

    private void installNext() {
        List<FoliaModelManager.Kind> pending = FoliaModelNotice.missing();
        if (pending.isEmpty()) {
            installing = false;
            status = "全部组件安装完成，智能混音 已经可用。";
            statusError = false;
            return;
        }
        FoliaModelManager.downloadAsync(pending.get(0), (ok, detail) -> {
            status = detail;
            statusError = !ok;
            if (ok) installNext();
            else installing = false;
        });
    }

    /** 判定失败一律当作「没装」：提示多弹一次远好过该提示时不提示。 */
    private static boolean installedQuietly(FoliaModelManager.Kind kind) {
        try {
            return FoliaModelManager.isInstalled(kind);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 把正文按给定宽度换行，并算出它需要几行。
     *
     * <p>字形是按需上传的，没上传的字宽度为 0——那样换行会把整段算成一行。项目里其它需要测量
     * 文字的地方（{@link ConfirmationOverlay}、{@link LyricLine}）也是这个套路：字形没齐之前
     * 每帧重算，照常绘制会把字形逐步上传，齐了之后再按宽度缓存结果。</p>
     */
    private void wrapInfo(double contentWidth) {
        boolean glyphsReady = FontManager.pf12 != null && FontManager.pf12.areGlyphsLoaded(INFO);
        if (!wrappedWithLoadedGlyphs || Math.abs(contentWidth - wrappedForWidth) >= .5) {
            wrappedForWidth = contentWidth;
            wrappedWithLoadedGlyphs = glyphsReady;
            String[] lines;
            try {
                lines = FontManager.pf12.fitWidth(INFO, contentWidth);
            } catch (Throwable ignored) {
                // 换行只是排版，任何异常都不该让一个提示窗打不开。
                lines = new String[]{INFO};
            }
            if (lines == null || lines.length == 0) lines = new String[]{INFO};
            if (lines.length > MAX_INFO_LINES) {
                String[] limited = new String[MAX_INFO_LINES];
                System.arraycopy(lines, 0, limited, 0, MAX_INFO_LINES);
                limited[MAX_INFO_LINES - 1] = FontManager.pf12.trim(
                        limited[MAX_INFO_LINES - 1], Math.max(1, contentWidth - 8)) + "…";
                lines = limited;
            }
            infoLines = lines;
        }
    }
}
