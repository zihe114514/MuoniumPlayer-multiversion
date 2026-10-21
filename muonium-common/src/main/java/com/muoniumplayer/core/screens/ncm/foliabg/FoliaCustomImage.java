package com.muoniumplayer.core.screens.ncm.foliabg;

import com.muoniumplayer.core.settings.ConfigPaths;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 「自定义图片」：莫奈与漫游的 {@code uploaded-global} 来源共用同一张图。
 *
 * <p>folia 把上传图存在 IndexedDB 的全局键 {@code monet_background_image} 上（不是每首歌一份）；
 * 这里等价成 {@code config/muonium/folia_background/background.png}。解码在后台线程做，渲染线程
 * 只读 {@link #get()}；文件改动时间变了才重新解码。</p>
 *
 * <p>读不到（没上传、格式坏了、被删了）时返回 {@code null}，宿主自动回落到封面 —— 与 folia
 * 「两种来源互为回落」的规则一致。</p>
 */
public final class FoliaCustomImage {

    private static volatile Image image;
    private static volatile long loadedStamp = -1L;
    private static volatile boolean loading;

    private FoliaCustomImage() {
    }

    /** 当前可用图片；没有就返回 null 并触发一次后台加载。 */
    public static Image get() {
        File file = file();
        long stamp = file.isFile() ? file.lastModified() : -1L;
        if (stamp < 0L) {
            clear();
            return null;
        }
        if (stamp == loadedStamp && image != null) return image;
        if (!loading) {
            loading = true;
            com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil.runAsync(() -> {
                try {
                    Image decoded = decode(file);
                    if (decoded != null) {
                        Image previous = image;
                        image = decoded;
                        loadedStamp = stamp;
                        if (previous != null) previous.close();
                    }
                } catch (Throwable failure) {
                    System.out.println("[MuoniumPlayer/FoliaBg] 自定义背景图解码失败: " + failure);
                } finally {
                    loading = false;
                }
            });
        }
        return image;
    }

    /** 是否已经有一张可用的自定义图（面板用来决定「自定义」这一档能不能选）。 */
    public static boolean available() {
        File file = file();
        return file.isFile() && file.length() > 0;
    }

    public static void clear() {
        Image previous = image;
        image = null;
        loadedStamp = -1L;
        if (previous != null) previous.close();
    }

    /** 用户选好文件后调用：复制成固定文件名，下次 {@link #get()} 自动重解码。 */
    public static boolean install(File source) {
        if (source == null || !source.isFile()) return false;
        try {
            File target = file();
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
            java.nio.file.Files.copy(source.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            clear();
            return true;
        } catch (Throwable failure) {
            System.out.println("[MuoniumPlayer/FoliaBg] 复制自定义背景图失败: " + failure);
            return false;
        }
    }

    private static File file() {
        return new File(ConfigPaths.FOLIA_BACKGROUND_DIR, "background.png");
    }

    private static Image decode(File file) throws Exception {
        BufferedImage source = ImageIO.read(file);
        if (source == null) return null;
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= 0 || height <= 0) return null;
        byte[] rgba = new byte[width * height * 4];
        int at = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = source.getRGB(x, y);
                rgba[at++] = (byte) ((argb >> 16) & 0xFF);
                rgba[at++] = (byte) ((argb >> 8) & 0xFF);
                rgba[at++] = (byte) (argb & 0xFF);
                rgba[at++] = (byte) ((argb >>> 24) & 0xFF);
            }
        }
        ImageInfo info = new ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL);
        return Image.makeRasterFromBytes(info, rgba, width * 4L);
    }
}
