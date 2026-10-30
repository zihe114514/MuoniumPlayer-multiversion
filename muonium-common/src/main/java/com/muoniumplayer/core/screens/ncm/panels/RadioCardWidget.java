package com.muoniumplayer.core.screens.ncm.panels;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.dto.DjRadio;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.texture.Textures;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedImageWidget;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.utils.Location;

import java.util.Collection;

/**
 * 电台 / 播客 / DIFM 频道 / 广播电台共用的封面卡片。
 *
 * <p>视觉语言与主页歌单卡（{@code HomePanel.PlaylistWidget}）一致：方形封面 + 悬停外扩 +
 * 两行文字，配 {@link PanelItemEntrance} 错峰进场。区别是这里不做接力飞行（电台详情页的
 * hero 封面走的是普通交叉转场，没有主页那张卡片的接力意义），也没有网格重排补间 ——
 * 电台页的网格列数只随窗口宽度变化，直接跟手重排就够了。</p>
 *
 * <p>列宽由所属面板按视口宽度算好，通过 {@link #layoutGrid} 每帧分发给所有卡片；
 * 卡片自身的高度跟着封面边长走。</p>
 */
public class RadioCardWidget extends AbstractWidget<RadioCardWidget> {

    private static final double COVER_RADIUS = 4.0;
    private static final double COVER_HOVER_EXPANSION = 5.0;
    private static final double TITLE_TOP_GAP = 4.0;
    /** 网格列间距，与主页网格同档。 */
    public static final double GRID_SPACING = 8.0;
    /** 单列最小宽度：低于它就不再加列。 */
    private static final double MIN_COLUMN_WIDTH = 110.0;
    /** 封面之下的文字块高度（标题一行 + 副标题一行）。 */
    private static final double TEXT_BLOCK_HEIGHT = 36.0;

    private final DjRadio radio;
    private final Runnable clickAction;
    /** 右键动作（DIFM / 广播的收藏开关）；电台卡片没有传 null。 */
    private final Runnable rightClickAction;

    private final RoundedImageWidget cover;
    private final LabelWidget lblName;
    private final LabelWidget lblSubtitle;
    private final CFontRenderer subtitleFont = FontManager.pf12;

    private double cardWidth = 110.0;
    /** 上一次真正落到 bounds 的宽度：变了才碰 setBounds 与容器布局签名。 */
    private double width = -1.0;
    /** 所属网格容器：宽度变化时失效它的排版签名（layoutGrid 分发后由面板回填）。 */
    private ScrollPanel gridContainer;
    private double emphasizeAnim;
    private boolean coverLoaded = false;
    /** 封面加载重试节拍器：与歌单卡同一条 20 帧（约 1/3 秒）节拍。 */
    private int coverRetryFrame = 19;

    private final PanelItemEntrance entrance = new PanelItemEntrance();
    private double entranceEased = 1.0;
    private PanelItemEntrance.RevealQueue entranceQueue;

