package com.muoniumplayer.core.ncm.apple;

/**
 * Apple 曲目的播放决策入口：这条伪 URL 是「本机引擎可出完整版」的唯一签名。
 *
 * <p>{@code Music.resolvePlayUrl} 对 Apple 行先问这里：引擎开启且 cookies 在位时返回一条
 * {@code muonium-apple://song/<id>} 伪 URL —— 它不指向任何网络资源，
 * {@code CloudMusic.cachedAudioFileLocked} 见到前缀就把完整版兑换整个交给
 * {@link AppleNativeEngine}；不可用时返回 null，点播链路随即落进换源替身，
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
     * 引擎可用时给伪 URL；不可用返回 null（上游落换源替身）。
     *
     * <p>判定：总开关（配置文件可关）→ cookies 在位。前缀常量与 {@code CloudMusic}
     * 的分派共用，改前缀等于改协议。</p>
     */
    public static String resolvePseudoUrl(String songId) {
        if (songId == null || songId.trim().isEmpty()) return null;
        if (!AppleConfigStore.snapshot().nativeEngineEnabled) return null;
        if (AppleCookieSupport.cookiesFileOrNull() != null) {
            return PSEUDO_URL_PREFIX + songId.trim();
        }
        // 无 cookies:资料库与完整版都不可用,直落换源。
        return null;
    }

    /** 从伪 URL 还原歌曲 id；不是本协议的 URL 返回 null。 */
    public static String songIdFromPseudoUrl(String playUrl) {
        if (playUrl == null || !playUrl.startsWith(PSEUDO_URL_PREFIX)) return null;
        String id = playUrl.substring(PSEUDO_URL_PREFIX.length()).trim();
        return id.isEmpty() ? null : id;
    }
}
