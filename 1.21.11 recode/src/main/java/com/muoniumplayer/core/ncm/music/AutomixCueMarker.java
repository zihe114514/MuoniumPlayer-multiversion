package com.muoniumplayer.core.ncm.music;

/**
 * 界面上那条「接缝开在哪儿」的读数。
 *
 * <p>automix 的规划结果原本只有两个读者：音频路径（照着它画增益曲线）和标准输出
 * （{@code AutomixPlan.summary} 那行日志）。玩家听得到接缝，却看不见接缝——他看到的
 * 进度条在交接之前与一次硬切毫无区别。这个类就是把一份已经算好的
 * {@link AutomixPlan} 里与位置有关的那几个数，翻译成进度条能直接用的东西。</p>
 *
 * <h2>为什么不直接读 {@code AutomixPlan}</h2>
 *
 * <p>{@code AutomixPlan} 与它的全部字段都是<b>包内可见</b>的，而计划本身活在
 * {@code CloudMusic.PlayThread} 里面（预备期在 {@code armed} 上、交接中在
 * {@code activeFade} 上）。界面层跨着包，既读不到也不该读到增益、相位误差、
 * 人声深度那十几个只对音频有意义的数。所以这里只带进度条用得上的四个。</p>
 *
 * <h2>时间轴归谁</h2>
 *
 * <p>三个数都落在<b>这条进度条此刻显示的那一首</b>的时间轴上，单位毫秒：</p>
 *
 * <ul>
 *   <li>交接<b>之前</b>（{@link #fading} 为 false）：进度条是出场曲的，于是
 *       {@link #cueMillis} 取计划的 {@code fireMillis}——它原本就是「退出方 deck
 *       开始淡出之处」，同一根时间轴，不需要换算；</li>
 *   <li>交接<b>之中</b>（{@link #fading} 为 true）：{@code performAutomixHandover}
 *       已经把 {@code currentlyPlaying} 与 {@code player} 换成了<b>进场</b>那一首，
 *       进度条显示的已经是新歌。这时照搬 {@code fireMillis} 会把标记画在错误的一首上，
 *       所以取计划的 {@code incomingCueMillis}——接缝在新歌上的那一段。</li>
 * </ul>
 *
 * <p>因此同一个记号在交接前后都回答同一个问题：<b>这一首歌的哪一段是两首叠在一起</b>。
 * 交接前它读作「再往右就是接缝」，交接后读作「脚下这一段才是接缝」。</p>
 *
 * <h2>没有读数就是没有读数</h2>
 *
 * <p>拿不到计划时 {@link CloudMusic#automixCueNow()} 返回 {@code null} 而不是一个零值对象：
 * 未预备、预备已作废、镜像态（出声的是外部播放器，本机根本没有交接）、时长还没落地，
 * 这几种情况画任何标记都是在向玩家预报一件不会发生的事。</p>
 */
public final class AutomixCueMarker {

    /** 接缝在这条进度条所属曲目上的起点，毫秒。 */
    public final long cueMillis;

    /** 接缝的终点，毫秒。<b>可能越过曲目末尾</b>，所以一律经 {@link #bandEndFraction()} 取用。 */
    public final long bandEndMillis;

    /** 这条进度条的总时长，毫秒；构造时保证 {@code > 0}。 */
    public final long durationMillis;

    /** 交接是否已经发生（进度条属于进场那一首）。 */
    public final boolean fading;

    AutomixCueMarker(long cueMillis, long bandEndMillis, long durationMillis, boolean fading) {
        this.cueMillis = Math.max(0L, cueMillis);
        // 终点不早于起点：进场曲入点极近、或交叠被 {@code LATE_FIRE_FADE_MILLIS} 那类
        // 缩短过的计划，都可能算出一个倒挂的窗口，那画出来是一条往左长的带子。
        this.bandEndMillis = Math.max(this.cueMillis, bandEndMillis);
        this.durationMillis = durationMillis;
        this.fading = fading;
    }

    /** 接缝起点占整条进度条的比例，钳在 {@code [0,1]}。 */
    public double cueFraction() {
        return fraction(cueMillis);
    }

    /** 接缝终点占整条进度条的比例，钳在 {@code [0,1]}。 */
    public double bandEndFraction() {
        return fraction(bandEndMillis);
    }

    /** 交叠带本身的宽度（比例）；{@code >= 0}，因为终点从不早于起点。 */
    public double bandWidthFraction() {
        return Math.max(0.0, bandEndFraction() - cueFraction());
    }

