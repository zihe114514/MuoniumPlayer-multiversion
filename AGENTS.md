# MuoniumPlayer 仓库开发规范

> 适用：本私有 monorepo 的开发者与 AI 助手。最后核对：2026-11-11。
> 本文规定仓库级边界、工作流程与验收要求；不是用户安装指南，也不是 Minecraft 通用模板。

## 1. 先读规则，再改代码

1. 阅读本文，以及工作目录下更具体的 `AGENTS.md`。
2. 涉及共享主体时读 `muonium-common/AGENTS.md`；涉及一起听时读 `mpparty-common/AGENTS.md`。
3. 动子系统前找 `docs/实现约定-<主题>.md`；线上资产另读 `docs/维护总纲-*.md`。
4. 用目标树的配置、源码和测试确认事实，再开始改动；不能把旧注释、方案或参考仓当成当前实现。

**规则分工**：本文管全局；目录规范补充局部约束；子系统实现约定管算法、线程、接口及生命周期。局部规则不能自行豁免共享中立性、版本基线、镜像只读等全局底线。发现矛盾时先核对实现与历史，修正文档，不静默挑一份执行。

**事实来源**：版本与依赖看各树 `gradle.properties`；工程/插件看 `settings.gradle`、加载器 `build.gradle`；Kotlin 基线看 `gradle/kotlin-support.gradle`；源码归属看实际源根和 `SharedLayoutStaticTest`。更改这些事实时同步更新对应规范。本文不维护源码数量、测试数量等易失效统计。

### 1.1 API 与版本事实：禁止猜测，禁止臆造（强制）

**独立核实单位是「Minecraft 精确版本 × 加载器及其精确版本 × 实际解析的 API/依赖版本 × 映射/访问配置」**，不是“同一版本树”“Fabric 系”“1.21.x”或“26.x”。同树两个加载器也可能修改原版类、访问权限、事件和运行流程，不能相互代证。

1. **禁止编造 API**：不凭记忆、命名习惯、补全建议或通用示例猜类名、包名、方法、字段、构造器、事件、注册入口、Gradle 任务及 Mixin 目标；不先写一个“应该存在”的调用再等编译器兜底。
2. **先确认目标，再写调用**：先看该树配置及目标加载器的实际依赖，再找同组合现有调用；新增、替换或移植的 API 必须核实对应依赖源码/字节码中的真实声明。旧代码只能定位线索，不能单独证明 API 当前可用或行为正确。
3. **核实完整契约**：包/所有者、参数与返回类型、构造器、static/实例、可见性、泛型和所需访问配置都要匹配；事件还须确认注册位置、总线、阶段、执行线程、回调顺序和可用上下文。名称相同不代表签名或行为相同。
4. **资料必须匹配组合**：优先目标构建实际解析的源码/JAR；反编译参考必须确认版本、映射和加载器补丁。官方文档也要选对应版本；其他版本、其他加载器、最新版网页、skill 示例和旧注释都不能直接作为当前兼容证据。
5. **不得推断复用**：相邻 MC 版本、相同 Java 目标、同属 Fabric/NeoForge、相同 Mojang 名称或文本相同，均不能证明可复用。26.3 的开发基准地位不赋予其 API 跨版本有效性。
6. **移植是重新适配，不是复制替换**：对实际受影响的每个组合单独核实调用和生命周期。禁止只改 import/版本号后整套复制 compat、事件注册、网络通道、GUI/输入或渲染逻辑。
7. **Mixin/访问配置逐组合确认**：查真实目标、完整描述符、注入位置、局部变量/捕获条件、映射命名空间和加载配置；确认哪个加载器实际加载它。禁止根据相邻版本生成描述符，或把 Mixin 类存在/编译成功当成注入已生效。
8. **不确定就停在不确定处**：缺依赖、找不到准确声明、资料冲突或未验证行为时，明确写“未确认”及缺少的证据；不能改口称“肯定不存在/已删除/完全等价”，不能臆造替代 API、补空实现或吞异常来假装适配成功。
9. **证据能定位、结论不过界**：API 变更说明记录适用组合、声明所在文件/类及签名；时序/线程结论附调用链或运行证据。仅对本次涉及的触点核实，不为纯规范任务扩展成全仓审计。

### 1.2 验证结论不能跨级、跨组合

- **源码有调用**只证明仓库当前这样写；**依赖声明已核实**只证明存在与静态契约；**目标编译通过**不证明事件触发、Mixin 命中或运行语义；**指定场景实测通过**不等于全部场景保证。
- 一个组合通过不代表另一组合通过；开发依赖版本通过也不证明元数据声明的最低版本通过。未测组合明确列出，不能汇总成“全版本兼容”。
- 文档里的配置快照和 API 名称不是永久白名单；依赖或配置变化后重新核实。只有实质证据才能写“已确认”，不写无法证明的“万无一失”。

