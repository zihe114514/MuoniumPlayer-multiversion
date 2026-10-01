package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 系统媒体会话（SMTC）的一次采样快照。不可变，因此可以安全地跨线程发布。
 *
 * <p>这是外部播放器的全部事实：谁在播、播什么、播到哪、它允许我们做什么，以及它自己那张
 * 封面落在哪个文件上（{@link #artFile()}，见 {@link ExternalArtwork}）。歌词仍然走本模组
 * 自己的跨平台匹配链路（{@link ExternalTrackMatcher}）—— SMTC 根本没有歌词这个东西，
 * 而曲库那边的词是逐字的。</p>
 */
public final class ExternalSnapshot {

    public static final ExternalSnapshot NONE = noSession("", 0L);

    /**
     * 「这一拍没有会话」。走工厂而不是就地写十七个实参：这个形状的手写字面量已经错过一次，
     * 少一个多空一个都编译不过，而工厂只有一处。
     */
    static ExternalSnapshot noSession(String error, long sampledAtMs) {
        return new ExternalSnapshot(false, "", "", "", "", "", "", 0L, 0L,
                false, false, false, false, false, false, error, sampledAtMs, -1f, 0);
    }

    private final boolean session;
    private final String app;
    private final String title;
    private final String artist;
    private final String album;
    private final String state;
    /** 助手把这台播放器的封面图落盘后的<b>文件名</b>（不含目录）；空串表示它没给封面。 */
    private final String artFile;
    private final long positionMs;
    private final long durationMs;
    private final boolean canPlay;
    private final boolean canPause;
    private final boolean canToggle;
    private final boolean canNext;
    private final boolean canPrevious;
    private final boolean canSeek;
    /** 助手报回来的异常名；只进日志，不参与显示。 */
    private final String error;
    /** 本机收到这条快照的时刻，用于判断新鲜度。 */
    private final long sampledAtMs;
    /**
     * 那支程序在 Windows 音量合成器里的一格，归一化 0..1；-1 表示助手没配上这支程序
     * （没有会话、独占模式播放、或进程名与 AUMID 对不上）。
     */
    private final float volumeLevel;
    /** 音量会话所属进程；0 表示没有。只进日志，用来核对「配到的是不是同一个播放器」。 */
    private final int volumePid;

    ExternalSnapshot(boolean session, String app, String title, String artist, String album,
                     String state, String artFile, long positionMs, long durationMs,
                     boolean canPlay, boolean canPause, boolean canToggle,
                     boolean canNext, boolean canPrevious, boolean canSeek,
                     String error, long sampledAtMs, float volumeLevel, int volumePid) {
        this.session = session;
        this.app = app == null ? "" : app;
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        this.album = album == null ? "" : album;
        this.state = state == null ? "" : state;
        this.artFile = artFile == null ? "" : artFile;
        this.positionMs = Math.max(0L, positionMs);
        this.durationMs = Math.max(0L, durationMs);
        this.canPlay = canPlay;
        this.canPause = canPause;
        this.canToggle = canToggle;
        this.canNext = canNext;
        this.canPrevious = canPrevious;
        this.canSeek = canSeek;
        this.error = error == null ? "" : error;
        this.sampledAtMs = sampledAtMs;
        this.volumeLevel = volumeLevel < 0f || volumeLevel > 1f ? -1f : volumeLevel;
        this.volumePid = Math.max(0, volumePid);
    }

    /** 解析助手输出的一行状态。解不出来返回 {@link #NONE}，绝不抛到调用方。 */
    static ExternalSnapshot parseState(String jsonLine, long nowMs) {
        try {
            JsonElement element = JsonParser.parseString(jsonLine);
            if (!element.isJsonObject()) return NONE;
            JsonObject root = element.getAsJsonObject();
            boolean hasSession = bool(root, "session");
            if (!hasSession) {
                return noSession(text(root, "error"), nowMs);
            }
            JsonObject caps = root.has("caps") && root.get("caps").isJsonObject()
                    ? root.getAsJsonObject("caps") : new JsonObject();
            return new ExternalSnapshot(true,
                    text(root, "app"), text(root, "title"), text(root, "artist"),
                    text(root, "album"), text(root, "state"), safeArtFile(text(root, "art")),
                    number(root, "posMs"), number(root, "durMs"),
                    bool(caps, "play"), bool(caps, "pause"), bool(caps, "toggle"),
                    bool(caps, "next"), bool(caps, "prev"), bool(caps, "seek"),
                    text(root, "error"), nowMs,
                    bool(root, "volok") ? decimal(root, "vol") : -1f,
                    (int) number(root, "pid"));
        } catch (Throwable failure) {
            return NONE;
        }
    }

    /** 音量是小数（助手写 "0.8300"），解不出来一律当「不知道」。 */
    private static float decimal(JsonObject root, String key) {
        try {
            JsonElement value = root.get(key);
            if (value == null || value.isJsonNull()) return -1f;
            float parsed = value.getAsFloat();
            return Float.isNaN(parsed) ? -1f : parsed;
        } catch (Throwable ignored) {
            return -1f;
        }
    }

    /**
     * 文件名是从外部进程的输出里来的，不能直接拿去拼路径。
     *
     * <p>只收助手自己那一种写法：{@code art-<16 位十六进制>.<png|jpg|gif|bmp>}。带目录分隔符、
     * 带 {@code ..}、或者后缀对不上的，一律当「没有封面」。这一条不是洁癖：这条链路读的是
     * 一个能被别的进程写文件的目录。</p>
     */
    static String safeArtFile(String value) {
        if (value == null) return "";
        String name = value.trim();
        if (!name.matches("art-[0-9a-f]{16}\\.(png|jpg|gif|bmp)")) return "";
        return name;
    }


    private static String text(JsonObject root, String key) {
        JsonElement value = root.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static boolean bool(JsonObject root, String key) {
        JsonElement value = root.get(key);
        return value != null && !value.isJsonNull() && value.getAsBoolean();
    }

    private static long number(JsonObject root, String key) {
        try {
            JsonElement value = root.get(key);
            return value == null || value.isJsonNull() ? 0L : (long) value.getAsDouble();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public boolean hasSession() {
        return session;
    }

    /** 这台播放器给没给封面图。给了的话 {@link #artFile()} 是那个文件名（不含目录）。 */
    public boolean hasArtwork() {
        return session && !artFile.isEmpty();
    }

    /** 封面图的文件名，已经在 {@link #safeArtFile(String)} 里过了一遍白名单。 */
    public String artFile() {
        return artFile;
    }

    /**
     * 助手有没有把那支程序与音量合成器里的一格对上。
     *
     * <p>没有对上是常态而不是故障：独占模式播放没有合成器会话，AUMID 与进程名可能对不上。
     * 调用方在 false 时必须什么都不做 —— 尤其不能退回去动主音量。</p>
     */
    public boolean hasVolume() {
        return session && volumeLevel >= 0f;
    }

    /** 那支程序在音量合成器里的电平，0..1；{@link #hasVolume()} 为 false 时是 -1。 */
    public float volumeLevel() {
        return volumeLevel;
    }

    /** 音量会话所属进程号，只进日志。 */
    public int volumePid() {
        return volumePid;
    }

    public boolean isPlaying() {
        return session && "Playing".equalsIgnoreCase(state);
    }

    /** 外部播放器是否已经停止或结束。暂停不算：暂停时歌词要停在原行，而不是清空。 */
    public boolean isStopped() {
        return session && ("Stopped".equalsIgnoreCase(state) || "Closed".equalsIgnoreCase(state));
    }

    public String title() {
        return title;
    }

    public String artist() {
        return artist;
    }

    public String album() {
        return album;
    }

    public String app() {
        return app;
    }

    public String state() {
        return state;
    }

    public long positionMs() {
        return positionMs;
    }

    public long durationMs() {
        return durationMs;
    }

    public String error() {
        return error;
    }

    public long sampledAtMs() {
        return sampledAtMs;
    }

    public boolean canPlay() {
        return canPlay;
    }

    public boolean canPause() {
        return canPause;
    }

    public boolean canToggle() {
        return canToggle;
    }

    public boolean canNext() {
        return canNext;
    }

    public boolean canPrevious() {
        return canPrevious;
    }

    public boolean canSeek() {
        return canSeek;
    }

    /** 采样是否还新鲜。超过这个窗口说明助手没在回话，界面要按「不可用」处理。 */
    public boolean isFresh(long nowMs, long windowMs) {
        return session && sampledAtMs > 0L && nowMs - sampledAtMs <= windowMs;
    }

    /**
     * 归一化后的曲名，用于匹配与换歌判定。
     *
     * <p>只做空白折叠与小写，<b>不</b>剥括号里的版本标注：网易云曲库里有大量
     * 「Live」「Acoustic」版本，把标注当噪声删掉会让匹配跳到另一首歌上去。</p>
     */
    public String normalizedTitle() {
        return normalize(title);
    }

    public String normalizedArtist() {
        return normalize(artist);
    }

    /**
     * 同一首外部曲目的键。带上应用名：同一个系统里两个播放器可以同时注册会话，
     * 而「Chrome 里的这首歌」与「网易云里的这首歌」对用户是两件事。
     */
    public String trackKey() {
        return normalize(app) + "#" + normalize(title) + "#" + normalize(artist);
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return "";
        StringBuilder collapsed = new StringBuilder(trimmed.length());
        boolean previousSpace = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean space = c == ' ' || c == '\t' || c == '\n' || c == '\r';
            if (space) {
                if (!previousSpace && collapsed.length() > 0) collapsed.append(' ');
                previousSpace = true;
            } else {
                collapsed.append(Character.toLowerCase(c));
                previousSpace = false;
            }
        }
        return collapsed.toString();
    }

    /** 供日志与状态标签用的一行摘要。 */
    @Override
    public String toString() {
        return "ExternalSnapshot{session=" + session + ", app='" + app + "', title='" + title
                + "', artist='" + artist + "', posMs=" + positionMs + ", durMs=" + durationMs
                + ", state='" + state + "', vol=" + volumeLevel + "/pid" + volumePid
                + ", error='" + error + "'}";
    }
}
