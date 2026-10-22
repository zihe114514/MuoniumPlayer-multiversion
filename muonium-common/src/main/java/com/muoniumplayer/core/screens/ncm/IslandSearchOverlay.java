package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.api.features.ExtensionScreen;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.NeteaseMusicService;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.NeteaseSearchSuggestService;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.entities.impl.TextField;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.screens.ncm.panels.MusicWidget;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.utils.KeyboardUtils;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.render.skija.SkijaGfx;

import java.util.ArrayList;
import java.util.List;

/**
 * 灵动岛快捷搜索栏：游戏内 Ctrl+F（可改键）从灵动岛长出的搜索胶囊。
 *
 * <p>这是一个<b>全透明</b>的 Screen：{@code drawScreen} 不画任何背景暗幕（先例：
 * CoverflowOverlay / GuiHudEditor），游戏画面照常可见，{@code doesGuiPauseGame()} 为 false
 * 不暂停游戏。它存在的唯一理由是承接键盘字符通道与 IME（无 Screen 时 MC 没有字符事件），
 * 视觉上真正的「玻璃胶囊」由这条链自己画。</p>
 *
 * <p>分工：几何与岛侧交接在 {@link IslandSearchTransition}（复用
 * {@code DownloadDynamicIsland} 的转场钩子，通知队列在交接期间冻结、结束后恢复）；
 * 状态机/防抖/竞态在 {@link IslandSearchModel}；搜索与播放完全复用现有链路 ——
 * 结果行直接用 {@link MusicWidget}（左键播放/右键换源/中键选词三键行为白得），
 * 异步走 {@code CloudMusic.search} + {@code MultiThreadingUtil}，竞态防护用
 * {@code NavigationRequestGate} 同构的 revision 计数。</p>
 */
public final class IslandSearchOverlay extends ExtensionScreen implements SharedConstants {

    @lombok.Getter
    private static final IslandSearchOverlay INSTANCE = new IslandSearchOverlay();

    // ------------------------------------------------------------------ 胶囊几何（布局单位）

    /** 胶囊高：与 NavigateBar.searchBar 那一格同档。 */
    private static final double CAPSULE_HEIGHT = 56.0;
    /** 展开结果区最多显示几行（30px/行）。 */
    private static final int MAX_RESULT_ROWS = 6;
    private static final double ROW_HEIGHT = 30.0;
    /** 胶囊宽度：62% 屏宽、封顶 640（与 metaball 参照实现同档）。 */
    private static final double CAPSULE_WIDTH_FRACTION = 0.62;
    private static final double CAPSULE_WIDTH_MAX = 640.0;
    /** 胶囊顶部离屏：从岛（TOP_MARGIN=6）下面一点的位置长出来，不与岛抢同一行。 */
    private static final double CAPSULE_TOP = 84.0;
    /** 内容与胶囊边缘的内边距。 */
    private static final double CONTENT_INSET = 10.0;

    /** 控件树根：布局单位坐标，绘制与命中都在同一轴上。 */
    private final Panel root = new Panel();
    /** 胶囊底板：玻璃 PLATE 档。 */
    private final RoundedRectWidget capsuleBg = new RoundedRectWidget();
    /** 结果列的滚动容器：融合组（挨着的行按一块玻璃收尾，与 PlaylistPanel 同机制）。 */
    private ScrollPanel resultsPanel;
    /** 结果行宿主：负责把 ScrollPanel 摆进胶囊剩余高度。 */
    private final Panel resultsHost = new Panel();
    private final List<MusicWidget> resultRows = new ArrayList<>();
    /** 建行时代次：与 IslandSearchModel.resultsRevision 比对，变了才重建。 */
    private long builtResultsRevision = -1;

    /** 搜索输入框（裸 TextField，CoverflowOverlay 同款；自带 IME 与焦点管理）。 */
    private final TextField searchField = new TextField(0, 0, 0, 0, 0);
    /** 联想词防抖/缓存：与 NavigateBar 同一服务。 */
    private List<NeteaseSearchSuggestService.Suggestion> suggestions = List.of();
    private long suggestQueryChangedAt;
    private static final long SUGGEST_DEBOUNCE_MILLIS = 180L;
    private int suggestHighlight = -1;
    private boolean suggestionsVisible;

