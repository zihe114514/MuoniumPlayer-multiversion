package com.muoniumplayer.core;

import lombok.Getter;
import com.muoniumplayer.api.OpenAPI;
import com.muoniumplayer.core.management.AbstractManager;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.module.impl.OpenNCMScreen;
import com.muoniumplayer.core.ncm.lxsource.LxSourceService;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.QishuiBackendLifecycle;
import com.muoniumplayer.core.reflection.Reflection;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.screens.ncm.NCMPlayerConfig;
import com.muoniumplayer.core.ncm.music.GdStudioSourceSettings;
import com.muoniumplayer.core.ncm.music.NeteaseUnblockSettings;
import com.muoniumplayer.core.ncm.music.SwitchSourceSettings;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.core.widget.impl.MusicInfoWidget;
import com.muoniumplayer.core.widget.impl.MusicLyricsWidget;

import java.util.Arrays;
import java.util.List;

/**
 */
public class MuoniumPlayerExtension {

    public static final String NAME = "MuoniumPlayer";
    public static final String AUTHOR = "MuoniumPlayer Contributors";
    public static final String VERSION = "2.1.0";

    @Getter
    private static final MuoniumPlayerExtension instance = new MuoniumPlayerExtension();

    @Getter
    private FontManager fontManager;

    /** 注册到旧版 OpenAPI 桥接上的音乐播放器功能。 */
    public OpenNCMScreen musicPlayerModule = new OpenNCMScreen();
    public MusicInfoWidget musicInfo = new MusicInfoWidget();
    public MusicLyricsWidget musicLyrics = new MusicLyricsWidget();

    public MuoniumPlayerExtension() {

    }

    public void init(OpenAPI api) {
        api.registerEvent(MuoniumPlayerEventHandler.getInstance());

        // 在异步账号引导可能创建 AudioPlayer 之前，先恢复所有播放器偏好。
        // 这包括 mod 自有的持久化音量。
        HudConfig.load();
        // Folia 背景的独立配置（config/muonium/folia_lyrics_background.json）。
        com.muoniumplayer.core.screens.ncm.foliabg.FoliaBackgroundConfig.load();
        this.musicInfo.volume.setValue((double) HudConfig.playerVolume);
        this.musicInfo.animatedCover.setValue(HudConfig.animatedCoverEnabled);
        NCMPlayerConfig.load();
        GdStudioSourceSettings.load();
        NeteaseUnblockSettings.load();
        SwitchSourceSettings.load();
        // 异步起自定义源：启用了脚本时这一步要转译并执行一整份 JS（官方参考源约 2.7 秒），
        // 放在主线程上就是进游戏前多一次卡顿；没启用任何脚本时它连 Rhino 都不会去碰。
        MultiThreadingUtil.runAsync(LxSourceService::init);
        // 脚本转译器（13.7 MB 的 closure jar，不进主包）与完整字体同期、进游戏就拉：
        // 用户真要用自定义源时多半已经就位。下载在后台守护线程上，这里只做判断就走。
        com.muoniumplayer.core.ncm.lxsource.ClosureCompilerDownload.ensureDownload();
        CloudMusic.quality = NCMPlayerConfig.getAudioQuality();
        MultiThreadingUtil.runAsync(CloudMusic::initNCM);
        // 汽水签名后端:装了伴生包就随游戏隐藏拉起(独立进程,游戏退出自动收掉);
        // 还没装则不惊动,首次点进汽水源时按需下载伴生包。
        QishuiBackendLifecycle.ensureStartedForGame();
        Reflection.init(api);

        this.fontManager = new FontManager();

        List<AbstractManager> managers = Arrays.asList(this.fontManager);

        for (AbstractManager manager : managers) {
//            logger.debug("calling init() on {}...", manager.getName());
            manager.init();
        }

        api.registerFeature(this.musicPlayerModule);
        api.registerFeature(this.musicInfo);
        api.registerFeature(this.musicInfo.widget);
        api.registerFeature(this.musicLyrics);
        api.registerFeature(this.musicLyrics.widget);

        // 歌曲信息 HUD / 歌词 HUD：默认开启，位置与缩放由 HudConfig 归一化字段决定。
        // 故默认开启 + 加载 HudConfig 持久化位置，并提供快捷键开关（TOGGLE_MUSIC_INFO / TOGGLE_LYRICS）
        // 与 HUD 编辑器（EDIT_HUD，参考 cloudmusic/mod/gui/GuiOverlayEditor）。
        this.musicInfo.setEnabled(true);
        this.musicLyrics.setEnabled(true);
        NCMTheme.load();
        this.musicLyrics.loadHudEditorSettings();
    }

    public void unload() {

    }

    /**
     * 是否正在主（客户端）线程上。
     *
     * <p><b>1.20.1 移植关键点</b>：1.8.9 的这个判断是比线程名 {@code "Client thread"}。
     * 1.20.1 把同一条线程改名成 {@code "Render thread"}，照抄名字会让主线程上的调用被判成
     * "不在主线程"，于是 {@code MultiThreadingUtil#runOnMainThreadBlocking} 把任务塞进队列
     * 后在同一条线程上等它执行 —— 自我死锁。实测表现为打开播放器界面（NCMScreen 首帧
     * 加载 ThemedTextureIconWidget 的纹理）后整个客户端卡死，窗口停在最后一帧。
     *
     * <p>改成问 MC 自己：{@code BlockableEventLoop#isSameThread()}（Yarn 里叫
     * {@code ThreadExecutor#isOnThread()}）比的是线程对象本身，
     * 等价于 1.8.9 的 {@code IThreadListener#isCallingFromMinecraftThread()}，不依赖线程名。
     */
    public static boolean isCallingFromMainThread() {
        net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
        // 极早期（Minecraft 尚未构造完）退化成按名字判断，避免 NPE。
        return client != null ? client.isSameThread() : "Render thread".equals(Thread.currentThread().getName());
    }

}
