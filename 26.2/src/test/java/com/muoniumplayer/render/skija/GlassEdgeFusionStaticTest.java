package com.muoniumplayer.render.skija;

import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.testsupport.ProjectPaths;
import io.github.humbleui.skija.RuntimeEffect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一组玻璃边缘攒成一张距离场那件事的不变量。
 *
 * <p>这里钉的四条，全都是「画出来只会略微不对、看不出是 bug」那一类，所以只能钉在代码上：</p>
 * <ol>
 *   <li>批次与逐矩形两条路的<em>折射数学必须逐字同源</em>。两处各抄一份 {@code edgeOffset}
 *       的话，同一块玻璃的两条边一条折得多一条折得少，而那种差别正好在最显眼的轮廓那一圈；</li>
 *   <li>组满必须<em>退回逐矩形</em>而不是丢掉不画：少一道颈可以接受，少一圈边缘光是 bug；</li>
 *   <li>帧末还开着的组必须丢掉而不是画：那一组坐标是在它自己那套画布矩阵下登记的，
 *       帧末矩阵已经回到恒等，照原样画就是整排亮边贴到屏幕别处；</li>
 *   <li>SkSL 必须真能在 Skia 里编译，且 uniform 字节数与 Java 侧写的完全一致
 *       （错了不会崩，只会「融合边缘这一层静默停用」）。</li>
 * </ol>
 */
class GlassEdgeFusionStaticTest {

    private static String read(String name) throws Exception {
        java.nio.file.Path path = ProjectPaths.sharedJava().resolve(Paths.get("com", "muoniumplayer",
                "render", "skija", name));
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** 取 SkSL 字符串里那个函数的整个函数体（从签名行到配对的右花括号）。 */
    private static String function(String sksl, String signature) {
        int at = sksl.indexOf(signature);
        assertTrue(at >= 0, "SkSL 里找不到 " + signature);
        int open = sksl.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < sksl.length(); i++) {
            if (sksl.charAt(i) == '{') {
                depth++;
            } else if (sksl.charAt(i) == '}') {
                depth--;
                if (depth == 0) {
                    return sksl.substring(at, i + 1).replaceAll("\\s+", " ").trim();
                }
            }
        }
        throw new AssertionError(signature + " 的花括号没配对");
    }

    @Test
    @DisplayName("折射那条式子：批次与逐矩形两份 SkSL 必须逐字一致")
    void refractionMathDoesNotFork() throws Exception {
        String fused = function(GlassEdgeBatch.source(), "float edgeOffset(");
        String single = function(GlassRefraction.source(), "float edgeOffset(");
        assertEquals(single, fused, "两条路的 Snell 近似分叉了：同一块玻璃的边缘会一条宽一条窄");
        // 圆角盒的符号距离也是同一套；抄第二份时最容易顺手改的就是那个 min/max
        assertEquals(function(GlassRefraction.source(), "float sdRoundBox("),
                function(GlassEdgeBatch.source(), "float sdRoundBox("),
                "两份 sdRoundBox 不一致：距离场错了，整张场的边都错");
    }

    @Test
    @DisplayName("批次里的常数不能自己抄一份：折射率与色散读回逐矩形那一处")
    void batchReusesRefractionConstants() throws Exception {
        String batch = read("GlassEdgeBatch.java");
        assertFalse(batch.contains("1.45f"), "批次里又写了一份折射率，改一处漏一处");
        assertTrue(batch.contains("GlassRefraction.refractiveIndex()"),
                "折射率必须从 GlassRefraction 那一处拿");
        String refraction = read("GlassRefraction.java");
        assertTrue(refraction.contains("static float refractiveIndex()"), "那份访问器没了");
    }

    @Test
    @DisplayName("组满 16 条：溢出的那条 add 返回 false，而不是被静默吞掉")
    void overflowIsReportedNotSwallowed() {
        GlassEdgeBatch.begin(6f, 0f, 0f);
        int accepted = 0;
        for (int i = 0; i < GlassEdgeBatch.MAX_EDGES + 4; i++) {
            if (GlassEdgeBatch.add(10 + i * 20, 10, 18, 30, 6, 9, 1f, 0.055f, 0f, 0.34f, 1f, 12f)) {
                accepted++;
            } else {
                // 调用点在这里退回逐矩形各画各的；批次这边只许记一笔
                assertFalse(GlassEdgeBatch.size() > GlassEdgeBatch.MAX_EDGES, "组里不许超过上限");
            }
        }
        assertEquals(GlassEdgeBatch.MAX_EDGES, accepted,
                "恰好收下 MAX_EDGES 条，多出来的必须回 false 让调用点自己画");
        GlassEdgeBatch.clear();
        assertFalse(GlassEdgeBatch.isOpen(), "clear 之后不该还开着组");
    }

