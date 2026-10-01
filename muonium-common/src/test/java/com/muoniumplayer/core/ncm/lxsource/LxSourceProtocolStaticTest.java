package com.muoniumplayer.core.ncm.lxsource;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自定义源（LX Music 脚本）的协议事实钉子。
 *
 * <p>这些断言分两类。一类是纯函数：音质八档换四档、平台到源 key 的映射、交给脚本的
 * {@code musicInfo} 旧结构字段名 —— 改错一个，脚本就读不到它要的那个键，而现象只是
 * 「点了歌没反应」。另一类是<b>源码扫描</b>：引导脚本里的三张白名单与返回值校验、
 * Rhino 的沙箱配置、以及宿主各处有没有真的把 lyric / pic 接上线。</p>
 *
 * <p>为什么要扫源码而不是只测函数：白名单与长度上限的权威副本在
 * {@code resources/muonium/lx/bootstrap.js} 里（照抄上游 preload.js），它不是 Java，
 * 测不到；而「界面上那颗开关到底有没有接到服务层」这种事，只有读源码才钉得住。</p>
 */
public class LxSourceProtocolStaticTest {

    private static String readJava(String relative) throws IOException {
        return new String(Files.readAllBytes(ProjectPaths.sharedJava().resolve(relative)), StandardCharsets.UTF_8);
    }

    private static String readResource(String relative) throws IOException {
        return new String(Files.readAllBytes(ProjectPaths.sharedResources().resolve(relative)), StandardCharsets.UTF_8);
    }

    private static String bootstrap() throws IOException {
        return readResource(Path.of("muonium", "lx", "bootstrap.js").toString());
    }

    private static String runtime() throws IOException {
        return readJava(Path.of("com", "muoniumplayer", "core", "ncm", "lxsource", "LxRuntime.java").toString());
    }

    // ════════════════════════════ 平台 → 源 key ════════════════════════════

    @Test
    @DisplayName("平台映射：网易云 wy / QQ tx / 酷狗 kg，GD 按子平台再映射一层")
    public void sourceKeyMappingFollowsUpstream() {
        assertEquals("wy", LxSourceService.sourceKeyFor("NETEASE", ""));
        assertEquals("tx", LxSourceService.sourceKeyFor("QQ", ""));
        assertEquals("kg", LxSourceService.sourceKeyFor("KUGOU", ""));
        // 大小写与空白都不该影响结果：调用方传的是枚举名，但别让它变成隐式契约。
        assertEquals("wy", LxSourceService.sourceKeyFor(" netease ", ""));
    }

    @Test
    @DisplayName("GD 子平台：五个有对应源，其余一律 null（自定义源管不了就交给内置链路）")
    public void gdSubPlatformsMapOnlyWhereUpstreamHasASource() {
        assertEquals("wy", LxSourceService.sourceKeyFor("GD", "netease"));
        assertEquals("kw", LxSourceService.sourceKeyFor("GD", "kuwo"));
        assertEquals("tx", LxSourceService.sourceKeyFor("GD", "tencent"));
        assertEquals("kg", LxSourceService.sourceKeyFor("GD", "kugou"));
        assertEquals("mg", LxSourceService.sourceKeyFor("GD", "migu"));
        // 这几个上游没有对应的源 key，硬凑一个只会让脚本收到它不认识的 source。
        assertNull(LxSourceService.sourceKeyFor("GD", "joox"));
        assertNull(LxSourceService.sourceKeyFor("GD", "bilibili"));
        assertNull(LxSourceService.sourceKeyFor("GD", "spotify"));
        assertNull(LxSourceService.sourceKeyFor("BILIBILI", ""));
        assertNull(LxSourceService.sourceKeyFor("QISHUI", ""));
        assertNull(LxSourceService.sourceKeyFor("YOUTUBE", ""));
        assertNull(LxSourceService.sourceKeyFor(null, null));
    }

    // ════════════════════════════ 音质八档 ↔ 四档 ════════════════════════════

