package com.muoniumplayer.render.skija;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.muoniumplayer.testsupport.ProjectPaths;

/**
 * 1.21.11 移植期新增的结构性不变量。纯文本扫描，不需要 GL、不需要启动客户端。
 *
 * <p>钉的都是「改错了不会编译失败、只会在实机上表现为画面异常」的连线：
 * <ol>
 *   <li><b>帧末合成的链路完整</b>。1.21.11 的 GUI 是延迟批处理，
 *       {@code DrawContext} 只往 {@code GuiRenderState} 追加 render state，
 *       真正提交在帧末的 {@code GuiRenderer#render} 里。所以本渲染层的离屏图必须由
 *       {@code GuiRendererMixin} 在那个方法的出口贴回去。少了 mixin 注册，
 *       编译照样通过，实机表现是「界面被原版 GUI 整片盖住」——非常难联想到原因。</li>
 *   <li><b>{@code endFrame} 不再自己合成</b>。回退成在 endFrame 里合成就是上一条的故障。</li>
 *   <li><b>GL 状态「快照 + 原样还原」与 unpack 布局复位没有被删</b>。
 *       这两条各自对应一个已修复的实机故障（天空/云/手持物错色；文字缺大半字符）。</li>
 *   <li><b>GlStateManager 的包名是 1.21.11 的 {@code blaze3d.opengl}</b>。</li>
 *   <li><b>access widener 保持空</b>。1.21.11 里那两个附件字段已从 int 变成 GpuTexture，
 *       按旧签名放开会让 AW 解析直接失败（启动期崩）。</li>
 * </ol>
 */
class Port121StaticTest {

    private static final Path MAIN_JAVA = ProjectPaths.sharedJava();
    private static final Path MAIN_RESOURCES = ProjectPaths.sharedResources();
    private static final Path SKIJA_HOST =
            MAIN_JAVA.resolve(Paths.get("com", "muoniumplayer", "render", "skija", "SkijaHost.java"));
    private static final Path COMPOSITE_MIXIN =
            MAIN_JAVA.resolve(Paths.get("com", "muoniumplayer", "mixin", "FramebufferMixin.java"));
    // mixin 配置与 access widener 是 Fabric 侧独有的资源，多加载器化之后从共享的
    // src/main/resources 搬到了 fabric/src/main/resources（NeoForge 侧另有一份 mixins.json）。
    private static final Path FABRIC_RESOURCES =
            ProjectPaths.fabric().resolve(Paths.get("src", "main", "resources"));
    private static final Path MIXIN_CONFIG = FABRIC_RESOURCES.resolve("muonium.mixins.json");
    private static final Path NEOFORGE_MIXIN_CONFIG = ProjectPaths.neoforge()
            .resolve(Paths.get("src", "main", "resources", "muonium.mixins.json"));

    @Test
    @DisplayName("帧末合成的三段链路都在：mixin 源文件、mixins.json 注册、SkijaHost.compositeFrame")
    void 帧末合成链路完整() throws IOException {
        assertTrue(Files.exists(COMPOSITE_MIXIN),
                "FramebufferMixin 不见了；没有它，Skija 画的内容永远不会被贴到屏幕上");

        String mixin = read(COMPOSITE_MIXIN);
        assertTrue(mixin.contains("SkijaHost.compositeFrame()"),
                "FramebufferMixin 必须调用 SkijaHost.compositeFrame()");
        assertTrue(mixin.contains("method = \"blitToScreen()V\""),
                "注入目标必须是 Framebuffer#blitToScreen()V");
        assertTrue(mixin.contains("@At(\"TAIL\")"),
                "必须挂出口。挂入口时默认帧缓冲里还没有这一帧的画面，"
                        + "随后的整屏 blit 会把我们贴的内容全部覆盖");
        // 映射统一到 Mojang 官方名后这里是 getMainRenderTarget()（yarn 的 getFramebuffer()）。
        assertTrue(mixin.contains("client.getMainRenderTarget() != (Object) this"),
                "必须判等主帧缓冲：blitToScreen 定义在抽象类上，"
                        + "别的 mod 对其它 RenderTarget 实例调用时不应触发合成");

        // 两个加载器各有一份 mixins.json，漏了哪一份就是那一侧界面不显示。
        for (Path config : List.of(MIXIN_CONFIG, NEOFORGE_MIXIN_CONFIG)) {
            String text = read(config);
            assertTrue(text.contains("\"FramebufferMixin\""),
                    config + " 的 client 数组里没有 FramebufferMixin，mixin 根本不会被加载");
            assertFalse(text.contains("GuiRendererMixin"),
                    "GuiRendererMixin 是已被证伪的旧方案（贴到默认帧缓冲后会被 blitToScreen 覆盖），"
                            + "不允许再注册：" + config);
        }

        String host = read(SKIJA_HOST);
        assertTrue(host.contains("public static void compositeFrame()"),
                "SkijaHost.compositeFrame 是 mixin 的唯一入口，签名不能动");
    }

