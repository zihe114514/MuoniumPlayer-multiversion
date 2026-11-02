package com.muoniumplayer.core.ncm.music;

import lombok.Getter;
import lombok.SneakyThrows;
import repackage.com.jsyn.devices.AudioDeviceFactory;
import repackage.com.jsyn.devices.AudioDeviceManager;
import repackage.com.jsyn.devices.javasound.JavaSoundAudioDevice;
import repackage.com.jsyn.exceptions.ChannelMismatchException;
import repackage.processing.sound.AutomixDeckChain;
import repackage.processing.sound.SoundFile;
import com.muoniumplayer.core.ncm.music.audio.OpenALAudioDevice;
import com.muoniumplayer.core.viper.ViperRuntime;
import com.muoniumplayer.render.skija.SkijaNatives;

import java.io.File;

/**
 * 播放视频里面的音频
 */
public class AudioPlayer {
    public SoundFile player;
    public Runnable afterPlayed;

    /**
     * 播放音量。渲染线程（音量滑块逐帧 {@code setVolume}）写、JSyn 音频线程
     * {@code applyAutomixFrame}/{@code effectiveAmp} 逐帧读——同文件所有跨线程
     * 音频参数都是 volatile，这个字段被 @Getter 生成的 getter 带着走了，漏了。
     */
    @Getter
    public volatile float volume = 0.25f;

    /**
     * 叠加在 {@link #volume} 之上的交叉淡化倍率。它的存在，是为了让 automix 交接时
     * 把一路 deck 降下、另一路升起，而全程不触碰两路 deck 共同持有的持久化播放器音量。
     * 
     */
    private volatile float fadeGain = 1.0f;

    /**
     * 挂在这一路 deck 自己信号链路上的 automix 处理链（低搁架 + 人声频带抵消）。
     * 只在 automix 真的要用它时才接上，关掉 automix 的播放器保持完全原始的信号图；
     * 在 0 dB、深度为 0 时两级都是逐位精确的直通，
     * 所以整条链可以一整曲挂着不拆。
     */
    private AutomixDeckChain automixChain;
    private volatile boolean manualTransitionProcessing;
    private volatile float bassGainDb = 0.0f;
    private volatile float vocalDuckDepth = 0.0f;
    private volatile float midGainDb = 0.0f;
    private volatile float highGainDb = 0.0f;
    private volatile float playbackRate = 1.0f;
    /** 音高倍率。只有「整半音吸附」会动它；节奏锁定恒为 1。 */
    private volatile float pitchRatio = 1.0f;

    /**
     * 上一次 {@link #setPlaybackRate} 是否由 WSOLA 时间伸缩完成（而不是重采样）。
     *
     * <p>只用于日志与诊断。真正的分支判断每次都重新问 {@code SoundFile.timeStretch()} 的返回值，
     * 不依赖这个缓存 —— 曲目换了、伸缩层能力变了，缓存会过期。</p>
     */
    private volatile boolean pitchPreserved;

    /**
     * 这一路 deck 构建时所用的解码缓存文件，留着它好让离线分析用自己的句柄再打开一次。
     * 直接读活的 JSyn 采样不行：它从共享的滑动窗口流式读取，
     * 在音频线程下去挪动那个窗口会直接毁掉播放。
     */
    private volatile File sourceFile;
    /** 供非音频状态界面使用的、人类可读的曲目元数据。 */
    private volatile String trackName;

    /**
     * 有些下载下来的文件解码器能打开，却不含任何可用的音频帧；
     * AudioSample.position() 会对 frames() 取模，
     * 因此空采样绝不能流到播放或渲染路径上。
     *
     * <p>close() 在持锁下写、isUsable() 无锁读（Play Thread 与后台线程都会问），
     * 同样按跨线程字段处理用 volatile。</p>
     */
    private volatile boolean closed;

    /** 由 {@link #prepareStart(float)} 设置：读头已定位、搁架已接入、窗口已预热。 */
    private volatile boolean prepared;

    /**
     * 「这一路 deck 已经被要求播放」这一意图，在 {@link #play()} 排队的那一刻同步置位。
     *
     * <p><b>为什么需要它。</b>{@code AudioSample.isPlaying()} 读的是引擎线程的状态，而
     * {@code play()} 只是在命令队列里排一条命令 —— 要到音频线程下一次 {@code generateNextBuffer()}
     * 才真正生效。中间这段时间里 `isPlaying()` 仍是 false。播放器界面恰恰是用
     * {@code !isPlaying()} 画暂停图标的，于是「解码已完成、命令已排队、声音马上就出来」
     * 与「压根没在播」在界面上长得一模一样。</p>
     *
     * <p>首播时这个窗口格外长：解码完成到 {@code play()} 之间还隔着一次
     * {@code loadLyric()} 的联网往返（首次还要跑多源歌词匹配），前面还有
     * {@code stopPreviousPlayer()} 里 250 ms 的固定等待。用户看到的就是「已经开始响了，
     * 但暂停图标和进度条都没动」。</p>
     *
     * <p>把「意图」与「引擎实际状态」分开之后，界面问 {@link #isPausing()} 得到的是
     * 「用户视角下这条播放算不算在走」，不再随引擎命令队列的延迟抖动。它刻意<b>不</b>取代
     * {@code isPlaying()}：真正要判断引擎是否在出声的地方仍然直接问引擎。</p>
     */
    private volatile boolean playRequested;

    /** 只读探测：命令已下、声音未出（或刚出的那一小段）也算。压原版音乐用，见 VanillaMusicDucker。 */
    public boolean isPlayRequested() {
        return playRequested;
    }

    public AudioPlayer(File file) {
        finished = false;
        this.player = loadUsableSoundFile(file);
        this.sourceFile = file;
        this.setListeners();
        this.closed = false;
    }