    @Test
    @DisplayName("八档并到四档：192k 与沉浸声归 320k，HiRes/母带/沉浸归 flac24bit")
    public void muQualityCollapsesIntoFourLxTiers() {
        assertEquals("128k", LxQuality.fromMuQuality("STANDARD"));
        assertEquals("320k", LxQuality.fromMuQuality("HIGHER"));
        assertEquals("320k", LxQuality.fromMuQuality("EXHIGH"));
        assertEquals("320k", LxQuality.fromMuQuality("SKY"));
        assertEquals("flac", LxQuality.fromMuQuality("LOSSLESS"));
        assertEquals("flac24bit", LxQuality.fromMuQuality("HIRES"));
        assertEquals("flac24bit", LxQuality.fromMuQuality("JYEFFECT"));
        assertEquals("flac24bit", LxQuality.fromMuQuality("JYMASTER"));
        // 认不出的档位宁可给无损也不要给 128k：用户点的是「能听」。
        assertEquals("flac", LxQuality.fromMuQuality("SOMETHING_NEW"));
        assertEquals("flac", LxQuality.fromMuQuality(null));
    }

    @Test
    @DisplayName("降级沿 flac24bit > flac > 320k > 128k；请求比脚本支持的都低时反而取更高那一档")
    public void qualityPickWalksDownTheLadderAndNeverGivesUp() {
        assertEquals("flac", LxQuality.pick("flac", Arrays.asList("128k", "320k", "flac")));
        // 脚本没有 flac：往下退到 320k。
        assertEquals("320k", LxQuality.pick("flac", Arrays.asList("128k", "320k")));
        // 脚本只有 flac，而这里要 128k：给 flac 而不是放弃。
        assertEquals("flac", LxQuality.pick("128k", Collections.singletonList("flac")));
        assertEquals("flac24bit", LxQuality.pick("flac24bit", Arrays.asList("flac24bit", "128k")));
        assertEquals("128k", LxQuality.pick("flac", Arrays.asList("flac24bit", "128k")));
        // 一个档位都没声明 → null，调用方据此放弃这一首。
        assertNull(LxQuality.pick("flac", Collections.<String>emptyList()));
        assertNull(LxQuality.pick("flac", null));
    }

    @Test
    @DisplayName("容器按链接后缀优先，认不出后缀才按档位推（flac 档给 flac，其余给 mp3）")
    public void formatPrefersUrlExtensionOverQualityGuess() {
        assertEquals("mp3", LxQuality.formatFor("https://x/a.mp3?sign=1", "flac"));
        assertEquals("flac", LxQuality.formatFor("https://x/a.flac", "128k"));
        assertEquals("m4a", LxQuality.formatFor("https://x/a.m4a#f", "128k"));
        // 签名直链经常没有扩展名，这时只能按档位猜 —— 猜错会被宿主的 normalizePlayUrl 整条丢弃。
        assertEquals("flac", LxQuality.formatFor("https://x/stream?auth=1", "flac24bit"));
        assertEquals("mp3", LxQuality.formatFor("https://x/stream?auth=1", "320k"));
    }

    @Test
    @DisplayName("音质徽标按 LX 档位换算成宿主 level，否则 320k 的 mp3 会显示成「标准」")
    public void muLevelKeepsTheBadgeHonest() {
        assertEquals("hires", LxQuality.muLevelFor("flac24bit"));
        assertEquals("lossless", LxQuality.muLevelFor("flac"));
        assertEquals("exhigh", LxQuality.muLevelFor("320k"));
        assertEquals("standard", LxQuality.muLevelFor("128k"));
        assertEquals("standard", LxQuality.muLevelFor("whatever"));
    }

    // ════════════════════════════ musicInfo 旧结构 ════════════════════════════

