# MuoniumPlayer —— 多版本单仓（private）

Minecraft 客户端 mod **MuoniumPlayer**（网易云音乐播放器 + 灵动岛 UI + 一起听房间）
的五个版本移植工程，统一放在这一个私有仓库里管理。

每个版本都是**一个多加载器工程**：共享源码在 `<版本>/src/`，加载器外壳在
`<版本>/fabric/` 与 `<版本>/neoforge/`（1.20.1 是 `forge/`）。结构参照
`F:/donked/modernMP-master`。

## 版本矩阵

| 目录 | Minecraft | 加载器 | 加载器版本 | 加载器插件 | fabric-api | Loader 下限 | Java | Gradle JDK |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `1.20.1 recode` | 1.20.1 | Fabric + **Forge** | 1.20.1-47.4.0 | MDG legacyforge 2.0.144 | 0.92.11+1.20.1 | 0.18.2 | 17 | JDK 21 |
| `1.21.1` | 1.21.1 | Fabric + NeoForge | 21.1.249 | MDG 2.0.144 | 0.116.15+1.21.1 | 0.15.11 | 21 | JDK 21 |
| `1.21.11 recode` | 1.21.11 | Fabric + NeoForge | 21.11.45 | MDG 2.0.144 | 0.141.6+1.21.11 | 0.17.3 | 21 | JDK 21 |
| `26.2` | 26.2 | Fabric + NeoForge | 26.2.0.72 | MDG 2.0.144 | 0.158.0+26.2 | 0.18.4 | 25 | JDK 25 |
| `26.3` | 26.3 | Fabric + NeoForge | 26.3.0.16-beta（26.3 线全是 -beta） | **MDG 2.0.147** | 0.161.0+26.3 | 0.19.3 | 25 | JDK 25 |

**只有 26.3 的 NeoForge 壳用 MDG 2.0.147，这是刻意不一致，别改回去。**
NeoForge 从 26.2.0.87 起改了 `HolderSet#contents()` 的访问修饰符生成方式，
2.0.144 在 `createMinecraftArtifacts` 的 recompile 节点上直接编不过**原版**
`net/minecraft/core/HolderSet.java`（报「weaker access privileges」），
看起来像本工程坏了，其实共享源码一行都没开始编；上游
issue `neoforged/NeoForge#3518` 的答复就是升级插件（NeoGradle 对应 7.1.39）。
其余四棵树的 NeoForge 版本都早于 26.2.0.87，继续用统一的 2.0.144。

### 运行期 Kotlin / 加载器下限

- Kotlin **不再随 mod jar 打包**，改由前置统一提供：
  - `1.20.1 Forge`：`Kotlin for Forge 4.3.0+`
  - `1.21.1 NeoForge`：`Kotlin for Forge 5.4.0+`
  - `1.21.11 NeoForge`：`Kotlin for Forge 6.0.0+`
  - `26.2 NeoForge`：`Kotlin for Forge 6.3.0+`
  - `26.3 NeoForge`：`Kotlin for Forge 6.3.0+`
  - Fabric 侧：`Fabric Language Kotlin 1.13.12+kotlin.2.4.0+`
- 加载器最低支持版本不再跟随构建时的最新版：
  - `Forge 47.0.0+`
  - `NeoForge 21.1.0+ / 21.11.0+ / 26.2.0+ / 26.3.0.0-beta+`

**1.20.1 用 Forge 而不是 NeoForge**：1.20.1 是 Forge 的最后一个大版本，NeoForge 从
1.20.2 才分家，1.20.1 的模组生态与整合包都在 Forge 上。

五份工程共用：`mod_version=1.7.0`、`maven_group=com.muoniumplayer`、
`archives_base_name=MuoniumPlayer`、Skija `0.143.17`（Fabric 侧 `include` 进 jar，
Forge/NeoForge 侧用 shadow 合并；原生库平台由 `skija_natives` 控制，主包只内置 windows-x64）。

