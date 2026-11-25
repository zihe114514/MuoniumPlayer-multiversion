package com.muoniumplayer.configskija.host;

import com.muoniumplayer.configskija.model.ConfigHost;
import com.muoniumplayer.configskija.model.HudElement;
import com.muoniumplayer.configskija.ui.hud.HudDocument;
import com.muoniumplayer.configskija.ui.hud.HudRuntime;

import java.util.List;
import java.util.Set;

/** Optional client-thread integration implemented alongside ConfigHost. No game or renderer ownership. */
public interface HudExtensions {
    enum Capability {
        ADDITIONAL_HUDS,
        HOST_COMPOSITION,
        HOST_SETTINGS,
        EXACT_TEXT,
        GUI_VISIBILITY
    }

    /** Advertise only callbacks that the host has actually installed. */
    Set<Capability> hudCapabilities();

    /** Profile identity; a change discards this studio's undo and clipboard ownership. */
    String hudOwner();

    HudDocument loadHudDocument();

    void saveHudDocument(HudDocument document);

    /** Retain the runtime beyond screen close and feed it the existing main Skija frame. */
    void bindHudRuntime(HudRuntime runtime);
    default HudRuntime existingHudRuntime(){return null;}

    /** Real GUI scale, time, debug/tab/chat/screen state and data; editor flag is set by HudStudio. */
    HudRuntime.Frame hudFrame();

    /** Providers and settings are live; registration may happen after the first frame. */
    List<HudElement> hudProviders();

    List<ConfigHost.Group> hudSettings(HudElement element);

    /** Required when EXACT_TEXT is advertised: Minecraft/Poppins metrics, weight, italic and outline. */
    HudRuntime.TextRenderer hudTextRenderer();

    /** Editor preview always calls this. Game frames call it only with HOST_COMPOSITION; use the existing main frame and exact live bounds. */
    void renderHostHud(
            com.muoniumplayer.configskija.ui.Painter painter,
            HudElement element,
            com.muoniumplayer.configskija.ui.Geometry.Rect bounds,
            HudRuntime.Frame frame);

    /** Fill this union once, with concave round joins; do not draw each member background again. */
    void renderHudShape(
            com.muoniumplayer.configskija.ui.Painter painter,
            com.muoniumplayer.configskija.ui.hud.HudMerge.Shape shape);
}
