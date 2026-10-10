package com.muoniumplayer.api.impl;

import com.muoniumplayer.compat.mc.ScaledResolution;
import com.muoniumplayer.api.interfaces.render.WindowResolution;

public class WindowResolutionImpl implements WindowResolution {

    private final ScaledResolution resolution;

    public WindowResolutionImpl() {
        // 1.8.9: new ScaledResolution(Minecraft.getMinecraft())
        this.resolution = new ScaledResolution();
    }

    @Override
    public int getScaleFactor() {
        return resolution.getScaleFactor();
    }

    @Override
    public int getWidth() {
        return resolution.getScaledWidth();
    }

    @Override
    public int getHeight() {
        return resolution.getScaledHeight();
    }
}