    @Test
    @DisplayName("交给脚本的是上游 toOldMusicInfo 的扁平旧结构，不是文档里的 meta.songId")
    public void musicInfoUsesTheOldFlatStructure() {
        String json = new LxTrack().source("wy").songmid("123").name("晴天").singer("周杰伦")
                .albumName("叶惠美").img("https://x/c.jpg").durationMillis(235_000L)
                .qualitys(Arrays.asList("128k", "flac"))
                .toMusicInfoJson();
        for (String key : new String[]{"\"name\":", "\"singer\":", "\"source\":", "\"songmid\":",
                "\"interval\":", "\"albumName\":", "\"albumId\":", "\"img\":",
                "\"typeUrl\":{}", "\"types\":[", "\"_types\":{"}) {
            assertTrue(json.contains(key), "musicInfo 少了 " + key + " → " + json);
        }
        // 时长是 "mm:ss" 字符串而不是毫秒数，未知时长写空串。
        assertTrue(json.contains("\"interval\":\"03:55\""), json);
        assertTrue(new LxTrack().durationMillis(0L).toMusicInfoJson().contains("\"interval\":\"\""),
                "未知时长应当写成空串，上游对未知时长也是空/null");
        // 宿主额外多给的 meta：按文档写的脚本会读它，多给不会让按旧结构写的脚本行为变样。
        assertTrue(json.contains("\"meta\":{"), json);
        assertTrue(json.contains("\"id\":\"wy_123\""), json);
    }

    @Test
    @DisplayName("酷狗带 hash，QQ 带 strMediaMid/albumMid/songId，咪咕带 copyrightId")
    public void perSourceExtraFieldsOnlyAppearWhenSet() {
        String kg = new LxTrack().source("kg").songmid("1").hash("ABCDEF").toMusicInfoJson();
        assertTrue(kg.contains("\"hash\":\"ABCDEF\""), kg);
        assertFalse(kg.contains("strMediaMid"), kg);

        String tx = new LxTrack().source("tx").songmid("003aAYrm3GE0Ac")
                .qq("003aAYrm3GE0Ac", "", "456").toMusicInfoJson();
        assertTrue(tx.contains("\"strMediaMid\":\"003aAYrm3GE0Ac\""), tx);
        assertTrue(tx.contains("\"songId\":\"456\""), tx);
        assertFalse(tx.contains("\"hash\""), tx);

        String mg = new LxTrack().source("mg").songmid("7").copyrightId("8").toMusicInfoJson();
        assertTrue(mg.contains("\"copyrightId\":\"8\""), mg);

        // 什么都没设时不该冒出这些键：脚本常用「有没有这个字段」判断自己拿到的是哪个源。
        String plain = new LxTrack().source("wy").songmid("9").toMusicInfoJson();
        assertFalse(plain.contains("\"hash\""), plain);
        assertFalse(plain.contains("copyrightId"), plain);
    }

    // ════════════════════════════ 引导脚本：三张白名单与校验 ════════════════════════════

    @Test
    @DisplayName("源 key 白名单与上游 preload.js 一字不差")
    public void bootstrapSourceWhitelistMatchesUpstream() throws IOException {
        String js = bootstrap();
        assertTrue(js.contains("var ALL_SOURCES = ['kw', 'kg', 'tx', 'wy', 'mg', 'local'];"),
                "源 key 白名单被改动了 —— 它必须与上游 preload.js 的 allSources 一致");
        // local 是唯一没有音质的源（本机文件不需要档位）。
        assertTrue(js.contains("local: []"), "local 的音质白名单应为空");
        for (String source : new String[]{"kw", "kg", "tx", "wy", "mg"}) {
            assertTrue(js.contains(source + ": ['128k', '320k', 'flac', 'flac24bit']"),
                    source + " 的音质白名单被改动了");
        }
    }

    @Test
    @DisplayName("动作白名单：非 local 只有 musicUrl，local 才有 lyric / pic")
    public void bootstrapActionWhitelistMatchesUpstream() throws IOException {
        String js = bootstrap();
        assertTrue(js.contains("kw: ['musicUrl'], kg: ['musicUrl'], tx: ['musicUrl'], "
                        + "wy: ['musicUrl'], mg: ['musicUrl'],"),
                "非 local 源的动作白名单被改动了 —— 上游只允许 musicUrl");
        assertTrue(js.contains("local: ['musicUrl', 'lyric', 'pic']"),
                "local 源的动作白名单被改动了");
    }

