package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.render.skija.SkijaNatives;
import com.muoniumplayer.testsupport.RequiresSkijaNative;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.EncoderPNG;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Surface;
import net.minecraft.client.Minecraft;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic raster evidence for the native Muonium settings surface.
 *
 * <p>This deliberately does not start Minecraft. It loads the production Skija font stack,
 * renders the real settings model through {@link MConfigHudScreen#renderProbe}, writes PNGs,
 * and rejects blank or nearly blank frames.</p>
 */
@RequiresSkijaNative
@EnabledOnOs(OS.WINDOWS)
class MConfigHudRenderProbeTest {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 800;
    private static Field minecraftSingleton;
    private static Object previousMinecraft;
    private static Unsafe unsafe;

    @BeforeAll
    static void setupSkijaAndFonts() {
        assertTrue(SkijaNatives.ensureLoaded(),
                "Skija native library failed to load: " + SkijaNatives.failure());
        seedScreenHostWithoutStartingMinecraft();
        FontManager.loadFonts();
    }

    @AfterAll
    static void restoreMinecraftSingleton() {
        if (minecraftSingleton == null || unsafe == null) return;
        unsafe.putObject(unsafe.staticFieldBase(minecraftSingleton),
                unsafe.staticFieldOffset(minecraftSingleton), previousMinecraft);
    }

    /**
     * The production screen inherits Minecraft's Screen constructor, which only needs the
     * client font for this raster probe. Seed that narrow host contract without constructing
     * a client, window, GL context, world, or network session.
     */
    private static void seedScreenHostWithoutStartingMinecraft() {
        try {
            unsafe = unsafe();
            for (Field field : Minecraft.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == Minecraft.class) {
                    minecraftSingleton = field;
                    break;
                }
            }
            assertTrue(minecraftSingleton != null, "Minecraft singleton field was not found");
            previousMinecraft = unsafe.getObject(unsafe.staticFieldBase(minecraftSingleton),
                    unsafe.staticFieldOffset(minecraftSingleton));
            Minecraft fakeClient = (Minecraft) unsafe.allocateInstance(Minecraft.class);
            unsafe.putObject(unsafe.staticFieldBase(minecraftSingleton),
                    unsafe.staticFieldOffset(minecraftSingleton), fakeClient);

            Field font = Minecraft.class.getDeclaredField("font");
            Object fakeFont = unsafe.allocateInstance(font.getType());
            unsafe.putObject(fakeClient, unsafe.objectFieldOffset(font), fakeFont);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("could not seed the Screen font contract", exception);
        }
    }

    private static Unsafe unsafe() throws ReflectiveOperationException {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    @Test
    void rendersEveryCategoryAndInteractiveSurfaceToPng() throws Exception {
        Path output = Path.of(System.getProperty("muonium.mconfig.probeDir",
                Path.of("build", "mconfig-probe").toString()));
        Files.createDirectories(output);

        HudSettingsModel.Category[] categories = HudSettingsModel.categories();
        assertTrue(categories.length > 0, "settings model must expose at least one category");
        for (int index = 0; index < categories.length; index++) {
            renderAndAssert(output.resolve(String.format("category-%02d.png", index)),
                    index, "page");
        }

        renderAndAssert(output.resolve("search.png"), 0, "search");
        renderAndAssert(output.resolve("dropdown.png"), 0, "dropdown");
        renderAndAssert(output.resolve("color-picker.png"), 0, "color-picker");
        renderAndAssert(output.resolve("reset-dialog.png"), 0, "reset-dialog");
        renderAndAssert(output.resolve("hud-editor.png"), 0, "hud-editor");
    }

    private static void renderAndAssert(Path output, int category, String surfaceName) throws Exception {
        try (Surface surface = Surface.makeRasterN32Premul(WIDTH, HEIGHT)) {
            assertTrue(MConfigHudScreen.renderProbe(surface.getCanvas(), WIDTH, HEIGHT,
                    category, surfaceName), "probe rejected surface " + surfaceName);
            try (Image image = surface.makeImageSnapshot();
                 Data png = EncoderPNG.encode(image)) {
                assertTrue(png != null && png.getBytes().length > 4096,
                        "PNG output is empty for " + surfaceName);
                Files.write(output, png.getBytes());
            }
            int[] bounds = nonBackgroundBounds(surface);
            assertTrue(bounds[2] > 80 && bounds[3] > 80,
                    "surface is blank or too small for " + surfaceName
                            + " bounds=" + bounds[0] + "," + bounds[1]
                            + "," + bounds[2] + "," + bounds[3]);
        }
        System.out.println("[MConfig probe] " + surfaceName + " -> " + output.toAbsolutePath());
    }

    private static int[] nonBackgroundBounds(Surface surface) {
        io.github.humbleui.skija.Bitmap bitmap = new io.github.humbleui.skija.Bitmap();
        bitmap.allocN32Pixels(WIDTH, HEIGHT, false);
        assertTrue(surface.readPixels(bitmap, 0, 0), "could not read raster surface");
        int left = WIDTH;
        int top = HEIGHT;
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int color = bitmap.getColor(x, y);
                int r = (color >>> 16) & 0xFF;
                int g = (color >>> 8) & 0xFF;
                int b = color & 0xFF;
                if (Math.abs(r - 11) + Math.abs(g - 16) + Math.abs(b - 23) > 18) {
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        bitmap.close();
        return new int[] { left, top, right < 0 ? 0 : right - left + 1,
                bottom < 0 ? 0 : bottom - top + 1 };
    }
}
