package com.muoniumplayer.core.screens.ncm;

/** 只在 UI 线程使用：后提交的导航、换源或离开页面，会使旧异步结果失效。 */
final class NavigationRequestGate {
    private long revision;
    long begin() { return ++revision; }
    void invalidate() { ++revision; }
    boolean isCurrent(long ticket) { return revision == ticket; }
}
