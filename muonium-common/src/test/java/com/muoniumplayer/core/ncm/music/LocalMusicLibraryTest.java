package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.ncm.music.dto.Music;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地音乐库的离线契约：身份稳定性、扫描兜底、平台闸口。
 *
 * <p>身份是这一整套功能的基石：歌词缓存、封面缓存、播放历史全按 {@code getStableKey()}
 * 落盘，而 LOCAL 行的键来自「路径的稳定哈希」——同一个文件两次扫描必须是同一首歌，
 * 两个不同文件哪怕标签完全一样也绝不能是同一首。</p>
 *
 * <p>元数据解析的容器字节另测（{@code LocalAudioMetadataTest}，直接在内存里拼容器），
 * 这里只走真文件路径，把「解析失败退化成文件名当标题」的兜底钉住。</p>
 */
class LocalMusicLibraryTest {

    @TempDir
    Path temp;

    // ────────────────────────── 身份 ╱ stableKey ──────────────────────────

    @Test
    @DisplayName("同一文件两次构造的 stableKey 一致；不同文件绝不撞键")
    void identityIsStableAcrossScans() throws IOException {
        File a = new File(temp.toFile(), "Song A.flac");
        assertTrue(a.createNewFile());

        Music first = LocalMusicLibrary.fromLocalFile(a);
        Music second = LocalMusicLibrary.fromLocalFile(a);
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.getStableKey(), second.getStableKey(),
                "同一个文件两次扫描必须是同一首歌，否则歌词封面缓存整体失效");
        assertEquals(first.getId(), second.getId());

