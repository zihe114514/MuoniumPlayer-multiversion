package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.io.File;

/**
 * 持久化的 automix（无缝曲目交接）偏好设置。
 *
 * <p>automix 让退出曲目仍可听见，而下一首已在第二个 deck 上解码并运行，
 * 因此队列前进变成重叠，而非旧的关闭播放器/sleep(250ms)/
 * 下载/解码间隙。这里全部为可关闭：禁用 automix 后，播放路径
 * 与原始单 deck 序列逐字节一致。</p>
 */
public final class AutomixSettings {

    /** 用户在 HUD 设置中选择的过渡引擎。 */
    public enum Engine {
        LEGACY("原版智能淡化"),
        FOLIA("智能混音");

        private final String label;

        Engine(String label) { this.label = label; }
        public String getLabel() { return label; }

        public static Engine fromStored(String value) {
            if (value == null) return DEFAULT_ENGINE;
            for (Engine engine : values()) {
                if (engine.name().equalsIgnoreCase(value) || engine.label.equalsIgnoreCase(value)) return engine;
            }
            return DEFAULT_ENGINE;
        }
    }

    /**
     * 出厂默认引擎。
     *
     * <p>Folia 在缺模型时会自行退回纯 DSP 分析（见 {@code FoliaAutomixPlanner} 里 profile 为 null
     * 的那些分支），所以把它当默认不会让「没装模型」的电脑退化成硬切；想要旧链路的用户仍然可以
     * 在 HUD 设置里显式切回 {@link Engine#LEGACY}。</p>
     *
     * <p>所有「没有显式选择」的回退都指向这里，而不是各自写死一个枚举常量——否则默认值一改，
     * 就总有几处漏改，表现成「同一个概念在不同场合给出不同答案」。</p>
     */
    public static final Engine DEFAULT_ENGINE = Engine.FOLIA;


    /** 仍读作混音而非硬切的最短重叠。 */
    public static final float MIN_OVERLAP_SECONDS = 1.5f;
    /** 最长重叠；超过此值两首曲子会互相打架太久。 */
    public static final float MAX_OVERLAP_SECONDS = 10.0f;
    public static final float DEFAULT_OVERLAP_SECONDS = 4.0f;

    /**
     * automix 低 shelf 的截止频率。200 Hz 是底鼓躯体和贝斯线
     * 所在之处：在它以下切掉可去除重叠中的浑浊，又不削薄人声。
     */
    public static final float BASS_SHELF_HZ = 200f;
    /** 在接入方接管处，退出方 deck 的低音被拉下多少。 */
    public static final float BASS_CUT_DB = -18f;
    /** 接入方 deck 在被允许进入混音之前低音先被压制多少。 */
    public static final float BASS_ENTRY_CUT_DB = -12f;

    /**
     * 退出方 deck 可被弯曲到的播放速率上下限。规划器（挑选
     * 目标）与计划（在其上安排渐变和相位修正）共享它，因此二者
     * 绝不会对可达范围有分歧。
     *
     * <p>±6 % 并非任意：一个半音是 2^(1/12) = +5.946 %，因此预算恰好宽到
     * 够一次整半音转调、而不再宽。这正是调性对齐要么准、要么干脆不发生的原因——
     * 介于两个半音之间的速率明显走调，
     * 比不动曲目更糟。</p>
     */
    public static final float MIN_PLAYBACK_RATE = .94f;
    public static final float MAX_PLAYBACK_RATE = 1.06f;
    /** Folia 通过 WSOLA 保持音高独立，因此速度弯曲可以使用完整策略范围。 */
    public static final float FOLIA_MIN_TEMPO_RATE = .88f;
    public static final float FOLIA_MAX_TEMPO_RATE = 1.12f;
    /** 以播放速率因子表示的一个半音。 */
    public static final float SEMITONE_RATIO = 1.0594630943592953f;

