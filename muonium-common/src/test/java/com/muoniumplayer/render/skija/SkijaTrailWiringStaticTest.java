package com.muoniumplayer.render.skija;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkijaTrailWiringStaticTest {
    private static final List<String> PORTS = List.of("1.20.1 recode", "1.21.1", "1.21.10",
            "1.21.11 recode", "26.1.2", "26.2", "26.3");

    @Test
    void gpuWorkHasPairedStateIsolationAndIsReleasedBeforeEveryContext() throws Exception {
        for (String port : PORTS) {
            String host = source(port, "render/skija/SkijaHost.java");
            int start = host.indexOf("static boolean renderOffscreen(Runnable draw)");
            int end = host.indexOf("static DirectContext context()", start);
            assertTrue(start >= 0 && end > start, port);
            String frame = host.substring(start, end);
            assertTrue(frame.contains("SNAPSHOT.capture()") && frame.contains("restoreMinecraftState()"), port);
            assertTrue(frame.contains("finally") && frame.contains("GL_READ_FRAMEBUFFER"), port);
            assertFalse(frame.contains("pendingComposite = true"), port + ": GPU work must not create an empty UI pass");
            int shutdown = host.indexOf("public static void shutdown()");
            int release = host.indexOf("SkijaTrailRenderer.release()", shutdown);
            int contextClose = host.indexOf("context.close()", shutdown);
            assertTrue(release > shutdown && contextClose > release, port);
        }
    }

    @Test
    void capablePortsKeepTheSameQuadPathAndRasterFallbackWhileLegacyMipStaysIntact() throws Exception {
        for (String port : PORTS) {
            String host = source(port, "compat/mc/WorldBillboard.java");
            if (port.equals("1.20.1 recode")) {
                assertFalse(host.contains("public boolean supportsGpuTrailCanvas()"));
                assertTrue(host.contains("tex.mipTop > 0"));
                continue;
            }
            assertTrue(host.contains("public boolean supportsGpuTrailCanvas()"), port);
            assertTrue(host.contains("drawGpuTrailCanvas(io.github.humbleui.skija.Picture"), port);
            assertTrue(host.contains("return drawTrailCanvas(null, picture"), port);
            assertTrue(host.contains("return drawTrailCanvas(px, null"), port);
            assertTrue(host.contains("if (picture != null) return false;"), port + ": GPU rejection must not poison raster path");
        }
        Path root = ProjectPaths.repoRoot();
        String gpu = Files.readString(root.resolve("muonium-common/src/main/java/com/muoniumplayer/render/skija/SkijaTrailRenderer.java"));
        assertFalse(gpu.contains("glReadPixels"));
        assertFalse(gpu.contains("glFinish"));
        // 移动端实验放行：available 不再按 launcherFamily 硬拦截（真机验证 Skia GL 互操作）；
        // 失败安全网仍在——异常熔断 unavailable、GL 3.2 探测、逃生开关三道都在。
        assertFalse(gpu.contains("MobileRendererEnv"),
                "GPU 拖尾不得再按移动启动器指纹硬拦截（实验放行，失败仍同帧回 CPU）");
        assertTrue(gpu.contains("muonium.lyrics3d.gpuTrails"));
        assertTrue(gpu.contains("(rgb * 255 + q.a / 2) / q.a"));
        assertTrue(gpu.contains("GL_COLOR_LOGIC_OP") && gpu.contains("GL_DITHER"));
    }

    private static String source(String port, String path) throws Exception {
        return Files.readString(ProjectPaths.repoRoot().resolve(port)
                .resolve("src/main/java/com/muoniumplayer").resolve(path));
    }
}
