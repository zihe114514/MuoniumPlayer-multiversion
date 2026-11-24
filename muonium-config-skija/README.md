# MuoniumConfig Skija

原设置中心的 Skija 迁移与验收工程。界面源码独立放在本目录，配置、预设、音乐 HUD、字体和渲染上下文直接连接 MuoniumPlayer。工程不依赖 Compose/Skiko，不打包另一份 Skija 或 Kotlin。

人工验收入口、自动检查记录与已知实现差异见 [人工验收交接](docs/人工验收交接.md)。当前实现与验证的事实分别记录在 docs/ 下。2026-11-24 的打开界面崩溃修复见 [主包字体接口兼容修复](docs/修复-主包字体接口兼容.md)。**可构建不等于逐页 1:1 视觉和游戏运行已经验收通过**。游戏验收完成后再将本目录的业务/页面源码接入主项目共享根；版本触点仍留每条车道，不复制十四份业务。

## 构建与验证

在本目录 PowerShell：

~~~powershell
.\verify.ps1                         # Java 17 的中立行为/资源/迁移测试
.\build.ps1 -Versions '26.3' -Loaders 'fabric' -HostJar '<实际 MuoniumPlayer 主包路径>' -Offline
.\src\test\java\com\muoniumplayer\configskija\text\run.ps1 -HostJar '<实际 MuoniumPlayer 主包路径>'
.\build.ps1 -Versions '26.3' -Offline # 两加载器、实际主项目依赖、独立产物
.\build.ps1 -Versions '1.20.1 recode','1.21.1','1.21.10','1.21.11 recode','26.1.2','26.2','26.3' -Offline
.\src\test\java\com\muoniumplayer\configskija\text\run.ps1
.\src\test\java\com\muoniumplayer\configskija\library\run-host-library-tests.ps1
.\src\test\java\com\muoniumplayer\configskija\appearance\run.ps1 -ComposeReference
node tools/audit-source.mjs
node tools/check-parity.mjs --json
~~~

测试日志、生成夹具、截图与产物只写本目录 build/ 和 dist/，不提交。最后一条审计在人工/游戏证据尚未齐全时返回非零，保留差异而不是把未验收项刷为通过。

- dist/：可安装的映射产物，文件名为 MuoniumConfig-Skija-<MC>-<loader>-0.1.0.jar。
- build/devdist/：开发命名空间包，仅用于对应主项目 dev run。旧 Fabric/Forge 不可把 dev 包装进生产实例。
- 原版主包、原附属仓、主项目 dist 与线上下载清单由本工程构建保持原状。

## 独立验收入口

验收包与对应车道的 MuoniumPlayer 同装，沿用主项目 H 绑定；用户自定义的当前按键仍由主项目管理。验收包在现有 OneConfigHudBridge 打开路径接管，并暂停 Compose 伴生包自动下载。设置数据仍写原主项目配置。可用 JVM 参数 -Dmuonium.config.skija.disabled=true 临时恢复原打开路径。

原 MuoniumConfig MOD 可以保留以做对照；独立新包不加载其 Compose 类。切换两套界面时通过上述开关分别启动，并核实日志和包指纹。不要将同一套 preview 的两个不同版本同时放进 mods。

## 生命周期和数据

- SkijaConfigService 是一个客户端常驻会话。新增 text/date/time/icon HUD 在冷启动即恢复，关闭设置页后继续绘制。
- SkijaConfigScreen 使用主项目 GuiScreen 的版本适配、Skija 帧及 IME，绘制只加入既有主渲染上下文。
- UI 状态使用 config/muonium/skija-config/ui.properties。声音、主题、界面偏好、原常用树、Profile 和 HUD 均按实际宿主契约接线。
- 旧设置迁移只读已核实的数据路径；未知类型和位置 schema 留在原文件并记录诊断，不删除旧配置。
- 本地 Profile 与可分享模板分开；Profile/模板后台文件操作回客户端线程应用，过期回调丢弃。
- 所有新增 UI/音频 IO 均在 worker；游戏音频使用原 SoundManager，HUD/字体/GL 资源在既有渲染生命周期创建和释放。

## 验收资料

| 文档 | 范围 |
| --- | --- |
| docs/feature-inventory.md | 原页面、子界面、交互、动画及未覆盖项 |
| docs/settings-parity.md | 全部设置 visualizer、文本、菜单和搜索 |
| docs/appearance-parity.md | 四主题、50项偏好、颜色和动画参数 |
| docs/hud-parity.md | 编辑器、层级、锚定、合并、教学与运行期 |
| docs/library-parity.md | 模组、Profile、模板、键位、Credits |
| docs/search-parity.md | 原模糊算法、异步 provider、语料生命周期 |
| docs/migration-parity.md | 旧配置 allowlist、位置 schema 和迁移诊断 |
| docs/sound-notifications-parity.md | 原事件、九个声音、toast/通知中心 |
| docs/font-parity.md | 原字体、热切、CJK回退与资源拥有者 |
| docs/avatar-parity.md | 真实玩家头像、逐车道皮肤契约与异步生命周期 |

原源码和资源授权见 reference/UPSTREAM-LICENSE.txt；发布包同时保留 META-INF/licenses/OneConfig-LICENSE.txt。移植内容的对应许可保持独立，不将源授权声明替换为主项目许可。