    @Test
    @DisplayName("宿主扩展开关默认不放宽白名单，且只放宽「宿主肯问」")
    public void extraActionsStaysOptIn() throws IOException {
        String js = bootstrap();
        // 开关必须严格等于 true 才生效：宿主没塞这个全局时（老引导脚本 / 意外缺失）走的是上游口径。
        assertTrue(js.contains("globalThis.__lxExtraActions === true"),
                "扩展开关必须是严格比较，缺省时要落回上游白名单");
        assertTrue(js.contains("if (EXTRA_ACTIONS && source !== 'local') return EXTRA_ACTION_LIST;"),
                "放宽逻辑被改动了");
        // 放宽之后仍然要与脚本自己声明的动作取交集，否则会对没实现 lyric 的脚本白发一次请求。
        assertTrue(js.contains("if (includes(declared.actions, allowed[a])) actions.push(allowed[a]);"),
                "白名单与脚本声明的交集被改动了");
        String runtime = runtime();
        assertTrue(runtime.contains("\"__lxExtraActions\""), "宿主没有把扩展开关交给引导脚本");
    }

    @Test
    @DisplayName("返回值校验：链接 ≤2048 且必须 http(s)，歌词四个字段各有长度上限")
    public void bootstrapVerifiesScriptReturnValues() throws IOException {
        String js = bootstrap();
        assertTrue(js.contains("response.length > 2048"), "链接长度上限被改动了");
        assertTrue(js.contains("!/^https?:/.test(response)"), "链接协议校验被改动了");
        assertTrue(js.contains("info.lyric.length > 51200"), "主歌词上限被改动了");
        assertTrue(js.contains("info.tlyric.length < 5120"), "翻译歌词上限被改动了");
        assertTrue(js.contains("info.rlyric.length < 5120"), "罗马音上限被改动了");
        assertTrue(js.contains("info.lxlyric.length < 8192"), "洛雪逐字歌词上限被改动了");
    }

    // ════════════════════════════ 沙箱 ════════════════════════════

    @Test
    @DisplayName("Rhino 必须开两道门：安全作用域 + ClassShutter 只放行宿主自己那两类")
    public void runtimeSandboxIsWired() throws IOException {
        String java = runtime();
        // 第一道：顶层不给 java / Packages / JavaAdapter。
        assertTrue(java.contains("initSafeStandardObjects(null, false)"),
                "标准对象初始化被换回了 initStandardObjects —— 那会把 java / Packages 暴露给脚本");
        assertFalse(java.contains("cx.initStandardObjects()"),
                "不要再用 initStandardObjects()：它会给脚本 java / Packages 顶层对象");
        // 第二道：把 Java 类交给脚本要过 ClassShutter，白名单只有宿主桥与 LxBuffer。
        int shutter = java.indexOf("setClassShutter");
        assertTrue(shutter > 0, "ClassShutter 被摘掉了");
        assertTrue(java.indexOf("javaClassVisibleToScripts(className)") > shutter,
                "ClassShutter 没有走白名单判定");
        assertTrue(java.contains("HOST_BRIDGE_CLASS.equals(className)"), "白名单里没有宿主桥");
        assertTrue(java.contains("LxBuffer.class.getName().equals(className)"), "白名单里没有 LxBuffer");
        // 白名单必须是「等值比较」而不是前缀匹配：前缀匹配会把整个包都放行。
        assertFalse(java.contains("startsWith(\"com.muoniumplayer"), "白名单退化成了按包前缀放行");
        // 这一条不是沙箱，但它是「脚本里 md5(x).length 拿到长度而不是方法」的唯一保障。
        assertTrue(java.contains("setJavaPrimitiveWrap(false)"), "JavaPrimitiveWrap 必须关掉");
        // 指令观察器只在解释模式下会被回调，编译模式会让「死循环打断」静默失效。
        assertTrue(java.contains("setOptimizationLevel(-1)"), "必须解释执行，否则指令观察器不生效");
        assertTrue(java.contains("setInstructionObserverThreshold"), "指令观察器阈值被摘掉了");
    }

