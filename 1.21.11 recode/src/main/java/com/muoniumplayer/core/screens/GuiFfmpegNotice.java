package com.muoniumplayer.core.screens;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.compat.mc.GuiScreen;
import com.muoniumplayer.core.ncm.music.FfmpegNotice;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.screens.ncm.FfmpegNoticeOverlay;
import com.muoniumplayer.render.skija.SkijaGfx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

import java.io.IOException;

/**
 * 「没装 ffmpeg」提示的宿主界面。
 *
 * <p>这个提示要在<b>主菜单</b>就弹出来，而主菜单上没有播放器界面可以承载浮层，所以这里用一个
 * 最薄的原版 {@code Screen} 当宿主，界面本体全部交给 {@link FfmpegNoticeOverlay}（它跑在模组的
 * Skija 渲染栈上，和播放器里的模态提示是同一套组件）。宿主只做四件事：</p>
 * <ol>
 *   <li>开一帧 Skija —— compat 的 {@link GuiScreen#render} 已经在做（它每帧
 *       {@code SkijaHost.beginFrame/endFrame}，合成由帧末的 mixin 完成）；</li>
 *   <li>每帧把窗口尺寸灌给浮层（窗口 resize / 改 GUI Scale 后布局与鼠标映射立即正确）；</li>
 *   <li>把 1.8.9 形状的鼠标/按键事件转给浮层；</li>
 *   <li>浮层收起后回到原来的界面。</li>
 * </ol>
 *
 * <p>这里以前是「原版 GuiScreen + GuiButton + GuiGraphics 画字」的实现，与播放器界面混用两套
 * 渲染栈：标题/正文的颜色常量不带 alpha，在 1.21 的原版文字链路里就是全透明，界面上只剩按钮
 * 文字（按钮走 ActiveTextCollector，颜色带 alpha）。现在整屏只走 Skija 一条链路。</p>
 */
public class GuiFfmpegNotice extends GuiScreen {

    /** 返回目标。主菜单弹出时是主菜单本身，游戏内弹出时是 {@code null}（即关闭界面）。 */
    private final net.minecraft.client.gui.screens.Screen parent;

    private final FfmpegNoticeOverlay overlay = new FfmpegNoticeOverlay();

    /** 窗口 resize 会让原版重新调用 {@code init()}，界面本体只能搭一次，否则子组件会越挂越多。 */
    private boolean overlayBuilt;

    public GuiFfmpegNotice(net.minecraft.client.gui.screens.Screen parent) {
        this.parent = parent;
    }

    /**
     * 每 tick 调一次：条件满足就把提示顶到最前面。
     *
     * <p>不用 {@code GuiOpenEvent} 换掉主菜单，因为 ffmpeg 探测是在后台线程上跑的，主菜单出现的那
     * 一刻结论往往还没出来——换 GUI 只有一次机会，轮询则是下一 tick 再问一次就行。</p>
     *
     * <p>两个触发点：主菜单（正常启动路径，也就是需求里的「游戏启动后」与「游戏主页面」），以及"已经在
     * 游戏里且没有打开任何界面"（直进存档/直连服务器的启动参数会跳过主菜单）。后者实际上只在主菜单
     * 没能弹的时候才会走到：弹过一次就标记本次启动已提示。</p>
     */
    public static void tickAutoShow(Minecraft mc) {
        if (mc == null) return;
        if (!FfmpegNotice.shouldShow()) return;

        net.minecraft.client.gui.screens.Screen current = mc.screen;
        boolean atMainMenu = current instanceof TitleScreen;
        boolean inGameIdle = current == null && mc.level != null && mc.player != null;
        if (!atMainMenu && !inGameIdle) return;

        FfmpegNotice.markShownThisSession();
        mc.setScreen(new GuiFfmpegNotice(atMainMenu ? current : null));
    }

    @Override
    public void initGui() {
        if (overlayBuilt) return;
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
        // 或改 GUI Scale 之后布局与鼠标映射都会错位（这是它自己在 javadoc 里写明的用法）。
        RenderSystem.refreshResolution();
        RenderSystem.FIXED_SCALE = false;
        overlay.setBounds(0, 0, RenderSystem.getWidth(), RenderSystem.getHeight());
        overlay.renderWidget(mouseX, mouseY, 0);
        if (overlay.shouldClose()) {
            dismiss();
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        // 悬停状态是上一帧渲染时算出来的，所以派发要放在渲染之后——调用点本来就是这个顺序。
        overlay.onMouseClickReceived(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        // 必须覆盖：compat 基类的 ESC 是 setScreen(null)，在主菜单上那会把主菜单一起关掉、剩一个黑屏。
        // 这里 ESC 等价于「我才不管呢喵」，回到原来的界面。
        if (keyCode == Keyboard.KEY_ESCAPE) {
            overlay.handleEscape();
        }
    }

    private void dismiss() {
        this.mc.setScreen(this.parent);
    }
}
