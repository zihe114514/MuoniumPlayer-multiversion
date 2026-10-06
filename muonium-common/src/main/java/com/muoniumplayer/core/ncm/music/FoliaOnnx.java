package com.muoniumplayer.core.ncm.music;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.FileChannel;
import java.util.Collections;
import java.util.Map;
import java.util.Scanner;

/**
 * ONNX Runtime Java 绑定的反射门面 + 运行时加载器。
 *
 * <h2>为什么走反射而不是编译依赖</h2>
 * onnxruntime 的 Java API（{@code ai.onnxruntime.*}）不参与编译——它的运行时
 * 以安卓 AAR 抽包的形式单独分发，体积与 native 库都不适合进 mod 主包。
 * 门面把整个 ORT 表面收拢成五个静态方法（建会话、问输入、造张量、跑、关），
 * 反射点全在这一个文件里；调用方只见 {@code Object} 与 {@code float[]}。
 *
 * <h2>运行时从哪来</h2>
 * {@link FoliaModelManager} 下载 {@code folia-onnx-android-arm64.zip} 并解到
 * {@code models/onnx-rt/}，内容是 {@code onnxruntime-android} AAR 抽包——
 * {@code classes.jar} + {@code libonnxruntime.so} + {@code libonnxruntime4j_jni.so}。
 * 桌面不走这条路：那里的推理引擎是随包管理的 Python 子进程。
 *
 * <h2>安卓的 native 加载：属性 + 暂存目录</h2>
 * ORT 自己支持 {@code onnxruntime.native.path} 系统属性（<b>在 ORT 类初始化时读一次</b>，
 * 所以必须先设属性再碰任何 ORT 类）。同时安卓的 linker namespace 禁止从外置存储
 * dlopen，两个 .so 要先复制到 JVM 所在树/应用私有缓存（Zalith/FCL 的 tmpdir 都在
 * 允许清单里），再把属性指过去。
 *
 * <p>AAR 里那份加载器的实际语义（反编译 {@code ai.onnxruntime.OnnxRuntime} 确认）：
 * {@code java.vendor != "The Android Project"} 时它<b>不用</b> {@code System.loadLibrary}，
 * 而是在属性目录里找 {@code libonnxruntime.so} / {@code libonnxruntime4j_jni.so} 后
 * 逐个 {@code System.load(绝对路径)}——FCL/Zalith 的 OpenJDK 报 {@code java.vendor=N/A}，
 * 恰好走这条正文。缺 {@code *_providers_shared} 只在日志里留一条 warning，不影响初始化；
 * 真正的 Android APK 部署（runtime 报 "The Android Project"）会转去 {@code System.loadLibrary}，
 * 那是另一条投放渠道，与我们无关。
 */
final class FoliaOnnx {

    private static final String TAG = "[Automix/Folia/Onnx] ";

    private static boolean loaded;
    private static boolean loadFailed;
    /** 上次加载失败时运行包的指纹；包变了就允许再试一次（手动解压常发生在失败之后）。 */
    private static long failedStamp;
    private static URLClassLoader loader;
    private static Object environment;
    private static Method createSessionMethod;
    private static Method inputInfoMethod;
    private static Method outputInfoMethod;
    private static Method infoMethod;
    private static Method shapeMethod;
    private static Method createTensorMethod;
    private static Method runMethod;
    private static Method resultSize;
    private static Method resultGet;
    private static Method resultClose;
    private static Method valueMethod;
    private static Method closeSessionMethod;
    private static java.lang.reflect.Constructor<?> sessionOptionsCtor;
    private static Method setIntraOpThreads;
    private static Method setArenaAllocator;
    private static Method setMemPattern;

    private FoliaOnnx() {}

    /** 运行时是否可用（成功永久缓存；失败也缓存，但运行包一变就自动重试）。 */
    static synchronized boolean available() {
        if (loaded) return true;
        if (loadFailed && installStamp() == failedStamp) return false;
        loadFailed = false;
        try {
            loaded = load();
        } catch (Throwable t) {
            System.err.println(TAG + "运行时加载失败: " + t);
            loaded = false;
        }
        if (!loaded) {
            loadFailed = true;
            failedStamp = installStamp();
        }
        return loaded;
    }