    /**
     * 接入方 deck 淡入时其人声频段被消除的强度。
     *
     * <p>两个主唱叠在一起是听众能指出的唯一毛病，而且与两个底鼓不同，
     * 它无法用 shelf 修好：人声与其他一切共用同一频段。
     * 消除到达曲目的限带中央，让它以纯伴奏底进入，
     * 只在退出人声消失后才成为歌曲。1 是完全消除，那会把
     * 军鼓和贝斯 DI 也吃掉；略过一半则保持进入的音乐性。</p>
     */
    public static final float VOCAL_DUCK_DEPTH = .62f;

    /**
     * 预备（解析 / 下载 / 解码 / 规划）的<b>兜底触发线</b>：出场曲剩余时间少到「交叠 + 本值」时，
     * 不管已经播到哪一段都必须立刻开始预备。
     *
     * <p>它<b>不是</b>「提前多久开始预备」。真正的触发条件在
     * {@code CloudMusic.PlayThread#maybeArmNextTrack}：「出场曲已播过 10 秒<b>或</b>剩余少于本值加
     * 交叠」，两者先到先算 —— 所以预备通常在实际起播 10 秒后就开始了。整曲配对选点能看见的退场
     * 候选范围因此本来就是整首歌，不受这个常量约束（别按「预备窗只有 30 秒」去推算可选范围）。</p>
     *
     * <p>它真正管的是两件半事：头 10 秒内的抢跑线；以及预备期还剩多少跑道，用来决定要不要等
     * 自定义音源的脚本（{@code lxArmReadyWaitMillis}）。未能及时就绪的预备直接退化为普通切换。</p>
     */
    static final long ARM_LEAD_MILLIS = 30_000L;

    /**
     * 第二张播放牌可以持有的最大已解码文件。
     *
     * <p>JSyn 把整段采样一次性放在堆里（见 {@link PlaybackMemoryLimits}），所以这张牌占多少内存
     * 与<b>什么时候建它</b>无关：早建不多占一个字节，只是多常驻一会儿。这条上限管的是「两张牌
     * 同时在堆里」这件事本身，不是预备窗的宽窄——把预备提前不会抬高内存峰值。</p>
     *
     * <p>120 MB 的 16 位 CD 音频约 11 分钟：对普通音乐够长，又短到两张实时牌不会像一小时的
     * 上传那样耗尽堆。</p>
     */
    private static final long ABSOLUTE_DECK_LIMIT_BYTES = 120L * 1024L * 1024L;

    private static final Object LOCK = new Object();
    private static final Gson GSON = new Gson();

    private static boolean loaded;
    private static Engine engine = DEFAULT_ENGINE;
    // 没有 automix.json 的旧安装和加载器专属游戏目录，同样落到 DEFAULT_ENGINE。
    // 以及 DEFAULT_ENGINE。
    private static boolean enabled = true;
    private static boolean beatAlign = true;
    private static boolean bassSwap = true;
    private static boolean tempoLock = true;
    private static boolean vocalDuck = true;
    private static boolean pitchMatch = true;
    private static boolean skipIntro = true;
    private static boolean performanceMode = false;
    /**
     * 进阶模式：整套「先算再切」的增强，出厂关闭。
     *
     * <p>它一次打开三件事——整曲包络扫描 + 出口×入口候选配对打分（{@link AutomixAutoCue}）、
     * 队列一键排列（{@link AutomixFlowPlanner}）、以及按实测人声证据决定的<b>双侧</b>人声让路
     * （{@code AutomixPlan#outgoingVocalDepth}）。关掉时这三件事一件都不发生：不扫包络、不起
     * 线程、不排队列，交接退回到 {@link FoliaAutomixPlanner} 原本的启发式路径，规划器读到的
     * 每个默认值都与「这个功能不存在」时逐位相同。</p>
     *
     * <p>之所以要一个总开关而不是三个独立开关：这三件事共用同一份整曲包络缓存，也共用同一条
     * 「预备期算不完就推迟」的时间预算。单独关其中一件，剩下两件仍要为它维护缓存与降级分支，
     * 而那只会让「这次为什么没 blend」更难解释。</p>
     */
    private static boolean advancedMode = false;
    private static boolean transitionAnimation = true;
    /**
     * 把接缝画在进度条上：一条竖线标出「从哪里开始切」，一段半透明带子标出「这一段是
     * 两首歌叠在一起」。见 {@link AutomixCueMarker}。
     *
     * <p>出厂打开，因为它只读一份<b>已经算好</b>的计划，不参与任何时序决策。关掉之后
     * 计划照旧算、交接照旧发生，只是玩家看不见。</p>
     */
    private static boolean cueMarker = true;
    private static boolean modelInferenceStatus = true;
    private static String modelsDirectory = "";
    private static float overlapSeconds = DEFAULT_OVERLAP_SECONDS;

