# 1.21.11 车道补充规范

> 继承仓库根 `AGENTS.md`；共享源码另遵守 `muonium-common/AGENTS.md`。核对：2026-11-11。
> `recode` 是目录名的一部分，不改变 Minecraft 版本号。

## API 使用与移植约束（强制）

- 本树分为 **Minecraft 1.21.11 × Fabric** 与 **Minecraft 1.21.11 × NeoForge** 两个独立核实组合；分别以当前实际解析的 loader/API 依赖、映射和访问配置为准，不按笼统“1.21.x”或“接近 26.x”推断行为。
- 禁止编造 API、猜签名或凭其他版本套用。新增/替换调用先核实本组合依赖源码或字节码；同树现有调用、相邻版本、通用示例及本文快照都不能单独证明兼容。
- 类/成员、事件注册位置与阶段、线程、网络、GUI/输入、渲染及 Mixin 目标逐触点确认；同树另一个加载器能用，不代表当前加载器也能用。
- 跨树/跨加载器只复用已确认一致的业务语义；底层适配按实际分别实现，不为统一代码强行套用 API。
- 查不到准确依据时明确“未确认”，不得猜替代 API、用空实现假装支持或通过升级依赖掩盖问题。执行根规范「API 与版本事实」的证据要求。
- 分别记录两组合的核实、编译及实测结果；未验证不得写成通过，一侧成功不得称“双加载器已兼容”。

## 配置快照

| 项目 | 当前值/来源 |
| --- | --- |
| MC / 外壳 | `1.21.11`；`fabric/` + `neoforge/` |
| Java 目标/运行 / daemon | 21 / 21；两侧 `build.gradle` 与 `gradle.properties` |
| Fabric Loader 开发 / 下限 | `0.19.3` / `0.17.3` |
| Fabric API | `0.141.6+1.21.11` |
| NeoForge 开发 / 下限 | `21.11.45` / `21.11.0` |
| FLK / KFF | `1.13.12+kotlin.2.4.0` / `6.0.0` |
| Kotlin 编译器 / 语言与 API / stdlib | `2.4.21` / `2.2` / `2.2.20`；仓库根 `gradle/kotlin-support.gradle` |
| Fabric / NeoForge 插件 | `fabric-loom 1.17.20` / `net.neoforged.moddev 2.0.144` |

## 本树的版本边界

- 仍是有映射的 Fabric 车道：`loom.officialMojangMappings()`、官方名源码、发布重映射。不能照搬 26.x Loom/Java 25 配置。
- GUI 仍是 `GuiGraphics`/`render`；输入用事件封装类型。GUI 为延迟批处理，Skija 开帧/绘制与最终合成阶段不可随意合并。
- **实体渲染不能当成 1.20.1/1.21.1**：本树已有渲染状态提取/提交体系。通透/陀螺等注入逐项确认，旧文档将 1.21.11 归为 `EntityRenderDispatcher.render` 的描述已不可靠。
- GL 类路径、GPU 对象、FBO/纹理生命周期看本树 `SkijaHost` 与 mixin；既不能因还叫 1.21 就全用老 API，也不能推导为 26.x `renderpearl`。
- 不把 26.3 SDL/scancode 窗口输入实现复制到此树。
- Java 目标 21；Kotlin 本树 API 2.2；共享 Kotlin 继续按 1.8。
- 本树可选 ModernUI 与默认外仓 MuoniumConfig 是不同 UI 路线，改 H 设置中心先确认路由与开发包同步。

## 验证

在仓库根 PowerShell：

```powershell
.\tools\build-all.ps1 -Versions '1.21.11 recode' -Task ':fabric:test' -KeepOldJars
.\tools\build-all.ps1 -Versions '1.21.11 recode'
```

在本树目录：

```powershell
.\gradlew.bat :fabric:runClient
.\gradlew.bat :neoforge:runClient
.\gradlew.bat :fabric:kotlinSmokeTest :neoforge:kotlinSmokeTest --no-parallel --max-workers=1
```

保留 `Port121StaticTest`、按键与渲染层守卫。Mixin/渲染改动检查运行时命中和合成阶段，不能以“与旧树同文”替代验证；共享改动仍须七树验收。