    @Test
    @DisplayName("脚本给进来的每个值都要有预算：length 是脚本自己填的")
    public void hostSideConversionsAreBudgeted() throws IOException {
        String java = runtime();
        // 指令观察器只数 JS 字节码，管不到宿主在 Java 侧的遍历；这几处不封顶就能把唯一的
        // JS 线程占死，而那条线程一挂，整套自定义源就废了。
        for (String constant : new String[]{"MAX_CONCAT_ELEMENTS", "MAX_CONCAT_BYTES", "MAX_TIMER_ARGS",
                "MAX_HEADER_ENTRIES", "MAX_HEADER_VALUE_CHARS", "MAX_INITED_JSON_CHARS",
                "MAX_RESPONSE_JSON_CHARS"}) {
            assertTrue(java.contains(constant), "预算常量 " + constant + " 不见了");
            // 声明之外还得真的用上：只定义不使用的常量等于没有。
            int first = java.indexOf("static final int " + constant);
            assertTrue(first > 0, constant + " 没有按 static final int 声明");
            assertTrue(java.indexOf(constant, first + constant.length() + 30) > 0,
                    constant + " 只声明了没有被使用");
        }
        assertTrue(java.contains("rawLength > MAX_CONCAT_ELEMENTS"), "buffer.concat 的元素数没有截断");
        assertTrue(java.contains("rawLength > MAX_TIMER_ARGS"), "setTimeout 的参数个数没有截断");
        assertTrue(java.contains("headerMap.size() >= MAX_HEADER_ENTRIES"), "请求头条目数没有封顶");
    }

    @Test
    @DisplayName("关掉运行时时，在路上的 HTTP 回调要静默丢弃，不能把异常抛回工作线程")
    public void callbacksSurviveRuntimeShutdown() throws IOException {
        String java = runtime();
        // shutdown() 只 shutdownNow() 各个池：脚本正发着请求时改那颗扩展开关 / 重载 / 删除，
        // 回调会在 LX-Source-HTTP-n 线程上撞进已终止的池，execute 直接抛
        // RejectedExecutionException（拿真脚本边取流边重载实测出现过）。
        assertTrue(java.contains("private void postToJsLoop("), "交回 JS 线程没有统一的那道封装");
        assertTrue(java.contains("catch (RejectedExecutionException"), "池终止后的拒绝没被吞掉");
        // 只有 start()（池刚建，不可能被拒）与 request()（自己有状态检查）可以裸用 execute。
        int bare = java.split("loop\\.execute\\(", -1).length - 1;
        assertEquals(3, bare, "loop.execute 的裸调用超过了三处，新增的跨线程交接要走 postToJsLoop");
        // 取流回包必须走封装：这一处是唯一从别的线程回来的。
        int sink = java.indexOf("postToJsLoop(new Runnable");
        assertTrue(sink > 0, "HTTP 回调没有走 postToJsLoop");
        assertTrue(java.indexOf("deliverHttp(callback, failure, response)", sink) > sink,
                "HTTP 回调的投递不在封装里面");
        // 请求侧不能只吞：等结果的是游戏线程，静默丢弃等于让它干等 20 秒超时。
        int request = java.indexOf("loop.execute(new Runnable", java.indexOf("public String request("));
        assertTrue(java.indexOf("catch (RejectedExecutionException", request) > request
                && java.indexOf("自定义源已停止", request) > request,
                "request() 在池已终止时必须立刻报「自定义源已停止」");
    }

