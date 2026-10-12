package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 本地签名后端（qs-backend）客户端。
 *
 * <p>汽水 {@code /luna/pc/*} 接口自 2026 年起要求逐请求的应用级签名头
 * {@code X-Helios}/{@code X-Medusa}，只有官方安全组件（客户端自带的
 * {@code bdms.node}）算得出来，Java 侧无法复现。qs-backend 把这件事
 * 抽成本机服务：加载官方 {@code bdms.node}，对模组指定的 PC 接口完成
 * 「拼 URL → 签名 → 带 Cookie 转发」，模组拿到的响应与真客户端一致。</p>
 *
 * <p>签名与 URL、请求头字节级绑定（改一个字节即作废），所以签名必须由
 * 最终发请求的一方完成——这正是它必须留在后端、不能让模组签名后自发的
 * 原因（模组的 HttpClient 无法复刻 bdms 的头序与字节）。</p>
 *
 * <p>后端不在时所有方法抛 {@link IOException}，由调用方回落到既有的
 * 免签名/匿名路径——遵循本包一贯的原则：回落必须显式记日志，绝不静默。</p>
 *
 * <p>地址：系统属性 {@code muonium.qishui.backend}，默认
 * {@code http://127.0.0.1:8787}。后端本体与部署说明见仓库 tools 文档。</p>
 */
public final class QishuiBackend {

    private static final String DEFAULT_BASE = "http://127.0.0.1:8787";
    private static final Duration TIMEOUT = Duration.ofSeconds(12);
    private static final long PROBE_INTERVAL_MILLIS = 30_000L;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private static final Gson GSON = new Gson();

    private static volatile long lastProbeAt;
    private static volatile boolean lastProbeAvailable;
    private static volatile boolean lastProbeHasCookie;
    /** 最近一次探活里后端自报的 pid / 父进程 pid / 是否带看门狗；0 / false = 未探到。 */
    private static volatile long lastProbeBackendPid;
    private static volatile long lastProbeParentPid;
    private static volatile boolean lastProbeWatchdog;
    /** 探活超时：真机健康检查是本机回环，正常在毫秒级；2 秒的等待只会让发起方白等。 */
    private static final Duration PROBE_TIMEOUT = Duration.ofMillis(800);

    private QishuiBackend() {
    }

    public static String baseUrl() {
        return System.getProperty("muonium.qishui.backend", DEFAULT_BASE);
    }

    /**
     * 最近一次探活里后端自报的进程 pid（0 = 没探到 / 对方是老版本）。
     * 只读，渲染路径可用。
     */
    static long peekBackendPid() {
        return lastProbeBackendPid;
    }

    /**
     * 最近一次探活里后端自报的父进程 pid（0 = 手动启动的后端，或对方是老版本）。
     *
     * <p>带看门狗拉起的后端会报它启动时看到的父进程（游戏 JVM）。这个值消失即代表
     * 「父进程死了而它居然还活着」——{@link QishuiBackendLifecycle} 拿它识别孤儿。</p>
     */
    static long peekParentPid() {
        return lastProbeParentPid;
    }

    /** 最近一次探活里后端是否自报带父进程看门狗（1.2.0 起才有这个字段）。 */
    static boolean peekWatchdog() {
        return lastProbeWatchdog;
    }

    /**
     * 只读最近一次探活结论，<b>绝不发起探测</b>。
     *
     * <p>渲染路径必须用这一个而不是 {@link #isAvailable()}：后者在 30 秒窗口的首次调用会
     * 同步发一次健康检查（还有 800 毫秒超时），探测失败时更会顺带触发伴生包下载调度 ——
     * 挂在每帧求值的文本 supplier 上就是「后端一卡，界面就卡」。</p>
     *
     * <p>返回 {@code false} 表示「最近一次探测说不可用」，也包含「还没探测过」。
     * 需要区分这两种情况（比如给用户文案）时用 {@link #hasProbed()}。</p>
     */
    public static boolean peekAvailable() {
        return lastProbeAvailable;
    }

    /** 是否已经探过至少一次。未探过时界面该显示中文中性文案，而不是断言后端不在。 */
    public static boolean hasProbed() {
        return lastProbeAt != 0L;
    }

    /** 只读最近一次探活里的 has_cookie，绝不发起探测（渲染路径可用）。 */
    public static boolean peekHasCookie() {
        return lastProbeAvailable && lastProbeHasCookie;
    }

    /**
     * 探测结果缓存 30 秒：游玩过程别每个请求都打健康检查。
     *
     * <p><b>会阻塞</b>（最多 {@link #PROBE_TIMEOUT}）：只在后台线程调用。渲染路径读
     * {@link #peekAvailable()}。</p>
     */
    public static boolean isAvailable() {
        long now = System.currentTimeMillis();
        if (now - lastProbeAt < PROBE_INTERVAL_MILLIS) return lastProbeAvailable;
        synchronized (QishuiBackend.class) {
            now = System.currentTimeMillis();
            if (now - lastProbeAt < PROBE_INTERVAL_MILLIS) return lastProbeAvailable;
            boolean ok;
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/health"))
                        .timeout(PROBE_TIMEOUT)
                        .GET().build();
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                // 后端是美化 JSON("ok": true 带空格),这里必须容忍空白差异——
                // 2026-10-11 真机:写死 "ok":true 无空格导致健康检查永远失败,
                // 后端明明活着,模组却一直按"后端缺失"处理(降级试听+重复拉起)。
                ok = response.statusCode() == 200
                        && response.body().replace(" ", "").contains("\"ok\":true");
                // 探活响应里就有 has_cookie,顺手解析:汽水源卡片的登录态提示要用。
                // 后端持有 Cookie 而模组会话库为空是常态(Cookie 由登录工具/后端 config 导入)。
                lastProbeHasCookie = ok && response.body().replace(" ", "").contains("\"has_cookie\":true");
                // 1.2.0 起后端自报身份与父进程:游戏侧靠它识别「上一局留下的孤儿后端」
                // 并决定要不要接管(见 QishuiBackendLifecycle.sweepOrphans)。
                // 老版本没有这些字段——解析不到就记 0/false,当作「不是我拉起的」处理。
                lastProbeBackendPid = ok ? readLongField(response.body(), "pid") : 0L;
                lastProbeParentPid = ok ? readLongField(response.body(), "parent_pid") : 0L;
                lastProbeWatchdog = ok
                        && response.body().replace(" ", "").contains("\"watchdog\":true");
            } catch (Throwable throwable) {
                ok = false;
            }
            lastProbeAvailable = ok;
            lastProbeAt = now;
            if (!ok) {
                lastProbeBackendPid = 0L;
                lastProbeParentPid = 0L;
                lastProbeWatchdog = false;
                // 本机没有可用后端:交给伴生包下载器(装包→拉起→健康检查→cookie 推送)。
                // 它自带尝试预算与冷却,不会每次探测都触发。
                QishuiCompanionDownload.onBackendMissing();
            }
            return ok;
        }
    }

    /**
     * 从后端 JSON 里取一个整数型字段；没有 / 不是数字 / 负数都返回 0。
     *
     * <p>手写解析而不是上 Gson：探活是热路径上最频繁的一次调用，而返回值只有三个
     * 固定字段，Gson 的反射开销不值得。容忍空白（后端输出的是美化 JSON）。</p>
     */
    private static long readLongField(String body, String field) {
        int at = body.indexOf("\"" + field + "\"");
        if (at < 0) return 0L;
        int colon = body.indexOf(':', at);
        if (colon < 0) return 0L;
        int end = colon + 1;
        while (end < body.length() && body.charAt(end) == ' ') end++;
        int start = end;
        while (end < body.length() && (Character.isDigit(body.charAt(end)) || body.charAt(end) == '-')) {
            end++;
        }
        if (start == end) return 0L;
        try {
            long value = Long.parseLong(body.substring(start, end));
            return value > 0 ? value : 0L;
        } catch (NumberFormatException overflow) {
            return 0L;
        }
    }

    /** 强制下次探测重跑(伴生包装好/拉起后立即调用,不等 30 秒缓存过期)。 */
    static void invalidateProbe() {
        lastProbeAt = 0L;
    }

    /**
     * 单次健康检查，<b>不带任何副作用</b>：不写探测缓存、失败也不触发伴生包下载。
     *
     * <p>给「等一个刚被杀掉的旧后端真正放开端口」这类场景用。普通的
     * {@link #isAvailable()} 不适合：它失败时会拉起 {@code onBackendMissing()}，
     * 在「孤儿已杀、新实例还没起」的空窗里正好会多派一个下载/拉起线程去抢 8787。</p>
     */
    static boolean probeAlive() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/health"))
                    .timeout(PROBE_TIMEOUT)
                    .GET().build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200
                    && response.body().replace(" ", "").contains("\"ok\":true");
        } catch (Throwable unreachable) {
            return false;
        }
    }

    /** 后端是否持有账号 Cookie(以最近一次探活为准;后端不可用时为 false)。 */
    public static boolean backendHasCookie() {
        return lastProbeAvailable && lastProbeHasCookie;
    }

    /** 伴生包把后端拉起后回调:立即重探,双向同步 Cookie(模组→后端配对,后端→模组回拉)。 */
    static void onBackendUp() {
        invalidateProbe();
        if (!isAvailable()) return;
        // 本地活动会话是用户当前选择：始终覆盖后端可能遗留的旧账号 Cookie。
        if (QishuiSessionStore.isLoggedIn()) {
            try {
                pushCookie(QishuiSessionStore.getCookieHeader(), true);
                QishuiMusicService.refreshProfile();
            } catch (Throwable error) {
                System.err.println("[Music/Qishui] Cookie 推送失败: " + error);
            }
        } else if (backendHasCookie()) {
            // 只有本地没有活动账号时，才把登录工具写入后端的账号回拉。
            try {
                pullCookieFromBackend();
            } catch (Throwable error) {
                System.err.println("[Music/Qishui] 从后端回拉 Cookie 失败: " + error);
            }
        }
    }

    /** 后端握有 Cookie 而模组没有时,把它拉进模组会话库(见 {@link #onBackendUp()})。 */
    private static void pullCookieFromBackend() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/cookie/full"))
                .timeout(TIMEOUT).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
        String cookie = body.has("cookie") ? body.get("cookie").getAsString() : "";
        if (cookie.isBlank()) return;
        Map<String, String> parsed = QishuiMusicService.parseCookieInput(cookie);
        if (parsed.isEmpty()) return;
        QishuiSessionStore.replaceCookies(parsed);
        QishuiMusicService.clearUserPlaylistsCache();
        QishuiMusicService.refreshProfile();
        QishuiAccountProfiles.saveCurrent();
        System.out.println("[Music/Qishui] 已从本机后端回拉 Cookie(" + cookie.length() + "B),账号功能解锁");
    }

    /** 后端没有 Cookie 而模组已登录时,把 Cookie 推过去(幂等,后端有就跳过)。 */
    static void syncCookieIfMissing() throws IOException, InterruptedException {
        pushCookie(QishuiSessionStore.getCookieHeader(), false);
    }

    /**
     * 把 Cookie 推给后端。
     *
     * <p>2026-10-11 真机教训:游戏内导入 Cookie 成功后如果没有人显式强推,
     * Cookie 就永远停在模组的会话库里,后端继续拿空 Cookie 打 1000016「登录失效」。
     * 所以导入入口必须调 {@code pushCookie(cookie, true)},不能指望下一次 onBackendUp。</p>
     *
     * @param force true 时后端已有 Cookie 也覆盖——游戏内导入的新 Cookie 是用户最新意图,
     *             必须盖过后端手里可能过期的旧凭据;false 只在后端缺 Cookie 时落笔。
     * @return 后端确认成功返回 true
     */
    static boolean pushCookie(String cookie, boolean force) throws IOException, InterruptedException {
        if (cookie == null || cookie.isBlank()) return false;
        if (!force) {
            HttpRequest getStatus = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/cookie"))
                    .timeout(TIMEOUT).GET().build();
            HttpResponse<String> status = HTTP.send(getStatus, HttpResponse.BodyHandlers.ofString());
            // 后端所有响应都是 JSON.stringify(obj, null, 1) 美化输出，键值对里有空格。
            // 与 isAvailable() 的 2026-10-11 教训同源：裸查 "has_cookie":false 永远匹配不上。
            if (status.statusCode() != 200
                    || !status.body().replace(" ", "").contains("\"has_cookie\":false")) {
                return false;
            }
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("cookie", cookie);
        payload.addProperty("source", "mod-pairing");
        HttpRequest push = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/cookie"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> pushed = HTTP.send(push, HttpResponse.BodyHandlers.ofString());
        // 同上：美化 JSON 必须先去空格再匹配，否则推送永远被当作失败。
        if (pushed.statusCode() == 200
                && pushed.body().replace(" ", "").contains("\"ok\":true")) {
            System.out.println("[Music/Qishui] 已把 Cookie 推送给本地后端(" + cookie.length() + "B"
                    + (force ? ",覆盖推送" : ",配对推送") + ")");
            return true;
        }
        System.err.println("[Music/Qishui] Cookie 推送被后端拒绝: HTTP " + pushed.statusCode()
                + " " + snippet(pushed.body()));
        return false;
    }

    /**
     * 走后端签名调用一条 {@code /luna/pc/*} 接口。
     *
     * @param path     接口路径（{@code /luna/pc/} 开头，后端有白名单校验）
     * @param params   追加在查询串里的业务参数（设备参数由后端统一补齐并参与签名）
     * @param jsonBody POST 的 JSON 体；GET 传 {@code null}
     * @return 汽水原始响应（与真客户端一致：顶层 {@code status_code/track/track_player…}）
     */
    static JsonObject signedPc(String path, Map<String, String> params, String jsonBody)
            throws IOException, InterruptedException {
        JsonObject payload = new JsonObject();
        payload.addProperty("path", path);
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                payload.addProperty(entry.getKey(), entry.getValue());
            }
        }
        if (jsonBody != null) {
            payload.add("body", JsonParser.parseString(jsonBody));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/pc"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("后端签名代理 HTTP " + response.statusCode() + ": "
                    + snippet(response.body()));
        }
        JsonObject envelope = JsonParser.parseString(response.body()).getAsJsonObject();
        int upstream = envelope.has("http_status") ? envelope.get("http_status").getAsInt() : 0;
        if (upstream < 200 || upstream >= 400) {
            throw new IOException("上游 HTTP " + upstream + ": " + snippet(envelope.toString()));
        }
        return envelope.has("body") && envelope.get("body").isJsonObject()
                ? envelope.getAsJsonObject("body")
                : new JsonObject();
    }

    /** 通用 POST:调后端任意 JSON 接口,返回解析后的对象;非 200 抛 IOException。 */
    static JsonObject postJson(String pathAndQuery, JsonObject payload)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + pathAndQuery))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("后端 HTTP " + response.statusCode() + ": " + snippet(response.body()));
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static String snippet(String text) {
        if (text == null) return "";
        String flat = text.replace('\n', ' ');
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "…";
    }
}
