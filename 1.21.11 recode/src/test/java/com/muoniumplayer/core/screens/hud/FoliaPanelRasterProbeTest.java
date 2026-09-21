package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.render.skija.RasterFrames;
import com.muoniumplayer.render.skija.SkijaGfx;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.EncoderPNG;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Surface;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 游戏外把 Folia 版 HUD 编辑器光栅成 PNG。
 *
 * <p>存在的理由：这个界面的排版是几百个相互耦合的 CSS 像素常量，只看代码改不动 ——
 * 每改一处要启动一次客户端才能看见结果，一轮就是十几秒，而对照的是浏览器里 1:1 的
 * folia 设置弹窗。这里直接开一帧 CPU 画布调真实的 {@code drawScreenUnguarded}，
 * 出图像素与游戏内一致（1 CSS 像素 = 1 像素），于是可以拿浏览器截图逐块叠着比。
 *
 * <p>刻意<b>不</b>走 {@code drawScreen}：那条路要先画遮罩之下的实时 HUD（要播放器、要
 * Minecraft 的窗口），而这一次校准关心的只有面板本身。
 *
 * <p>出图落在 {@code build/folia-raster/}。任何一步不可用（原生库、字体、Minecraft 类）
 * 都只打报告不出图 —— 这是个观察窗口，不该把别人的构建弄红。
 */
class FoliaPanelRasterProbeTest {

    /** folia 弹窗在 1920 宽视口下的实测：面板 896、屏高 1080 时 h-[85vh]。 */
    private static final int CSS_WIDTH = 1920;
    private static final int CSS_HEIGHT = 1080;
    /** 与游戏内一致的 Minecraft GUI 缩放（1920x1080 自动档）。发丝线与模糊 sigma 按它换算。 */
    private static final double HOST_GUI_SCALE = 3.0;

    /** 阴影剖面探针的底色：中灰，好让 25% 黑量得出差值。 */
    private static final int SHADOW_PROBE_BG = 0xFF808080;
    /** 面板左缘往外取多少个像素。模糊的支撑域只到 63，留点余量看它散没散尽。 */
    private static final int SHADOW_PROFILE_PX = 70;

    private static final Path OUT_DIR = Path.of("build", "folia-raster");

    private final StringBuilder report = new StringBuilder();

    /**
     * 出图。
     *
     * <p>逻辑尺寸直接取 CSS 像素（相当于游戏里把那根「设置界面缩放」滑杆放到 1.0）：
     * 这样一张图就是一个 1920×1080 的浏览器视口，可以和 folia 自己的截图逐像素叠着比。
     * 滑杆本身是均匀放大，不改变任何相对关系，所以按 1.0 校准、按 1.5 使用是同一套版式。</p>
     */
    @Test
    void 每个分区出一张图() {
        Surface surface = null;
        try {
            int guiWidth = CSS_WIDTH;
            int guiHeight = CSS_HEIGHT;
            surface = RasterFrames.begin(CSS_WIDTH, guiWidth, guiHeight, HOST_GUI_SCALE);
            step("光栅帧", CSS_WIDTH + "x" + CSS_HEIGHT + " 像素，逻辑 " + guiWidth + "x" + guiHeight);

            CFontRenderer fr = fonts();
            Object screen = newScreen(guiWidth, guiHeight);
            if (fr == null || screen == null) {
                return;
            }
            Files.createDirectories(OUT_DIR);
            int count = HudSettingsModel.categories().length;
            for (int i = 0; i < count; i++) {
                setField(screen, "categoryIndex", i);
                String name = HudSettingsModel.categories()[i].title;
                if (draw(screen, fr)) {
                    write(surface, "panel-" + i + ".png");
                    dumpRows(screen, i, name);
                    line("分区 " + i + " " + name + " -> build/folia-raster/panel-" + i + ".png");
                    drawTail(surface, screen, fr, i, name);
                }
            }
        } catch (Throwable t) {
            line("抛异常: " + t);
            t.printStackTrace(System.out);
        } finally {
            try {
                Files.write(OUT_DIR.resolve("report.txt"),
                        ("[folia-raster]\n" + report).getBytes("UTF-8"));
            } catch (Exception ignored) {
                // 报告写不出去也不能盖住真正的失败原因。
            }
            System.out.println("[folia-raster] 见 build/folia-raster/report.txt");
            RasterFrames.end(surface);
        }
    }

