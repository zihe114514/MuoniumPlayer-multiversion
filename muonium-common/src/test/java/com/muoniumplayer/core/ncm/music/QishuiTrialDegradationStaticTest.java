package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「后端未就绪的试听体验」一组回归的源码级契约。
 *
 * <p>真机问题链：后端不在（或未登录）时汽水只能解析出 H5 试听流——①主界面没有任何
 * 标识说明这不是完整版；②播放层按时长差把一切试听归因为「当前账号」，用户明明是
 * 后端没起来却去检查自己的会员；③那份试听文件以「曲目 + 音质」键落盘，之后哪怕
 * 后端就绪、账号权益足够，点歌依然命中旧试听，完整流 URL 被丢弃。这里钉住修完
 * 之后不许退回去的每一道闸。</p>
 *
 * <p>不启动 Minecraft、不联网。</p>
 */
class QishuiTrialDegradationStaticTest {

    private static String read(String relative) throws Exception {
        Path path = ProjectPaths.sourceFile(relative);
        assertTrue(Files.isRegularFile(path), "找不到源文件: " + relative);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static final String MUSIC = "com/muoniumplayer/core/ncm/music/";
    private static final String SCREENS = "com/muoniumplayer/core/screens/ncm/";

    /** 方法体粗切：从签名行到下一个同缩进的收尾大括号。 */
    private static String methodBody(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, "找不到方法: " + signature);
        int brace = source.indexOf('{', at);
        assertTrue(brace > 0, "方法没有方法体: " + signature);
        int depth = 0;
        for (int i = brace; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return source.substring(brace, i + 1);
            }
        }
        throw new AssertionError("方法体没有收尾: " + signature);
    }

    @Test
    @DisplayName("取流层把完整度结论随流上传，播放层不再瞎归因")
    void resolveResultCarriesFullFlag() throws Exception {
        String service = read(MUSIC + "QishuiMusicService.java");
        String resolve = methodBody(service, "static ResolveResult selectAac(");
        assertTrue(resolve.contains("isFullStream(selectedDuration, declaredDurationMillis)"),
                "完整度判定仍是 isFullStream（95% 时长判据），不许换成一拍脑袋的码率猜测");
        assertTrue(resolve.contains("new ResolveResult(url, normalized, streamBitrate(selected.node), selected.playAuth,\n"
                        + "                tierOf(selected.node), full)")
                || resolve.split("tierOf\\(selected\\.node\\), full\\)").length >= 2,
                "selectAac 必须把 full 结论塞进 ResolveResult");
        assertTrue(service.contains("public final boolean full"),
                "ResolveResult.full 必须是公开字段，播放层（CloudMusic）读得到");

        String music = read(MUSIC + "dto/Music.java");
        assertTrue(music.contains("setQishuiTrialResolve(!result.full)"),
                "resolveQishuiPlayUrl 必须把取流结论镜像到 Music 上");
        assertTrue(music.contains("isQishuiTrialResolve"),
                "Music 上要有只读入口供 UI 与播放层判断");
    }

    @Test
    @DisplayName("汽水音频缓存键带权益世代：登录/VIP/后端任一翻转旧键失配")
    void qishuiCacheKeyCarriesBenefitEpoch() throws Exception {
        String service = read(MUSIC + "QishuiMusicService.java");
        String epoch = methodBody(service, "static int audioCacheEpoch()");
        assertTrue(epoch.contains("isLoggedIn()"), "世代必须含登录态");
        assertTrue(epoch.contains("isVipAccount()"), "世代必须含账号 VIP");
        assertTrue(epoch.contains("QishuiBackend.peekAvailable()"), "世代必须含后端可用性——且只 peek 不探测");
        assertFalse(epoch.contains("isAvailable()"),
                "世代计算绝不许调 QishuiBackend.isAvailable()：它会阻塞并触发下载调度");

        String cloud = read(MUSIC + "CloudMusic.java");
        String prefix = methodBody(cloud, "private static String audioCachePrefix(Music song)");
        assertTrue(prefix.contains("QishuiMusicService.audioCacheEpoch()"),
                "汽水缓存键必须折进权益世代（与酷狗 AUDIO_CACHE_EPOCH 同款先例）");
        assertTrue(prefix.contains("song.isQishui()"),
                "世代只作用于汽水源，不牵连其它平台的既有缓存");
    }

    @Test
    @DisplayName("试听播放分叉：换源关闭且汽水时播完片段，换源开着维持停下换源")
    void trialPlaysThroughWhenAutoSwitchOff() throws Exception {
        String cloud = read(MUSIC + "CloudMusic.java");
        String init = methodBody(cloud, "private boolean initializeAndPlaySong(");
        // playSong 实播路径：分叉条件必须是「换源不可用 + 汽水」，其余维持 close+删文件+return false
        assertTrue(init.contains("!autoSwitchable && song.isQishui()"),
                "分叉条件：自动换源关闭且是汽水");
        assertTrue(init.contains("setQishuiTrialResolve(true)"),
                "试听播放路径要点亮试听标记（UI 徽标的依据）");
        assertTrue(init.contains("lastStreamIncomplete = false"),
                "试听继续播就不算播放失败——不能触发后续换源救援文案");
        assertTrue(init.contains("musicFile.delete()"),
                "换源开启（或非汽水）时维持现状：删试听缓存，防止旧键命中坏缓存");
        // automix 两条预备路径同判据
        String arm = methodBody(cloud, "private void armTrack(");
        assertTrue(arm.contains("!CrossSourceSwitcher.canAutoSwitch(song) && song.isQishui()"),
                "automix 预备路径同一分叉，下一曲不能预备了半首还悄悄丢弃");
        String manual = methodBody(cloud, "private void prepareManualSelection(");
        if (manual.contains("isIncompleteStream")) {
            assertTrue(manual.contains("!CrossSourceSwitcher.canAutoSwitch(song) && song.isQishui()"),
                    "手动切歌预备路径同一分叉（换源关且汽水 → 试听照常预备）");
            assertTrue(manual.contains("trialFailureReason(song)"),
                    "手动切歌预备的异常文案必须走如实归因");
        }
    }

    @Test
    @DisplayName("试听归因文案三态：后端未就绪/未登录/权益不足，不再一律怪账号")
    void trialFailureReasonAttributesHonestly() throws Exception {
        String cloud = read(MUSIC + "CloudMusic.java");
        String reason = methodBody(cloud, "private static String trialFailureReason(Music song)");
        assertTrue(reason.contains("!QishuiBackend.peekAvailable()"),
                "第一判据是后端可用性（只 peek，渲染/播放线程安全）");
        assertTrue(reason.contains("本机签名后端未就绪"),
                "后端未就绪必须归因到后端");
        assertTrue(reason.contains("汽水账号未登录"),
                "未登录必须归因到登录态");
        assertTrue(reason.contains("当前账号权益拿不到完整版"),
                "都不是才归因到账号权益");
        assertTrue(cloud.contains("trialFailureReason(song)"),
                "playSong 的失败提示必须经归因方法，不许再硬编码「当前账号只能拿到试听片段」");
        assertFalse(methodBody(cloud, "private boolean playSong(Music song)")
                        .contains("当前账号只能拿到试听片段"),
                "playSong 里不许残留旧的误导文案");
    }

    @Test
    @DisplayName("试听标识挂载：播放条不挂；全屏页在音质 pill 内替换；列表行有试听标")
    void trialBadgesPresentOnMainUi() throws Exception {
        String service = read(MUSIC + "QishuiMusicService.java");
        String badge = methodBody(service, "public static String trialBadgeLabel()");
        assertTrue(badge.contains("peekAvailable()"), "徽标文案第一态：后端未就绪");
        assertTrue(badge.contains("isLoggedIn()"), "徽标文案第二态：未登录");

        // 播放条（下方控制栏）不承担状态标识——挤在音质芯片与音量之间只会添堵。
        String controls = read(SCREENS + "panels/ControlsBar.java");
        assertFalse(controls.contains("trialBadge"),
                "播放条不许有试听徽标：标识只在全屏页与歌曲列表");

        // 全屏页：试听时音质 pill 本身替换成试听说明（WARN 色），不再单独立一枚。
        String nowPlaying = read(SCREENS + "NowPlayingInfo.java");
        String pills = methodBody(nowPlaying, "private List<Pill> pillsOf(Music music)");
        assertTrue(pills.contains("qishuiTrial ? WARN : NEUTRAL"),
                "音质 pill 在试听态必须转 WARN 色");
        assertTrue(pills.contains("QishuiMusicService.trialBadgeLabel()"),
                "替换文案与播放失败归因共用一份（trialBadgeLabel）");
        // 音质文案方法原样保留——非试听时 pill 仍显示「所选 → 实得」。
        assertTrue(methodBody(nowPlaying, "private static String qualityLabel(Music music, boolean english)")
                        .contains("music.isQishui()"),
                "非试听的音质标签逻辑不许被试听替换吃掉");

        // 歌曲列表行：VIP 同款胶囊，判定走「曲目要求 × 账号能力」对照。
        String widget = read(SCREENS + "panels/MusicWidget.java");
        assertTrue(widget.contains("\"试听\""), "列表行要有「试听」小标");
        assertTrue(widget.contains("music.isQishui() && music.hasVipRestriction()"),
                "试听标的静态判定：只有 VIP 受限的汽水曲目参与对照");
        assertTrue(widget.contains("QishuiMusicService.vipContentRestrictedToTrial()"),
                "判定必须是「曲目要求 × 账号能力」完整对照，不能只看后端/登录两个降级位");
        assertTrue(widget.contains("isQishuiTrialResolve()"),
                "真实播放过的曲目由播放层点亮兜底");

        String trialService = read(MUSIC + "QishuiMusicService.java");
        String restricted = methodBody(trialService, "public static boolean vipContentRestrictedToTrial()");
        assertTrue(restricted.contains("!QishuiBackend.peekAvailable()")
                        && restricted.contains("!QishuiSessionStore.isLoggedIn()")
                        && restricted.contains("!isVipAccount()"),
                "三样缺一即试听：后端、登录、账号会员——三者齐了 VIP 曲目就是完整版");
        assertFalse(restricted.contains("isAvailable()"),
                "判定只 peek 不探测（列表行每帧调用）");
    }

    @Test
    @DisplayName("账号行会员徽标：网易云 VIP、汽水 SVIP/VIP 两档、普通用户不亮")
    void membershipBadgeCoversBothPlatforms() throws Exception {
        String nav = read(SCREENS + "panels/NavigateBar.java");
        String badge = methodBody(nav, "private static String membershipBadgeText()");
        assertTrue(badge.contains("isNeteaseVip()"), "网易云沿用既有判定");
        assertTrue(badge.contains("QishuiMusicService.membershipBadgeText()"),
                "汽水侧经服务层门面取文案（SessionStore 包私有，不直接暴露）");
        // 底板三件套的显隐与文案都跟随该方法，宽度假定过 null 收 alpha。
        assertTrue(nav.contains("membershipBadgeText() != null"),
                "底板/图标的 alpha 必须按文案判空收放");

        String service = read(MUSIC + "QishuiMusicService.java");
        String facade = methodBody(service, "public static String membershipBadgeText()");
        assertTrue(facade.contains("SVIP") && facade.contains("svip"),
                "汽水 SVIP 档要有独立文案");
        assertTrue(facade.contains("!session.loggedIn || !session.vip"),
                "未登录与免费账号都不亮标");
    }

    @Test
    @DisplayName("解密中间残留 .decrypted 进残片清扫清单")
    void decryptedTempSweptAsPartial() throws Exception {
        String cache = read(MUSIC + "MusicAudioCache.java");
        Matcher matcher = Pattern.compile("PARTIAL_SUFFIXES = \\{([^}]*)\\}").matcher(cache);
        assertTrue(matcher.find(), "找不到 PARTIAL_SUFFIXES 声明");
        assertTrue(matcher.group(1).contains(".decrypted"),
                "汽水解密中间名 .decrypted 必须在残片清单里：崩溃残留只能靠 6 小时年龄清扫收走");
    }
}
