# 1.21.1 车道补充规范

> 继承仓库根 `AGENTS.md`；共享源码另遵守 `muonium-common/AGENTS.md`。核对：2026-11-11。
> 快照只说明当前配置；升级时同步更新，不作为自动追新的理由。

## API 使用与移植约束（强制）

- 本树分为 **Minecraft 1.21.1 × Fabric** 与 **Minecraft 1.21.1 × NeoForge** 两个独立核实组合；分别以当前实际解析的 loader/API 依赖、映射和访问配置为准，不能用其他 1.21.x 版本代证。
- 禁止编造 API、猜签名或凭其他版本套用。新增/替换调用先核实本组合依赖源码或字节码；同树现有调用、相邻版本、通用示例及本文快照都不能单独证明兼容。
- 类/成员、事件注册位置与阶段、线程、网络、GUI/输入、渲染及 Mixin 目标逐触点确认；同树另一个加载器能用，不代表当前加载器也能用。
- 跨树/跨加载器只复用已确认一致的业务语义；底层适配按实际分别实现，不为统一代码强行套用 API。
- 查不到准确依据时明确“未确认”，不得猜替代 API、用空实现假装支持或通过升级依赖掩盖问题。执行根规范「API 与版本事实」的证据要求。
- 分别记录两组合的核实、编译及实测结果；未验证不得写成通过，一侧成功不得称“双加载器已兼容”。

## 配置快照

| 项目 | 当前值/来源 |
| --- | --- |
| MC / 外壳 | `1.21.1`；`fabric/` + `neoforge/` |
| Java 目标/运行 / daemon | 21 / 21；两侧 `build.gradle` 与 `gradle.properties` |
| Fabric Loader 开发 / 下限 | `0.19.3` / `0.15.11` |
| Fabric API | `0.116.15+1.21.1` |
| NeoForge 开发 / 下限 | `21.1.249` / `21.1.0` |
| FLK / KFF | `1.13.12+kotlin.2.4.0` / `5.9.0` |
| Kotlin 编译器 / 语言与 API / stdlib | `2.4.21` / `2.0` / `2.0.0`；仓库根 `gradle/kotlin-support.gradle` |
| Fabric / NeoForge 插件 | `fabric-loom 1.17.20` / `net.neoforged.moddev 2.0.144` |

## 本树边界

- 两侧写 Mojang 官方名。Fabric 用 `loom.officialMojangMappings()` 并重映射；NeoForge 直接官方名，不借 Yarn patch 对齐。
- 保持 Java 21；不复制 26.x 的 Java 25、非重映射 Loom 或 GUI extractor 配置。
- 此版 Screen 输入及世界/实体渲染按本树真实签名适配；不要把 1.21.10/11 的事件 record、较新渲染阶段或 26.3 SDL 代码回贴到此树。
- NeoForge 开发期模块路径可能暴露依赖 split package，不能因新树启动正常就删此树打包隔离；共享库能力与 Fabric 对齐，但嵌包方式按外壳维护。
- `HudConfig`、`HudSetting`、相关 Screen 仍有树内留守副本。改设置键/default/schema 必须检查七树和 MuoniumConfig 契约，不默认这里与新树同文或功能完全相同。
- Kotlin 本树 API 上限为 2.0；共享 Kotlin 仍是 1.8，不因为本树能编译就用共享的新语言特性。
- `external/modernui/` 是可选旧适配；默认 H 面板是外仓 MuoniumConfig，排查时先确认实际 UI 路由及 `run/mods` 新包。

## 验证

在仓库根 PowerShell：

```powershell
.\tools\build-all.ps1 -Versions '1.21.1' -Task ':fabric:test' -KeepOldJars
.\tools\build-all.ps1 -Versions '1.21.1'
```

在本树目录：

```powershell
.\gradlew.bat :fabric:runClient
.\gradlew.bat :neoforge:runClient
.\gradlew.bat :fabric:kotlinSmokeTest :neoforge:kotlinSmokeTest --no-parallel --max-workers=1
```

保留 `Port1211StaticTest` 等版本守卫；测试通过不代表新 Mixin 运行时已命中。共享改动按根规范扩展到七树，不以本树单点验收代替。