**Skija 原生库伴生包（非 Windows 平台）**：Linux / macOS / Android 用户把
`dist/skija-companion/muonium-skija-<platform>-<skija版本>.zip`（各版本根目录
`gradlew packageSkijaCompanion` 产出，带许可文本；android-arm64 是真机实测过的那档）
解压到 `config/muonium/native/skija/` 即可，渲染层约 20 秒内自动重试，不用重启。
**音频输出同样自带兜底**：安卓 JRE 的 `javax.sound.sampled` 没有任何 Mixer（打包的 JSyn
只有 JavaSound 一个出厂设备，PortAudio 回退在打包时被裁掉），所以 `AudioPlayer` 在
JavaSound 拿不到输出设备时自动把 JSyn 的输出换成自带的 OpenAL 流式设备
（`OpenALAudioDevice`，自开独立 ALC 上下文，不碰游戏自己的声音引擎）——
FCL 这类不带音频桥的启动器上也能放歌；启动器提供了 MioJSound（Zalith v2 自带）时
JavaSound 有 Mixer，照走原路不抢设备。
Android 的 linker namespace 不允许从外置存储 dlopen，模组会自动把库搬到 libjvm 同树再加载
（`SkijaNativeCatalog.stageRoots`，搬运幂等）。**安卓渲染器必须选 MobileGlues（MG）并关闭
ANGLE**——2026-10-05 真机复测，这个组合下 Skia 的 GL 互操作完整可用；此前「MobileGlues
装不出上下文」的结论是 ANGLE 报的桌面语义版本串造成的误判。换 Krypton 包装仍可能弄坏世界
贴图，这条风险写在停用提示里。停用时的原版 GUI 提示是 `RenderLayerNotice`（走原版 GUI 是因为
它报告的正是 Skia 起不来这件事）。非 Windows 用户报障时先看启动日志里
`[MuoniumPlayer/runtime]` 那两行运行时身份。

**Automix（Folia）推理在安卓上改走 ONNX Runtime**：Python 运行组件在手机上没有可用形态，
所以只有 Android 换引擎——桌面继续走久经考验的 Python 子进程（`preferOnnxInference()`
恒 false），两端的模型与数学契约不变。安卓侧 `FoliaOnnx` 用反射装载运行包里的
`classes.jar`（onnxruntime-android AAR 抽包），两个 native 库先暂存到 libjvm 同树或应用
私有缓存再设给 `onnxruntime.native.path`（同受安卓 linker namespace 限制）；
Beat This! 的 mel 前端与 HTDemucs 分段分离整段搬进 Java（`FoliaMelFrontend` /
`FoliaBeatOnnx` / `FoliaStemOnnx`），输出与 Python 契约逐字段对齐，mel 前端与 numpy
参考实现对拍逐位一致。运行时包由 `tools/build-folia-onnx-runtime.ps1` 产出：
`dist/folia-onnx/folia-onnx-android-arm64.zip`（≈12 MB，ORT 1.29.0），大小与 SHA-256 钉在
`FoliaModelManager.ANDROID_ZIP_*`——运行时 zip 托管在**更新清单仓库**
（`MuoniumPlayer-updates`，格式与模型不同所以没放 folia-models），客户端经
`MIRRORS` 后三路（ghfast 代理 / raw / jsdelivr，与 `UpdateChecker` 同款三源）下载；
两个 `.onnx` 模型仍走前几路 folia-models。重出运行包时两处一起更新：传 zip 到
对应仓库，改 `ANDROID_ZIP_*` 常量。
ORT 版本下限是 1.21（1.20 的引擎加载不了 HTDemucs 导出：`/dft_istft` 的 DFT 算子在旧版
形状推断里报 `is_onesided and inverse`），这里与 Python 运行组件里的 onnxruntime 对齐到
1.29.0。桌面不装 ONNX 运行时的原因除了「Python 够用」，还有一条硬伤：ORT 1.21+ 的
Windows 原生库用新 MSVC 工具链编译，部分 JVM 自带旧 VC++ 运行库时会 DllMain 失败
（上游 onnxruntime#24287），不该把这类只影响桌面的故障引进来。

产物命名：`MuoniumPlayer-<mc版本>-<加载器>-<mod版本>.jar`，
例如 `MuoniumPlayer-1.21.11-neoforge-1.7.0.jar` / `MuoniumPlayer-1.20.1-forge-1.7.0.jar`。
名字里带 Minecraft 版本是必要的，不是好看：五个版本工程是五个独立构建，
10 个 jar 要平铺进同一个 `dist/`，不带版本号的话 1.21.1 与 1.21.11 的 fabric 产物会同名互相覆盖。
`buildAll` 跑完会把成品汇总到仓库根的 `dist/`（`collectDist` 任务，只收成品、不收源码包与改名前的旧 jar）。
（1.20.1 是 `-forge`），各约 55～57 MB。

## 映射：五个版本统一 Mojang 官方名（mojmap）

共享源码只能有一套类名，而 Forge/NeoForge 侧**只有** mojmap 一种命名，所以五个版本
的共享源码都写官方名：

* Fabric 侧显式 `mappings loom.officialMojangMappings()`（26.2 / 26.3 除外，见下），
  `gradle.properties` 里已无 `yarn_mappings`；
