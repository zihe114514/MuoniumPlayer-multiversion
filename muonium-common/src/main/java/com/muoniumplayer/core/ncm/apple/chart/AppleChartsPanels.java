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
     * <p>调用方在后台线程里跑本方法(navigateAsync)。8 个 feed **并行**拉取 ——
     * RSS 源在 CN 慢且抖,串行是「各地区最慢耗时 × 8」的体感;并行后页开时间
     * 等于最慢的一个。卡面用该地区冠军曲的封面(榜单没有自己的封面图),
     * 曲目数用 feed 实际条数;拉过的 feed 进缓存,详情懒加载直接复用。</p>
     */
    public static NCMPanel loadTopListsPanel() {
        List<PlayList> cards = chartCards();
        if (cards == null) return null;
        return new HomePanel(cards, "Apple Music 排行榜 · " + cards.size());
    }

    /** 榜单卡构建(主页兜底复用);feed 全挂返回 null。 */
    public static List<PlayList> chartCards() {
        java.util.Map<String, java.util.List<AppleChartsService.ChartEntry>> feeds =
                new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(
                AppleChartsService.STOREFRONTS.size());
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(
                        Math.min(8, AppleChartsService.STOREFRONTS.size()), r -> {
                            Thread t = new Thread(r, "muonium-chart-feed");
                            t.setDaemon(true);
                            return t;
                        });
        try {
            for (String storefront : AppleChartsService.STOREFRONTS) {
                pool.execute(() -> {
                    try {
                        feeds.put(storefront, AppleChartsService.topChartCached(storefront));
                    } finally {
                        done.countDown();
                    }
                });
            }
            try {
                // 总闸:RSS 再慢也不让页面干等超过 15 秒,没回来的地区先按空卡画。
                done.await(15, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        } finally {
            pool.shutdownNow();
        }

        List<PlayList> cards = new ArrayList<>();
        boolean anyFeed = false;
        for (String storefront : AppleChartsService.STOREFRONTS) {
            java.util.List<AppleChartsService.ChartEntry> feed =
                    feeds.getOrDefault(storefront, java.util.Collections.emptyList());
            if (!feed.isEmpty()) anyFeed = true;
            String cover = feed.isEmpty() ? "" : feed.get(0).coverUrl;
            PlayList card = new PlayList(
                    stableCardId(storefront),
                    AppleChartsService.chartTitle(storefront),
                    cover, feed.size(), 0L, null, "Apple Music 榜单", 0L);
            card.setPlatform(MusicPlatform.APPLE);
            card.setPlatformPlaylistId(AppleChartsService.CHART_PLAYLIST_PREFIX + storefront);
            cards.add(card);
        }
        if (!anyFeed) return null;
        return cards;
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
