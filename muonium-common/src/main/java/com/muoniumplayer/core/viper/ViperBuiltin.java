package com.muoniumplayer.core.viper;

import com.muoniumplayer.core.settings.ConfigPaths;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 随包内置的卷积核与预设：首次运行时铺一份副本进 {@code config/muonium/viper/}。
 *
 * <p><b>为什么是「铺副本」而不是直接从 classpath 读</b>：内核解析链读的是真实文件
 * （{@code 绝对路径 → viper/kernels/ → viper/}），铺副本之后列表、选用、重载、用户自己
 * 改内核文件这些既有路径全部原样成立；面板里的删除键对内置项也同样生效——删掉就不再回来。</p>
 *
 * <p><b>版本戳语义</b>：{@link #BUNDLE_MARKER} 里存着「已经铺过哪一版」。只有它的版本低于
 * {@link #BUNDLE_VERSION} 才执行一次「补缺复制」；复制只补目标缺失的文件，<b>绝不覆盖</b>
 * 用户改过的同名文件。升级内置包时把 {@code BUNDLE_VERSION} 加一：缺失的旧内置项会随新版本
 * 一并补回（换版本等于换一批内容，这是刻意的）。</p>
 *
 * <p><b>许可</b>：这批内核取自公开渠道的 V4A 生态 {@code .irs} 卷积核（含酷狗音效同款），
 * 与 VHE 核表同属「个人使用」范围；对外发布的产物应先移出（见
 * {@code docs/实现约定-ViPER蝰蛇音效配置兼容.md} §五）。</p>
 */
public final class ViperBuiltin {

    /** 内置包版本：内容有增删时 +1。 */
    static final int BUNDLE_VERSION = 1;
    /** 版本戳文件（相对 viper 目录）。 */
    static final String BUNDLE_MARKER = ".builtin-bundle";
    /** classpath 上随包内核的目录。 */
    private static final String RES_KERNELS = "/com/muoniumplayer/viper/kernels/";
    /** classpath 上随包预设的目录。 */
    private static final String RES_PRESETS = "/com/muoniumplayer/viper/presets/";

    /** 随包内核文件名（与 {@link #RES_KERNELS} 下的资源一一对应）。 */
    static final String[] KERNELS = {
            "【推荐】Dolby Pro Logic II Studio.irs",
            "【黑钻蝰蛇】.irs",
            "尊享旗舰·空间音效.irs",
            "至臻-沉浸环绕音效.irs",
            "酷狗-低音.irs",
            "酷狗3D丽音[重制版].irs",
            "酷狗HiFi.irs",
            "黑钻蝰蛇耳机.irs"
        };

    /** 随包预设文件名（内容 = 启用卷积器并引用同名内核）。 */
    static final String[] PRESETS = {
            "【推荐】Dolby Pro Logic II Studio.json",
            "【黑钻蝰蛇】.json",
            "尊享旗舰·空间音效.json",
            "至臻-沉浸环绕音效.json",
            "酷狗-低音.json",
            "酷狗3D丽音[重制版].json",
            "酷狗HiFi.json",
            "黑钻蝰蛇耳机.json"
        };

    private static final Object LOCK = new Object();
    private static volatile boolean provisioned;

    private ViperBuiltin() {
    }

    /**
     * 幂等：首次调用把随包内核/预设补缺复制进 config 目录，此后只查内存标志。
     * 失败不置位（下一次调用重试），也绝不抛出——音频链与面板都不该被铺设拖垮。
     */
    public static void ensureProvisioned() {
        if (provisioned) {
            return;
        }
        synchronized (LOCK) {
            if (provisioned) {
                return;
            }
            try {
                provision(ConfigPaths.VIPER_DIR, BUNDLE_VERSION);
                provisioned = true;
            } catch (Throwable failure) {
                System.out.println("[MuoniumPlayer/ViPER] 内置预设铺设失败：" + failure);
            }
        }
    }

    /**
     * 补缺复制（包级可见，测试可指向临时目录）。只补目标缺失的文件，绝不覆盖已有文件；
     * marker 版本不低于 {@code version} 时直接返回 0（用户删掉的内置项不会复活）。
     *
     * @return 本次实际复制的文件数
     */
    static int provision(final File viperDir, final int version) {
        final File marker = new File(viperDir, BUNDLE_MARKER);
        if (readVersion(marker) >= version) {
            return 0;
        }
        int copied = copyAll(KERNELS, new File(viperDir, "kernels"), RES_KERNELS);
        copied += copyAll(PRESETS, new File(viperDir, "presets"), RES_PRESETS);
        writeVersion(marker, version);
        return copied;
    }

    private static int copyAll(final String[] names, final File targetDir, final String resDir) {
        int copied = 0;
        for (final String name : names) {
            final File target = new File(targetDir, name);
            if (target.isFile()) {
                continue;
            }
            if (!targetDir.isDirectory() && !targetDir.mkdirs() && !targetDir.isDirectory()) {
                System.out.println("[MuoniumPlayer/ViPER] 内置目录创建失败：" + targetDir);
                return copied;
            }
            try (InputStream in = ViperBuiltin.class.getResourceAsStream(resDir + name)) {
                if (in == null) {
                    System.out.println("[MuoniumPlayer/ViPER] 内置资源缺失（打包不完整？）：" + resDir + name);
                    continue;
                }
                copy(in, target);
                copied++;
            } catch (IOException failure) {
                System.out.println("[MuoniumPlayer/ViPER] 内置文件写入失败（" + name + "）：" + failure);
            }
        }
        return copied;
    }

    private static void copy(final InputStream in, final File target) throws IOException {
        try (OutputStream out = new FileOutputStream(target)) {
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

    private static int readVersion(final File marker) {
        if (!marker.isFile()) {
            return -1;
        }
        try {
            return Integer.parseInt(new String(Files.readAllBytes(marker.toPath()),
                    StandardCharsets.UTF_8).trim());
        } catch (Throwable failure) {
            return -1;
        }
    }

    private static void writeVersion(final File marker, final int version) {
        try {
            final File parent = marker.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                System.out.println("[MuoniumPlayer/ViPER] 内置版本戳目录创建失败：" + parent);
                return;
            }
            Files.write(marker.toPath(), (version + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            System.out.println("[MuoniumPlayer/ViPER] 内置版本戳写入失败：" + failure);
        }
    }
}
