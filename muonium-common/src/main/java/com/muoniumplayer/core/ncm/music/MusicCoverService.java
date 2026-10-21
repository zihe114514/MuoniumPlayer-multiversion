package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.Cleanup;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.dto.Music;
import org.apache.commons.io.IOUtils;
import com.muoniumplayer.core.rendering.GaussianKernel;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.texture.AbstractTexture;
import com.muoniumplayer.core.rendering.texture.AnimatedCoverTexture;
import com.muoniumplayer.core.rendering.texture.DynamicTexture;
import com.muoniumplayer.core.rendering.texture.ITextureObject;
import com.muoniumplayer.core.rendering.texture.Textures;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.network.HttpUtils;
import com.muoniumplayer.core.utils.other.multithreading.BackgroundWork;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import com.muoniumplayer.core.settings.HudConfig;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Node;

/**
 * 异步音乐封面获取、纹理上传与歌词背景模糊生成。
 *
 * <p>本服务刻意保留既有的异步任务边界：主封面纹理一下载完成就立即上传，而模糊歌词纹理则在另一个独立任务上生成。</p>
 */
final class MusicCoverService {

    private static final Kernel GAUSSIAN_KERNEL = new Kernel(41, 41, GaussianKernel.generate(41));
    private static final int MAX_DYNAMIC_COVER_BYTES = 8 * 1024 * 1024;
    private static final int MAX_DYNAMIC_COVER_DIMENSION = 512;
    private static final int MAX_DYNAMIC_COVER_FRAMES = 24;
    /** 动态封面视频的下载上限。网易云的封面循环一般 1–3 MB,12 MB 足够而且不会让一次失手吃满内存。 */
    private static final int MAX_DYNAMIC_COVER_VIDEO_BYTES = 12 * 1024 * 1024;
    private static final Set<String> DYNAMIC_COVER_ATTEMPTS = new HashSet<String>();
    /** 尝试记录只用于去重,不能无限增长;超过这个数量就整表清空重新来。 */
    private static final int MAX_DYNAMIC_COVER_ATTEMPTS = 512;
    /**
     * 已装载的动态封面纹理。TextureManager 自己从不淘汰,而动画封面的帧数据是十几 MB 的堆内存 +
     * 一张 GL 纹理,不主动回收的话每播一首歌就多留一份。只保留当前与上一首(全屏歌词页切歌时会同时
     * 用到两首的封面做交叉淡化)。
     */
    private static final int MAX_LIVE_DYNAMIC_COVERS = 2;
    private static final LinkedList<LiveDynamicCover> LIVE_DYNAMIC_COVERS = new LinkedList<LiveDynamicCover>();
    /**
     * 跨平台曲目匹配到的网易云 songId（{@code stableKey → songId}）。
     *
     * <p>必须独立于 {@link #DYNAMIC_COVER_ATTEMPTS}：后者在 ffmpeg 缺失时会被 {@link #forgetAttempt}
     * 撤销，允许下次播到同一首再试，没有这层缓存就得重烧一次网易云搜索额度。0 也是有效结论。</p>
     */
    private static final Map<String, Long> NETEASE_COVER_IDS =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();
    /** 匹配结论表的上限，同 {@link #MAX_DYNAMIC_COVER_ATTEMPTS} 的粗粒度防泄漏。 */
    private static final int MAX_NETEASE_COVER_IDS = 512;

    private MusicCoverService() {
    }

    static void loadMusicCover(Music music, boolean forceReload) {
        Location musicCover = music.getCoverLocation();
        Location musicCoverSmall = music.getSmallCoverLocation();
        Location musicCoverBlur = music.getBlurredCoverLocation();
        TextureManager textureManager = TextureManager.getInstance();

        if (shouldLoadCover(textureManager, musicCover, forceReload)) {
            loadMainCoverAsync(music, musicCover, musicCoverBlur);
        }
        if (shouldLoadCover(textureManager, musicCoverSmall, forceReload)) {
            loadSmallCoverAsync(music, musicCoverSmall);
        }
    }