    @Test
    @DisplayName("没开组时 add 一律拒绝：不许把上一组剩下的东西接到下一组上")
    void addRequiresOpenGroup() {
        GlassEdgeBatch.clear();
        assertFalse(GlassEdgeBatch.add(0, 0, 20, 20, 4, 9, 1f, 0.055f, 0f, 0.34f, 1f, 12f),
                "关着组还能登记，等于把两组混成一张场");
    }

    @Test
    @DisplayName("begin 时上一组没收尾：先画掉旧的，不许把两组叠成一张场")
    void beginFlushesAnOpenGroupFirst() throws Exception {
        String batch = read("GlassEdgeBatch.java");
        int at = batch.indexOf("public static void begin(float smoothing, float offsetX, "
                + "float offsetY, float[] matrix)");
        assertTrue(at > 0, "带矩阵那一份 begin 没了：组内各块控件的坐标系就没地方烘回来了");
        String head = batch.substring(at, at + 700);
        assertTrue(head.contains("flush()") && head.indexOf("flush()") < head.indexOf("open = true"),
                "begin 必须先收尾上一组：" + head);
    }

    /* ==================== 组内换矩阵：把每条边烘回开组那一刻那套坐标系 ==================== */

    /** 行主序 {@code [sx, kx, tx, ky, sy, ty, p0, p1, p2]}，与 Skia 的 Matrix33 同一个布局。 */
    private static float[] matrix(float sx, float sy, float tx, float ty) {
        return new float[]{sx, 0f, tx, 0f, sy, ty, 0f, 0f, 1f};
    }

    @Test
    @DisplayName("纯位移：兄弟控件各自平移，烘回容器坐标系之后位置对得上")
    void bakesTranslationOnly() {
        float[] group = matrix(2f, 2f, 10f, 20f);      // 容器：缩放 2 + 平移
        float[] current = matrix(2f, 2f, 34f, 44f);    // 同一缩放，设备上再多走 (12, 12)
        float[] baked = GlassEdgeBatch.bakeIntoGroup(group, current, 5, 6, 20, 30, 3f, 9f);
        // 局部 (5,6) 在 current 下是设备 (2*5+34, 2*6+44) = (44,56)；
        // 回到 group 那套就是 ((44-10)/2, (56-20)/2) = (17,18)，宽高与半径厚度都不该变。
        assertArrayEquals(new float[]{17f, 18f, 20f, 30f, 3f, 9f}, baked, 0.0001f);
    }

    @Test
    @DisplayName("每行带自己的缩放：宽高、圆角与折射厚度一起吃这个比例")
    void bakesPerEntryScale() {
        float[] group = matrix(1f, 1f, 0f, 0f);
        // 进场动画那种「绕行中心 scale(0.6)」= translate(c)·scale·translate(-c)：
        // 中心 (100,20) 时矩阵就是 [0.6,0,40 · 0,0.6,8]。
        float[] current = matrix(0.6f, 0.6f, 40f, 8f);
        float[] baked = GlassEdgeBatch.bakeIntoGroup(group, current, 10, 5, 200, 30, 2f, 9f);
        assertArrayEquals(new float[]{46f, 11f, 120f, 18f, 1.2f, 5.4f}, baked, 0.0001f);
    }

    @Test
    @DisplayName("两套矩阵之间有旋转 / 读不到当前矩阵：这条不入组，逐矩形那条路还在")
    void refusesWhenNotAxisAligned() {
        float[] group = matrix(1f, 1f, 0f, 0f);
        float[] rotated = new float[]{0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f};
        assertNull(GlassEdgeBatch.bakeIntoGroup(group, rotated, 0, 0, 20, 20, 4f, 9f),
                "旋转之下轴对齐盒子像斜四边形，距离场表达不了，硬烘就是折到画面别处");
        assertNull(GlassEdgeBatch.bakeIntoGroup(group, null, 0, 0, 20, 20, 4f, 9f),
                "没有当前矩阵就不知道该怎么搬，只能不入组");
        assertNull(GlassEdgeBatch.bakeIntoGroup(null, group, 0, 0, 20, 20, 4f, 9f),
                "没有开组坐标系也没什么可烘的");
        // 退化（缩放 0）也不许过：那会把整条边压成一个点
        assertNull(GlassEdgeBatch.bakeIntoGroup(group, matrix(0f, 1f, 0f, 0f), 0, 0, 20, 20, 4f, 9f));
    }

