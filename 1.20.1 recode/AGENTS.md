# 1.20.1 车道补充规范

> 继承仓库根 `AGENTS.md`；共享源码另遵守 `muonium-common/AGENTS.md`。核对：2026-11-11。
> 本文快照不代替配置；变更依赖时同步核对并更新本文。

## API 使用与移植约束（强制）

- 本树分为 **Minecraft 1.20.1 × Fabric** 与 **Minecraft 1.20.1 × Forge** 两个独立核实组合；分别以当前实际解析的 loader/API 依赖、映射和访问配置为准。Forge 不按 NeoForge 规则处理。
- 禁止编造 API、猜签名或凭其他版本套用。新增/替换调用先核实本组合依赖源码或字节码；同树现有调用、相邻版本、通用示例及本文快照都不能单独证明兼容。
- 类/成员、事件注册位置与阶段、线程、网络、GUI/输入、渲染及 Mixin 目标逐触点确认；同树另一个加载器能用，不代表当前加载器也能用。
- 跨树/跨加载器只复用已确认一致的业务语义；底层适配按实际分别实现，不为统一代码强行套用 API。
- 查不到准确依据时明确“未确认”，不得猜替代 API、用空实现假装支持或通过升级依赖掩盖问题。执行根规范「API 与版本事实」的证据要求。
- 分别记录两组合的核实、编译及实测结果；未验证不得写成通过，一侧成功不得称“双加载器已兼容”。

## 配置快照

| 项目 | 当前值/来源 |
| --- | --- |
| MC / 外壳 | `1.20.1`；`fabric/` + `forge/` |
| Java 目标/运行 | 17；两侧 `build.gradle` 的 `targetJavaVersion` |
| Gradle daemon | JDK 21；`gradle.properties` 的 `org.gradle.java.home` |
| Fabric Loader 开发 / 下限 | `0.19.3` / `0.18.2` |
| Fabric API | `0.92.11+1.20.1` |
| Forge 开发 / 下限 | `1.20.1-47.4.0` / `47.0.0` |
| FLK / KFF | `1.13.12+kotlin.2.4.0` / `4.12.0` |
| Kotlin 编译器 / 语言与 API / stdlib | `2.1.20` / `1.8` / `1.8.22`；仓库根 `gradle/kotlin-support.gradle` |
| Fabric / Forge 插件 | `fabric-loom 1.17.20` / `net.neoforged.moddev.legacyforge 2.0.144` |

## 此车道不可照搬的地方

- **这是 Forge，不是 NeoForge**：保留 `net.minecraftforge.*` 外壳与 `META-INF/mods.toml`；插件名含 neoforged 不改变运行加载器身份。不回退到 ForgeGradle 6 模板。
- 两侧源码统一写 Mojang 官方名；Fabric 使用 `loom.officialMojangMappings()`，发布需重映射。不能直接使用 Yarn 或 26.x 非重映射配置。
- daemon 为 21 不代表游戏目标可抬到 21；保留 Java 17 编译/toolchain。批量脚本的启动器配置与 daemon 不同，手动执行也要分清。
- 此树是全仓共享 Java/Kotlin 的最低车道，不能通过提高语言/API 或运行时依赖来“修好共享层编译”。
- GUI/实体绘制和输入采用此版 API；不要复制事件 record、extract/submit 或 SDL 实现。已有 compat 将宿主输入转换为共享层约定。
- 世界歌词相关 protected 访问通过现有 AW/AT 处理；改 Fabric access widener 时检查 Forge access transformer 的对等能力与挂载。
- Forge dev 的类路径/模块与最终 JarJar 产物是不同路径；改第三方打包必须同时检查 runClient 和最终 jar，防止 split package。
- 可选 ModernUI 在 `external/modernui/`，不是默认 H 设置中心；不能搬进共享主体。`skipModernUi` 仅是有意跳过该可选适配，不是完整车道验收。

## 改动与验证

业务语义放共享根；本树只保留登记过的 compat、入口、Mixin 与版本测试。修改本树差异不要求伪造 26.3 改动，但要判断其他六树是否同样受影响。

从仓库根 PowerShell：

```powershell
.\tools\build-all.ps1 -Versions '1.20.1 recode' -Task ':fabric:test' -KeepOldJars
.\tools\build-all.ps1 -Versions '1.20.1 recode'
```

在本树目录：

```powershell
.\gradlew.bat :fabric:runClient
.\gradlew.bat :forge:runClient
.\gradlew.bat :fabric:kotlinSmokeTest :forge:kotlinSmokeTest --no-parallel --max-workers=1
```

共享改动仍须根规范规定的七树验证；Mixin、输入、GUI/GL、网络或打包变更补相应双加载器运行验证，不能只看编译成功。
