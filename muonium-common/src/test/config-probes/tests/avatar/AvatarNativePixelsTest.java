package com.muoniumplayer.configskija.avatar;

import com.mojang.blaze3d.platform.NativeImage;
import com.muoniumplayer.configskija.host.avatar.*;

import java.lang.reflect.Method;

/** Actual per-loader NativeImage byte-order check. Never creates a DynamicTexture, GPU surface or game client. */
public final class AvatarNativePixelsTest {
    public static void main(String[] args) throws Exception {
        boolean old = args[0].equals("ABGR");
        Method set =
                NativeImage.class.getMethod(
                        old ? "setPixelRGBA" : "setPixel", int.class, int.class, int.class);
        Method get =
                NativeImage.class.getMethod(
                        old ? "getPixelRGBA" : "getPixel", int.class, int.class);
        AvatarPixels detached;
        try (NativeImage image = new NativeImage(64, 64, false)) {
            for (int y = 0; y < 8; y++)
                for (int x = 0; x < 8; x++) {
                    int face = 0xFF123456, hat = 0x80FF0000;
                    set.invoke(image, 8 + x, 8 + y, old ? AvatarPixels.fromAbgr(face) : face);
                    set.invoke(image, 40 + x, 8 + y, old ? AvatarPixels.fromAbgr(hat) : hat);
                }
            detached = AvatarTextureAccess.copyImage(image);
            if (detached == null) throw new AssertionError("Readable native image was not copied");
            int still = (Integer) get.invoke(image, 8, 8);
            if ((old ? AvatarPixels.fromAbgr(still) : still) != 0xFF123456)
                throw new AssertionError("Borrowed image closed or mutated");
        }
        int expected = AvatarPixels.composite(0x80FF0000, 0xFF123456);
        for (int pixel : detached.headArgb())
            if (pixel != expected)
                throw new AssertionError(
                        "Native pixel channels differ: " + Integer.toHexString(pixel));
        try (NativeImage tiny = new NativeImage(16, 16, false)) {
            if (AvatarTextureAccess.copyImage(tiny) != null)
                throw new AssertionError("Short image accepted");
        }
        if (AvatarTextureAccess.copy(null) != null)
            throw new AssertionError("Missing texture accepted");
        System.out.println("AvatarNativePixelsTest: " + args[0] + " native pixel contract passed");
    }
}