    @Test
    @DisplayName("收尾必须把画布矩阵按回开组那一套，但本来就一样时不碰 save")
    void flushPinsMatrixOnlyWhenItChanged() throws Exception {
        String batch = read("GlassEdgeBatch.java");
        int at = batch.indexOf("public static int flush()");
        assertTrue(at > 0, "找不到 flush()");
        String body = batch.substring(at, batch.indexOf("private static boolean matches", at));
        assertTrue(body.contains("canvas.setMatrix(new Matrix33("), "收尾没按回开组那一刻的矩阵");
        assertTrue(body.contains("boolean pin ="), "矩阵没变过也要 save/setMatrix：一次 save 要还记账");
        assertTrue(body.contains("canvas.restoreToCount(depth)"), "save 之后必须配平还原");
        assertTrue(body.indexOf("canvas.save()") < body.indexOf("canvas.drawRect("),
                "setMatrix 必须发生在 save 与 restore 之间");
    }

    @Test
    @DisplayName("融合半径：0 / 负 / NaN 都算关，超出 32 钳住")
    void fusionRadiusClampsConfiguredValue() {
        float keep = HudConfig.glassFusion;
        try {
            HudConfig.glassFusion = 0f;
            assertEquals(0f, GlassLook.fusionRadius());
            HudConfig.glassFusion = -3f;
            assertEquals(0f, GlassLook.fusionRadius(), "负数要当关掉，不能变成「往反方向融合」");
            HudConfig.glassFusion = Float.NaN;
            assertEquals(0f, GlassLook.fusionRadius(), "NaN 一路乘下去会把整组边缘算成不可见");
            HudConfig.glassFusion = 90f;
            assertEquals(32f, GlassLook.fusionRadius(), 0.001f,
                    "半径不设上限会让整排控件糊成一坨，且每像素要过的条目剪形范围也跟着涨");
            HudConfig.glassFusion = 18f;
            assertEquals(18f, GlassLook.fusionRadius(), 0.001f);
        } finally {
            HudConfig.glassFusion = keep;
        }
    }

    /* ==================== 接线：容器那一层 ==================== */

