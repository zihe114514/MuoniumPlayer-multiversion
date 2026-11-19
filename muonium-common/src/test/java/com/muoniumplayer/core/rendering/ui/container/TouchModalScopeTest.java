package com.muoniumplayer.core.rendering.ui.container;

import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Pure widget ownership/denial tests: no Canvas, native loading, game events or runtime config. */
@SuppressWarnings("try")
class TouchModalScopeTest {
    @Test
    void unrestrictedAndExplicitNullScopesPermitAnyScrollPanel() {
        ScrollPanel first = new ScrollPanel();
        ScrollPanel second = new ScrollPanel();
        assertTrue(first.canPollTouchInput());
        assertTrue(second.canPollTouchInput());
        try (ScrollPanel.TouchInputScope ignored = ScrollPanel.restrictTouchInputTo(null)) {
            assertTrue(first.canPollTouchInput());
            assertTrue(second.canPollTouchInput());
        }
        assertTrue(first.canPollTouchInput());
    }

    @Test
    void restrictionAllowsOnlyRootIdentityAndItsDescendantsNotOverlappingSiblings() {
        Panel host = new Panel();
        Panel modal = new Panel();
        Panel nested = new Panel();
        ScrollPanel background = new ScrollPanel();
        ScrollPanel modalList = new ScrollPanel();
        host.addChild(background, modal);
        modal.addChild(nested);
        nested.addChild(modalList);
        background.setBounds(0, 0, 300, 300);
        modalList.setBounds(0, 0, 300, 300); // Matching geometry cannot confer input ownership.
        try (ScrollPanel.TouchInputScope ignored = ScrollPanel.restrictTouchInputTo(modal)) {
            assertFalse(background.canPollTouchInput());
            assertTrue(modalList.canPollTouchInput());
            assertFalse(new ScrollPanel().canPollTouchInput());
        }
        assertTrue(background.canPollTouchInput());
    }

    @Test
    void scrollPanelCanItselfBeTheAllowedRoot() {
        ScrollPanel root = new ScrollPanel();
        ScrollPanel child = new ScrollPanel();
        root.addChild(child);
        try (ScrollPanel.TouchInputScope ignored = ScrollPanel.restrictTouchInputTo(root)) {
            assertTrue(root.canPollTouchInput());
            assertTrue(child.canPollTouchInput());
        }
    }

    @Test
    void nestedRootsRestorePreviousRestrictionInLexicalOrder() {
        ScrollPanel outer = new ScrollPanel();
        ScrollPanel inner = new ScrollPanel();
        try (ScrollPanel.TouchInputScope ignored = ScrollPanel.restrictTouchInputTo(outer)) {
            assertTrue(outer.canPollTouchInput());
            assertFalse(inner.canPollTouchInput());
            try (ScrollPanel.TouchInputScope nested = ScrollPanel.restrictTouchInputTo(inner)) {
                assertFalse(outer.canPollTouchInput());
                assertTrue(inner.canPollTouchInput());
            }
            assertTrue(outer.canPollTouchInput());
            assertFalse(inner.canPollTouchInput());
        }
        assertTrue(outer.canPollTouchInput());
        assertTrue(inner.canPollTouchInput());
    }

    @Test
    void nestedNullTemporarilyLiftsRestrictionAndThenRestoresIt() {
        ScrollPanel root = new ScrollPanel();
        ScrollPanel background = new ScrollPanel();
        try (ScrollPanel.TouchInputScope ignored = ScrollPanel.restrictTouchInputTo(root)) {
            assertFalse(background.canPollTouchInput());
            try (ScrollPanel.TouchInputScope unrestricted = ScrollPanel.restrictTouchInputTo(null)) {
                assertTrue(background.canPollTouchInput());
            }
            assertFalse(background.canPollTouchInput());
        }
        assertTrue(background.canPollTouchInput());
    }

    @Test
    void exceptionsRestoreBothNestedScopeAndOuterUnrestrictedState() {
        ScrollPanel outer = new ScrollPanel();
        ScrollPanel inner = new ScrollPanel();
        assertThrows(IllegalStateException.class, () -> {
            try (ScrollPanel.TouchInputScope ignored = ScrollPanel.restrictTouchInputTo(outer)) {
                assertThrows(IllegalArgumentException.class, () -> {
                    try (ScrollPanel.TouchInputScope nested = ScrollPanel.restrictTouchInputTo(inner)) {
                        throw new IllegalArgumentException("child render failed");
                    }
                });
                assertTrue(outer.canPollTouchInput());
                assertFalse(inner.canPollTouchInput());
                throw new IllegalStateException("host render failed");
            }
        });
        assertTrue(outer.canPollTouchInput());
        assertTrue(inner.canPollTouchInput());
    }

