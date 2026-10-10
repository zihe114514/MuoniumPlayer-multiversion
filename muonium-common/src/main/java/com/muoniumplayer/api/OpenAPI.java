package com.muoniumplayer.api;

import com.muoniumplayer.api.features.ExtensionScreen;
import com.muoniumplayer.api.interfaces.EventHandler;
import com.muoniumplayer.api.interfaces.modules.values.ValueManager;
import com.muoniumplayer.api.interfaces.render.FontUtil;
import com.muoniumplayer.api.interfaces.render.GLStateManager;
import com.muoniumplayer.api.interfaces.render.RenderUtil;
import com.muoniumplayer.api.interfaces.render.ShaderUtil;

/**
 * Forge 1.8.9 适配层：对应原项目 com.muoniumplayer.api.OpenAPI 的只读门面。
 * 由 {@code MuoniumPlayerMod} 在初始化时注入具体实现。
 */
public abstract class OpenAPI {

    private static OpenAPI instance;

    public static OpenAPI getInstance() {
        return instance;
    }

    public static void setInstance(OpenAPI api) {
        instance = api;
    }

    public abstract void registerEvent(EventHandler handler);

    /** 注销一个事件处理器。 */
    public abstract void unregisterEvent(EventHandler handler);

    /** 注册一个 feature（ExtensionModule 或 ExtensionWidget）。 */
    public abstract void registerFeature(Object feature);

    /** 打开/关闭一个 ExtensionScreen（null 表示关闭当前屏幕）。 */
    public abstract void displayScreen(ExtensionScreen screen);

    /** 向聊天栏打印一条带格式的消息。 */
    public abstract void printMessage(String message);

    public abstract ValueManager getValueManager();

    public abstract GLStateManager getGLStateManager();

    public abstract ShaderUtil getShaderUtil();

    public abstract FontUtil getFontUtil();

    public abstract RenderUtil getRenderUtil();
}