## 2. 项目与构建模型

MuoniumPlayer 是 Minecraft **纯客户端**音乐 mod，mod id 为 `muonium`，支持多音源、播放器/HUD、音效、AUTOMIX、世界歌词与一起听等功能。

- **7 个独立 Gradle 工程**，每棵版本树有自己的 wrapper、settings、依赖与 JDK 配置。
- **14 个加载器子工程/构建目标**：Fabric 7、NeoForge 6、Forge 1。不是 14 个独立 Gradle 工程。
- 每棵树内部是根聚合工程 + 两个加载器子工程；禁止把七树合成一个大 Gradle 工程。
- `muonium-common`、`mpparty-common` 和 `mpserver-core` 是挂载的源码目录，**不是可独立构建的 Gradle 模块**。
- 纯客户端不等于禁止访问集成服务器：局域网一起听会使用内嵌房间引擎；这不意味着主 mod 支持安装到独立服务端。
- 主开发/新功能验证树是 **26.3**；共享业务直接改共享根，不先复制到 26.3。已有旧版本专属缺陷可在对应树修复，同时检查其他树是否受影响。

## 3. 七条版本车道

以下是编译/运行基线，不是依赖自动升级建议。开发依赖与发布最低依赖是两组值，不得混用。

| 版本目录 | MC | Java 目标/游戏运行 | Gradle daemon JDK | 加载器 | Fabric 映射模式 |
| --- | --- | --- | --- | --- | --- |
| `1.20.1 recode/` | 1.20.1 | 17 | 21 | Fabric + Forge | 官方 Mojang 映射，需重映射 |
| `1.21.1/` | 1.21.1 | 21 | 21 | Fabric + NeoForge | 官方 Mojang 映射，需重映射 |
| `1.21.10/` | 1.21.10 | 21 | 21 | Fabric + NeoForge | 官方 Mojang 映射，需重映射 |
| `1.21.11 recode/` | 1.21.11 | 21 | 21 | Fabric + NeoForge | 官方 Mojang 映射，需重映射 |
| `26.1.2/` | 26.1.2 | 25 | 25 | Fabric + NeoForge | 官方未混淆名称，不重映射 |
| `26.2/` | 26.2 | 25 | 25 | Fabric + NeoForge | 官方未混淆名称，不重映射 |
| `26.3/` | 26.3 | 25 | 25 | Fabric + NeoForge | 官方未混淆名称，不重映射 |

- 七树都写 **Mojang 官方名**，不要将 Yarn 示例直接粘贴进源码。
- 1.20.1 Forge 当前使用 **ModDevGradle `net.neoforged.moddev.legacyforge`**，仍是 Forge，保留 `META-INF/mods.toml`；不是 ForgeGradle 6，也不是 NeoForge。
- 1.21.x Fabric 使用 `fabric-loom` 与 `loom.officialMojangMappings()`；1.20.1 同此映射路线。
- 26.x Fabric 使用 `net.fabricmc.fabric-loom` 的非重映射路线；没有 `client_mappings`，不要补 Yarn/intermediary 或照搬旧树 remap 配置。
- NeoForge 使用 ModDevGradle；26.3 当前是 beta 依赖车道，不把它写成稳定版。
- GUI、输入、实体渲染、窗口/GL 后端不共享一个版本分界。逐树核对 API/注入描述符，禁止按“1.21.x 一套、26.x 一套”直接覆盖文件。
- JDK 启动器、daemon 和 toolchain 是三个概念。1.20.1 的 Java 17 目标不能因 daemon 是 21 而被抬高；本机路径以配置和脚本为准，不当成可移植公共路径。

各树 `AGENTS.md` 给出该车道当前依赖快照、特有风险和本地命令。

## 4. 目录与改动权限

