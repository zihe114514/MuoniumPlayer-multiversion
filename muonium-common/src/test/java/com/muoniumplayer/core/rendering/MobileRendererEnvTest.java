package com.muoniumplayer.core.rendering;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住「渲染器环境识别」的判定矩阵。
 *
 * <p>判据全部来自 ZL2 / FCL / MobileGlues-plugin / MobileGL 源码（2026-10-26 调研沉淀，
 * 见 docs/实现约定-移动端渲染器适配.md）。这组测试喂合成 Properties/env，
 * 防两类回归：判据值写错（比如 libgl4es_114.so 少个下划线）、判据顺序被倒
 * （POJAV_RENDERER 是弱信号，永远不该盖过 POJAVEXEC_EGL）。
 */
class MobileRendererEnvTest {

    private static Properties props(String libname) {
        Properties p = new Properties();
        if (libname != null) {
            p.setProperty("org.lwjgl.opengl.libname", libname);
        }
        return p;
    }

    private static java.util.Map<String, String> env(String... kv) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static MobileRendererEnv.Env detect(Properties p, java.util.Map<String, String> env) {
        return MobileRendererEnv.detectForTest(p, env, "Linux", "Android-15");
    }

    @Test
    void mobileGluesByEglEnv() {
        MobileRendererEnv.Env e = detect(props("libmobileglues.so"),
                env("POJAVEXEC_EGL", "libmobileglues.so"));
        assertEquals(MobileRendererEnv.Kind.MOBILEGLUES, e.kind());
        assertTrue(e.hit().contains("libmobileglues.so"));
    }

    @Test
    void mobileGluesByLibnameWhenEnvHidden() {
        // 「隐藏 MG 环境」只改 GL 字符串，env/属性仍在——这条是识别可靠性的关键。
        MobileRendererEnv.Env e = detect(props("libmobileglues.so"), env());
        assertEquals(MobileRendererEnv.Kind.MOBILEGLUES, e.kind());
    }

    @Test
    void mobileGlByEglEnv() {
        MobileRendererEnv.Env e = detect(props("libMobileGL.so"),
                env("POJAVEXEC_EGL", "/data/app/plugin/libMobileGL.so"));
        assertEquals(MobileRendererEnv.Kind.MOBILEGL, e.kind());
    }

    @Test
    void zinkByGlxShim() {
        MobileRendererEnv.Env e = detect(props("libglxshim.so"),
                env("POJAVEXEC_EGL", "libEGL_mesa.so"));
        assertEquals(MobileRendererEnv.Kind.ZINK, e.kind());
    }

    @Test
    void ngGl4esAndHolyGl4es() {
        assertEquals(MobileRendererEnv.Kind.NG_GL4ES,
                detect(props("libng_gl4es.so"), env()).kind());
        assertEquals(MobileRendererEnv.Kind.HOLY_GL4ES,
                detect(props("libgl4es_114.so"), env()).kind());
    }

    @Test
    void osmesaFamilyDisambiguatedByRendererEnv() {
        assertEquals(MobileRendererEnv.Kind.VIRGL,
                detect(props("/data/lib/libOSMesa_81.so"),
                        env("POJAV_RENDERER", "gallium_virgl")).kind());
        assertEquals(MobileRendererEnv.Kind.FREEDRENO,
                detect(props("libOSMesa_8.so"),
                        env("POJAV_RENDERER", "gallium_freedreno")).kind());
        assertEquals(MobileRendererEnv.Kind.PANFROST,
                detect(props("libOSMesa_2300d.so"),
                        env("POJAV_RENDERER", "gallium_panfrost")).kind());
        // VirGL 还有独立标记 VTEST_SOCKET_NAME。
        assertEquals(MobileRendererEnv.Kind.VIRGL,
                detect(props("libOSMesa_81.so"),
                        env("VTEST_SOCKET_NAME", "/tmp/vtest.sock")).kind());
    }

    @Test
    void uniqueRendererIdsWinDirectly() {
        assertEquals(MobileRendererEnv.Kind.ZINK, detect(props(null),
                env("POJAV_RENDERER", "opengles3_desktopgl_zink_kopper")).kind());
        assertEquals(MobileRendererEnv.Kind.FREEDRENO, detect(props(null),
                env("POJAV_RENDERER", "gallium_freedreno")).kind());
    }

    @Test
    void desktopWithoutFingerprintsIsUnknown() {
        MobileRendererEnv.Env e = MobileRendererEnv.detectForTest(
                new Properties(), env(), "Windows 11", "10.0");
        assertEquals(MobileRendererEnv.Kind.UNKNOWN, e.kind());
        assertFalse(e.launcherFamily());
    }

    @Test
    void overridePropertyWinsOverEverything() {
        Properties p = props("libglxshim.so");
        p.setProperty("muonium.renderer.override", "mobilegl");
        assertEquals(MobileRendererEnv.Kind.MOBILEGL,
                detect(p, env("POJAVEXEC_EGL", "libmobileglues.so")).kind());
    }

    @Test
    void launcherFamilySignals() {
        MobileRendererEnv.Env fcl = MobileRendererEnv.detectForTest(
                new Properties(), env("FCL_NATIVEDIR", "/data/data/fcl"), "Linux", "Linux");
        assertFalse(fcl.launcherFamily() == false || fcl.kind() != MobileRendererEnv.Kind.UNKNOWN,
                "FCL 家族标记应被认出（即使渲染器识别不出）");
        MobileRendererEnv.Env zl2 = MobileRendererEnv.detectForTest(
                propsOf("pojav.path.minecraft", "/data/user/0/mc"), env(), "Linux", "Linux");
        assertTrue(zl2.launcherFamily());
    }

    private static Properties propsOf(String k, String v) {
        Properties p = new Properties();
        p.setProperty(k, v);
        return p;
    }
}
