package com.muoniumplayer.core.ncm.lxsource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真起一个 Rhino 运行时，把沙箱与协议跑一遍。
 *
 * <p>与 {@link LxSourceProtocolStaticTest} 的分工：那边读源码钉字面量，这边真的执行。
 * 沙箱这种事光看源码不够 —— {@code initSafeStandardObjects} 少一个参数、{@code ClassShutter}
 * 装错时机，编译期都不会有任何动静，脚本却能一路摸到 {@code java.lang.Runtime}。
 * 所以这里用一个假脚本从脚本那一侧反过来试。</p>
 *
 * <p>全程离线：假脚本不发任何网络请求，也不碰 GL 与 Minecraft 运行期。两个运行时
 * （扩展关 / 扩展开）在 {@code @BeforeAll} 里各起一次，因为每次启动都要过一遍
 * Closure 转译，是秒级的开销，不该按测试方法数翻倍。</p>
 */
public class LxRuntimeSandboxTest {

    /** md5("abc")，用来确认宿主的加密原语在沙箱里仍然可用。 */
    private static final String MD5_ABC = "900150983cd24fb0d6963f7d28e17f72";

    /**
     * 假脚本。头部注释是必需的：{@link LxScriptInfo#parse} 认不出头注释就当它不是自定义源。
     *
     * <p>探针都打在 console 上，Java 侧从 {@link LxRuntime#getLogs()} 读 —— 这是唯一能
     * 看到「脚本那一侧发生了什么」的通道，也正好是界面上摊给用户的那几行。</p>
     */
    private static final String PROBE_SCRIPT = String.join("\n",
            "/*",
            " * @name SandboxProbe",
            " * @description 离屏探针用的假自定义源",
            " * @author muonium",
            " * @homepage https://example.invalid",
            " * @version 1.0.0",
            " */",
            "console.log('probe globals java=' + (typeof java) + ' packages=' + (typeof Packages)",
            "        + ' javaAdapter=' + (typeof JavaAdapter) + ' host=' + (typeof globalThis.__lxHost));",
            "try {",
            "  var leaked = java.lang.Runtime.getRuntime().exec('echo pwned');",
            "  console.log('probe exec-reachable ' + leaked);",
            "} catch (blocked) {",
            "  console.log('probe exec-blocked');",
            "}",
            "// 宿主的加密与缓冲区原语走的是同一条 Rhino 边界，沙箱开猛了它们会一起哑掉。",
            "console.log('probe md5=' + lx.utils.crypto.md5('abc'));",
            "console.log('probe b64=' + lx.utils.buffer.from('abc', 'utf8').toString('base64'));",
            "try {",
            "  var huge = { length: 2000000000 };",
            "  var joined = lx.utils.buffer.concat(huge);",
            "  console.log('probe concat-ok len=' + joined.length);",
            "} catch (budget) {",
            "  console.log('probe concat-threw');",
            "}",
            "console.log('probe env=' + lx.env + ' version=' + lx.version);",
            "lx.on('request', function (data) {",
            "  if (data.action === 'musicUrl') {",
            "    // 320k 这一档故意回一个非 http 的链接：上游的返回值校验必须把它挡下来。",
            "    if (data.info.type === '320k') return Promise.resolve('ftp://example.invalid/nope');",
            "    return Promise.resolve('https://example.invalid/probe.' + data.info.type + '.mp3');",
            "  }",
            "  if (data.action === 'lyric') {",
            "    return Promise.resolve({ lyric: '[00:01.00]探针', tlyric: '[00:01.00]probe',",
            "        rlyric: null, lxlyric: null });",
            "  }",
            "  if (data.action === 'pic') return Promise.resolve('https://example.invalid/cover.jpg');",
            "  if (data.action === 'search') {",
            "    // 空列表是「不合规」而不是「没搜到」：上游那套校验的口径是抛错，宿主据此落回内置链路。",
            "    if (data.info.keyword === 'bad') return Promise.resolve({ list: [] });",
            "    return Promise.resolve({ list: [",
            "      { name: '探针一', singer: '测试者甲、测试者乙', source: data.source, songmid: '12345',",
            "        albumName: '测试专辑', interval: '03:05', img: 'https://example.invalid/a.jpg' },",
            "      // 酷狗式的结果没有 songmid，只有 hash：宿主按 primaryKey() 退而求其次。",
            "      { name: '探针二', singer: '', source: data.source, songmid: '', hash: 'H1' }",
            "    ], allPage: 3, total: 90, limit: 30 });",
            "  }",
            "  return Promise.reject(new Error('unsupported action'));",
            "});",
            "lx.send('inited', {",
            "  message: '',",
            "  sources: {",
            "    wy: { type: 'music', name: 'probe-wy',",
            "          actions: ['musicUrl', 'lyric', 'pic', 'search'], qualitys: ['128k', 'flac'] },",
            "    kg: { type: 'music', name: 'probe-kg',",
            "          actions: ['musicUrl', 'search'], qualitys: ['320k'] },",
            // local 故意也声明 search：它是唯一能证明「扩展只放宽非 local 的源」的观察点
            // —— 上游那张表给 local 的就是三个动作，扩展不该动它。
            "    local: { type: 'music', name: 'probe-local',",
            "             actions: ['musicUrl', 'lyric', 'pic', 'search'], qualitys: [] }",
            "  }",
            "});");

