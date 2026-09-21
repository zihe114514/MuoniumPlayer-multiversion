package com.muoniumplayer.core.ncm.lxsource;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一个自定义源脚本的运行时：一条 JS 线程 + 一份 Rhino 作用域。
 *
 * <p>为什么必须是「一条线程」：脚本是单线程模型写的（Promise、setTimeout、闭包里的可变状态），
 * Rhino 的 {@code Context} 本身也是线程绑定的。HTTP 在另一组线程上跑，完成后把回调
 * <b>投递</b>回这条线程执行 —— 这就是 Electron 渲染进程里那套事件循环的最小复刻。</p>
 *
 * <p>用 {@link ScheduledExecutorService}（单线程）当事件循环，而不是自己写 while + 队列：
 * {@code setTimeout} / {@code setInterval} 要的延时与周期它本来就有，顺序也是「先到期先执行、
 * 同时到期按入队顺序」，和 JS 的宏任务队列一致。</p>
 */
public final class LxRuntime {

    /** 与上游 rendererEvent.ts 的 20 秒一致：脚本迟迟不回时要放行，别把播放卡死。 */
    public static final long REQUEST_TIMEOUT_MS = 20_000L;
    /** 脚本从开始执行到发出 inited 的时限。上游没有这个限制（它靠窗口一直挂着），但游戏里不能挂着。 */
    public static final long INIT_TIMEOUT_MS = 30_000L;
    /** 单个任务（一次脚本调用）允许连续执行多久。防的是 while(true) 把 JS 线程永久占死。 */
    private static final long TASK_WALL_CLOCK_LIMIT_MS = 15_000L;

    /*
     * ── 脚本给进来的值一律带预算 ──────────────────────────────────────────────
     * 指令观察器只能打断「脚本里跑的字节码」，管不到宿主自己在 Java 侧遍历脚本给的对象：
     * 一个 {@code {length: 2000000000}} 的假数组就能让下面这些循环空转到天荒地老，而那条
     * 唯一的 JS 线程一旦被占住，整套自定义源就废了（超时也救不回来，因为超时只让调用方放弃，
     * 线程还在转）。所以每个入口都要自己封顶。参照的是 qplayer 的 {@code PluginRuntime.fromJs}
     * （深度 / 节点数 / 字符串长度三重预算）。
     */
    /** {@code lx.utils.buffer.concat} 一次最多拼多少个元素。正常脚本是个位数。 */
    static final int MAX_CONCAT_ELEMENTS = 65_536;
    /** 拼出来的字节数上限。加密与 zlib 的中间产物顶多几 MB。 */
    static final int MAX_CONCAT_BYTES = 32 * 1024 * 1024;
    /** {@code setTimeout} 的额外参数个数上限。 */
    static final int MAX_TIMER_ARGS = 64;
    /** 请求头的条目数上限。 */
    static final int MAX_HEADER_ENTRIES = 128;
    /** 单个请求头值的字符上限。Cookie 是里面最长的那一个。 */
    static final int MAX_HEADER_VALUE_CHARS = 8_192;
    /** 脚本发来的 {@code inited} JSON 文本上限。上游那份只有几百字节。 */
    static final int MAX_INITED_JSON_CHARS = 65_536;
    /** 脚本发来的单次响应 JSON 文本上限。返回值本身已经在上游那套校验里限过长度。 */
    static final int MAX_RESPONSE_JSON_CHARS = 256 * 1024;

    /** 宿主桥在 Rhino 里的类名（{@code Context.javaToJS} 包装它时也要过 ClassShutter 这道门）。 */
    private static final String HOST_BRIDGE_CLASS = LxRuntime.class.getName() + "$Host";