    /**
     * 面板阴影必须是渐出去的，不能是一圈四周齐裁的方框。
     *
     * <p>这条是被实机截图逼出来的：模糊滤镜取 {@code CLAMP} 时，Skia 把源形状自身的边界像素
     * 往外续值，卷积看到的是一片无限延伸的同值半平面 —— 糊出来就是一张齐裁的实心矩形。
     * 表现即玩家报的「背景异常阴影」：面板外凭空多出一圈 25% 黑、边线笔直的方框，
     * 边界正好落在面板外 63 CSS 像素（3 倍 sigma 再减 12 的 spread）处。
     *
     * <p>底色必须换成中灰：默认的 {@code #09090B} 近黑，叠 25% 黑只差两个灰阶，量不出来。
     */
    @Test
    void 面板阴影是渐出的不是方框() {
        int[] profile = null;
        Surface surface = null;
        try {
            surface = RasterFrames.begin(CSS_WIDTH, CSS_WIDTH, CSS_HEIGHT, HOST_GUI_SCALE);
            CFontRenderer fr = fonts();
            Object screen = newScreen(CSS_WIDTH, CSS_HEIGHT);
            if (fr != null && screen != null) {
                SkijaGfx.canvas().clear(SHADOW_PROBE_BG);
                if (draw(screen, fr)) {
                    profile = shadowProfileLeftOfPanel(surface, screen);
                    Files.createDirectories(OUT_DIR);
                    write(surface, "shadow-grey.png");
                }
            }
        } catch (Throwable t) {
            line("阴影剖面不可用: " + t);
        } finally {
            try {
                Files.createDirectories(OUT_DIR);
                Files.write(OUT_DIR.resolve("shadow-report.txt"),
                        ("[folia-shadow]\n" + report).getBytes("UTF-8"));
            } catch (Exception ignored) {
                // 同 report.txt：写不出去不能盖住真正的失败。
            }
            RasterFrames.end(surface);
        }
        // 断言放在 try 之外：Throwable 会把 AssertionError 一起吞掉，那就等于没测。
        if (profile == null) {
            return;
        }
        int atEdge = profile[0];
        int atFar = profile[profile.length - 10];
        int maxJump = 0;
        for (int d = 1; d < profile.length; d++) {
            maxJump = Math.max(maxJump, profile[d] - profile[d - 1]);
        }
        assertTrue(atEdge <= 122, "面板左缘外应当压着阴影，实测灰阶 " + atEdge
                + "（底色 " + SHADOW_PROBE_BG + "）");
        assertTrue(atFar >= 126, "面板外 " + (profile.length - 10) + "px 处阴影应当已散尽，实测灰阶 "
                + atFar + " —— 齐裁的方框就是这里跑不掉的");
        assertTrue(maxJump <= 8, "阴影剖面里有 " + maxJump + " 灰阶的单像素跳变，高斯糊不出这种东西");
    }

    /** 沿面板垂直中线、从左缘往外逐像素取灰阶。下标即「距左缘多少像素」。 */
    private int[] shadowProfileLeftOfPanel(Surface surface, Object screen) throws Exception {
        int[] panel = (int[]) invoke(screen, "panelBounds");
        int y = panel[1] + panel[3] / 2;
        int[] out = new int[SHADOW_PROFILE_PX];
        byte[] px = readPixels(surface);
        for (int d = 0; d < out.length; d++) {
            out[d] = greyAt(px, CSS_WIDTH, panel[0] - 1 - d, y);
        }
        line("阴影剖面 贴边=" + out[0] + " 最外=" + out[out.length - 1]);
        return out;
    }

    private byte[] readPixels(Surface surface) {
        try (Bitmap bitmap = new Bitmap()) {
            bitmap.allocPixels(ImageInfo.makeN32Premul(CSS_WIDTH, CSS_HEIGHT));
            if (!surface.readPixels(bitmap, 0, 0)) {
                throw new IllegalStateException("readPixels 失败");
            }
            return bitmap.readPixels();
        }
    }

