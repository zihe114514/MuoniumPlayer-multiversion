package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.texture.Textures;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.other.multithreading.BackgroundWork;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;

/**
 * 外部播放器自己那张封面图的落地处。
 *
 * <p>链路是：助手（{@code SmtcWatcher.cs}）在换歌那一拍把 SMTC 的 {@code Thumbnail} 读出来
 * 落成 {@code config/muonium/smtc/art-<hash>.jpg}，并把文件名写进状态行的 {@code art} 字段；
 * 这里看到文件名变了就去读、去解，最后在主线程装成纹理。</p>
 *
 * <p>为什么读文件在后台、装纹理在主线程：解一张 800x800 的 JPEG 是十几毫秒的 CPU 活，
 * 挂在渲染那一帧上不合适；而 {@code DynamicTexture} 要建 GL 资源，只能在渲染线程上做。
 * 这两条都是本模组里已经踩过的位置（静态封面下载、动态封面抽帧都是同一套分工）。</p>
 *
 * <p>优先级写在两处：清晰图在 {@link MusicCoverService#preferredCoverLocation}，背景模糊图与
 * 取色在 {@link CloudMusic#preferredBlurredCoverLocation}。外部有图就用外部那张现算的，没有就
 * 退回曲库那条（网易云 → 本平台 → 静态）。这里只负责「外部这张图现在有没有」。</p>
 */
public final class ExternalArtwork {

    /** 与曲库封面隔开一个命名空间，两边永远不会互相顶掉。 */
    private static final String NAMESPACE = "muonium/textures/smtc/";
    /** 单个封面文件的上限。助手侧本来就限 4MB，这里只是防它被别的进程写坏。 */
    private static final long MAX_ART_BYTES = 8L * 1024L * 1024L;

    /** 已经交给后台去读的那个文件名。 */
    private static volatile String requestedFile = "";
    /** 后台解好、等主线程去装的那张图，以及它属于哪个文件。 */
    private static volatile BufferedImage decoded;
    private static volatile String decodedFile = "";
    /** 已经装成纹理的那张图。空串表示当前没有外部封面。 */
    private static volatile String loadedFile = "";
    private static volatile Location loadedLocation;
    /** 由同一张外部封面算出来的那张背景模糊图（全屏页与桌面信息条的底）。 */
    private static volatile Location loadedBlurredLocation;

    private ExternalArtwork() {
    }

    /**
     * 主线程每拍调用（由 {@link ExternalPlayback#onClientTick()} 驱动）。
     *
     * <p>整段包在 try 里：它读的是一个外部进程写的文件，任何一步出错都只该让「这一首没有
     * 外部封面」，不该把镜像本身或者游戏带下去。</p>
     */
    static void onSnapshot(ExternalSnapshot snapshot) {
        try {
            String file = snapshot == null ? "" : snapshot.artFile();
            if (file.isEmpty()) {
                // 这一首没图：把上一首的立刻撤掉。留着会出现「新歌挂着旧歌的封面」，
                // 比干脆没有封面更糟。
                drop();
                return;
            }
            if (file.equals(loadedFile)) return;
            if (!file.equals(requestedFile)) {
                // 换图了：先把旧的撤下来，宁可空一拍也不要拿上一首的顶着。
                drop();
                requestedFile = file;
                final String target = file;
                MultiThreadingUtil.runAsync(() -> decode(target));
            }
            BufferedImage image = decoded;
            if (image != null && file.equals(decodedFile)) {
                decoded = null;
                decodedFile = "";
                install(file, image);
            }
        } catch (Throwable failure) {
            System.err.println("[Music/SMTC] 外部封面处理异常: " + failure);
        }
    }

    private static void decode(String file) {
        try {
            File source = new File(ConfigPaths.SMTC_DIR, file);
            if (!source.isFile() || source.length() <= 0L || source.length() > MAX_ART_BYTES) {
                return;
            }
            BufferedImage image = MusicCoverService.decodeStillCover(Files.readAllBytes(source.toPath()));
            if (image == null) return;
            decoded = image;
            decodedFile = file;
        } catch (Throwable failure) {
            // 读不到就是没有这张封面，退回曲库那条链。
            System.out.println("[Music/SMTC] 外部封面读失败: " + failure);
        }
    }

