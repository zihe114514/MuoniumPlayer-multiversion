package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.ncm.music.dto.Album;
import com.muoniumplayer.core.ncm.music.dto.Artist;
import com.muoniumplayer.core.ncm.music.dto.Music;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「下一首播放」记账本的回归测试：<b>位置每次从活队列现算，所以没有任何下标会烂掉</b>。
 *
 * <p>这一组用例全部对着上一版的病根写。上一版记的是用户那块插入在 {@code playList} 里的绝对尾下标，
 * 读数就是 {@code 尾下标 - curIdx}，于是：</p>
 *
 * <ul>
 *   <li>列表循环回到队首（{@code curIdx} 跳回 0，而那个下标还停在 99）→ 待播角标显示 99；</li>
 *   <li>随机模式就地重洗整条列表 → 同上，而且几十行曲行被点上强调色；</li>
 *   <li>下一次真点「下一首播放」→ 插入位置算到旧下标后面，用户以为排第 1，实际排在一百首之后。</li>
 * </ul>
 *
 * <p>现在账本只记曲目身份，待播段是「紧跟在当前曲目之后的连续几格」，所以这三条都不需要有人去
 * 同步什么：队列怎么动，读数自己就跟着变。不启动 Minecraft、不联网，纯数据结构层面的事。</p>
 */
class PlayNextLedgerTest {

    @Test
    @DisplayName("待播数只数紧跟当前曲目的连续段，点击顺序就是将要播放的顺序")
    void countsTheContiguousRunAfterCurrentTrack() {
        List<Music> queue = songs(10);
        PlayNextLedger ledger = new PlayNextLedger();
        int current = 3;

        Music first = song(901L);
        Music second = song(902L);
        Music third = song(903L);
        queue.add(ledger.insertIndex(queue, current), first);
        ledger.remember(first);
        queue.add(ledger.insertIndex(queue, current), second);
        ledger.remember(second);
        queue.add(ledger.insertIndex(queue, current), third);
        ledger.remember(third);

        assertEquals(3, ledger.pendingRun(queue, current), "连点三次就是三格待播");
        assertEquals(7, ledger.insertIndex(queue, current), "第四次该接在这段末尾");
        assertEquals(new ArrayList<>(java.util.Arrays.asList(first, second, third)),
                ledger.pending(queue, current), "列表顺序必须是用户的点击顺序");
        // 插入把原来的歌序往后推了一格，但段只有三格：多出来的那些正常歌序不算待播。
        assertEquals(first, queue.get(4));
        assertEquals(second, queue.get(5));
        assertEquals(third, queue.get(6));
        assertEquals(songs(10).get(4), queue.get(7));
    }

    @Test
    @DisplayName("中间断了一格，段后面的那几首就不再算待播 —— 它们已经落回歌单正常顺序")
    void runStopsAtTheFirstGap() {
        List<Music> queue = songs(6);
        PlayNextLedger ledger = new PlayNextLedger();
        Music queued = queue.get(4);
        ledger.remember(queued);

        assertTrue(ledger.contains(queued), "身份还在账上");
        assertEquals(0, ledger.pendingRun(queue, 0), "它不紧跟当前曲目，就不在待播段里");
        assertFalse(ledger.isPending(queue, 0, queued));
    }

    @Test
    @DisplayName("播放指针跳回队首：账上残留的身份也不会被数成 99 首待播")
    void loopWrapCannotInflateTheCount() {
        List<Music> queue = songs(100);
        PlayNextLedger ledger = new PlayNextLedger();
        int nearTail = 98;

        Music queued = song(950L);
        queue.add(ledger.insertIndex(queue, nearTail), queued);
        ledger.remember(queued);
        assertEquals(1, ledger.pendingRun(queue, nearTail));

        // 列表循环走到头：curIdx 回到 0，紧跟它的是正常歌序。旧模型在这里报 99。
        assertEquals(0, ledger.pendingRun(queue, 0), "旧模型会把整段正常歌序当成用户排的队");
        assertEquals(0, ledger.pendingRun(queue, 5));
        assertEquals(1, ledger.insertIndex(queue, 0), "下一次插入必须紧贴当前曲目，而不是落在旧下标后面");
    }