* 26.2 起的官方 jar 本来就不混淆（version json 里没有 `client_mappings`，
  Yarn 与 intermediary 都停在 1.21.11），因此 26.2 / 26.3 两版连 `mappings` 声明都没有；
* 不用 architectury-loom（它能把 yarn 搬到 Forge）的原因有两条：它最高只发到
  `1.11.454`，跟不上本仓统一的 Gradle 9.6.1；`yarn-mappings-patch-neoforge`
  最高只到 `1.21+build.6`，覆盖不到 1.21.11 与 26.x。

**铁律：API 名一律用 `javap` 对着实际 jar 核实，禁止凭记忆写。**
各版本 `build.gradle` 里的 `dumpCompileClasspath` 任务就是为此准备的。

## 加载器耦合面

共享源码里不允许出现 `net.fabricmc` / `net.neoforged` / `net.minecraftforge`，
这条由每个版本的 `MultiLoaderStaticTest` 扫描断言。耦合面只有两处：

| 关注点 | 共享侧 | Fabric 侧 | Forge / NeoForge 侧 |
| --- | --- | --- | --- |
| 入口 + 事件 | `MuoniumPlayerClient` | `MuoniumPlayerMod`（`ClientModInitializer`） | `loader/{forge,neoforge}/MuoniumPlayer{Forge,NeoForge}`（`@Mod`） |
| 一起听通道 | `party/net/MpPayloads`（1.20.1 无，走裸字节） | `party/net/MpNetFabric` | `party/net/MpNet{Forge,NeoForge}` |

`MuoniumPlayerClient` 的调用契约（两个加载器都必须照做，顺序不能换）：
`createKeyMappings()` → `init()` → 加载器注册一起听通道 → 每 tick `onClientTick(Minecraft)`、
HUD 画完 `onHudRender()`、Screen 画完 `onScreenRendered(Screen)`。

## 各版本实测差异（都已 javap 核实）

| 主题 | 1.20.1 | 1.21.1 | 1.21.11 | 26.2 |
| --- | --- | --- | --- | --- |
| 按键分类 | 字符串翻译键 | 字符串翻译键 | `KeyMapping.Category.register(Identifier)` | 同 1.21.11 |
| 键盘常量 | `InputConstants.isKeyDown` | 同 | 同 | 同 |
| 变换栈 | `PoseStack.pushPose/popPose` | `getModelViewStack()` 返回 JOML `Matrix4fStack`，用 `pushMatrix/popMatrix` | 同 1.21.1 | 同 1.21.1 |
| 按钮渲染 | `renderWidget` 可覆写 | 同 | `renderWidget` 为 **final**，改实现 `renderContents` + `renderDefaultSprite`/`renderDefaultLabel` | 同 1.21.11 |
| 输入事件 | 散装形参 | 散装形参 | 打包成 `KeyEvent` / `CharacterEvent` / `MouseButtonEvent(…, MouseButtonInfo)` | 同 1.21.11 |
| 窗口句柄 | `Window#getWindow()` | 同 | `Window#handle()` | 同 1.21.11 |
| 当前界面 | `Minecraft#screen` | 同 | 同 | `Minecraft#gui.screen()` / `gui.setScreen()` |
| 聊天输出 | `Player#displayClientMessage` | 同 | 同 | `Player#sendSystemMessage`（前者已移除） |
| HUD 挂点（Fabric） | `HudRenderCallback` | 同 | 同 | `HudElementRegistry.addLast`（旧回调已删除） |
| Screen 挂点（Fabric） | `ScreenEvents.afterRender` | 同 | 同 | `ScreenEvents.afterExtract` |
| 客户端发包 | 原版 `ServerboundCustomPayloadPacket` | `PacketDistributor.sendToServer` | `ClientPacketDistributor.sendToServer` | 同 1.21.11 |
| 帧末合成 mixin | 无（GUI 是即时绘制） | 无 | `FramebufferMixin`（`RenderTarget#blitToScreen` 出口） | `GlSurfaceMixin`（`GlSurface#present` 入口） |
| 资源包 pack.mcmeta | 只写 `pack_format` 15 | 只写 `pack_format` 34 | 不写 `pack_format`，`min_format`=`max_format`=`[94,1]`（数据格式） | `pack_format` 88 + `min_format`/`max_format` |
| access widener | 空 | 空 | 空 | 无此文件 |
| 开发期运行 | BootstrapLauncher，走 JPMS 模块路径 | 同 1.20.1 | 纯 classpath，无模块路径 | 同 1.21.11 |

