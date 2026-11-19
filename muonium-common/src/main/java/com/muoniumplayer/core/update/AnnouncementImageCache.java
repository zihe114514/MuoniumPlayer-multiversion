package com.muoniumplayer.core.update;

import com.muoniumplayer.render.skija.SkijaNatives;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Codec;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.EncodedImageFormat;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 一张公告浮层拥有一份缓存；下载和完整光栅解码都不在渲染线程上做。
 *
 * <p>渲染线程只调 request / peek / state / draw。draw 与 withImage 的整个借用区间持有 LOCK，
 * 发布、淘汰和 close 也走同一把锁；借用计数还保护回调内重入 close 的情况。Image 不交给调用方
 * 长期持有或关闭。worker 在锁外只创建 CPU 光栅对象，不建 DirectContext、GL 纹理或 Canvas。
 * GPU Canvas 的采样/上传由宿主 Skia 管理，不由本缓存创建或拥有 GPU 对象。</p>
 *
 * <p>宿主必须在退出动画结束、浮层真正移除后 close（不是按下 ESC 时）。close 不等待网络线程；
 * 所有任务带条目身份，迟到结果只能关闭，不能复活缓存。resize 不必清理，重新打开用新实例。
 * 失败在本实例内记住；逐帧 request 不会逐帧重试。图片和失败记账均有界。</p>
 */
public final class AnnouncementImageCache implements AutoCloseable {
    public static final int MAX_ENTRIES = 24;
    public static final int MAX_ENCODED_BYTES = 4 * 1024 * 1024;
    public static final int MAX_DIMENSION = 4096;
    public static final long MAX_PIXELS = 8_000_000L;
    private static final long MAX_CACHE_PIXELS = 16L * 1024 * 1024; // N32 resident pixels <= 64 MiB.
    private static final int MAX_FAILURES = 128;
    private static final int MAX_URL_LENGTH = 4096;
    private static final int MAX_REDIRECTS = 5;
    private static final int TIMEOUT_MILLIS = 5000;
    private static final long FETCH_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(15);

    public enum State { EMPTY, LOADING, READY, FAILED }

    /** 不含 native 引用的不可变快照，可以保留跨帧；EMPTY / FAILED 的尺寸为零。 */
    public record Entry(State state, int width, int height, String failure) {
    }

    private static final Entry EMPTY = new Entry(State.EMPTY, 0, 0, "");
    private final Object LOCK = new Object();
    // Insertion order is moved only by render-side reads, not by worker completion.
    private final LinkedHashMap<String, Slot> entries = new LinkedHashMap<>();
    private final LinkedHashMap<String, Entry> failures = new LinkedHashMap<>(16, .75f, true);
    private final ThreadPoolExecutor workers;
    private final int capacity;
    private final ConnectionFactory connections;
    private final Decoder decoder;
    private final ResourceLoader resources;
    private long residentPixels;
    private boolean closed;

    public AnnouncementImageCache() {
        this(MAX_ENTRIES, uri -> (HttpURLConnection) uri.toURL().openConnection(),
                AnnouncementImageCache::decodeRaster);
    }

    // Test transport/decoder injection keeps the same URL/header/byte policies; no production fallback.
    AnnouncementImageCache(int capacity, ConnectionFactory connections, Decoder decoder) {
        this(capacity, connections, decoder, AnnouncementImageCache.class::getResourceAsStream);
    }