    public void setAudio(File file) {
        // 在关掉当前正在播放的歌曲之前先解码并校验：
        // 损坏或为空的替换曲绝不能毁掉原本还能用的播放器。
        SoundFile next = loadUsableSoundFile(file);
        next.setOnFinished(() -> finished = true);

        releaseAutomixChain();
        // 换曲：声场修饰的滤波器与延迟线里还留着上一首的余音，一并清掉。
        com.muoniumplayer.core.ncm.music.acoustics.AcousticsRuntime.onTrackChanged();
        SoundFile previous = this.player;
        this.player = next;
        finished = false;
        this.closed = false;
        this.prepared = false;
        // 新曲目还没有被要求播放。此刻 deck 上挂的是上一首的播放意图，
        // 留着它会让界面在新曲真正起播之前就报告「正在播放」——正是本次要修的那个错。
        // 新曲目已真正起播——正是本次改动所要修正的那种不匹配。
        this.playRequested = false;
        this.bassGainDb = 0.0f;
        this.vocalDuckDepth = 0.0f;
        this.playbackRate = 1.0f;
        this.pitchRatio = 1.0f;
        this.pitchPreserved = false;
        // 交叉淡入的增益必须一起归位。fadeGain 属于某一次转场，不属于这个 deck：
        // 淡出侧会被压到 0，预卷（pre-roll）的 deck 也从 0 起步。如果那次转场被打断
        // （被抢先的切歌、队列版本变化、一起听房间中途换歌），只有 incoming == ownedPlayer
        // 这一种情形会被还原成 1；其余情况这个 deck 带着 0 活下来，随后又可能被提升成
        // 活动播放器。那时 setAudio() 换上新曲，effectiveAmp() = volume * 0 —— 下载正常、
        // 解码正常、进度条正常走，就是一点声音都没有，而且不报任何错。
        this.fadeGain = 1.0f;
        this.sourceFile = file;
        dispose(previous);
    }

    public void setListeners() {
        if (player != null)
            player.setOnFinished(() -> finished = true);
    }

    public void play() {
        if (!isUsable())
            return;

        // 已经在出声就不再命令它播放第二次。AudioSample.play() 对「自己已在播放」的采样
        // 会克隆出一路新读头（getUnusedPlayer），而克隆对象没有任何句柄留在外面 ——
        // pause()/close() 都只停得住原本那一路，多出来的那路会一直响到曲子结束。
        // 暂停/播放键被反复点按时叠出的第二层、第三层音就是这么来的。
        if (this.player.isPlaying()) {
            this.playRequested = true;
            return;
        }

        attachAutomixChain();

        finished = false;
        // 先置位再排队：这条命令之后无论引擎线程什么时候才处理，界面都已经知道
        // 「这一路 deck 是被要求播放的」，不必等 isPlaying() 翻真。
        this.playRequested = true;
        try {
            // 先调振幅：让播放从配置好的电平起步，
            // 而不是头几帧按引擎默认的满音量渲染。
            this.player.amp(effectiveAmp());
            this.player.play();
            this.player.amp(effectiveAmp());
        } catch (ChannelMismatchException mismatch) {
            // 为声道变化保留 CloudMusic 里既有的重试路径。
            this.playRequested = false;
            throw mismatch;
        } catch (RuntimeException ignored) {
            // 解码器在校验与播放之间可能失效。
            this.playRequested = false;
        }
    }

    @SneakyThrows
    public void setPlaybackTime(float millis) {
        if (!isUsable())
            return;

        float total = getTotalTimeMillis();
        if (total <= 0.0f)
            return;

        float safeMillis = Math.max(0.0f, Math.min(total, millis));
        try {
            // 暂停中挪进度只许挪读头，绝不许出声。AudioSample.jump() 的契约是
            // 「跳转并继续播放，没在播就开播」：暂停时它会把这一路重新接回引擎开始出声，
            // 而播放意图（playRequested）仍是 false —— 界面照旧画着播放键，用户再按一下
            // 又会在「已在播放」的采样上触发第二次 play()，克隆出一路停不掉的读头（叠音）。
            // 暂停态要的是 cue()：同样把读头放到目标位置，但保持静音，等真正的 play()
            // 从那里续上。判据用播放意图而不是引擎瞬时状态：play() 命令排队后到引擎真正
            // 开播之间有一个窗口，那里意图已是要播、引擎还没动，此时拖动进度条必须继续播。
            if (this.playRequested) {
                this.player.jump(safeMillis / 1000F);
            } else {
                this.player.cue(safeMillis / 1000F);
            }
            this.player.resyncTimeStretch();
            this.player.amp(effectiveAmp());
            // 拖进度后声音内容不连续了：声场里的余音一并作废，否则旧句的尾巴会叠在新句上。
            com.muoniumplayer.core.ncm.music.acoustics.AcousticsRuntime.onTrackChanged();
        } catch (RuntimeException ignored) {
            // 定位是尽力而为。畸形流绝不能让界面崩溃。
        }
    }

    /**
     * 从一个显式偏移开始播放。供 automix 交接使用，
     * 让进来的 deck 能跳过死寂的前奏，而不是总从 0 开始。
     *
     * <p>{@code cue()} 会先停掉读头，因此随后的 {@code play()} 复用同一个 JSyn voice，
     * 而不是再分配第二个并发的 voice。</p>
     */
    public void playFrom(float millis) {
        if (!isUsable())
            return;

        finished = false;
        this.playRequested = true;
        try {
            this.player.amp(effectiveAmp());
            cueTo(millis);
            // 要在 cue() 之后、绝不能在它之前：AudioSample.cue() 会走 stop()，
            // 把效果从电路里扯掉且不再装回（JSyn 的 Processing 包装层对此有自己的 TODO）。
            // 先接链的话，淡化中进来的 deck 会完全不经滤波地播放——
            // 两首曲子都保留完整低频，接缝处就会咚地撞一下。
            attachAutomixChain();
            this.player.play();
            this.player.amp(effectiveAmp());
        } catch (ChannelMismatchException mismatch) {
            this.playRequested = false;
            throw mismatch;
        } catch (RuntimeException ignored) {
            // 解码器在校验与播放之间可能失效。
            this.playRequested = false;
        }
    }