    private static String readShared(String... parts) throws Exception {
        String[] full = new String[parts.length + 2];
        full[0] = "com";
        full[1] = "muoniumplayer";
        System.arraycopy(parts, 0, full, 2, parts.length);
        java.nio.file.Path path = ProjectPaths.sharedJava().resolve(
                Paths.get(full[0], java.util.Arrays.copyOfRange(full, 1, full.length)));
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    @DisplayName("容器括组：开在裁剪钩子之后、收在裁剪钩子之前，而且在 finally 里")
    void bracketSitsInsideTheClip() throws Exception {
        String widget = readShared("core", "rendering", "ui", "AbstractWidget.java");
        int at = widget.indexOf("public void renderWidget(double mouseX, double mouseY, int dWheel)");
        assertTrue(at > 0, "找不到 renderWidget");
        String body = widget.substring(at, widget.indexOf("public SELF setShouldOverrideMouseCursor", at));
        assertTrue(body.indexOf("GlassLook.beginFusion(") > body.indexOf("this.beforeRenderChildren("),
                "开组要在容器的裁剪/变换钩子之后，否则这一组的坐标系不是视口那一套");
        int fin = body.indexOf("} finally {");
        int end = body.indexOf("GlassLook.endFusion();", fin);
        assertTrue(end > fin, "收尾不在 finally 里：子控件抛异常就漏一组");
        assertTrue(end < body.indexOf("this.afterRenderChildren(", fin),
                "收尾必须发生在 afterRenderChildren 收裁剪之前，否则滚出视口的行把亮边盖到面板别处");
        assertTrue(body.indexOf("GlassLook.endFusion();", fin) > body.indexOf("GlassLook.beginFusion("),
                "顺序必须是 begin → 画子控件 → end");
    }

    @Test
    @DisplayName("只有那两列曲目行点名了融合：别处接上之前先要数清有几块 PLATE")
    void onlyTheRowListsAreWired() throws Exception {
        assertTrue(readShared("core", "screens", "ncm", "panels", "PlaylistPanel.java")
                        .contains("musicsPanel.setGlassFusion(GlassLook.Surface.PLAYER)"),
                "歌单详情那一列没接融合");
        assertTrue(readShared("core", "screens", "ncm", "panels", "PersonalFmPanel.java")
                        .contains("list.setGlassFusion(GlassLook.Surface.PLAYER)"),
                "私人 FM 历史那一列没接融合");
        final java.nio.file.Path root = ProjectPaths.sharedJava();
        final List<String> wired = new ArrayList<>();
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file,
                                                           java.nio.file.attribute.BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (name.endsWith(".java")) {
                    try {
                        String src = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                        int at = src.indexOf(".setGlassFusion(");
                        if (at >= 0 && !file.endsWith(root.resolve(Paths.get("com", "muoniumplayer",
                                "core", "rendering", "ui", "AbstractWidget.java")))) {
                            wired.add(root.relativize(file).toString());
                        }
                    } catch (IOException ignored) {
                        // 读不到的文件不算接线
                    }
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        assertEquals(2, wired.size(), "点名融合的第 3 处出现了：" + wired
                + "。每一处都要先确认那一层裁剪里真的排着几块挨着的 PLATE，"
                + "接在没有成片兄弟的容器上等于白接，接在含融合容器的外层上会把内层的边拖到界外画");
    }

    @Test
    @DisplayName("帧末没收尾的组要丢掉，不许在恒等矩阵下照原样画")
    void abandonedGroupIsDropped() throws Exception {
        String batch = read("GlassEdgeBatch.java");
        int at = batch.indexOf("static void abandonIfOpen()");
        assertTrue(at > 0, "帧末那一道兜底闸没了");
        String body = batch.substring(at, batch.indexOf("public static void clear()", at));
        assertTrue(body.contains("clear()"), "必须是丢掉");
        assertFalse(body.contains("flush()"), "帧末画掉会用错坐标系：那一组坐标是在它自己的矩阵下登记的");
        String host = read("SkijaHost.java");
        assertTrue(host.contains("GlassEdgeBatch.abandonIfOpen()"), "endFrame 没接这道兜底");
    }

    @Test
    @DisplayName("融合那份 SkSL 真能在 Skia 里编译，uniform 就是 50 个 float4")
    void fusedShaderCompiles() {
        String sksl = GlassEdgeBatch.source();
        long declared = sksl.lines().map(String::trim).filter(line -> line.startsWith("uniform ")).count();
        // uBackdrop + uCount + uRects[24] + uShapes[24] + uMisc[24] + uSample
        assertEquals(6L, declared, "uniform 行数变了要同步改 Java 侧打包顺序与这条断言");
        assertTrue(sksl.contains("uniform float4 uRects[" + GlassEdgeBatch.MAX_EDGES + "]"),
                "数组长度与 Java 侧的 MAX_EDGES 必须是同一个数");
        Supplier<RuntimeEffect> make = () -> RuntimeEffect.makeForShader(sksl);
        try (RuntimeEffect effect = make.get()) {
            assertNotNull(effect, "SkSL 编译失败：融合边缘会静默停用");
            int expected = (3 * GlassEdgeBatch.MAX_EDGES + 2) * 16;
            assertEquals(expected, effect.getUniformSize(),
                    "uCount + 三份数组 + uSample = " + (3 * GlassEdgeBatch.MAX_EDGES + 2)
                            + " 个 float4；Java 侧 uniforms() 按这个顺序写，多一格少一格 Skia 都给 nullptr");
        }
    }

    @Test
    @DisplayName("中央那片必须真的透明：延后一帧画的东西不许盖住已经画好的字")
    void interiorStaysTransparent() {
        String sksl = GlassEdgeBatch.source();
        assertTrue(sksl.contains("return half4(0.0);"), "场之外要写透明");
        assertTrue(sksl.contains("band <= 0.002"), "中央那片「只糊不折」的早退没了：会盖住文字");
    }
}
