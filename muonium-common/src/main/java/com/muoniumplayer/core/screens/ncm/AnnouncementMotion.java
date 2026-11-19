package com.muoniumplayer.core.screens.ncm;

/** 专栏切换先收旧内容再引入新内容；连点从当前透明度续接，不重播整张弹窗。 */
public final class AnnouncementMotion {
    private int visible;
    private int target;
    private int direction = 1;
    private double presence = 1;
    private long stamp = -1;

    public void select(int section) {
        if (section < 0 || section > 2 || section == target) return;
        direction = section > target ? 1 : -1;
        target = section;
    }

    /** 返回 true 表示旧内容已收完，调用方可以在排版前切换数据。 */
    public boolean tick(long now, double speed) {
        double millis = stamp < 0 ? 0 : Math.max(0, Math.min(100, (now - stamp) / 1_000_000.0));
        stamp = Math.max(stamp, now);
        double rate = Double.isFinite(speed) ? Math.max(.30, Math.min(3, speed)) : 1;
        if (visible != target) {
            presence = Math.max(0, presence - millis * rate / 330);
            if (presence == 0) {
                visible = target;
                return true;
            }
        } else {
            presence = Math.min(1, presence + millis * rate / 780);
        }
        return false;
    }

    public int visible() { return visible; }
    public int target() { return target; }
    public double alpha() {
        double value = presence * presence * (3 - 2 * presence);
        return presence < 1 ? Math.min(Math.nextDown(1.0), value) : 1;
    }
    public double offset() { return (1 - alpha()) * 26 * direction * (visible == target ? 1 : -1); }
    public boolean acceptsInput() { return visible == target && presence == 1; }
}
