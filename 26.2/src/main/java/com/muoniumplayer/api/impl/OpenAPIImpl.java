package com.muoniumplayer.api.impl;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import com.muoniumplayer.api.OpenAPI;
import com.muoniumplayer.api.features.ExtensionModule;
import com.muoniumplayer.api.features.ExtensionScreen;
import com.muoniumplayer.api.features.ExtensionWidget;
import com.muoniumplayer.api.interfaces.EventHandler;
import com.muoniumplayer.api.interfaces.modules.values.ValueManager;
import com.muoniumplayer.api.interfaces.render.FontUtil;
import com.muoniumplayer.api.interfaces.render.GLStateManager;
import com.muoniumplayer.api.interfaces.render.RenderUtil;
import com.muoniumplayer.api.interfaces.render.ShaderUtil;

import java.util.Collections;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

public class OpenAPIImpl extends OpenAPI {

    // 用 CopyOnWriteArrayList 而不是 ArrayList：
    // 实测 1.20.1 上首次 tick 会在遍历 eventHandlers 的过程中触发 RenderSystem 等类的静态初始化，
    // 而那些静态块里会调用 registerEvent()，于是在 Collections.unmodifiableList 视图上迭代时抛
    // ConcurrentModificationException（crash-2026-08-26_01.23.02，MuoniumPlayerMod:141）。
    // 1.8.9 只是碰巧类加载顺序不同没踩到，属同一个潜在缺陷。
    // COW 的迭代器是快照语义：本 tick 新注册的 handler 从下一 tick 开始参与，不改变任何既有行为。
    private final List<EventHandler> eventHandlers = new CopyOnWriteArrayList<>();
    private final List<ExtensionModule> modules = new CopyOnWriteArrayList<>();
    private final List<ExtensionWidget> widgets = new CopyOnWriteArrayList<>();

    private final GLStateManager glStateManager = new GLStateManagerImpl();
    private final ValueManager valueManager = new ValueManagerImpl();
    private final FontUtil fontUtil = new FontUtilImpl();
    private final ShaderUtil shaderUtil = new ShaderUtilImpl();
    private final RenderUtil renderUtil = new RenderUtilImpl();

    @Override
    public void registerEvent(EventHandler handler) {
        if (!eventHandlers.contains(handler)) {
            eventHandlers.add(handler);
        }
    }

    @Override
    public void unregisterEvent(EventHandler handler) {
        eventHandlers.remove(handler);
    }

    @Override
    public void registerFeature(Object feature) {
        if (feature instanceof ExtensionModule) {
            modules.add((ExtensionModule) feature);
        } else if (feature instanceof ExtensionWidget) {
            widgets.add((ExtensionWidget) feature);
        }
    }

    @Override
    public void displayScreen(ExtensionScreen screen) {
        Minecraft.getInstance().gui.setScreen(screen);
    }

    @Override
    public void printMessage(String message) {
        // 1.8.9: mc.ingameGUI.getChatGUI().printChatMessage(new ChatComponentText(message))
        // 26.2：聊天栏挂在 Minecraft#gui.hud 上（Yarn 的 inGameHud.getChatHud()）。
        // addMessage(...) 在 26.2 已变成 private 的四参重载（要带签名与消息来源）。
        // 对外只留三个入口，其中 addClientSystemMessage 正是「本地客户端产生的系统消息」，
        // 与本 mod 的用途一致；另两个（addServerSystemMessage / addPlayerMessage）
        // 会把消息标成来自服务端或某个玩家，语义不对。
        Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal(message));
    }

    @Override
    public ValueManager getValueManager() {
        return valueManager;
    }

    @Override
    public GLStateManager getGLStateManager() {
        return glStateManager;
    }

    @Override
    public ShaderUtil getShaderUtil() {
        return shaderUtil;
    }

    @Override
    public FontUtil getFontUtil() {
        return fontUtil;
    }

    @Override
    public RenderUtil getRenderUtil() {
        return renderUtil;
    }

    // ---- Forge 事件处理器在 S4/S5 会使用以下访问器 ----

    public List<EventHandler> getEventHandlers() {
        return Collections.unmodifiableList(eventHandlers);
    }

    public List<ExtensionModule> getModules() {
        return Collections.unmodifiableList(modules);
    }

    public List<ExtensionWidget> getWidgets() {
        return Collections.unmodifiableList(widgets);
    }
}
