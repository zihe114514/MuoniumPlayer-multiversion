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
 * <p>本模组自有的文件从不直接放在游戏目录下。持久化状态分两处：</p>
 * <ul>
 *   <li><b>{@code config/muonium/}</b> —— 配置、附件与运行时下载资产（ffmpeg、汽水后端、
 *       字体、模型、Skija 原生库…）。这是 Minecraft 模组启动器和配置备份工具所期望的布局；
 *       运行资产刻意留在这里：整合包作者发布时自己取舍带不带，带了用户装机即用、不必再
 *       下载一遍。</li>
 *   <li><b>{@code MuoniumPlayer/}</b>（游戏根目录）—— <b>可再生缓存</b>与<b>账号凭据</b>。
 *       前者会涨到几个 GB（GitHub issue #11：整合包导出时启动器默认把 {@code config/}
 *       整个打包，音乐缓存裹进去就是灾难）；后者是登录凭据，绝不能被打进分享出去的整合包。
 *       两类都可在启动器导出向导里被默认排除，但目录挪出去之后连「作者忘了排除」都不怕。</li>
 * </ul>
 * <p>历史位置（游戏根散文件 → config/muonium；config/muonium/cache → MuoniumPlayer/cache；
 * 凭据 → MuoniumPlayer/auth）在首次使用时自动迁移；新位置已存在的文件始终优先，
 * 迁移任何一步失败都留在原地继续用——宁可多占一点空间，绝不丢用户数据。</p>
 */
public final class ConfigPaths {

    /** 唯一由本模组自有的根目录。所有持久化状态都挂在此目录下。 */
    private static final File CONFIG_DIRECTORY = new File("config", "muonium");

    /**
     * 游戏根下的可再生日志根：缓存与账号凭据都在这里（类注释里的分工见上）。
     * 与 {@link #CONFIG_DIRECTORY} 同为「本模组自有」，但生命周期不同——
     * 整个目录删掉只损失缓存与登录态，配置与附件完好无损。
     */
    private static final File DATA_ROOT = new File("MuoniumPlayer");

    /** 账号凭据根：所有平台的登录态（cookie / token / 账号列表）都收进这里。 */
    private static final File AUTH_ROOT = new File(DATA_ROOT, "auth");

    /** 可再生缓存根：音频缓存、歌词、封面、解码边车……整目录删掉只是退化成重走网络。 */
    private static final File CACHE_ROOT = new File(DATA_ROOT, "cache");

    public static final File HUD = new File(CONFIG_DIRECTORY, "hud.json");
    public static final File PLAYER = new File(CONFIG_DIRECTORY, "player.json");
    public static final File THEME = new File(CONFIG_DIRECTORY, "theme.json");

    /**
     * 触屏手势层（拖动滚动 / 松手触发）的旋钮。
     *
     * <p>刻意不塞进 {@link #HUD}：手势实现全在共享层，而 HudConfig 是五棵版本树各自一份的
     * FORK——设置跟着实现走才能保持「零跨树复制」。参见 {@code TouchSettings}。</p>
     */
    public static final File TOUCH = new File(CONFIG_DIRECTORY, "touch.json");

    /**
     * 全屏歌词「Folia 背景」的独立配置。
     *
     * <p>刻意不塞进 {@link #HUD}：这一套参数按背景样式分组、字段多，而且与 QPlayer 那套流体
     * 背景是两条互不相干的系统；混在 HUD 里会让「歌词背景」看起来像 HUD 的一部分，也会让拖动
     * 任何一个滑块都重写整份 HUD 配置。</p>
     */
    public static final File FOLIA_LYRICS_BACKGROUND =
            new File(CONFIG_DIRECTORY, "folia_lyrics_background.json");

    /** Folia 背景的自定义图片（莫奈 / 漫游 / 嵌入共用一张）。 */
    public static final File FOLIA_BACKGROUND_DIR = new File(CONFIG_DIRECTORY, "folia_background");

