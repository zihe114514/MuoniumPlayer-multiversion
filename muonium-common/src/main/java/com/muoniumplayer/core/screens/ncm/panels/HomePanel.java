package com.muoniumplayer.core.screens.ncm.panels;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.Getter;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.BilibiliFavoriteService;
import com.muoniumplayer.core.ncm.music.NeteaseMusicService;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.KuGouMusicService;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.QishuiBackend;
import com.muoniumplayer.core.ncm.music.QishuiMusicService;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.texture.Textures;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedImageWidget;
import com.muoniumplayer.core.screens.ncm.NCMPanel;
import com.muoniumplayer.core.screens.ncm.PlaylistCoverHero;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.json.JsonUtils;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @author MuoniumPlayer Contributors
 * Date: 2025/11/4 22:30
 */
public class HomePanel extends NCMPanel {

    private final MusicPlatform platform;
    /** 固定结果集由播放器内歌单搜索使用，而不触碰首页缓存。 */
    private final List<PlayList> fixedPlaylists;
    private final String fixedRecommendationTitle;
    private final Map<Long, Runnable> fixedPlaylistActions;

    public HomePanel() {
        this(null, null, Collections.emptyMap());
    }

    /**
     * 构建一个只读的歌单卡片页，当前被网易云发现/搜索页使用。
     * 该结果不拥有全局缓存，因此返回首页总是恢复普通推荐页。
     */
    public HomePanel(List<PlayList> playlists, String recommendationTitle) {
        this(playlists, recommendationTitle, Collections.emptyMap());
    }

    /**允许数字专辑等发现卡片保持歌单卡片外观，而
     * 打开它们的提供方特定详情操作，而不是将专辑当作歌单处理。 */
    public HomePanel(List<PlayList> playlists, String recommendationTitle, Map<Long, Runnable> cardActions) {
        super();
        this.platform = NeteaseMusicService.getCurrentPlatform();
        this.fixedPlaylists = playlists == null ? null : new ArrayList<>(playlists);
        this.fixedRecommendationTitle = recommendationTitle;
        this.fixedPlaylistActions = cardActions == null ? Collections.emptyMap() : new LinkedHashMap<>(cardActions);
    }

    /** True only for the platform recommendation home, not a fixed result/discovery page. */
    public boolean isDefaultHomePage() {
        return this.fixedPlaylists == null;
    }

    private static final int NETEASE_HOME_TARGET = 240;
    private static final int NETEASE_PAGE_SIZE = 50;
    private static final ArrayList<PlayList> playLists = new ArrayList<>();
    private static volatile boolean neteaseHomeComplete;
    private static final int QQ_HOME_TARGET = 240;
    private static volatile List<PlayList> qqHomePlaylists = Collections.emptyList();
    private static volatile boolean qqHomeLoading;
    private static final int KUGOU_HOME_TARGET = 240;
    private static volatile List<PlayList> kugouHomePlaylists = Collections.emptyList();
    private static volatile boolean kugouHomeLoading;
    /**
     * 汽水主页要多少张推荐歌单。发现页那条接口不分页（见 {@code QishuiMusicService#recommendPlaylists}），
     * 只能靠反复抽样去重凑量，池子实测上限约 150~160 —— 所以这里直接要满，让后端抽到见底为止；
     * 实际能拿到多少由上游决定。
     */
    private static final int QISHUI_HOME_TARGET = 160;
    private static volatile List<PlayList> qishuiHomePlaylists = Collections.emptyList();
    private static volatile boolean qishuiHomeLoading;
    private static volatile List<PlayList> bilibiliHomePlaylists = Collections.emptyList();
    private static volatile boolean bilibiliHomeLoading;

    // ─────────────────────────── W2 主页网格几何 ───────────────────────────
    /** 页面左右内边距：页头与网格共用，网格视口宽度固定是「面板宽 - 24」。 */
    private static final double PAGE_MARGIN = 12.0;
    /** 欢迎语位置（pf25bold）。 */
    private static final double HEADER_TITLE_Y = 12.0;
    /** 「推荐歌单 · N」位置（pf14bold）。 */
    private static final double HEADER_SUBTITLE_Y = 39.0;
    /** 内容起始 y：占位布局与正式网格共用，数据到达时页面不跳。 */
    private static final double CONTENT_TOP = 60.0;
    /** 内容底部留白。 */
    private static final double CONTENT_BOTTOM_PADDING = 12.0;
    /** 卡片栅格间距（ScrollPanel 的横竖间距共用这一个值）。 */
    private static final double GRID_GAP = 12.0;
    /** hover 时封面四周各外扩多少：卡片宽里先给它留 5L，封面静态边长才是 cardW - 5。 */
    private static final double COVER_HOVER_EXPANSION = 5.0;
    /** 旧的密度公式：一列的基准宽 = 105 卡片 + 12 间距 = 117。 */
    private static final double GRID_COLUMN_PITCH = 105.0 + GRID_GAP;
    /**
     * 网格量化单位：1/64 L。
     *
     * <p>{@link ScrollPanel} 用 {@code offsetX + width > 视口宽} 判断换行，浮点累加只要多出
     * 1e-13，每行最后一格就会被挤到下一行。把卡片宽量化到 1/64 L 后，累加是精确的，
     * 且总量保证不超过视口宽；右侧余下的最大 1/64 L 肉眼不可见，左右边距仍然相等。</p>
     */
    private static final double GRID_QUANTUM = 64.0;

    @Override
    public void onInit() {
        if (fixedPlaylists != null) {
            layout(fixedPlaylists, fixedRecommendationTitle == null || fixedRecommendationTitle.trim().isEmpty()
                    ? "歌单搜索" : fixedRecommendationTitle);
            return;
        }
        if (platform == MusicPlatform.QQ) {
            loadQQHome();
        } else if (platform == MusicPlatform.KUGOU) {
            loadKuGouHome();
        } else if (platform == MusicPlatform.YOUTUBE) {
            layout(Collections.emptyList(), "YouTube Music · 使用搜索开始播放");
        } else if (platform == MusicPlatform.BILIBILI) {
            loadBilibiliHome();
        } else if (platform == MusicPlatform.QISHUI) {
            loadQishuiHome();
        } else {
            loadNeteaseHome();
        }
    }

    /**
     * 无副作用地预热主页这条内容页链路的类与控件构造。
     *
     * <p>首次打开播放器时 {@code new HomePanel()} 加载的是一整棵<b>只在首开才碰到</b>的类树
     * （卡片、标题控件、滚动网格、加载转子），类加载加首次构造全落在换页动画的帧预算里，
     * 是「第一次打开会卡一下」的主要来源之一。这里在 HUD 空闲期（紧跟
     * {@code NCMScreen#prewarmLayout}）用一张<b>假卡片</b>把同样的构造跑一遍：</p>
     *
     * <ul>
     *   <li>走 {@code fixedPlaylists != null} 的固定结果页分支——{@link #onInit} 只做布局，
     *       <b>不发任何网络请求</b>（推荐页的请求只在 {@code fixedPlaylists == null} 时发出）；</li>
     *   <li>封面下载只挂在封面控件的 beforeRenderCallback 上，本实例永不渲染，不会触发；</li>
     *   <li>{@link #layoutPlaceholder} 也走一遍：冷缓存首开先看到的就是那个转子，
     *       {@code PanelLoadingWidget} 同样是首开才加载的类；</li>
     *   <li>实例用完即弃，不进任何缓存，也不挂到 {@code NCMScreen} 上。</li>
     * </ul>
     */
    public static void prewarm() {
        try {
            HomePanel dummy = new HomePanel(Collections.singletonList(
                    new PlayList(-1L, "prewarm", "", 0, 0L, null, null, 0L)), "prewarm");
            dummy.layoutPlaceholder("prewarm", true);
            dummy.onInit();
        } catch (Throwable t) {
            // 预热只买「首开不卡」这一份收益；失败时完整路径在真正打开时照常执行。
            System.err.println("[MuoniumPlayer] 主页预热失败: " + t);
        }
    }

    /**
     * 首页问候语。
     *
     * <p>抽成一处是因为占位布局与正式布局都要用它，而两处如果不一致，加载中和加载完的标题会跳一下。</p>
     */
    private static String welcomeTextFor(MusicPlatform platform) {
        if (platform == MusicPlatform.QQ) return "欢迎使用 QQ 音乐!";
        if (platform == MusicPlatform.KUGOU) return "欢迎使用 酷狗音乐!";
        if (platform == MusicPlatform.YOUTUBE) return "欢迎使用 YouTube Music!";
        if (platform == MusicPlatform.BILIBILI) return "欢迎使用 哔哩哔哩!";
        if (platform == MusicPlatform.QISHUI) return "欢迎使用 汽水音乐!";
        return "欢迎来到 MuoniumPlayer!";
    }

