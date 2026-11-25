# 26.3 主开发车道补充规范

> 继承仓库根 `AGENTS.md`；共享源码另遵守 `muonium-common/AGENTS.md`。核对：2026-11-11。
> 26.3 是新功能宿主适配与实测基准，**不是共享层语言/API 基线**。

## API 使用与移植约束（强制）

- 本树分为 **Minecraft 26.3 × Fabric** 与 **Minecraft 26.3 × NeoForge** 两个独立核实组合；分别以当前实际解析的 loader/API 依赖、映射和访问配置为准。主开发树身份不赋予本树 API 跨版本有效性。
- 禁止编造 API、猜签名或凭其他版本套用。新增/替换调用先核实本组合依赖源码或字节码；同树现有调用、相邻版本、通用示例及本文快照都不能单独证明兼容。
- 类/成员、事件注册位置与阶段、线程、网络、GUI/输入、渲染及 Mixin 目标逐触点确认；同树另一个加载器能用，不代表当前加载器也能用。
- 跨树/跨加载器只复用已确认一致的业务语义；底层适配按实际分别实现，不为统一代码强行套用 API。
- 查不到准确依据时明确“未确认”，不得猜替代 API、用空实现假装支持或通过升级依赖掩盖问题。执行根规范「API 与版本事实」的证据要求。
- 分别记录两组合的核实、编译及实测结果；未验证不得写成通过，一侧成功不得称“双加载器已兼容”。

## 配置快照

| 项目 | 当前值/来源 |
| --- | --- |
| MC / 外壳 | `26.3`；`fabric/` + `neoforge/` |
| Java 目标/运行 / daemon | 25 / 25；两侧 `build.gradle` 与 `gradle.properties` |
| Fabric Loader 开发 / 下限 | `0.19.5` / `0.19.3` |
| Fabric API | `0.161.0+26.3` |
| NeoForge 开发 / 下限 | `26.3.0.16-beta` / `26.3.0.0-beta` |
| FLK / KFF | `1.14.1+kotlin.2.4.20` / `6.4.0` |
| Kotlin 编译器 / 语言与 API / stdlib | `2.4.21` / `2.4` / `2.4.0`；仓库根 `gradle/kotlin-support.gradle` |
| Fabric / NeoForge 插件 | `net.fabricmc.fabric-loom 1.17.20` / `net.neoforged.moddev 2.0.147` |

## 新功能开发顺序

1. 中立业务、数据和算法直接放共享根，不在本树先放一份临时副本。
2. 先完成本树 compat/外壳适配与双加载器验证，再逐项回适其余六树。
3. 共享调用契约按七树可实现的语义设计，不能向共享层暴露仅本树存在的类型/方法。
4. 给出七树能力/降级与验证结果。只在本树实现的阶段性功能必须明确标注，不能把未移植当成版本天生不支持。
5. 主开发树通过不是交付完成；共享生产代码仍要七树测试和十四目标构建。

## 本树不可退回的实现约束

- 官方 jar 未混淆，Java 25，Fabric 非重映射。不能添加 `client_mappings`、Yarn/intermediary 或旧 remap 任务。
- 当前窗口/输入后端是 **SDL**。按本树 Display/Keyboard/Mouse、入口和 mixin 维护窗口句柄、scancode 与鼠标状态，不换回 GLFW 模板，也不把它直接复制到旧树。
- Screen 使用 `GuiGraphicsExtractor`/`extractRenderState`；渲染状态采集、世界提交、Skija 最终合成不是同一个阶段。改挂点前确认顺序和 GL/纹理拥有者。
- GL/GPU/相机等类路径按本树真实 API；不能仅因同属 26.x 就直接覆盖 26.1.2/26.2。
- NeoForge 当前依赖为 **beta**，交付时如实说明。MDG `2.0.147` 用于解决旧插件面对上游 HolderSet 访问变化的重编译问题，不能回退到 `2.0.144` 来“统一版本”。
- FLK/KFF 运行时版本较新也不能抬高共享 Kotlin 基线或额外打包标准库。
- 真实 GPU/性能探针有运行环境要求；跳过或环境不满足时明确报告，不将普通单元测试冒充 GPU 验收。

## 验证

在仓库根 PowerShell：

```powershell
.\tools\build-all.ps1 -Versions '26.3' -Task ':fabric:test' -KeepOldJars
.\tools\build-all.ps1 -Versions '26.3'
```

在本树目录：

```powershell
.\gradlew.bat :fabric:runClient
.\gradlew.bat :neoforge:runClient
.\gradlew.bat :fabric:kotlinSmokeTest :neoforge:kotlinSmokeTest --no-parallel --max-workers=1
```

保留 `Port263StaticTest`、布局、输入、渲染与打包守卫。按影响实测启动/切歌/切屏、SDL 输入、resize/失焦、世界歌词/光影及资源释放；性能结论记录场景、基线和测量数据。


