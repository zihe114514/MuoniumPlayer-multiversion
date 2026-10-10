package com.muoniumplayer.core.ncm.lxsource;

import java.util.ArrayList;
import java.util.List;

/**
 * 把自定义源脚本降级成 Rhino 吃得下的 ES5。
 *
 * <p>为什么必须有这一步：Rhino（本 mod 已经带了）是一个很完整的 ES5 引擎，但 {@code class}、
 * {@code async}/{@code await}、{@code for (const x of ...)}、对象 rest 全都<b>不支持</b>，
 * 而自定义源脚本都是照着 Electron/Chrome 写的，这几样几乎必现。</p>
 *
 * <p><b>转译器本体不在主包里</b>：Closure Compiler 有 13.7 MB，而全项目只有这一处用它，
 * 所以它由 {@link ClosureCompilerDownload} 在进游戏时（与完整字体、Skija 伴生包同期）按需
 * 下载，经 {@link ClosureEs5Backend} 的反射桥调用——本类（以及整个主包）对
 * {@code com.google.javascript} 零编译期依赖，静态测试钉着这一点。转译器未就绪时
 * 返回带一句人话的失败结果，{@code LxRuntime} 会把原始脚本直送 Rhino 再试：
 * ES5 脚本全程无感，新语法脚本在转译器就位前会多失败一次。</p>
 *
 * <p>刻意不设 {@code CompilationLevel}：一旦设了 {@code WHITESPACE_ONLY}，Closure 就不再注入
 * {@code $jscomp} 运行时，输出里的 {@code $jscomp.asyncExecutePromiseGeneratorProgram} 会变成
 * 未定义引用；而 {@code SIMPLE_OPTIMIZATIONS} 会重命名变量，脚本报错时行号与名字全对不上。</p>
 */
public final class LxTranspiler {

    private LxTranspiler() {
    }

    public static final class Outcome {
        /** 转译成功时是 ES5 代码；失败时退回原始脚本，让 Rhino 自己再试一次。 */
        public final String code;
        public final boolean transpiled;
        public final List<String> messages;

        Outcome(String code, boolean transpiled, List<String> messages) {
            this.code = code;
            this.transpiled = transpiled;
            this.messages = messages;
        }
    }

    public static Outcome toEs5(String source) {
        List<String> messages = new ArrayList<>();
        if (source == null || source.trim().isEmpty()) {
            messages.add("脚本内容为空");
            return new Outcome("", false, messages);
        }
        ClosureEs5Backend backend = ClosureCompilerDownload.backend();
        if (backend == null) {
            // 未下载/下载中/加载失败：一句话说清这是暂态，原始脚本交给 Rhino 再试。
            messages.add(ClosureCompilerDownload.pendingMessage());
            return new Outcome(source, false, messages);
        }
        // 先按 UNSTABLE 来：那是「已实现但还没进稳定档」的语法集合，公共类字段就在里面。
        // 失败再用 UNSUPPORTED 兜一次 —— 它跳过特性集校验，能多吃下一点更新的写法，
        // 代价是 Closure 不再保证转译正确，所以只当退路而不是首选。
        Outcome stable = backend.attempt(source, ClosureEs5Backend.LANG_UNSTABLE);
        if (stable.transpiled) {
            return stable;
        }
        Outcome permissive = backend.attempt(source, ClosureEs5Backend.LANG_UNSUPPORTED);
        return permissive.transpiled ? permissive : stable;
    }
}
