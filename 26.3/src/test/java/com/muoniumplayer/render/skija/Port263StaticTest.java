package com.muoniumplayer.render.skija;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.muoniumplayer.testsupport.ProjectPaths;

/**
 * 26.3 移植期的结构性不变量（前身是 26.2 的 {@code Port262StaticTest}）。
 *
 * <p>26.3 相对 26.2 只动了两个面，本测试也跟着钉：
 * <ol>
 *   <li><b>GPU 层搬进了 {@code com.mojang.renderpearl.*}</b>：帧末合成的目标类、
 *       GlStateManager 全部换包，方法名与描述符没变（javap 两版逐条对过）。</li>
 *   <li><b>窗口/输入后端从 GLFW 换成了 SDL</b>：依赖里已经没有 lwjgl-glfw，
 *       键码空间也从 GLFW 码换成 SDL scancode；滚轮没有了可以套的回调，
 *       改挂 {@code MouseHandler#onScroll} 的入口。</li>
 * </ol>
 * 纯文本扫描，不需要 GL、不需要启动客户端。
 *
 * <p>钉的都是「改错了不会编译失败、只会在实机上表现为画面异常」的连线：
 * <ol>
 *   <li><b>帧末合成的链路完整</b>。26.2 的 GUI 仍是延迟批处理，
 *       {@code GuiGraphicsExtractor} 只往 render state 追加元素，真正提交在帧末。
 *       而 1.21.11 用的钩子 {@code Framebuffer#blitToScreen()} 在 26.2 已被删除
 *       （渲染后端抽象成了 {@code GpuDevice} / {@code GpuSurface}），
 *       新钩子是 {@code GlSurfaceMixin} 挂在 {@code GlSurface#present()} 的入口。
 *       少了 mixin 注册，编译照样通过，实机表现是「界面被原版 GUI 整片盖住」
 *       或干脆什么都不出现——非常难联想到原因。</li>
 *   <li><b>{@code endFrame} 不再自己合成</b>。回退成在 endFrame 里合成就是上一条的故障。</li>
 *   <li><b>GL 状态「快照 + 原样还原」与 unpack 布局复位没有被删</b>。
 *       这两条各自对应一个已修复的实机故障（天空/云/手持物错色；文字缺大半字符）。</li>
 *   <li><b>GlStateManager 的包名是 {@code blaze3d.opengl}</b>（1.20.1 时在 {@code blaze3d.platform}）。</li>
 *   <li><b>Vulkan 后端有明确报错</b>。26.2 新增了 Vulkan 后端，而本渲染层是 GL 互操作。
 *       缺了探测就会退化成「无报错、界面就是不出现」这一最难排查的症状。</li>
 *   <li><b>不再引用 access widener</b>。26.2 的官方 jar 不混淆、也不需要 AW，
 *       原本那份空文件连同 build.gradle / fabric.mod.json 的声明一起删掉了；
 *       留着空文件反而会误导下一个人以为需要放开字段。</li>
 *   <li><b>Yarn 专有名称已经清空</b>。26.2 没有 intermediary 映射，
 *       残留 Yarn 名不会被重映射，只会在运行期抛 NoClassDefFoundError。</li>
 * </ol>
 */
class Port263StaticTest {

    private static final Path MAIN_JAVA = ProjectPaths.sharedJava();
    private static final Path MAIN_RESOURCES = ProjectPaths.sharedResources();
    // fabric.mod.json / mixins.json 是 Fabric 侧独有的资源，多加载器化之后
    // 从共享的 src/main/resources 搬到了 fabric/src/main/resources。
    private static final Path FABRIC_RESOURCES =
            ProjectPaths.fabric().resolve(Paths.get("src", "main", "resources"));
    private static final Path SKIJA_HOST =
            ProjectPaths.sourceFile(Paths.get("com", "muoniumplayer", "render", "skija", "SkijaHost.java"));
    private static final Path COMPOSITE_MIXIN =
            ProjectPaths.sourceFile(Paths.get("com", "muoniumplayer", "mixin", "GlSurfaceMixin.java"));
    private static final Path MIXIN_CONFIG = FABRIC_RESOURCES.resolve("muonium.mixins.json");
    private static final Path NEOFORGE_MIXIN_CONFIG = ProjectPaths.neoforge()
            .resolve(Paths.get("src", "main", "resources", "muonium.mixins.json"));

