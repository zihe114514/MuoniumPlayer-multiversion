package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.screens.ncm.LyricLine;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 *
 * <table>
 *   <tr><td>{@code updateClock()}</td><td>{@link #updateClock()}</td></tr>
 *   <tr><td>{@code render()} 的行切换/暂停逻辑</td><td>{@link #update(...)}</td></tr>
 *   <tr><td>{@code spawn()}</td><td>{@link Floating#spawn}</td></tr>
 *   <tr><td>{@code charTimes()}</td><td>{@link Floating#charTimesOf}</td></tr>
 *   <tr><td>{@code splitWords()}/{@code wrap()}</td><td>{@link #splitWords}/{@link #wrap}</td></tr>
 *   <tr><td>{@code drawWorld()} 的 facePlayer / keepAway / 尺寸公式</td><td>{@link Row#resolve}</td></tr>
 *   <tr><td>{@code drawAnimated()} 逐字出现 + smoothstep</td><td>{@link LyricGlyphs#build}</td></tr>
 *   <tr><td>{@code withWiggle()} + {@code hashRand()}</td><td>{@link #hashRand}（渲染层消费）</td></tr>
 *   <tr><td>{@code appearOf()}/{@code drawFading()}</td><td>{@link #resumeFadeOf}/{@link #fadeAlphaOf}</td></tr>
 * </table>
 *
 * {@code CloudMusic.getSnapshot()}（含歌词偏移的唯一时钟）；CjkFont 平滑字体换成
 * {@code LyricTextureCache} 纹理烘焙（同一观感）；DeepGlow 后处理换成 TextGlow 烘焙。</p>
 */
public final class WorldLyricsState {


    public static final int TEXT_RGB = 0xF7E1D7;
    public static final float LYRIC_SIZE = 10f;
    public static final float LINE_H = 11f;
    public static final float WRAP_WIDTH = 200f;
    private static final long REVEAL_PER_CHAR_MS = 55L;
    private static final long REVEAL_MIN_MS = 250L;
    private static final double REVEAL_LINE_RATIO = 0.6;
    private static final float CHAR_FADE_MS = 180f;
    private static final float CHAR_LIFT_PX = 5f;
    public static final float FADE_SECONDS = 0.4f;
    public static final float FADE_LIFT = 0.4f;
    public static final double RESUME_FADE_SECONDS = 0.3;
    private static final double SILENCE_QUIT_SECONDS = 3.0;
    public static final double KEEP_AWAY_MIN_RATIO = 0.6;
    private static final float SPAWN_PITCH_DROP = 3f;
    private static final float SPAWN_PITCH_RANDOM = 10f;
    public static final float PX_TO_WORLD = 0.03f;
    public static final float DISTANCE_NORM = 6.5f;


    private Floating active;
    private final List<Floating> fading = new ArrayList<>();
    /** 当前歌曲 key（netease:{id}），换歌检测。 */
    private String currentKey;
    private long lastSeenNanos;
    private int lastIndex = -1;
    private boolean wasPaused;
    private final Random random = new Random();

    // ==================== 数据模型 ====================

    /**
     */
    public static final class Floating {
        public final List<String> lines;      // wrap 拆行后的文本
        public final long startMs;
        public final long revealMs;           // 逐字出现的总时长
        public double x, y, z;
        public double spawnYawDeg, spawnPitchDeg;
        /** 每个非空白字符出现的时间（相对这句开始，毫秒）——word pop 用。 */
        public final long[] charTimes;

        Floating(List<String> lines, long startMs, long revealMs,
                 double x, double y, double z, double spawnYawDeg, double spawnPitchDeg,
                 long[] charTimes) {
            this.lines = lines;
            this.startMs = startMs;
            this.revealMs = revealMs;
            this.x = x;
            this.y = y;
            this.z = z;
            this.spawnYawDeg = spawnYawDeg;
            this.spawnPitchDeg = spawnPitchDeg;
            this.charTimes = charTimes;
        }

        long fadeStartNanos;
        long resumedAtNanos;
    }

    /** 相机输入。宿主每帧提供，避免 common 直接碰 MC 类。 */
    public interface CameraView {
        double eyeX();
        double eyeY();
        double eyeZ();
        double lookX();
        double lookY();
        double lookZ();
        /** 视线 yaw（度，MC 口径：-180..180，0 朝 +Z）。 */
        double yawDeg();
        /** 视线 pitch（度，MC 口径：-90..90）。 */
        double pitchDeg();
    }

    /** 渲染参数快照（设置门面）。 */
    public interface Params {
        double distance();
        boolean splitLines();
        double spreadDeg();
        double scale();
        boolean facePlayer();
        boolean keepAway();
        boolean wordPop();
        boolean wiggle();
        double wiggleFps();
        double wiggleAmount();
    }

    /** 一行歌词这一帧的最终落点与字形状态，渲染层只读。 */
    public static final class Row {
        public final Floating floating;
        /** 锚点（世界坐标）。facePlayer/keepAway 处理后的最终位置。 */
        public final double x, y, z;
        /** 本帧相机世界位置（渲染层换算镜头相对坐标用）。 */
        public final double camX, camY, camZ;
        /** 字面朝向（Face player=朝向镜头的反算角；否则 spawn 冻结角）。 */
        public final double yawDeg, pitchDeg;
        public final float scale;
        /** 0..1 显示透明度。 */
        public final float alpha;
        /** 淡出上浮（方块）。 */
        public final float lift;
        public final boolean active;

        Row(Floating floating, double x, double y, double z,
            double camX, double camY, double camZ,
            double yawDeg, double pitchDeg, float scale, float alpha, float lift, boolean active) {
            this.floating = floating;
            this.x = x; this.y = y; this.z = z;
            this.camX = camX; this.camY = camY; this.camZ = camZ;
            this.yawDeg = yawDeg;
            this.pitchDeg = pitchDeg;
            this.scale = scale;
            this.alpha = alpha;
            this.lift = lift;
            this.active = active;
        }
    }

    /**
     * 下标（wiggle 种子）与 pop 缩放。由渲染层按此直接画。
     */
    public static final class LyricGlyph {
        public final char c;
        public final float x, y;   // 字的顶部（与 MC 字体一致）
        public final float alpha;  // 0..1
        public final int k;        // 非空白字符序号（wiggle/确定性随机种子用）
        public final float pop;    // word pop 缩放（1 = 无）

        LyricGlyph(char c, float x, float y, float alpha, int k, float pop) {
            this.c = c;
            this.x = x;
            this.y = y;
            this.alpha = alpha;
            this.k = k;
            this.pop = pop;
        }
    }

    /** 一句歌词的全部字形布局（相对平面原点，单位：文字像素）。 */
    public static final class LyricGlyphs {
        public final List<LyricGlyph> glyphs = new ArrayList<>();
        public final int seed;
        public final float totalHeight;

        LyricGlyphs(int seed, float totalHeight) {
            this.seed = seed;
            this.totalHeight = totalHeight;
        }
    }


    /**
     * 推进一帧。返回本帧要画的所有行（active + fading）。
     * {@code snapshotPlaying}/{@code snapshotPositionMs} 由宿主从 CloudMusic.getSnapshot() 取好传入。
     */
    public Frame update(CameraView camera, Params params, long nowNanos,
                        boolean snapshotPlaying, long snapshotPositionMs) {
        updateClock(snapshotPlaying, nowNanos);

        boolean paused = currentKey != null && !snapshotPlaying;

        // idx 即使暂停也算（indexAt 按时间戳二分），恢复时 idx==lastIndex 才算「恢复」而不是「换行」。
        List<LyricLine> lines = effectiveLines(params.splitLines());
        int idx = indexAtTimestamp(lines, snapshotPositionMs);
        LyricLine current = (!paused && idx >= 0 && idx < lines.size())
                ? lines.get(idx) : null;
        if (paused) {
            retireActive(nowNanos);
            wasPaused = true;
        } else if (idx != lastIndex || wasPaused) {
            boolean resumed = wasPaused && idx == lastIndex;
            wasPaused = false;
            lastIndex = idx;
            retireActive(nowNanos);
            if (idx >= 0 && idx < lines.size()) {
                LyricLine line = lines.get(idx);
                if (line != null && !line.isBreakLine && line.lyric != null && !line.lyric.isEmpty()) {
                    active = WorldLyricsState.spawn(line, lines, idx, camera, params, random);
                    if (resumed) active.resumedAtNanos = nowNanos;
                }
            }
        }

        Iterator<Floating> it = fading.iterator();
        while (it.hasNext()) {
            Floating f = it.next();
            if ((nowNanos - f.fadeStartNanos) / 1.0E9f > FADE_SECONDS) it.remove();
        }

        // 组帧
        List<Row> out = new ArrayList<>(1 + fading.size());
        if (active != null) {
            out.add(WorldLyricsState.resolve(active, camera, params,
                    resumeFadeOf(active, nowNanos), true, 0f));
        }
        for (Floating f : fading) {
            float a = fadeAlphaOf(f, nowNanos);
            if (a > 0.01f) {
                float t = (nowNanos - f.fadeStartNanos) / 1.0E9f;
                out.add(WorldLyricsState.resolve(f, camera, params, a, false, t * FADE_LIFT));
            }
        }
        return new Frame(out, currentKey != null);
    }

    private void updateClock(boolean playing, long nowNanos) {
        String key = CloudMusic.lyricsTrackKey;
        boolean hasTrack = key != null && CloudMusic.lyricsMatchCurrentTrack();
        if (!hasTrack) {
            if (currentKey != null && (nowNanos - lastSeenNanos) / 1.0E9 > SILENCE_QUIT_SECONDS) {
                currentKey = null;
                retireActive(nowNanos);
                lastIndex = -1;
            }
            return;
        }
        lastSeenNanos = nowNanos;
        // 但实例不同（重新提交的同一首歌）不应触发换歌重置。
        if (!java.util.Objects.equals(key, currentKey)) {
            currentKey = key;
            retireActive(nowNanos);
            lastIndex = -1;
        }
    }

    private static int indexAtTimestamp(List<LyricLine> lines, long positionMs) {
        if (lines == null || lines.isEmpty()) return -1;
        int lo = 0, hi = lines.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            LyricLine l = lines.get(mid);
            if (l != null && l.timestamp <= positionMs) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    /** 当前句转淡出。 */
    private void retireActive(long nowNanos) {
        if (active != null) {
            active.fadeStartNanos = nowNanos;
            fading.add(active);
            active = null;
        }
    }

    public static float resumeFadeOf(Floating f, long nowNanos) {
        return f.resumedAtNanos == 0L ? 1f
                : (float) clamp01((nowNanos - f.resumedAtNanos) / 1.0E9 / RESUME_FADE_SECONDS);
    }

    public static float fadeAlphaOf(Floating f, long nowNanos) {
        return (float) clamp01(1.0 - (nowNanos - f.fadeStartNanos) / 1.0E9 / FADE_SECONDS);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    /** yaw/pitch（度，MC 口径）→ 视线单位向量。spawn 与测试共用。 */
    static double[] yawPitchDirectionStatic(double yawDeg, double pitchDeg) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        return new double[] {
                -Math.sin(yaw) * Math.cos(pitch),
                -Math.sin(pitch),
                Math.cos(yaw) * Math.cos(pitch)
        };
    }


    /**
     * yaw 带 spread 随机偏角、pitch 下沉 3~13°，之后位置不再变。
     */
    static Floating spawn(LyricLine line, List<LyricLine> lines, int lineIndex, CameraView camera,
                          Params params, Random random) {
        List<String> wrapped = wrap(line.lyric, WRAP_WIDTH);
        long nextMs = nextLineStartMs(lines, lineIndex);
        long[] charTimes = charTimesOf(line, nextMs);

        long chars = 0;
        for (String s : wrapped) chars += s.length();
        long lineDur = Math.max(400L, nextMs - line.timestamp);
        long reveal = (long) Math.min(
                Math.max(REVEAL_MIN_MS, chars * REVEAL_PER_CHAR_MS),
                lineDur * REVEAL_LINE_RATIO);

        double yaw = camera.yawDeg() + (random.nextDouble() * 2.0 - 1.0) * params.spreadDeg();
        // 再夹 -60..30。之前把 player.pitch 也取了负 —— 平视时看不出，抬头/低头时歌词会跑到另一侧。
        double pitch = clampPitch(camera.pitchDeg() - SPAWN_PITCH_DROP
                - random.nextDouble() * SPAWN_PITCH_RANDOM);
        double[] dir = yawPitchDirectionStatic(yaw, pitch);
        double d = params.distance();
        return new Floating(wrapped, line.timestamp, reveal,
                camera.eyeX() + dir[0] * d, camera.eyeY() + dir[1] * d, camera.eyeZ() + dir[2] * d,
                yaw, pitch, charTimes);
    }

    private static long nextLineStartMs(List<LyricLine> lines, int lineIndex) {
        if (lines != null && lineIndex + 1 < lines.size()) {
            LyricLine next = lines.get(lineIndex + 1);
            if (next != null) return next.timestamp;
        }
        LyricLine cur = lines != null && lineIndex >= 0 && lineIndex < lines.size()
                ? lines.get(lineIndex) : null;
        return cur != null ? cur.timestamp + 5000L : 0L;
    }

    private static double clampPitch(double deg) {
        return Math.max(-60.0, Math.min(30.0, deg));
    }

    /**
     * 有逐字歌词用真实时间，没有就把一句平均分给每个词（每词 330ms、最多占行 75%）。
     */
    static long[] charTimesOf(LyricLine line, long nextMs) {
        List<Long> times = new ArrayList<>();
        if (line.hasTimedWords() && !line.words.isEmpty()) {
            for (LyricLine.Word w : line.words) {
                if (w == null || w.word == null) continue;
                long at = Math.max(0L, w.timestamp - line.timestamp);
                for (int i = 0; i < w.word.length(); i++) {
                    if (!Character.isWhitespace(w.word.charAt(i))) times.add(at);
                }
            }
        } else {
            List<String> toks = splitWords(line.lyric);
            long dur = Math.max(300L, nextMs - line.timestamp);
            double span = Math.min(dur * 0.75, toks.size() * 330.0);
            for (int i = 0; i < toks.size(); i++) {
                long at = (long) (span * i / Math.max(1, toks.size()));
                for (int c = 0; c < toks.get(i).length(); c++) times.add(at);
            }
        }
        long[] out = new long[times.size()];
        for (int i = 0; i < out.length; i++) out[i] = times.get(i);
        return out;
    }

    static List<String> splitWords(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                if (sb.length() > 0) {
                    out.add(sb.toString());
                    sb.setLength(0);
                }
                cjk = 0;
            } else if (c >= 0x2E80) {
                if (sb.length() > 0 && cjk == 0) {
                    out.add(sb.toString());
                    sb.setLength(0);
                }
                sb.append(c);
                cjk++;
                if (cjk >= 2) {
                    out.add(sb.toString());
                    sb.setLength(0);
                    cjk = 0;
                }
            } else {
                if (cjk > 0) {
                    if (sb.length() > 0) {
                        out.add(sb.toString());
                        sb.setLength(0);
                    }
                    cjk = 0;
                }
                sb.append(c);
            }
        }
        if (sb.length() > 0) out.add(sb.toString());
        return out;
    }

    static List<String> wrap(String text, float maxWidth) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int lastSpace = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(c);
            if (c == ' ') lastSpace = sb.length() - 1;
            if (estimatedWidth(sb.toString()) > maxWidth && sb.length() > 1) {
                if (lastSpace > 0) {
                    out.add(sb.substring(0, lastSpace).trim());
                    String rest = sb.substring(lastSpace + 1);
                    sb.setLength(0);
                    sb.append(rest);
                } else {
                    out.add(sb.substring(0, sb.length() - 1).trim());
                    sb.setLength(0);
                    sb.append(c);
                }
                lastSpace = -1;
            }
        }
        if (!sb.toString().trim().isEmpty()) out.add(sb.toString().trim());
        out.removeIf(String::isEmpty);
        return out;
    }

    /** MC 像素字估宽：全角 9px、半角 5px（1.21.1 原版字体全角字形宽 8~9px；精确值渲染层量）。 */
    private static float estimatedWidth(String s) {
        float w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += s.charAt(i) >= 0x2E80 ? 9f : 5f;
        }
        return w;
    }


    /** 一次世界渲染要画的全部内容。 */
    public static final class Frame {
        public final List<Row> rows;
        public final boolean available;

        Frame(List<Row> rows, boolean available) {
            this.rows = rows;
            this.available = available;
        }
    }

    /**
     * keep away 硬推、face player 从镜头→歌词方向反算朝向、尺寸随实际距离。
     */
    static Row resolve(Floating f, CameraView camera, Params params,
                       float alpha, boolean isActive, float lift) {
        double rx = f.x - camera.eyeX();
        double ry = f.y - camera.eyeY();
        double rz = f.z - camera.eyeZ();
        double len = Math.sqrt(rx * rx + ry * ry + rz * rz);

        double minD = params.distance() * KEEP_AWAY_MIN_RATIO;
        if (params.keepAway() && len > 1e-4 && len < minD) {
            double k = minD / len;
            f.x = camera.eyeX() + rx * k;
            f.y = camera.eyeY() + ry * k;
            f.z = camera.eyeZ() + rz * k;
            rx *= k; ry *= k; rz *= k;
            len = minD;
        }

        double yaw;
        double pitch;
        if (params.facePlayer() && len > 1e-4) {
            yaw = Math.toDegrees(Math.atan2(-rx, rz));
            pitch = Math.toDegrees(Math.atan2(-ry, Math.sqrt(rx * rx + rz * rz)));
        } else {
            yaw = f.spawnYawDeg;
            pitch = f.spawnPitchDeg;
        }

        float scale = (float) (PX_TO_WORLD * params.scale() * (len / DISTANCE_NORM));

        return new Row(f, f.x, f.y + lift, f.z,
                camera.eyeX(), camera.eyeY(), camera.eyeZ(),
                yaw, pitch, scale, alpha, lift, isActive);
    }


    /**
     * 构建一句的字形布局：逐字出现（每个字在 reveal 窗口内依次淡入、smoothstep、
     * 从下往上浮 5px），word pop 开时按 charTimes 整词弹出（1+0.55·back² 先大后回）。
     * 坐标以平面中心为原点、单位是文字像素（渲染层乘 scale 进世界）。
     *
     * @param pixelWidthOf 单行像素宽（宿主用 MC 字体量）
     */
    public static LyricGlyphs buildGlyphs(Floating f, long songNowMs, float alphaMul,
                                          boolean wordPop, PixelWidthOf pixelWidthOf) {
        List<String> lines = f.lines;
        long elapsedMs = songNowMs - f.startMs;
        boolean flashMode = wordPop && f.charTimes.length > 0;
        int total = 0;
        for (String s : lines) total += s.length();
        total = Math.max(1, total);

        LyricGlyphs out = new LyricGlyphs(lines.hashCode(), lines.size() * LINE_H);
        float y = -(lines.size() * LINE_H) / 2f;
        int k = 0;      // 字形下标（含空白，与  一致）
        int n = 0;      // 非空白字符序号
        for (String line : lines) {
            float w = pixelWidthOf.widthOf(line);
            float x = -w / 2f;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (flashMode && !Character.isWhitespace(c)) {
                    long at = f.charTimes[Math.min(n, f.charTimes.length - 1)];
                    n++;
                    float t = (elapsedMs - at) / 160f;
                    if (t >= 0f) {
                        float a = (float) clamp01(t * 3f) * alphaMul;
                        float back = (float) clamp01(1f - t);
                        float pop = 1f + 0.55f * back * back;
                        if (a > 0.02f) {
                            out.glyphs.add(new LyricGlyph(c, x, y, a, k, pop));
                        }
                    }
                } else if (!flashMode) {
                    float appearAt = (float) ((double) k / total * f.revealMs);
                    float a = (float) clamp01((elapsedMs - appearAt) / CHAR_FADE_MS);
                    a = a * a * (3f - 2f * a);  // smoothstep
                    float alpha = a * alphaMul;
                    if (alpha > 0.02f && !Character.isWhitespace(c)) {
                        out.glyphs.add(new LyricGlyph(c, x, y + (1f - a) * CHAR_LIFT_PX, alpha, k, 1f));
                    }
                }
                x += pixelWidthOf.widthOf(String.valueOf(c));
                k++;
            }
            y += LINE_H;
        }
        return out;
    }

    /** 宿主量像素宽的回调（MC 字体或纹理缓存的度量）。 */
    public interface PixelWidthOf {
        float widthOf(String s);
    }


    /**
     * 由 (字序号, 第几帧, 种类, 种子) 得到一个固定的 -1..1 随机数
     */
    public static float hashRand(int k, long frame, int salt, int seed) {
        int h = k * 73856093 ^ (int) (frame * 19349663L) ^ (salt * 83492791) ^ seed;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return (h & 0xFFFF) / 32767.5f - 1f;
    }

    public static long wiggleFrame(long wallMillis, double fps) {
        return (long) (wallMillis * Math.max(1.0, fps) / 1000.0);
    }

    public static float[] wiggleOf(LyricGlyph g, int seed, long frame, double amount) {
        float rot = hashRand(g.k, frame, 1, seed) * 9f * (float) amount;
        float dx = hashRand(g.k, frame, 2, seed) * 0.8f * (float) amount;
        float dy = hashRand(g.k, frame, 3, seed) * 1.6f * (float) amount;
        float sc = 1f + hashRand(g.k, frame, 4, seed) * 0.12f * (float) amount;
        return new float[] {rot, dx, dy, sc};
    }

    // ==================== 入口 ====================


    private List<LyricLine> cachedSplitLines;
    private long cachedSplitRevision = -1;

    static List<LyricLine> effectiveLines(boolean splitLines) {
        List<LyricLine> raw = CloudMusic.lyrics;
        if (raw == null || raw.isEmpty()) return raw;
        if (!splitLines) return raw;
        WorldLyricsState s = INSTANCE;
        long revision = CloudMusic.lyricsRevision;
        if (s.cachedSplitLines != null && s.cachedSplitRevision == revision) {
            return s.cachedSplitLines;
        }
        List<LyricLine> out = new ArrayList<>(raw.size());
        for (LyricLine l : raw) {
            if (l == null) continue;
            out.addAll(SplitLines.split(l, true));
        }
        s.cachedSplitLines = out;
        s.cachedSplitRevision = revision;
        return out;
    }

    private WorldLyricsState() {
    }

    /** 单例：3D 歌词是全局唯一的常驻渲染。 */
    private static final WorldLyricsState INSTANCE = new WorldLyricsState();

    public static WorldLyricsState get() {
        return INSTANCE;
    }

    /** 歌词不可用时整体隐藏（无词、无歌）。供渲染层快速短路。 */
    public static boolean hasLyrics() {
        return CloudMusic.lyrics != null && !CloudMusic.lyrics.isEmpty()
                && CloudMusic.lyricsMatchCurrentTrack();
    }

    /** 供测试：重置全部帧间状态。 */
    void resetForTest() {
        active = null;
        fading.clear();
        currentKey = null;
        lastSeenNanos = 0;
        lastIndex = -1;
        wasPaused = false;
    }
}
