package com.muoniumplayer.core.music.source;

/**
 * QQ 账号信息。自 自原 music-api-0.1.1.jar 手翻，2026-11 吸收进仓（原包 top.fpsmaster.music，现为 muonium 私有部分）。
 */
public final class QQUserInfo {
    private final String musicid;
    private final String nickname;
    private final String avatarUrl;

    public QQUserInfo(String musicid, String nickname, String avatarUrl) {
        this.musicid = musicid;
        this.nickname = nickname;
        this.avatarUrl = avatarUrl;
    }

    public String getMusicid() { return musicid; }

    public String getNickname() { return nickname; }

    public String getAvatarUrl() { return avatarUrl; }
}
