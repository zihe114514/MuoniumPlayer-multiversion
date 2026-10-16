package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.core.screens.ncm.LyricLine;

import java.util.List;

/**
 *
 * <p>与宿主的契约（树侧 WorldBillboard 实现）：</p>
 * <ul>
 *       {@code ModernWorld.push} 一比一：translate → rotateY(-yaw) → rotateX(pitch)
 *       → scale(-s,-s,s)。之后宿主按「MC 字体坐标」画字（x 右、y 下、原点是平面中心）；</li>
 *   <li>{@link Host#drawPixelText(text, x, y, argb, shadow)} —— 原版字体一批
 *   <li>{@link Host#drawSmoothGlyph(c, x, y, baselinePx, sizePx, rgb, alpha, rot, dx, dy, sc)}
 *       —— 平滑字体单字（纹理烘焙档）；</li>
 *   <li>{@link Host#popBillboard()}。</li>
 * </ul>
 *
 * 字的<b>顶部</b>（MC 字体口径），平面中心是原点、行高 {@code WorldLyricsState.LINE_H}。</p>
 */
public final class WorldLyricsRenderer {

    /** 诊断开关（与树侧同一个 -D 属性）。trace 每帧最多一行，避免刷爆日志。 */
    static final boolean TRACE = Boolean.getBoolean("muonium.debug.worldLyrics");
    private static long lastTraceNanos;

    private WorldLyricsRenderer() {
    }

    /** trace 节流：同一秒内不重复打。 */
    private static boolean traceThrottled() {
        long now = System.nanoTime();
        if (now - lastTraceNanos < 1_000_000_000L) return true;
        lastTraceNanos = now;
        return false;
    }

    /** 宿主能力。树侧实现（1.21.1 的 WorldBillboard）。 */
    public interface Host {
        /**
         * 绕 Y 转 -yaw、绕 X 转 pitch，再 scale(-s,-s,s) 翻成文字坐标。
         */
        void pushBillboard(double rx, double ry, double rz,
                           double yawDeg, double pitchDeg, float scale);

        void popBillboard();

        /** 原版像素字一批（y 是字顶；阴影按原版规则拆开单独画）。 */
        void drawPixelText(String text, float x, float y, int argb, boolean shadow);

        /** 原版像素字宽。 */
        float pixelWidth(String text);

        /** 原版单字符宽。 */
        float pixelCharWidth(char c);

        /**
         * 平滑字体单字（纹理烘焙档）。(x, y) 是字的顶部；rot/dx/dy/sc 是 wiggle
         * 四量（0/0/0/1 = 不动），宿主在纹理四边形上实现旋转/位移/缩放。
         */
        void drawSmoothGlyph(char c, float x, float y, float sizePx,
                             int rgb, float alpha, float rot, float dx, float dy, float sc);

        /** 平滑字体的单字宽（同字号）。 */
        float smoothCharWidth(char c, float sizePx);
    }

    /**
     */
    public static void render(WorldLyricsState state, WorldLyricsState.CameraView camera,
                              WorldLyricsSettings settings, Host host, long nowNanos) {
        try {
            renderGuarded(state, camera, settings, host, nowNanos);
        } catch (Throwable t) {
            System.out.println("[MuoniumPlayer/WorldLyrics] 渲染失败，本帧跳过: " + t);
        }
    }

    private static void renderGuarded(WorldLyricsState state, WorldLyricsState.CameraView camera,
                                      WorldLyricsSettings settings, Host host, long nowNanos) {
        if (!settings.enabled || !WorldLyricsState.hasLyrics() || camera == null || host == null) {
            return;
        }
        // 一次取整份快照：分开调 getSnapshot()/positionMsNow() 可能跨切歌边界，
        // 「上一首的 playing」配上「下一首的 position」会把换行判定搅乱。
        com.muoniumplayer.core.ncm.music.CloudMusic.PlaybackSnapshot snap =
                com.muoniumplayer.core.ncm.music.CloudMusic.getSnapshot();
        WorldLyricsState.Frame frame = state.update(camera, settings, nowNanos,
                snap.playing, snap.positionMs);
        if (frame == null || frame.rows.isEmpty()) {
            if (TRACE && !traceThrottled()) {
                System.out.println("[WorldLyrics/trace] hasLyrics=true 但本帧无行可画"
                        + "（playing=" + snap.playing + " posMs=" + snap.positionMs + "）");
            }
            return;
        }

        long songNowMs = snap.positionMs;
        long wallMs = System.currentTimeMillis();
        double wiggleFps = 8.0;
        double wiggleAmount = 0.8;
        long frame8 = WorldLyricsState.wiggleFrame(wallMs, wiggleFps);

        for (int i = 0; i < frame.rows.size(); i++) {
            WorldLyricsState.Row row = frame.rows.get(i);
            if (row.alpha <= 0.01f) continue;
            drawRow(row, settings, host, songNowMs, frame8, wiggleAmount);
        }
    }

    private static void drawRow(WorldLyricsState.Row row, WorldLyricsSettings settings,
                                Host host, long songNowMs, long frame8, double wiggleAmount) {
        // 视图矩阵已被 Bridge 换成单位矩阵、相机旋转在 basePose 里，所以顶点必须
        // 用「相对眼睛、未旋转」的坐标 —— 传世界坐标就是几万格外的视锥外。
        host.pushBillboard(
                row.x - row.camX, row.y - row.camY, row.z - row.camZ,
                row.yawDeg, row.pitchDeg, row.scale);
        try {
            // 平滑字体走纹理烘焙；像素字体直接 drawInBatch
            boolean smooth = settings.smoothFont;
            WorldLyricsState.Floating f = row.floating;
            WorldLyricsState.LyricGlyphs glyphs = WorldLyricsState.buildGlyphs(
                    f, songNowMs, row.alpha, settings.wordPop, host::pixelWidth);
            int seed = glyphs.seed;

            for (WorldLyricsState.LyricGlyph g : glyphs.glyphs) {
                float rot = 0f, dx = 0f, dy = 0f, sc = 1f;
                if (settings.wiggle && wiggleAmount > 0.001) {
                    float[] w = WorldLyricsState.wiggleOf(g, seed, frame8, wiggleAmount);
                    rot = w[0]; dx = w[1]; dy = w[2]; sc = w[3];
                }
                if (smooth) {
                    // 绕字中心）；当前宿主以像素字兜底，至少带上 rot/dx/dy 的位置分量
                    host.drawSmoothGlyph(g.c, g.x + dx, g.y + dy, WorldLyricsState.LYRIC_SIZE * sc,
                            WorldLyricsState.TEXT_RGB, g.alpha, rot, dx, dy, sc);
                } else {
                    int argb = ((int) (g.alpha * 255f) << 24) | (WorldLyricsState.TEXT_RGB & 0xFFFFFF);
                    if (sc != 1f) {
                        // pop 缩放（word pop）：单字按中心缩放 —— 宿主只支持整串，退化为直接画
                        host.drawPixelText(String.valueOf(g.c), g.x + dx, g.y + dy, argb, true);
                    } else {
                        host.drawPixelText(String.valueOf(g.c), g.x + dx, g.y + dy, argb, true);
                    }
                }
            }
        } finally {
            host.popBillboard();
        }
    }

    public static List<LyricLine> split(LyricLine line, boolean enabled) {
        return SplitLines.split(line, enabled);
    }
}