    public static final Path MUSIC_AUTH = AUTH_ROOT.toPath().resolve("music_auth.json");
    public static final File NETEASE_ACCOUNTS = new File(AUTH_ROOT, "netease_accounts.json");

    /** 旧版网易云 cookie 回退，与内置音源层的凭据存储分开保存。 */
    public static final File NETEASE_COOKIE = new File(AUTH_ROOT, "netease_cookie.txt");

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
    public static final File YOUTUBE_AUTH = new File(AUTH_ROOT, "youtube_auth.json");
    /** Automix（无缝曲目交接）偏好设置。 */
    public static final File GD_SOURCE = new File(CONFIG_DIRECTORY, "gd_source.json");

    /**
     * 酷狗的设备标识与登录凭证。
     *
     * <p>不能塞进 {@link #MUSIC_AUTH}：那个文件由内置音源层（吸收自原 music-api jar）的
     * {@code MusicCredentialStore} 独占读写，它只认识网易云 cookie 与 QQ 的 musicid/musickey，
     * 写进去的第三个平台会在它下一次保存时被整段丢掉。</p>
     */
    public static final File KUGOU_AUTH = new File(AUTH_ROOT, "kugou_auth.json");

    /**
     * 汽水音乐的 Passport Cookie 与本地账号资料。
     *
     * <p>同样不能写进 {@link #MUSIC_AUTH}；该文件由内置音源层的凭据存储整段重写。汽水登录
     * 由宿主自己完成，单独保存也便于退出登录时只清除汽水凭据。</p>
     */
    public static final File QISHUI_AUTH = new File(AUTH_ROOT, "qishui_auth.json");
    /** 汽水已保存账号列表；与当前活动会话分开，切换失败不会覆盖现账号。 */
    public static final File QISHUI_ACCOUNTS = new File(AUTH_ROOT, "qishui_accounts.json");

    /**
     * Spotify 的本机配置：用户自备的 Client ID 与市场代码。
     *
     * <p>与凭据分成两个文件：Client ID 不是秘密（它是公开的），改它不必碰 token；
     * 重新授权也不该丢掉用户的配置。Client ID 为什么要用户自备，见
     * {@code SpotifyConfigStore} 的类注释。</p>
     */
    public static final File SPOTIFY_CONFIG = new File(CONFIG_DIRECTORY, "spotify_config.json");

    /**
     * Spotify 的 OAuth token 与账号展示资料（PKCE 授权后落盘）。
     *
     * <p>与 {@link #KUGOU_AUTH}、{@link #QISHUI_AUTH} 同样的理由单开一个文件：
     * {@link #MUSIC_AUTH} 由内置音源层的凭据存储整段重写，只认识网易云 cookie 与 QQ 的
     * musicid/musickey，Spotify 的 access/refresh token 放进去会在下一次保存时被丢掉。</p>
     */
    public static final File SPOTIFY_AUTH = new File(AUTH_ROOT, "spotify_auth.json");