### 商籁文字平面验证（2026-11-23）

商籁主体位于共享 `core/lyrics3d/Sonnet*`；正常渲染为透明 Skija Picture，经本树既有 `drawGpuTrailCanvas` / CPU 回放口送进 3D 平面，不新增背景或 MC 挂点。字体、Picture、画笔、滤镜在渲染线程拥有/释放，`WorldLyricsBridge` 关闭/无世界/无歌词及 `SkijaHost.shutdown` 均接入共享清理。

`SonnetGpuProbeTest` 留在本树：它依赖本树 `SkijaTrailGpuTest` 的隐藏 SDL/OpenGL 和 MC RenderSystem 夹具，并实际执行 GPU 回读对照。需要显式 `-Dmuonium.ci.skipSkijaNative=false -Dmuonium.test.gpuTrails=true` 传进测试 JVM；普通 test 跳过它不构成 GPU 通过。此探针与源码/单测通过仍不等于 Minecraft 实际场景、光影或其他版本/加载器实测通过。验证范围仍遵守用户当轮授权，禁止自行扩展构建。


2026-11-24：普通世界通路的商籁整幅画布使用 `WorldBillboard.SONNET_CANVAS_PIPELINE` 与本树已有 `worldlyrics_text` shaders，避免 vanilla `text.fsh` 的 alpha<0.1 裁剪吞掉细线/入退场尾部。SonnetGpuProbeTest 同时核对实际管线对象及编译其资源着色器后的低 alpha 合成；光影包继续按既有 beacon/final-present 分发，探针不代替光影/天气游戏实测。

## 内置 Skija 设置中心接线（2026-11-24）

主体与资源由两侧既有 sourceSets 挂载 `muonium-common`，详见 `docs/实现约定-内置Skija设置中心.md`。H 走 `SkijaConfigScreen.openSettings()`，客户端接入 `SkijaConfigService.tick()`、HUD 后 `afterHud()` 和屏幕后 `afterScreen()`；不要求 OneConfig/MuoniumConfig 独立 MOD。调用阶段以本树入口/渲染桥实际调用链为准，注册存在不等于运行语义已验证。

本树只保留 `configskija/host/WindowAccess.java`、`host/avatar/{AvatarSkinAccess,AvatarTextureAccess}.java`、`host/keys/NativeKeyAccess.java`、`host/sound/McSoundBackend.java` 与标准 `mixin/configskija/ConfigToastMouseMixin.java` 六个适配触点；其余 host、UI、model 在共享根。窗口密度取 SDL display scale，当前屏幕读取 `mc.gui.screen()`，原生键型使用 `InputConstants.Type.KEYBOARD`，鼠标按钮经 SDL/legacy 编号转换。以上为源码触点，不能由同属 26.x 推断其他树或 NeoForge 契约相同。

鼠标 Mixin 位于 `src/main/java/com/muoniumplayer/mixin/configskija/`，声明包为 `com.muoniumplayer.mixin.configskija`，两侧 `muonium.mixins.json` 包前缀是 `com.muoniumplayer.mixin`，`client` 项须为 `configskija.ConfigToastMouseMixin`。本树源码描述符为 `onMove(JDDDD)V` 与 `onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V`；布局守卫核对登记，描述符、加载和命中仍须逐 loader 另行验证。

用户的 `src/test/java/com/muoniumplayer/core/screens/hud/MConfigHudRenderProbeTest.java` 留本树，已登记为版本探针。它使用 Unsafe 构造本树 Minecraft/Screen 最小夹具，在 Windows 加载真 Skija 字体，对 `MConfigHudScreen.renderProbe` 输出 raster PNG 并检查非空；不启动游戏，但会生成测试图片。登记不意味着执行成功，也不能作为迁入 `ConfigView` 的完整 UI、GPU、Mixin 或游戏验收。

本轮用户限定仅验证 26.3 Fabric，不构建、不启动游戏；26.3 NeoForge 与其他六树两侧仅只读适配/接线核对。不得将此阶段检查写成十四目标通过；完整验收仍按后续授权执行。

本次补充只读确认并行用户的 `WorldBillboardCanvasPictureCacheTest` 依赖本树 `WorldBillboard` 包可见嵌套类型 `CanvasPictureCache`（其他六树未定义），登记 OPTIONAL_TEST 及原因。没有修改或执行该测试，也不将其计为设置中心验证成果。用户已报告在 26.3 Fabric 执行 `SettingsNavigationTest` 3 项通过：分类唯一、逐分类点击、历史滚动；这是用户提供的指定测试结果。此次路径/登记修改后不重跑布局测试，等主 agent 最终 Gradle 统一；最终构建尚未运行，其他车道无新增测试结论。