    @Test
    @DisplayName("随机重洗把段打断之后，插入位置跟着活队列走，不再认那个记过的下标")
    void insertFollowsTheLiveQueueInsteadOfArememberedIndex() {
        List<Music> queue = songs(60);
        PlayNextLedger ledger = new PlayNextLedger();
        Music queued = song(960L);
        queue.add(queued);
        ledger.remember(queued);
        assertEquals(1, ledger.pendingRun(queue, 59), "它还紧跟在最后一首正常曲目后面");

        Collections.shuffle(queue, new java.util.Random(7L));

        // 重洗之后没人通知账本，而读数自己就对了：唯一要防的是「又记回那个已经不存在的下标」。
        int current = 0;
        assertTrue(ledger.pendingRun(queue, current) <= 1);
        assertEquals(current + 1 + ledger.pendingRun(queue, current), ledger.insertIndex(queue, current),
                "插入位置永远等于当前曲目加上真正的连续段");
    }

    @Test
    @DisplayName("开播即销账：同一首歌在队列里出现两次时，播过的那次不能把后面那格冒充成待播")
    void startedTrackLosesItsPendingSlot() {
        List<Music> queue = new ArrayList<>();
        Music current = song(1L);
        Music twin = song(1L);
        Music other = song(2L);
        queue.add(current);
        queue.add(other);
        queue.add(twin);
        PlayNextLedger ledger = new PlayNextLedger();
        ledger.remember(other);
        assertEquals(1, ledger.pendingRun(queue, 0));

        // 「下一首」播完了：位置前进一格，同时起播这一点把它的身份从账上划掉。
        ledger.forget(other);
        assertEquals(0, ledger.pendingRun(queue, 1),
                "同一首歌在后面再出现时，不该被认成用户还排着队");
        assertEquals(0, ledger.tracked());
    }

    @Test
    @DisplayName("清空待播：只摘掉连续段那几格，后面的歌单顺序一格不少地留着")
    void dropPendingRemovesOnlyTheRun() {
        List<Music> queue = songs(6);
        PlayNextLedger ledger = new PlayNextLedger();
        Music first = song(971L);
        Music second = song(972L);
        Music tailSong = queue.get(5);
        queue.add(ledger.insertIndex(queue, 0), first);
        ledger.remember(first);
        queue.add(ledger.insertIndex(queue, 0), second);
        ledger.remember(second);
        assertEquals(2, ledger.pendingRun(queue, 0));

        assertEquals(2, ledger.dropPending(queue, 0));
        assertEquals(0, ledger.pendingRun(queue, 0));
        assertEquals(0, ledger.tracked(), "摘掉的歌同时要从账上销掉");
        assertEquals(6, queue.size(), "六首原曲加两首插入再减两首：列表只短了那两格");
        assertTrue(queue.contains(tailSong), "歌单自己的曲目一首不许少");
        assertFalse(queue.contains(first));
        assertFalse(queue.contains(second));
    }

    @Test
    @DisplayName("空队列、负指针、null 曲目都不许炸，插入位置永远落在能 add 的区间里")
    void degenerateInputsStayInBounds() {
        PlayNextLedger ledger = new PlayNextLedger();
        List<Music> queue = songs(3);

        assertEquals(0, ledger.pendingRun(null, 0));
        assertEquals(0, ledger.pendingRun(Collections.<Music>emptyList(), 0));
        assertEquals(0, ledger.insertIndex(null, 0));
        assertEquals(1, ledger.insertIndex(queue, -1),
                "指针为负（切歌那一瞬间会短暂停在 -1）时按队首之后算，与旧行为一致，别丢到列表尾部");
        assertEquals(3, ledger.insertIndex(queue, 99), "越界也只能是 size，add(int) 才不抛");
        assertEquals(0, ledger.dropPending(null, 0));
        assertTrue(ledger.pending(null, 0).isEmpty());
        assertFalse(ledger.isPending(queue, 0, null));
        ledger.remember(null);
        ledger.forget(null);
        assertEquals(0, ledger.tracked());
    }

    @Test
    @DisplayName("去重按来源身份：跨平台的同名歌不是同一首")
    void identityIsSourceScoped() {
        Music netease = song(500L);
        Music kugouTwin = song(500L);
        kugouTwin.setSource(com.muoniumplayer.core.ncm.music.MusicPlatform.KUGOU);
        assertFalse(netease.equals(kugouTwin), "同 id 不同来源必须算两首歌");

        PlayNextLedger ledger = new PlayNextLedger();
        ledger.remember(netease);
        List<Music> queue = new ArrayList<>();
        queue.add(song(1L));
        queue.add(kugouTwin);
        assertEquals(0, ledger.pendingRun(queue, 0), "网易云那首的账不能算到酷狗头上");
    }