### 26.3 相对 26.2 的实测差异（同样都经 `javap` 核实）

上表 26.3 那一列没写的维度与 26.2 一致；只有下面这几条真的变了：

| 主题 | 26.2 | 26.3 |
| --- | --- | --- |
| GPU 后端层 | `com.mojang.blaze3d.opengl.*` / `blaze3d.systems.GpuDevice`（具体类） | 整层搬进 **`com.mojang.renderpearl.*`**：后端实现在 `renderpearl.backend.opengl.*`，后端无关门面在 `renderpearl.api.device.*`，`GpuDevice` 变成**接口**。`RenderSystem` 仍在 `com.mojang.blaze3d.systems` |
| GL 状态缓存 | `blaze3d.opengl.GlStateManager` | `renderpearl.backend.opengl.GlStateManager`，方法签名逐条一致（`_bindTexture/_genTexture/_deleteTexture/_texParameter/_pixelStore/_activeTexture/_viewport`） |
| 窗口/输入后端 | GLFW（`lwjgl-glfw` 在库里） | **换成 SDL**（`lwjgl-sdl`，库里没有 glfw 了）：`Window#handle()` 现在是 SDL 窗口指针，`GlSurface#present()` 内部是 `SDL_GL_SwapWindow`，事件走 `Window#handleEvent(SDL_Event)` 与 `blaze3d.platform.SDLEventHandler` |
| 键码空间 | `InputConstants.KEY_*` == GLFW 键码（`KEY_A=65`） | `InputConstants.KEY_*` == SDL 码（`KEY_A=4`、`KEY_ESCAPE=41`），且名字也跟着 SDL 走（`KEY_RETURN` 取代 `KEY_ENTER`，`KEY_LGUI/KEY_RGUI` 取代 `KEY_LSUPER/KEY_RSUPER`）。`lwjgl-glfw` 不在编译类路径上，`compat/lwjgl2` 的键码表与鼠标查询都要换后端 |
| 滚轮输入 | `glfwSetScrollCallback` 返回旧回调，在外面再套一层 | 没有可套的回调，改挂 `MouseHandler#onScroll(long, double, double)` 的入口（`MouseScrollMixin`），照样不吞 MC 自己的滚轮 |
| 帧末合成 | `GlSurfaceMixin` 挂 `com.mojang.blaze3d.opengl.GlSurface#present()` 入口 | 同一个钩子，目标类改名为 `com.mojang.renderpearl.backend.opengl.GlSurface`；链路不变（`GpuSurface#blitFromTexture` → `GlSurface#blitFromTexture` → `GlCommandEncoder#presentTexture` → `present()`） |
| Vulkan 后端 | `blaze3d.vulkan.*` | `renderpearl.backend.vulkan.*`；`PreferredGraphicsApi` 仍在 `net.minecraft.client`，`DeviceInfo#backendName()` 仍写死 `OpenGL` / `Vulkan`（探测逻辑不用改） |

六条容易踩的坑，已写成测试钉住：

* **1.20.1 的 Forge 通道必须用 `EventNetworkChannel`，不能用 `SimpleChannel`。**
  后者的 `IndexedMessageCodec` 会在负载前面写一个消息判别号，那一个字节就让
  mpsync 服务端解不出帧，而线协议要求与 Fabric / Paper 侧逐字节一致。
* **NeoForge 的按键分组要单独 `event.registerCategory(...)`。**
  `KeyMapping.Category.register(Identifier)` 只做「构造 + 查重」，不写原版的
  `SORT_ORDER`；NeoForge 的控制界面读的是 `RegisterKeyMappingsEvent` 自己的
  `moddedCategories`，漏了这一步键能绑定但分组标题不出现。
* **加载器侧 shadow 出来的库 jar 必须再压平 Multi-Release 目录。**
  `skija-shared` / `types` / `gson` / `kotlin-stdlib` 都是 Multi-Release jar：
  根目录那份类是 Java 8 实现，`META-INF/versions/9/` 下才是 Java 9+ 实现，
  由清单里的 `Multi-Release: true` 决定用哪份。shadow 合并时 `exclude` 掉了输入 jar 的
  `MANIFEST.MF`，这个属性随之消失，运行期只剩 Java 8 那份 —— skija 的
  `io.github.humbleui.skija.impl.Cleanable` 基线实现要 `sun.misc.Cleaner`（JDK 9 起已删除），
  静态初始化必抛 `NoClassDefFoundError`，于是任何 Skia 原生对象都造不出来，
  `SkijaHost` 直接停用整个渲染层。**症状是「不崩溃、日志无异常、游戏内一个像素都不显示」**，
  极难从日志反推。因此 `flattenEmbeddedLibs` 把 `versions/N` 里的类提到根目录覆盖基线实现，
  编译期类路径、开发期运行时类路径、以及打进 mod jar 的都用压平后的 jar。
  Fabric 侧不受影响：Loom 的 `include()` 是把原 jar 整个嵌进去（JiJ），清单还在。
