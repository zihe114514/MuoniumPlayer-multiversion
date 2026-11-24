package com.muoniumplayer.chat.state;

/** Immutable player presence entry. */
public final class ChatUser {
    public final String accountId;
    public final String displayName;
    public final String kind;
    public final String badge;
    public final boolean guest;

    public ChatUser(String accountId, String displayName, String kind, String badge, boolean guest) {
        this.accountId = safe(accountId);
        this.displayName = safe(displayName);
        this.kind = safe(kind);
        this.badge = safe(badge);
        this.guest = guest;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
