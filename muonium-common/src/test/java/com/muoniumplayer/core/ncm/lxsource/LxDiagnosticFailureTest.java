package com.muoniumplayer.core.ncm.lxsource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 D2 可观测性：脚本取流失败时，日志里必须能看到「宿主替它发了哪次 HTTP、结果如何」
 * 与「脚本在哪一步、因为什么拒绝」，而不是一句没有上下文的 {@code Fail}。
 *
 * <p>用一条指向关闭端口（127.0.0.1:5）的 {@code lx.request} 触发一次确定的 HTTP 失败，
 * 全程离线。这条失败请求应先被 {@code [lx.request]} 记录，脚本据此 reject 后又被
 * {@code [lx.<action> <source>]} 记录。少了任何一环，将来脚本挂了就又只能靠猜。</p>
 */
public class LxDiagnosticFailureTest {

    private static final String FAILING_SCRIPT = String.join("\n",
            "/*",
            " * @name FailingProbe",
            " * @description 取流必然失败的假源",
            " * @author muonium",
            " * @homepage https://example.invalid",
            " * @version 1.0.0",
            " */",
            "lx.on('request', function (data) {",
            "  if (data.action === 'musicUrl') {",
            "    return new Promise(function (resolve, reject) {",
            "      lx.request('http://127.0.0.1:5/nope', { method: 'GET', timeout: 5000 },",
            "        function (err, resp) {",
            "          if (err) reject(new Error('upstream-died'));",
            "          else resolve('https://example.invalid/x.mp3');",
            "        });",
            "    });",
            "  }",
            "  return Promise.reject(new Error('unsupported'));",
            "});",
            "lx.send('inited', { message: '', sources: {",
            "  wy: { type: 'music', name: 'failing-wy', actions: ['musicUrl'],",
            "        qualitys: ['128k', '320k', 'flac', 'flac24bit'] }",
            "}});");

    private LxRuntime runtime;

    @AfterEach
    public void tearDown() {
        if (runtime != null) runtime.shutdown();
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("取流失败时日志带 HTTP 结果与脚本拒绝原因，而不是光秃秃的 Fail")
    public void failureIsTraceableInLogs() {
        LxScriptInfo info = LxScriptInfo.parse(FAILING_SCRIPT);
        runtime = LxRuntime.start("failing", info, FAILING_SCRIPT, false);
        assertTrue(runtime.awaitReady(90_000L), "假源应能就绪：" + runtime.getError());

        LxSourceException failure = assertThrows(LxSourceException.class,
                () -> runtime.request("wy", "musicUrl",
                        "{\"type\":\"128k\",\"musicInfo\":{\"source\":\"wy\",\"songmid\":\"1\"}}"));
        assertTrue(String.valueOf(failure.getMessage()).contains("upstream-died"),
                "交给宿主的失败原因应是脚本自己抛的那句：" + failure.getMessage());

        List<String> logs = runtime.getLogs();
        String joined = logs == null ? "" : String.join("\n", logs);
        assertTrue(joined.contains("[lx.request]") && joined.contains("http://127.0.0.1:5/nope"),
                "日志里应有一条指向该 URL 的 HTTP 记录：" + joined);
        assertTrue(joined.contains("失败"),
                "HTTP 失败应被明确记下来：" + joined);
        assertTrue(joined.contains("[lx.musicUrl wy]") && joined.contains("脚本拒绝"),
                "脚本 reject 应带上 action/source 与被拒原因：" + joined);
    }
}