* **1.21.9 起 `pack.mcmeta` 不再接受「只写一个 `pack_format`」，而且格式号不能落在 (64, 81] 里。**
  缺格式区间声明时原版判定「元数据读不出来」（1.21.11 报 ERROR，26.2 报 WARN 后走兜底类型），
  mod 自己的 assets 只能靠兜底路径加载。1.20.1（15）与 1.21.1（34）在规则生效之前，不受影响。
  规则是照着 `PackFormat$IntermediaryFormat.validateNewFormat` 的字节码 + 实测报错原文反推的：
  mod 的这一份 `pack.mcmeta` 会被**按资源包和数据包各读一次**，两次的「旧格式窗口上界」不同
  （资源 64、数据 81），于是
  1. 格式号 > 64 → `min_format` / `max_format` 必填，且 `supported_formats` 被禁用；
  2. 格式号 ≤ 81 → 按数据包读时又**必须**有 `supported_formats`。
  落在 (64, 81] 的号（1.21.11 的资源格式 75 正是）两条互斥，写不出能同时过的文件。
  解法就是取一个 81 以上的号：26.2 用资源格式 88，1.21.11 用**数据**格式
  `min_format`=`max_format`=`[94, 1]`——这也正是 NeoForge 自己 jar 里那份 `pack.mcmeta` 的写法。
* **剪贴板不能用 AWT。**
  Minecraft 在 `net.minecraft.client.main.Main` 的静态初始化里写死了
  `System.setProperty("java.awt.headless", "true")`（五个版本的 `Main.class` 都能反编译出这三条字节码，
  26.3 复核过）。
  headless 下 `Toolkit#getSystemClipboard()` 必抛 `HeadlessException`，
  原来的 `KeyboardUtils` 又是 `catch (Exception) {}` 全吞，于是复制/粘贴静默变成空操作 ——
  「一起听」的复制链接、复制邀请码按钮照样亮「已复制」，剪贴板里什么都没有。
  正解是走原版自己的 `Minecraft#keyboardHandler`（`getClipboard()` / `setClipboard(String)`，
  内部在 26.2 及以早是 GLFW 剪贴板函数，26.3 起换成了 SDL——原版把剪贴板收进了
  `com.mojang.blaze3d.platform.ClipboardManager`，`KeyboardHandler` 自己已经不再直接引用 GLFW）；
  这两个方法名与字段名在五个版本上完全一致，共享源码只需要一份实现。
  后端剪贴板只能在渲染线程调用，所以写入时先判 `Minecraft#isSameThread()`，
  非渲染线程排到 `Minecraft#execute`。
* **加载器侧绝不能重复打包 Minecraft 自己就带的库。**
  1.20.1（BootstrapLauncher 1.1.2）与 1.21.1（2.0.2）的开发期运行会把 `legacyClassPath`
  里每个 jar 变成 JPMS 自动模块，两个模块导出同一个包时 JVM 在解析阶段就崩、游戏一行日志都打不出来：
  `ResolutionException: Modules com.google.gson and MuoniumPlayer.<loader>.embedded.libs export
  package com.google.gson to module org.lz4.java`。
  用「shadow 产物的包名集合 ∩ `clientLegacyClasspath.txt` 里各 jar 的包名集合」实测，
  重合的只有 `gson` / `jna` / `jna-platform` / `error_prone_annotations` 四个，
  全部由 Minecraft 提供，因此从 `embeddedLibs` 里 `exclude` 掉（编译期直接用原版那几个版本）。
  1.21.11 / 26.2 / 26.3 开发期不走模块路径，原本不暴露这个问题，但发布 jar 里同样不该塞重复库，五个版本统一排除。

## 全屏歌词背景 / 主题配色

打开全屏歌词页后，左上角 **A-Z** 快捷面板里有两条独立选项：

- **歌词背景**：`模糊封面`（原有默认） / `隐现`（封面取色的 MeshGradient + Dithering，随后播放包络实时响应）。
- **主题配色**：`当前主题`（原有默认，保留所有预设与自定义颜色） / `封面跟随`（按当前歌曲封面自动生成同色系明暗主题）。

