package com.muoniumplayer.core.screens.ncm.panels;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.Getter;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.NeteaseMusicService;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.KuGouMusicService;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
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
    private static final int QISHUI_HOME_TARGET = 120;
    private static volatile List<PlayList> qishuiHomePlaylists = Collections.emptyList();
    private static volatile boolean qishuiHomeLoading;

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
            layout(Collections.emptyList(), "哔哩哔哩 · 搜索、粘贴 BV号或视频链接");
        } else if (platform == MusicPlatform.QISHUI) {
            loadQishuiHome();
        } else {
            loadNeteaseHome();
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

        MultiThreadingUtil.runAsync(() -> {
            try {
                List<PlayList> result = QishuiMusicService.recommendPlaylists(QISHUI_HOME_TARGET);
                qishuiHomePlaylists = Collections.unmodifiableList(new ArrayList<>(result));
                final List<PlayList> displayed = new ArrayList<>(result);
                MultiThreadingUtil.runOnMainThread(() -> {
                    if (NeteaseMusicService.getCurrentPlatform() != platform) return;
                    if (!displayed.isEmpty()) {
                        layout(displayed, qishuiRecommendationTitle(displayed.size()));
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

        // 标题刻意加在被裁剪网格之后，因此它们始终在最上。
        displayedPlayLists.forEach(pl -> scrollPanel.addChild(new PlaylistWidget(pl, fixedPlaylistActions.get(pl.getId())).setShouldOverrideMouseCursor(true)));

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

        double emphasizeAnim = 0;

        boolean coverLoaded = false;

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

            cover
                    .setClickable(false)
                    .fadeIn()
                    .setLinearFilter(true)
                    .setBeforeRenderCallback(() -> {

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
                    else NCMScreen.getInstance().setCurrentPanel(new PlaylistPanel(playList));
                }

                return true;
            });

        }


        @Override
        public void onRender(double mouseX, double mouseY) {
            // 在本卡片的子级之前运行。一旦首帧加载了
            //标题字形，就在网格布局下一帧的行之前
            //重新测量并预留每个折行。
            updateTitleLayout(titleFont.areGlyphsLoaded(safePlaylistTitle(playList.getName())));
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