    /**
     * 为当前播放的曲目取动态封面，<b>跨平台一律先问网易云</b>。
     *
     * <p>网易云的动态封面实际上是一段几秒的循环 <b>MP4</b>(接口字段 {@code videoPlayUrl}),因此这里有
     * 两条路:极少数返回 GIF/APNG 之类的图片时直接用 ImageIO 解;返回视频时交给 {@link VideoCoverFrames}
     * 用 ffmpeg 抽成有限帧,再包成项目已有的 {@link AnimatedCoverTexture} 循环播放。</p>
     *
     * <h3>取址优先级（三级，见 {@link #resolveCoverSources}）</h3>
     * <ol>
     *   <li><b>网易云</b>：曲目本身就来自网易云时问本尊；来自别的平台时先拿曲名 / 艺人去网易云匹配
     *       一条真正的 songId（判据整个骑 {@link CrossSourceSwitcher} 那条已经钉死的换源流水线），
     *       再查它的动态封面。</li>
     *   <li><b>本平台原生</b>：网易云那一级落空（没登录、匹配不上、这首歌网易云没发动态封面）才轮到，
     *       目前只有酷狗有这一级。</li>
     *   <li><b>静态封面</b>：前两级都空时纹理压根不装，{@link #preferredCoverLocation} 自然回落到
     *       平台自带的那张图。动态封面纯属装饰,任何一级失败都不影响播放。</li>
     * </ol>
     *
     * <p>只为"正在播放"的那一首请求:动态封面是给桌面歌曲信息 HUD、全屏歌词页和播放条上的当前曲目用的,
     * 歌单列表里的缩略图仍然是静态封面(几十条同时解视频既没必要也扛不住)。</p>
     *
     * <p>任何一步失败(未登录、这首歌没有动态封面、ffmpeg 不存在、视频损坏、超时)都只是保持静态封面,
     * 不影响播放,也不会重试到打爆接口。</p>
     */
    static void loadDynamicMusicCover(final Music music) {
        if (!supportsDynamicCover(music)) {
            return;
        }
        if (!isDynamicCoverEnabled()) {
            return;
        }
        final Location dynamicLocation = music.getDynamicCoverLocation();
        final TextureManager textureManager = TextureManager.getInstance();
        if (textureManager.getTexture(dynamicLocation) != null) {
            return;
        }
        if (!markAttempted(music)) {
            return;
        }

        // 低优先级池：这条链要下载一段视频再用 ffmpeg 抽成几十张 256×256 的 PNG，是纯装饰性开销，
        // 没有任何理由和渲染线程争 CPU。
        BackgroundWork.submit(() -> {
            try {
                // 三级优先级的取址；网易云与酷狗拿「有没有动态封面 / 在哪」的方式完全不同，
                // 只有后面的解码与安装是共用的。
                CoverSources sources = resolveCoverSources(music);
                if (sources.isEmpty()) {
                    // 绝大多数曲目本来就没有动态封面(接口返回 data:{}),这不是失败,但日志里必须能和
                    // "取到了却用不上"区分开,否则无法判断是曲目问题还是功能坏了。链尾记的是走到哪一级
                    // 落的空，songId 只有在「已经配到一条网易云曲目、它确实没发封面」时才会出现。
                    System.out.println("[Music/Cover] No dynamic cover published for " + music.getStableKey()
                            + " (优先级链止于 " + sources.channel
                            + (sources.songId > 0L ? ", songId=" + sources.songId : "") + ")");
                    return;
                }
                ITextureObject texture = decodeImageCover(sources.imageUrl);
                if (!isAnimated(texture)) {
                    // 图片分支拿到的是单帧(等于静态封面)或什么都没拿到,这时视频才是真正的动态封面。
                    ITextureObject video = decodeVideoCover(music, sources.videoUrl);
                    if (video != null) {
                        discard(texture);
                        texture = video;
                    }
                }
                if (texture == null) {
                    return;
                }
                install(music, dynamicLocation, texture);
            } catch (Throwable failure) {
                // 动态封面纯属装饰,静态封面始终是保底;但静默失败会让问题无法定位,所以留一行。
                System.err.println("[Music/Cover] Dynamic cover lookup failed for " + music.getStableKey()
                        + ": " + failure);
            }
        });
    }

    /**
     * 这首歌值不值得去查一次动态封面。
     *
     * <p>网易云曲目要有真的 songId(接口按 id 查)；酷狗要有 hash(那是它取流与歌词的同一个主键,
     * 没有就说明这条 Music 不是从酷狗接口来的)；其余平台一律放行 —— 它们没有本家动态封面,
     * 优先链第 1 级落空时后面还有静态封面兜底,最坏结果只是白问一次。</p>
     */
    static boolean supportsDynamicCover(Music music) {
        if (music == null) {
            return false;
        }
        if (music.isNetease()) {
            return music.getId() > 0L;
        }
        if (music.isKuGou()) {
            String hash = music.getKugouHash();
            return hash != null && !hash.trim().isEmpty();
        }
        return true;
    }