两项都只影响新选择，旧配置没有对应字段时继续使用原有模糊封面和当前主题；`封面跟随`
不会覆盖预设主题的调色数据，随时切回 `当前主题` 即可恢复。

## 更新提示（四版共用一份实现）

启动后在主菜单弹一次提示：有新版本时提示更新，连续三次连不上清单服务器时提示检查网络。
实现与 ffmpeg 提示同构 —— 「守护线程探一次 + 每 tick 轮询该不该弹 + Skija 画界面」。

| 位置 | 职责 |
| --- | --- |
| `core/update/SemVer.java` | 版本号按段比数字（`String#compareTo` 会认为 1.10.0 比 1.9.0 旧） |
| `core/update/UpdateManifest.java` | 解析远端 `update.json`，查表顺序 `<mc>-<loader>` → `<mc>` → `fallback` |
| `core/update/UpdateChecker.java` | 守护线程拉清单、缓存 30 分钟、下载页域名白名单、额度与失败计数落盘 |
| `core/screens/ncm/UpdateNoticeOverlay.java` | 提示界面本体（Skija 画）；宿主薄壳见 `core/screens/GuiUpdateNotice.java` |
| `ConfigPaths.UPDATE_NOTICE` | `config/muonium/update_notice.json`：`skipCount` / `skipCountVersion` / `failureCount` / 清单缓存 |

