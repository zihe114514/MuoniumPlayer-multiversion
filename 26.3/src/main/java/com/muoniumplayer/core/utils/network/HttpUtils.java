package com.muoniumplayer.core.utils.network;

import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class HttpUtils {

    private static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 5000;
    private static final int DEFAULT_READ_TIMEOUT_MILLIS = 8000;

    /**
     * 手动跟随重定向的跳数上限。
     *
     * <p>{@link HttpURLConnection} 的自动跟随只在<b>同一协议内</b>生效，跨 http↔https 的 3xx
     * 会被原样抛给调用方。很多取流中继正是靠一条 301 把 http 升级成 https（例如长青这类
     * 脚本返回的 {@code http://…/wy.php?…}），不手动跟随就会把那 162 字节的跳转页当成音频
     * 写进缓存，最终表现为「一直播放失败」。</p>
     */
    private static final int MAX_REDIRECT_HOPS = 8;

    private static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    public static InputStream get(String url, Map<String, String> params) throws IOException {
        return get(url, params, null);
    }

    public static String getString(String url, Map<String, String> params) throws IOException {
        return readString(get(url, params));
    }

    public static String deleteString(String url, Map<String, String> params, Map<String, String> headers) throws IOException {
        return readString(delete(url, params, headers));
    }

    public static String putString(String url, Map<String, String> params, Map<String, String> headers) throws IOException {
        return readString(put(url, params, headers));
    }

    public static InputStream get(String url, Map<String, String> params, Map<String, String> headers) throws IOException {
        return request(mapToString(url, params, "?"), null, headers, "GET");
    }

    /**
     * 带显式超时的 GET。大型音频传输需要比默认 8 秒更长的读取窗口，
     * 否则缓慢的 CDN 会使传输停滞，导致曲目被报为无法播放。
     */
    public static InputStream get(String url, Map<String, String> params, Map<String, String> headers,
                                  int connectTimeoutMillis, int readTimeoutMillis) throws IOException {
        return get(url, params, headers, connectTimeoutMillis, readTimeoutMillis, null);
    }

    /** 带显式代理的 GET。传入 {@code null} 则保留历史行为的直连。 */
    public static InputStream get(String url, Map<String, String> params, Map<String, String> headers,
                                  int connectTimeoutMillis, int readTimeoutMillis,
                                  Proxy proxy) throws IOException {
        return request(mapToString(url, params, "?"), null, headers, "GET",
                "application/x-www-form-urlencoded", connectTimeoutMillis, readTimeoutMillis, proxy, 0);
    }

    public static String getString(String url, Map<String, String> params, Map<String, String> headers) throws IOException {
        return readString(get(url, params, headers));
    }

    public static void getAsync(String url, Map<String, String> params, OnHttpResult onHttpResult) {
        getAsync(url, params, null, onHttpResult);
    }

    public static void getAsync(String url, Map<String, String> params, Map<String, String> headers, OnHttpResult onHttpResult) {
        requestAsync(mapToString(url, params, "?"), null, headers, "GET", onHttpResult);
    }

    public static InputStream post(String url, Map<String, String> params) throws IOException {
        return post(url, params, null);
    }

    public static String postString(String url, Map<String, String> params) throws IOException {
        return readString(post(url, params));
    }

    public static String postString(String url, Map<String, String> params, Map<String, String> headers) throws IOException {
        return readString(post(url, params, headers));
    }

    public static InputStream post(String url, Map<String, String> params, Map<String, String> headers) throws IOException {
        return request(url, mapToString(null, params, null), headers, "POST");
    }

    public static void postAsyn(String url, Map<String, String> params, OnHttpResult onHttpResult) {
        postAsyn(url, params, null, onHttpResult);
    }

    public static void postAsyn(String url, Map<String, String> params, Map<String, String> headers, OnHttpResult onHttpResult) {
        requestAsync(url, mapToString(null, params, null), headers, "POST", onHttpResult);
    }

    public static InputStream put(String url, Map<String, String> params) throws IOException {
        return put(url, params, null);
    }

    public static InputStream put(String url, Map<String, String> params, Map<String, String> headers) throws IOException {
        return request(url, mapToString(null, params, null), headers, "PUT");
    }

    public static void putAsyn(String url, Map<String, String> params, OnHttpResult onHttpResult) {
        putAsyn(url, params, null, onHttpResult);
    }

    public static void putAsyn(String url, Map<String, String> params, Map<String, String> headers, OnHttpResult onHttpResult) {
        requestAsync(url, mapToString(null, params, null), headers, "PUT", onHttpResult);
    }

    public static InputStream delete(String url, Map<String, String> params) throws IOException {
        return delete(url, params, null);
    }

    public static InputStream delete(String url, Map<String, String> params, Map<String, String> headers) throws IOException {
        return request(mapToString(url, params, "?"), null, headers, "DELETE");
    }

    public static void deleteAsync(String url, Map<String, String> params, OnHttpResult onHttpResult) {
        deleteAsync(url, params, null, onHttpResult);
    }

    public static void deleteAsync(String url, Map<String, String> params, Map<String, String> headers, OnHttpResult onHttpResult) {
        requestAsync(mapToString(url, params, "?"), null, headers, "DELETE", onHttpResult);
    }

    public static InputStream request(String url, String params, Map<String, String> headers, String method) throws IOException {
        return request(url, params, headers, method, "application/x-www-form-urlencoded");
    }

    public static InputStream request(String url, String params, Map<String, String> headers, String method, String mediaType) throws IOException {
        return request(url, params, headers, method, mediaType,
                DEFAULT_CONNECT_TIMEOUT_MILLIS, DEFAULT_READ_TIMEOUT_MILLIS);
    }

    public static InputStream request(String url, String params, Map<String, String> headers, String method,
                                      String mediaType, int connectTimeoutMillis, int readTimeoutMillis) throws IOException {
        return request(url, params, headers, method, mediaType,
                connectTimeoutMillis, readTimeoutMillis, null, 0);
    }

    private static InputStream request(String url, String params, Map<String, String> headers, String method,
                                       String mediaType, int connectTimeoutMillis, int readTimeoutMillis,
                                       Proxy proxy, int redirectDepth) throws IOException {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        method = method.toUpperCase();
        OutputStreamWriter writer = null;
        URL httpUrl = new URL(url);
        HttpURLConnection conn = proxy == null
                ? (HttpURLConnection) httpUrl.openConnection()
                : (HttpURLConnection) httpUrl.openConnection(proxy);
        if (method.equals("POST") || method.equals("PUT")) {
            conn.setDoOutput(true);
            conn.setUseCaches(false);
        }
        conn.setReadTimeout(Math.max(1000, readTimeoutMillis));
        conn.setConnectTimeout(Math.max(1000, connectTimeoutMillis));
        conn.setRequestMethod(method);
//        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
//        conn.setRequestProperty("Accept-Charset", "utf-8");
//        conn.setRequestProperty("Content-Type", mediaType);
        // 添加请求�?
        if (headers != null) {
            for (String key : headers.keySet()) {
                conn.setRequestProperty(key, headers.get(key));
            }
        }
        // 添加参数
        if (params != null) {
            conn.setRequestProperty("Content-Length", String.valueOf(params.length()));
            // 请求体里有中文（搜索关键词、歌单名）。不写死 UTF-8 会按平台默认字符集编码，
            // 中文 Windows 客户端上就是 GBK，服务端收到乱码。
            writer = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8);
            writer.write(params);
            writer.flush();
        }

        BufferedInputStream bin;
        // 判断连接状�??
        if (conn.getResponseCode() >= 300) {
            int responseCode = conn.getResponseCode();
            String location = conn.getHeaderField("Location");
            // GET/HEAD 幂等：手动跟随跨协议或跨域的 3xx（见 MAX_REDIRECT_HOPS 注释）。
            // POST/PUT 保持原来的「非 2xx 直接抛」语义，不改既有调用方的错误处理。
            boolean followable = (method.equals("GET") || method.equals("HEAD"))
                    && isRedirect(responseCode) && location != null && !location.trim().isEmpty()
                    && redirectDepth < MAX_REDIRECT_HOPS;
            if (followable) {
                conn.disconnect();
                return request(new URL(httpUrl, location).toString(), params, headers, method,
                        mediaType, connectTimeoutMillis, readTimeoutMillis, proxy, redirectDepth + 1);
            }
            throw new RuntimeException("HTTP Request is not success, Response code is " + responseCode);
//            bin = new BufferedInputStream(conn.getErrorStream());
        }
        // 获取返回数据

//        ByteArrayOutputStream baos = new ByteArrayOutputStream();
//        int size;
//        byte[] buf = new byte[1024];
//        while ((size = bin.read(buf)) != -1) {
//            baos.write(buf, 0, size);
//        }
//
//        bin.close();

//        result = new ByteArrayInputStream(baos.toByteArray());

        int contentLength = conn.getContentLength();
        InputStream inputStream = conn.getInputStream();

        OutputStreamWriter finalWriter = writer;
        return new InputStream() {

            @Override
            public int read() throws IOException {
                return inputStream.read();
            }

            /*
             * 必须转发批量读：InputStream 的默认实现是「循环调用 read()」，于是每一个字节都要多走
             * 一层虚调用。所有走 HttpUtils 的传输（歌曲下载、封面、歌词、搜索 JSON）都经过这里，
             * 音频动辄几 MB，逐字节转发一度是整条链路上最贵的一段。
             */
            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                return inputStream.read(buffer, offset, length);
            }

            @Override
            public long skip(long count) throws IOException {
                return inputStream.skip(count);
            }

            /*
             * 语义保持不变：返回的是响应声明的总长度而不是「当前可无阻塞读取的字节数」。
             * WrappedInputStream 的进度分母、ShaderSource 的初始缓冲区容量都依赖这个既有行为。
             */
            @Override
            public int available() {
                return contentLength;
            }

            @Override
            public void close() throws IOException {
                inputStream.close();
                if (finalWriter != null) {
                    try {
                        finalWriter.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        };
    }

    @SneakyThrows
    public static InputStream download(String urlPath) {
        URL url = new URL(urlPath);

        URLConnection urlConnection = url.openConnection();
        HttpURLConnection httpURLConnection = (HttpURLConnection) urlConnection;
        httpURLConnection.setRequestMethod("GET");
        httpURLConnection.setRequestProperty("Charset", "UTF-8");
        httpURLConnection.connect();

        int fileLength = httpURLConnection.getContentLength();

        String fileName = httpURLConnection.getURL().getFile();

        InputStream inputStream = httpURLConnection.getInputStream();

        return new InputStream() {

            @Override
            public int read() throws IOException {
                return inputStream.read();
            }

            /** 同 {@code request}：不转发批量读会退化成逐字节。 */
            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                return inputStream.read(buffer, offset, length);
            }

            @Override
            public long skip(long count) throws IOException {
                return inputStream.skip(count);
            }

            @Override
            public int available() {
                return fileLength;
            }

            @Override
            public void close() throws IOException {
                inputStream.close();
            }
        };
    }

    public static void requestAsync(String url, String params, Map<String, String> headers, String method, OnHttpResult onHttpResult) {
        requestAsync(url, params, headers, method, "application/x-www-form-urlencoded", onHttpResult);
    }

    public static void requestAsync(String url, String params, Map<String, String> headers, String method, String mediaType, OnHttpResult onHttpResult) {
        MultiThreadingUtil.runAsync(() -> {
            try {
                InputStream result = request(url, params, headers, method, mediaType);
                onHttpResult.onSuccess(result);
            } catch (Exception e) {
                onHttpResult.onError(e.getMessage());
            }
        });
    }

    @SneakyThrows
    public static String mapToString(String url, Map<String, String> params, String first) {
        StringBuilder sb;
        if (url != null) {
            sb = new StringBuilder(url);
        } else {
            sb = new StringBuilder();
        }
        if (params != null) {
            boolean isFirst = true;
            for (String key : params.keySet()) {
                if (isFirst) {
                    if (first != null) {
                        sb.append(first);
                    }
                    isFirst = false;
                } else {
                    sb.append("&");
                }
                sb.append(URLEncoder.encode(key, StandardCharsets.UTF_8.name()));
                sb.append("=");
                sb.append(URLEncoder.encode(params.get(key), StandardCharsets.UTF_8.name()));
            }
        }
        return sb.toString();
    }

    @SneakyThrows
    public static String readString(InputStream stream) {
        StringBuilder sb = new StringBuilder();
        BufferedReader in = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            sb.append(line).append(System.lineSeparator());
        }
        return sb.toString();
    }
    @Getter
    @Setter
    private static int retryTimes = 10;

    public static InputStream downloadStream(String path) {
        return downloadStream(path, 0);
    }

    public static InputStream downloadStream(String path, int retry) {
        return downloadStream(path, retry, null);
    }

    public static InputStream downloadStream(String path, int retry, Proxy proxy) {
        InputStream bin = null;
        try {
            URL url = new URL(path);
            URLConnection urlConnection = proxy == null ? url.openConnection() : url.openConnection(proxy);
            HttpURLConnection httpURLConnection = (HttpURLConnection) urlConnection;
            httpURLConnection.setInstanceFollowRedirects(true);
            httpURLConnection.setRequestMethod("GET");
            httpURLConnection.setRequestProperty("Charset", "UTF-8");
            httpURLConnection.setReadTimeout(10 * 1000);
            httpURLConnection.connect();

            bin = httpURLConnection.getInputStream();
        } catch (Exception err) {
            if (retry >= retryTimes) {
                throw new RuntimeException("Max retry time reached for url " + path);
            }
            return downloadStream(path, ++retry, proxy);
        }
        return bin;
    }

    public interface OnHttpResult {
        void onSuccess(InputStream result);

        void onError(String message);
    }
}