    /** 离接缝还有多少毫秒；已经越过起点时为 0。 */
    public long remainingMillis(long positionMillis) {
        return Math.max(0L, cueMillis - Math.max(0L, positionMillis));
    }

    private double fraction(long millis) {
        if (durationMillis <= 0L) return 0.0;
        double value = millis / (double) durationMillis;
        if (Double.isNaN(value)) return 0.0;
        return value < 0.0 ? 0.0 : (value > 1.0 ? 1.0 : value);
    }

    /**
     * 一处进度条的标记平滑器：把 {@link #cueFraction()} 往目标值上收敛。
     *
     * <p>为什么必须有它：{@code ArmedTrack.plan} 在真正交接之前<b>会被换掉</b>——整曲包络
     * 到货后的回头重算、以及拖过切点时那条顶住交接的重算，预算一共三次。标记若每次都
     * 直接落在最新值上，玩家看到的就是一根在歌中间瞬移的线，而瞬移读起来像 bug。</p>
     *
     * <p>按<b>墙钟</b>而不是按帧收敛（{@code dt} 钳在 50 ms 以内）：高帧率与低帧率机器上
     * 收敛用时相同，而一帧卡顿不会被当成「时间过了很多」直接跳到终点。</p>
     *
     * <p>每处进度条各持一个实例（它们互不相干，且标记消失时各自的账要各自清）；
     * 标记消失那一方要调 {@link #reset()}，否则下次出现时会从上一首的位置慢慢飘过来。</p>
     */
    public static final class Glide {
        /** 收敛用时常数：约三分之一秒走完剩下的距离。 */
        private static final double TIME_CONSTANT_MILLIS = 110.0;
        private static final double MAX_STEP_MILLIS = 50.0;
        /** 已经足够接近目标时直接吸附，避免尾部永远差半像素。 */
        private static final double SNAP_EPSILON = 0.0008;

        private double cue = -1.0;
        private double bandEnd = -1.0;
        private long lastUpdateNs;

        /**
         * 推进一帧，返回这一帧该画的位置。
         *
         * @param marker 本帧读数；{@code null}（本帧没有标记）时不动，等调用方下一帧
         *               没有标记就把它丢弃，出现新标记时靠 {@link #reset()} 吸附
         */
        public Positions update(AutomixCueMarker marker) {
            long nowNs = System.nanoTime();
            long previousNs = lastUpdateNs;
            lastUpdateNs = nowNs;
            if (marker == null) return new Positions(cue, bandEnd);
            double targetCue = marker.cueFraction();
            double targetBandEnd = marker.bandEndFraction();
            if (cue < 0.0 || previousNs == 0L) {
                // 第一帧（或刚 reset 过）没有「上一个位置」可言，直接吸附：
                // 一首新歌起播时让标记从 0 爬到曲尾，读起来像是接缝正在自己往后退。
                cue = targetCue;
                bandEnd = targetBandEnd;
                return new Positions(cue, bandEnd);
            }
            double elapsed = Math.min(MAX_STEP_MILLIS, Math.max(0.0, (nowNs - previousNs) / 1_000_000.0));
            double alpha = 1.0 - Math.exp(-elapsed / TIME_CONSTANT_MILLIS);
            cue = approach(cue, targetCue, alpha);
            bandEnd = approach(bandEnd, targetBandEnd, alpha);
            return new Positions(cue, bandEnd);
        }

        /** 清账：本帧之后没有标记了，或换了一首歌。 */
        public void reset() {
            cue = -1.0;
            bandEnd = -1.0;
            lastUpdateNs = 0L;
        }

        private static double approach(double current, double target, double alpha) {
            if (target < 0.0) return current;
            double next = current + (target - current) * alpha;
            return Math.abs(target - next) <= SNAP_EPSILON ? target : next;
        }
    }

    /** {@link Glide#update} 的结果：这一帧该画在哪儿，都是比例。 */
    public static final class Positions {
        public final double cueFraction;
        public final double bandEndFraction;

        Positions(double cueFraction, double bandEndFraction) {
            this.cueFraction = cueFraction;
            this.bandEndFraction = bandEndFraction;
        }

        /** 还没吸附过任何读数（第一帧之前），调用方这一帧不该画。 */
        public boolean valid() {
            return cueFraction >= 0.0 && bandEndFraction >= 0.0;
        }

        /** 交叠带宽度；终点已经钳过，这里只会 {@code >= 0}。 */
        public double bandWidth() {
            return Math.max(0.0, bandEndFraction - cueFraction);
        }
    }
}
