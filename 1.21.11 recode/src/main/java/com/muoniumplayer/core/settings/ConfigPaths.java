package com.muoniumplayer.core.settings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * MuoniumPlayer 持久化路径的集中注册表。
 *
 * <p>本模组自有的文件从不直接放在游戏目录下。所有内容都保存在
 * {@code config/muonium/} 下，这是 Minecraft 模组启动器和配置备份工具
 * 所期望的布局。旧的根目录文件和目录在首次使用时迁移；新位置已存在的
 * 文件始终优先。</p>
 */
public final class ConfigPaths {

    /** 唯一由本模组自有的根目录。所有持久化状态都挂在此目录下。 */
    private static final File CONFIG_DIRECTORY = new File("config", "muonium");

    public static final File HUD = new File(CONFIG_DIRECTORY, "hud.json");
    public static final File PLAYER = new File(CONFIG_DIRECTORY, "player.json");
    public static final File THEME = new File(CONFIG_DIRECTORY, "theme.json");

    public static final Path MUSIC_AUTH = CONFIG_DIRECTORY.toPath().resolve("music_auth.json");
    public static final File NETEASE_ACCOUNTS = new File(CONFIG_DIRECTORY, "netease_accounts.json");

    /** 旧版网易云 cookie 回退，与 Cadence 的凭据存储分开保存。 */
    public static final File NETEASE_COOKIE = new File(CONFIG_DIRECTORY, "netease_cookie.txt");

    /** 用户选择的 GD Studio 聚合平台（netease/joox/...），单独持久化。 */
    public static final File YOUTUBE_PROXY = new File(CONFIG_DIRECTORY, "youtube_proxy.json");
    /** Automix（无缝曲目交接）偏好设置。 */
    public static final File GD_SOURCE = new File(CONFIG_DIRECTORY, "gd_source.json");

    /** 用户提供的 ffmpeg 可执行文件。 */
    public static final File KUGOU_AUTH = new File(CONFIG_DIRECTORY, "kugou_auth.json");

    /** 本模组自有的缓存。将其放在游戏根目录之外，但与小型 JSON 设置分开。 */
    public static final File QISHUI_AUTH = new File(CONFIG_DIRECTORY, "qishui_auth.json");

    /** 已下载并解码的音频文件。该目录可能增长到数 GB。 */
    public static final File LISTEN_TOGETHER = new File(CONFIG_DIRECTORY, "listen_together.json");

    /** Skija 本地库的确定性回退解压目录。 */
    public static final File NETEASE_UNBLOCK = new File(CONFIG_DIRECTORY, "netease_unblock.json");

    /** 首次运行设置门控。单独保存，使引导状态不会损坏播放器偏好。 */
    public static final File AUTOMIX = new File(CONFIG_DIRECTORY, "automix.json");

    /**
     * 仅在新文件不存在时迁移。已有的新文件始终优先，
     * 因此用户可安全地在旁边保留旧配置的备份。
     */
    public static final File BIN_DIR = new File(CONFIG_DIRECTORY, "bin");

    // 由开发版构建产生的中间 Muonium 命名。
    public static final File CACHE_DIR = new File(CONFIG_DIRECTORY, "cache");

    // 归一化之前的发布名称，过去曾位于游戏根目录。
    public static final File MUSIC_CACHE = new File(CACHE_DIR, "music");

    /**
     * 移动旧目录。若目标已存在，则逐个合并子项；
     * 冲突项保留在旧位置，而不被覆盖。
     */
    public static final File LYRIC_CACHE = new File(CACHE_DIR, "lyrics");

    // 尽力而为即可：被锁定的文件直接留待下次启动处理。
    public static final File LYRIC_OFFSETS = new File(CONFIG_DIRECTORY, "lyric_offsets.json");

    /**
     * 每首歌手动指定的歌词来源。
     *
     * <p>与 {@link #LYRIC_OFFSETS} 同一类东西——都是「这首歌的歌词不对」时用户手动留下的补偿，
     * 一样要跨切歌与重启存活，所以放同一个位置、同样的尽力而为语义。</p>
     */
    public static final File LYRIC_SOURCES = new File(CONFIG_DIRECTORY, "lyric_sources.json");

    /** Skija 原生库的确定性回退解压目录。 */
    public static final File SKIJA_NATIVE_ROOT = new File(CONFIG_DIRECTORY, "native/skija");

    // 只读/被锁定的旧文件不应阻止模组加载。
    public static final File FFMPEG_NOTICE = new File(CONFIG_DIRECTORY, "ffmpeg_notice.json");

    /**
     * 更新提示的开关、被跳过的版本号与远端清单缓存。
     *
     * <p>与 {@link #FFMPEG_NOTICE} 同理单独一个文件：这个提示同样要在<b>主菜单</b>就决定要不要弹，
     * 读失败的代价只是「多打一次网络请求、多提示一次」，不该有机会影响任何别的设置。
     * 把 {@code enabled} 改成 {@code false} 就是彻底关掉更新检查（连网络都不会打）。</p>
     */
    public static final File UPDATE_NOTICE = new File(CONFIG_DIRECTORY, "update_notice.json");