    /**
     * 允许被交给脚本的 Java 类。除此之外一律不可见 —— 特别是 {@code java.lang.Runtime}
     * 这类「一句话拿到本机命令行」的。
     *
     * <p>新加一个会对脚本可见的 Java 返回类型时，要同时把它加进这里，否则脚本那边表现为
     * 「Access to Java class ... is prohibited」。{@link LxRuntimeSandboxTest} 会立刻跑到。</p>
     */
    private static boolean javaClassVisibleToScripts(String className) {
        return HOST_BRIDGE_CLASS.equals(className) || LxBuffer.class.getName().equals(className);
    }

    public enum State { LOADING, READY, FAILED, CLOSED }

    /** 脚本在 inited 里声明、并按上游三张表过滤之后，真正可用的一个源。 */
    public static final class SourceDecl {
        public final String key;
        public final String name;
        public final List<String> actions;
        public final List<String> qualitys;

        SourceDecl(String key, String name, List<String> actions, List<String> qualitys) {
            this.key = key;
            this.name = name;
            this.actions = Collections.unmodifiableList(actions);
            this.qualitys = Collections.unmodifiableList(qualitys);
        }

        public boolean supports(String action) {
            return actions.contains(action);
        }
    }

    /** 脚本主动发来的更新提示，读一次就清掉。 */
    public static final class UpdateAlert {
        public final String log;
        public final String updateUrl;

        UpdateAlert(String log, String updateUrl) {
            this.log = log;
            this.updateUrl = updateUrl;
        }
    }

    private static final AtomicInteger RUNTIME_SEQUENCE = new AtomicInteger();

    private final String scriptId;
    private final LxScriptInfo info;
    private final ScheduledExecutorService loop;
    private final ExecutorService httpPool;
    private final ContextFactory contextFactory;
    private final Host host = new Host();

    private final Map<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    private final Map<Integer, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();
    private final Map<Integer, LxHttp.Call> calls = new ConcurrentHashMap<>();
    private final AtomicInteger handleSequence = new AtomicInteger();
    private final AtomicLong requestSequence = new AtomicLong();
    private final CountDownLatch initLatch = new CountDownLatch(1);
    private final ArrayDeque<String> logs = new ArrayDeque<>();

    private volatile State state = State.LOADING;
    private volatile String error = "";
    private volatile Map<String, SourceDecl> sources = Collections.emptyMap();
    private volatile UpdateAlert updateAlert;
    private volatile boolean transpiled;
    /** 当前任务的起始时刻，指令观察器靠它判断是否跑飞了。0 表示当前不在脚本里。 */
    private volatile long taskStartedAt;

    private Scriptable scope;

    /**
     * 宿主扩展开关：允许脚本为非 {@code local} 的源提供 {@code lyric} / {@code pic}。
     *
     * <p>上游的动作白名单只给 {@code local} 这两个动作，而 {@code local} 在洛雪里指的是本机文件，
     * MuoniumPlayer 的曲目全来自远端平台，照抄那张表等于这两个动作永远用不上。开关默认关
     * （与上游一字不差），打开后由引导脚本按它放宽白名单 —— 放宽的只是「宿主肯问」，
     * 脚本没声明的动作照样不会被问到。</p>
     */
    private final boolean extraActions;

