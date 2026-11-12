# 26.1.2 车道补充规范

> 继承仓库根 `AGENTS.md`；共享源码另遵守 `muonium-common/AGENTS.md`。核对：2026-11-11。
> 此树独立构建与验证，不是 26.2 的产物别名。

## API 使用与移植约束（强制）

- 本树分为 **Minecraft 26.1.2 × Fabric** 与 **Minecraft 26.1.2 × NeoForge** 两个独立核实组合；分别以当前实际解析的 loader/API 依赖、映射和访问配置为准。不能因同属 26.x 就套用 26.2/26.3。
- 禁止编造 API、猜签名或凭其他版本套用。新增/替换调用先核实本组合依赖源码或字节码；同树现有调用、相邻版本、通用示例及本文快照都不能单独证明兼容。
- 类/成员、事件注册位置与阶段、线程、网络、GUI/输入、渲染及 Mixin 目标逐触点确认；同树另一个加载器能用，不代表当前加载器也能用。
- 跨树/跨加载器只复用已确认一致的业务语义；底层适配按实际分别实现，不为统一代码强行套用 API。
- 查不到准确依据时明确“未确认”，不得猜替代 API、用空实现假装支持或通过升级依赖掩盖问题。执行根规范「API 与版本事实」的证据要求。
- 分别记录两组合的核实、编译及实测结果；未验证不得写成通过，一侧成功不得称“双加载器已兼容”。

## 配置快照

| 项目 | 当前值/来源 |
| --- | --- |
| MC / 外壳 | `26.1.2`；`fabric/` + `neoforge/` |
| Java 目标/运行 / daemon | 25 / 25；两侧 `build.gradle` 与 `gradle.properties` |
| Fabric Loader 开发 / 下限 | `0.19.3` / `0.18.4` |
| Fabric API | `0.155.3+26.1.2` |
| NeoForge 开发 / 下限 | `26.1.2.126` / `26.1.2` |
| FLK / KFF | `1.13.12+kotlin.2.4.0` / `6.3.0` |
| Kotlin 编译器 / 语言与 API / stdlib | `2.4.21` / `2.4` / `2.4.0`；仓库根 `gradle/kotlin-support.gradle` |
| Fabric / NeoForge 插件 | `net.fabricmc.fabric-loom 1.17.20` / `net.neoforged.moddev 2.0.144` |

## 此车道的硬边界

- 官方 jar 未混淆，直接官方名，Fabric 不重映射；不添加 `client_mappings`、Yarn/intermediary、旧 mapping 依赖或 remap 任务。
- daemon/编译/游戏使用 Java 25；Java 21 无法读取此版 MC class。共享源码仍受 Java 17/Kotlin 1.8 基线限制。
- GUI 使用 `GuiGraphicsExtractor`/`extractRenderState`；提取不是最终绘制，Skija 合成必须留在正确的帧阶段。
- **26.x 不是一个相同 API 车道**：26.1.2 与 26.2/26.3 的 GL/GPU 类路径、纹理与世界渲染接口要逐项确认。当前文件中沿用的“26.2”注释不构成版本依据。
- 不复制 26.3 的 SDL 窗口/输入实现；先看本树 Display/Keyboard/Mouse 和宿主库。
- NeoForge 本树使用稳定依赖 `26.1.2.126` 和当前 MDG `2.0.144`；不因主开发树需要 `2.0.147` 就盲目整体覆盖构建文件。
- 本树必须参与共享代码、资源与设置契约的七树验证；不因旧文档漏列而跳过。

## 验证

在仓库根 PowerShell：

```powershell
.\tools\build-all.ps1 -Versions '26.1.2' -Task ':fabric:test' -KeepOldJars
.\tools\build-all.ps1 -Versions '26.1.2'
```

在本树目录：

```powershell
.\gradlew.bat :fabric:runClient
.\gradlew.bat :neoforge:runClient
.\gradlew.bat :fabric:kotlinSmokeTest :neoforge:kotlinSmokeTest --no-parallel --max-workers=1
```

保留 `Port2612StaticTest`、双加载器/依赖、按键与渲染层守卫。世界歌词、屏幕提示、GUI 合成等变化须运行实测；共享修改仍扩展到七树完整范围。
