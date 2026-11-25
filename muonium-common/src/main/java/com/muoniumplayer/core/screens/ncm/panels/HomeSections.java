package com.muoniumplayer.core.screens.ncm.panels;

import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.DailyRecommendService;
import com.muoniumplayer.core.ncm.music.ListeningStats;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.NeteaseMusicService;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.SPlayerIcons;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.texture.Textures;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedImageWidget;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.SPlayerButton;
import com.muoniumplayer.core.screens.ncm.SPlayerStyle;
import com.muoniumplayer.core.utils.Location;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * SPlayer 主页（{@code pages/Home.vue}）的各个板块。它们都是主页网格里<b>占满一整行</b>的子项：
 * 网格是横向填充的 {@link ScrollPanel}，宽度等于视口宽的子项自己占一行，后面的歌单卡片自动换行。
 * 所以这些板块和卡片一起滚动，与 SPlayer 整页滚动一致。
 *
 * <p>板块高度会随数据变化（每日推荐到货、反复聆听从空变满），变化时让网格的布局签名失效。</p>
 */
final class HomeSections {

    /** 板块之间的竖向留白（SPlayer {@code gap-6} 减去网格自己的行距）。 */
    static final double SECTION_GAP = SPlayerStyle.px(8);

    private HomeSections() {
    }

    /** 所有板块的共同骨架：宽度跟视口走，高度变了就让网格重排。 */
    abstract static class Section extends Panel {
        private final ScrollPanel container;
        private double lastHeight = -1;

        Section(ScrollPanel container) {
            this.container = container;
            this.setBeforeRenderCallback(() -> {
                double width = Math.max(1.0, container.getWidth());
                double height = this.measure(width);
                if (Math.abs(height - this.lastHeight) > .01 || Math.abs(width - this.getWidth()) > .01) {
                    this.setBounds(width, height);
                    this.lastHeight = height;
                    container.invalidateLayout();
                }
            });
        }

        /** 这一帧的高度（含板块下方的留白）。 */
        abstract double measure(double width);

        /** 首帧前就给出尺寸，网格第一次排版就不错位。 */
        Section primeSize() {
            double width = Math.max(1.0, this.container.getWidth());
            this.setBounds(width, this.measure(width));
            this.lastHeight = this.getHeight();
            return this;
        }
    }

    // ------------------------------------------------------------------
    // 问候 + 收听统计
    // ------------------------------------------------------------------

    /**
     * 页头：左侧 {@code text-3xl font-bold} 的标题与 {@code text-sm text-on-surface-variant/70} 的副标题；
     * 默认主页右侧是三个数（本周时长、新增收藏、连续收听），各自 {@code text-2xl font-bold} 数值 +
     * {@code text-sm} 单位 + {@code text-xs /50} 说明，右对齐。
     */
    static final class Greeting extends Section {
        private final Supplier<String> title;
        private final Supplier<String> subtitle;
        private final boolean stats;

        Greeting(ScrollPanel container, Supplier<String> title, Supplier<String> subtitle, boolean stats) {
            super(container);
            this.title = title;
            this.subtitle = subtitle;
            this.stats = stats;
            this.setClickable(false);
        }

        @Override
        double measure(double width) {
            double height = SPlayerStyle.px(24) + SPlayerStyle.px(36);
            if (this.subtitle != null) height += SPlayerStyle.px(8) + SPlayerStyle.px(20);
            return height + SECTION_GAP;
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            float alpha = this.getAlpha();
            double x = this.getX();
            double top = this.getY() + SPlayerStyle.px(24);
            double statsWidth = 0;
            if (this.stats) statsWidth = this.renderStats(x + this.getWidth(), top, alpha);
            CFontRenderer titleFont = SPlayerStyle.text3xl();
            SPlayerStyle.text(titleFont, this.title.get(), x, top + SPlayerStyle.px(18),
                    this.getWidth() - statsWidth - SPlayerStyle.px(24), SPlayerStyle.a(SPlayerStyle.onSurface(), 1.0, alpha));
            if (this.subtitle != null) {
                SPlayerStyle.text(SPlayerStyle.textSm(false), this.subtitle.get(), x,
                        top + SPlayerStyle.px(36) + SPlayerStyle.px(8) + SPlayerStyle.px(10),
                        this.getWidth() - statsWidth - SPlayerStyle.px(24),
                        SPlayerStyle.a(SPlayerStyle.onSurfaceVariant(), .70, alpha));
            }
        }

