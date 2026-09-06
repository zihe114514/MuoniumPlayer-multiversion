package com.muoniumplayer.core.screens.onboarding;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.management.UiFont;
import com.muoniumplayer.core.management.UiFontLibrary;
import com.muoniumplayer.core.rendering.FontelloIcons;
import com.muoniumplayer.core.rendering.MusicBrandIcons;
import com.muoniumplayer.party.ui.PartyIcons;
import com.muoniumplayer.core.ncm.music.NeteaseMusicService;
import com.muoniumplayer.core.ncm.music.GdStudioMusicService;
import com.muoniumplayer.core.ncm.music.GdStudioSourceSettings;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.rendering.RGBA;
import com.muoniumplayer.core.rendering.Rect;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.screens.ncm.AccountManagerOverlay;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaShapes;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.skija.PathMeasure;
import io.github.humbleui.skija.Shader;
import today.opai.api.features.ExtensionScreen;

import java.util.List;

/**
 * Premium first-run surface for MuoniumPlayer. This is intentionally a standalone screen:
 * the existing player remains untouched and this class only orchestrates setup pages.
 */
public final class OnboardingScreen extends ExtensionScreen implements SharedConstants, SharedRenderingConstants {
    private static final int BACKDROP = RGBA.color(5, 9, 19, 255);
    private static final int GLASS = RGBA.color(12, 20, 38, 246);
    private static final int GLASS_2 = RGBA.color(17, 29, 53, 238);
    private static final int WHITE = RGBA.color(245, 248, 255, 255);
    private static final int SOFT = RGBA.color(181, 197, 224, 255);
    private static final int DIM = RGBA.color(112, 133, 165, 255);
    private static final int LINE = RGBA.color(63, 92, 145, 210);
    private static final int BLUE = RGBA.color(93, 145, 255, 255);
    private static final int CYAN = RGBA.color(103, 229, 221, 255);
    private static final int VIOLET = RGBA.color(203, 119, 255, 255);
    private static final int GREEN = RGBA.color(119, 228, 169, 255);

    private int stepIndex;
    private double alpha;
    private long pageStartedAt = System.nanoTime();
    private long openedAt = pageStartedAt;
    private AccountManagerOverlay accountOverlay;
    private boolean skipConfirm;
    private MusicPlatform selectedPlatform;
    private NCMTheme.ThemePreset selectedTheme;
    private List<UiFont> fonts;
    private int selectedFontIndex;

    public OnboardingScreen() {
        selectedPlatform = NeteaseMusicService.getCurrentPlatform();
        selectedTheme = NCMTheme.getCurrent();
        fonts = UiFontLibrary.getCatalog();
        selectedFontIndex = 0;
        for (int i = 0; i < fonts.size(); i++) {
            if (UiFontLibrary.isActive(fonts.get(i))) {
                selectedFontIndex = i;
                break;
            }
        }
    }

    private OnboardingStep step() {
        return OnboardingStep.values()[stepIndex];
    }

    @Override
    public void drawScreen(int mouseX, int mouseY) {
        RenderSystem.refreshResolution();
        RenderSystem.FIXED_SCALE = true;
        alpha = Interpolations.interpolate((float) alpha, 1f, .18f);

        double width = RenderSystem.getWidth();
        double height = RenderSystem.getHeight();
        double sx = RenderSystem.getWidthNotScaled() / width;
        double sy = RenderSystem.getHeightNotScaled() / height;
        double mx = mouseX / sx;
        double my = mouseY / sy;

        SkijaGfx.pushAttrib();
        SkijaGfx.pushMatrix();
        api.getGLStateManager().pushMatrix();
        api.getGLStateManager().scale(sx, sy, 1);
        try {
            if (step() == OnboardingStep.WELCOME) {
                drawFigmaWelcome(width, height, mx, my);
            } else {
                drawBackground(width, height);
                drawSurface(width, height, mx, my);
            }
            if (accountOverlay != null) {
                accountOverlay.setBounds(0, 0, width, height);
                accountOverlay.setAlpha((float) alpha);
                accountOverlay.renderWidget(mx, my, Mouse.getDWheel());
                if (accountOverlay.shouldClose()) {
                    accountOverlay.dispose();
                    accountOverlay = null;
                }
            }
        } finally {
            api.getGLStateManager().popMatrix();
            SkijaGfx.popMatrix();
            SkijaGfx.popAttrib();
        }
    }

