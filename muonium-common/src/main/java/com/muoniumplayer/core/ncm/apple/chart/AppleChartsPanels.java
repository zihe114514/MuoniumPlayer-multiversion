package com.muoniumplayer.core.ncm.apple.chart;

import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.screens.ncm.panels.HomePanel;
import com.muoniumplayer.core.screens.ncm.NCMPanel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Apple 榜单页:地区卡片墙(每张卡 = 一个 storefront 的 Top 100 虚拟歌单)。
 * 与 QQ/酷狗榜单页同形:这里只发请求拉目录,曲目由 {@link PlayList#queryMusics}
 * 走 {@code chart:<storefront>} 懒加载。
 */
public final class AppleChartsPanels {

    private AppleChartsPanels() {
    }

    /**
     * 榜单入口页:8 个地区各一张卡;feed 全挂返回 null(调用方维持原页面)。
     *
     * <p>调用方在后台线程里跑本方法(navigateAsync),所以这里敢逐地区拉 feed:
     * 卡面用该地区冠军曲的封面(榜单没有自己的封面图),曲目数用 feed 实际条数。
     * 拉过的 feed 进缓存,点进详情的曲目懒加载直接复用,不重打。</p>
     */
    public static NCMPanel loadTopListsPanel() {
        List<PlayList> cards = new ArrayList<>();
        for (String storefront : AppleChartsService.STOREFRONTS) {
            List<AppleChartsService.ChartEntry> feed =
                    AppleChartsService.topChartCached(storefront);
            String cover = feed.isEmpty() ? "" : feed.get(0).coverUrl;
            int trackCount = feed.size();
            PlayList card = new PlayList(
                    stableCardId(storefront),
                    AppleChartsService.chartTitle(storefront),
                    cover, trackCount, 0L, null, "Apple Music 榜单", 0L);
            card.setPlatform(MusicPlatform.APPLE);
            card.setPlatformPlaylistId(AppleChartsService.CHART_PLAYLIST_PREFIX + storefront);
            cards.add(card);
        }
        if (cards.isEmpty()) return null;
        return new HomePanel(cards, "Apple Music 排行榜 · " + cards.size());
    }

    /** 卡片 id 稳定键(FNV 形状与其他平台一致),不同地区不冲突。 */
    private static long stableCardId(String storefront) {
        long h = 0x811C9DC5L;
        String key = "apple-chart:" + storefront;
        for (int i = 0; i < key.length(); i++) {
            h ^= key.charAt(i);
            h *= 0x01000193L;
        }
        return -4_000_000L - (h & 0xFFFFFL);
    }
}
