package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.utils.other.multithreading.BackgroundWork;

import java.util.ArrayList;
import java.util.List;

/**
 * 进阶模式「一键排列」的<b>后台批次</b>：整段待播队列的特征采集跑在低优先级池上，界面线程
 * 只负责点按、看进度、取消。
 *
 * <h2>为什么这条必须离开渲染线程</h2>
 *
 * <p>排一次列要给每首歌做三件读盘的活：找到本地那个可播文件（要嗅探容器格式）、读它的整曲
 * 包络边车、读 {@code FoliaTrackProfiler} 的曲风侧车。单曲只有几毫秒，可待播队列是用户灌进来的
 * 歌单，几百首很常见 —— 原先这些活是在按钮的点击回调里一件件同步做完的，表现就是点下去卡住
 * 若干秒，而那期间 Minecraft 的整个界面都不刷新。计算本身（贪心）不贵，贵的是读盘。</p>
 *
 * <h2>为什么还要分片</h2>
 *
 * <p>后台池只有 1..2 条线程，而且同一口池跑着<b>交接用的</b>预备解码与整曲包络扫描。一趟几百首
 * 的读盘如果一口气占住那条线程，用户真正在等的那一次预备就得排在它后面 —— 排列是锦上添花，
 * 交接不能因为按过一次按钮而退化。所以这一批每读 {@value #CHUNK_SONGS} 首就把剩下的重新入队，
 * 让池里排在后面的活儿有机会插进来。</p>
 *
 * <h2>落排之前要验身份</h2>
 *
 * <p>算完到落排之间隔了几十秒，期间队列完全可能变：用户又插了一首「下一首播放」、正在播的这
 * 首结束导致 {@code curIdx} 前移。只比长度是挡不住的（少一首又多一首，长度不变），照一份过期
 * 的排布去搬会把用户的顺序搅乱。所以这里带着<b>扫描时那份快照</b>去落，逐格比身份，任何一格
 * 对不上就整个丢掉、一行日志。</p>
 *
 * <p>取消只停止<b>扫描</b>：落排这一步要么做完要么根本没开始，不存在「排到一半」的队列。</p>
 */
public final class QueueFlowSorter {

    private QueueFlowSorter() {
    }

    /**
     * 一次排列最多看前这么多首。
     *
     * <p>上限是给「读盘」而不是给算法设的：贪心是 {@code O(n²)}，几百首还在毫秒级，而读一千多
     * 份包络要几分钟，且这段时间里用户什么都得不到。超出部分原位不动，提示里写清楚看了多少。</p>
     */
    static final int MAX_SONGS = 240;

    /** 一片读多少首然后重新入队，让后台池里别的活儿有机会插进来。 */
    static final int CHUNK_SONGS = 12;

    /**
     * 量不出特征的那些，一次最多送这么多去预热。
     *
     * <p>预热一首要解码整曲（外部格式还要转码）。不设上限就是往后台池里塞几百个长任务，
     * 把真正要播的那一首的预备挤到后面去。剩下的等用户再按一次，或者等它们自己被预备时补上。</p>
     */
    static final int WARM_LIMIT = 12;

    /** 正在跑的那一批；{@code null} 表示空闲。同一时刻只允许一批。 */
    private static volatile Batch running;

    /** 结论或进度文案，界面每帧读一次，绝不在渲染线程上拼。 */
    private static volatile String notice = "";

    /**
     * 结论换了就 +1：界面靠它判断「这是一句新话，要重新计秒」，而不是每帧都刷新那个倒计时。
     */
    private static volatile long noticeSerial;

    /** 已经扫描的格数，只给进度文案用。 */
    private static volatile int scanned;

    /** 这一批一共要扫多少首。 */
    private static volatile int total;

    /**
     * 点按入口。<b>立刻返回</b>一句提示，活儿交给后台池。
     *
     * <p>每一条结论都过 {@link #publish} —— 界面看的是序号而不是返回值（一次点按的文案要显示
     * 4 秒，而后台到货的那句结论根本没有「返回值」可给）。</p>
     *
     * @return 刚刚发布的那句话
     */
    public static synchronized String request() {
        String result;
        if (!AutomixSettings.isAdvancedModeEnabled()) {
            result = "进阶模式没开，队列不动";
        } else if (running != null) {
            result = "正在排列 " + scanned + "/" + total + "，再按一次取消";
        } else {
            List<Music> pending = CloudMusic.getQueuedNextSongs();
            int size = pending.size();
            result = size < 2 ? "待播队列至少两首才谈排列" : startBatch(pending, size);
        }
        publish(result);
        return result;
    }

    private static String startBatch(List<Music> pending, int size) {
        int limit = Math.min(size, MAX_SONGS);
        Batch batch = new Batch(new ArrayList<>(pending.subList(0, limit)), limit, size - limit);
        scanned = 0;
        total = limit;
        running = batch;
        try {
            BackgroundWork.submit(batch);
        } catch (Throwable rejected) {
            if (running == batch) running = null;
            return "后台不可用，队列没动";
        }
        return "开始排列 " + limit + " 首"
                + (size - limit > 0 ? "（队列还有 " + (size - limit) + " 首这次不看）" : "");
    }

    /** 界面用它决定按钮写「排列」还是「取消」。 */
    public static boolean isRunning() {
        return running != null;
    }

