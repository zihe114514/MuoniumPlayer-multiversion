package com.muoniumplayer.core.screens.ncm.foliabg;

import com.muoniumplayer.core.utils.network.HttpUtils;
import io.github.humbleui.skija.Image;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「嵌入 / Embed」背景的图片缓存。
 *
 * <p>folia 原版的嵌入背景是一个 iframe：用户维护一张 URL 列表，选中的那个页面铺满整屏。
 * Minecraft 里没有浏览器，所以这里把它降级成「网络图片」：同一个列表、同一个选中项、同一层
 * 可读性遮罩，只是页面换成一张静态图。URL 的净化规则与 folia 的
 * {@code normalizeUrlBackgroundUrl} 一致（只接受 http/https）。</p>
 *
 * <p>下载在后台线程、解码在主线程（Skia 的 Image 是 GPU 上下文相关的）。最多同时记 4 张，
 * 超出按插入顺序淘汰；失败按 URL 记一个负缓存，避免每帧重试。</p>
 */
final class FoliaEmbedImages {

    private static final int MAX_CACHED = 4;
    private static final Map<String, CacheEntry> CACHE =
            new LinkedHashMap<String, CacheEntry>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return size() > MAX_CACHED;
                }
            };

    private FoliaEmbedImages() {
    }

    private static final class CacheEntry {
        volatile boolean loading;
        volatile boolean failed;
        volatile byte[] bytes;
        Image image;
    }

    /** 取一张已经解码好的图；没有就触发一次后台下载并返回 null。 */
    static Image get(String url) {
        if (url == null || url.isEmpty()) return null;
        CacheEntry entry;
        synchronized (CACHE) {
            entry = CACHE.get(url);
            if (entry == null) {
                entry = new CacheEntry();
                CACHE.put(url, entry);
            }
        }
        if (entry.image != null) return entry.image;
        if (entry.failed) return null;
        if (entry.bytes != null) {
            try {
                entry.image = Image.makeFromEncoded(entry.bytes);
            } catch (Throwable failure) {
                entry.failed = true;
            }
            entry.bytes = null;
            return entry.image;
        }
        if (!entry.loading) {
            entry.loading = true;
            final CacheEntry target = entry;
            final String source = url;
            com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil.runAsync(() -> {
                try {
                    InputStream stream = HttpUtils.downloadStream(source, 10, null);
                    byte[] data = org.apache.commons.io.IOUtils.toByteArray(stream);
                    if (data == null || data.length == 0) {
                        target.failed = true;
                    } else {
                        target.bytes = data;
                    }
                } catch (Throwable failure) {
                    target.failed = true;
                } finally {
                    target.loading = false;
                }
            });
        }
        return null;
    }

    static void clear() {
        synchronized (CACHE) {
            for (CacheEntry entry : CACHE.values()) {
                if (entry.image != null) {
                    entry.image.close();
                    entry.image = null;
                }
            }
            CACHE.clear();
        }
    }
}