    @Test
    @DisplayName("帧末合成的三段链路都在：mixin 源文件、mixins.json 注册、SkijaHost.compositeFrame")
    void 帧末合成链路完整() throws IOException {
        assertTrue(Files.exists(COMPOSITE_MIXIN),
                "GlSurfaceMixin 不见了；没有它，Skija 画的内容永远不会被贴到屏幕上");

        String mixin = read(COMPOSITE_MIXIN);
        assertTrue(mixin.contains("SkijaHost.compositeFrame()"),
                "GlSurfaceMixin 必须调用 SkijaHost.compositeFrame()");
        assertTrue(mixin.contains("@Mixin(GlSurface.class)")
                        && mixin.contains("import com.mojang.renderpearl.backend.opengl.GlSurface;"),
                "必须挂 OpenGL 后端的 GlSurface，而不是后端无关的 GpuSurface："
                        + "Skija 走的是 GL 互操作，Vulkan 后端下不能触发这个钩子");
        assertTrue(mixin.contains("method = \"present()V\""),
                "注入目标必须是 GlSurface#present()V —— 26.3 里它的全部字节码只有一句 SDL_GL_SwapWindow");
        assertTrue(mixin.contains("@At(\"HEAD\")"),
                "必须挂入口。此时 blitFromTexture 已经把主目标整屏 blit 到默认帧缓冲（FBO 0），"
                        + "而 SDL_GL_SwapWindow 还没执行；挂出口的话画面已经交换出去，贴的内容看不见");
        // 同样去注释：GlSurfaceMixin 的类注释里必须讲清「旧钩子 blitToScreen 为什么没了」，
        // 这里只禁止它出现在真实代码里。
        assertFalse(stripComments(mixin).contains("blitToScreen"),
                "Framebuffer#blitToScreen() 在 26.2 已被删除，不能再作为钩子");

        // 两个加载器各有一份 mixins.json，漏了哪一份就是那一侧界面不显示。
        for (Path each : java.util.List.of(MIXIN_CONFIG, NEOFORGE_MIXIN_CONFIG)) {
            assertTrue(read(each).contains("\"GlSurfaceMixin\""),
                    each + " 的 client 数组里没有 GlSurfaceMixin，mixin 根本不会被加载");
        }
        String config = read(MIXIN_CONFIG);
        assertTrue(config.contains("\"GlSurfaceMixin\""),
                "muonium.mixins.json 的 client 数组里没有 GlSurfaceMixin，mixin 根本不会被加载");
        assertFalse(config.contains("FramebufferMixin"),
                "FramebufferMixin 是 1.21.11 的钩子，其目标方法 blitToScreen() 在 26.2 已不存在，"
                        + "注册它只会在启动期报「找不到注入目标」");
        assertFalse(config.contains("GuiRendererMixin"),
                "GuiRendererMixin 是已被证伪的旧方案（贴到默认帧缓冲后会被随后的整屏 blit 覆盖），"
                        + "不允许再注册");

        String host = read(SKIJA_HOST);
        assertTrue(host.contains("public static void compositeFrame()"),
                "SkijaHost.compositeFrame 是 mixin 的唯一入口，签名不能动");
    }