    /**
     * Welcome-only composition following the supplied Figma visual language: monochrome chrome,
     * generous whitespace, mono labels, pill CTA, dashed selection language and a vivid gallery-like hero.
     */
    private void drawFigmaWelcome(double width, double height, double mouseX, double mouseY) {
        Rect.draw(0, 0, width, height, RGBA.color(255, 255, 255, 255));
        double pad = Math.max(28, width * .055);
        double heroX = pad;
        double heroY = 76;
        double heroW = width - pad * 2;
        double heroH = Math.min(height * .68, 420);
        double t = (System.nanoTime() - openedAt) / 1_000_000_000.0;
        double drift = Math.sin(t * .38) * 8.0;

        drawText(FontManager.pf18bold, "MuoniumPlayer", pad, 30, RGBA.color(0, 0, 0, 255));
        drawText(FontManager.pf12bold, "FIRST SETUP  /  01", width - pad - 132, 34, RGBA.color(0, 0, 0, 180));
        roundedRect(width - pad - 34, 20, 28, 28, 14, RGBA.color(0, 0, 0, 255));
        drawIcon(PartyIcons.PERSON, FontManager.partyIcon24, width - pad - 27, 22, RGBA.color(255, 255, 255, 255));

        SkijaShapes.roundedRectCornerGradient(heroX, heroY, heroW, heroH, 28,
                new java.awt.Color(150, 255, 93, 255),
                new java.awt.Color(255, 236, 71, 255),
                new java.awt.Color(115, 59, 255, 255),
                new java.awt.Color(255, 76, 169, 255), 1.0f);
        roundedOutline(heroX, heroY, heroW, heroH, 28, 2, new java.awt.Color(0, 0, 0, 255));

        // Floating gallery cards: black/white interface chrome over chromatic product content.
        double cardW = Math.min(230, heroW * .25);
        double cardH = 132;
        double card1X = heroX + heroW * .10 + drift;
        double card1Y = heroY + heroH * .20;
        double card2X = heroX + heroW * .64 - drift;
        double card2Y = heroY + heroH * .48;
        roundedRect(card1X, card1Y, cardW, cardH, 18, RGBA.color(255, 255, 255, 232));
        roundedOutline(card1X, card1Y, cardW, cardH, 18, 2, new java.awt.Color(0, 0, 0, 255));
        drawText(FontManager.pf12bold, "01  /  SOUND", card1X + 16, card1Y + 18, RGBA.color(0, 0, 0, 180));
        drawIcon(PartyIcons.HEADPHONES, FontManager.partyIcon40, card1X + 18, card1Y + 46, RGBA.color(0, 0, 0, 255));
        drawText(FontManager.pf14bold, "找到你的节奏", card1X + 70, card1Y + 54, RGBA.color(0, 0, 0, 255));
        drawText(FontManager.pf12, "账号 · 音源 · 外观", card1X + 70, card1Y + 78, RGBA.color(0, 0, 0, 150));
        roundedRect(card1X + 16, card1Y + 104, cardW - 32, 6, 3, RGBA.color(0, 0, 0, 255));

        roundedRect(card2X, card2Y, cardW * .84, cardH * .82, 18, RGBA.color(0, 0, 0, 235));
        roundedOutline(card2X, card2Y, cardW * .84, cardH * .82, 18, 2, new java.awt.Color(255, 255, 255, 255));
        drawIcon(PartyIcons.RADIO, FontManager.partyIcon40, card2X + 20, card2Y + 22, RGBA.color(255, 255, 255, 255));
        drawText(FontManager.pf14bold, "播放空间", card2X + 70, card2Y + 30, RGBA.color(255, 255, 255, 255));
        drawText(FontManager.pf12, "从这里开始", card2X + 70, card2Y + 54, RGBA.color(255, 255, 255, 170));

        double copyX = heroX + 34;
        double copyY = heroY + heroH - 108;
        drawText(FontManager.pf12bold, "WELCOME  /  你好  /  HELLO", copyX, copyY, RGBA.color(255, 255, 255, 230));
        drawText(FontManager.pf40bold, "欢迎进入", copyX, copyY + 28, RGBA.color(255, 255, 255, 255));
        drawText(FontManager.pf40bold, "你的音乐空间", copyX, copyY + 74, RGBA.color(255, 255, 255, 255));
        drawText(FontManager.pf14, "用几步设置，开始一段更适合你的播放体验。", copyX, copyY + 104, RGBA.color(255, 255, 255, 220));

        double railY = heroY + heroH + 32;
        drawText(FontManager.pf12bold, "01", pad, railY + 8, RGBA.color(0, 0, 0, 255));
        drawText(FontManager.pf12, "欢迎", pad + 28, railY + 8, RGBA.color(0, 0, 0, 170));
        Rect.draw(pad + 80, railY + 12, Math.max(80, width * .14), 2, RGBA.color(0, 0, 0, 255));
        drawText(FontManager.pf12, "账号", pad + 95 + Math.max(80, width * .14), railY + 8, RGBA.color(0, 0, 0, 110));
        drawText(FontManager.pf12, "音源", pad + 150 + Math.max(80, width * .14), railY + 8, RGBA.color(0, 0, 0, 110));
        drawText(FontManager.pf12, "外观", pad + 205 + Math.max(80, width * .14), railY + 8, RGBA.color(0, 0, 0, 110));

        double buttonW = 154;
        double buttonX = width - pad - buttonW;
        double buttonY = railY - 13;
        boolean hover = inside(mouseX, mouseY, buttonX, buttonY, buttonW, 44);
        roundedRect(buttonX, buttonY, buttonW, 44, 22, hover ? RGBA.color(32, 32, 32, 255) : RGBA.color(0, 0, 0, 255));
        drawText(FontManager.pf14bold, "开始设置", buttonX + 22, buttonY + 15, RGBA.color(255, 255, 255, 255));
        drawIcon(PartyIcons.CHEVRON_RIGHT, FontManager.partyIcon24, buttonX + buttonW - 34, buttonY + 10, RGBA.color(255, 255, 255, 255));
        drawText(FontManager.pf12, "跳过设置", buttonX - 80, buttonY + 16, RGBA.color(0, 0, 0, skipConfirm ? 255 : 140));
    }
    private void drawBackground(double width, double height) {
        Rect.draw(0, 0, width, height, BACKDROP);
        double t = (System.nanoTime() - openedAt) / 1_000_000_000.0;
        double breathe = .5 + .5 * Math.sin(t * .42);
        // Layered glassy light fields. They are deliberately oversized and soft, so the background
        // reads like a calm stage rather than a flat Minecraft menu.
        roundedRect(width * .02, height * .08, width * .62, height * .72, 160,
                24, 52, 113, (int) (28 + breathe * 12));
        roundedRect(width * .48, height * .20, width * .58, height * .70, 180,
                82, 36, 150, (int) (20 + (1.0 - breathe) * 16));
        roundedRect(width * .18, height * .86, width * .72, height * .08, 60,
                35, 105, 177, 18);
        for (int i = 0; i < 6; i++) {
            double x = width * (.07 + i * .18) + Math.sin(t * .25 + i) * 10;
            double y = height * (.12 + (i % 3) * .31);
            roundedRect(x, y, 2, 2, 2, 90, 160, 255, 85 + (i * 9));
        }
    }