    /**
     * 按优先级给一首歌取动态封面地址：网易云 → 本平台原生 → 全空。
     *
     * <p>只能在后台线程调用，两级都要发网络请求。返回空不代表失败，只是这首歌没有动态封面，
     * 调用方保持静态封面；{@link CoverSources#channel} 此时记的是「链走到哪一级落的空」,
     * 真机上就靠它分辨是没登录、匹配不上、还是网易云确实没发这张封面。</p>
     */
    static CoverSources resolveCoverSources(Music music) {
        CoverSources netease = neteaseCoverSources(music);
        if (!netease.isEmpty()) {
            return netease;
        }
        if (!hasPlatformNativeDynamicCover(music)) {
            return new CoverSources(netease.channel + "，本平台无动态封面", netease.songId);
        }
        // 酷狗只给视频，没有图片型动态封面这一支。
        CoverSources platform = new CoverSources("kugou");
        platform.videoUrl = safe(KuGouMusicService.dynamicCoverUrl(music));
        if (!platform.isEmpty()) {
            System.out.println("[Music/Cover] " + music.getStableKey() + " 网易云无动态封面，回退酷狗本家");
            return platform;
        }
        return new CoverSources(netease.channel + "，酷狗本家同样没有", netease.songId);
    }

    /** 优先链第 1 级：网易云那条曲目（可能是匹配出来的另一首歌）的动态封面地址。 */
    private static CoverSources neteaseCoverSources(Music music) {
        long songId = resolveNeteaseCoverId(music);
        if (songId <= 0L) {
            return new CoverSources("no-netease-id");
        }
        try {
            JsonObject response = CloudMusicApi.songDynamicCover(songId).toJsonObject();
            CoverSources sources = new CoverSources("netease", songId);
            sources.imageUrl = findDynamicCoverUrl(response);
            sources.videoUrl = findDynamicVideoUrl(response);
            if (!sources.isEmpty() && !music.isNetease()) {
                System.out.println("[Music/Cover] " + music.getStableKey() + " 采用网易云动态封面 songId="
                        + songId + " (" + music.getSource().name() + " 跨平台匹配)");
            }
            return sources;
        } catch (Throwable failure) {
            // 未登录、风控、接口形状变了都落在这里。整条链的保底是静态封面，但日志必须把
            // 「问失败了」和「网易云确实没发」分开，否则真机上分不清是覆盖率还是链路坏了。
            System.err.println("[Music/Cover] 网易云动态封面接口失败 " + music.getStableKey()
                    + " (songId=" + songId + "): " + failure);
            return new CoverSources("netease-error");
        }
    }

    /** 优先链第 2 级：本平台自己有没有动态封面可问。目前只有酷狗实现了。 */
    static boolean hasPlatformNativeDynamicCover(Music music) {
        return music != null && music.isKuGou();
    }

    /**
     * 这首歌对应的网易云 songId，拿不到返回 0（调用方按「网易云这一级放弃」处理）。
     *
     * <ul>
     *   <li>网易云自己的曲目：本尊 id，不查任何东西。</li>
     *   <li>没登录网易云：直接 0。{@code /api/songplay/dynamic-cover} 是登录接口，问一次只会换个
     *       失败回来。这里<b>不要求会员</b> —— 会员闸门是给取流用的（{@code hasVipAccess}），封面不取流。</li>
     *   <li>其它平台：曲名 / 艺人去网易云搜一条，过 {@link CrossSourceSwitcher#acceptanceScore} 的
     *       相似度 + 时长闸门后取排名最高者。不另写匹配器，判据与自动换源保持同一份。</li>
     * </ul>
     */
    static long resolveNeteaseCoverId(Music music) {
        if (music == null) {
            return 0L;
        }
        if (music.isNetease()) {
            return music.getId();
        }
        if (CloudMusic.profile == null) {
            return 0L;
        }
        String key = music.getStableKey();
        Long cached = cachedNeteaseCoverId(key);
        if (cached != null) {
            return cached;
        }
        long matched = searchNeteaseCoverId(music);
        rememberNeteaseCoverId(key, matched);
        return matched;
    }

    /** 在网易云搜一次并挑一条最像的。只能在后台线程调用。 */
    private static long searchNeteaseCoverId(Music music) {
        String title = NeteaseGreyUnblocker.cleanTitle(music.getName());
        if (title.isEmpty()) {
            return 0L;
        }
        String artist = NeteaseGreyUnblocker.keywordArtists(music);
        if (artist.isEmpty()) artist = safe(music.getArtistsName()).trim();
        String keyword = NeteaseGreyUnblocker.searchQuery(title, artist);
        List<Music> results;
        try {
            // searchPlatform 自带「带艺人搜空了就退纯曲名再搜一次」的降级。
            results = CrossSourceSwitcher.searchPlatform(MusicPlatform.NETEASE, keyword, title, artist);
        } catch (Throwable failure) {
            System.err.println("[Music/Cover] 网易云匹配搜索失败 " + music.getStableKey() + ": " + failure);
            return 0L;
        }
        return pickNeteaseMatch(music, results, title, artist);
    }

