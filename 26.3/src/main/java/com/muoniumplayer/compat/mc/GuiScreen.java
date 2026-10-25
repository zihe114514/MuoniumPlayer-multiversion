package com.muoniumplayer.compat.mc;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.core.utils.KeyboardUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
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
 * {@code keyTyped(char, int)} 同时给出字符和键码；原版拆成
 * {@code keyPressed(KeyEvent)} 和 {@code charTyped(CharacterEvent)}。
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

    /**
     * 当前帧的 GUI 采集器，仅在 {@link #extractRenderState} 期间非 null。
     *
     * <p>字段名保留 {@code currentDrawContext}：调用点（GuiFfmpegNotice 等）按这个名字读，
     * 改名只是把一次类型变更放大成一片无意义的改动。26.2 里它的类型是
     * {@link GuiGraphicsExtractor}——GUI 绘制在 26.x 是「往 render state 里追加」，
     * 不再是即时绘制。
     */
    protected GuiGraphicsExtractor currentDrawContext;

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
        GuiGraphicsExtractor context = this.currentDrawContext;
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
        mc.gui.setScreen(null);
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
     * <p>26.2 里这个入口叫 {@code Screen#extractBackground}，形参仍是
     * {@code mouseX/mouseY/delta}。这个方法是 1.8.9 语义的「画默认背景」，本身拿不到鼠标位置；
     * 传 0 与 0 delta 不影响结果，因为背景绘制不使用这三个值（它们只是为了让子类能做
     * 悬停/动画背景）。
     */
    public void drawDefaultBackground() {
        if (currentDrawContext != null) {
            extractBackground(currentDrawContext, 0, 0, 0.0F);
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

    // ---------------- 26.2 -> 1.8.9 桥接 ----------------

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
    public void added() {
        super.added();
        // 26.3 的 SDL 后端里，字符事件（SDL_TEXTINPUT → Screen#charTyped）只有在
        // SDL_StartTextInput 之后才会投递。原版靠 EditBox 聚焦时调
        // Minecraft#onTextInputFocusChange 开启，而本项目的输入框全是自绘的，
        // 没有原版控件替我们开——不开就一个字都打不进搜索栏/输入框。
        // 这里「界面开着就开」与 GLFW 时代的语义等价：那时 char 回调只要窗口
        // 聚焦就一直投递，并不看界面里有没有输入框。
        mc.textInputManager().startTextInput(this);
    }

    @Override
    public void removed() {
        onGuiClosed();
        // stopTextInput(owner) 内部有 owner 匹配检查（不是当前 owner 就不动），
        // 界面切换时不会误关下一个界面（或原版 EditBox）的文本输入。
        mc.textInputManager().stopTextInput(this);
    }

    @Override
    public void tick() {
        updateScreen();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        this.currentDrawContext = context;
        fontRendererObj.beginFrame(context);
        // 本 mod 的界面全部由 Skia 绘制，所以这里要开一个 Skia 帧。
        // beginFrame 返回 null 表示渲染层这一帧不可用（还没初始化 / 已停用），
        // 那就整段跳过而不是画一半。
        //
        // endFrame 必须在 finally 里：它负责把 Skia 排的活提交给 GPU、
        // 以及把 GL 状态交还给 Minecraft。漏掉后者会让 Minecraft 随后的绘制全部错乱。
        //
        // 合成不在这里做。26.2 的 Screen#extractRenderState 只是往 GuiRenderState 里追加 render state，
        // 此刻原版 GUI（含界面背景的模糊/暗化）还没画；离屏图由 GlSurfaceMixin 在 swapBuffers 前贴上去。
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

    // 26.2 把输入事件的散装形参打包成了记录类型：按键是 KeyEvent(key, scancode, modifiers)、
    // 字符是 CharacterEvent(codepoint)、鼠标是 MouseButtonEvent(x, y, MouseButtonInfo)。
    // 这一层的职责不变：拆包之后仍然转成 1.8.9 的 keyTyped(char,int) / mouseClicked(int,int,int)。

    @Override
    public boolean keyPressed(KeyEvent input) {
        try {
            keyTyped('\0', Keyboard.fromGlfw(input.key()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent input) {
        try {
            // codepoint 可能超出 char 范围（辅助平面）。1.8.9 的 keyTyped 只吃 char，
            // 这种字符在旧签名下本来就表达不了，直接丢掉而不是截断成乱码。
            int codepoint = input.codepoint();
            if (Character.isBmpCodePoint(codepoint)) {
                keyTyped((char) codepoint, 0);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick) {
        try {
            // click.button() 是 SDL 的按钮号（左 1 / 中 2 / 右 3），而本项目整套 UI 说
            // LWJGL2 的编号（左 0 / 右 1 / 中 2）。不换算的话左键会被当成「不是 0」，
            // 下面那句 if (mouseButton != 0) return; 会把每一次点击都吃掉。
            mouseClicked((int) click.x(), (int) click.y(), Mouse.fromSdlButton(click.button()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        mouseReleased((int) click.x(), (int) click.y(), Mouse.fromSdlButton(click.button()));
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // 项目通过 Mouse.getDWheel() 轮询滚轮；26.3 起滚轮由 MouseScrollMixin 投进累加器
        // （GLFW 时代是在 Mouse 里套回调链），这里再补一次会重复计数，故只做拦截返回。
        return true;
    }

    /** 1.8.9 的 ESC 由 {@link #keyTyped} 自己处理，禁用原版的自动关闭以免行为翻倍。 */
    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return doesGuiPauseGame();
    }
}
