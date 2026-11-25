# Control text centering contract

2026-11-25. Scope: shared configskija Painter/SkijaPainter, production HostTextRenderer and control helpers. Ordinary text and paragraphs still take a line-top y.

- `Painter.TextBounds(top, bottom)` describes the visible vertical extent relative to that same line-top origin, in current painter units.
- `textBounds(text, size, bold)` defaults to `(0, size)` for legacy recording painters and text providers.
- `textTopForCenter(text, centerY, size, bold)` returns a line-top position. Use this when an editor needs the origin rather than a draw call.
- `textCenteredY(text, x, centerY, size, bold, color)` keeps x and centers vertically.
- `textCentered(text, rect, size, bold, color)` centers vertically and uses the existing advance width for horizontal placement.

Example for sidebar agents: `p.textCenteredY(label, labelX, row.y() + row.h()/2, fontSize, bold, color)`. Continue to center SVG icons by their actual rectangle; their y is a square-top coordinate, not a text line-top.

## Implementation evidence

The cached actual 26.3 Fabric compile classpath resolves io.github.humbleui:skija-shared:0.143.17. Both 26.3 loader build files declare 0.143.17. Checked the cached binary with javap and the matching sources JAR before using:

- io.github.humbleui.skija.Font.getBounds(short[]): io.github.humbleui.types.Rect[].
- io.github.humbleui.skija.TextLine.getGlyphs(): short[].
- io.github.humbleui.skija.TextLine.getPositions(): float[]; source documents [x0, y0, x1, y1, ...].
- Existing shared CFontRenderer.getGlyphBounds(String) returns bounds relative to drawGlyphsOn's origin. HostTextRenderer converts those using its existing 2 - getBaselineOffset() draw translation.

Native runs union each positioned glyph's bounds; plain host fallback uses its own indexed glyph/fallback geometry. Complex host graphemes use font metrics because the host's plain indexed bounds do not represent its shaped grapheme path. Empty and whitespace-only strings also use the measured font line box. These metric fallbacks are not exact visible ink.

Bounds are lazy values in the existing bounded layout cache. Font revision changes, explicit invalidate, layout eviction and renderer close retain their existing lifetime behavior; bounds add no native resource. Measurement obeys the creating-thread requirement. ScaledPainter queries the physical font size and divides top/bottom once into logical units; no HUD model or screen scaling is changed.

LibraryPaint.center remains horizontal-only with a line-top y because existing title and paragraph callers rely on that convention. LibraryPaint.empty and actual buttons use the new rectangle/vertical helpers.

## Offscreen verification

Run from the repository root:

    & 'muonium-config-skija/src/test/java/com/muoniumplayer/configskija/text/run.ps1'
    & 'muonium-config-skija/src/test/java/com/muoniumplayer/configskija/settings/verify.ps1' -Jdk 'D:/JDK25'

These scripts use javac --release 17 and existing dependencies only. No Gradle, game or computer control is required. The production centering pixel tests pass HostTextRenderer directly to SkijaPainter. PreviewMain, HudPanelRasterPreview and EmbeddedSettingsRasterTest also implement TextBounds: Font.measureText gives baseline-relative ink, shifted by -getMetrics().getAscent() to match their drawString line-top origin; empty ink uses descent - ascent. Their fixture-font rendering now observes the centering interface, while the production renderer remains the evidence for host fallback typography.

verify.ps1 selects -Suite Neutral (default), Native, or All. Direct Skija/host-text imports select the native classpath; the selected/deferred native sources are listed explicitly. Native text checks use text/run.ps1 and its cached host dependencies. Run preview.ps1 -Jdk D:/JDK17 for the neutral checks and 15 page / 9 HUD panel fixture images; verify.ps1 -Suite Native -Jdk D:/JDK25 runs host/native text checks. Shared EmbeddedSettingsRasterTest can also be compiled and run offscreen against the cached 26.3 host classes to render its 11 actual categories; this does not run the playback HUD matrix.

Assertions cover legacy defaults, helper forwarding, On/Off, normal/HUD dropdown sizes, precision editor origin/descent and formatted overlay, icon fallback, physical density forwarding, ordinary line-top pixel behavior, fonts/weights, CJK/Latin/descenders/multiline, whitespace, cache invalidation and render-thread ownership. Pixel center tolerance is approximately one raster pixel. This is CPU evidence for the cached host dependency set, not a fourteen-target build or in-game verification.