    /** 运行包三件套的指纹（目录 mtime + 每个文件的大小与 mtime）；目录缺失为 -1。 */
    private static long installStamp() {
        File dir = FoliaModelManager.resolveOnnxRuntimeDir();
        if (dir == null || !dir.isDirectory()) return -1L;
        long stamp = dir.lastModified();
        for (String name : new String[] {"classes.jar", "libonnxruntime.so", "libonnxruntime4j_jni.so"}) {
            File file = FoliaModelManager.runtimeFile(dir, name);
            stamp = stamp * 31L + (file == null ? -1L : file.length() * 31L + file.lastModified());
        }
        return stamp;
    }

    private static boolean load() throws Exception {
        if (!FoliaModelManager.isAndroidPlatform()) {
            // 桌面不装 ONNX 运行时：这里的推理走 Python 子进程（见 preferOnnxInference 的注释）。
            return false;
        }
        File dir = FoliaModelManager.resolveOnnxRuntimeDir();
        if (dir == null || !dir.isDirectory()) {
            System.err.println(TAG + "运行时目录不存在: " + FoliaModelManager.onnxRuntimeDir().getAbsolutePath()
                    + " —— 把 folia-onnx-android-arm64.zip 解压到这里再试");
            return false;
        }
        File classes = FoliaModelManager.runtimeFile(dir, "classes.jar");
        File engine = FoliaModelManager.runtimeFile(dir, "libonnxruntime.so");
        File jni = FoliaModelManager.runtimeFile(dir, "libonnxruntime4j_jni.so");
        if (classes == null || engine == null || jni == null) {
            System.err.println(TAG + "运行包不完整，需要 classes.jar + libonnxruntime.so + "
                    + "libonnxruntime4j_jni.so 三件（缺失: "
                    + (classes == null ? "classes.jar " : "")
                    + (engine == null ? "libonnxruntime.so " : "")
                    + (jni == null ? "libonnxruntime4j_jni.so" : "") + "），当前目录 "
                    + dir.getAbsolutePath() + " 内容: " + listing(dir));
            return false;
        }
        File staged = stageNatives(engine, jni);
        if (staged == null) return false;
        // 必须在 ORT 类初始化之前设置：ORT 在 <clinit> 里读一次就不再看。
        System.setProperty("onnxruntime.native.path", staged.getAbsolutePath());
        URL[] classpath = new URL[] {classes.toURI().toURL()};
        loader = new URLClassLoader(classpath, FoliaOnnx.class.getClassLoader());
        Class<?> envClass = Class.forName("ai.onnxruntime.OrtEnvironment", true, loader);
        Class<?> sessionClass = Class.forName("ai.onnxruntime.OrtSession", true, loader);
        Class<?> optionsClass = Class.forName("ai.onnxruntime.OrtSession$SessionOptions", true, loader);
        Class<?> tensorClass = Class.forName("ai.onnxruntime.OnnxTensor", true, loader);
        environment = envClass.getMethod("getEnvironment").invoke(null);
        createSessionMethod = envClass.getMethod("createSession", String.class, optionsClass);
        sessionOptionsCtor = optionsClass.getDeclaredConstructor();
        setIntraOpThreads = optionsClass.getMethod("setIntraOpNumThreads", int.class);
        setArenaAllocator = optionsClass.getMethod("setCPUArenaAllocator", boolean.class);
        setMemPattern = optionsClass.getMethod("setMemoryPatternOptimization", boolean.class);
        inputInfoMethod = sessionClass.getMethod("getInputInfo");
        outputInfoMethod = sessionClass.getMethod("getOutputInfo");
        infoMethod = Class.forName("ai.onnxruntime.NodeInfo", true, loader).getMethod("getInfo");
        shapeMethod = Class.forName("ai.onnxruntime.TensorInfo", true, loader).getMethod("getShape");
        createTensorMethod = tensorClass.getMethod("createTensor",
                envClass, java.nio.FloatBuffer.class, long[].class);
        runMethod = sessionClass.getMethod("run", java.util.Map.class);
        closeSessionMethod = sessionClass.getMethod("close");
        Class<?> resultClass = Class.forName("ai.onnxruntime.OrtSession$Result", true, loader);
        resultSize = resultClass.getMethod("size");
        resultGet = resultClass.getMethod("get", int.class);
        resultClose = resultClass.getMethod("close");
        valueMethod = Class.forName("ai.onnxruntime.OnnxValue", true, loader).getMethod("getValue");
        System.out.println(TAG + "ONNX Runtime 就绪（android 运行时）");
        return true;
    }

