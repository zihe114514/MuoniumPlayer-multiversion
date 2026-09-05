package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.NeteaseMusicService;
import com.muoniumplayer.core.ncm.music.GdStudioMusicService;
import com.muoniumplayer.core.ncm.music.GdStudioSourceSettings;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.NeteaseUnblockSettings;
import com.muoniumplayer.core.rendering.MusicBrandIcons;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.screens.ncm.panels.HomePanel;
import com.muoniumplayer.party.ui.PartyIcons;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * 音乐来源浮层：官方源（网易云 / QQ / 酷狗）与 GD音乐台 聚合源的切换。
 *
 * <p>版式、动效与图标对齐都在 {@link NcmSheet}，这里只描述「有哪些来源、每张卡上写什么」。
 * 改写的起因是原来这个文件自己写了一遍遮罩、弹窗、标题、卡片和胶囊，纵向位置是一串写死的
 * 常量（68 / 88 / 154 / 220 / 292 / 312 / 380），而弹窗高度会被视口夹住 —— GUI 缩放一开大，
 * 底部的解灰开关和说明就被内容压在下面，用户永远点不到。</p>
 */
public final class MusicSourceOverlay extends NcmSheet {

    /** 页眉占掉的高度（图标盘 34 + 上下留白）。内容区从这里开始。 */
    private static final double HEADER_H = 64.0;
    /**
     * 品牌字标底盘的边长。
     *
     * <p>46 不是随手取的：品牌字形的墨迹约 36 逻辑像素（{@code musicBrand52} 的网易云字形占
     * 0.69 em、{@code qqMusicIcon68} 的 QQ 字形占 0.53 em，两者都落在 36 附近），46 的盘刚好
     * 留出一圈均匀的内边距。品牌字体只有 16 / 18 / 52 这几档，中间没有可用字号，
     * 所以是盘去迁就字形而不是反过来 —— 缩放字形会重采样发虚。</p>
     */
    private static final double DISC = 46.0;
    private static final double CARD_H = 60.0;
    /** 滚动行 = 卡高 + 行距。行距做进行高里，于是最后一行自带一段收尾留白。 */
    private static final double ROW_H = CARD_H + 7.0;
    /**
     * 分组小标题占的行高（第二组前面多留一段，让两组分开）。
     *
     * <p>这几个数和上面的卡高都是按“1080p + GUI 缩放 2”量出来的：那个档下内容区约 266
     * 逻辑像素，刚好能把三张官方卡 + 「在线聚合源」分组标题 + 聚合源卡的顶部露出来。露出一截
     * 是有意的：它是“下面还有东西”唯一可靠的提示，刚好切齐则会让人以为到底了。</p>
     */
    private static final double LABEL_ROW = 22.0;
    private static final double LABEL_ROW_2 = 26.0;
    /** 页脚：解灰开关一行 + 一行来源说明。这两项贴着弹窗底边，不参与滚动。 */
    private static final double FOOTER_H = 80.0;
    /** 卡片上文字块的左边缘 = 盘的左留白 + 盘宽 + 盘到文字的间距。 */
    private static final double TEXT_LEFT = 12.0 + DISC + 12.0;
    /** GD 平台列表的行高与卡高。 */
    private static final double PLATFORM_ROW = 56.0;
    private static final double PLATFORM_CARD = 48.0;

    private boolean choosingGdPlatform;
    private String statusText = "选一个平台后即可直接搜索与播放该平台的曲目";
    /**
     * 状态行的语气：0 说明、1 成功、2 失败。
     *
     * <p>存语气而不是直接存颜色：说明态的颜色要跟着主题走（原来写死 {@code 0xAEB5C4}，
     * 浅色主题下这行字几乎看不见），而图标也得按语气挑，拿颜色反推会在主题切换后错配。</p>
     */
    private int statusTone;

    @Override
    public void onInit() {
        showOverview();
    }

    public void handleEscape() {
        if (choosingGdPlatform) {
            showOverview();
        } else {
            dispose();
        }
    }

    // ════════════════════════════ 一级页：来源总览 ════════════════════════════