    public RadioCardWidget(DjRadio radio, Runnable clickAction, Runnable rightClickAction) {
        this.radio = radio;
        this.clickAction = clickAction;
        this.rightClickAction = rightClickAction;

        // 卡片构造即占位宽：VERTICAL_WITH_HORIZONTAL_FILL 的 ScrollPanel 在 addChild 的
        // 当帧就按子项尺寸排版。宽 0 的卡挤进同一行（全部叠在网格最左），下一帧宽到位后
        // 若没人失效布局签名，错位摆位就被冻结 —— 截图里"扇形叠牌"的根因。
        // 先按默认列宽占位（后面 layoutGrid 每帧校正），再在宽度真的变化时主动失效容器。
        this.width = 110.0;

        this.cover = new RoundedImageWidget(this::getCoverLocation, 0, 0, 1.0, 1.0);
        this.addChild(cover);
        cover.setClickable(false)
                .fadeIn()
                .setLinearFilter(true);

        this.lblName = new LabelWidget(radio::displayName, FontManager.pf14bold);
        this.addChild(lblName);
        lblName.setClickable(false);

        this.lblSubtitle = new LabelWidget(radio::subtitle, this.subtitleFont);
        this.addChild(lblSubtitle);
        lblSubtitle.setClickable(false);

        this.setShouldOverrideMouseCursor(rightClickAction != null || clickAction != null);
        this.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton == 0 && clickAction != null) {
                clickAction.run();
                return true;
            }
            if (mouseButton == 1 && rightClickAction != null) {
                rightClickAction.run();
                return true;
            }
            return mouseButton == 0;
        });

        this.setBeforeRenderCallback(() -> {
            this.updateCoverLoad();
            this.applyWidth();

            final double target = cover.isHovering() ? COVER_HOVER_EXPANSION : 0.0;
            this.emphasizeAnim = Interpolations.interpolate(this.emphasizeAnim, target, .2f);
            if (Math.abs(this.emphasizeAnim - target) < .01) this.emphasizeAnim = target;

            final double side = this.coverSide() + this.emphasizeAnim;
            final double inset = (this.getWidth() - side) * .5;
            if (cover.getWidth() != side || cover.getX() != inset) {
                cover.setBounds(inset, 0, side, side).setRadius(COVER_RADIUS);
            }

            this.updateEntrance();
        });

        lblName.setBeforeRenderCallback(() -> lblName
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                .setPosition(0, this.coverSide() + TITLE_TOP_GAP)
                .setMaxWidth(Math.max(1.0, this.getWidth()))
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH));
        lblSubtitle.setBeforeRenderCallback(() -> lblSubtitle
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                .setPosition(0, this.coverSide() + TITLE_TOP_GAP + 18)
                .setMaxWidth(Math.max(1.0, this.getWidth()))
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH));
    }

    /**
     * 把 layoutGrid 分到的列宽真正落到 bounds。宽度没变时只是一次 double 比较（滚动、
     * 悬停、进场期间零开销）；变了才 setBounds 并失效容器排版签名 —— ScrollPanel 的
     * VERTICAL_WITH_HORIZONTAL_FILL 按"子项当前宽"换行，宽变而签名不失效，错位摆位
     * 会被冻结（截图里的叠牌 bug）。
     */
    private void applyWidth() {
        double target = Math.max(1.0, this.cardWidth);
        if (this.width == target && this.getWidth() == target) return;
        this.width = target;
        this.setBounds(target, this.coverSide() + TEXT_BLOCK_HEIGHT);
        if (this.gridContainer != null) this.gridContainer.invalidateLayout();
    }

    /** 面板在建卡后回填网格容器引用，供宽度变化时失效布局签名。 */
    public void setGridContainer(ScrollPanel container) {
        this.gridContainer = container;
    }

    /** 按视口宽度算列数并把列宽分给所有卡片；面板每帧调一次。 */
    public static void layoutGrid(double viewportWidth, Collection<RadioCardWidget> cards) {
        if (viewportWidth <= 0.0 || cards == null || cards.isEmpty()) return;
        double perColumn = MIN_COLUMN_WIDTH + GRID_SPACING;
        int columns = (int) Math.floor((viewportWidth + GRID_SPACING) / perColumn);
        columns = Math.max(2, Math.min(columns, cards.size()));
        double cardWidth = (viewportWidth - GRID_SPACING * (columns - 1)) / columns;
        for (RadioCardWidget card : cards) card.cardWidth = cardWidth;
    }

    public DjRadio radio() {
        return this.radio;
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        // 全部视觉由子控件承担（封面 + 两行文字），进场推进在 beforeRender 里做；
        // 这里只剩基类抽象方法需要落实。
    }

    private double coverSide() {
        return Math.max(1.0, this.cardWidth);
    }

    private void updateCoverLoad() {
        if (coverLoaded) return;
        if (++coverRetryFrame < 20) return;
        coverRetryFrame = 0;

        TextureManager textureManager = TextureManager.getInstance();
        Location coverLoc = getCoverLocation();
        if (textureManager.getTexture(coverLoc) != null) {
            coverLoaded = true;
            return;
        }
        String url = radio.coverUrl();
        if (url.isEmpty()) return;
        // 网易云封面 URL 支持尺寸参数；DIFM / 广播的直链不带尺寸，原样下载。
        String requestUrl = radio.getKind() == DjRadio.Kind.DJ_RADIO ? url + "?param=256y256" : url;
        Textures.downloadTextureAndLoadAsync(requestUrl, coverLoc);
        coverLoaded = true;
    }

    private Location getCoverLocation() {
        String folder;
        switch (radio.getKind() == null ? DjRadio.Kind.DJ_RADIO : radio.getKind()) {
            case DIFM_CHANNEL: folder = "difm"; break;
            case BROADCAST_CHANNEL: folder = "broadcast"; break;
            default: folder = "dj"; break;
        }
        return Location.of("muonium/textures/radio/" + folder + "/" + radio.getId() + "/cover.png");
    }

    /** 挂上发号器：号在卡片第一次被渲染（刚滚进视口）时领，见 {@code PanelItemEntrance}。 */
    public void setEntranceAnimation(PanelItemEntrance.RevealQueue queue) {
        this.entranceQueue = queue;
        this.entranceEased = 0.0;
        this.setAlpha(0f);
        this.setTransformations(() -> PanelItemEntrance.applyTransform(this.entranceEased,
                this.getX() + this.getWidth() * .5, this.getY() + this.getHeight() * .5));
    }

    private void updateEntrance() {
        if (!this.entrance.isStarted()) {
            if (this.entranceQueue == null) return;
            this.entrance.begin(System.currentTimeMillis(), this.entranceQueue.nextDelayMillis());
        }
        if (this.entrance.isFinished()) return;

        this.entranceEased = this.entrance.tick();
        this.setAlpha((float) this.entranceEased);
        if (this.entrance.isFinished()) this.setTransformations(null);
    }

    private void finishEntranceAnimation() {
        this.entrance.finishImmediately();
        this.entranceEased = 1.0;
        this.setAlpha(1f);
        this.setTransformations(null);
    }

    @Override
    public void onViewportExit() {
        if (!this.entrance.isFinished()) this.finishEntranceAnimation();
    }

    @Override
    public void finishTransientAnimations() {
        this.finishEntranceAnimation();
        this.entranceQueue = null;
        super.finishTransientAnimations();
    }
}
