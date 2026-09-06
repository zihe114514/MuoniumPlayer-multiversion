package com.muoniumplayer.core.settings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Central registry for MuoniumPlayer persisted paths.
 *
 * <p>All newly written files use the MuoniumPlayer names. On first startup after
 * when the new target does not already exist, preserving existing user settings.</p>
 */
public final class ConfigPaths {

    public static final File HUD = new File("muoniumplayer_hud.json");
    public static final File PLAYER = new File("muoniumplayer_config.json");
    public static final File THEME = new File("muoniumplayer_theme.json");
    public static final Path MUSIC_AUTH = Paths.get("config", "muonium", "music_auth.json");
    public static final File NETEASE_ACCOUNTS = new File("config", "muonium/netease_accounts.json");

    /** User-selected GD Studio aggregated platform (netease/joox/...), persisted separately. */
    public static final File GD_SOURCE = new File("config", "muonium/gd_source.json");

    /**
     * 酷狗的设备标识与登录凭证。
     *
     * <p>不能塞进 {@link #MUSIC_AUTH}：那个文件由 Cadence（{@code music-api} 库）的
     * {@code MusicCredentialStore} 独占读写，它只认识网易云 cookie 与 QQ 的 musicid/musickey，
     * 写进去的第三个平台会在它下一次保存时被整段丢掉。</p>
     */
    public static final File KUGOU_AUTH = new File("config", "muonium/kugou_auth.json");

    /**
     * 一起听的行为开关。
     *
     * <p>单独一个文件而不是塞进 {@link #PLAYER}：这些开关只在房间里有意义，读失败的代价是
     * 回到默认行为，不该有机会影响播放器主配置的读写。</p>
     */
    public static final File LISTEN_TOGETHER = new File("config", "muonium/listen_together.json");

    /**
     * 网易云灰色歌曲解灰的行为开关。
     *
     * <p>与 {@link #LISTEN_TOGETHER} 同理单独一个文件：这些开关只在「官方链路已经取不到流」
     * 之后才生效，读失败的代价仅是回到默认行为，不该有机会影响播放器主配置的读写。</p>
     */
    public static final File NETEASE_UNBLOCK = new File("config", "muonium/netease_unblock.json");

    /** Automix (seamless track handover) preferences. */
    public static final File AUTOMIX = new File("config", "muonium/automix.json");

    /**
     * 「没装 ffmpeg」全屏提示的「永不展示」选择。
     *
     * <p>刻意单独一个文件而不是塞进 {@link #HUD}：这个提示要在<b>主菜单</b>就决定要不要弹，
     * 那时 HUD 配置的读写路径还牵着一整套 HUD 组件；单独一个只有一个布尔值的小文件，读失败也
     * 只是「再提示一次」，不会影响任何别的设置。</p>
     */
    public static final File FFMPEG_NOTICE = new File("config", "muonium/ffmpeg_notice.json");

    /**
     * 更新提示的开关、被跳过的版本号与远端清单缓存。
     *
     * <p>与 {@link #FFMPEG_NOTICE} 同理单独一个文件：这个提示同样要在<b>主菜单</b>就决定要不要弹，
     * 读失败的代价只是「多打一次网络请求、多提示一次」，不该有机会影响任何别的设置。
     * 把 {@code enabled} 改成 {@code false} 就是彻底关掉更新检查（连网络都不会打）。</p>
     */
    public static final File UPDATE_NOTICE = new File("config", "muonium/update_notice.json");

    /**
     * 界面字体的选择（哪一款系统字体或哪一个字体文件）。
     *
     * <p>与 {@link #FFMPEG_NOTICE} 同理单独一个文件：字体要在 {@code FontManager.init()} 里就读到，
     * 那时 HUD 配置那一整套组件还没建起来；而读失败的代价仅是回到内置字体，不该有机会影响
     * 任何别的设置。</p>
     */
    public static final File UI_FONT = new File("config", "muonium/ui_font.json");

    /**
     * 用户自备字体的存放目录。
     *
     * <p>「选择外部字体」会把选中的文件<b>复制</b>到这里再引用副本，而不是记住原路径：原文件
     * 可能在 U 盘上、可能被改名，而字体是每次启动都要读的东西，指向一个会消失的路径就等于
     * 下次启动静默回退成内置字体。用户也可以自己往这个目录里丢 ttf/otf，字体列表会直接列出来。</p>
     */
    public static final File FONT_DIR = new File("config", "muonium/fonts");

    /**
     * 已导入的 LX Music 自定义源脚本，以及当前启用的是哪一个。
     *
     * <p>与 {@link #FFMPEG_NOTICE} 同理单独一个文件，但理由更硬：里面存的是用户导入的
     * <b>脚本正文</b>（压缩后的 base64，单个可能几十 KB）。把它们摆进 {@link #PLAYER}
     * 会让播放器主配置胀成百 KB，而那个文件是每次改设置都要重写一遍的；
     * 更关键的是一份写坏的脚本存档不能有机会把整个主配置一起拘进去。</p>
     */
    public static final File LX_SOURCES = new File("config", "muonium/lx_sources.json");

    /** First-run setup gate. Kept separate so onboarding state cannot corrupt player preferences. */
    public static final File ONBOARDING = new File("config", "muonium/onboarding.json");

    static {
        migrateLegacyFiles();
    }

    private ConfigPaths() {
    }

    /**
     * Migrates only when the new file is absent. Existing new files always win,
     * so a user can safely keep backups of the old configuration beside them.
     */
    private static void migrateLegacyFiles() {
        migrate(new File("music_hud.json"), HUD);
        migrate(new File("_player_config.json"), PLAYER);
        migrate(new File("_player_theme.json"), THEME);
        // Also migrate the intermediate Muonium names created by development builds.
        migrate(new File("muonium_hud.json"), HUD);
        migrate(new File("muonium_player_config.json"), PLAYER);
        migrate(new File("muonium_player_theme.json"), THEME);

        migrate(Paths.get("config", "", "music_auth.json").toFile(), MUSIC_AUTH.toFile());
        migrate(Paths.get("config", "", "netease_accounts.json").toFile(), NETEASE_ACCOUNTS);
    }

    private static void migrate(File legacy, File target) {
        if (legacy == null || target == null || !legacy.isFile() || target.exists()) {
            return;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            return;
        }
        try {
            Files.move(legacy.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveUnsupported) {
            try {
                Files.move(legacy.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // A read-only/locked legacy file should not prevent the mod from loading.
            }
        }
    }
}
