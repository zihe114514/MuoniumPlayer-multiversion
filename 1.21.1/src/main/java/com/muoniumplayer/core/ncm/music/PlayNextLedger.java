package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.ncm.music.dto.Music;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「下一首播放」的记账本：只记用户点过哪几首，一格位置都不记。
 *
 * <p>这里刻意<b>不存任何下标</b>。上一版记的是用户那块插入在活队列里的绝对尾下标，可
 * {@code playList} 会被整条换掉、被随机模式就地重洗、也会在列表循环走到头时让播放指针跳回 0，
 * 而那些地方没有一个会去挪那个尾下标。它一旦落在很远处，读它的人就把「当前曲目到它之间」的每一格
 * 都当成用户排过的队：待播角标显示 99，几十行曲行被点上强调色，而用户下一次真点「下一首播放」
 * 时插入位置算到了整条列表的末尾——他以为排在第 1，实际排在一百首之后。改成只记身份之后，
 * 位置每次都从活队列现算，队列怎么动都不需要谁记得同步一个下标。</p>
 *
 * <p>待播段的定义是：<b>紧跟在当前曲目之后的、连续的几格「用户点过的歌」</b>。段外的成员不算
 * 待播——用户队列承诺的本来就是「这几首会连着现在这首播出去」，中间一旦断了一格，后面那几首
 * 已经落回歌单的正常顺序，再把它们报成待播就是在骗人。</p>
 *
 * <p>只依赖 {@link Music} 的来源身份（{@code equals} 用的就是来源加来源内 id，与曲行高亮、
 * 队列去重同一套判据），不碰 Minecraft 也不碰播放线程，所以能被单独跑起来的回归测试直接钉住。
 * 方法一律 {@code synchronized}：插入来自渲染线程、销账来自播放线程，而这个类的监视器是叶子锁，
 * 不会反向去拿 {@code CloudMusic.PLAY_NEXT_LOCK}，两边构不成环路。</p>
 */
final class PlayNextLedger {

    /** 用户点过「下一首播放」、还没轮到开播的曲目身份。插入序只是为调试时好读，不参与算位置。 */
    private final Set<Music> queued = new LinkedHashSet<>();

    /** 用户把这一首选成了「下一首播放」。同一首点两次只算一次，因为位置是按段数出来的。 */
    synchronized void remember(Music song) {
        if (song != null) queued.add(song);
    }

    /** 这一首不再欠一个待播位：被用户从待播列表里删掉了，或者它已经开播了。 */
    synchronized void forget(Music song) {
        if (song != null) queued.remove(song);
    }

    /** 忘掉整本账。队列被整条换掉、或者播放位置回到队首时走这里。 */
    synchronized void clear() {
        queued.clear();
    }

    /**
     * 账上还记着几首。
     *
     * <p><b>这个数不等于待播格数</b>：只有连在当前曲目后面的那一段才算待播。它存在的意义是让
     * 测试和日志能说清「用户一共点过几次」，界面任何一处要显示的数量都必须走
     * {@link #pendingRun(List, int)}。</p>
     */
    synchronized int tracked() {
        return queued.size();
    }

    /** 这一首在不在账上（不管它当前是不是真的排在待播段里）。 */
    synchronized boolean contains(Music song) {
        return song != null && queued.contains(song);
    }

    /**
     * 队列里某一格被换源替身顶掉了：那一格原本在账上的话，「用户为它排过队」要迁移到替身身上。
     *
     * <p>换源是<b>就地改写</b>队列那一格的（见 {@code CloudMusic} 的预备期换源），而待播段的定义是
     * 「紧跟在当前曲目之后、连续几格账上有名的歌」。不迁移的话，替身是个陌生身份，段数到它这里就
     * 断了，于是两个症状同时出现：待播列表看起来被换源清空了，而单曲循环下
     * {@code hasQueuedNext()} 也假报「没有下一首」，本该播出的那首被跳过、当前这首又重播一遍。</p>
     *
     * <p>是换掉而不是并存：旧身份此刻已经不在队列里了，继续留在账上只会让同一首歌在别处冒充待播。
     * 不在账上的歌（普通歌单曲目自己换了个源）什么都不做——它没欠用户一个待播位。</p>
     */
    synchronized void replacePendingIdentity(Music replaced, Music replacement) {
        if (replaced == null || replacement == null || replaced == replacement) return;
        if (!queued.remove(replaced)) return;
        queued.add(replacement);
    }

    /**
     * 紧跟在 {@code curIdx} 之后的连续待播格数。
     *
     * <p>队列或指针怎么动，这个数都跟着动，正因为如此它不需要任何维护代码 —— 这就是把绝对下标
     * 换成身份记账换来的东西。</p>
     */
    synchronized int pendingRun(List<Music> queue, int curIdx) {
        int index = curIdx + 1;
        int run = 0;
        while (true) {
            Music song = at(queue, index);
            if (song == null || !queued.contains(song)) return run;
            index++;
            run++;
        }
    }

    /**
     * 下一次插入该落在哪个下标：当前待播段的末尾，没有段就紧贴当前曲目。
     *
     * <p>返回值保证落在 {@code [0, size]} 之内，调用方可以直接喂给 {@code List#add(int, Object)}。
     * 播放指针意外为负（切歌那一瞬间 {@code curIdx} 可能短暂停在 -1）时按 0 处理，宁可插在最前面，
     * 也不要把用户排的歌丢到列表尾部去。</p>
     */
    synchronized int insertIndex(List<Music> queue, int curIdx) {
        int size = queue == null ? 0 : queue.size();
        int anchor = Math.max(0, curIdx);
        int index = anchor + 1 + pendingRun(queue, anchor);
        if (index > size) index = size;
        return Math.max(0, index);
    }

    /** 当前待播段里的曲目，按将要播放的顺序。给「下一首播放」列表面板每帧读一次。 */
    synchronized List<Music> pending(List<Music> queue, int curIdx) {
        int run = pendingRun(queue, curIdx);
        if (run <= 0) return Collections.emptyList();
        List<Music> result = new ArrayList<>(run);
        for (int index = curIdx + 1; index <= curIdx + run; index++) {
            Music song = at(queue, index);
            if (song == null) break;
            result.add(song);
        }
        return result;
    }

    /** 这一首是否就站在当前待播段里 —— 决定曲行上那枚「下一首播放」该不该亮。 */
    synchronized boolean isPending(List<Music> queue, int curIdx, Music song) {
        if (song == null) return false;
        int run = pendingRun(queue, curIdx);
        for (int index = curIdx + 1; index <= curIdx + run; index++) {
            if (song.equals(at(queue, index))) return true;
        }
        return false;
    }

    /**
     * 从 {@code index} 往后数连续的待播格，把命中的那些从活队列里摘出来，并同步销账。
     *
     * <p>倒着删：正着删每删一格后面的下标都要重算，而这里要的就是「连着当前曲目那几格」。</p>
     *
     * @return 被摘掉的曲目数量
     */
    synchronized int dropPending(List<Music> queue, int curIdx) {
        if (queue == null) return 0;
        int run = pendingRun(queue, curIdx);
        for (int index = curIdx + run; index > curIdx; index--) {
            forget(at(queue, index));
            if (index < queue.size()) queue.remove(index);
        }
        return run;
    }

    private static Music at(List<Music> queue, int index) {
        if (queue == null || index < 0 || index >= queue.size()) return null;
        return queue.get(index);
    }
}