    /**
     * 在运行时目录里找一个文件；找不到就再往下一层子目录看一眼。
     *
     * <p>解压工具常会「多套一层同名文件夹」（zip 根就是文件夹、或用户右键解压到
     * 同名目录），那种布局下三件套在 {@code onnx-rt/<某个子目录>/} 里。多认一层
     * 比报错让人重解压省事，也避免把「解压布局不对」误诊成「运行时装不上」。
     */
    private static String listing(File dir) {
        String[] names = dir.list();
        if (names == null || names.length == 0) return "(空)";
        StringBuilder text = new StringBuilder();
        int shown = 0;
        for (String name : names) {
            if (shown++ > 0) text.append(", ");
            if (shown > 8) { text.append("…"); break; }
            text.append(name);
        }
        return text.toString();
    }

    /**
     * 把两个 .so 复制到 linker namespace 允许的目录（优先 JVM 所在树，退回应用缓存）。
     *
     * <p>与 libskija 的暂存同一思路：外置存储上的 .so 无法被 JVM dlopen。这里不复用
     * Skija 的目录推导，是因为 ORT 走的是 {@code onnxruntime.native.path} 整目录
     * 加载，目录里必须正好是这两个文件，独立暂存最省心。
     */
    private static File stageNatives(File engine, File jni) {
        for (File root : stagingRoots()) {
            File target = new File(root, "muonium-onnx");
            if (target.isDirectory() || target.mkdirs()) {
                if (copyIfChanged(engine, new File(target, engine.getName()))
                        && copyIfChanged(jni, new File(target, jni.getName()))) {
                    System.out.println(TAG + "native 库暂存于 " + target.getAbsolutePath());
                    return target;
                }
            }
        }
        System.err.println(TAG + "找不到可写且 linker 允许的暂存目录");
        return null;
    }

