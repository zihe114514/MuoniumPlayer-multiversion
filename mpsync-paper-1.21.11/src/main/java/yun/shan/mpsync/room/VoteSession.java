package yun.shan.mpsync.room;

import com.google.gson.JsonObject;
import yun.shan.mpsync.net.Proto;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一次投票：频道里的「投票切歌」和「投票踢人」共用这一套。
 *
 * <p><b>为什么门槛写成「严格大于」。</b>需求是「50% 以上通过」「超过 80% 才踢」——
 * 5 个人里 3 票才算过半（不是 2.5 向上取整的 3 恰好相等的边界问题），所以判定用
 * {@code 同意数 > 有效人数 × 比例}，而不是 {@code >=}。10 个人踢人要 9 票，正好是
 * 「超过 80%」的字面意思。
 *
 * <p><b>为什么不算弃权。</b>只统计明确投了「同意」的人数，对分母（在场人数）取比例。
 * 没投等于不同意 —— 30 秒窗口里沉默的人更可能是没看到，把沉默算成同意会让一个人就能切歌。
 *
 * <p>发起人自动算一张同意票：他按下按钮这个动作本身就是表态，还要他再点一次「同意」很蠢。
 */
public final class VoteSession {

    private static final AtomicLong SEQ = new AtomicLong(1L);

    public enum Kind {
        /** 切歌。 */
        SKIP,
        /** 踢人。 */
        KICK;

        public String id() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    public final long id = SEQ.getAndIncrement();
    public final Kind kind;
    public final UUID initiator;
    public final String initiatorName;
    /** 踢人投票的目标；切歌投票为 null。 */
    public final UUID target;
    /** 展示用：踢人是玩家名，切歌是歌名。 */
    public final String subject;
    public final long startedAt = System.currentTimeMillis();
    public final long endsAt;
    /** 通过所需比例（0.5 / 0.8）。 */
    public final double ratio;

    private final Map<UUID, Boolean> votes = new LinkedHashMap<>();
    private boolean closed;
    private boolean passed;

    public VoteSession(Kind kind, UUID initiator, String initiatorName, UUID target,
                       String subject, long windowMs, double ratio) {
        this.kind = kind;
        this.initiator = initiator;
        this.initiatorName = initiatorName == null ? "" : initiatorName;
        this.target = target;
        this.subject = subject == null ? "" : subject;
        this.endsAt = startedAt + Math.max(5_000L, windowMs);
        this.ratio = ratio <= 0d || ratio >= 1d ? 0.5d : ratio;
        if (initiator != null) votes.put(initiator, Boolean.TRUE);
    }

    /** @return false 表示这个人已经投过了（不允许改票，否则最后一秒反复改票没法结算） */
    public boolean cast(UUID uuid, boolean agree) {
        if (uuid == null || closed) return false;
        if (votes.containsKey(uuid)) return false;
        votes.put(uuid, agree);
        return true;
    }

    public boolean hasVoted(UUID uuid) { return uuid != null && votes.containsKey(uuid); }

    public int agrees() {
        int n = 0;
        for (Boolean v : votes.values()) if (Boolean.TRUE.equals(v)) n++;
        return n;
    }

    public int cast() { return votes.size(); }

    /** 通过所需的最少同意票数：{@code floor(人数 × 比例) + 1}，即「严格超过这个比例」。 */
    public int needed(int eligible) {
        if (eligible <= 0) return 1;
        return (int) Math.floor(eligible * ratio) + 1;
    }

    /** 票数已经够了（可以提前结算，不必等满 30 秒）。 */
    public boolean decided(int eligible) {
        return agrees() >= needed(eligible);
    }

    /**
     * 已经不可能通过了：把还没投的人全算成同意也够不到门槛。
     *
     * <p>为什么要单独判这一条：否则一个注定失败的投票会把频道挂在那儿等满整个窗口 ——
     * 30 秒里谁都不能再发起投票，也不能切歌，体验上就是「卡住了」。
     */
    public boolean hopeless(int eligible) {
        int remaining = Math.max(0, eligible - cast());
        return agrees() + remaining < needed(eligible);
    }

    public boolean expired(long now) { return now >= endsAt; }

    public boolean closed() { return closed; }

    public boolean passedResult() { return passed; }

    /** 结算。返回是否通过。 */
    public boolean close(int eligible) {
        if (closed) return passed;
        closed = true;
        passed = agrees() >= needed(eligible);
        return passed;
    }

    public long remainingMs() {
        return Math.max(0L, endsAt - System.currentTimeMillis());
    }

    /** 一行中文描述，用于聊天广播。 */
    public String headline() {
        return kind == Kind.SKIP
                ? initiatorName + " 想要切换歌曲《" + subject + "》"
                : initiatorName + " 想要踢出玩家 " + subject;
    }

    public JsonObject toJson(int eligible) {
        JsonObject o = new JsonObject();
        o.addProperty("voteId", id);
        o.addProperty("kind", kind.id());
        o.addProperty("initiator", initiator == null ? "" : initiator.toString());
        o.addProperty("initiatorName", initiatorName);
        o.addProperty("target", target == null ? "" : target.toString());
        o.addProperty("subject", subject);
        o.addProperty("agree", agrees());
        o.addProperty("cast", cast());
        o.addProperty("eligible", eligible);
        o.addProperty("need", needed(eligible));
        o.addProperty("ratio", ratio);
        o.addProperty("endsAt", endsAt);
        o.addProperty("remaining", remainingMs());
        o.addProperty("closed", closed);
        o.addProperty("passed", passed);
        o.addProperty("headline", headline());
        return o;
    }

    /** 单独一帧（{@link Proto#S_VOTE}）。 */
    public JsonObject frame(int eligible) {
        JsonObject m = Proto.msg(Proto.S_VOTE);
        m.add("vote", toJson(eligible));
        return m;
    }
}