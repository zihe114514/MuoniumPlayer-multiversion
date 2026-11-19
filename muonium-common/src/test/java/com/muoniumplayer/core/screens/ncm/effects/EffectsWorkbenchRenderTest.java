package com.muoniumplayer.core.screens.ncm.effects;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.entities.impl.TextField;
import com.muoniumplayer.core.screens.ncm.NcmSheet;
import com.muoniumplayer.core.screens.ncm.WindowAdaptiveScale;
import com.muoniumplayer.core.viper.ViperDynamicEqModel;
import com.muoniumplayer.core.viper.ViperEqualizerModel;
import com.muoniumplayer.core.viper.ViperPresetEditor;
import com.muoniumplayer.core.viper.ViperWorkbenchCatalog;
import com.muoniumplayer.render.skija.SkijaHost;
import com.muoniumplayer.render.skija.SkijaNatives;
import com.muoniumplayer.testsupport.RequiresSkijaNative;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.EncodedImageFormat;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Surface;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.muoniumplayer.core.screens.ncm.effects.EffectsLayout.Box;
import static org.junit.jupiter.api.Assertions.*;

/** 真实工作台的离屏图与命中回归，不使用 HTML 代替 Skija 界面。 */
@RequiresSkijaNative
class EffectsWorkbenchRenderTest {

    private static final long FRAME_TIME = 10_000_000_000L;
    private static final String[] PAGES = {"PRESETS", "EQ", "EFFECTS", "ACOUSTICS", "AMBIENT"};
    private static final HostSize[] HOSTS = {
            new HostSize(1000, 650), new HostSize(928, 508), new HostSize(560, 430),
            new HostSize(360, 430), new HostSize(900, 270)
    };
    private static final Theme[] THEMES = {
            new Theme("dark-plum", EffectsPalette.fromColors(0x211821, 0x30212C, 0x493342, 0x604252,
                    0xFFF7FB, 0xC2AAB7, 0xE48AAB, 0xF4A9C4, 0x2A1D27, 0x1A1219)),
            new Theme("light-clay", EffectsPalette.fromColors(0xF4F0EA, 0xFFFCF7, 0xE7DDD1, 0xD4C6B8,
                    0x2A2723, 0x817970, 0xB85C38, 0xD37B54, 0xEEE7DF, 0xE7DED5)),
            new Theme("dark-cyan", EffectsPalette.fromColors(0x211821, 0x30212C, 0x493342, 0x604252,
                    0xFFF7FB, 0xC2AAB7, 0x5DCFE0, 0x9BE9F0, 0x2A1D27, 0x1A1219))
    };

    private record HostSize(int width, int height) {
        String name() { return width + "x" + height; }
    }
    private record Theme(String name, EffectsPalette palette) { }
    private record Frame(long pixels, GeometryProbe geometry) { }
    private record TextInk(String source, String shown, Box ink, Box allowed) { }
    private record ButtonInk(String label, Box button, Box icon, Box text, Box content) { }

    /** 观察实际绘制提交的字形度量，不另写一份工作台布局或文字渲染器。 */
    private static final class GeometryProbe implements EffectsPaint.Probe {
        final List<TextInk> texts = new ArrayList<>();
        final List<ButtonInk> buttons = new ArrayList<>();

        @Override public void text(String source, String shown, Box ink, Box allowed) {
            texts.add(new TextInk(source, shown, ink, allowed));
        }
        @Override public void button(String label, Box button, Box icon, Box text, Box content) {
            buttons.add(new ButtonInk(label, button, icon, text, content));
        }

        void assertContained() {
            assertFalse(texts.isEmpty(), "探针必须观察到生产绘字，不能仅验证背景板");
            for (TextInk text : texts) {
                assertInside(text.ink(), text.allowed(), .75, "文字溢出：" + text.source());
            }
            for (ButtonInk button : buttons) {
                assertInside(button.content(), button.button(), .01, "按钮内容溢出：" + button.label());
                assertEquals(button.button().centerX(), button.content().centerX(), .01,
                        "图标与文字必须作为一组居中：" + button.label());
                assertInside(button.icon(), button.button(), .01, "按钮图标溢出：" + button.label());
            }
        }
    }

    private static void assertInside(Box inner, Box outer, double tolerance, String message) {
        assertTrue(Double.isFinite(inner.x()) && Double.isFinite(inner.y())
                && Double.isFinite(inner.width()) && Double.isFinite(inner.height()), message + "（非有限几何）");
        assertTrue(inner.x() >= outer.x() - tolerance && inner.right() <= outer.right() + tolerance
                && inner.y() >= outer.y() - tolerance && inner.bottom() <= outer.bottom() + tolerance,
                message + " ink=" + inner + " allowed=" + outer);
    }