    /**
     * 做一次启动所需的一切，<em>唯独</em>不出声：定位读头、把 automix 低搁架接入（空闲的）电路、
     * 并把第一个流式窗口从磁盘拉下来。
     *
     * <p>之所以有这个方法，是因为 deck 实际读取音频的方式。解码后的 WAV 并不常驻内存；
     * 一个 {@code RandomAccessFile} 通过 4096 采样的滑动窗口读取，窗口由<b>JSyn 引擎线程</b>回填。
     * 因此在交叉淡化那一刻才启动的 deck，会在音频回调里付出一次文件 seek、一次 8 KB 读、
     * 一次信号图重接，偏偏另一路 deck 此刻也要被服务——
     * 这正是「无缝」交接绝不该有的卡顿。
     * 在预备线程上提前几秒把这些全做完，留给接缝本身的就只剩振幅斜坡。</p>
     * 
     */
    public void prepareStart(float millis) {
        if (!isUsable()) return;
        try {
            finished = false;
            this.player.amp(0.0f);
            float cued = cueTo(millis);
            attachAutomixChain();
            primeStreamWindow(cued);
            this.prepared = true;
        } catch (RuntimeException ignored) {
            this.prepared = false;
        }
    }

    /**
     * 启动一路已由 {@link #prepareStart(float)} 备好的 deck。刻意不再 cue：再次 cue
     * 会停掉读头、把低搁架再扯出来，并丢掉已预热的窗口。
     */
    public void startPrepared() {
        if (!isUsable()) return;
        if (!this.prepared) {
            play();
            return;
        }
        // 与 play() 同一条防线：已经在出声的采样再收一次 play() 会克隆出没人持有的第二路读头。
        if (this.player.isPlaying()) {
            this.playRequested = true;
            return;
        }
        finished = false;
        this.playRequested = true;
        try {
            this.player.amp(effectiveAmp());
            attachAutomixChain();   // 幂等；这条链通常从 prepareStart() 起就一直活着。
            this.player.play();
            this.player.amp(effectiveAmp());
        } catch (ChannelMismatchException mismatch) {
            this.playRequested = false;
            throw mismatch;
        } catch (RuntimeException ignored) {
            this.playRequested = false;
        }
    }

    /** 一旦 {@link #prepareStart(float)} 完成定位并预热这一路 deck，即为 true。 */
    public boolean isPrepared() {
        return this.prepared;
    }

    /** 夹到采样范围内并定位读头。返回实际定位到的位置，单位毫秒。 */
    private float cueTo(float millis) {
        float total = getTotalTimeMillis();
        float safeMillis = total <= 0.0f
                ? 0.0f
                : Math.max(0.0f, Math.min(Math.max(0.0f, total - 50.0f), millis));
        this.player.cue(safeMillis / 1000F); // 0 也必须重定位，手动下一首不能沿用自动 cue。
        // 定位之后必须让时间伸缩层重新对齐：它的输出时间轴是自己往前长的，
        // 不重锚就会在跳转后继续放跳转前那一段。
        this.player.resyncTimeStretch();
        return safeMillis;
    }

    /**
     * 强制把流式窗口挪到给定位置，让首次读取不发生在音频回调内部。
     * 一个采样就够了：读头会在被请求的下标周围填满一整个窗口。
     * 只在未出声的 deck 上调用——挪动正在播放采样的窗口，
     * 正是过去弄崩引擎线程的元凶。
     */
    private void primeStreamWindow(float millis) {
        try {
            int channels = Math.max(1, this.player.channels());
            int sampleRate = this.player.sampleRate();
            if (sampleRate <= 0) return;
            long frame = (long) ((millis / 1000.0f) * sampleRate);
            int frames = this.player.frames();
            if (frames <= 0) return;
            if (frame < 0L) frame = 0L;
            if (frame > frames - 1L) frame = frames - 1L;
            long index = frame * channels;
            if (index > Integer.MAX_VALUE - channels) return;
            this.player.read((int) index);
        } catch (RuntimeException ignored) {
            // 预热只是优化；拒绝预热的解码器仍能正常播放。
        }
    }

    /**
     * 设置交叉淡化倍率。{@code 0} 为静音，{@code 1} 为配置好的播放器音量；
     * 允许大于 1 的值，好让偏小的进来曲目能做响度匹配，
     * 最终乘积会夹到引擎合法的振幅范围内。
     */
    public void setFadeGain(float gain) {
        this.fadeGain = Float.isNaN(gain) || Float.isInfinite(gain) ? 0.0f : Math.max(0.0f, gain);
        applyAmp();
    }

    public float getFadeGain() {
        return this.fadeGain;
    }

    /** 供 automix 自动化使用的音频线程版本；不会把任何命令排回 JSyn 的队列。 */
    void applyAutomixFrame(float fade, float bassDb, float vocalDepth, float midDb, float highDb) {
        this.fadeGain = Float.isFinite(fade) ? Math.max(0f, fade) : 0f;
        this.bassGainDb = Float.isFinite(bassDb) ? bassDb : 0f;
        this.vocalDuckDepth = Float.isFinite(vocalDepth) ? Math.max(0f, Math.min(1f, vocalDepth)) : 0f;
        this.midGainDb = Float.isFinite(midDb) ? Math.max(-3f, Math.min(3f, midDb)) : 0f;
        this.highGainDb = Float.isFinite(highDb) ? Math.max(-3f, Math.min(3f, highDb)) : 0f;
        if (!isUsable()) return;
        this.player.setAmplitudeNow(this.volume * this.fadeGain);
        AutomixDeckChain chain = this.automixChain;
        if (chain != null) chain.setTargetsNow(this.bassGainDb, routeVocalDepth(this.vocalDuckDepth),
                this.midGainDb, this.highGainDb);
    }

