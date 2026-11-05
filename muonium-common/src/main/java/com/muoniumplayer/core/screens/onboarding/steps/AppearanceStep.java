package com.muoniumplayer.core.screens.onboarding.steps;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.management.UiFont;
import com.muoniumplayer.core.management.UiFontLibrary;
import com.muoniumplayer.core.rendering.FoliaLucideIcons;
import com.muoniumplayer.core.rendering.RGBA;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.screens.onboarding.OnboardingController;
import com.muoniumplayer.core.screens.onboarding.OnboardingMetrics;
import com.muoniumplayer.core.screens.onboarding.OnboardingStepView;
import com.muoniumplayer.core.screens.onboarding.OnboardingUi;
import com.muoniumplayer.core.settings.HudSetting;
import com.muoniumplayer.party.ui.PartyIcons;

/** 仅由既有主题、字体和动画设置构建的外观页。 */
public final class AppearanceStep implements OnboardingStepView {

    /** 一张主题卡：上面那块迷你播放器预览 + 下面名称与状态，六页里唯一会「变样」的一张卡。 */
    private static final double BASE_CARD_H = 126;
    /** 预览块在卡里的内缩与高度。 */
    private static final double PREVIEW_INSET = 10;
    private static final double PREVIEW_H = 58;
    /** 字体块与步进器的高度。绘制与命中测试读同一份。 */
    static final double FONT_BOX_H = 46;
    /** 界面动画那个步进器：卡高、加减按钮的宽高与内缩。 */
    static final double STEPPER_H = 48;
    static final double STEP_BUTTON_W = 28;
    static final double STEP_BUTTON_H = 18;
    static final double STEP_BUTTON_INSET = 12;

    @Override
    public void render(OnboardingController controller,
                       double x, double y, double width, double height,
                       double mouseX, double mouseY) {
        OnboardingUi.drawHeader("APPEARANCE", "找到你的风格",
                "主题、字体和动画会实时预览，并使用现有样式配置。", x, y);

        NCMTheme.ThemePreset[] themes = NCMTheme.ThemePreset.values();
        int start = controller.selectedTheme().ordinal() - 1;
        double cardY = cardY(y);
        for (int i = 0; i < 3; i++) {
            NCMTheme.ThemePreset theme = themes[Math.floorMod(start + i, themes.length)];
            double cardX = cardX(x, width, i);
            boolean selected = theme == controller.selectedTheme();
            boolean hover = OnboardingUi.inside(mouseX, mouseY, cardX, cardY, cardWidth(width), cardHeight());
            drawCard(controller, theme, cardX, cardY, cardWidth(width), selected, hover);
        }

        double fontY = fontY(y);
        drawSectionTitle(FoliaLucideIcons.TYPOGRAPHY, PartyIcons.TEXT_FORMAT, "字体", x, fontY);
        UiFont font = controller.selectedFont();
        String name = font == null ? "内置字体" : font.getDisplayName();
        // 字体名放得下才画在段标题右边，放不下就整段收掉——段标题本身已经说了这是「字体」。
        OnboardingUi.drawTextReserving(OnboardingUi.metric().bodyFont(), name + " · 点击切换",
                x + 22 + OnboardingUi.textWidth(OnboardingUi.metric().labelBold(), "字体") + 12, fontY + 2,
                width - 22, 0, OnboardingUi.textMuted());

        double fontBoxY = fontBoxY(y);
        OnboardingUi.roundedRect(x, fontBoxY, width, FONT_BOX_H, OnboardingUi.CARD_RADIUS - 1,
                OnboardingUi.surfaceHover());
        OnboardingUi.roundedOutline(x, fontBoxY, width, FONT_BOX_H, OnboardingUi.CARD_RADIUS - 1, 1,
                OnboardingUi.awtColorWithAlpha(OnboardingUi.hairline(), 170));
        // 样例文字按框宽截断：换一套宽字体的样例串可能比整张卡还长。
        // 字号固定 pf18 而不跟档位：这一块展示的是<b>玩家选的那套字体</b>，
        // 让它跟着引导的排版档一起缩，等于把「预览」也变成了随窗口变的东西。
        OnboardingUi.drawText(FontManager.pf18,
                OnboardingUi.ellipsize(FontManager.pf18, UiFontLibrary.sampleText(),
                        width - 32), x + 16, fontBoxY + (FONT_BOX_H - 18) * .5, OnboardingUi.CYAN);

        double animationY = animationY(y);
        drawSectionTitle(FoliaLucideIcons.SPEED, PartyIcons.SYNC, "界面动画", x, animationY);
        drawStepper(controller, HudSetting.PLAYER_UI_SWITCH_SPEED,
                x, stepperY(y), width, mouseX, mouseY);
    }

