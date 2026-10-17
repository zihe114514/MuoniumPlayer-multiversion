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

        for (int i = 0; i < frame.rows.size(); i++) {
            WorldLyricsState.Row row = frame.rows.get(i);
            if (row.alpha <= 0.01f) continue;
            drawRow(row, settings, host, songNowMs);
        }
    }

    private static void drawRow(WorldLyricsState.Row row, WorldLyricsSettings settings,
                                Host host, long songNowMs) {
        // 视图矩阵已被 Bridge 换成单位矩阵、相机旋转在 basePose 里，所以顶点必须
        // 用「相对眼睛、未旋转」的坐标 —— 传世界坐标就是几万格外的视锥外。
        host.pushBillboard(
                row.x - row.camX, row.y - row.camY, row.z - row.camZ,
                row.yawDeg, row.pitchDeg, row.scale);
        try {
            // 渲染风格：现代化（Skija 字形图集 + 烘焙发光）或原版像素字。
            // 只换绘制通道 —— billboard 矩阵、逐字时序、特效数学完全共享。
            boolean smooth = settings.styleModern;
            WorldLyricsState.Floating f = row.floating;
            // 量宽必须与绘制通道同源：现代档的字形 advance 是烘焙字体的（一个汉字约
            // 10 设计像素），原版档是 MC 像素字体的（一个汉字 9px）。布局若固定用
            // MC 字体量、字却按烘焙 advance 画，相邻字形会叠在一起 —— 量宽跟着风格走。
            WorldLyricsState.PixelWidthOf widthOf = smooth
                    ? s -> {
                        float total = 0f;
                        for (int i = 0; i < s.length(); i++) {
                            float w = host.smoothCharWidth(s.charAt(i), WorldLyricsState.LYRIC_SIZE);
                            total += w > 0f ? w : host.pixelWidth(String.valueOf(s.charAt(i)));
                        }
                        return total;
                    }
                    : host::pixelWidth;
            WorldLyricsState.LyricGlyphs glyphs = WorldLyricsState.buildGlyphs(
                    f, songNowMs, row.alpha, settings.wordPop, !row.active, widthOf);

                // 文字颜色：取色器自定义过的色原样显示（选白色就是纯白 0xFFFFFF）；
                // 「是否自定义」必须是独立旗标 —— 0xFFFFFF 本身是合法的自定义色，
                // 以前拿值当旗标，选白色被回落成暖白，看起来就是「偏肉色」。
                int inkRgb = settings.colorCustom
                        ? settings.colorRgb
                        : WorldLyricsState.TEXT_RGB;
            for (WorldLyricsState.LyricGlyph g : glyphs.glyphs) {
                // g.pop 恒生效的弹性缩放（入场飞入回弹 / word pop 回弹 / 出场收拢），
                if (smooth) {
                    host.drawSmoothGlyph(g.c, g.x, g.y, WorldLyricsState.LYRIC_SIZE,
                            inkRgb, g.alpha, 0f, 0f, 0f, g.pop);
                } else {
                    int argb = ((int) (g.alpha * 255f) << 24) | (inkRgb & 0xFFFFFF);
                    host.drawPixelText(String.valueOf(g.c), g.x, g.y, argb, true);
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
