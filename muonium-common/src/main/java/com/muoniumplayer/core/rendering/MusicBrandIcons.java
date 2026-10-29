package com.muoniumplayer.core.rendering;

/**
 * 单独打包的音源品牌图标字体的码位，以及<b>取不到矢量字标时的兜底字标</b>。
 *
 * <p>酷狗、哔哩哔哩、汽水三家都已经有真正的矢量标识了（见 {@link BrandVectorIcons}），下面留着的
 * 三个单字常量在正常路径上不再出现：矢量路径解析不出来时才会被画出来，免得卡片上留下一块空白。
 * 它们同时也是「这家压根没有矢量数据」时的回落目标 —— {@code BrandVectorIcons.has()} 为 false
 * 就画对应的那一个。</p>
 */
public final class MusicBrandIcons {

    /** 来自 fontello-54446a4c 的 QQ 音乐品牌字形；与 QQ 登录图标分开。 */
    public static final String QQ_MUSIC = "\ue888";

    public static final String NETEASE_CLOUD_MUSIC = "\ue801";

    /**
     * 酷狗音乐的兜底字标。
     *
     * <p>这里是一个<b>汉字</b>而不是私有区码位：music-brand-icons 与 qq-music-icons 两份图标字体里
     * 都没有酷狗的品牌字形，塞一个不存在的码位进去只会画出空白（QQ 的 U+E888 之前就踩过这个坑）。
     * 用正文字体里必然存在的「酷」当字标，任何字号下都画得出来，也仍然跟随卡片的前景色。正常路径上
     * 它已经让位给 {@link BrandVectorIcons} 里的矢量字标。</p>
     */
    public static final String KUGOU_MONOGRAM = "酷";
    public static final String PERSONAL_FM = "\ue802";

    /** 来自内置 youtube-icons 字体的 YouTube 播放按钮标识。 */
    public static final String YOUTUBE = "\ue803";

    /**
     * 哔哩哔哩的兜底字标，理由同 {@link #KUGOU_MONOGRAM}：两份图标字体里都没有 B 站的字形。
     * 正常路径上已经让位给 {@link BrandVectorIcons} 里的矢量字标。
     */
    public static final String BILIBILI_MONOGRAM = "B";

    /**
     * 汽水音乐的兜底字标，理由同 {@link #KUGOU_MONOGRAM}：两份图标字体里都没有汽水的字形。
     * 正常路径上已经让位给 {@link BrandVectorIcons} 里的那枚音符。
     */
    public static final String QISHUI_MONOGRAM = "汽";

    /**
     * Spotify 的兜底字标，理由同 {@link #KUGOU_MONOGRAM}。正常路径上已经让位给
     * {@link BrandVectorIcons} 里的那枚三弧标识。
     */
    public static final String SPOTIFY_MONOGRAM = "S";

    /**
     * Apple Music 的兜底字标，理由同 {@link #KUGOU_MONOGRAM}。正常路径上已经让位给
     * {@link BrandVectorIcons} 里的那枚圆角方块音符。取「♪」是正文字体里必然存在的音符字形，
     * 比字母 A 更贴近品牌的视觉联想。
     */
    public static final String APPLE_MONOGRAM = "♪";

    private MusicBrandIcons() {
    }
}