    private void drawSurface(double width, double height, double mouseX, double mouseY) {
        double left = surfaceLeft(width);
        double top = surfaceTop(height);
        double surfaceWidth = surfaceWidth(width);
        double surfaceHeight = surfaceHeight(height);
        roundedRect(left, top, surfaceWidth, surfaceHeight, 28, GLASS);
        roundedOutlineGradient(left, top, surfaceWidth, surfaceHeight, 28, 1.15,
                new java.awt.Color(73, 132, 255, 210),
                new java.awt.Color(35, 68, 120, 180),
                new java.awt.Color(197, 107, 255, 210),
                new java.awt.Color(54, 82, 143, 180));

        double dividerX = left + surfaceWidth * .385;
        roundedRect(dividerX, top + 34, 1, surfaceHeight - 118, 1, 48, 73, 116, 125);
        drawBrandRail(left + 42, top + 38, dividerX - left - 72, surfaceHeight - 96);
        drawPageContent(dividerX + 48, top + 42, left + surfaceWidth - dividerX - 82, surfaceHeight - 112, mouseX, mouseY);
        drawProgress(left + 50, top + surfaceHeight - 50, surfaceWidth - 100);
        drawNavigation(left, top, surfaceWidth, surfaceHeight, mouseX, mouseY);
    }