    /**
     * 这一路 deck 的人声让路由谁来做，并把深度发给做得了的那一个。
     *
     * <p>两条实现做的是同一件事（让进来的那首先只剩伴奏），只是精度差一个量级：素材淡入是逐采样
     * 把分离出来的人声减掉，带限抵消只能压正中间那一块。手上有人声素材时<b>整条交给素材</b>、
     * 滤波链深度归零 —— 不是「素材为主、抵消器补一点」，两条同时全开会在人声原本所在的位置上
     * 减两遍，那是一段中空的下陷，比少减一点难听得多。</p>
     *
     * <p>字段 {@code vocalDuckDepth} 里留的仍然是计划给的值：日志、界面与回退都按那条曲线说话，
     * 只有落到信号图上的那一份被改写。</p>
     *
     * @return 该写进滤波链的深度（素材接管时恒为 0，即滤波链的位精确直通分支）
     */
    private float routeVocalDepth(float depth) {
        SoundFile deck = this.player;
        if (deck == null || !deck.hasVocalMaterial()) return depth;
        deck.setVocalMaterialLevel(1.0 - depth);
        return 0f;
    }

    /**
     * 把离线分好的人声素材接到这路 deck。
     *
     * <p>只在预备线程上调用（那里可以同步读盘），且必须在交接出声之前：接上之后这条 deck 的
     * 人声控制就换了执行者，所以要拿当前的深度值重新分派一次，把已经按抵消器设下去的那一份交还。</p>
     *
     * @return 是否接上了；接不上时后续一切照旧走抵消器
     */
    public boolean attachVocalMaterial(FoliaVocalStem stem) {
        SoundFile deck = this.player;
        if (deck == null || stem == null) return false;
        if (!deck.attachVocalMaterial(stem.samples, stem.firstFrame,
                FoliaStemProfile.MATERIAL_RATE, stem.ratio)) return false;
        AutomixDeckChain chain = this.automixChain;
        if (chain != null) chain.duckDepth(routeVocalDepth(this.vocalDuckDepth));
        return true;
    }

    /** 卸下这一路的人声素材，把深度交还给滤波链；一次交接用完就该调它（素材有几 MB）。 */
    public void releaseVocalMaterial() {
        SoundFile deck = this.player;
        if (deck == null || !deck.hasVocalMaterial()) return;
        deck.detachVocalMaterial();
        AutomixDeckChain chain = this.automixChain;
        if (chain != null) chain.duckDepth(this.vocalDuckDepth);
    }

    /** 这条接缝的人声让路是否已经由素材接管。 */
    public boolean hasVocalMaterial() {
        SoundFile deck = this.player;
        return deck != null && deck.hasVocalMaterial();
    }

    /**
     * 淡出 deck 的音频线程版本，多带上已经解耦的速度与音高两条控制，以及<b>出场方的人声让路</b>。
     *
     * <p>{@code vocalDepth} 是后来加的：只压进场方时，出场曲那一句如果越过交叠窗还没唱完，
     * 中段就是两个人同时唱。深度 0 走 {@code VocalDuckFilter} 的位精确直通分支，
     * 所以旧计划与关闭进阶模式时这一条不改任何一个采样值。方法名不沿用
     * {@code applyAutomixFrame} 是因为它已经有一个五浮点参数的重载（进场方的曲线），
     * Java 只按参数类型区分，两个五参重载会撞成一个。</p>
     */
    void applyOutgoingAutomixFrame(float fade, float bassDb, float vocalDepth, float tempo, float pitch) {
        applyOutgoingAutomixFrame(fade, bassDb, vocalDepth, 0f, 0f, tempo, pitch);
    }

    /** 出场方同样可让出中高频；保留五参入口供原有自动混音调用。 */
    void applyOutgoingAutomixFrame(float fade, float bassDb, float vocalDepth,
                                   float midDb, float highDb, float tempo, float pitch) {
        applyAutomixFrame(fade, bassDb, vocalDepth, midDb, highDb);
        applySpeedAndPitchNow(tempo, pitch);
    }

    private void applySpeedAndPitchNow(float tempo, float pitch) {
        if (!isUsable()) return;
        float safeTempo = Float.isFinite(tempo) ? Math.max(.5f, Math.min(2f, tempo)) : 1f;
        float safePitch = Float.isFinite(pitch) ? Math.max(.5f, Math.min(2f, pitch)) : 1f;
        this.playbackRate = safeTempo;
        this.pitchRatio = safePitch;
        if (this.player.canTimeStretch()) {
            this.pitchPreserved = true;
            this.player.setRateAndStretchNow(safePitch, safeTempo / safePitch);
        } else {
            this.pitchPreserved = false;
            float combined = Math.max(AutomixSettings.MIN_PLAYBACK_RATE,
                    Math.min(AutomixSettings.MAX_PLAYBACK_RATE, safeTempo * safePitch));
            this.player.setRateNow(combined);
        }
    }

    /** 手动切歌独立使用轻量 EQ，不要求开启自动混音的人声抵消或低音替换。 */
    void enableManualTransitionProcessing() {
        this.manualTransitionProcessing = true;
        // 正在出声的旧牌只更新目标，不能每次切歌重置它的滤波历史。
        if (this.automixChain == null || !this.automixChain.isAttachedTo(this.player)) {
            attachAutomixChain();
        }
        if (this.automixChain != null) this.automixChain.transitionToneBands(true);
    }

