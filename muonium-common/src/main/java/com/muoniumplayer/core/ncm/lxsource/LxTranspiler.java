package com.muoniumplayer.core.ncm.lxsource;

import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.CompilerOptions;
import com.google.javascript.jscomp.JSError;
import com.google.javascript.jscomp.Result;
import com.google.javascript.jscomp.SourceFile;
import com.google.javascript.jscomp.WarningLevel;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 把自定义源脚本降级成 Rhino 吃得下的 ES5。
 *
 * <p>为什么必须有这一步：Rhino（本 mod 已经带了）是一个很完整的 ES5 引擎，但 {@code class}、
 * {@code async}/{@code await}、{@code for (const x of ...)}、对象 rest 全都<b>不支持</b>，
 * 而自定义源脚本都是照着 Electron/Chrome 写的，这几样几乎必现。Closure Compiler 同样已经在
 * 依赖里（原本没有任何代码用它），拿它做「只转译不优化」正好把这块补上。</p>
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
        // 先按 UNSTABLE 来：那是「已实现但还没进稳定档」的语法集合，公共类字段就在里面。
        // 失败再用 UNSUPPORTED 兜一次 —— 它跳过特性集校验，能多吃下一点更新的写法，
        // 代价是 Closure 不再保证转译正确，所以只当退路而不是首选。
        Outcome stable = attempt(source, CompilerOptions.LanguageMode.UNSTABLE);
        if (stable.transpiled) return stable;
        Outcome permissive = attempt(source, CompilerOptions.LanguageMode.UNSUPPORTED);
        return permissive.transpiled ? permissive : stable;
    }

    private static Outcome attempt(String source, CompilerOptions.LanguageMode languageIn) {
        List<String> messages = new ArrayList<>();
        try {
            // Closure 默认往 System.err 打诊断信息，转译用户脚本时那只会污染游戏日志。
            Compiler compiler = new Compiler(new PrintStream(new OutputStream() {
                @Override
                public void write(int ignored) {
                }
            }, false, "UTF-8"));
            compiler.disableThreads();

            CompilerOptions options = new CompilerOptions();
            options.setLanguageIn(languageIn);
            options.setLanguageOut(CompilerOptions.LanguageMode.ECMASCRIPT5);
            WarningLevel.QUIET.setOptionsForWarningLevel(options);
            options.setPrettyPrint(true);
            // 脚本顶层不能被塞进严格模式：上游是当普通 <script> 跑的，
            // 严格模式下 arguments.callee、给未声明变量赋值这类老写法会直接抛。
            options.setEmitUseStrict(false);
            // 内建对象的 polyfill 按需注入：Rhino 1.7 缺 Object.fromEntries / Array.flat 这类方法，
            // Closure 注入的版本是「原生存在就用原生」的写法，不会覆盖 Rhino 自带的实现。
            options.setRewritePolyfills(true);

            Result result = compiler.compile(
                    Collections.<SourceFile>emptyList(),
                    Collections.singletonList(SourceFile.fromCode("user-source.js", source)),
                    options);
            if (!result.success) {
                for (JSError error : result.errors) {
                    if (messages.size() >= 6) break;
                    messages.add(describe(error));
                }
                return new Outcome(source, false, messages);
            }
            String code = compiler.toSource();
            if (code == null || code.trim().isEmpty()) {
                messages.add("转译结果为空");
                return new Outcome(source, false, messages);
            }
            return new Outcome(code, true, messages);
        } catch (Throwable failure) {
            messages.add("转译器异常：" + failure);
            return new Outcome(source, false, messages);
        }
    }

    /** 把 Closure 的诊断压成一行「行:列 描述」，弹窗里放得下。 */
    private static String describe(JSError error) {
        StringBuilder sb = new StringBuilder();
        if (error.getLineNumber() > 0) sb.append(error.getLineNumber()).append(':').append(error.getCharno()).append(' ');
        sb.append(error.getDescription());
        return sb.length() > 220 ? sb.substring(0, 220) + "..." : sb.toString();
    }
}