    /** 扩展关（上游口径）。 */
    private static LxRuntime strict;
    /** 扩展开（宿主放开非 local 源的 lyric / pic）。 */
    private static LxRuntime extended;

    @BeforeAll
    public static void bootBoth() {
        strict = boot(false);
        extended = boot(true);
    }

    @AfterAll
    public static void shutdownBoth() {
        if (strict != null) strict.shutdown();
        if (extended != null) extended.shutdown();
    }

    private static LxRuntime boot(boolean extraActions) {
        LxScriptInfo info = LxScriptInfo.parse(PROBE_SCRIPT);
        LxRuntime runtime = LxRuntime.start("probe", info, PROBE_SCRIPT, extraActions);
        assertTrue(runtime.awaitReady(90_000L),
                "脚本没有就绪：" + runtime.getState() + " / " + runtime.getError()
                        + " / " + runtime.getLogs());
        return runtime;
    }

    private static String logsOf(LxRuntime runtime) {
        List<String> logs = runtime.getLogs();
        return logs == null ? "" : String.join("\n", logs);
    }

    // ════════════════════════════ 沙箱 ════════════════════════════

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("脚本摸不到 java / Packages，也摸不到宿主桥")
    public void scriptCannotReachJavaClasses() {
        String logs = logsOf(strict);
        assertTrue(logs.contains("probe globals java=undefined packages=undefined"),
                "顶层的 java / Packages 仍然对脚本可见：" + logs);
        assertTrue(logs.contains("javaAdapter=undefined"), "JavaAdapter 仍然对脚本可见：" + logs);
        // 引导脚本用完就把宿主桥摘了：留着等于把「绕过所有校验」的把手递给用户脚本。
        assertTrue(logs.contains("host=undefined"), "宿主桥 __lxHost 没有被摘掉：" + logs);
        assertTrue(logs.contains("probe exec-blocked"), "脚本居然摸到了 Runtime.exec：" + logs);
        assertFalse(logs.contains("probe exec-reachable"), "脚本执行了本机命令：" + logs);
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("沙箱没有把宿主原语一起关掉：md5 / buffer / 环境变量照常")
    public void hostPrimitivesStillWorkInsideTheSandbox() {
        String logs = logsOf(strict);
        assertTrue(logs.contains("probe md5=" + MD5_ABC), "md5 在沙箱里失效了：" + logs);
        // LxBuffer 是宿主塞进 JS 的 Java 对象。ClassShutter 只拦「按名字找类」，
        // 已存在对象上的方法调用不该受影响 —— 这一条就是在钉这个区别。
        assertTrue(logs.contains("probe b64=YWJj"), "LxBuffer.toString('base64') 失效了：" + logs);
        assertTrue(logs.contains("probe env=desktop version=2.0.0"),
                "lx.env / lx.version 与上游不一致：" + logs);
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("假 length 打不死 JS 线程：预算截断，而不是空转二十亿次")
    public void fakeArrayLengthIsClampedInsteadOfHanging() {
        String logs = logsOf(strict);
        // 没有预算时这里会一直转到测试超时：指令观察器只数 JS 字节码，管不到 Java 侧的循环，
        // 而那条唯一的 JS 线程一旦被占住，整套自定义源就废了。
        assertTrue(logs.contains("probe concat-ok len=0") || logs.contains("probe concat-threw"),
                "buffer.concat 的预算没有生效：" + logs);
        assertEquals(LxRuntime.State.READY, strict.getState(), "运行时不该因为一次越界调用而失败");
    }

    // ════════════════════════════ 请求往返 ════════════════════════════

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("musicUrl 往返：结果 JSON 带 type 与 url，与上游 handleRequest 一致")
    public void musicUrlRoundTrip() throws Exception {
        String payload = strict.request("wy", "musicUrl",
                "{\"type\":\"128k\",\"musicInfo\":{\"songmid\":\"1\",\"name\":\"探针\"}}");
        assertNotNull(payload);
        assertTrue(payload.contains("\"type\":\"128k\""), payload);
        assertTrue(payload.contains("\"url\":\"https://example.invalid/probe.128k.mp3\""), payload);
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("返回值不合规就整条拒掉：非 http(s) 的链接不能当成取流结果")
    public void nonHttpUrlIsRejected() {
        LxSourceException failure = assertThrows(LxSourceException.class,
                () -> strict.request("kg", "musicUrl", "{\"type\":\"320k\",\"musicInfo\":{}}"));
        assertTrue(String.valueOf(failure.getMessage()).contains("failed"),
                "拒绝原因应当与上游一致（failed）：" + failure.getMessage());
        // 一次请求失败不能把运行时带崩：后面还要能继续解析。
        assertEquals(LxRuntime.State.READY, strict.getState());
    }

    // ════════════════════════════ 动作白名单与扩展开关 ════════════════════════════

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("默认（扩展关）：非 local 源只有 musicUrl，local 才有 lyric / pic")
    public void upstreamActionWhitelistByDefault() {
        assertFalse(strict.isExtraActionsEnabled());
        LxRuntime.SourceDecl wy = strict.getSources().get("wy");
        assertNotNull(wy, "脚本声明的 wy 被过滤没了：" + strict.getSources().keySet());
        assertTrue(wy.supports("musicUrl"));
        // 脚本自己声明了 lyric / pic，但上游白名单不给非 local 源这两个动作。
        assertFalse(wy.supports("lyric"), "扩展关着时不该放开 lyric");
        assertFalse(wy.supports("pic"), "扩展关着时不该放开 pic");
        assertFalse(wy.supports("search"), "扩展关着时不该放开 search");

        LxRuntime.SourceDecl local = strict.getSources().get("local");
        assertNotNull(local);
        assertTrue(local.supports("lyric") && local.supports("pic"),
                "local 源应当保留三个动作：" + local.actions);
        // 音质同样按白名单取交集：脚本声明的 128k / flac 都在表里，原样保留。
        assertEquals(Arrays.asList("128k", "flac"), wy.qualitys);
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("扩展开：脚本声明过的 lyric / pic / search 对非 local 源也放开，并能真的取回结果")
    public void extensionOpensExtraActionsForRemoteSources() throws Exception {
        assertTrue(extended.isExtraActionsEnabled());
        LxRuntime.SourceDecl wy = extended.getSources().get("wy");
        assertNotNull(wy);
        assertTrue(wy.supports("lyric") && wy.supports("pic"),
                "扩展开着时 lyric / pic 应当放开：" + wy.actions);
        assertTrue(wy.supports("search"), "扩展开着时 search 应当放开：" + wy.actions);

        String lyric = extended.request("wy", "lyric", "{\"musicInfo\":{\"songmid\":\"1\"}}");
        assertTrue(lyric.contains("\"lyric\":\"[00:01.00]探针\""), lyric);
        assertTrue(lyric.contains("\"tlyric\":\"[00:01.00]probe\""), lyric);
        // 上游对缺字段是补 null 而不是省略，宿主按四个键读。
        assertTrue(lyric.contains("\"rlyric\":null"), lyric);
        assertTrue(lyric.contains("\"lxlyric\":null"), lyric);

        String pic = extended.request("wy", "pic", "{\"musicInfo\":{\"songmid\":\"1\"}}");
        assertTrue(pic.contains("\"url\":\"https://example.invalid/cover.jpg\""), pic);

        String search = extended.request("wy", "search", "{\"keyword\":\"探针\",\"page\":1,\"limit\":30}");
        assertTrue(search.contains("\"name\":\"探针一\""), search);
        assertTrue(search.contains("\"songmid\":\"12345\""), search);
        // 分页三项是内置 musicSearch 的口径，界面上「加载更多」要用。
        assertTrue(search.contains("\"allPage\":3"), search);
        assertTrue(search.contains("\"total\":90"), search);
        assertTrue(search.contains("\"limit\":30"), search);
        // 时长保持 "mm:ss" 字符串；只有 hash 没有 songmid 的那条也要原样带回来。
        assertTrue(search.contains("\"interval\":\"03:05\""), search);
        assertTrue(search.contains("\"hash\":\"H1\""), search);
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("search 的不合规返回值整条拒掉：空列表算失败，不算没搜到")
    public void emptySearchResultIsRejected() {
        // 与上游同一口径：verifySearch 抛 'failed'，宿主据此安静地落回内置搜索链路。
        LxSourceException failure = assertThrows(LxSourceException.class,
                () -> extended.request("wy", "search", "{\"keyword\":\"bad\",\"page\":1,\"limit\":30}"));
        assertTrue(String.valueOf(failure.getMessage()).contains("failed"),
                "拒绝原因应当与上游一致（failed）：" + failure.getMessage());
        assertEquals(LxRuntime.State.READY, extended.getState());
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("扩展只放宽「宿主肯问」：脚本没声明的动作照样不会凭空出现")
    public void extensionStillIntersectsWhatTheScriptDeclares() {
        LxRuntime.SourceDecl kg = extended.getSources().get("kg");
        assertNotNull(kg);
        assertTrue(kg.supports("musicUrl"));
        assertTrue(kg.supports("search"), "kg 自己声明了 search，扩展开着时就该放开");
        // kg 没声明 lyric / pic：放宽的是白名单，不是脚本自己的能力。
        assertFalse(kg.supports("lyric"), "不该替脚本凭空补出它没声明的动作");
        assertFalse(kg.supports("pic"), "不该替脚本凭空补出它没声明的动作");
        // local 在上游白名单里就没有 search：扩展只放宽非 local 的源，不动上游已经给的东西。
        assertFalse(extended.getSources().get("local").supports("search"),
                "local 的动作集应始终按上游那张表来");
    }
}
