package com.muoniumplayer.chat.state;

/** Immutable channel message for UI snapshots. */
public final class ChatMessage {
    public final String id;
    public final String channelId;
    public final String authorId;
    public final String authorName;
    public final String authorKind;
    public final String badge;
    public final String kind;
    public final String text;
    public final String createdAt;
    public final String replyTo;
    public final String shareType;
    public final String shareSummary;
    public final boolean deleted;

    public ChatMessage(String id, String channelId, String authorId, String authorName,
                       String authorKind, String badge, String kind, String text,
                       String createdAt, String replyTo, boolean deleted) {
        this(id, channelId, authorId, authorName, authorKind, badge, kind, text,
                createdAt, replyTo, "", "", deleted);
    }

    public ChatMessage(String id, String channelId, String authorId, String authorName,
                       String authorKind, String badge, String kind, String text,
                       String createdAt, String replyTo, String shareType, String shareSummary,
                       boolean deleted) {
        this.id = safe(id);
        this.channelId = safe(channelId);
        this.authorId = safe(authorId);
        this.authorName = safe(authorName);
        this.authorKind = safe(authorKind);
        this.badge = safe(badge);
        this.kind = safe(kind);
        this.text = safe(text);
        this.createdAt = safe(createdAt);
        this.replyTo = safe(replyTo);
        this.shareType = safe(shareType);
        this.shareSummary = safe(shareSummary);
        this.deleted = deleted;
    }

    public static ChatMessage system(String channelId, String text, String createdAt) {
        return new ChatMessage("", channelId, "", "MuoniumChat", "system", "guest", "system", text,
                createdAt, "", false);
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