    private void showOverview() {
        choosingGdPlatform = false;
        beginPage();

        final Panel dialog = sheet(476,
                () -> HEADER_H + LABEL_ROW + ROW_H * 3 + LABEL_ROW_2 + ROW_H + FOOTER_H);
        header(dialog, PartyIcons.NOTE, "音乐来源",
                () -> "官方源与在线聚合源各自独立，随时切换",
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));

        final Panel body = body(dialog, () -> HEADER_H, () -> FOOTER_H);
        final ScrollPanel feed = feed(body);

        sectionLabel(strip(feed, LABEL_ROW), "官方音乐源", () -> 5.0,
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
        officialCard(strip(feed, ROW_H), 0, MusicPlatform.NETEASE);
        officialCard(strip(feed, ROW_H), 1, MusicPlatform.QQ);
        officialCard(strip(feed, ROW_H), 2, MusicPlatform.KUGOU);

        sectionLabel(strip(feed, LABEL_ROW_2), "在线聚合源", () -> 9.0, MusicPlatform.GD::getBrandColor);
        gdCard(strip(feed, ROW_H), 3);

        unblockRow(dialog);
        tip(dialog, () -> "GD音乐台来源由 music-api.gdstudio.xyz 提供，请遵循上游服务的使用要求",
                () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                () -> dialog.getHeight() - 30);
    }