        /** 从右往左画三个统计，返回占掉的宽度。 */
        private double renderStats(double right, double top, float alpha) {
            String[][] items = {
                    {formatHours(ListeningStats.weekListenHours()), "h", "本周时长"},
                    {String.valueOf(ListeningStats.weekNewLikes()), "首", "新增收藏"},
                    {String.valueOf(ListeningStats.streakDays()), "天", "连续收听"},
            };
            CFontRenderer valueFont = SPlayerStyle.text2xl();
            CFontRenderer unitFont = SPlayerStyle.textSm(false);
            CFontRenderer labelFont = SPlayerStyle.textXs(false);
            double gap = SPlayerStyle.px(24);
            double cursor = right;
            for (int index = items.length - 1; index >= 0; index--) {
                String[] item = items[index];
                double valueWidth = valueFont.getStringWidthD(item[0]);
                double unitWidth = unitFont.getStringWidthD(item[1]);
                double labelWidth = labelFont.getStringWidthD(item[2]);
                double blockWidth = Math.max(valueWidth + SPlayerStyle.px(2) + unitWidth, labelWidth);
                double blockRight = cursor;
                double valueCenter = top + SPlayerStyle.px(16);
                SPlayerStyle.text(unitFont, item[1], blockRight - unitWidth, valueCenter + SPlayerStyle.px(3), 0,
                        SPlayerStyle.a(SPlayerStyle.onSurfaceVariant(), 1.0, alpha));
                SPlayerStyle.text(valueFont, item[0], blockRight - unitWidth - SPlayerStyle.px(2) - valueWidth,
                        valueCenter, 0, SPlayerStyle.a(SPlayerStyle.onSurface(), 1.0, alpha));
                SPlayerStyle.text(labelFont, item[2], blockRight - labelWidth, top + SPlayerStyle.px(42), 0,
                        SPlayerStyle.a(SPlayerStyle.onSurfaceVariant(), .50, alpha));
                cursor -= blockWidth + gap;
            }
            return right - cursor - gap;
        }

        private static String formatHours(double hours) {
            return String.format(Locale.ROOT, "%.1f", hours);
        }
    }

    /** SPlayer 的问候语：按当前时段 + 账号昵称。 */
    static String greeting(MusicPlatform platform) {
        int hour = LocalTime.now().getHour();
        String period = hour < 5 ? "夜深了" : hour < 11 ? "早上好" : hour < 13 ? "中午好" : hour < 18 ? "下午好" : "晚上好";
        String name = NeteaseMusicService.isLoggedIn(platform) ? NeteaseMusicService.getAccountName(platform) : null;
        return name == null || name.trim().isEmpty() ? period : period + "，" + name;
    }

    // ------------------------------------------------------------------
    // 每日推荐大卡
    // ------------------------------------------------------------------

    /**
     * 每日推荐（SPlayer 主页 Hero）：{@code SCard radius=xl p-4}，左侧 {@code size-32 rounded-lg} 封面，
     * 中间标签/标题/副标题/两颗按钮，宽面板时右侧一列 {@code w-80 border-l} 的前四首预览。
     * 没有数据（音源不支持、没登录、还没取到）时整块收成 0 高，不占位。
     */
    static final class DailyHero extends Section {
        private final MusicPlatform platform;
        private final SPlayerButton play;
        private final SPlayerButton queue;
        private final RoundedImageWidget cover;
        private boolean requested;

        DailyHero(ScrollPanel container, MusicPlatform platform) {
            super(container);
            this.platform = platform;
            this.cover = new RoundedImageWidget(() -> {
                DailyRecommendService.Mix mix = DailyRecommendService.peek(this.platform);
                Music first = mix == null ? null : mix.coverTrack();
                return first == null ? null : first.getCoverLocation();
            }, 0, 0, 0, 0);
            this.cover.fadeIn();
            this.cover.setLinearFilter(true);
            this.cover.setClickable(false);
            this.addChild(this.cover);

            this.play = SPlayerButton.pill(SPlayerIcons.PLAY, "立即播放", SPlayerButton.Variant.PRIMARY)
                    .iconSize(SPlayerStyle.px(14)).font(SPlayerStyle.textSm(false));
            this.play.setOnClickCallback((x, y, button) -> {
                if (button != 0) return false;
                DailyRecommendService.Mix mix = DailyRecommendService.peek(this.platform);
                if (mix != null && !mix.getTracks().isEmpty()) CloudMusic.play(new ArrayList<>(mix.getTracks()), 0);
                return true;
            });
            this.queue = SPlayerButton.pill(SPlayerIcons.PLUS, "添加到队列", SPlayerButton.Variant.SECONDARY)
                    .iconSize(SPlayerStyle.px(14)).font(SPlayerStyle.textSm(false));
            this.queue.setOnClickCallback((x, y, button) -> {
                if (button != 0) return false;
                DailyRecommendService.Mix mix = DailyRecommendService.peek(this.platform);
                if (mix == null) return true;
                for (Music track : mix.getTracks()) CloudMusic.playNext(track);
                return true;
            });
            this.addChild(this.play, this.queue);
        }