| 路径 | 职责与约束 |
| --- | --- |
| `muonium-common/` | 七树主体 Java/Kotlin、资源及共用测试；版本/加载器中立，见目录规范 |
| `<树>/src/main/{java,kotlin}/` | 两加载器共用的本树版本差异层；不是第二份业务主体 |
| `<树>/<加载器>/src/main/` | 加载器入口、事件、网络、元数据、AW/AT 等宿主配置 |
| `<树>/src/main/resources/` | 本树资源，现有语言文件等；不要与共享/外壳资源重复覆盖 |
| `<树>/src/test/{java,kotlin}/` | 版本特有测试；共用测试放共享根 |
| `mpparty-common/` | 一起听客户端、直连与局域网集成；协议/宿主边界见目录规范 |
| `mpserver-core/` | 服务端 core **只读镜像**，只能走 `tools/sync-mpserver-core.ps1` 单向同步 |
| `gradle/` | 受版本控制的共用构建支持与 Kotlin smoke；不是另一套 Gradle 根工程 |
| `<树>/external/modernui/` | 既有可选 ModernUI 适配；不可移入共享主体，亦不等于 H 设置中心 |
| `tools/` | 本机维护工具；当前被 gitignore，改动不能默认算作已纳入版本控制 |
| `docs/` | 内部实现约定、维护总纲、方案与审查报告；当前被 gitignore，不公开 |
| `.zcode/` 等会话目录 | 临时计划/会话产物，不是权威规范 |
| `dist/`、`交付/`、`build/`、`run/` | 产物及开发运行数据；不提交、不凭旧包推断本次构建成功 |
| `api-enhanced/`、`gamdl/`、`go-music-dl/` | 伴生/外部工程；先读其自身规范，不连带重构 |
| `_mcref/`、`_folia_ref/` | 只读反编译/参考资料，不修改，不当成当前可编译源码 |
| `_trash/`、`.recover-backup/`、`.scratch/`、`_scratch/` | 历史垃圾/快照；不清理、不提交，不作为规范依赖 |
| `光影/`、`shader-compat-work/` | 测试素材与适配工作区；只改任务明确需要的部分 |

带空格的目录必须加引号。任何资源移动都按字节复制；字体、贴图、原生库不经文本解码再写回。

## 5. 代码分层的硬规则

1. **共享层零加载器符号**：禁止 `net.fabricmc.*`、`net.neoforged.*`、`net.minecraftforge.*`、`cpw.mods.*` 的 import、全限定调用及 Kotlin alias。不能用反射绕过边界。
2. **版本差异收敛到 compat/宿主桥**：七树提供相同的共享调用契约，内部适配真实 API；共享层不堆 MC 版本判断。
3. **一份归属**：共享根、本树根、外壳不得定义同一个类或重复资源；Java/Kotlin 跨语言同名也不允许。
4. **只适配，不复制业务**：不能为解决一个 MC 方法差异，把整份播放器/渲染/设置逻辑搬回七树。
5. **遗留留守不是新模板**：现存 `HudConfig`、部分 Screen 等树内副本须逐树评估；不要由此推导“所有设置和 GUI 都应各树复制”。
6. **Mixin 默认树内维护**：注入目标、阶段、签名、元数据逐树核实；文本相同不代表注入契约相同。新增项同时更新对应 mixin 配置和归属守卫。
7. **最老支持车道决定共享语言/API 基线**：共享 Java 不超过 Java 17；共享 Kotlin 语言/API 不超过 1.8、标准库不超过 1.8.22，并遵守 Java 17 API 上限。26.3 编译通过不代表七树通过。
8. 保持现有 Java 入口和 Mixin；Kotlin 是渐进增量，不以新语言接入为由重写既有业务、引入协程或更换线程模型。

共享文件新增、归属变更、`SharedLayoutStaticTest` 留守表和历史迁移工具的正确用法见 `muonium-common/AGENTS.md`。

## 6. 子系统契约与外部仓库

### 6.1 音频、渲染、网络

- 音频线程不做网络、磁盘、模型加载或 UI 操作；AUTOMIX 端口直写、参数平滑和生命周期写入权沿用对应实现约定。
- 网络/文件/解码任务不堵客户端/渲染线程；异步结果回到正确线程，并处理切歌、关屏和退出后的过期回调。
- 模组自有播放器/HUD 沿用 Skija 栈；原版宿主提示按现有桥接边界处理，不强行把所有宿主 GUI 改成同一种 API。Compose 设置中心在外仓。
- GL/Skija 资源创建、状态交接和释放保持同一生命周期；变更缓存必须说明失效条件、资源拥有者及退出/重载路径。
- 一起听的播放控制走统一拦截/权限路径，不绕过房间控制权。协议或缓存格式变化要考虑旧数据/旧端兼容。
- 具体行为查相应实现约定与测试；以上不是替代子系统文档的简化算法。

### 6.2 H 打开的设置中心：MuoniumConfig

Compose 界面源码位于独立仓库 `E:\oneconfig lite\MuoniumConfig-src`。本仓库负责设置数据、反射契约、下载/更新与宿主提示，不在这里重做 Compose 页面。