    /**
     * 如果 automix 处理链还不在信号图里，就把它接上。
     *
     * <p>在 deck 即将出声之前调用，所以绝不会在音频流经信号图的中途重接。
     * 这条链只在 automix 真被允许使用时才创建：关掉 automix——
     * 或低音替换与人声闪避都关掉且没有手动交接——deck 就保持原始的、无效果的信号图。</p>
     * 
     */
    private void attachAutomixChain() {
        if (!AutomixSettings.isEnabled()) return;
        if (!this.manualTransitionProcessing && !AutomixSettings.isFoliaEngine() && !AutomixSettings.isBassSwapEnabled()
                && !AutomixSettings.isVocalDuckEnabled()) return;
        if (!isUsable()) return;
        try {
            if (this.automixChain == null) {
                this.automixChain = new AutomixDeckChain();
                this.automixChain.frequency(AutomixSettings.BASS_SHELF_HZ);
            }
            // 中央抵消器需要知道是否真有立体声声场：单声道文件被声像摆进两条完全相同的声道，
            // 这种情况下减去中央会把整个被抵消的频带一起带走。
            // 
            this.automixChain.stereo(channelCount() >= 2);
            this.automixChain.transitionToneBands(this.manualTransitionProcessing);
            // 走 routeVocalDepth 而不是直接读字段：素材接得上时这条链的深度必须是 0，
            // 否则预卷好的 deck 在 prepareStart 里会被重新 resetTo 一次，把已经在用素材减的
            // 那条人声又用抵消器减一遍 —— 症状是交接中段人声所在的那个位置整段中空。
            this.automixChain.resetTo(this.bassGainDb, routeVocalDepth(this.vocalDuckDepth));
            this.automixChain.midGainDb(this.midGainDb);
            this.automixChain.highGainDb(this.highGainDb);
            // 不能用 isProcessing() 判断：pause() 会不打招呼就把效果从电路里扯掉，
            // 所以恢复的 deck 必须重新接链，否则会不经滤波就播放。
            if (!this.automixChain.isAttachedTo(this.player)) {
                this.automixChain.forget(this.player);
                this.automixChain.process(this.player);
            }
        } catch (Throwable ignored) {
            // 拒绝这些额外单元的引擎绝不能让曲目无法播放。
            this.automixChain = null;
        }
    }

    private int channelCount() {
        try {
            return Math.max(1, this.player.channels());
        } catch (RuntimeException ignored) {
            return 1;
        }
    }

