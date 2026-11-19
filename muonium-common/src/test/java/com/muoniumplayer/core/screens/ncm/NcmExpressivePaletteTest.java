package com.muoniumplayer.core.screens.ncm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure color tests: no live theme/configuration, Minecraft bootstrap, fonts or GL. */
class NcmExpressivePaletteTest {

    @Test
    void everyActualThemePresetKeepsReadableTextOnPromptSurfaces() {
        List<Executable> checks = new ArrayList<>();
        // Use the enum's real getColor contract, not copied color tuples or setCurrent (which writes config).
        for (NCMTheme.ThemePreset preset : NCMTheme.ThemePreset.values()) {
            NcmExpressivePalette palette = fromPreset(preset);
            checks.add(() -> assertEquals(0xFF000000 | preset.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND),
                    palette.background(), preset.name() + " must retain its page color"));
            int[] backgrounds = {palette.surface(), palette.container(), palette.input(), palette.primaryContainer()};
            String[] names = {"surface", "container", "input", "primaryContainer"};
            for (int index = 0; index < backgrounds.length; index++) {
                int background = backgrounds[index];
                String pair = preset.name() + "/" + names[index];
                checks.add(() -> assertReadable(palette.text(), background, pair + "/text"));
                checks.add(() -> assertReadable(palette.muted(), background, pair + "/muted"));
            }
            checks.add(() -> assertReadable(palette.containerInk(), palette.container(), preset.name() + "/containerInk"));
        }
        assertAll("All real preset text pairs", checks);
    }

    @Test
    void theSameOnPrimaryInkIsReadableOnNormalAndHoveredPrimaryActions() {
        List<Executable> checks = new ArrayList<>();
        for (NCMTheme.ThemePreset preset : NCMTheme.ThemePreset.values()) {
            NcmExpressivePalette palette = fromPreset(preset);
            checks.add(() -> assertReadable(palette.onPrimary(), palette.primary(), preset.name() + "/primary"));
            checks.add(() -> assertReadable(palette.onPrimary(), palette.primaryHover(), preset.name() + "/primaryHover"));
        }
        assertAll("Hover is not allowed to invalidate the primary caption", checks);
    }

    @Test
    void semanticInkIsRecheckedAgainstTheActualCustomMixedSurface() {
        List<NcmExpressivePalette> palettes = new ArrayList<>();
        for (NCMTheme.ThemePreset preset : NCMTheme.ThemePreset.values()) palettes.add(fromPreset(preset));
        // User/cover colors can disagree with preset colors. Exercise both light and dark coherent surfaces,
        // including low-contrast requested ink, without altering the live theme or persistent custom colors.
        palettes.add(NcmExpressivePalette.fromColors(0x101010, 0x303030, 0x555555, 0x333333,
                0xCCCCCC, 0x222222, 0x333333, 0x555555, 0x151515));
        palettes.add(NcmExpressivePalette.fromColors(0xEEEEEE, 0xDDDDDD, 0xCCCCCC, 0xAAAAAA,
                0x555555, 0xCCCCCC, 0xEEEE99, 0xCCDDFF, 0xFFFFFF));
        List<Executable> checks = new ArrayList<>();
        for (int index = 0; index < palettes.size(); index++) {
            NcmExpressivePalette palette = palettes.get(index);
            String name = "palette " + index;
            for (int semantic : new int[]{palette.error(), palette.warning()}) {
                for (int base : new int[]{palette.surface(), palette.container()}) {
                    // Update feedback .08, generic status .10, danger action .11/.20.
                    for (double fraction : new double[]{.08, .10, .11, .20}) {
                        int actualSurface = NcmExpressivePalette.mix(base, semantic, fraction);
                        checks.add(() -> {
                            int foreground = NcmExpressivePalette.readableColor(semantic, actualSurface);
                            assertReadable(foreground, actualSurface, name + "/semantic mix " + fraction);
                            assertEquals(255, foreground >>> 24, "Semantic ink must be opaque ARGB");
                            if (wcagContrast(semantic, actualSurface) >= 4.5) {
                                assertEquals(semantic, foreground, "Already-readable semantic colors must not drift");
                            }
                        });
                        checks.add(() -> assertReadable(
                                NcmExpressivePalette.readableColor(palette.muted(), actualSurface), actualSurface,
                                name + "/status caption mix " + fraction));
                    }
                }
            }
        }
        assertAll("Real readableColor over mixed semantic surfaces", checks);
    }

    @Test
    void paletteMathAndTokensStayOpaqueAndUseTheSuppliedSnapshot() {
        for (NCMTheme.ThemePreset preset : NCMTheme.ThemePreset.values()) {
            NcmExpressivePalette palette = fromPreset(preset);
            for (int color : new int[]{palette.background(), palette.surface(), palette.container(), palette.input(),
                    palette.outline(), palette.text(), palette.muted(), palette.primary(), palette.primaryContainer(),
                    palette.primaryHover(), palette.onPrimary(), palette.containerInk(), palette.success(),
                    palette.warning(), palette.error()}) {
                assertEquals(255, color >>> 24, preset.name() + " has a transparent color token");
            }
        }
        NcmExpressivePalette warm = NcmExpressivePalette.fromColors(0xFFFFFF, 0xF5F5F5, 0xEAEAEA,
                0x666666, 0x202020, 0x555555, 0x775522, 0x553311, 0xEEE0C0);
        NcmExpressivePalette cool = NcmExpressivePalette.fromColors(0xFFFFFF, 0xF5F5F5, 0xEAEAEA,
                0x666666, 0x202020, 0x555555, 0x226677, 0x113355, 0xE0EEEE);
        assertNotEquals(warm.primary(), cool.primary(), "Accent cannot come from a cached/live theme");
        assertNotEquals(warm.primaryHover(), cool.primaryHover(), "Hover must use the same supplied snapshot");
        assertNotEquals(warm.input(), cool.input(), "Input background must use the supplied snapshot");
        assertEquals(0xFF123456, NcmExpressivePalette.mix(0x123456, 0xABCDEF, -1));
        assertEquals(0xFFABCDEF, NcmExpressivePalette.mix(0x123456, 0xABCDEF, 2));
        assertEquals(21.0, NcmExpressivePalette.contrast(0xFFFFFFFF, 0xFF000000), 1e-9);
        assertEquals(1.0, NcmExpressivePalette.contrast(0xFF123456, 0xFF123456), 1e-9);
        assertEquals(wcagContrast(0xFF775522, 0xFFF5F5F5),
                NcmExpressivePalette.contrast(0xFF775522, 0xFFF5F5F5), 1e-9);
    }

    private static NcmExpressivePalette fromPreset(NCMTheme.ThemePreset preset) {
        return NcmExpressivePalette.fromColors(
                preset.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND),
                preset.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                preset.getColor(NCMScreen.ColorType.ELEMENT_HOVER),
                preset.getColor(NCMScreen.ColorType.BORDER),
                preset.getColor(NCMScreen.ColorType.PRIMARY_TEXT),
                preset.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                preset.getColor(NCMScreen.ColorType.ACCENT),
                preset.getColor(NCMScreen.ColorType.ACCENT_HOVER),
                preset.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
    }

    private static void assertReadable(int foreground, int background, String pair) {
        double ratio = wcagContrast(foreground, background);
        assertTrue(ratio >= 4.5, () -> String.format("%s contrast %.6f < 4.5 (ink #%08X on #%08X)",
                pair, ratio, foreground, background));
    }

    // Independent WCAG sRGB calculation: a regression in production contrast() cannot green its own tests.
    private static double wcagContrast(int a, int b) {
        double first = luminance(a), second = luminance(b);
        return (Math.max(first, second) + .05) / (Math.min(first, second) + .05);
    }

    private static double luminance(int color) {
        return .2126 * channel((color >>> 16) & 255) + .7152 * channel((color >>> 8) & 255)
                + .0722 * channel(color & 255);
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= .04045 ? c / 12.92 : Math.pow((c + .055) / 1.055, 2.4);
    }
}