    @Test
    @DisplayName("合成目标是写死的窗口默认帧缓冲，不是「当前绑定」")
    void 合成目标不读当前绑定() throws IOException {
        String host = stripComments(read(SKIJA_HOST));
        assertTrue(host.contains("private static final int WINDOW_FRAMEBUFFER = 0;"),
                "合成目标必须是常量。每个 render pass 结束时 GlCommandEncoder#closePass() 都会把绑定重置成 0，"
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
                "endFrame 里又开始合成了。26.2 的调用点全在 GUI 的「收集」阶段，"
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
    @DisplayName("GlStateManager 在 26.3 的 renderpearl.backend.opengl 包（不是 blaze3d 下的任何一个）")
    void GlStateManager包名已更新() throws IOException {
        for (Path file : new Path[]{
                SKIJA_HOST,
                ProjectPaths.sourceFile(Paths.get("com", "muoniumplayer", "compat", "gl", "GL11.java")),
                ProjectPaths.sourceFile(Paths.get("com", "muoniumplayer", "compat", "gl", "GL14.java")),
        }) {
            String code = read(file);
            assertFalse(code.contains("blaze3d.platform.GlStateManager"),
                    file + " 还在用 1.20.1 的 blaze3d.platform.GlStateManager");
            assertFalse(code.contains("blaze3d.opengl.GlStateManager"),
                    file + " 还在用 26.2 的 blaze3d.opengl.GlStateManager —— "
                            + "26.3 把整个 GPU 层搬进了 com.mojang.renderpearl.backend.opengl");
            assertTrue(code.contains("renderpearl.backend.opengl.GlStateManager"),
                    file + " 没有引用 26.3 的 renderpearl.backend.opengl.GlStateManager");
        }
    }

    @Test
    @DisplayName("已从 26.2 删除的 API 没有被引用")
    void 不引用已删除的类() throws IOException {
        java.util.List<String> offenders = new java.util.ArrayList<>();
        { java.util.stream.Stream<Path> stream = ProjectPaths.allJavaFiles().stream();
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList()) {
                // 必须去注释再扫：SkijaHost 与各兼容层里刻意留了「XX 已删除」这类
                // 移植说明，那是给下一个版本的人看的，不去注释反而会让本测试红。
                String code = stripComments(read(file));
                if (code.contains("BufferRenderer")) {
                    offenders.add(file + " -> BufferRenderer（1.21.11 起已删除该类）");
                }
                if (code.contains("GlStateManager.TEXTURE_COUNT")) {
                    offenders.add(file + " -> GlStateManager.TEXTURE_COUNT（1.21.11 起已删除）");
                }
                if (code.contains("blitToScreen")) {
                    offenders.add(file + " -> blitToScreen（26.2 起 RenderTarget 上已无此方法）");
                }
                if (code.contains("HudRenderCallback")) {
                    offenders.add(file + " -> HudRenderCallback（Fabric API 26.2 已删除，"
                            + "改用 HudElementRegistry）");
                }
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n", offenders));
    }

    @Test
    @DisplayName("access widener 已彻底移除：文件、build.gradle 声明、fabric.mod.json 声明三处一致")
    void accessWidener已移除() throws IOException {
        Path aw = FABRIC_RESOURCES.resolve("muoniumplayer.accesswidener");
        assertFalse(Files.exists(aw),
                "26.2 不需要 access widener（本项目一条条目都没有），空文件只会误导下一个人"
                        + "以为必须放开某些字段。如果确实需要，请连同理由一起补上并放宽本测试。");
        // 去注释再判断：build.gradle 里刻意留了「为什么不声明 AW」的说明，
        // 那正是要传给下一个人的信息，不能因为它提到了这个词就让测试红。
        assertFalse(stripComments(read(ProjectPaths.fabric().resolve("build.gradle"))).contains("accessWidenerPath"),
                "build.gradle 仍声明 accessWidenerPath，而文件已不存在 —— 配置期就会失败");
        assertFalse(read(FABRIC_RESOURCES.resolve("fabric.mod.json")).contains("accessWidener"),
                "fabric.mod.json 仍声明 accessWidener，而文件已不存在 —— 启动期 Loader 会报错");
    }

    @Test
    @DisplayName("Vulkan 后端有明确报错，而不是静默不渲染")
    void 后端探测未被删() throws IOException {
        String host = stripComments(read(SKIJA_HOST));
        assertTrue(host.contains("private static boolean requireOpenGlBackend()"),
                "requireOpenGlBackend 不能删：26.2 允许把图形 API 切成 Vulkan，"
                        + "而本渲染层用 DirectContext.makeGL() 直接复用 GL 上下文，"
                        + "Vulkan 下帧末钩子根本不会触发。缺了探测的症状是"
                        + "「无异常、无 GL 错误、界面就是不出现」");
        assertTrue(host.contains("if (!requireOpenGlBackend())"),
                "beginFrame 必须先过后端探测，否则探测写了也不生效");
        assertTrue(host.contains("RenderSystem.tryGetDevice()"),
                "必须用 tryGetDevice()：getDevice() 在渲染器尚未初始化时直接抛 "
                        + "IllegalStateException，那只说明「问得太早」，不该把渲染层永久停用");
        assertTrue(host.contains("backendName()"),
                "探测要靠 backendName()：26.2 的 GpuDevice 是具体类、后端差异藏在私有字段里，"
                        + "26.3 虽然把 GpuDevice 变成了接口，但 instanceof 要引用具体后端实现类，"
                        + "等于把「只支持 OpenGL」写进编译期依赖；公开可达的标识是 DeviceInfo#backendName()");
    }

    @Test
    @DisplayName("主源码里没有残留的 Yarn 专有名称（26.2 无 intermediary，残留即运行期崩）")
    void 无Yarn名残留() throws IOException {
        // key = Yarn 名，value = 26.2 官方名，报错时直接给出替换目标。
        java.util.Map<String, String> yarnToOfficial = new java.util.LinkedHashMap<>();
        yarnToOfficial.put("MinecraftClient", "net.minecraft.client.Minecraft");
        yarnToOfficial.put("net.minecraft.util.Identifier", "net.minecraft.resources.Identifier");
        yarnToOfficial.put("net.minecraft.client.gui.DrawContext",
                "net.minecraft.client.gui.GuiGraphicsExtractor");
        yarnToOfficial.put("net.minecraft.client.font.TextRenderer",
                "net.minecraft.client.gui.Font");
        yarnToOfficial.put("net.minecraft.text.Text", "net.minecraft.network.chat.Component");
        yarnToOfficial.put("net.minecraft.client.gui.screen.", "net.minecraft.client.gui.screens.");
        yarnToOfficial.put("net.minecraft.client.gl.", "com.mojang.blaze3d.pipeline. / .opengl.");
        yarnToOfficial.put("net.minecraft.client.util.", "com.mojang.blaze3d.platform. 等");
        yarnToOfficial.put("net.minecraft.sound.SoundEvents", "net.minecraft.sounds.SoundEvents");
        yarnToOfficial.put("PositionedSoundInstance",
                "net.minecraft.client.resources.sounds.SimpleSoundInstance");
        yarnToOfficial.put("KeyBindingHelper",
                "net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper");

        java.util.List<String> offenders = new java.util.ArrayList<>();
        { java.util.stream.Stream<Path> stream = ProjectPaths.allJavaFiles().stream();
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    // repackage/ 是第三方源码原样内嵌，不属于本项目的移植范围。
                    .filter(p -> !p.toString().replace('\\', '/').contains("/repackage/"))
                    .toList()) {
                String code = stripComments(read(file));
                yarnToOfficial.forEach((yarn, official) -> {
                    if (code.contains(yarn)) {
                        offenders.add(file + " -> " + yarn + "（Yarn 名，26.2 应为 " + official + "）");
                    }
                });
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n", offenders));
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

    @Test
    @DisplayName("26.3 的窗口/输入后端是 SDL：源码里不许再有 lwjgl-glfw")
    void 不再有GLFW依赖() throws IOException {
        java.util.List<String> offenders = new java.util.ArrayList<>();
        { java.util.stream.Stream<Path> stream = ProjectPaths.allJavaFiles().stream();
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList()) {
                // 去注释再扫：各兼容层的注释里刻意留着「26.2 及更早这里是 glfwXxx」的说明。
                String code = stripComments(read(file));
                if (code.contains("org.lwjgl.glfw")) {
                    offenders.add(file + " -> org.lwjgl.glfw（26.3 的依赖里已经没有 lwjgl-glfw）");
                }
                if (code.contains("getPlatform().openUri")) {
                    offenders.add(file + " -> Util.getPlatform().openUri(...)（26.3 的 OS 上已无 openUri，"
                            + "开链接改走静态的 Blaze3D.openUri(URI)）");
                }
                if (code.contains("Type.KEYSYM") || code.contains("Type.SCANCODE")) {
                    offenders.add(file + " -> InputConstants.Type.KEYSYM/SCANCODE"
                            + "（26.3 只剩 KEYBOARD / MOUSE，键码空间统一成 SDL scancode）");
                }
                if (code.contains("isKeyDown(window") || code.contains("isKeyDown(getWindow")) {
                    offenders.add(file + " -> isKeyDown(Window, int)（26.3 的去掉 Window 形参，只剩 isKeyDown(int)）");
                }
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n", offenders));

        // 键码表必须落在 SDL 的命名空间上：值对了才有意义，
        // 而 Lwjgl2KeycodeProbeTest 那 89 条与 InputConstants.KEY_* 的逐条对照已经钉住值。
        String keyboard = stripComments(read(ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "compat", "lwjgl2", "Keyboard.java"))));
        assertTrue(keyboard.contains("import org.lwjgl.sdl.SDLScancode;"),
                "Keyboard 的键码表必须引用 org.lwjgl.sdl.SDLScancode");
    }

    @Test
    @DisplayName("滚轮累加器有投料口：MouseHandler#onScroll 的 mixin 已写好并注册")
    void 滚轮钩子完整() throws IOException {
        Path scrollMixin = ProjectPaths.sourceFile(Paths.get("com", "muoniumplayer", "mixin", "MouseScrollMixin.java"));
        assertTrue(Files.exists(scrollMixin),
                "MouseScrollMixin 不见了；26.3 没有可套的 GLFW 滚轮回调，"
                        + "少了它 getDWheel() 永远是 0，表现为「滚轮在列表里划不动」");
        String body = read(scrollMixin);
        assertTrue(body.contains("@Mixin(MouseHandler.class)")
                        && body.contains("onScroll(JDD)V") && body.contains("@At(\"HEAD\")"),
                "必须挂 MouseHandler#onScroll(JDD)V 的入口：HEAD 注入且不 cancel，"
                        + "原版照样收到这次滚轮，等价于旧版「套一层回调再转发」");
        assertTrue(body.contains("Mouse.pushScrollNotches("),
                "onScroll 要把滚轮量投进 Mouse 的累加器，否则读侧拿不到");
        for (Path each : java.util.List.of(MIXIN_CONFIG, NEOFORGE_MIXIN_CONFIG)) {
            assertTrue(read(each).contains("\"MouseScrollMixin\""),
                    each + " 的 client 数组里没有 MouseScrollMixin，滚轮那一侧不会生效");
        }
    }

    @Test
    @DisplayName("鼠标按钮编号只在 compat 边界换算（SDL 1-5 → LWJGL2 0/2/1/3/4，含侧键）")
    void 按钮编号在边界换算() throws IOException {
        Path mouse = ProjectPaths.sourceFile(Paths.get("com", "muoniumplayer", "compat", "lwjgl2", "Mouse.java"));
        Path guiScreen = ProjectPaths.sourceFile(Paths.get("com", "muoniumplayer", "compat", "mc", "GuiScreen.java"));
        Path guiTextField = ProjectPaths.sourceFile(Paths.get("com", "muoniumplayer", "compat", "mc", "GuiTextField.java"));
        String m = stripComments(read(mouse));

        int from = m.indexOf("static int fromSdlButton(int sdlButton)");
        int to = m.indexOf("static int toSdlButton(int lwjgl2Button)");
        assertTrue(from > 0 && to > from,
                "Mouse 里必须有 fromSdlButton / toSdlButton 这一对换算。26.3 原版事件带的是 SDL 的"
                        + "按钮号（lwjgl-sdl 的 SDLMouse：LEFT=1 / MIDDLE=2 / RIGHT=3），"
                        + "而本项目整套 UI 说 LWJGL2 的编号（0=左、1=右、2=中）。"
                        + "不换算的实机症状是「点任何控件都没有反应」，而悬停照常——"
                        + "因为 GuiScreen.mouseClicked 里那句 if (mouseButton != 0) return; "
                        + "把左键（此时是 1）整条丢掉，而 hover 只看坐标。"
                        + "这个失败不会编译报错，也不会被任何渲染层测试抓到");

        String fromBody = m.substring(from, to);
        assertTrue(fromBody.contains("sdlButton == 1") && containsOrdered(fromBody, "sdlButton == 1", "return 0"),
                "fromSdlButton 必须把 SDL 的 1（左）换成 LWJGL2 的 0");
        assertTrue(containsOrdered(fromBody, "sdlButton == 3", "return 1"),
                "fromSdlButton 必须把 SDL 的 3（右）换成 LWJGL2 的 1，而不是照抄 SDL 文档里"
                        + "「2=右」的旧顺序——以 SDLMouse 常量与原版 onButton 的分支为准");
        assertTrue(containsOrdered(fromBody, "sdlButton == 2", "return 2"),
                "fromSdlButton 必须把 SDL 的 2（中）换成 LWJGL2 的 2");
        assertTrue(containsOrdered(fromBody, "sdlButton == 4", "return 3")
                        && containsOrdered(fromBody, "sdlButton == 5", "return 4"),
                "侧键必须换算：GLFW 的侧键号是 3/4，SDL 是 X1=4/X2=5。NCMScreen 的侧键导航"
                        + "认的是旧编号（3=后退、4=前进），把 SDL 的 4/5 原样透传的实机症状是"
                        + "「按后退键触发前进、按前进键完全无反应」——即 26.3 移植期的侧键失效");

        String toBody = m.substring(to);
        assertTrue(containsOrdered(toBody, "lwjgl2Button == 3", "return 4")
                        && containsOrdered(toBody, "lwjgl2Button == 4", "return 5"),
                "toSdlButton 是 fromSdlButton 的反向，侧键也要换回去（3→4、4→5）："
                        + "GuiTextField 替原版构造 MouseButtonInfo 时靠它把编号还原成 SDL 的，"
                        + "漏了侧键会让原版把本项目构造出的侧键事件读成别的键");

        String screen = stripComments(read(guiScreen));
        assertTrue(screen.contains("Mouse.fromSdlButton(click.button())"),
                "GuiScreen 的 mouseClicked / mouseReleased 必须换算后再交给 1.8.9 那套 int 签名");
        assertTrue(screen.indexOf("Mouse.fromSdlButton(click.button())")
                        != screen.lastIndexOf("Mouse.fromSdlButton(click.button())"),
                "按下与抬起两处都要换算：只换算一处会让「按下没反应、抬起被当成左键」这类半死状态");
        assertFalse(screen.contains("(int) click.y(), click.button())"),
                "不许把原版的 click.button() 原样传进 int 签名，那是本次故障的成因");

        assertTrue(stripComments(read(guiTextField)).contains("Mouse.toSdlButton(mouseButton)"),
                "GuiTextField 替原版构造 MouseButtonInfo 时要做反方向换算，"
                        + "否则本项目说的「左键 0」会被原版读成 0 = 不是任何键");
    }

    @Test
    @DisplayName("按住状态有投料口：MouseHandler#onButton 的 mixin 已写好并注册，isButtonDown 不查原版三布尔")
    void 按住状态钩子完整() throws IOException {
        Path stateMixin = ProjectPaths.sourceFile(Paths.get("com", "muoniumplayer", "mixin", "MouseButtonStateMixin.java"));
        assertTrue(Files.exists(stateMixin),
                "MouseButtonStateMixin 不见了。原版 isLeftPressed/isMiddlePressed/isRightPressed"
                        + "只在没有任何 Screen 打开时才更新（onButton 里 Gui.screen() != null 直接跳过），"
                        + "界面开着时轮询它们恒为进界面前的旧值。少了这颗 mixin，"
                        + "isButtonDown(0) 在本项目任何界面里都恒 false，实机症状是"
                        + "「音量/HUD 滑块拖不动、全屏歌词点了没反应、文本框拖选失灵」");
        String body = read(stateMixin);
        assertTrue(body.contains("@Mixin(MouseHandler.class)")
                        && body.contains("onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V")
                        && body.contains("@At(\"HEAD\")"),
                "必须挂 MouseHandler#onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V 的入口："
                        + "它是 SDL handleMouseButtonEvent 分发的统一步口，HEAD 注入且不 cancel，"
                        + "原版照常处理。描述符里的参数类型必须写全限定名");
        assertTrue(body.contains("Mouse.setButtonPressed(") && body.contains("Mouse.fromSdlButton("),
                "onButton 要把按钮号换算成 LWJGL2 编号后投进 Mouse 的位图，"
                        + "与 GuiScreen 桥接处共用同一张换算表");

        for (Path each : java.util.List.of(MIXIN_CONFIG, NEOFORGE_MIXIN_CONFIG)) {
            assertTrue(read(each).contains("\"MouseButtonStateMixin\""),
                    each + " 的 client 数组里没有 MouseButtonStateMixin，按住状态那一侧不会生效");
        }

        String mouse = stripComments(read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "compat", "lwjgl2", "Mouse.java"))));
        assertFalse(mouse.contains("isLeftPressed") || mouse.contains("isMiddlePressed")
                        || mouse.contains("isRightPressed"),
                "isButtonDown 不许再查原版的三布尔：它们在 Screen 打开期间不更新，"
                        + "这就是 26.3 移植期「滑块拖不动」的成因");
    }

    @Test
    @DisplayName("界面开着就开 SDL 文本输入：GuiScreen 已对接 TextInputManager")
    void 文本输入已对接() throws IOException {
        String screen = stripComments(read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "compat", "mc", "GuiScreen.java"))));
        assertTrue(screen.contains("startTextInput(this)"),
                "GuiScreen#added 必须调 textInputManager().startTextInput(this)："
                        + "26.3 的 SDL 后端里字符事件只在 SDL_StartTextInput 之后投递，"
                        + "原版靠 EditBox 聚焦开启，而本项目输入框全是自绘的。"
                        + "少这一句的实机症状是「搜索栏/输入框一个字都打不进」，"
                        + "keyPressed（ESC、空格）却照常——很容易误判成键盘焦点问题");
        assertTrue(screen.contains("stopTextInput(this)"),
                "GuiScreen#removed 必须调 textInputManager().stopTextInput(this) 收尾，"
                        + "否则关界面后 SDL 文本输入一直开着，原版聊天框外也会弹输入法组合窗");
    }

    /** 断言 needle2 出现在 needle1 之后（只看顺序，不假设具体写法）。 */
    private static boolean containsOrdered(String haystack, String needle1, String needle2) {
        int a = haystack.indexOf(needle1);
        int b = haystack.indexOf(needle2);
        return a >= 0 && b > a;
    }
}
