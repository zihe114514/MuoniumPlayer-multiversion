package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.settings.ConfigPaths;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Own-profile Chromium sign-in and YouTube-cookie handoff. */
public final class YouTubeBrowserLogin {

    public enum State { IDLE, OPENING, WAITING_FOR_BROWSER, READING_COOKIES, VERIFYING, DONE, FAILED }

    private static final Object LOCK = new Object();
    private static volatile State state = State.IDLE;
    private static volatile String message = "";
    private static volatile Process signInProcess;
    private static volatile boolean cancelled;
    private static volatile Thread worker;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Process process = signInProcess;
            if (process != null) process.destroy();
        }, "YouTube Browser Login Shutdown"));
    }

    private YouTubeBrowserLogin() { }

    public static State state() { return state; }
    public static String message() { return message; }
    public static boolean isBusy() {
        State current = state;
        return current == State.OPENING || current == State.WAITING_FOR_BROWSER
                || current == State.READING_COOKIES || current == State.VERIFYING;
    }

    /** Starts the browser flow on a daemon worker. The normal browser profile is never inspected. */
    public static boolean begin() {
        synchronized (LOCK) {
            if (isBusy() || (worker != null && worker.isAlive())) return false;
            cancelled = false;
            state = State.OPENING;
            message = "正在准备专用登录浏览器…";
            worker = new Thread(YouTubeBrowserLogin::runFlow, "YouTube Browser Login");
            worker.setDaemon(true);
            worker.start();
            return true;
        }
    }

    public static void cancel() {
        synchronized (LOCK) {
            cancelled = true;
            Process process = signInProcess;
            if (process != null) process.destroy();
            state = State.IDLE;
            message = "已取消浏览器登录";
        }
    }

    /** Removes only this feature's dedicated profile; does not follow links outside the profile. */
    public static void clearProfile() {
        cancel();
        Thread cleaner = new Thread(() -> {
            Thread activeWorker = worker;
            if (activeWorker != null && activeWorker != Thread.currentThread()) {
                try {
                    activeWorker.join(5000);
                    if (activeWorker.isAlive()) return;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            Process process = signInProcess;
            if (process != null) {
                try {
                    if (!process.waitFor(5, TimeUnit.SECONDS)) return;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            deleteOwnedProfile();
        }, "YouTube Browser Profile Cleanup");
        cleaner.setDaemon(true);
        cleaner.start();
    }

    private static void deleteOwnedProfile() {
        Path root = ConfigPaths.YOUTUBE_BROWSER_PROFILE.toPath().toAbsolutePath().normalize();
        if (!root.equals(new java.io.File("MuoniumPlayer/auth/youtube-browser-profile")
                .toPath().toAbsolutePath().normalize()) || !Files.exists(root)) return;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    Files.deleteIfExists(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                @Override public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException error)
                        throws IOException {
                    if (error != null) throw error;
                    Files.deleteIfExists(dir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException failure) {
            System.err.println("[Music/YouTube] 专用浏览器资料清理失败: " + failure.getMessage());
        }
    }

    private static void runFlow() {
        try {
            Path browser = findBrowser();
            if (browser == null) throw new IOException("没有找到 Chrome、Edge、Brave 或 Chromium 浏览器");
            Path profile = ConfigPaths.YOUTUBE_BROWSER_PROFILE.toPath().toAbsolutePath().normalize();
            Files.createDirectories(profile);
            List<String> args = new ArrayList<>();
            args.add(browser.toString());
            args.add("--user-data-dir=" + profile);
            args.add("--no-first-run");
            args.add("--no-default-browser-check");
            args.add("--disable-background-mode");
            args.add("https://music.youtube.com/");
            Process process;
            synchronized (LOCK) {
                if (cancelled) return;
                process = new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                signInProcess = process;
            }
            state = State.WAITING_FOR_BROWSER;
            message = "请在专用浏览器完成登录，然后关闭该浏览器窗口";
            boolean exited = process.waitFor(10, TimeUnit.MINUTES);
            if (!exited) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
                throw new IOException("等待登录浏览器关闭超时，请重新尝试");
            }
            if (cancelled) return;
            signInProcess = null;
            state = State.READING_COOKIES;
            message = "正在安全读取 YouTube 登录态…";
            String cookie = readYouTubeCookies(browser, profile);
            if (cookie.isEmpty()) throw new IOException("专用浏览器中未找到 YouTube 登录 Cookie");
            if (cancelled) return;
            state = State.VERIFYING;
            message = "正在验证 YouTube 账号…";
            YouTubeMusicService.LoginResult result = YouTubeMusicService.loginWithCookie(cookie);
            if (!result.ok) throw new IOException(result.message);
            state = State.DONE;
            message = "已登录：" + result.accountName;
        } catch (Throwable failure) {
            if (cancelled) return;
            state = State.FAILED;
            String detail = failure.getMessage();
            message = detail == null || detail.trim().isEmpty()
                    ? "浏览器登录失败（" + failure.getClass().getSimpleName() + "）" : detail;
        } finally {
            signInProcess = null;
            worker = null;
        }
    }

    static Path findBrowser() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<Path> candidates = new ArrayList<>();
        if (os.contains("win")) {
            addEnv(candidates, "PROGRAMFILES", "Google/Chrome/Application/chrome.exe");
            addEnv(candidates, "PROGRAMFILES(X86)", "Google/Chrome/Application/chrome.exe");
            addEnv(candidates, "LOCALAPPDATA", "Google/Chrome/Application/chrome.exe");
            addEnv(candidates, "PROGRAMFILES", "Microsoft/Edge/Application/msedge.exe");
            addEnv(candidates, "PROGRAMFILES(X86)", "Microsoft/Edge/Application/msedge.exe");
            addEnv(candidates, "LOCALAPPDATA", "Microsoft/Edge/Application/msedge.exe");
            addEnv(candidates, "PROGRAMFILES", "BraveSoftware/Brave-Browser/Application/brave.exe");
            addEnv(candidates, "LOCALAPPDATA", "BraveSoftware/Brave-Browser/Application/brave.exe");
        } else if (os.contains("mac")) {
            candidates.add(Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"));
            candidates.add(Path.of("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"));
            candidates.add(Path.of("/Applications/Brave Browser.app/Contents/MacOS/Brave Browser"));
            candidates.add(Path.of("/Applications/Chromium.app/Contents/MacOS/Chromium"));
        } else {
            for (String name : Arrays.asList("google-chrome", "google-chrome-stable", "microsoft-edge",
                    "brave-browser", "chromium", "chromium-browser")) {
                Path found = commandOnPath(name);
                if (found != null) candidates.add(found);
            }
        }
        for (Path candidate : candidates) if (Files.isRegularFile(candidate)) return candidate;
        return null;
    }

    private static void addEnv(List<Path> paths, String variable, String relative) {
        String base = System.getenv(variable);
        if (base != null && !base.trim().isEmpty()) paths.add(Path.of(base, relative));
    }

    private static Path commandOnPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String entry : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            Path candidate = Path.of(entry, name);
            if (Files.isExecutable(candidate)) return candidate;
        }
        return null;
    }

    static String readYouTubeCookies(Path browser, Path profile) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            port = socket.getLocalPort();
        }
        Process process = new ProcessBuilder(browser.toString(), "--headless=new", "--disable-gpu",
                "--no-first-run", "--no-default-browser-check", "--remote-allow-origins=*",
                "--remote-debugging-port=" + port, "--user-data-dir=" + profile, "about:blank")
                .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            JsonObject version = awaitDevTools(port, process);
            URI websocketUri = URI.create(version.get("webSocketDebuggerUrl").getAsString());
            return cookieHeaderFromDevtools(JsonParser.parseString(devtoolsCookies(websocketUri)).getAsJsonObject());
        } finally {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
        }
    }

    static String cookieHeaderFromDevtools(JsonObject packet) throws IOException {
        if (packet.has("error")) throw new IOException("浏览器拒绝读取 Cookie");
        JsonObject result = packet.getAsJsonObject("result");
        JsonArray cookies = result == null ? null : result.getAsJsonArray("cookies");
        if (cookies == null) return "";
        StringBuilder header = new StringBuilder();
        for (JsonElement element : cookies) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String domain = string(item, "domain").toLowerCase(Locale.ROOT);
            if (!(domain.equals("youtube.com") || domain.endsWith(".youtube.com"))) continue;
            String name = string(item, "name");
            String value = string(item, "value");
            if (name.isEmpty() || value.isEmpty()) continue;
            if (header.length() > 0) header.append("; ");
            header.append(name).append('=').append(value);
        }
        return header.toString();
    }

    private static JsonObject awaitDevTools(int port, Process process) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        IOException last = null;
        while (System.nanoTime() < end && process.isAlive()) {
            try {
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/json/version"))
                        .timeout(Duration.ofSeconds(1)).GET().build();
                java.net.http.HttpResponse<String> response = client.send(request,
                        java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 200) return JsonParser.parseString(response.body()).getAsJsonObject();
            } catch (IOException failure) {
                last = failure;
            }
            Thread.sleep(150);
        }
        throw new IOException("无法连接专用浏览器的本机 Cookie 读取接口", last);
    }

    private static String devtoolsCookies(URI websocketUri) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> response = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        WebSocket.Listener listener = new WebSocket.Listener() {
            private final StringBuilder text = new StringBuilder();
            @Override public void onOpen(WebSocket webSocket) {
                webSocket.sendText("{\"id\":1,\"method\":\"Storage.getCookies\"}", true);
                WebSocket.Listener.super.onOpen(webSocket);
            }
            @Override public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data,
                                                                            boolean last) {
                text.append(data);
                if (last) {
                    try {
                        JsonObject packet = JsonParser.parseString(text.toString()).getAsJsonObject();
                        if (packet.has("id") && packet.get("id").getAsLong() == 1L) {
                            response.set(text.toString());
                            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
                            done.countDown();
                        }
                    } catch (Throwable failure) {
                        error.set(failure);
                        done.countDown();
                    } finally { text.setLength(0); }
                }
                webSocket.request(1);
                return CompletableFuture.completedFuture(null);
            }
            @Override public void onError(WebSocket webSocket, Throwable failure) {
                error.set(failure);
                done.countDown();
            }
        };
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5)).buildAsync(websocketUri, listener).get(7, TimeUnit.SECONDS);
        socket.request(1);
        if (!done.await(10, TimeUnit.SECONDS)) throw new IOException("读取浏览器 Cookie 超时");
        if (error.get() != null) throw new IOException("读取浏览器 Cookie 失败", error.get());
        if (response.get() == null) throw new IOException("浏览器没有返回 Cookie");
        return response.get();
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }
}
