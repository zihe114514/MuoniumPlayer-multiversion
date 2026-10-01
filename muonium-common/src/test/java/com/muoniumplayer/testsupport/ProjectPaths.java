package com.muoniumplayer.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试用的路径解析（五棵树共用这一份）。
 *
 * <p>存在的理由：这些静态扫描测试读的是源码与构建脚本本身，而多加载器化之后
 * 测试是挂在 {@code fabric} 子工程上跑的，工作目录不再是版本工程根目录。
 * 写死 {@code ..} 会在「直接从版本根目录跑测试」时又错一次，所以向上找
 * {@code settings.gradle} 来定位版本工程根，再向上找 {@code muonium-common} 定位仓库根。
 *
 * <p><b>两个源码根</b>：跨树共享的主体在 {@link #sharedJava()}（仓库根的
 * {@code muonium-common/src/main/java}），版本差异层在各树自己的 {@link #versionJava()}
 * （{@code compat/}、{@code mixin/} 与功能集不同的那几个文件）。
 * 定位单个文件用 {@link #sourceFile(String)}，它两个根都找；
 * <b>遍历全仓必须用 {@link #javaRoots()} 或 {@link #allJavaFiles()}</b> ——
 * 只扫一个根的话，「所有文件都不许出现 X」这类断言会悄悄少扫一半，
 * 而且是绿着少扫，出问题时看不出来。
 */
public final class ProjectPaths {

    /** 跨树共享层目录名（仓库根下，与 mpparty-common 同一层级、同一挂法）。 */
    private static final String COMMON = "muonium-common";

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

    /** 仓库根（五棵版本树与 muonium-common 所在的那一层）。 */
    public static Path repoRoot() {
        Path parent = versionRoot().getParent();
        if (parent == null || !Files.isDirectory(parent.resolve(COMMON))) {
            throw new IllegalStateException("版本工程根的上层里没有 " + COMMON
                    + "，仓库结构变了就需要同步这个帮助类：" + versionRoot());
        }
        return parent;
    }

    // ==================== 源码根 ====================

    /** 跨树共享主体：与版本、加载器都无关的那一份编译输入。 */
    public static Path sharedJava() {
        return repoRoot().resolve(COMMON).resolve(Paths.get("src", "main", "java"));
    }

    /** 本树的版本差异层（compat / mixin / 功能集差异的文件）。 */
    public static Path versionJava() {
        return versionRoot().resolve(Paths.get("src", "main", "java"));
    }

    /** 两个源码根，共享在前。全仓遍历用它，别只挑一个。 */
    public static List<Path> javaRoots() {
        List<Path> roots = new ArrayList<Path>();
        roots.add(sharedJava());
        roots.add(versionJava());
        return roots;
    }

    /**
     * 定位一个源文件：先共享根，再本树根。
     *
     * <p>两个根都没有时返回共享根下的那个路径 —— 让 {@code NoSuchFileException}
     * 与断言信息说「共享层缺这个文件」，比返回 null 或抛「找不到」更好查。
     */
    public static Path sourceFile(String relative) {
        return sourceFile(Paths.get(relative));
    }

    /** 同 {@link #sourceFile(String)}，参数是 {@code Paths.get("a","b",...)} 拼出来的相对路径。 */
    public static Path sourceFile(Path relative) {
        for (Path root : javaRoots()) {
            Path candidate = root.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return sharedJava().resolve(relative);
    }

    /** 两个根里实际存在的同名目录（用来「把这个包下的文件都看一遍」）。 */
    public static List<Path> sourceDirs(String relativeDir) {
        List<Path> found = new ArrayList<Path>();
        for (Path root : javaRoots()) {
            Path candidate = root.resolve(Paths.get(relativeDir));
            if (Files.isDirectory(candidate)) {
                found.add(candidate);
            }
        }
        return found;
    }

    /** 两个源码根下全部 .java 文件；需要「全仓扫描」语义的测试用它。 */
    public static List<Path> allJavaFiles() throws IOException {
        List<Path> all = new ArrayList<Path>();
        for (Path root : javaRoots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (file.getFileName().toString().endsWith(".java")) {
                        all.add(file);
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        }
        return all;
    }

    /** 两个源码根里某个包目录下的全部 .java（并集）。「这个包里的文件都不许 X」用它。 */
    public static List<Path> javaFilesIn(String relativeDir) throws IOException {
        List<Path> all = new ArrayList<Path>();
        for (Path dir : sourceDirs(relativeDir)) {
            Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (file.getFileName().toString().endsWith(".java")) {
                        all.add(file);
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        }
        return all;
    }

    // ==================== 资源根 ====================

    /** 跨树共享资源（字体、纹理、svg、lx 脚本、automix 的 python）。 */
    public static Path sharedResources() {
        return repoRoot().resolve(COMMON).resolve(Paths.get("src", "main", "resources"));
    }

    /** 本树资源（lang 这类随版本 API 变动的）。 */
    public static Path versionResources() {
        return versionRoot().resolve(Paths.get("src", "main", "resources"));
    }

    /** 两个资源根，共享在前。 */
    public static List<Path> resourceRoots() {
        List<Path> roots = new ArrayList<Path>();
        roots.add(sharedResources());
        roots.add(versionResources());
        return roots;
    }

    /** 定位一个资源文件：先共享根，再本树根。 */
    public static Path resourceFile(String relative) {
        return resourceFile(Paths.get(relative));
    }

    /** 同 {@link #resourceFile(String)}，参数是 {@code Paths.get(...)} / {@code Path.of(...)} 拼出来的。 */
    public static Path resourceFile(Path relative) {
        for (Path root : resourceRoots()) {
            Path candidate = root.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return sharedResources().resolve(relative);
    }

    // ==================== 加载器子工程 ====================

    /** Fabric 子工程目录。 */
    public static Path fabric() {
        return versionRoot().resolve("fabric");
    }

    /**
     * 另一侧加载器的子工程目录：1.20.1 那棵是 MinecraftForge（目录叫 {@code forge}），
     * 其余四棵是 NeoForge（目录叫 {@code neoforge}）。谁在就看谁，别写死。
     */
    public static Path loaderDir() {
        for (String name : new String[]{"forge", "neoforge"}) {
            Path candidate = versionRoot().resolve(name);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("版本工程根下既没有 forge 也没有 neoforge：" + versionRoot());
    }

    /** {@link #loaderDir()} 的别名，留给写着 forge 的老断言。 */
    public static Path forge() {
        return loaderDir();
    }

    /** {@link #loaderDir()} 的别名，留给写着 neoforge 的老断言。 */
    public static Path neoforge() {
        return loaderDir();
    }
}
