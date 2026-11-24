package com.muoniumplayer.chat.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable player identity and effective permission snapshot from auth.ok/auth.update. */
public final class ChatIdentity {
    public final String accountId;
    public final String displayName;
    public final String kind;
    public final String badge;
    public final String writeState;
    public final String restrictedReason;
    public final long sessionTtlMs;
    public final long resumeWindowMs;
    public final long serverOffsetMs;
    public final boolean mojangVerified;
    public final Permissions permissions;
    public final Limits limits;
    public final List<Channel> channels;

    public ChatIdentity(String accountId, String displayName, String kind, String badge,
                        String writeState, String restrictedReason, long sessionTtlMs,
                        long resumeWindowMs, long serverOffsetMs, boolean mojangVerified,
                        Permissions permissions, Limits limits, List<Channel> channels) {
        this.accountId = clean(accountId);
        this.displayName = clean(displayName);
        this.kind = clean(kind);
        this.badge = clean(badge);
        this.writeState = clean(writeState);
        this.restrictedReason = clean(restrictedReason);
        this.sessionTtlMs = Math.max(0L, sessionTtlMs);
        this.resumeWindowMs = Math.max(0L, resumeWindowMs);
        this.serverOffsetMs = serverOffsetMs;
        this.mojangVerified = mojangVerified;
        this.permissions = permissions == null ? new Permissions() : permissions;
        this.limits = limits == null ? new Limits() : limits;
        this.channels = Collections.unmodifiableList(new ArrayList<>(channels == null ? List.of() : channels));
    }

    public boolean canSpeak() {
        return this.writeState != null && this.writeState.equals("full") && this.permissions.speak;
    }

    public static final class Permissions {
        public final boolean browseChannels;
        public final boolean readHistory;
        public final boolean viewOnline;
        public final boolean joinPrivate;
        public final boolean speak;
        public final boolean action;
        public final boolean dm;
        public final boolean links;
        public final boolean shareTrack;
        public final boolean sharePlaylist;
        public final boolean sharePreset;
        public final boolean browsePresets;
        public final boolean likePresets;

        public Permissions() { this(false, false, false, false, false, false, false, false, false, false, false, false, false); }

        public Permissions(boolean browseChannels, boolean readHistory, boolean viewOnline, boolean joinPrivate,
                           boolean speak, boolean action, boolean dm, boolean links, boolean shareTrack,
                           boolean sharePlaylist, boolean sharePreset, boolean browsePresets, boolean likePresets) {
            this.browseChannels = browseChannels;
            this.readHistory = readHistory;
            this.viewOnline = viewOnline;
            this.joinPrivate = joinPrivate;
            this.speak = speak;
            this.action = action;
            this.dm = dm;
            this.links = links;
            this.shareTrack = shareTrack;
            this.sharePlaylist = sharePlaylist;
            this.sharePreset = sharePreset;
            this.browsePresets = browsePresets;
            this.likePresets = likePresets;
        }
    }

    public static final class Limits {
        public final int maxTextChars;
        public final int minIntervalMs;
        public final int messagesPerMinute;
        public final int messagesPerHour;
        public final int messagesPerDay;

        public Limits() { this(0, 0, 0, 0, 0); }

        public Limits(int maxTextChars, int minIntervalMs, int messagesPerMinute,
                      int messagesPerHour, int messagesPerDay) {
            this.maxTextChars = bounded(maxTextChars, 0, 4096);
            this.minIntervalMs = bounded(minIntervalMs, 0, 86_400_000);
            this.messagesPerMinute = bounded(messagesPerMinute, 0, 1_000_000);
            this.messagesPerHour = bounded(messagesPerHour, 0, 1_000_000);
            this.messagesPerDay = bounded(messagesPerDay, 0, 1_000_000);
        }
    }

    public static final class Channel {
        public final String id;
        public final String name;
        public final String type;
        public final String topic;
        public final int memberCount;
        public final int guestCount;
        public final boolean hasPassword;
        public final boolean archived;
        public final boolean guestReadable;

        public Channel(String id, String name, String type, String topic, int memberCount,
                       int guestCount, boolean hasPassword, boolean archived, boolean guestReadable) {
            this.id = clean(id);
            this.name = clean(name);
            this.type = clean(type);
            this.topic = clean(topic);
            this.memberCount = Math.max(0, memberCount);
            this.guestCount = Math.max(0, guestCount);
            this.hasPassword = hasPassword;
            this.archived = archived;
            this.guestReadable = guestReadable;
        }
    }

    private static int bounded(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