    /**
     * 一张主题卡。
     *
     * <p>卡里那块<b>迷你播放器</b>才是这一页的重点：玩家挑的就是「换上去长什么样」，
     * 而两行字加一条色带说明不了任何事。所以按这套主题的<b>真实配色</b>画一个缩小到
     * 1:6 的面板——面板底、封面方块、两行文字、一条走到三成六的进度条，选中那一张走到六成二。</p>
     *
     * <p>这些颜色<b>必须自己叠 alpha</b>：{@code ThemePreset.getColor} 回的是裸 RGB
     * （{@code 0x00RRGGBB}），直接当 ARGB 用等于「全透明」，一笔都不会画。这一页原来就是这么写的，
     * 于是三张卡的预览块整块是隐形的，玩家看到的是三个空壳。</p>
     */
    private static void drawCard(OnboardingController controller, NCMTheme.ThemePreset theme,
                                 double x, double y, double width, boolean selected, boolean hover) {
        // 手机档：真玻璃主题（液态玻璃/琉璃）不可选（见 NCMTheme.isSelectable）。
        // 卡片不响应悬停高亮，预览压一层底色纱，右下角标「手机不可用」。
        boolean lockedTheme = !NCMTheme.isSelectable(theme);
        hover &= !lockedTheme;
        OnboardingUi.roundedRect(x, y, width, cardHeight(), OnboardingUi.CARD_RADIUS + 1,
                lockedTheme ? OnboardingUi.surface()
                        : selected ? of(theme, NCMScreen.ColorType.ACCENT, .16f)
                        : (hover ? OnboardingUi.surfaceHover() : OnboardingUi.surface()));
        if (selected) {
            OnboardingUi.roundedOutline(x, y, width, cardHeight(), OnboardingUi.CARD_RADIUS + 1, 1.6,
                    OnboardingUi.awtColorWithAlpha(of(theme, NCMScreen.ColorType.ACCENT, 1.0f), 235));
        }
        drawMiniPlayer(theme, x + PREVIEW_INSET, y + PREVIEW_INSET,
                width - PREVIEW_INSET * 2, selected);

        // 卡高会随档位长高，名称与状态两行得跟着下移到「预览块之下、卡片之内」居中，
        // 否则大屏上它们贴死在预览块下面，卡片下半截空一大块。
        double nameBlockTop = y + PREVIEW_INSET + PREVIEW_H + 10;
        double nameBlockBottom = y + cardHeight() - PREVIEW_INSET;
        double labelY = nameBlockTop + Math.max(0, (nameBlockBottom - nameBlockTop - 30) * .5);
        OnboardingUi.drawIcon(FoliaLucideIcons.PALETTE, PartyIcons.TEXT_FORMAT,
                x + 19, labelY + 9, 14, of(theme, NCMScreen.ColorType.ACCENT, 1.0f));
        OnboardingUi.drawText(OnboardingUi.metric().labelBold(),
                OnboardingUi.ellipsize(OnboardingUi.metric().labelBold(), theme.getDisplayName(),
                        width - 36),
                x + 32, labelY + 2, lockedTheme ? OnboardingUi.textMuted() : OnboardingUi.textPrimary());
        OnboardingUi.drawText(FontManager.pf10bold,
                lockedTheme ? "手机不可用" : (selected ? "SELECTED" : "PREVIEW"),
                x + 12, labelY + 20,
                lockedTheme ? OnboardingUi.textMuted()
                        : (selected ? OnboardingUi.CYAN : OnboardingUi.textMuted()));
    }