    private AutomixSettings() {
    }

    /** automix 在空闲 deck 上可持有的最大已解码文件。 */
    static long maxDeckBytes() {
        return Math.min(ABSOLUTE_DECK_LIMIT_BYTES, PlaybackMemoryLimits.maxDecodedPcmBytes() / 2L);
    }

    public static Engine getEngine() {
        load();
        synchronized (LOCK) {
            return engine;
        }
    }

    public static void setEngine(Engine value) {
        load();
        synchronized (LOCK) {
            Engine next = value == null ? DEFAULT_ENGINE : value;
            if (engine == next) return;
            engine = next;
            saveLocked();
        }
    }

    public static boolean isFoliaEngine() {
        return getEngine() == Engine.FOLIA;
    }

    public static String getEngineLabel() {
        return getEngine().getLabel();
    }

    public static void cycleEngine() {
        setEngine(getEngine() == Engine.FOLIA ? Engine.LEGACY : Engine.FOLIA);
    }

    /** 用户要求时更激进的 build-up/音轨分离手势。 */
    public static boolean isPerformanceModeEnabled() {
        load();
        synchronized (LOCK) {
            return performanceMode;
        }
    }

    public static void setPerformanceModeEnabled(boolean value) {
        load();
        synchronized (LOCK) {
            if (performanceMode == value) return;
            performanceMode = value;
            saveLocked();
        }
    }

    /**
     * 进阶模式是否打开。见 {@link #advancedMode} 的字段注释：它是三件事的总闸，
     * 关闭时这三件事一次都不发生。
     */
    public static boolean isAdvancedModeEnabled() {
        load();
        synchronized (LOCK) {
            return advancedMode;
        }
    }

    public static void setAdvancedModeEnabled(boolean value) {
        load();
        synchronized (LOCK) {
            if (advancedMode == value) return;
            advancedMode = value;
            saveLocked();
        }
    }

    public static boolean isTransitionAnimationEnabled() {
        load();
        synchronized (LOCK) {
            return transitionAnimation;
        }
    }

    public static void setTransitionAnimationEnabled(boolean value) {
        load();
        synchronized (LOCK) {
            if (transitionAnimation == value) return;
            transitionAnimation = value;
            saveLocked();
        }
    }

    /**
     * 进度条上要不要画出接缝（切点竖线 + 交叠带）。出厂打开。
     *
     * <p>它与「过渡动画」不重复：那一件只在<b>交接正在进行</b>时于屏幕中央画圆环，且交叠
     * 短于五秒干脆不出现；这一件在<b>交接之前</b>就把位置告诉玩家，跟着进度条走。两者共用
     * 同一份计划，各自独立可关。</p>
     */
    public static boolean isCueMarkerEnabled() {
        load();
        synchronized (LOCK) {
            return cueMarker;
        }
    }

    public static void setCueMarkerEnabled(boolean value) {
        load();
        synchronized (LOCK) {
            if (cueMarker == value) return;
            cueMarker = value;
            saveLocked();
        }
    }

    /** Folia 模型推理是否向灵动岛发布实时的开始/结束卡片。 */
    public static boolean isModelInferenceStatusEnabled() {
        load();
        synchronized (LOCK) {
            return modelInferenceStatus;
        }
    }