    private void drawBrandRail(double x, double y, double width, double height) {
        roundedRect(x, y - 8, 30, 30, 11, colorWithAlpha(BLUE, 46));
        drawIcon(PartyIcons.RADIO, FontManager.partyIcon24, x + 7, y - 1, CYAN);
        drawText(FontManager.pf12bold, "MUONIUMPLAYER  /  FIRST RUN", x + 42, y, DIM);
        drawText(FontManager.pf25bold, "让音乐，先成为你的。", x, y + 46, WHITE);
        drawText(FontManager.pf14, "从账号、音源到视觉偏好，", x, y + 85, SOFT);
        drawText(FontManager.pf14, "几步完成一套属于你的播放体验。", x, y + 108, SOFT);

        drawSignature(x, y + 178, width, 1.0);
        drawText(FontManager.pf12, "A quieter beginning for every track.", x, y + 352, DIM);
        drawText(FontManager.pf12, "MuoniumPlayer", x, y + 374, DIM);

        double badgeY = y + height - 64;
        roundedRect(x, badgeY, width, 40, 14, GLASS_2);
        roundedOutline(x, badgeY, width, 40, 14, 1, awtColorWithAlpha(BLUE, 125));
        drawText(FontManager.pf12bold, "当前步骤", x + 14, badgeY + 10, DIM);
        drawText(FontManager.pf14bold, (stepIndex + 1) + " / " + OnboardingStep.values().length + "   " + step().getTitle(), x + 14, badgeY + 23, WHITE);
    }

    private void drawSignature(double x, double y, double width, double scaleHint) {
        double progress = Math.min(1.0, Math.max(0.0, (System.nanoTime() - openedAt) / 2_400_000_000.0));
        double pulse = .78 + .22 * Math.sin((System.nanoTime() - openedAt) / 1_000_000_000.0 * 1.2);
        String svg = "M12 104 C44 101 63 85 96 51 C116 30 145 15 166 13 C151 39 133 66 122 91 C114 109 126 121 139 107 C151 93 165 57 179 43 C189 34 196 38 190 51 C182 67 163 90 168 103 C175 116 200 110 220 87 C236 69 251 43 267 39 C282 35 283 55 271 76 C259 97 243 108 251 115 C264 126 299 97 317 77 C335 57 348 42 361 45 C373 48 361 74 347 91 C334 107 341 119 357 111 C378 100 389 66 409 51 C427 38 438 45 430 62 C420 83 402 103 414 112 C431 124 457 103 477 82 C493 65 505 53 518 57 C532 62 520 83 506 98 C493 112 500 123 516 116 C541 106 561 71 591 56 C615 44 641 49 656 61 C675 77 662 101 641 111";
        try (Path base = Path.makeFromSVGString(svg)) {
            float scale = (float) Math.max(.28, Math.min(.82, width / 760.0));
            try (Path scaled = base.makeScale(scale, scale);
                 Path placed = scaled.makeOffset((float) x, (float) y);
                 PathMeasure measure = new PathMeasure(placed);
                 PathBuilder builder = new PathBuilder()) {
                float length = measure.getLength();
                measure.getSegment(0, length * (float) progress, builder, true);
                try (Path partial = builder.snapshot()) {
                    Canvas canvas = SkijaGfx.canvas();
                    if (canvas == null) return;
                    int glow = RGBA.color(92, 130, 255, (int) (55 * pulse));
                    canvas.drawPath(partial, SkijaGfx.stroke(glow, 10.0));
                    int end = RGBA.color(238, 125, 255, 245);
                    int start = RGBA.color(93, 180, 255, 245);
                    try (Shader shader = Shader.makeLinearGradient((float) x, (float) y,
                            (float) (x + width), (float) (y + 120), new int[]{start, end})) {
                        canvas.drawPath(partial, SkijaGfx.strokeShader(shader, 3.0));
                    }
                    io.github.humbleui.types.Point tip = measure.getPosition(length * (float) progress);
                    roundedRect(tip.getX() - 4, tip.getY() - 4, 8, 8, 8, 173, 235, 255, (int) (180 * pulse));
                }
            }
        } catch (Throwable ignored) {
            // If a platform Skia build lacks path measurement, the rest of onboarding remains usable.
        }
    }

    private void drawPageContent(double x, double y, double width, double height, double mouseX, double mouseY) {
        double pageAge = Math.min(1.0, (System.nanoTime() - pageStartedAt) / 500_000_000.0);
        double lift = (1.0 - pageAge) * 16.0;
        double contentY = y + lift;
        drawText(FontManager.pf12bold, "0" + (stepIndex + 1), x, contentY, BLUE);
        drawText(FontManager.pf12, "OF 0" + OnboardingStep.values().length, x + 34, contentY, DIM);
        switch (step()) {
            case WELCOME:
                drawWelcome(x, contentY + 34, width);
                break;
            case ACCOUNT:
                drawAccount(x, contentY + 34, width, mouseX, mouseY);
                break;
            case SOURCE:
                drawSource(x, contentY + 34, width, mouseX, mouseY);
                break;
            case APPEARANCE:
                drawAppearance(x, contentY + 34, width, mouseX, mouseY);
                break;
            case FINISH:
                drawFinish(x, contentY + 34, width);
                break;
        }
    }

