package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 汽水「头像 / 会员标 / 完整流与试听」三处解析的离线回归测试。
 *
 * <p>三条都来自实测，而不是照参考实现猜的：</p>
 * <ul>
 *   <li>{@code /luna/pc/me} 的头像挂在 {@code my_info.medium_avatar_url} 与
 *       {@code larger_avatar_url} 上，形状是 {@code {"uri":…,"urls":[…,…]}}，
 *       <b>没有</b> {@code avatar_url}、也<b>没有</b> {@code avatar_thumb}。旧代码只找后两个名字，
 *       于是头像恒为空串，账号面板永远退回昵称首字。</li>
 *   <li>免费账号也带 {@code vip_stage}，值是 {@code "free"}。旧代码把「非空」当会员，
 *       每个登录成功的用户都被挂上假会员标。</li>
 *   <li>{@code url_player_info} 的 PlayInfoList 用 <b>PascalCase 的浮点秒</b>
 *       （{@code "Duration": 29.002} / {@code 279.918}）。旧代码只读小写名，而
 *       {@code getAsLong()} 对带小数点的数字直接抛异常并被吞掉，于是时长恒为 0，
 *       完整流与试听片段再也分不开。实测试听是 29.002 秒，所以「恰好 30 或 60 秒」
 *       这种固定值判据也会漏掉它。</li>
 * </ul>
 *
 * 抛错（否则 {@code 1000016} 会一路走到 {@code markVerified()}）、响应信封 {@code data}
 * 必须剥掉（否则 {@code track_v2} 的 {@code track_player} 永远读不到），以及候选流排序改成
 * 时长优先——之前按码率排，会把 29 秒的高码率试听排在 279 秒的完整流前面。</p>
 *
 * <p>不启动 Minecraft、不联网、不写真实配置文件，也不含任何凭据。</p>
 */
class QishuiAvatarAndStreamStaticTest {

    private static final String MEDIUM_URL =
            "https://p3-luna.douyinpic.com/img/aweme-avatar/tos-cn-i-0813_abcdef"
                    + "~noop.image?x-expires=1758300000&x-signature=aaa%3D";
    private static final String LARGER_URL =
            "https://p3-luna.douyinpic.com/img/aweme-avatar/tos-cn-i-0813_abcdef"
                    + "~noop.image?x-expires=1758300000&x-signature=ccc%3D";

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = QishuiMusicService.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    private static long durationMillis(JsonObject candidate) throws Exception {
        return (Long) invoke("durationMillis", new Class<?>[]{JsonObject.class}, candidate);
    }

    private static boolean isFullStream(long durationMillis, long declaredMillis) throws Exception {
        return (Boolean) invoke("isFullStream", new Class<?>[]{long.class, long.class},
                durationMillis, declaredMillis);
    }

    private static long declaredTrackDuration(JsonObject response) throws Exception {
        return (Long) invoke("declaredTrackDuration", new Class<?>[]{JsonObject.class}, response);
    }

    private static String serviceSource() throws IOException {
        Path dir = ProjectPaths.sharedJava()
                .resolve(Path.of("com", "muoniumplayer", "core", "ncm", "music"));
        return new String(Files.readAllBytes(dir.resolve("QishuiMusicService.java")), StandardCharsets.UTF_8);
    }

