package com.muoniumplayer.core.screens;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.compat.mc.GuiButton;
import com.muoniumplayer.compat.mc.GuiScreen;
import com.muoniumplayer.core.update.UpdateChecker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 「有新版本」的主菜单提示。
 *
 * <p>与 {@link GuiFfmpegNotice} 同构，理由也一样：这个提示要在<b>主菜单</b>就弹出来，那时播放器界面、
 * 主题色、离屏 framebuffer 都还没进入工作状态，所以用原版 {@link GuiScreen} + {@link GuiButton}，
 * 不碰 Mod 自己的渲染栈。刻意不用矩阵栈缩放标题：{@code getModelViewStack()} 在 1.20.1 上是
 * {@code pushPose/popPose}、在 1.21+ 上是 JOML 的 {@code pushMatrix/popMatrix}，
 * 不缩放就能让四个版本共用一份完全相同的源码。</p>
 *
 * <p>四条出口都不拦路：前往下载 / 复制下载链接 / 之后再说（ESC 等价）/ 跳过这个版本，
 * 另有「不再检查更新」彻底关掉联网检查。</p>
 */
public class GuiUpdateNotice extends GuiScreen {

    private static final int BUTTON_DOWNLOAD = 1;
    private static final int BUTTON_COPY = 2;
    private static final int BUTTON_LATER = 3;

    private static final String HEADING = "MuoniumPlayer 有新版本啦喵~";
    private static final String SKIP_LINK = "跳过这个版本";
    private static final String NEVER_LINK = "不再检查更新";

    private static final int MAX_TEXT_WIDTH = 340;
    private static final int BUTTON_HEIGHT = 20;
    private static final int WIDE_BUTTON_WIDTH = 200;
    private static final int NARROW_BUTTON_WIDTH = 128;

    private static final int PANEL_TOP_PAD = 18;
    private static final int HEADING_GAP = 12;
    private static final int BODY_GAP = 16;
    private static final int BUTTON_GAP = 6;
    private static final int LINK_GAP = 8;
    private static final int LINK_SPACING = 24;
    private static final int PANEL_BOTTOM_PAD = 8;
    private static final int LINE_GAP = 2;

    /** 返回目标。主菜单弹出时是主菜单本身，游戏内弹出时是 {@code null}（即关闭界面）。 */
    private final net.minecraft.client.gui.screens.Screen parent;

    private List<String> bodyLines = new ArrayList<String>();
    private int wrappedForWidth = -1;

    private GuiButton downloadButton;
    private GuiButton copyButton;
    private GuiButton laterButton;

    private int textWidth;
    private int panelLeft;
    private int panelTop;
    private int panelRight;
    private int panelBottom;
    private int headingY;
    private int bodyTop;
    private int statusTop;
    private int downloadButtonTop;
    private int copyButtonTop;
    private int laterButtonTop;
    private int linkTop;
    private int skipLeft;
    private int skipRight;
    private int neverLeft;
    private int neverRight;

    /** 点了「复制下载链接」或浏览器打不开时的一行反馈。 */
    private String status;

    public GuiUpdateNotice(net.minecraft.client.gui.screens.Screen parent) {
        this.parent = parent;
    }

