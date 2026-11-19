package com.muoniumplayer.core.screens.ncm;

/** 筛选先收旧内容，再提交新条件；快速输入只保留最后一次请求，退场由宿主时钟接管。 */
final class FontPickerMotion {
    private String query = "", requestedQuery = "";
    private int section, requestedSection;
    private long stamp;
    private double progress = 1;
    private boolean leaving;

    void request(String query, int section) {
        if (requestedQuery.equals(query) && requestedSection == section) return;
        requestedQuery = query;
        requestedSection = section;
        leaving = true;
    }

    boolean tick(long now, double speed) {
        double millis = stamp == 0 ? 0 : Math.max(0, Math.min(100, (now - stamp) / 1_000_000.0));
        stamp = now;
        double duration = (leaving ? 240 : 510) / Math.max(.30, Math.min(3, speed));
        progress = Math.max(0, Math.min(1, progress + (leaving ? -1 : 1) * millis / duration));
        if (!leaving || progress > 0) return false;
        query = requestedQuery;
        section = requestedSection;
        leaving = false;
        return true;
    }

    String query() { return query; }
    int section() { return section; }
    int targetSection() { return requestedSection; }
    double alpha() { return progress * progress * (3 - 2 * progress); }
    double offset() { return (1 - alpha()) * (leaving ? -6 : 10); }
    boolean acceptsInput() { return !leaving && progress >= .65; }
}
