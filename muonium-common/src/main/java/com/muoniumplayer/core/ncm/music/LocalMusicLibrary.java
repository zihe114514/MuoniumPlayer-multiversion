package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.dto.Album;
import com.muoniumplayer.core.ncm.music.dto.Artist;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 本地音乐库：用户注册进来的磁盘目录，以及「把目录里的音频文件变成可播放的 {@link Music} 行」。
 *
 * <p>链路是：「导入本地文件夹」把一个绝对路径登记进来（{@link #addDirectory}）→ 扫描目录下
 * 支持的音频文件（{@link #scanDirectory}，只读一层，不递归——递归会把整个音乐库的子目录
 * 一次性灌进歌单，用户没法控制粒度）→ 逐个读标签（{@link LocalAudioMetadata}）→
 * 标签缺什么就用文件名兜底 → 构造 {@code MusicPlatform.LOCAL} 的行交给
 * {@code LocalPlaylistStore} 落进歌单。</p>
 *
 * <p><b>身份与缓存键的稳定性</b>：{@code Music.id} 取「绝对路径的规范化小写」的稳定哈希
 * （与酷狗 hash 同一套 {@code stableLong} 语义）——同一个文件重命名前后是两首不同的歌
 * （路径就是它的主键），同一个文件在两次启动之间必须是同一首（歌词/封面缓存按
 * {@code getStableKey()} 落盘，键换了缓存就全废了）。Windows 的路径大小写不敏感，
 * 所以先 {@code toLowerCase(Locale.ROOT)} 再哈希，避免「C:\Music\A.flac」和
 * 「c:\music\a.flac」被当成两首。</p>
 *
 * <p><b>防御性</b>：目录消失/没有读权限返回空列表而不是抛错；单个文件解析失败只让这一首
 * 退化成「文件名当标题」；绝不写入用户音乐目录（封面提取落在
 * {@link ConfigPaths#LOCAL_ART_DIR}，AAC 解码边车由播放链路重定向到模组缓存）。</p>
 */
public final class LocalMusicLibrary {

    private static final Object LOCK = new Object();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** 支持的音频扩展名，与 {@code AudioContainerSupport.SUPPORTED_CONTAINERS} 对齐（ogg 明确不支持）。 */
    private static final Set<String> AUDIO_EXTENSIONS = new LinkedHashSet<>(Arrays.asList(
            "mp3", "flac", "wav", "m4a", "aac", "mp4"));

    private static boolean loaded;
    /** 保持插入顺序；重复添加按「目录已登记」处理而不是报错。 */
    private static final List<String> DIRECTORIES = new ArrayList<>();

    private LocalMusicLibrary() {
    }

    // ════════════════════════════ 目录注册 ════════════════════════════

    /**
     * 登记一个音乐目录并立刻落盘。已登记过返回 false；目录不存在也会登记——
     * 用户可能先登记「稍后插入的移动硬盘」，播放时文件缺失自然安全失败。
     */
    public static boolean addDirectory(String path) {
        String normalized = normalizeDirectory(path);
        if (normalized == null) return false;
        synchronized (LOCK) {
            load();
            if (DIRECTORIES.contains(normalized)) return false;
            DIRECTORIES.add(normalized);
            save();
        }
        return true;
    }

    public static void removeDirectory(String path) {
        String normalized = normalizeDirectory(path);
        if (normalized == null) return;
        synchronized (LOCK) {
            load();
            if (DIRECTORIES.remove(normalized)) {
                save();
            }
        }
    }

    /** 全部已登记目录的快照，按登记顺序。 */
    public static List<String> directories() {
        synchronized (LOCK) {
            load();
            return new ArrayList<>(DIRECTORIES);
        }
    }

    // ════════════════════════════ 扫描 ════════════════════════════

    /** 这个扩展名是不是支持的音频容器。 */
    public static boolean isAudioFile(File file) {
        if (file == null || !file.isFile()) return false;
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        return AUDIO_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * 扫描一个目录，返回可播放的 {@link Music} 行。<b>只读一层，不递归</b>。
     *
     * <p>目录消失/没有读权限返回空列表——调用方（UI 层）负责把「一个文件都没扫到」
     * 的归因说清楚，这里只保证不抛。</p>
     *
     * <p>行按文件名排序，而不是文件系统给的原序：后者在 Windows 上碰巧是字典序，
     * 但那是实现细节不是契约，排序让两次扫描的顺序可预期。</p>
     */
    public static List<Music> scanDirectory(String path) {
        List<Music> result = new ArrayList<>();
        String normalized = normalizeDirectory(path);
        if (normalized == null) return result;
        File directory = new File(normalized);
        File[] files = directory.listFiles();
        if (files == null) return result;
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File file : files) {
            if (!isAudioFile(file)) continue;
            try {
                Music music = fromLocalFile(file);
                if (music != null) result.add(music);
            } catch (Throwable failure) {
                // 一首怪文件不能让整个目录导入失败；行构造本身已经处处兜底，这里是最后一张网。
                System.err.println("[Music/Local] Skip unscannable file " + file.getName()
                        + ": " + failure.getMessage());
            }
        }
        return result;
    }

    /**
     * 由一个本地音频文件构造 {@link Music} 行。文件存在性交给调用方（扫描路径上必然存在；
     * 播放路径上缺失时会由取流分支报「文件已移动或删除」）。
     *
     * <p>标签优先、文件名兜底：标题缺了用「去掉扩展名的文件名」顶上（绝大多数本地文件的
     * 文件名本身就是歌名），歌手/专辑缺了就空着——界面本就允许 Unknown。</p>
     */
    public static Music fromLocalFile(File file) {
        if (file == null) return null;
        String identity = fileIdentity(file);
        long stableId = stableLong(identity);

        LocalAudioMetadata.Result meta = LocalAudioMetadata.read(file);
        String title = meta != null && !meta.title.isEmpty()
                ? meta.title : stripExtension(file.getName());
        String artist = meta == null ? "" : meta.artist;
        String album = meta == null ? "" : meta.album;
        long duration = meta == null ? 0L : meta.durationMillis;

        List<Artist> artistList = new ArrayList<>();
        if (!artist.trim().isEmpty()) {
            // 本地标签的歌手写法五花八门（"、""/"，";"），按在线平台同一套切法拆。
            for (String artistName : artist.split("\\s*[、,;/]\\s*")) {
                if (!artistName.trim().isEmpty()) {
                    artistList.add(new Artist(stableLong(identity + ":artist:" + artistName),
                            artistName.trim(), new ArrayList<>(), new ArrayList<>()));
                }
            }
        }
        if (artistList.isEmpty()) {
            // 占位名必须是 "Unknown"：AMLL 的匹配层认这个值（artistsOf 会把它排除掉），
            // 换成别的字符串会把「标签没写歌手」的文件当成歌手叫 "Local" 的歌去匹配。
            artistList.add(new Artist(0L, "Unknown", new ArrayList<>(), new ArrayList<>()));
        }

        Album albumDto = new Album(stableLong(identity + ":album:" + album), album, "",
                new ArrayList<>());

        Music music = new Music(title, title, "", stableId, artistList, new ArrayList<>(),
                albumDto, duration, 0L, 0L, new ArrayList<>());
        music.setSource(MusicPlatform.LOCAL);
        music.setSourceId(identity);
        music.setSourceMid("");
        music.setForeignCover(true); // 封面走本地提取，绝不该按 URL 下载。
        return music;
    }

    /** 由一行 LOCAL Music 反查它在磁盘上的文件；缺失/不存在返回 null。 */
    public static File fileOf(Music music) {
        if (music == null || music.getSource() != MusicPlatform.LOCAL) return null;
        String identity = music.getSourceId();
        if (identity == null || identity.isEmpty()) return null;
        // sourceId 存的就是规范化路径；老存档里若是哈希（不会发生，但防御），按缺失处理。
        if (identity.indexOf(File.separatorChar) < 0 && identity.indexOf('/') < 0) return null;
        File file = new File(identity);
        return file.isFile() ? file : null;
    }

    /**
     * 提取这首歌的嵌入封面，落成渲染链能读到的 PNG。
     *
     * <p>落点是 {@code Music#getCoverLocation()} 的既有键位
     * （{@code textures/music/<stableKey>/cover.png}）之下的物理文件——封面加载链只认
     * 那个 Location，我们提前把字节写到同一个键上，链路无需任何分支。目标已存在时
     * 直接复用（同一首歌重复点播不该反复解图）；提取不到返回 false（界面落字母兜底）。</p>
     */
    public static boolean ensureCoverExtracted(Music music) {
        if (music == null || music.getSource() != MusicPlatform.LOCAL) return false;
        File audio = fileOf(music);
        if (audio == null) return false;
        File target = physicalCoverFile(music);
        if (target == null) return false;
        if (target.isFile() && target.length() > 0L) return true;
        try {
            LocalAudioMetadata.Result meta = LocalAudioMetadata.read(audio);
            if (meta == null || meta.coverImage == null || meta.coverImage.length < 64) {
                return false;
            }
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(meta.coverImage));
            if (image == null) return false;
            Files.createDirectories(target.getParentFile().toPath());
            // 先写临时文件再原子替换：写到一半被杀会留下截断的 PNG，渲染链读到的就是坏纹理。
            File temp = new File(target.getParentFile(), target.getName() + ".tmp");
            ImageIO.write(image, "png", temp);
            try {
                Files.move(temp.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException fallback) {
                Files.move(temp.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException failure) {
            System.err.println("[Music/Local] Cover extract failed for " + music.getStableKey()
                    + ": " + failure.getMessage());
            return false;
        }
    }

    /** 这首歌的提取封面落点（可能尚不存在——先调 {@link #ensureCoverExtracted}）。 */
    public static File coverFileOf(Music music) {
        File target = physicalCoverFile(music);
        return target == null ? new File(ConfigPaths.LOCAL_ART_DIR, "missing.png") : target;
    }

    /** Location 是逻辑名；本地封面要落成物理文件，得按同一规则拼出物理路径。 */
    private static File physicalCoverFile(Music music) {
        // Music.getCoverLocation() = "muonium/textures/music/<stableKey>/cover.png"。
        // 纹理根在 assets 里，但 DownloadDynamicIsland / 静态封面的运行时贴图走的是
        // 可写目录；这里沿用本地缓存的目录约定，落 ConfigPaths.LOCAL_ART_DIR，
        // 由封面加载链的 LOCAL 分支显式指向这里（见 MusicCoverService）。
        try {
            File dir = new File(ConfigPaths.LOCAL_ART_DIR, music.getStableKey());
            return new File(dir, "cover.png");
        } catch (Throwable failure) {
            return null;
        }
    }

    // ════════════════════════════ 身份 ════════════════════════════

    /**
     * 文件的稳定身份：绝对路径的规范化小写。
     *
     * <p>存进 {@code Music.sourceId} 与落盘存档。用路径而不是内容哈希是刻意的：
     * 内容哈希要在导入时读一遍每个文件的完整字节（一个 10GB 的目录扫一次要几分钟），
     * 而路径对「同一个文件两次启动必须是同一首」已经足够——文件被移动/重命名，
     * 用户视角它就是变了位置，缓存键换掉并无不妥。</p>
     */
    static String fileIdentity(File file) {
        try {
            return file.getAbsoluteFile().getCanonicalPath().toLowerCase(Locale.ROOT)
                    .replace('\\', '/');
        } catch (IOException broken) {
            // getCanonicalPath 在盘符级故障下才抛；退回绝对路径，身份稳定性让位于可用性。
            return file.getAbsoluteFile().getPath().toLowerCase(Locale.ROOT).replace('\\', '/');
        }
    }

    private static String normalizeDirectory(String path) {
        if (path == null) return null;
        String trimmed = path.trim();
        if (trimmed.isEmpty()) return null;
        File file = new File(trimmed);
        try {
            return file.getAbsoluteFile().getCanonicalPath();
        } catch (IOException broken) {
            return file.getAbsoluteFile().getPath();
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 与 {@code Music} 私有 stableLong 同一套 FNV-1a：身份语义必须完全一致。 */
    private static long stableLong(String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        long hash = 0xcbf29ce484222325L;
        for (byte b : bytes) {
            hash ^= b & 0xffL;
            hash *= 0x100000001b3L;
        }
        return hash & Long.MAX_VALUE;
    }

    // ════════════════════════════ 持久化 ════════════════════════════

    private static void load() {
        if (loaded) return;
        loaded = true;
        DIRECTORIES.clear();
        try {
            JsonObject root = JsonConfigStorage.readObject(ConfigPaths.LOCAL_LIBRARY, GSON);
            if (root == null) return;
            JsonArray items = root.getAsJsonArray("directories");
            if (items == null) return;
            for (JsonElement element : items) {
                if (element == null || element.isJsonNull()) continue;
                String path = element.getAsString();
                if (path != null && !path.trim().isEmpty()) {
                    DIRECTORIES.add(path.trim());
                }
            }
        } catch (Throwable ignored) {
            // 读坏了当没有登记目录；用户重新导入一次即可，不能因此卡启动。
            DIRECTORIES.clear();
        }
    }

    private static void save() {
        try {
            JsonArray items = new JsonArray();
            for (String directory : DIRECTORIES) {
                items.add(directory);
            }
            JsonObject root = new JsonObject();
            root.add("directories", items);
            JsonConfigStorage.writeObject(ConfigPaths.LOCAL_LIBRARY, GSON, root);
        } catch (Throwable ignored) {
            // 写失败只影响下次启动记不记得这个目录；当场不失败。
        }
    }
}