清单不在本仓库里 —— 本仓库是私有的，而客户端必须能匿名读到清单。清单在公开仓库
[zihe114514/MuoniumPlayer-updates](https://github.com/zihe114514/MuoniumPlayer-updates)，
本地留了一份副本在 `tools/update-manifest/`（只是副本，改了要自己推到那个仓库）。

发一个新版本要做的事：把 jar 发到 Releases，然后改清单里对应 channel 的 `latest` / `page` / `notes`。
mod 侧不需要动。

几条刻意的设计：

- **加载器 id 由入口外壳登记**（`MuoniumPlayerClient.setLoaderId`，在 `init()` 之前）。共享源码不许
  引用加载器 API，没法自己判断跑在哪个加载器上，而清单是按「MC 版本 + 加载器」分桶的。
- **MC 版本写死成 `MuoniumPlayerClient.MC_VERSION`**，静态测试钉住它与 `gradle.properties` 的
  `minecraft_version` 一致（原版取版本号的入口五个版本各不相同）。
- **下载页有域名白名单**（github / githubusercontent / jsdelivr / modrinth / gitee）。清单是公开仓库里的
  纯文本，万一被改，「点一下跳浏览器」就成了把用户导去任意站点。客户端永远不会自动下载或替换 jar。
- **三个清单源，全部同时拉，取版本号最高的那份**（ghfast + raw + gcore.jsdelivr，实测都是 200）。
  单一源在国内网络下不可靠；而各源缓存策略不同，同一时刻可能一个给新清单、一个给几小时前的旧清单，
  所以「谁先答」不代表「谁更新」—— 全部试一遍取最高才不会漏掉新版本。
  源 1（raw 的加速入口）CDN 只缓存 5 分钟，jsDelivr 对 `@main` 是 12 小时。
  2026-09-16 去掉了 `cdn.jsdmirror.com` 与 `cdn.jsdelivr.net` 两个源。
- **界面是纯 Skija**（`UpdateNoticeOverlay extends NCMPanel`）：宿主 `GuiUpdateNotice` 只是薄壳，
  负责开帧、灌尺寸、转发输入、收起。四版共用同一份界面源码，不再各写一份原生控件版。

## 目录职责

```
C:\MUSIC
├─ 1.20.1 recode/      1.20.1（Fabric + Forge），渲染层 Skija 重写的主战场
├─ 1.21.1/             1.21.1（Fabric + NeoForge）
├─ 1.21.11 recode/     1.21.11（Fabric + NeoForge）
├─ 26.2/               26.2（Fabric + NeoForge，官方名 + Java 25）
├─ 26.3/               26.3（Fabric + NeoForge，日常开发用的那一棵）
├─ muonium-common/     五棵树共用的主体源码与资源（版本中立，见该目录 README）
├─ mpparty-common/     五个工程共用的「一起听」客户端源码，禁止出现 Minecraft 类型
├─ tools/              跨版本管理脚本（build-all / share-trees / diff-versions）
├─ .gitignore          全仓统一忽略规则
└─ .gitattributes      换行与二进制规则
```

每个版本目录内部（**主体已经搬到 muonium-common，这里只剩版本差异**）：

```
<版本>/
├─ settings.gradle       pluginManagement（Fabric / NeoForged / Forge / 阿里云）+ include 'fabric','neoforge'
├─ build.gradle          聚合：allprojects 的 group/version + buildAll / collectDist 任务
├─ gradle.properties     版本号、加载器版本、代理与 JDK 登记
├─ src/main/java         本树版本差异层：compat/、mixin/、以及功能集没对齐的那几个文件（共 34 个）
├─ src/main/resources    只剩 assets/muonium/lang/*.json（键位随版本 API 变）
├─ src/test/java         只留各树自己的移植/探针测试（13 个）
├─ fabric/               fabric-loom：入口外壳、通道实现、fabric.mod.json、mixins.json、accesswidener
└─ neoforge/（1.20.1 是 forge/）  MDG + shadow：入口外壳、通道实现、mods.toml、pack.mcmeta
```

两个加载器子工程各自把三棵共享源树挂进自己的 source set（**绝不复制**）：

```gradle
sourceSets.main.java.srcDirs = [
        'src/main/java',                                          // 本加载器外壳
        rootProject.file('../muonium-common/src/main/java'),      // 五树共享主体
        rootProject.file('src/main/java'),                        // 本树版本差异层
        rootProject.file('../mpparty-common/src/main/java')       // 一起听共用源码
]
```

挂接与搬运都由 `tools/share-trees.py` 做（`classify` / `apply` / `wire` / `verify`），
`SharedLayoutStaticTest` 钉着三件事：两边不许有同名类、共享层不许 import 加载器符号、
留守名单不许悄悄变胖。

## 不入库的东西

`build/`、`.gradle/`、`.idea/`、`run/`（含账号 cookie 与缓存音频）、`logs/`、`*.log`、
`_mcref/`（反编译 client jar + fabric-api + JDK，约 470 MB，本地生成）、
误建目录 `%SystemDrive%/`、`_legacy-git/`。

入库的是每个版本的 `src/`、`docs/`、`tools/`、`libs/`（三个第三方 jar：
`kotlin-stdlib` / `music-api` / `zstd-jni`，Maven 上取不到，必须随仓走）、
Gradle wrapper 与构建配置。

## 常用操作

在仓库根目录的 PowerShell 里直接点调用脚本（不要用 `pwsh -File`，那样数组参数会被当成一个字符串）：

```powershell
# 全版本构建（默认任务 buildAll = 两个加载器各出一个 jar，并汇总到仓库根 dist/；
# 日志写 _build_all.log，末尾打印 dist/ 里的 10 个 jar，任一版本失败退出码为 1）
# 默认会清理输出目录中旧的 MuoniumPlayer-*.jar；需要保留时加 -KeepOldJars
.\build-all.bat

# PowerShell 里直接调用；构建到指定目录而不是默认 dist/
.\tools\build-all.ps1 -OutputDir releases\1.6.1

# 只构建部分版本 / 换任务 / 离线 / 不复用 Gradle daemon
.\tools\build-all.ps1 -Versions '1.21.1','26.2' -Task ':fabric:build'
.\tools\build-all.ps1 -Task ':fabric:test' -Offline -NoDaemon

# 单版本：两个加载器都构建
cd '1.21.11 recode'; .\gradlew.bat buildAll

# 单版本：只编一侧 / 只跑测试（测试挂在 fabric 子工程上）
cd '1.21.11 recode'; .\gradlew.bat :neoforge:compileJava
cd '1.21.11 recode'; .\gradlew.bat :fabric:test

# 开发期启动客户端
cd '1.21.11 recode'; .\gradlew.bat :fabric:runClient      # 或 :neoforge:runClient / :forge:runClient

# 看两个版本之间的源码漂移（只在一边的文件 / 两边都有但内容不同的文件）
.\tools\diff-versions.ps1 -Left '1.21.1' -Right '1.21.11 recode'
.\tools\diff-versions.ps1 -Left '1.21.1' -Right '26.2' -SubPath 'src/main/java/com/muoniumplayer/compat'

# 共享层的搬运与核对（详见 muonium-common/README.md）
python tools\share-trees.py classify        # 五树体检：SAME / DRIFT / FORK / ONLY
python tools\share-trees.py apply --confirm # 按底片搬运，并重新生成 SHARE-MANIFEST.md
python tools\share-trees.py wire            # 把三个共享根挂进 10 个加载器构建（幂等）
python tools\share-trees.py verify          # 核对：共享 + 留守 == 底片并集，不丢不重
```

多加载器化之后，每个版本 861 个 java 文件里只有 20 个左右会碰 `net.minecraft`；
真正的加载器差异压缩到了 2 个文件（入口外壳 + 通道实现），版本差异压缩到了
`compat/` 的 12 个文件 —— 剩下的都在 `muonium-common/` 里只存一份。

**首次构建 Forge/NeoForge 侧要等**：MDG 的 `createMinecraftArtifacts`（反编译 + 打补丁 +
重编译整个 Minecraft）单版本约 10～15 分钟，之后走缓存。

## Git 网络配置（本机）

github.com 直连不通、必须走 `127.0.0.1:7890`，且 Windows 默认的 schannel 后端在这个代理下
TLS 握手会失败，所以本仓已写入 **仓库级** 配置（`git config --local`）：

```
http.proxy   = http://127.0.0.1:7890
https.proxy  = http://127.0.0.1:7890
http.sslBackend = openssl
```

换机器或重新 clone 后需要再设一遍这三项，否则 push 会报
`schannel: failed to receive handshake` 或 `Connection was reset`。

Gradle 侧同一个代理会偶发 `Remote host terminated the handshake`，
`maven.neoforged.net` / `maven.minecraftforge.net` 上尤其容易碰到。实测这两个域名**直连**
稳定 200 且约 1s，走 7890 代理 `curl -I` 三次里有两次是 `000`，所以正解是把它们放进直连白名单
（五个版本的 `gradle.properties` 都已加）：

```properties
systemProp.http.nonProxyHosts=…|maven.minecraftforge.net|maven.neoforged.net
systemProp.https.nonProxyHosts=…|maven.minecraftforge.net|maven.neoforged.net
```

TLS 版本也**不能只留 1.2**。1.20.1 那份原来写的是 `systemProp.https.protocols=TLSv1.2`，
`maven.minecraftforge.net`（只有这一版要用它取 `forge-1.20.1-47.4.0-userdev.jar`）会直接拒绝
纯 1.2 的 ClientHello，`:forge:createMinecraftArtifacts` 稳定失败并报
`The server may not support the client's requested TLS protocol versions: (TLSv1.2)`。
现在写成两个都提、1.3 优先：

```properties
systemProp.https.protocols=TLSv1.3,TLSv1.2
```

另外 `repo.maven.apache.org` 在本机完全不可用，各版本 `build.gradle` 里的
`retargetCentral` 会把所有 Maven Central 仓库（含插件自己注入的 `mavenCentral()`）
改写到阿里云。

## 历史

`1.20.1 recode` 迁入前是独立 git 仓库，历史（baseline → Skija 探针 → 渲染层方案 → S1 宿主层）
完整保留在分支 **`archive/1.20.1-recode`**；其原始 `.git` 目录备份在未入库的
`_legacy-git/1.20.1-recode.git`。`main` 的首个提交是四个版本迁入时的磁盘现状
（含当时 1.20.1 未提交的工作区改动）。

## 跨版本去重（已落地）

加载器维度的重复先消掉（同一份源码同时喂给两个加载器），然后是版本维度：
五份 `src/` 各约 12 MB、861 个 java 路径，字体资源 21.5 MB 完全重复。
2026-10-01 按 `mpparty-common` 的模式把中立部分抽成了 `muonium-common/`：

| | 搬家前 | 搬家后 |
| --- | --- | --- |
| 主源码 | 861 路径 × 5 份 | 共享 827 + 各树留守 30~33 |
| 资源 | 55 路径 × 5 份 | 共享 52 + 各树 lang 2 |
| 测试 | 121 路径 × 5 份 | 共享 108 + 各树 4~9（另加一份新写的共享守卫 `SharedLayoutStaticTest`） |

留守的 34 个主源码文件（`compat/` 12 个、mixin 3 个、API 世代差异 12 个、
功能集没对齐的 3 个 + `party/net/MpPayloads`）逐条登记在
`muonium-common/SHARE-MANIFEST.md`，并由 `SharedLayoutStaticTest` 钉住。

以后再要缩小这张表，路子只有一个：把差异**下沉成 compat 的同名方法**，
而不是把文件从共享层搬回各树。搬家前的实测数据（`tools\diff-versions.ps1`）留在这里作参照：
`1.21.1` 与 `1.21.11 recode` 的 748 / 750 个文件里 720 个逐字节相同，
26 个不同 —— 版本差异的实际面积一直就这么大。

## 分支

* `main` —— 四个版本迁入单仓时的状态（当时还都是单加载器 Fabric 工程）。
* `feat/multiloader` —— 本次多加载器化，四个版本各一个提交。
* `archive/1.20.1-recode` —— `1.20.1 recode` 迁入前的独立仓库历史。