    @Test
    @DisplayName("合成目标是写死的窗口默认帧缓冲，不是「当前绑定」")
    void 合成目标不读当前绑定() throws IOException {
        String host = stripComments(read(SKIJA_HOST));
        assertTrue(host.contains("private static final int WINDOW_FRAMEBUFFER = 0;"),
                "合成目标必须是常量。1.21.11 里每个 render pass 结束都会把绑定重置成 0，"
                        + "「当前绑定」在帧末不携带任何有用信息");
        assertTrue(host.contains("composite(WINDOW_FRAMEBUFFER)"),
                "compositeFrame 必须把目标显式传给 composite(int)");
        // 只看 composite(int) 的方法体：beginDirect（A/B 对比用的调试路径）仍然合法地用 savedFbo。
        int begin = host.indexOf("private static void composite(int targetFbo)");
        assertTrue(begin > 0, "找不到 composite(int targetFbo)");
        int end = host.indexOf("private static final class GlSnapshot", begin);
        assertTrue(end > begin, "找不到 GlSnapshot，无法界定 composite 的范围");
        assertFalse(host.substring(begin, end).contains("savedFbo"),
                "composite 不能再拿 savedFbo 当目标 FBO：那个字段的职责是「还原时写回什么」，"
                        + "兼任合成目标正是贴错目标的原因");
    }

    @Test
    @DisplayName("endFrame 只做 flush，不做合成")
    void endFrame不再合成() throws IOException {
        String host = stripComments(read(SKIJA_HOST));
        int begin = host.indexOf("public static void endFrame()");
        assertTrue(begin > 0, "找不到 endFrame");
        int end = host.indexOf("public static void compositeFrame()", begin);
        assertTrue(end > begin, "找不到 compositeFrame，无法界定 endFrame 的范围");

        String body = host.substring(begin, end);
        assertFalse(body.contains("composite();"),
                "endFrame 里又开始合成了。1.21.11 的调用点全在 GUI 的「收集」阶段，"
                        + "此刻原版 GUI 一个像素都没画，在这里贴图会被随后提交的原版 GUI 盖住");
        assertTrue(body.contains("pendingComposite = true"),
                "endFrame 必须打上「本帧有内容待合成」的标记，否则帧末不会贴图");
    }

    @Test
    @DisplayName("GL 状态快照还原与 unpack 布局复位都还在（各自对应一个已修复的实机故障）")
    void 状态还原红线未被回退() throws IOException {
        String host = read(SKIJA_HOST);
        assertTrue(host.contains("SNAPSHOT.capture()") && host.contains("SNAPSHOT.restore()"),
                "GlSnapshot 的「拍快照 + 原样写回」不能退回「设成一套已知的好值」："
                        + "MC 的状态管理器带缓存，且压根不管混合方程/模板开关/采样器对象/sRGB，"
                        + "实机症状是天空与云错色、全景图糊掉、手持与掉落物贴图错乱");
        assertTrue(host.contains("glBindSampler"),
                "采样器对象必须进快照：一旦绑上就完全覆盖纹理自身的过滤/包裹参数");
        assertTrue(host.contains("private static void resetPixelUnpackLayout()"),
                "resetPixelUnpackLayout 不能删：MC 的 NativeImage#upload 设了 "
                        + "GL_UNPACK_ROW_LENGTH/SKIP_PIXELS/SKIP_ROWS 且用完不还原，"
                        + "不清掉 Skia 字形图集增量上传会错位，症状是「只有文字缺大半字符」");

        int captures = count(host, "SNAPSHOT.capture()");
        assertTrue(captures >= 2,
                "beginFrame 与 compositeFrame 两个入口都要各自拍一次快照，实测只有 " + captures + " 次");
    }