    private LxRuntime(String scriptId, LxScriptInfo info, boolean extraActions) {
        this.scriptId = scriptId;
        this.info = info;
        this.extraActions = extraActions;
        final int index = RUNTIME_SEQUENCE.incrementAndGet();
        this.loop = Executors.newSingleThreadScheduledExecutor(named("LX-Source-JS-" + index));
        this.httpPool = Executors.newCachedThreadPool(named("LX-Source-HTTP-" + index));
        this.contextFactory = new ContextFactory() {
            @Override
            protected Context makeContext() {
                Context cx = super.makeContext();
                cx.setLanguageVersion(Context.VERSION_ES6);
                // 解释执行：一是转译后的脚本只跑一次，编译成字节码不划算；二是指令观察器
                // （下面的 observeInstructionCount）只在解释模式下会被调用。
                cx.setOptimizationLevel(-1);
                cx.setInstructionObserverThreshold(200_000);
                // Java 的 String/Number/Boolean 默认会被包成 NativeJavaObject，于是
                // lx.utils.crypto.md5(x).length 拿到的是 Java 的 length() 方法而不是长度。
                // 关掉之后这些值在脚本里就是普通的 JS 原始值。
                cx.getWrapFactory().setJavaPrimitiveWrap(false);
                // 断掉脚本对 Java 类的按名查找：默认的 initStandardObjects 会给出 java / Packages
                // 这些顶层对象，脚本一句 java.lang.Runtime.getRuntime().exec(...) 就等于拿到了本机
                // 的命令行。这道门只拦「把 Java 类交给脚本」，白名单以外的类一律不可见。
                //
                // 为什么不是像 qplayer 那样恒返回 false：那边的桥是 BaseFunction（纯 JS 函数对象），
                // 从来不需要把 Java 对象交出去；这里的 __lxHost 是一个 Java 对象，Rhino 包它的时候
                // 同样会问 ClassShutter，恒 false 会连宿主自己的桥一起挡掉（实测报错
                // "Access to Java class ... LxRuntime$Host is prohibited"）。所以只放行确实要过界的
                // 那两个类，其余全拒。
                cx.setClassShutter(new ClassShutter() {
                    @Override
                    public boolean visibleToScripts(String className) {
                        return javaClassVisibleToScripts(className);
                    }
                });
                return cx;
            }

            @Override
            protected void observeInstructionCount(Context cx, int instructionCount) {
                long startedAt = taskStartedAt;
                if (startedAt != 0L && System.currentTimeMillis() - startedAt > TASK_WALL_CLOCK_LIMIT_MS) {
                    throw new IllegalStateException("脚本单次执行超过 "
                            + (TASK_WALL_CLOCK_LIMIT_MS / 1000) + " 秒，已中断（疑似死循环）");
                }
            }
        };
    }

