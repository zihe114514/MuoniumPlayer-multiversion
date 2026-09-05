package com.muoniumplayer.party;

import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * 一次进行中／刚结束的投票（{@link MpProto#S_VOTE}，房间快照里也带）。
 *
 * <p>频道里没有房主，切歌和踢人由投票决定：切歌要过半，踢人要 80%。服务端把票数、门槛和
 * 倒计时都算好下发 —— 客户端不能自己算，否则同一房间里两个客户端会显示不同的「还需几票」。
 */
public final class MpVote {

    public final String voteId;
    /** {@link MpProto#VOTE_SKIP} / {@link MpProto#VOTE_KICK}。 */
    public final String kind;
    public final UUID initiator;
    public final String initiatorName;
    /** 踢人投票的对象；切歌投票为 null。 */
    public final UUID target;
    /** 投票主体的展示文本（要切的歌名 / 要踢的玩家名）。 */
    public final String subject;
    public final int agree;
    public final int cast;
    /** 有投票权的人数（踢人时不含被投的那个）。 */
    public final int eligible;
    /** 通过所需票数。 */
    public final int need;
    public final long endsAt;
    /** 剩余毫秒，服务端算好的。 */
    public final long remaining;
    public final boolean closed;
    public final boolean passed;
    public final String headline;

    private MpVote(String voteId, String kind, UUID initiator, String initiatorName, UUID target,
                   String subject, int agree, int cast, int eligible, int need, long endsAt,
                   long remaining, boolean closed, boolean passed, String headline) {
        this.voteId = voteId;
        this.kind = kind;
        this.initiator = initiator;
        this.initiatorName = initiatorName;
        this.target = target;
        this.subject = subject;
        this.agree = agree;
        this.cast = cast;
        this.eligible = eligible;
        this.need = need;
        this.endsAt = endsAt;
        this.remaining = remaining;
        this.closed = closed;
        this.passed = passed;
        this.headline = headline;
    }

    public static MpVote fromJson(JsonObject o) {
        if (o == null) return null;
        String id = MpProto.str(o, "voteId", "");
        if (id.isEmpty()) return null;
        UUID initiator = null;
        UUID target = null;
        try {
            initiator = UUID.fromString(MpProto.str(o, "initiator", ""));
        } catch (Throwable ignored) {
        }
        try {
            target = UUID.fromString(MpProto.str(o, "target", ""));
        } catch (Throwable ignored) {
        }
        return new MpVote(id,
                MpProto.str(o, "kind", MpProto.VOTE_SKIP),
                initiator,
                MpProto.str(o, "initiatorName", ""),
                target,
                MpProto.str(o, "subject", ""),
                (int) MpProto.num(o, "agree", 0L),
                (int) MpProto.num(o, "cast", 0L),
                (int) MpProto.num(o, "eligible", 0L),
                (int) MpProto.num(o, "need", 0L),
                MpProto.num(o, "endsAt", 0L),
                MpProto.num(o, "remaining", 0L),
                MpProto.bool(o, "closed", false),
                MpProto.bool(o, "passed", false),
                MpProto.str(o, "headline", ""));
    }

    public boolean skip() {
        return !MpProto.VOTE_KICK.equals(kind);
    }

    public String kindLabel() {
        return skip() ? "切歌投票" : "踢出投票";
    }

    /** 「3/5 票，还需 1 票」；已结束时给结果。 */
    public String tallyLabel() {
        if (closed) return passed ? "已通过" : "未通过";
        int left = need - agree;
        String base = agree + "/" + eligible + " 票（需 " + need + "）";
        return left > 0 ? base + " · 还差 " + left : base + " · 已够票";
    }

    /** 倒计时进度：1 = 刚开始，0 = 时间到。给进度条用。 */
    public double progress() {
        long window = remainingWindow();
        if (window <= 0L) return 0.0;
        double p = (double) remaining / (double) window;
        return p < 0.0 ? 0.0 : (p > 1.0 ? 1.0 : p);
    }

    /**
     * 投票窗口长度。服务端只给「剩余」，没给「总长」，所以按 hello 里下发的窗口取；
     * 拿不到就用需求写死的 30 秒兜底 —— 进度条画歪一点无所谓，别除以 0 就行。
     */
    private long remainingWindow() {
        long w = MpPartyClient.voteWindowMs();
        return w > 0L ? w : 30_000L;
    }

    public String remainingLabel() {
        long s = (remaining + 999L) / 1000L;
        if (s <= 0L) return "已结束";
        return s + " 秒";
    }
}