    /** 卡里那块迷你播放器：面板底 + 封面 + 两行字 + 进度条，全部用这套主题自己的颜色。 */
    private static void drawMiniPlayer(NCMTheme.ThemePreset theme, double x, double y,
                                       double width, boolean selected) {
        OnboardingUi.roundedRect(x, y, width, PREVIEW_H, 11,
                of(theme, NCMScreen.ColorType.GENERIC_BACKGROUND, 1.0f));
        OnboardingUi.roundedOutline(x, y, width, PREVIEW_H, 11, 1,
                OnboardingUi.awtColorWithAlpha(
                        of(theme, NCMScreen.ColorType.BORDER, 1.0f), 120));

        double cover = 22;
        double inset = 8;
        OnboardingUi.roundedRect(x + inset, y + inset, cover, cover, 6,
                of(theme, NCMScreen.ColorType.ACCENT, 1.0f));
        // 封面里那一枚音符用主题自己的主文字色，读起来才是「这块面板上的控件」。
        OnboardingUi.drawIcon(FoliaLucideIcons.NOTE, PartyIcons.NOTE,
                x + inset + cover * .5, y + inset + cover * .5,
                cover * .58f, of(theme, NCMScreen.ColorType.PRIMARY_TEXT, 1.0f));

        double textX = x + inset + cover + 8;
        double textW = Math.max(12, x + width - inset - 8 - textX);
        OnboardingUi.roundedRect(textX, y + inset + 2, textW * .72, 4, 2,
                of(theme, NCMScreen.ColorType.PRIMARY_TEXT, .9f));
        OnboardingUi.roundedRect(textX, y + inset + 11, textW * .44, 3, 1.5,
                of(theme, NCMScreen.ColorType.SECONDARY_TEXT, .6f));

        double barY = y + PREVIEW_H - inset - 3;
        OnboardingUi.roundedRect(x + inset, barY, width - inset * 2, 3, 1.5,
                of(theme, NCMScreen.ColorType.PRIMARY_TEXT, .2f));
        OnboardingUi.roundedRect(x + inset, barY,
                (width - inset * 2) * (selected ? .62 : .36), 3, 1.5,
                of(theme, NCMScreen.ColorType.ACCENT, 1.0f));
        double knob = 6;
        OnboardingUi.roundedRect(x + inset + (width - inset * 2) * (selected ? .62 : .36) - knob * .5,
                barY - (knob - 3) * .5, knob, knob, knob * .5,
                of(theme, NCMScreen.ColorType.ACCENT, 1.0f));
    }

    private static void drawSectionTitle(String lucide, String fallback, String title,
                                         double x, double y) {
        OnboardingUi.drawIcon(lucide, fallback, x + 7, y + 8, 14, OnboardingUi.textMuted());
        OnboardingUi.drawText(OnboardingUi.metric().labelBold(), title, x + 22, y, OnboardingUi.textPrimary());
    }