    public static void setModelInferenceStatusEnabled(boolean value) {
        load();
        synchronized (LOCK) {
            if (modelInferenceStatus == value) return;
            modelInferenceStatus = value;
            saveLocked();
        }
    }

    /** 持久化的 Folia 模型/运行时目录；为空表示默认位置。 */
    public static String getModelsDirectory() {
        load();
        synchronized (LOCK) {
            return modelsDirectory == null ? "" : modelsDirectory;
        }
    }

    public static void setModelsDirectory(String value) {
        load();
        synchronized (LOCK) {
            String next = value == null ? "" : value.trim();
            if (next.equals(modelsDirectory)) return;
            modelsDirectory = next;
            saveLocked();
        }
    }
    public static boolean isEnabled() {
        load();
        synchronized (LOCK) {
            return enabled;
        }
    }

    public static void setEnabled(boolean value) {
        load();
        synchronized (LOCK) {
            if (enabled == value) return;
            enabled = value;
            saveLocked();
        }
    }

    /** 是否允许把交接推到检测到的小节边界上。 */
    public static boolean isBeatAlignEnabled() {
        load();
        synchronized (LOCK) {
            return beatAlign;
        }
    }

    public static void setBeatAlignEnabled(boolean value) {
        load();
        synchronized (LOCK) {
            if (beatAlign == value) return;
            beatAlign = value;
            saveLocked();
        }
    }

    /**
     * 混音是否在两个 deck 之间交换低频段，而不只交叉淡化音量。
     *
     * <p>两首重叠曲目会把两个底鼓和两条贝斯线放进同一个八度，叠加
     * 成任何音量曲线都救不了的闷响——这是最明显的"这是个交叉淡化"
     * 瑕疵。把退出方 deck 的低 shelf 抽出、压住接入方的低音
     * 直到它掌控混音，正是俱乐部 DJ 所做，也正是让交接听起来像有意为之的关键。</p>
     */
    public static boolean isBassSwapEnabled() {
        load();
        synchronized (LOCK) {
            return bassSwap;
        }
    }

    public static void setBassSwapEnabled(boolean value) {
        load();
        synchronized (LOCK) {
            if (bassSwap == value) return;
            bassSwap = value;
            saveLocked();
        }
    }

    /**
     * 混音期间是否允许把退出方 deck 弯曲到接入曲目的速度。
     *
     * <p>没有它，相差 4 % 的两首曲子在四秒重叠内会漂移三分之一拍，
     * 于是一个开始时对拍的交接最终变成 flamming。修正被钳制，
     * 且只施加于正在消失的那个 deck。</p>
     */
    public static boolean isTempoLockEnabled() {
        load();
        synchronized (LOCK) {
            return tempoLock;
        }
    }

    public static void setTempoLockEnabled(boolean value) {
        load();
        synchronized (LOCK) {
            if (tempoLock == value) return;
            tempoLock = value;
            saveLocked();
        }
    }

    /**
     * 到达方 deck 淡入时是否消除其人声频段，并在它掌控
     * 混音后恢复。
     *
     * <p>出于偏好而非安全而默认关闭：消除是限带中央相减，
     * 因此在主唱不居中的混音上它削得不够，在单声道文件上
     * 又退化成中频凹陷。这两者仍比两个人声相撞好，但用户
     * 想要普通等功率混音的可以关闭它。</p>
     */
    public static boolean isVocalDuckEnabled() {
        load();
        synchronized (LOCK) {
            return vocalDuck;
        }
    }

    public static void setVocalDuckEnabled(boolean value) {
        load();
        synchronized (LOCK) {
            if (vocalDuck == value) return;
            vocalDuck = value;
            saveLocked();
        }
    }

    /**
     * 是否允许把退出方 deck 转调整数个半音，使两个调性一致。
     *
     * <p>仅在速度锁定无事可做时使用——落在 outro 或
     * 铺底上的混音退出，即节拍网格太弱不值得锁定之处。二者无法同时服务：
     * JSyn 的速率端口同时移动速度和音高，而整个 ±6 % 预算就是一个半音。</p>
     */
    public static boolean isPitchMatchEnabled() {
        load();
        synchronized (LOCK) {
            return pitchMatch;
        }
    }

