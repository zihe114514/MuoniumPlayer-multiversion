package yun.shan.mpsync.room;

import java.util.Set;

/**
 * 频道的空闲歌单：队列快空时由 {@link Room} 向它要下一首。
 *
 * <p>做成注入的接口而不是让 Room 直接持有 {@link IdlePlaylist}：Room 只关心「有没有下一首、
 * 这首放没放成」，歌单怎么存、怎么随机、失败怎么冷却都在实现那一侧，单元测试也能塞一个假的。</p>
 */
public interface IdleSource {

    /** 现在能不能补歌：已开启、歌单非空、没在因连续失败而暂停。 */
    boolean active();

    /**
     * 下一首（不带直链的新 Track 实例）。
     *
     * @param exclude 已经在队列里 / 正在放的曲目 key，跳过它们
     * @return null = 一首都给不出（全在冷却、全被排除、或全部校验不过）
     */
    Track next(Set<String> exclude);

    /** 这首空闲歌没能解析出链接、被跳过了。 */
    void failed(String key);

    /** 这首空闲歌完整放完了。 */
    void played(String key);

    /** 进 {@link QueueEntry#playlist} 的歌单名，客户端据此标出「这首是服务器歌单」。 */
    String label();
}
