# AGENTS.md —— MuoniumPlayer 仓库开发规矩

给在本仓库工作的 AI 助手与新人。这里是**最高频的全局规矩**；子系统的深水区约定在
`docs/实现约定-*.md`（内部文档，不公开），动某个子系统前先找对应那份，**与本文冲突时以更具体的实现约定为准**。

---

## 仓库一句话

MuoniumPlayer：多版本 Minecraft **纯客户端**音乐 mod（网易云 / QQ / 酷狗 / 汽水 / YouTube / Spotify / Apple…）。
五棵版本树 × 各 2 个加载器 = **10 个独立 Gradle 构建**；共享代码只有一份，改一处五树生效。

mod id 是 `muonium`。

## 目录地图

| 路径 | 是什么 | 动不动 |
| --- | --- | --- |
| `muonium-common/` | 五树共享主体（源码 ~830 + 资源 + 测试 ~110），由各树 build.gradle 挂载，**不单独构建** | ✅ 守铁律 |
| `1.20.1 recode/` | 版本树（Fabric + Forge）。目录名带空格，shell 里记得引号 | ✅ |
| `1.21.1/` `1.21.11 recode/` | 版本树（Fabric + NeoForge） | ✅ |
| `26.2/` `26.3/` | 版本树（Fabric + NeoForge）。**26.3 是主开发树** | ✅ |
| `<树>/src/main/java/…/` | 树内层：入口 `MuoniumPlayerClient`、`mixin/`、`compat/`（版本差异层） | ✅ |
| `<树>/fabric/` `<树>/forge|neoforge/` | 加载器外壳子工程 | ✅ |
| `mpparty-common/` | 一起听客户端共用源码，同 muonium-common 方式挂载 | ✅ |
| `mpserver-core/` | 服务端仓 core 的**只读镜像**（`tools/sync-mpserver-core.ps1` 单向同步） | ⛔ 手改会被覆盖 |
| `tools/` | 共享搬运 / 构建 / 版本号 / 同步脚本 | ✅ |
| `docs/` | 内部文档（实现约定、方案、审查报告），**gitignore，不公开** | ✅ 新约定往这放 |
| `.zcode/` | 会话产物（plans 等），非权威文档 | 随意 |
| `dist/` `交付/` | 构建产物与发版交付快照（gitignore） | 别提交 |
| `api-enhanced/` `gamdl/` `go-music-dl/` | 伴生/外部工程，各有人家自己的 README / AGENTS.md | 以人家为准 |
| `光影/` `shader-compat-work/` | 光影测试素材与适配工作区 | 谨慎 |
| `_mcref/` `_folia_ref/` | MC 反编译参考（`classes-26.3.txt` 等）与 Folia 参考检出版 | ⛔ 只读 |
| `_trash/` `.recover-backup/` `.scratch/` `_scratch/` | 垃圾与快照 | ⛔ 别碰别提交 |

## 铁律（违反即返工）

1. **共享层零加载器符号**：`muonium-common` 不许 import `net.fabricmc` / `net.neoforged` / `net.minecraftforge` / `cpw.mods`。
2. **版本差异不回搬文件**：需要版本差异时，在五树 `compat/` 各写**同名同签名**方法，共享层只调这个名字。不许把共享文件整个搬回树里。
3. **改共享层 = 改五树**：改完必须跑五树测试：`.\tools\build-all.ps1 -Task ':fabric:test'`。
4. **文件归属只有工具说了算**：新文件去留走 `python tools\share-trees.py classify / apply / verify`；
   `SHARE-MANIFEST.md` 是生成物（统计数字不许手改）。名单外的文件出现在树内 `src/main/java` 会被
   `SharedLayoutStaticTest` 钉红——修复靠 share-trees 流程，不是删测试。
5. **主开发在 26.3**：新功能先在 26.3 写好，再往回打差异到其余四树；不反向。
6. **版本号单入口**：只许 `bump-version.bat`（`tools/bump-version.ps1`），不许徒手改散落的版本号
   （5 树 × 3 处 + 共享 `MuoniumPlayerExtension.VERSION`，`UpdateCheckStaticTest` 钉着）。
7. **资源按字节走**：字体 / 贴图 / dll 不许「读成字符串再写回去」，会把字节撑坏。

## 车道纪律

