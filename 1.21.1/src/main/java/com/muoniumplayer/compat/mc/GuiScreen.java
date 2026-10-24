package com.muoniumplayer.compat.mc;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.core.utils.KeyboardUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 1.8.9 {@code net.minecraft.client.gui.GuiScreen} 的兼容替身。
 *
 * <p>只暴露本项目实际用到的那部分生命周期与工具方法，方法名/签名/protected 修饰
 * 全部与 1.8.9 一致，因此 {@code NCMScreen} / {@code CoverflowOverlay} /
 * {@code GuiHudEditor(Pro)} / {@code GuiFfmpegNotice} 只需要改 import。
 *
 * <p>键盘事件的形状差异是移植里唯一需要留意的地方：1.8.9 一次
 * {@code keyTyped(char, int)} 同时给出字符和键码；1.20.1 拆成
 * {@code keyPressed(glfwKey, scanCode, mods)} 和 {@code charTyped(char, mods)}。
 * 这里把两者都转发到 {@code keyTyped}，键码走 {@link Keyboard#fromGlfw(int)} 翻回
 * LWJGL2 码值（全项目的 {@code Keyboard.KEY_*} 比较依赖这套码值），字符事件的键码传 0。
 */
public abstract class GuiScreen extends Screen {

    /** 1.8.9 里 {@code mc} 就是 {@code Minecraft.getMinecraft()}；此处等价物是客户端单例。 */
    protected final Minecraft mc = Minecraft.getInstance();

    /** 1.8.9 的 {@code fontRendererObj}。 */
    protected final FontRenderer fontRendererObj = FontRenderer.vanilla();

    /** 1.8.9 的按钮列表；{@code initGui()} 里 clear + add，基类负责渲染与点击派发。 */
    protected final List<GuiButton> buttonList = new ArrayList<>();

    /** 当前帧的 GuiGraphics，仅在 {@link #render} 期间非 null。 */
    protected GuiGraphics currentDrawContext;

    protected GuiScreen() {
        super(Component.empty());
    }

    // ---------------- 1.8.9 生命周期钩子（子类覆写这些） ----------------

    public void initGui() {
    }

    public void onGuiClosed() {
    }

    public void updateScreen() {
    }

    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // 1.8.9 的 GuiScreen.drawScreen 负责画按钮；子类在自己的绘制末尾 super.drawScreen(...)。
        GuiGraphics context = this.currentDrawContext;
        if (context == null) {
            return;
        }
        for (GuiButton button : buttonList) {
            button.drawButton(context, mouseX, mouseY, partialTicks);
        }
    }

    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        // 1.8.9 默认行为：ESC 关闭界面。
        if (keyCode == Keyboard.KEY_ESCAPE) {
            closeScreen();
        }
    }

    /**
     * 关闭当前界面（1.8.9 的 {@code displayGuiScreen(null)} 等价物）。抽成方法是因为
     * 各版本的调用形态不同（26.x 是 {@code mc.gui.setScreen}，更早是 {@code mc.setScreen}），
     * 跨版本共享的界面代码只应调这一个版本中立的名字。
     */
    public void closeScreen() {
        mc.setScreen(null);
    }

    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        // 1.8.9 默认行为：左键命中按钮则播放点击音并派发 actionPerformed。
        if (mouseButton != 0) {
            return;
        }
        for (GuiButton button : new ArrayList<>(buttonList)) {
            if (button.mousePressed(mouseX, mouseY)) {
                button.playDownSound(mc.getSoundManager());
                dispatchActionPerformed(button);
                return;
            }
        }
    }

    protected void mouseReleased(int mouseX, int mouseY, int state) {
    }

    protected void actionPerformed(GuiButton button) throws IOException {
    }

    public boolean doesGuiPauseGame() {
        return true;
    }

    // ---------------- 工具方法（1.8.9 的 Gui / GuiScreen 静态或实例方法） ----------------

    /**
     * 1.8.9 的半透明渐变背景。
     *
     * <p>1.21.1 的 {@code Screen#renderBackground} 比 1.20.1 多了 {@code mouseX/mouseY/delta}
     * 三个形参（1.20.2 起的签名）。这个入口是 1.8.9 语义的「画默认背景」，本身拿不到鼠标位置；
     * 传 0/0/0 不影响结果 —— 原版实现只按「有没有世界」在 renderInGameBackground 与
     * 全景背景贴图之间二选一，这三个值一个都没用到（它们只是留给子类做悬停/动画背景）。
     */
    public void drawDefaultBackground() {
        if (currentDrawContext != null) {
            renderBackground(currentDrawContext, 0, 0, 0.0F);
        }
    }

    /** 1.8.9 的 {@code Gui#drawCenteredString}：以 x 为中心、带阴影。 */
    public void drawCenteredString(FontRenderer fontRenderer, String text, int x, int y, int color) {
        if (fontRenderer == null || text == null) {
            return;
        }
        fontRenderer.drawStringWithShadow(text, x - fontRenderer.getStringWidth(text) / 2.0F, y, color);
    }

    /** 1.8.9 的 {@code Gui#drawRect}。 */
    public static void drawRect(int left, int top, int right, int bottom, int color) {
        Gui.drawRect(left, top, right, bottom, color);
    }

    /** 1.8.9 的 {@code GuiScreen#setClipboardString}。 */
    public static void setClipboardString(String text) {
        KeyboardUtils.setClipboardString(text);
    }

    /** 1.8.9 的 {@code GuiScreen#getClipboardString}。 */
    public static String getClipboardString() {
        return KeyboardUtils.getClipboardString();
    }

    void dispatchActionPerformed(GuiButton button) {
        try {
            actionPerformed(button);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------------- 1.20.1 -> 1.8.9 桥接 ----------------

    @Override
    protected void init() {
        buttonList.clear();
        initGui();
        for (GuiButton button : buttonList) {
            button.attach(this);
            button.syncFromLegacyFields();
        }
    }

    @Override
    public void removed() {
        onGuiClosed();
    }

    @Override
    public void tick() {
        updateScreen();
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        this.currentDrawContext = context;
        fontRendererObj.beginFrame(context);
        // 本 mod 的界面全部由 Skia 绘制，所以这里要开一个 Skia 帧。
        // beginFrame 返回 null 表示渲染层这一帧不可用（还没初始化 / 已停用），
        // 那就整段跳过而不是画一半。
        //
        // endFrame 必须在 finally 里：它同时负责把离屏内容合成回游戏画面、
        // 以及把 GL 状态交还给 Minecraft。漏掉后者会让 Minecraft 随后的绘制全部错乱。
        boolean skijaFrame = com.muoniumplayer.render.skija.SkijaHost.beginFrame() != null;
        try {
            for (GuiButton button : buttonList) {
                button.attach(this);
            }
            if (skijaFrame) {
                drawScreen(mouseX, mouseY, delta);
            }
        } finally {
            if (skijaFrame) {
                com.muoniumplayer.render.skija.SkijaHost.endFrame();
            }
            fontRendererObj.endFrame();
            this.currentDrawContext = null;
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        try {
            keyTyped('\0', Keyboard.fromGlfw(keyCode));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return true;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        try {
            keyTyped(chr, 0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        try {
            mouseClicked((int) mouseX, (int) mouseY, button);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        mouseReleased((int) mouseX, (int) mouseY, button);
        return true;
    }

    // 1.20.1 是 mouseScrolled(x, y, amount)；1.21.1 拆成水平/垂直两个分量。
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // 项目通过 Mouse.getDWheel() 轮询滚轮；GLFW 回调链已在 Mouse 里挂好，
        // 这里再补一次会重复计数，故只做拦截返回，不再累加。
        return true;
    }

    /** 1.8.9 的 ESC 由 {@link #keyTyped} 自己处理，禁用 1.20.1 的自动关闭以免行为翻倍。 */
    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return doesGuiPauseGame();
    }
}
