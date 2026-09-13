package com.muoniumplayer.core.screens.ncm;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 洛雪 {@code [awlrc:...]} 容器解包的离线探针。
 *
 * <p>容器是本项目能拿到的<b>唯一一种本地逐字歌词</b>，而且它的失败方式是静默的：解不出来就退回
 * 正文，用户只会看到"本地歌词没有逐字效果"，不会报错。所以断言集中在「哪些键必须被认出来」
 * 「哪些必须被丢掉」「什么时候必须返回 null 让调用方回退」三件事上。</p>
 */
class AwlrcContainerProbeTest {

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void extractsEveryRecognisedTrack() {
        String awlrc = "[00:01.500]<0,500>Hel<500,500>lo";
        String content = "[00:01.500]Hello\n\n[awlrc:lrc:" + b64("[00:01.500]Hello")
                + ",tlrc:" + b64("[00:01.500]你好")
                + ",rlrc:" + b64("[00:01.500]hello")
                + ",awlrc:" + b64(awlrc) + "]";

        AwlrcContainer.Tracks tracks = AwlrcContainer.extract(content);

        assertNotNull(tracks);
        assertEquals("[00:01.500]Hello", tracks.lrc);
        assertEquals("[00:01.500]你好", tracks.tlrc);
        // 纯拉丁罗马音必须原样留着：只有汉字音译轨才丢（见下一个用例）。
        assertEquals("[00:01.500]hello", tracks.rlrc);
        assertEquals(awlrc, tracks.awlrc);
    }

    @Test
    void hanTransliterationTrackIsDropped() {
        // 酷狗的 rlrc 在韩文歌里放的是汉字谐音音译（韩语 슬퍼하지마 -> 丝跑哈几吗）。
        // 副字幕只有 translation / romanization 两档，把音译挂到"罗马音"上是名不副实的。
        String content = "[awlrc:lrc:" + b64("[00:01.000]x")
                + ",rlrc:" + b64("[00:01.000]丝跑哈几吗") + "]";

        AwlrcContainer.Tracks tracks = AwlrcContainer.extract(content);

        assertNotNull(tracks);
        assertEquals("", tracks.rlrc);
        assertEquals("[00:01.000]x", tracks.lrc);
    }

    @Test
    void unknownKeysAreIgnoredAndEmptyContainerFallsBack() {
        // 只带一个不认识的新键：一轨都没解出来，必须返回 null 让调用方走正文解析，
        // 而不是返回一个"非 null 但全空"的对象让调用方以为命中。
        assertNull(AwlrcContainer.extract("body\n[awlrc:foo:" + b64("x") + "]"));
        assertNull(AwlrcContainer.extract("[awlrc:lrc:" + b64("   ") + "]"));
    }

    @Test
    void plainLyricsWithoutTheTagReturnNull() {
        assertNull(AwlrcContainer.extract("[00:01.000]Hello"));
        assertNull(AwlrcContainer.extract("WEBVTT\n\n00:01.000 --> 00:02.000\nHello"));
        assertNull(AwlrcContainer.extract(""));
        assertNull(AwlrcContainer.extract(null));
    }

    @Test
    void everyTrackMayBeMissing() {
        // 只有逐字轨、没有原文轨：这是洛雪导出的一大类，必须认得出来。
        String content = "whatever\n[awlrc:awlrc:" + b64("[00:02.000]<0,1000>逐字") + "]";

        AwlrcContainer.Tracks tracks = AwlrcContainer.extract(content);

        assertNotNull(tracks);
        assertEquals("", tracks.lrc);
        assertEquals("", tracks.tlrc);
        assertEquals("[00:02.000]<0,1000>逐字", tracks.awlrc);
    }
}
