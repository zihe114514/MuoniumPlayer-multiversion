package com.muoniumplayer.party.ui;

/**
 * 一起听界面图标的码位（字体资源 {@code muonium/fonts/party-icons.ttf}）。
 *
 * <p>字形取自 Material Icons 的 round 一档（Apache-2.0），和界面里已有的
 * {@code hud-settings-icons} 是同一批设计，两套图标出现在同一个界面里不会一个圆一个方。
 * 生成脚本在 {@code tools/make_party_icon_font.py}，锁了图标包版本，可以重跑。</p>
 *
 * <p>码位落在 U+E400~E430（外加 {@link #FRAME}）。项目里的私用区已经很挤：{@code fontello}
 * 占 U+E001~E004 / U+E800~E802 / U+F127 / U+F234，{@code music-brand-icons} 占 U+E800~E802，
 * {@code qq-music-icons} 占 U+E888，{@code island-status-icons} 占 U+E803 / E807 / E808 /
 * E901~E904，{@code island-volume-icons} 占 U+E902~E906，{@code player-queue-icons} 占 U+E309，
 * {@code player-action-icons} 占 U+E113 / E144，{@code hud-settings-icons} 占 U+E200~E221 与
 * U+E2FF。每个 {@code CFontRenderer} 只认自己那份字体，所以重叠不会当场画错，但传错渲染器就会
 * 静默画出另一枚图标 —— 那种 bug 极难看出来，所以这里另起一段。</p>
 *
 * <p><b>{@link #FRAME} 是一枚满格实心方块，不用来显示。</b>量它的墨迹盒即得 em 方格相对
 * {@code drawString} 原点的位置，据此把图标按 Material 的设计网格居中；直接按各自的墨迹盒居中
 * 会把 {@link #CHEVRON_LEFT} 这类细长字形撑到和实心图形一样大。</p>
 */
public final class PartyIcons {

    /** 两个人：成员数、成员页。 */
    public static final String GROUP = "\ue400";
    /** 一个人：在线玩家、点歌人。 */
    public static final String PERSON = "\ue401";
    /** 人加号：邀请玩家。 */
    public static final String PERSON_ADD = "\ue402";
    /** 耳机：正在听。 */
    public static final String HEADPHONES = "\ue403";
    /** 桂冠奖章：会员（VIP）。 */
    public static final String VIP = "\ue404";
    /** 盾牌里的人：房主 / 服务器管理员。 */
    public static final String HOST = "\ue405";
    /** 法槌：封禁与踢出。 */
    public static final String GAVEL = "\ue406";
    /** 投票箱：投票切歌。 */
    public static final String VOTE = "\ue407";
    /** 信封：邀请。 */
    public static final String MAIL = "\ue408";
    /** 带角标的信封：待处理邀请。 */
    public static final String MAIL_UNREAD = "\ue409";
    /** 地球：公开房间。 */
    public static final String PUBLIC_ROOM = "\ue40a";
    /** 锁：不公开的房间。 */
    public static final String LOCK = "\ue40b";
    /** 钥匙：邀请码。 */
    public static final String KEY = "\ue40c";
    /** 喇叭：服务器频道。 */
    public static final String CHANNEL = "\ue40d";
    /** 指北针：发现页。 */
    public static final String EXPLORE = "\ue40e";
    /** 三角：播放。 */
    public static final String PLAY = "\ue40f";
    /** 双竖条：暂停。 */
    public static final String PAUSE = "\ue410";
    /** 上一曲。 */
    public static final String SKIP_PREVIOUS = "\ue411";
    /** 下一曲。 */
    public static final String SKIP_NEXT = "\ue412";
    /** 循环箭头：列表循环。 */
    public static final String REPEAT = "\ue413";
    /** 带 1 的循环箭头：单曲循环。 */
    public static final String REPEAT_ONE = "\ue414";
    /** 交叉箭头：随机播放。 */
    public static final String SHUFFLE = "\ue415";
    /** 带音符的列表：歌单页。 */
    public static final String QUEUE_MUSIC = "\ue416";
    /** 列表加号：插播（下一首就放这首）。 */
    public static final String PLAY_NEXT = "\ue417";
    /** 上箭头顶横线：置顶。 */
    public static final String TOP = "\ue418";
    /** 叉：移除一首 / 关闭。 */
    public static final String CLOSE = "\ue419";
    /** 带扫帚的垃圾桶：清空歌单。 */
    public static final String SWEEP = "\ue41a";
    /** 链条：链接点歌。 */
    public static final String LINK = "\ue41b";
    /** 双向箭头：同步一次。 */
    public static final String SYNC = "\ue41c";
    /** 门与出箭头：离开房间。 */
    public static final String LOGOUT = "\ue41d";
    /** 圆圈叉：解散房间。 */
    public static final String CANCEL = "\ue41e";
    /** 云带对勾：已连接。 */
    public static final String CLOUD_DONE = "\ue41f";
    /** 云带斜线：没有服务端 / 连不上。 */
    public static final String CLOUD_OFF = "\ue420";
    /** 圆圈对勾：成功。 */
    public static final String CHECK = "\ue421";
    /** 警告三角。 */
    public static final String WARNING = "\ue422";
    /** 信息圆：说明。 */
    public static final String INFO = "\ue423";
    /** 音符：没有封面时的占位、曲目标记。 */
    public static final String NOTE = "\ue424";
    /** 放大镜：搜索。 */
    public static final String SEARCH = "\ue425";
    /** 圆圈加号：创建房间。 */
    public static final String ADD = "\ue426";
    /** 左折角：返回。 */
    public static final String CHEVRON_LEFT = "\ue427";
    /** 右折角：进入。 */
    public static final String CHEVRON_RIGHT = "\ue428";
    /** 秒表：倒计时。 */
    public static final String TIMER = "\ue429";
    /** 禁止符：被封禁。 */
    public static final String BLOCK = "\ue42a";
    /** 圆圈双竖条：个人暂停。 */
    public static final String PAUSE_CIRCLE = "\ue42b";
    /** 圆圈三角：解除个人暂停。 */
    public static final String PLAY_CIRCLE = "\ue42c";
    /** 收音机：电台风格的房间 / 正在放。 */
    public static final String RADIO = "\ue42d";
    /** 沙漏：等着服务端回话。 */
    public static final String HOURGLASS = "\ue42e";
    /** 话筒：艺人。 */
    public static final String MIC = "\ue42f";
    /** 唱片：专辑。 */
    public static final String ALBUM = "\ue430";

    /**
     * 满格实心方块，<b>只用于量测，不要画出来</b>。
     *
     * <p>量它的墨迹盒即得 em 方格相对 {@code drawString} 原点的位置与尺寸，据此把图标按
     * Material 的 24 网格居中。</p>
     */
    public static final String FRAME = "\ue4ff";

    private PartyIcons() {
    }
}