    @Test
    @DisplayName("换源替身顶掉待播段里的一格：那段队列不能因此断在这里")
    void replacementInheritsPendingIdentity() {
        List<Music> queue = songs(8);
        PlayNextLedger ledger = new PlayNextLedger();
        Music first = song(981L);
        Music second = song(982L);
        queue.add(ledger.insertIndex(queue, 0), first);
        ledger.remember(first);
        queue.add(ledger.insertIndex(queue, 0), second);
        ledger.remember(second);
        assertEquals(2, ledger.pendingRun(queue, 0));

        // automix 预备期发现下一首播不出来，换到别的平台：队列那一格整块换成陌生身份。
        Music standIn = song(983L);
        queue.set(1, standIn);
        ledger.replacePendingIdentity(first, standIn);
        assertEquals(2, ledger.pendingRun(queue, 0),
                "替身要继承那一格的待播身份，否则待播列表看起来就是被换源清空了");
        assertEquals(2, ledger.tracked(), "迁移是换掉而不是追加：账上还是两格");
        assertTrue(ledger.contains(standIn));
        assertFalse(ledger.contains(first), "旧身份已经不在队列里，留着它只会在别处冒充待播");
    }

    @Test
    @DisplayName("迁移只对账上有名的歌生效：普通曲目被换源不欠它一个待播位")
    void replacementOfAnUntrackedSongIsANoOp() {
        List<Music> queue = songs(5);
        PlayNextLedger ledger = new PlayNextLedger();
        Music queued = song(990L);
        queue.add(ledger.insertIndex(queue, 0), queued);
        ledger.remember(queued);

        Music ordinary = queue.get(3);
        Music standIn = song(991L);
        ledger.replacePendingIdentity(ordinary, standIn);
        assertFalse(ledger.contains(standIn), "没排过队的歌换源之后也不该凭空排上队");
        assertEquals(1, ledger.pendingRun(queue, 0));

        ledger.replacePendingIdentity(null, standIn);
        ledger.replacePendingIdentity(queued, null);
        assertEquals(1, ledger.pendingRun(queue, 0));
    }

    @Test
    @DisplayName("isPending 认换源配对：面板重建之前，被换掉的原曲行角标不熄灭")
    void isPendingFollowsTheCrossSourcePeer() {
        List<Music> queue = songs(6);
        PlayNextLedger ledger = new PlayNextLedger();
        Music first = song(995L);
        queue.add(ledger.insertIndex(queue, 0), first);
        ledger.remember(first);
        assertTrue(ledger.isPending(queue, 0, first));

        // 预备期换源：队列格换成替身、账上身份迁到替身；行 widget 还绑着原曲对象。
        Music standIn = song(996L);
        standIn.setSource(com.muoniumplayer.core.ncm.music.MusicPlatform.KUWO);
        queue.set(1, standIn);
        ledger.replacePendingIdentity(first, standIn);
        // 两侧立起同一把配对钥匙（CrossSourceSwitcher.propagateUnblockLabel 的录取动作）。
        CrossSourceSwitcher.propagateUnblockLabel(first, standIn);

        assertTrue(ledger.isPending(queue, 0, first),
                "面板行绑着原曲，配对键认不上时「下一首」角标会当场熄灭");
        assertTrue(ledger.isPending(queue, 0, standIn));
        // 陌生行不碰瓷：没参与换源的歌不许凭配对键蹭上待播角标。
        assertFalse(ledger.isPending(queue, 0, song(997L)));
    }

    private static List<Music> songs(int count) {
        List<Music> result = new ArrayList<>(count);
        for (int id = 1; id <= count; id++) {
            result.add(song(id));
        }
        return result;
    }

    /**
     * 一首能进 {@code HashSet} 的测试曲目。
     *
     * <p>身份由「来源 + 来源内 id」决定（就是 {@code Music#equals} 用的那对），所以这里只给
     * {@code id} 就够区分了：来源字段在没有显式赋值时默认是网易云。</p>
     */
    private static Music song(long id) {
        return new Music("测试曲 " + id, "测试曲 " + id, "", id,
                new ArrayList<Artist>(), Collections.<String>emptyList(),
                new Album(id, "测试专辑 " + id, "", Collections.<String>emptyList()),
                200_000L, 0L, 0L, Collections.<String>emptyList());
    }
}
