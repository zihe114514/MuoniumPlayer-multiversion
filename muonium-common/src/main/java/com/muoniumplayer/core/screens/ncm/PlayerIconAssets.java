package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.texture.DynamicTexture;
import com.muoniumplayer.core.rendering.texture.Textures;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.awt.image.BufferedImage;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从提供的美术资源导入的静态播放器控制图标。
 *
 * <p>资源路径有意保持在历史 {@code muonium} 根目录下，
 * 因为该根目录是已安装 mod 资源兼容性契约的一部分。</p>
 *
 * <p><b>必须带前导斜杠。</b>{@link Location#getResourceStream()} 用的是
 * {@code Location.class.getResourceAsStream(path)}，不带斜杠会被当成相对
 * {@code com/muoniumplayer/core/utils/} 的路径解析，于是这七张图一张都找不到：
 * 每个 {@code ThemedTextureIconWidget} 每帧都会抛一次 {@code MissingResourceException}
 * 并静默退回字体兜底字形。那些兜底字形与相邻的图标字体不同形状、也不同大小
 * （歌曲行的"收藏"就因此比旁边两枚小了约三成），看上去就是右侧控件对不齐。
 * 项目里另外两处真实的 classpath 贴图（{@code MusicToast} 的 hud 图、着色器）
 * 一直是带斜杠写的；{@code muonium/textures/album|music|playlist|user/...} 那些
 * 不带斜杠的 Location 只是网络封面的缓存键，从不读 classpath，所以没暴露这个问题。</p>
 */
public final class PlayerIconAssets {

    public static final Location FAVORITE = Location.of("/muonium/textures/player/icons/favorite.png");
    public static final Location PLAYLIST = Location.of("/muonium/textures/player/icons/playlist.png");
    public static final Location HISTORY = Location.of("/muonium/textures/player/icons/history.png");
    public static final Location PERSONAL_FM = Location.of("/muonium/textures/player/icons/personal-fm.png");
    public static final Location PLAY_MODE_SEQUENTIAL = Location.of("/muonium/textures/player/icons/play-mode-sequential.png");
    public static final Location PLAY_MODE_RANDOM = Location.of("/muonium/textures/player/icons/play-mode-random.png");
    public static final Location PLAY_MODE_SINGLE_LOOP = Location.of("/muonium/textures/player/icons/play-mode-single-loop.png");

    private PlayerIconAssets() {
    }

    /**
     * play-mode 那三张图在 96×96 画布里的<b>墨迹高度</b>占比（实测 52×42，随机那张 52×44）。
     *
     * <p>调用方拿它反推 {@code ThemedTextureIconWidget#setContentInset}：中间那一排另外三颗是
     * icomoon 字形，墨迹高约 7 逻辑像素，而这张图画满 20×20 的框会得到 10.8×8.75 的墨迹 ——
     * 宽出 74%，看上去就是「第四颗大一号、而且不在同一条线上」。</p>
     */
    public static final double PLAY_MODE_INK_HEIGHT_RATIO = 42.0 / 96.0;

    /**
     * 对未提供封面的旧模式返回 {@code null}。
     * 调用方随后保留其既有的图标字体回退，而非把
     * 旧状态映射到错误的视觉图标。
     */
    public static Location forPlayMode(CloudMusic.PlayMode mode) {
        if (mode == null) {
            return null;
        }
        switch (mode) {
            case Sequential:
                return PLAY_MODE_SEQUENTIAL;
            case Random:
                return PLAY_MODE_RANDOM;
            case LoopSingle:
                return PLAY_MODE_SINGLE_LOOP;
            case LoopInList:
            default:
                return null;
        }
    }

    /** 正在后台解码/上传的图标，防止重复排队。 */
    private static final Set<Location> PENDING_PREWARM = ConcurrentHashMap.newKeySet();

    /**
     * 把这批控制图标提前装进 {@code TextureManager}：工作线程读 jar + 解码，
     * GL 上交给主线程的限额队列（每 tick 最多 4 项 / 2ms，见 {@code TextureUploadQueue}）。
     *
     * <p>{@code ThemedTextureIconWidget} 首次渲染某个 Location 时走的是
     * {@code TextureManager#bindTexture} 的同步兜底——渲染线程上读 jar、ImageIO 解码、
     * GL 分配上传一次做完。布局预热只<b>构造</b>控件树、从不渲染，这份成本于是全部落在
     * 首次打开播放器的第一帧。这里把它挪到 HUD 空闲期；三张播放模式图标一起装，
     * 播放模式切换的那一帧同样会触发同步兜底。</p>
     *
     * <p>参数刻意与同步兜底保持一致（{@code linear=false}）：采样方式由
     * {@code ThemedTextureIconWidget} 绘制时经 {@code RenderSystem.linearFilter()} 现场指定，
     * 纹理自身的过滤参数跟着旧路径走，预热与否不改变任何观感。</p>
     *
     * <p>缺资源（资源包或过时的开发 jar）、解码失败都只放弃预热收益：真正的同步兜底
     * 仍在，控件还有字体字形兜底，因此单张失败不中断其余图标。</p>
     */
    public static void prewarm() {
        prewarmIcon(FAVORITE);
        prewarmIcon(PLAYLIST);
        prewarmIcon(HISTORY);
        prewarmIcon(PERSONAL_FM);
        prewarmIcon(PLAY_MODE_SEQUENTIAL);
        prewarmIcon(PLAY_MODE_RANDOM);
        prewarmIcon(PLAY_MODE_SINGLE_LOOP);
    }

    private static void prewarmIcon(Location icon) {
        if (icon == null) return;
        TextureManager textureManager = TextureManager.getInstance();
        if (textureManager.getTexture(icon) != null || !PENDING_PREWARM.add(icon)) {
            return;
        }
        MultiThreadingUtil.runAsync(() -> {
            boolean queuedUpload = false;
            try {
                BufferedImage img = DynamicTexture.readImage(icon.getResourceStream());
                if (img != null) {
                    queuedUpload = true;
                    // 与 downloadTextureAndLoadAsync 同一收尾口径：上传真正完成后才释放图片。
                    Textures.loadTextureAsyncly(icon, img, true, true, false,
                            () -> PENDING_PREWARM.remove(icon));
                }
            } catch (Throwable t) {
                System.err.println("[MuoniumPlayer] 播放器图标预热失败(" + icon + "): " + t);
            } finally {
                // 失败（缺资源/解码不出）时允许将来重试；成功排队时由 after 回调释放。
                if (!queuedUpload) PENDING_PREWARM.remove(icon);
            }
        });
    }
}