    @Test
    @DisplayName("纹理绑定的还原必须同时写 GlStateManager 的缓存和裸 GL")
    void 纹理还原不能只走缓存() throws IOException {
        String host = stripComments(read(SKIJA_HOST));

        assertTrue(host.contains("private static void selectUnit(int textureEnum)")
                        && host.contains("private static void bindTextureExactly(int texture)"),
                "GlSnapshot 里缺了 selectUnit / bindTextureExactly。"
                        + "_activeTexture 与 _bindTexture 都是「值和缓存相同就跳过 GL 调用」的实现，"
                        + "而 Skia 用裸 GL 改绑定、缓存压根没变，"
                        + "所以「把缓存值再设一遍」会整段变成空操作");

        int begin = host.indexOf("void restore() {");
        assertTrue(begin > 0, "找不到 GlSnapshot.restore()");
        int end = host.indexOf("private static void selectUnit(int textureEnum)", begin);
        assertTrue(end > begin, "找不到 selectUnit，无法界定 restore 的范围");
        String body = host.substring(begin, end);
        assertTrue(body.contains("selectUnit(") && body.contains("bindTextureExactly("),
                "restore 必须通过这两个 helper 恢复纹理状态");
        assertFalse(body.contains("GlStateManager._bindTexture("),
                "restore 里不许直接调 GlStateManager._bindTexture 恢复绑定："
                        + "缓存相同就会被跳过，真实绑定留在 Skia 那边。"
                        + "后果延迟到下一次纹理上传——GlCommandEncoder#writeToTexture 里那句 "
                        + "_bindTexture 同样被跳过，字形写进了 Skia 的纹理，"
                        + "原版字体图集对应格子永远是垃圾像素（实测「回到游戏」的「游戏」变彩色噪点）");

        assertTrue(host.contains("org.lwjgl.opengl.GL13.glActiveTexture(textureEnum)"),
                "selectUnit 必须补一次裸 glActiveTexture，保证真实活动单元确实切过去");
        assertTrue(host.contains("org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0)"),
                "bindTextureExactly 在快照值为 0 时必须用裸 glBindTexture 兜底："
                        + "此时缓存可能已经是 0 而被跳过");
    }

    @Test
    @DisplayName("GlStateManager 用的是 1.21.11 的 blaze3d.opengl 包")
    void GlStateManager包名已更新() throws IOException {
        for (Path file : new Path[]{
                SKIJA_HOST,
                MAIN_JAVA.resolve(Paths.get("com", "muoniumplayer", "compat", "gl", "GL11.java")),
                MAIN_JAVA.resolve(Paths.get("com", "muoniumplayer", "compat", "gl", "GL14.java")),
        }) {
            String code = read(file);
            assertFalse(code.contains("blaze3d.platform.GlStateManager"),
                    file + " 还在用 1.20.1 的 blaze3d.platform.GlStateManager");
        }
    }

    @Test
    @DisplayName("已从 1.21.11 删除的类没有被引用")
    void 不引用已删除的类() throws IOException {
        java.util.List<String> offenders = new java.util.ArrayList<>();
        try (var stream = Files.walk(MAIN_JAVA)) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList()) {
                // 必须去注释再扫：SkijaHost 里刻意留了「1.21.11 已删除 BufferRenderer」这类
                // 移植说明，那是给下一个版本的人看的，不去注释反而会让本测试红。
                String code = stripComments(read(file));
                if (code.contains("BufferRenderer")) {
                    offenders.add(file + " -> BufferRenderer（1.21.11 已删除该类）");
                }
                if (code.contains("GlStateManager.TEXTURE_COUNT")) {
                    offenders.add(file + " -> GlStateManager.TEXTURE_COUNT（1.21.11 已删除）");
                }
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n", offenders));
    }

    @Test
    @DisplayName("access widener 保持空条目")
    void accessWidener保持空() throws IOException {
        Path aw = FABRIC_RESOURCES.resolve("muoniumplayer.accesswidener");
        assertTrue(Files.exists(aw), "accesswidener 文件被 build.gradle 引用，不能删");
        for (String line : Files.readAllLines(aw, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("accessWidener")) {
                continue;
            }
            throw new AssertionError("accesswidener 出现了条目「" + trimmed
                    + "」。1.21.11 里 Framebuffer 的附件字段已从 int 变成 GpuTexture，"
                    + "按旧签名放开会让 AW 解析失败（启动期崩）。确有需要请连同理由一起写清楚，"
                    + "并放宽本测试。");
        }
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * 去掉行注释与块注释。注释里提到已删除的旧 API 是<b>刻意保留的移植说明</b>，
     * 不去掉的话这些说明反而会让测试红。与 {@code RenderLayerStaticTest} 同一份逻辑。
     */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean inBlock = false;
        for (String line : source.split("\\R", -1)) {
            int i = 0;
            while (i < line.length()) {
                if (inBlock) {
                    int end = line.indexOf("*/", i);
                    if (end < 0) {
                        break;
                    }
                    inBlock = false;
                    i = end + 2;
                    continue;
                }
                int block = line.indexOf("/*", i);
                int lineComment = line.indexOf("//", i);
                if (lineComment >= 0 && (block < 0 || lineComment < block)) {
                    out.append(line, i, lineComment);
                    break;
                }
                if (block >= 0) {
                    out.append(line, i, block);
                    inBlock = true;
                    i = block + 2;
                    continue;
                }
                out.append(line, i, line.length());
                break;
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
