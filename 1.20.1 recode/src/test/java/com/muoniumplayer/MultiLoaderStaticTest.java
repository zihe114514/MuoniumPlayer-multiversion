package com.muoniumplayer;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.20.1 多加载器工程的结构不变量。
 *
 * <p>这一版的加载器是 MinecraftForge（1.20.1 是 Forge 的最后一个大版本，NeoForge 从
 * 1.20.2 才分家），其余三版是 NeoForge；断言里区分的正是这一点。
 *
 * <p>这些断言不测运行时行为，测的是「拆成 fabric/ + forge/ 之后，两侧还能同时编过」
 * 所依赖的结构约束。全部离线、毫秒级：破坏其中任何一条的后果，都要等到切到另一个
 * 加载器上构建时才会暴露。
 */
class MultiLoaderStaticTest {

    private static final Path ROOT = ProjectPaths.versionRoot();
    private static final Path SHARED_JAVA = ProjectPaths.sharedJava();
    private static final Path FABRIC_RESOURCES =
            ProjectPaths.fabric().resolve(Paths.get("src", "main", "resources"));

    @Test
    @DisplayName("版本声明一致：1.20.1 / fabric-api / forge 47 / Java 17")
    void 版本声明一致() throws IOException {
        String props = read(ROOT.resolve("gradle.properties"));
        assertTrue(props.contains("minecraft_version=1.20.1"),
                "gradle.properties 的 minecraft_version 不是 1.20.1");
        assertTrue(props.contains("+1.20.1"), "fabric_version 必须是 <ver>+1.20.1");
        assertTrue(props.contains("forge_version=1.20.1-47."),
                "forge_version 必须是 1.20.1 线的 47.x（1.20.1 用 Forge，不是 NeoForge）");
        assertFalse(props.contains("neoforge_version"),
                "1.20.1 刻意不用 NeoForge：它从 1.20.2 才分家，1.20.1 的模组生态在 Forge 上");

        for (String loader : List.of("fabric", "forge")) {
            assertTrue(read(ROOT.resolve(loader).resolve("build.gradle")).contains("def targetJavaVersion = 17"),
                    loader + " 侧的目标运行时必须是 Java 17");
        }
        assertTrue(read(FABRIC_RESOURCES.resolve("fabric.mod.json")).contains("\">=17\""),
                "fabric.mod.json 的 java 依赖必须是 >=17");
    }

    @Test
    @DisplayName("共享源码里没有任何加载器 API")
    void 共享源码与加载器无关() throws IOException {
        { Stream<Path> files = ProjectPaths.allJavaFiles().stream();
            List<Path> offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(MultiLoaderStaticTest::mentionsLoaderApi)
                    .toList();
            assertTrue(offenders.isEmpty(),
                    "共享源码引用了加载器 API，另一个加载器会编不过：" + offenders);
        }
    }

    @Test
    @DisplayName("两个加载器外壳都在，且 modId 一致")
    void 两个加载器外壳齐全() throws IOException {
        String fabricMeta = read(FABRIC_RESOURCES.resolve("fabric.mod.json"));
        assertTrue(fabricMeta.contains("\"id\": \"muonium\""), "fabric.mod.json 的 modId 必须是 muonium");
        assertTrue(fabricMeta.contains("com.muoniumplayer.MuoniumPlayerMod"),
                "fabric.mod.json 的 client entrypoint 必须指向 Fabric 外壳类");
        assertTrue(Files.exists(ProjectPaths.fabric().resolve(Paths.get(
                        "src", "main", "java", "com", "muoniumplayer", "MuoniumPlayerMod.java"))),
                "Fabric 入口类不存在");

        // 1.20.1 的文件名是 META-INF/mods.toml；neoforge.mods.toml 是 1.20.2+ 的写法。
        String forgeMeta = read(ProjectPaths.forge()
                .resolve(Paths.get("src", "main", "resources", "META-INF", "mods.toml")));
        assertTrue(forgeMeta.contains("modId = \"muonium\""), "mods.toml 的 modId 必须是 muonium");
        assertTrue(forgeMeta.contains("side = \"CLIENT\""), "Forge 侧必须声明客户端专用");
        assertTrue(Files.exists(ProjectPaths.forge().resolve(Paths.get(
                        "src", "main", "java", "com", "muoniumplayer", "loader", "forge",
                        "MuoniumPlayerForge.java"))),
                "Forge 入口类不存在");
    }

