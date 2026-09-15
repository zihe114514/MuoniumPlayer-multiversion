package com.muoniumplayer.core.ncm.music;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 酷狗「我喜欢」的本地乐观状态。
 *
 * <p>网易云那边有一个全量 id 表（{@code CloudMusic.likeList}），画收藏图标时可以直接查。
 * 酷狗没有对应的轻量接口：判断一首歌是否已在「我喜欢」里<b>只能</b>把整张歌单拉下来比对，
 * 而收藏图标是每一行都要画的，为它逐行触发网络请求显然不行。</p>
 *
 * <p>所以这里维护一个会话内的集合：用户点了收藏就记上，取消就删掉，接口报错时由调用方回滚。
 * 代价是<b>冷启动时看不到历史收藏</b>——第一眼所有歌都是未收藏态，直到用户自己点过一次。
 * 这是有意的取舍：宁可图标初始不准，也不要为了一个装饰性状态把播放列表页拖成 N 次请求。</p>
 *
 * <p>{@link #replace} 给「整张我喜欢歌单已经加载出来」的场景用，那时可以一次性校正全量状态。</p>
 */
public final class KuGouLikedCache {

    /** 上限只是为了防止长时间运行时无界增长；它是最近使用集合，满了就整体清掉。 */
    private static final int MAX_ENTRIES = 4096;

    private static final Set<String> LIKED = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private KuGouLikedCache() {
    }

    public static boolean contains(String stableKey) {
        return stableKey != null && LIKED.contains(stableKey);
    }

    public static void add(String stableKey) {
        if (stableKey == null) return;
        if (LIKED.size() >= MAX_ENTRIES) LIKED.clear();
        LIKED.add(stableKey);
    }

    public static void remove(String stableKey) {
        if (stableKey != null) LIKED.remove(stableKey);
    }

    /**
     * 用一份「完整」的已收藏集合整体替换当前状态。
     *
     * <p>只有在确实拿到了整张「我喜欢」歌单时才该调用——否则会把用户刚点的那几首抹掉。</p>
     */
    public static void replace(java.util.Collection<String> stableKeys) {
        LIKED.clear();
        if (stableKeys == null) return;
        for (String key : stableKeys) {
            if (key != null && LIKED.size() < MAX_ENTRIES) LIKED.add(key);
        }
    }

    public static void clear() {
        LIKED.clear();
    }
}