    /**
     * 每 tick 调一次：条件满足就把提示顶到最前面。
     *
     * <p>与 {@link GuiFfmpegNotice#tickAutoShow(Minecraft)} 同样用轮询而不是界面切换事件：
     * 清单是在后台线程上拉的，主菜单出现的那一刻结论往往还没回来，换 GUI 只有一次机会。</p>
     *
     * <p>ffmpeg 提示先弹的情况下这里读到的当前界面不是主菜单，于是本 tick 直接跳过，
     * 等用户关掉那个提示回到主菜单再弹 —— 两个提示天然排队，不会互相盖掉。</p>
     */
    public static void tickAutoShow(Minecraft mc) {
        if (mc == null) return;
        if (!UpdateChecker.shouldShow()) return;

        net.minecraft.client.gui.screens.Screen current = mc.screen;
        boolean atMainMenu = current instanceof TitleScreen;
        boolean inGameIdle = current == null && mc.level != null && mc.player != null;
        if (!atMainMenu && !inGameIdle) return;

        UpdateChecker.markShownThisSession();
        mc.setScreen(new GuiUpdateNotice(atMainMenu ? current : null));
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        layout();

        int centerX = this.width / 2;
        this.downloadButton = new GuiButton(BUTTON_DOWNLOAD,
                centerX - WIDE_BUTTON_WIDTH / 2, this.downloadButtonTop,
                WIDE_BUTTON_WIDTH, BUTTON_HEIGHT, "前往下载");
        this.copyButton = new GuiButton(BUTTON_COPY,
                centerX - WIDE_BUTTON_WIDTH / 2, this.copyButtonTop,
                WIDE_BUTTON_WIDTH, BUTTON_HEIGHT, "复制下载链接");
        this.laterButton = new GuiButton(BUTTON_LATER,
                centerX - NARROW_BUTTON_WIDTH / 2, this.laterButtonTop,
                NARROW_BUTTON_WIDTH, BUTTON_HEIGHT, "之后再说");
        this.buttonList.add(this.downloadButton);
        this.buttonList.add(this.copyButton);
        this.buttonList.add(this.laterButton);
    }

    /**
     * 按当前窗口尺寸算出正文换行、面板范围、三个按钮与两条文字链接的位置。
     *
     * <p>自下而上摆按钮和链接、自上而下摆标题和正文，面板高度取两边之和：任何一段文字变长
     * （换行多一行、或出现了「已复制」那行反馈）时面板跟着变高，不会让按钮压在正文上。</p>
     */
    private void layout() {
        this.textWidth = Math.max(120, Math.min(MAX_TEXT_WIDTH, this.width - 60));
        if (this.wrappedForWidth != this.textWidth) {
            this.wrappedForWidth = this.textWidth;
            this.bodyLines = wrap(buildBody());
        }

        int lineHeight = this.fontRendererObj.FONT_HEIGHT + LINE_GAP;
        int bodyHeight = this.bodyLines.size() * lineHeight;
        int statusHeight = this.status == null ? 0 : lineHeight + 2;
        int linkHeight = this.fontRendererObj.FONT_HEIGHT + 4;

        int contentHeight = PANEL_TOP_PAD + this.fontRendererObj.FONT_HEIGHT + HEADING_GAP
                + bodyHeight + statusHeight + BODY_GAP
                + BUTTON_HEIGHT + BUTTON_GAP + BUTTON_HEIGHT + BUTTON_GAP + BUTTON_HEIGHT
                + LINK_GAP + linkHeight + PANEL_BOTTOM_PAD;

        // 面板至少要装得下最宽的按钮（200px），否则窄窗口下按钮会横着穿出底板。
        int panelWidth = Math.min(this.textWidth + 40, Math.max(80, this.width - 8));
        panelWidth = Math.max(panelWidth, Math.min(Math.max(80, this.width - 8), WIDE_BUTTON_WIDTH + 16));
        int panelHeight = Math.min(Math.max(40, this.height - 8), contentHeight);

        this.panelLeft = this.width / 2 - panelWidth / 2;
        this.panelRight = this.panelLeft + panelWidth;
        this.panelTop = this.height / 2 - panelHeight / 2;
        this.panelBottom = this.panelTop + panelHeight;

        this.headingY = this.panelTop + PANEL_TOP_PAD;
        this.bodyTop = this.headingY + this.fontRendererObj.FONT_HEIGHT + HEADING_GAP;
        this.statusTop = this.bodyTop + bodyHeight;

        this.linkTop = this.panelBottom - PANEL_BOTTOM_PAD - linkHeight;
        this.laterButtonTop = this.linkTop - LINK_GAP - BUTTON_HEIGHT;
        this.copyButtonTop = this.laterButtonTop - BUTTON_GAP - BUTTON_HEIGHT;
        this.downloadButtonTop = this.copyButtonTop - BUTTON_GAP - BUTTON_HEIGHT;

        int skipWidth = showSkipLink() ? this.fontRendererObj.getStringWidth(SKIP_LINK) : 0;
        int neverWidth = this.fontRendererObj.getStringWidth(NEVER_LINK);
        int spacing = showSkipLink() ? LINK_SPACING : 0;
        int linksLeft = this.width / 2 - (skipWidth + spacing + neverWidth) / 2;
        this.skipLeft = linksLeft;
        this.skipRight = linksLeft + skipWidth;
        this.neverLeft = this.skipRight + spacing;
        this.neverRight = this.neverLeft + neverWidth;

        if (this.downloadButton != null) {
            this.downloadButton.xPosition = this.width / 2 - WIDE_BUTTON_WIDTH / 2;
            this.downloadButton.yPosition = this.downloadButtonTop;
        }
        if (this.copyButton != null) {
            this.copyButton.xPosition = this.width / 2 - WIDE_BUTTON_WIDTH / 2;
            this.copyButton.yPosition = this.copyButtonTop;
        }
        if (this.laterButton != null) {
            this.laterButton.xPosition = this.width / 2 - NARROW_BUTTON_WIDTH / 2;
            this.laterButton.yPosition = this.laterButtonTop;
        }
    }

