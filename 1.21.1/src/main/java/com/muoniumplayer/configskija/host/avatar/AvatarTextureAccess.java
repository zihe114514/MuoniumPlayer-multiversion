package com.muoniumplayer.configskija.host.avatar;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;

/** Borrowed image: copy on client before the task returns; never close or retain the game's live image. */
public final class AvatarTextureAccess {
    private AvatarTextureAccess() {}

    public static AvatarPixels copy(AbstractTexture texture) {
        if (!(texture instanceof DynamicTexture dynamic)) return null;
        return copyImage(dynamic.getPixels());
    }

    /** Client-thread-only read of a borrowed image; exposed separately for native pixel contract tests. */
    public static AvatarPixels copyImage(NativeImage pixels) {
        if (pixels == null || pixels.getWidth() < 48 || pixels.getHeight() < 16) return null;
        int[] face = new int[64], hat = new int[64];
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++) {
                face[y * 8 + x] = AvatarPixels.fromAbgr(pixels.getPixelRGBA(8 + x, 8 + y));
                hat[y * 8 + x] = AvatarPixels.fromAbgr(pixels.getPixelRGBA(40 + x, 8 + y));
            }
        return new AvatarPixels(face, hat);
    }
}
