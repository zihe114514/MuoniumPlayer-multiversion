package com.muoniumplayer.core.screens;

import com.muoniumplayer.render.skija.SkijaHost;
import com.muoniumplayer.render.skija.SkijaNatives;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * 渲染层停用时的<b>原版 GUI</b> 提示。
 *
 * <h2>为什么这一层不能画在 Skia 上</h2>
 * 它要说的事正是「Skia 那一层没能起来」。画在那上面等于对着空屏幕说话——
 * 事实上这就是过去每一个「装上模组、游戏正常、界面一个像素都没有」反馈的全部经过：
 * 停用信息只存在于一行 stdout 里，而手机端用户既看不到也不会去读。
 * 所以本类只用原版字体与 {@link GuiGraphicsExtractor}，走 Minecraft 自己的 GUI 提交链路，
 * 与 Skia 是否可用完全无关。原版字体自带 unifont 兜底，中文和路径都能画出来。
 *
 * <p>本文件是<b>留守文件</b>：26.x 的 HUD/Screen 回调给的是 {@link GuiGraphicsExtractor}
 * （文字方法叫 {@code text}），1.20.1~1.21.11 给的是 {@code GuiGraphics}（方法叫
 * {@code drawString}），两代 API 拼不成一份共享源码，所以五棵树各留一份、按代际分两种写法。
 *
 * <h2>说什么</h2>
 * 只说<b>用户下一步能做的事</b>：缺哪个伴生包、从哪下、解压到哪儿、多久会自动重试。
 * 内部诊断（尝试过哪几条路径、各自的异常）不进屏幕，进日志——提示层负责指路，
 * 不负责把堆栈糊在玩家脸上。
 *
 * <h2>什么时候说满、什么时候说一句</h2>
 * 停用后的头 {@link #DETAILED_WINDOW_NANOS} 打整块文案（这时候人刚打开游戏，正在看屏幕）；
 * 之后 HUD 上只留一行角标，其余部分等再有界面打开时补全。
 * 长期缺库的机器不该被一块常驻黑框挡住半个屏幕。
 */
public final class RenderLayerNotice {

    /** 整块文案的展示时长；之后收成一行。 */
    private static final long DETAILED_WINDOW_NANOS = 60L * 1_000_000_000L;

    /** 第一次观察到停用的时刻；null 表示还没停用过。 */
    private static Long suspendedSince;

    /** 上一次展示过整块文案，用来判断「本轮要不要说满」。 */
    private static boolean detailedShown;

    private static final int MARGIN = 4;
    private static final int LINE_HEIGHT = 10;
    /** 文字色：浅灰白，与原版提示一致，不跟主题色走——主题色在渲染层里，而渲染层正是坏的。 */
    private static final int TEXT_COLOR = 0xFFE6E6E6;
    private static final int BACKDROP = 0xC0101014;

    /** 自动下载进度条：贴在文案下面、与文案块同宽。 */
    private static final int BAR_GAP = 4;
    private static final int BAR_HEIGHT = 4;
    private static final int BAR_TRACK = 0xFF2E2E34;
    private static final int BAR_FILL = 0xFF6EDAA0;

    private RenderLayerNotice() {
    }

    /**
     * 画一帧提示。渲染层正常时什么都不做。
     *
     * @param graphics 原版 GUI 绘制上下文
     * @param onScreen 是否画在某个界面上（界面里空间大、视线在屏幕中央，永远说满）
     */
    public static void draw(GuiGraphicsExtractor graphics, boolean onScreen) {
        if (!SkijaHost.isSuspended()) {
            suspendedSince = null;
            detailedShown = false;
            return;
        }
        long now = System.nanoTime();
        if (suspendedSince == null) {
            suspendedSince = now;
            detailedShown = false;
        }
        // 自动下载全程整块常驻：下载中缩成一行角标会被当成卡死；装好待重启时更不能收，
        // 「必须重启游戏」是这一屏唯一要传达的事。
        boolean downloadVisible = SkijaNatives.nativeDownloadVisible();
        int downloadPercent = SkijaNatives.nativeDownloadPercent();
        boolean detailed = onScreen || !detailedShown || downloadVisible
                || now - suspendedSince < DETAILED_WINDOW_NANOS;
        if (detailed) {
            detailedShown = true;
        }

        SkijaNatives.Status status = SkijaHost.nativeStatus();
        Font font = Minecraft.getInstance().font;
        int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();

        List<String> lines = detailed
                ? detailedLines(status)
                : List.of(headline(status));
        String[] clipped = clipAll(lines, font, screenWidth - MARGIN * 2);

        int widest = 0;
        for (String line : clipped) {
            widest = Math.max(widest, font.width(line));
        }
        int height = clipped.length * LINE_HEIGHT + MARGIN * 2
                + (downloadVisible ? BAR_GAP + BAR_HEIGHT : 0);
        int x = MARGIN;
        // 小屏或字体放大时整块可能比屏幕还高：贴到顶也不能把它推出可视区，
        // 否则用户看到的仍然是「什么都没有」，正是这块提示要避免的事。
        int y = Math.max(MARGIN,
                Minecraft.getInstance().getWindow().getGuiScaledHeight() - height - MARGIN);
        graphics.fill(x, y, x + widest + MARGIN * 2, y + height, BACKDROP);
        for (int index = 0; index < clipped.length; index++) {
            graphics.text(font, clipped[index], x + MARGIN, y + MARGIN + index * LINE_HEIGHT,
                    TEXT_COLOR, false);
        }
        if (downloadVisible) {
            drawProgressBar(graphics, x + MARGIN,
                    y + MARGIN + clipped.length * LINE_HEIGHT + BAR_GAP, widest, downloadPercent);
        }
    }

    /**
     * 自动下载进度条。总长度拿得到就画实打实的百分比；拿不到画一段来回扫的不定长动画——
     * 静止的空条读起来像卡死。装好待重启的阶段画满条：绿条到头 = 「可以重启了」。
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

    /** 一行标题：三种失败形状各说各的关键词。 */
    private static String headline(SkijaNatives.Status status) {
        if (!status.loaded()) {
            if (status.nativeFound()) {
                return "MuoniumPlayer：渲染层原生库加载被系统拒绝";
            }
            // 停用发生在「还没碰原生库」之前（例如 26.x 的 Vulkan 后端检查）：
            // status 还是初始值，这时喊「缺原生库」会把 Vulkan 用户指去下伴生包。
            if (status.attempted().isEmpty() && SkijaHost.suspendReason() != null) {
                return "MuoniumPlayer：渲染层启动失败（原生库未及尝试）";
            }
            return "MuoniumPlayer：缺 " + status.mappedPlatform() + " 的渲染原生库";
        }
        return "MuoniumPlayer：渲染层启动失败（原生库已加载）";
    }

    /**
     * 整块文案。
     *
     * <p>分岔按「下一步动作相反」来切：
     * <ol>
     *   <li>Android → 除通用分岔外多说一行：渲染器要用 MobileGlues 并关闭 ANGLE，
     *       这是 2026-10-05 真机实测出的可用组合；</li>
     *   <li>原生库压根没找到 → 自动下载进行中就说进度与现状，否则给包名、地址、目录、重试时间；</li>
     *   <li>原生库找到了、版本也对，却仍然加载不了 → 再喊「请下载伴生包」是把人往回指第二遍，
     *       要说「请把日志给我们」；</li>
     *   <li>原生库加载成功了却仍停用 → 卡在 GPU 上下文或绘制，给伴生包没有任何意义。</li>
     * </ol>
     */
    private static List<String> detailedLines(SkijaNatives.Status status) {
        List<String> lines = new ArrayList<>();
        lines.add(headline(status));
        if (status.mappedPlatform().startsWith("android")) {
            // 2026-10-25 真机：MobileGL（26.3 当前的主流渲染器）在 GL 门对齐修复
            // （SkijaGlRedirector 把 libskija 的 GL/EGL 导入重定向到它那扇门）之后 Skia
            // 完整可用。修复前「MobileGlues + 关 ANGLE」的指引是旧结论，留在其余版本树；
            // 26.3 不再给——否则会把用户从更主流、且已被适配的 MobileGL 引开。
            lines.add("26.3 安卓请使用 MobileGL 渲染器（本模组已适配并实测）");
            lines.add("Krypton 包装的渲染器可能弄坏世界贴图，不建议使用");
        }
        if (!status.loaded()) {
            if (status.attempted().isEmpty() && SkijaHost.suspendReason() != null) {
                lines.add("原因：" + firstSentence(SkijaHost.suspendReason()));
                lines.add("日志里搜 MuoniumPlayer/SkijaHost 看完整记录");
                return lines;
            }
            if (status.nativeFound()) {
                lines.add("原生库已找到（" + status.mappedPlatform() + " " + status.pinnedVersion()
                        + "），但系统加载不了它");
                lines.add("多半是这份库的 ABI 与本机对不上，或文件不完整");
                lines.add("这种情况下再下一遍伴生包没有用——请把 latest.log 发给我们");
                lines.add("日志里搜 MuoniumPlayer/SkijaNatives 与 MuoniumPlayer/runtime");
            } else {
                // 缺库文案跟自动下载的状态走（下载中 / 装好待重试 / 失败转手动），
                // 由共享层统一生成——五棵树各写一份必然飘。
                lines.addAll(SkijaNatives.nativeMissingLines());
            }
        } else {
            lines.add("原因：" + firstSentence(SkijaHost.suspendReason()));
            lines.add("日志里搜 MuoniumPlayer/SkijaHost 看完整记录");
        }
        return lines;
    }

    /** 只取第一句，避免把几十条候选尝试的原因串成一条刷满屏幕。 */
    private static String firstSentence(String raw) {
        if (raw == null || raw.isBlank()) {
            return "未知";
        }
        int cut = raw.indexOf(" | ");
        return cut > 0 ? raw.substring(0, cut) : raw;
    }

    /** 按可用宽度截断，超出的部分用省略号收尾——路径截了就等于没给，所以宁可换行也不要截。 */
    private static String[] clipAll(List<String> lines, Font font, int maxWidth) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (font.width(line) <= maxWidth || maxWidth <= 0) {
                out.add(line);
                continue;
            }
            // 路径这种「一个整词」的长行，按分隔符切开续到下一行，比中间截断有用得多
            String remainder = line;
            while (font.width(remainder) > maxWidth && remainder.length() > 1) {
                int cut = breakPoint(remainder, font, maxWidth);
                out.add(remainder.substring(0, cut));
                remainder = remainder.substring(cut);
            }
            out.add(remainder);
        }
        return out.toArray(new String[0]);
    }

    /** 找一个不切开目录名与文件名的断点：优先分隔符，退而求其次按空格，再不行就硬切。 */
    private static int breakPoint(String text, Font font, int maxWidth) {
        int acc = 0;
        int lastSeparator = -1;
        int lastSpace = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            acc += font.width(String.valueOf(c));
            if (acc > maxWidth) {
                break;
            }
            if (c == '/' || c == '\\') {
                lastSeparator = i + 1;
            } else if (c == ' ') {
                lastSpace = i + 1;
            }
        }
        if (lastSeparator > 0) {
            return lastSeparator;
        }
        if (lastSpace > 0) {
            return lastSpace;
        }
        return Math.max(1, Math.min(text.length() - 1, maxWidth > 0 ? text.length() - 1 : 1));
    }
}
