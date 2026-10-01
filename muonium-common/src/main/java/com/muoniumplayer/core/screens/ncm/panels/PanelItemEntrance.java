package com.muoniumplayer.core.screens.ncm.panels;

import com.muoniumplayer.core.interfaces.SharedConstants;

/**
 * 面板条目「一件件落进来」的那套进场：不透明度 + 一段矩阵变换，进度按调用方给的起点与延迟算。
 *
 * <p>给两个用户用：歌单页的曲目行（{@link MusicWidget}）和主页的歌单卡片
 * （{@code HomePanel.PlaylistWidget}）。以前这两处要么只有 alpha（整页像一块布突然亮起），
 * 要么干脆没有进场，节奏还各写各的。时长与幅度集中在这里，两条列表才会是同一套动作。</p>
 *
 * <p>位移与缩放<b>全走矩阵</b>（{@code AbstractWidget#setTransformations} 在 pushMatrix 里跑它），
 * 条目的真实布局边界一动不动 —— 滚动位置与点击判定不受进场影响。这正是原来只做 alpha 的理由，
 * 这里把它保住。</p>
 */
final class PanelItemEntrance {

    /** 一条从演到停的时长（毫秒）。再长就会在快速滚动时看见半透明的行。 */
    private static final long DURATION_MILLIS = 280L;
    /** 从下方这么多逻辑像素落进来。 */
    private static final double DROP_DISTANCE = 6.0;
    /** 落位前缩到这么多：配合位移才有「落进来」，而不是一句「淡出来」。 */
    private static final double SCALE_FROM = .97;

    private long startedAt = -1L;
    private long delayMillis;
    private boolean started = false;
    private boolean finished = true;

    /** 开始一次进场。{@code delayMillis} 是这一条相对同批起点的错峰延迟。 */
    void begin(long startedAt, long delayMillis) {
        this.startedAt = startedAt;
        this.delayMillis = Math.max(0L, delayMillis);
        this.started = true;
        this.finished = false;
    }

    /** 还没轮到过一次 —— 见 {@link RevealQueue}：进场要等条目真的被看见才开始。 */
    boolean isStarted() {
        return started;
    }

    /**
     * 推进到本帧并返回缓动进度：0 = 还没轮到，1 = 已经落位。
     *
     * <p>三次缓出收尾，线性收尾会「啪」地停住。走到 1 之后自己标成演完，调用方据此把
     * {@code transformations} 摘掉 —— 一首 200 行的歌单不该为已经停住的行一直付矩阵开销。</p>
     */
    double tick() {
        if (finished) return 1.0;

        long elapsed = System.currentTimeMillis() - startedAt - delayMillis;
        if (elapsed <= 0L) return 0.0;

        double progress = Math.min(1.0, elapsed / (double) DURATION_MILLIS);
        double eased = 1.0 - Math.pow(1.0 - progress, 3.0);
        if (progress >= 1.0) {
            eased = 1.0;
            finished = true;
        }
        return eased;
    }

    boolean isFinished() {
        return finished;
    }

    /** 把这一条还没落位的那点位移与缩放乘到当前矩阵上。中心要传条目的绝对中心，缩放才不打偏。 */
    static void applyTransform(double eased, double centerX, double centerY) {
        if (eased >= 1.0) return;

        SharedConstants.api.getGLStateManager().translate(0.0, DROP_DISTANCE * (1.0 - eased), 0.0);
        double scale = SCALE_FROM + (1.0 - SCALE_FROM) * eased;
        SharedConstants.api.getGLStateManager().translate(centerX, centerY, 0.0);
        SharedConstants.api.getGLStateManager().scale(scale, scale, 1.0);
        SharedConstants.api.getGLStateManager().translate(-centerX, -centerY, 0.0);
    }

    /**
     * 给「这一帧第一次被看见」的条目发错峰号。
     *
     * <p>为什么不是建页时按序号一次性发好：{@code ScrollPanel#shouldRenderChildren} 会把视口外的
     * 行整个跳过，它们的回调一帧都不跑。于是「滚进视口」天然等于「第一次被渲染」—— 建页时就发好
     * 号的话，下面的行在你滚到它之前早就演完了，往下滚什么动画都看不见。
     *
     * <p>面板在自己的 {@code onRender} 开头调一次 {@link #beginFrame()}（它跑在子控件之前），
     * 条目在第一次被渲染时领号。首屏那一批拿到的是从 0 往上排的号，所以还是原来那道自上而下的
     * 瀑布；往下滚时零散几行进视口，各领各的号，也是同一套动作。
     */
    static final class RevealQueue {

        /** 首屏那一批比换页转场晚一档到场；滚进来的行不留这一档，否则会出现一段空白行。 */
        private static final long FIRST_BATCH_LEAD_MILLIS = 90L;
        private static final long STAGGER_MILLIS = 16L;
        private static final int STAGGER_LIMIT = 28;

        private int revealedThisFrame;
        private boolean firstBatch = true;

        /** 每帧开头调用：这一帧里新看见的条目重新从 0 号排起。 */
        void beginFrame() {
            if (this.revealedThisFrame > 0) this.firstBatch = false;
            this.revealedThisFrame = 0;
        }

        /** 重新武装：换页重建整列时（{@code onInit} 再走一遍）首屏那一档要再给一次。 */
        void restart() {
            this.revealedThisFrame = 0;
            this.firstBatch = true;
        }

        /** 领一个错峰延迟（毫秒）。 */
        long nextDelayMillis() {
            long delay = (this.firstBatch ? FIRST_BATCH_LEAD_MILLIS : 0L)
                    + Math.min(this.revealedThisFrame++, STAGGER_LIMIT) * STAGGER_MILLIS;
            return delay;
        }
    }
}
