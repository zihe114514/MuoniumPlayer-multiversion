package com.muoniumplayer.core.screens.ncm.panels;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 只驱动共享动作的请求/确认协调，不启动 UI、客户端队列或网络。 */
class LikeButtonPendingTest {
    @Test
    void listAndIslandShareTheSamePendingIdentity() {
        LikeButton.PendingLikes state = new LikeButton.PendingLikes();
        String listKey = "netease_42";
        String islandKey = new String(listKey);
        Object request = state.begin(listKey, null);

        assertNotNull(request);
        assertTrue(state.isPending(islandKey));
        assertNull(state.begin(islandKey, null), "另一入口不能发相反请求");
        assertNull(state.confirmation(islandKey), "乐观红心不能在请求完成前再发起取消确认");
        assertNotNull(state.begin("kugou_42", null), "另一平台的歌曲独立处理");

        state.finish(islandKey, request);
        assertFalse(state.isPending(listKey));
        assertNotNull(state.begin(listKey, null), "请求处理完才允许重试");
    }

    @Test
    void anAbandonedConfirmationDoesNotHoldPending() {
        LikeButton.PendingLikes state = new LikeButton.PendingLikes();
        Object abandoned = state.confirmation("netease_42");
        Object current = state.confirmation("netease_42");

        assertNotNull(abandoned);
        assertNotNull(current);
        assertFalse(state.isPending("netease_42"));
        assertNotNull(state.begin("netease_42", current), "关掉前一个确认框不应锁死歌曲");
        assertNull(state.begin("netease_42", abandoned));
    }

    @Test
    void failedUnlikeCannotReviveTheOtherHostsOldConfirmation() {
        LikeButton.PendingLikes state = new LikeButton.PendingLikes();
        Object listConfirmation = state.confirmation("netease_42");
        Object islandConfirmation = state.confirmation("netease_42");
        Object request = state.begin("netease_42", islandConfirmation);
        assertNotNull(request);

        // 失败回滚后红心重新亮起，旧回调不能仅凭 isLiked=true 再取消一次。
        state.finish("netease_42", request);
        assertNull(state.begin("netease_42", listConfirmation));
        assertNull(state.begin("netease_42", islandConfirmation), "确认回调只能消费一次");
        assertFalse(state.isPending("netease_42"));
        assertNotNull(state.begin("netease_42", state.confirmation("netease_42")));
    }

    @Test
    void unlikeThenRelikeStillInvalidatesTheOldConfirmation() {
        LikeButton.PendingLikes state = new LikeButton.PendingLikes();
        Object oldConfirmation = state.confirmation("netease_42");
        Object unlike = state.begin("netease_42", state.confirmation("netease_42"));
        assertNotNull(unlike);
        state.finish("netease_42", unlike);
        Object relike = state.begin("netease_42", null);
        assertNotNull(relike);
        state.finish("netease_42", relike);

        assertNull(state.begin("netease_42", oldConfirmation),
                "中间一轮操作后即使红心同为 true，也不能沿用旧确认");
    }

    @Test
    void staleCompletionCannotReleaseANewerRequest() {
        LikeButton.PendingLikes state = new LikeButton.PendingLikes();
        Object oldRequest = state.begin("netease_42", null);
        state.finish("netease_42", oldRequest);
        Object newRequest = state.begin("netease_42", null);

        state.finish("netease_42", oldRequest);
        assertTrue(state.isPending("netease_42"));
        assertTrue(state.owns("netease_42", newRequest));
        assertFalse(state.owns("netease_42", oldRequest), "旧结果不能获得回滚权");
        state.finish("netease_42", newRequest);
        assertFalse(state.isPending("netease_42"));
    }

    @Test
    void confirmationCannotBeUsedForAnotherTrack() {
        LikeButton.PendingLikes state = new LikeButton.PendingLikes();
        Object ticket = state.confirmation("netease_42");

        assertNull(state.begin("netease_43", ticket));
        assertFalse(state.isPending("netease_43"));
        Object other = state.begin("netease_43", null);
        assertNotNull(other);
        state.finish("netease_43", other);
        assertNotNull(state.begin("netease_42", ticket), "其他歌曲的操作不使本曲确认失效");
    }
}
