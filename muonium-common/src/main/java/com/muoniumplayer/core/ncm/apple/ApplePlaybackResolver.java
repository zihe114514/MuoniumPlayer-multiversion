package com.muoniumplayer.core.ncm.apple;

import java.io.File;

/**
 * Apple 曲目的播放决策入口：这条伪 URL 是「gamdl 伴生可用」的唯一签名。
 *
 * <p>{@code Music.resolvePlayUrl} 对 Apple 行先问这里：伴生后端就绪（或用户配了手动
 * gamdl 路径）时返回一条 {@code muonium-apple://song/<id>} 伪 URL —— 它不指向任何网络
 * 资源，{@code CloudMusic.cachedAudioFileLocked} 见到前缀就把兑换工作整个交给
 * {@link AppleGamdlBridge}；伴生不可用时返回 null，点播链路随即落进换源替身，
 * 用户视角是「自动换了源」而不是「播放失败」。</p>
 *
 * <p>伪 URL 而不是直接返回 preview 地址的原因：完整版与 30 秒试听在缓存、时长校验、
 * 兜底链路上的语义完全不同，混在一条 URL 里会让缓存键污染（30 秒文件冒充完整版）。</p>
 */
public final class ApplePlaybackResolver {

    public static final String PSEUDO_URL_PREFIX = "muonium-apple://song/";

    private ApplePlaybackResolver() {
    }

    /**
     * 伴生可用时给伪 URL；不可用返回 null（上游落换源替身）。
     *
     * <p>判定顺序：总开关（设置页可关）→ 伴生后端就绪 → 非 Windows 降级的手动 gamdl 路径。
     * 前缀常量与 {@code CloudMusic} 的分派共用，改前缀等于改协议。</p>
     *
     * <p><b>懒安装</b>：整包已发布但尚未装好时，第一次 Apple 点播会在这里后台拉起安装
     * （{@link AppleCompanionDownload#ensureInstalledAsync} 自带就绪/冷却/在跑三重闸，
     * 重复触发无害）——本次照旧走替身，装好后的下一次点播开始出完整版。用户不需要
     * 懂「伴生」是什么，点歌这个动作本身就是安装的扳机。</p>
     */
    public static String resolvePseudoUrl(String songId) {
        if (songId == null || songId.trim().isEmpty()) return null;
        AppleConfigStore.Config config = AppleConfigStore.snapshot();
        if (!config.gamdlEnabled) return null;
        if (AppleCompanionDownload.isReady() || manualExecutableOrNull() != null) {
            return PSEUDO_URL_PREFIX + songId.trim();
        }
        AppleCompanionDownload.ensureInstalledAsync();
        return null;
    }

    /** 非 Windows / 自动安装未发布时的降级：用户手动指定的 gamdl 可执行文件。 */
    public static File manualExecutableOrNull() {
        String path = AppleConfigStore.snapshot().manualGamdlPath;
        if (path.isEmpty()) return null;
        File file = new File(path);
        return file.isFile() ? file : null;
    }

    /** 从伪 URL 还原歌曲 id；不是本协议的 URL 返回 null。 */
    public static String songIdFromPseudoUrl(String playUrl) {
        if (playUrl == null || !playUrl.startsWith(PSEUDO_URL_PREFIX)) return null;
        String id = playUrl.substring(PSEUDO_URL_PREFIX.length()).trim();
        return id.isEmpty() ? null : id;
    }
}
