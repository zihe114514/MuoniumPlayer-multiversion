package com.muoniumplayer.core.ncm.lxsource;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住「现代 JS 垫片」真的装进了脚本作用域。
 *
 * <p>背景：用户脚本的语法由 Closure 降级到 ES5，但 Closure 不补运行期 built-in。Rhino 1.7.15
 * 缺 {@code Promise.any} / {@code AggregateError} / {@code String.matchAll} 这类，上游脚本
 * （例如「野花」从 1.0.0 升到用 Promise.any 的 1）一用就崩在初始化。修复是 {@code LxRuntime}
 * 在引导脚本之前把 {@code muonium/lx/polyfills.js} 装进同一个 scope。</p>
 *
 * <p>与 {@link LxRuntimeSandboxTest} 同一手法：真起运行时、用假脚本从脚本那一侧观察，全程离线。
 * 同步的事实（typeof、AggregateError、matchAll、findLast、hasOwn）在装载期就打日志；异步的
 * {@code Promise.any} 走一次真实 musicUrl 往返来验。</p>
 */
public class LxModernJsPolyfillTest {

    private static final String PROBE_SCRIPT = String.join("\n",
            "/*",
            " * @name PolyfillProbe",
            " * @description 现代 JS 垫片探针",
            " * @author muonium",
            " * @homepage https://example.invalid",
            " * @version 1.0.0",
            " */",
            "console.log('probe anyfn=' + (typeof Promise.any));",
            "console.log('probe allsettledfn=' + (typeof Promise.allSettled));",
            "console.log('probe aggfn=' + (typeof AggregateError));",
            "console.log('probe matchallfn=' + (typeof ''.matchAll));",
            "var ae = new AggregateError(['e1', 'e2'], 'boom');",
            "console.log('probe ae=' + (ae instanceof Error) + '/' + (ae instanceof AggregateError)",
            "        + '/' + ae.errors.length + '/' + ae.message);",
            "var ms = 0; for (var m of 'a1b2c3'.matchAll(/[0-9]/g)) { ms += 1; }",
            "console.log('probe matchallcount=' + ms);",
            "console.log('probe findlast=' + [1, 2, 3, 4].findLast(function (x) { return x % 2 === 0; }));",
            "console.log('probe hasown=' + Object.hasOwn({ k: 1 }, 'k') + '/' + Object.hasOwn({ k: 1 }, 'v'));",
            "lx.on('request', function (data) {",
            "  if (data.action === 'musicUrl') {",
            "    // 第一个 reject、第二个 resolve：Promise.any 应当取到那个成功的链接而不是被 reject 拖崩。",
            "    return Promise.any([",
            "      Promise.reject(new Error('dead-1')),",
            "      Promise.resolve('https://example.invalid/winner.mp3'),",
            "      Promise.reject(new Error('dead-2'))",
            "    ]);",
            "  }",
            "  return Promise.reject(new Error('unsupported'));",
            "});",
            "lx.send('inited', {",
            "  message: '',",
            "  sources: {",
            "    wy: { type: 'music', name: 'probe-wy', actions: ['musicUrl'],",
            "          qualitys: ['128k', '320k', 'flac', 'flac24bit'] }",
            "  }",
            "});");

    private static LxRuntime runtime;

    @BeforeAll
    public static void boot() {
        LxScriptInfo info = LxScriptInfo.parse(PROBE_SCRIPT);
        runtime = LxRuntime.start("polyfill-probe", info, PROBE_SCRIPT, false);
        assertTrue(runtime.awaitReady(90_000L),
                "假脚本没有就绪：" + runtime.getState() + " / " + runtime.getError()
                        + " / " + logs());
    }

    @AfterAll
    public static void shutdown() {
        if (runtime != null) runtime.shutdown();
    }

    private static String logs() {
        List<String> logs = runtime.getLogs();
        return logs == null ? "" : String.join("\n", logs);
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("缺失的现代 built-in 被补上，且 AggregateError 是真的 Error 子类")
    public void modernBuiltInsAreAvailableToScripts() {
        String logs = logs();
        assertTrue(logs.contains("probe anyfn=function"), "Promise.any 没补上：" + logs);
        assertTrue(logs.contains("probe allsettledfn=function"), "Promise.allSettled 不可用：" + logs);
        assertTrue(logs.contains("probe aggfn=function"), "AggregateError 没补上：" + logs);
        assertTrue(logs.contains("probe matchallfn=function"), "String.matchAll 没补上：" + logs);
        assertTrue(logs.contains("probe ae=true/true/2/boom"),
                "AggregateError 语义不对（应 instanceof Error/AggregateError、errors.length=2、message=boom）：" + logs);
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("垫片可当原生用：matchAll 能 for-of 迭代，findLast / hasOwn 正常")
    public void polyfillsBehaveLikeRealApis() {
        String logs = logs();
        assertTrue(logs.contains("probe matchallcount=3"), "matchAll 迭代结果不对（'a1b2c3' 应有 3 个数字匹配）：" + logs);
        assertTrue(logs.contains("probe findlast=4"), "Array.findLast 不对（偶数里最后一个=4）：" + logs);
        assertTrue(logs.contains("probe hasown=true/false"), "Object.hasOwn 不对：" + logs);
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("Promise.any 真跑一次取流往返：取到第一个 resolve，忽略前面的 reject")
    public void promiseAnyResolvesThroughRealMusicUrlRoundTrip() throws Exception {
        String payload = runtime.request("wy", "musicUrl",
                "{\"type\":\"128k\",\"musicInfo\":{\"songmid\":\"1\",\"name\":\"探针\"}}");
        assertTrue(payload.contains("\"url\":\"https://example.invalid/winner.mp3\""),
                "Promise.any 没能把第一个成功结果送回来：" + payload);
        assertEquals(LxRuntime.State.READY, runtime.getState(), "一次异步解析不该把运行时带崩");
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("接线与纯 JS 约束：垫片必须在引导脚本之前装载，且自身不含任何 Java 互操作")
    public void polyfillWiringStaysBeforeBootstrapAndPure() throws IOException {
        String runtime = new String(Files.readAllBytes(ProjectPaths.sharedJava().resolve(
                Path.of("com", "muoniumplayer", "core", "ncm", "lxsource", "LxRuntime.java").toString())),
                StandardCharsets.UTF_8);
        int install = runtime.indexOf("installPolyfills(cx);");
        int bootstrap = runtime.indexOf("String bootstrap = readBootstrap();");
        assertTrue(install > 0, "运行时没有调用 installPolyfills —— 现代脚本会重新崩在 Promise.any");
        assertTrue(bootstrap > install, "垫片必须排在引导脚本之前，这样宿主自身也吃得到");
        assertTrue(runtime.contains("/muonium/lx/polyfills.js"), "没有引用垫片资源路径");

        // 垫片不经 Closure 转译，是宿主代码：与 bootstrap 同一红线，不许出现任何 Java 互操作。
        String js = new String(Files.readAllBytes(ProjectPaths.sharedResources().resolve(
                Path.of("muonium", "lx", "polyfills.js").toString())), StandardCharsets.UTF_8);
        for (String forbidden : new String[]{"Packages.", "JavaImporter", "java.lang.", "getClass(", "new java."}) {
            assertFalse(js.contains(forbidden), "垫片里出现了 " + forbidden + " —— 会被 ClassShutter 挡掉");
        }
    }
}
