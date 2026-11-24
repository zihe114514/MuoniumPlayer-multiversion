package com.muoniumplayer.chat.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Snapshot published to the UI thread after every state change. */
public final class ChatSnapshot {
    public final String endpoint;
    public final String status;
    public final String error;
    public final String accountId;
    public final String displayName;
    public final String accountKind;
    public final String writeState;
    public final String restrictedReason;
    public final String activeChannelId;
    public final List<ChatIdentity.Channel> channels;
    public final List<ChatMessage> messages;
    public final List<ChatMessage> directMessages;
    public final String directMessageTargetId;
    public final String directMessageTargetName;
    public final List<ChatUser> users;
    public final ChatIdentity.Permissions permissions;
    public final ChatIdentity.Limits limits;
    public final int totalOnline;
    public final int totalGuests;

    public ChatSnapshot(String endpoint, String status, String error, String accountId, String displayName,
                        String accountKind, String writeState, String restrictedReason, String activeChannelId,
                        List<ChatIdentity.Channel> channels, List<ChatMessage> messages,
                        List<ChatMessage> directMessages, String directMessageTargetId,
                        String directMessageTargetName, List<ChatUser> users,
                        ChatIdentity.Permissions permissions, ChatIdentity.Limits limits,
                        int totalOnline, int totalGuests) {
        this.endpoint = safe(endpoint);
        this.status = safe(status);
        this.error = safe(error);
        this.accountId = safe(accountId);
        this.displayName = safe(displayName);
        this.accountKind = safe(accountKind);
        this.writeState = safe(writeState);
        this.restrictedReason = safe(restrictedReason);
        this.activeChannelId = safe(activeChannelId);
        this.channels = immutable(channels);
        this.messages = immutable(messages);
        this.directMessages = immutable(directMessages);
        this.directMessageTargetId = safe(directMessageTargetId);
        this.directMessageTargetName = safe(directMessageTargetName);
        this.users = immutable(users);
        this.permissions = permissions == null ? new ChatIdentity.Permissions() : permissions;
        this.limits = limits == null ? new ChatIdentity.Limits() : limits;
        this.totalOnline = Math.max(0, totalOnline);
        this.totalGuests = Math.max(0, totalGuests);
    }

    private static String safe(String text) { return text == null ? "" : text; }
    private static <T> List<T> immutable(List<T> list) {
        return Collections.unmodifiableList(new ArrayList<>(list == null ? List.of() : list));
    }
}
