package com.muoniumplayer.core.rendering.texture;

import lombok.experimental.UtilityClass;
import com.muoniumplayer.core.MuoniumPlayerExtension;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.network.HttpUtils;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @since 11/25/2023
 */
@UtilityClass
public class Textures implements SharedConstants {
    private static final TextureUploadQueue UPLOADS = new TextureUploadQueue();
    private static final Set<Location> PENDING_LOOKUPS = ConcurrentHashMap.newKeySet();
    /**
     * 已经在后台下载的封面。滚动列表会在很短时间内反复创建/重绘卡片，
     * 没有这一层闸门时同一张封面可能被排进多条 HTTP 任务，最后又重复上传。
     */
    private static final Set<Location> PENDING_DOWNLOADS = ConcurrentHashMap.newKeySet();

    /**
     * Called once per client tick. Keep cover bursts out of the unbounded task drain.
     *
     * <p>配速口径：一次快速滚动会让几十张封面几乎同时进视口，下载并发完成后的上传若按
     * 老口径每 tick 只走 4 张（20 TPS = 80 张/秒），排到队尾的那张（网格右下角，渲染顺序
     * 最晚发起下载）要空着占位板 0.4~0.8 秒——用户看到的就是「永远差最后一个」。GPU 贴图
     * 上传本身极廉，放宽到 4ms/12 张（240 张/秒）后同屏积压约 0.1 秒清空，右下角与同批
     * 封面几乎同时淡入，又不至于在极端帧里挤占渲染预算。</p>
     */
    public void processPendingUploads() {
        UPLOADS.drain(4_000_000L, 12);
    }

    public int pendingUploads() {
        return UPLOADS.pending();
    }

    public Optional<ITextureObject> getTextureOrLoadAsynchronously(Location location, BufferedImage img) {

        TextureManager textureManager = TextureManager.getInstance();

        ITextureObject texture = textureManager.getTexture(location);

        if (texture == null && img != null && PENDING_LOOKUPS.add(location)) {
            enqueueUpload(() -> {
                try {
                    // Another loader may have completed this resource meanwhile.
                    if (textureManager.getTexture(location) == null) {
                        loadTexture(location, img);
                    }
                } finally {
                    PENDING_LOOKUPS.remove(location);
                    img.flush();
                }
            });
        }

        return Optional.ofNullable(texture);
    }

    public void downloadTextureAndLoadAsync(String url, Location location) {
        if (url == null || url.trim().isEmpty() || location == null) {
            return;
        }
        TextureManager textureManager = TextureManager.getInstance();
        if (textureManager.getTexture(location) != null || !PENDING_DOWNLOADS.add(location)) {
            return;
        }

        MultiThreadingUtil.runAsync(() -> {
            boolean queuedUpload = false;
            try (InputStream inputStream = HttpUtils.downloadStream(url)) {
                if (inputStream != null) {
                    BufferedImage img = DynamicTexture.readImage(inputStream);

                    if (img != null) {
                        queuedUpload = true;
                        // 上传仍由主线程限额队列完成；只有上传真正完成后才释放下载闸门，
                        // 避免图片已经解码但还没贴到 GPU 时被下一帧重复下载。
                        Textures.loadTextureAsyncly(location, img, true, true, false,
                                () -> PENDING_DOWNLOADS.remove(location));
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                // 失败时允许下一次进入视口重新尝试。
                if (!queuedUpload) PENDING_DOWNLOADS.remove(location);
            }
        });
    }

    public void loadTexture(Location location, BufferedImage img) {
        Textures.loadTexture(location, img, true, false);
    }

    public void loadTexture(Location location, BufferedImage img, boolean clearable, boolean linear) {

        if (img == null)
            return;

        if (!MuoniumPlayerExtension.isCallingFromMainThread()) {
            enqueueUpload(() -> loadTexture(location, img, clearable, linear));
            return;
        }

        // 上传是在主线程做的，而一次解码完成就会往主线程排一条。封面这类「一批同时到手」的
        // 用法因此能把一整帧吃满。这里只做累加，供界面侧的性能日志读出「这一帧到底传了几张」。
        long startedAt = System.nanoTime();
        DynamicTexture dynamicTexture = new DynamicTexture(img, clearable, linear);
        TextureManager.getInstance().loadTexture(location, dynamicTexture);
        perfUploadNanos += System.nanoTime() - startedAt;
        perfUploads++;
    }

    /**
     * 主线程贴图上传的累计张数与耗时。
     *
     * <p>只在主线程写、只在主线程读（性能日志出在渲染那一帧里），所以是普通 long，不需要原子类。
     * 读法按「取差值」来用：谁想知道这一段传了几张，就先记一次再记一次。</p>
     */
    public static long perfUploads;
    public static long perfUploadNanos;

    /** Completion runs on the main thread, after pixels are uploaded and registered. */
    public void loadTextureAsyncly(Location location, BufferedImage img, boolean flush, boolean clearable, boolean linear, Runnable after) {
        if (img == null) {
            return;
        }
        // No decoding is left to do: dispatch directly rather than scheduling a
        // worker just to bounce back to the main thread. Retain pixels until done.
        enqueueUpload(() -> {
            try {
                loadTexture(location, img, clearable, linear);
                if (after != null) {
                    after.run();
                }
            } finally {
                if (flush) {
                    img.flush();
                }
            }
        });
    }

    private void enqueueUpload(Runnable upload) {
        UPLOADS.submit(() -> {
            try {
                upload.run();
            } catch (Exception failure) {
                failure.printStackTrace();
            }
        });
    }

    public void loadTextureAsyncly(Location location, BufferedImage img, boolean flush, boolean clearable, boolean linear) {
        Textures.loadTextureAsyncly(location, img, flush, clearable, linear, null);
    }

    public void loadTextureAsyncly(Location location, BufferedImage img, boolean flush) {
        Textures.loadTextureAsyncly(location, img, flush, true, false, null);
    }

    public void loadTextureAsyncly(Location location, BufferedImage img, Runnable after) {
        Textures.loadTextureAsyncly(location, img, true, true, false, after);
    }

    public void loadTextureAsyncly(Location location, BufferedImage img) {
        Textures.loadTextureAsyncly(location, img, true, true, false, null);
    }

    public void triggerLoad(Location location, ITextureObject object) {
        TextureManager.getInstance().loadTexture(location, object);
    }

}
