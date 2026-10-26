package com.muoniumplayer.render.skija;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住「链接器允许的落点」对启动器原生目录的追加。
 *
 * <p>背景：Android 的 linker namespace 不允许 JVM 从外置存储 dlopen（2026-09-29 真机
 * 证据），现有落点阶梯全部是「与 libjvm 同源」的位置。渲染器插件（MobileGlues / MobileGL /
 * Zink 套件）的 nativeLibraryDir 是启动器自己 dlopen 渲染器 .so 的地方——同样必然在
 * 允许的命名空间里，是「其它落点全部落空」时的最后一条结构性合法退路。
 * 本测试钉住：启动器 env 在场时这些目录进列表、裸名不进、桌面不受影响。
 * 落点列表会过滤不存在的目录（{@code dedup}），所以喂的是真实临时目录。
 */
class LauncherStageRootsTest {

    private static Map<String, String> env(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    /** 建一棵真实存在的两层临时目录树，返回 [上层, 下层]。 */
    private static File[] tempTree() throws IOException {
        File parent = Files.createTempDirectory("muonium-launcher").toFile();
        File child = new File(parent, "plugin");
        assertTrue(child.mkdirs());
        return new File[]{parent, child};
    }

    private static List<String> paths(Map<String, String> env) {
        return SkijaNativeCatalog.stageRoots(new HashMap<>(), env, null, new File("/cfg"), true)
                .stream().map(File::getAbsolutePath).toList();
    }

    @Test
    void launcherNativeDirsAppendedOnAndroid() throws IOException {
        File[] tree = tempTree();
        Map<String, String> env = env(
                "POJAV_NATIVEDIR", tree[0].getAbsolutePath(),
                "POJAVEXEC_EGL", new File(tree[1], "libMobileGL.so").getAbsolutePath());
        List<String> roots = paths(env);
        assertTrue(roots.contains(tree[0].getAbsolutePath()),
                "POJAV_NATIVEDIR 必须进落点列表");
        assertTrue(roots.contains(tree[1].getAbsolutePath()),
                "POJAVEXEC_EGL 的所在目录必须进落点列表");
    }

    @Test
    void bareSonameYieldsNoDirectory() throws IOException {
        File[] tree = tempTree();
        Map<String, String> env = env("POJAVEXEC_EGL", "libEGL_mesa.so",
                "SDL_EGL_LIBRARY", new File(tree[0], "libEGL_mesa.so").getAbsolutePath());
        List<String> roots = paths(env);
        // 裸名取不出目录；绝对路径取出目录。
        assertEquals(1,
                roots.stream().filter(p -> p.equals(tree[0].getAbsolutePath())).count(),
                "裸名不该被误拆，绝对路径目录只进一次");
    }

    @Test
    void desktopIgnoresLauncherDirs() throws IOException {
        File[] tree = tempTree();
        File config = Files.createTempDirectory("muonium-cfg").toFile();
        Map<String, String> env = env("POJAV_NATIVEDIR", tree[0].getAbsolutePath());
        List<String> roots = SkijaNativeCatalog.stageRoots(new HashMap<>(), env, null,
                config, false)
                .stream().map(File::getAbsolutePath).toList();
        assertFalse(roots.contains(tree[0].getAbsolutePath()),
                "桌面根本不该考虑启动器目录");
        assertTrue(roots.contains(config.getAbsolutePath()), "桌面配置目录仍是正解");
    }

    @Test
    void fclNativeDirAlsoAccepted() throws IOException {
        File[] tree = tempTree();
        Map<String, String> env = env("FCL_NATIVEDIR", tree[0].getAbsolutePath());
        assertTrue(paths(env).contains(tree[0].getAbsolutePath()));
    }
}
