package com.muoniumplayer.compat.chat;

import com.muoniumplayer.api.features.ExtensionScreen;
import com.muoniumplayer.chat.ChatClient;
import com.muoniumplayer.chat.ui.ChatView;
import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.render.skija.SkijaGfx;

/** Version-local Screen/input adapter for the version-neutral Skija ChatView. */
public final class ChatScreen extends ExtensionScreen {
    private final ChatView view = new ChatView();
    private boolean closing;

    @Override public boolean doesGuiPauseGame() { return false; }

    @Override public void initGui() {
        closing = false;
        RenderSystem.refreshResolution();
    }

    @Override public void updateScreen() { view.tick(); }

    @Override public void drawScreen(int mouseX, int mouseY) {
        if (!SkijaGfx.active()) return;
        RenderSystem.refreshResolution();
        view.render(mouseX, mouseY, Mouse.getDWheel());
    }

    @Override protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        view.click(mouseX, mouseY, mouseButton);
    }

    @Override protected void mouseReleased(int mouseX, int mouseY, int state) {
        view.release(state);
    }

    @Override protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            closeScreen();
            return;
        }
        if (keyCode == Keyboard.KEY_RETURN && view.submitComposer()) return;
        view.key(typedChar, keyCode);
    }

    @Override public void onGuiClosed() {
        if (closing) return;
        closing = true;
        view.close();
    }
}
