package com.muoniumplayer.core.screens.ncm;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.QishuiBackend;
import com.muoniumplayer.core.ncm.music.QishuiMusicService;
import com.muoniumplayer.core.ncm.music.Quality;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.rendering.RGBA;
import com.muoniumplayer.core.rendering.entities.impl.ScrollText;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.font.GlyphInkMetrics;
import com.muoniumplayer.core.utils.timing.Timer;
import com.muoniumplayer.party.ui.PartyIcons;

/**
 * 全屏播放页封面下方的曲目信息块。
 *
 * <p>五行版式：曲名 / 副标题（别名或译名）/ 标签胶囊 / 话筒＋艺人 / 唱片＋专辑。改版前这里只有
 * 曲名与艺人两行，别名、音源、音质、歌词类型、专辑这些信息在全屏页完全看不到 —— 而这一页正是
 * 用户盯着看时间最长的地方。</p>
 *
 * <h3>行距为什么给得比看起来需要的多</h3>
 * <p>这五行里有三行是同一档小字（副标题 / 艺人 / 专辑），只靠字号分不出层级，全靠行距分组：
 * 副标题紧贴曲名（它们是同一个标题的两截），标签、艺人、专辑之间则各留一档明显的空隙。
 * 行距压紧一点点，这一块立刻会糊成"一团小字"。</p>
 *
 * <h3>为什么高度要能收缩</h3>
 * <p>左栏下半截还压着进度条、时间、三枚控制键和音量条，它们的位置由 {@code MusicLyricsPanel}
 * 贴着面板底边算。字号是逻辑像素、不随面板缩放，所以面板一矮，这五行就会撞上进度条。
 * {@link #render} 因此要传入一个 {@code bottomLimit}：装不下时按<b>专辑 → 副标题 → 标签</b>
 * 的顺序让位，曲名与艺人两行是底线（也就是改版前的内容，任何尺寸下都不会比原来更差）。</p>
 *
 * <h3>没有内容的行不占位</h3>
 * <p>这首歌没有别名就不留那一行的位置 —— 留空会在曲名与标签之间开一个说不清的洞。整块传送带
 * （进度条与控制键）本来就是贴底边算的、不跟着这里走，所以块高随歌变化也不会让它上下跳。</p>
 */
final class NowPlayingInfo implements SharedRenderingConstants {

    /** 胶囊高度。10 像素的标签在里头上下各留 4 像素，再矮字就贴边了。 */
    private static final double PILL_H = 16.0;
    private static final double PILL_RADIUS = 5.0;
    /** 胶囊内文字左右留白。 */
    private static final double PILL_PAD = 6.0;
    private static final double PILL_GAP = 5.0;

    /**
     * 图标方格。必须正好是 {@code FontManager.partyIcon24} 画出来的 12 逻辑像素：
     * 字形是按字号栅格化的，靠缩放凑尺寸会重采样发虚；这个数还当作"图标占多宽"参与排版。
     */
    private static final double ICON_BOX = 12.0;
    private static final double ICON_TEXT_GAP = 6.0;

    /** 各行之上的间距。副标题贴着曲名，其余三行各自成组。 */
    private static final double GAP_SUBTITLE = 4.0;
    private static final double GAP_PILLS = 12.0;
    private static final double GAP_ARTIST = 12.0;
    private static final double GAP_ALBUM = 7.0;

    /** 四行都各自跑马灯：专辑名和"电影《…》主题曲"这类副标题经常比封面还宽。 */
    private final ScrollText stTitle = new ScrollText();
    private final ScrollText stSubtitle = new ScrollText();
    private final ScrollText stArtist = new ScrollText();
    private final ScrollText stAlbum = new ScrollText();

    /** 曲名。全屏页的视觉锚点，尺寸与改版前一致。 */
    private static CFontRenderer titleFont() {
        return FontManager.pf28bold;
    }

    /** 副标题。比曲名低两档，又要明显小于艺人那一行，它是标题的附注而不是一条信息。 */
    private static CFontRenderer subtitleFont() {
        return FontManager.pf12;
    }

    /** 艺人 / 专辑两行。取 14 是为了和 12 像素的图标配平 —— 12 像素的字配 12 像素的图标，图标会显得比字大。 */
    private static CFontRenderer rowFont() {
        return FontManager.pf14;
    }

    private static CFontRenderer pillFont() {
        return FontManager.pf10bold;
    }

