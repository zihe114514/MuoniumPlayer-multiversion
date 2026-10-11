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
        final int margin = 12;

        LabelWidget lblWelcome = new LabelWidget(welcomeText, FontManager.pf25bold);
        lblWelcome.setClickable(false);
        lblWelcome.setBeforeRenderCallback(() -> lblWelcome
                .setPosition(margin, margin)
                .setMaxWidth(Math.max(1.0, this.getWidth() - margin * 2.0))
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH)
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT)));
        this.addChild(lblWelcome);

        PanelLoadingWidget indicator = new PanelLoadingWidget(message);
        indicator.setSpinning(spinning);
        indicator.setClickable(false);
        indicator.setBeforeRenderCallback(() -> indicator.setBounds(margin, 52.0,
                Math.max(1.0, this.getWidth() - margin * 2.0),
                Math.max(1.0, this.getHeight() - 52.0 - 30.0)));
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

    private void layout(List<PlayList> displayedPlayLists, String recommendationsText) {
        this.getChildren().clear();
        final String welcomeText = welcomeTextFor(platform);
        final int margin = 12;

        // 字体图集在首次绘制前报告负高度。从第一帧起保持稳定的
        //头部预留，使响应式卡片网格永远不会盖住文字
        // ——即便播放器正在打开或在 100/90/80/70 之间切换。
        final double welcomeHeight = Math.max(12.0, FontManager.pf25bold.getStringHeight(welcomeText));
        final double recommendationsHeight = Math.max(7.0, FontManager.pf14bold.getStringHeight(recommendationsText));
        final double recommendationsY = margin + welcomeHeight + 2
                + margin * .5 - recommendationsHeight * .5;
        final double contentTop = Math.max(52.0, Math.ceil(recommendationsY + recommendationsHeight + margin * .75));
        // 账号身份已经挪到侧栏的账号行（那里本来就有头像和昵称），主页下方不再需要给它留位置。
        final double bottomPadding = margin;

        scrollPanel = new ScrollPanel();
        this.addChild(scrollPanel);

        scrollPanel
                .setSpacing(margin)
                .setScrollStrength(44)
                .setAlignment(ScrollPanel.Alignment.VERTICAL_WITH_HORIZONTAL_FILL)
                .setBeforeRenderCallback(() -> scrollPanel.setBounds(
                        margin,
                        contentTop,
                        Math.max(1.0, this.getWidth() - margin * 2.0),
                        Math.max(1.0, this.getHeight() - contentTop - bottomPadding)
                ));

        LabelWidget lblWelcome = new LabelWidget(welcomeText, FontManager.pf25bold);
        lblWelcome.setBeforeRenderCallback(() -> lblWelcome
                .setPosition(margin, margin)
                .setMaxWidth(Math.max(1.0, this.getWidth() - margin * 2.0))
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH)
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT)));
        this.addChild(lblWelcome);

        LabelWidget lblRecommendations = new LabelWidget(recommendationsText, FontManager.pf14bold);
        lblRecommendations.setBeforeRenderCallback(() -> lblRecommendations
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                .setMaxWidth(Math.max(1.0, this.getWidth() - margin * 2.0))
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH)
                .setPosition(margin, recommendationsY));
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
        buildPendingCards();

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
                PlaylistWidget card = new PlaylistWidget(playList, this.pendingCardActions.get(playList.getId()));
                card.setShouldOverrideMouseCursor(true);
                card.setEntranceAnimation(this.revealQueue);
                batch.add(card);
            }
            this.cardsPanel.addChild(batch);
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
        // 先把这一批卡片补上，再发号：本帧新挂的卡片会在同一次渲染里被遍历到，
        // 于是它们第一次被渲染就是这一帧，入场动画从这一帧起算。
        buildPendingCards();
        this.revealQueue.beginFrame();
        this.revealQueue.setAnimationsSuppressed(this.scrollPanel != null && this.scrollPanel.isScrollActive());
        super.onRender(mouseX, mouseY);
    }

    private static class PlaylistWidget extends AbstractWidget<PlaylistWidget> {

        /** 网易云首页网格中两条标题行之间的额外空隙。 */
        private static final double NETEASE_TITLE_LINE_GAP = 6.0;
        private static final double DEFAULT_TITLE_LINE_GAP = 4.0;
        private static final double COVER_SIZE = 100.0;
        private static final double COVER_HOVER_EXPANSION = 5.0;
        private static final double COVER_TITLE_SPACING = 4.0;

        @Getter
        private final PlayList playList;
        private final Runnable customAction;
        private final CFontRenderer titleFont;
        private final double titleLineGap;
        private final PlaylistTitleWidget titleWidget;
        /** 字形图集就绪前用一行占位；真正折行只计算一次，避免滚动时每帧 fitWidth。 */
        private boolean titleGlyphsReady;

        /** 卡片封面。接力起飞那一帧要问它现在真正落在哪儿。 */
        private RoundedImageWidget coverWidget;

        double emphasizeAnim = 0;

        boolean coverLoaded = false;

        /** 与曲目行同一套进场：淡入 + 从下方落进来 + 微微放大。 */
        private final PanelItemEntrance entrance = new PanelItemEntrance();
        private double entranceEased = 1.0;
        /** 第一次被渲染（= 刚滚进网格视口）时向它领一个错峰号。 */
        private PanelItemEntrance.RevealQueue entranceQueue;

        /** 由 {@code HomePanel} 在建网格时挂上：号是卡片第一次被渲染那一帧领的，不是现在发好的。 */
        void setEntranceAnimation(PanelItemEntrance.RevealQueue queue) {
            this.entranceQueue = queue;
            this.entranceEased = 0.0;
            this.setAlpha(0f);
            this.setTransformations(() -> PanelItemEntrance.applyTransform(this.entranceEased,
                    this.getX() + this.getWidth() * .5, this.getY() + this.getHeight() * .5));
        }

        public PlaylistWidget(PlayList playList) {
            this(playList, null);
        }

        public PlaylistWidget(PlayList playList, Runnable customAction) {
            this.playList = playList;
            this.customAction = customAction;

            double size = COVER_SIZE;
            double emphasizeAnimMax = COVER_HOVER_EXPANSION;
            this.titleFont = FontManager.pf14bold;
            boolean neteaseTitle = playList.getPlatform() == MusicPlatform.NETEASE;
            this.titleLineGap = neteaseTitle ? NETEASE_TITLE_LINE_GAP : DEFAULT_TITLE_LINE_GAP;

            // 字形宽度由 CFontRenderer 惰性生成。先从一行
            //加载文字开始，再在其字形图集测量过本标题后排版。
            this.titleWidget = new PlaylistTitleWidget(new String[]{safePlaylistTitle(playList.getName())},
                    titleFont, titleLineGap, size);
            updateTitleLayout(false);

            RoundedImageWidget cover = new RoundedImageWidget(this::getCoverLocation, 0, 0, size, size);

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
                        if (!coverLoaded) {
                            coverLoaded = true;
                            this.loadCover();
                        }

                        this.emphasizeAnim = Interpolations.interpolate(this.emphasizeAnim, cover.isHovering() ? emphasizeAnimMax : 0, .2f);

                        cover
                                .setBounds(size + this.emphasizeAnim)
                                .setRadius(4)
                                .centerHorizontally()
                                .setBounds(cover.getRelativeX(), this.getWidth() * .5 - size * .5 - emphasizeAnim * .5, cover.getWidth(), cover.getHeight());

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
            lblName.setBeforeRenderCallback(() -> lblName
                    .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                    .setPosition(cover.getRelativeX(), cover.getRelativeY() + cover.getHeight() + COVER_TITLE_SPACING));

            this.setOnClickCallback((relativeX, relativeY, mouseButton) -> {

                if (mouseButton == 0) {
                    if (customAction != null) customAction.run();
                    else {
                        // 接力要赶在换页之前交起点：那一刻卡片还画在它自己的位置上，矩形才是准的。
                        // 落点也在这里点名 —— 面板已经构造出来了，只有它能把这张封面接走，
                        // 上一页（同样是卡片在回报）抢不走。
                        // 视口交给接力自己判：卡片被网格滚出视口一半时，这张封面的绝对矩形已经在
                        // 列表上方那条页眉带里了，照它起飞就是一条盖住页眉的巨型封面。
                        PlaylistPanel panel = new PlaylistPanel(playList);
                        PlaylistCoverHero.begin(this, panel, playList, this.getCoverLocation(),
                                cover.getX(), cover.getY(), cover.getWidth(), cover.getRadius(),
                                getParent());
                        NCMScreen.getInstance().setCurrentPanel(panel);
                    }
                }

                return true;
            });

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
                if (entrance.isFinished()) {
                    // 摘掉变换：停在原地的卡片不该继续每帧付一次 pushMatrix。
                    this.setTransformations(null);
                }
            }

            // 字形第一次就绪时才重新折行；之后滚动只画文本，不再重复分配字符串数组。
            boolean glyphsLoaded = titleFont.areGlyphsLoaded(safePlaylistTitle(playList.getName()));
            if (glyphsLoaded && !titleGlyphsReady) {
                titleGlyphsReady = true;
                updateTitleLayout(true);
            }
        }

        private void finishEntranceAnimation() {
            this.entrance.finishImmediately();
            this.entranceEased = 1.0;
            this.setAlpha(1f);
            this.setTransformations(null);
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

        private void updateTitleLayout(boolean canMeasureText) {
            if (canMeasureText) {
                titleWidget.setLines(wrapPlaylistTitle(titleFont, playList.getName(), COVER_SIZE));
            }
            this.setBounds(COVER_SIZE + COVER_HOVER_EXPANSION,
                    COVER_SIZE + COVER_HOVER_EXPANSION + COVER_TITLE_SPACING + titleWidget.getContentHeight());
        }

        private static String safePlaylistTitle(String title) {
            String safe = title == null ? "" : title.trim();
            return safe.isEmpty() ? "未命名歌单" : safe;
        }

        private static String[] wrapPlaylistTitle(CFontRenderer font, String title, double width) {
            String[] lines = font.fitWidth(safePlaylistTitle(title), width);
            return lines == null || lines.length == 0 ? new String[]{""} : lines;
        }
        private void loadCover() {

            TextureManager textureManager = TextureManager.getInstance();
            Location coverLoc = this.getCoverLocation();
            if (textureManager.getTexture(coverLoc) != null)
                return;

            String coverUrl = playList.getCoverUrl();
            if (coverUrl == null || coverUrl.trim().isEmpty()) return;
            String secureUrl = coverUrl.replace("http://", "https://");
            String requestUrl = playList.getPlatform() == MusicPlatform.QQ || playList.getPlatform() == MusicPlatform.QISHUI || playList.isSearchMode()
                    ? secureUrl
                    : secureUrl + "?param=256y256";
            Textures.downloadTextureAndLoadAsync(requestUrl, coverLoc);

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
        private String[] lines;
        private final CFontRenderer font;
        private final double lineGap;
        private final double maxWidth;

        private PlaylistTitleWidget(String[] lines, CFontRenderer font, double lineGap, double maxWidth) {
            this.font = font;
            this.lineGap = lineGap;
            this.maxWidth = Math.max(1.0, maxWidth);
            setLines(lines);
        }

        private void setLines(String[] lines) {
            this.lines = lines == null || lines.length == 0 ? new String[]{""} : lines.clone();
            setBounds(maxWidth, getContentHeight());
        }

        private double getContentHeight() {
            return heightOf(font, lines, lineGap);
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            double advance = lineAdvance(font, lineGap);
            for (int index = 0; index < lines.length; index++) {
                font.drawString(lines[index], getX(), getY() + advance * index, getHexColor());
            }
            // 让文本视口与封面足迹精确对齐。显式宽度
            //阻止说明文字改变水平网格间距。
            setBounds(maxWidth, getContentHeight());
        }

        private static double heightOf(CFontRenderer font, String[] lines, double lineGap) {
            int count = Math.max(1, lines == null ? 0 : lines.length);
            return lineAdvance(font, lineGap) * count;
        }

        private static double lineAdvance(CFontRenderer font, double lineGap) {
            // 在首次字形上传前，CFontRenderer 报告负高度。
            // 配置的字号在测量就绪前是安全预留。
            double glyphHeight = Math.max(font.sizePx + 2.0, font.getFontHeight());
            return glyphHeight + Math.max(0.0, lineGap);
        }
    }

}