    AnnouncementImageCache(int capacity, ConnectionFactory connections, Decoder decoder, ResourceLoader resources) {
        if (capacity < 1 || capacity > MAX_ENTRIES) throw new IllegalArgumentException("cache capacity");
        this.capacity = capacity;
        this.connections = Objects.requireNonNull(connections);
        this.decoder = Objects.requireNonNull(decoder);
        this.resources = Objects.requireNonNull(resources);
        AtomicInteger sequence = new AtomicInteger();
        workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_ENTRIES), task -> {
                    Thread thread = new Thread(task, "muonium-announcement-image-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** 非阻塞、去重的请求。无效 URL 不入队；失败须新建缓存后再试。 */
    public void request(String url) {
        enqueue(url, null);
    }

    /**
     * 显式本地探针入口：传入读取好的字节，不访问文件、不联网，也不放宽 URL / 图片策略。
     * 复制一份有界输入后入同一 worker 队列；不是发布用的演示图或失败回退。
     */
    public void prime(String url, byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length == 0 || encoded.length > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("image byte limit");
        }
        enqueue(url, encoded);
    }

    /** 只允许模组内置的两张赞助图；读包、校验和解码全部沿同一后台队列。 */
    public void requestResource(String resourcePath) {
        String resourceKey = resourceKey(resourcePath);
        if (resourceKey == null) return;
        enqueue(resourceKey, null, null, resourcePath);
    }

    public State resourceState(String resourcePath) {
        String resourceKey = resourceKey(resourcePath);
        return resourceKey == null ? State.FAILED : peekKey(resourceKey).state();
    }

    public void drawResource(String resourcePath, Canvas canvas, Rect destination,
                             double radius, boolean contain, float opacity) {
        String resourceKey = resourceKey(resourcePath);
        if (resourceKey != null) drawKey(resourceKey, canvas, destination, radius, contain, opacity);
    }

    /** 微信海报的白底赞赏码区域；保留原图字节与完整码的留白，放大时仍可展示完整海报。 */
    public void drawResource(String resourcePath, Canvas canvas, Rect destination,
                             double radius, boolean contain, float opacity, boolean focusWechatCode) {
        String resourceKey = resourceKey(resourcePath);
        if (resourceKey == null) return;
        Rect source = focusWechatCode && resourcePath.endsWith("/wechat-qr.jpg")
                ? Rect.makeXYWH(353, 190, 506, 506) : null;
        drawKey(resourceKey, canvas, destination, radius, contain, opacity, source);
    }

    public boolean withResourceImage(String resourcePath, Consumer<Image> paint) {
        Objects.requireNonNull(paint, "paint");
        String resourceKey = resourceKey(resourcePath);
        return resourceKey != null && withImageKey(resourceKey, paint);
    }

    private static String resourceKey(String path) {
        if (!"/assets/muonium/textures/sponsor/afdian-qr.png".equals(path)
                && !"/assets/muonium/textures/sponsor/wechat-qr.jpg".equals(path)) return null;
        return "resource:" + path;
    }

    private void enqueue(String url, byte[] encoded) {
        String key = key(url);
        if (key.isEmpty()) return;
        URI target = allowedUri(key);
        if (target == null) return;
        enqueue(key, target, encoded, null);
    }

    private void enqueue(String key, URI target, byte[] encoded, String resourcePath) {
        synchronized (LOCK) {
            if (closed || touch(key) != null || failures.containsKey(key)) return;
            while (entries.size() >= capacity) evictOldest();
            Slot slot = new Slot();
            // Copy only after deduplication, before publishing a task to another thread.
            byte[] seed = encoded == null ? null : encoded.clone();
            slot.task = new FutureTask<>(() -> {
                load(key, target, slot, seed, resourcePath);
                return null;
            });
            entries.put(key, slot);
            try {
                workers.execute(slot.task);
            } catch (RejectedExecutionException rejected) {
                entries.remove(key);
                retire(slot);
                rememberFailure(key, "image queue full");
            }
        }
    }

    public State state(String url) {
        return peek(url).state();
    }

    /** 只看快照，不隐式发请求，也不把原生 Image 泄漏到跨帧状态里。 */
    public Entry peek(String url) {
        String key = key(url);
        if (key.isEmpty()) return EMPTY;
        if (allowedUri(key) == null) return new Entry(State.FAILED, 0, 0, "image URL not allowed");
        return peekKey(key);
    }

    private Entry peekKey(String key) {
        synchronized (LOCK) {
            if (closed) return EMPTY;
            Slot slot = touch(key);
            if (slot != null) return slot.view;
            Entry failure = failures.get(key);
            return failure == null ? EMPTY : failure;
        }
    }

    /**
     * 只画已就绪的图片；contain 为 true 时完整等比放进目标（二维码不裁切），否则居中 cover。
     * 不拥有 canvas。save / restore 和 Paint 释放在异常路径也成对。
     */
    public void draw(String url, Canvas canvas, Rect destination, double radius, boolean contain, float opacity) {
        String key = key(url);
        if (allowedUri(key) == null) return;
        drawKey(key, canvas, destination, radius, contain, opacity);
    }

    private void drawKey(String key, Canvas canvas, Rect destination, double radius, boolean contain, float opacity) {
        drawKey(key, canvas, destination, radius, contain, opacity, null);
    }

    private void drawKey(String key, Canvas canvas, Rect destination, double radius, boolean contain,
                         float opacity, Rect focus) {
        if (canvas == null || destination == null || !Float.isFinite(opacity) || opacity <= 0) return;
        float width = destination.getWidth();
        float height = destination.getHeight();
        if (!Float.isFinite(width) || !Float.isFinite(height) || width <= 0 || height <= 0) return;
        withImageKey(key, image -> {
            Rect source = focus == null ? Rect.makeXYWH(0, 0, image.getWidth(), image.getHeight())
                    : clampSource(focus, image.getWidth(), image.getHeight());
            if (source == null) return;
            Rect target = fit(destination, source.getWidth(), source.getHeight(), contain);
            float rounding = Double.isFinite(radius) ? (float) Math.max(0, Math.min(radius, Math.min(width, height) / 2)) : 0;
            int saved = canvas.save();
            try (Paint paint = new Paint().setColor(0xFFFFFFFF).setAlphaf(Math.min(1, opacity))) {
                canvas.clipRRect(RRect.makeXYWH(destination.getLeft(), destination.getTop(), width, height, rounding), true);
                canvas.drawImageRect(image, source, target, SamplingMode.LINEAR, paint, true);
            } finally {
                canvas.restoreToCount(saved);
            }
        });
    }

    /**
     * READY Image 的短借用。回调只做同线程绘制，不保留 / close Image、不做 IO 或等待 worker。
     * 返回 false 表示无图；即使回调抛出或重入本缓存 close，最后一次借用之后才释放原生引用。
     */
    public boolean withImage(String url, Consumer<Image> paint) {
        Objects.requireNonNull(paint, "paint");
        String key = key(url);
        return allowedUri(key) != null && withImageKey(key, paint);
    }

    private boolean withImageKey(String key, Consumer<Image> paint) {
        synchronized (LOCK) {
            if (closed) return false;
            Slot slot = touch(key);
            if (slot == null || slot.view.state() != State.READY) return false;
            slot.borrowers++;
            try {
                paint.accept(slot.image);
            } finally {
                slot.borrowers--;
                releaseRetired(slot);
            }
            return true;
        }
    }

    /** 唯一失效/退出入口；不 join，不在渲染线程上断开或读取网络连接。幂等。 */
    @Override
    public void close() {
        synchronized (LOCK) {
            if (closed) return;
            closed = true;
            for (Slot slot : entries.values()) retire(slot);
            entries.clear();
            failures.clear();
            residentPixels = 0;
            workers.shutdownNow();
        }
    }

    private Slot touch(String key) {
        Slot slot = entries.remove(key);
        if (slot != null) entries.put(key, slot);
        return slot;
    }

    private void evictOldest() {
        Iterator<Map.Entry<String, Slot>> iterator = entries.entrySet().iterator();
        if (!iterator.hasNext()) return;
        Map.Entry<String, Slot> oldest = iterator.next();
        iterator.remove();
        residentPixels -= oldest.getValue().pixels;
        retire(oldest.getValue());
    }

    private void rememberFailure(String key, String reason) {
        failures.put(key, new Entry(State.FAILED, 0, 0, reason));
        while (failures.size() > MAX_FAILURES) failures.remove(failures.keySet().iterator().next());
    }

    private void retire(Slot slot) {
        slot.retired = true;
        if (slot.task != null) {
            slot.task.cancel(true);
            workers.remove(slot.task);
        }
        releaseRetired(slot);
    }

    private static void releaseRetired(Slot slot) {
        if (slot.retired && slot.borrowers == 0 && slot.image != null) {
            Image image = slot.image;
            slot.image = null;
            image.close();
        }
    }

    private void load(String key, URI target, Slot slot, byte[] seed, String resourcePath) {
        Image result = null;
        try {
            checkInterrupted();
            byte[] encoded;
            if (resourcePath != null) {
                try (InputStream input = resources.open(resourcePath)) {
                    if (input == null) throw new IOException("bundled image missing");
                    encoded = readBounded(input, -1, System.nanoTime() + FETCH_BUDGET_NANOS);
                }
            } else {
                encoded = seed == null ? fetch(target, connections) : seed;
            }
            Header header = inspect(encoded); // Pure Java policy precedes every native call.
            checkInterrupted();
            result = decoder.decode(encoded, header);
            if (result == null) throw new IOException("image decoder returned no image");
            long pixels = (long) header.width * header.height;
            synchronized (LOCK) {
                if (closed || slot.retired || entries.get(key) != slot) return;
                while (residentPixels + pixels > MAX_CACHE_PIXELS) evictOldest();
                if (slot.retired) return;
                slot.image = result;
                slot.pixels = pixels;
                slot.view = new Entry(State.READY, header.width, header.height, "");
                residentPixels += pixels;
                result = null; // Ownership transferred exactly once, under LOCK.
            }
        } catch (IOException | RuntimeException | LinkageError failure) {
            synchronized (LOCK) {
                if (!closed && !slot.retired && entries.get(key) == slot) {
                    entries.remove(key);
                    rememberFailure(key, failure.getClass().getSimpleName());
                }
            }
        } finally {
            if (result != null) result.close(); // Includes late completion, eviction and native exceptions.
        }
    }

    private static final class Slot {
        private Entry view = new Entry(State.LOADING, 0, 0, "");
        private FutureTask<Void> task;
        private Image image;
        private long pixels;
        private int borrowers;
        private boolean retired;
    }

    @FunctionalInterface
    interface ResourceLoader {
        InputStream open(String path) throws IOException;
    }

    @FunctionalInterface
    interface ConnectionFactory {
        HttpURLConnection open(URI uri) throws IOException;
    }

    @FunctionalInterface
    interface Decoder {
        Image decode(byte[] encoded, Header header) throws IOException;
    }

    /** Strict transport syntax in addition to the single centrally maintained host whitelist. */
    static URI allowedUri(String url) {
        if (url == null || url.length() > MAX_URL_LENGTH) return null;
        try {
            URI uri = new URI(url.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getRawAuthority().endsWith(":")
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !UpdateChecker.isAllowedPage(uri.toASCIIString())) return null;
            return uri;
        } catch (URISyntaxException invalid) {
            return null;
        }
    }

    private static String key(String url) {
        if (url == null || url.isBlank()) return "";
        // Oversized URLs never enter the maps or the task queue.
        return url.length() > MAX_URL_LENGTH ? "!invalid-image-url" : url.trim();
    }

    static byte[] fetch(URI start, ConnectionFactory connections) throws IOException {
        URI target = start;
        long deadline = System.nanoTime() + FETCH_BUDGET_NANOS;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            checkInterrupted();
            if (target == null || allowedUri(target.toString()) == null) throw new IOException("image URL not allowed");
            HttpURLConnection connection = connections.open(target);
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("GET");
                connection.setAllowUserInteraction(false);
                connection.setUseCaches(false);
                connection.setConnectTimeout(remainingTimeout(deadline));
                connection.setReadTimeout(remainingTimeout(deadline));
                connection.setRequestProperty("Accept", "image/png, image/jpeg, image/webp");
                connection.setRequestProperty("Accept-Encoding", "identity");
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (hop == MAX_REDIRECTS || location == null || location.isBlank()) throw new IOException("image redirect limit");
                    try {
                        target = target.resolve(new URI(location.trim()));
                    } catch (IllegalArgumentException | URISyntaxException invalid) {
                        throw new IOException("invalid image redirect", invalid);
                    }
                    // Validate before opening the next hop, including protocol-relative redirects.
                    if (allowedUri(target.toString()) == null) throw new IOException("image redirect not allowed");
                    continue;
                }
                if (code != HttpURLConnection.HTTP_OK) throw new IOException("image HTTP " + code);
                String encoding = connection.getHeaderField("Content-Encoding");
                if (encoding != null && !encoding.isBlank() && !"identity".equalsIgnoreCase(encoding.trim())) {
                    throw new IOException("encoded image response not allowed");
                }
                String mime = mime(connection.getContentType());
                if (!mime.isEmpty() && !mime.equals("application/octet-stream") && !mime.equals("image/png")
                        && !mime.equals("image/jpeg") && !mime.equals("image/webp")) throw new IOException("image MIME not allowed");
                long length = connection.getContentLengthLong();
                if (length == 0 || length > MAX_ENCODED_BYTES) throw new IOException("image byte limit");
                byte[] bytes;
                try (InputStream input = connection.getInputStream()) {
                    bytes = readBounded(input, length, deadline);
                }
                Header header = inspect(bytes);
                if (mime.startsWith("image/") && !mime.equals(header.format.mime)) throw new IOException("image MIME mismatch");
                return bytes;
            } finally {
                connection.disconnect(); // Worker only; never drain an unbounded error/redirect body.
            }
        }
        throw new IOException("image redirect limit");
    }

    static byte[] readBounded(InputStream input, long declaredLength, long deadline) throws IOException {
        if (declaredLength == 0 || declaredLength > MAX_ENCODED_BYTES) throw new IOException("image byte limit");
        ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        while (true) {
            checkInterrupted();
            remainingTimeout(deadline);
            // At the exact cap, read just one extra byte to distinguish EOF from overflow.
            int count = input.read(buffer, 0, Math.min(buffer.length, MAX_ENCODED_BYTES - output.size() + 1));
            if (count < 0) break;
            if (count > MAX_ENCODED_BYTES - output.size()) throw new IOException("image byte limit");
            output.write(buffer, 0, count);
        }
        if (output.size() == 0 || (declaredLength >= 0 && output.size() != declaredLength)) {
            throw new IOException("incomplete image response");
        }
        return output.toByteArray();
    }

    private static int remainingTimeout(long deadline) throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("image fetch timeout");
        return (int) Math.max(1, Math.min(TIMEOUT_MILLIS, TimeUnit.NANOSECONDS.toMillis(remaining)));
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("image request cancelled");
    }

    private static String mime(String value) {
        if (value == null) return "";
        return value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    enum Format {
        PNG("image/png"), JPEG("image/jpeg"), WEBP("image/webp");
        final String mime;
        Format(String mime) { this.mime = mime; }
    }

    record Header(Format format, int width, int height) {
    }

    /** Magic/size inspection without ImageIO, native allocation, extension or Content-Type trust. */
    static Header inspect(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_ENCODED_BYTES) throw new IOException("image byte limit");
        if (bytes.length >= 33 && u8(bytes, 0) == 137 && ascii(bytes, 1, "PNG\r\n\u001a\n")) {
            if (be32(bytes, 8) != 13 || !ascii(bytes, 12, "IHDR")) throw new IOException("invalid PNG header");
            return header(Format.PNG, be32(bytes, 16), be32(bytes, 20));
        }
        if (bytes.length >= 4 && u8(bytes, 0) == 255 && u8(bytes, 1) == 216) {
            int offset = 2;
            while (offset < bytes.length) {
                if (u8(bytes, offset++) != 255) throw new IOException("invalid JPEG marker");
                while (offset < bytes.length && u8(bytes, offset) == 255) offset++;
                if (offset >= bytes.length) break;
                int marker = u8(bytes, offset++);
                if (marker == 0 || marker == 216 || marker == 217 || marker == 218) break;
                if (marker == 1 || (marker >= 208 && marker <= 215)) continue;
                if (offset + 2 > bytes.length) break;
                int size = be16(bytes, offset);
                if (size < 2 || size > bytes.length - offset) break;
                boolean sof = marker >= 192 && marker <= 207 && marker != 196 && marker != 200 && marker != 204;
                if (sof) {
                    if (size < 8) break;
                    return header(Format.JPEG, be16(bytes, offset + 5), be16(bytes, offset + 3));
                }
                offset += size;
            }
            throw new IOException("JPEG dimensions missing");
        }
        if (bytes.length >= 20 && ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP")) {
            if (le32(bytes, 4) != bytes.length - 8L) throw new IOException("invalid WebP container size");
            int offset = 12;
            Header dimensions = null;
            while (offset <= bytes.length - 8) {
                long size = le32(bytes, offset + 4);
                int data = offset + 8;
                if (size > bytes.length - data) throw new IOException("invalid WebP chunk size");
                Header chunk = null;
                if (ascii(bytes, offset, "VP8X")) {
                    if (size != 10 || (u8(bytes, data) & 2) != 0) throw new IOException("animated/invalid WebP");
                    chunk = header(Format.WEBP, le24(bytes, data + 4) + 1, le24(bytes, data + 7) + 1);
                } else if (ascii(bytes, offset, "VP8L")) {
                    if (size < 5 || u8(bytes, data) != 47) throw new IOException("invalid lossless WebP");
                    long bits = le32(bytes, data + 1);
                    chunk = header(Format.WEBP, (bits & 16383) + 1, ((bits >>> 14) & 16383) + 1);
                } else if (ascii(bytes, offset, "VP8 ")) {
                    if (size < 10 || (u8(bytes, data) & 1) != 0 || u8(bytes, data + 3) != 157
                            || u8(bytes, data + 4) != 1 || u8(bytes, data + 5) != 42) throw new IOException("invalid lossy WebP");
                    chunk = header(Format.WEBP, le16(bytes, data + 6) & 16383, le16(bytes, data + 8) & 16383);
                }
                if (chunk != null) {
                    if (dimensions != null && (chunk.width != dimensions.width || chunk.height != dimensions.height)) {
                        throw new IOException("WebP dimensions mismatch");
                    }
                    dimensions = chunk;
                }
                long next = data + size + (size & 1);
                if (next > bytes.length) throw new IOException("invalid WebP padding");
                offset = (int) next;
            }
            if (offset != bytes.length || dimensions == null) throw new IOException("WebP dimensions missing");
            return dimensions;
        }
        throw new IOException("image format not allowed");
    }

    private static Header header(Format format, long width, long height) throws IOException {
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION || width * height > MAX_PIXELS) {
            throw new IOException("image dimensions limit");
        }
        return new Header(format, (int) width, (int) height);
    }

    /** Complete native decode into immutable N32 pixels; never return a lazy encoded Image. */
    static Image decodeRaster(byte[] encoded, Header header) throws IOException {
        if (!SkijaNatives.ensureLoaded()) throw new IOException("Skija unavailable");
        Image result = null;
        try (Data data = Data.makeFromBytes(encoded);
             Codec codec = Codec.makeFromData(data);
             Bitmap bitmap = new Bitmap()) {
            ImageInfo info = codec.getImageInfo();
            header(header.format, info.getWidth(), info.getHeight());
            EncodedImageFormat format = codec.getEncodedImageFormat();
            if (info.getWidth() != header.width || info.getHeight() != header.height
                    || !format.name().equals(header.format.name()) || codec.getFrameCount() > 1) {
                throw new IOException("image decoder/header mismatch or animation");
            }
            if (!bitmap.allocPixels(ImageInfo.makeN32Premul(header.width, header.height))) {
                throw new IOException("image pixel allocation failed");
            }
            codec.readPixels(bitmap); // Throws on incomplete / invalid input; no partially decoded image.
            bitmap.setImmutable();
            result = Image.makeRasterFromBitmap(bitmap);
        } catch (IOException | RuntimeException | LinkageError failure) {
            if (result != null) result.close();
            throw failure;
        }
        return result;
    }

    private static Rect clampSource(Rect requested, int width, int height) {
        float left = Math.max(0, Math.min(width, requested.getLeft()));
        float top = Math.max(0, Math.min(height, requested.getTop()));
        float right = Math.max(left, Math.min(width, requested.getRight()));
        float bottom = Math.max(top, Math.min(height, requested.getBottom()));
        return right > left && bottom > top ? Rect.makeLTRB(left, top, right, bottom) : null;
    }

    static Rect fit(Rect destination, int imageWidth, int imageHeight, boolean contain) {
        return fit(destination, (float) imageWidth, (float) imageHeight, contain);
    }

    static Rect fit(Rect destination, float imageWidth, float imageHeight, boolean contain) {
        if (!(imageWidth > 0) || !(imageHeight > 0) || destination.getWidth() <= 0 || destination.getHeight() <= 0) return null;
        float sx = destination.getWidth() / imageWidth;
        float sy = destination.getHeight() / imageHeight;
        float scale = contain ? Math.min(sx, sy) : Math.max(sx, sy);
        float width = imageWidth * scale;
        float height = imageHeight * scale;
        return Rect.makeXYWH(destination.getLeft() + (destination.getWidth() - width) / 2,
                destination.getTop() + (destination.getHeight() - height) / 2, width, height);
    }

    private static boolean ascii(byte[] bytes, int offset, String value) {
        if (offset < 0 || value.length() > bytes.length - offset) return false;
        for (int i = 0; i < value.length(); i++) if (u8(bytes, offset + i) != value.charAt(i)) return false;
        return true;
    }

    private static int u8(byte[] bytes, int offset) { return bytes[offset] & 255; }
    private static int be16(byte[] bytes, int offset) { return (u8(bytes, offset) << 8) | u8(bytes, offset + 1); }
    private static int le16(byte[] bytes, int offset) { return u8(bytes, offset) | (u8(bytes, offset + 1) << 8); }
    private static long be32(byte[] bytes, int offset) {
        return ((long) be16(bytes, offset) << 16) | be16(bytes, offset + 2);
    }
    private static int le24(byte[] bytes, int offset) {
        return le16(bytes, offset) | (u8(bytes, offset + 2) << 16);
    }
    private static long le32(byte[] bytes, int offset) {
        return le24(bytes, offset) | ((long) u8(bytes, offset + 3) << 24);
    }
}
