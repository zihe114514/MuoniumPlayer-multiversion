package com.muoniumplayer.core.music.source;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** QQ 音乐账号中的电台分组与电台条目。 */
public final class QQRadioGroup {
    private final int id;
    private final String title;
    private final List<Radio> radios;

    public QQRadioGroup(int id, String title, List<Radio> radios) {
        this.id = id;
        this.title = title == null ? "" : title;
        this.radios = Collections.unmodifiableList(new ArrayList<>(radios == null
                ? Collections.<Radio>emptyList() : radios));
    }

    public int getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public List<Radio> getRadios() {
        return radios;
    }

    public static final class Radio {
        private final int id;
        private final String title;
        private final String coverUrl;
        private final long listenCount;

        public Radio(int id, String title, String coverUrl, long listenCount) {
            this.id = id;
            this.title = title == null ? "" : title;
            this.coverUrl = coverUrl == null ? "" : coverUrl;
            this.listenCount = Math.max(0L, listenCount);
        }

        public int getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public String getCoverUrl() {
            return coverUrl;
        }

        public long getListenCount() {
            return listenCount;
        }
    }
}