    /**
     * 从搜索结果里挑一条最像的网易云曲目 id，没有合格候选返回 0。
     *
     * <p>纯函数，不碰网络也不读任何全局：单测钉的就是它。判定与自动换源同源
     * （曲名 / 艺人加权相似度 + 时长闸门），所以不会出现「换源认为同一首歌、封面却配错了」。</p>
     */
    static long pickNeteaseMatch(Music current, List<Music> results, String title, String artist) {
        if (current == null || results == null || results.isEmpty()) {
            return 0L;
        }
        String currentKey = current.getStableKey();
        String name = safe(title).isEmpty() ? safe(current.getName()) : title;
        long bestId = 0L;
        double bestScore = 0.0;
        for (Music candidate : results) {
            // 只认网易云本家曲目：这张表存在的唯一理由就是拿真的 songId 去查动态封面端点。
            if (candidate == null || !candidate.isNetease()) {
                continue;
            }
            String key = candidate.getStableKey();
            if (key != null && key.equals(currentKey)) {
                continue;
            }
            double score = CrossSourceSwitcher.acceptanceScore(name, artist, current.getDuration(),
                    candidate.getName(), candidate.getArtistsName(), candidate.getDuration());
            if (score <= bestScore) {
                continue;
            }
            bestScore = score;
            bestId = candidate.getId();
        }
        return bestId;
    }

    /** 读匹配结论。{@code null} = 没查过；{@code 0L} = 查过但没有合格匹配。 */
    static Long cachedNeteaseCoverId(String key) {
        return key == null ? null : NETEASE_COVER_IDS.get(key);
    }

    /**
     * 记一次匹配结论。0 也要记 —— 负结论不重烧搜索额度是这张表的另一半意义：
     * {@link #forgetAttempt} 在 ffmpeg 缺失时会撤销尝试记录，下次播到同一首还要再试，
     * 没有这层缓存就得再搜一遍。
     */
    static void rememberNeteaseCoverId(String key, long songId) {
        if (key == null || key.isEmpty()) {
            return;
        }
        if (NETEASE_COVER_IDS.size() >= MAX_NETEASE_COVER_IDS) {
            NETEASE_COVER_IDS.clear();
        }
        NETEASE_COVER_IDS.put(key, songId);
    }