    private void drawWelcome(double x, double y, double width) {
        drawText(FontManager.pf40bold, "欢迎来到", x, y + 20, WHITE);
        drawText(FontManager.pf40bold, "你的音乐空间", x, y + 69, WHITE);
        drawText(FontManager.pf18, "先选一个方向，剩下的交给音乐。", x, y + 115, SOFT);
        drawText(FontManager.pf14, "账号可以稍后登录，音源与外观也都能随时修改。", x, y + 143, DIM);
        double cardY = y + 198;
        roundedRect(x, cardY, width, 78, 18, GLASS_2);
        roundedOutlineGradient(x, cardY, width, 78, 18, 1.0,
                new java.awt.Color(54, 118, 231, 190),
                new java.awt.Color(44, 72, 130, 160),
                new java.awt.Color(185, 102, 242, 190),
                new java.awt.Color(60, 85, 141, 160));
        roundedRect(x + 18, cardY + 22, 34, 34, 12, colorWithAlpha(BLUE, 55));
        drawIcon(PartyIcons.NOTE, FontManager.partyIcon24, x + 26, cardY + 27, CYAN);
        drawText(FontManager.pf14bold, "一套设置，马上开始。", x + 70, cardY + 19, WHITE);
        drawText(FontManager.pf12, "Welcome  ·  你好  ·  Hello", x + 70, cardY + 42, DIM);
    }

    private void drawAccount(double x, double y, double width, double mouseX, double mouseY) {
        drawText(FontManager.pf34bold, "连接账号", x, y + 20, WHITE);
        drawText(FontManager.pf14, "登录是可选的。我们会继续使用原有的本地账号系统。", x, y + 56, SOFT);
        MusicPlatform[] platforms = {MusicPlatform.NETEASE, MusicPlatform.QQ, MusicPlatform.KUGOU};
        for (int i = 0; i < platforms.length; i++) {
            MusicPlatform p = platforms[i];
            double cy = y + 94 + i * 62;
            boolean logged = NeteaseMusicService.isLoggedIn(p);
            boolean hover = inside(mouseX, mouseY, x, cy, width, 48);
            roundedRect(x, cy, width, 48, 14, hover ? GLASS_2 : GLASS);
            roundedOutline(x, cy, width, 48, 14, 1, awtColorWithAlpha(p.getBrandColor(), logged ? 210 : 105));
            roundedRect(x + 14, cy + 12, 24, 24, 8, colorWithAlpha(p.getBrandColor(), 85));
            drawText(FontManager.pf14bold, p.getDisplayName(), x + 52, cy + 11, WHITE);
            drawText(FontManager.pf12, logged ? NeteaseMusicService.getAccountName(p) : "未登录 · 点击连接", x + 52, cy + 28, logged ? GREEN : DIM);
            drawText(FontManager.pf20bold, logged ? "✓" : "›", x + width - 34, cy + 13, logged ? GREEN : BLUE);
        }
    }

    private void drawSource(double x, double y, double width, double mouseX, double mouseY) {
        drawText(FontManager.pf34bold, "选择音源", x, y + 20, WHITE);
        drawText(FontManager.pf14, "选中的来源会立即应用到播放器。", x, y + 56, SOFT);
        MusicPlatform[] platforms = {MusicPlatform.NETEASE, MusicPlatform.QQ, MusicPlatform.KUGOU, MusicPlatform.GD};
        for (int i = 0; i < platforms.length; i++) {
            MusicPlatform p = platforms[i];
            double cw = (width - 12) * .5;
            double cx = x + (i % 2) * (cw + 12);
            double cy = y + 94 + (i / 2) * 66;
            boolean selected = selectedPlatform == p;
            boolean hover = inside(mouseX, mouseY, cx, cy, cw, 52);
            roundedRect(cx, cy, cw, 52, 15, selected ? colorWithAlpha(BLUE, 50) : (hover ? GLASS_2 : GLASS));
            roundedOutline(cx, cy, cw, 52, 15, selected ? 1.6 : 1, awtColorWithAlpha(selected ? BLUE : LINE, selected ? 230 : 160));
            drawPlatformIcon(p, cx + 14, cy + 13, selected ? CYAN : SOFT);
            drawText(FontManager.pf14bold, p.getDisplayName(), cx + 48, cy + 12, WHITE);
            drawText(FontManager.pf12, selected ? "当前使用" : "点击选择", cx + 16, cy + 31, selected ? GREEN : DIM);
            if (selected) drawIcon(PartyIcons.CHECK, FontManager.partyIcon24, cx + cw - 34, cy + 14, CYAN);
        }
        drawText(FontManager.pf12, "GD 音乐台沿用现有的平台配置。", x, y + 254, DIM);
    }

