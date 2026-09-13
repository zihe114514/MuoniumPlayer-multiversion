package com.muoniumplayer.core.rendering;

/** 来自单独打包的音源品牌图标字体的码位。 */
public final class MusicBrandIcons {

    /** 来自 fontello-54446a4c 的 QQ 音乐品牌字形；与 QQ 登录图标分开。 */
    public static final String QQ_MUSIC = "\ue888";

    public static final String NETEASE_CLOUD_MUSIC = "\ue801";

    /** 来自内置 youtube-icons 字体的 YouTube 播放按钮标识。 */
    public static final String KUGOU_MONOGRAM = "酷";
    public static final String PERSONAL_FM = "\ue802";

    /** YouTube play-button mark from the bundled youtube-icons font. */
    public static final String YOUTUBE = "\ue803";

    /**
     * 哔哩哔哩的临时字标。现有图标字体没有官方 B 站字形，直接用正文字体的“B”，
     * 后续补上品牌字体时只需要替换这里与取字体的分支。
     */
    public static final String BILIBILI_MONOGRAM = "B";

    /** 汽水音乐暂用正文字标，避免为单个来源再引入一份字体资源。 */
    public static final String QISHUI_MONOGRAM = "汽";

    private MusicBrandIcons() {
    }
}