    /** 匹配结论与尝试记录的测试钩子：让单测能清账。 */
    static void clearCachesForTests() {
        NETEASE_COVER_IDS.clear();
        synchronized (DYNAMIC_COVER_ATTEMPTS) {
            DYNAMIC_COVER_ATTEMPTS.clear();
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /** 一次动态封面取址的结果：来自优先链的哪一级（日志与降级判定用）+ 图片 / 视频地址。 */
    static final class CoverSources {
        final String channel;
        final long songId;
        String imageUrl = "";
        String videoUrl = "";

        CoverSources(String channel) {
            this(channel, 0L);
        }

        CoverSources(String channel, long songId) {
            this.channel = channel;
            this.songId = songId;
        }

        boolean isEmpty() {
            return imageUrl.isEmpty() && videoUrl.isEmpty();
        }
    }

    /** 动态封面已经就绪时返回它,否则返回给定的静态封面。渲染层用这个决定绑哪张纹理。 */
    static Location preferredCoverLocation(Music music, Location fallback) {
        if (music == null) {
            return fallback;
        }
        // 镜像中且外部播放器自己给了封面：它排第一。那台播放器正在播的就是这首歌，它自己
        // 报的图比我们去曲库猜一张更可信；它没给图时这里返回 null，下面的曲库链原样接管。
        Location external = ExternalArtwork.coverLocation(music);
        if (external != null) {
            return external;
        }
        Location dynamic = music.getDynamicCoverLocation();
        return TextureManager.getInstance().getTexture(dynamic) != null ? dynamic : fallback;
    }

    /**
     * 把一张图片字节解成单帧封面。外部播放器那张图（{@link ExternalArtwork}）走这里。
     *
     * <p>与 {@code decodeDynamicCover} 分开是刻意的：那条要处理 GIF/MP4 抽帧与多帧时长，
     * 这条只需要「一张静止的专辑图」，而它跑在后台线程上，越简单越好推理。解不出来
     * （文件损坏、格式不认识、超大）一律返回 null，调用方退回曲库封面。</p>
     */
    static BufferedImage decodeStillCover(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            return null;
        }
        try {
            BufferedImage frame = ImageIO.read(new ByteArrayInputStream(imageBytes));
            return frame == null ? null : scaleDown(frame);
        } catch (Throwable failure) {
            return null;
        }
    }

    /**
     * 开关的唯一真实来源是 {@link HudConfig#animatedCoverEnabled}:HUD 编辑器的"动态封面"行与模块里的
     * {@code Animated Cover} 都读写它,两处显示因此永远一致,而且不依赖外部值存储是否已初始化。
     */
    private static boolean isDynamicCoverEnabled() {
        return HudConfig.animatedCoverEnabled;
    }

    /** ffmpeg 的已缓存探测结论:1 可用,-1 探测过但没找到,0 还没探测。不会触发新的探测。 */
    static int ffmpegState() {
        return FfmpegSupport.cachedState();
    }

    /** 同一首歌只尝试一次,避免每次重新播放都打一遍接口。 */
    private static boolean markAttempted(Music music) {
        synchronized (DYNAMIC_COVER_ATTEMPTS) {
            if (DYNAMIC_COVER_ATTEMPTS.size() >= MAX_DYNAMIC_COVER_ATTEMPTS) {
                DYNAMIC_COVER_ATTEMPTS.clear();
            }
            return DYNAMIC_COVER_ATTEMPTS.add(music.getStableKey());
        }
    }

    /** 撤销尝试记录。只有"这次没成是环境问题"时才用,例如用户还没装 ffmpeg。 */
    private static void forgetAttempt(Music music) {
        synchronized (DYNAMIC_COVER_ATTEMPTS) {
            DYNAMIC_COVER_ATTEMPTS.remove(music.getStableKey());
        }
    }

    /** 图片型动态封面(GIF/APNG)。没有图片 URL 或解不出来时返回 null,交给视频分支。 */
    private static ITextureObject decodeImageCover(String imageUrl) {
        try {
            if (imageUrl == null || imageUrl.isEmpty() || isVideoUrl(imageUrl)) {
                return null;
            }
            byte[] imageBytes;
            try (InputStream stream = HttpUtils.downloadStream(imageUrl, 2)) {
                imageBytes = readAtMost(stream, MAX_DYNAMIC_COVER_BYTES);
            }
            return decodeDynamicCover(imageBytes);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 视频型动态封面:下载到临时文件后用 ffmpeg 抽帧。ffmpeg 不存在时提示一次并撤销尝试记录——用户可能
     * 稍后才装上,下次播到这首歌应该还能再试。
     */
    private static ITextureObject decodeVideoCover(Music music, String videoUrl) {
        if (videoUrl == null || videoUrl.isEmpty()) {
            return null;
        }
        if (!FfmpegSupport.isAvailable()) {
            FfmpegSupport.warnMissingOnce();
            forgetAttempt(music);
            return null;
        }

        File temporary = null;
        try {
            temporary = File.createTempFile("muonium-cover-", ".mp4");
            long written;
            try (InputStream stream = HttpUtils.downloadStream(videoUrl, 2)) {
                written = writeAtMost(stream, temporary, MAX_DYNAMIC_COVER_VIDEO_BYTES);
            }
            if (written <= 0L) {
                return null;
            }

            List<BufferedImage> frames = VideoCoverFrames.decode(temporary);
            if (frames.isEmpty()) {
                return null;
            }
            List<Long> durations = new ArrayList<Long>(frames.size());
            for (int index = 0; index < frames.size(); index++) {
                durations.add(VideoCoverFrames.frameDurationMillis());
            }
            AnimatedCoverTexture texture = new AnimatedCoverTexture(frames, durations);
            System.out.println("[Music/Cover] Animated cover ready for " + music.getStableKey() + ": "
                    + frames.size() + " frames, " + (texture.getHeapBytes() / (1024L * 1024L)) + " MB");
            return texture;
        } catch (Throwable failure) {
            System.err.println("[Music/Cover] Animated cover unavailable for " + music.getStableKey() + ": "
                    + failure);
            return null;
        } finally {
            if (temporary != null && !temporary.delete()) {
                temporary.deleteOnExit();
            }
        }
    }

    /**
     * 装载纹理。解码期间用户可能已经换歌了,这种情况下必须把已经分配的 GL 纹理删掉而不是留着——它不在
     * TextureManager 里,没人再有机会回收它。
     */
    private static void install(final Music music, final Location dynamicLocation, final ITextureObject texture) {
        final String cacheKey = music.getStableKey();
        MultiThreadingUtil.runOnMainThread(() -> {
            // 比 stableKey 而不是比引用:歌单重载、automix 交接都可能让"同一首歌"换成另一个 Music 实例,
            // 而纹理位置本来就是按 stableKey 生成的。
            Music playing = CloudMusic.currentlyPlaying;
            if (playing == null || !cacheKey.equals(playing.getStableKey())) {
                // 解码期间换歌了:纹理没进 TextureManager,不删就再没人有机会删。
                discard(texture);
                System.out.println("[Music/Cover] Dropped dynamic cover for " + cacheKey + ": track changed");
                return;
            }
            TextureManager.getInstance().loadTexture(dynamicLocation, texture);
            trimLiveDynamicCovers(new LiveDynamicCover(cacheKey, dynamicLocation));
        });
    }

    /**
     * 主线程上执行:新装一张,顺手把过老的动态封面连纹理一起丢掉。被淘汰的那首同时撤销尝试记录——
     * 纹理已经没了,以后再播到它应该允许重新取一次,否则只能看静态封面。
     */
    private static void trimLiveDynamicCovers(LiveDynamicCover installed) {
        for (java.util.Iterator<LiveDynamicCover> iterator = LIVE_DYNAMIC_COVERS.iterator(); iterator.hasNext(); ) {
            if (iterator.next().location.equals(installed.location)) iterator.remove();
        }
        LIVE_DYNAMIC_COVERS.addFirst(installed);
        while (LIVE_DYNAMIC_COVERS.size() > MAX_LIVE_DYNAMIC_COVERS) {
            LiveDynamicCover stale = LIVE_DYNAMIC_COVERS.removeLast();
            TextureManager.getInstance().deleteTexture(stale.location);
            synchronized (DYNAMIC_COVER_ATTEMPTS) {
                DYNAMIC_COVER_ATTEMPTS.remove(stale.key);
            }
        }
    }

    /** 真的是动画(至少两帧)才算动态封面;单帧结果和静态封面没有区别。 */
    private static boolean isAnimated(ITextureObject texture) {
        return texture instanceof AnimatedCoverTexture && ((AnimatedCoverTexture) texture).getFrameCount() > 1;
    }

    private static void discard(ITextureObject texture) {
        try {
            if (texture instanceof AbstractTexture) {
                ((AbstractTexture) texture).deleteGlTexture();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 视频 URL 专用的候选选择。与图片分支相反:这里只要视频。 */
    private static String findDynamicVideoUrl(JsonElement element) {
        List<DynamicUrlCandidate> candidates = new ArrayList<DynamicUrlCandidate>();
        collectUrlCandidates(element, "", candidates);
        DynamicUrlCandidate best = null;
        for (DynamicUrlCandidate candidate : candidates) {
            if (!isVideoUrl(candidate.url)) {
                continue;
            }
            if (best == null || candidate.score > best.score) {
                best = candidate;
            }
        }
        return best == null ? "" : best.url;
    }

    /** 按上限写盘,超限直接放弃(返回 0)而不是留半个文件让 ffmpeg 去猜。 */
    private static long writeAtMost(InputStream stream, File target, long maxBytes) throws IOException {
        if (stream == null) {
            return 0L;
        }
        long total = 0L;
        try (OutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    return 0L;
                }
                output.write(buffer, 0, read);
            }
        }
        return total;
    }

    static BufferedImage gaussianBlur(BufferedImage image, int blur) {
        Map<RenderingHints.Key, Object> map = new HashMap<>();
        map.put(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        map.put(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        map.put(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        RenderingHints hints = new RenderingHints(map);

        ConvolveOp operation = new ConvolveOp(GAUSSIAN_KERNEL, ConvolveOp.EDGE_NO_OP, hints);
        BufferedImage filtered = operation.filter(image, null);
        BufferedImage output = new BufferedImage(filtered.getWidth(), filtered.getHeight(), filtered.getType());
        Graphics2D graphics = (Graphics2D) output.getGraphics();
        try {
            graphics.setRenderingHints(map);
            graphics.drawImage(filtered, -blur, -blur, filtered.getWidth() + blur * 2,
                    filtered.getHeight() + blur * 2, null);
        } finally {
            // AWT Graphics 是重量级原生资源，不 dispose 会积累到 GC 才释放。
            graphics.dispose();
        }
        return output;
    }

    private static boolean shouldLoadCover(TextureManager textureManager, Location coverLocation,
                                           boolean forceReload) {
        return textureManager.getTexture(coverLocation) == null || forceReload;
    }

    /**
     * 在后台线程上解析一条曲目的封面。
     *
     * <p>GD 走聚合源；其余先取平台自带的那张，取不到（有些第三方源就是没有专辑图）才问
     * 自定义源 —— 宿主扩展，默认关。酷我搜索响应里本来就没有封面（上游同样 img:null），
     * 走宿主移植的上游 kw/pic.js 单点查询。调用点已经在 {@code MultiThreadingUtil.runAsync}
     * 的后台线程上，问一次脚本/接口不会卡住渲染。</p>
     */
    private static String coverUrl(Music music, int size) {
        if (music != null && music.isGd()) {
            return music.resolveGdCoverUrl(size);
        }
        if (music == null) return "";
        String base = music.getCoverUrl(size);
        if (!base.isEmpty()) return base;
        String custom = music.resolveLxCoverUrl();
        if (!custom.isEmpty()) return custom;
        if (music.isKuwo()) {
            // 上游 pic.web 返回的就是最终直链（常见 http，酷我 star CDN 没配 TLS），原样用。
            String pic = KuwoMusicService.getPicUrl(music.getSourceId());
            if (!pic.isEmpty()) return pic;
        }
        return base;
    }

    private static void loadMainCoverAsync(Music music, Location musicCover, Location musicCoverBlur) {
        MultiThreadingUtil.runAsync(() -> {
            try {
                @Cleanup
                InputStream coverStream = HttpUtils.downloadStream(coverUrl(music, 320), 5,
                        music != null && music.isYouTube() ? YouTubeProxySettings.createHttpProxy() : null);
                byte[] imageData = IOUtils.toByteArray(coverStream);
                BufferedImage coverImage = DynamicTexture.readImage(new ByteArrayInputStream(imageData));
                if (coverImage != null) {
                    loadCoverTextures(music, coverImage, musicCover, musicCoverBlur);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    private static void loadCoverTextures(Music music, BufferedImage coverImage,
                                          Location musicCover, Location musicCoverBlur) {
        int[] palette = CoverPalette.extract(coverImage, 6);
        if (music != null) {
            music.setCoverPalette(palette);
        }
        if (music == CloudMusic.currentlyPlaying) {
            CoverThemePalette.set(palette);
        }
        Textures.loadTexture(musicCover, coverImage);
        // 低优先级池：31 半径的高斯模糊是纯 CPU 卷积，一张 320×320 的封面就能吃满一个核几十毫秒。
        BackgroundWork.submit(() -> Textures.loadTexture(musicCoverBlur, blurCover(coverImage)));
    }

    /**
     * 封面 → 背景用的那张模糊图。31 半径高斯，与 {@link #loadCoverTextures} 一直同一份实现。
     *
     * <p>规范成一张 ARGB 副本再卷积：一是 {@code ConvolveOp.filter(image, null)} 会沿用源图
     * 的 colorModel，JPEG 封面（TYPE_3BYTE_BGR）直接卷会产出 BGR 结果，下游纹理上传和
     * 测试都假定 ARGB；二是把「输出与输入互相独立」变成显式保证，不依赖 filter 不改源图
     * 这一实现细节。相比旧版（预拷贝 + 卷积产出）少了一次整图分配。</p>
     */
    static BufferedImage blurCover(BufferedImage coverImage) {
        if (coverImage == null) {
            return null;
        }
        BufferedImage argb = new BufferedImage(coverImage.getWidth(), coverImage.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = argb.createGraphics();
        try {
            g.drawImage(coverImage, 0, 0, null);
        } finally {
            g.dispose();
        }
        return gaussianBlur(argb, 31);
    }

    private static void loadSmallCoverAsync(Music music, Location musicCoverSmall) {
        MultiThreadingUtil.runAsync(() -> {
            // 与主封面路径同款 try-with-resources：不 close 的话每首小封面都漏一条网络连接，
            // DynamicTexture.readImage 只读不关。
            try (InputStream smallCoverStream = HttpUtils.downloadStream(coverUrl(music, 128), 5,
                    music != null && music.isYouTube() ? YouTubeProxySettings.createHttpProxy() : null)) {
                BufferedImage smallCoverImage = DynamicTexture.readImage(smallCoverStream);
                Textures.loadTexture(musicCoverSmall, smallCoverImage);
            } catch (Throwable t) {
                System.err.println("[Music] 小封面加载失败: " + t);
            }
        });
    }

    private static ITextureObject decodeDynamicCover(byte[] imageBytes) throws IOException {
        if (imageBytes == null || imageBytes.length == 0) {
            return null;
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes))) {
            java.util.Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, false);
                int count;
                try {
                    count = Math.min(MAX_DYNAMIC_COVER_FRAMES, Math.max(1, reader.getNumImages(true)));
                } catch (Throwable ignored) {
                    count = 1;
                }
                List<BufferedImage> frames = new ArrayList<BufferedImage>(count);
                List<Long> durations = new ArrayList<Long>(count);
                for (int index = 0; index < count; index++) {
                    BufferedImage frame = reader.read(index);
                    if (frame == null) {
                        continue;
                    }
                    frames.add(scaleDown(frame));
                    durations.add(readFrameDuration(reader.getImageMetadata(index)));
                }
                if (frames.isEmpty()) {
                    return null;
                }
                return frames.size() > 1 ? new AnimatedCoverTexture(frames, durations)
                        : new DynamicTexture(frames.get(0), true, true);
            } finally {
                reader.dispose();
            }
        }
    }

    private static BufferedImage scaleDown(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int max = Math.max(width, height);
        if (max <= MAX_DYNAMIC_COVER_DIMENSION && source.getType() == BufferedImage.TYPE_INT_ARGB) {
            return source;
        }
        double scale = max <= MAX_DYNAMIC_COVER_DIMENSION ? 1.0 : MAX_DYNAMIC_COVER_DIMENSION / (double) max;
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));
        BufferedImage result = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private static long readFrameDuration(IIOMetadata metadata) {
        if (metadata == null) {
            return 100L;
        }
        try {
            Node root = metadata.getAsTree("javax_imageio_gif_image_1.0");
            Node child = root.getFirstChild();
            while (child != null) {
                if ("GraphicControlExtension".equals(child.getNodeName()) && child.getAttributes() != null
                        && child.getAttributes().getNamedItem("delayTime") != null) {
                    return Math.max(40L, Long.parseLong(child.getAttributes().getNamedItem("delayTime").getNodeValue()) * 10L);
                }
                child = child.getNextSibling();
            }
        } catch (Throwable ignored) {
            // 非 GIF 的图像读取器不会暴露 GIF 元数据树。
        }
        return 100L;
    }

    private static byte[] readAtMost(InputStream stream, int maxBytes) throws IOException {
        if (stream == null) {
            return new byte[0];
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = stream.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("dynamic cover exceeds size limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String findDynamicCoverUrl(JsonElement element) {
        List<DynamicUrlCandidate> candidates = new ArrayList<DynamicUrlCandidate>();
        collectUrlCandidates(element, "", candidates);
        DynamicUrlCandidate best = null;
        for (DynamicUrlCandidate candidate : candidates) {
            if (isVideoUrl(candidate.url)) {
                continue;
            }
            if (best == null || candidate.score > best.score) {
                best = candidate;
            }
        }
        return best == null ? "" : best.url;
    }

    private static void collectUrlCandidates(JsonElement element, String path, List<DynamicUrlCandidate> candidates) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String value = element.getAsString();
            if (value.startsWith("http://") || value.startsWith("https://")) {
                String lowerPath = path.toLowerCase();
                int score = 1;
                if (lowerPath.contains("dynamic")) score += 100;
                if (lowerPath.contains("cover")) score += 40;
                if (lowerPath.contains("image") || lowerPath.contains("pic")) score += 25;
                if (lowerPath.contains("url")) score += 10;
                candidates.add(new DynamicUrlCandidate(value, score));
            }
            return;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int index = 0; index < array.size(); index++) {
                collectUrlCandidates(array.get(index), path + "[]", candidates);
            }
            return;
        }
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                collectUrlCandidates(entry.getValue(), path + "." + entry.getKey(), candidates);
            }
        }
    }

    private static boolean isVideoUrl(String url) {
        String lower = url == null ? "" : url.toLowerCase();
        return lower.contains(".mp4") || lower.contains(".m3u8") || lower.contains(".flv") || lower.contains(".webm");
    }

    /** 已装载的动态封面:位置用于删纹理,stableKey 用于撤销尝试记录。 */
    private static final class LiveDynamicCover {
        private final String key;
        private final Location location;

        private LiveDynamicCover(String key, Location location) {
            this.key = key;
            this.location = location;
        }
    }

    private static final class DynamicUrlCandidate {
        private final String url;
        private final int score;

        private DynamicUrlCandidate(String url, int score) {
            this.url = url;
            this.score = score;
        }
    }
}