    /** 只在主线程调用：把解好的图装成纹理，并顺手把背景那张模糊图与取色也算出来。 */
    private static void install(String file, BufferedImage image) {
        Location location = locationOf(file, "");
        Location blurred = locationOf(file, "_blurred");
        Music target = CloudMusic.currentlyPlaying;
        try {
            Textures.loadTexture(location, image, true, true);
            loadedFile = file;
            loadedLocation = location;
            loadedBlurredLocation = blurred;
            System.out.println("[Music/SMTC] 外部封面就绪: " + image.getWidth() + "x" + image.getHeight());
        } catch (Throwable failure) {
            System.err.println("[Music/SMTC] 外部封面装纹理失败: " + failure);
            return;
        }
        // 取色与 31 半径高斯都是几十毫秒的 CPU 活，跟曲库那条一样丢进低优先级池；
        // 做完要再认一次身份——这期间完全可能已经换歌，把上一首的色调推到新曲上比没色调更糟。
        final BufferedImage snapshot = image;
        BackgroundWork.submit(() -> {
            try {
                if (!file.equals(loadedFile)) return;
                int[] palette = CoverPalette.extract(snapshot, 6);
                if (target != null) target.setCoverPalette(palette);
                if (target == CloudMusic.currentlyPlaying) CoverThemePalette.set(palette);
                if (!file.equals(loadedFile)) return;
                Textures.loadTexture(blurred, MusicCoverService.blurCover(snapshot));
            } catch (Throwable failure) {
                System.err.println("[Music/SMTC] 外部封面取色/模糊失败: " + failure);
            }
        });
    }

    /** 文件名去掉后缀当 Location 的名，后缀本身对纹理没意义。 */
    private static Location locationOf(String file, String suffix) {
        int dot = file.lastIndexOf('.');
        String stem = dot > 0 ? file.substring(0, dot) : file;
        return Location.of(NAMESPACE + stem + suffix);
    }

    /** 撤掉当前这张外部封面（换歌、让位、会话结束都走这里）。 */
    private static void drop() {
        requestedFile = "";
        decoded = null;
        decodedFile = "";
        Location location = loadedLocation;
        Location blurred = loadedBlurredLocation;
        loadedFile = "";
        loadedLocation = null;
        loadedBlurredLocation = null;
        deleteQuietly(location);
        deleteQuietly(blurred);
    }

    private static void deleteQuietly(Location location) {
        if (location == null) return;
        try {
            TextureManager.getInstance().deleteTexture(location);
        } catch (Throwable ignored) {
            // 纹理没删成最多留一张没人引用的图，不影响正确性。
        }
    }

    /** 让位给内部播放器时调用。幂等。 */
    public static void clear() {
        drop();
    }

    /**
     * 这张外部封面现在该不该用。
     *
     * <p>只认「正在镜像的那一首」：拿引用比 {@code currentlyPlaying} 而不是比 stableKey，
     * 因为队列里、歌单行里的另一个 Music 实例即使键相同也不该被外部那张图顶掉 ——
     * 外部播放器只有一台，它对应的是当前这一首。</p>
     *
     * @return 有可用纹理时返回它的 Location，否则 {@code null}（调用方按原优先级继续往下找）
     */
    public static Location coverLocation(Music music) {
        return readyLocation(music, loadedLocation);
    }

    /**
     * 背景那张模糊图。判据与 {@link #coverLocation(Music)} 完全一致，只是它对应的是
     * 由外部封面现算的那张高斯模糊，而不是曲库下载后模糊的那张。
     *
     * <p>模糊比清晰图晚到几十到几百毫秒（它排在低优先级池里），所以这里必须查纹理在不在，
     * 不能只认「装过」——否则背景会在这一小段里读到一张不存在的纹理。</p>
     */
    public static Location blurredLocation(Music music) {
        return readyLocation(music, loadedBlurredLocation);
    }

    private static Location readyLocation(Music music, Location location) {
        if (!ExternalPlayback.isMirroring()) return null;
        if (location == null || loadedFile.isEmpty()) return null;
        if (music == null || music != CloudMusic.currentlyPlaying) return null;
        if (TextureManager.getInstance().getTexture(location) == null) return null;
        return location;
    }

    /** 给设置页与状态行用：这一首到底有没有拿到外部封面。 */
    public static boolean hasArtwork() {
        return !loadedFile.isEmpty();
    }
}