    private static ThreadFactory named(final String name) {
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, name);
                // 守护线程：脚本挂住时不能拖着游戏进程不退出。
                thread.setDaemon(true);
                return thread;
            }
        };
    }
    // ════════════════════════════ 生命周期 ════════════════════════════

    /** 立刻返回；加载在 JS 线程上进行，结果用 {@link #awaitReady(long)} 等。 */
    public static LxRuntime start(String scriptId, LxScriptInfo info, final String rawSource,
                                  boolean extraActions) {
        final LxRuntime runtime = new LxRuntime(scriptId, info, extraActions);
        runtime.loop.execute(new Runnable() {
            @Override
            public void run() {
                runtime.load(rawSource);
            }
        });
        runtime.loop.schedule(new Runnable() {
            @Override
            public void run() {
                if (runtime.state == State.LOADING) {
                    runtime.fail("脚本在 " + (INIT_TIMEOUT_MS / 1000) + " 秒内没有发送 inited 事件");
                }
            }
        }, INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        return runtime;
    }

    private void load(String rawSource) {
        Context cx = contextFactory.enterContext();
        try {
            // 安全版的标准对象：顶层没有 java / Packages / JavaAdapter 这些互操作入口。
            // 与 makeContext 里那把 ClassShutter 是两道独立的门（一道拦「顶层就没有」，
            // 一道拦「按名字去找」），qplayer 的 PluginRuntime 也是两道一起上。
            scope = cx.initSafeStandardObjects(null, false);
            // Rhino 1.7 之前没有 globalThis，而引导脚本与绝大多数自定义源都按它来写。
            if (!ScriptableObject.hasProperty(scope, "globalThis")) {
                ScriptableObject.putProperty(scope, "globalThis", scope);
            }
            ScriptableObject.putProperty(scope, "__lxHost", Context.javaToJS(host, scope));
            ScriptableObject.putProperty(scope, "__lxScriptInfo", scriptInfoJson(rawSource));
            // 引导脚本据此决定动作白名单要不要放宽到 lyric / pic（默认不放宽，与上游一致）。
            ScriptableObject.putProperty(scope, "__lxExtraActions", Boolean.valueOf(extraActions));

            String bootstrap = readBootstrap();
            beginTask();
            cx.evaluateString(scope, bootstrap, "lx-bootstrap.js", 1, null);

            LxTranspiler.Outcome outcome = LxTranspiler.toEs5(rawSource);
            transpiled = outcome.transpiled;
            if (!outcome.transpiled && !outcome.messages.isEmpty()) {
                // 转译失败时仍然把原文交给 Rhino 试一次：万一脚本本来就是 ES5，
                // 报错反而是 Closure 自己的毛病。真的语法不合就在下面这行抛出来。
                log("warn", "转译失败，改用原始脚本：" + outcome.messages.get(0));
            }
            beginTask();
            cx.evaluateString(scope, outcome.code, "user-source.js", 1, null);
        } catch (Throwable failure) {
            fail(describe(failure));
        } finally {
            settle(cx);
        }
    }

    private String scriptInfoJson(String rawSource) {
        JsonObject json = new JsonObject();
        json.addProperty("name", info.getName());
        json.addProperty("description", info.getDescription());
        json.addProperty("version", info.getVersion());
        json.addProperty("author", info.getAuthor());
        json.addProperty("homepage", info.getHomepage());
        json.addProperty("rawScript", rawSource);
        return json.toString();
    }

    private static String readBootstrap() {
        InputStream stream = LxRuntime.class.getResourceAsStream("/muonium/lx/bootstrap.js");
        if (stream == null) throw new IllegalStateException("缺少内置资源 /muonium/lx/bootstrap.js");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) > 0) out.write(chunk, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception broken) {
            throw new IllegalStateException("读取内置引导脚本失败", broken);
        } finally {
            try {
                stream.close();
            } catch (Exception ignored) {
                // 读完即弃，关不上也不影响。
            }
        }
    }

    /** @return true 表示脚本已就绪；false 表示失败或超时（用 {@link #getError()} 看原因） */
    public boolean awaitReady(long timeoutMs) {
        try {
            if (!initLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        return state == State.READY;
    }

    public void shutdown() {
        state = State.CLOSED;
        initLatch.countDown();
        for (LxHttp.Call call : calls.values()) call.cancel();
        calls.clear();
        for (CompletableFuture<String> waiting : pending.values()) {
            waiting.completeExceptionally(new IllegalStateException("自定义源已停止"));
        }
        pending.clear();
        loop.shutdownNow();
        httpPool.shutdownNow();
    }

    /**
     * 把一件事交到 JS 线程上跑。运行时已经停掉时静默丢弃，不把异常抛回调用方线程。
     *
     * <p>为什么必须有这道：HTTP 回调是在 {@code LX-Source-HTTP-n} 线程上回来的，而关掉一个源
     * （重载、删除、改那颗扩展开关）只 {@code shutdownNow()} 各个池。正在路上的那个请求返回时
     * 池已终止，{@code execute} 会抛 {@link RejectedExecutionException}，从 HTTP 工作线程上逃
     * 出去 —— 拿真脚本边取流边重载时实测出现过。那一刻回调本来就该被丢掉（等结果的人已经走
     * 了），所以只吞、不换成线程栈。</p>
     */
    private void postToJsLoop(Runnable body) {
        try {
            loop.execute(body);
        } catch (RejectedExecutionException gone) {
            // 运行时已经关了，没人等这个结果。
        }
    }

    // ════════════════════════════ 对外查询 ════════════════════════════

    public String getScriptId() {
        return scriptId;
    }

    public LxScriptInfo getInfo() {
        return info;
    }

    public State getState() {
        return state;
    }

    public String getError() {
        return error;
    }

    public boolean isTranspiled() {
        return transpiled;
    }

    public Map<String, SourceDecl> getSources() {
        return sources;
    }

    /** 这个运行时起来时，宿主的 lyric / pic 扩展开关是开还是关。 */
    public boolean isExtraActionsEnabled() {
        return extraActions;
    }

    /** 读一次就清掉：更新提示只该弹一次。 */
    public UpdateAlert consumeUpdateAlert() {
        UpdateAlert alert = updateAlert;
        updateAlert = null;
        return alert;
    }

    /** 脚本 console 的最近若干行，脚本报错时是唯一能给用户看的线索。 */
    public List<String> getLogs() {
        synchronized (logs) {
            return new ArrayList<>(logs);
        }
    }

    // ════════════════════════════ 请求 ════════════════════════════

    /**
     * 同步向脚本发一次请求，语义与上游 {@code sendUserApiRequest} 一致。
     *
     * @param infoJson {@code info} 对象的 JSON 文本（musicUrl 是 {type, musicInfo}）
     * @return 结果 JSON：musicUrl 为 {type,url}，lyric 为四个歌词字段，pic 为 {url}
     * @throws LxSourceException 脚本报错、超时或运行时不可用
     */
    public String request(String source, String action, String infoJson) throws LxSourceException {
        if (state != State.READY) throw new LxSourceException("自定义源未就绪：" + (error.isEmpty() ? state : error));
        final String key = "request__" + requestSequence.incrementAndGet();
        final CompletableFuture<String> future = new CompletableFuture<>();
        pending.put(key, future);
        final String finalSource = source;
        final String finalAction = action;
        final String finalInfo = infoJson;
        try {
            loop.execute(new Runnable() {
                @Override
                public void run() {
                    dispatch(key, finalSource, finalAction, finalInfo);
                }
            });
        } catch (RejectedExecutionException gone) {
            // 状态刚检查完就被关掉：这里必须报错，不能干等 20 秒的超时。
            throw new LxSourceException("自定义源已停止");
        }
        try {
            return future.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            throw new LxSourceException("自定义源 " + (REQUEST_TIMEOUT_MS / 1000) + " 秒未返回结果");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LxSourceException("请求被中断");
        } catch (java.util.concurrent.ExecutionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            throw new LxSourceException(cause.getMessage() == null ? String.valueOf(cause) : cause.getMessage());
        } finally {
            pending.remove(key);
        }
    }

    private void dispatch(String key, String source, String action, String infoJson) {
        try {
            Context cx = Context.getCurrentContext();
            Object dispatcher = ScriptableObject.getProperty(scope, "__lxDispatch");
            if (!(dispatcher instanceof Function)) {
                host.onResponse(key, false, "引导脚本没有装载成功");
                return;
            }
            beginTask();
            ((Function) dispatcher).call(cx, scope, scope, new Object[]{key, source, action, infoJson});
        } catch (Throwable failure) {
            host.onResponse(key, false, describe(failure));
        } finally {
            settle(Context.getCurrentContext());
        }
    }
    // ════════════════════════════ 内部状态 ════════════════════════════

    private void beginTask() {
        taskStartedAt = System.currentTimeMillis();
    }

    private void endTask() {
        taskStartedAt = 0L;
    }

    /**
     * 一个宏任务的收尾：先清空微任务队列，再解除超时监视。
     *
     * <p>这一步不能省。Rhino 只在 {@code Context#evaluateString} 结束时自动清一次微任务队列，
     * 从 Java 直接 {@code Function#call} 进去<b>不会</b>触发（{@code ScriptRuntime.doTopCall}
     * 也不会）。少了它，脚本里每一个 {@code .then} 都会永远排在队列里等下一次 eval —— 表现是
     * 「脚本明明执行到了，宿主却永远收不到结果」，最后只能等 20 秒超时。</p>
     */
    private void settle(Context cx) {
        try {
            if (cx != null) cx.processMicrotasks();
        } catch (Throwable failure) {
            log("error", "微任务队列抛出异常：" + describe(failure));
        } finally {
            endTask();
        }
    }

    private void fail(String message) {
        if (state == State.CLOSED) return;
        state = State.FAILED;
        error = message == null || message.isEmpty() ? "脚本初始化失败" : message;
        log("error", error);
        initLatch.countDown();
    }

    private void log(String level, String message) {
        String line = message == null ? "" : (message.length() > 400 ? message.substring(0, 400) + "..." : message);
        synchronized (logs) {
            logs.addLast(level + " | " + line);
            while (logs.size() > 80) logs.removeFirst();
        }
        String prefix = "[LX/" + (info.getName().isEmpty() ? scriptId : info.getName()) + "] ";
        if ("error".equals(level) || "warn".equals(level)) System.err.println(prefix + line);
        else System.out.println(prefix + line);
    }

    /** Rhino 把脚本里的异常包成 RhinoException，直接 toString 会带一大段栈，只留第一行。 */
    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isEmpty()) message = failure.getClass().getSimpleName();
        int newline = message.indexOf('\n');
        if (newline > 0) message = message.substring(0, newline);
        return message.length() > 400 ? message.substring(0, 400) + "..." : message;
    }

    // ════════════════════════════ 给 JS 用的宿主桥 ════════════════════════════

    /**
     * 引导脚本里的 {@code host}。
     *
     * <p>必须是 public 的顶层可见类：Rhino 通过反射调用这些方法，包私有类上的 public 方法
     * 在部分 JDK 上会被 {@code setAccessible} 拦下。</p>
     */
    public final class Host {

        public void log(String level, String message) {
            LxRuntime.this.log(level, message);
        }

        public String userAgent() {
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/126.0.0.0 Safari/537.36";
        }

        // ---- 缓冲区 ----

        public LxBuffer bufferFrom(Object value, String encoding) {
            return LxBuffer.from(value, encoding);
        }

        public LxBuffer bufferAlloc(double size) {
            return LxBuffer.of(new byte[Math.max(0, (int) size)]);
        }

        public boolean isBuffer(Object value) {
            return value instanceof LxBuffer;
        }

        public LxBuffer bufferConcat(Object list) {
            if (!(list instanceof Scriptable)) return LxBuffer.of(new byte[0]);
            Scriptable array = (Scriptable) list;
            Object lengthValue = ScriptableObject.getProperty(array, "length");
            // length 是脚本自己填的：填个二十亿，下面这个循环就会在 Java 侧空转到天荒地老，
            // 而指令观察器只数 JS 字节码，救不了这条线程。先按上限截断。
            double rawLength = lengthValue == Scriptable.NOT_FOUND ? 0d : Context.toNumber(lengthValue);
            int count = rawLength <= 0d || Double.isNaN(rawLength)
                    ? 0 : (rawLength > MAX_CONCAT_ELEMENTS ? MAX_CONCAT_ELEMENTS : (int) rawLength);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int i = 0; i < count; i++) {
                Object element = ScriptableObject.getProperty(array, i);
                byte[] bytes = LxUtils.bytesOf(element == Scriptable.NOT_FOUND ? null : element);
                if (out.size() + bytes.length > MAX_CONCAT_BYTES) {
                    throw new IllegalArgumentException("buffer.concat 的结果超过 "
                            + (MAX_CONCAT_BYTES / 1024 / 1024) + " MB，已拒绝");
                }
                out.write(bytes, 0, bytes.length);
            }
            return LxBuffer.of(out.toByteArray());
        }

        // ---- 加密 / zlib ----

        public String md5(String text) {
            return LxUtils.md5(text);
        }

        public LxBuffer randomBytes(double size) {
            return LxUtils.randomBytes((int) size);
        }

        public LxBuffer aesEncrypt(Object data, String mode, Object key, Object iv) throws Exception {
            return LxUtils.aesEncrypt(data, mode, key, iv);
        }

        public LxBuffer rsaEncrypt(Object data, String key) throws Exception {
            return LxUtils.rsaEncrypt(data, key);
        }

        public LxBuffer inflate(Object data) throws Exception {
            return LxUtils.inflate(data);
        }

        public LxBuffer deflate(Object data) {
            return LxUtils.deflate(data);
        }

        // ---- 定时器 ----

        public int setTimer(final Object handler, double delay, boolean repeat, final Object extra) {
            final int id = handleSequence.incrementAndGet();
            long millis = Math.max(0L, (long) delay);
            final Object[] args = toArgs(extra);
            Runnable body = new Runnable() {
                @Override
                public void run() {
                    callFunction(handler, args);
                }
            };
            ScheduledFuture<?> future = repeat
                    ? loop.scheduleWithFixedDelay(body, millis, Math.max(1L, millis), TimeUnit.MILLISECONDS)
                    : loop.schedule(body, millis, TimeUnit.MILLISECONDS);
            timers.put(Integer.valueOf(id), future);
            return id;
        }

        public void clearTimer(Object id) {
            if (id == null || Undefined.isUndefined(id)) return;
            ScheduledFuture<?> future = timers.remove(Integer.valueOf((int) Context.toNumber(id)));
            if (future != null) future.cancel(false);
        }

        // ---- HTTP ----

        public int httpSend(String url, String method, Object headers, Object payload,
                            double timeout, final Object callback) {
            final int id = handleSequence.incrementAndGet();
            Map<String, String> headerMap = new LinkedHashMap<>();
            if (headers instanceof Scriptable) {
                Scriptable table = (Scriptable) headers;
                for (Object key : table.getIds()) {
                    if (!(key instanceof String)) continue;
                    // 请求头是脚本给的对象：条目数与单个值都要封顶，否则一份几万条的
                    // 「请求头」会在 Java 侧把这条线程占住，指令观察器管不到。
                    if (headerMap.size() >= MAX_HEADER_ENTRIES) break;
                    Object value = ScriptableObject.getProperty(table, (String) key);
                    if (value == null || value == Scriptable.NOT_FOUND || Undefined.isUndefined(value)) continue;
                    String text = Context.toString(value);
                    headerMap.put((String) key, text.length() > MAX_HEADER_VALUE_CHARS
                            ? text.substring(0, MAX_HEADER_VALUE_CHARS) : text);
                }
            }
            byte[] body = payload == null || Undefined.isUndefined(payload) ? null : LxUtils.bytesOf(payload);
            LxHttp.Call call = LxHttp.send(method, url, headerMap, body, (int) timeout, httpPool, new LxHttp.Sink() {
                @Override
                public void finished(final String failure, final LxHttp.Response response) {
                    calls.remove(Integer.valueOf(id));
                    postToJsLoop(new Runnable() {
                        @Override
                        public void run() {
                            deliverHttp(callback, failure, response);
                        }
                    });
                }
            });
            calls.put(Integer.valueOf(id), call);
            return id;
        }

        public void httpAbort(Object id) {
            if (id == null || Undefined.isUndefined(id)) return;
            LxHttp.Call call = calls.remove(Integer.valueOf((int) Context.toNumber(id)));
            if (call != null) call.cancel();
        }

        // ---- 事件回传 ----

        public void onInited(String json) {
            if (json != null && json.length() > MAX_INITED_JSON_CHARS) {
                fail("脚本发来的 inited 数据超过 " + (MAX_INITED_JSON_CHARS / 1024) + " KB，已拒绝");
                return;
            }
            try {
                Map<String, SourceDecl> parsed = new LinkedHashMap<>();
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                JsonObject declared = root.getAsJsonObject("sources");
                if (declared != null) {
                    for (Map.Entry<String, JsonElement> entry : declared.entrySet()) {
                        JsonObject value = entry.getValue().getAsJsonObject();
                        parsed.put(entry.getKey(), new SourceDecl(entry.getKey(),
                                value.has("name") ? value.get("name").getAsString() : entry.getKey(),
                                strings(value.getAsJsonArray("actions")),
                                strings(value.getAsJsonArray("qualitys"))));
                    }
                }
                sources = Collections.unmodifiableMap(parsed);
                if (parsed.isEmpty()) {
                    fail("脚本没有声明任何可用的音乐源（inited 的 sources 为空）");
                    return;
                }
                state = State.READY;
                error = "";
                log("log", "已就绪，可用源：" + String.join("、", parsed.keySet())
                        + (transpiled ? "（已转译为 ES5）" : "（未经转译）"));
                initLatch.countDown();
            } catch (Throwable broken) {
                fail("解析 inited 数据失败：" + describe(broken));
            }
        }

        public void onInitFailed(String message) {
            fail(message);
        }

        public void onResponse(String requestKey, boolean ok, String payload) {
            CompletableFuture<String> future = pending.get(requestKey);
            if (future == null) return;
            // 返回值在引导脚本里已经按上游那套校验限过长度，这里再兜一道：脚本要是绕过了
            // 引导脚本（比如自己拼了个超大 JSON），不能让它在 Java 堆上落地。
            if (ok && payload != null && payload.length() > MAX_RESPONSE_JSON_CHARS) {
                future.completeExceptionally(new LxSourceException("脚本返回的结果超过 "
                        + (MAX_RESPONSE_JSON_CHARS / 1024) + " KB，已拒绝"));
                return;
            }
            if (ok) future.complete(payload);
            else future.completeExceptionally(new LxSourceException(payload == null ? "failed" : payload));
        }

        public void onUpdateAlert(String log, String updateUrl) {
            updateAlert = new UpdateAlert(log, updateUrl);
        }
    }

    private static List<String> strings(JsonArray array) {
        List<String> out = new ArrayList<>();
        if (array != null) {
            for (JsonElement element : array) out.add(element.getAsString());
        }
        return out;
    }

    private Object[] toArgs(Object extra) {
        if (!(extra instanceof Scriptable)) return new Object[0];
        Scriptable array = (Scriptable) extra;
        Object lengthValue = ScriptableObject.getProperty(array, "length");
        // 同样是脚本给的 length：不截断就可能 new Object[二十亿] 直接把堆打穿。
        double rawLength = lengthValue == Scriptable.NOT_FOUND ? 0d : Context.toNumber(lengthValue);
        int count = rawLength <= 0d || Double.isNaN(rawLength)
                ? 0 : (rawLength > MAX_TIMER_ARGS ? MAX_TIMER_ARGS : (int) rawLength);
        Object[] args = new Object[count];
        for (int i = 0; i < args.length; i++) {
            Object element = ScriptableObject.getProperty(array, i);
            args[i] = element == Scriptable.NOT_FOUND ? Undefined.instance : element;
        }
        return args;
    }

    /** 定时器回调既可能是函数，也可能是一段字符串（老写法）。 */
    private void callFunction(Object handler, Object[] args) {
        try {
            Context cx = Context.getCurrentContext();
            beginTask();
            if (handler instanceof Function) {
                ((Function) handler).call(cx, scope, scope, args);
            } else if (handler != null) {
                cx.evaluateString(scope, Context.toString(handler), "setTimeout.js", 1, null);
            }
        } catch (Throwable failure) {
            log("error", "定时器回调抛出异常：" + describe(failure));
        } finally {
            settle(Context.getCurrentContext());
        }
    }

    /** HTTP 结果回到 JS 线程后，交给引导脚本拼出 resp 对象。 */
    private void deliverHttp(Object callback, String failure, LxHttp.Response response) {
        try {
            Context cx = Context.getCurrentContext();
            Object handler = ScriptableObject.getProperty(scope, "__lxOnHttpDone");
            if (!(handler instanceof Function)) return;
            Object[] args = failure != null
                    ? new Object[]{callback, failure, Integer.valueOf(0), "", "{}", LxBuffer.of(new byte[0])}
                    : new Object[]{callback, null, Integer.valueOf(response.status), response.message,
                            response.headersJson, LxBuffer.of(response.body)};
            beginTask();
            ((Function) handler).call(cx, scope, scope, args);
        } catch (Throwable broken) {
            log("error", "处理 HTTP 结果时抛出异常：" + describe(broken));
        } finally {
            settle(Context.getCurrentContext());
        }
    }
}