    @Test
    void repeatedCloseCannotUndoANewerScope() {
        ScrollPanel first = new ScrollPanel();
        ScrollPanel second = new ScrollPanel();
        ScrollPanel.TouchInputScope finished = ScrollPanel.restrictTouchInputTo(first);
        finished.close();
        finished.close();
        assertTrue(second.canPollTouchInput());
        try (ScrollPanel.TouchInputScope current = ScrollPanel.restrictTouchInputTo(second)) {
            finished.close();
            assertFalse(first.canPollTouchInput());
            assertTrue(second.canPollTouchInput());
        }
        assertTrue(first.canPollTouchInput());
    }

    @Test
    void restrictionIsThreadLocalAndDoesNotLeakIntoAnotherRenderer() throws Exception {
        ScrollPanel first = new ScrollPanel();
        ScrollPanel second = new ScrollPanel();
        ExecutorService otherThread = Executors.newSingleThreadExecutor();
        try (ScrollPanel.TouchInputScope ignored = ScrollPanel.restrictTouchInputTo(first)) {
            assertFalse(second.canPollTouchInput());
            assertTrue(otherThread.submit(() -> {
                assertTrue(first.canPollTouchInput());
                assertTrue(second.canPollTouchInput());
                try (ScrollPanel.TouchInputScope own = ScrollPanel.restrictTouchInputTo(second)) {
                    assertFalse(first.canPollTouchInput());
                    assertTrue(second.canPollTouchInput());
                }
                return first.canPollTouchInput() && second.canPollTouchInput();
            }).get(5, TimeUnit.SECONDS));
            assertTrue(first.canPollTouchInput());
            assertFalse(second.canPollTouchInput());
        } finally {
            otherThread.shutdownNow();
            assertTrue(otherThread.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void localEntranceExitGateStillDeniesInsideRootOrUnrestrictedScope() {
        GatedPanel panel = new GatedPanel();
        assertTrue(panel.canPollTouchInput());
        panel.allow = false;
        assertFalse(panel.canPollTouchInput());
        try (ScrollPanel.TouchInputScope ignored = ScrollPanel.restrictTouchInputTo(panel)) {
            assertFalse(panel.canPollTouchInput());
            panel.allow = true;
            assertTrue(panel.canPollTouchInput());
        }
    }

    @Test
    void parentIdentityIsRecheckedWhenAWidgetMovesOutsideModalTree() {
        Panel root = new Panel();
        Panel background = new Panel();
        ScrollPanel list = new ScrollPanel();
        root.addChild(list);
        try (ScrollPanel.TouchInputScope ignored = ScrollPanel.restrictTouchInputTo(root)) {
            assertTrue(list.canPollTouchInput());
            list.setParent(background);
            assertFalse(list.canPollTouchInput());
            list.setParent(root);
            assertTrue(list.canPollTouchInput());
        }
    }

    @Test
    void deniedPollingResetsOngoingDragWithoutFlingsOrAnyGestureReads() throws Exception {
        ScrollPanel background = new ScrollPanel();
        background.actualScrollOffset = 31;
        background.targetScrollOffset = 47;
        draggingField().setBoolean(background, true);
        try (ScrollPanel.TouchInputScope ignored = ScrollPanel.restrictTouchInputTo(new Panel())) {
            advanceTouchDrag(background); // Actual production method; denied before TouchSettings/native access.
        }
        assertFalse(draggingField().getBoolean(background));
        assertEquals(31, background.actualScrollOffset);
        assertEquals(47, background.targetScrollOffset);
    }

    @Test
    void localGateDenialAlsoCancelsDragBeforeReadingGlobalGestureState() throws Exception {
        GatedPanel panel = new GatedPanel();
        panel.allow = false;
        panel.actualScrollOffset = 14;
        panel.targetScrollOffset = 29;
        draggingField().setBoolean(panel, true);
        advanceTouchDrag(panel);
        assertFalse(draggingField().getBoolean(panel));
        assertEquals(14, panel.actualScrollOffset);
        assertEquals(29, panel.targetScrollOffset);
    }

    private static Field draggingField() throws ReflectiveOperationException {
        Field field = ScrollPanel.class.getDeclaredField("touchDragging");
        field.setAccessible(true);
        return field;
    }

    private static void advanceTouchDrag(ScrollPanel panel) throws ReflectiveOperationException {
        Method method = ScrollPanel.class.getDeclaredMethod("advanceTouchDrag");
        method.setAccessible(true);
        method.invoke(panel);
    }

    private static final class GatedPanel extends ScrollPanel {
        boolean allow = true;
        @Override protected boolean acceptsTouchInput() { return allow; }
    }
}
