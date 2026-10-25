package com.muoniumplayer.render.skija;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住「Skia 必须与 Minecraft 走同一扇 GL 门」这条修复链的关键形状。
 *
 * <p>背景（2026-10 MobileGL 真机定位）：官方 libskija-android 直连系统
 * libGLESv2/libEGL，绕过启动器的翻译层。MobileGL 前端自建对象名空间与状态影子，
 * 两扇门互踩 → Skija 界面不显示 + 原版画面闪烁。修法是在 libskija 加载成功后做一次
 * GOT 重定向（{@link SkijaGlRedirector}）。本测试防两类「悄悄失效」：
 * <ol>
 *   <li>接线断掉——adopt 里忘了调，或调用点挪到「已经有 GL 调用发生」之后；</li>
 *   <li>触发条件被放宽——桌面默认环境（未设置 org.lwjgl.opengl.libname）也跑
 *       GOT 重写，等于对正常路径引入无谓风险。</li>
 * </ol>
 */
class SkijaGlRedirectorStaticTest {

    @Test
    void adoptWiresRedirectImmediatelyAfterLoadSuccess() throws Exception {
        Path path = ProjectPaths.sharedJava().resolve(
                Paths.get("com", "muoniumplayer", "render", "skija", "SkijaNatives.java"));
        String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        int adoptBody = source.indexOf("boolean recovered = Boolean.FALSE.equals(loaded);");
        int redirectCall = source.indexOf("SkijaGlRedirector.afterLibraryLoad();");
        int loadedTrue = source.indexOf("loaded = Boolean.TRUE;");
        assertTrue(adoptBody >= 0 && redirectCall > loadedTrue,
                "adopt() 必须在置 loaded=TRUE 之后立刻调 SkijaGlRedirector.afterLibraryLoad()");
        assertTrue(countOccurrences(source, "SkijaGlRedirector.afterLibraryLoad();") == 1,
                "afterLibraryLoad 在 SkijaNatives 里只调一处（其余场景靠幂等闸）");
    }

    @Test
    void redirectorOnlyActsOnLauncherProvidedGlLibrary() throws Exception {
        Path path = ProjectPaths.sharedJava().resolve(
                Paths.get("com", "muoniumplayer", "render", "skija", "SkijaGlRedirector.java"));
        String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        assertTrue(source.contains("\"org.lwjgl.opengl.libname\""),
                "触发条件必须是 LWJGL 的 GL 库被启动器显式替换（org.lwjgl.opengl.libname）");
        assertTrue(source.contains("glLib.isEmpty()"),
                "属性为空（桌面默认环境）时必须直接返回，不碰任何东西");
        assertTrue(source.contains("\"eglGetProcAddress\"") && source.contains("\"glGetString\""),
                "动手前必须探针校验目标库是完整的 GL/EGL 前端");
        assertTrue(source.contains("hasGlPrefix") && source.contains("\"gl\"") && source.contains("\"egl\""),
                "只允许重定向 gl/egl 前缀的符号，绝不能碰 libc/JNI 等其它命名空间");
        assertTrue(source.contains("muonium.skija.noGlRedirect"),
                "必须保留一个异常环境整体关闭本机制的总闸");
        assertFalse(source.contains("throw new UnsupportedOperationException"),
                "不允许遗留占位实现");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
