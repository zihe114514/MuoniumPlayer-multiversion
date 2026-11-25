/*
 * This file is part of OneConfig.
 * OneConfig - Next Generation Config Library for Minecraft: Java Edition
 * Copyright (C) 2021~2024 Polyfrost.
 *   <https://polyfrost.org> <https://github.com/Polyfrost/>
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 *   OneConfig is licensed under the terms of version 3 of the GNU Lesser
 * General Public License as published by the Free Software Foundation, AND
 * under the Additional Terms Applicable to OneConfig, as published by Polyfrost,
 * either version 1.0 of the Additional Terms, or (at your option) any later
 * version.
 *
 *   This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 *   You should have received a copy of the GNU Lesser General Public
 * License.  If not, see <https://www.gnu.org/licenses/>. You should
 * have also received a copy of the Additional Terms Applicable
 * to OneConfig, as published by Polyfrost. If not, see
 * <https://polyfrost.org/legal/oneconfig/additional-terms>
 */

package com.muoniumplayer.configskija.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/** Neutral notification history and live set. Snapshots can safely be consumed on the UI thread. */
public final class Notifications {
    public static final int MAX_HISTORY = 50;
    public static final double DEFAULT_DURATION = 4000;
    private static final AtomicLong IDS = new AtomicLong();
    private static final Notifications SHARED = new Notifications();
    private final List<Notice> active = new ArrayList<>();
    private final List<Notice> history = new ArrayList<>();
    private final LongSupplier clock;

    public enum Type {
        INFO("info", 0xFFDFEAFF), SUCCESS("check-circle", 0xFF3CAF77),
        ERROR("alert-circle", 0xFFF2545A), PROGRESS("refresh", 0xFF2B4BFF);
        private final String icon;
        private final int accent;
        Type(String icon, int accent) { this.icon = icon; this.accent = accent; }
        public String icon() { return icon; }
        public int accent() { return accent; }
    }
    public record ImageIcon(byte[] encoded, int width, int height) {
        public ImageIcon {
            encoded = Objects.requireNonNull(encoded).clone();
            if (width <= 0 || height <= 0) throw new IllegalArgumentException("Positive icon dimensions required");
        }
        @Override public byte[] encoded() { return encoded.clone(); }
    }
    public record Action(String label, boolean primary, Runnable onClick) {
        public Action { Objects.requireNonNull(label); Objects.requireNonNull(onClick); }
        public Action(String label, Runnable onClick) { this(label, false, onClick); }
    }
    public static final class Notice {
        private final long id = IDS.incrementAndGet();
        private final long createdAt;
        private final String title, message, icon;
        private final Type type;
        private final ImageIcon image;
        private final double duration;
        private final DoubleSupplier progress;
        private final List<Action> actions;
        private final Runnable onClick;
        private volatile boolean read, hovered, dismissRequested;
        private Notice(Builder builder) {
            createdAt = builder.owner.clock.getAsLong();
            title = builder.title; message = builder.message; type = builder.type; icon = builder.icon; image = builder.image;
            duration = builder.duration; progress = builder.progress;
            actions = List.copyOf(builder.actions); onClick = builder.onClick;
        }
        public long id() { return id; }
        public long createdAt() { return createdAt; }
        public String title() { return title; }
        public String message() { return message; }
        public Type type() { return type; }
        /** Optional host resource path; the center uses the source type icon regardless of this value. */
        public String icon() { return icon; }
        public ImageIcon image() { return image; }
        public double duration() { return duration; }
        public List<Action> actions() { return actions; }
        public Runnable onClick() { return onClick; }
        public boolean read() { return read; }
        public boolean hovered() { return hovered; }
        public void hovered(boolean value) { hovered = value; }
        public boolean dismissRequested() { return dismissRequested; }
        public void requestDismiss() { dismissRequested = true; }
        public boolean hasProgress() { return progress != null; }
        public boolean persistent() { return duration <= 0 && progress == null; }
        public Double progressOrNull() {
            if (progress == null) return null;
            try {
                double value = progress.getAsDouble();
                return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : null;
            } catch (RuntimeException failedSupplier) { return null; }
        }
    }
    public static final class Builder {
        private final Notifications owner;
        private final String title, message;
        private Type type = Type.INFO;
        private String icon;
        private ImageIcon image;
        private double duration = DEFAULT_DURATION;
        private DoubleSupplier progress;
        private final List<Action> actions = new ArrayList<>();
        private Runnable onClick;
        private Builder(Notifications owner, String title, String message) {
            this.owner = owner; this.title = Objects.requireNonNull(title); this.message = Objects.requireNonNull(message);
        }
        public Builder type(Type type) { this.type = Objects.requireNonNull(type); return this; }
        public Builder icon(String resourcePath) { icon = resourcePath; return this; }
        public Builder image(byte[] encoded, int width, int height) { image = new ImageIcon(encoded, width, height); return this; }
        public Builder duration(double milliseconds) {
            if (!Double.isFinite(milliseconds)) throw new IllegalArgumentException("Finite duration required");
            duration = milliseconds; return this;
        }
        public Builder persistent() { duration = -1; return this; }
        public Builder progress(DoubleSupplier supplier) { progress = supplier; if (supplier != null) type = Type.PROGRESS; return this; }
        public Builder onClick(Runnable callback) { onClick = callback; return this; }
        public Builder action(Action action) { actions.add(Objects.requireNonNull(action)); return this; }
        public Builder action(String label, Runnable callback) { return action(new Action(label, callback)); }
        public Notice send() { Notice notice = new Notice(this); owner.push(notice); return notice; }
    }
    public Notifications() { this(System::currentTimeMillis); }
    public Notifications(LongSupplier clock) { this.clock = Objects.requireNonNull(clock); }
    /** Use one model for the bell, center, toasts and asynchronous host events. */
    public static Notifications shared() { return SHARED; }
    public Builder builder(String title, String message) { return new Builder(this, title, message); }
    public Notice send(String title, String message) { return builder(title, message).send(); }
    public Notice info(String title, String message) { return send(title, message); }
    public Notice success(String title, String message) { return builder(title, message).type(Type.SUCCESS).send(); }
    public Notice error(String title, String message) { return builder(title, message).type(Type.ERROR).send(); }
    public Notice progress(String title, String message, DoubleSupplier progress) { return builder(title, message).persistent().progress(progress).send(); }
    public synchronized void push(Notice notice) {
        Objects.requireNonNull(notice);
        active.add(notice); history.add(0, notice);
        while (history.size() > MAX_HISTORY) history.remove(history.size() - 1);
    }
    /** Live toasts: oldest first. History: newest first, with no date grouping. */
    public synchronized List<Notice> active() { return List.copyOf(active); }
    public synchronized List<Notice> history() { return List.copyOf(history); }
    public synchronized int count() { return active.size(); }
    public synchronized int unreadCount() { return (int)history.stream().filter(notice -> !notice.read).count(); }
    public synchronized boolean contains(Notice notice) { return active.contains(notice); }
    public synchronized void markAllRead() { for (Notice notice : history) notice.read = true; }
    public synchronized void clearHistory() { history.clear(); }
    public synchronized void removeFromHistory(Notice notice) { history.remove(notice); }
    public synchronized void remove(Notice notice) { notice.dismissRequested = true; history.remove(notice); }
    public synchronized void dismiss(Notice notice) { active.remove(notice); }
    public synchronized void dismiss(long id) { active.removeIf(notice -> notice.id == id); }
    /** Matches the source API: clear live toasts only; history is retained. */
    public synchronized void clearAll() { active.clear(); }
}