    /**
     * 取消正在跑的扫描。
     *
     * @return 是否真的有一批被取消（空闲时返回 false，界面据此不显示「已取消」）
     */
    public static boolean cancel() {
        Batch batch = running;
        if (batch == null) return false;
        batch.cancelled = true;
        running = null;
        publish("已取消排列，队列没有改动");
        return true;
    }

    /**
     * 跑着的时候给进度，空闲时给最近一句结论。界面每帧读一次，字符串都在这里拼。
     *
     * <p>进度这句刻意<b>短</b>：它借的位置是表头那条「拖动可调整顺序」，右边就挨着「排列 / 清空 / ×」
     * 三颗按钮，长一个字就压上去。「这次少看了多少首」放在结论里说，那里只有 4 秒而且抽屉已经排完了。</p>
     */
    public static String notice() {
        if (running == null) return notice;
        return "排列中 " + scanned + "/" + total;
    }

    /** 结论的序号；变了就说明界面该把那句提示重新计一次秒。 */
    public static long noticeSerial() {
        return noticeSerial;
    }

    /** 最近一句<b>结论</b>（不含进度），界面用它显示那 4 秒。 */
    public static String lastNotice() {
        return notice;
    }

    private static void publish(String text) {
        notice = text == null ? "" : text;
        noticeSerial++;
    }

    /**
     * 一批扫描。状态都在实例里，静态字段只做「哪一批是当前」的记账，所以一个迟到的分片
     * 不会把新一轮的进度覆盖掉（它发现自己不是当前那批就直接退）。
     */
    private static final class Batch implements Runnable {
        private final List<Music> songs;
        private final AutomixFlowPlanner.Feature[] features;
        private final List<Music> missing = new ArrayList<>();
        private final int limit;
        /** 因为 {@link #MAX_SONGS} 而这次没看的尾部有几首，结论里要交代。 */
        private final int skipped;
        private int cursor;
        private int measured;
        private volatile boolean cancelled;

        private Batch(List<Music> songs, int limit, int skipped) {
            this.songs = songs;
            this.limit = limit;
            this.skipped = skipped;
            this.features = new AutomixFlowPlanner.Feature[limit];
        }

        @Override
        public void run() {
            if (cancelled || running != this) return;      // 被取消，或被新一轮顶掉
            boolean resubmitted = false;
            try {
                int end = Math.min(cursor + CHUNK_SONGS, limit);
                for (; cursor < end && !cancelled; cursor++) {
                    scanned = cursor + 1;
                    measure(cursor);
                }
                if (cancelled || running != this) return;
                if (cursor < limit) {
                    // 还有下一片：重新入队，排在后面的预备解码能插到这一片前面。
                    BackgroundWork.submit(this);
                    resubmitted = true;
                    return;
                }
                finish();
            } catch (Throwable failure) {
                // 一趟意外（读盘失败、某个文件被删了、池子关了）不许把 running 钉死：
                // 那会让按钮永远停在「取消」，而这批其实已经不存在了。
                System.err.println("[Automix/Flow] 排列中断：" + failure);
                publish("排列中途出错，队列没动");
            } finally {
                // 先落排再让位：反过来的话中间那一瞬界面显示「空闲」，用户可能就在这儿又按了一次，
                // 于是两批同时改同一段队列。重新入队的那条路除外——那一批还活着。
                if (!resubmitted && running == this) running = null;
            }
        }

        /** 量一首：只读，绝不扫描、绝不起进程 —— 这一切都跑在后台池，但一次排列不该触发几百次整曲解码。 */
        private void measure(int index) {
            Music song = songs.get(index);
            if (song == null) return;
            java.io.File file = CloudMusic.existingPlayableAudioFile(song);
            if (file == null) {
                missing.add(song);                          // 还没下载到本地：等它下完再说
                return;
            }
            AutomixEnvelope envelope = AutomixEnvelope.load(file);
            features[index] = AutomixFlowPlanner.featureOf(envelope, FoliaTrackProfiler.peek(file));
            if (features[index] != null && features[index].isSortable()) measured++;
            else missing.add(song);                         // 量不出来：送去预热，这一格原地不动
        }

        private void finish() {
            if (measured < 2) {
                warmMissing();
                publish("只有 " + measured + " 首量得出特征，已送后台分析，稍后再按一次");
                return;
            }
            int[] order = AutomixFlowPlanner.plan(features);
            if (!CloudMusic.reorderQueuedNext(order, songs)) {
                warmMissing();
                publish("队列在排列期间变了，没有改动");
                return;
            }
            warmMissing();
            int still = limit - measured;
            publish("已按衔接重排 " + measured + " 首"
                    + (still > 0 ? "，另 " + still + " 首原位不动" : "")
                    + (skipped > 0 ? "，末尾 " + skipped + " 首这次没看" : ""));
        }

        private void warmMissing() {
            int count = Math.min(missing.size(), WARM_LIMIT);
            for (int i = 0; i < count; i++) {
                final Music song = missing.get(i);
                try {
                    BackgroundWork.submit(() -> {
                        try {
                            CloudMusic.warmAutomixProfile(song);
                        } catch (Throwable ignored) {
                            // 预热失败的正确处理是「等它真轮到播时再说」，不打扰用户。
                        }
                    });
                } catch (Throwable rejected) {
                    return;     // 池子都不要了，剩下的下次再补
                }
            }
        }
    }
}