    /** 关键更新不给「跳过这个版本」，只留「之后再说」与「不再检查更新」。 */
    private static boolean showSkipLink() {
        return !UpdateChecker.isCritical();
    }

    private String buildBody() {
        StringBuilder body = new StringBuilder();
        body.append("当前 ").append(UpdateChecker.currentVersion())
                .append("，最新 ").append(UpdateChecker.latestVersion())
                .append("（").append(UpdateChecker.channel()).append("）。");
        String notes = UpdateChecker.releaseNotes();
        if (notes != null && !notes.isEmpty()) {
            body.append(" ").append(notes);
        }
        return body.toString();
    }

    private List<String> wrap(String text) {
        List<String> lines;
        try {
            lines = this.fontRendererObj.listFormattedStringToWidth(text, this.textWidth);
        } catch (Throwable ignored) {
            // 换行只是排版：出问题也要让用户看得到出口按钮。
            lines = null;
        }
        if (lines == null || lines.isEmpty()) {
            lines = new ArrayList<String>();
            lines.add(text);
        }
        return lines;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        // 每帧重算：出现「已复制」那一行反馈时面板要变高，按钮也要跟着往下走。
        layout();

        drawRect(this.panelLeft, this.panelTop, this.panelRight, this.panelBottom, 0xC0101010);
        drawRect(this.panelLeft, this.panelTop, this.panelRight, this.panelTop + 1, 0x40FFFFFF);
        drawRect(this.panelLeft, this.panelBottom - 1, this.panelRight, this.panelBottom, 0x40FFFFFF);

        drawCenteredString(this.fontRendererObj, HEADING, this.width / 2, this.headingY, 0xFFFFFF);

        int lineHeight = this.fontRendererObj.FONT_HEIGHT + LINE_GAP;
        int y = this.bodyTop;
        for (int index = 0; index < this.bodyLines.size(); index++) {
            drawCenteredString(this.fontRendererObj, this.bodyLines.get(index), this.width / 2, y, 0xC8C8C8);
            y += lineHeight;
        }

        if (this.status != null) {
            drawCenteredString(this.fontRendererObj, this.status, this.width / 2, this.statusTop + 2, 0xFFB74D);
        }

        if (showSkipLink()) {
            drawLink(SKIP_LINK, this.skipLeft, this.skipRight, isOverSkip(mouseX, mouseY));
        }
        drawLink(NEVER_LINK, this.neverLeft, this.neverRight, isOverNever(mouseX, mouseY));

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /** 原版没有「文字链接」控件：悬停时补一条下划线表示这一行可以点。 */
    private void drawLink(String text, int left, int right, boolean hovering) {
        drawCenteredString(this.fontRendererObj, text, (left + right) / 2,
                this.linkTop + 2, hovering ? 0xFFFFA0 : 0x909090);
        if (hovering) {
            drawRect(left, this.linkTop + 2 + this.fontRendererObj.FONT_HEIGHT,
                    right, this.linkTop + 3 + this.fontRendererObj.FONT_HEIGHT, 0xFFFFFFA0);
        }
    }

    private boolean isOverSkip(int mouseX, int mouseY) {
        return showSkipLink() && isOverLinkRow(mouseX, mouseY, this.skipLeft, this.skipRight);
    }

    private boolean isOverNever(int mouseX, int mouseY) {
        return isOverLinkRow(mouseX, mouseY, this.neverLeft, this.neverRight);
    }

    private boolean isOverLinkRow(int mouseX, int mouseY, int left, int right) {
        int linkHeight = this.fontRendererObj.FONT_HEIGHT + 4;
        return mouseX >= left && mouseX <= right
                && mouseY >= this.linkTop && mouseY <= this.linkTop + linkHeight;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton == 0 && isOverSkip(mouseX, mouseY)) {
            playClick();
            UpdateChecker.skipCurrentVersion();
            dismiss();
            return;
        }
        if (mouseButton == 0 && isOverNever(mouseX, mouseY)) {
            playClick();
            UpdateChecker.disable();
            dismiss();
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    /** 原版按钮自己会发这声，手写的文字链接得补一次，否则点上去毫无反馈。 */
    private void playClick() {
        this.mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == BUTTON_DOWNLOAD) {
            if (openDownloadPage()) {
                dismiss();
            } else {
                // 打不开浏览器（无桌面环境 / 被沙箱拦住）时把地址塞进剪贴板，别让用户照着抄。
                copyDownloadPage("打不开浏览器喵…");
            }
            return;
        }
        if (button.id == BUTTON_COPY) {
            copyDownloadPage("");
            return;
        }
        if (button.id == BUTTON_LATER) {
            dismiss();
        }
    }

    private void copyDownloadPage(String prefix) {
        String page = UpdateChecker.downloadPage();
        // setClipboardString 走的是原版 KeyboardHandler（GLFW 剪贴板），headless 下也有效。
        setClipboardString(page);
        this.status = prefix + "地址已复制到剪贴板：" + page;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        // 必须覆盖：原版的 ESC 是把界面设成 null，在主菜单上那会把主菜单一起关掉、剩一个黑屏。
        if (keyCode == Keyboard.KEY_ESCAPE) {
            dismiss();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void dismiss() {
        this.mc.setScreen(this.parent);
    }

    /**
     * 打开下载页。
     *
     * <p>与 {@link GuiFfmpegNotice} 同一条路：先反射 {@code java.awt.Desktop}（反射是为了不在错误的
     * 时机把 AWT 类加载进来），失败退到 LWJGL 的 {@code Sys.openURL}，再失败由调用方退到剪贴板。
     * 地址一定在 {@code UpdateChecker} 的域名白名单里，不会被清单牵去任意站点。</p>
     */
    private boolean openDownloadPage() {
        String page = UpdateChecker.downloadPage();
        if (!UpdateChecker.isAllowedPage(page)) {
            return false;
        }
        try {
            Class<?> desktopClass = Class.forName("java.awt.Desktop");
            Object desktop = desktopClass.getMethod("getDesktop").invoke(null);
            desktopClass.getMethod("browse", URI.class).invoke(desktop, new URI(page));
            return true;
        } catch (Throwable ignored) {
        }
        try {
            return com.muoniumplayer.compat.lwjgl2.Sys.openURL(page);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
