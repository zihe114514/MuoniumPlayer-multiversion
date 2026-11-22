package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.rendering.Image;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.texture.ITextureObject;
import com.muoniumplayer.core.rendering.ui.widgets.ThemedTextureIconWidget;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.render.skija.SkijaGfx;

public final class PlatformAppIcons {
    public static final Location QQ_MUSIC = Location.of("/muonium/textures/brands/qqmusic.jpg");
    public static final Location BODIAN = Location.of("/muonium/textures/brands/bodian.jpg");

    private PlatformAppIcons() { }

    public static Location location(MusicPlatform platform) {
        if (platform == MusicPlatform.QQ) return QQ_MUSIC;
        if (platform == MusicPlatform.BODIAN) return BODIAN;
        return null;
    }

    public static ThemedTextureIconWidget widget(MusicPlatform platform, String fallback,
                                                  CFontRenderer font, double x, double y, double size) {
        Location icon = location(platform);
        if (icon == null) return null;
        ThemedTextureIconWidget result = new ThemedTextureIconWidget(icon, fallback, font,
                x, y, size, size);
        result.setClickable(false);
        result.setShouldOverrideMouseCursor(false);
        result.setColor(0xFFFFFFFF);
        return result;
    }

    public static boolean drawCentered(MusicPlatform platform, double centerX, double centerY,
                                       double size, int argb) {
        Location icon = location(platform);
        if (icon == null) return false;
        TextureManager manager = TextureManager.getInstance();
        ITextureObject texture = manager.getTexture(icon);
        if (texture == null) {
            PlayerIconAssets.requestLoad(icon);
            return false;
        }
        manager.bindTexture(icon);
        RenderSystem.linearFilter();
        SkijaGfx.setColor(argb);
        try {
            Image.draw(texture, centerX - size * .5, centerY - size * .5,
                    size, size, Image.Type.NoColor);
        } finally {
            SkijaGfx.resetColor();
        }
        return true;
    }
}
