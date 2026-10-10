package com.muoniumplayer.core.ncm.music;

/**
 * 侧栏刷新按钮那一次「按当前平台强制重拉账号歌单」的结果。
 *
 * <p>与 {@link CloudMusic.NeteaseRefreshResult} 同形，但归属所有平台：QQ / 酷狗 / B站 /
 * 汽水的强刷入口都用它报告，侧栏按钮的收尾逻辑只认这一种形状。</p>
 */
public final class PlaylistRefreshResult {
    private final boolean success;
    private final int playlistCount;
    private final long elapsedMillis;
    private final String message;

    private PlaylistRefreshResult(boolean success, long elapsedMillis, int playlistCount, String message) {
        this.success = success;
        this.elapsedMillis = Math.max(0L, elapsedMillis);
        this.playlistCount = Math.max(0, playlistCount);
        this.message = message == null || message.trim().isEmpty() ? "刷新失败" : message.trim();
    }

    public static PlaylistRefreshResult success(long elapsedMillis, int playlistCount) {
        return new PlaylistRefreshResult(true, elapsedMillis, playlistCount, "刷新成功");
    }

    public static PlaylistRefreshResult failure(long elapsedMillis, String message) {
        return new PlaylistRefreshResult(false, elapsedMillis, 0, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public int getPlaylistCount() {
        return playlistCount;
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }

    public String getMessage() {
        return message;
    }
}