| 树 | MC | 目标 Java | 加载器 | 备注 |
| --- | --- | --- | --- | --- |
| `1.20.1 recode` | 1.20.1 | 17 | Fabric + Forge | Forge 保持 `mods.toml` + ForgeGradle 6 模式 |
| `1.21.1` | 1.21.1 | 21 | Fabric + NeoForge | |
| `1.21.11 recode` | 1.21.11 | 21 | Fabric + NeoForge | |
| `26.2` / `26.3` | 26.x | 25 | Fabric + NeoForge | 官方 jar 无混淆、无 `client_mappings`；源码直接官方名，Loom **不重映射** |

- 五棵树是**五个独立 Gradle 构建**（JDK / 映射 / 插件各不同），禁止合并成一个多项目构建；
  守护进程 JDK 已按树钉死在 `org.gradle.java.home`（1.20.1~1.21.11 → ms-21，26.x → temurin-25）。
- Fabric 全树用 `loom.officialMojangMappings()`：**写 mojmap 名，别写 Yarn 名**。
- `mixin/` 注入点本就各树一份（manifest 里的 ONLY），别试图共享。

## 构建与测试

```powershell
.\build-all.bat                                          # 五树全量 → dist/
.\build-all.bat -Versions '26.3' -Task ':fabric:test'    # 单树跑测试
.\build-all.bat -Versions '1.21.1','26.2' -Offline       # 指定树
cd '26.3'; .\gradlew :fabric:runClient                   # 单树跑客户端
python tools\share-trees.py classify                     # 共享层体检（只读，随时可跑）
.\tools\diff-shared-java.ps1                             # 树内版本差异层逐文件核对（基准 26.3）
```

已调好的坑，**改构建前先读对应 gradle.properties / build.gradle 的头注释**（现场记录都在），别凭经验重新调参：

- repo.maven.apache.org 本机不可达 → 插件与依赖走阿里云镜像（settings.gradle 里有记录）。
- fabric-loom 要求守护进程 JVM ≥ 21 → 各树已钉 `org.gradle.java.home`。
- 26.x 无 `client_mappings` → 别找 yarn_mappings，别配重映射任务。
- Apple license 传输走系统 curl（WAF 拒 JVM TLS 栈），见 git log `fix(apple)` 系列。

## 提交

- conventional commits + 中文主题，主题讲清「为什么」：`fix(apple): license 传输走系统 curl —— WAF 拒 JVM TLS 栈的硬约束落地`。
- 一次提交只做一件事：共享层改动与树内移植分开提交。
- 别提交：`dist/`、`交付/`、日志（`_build_all.log` 等）、`_trash/`、`.recover-backup/`、`.scratch/`。

## 文档体系

- `docs/实现约定-<主题>.md` —— 子系统权威约定（AUTOMIX 音频线程、GUI 渲染与文字显示、ViPER/JamesDSP、
  公告系统、更新检查、版本号管理、汽水后端…）。**动子系统前先读对应那份。**
- `docs/维护总纲-*.md` —— 线上资产（官网 / 分发仓库 / 汽水后端）维护总纲。
- `docs/审查报告-*.md` —— 改动自查基准。
- 新方案 / 新约定：写到 `docs/`，沿用「方案- / 实现约定- / 审查报告-」命名前缀。
- 本仓库是**私有 monorepo**；公开发布走 `tools/make-opensource.py` 出滤净副本，别把内部文档路径写进会公开的代码注释里。

## AI 助手补充（skill 用法）

- 加载 `minecraft-modding` skill：
  - **照用**：版本车道纪律、构建命令、物理客户端/服务端分离、`Identifier`/`ResourceLocation` 规范。
  - **跳过**：方块/物品注册、datagen、战利品表那套 checklist（本 mod 基本不注册方块物品）。
  - **打折**：其 Fabric 参考是 Yarn 名的 1.21.x 示例，名称以本仓库 mojmap 代码为准。
  - **忽略**：Architectury 章节 —— 本仓库自有共享方案（muonium-common 挂载 + compat 垫片），已论证不迁移。
- 拿不准 API 名时：先看同树既有 `compat/` 代码，再查 `_mcref/` 反编译参考，最后查 loader 官方文档。
- 探查类任务先翻 `docs/` 对应实现约定，避免重走已知弯路。
