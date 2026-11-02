package com.muoniumplayer.core.ncm.music.ambient;

/**
 * 环境选曲的心情词表。每个值是一类氛围，{@link MoodDecider} 的输出只能是这里的
 * {@link #display} 或若干个用 {@code /} 连接的复合标签。
 *
 * <p>每个心情带两套搜索关键词：中文源（网易云/QQ/酷狗/汽水/B站…）用
 * {@link #keywordsZh}，国际源（YouTube/Spotify/Apple）的中文元数据召回差，用
 * {@link #keywordsEn}。运行时按当前音源平台二选一（见
 * {@link AmbientMusicController#pickKeyword}），同一心情重复触发时随机取词，
 * 让同一种氛围搜出不同的歌。</p>
 */
public enum Mood {
    CALM      ("宁静", "轻音乐 治愈钢琴曲 纯音乐", "peaceful piano instrumental calming"),
    EXPLORE   ("探索", "轻快纯音乐 旅行 民谣", "upbeat folk adventure instrumental"),
    COMBAT    ("战斗", "激烈战斗bgm dubstep电子 金属摇滚 燃系epic", "epic battle music dubstep metal action"),
    TENSE     ("紧张", "悬疑配乐 暗黑氛围 惊悚bgm", "suspense thriller dark ambient score"),
    EERIE     ("诡异", "空灵 迷幻 暗黑氛围", "eerie ethereal psychedelic ambient"),
    EPIC      ("史诗", "史诗音乐 交响史诗 大气纯音乐", "epic orchestral cinematic trailer music"),
    PASTORAL  ("田园", "田园民谣 治愈民谣 轻音乐", "pastoral folk acoustic peaceful"),
    UNDERGROUND("地下", "暗黑氛围 低沉纯音乐 悬疑配乐", "dark ambient deep cave drone music"),
    NIGHT     ("夜晚", "深夜电台 舒缓 助眠音乐", "late night chill sleep music lofi"),
    UNDERWATER("水底", "空灵纯音乐 治愈 冥想音乐", "underwater ambient meditation ethereal"),
    BUILD     ("建造", "lofi 轻松纯音乐 咖啡厅音乐", "lofi beats cafe music chill"),
    REST      ("休憩", "白噪音 冥想音乐 环境音乐", "white noise ambient relaxation soundscape"),
    NETHER    ("下界", "工业金属 暗黑电子 燃向纯音乐", "industrial metal dark electronic intense"),
    END       ("末地", "空灵氛围 冥想音乐 太空音乐", "space ambient cosmic meditation music");

    /** 中文标签，调试输出与灵动岛文案用。 */
    public final String display;
    /** 中文源的搜索关键词，空格分隔。 */
    public final String keywordsZh;
    /** 国际源（YouTube/Spotify/Apple）的搜索关键词，空格分隔。 */
    public final String keywordsEn;

    Mood(String display, String keywordsZh, String keywordsEn) {
        this.display = display;
        this.keywordsZh = keywordsZh;
        this.keywordsEn = keywordsEn;
    }

    /** 按中文标签查心情，没有则 {@code null}。 */
    public static Mood byDisplay(String label) {
        if (label == null) return null;
        for (Mood m : values()) if (m.display.equals(label)) return m;
        return null;
    }
}
