package com.muoniumplayer.core.ncm.music;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * 通过反射调用「按需下载的」zstd-jni，产出 NCBL 协议要的标准 zstd 帧。
 *
 * <p>主包对 {@code com.github.luben.zstd} 零编译期依赖（静态测试钉着）——jar 由
 * {@link ZstdNativeDownload} 在用户登录网易云账号时拉到 {@code config/muonium/libs/}，
 * 本类用一个独立的 {@link URLClassLoader} 挂起来。反射面只有一个点：
 * {@code Zstd.compress(byte[])}（默认压缩级别 3，与旧直接调用完全一致，输出是
 * 服务端认的标准 zstd 帧）。</p>
 *
 * <p>加载器常驻：zstd 的原生库按 JNI 规范随类加载器卸载，反复建/关加载器会让
 * native 重复映射；一次挂好用到会话结束。</p>
 */
final class ZstdCompressor {

    private final URLClassLoader loader;
    private final Object lock = new Object();
    private volatile boolean closed;

    private ZstdCompressor(URLClassLoader loader) {
        this.loader = loader;
    }

    /** 从一个已校验的 jar 建压缩桥。任何一步失败都抛给调用方压成「组件不可用」。 */
    static ZstdCompressor create(File jar) throws Exception {
        URLClassLoader loader = new URLClassLoader(new URL[]{jar.toURI().toURL()},
                ZstdCompressor.class.getClassLoader());
        try {
            // 提前触发原生库所在类的解析：加载失败在这里暴露，而不是第一次压缩时。
            Class.forName("com.github.luben.zstd.Zstd", true, loader);
            return new ZstdCompressor(loader);
        } catch (Throwable linkage) {
            try {
                loader.close();
            } catch (Throwable ignored) {
            }
            throw linkage;
        }
    }

    /**
     * 标准帧格式的 zstd 压缩（默认级别），与旧直接调用 {@code Zstd.compress(source)} 逐字节同源。
     *
     * @throws Exception 压缩返回空数据或反射链路出问题时——调用方把它当一次上报失败处理
     */
    byte[] compress(byte[] source) throws Exception {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("压缩桥已关闭");
            }
            Class<?> zstd = Class.forName("com.github.luben.zstd.Zstd", true, loader);
            byte[] compressed = (byte[]) zstd.getMethod("compress", byte[].class).invoke(null, source);
            if (compressed == null || compressed.length == 0) {
                throw new IllegalStateException("Zstandard compression returned no data");
            }
            return compressed;
        }
    }
}