    /**
     * 画这一块，返回它的下边缘（调用方据此判断进度条要不要让路）。
     *
     * @param bottomLimit 允许占到的最低位置；超过就按优先级砍掉可选行
     */
    double render(Music music, double x, double y, double width, float alpha, double bottomLimit) {
        CFontRenderer title = titleFont();
        CFontRenderer subFont = subtitleFont();
        CFontRenderer rowFont = rowFont();

        String subtitle = subtitleOf(music);
        String artist = music == null ? "" : nonNull(music.getArtistsName()).trim();
        String album = albumOf(music);
        List<Pill> pills = pillsOf(music);

        boolean showSubtitle = !subtitle.isEmpty();
        boolean showAlbum = !album.isEmpty();
        boolean showPills = !pills.isEmpty();

        double bottom;
        while (true) {
            bottom = y + title.getHeight();
            if (showSubtitle) bottom += GAP_SUBTITLE + subFont.getHeight();
            if (showPills) bottom += GAP_PILLS + PILL_H;
            if (!artist.isEmpty()) bottom += GAP_ARTIST + rowFont.getHeight();
            if (showAlbum) bottom += GAP_ALBUM + rowFont.getHeight();

            if (bottom <= bottomLimit) break;
            if (showAlbum) { showAlbum = false; continue; }
            if (showSubtitle) { showSubtitle = false; continue; }
            if (showPills) { showPills = false; continue; }
            break; // 曲名 + 艺人是底线，再挤也不砍
        }

        double cursor = y;
        stTitle.render(title, nonNull(music == null ? null : music.getName()), x, cursor, width,
                RGBA.white(alpha));
        cursor += title.getHeight();

        if (showSubtitle) {
            cursor += GAP_SUBTITLE;
            stSubtitle.render(subFont, subtitle, x, cursor, width, RGBA.white(alpha * .58f));
            cursor += subFont.getHeight();
        }

        if (showPills) {
            cursor += GAP_PILLS;
            renderPills(pills, x, cursor, width, alpha);
            cursor += PILL_H;
        }

        if (!artist.isEmpty()) {
            cursor += GAP_ARTIST;
            renderIconRow(PartyIcons.MIC, stArtist, artist, x, cursor, width, alpha * .90f);
            cursor += rowFont.getHeight();
        }

        if (showAlbum) {
            cursor += GAP_ALBUM;
            renderIconRow(PartyIcons.ALBUM, stAlbum, album, x, cursor, width, alpha * .66f);
            cursor += rowFont.getHeight();
        }

        return bottom;
    }

    // ──────────────────────────── 取值 ────────────────────────────

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    /**
     * 副标题：优先别名（{@code alia}，"电影《…》主题曲"这类），没有才用译名（{@code tns}）。
     *
     * <p>两者语义不同，别名是发行方给的副题、译名是曲名的中文翻译。同时显示会把这一行挤成
     * 跑马灯，而副题比翻译更贴近"这首歌是什么"，所以别名优先。</p>
     */
    private static String subtitleOf(Music music) {
        if (music == null) return "";
        List<String> alias = music.getAliasName();
        if (alias != null) {
            for (String candidate : alias) {
                if (candidate != null && !candidate.trim().isEmpty()) return candidate.trim();
            }
        }
        return nonNull(music.getTranslatedNames()).trim();
    }

    private static String albumOf(Music music) {
        if (music == null || music.getAlbum() == null) return "";
        return nonNull(music.getAlbum().getName()).trim();
    }
    // ──────────────────────────── 标签胶囊 ────────────────────────────

    /** 中性胶囊。整块信息压在模糊封面上，白色是唯一在任何配色的封面上都读得清的颜色。 */
    private static final int NEUTRAL = 0xFFFFFF;
    /** 只有"这一首放的不是官方源"值得用颜色喊一声，其余状态歌曲列表里已经有徽标了。 */
    private static final int WARN = 0xE0B268;

    private static final class Pill {
        final String text;
        final int rgb;

        Pill(String text, int rgb) {
            this.text = text;
            this.rgb = rgb;
        }
    }

    /**
     * 歌词类型的探测结果缓存。
     *
     * <p>{@code hasWordByWordTiming} 对逐行歌词要扫完整张歌词表才能得出否定结论，而这里是
     * 每帧调用。歌词是异步加载的，不能一首歌只算一次，所以按时间片重算。</p>
     */
    private final Timer lyricProbe = new Timer();
    private String lyricLabelCache = "";
    private boolean lyricProbed = false;

