package com.muoniumplayer.core.screens;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.compat.mc.GuiScreen;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.screens.ncm.AnnouncementOverlay;
import com.muoniumplayer.core.update.AnnouncementChecker;
import com.muoniumplayer.render.skija.SkijaGfx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

import java.io.IOException;

/**
 * 公告中心的宿主界面。
 *
 * <p>与 {@link GuiUpdateNotice} 同构，也只做四件事：开一帧 Skija（compat 的 {@link GuiScreen#render}
 * 已经在做）、每帧把窗口尺寸灌给浮层、转发鼠标/键盘、浮层收起后回到原来的界面。界面本体全部在
 * {@link AnnouncementOverlay}（模组的 Skija 渲染栈）。</p>
 *
 * <p>与更新提示的唯一区别是<b>滚轮要转发</b>：公告是条数不定的一批内容，窄窗口里必然放不下，
 * 浮层内部靠 {@code ScrollPanel} 整页滚动，而滚轮在这个项目里是轮询出来的（{@code Mouse.getDWheel()}
 * 读一次就清零），所以宿主不把它传进去，列表就永远只能看到开头几条。
 * 参照 {@code OnboardingScreen} 里独立渲染的账号浮层同一写法。</p>
 *
 * <p>ESC 必须自己接管：compat 基类的默认行为是 {@code setScreen(null)}，在主菜单上那会把主菜单
 * 一起关掉、剩一个黑屏。</p>
 */
public class GuiAnnouncements extends GuiScreen {

    /** 返回目标。主菜单弹出时是主菜单本身，游戏内弹出时是 {@code null}（即关闭界面）。 */
    private final net.minecraft.client.gui.screens.Screen parent;

    private final AnnouncementOverlay overlay = new AnnouncementOverlay();

    /** 窗口 resize 会让原版重新调用 {@code init()}，界面本体只能搭一次，否则子组件会越挂越多。 */
    private boolean overlayBuilt;

    public GuiAnnouncements(net.minecraft.client.gui.screens.Screen parent) {
        this.parent = parent;
    }

    /**
     * 打开公告窗，并把调用方所在那一屏记成返回目标。
     *
     * <p>三处入口（主菜单那颗按钮、播放器侧栏的图标、HUD 设置里的行）都走这里：只有宿主知道自己
     * 该回到哪一屏，而「关掉即整批已读」这条行为也因此只有一处实现。共享的 {@code OpenAPI#displayScreen}
     * 只收 {@code ExtensionScreen}，公告窗不是，所以这里直接用原版的 {@code setScreen}。</p>
     */
    public static void open(net.minecraft.client.gui.screens.Screen parent) {
        Minecraft.getInstance().gui.setScreen(new GuiAnnouncements(parent));
    }

    /**
     * 每 tick 调一次：条件满足就把公告窗顶到最前面。
     *
     * <p>与 {@link GuiUpdateNotice#tickAutoShow(Minecraft)} 同样用轮询而不是界面切换事件：
     * 公告是在后台线程上拉的，主菜单出现的那一刻往往还没回来。</p>
     *
     * <p>排在更新提示之后调用，所以 ffmpeg / 更新提示任一在屏幕上时这里读到的当前界面不是主菜单，
     * 本 tick 直接跳过 —— 三个提示天然排队，不会互相盖掉。</p>
     */
    public static void tickAutoShow(Minecraft mc) {
        if (mc == null) {
            return;
        }
        if (!AnnouncementChecker.shouldShow()) {
            return;
        }
        net.minecraft.client.gui.screens.Screen current = mc.gui.screen();
        boolean atMainMenu = current instanceof TitleScreen;
        boolean inGameIdle = current == null && mc.level != null && mc.player != null;
        if (!atMainMenu && !inGameIdle) {
            return;
        }
        AnnouncementChecker.markShownThisSession();
        mc.gui.setScreen(new GuiAnnouncements(atMainMenu ? current : null));
    }

    @Override
    public void initGui() {
        if (overlayBuilt) {
            return;
        }
        overlayBuilt = true;
        overlay.onInit();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // compat 的 render 只在 Skija 帧拿到 canvas 时才调 drawScreen，所以这里理论上一定在帧内；
        // 再判一次是为了让「渲染层这一帧不可用」的情况整段跳过，而不是画一半。
        if (!SkijaGfx.active()) {
            return;
        }
        // 提示显示期间不渲染 HUD，RenderSystem 的分辨率会停在上一次游戏帧；不刷新的话窗口 resize
        // 或改 GUI Scale 之后布局与鼠标映射都会错位。
        RenderSystem.refreshResolution();
        RenderSystem.FIXED_SCALE = false;
        overlay.setBounds(0, 0, RenderSystem.getWidth(), RenderSystem.getHeight());
        overlay.renderWidget(mouseX, mouseY, Mouse.getDWheel());
        if (overlay.shouldClose()) {
            dismiss();
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        // 悬停状态是上一帧渲染时算出来的，所以派发要放在渲染之后 —— 调用点本来就是这个顺序。
        overlay.onMouseClickReceived(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            overlay.handleEscape();
        }
    }

    /**
     * 原版自己还有关闭界面的路径（{@code onClose()} 是 ESC 的默认落点）。
     *
     * <p>这里必须走一遍 {@code dispose()}：已读记账挂在收起动作上，从哪条路离开都得记账，
     * 否则下次启动又会弹一次同样的公告。</p>
     */
    @Override
    public void onClose() {
        overlay.dispose();
        dismiss();
    }

    private void dismiss() {
        this.mc.gui.setScreen(this.parent);
    }
}
