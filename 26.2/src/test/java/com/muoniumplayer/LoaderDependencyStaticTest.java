package com.muoniumplayer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.muoniumplayer.testsupport.ProjectPaths;

/**
 * fabric.mod.json 里声明的 Loader 下限的约束。
 *
 * <p>下限刻意与开发用的 Loader 版本分开：开发机上装的是最新的 Loader，但发布出去的 jar
 * 没有理由要求用户也升到那一版。两者写成同一个属性的话，每次升级开发环境都会顺手把
 * 用户的门槛抬高一次，而这种收紧在本地永远测不出来。</p>
 *
 * <p>同时钉住「下限不能高于开发用的版本」：那意味着开发环境自己都不满足自己声明的依赖，
 * runClient 能跑而装到实际启动器里却装不上。</p>
 */
class LoaderDependencyStaticTest {

    private static final Path GRADLE_PROPERTIES =
            ProjectPaths.versionRoot().resolve("gradle.properties");
    private static final Path FABRIC_MOD_JSON =
            Paths.get("src", "main", "resources", "fabric.mod.json");

    @Test
    @DisplayName("fabric.mod.json 用的是单独的下限属性，不是开发用的 Loader 版本")
    void 下限与开发版本分开声明() throws IOException {
        String manifest = read(FABRIC_MOD_JSON);
        assertTrue(manifest.contains("\"fabricloader\": \">=${loader_min_version}\""),
                "fabric.mod.json 的 fabricloader 依赖必须引用 loader_min_version：" +
                        "写成 loader_version 的话，开发环境一升级就把用户门槛也抬高了");
        assertFalse(manifest.contains("${loader_version}"),
                "fabric.mod.json 不该再出现 loader_version：那是开发/编译期用的版本");
    }

    @Test
    @DisplayName("声明的下限不高于开发用的 Loader 版本")
    void 下限不高于开发版本() throws IOException {
        Properties properties = new Properties();
        properties.load(new StringReader(read(GRADLE_PROPERTIES)));

        String dev = properties.getProperty("loader_version");
        String min = properties.getProperty("loader_min_version");
        assertTrue(dev != null && !dev.isBlank(), "gradle.properties 缺 loader_version");
        assertTrue(min != null && !min.isBlank(), "gradle.properties 缺 loader_min_version");

        assertTrue(compare(min, dev) <= 0,
                "声明的下限 " + min + " 高于开发用的 " + dev
                        + "：开发环境自己都不满足这条依赖，runClient 能跑但装进启动器会被拒");
    }

    /** 按点分数字段逐段比较，缺失的段当 0。Loader 的版本号形如 0.17.3。 */
    private static int compare(String left, String right) {
        String[] a = left.split("[.+-]");
        String[] b = right.split("[.+-]");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = numberAt(a, i);
            int y = numberAt(b, i);
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int numberAt(String[] parts, int index) {
        if (index >= parts.length) return 0;
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException notANumber) {
            // 预发布后缀之类的非数字段一律当 0，不影响「哪个更大」的判断。
            return 0;
        }
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