    private List<Pill> pillsOf(Music music) {
        List<Pill> pills = new ArrayList<Pill>(4);
        if (music == null) return pills;

        boolean english = NCMPlayerConfig.isNowPlayingTagsInEnglish();

        if (music.getSource() != null) {
            pills.add(new Pill(english ? platformEnglish(music) : music.getSource().getDisplayName(),
                    NEUTRAL));
        }
        // 汽水的音质标签自带英文（{@link Quality#displayNameEn()}），不走 qualityEnglish——
        // 那边是按中文取值反查的，遇到「极高 → EXHIGH」这类新档位会原样漏出来。
        // 试听时这枚 pill 直接换成试听说明（音质本来就拿不到，档位箭头没有信息量），
        // 并转 WARN 色——试听的原因（后端/登录/权益）就地说明，不再单独立一枚。
        boolean qishuiTrial = music.isQishui() && music.isQishuiTrialResolve();
        String quality = qualityLabel(music, english);
        if (!quality.isEmpty()) {
            pills.add(new Pill(qishuiTrial
                            ? (english ? "TRIAL · " + trialBadgeEn() : QishuiMusicService.trialBadgeLabel())
                            : quality,
                    qishuiTrial ? WARN : NEUTRAL));
        }
        String lyric = lyricLabel(english);
        if (!lyric.isEmpty()) pills.add(new Pill(lyric, NEUTRAL));

        String unblocked = nonNull(music.getUnblockedSourceLabel()).trim();
        if (!unblocked.isEmpty()) {
            // 「已换源」= 换进来的是别的平台另一首歌；「解灰」= 还是这首，只换了条取流地址。
            // 与歌曲行那枚徽标同一套判据（Music.isCrossSourceSwitched），两处不能各说各话。
            String prefix = music.isCrossSourceSwitched()
                    ? (english ? "SWITCHED·" : "已换源·")
                    : (english ? "UNLOCK·" : "解灰·");
            pills.add(new Pill(prefix + unblocked, WARN));
        }

        return pills;
    }

    /** 试听徽标的英文短文案（{@link QishuiMusicService#trialBadgeLabel} 的英文版）。 */
    private static String trialBadgeEn() {
        if (!QishuiBackend.peekAvailable()) return "BACKEND OFFLINE";
        if (!QishuiMusicService.isLoggedIn()) return "NOT LOGGED IN";
        return "ACCOUNT TIER";
    }

    /** 平台的英文短名。不用 {@code MusicPlatform.name()}：那是枚举名，"GD" 讲不清是什么。 */
    private static String platformEnglish(Music music) {        switch (music.getSource()) {
            case QQ:
                return "QQ MUSIC";
            case KUGOU:
                return "KUGOU";
            case KUWO:
                return "KUWO";
            case MIGU:
                return "MIGU";
            case YOUTUBE:
                return "YOUTUBE";
            case BILIBILI:
                return "BILIBILI";
            case QISHUI:
                return "QISHUI";
            case GD:
                return "GD STUDIO";
            case NETEASE:
            default:
                return "NETEASE";
        }
    }

    /**
     * 音质标签：只说「用户选的什么 → 实际听到的是什么」。
     *
     * <p>从前这里贴的是一串码率（{@code HQ · 324k/321k/320k/260k/258k/132k/130k/68k/66k}）——
     * 那是实现细节，用户读不懂也不关心；他要知道的只有两件事：我选的是哪一档、最后真正
     * 拿到的是哪一档。这两件事在汽水上是会不一致的（权益、版权、上游降级都会让实得低于所选），
     * 所以两边都要说，并且用箭头把方向讲明白。</p>
     *
     * <p>实得档位取自上游自己的 {@code quality} 字段（{@link Music#getQishuiActualTier()}），
     * 不是按码率猜——汽水的「高清环绕」与「极高」码率几乎一样，猜不出来。</p>
     */
    private static String qualityLabel(Music music, boolean english) {
        if (!music.isQishui()) {
            Music.PlaybackQuality resolved = music.getPlaybackQuality();
            String badge = resolved != null && resolved.hasBadge() ? resolved.getBadge()
                    : nonNull(music.getHighestQualityLabel()).trim();
            return english ? qualityEnglish(badge) : badge;
        }
        // 汽水：用户选的那一档 + 本次实得的那一档。实得缺失时只显示所选，不编一个出来。
        Quality chosenQuality = CloudMusic.quality;
        String chosen = english ? Quality.displayNameEnOf(chosenQuality)
                : Quality.displayNameOf(chosenQuality);
        Quality actual = music.getQishuiActualTier();
        if (actual == null) return chosen;
        String actualName = english ? actual.displayNameEn() : actual.displayName();
        // 两档同名（选了无损也确实拿到无损）就说一次，别写成「无损 → 无损」。
        return chosen.equals(actualName) ? chosen : chosen + " → " + actualName;
    }

