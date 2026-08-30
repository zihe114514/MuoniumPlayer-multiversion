package com.muoniumplayer.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 测试用的路径解析。
 *
 * <p>存在的理由：这些静态扫描测试读的是源码与构建脚本本身，而多加载器化之后
 * 测试是挂在 {@code fabric} 子工程上跑的，工作目录不再是版本工程根目录。
 * 写死 {@code ..} 会在「直接从版本根目录跑测试」时又错一次，所以向上找
 * {@code settings.gradle} 来定位根目录。
 */
public final class ProjectPaths {

    private ProjectPaths() {
    }

    /** 版本工程根目录（含 settings.gradle 的那一层，例如 C:\MUSIC\1.21.1）。 */
    public static Path versionRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && dir != null; i++) {
            if (Files.exists(dir.resolve("settings.gradle"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到含 settings.gradle 的版本工程根目录，起点："
                + Paths.get("").toAbsolutePath());
    }

    /** 共享源码根：与加载器无关的那一份。 */
    public static Path sharedJava() {
        return versionRoot().resolve(Paths.get("src", "main", "java"));
    }

    /** 共享资源根（assets / 字体 / 纹理）。 */
    public static Path sharedResources() {
        return versionRoot().resolve(Paths.get("src", "main", "resources"));
    }

    /** Fabric 子工程目录。 */
    public static Path fabric() {
        return versionRoot().resolve("fabric");
    }

    /** NeoForge 子工程目录。 */
    public static Path neoforge() {
        return versionRoot().resolve("neoforge");
    }
}