        File b = new File(temp.toFile(), "Song B.flac");
        assertTrue(b.createNewFile());
        Music other = LocalMusicLibrary.fromLocalFile(b);
        assertNotNull(other);
        assertNotEquals(first.getStableKey(), other.getStableKey(),
                "不同文件即便标签全空也必须是两首歌");
    }

    @Test
    @DisplayName("LOCAL 行的伪 URL 带得回文件：fileOf 按路径反查")
    void localFileRoundTripViaSourceId() throws IOException {
        File file = new File(temp.toFile(), "song.mp3");
        assertTrue(file.createNewFile());
        Music music = LocalMusicLibrary.fromLocalFile(file);
        assertNotNull(music);
        assertEquals(MusicPlatform.LOCAL, music.getSource());
        assertTrue(music.isLocal());

        // fileOf 反查的就是 sourceId 里存的那个路径；文件在就在，不在就 null。
        assertEquals(file.getAbsoluteFile(), music.localFile());
        File gone = new File(temp.toFile(), "deleted.mp3");
        assertTrue(gone.createNewFile());
        Music phantom = LocalMusicLibrary.fromLocalFile(gone);
        assertNotNull(phantom);
        assertTrue(gone.delete());
        assertNull(phantom.localFile(), "文件已删除时反查必须失败，不能返回不存在的路径");
    }

    @Test
    @DisplayName("非 LOCAL 行的 localFile 恒为 null")
    void localFileRejectsForeignPlatforms() {
        Music netease = new Music("在线歌", "在线歌", "", 185811L,
                java.util.Collections.emptyList(), java.util.Collections.emptyList(),
                null, 0L, 0L, 0L, java.util.Collections.emptyList());
        netease.setSource(MusicPlatform.NETEASE);
        assertNull(netease.localFile(), "在线行的 localFile 必须是 null");
        assertFalse(netease.isLocal());
    }

    // ────────────────────────── 扫描兜底 ──────────────────────────

    @Test
    @DisplayName("只收支持的扩展名；标题缺标签时用文件名兜底")
    void scanPicksSupportedFilesAndFallsBackToFilenames() throws IOException {
        assertTrue(new File(temp.toFile(), "晴天.mp3").createNewFile());
        assertTrue(new File(temp.toFile(), "假歌.ogg").createNewFile(), "ogg 明确不支持");
        assertTrue(new File(temp.toFile(), "README.txt").createNewFile());
        assertTrue(new File(temp.toFile(), "无损.flac").createNewFile());

        List<Music> scanned = LocalMusicLibrary.scanDirectory(temp.toString());
        assertEquals(2, scanned.size(), "只收 mp3/flac，ogg 与非音频不入列");
        for (Music music : scanned) {
            assertEquals(MusicPlatform.LOCAL, music.getSource());
            assertFalse(music.getName().trim().isEmpty(), "无标签文件退化成文件名当标题");
            // 兜底标题是「去掉扩展名的文件名」（晴天 / 无损），不是原始文件名。
            assertTrue(music.getName().equals("晴天") || music.getName().equals("无损"),
                    "兜底标题应为去扩展名的文件名，实得: " + music.getName());
        }
    }

    @Test
    @DisplayName("目录不存在/是文件时扫描安全失败返回空列表")
    void scanSurvivesMissingDirectory() throws IOException {
        assertTrue(LocalMusicLibrary.scanDirectory(
                new File(temp.toFile(), "不存在").getAbsolutePath()).isEmpty());
        File plainFile = new File(temp.toFile(), "不是目录.txt");
        assertTrue(plainFile.createNewFile());
        assertTrue(LocalMusicLibrary.scanDirectory(plainFile.getAbsolutePath()).isEmpty());
    }

    // ────────────────────────── 平台闸口 ──────────────────────────

    @Test
    @DisplayName("LOCAL 行不进换源候选、canAutoSwitch 恒 false")
    void localIsNeverSwitchable() throws IOException {
        File file = new File(temp.toFile(), "本地.flac");
        assertTrue(file.createNewFile());
        Music music = LocalMusicLibrary.fromLocalFile(file);
        assertNotNull(music);

        // 取流伪 URL：分派点靠这个前缀分派，形状必须钉住。
        com.muoniumplayer.core.utils.Tuple<String, String> playUrl = music.getPlayUrl();
        assertNotNull(playUrl, "本地行取流永远成功（文件在不在是分派点的事）");
        assertTrue(playUrl.getA().startsWith(Music.LOCAL_PSEUDO_URL_PREFIX),
                "取流必须是 muonium-local:// 伪 URL，直读磁盘不联网");
        assertEquals(Music.LOCAL_PSEUDO_URL_PREFIX + Long.toUnsignedString(music.getId()),
                playUrl.getA());

        // 换源闸口：本地文件没有「被换走」一说（两个方向都堵死，见 CrossSourceSwitcher）。
        assertFalse(com.muoniumplayer.core.ncm.music.CrossSourceSwitcher.canAutoSwitch(music),
                "LOCAL 行绝不自动换源：文件没了就该报错，换在线替身是放用户没点的歌");
    }

    @Test
    @DisplayName("伪 URL 兑换：文件在返回原文件，缺失/坏容器抛归因异常")
    void pseudoUrlDispatchesToRealFile() throws IOException {
        File file = new File(temp.toFile(), "真歌.flac");
        // 最小 FLAC 头：分派点会按字节探测容器，裸空文件会被当成坏容器拒绝。
        writeFlacHeader(file);
        Music music = LocalMusicLibrary.fromLocalFile(file);
        assertNotNull(music);
        File resolved = CloudMusicTestAccess.resolveLocalPseudoUrl(music);
        assertNotNull(resolved);
        assertEquals(file.getAbsoluteFile(), resolved);

        assertTrue(file.delete());
        assertNull(CloudMusicTestAccess.resolveLocalPseudoUrl(music),
                "文件已移动或删除时兑换必须失败（上层如实报错，绝不换在线替身）");
    }

    @Test
    @DisplayName("artist 占位名是 Unknown：AMLL 匹配层靠它排除空歌手")
    void unknownArtistPlaceholderIsTheContract() throws IOException {
        File file = new File(temp.toFile(), "无标签.mp3");
        assertTrue(file.createNewFile());
        Music music = LocalMusicLibrary.fromLocalFile(file);
        assertNotNull(music);
        assertEquals("Unknown", music.getArtistsName(),
                "占位名换掉的话 AMLL artistsOf 的排除逻辑失效，匹配分会全部走偏");
    }

    private static void writeFlacHeader(File file) throws IOException {
        byte[] header = new byte[42];
        header[0] = 'f';
        header[1] = 'L';
        header[2] = 'a';
        header[3] = 'C';
        // STREAMINFO（块类型 0，最后一块）+ 34 字节块体；采样率/总样本先填 0，
        // 时长解析读不到就走兜底，容器探测只认头四个字节。
        header[4] = 0x00;
        header[5] = 0x00;
        header[6] = 0x00;
        header[7] = 0x22;
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(header);
        }
    }

    /** CloudMusic 的本地分派点在私有个内部类里；测试经由这个包内访问器兑换伪 URL。 */
    static final class CloudMusicTestAccess {
        private CloudMusicTestAccess() {
        }

        static File resolveLocalPseudoUrl(Music music) {
            return CloudMusic.resolveLocalPseudoUrl(music);
        }
    }
}
