package com.muoniumplayer.core.utils;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.apache.commons.lang3.Validate;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.MissingResourceException;

@Getter
@EqualsAndHashCode(
        onlyExplicitlyIncluded = true,
        cacheStrategy = EqualsAndHashCode.CacheStrategy.LAZY
)
public class Location {

    @EqualsAndHashCode.Include
    protected final String resourcePath;

    protected Location(String resourcePath) {
        this.resourcePath = resourcePath;
        Validate.notNull(this.resourcePath);
    }

    public InputStream getResourceStream() {
        InputStream stream = Location.class.getResourceAsStream(resourcePath);

        if (stream == null)
            throw new MissingResourceException(resourcePath + " not found", Location.class.getName(), resourcePath);

        return stream;
    }

    public String toString() {
        return this.resourcePath;
    }

    /**
     * Location 实例缓存。
     *
     * <p>键多数是静态资源路径，但<b>不是全部</b>：每首歌的封面按
     * {@code music/<stableKey>/cover*.png} 派生四个键、歌词搜索封面按 URL hash、
     * 动态纹理按 {@code dynamic/<name>_<n>} 递增——这些键随播放历史单调增长。
     * 以前是裸 HashMap 只增不减，长听一场（几百上千首）缓存就常驻几十 MB 级的
     * 字符串与对象。软上限 + 整表清空：Location 只是「路径的不可变包装」，清掉
     * 下一帧重查一次 {@code of()} 即可重建，没有任何正确性代价（TextureManager
     * 那张按 Location 寻址的表用 equals 匹配，Location 的 equals 只看路径字符串）。</p>
     */
    private static final Map<String, Location> locationCache = new HashMap<>();
    /** 软上限。超过即整表清空——同项目里 LyricSearchOverlay 的 COVER_REQUESTS 同一套约定。 */
    private static final int CACHE_SOFT_LIMIT = 2048;

    public static synchronized Location of(String path) {
        Location cached = locationCache.get(path);
        if (cached != null) {
            return cached;
        }
        if (locationCache.size() >= CACHE_SOFT_LIMIT) {
            locationCache.clear();
        }
        Location location = new Location(path);
        locationCache.put(path, location);
        return location;
    }


}