    /** N32Premul 在小端机上是 BGRA。这张图是纯灰底，任取一通道即可。 */
    private static int greyAt(byte[] px, int stride, int x, int y) {
        return px[(y * stride + x) * 4 + 1] & 0xFF;
    }

    private static Object invoke(Object target, String methodName) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(methodName);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (NoSuchMethodException ignored) {
                // 继续往父类找
            }
        }
        throw new NoSuchMethodException(methodName);
    }

    /**
     * 再出一张「滚到底」的图，叫 {@code panel-<i>-tail.png}。
     *
     * <p>探针默认只画滚动位置 0 那一屏，而长分区最后几组（分析模型那排命令按钮就在最后）
     * 根本不在屏内 —— 那恰恰是最需要对着 folia 逐像素看的地方。绘制路径每帧都会把
     * {@code scrollOffset} 夹回 {@code maxScroll}，所以塞一个大数就是「滚到底」；
     * 但必须同时把 {@code snapScroll} 立起来，否则这一帧会落在平滑滚动的补间起点，
     * 出来的还是第一屏。</p>
     */
    private void drawTail(Surface surface, Object screen, CFontRenderer fr, int index, String title) {
        try {
            setField(screen, "scrollOffset", 99999);
            setField(screen, "snapScroll", Boolean.TRUE);
            if (draw(screen, fr)) {
                write(surface, "panel-" + index + "-tail.png");
                line("  尾部 -> build/folia-raster/panel-" + index + "-tail.png");
            }
        } catch (Throwable t) {
            line("  尾部出图跳过: " + t);
        } finally {
            try {
                setField(screen, "scrollOffset", 0);
                setField(screen, "snapScroll", Boolean.TRUE);
            } catch (Throwable ignored) {
                // 复位失败也不能盖住真正的问题。
            }
        }
    }

    /** pf 系列是纯 Skija 的，字体走 classpath 资源，不需要游戏资源包。 */
    private CFontRenderer fonts() {
        try {
            FontManager.loadFonts();

        } catch (Throwable t) {
            line("FontManager.loadFonts() 不可用: " + t);
            return null;
        }
        CFontRenderer fr = FontManager.pf12;
        line("字体: pf12=" + (fr == null ? "null" : "ok")
                + " pf14=" + (FontManager.pf14 == null ? "null" : "ok")
                + " pf14bold=" + (FontManager.pf14bold == null ? "null" : "ok"));
        return fr;
    }

    /**
     * 建一个屏幕对象并把尺寸塞进去。
     *
     * <p>先试常规构造。它可能死在字段初始化上 —— {@code GuiScreen} 的父类是 Minecraft 的
     * {@code Screen}，无游戏环境里 {@code Minecraft.getInstance()} 是 null。那时退回
     * {@link sun.misc.Unsafe#allocateInstance}：跳过所有初始化器，然后由 {@link #fillDefaults}
     * 把探针要用的那几个字段补上（集合、数组、枚举与尺寸）。
     *
     * <p>{@code width}/{@code height} 是 Minecraft {@code Screen} 的 protected 字段，
     * 而 {@code init(MinecraftClient, int, int)} 在没有客户端时不能调，所以一律反射写。
     */
    private Object newScreen(int guiWidth, int guiHeight) {
        Object screen;
        try {
            screen = GuiHudEditorFolia.class.getDeclaredConstructor().newInstance();
            line("构造 GuiHudEditorFolia 成功（常规构造）");
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            line("常规构造不可用: " + root);
            root.printStackTrace(System.out);
            screen = allocate(guiWidth, guiHeight);
            if (screen == null) {
                return null;
            }
        }
        try {
            setField(screen, "width", guiWidth);
            setField(screen, "height", guiHeight);
            setField(screen, "openTime", System.currentTimeMillis() - 60_000L);
            setField(screen, "introStarted", Boolean.TRUE);
            setField(screen, "layerAlpha", 1f);
        } catch (Throwable t) {
            line("设字段失败: " + t);
        }
        return screen;
    }

    private Object allocate(int guiWidth, int guiHeight) {
        try {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
            Object screen = unsafe.allocateInstance(GuiHudEditorFolia.class);
            fillDefaults(screen);
            line("构造 GuiHudEditorFolia 成功（allocateInstance + 补默认值）");
            return screen;
        } catch (Throwable t) {
            line("allocateInstance 也失败: " + t);
            return null;
        }
    }

    /** 把初始化器本来会给的容器补上：这里只按类型补，具体值由调用处覆盖。 */
    private void fillDefaults(Object screen) throws Exception {
        for (Class<?> c = screen.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                if (field.get(screen) != null) {
                    continue;
                }
                Class<?> type = field.getType();
                Object value = null;
                if (type == java.util.List.class || type == java.util.Collection.class) {
                    value = new java.util.ArrayList<Object>();
                } else if (type == java.util.Map.class) {
                    value = new java.util.HashMap<Object, Object>();
                } else if (type == int[].class) {
                    value = new int[64];
                } else if (type.isEnum()) {
                    Object[] constants = type.getEnumConstants();
                    value = constants != null && constants.length > 0 ? constants[0] : null;
                }
                if (value != null) {
                    field.set(screen, value);
                }
            }
        }
        setField(screen, "activeAnchor", -1);
        setField(screen, "contentShownCategory", Integer.MIN_VALUE);
        setField(screen, "chipDragStartX", Integer.MIN_VALUE);
    }

    /** 一个分区画不应当把整轮带走：失败就记下来，返回 false 让循环继续。 */
    private boolean draw(Object screen, CFontRenderer fr) {
        try {
            Method m = GuiHudEditorFolia.class.getDeclaredMethod(
                    "drawScreenUnguarded", CFontRenderer.class, int.class, int.class, float.class);
            m.setAccessible(true);
            // 鼠标停在面板外：对照图里不该有 hover 态。
            m.invoke(screen, fr, 10, 10, 0f);
            return true;
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            line("绘制失败: " + root);
            java.io.StringWriter sw = new java.io.StringWriter();
            root.printStackTrace(new java.io.PrintWriter(sw));
            report.append(sw);
            return false;
        }
    }

    /**
     * 把这一屏算出来的行矩形写成文本。
     *
     * <p>光看 PNG 只能看个大概，「这一格到底 74 还是 82」要能和 folia 那份 JSON 里的
     * {@code getBoundingClientRect} 逐行对上，才谈得上 1:1。</p>
     */
    private void dumpRows(Object screen, int index, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 分区 ").append(index).append(' ').append(title).append('\n');
        try {
            Object list = readField(screen, "rows");
            int size = ((java.util.List<?>) list).size();
            for (int i = 0; i < size; i++) {
                Object row = ((java.util.List<?>) list).get(i);
                Object entry = readField(row, "entry");
                sb.append(entry == null ? "HEAD" : String.valueOf(readField(row, "title")))
                        .append(" x=").append(readField(row, "x"))
                        .append(" y=").append(readField(row, "y"))
                        .append(" w=").append(readField(row, "w"))
                        .append(" h=").append(readField(row, "h"))
                        .append(" sep=").append(readField(row, "sep"))
                        .append(" padded=").append(readField(row, "padded"))
                        .append('\n');
            }
            Files.write(OUT_DIR.resolve("rows-" + index + ".txt"),
                    sb.toString().getBytes("UTF-8"));
        } catch (Throwable t) {
            line("行矩形导出失败: " + t);
        }
    }

    private static Object readField(Object target, String name) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            }
        }
        throw new NoSuchFieldException(name);
    }

    private void write(Surface surface, String fileName) throws Exception {
        Path out = OUT_DIR.resolve(fileName);
        try (Data png = EncoderPNG.encode(surface.makeImageSnapshot())) {
            if (png == null) {
                line("EncoderPNG.encode 返回 null");
                return;
            }
            Files.write(out, png.getBytes());
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找：width/height 在 Minecraft 的 Screen 上。
            }
        }
        throw new NoSuchFieldException(name);
    }

    private void step(String what, Object detail) {
        line(what + ": " + detail);
    }

    private void line(String text) {
        report.append("  ").append(text).append('\n');
    }
}