    /** 非汽水平台的音质档位只有这四个中文取值（见 {@code Music.toQualityLabel}），HQ 与 Hi-Res 本来就是英文。 */
    private static String qualityEnglish(String label) {
        if ("无损".equals(label)) return "LOSSLESS";
        if ("标准".equals(label)) return "STANDARD";
        return label;
    }

    /**
     * 逐字 / 逐行。
     *
     * <p>用的是渲染层真正的分野——有没有音节时间轴，而不是"源文件是 yrc 还是 lrc"：行级 TTML
     * 也是逐行的，按格式名分会把它标成逐字。</p>
     */
    private String lyricLabel(boolean english) {
        if (!lyricProbed || lyricProbe.isDelayed(400L)) {
            List<LyricLine> lines = CloudMusic.lyrics;
            if (lines == null || lines.isEmpty()) {
                lyricLabelCache = "";
            } else {
                lyricLabelCache = LyricParser.hasWordByWordTiming(lines) ? "逐字" : "逐行";
            }
            lyricProbed = true;
            lyricProbe.reset();
        }
        if (!english) return lyricLabelCache;
        if ("逐字".equals(lyricLabelCache)) return "WORD";
        if ("逐行".equals(lyricLabelCache)) return "LINE";
        return "";
    }

    private void renderPills(List<Pill> pills, double x, double y, double width, float alpha) {
        CFontRenderer font = pillFont();
        double cursor = x;
        for (Pill pill : pills) {
            double pillWidth = font.getStringWidthD(pill.text) + PILL_PAD * 2;
            // 放不下就整枚不画。画半枚被裁掉的胶囊比少一枚难看得多。
            if (cursor + pillWidth > x + width) break;

            // 一层极淡的底 + 一圈描边。只描边的话，浅色封面会把细线整条吃掉。
            roundedRect(cursor, y, pillWidth, PILL_H, PILL_RADIUS,
                    RGBA.color(pill.rgb, alpha * .07f));
            roundedOutline(cursor, y, pillWidth, PILL_H, PILL_RADIUS, 1.0,
                    awt(pill.rgb, alpha * .32f));
            font.drawCenteredStringVertical(pill.text, cursor + PILL_PAD, y + PILL_H * .5,
                    RGBA.color(pill.rgb, alpha * .86f));

            cursor += pillWidth + PILL_GAP;
        }
    }

    // ──────────────────────────── 图标行 ────────────────────────────

    /** 一行"图标 + 文字"。 */
    private void renderIconRow(String glyph, ScrollText scroll, String text,
                               double x, double y, double width, float alpha) {
        CFontRenderer font = rowFont();
        double centerY = y + font.getHeight() * .5;
        CFontRenderer iconFont = FontManager.partyIcon24;
        int iconColor = RGBA.white(alpha * .82f);

        // 按满格方块（FRAME）的墨迹居中，而不是按图标自己的墨迹：话筒是细长的、唱片是满格的，
        // 各自居中会让两行的图标一大一小、一高一低。
        GlyphInkMetrics square = GlyphInkMetrics.of(iconFont, PartyIcons.FRAME.charAt(0));
        if (square == null) {
            // 字形还没上传时量不出来，退回行盒居中：位置略偏，但图标仍在该在的地方。
            iconFont.drawCenteredStringVertical(glyph, x, centerY, iconColor);
        } else {
            iconFont.drawString(glyph, square.alignCenterX(x + ICON_BOX * .5),
                    square.alignCenterY(centerY), iconColor);
        }

        double textX = x + ICON_BOX + ICON_TEXT_GAP;
        scroll.render(font, text, textX, y, Math.max(1.0, width - (textX - x)), RGBA.white(alpha));
    }

    private static Color awt(int rgb, float alpha) {
        int a = (int) (Math.max(0f, Math.min(1f, alpha)) * 255f);
        return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, a);
    }
}