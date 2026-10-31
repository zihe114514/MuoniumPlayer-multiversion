package com.muoniumplayer.core.lyrics3d;

import org.joml.Matrix4f;

/**
 * 辉光 mask 的字形 quad 采集缓冲（树侧直画端持有）。
 *
 * <p>辉光后处理第一步是把「字的形状」画进离屏 mask。直画端帧头把缓冲交给宿主
 * （{@code WorldLyricsRenderer.Host#glowMaskTarget}），本帧 drawSmoothGlyph 发的
 * 字形 quad 逐个双写进来 —— 同一份几何、同一绕字中心变换，只丢 UV/纹理，
 * alpha 单记（入场淡入的语义随本体进 bloom 能量）。present/HUD 端把这份缓冲以
 * 平色画进 mask FBO → bloom → 加色垫底，再画正常采集/提交的清晰本体；
 * mask 与本体出自同一次逐字遍历，天然逐帧对齐。</p>
 *
 * <p>只收字形：特效自带柔边（进 bloom 双重加亮）、阴影是负贡献（挖暗 mask），
 * 均不进。顶点 9 float 与 26.x present 缓冲同构（pos3 + rgba4 + uv2），平色
 * RGB=白、alpha=字形 alpha；RGB 通道在 mask 着色器里按「能量」读。</p>
 */
public final class MaskQuadCollector {

    /** 单帧 quad 容量（一行 ~60 字，余量充足）。 */
    private static final int MAX_QUADS = 1024;
    private static final int FLOATS_PER_VERTEX = 9;
    private static final int FLOATS_PER_QUAD = FLOATS_PER_VERTEX * 4;

    private final float[] floats = new float[MAX_QUADS * FLOATS_PER_QUAD];
    private int quadCount;

    /** 帧头清空（渲染层在配置辉光后调）。 */
    public void beginFrame() {
        quadCount = 0;
    }

    public int quadCount() {
        return quadCount;
    }

    /**
     * 记一个字形 quad：四角（公告牌行内坐标）先绕 (cx,cy) 旋转/缩放，
     * 再进 billboard 矩阵，落到直画端可直接使用的最终空间。
     *
     * @param aMask 字形顶点 alpha 的 8bit（<<24 已移位），0~255
     */
    public void addQuad(Matrix4f mat, float x0, float y0, float x1, float y1,
                        float x2, float y2, float x3, float y3,
                        float cx, float cy, float cos, float sin, float s,
                        int aMask) {
        if (quadCount >= MAX_QUADS) return;
        float a = ((aMask >>> 24) & 0xFF) / 255f;
        float[] f = floats;
        int o = quadCount * FLOATS_PER_QUAD;
        put(f, o, mat, x0, y0, cx, cy, cos, sin, s, a);
        put(f, o + FLOATS_PER_QUAD, mat, x1, y1, cx, cy, cos, sin, s, a);
        put(f, o + FLOATS_PER_QUAD * 2, mat, x2, y2, cx, cy, cos, sin, s, a);
        put(f, o + FLOATS_PER_QUAD * 3, mat, x3, y3, cx, cy, cos, sin, s, a);
        quadCount++;
    }

    /** 四角展开式（左上/左下/右下/右上已按序传入时用）。 */
    public void addQuadCorners(Matrix4f mat,
                               float ax, float ay, float bx, float by,
                               float gx, float gy, float dx, float dy,
                               int aMask) {
        if (quadCount >= MAX_QUADS) return;
        float a = ((aMask >>> 24) & 0xFF) / 255f;
        float[] f = floats;
        int o = quadCount * FLOATS_PER_QUAD;
        putDirect(f, o, mat, ax, ay, a);
        putDirect(f, o + FLOATS_PER_QUAD, mat, bx, by, a);
        putDirect(f, o + FLOATS_PER_QUAD * 2, mat, gx, gy, a);
        putDirect(f, o + FLOATS_PER_QUAD * 3, mat, dx, dy, a);
        quadCount++;
    }

    private static void put(float[] f, int o, Matrix4f mat, float px, float py,
                            float cx, float cy, float cos, float sin, float s, float a) {
        float vx = px, vy = py;
        if (cos != 1f || sin != 0f || s != 1f) {
            float ox = px - cx, oy = py - cy;
            vx = cx + (ox * cos - oy * sin) * s;
            vy = cy + (ox * sin + oy * cos) * s;
        }
        putDirect(f, o, mat, vx, vy, a);
    }

    private static void putDirect(float[] f, int o, Matrix4f mat, float x, float y, float a) {
        f[o] = mat.m00() * x + mat.m10() * y + mat.m30();
        f[o + 1] = mat.m01() * x + mat.m11() * y + mat.m31();
        f[o + 2] = mat.m02() * x + mat.m12() * y + mat.m32();
        f[o + 3] = 1f;
        f[o + 4] = 1f;
        f[o + 5] = 1f;
        f[o + 6] = a;
        f[o + 7] = 0f;
        f[o + 8] = 0f;
    }

    /** 平铺顶点数据（quadCount × 4 × 9 float；落屏端直读）。 */
    public float[] floats() {
        return floats;
    }
}