- 默认 UI 已是 `mconfig`；以本树 `HudConfig.HUD_EDITOR_UI_*` 及加载逻辑为准，旧文档的 `builtin` 默认说明已失效。
- 改外仓前读其 `AGENTS.md`（如有）、`docs/MAINTENANCE.md`、`docs/PITFALLS.md`。
- 反射桥重点：`OneConfigHudBridge`、`MuoniumHudProperties`、`MuoniumFavorites`、`MuoniumHudProviders`、`MuoniumFontBridge`、`MuoniumConfigDownload`。反射使用的类名、方法、字段、类型与可见性都是接口，不能只看编译是否通过。
- 改设置 schema/default/key 时检查七树、持久化兼容、外仓消费方及契约测试；不能只改一个树内 `HudConfig`。
- 外仓构建后必须同步开发包到**实际测试车道/加载器**，再确认 `run/mods` 中加载的是新包。以下仅以 1.21.1 为例：

```powershell
# 在 MuoniumConfig-src 内执行；其他车道先查外仓支持的 lane 名
python tools/build-all.py --lane 1.21.1
python tools/sync-dev-mods.py --lane 1.21.1
python tools/check-music-contract.py --jar <MuoniumPlayer产物>
```

## 7. 标准开发流程

1. **定范围**：记录受影响的共享根、版本树、加载器、外仓和线程/资源边界；先看工作区已有改动。
2. **查依据**：读对应目录规范、实现约定及测试；按第 1.1 节先锁定目标组合，用现有代码定位，再核实实际依赖声明。参考反编译与官方资料须确认版本/加载器/映射匹配，不能按名字猜调用。
3. **实现**：版本中立逻辑直接写共享根；26.3 先验证新功能的宿主适配，其余六树按各自加载器重新核实与适配，不是直接复制。旧树专属修复不必伪造 26.3 差异。
4. **控制差异**：按受影响的「MC 精确版本 × 加载器」列出已实现/不适用/显式降级/未确认及依据；不能把“还没移植”写成“不支持”，也不能将一侧结果代表整树。
5. **验证**：按下节选择范围；失败要定位原因，不能删守卫、放宽断言或静默跳过某个 loader。
6. **交付**：说明改动、验证命令和结果、未验证范围及已知问题；更新对应规范。不擅自提交、推送或发布。

## 8. 构建、测试与验收

### 8.1 PowerShell 入口

仓库根批量命令推荐直接调 `.ps1`，避免 `.bat` 转发时数组参数被不同 shell 误解。

```powershell
# 共享业务/资源/测试改动：七树共用测试，保留现有 dist 包
.\tools\build-all.ps1 -Task ':fabric:test' -KeepOldJars

# 仅验证本树；依任务扩大范围，不以单树替代共享层七树验收
.\tools\build-all.ps1 -Versions '26.3' -Task ':fabric:test' -KeepOldJars

# 全量构建：七树、两加载器，成品集中到 dist/
.\tools\build-all.ps1

# 指定车道的完整双加载器构建
.\tools\build-all.ps1 -Versions '1.21.1','26.2'

# 版本差异报告（不同不自动等于错误，需人工解释）
.\tools\diff-shared-java.ps1 -Reference '26.3'

# 只读版本号盘点；还需人工核对结果，不把输出表当失败即报错的测试
.\tools\bump-version.ps1 -CheckOnly
```

**副作用先看清**：`build-all.ps1` 默认清理输出目录旧的 `MuoniumPlayer-*.jar`，即使任务只是测试；测试使用 `-KeepOldJars`。完整构建的 `collectDist` 仍会替换对应车道的旧包，`-KeepOldJars` 不是产物备份开关。脚本还会写日志；保留需留存的日志时指定新的 `-LogFile`。`-Offline` 仅在依赖缓存齐全时使用。

单树命令在该树目录执行，手动运行时核对启动器 JDK、daemon 和 toolchain：

```powershell
.\gradlew.bat :fabric:test
.\gradlew.bat :fabric:build :neoforge:build       # 1.20.1 改为 :forge:build
.\gradlew.bat :fabric:runClient                  # 或 :neoforge:runClient / :forge:runClient
.\gradlew.bat :fabric:kotlinSmokeTest :neoforge:kotlinSmokeTest --no-parallel --max-workers=1
```

### 8.2 最低验收范围