    /** 读共享层里的任意源文件（界面类在 screens 下，不在 music 包里）。 */
    private static String readShared(String relative) throws IOException {
        Path path = ProjectPaths.sharedJava().resolve(Path.of(relative.replace('/', java.io.File.separatorChar)));
        assertTrue(Files.isRegularFile(path), "找不到源文件: " + relative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /** 取一段源码：从 {@code from} 起到 {@code to} 之前。用来把断言钉在某个方法体内。 */
    private static String between(String source, String from, String to) {
        int start = source.indexOf(from);
        assertTrue(start >= 0, "找不到起点: " + from);
        int end = source.indexOf(to, start);
        assertTrue(end > start, "找不到终点: " + to);
        return source.substring(start, end);
    }

    // ──────────────────────────────── 头像 ────────────────────────────────

    @Test
    @DisplayName("medium_avatar_url 被取到，且优先于 larger_avatar_url")
    void mediumAvatarUrlIsResolved() {
        JsonObject info = json("{\"medium_avatar_url\":{\"uri\":\"aweme-avatar/tos-cn-i-0813_abcdef\","
                + "\"urls\":[" + quote(MEDIUM_URL) + ","
                + "\"https://p26-luna.douyinpic.com/img/aweme-avatar/tos-cn-i-0813_abcdef~noop.image\"]},"
                + "\"larger_avatar_url\":{\"uri\":\"aweme-avatar/tos-cn-i-0813_abcdef\",\"urls\":["
                + quote(LARGER_URL) + "]}}");
        assertEquals(MEDIUM_URL, QishuiMusicService.resolveAvatarUrl(info));
    }

    @Test
    @DisplayName("只有 medium 时也能取到 —— 这正是旧代码返回空串的那条路径")
    void mediumOnlyIsTheRegression() {
        JsonObject info = json("{\"medium_avatar_url\":{\"uri\":\"aweme-avatar/tos-cn-i-0813_abcdef\","
                + "\"urls\":[" + quote(MEDIUM_URL) + "]}}");
        assertEquals(MEDIUM_URL, QishuiMusicService.resolveAvatarUrl(info));
    }

    @Test
    @DisplayName("缺少 medium 时退回 larger_avatar_url")
    void fallsBackToLarger() {
        JsonObject info = json("{\"larger_avatar_url\":{\"uri\":\"aweme-avatar/tos-cn-i-0813_abcdef\","
                + "\"urls\":[" + quote(LARGER_URL) + "]}}");
        assertEquals(LARGER_URL, QishuiMusicService.resolveAvatarUrl(info));
    }

    @Test
    @DisplayName("旧的 avatar_thumb / avatar_url 形状仍然可用，不被这次修改打断")
    void legacyShapesStillWork() {
        assertEquals("https://p3-luna.douyinpic.com/img/x.png", QishuiMusicService.resolveAvatarUrl(
                json("{\"avatar_thumb\":{\"url_list\":[\"https://p3-luna.douyinpic.com/img/x.png\"]}}")));
        assertEquals("https://p3-luna.douyinpic.com/img/y.png", QishuiMusicService.resolveAvatarUrl(
                json("{\"avatar_url\":\"https://p3-luna.douyinpic.com/img/y.png\"}")));
    }

    @Test
    @DisplayName("裸 uri（无 http 前缀）不会被当成头像地址落盘")
    void bareUriIsRejected() {
        assertEquals("", QishuiMusicService.resolveAvatarUrl(
                json("{\"medium_avatar_url\":{\"uri\":\"aweme-avatar/tos-cn-i-0813_abcdef\"}}")));
        assertEquals("", QishuiMusicService.resolveAvatarUrl(json("{}")));
    }

    // ──────────────────────────────── 会员标 ────────────────────────────────

    @Test
    @DisplayName("vip_stage=\"free\" 不是会员 —— 假会员标的根因")
    void freeStageIsNotVip() {
        assertFalse(QishuiMusicService.resolveVip(json("{\"is_vip\":false,\"vip_stage\":\"free\"}")));
        assertFalse(QishuiMusicService.resolveVip(json("{\"vip_stage\":\"FREE\"}")));
        assertFalse(QishuiMusicService.resolveVip(json("{\"vip_stage\":\"none\"}")));
        assertFalse(QishuiMusicService.resolveVip(json("{\"vip_stage\":\"0\"}")));
        assertFalse(QishuiMusicService.resolveVip(json("{\"vip_stage\":\"default\"}")));
    }

    @Test
    @DisplayName("is_vip 为真或非 free 的 stage 才算会员")
    void realVipIsDetected() {
        assertTrue(QishuiMusicService.resolveVip(json("{\"is_vip\":true,\"vip_stage\":\"free\"}")));
        assertTrue(QishuiMusicService.resolveVip(json("{\"isVip\":true}")));
        assertTrue(QishuiMusicService.resolveVip(json("{\"vip_stage\":\"vip\"}")));
    }

    @Test
    @DisplayName("字段缺失或为 null 时保守判为非会员，不抛异常")
    void missingFieldsAreNotVip() {
        assertFalse(QishuiMusicService.resolveVip(json("{}")));
        assertFalse(QishuiMusicService.resolveVip(json("{\"is_vip\":null,\"vip_stage\":null}")));
        assertFalse(QishuiMusicService.resolveVip(json("{\"vip_stage\":123}")));
    }

    // ──────────────────────────────── 候选流时长 ────────────────────────────────

    @Test
    @DisplayName("PascalCase 浮点秒被读成毫秒 —— 旧代码在这里恒为 0")
    void pascalCaseFloatSecondsBecomeMillis() throws Exception {
        assertEquals(29_002L, durationMillis(json("{\"Duration\":29.002}")));
        assertEquals(279_918L, durationMillis(json("{\"Duration\":279.918}")));
    }

    @Test
    @DisplayName("小写整数字段与 video_meta 嵌套也认")
    void lowercaseAndNestedDurations() throws Exception {
        assertEquals(256_000L, durationMillis(json("{\"duration\":256}")));
        assertEquals(256_000L, durationMillis(json("{\"video_meta\":{\"duration\":256}}")));
        assertEquals(30_000L, durationMillis(json("{\"play_time\":30}")));
    }

    @Test
    @DisplayName("大于等于一万的原值按毫秒解释，不会被再乘一千")
    void largeValuesAreAlreadyMillis() throws Exception {
        assertEquals(256_000L, durationMillis(json("{\"duration\":256000}")));
    }

    @Test
    @DisplayName("没有时长字段时返回 0，而不是把垃圾读成某个数")
    void absentDurationIsZero() throws Exception {
        assertEquals(0L, durationMillis(json("{}")));
        assertEquals(0L, durationMillis(json("{\"duration\":\"\"}")));
        assertEquals(0L, durationMillis(null));
    }

    // ──────────────────────────────── 完整 / 试听判据 ────────────────────────────────

    @Test
    @DisplayName("29.002 秒的流在 279.918 秒的声明面前被判为试听")
    void previewIsRejected() throws Exception {
        assertFalse(isFullStream(29_002L, 279_918L));
    }

    @Test
    @DisplayName("等长的流是完整流；95% 阈值两侧各测一次")
    void fullStreamPassesThreshold() throws Exception {
        assertTrue(isFullStream(279_918L, 279_918L));
        assertTrue(isFullStream(265_923L, 279_918L));
        assertFalse(isFullStream(265_922L, 279_918L));
    }

    @Test
    @DisplayName("声明时长未知时宁可当作完整：读不出长度不该导致放不出声音")
    void unknownDeclaredDurationFailsOpen() throws Exception {
        assertTrue(isFullStream(0L, 279_918L));
        assertTrue(isFullStream(0L, 0L));
        assertTrue(isFullStream(256_000L, 0L));
    }

    @Test
    @DisplayName("声明未知时退回固定试听规格，而 29.002 秒这种非整数规格会漏 —— 已知取舍")
    void fallbackUsesFixedPreviewLengths() throws Exception {
        assertFalse(isFullStream(30_000L, 0L));
        assertFalse(isFullStream(60_000L, 0L));
        assertTrue(isFullStream(29_002L, 0L));
    }

    // ──────────────────────────────── 声明时长汇总 ────────────────────────────────

    @Test
    @DisplayName("seo_track / track / media_resource 各挂载位置都能取到声明时长")
    void declaredDurationFromEveryShape() throws Exception {
        assertEquals(279_918L, declaredTrackDuration(
                json("{\"seo_track\":{\"track\":{\"duration\":279.918}}}")));
        assertEquals(279_918L, declaredTrackDuration(
                json("{\"media_resource\":{\"Duration\":279.918}}")));
        assertEquals(256_000L, declaredTrackDuration(json("{\"duration_ms\":256000}")));
        assertEquals(0L, declaredTrackDuration(json("{}")));
        assertEquals(0L, declaredTrackDuration(null));
    }

    @Test
    @DisplayName("多处并存时取最大值，避免某一处给的是预览时长")
    void declaredDurationTakesMax() throws Exception {
        assertEquals(279_918L, declaredTrackDuration(
                json("{\"duration\":30,\"seo_track\":{\"duration\":279.918}}")));
    }

    // ──────────────────────────────── 接线（源码扫描） ────────────────────────────────

    @Test
    @DisplayName("选流把声明时长传进去，不再用「>=60 秒」当完整判据")
    void selectAacTakesDeclaredDuration() throws IOException {
        String source = serviceSource().replaceAll("\\s+", " ");
        assertTrue(source.contains(
                "static ResolveResult selectAac(List<Stream> candidates, Quality requestedQuality, "
                        + "long declaredDurationMillis)"),
                "selectAac 必须收到声明时长，否则完整/试听无从判断");
        assertTrue(source.contains("isFullStream(selectedDuration, declaredDurationMillis)"),
                "候选流长度必须与声明时长比对");
        assertFalse(source.contains("duration >= 60_000L"),
                "旧的固定阈值会把 60 秒以下的试听放进来，也会把短完整流挡掉");
    }

    @Test
    @DisplayName("getSongUrl 汇总两条链路的声明时长后再选流")
    void getSongUrlAggregatesDeclaredDuration() throws IOException {
        String source = serviceSource().replaceAll("\\s+", " ");
        assertTrue(source.contains("declared = Math.max(declared, declaredTrackDuration(pc))"),
                "登录链路的声明时长要参与汇总");
        assertTrue(source.contains("declared = Math.max(declared, declaredTrackDuration(web))"),
                "匿名链路的声明时长也要参与汇总");
        assertTrue(source.contains("return selectAac(candidates, requestedQuality, declared)"),
                "汇总结果必须传给选流");
    }

    @Test
    @DisplayName("浮点 JSON 数字不再被 getAsLong 静默读成 0")
    void longValueToleratesFloatingPoint() throws IOException {
        String source = serviceSource().replaceAll("\\s+", " ");
        assertTrue(source.contains("return Math.round(parent.get(property).getAsDouble());"),
                "上游确实存在 \"Bitrate\": 129895.0 这类写法");
    }

    @Test
    @DisplayName("refreshProfile 走统一的头像与会员解析，不再内联猜字段名")
    void profileRefreshUsesSharedResolvers() throws IOException {
        String source = serviceSource().replaceAll("\\s+", " ");
        assertTrue(source.contains("String avatar = resolveAvatarUrl(info);"),
                "头像解析集中到 resolveAvatarUrl，两处调用点共用一份字段表");
        assertTrue(source.contains("boolean vip = resolveVip(info);"),
                "会员判定集中到 resolveVip");
    }

    // ──────────────────────────────── PC 业务码 ────────────────────────────────

    @Test
    @DisplayName("status_code=1000016 必须抛错，并且被认成凭据失效")
    void sessionExpiredCodeThrowsAndMarksReauth() throws Exception {
        IOException thrown = assertThrows(IOException.class, () -> requirePcStatus(
                json("{\"status_code\":1000016,\"status_info\":{\"status_msg\":\"session expired\"}}"),
                "账号接口"));
        assertTrue(thrown.getMessage().contains("1000016"),
                "业务码要出现在消息里，looksLikeAuthRejection 只能靠它辨认");
        assertTrue(looksLikeAuthRejection(thrown));
    }

    @Test
    @DisplayName("status_code=1000062（客户端风控）抛错但不标凭据失效")
    void riskControlCodeThrowsWithoutReauth() throws Exception {
        IOException thrown = assertThrows(IOException.class, () -> requirePcStatus(
                json("{\"status_code\":1000062,\"status_info\":{\"status_msg\":\"当前应用版本有风险\"}}"),
                "取流"));
        assertTrue(thrown.getMessage().contains("1000062"));
        assertTrue(thrown.getMessage().contains("当前应用版本有风险"),
                "上游给的原因要带出来，否则排查只能靠猜");
        assertFalse(looksLikeAuthRejection(thrown),
                "风控不是过期。把它标成「需要重新登录」会让人反复重扫码，而问题根本不在凭据");
    }

    @Test
    @DisplayName("没有 status_code 字段就是成功 —— 歌单/收藏接口实测不带它")
    void absentCodeIsSuccess() throws Exception {
        JsonObject playlists = json("{\"playlists\":[{\"id\":\"1\"}]}");
        assertSame(playlists, requirePcStatus(playlists, "自建歌单"));
        JsonObject zero = json("{\"status_code\":0,\"data\":{}}");
        assertSame(zero, requirePcStatus(zero, "取流"));
        assertNull(requirePcStatus(null, "取流"));
    }

    @Test
    @DisplayName("error_code 是 status_code 的别名，不能漏")
    void errorCodeAliasAlsoRejected() {
        assertThrows(IOException.class, () ->
                requirePcStatus(json("{\"error_code\":1000005}"), "收藏"));
    }

    // ──────────────────────────────── 信封与账号本体 ────────────────────────────────

    @Test
    @DisplayName("envelope：有 data 就用 data，没有就留在顶层")
    void envelopePrefersData() throws Exception {
        JsonObject wrapped = json("{\"data\":{\"track\":{\"id\":\"1\"}},\"track\":{\"id\":\"2\"}}");
        assertSame(wrapped.getAsJsonObject("data"), envelope(wrapped));
        JsonObject plain = json("{\"seo_track\":{\"id\":\"1\"}}");
        assertSame(plain, envelope(plain), "H5 的 seo_track 在顶层，误剥一次就读不到流了");
        JsonObject emptyData = json("{\"data\":{},\"track\":{\"id\":\"2\"}}");
        assertSame(emptyData, envelope(emptyData), "空 data 不算信封");
    }

    @Test
    @DisplayName("locateAccountInfo 认顶层 my_info，也认 data 下的历史形状")
    void accountInfoFoundAtBothLevels() throws Exception {
        JsonObject top = json("{\"my_info\":{\"nickname\":\"n\",\"id\":\"1\"}}");
        assertSame(top.getAsJsonObject("my_info"), locateAccountInfo(top));
        JsonObject wrapped = json("{\"data\":{\"my_info\":{\"nickname\":\"n\"}}}");
        assertSame(wrapped.getAsJsonObject("data").getAsJsonObject("my_info"),
                locateAccountInfo(wrapped));
        JsonObject user = json("{\"user\":{\"nickname\":\"n\"}}");
        assertSame(user.getAsJsonObject("user"), locateAccountInfo(user));
        JsonObject bare = json("{\"nickname\":\"n\"}");
        assertSame(bare, locateAccountInfo(bare), "都没有时退回整包，保留改造前的兜底行为");
    }

    // ──────────────────────────────── 候选流采集 ────────────────────────────────

    @Test
    @DisplayName("track_v2 的 data.track.audio_info.play_info_list 能收到候选 —— 之前只在顶层找")
    void collectsFromDataEnvelope() throws Exception {
        List<QishuiMusicService.Stream> found = collectCandidates(json(
                "{\"data\":{\"track\":{\"audio_info\":{\"play_info_list\":["
                        + "{\"main_play_url\":\"https://cdn.example/a.m4a\",\"codec_type\":\"aac\","
                        + "\"format\":\"m4a\",\"duration\":279.918,\"bitrate\":129895}]}}}}"));
        assertEquals(1, found.size());
        assertEquals(279_918L, found.get(0).durationMillis);
    }

    @Test
    @DisplayName("video_model 是字符串再包一层 JSON：时长与 play_auth 在父节点，要继承下来")
    void collectsFromEmbeddedVideoModelWithInheritance() throws Exception {
        JsonArray videoList = new JsonArray();
        videoList.add(json("{\"main_url\":\"https://cdn.example/b.m4a\",\"format\":\"m4a\","
                + "\"codec_type\":\"aac\"}"));
        JsonObject model = new JsonObject();
        model.add("video_list", videoList);
        JsonObject player = new JsonObject();
        player.addProperty("video_duration", 279.918D);
        player.addProperty("play_auth", "auth-from-parent");
        player.addProperty("video_model", model.toString());
        JsonObject trackPlayer = new JsonObject();
        trackPlayer.add("track_player", player);
        JsonObject root = new JsonObject();
        root.add("data", trackPlayer);

        List<QishuiMusicService.Stream> found = collectCandidates(root);
        assertEquals(1, found.size(), "带地址的只有 video_list[0]，父节点不该被当成候选");
        assertEquals(279_918L, found.get(0).durationMillis,
                "不继承父节点的 video_duration 就恒为 0，「时长不足声明值九成算试听」这条判据永远不触发");
        assertEquals("auth-from-parent", found.get(0).playAuth,
                "加密流少了 play_auth，解密环节直接拿不到密钥");
    }

    @Test
    @DisplayName("顶层 seo_track 与 bit_rates 兜底同样被一次遍历收进来")
    void collectsTopLevelAndBitRates() throws Exception {
        List<QishuiMusicService.Stream> found = collectCandidates(json(
                "{\"seo_track\":{\"id\":\"7\",\"duration\":279.918},"
                        + "\"track\":{\"audio_info\":{\"bit_rates\":["
                        + "{\"main_play_url\":\"https://cdn.example/c.m4a\",\"codec_type\":\"aac\","
                        + "\"duration\":279.918,\"bitrate\":129895}]}}}"));
        assertEquals(1, found.size());
        assertEquals(279_918L, found.get(0).durationMillis);
        assertEquals(0, collectCandidates(null).size());
        assertEquals(0, collectCandidates(json("{}")).size());
    }

    @Test
    @DisplayName("locateTrack / locateTrackPlayer 覆盖 track、track_info、seo_track.track 三种挂载")
    void trackAndPlayerLocators() throws Exception {
        JsonObject a = json("{\"track\":{\"id\":\"1\"}}");
        assertSame(a.getAsJsonObject("track"), locateTrack(a));
        JsonObject b = json("{\"seo_track\":{\"track\":{\"id\":\"2\"}}}");
        assertSame(b.getAsJsonObject("seo_track").getAsJsonObject("track"), locateTrack(b));
        JsonObject c = json("{\"data\":{\"track_player\":{\"url\":\"x\"}}}");
        assertSame(c.getAsJsonObject("data").getAsJsonObject("track_player"),
                locateTrackPlayer(envelope(c)));
        assertEquals(0, locateTrackPlayer(json("{}")).size());
    }

    // ──────────────────────────────── 选流排序 ────────────────────────────────

    @Test
    @DisplayName("完整流赢过更高码率的试听 —— 旧的码率优先会选错")
    void fullStreamBeatsHigherBitratePreview() throws Exception {
        QishuiMusicService.ResolveResult result = selectAac(List.of(
                stream("{\"main_url\":\"https://cdn.example/preview.m4a\",\"format\":\"m4a\","
                        + "\"codec_type\":\"aac\",\"bitrate\":320000}", 29_002L, ""),
                stream("{\"main_url\":\"https://cdn.example/full.m4a\",\"format\":\"m4a\","
                        + "\"codec_type\":\"aac\",\"bitrate\":96000}", 279_918L, "")),
                Quality.LOSSLESS, 279_918L);
        assertEquals("https://cdn.example/full.m4a", result.url);
    }

    @Test
    @DisplayName("时长未知的候选不能凭 fail-open 压过已确认的完整流（两种顺序都测）")
    void unknownDurationRanksBelowConfirmedFull() throws Exception {
        String unknown = "{\"main_url\":\"https://cdn.example/unknown.m4a\",\"format\":\"m4a\","
                + "\"codec_type\":\"aac\",\"bitrate\":999999}";
        String full = "{\"main_url\":\"https://cdn.example/full.m4a\",\"format\":\"m4a\","
                + "\"codec_type\":\"aac\",\"bitrate\":96000}";
        assertEquals("https://cdn.example/full.m4a",
                selectAac(List.of(stream(unknown, 0L, ""), stream(full, 279_918L, "")),
                        Quality.LOSSLESS, 279_918L).url);
        assertEquals("https://cdn.example/full.m4a",
                selectAac(List.of(stream(full, 279_918L, ""), stream(unknown, 0L, "")),
                        Quality.LOSSLESS, 279_918L).url);
    }

    @Test
    @DisplayName("同档内取最长的那条")
    void longestWithinSameRank() throws Exception {
        QishuiMusicService.ResolveResult result = selectAac(List.of(
                stream("{\"main_url\":\"https://cdn.example/short.m4a\",\"format\":\"m4a\","
                        + "\"codec_type\":\"aac\",\"bitrate\":96000}", 265_923L, ""),
                stream("{\"main_url\":\"https://cdn.example/long.m4a\",\"format\":\"m4a\","
                        + "\"codec_type\":\"aac\",\"bitrate\":96000}", 279_918L, "")),
                Quality.LOSSLESS, 279_918L);
        assertEquals("https://cdn.example/long.m4a", result.url);
    }

    @Test
    @DisplayName("STANDARD 档在长度相同时挑低码率，省流量")
    void standardQualityPrefersLowerBitrate() throws Exception {
        QishuiMusicService.ResolveResult result = selectAac(List.of(
                stream("{\"main_url\":\"https://cdn.example/high.m4a\",\"format\":\"m4a\","
                        + "\"codec_type\":\"aac\",\"bitrate\":129895}", 279_918L, ""),
                stream("{\"main_url\":\"https://cdn.example/low.m4a\",\"format\":\"m4a\","
                        + "\"codec_type\":\"aac\",\"bitrate\":96000}", 279_918L, "")),
                Quality.STANDARD, 279_918L);
        assertEquals("https://cdn.example/low.m4a", result.url);
    }

    @Test
    @DisplayName("PascalCase 的 Codec 字段也认，否则上游一改命名就选不出流")
    void pascalCaseCodecIsAccepted() throws Exception {
        QishuiMusicService.ResolveResult result = selectAac(List.of(
                stream("{\"MainPlayUrl\":\"https://cdn.example/p.m4a\",\"Codec\":\"aac\"}",
                        279_918L, "auth-p")),
                Quality.LOSSLESS, 279_918L);
        assertEquals("https://cdn.example/p.m4a", result.url);
        assertEquals("auth-p", result.playAuth, "play_auth 要一路带到解密环节");
    }

    @Test
    @DisplayName("flac/wav 与没有地址的候选不参与竞争")
    void nonAacAndUrllessAreSkipped() throws Exception {
        List<QishuiMusicService.Stream> candidates = List.of(
                stream("{\"main_url\":\"https://cdn.example/a.flac\",\"format\":\"flac\","
                        + "\"codec_type\":\"flac\",\"bitrate\":2000000}", 279_918L, ""),
                stream("{\"codec_type\":\"aac\",\"bitrate\":96000}", 279_918L, ""));
        assertNull(selectAac(candidates, Quality.LOSSLESS, 279_918L));
        assertNull(selectAac(List.of(), Quality.LOSSLESS, 279_918L));
    }

    // ──────────────────────────────── 音质档位短名 ────────────────────────────────

    @Test
    @DisplayName("上游的 quality 字段映射到宿主档位 —— 每个取值都是实测见过的")
    void upstreamQualityMapsToHostTier() throws Exception {
        // 取值集合来自 2026-10-12 对 /luna/pc/track_v2 真实响应的采样：
        // PlayInfoList 的 Quality 字段只出现这几个。
        assertEquals(Quality.STANDARD, tierOf("{\"quality\":\"medium\"}"));
        assertEquals(Quality.HIGHER, tierOf("{\"quality\":\"higher\"}"));
        assertEquals(Quality.EXHIGH, tierOf("{\"quality\":\"highest\"}"));
        assertEquals(Quality.LOSSLESS, tierOf("{\"quality\":\"lossless\"}"));
        assertEquals(Quality.HIRES, tierOf("{\"quality\":\"hi_res\"}"));
        assertEquals(Quality.JYEFFECT, tierOf("{\"quality\":\"spatial\"}"));
        // PascalCase 与 video_meta 里那套也要认（上游两处都见过）。
        assertEquals(Quality.EXHIGH, tierOf("{\"Quality\":\"highest\"}"));
        assertEquals(Quality.HIRES, tierOf("{\"video_meta\":{\"quality\":\"hi_res\"}}"));
    }

    @Test
    @DisplayName("档位名认不出来时返回 null，绝不按码率瞎猜一档")
    void unknownTierStaysNull() throws Exception {
        // 「高清环绕」与「极高」的码率几乎一样（实测都是 324k），按码率猜必然猜错其中一个。
        assertNull(tierOf("{\"bitrate\":324226}"));
        assertNull(tierOf("{}"));
    }

    @Test
    @DisplayName("选中的流要把它自己那一档带回去 —— 界面靠它说「实得」")
    void selectedStreamCarriesItsOwnTier() throws Exception {
        QishuiMusicService.ResolveResult result = selectAac(List.of(
                stream("{\"main_url\":\"https://cdn.example/a.m4a\",\"format\":\"m4a\","
                        + "\"codec_type\":\"aac\",\"quality\":\"highest\",\"bitrate\":260166}", 279_918L, ""),
                stream("{\"main_url\":\"https://cdn.example/b.m4a\",\"format\":\"m4a\","
                        + "\"codec_type\":\"aac\",\"quality\":\"medium\",\"bitrate\":68190}", 279_918L, "")),
                Quality.LOSSLESS, 279_918L);
        assertEquals("https://cdn.example/a.m4a", result.url);
        assertEquals(Quality.EXHIGH, result.actualTier,
                "实得档位要取自被选中的那条流，而不是最高档或最低档");
    }

    @Test
    @DisplayName("播放条不再贴码率列表，只说「所选 → 实得」两个短名")
    void qualityPillTellsChosenAndDelivered() throws IOException {
        String pill = readShared("com/muoniumplayer/core/screens/ncm/NowPlayingInfo.java");
        String label = between(pill,
                "private static String qualityLabel(Music music, boolean english) {",
                "private static String qualityEnglish(");
        assertFalse(label.contains("getQishuiQualityTiersText") || label.contains("availableBitrates"),
                "音质标签又把码率列表贴回去了 —— 用户读不懂 324k/321k/320k 那串数字，"
                        + "他要知道的只是「我选了什么、听到的是什么」");
        assertTrue(label.contains("getQishuiActualTier()"), "实得档位要读解析结果里的那一档");
        assertTrue(label.contains("\" → \""),
                "所选与实得要有一个方向记号，否则两个档位名并排看不出谁是结果");
    }

    @Test
    @DisplayName("档位短名只有一份：菜单与播放条不许各写一套词")
    void tierDisplayNamesHaveASingleSource() throws IOException {
        String menu = readShared("com/muoniumplayer/core/screens/ncm/panels/ControlsBar.java");
        assertFalse(menu.contains("case JYMASTER: return \"母带\""),
                "音质菜单又自己写了一份档位中文名 —— 两处各写一套，改一处就会出现"
                        + "「菜单写极高、标签写 EXHIGH」这种对不上的说法");
        assertTrue(menu.contains("Quality.displayNameOf(quality)"),
                "菜单的档位名应当取 Quality 那一份");
        // 八个档位每一个都要有中文名，别落下谁变成枚举名。
        for (Quality quality : Quality.values()) {
            String name = quality.displayName();
            assertTrue(name != null && !name.isEmpty() && !name.equals(quality.name()),
                    quality + " 没有中文短名，界面会直接露出枚举名");
        }
    }

    @Test
    @DisplayName("只剩试听时仍然返回它 —— 有声音比静默好，但要在日志里说明")
    void previewOnlyFailsOpen() throws Exception {
        QishuiMusicService.ResolveResult result = selectAac(List.of(
                stream("{\"main_url\":\"https://cdn.example/preview.m4a\",\"format\":\"m4a\","
                        + "\"codec_type\":\"aac\",\"bitrate\":96000}", 29_002L, "")),
                Quality.LOSSLESS, 279_918L);
        assertEquals("https://cdn.example/preview.m4a", result.url);
    }

    @Test
    @DisplayName("完整度分档：2 完整、1 未知、0 试听")
    void completenessRanking() throws Exception {
        assertEquals(2, streamCompletenessRank(279_918L, 279_918L));
        assertEquals(0, streamCompletenessRank(29_002L, 279_918L));
        assertEquals(1, streamCompletenessRank(0L, 279_918L));
        assertTrue(streamCompletenessRank(0L, 0L) < streamCompletenessRank(279_918L, 0L),
                "未知必须低于已确认，这是排序退化成码率优先的根因");
    }

    // ──────────────────────────────── 接线（源码扫描） ────────────────────────────────

    @Test
    @DisplayName("每个 PC 接口都过 requirePcStatus")
    void everyPcCallValidatesStatus() throws IOException {
        String source = serviceSource();
        int uses = source.split("requirePcStatus\\(").length - 1;
        assertTrue(uses >= 9,
                "定义之外还要有 8 处调用（账号 2、搜索 2、歌单 1、收藏 1、取流 2），实测只有 " + (uses - 1) + " 处");
    }

    @Test
    @DisplayName("取流按上游做双域名 + POST/GET 两种形态，全失败才回落 H5")
    void trackRequestFallsBackQuietly() throws IOException {
        String source = serviceSource().replaceAll("\\s+", " ");
        assertTrue(source.contains("String[] PC_API_BASES"), "上游有多个可用域名，单域名会偶发打不通");
        assertTrue(source.contains("for (String base : PC_API_BASES)"));
        assertTrue(source.contains("postJson(buildPcTrackUrl(base, \"\"),"), "POST 是上游的主形态");
        assertTrue(source.contains("getJson(buildPcTrackUrl(base, trackId),"), "GET 作为回退");
        assertTrue(source.contains("+ describe(last)); return null;"),
                "track_v2 全失败要返回 null 让 H5 继续，不能把异常抛到播放线程");
    }

    @Test
    @DisplayName("PC 请求头补齐 Referer 与三个 x-luna 标记")
    void pcHeadersMatchReferenceClient() throws IOException {
        String source = serviceSource();
        for (String header : new String[]{"Referer", "x-luna-background-type",
                "x-luna-is-background-req", "x-luna-is-local-user"}) {
            assertTrue(source.contains("headers.put(\"" + header + "\""),
                    "参考实现带这几个头，缺一个就可能被判定为非官方客户端");
        }
        assertTrue(source.contains("LunaPC/3.3.0"), "UA 用官方 PC 版本号");
    }

    @Test
    @DisplayName("url_player_info 二跳走的是同一个采集器，并且先剥信封")
    void playerInfoSecondHopIsAligned() throws IOException {
        String source = serviceSource().replaceAll("\\s+", " ");
        assertTrue(source.contains("\"url_player_info\""), "PascalCase 那套 PlayInfoList 只能从二跳拿到");
        assertTrue(source.contains("collectStreams(envelope(fetchPlayerInfo(playerInfoUrl))"),
                "二跳响应同样可能是 data 信封包着的");
    }

    @Test
    @DisplayName("只有 401/403/1000016 会标记账号需要重新登录，且只标记不清凭据")
    void reauthMarkingIsNarrowAndNonDestructive() throws IOException {
        String source = serviceSource();
        assertTrue(source.contains("QishuiSessionStore.markReauthRequired();"));
        assertEquals(1, source.split("QishuiSessionStore\\.clear\\(\\)").length - 1,
                "只有用户主动登出才清凭据；取流或刷新失败最多标记账号过期，自动清会让重扫码变成用户手工成本");
        assertTrue(source.contains("message.contains(\"1000016\")"),
                "标记过期的判据只有 HTTP 401/403 与业务码 1000016");
        assertFalse(source.contains("message.contains(\"1000062\")"),
                "风控码不能进这条判据");
    }

    // ──────────────────────────────── 反射与夹具 ────────────────────────────────

    private static JsonObject requirePcStatus(JsonObject response, String what) throws IOException {
        // 反射会把目标异常包成 InvocationTargetException，而「消息里带上业务码」正是
        // looksLikeAuthRejection 的判据，所以这里必须把原始异常还原出来再抛。
        Method method;
        try {
            method = QishuiMusicService.class.getDeclaredMethod("requirePcStatus",
                    JsonObject.class, String.class);
            method.setAccessible(true);
            return (JsonObject) method.invoke(null, response, what);
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof IOException ioException) throw ioException;
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static boolean looksLikeAuthRejection(Throwable error) throws Exception {
        return (Boolean) invoke("looksLikeAuthRejection", new Class<?>[]{Throwable.class}, error);
    }

    private static JsonObject envelope(JsonObject response) throws Exception {
        return (JsonObject) invoke("envelope", new Class<?>[]{JsonObject.class}, response);
    }

    private static JsonObject locateAccountInfo(JsonObject response) throws Exception {
        return (JsonObject) invoke("locateAccountInfo", new Class<?>[]{JsonObject.class}, response);
    }

    private static JsonObject locateTrack(JsonObject response) throws Exception {
        return (JsonObject) invoke("locateTrack", new Class<?>[]{JsonObject.class}, response);
    }

    private static JsonObject locateTrackPlayer(JsonObject response) throws Exception {
        return (JsonObject) invoke("locateTrackPlayer", new Class<?>[]{JsonObject.class}, response);
    }

    @SuppressWarnings("unchecked")
    private static List<QishuiMusicService.Stream> collectCandidates(JsonObject response)
            throws Exception {
        return (List<QishuiMusicService.Stream>) invoke("collectCandidates",
                new Class<?>[]{JsonObject.class}, response);
    }

    private static QishuiMusicService.Stream stream(String node, long durationMillis, String playAuth) {
        return new QishuiMusicService.Stream(json(node), durationMillis, playAuth);
    }

    private static QishuiMusicService.ResolveResult selectAac(
            List<QishuiMusicService.Stream> candidates, Quality quality, long declared)
            throws Exception {
        return (QishuiMusicService.ResolveResult) invoke("selectAac",
                new Class<?>[]{List.class, Quality.class, long.class}, candidates, quality, declared);
    }

    /** 上游候选节点 → 宿主档位。包内静态方法，测试直接反射调用。 */
    private static Quality tierOf(String node) throws Exception {
        return (Quality) invoke("tierOf", new Class<?>[]{JsonObject.class}, json(node));
    }

    private static int streamCompletenessRank(long durationMillis, long declaredMillis)
            throws Exception {
        return (Integer) invoke("streamCompletenessRank",
                new Class<?>[]{long.class, long.class}, durationMillis, declaredMillis);
    }

    private static String quote(String value) {
        return "\"" + value + "\"";
    }
}