    /** 命中检测用的本帧胶囊矩形（布局坐标）。 */
    private double capsuleX, capsuleY, capsuleWidth, capsuleHeight;

    private IslandSearchOverlay() {
    }

    // ------------------------------------------------------------------ 打开 / 关闭

    /**
     * 游戏内唤出。播放器转场忙或功能被关时不动。
     *
     * <p>目标矩形在这里按当前屏幕宽算好传给转场 —— 打开中途窗口 resize 由
     * {@link #drawScreen} 每帧 {@code retarget} 纠正。</p>
     */
    public static void toggle() {
        if (!HudConfig.islandSearchEnabled) return;
        IslandSearchOverlay overlay = INSTANCE;
        if (IslandSearchTransition.isActive() || IslandSearchModel.isOpen()) {
            overlay.closeAndRestore();
            return;
        }
        double[] target = overlay.capsuleTarget();
        if (!IslandSearchTransition.open(target[0], target[1], target[2], target[3], target[4])) {
            return;
        }
        IslandSearchModel.reset();
        overlay.searchField.setText("");
        overlay.searchField.setPlaceholder("搜索歌曲...");
        overlay.searchField.setTextColor(-1);
        overlay.searchField.setDrawLineUnder(false);
        // 打开即聚焦：没有这一步，字符事件到来时 isFocused() 为 false，
        // textboxKeyTyped 直接把输入丢掉（真机截图里就是打不进字）。
        overlay.searchField.setFocused(true);
        overlay.suggestionsVisible = false;
        overlay.suggestHighlight = -1;
        api.displayScreen(overlay);
    }

    /** 关闭并把表面还给灵动岛。 */
    private void closeAndRestore() {
        IslandSearchTransition.close();
        IslandSearchModel.reset();
        this.searchField.setFocused(false);
        this.suggestionsVisible = false;
    }

    @Override
    public void initGui() {
        // 全透明浮层没有原版控件；真正的控件树在 drawScreen 里由 root.renderWidget 驱动。
    }

    /** 不暂停游戏（GuiHudEditor 同款）。 */
    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void onGuiClosed() {
        // 外部换屏（用户开了别的界面）时把岛要回来，不播撞击。
        IslandSearchTransition.abort();
        IslandSearchModel.reset();
        this.searchField.setFocused(false);
    }

    // ------------------------------------------------------------------ 每帧

    @Override
    public void drawScreen(int mouseX, int mouseY) {
        RenderSystem.refreshResolution();

        // 转场时钟：读几何之前必须先 tick（与 PlayerIslandTransition.tick 同一条规矩）。
        IslandSearchTransition.tick();

        // 窗口 resize / GUI Scale 变化后纠正目标矩形。
        double[] target = capsuleTarget();
        IslandSearchTransition.retarget(target[0], target[1], target[2], target[3], target[4]);

        // 转场落定收回后（geometry 已归零、岛已撞回）：此刻把屏幕还回去。
        // 多等一帧是为了让撞击波的第一帧先画出来，避免「胶囊消失 → 岛出现」中间空一帧。
        if (IslandSearchTransition.isIdle()) {
            IslandSearchModel.reset();
            api.displayScreen(null);
            return;
        }

        // 防抖到期发请求（输入即搜）。
        String due = IslandSearchModel.consumeDueSearch(System.currentTimeMillis());
        if (due != null) {
            submitSearch(due);
        } else {
            advanceSuggestions();
        }

        double[] bounds = IslandSearchTransition.surfaceBounds();
        this.capsuleX = bounds[0];
        this.capsuleY = bounds[1];
        this.capsuleWidth = bounds[2];
        this.capsuleHeight = bounds[3];
        double radius = bounds[4];
        float alpha = (float) IslandSearchTransition.contentAlpha();

        // ---- 布局轴：与播放器 drawScreen 同款 —— 除以修正因子后按 scale 画，命中与绘制同轴。
        // 先复位到已知 2D 状态：透明浮层叠在游戏画面上，不能继承上一段绘制留下的混合与颜色。
        SkijaGfx.pushMatrix();
        SkijaGfx.setBlendMode(io.github.humbleui.skija.BlendMode.SRC_OVER);
        SkijaGfx.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        api.getGLStateManager().pushMatrix();
        double xScale = RenderSystem.getWidthNotScaled() / RenderSystem.getWidth();
        double yScale = RenderSystem.getHeightNotScaled() / RenderSystem.getHeight();
        api.getGLStateManager().scale(xScale, yScale, 1);
        double mx = mouseX / xScale;
        double my = mouseY / yScale;

        try {
            drawCapsuleSurface(radius, alpha);
            drawCapsuleContent(mx, my, alpha);
        } finally {
            api.getGLStateManager().popMatrix();
            SkijaGfx.popMatrix();
        }
    }

