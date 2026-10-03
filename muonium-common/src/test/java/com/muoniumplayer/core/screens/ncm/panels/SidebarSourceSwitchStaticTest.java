package com.muoniumplayer.core.screens.ncm.panels;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.jupiter.api.Assertions.*;

/** 音源切换的用户体验契约：搜索草稿保留、旧异步结果不串回、栏目变化可动画。 */
class SidebarSourceSwitchStaticTest {
    private static final Path NAV = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "screens", "ncm", "panels", "NavigateBar.java"));
    private static final Path SOURCE = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "screens", "ncm", "MusicSourceOverlay.java"));
    private static final Path SCREEN = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "screens", "ncm", "NCMScreen.java"));

    @Test
    void rebuildingSidebarReusesSearchWidgetAndKeepsSourceContext() throws Exception {
        String nav = read(NAV);
        assertTrue(nav.contains("this.searchField = previous == null ? new TextFieldWidget"
                + "(FontManager.pf14bold) : previous.searchField"));
        assertTrue(nav.contains("this.playlistSearchMode = previous != null && previous.playlistSearchMode"));
        assertTrue(nav.contains("this.sourceChanged = previous != null"));
        assertTrue(nav.contains("this.indicatorTop = previous.indicatorTop"));
        assertTrue(nav.contains("this.indicatorOwner = null"));
        assertTrue(nav.contains("currentSourceIdentity()"));
        assertTrue(nav.contains("this.playlistPanel.actualScrollOffset = previous.playlistPanel.actualScrollOffset"));
    }

    @Test
    void sourceSwitchDoesNotClearDraftAndInvalidatesOldSearchResults() throws Exception {
        String nav = read(NAV);
        String source = read(SOURCE);
        String screen = read(SCREEN);
        assertTrue(source.contains("screen.setCurrentPanel(new HomePanel())"));
        assertTrue(screen.contains("this.navigationRequests.invalidate()"));
        assertTrue(nav.contains("screen.isContentRequestCurrent(ticket)"));
        assertTrue(screen.contains("if (!this.navigationRequests.isCurrent(ticket)) return"));
        assertTrue(nav.contains("NeteaseMusicService.getCurrentPlatform() != searchPlatform"));
        assertTrue(screen.contains("new NavigateBar(this.playlistsPanel)"));
    }

    @Test
    void sourceChangeAnimatesRowsAndSectionsWithoutMovingClickGeometry() throws Exception {
        String nav = read(NAV);
        assertTrue(nav.contains("SidebarLayoutMotion"));
        assertTrue(nav.contains("this.armRelocation(key, item, false)"));
        assertTrue(nav.contains("this.addSidebarSection(\"section:discovery\""));
        assertTrue(nav.contains("this.retireSections()"));
        assertTrue(nav.contains("sourceMotion.offset()"));
        assertTrue(nav.contains("ScissorClipManager.begin(getX(), getY(), getWidth(), getHeight())"));
        assertTrue(nav.contains("this.previousSections.clear()"));
        assertTrue(nav.contains("this.retirementLayer.getChildren().remove(section.widget)"));
        assertTrue(nav.contains("setTransformations(null)"));
    }

    private static String read(Path path) throws Exception {
        return Files.readString(path).replace("\r\n", "\n");
    }
}

