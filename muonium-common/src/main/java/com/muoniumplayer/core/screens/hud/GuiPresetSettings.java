package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.compat.mc.GuiScreen;
import com.muoniumplayer.core.preset.PresetService;
import com.muoniumplayer.core.preset.ui.PresetPanelSession;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.screens.ncm.WindowAdaptiveScale;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.render.skija.SkijaGfx;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.function.Consumer;

/** Version-neutral thin Screen host. Minecraft screen/parent APIs are supplied by HudSettingsModel. */
public final class GuiPresetSettings extends GuiScreen {
    private final Runnable returnToParent;
    private final PresetPanelSession session;
    private final PresetSettingsPanel panel;
    private boolean built, closed, returned, removed;

    public GuiPresetSettings(Runnable returnToParent, PresetSettingsActions.Mode mode) {
        this.returnToParent = returnToParent;
        session = new PresetPanelSession(PresetService.get(), new PresetPanelSession.Clipboard() {
            public String get() { return GuiScreen.getClipboardString(); }
            public void set(String text) { GuiScreen.setClipboardString(text); }
        });
        panel = new PresetSettingsPanel(session, mode);
        session.setHost(new PresetPanelSession.Host() {
            public void chooseFile(Consumer<File> result) { choosePresetFile(result); }
            public boolean openFolder(File directory) { return openPresetFolder(directory); }
        });
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        if (built) return;
        built = true;
        panel.onInit();
    }

    @Override
    public void updateScreen() { if (!closed) panel.onTickReceived(); }

    @Override
    public void onGuiClosed() {
        removed = true;
        closed = true;
        panel.dispose();
        session.setHost(null);
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (closed || !SkijaGfx.active()) return;
        RenderSystem.refreshResolution();
        WindowAdaptiveScale.beginForced();
        try {
            double sx = WindowAdaptiveScale.correctionX(), sy = WindowAdaptiveScale.correctionY();
            SkijaGfx.pushMatrix();
            SkijaGfx.scale(sx, sy);
            try {
                panel.setBounds(0, 0, RenderSystem.getWidth(), RenderSystem.getHeight());
                panel.renderWidget(mouseX / sx, mouseY / sy, 0);
                int wheel = Mouse.getDWheel();
                if (wheel != 0 && panel.isOpen()) panel.onDWheelReceived(mouseX / sx, mouseY / sy, wheel);
                if (panel.shouldClose()) dismiss();
            } finally {
                SkijaGfx.popMatrix();
            }
        } finally {
            WindowAdaptiveScale.end();
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (closed) return;
        RenderSystem.refreshResolution();
        WindowAdaptiveScale.beginForced();
        try {
            double sx = WindowAdaptiveScale.correctionX(), sy = WindowAdaptiveScale.correctionY();
            panel.onMouseClickReceived(mouseX / sx, mouseY / sy, mouseButton);
        } finally {
            WindowAdaptiveScale.end();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (!closed) panel.onKeyTypedReceived(typedChar, keyCode);
    }

    private void dismiss() {
        if (returned || closed) return;
        returned = true;
        closed = true;
        MultiThreadingUtil.runOnMainThread(() -> {
            if (removed) return;
            if (returnToParent != null) returnToParent.run();
            else closeScreen();
        });
    }

    private void choosePresetFile(Consumer<File> result) {
        int ticket = panel.navigationTicket();
        File directory = PresetService.get().repository().userDir();
        MultiThreadingUtil.runAsync(() -> {
            File chosen = null;
            String error = null;
            try {
                // Same optional tinyfd signature used by the existing font picker, without a loader dependency.
                Class<?> dialogs = Class.forName("org.lwjgl.util.tinyfd.TinyFileDialogs");
                Class<?> pointerBuffer = Class.forName("org.lwjgl.PointerBuffer");
                Object picked = dialogs.getMethod("tinyfd_openFileDialog", CharSequence.class,
                        CharSequence.class, pointerBuffer, CharSequence.class, boolean.class)
                        .invoke(null, "选择模板预设文件", directory.getAbsolutePath() + File.separator,
                                null, "模板预设 (*.mpreset.json)", false);
                if (picked != null && !picked.toString().trim().isEmpty()) chosen = new File(picked.toString());
            } catch (Throwable failure) {
                error = "系统文件选择器不可用，请粘贴分享码或把文件放进预设目录后刷新。";
            }
            final File selected = chosen;
            final String problem = error;
            MultiThreadingUtil.runOnMainThread(() -> {
                if (closed || !panel.isOpen()) return;
                if (!panel.acceptsCallback(ticket)) {
                    // Clear the old picker state, but never navigate a different page to an old result.
                    result.accept(null);
                    return;
                }
                result.accept(selected);
                if (problem != null) panel.reportHostFailure(problem);
                panel.afterHostAction();
            });
        });
    }

    private boolean openPresetFolder(File directory) {
        if (directory == null || closed || !panel.isOpen() || !java.awt.Desktop.isDesktopSupported()) return false;
        int ticket = panel.navigationTicket();
        MultiThreadingUtil.runAsync(() -> {
            try {
                Files.createDirectories(directory.toPath());
                java.awt.Desktop.getDesktop().open(directory);
            } catch (Throwable failure) {
                MultiThreadingUtil.runOnMainThread(() -> {
                    if (!closed && panel.acceptsCallback(ticket)) panel.reportHostFailure("打不开预设目录，请手动打开 config/muonium/presets/。");
                });
            }
        });
        return true;
    }
}