    /**
     * 一行步进器：左边标签、中间数值、右边加减。
     *
     * <p>原来标签写死 {@code x + 14}、数值 {@code x + width * .5}，卡宽跟着容器缩到某一档时
     * 两者直接压在一起（标签「界面动画」四个字就有一百来像素）。现在先量：标签放得下才画，
     * 数值只在「标签右边到加号左边」这一截里居中——两头都是实测的，不再靠猜半宽。</p>
     */
    private static void drawStepper(OnboardingController controller, HudSetting setting,
                                    double x, double y, double width,
                                    double mouseX, double mouseY) {
        OnboardingUi.roundedRect(x, y, width, STEPPER_H, OnboardingUi.CARD_RADIUS - 1,
                OnboardingUi.surfaceHover());
        OnboardingUi.roundedOutline(x, y, width, STEPPER_H, OnboardingUi.CARD_RADIUS - 1, 1,
                OnboardingUi.awtColorWithAlpha(OnboardingUi.hairline(), 165));

        double buttonSize = Math.min(STEP_BUTTON_W, Math.max(18, width * .16));
        double minusX = x + STEP_BUTTON_INSET;
        double plusX = x + width - STEP_BUTTON_INSET - buttonSize;

        // 标签在左、数值在右，两者之间的空隙是「量出来的」：标签宽度实测，数值自己量。
        double labelWidth = OnboardingUi.textWidth(OnboardingUi.metric().labelBold(), setting.getLabel());
        String value = String.format(java.util.Locale.ROOT, "%.2fx", controller.hudValue(setting));
        double valueWidth = OnboardingUi.textWidth(OnboardingUi.metric().labelBold(), value);
        double topRowRight = x + width - STEP_BUTTON_INSET;
        boolean sameRow = 14 + labelWidth + 10 + valueWidth <= width - STEP_BUTTON_INSET * 2;
        if (sameRow) {
            OnboardingUi.drawText(OnboardingUi.metric().labelBold(), setting.getLabel(), x + 14, y + 10,
                    OnboardingUi.textSecondary());
            OnboardingUi.drawText(OnboardingUi.metric().labelBold(), value, topRowRight - valueWidth, y + 10,
                    OnboardingUi.textPrimary());
        } else {
            // 窄档：标签先截断（它左边那截最有信息量），数值另起一行居中，谁也压不到谁。
            OnboardingUi.drawTextReserving(OnboardingUi.metric().labelBold(), setting.getLabel(),
                    x + 14, y + 8, width - STEP_BUTTON_INSET, STEP_BUTTON_INSET,
                    OnboardingUi.textSecondary());
            OnboardingUi.drawCentered(OnboardingUi.metric().labelBold(), value, x + width * .5,
                    y + STEPPER_H - 26, OnboardingUi.textPrimary());
        }

        drawStepButton(minusX, y + STEPPER_H - 26, buttonSize, "-", mouseX, mouseY);
        drawStepButton(plusX, y + STEPPER_H - 26, buttonSize, "+", mouseX, mouseY);
    }

    /** 加减按钮：只画，按下在哪由 {@link #mouseClicked} 用同一份坐标算。 */
    private static void drawStepButton(double x, double y, double size, String glyph,
                                       double mouseX, double mouseY) {
        boolean hover = OnboardingUi.inside(mouseX, mouseY, x, y, size, STEP_BUTTON_H);
        OnboardingUi.roundedRect(x, y, size, STEP_BUTTON_H, STEP_BUTTON_H * .5,
                hover ? OnboardingUi.colorWithAlpha(OnboardingUi.BLUE, 190) : OnboardingUi.surface());
        OnboardingUi.drawCentered(OnboardingUi.metric().labelBold(), glyph, x + size * .5, y + 4,
                OnboardingUi.textPrimary());
    }

    /** 主题色 + alpha。见 {@link #drawCard} 里那段「裸 RGB 当 ARGB 用就整块透明」的说明。 */
    private static int of(NCMTheme.ThemePreset theme, NCMScreen.ColorType type, float alpha) {
        return RGBA.color(theme.getColor(type), alpha);
    }

    // 卡片与两段的几何只有这一份算法，绘制与命中测试都读它。
    // 以前 render 用 y + 95、mouseClicked 用 y + 95 各写一遍字面量，
    // 改一处忘一处就会出现「看得见却点不到」——上一轮在小窗口上正是这个症状。

    static double cardWidth(double width) {
        return (width - 24) / 3;
    }

    static double cardX(double x, double width, int index) {
        return x + index * (cardWidth(width) + 12);
    }

