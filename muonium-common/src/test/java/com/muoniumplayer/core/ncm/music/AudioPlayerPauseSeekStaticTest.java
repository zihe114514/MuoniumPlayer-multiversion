package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 暂停态拖进度条、以及反复点播放键会叠音的回归锁。
 *
 * <p>三者是同一条链上的三个症状：{@code AudioSample.jump()} 的契约是「跳转并继续播放，
 * 没在播就开播」—— 暂停时拖条会直接出声，而播放意图（{@code playRequested}）没动，
 * 界面照旧画播放键；用户再点一下按钮，{@code AudioSample.play()} 又会在「已在播放」的
 * 采样上克隆出第二路读头（克隆对象无人持有，pause/close 都停不掉），每点一次叠一层。
 * 所以这里同时锁住「暂停仅 cue」与「播放先去重」两处防线。</p>
 */
class AudioPlayerPauseSeekStaticTest {

    private String player() throws Exception {
        return Files.readString(ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "ncm", "music", "AudioPlayer.java")));
    }

    @Test
    void pausedSeekCuesInsteadOfJumping() throws Exception {
        String seek = body(player(), "public void setPlaybackTime(float millis)");
        int branch = seek.indexOf("if (this.playRequested)");
        int jump = seek.indexOf("this.player.jump(");
        int cue = seek.indexOf("this.player.cue(");
        assertTrue(branch >= 0 && jump > branch && cue > jump,
                "播放中走 jump（继续出声），暂停中必须走 cue（只挪读头，保持静音）");
        assertFalse(seek.contains("isPlaying()"),
                "分支判据必须是播放意图而不是引擎瞬时状态：play() 排队到引擎开播之间有窗口，"
                        + "那里拖条必须继续播");
    }

    @Test
    void playDoesNotStackASecondVoice() throws Exception {
        String play = body(player(), "public void play()");
        int guard = play.indexOf("this.player.isPlaying()");
        int underlinePlay = play.indexOf("this.player.play()");
        assertTrue(guard >= 0 && underlinePlay > guard,
                "play() 必须先查引擎是否已在出声：已出声时只补意图，绝不再调一次 SoundFile.play()");
        assertTrue(play.substring(guard, underlinePlay).contains("playRequested = true"),
                "提前返回也要把播放意图置真，否则按钮又停在暂停图标上");

        String prepared = body(player(), "public void startPrepared()");
        int preparedGuard = prepared.indexOf("this.player.isPlaying()");
        int preparedPlay = prepared.indexOf("this.player.play()");
        assertTrue(preparedGuard >= 0 && preparedPlay > preparedGuard,
                "startPrepared() 与 play() 是同一条防线：预卷牌重复点也必须先去重");
    }

    /** 某个成员的方法体，按签名定位、按配对的花括号截断。 */
    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "源码里找不到: " + signature);
        int open = source.indexOf('{', start);
        assertTrue(open > start, "签名后面没有方法体: " + signature);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return source.substring(open, i + 1);
            }
        }
        throw new AssertionError("方法体没有闭合: " + signature);
    }
}
