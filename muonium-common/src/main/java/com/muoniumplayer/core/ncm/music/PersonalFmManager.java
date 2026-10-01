package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.RequestUtil;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.kugou.KuGouMusicApi;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;
import com.muoniumplayer.core.utils.json.JsonUtils;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 负责网易云私人 FM 的获取。FM 页面可能在播放开始前就显示当前批次，
 * 但普通播放只有在调用 {@link CloudMusic#playFm(List, int)} 时
 * 才会成为一个 FM session。
 */
public final class PersonalFmManager {

    public enum Mode {
        DEFAULT("默认推荐", ""),
        FAMILIAR("熟悉模式", ""),
        EXPLORE("探索模式", ""),
        SCENE_RCMD("场景推荐", SCENE_FOCUS),
        PUZZLE_MODE_RCMD("拼图模式", ""),
        AIDJ("AI DJ", "");

        private final String displayName;
        private final String defaultSubMode;

        Mode(String displayName, String defaultSubMode) {
            this.displayName = displayName;
            this.defaultSubMode = defaultSubMode;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getDefaultSubMode() {
            return defaultSubMode;
        }
    }

    public static final String SCENE_EXERCISE = "EXERCISE";
    public static final String SCENE_FOCUS = "FOCUS";
    public static final String SCENE_NIGHT_EMO = "NIGHT_EMO";

    /**
     * 官方文档「私人 FM 模式选择」列出的全部 42 个场景子模式，按界面分组顺序排列。
     * 分组标题与成员一一对应；每个子模式的展示中文名在 {@link #SCENE_LABELS}。
     */
    public static final String[] SCENE_GROUP_TITLES = {"流行热歌", "风格流派", "情绪心情", "生活场景"};
    public static final String[][] SCENE_GROUP_MODES = {
            {"CHINESE", "ENGLISH", "YUEYU", "K_POP", "JAPANESE", "GLOBAL", "FRANCH",
                    "ACG", "JINGDIAN", "ORIGINAL_MUSICIAL"},
            {"ROCK", "RAP", "RHYTHM_BLUES", "ELECTRONIC", "JAZZ", "BLUE", "PUNK", "LATIN",
                    "COUNTRY", "GUDIAN", "LIGHT", "GUOFENG", "FOLK", "DANCE", "MANYAO",
                    "MUSICAL", "YINGSHI", "GAMES"},
            {"FOCUS", "NIGHT_EMO", "CHEERFUL", "LYRICAL", "CURE", "SWEET", "INSPIRATIONAL"},
            {SCENE_EXERCISE, "SLEEP_HELP", "RELAX", "RAINY", "COMMUTE", "TAKE_SHOWER", "COFFEE_SHOP"}
    };

    /** 场景子模式 → 界面中文名。这份映射同时是子模式合法性的唯一白名单来源。 */
    private static final java.util.Map<String, String> SCENE_LABELS = new java.util.LinkedHashMap<>();

    static {
        String[][] names = {
                {"华语", "欧美", "粤语", "K-Pop", "日语", "全球", "法语", "二次元", "经典", "宝藏原创"},
                {"摇滚", "说唱", "R&B", "电音", "爵士", "蓝调", "放克", "拉丁", "乡村乐", "古典",
                        "轻音乐", "国风", "民谣", "舞蹈", "慢摇DJ", "音乐剧", "影视", "游戏"},
                {"专注", "伤感", "欢快", "抒情", "治愈", "情歌", "励志"},
                {"运动", "助眠", "放松", "雨天", "出行", "洗澡", "咖啡馆"}
        };
        for (int group = 0; group < SCENE_GROUP_MODES.length; group++) {
            String[] modes = SCENE_GROUP_MODES[group];
            if (modes.length != names[group].length) {
                throw new IllegalStateException("私人 FM 场景分组与中文名数量不一致：group " + group);
            }
            for (int index = 0; index < modes.length; index++) SCENE_LABELS.put(modes[index], names[group][index]);
        }
    }

    /** 场景子模式在界面上的短名；未登记的子模式返回空串。 */
    public static String sceneLabel(String subMode) {
        String label = subMode == null ? null : SCENE_LABELS.get(subMode);
        return label == null ? "" : label;
    }

    /** 场景子模式总数（官方文档当前为 42 项），供测试与界面钉住。 */
    public static int sceneCount() {
        return SCENE_LABELS.size();
    }

    /** 当前选中模式（含场景子模式）的完整展示文案，如「场景推荐 · 专注」。 */
    public static String getSelectionLabel() {
        Mode mode = selectedMode;
        String scene = sceneLabel(selectedSubMode);
        return scene.isEmpty() ? mode.getDisplayName() : mode.getDisplayName() + " · " + scene;
    }

    /** 私人 FM 刻意采用拉取模式：每次恰好只取一条推荐。 */
    private static final int BATCH_SIZE = 1;
    private static final int RECENT_ID_LIMIT = 24;
    private static final AtomicBoolean LOADING = new AtomicBoolean(false);
    /**
     * 后台"预备下一首"独占标志，刻意与 {@link #LOADING} 分开：预备是自动发生的，不能因为它在跑就
     * 让用户点刷新 / 换模式 / 跳过没反应，反过来也不能让用户操作被一次后台请求饿死。
     */
    private static final AtomicBoolean PREFETCHING = new AtomicBoolean(false);
    /** 防止一次失败的首次请求在每次面板重绘时递归地重新打开自身。 */
    private static final AtomicBoolean INITIAL_REQUEST_ISSUED = new AtomicBoolean(false);
    private static final Set<Long> RECENT_SONG_IDS = new LinkedHashSet<>();

    private static volatile List<Music> currentBatch = Collections.emptyList();
    /**
     * 这一轮里已经被换下去的推荐，新的在头。hero 卡只画 {@link #currentBatch} 那一首，
     * 「刚才推荐过」那一列读这里。
     *
     * <p>私人 FM 一次只拉一首（{@link #BATCH_SIZE}），所以没有这份历史的话，整个界面就只有
     * 一行可看，剩下大半屏是空的。上限只是防一手长时间挂机把它撑大，取数那一侧并不用它。</p>
     */
    private static volatile List<Music> history = Collections.emptyList();
    private static final int HISTORY_LIMIT = 20;
    /** 已经接进活队列、但还没播到的下一首；播到它时由 {@link #noteFmTrackStarted(Music)} 清掉。 */
    private static volatile Music prefetchedNext;
    private static volatile Mode selectedMode = Mode.DEFAULT;
    private static volatile String selectedSubMode = "";
    private static volatile String status = "进入后获取 1 首私人 FM 推荐";

    /** 模式选择的落盘状态。与 {@link #selectedMode} 分开加锁：读写只发生在用户切换与首次取数时。 */
    private static final Gson SETTINGS_GSON = new Gson();
    private static final Object SETTINGS_LOCK = new Object();
    private static boolean settingsLoaded;

    private PersonalFmManager() {
    }

    public static List<Music> getCurrentBatchSnapshot() {
        return new ArrayList<>(currentBatch);
    }

    public static List<Music> getHistorySnapshot() {
        return new ArrayList<>(history);
    }

    /**
     * 把即将被替换掉的那一批压进历史，然后交给新的一批。
     *
     * <p>去重按 {@link Music#getStableKey()} 而不是 id：上游在短时间内把同一首推回来是常事
     * （换模式又换回来尤其明显），历史里出现两行同名同艺人的歌，看着像是这个界面坏了。</p>
     */
    private static void retireToHistory() {
        List<Music> leaving = currentBatch;
        if (leaving.isEmpty()) return;

        List<Music> merged = new ArrayList<>(leaving.size() + history.size());
        Set<String> seen = new LinkedHashSet<>();
        for (Music song : leaving) {
            if (song != null && seen.add(song.getStableKey())) merged.add(song);
        }
        for (Music song : history) {
            if (song != null && seen.add(song.getStableKey())) merged.add(song);
        }
        while (merged.size() > HISTORY_LIMIT) {
            merged.remove(merged.size() - 1);
        }
        history = Collections.unmodifiableList(merged);
    }

    public static Mode getSelectedMode() {
        return selectedMode;
    }

    public static String getSelectedSubMode() {
        return selectedSubMode;
    }

    public static String getStatus() {
        Music pending = prefetchedNext;
        return pending == null ? status : status + " · 已预备下一首";
    }

    /** 是否已经把下一首推荐预备进了活队列。 */
    public static boolean hasPrefetchedNext() {
        return prefetchedNext != null;
    }

    public static boolean isLoading() {
        return LOADING.get();
    }

    /** 在 FM 面板首次打开时加载一次；出错时错误会一直可见，直到用户刷新。 */
    public static void ensureInitialLoad() {
        ensureSettingsLoaded();
        if (currentBatch.isEmpty() && !LOADING.get() && INITIAL_REQUEST_ISSUED.compareAndSet(false, true)) {
            requestBatch(selectedMode, selectedSubMode, true);
        }
    }

    /** 用户显式刷新：即使上一次请求失败也重试。 */
    public static void openOrRefresh() {
        INITIAL_REQUEST_ISSUED.set(true);
        requestBatch(selectedMode, selectedSubMode, true);
    }


    public static void selectMode(Mode mode, String subMode) {
        ensureSettingsLoaded();
        selectedMode = mode == null ? Mode.DEFAULT : mode;
        selectedSubMode = selectedMode == Mode.SCENE_RCMD ? normalizeSceneSubMode(subMode) : "";
        persistSelection();
        requestBatch(selectedMode, selectedSubMode, CloudMusic.isPersonalFmActive());
    }

    /** 由仅 FM 播放器按钮调用。这是唯一提交电台“垃圾桶”的路径。 */
    public static void skipCurrentAndRequestNext() {
        if (!CloudMusic.isPersonalFmActive() || !LOADING.compareAndSet(false, true)) {
            return;
        }

        final Music current = CloudMusic.currentlyPlaying;
        status = "正在切换私人 FM…";
        notifyUi();
        MultiThreadingUtil.runAsync(() -> {
            try {
                if (current != null && current.isNetease()) {
                    // “垃圾桶”操作刻意做成显式：仅仅进入 FM 或按普通“下一首”绝不会调用它。
                    CloudMusicApi.personalFmTrash(current.getId());
                } else if (current != null && current.isKuGou()) {
                    // 酷狗是同一个语义、不同端点：/v2/personal_recommend 带 action=garbage，
                    // 并且要回传最近一次推荐的游标，否则反馈会打到别批推荐上（且不报错）。
                    KuGouMusicService.reportFmTrash(current);
                }
            } catch (Throwable throwable) {
                System.err.println("[NCM/FM] Skip feedback failed: " + safeMessage(throwable));
            } finally {
                LOADING.set(false);
            }
            requestBatch(selectedMode, selectedSubMode, true);
        });
    }

    /**
     * 仅当 FM 小批次播到末尾、或其全部曲目都失败时触发。它不会
     * 把任何曲目标记为垃圾桶。
     */
    public static void requestNextBatchAsync() {
        requestBatch(selectedMode, selectedSubMode, true);
    }

    private static void requestBatch(Mode mode, String subMode, boolean startPlaybackAfterLoad) {
        if (!LOADING.compareAndSet(false, true)) {
            return;
        }
        // 酷狗也有私人 FM，但它的接口是「猜你喜欢」流，没有网易云那套模式/场景参数。
        // 分支放在这里而不是让调用方分叉：两个源共用同一套批处理、预取与播放衔接逻辑，
        // 差别只在「怎么拿到一批歌」。
        if (NeteaseMusicService.getCurrentPlatform() == MusicPlatform.KUGOU) {
            requestKuGouBatch(startPlaybackAfterLoad);
            return;
        }
        if (CloudMusic.profile == null || CloudMusic.profile.getId() <= 0L) {
            status = "私人 FM 需要登录网易云账号";
            LOADING.set(false);
            notifyUi();
            return;
        }

        selectedMode = mode == null ? Mode.DEFAULT : mode;
        selectedSubMode = selectedMode == Mode.SCENE_RCMD ? normalizeSceneSubMode(subMode) : "";
        status = "正在获取私人 FM 推荐…";
        notifyUi();

        MultiThreadingUtil.runAsync(() -> {
            try {
                List<Music> loaded = fetchRecommendations();
                retireToHistory();
                currentBatch = Collections.unmodifiableList(loaded);
                status = getSelectionLabel() + " · " + loaded.size() + " 首推荐";
                // playFm() 自身会建立隔离的 FM session，因此首次进入不得
                // 需要一个已处于活跃状态的 FM session 才能自动启动。
                if (startPlaybackAfterLoad) {
                    CloudMusic.playFm(loaded, 0);
                }
            } catch (Throwable throwable) {
                status = "私人 FM 获取失败：" + safeMessage(throwable);
                System.err.println("[NCM/FM] " + status);
            } finally {
                LOADING.set(false);
                notifyUi();
            }
        });
    }

    /**
     * 酷狗的私人 FM 分支。
     *
     * <p>{@code selectedMode} 仍然写进状态文案，但酷狗端不理解它——那边的接口没有模式参数。
     * 与其禁掉整个模式选择器，不如照常接受并在文案里说明，用户至少能看出「当前不是网易云」。</p>
     */
    private static void requestKuGouBatch(boolean startPlaybackAfterLoad) {
        if (!KuGouMusicService.isLoggedIn()) {
            status = "酷狗私人 FM 需要登录酷狗账号";
            LOADING.set(false);
            notifyUi();
            return;
        }
        status = "正在获取酷狗私人 FM 推荐…";
        notifyUi();

        MultiThreadingUtil.runAsync(() -> {
            try {
                // 传上一首的 hash，避免上游在短时间内又推回同一首。
                String exclude = currentBatch.isEmpty() ? "" : currentBatch.get(currentBatch.size() - 1).getSourceId();
                List<Music> loaded = KuGouMusicService.personalFm(kuGouMode(), BATCH_SIZE, exclude);
                if (loaded.isEmpty()) throw new IllegalStateException("未返回可播放的酷狗推荐");
                retireToHistory();
                currentBatch = Collections.unmodifiableList(loaded);
                status = "酷狗猜你喜欢 · " + loaded.size() + " 首推荐";
                if (startPlaybackAfterLoad) {
                    CloudMusic.playFm(loaded, 0);
                }
            } catch (Throwable throwable) {
                status = "酷狗私人 FM 获取失败：" + safeMessage(throwable);
                System.err.println("[NCM/FM] " + status);
            } finally {
                LOADING.set(false);
                notifyUi();
            }
        });
    }

    /**
     * 按当前选中的模式拉一次推荐并解析。请求失败、响应状态异常、没有可播放曲目都直接抛出，由调用方
     * 决定怎么反馈。抽出来给"用户触发的整批请求"和"后台预备下一首"共用，避免两条路径的接口参数和
     * 解析行为日后慢慢分叉。
     */
    private static List<Music> fetchRecommendations() throws Exception {
        if (NeteaseMusicService.getCurrentPlatform() == MusicPlatform.KUGOU) {
            if (!KuGouMusicService.isLoggedIn()) throw new IllegalStateException("酷狗私人 FM 需要登录");
            List<Music> loaded = KuGouMusicService.personalFm(kuGouMode(), BATCH_SIZE, "");
            if (loaded.isEmpty()) throw new IllegalStateException("未返回可播放的酷狗推荐");
            return loaded;
        }
        String apiMode = selectedMode == Mode.AIDJ ? "aidj" : selectedMode.name();
        RequestUtil.RequestAnswer answer = selectedMode == Mode.DEFAULT && selectedSubMode.isEmpty()
                ? CloudMusicApi.personalFm()
                : CloudMusicApi.personalFmMode(apiMode, selectedSubMode, BATCH_SIZE);
        if (answer == null || answer.getStatus() != 200) {
            throw new IllegalStateException("接口响应状态 " + (answer == null ? "未知" : answer.getStatus()));
        }

        List<Music> loaded = parseSongs(answer.toJsonObject());
        if (loaded.isEmpty()) {
            throw new IllegalStateException("未返回可播放的私人 FM 歌曲");
        }
        return loaded;
    }

    /**
     * 当前选中的网易云模式 → 酷狗私人 FM 的模式。
     *
     * <p>两边<b>不是一一对应</b>：网易云有六种模式（默认/熟悉/探索/场景/拼图/AI DJ），
     * 酷狗只有三种（{@code normal} 常规、{@code small} 小众、{@code peak} 30 秒高潮）。
     * 语义上唯一对得上的是「探索 ↔ 小众」，其余都落到常规那一档——<b>这不是妥协而是必须</b>：
     * 酷狗对未知 mode 值不报错，静默按 {@code normal} 处理，硬凑映射表只会让 UI 上的模式名
     * 变成一个骗人的开关。</p>
     *
     * <p>酷狗独有的 {@code PEAK}（30 秒高潮）在网易云的模式集合里没有对应项，因此从这条映射
     * 永远走不到；要用它得在 UI 上单独加一个模式项，那属于界面改动、不在本协议层范围内。</p>
     */
    private static KuGouMusicApi.FmMode kuGouMode() {
        return selectedMode == Mode.EXPLORE
                ? KuGouMusicApi.FmMode.SMALL
                : KuGouMusicApi.FmMode.NORMAL;
    }

    /**
     * 私人 FM 的"预备下一首"：当前这首还在放的时候就把下一首拉回来，接到活队列尾部。
     *
     * <p>私人 FM 一次只拉一首（{@link #BATCH_SIZE}），旧流程是"当前这首播完 → 才去请求下一首 → 拿到
     * 之后 {@link CloudMusic#playFm(List, int)} 重开一条播放线程"。中间必然横着一次网络请求加一整轮
     * 下载解码的静音，而且因为队列里从来只有正在播的这一首，无缝切换（automix）预测下一首时永远拿到
     * -1，在私人 FM 下等于完全没生效。提前接上队列之后，automix 能像普通歌单那样提前预解码并交接，
     * 手动下一首也走同一条捷径。</p>
     *
     * <p>纯预备动作，边界刻意收得很紧：不提交 trash（那只属于用户点"跳过"）、不起播、不改面板上正在
     * 显示的推荐、失败也不覆盖 {@link #getStatus()} 里用户能看到的文案。用户触发的请求（刷新 / 换模式
     * / 跳过）正在跑时直接让路，避免两条路径抢同一份 {@code currentBatch}。</p>
     *
     * @param expectedQueue 发起预备时的活队列实例，回来时用它校验用户没有退出 FM 或换掉队列
     */
    public static void prefetchNextAsync(List<Music> expectedQueue) {
        if (expectedQueue == null || !CloudMusic.isPersonalFmActive()) return;
        if (prefetchedNext != null || LOADING.get()) return;
        boolean kuGou = NeteaseMusicService.getCurrentPlatform() == MusicPlatform.KUGOU;
        if (!kuGou && (CloudMusic.profile == null || CloudMusic.profile.getId() <= 0L)) return;
        if (kuGou && !KuGouMusicService.isLoggedIn()) return;
        if (!PREFETCHING.compareAndSet(false, true)) return;

        MultiThreadingUtil.runAsync(() -> {
            try {
                final Music candidate = fetchRecommendations().get(0);
                // 队列改动统一回主线程做：界面线程正在遍历同一个 ArrayList。
                MultiThreadingUtil.runOnMainThread(() -> acceptPrefetchedTrack(expectedQueue, candidate));
            } catch (Throwable throwable) {
                // 预备失败不打扰用户：这首播完之后仍会走旧的 requestNextBatchAsync() 兜底路径。
                System.err.println("[NCM/FM] 预备下一首失败：" + safeMessage(throwable));
            } finally {
                PREFETCHING.set(false);
            }
        });
    }

    /** 主线程：把预备好的曲目接到活队列尾部。接不上就当没预备过，旧兜底路径照旧生效。 */
    private static void acceptPrefetchedTrack(List<Music> expectedQueue, Music candidate) {
        if (candidate == null || !CloudMusic.appendPrefetchedPersonalFmTrack(expectedQueue, candidate)) {
            return;
        }
        prefetchedNext = candidate;
        DownloadDynamicIsland.showPersonalFmPrefetched(candidate.getName());
        NCMScreen.getInstance().reloadCurrentPanel();
    }

    /**
     * 由播放线程在一首 FM 曲目真正开始出声时调用，普通起播与无缝交接两条路径都会走到。
     *
     * <p>做两件事：把已经消费掉的"预备下一首"标记清掉，让下一轮预备可以开始；把面板上显示的推荐换成
     * 此刻真正在播的这一首。有了预备之后 FM 的推进不再每首都经过 {@link #requestBatch}，所以这里必须
     * 补上原本由那次请求顺带完成的界面同步，否则私人 FM 页面会一直停在上一首。</p>
     */
    public static void noteFmTrackStarted(Music song) {
        if (song == null || !CloudMusic.isPersonalFmActive()) return;

        Music pending = prefetchedNext;
        if (pending != null && pending.getId() == song.getId()) {
            prefetchedNext = null;
        }

        List<Music> shown = currentBatch;
        if (shown.size() == 1 && shown.get(0) != null && shown.get(0).getId() == song.getId()) {
            return;
        }
        retireToHistory();
        currentBatch = Collections.singletonList(song);
        status = getSelectionLabel() + " · 正在播放推荐";
        notifyUi();
    }

    /** 换歌单 / 退出 FM / 重开 FM 时调用：预备状态跟着旧的活队列一起作废。 */
    public static void clearPrefetchState() {
        prefetchedNext = null;
    }

    private static List<Music> parseSongs(JsonObject root) {
        JsonArray songs = extractSongArray(root);
        if (songs == null) {
            JsonObject singleSong = extractSingleSong(root);
            if (singleSong != null) {
                songs = new JsonArray();
                songs.add(singleSong);
            }
        }
        if (songs == null) return Collections.emptyList();

        List<Music> fresh = new ArrayList<>();
        List<Music> duplicates = new ArrayList<>();
        Set<Long> batchIds = new LinkedHashSet<>();
        for (JsonElement element : songs) {
            if (fresh.size() + duplicates.size() >= BATCH_SIZE) break;
            if (element == null || !element.isJsonObject()) continue;
            JsonObject songObject = element.getAsJsonObject();
            JsonObject nestedSong = object(songObject, "song");
            if (nestedSong != null) songObject = nestedSong;
            songObject = normalizeSongPayload(songObject);
            try {
                Music music = JsonUtils.parse(songObject, Music.class);
                if (music == null || music.getId() <= 0L || !batchIds.add(music.getId())) continue;
                music.applyNeteaseMetadata(songObject, null);
                if (wasRecentlyReturned(music.getId())) duplicates.add(music);
                else fresh.add(music);
            } catch (Throwable ignored) {
                // 一条格式错误的推荐绝不能让整个小批次被丢弃。
            }
        }

        List<Music> result = fresh.isEmpty() ? duplicates : fresh;
        remember(result);
        return result;
    }


    /**
     * 私人 FM 的返回数据使用移动端字段名 artists/album/duration，
     * 而现有的不可变 DTO 映射的是标准歌曲详情字段名 ar/al/dt。
     * 在这个狭窄的 API 边界处做归一化，而不是改动每一个音乐源。
     */
    private static JsonObject normalizeSongPayload(JsonObject source) {
        JsonObject normalized = new JsonObject();
        if (source == null) return normalized;
        for (java.util.Map.Entry<String, JsonElement> entry : source.entrySet()) {
            normalized.add(entry.getKey(), entry.getValue());
        }
        copyIfMissing(normalized, "ar", source, "artists");
        copyIfMissing(normalized, "al", source, "album");
        copyIfMissing(normalized, "dt", source, "duration");
        copyIfMissing(normalized, "alia", source, "alias");
        return normalized;
    }

    private static void copyIfMissing(JsonObject target, String targetKey, JsonObject source, String sourceKey) {
        if (!target.has(targetKey) && source.has(sourceKey) && !source.get(sourceKey).isJsonNull()) {
            target.add(targetKey, source.get(sourceKey));
        }
    }


    private static JsonArray extractSongArray(JsonObject root) {
        if (root == null) return null;
        JsonArray direct = array(root, "data", "songs");
        if (direct != null) return direct;
        JsonObject data = object(root, "data");
        direct = array(data, "songs", "data");
        if (direct != null) return direct;
        JsonObject result = object(root, "result");
        return array(result, "songs", "data");
    }

    /** 基础 /radio/get 接口以 data:{...} 的形式返回一首歌。 */
    private static JsonObject extractSingleSong(JsonObject root) {
        if (root == null) return null;
        JsonObject data = object(root, "data");
        if (isSongObject(data)) return data;
        JsonObject result = object(root, "result");
        if (isSongObject(result)) return result;
        return isSongObject(root) ? root : null;
    }

    private static boolean isSongObject(JsonObject object) {
        return object != null && object.has("id") && object.has("name");
    }


    private static JsonArray array(JsonObject object, String... names) {
        if (object == null) return null;
        for (String name : names) {
            JsonElement value = object.get(name);
            if (value != null && value.isJsonArray()) return value.getAsJsonArray();
        }
        return null;
    }

    private static JsonObject object(JsonObject object, String name) {
        if (object == null) return null;
        JsonElement value = object.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static boolean wasRecentlyReturned(long songId) {
        synchronized (RECENT_SONG_IDS) {
            return RECENT_SONG_IDS.contains(songId);
        }
    }

    private static void remember(List<Music> songs) {
        synchronized (RECENT_SONG_IDS) {
            for (Music song : songs) {
                RECENT_SONG_IDS.add(song.getId());
            }
            while (RECENT_SONG_IDS.size() > RECENT_ID_LIMIT) {
                Long first = RECENT_SONG_IDS.iterator().next();
                RECENT_SONG_IDS.remove(first);
            }
        }
    }

    private static String normalizeSceneSubMode(String subMode) {
        if (SCENE_LABELS.containsKey(subMode)) return subMode;
        return Mode.SCENE_RCMD.getDefaultSubMode();
    }

    /** 首次触碰模式选择时读一次上次的选择；读失败一律当作「没用过，保持默认」。 */
    private static void ensureSettingsLoaded() {
        synchronized (SETTINGS_LOCK) {
            if (settingsLoaded) return;
            settingsLoaded = true;
            try {
                File file = ConfigPaths.PERSONAL_FM_MODE;
                if (!file.isFile()) return;
                JsonObject root = JsonConfigStorage.readObject(file, SETTINGS_GSON);
                if (root == null) return;
                Mode mode = parsePersistedMode(string(root, "mode"));
                if (mode == null) return;
                selectedMode = mode;
                selectedSubMode = mode == Mode.SCENE_RCMD ? normalizeSceneSubMode(string(root, "subMode")) : "";
            } catch (Throwable ignored) {
            }
        }
    }

    private static Mode parsePersistedMode(String name) {
        if (name == null) return null;
        try {
            return Mode.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return null;
        return object.get(key).getAsString();
    }

    /** 立刻落盘当前选择。写失败只会让下次启动回到上次的选择，不打扰用户。 */
    private static void persistSelection() {
        synchronized (SETTINGS_LOCK) {
            settingsLoaded = true;
            try {
                File parent = ConfigPaths.PERSONAL_FM_MODE.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) return;
                JsonObject root = new JsonObject();
                root.addProperty("mode", selectedMode.name());
                if (!selectedSubMode.isEmpty()) root.addProperty("subMode", selectedSubMode);
                JsonConfigStorage.writeObject(ConfigPaths.PERSONAL_FM_MODE, SETTINGS_GSON, root);
            } catch (Throwable ignored) {
            }
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable == null ? "网络请求失败" : throwable.getMessage();
        return message == null || message.trim().isEmpty() ? "网络请求失败" : message.trim();
    }

    private static void notifyUi() {
        MultiThreadingUtil.runOnMainThread(() -> NCMScreen.getInstance().reloadCurrentPanel());
    }
}