    /**
     * 为本路 deck 设置低搁架增益，单位 dB。负值把低音抽掉，
     * 交接正是靠它防止两首重叠曲目的底鼓叠成一声闷响。
     *
     * <p>没接搁架时（automix 或低音替换被禁用）是空操作，调用方不必知道哪些 deck 被滤波。</p>
     * 
     */
    public void setBassGainDb(float db) {
        float safe = Float.isNaN(db) || Float.isInfinite(db) ? 0.0f : db;
        this.bassGainDb = safe;
        AutomixDeckChain chain = this.automixChain;
        if (chain == null) return;
        try {
            chain.gainDb(safe);
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * 设置本路 deck 人声频带的抵消强度，0 不动它，1 对带限中央声道做完全抵消。
     * 
     *
     * <p>用在正在淡<em>入</em>的那一路 deck 上：它以纯器乐垫的身份进来，
     * 好让出去曲目的人声头顶不会再叠第二个人声，并在淡化结束前松开抵消。
     * 没接链时是空操作，调用方不必知道哪些 deck 被处理。</p>
     * 
     */
    public void setMidGainDb(float db) {
        float safe = Float.isNaN(db) || Float.isInfinite(db) ? 0f : Math.max(-3f, Math.min(3f, db));
        this.midGainDb = safe;
        AutomixDeckChain chain = this.automixChain;
        if (chain != null) try { chain.midGainDb(safe); } catch (RuntimeException ignored) {}
    }

    public void setHighGainDb(float db) {
        float safe = Float.isNaN(db) || Float.isInfinite(db) ? 0f : Math.max(-3f, Math.min(3f, db));
        this.highGainDb = safe;
        AutomixDeckChain chain = this.automixChain;
        if (chain != null) try { chain.highGainDb(safe); } catch (RuntimeException ignored) {}
    }

    public void setVocalDuckDepth(float depth) {
        float safe = Float.isNaN(depth) || Float.isInfinite(depth)
                ? 0.0f : Math.max(0.0f, Math.min(1.0f, depth));
        this.vocalDuckDepth = safe;
        AutomixDeckChain chain = this.automixChain;
        if (chain == null) {
            // 链还没接上（预备阶段就是这样：深度要在 prepareStart 之前就位，链在那一步才建）。
            // 素材这一路不依赖链，所以照样要分派出去，否则接了素材的 deck 只有等第一帧曲线
            // 落下来才开始减人声，第一个音节就漏了。
            routeVocalDepth(safe);
            return;
        }
        try {
            chain.duckDepth(routeVocalDepth(safe));
        } catch (RuntimeException ignored) {
        }
    }

    public float getVocalDuckDepth() {
        return this.vocalDuckDepth;
    }

    /** 这一路 deck 背后的解码文件；未知时为 {@code null}。 */
    /** 这一路 deck 上所加载曲目的显示名（若已知）。 */
    public String getTrackName() {
        return this.trackName;
    }

    public void setTrackName(String trackName) {
        this.trackName = trackName;
    }

    public File getSourceFile() {
        return this.sourceFile;
    }

    /**
     * 这一路播放素材自身的采样率；deck 还没建好或已经释放时为 {@code 0}。
     *
     * <p>人声素材按它判断能不能对齐：分离出来的那份固定在 44.1k，跟播放素材不同率时相减留下的是
     * 错位的残差，所以要么先问这个数，要么根本别接。</p>
     */
    public int sampleRate() {
        try {
            return isUsable() ? this.player.sampleRate() : 0;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    public float getBassGainDb() {
        return this.bassGainDb;
    }

    /**
     * 相对播放速率，1.0 即文件自身的速度。
     *
     * <p>只用在正在淡<em>出</em>淡化的那一路 deck 上：把它轻轻推到进来曲目的速度，
     * 让两套节拍网格在整个重叠期锁死，而不是差上小半拍慢慢漂开。</p>
     * 
     *
     * <p><b>优先走时间伸缩，不走重采样。</b>{@code SoundFile.rate()} 拧的是 JSyn 读头的步进，速度和音高
     * 一起变 —— 1.03 倍就是升高约半个音，而且是那种"整首歌走调"的听感，用户反馈过。
     * {@link repackage.processing.sound.SoundFile#timeStretch} 走的是 WSOLA：只改速度，音高不动。
     * 只有这首歌没有伸缩层时（{@code wrap()} 判定包不了）才退回原来的重采样，
     * 那时的行为和这次改动之前完全一致。</p>
     */
    public void setPlaybackRate(float ratio) {
        setSpeedAndPitch(ratio, 1.0f);
    }

    /**
     * 分别设置<b>速度</b>与<b>音高</b>倍率，两者互不牵连。
     *
     * <p>这是 TarsosDSP 的 {@code PitchShiftingExample} 那条链在本项目里的等价物：它把
     * {@code WaveformSimilarityBasedOverlapAdd}（时间伸缩）和 {@code RateTransposer}（重采样）串起来，
     * 靠两者的比值把「速度」和「音高」解耦。本项目里重采样那一环<b>本来就有</b> ——
     * JSyn 读头的 {@code rate} 端口就是 {@code RateTransposer}，所以只要补上 WSOLA 就凑齐了。</p>
     *
     * <p>推导：WSOLA 用因子 {@code t} 时，吃掉 {@code t} 倍输入产出一份输出，音高不变；读头用
     * {@code rate = r} 时，速度与音高同时 × {@code r}。串起来<b>速度 = t·r、音高 = r</b>。于是：</p>
     * <ul>
     *   <li>只改速度（节奏锁定）：{@code r = 1}、{@code t = tempo}；</li>
     *   <li>只改音高（整半音吸附）：{@code r = pitch}、{@code t = tempo / pitch}，
     *       {@code tempo = 1} 时 {@code t = 1/pitch} 正好把重采样带来的时长变化还原回去。</li>
     * </ul>
     *
     * <p>没有伸缩层的曲子（{@code wrap()} 判定包不了：单声道以外的多声道、短到装不满一个 WSOLA 序列）
     * 只剩一个重采样端口，速度与音高重新耦合，只能把两者的乘积拧上去 —— 那正是接 WSOLA 之前的行为，
     * 所以此时也把乘积收回 automix 的一个半音预算之内。</p>
     *
     * @param tempo 速度倍率，1 表示按素材原速
     * @param pitch 音高倍率，1 表示不移调
     */
    public void setSpeedAndPitch(float tempo, float pitch) {
        if (Float.isNaN(tempo) || Float.isInfinite(tempo)) return;
        if (Float.isNaN(pitch) || Float.isInfinite(pitch)) return;
        float safeTempo = Math.max(.5f, Math.min(2.0f, tempo));
        float safePitch = Math.max(.5f, Math.min(2.0f, pitch));
        // 淡化驱动器每 10 ms tick 一次，且在淡化大部分时间里把速率保持平坦；
        // 每 tick 都用同一个没变的值重设端口纯属浪费。
        if (Math.abs(safeTempo - this.playbackRate) < .0005f
                && Math.abs(safePitch - this.pitchRatio) < .0005f) {
            return;
        }
        this.playbackRate = safeTempo;
        this.pitchRatio = safePitch;
        if (!isUsable()) return;
        try {
            if (this.player.canTimeStretch()) {
                this.pitchPreserved = true;
                // 顺序无关紧要（两级串联），但读头必须先落到 pitch 上，否则某一帧里会出现
                // 「伸缩已经按解耦后的比值跑、读头还停在上一次的 pitch」这种半拍不一致。
                this.player.rate(safePitch);
                this.player.timeStretch(safeTempo / safePitch);
                return;
            }
            this.pitchPreserved = false;
            float combined = safeTempo * safePitch;
            if (combined < AutomixSettings.MIN_PLAYBACK_RATE) combined = AutomixSettings.MIN_PLAYBACK_RATE;
            if (combined > AutomixSettings.MAX_PLAYBACK_RATE) combined = AutomixSettings.MAX_PLAYBACK_RATE;
            this.player.rate(combined);
        } catch (RuntimeException ignored) {
        }
    }

    /** 当前的音高倍率，1 表示没移调。 */
    public float getPitchRatio() {
        return this.pitchRatio;
    }

    /** 这一次变速是不是由 WSOLA 完成的（也就是音高没被动过）。 */
    public boolean isPitchPreserved() {
        return this.pitchPreserved;
    }

    /** 时间伸缩层当前是否在跑。诊断用：交接结束后应当仍为 true（接管后不退回直通）。 */
    public boolean isTimeStretching() {
        return isUsable() && this.player.isTimeStretching();
    }

    public float getPlaybackRate() {
        return this.playbackRate;
    }

    /** 把这一路 deck 恢复成平直响应、完整人声与自然速度。 */
    public void resetAutomixProcessing() {
        this.manualTransitionProcessing = false;
        if (this.automixChain != null) this.automixChain.transitionToneBands(false);
        setBassGainDb(0.0f);
        setMidGainDb(0.0f);
        setHighGainDb(0.0f);
        setVocalDuckDepth(0.0f);
        // 素材只在交接那一段有用：先归位（setVocalDuckDepth 已经把它拉回 1，也就是恒等直通）,
        // 再把那几 MB 交出去。留在 deck 上会跟着这首歌一直活到下一首，而下一首的接缝要的是另一份。
        releaseVocalMaterial();
        if (Math.abs(this.playbackRate - 1.0f) > .0005f
                || Math.abs(this.pitchRatio - 1.0f) > .0005f) {
            setSpeedAndPitch(1.0f, 1.0f);
        }
    }

    private void releaseAutomixChain() {
        this.manualTransitionProcessing = false;
        AutomixDeckChain chain = this.automixChain;
        this.automixChain = null;
        if (chain == null) return;
        try {
            chain.release();
        } catch (Throwable ignored) {
        }
    }

    /** 交给引擎的有效振幅：持久化音量乘以交叉淡化增益。 */
    private float effectiveAmp() {
        float amp = this.volume * this.fadeGain;
        if (Float.isNaN(amp) || Float.isInfinite(amp)) return 0.0f;
        return Math.max(0.0f, Math.min(1.0f, amp));
    }

    private void applyAmp() {
        if (!isUsable()) return;
        try {
            this.player.amp(effectiveAmp());
        } catch (RuntimeException ignored) {
            // 会在下一次成功的播放操作时重新施加。
        }
    }

    /**
     * 刻意做成幂等。现在一路 deck 可能从好几个地方被释放（播放循环、被取消的切歌、交叉淡化结束），
     * 对已经清理过的采样再 dispose 一次，会把一个 null 缓冲塞给 JSyn 排队。
     * 
     */
    @SneakyThrows
    public synchronized void close() {
        if (closed) return;
        closed = true;
        this.playRequested = false;
        releaseAutomixChain();
        dispose(this.player);
    }

    /**
     * 引擎回调线程写、Play Thread 读（{@code isFinished()}）。与同文件其它跨线程字段一致
     * 用 volatile：不加的话最坏停留在旧值，让 {@code stopPreviousPlayer} 多走一次失效操作。
     */
    @Getter
    private volatile boolean finished;

    public void setAfterPlayed(Runnable runnable) {
        this.afterPlayed = runnable;
        if (this.player != null)
            this.player.setOnFinished(() -> {
                finished = true;
                if (runnable != null)
                    runnable.run();
            });
    }

    public float getTotalTimeSeconds() {
        return (int) (getTotalTimeMillis() / 1000.0f);
    }

    public float getCurrentTimeSeconds() {
        return (int) (getCurrentTimeMillis() / 1000);
    }

    public float getTotalTimeMillis() {
        // 直接由原始浮点秒换算毫秒（round 到最近毫秒），保留小数时长。
        if (!isUsable())
            return 0.0f;

        try {
            float duration = this.player.duration();
            if (Float.isNaN(duration) || Float.isInfinite(duration) || duration <= 0.0f)
                return 0.0f;
            return Math.max(0.0f, Math.round(duration * 1000.0f));
        } catch (RuntimeException ignored) {
            return 0.0f;
        }
    }

    public float getCurrentTimeMillis() {
        if (!isUsable())
            return 0.0f;

        try {
            float position = this.player.position() * 1000.0f;
            if (Float.isNaN(position) || Float.isInfinite(position))
                return 0.0f;

            float total = getTotalTimeMillis();
            if (total <= 0.0f)
                return 0.0f;
            return Math.max(0.0f, Math.min(total, position));
        } catch (RuntimeException ignored) {
            // AudioSample.position() 用 `% frames()` 取模，当解码器
            // 产出空采样时可能抛错。报一个安全的位置。
            return 0.0f;
        }
    }

    /**
     * 从用户视角看，这一路 deck 是否处于「没在播放」。
     *
     * <p>判据是<b>播放意图</b>（{@link #playRequested}）而不是引擎线程的 {@code isPlaying()}。
     * 后者在 {@code play()} 排队之后仍会保持 false 一整个引擎缓冲，首播时前面还压着解码、
     * 歌词联网往返与固定等待，于是界面上一条已经在出声的曲目会继续显示成暂停、
     * 进度条停在 0 —— 这正是本方法要消除的错位。</p>
     *
     * <p>需要知道引擎此刻是否真的在出声时，直接问 {@link #isPlaying()}，不要用本方法反推。</p>
     */
    public boolean isPausing() {
        return !isUsable() || !this.playRequested;
    }

    /**
     * 引擎线程当前是否真的在出声。<b>与 {@link #isPausing()} 不同</b>：这里的答案是引擎的
     * 真实状态，会在 {@code play()} 排队后的第一个缓冲之前保持 false。
     * 界面判断「要不要画暂停图标」请用 {@link #isPausing()}。
     */
    public boolean isPlaying() {
        return isUsable() && this.player.isPlaying();
    }

    public void setVolume(float volume) {
        this.volume = volume;
        applyAmp();
    }

    public void pause() {
        // 意图与引擎一起停下。先清意图：否则在引擎真正停住之前，界面会有一段
        // 「已按暂停、图标却还是播放」的反向错位，和本次修的是同一个量。
        this.playRequested = false;
        if (isUsable()) {
            try {
                this.player.pause();
            } catch (RuntimeException ignored) {
                // 忽略畸形的解码器状态。
            }
        }
    }

    public void unpause() {
        this.play();
    }

    /**
     * 返回解码器是否暴露了一个非空、可度量的采样。
     */
    public boolean isUsable() {
        if (closed || player == null)
            return false;
        return isUsable(player);
    }

    /** 兜底探测只做一次；结果（装没装 OpenAL 设备）对整个进程有效。 */
    private static volatile boolean audioDeviceFallbackChecked;

    /**
     * OpenAL 兜底的急停开关（默认开）。声音线程泵不创建第二个设备/上下文，
     * 但如果哪台机器上它另有古怪，加 {@code -Dmuonium.audio.openal=false} 可以
     * 一键退回「无音频设备」的原有失败，不用等发版。
     */
    private static final String OPENAL_FLAG = "muonium.audio.openal";

    /**
     * JavaSound 拿不到输出设备时（桌面 Linux / macOS、没装音频桥的安卓启动器），
     * 把 JSyn 的输出设备换成 mod 自带的 {@link OpenALAudioDevice}
     * （声音线程绑定：alcSetThreadContext 线程局部，不与游戏声音引擎抢进程级指针）。
     *
     * <p>必须在第一个 {@code new SoundFile(...)} 之前调：那一下就会触发
     * {@code Engine.getEngine()} 的设备选择，晚了就赶不上。桌面有可用 Mixer 时
     * 直接返回，绝不抢现成的设备。
     *
     * <p><b>安卓上连 JavaSoundAudioDevice 的构造都要跳过</b>：那次构造会触发
     * {@code AudioSystem} 的 Mixer 扫描，把启动器里 MioJSound 这类桥唤醒；
     * 它们与游戏声音引擎共用同一份 libopenal 的进程级 current 指针，一用起来
     * 游戏声音引擎立刻变成「Invalid name parameter」刷屏（2026-10-06 Zalith 真机）。
     * 我们自己的设备用线程局部上下文绑定，从根上绕开这场架。
     */
    private static synchronized void installAudioDeviceFallbackIfMissing() {
        if (audioDeviceFallbackChecked) {
            return;
        }
        audioDeviceFallbackChecked = true;
        try {
            boolean android = SkijaNatives.mappedPlatform().startsWith("android");
            if (!android) {
                JavaSoundAudioDevice javaSound = new JavaSoundAudioDevice();
                if (javaSound.getDefaultOutputDeviceID() != -1) {
                    return;
                }
            } else {
                System.out.println("[MuoniumPlayer/Audio] 安卓：绕过 javax.sound（含 MioJSound "
                        + "一类桥，它们会与游戏声音引擎抢上下文），直接使用自带 OpenAL 设备");
            }
            if ("false".equalsIgnoreCase(System.getProperty(OPENAL_FLAG, "true"))) {
                System.out.println("[MuoniumPlayer/Audio] OpenAL 兜底已被 -D" + OPENAL_FLAG
                        + "=false 显式关闭，音乐将无法播放");
                return;
            }
            AudioDeviceManager openal = OpenALAudioDevice.createIfAvailable();
            if (openal != null) {
                AudioDeviceFactory.setInstance(openal);
                System.out.println("[MuoniumPlayer/Audio] 音乐输出改走 OpenAL 流式设备"
                        + (android ? "（声音线程局部绑定）" : ""));
            } else {
                System.out.println("[MuoniumPlayer/Audio] JavaSound 与 OpenAL 都拿不到输出设备，"
                        + "音乐将无法播放");
            }
        } catch (Throwable t) {
            System.out.println("[MuoniumPlayer/Audio] 音频设备兜底探测失败（不影响现有播放）：" + t);
        }
    }

    private static SoundFile loadUsableSoundFile(File file) {
        installAudioDeviceFallbackIfMissing();
        if (file == null)
            throw new IllegalArgumentException("Audio file is null");

        // 旧构建（或异常长的流）留下的解码后 PCM 缓存，可能超出播放器在内存里允许持有的量。
        // 事先拒收，并把分配失败当作一次曲目失败，
        // 而不是让 OutOfMemoryError 逃进游戏循环。
        long pcmLimit = PlaybackMemoryLimits.maxDecodedPcmBytes();
        if (file.isFile() && file.length() > pcmLimit) {
            throw new IllegalStateException(PlaybackMemoryLimits.describeOverLimit(file.length()));
        }

        // ViPER 音效：与设备兜底同一时机挂接输出处理器 —— Engine 的创建与设备选择
        // 就发生在下面的 new SoundFile 里，在这里挂接不改变任何设备选择时序。
        ViperRuntime.ensureInstalled();
        // 场景声学：挂在主效果链之后的尾槽，看的是蝰蛇处理完的最终信号。
        // 同样的幂等语义，挂接失败不拦截本次装载。
        com.muoniumplayer.core.ncm.music.acoustics.AcousticsRuntime.ensureInstalled();

        SoundFile candidate;
        try {
            candidate = new SoundFile(file.getAbsolutePath());
        } catch (OutOfMemoryError error) {
            throw new IllegalStateException("音频过长，内存不足以载入：" + file.getName());
        } catch (LinkageError error) {
            // 解码器（jflac / jsyn）随 mod 一起打包，且按需加载：FLAC 只有在带标签时才会第一次用到
            // VorbisComment/VorbisString。若运行期间重新编译或替换了 mod 文件，这些还没加载过的类
            // 就再也读不到字节码，抛 NoClassDefFoundError。第三方源的无损文件普遍带标签，所以往往
            // 是它先撞上，看起来像"只有第三方源不能播放"。这与歌曲本身无关，提示重启比抛类名有用。
            throw new IllegalStateException("音频解码器加载失败，请重启游戏后重试："
                    + error.getClass().getSimpleName());
        }
        if (isUsable(candidate))
            return candidate;

        dispose(candidate);
        throw new IllegalStateException("Audio file contains no usable frames: " + file.getName());
    }

    private static boolean isUsable(SoundFile soundFile) {
        if (soundFile == null)
            return false;

        try {
            int frames = soundFile.frames();
            int sampleRate = soundFile.sampleRate();
            float duration = soundFile.duration();
            return frames > 0 && sampleRate > 0
                    && !Float.isNaN(duration)
                    && !Float.isInfinite(duration)
                    && duration > 0.0f;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void dispose(SoundFile soundFile) {
        if (soundFile == null)
            return;

        // 这里刻意不调 jump(0)。AudioSample.jump() 会清空数据队列，然后*开始*播放：
        // 它把读头重新加回正在跑的引擎并排一条新的数据命令，
        // 结果下面的 stop() 又把它拆掉。曲目自然结束时这无害，
        // 但交叉淡化里出去的那一路 deck 是在进来曲目还听得见时被释放的，
        // 那条没用的 voice 正好落在接缝上。
        try {
            soundFile.stop();
        } catch (RuntimeException ignored) {
        }
        try {
            soundFile.cleanUp();
        } catch (RuntimeException ignored) {
        }
    }
}
