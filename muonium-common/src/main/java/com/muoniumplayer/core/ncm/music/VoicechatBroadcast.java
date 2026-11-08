package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;
import repackage.com.jsyn.engine.SynthesisEngine;

import java.io.File;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 「语音广播」：把正在放的音乐合并进 Simple Voice Chat 的麦克风上行，让附近玩家在你所在
 * 位置听到歌——你同时还能正常说话（SVC 端把麦克风与合并进来的音乐相加）。
 *
 * <h2>声音从哪来</h2>
 * <p>{@link EngineOutputTap}（最终混音输出观察者，蝰蛇音效与场景声学之后、写设备之前）把
 * 每缓冲 PCM 灌进这里的环形缓冲。广播看到的就是<b>写进声卡的那份信号</b>，只有一处例外：
 * 「失焦压低」({@link FocusVolumeDucker}) 是它之后的最后一级，你切窗口时听友不该跟着听歌
 * 变小声，所以这里把那一档倍率除回去（除 0 视作 1，即压到静音档时广播满音量）。播放暂停时
 * 引擎出的是静音，听友那边自然安静；电台/直播（{@link LiveStreamPlayer}）绕过引擎，不广播。</p>
 *
 * <h2>声音怎么走</h2>
 * <p>SVC 的麦克风线程每 20ms 采集一帧并对上行派发 {@code MergeClientSoundEvent}（静音、
 * 未说话也派发，见 {@link MuoniumVoicechatPlugin}）。插件每次回调调 {@link #pollFrame()}
 * 取走<b>恰好 960 采样</b>（48kHz 单声道 16bit，SVC 的硬格式）交给事件合并。定拍完全交给
 * 麦克风线程的采集节奏：它跑多快，广播就送多快，本类不做任何定时。</p>
 *
 * <h2>环缓冲（SPSC）</h2>
 * <p>写侧只有音频线程，读侧只有麦克风线程；{@link #writeSeq} 只写侧碰、{@link #readSeq}
 * 只读侧碰，用 AtomicLong 保证可见性。容量 {@link #CAPACITY}（1&lt;&lt;22 采样 ≈ 80 秒）：
 * <ul>
 *   <li><b>写侧满则整缓冲丢弃</b>（听友那边没有消费者时必然溢满），永不碰 readSeq ——
 *       读侧读到的半帧不会撕裂。</li>
 *   <li><b>读侧积压超 {@link #STALE_LIMIT}</b>（约 4 秒）就跳到新鲜端留 1 秒——单人世界挂机
 *       攒下的旧帧不该在进服后当「新歌」播出来。只有读侧改 readSeq，跳转无撕裂。</li>
 * </ul></p>
 *
 * <h2>持久化</h2>
 * <p>{@code config/muonium/voicechat_broadcast.json}，照 {@link FocusVolumeDucker} 那一族：
 * 懒加载、锁内写、写失败静默。<b>默认关</b>——往语音通道灌歌要用户明确点头。</p>
 */
public final class VoicechatBroadcast {

    /** SVC 硬格式：48kHz、20ms 一帧、16bit 单声道。帧长 960 采样是上行包的硬门槛，差一点整包被丢。 */
    static final int SAMPLE_RATE = 48_000;
    static final int FRAME_SIZE = SAMPLE_RATE / 50;
    /** 广播预乘：SVC 端把麦克风与音乐<b>相加</b>后截断，满幅音乐+说话会削顶，留一点对讲余量。 */
    private static final float BROADCAST_GAIN = 0.9f;
    /** 环容量（采样数）：1&lt;&lt;22 ≈ 80 秒，远超引擎写设备的超前量，只剩「没消费者」才会填满。 */
    private static final int CAPACITY = 1 << 22;
    private static final int MASK = CAPACITY - 1;
    /** 读侧积压超过这个采样数（约 4 秒）就跳到新鲜端，不留 1 秒的余韵。 */
    private static final int STALE_LIMIT = SAMPLE_RATE * 4;
    private static final int KEEP_ON_SKIP = SAMPLE_RATE;

    private static final Gson GSON = new GsonBuilder().create();
    private static final Object LOCK = new Object();

    private static final short[] RING = new short[CAPACITY];
    /** 下一个要写入的采样序号。只有音频线程（写侧）碰。 */
    private static final AtomicLong WRITE_SEQ = new AtomicLong();
    /** 下一个要读走的采样序号。只有麦克风线程（读侧）碰。 */
    private static final AtomicLong READ_SEQ = new AtomicLong();

    /** 双检锁的快路径标记：volatile，只在读盘结束后置位。 */
    private static volatile boolean loaded;
    /** 测试 IO 闸：置位后 load/save 全部旁路（测试 JVM 不许碰真实工作目录的 voicechat_broadcast.json）。 */
    static boolean testIoHooked;
    /** 开关（默认关）。只在锁内写；音频线程与麦克风线程无锁读。 */
    private static volatile boolean enabled;

    /** 幂等挂接标记；挂接动作发生在音频装载点，见 {@link #ensureInstalled()}。 */
    private static volatile boolean tapInstalled;

    /** 写侧：挂在 {@link EngineOutputTap} 上的采集回调。音频线程上跑：零分配、无锁、不抛。 */
    private static final EngineOutputTap.Listener TAP = VoicechatBroadcast::capture;

    private VoicechatBroadcast() {
    }

    // ════════════════════════ 挂接（音频装载点） ════════════════════════

    /**
     * 把采集回调挂上 {@link EngineOutputTap}。幂等。调用时机与 {@code ViperRuntime.ensureInstalled()}
     * 同款：只在 {@code AudioPlayer.loadUsableSoundFile} 这类「此刻引擎该在」的装载点调——
     * 提前建引擎会破坏设备兜底的探测顺序，这里不设防，靠调用方守约。
     */
    public static void ensureInstalled() {
        if (tapInstalled) return;
        synchronized (LOCK) {
            if (tapInstalled) return;
            EngineOutputTap.install();
            EngineOutputTap.addListener(TAP);
            tapInstalled = true;
        }
    }

    // ════════════════════════ 写侧（音频线程） ════════════════════════

    /** 写侧门控：开着且确有歌在放（暂停/停止时引擎出的是静音，灌它只是白耗）。 */
    private static boolean shouldCapture() {
        return enabled && CloudMusic.playing.get();
    }

    /** 包私有：环缓冲的采集逻辑要真对象测试（同包直接灌 PCM）。音频线程上跑。 */
    static void capture(double[] interleaved, int channels, int sampleRate) {
        try {
            if (channels <= 0 || interleaved == null || interleaved.length == 0) return;
            if (sampleRate != SAMPLE_RATE || !shouldCapture()) return;

            // 失焦压低是本观察者之后的最后一级，这里把它除回去：听友不该跟着你切窗口听歌变小声。
            // gain() 与压低级同线程同缓冲内先后执行，时序差是纳秒级；压到 0 视作 1（广播满音量）。
            float restore = FocusVolumeDucker.gain();
            float scale = BROADCAST_GAIN / (restore <= 1.0E-4f ? 1.0f : restore);

            long write = WRITE_SEQ.get();
            long read = READ_SEQ.get();
            if (write - read + interleaved.length > CAPACITY) {
                // 满了：读侧没有消费者（SVC 不在/没连上/没在回调）。整缓冲丢弃，等读侧自己追。
                return;
            }
            int n = interleaved.length;
            for (int i = 0; i < n; i++) {
                double v = interleaved[i] * scale;
                short s = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) Math.round(v * 32767.0)));
                RING[(int) ((write + i) & MASK)] = s;
            }
            WRITE_SEQ.set(write + n);
        } catch (Throwable ignored) {
            // 采集炸了不许连累播放主循环。
        }
    }

    // ════════════════════════ 读侧（SVC 麦克风线程） ════════════════════════

    /**
     * 取走下一帧（960 采样，48kHz 单声道 16bit）。SVC 麦克风线程每帧调一次；没有新鲜数据
     * 返回 null（本帧不合并，SVC 端上行就是纯麦克风）。<b>只在麦克风线程调用</b>——
     * readSeq 的读改写无锁，靠「单读侧」约定成立。
     */
    public static short[] pollFrame() {
        long read = READ_SEQ.get();
        long write = WRITE_SEQ.get();
        long backlog = write - read;
        if (backlog > STALE_LIMIT) {
            // 陈年积压（挂机攒的/换服才第一次消费）：跳到新鲜端留 1 秒，别把旧货当新歌唱。
            read = Math.max(0L, write - KEEP_ON_SKIP);
        }
        if (write - read < FRAME_SIZE) return null;
        short[] frame = new short[FRAME_SIZE];
        for (int i = 0; i < FRAME_SIZE; i++) {
            frame[i] = RING[(int) ((read + i) & MASK)];
        }
        READ_SEQ.set(read + FRAME_SIZE);
        return frame;
    }

    // ════════════════════════ 设置 ════════════════════════

    public static boolean isEnabled() {
        load();
        return enabled;
    }

    public static void setEnabled(boolean value) {
        load();
        synchronized (LOCK) {
            if (enabled == value) return;
            enabled = value;
            saveLocked();
        }
    }

    private static void load() {
        if (loaded) return;
        synchronized (LOCK) {
            if (loaded) return;
            try {
                if (testIoHooked) return; // 测试闸:不读真实文件
                File file = ConfigPaths.VOICECHAT_BROADCAST;
                if (!file.isFile()) return;
                JsonObject root = JsonConfigStorage.readObject(file, GSON);
                if (root == null) return;
                if (root.has("enabled") && !root.get("enabled").isJsonNull()) {
                    enabled = root.get("enabled").getAsBoolean();
                }
            } catch (Throwable ignored) {
                // 配置坏了用默认值（关），不能影响播放。
            } finally {
                loaded = true;
            }
        }
    }

    private static void saveLocked() {
        if (testIoHooked) return; // 测试闸:不写真实文件
        try {
            File parent = ConfigPaths.VOICECHAT_BROADCAST.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) return;
            JsonObject root = new JsonObject();
            root.addProperty("enabled", enabled);
            JsonConfigStorage.writeObject(ConfigPaths.VOICECHAT_BROADCAST, GSON, root);
        } catch (Throwable ignored) {
        }
    }

    /** 测试专用：清 loaded 标记与全部运行态，隔离文件系统与环缓冲。 */
    static void resetForTest() {
        synchronized (LOCK) {
            loaded = false;
            testIoHooked = true;
            enabled = false;
            tapInstalled = false;
            WRITE_SEQ.set(0L);
            READ_SEQ.set(0L);
            java.util.Arrays.fill(RING, (short) 0);
        }
    }
}
