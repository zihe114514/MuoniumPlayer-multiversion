package com.muoniumplayer.core.ncm.lxsource;

import java.io.File;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 通过反射调用「按需下载的」Closure Compiler，把自定义源脚本降级成 Rhino 吃得下的 ES5。
 *
 * <h2>为什么是反射而不是直接依赖</h2>
 * closure-compiler 一个 jar 就有 13.7 MB（钉在 v20240317，剥掉 checkerframework 死重后），
 * 而全项目只有 LX 自定义源转译这一处用它——为这一个功能让十个发行渠道每个背上 12.6 MB
 * 不划算。于是它不再打进主包，改由 {@link ClosureCompilerDownload} 在用户进入自定义源
 * 界面时下载到 {@code config/muonium/libs/}，本类用一个独立的 {@link URLClassLoader}
 * 把它挂起来用。主包对它<b>零编译期依赖</b>（静态测试钉着 LxTranspiler 里不许出现
 * {@code com.google.javascript} 的 import），下载没完成时转译环节整体让位、原始脚本
 * 直送 Rhino——那条降级路径本来就有。
 *
 * <h2>反射面有多小</h2>
 * 六个类、约十个成员，全部来自 {@code LxTranspiler} 旧实现用过的那几个点：
 * {@code Compiler(PrintStream) / disableThreads / compile(List,List,Options) / toSource}、
 * {@code CompilerOptions} 的四个 setter、{@code WarningLevel.QUIET.setOptionsForWarningLevel}、
 * {@code SourceFile.fromCode}、{@code Result.success / errors} 与 {@code JSError} 的三个
 * getter。版本被 sha256 钉死，不存在「升级后 API 漂移」的问题。
 */
final class ClosureEs5Backend {

    /** 与旧实现的两次尝试对应：先 UNSTABLE（稳），失败再 UNSUPPORTED（宽）。 */
    static final String LANG_UNSTABLE = "UNSTABLE";
    static final String LANG_UNSUPPORTED = "UNSUPPORTED";

    private final ClassLoader loader;
    private final Class<?> compilerClass;
    private final Class<?> optionsClass;
    private final Object languageOutEs5;
    private final Object warningLevelQuiet;
    private final Method warningLevelApply;
    private final Method sourceFileFromCode;
    private final Method compilerDisableThreads;
    private final Method compilerCompile;
    private final Method compilerToSource;
    private final Method errorGetLineNumber;
    private final Method errorGetCharno;
    private final Method errorGetDescription;
    private final Field resultSuccess;
    private final Field resultErrors;

    private ClosureEs5Backend(ClassLoader loader, Class<?> compilerClass, Class<?> optionsClass,
                              Object languageOutEs5, Object warningLevelQuiet, Method warningLevelApply,
                              Method sourceFileFromCode, Method compilerDisableThreads,
                              Method compilerCompile, Method compilerToSource,
                              Method errorGetLineNumber, Method errorGetCharno,
                              Method errorGetDescription, Field resultSuccess, Field resultErrors) {
        this.loader = loader;
        this.compilerClass = compilerClass;
        this.optionsClass = optionsClass;
        this.languageOutEs5 = languageOutEs5;
        this.warningLevelQuiet = warningLevelQuiet;
        this.warningLevelApply = warningLevelApply;
        this.sourceFileFromCode = sourceFileFromCode;
        this.compilerDisableThreads = compilerDisableThreads;
        this.compilerCompile = compilerCompile;
        this.compilerToSource = compilerToSource;
        this.errorGetLineNumber = errorGetLineNumber;
        this.errorGetCharno = errorGetCharno;
        this.errorGetDescription = errorGetDescription;
        this.resultSuccess = resultSuccess;
        this.resultErrors = resultErrors;
    }

    /**
     * 从一个已校验的 jar 建 backend。任何一步失败都抛异常——调用方
     * （{@link ClosureCompilerDownload#backend()}）会把它压成「转译器不可用」，
     * 让转译环节整体走原始脚本降级，绝不让异常冒到脚本加载链路上。
     */
    static ClosureEs5Backend create(File jar) throws Exception {
        URLClassLoader loader = new URLClassLoader(new URL[]{jar.toURI().toURL()},
                ClosureEs5Backend.class.getClassLoader());
        loader.setDefaultAssertionStatus(false);
        try {
            Class<?> compiler = Class.forName("com.google.javascript.jscomp.Compiler", true, loader);
            Class<?> options = Class.forName("com.google.javascript.jscomp.CompilerOptions", true, loader);
            Class<?> languageMode = Class.forName(
                    "com.google.javascript.jscomp.CompilerOptions$LanguageMode", true, loader);
            Class<?> warningLevel = Class.forName(
                    "com.google.javascript.jscomp.WarningLevel", true, loader);
            Class<?> sourceFile = Class.forName(
                    "com.google.javascript.jscomp.SourceFile", true, loader);
            Class<?> resultClass = Class.forName("com.google.javascript.jscomp.Result", true, loader);
            Class<?> errorClass = Class.forName("com.google.javascript.jscomp.JSError", true, loader);

            Object outEs5 = enumConstant(languageMode, "ECMASCRIPT5");
            Object quiet = enumConstant(warningLevel, "QUIET");
            return new ClosureEs5Backend(loader, compiler, options, outEs5, quiet,
                    warningLevel.getMethod("setOptionsForWarningLevel", options),
                    sourceFile.getMethod("fromCode", String.class, String.class),
                    compiler.getMethod("disableThreads"),
                    compiler.getMethod("compile", List.class, List.class, options),
                    compiler.getMethod("toSource"),
                    errorClass.getMethod("getLineNumber"),
                    errorClass.getMethod("getCharno"),
                    errorClass.getMethod("getDescription"),
                    resultClass.getField("success"),
                    resultClass.getField("errors"));
        } catch (Throwable linkage) {
            try {
                loader.close();
            } catch (Throwable ignored) {
            }
            throw linkage;
        }
    }