    @Test
    @DisplayName("映射统一在 Mojang 官方名，yarn 不再参与构建")
    void 映射统一mojmap() throws IOException {
        String fabricBuild = read(ProjectPaths.fabric().resolve("build.gradle"));
        assertTrue(fabricBuild.contains("loom.officialMojangMappings()"),
                "Fabric 侧必须显式用官方名映射，否则共享源码在 Forge 侧编不过");
        assertFalse(fabricBuild.contains("net.fabricmc:yarn"),
                "fabric/build.gradle 里不该再依赖 yarn");
        assertFalse(read(ROOT.resolve("gradle.properties")).contains("yarn_mappings="),
                "gradle.properties 里不该再有 yarn_mappings（已统一 mojmap）");
    }

    @Test
    @DisplayName("access widener 与 Forge AT 条目数同步 —— 一条 AW 必须对应一条 AT")
    void accessWidener与Forge_AT同步() throws IOException {
        // 3D 歌词光影包适配（WorldBillboard.shaderTextType）起，AW 有意携带条目
        // （RenderType$CompositeState 等 9 条），Forge 侧 accesstransformer.cfg 是
        // 它的等价镜像。守护的不变量从「两边都空」改为「两边同步」：
        // 条目数相等（AT 文件的注释行不计），且 AT 文件必须存在、逐类对得上。
        List<String> awEntries = new ArrayList<>();
        for (String line : read(FABRIC_RESOURCES.resolve("muoniumplayer.accesswidener")).split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("accessWidener")) {
                continue;
            }
            awEntries.add(trimmed);
        }
        assertFalse(awEntries.isEmpty(), "AW 至少要保留光影适配的条目；真要清空请连 AT 一起清并同步本测试");
        Path at = ProjectPaths.forge()
                .resolve(Paths.get("src", "main", "resources", "META-INF", "accesstransformer.cfg"));
        assertTrue(Files.exists(at), "AW 有条目时 Forge 侧必须有等价 accesstransformer.cfg");
        List<String> atEntries = new ArrayList<>();
        for (String line : read(at).split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            // AT 行尾允许 `f_110139_ # TRANSLUCENT_TRANSPARENCY` 这类 mojmap 注释
            atEntries.add(trimmed.split("\\s+#")[0].trim());
        }
        assertEquals(awEntries.size(), atEntries.size(),
                "AW 与 AT 条目数必须相等（AW=" + awEntries.size() + " AT=" + atEntries.size()
                        + "）：加/删 AW 条目时同步维护 AT，反之亦然");
        // 类条目两边都是 mojmap/官方名，直接点名比对；字段名两边语法不同（AW 用官方名，
        // AT 用 SRG 名），数量对齐 + 抽查关键字段即可。
        assertTrue(atEntries.stream().anyMatch(e -> e.contains("RenderType$CompositeState")),
                "AT 缺 RenderType$CompositeState 类条目");
        assertTrue(atEntries.stream().anyMatch(e -> e.contains("f_110139_")),
                "AT 缺 TRANSLUCENT_TRANSPARENCY 对应的 SRG 字段条目");
    }

    @Test
    @DisplayName("Forge 的一起听通道不走 SimpleChannel：那会给负载加一个判别字节")
    void 通道保持裸负载() throws IOException {
        String net = stripComments(read(ProjectPaths.forge().resolve(Paths.get(
                "src", "main", "java", "com", "muoniumplayer", "party", "net", "MpNetForge.java"))));
        // 两种等价注册形态都认：ChannelBuilder 链式的 eventNetworkChannel() 与
        // NetworkRegistry.newEventChannel(...) —— 最终都走同一个 createInstance，
        // 返回同一个 EventNetworkChannel（裸 FriendlyByteBuf，无判别字节）。
        assertTrue(net.contains("eventNetworkChannel()")
                        || net.contains("newEventChannel("),
                "必须用 EventNetworkChannel：它把 FriendlyByteBuf 原样交出来");
        assertFalse(net.contains("simpleChannel()") || net.contains("registerMessage("),
                "SimpleChannel 的 IndexedMessageCodec 会在负载前面写一个消息判别号，"
                        + "那一个字节就足以让 mpsync 服务端解不出帧");
        assertTrue(net.contains("new ServerboundCustomPayloadPacket("),
                "发送侧必须走原版 packet，否则同样会被 Forge 加壳");
    }

    @Test
    @DisplayName("第三方库压平了 Multi-Release jar：不能让 skija 落回 Java 8 实现")
    void 第三方库压平了多版本jar() throws IOException {
        // skija-shared / types / gson / kotlin-stdlib 都是 Multi-Release jar：根目录那份类是
        // Java 8 实现，META-INF/versions/9/ 下才是 Java 9+ 实现，靠清单里的 Multi-Release: true
        // 选择。shadow 合并时 exclude 掉了输入 jar 的 MANIFEST.MF，属性随之丢失，
        // 运行期只剩 Java 8 那份 —— skija 的 Cleanable 基线实现要 sun.misc.Cleaner
        // （JDK 9 起已删除），静态初始化必抛 NoClassDefFoundError，
        // SkijaHost 于是停用整个渲染层：不崩溃、无报错、游戏内一个像素都不显示。
        // Fabric 侧不受影响（Loom 的 include() 是整 jar 嵌入，清单还在），所以这条只管加载器侧。
        String build = read(ProjectPaths.forge().resolve("build.gradle"));
        assertTrue(build.contains("tasks.register('flattenEmbeddedLibs'"),
                "必须有 flattenEmbeddedLibs 把 META-INF/versions/N 里的类提到根目录");
        assertFalse(build.contains("files(shadeEmbeddedLibs.map"),
                "编译期与开发期运行时的类路径都要用压平后的 jar，不能直接用 shadow 的产物");
        assertTrue(build.contains("files(flattenEmbeddedLibs.map"),
                "编译期与开发期运行时的类路径必须指向压平后的 jar");
        // 这里只能查「有没有用压平后的」，不能查「有没有出现 shadeEmbeddedLibs」——
        // flattenEmbeddedLibs 任务自己就要读 shadow 的产物。
        assertTrue(build.contains("zipTree(flattenEmbeddedLibs.get()"),
                "打进 mod jar 的必须是压平后的 jar");
    }

    @Test
    @DisplayName("pack.mcmeta 按当前版本的规则声明格式号")
    void 资源包元数据符合当前版本规则() throws IOException {
        // 1.21.9 起原版不再接受「只写一个 pack_format」，缺格式区间声明会被判定成
        // 「元数据读不出来」（1.21.11 报 ERROR，26.2 报 WARN 后走兜底类型），
        // mod 自己的 assets 只能靠兜底路径加载。
        //
        // 规则是从 PackFormat$IntermediaryFormat.validateNewFormat 的字节码 + 实测报错原文
        // 反推出来的：mod 的这一份 pack.mcmeta 会被按资源包和数据包各读一次，
        // 两次的「旧格式窗口上界」不同（资源 64、数据 81）。落在 (64, 81] 里的格式号
        // 会同时撞上「按资源包读不许有 supported_formats」和「按数据包读必须有
        // supported_formats」两条互斥要求，根本写不出能同时过的文件。
        // 所以两版的取值都在 81 以上：26.2 用资源格式 88，
        // 1.21.11 用数据格式 94（这也是 NeoForge 自己 jar 里那份 pack.mcmeta 的写法）。
        String meta = read(ProjectPaths.forge().resolve(
                Paths.get("src", "main", "resources", "pack.mcmeta")));
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\"pack_format\"\\s*:\\s*(\\d+)").matcher(meta);
        // 不写 pack_format 也是合法写法（1.21.11 就是），此时一定走新格式那条路。
        int packFormat = matcher.find() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
        if (packFormat > 64) {
            assertTrue(meta.contains("\"min_format\"") && meta.contains("\"max_format\""),
                    "格式号高于 64 时 min_format / max_format 是必填的");
            assertFalse(meta.contains("\"supported_formats\""),
                    "格式号 65 起 supported_formats 已被弃用，写了会让原版直接拒读这份元数据");
        }
    }

    @Test
    @DisplayName("不重复打包 Minecraft 自带的库：否则开发期 JPMS 解析就崩")
    void 不重复打包原版自带的库() throws IOException {
        // 1.20.1（BootstrapLauncher 1.1.2）与 1.21.1（2.0.2）的开发期运行会把
        // legacyClassPath 里每个 jar 变成 JPMS 自动模块，两个模块导出同一个包时 JVM 在
        // 解析阶段直接抛 ResolutionException，游戏一行日志都打不出来：
        //   模块 com.google.gson 与 MuoniumPlayer.<loader>.embedded.libs
        //   export package com.google.gson to module org.lz4.java
        // 用「shadow 产物的包名集合 ∩ clientLegacyClasspath.txt 里各 jar 的包名集合」实测过，
        // 重合的只有这四个，全部由 Minecraft 提供。1.21.11 / 26.2 的开发期不走模块路径，
        // 原本不暴露，但发布 jar 里同样不该塞重复库，四个版本统一排除。
        String build = read(ProjectPaths.forge().resolve("build.gradle"));
        for (String provided : List.of(
                "exclude group: 'com.google.code.gson', module: 'gson'",
                "exclude group: 'net.java.dev.jna', module: 'jna'",
                "exclude group: 'net.java.dev.jna', module: 'jna-platform'",
                "exclude group: 'com.google.errorprone', module: 'error_prone_annotations'")) {
            assertTrue(build.contains(provided), "embeddedLibs 缺少排除：" + provided);
        }
        assertFalse(build.contains("embeddedLibs 'com.google.code.gson:gson"),
                "gson 由 Minecraft 提供，不能再自带一份");
        assertFalse(build.contains("embeddedLibs 'net.java.dev.jna:"),
                "jna / jna-platform 由 Minecraft 提供，不能再自带一份");
    }

    @Test
    @DisplayName("产物名带 Minecraft 版本，且 buildAll 会汇总到 dist/")
    void 产物名带版本且汇总到统一目录() throws IOException {
        // 四个版本工程是四个独立的 Gradle 构建，8 个 jar 平铺进同一个 dist/ 才不撞名的前提，
        // 就是 archivesName 里带了 Minecraft 版本：MuoniumPlayer-<mc>-<loader>-<modver>.jar。
        // 少了这一段，1.21.1 与 1.21.11 的 fabric 产物会同名互相覆盖。
        for (String loader : List.of("fabric", "forge")) {
            String build = read(ROOT.resolve(loader).resolve("build.gradle"));
            assertTrue(build.contains(
                            "archivesName = \"${project.archives_base_name}-${project.minecraft_version}-" + loader + "\""),
                    loader + " 侧的 archivesName 必须是 <base>-<minecraft_version>-<loader>");
        }

        // collectDist 只挑「能直接丢进 mods/ 的成品」：通配全收会把源码包和改名前的旧 jar 一起发出去。
        String aggregate = read(ROOT.resolve("build.gradle"));
        assertTrue(aggregate.contains("tasks.register('collectDist', Copy)"),
                "聚合工程里必须有 collectDist 任务，负责把产物汇总到仓库根的 dist/");
        assertTrue(aggregate.contains("rootProject.file('../dist')"),
                "dist/ 必须落在仓库根，四个版本共用一个目录");
        assertTrue(aggregate.contains("'collectDist'"), "buildAll 必须带上 collectDist");
        // 聚合工程的注释里刻意写着「刻意不写 include '*.jar'」这句移植说明，扫描前必须先去注释，
        // 否则这条断言会被那句说明本身钉红。
        assertFalse(stripComments(aggregate).contains("include '*.jar'"),
                "collectDist 不能用 include '*.jar' 全收，会把源码包与旧名产物一起发出去");
    }

    @Test
    @DisplayName("剪贴板走原版通道，不许用 AWT")
    void 剪贴板不走AWT() throws IOException {
        // Minecraft 在 net.minecraft.client.main.Main 的静态初始化里写死了
        // System.setProperty("java.awt.headless", "true")（四个版本的 Main.class 都能反编译出来）。
        // headless 下 Toolkit#getSystemClipboard() 必抛 HeadlessException，
        // 复制 / 粘贴会静默退化成空操作 —— 「一起听」里复制链接、复制邀请码两个按钮
        // 就是这么坏的：按钮照样亮「已复制」，剪贴板里什么都没有。
        // 正确通道是原版自己的 Minecraft#keyboardHandler（内部是 GLFW 剪贴板函数）。
        Path keyboardUtils = ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "utils", "KeyboardUtils.java"));
        String body = stripComments(read(keyboardUtils));
        assertTrue(body.contains("keyboardHandler.setClipboard("),
                "写剪贴板必须走原版的 KeyboardHandler#setClipboard");
        assertTrue(body.contains("keyboardHandler.getClipboard()"),
                "读剪贴板必须走原版的 KeyboardHandler#getClipboard");
        assertTrue(body.contains("isSameThread()"),
                "GLFW 剪贴板函数只能在渲染线程调用，必须先判线程");

        { Stream<Path> files = ProjectPaths.allJavaFiles().stream();
            List<Path> offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(MultiLoaderStaticTest::usesAwtClipboard)
                    .toList();
            assertTrue(offenders.isEmpty(),
                    "共享源码里还有地方在用 AWT 剪贴板，headless 下必然是空操作：" + offenders);
        }
    }

    private static boolean usesAwtClipboard(Path file) {
        try {
            String body = stripComments(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            return body.contains("getSystemClipboard") || body.contains("StringSelection");
        } catch (IOException e) {
            throw new AssertionError("读不了 " + file, e);
        }
    }

    private static boolean mentionsLoaderApi(Path file) {
        try {
            String body = stripComments(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            return body.contains("net.fabricmc") || body.contains("net.minecraftforge");
        } catch (IOException e) {
            throw new AssertionError("读不了 " + file, e);
        }
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** 注释里刻意保留了旧写法作为移植说明，扫描前先去掉。 */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }
}