    public static void setPitchMatchEnabled(boolean value) {
        load();
        synchronized (LOCK) {
            if (pitchMatch == value) return;
            pitchMatch = value;
            saveLocked();
        }
    }

    /**
     * 交接时是否跳过新歌的前奏，直接从主体进来。
     *
     * <p>关掉之后新歌一律从第 0 毫秒开始，也就是回到「淡入整首歌的开头」这一最朴素的行为。前奏是不是
     * 「废话」终究是主观判断：铺垫式的长前奏对一部分听众正是这首歌的一半，被自动切掉会觉得歌被剪了。</p>
     *
     * <p>注意这只改变<b>从哪里开始出声</b>，不影响分析：前奏位置照样要测出来，节拍与调性必须在有内容的
     * 段落上分析，否则安静的前奏会把 BPM 和调性一起测废，连节奏锁定与和声判断都跟着失效。</p>
     */
    public static boolean isSkipIntroEnabled() {
        load();
        synchronized (LOCK) {
            return skipIntro;
        }
    }

    public static void setSkipIntroEnabled(boolean value) {
        load();
        synchronized (LOCK) {
            if (skipIntro == value) return;
            skipIntro = value;
            saveLocked();
        }
    }

    public static float getOverlapSeconds() {
        load();
        synchronized (LOCK) {
            return overlapSeconds;
        }
    }

    public static long getOverlapMillis() {
        return (long) (getOverlapSeconds() * 1000f);
    }

    public static void setOverlapSeconds(float value) {
        load();
        synchronized (LOCK) {
            float clamped = clamp(value);
            if (Math.abs(clamped - overlapSeconds) < .01f) return;
            overlapSeconds = clamped;
            saveLocked();
        }
    }

    /**
     * 重叠时长的可选档位。设置界面用点击循环而不是滑块：这一项的有效范围只有 1.5~10 秒，
     * 而且用户真正会选的就是这几个整数值，一格一格拖没有意义。
     */
    private static final float[] OVERLAP_PRESETS = {1.5f, 2f, 3f, 4f, 6f, 8f, 10f};

    /** 当前重叠时长的显示文案。 */
    public static String getOverlapLabel() {
        float seconds = getOverlapSeconds();
        if (Math.abs(seconds - Math.round(seconds)) < .05f) {
            return Math.round(seconds) + " 秒";
        }
        return String.format("%.1f 秒", seconds);
    }

    /** 切到下一个重叠档位，走到末尾回到第一个。 */
    public static void cycleOverlapSeconds() {
        float current = getOverlapSeconds();
        int index = 0;
        // 找当前值所在的档位；配置文件里手填的中间值会落到「下一个比它大的档位」。
        for (int i = 0; i < OVERLAP_PRESETS.length; i++) {
            if (current >= OVERLAP_PRESETS[i] - .05f) index = i;
        }
        setOverlapSeconds(OVERLAP_PRESETS[(index + 1) % OVERLAP_PRESETS.length]);
    }

    private static float clamp(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return DEFAULT_OVERLAP_SECONDS;
        return Math.max(MIN_OVERLAP_SECONDS, Math.min(MAX_OVERLAP_SECONDS, value));
    }

