package com.muoniumplayer.core.ncm.apple;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/**
 * amp_api.py 助手的 Java 侧封装：AMP API 的「真身请求」全部经由伴生整包里的
 * Python 解释器执行。
 *
 * <h3>为什么必须绕道 Python</h3>
 * <p>amp-api.music.apple.com 按 <b>TLS/JA3 指纹</b>拒绝 Java 客户端：Java 25 的
 * {@code HttpURLConnection} 打任何端点（连无需登录的 catalog 搜索都在内）稳定 401，
 * 而同一枚 token、同一组头在 Python（urllib / httpx，即 gamdl）下全部 200。
 * 逐头二分（UA / Origin / Referer / Cookie 形态 / Accept-Encoding）全部排除后
 * 唯一变量只剩 TLS ClientHello 指纹 —— 服务端把「Java 指纹 + media-user-token」
 * 组合判为盗用。修复不碰 TLS 栈：伴生整包自带完整 Python 运行时，
 * {@code amp_api.py} 以 Python 指纹发请求，Java 子进程调用拿 JSON 结果。</p>
 *
 * <h3>进程纪律</h3>
 * <p>{@code ExternalMediaService} 同款：stdin JSON / stdout JSON、UTF-8 显式、
 * 超时 destroy、绝不泄漏 cookie 内容到日志。调用方全部在后台线程
 * （probe / 搜索 / 歌单 / 歌词本来就都不在渲染线程）。</p>
 */
public final class AppleAmpHelper {

    /** 单次调用的宽限上限：搜索/歌单要一两跳网络，probe 两跳起步。 */
    private static final long TIMEOUT_MILLIS = 60_000L;

    private AppleAmpHelper() {
    }

    /** 助手脚本在整包里的位置（gamdl.exe 同目录）。 */
    public static File scriptOrNull() {
        File exe = AppleCompanionDownload.executableOrNull();
        if (exe == null) {
            exe = ApplePlaybackResolver.manualExecutableOrNull();
        }
        if (exe == null) return null;
        // 放 exe 同目录而不是 _internal/：那里面有 python312.dll，Windows 的 DLL 搜索
        // 顺序会让脚本目录抢先于系统 Python，系统 python 跑脚本直接报
        // "Module use of python312.dll conflicts"（实机踩中）。
        File script = new File(exe.getParentFile(), "amp_api.py");
        // 手动路径可能是任意 gamdl 安装（非本整包），没有助手时如实说没有。
        return script.isFile() ? script : null;
    }

    /** 助手可用性（设置页状态行用）。 */
    public static boolean available() {
        return pythonExecutableOrNull() != null && scriptOrNull() != null;
    }

    private static File pythonExecutableOrNull() {
        File exe = AppleCompanionDownload.executableOrNull();
        if (exe != null) {
            // 整包的 gamdl.exe 本身就是带 Python 运行时的引导器，但它只会跑 gamdl 主程序；
            // 跑任意脚本要用 _internal 里的 python.dll 对应的解释器 —— PyInstaller onedir
            // 不带独立 python.exe，改用「gamdl.exe 不可复用、系统 python 兜底」策略：
            // 优先系统 PATH 上的 python（开发与普通用户多数有），没有再放弃。
            for (String candidate : new String[]{"python", "python3", "py"}) {
                File found = resolveOnPath(candidate);
                if (found != null) return found;
            }
            return null;
        }
        return null;
    }

    private static File resolveOnPath(String name) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        for (String dir : pathEnv.split(";")) {
            if (dir.isBlank()) continue;
            for (String suffix : new String[]{".exe", ".bat", ""}) {
                File f = new File(dir.trim(), name + suffix);
                if (f.isFile()) return f;
            }
        }
        return null;
    }

    /**
     * 调一次助手。
     *
     * @param command 子命令（probe / search / libraryPlaylists / ...）
     * @param argsJson stdin 上的 JSON 参数
     * @return stdout 解析出的 JSON；助手缺失或执行失败返回 null（调用方按「无结果」处理）
     */
    public static JsonObject call(String command, String argsJson) {
        File script = scriptOrNull();
        File python = pythonExecutableOrNull();
        if (script == null || python == null) return null;
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    python.getAbsolutePath(), script.getAbsolutePath(), command);
            builder.redirectErrorStream(false);
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            // 助手走 Python 网络栈；宿主代理环境变量同样清掉（与 gamdl 同一纪律）。
            for (String proxyVar : new String[]{"ALL_PROXY", "all_proxy", "HTTP_PROXY",
                    "http_proxy", "HTTPS_PROXY", "https_proxy"}) {
                builder.environment().remove(proxyVar);
            }
            Process process = builder.start();
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(argsJson.getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder stdout = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    char[] buffer = new char[8192];
                    int read;
                    while ((read = r.read(buffer)) != -1) {
                        synchronized (stdout) {
                            stdout.append(buffer, 0, read);
                        }
                    }
                } catch (IOException ignored) {
                }
            }, "muonium-amp-helper-read");
            reader.setDaemon(true);
            reader.start();
            StringBuilder stderr = new StringBuilder();
            Thread errReader = new Thread(() -> drain(process.getErrorStream(), stderr),
                    "muonium-amp-helper-err");
            errReader.setDaemon(true);
            errReader.start();

            boolean exited = process.waitFor(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                System.err.println("[Music/Apple] amp 助手超时（" + command + "）");
                return null;
            }
            if (process.exitValue() != 0) {
                System.err.println("[Music/Apple] amp 助手退出码 " + process.exitValue()
                        + "（" + command + "）: " + tail(stderr));
                return null;
            }
            String payload;
            synchronized (stdout) {
                payload = stdout.toString().trim();
            }
            int brace = payload.indexOf('{');
            if (brace < 0) return null;
            JsonElement parsed = JsonParser.parseString(payload.substring(brace));
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Throwable throwable) {
            System.err.println("[Music/Apple] amp 助手调用失败（" + command + "）: " + throwable);
            return null;
        }
    }

    // ── JSON 便捷读取 ───────────────────────────────────────────────────────

    public static String text(JsonObject object, String member) {
        try {
            if (object == null || !object.has(member) || object.get(member).isJsonNull()) return "";
            return object.get(member).getAsString().trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static boolean bool(JsonObject object, String member) {
        try {
            return object != null && object.has(member) && object.get(member).isJsonPrimitive()
                    && object.get(member).getAsBoolean();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static JsonArray array(JsonObject object, String member) {
        try {
            if (object == null || !object.has(member) || object.get(member).isJsonNull()) {
                return new JsonArray();
            }
            return object.getAsJsonArray(member);
        } catch (Throwable ignored) {
            return new JsonArray();
        }
    }

    private static void drain(InputStream raw, StringBuilder tail) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(raw, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = r.read(buffer)) != -1) {
                synchronized (tail) {
                    if (tail.length() < 2000) tail.append(buffer, 0, read);
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static String tail(StringBuilder tail) {
        synchronized (tail) {
            String text = tail.toString().trim();
            return text.length() <= 400 ? text : text.substring(text.length() - 400);
        }
    }
}