    private static Object enumConstant(Class<?> enumClass, String name) throws Exception {
        for (Object constant : enumClass.getEnumConstants()) {
            if (name.equals(((Enum<?>) constant).name())) {
                return constant;
            }
        }
        throw new IllegalStateException("枚举里没有 " + enumClass.getName() + "." + name);
    }

    /**
     * 一次转译尝试。与旧 {@code LxTranspiler.attempt} 逐步对应：静音诊断流、只转译不优化、
     * 不进严格模式、按需注入 polyfill。
     *
     * @param languageIn {@link #LANG_UNSTABLE} 或 {@link #LANG_UNSUPPORTED}
     */
    LxTranspiler.Outcome attempt(String source, String languageIn) {
        List<String> messages = new ArrayList<>();
        try {
            // Closure 默认往 System.err 打诊断信息，转译用户脚本时那只会污染游戏日志。
            Object compiler = compilerClass.getConstructor(PrintStream.class)
                    .newInstance(new PrintStream(new OutputStream() {
                        @Override
                        public void write(int ignored) {
                        }
                    }, false, "UTF-8"));
            compilerDisableThreads.invoke(compiler);

            Object options = optionsClass.getDeclaredConstructor().newInstance();
            Class<?> languageMode = languageOutEs5.getClass();
            optionsClass.getMethod("setLanguageIn", languageMode)
                    .invoke(options, enumConstant(languageMode, languageIn));
            optionsClass.getMethod("setLanguageOut", languageMode).invoke(options, languageOutEs5);
            warningLevelApply.invoke(warningLevelQuiet, options);
            optionsClass.getMethod("setPrettyPrint", boolean.class).invoke(options, true);
            // 脚本顶层不能被塞进严格模式：上游是当普通 <script> 跑的，
            // 严格模式下 arguments.callee、给未声明变量赋值这类老写法会直接抛。
            optionsClass.getMethod("setEmitUseStrict", boolean.class).invoke(options, false);
            // 内建对象的 polyfill 按需注入：Rhino 1.7 缺 Object.fromEntries / Array.flat 这类方法，
            // Closure 注入的版本是「原生存在就用原生」的写法，不会覆盖 Rhino 自带的实现。
            optionsClass.getMethod("setRewritePolyfills", boolean.class).invoke(options, true);

            Object userSource = sourceFileFromCode.invoke(null, "user-source.js", source);
            Object result = compilerCompile.invoke(compiler,
                    Collections.emptyList(), Collections.singletonList(userSource), options);
            if (!(Boolean) resultSuccess.get(result)) {
                // v20240317 的 Result.errors 是 ImmutableList<JSError>（java.util.List），
                // 不是老文档写的 JSError[]——两种形态都吃，升级 closure 时这里不用跟着动。
                Object errorsObj = resultErrors.get(result);
                if (errorsObj instanceof Iterable<?> errors) {
                    for (Object error : errors) {
                        if (messages.size() >= 6) break;
                        messages.add(describe(error));
                    }
                } else if (errorsObj instanceof Object[] errors) {
                    for (Object error : errors) {
                        if (messages.size() >= 6) break;
                        messages.add(describe(error));
                    }
                }
                return new LxTranspiler.Outcome(source, false, messages);
            }
            String code = (String) compilerToSource.invoke(compiler);
            if (code == null || code.trim().isEmpty()) {
                messages.add("转译结果为空");
                return new LxTranspiler.Outcome(source, false, messages);
            }
            return new LxTranspiler.Outcome(code, true, messages);
        } catch (Throwable failure) {
            // 意料外异常（反射签名对不上、jarjar 内部状态崩了）：带堆栈进日志，
            // 但只当一次转译失败处理，绝不冒到脚本加载链路上。
            failure.printStackTrace();
            messages.add("转译器异常：" + failure);
            return new LxTranspiler.Outcome(source, false, messages);
        }
    }

    /** 把 Closure 的诊断压成一行「行:列 描述」，弹窗里放得下。 */
    private String describe(Object error) throws Exception {
        StringBuilder sb = new StringBuilder();
        int line = (Integer) errorGetLineNumber.invoke(error);
        int charno = (Integer) errorGetCharno.invoke(error);
        if (line > 0) {
            sb.append(line).append(':').append(charno).append(' ');
        }
        sb.append((String) errorGetDescription.invoke(error));
        return sb.length() > 220 ? sb.substring(0, 220) + "..." : sb.toString();
    }
}