    @BeforeAll
    static void fonts() {
        assertTrue(SkijaNatives.ensureLoaded());
        FontManager.loadFonts();
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static EffectsWorkbench fixture(int width, int height, String page) throws Exception {
        EffectsWorkbench workbench = new EffectsWorkbench();
        EffectsLayout.Viewport viewport = new EffectsLayout.Viewport(width, height, WindowAdaptiveScale.DESIGN_PER_LAYOUT);
        workbench.setBounds(0, 0, width, height);
        workbench.setAlpha(1);
        field(EffectsWorkbench.class, "viewport").set(workbench, viewport);
        ViperPresetEditor editor = ViperPresetEditor.overDefaults();
        editor.setNumberQuiet(editor.groupIndex("masterLimiter"), editor.fieldIndex(editor.groupIndex("masterLimiter"), "outputVolume"), .8);
        com.muoniumplayer.core.viper.ViperEqualizerModel eq = new com.muoniumplayer.core.viper.ViperEqualizerModel(editor);
        eq.applyPresetQuiet("rock");
        field(EffectsWorkbench.class, "editor").set(workbench, editor);
        field(EffectsWorkbench.class, "loading").set(workbench, false);
        field(EffectsWorkbench.class, "enabled").set(workbench, true);
        field(EffectsWorkbench.class, "active").set(workbench, "自然低音 · 温暖声场");
        field(EffectsWorkbench.class, "notice").set(workbench, "已保存并应用 · 自定义");
        field(EffectsWorkbench.class, "presets").set(workbench, List.of("自然低音 · 温暖声场", "人声与钢琴", "空间感 · 轻混响", "夜间聆听", "导入的耳机方案", "一个名字非常长的自定义音效预设用于检验截断与按钮互不遮挡"));
        field(EffectsWorkbench.class, "kernels").set(workbench, List.of("Studio.irs", "Room.wav"));
        field(EffectsWorkbench.class, "ddc").set(workbench, List.of("My Headphones"));
        field(EffectsWorkbench.class, "acoustics").set(workbench, true);
        field(EffectsWorkbench.class, "submersion").set(workbench, true);
        field(EffectsWorkbench.class, "enclosure").set(workbench, true);
        field(EffectsWorkbench.class, "rainfall").set(workbench, true);
        Field current = field(EffectsWorkbench.class, "current");
        Object selected = Enum.valueOf((Class<Enum>) current.getType(), page);
        current.set(workbench, selected);
        Object indicator = field(EffectsWorkbench.class, "navigationIndicator").get(workbench);
        indicator.getClass().getMethod("snap", double.class).invoke(indicator, (double) ((Enum<?>) selected).ordinal());
        TextField search = (TextField) field(EffectsWorkbench.class, "search").get(workbench);
        search.setFontRenderer(EffectsTypography.body());
        search.setPlaceholder("PRESETS".equals(page) ? "搜索预设" : "搜索效果器");
        ((TextField) field(EffectsWorkbench.class, "input").get(workbench)).setFontRenderer(EffectsTypography.body());
        EffectsLayout layout = EffectsLayout.calculate(viewport.width(), viewport.height(), 0);
        field(EffectsWorkbench.class, "layout").set(workbench, layout);
        field(EffectsWorkbench.class, "pageClip").set(workbench, layout.viewport);
        field(EffectsWorkbench.class, "now").set(workbench, FRAME_TIME);
        field(EffectsWorkbench.class, "mouseX").set(workbench, -1000.0);
        field(EffectsWorkbench.class, "mouseY").set(workbench, -1000.0);
        field(EffectsWorkbench.class, "painter").set(workbench, new EffectsPaint());
        field(NcmSheet.class, "presentation").set(workbench, 1.0);
        field(NcmSheet.class, "pageFade").set(workbench, 1.0);
        return workbench;
    }

    private static Frame render(EffectsWorkbench workbench, int width, int height, Path output) throws Exception {
        Field host = field(SkijaHost.class, "canvas");
        Object previous = host.get(null);
        EffectsPaint painter = (EffectsPaint) field(EffectsWorkbench.class, "painter").get(workbench);
        GeometryProbe geometry = new GeometryProbe();
        painter.setProbe(geometry);
        long fingerprint = 0xcbf29ce484222325L;
        try (Surface surface = Surface.makeRasterN32Premul(width * 2, height * 2)) {
            host.set(null, surface.getCanvas());
            surface.getCanvas().clear(0xFF38343D);
            surface.getCanvas().scale(2, 2);
            int depth = surface.getCanvas().getSaveCount();
            ((List<?>) field(EffectsWorkbench.class, "hits").get(workbench)).clear();
            Method draw = EffectsWorkbench.class.getDeclaredMethod("drawInHost", io.github.humbleui.skija.Canvas.class, double.class);
            draw.setAccessible(true);
            draw.invoke(workbench, surface.getCanvas(), 1.0);
            assertEquals(depth, surface.getCanvas().getSaveCount(), "工作台不得泄漏变换或裁剪栈");
            try (Image image = surface.makeImageSnapshot(); Bitmap bitmap = new Bitmap()) {
                assertTrue(bitmap.allocN32Pixels(width * 2, height * 2, false));
                assertTrue(image.readPixels(bitmap, 0, 0));
                int textPixels = 0;
                int ink = ((EffectsPalette) field(EffectsWorkbench.class, "palette").get(workbench)).text();
                for (int y = 0; y < height * 2; y++) {
                    for (int x = 0; x < width * 2; x++) {
                        int color = bitmap.getColor(x, y);
                        fingerprint = (fingerprint ^ (color & 0xffffffffL)) * 0x100000001b3L;
                        if (Math.abs((color >>> 16 & 255) - (ink >>> 16 & 255)) <= 6
                                && Math.abs((color >>> 8 & 255) - (ink >>> 8 & 255)) <= 6
                                && Math.abs((color & 255) - (ink & 255)) <= 6) textPixels++;
                    }
                }
                boolean fadingDialog = field(EffectsWorkbench.class, "retiringDialog").get(workbench) != null
                        && field(EffectsWorkbench.class, "dialogTitle").get(workbench) == null;
                assertTrue(fadingDialog ? visibleGlyphPixels(bitmap, geometry.texts) > 500 : textPixels > 500,
                        "真实文字必须可见，退场混合色按字形周围底色对照；不能把底板当成文字像素：" + output);
                geometry.assertContained();
                if (output != null) {
                    Files.createDirectories(output.getParent());
                    try (Data data = image.encodeToData(EncodedImageFormat.PNG)) {
                        assertNotNull(data, "Skija PNG 编码不能返回空结果");
                        Files.write(output, data.getBytes());
                    }
                }
            }
            return new Frame(fingerprint, geometry);
        } finally {
            painter.setProbe(null);
            host.set(null, previous);
        }
    }

    private static int visibleGlyphPixels(Bitmap bitmap, List<TextInk> texts) {
        int visible = 0;
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        for (TextInk text : texts) {
            Box ink = text.ink();
            int left = Math.max(0, (int) Math.ceil(ink.x())), right = Math.min(width - 1, (int) Math.floor(ink.right()));
            int top = Math.max(0, (int) Math.ceil(ink.y())), bottom = Math.min(height - 1, (int) Math.floor(ink.bottom()));
            if (left >= right || top >= bottom) continue;
            int sample = bitmap.getColor(Math.max(0, left - 3), top + (bottom - top) / 2);
            for (int y = top; y <= bottom; y++) for (int x = left; x <= right; x++) {
                int color = bitmap.getColor(x, y);
                if (Math.abs((color >>> 16 & 255) - (sample >>> 16 & 255)) > 40
                        || Math.abs((color >>> 8 & 255) - (sample >>> 8 & 255)) > 40
                        || Math.abs((color & 255) - (sample & 255)) > 40) visible++;
            }
        }
        return visible;
    }

    private static Path output(String name) {
        String path = System.getProperty("muonium.effects.previewDir");
        return path == null || path.isBlank() ? null : Path.of(path).resolve(name + ".png");
    }

    private static void theme(EffectsWorkbench workbench, EffectsPalette palette) throws Exception {
        Method apply = EffectsWorkbench.class.getDeclaredMethod("applyPalette", EffectsPalette.class);
        apply.setAccessible(true);
        apply.invoke(workbench, palette);
    }

    private static void settle(EffectsWorkbench workbench) throws Exception {
        field(EffectsWorkbench.class, "now").setLong(workbench, field(EffectsWorkbench.class, "now").getLong(workbench) + 4_000_000_000L);
    }

    private static void scrollTo(EffectsWorkbench workbench, String name, double fraction) throws Exception {
        EffectsMotion.Scroll position = (EffectsMotion.Scroll) field(EffectsWorkbench.class, name).get(workbench);
        position.drag(position.max() * fraction - position.target(), field(EffectsWorkbench.class, "now").getLong(workbench));
    }

    private static void invoke(EffectsWorkbench workbench, String name) throws Exception {
        Method method = EffectsWorkbench.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(workbench);
    }

    @Test
    void expressiveThemesAndHostSizesRenderWithoutTextOrButtonOverflow() throws Exception {
        for (Theme theme : THEMES) {
            for (HostSize host : HOSTS) {
                for (String page : PAGES) {
                    EffectsWorkbench workbench = fixture(host.width, host.height, page);
                    try {
                        theme(workbench, theme.palette);
                        render(workbench, host.width, host.height, output("matrix-" + theme.name + "-" + host.name() + "-" + page.toLowerCase(Locale.ROOT)));
                    } finally { workbench.onHostClosed(); }
                }
            }
        }
    }

    @Test
    void allCategoriesAndDeepEditorScrollPositionsStayContained() throws Exception {
        for (ViperWorkbenchCatalog.Category category : ViperWorkbenchCatalog.Category.values()) {
            EffectsWorkbench workbench = fixture(928, 508, "EFFECTS");
            try {
                field(EffectsWorkbench.class, "category").set(workbench, category);
                render(workbench, 928, 508, output("category-" + category.name().toLowerCase(Locale.ROOT)));
                scrollTo(workbench, "scroll", 1);
                render(workbench, 928, 508, output("category-" + category.name().toLowerCase(Locale.ROOT) + "-end"));
            } finally { workbench.onHostClosed(); }
        }
        for (int width : new int[]{928, 360}) {
            for (String group : new String[]{"fetCompressor", "jdspReverb", "multibandCompressor", "dynamicEq"}) {
                EffectsWorkbench workbench = fixture(width, 508, "EFFECTS");
                try {
                    ViperPresetEditor editor = (ViperPresetEditor) field(EffectsWorkbench.class, "editor").get(workbench);
                    field(EffectsWorkbench.class, "selectedGroup").setInt(workbench, editor.groupIndex(group));
                    field(EffectsWorkbench.class, "advanced").setBoolean(workbench, true);
                    if ("dynamicEq".equals(group)) new ViperDynamicEqModel(editor).setBandCountQuiet(10);
                    for (double fraction : new double[]{0, .5, 1}) {
                        render(workbench, width, 508, null);
                        scrollTo(workbench, "scroll", fraction);
                        render(workbench, width, 508, output("editor-" + group + "-" + width + "-" + (int) (fraction * 100)));
                    }
                } finally { workbench.onHostClosed(); }
            }
        }
    }

    @Test
    void tenAndThirtyOneBandEqualizersRenderAtTopFadersAndOutput() throws Exception {
        for (int count : new int[]{10, 31}) {
            for (int width : new int[]{928, 360}) {
                EffectsWorkbench workbench = fixture(width, 560, "EQ");
                try {
                    new ViperEqualizerModel((ViperPresetEditor) field(EffectsWorkbench.class, "editor").get(workbench)).setBandCountQuiet(count);
                    for (double fraction : new double[]{0, .45, 1}) {
                        render(workbench, width, 560, null);
                        scrollTo(workbench, "scroll", fraction);
                        render(workbench, width, 560, output("eq-" + count + "bands-" + width + "-" + (int) (fraction * 100)));
                    }
                } finally { workbench.onHostClosed(); }
            }
        }
    }

    @Test
    void popupAndLongErrorDialogsUseActualAnimatedAndClippedRenderer() throws Exception {
        for (Theme theme : Arrays.copyOf(THEMES, 2)) {
            EffectsWorkbench workbench = fixture(360, 430, "EQ");
            try {
                theme(workbench, theme.palette);
                render(workbench, 360, 430, null);
                Method open = EffectsWorkbench.class.getDeclaredMethod("openEqPresets", Box.class);
                open.setAccessible(true);
                open.invoke(workbench, new Box(180, 220, 140, 32));
                settle(workbench);
                render(workbench, 360, 430, output("menu-" + theme.name));
                invoke(workbench, "closeMenu");
                field(EffectsWorkbench.class, "now").setLong(workbench, field(EffectsWorkbench.class, "now").getLong(workbench) + 60_000_000L);
                render(workbench, 360, 430, output("menu-exit-" + theme.name));
                settle(workbench);
                render(workbench, 360, 430, null);
                invoke(workbench, "openSaveAs");
                field(EffectsWorkbench.class, "dialogTitle").set(workbench, "保存为独立方案 · 不覆盖原文件与同名方案");
                field(EffectsWorkbench.class, "dialogBody").set(workbench, "保存全部效果参数并选用新方案。原文件保留，不覆盖同名文件。\n请确认完整名称与文件目录。长文本必须能滚动，底部按钮固定。");
                field(EffectsWorkbench.class, "inputError").set(workbench, "这个名字已经存在，请换一个名称。\n不要包含路径分隔符、非法字符或仅包含空白。\n这里的长错误提示必须完整显示在独立可滚动正文中，而不是盖住确认按钮。".repeat(3));
                ((TextField) field(EffectsWorkbench.class, "input").get(workbench)).setText("名称很长的独立预设 · 保留原方案 · 精细调音与空间环境测试");
                settle(workbench);
                render(workbench, 360, 430, output("dialog-long-" + theme.name));
                scrollTo(workbench, "dialogScroll", 1);
                render(workbench, 360, 430, output("dialog-long-end-" + theme.name));
                List<?> hits = (List<?>) field(EffectsWorkbench.class, "hits").get(workbench);
                Box accept = hitBox(findHit(hits, "dialog.accept"));
                Box clip = (Box) field(EffectsWorkbench.class, "dialogViewport").get(workbench);
                assertTrue(clip.bottom() <= accept.y() - 2, "长错误不能压到固定按钮");
                invoke(workbench, "closeDialog");
                field(EffectsWorkbench.class, "now").setLong(workbench, field(EffectsWorkbench.class, "now").getLong(workbench) + 60_000_000L);
                render(workbench, 360, 430, output("dialog-exit-" + theme.name));
                assertTrue(((List<?>) field(EffectsWorkbench.class, "hits").get(workbench)).isEmpty(), "退场期间不得误点下层");
            } finally { workbench.onHostClosed(); }
        }
    }

    @Test
    void pressFeedbackAndInterruptedNavigationKeepTheirActualHitTargets() throws Exception {
        EffectsWorkbench workbench = fixture(928, 508, "PRESETS");
        try {
            Frame idle = render(workbench, 928, 508, output("interaction-idle"));
            Object target = findHit((List<?>) field(EffectsWorkbench.class, "hits").get(workbench), "preset.create");
            Box targetBox = hitBox(target);
            field(EffectsWorkbench.class, "mouseX").setDouble(workbench, targetBox.centerX());
            field(EffectsWorkbench.class, "mouseY").setDouble(workbench, targetBox.centerY());
            Method sample = EffectsWorkbench.class.getDeclaredMethod("samplePress", boolean.class);
            sample.setAccessible(true);
            sample.invoke(workbench, true);
            render(workbench, 928, 508, null);
            field(EffectsWorkbench.class, "now").setLong(workbench, FRAME_TIME + 120_000_000L);
            Frame pressed = render(workbench, 928, 508, output("interaction-pressed"));
            assertNotEquals(idle.pixels, pressed.pixels);
            assertEquals(targetBox, hitBox(findHit((List<?>) field(EffectsWorkbench.class, "hits").get(workbench), "preset.create")), "按压形状不改稳定命中盒");
            sample.invoke(workbench, false);
            render(workbench, 928, 508, null);
            settle(workbench);
            render(workbench, 928, 508, output("interaction-release"));
            Field current = field(EffectsWorkbench.class, "current");
            Method navigate = EffectsWorkbench.class.getDeclaredMethod("navigate", current.getType(), int.class);
            navigate.setAccessible(true);
            @SuppressWarnings({"rawtypes", "unchecked"}) Object eq = Enum.valueOf((Class<Enum>) current.getType(), "EQ");
            @SuppressWarnings({"rawtypes", "unchecked"}) Object ambient = Enum.valueOf((Class<Enum>) current.getType(), "AMBIENT");
            navigate.invoke(workbench, eq, -1);
            for (int milliseconds : new int[]{0, 60, 120, 200, 350}) {
                field(EffectsWorkbench.class, "now").setLong(workbench, FRAME_TIME + 4_120_000_000L + milliseconds * 1_000_000L);
                render(workbench, 928, 508, output("navigation-" + milliseconds));
            }
            navigate.invoke(workbench, ambient, -1);
            field(EffectsWorkbench.class, "now").setLong(workbench, field(EffectsWorkbench.class, "now").getLong(workbench) + 60_000_000L);
            navigate.invoke(workbench, eq, -1);
            render(workbench, 928, 508, output("navigation-interrupted"));
            settle(workbench);
            render(workbench, 928, 508, output("navigation-settled"));
            assertTrue(((List<?>) field(EffectsWorkbench.class, "departing").get(workbench)).isEmpty());
            invoke(workbench, "flipReducedMotion");
            render(workbench, 928, 508, output("reduced-motion"));
            assertEquals(0, ((EffectsMotion.Spring) field(EffectsWorkbench.class, "pageOffset").get(workbench)).value(FRAME_TIME + 10_000_000_000L));
        } finally { workbench.onHostClosed(); }
    }

    @Test
    void workbenchFontsUseReadablePixelSizesInsteadOfHalfSizeNames() {
        assertEquals(20, EffectsTypography.title().sizePx, .001);
        assertEquals(20, EffectsTypography.heading().sizePx, .001);
        assertEquals(17, EffectsTypography.section().sizePx, .001);
        assertEquals(14, EffectsTypography.label().sizePx, .001);
        assertEquals(16, EffectsTypography.body().sizePx, .001);
        assertEquals(12.5, EffectsTypography.caption().sizePx, .001);
        assertEquals(12.5, EffectsTypography.captionStrong().sizePx, .001);
        assertSame(FontManager.pf32, EffectsTypography.body(), "借用已登记的字体，保持换字体与回退链一致");
        assertSame(FontManager.pf28bold, EffectsTypography.label());
        assertTrue(EffectsTypography.label().getStringWidthD("导入预设") + 42 < 156,
                "新字号与工具栏按钮必须同时适配，不能靠截断压回去");
    }

    @Test
    void lightAndDarkThemeSnapshotsRenderAndKeepSemanticMessagesLive() throws Exception {
        EffectsPalette[] palettes = {
                EffectsPalette.fromColors(0x211821, 0x30212C, 0x493342, 0x604252, 0xFFF7FB, 0xC2AAB7, 0xE48AAB),
                EffectsPalette.fromColors(0xF4F0EA, 0xFFFCF7, 0xE7DDD1, 0xD4C6B8, 0x2A2723, 0x817970, 0xB85C38)
        };
        Method apply = EffectsWorkbench.class.getDeclaredMethod("applyPalette", EffectsPalette.class);
        apply.setAccessible(true);
        for (int i = 0; i < palettes.length; i++) {
            for (String page : new String[]{"PRESETS", "EQ", "EFFECTS", "ACOUSTICS", "AMBIENT"}) {
                EffectsWorkbench workbench = fixture(928, 508, page);
                try {
                    apply.invoke(workbench, palettes[i]);
                    render(workbench, 928, 508, output("theme-" + i + "-" + page.toLowerCase()));
                    assertEquals(palettes[i].background(), field(EffectsWorkbench.class, "BACKGROUND").getInt(workbench));
                    assertEquals(palettes[i].primary(), field(EffectsWorkbench.class, "PRIMARY").getInt(workbench));
                    assertEquals(palettes[i].text(), field(EffectsWorkbench.class, "TEXT").getInt(workbench));
                } finally { workbench.onHostClosed(); }
            }
        }
        EffectsWorkbench workbench = fixture(928, 508, "PRESETS");
        try {
            Field tone = field(EffectsWorkbench.class, "noticeTone");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object success = Enum.valueOf((Class<Enum>) tone.getType(), "SUCCESS");
            tone.set(workbench, success);
            apply.invoke(workbench, palettes[0]);
            int first = field(EffectsWorkbench.class, "GREEN").getInt(workbench);
            apply.invoke(workbench, palettes[1]);
            assertNotEquals(first, field(EffectsWorkbench.class, "GREEN").getInt(workbench));
            assertSame(success, tone.get(workbench), "消息只保存语义，主题切换不得冻结旧提示色");
        } finally { workbench.onHostClosed(); }
    }

    @Test
    void shortWindowsUseTopNavigationAndNarrowEqStillOffersItsViewControl() throws Exception {
        assertFalse(EffectsLayout.calculate(1800, 450, 0).rail, "矮窗口不能裁掉侧栏底部分类");
        EffectsWorkbench workbench = fixture(360, 560, "EQ");
        try {
            render(workbench, 360, 560, output("narrow-eq-controls"));
            List<?> hits = (List<?>) field(EffectsWorkbench.class, "hits").get(workbench);
            assertNotNull(findHit(hits, "eq.view.compact"));
        } finally { workbench.onHostClosed(); }
    }

    @Test
    void wrappedNumericControlsDoNotOverlapTheirHintOrSlider() throws Exception {
        EffectsWorkbench workbench = fixture(360, 560, "EFFECTS");
        try {
            ViperPresetEditor editor = (ViperPresetEditor) field(EffectsWorkbench.class, "editor").get(workbench);
            field(EffectsWorkbench.class, "selectedGroup").set(workbench, editor.groupIndex("fetCompressor"));
            field(EffectsWorkbench.class, "advanced").set(workbench, true);
            render(workbench, 360, 560, output("narrow-fet-fields"));
            List<?> hits = (List<?>) field(EffectsWorkbench.class, "hits").get(workbench);
            EffectsLayout.Box value = hitBox(findHit(hits, "field.fetCompressor.threshold.value"));
            EffectsLayout.Box slider = hitBox(findHit(hits, "field.fetCompressor.threshold.slider"));
            assertTrue(slider.y() >= value.bottom() + 4);
            assertTrue(value.width() >= EffectsTypography.label().getStringWidthD("-18.0 dB 配置") + 12);
        } finally { workbench.onHostClosed(); }
    }

    @Test
    void renderAllMajorPagesAtWideAndCompactWidths() throws Exception {
        for (String page : new String[]{"PRESETS", "EQ", "EFFECTS", "ACOUSTICS", "AMBIENT"}) {
            for (int width : new int[]{1000, 560, 360}) {
                int height = width == 360 ? 560 : 670;
                EffectsWorkbench workbench = fixture(width, height, page);
                try { render(workbench, width, height, output(page.toLowerCase() + "-" + width)); }
                finally { workbench.onHostClosed(); }
            }
        }
    }

    @Test
    void deepEditorsAndMenusUseTheSameProductionRenderer() throws Exception {
        for (String group : new String[]{"fetCompressor", "jdspReverb", "multibandCompressor", "dynamicEq"}) {
            EffectsWorkbench workbench = fixture(1000, 670, "EFFECTS");
            ViperPresetEditor editor = (ViperPresetEditor) field(EffectsWorkbench.class, "editor").get(workbench);
            field(EffectsWorkbench.class, "selectedGroup").set(workbench, editor.groupIndex(group));
            field(EffectsWorkbench.class, "advanced").set(workbench, true);
            try { render(workbench, 1000, 670, output("detail-" + group)); }
            finally { workbench.onHostClosed(); }
        }
        EffectsWorkbench workbench = fixture(560, 670, "EQ");
        try {
            field(EffectsWorkbench.class, "dialogTitle").set(workbench, "保存为独立方案");
            field(EffectsWorkbench.class, "dialogBody").set(workbench, "保存全部效果参数并选用新方案。原文件保留，不覆盖同名文件。");
            field(EffectsWorkbench.class, "naming").set(workbench, true);
            com.muoniumplayer.core.rendering.entities.impl.TextField input = (com.muoniumplayer.core.rendering.entities.impl.TextField) field(EffectsWorkbench.class, "input").get(workbench);
            input.setText("夜间聆听 · 我的方案");
            render(workbench, 560, 670, output("save-dialog"));
        } finally { workbench.onHostClosed(); }
    }

    /** 只替代需要游戏 OpenAPI 的输入宿主入口；仍检查工作台传给它的真实设计坐标。 */
    private static final class CoordinateTextField extends com.muoniumplayer.core.rendering.entities.impl.TextField {
        private double clickX, clickY;

        CoordinateTextField() { super(0, 0, 0, 1, 1); }

        @Override
        public boolean mouseClicked(double x, double y, int button) {
            clickX = x;
            clickY = y;
            return button == 0 && x >= xPosition && x < xPosition + width
                    && y >= yPosition && y < yPosition + height;
        }
    }

    @Test
    void scaledNavigationSearchAndMenusUseTheSameCoordinatesAsDrawing() throws Exception {
        EffectsWorkbench workbench = fixture(928, 508, "PRESETS");
        try {
            workbench.setPosition(16, 18);
            render(workbench, 960, 540, null);
            List<?> hits = (List<?>) field(EffectsWorkbench.class, "hits").get(workbench);
            Object target = findHit(hits, "nav.EQ");
            EffectsLayout.Box box = hitBox(target);
            EffectsLayout.Viewport viewport = (EffectsLayout.Viewport) field(EffectsWorkbench.class, "viewport").get(workbench);
            workbench.onMouseClicked(viewport.toLayout(box.centerX()), viewport.toLayout(box.centerY()), 0);
            assertEquals("EQ", ((Enum<?>) field(EffectsWorkbench.class, "current").get(workbench)).name());
            workbench.onDWheelReceived(workbench.getX() + viewport.toLayout(box.centerX()),
                    workbench.getY() + viewport.toLayout(box.centerY()), -120);
            assertTrue(((EffectsMotion.Scroll) field(EffectsWorkbench.class, "scroll").get(workbench)).target() > 0);
        } finally { workbench.onHostClosed(); }

        EffectsWorkbench search = fixture(928, 508, "PRESETS");
        CoordinateTextField input = new CoordinateTextField();
        field(EffectsWorkbench.class, "search").set(search, input);
        try {
            search.setPosition(16, 18);
            render(search, 960, 540, null);
            EffectsLayout.Box box = hitBox(findHit((List<?>) field(EffectsWorkbench.class, "hits").get(search), "search"));
            EffectsLayout.Viewport viewport = (EffectsLayout.Viewport) field(EffectsWorkbench.class, "viewport").get(search);
            search.onMouseClicked(viewport.toLayout(box.centerX()), viewport.toLayout(box.centerY()), 0);
            assertTrue(input.isFocused());
            assertEquals(box.x() + 12, input.xPosition, .001, "输入选择也必须用设计坐标，不能叠加宿主原点");
            assertEquals(box.width() - 24, input.width, .001);
            assertEquals(box.centerX(), input.clickX, .001);
            assertEquals(box.centerY(), input.clickY, .001);
        } finally { search.onHostClosed(); }
    }

    @Test
    void scaledGraphMapsTheRenderedPointBackToItsBandGain() throws Exception {
        EffectsWorkbench workbench = fixture(928, 508, "EQ");
        try {
            render(workbench, 928, 508, null);
            Object graph = findHit((List<?>) field(EffectsWorkbench.class, "hits").get(workbench), "eq.graph");
            EffectsLayout.Box box = hitBox(graph);
            EffectsLayout.Viewport viewport = (EffectsLayout.Viewport) field(EffectsWorkbench.class, "viewport").get(workbench);
            double x = EffectsLayout.frequencyX(1000, box), y = EffectsLayout.gainY(4.5, box);
            field(EffectsWorkbench.class, "mouseX").set(workbench, viewport.toDesign(viewport.toLayout(x)));
            field(EffectsWorkbench.class, "mouseY").set(workbench, viewport.toDesign(viewport.toLayout(y)));
            Method begin = EffectsWorkbench.class.getDeclaredMethod("beginDrag", graph.getClass());
            begin.setAccessible(true);
            begin.invoke(workbench, graph);
            Method apply = EffectsWorkbench.class.getDeclaredMethod("applyDrag", double.class, double.class);
            apply.setAccessible(true);
            apply.invoke(workbench, viewport.toDesign(viewport.toLayout(x)), viewport.toDesign(viewport.toLayout(y)));
            ViperPresetEditor editor = (ViperPresetEditor) field(EffectsWorkbench.class, "editor").get(workbench);
            assertEquals(4.5, new com.muoniumplayer.core.viper.ViperEqualizerModel(editor).gains()[5], 1e-9);
            Method finish = EffectsWorkbench.class.getDeclaredMethod("finishDrag", boolean.class);
            finish.setAccessible(true);
            finish.invoke(workbench, false);
            assertEquals(.5, new com.muoniumplayer.core.viper.ViperEqualizerModel(
                    (ViperPresetEditor) field(EffectsWorkbench.class, "editor").get(workbench)).gains()[5], 1e-9);
        } finally { workbench.onHostClosed(); }
    }

    private static Object findHit(List<?> hits, String key) throws Exception {
        for (Object hit : hits) {
            Method id = hit.getClass().getDeclaredMethod("id");
            id.setAccessible(true);
            if (key.equals(id.invoke(hit))) return hit;
        }
        throw new AssertionError("缺少命中目标：" + key);
    }

    private static EffectsLayout.Box hitBox(Object hit) throws Exception {
        Method box = hit.getClass().getDeclaredMethod("box");
        box.setAccessible(true);
        return (EffectsLayout.Box) box.invoke(hit);
    }

    @Test
    void graphHitsAreClippedAndGlobalWheelIsDeliveredOnlyOnce() throws Exception {
        EffectsWorkbench workbench = fixture(560, 670, "EQ");
        try {
            render(workbench, 560, 670, null);
            assertTrue(workbench.getChildren().isEmpty(), "不能生成第二条 ScrollPanel 滚轮派发路径");
            List<?> hits = (List<?>) field(EffectsWorkbench.class, "hits").get(workbench);
            Object graph = hits.stream().filter(hit -> {
                try { Method id = hit.getClass().getDeclaredMethod("id"); id.setAccessible(true); return "eq.graph".equals(id.invoke(hit)); }
                catch (Exception failure) { throw new RuntimeException(failure); }
            }).findFirst().orElseThrow();
            Method contains = graph.getClass().getDeclaredMethod("contains", double.class, double.class);
            contains.setAccessible(true);
            assertFalse((boolean) contains.invoke(graph, 280, 40), "图表不应接收工具栏的按下");
            EffectsLayout layout = (EffectsLayout) field(EffectsWorkbench.class, "layout").get(workbench);
            EffectsLayout.Viewport viewport = (EffectsLayout.Viewport) field(EffectsWorkbench.class, "viewport").get(workbench);
            double x = viewport.toLayout(layout.viewport.centerX()), y = viewport.toLayout(layout.viewport.centerY());
            EffectsMotion.Scroll scroll = (EffectsMotion.Scroll) field(EffectsWorkbench.class, "scroll").get(workbench);
            assertEquals(0, scroll.target());
            workbench.onDWheelReceived(x, y, -120);
            assertEquals(54, scroll.target(), 1e-9);
            workbench.setInteractionOwned(false);
            workbench.onDWheelReceived(x, y, -120);
            assertEquals(54, scroll.target(), 1e-9, "盖住工作台后禁止滚轮和原始拖动");
        } finally { workbench.onHostClosed(); }
    }
}
