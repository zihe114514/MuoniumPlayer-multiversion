package com.muoniumplayer.party;

import com.muoniumplayer.core.ncm.music.BilibiliFavoriteService;
import com.muoniumplayer.core.ncm.music.NeteaseMusicService;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.ImportedPlaylistStore;
import com.muoniumplayer.core.ncm.music.KuGouMusicService;
import com.muoniumplayer.core.ncm.music.LocalPlaylistStore;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.QishuiMusicService;
import com.muoniumplayer.core.ncm.music.dto.PlayList;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「播放歌单」那颗按钮点开之后能选的那些单子。
 *
 * <p>三个来源，按<b>曲目从哪来</b>排而不是按<b>存在哪儿</b>排：本机自建（{@link LocalPlaylistStore}，
 * 曲目就存在本地 JSON 里）、粘贴分享链接导入的引用（{@link ImportedPlaylistStore}，点开才回上游拉）、
 * 以及当前平台的账号歌单（网易云 / QQ / 酷狗 / B 站 / 汽水各一套，都是登录后的异步快照）。
 * 这个顺序就是用户自己的熟悉程度：自己建的最不可能陌生，账号单最可能是「登录了才有」。</p>
 *
 * <p><b>只列当前平台。</b>账号歌单没有「一次把所有平台的单子都拿过来」这种接口 —— 每一家的快照
 * 都只在切到那个平台时才加载，未登录时更是空表。跨平台一次性枚举会变成「一堆永远为空的分组」，
 * 而那正是界面上最难解释的空白。要做的是让用户先切平台再来，不是在这里替他猜。</p>
 *
 * <p><b>整个方法不许抛。</b>它被界面每帧/每次打开调用，而下面那些服务类分布在五棵版本树上，
 * 任何一家在某一棵树里因为上游改版抛一下都是可能的。所以每一段单独包起来：拿不到那一组就少一组，
 * 界面上是「少几个选项」，不是「一起听面板打不开」。</p>
 */
public final class MpRoomPlaylists {

    private MpRoomPlaylists() {
    }

    /** 列表里的一行。 */
    public static final class Item {
        public final PlayList playlist;
        /** 分组名，用于界面上的那一小段灰字：「Mu本地歌单」「导入的歌单」「网易云歌单」。 */
        public final String group;
        /** 歌单名，就是发给服务端的那个标签（同一套裁剪规则，见 {@link #labelOf}）。 */
        public final String title;
        /** 副行：曲目数等。 */
        public final String subtitle;

        Item(PlayList playlist, String group, String title, String subtitle) {
            this.playlist = playlist;
            this.group = group;
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    /**
     * 这张单子在房间里的标签 —— 发送、显示、撤回三处<b>必须是同一个字符串</b>，所以只此一份实现。
     *
     * <p>服务端会把名字截到 80 个字符再存进队列条目。客户端这边提前按同一个长度截，否则一张
     * 超长名的单子会出现「界面上写着已排 30 首，点撤回却一句都撤不动」：比对的是本地全名，
     * 队列里存的是截断名，两个字符串永远不相等。</p>
     */
    public static String labelOf(PlayList playlist) {
        String name;
        try {
            name = playlist.getName();
        } catch (Throwable ignored) {
            name = null;
        }
        String label = name == null || name.trim().isEmpty() ? "未命名歌单" : name.trim();
        return label.length() > 80 ? label.substring(0, 80) : label;
    }

    /** 现在能选到的歌单，按来源分组排好。 */
    public static List<Item> collect() {
        List<Item> out = new ArrayList<Item>();
        Set<String> seen = new HashSet<String>();

        append(out, seen, LocalPlaylistStore.list(), "Mu本地歌单");
        append(out, seen, safeImported(), "导入的歌单");
        append(out, seen, accountPlaylists(), accountGroup());
        return out;
    }

    /** 当前平台账号歌单那一段的分组名；网易云之外的平台把名字写在界面上，省得用户以为串了号。 */
    private static String accountGroup() {
        try {
            MusicPlatform platform = NeteaseMusicService.getCurrentPlatform();
            if (platform == null || platform == MusicPlatform.NETEASE) return "我的网易云歌单";
            return "我的" + platform.getDisplayName() + "歌单";
        } catch (Throwable ignored) {
            return "我的账号歌单";
        }
    }

    /**
     * 当前平台的账号歌单快照。
     *
     * <p>全部走<b>快照</b>而不是「现拉一次」：这些 getter 只读内存里已经加载好的那份，零网络。
     * 一起听面板是开着游戏放的，为了列单子而阻塞发十个上游请求是不成比例的代价 —— 用户要选的那张
     * 单子在播放器侧栏里早就拉过了，这里只是把它读出来。</p>
     */
    private static List<PlayList> accountPlaylists() {
        try {
            MusicPlatform platform = NeteaseMusicService.getCurrentPlatform();
            if (platform == MusicPlatform.QQ) return NeteaseMusicService.getQQUserPlaylistsSnapshot();
            if (platform == MusicPlatform.KUGOU) return KuGouMusicService.getUserPlaylistsSnapshot();
            if (platform == MusicPlatform.BILIBILI) return BilibiliFavoriteService.getUserPlaylistsSnapshot();
            if (platform == MusicPlatform.QISHUI) return QishuiMusicService.getUserPlaylistsSnapshot();
            if (platform == MusicPlatform.NETEASE) {
                List<PlayList> netease = CloudMusic.playLists;
                return netease == null ? new ArrayList<PlayList>() : netease;
            }
        } catch (Throwable ignored) {
            // 某一家的快照接口在某棵树上临时坏了：这一组就是空的，另外两组照样能选。
        }
        return new ArrayList<PlayList>();
    }

    private static List<PlayList> safeImported() {
        try {
            return ImportedPlaylistStore.list();
        } catch (Throwable ignored) {
            return new ArrayList<PlayList>();
        }
    }

    private static void append(List<Item> out, Set<String> seen, List<PlayList> source, String group) {
        if (source == null) return;
        for (PlayList playlist : source) {
            if (playlist == null) continue;
            String key = keyOf(playlist);
            // 同一张单子可能既被导入过、又在账号列表里（用户把自己建的网易云单粘了一遍）。
            // 灌两次没意义，而界面上出现两行同名同曲目数的单子只会让人怀疑是不是两个东西。
            if (!seen.add(key)) continue;
            out.add(new Item(playlist, group, labelOf(playlist), subtitleOf(playlist)));
        }
    }

    private static String keyOf(PlayList playlist) {
        try {
            return playlist.getPlatform().name() + ":" + playlist.getPlatformPlaylistId();
        } catch (Throwable ignored) {
            return String.valueOf(System.identityHashCode(playlist));
        }
    }

    private static String subtitleOf(PlayList playlist) {
        try {
            // 字段叫 count（{@code @SerializedName("trackCount")}），Lombok 生成的是 getCount()。
            int count = playlist.getCount();
            return count > 0 ? count + " 首" : "";
        } catch (Throwable ignored) {
            return "";
        }
    }
}