    /** 一张官方来源卡。点一下即切换并关闭浮层 —— 这是终点操作，所以没有 chevron。 */
    private void officialCard(final Panel row, final int slot, final MusicPlatform platform) {
        final BooleanSupplier picked = () -> NeteaseMusicService.getCurrentPlatform() == platform;
        // 底色要用卡片自己的悬停进度推，而 card(...) 在构造时就要拿到颜色供应器。
        // 供应器只在渲染时求值，所以先留一个格子、拿到卡片之后回填。
        final Card[] self = new Card[1];
        final Card card = card(row, slot, () -> 0.0, () -> CARD_H, () -> picked.getAsBoolean()
                ? platform.getBrandColor()
                : mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), self[0].hover));
        self[0] = card;
        card.setShouldOverrideMouseCursor(true);
        card.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            boolean changed = NeteaseMusicService.getCurrentPlatform() != platform;
            // 即便点的就是当前来源也要走一遍 setter：它顺带清掉残留的 GD 平台选择。
            NeteaseMusicService.setCurrentPlatform(platform);
            if (changed) resetToHomeForCurrentSource();
            dispose();
            return true;
        });

        selectionSheen(card, picked);
        brandDisc(card, platform, picked);

        final IntSupplier ink = () -> picked.getAsBoolean()
                ? 0xFFFFFF : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT);
        cardText(card, platform::getDisplayName, () -> accountLine(platform), ink,
                () -> picked.getAsBoolean()
                        // 选中卡是品牌色底，副标题不能直接用次要文字色（那是给深/浅底色调的），
                        // 从品牌色朝白推 .78 得到的才是"同一块底上的第二层文字"。
                        ? mix(platform.getBrandColor(), 0xFFFFFF, .78)
                        : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                () -> picked.getAsBoolean() ? 46.0 : 16.0);

        checkBadge(card, picked, platform::getBrandColor, () -> card.getWidth() - 23);
    }

    /** GD 聚合源卡。它通往下一页，所以右端常驻一枚 chevron。 */
    private void gdCard(final Panel row, final int slot) {
        final BooleanSupplier picked = this::isGdActive;
        final Card[] self = new Card[1];
        final Card card = card(row, slot, () -> 0.0, () -> CARD_H, () -> picked.getAsBoolean()
                ? MusicPlatform.GD.getBrandColor()
                : mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), self[0].hover));
        self[0] = card;
        card.setShouldOverrideMouseCursor(true);
        card.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            showGdPlatforms();
            return true;
        });

        selectionSheen(card, picked);

        final IntSupplier ink = () -> picked.getAsBoolean()
                ? 0xFFFFFF : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT);
        final RoundedRectWidget disc = new RoundedRectWidget();
        card.addChild(disc);
        disc.setClickable(false);
        disc.setRadius(14);
        disc.setBeforeRenderCallback(() -> {
            disc.setBounds(DISC, DISC);
            disc.setPosition(12, card.getHeight() * .5 - DISC * .5);
            disc.setColor(picked.getAsBoolean()
                    ? mix(MusicPlatform.GD.getBrandColor(), 0xFFFFFF, .20)
                    : mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                    MusicPlatform.GD.getBrandColor(), .18));
        });
        // 聚合源没有品牌字标，用"探索"这一枚：它比品牌盘小一圈，一眼就能和上面三张官方卡分开。
        icon(disc, PartyIcons.EXPLORE, ICON_XL, () -> DISC * .5, () -> DISC * .5,
                () -> picked.getAsBoolean() ? 0xFFFFFF : MusicPlatform.GD.getBrandColor());

        cardText(card, () -> "GD音乐台 · 在线聚合", this::buildGdSummary, ink,
                () -> picked.getAsBoolean()
                        ? mix(MusicPlatform.GD.getBrandColor(), 0xFFFFFF, .78)
                        : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                () -> picked.getAsBoolean() ? 74.0 : 40.0);

        icon(card, PartyIcons.CHEVRON_RIGHT, ICON_M, () -> card.getWidth() - 20,
                () -> card.getHeight() * .5, () -> picked.getAsBoolean() ? 0xFFFFFF
                        : mix(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT), self[0].hover));
        checkBadge(card, picked, MusicPlatform.GD::getBrandColor, () -> card.getWidth() - 46);
    }

    /**
     * 「灰色歌曲解灰」一行。
     *
     * <p>留在音乐来源这一层而不是挪进 HUD 配置：它决定的就是「官方源没有流的时候要不要换一家」，
     * 和这个面板讨论的是同一件事。钉在页脚是因为它是设置而不是列表项，滚动内容不该把它带走。</p>
     */
    private void unblockRow(final Panel dialog) {
        final RoundedRectWidget surface = new RoundedRectWidget();
        dialog.addChild(surface);
        surface.setClickable(false);
        surface.setRadius(11);
        surface.setBeforeRenderCallback(() -> {
            surface.setBounds(Math.max(1, dialog.getWidth() - (PAD + CARD_INSET) * 2), 40);
            surface.setPosition(PAD + CARD_INSET, dialog.getHeight() - FOOTER_H + 4);
            surface.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });

        icon(surface, PartyIcons.SWEEP, ICON_M, () -> 20.0, () -> 20.0,
                () -> NeteaseUnblockSettings.isEnabled()
                        ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                        : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));

        final LabelWidget title = label(surface, "灰色歌曲解灰", FontManager.pf12bold);
        title.setBeforeRenderCallback(() -> {
            title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setPosition(38, inkTop(title, 13.0));
        });

        final LabelWidget hint = label(surface, "官方源没有可播放流时，改到第三方源按曲名匹配", FontManager.pf12);
        hint.setBeforeRenderCallback(() -> {
            hint.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            // 96 = 开关宽 34 + 开关到右边缘 12 + 文字到开关 12 + 一点余量。
            hint.setMaxWidth(Math.max(1, surface.getWidth() - 38 - 96));
            hint.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            hint.setPosition(38, inkTop(hint, 28.0));
        });

        toggle(surface, NeteaseUnblockSettings::isEnabled,
                () -> NeteaseUnblockSettings.setEnabled(!NeteaseUnblockSettings.isEnabled()),
                () -> surface.getWidth() - 12, () -> 20.0,
                () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
    }

    // ════════════════════════════ 二级页：GD 平台选择 ════════════════════════════

    private void showGdPlatforms() {
        choosingGdPlatform = true;
        beginPage();

        final List<GdStudioMusicService.Platform> platforms = GdStudioMusicService.getPlatforms();
        final Panel dialog = sheet(516,
                () -> 88 + 6 + Math.min(platforms.size() * PLATFORM_ROW, 280.0) + 44);
        subHeader(dialog, this::showOverview, () -> "GD音乐台 · 在线聚合",
                () -> "选一个平台作为独立内容源；再点当前平台即可关闭",
                MusicPlatform.GD::getBrandColor);
        tip(dialog, this::statusGlyph, () -> statusText, this::statusColor, () -> 60.0);

        final Panel body = body(dialog, () -> 88.0, () -> 44.0);
        final ScrollPanel feed = feed(body);
        // 开头让出几像素：卡片悬停生长是绕着自己中心做的，贴着上边会被硬裁切掉圆角。
        strip(feed, 6.0);
        for (int i = 0; i < platforms.size(); i++) {
            platformRow(strip(feed, PLATFORM_ROW), i, platforms.get(i));
        }

        tip(dialog, () -> PartyIcons.TIMER,
                () -> "额度剩余 " + GdStudioMusicService.remainingRequestBudget() + "/50 · 由 music-api.gdstudio.xyz 提供",
                () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                () -> dialog.getHeight() - 30);
    }

    private void platformRow(final Panel row, final int slot, final GdStudioMusicService.Platform platform) {
        final BooleanSupplier picked = () -> platform.key.equals(GdStudioSourceSettings.getPlatform());
        final Card[] self = new Card[1];
        final Card card = card(row, slot, () -> 0.0, () -> PLATFORM_CARD, () -> picked.getAsBoolean()
                ? mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                MusicPlatform.GD.getBrandColor(), .26)
                : mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), self[0].hover));
        self[0] = card;
        card.setShouldOverrideMouseCursor(true);
        card.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            togglePlatform(platform);
            return true;
        });
        stroke(card, 12.0, MusicPlatform.GD::getBrandColor, () -> picked.getAsBoolean() ? .55 : 0.0);

        // 左侧状态盘：选中 / 冷却 / 可用各有自己的图标，不必去读右边那行小字才知道能不能用。
        final RoundedRectWidget dot = new RoundedRectWidget();
        card.addChild(dot);
        dot.setClickable(false);
        dot.setRadius(9);
        dot.setBeforeRenderCallback(() -> {
            dot.setBounds(26, 26);
            dot.setPosition(12, card.getHeight() * .5 - 13);
            dot.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                    rowTone(platform, picked), .30));
        });
        icon(dot, () -> rowGlyph(platform, picked), ICON_M, () -> 13.0, () -> 13.0,
                () -> rowTone(platform, picked));

        final LabelWidget name = label(card, platform.displayName, FontManager.pf12bold);
        name.setBeforeRenderCallback(() -> {
            name.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            name.setMaxWidth(Math.max(1, card.getWidth() - 48 - 96));
            name.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            name.setPosition(48, inkTop(name, card.getHeight() * .5 - 8));
        });

        // 静态说明写的是上游支持什么；运行时说明写的是熔断冷却 —— 刚失败过的源要能就地解释自己。
        final LabelWidget desc = label(card, () -> {
            String runtime = GdStudioMusicService.statusLabel(platform.key);
            return platform.key + " · " + (runtime.isEmpty() ? platform.status.description : runtime);
        }, FontManager.pf12);
        desc.setBeforeRenderCallback(() -> {
            desc.setColor(GdStudioMusicService.statusLabel(platform.key).isEmpty()
                    ? platform.status.color : WARN_COLOR);
            desc.setMaxWidth(Math.max(1, card.getWidth() - 48 - 96));
            desc.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            desc.setPosition(48, inkTop(desc, card.getHeight() * .5 + 8));
        });

        pill(card, () -> picked.getAsBoolean() ? PartyIcons.CHECK : PartyIcons.PLAY,
                () -> picked.getAsBoolean() ? "使用中" : "选用",
                () -> picked.getAsBoolean() ? OK_COLOR
                        : mix(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        NCMScreen.getColor(NCMScreen.ColorType.ACCENT), self[0].hover),
                () -> card.getWidth() - 12, () -> card.getHeight() * .5);
    }

    private void togglePlatform(final GdStudioMusicService.Platform platform) {
        boolean enable = !platform.key.equals(GdStudioSourceSettings.getPlatform());
        if (!GdStudioSourceSettings.setPlatform(enable ? platform.key : "")) return;

        if (enable) {
            NeteaseMusicService.setCurrentPlatform(MusicPlatform.GD);
            statusText = "已切换到「" + platform.displayName + "」内容源";
            statusTone = 1;
        } else {
            if (NeteaseMusicService.getCurrentPlatform() == MusicPlatform.GD) {
                NeteaseMusicService.setCurrentPlatform(MusicPlatform.NETEASE);
            }
            statusText = "已关闭 GD音乐台 内容源";
            statusTone = 0;
        }
        resetToHomeForCurrentSource();
        showGdPlatforms();
    }

    // ════════════════════════════ 卡片零件 ════════════════════════════

    /** 选中卡上的一层横向白色渐隐 + 一圈描边。品牌色铺整块会是一片死板的纯色。 */
    private void selectionSheen(final Card card, final BooleanSupplier picked) {
        final Wash sheen = wash(card, true, 12.0);
        sheen.tint(0xFFFFFF, 0xFFFFFF).alphas(.14, 0.0);
        sheen.setBeforeRenderCallback(() -> {
            sheen.setBounds(Math.max(1, card.getWidth()), Math.max(1, card.getHeight()));
            sheen.setPosition(0, 0);
            sheen.setAlpha(picked.getAsBoolean() ? 1f : 0f);
        });
        stroke(card, 12.0, () -> 0xFFFFFF, () -> picked.getAsBoolean() ? .26 : 0.0);
    }

    /** 品牌字标底盘。未选中时是「卡片底色朝品牌色推一小步」，于是浅色主题上也看得见。 */
    private void brandDisc(final Card card, final MusicPlatform platform, final BooleanSupplier picked) {
        final boolean kugou = platform == MusicPlatform.KUGOU;
        final boolean qq = platform == MusicPlatform.QQ;
        // 品牌字形必须配对到自己那份字体：music-brand-icons 里没有 U+E888（QQ 音乐），
        // 拿错字体会落到兜底字体上、画出一片空白。酷狗两份字体里都没有，用正文字体画一枚汉字字标。
        final String mark = kugou ? MusicBrandIcons.KUGOU_MONOGRAM
                : qq ? MusicBrandIcons.QQ_MUSIC : MusicBrandIcons.NETEASE_CLOUD_MUSIC;
        final CFontRenderer font = kugou ? FontManager.pf34bold
                : qq ? FontManager.qqMusicIcon68 : FontManager.musicBrand52;

        final RoundedRectWidget disc = new RoundedRectWidget();
        card.addChild(disc);
        disc.setClickable(false);
        disc.setRadius(14);
        disc.setBeforeRenderCallback(() -> {
            disc.setBounds(DISC, DISC);
            disc.setPosition(12, card.getHeight() * .5 - DISC * .5);
            disc.setColor(picked.getAsBoolean()
                    ? mix(platform.getBrandColor(), 0xFFFFFF, .20)
                    : mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                    platform.getBrandColor(), .18));
        });
        glyph(disc, mark, font, () -> DISC * .5, () -> DISC * .5,
                () -> picked.getAsBoolean() ? 0xFFFFFF : platform.getBrandColor());
    }

    /**
     * 卡片上的「标题 + 副标题」两行。
     *
     * <p>两行绕着卡片中线各让 9 像素，而不是写死 y+13 / y+34：卡高一改，写死的那两个数就得
     * 重新调，而按中线算的这一对永远居中。{@code reserve} 是右端零件（勾标、chevron）占掉的宽度，
     * 副标题必须在它之前收住，否则长文案会压到底下去。</p>
     */
    private void cardText(final Card card, final java.util.function.Supplier<String> titleText,
                          final java.util.function.Supplier<String> noteText,
                          final IntSupplier titleInk, final IntSupplier noteInk,
                          final java.util.function.DoubleSupplier reserve) {
        final LabelWidget title = label(card, titleText, FontManager.pf14bold);
        title.setBeforeRenderCallback(() -> {
            title.setColor(titleInk.getAsInt());
            title.setMaxWidth(Math.max(1, card.getWidth() - TEXT_LEFT - reserve.getAsDouble()));
            title.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            title.setPosition(TEXT_LEFT, inkTop(title, card.getHeight() * .5 - 9));
        });

        final LabelWidget note = label(card, noteText, FontManager.pf12);
        note.setBeforeRenderCallback(() -> {
            note.setColor(noteInk.getAsInt());
            note.setMaxWidth(Math.max(1, card.getWidth() - TEXT_LEFT - reserve.getAsDouble()));
            note.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            note.setPosition(TEXT_LEFT, inkTop(note, card.getHeight() * .5 + 9));
        });
    }

    /**
     * 选中标记：一枚白底圆里放品牌色的勾。
     *
     * <p>不用 {@link #pill}：胶囊的底色是从页面底色推出来的，落在品牌色卡片上会变成一块灰扑扑的
     * 补丁。白底 + 品牌色勾在任何品牌色上都成立。整块套一层 {@link #veil} 控透明度，是因为
     * 胶囊/图标这类零件自己已经占了 {@code beforeRenderCallback}，调用方再挂一个会覆盖它的排版。</p>
     */
    private void checkBadge(final Card card, final BooleanSupplier picked, final IntSupplier tint,
                            final java.util.function.DoubleSupplier centerX) {
        final Panel layer = veil(card, () -> picked.getAsBoolean() ? 1.0 : 0.0);
        final RoundedRectWidget badge = new RoundedRectWidget();
        layer.addChild(badge);
        badge.setClickable(false);
        badge.setRadius(11);
        badge.setBeforeRenderCallback(() -> {
            badge.setBounds(22, 22);
            badge.setPosition(centerX.getAsDouble() - 11, layer.getHeight() * .5 - 11);
            badge.setColor(0xFFFFFF);
        });
        icon(badge, PartyIcons.CHECK, ICON_M, () -> 11.0, () -> 11.0, tint);
    }

    // ════════════════════════════ 文案与状态 ════════════════════════════

    /** 官方卡副标题：这个来源现在能不能用。比一句写死的能力描述有用得多。 */
    private String accountLine(final MusicPlatform platform) {
        if (NeteaseMusicService.isLoggedIn(platform)) {
            return "已登录 · " + NeteaseMusicService.getAccountName(platform);
        }
        return "未登录 · 可搜索与播放，会员曲目需登录";
    }

    private boolean isGdActive() {
        return NeteaseMusicService.getCurrentPlatform() == MusicPlatform.GD && GdStudioSourceSettings.isEnabled();
    }

    private String buildGdSummary() {
        String key = GdStudioSourceSettings.getPlatform();
        if (key.isEmpty()) return "未选用 · 选一个平台后作为独立内容源";
        String label = GdStudioMusicService.displayName(key) + "（" + key + "）";
        // 上游的额度是 5 分钟 50 次；把剩余额度摊开来写，节流就不会看着像一次随机失败。
        return NeteaseMusicService.getCurrentPlatform() == MusicPlatform.GD
                ? "当前平台：" + label + " · 额度剩余 " + GdStudioMusicService.remainingRequestBudget() + "/50"
                : "已选平台：" + label + " · 点击切回";
    }

    private int statusColor() {
        return statusTone == 1 ? OK_COLOR : statusTone == 2 ? BAD_COLOR
                : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT);
    }

    private String statusGlyph() {
        return statusTone == 1 ? PartyIcons.CHECK : statusTone == 2 ? PartyIcons.WARNING : PartyIcons.INFO;
    }

    private int rowTone(final GdStudioMusicService.Platform platform, final BooleanSupplier picked) {
        if (picked.getAsBoolean()) return MusicPlatform.GD.getBrandColor();
        return GdStudioMusicService.statusLabel(platform.key).isEmpty()
                ? NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT) : WARN_COLOR;
    }

    private String rowGlyph(final GdStudioMusicService.Platform platform, final BooleanSupplier picked) {
        if (picked.getAsBoolean()) return PartyIcons.CHECK;
        return GdStudioMusicService.statusLabel(platform.key).isEmpty()
                ? PartyIcons.RADIO : PartyIcons.HOURGLASS;
    }

    /**
     * 换来源等于换「当前面板能渲染什么」。
     *
     * <p>先回主页再重建导航栏，而不是把上一个来源的搜索 / 发现面板留在屏幕上。</p>
     */
    private void resetToHomeForCurrentSource() {
        NCMScreen screen = NCMScreen.getInstance();
        screen.setCurrentPanel(new HomePanel());
        screen.markDirty();
    }
}