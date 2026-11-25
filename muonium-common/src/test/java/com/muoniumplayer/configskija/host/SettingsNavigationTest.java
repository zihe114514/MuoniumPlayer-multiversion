package com.muoniumplayer.configskija.host;

import com.muoniumplayer.configskija.model.ConfigHost;
import com.muoniumplayer.configskija.model.HudElement;
import com.muoniumplayer.configskija.model.Navigation;
import com.muoniumplayer.configskija.model.UiPreferences;
import com.muoniumplayer.configskija.ui.ConfigView;
import com.muoniumplayer.configskija.ui.Geometry;
import com.muoniumplayer.configskija.ui.Painter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SettingsNavigationTest {
    private static final double WIDTH = 1728, HEIGHT = 960;

    @Test
    void everyHostCategoryHasDistinctStableIdentity() throws Exception { ConfigProbeProcess.run(SettingsNavigationTest.class, "identity"); }

    private void runEveryHostCategoryHasDistinctStableIdentity() {
        List<ConfigHost.Category> first = host().categories();
        List<ConfigHost.Category> second = host().categories();
        Set<String> categories = new HashSet<>(), groups = new HashSet<>(), options = new HashSet<>();
        assertTrue(first.size() >= 10, "真实播放器分类应全部装入内置设置中心");
        assertEquals(first.stream().map(ConfigHost.Category::id).toList(), second.stream().map(ConfigHost.Category::id).toList());
        for (ConfigHost.Category category : first) {
            assertFalse(category.id().isBlank(), category.title());
            assertTrue(categories.add(category.id()), "重复分类路由: " + category.title());
            assertFalse(category.groups().isEmpty(), category.title());
            for (ConfigHost.Group group : category.groups()) {
                assertTrue(groups.add(group.id()), "重复分组: " + category.title() + "/" + group.title());
                for (ConfigHost.Option option : group.options()) assertTrue(options.add(option.id()), option.id());
            }
        }
    }

    @Test
    void clickingEveryCategoryShowsItsOwnOptionsAndResetsScroll() throws Exception { ConfigProbeProcess.run(SettingsNavigationTest.class, "clicks"); }

    private void runClickingEveryCategoryShowsItsOwnOptionsAndResetsScroll() {
        List<ConfigHost.Category> categories = host().categories();
        ConfigView view = view(categories);
        RecordingPainter painter = new RecordingPainter();
        view.navigate(new Navigation.Route(Navigation.Page.SETTINGS));
        render(view, painter, 1);
        for (int index = 0; index < categories.size(); index++) {
            ConfigHost.Category category = categories.get(index);
            view.scroll(100000);
            click(view, "settings.category." + category.id());
            painter.text.clear();
            render(view, painter, 2 + index);
            assertEquals(category.id(), view.route().category(), category.title());
            assertEquals(0, view.scroll(), .001, category.title() + " 沿用了上一页的滚动位置");
            Set<String> expected = category.groups().stream().flatMap(group -> group.options().stream()).map(ConfigHost.Option::title).collect(Collectors.toSet());
            assertTrue(painter.text.stream().anyMatch(expected::contains), category.title() + " 没有绘制本页选项");
            Set<String> ownIds = category.groups().stream().flatMap(group -> group.options().stream()).map(ConfigHost.Option::id).collect(Collectors.toSet());
            assertTrue(view.hitBounds().keySet().stream().anyMatch(id -> ownIds.stream().anyMatch(id::endsWith)), category.title() + " 没有本页可交互选项");
        }
    }

    @Test
    void categoryHistoryRestoresItsOwnScrollAndSearch() throws Exception { ConfigProbeProcess.run(SettingsNavigationTest.class, "history"); }

    private void runCategoryHistoryRestoresItsOwnScrollAndSearch() {
        List<ConfigHost.Category> categories = host().categories();
        ConfigView view = view(categories);
        RecordingPainter painter = new RecordingPainter();
        view.navigate(new Navigation.Route(Navigation.Page.SETTINGS, categories.get(1).id()));
        render(view, painter, 1);
        view.scroll(120);
        double remembered = view.scroll();
        assertTrue(remembered > 0);
        click(view, "settings.category." + categories.get(2).id());
        render(view, painter, 2);
        assertEquals(0, view.scroll());
        click(view, "header:back");
        render(view, painter, 3);
        assertEquals(categories.get(1).id(), view.route().category());
        assertEquals(remembered, view.scroll(), .001);
        click(view, "header:forward");
        render(view, painter, 4);
        assertEquals(categories.get(2).id(), view.route().category());
        assertEquals(0, view.scroll());
    }

    public static void main(String[] args) {
        SettingsNavigationTest test = new SettingsNavigationTest();
        switch (args[0]) {
            case "identity" -> test.runEveryHostCategoryHasDistinctStableIdentity();
            case "clicks" -> test.runClickingEveryCategoryShowsItsOwnOptionsAndResetsScroll();
            case "history" -> test.runCategoryHistoryRestoresItsOwnScrollAndSearch();
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    private static MuoniumHost host() { return new MuoniumHost(() -> {}, preferences()); }
    private static UiPreferences preferences() {
        return new UiPreferences(Map.of("guiOpenAnimation", "false", "showPageAnimations", "false", "instantSearch", "false", "rememberLastPage", "0"), () -> {});
    }
    private static ConfigView view(List<ConfigHost.Category> categories) {
        // Actual host categories/identities with side-effect-free values: no world, audio or network is started.
        List<ConfigHost.Category> safe = categories.stream().map(category -> new ConfigHost.Category(category.id(), category.title(), category.subtitle(), category.icon(), category.groups().stream().map(group -> new ConfigHost.Group(group.id(), group.title(), group.options().stream().map(option -> new ConfigHost.Option(option.id(), option.title(), option.description(), ConfigHost.Kind.TOGGLE, 0, 1, 1, List.of(), () -> false, value -> {}, () -> "", () -> {})).toList())).toList())).toList();
        ConfigHost host = new ConfigHost() {
            public List<Category> categories() { return safe; }
            public List<HudElement> huds() { return List.of(); }
            public void persist() {}
            public void close() {}
            public String clipboard() { return ""; }
            public void clipboard(String value) {}
            public void error(String action, Throwable error) { throw new AssertionError(action, error); }
        };
        return new ConfigView(host, preferences());
    }
    private static void render(ConfigView view, Painter painter, double time) {
        view.frame(painter, WIDTH, HEIGHT, -100, -100, time);
    }
    private static void click(ConfigView view, String id) {
        Geometry.Rect hit = view.hitBounds().get(id);
        assertNotNull(hit, "找不到点击目标 " + id);
        Geometry.Point point = view.viewport().screen(hit.x() + hit.w() / 2, hit.y() + hit.h() / 2);
        assertTrue(view.pointerDown(point.x(), point.y(), 0, false, false, false));
        view.pointerUp(point.x(), point.y(), 0);
    }
    private static final class RecordingPainter implements Painter {
        final List<String> text = new ArrayList<>();
        public void save() {}
        public void restore() {}
        public void translate(double x, double y) {}
        public void scale(double x, double y) {}
        public void clip(Geometry.Rect bounds, double radius) {}
        public void rect(Geometry.Rect bounds, double radius, int color) {}
        public void outline(Geometry.Rect bounds, double radius, double width, int color) {}
        public void line(double x, double y, double endX, double endY, double width, int color) {}
        public void text(String value, double x, double y, double size, boolean bold, int color) { text.add(value); }
        public double measure(String value, double size, boolean bold) { return value.codePointCount(0, value.length()) * size * .65; }
    }
}