    /** 玻璃胶囊表面：ISLAND 面的 paintFrost 铺底，铺不上退回深色底（GlassLook 内建降级）。 */
    private void drawCapsuleSurface(double radius, float alpha) {
        if (alpha <= .02f) return;
        boolean frosted = GlassLook.paintFrost(GlassLook.Surface.ISLAND,
                capsuleX, capsuleY, capsuleWidth, capsuleHeight, radius, alpha);
        int base = NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND);
        // frosted=false（手机档/主题没开/这一帧还没采到背景）时给一层压薄的实色，
        // 观感退回「深色胶囊」，绝不会画成透明洞。
        int argb = GlassLook.tintedArgb(GlassLook.Surface.ISLAND,
                base | ((int) (alpha * (frosted ? 255 : 224)) << 24));
        capsuleBg.setRadius(radius);
        // setBounds 的四参版会连位置一起写，写 (0,0,w,h) 就把胶囊拍回屏幕原点
        //（2026-10-21 真机截图）。位置与尺寸分开设。
        capsuleBg.setBounds(capsuleX, capsuleY, capsuleWidth, capsuleHeight);
        capsuleBg.setColor(argb);
        capsuleBg.setAlpha(alpha);
        // 直接画这一块（不进控件树：它没有子控件也没有命中语义）。
        capsuleBg.renderWidget(-1, -1, 0);
    }

    /** 胶囊内容：输入框 + 结果列表。内容 alpha 跟随转场（比表面晚一档浮现）。 */
    private void drawCapsuleContent(double mouseX, double mouseY, float alpha) {
        if (alpha <= .05f) return;

        root.setBounds(capsuleWidth, capsuleHeight);
        root.setPosition(capsuleX, capsuleY);
        root.setAlpha(alpha);

        // 输入框：转场期间跟着几何走，落定后占满胶囊上部。
        this.searchField.xPosition = (float) (capsuleX + CONTENT_INSET);
        this.searchField.yPosition = (float) (capsuleY + CONTENT_INSET);
        this.searchField.width = (float) Math.max(40.0, capsuleWidth - CONTENT_INSET * 2.0);
        this.searchField.height = (float) Math.min(24.0, Math.max(12.0, capsuleHeight * 0.42));
        this.searchField.setWholeAlpha(alpha);
        this.searchField.setFontRenderer(FontManager.pf14bold);
        // 胶囊自带玻璃边，输入框那条 1.8.9 式下划线是多余的（真机截图里的粉色横线就是它）。
        this.searchField.setDrawLineUnder(false);
        this.searchField.setCanLoseFocus(false);
        this.searchField.setEnabled(true);

        double listTop = CONTENT_INSET + this.searchField.height + 6.0;
        double listHeight = Math.max(0.0, capsuleHeight - listTop - CONTENT_INSET);

        ensureResultRows();
        resultsHost.setBounds(Math.max(1.0, capsuleWidth - CONTENT_INSET * 2.0), Math.max(0.0, listHeight));
        resultsHost.setPosition(CONTENT_INSET, listTop);
        resultsHost.setAlpha(alpha);

        int wheel = com.muoniumplayer.compat.lwjgl2.Mouse.getDWheel();
        root.renderWidget(mouseX, mouseY, wheel);

        // 输入框画在控件树之上（它不是树成员，命中在 keyTyped/mouseClicked 里手工转发）。
        this.searchField.drawTextBox((int) mouseX, (int) mouseY);

        drawSuggestions(mouseX, mouseY, alpha);
        drawEmptyState(alpha);
    }

    /** 结果代次变了才重建行；MusicWidget 树的创建是贵的，一帧最多一小批由 ScrollPanel 吸收。 */
    private void ensureResultRows() {
        long revision = IslandSearchModel.resultsRevision();
        if (revision == this.builtResultsRevision) return;
        this.builtResultsRevision = revision;
        rebuildResultRows();
    }

    private void rebuildResultRows() {
        if (this.resultsPanel != null) {
            this.resultsPanel.getChildren().forEach(AbstractWidget::onViewportExit);
        }
        this.resultRows.clear();
        this.resultsPanel = new ScrollPanel();
        this.resultsPanel.setSpacing(0).setUniformVerticalItemHeight(ROW_HEIGHT);
        // 不点名融合（setGlassFusion）：全仓融合落点由 GlassEdgeFusionStaticTest 钉死为两处，
        // 这里的小列表行数少、彼此间距为零时行自带的 PLATE 已经足够；
        // 真要把这一列也融成一块板，先数清 PLATE 再去改那份测试的计数。

        PlayList playlist = IslandSearchModel.resultPlaylist();
        List<Music> results = IslandSearchModel.results();
        int rows = Math.min(results.size(), MAX_RESULT_ROWS * 4);
        List<AbstractWidget<?>> batch = new ArrayList<>(rows);
        for (int index = 0; index < rows; index++) {
            MusicWidget row = new MusicWidget(results.get(index), playlist, index);
            row.setShouldOverrideMouseCursor(true);
            batch.add(row);
            this.resultRows.add(row);
        }
        this.resultsPanel.addChild(batch);
        this.resultsHost.addChild(this.resultsPanel);
        this.resultsPanel.invalidateLayout();
    }

    /** 空状态提示：没有结果且不在加载时给一句占位。 */
    private void drawEmptyState(float alpha) {
        if (!IslandSearchModel.results().isEmpty() || IslandSearchModel.isAwaitingResults()) return;
        if (IslandSearchModel.pendingKeyword().length() > 0 && !this.suggestionsVisible) return;
        var fr = FontManager.pf14bold;
        // 直接用字体画在结果区左上角，不借 LabelWidget —— 裸 renderWidget 走的是
        // 控件自身坐标（默认 0,0），上一版因此画到了屏幕原点。
        fr.drawString("输入关键词搜索歌曲 · 回车立即搜 · Esc 收回",
                capsuleX + CONTENT_INSET + 2.0, capsuleY + CONTENT_INSET + this.searchField.height + 12.0,
                1.0, RenderSystem.reAlpha(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT), alpha));
    }

    // ------------------------------------------------------------------ 联想词

    /** 每帧驱动：180ms 防抖 prefetch + peek（与 NavigateBar.layoutSuggestions 同一服务）。 */
    private void advanceSuggestions() {
        String text = this.searchField.getText() == null ? "" : this.searchField.getText().trim();
        boolean eligible = text.length() > 0
                && !text.equals(IslandSearchModel.searchedKeyword())
                && supportsSuggestions();
        if (eligible && System.currentTimeMillis() - this.suggestQueryChangedAt >= SUGGEST_DEBOUNCE_MILLIS) {
            NeteaseSearchSuggestService.prefetch(text);
        }
        this.suggestions = eligible ? NeteaseSearchSuggestService.peek(text) : List.of();
        this.suggestionsVisible = !this.suggestions.isEmpty();
        if (!this.suggestionsVisible) this.suggestHighlight = -1;
    }

    private static boolean supportsSuggestions() {
        MusicPlatform current = NeteaseMusicService.getCurrentPlatform();
        return current == MusicPlatform.NETEASE || current == MusicPlatform.KUGOU
                || current == MusicPlatform.QISHUI;
    }

    /** 联想词画在胶囊下缘，命中在 mouseClicked 里处理。 */
    private void drawSuggestions(double mouseX, double mouseY, float alpha) {
        if (!this.suggestionsVisible) return;
        double y = capsuleY + Math.min(capsuleHeight, CAPSULE_HEIGHT) + 4.0;
        for (int i = 0; i < this.suggestions.size() && i < 6; i++) {
            NeteaseSearchSuggestService.Suggestion suggestion = this.suggestions.get(i);
            boolean hovered = mouseY >= y && mouseY < y + 24.0 && mouseX >= capsuleX
                    && mouseX < capsuleX + capsuleWidth;
            boolean highlighted = hovered || i == this.suggestHighlight;
            int bg = NCMScreen.getColor(highlighted
                    ? NCMScreen.ColorType.ELEMENT_HOVER : NCMScreen.ColorType.GENERIC_BACKGROUND);
            RoundedRectWidget rowBg = new RoundedRectWidget();
            rowBg.setRadius(8);
            rowBg.setBounds(0, 0, capsuleWidth, 22);
            rowBg.setColor(bg | ((int) (alpha * 255) << 24));
            rowBg.renderWidget(-1, -1, 0);
            drawSuggestionRow(suggestion.text, suggestion.detail,
                    capsuleX + CONTENT_INSET, y + 5.0, alpha,
                    highlighted ? NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT)
                            : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            y += 24.0;
        }
    }

    private int suggestionIndexAt(double mouseX, double mouseY) {
        if (!this.suggestionsVisible) return -1;
        double top = capsuleY + Math.min(capsuleHeight, CAPSULE_HEIGHT) + 4.0;
        if (mouseX < capsuleX || mouseX > capsuleX + capsuleWidth) return -1;
        for (int i = 0; i < this.suggestions.size() && i < 6; i++) {
            if (mouseY >= top + i * 24.0 && mouseY < top + i * 24.0 + 22.0) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ 搜索

    /** 发请求（工作线程）→ 落地（主线程，revision 校验）。与 NavigateBar.submitSearch 同构。 */
    private static void submitSearch(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return;
        final String query = keyword.trim();
        final long ticket = IslandSearchModel.beginRequest();
        MultiThreadingUtil.runAsync(() -> {
            List<Music> search = CloudMusic.search(query);
            MultiThreadingUtil.runOnMainThread(() -> IslandSearchModel.deliverResults(ticket, search));
        });
        INSTANCE.suggestionsVisible = false;
        INSTANCE.suggestHighlight = -1;
    }

    // ------------------------------------------------------------------ 输入

    @Override
    public void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            this.closeAndRestore();
            return;
        }

        // Ctrl+F：搜索栏开着时再按一次 = 收回（与 toggle() 的语义一致）。
        if (keyCode == Keyboard.KEY_F && KeyboardUtils.isCtrlKeyDown()) {
            this.closeAndRestore();
            return;
        }

        // 回车：有高亮联想词就用它，否则直接搜输入框原文（绕过防抖）。
        if ((keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER)
                && this.searchField.isFocused()) {
            String keyword = highlightedSuggestionOrText();
            String due = IslandSearchModel.consumeImmediateSearch(keyword);
            if (due != null) {
                submitSearch(due);
            }
            return;
        }

        // ↑/↓ 在联想词与结果行之间移动高亮；回车落在结果行上直接播放。
        if (keyCode == Keyboard.KEY_DOWN || keyCode == Keyboard.KEY_UP) {
            if (this.suggestionsVisible
                    && moveHighlight(keyCode == Keyboard.KEY_DOWN ? 1 : -1)) {
                return;
            }
            if (moveResultSelection(keyCode == Keyboard.KEY_DOWN ? 1 : -1)) {
                return;
            }
        }

        this.searchField.textboxKeyTyped(typedChar, keyCode);
        IslandSearchModel.onInputChanged(this.searchField.getText(), System.currentTimeMillis());
        this.suggestQueryChangedAt = System.currentTimeMillis();
    }

    private String highlightedSuggestionOrText() {
        if (this.suggestHighlight >= 0 && this.suggestHighlight < this.suggestions.size()) {
            return this.suggestions.get(this.suggestHighlight).text;
        }
        return this.searchField.getText() == null ? "" : this.searchField.getText();
    }

    private boolean moveHighlight(int delta) {
        int next = this.suggestHighlight + delta;
        if (next < -1 || next >= Math.min(this.suggestions.size(), 6)) return false;
        this.suggestHighlight = next;
        return true;
    }

    /** 结果行键盘选择：高亮行直接起播（搜索结果走 playFromSearchSelection，接最近播放）。 */
    private boolean moveResultSelection(int delta) {
        List<Music> results = IslandSearchModel.results();
        if (results.isEmpty()) return false;
        int max = Math.min(results.size(), MAX_RESULT_ROWS * 4) - 1;
        int current = this.resultHighlight;
        int next = Math.max(0, Math.min(max, current + delta));
        if (next == current && delta != 0 && current >= 0) return false;
        this.resultHighlight = next;
        return true;
    }

    private int resultHighlight = -1;

    @Override
    public void mouseClicked(int mX, int mY, int mouseButton) {
        double xScale = RenderSystem.getWidthNotScaled() / RenderSystem.getWidth();
        double yScale = RenderSystem.getHeightNotScaled() / RenderSystem.getHeight();
        double mouseX = mX / xScale;
        double mouseY = mY / yScale;

        // 联想词优先（它们画在胶囊之外）。
        int suggestion = suggestionIndexAt(mouseX, mouseY);
        if (suggestion >= 0) {
            String completion = this.suggestions.get(suggestion).text;
            this.searchField.setText(completion);
            this.searchField.setCursorPositionEnd();
            this.suggestHighlight = -1;
            String due = IslandSearchModel.consumeImmediateSearch(completion);
            if (due != null) submitSearch(due);
            return;
        }

        // 输入框命中。
        if (this.searchField.mouseClicked(mX, mY, mouseButton)) {
            return;
        }

        // 结果区命中：交给控件树（MusicWidget 三键行为）。
        if (mouseY >= capsuleY + CONTENT_INSET + this.searchField.height
                && mouseY <= capsuleY + capsuleHeight) {
            this.resultsPanel.onMouseClickReceived(mouseX, mouseY, mouseButton);
            return;
        }

        // 点空白处收回（胶囊与联想之外都是空白）。
        if (mouseX < capsuleX || mouseX > capsuleX + capsuleWidth
                || mouseY < capsuleY || mouseY > capsuleY + capsuleHeight) {
            this.closeAndRestore();
        }
    }

    // ------------------------------------------------------------------ 几何

    /** 搜索胶囊目标矩形（布局坐标）：居中、62% 宽封顶 640、高随结果行数长出来。 */
    private double[] capsuleTarget() {
        double screenW = RenderSystem.getWidth();
        double width = Math.min(CAPSULE_WIDTH_MAX, screenW * CAPSULE_WIDTH_FRACTION);
        // 展开高度 = 输入行 + 结果行；没有结果时只有输入行。转场里高度一起插值。
        int rows = Math.min(IslandSearchModel.results().size(), MAX_RESULT_ROWS);
        double height = Math.max(CAPSULE_HEIGHT,
                CONTENT_INSET + 24.0 + 6.0 + rows * ROW_HEIGHT + CONTENT_INSET);
        double x = screenW * .5 - width * .5;
        return new double[]{x, CAPSULE_TOP, width, height, 14.0};
    }

    /**
     * 联想行绘制：主词 + 灰色小注（类型/释义），单行。
     */
    private void drawSuggestionRow(String text, String detail, double x, double y,
                                   float alpha, int color) {
        var fr = FontManager.pf14bold;
        String main = fr.trim(text == null ? "" : text, capsuleWidth * .55);
        fr.drawString(main, x, y, 1.0, RenderSystem.reAlpha(color, alpha));
        if (detail != null && !detail.isEmpty()) {
            var frSmall = FontManager.pf12bold;
            String tail = frSmall.trim(detail, capsuleWidth * .30);
            frSmall.drawString(tail, x + fr.getStringWidthD(main) + 10.0, y + 2.0, 1.0,
                    RenderSystem.reAlpha(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT), alpha));
        }
    }
}