    @Test
    @DisplayName("引导脚本不许出现任何 Java 互操作（否则沙箱一开就崩）")
    public void bootstrapStaysPureJavaScript() throws IOException {
        String js = bootstrap();
        for (String forbidden : new String[]{"Packages.", "JavaImporter", "java.lang.", "getClass("}) {
            assertFalse(js.contains(forbidden), "引导脚本里出现了 " + forbidden + " —— ClassShutter 会把它挡掉");
        }
        // 宿主桥必须在用户脚本跑起来之前就摘掉，否则脚本能绕过引导脚本里的所有校验。
        assertTrue(js.contains("delete globalThis.__lxHost;"), "宿主桥没有在使用后摘掉");
    }

    // ════════════════════════════ 存档格式 ════════════════════════════

    @Test
    @DisplayName("脚本正文按上游格式存（gz_ + base64(deflate)），两边存档可以互相搬")
    public void storeKeepsTheUpstreamArchiveFormat() throws IOException {
        String java = readJava(Path.of("com", "muoniumplayer", "core", "ncm",
                "lxsource", "LxSourceStore.java").toString());
        assertTrue(java.contains("\"gz_\" + Base64.getEncoder()"), "压缩存档的前缀被改动了");
        assertTrue(java.contains("if (!stored.startsWith(\"gz_\")) return stored;"),
                "读存档时必须兼容未压缩的正文");
        assertTrue(java.contains("\"extraActions\""), "扩展开关没有落盘，重启就会忘");
    }

    // ════════════════════════════ 接线：入口必须真的接上 ════════════════════════════

    @Test
    @DisplayName("取流排在整条内置链路之前，且任何失败都返回 null 让调用方继续")
    public void playUrlPutsCustomSourceFirstAndFallsThrough() throws IOException {
        String java = readJava(Path.of("com", "muoniumplayer", "core", "ncm",
                "music", "dto", "Music.java").toString());
        int body = java.indexOf("public Tuple<String, String> getPlayUrl()");
        assertTrue(body > 0, "找不到 getPlayUrl()");
        int custom = java.indexOf("Tuple<String, String> custom = resolveLxSource();", body);
        assertTrue(custom > body, "getPlayUrl 里没有调用 resolveLxSource");
        // 一起听房间下发的链接必须仍然优先：房间共用一条链接，本机重新解析会让进度条失去意义。
        int party = java.indexOf("Tuple<String, String> shared = partyPlayUrl();", body);
        assertTrue(party > body && party < custom, "房间下发链接必须排在自定义源之前");
        assertTrue(java.indexOf("if (custom != null) return custom;", custom) > 0,
                "自定义源失败时必须继续往下走");
        for (String builtIn : new String[]{"resolveYouTubePlayUrl()", "resolveBilibiliPlayUrl()",
                "resolveQishuiPlayUrl()", "resolveGdPlayUrl()", "resolveKuGouPlayUrl()"}) {
            // 只在 getPlayUrl 的方法体里找：这些方法自己在文件后面还有一处定义。
            assertTrue(java.indexOf(builtIn, body) > custom, builtIn + " 必须排在自定义源之后");
        }
    }

    @Test
    @DisplayName("歌词与封面是兜底：内置一条都没给出时才问脚本，且默认不问")
    public void lyricAndPicAreWiredAsFallbacks() throws IOException {
        String lyricLoader = readJava(Path.of("com", "muoniumplayer", "core", "ncm",
                "music", "LyricLoadService.java").toString());
        assertTrue(lyricLoader.contains("music.resolveLxLyrics()"), "歌词链路没有接自定义源");
        // 必须是「内置为空才问」，否则脚本给的歌词会盖掉平台自己那份带翻译的。
        int insert = lyricLoader.indexOf("music.resolveLxLyrics()");
        assertTrue(lyricLoader.lastIndexOf("if (parsed.isEmpty()) {", insert) > 0,
                "自定义源歌词必须只在 parsed 为空时才问");
        assertTrue(lyricLoader.indexOf("NeteaseMusicService.getLyric(music)") < insert,
                "自定义源歌词必须排在统一提供方之后");

        String covers = readJava(Path.of("com", "muoniumplayer", "core", "ncm",
                "music", "MusicCoverService.java").toString());
        assertTrue(covers.contains("music.resolveLxCoverUrl()"), "封面链路没有接自定义源");
        int coverInsert = covers.indexOf("music.resolveLxCoverUrl()");
        assertTrue(covers.indexOf("if (!base.isEmpty()) return base;") < coverInsert,
                "平台自带的封面优先，为空才问脚本");

        String music = readJava(Path.of("com", "muoniumplayer", "core", "ncm",
                "music", "dto", "Music.java").toString());
        assertTrue(music.contains("isExtraActionsEnabled()"), "lyric / pic 必须受扩展开关控制（默认关）");
    }