    /**
     * 首页只有<b>标题 + 一枚转子</b>的过渡布局。
     *
     * <p>冷启动时推荐歌单缓存是空的，原来这段等待里 {@code getChildren()} 也是空的 ——
     * 页面整片空白，看不出是在加载还是坏了。{@link #layout} 开头就 {@code getChildren().clear()}，
     * 所以数据回来时这份占位会被自然覆盖，不需要额外清理。</p>
     *
     * @param spinning {@code false} 用于终态提示（获取失败），此时不该继续转
     */
    private void layoutPlaceholder(String message, boolean spinning) {
        this.getChildren().clear();
        this.scrollPanel = null;
        // 占位布局把网格整个丢掉，还没建完的那些卡片也就没有落点了：一起清掉，
        // 否则它们会在下一次 layout 时被追加到新网格上（连的是上一份数据）。
        this.cardsPanel = null;
        this.pendingCards = null;
        this.pendingCardActions = Collections.emptyMap();
        this.recommendationsLabel = null;
        this.displayedCardIds.clear();
        this.layoutGeneration++;
        final String welcomeText = welcomeTextFor(platform);

        LabelWidget lblWelcome = new LabelWidget(welcomeText, FontManager.pf25bold);
        lblWelcome.setClickable(false);
        lblWelcome.setBeforeRenderCallback(() -> lblWelcome
                .setPosition(PAGE_MARGIN, HEADER_TITLE_Y)
                .setMaxWidth(Math.max(1.0, this.getWidth() - PAGE_MARGIN * 2.0))
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH)
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT)));
        this.addChild(lblWelcome);

        // 占位内容与正式网格从同一个 y 起排：数据回来换成网格时页面不会跳一下。
        PanelLoadingWidget indicator = new PanelLoadingWidget(message);
        indicator.setSpinning(spinning);
        indicator.setClickable(false);
        indicator.setBeforeRenderCallback(() -> indicator.setBounds(PAGE_MARGIN, CONTENT_TOP,
                Math.max(1.0, this.getWidth() - PAGE_MARGIN * 2.0),
                Math.max(1.0, this.getHeight() - CONTENT_TOP - 30.0)));
        this.addChild(indicator);
    }

    private void loadNeteaseHome() {
        List<PlayList> cached;
        synchronized (playLists) {
            cached = new ArrayList<>(playLists);
        }

        // 在更大的公开页面在后台追加时，
        //不隐藏已加载的推荐。
        if (!cached.isEmpty()) {
            layout(cached, recommendationTitle(cached.size()));
        }
        if (neteaseHomeComplete) {
            return;
        }
        // 冷启动：缓存为空时先立起转子，否则这段网络等待期间首页是整片空白。
        final boolean hadCache = !cached.isEmpty();
        if (!hadCache) {
            layoutPlaceholder("正在获取推荐歌单…", true);
        }

        MultiThreadingUtil.runAsync(() -> {
            LinkedHashMap<Long, PlayList> merged = new LinkedHashMap<>();
            synchronized (playLists) {
                for (PlayList playList : playLists) {
                    if (playList != null) merged.put(playList.getId(), playList);
                }
            }

            // 账号感知的每日推荐留在最前，但该端点
            //刻意只返回一小集，因此不能作为唯一
            //首页源。
            appendNeteasePlaylists(merged, CloudMusicApi.recommendResource(), "recommend");

            boolean personalizedLoaded = appendNeteasePlaylists(
                    merged, CloudMusicApi.personalizedPlaylists(100), "result");

            boolean publicPagesLoaded = true;
            int offset = 0;
            int pagesWithoutNewItems = 0;
            while (merged.size() < NETEASE_HOME_TARGET && offset < NETEASE_HOME_TARGET * 3) {
                int previousSize = merged.size();
                boolean pageLoaded = appendNeteasePlaylists(
                        merged, CloudMusicApi.topPlaylists(NETEASE_PAGE_SIZE, offset), "playlists");
                if (!pageLoaded) {
                    publicPagesLoaded = false;
                    break;
                }

                offset += NETEASE_PAGE_SIZE;
                pagesWithoutNewItems = merged.size() == previousSize
                        ? pagesWithoutNewItems + 1 : 0;
                if (pagesWithoutNewItems >= 2) {
                    break;
                }
            }

            List<PlayList> result = new ArrayList<>(merged.values());
            if (result.size() > NETEASE_HOME_TARGET) {
                result = new ArrayList<>(result.subList(0, NETEASE_HOME_TARGET));
            }

            if (!result.isEmpty()) {
                synchronized (playLists) {
                    playLists.clear();
                    playLists.addAll(result);
                }
            }

            // 若一个公开源失败，保持缓存可用但允许下次访问
            //重试填充，而非永久锁定在部分的首次回答上。
            neteaseHomeComplete = personalizedLoaded
                    && publicPagesLoaded
                    && !result.isEmpty();

            final List<PlayList> displayed = result;
            MultiThreadingUtil.runOnMainThread(() -> {
                if (NeteaseMusicService.getCurrentPlatform() != platform) return;
                if (!displayed.isEmpty()) {
                    layout(displayed, recommendationTitle(displayed.size()));
                    return;
                }
                // 一件都没取到时必须给个终态：否则上面立起的转子会一直转下去，
                // 那比"页面空白"更糟 —— 用户会一直等一个不会来的结果。
                // 有缓存时页面上已经是真实内容，不能拿失败提示把它盖掉。
                if (hadCache) return;
                layoutPlaceholder("推荐歌单获取失败，请稍后重试", false);
            });

            System.out.println("[Music] Loaded " + result.size() + " Netease homepage playlists.");
        });
    }

    private static boolean appendNeteasePlaylists(Map<Long, PlayList> destination,
                                                   com.muoniumplayer.core.ncm.RequestUtil.RequestAnswer answer,
                                                   String arrayName) {
        try {
            if (answer == null || answer.getStatus() < 200 || answer.getStatus() >= 300) {
                return false;
            }

            JsonObject response = answer.toJsonObject();
            JsonElement code = response == null ? null : response.get("code");
            if (response == null || code == null || !code.isJsonPrimitive()
                    || code.getAsInt() != 200 || !response.has(arrayName)
                    || !response.get(arrayName).isJsonArray()) {
                return false;
            }

            JsonArray playlists = response.getAsJsonArray(arrayName);
            for (JsonElement element : playlists) {
                if (!element.isJsonObject()) continue;

                try {
                    JsonObject object = element.getAsJsonObject();
                    if (object.has("picUrl") && !object.get("picUrl").isJsonNull()) {
                        object.addProperty("coverImgUrl", object.get("picUrl").getAsString());
                    }
                    if (object.has("playcount") && !object.get("playcount").isJsonNull()) {
                        object.addProperty("playCount", object.get("playcount").getAsLong());
                    }

                    PlayList playList = JsonUtils.parse(object, PlayList.class);
                    if (playList == null || playList.getId() == 0 || playList.getName() == null
                            || playList.getName().trim().isEmpty() || playList.getCoverUrl() == null
                            || playList.getCoverUrl().trim().isEmpty()) {
                        continue;
                    }
                    destination.putIfAbsent(playList.getId(), playList);
                } catch (Throwable ignored) {
                    // 一条畸形推荐绝不能丢弃其后每个有效条目。
                }
            }
            return true;
        } catch (Throwable throwable) {
            System.err.println("[Music] Failed to parse Netease homepage page '"
                    + arrayName + "': " + throwable.getMessage());
            return false;
        }
    }

    private static String recommendationTitle(int count) {
        return count > 0 ? "推荐歌单 · " + count : "推荐歌单";
    }
    private void loadQQHome() {
        List<PlayList> cached = new ArrayList<>(qqHomePlaylists);
        if (!cached.isEmpty()) {
            layout(cached, qqRecommendationTitle(cached.size()));
            return;
        }

        // 无论这次是否真的发起请求，都要先给出"在忙"的反馈：另一个实例正在加载时直接 return，
        // 原来会留下一个空白页面，看起来像点了没反应。
        layoutPlaceholder("正在获取 QQ 音乐推荐…", true);

        synchronized (HomePanel.class) {
            if (qqHomeLoading) {
                // 已有一次加载在飞：不重复发请求，改为等它把缓存填好再自己布局。
                // 原来是直接 return —— 现在上面立了转子，直接返回会让它永远转下去，
                // 因为那次加载的完成回调作用在另一个 HomePanel 实例上。
                MultiThreadingUtil.runAsync(this::awaitQQHomeCache);
                return;
            }
            qqHomeLoading = true;
        }

        MultiThreadingUtil.runAsync(() -> {
            List<PlayList> result = Collections.emptyList();
            try {
                // Cadence 已提供 QQ 发现页 GetRecommendFeed，优先展示真实推荐歌单。
                result = NeteaseMusicService.getQQRecommendPlaylists(QQ_HOME_TARGET);
                if (result.isEmpty()) {
                    // 推荐接口临时不可用时才回退到排行榜，避免主页整页空白。
                    List<Music> tracks = NeteaseMusicService.getQQTopTracks(50);
                    String cover = tracks.isEmpty() ? "" : tracks.get(0).getCoverUrl(256);
                    PlayList top = new PlayList(-260026L,
                            tracks.isEmpty() ? "巅峰热歌榜（暂时无可用曲目）" : "巅峰榜 · 热歌",
                            cover, tracks.size(), 0L, null, "QQ 音乐热门歌曲", 0L);
                    top.setPlatform(MusicPlatform.QQ);
                    top.setPlatformPlaylistId("toplist:26");
                    top.setSearchMode(true);
                    top.setMusics(new CopyOnWriteArrayList<>(tracks));
                    top.setMusicsQueried(true);
                    top.setMusicsLoaded(true);
                    result = Collections.singletonList(top);
                }

                qqHomePlaylists = Collections.unmodifiableList(new ArrayList<>(result));
                final List<PlayList> displayed = new ArrayList<>(result);
                MultiThreadingUtil.runOnMainThread(() -> {
                    if (NeteaseMusicService.getCurrentPlatform() != platform) return;
                    if (!displayed.isEmpty()) {
                        layout(displayed, qqRecommendationTitle(displayed.size()));
                    } else {
                        layoutPlaceholder("QQ 音乐推荐获取失败，请稍后重试", false);
                    }
                });
            } catch (Throwable throwable) {
                // 原来这里只有 finally：推荐与排行榜双双抛异常时转子会永远转下去。
                System.err.println("[Music] QQ 首页加载失败: " + throwable);
                MultiThreadingUtil.runOnMainThread(() -> {
                    if (NeteaseMusicService.getCurrentPlatform() == platform)
                        layoutPlaceholder("QQ 音乐推荐获取失败，请稍后重试", false);
                });
            } finally {
                qqHomeLoading = false;
            }
        });
    }

    /** 等另一次 QQ 首页加载填好缓存（最多 20 秒），然后用它布局本实例。超时按失败处理。 */
    private void awaitQQHomeCache() {
        final long deadline = System.nanoTime() + 20_000_000_000L;
        while (qqHomeLoading && System.nanoTime() - deadline < 0L) {
            try {
                Thread.sleep(120L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        final List<PlayList> cached = new ArrayList<>(qqHomePlaylists);
        MultiThreadingUtil.runOnMainThread(() -> {
            if (NeteaseMusicService.getCurrentPlatform() != platform) return;
            if (cached.isEmpty()) {
                layoutPlaceholder("QQ 音乐推荐获取失败，请稍后重试", false);
            } else {
                layout(cached, qqRecommendationTitle(cached.size()));
            }
        });
    }

    private static String qqRecommendationTitle(int count) {
        return count > 1 ? "QQ 音乐 · 推荐歌单 · " + count : "QQ 音乐 · 巅峰榜";
    }

    /**
     * 酷狗首页。结构照 {@link #loadQQHome()}：先用静态缓存立即出图，冷启动立转子，
     * 已有另一次加载在飞时不重复发请求、改为等缓存（否则那次加载的回调作用在别的实例上，
     * 本实例的转子会一直转）。
     */
    private void loadKuGouHome() {
        List<PlayList> cached = new ArrayList<>(kugouHomePlaylists);
        if (!cached.isEmpty()) {
            layout(cached, kugouRecommendationTitle(cached.size()));
            return;
        }

        layoutPlaceholder("正在获取酷狗推荐歌单…", true);

        synchronized (HomePanel.class) {
            if (kugouHomeLoading) {
                MultiThreadingUtil.runAsync(this::awaitKuGouHomeCache);
                return;
            }
            kugouHomeLoading = true;
        }

        MultiThreadingUtil.runAsync(() -> {
            try {
                List<PlayList> result = KuGouMusicService.recommendPlaylists(KUGOU_HOME_TARGET);
                if (result.isEmpty()) {
                    // 推荐接口临时不可用时回退到榜单，避免主页整页空白。
                    result = KuGouMusicService.rankPlaylists();
                }
                kugouHomePlaylists = Collections.unmodifiableList(new ArrayList<>(result));
                final List<PlayList> displayed = new ArrayList<>(result);
                MultiThreadingUtil.runOnMainThread(() -> {
                    if (NeteaseMusicService.getCurrentPlatform() != platform) return;
                    if (!displayed.isEmpty()) {
                        layout(displayed, kugouRecommendationTitle(displayed.size()));
                    } else {
                        layoutPlaceholder("酷狗推荐获取失败，请稍后重试", false);
                    }
                });
            } catch (Throwable throwable) {
                System.err.println("[Music] 酷狗首页加载失败: " + throwable);
                MultiThreadingUtil.runOnMainThread(() -> {
                    if (NeteaseMusicService.getCurrentPlatform() == platform)
                        layoutPlaceholder("酷狗推荐获取失败，请稍后重试", false);
                });
            } finally {
                kugouHomeLoading = false;
            }
        });
    }

    /** 等另一次酷狗首页加载填好缓存（最多 20 秒），然后用它布局本实例。超时按失败处理。 */
    private void awaitKuGouHomeCache() {
        final long deadline = System.nanoTime() + 20_000_000_000L;
        while (kugouHomeLoading && System.nanoTime() - deadline < 0L) {
            try {
                Thread.sleep(120L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        final List<PlayList> cached = new ArrayList<>(kugouHomePlaylists);
        MultiThreadingUtil.runOnMainThread(() -> {
            if (NeteaseMusicService.getCurrentPlatform() != platform) return;
            if (cached.isEmpty()) {
                layoutPlaceholder("酷狗推荐获取失败，请稍后重试", false);
            } else {
                layout(cached, kugouRecommendationTitle(cached.size()));
            }
        });
    }

    private static String kugouRecommendationTitle(int count) {
        return count > 0 ? "酷狗音乐 · 推荐歌单 · " + count : "酷狗音乐 · 推荐歌单";
    }
    /**
     * B 站首页。
     *
     * <p>B 站没有推荐歌单这个接口，能拿得出手的是<b>自己的收藏夹</b>，所以这一页登录后就是收藏夹
     * 卡片。未登录时保持原来的提示文案 —— 匿名浏览和播放本来就是通的，别写成「请先登录」。</p>
     */
    private void loadBilibiliHome() {
        if (!NeteaseMusicService.isLoggedIn(MusicPlatform.BILIBILI)) {
            layout(Collections.emptyList(), "哔哩哔哩 · 搜索、粘贴 BV号或视频链接");
            return;
        }

        List<PlayList> cached = new ArrayList<>(bilibiliHomePlaylists);
        if (!cached.isEmpty()) {
            layout(cached, "我的收藏夹 · " + cached.size());
            return;
        }

        layoutPlaceholder("正在获取 B 站收藏夹…", true);
        synchronized (HomePanel.class) {
            if (bilibiliHomeLoading) return;
            bilibiliHomeLoading = true;
        }

        MultiThreadingUtil.runAsync(() -> {
            try {
                List<PlayList> result = BilibiliFavoriteService.userPlaylists();
                bilibiliHomePlaylists = Collections.unmodifiableList(new ArrayList<>(result));
                final List<PlayList> displayed = new ArrayList<>(result);
                MultiThreadingUtil.runOnMainThread(() -> {
                    if (NeteaseMusicService.getCurrentPlatform() != platform) return;
                    if (!displayed.isEmpty()) {
                        layout(displayed, "我的收藏夹 · " + displayed.size());
                    } else {
                        layoutPlaceholder("B 站收藏夹为空，或该账号还没建过收藏夹", false);
                    }
                });
            } catch (Throwable throwable) {
                System.err.println("[Music] B 站首页加载失败: " + throwable);
                MultiThreadingUtil.runOnMainThread(() -> {
                    if (NeteaseMusicService.getCurrentPlatform() == platform)
                        layoutPlaceholder("B 站收藏夹获取失败，请稍后重试", false);
                });
            } finally {
                bilibiliHomeLoading = false;
            }
        });
    }

    /** 汽水首页：公开发现流直接铺成推荐歌单卡片墙。 */
    private void loadQishuiHome() {
        List<PlayList> cached = new ArrayList<>(qishuiHomePlaylists);
        if (!cached.isEmpty()) {
            layout(cached, qishuiRecommendationTitle(cached.size()));
            return;
        }

        layoutPlaceholder("正在获取汽水推荐歌单…", true);
        synchronized (HomePanel.class) {
            if (qishuiHomeLoading) {
                MultiThreadingUtil.runAsync(this::awaitQishuiHomeCache);
                return;
            }
            qishuiHomeLoading = true;
        }

        // 首屏发布记下它铺的是哪一次布局：后续追加只在这一代布局还活着时才落笔，
        // 否则会把新数据补到上一份（已经换掉的）网格上。
        final java.util.concurrent.atomic.AtomicLong publishedGeneration =
                new java.util.concurrent.atomic.AtomicLong(-1L);
        MultiThreadingUtil.runAsync(() -> {
            try {
                // 「每日推荐」虚拟卡排在墙最前：曲目来自 feed/song-tab，打开即取今天的一页。
                // 需要登录 + 签名后端；缺一个就别放这张卡，免得点开是一片空白。
                final PlayList daily = QishuiMusicService.isLoggedIn() && QishuiBackend.isAvailable()
                        ? QishuiMusicService.dailyFeedCard() : null;
                // 渐进式：第一轮采样先铺首屏，后面几轮在后台补齐再追加。
                // 原来是等全部采样跑完才一次性显示，用户要为最后那几十张干等两秒多。
                List<PlayList> sampled = QishuiMusicService.recommendPlaylists(QISHUI_HOME_TARGET,
                        partial -> publishQishuiProgress(partial, daily, publishedGeneration));
                // 「每日推荐」虚拟卡排在墙最前，但它自己不算「这次拉到了内容」。
                final List<PlayList> displayed = new ArrayList<>(sampled.size() + 1);
                if (daily != null) displayed.add(daily);
                displayed.addAll(sampled);
                // 只拿到了那张虚拟卡（采样空手）时不要进缓存：缓存下来会让下一次进主页被
                // cache-first 分支直接命中，于是永远不再重试 —— 一次网络抖动就变成
                // 「主页再也不出内容」。宁可下次再拉一次。
                if (!sampled.isEmpty()) {
                    qishuiHomePlaylists = Collections.unmodifiableList(new ArrayList<>(displayed));
                }
                MultiThreadingUtil.runOnMainThread(() -> {
                    if (NeteaseMusicService.getCurrentPlatform() != platform) return;
                    if (publishedGeneration.get() >= 0) {
                        // 首屏已经在屏幕上了：只补最后一轮新增的那几张。
                        appendQishuiCards(displayed);
                        return;
                    }
                    if (!displayed.isEmpty()) {
                        layout(displayed, qishuiRecommendationTitle(displayed.size()));
                        publishedGeneration.set(this.layoutGeneration);
                    } else {
                        layoutPlaceholder("汽水推荐歌单获取失败，请稍后重试", false);
                    }
                });
            } catch (Throwable throwable) {
                System.err.println("[Music] 汽水首页加载失败: " + throwable);
                MultiThreadingUtil.runOnMainThread(() -> {
                    if (NeteaseMusicService.getCurrentPlatform() == platform)
                        layoutPlaceholder("汽水推荐歌单获取失败，请稍后重试", false);
                });
            } finally {
                qishuiHomeLoading = false;
            }
        });
    }

    /**
     * 一轮采样到手的部分结果：第一次调用铺首屏，之后只把新增的那些追加进去。
     *
     * <p>在采样线程上被调，所以这里立刻把列表拷一份再切主线程 —— 上游传下来的是当轮快照，
     * 但拷贝的成本远小于在主线程上做判重与建控件。</p>
     */
    private void publishQishuiProgress(List<PlayList> partial, PlayList daily,
                                       java.util.concurrent.atomic.AtomicLong publishedGeneration) {
        final List<PlayList> snapshot = new ArrayList<>(partial.size() + 1);
        if (daily != null) snapshot.add(daily);
        snapshot.addAll(partial);
        MultiThreadingUtil.runOnMainThread(() -> {
            if (NeteaseMusicService.getCurrentPlatform() != platform) return;
            if (publishedGeneration.get() < 0) {
                if (snapshot.isEmpty()) return;
                layout(snapshot, qishuiRecommendationTitle(snapshot.size()));
                publishedGeneration.set(this.layoutGeneration);
                // 顺便把这一份写进缓存：加载中途退出再进来，看到的是已有的六十张，
                // 而不是又一次从头转圈。
                qishuiHomePlaylists = Collections.unmodifiableList(new ArrayList<>(snapshot));
                return;
            }
            if (publishedGeneration.get() != this.layoutGeneration) return;
            appendQishuiCards(snapshot);
        });
    }

    /**
     * 把新到的歌单追加进现有网格。
     *
     * <p>只补新卡片，<b>不 clear、不重建、不重播入场</b>：首屏已经在屏幕上了，重建会让用户
     * 看到整屏闪一下，而这里要表达的只是「又多了一批」。新增的部分同样走分批队列 ——
     * 一次追加一百来张也不该在一帧里建完。</p>
     */
    private void appendQishuiCards(List<PlayList> cards) {
        // 卡片必须直接挂在网格 ScrollPanel 下：封面接力拿 getParent() 当裁剪视口，
        // 挂在中间层上它就不再是「谁裁住我」的那一层了（见 PlaylistCoverHero）。
        if (this.cardsPanel == null || this.cardsPanel != this.scrollPanel) return;
        List<PlayList> fresh = new ArrayList<>();
        for (PlayList playlist : cards) {
            if (playlist == null) continue;
            // 按歌单 id 判重：渐进发布是累积快照，前后两批必然重叠。
            if (this.displayedCardIds.add(playlist.getId())) fresh.add(playlist);
        }
        if (fresh.isEmpty()) return;
        if (this.recommendationsLabel != null) {
            this.recommendationsLabel.setLabel(qishuiRecommendationTitle(this.displayedCardIds.size()));
        }
        this.pendingCardActions = this.fixedPlaylistActions;
        if (this.pendingCards != null) {
            this.pendingCards.addAll(fresh);
        } else {
            this.pendingCards = new ArrayList<>(fresh);
            this.pendingCardIndex = 0;
            buildPendingCards();
        }
    }

    private void awaitQishuiHomeCache() {
        final long deadline = System.nanoTime() + 20_000_000_000L;
        while (qishuiHomeLoading && System.nanoTime() - deadline < 0L) {
            try {
                Thread.sleep(120L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        final List<PlayList> cached = new ArrayList<>(qishuiHomePlaylists);
        MultiThreadingUtil.runOnMainThread(() -> {
            if (NeteaseMusicService.getCurrentPlatform() != platform) return;
            if (cached.isEmpty()) {
                layoutPlaceholder("汽水推荐歌单获取失败，请稍后重试", false);
            } else {
                layout(cached, qishuiRecommendationTitle(cached.size()));
            }
        });
    }

    private static String qishuiRecommendationTitle(int count) {
        return count > 0 ? "汽水音乐 · 推荐歌单 · " + count : "汽水音乐 · 推荐歌单";
    }
    public ScrollPanel scrollPanel;

    /** 主页网格几何（列宽/封面边长）：每帧 O(1) 刷新，所有卡片共享同一份。 */
    private final GridMetrics grid = new GridMetrics();
    /** 尺寸变化时卡片重排的补间：全网格共享一条时钟（见 {@link GridReshape}）。 */
    private final GridReshape reshape = new GridReshape();
    /**
     * 触发重排补间所需的最小几何跳变（布局单位，按卡片宽变化量算）。
     *
     * <p>1.5L 的意义：内置「播放器大小」缩放时，面板在十几帧里从 100% 平滑逼近 90%，
     * 每帧卡片宽的变化通常只有零点几 L，远低于这个阈值 —— 那种情况卡片直接跟随网格，
     * 观感才连贯。而窗口 resize / 切换 GUI Scale 是一步到位的大跳变（往往几十 L），
     * 这时才值得用补间把「瞬移」抹平成「滑过去」。</p>
     */
    private static final double RESHAPE_MIN_STEP = 1.5;
    /**
     * 「几何刚变过」标记：宽度停止变化的第一帧给在跑的重排补间一个快速收尾，
     * 不让 240ms 的尾巴拖在已经静止的画面上（见 {@link #refreshGridLayout}）。
     */
    private boolean reshapeAwaitingSettle;

    /**
     * 把当前网格几何立刻推给所有已构建卡片（绕过「等卡片自己 onRender 时再套用」的旧时序）。
     *
     * <p>只在列宽/封面边长变化的那几帧被调；卡片侧 {@code applyGridGeometry} 有 revision
     * 快速路径，几何没变的卡片在这里只是一次 long 比较。</p>
     */
    private void applyGridGeometryToCards() {
        if (this.scrollPanel == null) return;
        for (AbstractWidget<?> child : this.scrollPanel.getChildren()) {
            if (child instanceof PlaylistWidget) {
                ((PlaylistWidget) child).applyGridGeometryNow();
            }
        }
    }

    private void layout(List<PlayList> displayedPlayLists, String recommendationsText) {
        this.getChildren().clear();
        final String welcomeText = welcomeTextFor(platform);

        // 页头几何改成固定 token：欢迎语 (12,12)、副标题 (12,39)、内容从 60 起。
        // 以前副标题的 y 是由字体量出来的，字体图集没就绪时的负高度会让它上下跳；
        // 固定值之后占位布局与正式布局共用同一个 contentTop，数据到达时网格不再整体跳一下。
        scrollPanel = new ScrollPanel();
        this.addChild(scrollPanel);

        scrollPanel
                .setSpacing(GRID_GAP)
                .setScrollStrength(44)
                .setAlignment(ScrollPanel.Alignment.VERTICAL_WITH_HORIZONTAL_FILL);
        // 网格视口的 bounds 不在这里挂 beforeRender：几何由 HomePanel#onRender 每帧 O(1) 刷新，
        // 那里跑在子控件之前，本帧新补的卡片在构造时就能拿到当帧列宽。
        // 账号身份已经挪到侧栏的账号行（那里本来就有头像和昵称），主页下方不再需要给它留位置。

        LabelWidget lblWelcome = new LabelWidget(welcomeText, FontManager.pf25bold);
        lblWelcome.setBeforeRenderCallback(() -> lblWelcome
                .setPosition(PAGE_MARGIN, HEADER_TITLE_Y)
                .setMaxWidth(Math.max(1.0, this.getWidth() - PAGE_MARGIN * 2.0))
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH)
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT)));
        this.addChild(lblWelcome);

        LabelWidget lblRecommendations = new LabelWidget(recommendationsText, FontManager.pf14bold);
        lblRecommendations.setBeforeRenderCallback(() -> lblRecommendations
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                .setMaxWidth(Math.max(1.0, this.getWidth() - PAGE_MARGIN * 2.0))
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH)
                .setPosition(PAGE_MARGIN, HEADER_SUBTITLE_Y));
        this.addChild(lblRecommendations);
        // 渐进发布的追加批要改这一行的计数（60 → 150），而不是重建整页。
        this.recommendationsLabel = lblRecommendations;

        // 标题刻意加在被裁剪网格之后，因此它们始终在最上。
        //
        // 卡片按网格顺序错峰落进来，和歌单页的曲目行用的是同一套动作（PanelItemEntrance）：
        // 主页以前完全没有进场，切回主页就是整屏内容凭空出现，和旁边那些有动画的页面比很突兀。
        // 号是卡片第一次被渲染时领的（网格同样是 ScrollPanel，视口外的卡片不渲染），所以往下滚
        // 露出来的那几行也会补一次进场，而不是只有首屏有动画。
        this.revealQueue.restart();
        // 卡片不在这里一口气建完：整张网格最多 240 张卡（每张封面 + 标题 + 若干回调），
        // 一次性构造会把换页/数据到达那一帧撑长成一次可见的顿挫。交给 onRender 每帧补一批，
        // 首屏先出来，其余在随后几帧内到齐（见 buildPendingCards）。
        // 不逐张 addChild 的理由不变：CopyOnWriteArrayList 逐张 add 会为每张卡复制整个数组，
        // 一批一次 addAll 只发布一个快照。这些 card 仍直接挂在网格 ScrollPanel 下，
        // 所以接力的视口认领关系不变。
        this.cardsPanel = scrollPanel;
        this.pendingCards = new ArrayList<>(displayedPlayLists);
        this.pendingCardIndex = 0;
        this.pendingCardActions = fixedPlaylistActions;
        // 记下这一代网格上面有哪些卡片，并给这一代布局一个新号码：之后到的追加批
        // 只在这一代还活着时落笔（用户中途换来源/重排，号码就对不上了）。
        this.displayedCardIds.clear();
        for (PlayList playlist : displayedPlayLists) {
            if (playlist != null) this.displayedCardIds.add(playlist.getId());
        }
        this.layoutGeneration++;
        // 先把当帧列宽算好再补第一批卡片：面板已经上屏时（占位 → 数据到达）这一帧就是最终几何。
        // 冷启动首帧面板宽还是 0，这一次调用是 no-op，卡片先按默认宽构造，下一次渲染前会校正。
        this.refreshGridLayout();
        buildPendingCards();

    }

    /**
     * 每帧 O(1) 地刷新网格几何与滚动视口 bounds。
     *
     * <p>放在 {@link #onRender} 里而不是卡片里：onRender 跑在所有子控件之前，
     * 本帧新补的卡片在构造时就能拿到当帧列宽；列宽没变时 {@link GridMetrics#update}
     * 只是一次 double 比较，连除法都不做。</p>
     */
    private void refreshGridLayout() {
        // update 只在几何真的变了才返回 true（宽度没变时连列数除法都不做）。
        if (this.grid.update(this.getWidth())) {
            // 关键时序：先把新卡宽推给所有已构建卡片，再失效容器布局。
            //
            // 顺序反了的话（先失效、卡片在自己的 onRender 里才改宽），ScrollPanel 的
            // alignChildren 会用「上一帧的旧卡宽」摆位 —— 本帧排出来就是错的；下一帧
            // 宽度不再变、失效不再发生，布局签名命中，这份错位摆位被冻结，直到一滚动
            // （偏移入签）才重排。表现正是「缩放堆叠抽搐 / 切到排行榜间隙错位，滚动自愈」。
            // 卡片侧有 revision 快速路径，非本代的卡片在这里只是一次 long 比较，240 张可忽略。
            this.applyGridGeometryToCards();
            // 卡片宽变了 = 子项尺寸变了，网格容器必须重排。显式失效比让容器每帧
            // 遍历 240 个子项求和便宜得多。
            if (this.scrollPanel != null) this.scrollPanel.invalidateLayout();
            // 连续缩放（内置「播放器大小」）与离散跳变（窗口 resize）必须分开对待：
            // 前者面板本身正在十几帧里平滑逼近目标，卡片只要老老实实跟着网格走就是顺的；
            // 再叠一层补间会和面板运动打架，表现成抽搐与闪烁。
            // 判据是「这一步卡片宽变了多少」——连续缩放每步通常远小于 1L。
            // 例外是跨列：那一步的宽度变化可能很小，但整行卡片会换行，必须补一次。
            if (this.grid.lastStepDelta >= RESHAPE_MIN_STEP || this.grid.columnsChanged) {
                this.reshape.restart();
            } else {
                // 小步跟随：立刻收掉任何在跑的补间，卡片直接落在网格算好的位置上。
                this.reshape.finishImmediately();
            }
            // 记下「几何刚变过」：宽度停止变化的第一帧（下一行）要给在跑的补间一个快速收尾。
            this.reshapeAwaitingSettle = true;
        } else if (this.reshapeAwaitingSettle) {
            // resize 结束帧：几何不再变，若补间还在跑，把行程砍到只剩 30% 立刻收拢 ——
            // 否则 240ms 的尾巴会拖在已经静止的画面上，像卡片慢半拍才想起来归位。
            this.reshapeAwaitingSettle = false;
            this.reshape.settleSoon();
        }
        if (this.scrollPanel != null) {
            this.scrollPanel.setBounds(
                    PAGE_MARGIN,
                    CONTENT_TOP,
                    Math.max(1.0, this.getWidth() - PAGE_MARGIN * 2.0),
                    Math.max(1.0, this.getHeight() - CONTENT_TOP - CONTENT_BOTTOM_PADDING));
        }
    }

    /** 攒着还没建完的卡片源（{@link #buildPendingCards} 每帧消耗一批）。 */
    private List<PlayList> pendingCards;
    private int pendingCardIndex;
    private Map<Long, Runnable> pendingCardActions = Collections.emptyMap();
    /** 这一批卡片最终挂到哪个网格上。网格本身在 {@link #layout} 里换新，所以每帧读它。 */
    private ScrollPanel cardsPanel;
    /**
     * 每次 {@link #layout} 递增。渐进发布用它认出「我铺的那一代布局还在不在」——
     * 中间用户换了来源、或数据重排过一次，旧的一批追加就该作废。
     */
    private volatile long layoutGeneration;
    /** 网格上现有卡片的歌单 id。追加时据此判重：渐进发布是累积快照，前后必然重叠。 */
    private final java.util.Set<Long> displayedCardIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 「推荐歌单 · N」那一行。追加后要就地改数字，而不是重建整页。 */
    private LabelWidget recommendationsLabel;

    /**
     * 每帧只构建一小批卡片。
     *
     * <p>与歌单页的 {@code PlaylistPanel#buildPendingMusicWidgets} 同一形状：真正昂贵的是
     * {@link PlaylistWidget} 及其子控件树的创建与首次布局，把它摊到渲染帧之间，避免首开、
     * 换源、数据到达这些时刻出现长帧。分批不影响入场观感 —— 进场号是卡片第一次被渲染时
     * 才向 {@link #revealQueue} 领的，分批只是让它们晚一两帧领号。</p>
     */
    private void buildPendingCards() {
        if (this.cardsPanel == null || this.pendingCards == null) return;

        int total = this.pendingCards.size();
        int end = Math.min(total, this.pendingCardIndex + CARD_BUILD_BATCH);
        if (this.pendingCardIndex < end) {
            List<AbstractWidget<?>> batch = new ArrayList<>(end - this.pendingCardIndex);
            for (int index = this.pendingCardIndex; index < end; index++) {
                PlayList playList = this.pendingCards.get(index);
                PlaylistWidget card = new PlaylistWidget(playList,
                        this.pendingCardActions.get(playList.getId()), this.grid, this.reshape,
                        this.cardsPanel);
                card.setShouldOverrideMouseCursor(true);
                card.setEntranceAnimation(this.revealQueue);
                batch.add(card);
            }
            this.cardsPanel.addChild(batch);
            // 子项数量变了，排版签名必须失效：否则容器会以为上一帧的排布仍然有效。
            this.cardsPanel.invalidateLayout();
            this.pendingCardIndex = end;
        }

        if (this.pendingCardIndex >= total) {
            this.pendingCards = null;
            this.pendingCardActions = Collections.emptyMap();
        }
    }

    /** 每帧构建的卡片数。与歌单页同一档：一屏可见十来张，32 张约两屏，首屏当帧就到齐。 */
    private static final int CARD_BUILD_BATCH = 32;

    /** 卡片进场的错峰发号器，见 {@link PanelItemEntrance.RevealQueue}。 */
    private final PanelItemEntrance.RevealQueue revealQueue = new PanelItemEntrance.RevealQueue();

    @Override
    public void onRender(double mouseX, double mouseY) {
        // 页头/网格几何每帧只做一次 O(1) 更新（宽度没变时只是一次比较），
        // 让本帧补上的卡片在构造时就能读到当帧列宽。
        this.refreshGridLayout();
        // 重排补间每帧只推进一次：卡片在矩阵回调里只读结果。
        // 若让每张卡各自 tick，同一帧里几十张卡会把共享时钟推进几十遍，动画直接跑飞。
        this.reshape.tick();
        // 先把这一批卡片补上，再发号：本帧新挂的卡片会在同一次渲染里被遍历到，
        // 于是它们第一次被渲染就是这一帧，入场动画从这一帧起算。
        buildPendingCards();
        this.revealQueue.beginFrame();
        this.revealQueue.setAnimationsSuppressed(this.scrollPanel != null && this.scrollPanel.isScrollActive());
        super.onRender(mouseX, mouseY);
    }

    /**
     * 窗口/播放器尺寸变化时，卡片重排的补间状态（全网格共享一份）。
     *
     * <p>问题：列宽一变，{@link GridMetrics} 当场改写几何，{@code ScrollPanel} 下一帧就把每张卡
     * 摆到新位置 —— 整屏卡片在<b>同一帧</b>瞬移，缩放窗口时看起来像画面被硬切了一下。</p>
     *
     * <p>做法：几何变化的那一刻记下「这一次重排」的起点，之后若干毫秒内每张卡片在<b>渲染期</b>
     * 把「旧位置 - 新位置」的差值按缓动补掉（走矩阵 translate，真实布局边界不动，滚动与点击
     * 判定完全不受影响）。补间结束偏移归零，之后每帧只付一次 long 比较。</p>
     *
     * <p>为什么不做成「每张卡各自一段动画」：拖窗口时列宽每帧都在变，各自起动画会互相打断、
     * 越拖越乱。这里用一条共享时钟，连续拖动时<b>只续命不重启</b>，卡片始终朝最新几何收敛。</p>
     */
    private static final class GridReshape {

        /** 一次重排的时长。比换页(≈140ms)略长：这是「跟随尺寸变化」而不是「切换页面」。 */
        private static final long DURATION_MILLIS = 240L;
        /** 单帧毫秒上限，挡切后台回来那种断档（同 ScrollPanel 的口径）。 */
        private static final double MAX_FRAME_MS = 64.0;

        /** 这一次重排开始的时刻；0 = 当前没有重排在进行。 */
        private long startedAtNanos;
        /** 上一次推进时的缓动值，用来判断是否还在动。 */
        private double eased = 1.0;
        /**
         * 本帧的缓动值。由面板每帧推进一次（{@link #tick()}），卡片只读它 ——
         * 几十张卡各推进一次会把共享时钟推飞。
         */
        private double frameEased = 1.0;
        /**
         * 拖动窗口期间列宽会连续变化：每变一次就把时钟往前推一小段，
         * 让补间始终「还有一点没走完」，卡片于是平滑地跟着走而不是一顿一顿。
         */
        private boolean active;
        /**
         * 第几次几何变化。卡片据此判断「我手上的差值是不是这一代的」——
         * 连续拖动时每次几何变化都会换基准，旧差值必须作废重算。
         */
        private long generation;

        /** 本帧卡片应当使用的缓动值（只读）。 */
        private double frameEased() {
            return this.frameEased;
        }

        /** 几何变化时调用：开始或续期一次重排。 */
        private void restart() {
            // 续期而不是重置：正在拖窗口时保持同一条时钟，卡片不会每次都从 0 重新起步。
            if (!this.active) {
                this.startedAtNanos = System.nanoTime();
                this.eased = 0.0;
                this.frameEased = 0.0;
                this.active = true;
            } else {
                // 已经在动：把起点往近处拉一点，等价于「再给 40% 的行程」，
                // 连续变化时曲线不会因为每次重置而卡在第一格。
                final long now = System.nanoTime();
                final long total = DURATION_MILLIS * 1_000_000L;
                final long minStart = now - (long) (total * .60);
                if (this.startedAtNanos < minStart) this.startedAtNanos = minStart;
            }
            // 每次几何变化都让这一代的补间从头计：卡片的 reshapeDx/Dy 是相对「上一次排版位置」的
            // 差值，几何一改差值就换了基准，缓动必须同步归零才不会把旧差值按旧进度画出去。
            this.generation++;
        }

        /** 推进到本帧，返回 0..1 的缓动值；没有重排在跑时恒为 1。 */
        private double tick() {
            if (!this.active) {
                this.frameEased = 1.0;
                return 1.0;
            }

            final long now = System.nanoTime();
            final long elapsedNanos = now - this.startedAtNanos;
            final double totalNanos = DURATION_MILLIS * 1_000_000.0;
            final double progress = Math.max(0.0, Math.min(1.0, elapsedNanos / totalNanos));

            // 三次缓出：起步快、收尾缓，缩放窗口时卡片「滑过去」而不是「弹过去」。
            this.eased = 1.0 - Math.pow(1.0 - progress, 3.0);
            if (progress >= 1.0) {
                this.active = false;
                this.eased = 1.0;
            }
            this.frameEased = this.eased;
            return this.eased;
        }

        /** 是否还有卡片需要按偏移绘制（决定要不要挂矩阵变换）。 */
        private boolean isRunning() {
            return this.active;
        }

        /** 立刻收尾（页面被换下去 / 进场被打断时用，避免留下半程偏移）。 */
        private void finishImmediately() {
            this.active = false;
            this.eased = 1.0;
            this.frameEased = 1.0;
        }

        /**
         * 快速收尾：从「当前视觉位置」重新起步，用 30% 时长滑完剩余距离。
         *
         * <p>resize 结束的第一帧调它：几何已经不再变，与其放任 240ms 全程跑完（尾巴拖在
         * 静止画面上），也与其硬切 {@link #finishImmediately()}，不如重算一次差值、
         * 用一段短补间从当前画面位置优雅滑到终点。</p>
         *
         * <p>「重新起步」必须换代：卡片的 reshapeDx/Dy 定格于换代时刻的位置差，若只压时钟
         * 不换代，进度从已走的 5% 跳到 97%，画面瞬移。generation++ 让每张卡下一帧重新定格
         * 「当前位置 → 新位置」的差值（此刻两者已非常接近，差值只剩尾巴那一截），配合
         * 短时钟正好是一条短而准的收尾曲线。</p>
         */
        private void settleSoon() {
            if (!this.active) return;
            final long now = System.nanoTime();
            final long total = DURATION_MILLIS * 1_000_000L;
            // 剩余不足三成就顺其自然走完，不值得再折腾一代。
            final double elapsed = (double) (now - this.startedAtNanos) / total;
            if (elapsed >= .70) return;
            this.startedAtNanos = now - (long) (total * .70);
            this.eased = 1.0 - Math.pow(1.0 - .70, 3.0);
            this.frameEased = this.eased;
            this.generation++;
        }
    }

    /**
     * 主页网格的当前几何（列宽 / 封面边长）。
     *
     * <p>只在面板宽度变化时由 {@link HomePanel#refreshGridLayout()} 改写；所有卡片共享同一份
     * 实例，每帧只比较一次 {@link #revision}，命中就完全跳过 —— 不测量文本、不分配对象。</p>
     */
    private static final class GridMetrics {
        /** 上一次计算用的网格视口宽度；-1 表示还没算过。 */
        private double viewportWidth = -1.0;
        /** 当前卡片宽（已含 hover 外扩的 5L 预留）。 */
        private double cardWidth = 105.0;
        /** 封面静态边长 = cardWidth - 5。 */
        private double coverSize = 100.0;
        /** 每次几何变化 +1：卡片据此判断要不要重挂宽度、重算折行。 */
        private long revision;
        /** 上一次的卡片宽：用来算这一步变化了多大幅度（见 {@link #lastStepDelta}）。 */
        private double lastCardWidth = 105.0;
        /**
         * 最近一次几何变化中卡片宽改变了多少。
         *
         * <p>这个值决定要不要给卡片加重排补间：面板连续缩放时每一步都很小（内置缩放是
         * 每帧逼近目标，一步常常不到 1L），此时补间会和面板自身的平滑运动叠加成抽搐；
         * 而窗口 resize 是一步到位的大跳变，补间才有意义。</p>
         */
        private double lastStepDelta;
        /** 上一次的列数：跨列（6 列变 5 列）会让整行卡片换行，是必须补一次的拐点。 */
        private int lastColumns = -1;
        /** 这一次几何变化是否跨过了列数边界。 */
        private boolean columnsChanged;

        /**
         * 用面板宽度刷新几何。O(1)；宽度没变时连列数除法都不做。
         *
         * @return 几何是否发生变化
         */
        private boolean update(double panelWidth) {
            // 首帧之前面板宽还是 0：保留上一份几何，别把卡片排成一根 1px 的柱子。
            if (panelWidth <= 0.0) {
                return false;
            }
            final double viewport = Math.max(1.0, panelWidth - PAGE_MARGIN * 2.0);
            if (viewport == this.viewportWidth) {
                return false;
            }
            this.viewportWidth = viewport;

            // 密度公式沿用旧版：一列按 117L（105 卡片 + 12 间距）折算。
            final int columns = Math.max(1,
                    (int) Math.floor((viewport + GRID_GAP) / GRID_COLUMN_PITCH));
            // 以 1/64 L 为单位做整数分配：保证 cols*cardW + (cols-1)*gap ≤ 视口宽，
            // 且 ScrollPanel 里的累加是精确的 —— 浮点残差会把每行最后一格挤到下一行。
            final long viewportUnits = (long) Math.floor(viewport * GRID_QUANTUM);
            final long gapUnits = Math.round(GRID_GAP * GRID_QUANTUM);
            final long cardUnits = Math.max(1L, (viewportUnits - (columns - 1L) * gapUnits) / columns);
            final double cardWidth = Math.max(1.0, (double) cardUnits / GRID_QUANTUM);
            final double coverSize = Math.max(1.0, cardWidth - COVER_HOVER_EXPANSION);

            this.viewportWidth = viewport;
            // 宽度只有零点几个 L 的变化常常量化到同一个卡片宽：这时不要 bump revision，
            // 否则拖动窗口的每一帧都会让所有可见卡片重算一次折行。
            if (cardWidth == this.cardWidth && coverSize == this.coverSize) {
                return false;
            }
            this.cardWidth = cardWidth;
            this.coverSize = coverSize;
            // 记下这一次几何变化相对上一次的幅度。调用方据此区分两种完全不同的情况：
            //   · 幅度很小 → 面板正在连续缩放（内置「播放器大小」每帧逼近目标，十几帧才停），
            //     这时卡片必须<b>直接跟随</b>，任何补间都会和面板自身的平滑运动打架，表现就是抽搐；
            //   · 幅度很大 → 真正的离散跳变（窗口 resize 一次到位），这时才值得补间。
            this.lastStepDelta = Math.abs(cardWidth - this.lastCardWidth);
            this.lastCardWidth = cardWidth;
            // 列数变化是网格重排的本质拐点：整行卡片会换行。哪怕这一步的宽度变化很小
            // （连续缩放刚好跨过 117L 的整数倍），也值得补一次，否则会看到一行卡片突然跳位。
            this.columnsChanged = columns != this.lastColumns;
            this.lastColumns = columns;
            this.revision++;
            return true;
        }
    }

    private static class PlaylistWidget extends AbstractWidget<PlaylistWidget> {

        /** 网易云首页网格中两条标题行之间的额外空隙。 */
        private static final double NETEASE_TITLE_LINE_GAP = 6.0;
        private static final double DEFAULT_TITLE_LINE_GAP = 4.0;
        /** 封面静态内缩：四周各 2.5L，正好是 hover 外扩量（5L）的一半。 */
        private static final double COVER_INSET = COVER_HOVER_EXPANSION * .5;
        private static final double COVER_TITLE_SPACING = 4.0;
        private static final double COVER_RADIUS = 4.0;
        /** 标题超过两行时第二行的收尾省略号。 */
        private static final String TITLE_ELLIPSIS = "\u2026";

        @Getter
        private final PlayList playList;
        private final Runnable customAction;
        /** 共享的网格几何（HomePanel 每帧 O(1) 刷新，列宽/封面边长只有一份）。 */
        private final GridMetrics grid;
        /** 共享的重排补间（与 {@link #grid} 同一模式：静态嵌套类拿不到外部实例，只能显式传）。 */
        private final GridReshape reshape;
        /** 网格所在的滚动容器：卡高变化时据此让布局签名失效（见 applyGridGeometry）。 */
        private final ScrollPanel container;
        private final CFontRenderer titleFont;
        private final double titleLineGap;
        private final PlaylistTitleWidget titleWidget;
        /** 安全化后的标题，构建期算一次；渲染热路径不再 trim / 判空。 */
        private final String titleText;
        /** 上次套用的网格几何 revision：没变就完全跳过尺寸与折行重算。 */
        private long appliedGridRevision = -1L;
        /** 上次套用时的字体度量代次：换字体/热重建后标题 y 与折行要跟着重挂。 */
        private long appliedMetrics = -1L;
        /** 本卡片当前的列宽与封面边长（共享几何的本地快照）。 */
        private double cardWidth = 105.0;
        private double coverSize = 100.0;
        /**
         * 上一次排版时卡片左上角在网格里的位置（相对父级）。重排补间用它算「从哪儿滑过来」。
         * 用 {@code Double.NaN} 表示还没排过版 —— 那时不该有任何偏移。
         */
        private double lastLayoutX = Double.NaN;
        private double lastLayoutY = Double.NaN;
        /** 本帧要补的重排位移（旧位置 - 新位置）；补间推进时按 (1-eased) 缩小到 0。 */
        private double reshapeDx;
        private double reshapeDy;
        /** 这份位移取自哪一代几何：几何又变了就作废重算（见 {@link GridReshape#generation}）。 */
        private long reshapeGeneration = -1L;
        /** 折行缓存：只有 cover 宽度、字形就绪状态或字体度量代次变了才重算（文本每张卡固定）。 */
        private String[] titleLines;
        private double titleLinesWidth = -1.0;
        private boolean titleLinesGlyphsReady;
        private long titleLinesMetrics = -1L;
        /** 标题当前主题色；每帧只比 int，变主题时才 setColor —— 避免每帧 new Color。 */
        private int titleColor = Integer.MIN_VALUE;

        /** 卡片封面。接力起飞那一帧要问它现在真正落在哪儿。 */
        private RoundedImageWidget coverWidget;

        double emphasizeAnim = 0;

        boolean coverLoaded = false;
        /**
         * 封面加载的重试节拍器。初值取临界值减一：第一帧 ++ 后即达 20，首次加载当帧发起；
         * 之后每 20 帧重试一次直到 loadCover 返回 true。
         */
        int coverRetryFrame = 19;

        /** 与曲目行同一套进场：淡入 + 从下方落进来 + 微微放大。 */
        private final PanelItemEntrance entrance = new PanelItemEntrance();
        private double entranceEased = 1.0;
        /** 第一次被渲染（= 刚滚进网格视口）时向它领一个错峰号。 */
        private PanelItemEntrance.RevealQueue entranceQueue;
        /** 矩阵变换当前是否已挂上（进场与重排共用同一个槽位，见 {@link #syncTransformations()}）。 */
        private boolean transformationsAttached;

        /** 由 {@code HomePanel} 在建网格时挂上：号是卡片第一次被渲染那一帧领的，不是现在发好的。 */
        void setEntranceAnimation(PanelItemEntrance.RevealQueue queue) {
            this.entranceQueue = queue;
            this.entranceEased = 0.0;
            this.setAlpha(0f);
            // 变换不在建卡时就挂：第一帧真正渲染前由 syncTransformations 统一决定。
            // 这里只把状态摆好，避免「挂上了但几何还没算」那一帧用错中心点。
            this.transformationsAttached = false;
            this.setTransformations(null);
        }

        public PlaylistWidget(PlayList playList, Runnable customAction, GridMetrics grid,
                              GridReshape reshape, ScrollPanel container) {
            this.playList = playList;
            this.customAction = customAction;
            this.grid = grid;
            this.reshape = reshape;
            this.container = container;
            this.titleText = safePlaylistTitle(playList.getName());
            this.titleFont = FontManager.pf14bold;
            this.titleLineGap = playList.getPlatform() == MusicPlatform.NETEASE
                    ? NETEASE_TITLE_LINE_GAP : DEFAULT_TITLE_LINE_GAP;

            // 先用占位的一行文本把标题控件建出来：真正的两行折行在构建末尾的
            // applyGridGeometry 里按当帧封面宽度做一次，之后由缓存键守住，滚动时一帧都不重量。
            this.titleWidget = new PlaylistTitleWidget(new String[]{this.titleText},
                    this.titleFont, this.titleLineGap, this.grid.coverSize);

            // 封面先用 1×1 建出来；真实几何在构建末尾落一次，之后只在网格列宽变化时重挂。
            RoundedImageWidget cover = new RoundedImageWidget(this::getCoverLocation, 0, 0, 1.0, 1.0);

            this.addChild(cover);
            this.coverWidget = cover;

            // 接力飞行期间这张封面让位：把纹理供给换成 null，而不是 setHidden —— 后者会让
            // renderWidget 直接 return，控件自己的回调再也跑不到，也就没人把它取消隐藏。
            cover.setLocImg(() -> PlaylistCoverHero.coversYield(this) ? null : this.getCoverLocation());

            cover
                    .setClickable(false)
                    .fadeIn()
                    .setLinearFilter(true)
                    .setBeforeRenderCallback(() -> {

                        if (PlaylistCoverHero.consumeHandoff(this)) {
                            cover.setAlpha(1.0f);
                        }
                        // 封面加载带重试：首次当帧立即发起（计数器初值就是临界值），
                        // 之后 URL 异步就绪或上次下载失败时，每隔约 1/3 秒再问一次。
                        // 节拍而不是每帧——loadCover 未就绪时虽是廉价判断，重试语义不该
                        // 依赖渲染帧率；首次不节拍——冷下载晚 1/3 秒是肉眼可感的空白。
                        if (!coverLoaded && ++coverRetryFrame >= 20) {
                            coverRetryFrame = 0;
                            coverLoaded = this.loadCover();
                        }

                        // 悬停外扩的逼近：用比例逼近（跟手），但收敛后必须精确归位。
                        // 原来永远差一点点、每帧都在动，封面 bounds 于是每帧重设 ——
                        // 缩放窗口时这层微动会和重排叠在一起，看起来就是抖。
                        final double emphasizeTarget = cover.isHovering() ? COVER_HOVER_EXPANSION : 0.0;
                        this.emphasizeAnim = Interpolations.interpolate(this.emphasizeAnim,
                                emphasizeTarget, .2f);
                        if (Math.abs(this.emphasizeAnim - emphasizeTarget) < .01) {
                            this.emphasizeAnim = emphasizeTarget;
                        }

                        // 悬停只让封面在卡片里胀满：x/y 与尺寸都由「卡片宽 - 当前封面边」推出来。
                        // 标题位置是网格几何算好的常量，不会跟着这张封面的动画斜移。
                        final double size = this.coverSize + this.emphasizeAnim;
                        final double inset = (this.cardWidth - size) * .5;
                        // 尺寸真的变了才重设 bounds：静止的封面不该每帧重录一次圆角裁剪。
                        if (cover.getWidth() != size || cover.getX() != inset) {
                            cover.setBounds(inset, inset, size, size).setRadius(COVER_RADIUS);
                        }

                        // 回程的落点：从歌单页按返回时，飞回来的那张封面要落在同一首歌单的这张卡片上。
                        // 视口就是本卡片的父级（网格那块 ScrollPanel），卡片被滚出去一半时这里不认领，
                        // 接力会在第一帧自己收手 —— 落点得是屏幕上真实存在的那块矩形，不然飞回来的
                        // 封面会画到列表视口之外的页眉带上。
                        PlaylistCoverHero.setTarget(this, this.playList, cover.getX(), cover.getY(),
                                cover.getWidth(), cover.getRadius(), getParent());
                    });

            PlaylistTitleWidget lblName = this.titleWidget;
            this.addChild(lblName);
            lblName.setClickable(false);
            // 位置/宽度只在网格几何变化时改（applyGridGeometry）；这里每帧只同步主题色，
            // 且仅在颜色真的变了时才 setColor —— 否则每帧都会 new 一个 Color。
            lblName.setBeforeRenderCallback(() -> {
                final int color = NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT);
                if (this.titleColor != color) {
                    this.titleColor = color;
                    lblName.setColor(color);
                }
            });

            this.setOnClickCallback((relativeX, relativeY, mouseButton) -> {

                if (mouseButton == 0) {
                    if (customAction != null) customAction.run();
                    else {
                        // 接力要赶在换页之前交起点：那一刻卡片还画在它自己的位置上，矩形才是准的。
                        // 落点也在这里点名 —— 面板已经构造出来了，只有它能把这张封面接走，
                        // 上一页（同样是卡片在回报）抢不走。
                        // 视口交给接力自己判：卡片被网格滚出视口一半时，这张封面的绝对矩形已经在
                        // 列表上方那条页眉带里了，照它起飞就是一条盖住页眉的巨型封面。
                        // 起点矩形一律读封面控件当帧的 bounds（悬停时它已经胀大过），不能拿常量。
                        PlaylistPanel panel = new PlaylistPanel(playList);
                        PlaylistCoverHero.begin(this, panel, playList, this.getCoverLocation(),
                                cover.getX(), cover.getY(), cover.getWidth(), cover.getRadius(),
                                getParent());
                        NCMScreen.getInstance().setCurrentPanel(panel);
                    }
                }

                return true;
            });

            // 首帧渲染前就把卡片宽度/标题位置/折行落到共享几何上：网格第一次排版就是对的。
            // 构建期 container 已可用（构造器传入），此时让卡高失效也无害——容器还没排过版。
            this.applyGridGeometry(this.container);
        }


        @Override
        public void onRender(double mouseX, double mouseY) {
            if (!entrance.isStarted()) {
                // 第一次被渲染 = 刚被网格让进视口。号在这里领，往下滚露出来的卡片才有进场。
                if (this.entranceQueue != null) {
                    entrance.begin(System.currentTimeMillis(), this.entranceQueue.nextDelayMillis());
                }
            }
            if (!entrance.isFinished()) {
                this.entranceEased = entrance.tick();
                this.setAlpha((float) this.entranceEased);
            }

            // 列宽/封面边长只在共享几何 revision 变化时重挂；折行缓存的键含这个宽度与
            // 字形就绪状态，滚动、悬停期间这里只是一次 long 比较加两次 double 比较。
            // 容器传给 applyGridGeometry 供卡高变化时失效布局签名（正常路径是一帧一次 null 判断）。
            this.applyGridGeometry(this.container);
            this.updateTitleLines(this.coverSize);

            // 进场与重排共用同一个矩阵通道：任一条还在动就挂上合成变换，两条都停了才摘掉。
            // 摘掉之后停住的卡片每帧只付一次 null 判断，不再有 pushMatrix。
            this.syncTransformations();

            // 封面底板：图还没到时先给一块 ELEMENT_BACKGROUND 圆角占位，避免那个位置是个黑洞；
            // 图到了自然被封面完全盖住。直接画在子控件之前（=封面下面），不新增控件树，
            // 也不参与命中测试。
            this.roundedRect(this.getX() + COVER_INSET, this.getY() + COVER_INSET,
                    this.coverSize, this.coverSize, COVER_RADIUS,
                    reAlpha(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND), this.getAlpha()));

            // 记下本帧的真实排版位置，供下一帧判「有没有被重排」。
            // 重排补间进行中<b>不更新</b>：补间的基准必须固定成「换代那一刻的位置」，
            // 若每帧都刷新，差值会恒等于零、动画失效，而且滚动混进来时会把卡片拖偏。
            if (this.reshape.frameEased() >= 1.0) {
                this.lastLayoutX = this.getX();
                this.lastLayoutY = this.getY();
            }
        }

        /**
         * 合成这一帧的矩阵变换：先补重排位移，再套进场（位移+缩放）。
         *
         * <p>为什么必须合成而不能各挂各的：{@code AbstractWidget} 只有一个
         * {@code transformations} 槽位，后挂的会覆盖先挂的。进场动画原来在这里独占它，
         * 重排如果直接抢走，卡片就会在缩放窗口时丢掉「落进来」那一段。</p>
         *
         * <p>重排位移读的是<b>父级共享时钟</b>（{@link GridReshape}），所以整屏卡片同一条进度，
         * 不会各走各的；偏移量由「上一次排版位置 - 当前位置」算出来，补间走完自然归零。</p>
         */
        private void syncTransformations() {
            // 只有在「几何换代」时才取差值：滚动同样会让 getX()/getY() 每帧变化，
            // 若按「位置变了就补」来判断，滚动会被误当成重排，卡片会一路拖着残影走。
            // 代次由 HomePanel 在列宽/封面边长真的变化时递增（见 GridReshape#restart）。
            if (this.reshapeGeneration != this.reshape.generation) {
                if (!Double.isNaN(this.lastLayoutX)) {
                    // 这一帧的新位置已经由 ScrollPanel 排好，旧位置是上一帧记下的。
                    // 差值必须在补间推进之前定格，否则推进一次差值就变小了。
                    // 定格后整个补间期间不再重算：重算会让差值恒等于「上一帧 - 当前帧」≈ 0，
                    // 动画直接失效。
                    this.reshapeDx = this.lastLayoutX - this.getX();
                    this.reshapeDy = this.lastLayoutY - this.getY();
                } else {
                    // 这张卡还没排过版（刚构建）：没有「从哪儿来」，不该有偏移。
                    this.reshapeDx = 0.0;
                    this.reshapeDy = 0.0;
                }
                this.reshapeGeneration = this.reshape.generation;
            }
            final boolean reshaping = this.reshape.frameEased() < 1.0
                    && (this.reshapeDx != 0.0 || this.reshapeDy != 0.0);
            final boolean entering = !entrance.isFinished();
            if (!reshaping && !entering) {
                if (this.transformationsAttached) {
                    this.setTransformations(null);
                    this.transformationsAttached = false;
                }
                return;
            }
            if (!this.transformationsAttached) {
                this.setTransformations(this::applyCompositeTransform);
                this.transformationsAttached = true;
            }
        }

        /**
         * 矩阵合成：重排位移（旧位置 → 新位置）+ 进场（下落 + 轻微放大）。
         *
         * <p>重排补的是「还在路上的那部分距离」：{@code (旧 - 新) * (1 - eased)}。
         * 缓动到 1 时它变成 0，卡片正好落在网格排好的位置上。</p>
         */
        private void applyCompositeTransform() {
            // 只读父级本帧推进好的值：每张卡都 tick 一次会把共享时钟推进几十遍。
            final double eased = this.reshape.frameEased();
            if (eased < 1.0) {
                final double dx = this.reshapeDx * (1.0 - eased);
                final double dy = this.reshapeDy * (1.0 - eased);
                if (dx != 0.0 || dy != 0.0) {
                    api.getGLStateManager().translate(dx, dy, 0.0);
                }
            }
            if (this.entranceEased < 1.0) {
                PanelItemEntrance.applyTransform(this.entranceEased,
                        this.getX() + this.getWidth() * .5, this.getY() + this.getHeight() * .5);
            }
        }

        private void finishEntranceAnimation() {
            this.entrance.finishImmediately();
            this.entranceEased = 1.0;
            this.setAlpha(1f);
            // 重排偏移也一并收掉：这一帧之后卡片不再需要任何矩阵。
            this.lastLayoutX = this.getX();
            this.lastLayoutY = this.getY();
            this.setTransformations(null);
            this.transformationsAttached = false;
        }

        @Override
        public void onViewportExit() {
            if (!this.entrance.isFinished()) {
                this.finishEntranceAnimation();
            }
        }

        /** Finish the card before its parent page is dropped by an interrupted navigation. */
        @Override
        public void finishTransientAnimations() {
            this.finishEntranceAnimation();
            this.entranceQueue = null;
            super.finishTransientAnimations();
        }

        /**
         * 套用共享网格几何。
         *
         * <p>两类变化的感知路径不同，<b>都不能早退</b>：</p>
         * <ul>
         *   <li><b>列宽/封面边长</b>（{@code grid.revision}）：只在列宽变化那几帧重挂，
         *       平时是一次 long 比较；</li>
         *   <li><b>字体度量</b>（{@code metricsRevision}）：换字体/渲染层热重建会改行进与
         *       字宽，但不改列宽、不 bump revision。标题 y、折行、卡高全部派生自度量，
         *       必须跟着它走 —— 否则换字体后标题停在旧行进的位置、折行用旧字宽，
         *       文字溢出压到封面与相邻卡片上（截图里「文字叠在封面下缘」正是它）。</li>
         * </ul>
         *
         * <p>度量变化的自愈是一帧的事：metricsRevision 变 → 折行重算 + 卡高变化 → 容器
         * 签名失效 → 下一帧容器重排。之后每帧只付「两次 revision 比较 + 一次高度比较」。</p>
         *
         * @param container 网格所在的 ScrollPanel，卡高变化时让它的布局签名失效
         */
        private void applyGridGeometry(final ScrollPanel container) {
            final boolean metricsChanged = this.appliedMetrics != this.titleFont.metricsRevision();
            final boolean gridChanged = this.appliedGridRevision != this.grid.revision;

            if (gridChanged) {
                this.appliedGridRevision = this.grid.revision;
                this.cardWidth = this.grid.cardWidth;
                this.coverSize = this.grid.coverSize;
                // 封面静态几何也落一次：首帧在网格视口里排版前就有正确宽度（悬停动画随后覆盖它）。
                this.coverWidget.setBounds(COVER_INSET, COVER_INSET, this.coverSize, this.coverSize)
                        .setRadius(COVER_RADIUS);
            }

            // titleY/newHeight 必须在 coverSize 快照刷新<b>之后</b>计算：字段初值 100 与
            // 真实列宽（100~123 视视口而定）差的那十几像素恰好是一个行进 —— 先算后刷的话，
            // 标题被 setPosition 到旧快照的位置、压进封面下缘，且此后不再重挂，错误永久冻结。
            final double titleY = COVER_INSET + this.coverSize + COVER_TITLE_SPACING;
            final double newHeight = titleY + this.titleWidget.getBlockHeight();
            final boolean heightChanged = Math.abs(this.getHeight() - newHeight) > .01;

            if (gridChanged || metricsChanged) {
                this.titleWidget.setPosition(COVER_INSET, titleY);
                this.updateTitleLines(this.coverSize);
            }

            this.setBounds(this.cardWidth, newHeight);
            if ((heightChanged || metricsChanged) && container != null) {
                container.invalidateLayout();
            }
            if (metricsChanged) {
                this.appliedMetrics = this.titleFont.metricsRevision();
            }
        }

        /** HomePanel 在列宽变化的那几帧统一调用（时序在容器摆位之前，见 refreshGridLayout）。 */
        void applyGridGeometryNow() {
            this.applyGridGeometry(this.container);
        }

        /**
         * 标题折行结果缓存：只有 cover 宽度、字形就绪状态或字体度量代次变化时才重算。
         *
         * <p>文本每张卡固定，构建期已经安全化过一次，这里既不 trim 也不判空；
         * 命中缓存时一帧一分钱都不花，滚动与 hover 都不会触发文本测量。</p>
         */
        private void updateTitleLines(double coverWidth) {
            final boolean glyphsReady = titleFont.areGlyphsLoaded(titleText);
            // 缓存键用「量化后的宽度」而不是精确值：内置「播放器大小」缩放时面板每帧都在
            // 平滑逼近目标，封面宽每帧都可能差零点几 L。若按精确值比，缓存每帧失效，
            // 于是每张卡每帧都重新 fitWidth + 贪心删字符 —— 240 张卡就是每帧几百次文本测量，
            // 这正是缩放时卡顿的主因。
            //
            // 量化到 1L：折行结果在 1L 之内的差别肉眼不可见（一行少一个字的边界情况除外，
            // 那种情况本来也会在真正跨过边界时重算），但缓存命中率从「每帧失效」变成「稳定」。
            // 度量代次（用户换字体时递增）必须在键里：宽度没变、度量变了，旧折行就是错的。
            final long metrics = titleFont.metricsRevision();
            final double quantized = Math.floor(coverWidth);
            if (this.titleLines != null && this.titleLinesWidth == quantized
                    && this.titleLinesGlyphsReady == glyphsReady
                    && this.titleLinesMetrics == metrics) {
                // 命中缓存：连 setLines 都不调 —— 它内部会 clone 数组并重设 bounds，
                // 240 张卡每帧各来一次就是白白的 240 次分配。
                // 但宽度确实变了（量化后仍在同一档）：只更新排版宽度，不重算折行。
                if (this.titleWidget.getMaxWidth() != Math.max(1.0, coverWidth)) {
                    this.titleWidget.setMaxWidth(coverWidth);
                }
                return;
            }
            this.titleLines = wrapTwoLineTitle(titleFont, titleText, coverWidth);
            this.titleLinesWidth = quantized;
            this.titleLinesGlyphsReady = glyphsReady;
            this.titleLinesMetrics = metrics;
            this.titleWidget.setLines(this.titleLines, coverWidth);
        }

        private static String safePlaylistTitle(String title) {
            String safe = title == null ? "" : title.trim();
            return safe.isEmpty() ? "未命名歌单" : safe;
        }

        /**
         * 标题最多两行：先按封面宽度自然折行；超过两行时取前两行，第二行用「…」收尾
         * （贪心删字符，直到「前缀 + …」放得下）。
         *
         * <p>只在 {@link #updateTitleLines} 的缓存失效时调用，渲染热路径里不会执行。</p>
         */
        private static String[] wrapTwoLineTitle(CFontRenderer font, String title, double width) {
            if (width <= 1.0) {
                return new String[]{""};
            }
            final String[] fitted = font.fitWidth(title, width);
            if (fitted == null || fitted.length == 0) {
                return new String[]{""};
            }
            if (fitted.length <= 2) {
                return fitted;
            }

            String second = fitted[1];
            while (!second.isEmpty() && font.getStringWidthD(second + TITLE_ELLIPSIS) > width) {
                second = second.substring(0, second.length() - 1);
            }
            // 贪心删字符可能停在空格上，省略号前不该留它。
            while (!second.isEmpty() && second.charAt(second.length() - 1) == ' ') {
                second = second.substring(0, second.length() - 1);
            }
            if (second.isEmpty()) {
                // 极窄窗口连「…」都放不下：宁可只留第一行，也不画出超宽文本。
                return new String[]{fitted[0]};
            }
            return new String[]{fitted[0], second + TITLE_ELLIPSIS};
        }
        /**
         * 封面加载：纹理已在内存、或下载已受理（入队）= true；URL 还没就绪 = false。
         *
         * <p>与曲目行（{@code MusicWidget#loadCover}）同一口径 —— 覆盖的是「URL 异步就绪晚」
         * 这一段：返回 false 的卡下轮重试。注意「已受理」只代表进了下载闸门，HTTP 失败后
         * 闸门虽会释放（Textures.PENDING_DOWNLOADS 移除），但本卡的 coverLoaded 已经为 true、
         * 不会再发 —— 这一层与曲目行完全一致，若将来要真正的失败重试，需要 Textures 暴露
         * 失败回调来清 coverLoaded。</p>
         */
        private boolean loadCover() {

            TextureManager textureManager = TextureManager.getInstance();
            Location coverLoc = this.getCoverLocation();
            if (textureManager.getTexture(coverLoc) != null)
                return true;

            String coverUrl = playList.getCoverUrl();
            if (coverUrl == null || coverUrl.trim().isEmpty()) return false;
            String secureUrl = coverUrl.replace("http://", "https://");
            String requestUrl = playList.getPlatform() == MusicPlatform.QQ || playList.getPlatform() == MusicPlatform.QISHUI || playList.isSearchMode()
                    ? secureUrl
                    : secureUrl + "?param=256y256";
            // 入队（下载闸门在 Textures.PENDING_DOWNLOADS 里去重）。已经在下/已下载的
            // 第二次调用会在闸门处被吞掉，按「已受理」返回 true。
            Textures.downloadTextureAndLoadAsync(requestUrl, coverLoc);
            return true;

        }

        private Location getCoverLocation() {
            return this.playList.getCoverLocation();
        }

    }

    /**
     * 显式绘制每条歌单标题行，使网易云卡片能用略宽松的
     * 行前进，而不改变全局字体渲染器。
     */
    private static final class PlaylistTitleWidget extends AbstractWidget<PlaylistTitleWidget> {
        /** 标题块固定按两行预留高度：卡片高度不随标题长短变化，栅格行高才统一。 */
        private static final int BLOCK_LINES = 2;

        private String[] lines;
        private final CFontRenderer font;
        private final double lineGap;
        private double maxWidth;

        private PlaylistTitleWidget(String[] lines, CFontRenderer font, double lineGap, double maxWidth) {
            this.font = font;
            this.lineGap = lineGap;
            setLines(lines, maxWidth);
        }

        /** 行数组与标题宽一起换；高度恒为两行，短标题不会把卡片撑矮。 */
        private void setLines(String[] lines, double maxWidth) {
            this.lines = lines == null || lines.length == 0 ? new String[]{""} : lines.clone();
            this.maxWidth = Math.max(1.0, maxWidth);
            setBounds(this.maxWidth, getBlockHeight());
        }

        /** 当前排版宽度。 */
        private double getMaxWidth() {
            return this.maxWidth;
        }

        /**
         * 只更新排版宽度，不动行数组。
         *
         * <p>连续缩放时折行结果没变（量化后仍同一档），但卡片宽确实变了：
         * 这时只需要把 bounds 跟上，不必重新 clone 行数组、更不必重新测量文本。</p>
         */
        private void setMaxWidth(double maxWidth) {
            this.maxWidth = Math.max(1.0, maxWidth);
            setBounds(this.maxWidth, getBlockHeight());
        }

        /** 两行标题块的高度（固定值，与当前实际画几行无关）。 */
        private double getBlockHeight() {
            return lineAdvance(font, lineGap) * BLOCK_LINES;
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            // 行高、宽度、行数组都在 setLines 里定死；这里只画，不再每帧重设 bounds。
            final double advance = lineAdvance(font, lineGap);
            for (int index = 0; index < lines.length; index++) {
                font.drawString(lines[index], getX(), getY() + advance * index, getHexColor());
            }
        }

        private static double lineAdvance(CFontRenderer font, double lineGap) {
            // 在首次字形上传前，CFontRenderer 报告负高度。
            // 配置的字号在测量就绪前是安全预留。
            double glyphHeight = Math.max(font.sizePx + 2.0, font.getFontHeight());
            return glyphHeight + Math.max(0.0, lineGap);
        }
    }

}