    private void drawAppearance(double x, double y, double width, double mouseX, double mouseY) {
        drawText(FontManager.pf34bold, "找到你的风格", x, y + 20, WHITE);
        drawText(FontManager.pf14, "主题与字体会实时预览，并使用现有样式配置。", x, y + 56, SOFT);
        NCMTheme.ThemePreset[] themes = NCMTheme.ThemePreset.values();
        int start = selectedTheme.ordinal() - 1;
        for (int i = 0; i < 3; i++) {
            int idx = Math.floorMod(start + i, themes.length);
            NCMTheme.ThemePreset theme = themes[idx];
            double cw = (width - 24) / 3;
            double cx = x + i * (cw + 12);
            double cy = y + 95;
            boolean selected = theme == selectedTheme;
            boolean hover = inside(mouseX, mouseY, cx, cy, cw, 110);
            roundedRect(cx, cy, cw, 110, 16, selected ? colorWithAlpha(theme.getColor(NCMScreen.ColorType.ACCENT), 60) : (hover ? GLASS_2 : GLASS));
            roundedRect(cx + 12, cy + 12, cw - 24, 42, 11, theme.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND));
            roundedRect(cx + 12, cy + 62, cw - 24, 8, 4, theme.getColor(NCMScreen.ColorType.ACCENT));
            drawIcon(PartyIcons.TEXT_FORMAT, FontManager.partyIcon24, cx + 12, cy + 78, theme.getColor(NCMScreen.ColorType.ACCENT));
            drawText(FontManager.pf12bold, theme.getDisplayName(), cx + 42, cy + 83, WHITE);
            drawText(FontManager.pf10bold, selected ? "SELECTED" : "PREVIEW", cx + 12, cy + 97, selected ? CYAN : DIM);
        }
        double fontY = y + 235;
        drawText(FontManager.pf14bold, "字体", x, fontY, WHITE);
        String name = fonts.isEmpty() ? "内置字体" : fonts.get(selectedFontIndex).getDisplayName();
        drawText(FontManager.pf12, name + "  ·  点击切换", x + 42, fontY + 1, DIM);
        roundedRect(x, fontY + 24, width, 46, 14, GLASS_2);
        roundedOutline(x, fontY + 24, width, 46, 14, 1, awtColorWithAlpha(LINE, 170));
        drawText(FontManager.pf18, "Aa  音乐让每一次播放都更有温度", x + 16, fontY + 37, CYAN);
    }

    private void drawFinish(double x, double y, double width) {
        roundedRect(x, y + 40, 62, 62, 22, colorWithAlpha(GREEN, 40));
        drawIcon(PartyIcons.CHECK, FontManager.partyIcon40, x + 13, y + 48, GREEN);
        drawText(FontManager.pf40bold, "一切就绪", x, y + 155, WHITE);
        drawText(FontManager.pf20, "MuoniumPlayer 已准备好陪你播放下一首。", x, y + 198, SOFT);
        drawText(FontManager.pf14, "点击右下角开始使用，随时可以回到主界面继续调整。", x, y + 238, DIM);
    }

    private void drawProgress(double x, double y, double width) {
        double segment = width / (OnboardingStep.values().length - 1);
        for (int i = 0; i < OnboardingStep.values().length - 1; i++) {
            Rect.draw(x + i * segment, y - 1, segment, 2, i < stepIndex ? GREEN : RGBA.color(42, 61, 98, 230));
        }
        for (int i = 0; i < OnboardingStep.values().length; i++) {
            double cx = x + i * segment;
            boolean current = i == stepIndex;
            int color = current ? BLUE : (i < stepIndex ? GREEN : RGBA.color(64, 87, 130, 255));
            roundedRect(cx - (current ? 6 : 4), y - (current ? 6 : 4), current ? 12 : 8, current ? 12 : 8, 8, color);
        }
    }

    private void drawNavigation(double left, double top, double width, double height, double mouseX, double mouseY) {
        double buttonW = 144;
        double buttonX = left + width - buttonW - 38;
        double buttonY = top + height - 72;
        boolean hover = inside(mouseX, mouseY, buttonX, buttonY, buttonW, 44);
        roundedRect(buttonX, buttonY, buttonW, 44, 22, hover ? colorWithAlpha(BLUE, 220) : colorWithAlpha(BLUE, 165));
        roundedOutline(buttonX, buttonY, buttonW, 44, 22, 1, awtColorWithAlpha(CYAN, 190));
        drawText(FontManager.pf14bold, stepIndex == OnboardingStep.values().length - 1 ? "开始使用" : (stepIndex == 0 ? "开始设置" : "下一步"), buttonX + 20, buttonY + 15, WHITE);
        drawIcon(PartyIcons.CHEVRON_RIGHT, FontManager.partyIcon24, buttonX + buttonW - 34, buttonY + 10, WHITE);
        if (stepIndex > 0) {
            int backColor = inside(mouseX, mouseY, left + 28, buttonY, 100, 40) ? WHITE : DIM;
            drawIcon(PartyIcons.CHEVRON_LEFT, FontManager.partyIcon24, left + 42, buttonY + 10, backColor);
            drawText(FontManager.pf12bold, "上一步", left + 66, buttonY + 16, backColor);
        }
        if (stepIndex < OnboardingStep.values().length - 1) {
            drawIcon(PartyIcons.CLOSE, FontManager.partyIcon24, buttonX - (skipConfirm ? 148 : 100), buttonY + 14, skipConfirm ? VIOLET : DIM);
            drawText(FontManager.pf12, skipConfirm ? "再次点击确认跳过" : "跳过设置", buttonX - (skipConfirm ? 124 : 76), buttonY + 17, skipConfirm ? VIOLET : DIM);
        }
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton != 0) return;
        RenderSystem.refreshResolution();
        RenderSystem.FIXED_SCALE = true;
        double width = RenderSystem.getWidth();
        double height = RenderSystem.getHeight();
        double sx = RenderSystem.getWidthNotScaled() / width;
        double sy = RenderSystem.getHeightNotScaled() / height;
        double x = mouseX / sx;
        double y = mouseY / sy;
        if (accountOverlay != null) {
            accountOverlay.onMouseClickReceived(x, y, mouseButton);
            return;
        }        if (step() == OnboardingStep.WELCOME) {
            double pad = Math.max(28, width * .055);
            double heroY = 76;
            double heroH = Math.min(height * .68, 420);
            double railY = heroY + heroH + 32;
            double buttonW = 154;
            double buttonX = width - pad - buttonW;
            double buttonY = railY - 13;
            if (inside(x, y, buttonX, buttonY, buttonW, 44)) {
                nextPage();
                return;
            }
            if (inside(x, y, buttonX - 92, buttonY, 88, 44)) {
                if (skipConfirm) OnboardingManager.skip(this);
                else skipConfirm = true;
                return;
            }
            return;
        }

        double left = surfaceLeft(width);
        double top = surfaceTop(height);
        double sw = surfaceWidth(width);
        double sh = surfaceHeight(height);
        double buttonW = 144;
        double buttonX = left + sw - buttonW - 38;
        double buttonY = top + sh - 72;
        if (inside(x, y, buttonX, buttonY, buttonW, 44)) {
            if (stepIndex == OnboardingStep.values().length - 1) OnboardingManager.finish(this);
            else nextPage();
            return;
        }
        if (stepIndex > 0 && inside(x, y, left + 28, buttonY, 110, 40)) {
            stepIndex--;
            pageStartedAt = System.nanoTime();
            skipConfirm = false;
            return;
        }
        if (stepIndex < OnboardingStep.values().length - 1 && inside(x, y, buttonX - 130, buttonY, 120, 44)) {
            if (skipConfirm) OnboardingManager.skip(this);
            else skipConfirm = true;
            return;
        }

        double contentX = left + sw * .385 + 48;
        double contentY = top + 42 + 34;
        double contentW = left + sw - contentX - 82;
        if (step() == OnboardingStep.ACCOUNT) {
            MusicPlatform[] platforms = {MusicPlatform.NETEASE, MusicPlatform.QQ, MusicPlatform.KUGOU};
            for (int i = 0; i < platforms.length; i++) {
                if (inside(x, y, contentX, contentY + 94 + i * 62, contentW, 48)) {
                    if (accountOverlay != null) accountOverlay.dispose();
                    accountOverlay = new AccountManagerOverlay();
                    accountOverlay.onInit();
                    return;
                }
            }
        } else if (step() == OnboardingStep.SOURCE) {
            MusicPlatform[] platforms = {MusicPlatform.NETEASE, MusicPlatform.QQ, MusicPlatform.KUGOU, MusicPlatform.GD};
            double cw = (contentW - 12) * .5;
            for (int i = 0; i < platforms.length; i++) {
                double cx = contentX + (i % 2) * (cw + 12);
                double cy = contentY + 94 + (i / 2) * 66;
                if (inside(x, y, cx, cy, cw, 52)) {
                    selectedPlatform = platforms[i];
                    NeteaseMusicService.setCurrentPlatform(selectedPlatform);
                    if (selectedPlatform == MusicPlatform.GD && !GdStudioSourceSettings.isEnabled()) {
                        List<GdStudioMusicService.Platform> options = GdStudioMusicService.getPlatforms();
                        if (!options.isEmpty()) GdStudioSourceSettings.setPlatform(options.get(0).key);
                    }
                    return;
                }
            }
        } else if (step() == OnboardingStep.APPEARANCE) {
            NCMTheme.ThemePreset[] themes = NCMTheme.ThemePreset.values();
            int start = selectedTheme.ordinal() - 1;
            double cw = (contentW - 24) / 3;
            for (int i = 0; i < 3; i++) {
                int idx = Math.floorMod(start + i, themes.length);
                double cx = contentX + i * (cw + 12);
                if (inside(x, y, cx, contentY + 95, cw, 110)) {
                    selectedTheme = themes[idx];
                    NCMTheme.setCurrent(selectedTheme);
                    return;
                }
            }
            double fontY = contentY + 235;
            if (inside(x, y, contentX, fontY + 24, contentW, 46) && !fonts.isEmpty()) {
                selectedFontIndex = (selectedFontIndex + 1) % fonts.size();
                UiFontLibrary.select(fonts.get(selectedFontIndex));
            }
        }
    }

    private void nextPage() {
        stepIndex = Math.min(OnboardingStep.values().length - 1, stepIndex + 1);
        pageStartedAt = System.nanoTime();
        skipConfirm = false;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (accountOverlay != null) {
            if (accountOverlay.onKeyTypedReceived(typedChar, keyCode)) return;
            if (keyCode == Keyboard.KEY_ESCAPE) accountOverlay.handleEscape();
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (stepIndex == 0) {
                if (skipConfirm) OnboardingManager.skip(this);
                else skipConfirm = true;
            } else {
                stepIndex--;
                pageStartedAt = System.nanoTime();
            }
        }
    }

    private static double surfaceLeft(double width) { return (width - surfaceWidth(width)) * .5; }
    private static double surfaceTop(double height) { return (height - surfaceHeight(height)) * .5; }
    private static double surfaceWidth(double width) { return Math.min(width * .83, 1120); }
    private static double surfaceHeight(double height) { return Math.min(height * .83, 700); }

    private String stepIcon() {
        switch (step()) {
            case ACCOUNT: return PartyIcons.PERSON;
            case SOURCE: return PartyIcons.RADIO;
            case APPEARANCE: return PartyIcons.TEXT_FORMAT;
            case FINISH: return PartyIcons.CHECK;
            default: return PartyIcons.NOTE;
        }
    }

    private static void drawPlatformIcon(MusicPlatform platform, double x, double y, int color) {
        if (platform == MusicPlatform.NETEASE) drawIcon(MusicBrandIcons.NETEASE_CLOUD_MUSIC, FontManager.musicBrand18, x, y, color);
        else if (platform == MusicPlatform.QQ) drawIcon(MusicBrandIcons.QQ_MUSIC, FontManager.qqMusicIcon20, x, y, color);
        else if (platform == MusicPlatform.KUGOU) drawIcon(MusicBrandIcons.KUGOU_MONOGRAM, FontManager.musicBrand18, x, y, color);
        else drawIcon(PartyIcons.RADIO, FontManager.partyIcon24, x, y, color);
    }

    private static void drawIcon(String icon, com.muoniumplayer.core.rendering.font.CFontRenderer font, double x, double y, int color) {
        if (font != null && icon != null) font.drawString(icon, x, y, color);
    }

    private static void drawText(com.muoniumplayer.core.rendering.font.CFontRenderer font, String text, double x, double y, int color) {
        if (font != null && text != null) font.drawString(text, x, y, color);
    }

    private static int colorWithAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    private static java.awt.Color awtColorWithAlpha(int color, int alpha) {
        return new java.awt.Color((color >>> 16) & 255, (color >>> 8) & 255, color & 255, Math.max(0, Math.min(255, alpha)));
    }
private static boolean inside(double mouseX, double mouseY, double x, double y, double width, double height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }
}