    private static Iterable<File> stagingRoots() {
        java.util.List<File> roots = new java.util.ArrayList<>();
        try (Scanner scanner = new Scanner(
                new File("/proc/self/maps"), java.nio.charset.StandardCharsets.UTF_8)) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                if (line.endsWith("/libjvm.so")) {
                    int start = line.indexOf(" /");
                    if (start >= 0) {
                        File dir = new File(line.substring(start + 1).trim()).getParentFile();
                        if (dir != null && dir.isDirectory()) roots.add(dir);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 没有 procfs 的平台（桌面 Windows）本来就不走安卓布局。
        }
        String tmp = System.getProperty("java.io.tmpdir", "");
        if (!tmp.isBlank()) roots.add(new File(tmp));
        return roots;
    }

    private static boolean copyIfChanged(File source, File target) {
        try {
            if (target.isFile() && target.length() == source.length()) return true;
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
            try (FileInputStream in = new FileInputStream(source);
                 FileOutputStream out = new FileOutputStream(target);
                 FileChannel input = in.getChannel(); FileChannel output = out.getChannel()) {
                input.transferTo(0, source.length(), output);
            }
            return target.isFile() && target.length() == source.length();
        } catch (Throwable t) {
            System.err.println(TAG + "native 暂存失败 " + target.getName() + ": " + t);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 反射门面：调用方只见 Object 与基础类型
    // ------------------------------------------------------------------

    /** 建会话。低内存两开关对应 Python 参考实现里 arena/mem-pattern 两个 flag。 */
    static Object createSession(String modelPath, boolean lowMemory, int intraThreads) throws Exception {
        Object options = sessionOptionsCtor.newInstance();
        setIntraOpThreads.invoke(options, intraThreads);
        if (lowMemory) {
            setArenaAllocator.invoke(options, Boolean.FALSE);
            setMemPattern.invoke(options, Boolean.FALSE);
        }
        return createSessionMethod.invoke(environment, modelPath, options);
    }

    /** 首个输入名。ORT 的 InputInfo map 保持模型声明顺序。 */
    static String inputName(Object session) throws Exception {
        Map<?, ?> info = (Map<?, ?>) inputInfoMethod.invoke(session);
        for (Map.Entry<?, ?> entry : info.entrySet()) {
            return String.valueOf(entry.getKey());
        }
        throw new IllegalStateException("model has no inputs");
    }

    /** 输出个数（保持模型声明顺序；名字索引用 {@link #outputName}）。 */
    static int outputCount(Object session) throws Exception {
        return ((Map<?, ?>) outputInfoMethod.invoke(session)).size();
    }

    /** 第 index 个输出的名字。 */
    static String outputName(Object session, int index) throws Exception {
        int i = 0;
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) outputInfoMethod.invoke(session)).entrySet()) {
            if (i++ == index) return String.valueOf(entry.getKey());
        }
        throw new IndexOutOfBoundsException("output " + index);
    }

    /** 输入张量形状；导出时未固定的维度以 ORT 给的值（可能是负哨兵）原样返回。 */
    static long[] inputShape(Object session, String inputName) throws Exception {
        Object nodeInfo = ((Map<?, ?>) inputInfoMethod.invoke(session)).get(inputName);
        Object tensorInfo = infoMethod.invoke(nodeInfo);
        return (long[]) shapeMethod.invoke(tensorInfo);
    }

    /** 用 float 数组造一个张量；布局按 shape 行主序。 */
    static Object tensor(float[] data, long[] shape) throws Exception {
        return createTensorMethod.invoke(null, environment,
                java.nio.FloatBuffer.wrap(data), shape);
    }

    /**
     * 跑一次推理并关闭结果。返回每个输出 {@code getValue()} 的原始对象——
     * float32 输出按模型秩还原成 {@code float[]} / {@code float[][]} / {@code float[][][][]}。
     */
    static Object[] run(Object session, String inputName, Object tensor) throws Exception {
        Object result = runMethod.invoke(session, Collections.singletonMap(inputName, tensor));
        try {
            int count = ((Integer) resultSize.invoke(result));
            Object[] outputs = new Object[count];
            for (int i = 0; i < count; i++) {
                Object value = resultGet.invoke(result, i);
                outputs[i] = valueMethod.invoke(value);
            }
            return outputs;
        } finally {
            resultClose.invoke(result);
        }
    }

    static void closeSession(Object session) {
        try {
            closeSessionMethod.invoke(session);
        } catch (Throwable ignored) {}
    }

    /** 把 ORT getValue() 返回的嵌套 float 数组摊平成一维（参考实现的 reshape(-1)）。 */
    static float[] flatten(Object value) {
        if (value instanceof float[] flat) return flat;
        java.util.List<Float> out = new java.util.ArrayList<>();
        append(value, out);
        float[] flat = new float[out.size()];
        for (int i = 0; i < flat.length; i++) flat[i] = out.get(i);
        return flat;
    }

    private static void append(Object value, java.util.List<Float> out) {
        if (value instanceof float[] row) {
            for (float v : row) out.add(v);
        } else if (value instanceof Object[] nest) {
            for (Object child : nest) append(child, out);
        } else if (value instanceof Float f) {
            out.add(f);
        }
    }
}
