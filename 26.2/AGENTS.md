# 26.2 车道补充规范

> 继承仓库根 `AGENTS.md`；共享源码另遵守 `muonium-common/AGENTS.md`。核对：2026-11-11。
> 此树使用稳定依赖车道，但不是其他 26.x 版本的通用模板。

## API 使用与移植约束（强制）

- 本树分为 **Minecraft 26.2 × Fabric** 与 **Minecraft 26.2 × NeoForge** 两个独立核实组合；分别以当前实际解析的 loader/API 依赖、映射和访问配置为准。不能以 26.1.2 或 26.3 的实现代证。
- 禁止编造 API、猜签名或凭其他版本套用。新增/替换调用先核实本组合依赖源码或字节码；同树现有调用、相邻版本、通用示例及本文快照都不能单独证明兼容。
- 类/成员、事件注册位置与阶段、线程、网络、GUI/输入、渲染及 Mixin 目标逐触点确认；同树另一个加载器能用，不代表当前加载器也能用。
- 跨树/跨加载器只复用已确认一致的业务语义；底层适配按实际分别实现，不为统一代码强行套用 API。
- 查不到准确依据时明确“未确认”，不得猜替代 API、用空实现假装支持或通过升级依赖掩盖问题。执行根规范「API 与版本事实」的证据要求。
- 分别记录两组合的核实、编译及实测结果；未验证不得写成通过，一侧成功不得称“双加载器已兼容”。

## 配置快照

| 项目 | 当前值/来源 |
| --- | --- |
| MC / 外壳 | `26.2`；`fabric/` + `neoforge/` |
| Java 目标/运行 / daemon | 25 / 25；两侧 `build.gradle` 与 `gradle.properties` |
| Fabric Loader 开发 / 下限 | `0.19.3` / `0.18.4` |
| Fabric API | `0.158.0+26.2` |
| NeoForge 开发 / 下限 | `26.2.0.72` / `26.2.0` |
| FLK / KFF | `1.13.12+kotlin.2.4.0` / `6.3.0` |
| Kotlin 编译器 / 语言与 API / stdlib | `2.4.21` / `2.4` / `2.4.0`；仓库根 `gradle/kotlin-support.gradle` |
| Fabric / NeoForge 插件 | `net.fabricmc.fabric-loom 1.17.20` / `net.neoforged.moddev 2.0.144` |

## 本树适配重点

- 官方未混淆名称、Java 25、Fabric 非重映射路线；不要恢复 1.21.x mapping/remap 配置。共享语言/API 基线仍由最老车道决定。
- GUI 是 `GuiGraphicsExtractor`/`extractRenderState`，世界渲染与 GPU/GL 接口看本树 compat；不把提取阶段当成最终画面提交。
- 此树 `compat/lwjgl2/Display` 当前使用 GLFW；26.3 使用 SDL，窗口句柄、按键码、滚轮/按住状态不能直接回贴。
- 本树存在光影期世界歌词直画相关适配；改 `LyricPresentRenderer`、`LyricBlaze3DRenderer`、`WorldLyricsHudDraw` 或挂点时，检查 GL 状态交接、矩阵来源、深度/混合与最终阶段。
- 当前 NeoForge `26.2.0.72` 配 MDG `2.0.144`；升级到引入 HolderSet 访问变化的较新 NeoForge 时，必须重新核对插件兼容，不能照旧版本能编译就假定新版本也行。
- 版本中立修复直接改共享根；此树只承担本版 API 适配，不维护第二份业务主体。

## 验证

在仓库根 PowerShell：

```powershell
.\tools\build-all.ps1 -Versions '26.2' -Task ':fabric:test' -KeepOldJars
.\tools\build-all.ps1 -Versions '26.2'
```

在本树目录：

```powershell
.\gradlew.bat :fabric:runClient
.\gradlew.bat :neoforge:runClient
.\gradlew.bat :fabric:kotlinSmokeTest :neoforge:kotlinSmokeTest --no-parallel --max-workers=1
```

保留 `Port262StaticTest` 等版本守卫。涉及世界歌词/GL 时按影响补无光影和相关光影场景、FBO/resize/GUI scale 与退出清理验证；共享改动仍须七树验收。