    /**
     * Apple Music 的本机配置：media-user-token、storefront 与本音源的偏好开关。
     *
     * <p>与 {@link #KUGOU_AUTH}、{@link #QISHUI_AUTH}、{@link #SPOTIFY_AUTH} 同样的理由单开一个文件：
     * {@link #MUSIC_AUTH} 由内置音源层的凭据存储整段重写，只认识网易云 cookie 与 QQ 的
     * musicid/musickey，Apple 的 media-user-token 放进去会在下一次保存时被丢掉。</p>
     *
     * <p>注意 cookies.txt（Apple 引擎的凭据）不在这里，而在 {@link #APPLE_MUSIC_DIR} 下——
     * 它是整份明文的 Netscape cookie 文件，比 JSON 配置里的 token 敏感一个量级，分开放方便
     * 用户理解「删哪个是删凭据」。</p>
     */
    public static final File APPLE_MUSIC_AUTH = new File(AUTH_ROOT, "apple_music_auth.json");

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
     * 本地音乐库的注册目录列表（「导入本地文件夹」扫出来的那几个磁盘目录）。
     *
     * <p>与 {@link #LOCAL_PLAYLISTS} 分开一个文件：目录列表是「这台机器上有哪些音乐文件夹」，
     * 曲目存档是「歌单里有哪些歌」，重建（删除并重扫）目录列表不应该碰任何一张歌单。
     * 只存绝对路径字符串；目录被移动/删除后由 {@code LocalMusicLibrary} 在播放时按文件
     * 缺失安全失败，不做任何自动迁移。</p>
     */
    public static final File LOCAL_LIBRARY = new File(CONFIG_DIRECTORY, "local_library.json");

    /**
     * Bilibili 的 Web Cookie 与本地账号资料（扫码登录后落盘）。
     *
     * <p>与 {@link #KUGOU_AUTH}、{@link #QISHUI_AUTH} 同样的理由单开一个文件：
     * {@link #MUSIC_AUTH} 由内置音源层的凭据存储整段重写，只认识网易云 cookie 与 QQ 的
     * musicid/musickey。B 站的 {@code SESSDATA}/{@code bili_jct} 放进去会在下一次保存时被丢掉。</p>
     */
    public static final File BILIBILI_AUTH = new File(AUTH_ROOT, "bilibili_auth.json");

    /**
     * 一起听的行为开关。
     *
     * <p>单独一个文件而不是塞进 {@link #PLAYER}：这些开关只在房间里有意义，读失败的代价是
     * 回到默认行为，不该有机会影响播放器主配置的读写。</p>
     */
    public static final File LISTEN_TOGETHER = new File(CONFIG_DIRECTORY, "listen_together.json");

    /**
     * 直连房间（无服务端 mod 的一起听）的行为开关。
     *
     * <p>与 {@link #LISTEN_TOGETHER} 同理单独一个文件：只存上次的监听端口与局域网直连开关，
     * 读失败的代价是回到默认端口，不该有机会影响播放器主配置的读写。</p>
     */
    public static final File DIRECT_ROOM = new File(CONFIG_DIRECTORY, "direct_room.json");

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

    /** 「播放时压掉原版音乐」的开关（VanillaMusicDucker）。单布尔，默认开。 */
    public static final File VANILLA_MUSIC_DUCKER = new File(CONFIG_DIRECTORY, "vanilla_music_ducker.json");

    /** 「游戏窗口失焦时压低音乐」的开关与压低档位（FocusVolumeDucker）。 */
    public static final File FOCUS_VOLUME_DUCKER = new File(CONFIG_DIRECTORY, "focus_volume_ducker.json");

    /**
     * 场景声学的开关与分项。
     *
     * <p>单独一个文件而不是塞进 {@link #PLAYER}：这条链的读写频率比播放器主配置高
     * （面板上每颗开关、滑条都要立即落盘），而它读失败的代价只是「回到关闭」，不该有
     * 机会牵连主配置。分项（浸液/围合/降水/强度）与总开关放同一份：它们只在总开关
     * 打开时才有意义，分开存会让「总开关关了、分项还是旧值」成为常态。</p>
     */
    public static final File SCENE_ACOUSTICS = new File(CONFIG_DIRECTORY, "scene_acoustics.json");

    /**
     * 世界声源的锚点记忆(「虚拟音箱」放哪了)。
     *
     * <p>与 {@link #SCENE_ACOUSTICS} 同理单独一个文件:只存一组坐标 + 维度串,
     * 退出重进后据此恢复锚点;读失败的代价只是「没有音箱」,不该牵连任何
     * 播放器主配置。数据经由 {@code SpeakerState#sanitize} 逐字段校验后才生效。</p>
     */
    public static final File SPEAKER = new File(CONFIG_DIRECTORY, "speaker.json");

    /**
     * 环境选曲（Ambient auto-DJ）的开关与稳定期。
     *
     * <p>单独一个文件，理由同 {@link #SCENE_ACOUSTICS}：设置页上每颗开关/滑条都要
     * 立即落盘，读失败的代价只是「回到关闭」，不该牵连播放器主配置。</p>
     */
    public static final File AMBIENT_DJ = new File(CONFIG_DIRECTORY, "ambient_dj.json");

    /**
     * 「通透模式」的开关与两档幅度（随节拍压缩生物/玩家模型的整活特效）。
     *
     * <p>单独一个文件，理由同 {@link #SCENE_ACOUSTICS}：设置页上每颗开关/滑条都要
     * 立即落盘，读失败的代价只是「回到关闭」，不该牵连播放器主配置。不进 {@link #HUD}
     * 的原因也同：HudConfig 是五棵版本树各自一份的留守文件，每加一个字段要改五份。</p>
     */
    public static final File TRANSPARENT_MODE = new File(CONFIG_DIRECTORY, "transparent_mode.json");

    /**
     * 「卡点运镜」（MuoniumCine）的开关与镜头/转场分项。
     *
     * <p>单独一个文件，理由同 {@link #SCENE_ACOUSTICS}：设置页上每颗开关/滑条都要
     * 立即落盘，读失败的代价只是「回到关闭」，不该牵连播放器主配置。不进 {@link #HUD}
     * 的原因也同：HudConfig 是五棵版本树各自一份的留守文件，每加一个字段要改五份。</p>
     */
    public static final File CINE = new File(CONFIG_DIRECTORY, "cine.json");

    /**
     * 「上次播放到哪」的会话快照（播放记忆）。
     *
     * <p>与所有设置文件分开：这份内容是<b>易失的会话状态</b>，损坏/缺失的代价只是「这次启动
     * 不恢复」，恢复逻辑本身就要把它当不可信输入逐条校验。它不该有机会牵连任何真正的偏好
     * 设置，也不该被偏好设置链路的读写节奏带着走（它由播放线程按事件+心跳写，频率完全不同）。</p>
     */
    public static final File PLAYBACK_RESUME = new File(CONFIG_DIRECTORY, "playback_resume.json");

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

    /**
     * Apple Music 的 cookies.txt 等本机凭据目录。
     */
    public static final File APPLE_MUSIC_DIR = new File(AUTH_ROOT, "apple-music");

    // 可再生缓存根（GitHub issue #11）：音频缓存会涨到几个 GB，绝不能留在会被整合包
    // 整体打包的 config/ 里。整目录删掉只是退化成重走网络，不丢任何用户数据。
    public static final File CACHE_DIR = CACHE_ROOT;

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
     * 本地音乐的封面提取缓存：嵌入专辑图解出来后按 stableKey 落成 PNG，供
     * 封面渲染链按 {@code Music#getCoverLocation()} 的既有键位读取。
     */
    public static final File LOCAL_ART_DIR = new File(CACHE_DIR, "local_art");

    /**
     * 本地音乐 AAC/M4A 解码出的 WAV 边车缓存（按源文件路径哈希分目录）。
     *
     * <p>本地文件的用户目录是只读资产，边车必须改落模组缓存；与下载缓存分开一个目录，
     * 是为了让「清空下载缓存」不必顺带删掉这些大文件，也让「删边车」不必翻下载缓存。</p>
     */
    public static final File DECODED_WAV_DIR = new File(CACHE_DIR, "decoded_wav");

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

    /**
     * 首启引导那张「当前画面」抓帧的落盘草稿。
     *
     * <p>之所以真的落一次盘：五棵树的 {@code NativeImage} 像素打包方式不是一套
     * （1.20.1 / 1.21.1 只有 {@code getPixelsRGBA()}，1.21.11 起才有 {@code getPixels()} /
     * {@code getPixelsABGR()}），而 {@code writeToByteArray} 哪一版都没有。走 PNG 就没有通道顺序
     * 可猜。用完即删；{@code -Dmuonium.debug.capture=true} 时留着给人直接打开看。</p>
     */
    public static final File INTRO_CAPTURE = new File(CONFIG_DIRECTORY, "intro_capture.png");

    /**
     * 【只给开发验证】存在就在下一个 tick 等价于「按了一次右 Shift」，文件本身随即被删掉。
     *
     * <p>自动化环境里合成按键驱动不了 {@code KeyMapping}（那一类要等 tick 循环里的
     * {@code consumeClick()}，实测 vanilla 的 E 与 Esc 同样收不到），而覆盖层与引导的视觉验证
     * 必须真的把界面开出来。这条只补「按那一下」，走的还是同一个
     * {@code OnboardingManager.openPlayerEntry()}，不绕任何闸门。普通用户不会有这个文件。</p>
     */
    public static final File DEV_OPEN_PLAYER = new File(CONFIG_DIRECTORY, "dev_open_player");

    /**
     * 【只给开发验证】存在就在主菜单/游戏内空闲时等价于「按了一次 H」——打开 HUD 设置界面。
     *
     * <p>与 {@link #DEV_OPEN_PLAYER} 完全同一个套路，理由也一样：自动化环境驱动不了
     * {@code KeyMapping}，而 HUD 设置界面（MuoniumConfig 的整套 Compose UI）只有真的
     * 开出来才能验证「按键 → 桥 → 界面 → Skia 绘制」这条链路是通的。
     * 文件用完即删，普通用户不会有它。</p>
     */
    public static final File DEV_OPEN_HUD = new File(CONFIG_DIRECTORY, "dev_open_hud");

    /**
     * 【只给开发验证】存在就打开<b>背景采集探针</b>：每帧把「此刻还不含我们 HUD 的游戏画面」
     * 拷成一张纹理，并在屏幕左下角贴原图与糊过一档的对比块，日志里带采集耗时与纹理尺寸。
     * 删掉这个文件即关。
     *
     * <p>与 {@link #DEV_OPEN_PLAYER} 同一个理由：{@code runClient} 塞 JVM 参数不方便，而自动化
     * 环境只能操作文件与鼠标。等价开关是 {@code -Dmuonium.debug.glass=true}（那个是常量，
     * 本文件是运行中可开关）。</p>
     */
    public static final File DEV_GLASS_PROBE = new File(CONFIG_DIRECTORY, "dev_glass_probe");

    /**
     * ViPER 蝰蛇音效的预设与开关。
     *
     * <p>{@code presets/} 下可以直接放三类文件：官方 App 导出的 JSON（schema 2.1，以及更早的
     * 分组 / 扁平 JSON）、以及更老的 XML 预设（{@code <map>} 数字 ID 格式，即经典「蝰蛇音效
     * 配置文件」）。读取时统一归一化为 2.1 语义单位，与官方 App 的导入行为一致。</p>
     *
     * <p>{@code settings.json} 只存「总开关 + 当前预设名」，与预设文件分开：改开关、换预设
     * 都不会重写任何一份预设，读失败的代价也只是回到「未启用、未选择」。</p>
     */
    public static final File VIPER_DIR = new File(CONFIG_DIRECTORY, "viper");
    public static final File VIPER_SETTINGS = new File(VIPER_DIR, "settings.json");
    public static final File VIPER_PRESETS = new File(VIPER_DIR, "presets");

    /** User-created/imported template presets; builtins are read directly from the mod resources. */
    public static final File PRESETS_DIR = new File(CONFIG_DIRECTORY, "presets");
    /** Atomic pre-apply backups, newest ten retained. */
    public static final File PRESETS_BACKUP_DIR = new File(PRESETS_DIR, "backups");

    /**
     * 凭据文件的旧→新映射表。声明顺序无关；用 LinkedHashMap 保持可读性。
     * key 是 config/muonium 下的旧文件，value 是 MuoniumPlayer/auth 下的新文件。
     */
    private static final java.util.LinkedHashMap<File, File> AUTH_MIGRATION_TARGET = buildAuthMigration();

    /** 旧凭据文件清单（迁移用）；新位置常量已在上面逐个改指 {@link #AUTH_ROOT}。 */
    private static final File[] AUTH_MIGRATION_LEGACY;

    private static java.util.LinkedHashMap<File, File> buildAuthMigration() {
        java.util.LinkedHashMap<File, File> m = new java.util.LinkedHashMap<>();
        m.put(new File(CONFIG_DIRECTORY, "music_auth.json"), MUSIC_AUTH.toFile());
        m.put(new File(CONFIG_DIRECTORY, "netease_accounts.json"), NETEASE_ACCOUNTS);
        m.put(new File(CONFIG_DIRECTORY, "netease_cookie.txt"), NETEASE_COOKIE);
        m.put(new File(CONFIG_DIRECTORY, "youtube_auth.json"), YOUTUBE_AUTH);
        m.put(new File(CONFIG_DIRECTORY, "kugou_auth.json"), KUGOU_AUTH);
        m.put(new File(CONFIG_DIRECTORY, "qishui_auth.json"), QISHUI_AUTH);
        m.put(new File(CONFIG_DIRECTORY, "qishui_accounts.json"), QISHUI_ACCOUNTS);
        m.put(new File(CONFIG_DIRECTORY, "spotify_auth.json"), SPOTIFY_AUTH);
        m.put(new File(CONFIG_DIRECTORY, "apple_music_auth.json"), APPLE_MUSIC_AUTH);
        m.put(new File(CONFIG_DIRECTORY, "bilibili_auth.json"), BILIBILI_AUTH);
        return m;
    }

    static {
        java.util.LinkedHashMap<File, File> m = AUTH_MIGRATION_TARGET;
        AUTH_MIGRATION_LEGACY = m.keySet().toArray(new File[0]);
    }

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

        // ── issue #11：缓存与凭据迁出 config/muonium（新位置见 DATA_ROOT 的类注释）──
        // 迁移全程「尽力而为」：任何一步失败都留在原地，下次启动再试。
        // 凭据在迁移成功前绝不会被丢弃——move() 失败时旧文件原样在原位，读取方两个位置都找不到
        // 新文件时会照常重新登录，但已迁移完成的场景（绝大多数）对用户完全无感。
        migrateDataOut(CONFIG_DIRECTORY, CACHE_DIR, AUTH_ROOT, AUTH_MIGRATION_TARGET);
    }

    /**
     * issue #11 的「缓存 + 凭据迁出 config」迁移，参数化以便测试：
     * 把 {@code configDir/cache} 整目录挪到 {@code cacheDir}，把 {@code authMap} 里
     * 旧文件逐个挪到新文件，再把 {@code configDir/apple-music} 挪到 {@code authRoot/apple-music}。
     *
     * <p>无感语义的三条保证：新位置已存在时旧文件原样保留（已有数据优先，绝不覆盖）；
     * move 失败（被锁/跨盘失败）时旧文件留在原地，下次启动再试；迁移后 config 下的
     * 空 cache 壳删掉，导出包里不再出现空目录。</p>
     */
    static void migrateDataOut(File configDir, File cacheDir, File authRoot,
                               java.util.LinkedHashMap<File, File> authMap) {
        migrateDirectory(new File(configDir, "cache"), cacheDir);
        for (java.util.Map.Entry<File, File> e : authMap.entrySet()) {
            migrate(e.getKey(), e.getValue());
        }
        migrateDirectory(new File(configDir, "apple-music"), new File(authRoot, "apple-music"));
        File legacyCache = new File(configDir, "cache");
        File[] leftover = legacyCache.listFiles();
        if (leftover != null && leftover.length == 0) {
            // noinspection ResultOfMethodCallIgnored
            legacyCache.delete();
        }
    }


    static void migrate(File legacy, File target) {
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
    static void migrateDirectory(File legacy, File target) {
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

    static void move(File legacy, File target) {
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

    static boolean ensureDirectory(File directory) {
        return directory == null || directory.isDirectory() || directory.mkdirs();
    }
}
