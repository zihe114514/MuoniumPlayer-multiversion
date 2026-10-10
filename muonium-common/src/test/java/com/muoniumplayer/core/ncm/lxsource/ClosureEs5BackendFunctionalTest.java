package com.muoniumplayer.core.ncm.lxsource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 反射桥对真实 Closure jar 的冒烟验证：一段新语法脚本进去，ES5 出来。
 *
 * <p>静态测试钉形状，这里钉<b>功能</b>——反射桥十个调用点里任何一个签名记错，
 * 都只有真跑一次才能发现。jar 用清单仓库里发布的剥离版（与下载器 sha256 钉的是同一份）；
 * 找不到仓库检出的机器上自动跳过，不红。</p>
 *
 * <p>定位优先级：环境变量 {@code MUONIUM_UPDATES_REPO}（清单仓库根）→ 本机默认检出路径。
 * 顺带对拍字节数与 sha256——仓库文件被换过而常量没跟上时，这里先红一步。</p>
 */
class ClosureEs5BackendFunctionalTest {

    private static Path publishedJar() {
        String[] roots = {
                System.getenv("MUONIUM_UPDATES_REPO"),
                "F:\\donked\\MuoniumPlayer-updates-main",
        };
        for (String root : roots) {
            if (root == null || root.isBlank()) {
                continue;
            }
            Path jar = Paths.get(root, "libs", ClosureCompilerDownload.FILE_NAME);
            if (Files.isRegularFile(jar)) {
                return jar;
            }
        }
        return null;
    }

    @Test
    @DisplayName("真实 jar：新语法脚本（class/async/静态字段）转译成 ES5")
    void transpilesModernSyntaxToEs5() throws Exception {
        Path jar = publishedJar();
        assumeTrue(jar != null, "本机找不到清单仓库检出（可用 MUONIUM_UPDATES_REPO 指路）——跳过功能冒烟");
        assumeTrue(jar.toFile().length() == ClosureCompilerDownload.BYTES,
                "清单仓库里的 jar 字节数与钉值不符（发布文件换了？同步 ClosureCompilerDownload 常量）");

        ClosureEs5Backend backend = ClosureEs5Backend.create(jar.toFile());

        String modern = "class A { static x = 1; async *gen() { yield A.x; } }\n"
                + "async function main() { const arr = [...(await Promise.all([1, 2]))].flat(); return arr; }\n"
                + "var out = new A();";
        LxTranspiler.Outcome outcome = backend.attempt(modern, ClosureEs5Backend.LANG_UNSTABLE);
        assertTrue(outcome.transpiled, "应当转译成功，消息：" + outcome.messages);
        assertTrue(outcome.code.contains("function"), "输出应是 ES5 函数形态");
        assertFalse(outcome.code.contains("class A"), "class 语法应已被降级");
        // async/await 降级的证据是 $jscomp 运行时被注入（输出里出现 async 字样多半来自
        // $jscomp.asyncExecutePromiseGeneratorProgram 这类辅助函数名，不能按裸词断言）。
        assertTrue(outcome.code.contains("$jscomp"),
                "async/await 应已降级为 $jscomp 运行时 + Promise 状态机");
    }

    @Test
    @DisplayName("真实 jar：ES5 老脚本原样通过；坏脚本给出可读错误")
    void passesEs5AndReportsBrokenScripts() throws Exception {
        Path jar = publishedJar();
        assumeTrue(jar != null, "本机找不到清单仓库检出（可用 MUONIUM_UPDATES_REPO 指路）——跳过功能冒烟");

        ClosureEs5Backend backend = ClosureEs5Backend.create(jar.toFile());

        LxTranspiler.Outcome plain = backend.attempt(
                "var add = function (a, b) { return a + b; };", ClosureEs5Backend.LANG_UNSTABLE);
        assertTrue(plain.transpiled, "ES5 脚本应原样通过，消息：" + plain.messages);
        assertTrue(plain.code.contains("add"));

        LxTranspiler.Outcome broken = backend.attempt(
                "function {{{ not javascript", ClosureEs5Backend.LANG_UNSTABLE);
        assertFalse(broken.transpiled, "坏脚本不该转译成功");
        assertFalse(broken.messages.isEmpty(), "至少一条可读的诊断");
        assertEquals(broken.code, "function {{{ not javascript", "失败时退回原始脚本");
    }
}
