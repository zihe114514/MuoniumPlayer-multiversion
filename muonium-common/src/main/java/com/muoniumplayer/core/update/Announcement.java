package com.muoniumplayer.core.update;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable remote content card. Only announcement ids participate in read tracking;
 * sponsorships and communities use the same data shape with their own id scopes.
 */
public final class Announcement {

    public static final int MAX_ID_CHARS = 256;
    public static final int MAX_TITLE_CHARS = 256;
    public static final int MAX_BODY_CHARS = 16384;
    public static final int MAX_URL_CHARS = 2048;
    public static final int MAX_ACTIONS = 3;

    /** A descriptive HTTPS action; trust is checked by the shared page policy before use. */
    public static final class Action {
        private final String text;
        private final String url;

        public Action(String text, String url) {
            this.text = bounded(text, MAX_TITLE_CHARS);
            this.url = boundedUrl(url);
        }

        public String text() {
            return this.text;
        }

        public String url() {
            return this.url;
        }
    }

    private final String id;
    private final String date;
    private final String kind;
    private final String title;
    private final String body;
    private final String link;
    private final String linkText;
    private final String image;
    private final String imageAlt;
    private final String imageFit;
    private final String badge;
    private final List<Action> actions;

    /** Legacy source/binary contract: existing callers need not know about rich cards. */
    public Announcement(String id, String date, String kind, String title,
                        String body, String link, String linkText) {
        this(id, date, kind, title, body, link, linkText, "", "", "contain", "",
                Collections.<Action>emptyList());
    }

    public Announcement(String id, String date, String kind, String title,
                        String body, String link, String linkText, String image,
                        String imageAlt, String imageFit, String badge, List<Action> actions) {
        this.id = trim(id);
        this.date = bounded(date, 64);
        this.kind = bounded(kind, 64);
        this.title = bounded(title, MAX_TITLE_CHARS);
        this.body = bounded(body, MAX_BODY_CHARS);
        this.link = boundedUrl(link);
        this.linkText = bounded(linkText, MAX_TITLE_CHARS);
        this.image = boundedUrl(image);
        this.imageAlt = bounded(imageAlt, MAX_TITLE_CHARS);
        // Unknown/missing modes must not crop QR codes or other informational images.
        this.imageFit = "cover".equalsIgnoreCase(trim(imageFit)) ? "cover" : "contain";
        this.badge = bounded(badge, 64);
        List<Action> copy = new ArrayList<Action>();
        if (actions != null) {
            for (Action action : actions) {
                if (action != null && !action.url().isEmpty()) {
                    copy.add(action);
                    if (copy.size() == MAX_ACTIONS) {
                        break;
                    }
                }
            }
        }
        this.actions = Collections.unmodifiableList(copy);
    }

    /** Stable read-tracking key. Change it when an announcement should be unread again. */
    public String id() {
        return this.id;
    }

    public String date() {
        return this.date;
    }

    /** Unknown kinds remain displayable; they do not change click or popup behavior. */
    public String kind() {
        return this.kind;
    }

    public String title() {
        return this.title;
    }

    /** Newlines remain paragraph separators. */
    public String body() {
        return this.body;
    }

    /** Legacy primary link, independent of the additional action list. */
    public String link() {
        return this.link;
    }

    public String linkText() {
        return this.linkText;
    }

    public String image() {
        return this.image;
    }

    public String imageAlt() {
        return this.imageAlt;
    }

    /** Either contain (default, no cropping) or cover (explicit opt-in). */
    public String imageFit() {
        return this.imageFit;
    }

    public String badge() {
        return this.badge;
    }

    /** Immutable, never null, at most MAX_ACTIONS additional actions. */
    public List<Action> actions() {
        return this.actions;
    }

    private static String boundedUrl(String value) {
        String trimmed = trim(value);
        // Truncating a URL could change its target; discard it instead.
        return trimmed.length() > MAX_URL_CHARS ? "" : trimmed;
    }

    private static String bounded(String value, int limit) {
        String trimmed = trim(value);
        if (trimmed.length() <= limit) {
            return trimmed;
        }
        int end = limit;
        if (Character.isHighSurrogate(trimmed.charAt(end - 1))) {
            end--;
        }
        return trimmed.substring(0, end);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
