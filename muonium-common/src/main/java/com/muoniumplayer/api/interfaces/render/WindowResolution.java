package com.muoniumplayer.api.interfaces.render;

/**
 * 对应原项目 com.muoniumplayer.api.interfaces.render.WindowResolution。
 * 由 Minecraft 的 ScaledResolution 包装实现。
 */
public interface WindowResolution {

    int getScaleFactor();

    int getWidth();

    int getHeight();
}