    @Test
    @DisplayName("界面上点得到：扩展开关必须真的接到自定义源那一页")
    public void overlayExposesTheExtensionToggle() throws IOException {
        String overlay = readJava(Path.of("com", "muoniumplayer", "core", "screens",
                "ncm", "MusicSourceOverlay.java").toString());
        assertTrue(overlay.contains("LxSourceService::isExtraActionsEnabled"),
                "自定义源页上没有读出扩展开关的状态");
        assertTrue(overlay.contains("LxSourceService.setExtraActions("),
                "自定义源页上那颗开关没有接回服务层");
        assertTrue(overlay.contains("允许脚本提供搜索 / 歌词 / 封面"), "开关的文案不见了");
        // 安全说明要跟实际配置一致：沙箱开了就不能再写「没有沙箱」。
        assertTrue(overlay.contains("已断掉它访问 Java 类的路"), "页脚的安全说明与实际沙箱配置不一致");
        assertFalse(overlay.contains("脚本在本机直接执行，没有沙箱"), "页脚还写着「没有沙箱」，与代码不符");
    }

    // ════════════════════════════ search（宿主加的动作） ════════════════════════════

    @Test
    @DisplayName("源 key 反查平台：wy/tx/kg 接回本家平台，kw/mg 接回自定义源渠道，local 无平台")
    public void reverseSourceKeyMappingOnlyCoversPlatformsThatExist() {
        assertEquals("NETEASE", LxSourceService.platformForSourceKey("wy"));
        assertEquals("QQ", LxSourceService.platformForSourceKey("tx"));
        assertEquals("KUGOU", LxSourceService.platformForSourceKey("kg"));
        // 酷我与咪咕现在是宿主化的「自定义源渠道」：宿主按上游 musicSdk 内置了它们的搜索，
        // 取流只认脚本，所以反查必须接得回来，否则脚本从 kw/mg 搜到的曲目会被静默丢掉。
        assertEquals("KUWO", LxSourceService.platformForSourceKey("kw"));
        assertEquals("MIGU", LxSourceService.platformForSourceKey("mg"));
        assertNull(LxSourceService.platformForSourceKey("local"));
        assertNull(LxSourceService.platformForSourceKey(null));
        // 大小写与空白都不该影响结果。
        assertEquals("NETEASE", LxSourceService.platformForSourceKey(" WY "));
        // 反向映射必须与正向映射互洽，否则 sourceKeyFor 改了这里就会静默失配。
        for (String platform : new String[]{"NETEASE", "QQ", "KUGOU", "KUWO", "MIGU"}) {
            String key = LxSourceService.sourceKeyFor(platform, "");
            assertEquals(platform, LxSourceService.platformForSourceKey(key),
                    platform + " ↔ " + key + " 的往返对不上");
        }
    }

