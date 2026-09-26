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

    /**
     * YouTube 专用 HTTP 代理。
     *
     * <p>只作用于 YouTube InnerTube 请求、YouTube 音频 CDN 下载与 YouTube 封面下载；其他音源
     * 与更新检查不读取这个文件，避免用户为 YouTube 配置代理时改变全局网络行为。</p>
     */
    public static final File YOUTUBE_PROXY = new File(CONFIG_DIRECTORY, "youtube_proxy.json");
    /**
     * YouTube 的浏览器 Cookie 与账号展示资料。
     *
     * <p>只做 Cookie 这一种登录（OAuth 设备码要自备 Google Cloud 的 client 凭据，
     * 不适合公开分发）。只影响 YouTube 的元数据链路：歌词、歌单详情、账号信息。
     * 取流仍然走匿名的那条 Android 客户端——实测登录态对它没必要，而把 Cookie
     * 发给每一个 CDN 请求反而是多余的暴露面。</p>
     */
    public static final File YOUTUBE_AUTH = new File(CONFIG_DIRECTORY, "youtube_auth.json");
    /** Automix（无缝曲目交接）偏好设置。 */
    public static final File GD_SOURCE = new File(CONFIG_DIRECTORY, "gd_source.json");

    /**
     * 酷狗的设备标识与登录凭证。
     *
     * <p>不能塞进 {@link #MUSIC_AUTH}：那个文件由 Cadence（{@code music-api} 库）的
     * {@code MusicCredentialStore} 独占读写，它只认识网易云 cookie 与 QQ 的 musicid/musickey，
     * 写进去的第三个平台会在它下一次保存时被整段丢掉。</p>
     */
    public static final File KUGOU_AUTH = new File(CONFIG_DIRECTORY, "kugou_auth.json");

    /**
     * 汽水音乐的 Passport Cookie 与本地账号资料。
     *
     * <p>同样不能写进 {@link #MUSIC_AUTH}；该文件由 Cadence 的凭据存储整段重写。汽水登录
     * 由宿主自己完成，单独保存也便于退出登录时只清除汽水凭据。</p>
     */
    public static final File QISHUI_AUTH = new File(CONFIG_DIRECTORY, "qishui_auth.json");

    /**
     * 用户用「粘贴分享链接/ID」导入的汽水音乐歌单。
     *
     * <p>只存歌单引用（歌单 ID + 标题 + 封面 + 曲目数），曲目每次点开时才匿名向上游拉取，
     * 所以天然与 {@link #QISHUI_AUTH} 的凭据解耦：导入不需要登录，退出登录也不会丢导入的歌单。
     * 与 {@link #LX_SOURCES} 同理单开一个文件——一份可能不断追加的列表不该挤进每次改设置都重写的
     * {@link #PLAYER} 主配置。</p>
     */
    public static final File QISHUI_PLAYLISTS = new File(CONFIG_DIRECTORY, "qishui_playlists.json");

    /**
     * 用户用「粘贴分享链接」导入的跨平台歌单引用（网易云 / QQ / 酷狗 / B 站 / 汽水）。
     *
     * <p>取代只服务汽水一个渠道的 {@link #QISHUI_PLAYLISTS}：键是 {@code 平台名:平台歌单ID}，
     * 首次读取时若本文件不存在会把旧汽水存档合并进来（只此一次，之后删除的导入项不会复活）。</p>
     */
    public static final File IMPORTED_PLAYLISTS = new File(CONFIG_DIRECTORY, "imported_playlists.json");

    /**
     * 用户在本机自建的歌单，以及<b>收进来的每一首歌</b>（网易云 / QQ / 酷狗 / 汽水 / B 站 / GD 混在一张单子里）。
     *
     * <p>与 {@link #IMPORTED_PLAYLISTS} 分成两个文件是刻意的：导入的那份<b>只存引用</b>，点开时拿
     * {@code 平台:歌单ID} 去上游重新拉，所以它跟随上游、体积恒定；本地自建的那份必须<b>存曲目本身</b>，
     * 因为这些歌没有任何上游歌单可以拉回去。两者的生命周期也完全不同 —— 上游删了歌单，导入项还在；
     * 本地单里的歌一首都不会自己消失。</p>
     */
    public static final File LOCAL_PLAYLISTS = new File(CONFIG_DIRECTORY, "local_playlists.json");

    /**
     * Bilibili 的 Web Cookie 与本地账号资料（扫码登录后落盘）。
     *
     * <p>与 {@link #KUGOU_AUTH}、{@link #QISHUI_AUTH} 同样的理由单开一个文件：
     * {@link #MUSIC_AUTH} 由 Cadence 的凭据存储整段重写，只认识网易云 cookie 与 QQ 的
     * musicid/musickey。B 站的 {@code SESSDATA}/{@code bili_jct} 放进去会在下一次保存时被丢掉。</p>
     */
    public static final File BILIBILI_AUTH = new File(CONFIG_DIRECTORY, "bilibili_auth.json");

    /**
     * 一起听的行为开关。
     *
     * <p>单独一个文件而不是塞进 {@link #PLAYER}：这些开关只在房间里有意义，读失败的代价是
     * 回到默认行为，不该有机会影响播放器主配置的读写。</p>
     */
    public static final File LISTEN_TOGETHER = new File(CONFIG_DIRECTORY, "listen_together.json");

    /**
     * 网易云灰色歌曲解灰的行为开关。
     *
     * <p>与 {@link #LISTEN_TOGETHER} 同理单独一个文件：这些开关只在「官方链路已经取不到流」
     * 之后才生效，读失败的代价仅是回到默认行为，不该有机会影响播放器主配置的读写。</p>
     */
    public static final File NETEASE_UNBLOCK = new File(CONFIG_DIRECTORY, "netease_unblock.json");

    /**
     * 跨平台自动换源的行为开关。
     *
     * <p>与 {@link #NETEASE_UNBLOCK} 分开一个文件：解灰换的是取流地址，换源换的是曲目本体，
     * 两者的失败半径不同，不该共用一次配置读写。</p>
     */
    public static final File SWITCH_SOURCE = new File(CONFIG_DIRECTORY, "switch_source.json");

    /** 首次运行设置门控。单独保存，使引导状态不会损坏播放器偏好。 */
    public static final File AUTOMIX = new File(CONFIG_DIRECTORY, "automix.json");

    /**
     * 仅在新文件不存在时迁移。已有的新文件始终优先，
     * 因此用户可安全地在旁边保留旧配置的备份。
     */
    public static final File BIN_DIR = new File(CONFIG_DIRECTORY, "bin");

    /**
     * 系统媒体（SMTC）助手的编译与存放目录。
     *
     * <p>仓库里只带 {@code muonium/external/SmtcWatcher.cs} 这份 C# 源码，第一次需要它时用
     * Windows 自带的 {@code csc.exe} 现场编成 exe 放在这里。二进制不进仓库：既省掉「每个版本
     * 手工重编一次」这道必然被忘记的工序，也让用户机上不存在一个来路不明的可执行文件。</p>
     */
    public static final File SMTC_DIR = new File(CONFIG_DIRECTORY, "smtc");

    /**
     * 汽水外部取码工具与模组之间的共享目录：工具写 {@code state.json}，模组写 {@code command.json}。
     *
     * <p>放在 {@link #BIN_DIR} 之外：那一层是放可执行文件的，塞一个每次登录都要反复读写的
     * 目录进去，会让「这个 exe 是我自己放的还是工具生成的」变模糊。</p>
     */
    public static final File QISHUI_BRIDGE_DIR = new File(CONFIG_DIRECTORY, "qishui-bridge");

    // 由开发版构建产生的中间 Muonium 命名。
    public static final File CACHE_DIR = new File(CONFIG_DIRECTORY, "cache");

    // 归一化之前的发布名称，过去曾位于游戏根目录。
    public static final File MUSIC_CACHE = new File(CACHE_DIR, "music");

    /**
     * 已解析歌词时间轴的落盘缓存。
     *
     * <p>一首歌一个 JSON，按歌曲稳定键的哈希命名，<b>可以随时整个删掉</b>——删掉只会让下次播放
     * 重新走一遍网络链路，不会丢任何用户数据。放在 cache 而不是配置根，就是为了让这个语义清楚。</p>
     */
    public static final File LYRIC_CACHE = new File(CACHE_DIR, "lyrics");

    /**
     * 酷狗「我喜欢」的本地全量索引（按账号分键存 hash 列表）。
     *
     * <p>存在的理由：酷狗判断一首歌是否已收藏，只能把整张「我喜欢」拉下来比对，而收藏图标是每一行
     * 都要画的。没有这份索引时冷启动会显示「全部未收藏」，用户再点一次就是重复收藏。</p>
     *
     * <p>与 {@link #LYRIC_CACHE} 同一类东西，<b>可以随时整个删掉</b>——删掉只是退化成「红心图标
     * 初始不准，下次登录重新拉」，不丢任何用户数据。所以放在 cache 而不是配置目录。</p>
     */
    public static final File KUGOU_LIKED = new File(CACHE_DIR, "kugou_liked.json");

    /**
     * 已算出的 automix 交接计划。
     *
     * <p>与 {@link #LYRIC_CACHE} 同一类东西：一首歌一份 JSON，按「退出曲目 → 接入曲目」这一对
     * 稳定键命名，<b>可以随时整个删掉</b>——删掉只会让下次交接重新跑一遍音频分析，不丢任何
     * 用户数据。放在 cache 而不是配置根，就是为了让这个语义清楚。</p>
     *
     * <p>它值得落盘的原因是分析本身很贵：装了模型的机器要跑一次 Folia（内含 Python 的 stem
     * 分离进程），纯信号处理那一路也要把两首歌各解码一遍读 PCM。同一对曲目反复出现（列表循环、
     * 一起听房间里轮流点歌、同一首歌在不同房间里被放）时，重复推理纯属白烧 CPU。</p>
     */
    public static final File AUTOMIX_PLAN_CACHE = new File(CACHE_DIR, "automix_plans");

    /**
     * 每首歌手动设置的歌词时间轴偏移（毫秒）。
     *
     * <p>与 {@link #FFMPEG_NOTICE} 同理单独一个文件：这是用户对某首歌的补偿值，读失败的代价只是
     * 「这首歌回到零偏移」，不该有机会影响播放器主配置的读写。</p>
     */
    public static final File LYRIC_OFFSETS = new File(CONFIG_DIRECTORY, "lyric_offsets.json");

    /**
     * 每首歌手动指定的歌词来源。
     *
     * <p>与 {@link #LYRIC_OFFSETS} 同一类东西——都是「这首歌的歌词不对」时用户手动留下的补偿，
     * 一样要跨切歌与重启存活，所以放同一个位置、同样的尽力而为语义。</p>
     */
    public static final File LYRIC_SOURCES = new File(CONFIG_DIRECTORY, "lyric_sources.json");

    /**
     * 每首歌<b>手动搜出来并确认</b>的那一条歌词。
     *
     * <p>与 {@link #LYRIC_SOURCES} 的区别是钉的粒度：换源只说「去哪个库拿」，库里同名不同版本照样
     * 会挑错；这一份钉的是<b>某一首歌</b>——连候选的原始 JSON 一起存下来，下次加载直接按它精确重取。
     * 自动链路永远到不了这一档，所以它必须是加载链最前面的一级。</p>
     *
     * <p>同样是「读失败的代价只是回到自动链路」的小文件，单独一份、互不影响。</p>
     */
    public static final File LYRIC_MANUAL = new File(CONFIG_DIRECTORY, "lyric_manual.json");

    /** Skija 原生库的确定性回退解压目录。 */
    public static final File SKIJA_NATIVE_ROOT = new File(CONFIG_DIRECTORY, "native/skija");

    /**
     * 「没装 ffmpeg」全屏提示的「永不展示」选择。
     *
     * <p>刻意单独一个文件而不是塞进 {@link #HUD}：这个提示要在<b>主菜单</b>就决定要不要弹，
     * 那时 HUD 配置的读写路径还牵着一整套 HUD 组件；单独一个只有一个布尔值的小文件，读失败也
     * 只是「再提示一次」，不会影响任何别的设置。</p>
     */
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
     * 公告中心的已读记账与清单缓存。
     *
     * <p>与 {@link #UPDATE_NOTICE} 分开两个文件，虽然它们读的是同一份远端清单：更新提示记的是
     * 「这个版本跳过几次」，公告记的是「哪些 id 看过」。混在一个文件里，一次写坏就把两件事一起
     * 丢了；而这两件事的写入时机也完全不同（一个是收起提示时，一个是关掉公告窗时）。</p>
     */
    public static final File ANNOUNCEMENTS = new File(CONFIG_DIRECTORY, "announcements.json");

    /**
     * 【开发者覆盖】存在就只用它、完全不联网的本地清单。
     *
     * <p>写这个文件是为了让「改公告 → 开游戏看效果」不必先推一次 GitHub：把清单正文放进
     * {@code config/muonium/announcements_local.json} 即可，格式与远端那份完全一样。
     * 普通用户不会有这个文件，所以它不是一条会被误触的路径，也不需要发布前回滚
     * （对比 {@code UpdateChecker.Preview}：那个是编译期常量，必须改回去）。</p>
     */
    public static final File ANNOUNCEMENTS_LOCAL = new File(CONFIG_DIRECTORY, "announcements_local.json");

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

    /**
     * 私人 FM 的模式选择（模式 + 场景子模式）。
     *
     * <p>与 {@link #FFMPEG_NOTICE} 同理单独一个文件：这只是「上次选了哪个推荐模式」两三个字符串，
     * 读失败的代价仅是回到默认推荐，不该有机会影响播放器主配置的读写。</p>
     */
    public static final File PERSONAL_FM_MODE = new File(CONFIG_DIRECTORY, "personal_fm_mode.json");

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