    public static void load() {
        synchronized (LOCK) {
            if (loaded) return;
            loaded = true;
            try {
                File file = ConfigPaths.AUTOMIX;
                if (!file.isFile()) return;
                JsonObject root = JsonConfigStorage.readObject(file, GSON);
                if (root == null) return;
                boolean migrateEngine = !root.has("engine") || root.get("engine").isJsonNull();
                if (root.has("enabled") && !root.get("enabled").isJsonNull()) {
                    enabled = root.get("enabled").getAsBoolean();
                }
                if (!migrateEngine) {
                    engine = Engine.fromStored(root.get("engine").getAsString());
                } else {
                    // 在引擎选择器存在之前写入的配置从来没有表达过引擎选择，所以它们跟随
                    // 当前默认值；迁移结果马上就写回盘（下面的 saveLocked），从此这条配置就是
                    // 一条显式记录了，不会再被后续的默认值变化影响。
                    engine = DEFAULT_ENGINE;
                }
                if (root.has("beatAlign") && !root.get("beatAlign").isJsonNull()) {
                    beatAlign = root.get("beatAlign").getAsBoolean();
                }
                if (root.has("bassSwap") && !root.get("bassSwap").isJsonNull()) {
                    bassSwap = root.get("bassSwap").getAsBoolean();
                }
                if (root.has("tempoLock") && !root.get("tempoLock").isJsonNull()) {
                    tempoLock = root.get("tempoLock").getAsBoolean();
                }
                if (root.has("vocalDuck") && !root.get("vocalDuck").isJsonNull()) {
                    vocalDuck = root.get("vocalDuck").getAsBoolean();
                }
                if (root.has("skipIntro") && !root.get("skipIntro").isJsonNull()) {
                    skipIntro = root.get("skipIntro").getAsBoolean();
                }
                if (root.has("pitchMatch") && !root.get("pitchMatch").isJsonNull()) {
                    pitchMatch = root.get("pitchMatch").getAsBoolean();
                }
                if (root.has("performanceMode") && !root.get("performanceMode").isJsonNull()) {
                    performanceMode = root.get("performanceMode").getAsBoolean();
                }
                if (root.has("advancedMode") && !root.get("advancedMode").isJsonNull()) {
                    advancedMode = root.get("advancedMode").getAsBoolean();
                }
                if (root.has("transitionAnimation") && !root.get("transitionAnimation").isJsonNull()) {
                    transitionAnimation = root.get("transitionAnimation").getAsBoolean();
                }
                if (root.has("cueMarker") && !root.get("cueMarker").isJsonNull()) {
                    cueMarker = root.get("cueMarker").getAsBoolean();
                }
                if (root.has("modelInferenceStatus") && !root.get("modelInferenceStatus").isJsonNull()) {
                    modelInferenceStatus = root.get("modelInferenceStatus").getAsBoolean();
                }
                if (root.has("modelsPath") && !root.get("modelsPath").isJsonNull()) {
                    modelsDirectory = root.get("modelsPath").getAsString();
                }
                if (root.has("overlapSeconds") && !root.get("overlapSeconds").isJsonNull()) {
                    overlapSeconds = clamp(root.get("overlapSeconds").getAsFloat());
                }
                if (migrateEngine) saveLocked();
            } catch (Throwable ignored) {
                // 损坏的 automix 配置绝不能阻止播放启动。
            }
        }
    }

    private static void saveLocked() {
        try {
            File parent = ConfigPaths.AUTOMIX.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) return;
            JsonObject root = new JsonObject();
            root.addProperty("enabled", enabled);
            root.addProperty("engine", engine.name());
            root.addProperty("performanceMode", performanceMode);
            root.addProperty("advancedMode", advancedMode);
            root.addProperty("transitionAnimation", transitionAnimation);
            root.addProperty("cueMarker", cueMarker);
            root.addProperty("modelInferenceStatus", modelInferenceStatus);
            root.addProperty("beatAlign", beatAlign);
            root.addProperty("bassSwap", bassSwap);
            root.addProperty("tempoLock", tempoLock);
            root.addProperty("vocalDuck", vocalDuck);
            root.addProperty("pitchMatch", pitchMatch);
            root.addProperty("skipIntro", skipIntro);
            root.addProperty("overlapSeconds", overlapSeconds);
            root.addProperty("modelsPath", modelsDirectory == null ? "" : modelsDirectory);
            JsonConfigStorage.writeObject(ConfigPaths.AUTOMIX, GSON, root);
        } catch (Throwable ignored) {
        }
    }
}
