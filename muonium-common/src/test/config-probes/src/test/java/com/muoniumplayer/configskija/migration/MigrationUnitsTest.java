package com.muoniumplayer.configskija.migration;

import com.muoniumplayer.configskija.host.migration.LegacyMigration;
import com.muoniumplayer.configskija.model.HudDesigner;
import com.muoniumplayer.configskija.ui.hud.HudDocument;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Original GUI-axis formulas compared in physical pixels across independently varied layout scales. */
public final class MigrationUnitsTest {
    private static int assertions, matrixCases;
    private static final List<String> passed = new ArrayList<>();
    private static final double PHYSICAL_WIDTH = 1920, PHYSICAL_HEIGHT = 1080, CUSTOM_SCALE = 1.5;
    private static final List<String> SECTIONS = List.of("TopLeft", "TopCenter", "TopRight", "CenterLeft", "Center", "CenterRight", "BottomLeft", "BottomCenter", "BottomRight");
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(output);
        compatibility(output); passed.add("request-compatible-constructors-and-positive-physical-unit");
        sectionMatrix(output); passed.add("nine-section-physical-matrix-with-independent-gui-and-layout-scales");
        absoluteAndLinks(output); passed.add("old-absolute-gui-coordinates-and-unscaled-gui-anchor-offsets");
        System.out.println("MigrationUnitsTest PASS: " + passed.size() + " groups, " + matrixCases + " matrix cases, " + assertions + " assertions");
        passed.forEach(name -> System.out.println("PASS " + name));
    }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static void near(double actual, double expected, String message) { assertions++; if (!Double.isFinite(actual) || Math.abs(actual - expected) > 1e-7) throw new AssertionError(message + ": expected=" + expected + " actual=" + actual); }
    private static void equal(Object actual, Object expected, String message) { assertions++; if (!java.util.Objects.equals(actual, expected)) throw new AssertionError(message + ": expected=" + expected + " actual=" + actual); }
    private static Path fixture(Path output, String name) throws Exception { Path root = Files.createTempDirectory(output, name + "-"); Files.createDirectories(root.resolve("config/huds")); return root; }
    private static void write(Path root, String name, String data) throws Exception { Files.writeString(root.resolve("config/huds/" + name), data, StandardCharsets.UTF_8); }
    private static String source(String section, boolean gui) {
        return "{\"hudClass\":\"org.polyfrost.oneconfig.api.hud.v1.TextHud$Simple\",\"staticWidth\":true,\"staticW\":100,\"staticH\":30,\"customScale\":1.5,\"useGuiScale\":" + gui
                + ",\"section\":{\"class\":\"org.polyfrost.oneconfig.api.hud.v1.Section\",\"value\":\"" + section + "\"},\"posSchema\":1,\"relativeX\":20.7,\"relativeY\":20.3,\"growthAnchor\":{\"class\":\"org.polyfrost.oneconfig.api.hud.v1.HudAnchor\",\"value\":\"Auto\"}}";
    }
    private static void compatibility(Path output) throws Exception {
        Path root = fixture(output, "constructors");
        LegacyMigration.Request legacy = new LegacyMigration.Request(root, 640, 360, 3);
        near(legacy.physicalPerUnit(), 3, "four-argument request remains GUI-axis");
        Map<String,String> types = Map.of("old.Provider", "text"); Set<String> mods = Set.of("muonium.hud");
        LegacyMigration.Request options = new LegacyMigration.Request(root, 640, 360, 3, types, mods, false);
        near(options.physicalPerUnit(), 3, "seven-argument request remains GUI-axis"); equal(options.hudTypes(), types, "old type mapping retained"); equal(options.knownModIds(), mods, "old mod whitelist retained"); check(!options.includeProfiles(), "old includeProfiles retained");
        LegacyMigration.Request layout = new LegacyMigration.Request(root, 960, 540, 3, 2);
        near(layout.guiScale(), 3, "real GUI scale independent"); near(layout.physicalPerUnit(), 2, "explicit physical-per-layout scale");
        LegacyMigration.Request all = new LegacyMigration.Request(root, 960, 540, 3, 2, types, mods, false);
        near(all.physicalPerUnit(), 2, "explicit full request physical scale"); equal(all.hudTypes(), types, "full mapping unchanged");
        for (double bad : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            boolean rejected = false; try { new LegacyMigration.Request(root, 960, 540, 3, bad); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "invalid physicalPerUnit rejected: " + bad);
        }
    }
    private static void sectionMatrix(Path output) throws Exception {
        Path root = fixture(output, "nine-sections");
        for (int section = 0; section < SECTIONS.size(); section++) for (boolean gui : new boolean[]{true, false}) write(root, "s" + section + (gui ? "-gui" : "-physical") + ".json", source(SECTIONS.get(section), gui));
        for (double guiScale : new double[]{1, 1.5, 2, 3, 4}) for (double physicalPerUnit : new double[]{1, 1.25, 1.5, 2, 3, 4}) {
            double width = PHYSICAL_WIDTH / physicalPerUnit, height = PHYSICAL_HEIGHT / physicalPerUnit;
            LegacyMigration.Snapshot snapshot = LegacyMigration.read(new LegacyMigration.Request(root, width, height, guiScale, physicalPerUnit));
            equal(snapshot.issues(), List.of(), "static matrix fixtures require no provisional geometry or conversion warnings");
            equal(snapshot.hud().elements().size(), 18, "all section/scale modes converted");
            for (HudDesigner.State state : snapshot.hud().elements()) {
                int section = Integer.parseInt(state.properties().get("section")); int column = section % 3, row = section / 3;
                boolean gui = Boolean.parseBoolean(state.properties().get("useGuiScale"));
                double expectedWidth = 100 * CUSTOM_SCALE * (gui ? guiScale : 1), expectedHeight = 30 * CUSTOM_SCALE * (gui ? guiScale : 1);
                double pinX = column == 0 ? 21 * guiScale : column == 1 ? PHYSICAL_WIDTH / 2 + 21 * guiScale : PHYSICAL_WIDTH - 21 * guiScale;
                double pinY = row == 0 ? 20 * guiScale : row == 1 ? PHYSICAL_HEIGHT / 2 + 20 * guiScale : PHYSICAL_HEIGHT - 20 * guiScale;
                near(state.width() * physicalPerUnit, expectedWidth, "physical width " + guiScale + "/" + physicalPerUnit + "/" + section + "/" + gui);
                near(state.height() * physicalPerUnit, expectedHeight, "physical height");
                near(state.x() * (width - state.width()) * physicalPerUnit, pinX - column * .5 * expectedWidth, "physical x keeps source GUI section anchor");
                near(state.y() * (height - state.height()) * physicalPerUnit, pinY - row * .5 * expectedHeight, "physical y keeps source GUI section anchor");
                near(Double.parseDouble(state.properties().get("relativeX")), 21, "stored x remains rounded GUI scalar, not pre-scaled layout x");
                near(Double.parseDouble(state.properties().get("relativeY")), 20, "stored y remains rounded GUI scalar");
                near(Double.parseDouble(state.properties().get("staticW")), 100, "static intrinsic width is not pre-scaled");
                near(Double.parseDouble(state.properties().get("staticH")), 30, "static intrinsic height is not pre-scaled");
                near(state.scale(), CUSTOM_SCALE, "custom scale unchanged");
                matrixCases++;
            }
            equal(HudDocument.decode(snapshot.hud().encode()), snapshot.hud(), "document roundtrip preserves separated units");
        }
        // Existing callers which already supply GUI viewport dimensions keep their original semantics.
        write(root, "absolute.json", "{\"hudClass\":\"org.polyfrost.oneconfig.api.hud.v1.TextHud$Simple\",\"staticWidth\":true,\"staticW\":100,\"staticH\":30,\"useGuiScale\":true,\"customScale\":1.5,\"x\":123,\"y\":45,\"growthAnchor\":{\"value\":\"TopLeft\"}}");
        LegacyMigration.Snapshot legacy = LegacyMigration.read(new LegacyMigration.Request(root, 640, 360, 3));
        HudDesigner.State absolute = legacy.hud().elements().stream().filter(s -> s.id().equals("huds/absolute.json")).findFirst().orElseThrow();
        near(absolute.width(), 150, "legacy GUI-axis request width"); near(absolute.x() * (640 - absolute.width()), 123, "legacy GUI-axis request x");
    }
    private static void absoluteAndLinks(Path output) throws Exception {
        Path root = fixture(output, "absolute-link");
        write(root, "absolute.json", "{\"hudClass\":\"org.polyfrost.oneconfig.api.hud.v1.TextHud$Simple\",\"staticWidth\":true,\"staticW\":100,\"staticH\":30,\"customScale\":1.5,\"useGuiScale\":false,\"x\":123,\"y\":45,\"growthAnchor\":{\"value\":\"TopLeft\"}}");
        String child = source("TopLeft", true); child = child.substring(0, child.length()-1) + ",\"anchorTargetId\":\"huds/absolute.json\",\"anchorPoint\":{\"value\":\"BottomRight\"},\"selfAnchorPoint\":{\"value\":\"TopLeft\"},\"anchorOffsetX\":7.5,\"anchorOffsetY\":-2.25}";
        write(root, "child.json", child);
        for (double guiScale : new double[]{1, 2, 3, 4}) for (double physicalPerUnit : new double[]{1, 1.5, 2, 3, 4}) {
            double width = PHYSICAL_WIDTH / physicalPerUnit, height = PHYSICAL_HEIGHT / physicalPerUnit;
            LegacyMigration.Snapshot snapshot = LegacyMigration.read(new LegacyMigration.Request(root, width, height, guiScale, physicalPerUnit));
            equal(snapshot.issues(), List.of(), "absolute/link conversion has no unknown units");
            HudDesigner.State absolute = snapshot.hud().elements().stream().filter(s -> s.id().equals("huds/absolute.json")).findFirst().orElseThrow();
            near(absolute.x() * (width - absolute.width()) * physicalPerUnit, 123 * guiScale, "absolute old GUI x converts once");
            near(absolute.y() * (height - absolute.height()) * physicalPerUnit, 45 * guiScale, "absolute old GUI y converts once");
            near(absolute.width() * physicalPerUnit, 150, "fixed physical-size mode stays independent of GUI scale");
            HudDesigner.State anchored = snapshot.hud().elements().stream().filter(s -> s.id().equals("huds/child.json")).findFirst().orElseThrow();
            near(anchored.anchor().offsetX(), 7.5, "anchor x stays GUI scalar for runtime link conversion");
            near(anchored.anchor().offsetY(), -2.25, "anchor y stays GUI scalar");
            equal(anchored.anchor().parent(), absolute.id(), "parent identity unchanged by unit conversion");
            matrixCases++;
        }
    }
}
