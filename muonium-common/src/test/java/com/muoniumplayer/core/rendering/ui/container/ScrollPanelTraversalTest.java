package com.muoniumplayer.core.rendering.ui.container;

import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScrollPanelTraversalTest {

    private static final class ProbeRow extends AbstractWidget<ProbeRow> {
        final int index;
        final List<Integer> rendered;
        int exits;

        ProbeRow(int index, List<Integer> rendered) {
            this.index = index;
            this.rendered = rendered;
            this.setBounds(100, 30);
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            this.rendered.add(this.index);
        }

        @Override
        public void onViewportExit() {
            this.exits++;
        }
    }

    private static final class ProbePanel extends ScrollPanel {
        int candidates;

        @Override
        public void onRender(double mouseX, double mouseY) {
            this.alignChildren();
        }

        @Override
        protected void beforeRenderChildren(double mouseX, double mouseY) {
        }

        @Override
        protected void afterRenderChildren(double mouseX, double mouseY) {
        }

        @Override
        protected boolean shouldRenderChildren(AbstractWidget<?> child, double mouseX, double mouseY) {
            this.candidates++;
            return super.shouldRenderChildren(child, mouseX, mouseY);
        }

        List<AbstractWidget<?>> renderChildren() {
            return this.getRenderChildren();
        }
    }

    private static ProbePanel panel(int count, List<Integer> rendered) {
        ProbePanel panel = new ProbePanel();
        panel.setBounds(100, 90);
        panel.setUniformVerticalItemHeight(30);
        List<AbstractWidget<?>> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new ProbeRow(i, rendered));
        }
        panel.addChild(rows);
        return panel;
    }

    @Test
    void tenThousandRowsVisitOnlyTheOrderedViewportAndPreserveLegacyVisibility() {
        List<Integer> rendered = new ArrayList<>();
        ProbePanel panel = panel(10_000, rendered);
        panel.actualScrollOffset = 9_000;
        panel.targetScrollOffset = 9_000;
        panel.renderWidget(-100, -100, 0);

        assertEquals(10_000, panel.getChildren().size());
        assertEquals(6, panel.candidates);
        assertEquals(List.of(299, 300, 301, 302, 303), rendered);
        assertEquals(List.of(299, 300, 301, 302, 303, 304),
                panel.renderChildren().stream().map(row -> ((ProbeRow) row).index).toList());
    }

    @Test
    void jumpingAwayClearsHoverAndTooltipTimingWithoutVisitingAllRows() throws Exception {
        List<Integer> rendered = new ArrayList<>();
        ProbePanel panel = panel(10_000, rendered);
        panel.renderWidget(10, 10, 0);
        ProbeRow first = (ProbeRow) panel.getChildren().get(0);
        ProbeRow nested = new ProbeRow(-1, new ArrayList<>());
        first.addChild(nested);
        setField(first, "hovering", true);
        setField(first, "tooltipHoverSince", 123L);
        setField(nested, "hovering", true);
        setField(nested, "tooltipHoverSince", 123L);
        int exitsBefore = first.exits;

        rendered.clear();
        panel.candidates = 0;
        panel.actualScrollOffset = 90_000;
        panel.targetScrollOffset = 90_000;
        panel.renderWidget(-100, -100, 0);

        assertFalse(first.isHovering());
        assertFalse(nested.isHovering());
        assertEquals(0L, getField(first, "tooltipHoverSince"));
        assertEquals(0L, getField(nested, "tooltipHoverSince"));
        assertEquals(exitsBefore + 1, first.exits);
        assertTrue(first.getRelativeY() < -100_000);
        assertEquals(6, panel.candidates);
    }

    @Test
    void replacementAndEmptyListsReleaseThePreviousVisibleRows() throws Exception {
        ProbePanel panel = panel(100, new ArrayList<>());
        panel.alignChildren();
        ProbeRow old = (ProbeRow) panel.getChildren().get(0);
        setField(old, "hovering", true);
        int oldExits = old.exits;
        ProbeRow replacement = new ProbeRow(200, new ArrayList<>());
        panel.getChildren().set(0, replacement);
        replacement.setParent(panel);
        panel.alignChildren();

        assertFalse(old.isHovering());
        assertEquals(oldExits + 1, old.exits);
        assertSame(replacement, panel.renderChildren().get(0));

        setField(replacement, "hovering", true);
        int replacementExits = replacement.exits;
        panel.getChildren().clear();
        panel.alignChildren();
        assertTrue(panel.renderChildren().isEmpty());
        assertFalse(replacement.isHovering());
        assertEquals(replacementExits + 1, replacement.exits);
    }

    @Test
    void disablingUniformLayoutRestoresCompleteChildTraversal() {
        ProbePanel panel = panel(100, new ArrayList<>());
        panel.alignChildren();
        assertTrue(panel.renderChildren().size() < 100);
        panel.setUniformVerticalItemHeight(-1);
        assertSame(panel.getChildren(), panel.renderChildren());
        panel.setUniformVerticalItemHeight(30);
        panel.setAlignment(ScrollPanel.Alignment.HORIZONTAL);
        assertSame(panel.getChildren(), panel.renderChildren());
    }

    @Test
    void childCallbacksCanResetLayoutWithoutInvalidatingTheCurrentRenderIteration() {
        List<Integer> rendered = new ArrayList<>();
        ProbePanel panel = panel(100, rendered);
        ((ProbeRow) panel.getChildren().get(0)).setBeforeRenderCallback(() ->
                panel.setUniformVerticalItemHeight(-1));
        panel.renderWidget(-100, -100, 0);
        assertEquals(List.of(0, 1, 2, 3), rendered);
        assertSame(panel.getChildren(), panel.renderChildren());
    }

    @Test
    void stableWindowReusesItsCandidateSnapshot() {
        ProbePanel panel = panel(100, new ArrayList<>());
        panel.alignChildren();
        List<AbstractWidget<?>> candidates = panel.renderChildren();
        panel.alignChildren();
        assertSame(candidates, panel.renderChildren());
    }

    private static void setField(AbstractWidget<?> widget, String name, Object value) throws Exception {
        Field field = AbstractWidget.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(widget, value);
    }

    private static Object getField(AbstractWidget<?> widget, String name) throws Exception {
        Field field = AbstractWidget.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(widget);
    }
}