| 改动类型 | 至少验证 |
| --- | --- |
| 仅 Markdown 规范 | 路径/命令/版本矩阵与源码配置对照、链接及 `git diff --check`；无需为纯文档重跑游戏构建 |
| 共享业务、资源或共用测试 | 七树 `:fabric:test`；共享生产代码/依赖改动还需七树双加载器构建 |
| 单树 compat/入口/Mixin | 该树 `:fabric:test` + 两加载器构建；涉及入口/渲染/输入/网络时补对应客户端实测 |
| 共用构建、打包、Kotlin 接线 | 七树测试 + 14 目标构建；涉及 Kotlin 配置时跑 `python -I tools\kotlin-support-tests.py` 并检查双侧 smoke |
| MuoniumConfig 反射/设置契约 | 覆盖受影响车道，外仓契约校验 + 新包同步 + 游戏内打开/HUD 实测 |

Fabric 子工程挂载七树共用 JUnit 测试；Forge/NeoForge 的 `test` 任务成功不等于跑过这套测试。`build`/smoke 成功也不等于 Mixin 注入和游戏运行成功。

UI/音频/网络改动按影响补测：首次启动与重进、切歌/暂停/恢复、切屏/失焦、resize/GUI scale、资源释放、无网络/缺资源降级、相关光影/渲染器。性能结论必须附场景与测量，不以“减少几行代码”代替证据。

### 8.3 构建故障不能靠猜

先读本树 `gradle.properties`、`settings.gradle`、目标 `build.gradle` 的现场注释。镜像/代理/TLS/JDK 配置是本机事实，不是跨机器通用规则。先分清依赖下载失败、插件配置失败、编译失败、测试失败和运行失败；不随手升级依赖、删除缓存或恢复错误旧模板来碰运气。

## 9. 依赖、版本号、产物与提交

- 新增第三方库先判断 MC 是否自带、JNI/模块/服务资源是否允许 relocation。沿用既有 include/JarJar/relocate 分类；不要重新打包 `kotlin.*` 或 MC 自带库。
- Fabric 与另一 loader 的库集合/能力应对齐，但实现方式无需强行相同。改打包后检查**最终发布 jar**；开发 classpath 正常不是发布成功。
- mod 版本只通过 `bump-version.bat` / `tools/bump-version.ps1` 修改。当前每树三处版本落点加共享 `MuoniumPlayerExtension.VERSION`，不要手工维护散落常量或 UI 字面量。
- 子集热修前检查脚本实际逻辑：当前按 `Trees.Count >= 5` 自动同步 Extension，**不是按是否恰好覆盖全部七树判断**。不能照旧帮助文字假定子集一定不动共享版本。
- 发布配置区分 `loader_version` / `loader_min_version`、`neoforge_version` / `neoforge_min_version` 等。降低声明下限必须有 API/前置与运行证据，不等于改一个元数据字符串。
- 发布 jar 为 `MuoniumPlayer-<mc>-<loader>-<mod版本>.jar`；不把 sources、dev、旧版本或 embedded 临时包当主包。
- 提交用 Conventional Commits + 中文主题，说明目的/原因。共享业务与树内适配尽量拆成可审查的完整提交；依赖关系和整批验收必须说清，不制造“单个提交已全树通过”的假象。
- 不提交产物、日志、运行期配置、账号凭据、缓存与快照。提交前看 `git status` 和实际 diff，不因文件被忽略而假定其改动已保存到 Git。
- 未明确授权不执行 commit/push/release、线上更新清单或部署；发布前读 release 技能与线上维护约定。

## 10. 文档治理与 AI 使用

- **可随仓库传递的规范**：根/目录 `AGENTS.md`、模块 `README.md`。全局规则只在根定义；子目录只写更严格边界、版本差异和必要快照。
- **内部权威细节**：`docs/实现约定-*.md`；新方案用 `方案-`，审查证据用 `审查报告-`。方案/审查报告不会自动成为现行实现约定。
- 历史 `SHARE-MANIFEST.md` 是迁移生成记录，不是当前完整目录清单；不手改统计数字，不重跑迁移来“刷新规范”。现状守卫见共享层规范。
- 新约定写清适用范围、核对日期、事实来源、验证方式和例外；架构/车道变化时同时更新规范。旧文档保留有效技术结论，过期处标明，不全库机械替换数字。
- 本仓库是私有 monorepo；公开源码走 `tools/make-opensource.py` 的过滤流程。不要把内部文档路径、凭据或机器信息新增到公开源码注释/用户指南。
- AI 修改 mod 代码先加载 `minecraft-modding`；仅采用匹配本仓车道的客户端/构建/API 规则。跳过方块/物品/datagen 清单，不迁移 Architectury，不照搬 Yarn 或旧版模板。
- 报告必须区分“已修改”“静态核对通过”“构建/测试通过”“游戏实测通过”。未运行、失败和被环境阻塞都要如实写明。
