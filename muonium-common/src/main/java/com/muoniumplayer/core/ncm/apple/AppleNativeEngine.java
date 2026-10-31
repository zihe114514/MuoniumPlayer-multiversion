package com.muoniumplayer.core.ncm.apple;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.apple.media.AppleCencDecryptor;
import com.muoniumplayer.core.ncm.apple.media.AppleSongAssembler;
import com.muoniumplayer.core.ncm.apple.protocol.AppleWidevineCdm;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯 Java 播放引擎：webplayback → ctrp256 流 → Widevine license → CENC 解密 →
 * JVM 重封装 → 缓存键位产物。入口 {@link #ensureLocalAudioFile} 与
 * 缓存键位契约与试听兜底链对齐，供
 * {@code CloudMusic.cachedAudioFileLocked} 的 muonium-apple:// 分派点选择优先走本引擎。
 *
 * <p>失败语义：返回 null 并把归因写入 {@link #lastFailureDetail()}，
 * 上层照旧走「备用引擎 → 换源 → 试听兜底」链。</p>
 */
public final class AppleNativeEngine {

    private static final String WEBPLAYBACK_URL =
            "https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/webPlayback";
    private static final String LICENSE_URL =
            "https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/acquireWebPlaybackLicense";
    private static final int LICENSE_TIMEOUT_MILLIS = 60_000;

    /** 同曲缓存键单飞（分键避免互堵）。 */
    private static final ConcurrentHashMap<String, Object> KEY_LOCKS = new ConcurrentHashMap<>();

    /** 产出下限:小于它的 m4a 视为坏块(截断/空流),不进缓存。 */
    static final long MIN_OUTPUT_BYTES = 512L * 1024L;

    private static volatile String lastFailureDetail = "";

    private AppleNativeEngine() {
    }

    public static String lastFailureDetail() {
        return lastFailureDetail;
    }

    private static void recordFailure(String detail) {
        lastFailureDetail = detail;
        System.err.println("[Music/Apple] 本地引擎: " + detail);
    }

    /** 内置 wvd 设备：优先用户外置路径,其次内嵌资源。返回 null 时引擎不可用。 */
    private static byte[] wvdDeviceBytes() {
        String override = AppleConfigStore.snapshot().wvdDevicePath;
        if (!override.isEmpty()) {
            try {
                return Files.readAllBytes(Path.of(override));
            } catch (IOException e) {
                recordFailure("外置 wvd 读取失败: " + e.getMessage());
                return null;
            }
        }
        try (var in = AppleNativeEngine.class.getResourceAsStream(
                "/muonium/apple/widevine_device.wvd")) {
            if (in == null) {
                recordFailure("内置 wvd 设备缺失(资源未打包)");
                return null;
            }
            return in.readAllBytes();
        } catch (IOException e) {
            recordFailure("内置 wvd 读取失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 确保缓存键位上有
     * 可播放的 .m4a;成功返回文件,失败返回 null(已记录归因)。
     */
    public static File ensureLocalAudioFile(File musicCacheDir, String cacheKey,
                                            com.muoniumplayer.core.ncm.music.dto.Music song,
                                            com.muoniumplayer.core.ncm.music.Quality quality) {
        String songId = song == null ? "" : song.getSourceId();
        if (cacheKey == null || cacheKey.isEmpty() || songId.isEmpty()) return null;
        byte[] cookies = AppleCookieSupport.cookiesFileOrNull() == null
                ? null : readCookiesQuietly();
        if (cookies == null) {
            recordFailure("cookies.txt 未导入(Apple 设置页粘贴 music.apple.com 的 cookies)");
            return null;
        }
        byte[] wvd = wvdDeviceBytes();
        if (wvd == null) return null;
        String mediaUserToken = extractMediaUserToken(cookies);
        if (mediaUserToken.isEmpty()) {
            recordFailure("cookies 里没有 media-user-token");
            return null;
        }

        Object keyLock = KEY_LOCKS.computeIfAbsent(cacheKey, k -> new Object());
        synchronized (keyLock) {
            File target = new File(musicCacheDir, cacheKey + ".m4a");
            if (target.isFile() && target.length() > MIN_OUTPUT_BYTES) {
                return target;
            }
            try {
                HttpClient client = AppleHttp.clientForPlay();
                String token = AppleTokenManager.current();
                byte[] m4a = fetchEncryptedFmp4AndAssemble(
                        client, token, mediaUserToken, songId, wvd);
                if (m4a == null) return null;
                Path tmp = target.toPath().resolveSibling(cacheKey + ".native.tmp");
                Files.write(tmp, m4a);
                Files.move(tmp, target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[Music/Apple] 本地引擎完成: " + song.getName()
                        + " (" + songId + ") → " + m4a.length + "B");
                return target;
            } catch (Exception e) {
                recordFailure(song.getName() + ": " + e.getMessage());
                return null;
            }
        }
    }

    private static byte[] readCookiesQuietly() {
        try {
            return Files.readAllBytes(AppleCookieSupport.cookiesFile().toPath());
        } catch (IOException e) {
            return null;
        }
    }

    private static String extractMediaUserToken(byte[] cookiesText) {
        for (String line : new String(cookiesText, StandardCharsets.UTF_8).split("\n")) {
            if (line.startsWith("#") || line.trim().isEmpty()) continue;
            String[] cols = line.split("\t");
            if (cols.length >= 7 && "media-user-token".equals(cols[5])) return cols[6].trim();
        }
        return "";
    }

    // ── HTTP(play.itunes 双端点:HttpClient 被指纹拒时回落系统 curl) ──────────

    private static HttpResponse<String> postJson(HttpClient client, String url,
                                                 String token, String mut, String body)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(LICENSE_TIMEOUT_MILLIS))
                .header("Authorization", "Bearer " + token)
                .header("Origin", AppleApiClient.HOMEPAGE_ORIGIN)
                .header("Referer", AppleApiClient.HOMEPAGE_ORIGIN + "/")
                .header("Content-Type", "application/json")
                .header("User-Agent", AppleHttp.USER_AGENT)
                .header("Cookie", "media-user-token=" + mut)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** curl 兜底:POST body 经 stdin 传入,返回响应体;非 2xx 返回 null。 */
    private static String curlPostJson(String url, String token, String mut, String body)
            throws IOException, InterruptedException {
        String curl = resolveCurl();
        if (curl == null) {
            recordFailure("license 传输找不到 curl(Win10+ 自带 System32\\curl.exe)");
            return null;
        }
        Path payload = Files.createTempFile("muonium-apple-license-", ".json");
        Files.write(payload, body.getBytes(StandardCharsets.UTF_8));
        Process proc = new ProcessBuilder(curl, "-s", "--connect-timeout", "15",
                "--max-time", "60", "-w", "\n%{http_code}", url,
                "--data-binary", "@" + payload.toAbsolutePath(),
                "-H", "Authorization: Bearer " + token,
                "-H", "Origin: " + AppleApiClient.HOMEPAGE_ORIGIN,
                "-H", "Referer: " + AppleApiClient.HOMEPAGE_ORIGIN + "/",
                "-H", "Content-Type: application/json",
                "-H", "User-Agent: " + AppleHttp.USER_AGENT,
                "-H", "Cookie: media-user-token=" + mut).start();
        byte[] out;
        try (var in = proc.getInputStream()) {
            out = in.readAllBytes();
        }
        String err;
        try (var ein = proc.getErrorStream()) {
            err = new String(ein.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!proc.waitFor(70, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            recordFailure("license curl 超时");
            return null;
        }
        if (proc.exitValue() != 0) {
            recordFailure("license curl exit=" + proc.exitValue()
                    + (err.isBlank() ? "" : " stderr=" + err.trim()));
            return null;
        }
        String text = new String(out, StandardCharsets.UTF_8);
        int sep = text.lastIndexOf('\n');
        String httpCode = sep >= 0 ? text.substring(sep + 1).trim() : "";
        String respBody = sep >= 0 ? text.substring(0, sep) : text;
        if (!httpCode.startsWith("2")) {
            recordFailure("license curl HTTP " + (httpCode.isEmpty() ? "?" : httpCode)
                    + " body尾=" + respBody.substring(Math.max(0, respBody.length() - 160)));
            return null;
        }
        if (respBody.isEmpty()) {
            recordFailure("license curl HTTP " + httpCode + " 空 body");
            return null;
        }
        if (Boolean.getBoolean("apple.native.diag")) {
            System.out.println("[Music/Apple] license curl http=" + httpCode
                    + " bodyLen=" + respBody.length());
        }
        return respBody;
    }

    // ── 管线四步 ────────────────────────────────────────────────────────────

    /** 步骤① webplayback → ctrp256 流 m3u8 URL;失败返回 null(已记录归因)。 */
    private static String webplaybackStreamUrl(HttpClient client, String token, String mut,
                                               String songId) throws Exception {
        HttpResponse<String> resp = postJson(client, WEBPLAYBACK_URL, token, mut,
                "{\"salableAdamId\":" + songId + ",\"language\":\"zh-CN\"}");
        if (resp.statusCode() != 200 || resp.body().contains("failureType")) {
            recordFailure("webplayback 拒绝(HTTP " + resp.statusCode()
                    + "),可能风控/会话过期");
            return null;
        }
        Matcher assets = Pattern.compile("\"assets\"\\s*:\\s*\\[").matcher(resp.body());
        if (!assets.find()) {
            recordFailure("webplayback 无 assets");
            return null;
        }
        int start = assets.end() - 1;
        int i = start + 1;
        while (i < resp.body().length()) {
            while (i < resp.body().length()
                    && (resp.body().charAt(i) == ','
                        || Character.isWhitespace(resp.body().charAt(i)))) i++;
            if (i >= resp.body().length() || resp.body().charAt(i) != '{') break;
            int depth = 0;
            int objStart = i;
            for (; i < resp.body().length(); i++) {
                char c = resp.body().charAt(i);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) break;
                }
            }
            String obj = resp.body().substring(objStart, Math.min(i + 1, resp.body().length()));
            if (obj.contains("28:ctrp256")) {
                Matcher u = Pattern.compile("\"URL\"\\s*:\\s*\"([^\"]+)\"").matcher(obj);
                return u.find() ? u.group(1).replace("\\/", "/") : null;
            }
            i++;
        }
        recordFailure("webplayback 无 ctrp256 资产");
        return null;
    }

    /** 步骤② 流 m3u8 → ISO-23001-7 KID 与物理媒体 URL。 */
    private static String[] parsePlaylist(HttpClient client, String streamUrl) throws Exception {
        HttpResponse<String> resp = client.send(HttpRequest.newBuilder(URI.create(streamUrl))
                        .timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String playlist = resp.body();
        Matcher keyM = Pattern.compile(
                "#EXT-X-KEY:METHOD=ISO-23001-7,URI=\"(data:;base64,[A-Za-z0-9+/=]+)\"")
                .matcher(playlist);
        if (!keyM.find()) {
            recordFailure("流 m3u8 无 ISO-23001-7 key");
            return null;
        }
        String dataUri = keyM.group(1);
        String mediaUrl = null;
        for (String line : playlist.split("\n")) {
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                mediaUrl = line.startsWith("http") ? line
                        : streamUrl.substring(0, streamUrl.lastIndexOf('/') + 1) + line;
                break;
            }
        }
        if (mediaUrl == null) {
            recordFailure("流 m3u8 无媒体分片");
            return null;
        }
        return new String[]{dataUri, mediaUrl};
    }

    /** 全管线:下载+解密+重封装,返回经典 m4a 字节;失败返回 null(已记录归因)。 */
    private static byte[] fetchEncryptedFmp4AndAssemble(HttpClient client, String token,
                                                        String mut, String songId,
                                                        byte[] wvd) throws Exception {
        String streamUrl = webplaybackStreamUrl(client, token, mut, songId);
        if (streamUrl == null) return null;
        HttpResponse<String> plResp = client.send(
                HttpRequest.newBuilder(URI.create(streamUrl))
                        .timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String playlist = plResp.body();
        Matcher keyM = Pattern.compile(
                "#EXT-X-KEY:METHOD=ISO-23001-7,URI=\"(data:;base64,[A-Za-z0-9+/=]+)\"")
                .matcher(playlist);
        if (!keyM.find()) {
            recordFailure("流 m3u8 无 ISO-23001-7 key");
            return null;
        }
        String dataUri = keyM.group(1);
        String mediaUrl = null;
        for (String line : playlist.split("\n")) {
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                mediaUrl = line.startsWith("http") ? line
                        : streamUrl.substring(0, streamUrl.lastIndexOf('/') + 1) + line;
                break;
            }
        }
        if (mediaUrl == null) {
            recordFailure("流 m3u8 无媒体分片");
            return null;
        }

        AppleWidevineCdm cdm = AppleWidevineCdm.fromWvd(wvd, 1);
        byte[] kid = Base64.getDecoder().decode(dataUri.substring(dataUri.indexOf(',') + 1));
        byte[] challenge = cdm.buildLicenseChallenge(
                AppleWidevineCdm.reconstructPsshInitData(kid),
                System.currentTimeMillis() / 1000L);
        String licBody = "{\"challenge\":\""
                + Base64.getEncoder().encodeToString(challenge)
                + "\",\"key-system\":\"com.widevine.alpha\",\"uri\":\"" + dataUri
                + "\",\"adamId\":\"" + songId + "\",\"isLibrary\":false,\"user-initiated\":true}";
        // license 交换与 webplayback 同主机:JVM HttpClient 直连(实测可过)。curl 仅在
        // JVM 路径异常时兜底;两条路径的响应码与 body 尾巴都写进归因,不留「无响应」盲区。
        String lic = null;
        int licCode = -1;
        try {
            HttpResponse<String> licResp = postJson(client, LICENSE_URL, token, mut, licBody);
            licCode = licResp.statusCode();
            if (licCode == 200 && !licResp.body().isEmpty()) lic = licResp.body();
        } catch (IOException | InterruptedException transportError) {
            if (transportError instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            recordFailure("license JVM 直连异常: " + transportError.getMessage());
        }
        if (lic == null) {
            recordFailure("license JVM 直连 HTTP " + licCode + " 未产出,转系统 curl 兜底");
            lic = curlPostJson(LICENSE_URL, token, mut, licBody);
        }
        if (lic == null) return null;
        Matcher stM = Pattern.compile("\"status\"\\s*:\\s*([0-9]+)").matcher(lic);
        int status = stM.find() ? Integer.parseInt(stM.group(1)) : -1;
        Matcher licM = Pattern.compile("\"license\"\\s*:\\s*\"([^\"]+)\"").matcher(lic);
        if (status != 0 || !licM.find()) {
            recordFailure("license 被拒(status=" + status
                    + ";每曲一次性,重复交换/风控都会拒)");
            return null;
        }
        Map<String, String> keys = cdm.parseLicense(Base64.getDecoder().decode(licM.group(1)));
        String keyHex = keys.values().iterator().next();
        byte[] key = hexToBytes(keyHex);

        // 下载整文件(单物理文件 BYTERANGE)
        HttpResponse<byte[]> media = client.send(
                HttpRequest.newBuilder(URI.create(mediaUrl))
                        .timeout(Duration.ofMinutes(3)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (media.statusCode() != 200) {
            recordFailure("媒体下载 HTTP " + media.statusCode());
            return null;
        }
        byte[] fmp4 = media.body();
        if (fmp4.length < MIN_OUTPUT_BYTES) {
            recordFailure("媒体文件过小(" + fmp4.length + "B)");
            return null;
        }
        // 解密由 assemble 内部按 fragment 完成(含 subsample);这里不要再过一次
        // decryptInPlace —— CTR 重复异或等于把明文重新加密,产物结构正确但音频是噪声。
        return AppleSongAssembler.assemble(fmp4, key);
    }

    /** curl 可执行解析:PATH → System32(Win10+ 自带)→ Git for Windows。找不到返回 null。 */
    private static String resolveCurl() {
        for (String candidate : java.util.List.of("curl",
                "C:\\Windows\\System32\\curl.exe",
                "C:\\Program Files\\Git\\mingw64\\bin\\curl.exe")) {
            try {
                Process probe = new ProcessBuilder(candidate, "--version").start();
                if (probe.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
                        && probe.exitValue() == 0) {
                    return candidate;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    static byte[] hexToBytes(String h) {
        byte[] o = new byte[h.length() / 2];
        for (int i = 0; i < o.length; i++) {
            o[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return o;
    }
}