    @Test
    @DisplayName("search 的载荷与校验：结构照抄内置 musicSearch，越界的一律裁掉")
    public void bootstrapValidatesSearchPayload() throws IOException {
        String js = bootstrap();
        // 上游没有 search，所以它只可能出现在放宽后的那张扩展表里。
        assertTrue(js.contains("var EXTRA_ACTION_LIST = ['musicUrl', 'lyric', 'pic', 'search'];"),
                "扩展动作表被改动了");
        assertFalse(js.contains("local: ['musicUrl', 'lyric', 'pic', 'search']"),
                "local 的动作集必须始终是上游那三个，扩展不该动它");
        assertTrue(js.contains("case 'search':"), "分发里没有 search 这个动作");
        assertTrue(js.contains("payload = JSON.stringify(verifySearch(response));"),
                "search 的返回值没有过校验");
        // 一次搜索最多收 200 条：脚本给多少都不该把界面试图渲染的东西撑爆。
        assertTrue(js.contains("list.length < 200"), "搜索结果条数上限被改动了");
        assertTrue(js.contains("if (!list.length) throw new Error('failed');"),
                "空列表必须算失败（宿主据此落回内置链路），不能算「没搜到」");
        // 分页三项都得是正数，否则「加载更多」会算出负页数。
        assertTrue(js.contains("function positiveInt(value, fallback)"), "分页字段没有做正数兜底");
        // 封面必须是 http(s) 且不超过 2048：与 musicUrl 同一口径。
        assertTrue(js.contains("/^https?:/.test(item.img) && item.img.length <= 2048"),
                "搜索结果的封面链接没有校验");
    }

    @Test
    @DisplayName("搜索接线：脚本优先、空结果落回内置，且默认不问")
    public void searchIsWiredIntoPlayerSearch() throws IOException {
        String cadence = readJava(Path.of("com", "muoniumplayer", "core", "ncm",
                "music", "NeteaseMusicService.java").toString());
        int body = cadence.indexOf("public static List<Music> search(String keyword, int limit)");
        assertTrue(body > 0, "找不到 search(String, int)");
        int lxCall = cadence.indexOf("searchViaLxSource(keyword, limit)", body);
        assertTrue(lxCall > body, "搜索链路没有问自定义源");
        // 必须排在内置分发之前（与取流同一口径：用户启用脚本就是为了用它）。
        assertTrue(lxCall < cadence.indexOf("KuGouMusicService.search(keyword, limit)", body),
                "自定义源应当排在内置各平台搜索之前");
        assertTrue(cadence.contains("if (!fromScript.isEmpty()) return fromScript;"),
                "脚本没搜到时必须继续走内置链路");
        // 脚本答不上时不该白跑一次网易云会话初始化 —— 所以扩展问句要在 initialize 之前。
        assertTrue(lxCall < cadence.indexOf("initialize(OptionsUtil.getCookie());", body),
                "自定义源搜索应当排在 initialize 之前，答上了就不必建会话");
        assertTrue(cadence.contains("platformForSourceKey(sourceKey) == null"),
                "没有对应平台的源（kw / mg）不该被拿去搜索，结果拼不出可播曲目");

        String service = readJava(Path.of("com", "muoniumplayer", "core", "ncm",
                "lxsource", "LxSourceService.java").toString());
        assertTrue(service.contains("ready(sourceKey, \"search\")"), "resolveSearch 没有过四道门");
        assertTrue(service.contains("if (!isExtraActionsEnabled()) return null;"),
                "resolveSearch 必须受扩展开关控制（默认关）");
        assertTrue(service.contains("Math.min(limit, 60)"), "请求条数没有封顶");

        String music = readJava(Path.of("com", "muoniumplayer", "core", "ncm",
                "music", "dto", "Music.java").toString());
        assertTrue(music.contains("fromLxSearchHit"), "Music 侧没有从脚本结果建曲目的工厂");
        // 纯数字主键要直接当 id：网易云内置解析读数字 id，编成哈希会断掉退回官方源这条路。
        assertTrue(music.contains("Long.parseLong(primaryKey)"), "纯数字主键没有被直接当成 id");
        // 脚本给的封面是别人家的直链，不能再追加网易云的 ?param=。
        assertTrue(music.contains("|| foreignCover"), "外链封面没有跳过网易云的 ?param= 追加");
    }
}
