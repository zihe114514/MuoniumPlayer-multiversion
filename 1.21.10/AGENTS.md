# 1.21.10 车道补充规范

> 继承仓库根 `AGENTS.md`；共享源码另遵守 `muonium-common/AGENTS.md`。核对：2026-11-11。
> 此树是独立车道，不是 1.21.1 或 1.21.11 的目录别名。

## API 使用与移植约束（强制）

- 本树分为 **Minecraft 1.21.10 × Fabric** 与 **Minecraft 1.21.10 × NeoForge** 两个独立核实组合；分别以当前实际解析的 loader/API 依赖、映射和访问配置为准，不能由 1.21.1 或 1.21.11 推断本版 API。
- 禁止编造 API、猜签名或凭其他版本套用。新增/替换调用先核实本组合依赖源码或字节码；同树现有调用、相邻版本、通用示例及本文快照都不能单独证明兼容。
- 类/成员、事件注册位置与阶段、线程、网络、GUI/输入、渲染及 Mixin 目标逐触点确认；同树另一个加载器能用，不代表当前加载器也能用。
- 跨树/跨加载器只复用已确认一致的业务语义；底层适配按实际分别实现，不为统一代码强行套用 API。
- 查不到准确依据时明确“未确认”，不得猜替代 API、用空实现假装支持或通过升级依赖掩盖问题。执行根规范「API 与版本事实」的证据要求。
- 分别记录两组合的核实、编译及实测结果；未验证不得写成通过，一侧成功不得称“双加载器已兼容”。

## 配置快照

| 项目 | 当前值/来源 |
| --- | --- |
| MC / 外壳 | `1.21.10`；`fabric/` + `neoforge/` |
| Java 目标/运行 / daemon | 21 / 21；两侧 `build.gradle` 与 `gradle.properties` |
| Fabric Loader 开发 / 下限 | `0.19.3` / `0.17.0` |
| Fabric API | `0.138.4+1.21.10` |
| NeoForge 开发 / 下限 | `21.10.64` / `21.10.0` |
| FLK / KFF | `1.13.12+kotlin.2.4.0` / `6.0.0` |
| Kotlin 编译器 / 语言与 API / stdlib | `2.4.21` / `2.2` / `2.2.20`；仓库根 `gradle/kotlin-support.gradle` |
| Fabric / NeoForge 插件 | `fabric-loom 1.17.20` / `net.neoforged.moddev 2.0.144` |

## 过渡车道的适配规则

- 使用 Mojang 官方名。Fabric 仍是 `loom.officialMojangMappings()` + 重映射，不能因接近 26.x 而切换非重映射构建。
- Screen 本树使用 `GuiGraphics`/`render`；输入已使用 `KeyEvent`、`MouseButtonEvent` 等封装类型。这些边界不能用一句“旧 GUI”或“新输入”概括并批量覆盖。
- 世界/实体渲染、RenderType、GPU 与输入分别核对本树源码/字节码；不要从 1.21.1 复制整套 compat，也不要从 1.21.11/26.x 推导注入描述符。
- `RenderTypeCreateAccessor` 有本版四参 `create` 形态；不能替换为 26.x 的 `RenderSetup` 形态 accessor。改时同时核查 mixin 配置和真正调用处。
- Java 目标仍为 21；Kotlin 本树语言/API 2.2，共享 Kotlin 仍按 1.8。
- 可选 ModernUI 留 `external/modernui/`；H 默认设置中心在 MuoniumConfig 外仓，不能把旧适配与 Compose 界面混淆。
- 此树必须参与共享改动的七树验收；不能因为旧规范漏列 1.21.10 而跳过。

## 验证

在仓库根 PowerShell：

```powershell
.\tools\build-all.ps1 -Versions '1.21.10' -Task ':fabric:test' -KeepOldJars
.\tools\build-all.ps1 -Versions '1.21.10'
```

在本树目录：

```powershell
.\gradlew.bat :fabric:runClient
.\gradlew.bat :neoforge:runClient
.\gradlew.bat :fabric:kotlinSmokeTest :neoforge:kotlinSmokeTest --no-parallel --max-workers=1
```

保留 `Port12110StaticTest`、按键与渲染层守卫。GUI/输入变化实测字符输入、组合键、鼠标按住/释放、滚轮和 resize；世界渲染变化检查正确绘制阶段及光影兼容。
