package com.muoniumplayer.core.screens;

import com.muoniumplayer.core.screens.hud.MuoniumConfigDownload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

/**
 * MuoniumConfig 自动下载的<b>原版 GUI</b> 提示——与 {@link RenderLayerNotice} 同一套做法：
 * 只用原版字体与 {@link GuiGraphicsExtractor}，跟 Skia 是否可用完全无关（下载 MuoniumConfig
 * 的机器上渲染层多半是好的，但提示绝不能画在那个可能缺席的层上）。
 *
 * <h2>什么时候出现、挡多久</h2>
 * 下载中与「已装好待重启」两段<b>整块常驻</b>：下载中缩成一行角标会被当成卡死；
 * 装好待重启时「需要重启游戏」是这一屏唯一要传达的事，自动重启倒计时也在块里。
 * 失败只在头 60 秒整块出现（纯外观损失，不该常驻骚扰），之后收起，下次启动自动重试。
 *
 * <p>文案与可见性全部来自共享层的 {@link MuoniumConfigDownload} 门面，本文件只管画；
 * 画在<b>左上角</b>——渲染层停用提示（RenderLayerNotice）钉在左下角，两边同时出现也不叠。</p>
 *
 * <p>本文件是<b>留守文件</b>：26.x 的 HUD/Screen 回调给的是 {@link GuiGraphicsExtractor}
 * （文字方法叫 {@code text}），1.20.1~1.21.11 给的是 {@code GuiGraphics}，两代 API
 * 拼不成一份共享源码，各树一份、按代际分两种写法。</p>
 */
public final class MuoniumConfigNotice {

    /** 失败整块文案的展示时长；之后收起（下次启动自动重试，不需要用户记着这件事）。 */
    private static final long FAILURE_WINDOW_NANOS = 60L * 1_000_000_000L;

    /** 第一次观察到失败的时刻；null 表示本次会话还没失败过。 */
    private static Long failureSince;

    private static final int MARGIN = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int TEXT_COLOR = 0xFFE6E6E6;
    private static final int BACKDROP = 0xC0101014;

    private static final int BAR_GAP = 4;
    private static final int BAR_HEIGHT = 4;
    private static final int BAR_TRACK = 0xFF2E2E34;
    private static final int BAR_FILL = 0xFF6EDAA0;

    private MuoniumConfigNotice() {
    }

    /** 画一帧提示；不可见的帧什么都不做。 */
    public static void draw(GuiGraphicsExtractor graphics, boolean onScreen) {
        MuoniumConfigDownload.Phase phase = MuoniumConfigDownload.state().phase();
        long now = System.nanoTime();
        boolean failed = phase == MuoniumConfigDownload.Phase.FAILED;
        if (failed) {
            if (failureSince == null) {
                failureSince = now;
            }
        } else {
            failureSince = null;
        }
        boolean visible = MuoniumConfigDownload.noticeVisible()
                || (failed && now - failureSince < FAILURE_WINDOW_NANOS);
        if (!visible) {
            return;
        }

        Font font = Minecraft.getInstance().font;
        int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        List<String> lines = MuoniumConfigDownload.noticeLines();
        if (lines.isEmpty()) {
            return;
        }
        String[] clipped = clipAll(lines, font, screenWidth - MARGIN * 2);

        int widest = 0;
        for (String line : clipped) {
            widest = Math.max(widest, font.width(line));
        }
        boolean downloading = phase == MuoniumConfigDownload.Phase.DOWNLOADING;
        int height = clipped.length * LINE_HEIGHT + MARGIN * 2
                + (downloading ? BAR_GAP + BAR_HEIGHT : 0);
        int x = MARGIN;
        int y = MARGIN;
        graphics.fill(x, y, x + widest + MARGIN * 2, y + height, BACKDROP);
        for (int index = 0; index < clipped.length; index++) {
            graphics.text(font, clipped[index], x + MARGIN, y + MARGIN + index * LINE_HEIGHT,
                    TEXT_COLOR, false);
        }
        if (downloading) {
            drawProgressBar(graphics, x + MARGIN,
                    y + MARGIN + clipped.length * LINE_HEIGHT + BAR_GAP, widest,
                    MuoniumConfigDownload.noticePercent());
        }
    }

    /**
     * 下载进度条（与 RenderLayerNotice 同一条视觉纪律）：总长度拿得到就画实打实的百分比；
     * 拿不到画一段来回扫的不定长动画——静止的空条读起来像卡死。
     */
    private static void drawProgressBar(GuiGraphicsExtractor graphics, int x, int y, int width,
                                        int percent) {
        if (width <= 0) {
            return;
        }
        graphics.fill(x, y, x + width, y + BAR_HEIGHT, BAR_TRACK);
        if (percent < 0) {
            long sweep = Math.max(8, width / 5);
            long head = (System.currentTimeMillis() / 12L) % (width + sweep) - sweep;
            int from = (int) Math.max(0, head);
            int to = (int) Math.min(width, head + sweep);
            if (to > from) {
                graphics.fill(x + from, y, x + to, y + BAR_HEIGHT, BAR_FILL);
            }
            return;
        }
        int fill = (int) Math.min(width,
                (long) width * Math.max(0, Math.min(100, percent)) / 100L);
        if (fill > 0) {
            graphics.fill(x, y, x + fill, y + BAR_HEIGHT, BAR_FILL);
        }
    }

    /** 按可用宽度截断，超出的部分用省略号收尾（与 RenderLayerNotice 同一条规则）。 */
    private static String[] clipAll(List<String> lines, Font font, int maxWidth) {
        List<String> out = new java.util.ArrayList<>();
        for (String line : lines) {
            if (font.width(line) <= maxWidth || maxWidth <= 0) {
                out.add(line);
                continue;
            }
            String remainder = line;
            while (font.width(remainder) > maxWidth && remainder.length() > 1) {
                int cut = Math.max(1, Math.min(remainder.length() - 1, maxWidth));
                out.add(remainder.substring(0, cut));
                remainder = remainder.substring(cut);
            }
            out.add(remainder);
        }
        return out.toArray(new String[0]);
    }
}
