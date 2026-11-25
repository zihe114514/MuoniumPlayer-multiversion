package com.muoniumplayer.configskija.host.avatar;


/** Detached 8x8 face and hat in ARGB. Borrowed NativeImage ownership never crosses this boundary. */
public final class AvatarPixels {
    private final int[] face, hat;

    public AvatarPixels(int[] face, int[] hat) {
        if (face.length != 64 || hat.length != 64)
            throw new IllegalArgumentException("A face and hat each need 64 pixels");
        this.face = face.clone();
        this.hat = hat.clone();
    }

    public int[] headArgb() {
        int[] result = new int[1024];
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++) {
                int index = y * 8 + x, color = composite(hat[index], face[index]);
                for (int dy = 0; dy < 4; dy++)
                    for (int dx = 0; dx < 4; dx++) result[(y * 4 + dy) * 32 + x * 4 + dx] = color;
            }
        return result;
    }

    /** Same integer source-over arithmetic as PlayerHeadBlit; channels are explicit ARGB. */
    public static int composite(int src, int dst) {
        int sa = src >>> 24;
        if (sa == 255) return src;
        if (sa == 0) return dst;
        int da = dst >>> 24;
        if (da == 0) return src;
        int outA = sa + da * (255 - sa) / 255;
        if (outA == 0) return 0;
        int out = outA << 24;
        for (int shift : new int[] {16, 8, 0}) {
            int s = src >>> shift & 255, d = dst >>> shift & 255;
            int value = (s * sa + d * da * (255 - sa) / 255) / outA;
            out |= value << shift;
        }
        return out;
    }

    public static int fromAbgr(int value) {
        return value & 0xFF00FF00 | (value & 255) << 16 | (value >>> 16 & 255);
    }
}