    /**
     * 界面字体的选择（哪一款系统字体或哪一个字体文件）。
     *
     * <p>与 {@link #FFMPEG_NOTICE} 同理单独一个文件：字体要在 {@code FontManager.init()} 里就读到，
     * 那时 HUD 配置那一整套组件还没建起来；而读失败的代价仅是回到内置字体，不该有机会影响
     * 任何别的设置。</p>
     */
    public static final File UI_FONT = new File(CONFIG_DIRECTORY, "ui_font.json");

    /**
     * 用户自备字体的存放目录。
     *
     * <p>「选择外部字体」会把选中的文件<b>复制</b>到这里再引用副本，而不是记住原路径：原文件
     * 可能在 U 盘上、可能被改名，而字体是每次启动都要读的东西，指向一个会消失的路径就等于
     * 下次启动静默回退成内置字体。用户也可以自己往这个目录里丢 ttf/otf，字体列表会直接列出来。</p>
     */
    public static final File FONT_DIR = new File(CONFIG_DIRECTORY, "fonts");

    /**
     * 已导入的 LX Music 自定义源脚本，以及当前启用的是哪一个。
     *
     * <p>与 {@link #FFMPEG_NOTICE} 同理单独一个文件，但理由更硬：里面存的是用户导入的
     * <b>脚本正文</b>（压缩后的 base64，单个可能几十 KB）。把它们摆进 {@link #PLAYER}
     * 会让播放器主配置胀成百 KB，而那个文件是每次改设置都要重写一遍的；
     * 更关键的是一份写坏的脚本存档不能有机会把整个主配置一起拘进去。</p>
     */
    public static final File LX_SOURCES = new File(CONFIG_DIRECTORY, "lx_sources.json");

    /** 首次运行设置门控。独立保存，使引导状态不会污染播放器偏好设置。 */
    public static final File ONBOARDING = new File(CONFIG_DIRECTORY, "onboarding.json");

    static {
        migrateLegacyFiles();
    }

    private ConfigPaths() {
    }

    /**
     * 仅在新文件不存在时迁移。已有的新文件始终优先，
     * 因此用户可以安全地在旁边保留旧配置的备份。
     */
    private static void migrateLegacyFiles() {
        migrate(new File("music_hud.json"), HUD);
        migrate(new File("_player_config.json"), PLAYER);
        migrate(new File("_player_theme.json"), THEME);

        // 开发构建创建的中间 Muonium 名称。
        migrate(new File("muonium_hud.json"), HUD);
        migrate(new File("muonium_player_config.json"), PLAYER);
        migrate(new File("muonium_player_theme.json"), THEME);

        // 规范化之前的发布名称，过去位于游戏根目录。
        migrate(new File("muoniumplayer_hud.json"), HUD);
        migrate(new File("muoniumplayer_config.json"), PLAYER);
        migrate(new File("muoniumplayer_theme.json"), THEME);
        migrate(new File("NCMCookie.txt"), NETEASE_COOKIE);
        migrateDirectory(new File("MusicCache"), MUSIC_CACHE);

        migrate(Paths.get("config", "", "music_auth.json").toFile(), MUSIC_AUTH.toFile());
        migrate(Paths.get("config", "", "netease_accounts.json").toFile(), NETEASE_ACCOUNTS);
    }

    private static void migrate(File legacy, File target) {
        if (legacy == null || target == null || !legacy.isFile() || target.exists()) {
            return;
        }
        if (!ensureDirectory(target.getParentFile())) {
            return;
        }
        move(legacy, target);
    }

    /**
     * 移动旧版目录。如果目标已存在，则逐个合并子项；
     * 冲突项保留在旧位置而不是被覆盖。
     */
    private static void migrateDirectory(File legacy, File target) {
        if (legacy == null || target == null || !legacy.isDirectory()) {
            return;
        }
        if (!target.exists()) {
            if (!ensureDirectory(target.getParentFile())) {
                return;
            }
            move(legacy, target);
            return;
        }
        if (!target.isDirectory()) {
            return;
        }

        File[] children = legacy.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            File childTarget = new File(target, child.getName());
            if (child.isDirectory()) {
                migrateDirectory(child, childTarget);
            } else {
                migrate(child, childTarget);
            }
        }
        File[] remaining = legacy.listFiles();
        if (remaining != null && remaining.length == 0) {
            // 保持尽力而为：被锁定的文件直接留到下次启动处理。
            // noinspection ResultOfMethodCallIgnored
            legacy.delete();
        }
    }

    private static void move(File legacy, File target) {
        try {
            Files.move(legacy.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveUnsupported) {
            try {
                Files.move(legacy.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // 只读/锁定的旧文件不应阻止 mod 加载。
            }
        }
    }

    private static boolean ensureDirectory(File directory) {
        return directory == null || directory.isDirectory() || directory.mkdirs();
    }
}