        private DailyRecommendService.Mix mix() {
            if (!this.requested) {
                this.requested = true;
                DailyRecommendService.ensureLoaded(this.platform, () -> NCMScreen.getInstance().invalidateTransitionContent());
            }
            return DailyRecommendService.peek(this.platform);
        }

        private double cardHeight() {
            return SPlayerStyle.px(16) * 2 + SPlayerStyle.px(128);
        }

        @Override
        double measure(double width) {
            return this.mix() == null ? 0.0 : this.cardHeight() + SECTION_GAP;
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            DailyRecommendService.Mix mix = this.mix();
            boolean visible = mix != null;
            this.cover.setHidden(!visible);
            this.play.setHidden(!visible);
            this.queue.setHidden(!visible);
            if (!visible) return;
            float alpha = this.getAlpha();
            double x = this.getX();
            double y = this.getY();
            double width = this.getWidth();
            double height = this.cardHeight();
            SPlayerStyle.card(x, y, width, height, SPlayerStyle.RADIUS_XL, 0f, alpha);

            double pad = SPlayerStyle.px(16);
            double coverSize = SPlayerStyle.px(128);
            Music first = mix.coverTrack();
            requestCover(first, true);
            if (TextureManager.getInstance().getTexture(first.getCoverLocation()) == null) {
                SPlayerStyle.fill(x + pad, y + pad, coverSize, coverSize, SPlayerStyle.RADIUS_LG,
                        SPlayerStyle.a(SPlayerStyle.onSurface(), .08, alpha));
            }
            this.cover.setBounds(pad, pad, coverSize, coverSize);
            this.cover.setRadius(SPlayerStyle.RADIUS_LG);

            boolean preview = width >= SPlayerStyle.px(900);
            double previewWidth = preview ? SPlayerStyle.px(320) : 0;
            double infoX = x + pad + coverSize + SPlayerStyle.px(16);
            double infoWidth = width - (infoX - x) - pad - (preview ? previewWidth + SPlayerStyle.px(16) : 0);
            double centerY = y + height * .5;

            // STag（small round default）：「每日推荐」。
            CFontRenderer tagFont = SPlayerStyle.textXs(false);
            String tag = "每日推荐";
            double tagHeight = SPlayerStyle.px(20);
            double tagWidth = tagFont.getStringWidthD(tag) + SPlayerStyle.px(14);
            double tagY = centerY - SPlayerStyle.px(48);
            SPlayerStyle.fill(infoX, tagY, tagWidth, tagHeight, tagHeight * .5,
                    SPlayerStyle.a(SPlayerStyle.onSurface(), .10, alpha));
            SPlayerStyle.text(tagFont, tag, infoX + SPlayerStyle.px(7), tagY + tagHeight * .5, 0,
                    SPlayerStyle.a(SPlayerStyle.onSurface(), .85, alpha));

            SPlayerStyle.text(SPlayerStyle.textXl(), mix.title(), infoX, centerY - SPlayerStyle.px(12), infoWidth,
                    SPlayerStyle.a(SPlayerStyle.onSurface(), 1.0, alpha));
            SPlayerStyle.text(SPlayerStyle.textSm(false), mix.subtitle(), infoX, centerY + SPlayerStyle.px(14), infoWidth,
                    SPlayerStyle.a(SPlayerStyle.onSurfaceVariant(), .70, alpha));

            double buttonHeight = SPlayerStyle.px(30);
            double buttonY = centerY + SPlayerStyle.px(30) - y;
            double playWidth = this.play.preferredWidth(buttonHeight);
            this.play.setBounds(infoX - x, buttonY, playWidth, buttonHeight);
            this.queue.setBounds(infoX - x + playWidth + SPlayerStyle.px(8), buttonY,
                    this.queue.preferredWidth(buttonHeight), buttonHeight);

            if (preview) {
                double listX = x + width - pad - previewWidth;
                SPlayerStyle.line(listX, y + pad, listX, y + height - pad, SPlayerStyle.px(1),
                        SPlayerStyle.a(SPlayerStyle.onSurface(), .08, alpha));
                List<Music> tracks = mix.getTracks();
                int rows = Math.min(4, tracks.size());
                double rowHeight = (height - pad * 2) / 4.0;
                CFontRenderer noFont = SPlayerStyle.textXs(false);
                CFontRenderer nameFont = SPlayerStyle.textSm(false);
                for (int index = 0; index < rows; index++) {
                    Music track = tracks.get(index);
                    double rowCenter = y + pad + rowHeight * (index + .5);
                    double cursor = listX + SPlayerStyle.px(16);
                    SPlayerStyle.text(noFont, String.format(Locale.ROOT, "%02d", index + 1), cursor, rowCenter, 0,
                            SPlayerStyle.a(SPlayerStyle.onSurfaceVariant(), .35, alpha));
                    cursor += SPlayerStyle.px(20) + SPlayerStyle.px(10);
                    String artist = track.getArtistsName();
                    double artistWidth = Math.min(SPlayerStyle.px(96), noFont.getStringWidthD(artist == null ? "" : artist));
                    double right = listX + previewWidth;
                    SPlayerStyle.text(noFont, artist, right - artistWidth, rowCenter, artistWidth + .5,
                            SPlayerStyle.a(SPlayerStyle.onSurfaceVariant(), .45, alpha));
                    SPlayerStyle.text(nameFont, track.getName(), cursor, rowCenter,
                            right - artistWidth - SPlayerStyle.px(10) - cursor,
                            SPlayerStyle.a(SPlayerStyle.onSurface(), 1.0, alpha));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 快捷入口
    // ------------------------------------------------------------------

    /** 一个快捷入口。 */
    private static final class QuickAction {
        final String icon;
        final String title;
        final String desc;
        final Runnable run;

        QuickAction(String icon, String title, String desc, Runnable run) {
            this.icon = icon;
            this.title = title;
            this.desc = desc;
            this.run = run;
        }
    }

    /**
     * 快捷入口（{@code grid-cols-4 gap-3}）：每张 {@code SCard radius=xl hoverable}，
     * 左边 {@code size-9 rounded-lg bg-primary/10 text-primary} 的图标块，右边标题 + 一句说明。
     *
     * <p>入口按音源能力取舍：每日推荐只在有每日推荐接口的音源出现，私人 FM / 最近播放同理；
     * 「试试手气」始终在。SPlayer 的「心动模式」本播放器没有对应功能，这一格换成「最近播放」。</p>
     */
    static final class QuickActions extends Section {
        private final List<QuickAction> actions = new ArrayList<>();
        private final float[] hover;
        private int hovered = -1;

        QuickActions(ScrollPanel container, MusicPlatform platform) {
            super(container);
            this.setShouldOverrideMouseCursor(true);
            this.actions.add(new QuickAction(SPlayerIcons.DICES, "试试手气", "随机播放一首", () -> playLucky(platform)));
            if (DailyRecommendService.supports(platform)) {
                this.actions.add(new QuickAction(SPlayerIcons.CALENDAR_DAYS, "每日推荐", "每天为你精选",
                        () -> openDaily(platform)));
            }
            Supplier<com.muoniumplayer.core.screens.ncm.NCMPanel> recent = recentLoader(platform);
            if (recent != null) {
                this.actions.add(new QuickAction(SPlayerIcons.HISTORY, "最近播放", "接着上次的旋律",
                        () -> NCMScreen.getInstance().navigateAsync("最近播放", recent)));
            }
            if (platform == MusicPlatform.NETEASE || platform == MusicPlatform.QQ
                    || platform == MusicPlatform.KUGOU || platform == MusicPlatform.QISHUI) {
                this.actions.add(new QuickAction(SPlayerIcons.RADIO, "私人 FM", "为你不停推荐",
                        () -> NCMScreen.getInstance().setCurrentPanel(new PersonalFmPanel())));
            }
            this.hover = new float[this.actions.size()];
            this.setOnClickCallback((x, y, button) -> {
                if (button != 0 || this.hovered < 0) return false;
                this.actions.get(this.hovered).run.run();
                return true;
            });
        }

        private double cardHeight() {
            return SPlayerStyle.px(12) * 2 + SPlayerStyle.px(36);
        }

        @Override
        double measure(double width) {
            return this.cardHeight() + SECTION_GAP;
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            float alpha = this.getAlpha();
            int count = this.actions.size();
            double gap = SPlayerStyle.px(12);
            // SPlayer 固定四列；入口不足四个时仍按四列宽排，不把卡片拉得过长。
            double columns = Math.max(4, count);
            double cardWidth = (this.getWidth() - gap * (columns - 1)) / columns;
            double height = this.cardHeight();
            this.hovered = -1;
            for (int index = 0; index < count; index++) {
                QuickAction action = this.actions.get(index);
                double x = this.getX() + index * (cardWidth + gap);
                double y = this.getY();
                boolean over = this.isHovering() && mouseX >= x && mouseX <= x + cardWidth
                        && mouseY >= y && mouseY <= y + height;
                if (over) this.hovered = index;
                this.hover[index] = SPlayerStyle.approach(this.hover[index], over ? 1f : 0f);
                SPlayerStyle.card(x, y, cardWidth, height, SPlayerStyle.RADIUS_XL, this.hover[index], alpha);
                double box = SPlayerStyle.px(36);
                double boxX = x + SPlayerStyle.px(16);
                double boxY = y + (height - box) * .5;
                SPlayerStyle.fill(boxX, boxY, box, box, SPlayerStyle.RADIUS_LG, SPlayerStyle.a(SPlayerStyle.primary(), .10, alpha));
                SPlayerIcons.draw(action.icon, boxX + box * .5, boxY + box * .5, SPlayerStyle.px(20),
                        SPlayerStyle.a(SPlayerStyle.primary(), 1.0, alpha));
                double textX = boxX + box + SPlayerStyle.px(12);
                double textWidth = x + cardWidth - SPlayerStyle.px(12) - textX;
                SPlayerStyle.text(SPlayerStyle.textSm(true), action.title, textX, y + height * .5 - SPlayerStyle.px(9),
                        textWidth, SPlayerStyle.a(SPlayerStyle.onSurface(), 1.0, alpha));
                SPlayerStyle.text(SPlayerStyle.textXs(false), action.desc, textX, y + height * .5 + SPlayerStyle.px(10),
                        textWidth, SPlayerStyle.a(SPlayerStyle.onSurfaceVariant(), .50, alpha));
            }
        }

        /** 试试手气：从「反复聆听」与今天的每日推荐里随机挑一首。两边都空时给一句提示，不静默。 */
        private static void playLucky(MusicPlatform platform) {
            List<Music> pool = new ArrayList<>();
            for (ListeningStats.TrackRecord record : ListeningStats.topTracks(60)) pool.add(record.getMusic());
            DailyRecommendService.Mix mix = DailyRecommendService.peek(platform);
            if (mix != null) pool.addAll(mix.getTracks());
            if (pool.isEmpty()) {
                DownloadDynamicIsland.showModelOperationResult("试试手气", "还没有可以随机的歌曲，先去听几首吧", false);
                return;
            }
            Music pick = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
            List<Music> single = new ArrayList<>();
            single.add(pick);
            CloudMusic.play(single, 0);
        }

        /** 每日推荐：有现成的就直接开歌曲列表页，没有就先取再开（酷狗走它自己的每日推荐页）。 */
        private static void openDaily(MusicPlatform platform) {
            if (platform == MusicPlatform.KUGOU) {
                NCMScreen.getInstance().navigateAsync("每日推荐", NeteaseDiscoveryPanel::loadKuGouDailyPanel);
                return;
            }
            DailyRecommendService.Mix mix = DailyRecommendService.peek(platform);
            if (mix != null) {
                NeteaseDiscoveryPanel.openSongs("每日推荐", new ArrayList<>(mix.getTracks()));
                return;
            }
            if (!NeteaseMusicService.isLoggedIn(platform)) {
                DownloadDynamicIsland.showModelOperationResult("每日推荐", "登录后才有每日推荐", false);
                NCMScreen.getInstance().openAccountManager();
                return;
            }
            DailyRecommendService.ensureLoaded(platform, () -> {
                DailyRecommendService.Mix ready = DailyRecommendService.peek(platform);
                if (ready != null) NeteaseDiscoveryPanel.openSongs("每日推荐", new ArrayList<>(ready.getTracks()));
            });
        }

        private static Supplier<com.muoniumplayer.core.screens.ncm.NCMPanel> recentLoader(MusicPlatform platform) {
            if (platform == MusicPlatform.NETEASE) return NeteaseDiscoveryPanel::loadRecentSongsPanel;
            if (platform == MusicPlatform.KUGOU) return NeteaseDiscoveryPanel::loadKuGouRecentSongsPanel;
            if (platform == MusicPlatform.QISHUI) return NeteaseDiscoveryPanel::loadQishuiRecentSongsPanel;
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 反复聆听
    // ------------------------------------------------------------------

    /**
     * 反复聆听（{@code grid-cols-3 gap-3}）：播放次数最多的六首。每张 {@code SCard size=small radius=xl}：
     * 序号、{@code size-12 rounded-lg} 封面（悬停压暗出播放键）、歌名/歌手、右侧「播放 N 次」。
     * 一首都没有时是一块虚线框空状态。点哪张就从那一首开始播这六首。
     */
    static final class RepeatListening extends Section {
        private static final int LIMIT = 6;
        private final RoundedImageWidget[] covers = new RoundedImageWidget[LIMIT];
        private final float[] hover = new float[LIMIT];
        private List<ListeningStats.TrackRecord> records = new ArrayList<>();
        private int hovered = -1;
        private long refreshedAt;

        RepeatListening(ScrollPanel container) {
            super(container);
            this.setShouldOverrideMouseCursor(true);
            for (int index = 0; index < LIMIT; index++) {
                final int slot = index;
                RoundedImageWidget cover = new RoundedImageWidget(() -> {
                    List<ListeningStats.TrackRecord> current = this.records;
                    return slot < current.size() ? current.get(slot).getMusic().getSmallCoverLocation() : null;
                }, 0, 0, 0, 0);
                cover.fadeIn();
                cover.setLinearFilter(true);
                cover.setClickable(false);
                this.covers[index] = cover;
                this.addChild(cover);
            }
            this.setOnClickCallback((x, y, button) -> {
                if (button != 0 || this.hovered < 0 || this.hovered >= this.records.size()) return false;
                List<Music> tracks = new ArrayList<>();
                for (ListeningStats.TrackRecord record : this.records) tracks.add(record.getMusic());
                CloudMusic.play(tracks, this.hovered);
                return true;
            });
            this.refresh();
        }

        /** 每隔几秒重读一次统计：听完一首回到主页，计数会自己涨上去。 */
        private void refresh() {
            this.refreshedAt = System.currentTimeMillis();
            this.records = ListeningStats.topTracks(LIMIT);
        }

        private double headerHeight() {
            return SPlayerStyle.px(28) + SPlayerStyle.px(2) + SPlayerStyle.px(16) + SPlayerStyle.px(12);
        }

        private double cardHeight() {
            return SPlayerStyle.px(8) * 2 + SPlayerStyle.px(48);
        }

        @Override
        double measure(double width) {
            if (System.currentTimeMillis() - this.refreshedAt > 5_000L) this.refresh();
            int count = this.records.size();
            double body = count == 0 ? SPlayerStyle.px(120)
                    : Math.ceil(count / 3.0) * this.cardHeight() + (Math.ceil(count / 3.0) - 1) * SPlayerStyle.px(12);
            return this.headerHeight() + body + SECTION_GAP;
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            float alpha = this.getAlpha();
            double x = this.getX();
            double y = this.getY();
            List<ListeningStats.TrackRecord> current = this.records;
            sectionTitle(x, y, "反复聆听",
                    current.isEmpty() ? "听得越多，这里越懂你" : "你最常听的 " + current.size() + " 首歌", alpha);
            double top = y + this.headerHeight();
            this.hovered = -1;
            if (current.isEmpty()) {
                for (RoundedImageWidget cover : this.covers) cover.setHidden(true);
                double height = SPlayerStyle.px(120);
                drawDashed(x, top, this.getWidth(), height, SPlayerStyle.RADIUS_XL,
                        SPlayerStyle.a(SPlayerStyle.onSurface(), .12, alpha));
                SPlayerIcons.draw(SPlayerIcons.HEADPHONES, x + this.getWidth() * .5, top + height * .5 - SPlayerStyle.px(12),
                        SPlayerStyle.px(28), SPlayerStyle.a(SPlayerStyle.onSurfaceVariant(), .40, alpha));
                CFontRenderer font = SPlayerStyle.textSm(false);
                String hint = "开始听歌后，这里会出现你最常听的歌";
                SPlayerStyle.text(font, hint, x + (this.getWidth() - font.getStringWidthD(hint)) * .5,
                        top + height * .5 + SPlayerStyle.px(16), 0,
                        SPlayerStyle.a(SPlayerStyle.onSurfaceVariant(), .40, alpha));
                return;
            }
            double gap = SPlayerStyle.px(12);
            double cardWidth = (this.getWidth() - gap * 2) / 3.0;
            double cardHeight = this.cardHeight();
            for (int index = 0; index < LIMIT; index++) {
                RoundedImageWidget cover = this.covers[index];
                if (index >= current.size()) {
                    cover.setHidden(true);
                    continue;
                }
                cover.setHidden(false);
                Music music = current.get(index).getMusic();
                int column = index % 3;
                int row = index / 3;
                double cx = x + column * (cardWidth + gap);
                double cy = top + row * (cardHeight + gap);
                boolean over = this.isHovering() && mouseX >= cx && mouseX <= cx + cardWidth
                        && mouseY >= cy && mouseY <= cy + cardHeight;
                if (over) this.hovered = index;
                this.hover[index] = SPlayerStyle.approach(this.hover[index], over ? 1f : 0f);
                SPlayerStyle.card(cx, cy, cardWidth, cardHeight, SPlayerStyle.RADIUS_XL, this.hover[index], alpha);

                double cursor = cx + SPlayerStyle.px(12);
                double centerY = cy + cardHeight * .5;
                CFontRenderer noFont = SPlayerStyle.textSm(true);
                String no = String.format(Locale.ROOT, "%02d", index + 1);
                SPlayerStyle.text(noFont, no, cursor + (SPlayerStyle.px(20) - noFont.getStringWidthD(no)) * .5, centerY, 0,
                        SPlayerStyle.a(SPlayerStyle.onSurfaceVariant(), .30, alpha));
                cursor += SPlayerStyle.px(20) + SPlayerStyle.px(12);

                double coverSize = SPlayerStyle.px(48);
                requestCover(music, false);
                if (TextureManager.getInstance().getTexture(music.getSmallCoverLocation()) == null) {
                    SPlayerStyle.fill(cursor, centerY - coverSize * .5, coverSize, coverSize, SPlayerStyle.RADIUS_LG,
                            SPlayerStyle.a(SPlayerStyle.onSurface(), .08, alpha));
                }
                cover.setBounds(cursor - x, centerY - coverSize * .5 - y, coverSize, coverSize);
                cover.setRadius(SPlayerStyle.RADIUS_LG);
                double coverX = cursor;
                cursor += coverSize + SPlayerStyle.px(12);

                CFontRenderer countFont = SPlayerStyle.textXs(false);
                String count = "播放 " + current.get(index).getCount() + " 次";
                boolean showCount = cardWidth > SPlayerStyle.px(260);
                double countWidth = showCount ? countFont.getStringWidthD(count) : 0;
                double right = cx + cardWidth - SPlayerStyle.px(12);
                if (showCount) {
                    SPlayerStyle.text(countFont, count, right - countWidth, centerY, 0,
                            SPlayerStyle.a(SPlayerStyle.onSurfaceVariant(), .45, alpha));
                }
                double textWidth = right - countWidth - (showCount ? SPlayerStyle.px(12) : 0) - cursor;
                SPlayerStyle.text(SPlayerStyle.textSm(false), music.getName(), cursor, centerY - SPlayerStyle.px(9),
                        textWidth, SPlayerStyle.a(SPlayerStyle.onSurface(), 1.0, alpha));
                SPlayerStyle.text(SPlayerStyle.textXs(false), music.getArtistsName(), cursor, centerY + SPlayerStyle.px(10),
                        textWidth, SPlayerStyle.a(SPlayerStyle.onSurfaceVariant(), .50, alpha));
                // 悬停播放遮罩画在封面之上：交给 afterRenderChildren，这里只记下位置。
                this.overlayX[index] = coverX;
                this.overlayY[index] = centerY - coverSize * .5;
            }
        }

        private final double[] overlayX = new double[LIMIT];
        private final double[] overlayY = new double[LIMIT];

        @Override
        protected void afterRenderChildren(double mouseX, double mouseY) {
            float alpha = this.getAlpha();
            double coverSize = SPlayerStyle.px(48);
            for (int index = 0; index < Math.min(LIMIT, this.records.size()); index++) {
                if (this.hover[index] <= .01f) continue;
                SPlayerStyle.fill(this.overlayX[index], this.overlayY[index], coverSize, coverSize, SPlayerStyle.RADIUS_LG,
                        SPlayerStyle.a(0x000000, .45 * this.hover[index], alpha));
                SPlayerIcons.draw(SPlayerIcons.PLAY, this.overlayX[index] + coverSize * .5, this.overlayY[index] + coverSize * .5,
                        SPlayerStyle.px(20), SPlayerStyle.a(0xFFFFFF, this.hover[index], alpha), true);
            }
        }
    }

    // ------------------------------------------------------------------
    // 歌单网格的标题
    // ------------------------------------------------------------------

    /** 歌单网格上方的标题行（{@code text-lg font-semibold} + {@code text-xs /50} 副标题）。 */
    static final class GridTitle extends Section {
        private final Supplier<String> title;
        private final Supplier<String> subtitle;

        GridTitle(ScrollPanel container, Supplier<String> title, Supplier<String> subtitle) {
            super(container);
            this.title = title;
            this.subtitle = subtitle;
            this.setClickable(false);
        }

        @Override
        double measure(double width) {
            return SPlayerStyle.px(28) + SPlayerStyle.px(2) + SPlayerStyle.px(16);
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            sectionTitle(this.getX(), this.getY(), this.title.get(), this.subtitle.get(), this.getAlpha());
        }
    }

    // ------------------------------------------------------------------
    // 共用画法
    // ------------------------------------------------------------------

    /** 板块标题：{@code text-lg font-semibold} 标题 + {@code mt-0.5 text-xs text-on-surface-variant/50} 副标题。 */
    static void sectionTitle(double x, double y, String title, String subtitle, float alpha) {
        SPlayerStyle.text(SPlayerStyle.textLg(), title, x, y + SPlayerStyle.px(14), 0,
                SPlayerStyle.a(SPlayerStyle.onSurface(), 1.0, alpha));
        if (subtitle != null && !subtitle.isEmpty()) {
            SPlayerStyle.text(SPlayerStyle.textXs(false), subtitle, x, y + SPlayerStyle.px(28) + SPlayerStyle.px(2)
                    + SPlayerStyle.px(8), 0, SPlayerStyle.a(SPlayerStyle.onSurfaceVariant(), .50, alpha));
        }
    }

    /** 虚线圆角框（空状态的 {@code border-dashed}）：沿边画短线段。 */
    static void drawDashed(double x, double y, double width, double height, double radius, int argb) {
        double dash = SPlayerStyle.px(6);
        double gap = SPlayerStyle.px(4);
        double thickness = SPlayerStyle.px(1);
        for (double cursor = x + radius; cursor < x + width - radius; cursor += dash + gap) {
            double end = Math.min(cursor + dash, x + width - radius);
            SPlayerStyle.line(cursor, y + thickness * .5, end, y + thickness * .5, thickness, argb);
            SPlayerStyle.line(cursor, y + height - thickness * .5, end, y + height - thickness * .5, thickness, argb);
        }
        for (double cursor = y + radius; cursor < y + height - radius; cursor += dash + gap) {
            double end = Math.min(cursor + dash, y + height - radius);
            SPlayerStyle.line(x + thickness * .5, cursor, x + thickness * .5, end, thickness, argb);
            SPlayerStyle.line(x + width - thickness * .5, cursor, x + width - thickness * .5, end, thickness, argb);
        }
    }

    /** 封面请求的重试间隔：下载失败后闸门会放开，不节流的话每帧都会重发一次请求。 */
    private static final long COVER_RETRY_MILLIS = 20_000L;
    private static final int COVER_REQUEST_MEMORY = 256;
    private static final java.util.LinkedHashMap<Location, Long> COVER_REQUESTS = new java.util.LinkedHashMap<>();

    /** 曲目封面按需请求（大图走 256、小图走 64，与曲目行、正在播放的口径一致）；同一张 20 秒内只发一次。 */
    static void requestCover(Music music, boolean large) {
        if (music == null) return;
        Location location = large ? music.getCoverLocation() : music.getSmallCoverLocation();
        if (TextureManager.getInstance().getTexture(location) != null) return;
        long now = System.currentTimeMillis();
        Long last = COVER_REQUESTS.get(location);
        if (last != null && now - last < COVER_RETRY_MILLIS) return;
        String url = music.getCoverUrl(large ? 256 : 64);
        if (url == null || url.trim().isEmpty()) return;
        COVER_REQUESTS.remove(location);
        COVER_REQUESTS.put(location, now);
        if (COVER_REQUESTS.size() > COVER_REQUEST_MEMORY) {
            COVER_REQUESTS.remove(COVER_REQUESTS.keySet().iterator().next());
        }
        Textures.downloadTextureAndLoadAsync(url, location);
    }
}
