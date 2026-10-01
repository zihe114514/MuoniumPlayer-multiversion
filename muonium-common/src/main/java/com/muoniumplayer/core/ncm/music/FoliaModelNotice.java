package com.muoniumplayer.core.ncm.music;

import java.util.ArrayList;
import java.util.List;

/**
 * 「这台电脑还没装 Folia 分析模型」提示的状态。
 *
 * <p>与 {@link FfmpegNotice} 同一套路：状态与界面分开，界面在
 * {@code com.muoniumplayer.core.screens.ncm.ModelInstallNoticeOverlay}。区别在触发时机——
 * ffmpeg 提示在主菜单弹，这个提示在<b>本次启动第一次打开播放器界面</b>时弹，因为那才是用户
 * 真正开始用到 automix 的时刻。</p>
 *
 * <p>刻意不落盘、也没有「永不展示」：需求就是这个提示每次启动都要在第一次打开播放器时出现
 * 一次。只用一个纯内存的「本次启动已弹过」标记收口，免得每次开关播放器都糊一遍。</p>
 */
public final class FoliaModelNotice {

    /** 全部齐备才算就绪的组件。顺序与 {@link FoliaModelManager.Kind} 的声明顺序一致。 */
    private static final FoliaModelManager.Kind[] REQUIRED = {
            FoliaModelManager.Kind.BEAT_THIS,
            FoliaModelManager.Kind.HTDEMUCS,
            FoliaModelManager.Kind.RUNTIME
    };

    /** 本次启动是否已经弹过。纯内存：重启游戏即失效，这正是「每次启动提示一次」想要的。 */
    private static volatile boolean shownThisSession;

    private FoliaModelNotice() {
    }

    /** 仍然缺失的组件；空列表表示全部就绪。 */
    public static List<FoliaModelManager.Kind> missing() {
        List<FoliaModelManager.Kind> absent = new ArrayList<FoliaModelManager.Kind>();
        for (FoliaModelManager.Kind kind : REQUIRED) {
            try {
                if (!FoliaModelManager.isInstalled(kind)) absent.add(kind);
            } catch (Throwable ignored) {
                // 判定本身出错（目录不可读等）按「没装」处理：提示最多多弹一次，用户可以关掉。
                absent.add(kind);
            }
        }
        return absent;
    }

    public static boolean allInstalled() {
        return missing().isEmpty();
    }

    /**
     * 现在该不该弹这个提示。
     *
     * <p>每次都是实时判定、而不是读启动时缓存的结论：用户完全可能在启动之后、打开播放器之前
     * 就通过引导页或 HUD 设置把模型装好了，那种情况下再糊一遍提示就是误报。判定本身很便宜——
     * 两个模型是文件大小比对，运行组件的目录树只有第一次会走，之后就命中缓存。</p>
     */
    public static boolean shouldShow() {
        if (shownThisSession) return false;
        return !allInstalled();
    }

    /** 标记本次启动已经弹过。由界面在真正挂上去的那一刻调用。 */
    public static void markShownThisSession() {
        shownThisSession = true;
    }
}