    static double cardY(double y) {
        return y + OnboardingUi.metric().bodyOffset;
    }

    /** 主题卡高：预览块是固定比例的小播放器，卡高只加下面那两行字的余量。 */
    static double cardHeight() {
        return BASE_CARD_H + Math.max(0, OnboardingUi.metric().cardHeight - 56) * .5;
    }

    /** 段与段之间的纵向留白，跟着档位放：大屏每段之间更松，紧凑档收紧。 */
    private static double sectionGap() {
        return OnboardingUi.metric().tier == OnboardingMetrics.Tier.COMPACT ? 14
                : OnboardingUi.metric().tier == OnboardingMetrics.Tier.REGULAR ? 22
                : 30;
    }

    /** 字体段标题的 y（相对页顶）。 */
    static double fontY(double y) {
        return cardY(y) + cardHeight() + sectionGap();
    }

    /** 字体块（那块点一下就换字体的卡片）的 y。 */
    static double fontBoxY(double y) {
        return fontY(y) + 22 + sectionGap() * .1;
    }

    /** 「界面动画」段标题的 y。 */
    static double animationY(double y) {
        return fontBoxY(y) + FONT_BOX_H + sectionGap() + 4;
    }

    /** 步进器卡的 y。 */
    static double stepperY(double y) {
        return animationY(y) + 22 + sectionGap() * .1;
    }

    /** 加减按钮的尺寸，随卡宽收放；绘制与命中测试读同一份。 */
    static double stepButtonWidth(double width) {
        return Math.min(STEP_BUTTON_W, Math.max(18, width * .16));
    }

    /** 某一侧加减按钮的 x。{@code plus} 为 true 取右边那枚。 */
    static double stepButtonX(double x, double width, boolean plus) {
        double size = stepButtonWidth(width);
        return plus ? x + width - STEP_BUTTON_INSET - size : x + STEP_BUTTON_INSET;
    }

    /** 加减按钮的 y（在步进器卡里）。 */
    static double stepButtonY(double y) {
        return stepperY(y) + STEPPER_H - 26;
    }

    @Override
    public boolean mouseClicked(OnboardingController controller,
                                 double x, double y, double width, double height,
                                 double mouseX, double mouseY, int mouseButton) {
        if (mouseButton != 0) {
            return false;
        }

        NCMTheme.ThemePreset[] themes = NCMTheme.ThemePreset.values();
        int start = controller.selectedTheme().ordinal() - 1;
        double cardY = cardY(y);
        for (int i = 0; i < 3; i++) {
            double cardX = cardX(x, width, i);
            if (OnboardingUi.inside(mouseX, mouseY, cardX, cardY, cardWidth(width), cardHeight())) {
                NCMTheme.ThemePreset target = themes[Math.floorMod(start + i, themes.length)];
                // 手机档点真玻璃主题卡：吞掉点击但什么都不做（卡片已画成灰的）。
                if (NCMTheme.isSelectable(target)) {
                    controller.selectTheme(target);
                }
                return true;
            }
        }

        if (OnboardingUi.inside(mouseX, mouseY, x, fontBoxY(y), width, FONT_BOX_H)) {
            controller.cycleFont();
            return true;
        }

        double buttonSize = stepButtonWidth(width);
        double buttonY = stepButtonY(y);
        if (OnboardingUi.inside(mouseX, mouseY,
                stepButtonX(x, width, false), buttonY, buttonSize, STEP_BUTTON_H)) {
            controller.adjustHudSetting(HudSetting.PLAYER_UI_SWITCH_SPEED, -0.10f);
            return true;
        }
        if (OnboardingUi.inside(mouseX, mouseY,
                stepButtonX(x, width, true), buttonY, buttonSize, STEP_BUTTON_H)) {
            controller.adjustHudSetting(HudSetting.PLAYER_UI_SWITCH_SPEED, 0.10f);
            return true;
        }
        return false;
    }
}
