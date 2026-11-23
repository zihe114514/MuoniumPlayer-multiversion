# 源码基线与 1:1 验收清单

扫描策略核对：2026-11-24。生成入口：tools/audit-source.mjs。原仓全程只读；默认扫描和 check-parity 不写文件。

原仓 HEAD：`bdaa0eb7f0bdcbbd9d8ceee2ccd748fdcb36503b`；源码树 SHA256：`604622a8573734617129283fee2febe9eaa7b6dc801165ef1e13c5f377a5abe1`。当前原仓工作区：干净。

基线包含 1343 个仓库输入、1003 个生产源码文件、209 个资源文件；定位 464 个 UI 相关源文件中的 6915 个源码位置。字节验收资产 158 项，声音声明仅允许已核实的 oneconfig: → muoniumconfigskija: 命名空间替换。原始声明 SHA256 仍保留。数字仅是本次快照，不是完整行为已还原的证明。

## 重跑命令

在 muonium-config-skija 目录执行（Node.js 18+ 内置模块，无需 npm/Gradle）：

~~~powershell
node tools/audit-source.mjs
node tools/audit-source.mjs --json
node tools/audit-source.mjs --write
node tools/check-parity.mjs
node tools/check-parity.mjs --json
node tools/check-parity.mjs --stage implementation
node tools/audit-source.mjs --self-test
node tools/check-parity.mjs --self-test
# 换机器时显式指定原仓；--project 可指定验收工程
node tools/check-parity.mjs --source "E:/oneconfig lite/MuoniumConfig-src"
~~~

--write 仅更新 reference/source-manifest.json、reference/feature-map.json、docs/feature-inventory.md；保留同一基线的既有证据。上游漂移会把旧证据保留下来并重置为 pending；被删项目进入 retiredFeatures 等待处理。退出码：0=全部检查满足，1=存在差距，2=工具/输入错误。audit-source 普通扫描成功为 0，表示扫描完成。

## 判定边界

游戏、视觉、交互和动画的最终验收由人工负责。pending 表示证据待齐，不是失败功能计数。--stage implementation 只针对源码/资产/打包约束和已声明实现的自动行为证据判断可测试状态，完整报告仍保留人工待验收及旧成品待重建项，源侧缺资源与禁用依赖仍阻塞；它不把任何项提升为 1:1 完成，也不是生成测试包的前置门禁。

feature-map 中全部项目初始为 pending。类存在、静态核对或编译通过都不能标为 complete。实现必须列真实路径/行号/SHA256；语义案例覆盖本项全部定位点；自动化行为报告、逐组合游戏记录和原版/新实现的视觉证据必须存在且哈希匹配。报告需绑定 featureId、sourceTreeSha256、当前 implementationSnapshotSha256 和准确目标。工具验证证据的完整性与关联，不替人工确认报告事实。

普通 JAR 扫描验证已有成品的字节内容，不证明其来自当前源码、Mixin 命中或游戏行为。缺少任一组合的新鲜成品仍是缺口。主仓自带 Skija/Kotlin 的 classpath 不等于本工程打包了它们；禁止的是本工程引入 Compose/Skiko、自带第二套 Skija/native 或打包 Kotlin/loader 类。loader 引用只允许实际 src/acceptance 外壳，host 可接 Minecraft，ui/model 不可接 Minecraft/loader。

扫描器定位词法声明/调用、函数类型注解和手工锚定的语义行为；不运行 Kotlin 编译器、反射或动态路由。文件级项目默认需要人工语义复核，全部定位点留在下方；注册扩展、数据驱动页和运行期资源仍须从真实入口实测。

## 当前全量差距

检查对应的项目输入 SHA256：`6b847ef988c198cac8334a1ad704fa73308d17d437b1237d28274a3c9a7e7aec`。检查结果：仍有缺口；已核实实现/中立自动证据=5；1:1 complete=0/569，pending=569；资产一致=158/158；检查发现=625 项。

| 检查类别 | 未满足数 |
| --- | ---: |
| artifact-asset-missing | 40 |
| artifact-provenance-missing | 1 |
| artifact-target-missing | 10 |
| feature-pending | 569 |
| legacy-schema-tag-review | 1 |
| reference-oracle-classpath-review | 3 |
| test-source-contract-review | 1 |

完整逐项内容由 check-parity --json 输出；下表保留每个语义功能/源文件项目，未覆盖内容不会因限制终端显示而丢弃。

### 字节资产差距


### 架构、证据与成品检查差距

- **legacy-schema-tag-review** src/main/java/com/muoniumplayer/configskija/host/migration/LegacyMigration.java：Exact old serialized PolyColor class tag is compared as inert data; no old class loading or runtime dependency.
- **reference-oracle-classpath-review** src/test/java/com/muoniumplayer/configskija/appearance/AppearanceComposeReferenceTest.java：Test-only reflective upstream oracle: verify it is excluded from production dependency/package inputs; this is not a production UI runtime.
- **reference-oracle-classpath-review** src/test/java/com/muoniumplayer/configskija/hud/HudMotionReferenceTest.java：Test-only reflective upstream oracle: verify it is excluded from production dependency/package inputs; this is not a production UI runtime.
- **reference-oracle-classpath-review** src/test/java/com/muoniumplayer/configskija/migration/MigrationParityTest.java：Test-only reflective upstream oracle: verify it is excluded from production dependency/package inputs; this is not a production UI runtime.
- **test-source-contract-review** src/test/java/com/muoniumplayer/configskija/text/HostTextContractTest.java：Test-only contract/oracle names loader symbols without production imports; verify tests and oracle classpath remain excluded from runtime packaging.
- **artifact-provenance-missing** build/acceptance/artifact-provenance.json：No current source-bound build log/provenance. Existing JAR inspection does not prove build freshness or runtime behavior.
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-fabric-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/color/alpha.png
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-fabric-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/color/hue.png
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-fabric-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/fonts/minecraft/Minecraft-Bold.otf
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-fabric-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/fonts/minecraft/Minecraft-Regular.otf
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-fabric-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/hud/align/background.svg
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-fabric-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/hud/align/circle.svg
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-fabric-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/hud/selector_curve.svg
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-fabric-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/images/head.png
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-fabric-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/images/header.png
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-fabric-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/images/minecraft-dark.svg
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-fabric-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/images/minecraft-light.svg
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-fabric-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/images/polyglass-dark.svg
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-fabric-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/images/polyglass-light.svg
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-fabric-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/lang/en_us.json
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-fabric-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/lang/zh_cn.json
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-fabric-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/sounds.json
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-fabric-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/sounds/ui/click.ogg
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-fabric-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/sounds/ui/close.ogg
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-fabric-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/sounds/ui/open.ogg
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-fabric-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/sounds/ui/slider.ogg
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-neoforge-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/color/alpha.png
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-neoforge-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/color/hue.png
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-neoforge-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/fonts/minecraft/Minecraft-Bold.otf
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-neoforge-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/fonts/minecraft/Minecraft-Regular.otf
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-neoforge-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/hud/align/background.svg
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-neoforge-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/hud/align/circle.svg
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-neoforge-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/hud/selector_curve.svg
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-neoforge-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/images/head.png
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-neoforge-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/images/header.png
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-neoforge-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/images/minecraft-dark.svg
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-neoforge-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/images/minecraft-light.svg
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-neoforge-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/images/polyglass-dark.svg
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-neoforge-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/images/polyglass-light.svg
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-neoforge-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/lang/en_us.json
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-neoforge-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/lang/zh_cn.json
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-neoforge-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/sounds.json
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-neoforge-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/sounds/ui/click.ogg
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-neoforge-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/sounds/ui/close.ogg
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-neoforge-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/sounds/ui/open.ogg
- **artifact-asset-missing** dist/MuoniumConfig-Skija-26.3-neoforge-0.1.0-dev.jar：Source asset absent from release JAR: assets/muoniumconfigskija/sounds/ui/slider.ogg
- **artifact-target-missing** ：No release artifact inspected for 1.21.1/fabric.
- **artifact-target-missing** ：No release artifact inspected for 1.21.1/neoforge.
- **artifact-target-missing** ：No release artifact inspected for 1.21.10/fabric.
- **artifact-target-missing** ：No release artifact inspected for 1.21.10/neoforge.
- **artifact-target-missing** ：No release artifact inspected for 1.21.11/fabric.
- **artifact-target-missing** ：No release artifact inspected for 1.21.11/neoforge.
- **artifact-target-missing** ：No release artifact inspected for 26.1.2/fabric.
- **artifact-target-missing** ：No release artifact inspected for 26.1.2/neoforge.
- **artifact-target-missing** ：No release artifact inspected for 26.2/fabric.
- **artifact-target-missing** ：No release artifact inspected for 26.2/neoforge.

## 需要主助手明确分配的跨域工作

- 通知既有侧栏中心，也有游戏全局 toast：悬浮暂停、进度、按钮、已读/历史、关闭命中和渲染层级需要独立验收。
- HUD tour 共九步，含真正完成合并后才推进的交互步骤；HUD 库含分组、预览自动排版、搜索、滚动定位、点击/拖出添加。
- 位置锚定、增长方向、固定角、合并背景与整组合并拖动属于 HUD 运行模型契约；只做编辑器面板不足以验收。
- 字体运行时替换、字重/CJK 回退、语言刷新、玩家头像/品牌 tint、像素主题缩放和图标样式均可能跨页面与宿主职责。
- 搜索 provider 与自定义页面/配置行/控件扩展是公开接入契约，需明确深度绑定后的保留范围，不能静默丢弃。
- 九种 UI 事件声音、主题相关音色、环境循环、音乐 ducking 和 SoundEngine 关闭恢复涉及宿主生命周期。
- 七树背景模糊、开/关屏中断、焦点、暂停、原始屏幕返回、GUI scale 与窗口 resize 要分别实测 14 组合。
- 剪贴板/文件选择器取消、预设协议兼容、profile 切换后的绑定与撤销恢复、异步完成后的过期回调需要异常路径验收。

## 行为证据与构建证据的填写

自动化报告需 JSON schemaVersion=1、kind=behavior、result=pass，包含 featureId、sourceTreeSha256、implementationSnapshotSha256、targets，以及 cases[{id,result,assertions:[{expected,observed,pass:true}]}]。每份记录的 caseIds 对应 semanticReview.cases。编译日志缺少实际状态断言，不能充当行为报告。游戏与视觉记录每条仅针对一个组合；视觉要成对原版/新实现截图或视频，并描述布局、颜色、字形及动画比较。具体字段模板见 feature-map.evidenceExample。

已有成品的新鲜度记录独立写 build/acceptance/artifact-provenance.json：schemaVersion=1、sourceTreeSha256、implementationSnapshotSha256、artifacts[{path,sha256,target,result:"pass",command,log:{path,sha256}}]。路径须指向本工程真实成品及实际构建日志；记录与当前源码哈希不一致仍失败。这不是代替行为/游戏/视觉测试的入口。

## 按行为组织的验收项

| ID / 状态 | 源码位置 | 可验收行为与步骤 |
| --- | --- | --- |
| shell-layout / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/shell/Shell.kt:151 | 外壳的固定设计坐标、边距、阴影与多层辉光一致。<br>1. 同一窗口大小与 UI scale 对比侧栏、页头、内容区边界和滚动裁剪。<br>2. 改变 page/sidebar/glow opacity，核对各层独立透明度和叠加结果。 |
| shell-brand-avatar / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/Sidebar.kt:250 | 侧栏品牌、头像、玩家名、版本与外链 hover 完整显示。<br>1. 有/无玩家头像及较长用户名分别截图，检查回退图和文字截断。<br>2. 验证亮/暗及 Minecraft 主题的品牌和图标变体。 |
| shell-bell / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/Sidebar.kt:211 | 通知铃铛未读计数和弹出中心与实际历史同步。<br>1. 新通知到来时核对徽标，打开中心后未读状态更新。<br>2. 外点/Esc 关闭后继续收到通知，重开验证历史。 |
| header-navigation / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/Header.kt:76 | 页头标题、作者信息、前进/后退和打开原始配置屏行为一致。<br>1. 从 Mods 进入配置，再前进/后退，核对标题、作者 tooltip 和按钮状态。<br>2. 有原始配置屏入口时打开并返回，验证焦点和页面保留。 |
| header-search / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/Header.kt:311 | 全局搜索栏随当前页面变更提示、查询内容和焦点。<br>1. 在不同页面输入、清空、回退，核对各页面查询隔离。<br>2. 启用/关闭 instantSearch，验证 Enter、快捷键、Esc 和中文输入。 |
| routes-builtins / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/navigation/Pages.kt:60 | 常用、Mods、Profiles、Themes、Preferences、Credits 的路由、分组和顺序一致。<br>1. 逐个从侧栏进入，核对选中态、标题与历史栈。<br>2. 不存在/被替换页面目标的处理要留实际记录。 |
| routes-extension / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/page/OneConfigPages.kt:16 | 外部自定义页面注册、注销、命名空间和配置分类直达契约需保留或明确验收。<br>1. 注册带搜索与侧栏的页面，打开、注销、重开检查可见结果。<br>2. 验证自定义配置 modId/category 目标解析及缺失目标。 |
| shell-page-memory / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/shell/ShellController.kt:45 | 关闭/恢复的页面、搜索、滚动与生命周期保留策略一致。<br>1. 按 rememberLastPage 各策略重开，核对关闭时间与返回位置。<br>2. HUD 编辑器恢复和普通页面恢复分别记录。 |
| shell-scroll-memory / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/shell/ScrollMemory.kt:32 | 每个页面与分类的滚动锚点独立保存。<br>1. 滚动多个分类后切换和返回，确认锚点及底部位置。<br>2. 修改内容高度、过滤结果和窗口大小，验证锚点不越界。 |
| shell-wheel-containment / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/VerticalScrollContainment.kt:19 | 嵌套滚动、滚动条拖动和到边界的滚轮传递一致。<br>1. 在下拉、列表和页内容边界滚动，核对消费顺序。<br>2. 不同分辨率和 GUI scale 下拖滚动条验证坐标。 |
| shell-open-close / **pending** | 1.20.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/impls/OneConfigUIScreen.kt:80<br>1.21.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/impls/OneConfigUIScreen.kt:80<br>1.21.10/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/impls/OneConfigUIScreen.kt:80<br>1.21.11/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/impls/OneConfigUIScreen.kt:79<br>26.1.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/impls/OneConfigUIScreen.kt:79<br>26.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/impls/OneConfigUIScreen.kt:79<br>26.3/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/impls/OneConfigUIScreen.kt:79 | 每棵车道的开/关屏过渡、返回原屏幕、焦点与暂停语义一致。<br>1. 逐组合对比动画启用/禁用、半途关闭/重开、失焦/resize。<br>2. 关屏后验证鼠标捕获、游戏暂停和渲染状态恢复。 |
| shell-page-motion / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/OneConfigInterface.kt:59 | 页面切换、首次页面动画及保留退出内容的过渡一致。<br>1. 前进/后退和搜索页切换录屏，核对方向、时长、起止状态。<br>2. 切换 showPageAnimations/showOpeningPageAnimation 和零时长，验证最终状态。 |
| shell-retained-exit / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/RetainedVisibility.kt:45 | 退出动画完成前保留内容，反向/中断时不闪烁或复用过期输入。<br>1. 快速开关同一弹层和切页，逐帧检查旧内容的释放时机。<br>2. 在退出过程中点击/按键，核对正确的输入拥有者。 |
| mods-grid / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/Mods.kt:187 | Mods 卡片分组、类别筛选、布局与真实配置集合一致。<br>1. 不同类别、无配置和大量配置时检查网格、折叠组和滚动。<br>2. 打开卡片确认真实宿主配置而非演示数据。 |
| mods-card-actions / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/Mods.kt:459 | 卡片开关、收藏、隐藏、原屏入口和 hover 行为一致。<br>1. 切换卡片开关、星标、眼睛图标并重开，核对持久化。<br>2. 隐藏/禁用/不提供开关的卡片分别检查菜单和可点击区域。 |
| mods-order-drag / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/Mods.kt:405 | 卡片拖动排序、收藏区落点和自定义顺序保存一致。<br>1. 跨行、分组和收藏区拖动，核对占位提示及最终顺序。<br>2. 取消拖动、搜索中拖动和重开页面检查恢复。 |
| mods-sort / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/ModSortControl.kt:45 | 排序分段按钮、当前方向与自定义顺序一致。<br>1. 切换每种排序模式并核对选择态、结果与记忆。<br>2. 在手工拖动后切换排序，再返回验证原自定义顺序。 |
| mods-extension / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/ModsPageDecorators.kt:17 | 卡片装饰器、分组/类型、开关与隐藏扩展有明确兼容验收。<br>1. 注册自定义装饰和类型、改变排序再注销，核对稳定布局。<br>2. 异常/空扩展要展示可诊断结果且保留其余页面。 |
| favorites-host-tree / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/Favorites.kt:19 | 常用页从宿主 FavoritesTree 展示并修改实际设置。<br>1. 核对宿主常用条目、分类、值和缺树时行为。<br>2. 修改后检查播放器/HUD 的真实结果及重开持久化。 |
| config-category-layout / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/ConfigScreen.kt:117 | 配置分类、子分类、accordion、双列/宽控件布局一致。<br>1. 逐分类展开/收起，检查长标签、宽滑块和嵌套设置。<br>2. 窗口缩放、动态属性出现/消失时核对滚动与高度。 |
| config-display-rules / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/ConfigScreen.kt:656 | 属性隐藏/禁用/条件显示和装饰器实时更新。<br>1. 切换依赖属性，验证关联行 alpha、交互与重新显示值。<br>2. 重置后同一控件立即重新读取默认值。 |
| config-local-search / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/ConfigScreen.kt:244 | 配置内搜索保留分组、匹配行和嵌套树上下文。<br>1. 使用分类名、属性名、描述、无结果查询并清空。<br>2. 从全局搜索跳入具体属性，核对滚动定位与高亮。 |
| option-menu-reset / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/Options.kt:174 | 设置行操作按钮与右键菜单支持默认值恢复、解绑及禁用态。<br>1. 对普通属性、改键属性分别右键，核对 Reset/Unbind 的可用性。<br>2. 恢复默认后核对控件、真实属性与持久化值同时更新。 |
| option-custom-visualizer / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/OptionWidgets.kt:7 | 自定义 visualizer/OptionWidgets 与真实属性读写兼容。<br>1. 注册自定义控件后打开、改值、重置和卸载，检查数据一致。<br>2. 未知 visualizer 与错误元数据要保留可诊断行为。 |
| option-boolean / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/Options.kt:113 | 布尔 switch/checkbox 的状态、hover、禁用与属性监听一致。<br>1. 按源控件逐个状态截图并记录实际属性前后值。<br>2. 覆盖键鼠、禁用、重置、非法/空输入与重开。 |
| option-number / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/NumberOption.kt:217 | 数值编辑的范围、格式、步进和非法输入处理一致。<br>1. 按源控件逐个状态截图并记录实际属性前后值。<br>2. 覆盖键鼠、禁用、重置、非法/空输入与重开。 |
| option-slider / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/SliderOption.kt:18 | 滑块拖动、数值输入、边界与声音节流一致。<br>1. 按源控件逐个状态截图并记录实际属性前后值。<br>2. 覆盖键鼠、禁用、重置、非法/空输入与重开。 |
| option-inherited-slider / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/InheritableSliderOption.kt:54 | 可继承滑块的继承/覆盖开关与真实值一致。<br>1. 按源控件逐个状态截图并记录实际属性前后值。<br>2. 覆盖键鼠、禁用、重置、非法/空输入与重开。 |
| option-range / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/RangeOption.kt:55 | 双端范围输入、交叉拖动与边界约束一致。<br>1. 按源控件逐个状态截图并记录实际属性前后值。<br>2. 覆盖键鼠、禁用、重置、非法/空输入与重开。 |
| option-number-chain / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/NumberChainOption.kt:83 | 多值链式数值输入与联动约束一致。<br>1. 按源控件逐个状态截图并记录实际属性前后值。<br>2. 覆盖键鼠、禁用、重置、非法/空输入与重开。 |
| option-text / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/TextOption.kt:40 | 文本输入、选择、复制粘贴、提交和失焦行为一致。<br>1. 按源控件逐个状态截图并记录实际属性前后值。<br>2. 覆盖键鼠、禁用、重置、非法/空输入与重开。 |
| option-dropdown / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/DropdownOption.kt:35 | 下拉选择、当前值、滚动与外点关闭一致。<br>1. 按源控件逐个状态截图并记录实际属性前后值。<br>2. 覆盖键鼠、禁用、重置、非法/空输入与重开。 |
| option-multi-select / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/MultiSelectDropdownOption.kt:59 | 多选下拉的勾选、摘要和空选择一致。<br>1. 按源控件逐个状态截图并记录实际属性前后值。<br>2. 覆盖键鼠、禁用、重置、非法/空输入与重开。 |
| option-radio / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/RadioButtonOption.kt:41 | 单选控件的选中、hover、禁用与属性更新一致。<br>1. 按源控件逐个状态截图并记录实际属性前后值。<br>2. 覆盖键鼠、禁用、重置、非法/空输入与重开。 |
| option-action-info / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/ButtonOption.kt:36 | 操作按钮执行真实动作并展示一致的等待/结果。<br>1. 按源控件逐个状态截图并记录实际属性前后值。<br>2. 覆盖键鼠、禁用、重置、非法/空输入与重开。 |
| option-file / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/FileOption.kt:49 | 文件选择、取消、路径显示与真实文件属性持久化一致。<br>1. 按源控件逐个状态截图并记录实际属性前后值。<br>2. 覆盖键鼠、禁用、重置、非法/空输入与重开。 |
| option-list-textlist / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/TextListOption.kt:18 | 列表 TextList 的添加/删除/编辑/排序与持久化一致。<br>1. 空、单项、多项和长项列表检查布局、滚动与错误提示。<br>2. 操作后重开并恢复默认，核对真实元素顺序和值。 |
| option-list-numberlist / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/NumberListOption.kt:12 | 列表 NumberList 的添加/删除/编辑/排序与持久化一致。<br>1. 空、单项、多项和长项列表检查布局、滚动与错误提示。<br>2. 操作后重开并恢复默认，核对真实元素顺序和值。 |
| option-list-rangelist / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/RangeListOption.kt:22 | 列表 RangeList 的添加/删除/编辑/排序与持久化一致。<br>1. 空、单项、多项和长项列表检查布局、滚动与错误提示。<br>2. 操作后重开并恢复默认，核对真实元素顺序和值。 |
| option-list-colorlist / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/ColorListOption.kt:41 | 列表 ColorList 的添加/删除/编辑/排序与持久化一致。<br>1. 空、单项、多项和长项列表检查布局、滚动与错误提示。<br>2. 操作后重开并恢复默认，核对真实元素顺序和值。 |
| option-list-filelist / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/FileListOption.kt:31 | 列表 FileList 的添加/删除/编辑/排序与持久化一致。<br>1. 空、单项、多项和长项列表检查布局、滚动与错误提示。<br>2. 操作后重开并恢复默认，核对真实元素顺序和值。 |
| option-list-draggablelist / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/DraggableListOption.kt:66 | 列表 DraggableList 的添加/删除/编辑/排序与持久化一致。<br>1. 空、单项、多项和长项列表检查布局、滚动与错误提示。<br>2. 操作后重开并恢复默认，核对真实元素顺序和值。 |
| color-hsva-hex / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/ColorOption.kt:303 | 颜色弹层的 HSV 平面、Hue/Alpha、十六进制输入与预览一致。<br>1. 拖动每个平面/滑条并输入合法/非法 HEX，核对值与焦点。<br>2. 核对 alpha 棋盘、弹层边界、外点关闭及重开取值。 |
| color-gradient / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/ColorOption.kt:259 | 渐变开关、两端颜色、渐变预览与 PolyColor 持久化一致。<br>1. 分别编辑两端并切换渐变，核对真实 HUD/主题渐变。<br>2. 确认无渐变能力的类型不提供不可保存的选项。 |
| color-chroma / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/ColorOption.kt:166 | Chroma 开关、速度、实时色相和与渐变互斥的预览一致。<br>1. 切换 Chroma、速度上下限并逐帧对比；关闭弹层后核对 HUD。<br>2. Int/Color 与 PolyColor 属性分别验证能力边界。 |
| keybind-recording / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/KeybindOption.kt:111 | 改键录制支持键盘/鼠标/修饰键、提交、取消、解绑和复位。<br>1. 单键、多键、鼠标、Esc 与空录制分别测试，检查显示名。<br>2. 录制时不能触发其它全局动作；结束后绑定真实生效。 |
| keybind-conflicts / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/KeybindConflicts.kt:38 | 改键冲突检测与提示实时反映配置值。<br>1. 制造同组合键冲突、解绑一侧并重置，检查双方提示更新。<br>2. 配置树新增/删除与 profile 切换后重新核对冲突。 |
| keybind-provider / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/keybind/KeybindCatalog.kt:33 | 外部键位 provider、组折叠、Minecraft 绑定与配置绑定完整汇总。<br>1. 注册两个同 modId 的来源，检查分组去重和标签。<br>2. 注销、改键与配置刷新后检查实际游戏设置与显示。 |
| keybind-focus-routing / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/keybind/KeybindManager.kt:90 | 全局键位注册/替换/焦点拦截与按下释放时序一致。<br>1. 在文本框、弹层、HUD 编辑器和正常游戏分别按同一组合。<br>2. 切屏/失焦/重开后确认无卡住的按下态。 |
| profiles-grid-create / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/Profiles.kt:599 | 配置档分组、创建、搜索、图标和名称校验一致。<br>1. 创建空/重名/长名档，核对校验提示和新增卡片。<br>2. 在各类别搜索与清空，检查选中档与滚动。 |
| profiles-actions / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/Profiles.kt:935 | 配置档重命名、克隆、换图标、删除和激活菜单一致。<br>1. 逐动作测试确认/取消、当前档限制和默认克隆名称。<br>2. 激活后验证播放器/HUD/键位的真实配置值与提示。 |
| profiles-file-operations / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/Profiles.kt:229 | 配置档导入/导出选择器、取消、失败与文件格式兼容。<br>1. 导入有效、损坏与旧档；导出后再导入核对数据。<br>2. 文件选择取消和异常都恢复交互，不丢当前档。 |
| profiles-busy-overlay / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/Profiles.kt:406 | 异步配置档操作显示忙碌遮罩并防止重复动作。<br>1. 慢操作时核对遮罩先显示、按钮/输入被拦截。<br>2. 操作失败/关屏后回调不修改已过期页面。 |
| preset-catalog / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/presets/PresetTemplatesPanel.kt:189 | 预设模板目录、分类/搜索、详情与来源标签一致。<br>1. 列出内置与用户卡片，搜索并进入详情。<br>2. 目录空/错误/刷新时核对提示和选择保留。 |
| preset-preview-facets / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/presets/PresetTemplatesPanel.kt:256 | 预设 facet 选择、差异预览、部分应用和撤销真实生效。<br>1. 选择外观/音频组合，核对预览和实际改动范围。<br>2. 应用后撤销，检查未选项未被修改且旧状态恢复。 |
| preset-save-share / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/presets/PresetTemplatesPanel.kt:362 | 预设保存、分享码复制与删除确认完整实现。<br>1. 输入名称/选择 facet 后保存并复制，再读回核对协议内容。<br>2. 删除确认/取消及失败要显示真实结果。 |
| preset-import / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/presets/PresetTemplatesPanel.kt:413 | 预设分享码/文本/文件导入和打开预设目录行为一致。<br>1. 粘贴合法/非法/旧版分享码；从文件导入并处理取消。<br>2. 导入后预览、应用、重开目录确认真实保存。 |
| themes-four-variants / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/themes/Impls.kt:76 | PolyGlass/Minecraft 各深浅主题的色板、图标、圆角与预览一致。<br>1. 四个主题分别对照所有页面、菜单、控件与通知。<br>2. 核对 Minecraft 图标覆盖、像素边缘和明暗文字对比。 |
| themes-selection-motion / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/Themes.kt:74 | 主题卡片 hover、选中、预览与即时切换/持久化一致。<br>1. 逐卡片选择并重开，检查预览与当前主题标识。<br>2. 动画中切主题，验证颜色/字体/图标不会混用旧缓存。 |
| themes-pixel-grid / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/themes/Provider.kt:132 | 像素主题缩放依据屏幕比和网格模式对齐。<br>1. 多种窗口大小、UI scale 和 pixelGrid 模式对比像素边界。<br>2. 检查文本/图标/点击位置与视觉布局一致。 |
| theme-accent-gradient / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/themes/Provider.kt:51 | 主题强调色与渐变传递到品牌、控件、辉光和通知。<br>1. 切换强调色/渐变，核对弹层与已显示通知的更新。<br>2. 重开后核对颜色持久化与各层 alpha。 |
| font-source-hot-swap / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/UiFontOverride.kt:104 | 宿主替换字体通过 revision 失效缓存，字重与 CJK 回退完整。<br>1. 热切字体/恢复默认/同名文件内容变化后比较页面与 HUD。<br>2. 检查 regular/medium/semibold/bold、缺失字形和旧资源释放。 |
| font-files-fallback / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/font/UiFontFiles.kt:68 | 外部字体目录、常规/粗体及回退读取契约完整。<br>1. 有/无目录、缺粗体、损坏字体和缺回退字体分别打开。<br>2. 核对日志/可见降级、字重回退及重开行为。 |
| language-refresh / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/I18n.kt:19 | 语言与格式化文案随宿主语言变化更新。<br>1. 中英文切换后核对页头、HUD tour、通知、菜单和搜索。<br>2. 带参数、缺翻译与长文本分别检查回退和排版。 |
| preferences-schema / **pending** | muoniumconfig-common/src/main/java/org/polyfrost/oneconfig/internal/OneConfigConfig.java:72 | 偏好页的完整配置 schema/default/key 与持久化兼容。<br>1. 逐项核对键位、模糊、透明度、暂停、缩放、记忆、动画、搜索与声音开关。<br>2. 迁移已有 oneconfig.json，改值重开确认实际效果和持久化。 |
| credits-links / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/Credits.kt:89 | 致谢分组、翻译者、链接、头像与语言过滤一致。<br>1. 中英文分别对照作者/翻译者名单和长名排版。<br>2. 验证每个外链的目标及点击区域。 |
| search-global-results / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/SearchResultsScreen.kt:49 | 全局搜索按配置/页面分组，显示匹配上下文并准确跳转。<br>1. 查询标题、描述、类别和未知词，核对结果排序与空态。<br>2. 点击结果检查目标页/分类/设置高亮与后退状态。 |
| search-provider-registry / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/search/SearchProviderRegistry.kt:10 | 搜索 provider 按优先级与可用性选择并建立语料。<br>1. 注册高优先级、不可用和抛异常 provider，核对回退。<br>2. 注销后搜索必须切回当前可用 provider。 |
| search-matching-distance / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/search/DefaultSearchProvider.kt:85 | 默认搜索的大小写/子串/容错距离与设置一致。<br>1. 分别设置 searchDistance，用精确词、错字、短词和中文查询对照。<br>2. 局部搜索和全局搜索使用同一原始案例核对可见结果。 |
| search-public-api / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/search/SearchProviders.kt:8 | 公开 SearchProviders、SearchScope 和 PageSearchQuery 扩展契约完整。<br>1. 通过公开 API 注册 provider/文档，检查不同 scope 的结果。<br>2. 注销、动态配置变更与页面关闭后的语料更新要留记录。 |
| notifications-history / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/NotificationsCenter.kt:46 | 通知中心显示类型图标/颜色、相对时间、动作、移除和清空历史。<br>1. 推送各 NotificationType 与长文本，核对顺序、时间和滚动。<br>2. 点击动作/删除/清空，确认历史、未读和活动 toast 正确变化。 |
| notifications-toasts / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/notifications/v1/NotificationToasts.kt:88 | 游戏全局 toast 的堆叠、进出动画、文字换行和点击区域完整。<br>1. 同时推多条、长文和带动作通知，逐帧核对位置与遮挡。<br>2. 在主界面/HUD 编辑器/普通游戏触发并验证输入命中。 |
| notifications-progress / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/notifications/v1/NotificationToasts.kt:115 | toast 的寿命进度、外部进度和 hover 暂停语义一致。<br>1. 自动超时、持续通知、动态进度和悬浮暂停分别计时录屏。<br>2. 移开鼠标/关屏后验证进度和退出状态。 |
| notifications-input / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/notifications/v1/ToastInput.kt:58 | toast 悬浮/点击/动作与游戏输入路由一致。<br>1. 点击主体、动作、外部与多层交叠处，核对仅正确目标触发。<br>2. 失焦/隐藏/移除通知后不得保留旧 hover 或吞输入。 |
| notifications-thread-history / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/notifications/v1/NotificationsManager.kt:70 | 跨线程推送、活动/历史/已读状态与通知动作生命周期一致。<br>1. 后台线程连续推送/更新/移除，观察顺序和显示。<br>2. 清历史与关闭活动通知分别验证，不能将两者混同。 |
| hud-studio-entry / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudDesignStudio.kt:1034 | HUD 选择、设置/设计分页、可拖面板和退出/恢复一致。<br>1. 无 HUD、有选中、多选及原 HUD 已删除时分别打开。<br>2. 拖面板、切分类和重开，检查位置、选择与搜索。 |
| hud-selection-marquee / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudDesignStudio.kt:1485 | 点击穿透、重叠 HUD 轮选、多选和框选动画一致。<br>1. 单击、组合键加选、反向框选及交叠 HUD 逐项对照。<br>2. 框选过程中 Esc/切屏，确认临时状态和选择正确恢复。 |
| hud-drag-snap / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudDesignStudio.kt:244 | HUD 拖动、吸附线、对齐和拖动时 UI 透明度一致。<br>1. 拖到屏幕边/中心和邻居附近，核对阈值、坐标与导线。<br>2. 锁定 HUD、多选拖动、不同 GUI scale 与窗口 resize 分别测。 |
| hud-resize / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudDesignStudio.kt:701 | 四角缩放、文本缩放/静态尺寸和尺寸标记一致。<br>1. 分别拖四角，核对最小尺寸、保持角与字体/背景大小。<br>2. 缩放时切 GUI scale 或取消操作，检查坐标不跳变。 |
| hud-action-context / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudDesignStudio.kt:867 | HUD 浮动操作条、右键菜单、锁定提示与命中顺序一致。<br>1. 对重叠、锁定和多选 HUD 检查所有动作与 tooltip。<br>2. 菜单临近窗口边缘时核对定位和关闭。 |
| hud-copy-group / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudDesignStudio.kt:2712 | HUD 复制/剪切/粘贴/克隆保留配置并重建组内关联。<br>1. 复制一个 HUD 与锚定/合并组合，再粘贴核对独立属性。<br>2. 非可克隆 HUD、取消和删除后撤销分别记录。 |
| hud-undo-redo / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudHistory.kt:186 | HUD 布局历史合并连续操作，撤销重做还原实体和属性。<br>1. 拖动/缩放/改样式/新增/删除后连续撤销重做。<br>2. 跨 profile、切屏和异步修改后核对历史拥有者。 |
| hud-shortcut-session / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudDesignSession.kt:34 | HUD 快捷键统一进入当前设计会话并尊重文字焦点。<br>1. 复制/剪切/粘贴、删除、全选、锁定、隐藏、设置、撤销和重做逐个实测。<br>2. 弹层录制键位/文本输入/已退出编辑器时不误执行。 |
| hud-library-sections / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudDesignStudio.kt:3354 | HUD 库按 mod 分组、搜索、图标导航和滚动定位一致。<br>1. 多来源/空来源/Compat HUD 混合时对照分组与标签。<br>2. 点击 mod 图标、搜索和滚动到底，检查当前分组高亮。 |
| hud-library-layout / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudDesignStudio.kt:3267 | HUD 库依据自然预览尺寸排版，卡片/间距/滚动条一致。<br>1. 用宽窄/高矮 HUD 混排，调整窗口和 UI scale 对比换行。<br>2. 预览状态变化后核对缓存失效与点击区域。 |
| hud-library-add / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudDesignStudio.kt:999 | 点击和拖出 HUD 库卡片都创建真实 HUD，并保持正确初始位置。<br>1. 点击添加、拖出到游戏区、在外壳内释放取消逐项实测。<br>2. 空选中自动展开库、添加后设置面板与选择要正确更新。 |
| hud-library-preview / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudDesignStudio.kt:3670 | 原生/Compat HUD 预览、hover 添加覆盖与比例一致。<br>1. 对比真实 HUD 和卡片预览的文字、背景、阴影与裁剪。<br>2. 检查旧 HUD 渲染路径、缺预览与动态内容。 |
| hud-tour-nine-steps / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudTour.kt:59 | HUD tour 的 Welcome/Adding/Dragging/Merging/Anchoring/Settings/Designer/Fonts/Done 九步完整。<br>1. 首次进入、手动重播、前后切步、关闭和完成后重开逐项验证。<br>2. 检查每步聚焦目标、高亮遮罩、说明卡避让和实际页面类别。 |
| hud-tour-interactive-merge / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudDesignStudio.kt:1164 | 导览合并步骤依据真实拖动合并事件推进，而非纯静态演示。<br>1. 无可合并 HUD、锁定 HUD 和真实合并成功分别观察提示和推进条件。<br>2. 退出导览要恢复选择/库/面板临时状态并保存已看标志。 |
| hud-anchor-target / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/hud/v1/Hud.kt:880 | HUD 自身/目标九点锚定、清除和环路约束一致。<br>1. 将多个 HUD 互相锚定，改变目标尺寸/位置后观察跟随。<br>2. 清锚、删除目标与尝试环路，核对位置和数据。 |
| hud-anchor-growth-pin / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/hud/v1/Hud.kt:674 | 增长方向、自身锚点和固定角更改保持屏幕位置语义。<br>1. 改变内容宽高、增长点、自锚点和固定角，逐步记录坐标。<br>2. resize/GUI scale/profile 切换后检查锚点仍有效。 |
| hud-background-merge / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/hud/v1/HudBackgroundMerge.kt:54 | 相邻/对角 HUD 背景合并、圆角和隐藏保留背景规则一致。<br>1. 横排、竖排、对角、不同色/圆角/边距组合逐个截图。<br>2. 开启/关闭 merge diagonally 和 keep background when hidden 后核对组背景。 |
| hud-merge-drag-group / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudDesignStudio.kt:1048 | 合并组整体拖动、单体分离与重新接合的规则一致。<br>1. 普通拖动与分离修饰键分别对比组成员坐标。<br>2. 有锁定成员、锚定成员和多选时验证排除集合与结束清理。 |
| hud-settings-background / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/sections/Settings.kt:50 | HUD 可见性、锁定、GUI scale、背景/阴影与显示场景设置一致。<br>1. 逐项更改背景色/Chroma/圆角/阴影及 F3/Tab/GUI/Chat 可见性。<br>2. 恢复位置/删除 HUD 后核对真实实例、选择与撤销。 |
| hud-designer-size-padding / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/sections/Designer.kt:128 | 设计器尺寸、对齐、静态大小、边缘和间距布局设置完整。<br>1. 动态与静态尺寸模式分别编辑宽高、边距、三种间距分配。<br>2. 检查多选、无选择与搜索过滤后的控件状态。 |
| hud-designer-text / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/sections/Designer.kt:226 | HUD 文字字体、字号、粗体/斜体、字重、对齐与大小写样式一致。<br>1. Poppins/Minecraft 两条字体路径、三种对齐/大小写和修饰组合截图。<br>2. 预览与真实 HUD 逐字对照，核对文字尺寸与阴影。 |
| hud-text-render-format / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/hud/v1/TextHud.kt:88 | 文本 HUD 的前后缀、大小写、换行、字形格式与时间更新一致。<br>1. 空文本、长行、多行和格式码分别对比。<br>2. 日期时间 HUD 的格式错误、更新频率和克隆后状态要实测。 |
| sound-nine-events / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/sound/UiSoundEvent.kt:3 | OPEN/CLOSE/CLICK/SLIDER_TICK/HUD_SELECT/DRAG_START/DRAG_END/RESIZE_START/RESIZE_END 九类音效触发一致。<br>1. 逐事件记录触发次数、顺序和音量，拖动长过程不能重复触发起始音。<br>2. 总开关和各类子开关都要改变实际声音。 |
| sound-theme-routing / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/sound/UiSounds.kt:22 | UI 音效服务路由、主题、音量边界和无服务降级一致。<br>1. 两种音色主题、静音/恢复与零音量分别听测。<br>2. 缺资源/缺服务时界面仍可用且不伪报有声音。 |
| sound-ambience-life / **pending** | muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/sound/UiSounds.kt:44 | 界面环境循环的获取/释放、主题切换与音量即时更新一致。<br>1. 开关屏、编辑器切换、重入与主题切换时核对不叠加循环。<br>2. 退出/失焦/游戏声音引擎重载后核对停止与恢复。 |
| sound-ducking-host / **pending** | 1.20.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/sound/McUiSoundService.kt:19<br>1.21.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/sound/McUiSoundService.kt:19<br>1.21.10/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/sound/McUiSoundService.kt:19<br>1.21.11/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/sound/McUiSoundService.kt:19<br>26.1.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/sound/McUiSoundService.kt:19<br>26.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/sound/McUiSoundService.kt:19<br>26.3/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/sound/McUiSoundService.kt:19 | 各车道实际音效映射、环境声和游戏音乐 ducking/恢复完整。<br>1. 背景音乐播放时打开/关闭设置中心，核对开启/禁用 ducking。<br>2. 引擎重载、缺文件与快速重开后不能留下低音量或未释放声源。 |
| host-background-blur-1.21.11 / **pending** | 1.21.11/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/UiBlur.kt:20 | 1.21.11 的现代渲染提取/后处理模糊路径需要独立适配和验收。<br>1. 核对该版本准确依赖与 mixin 调用链；不要以旧车道的空 UiBlur.kt 推断行为。<br>2. 逐加载器实测开关、透明度、resize、关屏后渲染状态与 GPU 资源。 |
| host-background-blur-26.1.2 / **pending** | 26.1.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/UiBlur.kt:20 | 26.1.2 的现代渲染提取/后处理模糊路径需要独立适配和验收。<br>1. 核对该版本准确依赖与 mixin 调用链；不要以旧车道的空 UiBlur.kt 推断行为。<br>2. 逐加载器实测开关、透明度、resize、关屏后渲染状态与 GPU 资源。 |
| host-background-blur-26.2 / **pending** | 26.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/UiBlur.kt:21 | 26.2 的现代渲染提取/后处理模糊路径需要独立适配和验收。<br>1. 核对该版本准确依赖与 mixin 调用链；不要以旧车道的空 UiBlur.kt 推断行为。<br>2. 逐加载器实测开关、透明度、resize、关屏后渲染状态与 GPU 资源。 |
| host-background-blur-26.3 / **pending** | 26.3/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/UiBlur.kt:21 | 26.3 的现代渲染提取/后处理模糊路径需要独立适配和验收。<br>1. 核对该版本准确依赖与 mixin 调用链；不要以旧车道的空 UiBlur.kt 推断行为。<br>2. 逐加载器实测开关、透明度、resize、关屏后渲染状态与 GPU 资源。 |

## 逐源文件定位与待验收面

以下每个文件是独立 pending/complete 单元；与上面的语义项目同时验收，避免只完成主页面而遗漏回调、扩展或宿主。行号对应上述基线；每条位置附源码摘录，注释和字符串不会伪造调用。

### 1.20.1/fabric/src/main/java/org/polyfrost/oneconfig/internal/OneConfig.java

`file-7596093ca6bedc0944d4`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 239 | search-provider / SearchCorpus.INSTANCE | EventManager.register(ResourceFinishedLoading.class, e -> SearchCorpus.INSTANCE.init()); |
| 338 | font-language / McFontService.INSTANCE | McFontService.INSTANCE.init(); |

### 1.20.1/fabric/src/main/java/org/polyfrost/oneconfig/internal/OneConfigPreLaunch.java

`file-501497cd66e491bd2562`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 25 | font-language / McFontService.installEngines | McFontService.installEngines(); |

### 1.20.1/forge/src/main/java/org/polyfrost/oneconfig/internal/OneConfig.java

`file-71dc9c9e392911181ad5`；状态 **pending**；领域 ui-support；组合 1.20.1/forge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 238 | search-provider / SearchCorpus.INSTANCE | EventManager.register(ResourceFinishedLoading.class, e -> SearchCorpus.INSTANCE.init()); |
| 348 | font-language / McFontService.INSTANCE | McFontService.INSTANCE.init(); |

### 1.20.1/forge/src/main/java/org/polyfrost/oneconfig/internal/OneConfigPreLaunch.java

`file-ac9f1fa3a872dd5f6a3b`；状态 **pending**；领域 ui-support；组合 1.20.1/forge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 22 | font-language / McFontService.installEngines | org.polyfrost.oneconfig.internal.ui.compose.McFontService.installEngines(); |

### 1.20.1/src/main/java/org/polyfrost/oneconfig/api/platform/v1/internal/I18nPlatformImpl.java

`file-91b062c89ad4aa3337f7`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 39 | font-language / I18nPlatform | public class I18nPlatformImpl implements I18nPlatform { |

### 1.20.1/src/main/java/org/polyfrost/oneconfig/api/platform/v1/internal/KeysImpl.java

`file-1a6e04b88291036dc766`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 21 | keybind / getKeyA | public int getKeyA() { |
| 26 | keybind / getKeyC | public int getKeyC() { |
| 31 | keybind / getKeyD | public int getKeyD() { |
| 36 | keybind / getKeyE | public int getKeyE() { |
| 41 | keybind / getKeyF | public int getKeyF() { |
| 46 | keybind / getKeyH | public int getKeyH() { |
| 51 | keybind / getKeyL | public int getKeyL() { |
| 56 | keybind / getKeyR | public int getKeyR() { |
| 61 | keybind / getKeyV | public int getKeyV() { |
| 66 | keybind / getKeyX | public int getKeyX() { |
| 71 | keybind / getKeyY | public int getKeyY() { |
| 76 | keybind / getKeyZ | public int getKeyZ() { |
| 81 | keybind / getKeyDelete | public int getKeyDelete() { |
| 91 | keybind / getKeyLeftShift | public int getKeyLeftShift() { |
| 96 | keybind / getKeyRightShift | public int getKeyRightShift() { |
| 101 | keybind / getKeyLeftControl | public int getKeyLeftControl() { |
| 106 | keybind / getKeyRightControl | public int getKeyRightControl() { |
| 111 | keybind / getKeyLeftAlt | public int getKeyLeftAlt() { |
| 116 | keybind / getKeyRightAlt | public int getKeyRightAlt() { |
| 121 | keybind / getKeyLeftSuper | public int getKeyLeftSuper() { |
| 126 | keybind / getKeyRightSuper | public int getKeyRightSuper() { |

### 1.20.1/src/main/java/org/polyfrost/oneconfig/api/ui/v1/api/internal/TinyFdApiImpl.java

`file-6a9b8dd9c67f68ba7698`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge。

核对打开/取消/确认、焦点、边界定位、外点关闭与提交后可见结果。 定位类型: dialog-or-popup

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 74 | dialog-or-popup / Dialog | private static <T> T open(String description, T fallback, Dialog<T> dialog) { |
| 151 | dialog-or-popup / tinyfd_openFileDialog | String result = TinyFileDialogs.tinyfd_openFileDialog(sanitizeText(title), sanitizePath(defaultFilePath), filters(stack, filterPatterns), sanitizeText(filterDescription), false); |
| 161 | dialog-or-popup / tinyfd_saveFileDialog | String result = TinyFileDialogs.tinyfd_saveFileDialog(sanitizeText(title), sanitizePath(defaultFilePath), filters(stack, filterPatterns), sanitizeText(filterDescription)); |
| 171 | dialog-or-popup / tinyfd_openFileDialog | String result = TinyFileDialogs.tinyfd_openFileDialog(sanitizeText(title), sanitizePath(defaultFilePath), filters(stack, filterPatterns), sanitizeText(filterDescription), true); |
| 187 | dialog-or-popup / tinyfd_selectFolderDialog | firstPath(TinyFileDialogs.tinyfd_selectFolderDialog(sanitizeText(title), sanitizePath(defaultFolderPath)))); |
| 199 | dialog-or-popup / tinyfd_notifyPopup | TinyFileDialogs.tinyfd_notifyPopup(sanitizeText(title), sanitizeText(message), icon) == 1 ? 0 : 1); |
| 271 | dialog-or-popup / Dialog | private interface Dialog<T> { |

### 1.20.1/src/main/java/org/polyfrost/oneconfig/api/ui/v1/keybind/internal/MinecraftKeybindCodec.java

`file-ece93fa5efec0b8ed66e`；状态 **pending**；领域 keybind；组合 1.20.1/fabric, 1.20.1/forge。

该文件支撑 keybind；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 1.20.1/src/main/java/org/polyfrost/oneconfig/internal/mixin/render/Mixin_UiBlur.java

`file-238c627c565872ccb04f`；状态 **pending**；领域 blur；组合 1.20.1/fabric, 1.20.1/forge。

该文件支撑 blur；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 1.20.1/src/main/java/org/polyfrost/oneconfig/internal/ui/PlayerHeadBlit.java

`file-26d7e7e23875ef52a234`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 1.20.1/src/main/java/org/polyfrost/oneconfig/internal/ui/PlayerHeadSkinAccess.java

`file-122751b16f3a6620c3ca`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 1.20.1/src/main/kotlin/org/polyfrost/oneconfig/api/hud/v1/LegacyHud.kt

`file-6e4965252f7c1a4218b0`；状态 **pending**；领域 hud-editor；组合 1.20.1/fabric, 1.20.1/forge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 14 | ui-function / render | abstract fun render(mcCtx: GuiGraphics) |
| 18 | ui-function / minimumSize | override fun minimumSize(): Pair<Float, Float> = width to height |
| 20 | composable / Content | @Composable |
| 21 | ui-function / Content | override fun Content() {} |

### 1.20.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/GuiProjection.kt

`file-f6ca452c2f2d988b5d42`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 9 | ui-function / withGuiProjection | internal inline fun withGuiProjection(guiWidth: Int, guiHeight: Int, render: () -> Unit) { |

### 1.20.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/OffscreenTarget.kt

`file-7e2b7e0ee94a10641cac`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 24 | ui-function / resolve | fun resolve(w: Int, h: Int): Boolean { |
| 46 | ui-function / clear | fun clear() { |
| 53 | ui-function / destroy | fun destroy() { |
| 60 | ui-function / dispose | fun dispose() { |
| 70 | ui-function / destroyAll | fun destroyAll() { |
| 83 | ui-function / texture | fun texture(target: RenderTarget, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, alpha: Float) |

### 1.20.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/RenderTargetFbo.kt

`file-0982780597171fffd2ae`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 19 | ui-function / getFboId | fun getFboId(frameBuffer: RenderTarget): Int { |
| 23 | ui-function / getColorTexId | fun getColorTexId(frameBuffer: RenderTarget): Int = frameBuffer.colorTextureId |
| 25 | ui-function / saveBoundTarget | fun saveBoundTarget() = IntArray(5).also { |
| 30 | ui-function / restoreBoundTarget | fun restoreBoundTarget(s: IntArray) { |

### 1.20.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/BlazeRenderer.kt

`file-eafd02e2390b9b075f92`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 1.20.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/ComposeSceneContextImpl.kt

`file-e66c91456a38c1450f01`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 28 | ui-function / requestInputMode | override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard |
| 37 | ui-function / isKeyDown | private fun isKeyDown(key: Int): Boolean { |
| 43 | keybind / isCtrlPressed | isCtrlPressed = isKeyDown(InputConstants.KEY_LCONTROL) \|\| isKeyDown(InputConstants.KEY_RCONTROL), |
| 44 | keybind / isShiftPressed | isShiftPressed = isKeyDown(InputConstants.KEY_LSHIFT) \|\| isKeyDown(InputConstants.KEY_RSHIFT), |
| 45 | keybind / isAltPressed | isAltPressed = isKeyDown(InputConstants.KEY_LALT) \|\| isKeyDown(InputConstants.KEY_RALT), |
| 49 | keybind / isMetaPressed | isMetaPressed = isKeyDown(Platform.compatibility().keys().keyLeftSuper) \|\| isKeyDown(Platform.compatibility().keys().keyRightSuper) |
| 74 | ui-function / setPointerIcon | override fun setPointerIcon(pointerIcon: PointerIcon) { |
| 81 | ui-function / resetPointerIcon | fun resetPointerIcon() { |
| 85 | ui-function / applyPointerIcon | private fun applyPointerIcon(pointerIcon: PointerIcon) { |
| 94 | ui-function / requestFocus | override fun requestFocus(): Boolean { |
| 99 | ui-function / allowCursorChanges | private fun allowCursorChanges(): Boolean { |
| 116 | ui-function / sync | fun sync() {} |
| 117 | ui-function / close | fun close() {} |
| 124 | ui-function / resetPointerIcon | fun resetPointerIcon() { |
| 128 | ui-function / updateContainerSize | fun updateContainerSize(width: Int, height: Int) { |

### 1.20.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/ComposeScreen.kt

`file-868f3d67ba555c9d8507`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 63 | ui-function / getText | override fun getText(): AnnotatedString? { |
| 72 | ui-function / setText | override fun setText(annotatedString: AnnotatedString) { |
| 79 | ui-function / hasText | override fun hasText(): Boolean { |
| 90 | ui-function / getClipEntry | override suspend fun getClipEntry(): ClipEntry? { |
| 96 | ui-function / setClipEntry | override suspend fun setClipEntry(clipEntry: ClipEntry?) { |
| 109 | route / ComposeScreen | abstract class ComposeScreen( |
| 119 | composable / compose | @Composable |
| 120 | ui-function / compose | abstract fun compose() |
| 143 | ui-function / liveScene | private fun liveScene(): ComposeScene? { |
| 148 | ui-function / withScene | private inline fun <T> withScene(block: (ComposeScene) -> T): T? { |
| 163 | ui-function / sendKeyEventSafely | private inline fun sendKeyEventSafely(build: () -> androidx.compose.ui.input.key.KeyEvent): Boolean { |
| 174 | ui-function / poisonScene | private fun poisonScene(cause: Throwable) { |
| 193 | ui-function / onSceneRebuilding | protected open fun onSceneRebuilding() {} |
| 195 | ui-function / ensureScene | private fun ensureScene(): ComposeScene? { |
| 211 | ui-function / closeSceneQuietly | private fun closeSceneQuietly() { |
| 236 | ui-function / createScene | private fun createScene(): ComposeScene { |
| 280 | ui-function / init | override fun init() { |
| 309 | ui-function / bindContent | private fun bindContent(): Boolean { |
| 316 | ui-function / setContentOn | private fun setContentOn(scene: ComposeScene) { |
| 331 | ui-function / reportUnavailableAndClose | private fun reportUnavailableAndClose() { |
| 338 | ui-function / closeWithMessage | private fun closeWithMessage(reason: String) { |
| 345 | ui-function / resize | override fun resize( |
| 355 | ui-function / isPauseScreen | override fun isPauseScreen(): Boolean = false |
| 364 | ui-function / blurAlpha | protected open fun blurAlpha(): Float = 0f |
| 371 | ui-function / disposeScene | private fun disposeScene() { |
| 376 | ui-function / releaseScene | private fun releaseScene() { |
| 384 | ui-function / onClose | override fun onClose() { |
| 389 | ui-function / removed | override fun removed() { |
| 401 | ui-function / render | override fun render(ctx: GuiGraphics, mouseX: Int, mouseY: Int, tickDelta: Float) { |
| 464 | ui-function / paint | private fun paint(ctx: RenderContext, nanos: Long) { |
| 481 | ui-function / mouseClicked | override fun mouseClicked(x: Double, y: Double, button: Int): Boolean { |
| 482 | keybind / KeybindRecordingBus.consumeMouse | if (KeybindRecordingBus.consumeMouse(button, true) \|\| handleMouseClicked(button)) { |
| 492 | ui-function / mouseReleased | override fun mouseReleased(x: Double, y: Double, button: Int): Boolean { |
| 493 | keybind / KeybindRecordingBus.consumeMouse | val recordingConsumed = KeybindRecordingBus.consumeMouse(button, false) |
| 504 | ui-function / mouseScrolled | override fun mouseScrolled(x: Double, y: Double, scrollY: Double): Boolean { |
| 509 | ui-function / handleMouseClicked | protected open fun handleMouseClicked(button: Int): Boolean = false |
| 517 | ui-function / releaseHeldButtons | private fun releaseHeldButtons() { |
| 525 | ui-function / sendMouseButtonEvent | private fun sendMouseButtonEvent(type: PointerEventType, button: Int) { |
| 540 | ui-function / sendScrollEvent | private fun sendScrollEvent(scrollX: Double, scrollY: Double) { |
| 562 | ui-function / isPreciseScroll | private fun isPreciseScroll(scrollX: Double, scrollY: Double, now: Long, macPrecise: Boolean?): Boolean { |
| 574 | ui-function / charTyped | override fun charTyped(char: Char, modifiers: Int): Boolean { |
| 580 | ui-function / ctrlDown | fun Int.ctrlDown() = this and InputConstants.MOD_CONTROL != 0 |
| 581 | ui-function / shiftDown | fun Int.shiftDown() = this and GLFW.GLFW_MOD_SHIFT != 0 |
| 582 | ui-function / altDown | fun Int.altDown() = this and GLFW.GLFW_MOD_ALT != 0 |
| 583 | ui-function / superDown | fun Int.superDown() = this and GLFW.GLFW_MOD_SUPER != 0 |
| 585 | ui-function / handleKeyPressed | protected open fun handleKeyPressed(key: Int, modifiers: Int): Boolean = false |
| 590 | ui-function / keyPressed | override fun keyPressed(key: Int, scanCode: Int, modifiers: Int): Boolean { |
| 597 | ui-function / keyReleased | override fun keyReleased(key: Int, scanCode: Int, modifiers: Int): Boolean { |
| 604 | ui-function / dispatchKeyPressed | private fun dispatchKeyPressed(bindingKey: Int, shortcutKey: Int, modifiers: Int): Boolean { |
| 606 | keybind / KeybindRecordingBus.consumeEscape | if (KeybindRecordingBus.consumeEscape()) { |
| 610 | keybind / KeybindRecordingBus.isRecording | if (KeybindRecordingBus.isRecording) { |
| 622 | ui-function / sendKeyPressedEvent | private fun sendKeyPressedEvent(bindingKey: Int, shortcutKey: Int, modifiers: Int): Boolean { |
| 632 | keybind / isCtrlPressed | isCtrlPressed = modifiers.ctrlDown(), |
| 633 | keybind / isShiftPressed | isShiftPressed = modifiers.shiftDown(), |
| 634 | keybind / isAltPressed | isAltPressed = modifiers.altDown(), |
| 635 | keybind / isMetaPressed | isMetaPressed = modifiers.superDown(), |
| 649 | ui-function / sendKeyReleasedEvent | private fun sendKeyReleasedEvent(bindingKey: Int, shortcutKey: Int, modifiers: Int): Boolean { |
| 657 | keybind / isCtrlPressed | isCtrlPressed = modifiers.ctrlDown(), |
| 658 | keybind / isShiftPressed | isShiftPressed = modifiers.shiftDown(), |
| 659 | keybind / isAltPressed | isAltPressed = modifiers.altDown(), |
| 660 | keybind / isMetaPressed | isMetaPressed = modifiers.superDown(), |
| 674 | ui-function / sendCharacterEvent | private fun sendCharacterEvent(char: Char, codePoint: Int, modifiers: Int): Boolean { |
| 686 | ui-function / sendCharKeyEvent | private fun sendCharKeyEvent(char: Char, codePoint: Int, modifiers: Int): Boolean { |
| 692 | keybind / isCtrlPressed | isCtrlPressed = modifiers.ctrlDown(), |
| 693 | keybind / isShiftPressed | isShiftPressed = modifiers.shiftDown(), |
| 694 | keybind / isAltPressed | isAltPressed = modifiers.altDown(), |
| 695 | keybind / isMetaPressed | isMetaPressed = modifiers.superDown(), |
| 709 | ui-function / modifiersToAwt | private fun modifiersToAwt(modifiers: Int): Int { |
| 718 | ui-function / syncSceneMetrics | private fun syncSceneMetrics(): Boolean { |
| 747 | ui-function / surfaceScale | private fun surfaceScale(): Float { |
| 758 | ui-function / pointerPosition | protected fun pointerPosition(): Offset { |
| 779 | ui-function / isWheelStep | fun isWheelStep(value: Double) = abs(value * 120 - round(value * 120)) < 1e-3 |
| 785 | ui-function / sceneDensity | internal fun sceneDensity(): Float { |
| 804 | ui-function / isDispatchNeeded | override fun isDispatchNeeded(context: CoroutineContext): Boolean = |
| 807 | ui-function / dispatch | override fun dispatch(context: CoroutineContext, block: Runnable) { |

### 1.20.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/GlHost.kt

`file-1425501f0a82031e5260`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge。

对齐背景模糊开关、强度、HUD 穿透、resize 与关屏后的 GPU/画面恢复。 定位类型: blur 从实际宿主入口触发，核对完整状态变化、交互、退出及重进后的结果。 定位类型: peripheral-ui 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 67 | ui-function / unavailableReason | fun unavailableReason(): String? { |
| 81 | ui-function / setGlTexId | fun setGlTexId(id: Int) { this.id = id } |
| 82 | ui-function / load | override fun load(manager: ResourceManager) {} |
| 83 | ui-function / close | override fun close() { this.id = -1 } |
| 87 | ui-function / blitHud | fun blitHud(guiGraphics: GuiGraphics) { |
| 124 | ui-function / screen | fun screen(dirty: Boolean, blurAlpha: Float, underlay: GuiDraw?, paint: (RenderContext) -> Unit) { |
| 145 | ui-function / deferDebugScreen | fun deferDebugScreen(overlay: DebugScreenOverlay): Boolean { |
| 152 | ui-function / drawBeforeBlit | fun drawBeforeBlit() { |
| 158 | ui-function / drawAtPresent | fun drawAtPresent() { |
| 166 | ui-function / draw | private fun draw(fbo: Int) { |
| 172 | blur / GlBlur.release | GlBlur.release() |
| 181 | peripheral-ui / NotificationsManager.count | if (NotificationsManager.count > 0) drawGl(fbo, null) { |
| 191 | ui-function / drawGui | private fun drawGui(draw: GuiDraw) { |
| 210 | ui-function / drawBlur | private fun drawBlur(fbo: Int, alpha: Float) { |
| 216 | blur / GlBlur.draw | GlBlur.draw(fbo, w, h, alpha, client.isPaused) |
| 224 | ui-function / drawGl | private fun drawGl(fbo: Int, cached: GlRenderer?, kept: Boolean = false, draw: (RenderContext) -> Unit): GlRenderer? { |
| 243 | ui-function / maxTextureSize | private fun maxTextureSize(): Int = |
| 248 | ui-function / cachedMaxTextureSize | private fun cachedMaxTextureSize(): Int { |
| 257 | ui-function / viewportFitsTexture | private fun viewportFitsTexture(w: Int, h: Int): Boolean { |
| 276 | ui-function / reuse | private fun reuse(renderer: GlRenderer?): GlRenderer { |
| 282 | ui-function / drawHudGl | fun drawHudGl(draw: (RenderContext, RenderContext?) -> Unit) { |
| 301 | ui-function / resolveHudTarget | private fun resolveHudTarget(): RenderTarget? { |
| 326 | ui-function / destroyHudTarget | private fun destroyHudTarget() { |
| 342 | ui-function / onAllocFailure | private fun onAllocFailure(what: String, w: Int, h: Int, error: Throwable) { |
| 357 | ui-function / recreateSurface | fun recreateSurface(width: Int, height: Int) { |

### 1.20.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/GlRenderer.kt

`file-6a8e9af5fd97e4a56026`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 41 | ui-function / moved | fun moved(at: Int) = Live(at, state, draw, key).also { it.last = last } |
| 43 | ui-function / changed | fun changed() = key == null \|\| key() != last |
| 45 | ui-function / commit | fun commit() { |
| 55 | ui-function / bitmapTexture | override fun bitmapTexture(bitmap: PolyBitmap): Any = GlTextures.bitmap(bitmap) |
| 59 | ui-function / mark | override fun mark(): Any { |
| 64 | ui-function / since | override fun since(mark: Any): Any { |
| 87 | ui-function / valid | override fun valid(captured: Any): Boolean = (captured as Captured).batches.all { GlTextures.alive(it.texture) } |
| 89 | ui-function / append | override fun append(captured: Any) { |
| 97 | ui-function / writeStateKey | override fun writeStateKey() { |
| 102 | ui-function / matches | fun matches(): Boolean = |
| 105 | ui-function / reset | fun reset() { |
| 111 | ui-function / submit | override fun submit(texture: Any, premultiplied: Boolean, nearest: Boolean, verts: FloatArray, colors: IntArray, bounds: FloatArray) { |
| 117 | ui-function / layerMark | override fun layerMark(): Any = intArrayOf(batches.size, lives.size) |
| 119 | ui-function / layer | override fun layer(mark: Any, alpha: Float) { |
| 132 | ui-function / fade | override fun fade(mark: Any, alpha: Float): Boolean { |
| 142 | ui-function / live | override fun live(draw: (PolyRenderer) -> Unit) { |
| 150 | ui-function / mcTextLive | override fun mcTextLive(text: String, x: Float, y: Float, color: () -> Int, shadow: Boolean, scale: Float) { |
| 176 | ui-function / texture | override fun texture(target: RenderTarget, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, alpha: Float) { |
| 186 | ui-function / draw | fun draw(fbo: Int, w: Int, h: Int, topDown: Boolean, clear: Boolean, kept: Boolean = false) { |
| 192 | ui-function / flushPending | private fun flushPending() = flush() |
| 194 | ui-function / replay | private fun replay(lives: List<Live>): List<GlProgram.Segment> = lives.map { l -> |
| 209 | ui-function / take | fun take(renderer: GlRenderer) { |
| 224 | ui-function / idle | private fun idle() { |
| 232 | ui-function / same | private fun same(a: List<GlProgram.Batch>, b: List<GlProgram.Batch>) = a.size == b.size && a.indices.all { a[it] === b[it] } |
| 234 | ui-function / sameLives | private fun sameLives(a: List<Live>, b: List<Live>) = |
| 237 | ui-function / coalesce | private fun coalesce() { |
| 241 | ui-function / emit | fun emit() { |
| 264 | ui-function / draw | fun draw(fbo: Int, w: Int, h: Int, topDown: Boolean, clear: Boolean, vararg layers: Layer) { |
| 292 | ui-function / stale | private fun stale(): Boolean = (live != null && changed()) \|\| batches.any { (it.texture as? Group)?.stale() == true } |
| 294 | ui-function / render | private fun render(composite: GlProgram.Batch, transient: ArrayList<Group>?, sink: ArrayList<Group>) { |
| 322 | ui-function / release | fun release() { |
| 336 | ui-function / of | fun of(batches: List<GlProgram.Batch>, live: (() -> List<GlProgram.Segment>)?, changed: () -> Boolean, alpha: Float, screenW: Int, screenH: Int): Group? { |
| 352 | ui-function / prepare | fun prepare(batches: List<GlProgram.Batch>, transient: ArrayList<Group>?, sink: ArrayList<Group>) { |
| 356 | ui-function / endFrame | fun endFrame() { |
| 361 | ui-function / recycle | private fun recycle(t: IntArray) { |
| 365 | ui-function / acquire | private fun acquire(w: Int, h: Int): IntArray { |
| 381 | ui-function / delete | private fun delete(t: IntArray) { |
| 392 | ui-function / segments | fun segments(lives: List<Live>): List<GlProgram.Segment> { |
| 397 | ui-function / drawBatches | fun drawBatches(fbo: Int, w: Int, h: Int, topDown: Boolean, clear: Boolean, batches: List<GlProgram.Batch>, segments: List<GlProgram.Segment>, kept: Boolean, arena: Boolean) { |
| 426 | ui-function / sameKey | fun sameKey(o: Batch) = texture === o.texture && premultiplied == o.premultiplied && nearest == o.nearest |
| 428 | ui-function / faded | fun faded(alpha: Float) = Batch(texture, premultiplied, nearest, verts, IntArray(colors.size) { i -> |
| 444 | ui-function / merge | fun merge(list: List<Batch>): Batch { |
| 493 | ui-function / init | private fun init() { |
| 520 | ui-function / shader | private fun shader(type: Int, src: String): Int { |
| 528 | ui-function / invalidate | fun invalidate(b: Batch) { |
| 534 | ui-function / draw | fun draw(batches: List<Batch>, w: Int, h: Int, topDown: Boolean, live: List<Segment> = emptyList(), kept: Boolean = false, ox: Float = 0f, oy: Float = 0f) { |
| 578 | ui-function / drawArena | fun drawArena(batches: List<Batch>, w: Int, h: Int, topDown: Boolean, live: List<Segment>) { |
| 642 | ui-function / use | fun use(id: Int) { |
| 673 | ui-function / attribPointers | private fun attribPointers() { |
| 679 | ui-function / setupState | private fun setupState(w: Int, h: Int, topDown: Boolean, ox: Float = 0f, oy: Float = 0f) { |
| 696 | ui-function / drawBatch | private fun drawBatch(b: Batch, first: Int) { |
| 712 | ui-function / filter | private fun filter(mode: Int) { |
| 717 | ui-function / upload | private fun upload(at: Int, batches: List<Batch>): Int { |
| 751 | ui-function / ensureIndices | private fun ensureIndices(quads: Int) { |
| 799 | ui-function / bitmap | fun bitmap(bitmap: PolyBitmap): Tex = bitmaps.getOrPut(bitmap) { |
| 804 | ui-function / external | fun external(id: Int): Tex = external.getOrPut(id) { Tex(null, id, 0) } |
| 806 | ui-function / alive | fun alive(key: Any): Boolean = key !is Tex \|\| key.bitmap == null \|\| bitmaps[key.bitmap] === key |
| 808 | ui-function / id | fun id(key: Any): Int = when (key) { |
| 819 | ui-function / glyphPage | private fun glyphPage(page: PolyGlyphPage.Page): Int { |
| 836 | ui-function / upload | private fun upload(bitmap: PolyBitmap): Int { |
| 845 | ui-function / unpackDefaults | private fun unpackDefaults(rowLength: Int) { |
| 853 | ui-function / newTexture | private fun newTexture(): Int { |
| 863 | ui-function / genTexture | private fun genTexture(): Int { |
| 867 | ui-function / bind | private fun bind(id: Int) { |
| 871 | ui-function / texParameter | private fun texParameter(name: Int, value: Int) { |
| 875 | ui-function / pixelStore | private fun pixelStore(name: Int, value: Int) { |
| 879 | ui-function / texImage | private fun texImage(w: Int, h: Int, px: IntBuffer) { |
| 883 | ui-function / texSubImage | private fun texSubImage(y: Int, w: Int, h: Int, px: IntBuffer) { |
| 887 | ui-function / deleteTexture | private fun deleteTexture(id: Int) { |
| 891 | ui-function / endFrame | fun endFrame() { |

### 1.20.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/MacScrollEvent.kt

`file-9eb4a0f96f0586669312`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 18 | ui-function / readScroll | fun readScroll(): Boolean? = try { |

### 1.20.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/McFontService.kt

`file-253c62121ceb7458fa49`；状态 **pending**；领域 font-language；组合 1.20.1/fabric, 1.20.1/forge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 20 | font-language / McFontService | object McFontService { |
| 23 | ui-function / init | fun init() { |
| 27 | font-language / McResourceFont.init | McResourceFont.init() |
| 30 | ui-function / installBundledFonts | private fun installBundledFonts() { |
| 31 | font-language / McFontService | val loader = McFontService::class.java.classLoader |
| 33 | font-language / UiFontFiles.DEFAULT_REGULAR | "assets/oneconfig/fonts/minecraft/Minecraft-Regular.otf" to UiFontFiles.DEFAULT_REGULAR, |
| 34 | font-language / UiFontFiles.DEFAULT_BOLD | "assets/oneconfig/fonts/minecraft/Minecraft-Bold.otf" to UiFontFiles.DEFAULT_BOLD, |
| 46 | ui-function / installHostFonts | private fun installHostFonts() { |
| 47 | font-language / UiFontFiles.readRegular | val regular = UiFontFiles.readRegular() |
| 54 | font-language / UiFontFiles.REGULAR | FontManager.replace(UiFontFiles.REGULAR, regular) |
| 55 | font-language / UiFontFiles.readBold | val bold = UiFontFiles.readBold() ?: regular |
| 57 | font-language / UiFontFiles.BOLD | FontManager.replace(UiFontFiles.BOLD, bold) |
| 60 | font-language / UiFontFiles.BOLD | FontManager.replace(UiFontFiles.BOLD, regular) |
| 62 | font-language / UiFontFiles.REGULAR | FontManager.setDefault(UiFontFiles.REGULAR) |
| 68 | font-language / UiFontFiles.readFallback | val fallback = UiFontFiles.readFallback() |
| 71 | font-language / UiFontFiles.FALLBACK | FontManager.replace(UiFontFiles.FALLBACK, fallback) |
| 82 | ui-function / installEngines | fun installEngines() { |

### 1.20.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/McResourceFont.kt

`file-0113292edd0124b768e8`；状态 **pending**；领域 font-language；组合 1.20.1/fabric, 1.20.1/forge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 28 | font-language / McResourceFont | internal object McResourceFont { |
| 40 | ui-function / glyph | fun glyph(cp: Int): McGlyph? = glyphs[cp] ?: unihex[cp]?.let { u -> baked.getOrPut(cp) { bake(u) } } |
| 47 | ui-function / init | fun init() { |
| 55 | ui-function / reload | private fun reload() { |
| 74 | ui-function / fontOptions | private fun fontOptions(): Int { |
| 81 | ui-function / load | private fun load(rm: ResourceManager, options: Int): Font? { |
| 87 | ui-function / unclaimed | fun unclaimed(cp: Int) = cp !in glyphs && cp !in unihex |
| 105 | ui-function / collectProviders | private fun collectProviders(rm: ResourceManager, ref: String, out: MutableList<JsonObject>, visited: MutableSet<String>, options: Int) { |
| 121 | ui-function / filterPasses | private fun filterPasses(provider: JsonObject, options: Int): Boolean { |
| 128 | ui-function / loadBitmap | private fun loadBitmap(rm: ResourceManager, provider: JsonObject): Map<Int, McGlyph> { |
| 155 | ui-function / loadUnihex | private fun loadUnihex(rm: ResourceManager, provider: JsonObject): Map<Int, Unihex> { |
| 180 | ui-function / parseHex | private fun parseHex(line: String, overrides: List<IntArray>, out: MutableMap<Int, Unihex>): Boolean { |
| 208 | ui-function / bake | private fun bake(u: Unihex): McGlyph { |
| 220 | ui-function / read | private fun read(rm: ResourceManager, ref: String, folder: String): ByteArray? = |
| 223 | ui-function / id | private fun id(ref: String, folder: String, suffix: String = "") = |

### 1.20.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/MinecraftKeyboardAdapter.kt

`file-1007023a52bc4bb63544`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 13 | ui-function / keyLocation | fun keyLocation(bindingKey: Int): Int  = when (bindingKey) { |
| 20 | ui-function / toAwtKeyCode | fun toAwtKeyCode(shortcutKey: Int): Int = when (shortcutKey) { |

### 1.20.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/UiBlur.kt

`file-198fe8eb5f5d94dcf25c`；状态 **pending**；领域 blur；组合 1.20.1/fabric, 1.20.1/forge。

该文件支撑 blur；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 1.20.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/impls/HudEditorUIScreen.kt

`file-beebd8804f6facb52af4`；状态 **pending**；领域 hud-editor；组合 1.20.1/fabric, 1.20.1/forge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 37 | route / HudEditorUIScreen | class HudEditorUIScreen private constructor() : ComposeScreen() { |
| 42 | ui-function / open | fun open(fromOneConfig: Boolean): HudEditorUIScreen { |
| 57 | ui-function / beginClose | private fun beginClose() { |
| 62 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLOSE) |
| 62 | sound / UiSoundEvent.CLOSE | UiSounds.play(UiSoundEvent.CLOSE) |
| 63 | sound / UiSounds.releaseAmbience | UiSounds.releaseAmbience() |
| 63 | sound / releaseAmbience | UiSounds.releaseAmbience() |
| 66 | ui-function / cancelClose | private fun cancelClose(): Boolean { |
| 70 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.OPEN) |
| 70 | sound / UiSoundEvent.OPEN | UiSounds.play(UiSoundEvent.OPEN) |
| 71 | sound / UiSounds.acquireAmbience | UiSounds.acquireAmbience() |
| 71 | sound / acquireAmbience | UiSounds.acquireAmbience() |
| 79 | ui-function / returnToOneConfig | private fun returnToOneConfig() { |
| 87 | ui-function / init | override fun init() { |
| 96 | sound / UiSounds.acquireAmbience | UiSounds.acquireAmbience() |
| 96 | sound / acquireAmbience | UiSounds.acquireAmbience() |
| 102 | ui-function / removed | override fun removed() { |
| 108 | sound / UiSounds.releaseAmbience | UiSounds.releaseAmbience() |
| 108 | sound / releaseAmbience | UiSounds.releaseAmbience() |
| 114 | ui-function / isPauseScreen | override fun isPauseScreen(): Boolean = OneConfigConfig.pauseGame |
| 116 | ui-function / handleOneConfigKeybind | private fun handleOneConfigKeybind(): Boolean { |
| 128 | ui-function / handleKeyPressed | override fun handleKeyPressed(key: Int, modifiers: Int): Boolean { |
| 137 | keybind / KeybindRecordingBus.isRecording | if (toggleKey != null && key == toggleKey && !KeybindRecordingBus.isRecording) { |
| 143 | ui-function / handleMouseClicked | override fun handleMouseClicked(button: Int): Boolean { |
| 144 | keybind / KeybindRecordingBus.isRecording | if (KeybindRecordingBus.isRecording) return false |
| 147 | keybind / KeybindManager.isTriggeredByMouse | KeybindManager.isTriggeredByMouse(OneConfigConfig.oneConfigKeybind, button) |
| 155 | ui-function / render | override fun render(ctx: GuiGraphics, mouseX: Int, mouseY: Int, tickDelta: Float) { |
| 176 | composable / compose | @Composable |
| 177 | ui-function / compose | override fun compose() { |
| 198 | animation / RetainedVisibility | RetainedVisibility( |
| 200 | animation / tween | enter = tween(200, easing = EaseOutCubic), |
| 201 | animation / tween | exit = tween(exitMs, easing = EaseIn), |

### 1.20.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/impls/OneConfigUIScreen.kt

`file-fc22a76802d64b1d0bf3`；状态 **pending**；领域 shell-navigation；组合 1.20.1/fabric, 1.20.1/forge。

对齐背景模糊开关、强度、HUD 穿透、resize 与关屏后的 GPU/画面恢复。 定位类型: blur 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 80 | route / OneConfigUIScreen | class OneConfigUIScreen @JvmOverloads constructor( |
| 92 | ui-function / blurAlpha | override fun blurAlpha(): Float { |
| 93 | blur / enableBackgroundBlur | if (!OneConfigConfig.enableBackgroundBlur) return 0f |
| 111 | ui-function / shared | private fun shared(): OneConfigUIScreen = |
| 115 | ui-function / forRoute | fun forRoute(route: Any?): OneConfigUIScreen = shared().also { |
| 121 | ui-function / open | fun open(): OneConfigUIScreen = shared().also { |
| 128 | ui-function / resume | fun resume(): OneConfigUIScreen = shared().also { |
| 142 | ui-function / scheduleSave | private fun scheduleSave() { |
| 155 | route / OpeningRoute | private data class OpeningRoute(val route: Any, val restored: Boolean = false) |
| 157 | ui-function / resolveOpeningRoute | private fun resolveOpeningRoute(): OpeningRoute { |
| 177 | ui-function / stopWarmUp | fun stopWarmUp() { |
| 190 | ui-function / dropPrerasteredLater | private fun dropPrerasteredLater() { |
| 197 | ui-function / prepareWarmUp | fun prepareWarmUp(): Runnable? { |
| 212 | ui-function / warmUp | private fun warmUp(w: Int, h: Int, density: Float, surface: Float) { |
| 222 | ui-function / warmFrames | private fun warmFrames(w: Int, h: Int, density: Float, surface: Float) { |
| 227 | ui-function / isDispatchNeeded | override fun isDispatchNeeded(context: CoroutineContext) = Thread.currentThread() !== thread |
| 228 | ui-function / dispatch | override fun dispatch(context: CoroutineContext, block: Runnable) { |
| 232 | ui-function / drain | fun drain() { |
| 292 | ui-function / openLastSession | fun openLastSession() { |
| 303 | ui-function / beginClose | private fun beginClose() { |
| 308 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLOSE) |
| 308 | sound / UiSoundEvent.CLOSE | UiSounds.play(UiSoundEvent.CLOSE) |
| 309 | sound / UiSounds.releaseAmbience | UiSounds.releaseAmbience() |
| 309 | sound / releaseAmbience | UiSounds.releaseAmbience() |
| 312 | ui-function / cancelClose | private fun cancelClose(): Boolean { |
| 323 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.OPEN) |
| 323 | sound / UiSoundEvent.OPEN | UiSounds.play(UiSoundEvent.OPEN) |
| 324 | sound / UiSounds.acquireAmbience | UiSounds.acquireAmbience() |
| 324 | sound / acquireAmbience | UiSounds.acquireAmbience() |
| 340 | ui-function / markClosed | private fun markClosed() { |
| 344 | ui-function / init | override fun init() { |
| 416 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.OPEN) |
| 416 | sound / UiSoundEvent.OPEN | UiSounds.play(UiSoundEvent.OPEN) |
| 417 | sound / UiSounds.acquireAmbience | UiSounds.acquireAmbience() |
| 417 | sound / acquireAmbience | UiSounds.acquireAmbience() |
| 427 | ui-function / onSceneRebuilding | override fun onSceneRebuilding() { |
| 433 | ui-function / removed | override fun removed() { |
| 438 | sound / UiSounds.releaseAmbience | UiSounds.releaseAmbience() |
| 438 | sound / releaseAmbience | UiSounds.releaseAmbience() |
| 444 | ui-function / isPauseScreen | override fun isPauseScreen(): Boolean = OneConfigConfig.pauseGame |
| 446 | ui-function / handleOneConfigKeybind | private fun handleOneConfigKeybind(): Boolean { |
| 458 | ui-function / handleKeyPressed | override fun handleKeyPressed(key: Int, modifiers: Int): Boolean { |
| 467 | keybind / KeybindRecordingBus.isRecording | if (toggleKey != null && key == toggleKey && !KeybindRecordingBus.isRecording) { |
| 474 | ui-function / handleMouseClicked | override fun handleMouseClicked(button: Int): Boolean { |
| 475 | keybind / KeybindRecordingBus.isRecording | if (KeybindRecordingBus.isRecording) return false |
| 478 | keybind / KeybindManager.isTriggeredByMouse | KeybindManager.isTriggeredByMouse(OneConfigConfig.oneConfigKeybind, button) |
| 485 | keybind / KeybindManager.hasTriggeredMouseBind | if (KeybindManager.hasTriggeredMouseBind(button)) return true |
| 486 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLICK) |
| 486 | sound / UiSoundEvent.CLICK | UiSounds.play(UiSoundEvent.CLICK) |
| 491 | keybind / KeybindManager.hasTriggeredMouseBind | if (KeybindManager.hasTriggeredMouseBind(button)) return true |
| 492 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLICK) |
| 492 | sound / UiSoundEvent.CLICK | UiSounds.play(UiSoundEvent.CLICK) |
| 502 | ui-function / render | override fun render(ctx: GuiGraphics, mouseX: Int, mouseY: Int, tickDelta: Float) { |
| 525 | ui-function / easeOutExpo | private fun easeOutExpo(progress: Float): Float { |
| 533 | composable / compose | @Composable |
| 534 | ui-function / compose | override fun compose() { |
| 566 | ui-function / submit | override fun submit(texture: Any, premultiplied: Boolean, nearest: Boolean, verts: FloatArray, colors: IntArray, bounds: FloatArray) {} |
| 568 | ui-function / bitmapTexture | override fun bitmapTexture(bitmap: PolyBitmap): Any = bitmap |
| 570 | ui-function / live | override fun live(draw: (PolyRenderer) -> Unit) = draw(this) |

### 1.20.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/opengl/StoredGLState.kt

`file-96d17f9e942e6aa452d2`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 10 | ui-function / resyncTextureBindCache | fun resyncTextureBindCache() { |
| 21 | ui-function / capture | fun capture(): StoredGLState { |
| 102 | ui-function / restore | fun restore(): StoredGLState { |
| 195 | ui-function / forceToggle | private fun forceToggle(enabled: Boolean, enable: () -> Unit, disable: () -> Unit) { |
| 205 | ui-function / forceDepthFunc | private fun forceDepthFunc(func: Int) { |
| 211 | ui-function / forceDepthMask | private fun forceDepthMask(mask: Boolean) { |

### 1.20.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/CompatOverlayRenderer.kt

`file-d00b3f588b2198b85583`；状态 **pending**；领域 hud-editor；组合 1.20.1/fabric, 1.20.1/forge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 14 | ui-function / register | fun register(hook: (GuiGraphics) -> Unit) { |
| 19 | ui-function / hasHooks | fun hasHooks(): Boolean = hooks.isNotEmpty() |
| 22 | ui-function / oneConfigScreenOpen | fun oneConfigScreenOpen(): Boolean = HudManager.isEditing |
| 25 | ui-function / render | fun render(ctx: GuiGraphics) { |

### 1.20.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/LegacyHudRenderer.kt

`file-898c824dfc01b9dcda68`；状态 **pending**；领域 hud-editor；组合 1.20.1/fabric, 1.20.1/forge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 32 | ui-function / reportFailure | private fun reportFailure(hud: LegacyHud, e: Throwable) { |
| 47 | ui-function / renderLive | fun renderLive(graphics: GuiGraphics) { |
| 52 | ui-function / renderLiveHuds | private fun renderLiveHuds(graphics: GuiGraphics) { |

### 1.20.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/keybind/MinecraftKeybindProvider.kt

`file-90aab04992d7139deaa2`；状态 **pending**；领域 keybind；组合 1.20.1/fabric, 1.20.1/forge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 22 | keybind / OneConfigKeybind | private val properties = IdentityHashMap<KeyMapping, Property<OneConfigKeybind>>() |
| 37 | ui-function / groups | override fun groups(): List<KeybindGroup> { |
| 77 | ui-function / managedMappings | fun managedMappings(): List<KeyMapping> { |
| 82 | keybind / OneConfigKeybind | private fun propertyFor(mapping: KeyMapping): Property<OneConfigKeybind> { |
| 82 | ui-function / propertyFor | private fun propertyFor(mapping: KeyMapping): Property<OneConfigKeybind> { |
| 89 | keybind / OneConfigKeybind | OneConfigKeybind::class.java, |
| 99 | ui-function / ownerMod | private fun ownerMod(mapping: KeyMapping, modsById: Map<String, ModInfo>): ModInfo? { |
| 105 | ui-function / ownerCandidates | private fun ownerCandidates(keyName: String): Sequence<String> = sequence { |
| 117 | ui-function / modIcon | private fun modIcon(owner: ModInfo?): String? = |
| 124 | ui-function / minecraftIcon | private fun minecraftIcon(): String { |
| 132 | ui-function / extractMinecraftIcon | private fun extractMinecraftIcon(): String? = runCatching { |
| 148 | ui-function / groupTitle | private fun groupTitle(category: String, owner: ModInfo?): String { |
| 154 | ui-function / sanitizeId | private fun sanitizeId(value: String): String { |
| 161 | keybind / OneConfigKeybind | private fun KeyMapping.toOneConfigKeybind(): OneConfigKeybind { |
| 161 | ui-function / toOneConfigKeybind | private fun KeyMapping.toOneConfigKeybind(): OneConfigKeybind { |
| 166 | keybind / Key.toOneConfigKeybind | private fun InputConstants.Key.toOneConfigKeybind(): OneConfigKeybind { |
| 166 | keybind / OneConfigKeybind | private fun InputConstants.Key.toOneConfigKeybind(): OneConfigKeybind { |
| 166 | ui-function / toOneConfigKeybind | private fun InputConstants.Key.toOneConfigKeybind(): OneConfigKeybind { |
| 171 | keybind / OneConfigKeybind | OneConfigKeybind(intArrayOf(value), null, KeyModifiers.NONE, 0L) { true } |
| 174 | keybind / OneConfigKeybind | OneConfigKeybind(null, intArrayOf(value), KeyModifiers.NONE, 0L) { true } |
| 175 | keybind / OneConfigKeybind | else -> OneConfigKeybind(null, null, KeyModifiers.NONE, 0L) { true } |
| 179 | keybind / OneConfigKeybind | private fun KeyMapping.applyOneConfigKeybind(keybind: OneConfigKeybind?) { |
| 179 | ui-function / applyOneConfigKeybind | private fun KeyMapping.applyOneConfigKeybind(keybind: OneConfigKeybind?) { |
| 193 | ui-function / categoryLabel | private fun categoryLabel(mapping: KeyMapping): String { |
| 197 | ui-function / translate | private fun translate(key: String): String { |
| 209 | ui-function / isOneConfigMirror | private fun isOneConfigMirror(@Suppress("UNUSED_PARAMETER") mapping: KeyMapping): Boolean = false |

### 1.20.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/sound/McUiSoundService.kt

`file-0257fe67be7a3d15f2dd`；状态 **pending**；领域 sound；组合 1.20.1/fabric, 1.20.1/forge。

对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 19 | sound / McUiSoundService | class McUiSoundService : UiSoundService { |
| 19 | sound / UiSoundService | class McUiSoundService : UiSoundService { |
| 28 | sound / UiSoundTheme | private val ambienceShuffle = mutableMapOf<UiSoundTheme, MutableList<String>>() |
| 29 | sound / UiSoundTheme | private val lastAmbienceSoundId = mutableMapOf<UiSoundTheme, String>() |
| 33 | sound / UiSoundEvent | override fun play(event: UiSoundEvent, theme: UiSoundTheme, volume: Float) { |
| 33 | sound / UiSoundTheme | override fun play(event: UiSoundEvent, theme: UiSoundTheme, volume: Float) { |
| 33 | ui-function / play | override fun play(event: UiSoundEvent, theme: UiSoundTheme, volume: Float) { |
| 37 | sound / UiSoundTheme.MODERN | UiSoundTheme.MODERN -> playModern(event, volume) |
| 38 | sound / UiSoundTheme.MINECRAFT | UiSoundTheme.MINECRAFT -> playMinecraft(event, volume) |
| 44 | sound / UiSoundEvent | private fun playModern(event: UiSoundEvent, volume: Float) { |
| 44 | ui-function / playModern | private fun playModern(event: UiSoundEvent, volume: Float) { |
| 46 | sound / UiSoundEvent.OPEN | UiSoundEvent.OPEN -> playModernSound("ui.open", 1f, volume) |
| 47 | sound / UiSoundEvent.CLOSE | UiSoundEvent.CLOSE -> playModernSound("ui.close", 1f, volume) |
| 48 | sound / UiSoundEvent.CLICK | UiSoundEvent.CLICK -> playModernSound("ui.click", 1f, volume) |
| 49 | sound / UiSoundEvent.SLIDER_TICK | UiSoundEvent.SLIDER_TICK -> playModernSound("ui.slider", 1f, volume) |
| 50 | sound / UiSoundEvent.HUD_SELECT | UiSoundEvent.HUD_SELECT -> playModernSound("ui.click", 1.0f, volume) |
| 51 | sound / UiSoundEvent.HUD_DRAG_START | UiSoundEvent.HUD_DRAG_START -> playModernSound("ui.click", 0.9f, volume) |
| 52 | sound / UiSoundEvent.HUD_DRAG_END | UiSoundEvent.HUD_DRAG_END -> playModernSound("ui.click", 1.1f, volume) |
| 53 | sound / UiSoundEvent.HUD_RESIZE_END | UiSoundEvent.HUD_RESIZE_END -> playModernSound("ui.click", 1.0f, volume) |
| 58 | ui-function / playModernSound | private fun playModernSound(id: String, pitch: Float, volume: Float) { |
| 63 | sound / UiSoundEvent | private fun playMinecraft(event: UiSoundEvent, volume: Float) { |
| 63 | ui-function / playMinecraft | private fun playMinecraft(event: UiSoundEvent, volume: Float) { |
| 65 | sound / UiSoundEvent.CLICK | UiSoundEvent.CLICK -> playVanilla(SoundEvents.UI_BUTTON_CLICK, 1f, 0.5f * volume) |
| 66 | sound / UiSoundEvent.OPEN | UiSoundEvent.OPEN -> playModernSound("ui.open", 1f, volume) |
| 67 | sound / UiSoundEvent.CLOSE | UiSoundEvent.CLOSE -> playModernSound("ui.close", 1f, volume) |
| 68 | sound / UiSoundEvent.SLIDER_TICK | UiSoundEvent.SLIDER_TICK -> { |
| 73 | sound / UiSoundEvent.HUD_SELECT | UiSoundEvent.HUD_SELECT -> playVanilla(SoundEvents.UI_BUTTON_CLICK, 1f, 0.3f * volume) |
| 74 | sound / UiSoundEvent.HUD_DRAG_END | UiSoundEvent.HUD_DRAG_END -> playVanilla(SoundEvents.UI_BUTTON_CLICK, 1f, 0.3f * volume) |
| 75 | sound / UiSoundEvent.HUD_RESIZE_END | UiSoundEvent.HUD_RESIZE_END -> playVanilla(SoundEvents.UI_BUTTON_CLICK, 1f, 0.3f * volume) |
| 80 | ui-function / playVanilla | private fun playVanilla(event: SoundEvent, pitch: Float, volume: Float) { |
| 85 | ui-function / playVanilla | private fun playVanilla(holder: Holder<SoundEvent>, pitch: Float, volume: Float) = |
| 88 | sound / startAmbience | override fun startAmbience(theme: UiSoundTheme, volume: Float) { |
| 88 | sound / UiSoundTheme | override fun startAmbience(theme: UiSoundTheme, volume: Float) { |
| 88 | ui-function / startAmbience | override fun startAmbience(theme: UiSoundTheme, volume: Float) { |
| 109 | sound / stopAmbience | override fun stopAmbience(@Suppress("UNUSED_PARAMETER") setDucking: Boolean) { |
| 109 | ui-function / stopAmbience | override fun stopAmbience(@Suppress("UNUSED_PARAMETER") setDucking: Boolean) { |
| 118 | sound / UiSoundTheme | private fun ambienceVolumeFor(theme: UiSoundTheme, volume: Float): Float = when (theme) { |
| 118 | ui-function / ambienceVolumeFor | private fun ambienceVolumeFor(theme: UiSoundTheme, volume: Float): Float = when (theme) { |
| 119 | sound / UiSoundTheme.MODERN | UiSoundTheme.MODERN -> 0.5f * volume |
| 120 | sound / UiSoundTheme.MINECRAFT | UiSoundTheme.MINECRAFT -> 0.08f * volume |
| 123 | sound / UiSoundTheme | private fun ambienceSoundIds(theme: UiSoundTheme): List<String> = when (theme) { |
| 123 | ui-function / ambienceSoundIds | private fun ambienceSoundIds(theme: UiSoundTheme): List<String> = when (theme) { |
| 124 | sound / UiSoundTheme.MODERN | UiSoundTheme.MODERN -> listOf("ui.pad_loop") |
| 125 | sound / UiSoundTheme.MINECRAFT | UiSoundTheme.MINECRAFT -> listOf( |
| 133 | sound / UiSoundTheme | private fun playAmbienceInstance(theme: UiSoundTheme, targetVolume: Float): FadingLoopSoundInstance { |
| 133 | ui-function / playAmbienceInstance | private fun playAmbienceInstance(theme: UiSoundTheme, targetVolume: Float): FadingLoopSoundInstance { |
| 144 | sound / UiSoundTheme | private fun nextAmbienceSoundId(theme: UiSoundTheme, ids: List<String>): String { |
| 144 | ui-function / nextAmbienceSoundId | private fun nextAmbienceSoundId(theme: UiSoundTheme, ids: List<String>): String { |
| 165 | ui-function / driveAmbienceLoop | private fun driveAmbienceLoop() { |
| 196 | sound / UiSoundTheme | val theme: UiSoundTheme, |
| 215 | ui-function / beginFadeOut | fun beginFadeOut() { |
| 219 | ui-function / cancelFadeOut | fun cancelFadeOut() { |
| 224 | ui-function / updateTargetVolume | fun updateTargetVolume(newTarget: Float) { |
| 229 | ui-function / tick | override fun tick() { |

### 1.21.1/fabric/src/main/java/org/polyfrost/oneconfig/internal/OneConfig.java

`file-d4c59b8997e1242227b0`；状态 **pending**；领域 ui-support；组合 1.21.1/fabric。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 239 | search-provider / SearchCorpus.INSTANCE | EventManager.register(ResourceFinishedLoading.class, e -> SearchCorpus.INSTANCE.init()); |
| 338 | font-language / McFontService.INSTANCE | McFontService.INSTANCE.init(); |

### 1.21.1/fabric/src/main/java/org/polyfrost/oneconfig/internal/OneConfigPreLaunch.java

`file-aa3c03f6e80af28bf835`；状态 **pending**；领域 ui-support；组合 1.21.1/fabric。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 25 | font-language / McFontService.installEngines | McFontService.installEngines(); |

### 1.21.1/neoforge/src/main/java/org/polyfrost/oneconfig/internal/OneConfig.java

`file-607b26299e487888330c`；状态 **pending**；领域 ui-support；组合 1.21.1/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 238 | search-provider / SearchCorpus.INSTANCE | EventManager.register(ResourceFinishedLoading.class, e -> SearchCorpus.INSTANCE.init()); |
| 348 | font-language / McFontService.INSTANCE | McFontService.INSTANCE.init(); |

### 1.21.1/neoforge/src/main/java/org/polyfrost/oneconfig/internal/OneConfigPreLaunch.java

`file-14a8bb04c7abe69ad6f2`；状态 **pending**；领域 ui-support；组合 1.21.1/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 22 | font-language / McFontService.installEngines | org.polyfrost.oneconfig.internal.ui.compose.McFontService.installEngines(); |

### 1.21.1/src/main/java/org/polyfrost/oneconfig/api/platform/v1/internal/I18nPlatformImpl.java

`file-e8da40154fd38c74501c`；状态 **pending**；领域 ui-support；组合 1.21.1/fabric, 1.21.1/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 39 | font-language / I18nPlatform | public class I18nPlatformImpl implements I18nPlatform { |

### 1.21.1/src/main/java/org/polyfrost/oneconfig/api/platform/v1/internal/KeysImpl.java

`file-c691bfc771d2c2a1cf75`；状态 **pending**；领域 ui-support；组合 1.21.1/fabric, 1.21.1/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 21 | keybind / getKeyA | public int getKeyA() { |
| 26 | keybind / getKeyC | public int getKeyC() { |
| 31 | keybind / getKeyD | public int getKeyD() { |
| 36 | keybind / getKeyE | public int getKeyE() { |
| 41 | keybind / getKeyF | public int getKeyF() { |
| 46 | keybind / getKeyH | public int getKeyH() { |
| 51 | keybind / getKeyL | public int getKeyL() { |
| 56 | keybind / getKeyR | public int getKeyR() { |
| 61 | keybind / getKeyV | public int getKeyV() { |
| 66 | keybind / getKeyX | public int getKeyX() { |
| 71 | keybind / getKeyY | public int getKeyY() { |
| 76 | keybind / getKeyZ | public int getKeyZ() { |
| 81 | keybind / getKeyDelete | public int getKeyDelete() { |
| 91 | keybind / getKeyLeftShift | public int getKeyLeftShift() { |
| 96 | keybind / getKeyRightShift | public int getKeyRightShift() { |
| 101 | keybind / getKeyLeftControl | public int getKeyLeftControl() { |
| 106 | keybind / getKeyRightControl | public int getKeyRightControl() { |
| 111 | keybind / getKeyLeftAlt | public int getKeyLeftAlt() { |
| 116 | keybind / getKeyRightAlt | public int getKeyRightAlt() { |
| 121 | keybind / getKeyLeftSuper | public int getKeyLeftSuper() { |
| 126 | keybind / getKeyRightSuper | public int getKeyRightSuper() { |

### 1.21.1/src/main/java/org/polyfrost/oneconfig/api/ui/v1/api/internal/TinyFdApiImpl.java

`file-3879cb91a82715bb56fa`；状态 **pending**；领域 ui-support；组合 1.21.1/fabric, 1.21.1/neoforge。

核对打开/取消/确认、焦点、边界定位、外点关闭与提交后可见结果。 定位类型: dialog-or-popup

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 74 | dialog-or-popup / Dialog | private static <T> T open(String description, T fallback, Dialog<T> dialog) { |
| 151 | dialog-or-popup / tinyfd_openFileDialog | String result = TinyFileDialogs.tinyfd_openFileDialog(sanitizeText(title), sanitizePath(defaultFilePath), filters(stack, filterPatterns), sanitizeText(filterDescription), false); |
| 161 | dialog-or-popup / tinyfd_saveFileDialog | String result = TinyFileDialogs.tinyfd_saveFileDialog(sanitizeText(title), sanitizePath(defaultFilePath), filters(stack, filterPatterns), sanitizeText(filterDescription)); |
| 171 | dialog-or-popup / tinyfd_openFileDialog | String result = TinyFileDialogs.tinyfd_openFileDialog(sanitizeText(title), sanitizePath(defaultFilePath), filters(stack, filterPatterns), sanitizeText(filterDescription), true); |
| 187 | dialog-or-popup / tinyfd_selectFolderDialog | firstPath(TinyFileDialogs.tinyfd_selectFolderDialog(sanitizeText(title), sanitizePath(defaultFolderPath)))); |
| 199 | dialog-or-popup / tinyfd_notifyPopup | TinyFileDialogs.tinyfd_notifyPopup(sanitizeText(title), sanitizeText(message), icon) == 1 ? 0 : 1); |
| 271 | dialog-or-popup / Dialog | private interface Dialog<T> { |

### 1.21.1/src/main/java/org/polyfrost/oneconfig/api/ui/v1/keybind/internal/MinecraftKeybindCodec.java

`file-0a73708cc8eca6b2bf64`；状态 **pending**；领域 keybind；组合 1.21.1/fabric, 1.21.1/neoforge。

该文件支撑 keybind；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 1.21.1/src/main/java/org/polyfrost/oneconfig/internal/mixin/render/Mixin_UiBlur.java

`file-20340203ae66c51f363c`；状态 **pending**；领域 blur；组合 1.21.1/fabric, 1.21.1/neoforge。

该文件支撑 blur；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 1.21.1/src/main/java/org/polyfrost/oneconfig/internal/ui/PlayerHeadBlit.java

`file-0dbdd2bc3237a2ebe09d`；状态 **pending**；领域 ui-support；组合 1.21.1/fabric, 1.21.1/neoforge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 1.21.1/src/main/java/org/polyfrost/oneconfig/internal/ui/PlayerHeadSkinAccess.java

`file-77b5c841da96b51db806`；状态 **pending**；领域 ui-support；组合 1.21.1/fabric, 1.21.1/neoforge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 1.21.1/src/main/kotlin/org/polyfrost/oneconfig/api/hud/v1/LegacyHud.kt

`file-ac2bf7987075e87f9056`；状态 **pending**；领域 hud-editor；组合 1.21.1/fabric, 1.21.1/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 14 | ui-function / render | abstract fun render(mcCtx: GuiGraphics) |
| 18 | ui-function / minimumSize | override fun minimumSize(): Pair<Float, Float> = width to height |
| 20 | composable / Content | @Composable |
| 21 | ui-function / Content | override fun Content() {} |

### 1.21.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/GuiProjection.kt

`file-0577933bc8a139136f20`；状态 **pending**；领域 ui-support；组合 1.21.1/fabric, 1.21.1/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 9 | ui-function / withGuiProjection | internal inline fun withGuiProjection(guiWidth: Int, guiHeight: Int, render: () -> Unit) { |

### 1.21.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/OffscreenTarget.kt

`file-48daf75b402072425370`；状态 **pending**；领域 ui-support；组合 1.21.1/fabric, 1.21.1/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 24 | ui-function / resolve | fun resolve(w: Int, h: Int): Boolean { |
| 46 | ui-function / clear | fun clear() { |
| 53 | ui-function / destroy | fun destroy() { |
| 60 | ui-function / dispose | fun dispose() { |
| 70 | ui-function / destroyAll | fun destroyAll() { |
| 83 | ui-function / texture | fun texture(target: RenderTarget, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, alpha: Float) |

### 1.21.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/RenderTargetFbo.kt

`file-cdbb0714c4739cefde5b`；状态 **pending**；领域 ui-support；组合 1.21.1/fabric, 1.21.1/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 21 | ui-function / getFboId | fun getFboId(frameBuffer: RenderTarget): Int { |
| 25 | ui-function / getColorTexId | fun getColorTexId(frameBuffer: RenderTarget): Int = frameBuffer.colorTextureId |
| 27 | ui-function / saveBoundTarget | fun saveBoundTarget() = IntArray(5).also { |
| 33 | ui-function / restoreBoundTarget | fun restoreBoundTarget(s: IntArray) { |

### 1.21.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/BlazeRenderer.kt

`file-9c48b9c66dc5f770ae00`；状态 **pending**；领域 ui-support；组合 1.21.1/fabric, 1.21.1/neoforge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 1.21.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/ComposeSceneContextImpl.kt

`file-94007e3482a52b209a8b`；状态 **pending**；领域 ui-support；组合 1.21.1/fabric, 1.21.1/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 28 | ui-function / requestInputMode | override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard |
| 37 | ui-function / isKeyDown | private fun isKeyDown(key: Int): Boolean { |
| 43 | keybind / isCtrlPressed | isCtrlPressed = isKeyDown(InputConstants.KEY_LCONTROL) \|\| isKeyDown(InputConstants.KEY_RCONTROL), |
| 44 | keybind / isShiftPressed | isShiftPressed = isKeyDown(InputConstants.KEY_LSHIFT) \|\| isKeyDown(InputConstants.KEY_RSHIFT), |
| 45 | keybind / isAltPressed | isAltPressed = isKeyDown(InputConstants.KEY_LALT) \|\| isKeyDown(InputConstants.KEY_RALT), |
| 49 | keybind / isMetaPressed | isMetaPressed = isKeyDown(Platform.compatibility().keys().keyLeftSuper) \|\| isKeyDown(Platform.compatibility().keys().keyRightSuper) |
| 74 | ui-function / setPointerIcon | override fun setPointerIcon(pointerIcon: PointerIcon) { |
| 81 | ui-function / resetPointerIcon | fun resetPointerIcon() { |
| 85 | ui-function / applyPointerIcon | private fun applyPointerIcon(pointerIcon: PointerIcon) { |
| 94 | ui-function / requestFocus | override fun requestFocus(): Boolean { |
| 99 | ui-function / allowCursorChanges | private fun allowCursorChanges(): Boolean { |
| 116 | ui-function / sync | fun sync() {} |
| 117 | ui-function / close | fun close() {} |
| 124 | ui-function / resetPointerIcon | fun resetPointerIcon() { |
| 128 | ui-function / updateContainerSize | fun updateContainerSize(width: Int, height: Int) { |

### 1.21.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/ComposeScreen.kt

`file-6930ac1a391a764d3adf`；状态 **pending**；领域 ui-support；组合 1.21.1/fabric, 1.21.1/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 63 | ui-function / getText | override fun getText(): AnnotatedString? { |
| 72 | ui-function / setText | override fun setText(annotatedString: AnnotatedString) { |
| 79 | ui-function / hasText | override fun hasText(): Boolean { |
| 90 | ui-function / getClipEntry | override suspend fun getClipEntry(): ClipEntry? { |
| 96 | ui-function / setClipEntry | override suspend fun setClipEntry(clipEntry: ClipEntry?) { |
| 109 | route / ComposeScreen | abstract class ComposeScreen( |
| 119 | composable / compose | @Composable |
| 120 | ui-function / compose | abstract fun compose() |
| 143 | ui-function / liveScene | private fun liveScene(): ComposeScene? { |
| 148 | ui-function / withScene | private inline fun <T> withScene(block: (ComposeScene) -> T): T? { |
| 163 | ui-function / sendKeyEventSafely | private inline fun sendKeyEventSafely(build: () -> androidx.compose.ui.input.key.KeyEvent): Boolean { |
| 174 | ui-function / poisonScene | private fun poisonScene(cause: Throwable) { |
| 193 | ui-function / onSceneRebuilding | protected open fun onSceneRebuilding() {} |
| 195 | ui-function / ensureScene | private fun ensureScene(): ComposeScene? { |
| 211 | ui-function / closeSceneQuietly | private fun closeSceneQuietly() { |
| 236 | ui-function / createScene | private fun createScene(): ComposeScene { |
| 280 | ui-function / init | override fun init() { |
| 309 | ui-function / bindContent | private fun bindContent(): Boolean { |
| 316 | ui-function / setContentOn | private fun setContentOn(scene: ComposeScene) { |
| 331 | ui-function / reportUnavailableAndClose | private fun reportUnavailableAndClose() { |
| 338 | ui-function / closeWithMessage | private fun closeWithMessage(reason: String) { |
| 345 | ui-function / resize | override fun resize( |
| 355 | ui-function / isPauseScreen | override fun isPauseScreen(): Boolean = false |
| 363 | ui-function / renderBackground | override fun renderBackground(ctx: GuiGraphics, mouseX: Int, mouseY: Int, tickDelta: Float) { |
| 369 | ui-function / blurAlpha | protected open fun blurAlpha(): Float = 0f |
| 376 | ui-function / disposeScene | private fun disposeScene() { |
| 381 | ui-function / releaseScene | private fun releaseScene() { |
| 389 | ui-function / onClose | override fun onClose() { |
| 394 | ui-function / removed | override fun removed() { |
| 406 | ui-function / render | override fun render(ctx: GuiGraphics, mouseX: Int, mouseY: Int, tickDelta: Float) { |
| 468 | ui-function / paint | private fun paint(ctx: RenderContext, nanos: Long) { |
| 485 | ui-function / mouseClicked | override fun mouseClicked(x: Double, y: Double, button: Int): Boolean { |
| 486 | keybind / KeybindRecordingBus.consumeMouse | if (KeybindRecordingBus.consumeMouse(button, true) \|\| handleMouseClicked(button)) { |
| 496 | ui-function / mouseReleased | override fun mouseReleased(x: Double, y: Double, button: Int): Boolean { |
| 497 | keybind / KeybindRecordingBus.consumeMouse | val recordingConsumed = KeybindRecordingBus.consumeMouse(button, false) |
| 508 | ui-function / mouseScrolled | override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean { |
| 513 | ui-function / handleMouseClicked | protected open fun handleMouseClicked(button: Int): Boolean = false |
| 521 | ui-function / releaseHeldButtons | private fun releaseHeldButtons() { |
| 529 | ui-function / sendMouseButtonEvent | private fun sendMouseButtonEvent(type: PointerEventType, button: Int) { |
| 544 | ui-function / sendScrollEvent | private fun sendScrollEvent(scrollX: Double, scrollY: Double) { |
| 566 | ui-function / isPreciseScroll | private fun isPreciseScroll(scrollX: Double, scrollY: Double, now: Long, macPrecise: Boolean?): Boolean { |
| 578 | ui-function / charTyped | override fun charTyped(char: Char, modifiers: Int): Boolean { |
| 584 | ui-function / ctrlDown | fun Int.ctrlDown() = this and InputConstants.MOD_CONTROL != 0 |
| 585 | ui-function / shiftDown | fun Int.shiftDown() = this and GLFW.GLFW_MOD_SHIFT != 0 |
| 586 | ui-function / altDown | fun Int.altDown() = this and GLFW.GLFW_MOD_ALT != 0 |
| 587 | ui-function / superDown | fun Int.superDown() = this and GLFW.GLFW_MOD_SUPER != 0 |
| 589 | ui-function / handleKeyPressed | protected open fun handleKeyPressed(key: Int, modifiers: Int): Boolean = false |
| 594 | ui-function / keyPressed | override fun keyPressed(key: Int, scanCode: Int, modifiers: Int): Boolean { |
| 601 | ui-function / keyReleased | override fun keyReleased(key: Int, scanCode: Int, modifiers: Int): Boolean { |
| 608 | ui-function / dispatchKeyPressed | private fun dispatchKeyPressed(bindingKey: Int, shortcutKey: Int, modifiers: Int): Boolean { |
| 610 | keybind / KeybindRecordingBus.consumeEscape | if (KeybindRecordingBus.consumeEscape()) { |
| 614 | keybind / KeybindRecordingBus.isRecording | if (KeybindRecordingBus.isRecording) { |
| 626 | ui-function / sendKeyPressedEvent | private fun sendKeyPressedEvent(bindingKey: Int, shortcutKey: Int, modifiers: Int): Boolean { |
| 636 | keybind / isCtrlPressed | isCtrlPressed = modifiers.ctrlDown(), |
| 637 | keybind / isShiftPressed | isShiftPressed = modifiers.shiftDown(), |
| 638 | keybind / isAltPressed | isAltPressed = modifiers.altDown(), |
| 639 | keybind / isMetaPressed | isMetaPressed = modifiers.superDown(), |
| 653 | ui-function / sendKeyReleasedEvent | private fun sendKeyReleasedEvent(bindingKey: Int, shortcutKey: Int, modifiers: Int): Boolean { |
| 661 | keybind / isCtrlPressed | isCtrlPressed = modifiers.ctrlDown(), |
| 662 | keybind / isShiftPressed | isShiftPressed = modifiers.shiftDown(), |
| 663 | keybind / isAltPressed | isAltPressed = modifiers.altDown(), |
| 664 | keybind / isMetaPressed | isMetaPressed = modifiers.superDown(), |
| 678 | ui-function / sendCharacterEvent | private fun sendCharacterEvent(char: Char, codePoint: Int, modifiers: Int): Boolean { |
| 690 | ui-function / sendCharKeyEvent | private fun sendCharKeyEvent(char: Char, codePoint: Int, modifiers: Int): Boolean { |
| 696 | keybind / isCtrlPressed | isCtrlPressed = modifiers.ctrlDown(), |
| 697 | keybind / isShiftPressed | isShiftPressed = modifiers.shiftDown(), |
| 698 | keybind / isAltPressed | isAltPressed = modifiers.altDown(), |
| 699 | keybind / isMetaPressed | isMetaPressed = modifiers.superDown(), |
| 713 | ui-function / modifiersToAwt | private fun modifiersToAwt(modifiers: Int): Int { |
| 722 | ui-function / syncSceneMetrics | private fun syncSceneMetrics(): Boolean { |
| 751 | ui-function / surfaceScale | private fun surfaceScale(): Float { |
| 762 | ui-function / pointerPosition | protected fun pointerPosition(): Offset { |
| 783 | ui-function / isWheelStep | fun isWheelStep(value: Double) = abs(value * 120 - round(value * 120)) < 1e-3 |
| 789 | ui-function / sceneDensity | internal fun sceneDensity(): Float { |
| 808 | ui-function / isDispatchNeeded | override fun isDispatchNeeded(context: CoroutineContext): Boolean = |
| 811 | ui-function / dispatch | override fun dispatch(context: CoroutineContext, block: Runnable) { |

### 1.21.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/GlHost.kt

`file-73925c4b1187da5c3644`；状态 **pending**；领域 ui-support；组合 1.21.1/fabric, 1.21.1/neoforge。

对齐背景模糊开关、强度、HUD 穿透、resize 与关屏后的 GPU/画面恢复。 定位类型: blur 从实际宿主入口触发，核对完整状态变化、交互、退出及重进后的结果。 定位类型: peripheral-ui 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 67 | ui-function / unavailableReason | fun unavailableReason(): String? { |
| 81 | ui-function / setGlTexId | fun setGlTexId(id: Int) { this.id = id } |
| 82 | ui-function / load | override fun load(manager: ResourceManager) {} |
| 83 | ui-function / close | override fun close() { this.id = -1 } |
| 87 | ui-function / blitHud | fun blitHud(guiGraphics: GuiGraphics) { |
| 131 | ui-function / screen | fun screen(dirty: Boolean, blurAlpha: Float, underlay: GuiDraw?, paint: (RenderContext) -> Unit) { |
| 152 | ui-function / deferDebugScreen | fun deferDebugScreen(overlay: DebugScreenOverlay): Boolean { |
| 159 | ui-function / drawBeforeBlit | fun drawBeforeBlit() { |
| 165 | ui-function / drawAtPresent | fun drawAtPresent() { |
| 173 | ui-function / draw | private fun draw(fbo: Int) { |
| 179 | blur / GlBlur.release | GlBlur.release() |
| 188 | peripheral-ui / NotificationsManager.count | if (NotificationsManager.count > 0) drawGl(fbo, null) { |
| 198 | ui-function / drawGui | private fun drawGui(draw: GuiDraw) { |
| 217 | ui-function / drawBlur | private fun drawBlur(fbo: Int, alpha: Float) { |
| 223 | blur / GlBlur.draw | GlBlur.draw(fbo, w, h, alpha, client.isPaused) |
| 231 | ui-function / drawGl | private fun drawGl(fbo: Int, cached: GlRenderer?, kept: Boolean = false, draw: (RenderContext) -> Unit): GlRenderer? { |
| 259 | ui-function / maxTextureSize | private fun maxTextureSize(): Int = |
| 264 | ui-function / cachedMaxTextureSize | private fun cachedMaxTextureSize(): Int { |
| 273 | ui-function / viewportFitsTexture | private fun viewportFitsTexture(w: Int, h: Int): Boolean { |
| 292 | ui-function / reuse | private fun reuse(renderer: GlRenderer?): GlRenderer { |
| 298 | ui-function / drawHudGl | fun drawHudGl(draw: (RenderContext, RenderContext?) -> Unit) { |
| 323 | ui-function / resolveHudTarget | private fun resolveHudTarget(): RenderTarget? { |
| 348 | ui-function / destroyHudTarget | private fun destroyHudTarget() { |
| 364 | ui-function / onAllocFailure | private fun onAllocFailure(what: String, w: Int, h: Int, error: Throwable) { |
| 379 | ui-function / recreateSurface | fun recreateSurface(width: Int, height: Int) { |

### 1.21.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/GlRenderer.kt

`file-c37abaf71d1400abff16`；状态 **pending**；领域 ui-support；组合 1.21.1/fabric, 1.21.1/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 51 | ui-function / moved | fun moved(at: Int) = Live(at, state, draw, key).also { it.last = last } |
| 53 | ui-function / changed | fun changed() = key == null \|\| key() != last |
| 55 | ui-function / commit | fun commit() { |
| 65 | ui-function / bitmapTexture | override fun bitmapTexture(bitmap: PolyBitmap): Any = GlTextures.bitmap(bitmap) |
| 69 | ui-function / mark | override fun mark(): Any { |
| 74 | ui-function / since | override fun since(mark: Any): Any { |
| 97 | ui-function / valid | override fun valid(captured: Any): Boolean = (captured as Captured).batches.all { GlTextures.alive(it.texture) } |
| 99 | ui-function / append | override fun append(captured: Any) { |
| 107 | ui-function / writeStateKey | override fun writeStateKey() { |
| 112 | ui-function / matches | fun matches(): Boolean = |
| 115 | ui-function / reset | fun reset() { |
| 121 | ui-function / submit | override fun submit(texture: Any, premultiplied: Boolean, nearest: Boolean, verts: FloatArray, colors: IntArray, bounds: FloatArray) { |
| 127 | ui-function / layerMark | override fun layerMark(): Any = intArrayOf(batches.size, lives.size) |
| 129 | ui-function / layer | override fun layer(mark: Any, alpha: Float) { |
| 142 | ui-function / fade | override fun fade(mark: Any, alpha: Float): Boolean { |
| 152 | ui-function / live | override fun live(draw: (PolyRenderer) -> Unit) { |
| 160 | ui-function / mcTextLive | override fun mcTextLive(text: String, x: Float, y: Float, color: () -> Int, shadow: Boolean, scale: Float) { |
| 186 | ui-function / texture | override fun texture(target: RenderTarget, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, alpha: Float) { |
| 196 | ui-function / draw | fun draw(fbo: Int, w: Int, h: Int, topDown: Boolean, clear: Boolean, kept: Boolean = false) { |
| 201 | ui-function / drawVanilla | fun drawVanilla(w: Int, h: Int, topDown: Boolean) { |
| 208 | ui-function / flushPending | private fun flushPending() = flush() |
| 210 | ui-function / replay | private fun replay(lives: List<Live>): List<GlProgram.Segment> = lives.map { l -> |
| 225 | ui-function / take | fun take(renderer: GlRenderer) { |
| 240 | ui-function / idle | private fun idle() { |
| 248 | ui-function / same | private fun same(a: List<GlProgram.Batch>, b: List<GlProgram.Batch>) = a.size == b.size && a.indices.all { a[it] === b[it] } |
| 250 | ui-function / sameLives | private fun sameLives(a: List<Live>, b: List<Live>) = |
| 253 | ui-function / coalesce | private fun coalesce() { |
| 257 | ui-function / emit | fun emit() { |
| 280 | ui-function / draw | fun draw(fbo: Int, w: Int, h: Int, topDown: Boolean, clear: Boolean, vararg layers: Layer) { |
| 308 | ui-function / stale | private fun stale(): Boolean = (live != null && changed()) \|\| batches.any { (it.texture as? Group)?.stale() == true } |
| 310 | ui-function / render | private fun render(composite: GlProgram.Batch, transient: ArrayList<Group>?, sink: ArrayList<Group>) { |
| 338 | ui-function / release | fun release() { |
| 352 | ui-function / of | fun of(batches: List<GlProgram.Batch>, live: (() -> List<GlProgram.Segment>)?, changed: () -> Boolean, alpha: Float, screenW: Int, screenH: Int): Group? { |
| 368 | ui-function / prepare | fun prepare(batches: List<GlProgram.Batch>, transient: ArrayList<Group>?, sink: ArrayList<Group>) { |
| 372 | ui-function / endFrame | fun endFrame() { |
| 377 | ui-function / recycle | private fun recycle(t: IntArray) { |
| 381 | ui-function / acquire | private fun acquire(w: Int, h: Int): IntArray { |
| 397 | ui-function / delete | private fun delete(t: IntArray) { |
| 408 | ui-function / segments | fun segments(lives: List<Live>): List<GlProgram.Segment> { |
| 413 | ui-function / drawBatches | fun drawBatches(fbo: Int, w: Int, h: Int, topDown: Boolean, clear: Boolean, batches: List<GlProgram.Batch>, segments: List<GlProgram.Segment>, kept: Boolean, arena: Boolean) { |
| 436 | ui-function / draw | fun draw(batches: List<GlProgram.Batch>, w: Int, h: Int, topDown: Boolean, live: List<GlProgram.Segment>) { |
| 469 | ui-function / drawBatch | private fun drawBatch(b: GlProgram.Batch) { |
| 489 | ui-function / sameKey | fun sameKey(o: Batch) = texture === o.texture && premultiplied == o.premultiplied && nearest == o.nearest |
| 491 | ui-function / faded | fun faded(alpha: Float) = Batch(texture, premultiplied, nearest, verts, IntArray(colors.size) { i -> |
| 507 | ui-function / merge | fun merge(list: List<Batch>): Batch { |
| 556 | ui-function / init | private fun init() { |
| 583 | ui-function / shader | private fun shader(type: Int, src: String): Int { |
| 591 | ui-function / invalidate | fun invalidate(b: Batch) { |
| 597 | ui-function / draw | fun draw(batches: List<Batch>, w: Int, h: Int, topDown: Boolean, live: List<Segment> = emptyList(), kept: Boolean = false, ox: Float = 0f, oy: Float = 0f) { |
| 641 | ui-function / drawArena | fun drawArena(batches: List<Batch>, w: Int, h: Int, topDown: Boolean, live: List<Segment>) { |
| 705 | ui-function / use | fun use(id: Int) { |
| 736 | ui-function / attribPointers | private fun attribPointers() { |
| 742 | ui-function / setupState | private fun setupState(w: Int, h: Int, topDown: Boolean, ox: Float = 0f, oy: Float = 0f) { |
| 759 | ui-function / drawBatch | private fun drawBatch(b: Batch, first: Int) { |
| 775 | ui-function / filter | private fun filter(mode: Int) { |
| 780 | ui-function / upload | private fun upload(at: Int, batches: List<Batch>): Int { |
| 814 | ui-function / ensureIndices | private fun ensureIndices(quads: Int) { |
| 862 | ui-function / bitmap | fun bitmap(bitmap: PolyBitmap): Tex = bitmaps.getOrPut(bitmap) { |
| 867 | ui-function / external | fun external(id: Int): Tex = external.getOrPut(id) { Tex(null, id, 0) } |
| 869 | ui-function / alive | fun alive(key: Any): Boolean = key !is Tex \|\| key.bitmap == null \|\| bitmaps[key.bitmap] === key |
| 871 | ui-function / id | fun id(key: Any): Int = when (key) { |
| 882 | ui-function / glyphPage | private fun glyphPage(page: PolyGlyphPage.Page): Int { |
| 899 | ui-function / upload | private fun upload(bitmap: PolyBitmap): Int { |
| 908 | ui-function / unpackDefaults | private fun unpackDefaults(rowLength: Int) { |
| 916 | ui-function / newTexture | private fun newTexture(): Int { |
| 926 | ui-function / genTexture | private fun genTexture(): Int { |
| 931 | ui-function / bind | private fun bind(id: Int) { |
| 936 | ui-function / texParameter | private fun texParameter(name: Int, value: Int) { |
| 941 | ui-function / pixelStore | private fun pixelStore(name: Int, value: Int) { |
| 946 | ui-function / texImage | private fun texImage(w: Int, h: Int, px: IntBuffer) { |
| 951 | ui-function / texSubImage | private fun texSubImage(y: Int, w: Int, h: Int, px: IntBuffer) { |
| 956 | ui-function / deleteTexture | private fun deleteTexture(id: Int) { |
| 961 | ui-function / endFrame | fun endFrame() { |

### 1.21.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/MacScrollEvent.kt

`file-6410d74589c9b7654791`；状态 **pending**；领域 ui-support；组合 1.21.1/fabric, 1.21.1/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 18 | ui-function / readScroll | fun readScroll(): Boolean? = try { |

### 1.21.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/McFontService.kt

`file-e9a3793fe0341b2555b4`；状态 **pending**；领域 font-language；组合 1.21.1/fabric, 1.21.1/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 20 | font-language / McFontService | object McFontService { |
| 23 | ui-function / init | fun init() { |
| 27 | font-language / McResourceFont.init | McResourceFont.init() |
| 30 | ui-function / installBundledFonts | private fun installBundledFonts() { |
| 31 | font-language / McFontService | val loader = McFontService::class.java.classLoader |
| 33 | font-language / UiFontFiles.DEFAULT_REGULAR | "assets/oneconfig/fonts/minecraft/Minecraft-Regular.otf" to UiFontFiles.DEFAULT_REGULAR, |
| 34 | font-language / UiFontFiles.DEFAULT_BOLD | "assets/oneconfig/fonts/minecraft/Minecraft-Bold.otf" to UiFontFiles.DEFAULT_BOLD, |
| 46 | ui-function / installHostFonts | private fun installHostFonts() { |
| 47 | font-language / UiFontFiles.readRegular | val regular = UiFontFiles.readRegular() |
| 54 | font-language / UiFontFiles.REGULAR | FontManager.replace(UiFontFiles.REGULAR, regular) |
| 55 | font-language / UiFontFiles.readBold | val bold = UiFontFiles.readBold() ?: regular |
| 57 | font-language / UiFontFiles.BOLD | FontManager.replace(UiFontFiles.BOLD, bold) |
| 60 | font-language / UiFontFiles.BOLD | FontManager.replace(UiFontFiles.BOLD, regular) |
| 62 | font-language / UiFontFiles.REGULAR | FontManager.setDefault(UiFontFiles.REGULAR) |
| 68 | font-language / UiFontFiles.readFallback | val fallback = UiFontFiles.readFallback() |
| 71 | font-language / UiFontFiles.FALLBACK | FontManager.replace(UiFontFiles.FALLBACK, fallback) |
| 82 | ui-function / installEngines | fun installEngines() { |

### 1.21.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/McResourceFont.kt

`file-3830c526e9341bac844e`；状态 **pending**；领域 font-language；组合 1.21.1/fabric, 1.21.1/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 28 | font-language / McResourceFont | internal object McResourceFont { |
| 40 | ui-function / glyph | fun glyph(cp: Int): McGlyph? = glyphs[cp] ?: unihex[cp]?.let { u -> baked.getOrPut(cp) { bake(u) } } |
| 47 | ui-function / init | fun init() { |
| 55 | ui-function / reload | private fun reload() { |
| 74 | ui-function / fontOptions | private fun fontOptions(): Int { |
| 82 | ui-function / load | private fun load(rm: ResourceManager, options: Int): Font? { |
| 88 | ui-function / unclaimed | fun unclaimed(cp: Int) = cp !in glyphs && cp !in unihex |
| 106 | ui-function / collectProviders | private fun collectProviders(rm: ResourceManager, ref: String, out: MutableList<JsonObject>, visited: MutableSet<String>, options: Int) { |
| 122 | ui-function / filterPasses | private fun filterPasses(provider: JsonObject, options: Int): Boolean { |
| 129 | ui-function / loadBitmap | private fun loadBitmap(rm: ResourceManager, provider: JsonObject): Map<Int, McGlyph> { |
| 156 | ui-function / loadUnihex | private fun loadUnihex(rm: ResourceManager, provider: JsonObject): Map<Int, Unihex> { |
| 181 | ui-function / parseHex | private fun parseHex(line: String, overrides: List<IntArray>, out: MutableMap<Int, Unihex>): Boolean { |
| 209 | ui-function / bake | private fun bake(u: Unihex): McGlyph { |
| 221 | ui-function / read | private fun read(rm: ResourceManager, ref: String, folder: String): ByteArray? = |
| 224 | ui-function / id | private fun id(ref: String, folder: String, suffix: String = "") = |

### 1.21.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/MinecraftKeyboardAdapter.kt

`file-ec5219a5de673e772fd1`；状态 **pending**；领域 ui-support；组合 1.21.1/fabric, 1.21.1/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 13 | ui-function / keyLocation | fun keyLocation(bindingKey: Int): Int  = when (bindingKey) { |
| 20 | ui-function / toAwtKeyCode | fun toAwtKeyCode(shortcutKey: Int): Int = when (shortcutKey) { |

### 1.21.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/UiBlur.kt

`file-4ff8e76e7ed41cb24fd6`；状态 **pending**；领域 blur；组合 1.21.1/fabric, 1.21.1/neoforge。

该文件支撑 blur；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 1.21.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/impls/HudEditorUIScreen.kt

`file-d4a0cf15167d4942d34a`；状态 **pending**；领域 hud-editor；组合 1.21.1/fabric, 1.21.1/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 37 | route / HudEditorUIScreen | class HudEditorUIScreen private constructor() : ComposeScreen() { |
| 42 | ui-function / open | fun open(fromOneConfig: Boolean): HudEditorUIScreen { |
| 57 | ui-function / beginClose | private fun beginClose() { |
| 62 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLOSE) |
| 62 | sound / UiSoundEvent.CLOSE | UiSounds.play(UiSoundEvent.CLOSE) |
| 63 | sound / UiSounds.releaseAmbience | UiSounds.releaseAmbience() |
| 63 | sound / releaseAmbience | UiSounds.releaseAmbience() |
| 66 | ui-function / cancelClose | private fun cancelClose(): Boolean { |
| 70 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.OPEN) |
| 70 | sound / UiSoundEvent.OPEN | UiSounds.play(UiSoundEvent.OPEN) |
| 71 | sound / UiSounds.acquireAmbience | UiSounds.acquireAmbience() |
| 71 | sound / acquireAmbience | UiSounds.acquireAmbience() |
| 79 | ui-function / returnToOneConfig | private fun returnToOneConfig() { |
| 87 | ui-function / init | override fun init() { |
| 96 | sound / UiSounds.acquireAmbience | UiSounds.acquireAmbience() |
| 96 | sound / acquireAmbience | UiSounds.acquireAmbience() |
| 102 | ui-function / removed | override fun removed() { |
| 108 | sound / UiSounds.releaseAmbience | UiSounds.releaseAmbience() |
| 108 | sound / releaseAmbience | UiSounds.releaseAmbience() |
| 114 | ui-function / isPauseScreen | override fun isPauseScreen(): Boolean = OneConfigConfig.pauseGame |
| 116 | ui-function / handleOneConfigKeybind | private fun handleOneConfigKeybind(): Boolean { |
| 128 | ui-function / handleKeyPressed | override fun handleKeyPressed(key: Int, modifiers: Int): Boolean { |
| 137 | keybind / KeybindRecordingBus.isRecording | if (toggleKey != null && key == toggleKey && !KeybindRecordingBus.isRecording) { |
| 143 | ui-function / handleMouseClicked | override fun handleMouseClicked(button: Int): Boolean { |
| 144 | keybind / KeybindRecordingBus.isRecording | if (KeybindRecordingBus.isRecording) return false |
| 147 | keybind / KeybindManager.isTriggeredByMouse | KeybindManager.isTriggeredByMouse(OneConfigConfig.oneConfigKeybind, button) |
| 155 | ui-function / render | override fun render(ctx: GuiGraphics, mouseX: Int, mouseY: Int, tickDelta: Float) { |
| 175 | composable / compose | @Composable |
| 176 | ui-function / compose | override fun compose() { |
| 197 | animation / RetainedVisibility | RetainedVisibility( |
| 199 | animation / tween | enter = tween(200, easing = EaseOutCubic), |
| 200 | animation / tween | exit = tween(exitMs, easing = EaseIn), |

### 1.21.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/impls/OneConfigUIScreen.kt

`file-661fe3b9e20afe5d5191`；状态 **pending**；领域 shell-navigation；组合 1.21.1/fabric, 1.21.1/neoforge。

对齐背景模糊开关、强度、HUD 穿透、resize 与关屏后的 GPU/画面恢复。 定位类型: blur 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 80 | route / OneConfigUIScreen | class OneConfigUIScreen @JvmOverloads constructor( |
| 92 | ui-function / blurAlpha | override fun blurAlpha(): Float { |
| 93 | blur / enableBackgroundBlur | if (!OneConfigConfig.enableBackgroundBlur) return 0f |
| 111 | ui-function / shared | private fun shared(): OneConfigUIScreen = |
| 115 | ui-function / forRoute | fun forRoute(route: Any?): OneConfigUIScreen = shared().also { |
| 121 | ui-function / open | fun open(): OneConfigUIScreen = shared().also { |
| 128 | ui-function / resume | fun resume(): OneConfigUIScreen = shared().also { |
| 142 | ui-function / scheduleSave | private fun scheduleSave() { |
| 155 | route / OpeningRoute | private data class OpeningRoute(val route: Any, val restored: Boolean = false) |
| 157 | ui-function / resolveOpeningRoute | private fun resolveOpeningRoute(): OpeningRoute { |
| 177 | ui-function / stopWarmUp | fun stopWarmUp() { |
| 190 | ui-function / dropPrerasteredLater | private fun dropPrerasteredLater() { |
| 197 | ui-function / prepareWarmUp | fun prepareWarmUp(): Runnable? { |
| 212 | ui-function / warmUp | private fun warmUp(w: Int, h: Int, density: Float, surface: Float) { |
| 222 | ui-function / warmFrames | private fun warmFrames(w: Int, h: Int, density: Float, surface: Float) { |
| 227 | ui-function / isDispatchNeeded | override fun isDispatchNeeded(context: CoroutineContext) = Thread.currentThread() !== thread |
| 228 | ui-function / dispatch | override fun dispatch(context: CoroutineContext, block: Runnable) { |
| 232 | ui-function / drain | fun drain() { |
| 292 | ui-function / openLastSession | fun openLastSession() { |
| 303 | ui-function / beginClose | private fun beginClose() { |
| 308 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLOSE) |
| 308 | sound / UiSoundEvent.CLOSE | UiSounds.play(UiSoundEvent.CLOSE) |
| 309 | sound / UiSounds.releaseAmbience | UiSounds.releaseAmbience() |
| 309 | sound / releaseAmbience | UiSounds.releaseAmbience() |
| 312 | ui-function / cancelClose | private fun cancelClose(): Boolean { |
| 323 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.OPEN) |
| 323 | sound / UiSoundEvent.OPEN | UiSounds.play(UiSoundEvent.OPEN) |
| 324 | sound / UiSounds.acquireAmbience | UiSounds.acquireAmbience() |
| 324 | sound / acquireAmbience | UiSounds.acquireAmbience() |
| 340 | ui-function / markClosed | private fun markClosed() { |
| 344 | ui-function / init | override fun init() { |
| 416 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.OPEN) |
| 416 | sound / UiSoundEvent.OPEN | UiSounds.play(UiSoundEvent.OPEN) |
| 417 | sound / UiSounds.acquireAmbience | UiSounds.acquireAmbience() |
| 417 | sound / acquireAmbience | UiSounds.acquireAmbience() |
| 427 | ui-function / onSceneRebuilding | override fun onSceneRebuilding() { |
| 433 | ui-function / removed | override fun removed() { |
| 438 | sound / UiSounds.releaseAmbience | UiSounds.releaseAmbience() |
| 438 | sound / releaseAmbience | UiSounds.releaseAmbience() |
| 444 | ui-function / isPauseScreen | override fun isPauseScreen(): Boolean = OneConfigConfig.pauseGame |
| 446 | ui-function / handleOneConfigKeybind | private fun handleOneConfigKeybind(): Boolean { |
| 458 | ui-function / handleKeyPressed | override fun handleKeyPressed(key: Int, modifiers: Int): Boolean { |
| 467 | keybind / KeybindRecordingBus.isRecording | if (toggleKey != null && key == toggleKey && !KeybindRecordingBus.isRecording) { |
| 474 | ui-function / handleMouseClicked | override fun handleMouseClicked(button: Int): Boolean { |
| 475 | keybind / KeybindRecordingBus.isRecording | if (KeybindRecordingBus.isRecording) return false |
| 478 | keybind / KeybindManager.isTriggeredByMouse | KeybindManager.isTriggeredByMouse(OneConfigConfig.oneConfigKeybind, button) |
| 485 | keybind / KeybindManager.hasTriggeredMouseBind | if (KeybindManager.hasTriggeredMouseBind(button)) return true |
| 486 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLICK) |
| 486 | sound / UiSoundEvent.CLICK | UiSounds.play(UiSoundEvent.CLICK) |
| 491 | keybind / KeybindManager.hasTriggeredMouseBind | if (KeybindManager.hasTriggeredMouseBind(button)) return true |
| 492 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLICK) |
| 492 | sound / UiSoundEvent.CLICK | UiSounds.play(UiSoundEvent.CLICK) |
| 502 | ui-function / render | override fun render(ctx: GuiGraphics, mouseX: Int, mouseY: Int, tickDelta: Float) { |
| 524 | ui-function / easeOutExpo | private fun easeOutExpo(progress: Float): Float { |
| 532 | composable / compose | @Composable |
| 533 | ui-function / compose | override fun compose() { |
| 565 | ui-function / submit | override fun submit(texture: Any, premultiplied: Boolean, nearest: Boolean, verts: FloatArray, colors: IntArray, bounds: FloatArray) {} |
| 567 | ui-function / bitmapTexture | override fun bitmapTexture(bitmap: PolyBitmap): Any = bitmap |
| 569 | ui-function / live | override fun live(draw: (PolyRenderer) -> Unit) = draw(this) |

### 1.21.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/opengl/StoredGLState.kt

`file-0ab435f8f2c2622b16e6`；状态 **pending**；领域 ui-support；组合 1.21.1/fabric, 1.21.1/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 10 | ui-function / resyncTextureBindCache | fun resyncTextureBindCache() { |
| 21 | ui-function / capture | fun capture(): StoredGLState { |
| 102 | ui-function / restore | fun restore(): StoredGLState { |
| 195 | ui-function / forceToggle | private fun forceToggle(enabled: Boolean, enable: () -> Unit, disable: () -> Unit) { |
| 205 | ui-function / forceDepthFunc | private fun forceDepthFunc(func: Int) { |
| 211 | ui-function / forceDepthMask | private fun forceDepthMask(mask: Boolean) { |

### 1.21.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/CompatOverlayRenderer.kt

`file-bcfb4f2d4494ff9e3c87`；状态 **pending**；领域 hud-editor；组合 1.21.1/fabric, 1.21.1/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 14 | ui-function / register | fun register(hook: (GuiGraphics) -> Unit) { |
| 19 | ui-function / hasHooks | fun hasHooks(): Boolean = hooks.isNotEmpty() |
| 22 | ui-function / oneConfigScreenOpen | fun oneConfigScreenOpen(): Boolean = HudManager.isEditing |
| 25 | ui-function / render | fun render(ctx: GuiGraphics) { |

### 1.21.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/LegacyHudRenderer.kt

`file-4b5de522d705daa9c101`；状态 **pending**；领域 hud-editor；组合 1.21.1/fabric, 1.21.1/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 32 | ui-function / reportFailure | private fun reportFailure(hud: LegacyHud, e: Throwable) { |
| 47 | ui-function / renderLive | fun renderLive(graphics: GuiGraphics) { |
| 52 | ui-function / renderLiveHuds | private fun renderLiveHuds(graphics: GuiGraphics) { |

### 1.21.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/keybind/MinecraftKeybindProvider.kt

`file-a4b65c71e2ec40ebf49d`；状态 **pending**；领域 keybind；组合 1.21.1/fabric, 1.21.1/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 22 | keybind / OneConfigKeybind | private val properties = IdentityHashMap<KeyMapping, Property<OneConfigKeybind>>() |
| 37 | ui-function / groups | override fun groups(): List<KeybindGroup> { |
| 77 | ui-function / managedMappings | fun managedMappings(): List<KeyMapping> { |
| 82 | keybind / OneConfigKeybind | private fun propertyFor(mapping: KeyMapping): Property<OneConfigKeybind> { |
| 82 | ui-function / propertyFor | private fun propertyFor(mapping: KeyMapping): Property<OneConfigKeybind> { |
| 89 | keybind / OneConfigKeybind | OneConfigKeybind::class.java, |
| 99 | ui-function / ownerMod | private fun ownerMod(mapping: KeyMapping, modsById: Map<String, ModInfo>): ModInfo? { |
| 105 | ui-function / ownerCandidates | private fun ownerCandidates(keyName: String): Sequence<String> = sequence { |
| 117 | ui-function / modIcon | private fun modIcon(owner: ModInfo?): String? = |
| 124 | ui-function / minecraftIcon | private fun minecraftIcon(): String { |
| 132 | ui-function / extractMinecraftIcon | private fun extractMinecraftIcon(): String? = runCatching { |
| 148 | ui-function / groupTitle | private fun groupTitle(category: String, owner: ModInfo?): String { |
| 154 | ui-function / sanitizeId | private fun sanitizeId(value: String): String { |
| 161 | keybind / OneConfigKeybind | private fun KeyMapping.toOneConfigKeybind(): OneConfigKeybind { |
| 161 | ui-function / toOneConfigKeybind | private fun KeyMapping.toOneConfigKeybind(): OneConfigKeybind { |
| 166 | keybind / Key.toOneConfigKeybind | private fun InputConstants.Key.toOneConfigKeybind(): OneConfigKeybind { |
| 166 | keybind / OneConfigKeybind | private fun InputConstants.Key.toOneConfigKeybind(): OneConfigKeybind { |
| 166 | ui-function / toOneConfigKeybind | private fun InputConstants.Key.toOneConfigKeybind(): OneConfigKeybind { |
| 171 | keybind / OneConfigKeybind | OneConfigKeybind(intArrayOf(value), null, KeyModifiers.NONE, 0L) { true } |
| 174 | keybind / OneConfigKeybind | OneConfigKeybind(null, intArrayOf(value), KeyModifiers.NONE, 0L) { true } |
| 175 | keybind / OneConfigKeybind | else -> OneConfigKeybind(null, null, KeyModifiers.NONE, 0L) { true } |
| 179 | keybind / OneConfigKeybind | private fun KeyMapping.applyOneConfigKeybind(keybind: OneConfigKeybind?) { |
| 179 | ui-function / applyOneConfigKeybind | private fun KeyMapping.applyOneConfigKeybind(keybind: OneConfigKeybind?) { |
| 193 | ui-function / categoryLabel | private fun categoryLabel(mapping: KeyMapping): String { |
| 197 | ui-function / translate | private fun translate(key: String): String { |
| 209 | ui-function / isOneConfigMirror | private fun isOneConfigMirror(@Suppress("UNUSED_PARAMETER") mapping: KeyMapping): Boolean = false |

### 1.21.1/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/sound/McUiSoundService.kt

`file-8b2401ca8da254015fe7`；状态 **pending**；领域 sound；组合 1.21.1/fabric, 1.21.1/neoforge。

对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 19 | sound / McUiSoundService | class McUiSoundService : UiSoundService { |
| 19 | sound / UiSoundService | class McUiSoundService : UiSoundService { |
| 28 | sound / UiSoundTheme | private val ambienceShuffle = mutableMapOf<UiSoundTheme, MutableList<String>>() |
| 29 | sound / UiSoundTheme | private val lastAmbienceSoundId = mutableMapOf<UiSoundTheme, String>() |
| 33 | sound / UiSoundEvent | override fun play(event: UiSoundEvent, theme: UiSoundTheme, volume: Float) { |
| 33 | sound / UiSoundTheme | override fun play(event: UiSoundEvent, theme: UiSoundTheme, volume: Float) { |
| 33 | ui-function / play | override fun play(event: UiSoundEvent, theme: UiSoundTheme, volume: Float) { |
| 37 | sound / UiSoundTheme.MODERN | UiSoundTheme.MODERN -> playModern(event, volume) |
| 38 | sound / UiSoundTheme.MINECRAFT | UiSoundTheme.MINECRAFT -> playMinecraft(event, volume) |
| 44 | sound / UiSoundEvent | private fun playModern(event: UiSoundEvent, volume: Float) { |
| 44 | ui-function / playModern | private fun playModern(event: UiSoundEvent, volume: Float) { |
| 46 | sound / UiSoundEvent.OPEN | UiSoundEvent.OPEN -> playModernSound("ui.open", 1f, volume) |
| 47 | sound / UiSoundEvent.CLOSE | UiSoundEvent.CLOSE -> playModernSound("ui.close", 1f, volume) |
| 48 | sound / UiSoundEvent.CLICK | UiSoundEvent.CLICK -> playModernSound("ui.click", 1f, volume) |
| 49 | sound / UiSoundEvent.SLIDER_TICK | UiSoundEvent.SLIDER_TICK -> playModernSound("ui.slider", 1f, volume) |
| 50 | sound / UiSoundEvent.HUD_SELECT | UiSoundEvent.HUD_SELECT -> playModernSound("ui.click", 1.0f, volume) |
| 51 | sound / UiSoundEvent.HUD_DRAG_START | UiSoundEvent.HUD_DRAG_START -> playModernSound("ui.click", 0.9f, volume) |
| 52 | sound / UiSoundEvent.HUD_DRAG_END | UiSoundEvent.HUD_DRAG_END -> playModernSound("ui.click", 1.1f, volume) |
| 53 | sound / UiSoundEvent.HUD_RESIZE_END | UiSoundEvent.HUD_RESIZE_END -> playModernSound("ui.click", 1.0f, volume) |
| 58 | ui-function / playModernSound | private fun playModernSound(id: String, pitch: Float, volume: Float) { |
| 63 | sound / UiSoundEvent | private fun playMinecraft(event: UiSoundEvent, volume: Float) { |
| 63 | ui-function / playMinecraft | private fun playMinecraft(event: UiSoundEvent, volume: Float) { |
| 65 | sound / UiSoundEvent.CLICK | UiSoundEvent.CLICK -> playVanilla(SoundEvents.UI_BUTTON_CLICK, 1f, 0.5f * volume) |
| 66 | sound / UiSoundEvent.OPEN | UiSoundEvent.OPEN -> playModernSound("ui.open", 1f, volume) |
| 67 | sound / UiSoundEvent.CLOSE | UiSoundEvent.CLOSE -> playModernSound("ui.close", 1f, volume) |
| 68 | sound / UiSoundEvent.SLIDER_TICK | UiSoundEvent.SLIDER_TICK -> { |
| 73 | sound / UiSoundEvent.HUD_SELECT | UiSoundEvent.HUD_SELECT -> playVanilla(SoundEvents.UI_BUTTON_CLICK, 1f, 0.3f * volume) |
| 74 | sound / UiSoundEvent.HUD_DRAG_END | UiSoundEvent.HUD_DRAG_END -> playVanilla(SoundEvents.UI_BUTTON_CLICK, 1f, 0.3f * volume) |
| 75 | sound / UiSoundEvent.HUD_RESIZE_END | UiSoundEvent.HUD_RESIZE_END -> playVanilla(SoundEvents.UI_BUTTON_CLICK, 1f, 0.3f * volume) |
| 80 | ui-function / playVanilla | private fun playVanilla(event: SoundEvent, pitch: Float, volume: Float) { |
| 85 | ui-function / playVanilla | private fun playVanilla(holder: Holder<SoundEvent>, pitch: Float, volume: Float) = |
| 88 | sound / startAmbience | override fun startAmbience(theme: UiSoundTheme, volume: Float) { |
| 88 | sound / UiSoundTheme | override fun startAmbience(theme: UiSoundTheme, volume: Float) { |
| 88 | ui-function / startAmbience | override fun startAmbience(theme: UiSoundTheme, volume: Float) { |
| 109 | sound / stopAmbience | override fun stopAmbience(@Suppress("UNUSED_PARAMETER") setDucking: Boolean) { |
| 109 | ui-function / stopAmbience | override fun stopAmbience(@Suppress("UNUSED_PARAMETER") setDucking: Boolean) { |
| 118 | sound / UiSoundTheme | private fun ambienceVolumeFor(theme: UiSoundTheme, volume: Float): Float = when (theme) { |
| 118 | ui-function / ambienceVolumeFor | private fun ambienceVolumeFor(theme: UiSoundTheme, volume: Float): Float = when (theme) { |
| 119 | sound / UiSoundTheme.MODERN | UiSoundTheme.MODERN -> 0.5f * volume |
| 120 | sound / UiSoundTheme.MINECRAFT | UiSoundTheme.MINECRAFT -> 0.08f * volume |
| 123 | sound / UiSoundTheme | private fun ambienceSoundIds(theme: UiSoundTheme): List<String> = when (theme) { |
| 123 | ui-function / ambienceSoundIds | private fun ambienceSoundIds(theme: UiSoundTheme): List<String> = when (theme) { |
| 124 | sound / UiSoundTheme.MODERN | UiSoundTheme.MODERN -> listOf("ui.pad_loop") |
| 125 | sound / UiSoundTheme.MINECRAFT | UiSoundTheme.MINECRAFT -> listOf( |
| 133 | sound / UiSoundTheme | private fun playAmbienceInstance(theme: UiSoundTheme, targetVolume: Float): FadingLoopSoundInstance { |
| 133 | ui-function / playAmbienceInstance | private fun playAmbienceInstance(theme: UiSoundTheme, targetVolume: Float): FadingLoopSoundInstance { |
| 144 | sound / UiSoundTheme | private fun nextAmbienceSoundId(theme: UiSoundTheme, ids: List<String>): String { |
| 144 | ui-function / nextAmbienceSoundId | private fun nextAmbienceSoundId(theme: UiSoundTheme, ids: List<String>): String { |
| 165 | ui-function / driveAmbienceLoop | private fun driveAmbienceLoop() { |
| 196 | sound / UiSoundTheme | val theme: UiSoundTheme, |
| 215 | ui-function / beginFadeOut | fun beginFadeOut() { |
| 219 | ui-function / cancelFadeOut | fun cancelFadeOut() { |
| 224 | ui-function / updateTargetVolume | fun updateTargetVolume(newTarget: Float) { |
| 229 | ui-function / tick | override fun tick() { |

### 1.21.10/fabric/src/main/java/org/polyfrost/oneconfig/internal/OneConfig.java

`file-c6dabe6affe4f3d8c200`；状态 **pending**；领域 ui-support；组合 1.21.10/fabric。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 240 | search-provider / SearchCorpus.INSTANCE | EventManager.register(ResourceFinishedLoading.class, e -> SearchCorpus.INSTANCE.init()); |
| 339 | font-language / McFontService.INSTANCE | McFontService.INSTANCE.init(); |

### 1.21.10/fabric/src/main/java/org/polyfrost/oneconfig/internal/OneConfigPreLaunch.java

`file-378b4d210427eace28f4`；状态 **pending**；领域 ui-support；组合 1.21.10/fabric。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 25 | font-language / McFontService.installEngines | McFontService.installEngines(); |

### 1.21.10/neoforge/src/main/java/org/polyfrost/oneconfig/internal/OneConfig.java

`file-948966cd2f0ff8766c05`；状态 **pending**；领域 ui-support；组合 1.21.10/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 239 | search-provider / SearchCorpus.INSTANCE | EventManager.register(ResourceFinishedLoading.class, e -> SearchCorpus.INSTANCE.init()); |
| 349 | font-language / McFontService.INSTANCE | McFontService.INSTANCE.init(); |

### 1.21.10/neoforge/src/main/java/org/polyfrost/oneconfig/internal/OneConfigPreLaunch.java

`file-336c1a9cd97650866f6d`；状态 **pending**；领域 ui-support；组合 1.21.10/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 22 | font-language / McFontService.installEngines | org.polyfrost.oneconfig.internal.ui.compose.McFontService.installEngines(); |

### 1.21.10/src/main/java/org/polyfrost/oneconfig/api/platform/v1/internal/I18nPlatformImpl.java

`file-093eeade840834e2ad2b`；状态 **pending**；领域 ui-support；组合 1.21.10/fabric, 1.21.10/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 39 | font-language / I18nPlatform | public class I18nPlatformImpl implements I18nPlatform { |

### 1.21.10/src/main/java/org/polyfrost/oneconfig/api/platform/v1/internal/KeysImpl.java

`file-3ef3968137c0dad07395`；状态 **pending**；领域 ui-support；组合 1.21.10/fabric, 1.21.10/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 21 | keybind / getKeyA | public int getKeyA() { |
| 26 | keybind / getKeyC | public int getKeyC() { |
| 31 | keybind / getKeyD | public int getKeyD() { |
| 36 | keybind / getKeyE | public int getKeyE() { |
| 41 | keybind / getKeyF | public int getKeyF() { |
| 46 | keybind / getKeyH | public int getKeyH() { |
| 51 | keybind / getKeyL | public int getKeyL() { |
| 56 | keybind / getKeyR | public int getKeyR() { |
| 61 | keybind / getKeyV | public int getKeyV() { |
| 66 | keybind / getKeyX | public int getKeyX() { |
| 71 | keybind / getKeyY | public int getKeyY() { |
| 76 | keybind / getKeyZ | public int getKeyZ() { |
| 81 | keybind / getKeyDelete | public int getKeyDelete() { |
| 91 | keybind / getKeyLeftShift | public int getKeyLeftShift() { |
| 96 | keybind / getKeyRightShift | public int getKeyRightShift() { |
| 101 | keybind / getKeyLeftControl | public int getKeyLeftControl() { |
| 106 | keybind / getKeyRightControl | public int getKeyRightControl() { |
| 111 | keybind / getKeyLeftAlt | public int getKeyLeftAlt() { |
| 116 | keybind / getKeyRightAlt | public int getKeyRightAlt() { |
| 121 | keybind / getKeyLeftSuper | public int getKeyLeftSuper() { |
| 126 | keybind / getKeyRightSuper | public int getKeyRightSuper() { |

### 1.21.10/src/main/java/org/polyfrost/oneconfig/api/ui/v1/api/internal/TinyFdApiImpl.java

`file-6126db889f221b960656`；状态 **pending**；领域 ui-support；组合 1.21.10/fabric, 1.21.10/neoforge。

核对打开/取消/确认、焦点、边界定位、外点关闭与提交后可见结果。 定位类型: dialog-or-popup

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 74 | dialog-or-popup / Dialog | private static <T> T open(String description, T fallback, Dialog<T> dialog) { |
| 151 | dialog-or-popup / tinyfd_openFileDialog | String result = TinyFileDialogs.tinyfd_openFileDialog(sanitizeText(title), sanitizePath(defaultFilePath), filters(stack, filterPatterns), sanitizeText(filterDescription), false); |
| 161 | dialog-or-popup / tinyfd_saveFileDialog | String result = TinyFileDialogs.tinyfd_saveFileDialog(sanitizeText(title), sanitizePath(defaultFilePath), filters(stack, filterPatterns), sanitizeText(filterDescription)); |
| 171 | dialog-or-popup / tinyfd_openFileDialog | String result = TinyFileDialogs.tinyfd_openFileDialog(sanitizeText(title), sanitizePath(defaultFilePath), filters(stack, filterPatterns), sanitizeText(filterDescription), true); |
| 187 | dialog-or-popup / tinyfd_selectFolderDialog | firstPath(TinyFileDialogs.tinyfd_selectFolderDialog(sanitizeText(title), sanitizePath(defaultFolderPath)))); |
| 199 | dialog-or-popup / tinyfd_notifyPopup | TinyFileDialogs.tinyfd_notifyPopup(sanitizeText(title), sanitizeText(message), icon) == 1 ? 0 : 1); |
| 271 | dialog-or-popup / Dialog | private interface Dialog<T> { |

### 1.21.10/src/main/java/org/polyfrost/oneconfig/api/ui/v1/keybind/internal/MinecraftKeybindCodec.java

`file-74faf39c720939a18dff`；状态 **pending**；领域 keybind；组合 1.21.10/fabric, 1.21.10/neoforge。

该文件支撑 keybind；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 1.21.10/src/main/java/org/polyfrost/oneconfig/internal/mixin/render/Mixin_UiBlur.java

`file-825991221392646f17c9`；状态 **pending**；领域 blur；组合 1.21.10/fabric, 1.21.10/neoforge。

该文件支撑 blur；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 1.21.10/src/main/java/org/polyfrost/oneconfig/internal/ui/PlayerHeadBlit.java

`file-a66bc3965ef9ac1300ac`；状态 **pending**；领域 ui-support；组合 1.21.10/fabric, 1.21.10/neoforge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 1.21.10/src/main/java/org/polyfrost/oneconfig/internal/ui/PlayerHeadSkinAccess.java

`file-ed61a4fa3e95e8a3a002`；状态 **pending**；领域 ui-support；组合 1.21.10/fabric, 1.21.10/neoforge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 1.21.10/src/main/kotlin/org/polyfrost/oneconfig/api/hud/v1/LegacyHud.kt

`file-3d199d3ec1ba87aa10e7`；状态 **pending**；领域 hud-editor；组合 1.21.10/fabric, 1.21.10/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 14 | ui-function / render | abstract fun render(mcCtx: GuiGraphics) |
| 18 | ui-function / minimumSize | override fun minimumSize(): Pair<Float, Float> = width to height |
| 20 | composable / Content | @Composable |
| 21 | ui-function / Content | override fun Content() {} |

### 1.21.10/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/GuiProjection.kt

`file-bfb449888a9d2947f0ae`；状态 **pending**；领域 ui-support；组合 1.21.10/fabric, 1.21.10/neoforge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 1.21.10/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/OffscreenTarget.kt

`file-a04a370d82aeef331c6e`；状态 **pending**；领域 ui-support；组合 1.21.10/fabric, 1.21.10/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 25 | ui-function / resolve | fun resolve(w: Int, h: Int): Boolean { |
| 44 | ui-function / clear | fun clear() { |
| 51 | ui-function / destroy | fun destroy() { |
| 56 | ui-function / dispose | fun dispose() { |
| 66 | ui-function / destroyAll | fun destroyAll() { |
| 79 | ui-function / texture | fun texture(target: RenderTarget, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, alpha: Float) |

### 1.21.10/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/RenderTargetFbo.kt

`file-7b86dcaf9507334a1903`；状态 **pending**；领域 ui-support；组合 1.21.10/fabric, 1.21.10/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 47 | ui-function / unwrapDevice | private fun unwrapDevice(device: GpuDevice?): GpuDevice? { |
| 60 | ui-function / unwrapTexture | private fun unwrapTexture(texture: GpuTexture?): GlTexture? { |
| 71 | ui-function / getFboId | fun getFboId(frameBuffer: RenderTarget): Int { |
| 78 | ui-function / getColorTexId | fun getColorTexId(frameBuffer: RenderTarget): Int = |

### 1.21.10/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/BlazeRenderer.kt

`file-41e177a20925d542f3bb`；状态 **pending**；领域 ui-support；组合 1.21.10/fabric, 1.21.10/neoforge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 1.21.10/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/ComposeSceneContextImpl.kt

`file-14798e0b940372a47001`；状态 **pending**；领域 ui-support；组合 1.21.10/fabric, 1.21.10/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 28 | ui-function / requestInputMode | override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard |
| 37 | ui-function / isKeyDown | private fun isKeyDown(key: Int): Boolean { |
| 43 | keybind / isCtrlPressed | isCtrlPressed = isKeyDown(InputConstants.KEY_LCONTROL) \|\| isKeyDown(InputConstants.KEY_RCONTROL), |
| 44 | keybind / isShiftPressed | isShiftPressed = isKeyDown(InputConstants.KEY_LSHIFT) \|\| isKeyDown(InputConstants.KEY_RSHIFT), |
| 45 | keybind / isAltPressed | isAltPressed = isKeyDown(InputConstants.KEY_LALT) \|\| isKeyDown(InputConstants.KEY_RALT), |
| 49 | keybind / isMetaPressed | isMetaPressed = isKeyDown(Platform.compatibility().keys().keyLeftSuper) \|\| isKeyDown(Platform.compatibility().keys().keyRightSuper) |
| 74 | ui-function / setPointerIcon | override fun setPointerIcon(pointerIcon: PointerIcon) { |
| 81 | ui-function / resetPointerIcon | fun resetPointerIcon() { |
| 85 | ui-function / applyPointerIcon | private fun applyPointerIcon(pointerIcon: PointerIcon) { |
| 94 | ui-function / requestFocus | override fun requestFocus(): Boolean { |
| 99 | ui-function / allowCursorChanges | private fun allowCursorChanges(): Boolean { |
| 116 | ui-function / sync | fun sync() {} |
| 117 | ui-function / close | fun close() {} |
| 124 | ui-function / resetPointerIcon | fun resetPointerIcon() { |
| 128 | ui-function / updateContainerSize | fun updateContainerSize(width: Int, height: Int) { |

### 1.21.10/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/ComposeScreen.kt

`file-924257900570b418ce11`；状态 **pending**；领域 ui-support；组合 1.21.10/fabric, 1.21.10/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 66 | ui-function / getText | override fun getText(): AnnotatedString? { |
| 75 | ui-function / setText | override fun setText(annotatedString: AnnotatedString) { |
| 82 | ui-function / hasText | override fun hasText(): Boolean { |
| 93 | ui-function / getClipEntry | override suspend fun getClipEntry(): ClipEntry? { |
| 99 | ui-function / setClipEntry | override suspend fun setClipEntry(clipEntry: ClipEntry?) { |
| 112 | route / ComposeScreen | abstract class ComposeScreen( |
| 122 | composable / compose | @Composable |
| 123 | ui-function / compose | abstract fun compose() |
| 146 | ui-function / liveScene | private fun liveScene(): ComposeScene? { |
| 151 | ui-function / withScene | private inline fun <T> withScene(block: (ComposeScene) -> T): T? { |
| 166 | ui-function / sendKeyEventSafely | private inline fun sendKeyEventSafely(build: () -> androidx.compose.ui.input.key.KeyEvent): Boolean { |
| 177 | ui-function / poisonScene | private fun poisonScene(cause: Throwable) { |
| 196 | ui-function / onSceneRebuilding | protected open fun onSceneRebuilding() {} |
| 198 | ui-function / ensureScene | private fun ensureScene(): ComposeScene? { |
| 214 | ui-function / closeSceneQuietly | private fun closeSceneQuietly() { |
| 239 | ui-function / createScene | private fun createScene(): ComposeScene { |
| 283 | ui-function / init | override fun init() { |
| 312 | ui-function / bindContent | private fun bindContent(): Boolean { |
| 319 | ui-function / setContentOn | private fun setContentOn(scene: ComposeScene) { |
| 334 | ui-function / reportUnavailableAndClose | private fun reportUnavailableAndClose() { |
| 341 | ui-function / closeWithMessage | private fun closeWithMessage(reason: String) { |
| 348 | ui-function / resize | override fun resize( |
| 358 | ui-function / isPauseScreen | override fun isPauseScreen(): Boolean = false |
| 366 | ui-function / renderBackground | override fun renderBackground(ctx: GuiGraphics, mouseX: Int, mouseY: Int, tickDelta: Float) { |
| 372 | ui-function / blurAlpha | protected open fun blurAlpha(): Float = 0f |
| 379 | ui-function / disposeScene | private fun disposeScene() { |
| 384 | ui-function / releaseScene | private fun releaseScene() { |
| 392 | ui-function / onClose | override fun onClose() { |
| 397 | ui-function / removed | override fun removed() { |
| 409 | ui-function / render | override fun render(ctx: GuiGraphics, mouseX: Int, mouseY: Int, tickDelta: Float) { |
| 471 | ui-function / paint | private fun paint(ctx: RenderContext, nanos: Long) { |
| 488 | ui-function / mouseClicked | override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean { |
| 490 | keybind / KeybindRecordingBus.consumeMouse | if (KeybindRecordingBus.consumeMouse(button, true) \|\| handleMouseClicked(button)) { |
| 500 | ui-function / mouseReleased | override fun mouseReleased(event: MouseButtonEvent): Boolean { |
| 502 | keybind / KeybindRecordingBus.consumeMouse | val recordingConsumed = KeybindRecordingBus.consumeMouse(button, false) |
| 513 | ui-function / mouseScrolled | override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean { |
| 518 | ui-function / handleMouseClicked | protected open fun handleMouseClicked(button: Int): Boolean = false |
| 526 | ui-function / releaseHeldButtons | private fun releaseHeldButtons() { |
| 534 | ui-function / sendMouseButtonEvent | private fun sendMouseButtonEvent(type: PointerEventType, button: Int) { |
| 549 | ui-function / sendScrollEvent | private fun sendScrollEvent(scrollX: Double, scrollY: Double) { |
| 571 | ui-function / isPreciseScroll | private fun isPreciseScroll(scrollX: Double, scrollY: Double, now: Long, macPrecise: Boolean?): Boolean { |
| 583 | ui-function / charTyped | override fun charTyped(event: CharacterEvent): Boolean { |
| 591 | ui-function / ctrlDown | fun Int.ctrlDown() = this and InputConstants.MOD_CONTROL != 0 |
| 592 | ui-function / shiftDown | fun Int.shiftDown() = this and InputConstants.MOD_SHIFT != 0 |
| 593 | ui-function / altDown | fun Int.altDown() = this and InputConstants.MOD_ALT != 0 |
| 594 | ui-function / superDown | fun Int.superDown() = this and InputConstants.MOD_SUPER != 0 |
| 596 | ui-function / handleKeyPressed | protected open fun handleKeyPressed(key: Int, modifiers: Int): Boolean = false |
| 601 | ui-function / keyPressed | override fun keyPressed(event: McKeyEvent): Boolean { |
| 609 | ui-function / keyReleased | override fun keyReleased(event: McKeyEvent): Boolean { |
| 617 | ui-function / dispatchKeyPressed | private fun dispatchKeyPressed(bindingKey: Int, shortcutKey: Int, modifiers: Int): Boolean { |
| 619 | keybind / KeybindRecordingBus.consumeEscape | if (KeybindRecordingBus.consumeEscape()) { |
| 623 | keybind / KeybindRecordingBus.isRecording | if (KeybindRecordingBus.isRecording) { |
| 635 | ui-function / sendKeyPressedEvent | private fun sendKeyPressedEvent(bindingKey: Int, shortcutKey: Int, modifiers: Int): Boolean { |
| 645 | keybind / isCtrlPressed | isCtrlPressed = modifiers.ctrlDown(), |
| 646 | keybind / isShiftPressed | isShiftPressed = modifiers.shiftDown(), |
| 647 | keybind / isAltPressed | isAltPressed = modifiers.altDown(), |
| 648 | keybind / isMetaPressed | isMetaPressed = modifiers.superDown(), |
| 662 | ui-function / sendKeyReleasedEvent | private fun sendKeyReleasedEvent(bindingKey: Int, shortcutKey: Int, modifiers: Int): Boolean { |
| 670 | keybind / isCtrlPressed | isCtrlPressed = modifiers.ctrlDown(), |
| 671 | keybind / isShiftPressed | isShiftPressed = modifiers.shiftDown(), |
| 672 | keybind / isAltPressed | isAltPressed = modifiers.altDown(), |
| 673 | keybind / isMetaPressed | isMetaPressed = modifiers.superDown(), |
| 687 | ui-function / sendCharacterEvent | private fun sendCharacterEvent(char: Char, codePoint: Int, modifiers: Int): Boolean { |
| 699 | ui-function / sendCharKeyEvent | private fun sendCharKeyEvent(char: Char, codePoint: Int, modifiers: Int): Boolean { |
| 705 | keybind / isCtrlPressed | isCtrlPressed = modifiers.ctrlDown(), |
| 706 | keybind / isShiftPressed | isShiftPressed = modifiers.shiftDown(), |
| 707 | keybind / isAltPressed | isAltPressed = modifiers.altDown(), |
| 708 | keybind / isMetaPressed | isMetaPressed = modifiers.superDown(), |
| 722 | ui-function / modifiersToAwt | private fun modifiersToAwt(modifiers: Int): Int { |
| 731 | ui-function / syncSceneMetrics | private fun syncSceneMetrics(): Boolean { |
| 760 | ui-function / surfaceScale | private fun surfaceScale(): Float { |
| 771 | ui-function / pointerPosition | protected fun pointerPosition(): Offset { |
| 792 | ui-function / isWheelStep | fun isWheelStep(value: Double) = abs(value * 120 - round(value * 120)) < 1e-3 |
| 798 | ui-function / sceneDensity | internal fun sceneDensity(): Float { |
| 817 | ui-function / isDispatchNeeded | override fun isDispatchNeeded(context: CoroutineContext): Boolean = |
| 820 | ui-function / dispatch | override fun dispatch(context: CoroutineContext, block: Runnable) { |

### 1.21.10/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/GlHost.kt

`file-6668107f7d0ff56d0b4e`；状态 **pending**；领域 ui-support；组合 1.21.10/fabric, 1.21.10/neoforge。

对齐背景模糊开关、强度、HUD 穿透、resize 与关屏后的 GPU/画面恢复。 定位类型: blur 从实际宿主入口触发，核对完整状态变化、交互、退出及重进后的结果。 定位类型: peripheral-ui 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 71 | ui-function / unavailableReason | fun unavailableReason(): String? { |
| 86 | ui-function / setGpuTexture | fun setGpuTexture(t: GpuTexture?) { |
| 90 | ui-function / setGpuTextureView | fun setGpuTextureView(v: GpuTextureView?) { |
| 94 | ui-function / close | override fun close() { |
| 101 | ui-function / blitHud | fun blitHud(guiGraphics: GuiGraphics) { |
| 131 | ui-function / screen | fun screen(dirty: Boolean, blurAlpha: Float, underlay: GuiDraw?, paint: (RenderContext) -> Unit) { |
| 152 | ui-function / deferDebugScreen | fun deferDebugScreen(overlay: DebugScreenOverlay): Boolean { |
| 159 | ui-function / drawBeforeBlit | fun drawBeforeBlit() { |
| 165 | ui-function / drawAtPresent | fun drawAtPresent() { |
| 173 | ui-function / draw | private fun draw(fbo: Int) { |
| 179 | blur / GlBlur.release | GlBlur.release() |
| 188 | peripheral-ui / NotificationsManager.count | if (NotificationsManager.count > 0) drawGl(fbo, null) { |
| 198 | ui-function / drawGui | private fun drawGui(draw: GuiDraw) { |
| 220 | ui-function / drawBlur | private fun drawBlur(fbo: Int, alpha: Float) { |
| 226 | blur / GlBlur.draw | GlBlur.draw(fbo, w, h, alpha, client.isPaused) |
| 234 | ui-function / drawGl | private fun drawGl(fbo: Int, cached: GlRenderer?, kept: Boolean = false, draw: (RenderContext) -> Unit): GlRenderer? { |
| 253 | ui-function / maxTextureSize | private fun maxTextureSize(): Int = |
| 258 | ui-function / cachedMaxTextureSize | private fun cachedMaxTextureSize(): Int { |
| 267 | ui-function / viewportFitsTexture | private fun viewportFitsTexture(w: Int, h: Int): Boolean { |
| 286 | ui-function / reuse | private fun reuse(renderer: GlRenderer?): GlRenderer { |
| 292 | ui-function / drawHudGl | fun drawHudGl(draw: (RenderContext, RenderContext?) -> Unit) { |
| 311 | ui-function / resolveHudTarget | private fun resolveHudTarget(): RenderTarget? { |
| 341 | ui-function / destroyHudTarget | private fun destroyHudTarget() { |
| 355 | ui-function / onAllocFailure | private fun onAllocFailure(what: String, w: Int, h: Int, error: Throwable) { |
| 370 | ui-function / recreateSurface | fun recreateSurface(width: Int, height: Int) { |

### 1.21.10/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/GlRenderer.kt

`file-93b1d05c082e814fb52e`；状态 **pending**；领域 ui-support；组合 1.21.10/fabric, 1.21.10/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 41 | ui-function / moved | fun moved(at: Int) = Live(at, state, draw, key).also { it.last = last } |
| 43 | ui-function / changed | fun changed() = key == null \|\| key() != last |
| 45 | ui-function / commit | fun commit() { |
| 55 | ui-function / bitmapTexture | override fun bitmapTexture(bitmap: PolyBitmap): Any = GlTextures.bitmap(bitmap) |
| 59 | ui-function / mark | override fun mark(): Any { |
| 64 | ui-function / since | override fun since(mark: Any): Any { |
| 87 | ui-function / valid | override fun valid(captured: Any): Boolean = (captured as Captured).batches.all { GlTextures.alive(it.texture) } |
| 89 | ui-function / append | override fun append(captured: Any) { |
| 97 | ui-function / writeStateKey | override fun writeStateKey() { |
| 102 | ui-function / matches | fun matches(): Boolean = |
| 105 | ui-function / reset | fun reset() { |
| 111 | ui-function / submit | override fun submit(texture: Any, premultiplied: Boolean, nearest: Boolean, verts: FloatArray, colors: IntArray, bounds: FloatArray) { |
| 117 | ui-function / layerMark | override fun layerMark(): Any = intArrayOf(batches.size, lives.size) |
| 119 | ui-function / layer | override fun layer(mark: Any, alpha: Float) { |
| 132 | ui-function / fade | override fun fade(mark: Any, alpha: Float): Boolean { |
| 142 | ui-function / live | override fun live(draw: (PolyRenderer) -> Unit) { |
| 150 | ui-function / mcTextLive | override fun mcTextLive(text: String, x: Float, y: Float, color: () -> Int, shadow: Boolean, scale: Float) { |
| 176 | ui-function / texture | override fun texture(target: RenderTarget, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, alpha: Float) { |
| 186 | ui-function / draw | fun draw(fbo: Int, w: Int, h: Int, topDown: Boolean, clear: Boolean, kept: Boolean = false) { |
| 192 | ui-function / flushPending | private fun flushPending() = flush() |
| 194 | ui-function / replay | private fun replay(lives: List<Live>): List<GlProgram.Segment> = lives.map { l -> |
| 209 | ui-function / take | fun take(renderer: GlRenderer) { |
| 224 | ui-function / idle | private fun idle() { |
| 232 | ui-function / same | private fun same(a: List<GlProgram.Batch>, b: List<GlProgram.Batch>) = a.size == b.size && a.indices.all { a[it] === b[it] } |
| 234 | ui-function / sameLives | private fun sameLives(a: List<Live>, b: List<Live>) = |
| 237 | ui-function / coalesce | private fun coalesce() { |
| 241 | ui-function / emit | fun emit() { |
| 264 | ui-function / draw | fun draw(fbo: Int, w: Int, h: Int, topDown: Boolean, clear: Boolean, vararg layers: Layer) { |
| 292 | ui-function / stale | private fun stale(): Boolean = (live != null && changed()) \|\| batches.any { (it.texture as? Group)?.stale() == true } |
| 294 | ui-function / render | private fun render(composite: GlProgram.Batch, transient: ArrayList<Group>?, sink: ArrayList<Group>) { |
| 322 | ui-function / release | fun release() { |
| 336 | ui-function / of | fun of(batches: List<GlProgram.Batch>, live: (() -> List<GlProgram.Segment>)?, changed: () -> Boolean, alpha: Float, screenW: Int, screenH: Int): Group? { |
| 352 | ui-function / prepare | fun prepare(batches: List<GlProgram.Batch>, transient: ArrayList<Group>?, sink: ArrayList<Group>) { |
| 356 | ui-function / endFrame | fun endFrame() { |
| 361 | ui-function / recycle | private fun recycle(t: IntArray) { |
| 365 | ui-function / acquire | private fun acquire(w: Int, h: Int): IntArray { |
| 381 | ui-function / delete | private fun delete(t: IntArray) { |
| 392 | ui-function / segments | fun segments(lives: List<Live>): List<GlProgram.Segment> { |
| 397 | ui-function / drawBatches | fun drawBatches(fbo: Int, w: Int, h: Int, topDown: Boolean, clear: Boolean, batches: List<GlProgram.Batch>, segments: List<GlProgram.Segment>, kept: Boolean, arena: Boolean) { |
| 426 | ui-function / sameKey | fun sameKey(o: Batch) = texture === o.texture && premultiplied == o.premultiplied && nearest == o.nearest |
| 428 | ui-function / faded | fun faded(alpha: Float) = Batch(texture, premultiplied, nearest, verts, IntArray(colors.size) { i -> |
| 444 | ui-function / merge | fun merge(list: List<Batch>): Batch { |
| 493 | ui-function / init | private fun init() { |
| 520 | ui-function / shader | private fun shader(type: Int, src: String): Int { |
| 528 | ui-function / invalidate | fun invalidate(b: Batch) { |
| 534 | ui-function / draw | fun draw(batches: List<Batch>, w: Int, h: Int, topDown: Boolean, live: List<Segment> = emptyList(), kept: Boolean = false, ox: Float = 0f, oy: Float = 0f) { |
| 578 | ui-function / drawArena | fun drawArena(batches: List<Batch>, w: Int, h: Int, topDown: Boolean, live: List<Segment>) { |
| 642 | ui-function / use | fun use(id: Int) { |
| 673 | ui-function / attribPointers | private fun attribPointers() { |
| 679 | ui-function / setupState | private fun setupState(w: Int, h: Int, topDown: Boolean, ox: Float = 0f, oy: Float = 0f) { |
| 696 | ui-function / drawBatch | private fun drawBatch(b: Batch, first: Int) { |
| 712 | ui-function / filter | private fun filter(mode: Int) { |
| 717 | ui-function / upload | private fun upload(at: Int, batches: List<Batch>): Int { |
| 751 | ui-function / ensureIndices | private fun ensureIndices(quads: Int) { |
| 799 | ui-function / bitmap | fun bitmap(bitmap: PolyBitmap): Tex = bitmaps.getOrPut(bitmap) { |
| 804 | ui-function / external | fun external(id: Int): Tex = external.getOrPut(id) { Tex(null, id, 0) } |
| 806 | ui-function / alive | fun alive(key: Any): Boolean = key !is Tex \|\| key.bitmap == null \|\| bitmaps[key.bitmap] === key |
| 808 | ui-function / id | fun id(key: Any): Int = when (key) { |
| 819 | ui-function / glyphPage | private fun glyphPage(page: PolyGlyphPage.Page): Int { |
| 836 | ui-function / upload | private fun upload(bitmap: PolyBitmap): Int { |
| 845 | ui-function / unpackDefaults | private fun unpackDefaults(rowLength: Int) { |
| 853 | ui-function / newTexture | private fun newTexture(): Int { |
| 863 | ui-function / genTexture | private fun genTexture(): Int { |
| 867 | ui-function / bind | private fun bind(id: Int) { |
| 871 | ui-function / texParameter | private fun texParameter(name: Int, value: Int) { |
| 875 | ui-function / pixelStore | private fun pixelStore(name: Int, value: Int) { |
| 879 | ui-function / texImage | private fun texImage(w: Int, h: Int, px: IntBuffer) { |
| 883 | ui-function / texSubImage | private fun texSubImage(y: Int, w: Int, h: Int, px: IntBuffer) { |
| 887 | ui-function / deleteTexture | private fun deleteTexture(id: Int) { |
| 891 | ui-function / endFrame | fun endFrame() { |

### 1.21.10/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/MacScrollEvent.kt

`file-c2dc531c0c6c866f0858`；状态 **pending**；领域 ui-support；组合 1.21.10/fabric, 1.21.10/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 18 | ui-function / readScroll | fun readScroll(): Boolean? = try { |

### 1.21.10/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/McFontService.kt

`file-2a216b9c159619a488d5`；状态 **pending**；领域 font-language；组合 1.21.10/fabric, 1.21.10/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 20 | font-language / McFontService | object McFontService { |
| 23 | ui-function / init | fun init() { |
| 27 | font-language / McResourceFont.init | McResourceFont.init() |
| 30 | ui-function / installBundledFonts | private fun installBundledFonts() { |
| 31 | font-language / McFontService | val loader = McFontService::class.java.classLoader |
| 33 | font-language / UiFontFiles.DEFAULT_REGULAR | "assets/oneconfig/fonts/minecraft/Minecraft-Regular.otf" to UiFontFiles.DEFAULT_REGULAR, |
| 34 | font-language / UiFontFiles.DEFAULT_BOLD | "assets/oneconfig/fonts/minecraft/Minecraft-Bold.otf" to UiFontFiles.DEFAULT_BOLD, |
| 46 | ui-function / installHostFonts | private fun installHostFonts() { |
| 47 | font-language / UiFontFiles.readRegular | val regular = UiFontFiles.readRegular() |
| 54 | font-language / UiFontFiles.REGULAR | FontManager.replace(UiFontFiles.REGULAR, regular) |
| 55 | font-language / UiFontFiles.readBold | val bold = UiFontFiles.readBold() ?: regular |
| 57 | font-language / UiFontFiles.BOLD | FontManager.replace(UiFontFiles.BOLD, bold) |
| 60 | font-language / UiFontFiles.BOLD | FontManager.replace(UiFontFiles.BOLD, regular) |
| 62 | font-language / UiFontFiles.REGULAR | FontManager.setDefault(UiFontFiles.REGULAR) |
| 68 | font-language / UiFontFiles.readFallback | val fallback = UiFontFiles.readFallback() |
| 71 | font-language / UiFontFiles.FALLBACK | FontManager.replace(UiFontFiles.FALLBACK, fallback) |
| 82 | ui-function / installEngines | fun installEngines() { |

### 1.21.10/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/McResourceFont.kt

`file-48e7c24334fef651d8c7`；状态 **pending**；领域 font-language；组合 1.21.10/fabric, 1.21.10/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 28 | font-language / McResourceFont | internal object McResourceFont { |
| 40 | ui-function / glyph | fun glyph(cp: Int): McGlyph? = glyphs[cp] ?: unihex[cp]?.let { u -> baked.getOrPut(cp) { bake(u) } } |
| 47 | ui-function / init | fun init() { |
| 55 | ui-function / reload | private fun reload() { |
| 74 | ui-function / fontOptions | private fun fontOptions(): Int { |
| 82 | ui-function / load | private fun load(rm: ResourceManager, options: Int): Font? { |
| 88 | ui-function / unclaimed | fun unclaimed(cp: Int) = cp !in glyphs && cp !in unihex |
| 106 | ui-function / collectProviders | private fun collectProviders(rm: ResourceManager, ref: String, out: MutableList<JsonObject>, visited: MutableSet<String>, options: Int) { |
| 122 | ui-function / filterPasses | private fun filterPasses(provider: JsonObject, options: Int): Boolean { |
| 129 | ui-function / loadBitmap | private fun loadBitmap(rm: ResourceManager, provider: JsonObject): Map<Int, McGlyph> { |
| 156 | ui-function / loadUnihex | private fun loadUnihex(rm: ResourceManager, provider: JsonObject): Map<Int, Unihex> { |
| 181 | ui-function / parseHex | private fun parseHex(line: String, overrides: List<IntArray>, out: MutableMap<Int, Unihex>): Boolean { |
| 209 | ui-function / bake | private fun bake(u: Unihex): McGlyph { |
| 221 | ui-function / read | private fun read(rm: ResourceManager, ref: String, folder: String): ByteArray? = |
| 224 | ui-function / id | private fun id(ref: String, folder: String, suffix: String = "") = |

### 1.21.10/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/MinecraftKeyboardAdapter.kt

`file-370e341ca7cf393c5857`；状态 **pending**；领域 ui-support；组合 1.21.10/fabric, 1.21.10/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 13 | ui-function / keyLocation | fun keyLocation(bindingKey: Int): Int  = when (bindingKey) { |
| 20 | ui-function / toAwtKeyCode | fun toAwtKeyCode(shortcutKey: Int): Int = when (shortcutKey) { |

### 1.21.10/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/UiBlur.kt

`file-405eac0665f6eb6d373e`；状态 **pending**；领域 blur；组合 1.21.10/fabric, 1.21.10/neoforge。

该文件支撑 blur；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 1.21.10/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/impls/HudEditorUIScreen.kt

`file-77863e5fb9a689409ddd`；状态 **pending**；领域 hud-editor；组合 1.21.10/fabric, 1.21.10/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 37 | route / HudEditorUIScreen | class HudEditorUIScreen private constructor() : ComposeScreen() { |
| 42 | ui-function / open | fun open(fromOneConfig: Boolean): HudEditorUIScreen { |
| 57 | ui-function / beginClose | private fun beginClose() { |
| 62 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLOSE) |
| 62 | sound / UiSoundEvent.CLOSE | UiSounds.play(UiSoundEvent.CLOSE) |
| 63 | sound / UiSounds.releaseAmbience | UiSounds.releaseAmbience() |
| 63 | sound / releaseAmbience | UiSounds.releaseAmbience() |
| 66 | ui-function / cancelClose | private fun cancelClose(): Boolean { |
| 70 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.OPEN) |
| 70 | sound / UiSoundEvent.OPEN | UiSounds.play(UiSoundEvent.OPEN) |
| 71 | sound / UiSounds.acquireAmbience | UiSounds.acquireAmbience() |
| 71 | sound / acquireAmbience | UiSounds.acquireAmbience() |
| 79 | ui-function / returnToOneConfig | private fun returnToOneConfig() { |
| 87 | ui-function / init | override fun init() { |
| 96 | sound / UiSounds.acquireAmbience | UiSounds.acquireAmbience() |
| 96 | sound / acquireAmbience | UiSounds.acquireAmbience() |
| 102 | ui-function / removed | override fun removed() { |
| 108 | sound / UiSounds.releaseAmbience | UiSounds.releaseAmbience() |
| 108 | sound / releaseAmbience | UiSounds.releaseAmbience() |
| 114 | ui-function / isPauseScreen | override fun isPauseScreen(): Boolean = OneConfigConfig.pauseGame |
| 116 | ui-function / handleOneConfigKeybind | private fun handleOneConfigKeybind(): Boolean { |
| 128 | ui-function / handleKeyPressed | override fun handleKeyPressed(key: Int, modifiers: Int): Boolean { |
| 137 | keybind / KeybindRecordingBus.isRecording | if (toggleKey != null && key == toggleKey && !KeybindRecordingBus.isRecording) { |
| 143 | ui-function / handleMouseClicked | override fun handleMouseClicked(button: Int): Boolean { |
| 144 | keybind / KeybindRecordingBus.isRecording | if (KeybindRecordingBus.isRecording) return false |
| 147 | keybind / KeybindManager.isTriggeredByMouse | KeybindManager.isTriggeredByMouse(OneConfigConfig.oneConfigKeybind, button) |
| 155 | ui-function / render | override fun render(ctx: GuiGraphics, mouseX: Int, mouseY: Int, tickDelta: Float) { |
| 175 | composable / compose | @Composable |
| 176 | ui-function / compose | override fun compose() { |
| 197 | animation / RetainedVisibility | RetainedVisibility( |
| 199 | animation / tween | enter = tween(200, easing = EaseOutCubic), |
| 200 | animation / tween | exit = tween(exitMs, easing = EaseIn), |

### 1.21.10/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/impls/OneConfigUIScreen.kt

`file-2d5a6d7e660d113e1b67`；状态 **pending**；领域 shell-navigation；组合 1.21.10/fabric, 1.21.10/neoforge。

对齐背景模糊开关、强度、HUD 穿透、resize 与关屏后的 GPU/画面恢复。 定位类型: blur 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 80 | route / OneConfigUIScreen | class OneConfigUIScreen @JvmOverloads constructor( |
| 92 | ui-function / blurAlpha | override fun blurAlpha(): Float { |
| 93 | blur / enableBackgroundBlur | if (!OneConfigConfig.enableBackgroundBlur) return 0f |
| 111 | ui-function / shared | private fun shared(): OneConfigUIScreen = |
| 115 | ui-function / forRoute | fun forRoute(route: Any?): OneConfigUIScreen = shared().also { |
| 121 | ui-function / open | fun open(): OneConfigUIScreen = shared().also { |
| 128 | ui-function / resume | fun resume(): OneConfigUIScreen = shared().also { |
| 142 | ui-function / scheduleSave | private fun scheduleSave() { |
| 155 | route / OpeningRoute | private data class OpeningRoute(val route: Any, val restored: Boolean = false) |
| 157 | ui-function / resolveOpeningRoute | private fun resolveOpeningRoute(): OpeningRoute { |
| 177 | ui-function / stopWarmUp | fun stopWarmUp() { |
| 190 | ui-function / dropPrerasteredLater | private fun dropPrerasteredLater() { |
| 197 | ui-function / prepareWarmUp | fun prepareWarmUp(): Runnable? { |
| 212 | ui-function / warmUp | private fun warmUp(w: Int, h: Int, density: Float, surface: Float) { |
| 222 | ui-function / warmFrames | private fun warmFrames(w: Int, h: Int, density: Float, surface: Float) { |
| 227 | ui-function / isDispatchNeeded | override fun isDispatchNeeded(context: CoroutineContext) = Thread.currentThread() !== thread |
| 228 | ui-function / dispatch | override fun dispatch(context: CoroutineContext, block: Runnable) { |
| 232 | ui-function / drain | fun drain() { |
| 292 | ui-function / openLastSession | fun openLastSession() { |
| 303 | ui-function / beginClose | private fun beginClose() { |
| 308 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLOSE) |
| 308 | sound / UiSoundEvent.CLOSE | UiSounds.play(UiSoundEvent.CLOSE) |
| 309 | sound / UiSounds.releaseAmbience | UiSounds.releaseAmbience() |
| 309 | sound / releaseAmbience | UiSounds.releaseAmbience() |
| 312 | ui-function / cancelClose | private fun cancelClose(): Boolean { |
| 323 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.OPEN) |
| 323 | sound / UiSoundEvent.OPEN | UiSounds.play(UiSoundEvent.OPEN) |
| 324 | sound / UiSounds.acquireAmbience | UiSounds.acquireAmbience() |
| 324 | sound / acquireAmbience | UiSounds.acquireAmbience() |
| 340 | ui-function / markClosed | private fun markClosed() { |
| 344 | ui-function / init | override fun init() { |
| 416 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.OPEN) |
| 416 | sound / UiSoundEvent.OPEN | UiSounds.play(UiSoundEvent.OPEN) |
| 417 | sound / UiSounds.acquireAmbience | UiSounds.acquireAmbience() |
| 417 | sound / acquireAmbience | UiSounds.acquireAmbience() |
| 427 | ui-function / onSceneRebuilding | override fun onSceneRebuilding() { |
| 433 | ui-function / removed | override fun removed() { |
| 438 | sound / UiSounds.releaseAmbience | UiSounds.releaseAmbience() |
| 438 | sound / releaseAmbience | UiSounds.releaseAmbience() |
| 444 | ui-function / isPauseScreen | override fun isPauseScreen(): Boolean = OneConfigConfig.pauseGame |
| 446 | ui-function / handleOneConfigKeybind | private fun handleOneConfigKeybind(): Boolean { |
| 458 | ui-function / handleKeyPressed | override fun handleKeyPressed(key: Int, modifiers: Int): Boolean { |
| 467 | keybind / KeybindRecordingBus.isRecording | if (toggleKey != null && key == toggleKey && !KeybindRecordingBus.isRecording) { |
| 474 | ui-function / handleMouseClicked | override fun handleMouseClicked(button: Int): Boolean { |
| 475 | keybind / KeybindRecordingBus.isRecording | if (KeybindRecordingBus.isRecording) return false |
| 478 | keybind / KeybindManager.isTriggeredByMouse | KeybindManager.isTriggeredByMouse(OneConfigConfig.oneConfigKeybind, button) |
| 485 | keybind / KeybindManager.hasTriggeredMouseBind | if (KeybindManager.hasTriggeredMouseBind(button)) return true |
| 486 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLICK) |
| 486 | sound / UiSoundEvent.CLICK | UiSounds.play(UiSoundEvent.CLICK) |
| 491 | keybind / KeybindManager.hasTriggeredMouseBind | if (KeybindManager.hasTriggeredMouseBind(button)) return true |
| 492 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLICK) |
| 492 | sound / UiSoundEvent.CLICK | UiSounds.play(UiSoundEvent.CLICK) |
| 502 | ui-function / render | override fun render(ctx: GuiGraphics, mouseX: Int, mouseY: Int, tickDelta: Float) { |
| 524 | ui-function / easeOutExpo | private fun easeOutExpo(progress: Float): Float { |
| 532 | composable / compose | @Composable |
| 533 | ui-function / compose | override fun compose() { |
| 565 | ui-function / submit | override fun submit(texture: Any, premultiplied: Boolean, nearest: Boolean, verts: FloatArray, colors: IntArray, bounds: FloatArray) {} |
| 567 | ui-function / bitmapTexture | override fun bitmapTexture(bitmap: PolyBitmap): Any = bitmap |
| 569 | ui-function / live | override fun live(draw: (PolyRenderer) -> Unit) = draw(this) |

### 1.21.10/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/opengl/StoredGLState.kt

`file-3314a3ab4bcd3b4740b3`；状态 **pending**；领域 ui-support；组合 1.21.10/fabric, 1.21.10/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 10 | ui-function / resyncTextureBindCache | fun resyncTextureBindCache() { |
| 21 | ui-function / capture | fun capture(): StoredGLState { |
| 102 | ui-function / restore | fun restore(): StoredGLState { |
| 195 | ui-function / forceToggle | private fun forceToggle(enabled: Boolean, enable: () -> Unit, disable: () -> Unit) { |
| 205 | ui-function / forceDepthFunc | private fun forceDepthFunc(func: Int) { |
| 211 | ui-function / forceDepthMask | private fun forceDepthMask(mask: Boolean) { |

### 1.21.10/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/CompatOverlayRenderer.kt

`file-fc1a268d83640a988ba0`；状态 **pending**；领域 hud-editor；组合 1.21.10/fabric, 1.21.10/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 14 | ui-function / register | fun register(hook: (GuiGraphics) -> Unit) { |
| 19 | ui-function / hasHooks | fun hasHooks(): Boolean = hooks.isNotEmpty() |
| 22 | ui-function / oneConfigScreenOpen | fun oneConfigScreenOpen(): Boolean = HudManager.isEditing |
| 25 | ui-function / render | fun render(ctx: GuiGraphics) { |

### 1.21.10/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/LegacyHudRenderer.kt

`file-a8671d5be1db16197b34`；状态 **pending**；领域 hud-editor；组合 1.21.10/fabric, 1.21.10/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 32 | ui-function / reportFailure | private fun reportFailure(hud: LegacyHud, e: Throwable) { |
| 47 | ui-function / renderLive | fun renderLive(graphics: GuiGraphics) { |
| 52 | ui-function / renderLiveHuds | private fun renderLiveHuds(graphics: GuiGraphics) { |

### 1.21.10/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/keybind/MinecraftKeybindProvider.kt

`file-50235760475e6c7b780d`；状态 **pending**；领域 keybind；组合 1.21.10/fabric, 1.21.10/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 22 | keybind / OneConfigKeybind | private val properties = IdentityHashMap<KeyMapping, Property<OneConfigKeybind>>() |
| 37 | ui-function / groups | override fun groups(): List<KeybindGroup> { |
| 77 | ui-function / managedMappings | fun managedMappings(): List<KeyMapping> { |
| 82 | keybind / OneConfigKeybind | private fun propertyFor(mapping: KeyMapping): Property<OneConfigKeybind> { |
| 82 | ui-function / propertyFor | private fun propertyFor(mapping: KeyMapping): Property<OneConfigKeybind> { |
| 89 | keybind / OneConfigKeybind | OneConfigKeybind::class.java, |
| 99 | ui-function / ownerMod | private fun ownerMod(mapping: KeyMapping, modsById: Map<String, ModInfo>): ModInfo? { |
| 105 | ui-function / ownerCandidates | private fun ownerCandidates(keyName: String): Sequence<String> = sequence { |
| 117 | ui-function / modIcon | private fun modIcon(owner: ModInfo?): String? = |
| 124 | ui-function / minecraftIcon | private fun minecraftIcon(): String { |
| 132 | ui-function / extractMinecraftIcon | private fun extractMinecraftIcon(): String? = runCatching { |
| 148 | ui-function / groupTitle | private fun groupTitle(category: String, owner: ModInfo?): String { |
| 154 | ui-function / sanitizeId | private fun sanitizeId(value: String): String { |
| 161 | keybind / OneConfigKeybind | private fun KeyMapping.toOneConfigKeybind(): OneConfigKeybind { |
| 161 | ui-function / toOneConfigKeybind | private fun KeyMapping.toOneConfigKeybind(): OneConfigKeybind { |
| 166 | keybind / Key.toOneConfigKeybind | private fun InputConstants.Key.toOneConfigKeybind(): OneConfigKeybind { |
| 166 | keybind / OneConfigKeybind | private fun InputConstants.Key.toOneConfigKeybind(): OneConfigKeybind { |
| 166 | ui-function / toOneConfigKeybind | private fun InputConstants.Key.toOneConfigKeybind(): OneConfigKeybind { |
| 171 | keybind / OneConfigKeybind | OneConfigKeybind(intArrayOf(value), null, KeyModifiers.NONE, 0L) { true } |
| 174 | keybind / OneConfigKeybind | OneConfigKeybind(null, intArrayOf(value), KeyModifiers.NONE, 0L) { true } |
| 175 | keybind / OneConfigKeybind | else -> OneConfigKeybind(null, null, KeyModifiers.NONE, 0L) { true } |
| 179 | keybind / OneConfigKeybind | private fun KeyMapping.applyOneConfigKeybind(keybind: OneConfigKeybind?) { |
| 179 | ui-function / applyOneConfigKeybind | private fun KeyMapping.applyOneConfigKeybind(keybind: OneConfigKeybind?) { |
| 193 | ui-function / categoryLabel | private fun categoryLabel(mapping: KeyMapping): String { |
| 199 | ui-function / translate | private fun translate(key: String): String { |
| 211 | ui-function / isOneConfigMirror | private fun isOneConfigMirror(@Suppress("UNUSED_PARAMETER") mapping: KeyMapping): Boolean = false |

### 1.21.10/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/sound/McUiSoundService.kt

`file-837ea861a2c99259cd72`；状态 **pending**；领域 sound；组合 1.21.10/fabric, 1.21.10/neoforge。

对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 19 | sound / McUiSoundService | class McUiSoundService : UiSoundService { |
| 19 | sound / UiSoundService | class McUiSoundService : UiSoundService { |
| 28 | sound / UiSoundTheme | private val ambienceShuffle = mutableMapOf<UiSoundTheme, MutableList<String>>() |
| 29 | sound / UiSoundTheme | private val lastAmbienceSoundId = mutableMapOf<UiSoundTheme, String>() |
| 33 | sound / UiSoundEvent | override fun play(event: UiSoundEvent, theme: UiSoundTheme, volume: Float) { |
| 33 | sound / UiSoundTheme | override fun play(event: UiSoundEvent, theme: UiSoundTheme, volume: Float) { |
| 33 | ui-function / play | override fun play(event: UiSoundEvent, theme: UiSoundTheme, volume: Float) { |
| 37 | sound / UiSoundTheme.MODERN | UiSoundTheme.MODERN -> playModern(event, volume) |
| 38 | sound / UiSoundTheme.MINECRAFT | UiSoundTheme.MINECRAFT -> playMinecraft(event, volume) |
| 44 | sound / UiSoundEvent | private fun playModern(event: UiSoundEvent, volume: Float) { |
| 44 | ui-function / playModern | private fun playModern(event: UiSoundEvent, volume: Float) { |
| 46 | sound / UiSoundEvent.OPEN | UiSoundEvent.OPEN -> playModernSound("ui.open", 1f, volume) |
| 47 | sound / UiSoundEvent.CLOSE | UiSoundEvent.CLOSE -> playModernSound("ui.close", 1f, volume) |
| 48 | sound / UiSoundEvent.CLICK | UiSoundEvent.CLICK -> playModernSound("ui.click", 1f, volume) |
| 49 | sound / UiSoundEvent.SLIDER_TICK | UiSoundEvent.SLIDER_TICK -> playModernSound("ui.slider", 1f, volume) |
| 50 | sound / UiSoundEvent.HUD_SELECT | UiSoundEvent.HUD_SELECT -> playModernSound("ui.click", 1.0f, volume) |
| 51 | sound / UiSoundEvent.HUD_DRAG_START | UiSoundEvent.HUD_DRAG_START -> playModernSound("ui.click", 0.9f, volume) |
| 52 | sound / UiSoundEvent.HUD_DRAG_END | UiSoundEvent.HUD_DRAG_END -> playModernSound("ui.click", 1.1f, volume) |
| 53 | sound / UiSoundEvent.HUD_RESIZE_END | UiSoundEvent.HUD_RESIZE_END -> playModernSound("ui.click", 1.0f, volume) |
| 58 | ui-function / playModernSound | private fun playModernSound(id: String, pitch: Float, volume: Float) { |
| 63 | sound / UiSoundEvent | private fun playMinecraft(event: UiSoundEvent, volume: Float) { |
| 63 | ui-function / playMinecraft | private fun playMinecraft(event: UiSoundEvent, volume: Float) { |
| 65 | sound / UiSoundEvent.CLICK | UiSoundEvent.CLICK -> playVanilla(SoundEvents.UI_BUTTON_CLICK, 1f, 0.5f * volume) |
| 66 | sound / UiSoundEvent.OPEN | UiSoundEvent.OPEN -> playModernSound("ui.open", 1f, volume) |
| 67 | sound / UiSoundEvent.CLOSE | UiSoundEvent.CLOSE -> playModernSound("ui.close", 1f, volume) |
| 68 | sound / UiSoundEvent.SLIDER_TICK | UiSoundEvent.SLIDER_TICK -> { |
| 73 | sound / UiSoundEvent.HUD_SELECT | UiSoundEvent.HUD_SELECT -> playVanilla(SoundEvents.UI_BUTTON_CLICK, 1f, 0.3f * volume) |
| 74 | sound / UiSoundEvent.HUD_DRAG_END | UiSoundEvent.HUD_DRAG_END -> playVanilla(SoundEvents.UI_BUTTON_CLICK, 1f, 0.3f * volume) |
| 75 | sound / UiSoundEvent.HUD_RESIZE_END | UiSoundEvent.HUD_RESIZE_END -> playVanilla(SoundEvents.UI_BUTTON_CLICK, 1f, 0.3f * volume) |
| 80 | ui-function / playVanilla | private fun playVanilla(event: SoundEvent, pitch: Float, volume: Float) { |
| 85 | ui-function / playVanilla | private fun playVanilla(holder: Holder<SoundEvent>, pitch: Float, volume: Float) = |
| 88 | sound / startAmbience | override fun startAmbience(theme: UiSoundTheme, volume: Float) { |
| 88 | sound / UiSoundTheme | override fun startAmbience(theme: UiSoundTheme, volume: Float) { |
| 88 | ui-function / startAmbience | override fun startAmbience(theme: UiSoundTheme, volume: Float) { |
| 109 | sound / stopAmbience | override fun stopAmbience(@Suppress("UNUSED_PARAMETER") setDucking: Boolean) { |
| 109 | ui-function / stopAmbience | override fun stopAmbience(@Suppress("UNUSED_PARAMETER") setDucking: Boolean) { |
| 118 | sound / UiSoundTheme | private fun ambienceVolumeFor(theme: UiSoundTheme, volume: Float): Float = when (theme) { |
| 118 | ui-function / ambienceVolumeFor | private fun ambienceVolumeFor(theme: UiSoundTheme, volume: Float): Float = when (theme) { |
| 119 | sound / UiSoundTheme.MODERN | UiSoundTheme.MODERN -> 0.5f * volume |
| 120 | sound / UiSoundTheme.MINECRAFT | UiSoundTheme.MINECRAFT -> 0.08f * volume |
| 123 | sound / UiSoundTheme | private fun ambienceSoundIds(theme: UiSoundTheme): List<String> = when (theme) { |
| 123 | ui-function / ambienceSoundIds | private fun ambienceSoundIds(theme: UiSoundTheme): List<String> = when (theme) { |
| 124 | sound / UiSoundTheme.MODERN | UiSoundTheme.MODERN -> listOf("ui.pad_loop") |
| 125 | sound / UiSoundTheme.MINECRAFT | UiSoundTheme.MINECRAFT -> listOf( |
| 133 | sound / UiSoundTheme | private fun playAmbienceInstance(theme: UiSoundTheme, targetVolume: Float): FadingLoopSoundInstance { |
| 133 | ui-function / playAmbienceInstance | private fun playAmbienceInstance(theme: UiSoundTheme, targetVolume: Float): FadingLoopSoundInstance { |
| 144 | sound / UiSoundTheme | private fun nextAmbienceSoundId(theme: UiSoundTheme, ids: List<String>): String { |
| 144 | ui-function / nextAmbienceSoundId | private fun nextAmbienceSoundId(theme: UiSoundTheme, ids: List<String>): String { |
| 165 | ui-function / driveAmbienceLoop | private fun driveAmbienceLoop() { |
| 196 | sound / UiSoundTheme | val theme: UiSoundTheme, |
| 215 | ui-function / beginFadeOut | fun beginFadeOut() { |
| 219 | ui-function / cancelFadeOut | fun cancelFadeOut() { |
| 224 | ui-function / updateTargetVolume | fun updateTargetVolume(newTarget: Float) { |
| 229 | ui-function / tick | override fun tick() { |

### 1.21.11/fabric/src/main/java/org/polyfrost/oneconfig/internal/OneConfig.java

`file-c6681e64357eb78bd1bd`；状态 **pending**；领域 ui-support；组合 1.21.11/fabric。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 235 | search-provider / SearchCorpus.INSTANCE | EventManager.register(ResourceFinishedLoading.class, e -> SearchCorpus.INSTANCE.init()); |
| 334 | font-language / McFontService.INSTANCE | McFontService.INSTANCE.init(); |

### 1.21.11/fabric/src/main/java/org/polyfrost/oneconfig/internal/OneConfigPreLaunch.java

`file-b76a498b3fb48267c526`；状态 **pending**；领域 ui-support；组合 1.21.11/fabric。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 25 | font-language / McFontService.installEngines | McFontService.installEngines(); |

### 1.21.11/neoforge/src/main/java/org/polyfrost/oneconfig/internal/OneConfig.java

`file-0c7267f066252dfa9066`；状态 **pending**；领域 ui-support；组合 1.21.11/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 234 | search-provider / SearchCorpus.INSTANCE | EventManager.register(ResourceFinishedLoading.class, e -> SearchCorpus.INSTANCE.init()); |
| 344 | font-language / McFontService.INSTANCE | McFontService.INSTANCE.init(); |

### 1.21.11/neoforge/src/main/java/org/polyfrost/oneconfig/internal/OneConfigPreLaunch.java

`file-f51e52156ef0ae5b1f61`；状态 **pending**；领域 ui-support；组合 1.21.11/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 22 | font-language / McFontService.installEngines | org.polyfrost.oneconfig.internal.ui.compose.McFontService.installEngines(); |

### 1.21.11/src/main/java/org/polyfrost/oneconfig/api/platform/v1/internal/I18nPlatformImpl.java

`file-1234160961072dee1439`；状态 **pending**；领域 ui-support；组合 1.21.11/fabric, 1.21.11/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 39 | font-language / I18nPlatform | public class I18nPlatformImpl implements I18nPlatform { |

### 1.21.11/src/main/java/org/polyfrost/oneconfig/api/platform/v1/internal/KeysImpl.java

`file-40fd39c91251a1704740`；状态 **pending**；领域 ui-support；组合 1.21.11/fabric, 1.21.11/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 21 | keybind / getKeyA | public int getKeyA() { |
| 26 | keybind / getKeyC | public int getKeyC() { |
| 31 | keybind / getKeyD | public int getKeyD() { |
| 36 | keybind / getKeyE | public int getKeyE() { |
| 41 | keybind / getKeyF | public int getKeyF() { |
| 46 | keybind / getKeyH | public int getKeyH() { |
| 51 | keybind / getKeyL | public int getKeyL() { |
| 56 | keybind / getKeyR | public int getKeyR() { |
| 61 | keybind / getKeyV | public int getKeyV() { |
| 66 | keybind / getKeyX | public int getKeyX() { |
| 71 | keybind / getKeyY | public int getKeyY() { |
| 76 | keybind / getKeyZ | public int getKeyZ() { |
| 81 | keybind / getKeyDelete | public int getKeyDelete() { |
| 91 | keybind / getKeyLeftShift | public int getKeyLeftShift() { |
| 96 | keybind / getKeyRightShift | public int getKeyRightShift() { |
| 101 | keybind / getKeyLeftControl | public int getKeyLeftControl() { |
| 106 | keybind / getKeyRightControl | public int getKeyRightControl() { |
| 111 | keybind / getKeyLeftAlt | public int getKeyLeftAlt() { |
| 116 | keybind / getKeyRightAlt | public int getKeyRightAlt() { |
| 121 | keybind / getKeyLeftSuper | public int getKeyLeftSuper() { |
| 126 | keybind / getKeyRightSuper | public int getKeyRightSuper() { |

### 1.21.11/src/main/java/org/polyfrost/oneconfig/api/ui/v1/api/internal/TinyFdApiImpl.java

`file-e7b5d6edd720ef738266`；状态 **pending**；领域 ui-support；组合 1.21.11/fabric, 1.21.11/neoforge。

核对打开/取消/确认、焦点、边界定位、外点关闭与提交后可见结果。 定位类型: dialog-or-popup

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 74 | dialog-or-popup / Dialog | private static <T> T open(String description, T fallback, Dialog<T> dialog) { |
| 151 | dialog-or-popup / tinyfd_openFileDialog | String result = TinyFileDialogs.tinyfd_openFileDialog(sanitizeText(title), sanitizePath(defaultFilePath), filters(stack, filterPatterns), sanitizeText(filterDescription), false); |
| 161 | dialog-or-popup / tinyfd_saveFileDialog | String result = TinyFileDialogs.tinyfd_saveFileDialog(sanitizeText(title), sanitizePath(defaultFilePath), filters(stack, filterPatterns), sanitizeText(filterDescription)); |
| 171 | dialog-or-popup / tinyfd_openFileDialog | String result = TinyFileDialogs.tinyfd_openFileDialog(sanitizeText(title), sanitizePath(defaultFilePath), filters(stack, filterPatterns), sanitizeText(filterDescription), true); |
| 187 | dialog-or-popup / tinyfd_selectFolderDialog | firstPath(TinyFileDialogs.tinyfd_selectFolderDialog(sanitizeText(title), sanitizePath(defaultFolderPath)))); |
| 199 | dialog-or-popup / tinyfd_notifyPopup | TinyFileDialogs.tinyfd_notifyPopup(sanitizeText(title), sanitizeText(message), icon) == 1 ? 0 : 1); |
| 271 | dialog-or-popup / Dialog | private interface Dialog<T> { |

### 1.21.11/src/main/java/org/polyfrost/oneconfig/api/ui/v1/keybind/internal/MinecraftKeybindCodec.java

`file-cff28c94ed4bb961cdbc`；状态 **pending**；领域 keybind；组合 1.21.11/fabric, 1.21.11/neoforge。

该文件支撑 keybind；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 1.21.11/src/main/java/org/polyfrost/oneconfig/internal/mixin/render/Mixin_UiBlur.java

`file-12e185b45e0f070c873c`；状态 **pending**；领域 blur；组合 1.21.11/fabric, 1.21.11/neoforge。

对齐背景模糊开关、强度、HUD 穿透、resize 与关屏后的 GPU/画面恢复。 定位类型: blur

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 14 | blur / UiBlur.process | if (UiBlur.process()) ci.cancel(); |

### 1.21.11/src/main/java/org/polyfrost/oneconfig/internal/ui/PlayerHeadBlit.java

`file-826b6d6988ffd0b87aa0`；状态 **pending**；领域 ui-support；组合 1.21.11/fabric, 1.21.11/neoforge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 1.21.11/src/main/java/org/polyfrost/oneconfig/internal/ui/PlayerHeadSkinAccess.java

`file-90964b5faf9d7fb4029c`；状态 **pending**；领域 ui-support；组合 1.21.11/fabric, 1.21.11/neoforge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 1.21.11/src/main/kotlin/org/polyfrost/oneconfig/api/hud/v1/LegacyHud.kt

`file-16a2c32519e339db1255`；状态 **pending**；领域 hud-editor；组合 1.21.11/fabric, 1.21.11/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 14 | ui-function / render | abstract fun render(mcCtx: GuiGraphics) |
| 18 | ui-function / minimumSize | override fun minimumSize(): Pair<Float, Float> = width to height |
| 20 | composable / Content | @Composable |
| 21 | ui-function / Content | override fun Content() {} |

### 1.21.11/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/GuiProjection.kt

`file-935ab3e685d820675ff8`；状态 **pending**；领域 ui-support；组合 1.21.11/fabric, 1.21.11/neoforge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 1.21.11/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/OffscreenTarget.kt

`file-9607d19f8057dcc40adb`；状态 **pending**；领域 ui-support；组合 1.21.11/fabric, 1.21.11/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 25 | ui-function / resolve | fun resolve(w: Int, h: Int): Boolean { |
| 44 | ui-function / clear | fun clear() { |
| 51 | ui-function / destroy | fun destroy() { |
| 56 | ui-function / dispose | fun dispose() { |
| 66 | ui-function / destroyAll | fun destroyAll() { |
| 79 | ui-function / texture | fun texture(target: RenderTarget, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, alpha: Float) |

### 1.21.11/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/RenderTargetFbo.kt

`file-edbbbbb0ac2f6263c531`；状态 **pending**；领域 ui-support；组合 1.21.11/fabric, 1.21.11/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 47 | ui-function / unwrapDevice | private fun unwrapDevice(device: GpuDevice?): GpuDevice? { |
| 60 | ui-function / unwrapTexture | private fun unwrapTexture(texture: GpuTexture?): GlTexture? { |
| 71 | ui-function / getFboId | fun getFboId(frameBuffer: RenderTarget): Int { |
| 78 | ui-function / getColorTexId | fun getColorTexId(frameBuffer: RenderTarget): Int = |

### 1.21.11/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/BlazeRenderer.kt

`file-6d093aa02d4fc99f8754`；状态 **pending**；领域 ui-support；组合 1.21.11/fabric, 1.21.11/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 从实际宿主入口触发，核对完整状态变化、交互、退出及重进后的结果。 定位类型: peripheral-ui 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 72 | ui-function / bitmapTexture | override fun bitmapTexture(bitmap: PolyBitmap): Any { |
| 81 | ui-function / mark | override fun mark(): Any { |
| 86 | ui-function / since | override fun since(mark: Any): Any { |
| 93 | ui-function / valid | override fun valid(captured: Any): Boolean { |
| 98 | ui-function / append | override fun append(captured: Any) { |
| 107 | ui-function / writeStateKey | override fun writeStateKey() { |
| 112 | ui-function / matches | fun matches(guiScale: Float): Boolean { |
| 117 | ui-function / reset | fun reset() { |
| 126 | ui-function / layerMark | override fun layerMark(): Any = ops.size |
| 128 | ui-function / layer | override fun layer(mark: Any, alpha: Float) { |
| 136 | ui-function / fade | override fun fade(mark: Any, alpha: Float): Boolean { |
| 151 | ui-function / touchText | private fun touchText(text: String, x: Float, y: Float, scale: Float, shadow: Boolean) { |
| 162 | ui-function / submit | override fun submit(texture: Any, premultiplied: Boolean, nearest: Boolean, verts: FloatArray, colors: IntArray, bounds: FloatArray) { |
| 174 | ui-function / mcText | override fun mcText(text: String, x: Float, y: Float, color: Int, shadow: Boolean, scale: Float) { |
| 186 | font-language / Language.getInstance | else if ('§' in line \|\| Language.getInstance().isDefaultRightToLeft) bakeText(font, seq, m, c, shadow, scissor) |
| 192 | ui-function / mcTextLive | override fun mcTextLive(text: String, x: Float, y: Float, color: () -> Int, shadow: Boolean, scale: Float) { |
| 193 | font-language / Language.getInstance | if (!keepVertices \|\| '§' in text \|\| Language.getInstance().isDefaultRightToLeft) return super.mcTextLive(text, x, y, color, shadow, scale) |
| 209 | ui-function / lineMatrix | private fun lineMatrix(x: Float, y: Float, index: Int, lineHeight: Int, scale: Float): Matrix3x2f = |
| 213 | ui-function / mcTextOutline | override fun mcTextOutline(text: String, x: Float, y: Float, color: Int, outlineColor: Int, scale: Float) { |
| 242 | ui-function / bakeText | private fun bakeText(font: Font, seq: FormattedCharSequence, m: Matrix3x2f, color: Int, shadow: Boolean, scissor: ScreenRectangle?): List<Mesh>? { |
| 253 | ui-function / close | fun close() { |
| 280 | ui-function / addVertex | override fun addVertex(x: Float, y: Float, z: Float) = apply { |
| 284 | ui-function / setUv | override fun setUv(u: Float, v: Float) = apply { verts[n * 4 - 2] = u; verts[n * 4 - 1] = v } |
| 285 | ui-function / setColor | override fun setColor(color: Int) = apply { colors[n - 1] = color } |
| 286 | ui-function / setColor | override fun setColor(r: Int, g: Int, b: Int, a: Int) = setColor((a shl 24) or (r shl 16) or (g shl 8) or b) |
| 287 | ui-function / setUv1 | override fun setUv1(u: Int, v: Int) = this |
| 288 | ui-function / setUv2 | override fun setUv2(u: Int, v: Int) = this |
| 289 | ui-function / setNormal | override fun setNormal(x: Float, y: Float, z: Float) = this |
| 290 | ui-function / setLineWidth | override fun setLineWidth(width: Float) = this |
| 293 | ui-function / texture | override fun texture(target: RenderTarget, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, alpha: Float) { |
| 297 | ui-function / texture | fun texture(view: GpuTextureView, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, alpha: Float) { |
| 309 | ui-function / live | override fun live(draw: (PolyRenderer) -> Unit) { |
| 319 | ui-function / faded | fun faded(alpha: Float) = LiveOp(s.copy().also { it.alpha *= alpha }, draw) |
| 321 | ui-function / invoke | override fun invoke(g: GuiGraphics) { |
| 334 | ui-function / finish | fun finish(): List<(GuiGraphics) -> Unit> { |
| 339 | ui-function / rectGui | private fun rectGui(x0: Float, y0: Float, x1: Float, y1: Float): ScreenRectangle? { |
| 348 | ui-function / invoke | override fun invoke(g: GuiGraphics) { |
| 360 | ui-function / shifted | fun shifted(dx: Int, dy: Int) = LiveTextOp(meshes?.map { it.shifted(dx, dy) }, color, alpha, base) { g, c -> shiftedDraw(g, dx, dy) { fallback(it, c) } } |
| 362 | ui-function / faded | fun faded(a: Float) = LiveTextOp(meshes?.map { it.faded(1f) }, color, alpha * a, base, fallback) |
| 364 | ui-function / changed | fun changed() = alphaMul(color(), alpha) != drawn |
| 366 | ui-function / invoke | override fun invoke(g: GuiGraphics) { |
| 384 | ui-function / shifted | fun shifted(dx: Int, dy: Int) = BakedTextOp(meshes?.map { it.shifted(dx, dy) }, color) { g, c -> shiftedDraw(g, dx, dy) { draw(it, c) } } |
| 386 | ui-function / faded | fun faded(alpha: Float) = BakedTextOp(meshes?.map { it.faded(alpha) }, alphaMul(color, alpha), draw) |
| 388 | ui-function / invoke | override fun invoke(g: GuiGraphics) { |
| 412 | ui-function / shifted | fun shifted(dx: Int, dy: Int) = Mesh( |
| 417 | ui-function / faded | fun faded(alpha: Float): Mesh { |
| 423 | ui-function / mergeable | fun mergeable(o: Mesh): Boolean = |
| 427 | ui-function / overlaps | fun overlaps(b: ScreenRectangle): Boolean = |
| 431 | ui-function / merge | fun merge(list: List<Mesh>): Mesh { |
| 449 | ui-function / buildVertices | override fun buildVertices(consumer: VertexConsumer) { |
| 470 | ui-function / vertex | private fun vertex(consumer: VertexConsumer, x: Float, y: Float, u: Float, v: Float, color: Int) { |
| 475 | ui-function / pipeline | override fun pipeline() = if (gpu != null) KeptMeshes.pipeline(pipeline) else pipeline |
| 476 | ui-function / textureSetup | override fun textureSetup() = textureSetup |
| 477 | ui-function / scissorArea | override fun scissorArea(): ScreenRectangle? = scissor |
| 478 | ui-function / bounds | override fun bounds() = bounds |
| 482 | ui-function / coalesce | fun coalesce(ops: List<(GuiGraphics) -> Unit>): List<(GuiGraphics) -> Unit> { |
| 487 | ui-function / emit | fun emit() { |
| 493 | ui-function / union | fun union(a: ScreenRectangle, b: ScreenRectangle): ScreenRectangle { |
| 497 | ui-function / place | fun place(m: Mesh) { |
| 538 | ui-function / fadeColor | private fun fadeColor(color: Int, alpha: Float, premultiplied: Boolean): Int { |
| 549 | ui-function / alphaMul | private fun alphaMul(color: Int, alpha: Float): Int { |
| 555 | ui-function / vanillaText | private fun vanillaText(g: GuiGraphics, font: Font, seq: FormattedCharSequence, m: Matrix3x2f, c: Int, shadow: Boolean, scissor: ScreenRectangle?) { |
| 564 | ui-function / isTextPipeline | private fun isTextPipeline(p: RenderPipeline) = p === RenderPipelines.GUI_TEXT \|\| p === RenderPipelines.GUI_TEXT_INTENSITY |
| 566 | ui-function / visitRenderables | private inline fun visitRenderables(prepared: Font.PreparedText, crossinline accept: (TextRenderable) -> Unit) { |
| 568 | ui-function / acceptGlyph | override fun acceptGlyph(glyph: TextRenderable.Styled) = accept(glyph) |
| 569 | ui-function / acceptEffect | override fun acceptEffect(effect: TextRenderable) = accept(effect) |
| 588 | ui-function / glyph | private fun glyph(font: Font, c: Char): Glyph { |
| 593 | ui-function / probe | private fun probe(font: Font, c: Char): Glyph { |
| 614 | ui-function / bake | fun bake(font: Font, line: String, m: Matrix3x2f, color: Int, shadow: Boolean, scissor: ScreenRectangle?, keep: Boolean = true): List<BlazeRenderer.Mesh>? { |
| 644 | ui-function / close | fun close() { |
| 667 | ui-function / quad | private fun quad(cap: BlazeRenderer.GlyphCapture, pose: Matrix4f, x: Float, y: Float, g: Glyph, color: Int) { |
| 677 | ui-function / visualOrder | private fun visualOrder(line: String): FormattedCharSequence { |
| 678 | font-language / Language.getInstance | val lang = Language.getInstance() |
| 707 | ui-function / get | fun get(text: String, kx: Float, ky: Float, fx: Float, fy: Float): Outline? { |
| 723 | ui-function / evict | private fun evict() { |
| 735 | ui-function / uncolored | private fun uncolored(seq: FormattedCharSequence) = FormattedCharSequence { sink -> |
| 745 | ui-function / draw | fun draw(g: GuiGraphics, pose: Matrix3x2f, x0: Float, y0: Float, color: Int, bounds: ScreenRectangle, scissor: ScreenRectangle?) { |
| 762 | ui-function / release | internal fun release() { |
| 768 | ui-function / render | private fun render() { |
| 783 | ui-function / renderInto | private fun renderInto(mc: Minecraft, state: GuiRenderState, renderer: GuiRenderer, rt: RenderTarget) { |
| 845 | ui-function / acquire | fun acquire(mc: Minecraft): Slot { |
| 855 | ui-function / release | fun release(slot: Slot) { |
| 859 | ui-function / create | private fun create(mc: Minecraft, state: GuiRenderState): GuiRenderer { |
| 867 | ui-function / targetV | private fun targetV(v: Float) = if (topDownTargets) 1f - v else v |
| 890 | ui-function / pipeline | fun pipeline(vanilla: RenderPipeline) = when { |
| 897 | ui-function / capture | fun capture(mesh: BlazeRenderer.Mesh, ptr: Long, len: Int) { |
| 904 | ui-function / kept | private fun kept(element: GuiElementRenderState): BlazeRenderer.Mesh? { |
| 914 | ui-function / added | fun added(index: Int, element: GuiElementRenderState) { |
| 920 | ui-function / recorded | fun recorded(draws: List<*>) { |
| 926 | ui-function / execute | fun execute(draw: Any, pass: RenderPass): Boolean { |
| 956 | ui-function / endFrame | fun endFrame() { |
| 977 | ui-function / markDirty | fun markDirty() { |
| 982 | ui-function / render | fun render(gfx: GuiGraphics) { |
| 1004 | ui-function / reuse | private fun reuse(renderer: BlazeRenderer?, guiScale: Float): BlazeRenderer { |
| 1017 | ui-function / render | fun render(gfx: GuiGraphics) { |
| 1018 | peripheral-ui / NotificationsManager.count | val show = NotificationsManager.count > 0 |
| 1038 | ui-function / render | fun render(gfx: GuiGraphics, dirty: Boolean, draw: (RenderContext) -> Unit) { |
| 1053 | ui-function / take | fun take(renderer: BlazeRenderer, copy: Boolean = false) { |
| 1068 | ui-function / idle | fun idle() { |
| 1075 | ui-function / replay | fun replay(gfx: GuiGraphics) { |
| 1087 | ui-function / moved | private fun moved(dx: Int, dy: Int) = LayerOp(ops, alpha, guiScale, bounds.shifted(dx, dy)) |
| 1089 | ui-function / faded | fun faded(a: Float) = LayerOp(ops, alpha * a, guiScale, bounds) |
| 1091 | ui-function / stale | private fun stale(): Boolean = ops.any { |
| 1100 | ui-function / invoke | override fun invoke(g: GuiGraphics) { |
| 1107 | ui-function / release | fun release() { |
| 1115 | ui-function / render | private fun render(): BlazeRenderer.Mesh? { |
| 1143 | ui-function / renderInto | private fun renderInto(mc: Minecraft, state: GuiRenderState, renderer: GuiRenderer, rt: RenderTarget, gs: Int) { |
| 1168 | ui-function / of | fun of(ops: List<(GuiGraphics) -> Unit>, alpha: Float, guiScale: Float, screenW: Int, screenH: Int): LayerOp? { |
| 1170 | ui-function / add | fun add(b: ScreenRectangle) { bounds = bounds?.let { union(it, b) } ?: b } |
| 1192 | ui-function / retire | fun retire(old: List<(GuiGraphics) -> Unit>, next: List<(GuiGraphics) -> Unit>) { |
| 1199 | ui-function / acquire | private fun acquire(w: Int, h: Int): OffscreenTarget { |
| 1205 | ui-function / sweep | private fun sweep(now: Long) { |
| 1211 | ui-function / union | private fun union(a: ScreenRectangle, b: ScreenRectangle): ScreenRectangle { |
| 1218 | ui-function / shifted | private fun ScreenRectangle.shifted(dx: Int, dy: Int) = ScreenRectangle(left() - dx, top() - dy, width(), height()) |
| 1220 | ui-function / shiftedDraw | private fun shiftedDraw(g: GuiGraphics, dx: Int, dy: Int, draw: (GuiGraphics) -> Unit) { |
| 1230 | ui-function / stacking | private inline fun stacking(gfx: GuiGraphics, draw: () -> Unit) { |
| 1246 | ui-function / oneconfig$setStacking | fun `oneconfig$setStacking`(stacking: Boolean) |
| 1252 | ui-function / setup | fun setup(nearest: Boolean = false): TextureSetup = TextureSetup.singleTexture(texture.textureView, sampler(nearest)) |
| 1255 | ui-function / sampler | private fun sampler(nearest: Boolean) = RenderSystem.getSamplerCache().getClampToEdge(if (nearest) FilterMode.NEAREST else FilterMode.LINEAR) |
| 1265 | ui-function / entry | private fun entry(page: PolyGlyphPage.Page) = entries.getOrPut(page) { |
| 1271 | ui-function / page | fun page(page: PolyGlyphPage.Page): AbstractTextureRef = entry(page).ref |
| 1273 | ui-function / uploadDirty | fun uploadDirty() { |
| 1299 | ui-function / get | fun get(bitmap: PolyBitmap): AbstractTextureRef { |
| 1333 | ui-function / touch | fun touch(bitmaps: Set<PolyBitmap>) { |
| 1337 | ui-function / endFrame | fun endFrame() { |

### 1.21.11/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/ComposeSceneContextImpl.kt

`file-5f725eead79eba616111`；状态 **pending**；领域 ui-support；组合 1.21.11/fabric, 1.21.11/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 28 | ui-function / requestInputMode | override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard |
| 37 | ui-function / isKeyDown | private fun isKeyDown(key: Int): Boolean { |
| 43 | keybind / isCtrlPressed | isCtrlPressed = isKeyDown(InputConstants.KEY_LCONTROL) \|\| isKeyDown(InputConstants.KEY_RCONTROL), |
| 44 | keybind / isShiftPressed | isShiftPressed = isKeyDown(InputConstants.KEY_LSHIFT) \|\| isKeyDown(InputConstants.KEY_RSHIFT), |
| 45 | keybind / isAltPressed | isAltPressed = isKeyDown(InputConstants.KEY_LALT) \|\| isKeyDown(InputConstants.KEY_RALT), |
| 49 | keybind / isMetaPressed | isMetaPressed = isKeyDown(Platform.compatibility().keys().keyLeftSuper) \|\| isKeyDown(Platform.compatibility().keys().keyRightSuper) |
| 74 | ui-function / setPointerIcon | override fun setPointerIcon(pointerIcon: PointerIcon) { |
| 81 | ui-function / resetPointerIcon | fun resetPointerIcon() { |
| 85 | ui-function / applyPointerIcon | private fun applyPointerIcon(pointerIcon: PointerIcon) { |
| 94 | ui-function / requestFocus | override fun requestFocus(): Boolean { |
| 99 | ui-function / allowCursorChanges | private fun allowCursorChanges(): Boolean { |
| 116 | ui-function / sync | fun sync() {} |
| 117 | ui-function / close | fun close() {} |
| 124 | ui-function / resetPointerIcon | fun resetPointerIcon() { |
| 128 | ui-function / updateContainerSize | fun updateContainerSize(width: Int, height: Int) { |

### 1.21.11/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/ComposeScreen.kt

`file-e51efb8e51be60542565`；状态 **pending**；领域 ui-support；组合 1.21.11/fabric, 1.21.11/neoforge。

对齐背景模糊开关、强度、HUD 穿透、resize 与关屏后的 GPU/画面恢复。 定位类型: blur 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 66 | ui-function / getText | override fun getText(): AnnotatedString? { |
| 75 | ui-function / setText | override fun setText(annotatedString: AnnotatedString) { |
| 82 | ui-function / hasText | override fun hasText(): Boolean { |
| 93 | ui-function / getClipEntry | override suspend fun getClipEntry(): ClipEntry? { |
| 99 | ui-function / setClipEntry | override suspend fun setClipEntry(clipEntry: ClipEntry?) { |
| 112 | route / ComposeScreen | abstract class ComposeScreen( |
| 122 | composable / compose | @Composable |
| 123 | ui-function / compose | abstract fun compose() |
| 146 | ui-function / liveScene | private fun liveScene(): ComposeScene? { |
| 151 | ui-function / withScene | private inline fun <T> withScene(block: (ComposeScene) -> T): T? { |
| 166 | ui-function / sendKeyEventSafely | private inline fun sendKeyEventSafely(build: () -> androidx.compose.ui.input.key.KeyEvent): Boolean { |
| 177 | ui-function / poisonScene | private fun poisonScene(cause: Throwable) { |
| 196 | ui-function / onSceneRebuilding | protected open fun onSceneRebuilding() {} |
| 198 | ui-function / ensureScene | private fun ensureScene(): ComposeScene? { |
| 214 | ui-function / closeSceneQuietly | private fun closeSceneQuietly() { |
| 239 | ui-function / createScene | private fun createScene(): ComposeScene { |
| 283 | ui-function / init | override fun init() { |
| 312 | ui-function / bindContent | private fun bindContent(): Boolean { |
| 319 | ui-function / setContentOn | private fun setContentOn(scene: ComposeScene) { |
| 334 | ui-function / reportUnavailableAndClose | private fun reportUnavailableAndClose() { |
| 341 | ui-function / closeWithMessage | private fun closeWithMessage(reason: String) { |
| 348 | ui-function / resize | override fun resize( |
| 357 | ui-function / isPauseScreen | override fun isPauseScreen(): Boolean = false |
| 365 | ui-function / renderBackground | override fun renderBackground(ctx: GuiGraphics, mouseX: Int, mouseY: Int, tickDelta: Float) { |
| 369 | blur / UiBlur.extract | UiBlur.extract(ctx, blurAlpha()) |
| 373 | ui-function / blurAlpha | protected open fun blurAlpha(): Float = 0f |
| 380 | ui-function / disposeScene | private fun disposeScene() { |
| 385 | ui-function / releaseScene | private fun releaseScene() { |
| 393 | ui-function / onClose | override fun onClose() { |
| 398 | ui-function / removed | override fun removed() { |
| 410 | ui-function / render | override fun render(ctx: GuiGraphics, mouseX: Int, mouseY: Int, tickDelta: Float) { |
| 473 | ui-function / paint | private fun paint(ctx: RenderContext, nanos: Long) { |
| 490 | ui-function / mouseClicked | override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean { |
| 492 | keybind / KeybindRecordingBus.consumeMouse | if (KeybindRecordingBus.consumeMouse(button, true) \|\| handleMouseClicked(button)) { |
| 502 | ui-function / mouseReleased | override fun mouseReleased(event: MouseButtonEvent): Boolean { |
| 504 | keybind / KeybindRecordingBus.consumeMouse | val recordingConsumed = KeybindRecordingBus.consumeMouse(button, false) |
| 515 | ui-function / mouseScrolled | override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean { |
| 520 | ui-function / handleMouseClicked | protected open fun handleMouseClicked(button: Int): Boolean = false |
| 528 | ui-function / releaseHeldButtons | private fun releaseHeldButtons() { |
| 536 | ui-function / sendMouseButtonEvent | private fun sendMouseButtonEvent(type: PointerEventType, button: Int) { |
| 551 | ui-function / sendScrollEvent | private fun sendScrollEvent(scrollX: Double, scrollY: Double) { |
| 573 | ui-function / isPreciseScroll | private fun isPreciseScroll(scrollX: Double, scrollY: Double, now: Long, macPrecise: Boolean?): Boolean { |
| 585 | ui-function / charTyped | override fun charTyped(event: CharacterEvent): Boolean { |
| 593 | ui-function / ctrlDown | fun Int.ctrlDown() = this and InputConstants.MOD_CONTROL != 0 |
| 594 | ui-function / shiftDown | fun Int.shiftDown() = this and InputConstants.MOD_SHIFT != 0 |
| 595 | ui-function / altDown | fun Int.altDown() = this and InputConstants.MOD_ALT != 0 |
| 596 | ui-function / superDown | fun Int.superDown() = this and InputConstants.MOD_SUPER != 0 |
| 598 | ui-function / handleKeyPressed | protected open fun handleKeyPressed(key: Int, modifiers: Int): Boolean = false |
| 603 | ui-function / keyPressed | override fun keyPressed(event: McKeyEvent): Boolean { |
| 611 | ui-function / keyReleased | override fun keyReleased(event: McKeyEvent): Boolean { |
| 619 | ui-function / dispatchKeyPressed | private fun dispatchKeyPressed(bindingKey: Int, shortcutKey: Int, modifiers: Int): Boolean { |
| 621 | keybind / KeybindRecordingBus.consumeEscape | if (KeybindRecordingBus.consumeEscape()) { |
| 625 | keybind / KeybindRecordingBus.isRecording | if (KeybindRecordingBus.isRecording) { |
| 637 | ui-function / sendKeyPressedEvent | private fun sendKeyPressedEvent(bindingKey: Int, shortcutKey: Int, modifiers: Int): Boolean { |
| 647 | keybind / isCtrlPressed | isCtrlPressed = modifiers.ctrlDown(), |
| 648 | keybind / isShiftPressed | isShiftPressed = modifiers.shiftDown(), |
| 649 | keybind / isAltPressed | isAltPressed = modifiers.altDown(), |
| 650 | keybind / isMetaPressed | isMetaPressed = modifiers.superDown(), |
| 664 | ui-function / sendKeyReleasedEvent | private fun sendKeyReleasedEvent(bindingKey: Int, shortcutKey: Int, modifiers: Int): Boolean { |
| 672 | keybind / isCtrlPressed | isCtrlPressed = modifiers.ctrlDown(), |
| 673 | keybind / isShiftPressed | isShiftPressed = modifiers.shiftDown(), |
| 674 | keybind / isAltPressed | isAltPressed = modifiers.altDown(), |
| 675 | keybind / isMetaPressed | isMetaPressed = modifiers.superDown(), |
| 689 | ui-function / sendCharacterEvent | private fun sendCharacterEvent(char: Char, codePoint: Int, modifiers: Int): Boolean { |
| 701 | ui-function / sendCharKeyEvent | private fun sendCharKeyEvent(char: Char, codePoint: Int, modifiers: Int): Boolean { |
| 707 | keybind / isCtrlPressed | isCtrlPressed = modifiers.ctrlDown(), |
| 708 | keybind / isShiftPressed | isShiftPressed = modifiers.shiftDown(), |
| 709 | keybind / isAltPressed | isAltPressed = modifiers.altDown(), |
| 710 | keybind / isMetaPressed | isMetaPressed = modifiers.superDown(), |
| 724 | ui-function / modifiersToAwt | private fun modifiersToAwt(modifiers: Int): Int { |
| 733 | ui-function / syncSceneMetrics | private fun syncSceneMetrics(): Boolean { |
| 762 | ui-function / surfaceScale | private fun surfaceScale(): Float { |
| 773 | ui-function / pointerPosition | protected fun pointerPosition(): Offset { |
| 794 | ui-function / isWheelStep | fun isWheelStep(value: Double) = abs(value * 120 - round(value * 120)) < 1e-3 |
| 800 | ui-function / sceneDensity | internal fun sceneDensity(): Float { |
| 819 | ui-function / isDispatchNeeded | override fun isDispatchNeeded(context: CoroutineContext): Boolean = |
| 822 | ui-function / dispatch | override fun dispatch(context: CoroutineContext, block: Runnable) { |

### 1.21.11/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/GlHost.kt

`file-2c95f9ddb5c815e3114f`；状态 **pending**；领域 ui-support；组合 1.21.11/fabric, 1.21.11/neoforge。

对齐背景模糊开关、强度、HUD 穿透、resize 与关屏后的 GPU/画面恢复。 定位类型: blur 从实际宿主入口触发，核对完整状态变化、交互、退出及重进后的结果。 定位类型: peripheral-ui 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 66 | ui-function / unavailableReason | fun unavailableReason(): String? { |
| 79 | ui-function / setGpuTexture | fun setGpuTexture(t: GpuTexture?) { |
| 83 | ui-function / setGpuTextureView | fun setGpuTextureView(v: GpuTextureView?) { |
| 87 | ui-function / close | override fun close() { |
| 94 | ui-function / blitHud | fun blitHud(guiGraphics: GuiGraphics) { |
| 124 | ui-function / screen | fun screen(dirty: Boolean, blurAlpha: Float, underlay: GuiDraw?, paint: (RenderContext) -> Unit) { |
| 142 | ui-function / drawBeforeBlit | fun drawBeforeBlit() { |
| 147 | ui-function / drawAtPresent | fun drawAtPresent() { |
| 155 | ui-function / draw | private fun draw(fbo: Int) { |
| 161 | blur / GlBlur.release | GlBlur.release() |
| 169 | peripheral-ui / NotificationsManager.count | if (NotificationsManager.count > 0) drawGl(fbo, null) { |
| 176 | ui-function / drawBlur | private fun drawBlur(fbo: Int, alpha: Float) { |
| 182 | blur / GlBlur.draw | GlBlur.draw(fbo, w, h, alpha, client.isPaused) |
| 190 | ui-function / drawGl | private fun drawGl(fbo: Int, cached: GlRenderer?, kept: Boolean = false, draw: (RenderContext) -> Unit): GlRenderer? { |
| 209 | ui-function / maxTextureSize | private fun maxTextureSize(): Int = |
| 214 | ui-function / cachedMaxTextureSize | private fun cachedMaxTextureSize(): Int { |
| 223 | ui-function / viewportFitsTexture | private fun viewportFitsTexture(w: Int, h: Int): Boolean { |
| 242 | ui-function / reuse | private fun reuse(renderer: GlRenderer?): GlRenderer { |
| 248 | ui-function / drawHudGl | fun drawHudGl(draw: (RenderContext, RenderContext?) -> Unit) { |
| 267 | ui-function / resolveHudTarget | private fun resolveHudTarget(): RenderTarget? { |
| 297 | ui-function / destroyHudTarget | private fun destroyHudTarget() { |
| 311 | ui-function / onAllocFailure | private fun onAllocFailure(what: String, w: Int, h: Int, error: Throwable) { |
| 326 | ui-function / recreateSurface | fun recreateSurface(width: Int, height: Int) { |

### 1.21.11/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/GlRenderer.kt

`file-90fc8f1efae236e49bea`；状态 **pending**；领域 ui-support；组合 1.21.11/fabric, 1.21.11/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 41 | ui-function / moved | fun moved(at: Int) = Live(at, state, draw, key).also { it.last = last } |
| 43 | ui-function / changed | fun changed() = key == null \|\| key() != last |
| 45 | ui-function / commit | fun commit() { |
| 55 | ui-function / bitmapTexture | override fun bitmapTexture(bitmap: PolyBitmap): Any = GlTextures.bitmap(bitmap) |
| 59 | ui-function / mark | override fun mark(): Any { |
| 64 | ui-function / since | override fun since(mark: Any): Any { |
| 87 | ui-function / valid | override fun valid(captured: Any): Boolean = (captured as Captured).batches.all { GlTextures.alive(it.texture) } |
| 89 | ui-function / append | override fun append(captured: Any) { |
| 97 | ui-function / writeStateKey | override fun writeStateKey() { |
| 102 | ui-function / matches | fun matches(): Boolean = |
| 105 | ui-function / reset | fun reset() { |
| 111 | ui-function / submit | override fun submit(texture: Any, premultiplied: Boolean, nearest: Boolean, verts: FloatArray, colors: IntArray, bounds: FloatArray) { |
| 117 | ui-function / layerMark | override fun layerMark(): Any = intArrayOf(batches.size, lives.size) |
| 119 | ui-function / layer | override fun layer(mark: Any, alpha: Float) { |
| 132 | ui-function / fade | override fun fade(mark: Any, alpha: Float): Boolean { |
| 142 | ui-function / live | override fun live(draw: (PolyRenderer) -> Unit) { |
| 150 | ui-function / mcTextLive | override fun mcTextLive(text: String, x: Float, y: Float, color: () -> Int, shadow: Boolean, scale: Float) { |
| 176 | ui-function / texture | override fun texture(target: RenderTarget, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, alpha: Float) { |
| 186 | ui-function / draw | fun draw(fbo: Int, w: Int, h: Int, topDown: Boolean, clear: Boolean, kept: Boolean = false) { |
| 192 | ui-function / flushPending | private fun flushPending() = flush() |
| 194 | ui-function / replay | private fun replay(lives: List<Live>): List<GlProgram.Segment> = lives.map { l -> |
| 209 | ui-function / take | fun take(renderer: GlRenderer) { |
| 224 | ui-function / idle | private fun idle() { |
| 232 | ui-function / same | private fun same(a: List<GlProgram.Batch>, b: List<GlProgram.Batch>) = a.size == b.size && a.indices.all { a[it] === b[it] } |
| 234 | ui-function / sameLives | private fun sameLives(a: List<Live>, b: List<Live>) = |
| 237 | ui-function / coalesce | private fun coalesce() { |
| 241 | ui-function / emit | fun emit() { |
| 264 | ui-function / draw | fun draw(fbo: Int, w: Int, h: Int, topDown: Boolean, clear: Boolean, vararg layers: Layer) { |
| 292 | ui-function / stale | private fun stale(): Boolean = (live != null && changed()) \|\| batches.any { (it.texture as? Group)?.stale() == true } |
| 294 | ui-function / render | private fun render(composite: GlProgram.Batch, transient: ArrayList<Group>?, sink: ArrayList<Group>) { |
| 322 | ui-function / release | fun release() { |
| 336 | ui-function / of | fun of(batches: List<GlProgram.Batch>, live: (() -> List<GlProgram.Segment>)?, changed: () -> Boolean, alpha: Float, screenW: Int, screenH: Int): Group? { |
| 352 | ui-function / prepare | fun prepare(batches: List<GlProgram.Batch>, transient: ArrayList<Group>?, sink: ArrayList<Group>) { |
| 356 | ui-function / endFrame | fun endFrame() { |
| 361 | ui-function / recycle | private fun recycle(t: IntArray) { |
| 365 | ui-function / acquire | private fun acquire(w: Int, h: Int): IntArray { |
| 381 | ui-function / delete | private fun delete(t: IntArray) { |
| 392 | ui-function / segments | fun segments(lives: List<Live>): List<GlProgram.Segment> { |
| 397 | ui-function / drawBatches | fun drawBatches(fbo: Int, w: Int, h: Int, topDown: Boolean, clear: Boolean, batches: List<GlProgram.Batch>, segments: List<GlProgram.Segment>, kept: Boolean, arena: Boolean) { |
| 426 | ui-function / sameKey | fun sameKey(o: Batch) = texture === o.texture && premultiplied == o.premultiplied && nearest == o.nearest |
| 428 | ui-function / faded | fun faded(alpha: Float) = Batch(texture, premultiplied, nearest, verts, IntArray(colors.size) { i -> |
| 444 | ui-function / merge | fun merge(list: List<Batch>): Batch { |
| 493 | ui-function / init | private fun init() { |
| 520 | ui-function / shader | private fun shader(type: Int, src: String): Int { |
| 528 | ui-function / invalidate | fun invalidate(b: Batch) { |
| 534 | ui-function / draw | fun draw(batches: List<Batch>, w: Int, h: Int, topDown: Boolean, live: List<Segment> = emptyList(), kept: Boolean = false, ox: Float = 0f, oy: Float = 0f) { |
| 578 | ui-function / drawArena | fun drawArena(batches: List<Batch>, w: Int, h: Int, topDown: Boolean, live: List<Segment>) { |
| 642 | ui-function / use | fun use(id: Int) { |
| 673 | ui-function / attribPointers | private fun attribPointers() { |
| 679 | ui-function / setupState | private fun setupState(w: Int, h: Int, topDown: Boolean, ox: Float = 0f, oy: Float = 0f) { |
| 696 | ui-function / drawBatch | private fun drawBatch(b: Batch, first: Int) { |
| 712 | ui-function / filter | private fun filter(mode: Int) { |
| 717 | ui-function / upload | private fun upload(at: Int, batches: List<Batch>): Int { |
| 751 | ui-function / ensureIndices | private fun ensureIndices(quads: Int) { |
| 799 | ui-function / bitmap | fun bitmap(bitmap: PolyBitmap): Tex = bitmaps.getOrPut(bitmap) { |
| 804 | ui-function / external | fun external(id: Int): Tex = external.getOrPut(id) { Tex(null, id, 0) } |
| 806 | ui-function / alive | fun alive(key: Any): Boolean = key !is Tex \|\| key.bitmap == null \|\| bitmaps[key.bitmap] === key |
| 808 | ui-function / id | fun id(key: Any): Int = when (key) { |
| 819 | ui-function / glyphPage | private fun glyphPage(page: PolyGlyphPage.Page): Int { |
| 836 | ui-function / upload | private fun upload(bitmap: PolyBitmap): Int { |
| 845 | ui-function / unpackDefaults | private fun unpackDefaults(rowLength: Int) { |
| 853 | ui-function / newTexture | private fun newTexture(): Int { |
| 863 | ui-function / genTexture | private fun genTexture(): Int { |
| 867 | ui-function / bind | private fun bind(id: Int) { |
| 871 | ui-function / texParameter | private fun texParameter(name: Int, value: Int) { |
| 875 | ui-function / pixelStore | private fun pixelStore(name: Int, value: Int) { |
| 879 | ui-function / texImage | private fun texImage(w: Int, h: Int, px: IntBuffer) { |
| 883 | ui-function / texSubImage | private fun texSubImage(y: Int, w: Int, h: Int, px: IntBuffer) { |
| 887 | ui-function / deleteTexture | private fun deleteTexture(id: Int) { |
| 891 | ui-function / endFrame | fun endFrame() { |

### 1.21.11/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/MacScrollEvent.kt

`file-b48817888fabfa36f86d`；状态 **pending**；领域 ui-support；组合 1.21.11/fabric, 1.21.11/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 18 | ui-function / readScroll | fun readScroll(): Boolean? = try { |

### 1.21.11/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/McFontService.kt

`file-b8fccb59299a6ff5c1b7`；状态 **pending**；领域 font-language；组合 1.21.11/fabric, 1.21.11/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 26 | font-language / McFontService | object McFontService { |
| 29 | ui-function / init | fun init() { |
| 40 | ui-function / installBundledFonts | private fun installBundledFonts() { |
| 41 | font-language / McFontService | val loader = McFontService::class.java.classLoader |
| 43 | font-language / UiFontFiles.DEFAULT_REGULAR | "assets/oneconfig/fonts/minecraft/Minecraft-Regular.otf" to UiFontFiles.DEFAULT_REGULAR, |
| 44 | font-language / UiFontFiles.DEFAULT_BOLD | "assets/oneconfig/fonts/minecraft/Minecraft-Bold.otf" to UiFontFiles.DEFAULT_BOLD, |
| 56 | ui-function / installHostFonts | private fun installHostFonts() { |
| 57 | font-language / UiFontFiles.readRegular | val regular = UiFontFiles.readRegular() |
| 64 | font-language / UiFontFiles.REGULAR | FontManager.replace(UiFontFiles.REGULAR, regular) |
| 65 | font-language / UiFontFiles.readBold | val bold = UiFontFiles.readBold() ?: regular |
| 67 | font-language / UiFontFiles.BOLD | FontManager.replace(UiFontFiles.BOLD, bold) |
| 70 | font-language / UiFontFiles.BOLD | FontManager.replace(UiFontFiles.BOLD, regular) |
| 72 | font-language / UiFontFiles.REGULAR | FontManager.setDefault(UiFontFiles.REGULAR) |
| 78 | font-language / UiFontFiles.readFallback | val fallback = UiFontFiles.readFallback() |
| 81 | font-language / UiFontFiles.FALLBACK | FontManager.replace(UiFontFiles.FALLBACK, fallback) |
| 92 | ui-function / installEngines | fun installEngines() { |

### 1.21.11/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/McResourceFont.kt

`file-c43cbe1e5256983e7602`；状态 **pending**；领域 font-language；组合 1.21.11/fabric, 1.21.11/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 28 | font-language / McResourceFont | internal object McResourceFont { |
| 40 | ui-function / glyph | fun glyph(cp: Int): McGlyph? = glyphs[cp] ?: unihex[cp]?.let { u -> baked.getOrPut(cp) { bake(u) } } |
| 47 | ui-function / init | fun init() { |
| 55 | ui-function / reload | private fun reload() { |
| 74 | ui-function / fontOptions | private fun fontOptions(): Int { |
| 82 | ui-function / load | private fun load(rm: ResourceManager, options: Int): Font? { |
| 88 | ui-function / unclaimed | fun unclaimed(cp: Int) = cp !in glyphs && cp !in unihex |
| 106 | ui-function / collectProviders | private fun collectProviders(rm: ResourceManager, ref: String, out: MutableList<JsonObject>, visited: MutableSet<String>, options: Int) { |
| 122 | ui-function / filterPasses | private fun filterPasses(provider: JsonObject, options: Int): Boolean { |
| 129 | ui-function / loadBitmap | private fun loadBitmap(rm: ResourceManager, provider: JsonObject): Map<Int, McGlyph> { |
| 156 | ui-function / loadUnihex | private fun loadUnihex(rm: ResourceManager, provider: JsonObject): Map<Int, Unihex> { |
| 181 | ui-function / parseHex | private fun parseHex(line: String, overrides: List<IntArray>, out: MutableMap<Int, Unihex>): Boolean { |
| 209 | ui-function / bake | private fun bake(u: Unihex): McGlyph { |
| 221 | ui-function / read | private fun read(rm: ResourceManager, ref: String, folder: String): ByteArray? = |
| 224 | ui-function / id | private fun id(ref: String, folder: String, suffix: String = "") = |

### 1.21.11/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/MinecraftKeyboardAdapter.kt

`file-496f432b0744f031298e`；状态 **pending**；领域 ui-support；组合 1.21.11/fabric, 1.21.11/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 13 | ui-function / keyLocation | fun keyLocation(bindingKey: Int): Int  = when (bindingKey) { |
| 20 | ui-function / toAwtKeyCode | fun toAwtKeyCode(shortcutKey: Int): Int = when (shortcutKey) { |

### 1.21.11/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/UiBlur.kt

`file-5f8bbc78a2bceeb4c485`；状态 **pending**；领域 blur；组合 1.21.11/fabric, 1.21.11/neoforge。

对齐背景模糊开关、强度、HUD 穿透、resize 与关屏后的 GPU/画面恢复。 定位类型: blur 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 20 | blur / UiBlur | object UiBlur { |
| 51 | ui-function / extract | fun extract(ctx: GuiGraphics, alpha: Float) { |
| 68 | ui-function / process | fun process(): Boolean { |
| 98 | ui-function / downInfo | private fun downInfo(mainW: Int, mainH: Int, w: Int, h: Int): GpuBuffer { |
| 109 | ui-function / info | private fun info(outW: Int, outH: Int, inW: Int, inH: Int): GpuBuffer = MemoryStack.stackPush().use { stack -> |
| 115 | ui-function / draw | private fun draw(pass: PostPass, input: GpuTextureView, output: RenderTarget, info: GpuBuffer) { |

### 1.21.11/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/impls/HudEditorUIScreen.kt

`file-94f92e76af85806bd706`；状态 **pending**；领域 hud-editor；组合 1.21.11/fabric, 1.21.11/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 36 | route / HudEditorUIScreen | class HudEditorUIScreen private constructor() : ComposeScreen() { |
| 41 | ui-function / open | fun open(fromOneConfig: Boolean): HudEditorUIScreen { |
| 56 | ui-function / beginClose | private fun beginClose() { |
| 61 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLOSE) |
| 61 | sound / UiSoundEvent.CLOSE | UiSounds.play(UiSoundEvent.CLOSE) |
| 62 | sound / UiSounds.releaseAmbience | UiSounds.releaseAmbience() |
| 62 | sound / releaseAmbience | UiSounds.releaseAmbience() |
| 65 | ui-function / cancelClose | private fun cancelClose(): Boolean { |
| 69 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.OPEN) |
| 69 | sound / UiSoundEvent.OPEN | UiSounds.play(UiSoundEvent.OPEN) |
| 70 | sound / UiSounds.acquireAmbience | UiSounds.acquireAmbience() |
| 70 | sound / acquireAmbience | UiSounds.acquireAmbience() |
| 78 | ui-function / returnToOneConfig | private fun returnToOneConfig() { |
| 86 | ui-function / init | override fun init() { |
| 95 | sound / UiSounds.acquireAmbience | UiSounds.acquireAmbience() |
| 95 | sound / acquireAmbience | UiSounds.acquireAmbience() |
| 101 | ui-function / removed | override fun removed() { |
| 107 | sound / UiSounds.releaseAmbience | UiSounds.releaseAmbience() |
| 107 | sound / releaseAmbience | UiSounds.releaseAmbience() |
| 113 | ui-function / isPauseScreen | override fun isPauseScreen(): Boolean = OneConfigConfig.pauseGame |
| 115 | ui-function / handleOneConfigKeybind | private fun handleOneConfigKeybind(): Boolean { |
| 127 | ui-function / handleKeyPressed | override fun handleKeyPressed(key: Int, modifiers: Int): Boolean { |
| 136 | keybind / KeybindRecordingBus.isRecording | if (toggleKey != null && key == toggleKey && !KeybindRecordingBus.isRecording) { |
| 142 | ui-function / handleMouseClicked | override fun handleMouseClicked(button: Int): Boolean { |
| 143 | keybind / KeybindRecordingBus.isRecording | if (KeybindRecordingBus.isRecording) return false |
| 146 | keybind / KeybindManager.isTriggeredByMouse | KeybindManager.isTriggeredByMouse(OneConfigConfig.oneConfigKeybind, button) |
| 154 | ui-function / render | override fun render(ctx: GuiGraphics, mouseX: Int, mouseY: Int, tickDelta: Float) { |
| 174 | composable / compose | @Composable |
| 175 | ui-function / compose | override fun compose() { |
| 196 | animation / RetainedVisibility | RetainedVisibility( |
| 198 | animation / tween | enter = tween(200, easing = EaseOutCubic), |
| 199 | animation / tween | exit = tween(exitMs, easing = EaseIn), |

### 1.21.11/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/impls/OneConfigUIScreen.kt

`file-733127086aadd8850bb2`；状态 **pending**；领域 shell-navigation；组合 1.21.11/fabric, 1.21.11/neoforge。

对齐背景模糊开关、强度、HUD 穿透、resize 与关屏后的 GPU/画面恢复。 定位类型: blur 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 79 | route / OneConfigUIScreen | class OneConfigUIScreen @JvmOverloads constructor( |
| 91 | ui-function / blurAlpha | override fun blurAlpha(): Float { |
| 92 | blur / enableBackgroundBlur | if (!OneConfigConfig.enableBackgroundBlur) return 0f |
| 110 | ui-function / shared | private fun shared(): OneConfigUIScreen = |
| 114 | ui-function / forRoute | fun forRoute(route: Any?): OneConfigUIScreen = shared().also { |
| 120 | ui-function / open | fun open(): OneConfigUIScreen = shared().also { |
| 127 | ui-function / resume | fun resume(): OneConfigUIScreen = shared().also { |
| 141 | ui-function / scheduleSave | private fun scheduleSave() { |
| 154 | route / OpeningRoute | private data class OpeningRoute(val route: Any, val restored: Boolean = false) |
| 156 | ui-function / resolveOpeningRoute | private fun resolveOpeningRoute(): OpeningRoute { |
| 176 | ui-function / stopWarmUp | fun stopWarmUp() { |
| 189 | ui-function / dropPrerasteredLater | private fun dropPrerasteredLater() { |
| 196 | ui-function / prepareWarmUp | fun prepareWarmUp(): Runnable? { |
| 211 | ui-function / warmUp | private fun warmUp(w: Int, h: Int, density: Float, surface: Float) { |
| 221 | ui-function / warmFrames | private fun warmFrames(w: Int, h: Int, density: Float, surface: Float) { |
| 226 | ui-function / isDispatchNeeded | override fun isDispatchNeeded(context: CoroutineContext) = Thread.currentThread() !== thread |
| 227 | ui-function / dispatch | override fun dispatch(context: CoroutineContext, block: Runnable) { |
| 231 | ui-function / drain | fun drain() { |
| 291 | ui-function / openLastSession | fun openLastSession() { |
| 302 | ui-function / beginClose | private fun beginClose() { |
| 307 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLOSE) |
| 307 | sound / UiSoundEvent.CLOSE | UiSounds.play(UiSoundEvent.CLOSE) |
| 308 | sound / UiSounds.releaseAmbience | UiSounds.releaseAmbience() |
| 308 | sound / releaseAmbience | UiSounds.releaseAmbience() |
| 311 | ui-function / cancelClose | private fun cancelClose(): Boolean { |
| 322 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.OPEN) |
| 322 | sound / UiSoundEvent.OPEN | UiSounds.play(UiSoundEvent.OPEN) |
| 323 | sound / UiSounds.acquireAmbience | UiSounds.acquireAmbience() |
| 323 | sound / acquireAmbience | UiSounds.acquireAmbience() |
| 339 | ui-function / markClosed | private fun markClosed() { |
| 343 | ui-function / init | override fun init() { |
| 415 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.OPEN) |
| 415 | sound / UiSoundEvent.OPEN | UiSounds.play(UiSoundEvent.OPEN) |
| 416 | sound / UiSounds.acquireAmbience | UiSounds.acquireAmbience() |
| 416 | sound / acquireAmbience | UiSounds.acquireAmbience() |
| 426 | ui-function / onSceneRebuilding | override fun onSceneRebuilding() { |
| 432 | ui-function / removed | override fun removed() { |
| 437 | sound / UiSounds.releaseAmbience | UiSounds.releaseAmbience() |
| 437 | sound / releaseAmbience | UiSounds.releaseAmbience() |
| 443 | ui-function / isPauseScreen | override fun isPauseScreen(): Boolean = OneConfigConfig.pauseGame |
| 445 | ui-function / handleOneConfigKeybind | private fun handleOneConfigKeybind(): Boolean { |
| 457 | ui-function / handleKeyPressed | override fun handleKeyPressed(key: Int, modifiers: Int): Boolean { |
| 466 | keybind / KeybindRecordingBus.isRecording | if (toggleKey != null && key == toggleKey && !KeybindRecordingBus.isRecording) { |
| 473 | ui-function / handleMouseClicked | override fun handleMouseClicked(button: Int): Boolean { |
| 474 | keybind / KeybindRecordingBus.isRecording | if (KeybindRecordingBus.isRecording) return false |
| 477 | keybind / KeybindManager.isTriggeredByMouse | KeybindManager.isTriggeredByMouse(OneConfigConfig.oneConfigKeybind, button) |
| 484 | keybind / KeybindManager.hasTriggeredMouseBind | if (KeybindManager.hasTriggeredMouseBind(button)) return true |
| 485 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLICK) |
| 485 | sound / UiSoundEvent.CLICK | UiSounds.play(UiSoundEvent.CLICK) |
| 490 | keybind / KeybindManager.hasTriggeredMouseBind | if (KeybindManager.hasTriggeredMouseBind(button)) return true |
| 491 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLICK) |
| 491 | sound / UiSoundEvent.CLICK | UiSounds.play(UiSoundEvent.CLICK) |
| 501 | ui-function / render | override fun render(ctx: GuiGraphics, mouseX: Int, mouseY: Int, tickDelta: Float) { |
| 523 | ui-function / easeOutExpo | private fun easeOutExpo(progress: Float): Float { |
| 531 | composable / compose | @Composable |
| 532 | ui-function / compose | override fun compose() { |
| 564 | ui-function / submit | override fun submit(texture: Any, premultiplied: Boolean, nearest: Boolean, verts: FloatArray, colors: IntArray, bounds: FloatArray) {} |
| 566 | ui-function / bitmapTexture | override fun bitmapTexture(bitmap: PolyBitmap): Any = bitmap |
| 568 | ui-function / live | override fun live(draw: (PolyRenderer) -> Unit) = draw(this) |

### 1.21.11/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/opengl/StoredGLState.kt

`file-1de1c08eada78326060e`；状态 **pending**；领域 ui-support；组合 1.21.11/fabric, 1.21.11/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 10 | ui-function / resyncTextureBindCache | fun resyncTextureBindCache() { |
| 21 | ui-function / capture | fun capture(): StoredGLState { |
| 102 | ui-function / restore | fun restore(): StoredGLState { |
| 195 | ui-function / forceToggle | private fun forceToggle(enabled: Boolean, enable: () -> Unit, disable: () -> Unit) { |
| 205 | ui-function / forceDepthFunc | private fun forceDepthFunc(func: Int) { |
| 211 | ui-function / forceDepthMask | private fun forceDepthMask(mask: Boolean) { |

### 1.21.11/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/CompatOverlayRenderer.kt

`file-50589f626e83c3260316`；状态 **pending**；领域 hud-editor；组合 1.21.11/fabric, 1.21.11/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 14 | ui-function / register | fun register(hook: (GuiGraphics) -> Unit) { |
| 19 | ui-function / hasHooks | fun hasHooks(): Boolean = hooks.isNotEmpty() |
| 22 | ui-function / oneConfigScreenOpen | fun oneConfigScreenOpen(): Boolean = HudManager.isEditing |
| 25 | ui-function / render | fun render(ctx: GuiGraphics) { |

### 1.21.11/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/LegacyHudRenderer.kt

`file-5eca65291b33341a216c`；状态 **pending**；领域 hud-editor；组合 1.21.11/fabric, 1.21.11/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 32 | ui-function / reportFailure | private fun reportFailure(hud: LegacyHud, e: Throwable) { |
| 47 | ui-function / renderLive | fun renderLive(graphics: GuiGraphics) { |
| 52 | ui-function / renderLiveHuds | private fun renderLiveHuds(graphics: GuiGraphics) { |

### 1.21.11/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/keybind/MinecraftKeybindProvider.kt

`file-e536fc15268751a4b422`；状态 **pending**；领域 keybind；组合 1.21.11/fabric, 1.21.11/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 22 | keybind / OneConfigKeybind | private val properties = IdentityHashMap<KeyMapping, Property<OneConfigKeybind>>() |
| 37 | ui-function / groups | override fun groups(): List<KeybindGroup> { |
| 77 | ui-function / managedMappings | fun managedMappings(): List<KeyMapping> { |
| 82 | keybind / OneConfigKeybind | private fun propertyFor(mapping: KeyMapping): Property<OneConfigKeybind> { |
| 82 | ui-function / propertyFor | private fun propertyFor(mapping: KeyMapping): Property<OneConfigKeybind> { |
| 89 | keybind / OneConfigKeybind | OneConfigKeybind::class.java, |
| 99 | ui-function / ownerMod | private fun ownerMod(mapping: KeyMapping, modsById: Map<String, ModInfo>): ModInfo? { |
| 105 | ui-function / ownerCandidates | private fun ownerCandidates(keyName: String): Sequence<String> = sequence { |
| 117 | ui-function / modIcon | private fun modIcon(owner: ModInfo?): String? = |
| 124 | ui-function / minecraftIcon | private fun minecraftIcon(): String { |
| 132 | ui-function / extractMinecraftIcon | private fun extractMinecraftIcon(): String? = runCatching { |
| 148 | ui-function / groupTitle | private fun groupTitle(category: String, owner: ModInfo?): String { |
| 154 | ui-function / sanitizeId | private fun sanitizeId(value: String): String { |
| 161 | keybind / OneConfigKeybind | private fun KeyMapping.toOneConfigKeybind(): OneConfigKeybind { |
| 161 | ui-function / toOneConfigKeybind | private fun KeyMapping.toOneConfigKeybind(): OneConfigKeybind { |
| 166 | keybind / Key.toOneConfigKeybind | private fun InputConstants.Key.toOneConfigKeybind(): OneConfigKeybind { |
| 166 | keybind / OneConfigKeybind | private fun InputConstants.Key.toOneConfigKeybind(): OneConfigKeybind { |
| 166 | ui-function / toOneConfigKeybind | private fun InputConstants.Key.toOneConfigKeybind(): OneConfigKeybind { |
| 171 | keybind / OneConfigKeybind | OneConfigKeybind(intArrayOf(value), null, KeyModifiers.NONE, 0L) { true } |
| 174 | keybind / OneConfigKeybind | OneConfigKeybind(null, intArrayOf(value), KeyModifiers.NONE, 0L) { true } |
| 175 | keybind / OneConfigKeybind | else -> OneConfigKeybind(null, null, KeyModifiers.NONE, 0L) { true } |
| 179 | keybind / OneConfigKeybind | private fun KeyMapping.applyOneConfigKeybind(keybind: OneConfigKeybind?) { |
| 179 | ui-function / applyOneConfigKeybind | private fun KeyMapping.applyOneConfigKeybind(keybind: OneConfigKeybind?) { |
| 193 | ui-function / categoryLabel | private fun categoryLabel(mapping: KeyMapping): String { |
| 199 | ui-function / translate | private fun translate(key: String): String { |
| 211 | ui-function / isOneConfigMirror | private fun isOneConfigMirror(@Suppress("UNUSED_PARAMETER") mapping: KeyMapping): Boolean = false |

### 1.21.11/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/sound/McUiSoundService.kt

`file-65abba06e2afc9f2ac2f`；状态 **pending**；领域 sound；组合 1.21.11/fabric, 1.21.11/neoforge。

对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 19 | sound / McUiSoundService | class McUiSoundService : UiSoundService { |
| 19 | sound / UiSoundService | class McUiSoundService : UiSoundService { |
| 28 | sound / UiSoundTheme | private val ambienceShuffle = mutableMapOf<UiSoundTheme, MutableList<String>>() |
| 29 | sound / UiSoundTheme | private val lastAmbienceSoundId = mutableMapOf<UiSoundTheme, String>() |
| 33 | sound / UiSoundEvent | override fun play(event: UiSoundEvent, theme: UiSoundTheme, volume: Float) { |
| 33 | sound / UiSoundTheme | override fun play(event: UiSoundEvent, theme: UiSoundTheme, volume: Float) { |
| 33 | ui-function / play | override fun play(event: UiSoundEvent, theme: UiSoundTheme, volume: Float) { |
| 37 | sound / UiSoundTheme.MODERN | UiSoundTheme.MODERN -> playModern(event, volume) |
| 38 | sound / UiSoundTheme.MINECRAFT | UiSoundTheme.MINECRAFT -> playMinecraft(event, volume) |
| 44 | sound / UiSoundEvent | private fun playModern(event: UiSoundEvent, volume: Float) { |
| 44 | ui-function / playModern | private fun playModern(event: UiSoundEvent, volume: Float) { |
| 46 | sound / UiSoundEvent.OPEN | UiSoundEvent.OPEN -> playModernSound("ui.open", 1f, volume) |
| 47 | sound / UiSoundEvent.CLOSE | UiSoundEvent.CLOSE -> playModernSound("ui.close", 1f, volume) |
| 48 | sound / UiSoundEvent.CLICK | UiSoundEvent.CLICK -> playModernSound("ui.click", 1f, volume) |
| 49 | sound / UiSoundEvent.SLIDER_TICK | UiSoundEvent.SLIDER_TICK -> playModernSound("ui.slider", 1f, volume) |
| 50 | sound / UiSoundEvent.HUD_SELECT | UiSoundEvent.HUD_SELECT -> playModernSound("ui.click", 1.0f, volume) |
| 51 | sound / UiSoundEvent.HUD_DRAG_START | UiSoundEvent.HUD_DRAG_START -> playModernSound("ui.click", 0.9f, volume) |
| 52 | sound / UiSoundEvent.HUD_DRAG_END | UiSoundEvent.HUD_DRAG_END -> playModernSound("ui.click", 1.1f, volume) |
| 53 | sound / UiSoundEvent.HUD_RESIZE_END | UiSoundEvent.HUD_RESIZE_END -> playModernSound("ui.click", 1.0f, volume) |
| 58 | ui-function / playModernSound | private fun playModernSound(id: String, pitch: Float, volume: Float) { |
| 63 | sound / UiSoundEvent | private fun playMinecraft(event: UiSoundEvent, volume: Float) { |
| 63 | ui-function / playMinecraft | private fun playMinecraft(event: UiSoundEvent, volume: Float) { |
| 65 | sound / UiSoundEvent.CLICK | UiSoundEvent.CLICK -> playVanilla(SoundEvents.UI_BUTTON_CLICK, 1f, 0.5f * volume) |
| 66 | sound / UiSoundEvent.OPEN | UiSoundEvent.OPEN -> playModernSound("ui.open", 1f, volume) |
| 67 | sound / UiSoundEvent.CLOSE | UiSoundEvent.CLOSE -> playModernSound("ui.close", 1f, volume) |
| 68 | sound / UiSoundEvent.SLIDER_TICK | UiSoundEvent.SLIDER_TICK -> { |
| 73 | sound / UiSoundEvent.HUD_SELECT | UiSoundEvent.HUD_SELECT -> playVanilla(SoundEvents.UI_BUTTON_CLICK, 1f, 0.3f * volume) |
| 74 | sound / UiSoundEvent.HUD_DRAG_END | UiSoundEvent.HUD_DRAG_END -> playVanilla(SoundEvents.UI_BUTTON_CLICK, 1f, 0.3f * volume) |
| 75 | sound / UiSoundEvent.HUD_RESIZE_END | UiSoundEvent.HUD_RESIZE_END -> playVanilla(SoundEvents.UI_BUTTON_CLICK, 1f, 0.3f * volume) |
| 80 | ui-function / playVanilla | private fun playVanilla(event: SoundEvent, pitch: Float, volume: Float) { |
| 85 | ui-function / playVanilla | private fun playVanilla(holder: Holder<SoundEvent>, pitch: Float, volume: Float) = |
| 88 | sound / startAmbience | override fun startAmbience(theme: UiSoundTheme, volume: Float) { |
| 88 | sound / UiSoundTheme | override fun startAmbience(theme: UiSoundTheme, volume: Float) { |
| 88 | ui-function / startAmbience | override fun startAmbience(theme: UiSoundTheme, volume: Float) { |
| 109 | sound / stopAmbience | override fun stopAmbience(@Suppress("UNUSED_PARAMETER") setDucking: Boolean) { |
| 109 | ui-function / stopAmbience | override fun stopAmbience(@Suppress("UNUSED_PARAMETER") setDucking: Boolean) { |
| 118 | sound / UiSoundTheme | private fun ambienceVolumeFor(theme: UiSoundTheme, volume: Float): Float = when (theme) { |
| 118 | ui-function / ambienceVolumeFor | private fun ambienceVolumeFor(theme: UiSoundTheme, volume: Float): Float = when (theme) { |
| 119 | sound / UiSoundTheme.MODERN | UiSoundTheme.MODERN -> 0.5f * volume |
| 120 | sound / UiSoundTheme.MINECRAFT | UiSoundTheme.MINECRAFT -> 0.08f * volume |
| 123 | sound / UiSoundTheme | private fun ambienceSoundIds(theme: UiSoundTheme): List<String> = when (theme) { |
| 123 | ui-function / ambienceSoundIds | private fun ambienceSoundIds(theme: UiSoundTheme): List<String> = when (theme) { |
| 124 | sound / UiSoundTheme.MODERN | UiSoundTheme.MODERN -> listOf("ui.pad_loop") |
| 125 | sound / UiSoundTheme.MINECRAFT | UiSoundTheme.MINECRAFT -> listOf( |
| 133 | sound / UiSoundTheme | private fun playAmbienceInstance(theme: UiSoundTheme, targetVolume: Float): FadingLoopSoundInstance { |
| 133 | ui-function / playAmbienceInstance | private fun playAmbienceInstance(theme: UiSoundTheme, targetVolume: Float): FadingLoopSoundInstance { |
| 144 | sound / UiSoundTheme | private fun nextAmbienceSoundId(theme: UiSoundTheme, ids: List<String>): String { |
| 144 | ui-function / nextAmbienceSoundId | private fun nextAmbienceSoundId(theme: UiSoundTheme, ids: List<String>): String { |
| 165 | ui-function / driveAmbienceLoop | private fun driveAmbienceLoop() { |
| 196 | sound / UiSoundTheme | val theme: UiSoundTheme, |
| 215 | ui-function / beginFadeOut | fun beginFadeOut() { |
| 219 | ui-function / cancelFadeOut | fun cancelFadeOut() { |
| 224 | ui-function / updateTargetVolume | fun updateTargetVolume(newTarget: Float) { |
| 229 | ui-function / tick | override fun tick() { |

### 26.1.2/fabric/src/main/java/org/polyfrost/oneconfig/internal/OneConfig.java

`file-f26ff7e8898b9cd07d58`；状态 **pending**；领域 ui-support；组合 26.1.2/fabric。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 236 | search-provider / SearchCorpus.INSTANCE | EventManager.register(ResourceFinishedLoading.class, e -> SearchCorpus.INSTANCE.init()); |
| 335 | font-language / McFontService.INSTANCE | McFontService.INSTANCE.init(); |

### 26.1.2/fabric/src/main/java/org/polyfrost/oneconfig/internal/OneConfigPreLaunch.java

`file-6a19d0731a2c5332e349`；状态 **pending**；领域 ui-support；组合 26.1.2/fabric。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 25 | font-language / McFontService.installEngines | McFontService.installEngines(); |

### 26.1.2/neoforge/src/main/java/org/polyfrost/oneconfig/internal/OneConfig.java

`file-61b902bd63416935fa09`；状态 **pending**；领域 ui-support；组合 26.1.2/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 235 | search-provider / SearchCorpus.INSTANCE | EventManager.register(ResourceFinishedLoading.class, e -> SearchCorpus.INSTANCE.init()); |
| 345 | font-language / McFontService.INSTANCE | McFontService.INSTANCE.init(); |

### 26.1.2/neoforge/src/main/java/org/polyfrost/oneconfig/internal/OneConfigPreLaunch.java

`file-7bcc257b1af0a80811eb`；状态 **pending**；领域 ui-support；组合 26.1.2/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 22 | font-language / McFontService.installEngines | org.polyfrost.oneconfig.internal.ui.compose.McFontService.installEngines(); |

### 26.1.2/src/main/java/org/polyfrost/oneconfig/api/platform/v1/internal/I18nPlatformImpl.java

`file-65d21980fb8c4c000361`；状态 **pending**；领域 ui-support；组合 26.1.2/fabric, 26.1.2/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 39 | font-language / I18nPlatform | public class I18nPlatformImpl implements I18nPlatform { |

### 26.1.2/src/main/java/org/polyfrost/oneconfig/api/platform/v1/internal/KeysImpl.java

`file-d338ef2d4f31d68593e7`；状态 **pending**；领域 ui-support；组合 26.1.2/fabric, 26.1.2/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 21 | keybind / getKeyA | public int getKeyA() { |
| 26 | keybind / getKeyC | public int getKeyC() { |
| 31 | keybind / getKeyD | public int getKeyD() { |
| 36 | keybind / getKeyE | public int getKeyE() { |
| 41 | keybind / getKeyF | public int getKeyF() { |
| 46 | keybind / getKeyH | public int getKeyH() { |
| 51 | keybind / getKeyL | public int getKeyL() { |
| 56 | keybind / getKeyR | public int getKeyR() { |
| 61 | keybind / getKeyV | public int getKeyV() { |
| 66 | keybind / getKeyX | public int getKeyX() { |
| 71 | keybind / getKeyY | public int getKeyY() { |
| 76 | keybind / getKeyZ | public int getKeyZ() { |
| 81 | keybind / getKeyDelete | public int getKeyDelete() { |
| 91 | keybind / getKeyLeftShift | public int getKeyLeftShift() { |
| 96 | keybind / getKeyRightShift | public int getKeyRightShift() { |
| 101 | keybind / getKeyLeftControl | public int getKeyLeftControl() { |
| 106 | keybind / getKeyRightControl | public int getKeyRightControl() { |
| 111 | keybind / getKeyLeftAlt | public int getKeyLeftAlt() { |
| 116 | keybind / getKeyRightAlt | public int getKeyRightAlt() { |
| 121 | keybind / getKeyLeftSuper | public int getKeyLeftSuper() { |
| 126 | keybind / getKeyRightSuper | public int getKeyRightSuper() { |

### 26.1.2/src/main/java/org/polyfrost/oneconfig/api/ui/v1/api/internal/TinyFdApiImpl.java

`file-f8703ac6950d7e35b19b`；状态 **pending**；领域 ui-support；组合 26.1.2/fabric, 26.1.2/neoforge。

核对打开/取消/确认、焦点、边界定位、外点关闭与提交后可见结果。 定位类型: dialog-or-popup

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 74 | dialog-or-popup / Dialog | private static <T> T open(String description, T fallback, Dialog<T> dialog) { |
| 151 | dialog-or-popup / tinyfd_openFileDialog | String result = TinyFileDialogs.tinyfd_openFileDialog(sanitizeText(title), sanitizePath(defaultFilePath), filters(stack, filterPatterns), sanitizeText(filterDescription), false); |
| 161 | dialog-or-popup / tinyfd_saveFileDialog | String result = TinyFileDialogs.tinyfd_saveFileDialog(sanitizeText(title), sanitizePath(defaultFilePath), filters(stack, filterPatterns), sanitizeText(filterDescription)); |
| 171 | dialog-or-popup / tinyfd_openFileDialog | String result = TinyFileDialogs.tinyfd_openFileDialog(sanitizeText(title), sanitizePath(defaultFilePath), filters(stack, filterPatterns), sanitizeText(filterDescription), true); |
| 187 | dialog-or-popup / tinyfd_selectFolderDialog | firstPath(TinyFileDialogs.tinyfd_selectFolderDialog(sanitizeText(title), sanitizePath(defaultFolderPath)))); |
| 199 | dialog-or-popup / tinyfd_notifyPopup | TinyFileDialogs.tinyfd_notifyPopup(sanitizeText(title), sanitizeText(message), icon) == 1 ? 0 : 1); |
| 271 | dialog-or-popup / Dialog | private interface Dialog<T> { |

### 26.1.2/src/main/java/org/polyfrost/oneconfig/api/ui/v1/keybind/internal/MinecraftKeybindCodec.java

`file-ee751e2b850279ec7fb0`；状态 **pending**；领域 keybind；组合 26.1.2/fabric, 26.1.2/neoforge。

该文件支撑 keybind；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 26.1.2/src/main/java/org/polyfrost/oneconfig/internal/mixin/render/Mixin_UiBlur.java

`file-68333b9bf00ab0cf8979`；状态 **pending**；领域 blur；组合 26.1.2/fabric, 26.1.2/neoforge。

对齐背景模糊开关、强度、HUD 穿透、resize 与关屏后的 GPU/画面恢复。 定位类型: blur

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 14 | blur / UiBlur.process | if (UiBlur.process()) ci.cancel(); |

### 26.1.2/src/main/java/org/polyfrost/oneconfig/internal/ui/PlayerHeadBlit.java

`file-f6dec9063a741f69f484`；状态 **pending**；领域 ui-support；组合 26.1.2/fabric, 26.1.2/neoforge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 26.1.2/src/main/java/org/polyfrost/oneconfig/internal/ui/PlayerHeadSkinAccess.java

`file-7918bbab3e8d127b38a9`；状态 **pending**；领域 ui-support；组合 26.1.2/fabric, 26.1.2/neoforge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 26.1.2/src/main/kotlin/org/polyfrost/oneconfig/api/hud/v1/LegacyHud.kt

`file-3c647eeae547d4e08390`；状态 **pending**；领域 hud-editor；组合 26.1.2/fabric, 26.1.2/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 14 | ui-function / render | abstract fun render(mcCtx: GuiGraphicsExtractor) |
| 18 | ui-function / minimumSize | override fun minimumSize(): Pair<Float, Float> = width to height |
| 20 | composable / Content | @Composable |
| 21 | ui-function / Content | override fun Content() {} |

### 26.1.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/GuiProjection.kt

`file-bcc1781c08d61ba93ea4`；状态 **pending**；领域 ui-support；组合 26.1.2/fabric, 26.1.2/neoforge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 26.1.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/OffscreenTarget.kt

`file-6dc2ea04b1180dc82e3c`；状态 **pending**；领域 ui-support；组合 26.1.2/fabric, 26.1.2/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 26 | ui-function / resolve | fun resolve(w: Int, h: Int): Boolean { |
| 45 | ui-function / clear | fun clear() { |
| 52 | ui-function / destroy | fun destroy() { |
| 57 | ui-function / dispose | fun dispose() { |
| 67 | ui-function / destroyAll | fun destroyAll() { |
| 80 | ui-function / texture | fun texture(target: RenderTarget, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, alpha: Float) |

### 26.1.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/RenderTargetFbo.kt

`file-5b280cb28eb49f2ac342`；状态 **pending**；领域 ui-support；组合 26.1.2/fabric, 26.1.2/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 31 | ui-function / unwrapTexture | private fun unwrapTexture(texture: com.mojang.blaze3d.textures.GpuTexture?): GlTexture? { |
| 41 | ui-function / getFboId | fun getFboId(frameBuffer: RenderTarget): Int { |
| 50 | ui-function / getColorTexId | fun getColorTexId(frameBuffer: RenderTarget): Int = |

### 26.1.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/BlazeRenderer.kt

`file-e4357cefc84c987d21fb`；状态 **pending**；领域 ui-support；组合 26.1.2/fabric, 26.1.2/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 从实际宿主入口触发，核对完整状态变化、交互、退出及重进后的结果。 定位类型: peripheral-ui 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 73 | ui-function / bitmapTexture | override fun bitmapTexture(bitmap: PolyBitmap): Any { |
| 82 | ui-function / mark | override fun mark(): Any { |
| 87 | ui-function / since | override fun since(mark: Any): Any { |
| 94 | ui-function / valid | override fun valid(captured: Any): Boolean { |
| 99 | ui-function / append | override fun append(captured: Any) { |
| 108 | ui-function / writeStateKey | override fun writeStateKey() { |
| 113 | ui-function / matches | fun matches(guiScale: Float): Boolean { |
| 118 | ui-function / reset | fun reset() { |
| 127 | ui-function / layerMark | override fun layerMark(): Any = ops.size |
| 129 | ui-function / layer | override fun layer(mark: Any, alpha: Float) { |
| 137 | ui-function / fade | override fun fade(mark: Any, alpha: Float): Boolean { |
| 152 | ui-function / touchText | private fun touchText(text: String, x: Float, y: Float, scale: Float, shadow: Boolean) { |
| 163 | ui-function / submit | override fun submit(texture: Any, premultiplied: Boolean, nearest: Boolean, verts: FloatArray, colors: IntArray, bounds: FloatArray) { |
| 175 | ui-function / mcText | override fun mcText(text: String, x: Float, y: Float, color: Int, shadow: Boolean, scale: Float) { |
| 187 | font-language / Language.getInstance | else if ('§' in line \|\| Language.getInstance().isDefaultRightToLeft) bakeText(font, seq, m, c, shadow, scissor) |
| 193 | ui-function / mcTextLive | override fun mcTextLive(text: String, x: Float, y: Float, color: () -> Int, shadow: Boolean, scale: Float) { |
| 194 | font-language / Language.getInstance | if (!keepVertices \|\| '§' in text \|\| Language.getInstance().isDefaultRightToLeft) return super.mcTextLive(text, x, y, color, shadow, scale) |
| 210 | ui-function / lineMatrix | private fun lineMatrix(x: Float, y: Float, index: Int, lineHeight: Int, scale: Float): Matrix3x2f = |
| 214 | ui-function / mcTextOutline | override fun mcTextOutline(text: String, x: Float, y: Float, color: Int, outlineColor: Int, scale: Float) { |
| 243 | ui-function / bakeText | private fun bakeText(font: Font, seq: FormattedCharSequence, m: Matrix3x2f, color: Int, shadow: Boolean, scissor: ScreenRectangle?): List<Mesh>? { |
| 254 | ui-function / close | fun close() { |
| 281 | ui-function / addVertex | override fun addVertex(x: Float, y: Float, z: Float) = apply { |
| 285 | ui-function / setUv | override fun setUv(u: Float, v: Float) = apply { verts[n * 4 - 2] = u; verts[n * 4 - 1] = v } |
| 286 | ui-function / setColor | override fun setColor(color: Int) = apply { colors[n - 1] = color } |
| 287 | ui-function / setColor | override fun setColor(r: Int, g: Int, b: Int, a: Int) = setColor((a shl 24) or (r shl 16) or (g shl 8) or b) |
| 288 | ui-function / setUv1 | override fun setUv1(u: Int, v: Int) = this |
| 289 | ui-function / setUv2 | override fun setUv2(u: Int, v: Int) = this |
| 290 | ui-function / setNormal | override fun setNormal(x: Float, y: Float, z: Float) = this |
| 291 | ui-function / setLineWidth | override fun setLineWidth(width: Float) = this |
| 294 | ui-function / texture | override fun texture(target: RenderTarget, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, alpha: Float) { |
| 298 | ui-function / texture | fun texture(view: GpuTextureView, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, alpha: Float) { |
| 310 | ui-function / live | override fun live(draw: (PolyRenderer) -> Unit) { |
| 320 | ui-function / faded | fun faded(alpha: Float) = LiveOp(s.copy().also { it.alpha *= alpha }, draw) |
| 322 | ui-function / invoke | override fun invoke(g: GuiGraphicsExtractor) { |
| 335 | ui-function / finish | fun finish(): List<(GuiGraphicsExtractor) -> Unit> { |
| 340 | ui-function / rectGui | private fun rectGui(x0: Float, y0: Float, x1: Float, y1: Float): ScreenRectangle? { |
| 349 | ui-function / invoke | override fun invoke(g: GuiGraphicsExtractor) { |
| 361 | ui-function / shifted | fun shifted(dx: Int, dy: Int) = LiveTextOp(meshes?.map { it.shifted(dx, dy) }, color, alpha, base) { g, c -> shiftedDraw(g, dx, dy) { fallback(it, c) } } |
| 363 | ui-function / faded | fun faded(a: Float) = LiveTextOp(meshes?.map { it.faded(1f) }, color, alpha * a, base, fallback) |
| 365 | ui-function / changed | fun changed() = alphaMul(color(), alpha) != drawn |
| 367 | ui-function / invoke | override fun invoke(g: GuiGraphicsExtractor) { |
| 385 | ui-function / shifted | fun shifted(dx: Int, dy: Int) = BakedTextOp(meshes?.map { it.shifted(dx, dy) }, color) { g, c -> shiftedDraw(g, dx, dy) { draw(it, c) } } |
| 387 | ui-function / faded | fun faded(alpha: Float) = BakedTextOp(meshes?.map { it.faded(alpha) }, alphaMul(color, alpha), draw) |
| 389 | ui-function / invoke | override fun invoke(g: GuiGraphicsExtractor) { |
| 413 | ui-function / shifted | fun shifted(dx: Int, dy: Int) = Mesh( |
| 418 | ui-function / faded | fun faded(alpha: Float): Mesh { |
| 424 | ui-function / mergeable | fun mergeable(o: Mesh): Boolean = |
| 428 | ui-function / overlaps | fun overlaps(b: ScreenRectangle): Boolean = |
| 432 | ui-function / merge | fun merge(list: List<Mesh>): Mesh { |
| 450 | ui-function / buildVertices | override fun buildVertices(consumer: VertexConsumer) { |
| 471 | ui-function / vertex | private fun vertex(consumer: VertexConsumer, x: Float, y: Float, u: Float, v: Float, color: Int) { |
| 476 | ui-function / pipeline | override fun pipeline() = if (gpu != null) KeptMeshes.pipeline(pipeline) else pipeline |
| 477 | ui-function / textureSetup | override fun textureSetup() = textureSetup |
| 478 | ui-function / scissorArea | override fun scissorArea(): ScreenRectangle? = scissor |
| 479 | ui-function / bounds | override fun bounds() = bounds |
| 483 | ui-function / coalesce | fun coalesce(ops: List<(GuiGraphicsExtractor) -> Unit>): List<(GuiGraphicsExtractor) -> Unit> { |
| 488 | ui-function / emit | fun emit() { |
| 494 | ui-function / union | fun union(a: ScreenRectangle, b: ScreenRectangle): ScreenRectangle { |
| 498 | ui-function / place | fun place(m: Mesh) { |
| 539 | ui-function / fadeColor | private fun fadeColor(color: Int, alpha: Float, premultiplied: Boolean): Int { |
| 550 | ui-function / alphaMul | private fun alphaMul(color: Int, alpha: Float): Int { |
| 556 | ui-function / vanillaText | private fun vanillaText(g: GuiGraphicsExtractor, font: Font, seq: FormattedCharSequence, m: Matrix3x2f, c: Int, shadow: Boolean, scissor: ScreenRectangle?) { |
| 565 | ui-function / isTextPipeline | private fun isTextPipeline(p: RenderPipeline) = p === RenderPipelines.GUI_TEXT \|\| p === RenderPipelines.GUI_TEXT_INTENSITY |
| 567 | ui-function / visitRenderables | private inline fun visitRenderables(prepared: Font.PreparedText, crossinline accept: (TextRenderable) -> Unit) { |
| 569 | ui-function / acceptGlyph | override fun acceptGlyph(glyph: TextRenderable.Styled) = accept(glyph) |
| 570 | ui-function / acceptEffect | override fun acceptEffect(effect: TextRenderable) = accept(effect) |
| 589 | ui-function / glyph | private fun glyph(font: Font, c: Char): Glyph { |
| 594 | ui-function / probe | private fun probe(font: Font, c: Char): Glyph { |
| 615 | ui-function / bake | fun bake(font: Font, line: String, m: Matrix3x2f, color: Int, shadow: Boolean, scissor: ScreenRectangle?, keep: Boolean = true): List<BlazeRenderer.Mesh>? { |
| 645 | ui-function / close | fun close() { |
| 668 | ui-function / quad | private fun quad(cap: BlazeRenderer.GlyphCapture, pose: Matrix4f, x: Float, y: Float, g: Glyph, color: Int) { |
| 678 | ui-function / visualOrder | private fun visualOrder(line: String): FormattedCharSequence { |
| 679 | font-language / Language.getInstance | val lang = Language.getInstance() |
| 708 | ui-function / get | fun get(text: String, kx: Float, ky: Float, fx: Float, fy: Float): Outline? { |
| 724 | ui-function / evict | private fun evict() { |
| 736 | ui-function / uncolored | private fun uncolored(seq: FormattedCharSequence) = FormattedCharSequence { sink -> |
| 746 | ui-function / draw | fun draw(g: GuiGraphicsExtractor, pose: Matrix3x2f, x0: Float, y0: Float, color: Int, bounds: ScreenRectangle, scissor: ScreenRectangle?) { |
| 763 | ui-function / release | internal fun release() { |
| 769 | ui-function / render | private fun render() { |
| 784 | ui-function / renderInto | private fun renderInto(mc: Minecraft, state: GuiRenderState, renderer: GuiRenderer, rt: RenderTarget) { |
| 846 | ui-function / acquire | fun acquire(mc: Minecraft): Slot { |
| 856 | ui-function / release | fun release(slot: Slot) { |
| 860 | ui-function / create | private fun create(mc: Minecraft, state: GuiRenderState): GuiRenderer { |
| 868 | ui-function / targetV | private fun targetV(v: Float) = if (topDownTargets) 1f - v else v |
| 891 | ui-function / pipeline | fun pipeline(vanilla: RenderPipeline) = when { |
| 898 | ui-function / capture | fun capture(mesh: BlazeRenderer.Mesh, ptr: Long, len: Int) { |
| 905 | ui-function / kept | private fun kept(element: GuiElementRenderState): BlazeRenderer.Mesh? { |
| 915 | ui-function / added | fun added(index: Int, element: GuiElementRenderState) { |
| 921 | ui-function / recorded | fun recorded(draws: List<*>) { |
| 927 | ui-function / execute | fun execute(draw: Any, pass: RenderPass): Boolean { |
| 957 | ui-function / endFrame | fun endFrame() { |
| 978 | ui-function / markDirty | fun markDirty() { |
| 983 | ui-function / render | fun render(gfx: GuiGraphicsExtractor) { |
| 1005 | ui-function / reuse | private fun reuse(renderer: BlazeRenderer?, guiScale: Float): BlazeRenderer { |
| 1018 | ui-function / render | fun render(gfx: GuiGraphicsExtractor) { |
| 1019 | peripheral-ui / NotificationsManager.count | val show = NotificationsManager.count > 0 |
| 1039 | ui-function / render | fun render(gfx: GuiGraphicsExtractor, dirty: Boolean, draw: (RenderContext) -> Unit) { |
| 1054 | ui-function / take | fun take(renderer: BlazeRenderer, copy: Boolean = false) { |
| 1069 | ui-function / idle | fun idle() { |
| 1076 | ui-function / replay | fun replay(gfx: GuiGraphicsExtractor) { |
| 1088 | ui-function / moved | private fun moved(dx: Int, dy: Int) = LayerOp(ops, alpha, guiScale, bounds.shifted(dx, dy)) |
| 1090 | ui-function / faded | fun faded(a: Float) = LayerOp(ops, alpha * a, guiScale, bounds) |
| 1092 | ui-function / stale | private fun stale(): Boolean = ops.any { |
| 1101 | ui-function / invoke | override fun invoke(g: GuiGraphicsExtractor) { |
| 1108 | ui-function / release | fun release() { |
| 1116 | ui-function / render | private fun render(): BlazeRenderer.Mesh? { |
| 1144 | ui-function / renderInto | private fun renderInto(mc: Minecraft, state: GuiRenderState, renderer: GuiRenderer, rt: RenderTarget, gs: Int) { |
| 1169 | ui-function / of | fun of(ops: List<(GuiGraphicsExtractor) -> Unit>, alpha: Float, guiScale: Float, screenW: Int, screenH: Int): LayerOp? { |
| 1171 | ui-function / add | fun add(b: ScreenRectangle) { bounds = bounds?.let { union(it, b) } ?: b } |
| 1193 | ui-function / retire | fun retire(old: List<(GuiGraphicsExtractor) -> Unit>, next: List<(GuiGraphicsExtractor) -> Unit>) { |
| 1200 | ui-function / acquire | private fun acquire(w: Int, h: Int): OffscreenTarget { |
| 1206 | ui-function / sweep | private fun sweep(now: Long) { |
| 1212 | ui-function / union | private fun union(a: ScreenRectangle, b: ScreenRectangle): ScreenRectangle { |
| 1219 | ui-function / shifted | private fun ScreenRectangle.shifted(dx: Int, dy: Int) = ScreenRectangle(left() - dx, top() - dy, width(), height()) |
| 1221 | ui-function / shiftedDraw | private fun shiftedDraw(g: GuiGraphicsExtractor, dx: Int, dy: Int, draw: (GuiGraphicsExtractor) -> Unit) { |
| 1231 | ui-function / stacking | private inline fun stacking(gfx: GuiGraphicsExtractor, draw: () -> Unit) { |
| 1247 | ui-function / oneconfig$setStacking | fun `oneconfig$setStacking`(stacking: Boolean) |
| 1253 | ui-function / setup | fun setup(nearest: Boolean = false): TextureSetup = TextureSetup.singleTexture(texture.textureView, sampler(nearest)) |
| 1256 | ui-function / sampler | private fun sampler(nearest: Boolean) = RenderSystem.getSamplerCache().getClampToEdge(if (nearest) FilterMode.NEAREST else FilterMode.LINEAR) |
| 1266 | ui-function / entry | private fun entry(page: PolyGlyphPage.Page) = entries.getOrPut(page) { |
| 1272 | ui-function / page | fun page(page: PolyGlyphPage.Page): AbstractTextureRef = entry(page).ref |
| 1274 | ui-function / uploadDirty | fun uploadDirty() { |
| 1300 | ui-function / get | fun get(bitmap: PolyBitmap): AbstractTextureRef { |
| 1334 | ui-function / touch | fun touch(bitmaps: Set<PolyBitmap>) { |
| 1338 | ui-function / endFrame | fun endFrame() { |

### 26.1.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/ComposeSceneContextImpl.kt

`file-d01e53038214c79744cf`；状态 **pending**；领域 ui-support；组合 26.1.2/fabric, 26.1.2/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 30 | ui-function / requestInputMode | override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard |
| 39 | ui-function / isKeyDown | private fun isKeyDown(key: Int): Boolean { |
| 45 | keybind / isCtrlPressed | isCtrlPressed = isKeyDown(InputConstants.KEY_LCONTROL) \|\| isKeyDown(InputConstants.KEY_RCONTROL), |
| 46 | keybind / isShiftPressed | isShiftPressed = isKeyDown(InputConstants.KEY_LSHIFT) \|\| isKeyDown(InputConstants.KEY_RSHIFT), |
| 47 | keybind / isAltPressed | isAltPressed = isKeyDown(InputConstants.KEY_LALT) \|\| isKeyDown(InputConstants.KEY_RALT), |
| 51 | keybind / isMetaPressed | isMetaPressed = isKeyDown(Platform.compatibility().keys().keyLeftSuper) \|\| isKeyDown(Platform.compatibility().keys().keyRightSuper) |
| 76 | ui-function / setPointerIcon | override fun setPointerIcon(pointerIcon: PointerIcon) { |
| 83 | ui-function / resetPointerIcon | fun resetPointerIcon() { |
| 87 | ui-function / applyPointerIcon | private fun applyPointerIcon(pointerIcon: PointerIcon) { |
| 96 | ui-function / requestFocus | override fun requestFocus(): Boolean { |
| 101 | ui-function / allowCursorChanges | private fun allowCursorChanges(): Boolean { |
| 122 | ui-function / startInputMethod | override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing { |
| 134 | ui-function / sync | fun sync() { |
| 147 | ui-function / close | fun close() { |
| 157 | ui-function / resetPointerIcon | fun resetPointerIcon() { |
| 161 | ui-function / updateContainerSize | fun updateContainerSize(width: Int, height: Int) { |

### 26.1.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/ComposeScreen.kt

`file-09963d0183c8d7fe02b3`；状态 **pending**；领域 ui-support；组合 26.1.2/fabric, 26.1.2/neoforge。

对齐背景模糊开关、强度、HUD 穿透、resize 与关屏后的 GPU/画面恢复。 定位类型: blur 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 66 | ui-function / getText | override fun getText(): AnnotatedString? { |
| 75 | ui-function / setText | override fun setText(annotatedString: AnnotatedString) { |
| 82 | ui-function / hasText | override fun hasText(): Boolean { |
| 93 | ui-function / getClipEntry | override suspend fun getClipEntry(): ClipEntry? { |
| 99 | ui-function / setClipEntry | override suspend fun setClipEntry(clipEntry: ClipEntry?) { |
| 112 | route / ComposeScreen | abstract class ComposeScreen( |
| 122 | composable / compose | @Composable |
| 123 | ui-function / compose | abstract fun compose() |
| 146 | ui-function / liveScene | private fun liveScene(): ComposeScene? { |
| 151 | ui-function / withScene | private inline fun <T> withScene(block: (ComposeScene) -> T): T? { |
| 166 | ui-function / sendKeyEventSafely | private inline fun sendKeyEventSafely(build: () -> androidx.compose.ui.input.key.KeyEvent): Boolean { |
| 177 | ui-function / poisonScene | private fun poisonScene(cause: Throwable) { |
| 196 | ui-function / onSceneRebuilding | protected open fun onSceneRebuilding() {} |
| 198 | ui-function / ensureScene | private fun ensureScene(): ComposeScene? { |
| 214 | ui-function / closeSceneQuietly | private fun closeSceneQuietly() { |
| 239 | ui-function / createScene | private fun createScene(): ComposeScene { |
| 283 | ui-function / init | override fun init() { |
| 312 | ui-function / bindContent | private fun bindContent(): Boolean { |
| 319 | ui-function / setContentOn | private fun setContentOn(scene: ComposeScene) { |
| 334 | ui-function / reportUnavailableAndClose | private fun reportUnavailableAndClose() { |
| 341 | ui-function / closeWithMessage | private fun closeWithMessage(reason: String) { |
| 348 | ui-function / resize | override fun resize( |
| 357 | ui-function / isPauseScreen | override fun isPauseScreen(): Boolean = false |
| 365 | ui-function / extractBackground | override fun extractBackground(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, tickDelta: Float) { |
| 369 | blur / UiBlur.extract | UiBlur.extract(ctx, blurAlpha()) |
| 373 | ui-function / blurAlpha | protected open fun blurAlpha(): Float = 0f |
| 380 | ui-function / disposeScene | private fun disposeScene() { |
| 385 | ui-function / releaseScene | private fun releaseScene() { |
| 393 | ui-function / onClose | override fun onClose() { |
| 398 | ui-function / removed | override fun removed() { |
| 410 | ui-function / extractRenderState | override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, tickDelta: Float) { |
| 473 | ui-function / paint | private fun paint(ctx: RenderContext, nanos: Long) { |
| 490 | ui-function / mouseClicked | override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean { |
| 492 | keybind / KeybindRecordingBus.consumeMouse | if (KeybindRecordingBus.consumeMouse(button, true) \|\| handleMouseClicked(button)) { |
| 502 | ui-function / mouseReleased | override fun mouseReleased(event: MouseButtonEvent): Boolean { |
| 504 | keybind / KeybindRecordingBus.consumeMouse | val recordingConsumed = KeybindRecordingBus.consumeMouse(button, false) |
| 515 | ui-function / mouseScrolled | override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean { |
| 520 | ui-function / handleMouseClicked | protected open fun handleMouseClicked(button: Int): Boolean = false |
| 528 | ui-function / releaseHeldButtons | private fun releaseHeldButtons() { |
| 536 | ui-function / sendMouseButtonEvent | private fun sendMouseButtonEvent(type: PointerEventType, button: Int) { |
| 551 | ui-function / sendScrollEvent | private fun sendScrollEvent(scrollX: Double, scrollY: Double) { |
| 573 | ui-function / isPreciseScroll | private fun isPreciseScroll(scrollX: Double, scrollY: Double, now: Long, macPrecise: Boolean?): Boolean { |
| 585 | ui-function / charTyped | override fun charTyped(event: CharacterEvent): Boolean { |
| 593 | ui-function / ctrlDown | fun Int.ctrlDown() = this and InputConstants.MOD_CONTROL != 0 |
| 594 | ui-function / shiftDown | fun Int.shiftDown() = this and InputConstants.MOD_SHIFT != 0 |
| 595 | ui-function / altDown | fun Int.altDown() = this and InputConstants.MOD_ALT != 0 |
| 596 | ui-function / superDown | fun Int.superDown() = this and InputConstants.MOD_SUPER != 0 |
| 598 | ui-function / handleKeyPressed | protected open fun handleKeyPressed(key: Int, modifiers: Int): Boolean = false |
| 603 | ui-function / keyPressed | override fun keyPressed(event: McKeyEvent): Boolean { |
| 611 | ui-function / keyReleased | override fun keyReleased(event: McKeyEvent): Boolean { |
| 619 | ui-function / dispatchKeyPressed | private fun dispatchKeyPressed(bindingKey: Int, shortcutKey: Int, modifiers: Int): Boolean { |
| 621 | keybind / KeybindRecordingBus.consumeEscape | if (KeybindRecordingBus.consumeEscape()) { |
| 625 | keybind / KeybindRecordingBus.isRecording | if (KeybindRecordingBus.isRecording) { |
| 637 | ui-function / sendKeyPressedEvent | private fun sendKeyPressedEvent(bindingKey: Int, shortcutKey: Int, modifiers: Int): Boolean { |
| 647 | keybind / isCtrlPressed | isCtrlPressed = modifiers.ctrlDown(), |
| 648 | keybind / isShiftPressed | isShiftPressed = modifiers.shiftDown(), |
| 649 | keybind / isAltPressed | isAltPressed = modifiers.altDown(), |
| 650 | keybind / isMetaPressed | isMetaPressed = modifiers.superDown(), |
| 664 | ui-function / sendKeyReleasedEvent | private fun sendKeyReleasedEvent(bindingKey: Int, shortcutKey: Int, modifiers: Int): Boolean { |
| 672 | keybind / isCtrlPressed | isCtrlPressed = modifiers.ctrlDown(), |
| 673 | keybind / isShiftPressed | isShiftPressed = modifiers.shiftDown(), |
| 674 | keybind / isAltPressed | isAltPressed = modifiers.altDown(), |
| 675 | keybind / isMetaPressed | isMetaPressed = modifiers.superDown(), |
| 689 | ui-function / sendCharacterEvent | private fun sendCharacterEvent(char: Char, codePoint: Int, modifiers: Int): Boolean { |
| 701 | ui-function / sendCharKeyEvent | private fun sendCharKeyEvent(char: Char, codePoint: Int, modifiers: Int): Boolean { |
| 707 | keybind / isCtrlPressed | isCtrlPressed = modifiers.ctrlDown(), |
| 708 | keybind / isShiftPressed | isShiftPressed = modifiers.shiftDown(), |
| 709 | keybind / isAltPressed | isAltPressed = modifiers.altDown(), |
| 710 | keybind / isMetaPressed | isMetaPressed = modifiers.superDown(), |
| 724 | ui-function / modifiersToAwt | private fun modifiersToAwt(modifiers: Int): Int { |
| 733 | ui-function / syncSceneMetrics | private fun syncSceneMetrics(): Boolean { |
| 762 | ui-function / surfaceScale | private fun surfaceScale(): Float { |
| 773 | ui-function / pointerPosition | protected fun pointerPosition(): Offset { |
| 794 | ui-function / isWheelStep | fun isWheelStep(value: Double) = abs(value * 120 - round(value * 120)) < 1e-3 |
| 800 | ui-function / sceneDensity | internal fun sceneDensity(): Float { |
| 819 | ui-function / isDispatchNeeded | override fun isDispatchNeeded(context: CoroutineContext): Boolean = |
| 822 | ui-function / dispatch | override fun dispatch(context: CoroutineContext, block: Runnable) { |

### 26.1.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/GlHost.kt

`file-b33e9480445e466c8439`；状态 **pending**；领域 ui-support；组合 26.1.2/fabric, 26.1.2/neoforge。

对齐背景模糊开关、强度、HUD 穿透、resize 与关屏后的 GPU/画面恢复。 定位类型: blur 从实际宿主入口触发，核对完整状态变化、交互、退出及重进后的结果。 定位类型: peripheral-ui 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 66 | ui-function / unavailableReason | fun unavailableReason(): String? { |
| 79 | ui-function / setGpuTexture | fun setGpuTexture(t: GpuTexture?) { |
| 83 | ui-function / setGpuTextureView | fun setGpuTextureView(v: GpuTextureView?) { |
| 89 | ui-function / close | override fun close() { |
| 96 | ui-function / blitHud | fun blitHud(guiGraphics: GuiGraphicsExtractor) { |
| 126 | ui-function / screen | fun screen(dirty: Boolean, blurAlpha: Float, underlay: GuiDraw?, paint: (RenderContext) -> Unit) { |
| 144 | ui-function / drawBeforeBlit | fun drawBeforeBlit() { |
| 149 | ui-function / drawAtPresent | fun drawAtPresent() { |
| 157 | ui-function / draw | private fun draw(fbo: Int) { |
| 163 | blur / GlBlur.release | GlBlur.release() |
| 171 | peripheral-ui / NotificationsManager.count | if (NotificationsManager.count > 0) drawGl(fbo, null) { |
| 178 | ui-function / drawBlur | private fun drawBlur(fbo: Int, alpha: Float) { |
| 184 | blur / GlBlur.draw | GlBlur.draw(fbo, w, h, alpha, client.isPaused) |
| 192 | ui-function / drawGl | private fun drawGl(fbo: Int, cached: GlRenderer?, kept: Boolean = false, draw: (RenderContext) -> Unit): GlRenderer? { |
| 211 | ui-function / maxTextureSize | private fun maxTextureSize(): Int = |
| 216 | ui-function / cachedMaxTextureSize | private fun cachedMaxTextureSize(): Int { |
| 225 | ui-function / viewportFitsTexture | private fun viewportFitsTexture(w: Int, h: Int): Boolean { |
| 244 | ui-function / reuse | private fun reuse(renderer: GlRenderer?): GlRenderer { |
| 250 | ui-function / drawHudGl | fun drawHudGl(draw: (RenderContext, RenderContext?) -> Unit) { |
| 269 | ui-function / resolveHudTarget | private fun resolveHudTarget(): RenderTarget? { |
| 299 | ui-function / destroyHudTarget | private fun destroyHudTarget() { |
| 313 | ui-function / onAllocFailure | private fun onAllocFailure(what: String, w: Int, h: Int, error: Throwable) { |
| 328 | ui-function / recreateSurface | fun recreateSurface(width: Int, height: Int) { |

### 26.1.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/GlRenderer.kt

`file-730418d16944a91dc18a`；状态 **pending**；领域 ui-support；组合 26.1.2/fabric, 26.1.2/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 41 | ui-function / moved | fun moved(at: Int) = Live(at, state, draw, key).also { it.last = last } |
| 43 | ui-function / changed | fun changed() = key == null \|\| key() != last |
| 45 | ui-function / commit | fun commit() { |
| 55 | ui-function / bitmapTexture | override fun bitmapTexture(bitmap: PolyBitmap): Any = GlTextures.bitmap(bitmap) |
| 59 | ui-function / mark | override fun mark(): Any { |
| 64 | ui-function / since | override fun since(mark: Any): Any { |
| 87 | ui-function / valid | override fun valid(captured: Any): Boolean = (captured as Captured).batches.all { GlTextures.alive(it.texture) } |
| 89 | ui-function / append | override fun append(captured: Any) { |
| 97 | ui-function / writeStateKey | override fun writeStateKey() { |
| 102 | ui-function / matches | fun matches(): Boolean = |
| 105 | ui-function / reset | fun reset() { |
| 111 | ui-function / submit | override fun submit(texture: Any, premultiplied: Boolean, nearest: Boolean, verts: FloatArray, colors: IntArray, bounds: FloatArray) { |
| 117 | ui-function / layerMark | override fun layerMark(): Any = intArrayOf(batches.size, lives.size) |
| 119 | ui-function / layer | override fun layer(mark: Any, alpha: Float) { |
| 132 | ui-function / fade | override fun fade(mark: Any, alpha: Float): Boolean { |
| 142 | ui-function / live | override fun live(draw: (PolyRenderer) -> Unit) { |
| 150 | ui-function / mcTextLive | override fun mcTextLive(text: String, x: Float, y: Float, color: () -> Int, shadow: Boolean, scale: Float) { |
| 176 | ui-function / texture | override fun texture(target: RenderTarget, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, alpha: Float) { |
| 186 | ui-function / draw | fun draw(fbo: Int, w: Int, h: Int, topDown: Boolean, clear: Boolean, kept: Boolean = false) { |
| 192 | ui-function / flushPending | private fun flushPending() = flush() |
| 194 | ui-function / replay | private fun replay(lives: List<Live>): List<GlProgram.Segment> = lives.map { l -> |
| 209 | ui-function / take | fun take(renderer: GlRenderer) { |
| 224 | ui-function / idle | private fun idle() { |
| 232 | ui-function / same | private fun same(a: List<GlProgram.Batch>, b: List<GlProgram.Batch>) = a.size == b.size && a.indices.all { a[it] === b[it] } |
| 234 | ui-function / sameLives | private fun sameLives(a: List<Live>, b: List<Live>) = |
| 237 | ui-function / coalesce | private fun coalesce() { |
| 241 | ui-function / emit | fun emit() { |
| 264 | ui-function / draw | fun draw(fbo: Int, w: Int, h: Int, topDown: Boolean, clear: Boolean, vararg layers: Layer) { |
| 292 | ui-function / stale | private fun stale(): Boolean = (live != null && changed()) \|\| batches.any { (it.texture as? Group)?.stale() == true } |
| 294 | ui-function / render | private fun render(composite: GlProgram.Batch, transient: ArrayList<Group>?, sink: ArrayList<Group>) { |
| 322 | ui-function / release | fun release() { |
| 336 | ui-function / of | fun of(batches: List<GlProgram.Batch>, live: (() -> List<GlProgram.Segment>)?, changed: () -> Boolean, alpha: Float, screenW: Int, screenH: Int): Group? { |
| 352 | ui-function / prepare | fun prepare(batches: List<GlProgram.Batch>, transient: ArrayList<Group>?, sink: ArrayList<Group>) { |
| 356 | ui-function / endFrame | fun endFrame() { |
| 361 | ui-function / recycle | private fun recycle(t: IntArray) { |
| 365 | ui-function / acquire | private fun acquire(w: Int, h: Int): IntArray { |
| 381 | ui-function / delete | private fun delete(t: IntArray) { |
| 392 | ui-function / segments | fun segments(lives: List<Live>): List<GlProgram.Segment> { |
| 397 | ui-function / drawBatches | fun drawBatches(fbo: Int, w: Int, h: Int, topDown: Boolean, clear: Boolean, batches: List<GlProgram.Batch>, segments: List<GlProgram.Segment>, kept: Boolean, arena: Boolean) { |
| 426 | ui-function / sameKey | fun sameKey(o: Batch) = texture === o.texture && premultiplied == o.premultiplied && nearest == o.nearest |
| 428 | ui-function / faded | fun faded(alpha: Float) = Batch(texture, premultiplied, nearest, verts, IntArray(colors.size) { i -> |
| 444 | ui-function / merge | fun merge(list: List<Batch>): Batch { |
| 493 | ui-function / init | private fun init() { |
| 520 | ui-function / shader | private fun shader(type: Int, src: String): Int { |
| 528 | ui-function / invalidate | fun invalidate(b: Batch) { |
| 534 | ui-function / draw | fun draw(batches: List<Batch>, w: Int, h: Int, topDown: Boolean, live: List<Segment> = emptyList(), kept: Boolean = false, ox: Float = 0f, oy: Float = 0f) { |
| 578 | ui-function / drawArena | fun drawArena(batches: List<Batch>, w: Int, h: Int, topDown: Boolean, live: List<Segment>) { |
| 642 | ui-function / use | fun use(id: Int) { |
| 673 | ui-function / attribPointers | private fun attribPointers() { |
| 679 | ui-function / setupState | private fun setupState(w: Int, h: Int, topDown: Boolean, ox: Float = 0f, oy: Float = 0f) { |
| 696 | ui-function / drawBatch | private fun drawBatch(b: Batch, first: Int) { |
| 712 | ui-function / filter | private fun filter(mode: Int) { |
| 717 | ui-function / upload | private fun upload(at: Int, batches: List<Batch>): Int { |
| 751 | ui-function / ensureIndices | private fun ensureIndices(quads: Int) { |
| 799 | ui-function / bitmap | fun bitmap(bitmap: PolyBitmap): Tex = bitmaps.getOrPut(bitmap) { |
| 804 | ui-function / external | fun external(id: Int): Tex = external.getOrPut(id) { Tex(null, id, 0) } |
| 806 | ui-function / alive | fun alive(key: Any): Boolean = key !is Tex \|\| key.bitmap == null \|\| bitmaps[key.bitmap] === key |
| 808 | ui-function / id | fun id(key: Any): Int = when (key) { |
| 819 | ui-function / glyphPage | private fun glyphPage(page: PolyGlyphPage.Page): Int { |
| 836 | ui-function / upload | private fun upload(bitmap: PolyBitmap): Int { |
| 845 | ui-function / unpackDefaults | private fun unpackDefaults(rowLength: Int) { |
| 853 | ui-function / newTexture | private fun newTexture(): Int { |
| 863 | ui-function / genTexture | private fun genTexture(): Int { |
| 867 | ui-function / bind | private fun bind(id: Int) { |
| 871 | ui-function / texParameter | private fun texParameter(name: Int, value: Int) { |
| 875 | ui-function / pixelStore | private fun pixelStore(name: Int, value: Int) { |
| 879 | ui-function / texImage | private fun texImage(w: Int, h: Int, px: IntBuffer) { |
| 883 | ui-function / texSubImage | private fun texSubImage(y: Int, w: Int, h: Int, px: IntBuffer) { |
| 887 | ui-function / deleteTexture | private fun deleteTexture(id: Int) { |
| 891 | ui-function / endFrame | fun endFrame() { |

### 26.1.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/MacScrollEvent.kt

`file-53382e72fc052487481c`；状态 **pending**；领域 ui-support；组合 26.1.2/fabric, 26.1.2/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 18 | ui-function / readScroll | fun readScroll(): Boolean? = try { |

### 26.1.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/McFontService.kt

`file-7f84fd28089c21529d7c`；状态 **pending**；领域 font-language；组合 26.1.2/fabric, 26.1.2/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 26 | font-language / McFontService | object McFontService { |
| 29 | ui-function / init | fun init() { |
| 40 | ui-function / installBundledFonts | private fun installBundledFonts() { |
| 41 | font-language / McFontService | val loader = McFontService::class.java.classLoader |
| 43 | font-language / UiFontFiles.DEFAULT_REGULAR | "assets/oneconfig/fonts/minecraft/Minecraft-Regular.otf" to UiFontFiles.DEFAULT_REGULAR, |
| 44 | font-language / UiFontFiles.DEFAULT_BOLD | "assets/oneconfig/fonts/minecraft/Minecraft-Bold.otf" to UiFontFiles.DEFAULT_BOLD, |
| 56 | ui-function / installHostFonts | private fun installHostFonts() { |
| 57 | font-language / UiFontFiles.readRegular | val regular = UiFontFiles.readRegular() |
| 64 | font-language / UiFontFiles.REGULAR | FontManager.replace(UiFontFiles.REGULAR, regular) |
| 65 | font-language / UiFontFiles.readBold | val bold = UiFontFiles.readBold() ?: regular |
| 67 | font-language / UiFontFiles.BOLD | FontManager.replace(UiFontFiles.BOLD, bold) |
| 70 | font-language / UiFontFiles.BOLD | FontManager.replace(UiFontFiles.BOLD, regular) |
| 72 | font-language / UiFontFiles.REGULAR | FontManager.setDefault(UiFontFiles.REGULAR) |
| 78 | font-language / UiFontFiles.readFallback | val fallback = UiFontFiles.readFallback() |
| 81 | font-language / UiFontFiles.FALLBACK | FontManager.replace(UiFontFiles.FALLBACK, fallback) |
| 92 | ui-function / installEngines | fun installEngines() { |

### 26.1.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/McResourceFont.kt

`file-3974c3ea538bd6d1387a`；状态 **pending**；领域 font-language；组合 26.1.2/fabric, 26.1.2/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 28 | font-language / McResourceFont | internal object McResourceFont { |
| 40 | ui-function / glyph | fun glyph(cp: Int): McGlyph? = glyphs[cp] ?: unihex[cp]?.let { u -> baked.getOrPut(cp) { bake(u) } } |
| 47 | ui-function / init | fun init() { |
| 55 | ui-function / reload | private fun reload() { |
| 74 | ui-function / fontOptions | private fun fontOptions(): Int { |
| 82 | ui-function / load | private fun load(rm: ResourceManager, options: Int): Font? { |
| 88 | ui-function / unclaimed | fun unclaimed(cp: Int) = cp !in glyphs && cp !in unihex |
| 106 | ui-function / collectProviders | private fun collectProviders(rm: ResourceManager, ref: String, out: MutableList<JsonObject>, visited: MutableSet<String>, options: Int) { |
| 122 | ui-function / filterPasses | private fun filterPasses(provider: JsonObject, options: Int): Boolean { |
| 129 | ui-function / loadBitmap | private fun loadBitmap(rm: ResourceManager, provider: JsonObject): Map<Int, McGlyph> { |
| 156 | ui-function / loadUnihex | private fun loadUnihex(rm: ResourceManager, provider: JsonObject): Map<Int, Unihex> { |
| 181 | ui-function / parseHex | private fun parseHex(line: String, overrides: List<IntArray>, out: MutableMap<Int, Unihex>): Boolean { |
| 209 | ui-function / bake | private fun bake(u: Unihex): McGlyph { |
| 221 | ui-function / read | private fun read(rm: ResourceManager, ref: String, folder: String): ByteArray? = |
| 224 | ui-function / id | private fun id(ref: String, folder: String, suffix: String = "") = |

### 26.1.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/MinecraftKeyboardAdapter.kt

`file-8bf212c23f9a528c775c`；状态 **pending**；领域 ui-support；组合 26.1.2/fabric, 26.1.2/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 13 | ui-function / keyLocation | fun keyLocation(bindingKey: Int): Int  = when (bindingKey) { |
| 20 | ui-function / toAwtKeyCode | fun toAwtKeyCode(shortcutKey: Int): Int = when (shortcutKey) { |

### 26.1.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/UiBlur.kt

`file-edd38b56c2388b059ff2`；状态 **pending**；领域 blur；组合 26.1.2/fabric, 26.1.2/neoforge。

对齐背景模糊开关、强度、HUD 穿透、resize 与关屏后的 GPU/画面恢复。 定位类型: blur 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 20 | blur / UiBlur | object UiBlur { |
| 51 | ui-function / extract | fun extract(ctx: GuiGraphicsExtractor, alpha: Float) { |
| 68 | ui-function / process | fun process(): Boolean { |
| 98 | ui-function / downInfo | private fun downInfo(mainW: Int, mainH: Int, w: Int, h: Int): GpuBuffer { |
| 109 | ui-function / info | private fun info(outW: Int, outH: Int, inW: Int, inH: Int): GpuBuffer = MemoryStack.stackPush().use { stack -> |
| 115 | ui-function / draw | private fun draw(pass: PostPass, input: GpuTextureView, output: RenderTarget, info: GpuBuffer) { |

### 26.1.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/impls/HudEditorUIScreen.kt

`file-e648ce0f51691178157b`；状态 **pending**；领域 hud-editor；组合 26.1.2/fabric, 26.1.2/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 36 | route / HudEditorUIScreen | class HudEditorUIScreen private constructor() : ComposeScreen() { |
| 41 | ui-function / open | fun open(fromOneConfig: Boolean): HudEditorUIScreen { |
| 56 | ui-function / beginClose | private fun beginClose() { |
| 61 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLOSE) |
| 61 | sound / UiSoundEvent.CLOSE | UiSounds.play(UiSoundEvent.CLOSE) |
| 62 | sound / UiSounds.releaseAmbience | UiSounds.releaseAmbience() |
| 62 | sound / releaseAmbience | UiSounds.releaseAmbience() |
| 65 | ui-function / cancelClose | private fun cancelClose(): Boolean { |
| 69 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.OPEN) |
| 69 | sound / UiSoundEvent.OPEN | UiSounds.play(UiSoundEvent.OPEN) |
| 70 | sound / UiSounds.acquireAmbience | UiSounds.acquireAmbience() |
| 70 | sound / acquireAmbience | UiSounds.acquireAmbience() |
| 78 | ui-function / returnToOneConfig | private fun returnToOneConfig() { |
| 86 | ui-function / init | override fun init() { |
| 95 | sound / UiSounds.acquireAmbience | UiSounds.acquireAmbience() |
| 95 | sound / acquireAmbience | UiSounds.acquireAmbience() |
| 101 | ui-function / removed | override fun removed() { |
| 107 | sound / UiSounds.releaseAmbience | UiSounds.releaseAmbience() |
| 107 | sound / releaseAmbience | UiSounds.releaseAmbience() |
| 113 | ui-function / isPauseScreen | override fun isPauseScreen(): Boolean = OneConfigConfig.pauseGame |
| 115 | ui-function / handleOneConfigKeybind | private fun handleOneConfigKeybind(): Boolean { |
| 127 | ui-function / handleKeyPressed | override fun handleKeyPressed(key: Int, modifiers: Int): Boolean { |
| 136 | keybind / KeybindRecordingBus.isRecording | if (toggleKey != null && key == toggleKey && !KeybindRecordingBus.isRecording) { |
| 142 | ui-function / handleMouseClicked | override fun handleMouseClicked(button: Int): Boolean { |
| 143 | keybind / KeybindRecordingBus.isRecording | if (KeybindRecordingBus.isRecording) return false |
| 146 | keybind / KeybindManager.isTriggeredByMouse | KeybindManager.isTriggeredByMouse(OneConfigConfig.oneConfigKeybind, button) |
| 154 | ui-function / extractRenderState | override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, tickDelta: Float) { |
| 174 | composable / compose | @Composable |
| 175 | ui-function / compose | override fun compose() { |
| 196 | animation / RetainedVisibility | RetainedVisibility( |
| 198 | animation / tween | enter = tween(200, easing = EaseOutCubic), |
| 199 | animation / tween | exit = tween(exitMs, easing = EaseIn), |

### 26.1.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/impls/OneConfigUIScreen.kt

`file-1ed614f0ca6b7d36b633`；状态 **pending**；领域 shell-navigation；组合 26.1.2/fabric, 26.1.2/neoforge。

对齐背景模糊开关、强度、HUD 穿透、resize 与关屏后的 GPU/画面恢复。 定位类型: blur 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 79 | route / OneConfigUIScreen | class OneConfigUIScreen @JvmOverloads constructor( |
| 91 | ui-function / blurAlpha | override fun blurAlpha(): Float { |
| 92 | blur / enableBackgroundBlur | if (!OneConfigConfig.enableBackgroundBlur) return 0f |
| 110 | ui-function / shared | private fun shared(): OneConfigUIScreen = |
| 114 | ui-function / forRoute | fun forRoute(route: Any?): OneConfigUIScreen = shared().also { |
| 120 | ui-function / open | fun open(): OneConfigUIScreen = shared().also { |
| 127 | ui-function / resume | fun resume(): OneConfigUIScreen = shared().also { |
| 141 | ui-function / scheduleSave | private fun scheduleSave() { |
| 154 | route / OpeningRoute | private data class OpeningRoute(val route: Any, val restored: Boolean = false) |
| 156 | ui-function / resolveOpeningRoute | private fun resolveOpeningRoute(): OpeningRoute { |
| 176 | ui-function / stopWarmUp | fun stopWarmUp() { |
| 189 | ui-function / dropPrerasteredLater | private fun dropPrerasteredLater() { |
| 196 | ui-function / prepareWarmUp | fun prepareWarmUp(): Runnable? { |
| 211 | ui-function / warmUp | private fun warmUp(w: Int, h: Int, density: Float, surface: Float) { |
| 221 | ui-function / warmFrames | private fun warmFrames(w: Int, h: Int, density: Float, surface: Float) { |
| 226 | ui-function / isDispatchNeeded | override fun isDispatchNeeded(context: CoroutineContext) = Thread.currentThread() !== thread |
| 227 | ui-function / dispatch | override fun dispatch(context: CoroutineContext, block: Runnable) { |
| 231 | ui-function / drain | fun drain() { |
| 291 | ui-function / openLastSession | fun openLastSession() { |
| 302 | ui-function / beginClose | private fun beginClose() { |
| 307 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLOSE) |
| 307 | sound / UiSoundEvent.CLOSE | UiSounds.play(UiSoundEvent.CLOSE) |
| 308 | sound / UiSounds.releaseAmbience | UiSounds.releaseAmbience() |
| 308 | sound / releaseAmbience | UiSounds.releaseAmbience() |
| 311 | ui-function / cancelClose | private fun cancelClose(): Boolean { |
| 322 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.OPEN) |
| 322 | sound / UiSoundEvent.OPEN | UiSounds.play(UiSoundEvent.OPEN) |
| 323 | sound / UiSounds.acquireAmbience | UiSounds.acquireAmbience() |
| 323 | sound / acquireAmbience | UiSounds.acquireAmbience() |
| 339 | ui-function / markClosed | private fun markClosed() { |
| 343 | ui-function / init | override fun init() { |
| 415 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.OPEN) |
| 415 | sound / UiSoundEvent.OPEN | UiSounds.play(UiSoundEvent.OPEN) |
| 416 | sound / UiSounds.acquireAmbience | UiSounds.acquireAmbience() |
| 416 | sound / acquireAmbience | UiSounds.acquireAmbience() |
| 426 | ui-function / onSceneRebuilding | override fun onSceneRebuilding() { |
| 432 | ui-function / removed | override fun removed() { |
| 437 | sound / UiSounds.releaseAmbience | UiSounds.releaseAmbience() |
| 437 | sound / releaseAmbience | UiSounds.releaseAmbience() |
| 443 | ui-function / isPauseScreen | override fun isPauseScreen(): Boolean = OneConfigConfig.pauseGame |
| 445 | ui-function / handleOneConfigKeybind | private fun handleOneConfigKeybind(): Boolean { |
| 457 | ui-function / handleKeyPressed | override fun handleKeyPressed(key: Int, modifiers: Int): Boolean { |
| 466 | keybind / KeybindRecordingBus.isRecording | if (toggleKey != null && key == toggleKey && !KeybindRecordingBus.isRecording) { |
| 473 | ui-function / handleMouseClicked | override fun handleMouseClicked(button: Int): Boolean { |
| 474 | keybind / KeybindRecordingBus.isRecording | if (KeybindRecordingBus.isRecording) return false |
| 477 | keybind / KeybindManager.isTriggeredByMouse | KeybindManager.isTriggeredByMouse(OneConfigConfig.oneConfigKeybind, button) |
| 484 | keybind / KeybindManager.hasTriggeredMouseBind | if (KeybindManager.hasTriggeredMouseBind(button)) return true |
| 485 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLICK) |
| 485 | sound / UiSoundEvent.CLICK | UiSounds.play(UiSoundEvent.CLICK) |
| 490 | keybind / KeybindManager.hasTriggeredMouseBind | if (KeybindManager.hasTriggeredMouseBind(button)) return true |
| 491 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLICK) |
| 491 | sound / UiSoundEvent.CLICK | UiSounds.play(UiSoundEvent.CLICK) |
| 501 | ui-function / extractRenderState | override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, tickDelta: Float) { |
| 523 | ui-function / easeOutExpo | private fun easeOutExpo(progress: Float): Float { |
| 531 | composable / compose | @Composable |
| 532 | ui-function / compose | override fun compose() { |
| 564 | ui-function / submit | override fun submit(texture: Any, premultiplied: Boolean, nearest: Boolean, verts: FloatArray, colors: IntArray, bounds: FloatArray) {} |
| 566 | ui-function / bitmapTexture | override fun bitmapTexture(bitmap: PolyBitmap): Any = bitmap |
| 568 | ui-function / live | override fun live(draw: (PolyRenderer) -> Unit) = draw(this) |

### 26.1.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/opengl/StoredGLState.kt

`file-5b2b14783661a3982086`；状态 **pending**；领域 ui-support；组合 26.1.2/fabric, 26.1.2/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 10 | ui-function / resyncTextureBindCache | fun resyncTextureBindCache() { |
| 21 | ui-function / capture | fun capture(): StoredGLState { |
| 102 | ui-function / restore | fun restore(): StoredGLState { |
| 195 | ui-function / forceToggle | private fun forceToggle(enabled: Boolean, enable: () -> Unit, disable: () -> Unit) { |
| 205 | ui-function / forceDepthFunc | private fun forceDepthFunc(func: Int) { |
| 211 | ui-function / forceDepthMask | private fun forceDepthMask(mask: Boolean) { |

### 26.1.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/CompatOverlayRenderer.kt

`file-5de2a9f74f9c07bce4cc`；状态 **pending**；领域 hud-editor；组合 26.1.2/fabric, 26.1.2/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 14 | ui-function / register | fun register(hook: (GuiGraphicsExtractor) -> Unit) { |
| 19 | ui-function / hasHooks | fun hasHooks(): Boolean = hooks.isNotEmpty() |
| 22 | ui-function / oneConfigScreenOpen | fun oneConfigScreenOpen(): Boolean = HudManager.isEditing |
| 25 | ui-function / render | fun render(ctx: GuiGraphicsExtractor) { |

### 26.1.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/LegacyHudRenderer.kt

`file-a955aaace570466f54e4`；状态 **pending**；领域 hud-editor；组合 26.1.2/fabric, 26.1.2/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 32 | ui-function / reportFailure | private fun reportFailure(hud: LegacyHud, e: Throwable) { |
| 47 | ui-function / renderLive | fun renderLive(graphics: GuiGraphicsExtractor) { |
| 52 | ui-function / renderLiveHuds | private fun renderLiveHuds(graphics: GuiGraphicsExtractor) { |

### 26.1.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/keybind/MinecraftKeybindProvider.kt

`file-07778b12269beecf60b9`；状态 **pending**；领域 keybind；组合 26.1.2/fabric, 26.1.2/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 22 | keybind / OneConfigKeybind | private val properties = IdentityHashMap<KeyMapping, Property<OneConfigKeybind>>() |
| 37 | ui-function / groups | override fun groups(): List<KeybindGroup> { |
| 77 | ui-function / managedMappings | fun managedMappings(): List<KeyMapping> { |
| 82 | keybind / OneConfigKeybind | private fun propertyFor(mapping: KeyMapping): Property<OneConfigKeybind> { |
| 82 | ui-function / propertyFor | private fun propertyFor(mapping: KeyMapping): Property<OneConfigKeybind> { |
| 89 | keybind / OneConfigKeybind | OneConfigKeybind::class.java, |
| 99 | ui-function / ownerMod | private fun ownerMod(mapping: KeyMapping, modsById: Map<String, ModInfo>): ModInfo? { |
| 105 | ui-function / ownerCandidates | private fun ownerCandidates(keyName: String): Sequence<String> = sequence { |
| 117 | ui-function / modIcon | private fun modIcon(owner: ModInfo?): String? = |
| 124 | ui-function / minecraftIcon | private fun minecraftIcon(): String { |
| 132 | ui-function / extractMinecraftIcon | private fun extractMinecraftIcon(): String? = runCatching { |
| 148 | ui-function / groupTitle | private fun groupTitle(category: String, owner: ModInfo?): String { |
| 154 | ui-function / sanitizeId | private fun sanitizeId(value: String): String { |
| 161 | keybind / OneConfigKeybind | private fun KeyMapping.toOneConfigKeybind(): OneConfigKeybind { |
| 161 | ui-function / toOneConfigKeybind | private fun KeyMapping.toOneConfigKeybind(): OneConfigKeybind { |
| 166 | keybind / Key.toOneConfigKeybind | private fun InputConstants.Key.toOneConfigKeybind(): OneConfigKeybind { |
| 166 | keybind / OneConfigKeybind | private fun InputConstants.Key.toOneConfigKeybind(): OneConfigKeybind { |
| 166 | ui-function / toOneConfigKeybind | private fun InputConstants.Key.toOneConfigKeybind(): OneConfigKeybind { |
| 171 | keybind / OneConfigKeybind | OneConfigKeybind(intArrayOf(value), null, KeyModifiers.NONE, 0L) { true } |
| 174 | keybind / OneConfigKeybind | OneConfigKeybind(null, intArrayOf(value), KeyModifiers.NONE, 0L) { true } |
| 175 | keybind / OneConfigKeybind | else -> OneConfigKeybind(null, null, KeyModifiers.NONE, 0L) { true } |
| 179 | keybind / OneConfigKeybind | private fun KeyMapping.applyOneConfigKeybind(keybind: OneConfigKeybind?) { |
| 179 | ui-function / applyOneConfigKeybind | private fun KeyMapping.applyOneConfigKeybind(keybind: OneConfigKeybind?) { |
| 193 | ui-function / categoryLabel | private fun categoryLabel(mapping: KeyMapping): String { |
| 199 | ui-function / translate | private fun translate(key: String): String { |
| 211 | ui-function / isOneConfigMirror | private fun isOneConfigMirror(@Suppress("UNUSED_PARAMETER") mapping: KeyMapping): Boolean = false |

### 26.1.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/sound/McUiSoundService.kt

`file-adde74dcce8aab5d3a77`；状态 **pending**；领域 sound；组合 26.1.2/fabric, 26.1.2/neoforge。

对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 19 | sound / McUiSoundService | class McUiSoundService : UiSoundService { |
| 19 | sound / UiSoundService | class McUiSoundService : UiSoundService { |
| 28 | sound / UiSoundTheme | private val ambienceShuffle = mutableMapOf<UiSoundTheme, MutableList<String>>() |
| 29 | sound / UiSoundTheme | private val lastAmbienceSoundId = mutableMapOf<UiSoundTheme, String>() |
| 33 | sound / UiSoundEvent | override fun play(event: UiSoundEvent, theme: UiSoundTheme, volume: Float) { |
| 33 | sound / UiSoundTheme | override fun play(event: UiSoundEvent, theme: UiSoundTheme, volume: Float) { |
| 33 | ui-function / play | override fun play(event: UiSoundEvent, theme: UiSoundTheme, volume: Float) { |
| 37 | sound / UiSoundTheme.MODERN | UiSoundTheme.MODERN -> playModern(event, volume) |
| 38 | sound / UiSoundTheme.MINECRAFT | UiSoundTheme.MINECRAFT -> playMinecraft(event, volume) |
| 44 | sound / UiSoundEvent | private fun playModern(event: UiSoundEvent, volume: Float) { |
| 44 | ui-function / playModern | private fun playModern(event: UiSoundEvent, volume: Float) { |
| 46 | sound / UiSoundEvent.OPEN | UiSoundEvent.OPEN -> playModernSound("ui.open", 1f, volume) |
| 47 | sound / UiSoundEvent.CLOSE | UiSoundEvent.CLOSE -> playModernSound("ui.close", 1f, volume) |
| 48 | sound / UiSoundEvent.CLICK | UiSoundEvent.CLICK -> playModernSound("ui.click", 1f, volume) |
| 49 | sound / UiSoundEvent.SLIDER_TICK | UiSoundEvent.SLIDER_TICK -> playModernSound("ui.slider", 1f, volume) |
| 50 | sound / UiSoundEvent.HUD_SELECT | UiSoundEvent.HUD_SELECT -> playModernSound("ui.click", 1.0f, volume) |
| 51 | sound / UiSoundEvent.HUD_DRAG_START | UiSoundEvent.HUD_DRAG_START -> playModernSound("ui.click", 0.9f, volume) |
| 52 | sound / UiSoundEvent.HUD_DRAG_END | UiSoundEvent.HUD_DRAG_END -> playModernSound("ui.click", 1.1f, volume) |
| 53 | sound / UiSoundEvent.HUD_RESIZE_END | UiSoundEvent.HUD_RESIZE_END -> playModernSound("ui.click", 1.0f, volume) |
| 58 | ui-function / playModernSound | private fun playModernSound(id: String, pitch: Float, volume: Float) { |
| 63 | sound / UiSoundEvent | private fun playMinecraft(event: UiSoundEvent, volume: Float) { |
| 63 | ui-function / playMinecraft | private fun playMinecraft(event: UiSoundEvent, volume: Float) { |
| 65 | sound / UiSoundEvent.CLICK | UiSoundEvent.CLICK -> playVanilla(SoundEvents.UI_BUTTON_CLICK, 1f, 0.5f * volume) |
| 66 | sound / UiSoundEvent.OPEN | UiSoundEvent.OPEN -> playModernSound("ui.open", 1f, volume) |
| 67 | sound / UiSoundEvent.CLOSE | UiSoundEvent.CLOSE -> playModernSound("ui.close", 1f, volume) |
| 68 | sound / UiSoundEvent.SLIDER_TICK | UiSoundEvent.SLIDER_TICK -> { |
| 73 | sound / UiSoundEvent.HUD_SELECT | UiSoundEvent.HUD_SELECT -> playVanilla(SoundEvents.UI_BUTTON_CLICK, 1f, 0.3f * volume) |
| 74 | sound / UiSoundEvent.HUD_DRAG_END | UiSoundEvent.HUD_DRAG_END -> playVanilla(SoundEvents.UI_BUTTON_CLICK, 1f, 0.3f * volume) |
| 75 | sound / UiSoundEvent.HUD_RESIZE_END | UiSoundEvent.HUD_RESIZE_END -> playVanilla(SoundEvents.UI_BUTTON_CLICK, 1f, 0.3f * volume) |
| 80 | ui-function / playVanilla | private fun playVanilla(event: SoundEvent, pitch: Float, volume: Float) { |
| 85 | ui-function / playVanilla | private fun playVanilla(holder: Holder<SoundEvent>, pitch: Float, volume: Float) = |
| 88 | sound / startAmbience | override fun startAmbience(theme: UiSoundTheme, volume: Float) { |
| 88 | sound / UiSoundTheme | override fun startAmbience(theme: UiSoundTheme, volume: Float) { |
| 88 | ui-function / startAmbience | override fun startAmbience(theme: UiSoundTheme, volume: Float) { |
| 109 | sound / stopAmbience | override fun stopAmbience(@Suppress("UNUSED_PARAMETER") setDucking: Boolean) { |
| 109 | ui-function / stopAmbience | override fun stopAmbience(@Suppress("UNUSED_PARAMETER") setDucking: Boolean) { |
| 118 | sound / UiSoundTheme | private fun ambienceVolumeFor(theme: UiSoundTheme, volume: Float): Float = when (theme) { |
| 118 | ui-function / ambienceVolumeFor | private fun ambienceVolumeFor(theme: UiSoundTheme, volume: Float): Float = when (theme) { |
| 119 | sound / UiSoundTheme.MODERN | UiSoundTheme.MODERN -> 0.5f * volume |
| 120 | sound / UiSoundTheme.MINECRAFT | UiSoundTheme.MINECRAFT -> 0.08f * volume |
| 123 | sound / UiSoundTheme | private fun ambienceSoundIds(theme: UiSoundTheme): List<String> = when (theme) { |
| 123 | ui-function / ambienceSoundIds | private fun ambienceSoundIds(theme: UiSoundTheme): List<String> = when (theme) { |
| 124 | sound / UiSoundTheme.MODERN | UiSoundTheme.MODERN -> listOf("ui.pad_loop") |
| 125 | sound / UiSoundTheme.MINECRAFT | UiSoundTheme.MINECRAFT -> listOf( |
| 133 | sound / UiSoundTheme | private fun playAmbienceInstance(theme: UiSoundTheme, targetVolume: Float): FadingLoopSoundInstance { |
| 133 | ui-function / playAmbienceInstance | private fun playAmbienceInstance(theme: UiSoundTheme, targetVolume: Float): FadingLoopSoundInstance { |
| 144 | sound / UiSoundTheme | private fun nextAmbienceSoundId(theme: UiSoundTheme, ids: List<String>): String { |
| 144 | ui-function / nextAmbienceSoundId | private fun nextAmbienceSoundId(theme: UiSoundTheme, ids: List<String>): String { |
| 165 | ui-function / driveAmbienceLoop | private fun driveAmbienceLoop() { |
| 196 | sound / UiSoundTheme | val theme: UiSoundTheme, |
| 215 | ui-function / beginFadeOut | fun beginFadeOut() { |
| 219 | ui-function / cancelFadeOut | fun cancelFadeOut() { |
| 224 | ui-function / updateTargetVolume | fun updateTargetVolume(newTarget: Float) { |
| 229 | ui-function / tick | override fun tick() { |

### 26.2/fabric/src/main/java/org/polyfrost/oneconfig/internal/OneConfig.java

`file-b2434ec74adb6e357a7b`；状态 **pending**；领域 ui-support；组合 26.2/fabric。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 236 | search-provider / SearchCorpus.INSTANCE | EventManager.register(ResourceFinishedLoading.class, e -> SearchCorpus.INSTANCE.init()); |
| 335 | font-language / McFontService.INSTANCE | McFontService.INSTANCE.init(); |

### 26.2/fabric/src/main/java/org/polyfrost/oneconfig/internal/OneConfigPreLaunch.java

`file-0d35e343e8859c3c9512`；状态 **pending**；领域 ui-support；组合 26.2/fabric。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 25 | font-language / McFontService.installEngines | McFontService.installEngines(); |

### 26.2/neoforge/src/main/java/org/polyfrost/oneconfig/internal/OneConfig.java

`file-8006a96a5bd0d0dd035b`；状态 **pending**；领域 ui-support；组合 26.2/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 235 | search-provider / SearchCorpus.INSTANCE | EventManager.register(ResourceFinishedLoading.class, e -> SearchCorpus.INSTANCE.init()); |
| 345 | font-language / McFontService.INSTANCE | McFontService.INSTANCE.init(); |

### 26.2/neoforge/src/main/java/org/polyfrost/oneconfig/internal/OneConfigPreLaunch.java

`file-5aa6e626d1fd77f39e12`；状态 **pending**；领域 ui-support；组合 26.2/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 22 | font-language / McFontService.installEngines | org.polyfrost.oneconfig.internal.ui.compose.McFontService.installEngines(); |

### 26.2/src/main/java/org/polyfrost/oneconfig/api/platform/v1/internal/I18nPlatformImpl.java

`file-29ea0a9a6e024aa146d4`；状态 **pending**；领域 ui-support；组合 26.2/fabric, 26.2/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 40 | font-language / I18nPlatform | public class I18nPlatformImpl implements I18nPlatform { |
| 53 | font-language / Language.getInstance | return Language.getInstance().has(key); |

### 26.2/src/main/java/org/polyfrost/oneconfig/api/platform/v1/internal/KeysImpl.java

`file-7b27ca16c02cf8eb16e1`；状态 **pending**；领域 ui-support；组合 26.2/fabric, 26.2/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 21 | keybind / getKeyA | public int getKeyA() { |
| 26 | keybind / getKeyC | public int getKeyC() { |
| 31 | keybind / getKeyD | public int getKeyD() { |
| 36 | keybind / getKeyE | public int getKeyE() { |
| 41 | keybind / getKeyF | public int getKeyF() { |
| 46 | keybind / getKeyH | public int getKeyH() { |
| 51 | keybind / getKeyL | public int getKeyL() { |
| 56 | keybind / getKeyR | public int getKeyR() { |
| 61 | keybind / getKeyV | public int getKeyV() { |
| 66 | keybind / getKeyX | public int getKeyX() { |
| 71 | keybind / getKeyY | public int getKeyY() { |
| 76 | keybind / getKeyZ | public int getKeyZ() { |
| 81 | keybind / getKeyDelete | public int getKeyDelete() { |
| 91 | keybind / getKeyLeftShift | public int getKeyLeftShift() { |
| 96 | keybind / getKeyRightShift | public int getKeyRightShift() { |
| 101 | keybind / getKeyLeftControl | public int getKeyLeftControl() { |
| 106 | keybind / getKeyRightControl | public int getKeyRightControl() { |
| 111 | keybind / getKeyLeftAlt | public int getKeyLeftAlt() { |
| 116 | keybind / getKeyRightAlt | public int getKeyRightAlt() { |
| 121 | keybind / getKeyLeftSuper | public int getKeyLeftSuper() { |
| 126 | keybind / getKeyRightSuper | public int getKeyRightSuper() { |

### 26.2/src/main/java/org/polyfrost/oneconfig/api/ui/v1/api/internal/TinyFdApiImpl.java

`file-7475614f4d2f0ddf125d`；状态 **pending**；领域 ui-support；组合 26.2/fabric, 26.2/neoforge。

核对打开/取消/确认、焦点、边界定位、外点关闭与提交后可见结果。 定位类型: dialog-or-popup

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 74 | dialog-or-popup / Dialog | private static <T> T open(String description, T fallback, Dialog<T> dialog) { |
| 151 | dialog-or-popup / tinyfd_openFileDialog | String result = TinyFileDialogs.tinyfd_openFileDialog(sanitizeText(title), sanitizePath(defaultFilePath), filters(stack, filterPatterns), sanitizeText(filterDescription), false); |
| 161 | dialog-or-popup / tinyfd_saveFileDialog | String result = TinyFileDialogs.tinyfd_saveFileDialog(sanitizeText(title), sanitizePath(defaultFilePath), filters(stack, filterPatterns), sanitizeText(filterDescription)); |
| 171 | dialog-or-popup / tinyfd_openFileDialog | String result = TinyFileDialogs.tinyfd_openFileDialog(sanitizeText(title), sanitizePath(defaultFilePath), filters(stack, filterPatterns), sanitizeText(filterDescription), true); |
| 187 | dialog-or-popup / tinyfd_selectFolderDialog | firstPath(TinyFileDialogs.tinyfd_selectFolderDialog(sanitizeText(title), sanitizePath(defaultFolderPath)))); |
| 199 | dialog-or-popup / tinyfd_notifyPopup | TinyFileDialogs.tinyfd_notifyPopup(sanitizeText(title), sanitizeText(message), icon) == 1 ? 0 : 1); |
| 271 | dialog-or-popup / Dialog | private interface Dialog<T> { |

### 26.2/src/main/java/org/polyfrost/oneconfig/api/ui/v1/keybind/internal/MinecraftKeybindCodec.java

`file-2c3101f36f46e7b42396`；状态 **pending**；领域 keybind；组合 26.2/fabric, 26.2/neoforge。

该文件支撑 keybind；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 26.2/src/main/java/org/polyfrost/oneconfig/internal/mixin/render/Mixin_UiBlur.java

`file-0631c88bb27683ee7db0`；状态 **pending**；领域 blur；组合 26.2/fabric, 26.2/neoforge。

对齐背景模糊开关、强度、HUD 穿透、resize 与关屏后的 GPU/画面恢复。 定位类型: blur

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 14 | blur / UiBlur.process | if (UiBlur.process()) ci.cancel(); |

### 26.2/src/main/java/org/polyfrost/oneconfig/internal/ui/PlayerHeadBlit.java

`file-543691193389f483050b`；状态 **pending**；领域 ui-support；组合 26.2/fabric, 26.2/neoforge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 26.2/src/main/java/org/polyfrost/oneconfig/internal/ui/PlayerHeadSkinAccess.java

`file-d739001ec41cc5b641d0`；状态 **pending**；领域 ui-support；组合 26.2/fabric, 26.2/neoforge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 26.2/src/main/kotlin/org/polyfrost/oneconfig/api/hud/v1/LegacyHud.kt

`file-3ad47eb8c63282145e10`；状态 **pending**；领域 hud-editor；组合 26.2/fabric, 26.2/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 14 | ui-function / render | abstract fun render(mcCtx: GuiGraphicsExtractor) |
| 18 | ui-function / minimumSize | override fun minimumSize(): Pair<Float, Float> = width to height |
| 20 | composable / Content | @Composable |
| 21 | ui-function / Content | override fun Content() {} |

### 26.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/GuiProjection.kt

`file-0e288f742069cae2d0a9`；状态 **pending**；领域 ui-support；组合 26.2/fabric, 26.2/neoforge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 26.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/OffscreenTarget.kt

`file-53d7bb4f4581a1831c7a`；状态 **pending**；领域 ui-support；组合 26.2/fabric, 26.2/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 28 | ui-function / resolve | fun resolve(w: Int, h: Int): Boolean { |
| 47 | ui-function / clear | fun clear() { |
| 54 | ui-function / destroy | fun destroy() { |
| 59 | ui-function / dispose | fun dispose() { |
| 69 | ui-function / destroyAll | fun destroyAll() { |
| 82 | ui-function / texture | fun texture(target: RenderTarget, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, alpha: Float) |

### 26.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/RenderTargetFbo.kt

`file-576917bdf0ae665928f2`；状态 **pending**；领域 ui-support；组合 26.2/fabric, 26.2/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 32 | ui-function / unwrapTexture | private fun unwrapTexture(texture: com.mojang.blaze3d.textures.GpuTexture?): GlTexture? { |
| 42 | ui-function / getFboId | fun getFboId(frameBuffer: RenderTarget): Int { |
| 52 | ui-function / getColorTexId | fun getColorTexId(frameBuffer: RenderTarget): Int = |

### 26.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/BlazeRenderer.kt

`file-ced225250e1a7574240b`；状态 **pending**；领域 ui-support；组合 26.2/fabric, 26.2/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 从实际宿主入口触发，核对完整状态变化、交互、退出及重进后的结果。 定位类型: peripheral-ui 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 71 | ui-function / bitmapTexture | override fun bitmapTexture(bitmap: PolyBitmap): Any { |
| 80 | ui-function / mark | override fun mark(): Any { |
| 85 | ui-function / since | override fun since(mark: Any): Any { |
| 92 | ui-function / valid | override fun valid(captured: Any): Boolean { |
| 97 | ui-function / append | override fun append(captured: Any) { |
| 106 | ui-function / writeStateKey | override fun writeStateKey() { |
| 111 | ui-function / matches | fun matches(guiScale: Float): Boolean { |
| 116 | ui-function / reset | fun reset() { |
| 125 | ui-function / layerMark | override fun layerMark(): Any = ops.size |
| 127 | ui-function / layer | override fun layer(mark: Any, alpha: Float) { |
| 135 | ui-function / fade | override fun fade(mark: Any, alpha: Float): Boolean { |
| 150 | ui-function / touchText | private fun touchText(text: String, x: Float, y: Float, scale: Float, shadow: Boolean) { |
| 161 | ui-function / submit | override fun submit(texture: Any, premultiplied: Boolean, nearest: Boolean, verts: FloatArray, colors: IntArray, bounds: FloatArray) { |
| 173 | ui-function / mcText | override fun mcText(text: String, x: Float, y: Float, color: Int, shadow: Boolean, scale: Float) { |
| 185 | font-language / Language.getInstance | else if ('§' in line \|\| Language.getInstance().isDefaultRightToLeft) bakeText(font, seq, m, c, shadow, scissor) |
| 191 | ui-function / mcTextLive | override fun mcTextLive(text: String, x: Float, y: Float, color: () -> Int, shadow: Boolean, scale: Float) { |
| 192 | font-language / Language.getInstance | if (!keepVertices \|\| '§' in text \|\| Language.getInstance().isDefaultRightToLeft) return super.mcTextLive(text, x, y, color, shadow, scale) |
| 208 | ui-function / lineMatrix | private fun lineMatrix(x: Float, y: Float, index: Int, lineHeight: Int, scale: Float): Matrix3x2f = |
| 212 | ui-function / mcTextOutline | override fun mcTextOutline(text: String, x: Float, y: Float, color: Int, outlineColor: Int, scale: Float) { |
| 241 | ui-function / bakeText | private fun bakeText(font: Font, seq: FormattedCharSequence, m: Matrix3x2f, color: Int, shadow: Boolean, scissor: ScreenRectangle?): List<Mesh>? { |
| 252 | ui-function / close | fun close() { |
| 279 | ui-function / addVertex | override fun addVertex(x: Float, y: Float, z: Float) = apply { |
| 283 | ui-function / setUv | override fun setUv(u: Float, v: Float) = apply { verts[n * 4 - 2] = u; verts[n * 4 - 1] = v } |
| 284 | ui-function / setColor | override fun setColor(color: Int) = apply { colors[n - 1] = color } |
| 285 | ui-function / setColor | override fun setColor(r: Int, g: Int, b: Int, a: Int) = setColor((a shl 24) or (r shl 16) or (g shl 8) or b) |
| 286 | ui-function / setUv1 | override fun setUv1(u: Int, v: Int) = this |
| 287 | ui-function / setUv2 | override fun setUv2(u: Int, v: Int) = this |
| 288 | ui-function / setNormal | override fun setNormal(x: Float, y: Float, z: Float) = this |
| 289 | ui-function / setLineWidth | override fun setLineWidth(width: Float) = this |
| 292 | ui-function / texture | override fun texture(target: RenderTarget, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, alpha: Float) { |
| 296 | ui-function / texture | fun texture(view: GpuTextureView, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, alpha: Float) { |
| 308 | ui-function / live | override fun live(draw: (PolyRenderer) -> Unit) { |
| 318 | ui-function / faded | fun faded(alpha: Float) = LiveOp(s.copy().also { it.alpha *= alpha }, draw) |
| 320 | ui-function / invoke | override fun invoke(g: GuiGraphicsExtractor) { |
| 333 | ui-function / finish | fun finish(): List<(GuiGraphicsExtractor) -> Unit> { |
| 338 | ui-function / rectGui | private fun rectGui(x0: Float, y0: Float, x1: Float, y1: Float): ScreenRectangle? { |
| 347 | ui-function / invoke | override fun invoke(g: GuiGraphicsExtractor) { |
| 359 | ui-function / shifted | fun shifted(dx: Int, dy: Int) = LiveTextOp(meshes?.map { it.shifted(dx, dy) }, color, alpha, base) { g, c -> shiftedDraw(g, dx, dy) { fallback(it, c) } } |
| 361 | ui-function / faded | fun faded(a: Float) = LiveTextOp(meshes?.map { it.faded(1f) }, color, alpha * a, base, fallback) |
| 363 | ui-function / changed | fun changed() = alphaMul(color(), alpha) != drawn |
| 365 | ui-function / invoke | override fun invoke(g: GuiGraphicsExtractor) { |
| 383 | ui-function / shifted | fun shifted(dx: Int, dy: Int) = BakedTextOp(meshes?.map { it.shifted(dx, dy) }, color) { g, c -> shiftedDraw(g, dx, dy) { draw(it, c) } } |
| 385 | ui-function / faded | fun faded(alpha: Float) = BakedTextOp(meshes?.map { it.faded(alpha) }, alphaMul(color, alpha), draw) |
| 387 | ui-function / invoke | override fun invoke(g: GuiGraphicsExtractor) { |
| 411 | ui-function / shifted | fun shifted(dx: Int, dy: Int) = Mesh( |
| 416 | ui-function / faded | fun faded(alpha: Float): Mesh { |
| 422 | ui-function / mergeable | fun mergeable(o: Mesh): Boolean = |
| 426 | ui-function / overlaps | fun overlaps(b: ScreenRectangle): Boolean = |
| 430 | ui-function / merge | fun merge(list: List<Mesh>): Mesh { |
| 448 | ui-function / buildVertices | override fun buildVertices(consumer: VertexConsumer) { |
| 468 | ui-function / vertex | private fun vertex(consumer: VertexConsumer, x: Float, y: Float, u: Float, v: Float, color: Int) { |
| 473 | ui-function / pipeline | override fun pipeline() = if (gpu != null) KeptMeshes.pipeline(pipeline) else pipeline |
| 474 | ui-function / textureSetup | override fun textureSetup() = textureSetup |
| 475 | ui-function / scissorArea | override fun scissorArea(): ScreenRectangle? = scissor |
| 476 | ui-function / bounds | override fun bounds() = bounds |
| 480 | ui-function / coalesce | fun coalesce(ops: List<(GuiGraphicsExtractor) -> Unit>): List<(GuiGraphicsExtractor) -> Unit> { |
| 485 | ui-function / emit | fun emit() { |
| 491 | ui-function / union | fun union(a: ScreenRectangle, b: ScreenRectangle): ScreenRectangle { |
| 495 | ui-function / place | fun place(m: Mesh) { |
| 536 | ui-function / fadeColor | private fun fadeColor(color: Int, alpha: Float, premultiplied: Boolean): Int { |
| 547 | ui-function / alphaMul | private fun alphaMul(color: Int, alpha: Float): Int { |
| 553 | ui-function / vanillaText | private fun vanillaText(g: GuiGraphicsExtractor, font: Font, seq: FormattedCharSequence, m: Matrix3x2f, c: Int, shadow: Boolean, scissor: ScreenRectangle?) { |
| 562 | ui-function / isTextPipeline | private fun isTextPipeline(p: RenderPipeline) = p === RenderPipelines.GUI_TEXT \|\| p === RenderPipelines.GUI_TEXT_GRAYSCALE |
| 564 | ui-function / visitRenderables | private inline fun visitRenderables(prepared: Font.PreparedText, crossinline accept: (TextRenderable) -> Unit) { |
| 566 | ui-function / acceptRenderable | override fun acceptRenderable(renderable: TextRenderable) = accept(renderable) |
| 585 | ui-function / glyph | private fun glyph(font: Font, c: Char): Glyph { |
| 590 | ui-function / probe | private fun probe(font: Font, c: Char): Glyph { |
| 611 | ui-function / bake | fun bake(font: Font, line: String, m: Matrix3x2f, color: Int, shadow: Boolean, scissor: ScreenRectangle?, keep: Boolean = true): List<BlazeRenderer.Mesh>? { |
| 641 | ui-function / close | fun close() { |
| 664 | ui-function / quad | private fun quad(cap: BlazeRenderer.GlyphCapture, pose: Matrix4f, x: Float, y: Float, g: Glyph, color: Int) { |
| 674 | ui-function / visualOrder | private fun visualOrder(line: String): FormattedCharSequence { |
| 675 | font-language / Language.getInstance | val lang = Language.getInstance() |
| 704 | ui-function / get | fun get(text: String, kx: Float, ky: Float, fx: Float, fy: Float): Outline? { |
| 720 | ui-function / evict | private fun evict() { |
| 732 | ui-function / uncolored | private fun uncolored(seq: FormattedCharSequence) = FormattedCharSequence { sink -> |
| 742 | ui-function / draw | fun draw(g: GuiGraphicsExtractor, pose: Matrix3x2f, x0: Float, y0: Float, color: Int, bounds: ScreenRectangle, scissor: ScreenRectangle?) { |
| 759 | ui-function / release | internal fun release() { |
| 765 | ui-function / render | private fun render() { |
| 780 | ui-function / renderInto | private fun renderInto(mc: Minecraft, state: GuiRenderState, renderer: GuiRenderer, rt: RenderTarget) { |
| 842 | ui-function / acquire | fun acquire(mc: Minecraft): Slot { |
| 852 | ui-function / release | fun release(slot: Slot) { |
| 856 | ui-function / create | private fun create(mc: Minecraft, state: GuiRenderState): GuiRenderer { |
| 864 | ui-function / targetV | private fun targetV(v: Float) = if (topDownTargets) 1f - v else v |
| 886 | ui-function / pipeline | fun pipeline(vanilla: RenderPipeline) = when { |
| 893 | ui-function / capture | fun capture(mesh: BlazeRenderer.Mesh, ptr: Long, len: Int) { |
| 900 | ui-function / kept | private fun kept(element: GuiElementRenderState): BlazeRenderer.Mesh? { |
| 908 | ui-function / added | fun added(draw: Any, element: GuiElementRenderState) { |
| 914 | ui-function / execute | fun execute(draw: Any, pass: RenderPass): Boolean { |
| 944 | ui-function / endFrame | fun endFrame() { |
| 965 | ui-function / markDirty | fun markDirty() { |
| 970 | ui-function / render | fun render(gfx: GuiGraphicsExtractor) { |
| 992 | ui-function / reuse | private fun reuse(renderer: BlazeRenderer?, guiScale: Float): BlazeRenderer { |
| 1005 | ui-function / render | fun render(gfx: GuiGraphicsExtractor) { |
| 1006 | peripheral-ui / NotificationsManager.count | val show = NotificationsManager.count > 0 |
| 1026 | ui-function / render | fun render(gfx: GuiGraphicsExtractor, dirty: Boolean, draw: (RenderContext) -> Unit) { |
| 1041 | ui-function / take | fun take(renderer: BlazeRenderer, copy: Boolean = false) { |
| 1056 | ui-function / idle | fun idle() { |
| 1063 | ui-function / replay | fun replay(gfx: GuiGraphicsExtractor) { |
| 1075 | ui-function / moved | private fun moved(dx: Int, dy: Int) = LayerOp(ops, alpha, guiScale, bounds.shifted(dx, dy)) |
| 1077 | ui-function / faded | fun faded(a: Float) = LayerOp(ops, alpha * a, guiScale, bounds) |
| 1079 | ui-function / stale | private fun stale(): Boolean = ops.any { |
| 1088 | ui-function / invoke | override fun invoke(g: GuiGraphicsExtractor) { |
| 1095 | ui-function / release | fun release() { |
| 1103 | ui-function / render | private fun render(): BlazeRenderer.Mesh? { |
| 1131 | ui-function / renderInto | private fun renderInto(mc: Minecraft, state: GuiRenderState, renderer: GuiRenderer, rt: RenderTarget, gs: Int) { |
| 1156 | ui-function / of | fun of(ops: List<(GuiGraphicsExtractor) -> Unit>, alpha: Float, guiScale: Float, screenW: Int, screenH: Int): LayerOp? { |
| 1158 | ui-function / add | fun add(b: ScreenRectangle) { bounds = bounds?.let { union(it, b) } ?: b } |
| 1180 | ui-function / retire | fun retire(old: List<(GuiGraphicsExtractor) -> Unit>, next: List<(GuiGraphicsExtractor) -> Unit>) { |
| 1187 | ui-function / acquire | private fun acquire(w: Int, h: Int): OffscreenTarget { |
| 1193 | ui-function / sweep | private fun sweep(now: Long) { |
| 1199 | ui-function / union | private fun union(a: ScreenRectangle, b: ScreenRectangle): ScreenRectangle { |
| 1206 | ui-function / shifted | private fun ScreenRectangle.shifted(dx: Int, dy: Int) = ScreenRectangle(left() - dx, top() - dy, width(), height()) |
| 1208 | ui-function / shiftedDraw | private fun shiftedDraw(g: GuiGraphicsExtractor, dx: Int, dy: Int, draw: (GuiGraphicsExtractor) -> Unit) { |
| 1218 | ui-function / stacking | private inline fun stacking(gfx: GuiGraphicsExtractor, draw: () -> Unit) { |
| 1234 | ui-function / oneconfig$setStacking | fun `oneconfig$setStacking`(stacking: Boolean) |
| 1240 | ui-function / setup | fun setup(nearest: Boolean = false): TextureSetup = TextureSetup.singleTexture(texture.textureView, sampler(nearest)) |
| 1243 | ui-function / sampler | private fun sampler(nearest: Boolean) = RenderSystem.getSamplerCache().getClampToEdge(if (nearest) FilterMode.NEAREST else FilterMode.LINEAR) |
| 1253 | ui-function / entry | private fun entry(page: PolyGlyphPage.Page) = entries.getOrPut(page) { |
| 1259 | ui-function / page | fun page(page: PolyGlyphPage.Page): AbstractTextureRef = entry(page).ref |
| 1261 | ui-function / uploadDirty | fun uploadDirty() { |
| 1287 | ui-function / get | fun get(bitmap: PolyBitmap): AbstractTextureRef { |
| 1321 | ui-function / touch | fun touch(bitmaps: Set<PolyBitmap>) { |
| 1325 | ui-function / endFrame | fun endFrame() { |

### 26.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/ComposeSceneContextImpl.kt

`file-b9a957eedb337ac31ecf`；状态 **pending**；领域 ui-support；组合 26.2/fabric, 26.2/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 30 | ui-function / requestInputMode | override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard |
| 39 | ui-function / isKeyDown | private fun isKeyDown(key: Int): Boolean { |
| 45 | keybind / isCtrlPressed | isCtrlPressed = isKeyDown(InputConstants.KEY_LCONTROL) \|\| isKeyDown(InputConstants.KEY_RCONTROL), |
| 46 | keybind / isShiftPressed | isShiftPressed = isKeyDown(InputConstants.KEY_LSHIFT) \|\| isKeyDown(InputConstants.KEY_RSHIFT), |
| 47 | keybind / isAltPressed | isAltPressed = isKeyDown(InputConstants.KEY_LALT) \|\| isKeyDown(InputConstants.KEY_RALT), |
| 51 | keybind / isMetaPressed | isMetaPressed = isKeyDown(Platform.compatibility().keys().keyLeftSuper) \|\| isKeyDown(Platform.compatibility().keys().keyRightSuper) |
| 76 | ui-function / setPointerIcon | override fun setPointerIcon(pointerIcon: PointerIcon) { |
| 83 | ui-function / resetPointerIcon | fun resetPointerIcon() { |
| 87 | ui-function / applyPointerIcon | private fun applyPointerIcon(pointerIcon: PointerIcon) { |
| 96 | ui-function / requestFocus | override fun requestFocus(): Boolean { |
| 101 | ui-function / allowCursorChanges | private fun allowCursorChanges(): Boolean { |
| 122 | ui-function / startInputMethod | override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing { |
| 134 | ui-function / sync | fun sync() { |
| 147 | ui-function / close | fun close() { |
| 157 | ui-function / resetPointerIcon | fun resetPointerIcon() { |
| 161 | ui-function / updateContainerSize | fun updateContainerSize(width: Int, height: Int) { |

### 26.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/ComposeScreen.kt

`file-94fedba4079882bc1bf1`；状态 **pending**；领域 ui-support；组合 26.2/fabric, 26.2/neoforge。

对齐背景模糊开关、强度、HUD 穿透、resize 与关屏后的 GPU/画面恢复。 定位类型: blur 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 66 | ui-function / getText | override fun getText(): AnnotatedString? { |
| 75 | ui-function / setText | override fun setText(annotatedString: AnnotatedString) { |
| 82 | ui-function / hasText | override fun hasText(): Boolean { |
| 93 | ui-function / getClipEntry | override suspend fun getClipEntry(): ClipEntry? { |
| 99 | ui-function / setClipEntry | override suspend fun setClipEntry(clipEntry: ClipEntry?) { |
| 112 | route / ComposeScreen | abstract class ComposeScreen( |
| 122 | composable / compose | @Composable |
| 123 | ui-function / compose | abstract fun compose() |
| 146 | ui-function / liveScene | private fun liveScene(): ComposeScene? { |
| 151 | ui-function / withScene | private inline fun <T> withScene(block: (ComposeScene) -> T): T? { |
| 166 | ui-function / sendKeyEventSafely | private inline fun sendKeyEventSafely(build: () -> androidx.compose.ui.input.key.KeyEvent): Boolean { |
| 177 | ui-function / poisonScene | private fun poisonScene(cause: Throwable) { |
| 196 | ui-function / onSceneRebuilding | protected open fun onSceneRebuilding() {} |
| 198 | ui-function / ensureScene | private fun ensureScene(): ComposeScene? { |
| 214 | ui-function / closeSceneQuietly | private fun closeSceneQuietly() { |
| 239 | ui-function / createScene | private fun createScene(): ComposeScene { |
| 283 | ui-function / init | override fun init() { |
| 312 | ui-function / bindContent | private fun bindContent(): Boolean { |
| 319 | ui-function / setContentOn | private fun setContentOn(scene: ComposeScene) { |
| 334 | ui-function / reportUnavailableAndClose | private fun reportUnavailableAndClose() { |
| 341 | ui-function / closeWithMessage | private fun closeWithMessage(reason: String) { |
| 348 | ui-function / resize | override fun resize( |
| 357 | ui-function / isPauseScreen | override fun isPauseScreen(): Boolean = false |
| 365 | ui-function / extractBackground | override fun extractBackground(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, tickDelta: Float) { |
| 369 | blur / UiBlur.extract | UiBlur.extract(ctx, blurAlpha()) |
| 373 | ui-function / blurAlpha | protected open fun blurAlpha(): Float = 0f |
| 380 | ui-function / disposeScene | private fun disposeScene() { |
| 385 | ui-function / releaseScene | private fun releaseScene() { |
| 393 | ui-function / onClose | override fun onClose() { |
| 398 | ui-function / removed | override fun removed() { |
| 410 | ui-function / extractRenderState | override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, tickDelta: Float) { |
| 473 | ui-function / paint | private fun paint(ctx: RenderContext, nanos: Long) { |
| 490 | ui-function / mouseClicked | override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean { |
| 492 | keybind / KeybindRecordingBus.consumeMouse | if (KeybindRecordingBus.consumeMouse(button, true) \|\| handleMouseClicked(button)) { |
| 502 | ui-function / mouseReleased | override fun mouseReleased(event: MouseButtonEvent): Boolean { |
| 504 | keybind / KeybindRecordingBus.consumeMouse | val recordingConsumed = KeybindRecordingBus.consumeMouse(button, false) |
| 515 | ui-function / mouseScrolled | override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean { |
| 520 | ui-function / handleMouseClicked | protected open fun handleMouseClicked(button: Int): Boolean = false |
| 528 | ui-function / releaseHeldButtons | private fun releaseHeldButtons() { |
| 536 | ui-function / sendMouseButtonEvent | private fun sendMouseButtonEvent(type: PointerEventType, button: Int) { |
| 551 | ui-function / sendScrollEvent | private fun sendScrollEvent(scrollX: Double, scrollY: Double) { |
| 573 | ui-function / isPreciseScroll | private fun isPreciseScroll(scrollX: Double, scrollY: Double, now: Long, macPrecise: Boolean?): Boolean { |
| 585 | ui-function / charTyped | override fun charTyped(event: CharacterEvent): Boolean { |
| 593 | ui-function / ctrlDown | fun Int.ctrlDown() = this and InputConstants.MOD_CONTROL != 0 |
| 594 | ui-function / shiftDown | fun Int.shiftDown() = this and InputConstants.MOD_SHIFT != 0 |
| 595 | ui-function / altDown | fun Int.altDown() = this and InputConstants.MOD_ALT != 0 |
| 596 | ui-function / superDown | fun Int.superDown() = this and InputConstants.MOD_SUPER != 0 |
| 598 | ui-function / handleKeyPressed | protected open fun handleKeyPressed(key: Int, modifiers: Int): Boolean = false |
| 603 | ui-function / keyPressed | override fun keyPressed(event: McKeyEvent): Boolean { |
| 611 | ui-function / keyReleased | override fun keyReleased(event: McKeyEvent): Boolean { |
| 619 | ui-function / dispatchKeyPressed | private fun dispatchKeyPressed(bindingKey: Int, shortcutKey: Int, modifiers: Int): Boolean { |
| 621 | keybind / KeybindRecordingBus.consumeEscape | if (KeybindRecordingBus.consumeEscape()) { |
| 625 | keybind / KeybindRecordingBus.isRecording | if (KeybindRecordingBus.isRecording) { |
| 637 | ui-function / sendKeyPressedEvent | private fun sendKeyPressedEvent(bindingKey: Int, shortcutKey: Int, modifiers: Int): Boolean { |
| 647 | keybind / isCtrlPressed | isCtrlPressed = modifiers.ctrlDown(), |
| 648 | keybind / isShiftPressed | isShiftPressed = modifiers.shiftDown(), |
| 649 | keybind / isAltPressed | isAltPressed = modifiers.altDown(), |
| 650 | keybind / isMetaPressed | isMetaPressed = modifiers.superDown(), |
| 664 | ui-function / sendKeyReleasedEvent | private fun sendKeyReleasedEvent(bindingKey: Int, shortcutKey: Int, modifiers: Int): Boolean { |
| 672 | keybind / isCtrlPressed | isCtrlPressed = modifiers.ctrlDown(), |
| 673 | keybind / isShiftPressed | isShiftPressed = modifiers.shiftDown(), |
| 674 | keybind / isAltPressed | isAltPressed = modifiers.altDown(), |
| 675 | keybind / isMetaPressed | isMetaPressed = modifiers.superDown(), |
| 689 | ui-function / sendCharacterEvent | private fun sendCharacterEvent(char: Char, codePoint: Int, modifiers: Int): Boolean { |
| 701 | ui-function / sendCharKeyEvent | private fun sendCharKeyEvent(char: Char, codePoint: Int, modifiers: Int): Boolean { |
| 707 | keybind / isCtrlPressed | isCtrlPressed = modifiers.ctrlDown(), |
| 708 | keybind / isShiftPressed | isShiftPressed = modifiers.shiftDown(), |
| 709 | keybind / isAltPressed | isAltPressed = modifiers.altDown(), |
| 710 | keybind / isMetaPressed | isMetaPressed = modifiers.superDown(), |
| 724 | ui-function / modifiersToAwt | private fun modifiersToAwt(modifiers: Int): Int { |
| 733 | ui-function / syncSceneMetrics | private fun syncSceneMetrics(): Boolean { |
| 762 | ui-function / surfaceScale | private fun surfaceScale(): Float { |
| 773 | ui-function / pointerPosition | protected fun pointerPosition(): Offset { |
| 794 | ui-function / isWheelStep | fun isWheelStep(value: Double) = abs(value * 120 - round(value * 120)) < 1e-3 |
| 800 | ui-function / sceneDensity | internal fun sceneDensity(): Float { |
| 819 | ui-function / isDispatchNeeded | override fun isDispatchNeeded(context: CoroutineContext): Boolean = |
| 822 | ui-function / dispatch | override fun dispatch(context: CoroutineContext, block: Runnable) { |

### 26.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/GlHost.kt

`file-589beaed0e5a5d328b6e`；状态 **pending**；领域 ui-support；组合 26.2/fabric, 26.2/neoforge。

对齐背景模糊开关、强度、HUD 穿透、resize 与关屏后的 GPU/画面恢复。 定位类型: blur 从实际宿主入口触发，核对完整状态变化、交互、退出及重进后的结果。 定位类型: peripheral-ui 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 67 | ui-function / unavailableReason | fun unavailableReason(): String? { |
| 80 | ui-function / setGpuTexture | fun setGpuTexture(t: GpuTexture?) { |
| 84 | ui-function / setGpuTextureView | fun setGpuTextureView(v: GpuTextureView?) { |
| 90 | ui-function / close | override fun close() { |
| 97 | ui-function / blitHud | fun blitHud(guiGraphics: GuiGraphicsExtractor) { |
| 127 | ui-function / screen | fun screen(dirty: Boolean, blurAlpha: Float, underlay: GuiDraw?, paint: (RenderContext) -> Unit) { |
| 145 | ui-function / drawBeforeBlit | fun drawBeforeBlit() { |
| 150 | ui-function / drawAtPresent | fun drawAtPresent() { |
| 158 | ui-function / draw | private fun draw(fbo: Int) { |
| 164 | blur / GlBlur.release | GlBlur.release() |
| 172 | peripheral-ui / NotificationsManager.count | if (NotificationsManager.count > 0) drawGl(fbo, null) { |
| 179 | ui-function / drawBlur | private fun drawBlur(fbo: Int, alpha: Float) { |
| 185 | blur / GlBlur.draw | GlBlur.draw(fbo, w, h, alpha, client.isPaused) |
| 193 | ui-function / drawGl | private fun drawGl(fbo: Int, cached: GlRenderer?, kept: Boolean = false, draw: (RenderContext) -> Unit): GlRenderer? { |
| 212 | ui-function / maxTextureSize | private fun maxTextureSize(): Int = |
| 217 | ui-function / cachedMaxTextureSize | private fun cachedMaxTextureSize(): Int { |
| 226 | ui-function / viewportFitsTexture | private fun viewportFitsTexture(w: Int, h: Int): Boolean { |
| 245 | ui-function / reuse | private fun reuse(renderer: GlRenderer?): GlRenderer { |
| 251 | ui-function / drawHudGl | fun drawHudGl(draw: (RenderContext, RenderContext?) -> Unit) { |
| 270 | ui-function / resolveHudTarget | private fun resolveHudTarget(): RenderTarget? { |
| 300 | ui-function / destroyHudTarget | private fun destroyHudTarget() { |
| 314 | ui-function / onAllocFailure | private fun onAllocFailure(what: String, w: Int, h: Int, error: Throwable) { |
| 329 | ui-function / recreateSurface | fun recreateSurface(width: Int, height: Int) { |

### 26.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/GlRenderer.kt

`file-efc517c83ad0b2e9529f`；状态 **pending**；领域 ui-support；组合 26.2/fabric, 26.2/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 41 | ui-function / moved | fun moved(at: Int) = Live(at, state, draw, key).also { it.last = last } |
| 43 | ui-function / changed | fun changed() = key == null \|\| key() != last |
| 45 | ui-function / commit | fun commit() { |
| 55 | ui-function / bitmapTexture | override fun bitmapTexture(bitmap: PolyBitmap): Any = GlTextures.bitmap(bitmap) |
| 59 | ui-function / mark | override fun mark(): Any { |
| 64 | ui-function / since | override fun since(mark: Any): Any { |
| 87 | ui-function / valid | override fun valid(captured: Any): Boolean = (captured as Captured).batches.all { GlTextures.alive(it.texture) } |
| 89 | ui-function / append | override fun append(captured: Any) { |
| 97 | ui-function / writeStateKey | override fun writeStateKey() { |
| 102 | ui-function / matches | fun matches(): Boolean = |
| 105 | ui-function / reset | fun reset() { |
| 111 | ui-function / submit | override fun submit(texture: Any, premultiplied: Boolean, nearest: Boolean, verts: FloatArray, colors: IntArray, bounds: FloatArray) { |
| 117 | ui-function / layerMark | override fun layerMark(): Any = intArrayOf(batches.size, lives.size) |
| 119 | ui-function / layer | override fun layer(mark: Any, alpha: Float) { |
| 132 | ui-function / fade | override fun fade(mark: Any, alpha: Float): Boolean { |
| 142 | ui-function / live | override fun live(draw: (PolyRenderer) -> Unit) { |
| 150 | ui-function / mcTextLive | override fun mcTextLive(text: String, x: Float, y: Float, color: () -> Int, shadow: Boolean, scale: Float) { |
| 176 | ui-function / texture | override fun texture(target: RenderTarget, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, alpha: Float) { |
| 186 | ui-function / draw | fun draw(fbo: Int, w: Int, h: Int, topDown: Boolean, clear: Boolean, kept: Boolean = false) { |
| 192 | ui-function / flushPending | private fun flushPending() = flush() |
| 194 | ui-function / replay | private fun replay(lives: List<Live>): List<GlProgram.Segment> = lives.map { l -> |
| 209 | ui-function / take | fun take(renderer: GlRenderer) { |
| 224 | ui-function / idle | private fun idle() { |
| 232 | ui-function / same | private fun same(a: List<GlProgram.Batch>, b: List<GlProgram.Batch>) = a.size == b.size && a.indices.all { a[it] === b[it] } |
| 234 | ui-function / sameLives | private fun sameLives(a: List<Live>, b: List<Live>) = |
| 237 | ui-function / coalesce | private fun coalesce() { |
| 241 | ui-function / emit | fun emit() { |
| 264 | ui-function / draw | fun draw(fbo: Int, w: Int, h: Int, topDown: Boolean, clear: Boolean, vararg layers: Layer) { |
| 292 | ui-function / stale | private fun stale(): Boolean = (live != null && changed()) \|\| batches.any { (it.texture as? Group)?.stale() == true } |
| 294 | ui-function / render | private fun render(composite: GlProgram.Batch, transient: ArrayList<Group>?, sink: ArrayList<Group>) { |
| 322 | ui-function / release | fun release() { |
| 336 | ui-function / of | fun of(batches: List<GlProgram.Batch>, live: (() -> List<GlProgram.Segment>)?, changed: () -> Boolean, alpha: Float, screenW: Int, screenH: Int): Group? { |
| 352 | ui-function / prepare | fun prepare(batches: List<GlProgram.Batch>, transient: ArrayList<Group>?, sink: ArrayList<Group>) { |
| 356 | ui-function / endFrame | fun endFrame() { |
| 361 | ui-function / recycle | private fun recycle(t: IntArray) { |
| 365 | ui-function / acquire | private fun acquire(w: Int, h: Int): IntArray { |
| 381 | ui-function / delete | private fun delete(t: IntArray) { |
| 392 | ui-function / segments | fun segments(lives: List<Live>): List<GlProgram.Segment> { |
| 397 | ui-function / drawBatches | fun drawBatches(fbo: Int, w: Int, h: Int, topDown: Boolean, clear: Boolean, batches: List<GlProgram.Batch>, segments: List<GlProgram.Segment>, kept: Boolean, arena: Boolean) { |
| 426 | ui-function / sameKey | fun sameKey(o: Batch) = texture === o.texture && premultiplied == o.premultiplied && nearest == o.nearest |
| 428 | ui-function / faded | fun faded(alpha: Float) = Batch(texture, premultiplied, nearest, verts, IntArray(colors.size) { i -> |
| 444 | ui-function / merge | fun merge(list: List<Batch>): Batch { |
| 493 | ui-function / init | private fun init() { |
| 520 | ui-function / shader | private fun shader(type: Int, src: String): Int { |
| 528 | ui-function / invalidate | fun invalidate(b: Batch) { |
| 534 | ui-function / draw | fun draw(batches: List<Batch>, w: Int, h: Int, topDown: Boolean, live: List<Segment> = emptyList(), kept: Boolean = false, ox: Float = 0f, oy: Float = 0f) { |
| 578 | ui-function / drawArena | fun drawArena(batches: List<Batch>, w: Int, h: Int, topDown: Boolean, live: List<Segment>) { |
| 642 | ui-function / use | fun use(id: Int) { |
| 673 | ui-function / attribPointers | private fun attribPointers() { |
| 679 | ui-function / setupState | private fun setupState(w: Int, h: Int, topDown: Boolean, ox: Float = 0f, oy: Float = 0f) { |
| 696 | ui-function / drawBatch | private fun drawBatch(b: Batch, first: Int) { |
| 712 | ui-function / filter | private fun filter(mode: Int) { |
| 717 | ui-function / upload | private fun upload(at: Int, batches: List<Batch>): Int { |
| 751 | ui-function / ensureIndices | private fun ensureIndices(quads: Int) { |
| 799 | ui-function / bitmap | fun bitmap(bitmap: PolyBitmap): Tex = bitmaps.getOrPut(bitmap) { |
| 804 | ui-function / external | fun external(id: Int): Tex = external.getOrPut(id) { Tex(null, id, 0) } |
| 806 | ui-function / alive | fun alive(key: Any): Boolean = key !is Tex \|\| key.bitmap == null \|\| bitmaps[key.bitmap] === key |
| 808 | ui-function / id | fun id(key: Any): Int = when (key) { |
| 819 | ui-function / glyphPage | private fun glyphPage(page: PolyGlyphPage.Page): Int { |
| 836 | ui-function / upload | private fun upload(bitmap: PolyBitmap): Int { |
| 845 | ui-function / unpackDefaults | private fun unpackDefaults(rowLength: Int) { |
| 853 | ui-function / newTexture | private fun newTexture(): Int { |
| 863 | ui-function / genTexture | private fun genTexture(): Int { |
| 867 | ui-function / bind | private fun bind(id: Int) { |
| 871 | ui-function / texParameter | private fun texParameter(name: Int, value: Int) { |
| 875 | ui-function / pixelStore | private fun pixelStore(name: Int, value: Int) { |
| 879 | ui-function / texImage | private fun texImage(w: Int, h: Int, px: IntBuffer) { |
| 883 | ui-function / texSubImage | private fun texSubImage(y: Int, w: Int, h: Int, px: IntBuffer) { |
| 887 | ui-function / deleteTexture | private fun deleteTexture(id: Int) { |
| 891 | ui-function / endFrame | fun endFrame() { |

### 26.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/MacScrollEvent.kt

`file-253a191e5937d9894d7c`；状态 **pending**；领域 ui-support；组合 26.2/fabric, 26.2/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 18 | ui-function / readScroll | fun readScroll(): Boolean? = try { |

### 26.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/McFontService.kt

`file-b38c34c1c0123532f4b8`；状态 **pending**；领域 font-language；组合 26.2/fabric, 26.2/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 26 | font-language / McFontService | object McFontService { |
| 29 | ui-function / init | fun init() { |
| 40 | ui-function / installBundledFonts | private fun installBundledFonts() { |
| 41 | font-language / McFontService | val loader = McFontService::class.java.classLoader |
| 43 | font-language / UiFontFiles.DEFAULT_REGULAR | "assets/oneconfig/fonts/minecraft/Minecraft-Regular.otf" to UiFontFiles.DEFAULT_REGULAR, |
| 44 | font-language / UiFontFiles.DEFAULT_BOLD | "assets/oneconfig/fonts/minecraft/Minecraft-Bold.otf" to UiFontFiles.DEFAULT_BOLD, |
| 56 | ui-function / installHostFonts | private fun installHostFonts() { |
| 57 | font-language / UiFontFiles.readRegular | val regular = UiFontFiles.readRegular() |
| 64 | font-language / UiFontFiles.REGULAR | FontManager.replace(UiFontFiles.REGULAR, regular) |
| 65 | font-language / UiFontFiles.readBold | val bold = UiFontFiles.readBold() ?: regular |
| 67 | font-language / UiFontFiles.BOLD | FontManager.replace(UiFontFiles.BOLD, bold) |
| 70 | font-language / UiFontFiles.BOLD | FontManager.replace(UiFontFiles.BOLD, regular) |
| 72 | font-language / UiFontFiles.REGULAR | FontManager.setDefault(UiFontFiles.REGULAR) |
| 78 | font-language / UiFontFiles.readFallback | val fallback = UiFontFiles.readFallback() |
| 81 | font-language / UiFontFiles.FALLBACK | FontManager.replace(UiFontFiles.FALLBACK, fallback) |
| 92 | ui-function / installEngines | fun installEngines() { |

### 26.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/McResourceFont.kt

`file-773d51932e05e6785e4e`；状态 **pending**；领域 font-language；组合 26.2/fabric, 26.2/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 28 | font-language / McResourceFont | internal object McResourceFont { |
| 40 | ui-function / glyph | fun glyph(cp: Int): McGlyph? = glyphs[cp] ?: unihex[cp]?.let { u -> baked.getOrPut(cp) { bake(u) } } |
| 47 | ui-function / init | fun init() { |
| 55 | ui-function / reload | private fun reload() { |
| 74 | ui-function / fontOptions | private fun fontOptions(): Int { |
| 82 | ui-function / load | private fun load(rm: ResourceManager, options: Int): Font? { |
| 88 | ui-function / unclaimed | fun unclaimed(cp: Int) = cp !in glyphs && cp !in unihex |
| 106 | ui-function / collectProviders | private fun collectProviders(rm: ResourceManager, ref: String, out: MutableList<JsonObject>, visited: MutableSet<String>, options: Int) { |
| 122 | ui-function / filterPasses | private fun filterPasses(provider: JsonObject, options: Int): Boolean { |
| 129 | ui-function / loadBitmap | private fun loadBitmap(rm: ResourceManager, provider: JsonObject): Map<Int, McGlyph> { |
| 156 | ui-function / loadUnihex | private fun loadUnihex(rm: ResourceManager, provider: JsonObject): Map<Int, Unihex> { |
| 181 | ui-function / parseHex | private fun parseHex(line: String, overrides: List<IntArray>, out: MutableMap<Int, Unihex>): Boolean { |
| 209 | ui-function / bake | private fun bake(u: Unihex): McGlyph { |
| 221 | ui-function / read | private fun read(rm: ResourceManager, ref: String, folder: String): ByteArray? = |
| 224 | ui-function / id | private fun id(ref: String, folder: String, suffix: String = "") = |

### 26.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/MinecraftKeyboardAdapter.kt

`file-b18da7ce1a7575d64915`；状态 **pending**；领域 ui-support；组合 26.2/fabric, 26.2/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 13 | ui-function / keyLocation | fun keyLocation(bindingKey: Int): Int  = when (bindingKey) { |
| 20 | ui-function / toAwtKeyCode | fun toAwtKeyCode(shortcutKey: Int): Int = when (shortcutKey) { |

### 26.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/UiBlur.kt

`file-2e9dcf4982209bc7a5de`；状态 **pending**；领域 blur；组合 26.2/fabric, 26.2/neoforge。

对齐背景模糊开关、强度、HUD 穿透、resize 与关屏后的 GPU/画面恢复。 定位类型: blur 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 21 | blur / UiBlur | object UiBlur { |
| 52 | ui-function / extract | fun extract(ctx: GuiGraphicsExtractor, alpha: Float) { |
| 69 | ui-function / process | fun process(): Boolean { |
| 99 | ui-function / downInfo | private fun downInfo(mainW: Int, mainH: Int, w: Int, h: Int): GpuBuffer { |
| 110 | ui-function / info | private fun info(outW: Int, outH: Int, inW: Int, inH: Int): GpuBuffer = MemoryStack.stackPush().use { stack -> |
| 116 | ui-function / draw | private fun draw(pass: PostPass, input: GpuTextureView, output: RenderTarget, info: GpuBuffer) { |

### 26.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/impls/HudEditorUIScreen.kt

`file-617c2d0a51ccc31cc7b6`；状态 **pending**；领域 hud-editor；组合 26.2/fabric, 26.2/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 36 | route / HudEditorUIScreen | class HudEditorUIScreen private constructor() : ComposeScreen() { |
| 41 | ui-function / open | fun open(fromOneConfig: Boolean): HudEditorUIScreen { |
| 56 | ui-function / beginClose | private fun beginClose() { |
| 61 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLOSE) |
| 61 | sound / UiSoundEvent.CLOSE | UiSounds.play(UiSoundEvent.CLOSE) |
| 62 | sound / UiSounds.releaseAmbience | UiSounds.releaseAmbience() |
| 62 | sound / releaseAmbience | UiSounds.releaseAmbience() |
| 65 | ui-function / cancelClose | private fun cancelClose(): Boolean { |
| 69 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.OPEN) |
| 69 | sound / UiSoundEvent.OPEN | UiSounds.play(UiSoundEvent.OPEN) |
| 70 | sound / UiSounds.acquireAmbience | UiSounds.acquireAmbience() |
| 70 | sound / acquireAmbience | UiSounds.acquireAmbience() |
| 78 | ui-function / returnToOneConfig | private fun returnToOneConfig() { |
| 86 | ui-function / init | override fun init() { |
| 95 | sound / UiSounds.acquireAmbience | UiSounds.acquireAmbience() |
| 95 | sound / acquireAmbience | UiSounds.acquireAmbience() |
| 101 | ui-function / removed | override fun removed() { |
| 107 | sound / UiSounds.releaseAmbience | UiSounds.releaseAmbience() |
| 107 | sound / releaseAmbience | UiSounds.releaseAmbience() |
| 113 | ui-function / isPauseScreen | override fun isPauseScreen(): Boolean = OneConfigConfig.pauseGame |
| 115 | ui-function / handleOneConfigKeybind | private fun handleOneConfigKeybind(): Boolean { |
| 127 | ui-function / handleKeyPressed | override fun handleKeyPressed(key: Int, modifiers: Int): Boolean { |
| 136 | keybind / KeybindRecordingBus.isRecording | if (toggleKey != null && key == toggleKey && !KeybindRecordingBus.isRecording) { |
| 142 | ui-function / handleMouseClicked | override fun handleMouseClicked(button: Int): Boolean { |
| 143 | keybind / KeybindRecordingBus.isRecording | if (KeybindRecordingBus.isRecording) return false |
| 146 | keybind / KeybindManager.isTriggeredByMouse | KeybindManager.isTriggeredByMouse(OneConfigConfig.oneConfigKeybind, button) |
| 154 | ui-function / extractRenderState | override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, tickDelta: Float) { |
| 174 | composable / compose | @Composable |
| 175 | ui-function / compose | override fun compose() { |
| 196 | animation / RetainedVisibility | RetainedVisibility( |
| 198 | animation / tween | enter = tween(200, easing = EaseOutCubic), |
| 199 | animation / tween | exit = tween(exitMs, easing = EaseIn), |

### 26.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/impls/OneConfigUIScreen.kt

`file-4e460ef92c1fe473154a`；状态 **pending**；领域 shell-navigation；组合 26.2/fabric, 26.2/neoforge。

对齐背景模糊开关、强度、HUD 穿透、resize 与关屏后的 GPU/画面恢复。 定位类型: blur 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 79 | route / OneConfigUIScreen | class OneConfigUIScreen @JvmOverloads constructor( |
| 91 | ui-function / blurAlpha | override fun blurAlpha(): Float { |
| 92 | blur / enableBackgroundBlur | if (!OneConfigConfig.enableBackgroundBlur) return 0f |
| 110 | ui-function / shared | private fun shared(): OneConfigUIScreen = |
| 114 | ui-function / forRoute | fun forRoute(route: Any?): OneConfigUIScreen = shared().also { |
| 120 | ui-function / open | fun open(): OneConfigUIScreen = shared().also { |
| 127 | ui-function / resume | fun resume(): OneConfigUIScreen = shared().also { |
| 141 | ui-function / scheduleSave | private fun scheduleSave() { |
| 154 | route / OpeningRoute | private data class OpeningRoute(val route: Any, val restored: Boolean = false) |
| 156 | ui-function / resolveOpeningRoute | private fun resolveOpeningRoute(): OpeningRoute { |
| 176 | ui-function / stopWarmUp | fun stopWarmUp() { |
| 189 | ui-function / dropPrerasteredLater | private fun dropPrerasteredLater() { |
| 196 | ui-function / prepareWarmUp | fun prepareWarmUp(): Runnable? { |
| 211 | ui-function / warmUp | private fun warmUp(w: Int, h: Int, density: Float, surface: Float) { |
| 221 | ui-function / warmFrames | private fun warmFrames(w: Int, h: Int, density: Float, surface: Float) { |
| 226 | ui-function / isDispatchNeeded | override fun isDispatchNeeded(context: CoroutineContext) = Thread.currentThread() !== thread |
| 227 | ui-function / dispatch | override fun dispatch(context: CoroutineContext, block: Runnable) { |
| 231 | ui-function / drain | fun drain() { |
| 291 | ui-function / openLastSession | fun openLastSession() { |
| 302 | ui-function / beginClose | private fun beginClose() { |
| 307 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLOSE) |
| 307 | sound / UiSoundEvent.CLOSE | UiSounds.play(UiSoundEvent.CLOSE) |
| 308 | sound / UiSounds.releaseAmbience | UiSounds.releaseAmbience() |
| 308 | sound / releaseAmbience | UiSounds.releaseAmbience() |
| 311 | ui-function / cancelClose | private fun cancelClose(): Boolean { |
| 322 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.OPEN) |
| 322 | sound / UiSoundEvent.OPEN | UiSounds.play(UiSoundEvent.OPEN) |
| 323 | sound / UiSounds.acquireAmbience | UiSounds.acquireAmbience() |
| 323 | sound / acquireAmbience | UiSounds.acquireAmbience() |
| 339 | ui-function / markClosed | private fun markClosed() { |
| 343 | ui-function / init | override fun init() { |
| 415 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.OPEN) |
| 415 | sound / UiSoundEvent.OPEN | UiSounds.play(UiSoundEvent.OPEN) |
| 416 | sound / UiSounds.acquireAmbience | UiSounds.acquireAmbience() |
| 416 | sound / acquireAmbience | UiSounds.acquireAmbience() |
| 426 | ui-function / onSceneRebuilding | override fun onSceneRebuilding() { |
| 432 | ui-function / removed | override fun removed() { |
| 437 | sound / UiSounds.releaseAmbience | UiSounds.releaseAmbience() |
| 437 | sound / releaseAmbience | UiSounds.releaseAmbience() |
| 443 | ui-function / isPauseScreen | override fun isPauseScreen(): Boolean = OneConfigConfig.pauseGame |
| 445 | ui-function / handleOneConfigKeybind | private fun handleOneConfigKeybind(): Boolean { |
| 457 | ui-function / handleKeyPressed | override fun handleKeyPressed(key: Int, modifiers: Int): Boolean { |
| 466 | keybind / KeybindRecordingBus.isRecording | if (toggleKey != null && key == toggleKey && !KeybindRecordingBus.isRecording) { |
| 473 | ui-function / handleMouseClicked | override fun handleMouseClicked(button: Int): Boolean { |
| 474 | keybind / KeybindRecordingBus.isRecording | if (KeybindRecordingBus.isRecording) return false |
| 477 | keybind / KeybindManager.isTriggeredByMouse | KeybindManager.isTriggeredByMouse(OneConfigConfig.oneConfigKeybind, button) |
| 484 | keybind / KeybindManager.hasTriggeredMouseBind | if (KeybindManager.hasTriggeredMouseBind(button)) return true |
| 485 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLICK) |
| 485 | sound / UiSoundEvent.CLICK | UiSounds.play(UiSoundEvent.CLICK) |
| 490 | keybind / KeybindManager.hasTriggeredMouseBind | if (KeybindManager.hasTriggeredMouseBind(button)) return true |
| 491 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLICK) |
| 491 | sound / UiSoundEvent.CLICK | UiSounds.play(UiSoundEvent.CLICK) |
| 501 | ui-function / extractRenderState | override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, tickDelta: Float) { |
| 523 | ui-function / easeOutExpo | private fun easeOutExpo(progress: Float): Float { |
| 531 | composable / compose | @Composable |
| 532 | ui-function / compose | override fun compose() { |
| 564 | ui-function / submit | override fun submit(texture: Any, premultiplied: Boolean, nearest: Boolean, verts: FloatArray, colors: IntArray, bounds: FloatArray) {} |
| 566 | ui-function / bitmapTexture | override fun bitmapTexture(bitmap: PolyBitmap): Any = bitmap |
| 568 | ui-function / live | override fun live(draw: (PolyRenderer) -> Unit) = draw(this) |

### 26.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/opengl/StoredGLState.kt

`file-1e2fe368be3b5dd53807`；状态 **pending**；领域 ui-support；组合 26.2/fabric, 26.2/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 10 | ui-function / resyncTextureBindCache | fun resyncTextureBindCache() { |
| 21 | ui-function / capture | fun capture(): StoredGLState { |
| 102 | ui-function / restore | fun restore(): StoredGLState { |
| 195 | ui-function / forceToggle | private fun forceToggle(enabled: Boolean, enable: () -> Unit, disable: () -> Unit) { |
| 205 | ui-function / forceDepthFunc | private fun forceDepthFunc(func: Int) { |
| 211 | ui-function / forceDepthMask | private fun forceDepthMask(mask: Boolean) { |

### 26.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/CompatOverlayRenderer.kt

`file-482d1d37b7b368744e99`；状态 **pending**；领域 hud-editor；组合 26.2/fabric, 26.2/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 14 | ui-function / register | fun register(hook: (GuiGraphicsExtractor) -> Unit) { |
| 19 | ui-function / hasHooks | fun hasHooks(): Boolean = hooks.isNotEmpty() |
| 22 | ui-function / oneConfigScreenOpen | fun oneConfigScreenOpen(): Boolean = HudManager.isEditing |
| 25 | ui-function / render | fun render(ctx: GuiGraphicsExtractor) { |

### 26.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/LegacyHudRenderer.kt

`file-cf1f3d3a366f7a7d4020`；状态 **pending**；领域 hud-editor；组合 26.2/fabric, 26.2/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 32 | ui-function / reportFailure | private fun reportFailure(hud: LegacyHud, e: Throwable) { |
| 47 | ui-function / renderLive | fun renderLive(graphics: GuiGraphicsExtractor) { |
| 52 | ui-function / renderLiveHuds | private fun renderLiveHuds(graphics: GuiGraphicsExtractor) { |

### 26.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/keybind/MinecraftKeybindProvider.kt

`file-87fc79443054671844cf`；状态 **pending**；领域 keybind；组合 26.2/fabric, 26.2/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 22 | keybind / OneConfigKeybind | private val properties = IdentityHashMap<KeyMapping, Property<OneConfigKeybind>>() |
| 37 | ui-function / groups | override fun groups(): List<KeybindGroup> { |
| 77 | ui-function / managedMappings | fun managedMappings(): List<KeyMapping> { |
| 82 | keybind / OneConfigKeybind | private fun propertyFor(mapping: KeyMapping): Property<OneConfigKeybind> { |
| 82 | ui-function / propertyFor | private fun propertyFor(mapping: KeyMapping): Property<OneConfigKeybind> { |
| 89 | keybind / OneConfigKeybind | OneConfigKeybind::class.java, |
| 99 | ui-function / ownerMod | private fun ownerMod(mapping: KeyMapping, modsById: Map<String, ModInfo>): ModInfo? { |
| 105 | ui-function / ownerCandidates | private fun ownerCandidates(keyName: String): Sequence<String> = sequence { |
| 117 | ui-function / modIcon | private fun modIcon(owner: ModInfo?): String? = |
| 124 | ui-function / minecraftIcon | private fun minecraftIcon(): String { |
| 132 | ui-function / extractMinecraftIcon | private fun extractMinecraftIcon(): String? = runCatching { |
| 148 | ui-function / groupTitle | private fun groupTitle(category: String, owner: ModInfo?): String { |
| 154 | ui-function / sanitizeId | private fun sanitizeId(value: String): String { |
| 161 | keybind / OneConfigKeybind | private fun KeyMapping.toOneConfigKeybind(): OneConfigKeybind { |
| 161 | ui-function / toOneConfigKeybind | private fun KeyMapping.toOneConfigKeybind(): OneConfigKeybind { |
| 166 | keybind / Key.toOneConfigKeybind | private fun InputConstants.Key.toOneConfigKeybind(): OneConfigKeybind { |
| 166 | keybind / OneConfigKeybind | private fun InputConstants.Key.toOneConfigKeybind(): OneConfigKeybind { |
| 166 | ui-function / toOneConfigKeybind | private fun InputConstants.Key.toOneConfigKeybind(): OneConfigKeybind { |
| 171 | keybind / OneConfigKeybind | OneConfigKeybind(intArrayOf(value), null, KeyModifiers.NONE, 0L) { true } |
| 174 | keybind / OneConfigKeybind | OneConfigKeybind(null, intArrayOf(value), KeyModifiers.NONE, 0L) { true } |
| 175 | keybind / OneConfigKeybind | else -> OneConfigKeybind(null, null, KeyModifiers.NONE, 0L) { true } |
| 179 | keybind / OneConfigKeybind | private fun KeyMapping.applyOneConfigKeybind(keybind: OneConfigKeybind?) { |
| 179 | ui-function / applyOneConfigKeybind | private fun KeyMapping.applyOneConfigKeybind(keybind: OneConfigKeybind?) { |
| 193 | ui-function / categoryLabel | private fun categoryLabel(mapping: KeyMapping): String { |
| 199 | ui-function / translate | private fun translate(key: String): String { |
| 211 | ui-function / isOneConfigMirror | private fun isOneConfigMirror(@Suppress("UNUSED_PARAMETER") mapping: KeyMapping): Boolean = false |

### 26.2/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/sound/McUiSoundService.kt

`file-4a718212d29a4dfe803b`；状态 **pending**；领域 sound；组合 26.2/fabric, 26.2/neoforge。

对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 19 | sound / McUiSoundService | class McUiSoundService : UiSoundService { |
| 19 | sound / UiSoundService | class McUiSoundService : UiSoundService { |
| 28 | sound / UiSoundTheme | private val ambienceShuffle = mutableMapOf<UiSoundTheme, MutableList<String>>() |
| 29 | sound / UiSoundTheme | private val lastAmbienceSoundId = mutableMapOf<UiSoundTheme, String>() |
| 33 | sound / UiSoundEvent | override fun play(event: UiSoundEvent, theme: UiSoundTheme, volume: Float) { |
| 33 | sound / UiSoundTheme | override fun play(event: UiSoundEvent, theme: UiSoundTheme, volume: Float) { |
| 33 | ui-function / play | override fun play(event: UiSoundEvent, theme: UiSoundTheme, volume: Float) { |
| 37 | sound / UiSoundTheme.MODERN | UiSoundTheme.MODERN -> playModern(event, volume) |
| 38 | sound / UiSoundTheme.MINECRAFT | UiSoundTheme.MINECRAFT -> playMinecraft(event, volume) |
| 44 | sound / UiSoundEvent | private fun playModern(event: UiSoundEvent, volume: Float) { |
| 44 | ui-function / playModern | private fun playModern(event: UiSoundEvent, volume: Float) { |
| 46 | sound / UiSoundEvent.OPEN | UiSoundEvent.OPEN -> playModernSound("ui.open", 1f, volume) |
| 47 | sound / UiSoundEvent.CLOSE | UiSoundEvent.CLOSE -> playModernSound("ui.close", 1f, volume) |
| 48 | sound / UiSoundEvent.CLICK | UiSoundEvent.CLICK -> playModernSound("ui.click", 1f, volume) |
| 49 | sound / UiSoundEvent.SLIDER_TICK | UiSoundEvent.SLIDER_TICK -> playModernSound("ui.slider", 1f, volume) |
| 50 | sound / UiSoundEvent.HUD_SELECT | UiSoundEvent.HUD_SELECT -> playModernSound("ui.click", 1.0f, volume) |
| 51 | sound / UiSoundEvent.HUD_DRAG_START | UiSoundEvent.HUD_DRAG_START -> playModernSound("ui.click", 0.9f, volume) |
| 52 | sound / UiSoundEvent.HUD_DRAG_END | UiSoundEvent.HUD_DRAG_END -> playModernSound("ui.click", 1.1f, volume) |
| 53 | sound / UiSoundEvent.HUD_RESIZE_END | UiSoundEvent.HUD_RESIZE_END -> playModernSound("ui.click", 1.0f, volume) |
| 58 | ui-function / playModernSound | private fun playModernSound(id: String, pitch: Float, volume: Float) { |
| 63 | sound / UiSoundEvent | private fun playMinecraft(event: UiSoundEvent, volume: Float) { |
| 63 | ui-function / playMinecraft | private fun playMinecraft(event: UiSoundEvent, volume: Float) { |
| 65 | sound / UiSoundEvent.CLICK | UiSoundEvent.CLICK -> playVanilla(SoundEvents.UI_BUTTON_CLICK, 1f, 0.5f * volume) |
| 66 | sound / UiSoundEvent.OPEN | UiSoundEvent.OPEN -> playModernSound("ui.open", 1f, volume) |
| 67 | sound / UiSoundEvent.CLOSE | UiSoundEvent.CLOSE -> playModernSound("ui.close", 1f, volume) |
| 68 | sound / UiSoundEvent.SLIDER_TICK | UiSoundEvent.SLIDER_TICK -> { |
| 73 | sound / UiSoundEvent.HUD_SELECT | UiSoundEvent.HUD_SELECT -> playVanilla(SoundEvents.UI_BUTTON_CLICK, 1f, 0.3f * volume) |
| 74 | sound / UiSoundEvent.HUD_DRAG_END | UiSoundEvent.HUD_DRAG_END -> playVanilla(SoundEvents.UI_BUTTON_CLICK, 1f, 0.3f * volume) |
| 75 | sound / UiSoundEvent.HUD_RESIZE_END | UiSoundEvent.HUD_RESIZE_END -> playVanilla(SoundEvents.UI_BUTTON_CLICK, 1f, 0.3f * volume) |
| 80 | ui-function / playVanilla | private fun playVanilla(event: SoundEvent, pitch: Float, volume: Float) { |
| 85 | ui-function / playVanilla | private fun playVanilla(holder: Holder<SoundEvent>, pitch: Float, volume: Float) = |
| 88 | sound / startAmbience | override fun startAmbience(theme: UiSoundTheme, volume: Float) { |
| 88 | sound / UiSoundTheme | override fun startAmbience(theme: UiSoundTheme, volume: Float) { |
| 88 | ui-function / startAmbience | override fun startAmbience(theme: UiSoundTheme, volume: Float) { |
| 109 | sound / stopAmbience | override fun stopAmbience(@Suppress("UNUSED_PARAMETER") setDucking: Boolean) { |
| 109 | ui-function / stopAmbience | override fun stopAmbience(@Suppress("UNUSED_PARAMETER") setDucking: Boolean) { |
| 118 | sound / UiSoundTheme | private fun ambienceVolumeFor(theme: UiSoundTheme, volume: Float): Float = when (theme) { |
| 118 | ui-function / ambienceVolumeFor | private fun ambienceVolumeFor(theme: UiSoundTheme, volume: Float): Float = when (theme) { |
| 119 | sound / UiSoundTheme.MODERN | UiSoundTheme.MODERN -> 0.5f * volume |
| 120 | sound / UiSoundTheme.MINECRAFT | UiSoundTheme.MINECRAFT -> 0.08f * volume |
| 123 | sound / UiSoundTheme | private fun ambienceSoundIds(theme: UiSoundTheme): List<String> = when (theme) { |
| 123 | ui-function / ambienceSoundIds | private fun ambienceSoundIds(theme: UiSoundTheme): List<String> = when (theme) { |
| 124 | sound / UiSoundTheme.MODERN | UiSoundTheme.MODERN -> listOf("ui.pad_loop") |
| 125 | sound / UiSoundTheme.MINECRAFT | UiSoundTheme.MINECRAFT -> listOf( |
| 133 | sound / UiSoundTheme | private fun playAmbienceInstance(theme: UiSoundTheme, targetVolume: Float): FadingLoopSoundInstance { |
| 133 | ui-function / playAmbienceInstance | private fun playAmbienceInstance(theme: UiSoundTheme, targetVolume: Float): FadingLoopSoundInstance { |
| 144 | sound / UiSoundTheme | private fun nextAmbienceSoundId(theme: UiSoundTheme, ids: List<String>): String { |
| 144 | ui-function / nextAmbienceSoundId | private fun nextAmbienceSoundId(theme: UiSoundTheme, ids: List<String>): String { |
| 165 | ui-function / driveAmbienceLoop | private fun driveAmbienceLoop() { |
| 196 | sound / UiSoundTheme | val theme: UiSoundTheme, |
| 215 | ui-function / beginFadeOut | fun beginFadeOut() { |
| 219 | ui-function / cancelFadeOut | fun cancelFadeOut() { |
| 224 | ui-function / updateTargetVolume | fun updateTargetVolume(newTarget: Float) { |
| 229 | ui-function / tick | override fun tick() { |

### 26.3/fabric/src/main/java/org/polyfrost/oneconfig/internal/OneConfig.java

`file-be77d37ce012e9626515`；状态 **pending**；领域 ui-support；组合 26.3/fabric。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 236 | search-provider / SearchCorpus.INSTANCE | EventManager.register(ResourceFinishedLoading.class, e -> SearchCorpus.INSTANCE.init()); |
| 335 | font-language / McFontService.INSTANCE | McFontService.INSTANCE.init(); |

### 26.3/fabric/src/main/java/org/polyfrost/oneconfig/internal/OneConfigPreLaunch.java

`file-f50d6f9211b08aa0093f`；状态 **pending**；领域 ui-support；组合 26.3/fabric。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 25 | font-language / McFontService.installEngines | McFontService.installEngines(); |

### 26.3/neoforge/src/main/java/org/polyfrost/oneconfig/internal/OneConfig.java

`file-952e2dce32d1e1080c60`；状态 **pending**；领域 ui-support；组合 26.3/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 235 | search-provider / SearchCorpus.INSTANCE | EventManager.register(ResourceFinishedLoading.class, e -> SearchCorpus.INSTANCE.init()); |
| 345 | font-language / McFontService.INSTANCE | McFontService.INSTANCE.init(); |

### 26.3/neoforge/src/main/java/org/polyfrost/oneconfig/internal/OneConfigPreLaunch.java

`file-39254b51b967ebbafb7f`；状态 **pending**；领域 ui-support；组合 26.3/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 22 | font-language / McFontService.installEngines | org.polyfrost.oneconfig.internal.ui.compose.McFontService.installEngines(); |

### 26.3/src/main/java/org/polyfrost/oneconfig/api/platform/v1/internal/I18nPlatformImpl.java

`file-78cc567bac7a008a8674`；状态 **pending**；领域 ui-support；组合 26.3/fabric, 26.3/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 40 | font-language / I18nPlatform | public class I18nPlatformImpl implements I18nPlatform { |
| 53 | font-language / Language.getInstance | return Language.getInstance().has(key); |

### 26.3/src/main/java/org/polyfrost/oneconfig/api/platform/v1/internal/KeysImpl.java

`file-1ff5246ddbd932839236`；状态 **pending**；领域 ui-support；组合 26.3/fabric, 26.3/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 21 | keybind / getKeyA | public int getKeyA() { |
| 26 | keybind / getKeyC | public int getKeyC() { |
| 31 | keybind / getKeyD | public int getKeyD() { |
| 36 | keybind / getKeyE | public int getKeyE() { |
| 41 | keybind / getKeyF | public int getKeyF() { |
| 46 | keybind / getKeyH | public int getKeyH() { |
| 51 | keybind / getKeyL | public int getKeyL() { |
| 56 | keybind / getKeyR | public int getKeyR() { |
| 61 | keybind / getKeyV | public int getKeyV() { |
| 66 | keybind / getKeyX | public int getKeyX() { |
| 71 | keybind / getKeyY | public int getKeyY() { |
| 76 | keybind / getKeyZ | public int getKeyZ() { |
| 81 | keybind / getKeyDelete | public int getKeyDelete() { |
| 91 | keybind / getKeyLeftShift | public int getKeyLeftShift() { |
| 96 | keybind / getKeyRightShift | public int getKeyRightShift() { |
| 101 | keybind / getKeyLeftControl | public int getKeyLeftControl() { |
| 106 | keybind / getKeyRightControl | public int getKeyRightControl() { |
| 111 | keybind / getKeyLeftAlt | public int getKeyLeftAlt() { |
| 116 | keybind / getKeyRightAlt | public int getKeyRightAlt() { |
| 121 | keybind / getKeyLeftSuper | public int getKeyLeftSuper() { |
| 126 | keybind / getKeyRightSuper | public int getKeyRightSuper() { |

### 26.3/src/main/java/org/polyfrost/oneconfig/api/ui/v1/api/internal/TinyFdApiImpl.java

`file-32ca83bb5958a23cf090`；状态 **pending**；领域 ui-support；组合 26.3/fabric, 26.3/neoforge。

核对打开/取消/确认、焦点、边界定位、外点关闭与提交后可见结果。 定位类型: dialog-or-popup

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 128 | dialog-or-popup / Dialog | private static <T> T open(String description, T fallback, Dialog<T> dialog) { |
| 142 | dialog-or-popup / showFileDialog | private List<String> showFileDialog(int type, @Nullable String title, @Nullable String defaultPath, @Nullable String[] filterPatterns, @Nullable String filterDescription, boolean many) { |
| 272 | dialog-or-popup / showFileDialog | firstPath(showFileDialog(SDL_FILEDIALOG_OPENFILE, title, defaultFilePath, filterPatterns, filterDescription, false))); |
| 278 | dialog-or-popup / showFileDialog | firstPath(showFileDialog(SDL_FILEDIALOG_SAVEFILE, title, defaultFilePath, filterPatterns, filterDescription, false))); |
| 284 | dialog-or-popup / showFileDialog | showFileDialog(SDL_FILEDIALOG_OPENFILE, title, defaultFilePath, filterPatterns, filterDescription, true) |
| 292 | dialog-or-popup / showFileDialog | firstPath(showFileDialog(SDL_FILEDIALOG_OPENFOLDER, title, defaultFolderPath, null, null, false))); |
| 348 | dialog-or-popup / Dialog | private interface Dialog<T> { |

### 26.3/src/main/java/org/polyfrost/oneconfig/api/ui/v1/keybind/internal/MinecraftKeybindCodec.java

`file-a2f565504b0eaa24f915`；状态 **pending**；领域 keybind；组合 26.3/fabric, 26.3/neoforge。

该文件支撑 keybind；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 26.3/src/main/java/org/polyfrost/oneconfig/internal/mixin/render/Mixin_UiBlur.java

`file-38ade660201c0d35836a`；状态 **pending**；领域 blur；组合 26.3/fabric, 26.3/neoforge。

对齐背景模糊开关、强度、HUD 穿透、resize 与关屏后的 GPU/画面恢复。 定位类型: blur

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 14 | blur / UiBlur.process | if (UiBlur.process()) ci.cancel(); |

### 26.3/src/main/java/org/polyfrost/oneconfig/internal/ui/PlayerHeadBlit.java

`file-2382de5020db12eb7915`；状态 **pending**；领域 ui-support；组合 26.3/fabric, 26.3/neoforge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 26.3/src/main/java/org/polyfrost/oneconfig/internal/ui/PlayerHeadSkinAccess.java

`file-4ff0d5920c5eda0d43e9`；状态 **pending**；领域 ui-support；组合 26.3/fabric, 26.3/neoforge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 26.3/src/main/kotlin/org/polyfrost/oneconfig/api/hud/v1/LegacyHud.kt

`file-af022f88d4fe3f7a57fa`；状态 **pending**；领域 hud-editor；组合 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 14 | ui-function / render | abstract fun render(mcCtx: GuiGraphicsExtractor) |
| 18 | ui-function / minimumSize | override fun minimumSize(): Pair<Float, Float> = width to height |
| 20 | composable / Content | @Composable |
| 21 | ui-function / Content | override fun Content() {} |

### 26.3/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/GuiProjection.kt

`file-cd8c4e9d22af4ef7451c`；状态 **pending**；领域 ui-support；组合 26.3/fabric, 26.3/neoforge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### 26.3/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/OffscreenTarget.kt

`file-ce84fdaa1faf50ede6fa`；状态 **pending**；领域 ui-support；组合 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 28 | ui-function / resolve | fun resolve(w: Int, h: Int): Boolean { |
| 51 | ui-function / clear | fun clear() { |
| 58 | ui-function / destroy | fun destroy() { |
| 63 | ui-function / dispose | fun dispose() { |
| 73 | ui-function / destroyAll | fun destroyAll() { |
| 86 | ui-function / texture | fun texture(target: RenderTarget, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, alpha: Float) |

### 26.3/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/RenderTargetFbo.kt

`file-b9635a033abad1aa1578`；状态 **pending**；领域 ui-support；组合 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 32 | ui-function / unwrapTexture | private fun unwrapTexture(texture: com.mojang.renderpearl.api.textures.GpuTexture?): GlTexture? { |
| 42 | ui-function / getFboId | fun getFboId(frameBuffer: RenderTarget): Int { |
| 52 | ui-function / getColorTexId | fun getColorTexId(frameBuffer: RenderTarget): Int = |

### 26.3/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/BlazeRenderer.kt

`file-221c54b5ae992ace4983`；状态 **pending**；领域 ui-support；组合 26.3/fabric, 26.3/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 从实际宿主入口触发，核对完整状态变化、交互、退出及重进后的结果。 定位类型: peripheral-ui 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 71 | ui-function / bitmapTexture | override fun bitmapTexture(bitmap: PolyBitmap): Any { |
| 80 | ui-function / mark | override fun mark(): Any { |
| 85 | ui-function / since | override fun since(mark: Any): Any { |
| 92 | ui-function / valid | override fun valid(captured: Any): Boolean { |
| 97 | ui-function / append | override fun append(captured: Any) { |
| 106 | ui-function / writeStateKey | override fun writeStateKey() { |
| 111 | ui-function / matches | fun matches(guiScale: Float): Boolean { |
| 116 | ui-function / reset | fun reset() { |
| 125 | ui-function / layerMark | override fun layerMark(): Any = ops.size |
| 127 | ui-function / layer | override fun layer(mark: Any, alpha: Float) { |
| 135 | ui-function / fade | override fun fade(mark: Any, alpha: Float): Boolean { |
| 150 | ui-function / touchText | private fun touchText(text: String, x: Float, y: Float, scale: Float, shadow: Boolean) { |
| 161 | ui-function / submit | override fun submit(texture: Any, premultiplied: Boolean, nearest: Boolean, verts: FloatArray, colors: IntArray, bounds: FloatArray) { |
| 173 | ui-function / mcText | override fun mcText(text: String, x: Float, y: Float, color: Int, shadow: Boolean, scale: Float) { |
| 185 | font-language / Language.getInstance | else if ('§' in line \|\| Language.getInstance().isDefaultRightToLeft) bakeText(font, seq, m, c, shadow, scissor) |
| 191 | ui-function / mcTextLive | override fun mcTextLive(text: String, x: Float, y: Float, color: () -> Int, shadow: Boolean, scale: Float) { |
| 192 | font-language / Language.getInstance | if (!keepVertices \|\| '§' in text \|\| Language.getInstance().isDefaultRightToLeft) return super.mcTextLive(text, x, y, color, shadow, scale) |
| 208 | ui-function / lineMatrix | private fun lineMatrix(x: Float, y: Float, index: Int, lineHeight: Int, scale: Float): Matrix3x2f = |
| 212 | ui-function / mcTextOutline | override fun mcTextOutline(text: String, x: Float, y: Float, color: Int, outlineColor: Int, scale: Float) { |
| 241 | ui-function / bakeText | private fun bakeText(font: Font, seq: FormattedCharSequence, m: Matrix3x2f, color: Int, shadow: Boolean, scissor: ScreenRectangle?): List<Mesh>? { |
| 252 | ui-function / close | fun close() { |
| 279 | ui-function / addVertex | override fun addVertex(x: Float, y: Float, z: Float) = apply { |
| 283 | ui-function / setUv | override fun setUv(u: Float, v: Float) = apply { verts[n * 4 - 2] = u; verts[n * 4 - 1] = v } |
| 284 | ui-function / setColor | override fun setColor(color: Int) = apply { colors[n - 1] = color } |
| 285 | ui-function / setColor | override fun setColor(r: Int, g: Int, b: Int, a: Int) = setColor((a shl 24) or (r shl 16) or (g shl 8) or b) |
| 286 | ui-function / setUv1 | override fun setUv1(u: Int, v: Int) = this |
| 287 | ui-function / setUv2 | override fun setUv2(u: Int, v: Int) = this |
| 288 | ui-function / setUv3 | override fun setUv3(u: Float, v: Float) = this |
| 289 | ui-function / setNormal | override fun setNormal(x: Float, y: Float, z: Float) = this |
| 290 | ui-function / setLineWidth | override fun setLineWidth(width: Float) = this |
| 293 | ui-function / texture | override fun texture(target: RenderTarget, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, alpha: Float) { |
| 297 | ui-function / texture | fun texture(view: GpuTextureView, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, alpha: Float) { |
| 309 | ui-function / live | override fun live(draw: (PolyRenderer) -> Unit) { |
| 319 | ui-function / faded | fun faded(alpha: Float) = LiveOp(s.copy().also { it.alpha *= alpha }, draw) |
| 321 | ui-function / invoke | override fun invoke(g: GuiGraphicsExtractor) { |
| 334 | ui-function / finish | fun finish(): List<(GuiGraphicsExtractor) -> Unit> { |
| 339 | ui-function / rectGui | private fun rectGui(x0: Float, y0: Float, x1: Float, y1: Float): ScreenRectangle? { |
| 348 | ui-function / invoke | override fun invoke(g: GuiGraphicsExtractor) { |
| 360 | ui-function / shifted | fun shifted(dx: Int, dy: Int) = LiveTextOp(meshes?.map { it.shifted(dx, dy) }, color, alpha, base) { g, c -> shiftedDraw(g, dx, dy) { fallback(it, c) } } |
| 362 | ui-function / faded | fun faded(a: Float) = LiveTextOp(meshes?.map { it.faded(1f) }, color, alpha * a, base, fallback) |
| 364 | ui-function / changed | fun changed() = alphaMul(color(), alpha) != drawn |
| 366 | ui-function / invoke | override fun invoke(g: GuiGraphicsExtractor) { |
| 384 | ui-function / shifted | fun shifted(dx: Int, dy: Int) = BakedTextOp(meshes?.map { it.shifted(dx, dy) }, color) { g, c -> shiftedDraw(g, dx, dy) { draw(it, c) } } |
| 386 | ui-function / faded | fun faded(alpha: Float) = BakedTextOp(meshes?.map { it.faded(alpha) }, alphaMul(color, alpha), draw) |
| 388 | ui-function / invoke | override fun invoke(g: GuiGraphicsExtractor) { |
| 412 | ui-function / shifted | fun shifted(dx: Int, dy: Int) = Mesh( |
| 417 | ui-function / faded | fun faded(alpha: Float): Mesh { |
| 423 | ui-function / mergeable | fun mergeable(o: Mesh): Boolean = |
| 427 | ui-function / overlaps | fun overlaps(b: ScreenRectangle): Boolean = |
| 431 | ui-function / merge | fun merge(list: List<Mesh>): Mesh { |
| 449 | ui-function / buildVertices | override fun buildVertices(consumer: VertexConsumer) { |
| 469 | ui-function / vertex | private fun vertex(consumer: VertexConsumer, x: Float, y: Float, u: Float, v: Float, color: Int) { |
| 474 | ui-function / pipeline | override fun pipeline() = if (gpu != null) KeptMeshes.pipeline(pipeline) else pipeline |
| 475 | ui-function / textureSetup | override fun textureSetup() = textureSetup |
| 476 | ui-function / scissorArea | override fun scissorArea(): ScreenRectangle? = scissor |
| 477 | ui-function / bounds | override fun bounds() = bounds |
| 481 | ui-function / coalesce | fun coalesce(ops: List<(GuiGraphicsExtractor) -> Unit>): List<(GuiGraphicsExtractor) -> Unit> { |
| 486 | ui-function / emit | fun emit() { |
| 492 | ui-function / union | fun union(a: ScreenRectangle, b: ScreenRectangle): ScreenRectangle { |
| 496 | ui-function / place | fun place(m: Mesh) { |
| 537 | ui-function / fadeColor | private fun fadeColor(color: Int, alpha: Float, premultiplied: Boolean): Int { |
| 548 | ui-function / alphaMul | private fun alphaMul(color: Int, alpha: Float): Int { |
| 554 | ui-function / vanillaText | private fun vanillaText(g: GuiGraphicsExtractor, font: Font, seq: FormattedCharSequence, m: Matrix3x2f, c: Int, shadow: Boolean, scissor: ScreenRectangle?) { |
| 563 | ui-function / isTextPipeline | private fun isTextPipeline(p: RenderPipeline) = p === RenderPipelines.GUI_TEXT \|\| p === RenderPipelines.GUI_TEXT_GRAYSCALE |
| 565 | ui-function / visitRenderables | private inline fun visitRenderables(prepared: Font.PreparedText, crossinline accept: (TextRenderable) -> Unit) { |
| 567 | ui-function / acceptRenderable | override fun acceptRenderable(renderable: TextRenderable) = accept(renderable) |
| 586 | ui-function / glyph | private fun glyph(font: Font, c: Char): Glyph { |
| 591 | ui-function / probe | private fun probe(font: Font, c: Char): Glyph { |
| 612 | ui-function / bake | fun bake(font: Font, line: String, m: Matrix3x2f, color: Int, shadow: Boolean, scissor: ScreenRectangle?, keep: Boolean = true): List<BlazeRenderer.Mesh>? { |
| 642 | ui-function / close | fun close() { |
| 665 | ui-function / quad | private fun quad(cap: BlazeRenderer.GlyphCapture, pose: Matrix4f, x: Float, y: Float, g: Glyph, color: Int) { |
| 675 | ui-function / visualOrder | private fun visualOrder(line: String): FormattedCharSequence { |
| 676 | font-language / Language.getInstance | val lang = Language.getInstance() |
| 705 | ui-function / get | fun get(text: String, kx: Float, ky: Float, fx: Float, fy: Float): Outline? { |
| 721 | ui-function / evict | private fun evict() { |
| 733 | ui-function / uncolored | private fun uncolored(seq: FormattedCharSequence) = FormattedCharSequence { sink -> |
| 743 | ui-function / draw | fun draw(g: GuiGraphicsExtractor, pose: Matrix3x2f, x0: Float, y0: Float, color: Int, bounds: ScreenRectangle, scissor: ScreenRectangle?) { |
| 760 | ui-function / release | internal fun release() { |
| 766 | ui-function / render | private fun render() { |
| 781 | ui-function / renderInto | private fun renderInto(mc: Minecraft, state: GuiRenderState, renderer: GuiRenderer, rt: RenderTarget) { |
| 843 | ui-function / acquire | fun acquire(mc: Minecraft): Slot { |
| 853 | ui-function / release | fun release(slot: Slot) { |
| 857 | ui-function / create | private fun create(mc: Minecraft, state: GuiRenderState): GuiRenderer { |
| 865 | ui-function / targetV | private fun targetV(v: Float) = if (topDownTargets) 1f - v else v |
| 885 | ui-function / pipeline | fun pipeline(vanilla: RenderPipeline) = when { |
| 892 | ui-function / capture | fun capture(mesh: BlazeRenderer.Mesh, ptr: Long, len: Int) { |
| 899 | ui-function / kept | private fun kept(element: GuiElementRenderState): BlazeRenderer.Mesh? { |
| 907 | ui-function / added | fun added(draw: Any, element: GuiElementRenderState) { |
| 913 | ui-function / execute | fun execute(draw: Any, pass: RenderPass): Boolean { |
| 942 | ui-function / endFrame | fun endFrame() { |
| 963 | ui-function / markDirty | fun markDirty() { |
| 968 | ui-function / render | fun render(gfx: GuiGraphicsExtractor) { |
| 990 | ui-function / reuse | private fun reuse(renderer: BlazeRenderer?, guiScale: Float): BlazeRenderer { |
| 1003 | ui-function / render | fun render(gfx: GuiGraphicsExtractor) { |
| 1004 | peripheral-ui / NotificationsManager.count | val show = NotificationsManager.count > 0 |
| 1024 | ui-function / render | fun render(gfx: GuiGraphicsExtractor, dirty: Boolean, draw: (RenderContext) -> Unit) { |
| 1039 | ui-function / take | fun take(renderer: BlazeRenderer, copy: Boolean = false) { |
| 1054 | ui-function / idle | fun idle() { |
| 1061 | ui-function / replay | fun replay(gfx: GuiGraphicsExtractor) { |
| 1073 | ui-function / moved | private fun moved(dx: Int, dy: Int) = LayerOp(ops, alpha, guiScale, bounds.shifted(dx, dy)) |
| 1075 | ui-function / faded | fun faded(a: Float) = LayerOp(ops, alpha * a, guiScale, bounds) |
| 1077 | ui-function / stale | private fun stale(): Boolean = ops.any { |
| 1086 | ui-function / invoke | override fun invoke(g: GuiGraphicsExtractor) { |
| 1093 | ui-function / release | fun release() { |
| 1101 | ui-function / render | private fun render(): BlazeRenderer.Mesh? { |
| 1129 | ui-function / renderInto | private fun renderInto(mc: Minecraft, state: GuiRenderState, renderer: GuiRenderer, rt: RenderTarget, gs: Int) { |
| 1154 | ui-function / of | fun of(ops: List<(GuiGraphicsExtractor) -> Unit>, alpha: Float, guiScale: Float, screenW: Int, screenH: Int): LayerOp? { |
| 1156 | ui-function / add | fun add(b: ScreenRectangle) { bounds = bounds?.let { union(it, b) } ?: b } |
| 1178 | ui-function / retire | fun retire(old: List<(GuiGraphicsExtractor) -> Unit>, next: List<(GuiGraphicsExtractor) -> Unit>) { |
| 1185 | ui-function / acquire | private fun acquire(w: Int, h: Int): OffscreenTarget { |
| 1191 | ui-function / sweep | private fun sweep(now: Long) { |
| 1197 | ui-function / union | private fun union(a: ScreenRectangle, b: ScreenRectangle): ScreenRectangle { |
| 1204 | ui-function / shifted | private fun ScreenRectangle.shifted(dx: Int, dy: Int) = ScreenRectangle(left() - dx, top() - dy, width(), height()) |
| 1206 | ui-function / shiftedDraw | private fun shiftedDraw(g: GuiGraphicsExtractor, dx: Int, dy: Int, draw: (GuiGraphicsExtractor) -> Unit) { |
| 1216 | ui-function / stacking | private inline fun stacking(gfx: GuiGraphicsExtractor, draw: () -> Unit) { |
| 1232 | ui-function / oneconfig$setStacking | fun `oneconfig$setStacking`(stacking: Boolean) |
| 1238 | ui-function / setup | fun setup(nearest: Boolean = false): TextureSetup = TextureSetup.singleTexture(texture.textureView, sampler(nearest)) |
| 1241 | ui-function / sampler | private fun sampler(nearest: Boolean) = RenderSystem.getSamplerCache().getClampToEdge(if (nearest) FilterMode.NEAREST else FilterMode.LINEAR) |
| 1251 | ui-function / entry | private fun entry(page: PolyGlyphPage.Page) = entries.getOrPut(page) { |
| 1257 | ui-function / page | fun page(page: PolyGlyphPage.Page): AbstractTextureRef = entry(page).ref |
| 1259 | ui-function / uploadDirty | fun uploadDirty() { |
| 1285 | ui-function / get | fun get(bitmap: PolyBitmap): AbstractTextureRef { |
| 1319 | ui-function / touch | fun touch(bitmaps: Set<PolyBitmap>) { |
| 1323 | ui-function / endFrame | fun endFrame() { |

### 26.3/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/ComposeSceneContextImpl.kt

`file-b4b5a379a561239b802a`；状态 **pending**；领域 ui-support；组合 26.3/fabric, 26.3/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 31 | ui-function / requestInputMode | override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard |
| 40 | ui-function / isKeyDown | private fun isKeyDown(key: Int): Boolean { |
| 46 | keybind / isCtrlPressed | isCtrlPressed = isKeyDown(InputConstants.KEY_LCONTROL) \|\| isKeyDown(InputConstants.KEY_RCONTROL), |
| 47 | keybind / isShiftPressed | isShiftPressed = isKeyDown(InputConstants.KEY_LSHIFT) \|\| isKeyDown(InputConstants.KEY_RSHIFT), |
| 48 | keybind / isAltPressed | isAltPressed = isKeyDown(InputConstants.KEY_LALT) \|\| isKeyDown(InputConstants.KEY_RALT), |
| 52 | keybind / isMetaPressed | isMetaPressed = isKeyDown(Platform.compatibility().keys().keyLeftSuper) \|\| isKeyDown(Platform.compatibility().keys().keyRightSuper) |
| 77 | ui-function / setPointerIcon | override fun setPointerIcon(pointerIcon: PointerIcon) { |
| 84 | ui-function / resetPointerIcon | fun resetPointerIcon() { |
| 88 | ui-function / applyPointerIcon | private fun applyPointerIcon(pointerIcon: PointerIcon) { |
| 98 | ui-function / requestFocus | override fun requestFocus(): Boolean { |
| 103 | ui-function / allowCursorChanges | private fun allowCursorChanges(): Boolean { |
| 124 | ui-function / startInputMethod | override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing { |
| 136 | ui-function / sync | fun sync() { |
| 149 | ui-function / close | fun close() { |
| 159 | ui-function / resetPointerIcon | fun resetPointerIcon() { |
| 163 | ui-function / updateContainerSize | fun updateContainerSize(width: Int, height: Int) { |

### 26.3/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/ComposeScreen.kt

`file-1187aee4b47dd90a0d1d`；状态 **pending**；领域 ui-support；组合 26.3/fabric, 26.3/neoforge。

对齐背景模糊开关、强度、HUD 穿透、resize 与关屏后的 GPU/画面恢复。 定位类型: blur 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 66 | ui-function / getText | override fun getText(): AnnotatedString? { |
| 75 | ui-function / setText | override fun setText(annotatedString: AnnotatedString) { |
| 82 | ui-function / hasText | override fun hasText(): Boolean { |
| 93 | ui-function / getClipEntry | override suspend fun getClipEntry(): ClipEntry? { |
| 99 | ui-function / setClipEntry | override suspend fun setClipEntry(clipEntry: ClipEntry?) { |
| 112 | route / ComposeScreen | abstract class ComposeScreen( |
| 122 | composable / compose | @Composable |
| 123 | ui-function / compose | abstract fun compose() |
| 146 | ui-function / liveScene | private fun liveScene(): ComposeScene? { |
| 151 | ui-function / withScene | private inline fun <T> withScene(block: (ComposeScene) -> T): T? { |
| 166 | ui-function / sendKeyEventSafely | private inline fun sendKeyEventSafely(build: () -> androidx.compose.ui.input.key.KeyEvent): Boolean { |
| 177 | ui-function / poisonScene | private fun poisonScene(cause: Throwable) { |
| 196 | ui-function / onSceneRebuilding | protected open fun onSceneRebuilding() {} |
| 198 | ui-function / ensureScene | private fun ensureScene(): ComposeScene? { |
| 214 | ui-function / closeSceneQuietly | private fun closeSceneQuietly() { |
| 239 | ui-function / createScene | private fun createScene(): ComposeScene { |
| 283 | ui-function / init | override fun init() { |
| 313 | ui-function / bindContent | private fun bindContent(): Boolean { |
| 320 | ui-function / setContentOn | private fun setContentOn(scene: ComposeScene) { |
| 335 | ui-function / reportUnavailableAndClose | private fun reportUnavailableAndClose() { |
| 342 | ui-function / closeWithMessage | private fun closeWithMessage(reason: String) { |
| 349 | ui-function / resize | override fun resize( |
| 358 | ui-function / isPauseScreen | override fun isPauseScreen(): Boolean = false |
| 366 | ui-function / extractBackground | override fun extractBackground(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, tickDelta: Float) { |
| 370 | blur / UiBlur.extract | UiBlur.extract(ctx, blurAlpha()) |
| 374 | ui-function / blurAlpha | protected open fun blurAlpha(): Float = 0f |
| 381 | ui-function / disposeScene | private fun disposeScene() { |
| 386 | ui-function / releaseScene | private fun releaseScene() { |
| 394 | ui-function / onClose | override fun onClose() { |
| 399 | ui-function / removed | override fun removed() { |
| 411 | ui-function / extractRenderState | override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, tickDelta: Float) { |
| 474 | ui-function / paint | private fun paint(ctx: RenderContext, nanos: Long) { |
| 491 | ui-function / mouseClicked | override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean { |
| 493 | keybind / KeybindRecordingBus.consumeMouse | if (KeybindRecordingBus.consumeMouse(button, true) \|\| handleMouseClicked(button)) { |
| 503 | ui-function / mouseReleased | override fun mouseReleased(event: MouseButtonEvent): Boolean { |
| 505 | keybind / KeybindRecordingBus.consumeMouse | val recordingConsumed = KeybindRecordingBus.consumeMouse(button, false) |
| 516 | ui-function / mouseScrolled | override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean { |
| 521 | ui-function / handleMouseClicked | protected open fun handleMouseClicked(button: Int): Boolean = false |
| 529 | ui-function / releaseHeldButtons | private fun releaseHeldButtons() { |
| 537 | ui-function / sendMouseButtonEvent | private fun sendMouseButtonEvent(type: PointerEventType, button: Int) { |
| 552 | ui-function / sendScrollEvent | private fun sendScrollEvent(scrollX: Double, scrollY: Double) { |
| 574 | ui-function / isPreciseScroll | private fun isPreciseScroll(scrollX: Double, scrollY: Double, now: Long, macPrecise: Boolean?): Boolean { |
| 586 | ui-function / charTyped | override fun charTyped(event: CharacterEvent): Boolean { |
| 594 | ui-function / ctrlDown | fun Int.ctrlDown() = this and InputConstants.MOD_CONTROL != 0 |
| 595 | ui-function / shiftDown | fun Int.shiftDown() = this and InputConstants.MOD_SHIFT != 0 |
| 596 | ui-function / altDown | fun Int.altDown() = this and InputConstants.MOD_ALT != 0 |
| 597 | ui-function / superDown | fun Int.superDown() = this and InputConstants.MOD_SUPER != 0 |
| 599 | ui-function / handleKeyPressed | protected open fun handleKeyPressed(key: Int, modifiers: Int): Boolean = false |
| 604 | ui-function / keyPressed | override fun keyPressed(event: McKeyEvent): Boolean { |
| 612 | ui-function / keyReleased | override fun keyReleased(event: McKeyEvent): Boolean { |
| 620 | ui-function / dispatchKeyPressed | private fun dispatchKeyPressed(bindingKey: Int, shortcutKey: Int, modifiers: Int): Boolean { |
| 622 | keybind / KeybindRecordingBus.consumeEscape | if (KeybindRecordingBus.consumeEscape()) { |
| 626 | keybind / KeybindRecordingBus.isRecording | if (KeybindRecordingBus.isRecording) { |
| 638 | ui-function / sendKeyPressedEvent | private fun sendKeyPressedEvent(bindingKey: Int, shortcutKey: Int, modifiers: Int): Boolean { |
| 648 | keybind / isCtrlPressed | isCtrlPressed = modifiers.ctrlDown(), |
| 649 | keybind / isShiftPressed | isShiftPressed = modifiers.shiftDown(), |
| 650 | keybind / isAltPressed | isAltPressed = modifiers.altDown(), |
| 651 | keybind / isMetaPressed | isMetaPressed = modifiers.superDown(), |
| 665 | ui-function / sendKeyReleasedEvent | private fun sendKeyReleasedEvent(bindingKey: Int, shortcutKey: Int, modifiers: Int): Boolean { |
| 673 | keybind / isCtrlPressed | isCtrlPressed = modifiers.ctrlDown(), |
| 674 | keybind / isShiftPressed | isShiftPressed = modifiers.shiftDown(), |
| 675 | keybind / isAltPressed | isAltPressed = modifiers.altDown(), |
| 676 | keybind / isMetaPressed | isMetaPressed = modifiers.superDown(), |
| 690 | ui-function / sendCharacterEvent | private fun sendCharacterEvent(char: Char, codePoint: Int, modifiers: Int): Boolean { |
| 702 | ui-function / sendCharKeyEvent | private fun sendCharKeyEvent(char: Char, codePoint: Int, modifiers: Int): Boolean { |
| 708 | keybind / isCtrlPressed | isCtrlPressed = modifiers.ctrlDown(), |
| 709 | keybind / isShiftPressed | isShiftPressed = modifiers.shiftDown(), |
| 710 | keybind / isAltPressed | isAltPressed = modifiers.altDown(), |
| 711 | keybind / isMetaPressed | isMetaPressed = modifiers.superDown(), |
| 725 | ui-function / modifiersToAwt | private fun modifiersToAwt(modifiers: Int): Int { |
| 734 | ui-function / syncSceneMetrics | private fun syncSceneMetrics(): Boolean { |
| 763 | ui-function / surfaceScale | private fun surfaceScale(): Float { |
| 774 | ui-function / pointerPosition | protected fun pointerPosition(): Offset { |
| 795 | ui-function / isWheelStep | fun isWheelStep(value: Double) = abs(value * 120 - round(value * 120)) < 1e-3 |
| 801 | ui-function / sceneDensity | internal fun sceneDensity(): Float { |
| 817 | ui-function / isDispatchNeeded | override fun isDispatchNeeded(context: CoroutineContext): Boolean = |
| 820 | ui-function / dispatch | override fun dispatch(context: CoroutineContext, block: Runnable) { |

### 26.3/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/GlHost.kt

`file-55fec8c56c0a191de15b`；状态 **pending**；领域 ui-support；组合 26.3/fabric, 26.3/neoforge。

对齐背景模糊开关、强度、HUD 穿透、resize 与关屏后的 GPU/画面恢复。 定位类型: blur 从实际宿主入口触发，核对完整状态变化、交互、退出及重进后的结果。 定位类型: peripheral-ui 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 67 | ui-function / unavailableReason | fun unavailableReason(): String? { |
| 80 | ui-function / setGpuTexture | fun setGpuTexture(t: GpuTexture?) { |
| 84 | ui-function / setGpuTextureView | fun setGpuTextureView(v: GpuTextureView?) { |
| 90 | ui-function / close | override fun close() { |
| 97 | ui-function / blitHud | fun blitHud(guiGraphics: GuiGraphicsExtractor) { |
| 127 | ui-function / screen | fun screen(dirty: Boolean, blurAlpha: Float, underlay: GuiDraw?, paint: (RenderContext) -> Unit) { |
| 145 | ui-function / drawBeforeBlit | fun drawBeforeBlit() { |
| 150 | ui-function / drawAtPresent | fun drawAtPresent() { |
| 158 | ui-function / draw | private fun draw(fbo: Int) { |
| 164 | blur / GlBlur.release | GlBlur.release() |
| 172 | peripheral-ui / NotificationsManager.count | if (NotificationsManager.count > 0) drawGl(fbo, null) { |
| 179 | ui-function / drawBlur | private fun drawBlur(fbo: Int, alpha: Float) { |
| 185 | blur / GlBlur.draw | GlBlur.draw(fbo, w, h, alpha, client.isPaused) |
| 193 | ui-function / drawGl | private fun drawGl(fbo: Int, cached: GlRenderer?, kept: Boolean = false, draw: (RenderContext) -> Unit): GlRenderer? { |
| 212 | ui-function / maxTextureSize | private fun maxTextureSize(): Int = |
| 217 | ui-function / cachedMaxTextureSize | private fun cachedMaxTextureSize(): Int { |
| 226 | ui-function / viewportFitsTexture | private fun viewportFitsTexture(w: Int, h: Int): Boolean { |
| 245 | ui-function / reuse | private fun reuse(renderer: GlRenderer?): GlRenderer { |
| 251 | ui-function / drawHudGl | fun drawHudGl(draw: (RenderContext, RenderContext?) -> Unit) { |
| 270 | ui-function / resolveHudTarget | private fun resolveHudTarget(): RenderTarget? { |
| 304 | ui-function / destroyHudTarget | private fun destroyHudTarget() { |
| 318 | ui-function / onAllocFailure | private fun onAllocFailure(what: String, w: Int, h: Int, error: Throwable) { |
| 333 | ui-function / recreateSurface | fun recreateSurface(width: Int, height: Int) { |

### 26.3/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/GlRenderer.kt

`file-8d2ea6583af9ff18ec88`；状态 **pending**；领域 ui-support；组合 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 41 | ui-function / moved | fun moved(at: Int) = Live(at, state, draw, key).also { it.last = last } |
| 43 | ui-function / changed | fun changed() = key == null \|\| key() != last |
| 45 | ui-function / commit | fun commit() { |
| 55 | ui-function / bitmapTexture | override fun bitmapTexture(bitmap: PolyBitmap): Any = GlTextures.bitmap(bitmap) |
| 59 | ui-function / mark | override fun mark(): Any { |
| 64 | ui-function / since | override fun since(mark: Any): Any { |
| 87 | ui-function / valid | override fun valid(captured: Any): Boolean = (captured as Captured).batches.all { GlTextures.alive(it.texture) } |
| 89 | ui-function / append | override fun append(captured: Any) { |
| 97 | ui-function / writeStateKey | override fun writeStateKey() { |
| 102 | ui-function / matches | fun matches(): Boolean = |
| 105 | ui-function / reset | fun reset() { |
| 111 | ui-function / submit | override fun submit(texture: Any, premultiplied: Boolean, nearest: Boolean, verts: FloatArray, colors: IntArray, bounds: FloatArray) { |
| 117 | ui-function / layerMark | override fun layerMark(): Any = intArrayOf(batches.size, lives.size) |
| 119 | ui-function / layer | override fun layer(mark: Any, alpha: Float) { |
| 132 | ui-function / fade | override fun fade(mark: Any, alpha: Float): Boolean { |
| 142 | ui-function / live | override fun live(draw: (PolyRenderer) -> Unit) { |
| 150 | ui-function / mcTextLive | override fun mcTextLive(text: String, x: Float, y: Float, color: () -> Int, shadow: Boolean, scale: Float) { |
| 176 | ui-function / texture | override fun texture(target: RenderTarget, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, alpha: Float) { |
| 186 | ui-function / draw | fun draw(fbo: Int, w: Int, h: Int, topDown: Boolean, clear: Boolean, kept: Boolean = false) { |
| 192 | ui-function / flushPending | private fun flushPending() = flush() |
| 194 | ui-function / replay | private fun replay(lives: List<Live>): List<GlProgram.Segment> = lives.map { l -> |
| 209 | ui-function / take | fun take(renderer: GlRenderer) { |
| 224 | ui-function / idle | private fun idle() { |
| 232 | ui-function / same | private fun same(a: List<GlProgram.Batch>, b: List<GlProgram.Batch>) = a.size == b.size && a.indices.all { a[it] === b[it] } |
| 234 | ui-function / sameLives | private fun sameLives(a: List<Live>, b: List<Live>) = |
| 237 | ui-function / coalesce | private fun coalesce() { |
| 241 | ui-function / emit | fun emit() { |
| 264 | ui-function / draw | fun draw(fbo: Int, w: Int, h: Int, topDown: Boolean, clear: Boolean, vararg layers: Layer) { |
| 292 | ui-function / stale | private fun stale(): Boolean = (live != null && changed()) \|\| batches.any { (it.texture as? Group)?.stale() == true } |
| 294 | ui-function / render | private fun render(composite: GlProgram.Batch, transient: ArrayList<Group>?, sink: ArrayList<Group>) { |
| 322 | ui-function / release | fun release() { |
| 336 | ui-function / of | fun of(batches: List<GlProgram.Batch>, live: (() -> List<GlProgram.Segment>)?, changed: () -> Boolean, alpha: Float, screenW: Int, screenH: Int): Group? { |
| 352 | ui-function / prepare | fun prepare(batches: List<GlProgram.Batch>, transient: ArrayList<Group>?, sink: ArrayList<Group>) { |
| 356 | ui-function / endFrame | fun endFrame() { |
| 361 | ui-function / recycle | private fun recycle(t: IntArray) { |
| 365 | ui-function / acquire | private fun acquire(w: Int, h: Int): IntArray { |
| 381 | ui-function / delete | private fun delete(t: IntArray) { |
| 392 | ui-function / segments | fun segments(lives: List<Live>): List<GlProgram.Segment> { |
| 397 | ui-function / drawBatches | fun drawBatches(fbo: Int, w: Int, h: Int, topDown: Boolean, clear: Boolean, batches: List<GlProgram.Batch>, segments: List<GlProgram.Segment>, kept: Boolean, arena: Boolean) { |
| 426 | ui-function / sameKey | fun sameKey(o: Batch) = texture === o.texture && premultiplied == o.premultiplied && nearest == o.nearest |
| 428 | ui-function / faded | fun faded(alpha: Float) = Batch(texture, premultiplied, nearest, verts, IntArray(colors.size) { i -> |
| 444 | ui-function / merge | fun merge(list: List<Batch>): Batch { |
| 493 | ui-function / init | private fun init() { |
| 520 | ui-function / shader | private fun shader(type: Int, src: String): Int { |
| 528 | ui-function / invalidate | fun invalidate(b: Batch) { |
| 534 | ui-function / draw | fun draw(batches: List<Batch>, w: Int, h: Int, topDown: Boolean, live: List<Segment> = emptyList(), kept: Boolean = false, ox: Float = 0f, oy: Float = 0f) { |
| 578 | ui-function / drawArena | fun drawArena(batches: List<Batch>, w: Int, h: Int, topDown: Boolean, live: List<Segment>) { |
| 642 | ui-function / use | fun use(id: Int) { |
| 673 | ui-function / attribPointers | private fun attribPointers() { |
| 679 | ui-function / setupState | private fun setupState(w: Int, h: Int, topDown: Boolean, ox: Float = 0f, oy: Float = 0f) { |
| 696 | ui-function / drawBatch | private fun drawBatch(b: Batch, first: Int) { |
| 712 | ui-function / filter | private fun filter(mode: Int) { |
| 717 | ui-function / upload | private fun upload(at: Int, batches: List<Batch>): Int { |
| 751 | ui-function / ensureIndices | private fun ensureIndices(quads: Int) { |
| 799 | ui-function / bitmap | fun bitmap(bitmap: PolyBitmap): Tex = bitmaps.getOrPut(bitmap) { |
| 804 | ui-function / external | fun external(id: Int): Tex = external.getOrPut(id) { Tex(null, id, 0) } |
| 806 | ui-function / alive | fun alive(key: Any): Boolean = key !is Tex \|\| key.bitmap == null \|\| bitmaps[key.bitmap] === key |
| 808 | ui-function / id | fun id(key: Any): Int = when (key) { |
| 819 | ui-function / glyphPage | private fun glyphPage(page: PolyGlyphPage.Page): Int { |
| 836 | ui-function / upload | private fun upload(bitmap: PolyBitmap): Int { |
| 845 | ui-function / unpackDefaults | private fun unpackDefaults(rowLength: Int) { |
| 853 | ui-function / newTexture | private fun newTexture(): Int { |
| 863 | ui-function / genTexture | private fun genTexture(): Int { |
| 867 | ui-function / bind | private fun bind(id: Int) { |
| 871 | ui-function / texParameter | private fun texParameter(name: Int, value: Int) { |
| 875 | ui-function / pixelStore | private fun pixelStore(name: Int, value: Int) { |
| 879 | ui-function / texImage | private fun texImage(w: Int, h: Int, px: IntBuffer) { |
| 883 | ui-function / texSubImage | private fun texSubImage(y: Int, w: Int, h: Int, px: IntBuffer) { |
| 887 | ui-function / deleteTexture | private fun deleteTexture(id: Int) { |
| 891 | ui-function / endFrame | fun endFrame() { |

### 26.3/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/MacScrollEvent.kt

`file-4dbb95c8a81110db0774`；状态 **pending**；领域 ui-support；组合 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 23 | ui-function / readScroll | fun readScroll(): Boolean? = try { |
| 51 | ui-function / dropExpiredLabels | private fun dropExpiredLabels(now: Long) { |
| 58 | ui-function / watchWheel | fun watchWheel() { |
| 63 | ui-function / takeScroll | fun takeScroll(x: Double, y: Double): Boolean? { |

### 26.3/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/McFontService.kt

`file-c1d893a5087b6beeb2e8`；状态 **pending**；领域 font-language；组合 26.3/fabric, 26.3/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 26 | font-language / McFontService | object McFontService { |
| 29 | ui-function / init | fun init() { |
| 40 | ui-function / installBundledFonts | private fun installBundledFonts() { |
| 41 | font-language / McFontService | val loader = McFontService::class.java.classLoader |
| 43 | font-language / UiFontFiles.DEFAULT_REGULAR | "assets/oneconfig/fonts/minecraft/Minecraft-Regular.otf" to UiFontFiles.DEFAULT_REGULAR, |
| 44 | font-language / UiFontFiles.DEFAULT_BOLD | "assets/oneconfig/fonts/minecraft/Minecraft-Bold.otf" to UiFontFiles.DEFAULT_BOLD, |
| 56 | ui-function / installHostFonts | private fun installHostFonts() { |
| 57 | font-language / UiFontFiles.readRegular | val regular = UiFontFiles.readRegular() |
| 64 | font-language / UiFontFiles.REGULAR | FontManager.replace(UiFontFiles.REGULAR, regular) |
| 65 | font-language / UiFontFiles.readBold | val bold = UiFontFiles.readBold() ?: regular |
| 67 | font-language / UiFontFiles.BOLD | FontManager.replace(UiFontFiles.BOLD, bold) |
| 70 | font-language / UiFontFiles.BOLD | FontManager.replace(UiFontFiles.BOLD, regular) |
| 72 | font-language / UiFontFiles.REGULAR | FontManager.setDefault(UiFontFiles.REGULAR) |
| 78 | font-language / UiFontFiles.readFallback | val fallback = UiFontFiles.readFallback() |
| 81 | font-language / UiFontFiles.FALLBACK | FontManager.replace(UiFontFiles.FALLBACK, fallback) |
| 92 | ui-function / installEngines | fun installEngines() { |

### 26.3/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/McResourceFont.kt

`file-e3f7df704a7acdab37ed`；状态 **pending**；领域 font-language；组合 26.3/fabric, 26.3/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 28 | font-language / McResourceFont | internal object McResourceFont { |
| 40 | ui-function / glyph | fun glyph(cp: Int): McGlyph? = glyphs[cp] ?: unihex[cp]?.let { u -> baked.getOrPut(cp) { bake(u) } } |
| 47 | ui-function / init | fun init() { |
| 55 | ui-function / reload | private fun reload() { |
| 74 | ui-function / fontOptions | private fun fontOptions(): Int { |
| 82 | ui-function / load | private fun load(rm: ResourceManager, options: Int): Font? { |
| 88 | ui-function / unclaimed | fun unclaimed(cp: Int) = cp !in glyphs && cp !in unihex |
| 106 | ui-function / collectProviders | private fun collectProviders(rm: ResourceManager, ref: String, out: MutableList<JsonObject>, visited: MutableSet<String>, options: Int) { |
| 122 | ui-function / filterPasses | private fun filterPasses(provider: JsonObject, options: Int): Boolean { |
| 129 | ui-function / loadBitmap | private fun loadBitmap(rm: ResourceManager, provider: JsonObject): Map<Int, McGlyph> { |
| 156 | ui-function / loadUnihex | private fun loadUnihex(rm: ResourceManager, provider: JsonObject): Map<Int, Unihex> { |
| 181 | ui-function / parseHex | private fun parseHex(line: String, overrides: List<IntArray>, out: MutableMap<Int, Unihex>): Boolean { |
| 209 | ui-function / bake | private fun bake(u: Unihex): McGlyph { |
| 221 | ui-function / read | private fun read(rm: ResourceManager, ref: String, folder: String): ByteArray? = |
| 224 | ui-function / id | private fun id(ref: String, folder: String, suffix: String = "") = |

### 26.3/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/MinecraftKeyboardAdapter.kt

`file-78e9ee322eb84f481359`；状态 **pending**；领域 ui-support；组合 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 14 | ui-function / keyLocation | fun keyLocation(bindingKey: Int): Int  = when (bindingKey) { |
| 21 | ui-function / toAwtKeyCode | fun toAwtKeyCode(shortcutKey: Int): Int = when (shortcutKey) { |

### 26.3/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/UiBlur.kt

`file-237c963123c25a7474ab`；状态 **pending**；领域 blur；组合 26.3/fabric, 26.3/neoforge。

对齐背景模糊开关、强度、HUD 穿透、resize 与关屏后的 GPU/画面恢复。 定位类型: blur 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 21 | blur / UiBlur | object UiBlur { |
| 52 | ui-function / extract | fun extract(ctx: GuiGraphicsExtractor, alpha: Float) { |
| 69 | ui-function / process | fun process(): Boolean { |
| 99 | ui-function / downInfo | private fun downInfo(mainW: Int, mainH: Int, w: Int, h: Int): GpuBuffer { |
| 110 | ui-function / info | private fun info(outW: Int, outH: Int, inW: Int, inH: Int): GpuBuffer = MemoryStack.stackPush().use { stack -> |
| 116 | ui-function / draw | private fun draw(pass: PostPass, input: GpuTextureView, output: RenderTarget, info: GpuBuffer) { |

### 26.3/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/impls/HudEditorUIScreen.kt

`file-b9846392323f51d805aa`；状态 **pending**；领域 hud-editor；组合 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 36 | route / HudEditorUIScreen | class HudEditorUIScreen private constructor() : ComposeScreen() { |
| 41 | ui-function / open | fun open(fromOneConfig: Boolean): HudEditorUIScreen { |
| 56 | ui-function / beginClose | private fun beginClose() { |
| 61 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLOSE) |
| 61 | sound / UiSoundEvent.CLOSE | UiSounds.play(UiSoundEvent.CLOSE) |
| 62 | sound / UiSounds.releaseAmbience | UiSounds.releaseAmbience() |
| 62 | sound / releaseAmbience | UiSounds.releaseAmbience() |
| 65 | ui-function / cancelClose | private fun cancelClose(): Boolean { |
| 69 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.OPEN) |
| 69 | sound / UiSoundEvent.OPEN | UiSounds.play(UiSoundEvent.OPEN) |
| 70 | sound / UiSounds.acquireAmbience | UiSounds.acquireAmbience() |
| 70 | sound / acquireAmbience | UiSounds.acquireAmbience() |
| 78 | ui-function / returnToOneConfig | private fun returnToOneConfig() { |
| 86 | ui-function / init | override fun init() { |
| 95 | sound / UiSounds.acquireAmbience | UiSounds.acquireAmbience() |
| 95 | sound / acquireAmbience | UiSounds.acquireAmbience() |
| 101 | ui-function / removed | override fun removed() { |
| 107 | sound / UiSounds.releaseAmbience | UiSounds.releaseAmbience() |
| 107 | sound / releaseAmbience | UiSounds.releaseAmbience() |
| 113 | ui-function / isPauseScreen | override fun isPauseScreen(): Boolean = OneConfigConfig.pauseGame |
| 115 | ui-function / handleOneConfigKeybind | private fun handleOneConfigKeybind(): Boolean { |
| 127 | ui-function / handleKeyPressed | override fun handleKeyPressed(key: Int, modifiers: Int): Boolean { |
| 136 | keybind / KeybindRecordingBus.isRecording | if (toggleKey != null && key == toggleKey && !KeybindRecordingBus.isRecording) { |
| 142 | ui-function / handleMouseClicked | override fun handleMouseClicked(button: Int): Boolean { |
| 143 | keybind / KeybindRecordingBus.isRecording | if (KeybindRecordingBus.isRecording) return false |
| 146 | keybind / KeybindManager.isTriggeredByMouse | KeybindManager.isTriggeredByMouse(OneConfigConfig.oneConfigKeybind, button) |
| 154 | ui-function / extractRenderState | override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, tickDelta: Float) { |
| 174 | composable / compose | @Composable |
| 175 | ui-function / compose | override fun compose() { |
| 196 | animation / RetainedVisibility | RetainedVisibility( |
| 198 | animation / tween | enter = tween(200, easing = EaseOutCubic), |
| 199 | animation / tween | exit = tween(exitMs, easing = EaseIn), |

### 26.3/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/impls/OneConfigUIScreen.kt

`file-6d8ea9a10ba2856f4239`；状态 **pending**；领域 shell-navigation；组合 26.3/fabric, 26.3/neoforge。

对齐背景模糊开关、强度、HUD 穿透、resize 与关屏后的 GPU/画面恢复。 定位类型: blur 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 79 | route / OneConfigUIScreen | class OneConfigUIScreen @JvmOverloads constructor( |
| 91 | ui-function / blurAlpha | override fun blurAlpha(): Float { |
| 92 | blur / enableBackgroundBlur | if (!OneConfigConfig.enableBackgroundBlur) return 0f |
| 110 | ui-function / shared | private fun shared(): OneConfigUIScreen = |
| 114 | ui-function / forRoute | fun forRoute(route: Any?): OneConfigUIScreen = shared().also { |
| 120 | ui-function / open | fun open(): OneConfigUIScreen = shared().also { |
| 127 | ui-function / resume | fun resume(): OneConfigUIScreen = shared().also { |
| 141 | ui-function / scheduleSave | private fun scheduleSave() { |
| 154 | route / OpeningRoute | private data class OpeningRoute(val route: Any, val restored: Boolean = false) |
| 156 | ui-function / resolveOpeningRoute | private fun resolveOpeningRoute(): OpeningRoute { |
| 176 | ui-function / stopWarmUp | fun stopWarmUp() { |
| 189 | ui-function / dropPrerasteredLater | private fun dropPrerasteredLater() { |
| 196 | ui-function / prepareWarmUp | fun prepareWarmUp(): Runnable? { |
| 211 | ui-function / warmUp | private fun warmUp(w: Int, h: Int, density: Float, surface: Float) { |
| 221 | ui-function / warmFrames | private fun warmFrames(w: Int, h: Int, density: Float, surface: Float) { |
| 226 | ui-function / isDispatchNeeded | override fun isDispatchNeeded(context: CoroutineContext) = Thread.currentThread() !== thread |
| 227 | ui-function / dispatch | override fun dispatch(context: CoroutineContext, block: Runnable) { |
| 231 | ui-function / drain | fun drain() { |
| 291 | ui-function / openLastSession | fun openLastSession() { |
| 302 | ui-function / beginClose | private fun beginClose() { |
| 307 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLOSE) |
| 307 | sound / UiSoundEvent.CLOSE | UiSounds.play(UiSoundEvent.CLOSE) |
| 308 | sound / UiSounds.releaseAmbience | UiSounds.releaseAmbience() |
| 308 | sound / releaseAmbience | UiSounds.releaseAmbience() |
| 311 | ui-function / cancelClose | private fun cancelClose(): Boolean { |
| 322 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.OPEN) |
| 322 | sound / UiSoundEvent.OPEN | UiSounds.play(UiSoundEvent.OPEN) |
| 323 | sound / UiSounds.acquireAmbience | UiSounds.acquireAmbience() |
| 323 | sound / acquireAmbience | UiSounds.acquireAmbience() |
| 339 | ui-function / markClosed | private fun markClosed() { |
| 343 | ui-function / init | override fun init() { |
| 415 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.OPEN) |
| 415 | sound / UiSoundEvent.OPEN | UiSounds.play(UiSoundEvent.OPEN) |
| 416 | sound / UiSounds.acquireAmbience | UiSounds.acquireAmbience() |
| 416 | sound / acquireAmbience | UiSounds.acquireAmbience() |
| 426 | ui-function / onSceneRebuilding | override fun onSceneRebuilding() { |
| 432 | ui-function / removed | override fun removed() { |
| 437 | sound / UiSounds.releaseAmbience | UiSounds.releaseAmbience() |
| 437 | sound / releaseAmbience | UiSounds.releaseAmbience() |
| 443 | ui-function / isPauseScreen | override fun isPauseScreen(): Boolean = OneConfigConfig.pauseGame |
| 445 | ui-function / handleOneConfigKeybind | private fun handleOneConfigKeybind(): Boolean { |
| 457 | ui-function / handleKeyPressed | override fun handleKeyPressed(key: Int, modifiers: Int): Boolean { |
| 466 | keybind / KeybindRecordingBus.isRecording | if (toggleKey != null && key == toggleKey && !KeybindRecordingBus.isRecording) { |
| 473 | ui-function / handleMouseClicked | override fun handleMouseClicked(button: Int): Boolean { |
| 474 | keybind / KeybindRecordingBus.isRecording | if (KeybindRecordingBus.isRecording) return false |
| 477 | keybind / KeybindManager.isTriggeredByMouse | KeybindManager.isTriggeredByMouse(OneConfigConfig.oneConfigKeybind, button) |
| 484 | keybind / KeybindManager.hasTriggeredMouseBind | if (KeybindManager.hasTriggeredMouseBind(button)) return true |
| 485 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLICK) |
| 485 | sound / UiSoundEvent.CLICK | UiSounds.play(UiSoundEvent.CLICK) |
| 490 | keybind / KeybindManager.hasTriggeredMouseBind | if (KeybindManager.hasTriggeredMouseBind(button)) return true |
| 491 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLICK) |
| 491 | sound / UiSoundEvent.CLICK | UiSounds.play(UiSoundEvent.CLICK) |
| 501 | ui-function / extractRenderState | override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, tickDelta: Float) { |
| 523 | ui-function / easeOutExpo | private fun easeOutExpo(progress: Float): Float { |
| 531 | composable / compose | @Composable |
| 532 | ui-function / compose | override fun compose() { |
| 564 | ui-function / submit | override fun submit(texture: Any, premultiplied: Boolean, nearest: Boolean, verts: FloatArray, colors: IntArray, bounds: FloatArray) {} |
| 566 | ui-function / bitmapTexture | override fun bitmapTexture(bitmap: PolyBitmap): Any = bitmap |
| 568 | ui-function / live | override fun live(draw: (PolyRenderer) -> Unit) = draw(this) |

### 26.3/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/opengl/StoredGLState.kt

`file-dfab05654c4cef34748e`；状态 **pending**；领域 ui-support；组合 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 10 | ui-function / resyncTextureBindCache | fun resyncTextureBindCache() { |
| 21 | ui-function / capture | fun capture(): StoredGLState { |
| 102 | ui-function / restore | fun restore(): StoredGLState { |
| 195 | ui-function / forceToggle | private fun forceToggle(enabled: Boolean, enable: () -> Unit, disable: () -> Unit) { |
| 205 | ui-function / forceDepthFunc | private fun forceDepthFunc(func: Int) { |
| 211 | ui-function / forceDepthMask | private fun forceDepthMask(mask: Boolean) { |

### 26.3/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/CompatOverlayRenderer.kt

`file-994a21d87b450f611be4`；状态 **pending**；领域 hud-editor；组合 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 14 | ui-function / register | fun register(hook: (GuiGraphicsExtractor) -> Unit) { |
| 19 | ui-function / hasHooks | fun hasHooks(): Boolean = hooks.isNotEmpty() |
| 22 | ui-function / oneConfigScreenOpen | fun oneConfigScreenOpen(): Boolean = HudManager.isEditing |
| 25 | ui-function / render | fun render(ctx: GuiGraphicsExtractor) { |

### 26.3/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/LegacyHudRenderer.kt

`file-4d53f4de25dc2240109c`；状态 **pending**；领域 hud-editor；组合 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 32 | ui-function / reportFailure | private fun reportFailure(hud: LegacyHud, e: Throwable) { |
| 47 | ui-function / renderLive | fun renderLive(graphics: GuiGraphicsExtractor) { |
| 52 | ui-function / renderLiveHuds | private fun renderLiveHuds(graphics: GuiGraphicsExtractor) { |

### 26.3/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/keybind/MinecraftKeybindProvider.kt

`file-9f9f64628f921180add0`；状态 **pending**；领域 keybind；组合 26.3/fabric, 26.3/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 23 | keybind / OneConfigKeybind | private val properties = IdentityHashMap<KeyMapping, Property<OneConfigKeybind>>() |
| 38 | ui-function / groups | override fun groups(): List<KeybindGroup> { |
| 78 | ui-function / managedMappings | fun managedMappings(): List<KeyMapping> { |
| 83 | keybind / OneConfigKeybind | private fun propertyFor(mapping: KeyMapping): Property<OneConfigKeybind> { |
| 83 | ui-function / propertyFor | private fun propertyFor(mapping: KeyMapping): Property<OneConfigKeybind> { |
| 90 | keybind / OneConfigKeybind | OneConfigKeybind::class.java, |
| 100 | ui-function / ownerMod | private fun ownerMod(mapping: KeyMapping, modsById: Map<String, ModInfo>): ModInfo? { |
| 106 | ui-function / ownerCandidates | private fun ownerCandidates(keyName: String): Sequence<String> = sequence { |
| 118 | ui-function / modIcon | private fun modIcon(owner: ModInfo?): String? = |
| 125 | ui-function / minecraftIcon | private fun minecraftIcon(): String { |
| 133 | ui-function / extractMinecraftIcon | private fun extractMinecraftIcon(): String? = runCatching { |
| 150 | ui-function / groupTitle | private fun groupTitle(category: String, owner: ModInfo?): String { |
| 156 | ui-function / sanitizeId | private fun sanitizeId(value: String): String { |
| 163 | keybind / OneConfigKeybind | private fun KeyMapping.toOneConfigKeybind(): OneConfigKeybind { |
| 163 | ui-function / toOneConfigKeybind | private fun KeyMapping.toOneConfigKeybind(): OneConfigKeybind { |
| 168 | keybind / Key.toOneConfigKeybind | private fun InputConstants.Key.toOneConfigKeybind(): OneConfigKeybind { |
| 168 | keybind / OneConfigKeybind | private fun InputConstants.Key.toOneConfigKeybind(): OneConfigKeybind { |
| 168 | ui-function / toOneConfigKeybind | private fun InputConstants.Key.toOneConfigKeybind(): OneConfigKeybind { |
| 173 | keybind / OneConfigKeybind | OneConfigKeybind(intArrayOf(value), null, KeyModifiers.NONE, 0L) { true } |
| 176 | keybind / OneConfigKeybind | OneConfigKeybind(null, intArrayOf(value), KeyModifiers.NONE, 0L) { true } |
| 177 | keybind / OneConfigKeybind | else -> OneConfigKeybind(null, null, KeyModifiers.NONE, 0L) { true } |
| 181 | keybind / OneConfigKeybind | private fun KeyMapping.applyOneConfigKeybind(keybind: OneConfigKeybind?) { |
| 181 | ui-function / applyOneConfigKeybind | private fun KeyMapping.applyOneConfigKeybind(keybind: OneConfigKeybind?) { |
| 195 | ui-function / categoryLabel | private fun categoryLabel(mapping: KeyMapping): String { |
| 201 | ui-function / translate | private fun translate(key: String): String { |
| 213 | ui-function / isOneConfigMirror | private fun isOneConfigMirror(@Suppress("UNUSED_PARAMETER") mapping: KeyMapping): Boolean = false |

### 26.3/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/sound/McUiSoundService.kt

`file-58b50f750c56b67debcd`；状态 **pending**；领域 sound；组合 26.3/fabric, 26.3/neoforge。

对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 19 | sound / McUiSoundService | class McUiSoundService : UiSoundService { |
| 19 | sound / UiSoundService | class McUiSoundService : UiSoundService { |
| 28 | sound / UiSoundTheme | private val ambienceShuffle = mutableMapOf<UiSoundTheme, MutableList<String>>() |
| 29 | sound / UiSoundTheme | private val lastAmbienceSoundId = mutableMapOf<UiSoundTheme, String>() |
| 33 | sound / UiSoundEvent | override fun play(event: UiSoundEvent, theme: UiSoundTheme, volume: Float) { |
| 33 | sound / UiSoundTheme | override fun play(event: UiSoundEvent, theme: UiSoundTheme, volume: Float) { |
| 33 | ui-function / play | override fun play(event: UiSoundEvent, theme: UiSoundTheme, volume: Float) { |
| 37 | sound / UiSoundTheme.MODERN | UiSoundTheme.MODERN -> playModern(event, volume) |
| 38 | sound / UiSoundTheme.MINECRAFT | UiSoundTheme.MINECRAFT -> playMinecraft(event, volume) |
| 44 | sound / UiSoundEvent | private fun playModern(event: UiSoundEvent, volume: Float) { |
| 44 | ui-function / playModern | private fun playModern(event: UiSoundEvent, volume: Float) { |
| 46 | sound / UiSoundEvent.OPEN | UiSoundEvent.OPEN -> playModernSound("ui.open", 1f, volume) |
| 47 | sound / UiSoundEvent.CLOSE | UiSoundEvent.CLOSE -> playModernSound("ui.close", 1f, volume) |
| 48 | sound / UiSoundEvent.CLICK | UiSoundEvent.CLICK -> playModernSound("ui.click", 1f, volume) |
| 49 | sound / UiSoundEvent.SLIDER_TICK | UiSoundEvent.SLIDER_TICK -> playModernSound("ui.slider", 1f, volume) |
| 50 | sound / UiSoundEvent.HUD_SELECT | UiSoundEvent.HUD_SELECT -> playModernSound("ui.click", 1.0f, volume) |
| 51 | sound / UiSoundEvent.HUD_DRAG_START | UiSoundEvent.HUD_DRAG_START -> playModernSound("ui.click", 0.9f, volume) |
| 52 | sound / UiSoundEvent.HUD_DRAG_END | UiSoundEvent.HUD_DRAG_END -> playModernSound("ui.click", 1.1f, volume) |
| 53 | sound / UiSoundEvent.HUD_RESIZE_END | UiSoundEvent.HUD_RESIZE_END -> playModernSound("ui.click", 1.0f, volume) |
| 58 | ui-function / playModernSound | private fun playModernSound(id: String, pitch: Float, volume: Float) { |
| 63 | sound / UiSoundEvent | private fun playMinecraft(event: UiSoundEvent, volume: Float) { |
| 63 | ui-function / playMinecraft | private fun playMinecraft(event: UiSoundEvent, volume: Float) { |
| 65 | sound / UiSoundEvent.CLICK | UiSoundEvent.CLICK -> playVanilla(SoundEvents.UI_BUTTON_CLICK, 1f, 0.5f * volume) |
| 66 | sound / UiSoundEvent.OPEN | UiSoundEvent.OPEN -> playModernSound("ui.open", 1f, volume) |
| 67 | sound / UiSoundEvent.CLOSE | UiSoundEvent.CLOSE -> playModernSound("ui.close", 1f, volume) |
| 68 | sound / UiSoundEvent.SLIDER_TICK | UiSoundEvent.SLIDER_TICK -> { |
| 73 | sound / UiSoundEvent.HUD_SELECT | UiSoundEvent.HUD_SELECT -> playVanilla(SoundEvents.UI_BUTTON_CLICK, 1f, 0.3f * volume) |
| 74 | sound / UiSoundEvent.HUD_DRAG_END | UiSoundEvent.HUD_DRAG_END -> playVanilla(SoundEvents.UI_BUTTON_CLICK, 1f, 0.3f * volume) |
| 75 | sound / UiSoundEvent.HUD_RESIZE_END | UiSoundEvent.HUD_RESIZE_END -> playVanilla(SoundEvents.UI_BUTTON_CLICK, 1f, 0.3f * volume) |
| 80 | ui-function / playVanilla | private fun playVanilla(event: SoundEvent, pitch: Float, volume: Float) { |
| 85 | ui-function / playVanilla | private fun playVanilla(holder: Holder<SoundEvent>, pitch: Float, volume: Float) = |
| 88 | sound / startAmbience | override fun startAmbience(theme: UiSoundTheme, volume: Float) { |
| 88 | sound / UiSoundTheme | override fun startAmbience(theme: UiSoundTheme, volume: Float) { |
| 88 | ui-function / startAmbience | override fun startAmbience(theme: UiSoundTheme, volume: Float) { |
| 109 | sound / stopAmbience | override fun stopAmbience(@Suppress("UNUSED_PARAMETER") setDucking: Boolean) { |
| 109 | ui-function / stopAmbience | override fun stopAmbience(@Suppress("UNUSED_PARAMETER") setDucking: Boolean) { |
| 118 | sound / UiSoundTheme | private fun ambienceVolumeFor(theme: UiSoundTheme, volume: Float): Float = when (theme) { |
| 118 | ui-function / ambienceVolumeFor | private fun ambienceVolumeFor(theme: UiSoundTheme, volume: Float): Float = when (theme) { |
| 119 | sound / UiSoundTheme.MODERN | UiSoundTheme.MODERN -> 0.5f * volume |
| 120 | sound / UiSoundTheme.MINECRAFT | UiSoundTheme.MINECRAFT -> 0.08f * volume |
| 123 | sound / UiSoundTheme | private fun ambienceSoundIds(theme: UiSoundTheme): List<String> = when (theme) { |
| 123 | ui-function / ambienceSoundIds | private fun ambienceSoundIds(theme: UiSoundTheme): List<String> = when (theme) { |
| 124 | sound / UiSoundTheme.MODERN | UiSoundTheme.MODERN -> listOf("ui.pad_loop") |
| 125 | sound / UiSoundTheme.MINECRAFT | UiSoundTheme.MINECRAFT -> listOf( |
| 133 | sound / UiSoundTheme | private fun playAmbienceInstance(theme: UiSoundTheme, targetVolume: Float): FadingLoopSoundInstance { |
| 133 | ui-function / playAmbienceInstance | private fun playAmbienceInstance(theme: UiSoundTheme, targetVolume: Float): FadingLoopSoundInstance { |
| 144 | sound / UiSoundTheme | private fun nextAmbienceSoundId(theme: UiSoundTheme, ids: List<String>): String { |
| 144 | ui-function / nextAmbienceSoundId | private fun nextAmbienceSoundId(theme: UiSoundTheme, ids: List<String>): String { |
| 165 | ui-function / driveAmbienceLoop | private fun driveAmbienceLoop() { |
| 196 | sound / UiSoundTheme | val theme: UiSoundTheme, |
| 215 | ui-function / beginFadeOut | fun beginFadeOut() { |
| 219 | ui-function / cancelFadeOut | fun cancelFadeOut() { |
| 224 | ui-function / updateTargetVolume | fun updateTargetVolume(newTarget: Float) { |
| 229 | ui-function / tick | override fun tick() { |

### muoniumconfig-common/src/main/java/org/polyfrost/oneconfig/api/config/v1/CompatSnapshots.java

`file-e8e62a90405379b1f433`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 304 | keybind / OneConfigKeybind | if (value instanceof OneConfigKeybind && ((OneConfigKeybind) value).getHasUnresolvedInputs()) { |
| 304 | keybind / OneConfigKeybind | if (value instanceof OneConfigKeybind && ((OneConfigKeybind) value).getHasUnresolvedInputs()) { |

### muoniumconfig-common/src/main/java/org/polyfrost/oneconfig/api/config/v1/serialize/adapter/impl/OneConfigKeybindAdapter.java

`file-126a92d3f1f17f3680ed`；状态 **pending**；领域 keybind；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 21 | keybind / OneConfigKeybind | public final class OneConfigKeybindAdapter extends Adapter<OneConfigKeybind, Map> { |
| 34 | keybind / OneConfigKeybind | public Map<String, Object> serialize(OneConfigKeybind in) { |
| 36 | keybind / getKeyCodes | List<Object> keys = merge(in.getKeyCodes() == null ? null : encode(in.getKeyCodes(), true), in.getUnresolvedKeyInputs()); |
| 36 | keybind / getKeyCodes | List<Object> keys = merge(in.getKeyCodes() == null ? null : encode(in.getKeyCodes(), true), in.getUnresolvedKeyInputs()); |
| 46 | keybind / OneConfigKeybind | public OneConfigKeybind deserialize(Map in) { |
| 53 | keybind / OneConfigKeybind | OneConfigKeybind out = BindNotInScreen.class.getName().equals(in.get("class")) |
| 55 | keybind / OneConfigKeybind | : new OneConfigKeybind(keyCodes, mouseBtns, mods, durationNanos, ignored -> true); |
| 69 | keybind / OneConfigKeybind | public Class<OneConfigKeybind> getTargetClass() { |
| 70 | keybind / OneConfigKeybind.class | return OneConfigKeybind.class; |

### muoniumconfig-common/src/main/java/org/polyfrost/oneconfig/api/hud/v1/FavoritesTree.java

`file-ea03f8617b2898aaeded`；状态 **pending**；领域 hud-editor；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

该文件支撑 hud-editor；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### muoniumconfig-common/src/main/java/org/polyfrost/oneconfig/api/hud/v1/NormalizedHudProvider.java

`file-b253c8fbaa3c76f24fa5`；状态 **pending**；领域 hud-editor；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

该文件支撑 hud-editor；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### muoniumconfig-common/src/main/java/org/polyfrost/oneconfig/api/hud/v1/NormalizedHudSupport.java

`file-45ae266a6e596c0d9dc9`；状态 **pending**；领域 hud-editor；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

该文件支撑 hud-editor；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### muoniumconfig-common/src/main/java/org/polyfrost/oneconfig/api/hud/v1/events/HudEditorToggleEvent.java

`file-c852d6dd872365b3198d`；状态 **pending**；领域 hud-editor；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

该文件支撑 hud-editor；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### muoniumconfig-common/src/main/java/org/polyfrost/oneconfig/api/platform/v1/I18nPlatform.java

`file-57b7b963b3c30e3218b1`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 29 | font-language / I18nPlatform | public interface I18nPlatform { |

### muoniumconfig-common/src/main/java/org/polyfrost/oneconfig/api/platform/v1/Platform.java

`file-5304d1cd436a811f8250`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 42 | font-language / I18nPlatform | private static I18nPlatform i18nPlatform; |
| 53 | font-language / I18nPlatform | public static I18nPlatform i18n() { |
| 54 | font-language / I18nPlatform.class | return i18nPlatform == null ? i18nPlatform = load(I18nPlatform.class) : i18nPlatform; |

### muoniumconfig-common/src/main/java/org/polyfrost/oneconfig/api/ui/v1/api/NanoSvgApi.java

`file-0107e3cda2ffa63fc95a`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### muoniumconfig-common/src/main/java/org/polyfrost/oneconfig/api/ui/v1/api/RendererExt.java

`file-b28ecddb5f6e2ce45824`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### muoniumconfig-common/src/main/java/org/polyfrost/oneconfig/api/ui/v1/api/StbApi.java

`file-e1e203c827d6dc05d1b4`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### muoniumconfig-common/src/main/java/org/polyfrost/oneconfig/api/ui/v1/api/TinyFdApi.java

`file-31ba9306a84dd0907761`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### muoniumconfig-common/src/main/java/org/polyfrost/oneconfig/api/ui/v1/api/TinyFdHolder.java

`file-254e2b90748dee0f1cee`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### muoniumconfig-common/src/main/java/org/polyfrost/oneconfig/api/ui/v1/keybind/internal/KeybindCodec.java

`file-a224f90fd84c6b33a73f`；状态 **pending**；领域 keybind；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

该文件支撑 keybind；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### muoniumconfig-common/src/main/java/org/polyfrost/oneconfig/api/ui/v1/screen/BlurScreen.java

`file-a240e7f9bd6f6512280d`；状态 **pending**；领域 blur；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐背景模糊开关、强度、HUD 穿透、resize 与关屏后的 GPU/画面恢复。 定位类型: blur

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 29 | blur / BlurScreen | public interface BlurScreen { |

### muoniumconfig-common/src/main/java/org/polyfrost/oneconfig/internal/OneConfigConfig.java

`file-3801455ce987074d1c86`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 对齐背景模糊开关、强度、HUD 穿透、resize 与关屏后的 GPU/画面恢复。 定位类型: blur 实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: preference-setting 对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 61 | keybind / OneConfigKeybind | public static OneConfigKeybind oneConfigKeybind = |
| 61 | preference-setting / oneConfigKeybind | public static OneConfigKeybind oneConfigKeybind = |
| 62 | keybind / OneConfigKeybind | new OneConfigKeybind(null, null, KeyModifiers.NONE, 0L, pressed -> true); |
| 72 | preference-setting / keybindClosesGui | public static boolean keybindClosesGui = false; |
| 82 | keybind / OneConfigKeybind | public static OneConfigKeybind hudSettingsKeybind = |
| 82 | preference-setting / hudSettingsKeybind | public static OneConfigKeybind hudSettingsKeybind = |
| 83 | keybind / OneConfigKeybind | new OneConfigKeybind(new int[] {KEYS.getKeyE()}, null, HUD_ACTION_MODS, 0L, pressed -> true); |
| 83 | keybind / getKeyE | new OneConfigKeybind(new int[] {KEYS.getKeyE()}, null, HUD_ACTION_MODS, 0L, pressed -> true); |
| 93 | keybind / OneConfigKeybind | public static OneConfigKeybind hudVisibilityKeybind = |
| 93 | preference-setting / hudVisibilityKeybind | public static OneConfigKeybind hudVisibilityKeybind = |
| 94 | keybind / OneConfigKeybind | new OneConfigKeybind(new int[] {KEYS.getKeyH()}, null, HUD_ACTION_MODS, 0L, pressed -> true); |
| 94 | keybind / getKeyH | new OneConfigKeybind(new int[] {KEYS.getKeyH()}, null, HUD_ACTION_MODS, 0L, pressed -> true); |
| 104 | keybind / OneConfigKeybind | public static OneConfigKeybind hudLockKeybind = |
| 104 | preference-setting / hudLockKeybind | public static OneConfigKeybind hudLockKeybind = |
| 105 | keybind / OneConfigKeybind | new OneConfigKeybind(new int[] {KEYS.getKeyL()}, null, HUD_ACTION_MODS, 0L, pressed -> true); |
| 105 | keybind / getKeyL | new OneConfigKeybind(new int[] {KEYS.getKeyL()}, null, HUD_ACTION_MODS, 0L, pressed -> true); |
| 115 | keybind / OneConfigKeybind | public static OneConfigKeybind hudCopyKeybind = |
| 115 | preference-setting / hudCopyKeybind | public static OneConfigKeybind hudCopyKeybind = |
| 116 | keybind / OneConfigKeybind | new OneConfigKeybind(new int[] {KEYS.getKeyC()}, null, HUD_ACTION_MODS, 0L, pressed -> true); |
| 116 | keybind / getKeyC | new OneConfigKeybind(new int[] {KEYS.getKeyC()}, null, HUD_ACTION_MODS, 0L, pressed -> true); |
| 126 | keybind / OneConfigKeybind | public static OneConfigKeybind hudCutKeybind = |
| 126 | preference-setting / hudCutKeybind | public static OneConfigKeybind hudCutKeybind = |
| 127 | keybind / OneConfigKeybind | new OneConfigKeybind(new int[] {KEYS.getKeyX()}, null, HUD_ACTION_MODS, 0L, pressed -> true); |
| 127 | keybind / getKeyX | new OneConfigKeybind(new int[] {KEYS.getKeyX()}, null, HUD_ACTION_MODS, 0L, pressed -> true); |
| 137 | keybind / OneConfigKeybind | public static OneConfigKeybind hudPasteKeybind = |
| 137 | preference-setting / hudPasteKeybind | public static OneConfigKeybind hudPasteKeybind = |
| 138 | keybind / OneConfigKeybind | new OneConfigKeybind(new int[] {KEYS.getKeyV()}, null, HUD_ACTION_MODS, 0L, pressed -> true); |
| 138 | keybind / getKeyV | new OneConfigKeybind(new int[] {KEYS.getKeyV()}, null, HUD_ACTION_MODS, 0L, pressed -> true); |
| 148 | keybind / OneConfigKeybind | public static OneConfigKeybind hudDuplicateKeybind = |
| 148 | preference-setting / hudDuplicateKeybind | public static OneConfigKeybind hudDuplicateKeybind = |
| 149 | keybind / OneConfigKeybind | new OneConfigKeybind(new int[] {KEYS.getKeyD()}, null, HUD_ACTION_MODS, 0L, pressed -> true); |
| 149 | keybind / getKeyD | new OneConfigKeybind(new int[] {KEYS.getKeyD()}, null, HUD_ACTION_MODS, 0L, pressed -> true); |
| 159 | keybind / OneConfigKeybind | public static OneConfigKeybind hudResetKeybind = |
| 159 | preference-setting / hudResetKeybind | public static OneConfigKeybind hudResetKeybind = |
| 160 | keybind / OneConfigKeybind | new OneConfigKeybind(new int[] {KEYS.getKeyR()}, null, HUD_ACTION_MODS, 0L, pressed -> true); |
| 160 | keybind / getKeyR | new OneConfigKeybind(new int[] {KEYS.getKeyR()}, null, HUD_ACTION_MODS, 0L, pressed -> true); |
| 170 | keybind / OneConfigKeybind | public static OneConfigKeybind hudDeleteKeybind = |
| 170 | preference-setting / hudDeleteKeybind | public static OneConfigKeybind hudDeleteKeybind = |
| 171 | keybind / OneConfigKeybind | new OneConfigKeybind(new int[] {KEYS.getKeyDelete()}, null, KeyModifiers.NONE, 0L, pressed -> true); |
| 171 | keybind / getKeyDelete | new OneConfigKeybind(new int[] {KEYS.getKeyDelete()}, null, KeyModifiers.NONE, 0L, pressed -> true); |
| 181 | keybind / OneConfigKeybind | public static OneConfigKeybind hudSelectAllKeybind = |
| 181 | preference-setting / hudSelectAllKeybind | public static OneConfigKeybind hudSelectAllKeybind = |
| 182 | keybind / OneConfigKeybind | new OneConfigKeybind(new int[] {KEYS.getKeyA()}, null, HUD_ACTION_MODS, 0L, pressed -> true); |
| 182 | keybind / getKeyA | new OneConfigKeybind(new int[] {KEYS.getKeyA()}, null, HUD_ACTION_MODS, 0L, pressed -> true); |
| 192 | keybind / OneConfigKeybind | public static OneConfigKeybind hudUndoKeybind = |
| 192 | preference-setting / hudUndoKeybind | public static OneConfigKeybind hudUndoKeybind = |
| 193 | keybind / OneConfigKeybind | new OneConfigKeybind(new int[] {KEYS.getKeyZ()}, null, HUD_ACTION_MODS, 0L, pressed -> true); |
| 193 | keybind / getKeyZ | new OneConfigKeybind(new int[] {KEYS.getKeyZ()}, null, HUD_ACTION_MODS, 0L, pressed -> true); |
| 203 | keybind / OneConfigKeybind | public static OneConfigKeybind hudRedoKeybind = |
| 203 | preference-setting / hudRedoKeybind | public static OneConfigKeybind hudRedoKeybind = |
| 204 | keybind / OneConfigKeybind | new OneConfigKeybind(new int[] {KEYS.getKeyZ()}, null, HUD_REDO_MODS, 0L, pressed -> true); |
| 204 | keybind / getKeyZ | new OneConfigKeybind(new int[] {KEYS.getKeyZ()}, null, HUD_REDO_MODS, 0L, pressed -> true); |
| 214 | keybind / OneConfigKeybind | public static OneConfigKeybind hudRedoAltKeybind = |
| 214 | preference-setting / hudRedoAltKeybind | public static OneConfigKeybind hudRedoAltKeybind = |
| 215 | keybind / OneConfigKeybind | new OneConfigKeybind(new int[] {KEYS.getKeyY()}, null, HUD_ACTION_MODS, 0L, pressed -> true); |
| 215 | keybind / getKeyY | new OneConfigKeybind(new int[] {KEYS.getKeyY()}, null, HUD_ACTION_MODS, 0L, pressed -> true); |
| 225 | preference-setting / showKeybindHints | public static boolean showKeybindHints = true; |
| 235 | animation / marqueeSelectionAnim | public static boolean marqueeSelectionAnim = true; |
| 235 | preference-setting / marqueeSelectionAnim | public static boolean marqueeSelectionAnim = true; |
| 248 | animation / marqueeSelectionAnimDuration | public static float marqueeSelectionAnimDuration = 150f; |
| 248 | preference-setting / marqueeSelectionAnimDuration | public static float marqueeSelectionAnimDuration = 150f; |
| 258 | blur / enableBackgroundBlur | public static boolean enableBackgroundBlur = true; |
| 258 | preference-setting / enableBackgroundBlur | public static boolean enableBackgroundBlur = true; |
| 271 | preference-setting / hudDragUiOpacity | public static float hudDragUiOpacity = 0.60f; |
| 284 | preference-setting / pageOpacity | public static float pageOpacity = 88f; |
| 297 | preference-setting / sidebarOpacity | public static float sidebarOpacity = 80f; |
| 310 | preference-setting / glowOpacity | public static float glowOpacity = 25f; |
| 320 | preference-setting / pauseGame | public static boolean pauseGame = false; |
| 333 | preference-setting / uiScale | public static float uiScale = 100f; |
| 349 | preference-setting / pixelGrid | public static int pixelGrid = 0; |
| 367 | preference-setting / rememberLastPage | public static int rememberLastPage = 1; |
| 376 | preference-setting / hudEditorTourSeen | public static boolean hudEditorTourSeen = false; |
| 386 | preference-setting / restoreHudEditor | public static boolean restoreHudEditor = false; |
| 396 | animation / showOpeningPageAnimation | public static boolean showOpeningPageAnimation = false; |
| 396 | preference-setting / showOpeningPageAnimation | public static boolean showOpeningPageAnimation = false; |
| 406 | preference-setting / showOptionActionButtons | public static boolean showOptionActionButtons = true; |
| 416 | preference-setting / flipTopOptionOrder | public static boolean flipTopOptionOrder = false; |
| 426 | preference-setting / instantSearch | public static boolean instantSearch = true; |
| 436 | keybind / OneConfigKeybind | public static OneConfigKeybind searchKeybind = |
| 436 | preference-setting / searchKeybind | public static OneConfigKeybind searchKeybind = |
| 437 | keybind / OneConfigKeybind | new OneConfigKeybind(new int[] {KEYS.getKeyF()}, null, KeybindUtils.getActionModifier(), 0L, pressed -> true); |
| 437 | keybind / getKeyF | new OneConfigKeybind(new int[] {KEYS.getKeyF()}, null, KeybindUtils.getActionModifier(), 0L, pressed -> true); |
| 449 | preference-setting / searchDistance | public static int searchDistance = 2; |
| 459 | animation / guiOpenAnimation | public static boolean guiOpenAnimation = true; |
| 459 | preference-setting / guiOpenAnimation | public static boolean guiOpenAnimation = true; |
| 469 | animation / guiClosingAnimation | public static boolean guiClosingAnimation = true; |
| 469 | preference-setting / guiClosingAnimation | public static boolean guiClosingAnimation = true; |
| 482 | animation / animationTime | public static float animationTime = 0.6f; |
| 482 | preference-setting / animationTime | public static float animationTime = 0.6f; |
| 492 | animation / showPageAnimations | public static boolean showPageAnimations = true; |
| 492 | preference-setting / showPageAnimations | public static boolean showPageAnimations = true; |
| 502 | preference-setting / enableUISounds | public static boolean enableUISounds = true; |
| 512 | preference-setting / enableUIMenuSounds | public static boolean enableUIMenuSounds = true; |
| 522 | preference-setting / enableUIClickSounds | public static boolean enableUIClickSounds = true; |
| 532 | preference-setting / enableUISliderSounds | public static boolean enableUISliderSounds = true; |
| 542 | preference-setting / enableHudEditorSounds | public static boolean enableHudEditorSounds = true; |
| 552 | preference-setting / enableUIAmbience | public static boolean enableUIAmbience = true; |
| 562 | preference-setting / enableUIMusicDucking | public static boolean enableUIMusicDucking = true; |
| 562 | sound / enableUIMusicDucking | public static boolean enableUIMusicDucking = true; |
| 575 | preference-setting / uiSoundVolume | public static float uiSoundVolume = 1f; |
| 588 | preference-setting / uiAmbienceVolume | public static float uiAmbienceVolume = 1f; |
| 601 | animation / pageAnimationDuration | public static float pageAnimationDuration = 0.3f; |
| 601 | preference-setting / pageAnimationDuration | public static float pageAnimationDuration = 0.3f; |
| 611 | preference-setting / showFirstLaunchMessage | public static boolean showFirstLaunchMessage = true; |
| 621 | preference-setting / profileSwitchToast | public static boolean profileSwitchToast = true; |
| 626 | preference-setting / INSTANCE | public static OneConfigConfig INSTANCE; |
| 638 | keybind / OneConfigKeybind | private static OneConfigKeybind registeredKeybind; |
| 643 | keybind / OneConfigKeybind | private static OneConfigKeybind registeredHudDuplicateKeybind; |
| 648 | keybind / OneConfigKeybind | private static OneConfigKeybind registeredSearchKeybind; |
| 653 | keybind / OneConfigKeybind | private static final AtomicReference<OneConfigKeybind> |
| 655 | keybind / OneConfigKeybind | private static final AtomicReference<OneConfigKeybind> |
| 657 | keybind / OneConfigKeybind | private static final AtomicReference<OneConfigKeybind> |
| 659 | keybind / OneConfigKeybind | private static final AtomicReference<OneConfigKeybind> |
| 661 | keybind / OneConfigKeybind | private static final AtomicReference<OneConfigKeybind> |
| 663 | keybind / OneConfigKeybind | private static final AtomicReference<OneConfigKeybind> |
| 665 | keybind / OneConfigKeybind | private static final AtomicReference<OneConfigKeybind> |
| 667 | keybind / OneConfigKeybind | private static final AtomicReference<OneConfigKeybind> |
| 669 | keybind / OneConfigKeybind | private static final AtomicReference<OneConfigKeybind> |
| 671 | keybind / OneConfigKeybind | private static final AtomicReference<OneConfigKeybind> |
| 673 | keybind / OneConfigKeybind | private static final AtomicReference<OneConfigKeybind> |
| 675 | keybind / OneConfigKeybind | private static final AtomicReference<OneConfigKeybind> |
| 742 | sound / UiSounds.refreshAmbience | UiSounds.refreshAmbience(); |
| 742 | sound / refreshAmbience | UiSounds.refreshAmbience(); |
| 747 | sound / UiSounds.refreshAmbience | UiSounds.refreshAmbience(); |
| 747 | sound / refreshAmbience | UiSounds.refreshAmbience(); |
| 752 | sound / UiSounds.refreshAmbience | UiSounds.refreshAmbience(); |
| 752 | sound / refreshAmbience | UiSounds.refreshAmbience(); |
| 757 | keybind / OneConfigKeybind | "oneConfigKeybind", (OneConfigKeybind kb) -> { |
| 763 | keybind / OneConfigKeybind | "searchKeybind", (OneConfigKeybind kb) -> { |
| 769 | keybind / OneConfigKeybind | "hudDuplicateKeybind", (OneConfigKeybind kb) -> { |
| 775 | keybind / OneConfigKeybind | "hudSettingsKeybind", (OneConfigKeybind kb) -> { |
| 781 | keybind / OneConfigKeybind | "hudVisibilityKeybind", (OneConfigKeybind kb) -> { |
| 787 | keybind / OneConfigKeybind | "hudResetKeybind", (OneConfigKeybind kb) -> { |
| 793 | keybind / OneConfigKeybind | "hudCopyKeybind", (OneConfigKeybind kb) -> { |
| 799 | keybind / OneConfigKeybind | "hudCutKeybind", (OneConfigKeybind kb) -> { |
| 805 | keybind / OneConfigKeybind | "hudPasteKeybind", (OneConfigKeybind kb) -> { |
| 811 | keybind / OneConfigKeybind | "hudDeleteKeybind", (OneConfigKeybind kb) -> { |
| 817 | keybind / OneConfigKeybind | "hudSelectAllKeybind", (OneConfigKeybind kb) -> { |
| 823 | keybind / OneConfigKeybind | "hudLockKeybind", (OneConfigKeybind kb) -> { |
| 829 | keybind / OneConfigKeybind | "hudUndoKeybind", (OneConfigKeybind kb) -> { |
| 835 | keybind / OneConfigKeybind | "hudRedoKeybind", (OneConfigKeybind kb) -> { |
| 841 | keybind / OneConfigKeybind | "hudRedoAltKeybind", (OneConfigKeybind kb) -> { |
| 921 | keybind / OneConfigKeybind | private static void refreshKeybind(OneConfigKeybind src) { |
| 923 | keybind / KeybindManager.unregister | KeybindManager.unregister(registeredKeybind); |
| 929 | keybind / OneConfigKeybind | registeredKeybind = new OneConfigKeybind( |
| 930 | keybind / getKeyCodes | src.getKeyCodes(), |
| 935 | keybind / KeybindManager.register | KeybindManager.register(registeredKeybind); |
| 947 | keybind / KeybindManager.unregister | KeybindManager.unregister(registeredSearchKeybind); |
| 950 | keybind / OneConfigKeybind | OneConfigKeybind src = searchKeybind; |
| 954 | keybind / OneConfigKeybind | registeredSearchKeybind = new OneConfigKeybind( |
| 955 | keybind / getKeyCodes | src.getKeyCodes(), |
| 961 | keybind / KeybindManager.register | KeybindManager.register(registeredSearchKeybind); |
| 972 | keybind / KeybindManager.unregister | KeybindManager.unregister(registeredHudDuplicateKeybind); |
| 975 | keybind / OneConfigKeybind | OneConfigKeybind src = hudDuplicateKeybind; |
| 979 | keybind / OneConfigKeybind | registeredHudDuplicateKeybind = new OneConfigKeybind( |
| 980 | keybind / getKeyCodes | src.getKeyCodes(), |
| 985 | keybind / KeybindManager.register | KeybindManager.register(registeredHudDuplicateKeybind); |
| 995 | keybind / OneConfigKeybind | OneConfigKeybind src, |
| 996 | keybind / OneConfigKeybind | AtomicReference<OneConfigKeybind> slot, |
| 1002 | keybind / OneConfigKeybind | OneConfigKeybind src, |
| 1003 | keybind / OneConfigKeybind | AtomicReference<OneConfigKeybind> slot, |
| 1006 | keybind / OneConfigKeybind | OneConfigKeybind old = slot.getAndSet(null); |
| 1008 | keybind / KeybindManager.unregister | KeybindManager.unregister(old); |
| 1013 | keybind / OneConfigKeybind | OneConfigKeybind keybind = new OneConfigKeybind( |
| 1013 | keybind / OneConfigKeybind | OneConfigKeybind keybind = new OneConfigKeybind( |
| 1014 | keybind / getKeyCodes | src.getKeyCodes(), |
| 1026 | keybind / KeybindManager.register | KeybindManager.register(keybind); |

### muoniumconfig-common/src/main/java/org/polyfrost/oneconfig/internal/ui/PlayerHeadTextureAccess.java

`file-ad31066a62e77c2a1474`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### muoniumconfig-common/src/main/kotlin/androidx/compose/ui/text/platform/FontLoader.kt

`file-cd51155455538dfedf9a`；状态 **pending**；领域 font-language；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 40 | ui-function / load | override fun load(font: Font): Any { |
| 66 | ui-function / resolvePolyText | private fun resolvePolyText(): Any = |
| 74 | ui-function / invoke | private fun invoke( |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/compose/composables/Composables.kt

`file-109c85514562020fe7af`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 13 | composable / PolyBox | @Composable |
| 16 | composable / @Composable callback/type | content: @Composable () -> Unit = {}, |
| 25 | composable / PolyImage | @Composable |
| 36 | composable / PolyRow | @Composable |
| 40 | composable / @Composable callback/type | content: @Composable () -> Unit = {}, |
| 52 | composable / PolyColumn | @Composable |
| 56 | composable / @Composable callback/type | content: @Composable () -> Unit = {}, |
| 68 | composable / PolyText | @Composable |
| 94 | composable / PolyMcText | @Composable |
| 105 | composable / PolyMcText | @Composable |
| 127 | composable / PolyMcText | @Composable |
| 149 | composable / PolySpacer | @Composable |
| 161 | composable / PolyCanvas | @Composable |
| 165 | composable / PolyCanvas | @Composable |
| 181 | composable / PolyRect | @Composable |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/compose/font/PolyFonts.kt

`file-0b5f6968068ed4f10f65`；状态 **pending**；领域 font-language；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 20 | ui-function / Font | fun Font(resource: String, weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal): Font = |
| 27 | ui-function / Font | fun Font(file: File, weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal): Font = |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/compose/font/PolyFontsBytes.kt

`file-d52bb05316e125638944`；状态 **pending**；领域 font-language；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 12 | ui-function / Font | fun Font(identity: String, data: ByteArray, weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal): Font = |
| 15 | ui-function / Font | fun Font(identity: String, getData: () -> ByteArray, weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal): Font = |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/compose/render/FontCheck.kt

`file-dd914236427eea8292a0`；状态 **pending**；领域 font-language；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 7 | ui-function / isReadableFont | internal fun isReadableFont(bytes: ByteArray, at: Int = 0): Boolean { |
| 8 | ui-function / u16 | fun u16(o: Int) = (bytes[o].toInt() and 0xFF shl 8) or (bytes[o + 1].toInt() and 0xFF) |
| 9 | ui-function / u32 | fun u32(o: Int) = (u16(o).toLong() shl 16) or u16(o + 2).toLong() |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/compose/render/FontManager.kt

`file-da2007440a093834cdf4`；状态 **pending**；领域 font-language；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 17 | ui-function / invalidateMetrics | fun invalidateMetrics() { |
| 21 | ui-function / loadIfNeeded | fun loadIfNeeded(name: String, pixelAligned: Boolean = false, bytes: () -> ByteArray): ByteArray = |
| 35 | ui-function / replace | fun replace(name: String, bytes: ByteArray, pixelAligned: Boolean = false) = synchronized(fontSuppliers) { |
| 43 | ui-function / unregister | fun unregister(name: String) = synchronized(fontSuppliers) { |
| 53 | ui-function / restoreBundledFallback | fun restoreBundledFallback(): Boolean { |
| 63 | ui-function / register | fun register(name: String, bytes: ByteArray, pixelAligned: Boolean = false) = synchronized(fontSuppliers) { |
| 71 | ui-function / loadFromResource | fun loadFromResource(path: String, name: String = DEFAULT_KEY, loader: ClassLoader? = null, pixelAligned: Boolean = false): Boolean { |
| 79 | ui-function / setDefault | fun setDefault(name: String) { |
| 84 | ui-function / fontData | fun fontData(name: String): ByteArray? = fontData[name] |
| 86 | ui-function / isPixelAligned | fun isPixelAligned(name: String) = name in pixelFonts |
| 90 | ui-function / loadFromResourceCompat | fun loadFromResourceCompat(path: String, name: String = DEFAULT_KEY, loader: ClassLoader? = null): Boolean = |
| 95 | ui-function / loadDefaultFromResource | fun loadDefaultFromResource(path: String) { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/compose/render/PolyColor.kt

`file-d75a1aa8c1abe0ecac4f`；状态 **pending**；领域 color-gradient；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 42 | ui-function / withAlpha | fun withAlpha(a: Int): PolyColor = PolyColor((argb and 0x00FFFFFF) or ((a.coerceIn(0, 255)) shl 24), chroma, chromaSpeed) |
| 43 | ui-function / withAlpha | fun withAlpha(a: Float): PolyColor = withAlpha((a * 255f).roundToInt()) |
| 45 | ui-function / withRed | fun withRed(r: Int): PolyColor = PolyColor((argb and 0xFF00FFFF.toInt()) or ((r.coerceIn(0, 255)) shl 16), chroma, chromaSpeed) |
| 46 | ui-function / withGreen | fun withGreen(g: Int): PolyColor = PolyColor((argb and 0xFFFF00FF.toInt()) or ((g.coerceIn(0, 255)) shl 8), chroma, chromaSpeed) |
| 47 | ui-function / withBlue | fun withBlue(b: Int): PolyColor = PolyColor((argb and 0xFFFFFF00.toInt()) or (b.coerceIn(0, 255)), chroma, chromaSpeed) |
| 50 | ui-function / withChroma | fun withChroma(chroma: Boolean, speed: Float = chromaSpeed): PolyColor = |
| 57 | ui-function / withGradient | fun withGradient(enabled: Boolean, argb: Int = gradientArgb): PolyColor = |
| 63 | ui-function / multiplyAlpha | fun multiplyAlpha(factor: Float): PolyColor = withAlpha((alpha * factor).roundToInt().coerceIn(0, 255)) |
| 65 | ui-function / lighten | fun lighten(amount: Float): PolyColor { |
| 74 | ui-function / darken | fun darken(amount: Float): PolyColor { |
| 83 | ui-function / lerp | fun lerp(other: PolyColor, t: Float): PolyColor { |
| 85 | ui-function / mix | fun mix(a: Int, b: Int) = (a + (b - a) * f).roundToInt() |
| 96 | ui-function / invert | fun invert(): PolyColor = rgb(255 - red, 255 - green, 255 - blue).withAlpha(alpha) |
| 98 | ui-function / toSkiaColor | fun toSkiaColor(): Int = argb |
| 100 | ui-function / toString | override fun toString(): String = "PolyColor(#%08X)".format(argb.toLong() and 0xFFFFFFFFL) |
| 102 | ui-function / equals | override fun equals(other: Any?): Boolean { |
| 109 | ui-function / hashCode | override fun hashCode(): Int { |
| 141 | ui-function / rgb | fun rgb(r: Int, g: Int, b: Int) = |
| 144 | ui-function / argb | fun argb(a: Int, r: Int, g: Int, b: Int) = |
| 147 | ui-function / rgba | fun rgba(r: Int, g: Int, b: Int, a: Int) = argb(a, r, g, b) |
| 148 | ui-function / hex | fun hex(value: Long) = PolyColor(value.toInt()) |
| 149 | ui-function / hex | fun hex(value: Int) = PolyColor(value) |
| 150 | ui-function / hex | fun hex(value: String): PolyColor { |
| 166 | ui-function / hsv | fun hsv(h: Float, s: Float, v: Float, a: Float = 1f): PolyColor { |
| 187 | ui-function / hsl | fun hsl(h: Float, s: Float, l: Float, a: Float = 1f): PolyColor { |
| 207 | ui-function / lerp | fun lerp(from: PolyColor, to: PolyColor, t: Float) = from.lerp(to, t) |
| 209 | ui-function / chromaArgb | private fun chromaArgb(argb: Int, speed: Float): Int { |
| 217 | ui-function / rgbToHsb | private fun rgbToHsb(red: Int, green: Int, blue: Int): FloatArray { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/compose/render/PolyFont.kt

`file-d9c7faab1a6a8f79cd5c`；状态 **pending**；领域 font-language；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 12 | ui-function / measureTextWidth | fun measureTextWidth(font: PolyFont, text: String): Float |
| 13 | ui-function / metrics | fun metrics(font: PolyFont): Metrics |
| 26 | ui-function / measureTextWidth | fun measureTextWidth(text: String): Float = engine.measureTextWidth(this, text) |
| 30 | ui-function / default | fun default(size: Float) = PolyFont(FontManager.DEFAULT_KEY, size) |
| 34 | ui-function / measureTextWidth | override fun measureTextWidth(font: PolyFont, text: String) = error("PolyFont.engine is not installed") |
| 35 | ui-function / metrics | override fun metrics(font: PolyFont) = error("PolyFont.engine is not installed") |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/compose/runtime/PolyComposeRuntime.kt

`file-0b9590e71ceba2e1477a`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 18 | composable / @Composable callback/type | fun setContent(content: @Composable () -> Unit) { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/config/v1/KtConfig.kt

`file-37d710312e19838d18dd`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 420 | keybind / OneConfigKeybind | def: OneConfigKeybind? = null, |
| 436 | keybind / OneConfigKeybind | OneConfigKeybind::class.java, |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/config/v1/Visualizer.kt

`file-7c0abe46021d143912ae`；状态 **pending**；领域 settings；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 47 | composable / visualize | @Composable |
| 51 | composable / visualize | @Composable override fun visualize(prop: Property<*>) { Visualizer[ButtonVisualizer::class.java]?.visualize(prop) } |
| 55 | composable / visualize | @Composable override fun visualize(prop: Property<*>) { Visualizer[ColorVisualizer::class.java]?.visualize(prop) } |
| 59 | composable / visualize | @Composable override fun visualize(prop: Property<*>) { Visualizer[DropdownVisualizer::class.java]?.visualize(prop) } |
| 63 | composable / visualize | @Composable override fun visualize(prop: Property<*>) { Visualizer[KeybindVisualizer::class.java]?.visualize(prop) } |
| 67 | composable / visualize | @Composable override fun visualize(prop: Property<*>) { Visualizer[InfoVisualizer::class.java]?.visualize(prop) } |
| 71 | composable / visualize | @Composable override fun visualize(prop: Property<*>) { Visualizer[DraggableListVisualizer::class.java]?.visualize(prop) } |
| 75 | composable / visualize | @Composable override fun visualize(prop: Property<*>) { Visualizer[MultiSelectDropdownVisualizer::class.java]?.visualize(prop) } |
| 79 | composable / visualize | @Composable override fun visualize(prop: Property<*>) { Visualizer[NumberVisualizer::class.java]?.visualize(prop) } |
| 83 | composable / visualize | @Composable override fun visualize(prop: Property<*>) { Visualizer[RadioVisualizer::class.java]?.visualize(prop) } |
| 87 | composable / visualize | @Composable override fun visualize(prop: Property<*>) { Visualizer[SliderVisualizer::class.java]?.visualize(prop) } |
| 97 | composable / visualize | @Composable override fun visualize(prop: Property<*>) { Visualizer[RangeSliderVisualizer::class.java]?.visualize(prop) } |
| 104 | composable / visualize | @Composable override fun visualize(prop: Property<*>) { Visualizer[RangeNumberVisualizer::class.java]?.visualize(prop) } |
| 113 | composable / visualize | @Composable override fun visualize(prop: Property<*>) { Visualizer[RangeSliderListVisualizer::class.java]?.visualize(prop) } |
| 120 | composable / visualize | @Composable override fun visualize(prop: Property<*>) { Visualizer[RangeNumberListVisualizer::class.java]?.visualize(prop) } |
| 139 | composable / visualize | @Composable override fun visualize(prop: Property<*>) { Visualizer[InheritableSliderVisualizer::class.java]?.visualize(prop) } |
| 152 | composable / visualize | @Composable override fun visualize(prop: Property<*>) { Visualizer[NumberChainVisualizer::class.java]?.visualize(prop) } |
| 156 | composable / visualize | @Composable override fun visualize(prop: Property<*>) { Visualizer[SwitchVisualizer::class.java]?.visualize(prop) } |
| 160 | composable / visualize | @Composable override fun visualize(prop: Property<*>) { Visualizer[CheckboxVisualizer::class.java]?.visualize(prop) } |
| 164 | composable / visualize | @Composable override fun visualize(prop: Property<*>) { Visualizer[TextVisualizer::class.java]?.visualize(prop) } |
| 168 | composable / visualize | @Composable override fun visualize(prop: Property<*>) { Visualizer[TextListVisualizer::class.java]?.visualize(prop) } |
| 178 | composable / visualize | @Composable override fun visualize(prop: Property<*>) { Visualizer[ItemListVisualizer::class.java]?.visualize(prop) } |
| 188 | composable / visualize | @Composable override fun visualize(prop: Property<*>) { Visualizer[EffectListVisualizer::class.java]?.visualize(prop) } |
| 192 | composable / visualize | @Composable override fun visualize(prop: Property<*>) { Visualizer[FileVisualizer::class.java]?.visualize(prop) } |
| 196 | composable / visualize | @Composable override fun visualize(prop: Property<*>) { Visualizer[FileListVisualizer::class.java]?.visualize(prop) } |
| 200 | composable / visualize | @Composable override fun visualize(prop: Property<*>) { Visualizer[ColorListVisualizer::class.java]?.visualize(prop) } |
| 204 | composable / visualize | @Composable override fun visualize(prop: Property<*>) { Visualizer[NumberListVisualizer::class.java]?.visualize(prop) } |
| 208 | composable / visualize | @Composable override fun visualize(prop: Property<*>) { Visualizer[SliderListVisualizer::class.java]?.visualize(prop) } |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/config/v1/dsl/ConfigDSL.kt

`file-c84e85e22f5f1d83d6d9`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 56 | keybind / OneConfigKeybind | fun keybind(default: OneConfigKeybind?): Prop<OneConfigKeybind> = put(Prop(default, OneConfigKeybind::class.java), Visualizer.KeybindVisualizer()) |
| 56 | keybind / OneConfigKeybind | fun keybind(default: OneConfigKeybind?): Prop<OneConfigKeybind> = put(Prop(default, OneConfigKeybind::class.java), Visualizer.KeybindVisualizer()) |
| 56 | keybind / OneConfigKeybind | fun keybind(default: OneConfigKeybind?): Prop<OneConfigKeybind> = put(Prop(default, OneConfigKeybind::class.java), Visualizer.KeybindVisualizer()) |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/hud/v1/Hud.kt

`file-fb362b23608d14fd107d`；状态 **pending**；领域 hud-editor；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 从实际宿主入口触发，核对完整状态变化、交互、退出及重进后的结果。 定位类型: peripheral-ui 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 85 | ui-function / toAlign | fun toAlign(): PolyAlign? = when (this) { |
| 100 | ui-function / of | fun of(align: PolyAlign): HudAnchor = when (align) { |
| 132 | ui-function / computeValue | override fun computeValue(type: Class<*>): Boolean = GEOMETRY_GETTERS.any { |
| 140 | ui-function / computeValue | override fun computeValue(type: Class<*>): Boolean = |
| 145 | ui-function / computeValue | override fun computeValue(type: Class<*>): Boolean = runCatching { |
| 190 | ui-function / measureNaturalContentSize | protected fun measureNaturalContentSize(): Pair<Float, Float>? { |
| 213 | ui-function / reseedStaticSizeIfNeeded | fun reseedStaticSizeIfNeeded() { |
| 221 | ui-function / reseedStaticWidth | fun reseedStaticWidth() { |
| 233 | ui-function / reseedStaticHeight | fun reseedStaticHeight() { |
| 255 | ui-function / captureStaticSizeDefaults | fun captureStaticSizeDefaults(force: Boolean = false) { |
| 272 | ui-function / capturePositionDefaults | fun capturePositionDefaults() { |
| 353 | ui-function / frameMinimumSize | fun frameMinimumSize(): Pair<Float, Float> { |
| 380 | ui-function / fracX | private fun fracX(anchor: HudAnchor): Float = when (anchor) { |
| 391 | ui-function / fracY | private fun fracY(anchor: HudAnchor): Float = when (anchor) { |
| 406 | ui-function / pinnedFracX | private fun pinnedFracX(point: HudAnchor): Float = fracX(pinnedCorner ?: point) |
| 408 | ui-function / pinnedFracY | private fun pinnedFracY(point: HudAnchor): Float = fracY(pinnedCorner ?: point) |
| 411 | ui-function / anchorPointX | fun anchorPointX(point: HudAnchor): Float = x + fracX(point) * scaledWidth |
| 414 | ui-function / anchorPointY | fun anchorPointY(point: HudAnchor): Float = y + fracY(point) * scaledHeight |
| 416 | ui-function / sectionX | private fun sectionX(sw: Float): Float { |
| 425 | ui-function / sectionY | private fun sectionY(sh: Float): Float { |
| 438 | ui-function / layoutX | internal fun layoutX(sw: Float): Float { |
| 451 | ui-function / layoutY | internal fun layoutY(sh: Float): Float { |
| 468 | ui-function / rawX | private fun rawX(a: ResolvedAnchor?, w: Float): Float { |
| 480 | ui-function / rawY | private fun rawY(a: ResolvedAnchor?, h: Float): Float { |
| 510 | ui-function / keepVisibleX | private fun keepVisibleX(absX: Float, w: Float): Float { |
| 517 | ui-function / keepVisibleY | private fun keepVisibleY(absY: Float, h: Float): Float { |
| 524 | ui-function / updateRelativeX | protected open fun updateRelativeX(absX: Float) { |
| 550 | ui-function / updateRelativeY | protected open fun updateRelativeY(absY: Float) { |
| 574 | ui-function / setAbsolutePosition | fun setAbsolutePosition(absX: Float, absY: Float) { |
| 604 | ui-function / onEditorDragStart | open fun onEditorDragStart() {} |
| 606 | ui-function / onEditorDragEnd | open fun onEditorDragEnd() {} |
| 619 | ui-function / applyEditorWidth | open fun applyEditorWidth(width: Float) { |
| 633 | peripheral-ui / pinCorner | open fun pinCorner(pin: HudAnchor) { |
| 633 | ui-function / pinCorner | open fun pinCorner(pin: HudAnchor) { |
| 644 | ui-function / unpinCorner | open fun unpinCorner() { |
| 649 | ui-function / settlePin | open fun settlePin() { |
| 674 | peripheral-ui / setGrowthAnchorKeepingPosition | fun setGrowthAnchorKeepingPosition(anchor: HudAnchor) { |
| 674 | ui-function / setGrowthAnchorKeepingPosition | fun setGrowthAnchorKeepingPosition(anchor: HudAnchor) { |
| 716 | peripheral-ui / setSelfAnchorPointKeepingPosition | fun setSelfAnchorPointKeepingPosition(point: HudAnchor) { |
| 716 | ui-function / setSelfAnchorPointKeepingPosition | fun setSelfAnchorPointKeepingPosition(point: HudAnchor) { |
| 762 | ui-function / withOffset | fun withOffset(offset: Float) = if (offset == this.offset) this else MergeLink(parent, selfPoint, targetPoint, offset) |
| 779 | ui-function / mergeLink | private fun mergeLink(axis: MergeAxis) = if (axis == MergeAxis.X) mergeLinkX else mergeLinkY |
| 785 | ui-function / resolvedAnchor | private fun resolvedAnchor(axis: MergeAxis): ResolvedAnchor? { |
| 822 | ui-function / applyMergeLink | internal fun applyMergeLink(parent: Hud, selfPoint: HudAnchor, targetPoint: HudAnchor, axis: MergeAxis) { |
| 844 | ui-function / clearMergeLink | internal fun clearMergeLink() = clearMergeLinks(clearX = true, clearY = true) |
| 848 | ui-function / dropMergeLinks | fun dropMergeLinks() { |
| 854 | ui-function / clearMergeLinks | internal fun clearMergeLinks(clearX: Boolean, clearY: Boolean) { |
| 880 | peripheral-ui / anchorTo | fun anchorTo(target: Hud, point: HudAnchor, self: HudAnchor = HudAnchor.Auto) { |
| 880 | ui-function / anchorTo | fun anchorTo(target: Hud, point: HudAnchor, self: HudAnchor = HudAnchor.Auto) { |
| 896 | peripheral-ui / clearAnchor | fun clearAnchor() { |
| 896 | ui-function / clearAnchor | fun clearAnchor() { |
| 908 | ui-function / anchorChainContains | fun anchorChainContains(hud: Hud): Boolean { |
| 974 | ui-function / getPoppinsFontName | fun getPoppinsFontName(): String { |
| 1097 | ui-function / contentChanged | protected fun contentChanged() { |
| 1111 | ui-function / bumpRevision | private fun bumpRevision() { |
| 1115 | ui-function / put | private fun <T> MutableState<T>.put(v: T) { |
| 1127 | ui-function / walkChain | private fun walkChain() { |
| 1131 | ui-function / visit | fun visit(parent: Hud?) { |
| 1177 | ui-function / observeGeometryRevision | open fun observeGeometryRevision() { |
| 1180 | ui-function / externalParentMoved | private fun externalParentMoved(): Boolean { |
| 1193 | ui-function / chainChanged | private fun chainChanged(): Boolean { |
| 1202 | ui-function / guiScaleInput | private fun guiScaleInput(): Float = |
| 1209 | ui-function / refreshFrameGeometry | private fun refreshFrameGeometry(): Boolean { |
| 1279 | ui-function / refreshFrameFlags | private fun refreshFrameFlags(): Boolean { |
| 1316 | ui-function / addToInitQueue | override fun addToInitQueue() {} |
| 1335 | ui-function / setVisible | internal fun setVisible(visible: Boolean) { |
| 1359 | composable / Content | @Composable |
| 1360 | ui-function / Content | abstract fun Content() |
| 1363 | ui-function / make | fun make(with: Tree? = null): Hud { |
| 1474 | ui-function / addCallbacks | protected open fun addCallbacks(tree: Tree) {} |
| 1475 | ui-function / addToSerialized | protected open fun addToSerialized(tree: Tree) {} |
| 1477 | ui-function / updateWhenChanged | protected fun updateWhenChanged(optionName: String) { |
| 1482 | ui-function / updateAndRecalculate | fun updateAndRecalculate() { |
| 1486 | ui-function / setup | open fun setup() {} |
| 1487 | ui-function / update | abstract fun update(): Boolean |
| 1488 | ui-function / updateFrequency | open fun updateFrequency(): Long = -1L |
| 1489 | ui-function / defaultPosition | open fun defaultPosition(): Pair<Float, Float> = 10f to 10f |
| 1497 | ui-function / shouldShow | open fun shouldShow(): Boolean = true |
| 1499 | ui-function / showByDefault | open fun showByDefault(): Boolean = false |
| 1500 | ui-function / hasBackground | open fun hasBackground(): Boolean = true |
| 1519 | ui-function / canMergeBackground | open fun canMergeBackground(): Boolean = this is TextHud |
| 1528 | ui-function / hudBackground | fun hudBackground(modifier: PolyModifier = PolyModifier): PolyModifier = |
| 1535 | ui-function / backgroundColor | open fun backgroundColor(): PolyColor? = null |
| 1536 | ui-function / multipleInstancesAllowed | open fun multipleInstancesAllowed(): Boolean = true |
| 1537 | ui-function / minimumSize | open fun minimumSize(): Pair<Float, Float> = 0f to 0f |
| 1549 | ui-function / deletable | open fun deletable(): Boolean = true |
| 1554 | ui-function / canDelete | fun canDelete(): Boolean = deletable() && (isReal \|\| this in HudManager.activeInstances) |
| 1565 | ui-function / detachTree | internal fun detachTree() { |
| 1570 | ui-function / restoreCapturedDefaults | internal fun restoreCapturedDefaults() { |
| 1574 | ui-function / remove | open fun remove() {} |
| 1578 | ui-function / clone | override fun clone(): Hud = (super.clone() as Hud).apply { |
| 1679 | ui-function / sanitizeHudCapturedDefaults | private fun sanitizeHudCapturedDefaults(tree: Tree) { |
| 1697 | ui-function / toString | override fun toString() = name |
| 1698 | ui-function / hashCode | override fun hashCode() = id.toInt() |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/hud/v1/HudBackgroundMerge.kt

`file-b8495714bb040f246668`；状态 **pending**；领域 hud-editor；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

从实际宿主入口触发，核对完整状态变化、交互、退出及重进后的结果。 定位类型: peripheral-ui 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 54 | peripheral-ui / HudBackgroundMerge | internal object HudBackgroundMerge { |
| 98 | ui-function / draw | fun draw(ctx: RenderContext) { |
| 103 | ui-function / computeGroups | fun computeGroups(huds: List<Hud>, refWidth: Float, refHeight: Float): List<Group> { |
| 153 | ui-function / canMerge | fun canMerge(hud: Hud): Boolean = |
| 159 | ui-function / sameStyle | fun sameStyle(a: Hud, b: Hud): Boolean = |
| 166 | ui-function / axisLinks | private fun axisLinks( |
| 180 | ui-function / reaches | fun reaches(from: Int, target: Hud): Boolean { |
| 233 | ui-function / contactPoints | private fun contactPoints(child: Item, parent: Item): Pair<HudAnchor, HudAnchor> { |
| 268 | ui-function / alignedSide | private fun alignedSide(aMin: Float, aMax: Float, bMin: Float, bMax: Float): Float = when { |
| 274 | ui-function / anchorAt | private fun anchorAt(fx: Float, fy: Float): HudAnchor = when (fy) { |
| 308 | ui-function / sameStyle | fun sameStyle(other: Item): Boolean = sameStyle(hud, other.hud) |
| 311 | ui-function / contactPins | private fun contactPins(a: Item, b: Item): Int { |
| 335 | ui-function / nests | private fun nests(aMin: Float, aMax: Float, bMin: Float, bMax: Float): Boolean = |
| 339 | ui-function / overlap | private fun overlap(aMin: Float, aMax: Float, bMin: Float, bMax: Float): Float = |
| 342 | ui-function / union | private fun union(parent: IntArray, a: Int, b: Int) { |
| 348 | ui-function / find | private fun find(parent: IntArray, i: Int): Int { |
| 357 | ui-function / outlines | fun outlines(rects: List<FloatArray>): List<FloatArray> = trace(snapEdges(rects)) |
| 359 | ui-function / snapEdges | private fun snapEdges(rects: List<FloatArray>): List<FloatArray> { |
| 371 | ui-function / snapMap | private fun snapMap(values: List<Float>): Map<Float, Float> { |
| 386 | ui-function / trace | private fun trace(rects: List<FloatArray>): List<FloatArray> { |
| 402 | ui-function / cov | fun cov(i: Int, j: Int) = i in 0 until nx && j in 0 until ny && covered[i][j] |
| 406 | ui-function / node | fun node(i: Int, j: Int) = i * (ny + 1) + j |
| 407 | ui-function / edge | fun edge(i0: Int, j0: Int, i1: Int, j1: Int) { |
| 443 | ui-function / toCoordinates | private fun toCoordinates(points: List<Int>, xs: List<Float>, ys: List<Float>, ny: Int): FloatArray { |
| 462 | ui-function / emitLoop | private fun emitLoop(pb: PolyPath, loop: FloatArray, radius: Float) { |
| 465 | ui-function / px | fun px(k: Int) = loop[(k % n) * 2] |
| 466 | ui-function / py | fun py(k: Int) = loop[(k % n) * 2 + 1] |
| 487 | ui-function / layoutKey | fun layoutKey(huds: List<Hud>): Int { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/hud/v1/HudManager.kt

`file-c5d69b7f5eba36ad778f`；状态 **pending**；领域 hud-editor；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

从实际宿主入口触发，核对完整状态变化、交互、退出及重进后的结果。 定位类型: peripheral-ui 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 57 | ui-function / isFatalHudFailure | private fun Throwable.isFatalHudFailure(): Boolean = this is VirtualMachineError \|\| this is ThreadDeath |
| 95 | ui-function / onProfileChanged | override fun onProfileChanged(newProfile: String) { |
| 141 | ui-function / noteLayoutArranged | internal fun noteLayoutArranged() { |
| 201 | ui-function / invalidate | fun invalidate() { |
| 236 | peripheral-ui / HudBackgroundMerge.Group | private var mergedDraw: Triple<List<HudBackgroundMerge.Group>, Any, Any>? = null |
| 255 | peripheral-ui / HudBackgroundMerge.Group | private var frameGroups: List<HudBackgroundMerge.Group> = emptyList() |
| 271 | ui-function / registryProperty | private fun registryProperty() = Properties.simple( |
| 277 | ui-function / loadRegistry | private fun loadRegistry() { |
| 295 | ui-function / saveRegistry | private fun saveRegistry() { |
| 306 | ui-function / markProviderKnown | fun markProviderKnown(hud: Hud) { |
| 319 | ui-function / instances | fun instances(): List<Hud> = activeInstances.toList() |
| 326 | ui-function / getText | override fun getText(): String = "mmrp\nmeow" |
| 341 | ui-function / addRegistrationListener | fun addRegistrationListener(listener: Runnable) { |
| 345 | ui-function / notifyRegistrationChanged | private fun notifyRegistrationChanged() = registrationListeners.forEach(Runnable::run) |
| 348 | ui-function / register | fun register(hud: Hud) { |
| 373 | ui-function / register | fun register(vararg huds: Hud) { |
| 378 | ui-function / register | fun register(hud: Hud, configId: String) { |
| 395 | ui-function / register | fun register(hud: Hud, configId: String, icon: String) { |
| 401 | ui-function / iconFor | fun iconFor(configId: String): String? = hudIcons[configId] |
| 403 | ui-function / providers | fun providers(): Collection<Hud> = hudProviders.values + idKeyedProviders.values |
| 405 | ui-function / unregister | fun <T : Hud> unregister(hud: T, removeActiveInstances: Boolean = false, delete: Boolean = false): ArrayList<T>? { |
| 431 | ui-function / getHudsOfType | fun <T : Hud> getHudsOfType(hudClass: Class<T>): ArrayList<T> { |
| 442 | ui-function / hasHudOfType | fun hasHudOfType(hudClass: Class<out Hud>): Boolean = activeInstances.any { it::class.java == hudClass } |
| 451 | ui-function / hasHudWithId | fun hasHudWithId(hudId: String): Boolean = activeInstances.any { it.id == hudId } |
| 453 | ui-function / getProvider | fun getProvider(hudClass: Class<out Hud>): Hud? = hudProviders[hudClass] |
| 456 | ui-function / instanceById | fun instanceById(id: String): Hud? { |
| 463 | ui-function / toggleAllHuds | fun toggleAllHuds(hud: Hud, hidden: Boolean) { |
| 471 | ui-function / removeHud | fun removeHud(hud: Hud, delete: Boolean = false) { |
| 477 | ui-function / disposeHudLogging | private fun disposeHudLogging(hud: Hud, delete: Boolean) { |
| 486 | ui-function / disposeHud | private fun disposeHud(hud: Hud, delete: Boolean) { |
| 489 | ui-function / cleanup | fun cleanup(action: () -> Unit) { |
| 510 | peripheral-ui / clearAnchor | if (it.anchorTargetId == gone) cleanup { it.clearAnchor() } |
| 532 | ui-function / screenBounds | private fun screenBounds(hud: Hud): FloatArray? { |
| 547 | ui-function / encloses | private fun encloses(outer: FloatArray, inner: FloatArray): Boolean = |
| 553 | ui-function / zOrderedInstances | fun zOrderedInstances(bounds: (Hud) -> FloatArray? = ::screenBounds): List<Hud> = zOrdered(activeInstances, bounds) |
| 555 | ui-function / zOrdered | private fun zOrdered(list: List<Hud>, bounds: (Hud) -> FloatArray?): List<Hud> { |
| 572 | ui-function / updateAndAdvance | private fun updateAndAdvance() { |
| 589 | ui-function / layout | private fun layout(hud: Hud, screenWidth: Float, screenHeight: Float, scale: Float): RootNode { |
| 615 | ui-function / updateIfDue | fun updateIfDue(hud: Hud) { |
| 633 | ui-function / layoutOnce | private fun layoutOnce(hud: Hud, screenWidth: Float, screenHeight: Float, scale: Float): RootNode { |
| 639 | ui-function / shouldDraw | private fun shouldDraw(hud: Hud): Boolean { |
| 644 | ui-function / keepsBackgroundOnly | private fun keepsBackgroundOnly(hud: Hud): Boolean = |
| 648 | ui-function / isShown | private fun isShown(hud: Hud): Boolean { |
| 662 | ui-function / liveRedraw | private fun liveRedraw(hud: Hud): Boolean = !hud.redrawOverridden && lastDraw(hud) != null |
| 664 | ui-function / lastDraw | private fun lastDraw(hud: Hud): HudDraw? = if (hud.lastDrawRender == renderId - 1) hud.lastDraw else null |
| 666 | ui-function / stored | private fun stored(hud: Hud, draw: HudDraw) { |
| 674 | ui-function / collectFrameOrder | private fun collectFrameOrder(): Boolean { |
| 724 | ui-function / prepareFrame | private inline fun prepareFrame( |
| 764 | ui-function / beginFrame | fun beginFrame(screenWidth: Float, screenHeight: Float, beforeDirtyCheck: Runnable? = null, alwaysRender: Boolean = false, liveChroma: Boolean = false): Boolean { |
| 789 | ui-function / layoutAll | private fun layoutAll(huds: List<Hud>, screenWidth: Float, screenHeight: Float, scale: Float) { |
| 807 | ui-function / updateBackgroundGroups | private fun updateBackgroundGroups(huds: List<Hud>, screenWidth: Float, screenHeight: Float, scale: Float) { |
| 825 | peripheral-ui / HudBackgroundMerge.layoutKey | val key = HudBackgroundMerge.layoutKey(huds) |
| 836 | peripheral-ui / HudBackgroundMerge.computeGroups | frameGroups = HudBackgroundMerge.computeGroups(mergeable, refW, refH) |
| 888 | ui-function / setMergeExclusions | fun setMergeExclusions(huds: Collection<Hud>) { |
| 907 | ui-function / isMergeExcluded | private fun isMergeExcluded(hud: Hud): Boolean = mergeExclusions.any { it === hud } |
| 912 | ui-function / mergeGroupOf | fun mergeGroupOf(hud: Hud): List<Hud> = |
| 917 | peripheral-ui / HudBackgroundMerge.canMerge | fun isMergeCandidate(hud: Hud): Boolean = HudBackgroundMerge.canMerge(hud) |
| 917 | ui-function / isMergeCandidate | fun isMergeCandidate(hud: Hud): Boolean = HudBackgroundMerge.canMerge(hud) |
| 921 | peripheral-ui / HudBackgroundMerge.sameStyle | fun backgroundStylesMatch(a: Hud, b: Hud): Boolean = HudBackgroundMerge.sameStyle(a, b) |
| 921 | ui-function / backgroundStylesMatch | fun backgroundStylesMatch(a: Hud, b: Hud): Boolean = HudBackgroundMerge.sameStyle(a, b) |
| 931 | ui-function / updateMergeLinks | private fun updateMergeLinks() { |
| 957 | ui-function / drawMergedBackgrounds | fun drawMergedBackgrounds(ctx: RenderContext) { |
| 967 | ui-function / frameKey | private fun frameKey(): Long { |
| 985 | ui-function / orderedForRender | private fun orderedForRender(): List<Hud> { |
| 1041 | ui-function / polledUnchanged | private fun polledUnchanged(): Boolean { |
| 1049 | ui-function / drawnBounds | private fun drawnBounds(hud: Hud): FloatArray? = if (hud in frameDrawn) screenBounds(hud) else null |
| 1051 | ui-function / sameBound | private fun Float.sameBound(other: Float): Boolean = |
| 1055 | ui-function / prepare | fun prepare(screenWidth: Float, screenHeight: Float) { |
| 1070 | ui-function / render | fun render(ctx: RenderContext, screenWidth: Float, screenHeight: Float, volatileCtx: RenderContext? = null) { |
| 1188 | ui-function / splitLayers | private fun splitLayers(): Boolean { |
| 1216 | ui-function / drawHud | private fun drawHud(hud: Hud, root: RootNode, ctx: RenderContext) { |
| 1227 | ui-function / toggleEditor | fun toggleEditor() { |
| 1232 | ui-function / openEditor | fun openEditor() { |
| 1240 | ui-function / closeEditor | fun closeEditor() { |
| 1251 | ui-function / onEditorScreenAdded | fun onEditorScreenAdded() { |
| 1256 | ui-function / onEditorScreenRemoved | fun onEditorScreenRemoved() { |
| 1271 | ui-function / initialize | fun initialize() { |
| 1281 | ui-function / setProfileReloadDispatcher | fun setProfileReloadDispatcher(dispatcher: Consumer<Runnable>) { |
| 1285 | ui-function / teardownForProfile | private fun teardownForProfile(profile: String) { |
| 1315 | ui-function / restoreProviderDefaults | private fun restoreProviderDefaults() { |
| 1327 | ui-function / drainProfileReload | private fun drainProfileReload() { |
| 1349 | ui-function / applyPendingProfileReload | private fun applyPendingProfileReload() { |
| 1359 | ui-function / gatherHudTrees | private fun gatherHudTrees(): Collection<Tree> = try { |
| 1367 | ui-function / migratePositions | private fun migratePositions(screenWidth: Float, screenHeight: Float) { |
| 1386 | ui-function / loadFromActiveProfile | private fun loadFromActiveProfile(prefetched: Collection<Tree>? = null) { |
| 1399 | ui-function / loadHuds | private fun loadHuds(now: Long, prefetched: Collection<Tree>?) { |
| 1406 | ui-function / rollback | fun rollback(candidate: Hud?) { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/hud/v1/HudResize.kt

`file-3505bd587e1e66434f88`；状态 **pending**；领域 hud-editor；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

该文件支撑 hud-editor；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/hud/v1/LegacyHudMarker.kt

`file-619ae2e87f94bb308413`；状态 **pending**；领域 hud-editor；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

该文件支撑 hud-editor；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/hud/v1/OneConfigHudCompat.kt

`file-803a238941b0920969dc`；状态 **pending**；领域 hud-editor；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 从实际宿主入口触发，核对完整状态变化、交互、退出及重进后的结果。 定位类型: peripheral-ui 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 15 | composable / Content | @Composable |
| 16 | ui-function / Content | override fun Content() { |
| 41 | ui-function / fault | private fun fault(member: String, error: Throwable) { |
| 51 | ui-function / reportTransient | private fun reportTransient(member: String, error: Throwable) { |
| 56 | ui-function / guard | private inline fun <T> guard(member: String, fallback: T, block: () -> T): T { |
| 87 | ui-function / update | override fun update(): Boolean = false |
| 88 | ui-function / multipleInstancesAllowed | override fun multipleInstancesAllowed(): Boolean = false |
| 90 | ui-function / deletable | override fun deletable(): Boolean = faulted |
| 118 | ui-function / sizeW | private fun sizeW(): Float { |
| 126 | ui-function / sizeH | private fun sizeH(): Float { |
| 141 | ui-function / noteGeometry | private fun noteGeometry(w: Float?, h: Float?) { |
| 161 | ui-function / bumpGeometryRevision | internal fun bumpGeometryRevision() { |
| 165 | ui-function / observeGeometryRevision | override fun observeGeometryRevision() { |
| 209 | ui-function / applyEditorWidth | override fun applyEditorWidth(width: Float) { |
| 218 | peripheral-ui / pinCorner | override fun pinCorner(pin: HudAnchor) { |
| 218 | ui-function / pinCorner | override fun pinCorner(pin: HudAnchor) { |
| 223 | ui-function / unpinCorner | override fun unpinCorner() { pin = null } |
| 224 | ui-function / settlePin | override fun settlePin() {} |
| 226 | ui-function / keepPinned | private inline fun keepPinned(change: () -> Unit) { |
| 233 | ui-function / updateRelativeX | override fun updateRelativeX(absX: Float) { x = absX } |
| 234 | ui-function / updateRelativeY | override fun updateRelativeY(absY: Float) { y = absY } |
| 236 | ui-function / onEditorDragStart | override fun onEditorDragStart() { |
| 240 | ui-function / onEditorDragEnd | override fun onEditorDragEnd() { |
| 249 | ui-function / linkedPropertiesGuarded | fun linkedPropertiesGuarded(): List<Property<*>> { |
| 255 | ui-function / saveWrapper | private fun saveWrapper() { |
| 265 | ui-function / trackPlacementPerProfile | fun trackPlacementPerProfile(tree: Tree, placementPerProfile: Boolean = true) { |
| 279 | ui-function / placementProperty | private fun placementProperty( |
| 296 | ui-function / excludeFromSnapshots | private fun excludeFromSnapshots(tree: Tree) { |
| 332 | ui-function / onDragStart | fun onDragStart() {} |
| 334 | ui-function / onDragEnd | fun onDragEnd() {} |
| 336 | ui-function / linkedProperties | fun linkedProperties(): List<Property<*>> = emptyList() |
| 338 | ui-function / save | fun save() {} |
| 340 | ui-function / register | fun register() { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/hud/v1/TextHud.kt

`file-c01cc6f4e14e1832ad0c`；状态 **pending**；领域 hud-editor；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 81 | ui-function / casedText | private fun casedText(case: Int): String = when (case) { |
| 87 | composable / Content | @Composable |
| 88 | ui-function / Content | override fun Content() { |
| 150 | ui-function / concat | open fun concat(prefix: String, value: String?, suffix: String): String { |
| 163 | ui-function / decorate | protected fun decorate(text: String): String = |
| 171 | ui-function / update | override fun update(): Boolean { |
| 188 | ui-function / minimumSize | override fun minimumSize(): Pair<Float, Float> = |
| 191 | ui-function / setup | override fun setup() { |
| 203 | ui-function / getText | protected abstract fun getText(): String? |
| 205 | ui-function / clone | override fun clone(): Hud = (super.clone() as TextHud).also { |
| 216 | ui-function / getText | override fun getText() = it |
| 217 | ui-function / setup | override fun setup() { |
| 253 | ui-function / updateFrequency | override fun updateFrequency(): Long { |
| 267 | ui-function / setup | override fun setup() { |
| 276 | ui-function / getText | override fun getText(): String? = formatter?.let { LocalDateTime.now().format(it) } |
| 278 | ui-function / clone | override fun clone(): Hud = (super.clone() as DateTime).also { |
| 304 | ui-function / measureContent | override fun measureContent() { |
| 317 | ui-function / render | override fun render(ctx: RenderContext) { |
| 327 | ui-function / draw | private fun draw(ctx: RenderContext, s: PoppinsTextSpec, lines: List<String>, x: Float, y: Float) { |
| 356 | ui-function / computeValue | override fun computeValue(type: Class<*>): Boolean = runCatching { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/notifications/v1/Notification.kt

`file-d5eac8aee88062d00714`；状态 **pending**；领域 notifications；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 139 | ui-function / progressOrNull | fun progressOrNull(): Float? { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/notifications/v1/NotificationTheme.kt

`file-0adfe47da279bfa53348`；状态 **pending**；领域 notifications；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 78 | ui-function / accentFor | fun accentFor(type: NotificationType): PolyColor = when (type) { |
| 86 | ui-function / set | fun set( |
| 117 | ui-function / useIconFolder | fun useIconFolder(iconFolder: String?) { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/notifications/v1/NotificationToasts.kt

`file-4b6dd80181f0397be6bf`；状态 **pending**；领域 notifications；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 从实际宿主入口触发，核对完整状态变化、交互、退出及重进后的结果。 定位类型: peripheral-ui 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 87 | composable / NotificationToasts | @Composable |
| 88 | peripheral-ui / NotificationToasts | fun NotificationToasts() { |
| 88 | ui-function / NotificationToasts | fun NotificationToasts() { |
| 98 | peripheral-ui / NotificationsManager.active | for (notification in NotificationsManager.active) { |
| 105 | composable / Toast | @Composable |
| 106 | ui-function / Toast | private fun Toast(notification: Notification) { |
| 115 | ui-function / sampleProgress | fun sampleProgress(): Float { |
| 121 | animation / withFrameNanos | val enterStart = withFrameNanos { it } |
| 123 | animation / withFrameNanos | val now = withFrameNanos { it } |
| 135 | animation / withFrameNanos | withFrameNanos { } |
| 137 | animation / withFrameNanos | val lingerStart = withFrameNanos { it } |
| 138 | animation / withFrameNanos | while (withFrameNanos { it } - lingerStart < PROGRESS_LINGER_NANOS && !notification.dismissRequested) { } |
| 141 | animation / withFrameNanos | while (!notification.dismissRequested) withFrameNanos { } |
| 146 | animation / withFrameNanos | var last = withFrameNanos { it } |
| 148 | animation / withFrameNanos | val now = withFrameNanos { it } |
| 158 | animation / withFrameNanos | val exitStart = withFrameNanos { it } |
| 160 | animation / withFrameNanos | val now = withFrameNanos { it } |
| 165 | peripheral-ui / NotificationsManager.dismiss | NotificationsManager.dismiss(notification) |
| 212 | ui-function / quantize | private fun quantize(value: Float): Float { |
| 217 | composable / ProgressBar | @Composable |
| 218 | ui-function / ProgressBar | private fun ProgressBar(progress: () -> Float) { |
| 233 | composable / ActionButton | @Composable |
| 234 | ui-function / ActionButton | private fun ActionButton(notification: Notification, action: NotificationAction) { |
| 235 | peripheral-ui / ToastInput.hoveredAction | val hovered = ToastInput.hoveredAction === action |
| 249 | composable / ToastIcon | @Composable |
| 250 | ui-function / ToastIcon | private fun ToastIcon(notification: Notification) { |
| 274 | ui-function / messageFont | private fun messageFont(): PolyFont = NotificationTheme.fontBody?.let { PolyFont(it, MESSAGE_SIZE) } ?: PolyFont.default(MESSAGE_SIZE) |
| 276 | ui-function / wrapText | private fun wrapText(text: String, maxWidth: Float, font: PolyFont): List<String> { |
| 294 | ui-function / easeOutCubic | private fun easeOutCubic(t: Float): Float { |
| 299 | ui-function / easeInCubic | private fun easeInCubic(t: Float): Float = t * t * t |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/notifications/v1/Notifications.kt

`file-39af14cf42eea36e0df3`；状态 **pending**；领域 notifications；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

从实际宿主入口触发，核对完整状态变化、交互、退出及重进后的结果。 定位类型: peripheral-ui 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 66 | ui-function / send | fun send( |
| 82 | ui-function / sendSkia | fun sendSkia( |
| 96 | ui-function / sendSkia | fun sendSkia(title: String, message: String, type: NotificationType, icon: Image?): Notification = |
| 102 | ui-function / sendSkia | fun sendSkia(title: String, message: String, type: NotificationType, icon: Image?, duration: Float): Notification = |
| 108 | ui-function / sendSkia | fun sendSkia(title: String, message: String, type: NotificationType, icon: Image?, duration: Float, progress: Callable<Float>?): Notification = |
| 114 | ui-function / sendSkia | fun sendSkia(title: String, message: String, type: NotificationType, icon: Image?, duration: Float, progress: Callable<Float>?, onClick: Runnable?): Notification = |
| 117 | ui-function / push | private fun push(notification: Notification): Notification { |
| 118 | peripheral-ui / NotificationsManager.push | NotificationsManager.push(notification) |
| 125 | ui-function / info | fun info(title: String, message: String, duration: Float = Notification.DEFAULT_DURATION): Notification = |
| 130 | ui-function / success | fun success(title: String, message: String, duration: Float = Notification.DEFAULT_DURATION): Notification = |
| 135 | ui-function / error | fun error(title: String, message: String, duration: Float = Notification.DEFAULT_DURATION): Notification = |
| 145 | ui-function / progress | fun progress(title: String, message: String, progress: Callable<Float>): Notification = |
| 149 | peripheral-ui / NotificationsManager.dismiss | fun dismiss(notification: Notification) = NotificationsManager.dismiss(notification) |
| 149 | ui-function / dismiss | fun dismiss(notification: Notification) = NotificationsManager.dismiss(notification) |
| 152 | peripheral-ui / NotificationsManager.clearAll | fun clearAll() = NotificationsManager.clearAll() |
| 152 | ui-function / clearAll | fun clearAll() = NotificationsManager.clearAll() |
| 156 | ui-function / icon | fun icon(resourcePath: String, size: Int = 64): PolyBitmap? = SvgMask.get(resourcePath, size) |
| 162 | ui-function / skiaIcon | fun skiaIcon(resourcePath: String, size: Int = 64): Image? = null |
| 165 | ui-function / builder | fun builder(title: String, message: String): Builder = Builder(title, message) |
| 175 | ui-function / type | fun type(type: NotificationType) = apply { this.type = type } |
| 176 | ui-function / icon | fun icon(icon: PolyBitmap?) = apply { this.icon = icon } |
| 180 | ui-function / skiaIcon | fun skiaIcon(icon: Image?) = apply { this.icon = null } |
| 181 | ui-function / duration | fun duration(duration: Float) = apply { this.duration = duration } |
| 186 | ui-function / persistent | fun persistent() = apply { this.duration = -1f } |
| 188 | ui-function / progress | fun progress(progress: Callable<Float>?) = apply { |
| 193 | ui-function / onClick | fun onClick(onClick: Runnable?) = apply { this.onClick = onClick } |
| 194 | ui-function / action | fun action(action: NotificationAction) = apply { this.actions.add(action) } |
| 195 | ui-function / action | fun action(label: String, onClick: Runnable) = apply { this.actions.add(NotificationAction(label, onClick = onClick)) } |
| 200 | ui-function / send | fun send(): Notification = |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/notifications/v1/NotificationsManager.kt

`file-2e5b9f721c45ec363a84`；状态 **pending**；领域 notifications；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

从实际宿主入口触发，核对完整状态变化、交互、退出及重进后的结果。 定位类型: peripheral-ui 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 36 | peripheral-ui / NotificationsManager | object NotificationsManager { |
| 43 | ui-function / ensureInitialized | fun ensureInitialized() { |
| 47 | ui-function / mutate | private fun mutate(block: () -> Unit) { |
| 70 | ui-function / push | fun push(notification: Notification) { |
| 78 | ui-function / markAllRead | fun markAllRead() { |
| 84 | ui-function / removeFromHistory | fun removeFromHistory(notification: Notification) { |
| 93 | ui-function / remove | fun remove(notification: Notification) { |
| 100 | ui-function / clearHistory | fun clearHistory() { |
| 109 | ui-function / dismiss | fun dismiss(notification: Notification) { |
| 115 | ui-function / dismiss | fun dismiss(id: Long) { |
| 121 | ui-function / clearAll | fun clearAll() { |
| 130 | ui-function / contains | fun contains(notification: Notification): Boolean = _active.contains(notification) |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/notifications/v1/NotificationsRenderer.kt

`file-e1b0cf37105c6eb02c86`；状态 **pending**；领域 notifications；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

从实际宿主入口触发，核对完整状态变化、交互、退出及重进后的结果。 定位类型: peripheral-ui 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 51 | ui-function / runtime | private fun runtime(): PolyComposeRuntime = |
| 54 | peripheral-ui / NotificationToasts | it.setContent { NotificationToasts() } |
| 55 | peripheral-ui / ToastInput.install | ToastInput.install() |
| 62 | ui-function / render | fun render(ctx: RenderContext, screenWidth: Float, screenHeight: Float) { |
| 68 | ui-function / prepare | fun prepare(screenWidth: Float, screenHeight: Float): Boolean { |
| 91 | ui-function / draw | fun draw(ctx: RenderContext) { |
| 99 | ui-function / handleInput | private fun handleInput(root: PolyNode, scale: Float) { |
| 103 | peripheral-ui / ToastInput.mouseX | if (screenOpen && ToastInput.mouseX >= 0f && ToastInput.mouseY >= 0f) { |
| 103 | peripheral-ui / ToastInput.mouseY | if (screenOpen && ToastInput.mouseX >= 0f && ToastInput.mouseY >= 0f) { |
| 105 | peripheral-ui / ToastInput.mouseX | top = topHit(root, ToastInput.mouseX * pixelRatio / scale, ToastInput.mouseY * pixelRatio / scale, null) |
| 105 | peripheral-ui / ToastInput.mouseY | top = topHit(root, ToastInput.mouseX * pixelRatio / scale, ToastInput.mouseY * pixelRatio / scale, null) |
| 109 | peripheral-ui / NotificationsManager.active | for (notification in NotificationsManager.active) { |
| 114 | peripheral-ui / ToastInput.hoverTarget | ToastInput.hoverTarget = top |
| 116 | peripheral-ui / ToastInput.hoveredAction | if (ToastInput.hoveredAction !== hoveredAction) ToastInput.hoveredAction = hoveredAction |
| 116 | peripheral-ui / ToastInput.hoveredAction | if (ToastInput.hoveredAction !== hoveredAction) ToastInput.hoveredAction = hoveredAction |
| 119 | ui-function / dispatchClick | internal fun dispatchClick(target: ToastHit?) { |
| 121 | peripheral-ui / NotificationsManager.contains | if (!NotificationsManager.contains(target.notification) \|\| target.notification.dismissRequested) { |
| 122 | peripheral-ui / ToastInput.clearHover | ToastInput.clearHover() |
| 130 | peripheral-ui / NotificationsManager.remove | NotificationsManager.remove(target.notification) |
| 141 | ui-function / topHit | private fun topHit(node: PolyNode, x: Float, y: Float, current: ToastHit?): ToastHit? { |
| 149 | ui-function / contains | private fun contains(node: PolyNode, x: Float, y: Float): Boolean = |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/notifications/v1/SvgGeometry.kt

`file-7247543fde036c667b29`；状态 **pending**；领域 notifications；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 18 | ui-function / identity | fun identity() = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f) |
| 20 | ui-function / translate | fun translate(x: Float, y: Float) = floatArrayOf(1f, 0f, 0f, 1f, x, y) |
| 22 | ui-function / scale | fun scale(x: Float, y: Float) = floatArrayOf(x, 0f, 0f, y, 0f, 0f) |
| 24 | ui-function / mul | fun mul(a: FloatArray, b: FloatArray) = floatArrayOf( |
| 30 | ui-function / invert | fun invert(m: FloatArray): FloatArray? { |
| 36 | ui-function / scaleOf | fun scaleOf(m: FloatArray) = sqrt(abs(m[0] * m[3] - m[1] * m[2])) |
| 38 | ui-function / map | fun map(m: FloatArray, p: FloatArray) = FloatArray(p.size) { i -> |
| 42 | ui-function / mapRect | fun mapRect(m: FloatArray, r: FloatArray): FloatArray { |
| 47 | ui-function / rectPoly | fun rectPoly(r: FloatArray) = floatArrayOf(r[0], r[1], r[2], r[1], r[2], r[3], r[0], r[3]) |
| 49 | ui-function / parse | fun parse(s: String?): FloatArray { |
| 84 | ui-function / verb | private fun verb(v: Byte) { |
| 89 | ui-function / pt | private fun pt(x: Float, y: Float) { |
| 95 | ui-function / moveTo | fun moveTo(x: Float, y: Float) { verb(MOVE); pt(x, y) } |
| 96 | ui-function / lineTo | fun lineTo(x: Float, y: Float) { verb(LINE); pt(x, y) } |
| 97 | ui-function / cubicTo | fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) { verb(CUBIC); pt(x1, y1); pt(x2, y2); pt(x, y) } |
| 98 | ui-function / close | fun close() = verb(CLOSE) |
| 100 | ui-function / transform | fun transform(m: FloatArray): SvgPath { |
| 109 | ui-function / flatten | fun flatten(tol: Float): List<Polyline> { |
| 115 | ui-function / begin | fun begin() { if (cur.size == 0) cur.add(sx, sy) } |
| 116 | ui-function / flush | fun flush(closed: Boolean) { |
| 158 | ui-function / bounds | fun bounds(): FloatArray? { |
| 170 | ui-function / ellipse | fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float) { |
| 180 | ui-function / roundRect | fun roundRect(x: Float, y: Float, w: Float, h: Float, rx: Float, ry: Float) { |
| 208 | ui-function / numbers | fun numbers(s: String): FloatArray = NUMBER.findAll(s).map { it.value.toFloat() }.toList().toFloatArray() |
| 210 | ui-function / parse | fun parse(d: String): SvgPath { |
| 218 | ui-function / num | fun num(): Float { |
| 224 | ui-function / flag | fun flag(): Boolean { |
| 230 | ui-function / cubic | fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, ex: Float, ey: Float) { |
| 285 | ui-function / hypot | private fun hypot(x: Float, y: Float) = sqrt(x * x + y * y) |
| 288 | ui-function / arc | private fun arc(x0: Float, y0: Float, rx0: Float, ry0: Float, rotation: Float, large: Boolean, sweep: Boolean, ex: Float, ey: Float) { |
| 309 | ui-function / px | fun px(ux: Double, uy: Double) = (mx + cp * rx * ux - sp * ry * uy).toFloat() |
| 310 | ui-function / py | fun py(ux: Double, uy: Double) = (my + sp * rx * ux + cp * ry * uy).toFloat() |
| 327 | ui-function / add | fun add(x: Float, y: Float) { |
| 333 | ui-function / clear | fun clear() { size = 0 } |
| 334 | ui-function / toArray | fun toArray(): FloatArray = data.copyOf(size) |
| 344 | ui-function / dash | fun dash(lines: List<Polyline>, pattern: FloatArray, offset: Float): List<Polyline> { |
| 383 | ui-function / stroke | fun stroke(lines: List<Polyline>, hw: Float, cap: Int, join: Int, miterLimit: Float, tol: Float): List<FloatArray> { |
| 458 | ui-function / circle | fun circle(cx: Float, cy: Float, r: Float, tol: Float): FloatArray { |
| 464 | ui-function / oriented | private fun oriented(p: FloatArray): FloatArray { |
| 482 | ui-function / fill | fun fill(polys: List<FloatArray>, evenOdd: Boolean, w: Int, h: Int, out: FloatArray): IntArray? { |
| 565 | ui-function / parse | fun parse(value: String, current: Int): Int? { |
| 584 | ui-function / channel | fun channel(s: String, scale: Float): Float? = |
| 595 | ui-function / b | fun b(f: Float) = (f.coerceIn(0f, 1f) * 255f + 0.5f).toInt() |
| 601 | ui-function / hsl | private fun hsl(h: Float, s: Float, l: Float): FloatArray { |
| 604 | ui-function / hue | fun hue(t0: Float): Float { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/notifications/v1/SvgMask.kt

`file-165156f333e36f084314`；状态 **pending**；领域 notifications；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 13 | ui-function / get | fun get(resourcePath: String, pixelSize: Int, tint: Int? = null): PolyBitmap? { |
| 21 | ui-function / rasterize | fun rasterize(svg: String, size: Int, tint: Int? = null): PolyBitmap? = |
| 24 | ui-function / parsePath | fun parsePath(d: String): List<FloatArray> = SvgPath.parse(d).flatten(0.001f).map { it.pts } |
| 26 | ui-function / readResource | private fun readResource(path: String): ByteArray? { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/notifications/v1/SvgRenderer.kt

`file-d4f7685157936fd4d3c8`；状态 **pending**；领域 notifications；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 42 | ui-function / copy | fun copy() = SvgStyle(fill, fillOpacity, fillEvenOdd, clipEvenOdd, stroke, strokeOpacity, strokeWidth, cap, join, miterLimit, dashArray, dashOffset, color, visible, linearFilters) |
| 47 | ui-function / eval | abstract fun eval(x: Float, y: Float, out: FloatArray) |
| 56 | ui-function / eval | override fun eval(x: Float, y: Float, out: FloatArray) { |
| 69 | ui-function / eval | override fun eval(x: Float, y: Float, out: FloatArray) { |
| 103 | ui-function / eval | override fun eval(x: Float, y: Float, out: FloatArray) { |
| 120 | ui-function / index | private fun index(el: Element) { |
| 125 | ui-function / isSvg | fun isSvg(el: Element) = el.namespaceURI == null \|\| el.namespaceURI == SVG_NS |
| 127 | ui-function / props | fun props(el: Element): Map<String, String> = propCache.getOrPut(el) { |
| 145 | ui-function / href | fun href(el: Element): Element? { |
| 150 | ui-function / styleOf | fun styleOf(el: Element): SvgStyle = styleCache[el] ?: run { |
| 155 | ui-function / derive | fun derive(parent: SvgStyle, el: Element): SvgStyle { |
| 181 | ui-function / validPaint | private fun validPaint(v: String) = v == "none" \|\| v.startsWith("url(") \|\| SvgColor.parse(v, 0) != null |
| 190 | ui-function / cssValue | private fun cssValue(raw: String): String? { |
| 197 | ui-function / children | fun children(el: Element): List<Element> { |
| 207 | ui-function / opacity | fun opacity(v: String): Float? { |
| 213 | ui-function / length | fun length(v: String): Pair<Float, String>? { |
| 218 | ui-function / urlRef | fun urlRef(v: String?): String? = v?.let { Regex("url\\(\\s*['\"]?#([^)'\"]+)['\"]?\\s*\\)").find(it)?.groupValues?.get(1) } |
| 237 | ui-function / warning | override fun warning(e: SAXParseException) {} |
| 238 | ui-function / error | override fun error(e: SAXParseException) {} |
| 239 | ui-function / fatalError | override fun fatalError(e: SAXParseException) = throw e |
| 244 | ui-function / parse | fun parse(svg: String): SvgDoc? { |
| 260 | ui-function / render | fun render(root: Element, m: FloatArray, vw: Float, vh: Float): FloatArray { |
| 267 | ui-function / renderChildren | private fun renderChildren(el: Element, ctx: Ctx, dst: FloatArray) { |
| 271 | ui-function / render | private fun render(el: Element, parent: Ctx, dst: FloatArray) { |
| 317 | ui-function / nested | private fun nested(el: Element, ctx: Ctx, dst: FloatArray, x: Float, y: Float, vw: Float, vh: Float) { |
| 330 | ui-function / effects | private inline fun effects(el: Element, ctx: Ctx, dst: FloatArray, clipRect: FloatArray?, bboxOf: () -> FloatArray?, draw: (FloatArray) -> Unit) { |
| 361 | ui-function / polyCoverage | private fun polyCoverage(poly: FloatArray): FloatArray { |
| 367 | ui-function / len | private fun len(v: String?, ctx: Ctx, axis: Int, def: Float): Float { |
| 387 | ui-function / frac | private fun frac(v: String?, def: Float): Float { |
| 392 | ui-function / shapePath | private fun shapePath(el: Element, ctx: Ctx): SvgPath? { |
| 436 | ui-function / drawShape | private fun drawShape(el: Element, path: SvgPath, ctx: Ctx, dst: FloatArray) { |
| 455 | ui-function / dashes | private fun dashes(st: SvgStyle, ctx: Ctx): FloatArray? { |
| 464 | ui-function / composite | private fun composite(dst: FloatArray, coverage: FloatArray, b: IntArray, paint: SvgPaint) { |
| 481 | ui-function / paint | private fun paint(spec: String, st: SvgStyle, ctx: Ctx, bbox: () -> FloatArray?, opacity: Float): SvgPaint? { |
| 499 | ui-function / chainAttr | private fun chainAttr(el: Element, name: String, kinds: Set<String>): String? { |
| 509 | ui-function / chainChildren | private fun chainChildren(el: Element, kinds: Set<String>, accept: (Element) -> Boolean): List<Element> { |
| 520 | ui-function / bboxMatrix | private fun bboxMatrix(b: FloatArray) = floatArrayOf(b[2] - b[0], 0f, 0f, b[3] - b[1], b[0], b[1]) |
| 522 | ui-function / gradient | private fun gradient(g: Element, ctx: Ctx, bbox: () -> FloatArray?, opacity: Float): SvgPaint? { |
| 538 | ui-function / attr | fun attr(name: String) = doc.props(g)[name] |
| 547 | ui-function / coord | fun coord(name: String, def: Float, axis: Int, fallback: String? = null): Float { |
| 584 | ui-function / argbOf | private fun argbOf(colors: FloatArray, i: Int): Int { |
| 585 | ui-function / b | fun b(f: Float) = (f.coerceIn(0f, 1f) * 255f + 0.5f).toInt() |
| 589 | ui-function / pattern | private fun pattern(pat: Element, ctx: Ctx, bbox: () -> FloatArray?, opacity: Float): SvgPaint? { |
| 590 | ui-function / attr | fun attr(name: String) = chainAttr(pat, name, PATTERNS) |
| 624 | ui-function / imageRect | private fun imageRect(el: Element, ctx: Ctx): FloatArray? { |
| 631 | ui-function / drawImage | private fun drawImage(el: Element, ctx: Ctx, dst: FloatArray) { |
| 668 | ui-function / bbox | private fun bbox(el: Element, ctx: Ctx): FloatArray? = when (el.localName) { |
| 684 | ui-function / groupBBox | private fun groupBBox(el: Element, ctx: Ctx): FloatArray? { |
| 695 | ui-function / clipCoverage | private fun clipCoverage(clipEl: Element, ctx: Ctx, bbox: FloatArray?, depth: Int): FloatArray { |
| 734 | ui-function / maskCoverage | private fun maskCoverage(maskEl: Element, ctx: Ctx, bbox: FloatArray?): FloatArray { |
| 766 | ui-function / applyFilter | private fun applyFilter(filter: Element, src: FloatArray, ctx: Ctx, bbox: FloatArray?): FloatArray? { |
| 796 | ui-function / input | fun input(name: String?): FBuf = when (name) { |
| 802 | ui-function / inp | fun inp(name: String?) = convert(input(name), linear, r).px |
| 803 | ui-function / num | fun num(v: String?, def: Float) = v?.toFloatOrNull() ?: def |
| 847 | ui-function / cropped | private fun cropped(src: FloatArray, r: IntArray): FloatArray { |
| 853 | ui-function / convert | private fun convert(b: FBuf, linear: Boolean, r: IntArray): FBuf { |
| 868 | ui-function / toLinear | private fun toLinear(v: Float) = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat() |
| 869 | ui-function / toSrgb | private fun toSrgb(v: Float) = if (v <= 0.0031308f) v * 12.92f else (1.055 * v.toDouble().pow(1 / 2.4) - 0.055).toFloat() |
| 871 | ui-function / flood | private fun flood(pp: Map<String, String>, st: SvgStyle, linear: Boolean, r: IntArray): FloatArray { |
| 884 | ui-function / blur | private fun blur(src: FloatArray, sx: Float, sy: Float, r: IntArray): FloatArray { |
| 891 | ui-function / blur1 | private fun blur1(src: FloatArray, sigma: Float, horizontal: Boolean, r: IntArray): FloatArray { |
| 913 | ui-function / offset | private fun offset(src: FloatArray, dx: Float, dy: Float, r: IntArray): FloatArray { |
| 923 | ui-function / colorMatrix | private fun colorMatrix(src: FloatArray, pp: Map<String, String>, r: IntArray): FloatArray { |
| 963 | ui-function / over | private fun over(src: FloatArray, dst: FloatArray, r: IntArray) { |
| 971 | ui-function / composite2 | private fun composite2(a: FloatArray, b: FloatArray, pp: Map<String, String>, r: IntArray): FloatArray { |
| 998 | ui-function / blend | private fun blend(a: FloatArray, b: FloatArray, mode: String, r: IntArray): FloatArray { |
| 1038 | ui-function / viewBox | fun viewBox(v: String?): FloatArray? { |
| 1043 | ui-function / viewBoxMatrix | fun viewBoxMatrix(vb: FloatArray, width: Float, height: Float, par: String?): FloatArray { |
| 1062 | ui-function / bilinear | fun bilinear(src: FloatArray, sw: Int, sh: Int, u: Float, v: Float, wrap: Boolean, out: FloatArray) { |
| 1079 | ui-function / rasterize | fun rasterize(svg: String, size: Int, tint: Int?): PolyBitmap? { |
| 1084 | ui-function / absLen | fun absLen(v: String?) = v?.let(SvgDoc::length)?.takeIf { it.second != "%" && it.first > 0f }?.let { (n, u) -> |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/notifications/v1/ToastInput.kt

`file-80c44b533e7722aac020`；状态 **pending**；领域 notifications；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

从实际宿主入口触发，核对完整状态变化、交互、退出及重进后的结果。 定位类型: peripheral-ui 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 37 | peripheral-ui / ToastInput | internal object ToastInput { |
| 53 | ui-function / clearHover | fun clearHover() { |
| 58 | ui-function / install | fun install() { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/ExperimentalOneConfigUiApi.kt

`file-d237b26ec382d6f165cb`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/ModCardIcons.kt

`file-d3fd05223f0d13696da9`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 11 | ui-function / iconFor | fun interface ModCardIconProvider { |
| 12 | ui-function / iconFor | fun iconFor(modId: String, defaultIcon: String?): String? |
| 24 | ui-function / register | fun register(entry: ModCardIconProvider) { |
| 35 | ui-function / unregister | fun unregister(entry: ModCardIconProvider): Boolean = synchronized(lock) { |
| 43 | ui-function / resolve | fun resolve(modId: String, defaultIcon: String?): String? = entries.fold(defaultIcon) { icon, provider -> |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/ModCardOrder.kt

`file-d52bb60bce95ac93d40a`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 8 | ui-function / current | fun current(): List<String> = extensionsBridge()?.modOrder() ?: emptyList() |
| 11 | ui-function / replace | fun replace(ids: List<String>) { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/ModCardType.kt

`file-dcef01fccf05477fdbd9`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 10 | ui-function / equals | override fun equals(other: Any?) = this === other \|\| (other is ModCardType && other.id == id) |
| 12 | ui-function / hashCode | override fun hashCode() = id.hashCode() |
| 14 | ui-function / toString | override fun toString() = |
| 18 | ui-function / typeIdFor | fun interface ModCardTypeResolver { |
| 19 | ui-function / typeIdFor | fun typeIdFor(modId: String): String? |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/ModCardTypes.kt

`file-0f5acd1ad2c1319b88de`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 30 | ui-function / register | fun register(type: ModCardType) { |
| 44 | ui-function / assign | fun assign(modId: String, typeId: String) { |
| 55 | ui-function / assignAll | fun assignAll(typeId: String, vararg modIds: String) { |
| 67 | ui-function / registerResolver | fun registerResolver(resolver: ModCardTypeResolver) { |
| 76 | ui-function / types | fun types(): List<ModCardType> = sortedTypes |
| 79 | ui-function / byId | fun byId(typeId: String): ModCardType? = typesById[typeId] |
| 82 | ui-function / typeFor | fun typeFor(modId: String): ModCardType? { |
| 93 | ui-function / normalise | private fun normalise(modId: String) = modId.trim().removeSuffix(".json").lowercase() |
| 95 | ui-function / keysFor | private fun keysFor(modId: String) = sequenceOf( |
| 102 | ui-function / safeResolve | private fun safeResolve(resolver: ModCardTypeResolver, modId: String): String? = |
| 111 | ui-function / bump | private fun bump() { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/ModSortModes.kt

`file-aa3c3348873230b59091`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 27 | ui-function / register | fun register(mode: ModSortMode) { |
| 38 | ui-function / unregister | fun unregister(mode: ModSortMode): Boolean = synchronized(lock) { |
| 45 | ui-function / all | fun all(): List<ModSortMode> = entries |
| 48 | ui-function / currentId | fun currentId(): String? = extensionsBridge()?.modSortModeId() |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/ModToggle.kt

`file-4100dee591768c642735`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 8 | ui-function / needsSetup | override fun needsSetup(): Boolean = false |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/ModToggles.kt

`file-ff60e72093649dd119aa`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 42 | ui-function / load | fun load(): Set<String> |
| 44 | ui-function / save | fun save(disabled: Set<String>) |
| 47 | ui-function / onToggle | fun interface Listener { |
| 48 | ui-function / onToggle | fun onToggle(modId: String, enabled: Boolean) |
| 57 | ui-function / register | fun register(modId: String, toggle: ModToggle) { |
| 70 | ui-function / register | fun register(modId: String, toggle: org.polyfrost.oneconfig.api.ui.v1.ModToggle) = |
| 79 | ui-function / manage | fun manage(vararg modIds: String) { |
| 87 | ui-function / toggleFor | fun toggleFor(modId: String): ModToggle? { |
| 100 | ui-function / isEnabled | fun isEnabled(modId: String): Boolean { |
| 106 | ui-function / setEnabled | fun setEnabled(modId: String, enabled: Boolean) { |
| 131 | ui-function / addListener | fun addListener(listener: Listener) { |
| 136 | ui-function / removeListener | fun removeListener(listener: Listener) { |
| 141 | ui-function / setStore | fun setStore(store: Store) { |
| 156 | ui-function / isEnabled | override fun isEnabled() = ModToggles.isEnabled(key) |
| 158 | ui-function / setEnabled | override fun setEnabled(enabled: Boolean) = ModToggles.setEnabled(key, enabled) |
| 161 | ui-function / normalise | private fun normalise(modId: String) = modId.trim().removeSuffix(".json").lowercase() |
| 163 | ui-function / keysFor | private fun keysFor(modId: String) = sequenceOf( |
| 170 | ui-function / bump | private fun bump() { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/ModsPageDecorators.kt

`file-3dbb6c51c39a7e841244`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 12 | composable / Decorate | @Composable |
| 13 | composable / @Composable callback/type | abstract fun Decorate(content: @Composable () -> Unit) |
| 13 | ui-function / Decorate | abstract fun Decorate(content: @Composable () -> Unit) |
| 24 | ui-function / register | fun register(entry: ModsPageDecorator) { |
| 35 | ui-function / unregister | fun unregister(entry: ModsPageDecorator): Boolean = synchronized(lock) { |
| 42 | ui-function / all | fun all(): List<ModsPageDecorator> = entries |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/OneConfigUI.kt

`file-92db503de4d185ddc898`；状态 **pending**；领域 shell-navigation；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 52 | ui-function / open | fun open(route: Any? = null) { |
| 75 | ui-function / createScreen | fun createScreen(route: Any? = null): Any? = bridge?.createScreen(route) |
| 79 | ui-function / isOneConfigScreen | fun isOneConfigScreen(screen: Any?): Boolean = bridge?.isOneConfigScreen(screen) ?: false |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/OneConfigUIBridge.kt

`file-1cdbd313df068dd62f17`；状态 **pending**；领域 shell-navigation；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 8 | ui-function / createScreen | fun createScreen(route: Any?): Any |
| 11 | ui-function / open | fun open(route: Any?) |
| 13 | ui-function / isOneConfigScreen | fun isOneConfigScreen(screen: Any?): Boolean = false |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/OptionWidgets.kt

`file-8edcb60730ad9ac1a312`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 9 | ui-function / refresh | fun refresh(property: Property<*>) { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/SettingRowDecorators.kt

`file-d2f93f6edef8f2815cf8`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 13 | composable / Decorate | @Composable |
| 14 | composable / @Composable callback/type | abstract fun Decorate(property: Property<*>, content: @Composable () -> Unit) |
| 14 | ui-function / Decorate | abstract fun Decorate(property: Property<*>, content: @Composable () -> Unit) |
| 25 | ui-function / register | fun register(entry: SettingRowDecorator) { |
| 36 | ui-function / unregister | fun unregister(entry: SettingRowDecorator): Boolean = synchronized(lock) { |
| 43 | ui-function / all | fun all(): List<SettingRowDecorator> = entries |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/UiFontOverride.kt

`file-db2ce29ec19c7534cba3`；状态 **pending**；领域 font-language；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 57 | font-language / UiFontOverride | object UiFontOverride { |
| 61 | ui-function / id | fun id(): String |
| 64 | ui-function / regular | fun regular(): ByteArray? |
| 67 | ui-function / fallback | fun fallback(): ByteArray? = null |
| 70 | ui-function / medium | fun medium(): ByteArray? = null |
| 73 | ui-function / semibold | fun semibold(): ByteArray? = null |
| 76 | ui-function / bold | fun bold(): ByteArray? = null |
| 104 | ui-function / set | fun set(source: FontSource?) { |
| 118 | ui-function / bumpRevision | fun bumpRevision() { |
| 124 | ui-function / clear | fun clear() = set(null) |
| 126 | ui-function / restoreBundledFallbackIfNeeded | private fun restoreBundledFallbackIfNeeded() { |
| 132 | ui-function / installFallback | private fun installFallback(bytes: ByteArray) { |
| 144 | ui-function / buildFamily | fun buildFamily(): FontFamily? { |
| 172 | ui-function / fontOf | private fun fontOf(identity: String, data: ByteArray, weight: FontWeight): Font = |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/components/Borders.kt

`file-d567d6189d900fd6e11e`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 13 | composable / fadingEdges | @Composable |
| 14 | ui-function / fadingEdges | fun Modifier.fadingEdges(scrollState: ScrollState, color: Color): Modifier = |
| 18 | composable / fadingEdges | @Composable |
| 19 | ui-function / fadingEdges | fun Modifier.fadingEdges(scrollState: ScrollState, color: Color, length: Dp): Modifier = |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/components/Button.kt

`file-4ad79c16bf3b199b3554`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 11 | composable / IconButton | @Composable |
| 12 | ui-function / IconButton | fun IconButton(iconName: String, modifier: Modifier, onClick: () -> Unit) { |
| 17 | composable / IconButton | @Composable |
| 18 | ui-function / IconButton | fun IconButton(iconName: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) { |
| 24 | composable / IconButton | @Composable |
| 25 | ui-function / IconButton | fun IconButton( |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/components/Chips.kt

`file-0a1423e9b61b2c0df81f`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 8 | composable / Chip | @Composable |
| 9 | ui-function / Chip | fun Chip(label: String, selected: Boolean, onClick: () -> Unit) { |
| 14 | composable / Chip | @Composable |
| 15 | ui-function / Chip | fun Chip(label: String, selected: Boolean, icon: String?, onClick: () -> Unit) { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/components/ColorPicker.kt

`file-3c79831d8f8c3824626c`；状态 **pending**；领域 color-gradient；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 核对打开/取消/确认、焦点、边界定位、外点关闭与提交后可见结果。 定位类型: dialog-or-popup 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 12 | dialog-or-popup / createColorPicker | internal val handle: Any = componentsBridge().createColorPicker(initialColor, hasAlpha) |
| 16 | composable / ColorPickerPopup | @Composable |
| 17 | dialog-or-popup / ColorPickerPopup | fun ColorPickerPopup(model: ColorPickerModel, onColorChanged: (Color) -> Unit, onClose: () -> Unit) { |
| 17 | ui-function / ColorPickerPopup | fun ColorPickerPopup(model: ColorPickerModel, onColorChanged: (Color) -> Unit, onClose: () -> Unit) { |
| 18 | dialog-or-popup / ColorPickerPopup | componentsBridge().ColorPickerPopup(model.handle, onColorChanged, onClose, true) |
| 22 | composable / ColorPickerPopup | @Composable |
| 23 | dialog-or-popup / ColorPickerPopup | fun ColorPickerPopup(model: ColorPickerModel, onColorChanged: (Color) -> Unit, onClose: () -> Unit, chromaCapable: Boolean) { |
| 23 | ui-function / ColorPickerPopup | fun ColorPickerPopup(model: ColorPickerModel, onColorChanged: (Color) -> Unit, onClose: () -> Unit, chromaCapable: Boolean) { |
| 24 | dialog-or-popup / ColorPickerPopup | componentsBridge().ColorPickerPopup(model.handle, onColorChanged, onClose, chromaCapable) |
| 28 | ui-function / colorToHex | fun colorToHex(color: Color): String = componentsBridge().colorToHex(color) |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/components/Common.kt

`file-becdc6bb87742912fc5f`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 11 | composable / rememberInteractionSource | @Composable |
| 12 | ui-function / rememberInteractionSource | fun rememberInteractionSource(): MutableInteractionSource = remember { MutableInteractionSource() } |
| 15 | ui-function / onClick | fun Modifier.onClick(interactionSource: MutableInteractionSource, onClick: () -> Unit): Modifier = |
| 19 | ui-function / onClick | fun Modifier.onClick(interactionSource: MutableInteractionSource, enabled: Boolean, onClick: () -> Unit): Modifier = |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/components/Icon.kt

`file-8df8de646c672cd2e447`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 10 | composable / Icon | @Composable |
| 11 | ui-function / Icon | fun Icon(iconName: String, color: Color, modifier: Modifier) { |
| 16 | composable / Icon | @Composable |
| 17 | ui-function / Icon | fun Icon(iconName: String, color: Color, modifier: Modifier, fitAspectRatio: Boolean) { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/components/OptionWidth.kt

`file-28709d5dc0a29fa11bc2`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 10 | composable / @Composable callback/type | @Composable get() = componentsBridge().optionWidth() |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/components/Text.kt

`file-7140363ab52a0509b9e1`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 16 | composable / Text | @Composable |
| 17 | ui-function / Text | fun Text(text: Any, modifier: Modifier, color: Color) { |
| 22 | composable / Text | @Composable |
| 23 | ui-function / Text | fun Text(text: Any, modifier: Modifier, color: Color, fontSize: TextUnit) { |
| 28 | composable / Text | @Composable |
| 29 | ui-function / Text | fun Text(text: Any, modifier: Modifier, color: Color, fontSize: TextUnit, fontWeight: FontWeight) { |
| 34 | composable / Text | @Composable |
| 35 | ui-function / Text | fun Text( |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/components/Tooltip.kt

`file-65299997943d9ff86163`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 11 | composable / Tooltip | @Composable |
| 12 | composable / @Composable callback/type | fun Tooltip(text: @Composable () -> Unit, modifier: Modifier, anchor: Alignment, content: @Composable () -> Unit) { |
| 12 | composable / @Composable callback/type | fun Tooltip(text: @Composable () -> Unit, modifier: Modifier, anchor: Alignment, content: @Composable () -> Unit) { |
| 12 | ui-function / Tooltip | fun Tooltip(text: @Composable () -> Unit, modifier: Modifier, anchor: Alignment, content: @Composable () -> Unit) { |
| 17 | composable / Tooltip | @Composable |
| 18 | ui-function / Tooltip | fun Tooltip( |
| 19 | composable / @Composable callback/type | text: @Composable () -> Unit, |
| 24 | composable / @Composable callback/type | content: @Composable () -> Unit, |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/internal/Bridges.kt

`file-2eeb3d12ea367525977d`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 8 | ui-function / loadBridge | internal fun <T : Any> loadBridge(type: Class<T>, consequence: String): T? { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/internal/OneConfigUIBridgeImpl.kt

`file-cf5f94df250febbdfacc`；状态 **pending**；领域 shell-navigation；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 14 | route / resolveOpenTarget | override fun createScreen(route: Any?): Any = OneConfigUIScreen.forRoute(resolveOpenTarget(route)) |
| 14 | ui-function / createScreen | override fun createScreen(route: Any?): Any = OneConfigUIScreen.forRoute(resolveOpenTarget(route)) |
| 16 | ui-function / open | override fun open(route: Any?) { |
| 17 | route / resolveOpenTarget | val target = resolveOpenTarget(route) |
| 36 | ui-function / isOneConfigScreen | override fun isOneConfigScreen(screen: Any?): Boolean = screen is OneConfigUIScreen |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/internal/OneConfigUIComponentsBridge.kt

`file-262469a7f07502bc2b86`；状态 **pending**；领域 shell-navigation；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 核对打开/取消/确认、焦点、边界定位、外点关闭与提交后可见结果。 定位类型: dialog-or-popup 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 22 | composable / currentTheme | @Composable |
| 23 | ui-function / currentTheme | fun currentTheme(): OneConfigUITheme |
| 25 | composable / accent | @Composable |
| 26 | ui-function / accent | fun accent(): Color |
| 28 | composable / ProvideTheme | @Composable |
| 29 | composable / polyGlassDark | fun ProvideTheme(content: @Composable () -> Unit) |
| 29 | ui-function / ProvideTheme | fun ProvideTheme(content: @Composable () -> Unit) |
| 31 | ui-function / polyGlassDark | fun polyGlassDark(): OneConfigUITheme |
| 33 | ui-function / polyGlassLight | fun polyGlassLight(): OneConfigUITheme |
| 35 | ui-function / minecraftDark | fun minecraftDark(): OneConfigUITheme |
| 37 | ui-function / minecraftLight | fun minecraftLight(): OneConfigUITheme |
| 39 | ui-function / activeTheme | fun activeTheme(): OneConfigUITheme? |
| 41 | ui-function / activateTheme | fun activateTheme(theme: OneConfigUITheme) |
| 43 | ui-function / ensureThemeLoaded | fun ensureThemeLoaded() |
| 45 | composable / concentric | @Composable |
| 46 | ui-function / concentric | fun concentric(shape: Shape, inset: Dp, minRadius: Dp): Shape |
| 48 | composable / Icon | @Composable |
| 49 | ui-function / Icon | fun Icon(iconName: String, color: Color, modifier: Modifier, fitAspectRatio: Boolean) |
| 51 | composable / Text | @Composable |
| 52 | ui-function / Text | fun Text( |
| 65 | composable / Chip | @Composable |
| 66 | ui-function / Chip | fun Chip(label: String, selected: Boolean, icon: String?, onClick: () -> Unit) |
| 68 | composable / IconButton | @Composable |
| 69 | ui-function / IconButton | fun IconButton( |
| 79 | composable / Tooltip | @Composable |
| 80 | ui-function / Tooltip | fun Tooltip( |
| 81 | composable / onClick | text: @Composable () -> Unit, |
| 86 | composable / onClick | content: @Composable () -> Unit, |
| 89 | ui-function / onClick | fun onClick(modifier: Modifier, interactionSource: MutableInteractionSource, enabled: Boolean, onClick: () -> Unit): Modifier |
| 91 | composable / fadingEdges | @Composable |
| 92 | ui-function / fadingEdges | fun fadingEdges(modifier: Modifier, scrollState: ScrollState, color: Color, length: Dp): Modifier |
| 94 | dialog-or-popup / createColorPicker | fun createColorPicker(initialColor: Color, hasAlpha: Boolean): Any |
| 94 | ui-function / createColorPicker | fun createColorPicker(initialColor: Color, hasAlpha: Boolean): Any |
| 96 | composable / ColorPickerPopup | @Composable |
| 97 | dialog-or-popup / ColorPickerPopup | fun ColorPickerPopup(model: Any, onColorChanged: (Color) -> Unit, onClose: () -> Unit, chromaCapable: Boolean) |
| 97 | ui-function / ColorPickerPopup | fun ColorPickerPopup(model: Any, onColorChanged: (Color) -> Unit, onClose: () -> Unit, chromaCapable: Boolean) |
| 99 | ui-function / colorToHex | fun colorToHex(color: Color): String |
| 101 | composable / optionWidth | @Composable |
| 102 | ui-function / optionWidth | fun optionWidth(): Dp |
| 104 | composable / pageSearchQuery | @Composable |
| 105 | ui-function / pageSearchQuery | fun pageSearchQuery(): String |
| 114 | ui-function / componentsBridge | internal fun componentsBridge(): OneConfigUIComponentsBridge = |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/internal/OneConfigUIComponentsBridgeImpl.kt

`file-f6d06fe0442d064225f2`；状态 **pending**；领域 shell-navigation；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 核对打开/取消/确认、焦点、边界定位、外点关闭与提交后可见结果。 定位类型: dialog-or-popup 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 42 | composable / currentTheme | @Composable |
| 43 | ui-function / currentTheme | override fun currentTheme(): OneConfigUITheme = LocalTheme.current |
| 45 | composable / accent | @Composable |
| 46 | ui-function / accent | override fun accent(): Color = Accent |
| 48 | composable / ProvideTheme | @Composable |
| 49 | composable / @Composable callback/type | override fun ProvideTheme(content: @Composable () -> Unit) = Theme(content) |
| 49 | ui-function / ProvideTheme | override fun ProvideTheme(content: @Composable () -> Unit) = Theme(content) |
| 51 | ui-function / polyGlassDark | override fun polyGlassDark(): OneConfigUITheme = PolyGlassDark |
| 53 | ui-function / polyGlassLight | override fun polyGlassLight(): OneConfigUITheme = PolyGlassLight |
| 55 | ui-function / minecraftDark | override fun minecraftDark(): OneConfigUITheme = MinecraftDark |
| 57 | ui-function / minecraftLight | override fun minecraftLight(): OneConfigUITheme = MinecraftLight |
| 59 | ui-function / activeTheme | override fun activeTheme(): OneConfigUITheme? = ThemeRegistry.activeTheme |
| 61 | ui-function / activateTheme | override fun activateTheme(theme: OneConfigUITheme) { |
| 66 | ui-function / ensureThemeLoaded | override fun ensureThemeLoaded() { |
| 71 | composable / concentric | @Composable |
| 72 | ui-function / concentric | override fun concentric(shape: Shape, inset: Dp, minRadius: Dp): Shape = shape.concentric(inset, minRadius) |
| 74 | composable / Icon | @Composable |
| 75 | ui-function / Icon | override fun Icon(iconName: String, color: Color, modifier: Modifier, fitAspectRatio: Boolean) = |
| 78 | composable / Text | @Composable |
| 79 | ui-function / Text | override fun Text( |
| 92 | composable / Chip | @Composable |
| 93 | ui-function / Chip | override fun Chip(label: String, selected: Boolean, icon: String?, onClick: () -> Unit) = |
| 96 | composable / IconButton | @Composable |
| 97 | ui-function / IconButton | override fun IconButton( |
| 107 | composable / Tooltip | @Composable |
| 108 | ui-function / Tooltip | override fun Tooltip( |
| 109 | composable / @Composable callback/type | text: @Composable () -> Unit, |
| 114 | composable / @Composable callback/type | content: @Composable () -> Unit, |
| 117 | ui-function / onClick | override fun onClick( |
| 124 | composable / fadingEdges | @Composable |
| 125 | ui-function / fadingEdges | override fun fadingEdges(modifier: Modifier, scrollState: ScrollState, color: Color, length: Dp): Modifier = |
| 128 | dialog-or-popup / createColorPicker | override fun createColorPicker(initialColor: Color, hasAlpha: Boolean): Any = ColorPickerModel(initialColor, hasAlpha) |
| 128 | ui-function / createColorPicker | override fun createColorPicker(initialColor: Color, hasAlpha: Boolean): Any = ColorPickerModel(initialColor, hasAlpha) |
| 130 | composable / ColorPickerPopup | @Composable |
| 131 | dialog-or-popup / ColorPickerPopup | override fun ColorPickerPopup(model: Any, onColorChanged: (Color) -> Unit, onClose: () -> Unit, chromaCapable: Boolean) = |
| 131 | ui-function / ColorPickerPopup | override fun ColorPickerPopup(model: Any, onColorChanged: (Color) -> Unit, onClose: () -> Unit, chromaCapable: Boolean) = |
| 132 | dialog-or-popup / ColorPickerPopup | InternalColorPickerPopup(model as ColorPickerModel, onColorChanged, onClose, chromaCapable) |
| 134 | ui-function / colorToHex | override fun colorToHex(color: Color): String = internalColorToHex(color) |
| 136 | composable / optionWidth | @Composable |
| 137 | ui-function / optionWidth | override fun optionWidth(): Dp = LocalOptionWidth.current |
| 139 | composable / pageSearchQuery | @Composable |
| 140 | ui-function / pageSearchQuery | override fun pageSearchQuery(): String = shellPageSearchQuery() |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/internal/OneConfigUIExtensionsBridge.kt

`file-9dd5d0c2711227501505`；状态 **pending**；领域 shell-navigation；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 8 | ui-function / modOrder | fun modOrder(): List<String> |
| 10 | ui-function / replaceModOrder | fun replaceModOrder(ids: List<String>) |
| 12 | ui-function / modSortModeId | fun modSortModeId(): String |
| 14 | ui-function / refreshOption | fun refreshOption(property: Property<*>) |
| 23 | ui-function / extensionsBridge | internal fun extensionsBridge(): OneConfigUIExtensionsBridge? = loadedExtensionsBridge |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/internal/OneConfigUIExtensionsBridgeImpl.kt

`file-ff9d26a96ce6725ac404`；状态 **pending**；领域 shell-navigation；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 9 | ui-function / modOrder | override fun modOrder(): List<String> = ModOrder.current() |
| 11 | ui-function / replaceModOrder | override fun replaceModOrder(ids: List<String>) = ModOrder.replace(ids) |
| 13 | ui-function / modSortModeId | override fun modSortModeId(): String = ModSortStore.selectedId |
| 15 | ui-function / refreshOption | override fun refreshOption(property: Property<*>) = bumpResetEpoch(property) |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/internal/OneConfigUIKeybindBridge.kt

`file-4a9ff531d78a56b822cc`；状态 **pending**；领域 keybind；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 9 | keybind / ConfigKeybind | fun configKeybinds(): List<ConfigKeybind> |
| 9 | ui-function / configKeybinds | fun configKeybinds(): List<ConfigKeybind> |
| 15 | keybind / OneConfigKeybind | fun configKeybind(keybind: OneConfigKeybind, title: String, path: String, modId: String, modTitle: String): ConfigKeybind = |
| 15 | keybind / ConfigKeybind | fun configKeybind(keybind: OneConfigKeybind, title: String, path: String, modId: String, modTitle: String): ConfigKeybind = |
| 15 | ui-function / configKeybind | fun configKeybind(keybind: OneConfigKeybind, title: String, path: String, modId: String, modTitle: String): ConfigKeybind = |
| 16 | keybind / ConfigKeybind | ConfigKeybind(keybind, title, path, modId, modTitle) |
| 25 | ui-function / keybindBridge | internal fun keybindBridge(): OneConfigUIKeybindBridge? = loadedKeybindBridge |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/internal/OneConfigUIKeybindBridgeImpl.kt

`file-6cfcd3a58a65bcbb5a62`；状态 **pending**；领域 keybind；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 10 | keybind / ConfigKeybind | override fun configKeybinds(): List<ConfigKeybind> = collectAllKeybindGroups().flatMap { group -> |
| 10 | ui-function / configKeybinds | override fun configKeybinds(): List<ConfigKeybind> = collectAllKeybindGroups().flatMap { group -> |
| 13 | keybind / OneConfigKeybind | val keybind = entry.prop.get() as? OneConfigKeybind ?: return@mapNotNull null |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/internal/OneConfigUISearchBridge.kt

`file-b1297ee5ce403fa13d08`；状态 **pending**；领域 shell-navigation；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 11 | search-provider / SearchProvider | fun register(provider: SearchProvider) |
| 11 | ui-function / register | fun register(provider: SearchProvider) |
| 13 | search-provider / SearchProvider | fun unregister(provider: SearchProvider): Boolean |
| 13 | ui-function / unregister | fun unregister(provider: SearchProvider): Boolean |
| 15 | ui-function / documentIds | fun documentIds(): Set<String> |
| 17 | ui-function / document | fun document(id: String): SearchDocument? |
| 23 | search-provider / SearchScope | fun scope(key: String): SearchScope = SearchScope(key) |
| 23 | search-provider / SearchScope | fun scope(key: String): SearchScope = SearchScope(key) |
| 23 | ui-function / scope | fun scope(key: String): SearchScope = SearchScope(key) |
| 26 | ui-function / metadata | fun metadata( |
| 40 | search-provider / SearchScope | fun document(id: String, scopes: Set<SearchScope>, metadata: SearchMetadata): SearchDocument = SearchDocument(id, scopes, metadata) |
| 40 | ui-function / document | fun document(id: String, scopes: Set<SearchScope>, metadata: SearchMetadata): SearchDocument = SearchDocument(id, scopes, metadata) |
| 49 | ui-function / searchBridge | internal fun searchBridge(): OneConfigUISearchBridge? = loadedSearchBridge |
| 52 | ui-function / requireSearchBridge | internal fun requireSearchBridge(): OneConfigUISearchBridge = |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/internal/OneConfigUISearchBridgeImpl.kt

`file-f29a79b65ae757ba0580`；状态 **pending**；领域 shell-navigation；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 17 | search-provider / SearchProvider | private val adapters = ConcurrentHashMap<PublicSearchProvider, SearchProvider>() |
| 17 | search-provider / SearchProvider | private val adapters = ConcurrentHashMap<PublicSearchProvider, SearchProvider>() |
| 19 | search-provider / SearchProvider | override fun register(provider: PublicSearchProvider) { |
| 19 | ui-function / register | override fun register(provider: PublicSearchProvider) { |
| 25 | search-provider / SearchProviderRegistry.registerSearchProvider | SearchProviderRegistry.registerSearchProvider(adapter) |
| 25 | search-provider / registerSearchProvider | SearchProviderRegistry.registerSearchProvider(adapter) |
| 28 | search-provider / SearchProvider | override fun unregister(provider: PublicSearchProvider): Boolean { |
| 28 | ui-function / unregister | override fun unregister(provider: PublicSearchProvider): Boolean { |
| 30 | search-provider / SearchProviderRegistry.unregisterSearchProvider | SearchProviderRegistry.unregisterSearchProvider(adapter) |
| 34 | search-provider / SearchCorpus.corpus | override fun documentIds(): Set<String> = SearchCorpus.corpus.keys.toSet() |
| 34 | ui-function / documentIds | override fun documentIds(): Set<String> = SearchCorpus.corpus.keys.toSet() |
| 36 | search-provider / SearchCorpus.corpus | override fun document(id: String): PublicSearchDocument? = SearchCorpus.corpus[id]?.toPublic() |
| 36 | ui-function / document | override fun document(id: String): PublicSearchDocument? = SearchCorpus.corpus[id]?.toPublic() |
| 39 | search-provider / SearchProvider.displayName | internal fun SearchProvider.displayName(): String = |
| 39 | ui-function / displayName | internal fun SearchProvider.displayName(): String = |
| 42 | search-provider / SearchProvider | private class PublicSearchProviderAdapter(val provider: PublicSearchProvider) : SearchProvider { |
| 42 | search-provider / SearchProvider | private class PublicSearchProviderAdapter(val provider: PublicSearchProvider) : SearchProvider { |
| 45 | ui-function / isAvailable | override fun isAvailable(): Boolean = provider.isAvailable() |
| 47 | search-provider / SearchScope | override fun search(query: String, scopes: Set<SearchScope>): List<SearchDocument<*>> { |
| 47 | ui-function / search | override fun search(query: String, scopes: Set<SearchScope>): List<SearchDocument<*>> { |
| 48 | search-provider / SearchCorpus.corpus | val corpus = SearchCorpus.corpus |
| 52 | ui-function / searchGrouped | override fun <T> searchGrouped( |
| 54 | search-provider / SearchScope | scopes: Set<SearchScope>, |
| 58 | ui-function / onCorpusUpdate | override suspend fun onCorpusUpdate(added: List<SearchDocument<*>>, removed: Set<String>) { |
| 63 | search-provider / SearchScope.toPublic | private fun SearchScope.toPublic(): PublicSearchScope = when (this) { |
| 63 | search-provider / SearchScope | private fun SearchScope.toPublic(): PublicSearchScope = when (this) { |
| 63 | ui-function / toPublic | private fun SearchScope.toPublic(): PublicSearchScope = when (this) { |
| 64 | search-provider / SearchScope.Mods | SearchScope.Mods -> PublicSearchScope.MODS |
| 64 | search-provider / SearchScope.MODS | SearchScope.Mods -> PublicSearchScope.MODS |
| 65 | search-provider / SearchScope.Options | SearchScope.Options -> PublicSearchScope.OPTIONS |
| 65 | search-provider / SearchScope.OPTIONS | SearchScope.Options -> PublicSearchScope.OPTIONS |
| 66 | search-provider / SearchScope.Keybinds | SearchScope.Keybinds -> PublicSearchScope.KEYBINDS |
| 66 | search-provider / SearchScope.KEYBINDS | SearchScope.Keybinds -> PublicSearchScope.KEYBINDS |
| 67 | search-provider / SearchScope.Huds | SearchScope.Huds -> PublicSearchScope.HUDS |
| 67 | search-provider / SearchScope.HUDS | SearchScope.Huds -> PublicSearchScope.HUDS |
| 68 | search-provider / SearchScope.Config | is SearchScope.Config -> PublicSearchScope.config(id) |
| 68 | search-provider / SearchScope.config | is SearchScope.Config -> PublicSearchScope.config(id) |
| 71 | ui-function / toPublic | private fun SearchDocument<*>.toPublic(): PublicSearchDocument { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/keybind/BindNotInScreen.kt

`file-e79e54cc60214e9896ea`；状态 **pending**；领域 keybind；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 5 | route / BindNotInScreen | class BindNotInScreen( |
| 11 | keybind / OneConfigKeybind | ) : OneConfigKeybind(keyCodes, mouseBtns, mods, durationNanos, action) { |
| 12 | ui-function / test | override fun test(downKeys: Set<Int>, downMouse: Set<Int>, currentMods: Byte): Boolean { |
| 16 | keybind / OneConfigKeybind | override fun copyWith(keyCodes: IntArray?, mouseBtns: IntArray?, mods: Byte): OneConfigKeybind = |
| 16 | ui-function / copyWith | override fun copyWith(keyCodes: IntArray?, mouseBtns: IntArray?, mods: Byte): OneConfigKeybind = |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/keybind/ConfigKeybind.kt

`file-f2b57f82b54896e32c18`；状态 **pending**；领域 keybind；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 6 | keybind / ConfigKeybind | class ConfigKeybind internal constructor( |
| 7 | keybind / OneConfigKeybind | val keybind: OneConfigKeybind, |
| 13 | ui-function / toString | override fun toString(): String = "ConfigKeybind(modId=$modId, path=$path)" |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/keybind/KeyModifiers.kt

`file-376859febcb08e60dea9`；状态 **pending**；领域 keybind；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 20 | ui-function / of | fun of(key: Int): Byte = BY_KEY[key] ?: NONE |
| 22 | ui-function / has | fun has(mask: Byte, flag: Byte) = (mask and flag) == flag |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/keybind/KeybindHelper.kt

`file-2ebad14977a5aab1d714`；状态 **pending**；领域 keybind；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 17 | ui-function / key | fun key(vararg codes: Int) = apply { keyCodes.addAll(codes.asList()) } |
| 18 | ui-function / mouse | fun mouse(vararg btns: Int) = apply { mouseBtns.addAll(btns.asList()) } |
| 22 | ui-function / name | fun name(name: String) = apply { this.name = name } |
| 28 | ui-function / category | fun category(category: String) = apply { this.category = category } |
| 29 | ui-function / shift | fun shift() = apply { mods = (mods or KeyModifiers.SHIFT).toByte() } |
| 30 | ui-function / ctrl | fun ctrl() = apply { mods = (mods or KeyModifiers.CTRL).toByte() } |
| 31 | ui-function / alt | fun alt() = apply { mods = (mods or KeyModifiers.ALT).toByte() } |
| 32 | ui-function / meta | fun meta() = apply { mods = (mods or KeyModifiers.META).toByte() } |
| 33 | ui-function / inScreens | fun inScreens() = apply { inScreens = true } |
| 34 | ui-function / firesWhileTyping | fun firesWhileTyping() = apply { firesWhileTyping = true } |
| 35 | ui-function / duration | fun duration(nanos: Long) = apply { durationNanos = nanos } |
| 36 | ui-function / action | fun action(fn: (Boolean) -> Boolean) = apply { action = fn } |
| 37 | ui-function / action | fun action(fn: () -> Unit) = apply { action = { b -> if (b) fn(); true } } |
| 38 | ui-function / action | fun action(fn: Runnable) = apply { action = { b -> if (b) fn.run(); true } } |
| 39 | ui-function / action | fun action(fn: Consumer<Boolean>) = apply { action = { b -> fn.accept(b); true } } |
| 49 | keybind / OneConfigKeybind | fun build(): OneConfigKeybind { |
| 49 | ui-function / build | fun build(): OneConfigKeybind { |
| 53 | keybind / OneConfigKeybind | val bind = if (inScreens) OneConfigKeybind(keys, mouse, mods, durationNanos, fn) |
| 61 | keybind / OneConfigKeybind | fun register(): OneConfigKeybind = KeybindManager.register(build()) |
| 61 | keybind / KeybindManager.register | fun register(): OneConfigKeybind = KeybindManager.register(build()) |
| 61 | ui-function / register | fun register(): OneConfigKeybind = KeybindManager.register(build()) |
| 67 | ui-function / builder | fun builder() = KeybindHelper() |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/keybind/KeybindManager.kt

`file-6762e8fc3e61fd642789`；状态 **pending**；领域 keybind；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 20 | keybind / KeybindManager | object KeybindManager { |
| 23 | keybind / OneConfigKeybind | private val binds = CopyOnWriteArrayList<OneConfigKeybind>() |
| 24 | keybind / OneConfigKeybind | private val activeBinds = HashSet<OneConfigKeybind>() |
| 40 | keybind / OneConfigKeybind | private val rebindListeners = ArrayList<(OneConfigKeybind, OneConfigKeybind) -> Unit>() |
| 40 | keybind / OneConfigKeybind | private val rebindListeners = ArrayList<(OneConfigKeybind, OneConfigKeybind) -> Unit>() |
| 43 | keybind / OneConfigKeybind | private val menuEditListeners = ArrayList<(OneConfigKeybind) -> Unit>() |
| 79 | keybind / TextInputFocus.clear | TextInputFocus.clear() |
| 90 | keybind / OneConfigKeybind | fun register(bind: OneConfigKeybind): OneConfigKeybind { |
| 90 | keybind / OneConfigKeybind | fun register(bind: OneConfigKeybind): OneConfigKeybind { |
| 90 | ui-function / register | fun register(bind: OneConfigKeybind): OneConfigKeybind { |
| 97 | keybind / OneConfigKeybind | fun unregister(bind: OneConfigKeybind) { |
| 97 | ui-function / unregister | fun unregister(bind: OneConfigKeybind) { |
| 104 | keybind / OneConfigKeybind | fun isRegistered(bind: OneConfigKeybind): Boolean = bind in binds |
| 104 | ui-function / isRegistered | fun isRegistered(bind: OneConfigKeybind): Boolean = bind in binds |
| 113 | keybind / OneConfigKeybind | fun refreshMinecraftBinding(bind: OneConfigKeybind) { |
| 113 | ui-function / refreshMinecraftBinding | fun refreshMinecraftBinding(bind: OneConfigKeybind) { |
| 126 | keybind / OneConfigKeybind | fun replace(old: OneConfigKeybind?, new: OneConfigKeybind): OneConfigKeybind { |
| 126 | keybind / OneConfigKeybind | fun replace(old: OneConfigKeybind?, new: OneConfigKeybind): OneConfigKeybind { |
| 126 | keybind / OneConfigKeybind | fun replace(old: OneConfigKeybind?, new: OneConfigKeybind): OneConfigKeybind { |
| 126 | ui-function / replace | fun replace(old: OneConfigKeybind?, new: OneConfigKeybind): OneConfigKeybind { |
| 143 | keybind / OneConfigKeybind | fun addRebindListener(listener: (OneConfigKeybind, OneConfigKeybind) -> Unit) { |
| 143 | keybind / OneConfigKeybind | fun addRebindListener(listener: (OneConfigKeybind, OneConfigKeybind) -> Unit) { |
| 143 | ui-function / addRebindListener | fun addRebindListener(listener: (OneConfigKeybind, OneConfigKeybind) -> Unit) { |
| 148 | keybind / OneConfigKeybind | fun addMenuEditListener(listener: (OneConfigKeybind) -> Unit) { |
| 148 | ui-function / addMenuEditListener | fun addMenuEditListener(listener: (OneConfigKeybind) -> Unit) { |
| 158 | keybind / OneConfigKeybind | fun notifyMenuEdit(bind: OneConfigKeybind) { |
| 158 | ui-function / notifyMenuEdit | fun notifyMenuEdit(bind: OneConfigKeybind) { |
| 170 | ui-function / rebindFromMinecraft | fun rebindFromMinecraft( |
| 171 | keybind / OneConfigKeybind | old: OneConfigKeybind, |
| 175 | keybind / OneConfigKeybind | ): OneConfigKeybind { |
| 194 | keybind / OneConfigKeybind | fun findConflicts(bind: OneConfigKeybind): List<OneConfigKeybind> { |
| 194 | keybind / OneConfigKeybind | fun findConflicts(bind: OneConfigKeybind): List<OneConfigKeybind> { |
| 194 | ui-function / findConflicts | fun findConflicts(bind: OneConfigKeybind): List<OneConfigKeybind> { |
| 201 | keybind / ConfigKeybind | fun configKeybinds(): List<ConfigKeybind> = keybindBridge()?.configKeybinds() ?: emptyList() |
| 201 | ui-function / configKeybinds | fun configKeybinds(): List<ConfigKeybind> = keybindBridge()?.configKeybinds() ?: emptyList() |
| 205 | ui-function / addInputCapture | fun addInputCapture(capture: BooleanSupplier) { |
| 211 | ui-function / removeInputCapture | fun removeInputCapture(capture: BooleanSupplier): Boolean = inputCaptures.remove(capture) |
| 215 | ui-function / isInputCaptured | fun isInputCaptured(): Boolean = inputCaptures.any { capture -> |
| 226 | ui-function / hasTriggeredMouseBind | fun hasTriggeredMouseBind(button: Int): Boolean = binds.any { isTriggeredByMouse(it, button) } |
| 230 | keybind / OneConfigKeybind | fun isTriggeredByMouse(bind: OneConfigKeybind, button: Int): Boolean = |
| 230 | ui-function / isTriggeredByMouse | fun isTriggeredByMouse(bind: OneConfigKeybind, button: Int): Boolean = |
| 234 | ui-function / builder | fun builder() = KeybindHelper() |
| 236 | ui-function / checkBinds | private fun checkBinds() { |
| 237 | keybind / TextInputFocus.isTyping | val typing = TextInputFocus.isTyping |
| 257 | ui-function / clearState | private fun clearState() { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/keybind/KeybindUtils.kt

`file-6e59bb8bf2f95a94a258`；状态 **pending**；领域 keybind；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 14 | ui-function / isActionModifierPressed | fun isActionModifierPressed(modifiers: PointerKeyboardModifiers): Boolean = |
| 15 | keybind / isMetaPressed | if (DesktopHelper.isMac) modifiers.isMetaPressed else modifiers.isCtrlPressed |
| 15 | keybind / isCtrlPressed | if (DesktopHelper.isMac) modifiers.isMetaPressed else modifiers.isCtrlPressed |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/keybind/MinecraftKeybindBridge.kt

`file-5d9c8ace267f2498d53d`；状态 **pending**；领域 keybind；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 12 | keybind / OneConfigKeybind | fun register(bind: OneConfigKeybind) |
| 12 | ui-function / register | fun register(bind: OneConfigKeybind) |
| 15 | keybind / OneConfigKeybind | fun unregister(bind: OneConfigKeybind) |
| 15 | ui-function / unregister | fun unregister(bind: OneConfigKeybind) |
| 23 | keybind / OneConfigKeybind | fun sync(bind: OneConfigKeybind) |
| 23 | ui-function / sync | fun sync(bind: OneConfigKeybind) |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/keybind/OneConfigKeybind.kt

`file-c69e8a338f161f947988`；状态 **pending**；领域 keybind；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 7 | keybind / OneConfigKeybind | open class OneConfigKeybind( |
| 45 | keybind / OneConfigKeybind | var defaultKeybind: OneConfigKeybind? = null |
| 87 | keybind / OneConfigKeybind | open fun copyWith(keyCodes: IntArray?, mouseBtns: IntArray?, mods: Byte): OneConfigKeybind = |
| 87 | ui-function / copyWith | open fun copyWith(keyCodes: IntArray?, mouseBtns: IntArray?, mods: Byte): OneConfigKeybind = |
| 88 | keybind / OneConfigKeybind | OneConfigKeybind(keyCodes, mouseBtns, mods, durationNanos, action).also { |
| 100 | ui-function / mergeOverwrite | override fun mergeOverwrite(incoming: Any): Any { |
| 101 | keybind / OneConfigKeybind | if (incoming is OneConfigKeybind && incoming !== this) { |
| 112 | ui-function / test | open fun test(downKeys: Set<Int>, downMouse: Set<Int>, currentMods: Byte): Boolean { |
| 120 | keybind / OneConfigKeybind | fun conflictsWith(other: OneConfigKeybind): Boolean { |
| 120 | ui-function / conflictsWith | fun conflictsWith(other: OneConfigKeybind): Boolean { |
| 127 | ui-function / sameCodes | private fun sameCodes(a: IntArray?, b: IntArray?): Boolean { |
| 140 | ui-function / displayName | fun displayName(): String { |
| 152 | ui-function / keyName | fun keyName(key: Int): String = Platform.compatibility().keys().keyName(key) |
| 154 | ui-function / modifierNames | private fun modifierNames(mods: Byte): List<String> = buildList { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/keybind/TextInputFocus.kt

`file-2caac7749d7fabe5aaff`；状态 **pending**；领域 keybind；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 14 | keybind / TextInputFocus | object TextInputFocus { |
| 30 | ui-function / acquire | fun acquire(token: Any) { |
| 35 | ui-function / release | fun release(token: Any) { |
| 41 | ui-function / clear | fun clear() { |
| 45 | ui-function / currentScreen | private fun currentScreen(): Any = Platform.screen().current<Any?>() ?: NO_SCREEN |
| 48 | ui-function / trackTextInputFocus | fun Modifier.trackTextInputFocus(): Modifier = this then TrackTextInputFocusElement |
| 51 | ui-function / create | override fun create() = TrackTextInputFocusNode() |
| 53 | ui-function / update | override fun update(node: TrackTextInputFocusNode) {} |
| 55 | ui-function / equals | override fun equals(other: Any?) = other === this |
| 57 | ui-function / hashCode | override fun hashCode() = System.identityHashCode(this) |
| 59 | ui-function / inspectableProperties | override fun InspectorInfo.inspectableProperties() { |
| 67 | ui-function / onFocusEvent | override fun onFocusEvent(focusState: FocusState) { |
| 71 | keybind / TextInputFocus.acquire | if (focused) TextInputFocus.acquire(this) else TextInputFocus.release(this) |
| 71 | keybind / TextInputFocus.release | if (focused) TextInputFocus.acquire(this) else TextInputFocus.release(this) |
| 74 | ui-function / onDetach | override fun onDetach() { |
| 77 | keybind / TextInputFocus.release | TextInputFocus.release(this) |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/page/ModConfigTarget.kt

`file-e0be9cb9c9dc89cab313`；状态 **pending**；领域 shell-navigation；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 7 | ui-function / equals | override fun equals(other: Any?): Boolean = |
| 10 | ui-function / hashCode | override fun hashCode(): Int = 31 * modId.hashCode() + (category?.hashCode() ?: 0) |
| 12 | ui-function / toString | override fun toString(): String = "ModConfigTarget(modId=$modId, category=$category)" |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/page/OneConfigPage.kt

`file-83f74bed30133386069a`；状态 **pending**；领域 shell-navigation；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 7 | route / OneConfigPage | abstract class OneConfigPage(val id: String, val title: PageText, val icon: String, val group: PageGroup) { |
| 18 | composable / Content | @Composable |
| 19 | ui-function / Content | abstract fun Content() |
| 21 | ui-function / toString | override fun toString(): String = "OneConfigPage(id=$id)" |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/page/OneConfigPages.kt

`file-989d85b048df7368a4df`；状态 **pending**；领域 shell-navigation；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 16 | ui-function / register | fun register(page: OneConfigPage) { |
| 24 | ui-function / unregister | fun unregister(id: String): Boolean = synchronized(lock) { Snapshot.withMutableSnapshot { pages.removeAll { it.id == id } } } |
| 27 | ui-function / all | fun all(): List<OneConfigPage> = |
| 31 | ui-function / byId | fun byId(id: String): OneConfigPage? = pages.toList().firstOrNull { it.id == id } |
| 34 | route / ModConfigTarget | fun modConfig(modId: String): ModConfigTarget = ModConfigTarget(modId, null) |
| 34 | ui-function / modConfig | fun modConfig(modId: String): ModConfigTarget = ModConfigTarget(modId, null) |
| 37 | route / ModConfigTarget | fun modConfig(modId: String, category: String?): ModConfigTarget = ModConfigTarget(modId, category) |
| 37 | ui-function / modConfig | fun modConfig(modId: String, category: String?): ModConfigTarget = ModConfigTarget(modId, category) |
| 39 | ui-function / requireNamespacedId | internal fun requireNamespacedId(id: String) { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/page/PageGroup.kt

`file-25afe6c342baef0f750d`；状态 **pending**；领域 shell-navigation；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 7 | ui-function / equals | override fun equals(other: Any?): Boolean = this === other \|\| (other is PageGroup && other.id == id) |
| 9 | ui-function / hashCode | override fun hashCode(): Int = id.hashCode() |
| 11 | ui-function / toString | override fun toString(): String = "PageGroup(id=$id, order=$order)" |
| 37 | ui-function / create | fun create(id: String, title: PageText, order: Int): PageGroup { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/page/PageSearchQuery.kt

`file-90f5b66d4c53497656a8`；状态 **pending**；领域 shell-navigation；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 9 | composable / @Composable callback/type | @Composable get() = componentsBridge().pageSearchQuery() |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/page/PageText.kt

`file-1ae013f421789928f87d`；状态 **pending**；领域 shell-navigation；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 7 | ui-function / equals | override fun equals(other: Any?): Boolean = |
| 10 | ui-function / hashCode | override fun hashCode(): Int = 31 * (key?.hashCode() ?: 0) + fallback.hashCode() |
| 12 | ui-function / toString | override fun toString(): String = "PageText(key=$key, fallback=$fallback)" |
| 16 | ui-function / translatable | fun translatable(key: String, fallback: String): PageText { |
| 22 | ui-function / literal | fun literal(text: String): PageText = PageText(null, text) |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/search/SearchDocument.kt

`file-4d695c4de6a9a2262fd9`；状态 **pending**；领域 search；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 6 | search-provider / SearchScope | class SearchDocument internal constructor(val id: String, val scopes: Set<SearchScope>, val metadata: SearchMetadata) { |
| 7 | ui-function / toString | override fun toString(): String = "SearchDocument(id=$id, scopes=$scopes)" |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/search/SearchMetadata.kt

`file-24898f94fa4227726f62`；状态 **pending**；领域 search；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 21 | ui-function / toString | override fun toString(): String = "SearchMetadata(title=$title, id=$id, path=$path)" |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/search/SearchProvider.kt

`file-54c907e3d11b4e927619`；状态 **pending**；领域 search；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 6 | search-provider / SearchProvider | abstract class SearchProvider { |
| 9 | ui-function / isAvailable | abstract fun isAvailable(): Boolean |
| 11 | search-provider / SearchScope | abstract fun search(query: String, scopes: Set<SearchScope>): List<String> |
| 11 | ui-function / search | abstract fun search(query: String, scopes: Set<SearchScope>): List<String> |
| 13 | ui-function / onCorpusUpdate | open fun onCorpusUpdate(added: List<SearchDocument>, removed: Set<String>) {} |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/search/SearchProviders.kt

`file-0b89c8f8fbba91912249`；状态 **pending**；领域 search；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 8 | search-provider / SearchProviders | object SearchProviders { |
| 10 | search-provider / SearchProvider | fun register(provider: SearchProvider) { |
| 10 | ui-function / register | fun register(provider: SearchProvider) { |
| 15 | search-provider / SearchProvider | fun unregister(provider: SearchProvider): Boolean = searchBridge()?.unregister(provider) ?: false |
| 15 | ui-function / unregister | fun unregister(provider: SearchProvider): Boolean = searchBridge()?.unregister(provider) ?: false |
| 18 | ui-function / documentIds | fun documentIds(): Set<String> = searchBridge()?.documentIds() ?: emptySet() |
| 21 | ui-function / document | fun document(id: String): SearchDocument? = searchBridge()?.document(id) |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/search/SearchScope.kt

`file-5d3df574d078ad509b40`；状态 **pending**；领域 search；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 6 | search-provider / SearchScope | class SearchScope internal constructor(val key: String) { |
| 7 | search-provider / SearchScope | override fun equals(other: Any?): Boolean = this === other \|\| (other is SearchScope && other.key == key) |
| 7 | ui-function / equals | override fun equals(other: Any?): Boolean = this === other \|\| (other is SearchScope && other.key == key) |
| 9 | ui-function / hashCode | override fun hashCode(): Int = key.hashCode() |
| 11 | ui-function / toString | override fun toString(): String = "SearchScope($key)" |
| 15 | search-provider / SearchScope | val MODS: SearchScope = SearchScope("mods") |
| 15 | search-provider / SearchScope | val MODS: SearchScope = SearchScope("mods") |
| 18 | search-provider / SearchScope | val OPTIONS: SearchScope = SearchScope("options") |
| 18 | search-provider / SearchScope | val OPTIONS: SearchScope = SearchScope("options") |
| 21 | search-provider / SearchScope | val KEYBINDS: SearchScope = SearchScope("keybinds") |
| 21 | search-provider / SearchScope | val KEYBINDS: SearchScope = SearchScope("keybinds") |
| 24 | search-provider / SearchScope | val HUDS: SearchScope = SearchScope("huds") |
| 24 | search-provider / SearchScope | val HUDS: SearchScope = SearchScope("huds") |
| 27 | search-provider / SearchScope | fun config(id: String): SearchScope = SearchScope("config:$id") |
| 27 | search-provider / SearchScope | fun config(id: String): SearchScope = SearchScope("config:$id") |
| 27 | ui-function / config | fun config(id: String): SearchScope = SearchScope("config:$id") |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/theme/OneConfigTheme.kt

`file-d83ab44cce5ca3208660`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 26 | composable / @Composable callback/type | @Composable get() = componentsBridge().currentTheme() |
| 29 | composable / @Composable callback/type | @Composable get() = componentsBridge().accent() |
| 36 | ui-function / builtIn | fun builtIn(): List<OneConfigUITheme> = listOf(POLY_GLASS_DARK, POLY_GLASS_LIGHT, MINECRAFT_DARK, MINECRAFT_LIGHT) |
| 39 | ui-function / activate | fun activate(theme: OneConfigUITheme) { |
| 44 | ui-function / ensureLoaded | fun ensureLoaded() { |
| 53 | ui-function / overrideLogo | fun overrideLogo(path: String?) { |
| 59 | composable / ProvideOneConfigTheme | @Composable |
| 60 | composable / @Composable callback/type | fun ProvideOneConfigTheme(content: @Composable () -> Unit) { |
| 60 | ui-function / ProvideOneConfigTheme | fun ProvideOneConfigTheme(content: @Composable () -> Unit) { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/theme/OneConfigUITheme.kt

`file-b292a15a437ea0a92db4`；状态 **pending**；领域 shell-navigation；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

该文件支撑 shell-navigation；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/theme/Radii.kt

`file-72c0ee7dde790dd9c735`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 11 | composable / concentric | @Composable |
| 12 | ui-function / concentric | fun Shape.concentric(inset: Dp): Shape = componentsBridge().concentric(this, inset, 0.dp) |
| 15 | composable / concentric | @Composable |
| 16 | ui-function / concentric | fun Shape.concentric(inset: Dp, minRadius: Dp): Shape = componentsBridge().concentric(this, inset, minRadius) |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/presets/MuoniumMusicStateProfiles.kt

`file-1914c35e27d0a031f5b0`；状态 **pending**；领域 presets；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 48 | ui-function / attach | fun attach() { |
| 64 | ui-function / onTreeRegistered | override fun onTreeRegistered(tree: Tree) { |
| 71 | ui-function / onProfileSaving | override fun onProfileSaving(profile: String) { |
| 76 | ui-function / onProfileUnloading | override fun onProfileUnloading(profile: String) { |
| 81 | ui-function / onProfileOperationFinished | override fun onProfileOperationFinished() { |
| 86 | ui-function / onProfileChanged | override fun onProfileChanged(profile: String) { |
| 91 | ui-function / captureTo | private fun captureTo(profile: String) { |
| 113 | ui-function / writeEnvelope | private fun writeEnvelope(profile: String, json: String) { |
| 127 | ui-function / restoreActiveProfile | private fun restoreActiveProfile(reason: String) { |
| 196 | ui-function / envelopeFile | private fun envelopeFile(profile: String): Path = ConfigManager.profileDir(profile).resolve(MUSIC_STATE_FILE) |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/presets/MuoniumPresetBridge.kt

`file-28649eac8876de929e49`；状态 **pending**；领域 presets；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 47 | ui-function / available | fun available(): Boolean { |
| 52 | ui-function / resolve | private fun resolve() { |
| 70 | ui-function / method | private fun method(name: String, vararg types: Class<*>): Method? { |
| 85 | ui-function / call | private inline fun <T> call(member: String, fallback: T, invoke: () -> T): T { |
| 103 | ui-function / transient | private fun transient(member: String, failure: Throwable) { |
| 109 | ui-function / registerUi | fun registerUi(opener: java.util.function.Function<String, Boolean>): Boolean = call("registerUi", false) { |
| 116 | ui-function / unregisterUi | fun unregisterUi(opener: java.util.function.Function<String, Boolean>) = call("unregisterUi", Unit) { |
| 121 | ui-function / request | fun request(json: String): CompletableFuture<String>? = call("request", null) { |
| 127 | ui-function / captureLocalState | fun captureLocalState(): CompletableFuture<String>? = call("captureLocalState", null) { |
| 133 | ui-function / restoreLocalState | fun restoreLocalState(json: String): CompletableFuture<String>? = call("restoreLocalState", null) { |
| 148 | ui-function / request | fun request(action: String, body: JsonObject.() -> Unit = {}): String { |
| 157 | ui-function / catalog | fun catalog(): String = request("catalog") |
| 160 | ui-function / detail | fun detail(id: String): String = request("detail") { addProperty("id", id) } |
| 163 | ui-function / preview | fun preview(id: String, facets: Collection<String>): String = |
| 167 | ui-function / apply | fun apply(id: String, facets: Collection<String>): String = |
| 171 | ui-function / save | fun save(name: String, facets: Collection<String>): String = request("save") { |
| 177 | ui-function / importText | fun importText(text: String): String = request("import") { addProperty("text", text) } |
| 180 | ui-function / share | fun share(id: String): String = request("share") { addProperty("id", id) } |
| 183 | ui-function / delete | fun delete(id: String): String = request("delete") { addProperty("id", id) } |
| 186 | ui-function / undo | fun undo(): String = request("undo") |
| 188 | ui-function / facetRequest | private fun facetRequest(action: String, id: String, facets: Collection<String>): String = request(action) { |
| 193 | ui-function / facets | private fun facets(facets: Collection<String>): JsonArray { |
| 202 | ui-function / parse | fun parse(json: String): JsonObject? = try { |
| 210 | ui-function / ok | fun ok(result: JsonObject): Boolean = bool(result, "ok") |
| 213 | ui-function / errorOf | fun errorOf(result: JsonObject): String? = string(result, "error") |
| 217 | ui-function / changedKeys | fun changedKeys(result: JsonObject): Int { |
| 224 | ui-function / lines | fun lines(result: JsonObject): List<String> { |
| 231 | ui-function / catalogOf | fun catalogOf(result: JsonObject): CatalogData? { |
| 258 | ui-function / cardOf | fun cardOf(result: JsonObject): PresetCard? { |
| 264 | ui-function / shareText | fun shareText(result: JsonObject): String? = string(result, "text") |
| 266 | ui-function / cards | private fun cards(array: JsonArray?): List<PresetCard>? { |
| 276 | ui-function / cardFrom | private fun cardFrom(card: JsonObject): PresetCard? { |
| 289 | ui-function / strings | private fun strings(array: JsonArray?): List<String> { |
| 294 | ui-function / string | private fun string(obj: JsonObject, key: String): String? { |
| 299 | ui-function / bool | private fun bool(obj: JsonObject, key: String): Boolean { |
| 304 | ui-function / int | private fun int(obj: JsonObject, key: String): Int { |
| 344 | ui-function / looksValid | fun looksValid(json: String): Boolean { |
| 356 | ui-function / stateOf | fun stateOf(json: String): JsonElement? { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/presets/MuoniumPresets.kt

`file-7e03424e979453d7aec1`；状态 **pending**；领域 presets；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 29 | ui-function / install | fun install() { |
| 47 | ui-function / openTemplates | private fun openTemplates(mode: String?): Boolean { |
| 63 | ui-function / consumePendingMode | fun consumePendingMode(): String? = pendingMode.getAndSet(null) |
| 67 | ui-function / peekPendingMode | fun peekPendingMode(): String? = pendingMode.get() |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/OneConfigInterface.kt

`file-590072c2b684f35fd4ca`；状态 **pending**；领域 shell-navigation；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 50 | ui-function / guiCloseAnimationMillis | fun guiCloseAnimationMillis(): Long = |
| 51 | animation / guiClosingAnimation | if (!OneConfigConfig.guiClosingAnimation) 0L |
| 52 | animation / animationTime | else (OneConfigConfig.animationTime * 1000f).toLong().coerceIn(1L, MAX_CLOSE_ANIMATION_MS) |
| 58 | composable / OneConfigInterface | @Composable |
| 59 | ui-function / OneConfigInterface | fun OneConfigInterface( |
| 90 | animation / animateOpening | val animateOpening = !restoring && OneConfigConfig.showOpeningPageAnimation |
| 90 | animation / showOpeningPageAnimation | val animateOpening = !restoring && OneConfigConfig.showOpeningPageAnimation |
| 93 | animation / animateOpening | if (animateOpening) { |
| 102 | animation / withFrameNanos | withFrameNanos { } |
| 110 | animation / animateOpening | shell.navigation.navigate(initialRoute, clearSearch = !restoring, animate = animateOpening) |
| 124 | animation / withFrameNanos | withFrameNanos { } |
| 178 | animation / animationTime | val animMs = (OneConfigConfig.animationTime * 1000f).toInt().coerceAtLeast(1) |
| 179 | animation / guiOpenAnimation | val enterMs = if (OneConfigConfig.guiOpenAnimation) animMs else 1 |
| 181 | animation / animateFloatAsState | val dragAlpha by animateFloatAsState( |
| 183 | animation / tween | animationSpec = tween(150), |
| 187 | animation / RetainedVisibility | RetainedVisibility( |
| 189 | animation / tween | enter = tween(enterMs, easing = EaseOutExpo), |
| 190 | animation / tween | exit = tween(exitMs, easing = EaseOutCubic), |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/PlayerHeadLoader.kt

`file-b9da22e7f11be36092e6`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 25 | ui-function / cachedLocalPlayerHeadPng | fun cachedLocalPlayerHeadPng(mc: Minecraft): ByteArray? = profileId(mc)?.let { cache[it] } |
| 32 | ui-function / loadLocalPlayerHeadPng | fun loadLocalPlayerHeadPng(mc: Minecraft): ByteArray? { |
| 43 | ui-function / profileId | private fun profileId(mc: Minecraft): UUID? = runCatching { mc.user.profileId }.getOrNull() |
| 45 | ui-function / loadFromCachedSkin | private fun loadFromCachedSkin(mc: Minecraft): ByteArray? { |
| 58 | ui-function / onClientThread | private fun onClientThread(mc: Minecraft, task: Supplier<NativeImage>): NativeImage? { |
| 73 | ui-function / encodeHeadPng | private fun encodeHeadPng(head: NativeImage): ByteArray? = head.use { |
| 87 | ui-function / loadFromWeb | private fun loadFromWeb(profileId: UUID): ByteArray? { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/api/ConfigData.kt

`file-b3cbe3e2269c15b2206f`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 29 | composable / @Composable callback/type | val preview: (@Composable (Modifier) -> Unit)? get() = null |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/api/ConfigRegistry.kt

`file-85736ab5712f4280b303`；状态 **pending**；领域 settings；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 106 | ui-function / shouldShowModCard | fun shouldShowModCard(config: ConfigData): Boolean = |
| 109 | ui-function / shouldShowModCardId | fun shouldShowModCardId(id: String): Boolean { |
| 114 | ui-function / shouldShowInSearch | fun shouldShowInSearch(config: ConfigData): Boolean = |
| 122 | ui-function / loadFrom | fun loadFrom(manager: ConfigManager, source: ConfigSource) { |
| 131 | search-provider / SearchCorpus.invalidate | SearchCorpus.invalidate(ConfigDocumentSource) |
| 137 | ui-function / registerTree | fun registerTree( |
| 149 | ui-function / register | fun register(data: ConfigData) { |
| 153 | ui-function / unregister | fun unregister(id: String) { |
| 155 | search-provider / SearchCorpus.invalidate | SearchCorpus.invalidate(ConfigDocumentSource) |
| 160 | ui-function / findById | fun findById(id: String): ConfigData? = configs.find { it.id == id } |
| 162 | ui-function / findTree | fun findTree(id: String): Tree? = (findById(id) as? TreeConfigData)?.tree |
| 164 | ui-function / upsert | private fun upsert(data: ConfigData, bumpRevision: Boolean): Boolean { |
| 172 | search-provider / SearchCorpus.invalidate | SearchCorpus.invalidate(ConfigDocumentSource) |
| 180 | ui-function / wraps | private fun ConfigData.wraps(other: ConfigData): Boolean { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/api/ModCardGrouping.kt

`file-00da61a39ab79a123fb3`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 22 | ui-function / modCardGroupRank | fun modCardGroupRank(id: String): Int { |
| 28 | ui-function / modCardOrder | fun modCardOrder(): Comparator<ConfigData> { |
| 38 | ui-function / buildModGridEntries | fun buildModGridEntries(sorted: List<ConfigData>, collapsed: (String) -> Boolean): List<ModGridEntry> { |
| 59 | ui-function / modFavoriteAt | fun modFavoriteAt(entries: List<ModGridEntry>, index: Int, isFavorite: (String) -> Boolean): Boolean { |
| 71 | ui-function / modGroupBounds | fun modGroupBounds(entries: List<ModGridEntry>, index: Int): IntRange { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/api/ModCardTypeCollapseStore.kt

`file-51ccb32ea2815702d242`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 25 | ui-function / file | private fun file(): Path = ConfigManager.internal().folder.resolve(FILE_NAME) |
| 27 | ui-function / ensureLoaded | private fun ensureLoaded() { |
| 48 | ui-function / defaultCollapsed | private fun defaultCollapsed(typeId: String) = ModCardTypes.byId(typeId)?.collapsedByDefault ?: false |
| 50 | ui-function / isCollapsed | fun isCollapsed(typeId: String): Boolean { |
| 55 | ui-function / setCollapsed | fun setCollapsed(typeId: String, value: Boolean) { |
| 67 | ui-function / toggle | fun toggle(typeId: String) = setCollapsed(typeId, !isCollapsed(typeId)) |
| 69 | ui-function / persist | private fun persist() { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/api/ModFavorites.kt

`file-c402b52c3bbd8b3b0ec0`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 4 | ui-function / isFavorite | fun isFavorite(id: String): Boolean = id in this |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/api/ModHidden.kt

`file-0c7cd05abc1c5959c3e3`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 4 | ui-function / isHidden | fun isHidden(id: String): Boolean = id in this |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/api/ModIdStore.kt

`file-15f885d2627de240f616`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 25 | ui-function / file | private fun file(): Path = ConfigManager.internal().folder.resolve(fileName) |
| 27 | ui-function / ensureLoaded | private fun ensureLoaded() { |
| 43 | ui-function / contains | operator fun contains(id: String): Boolean { |
| 48 | ui-function / toggle | fun toggle(id: String) { |
| 59 | ui-function / persist | private fun persist() { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/api/ModOrder.kt

`file-094339b878702c29da46`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 28 | ui-function / file | private fun file(): Path = ConfigManager.internal().folder.resolve(FILE_NAME) |
| 31 | ui-function / ensureLoaded | private fun ensureLoaded() { |
| 48 | ui-function / current | fun current(): List<String> { |
| 54 | ui-function / replace | fun replace(ids: List<String>) { |
| 63 | ui-function / indexOf | fun indexOf(id: String): Int { |
| 76 | ui-function / reorder | fun reorder(visible: List<String>, all: List<String>) { |
| 89 | ui-function / persist | private fun persist() { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/api/ModSortStore.kt

`file-698d1edf0eb4325b869f`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 31 | ui-function / file | private fun file(): Path = ConfigManager.internal().folder.resolve(FILE_NAME) |
| 34 | ui-function / ensureLoaded | private fun ensureLoaded() { |
| 51 | ui-function / select | fun select(id: String) { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/api/ModToggleSupport.kt

`file-287caff23d27e53e8d30`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 23 | ui-function / shown | fun <T> Property<T>.shown(): T? { |
| 31 | ui-function / masterSwitchName | private fun masterSwitchName(id: String): String = |
| 34 | ui-function / isMasterSwitchName | fun isMasterSwitchName(name: String): Boolean = masterSwitchName(name) in MASTER_SWITCH_NAMES |
| 38 | ui-function / findMarkedSwitch | fun Tree.findMarkedSwitch(): Property<*>? { |
| 58 | ui-function / findMasterSwitch | fun Tree.findMasterSwitch(): Property<Boolean>? { |
| 70 | ui-function / isEnabled | override fun isEnabled(): Boolean = property.get() != false |
| 72 | ui-function / setEnabled | override fun setEnabled(enabled: Boolean) { |
| 87 | ui-function / file | private fun file(): Path = ConfigManager.internal().folder.resolve(FILE_NAME) |
| 91 | ui-function / install | fun install() { |
| 97 | ui-function / load | override fun load(): Set<String> { |
| 105 | ui-function / save | override fun save(disabled: Set<String>) { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/api/ProfileData.kt

`file-bcbbd67ce6a844743afb`；状态 **pending**；领域 profiles；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

该文件支撑 profiles；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/api/TextComponentRenderer.kt

`file-87427def5b932ac8d758`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 32 | composable / TextComponent | @Composable |
| 34 | ui-function / TextComponent | fun TextComponent( |
| 49 | composable / @Composable callback/type | val text = @Composable { |
| 87 | composable / text | @Composable |
| 88 | ui-function / text | fun text(text: Component) = buildAnnotatedString { |
| 113 | composable / Tooltip | @Composable |
| 115 | ui-function / Tooltip | fun Tooltip( |
| 116 | composable / @Composable callback/type | text: @Composable () -> Unit, |
| 121 | composable / @Composable callback/type | content: @Composable () -> Unit |
| 129 | composable / @Composable callback/type | content = content, delayMillis = 200, tooltip = @Composable { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/api/TranslationCredits.kt

`file-633e8db4a6f2d36fc4ec`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/api/TreeConfigData.kt

`file-4e0c7b098423158c7c3d`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 79 | ui-function / findModInfo | private fun findModInfo(): ModInfo? { |
| 93 | ui-function / nonBlankOrNull | private fun String.nonBlankOrNull(): String? = trim().takeIf { it.isNotEmpty() } |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/api/settings/BuiltinVisualizers.kt

`file-13635be50c631e955bbd`；状态 **pending**；领域 settings；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 27 | ui-function / register | fun register() { |
| 89 | keybind / KeybindOption | KeybindOption(KeybindOptionData(prop)) |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/api/settings/OptionData.kt

`file-83e03bf0185c579307ce`；状态 **pending**；领域 settings；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 63 | ui-function / read | fun read(): Pair<Float, Float>? { |
| 69 | ui-function / write | fun write(start: Float, end: Float) = prop.writeNumbers(listOf(start, end)) |
| 89 | ui-function / label | fun label(value: Float): String { |
| 96 | ui-function / nextValue | fun nextValue(current: List<Float>): Float { |
| 101 | ui-function / read | fun read(): List<Float> = prop.readNumbers() ?: emptyList() |
| 103 | ui-function / write | fun write(values: List<Float>) = prop.writeNumbers(values) |
| 106 | ui-function / boundedMin | private fun Property<*>.boundedMin(annotated: Float): Float = |
| 109 | ui-function / boundedMax | private fun Property<*>.boundedMax(annotated: Float): Float = |
| 112 | ui-function / valueFormat | private fun Property<*>.valueFormat(type: Class<*>): ((Float) -> String)? { |
| 117 | ui-function / readNumbers | private fun Property<*>.readNumbers(): List<Float>? = when (val value = get()) { |
| 129 | ui-function / writeNumbers | private fun Property<*>.writeNumbers(values: List<Float>) { |
| 186 | ui-function / set | fun set(value: Float) = (prop as Property<Any>).set(value.toNumberType(prop.type)) |
| 189 | ui-function / inherit | fun inherit() = (prop as Property<Any?>).set(inheritSentinel?.let { (it as? Number)?.toFloat()?.toNumberType(prop.type) }) |
| 216 | ui-function / strings | fun strings(): List<String> = elements().map { it?.toString() ?: "" } |
| 239 | ui-function / elements | fun elements(): List<Any?> = prop.listElements() |
| 241 | ui-function / setElements | fun setElements(values: List<Any?>) = prop.setListElements(values) |
| 253 | ui-function / strings | fun strings(): List<String> = elements().map { it?.toString() ?: "" } |
| 259 | ui-function / argb | fun argb(): List<Int> = elements().map { |
| 267 | ui-function / setArgb | fun setArgb(values: List<Int>) { |
| 286 | ui-function / floats | fun floats(): List<Float> = elements().map { (it as? Number)?.toFloat() ?: min } |
| 304 | ui-function / ranges | fun ranges(): List<Pair<Float, Float>> { |
| 311 | ui-function / setRanges | fun setRanges(ranges: List<Pair<Float, Float>>) { |
| 318 | ui-function / withinBounds | private fun Float.withinBounds(min: Float, max: Float): Float = coerceAtLeast(min).coerceAtMost(max) |
| 321 | ui-function / roundedTo | private fun Float.roundedTo(type: Class<*>): Number { |
| 331 | ui-function / numericElementType | private fun ListOptionData.numericElementType(min: Float, max: Float, step: Float): Class<*> { |
| 408 | ui-function / filterPatterns | private fun Property<*>.filterPatterns(directory: Boolean): Array<String> { |
| 414 | ui-function / toFilterPattern | private fun String.toFilterPattern(): String = when { |
| 421 | ui-function / listElements | private fun Property<*>.listElements(): List<Any?> { |
| 431 | ui-function / setListElements | private fun Property<*>.setListElements(values: List<Any?>) { |
| 446 | ui-function / setIdListElements | private fun Property<*>.setIdListElements(values: List<String>) { |
| 485 | ui-function / toComponentType | private fun Number.toComponentType(component: Class<*>): Any = when (component) { |
| 495 | ui-function / optionLabels | private fun Property<*>.optionLabels(): List<Any>? { |
| 513 | ui-function / optionValues | private fun Property<*>.optionValues(): List<Any>? = getMetadata<Any>("optionValues").optionList().takeIf { it.isNotEmpty() } |
| 515 | ui-function / optionLabelMap | private fun Property<*>.optionLabelMap(): Map<String, Any> { |
| 521 | ui-function / optionList | private fun Any?.optionList(): List<Any> { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/Borders.kt

`file-020c6d0fda191023239a`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 26 | ui-function / animatedFrame | fun Modifier.animatedFrame( |
| 57 | ui-function / topBorder | fun Modifier.topBorder( |
| 70 | ui-function / rightBorder | fun Modifier.rightBorder( |
| 83 | ui-function / bottomBorder | fun Modifier.bottomBorder( |
| 96 | ui-function / leftBorder | fun Modifier.leftBorder( |
| 117 | composable / fadingEdges | @Composable |
| 118 | ui-function / fadingEdges | fun Modifier.fadingEdges( |
| 123 | animation / animateFloatAsState | val topAlpha by animateFloatAsState(if (scrollState.canScrollBackward) 1f else 0f) |
| 124 | animation / animateFloatAsState | val bottomAlpha by animateFloatAsState(if (scrollState.canScrollForward) 1f else 0f) |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/Button.kt

`file-b266b2e609bdbb174059`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 20 | composable / IconButton | @Composable |
| 21 | ui-function / IconButton | fun IconButton( |
| 35 | animation / animateColorAsState | val iconColor = animateColorAsState( |
| 42 | animation / animateColorAsState | val borderColor = animateColorAsState( |
| 61 | composable / SelectableIconButton | @Composable |
| 62 | ui-function / SelectableIconButton | fun SelectableIconButton( |
| 74 | animation / animateColorAsState | val iconColor = animateColorAsState( |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/Chips.kt

`file-becd32bd6755a9775630`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 25 | composable / Chip | @Composable |
| 26 | ui-function / Chip | fun Chip( |
| 36 | animation / animateColorAsState | val backgroundColor = animateColorAsState( |
| 39 | animation / animateFloatAsState | val selectedAlpha = animateFloatAsState(if (selected) 1f else 0f) |
| 40 | animation / animateColorAsState | val textColor = animateColorAsState( |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/Common.kt

`file-a50c699c57b12f6531a6`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 30 | composable / rememberInteractionSource | @Composable |
| 31 | ui-function / rememberInteractionSource | fun rememberInteractionSource() = remember { MutableInteractionSource() } |
| 33 | ui-function / onClick | fun Modifier.onClick(interactionSource: MutableInteractionSource, onClick: () -> Unit) = |
| 36 | ui-function / onClick | fun Modifier.onClick(interactionSource: MutableInteractionSource, enabled: Boolean, onClick: () -> Unit) = |
| 43 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLICK) |
| 43 | sound / UiSoundEvent.CLICK | UiSounds.play(UiSoundEvent.CLICK) |
| 51 | ui-function / keepSearchFocus | fun Modifier.keepSearchFocus(): Modifier = this then KeepSearchFocusElement |
| 54 | ui-function / create | override fun create() = KeepSearchFocusNode() |
| 56 | ui-function / update | override fun update(node: KeepSearchFocusNode) {} |
| 58 | ui-function / equals | override fun equals(other: Any?) = other === this |
| 60 | ui-function / hashCode | override fun hashCode() = System.identityHashCode(this) |
| 69 | ui-function / onPointerEvent | override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) { |
| 74 | ui-function / onCancelPointerInput | override fun onCancelPointerInput() { |
| 78 | ui-function / applyFocusProperties | override fun applyFocusProperties(focusProperties: FocusProperties) { |
| 91 | ui-function / blockInteraction | fun Modifier.blockInteraction(blocked: Boolean = true, keyboard: Boolean = false): Modifier = |
| 93 | keybind / onPreviewKeyEvent | if (keyboard) focusProperties { canFocus = false }.onPreviewKeyEvent { it.key != Key.Tab && it.key != Key.Escape } |
| 93 | keybind / Key.Tab | if (keyboard) focusProperties { canFocus = false }.onPreviewKeyEvent { it.key != Key.Tab && it.key != Key.Escape } |
| 93 | keybind / Key.Escape | if (keyboard) focusProperties { canFocus = false }.onPreviewKeyEvent { it.key != Key.Tab && it.key != Key.Escape } |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/Header.kt

`file-33270ff5235cff625ed7`；状态 **pending**；领域 shell-navigation；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 核对打开/取消/确认、焦点、边界定位、外点关闭与提交后可见结果。 定位类型: dialog-or-popup 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 75 | composable / Header | @Composable |
| 76 | ui-function / Header | fun Header(modifier: Modifier = Modifier) { |
| 105 | animation / AnimatedContent | AnimatedContent( |
| 140 | animation / AnimatedVisibility | AnimatedVisibility( |
| 181 | ui-function / headerTitle | private fun NavBackStackEntry?.headerTitle(): HeaderTitle? { |
| 201 | composable / OpenOriginalScreenButton | @Composable |
| 202 | ui-function / OpenOriginalScreenButton | private fun OpenOriginalScreenButton(runnable: Runnable) { |
| 206 | animation / animateFloatAsState | val accentAlpha = animateFloatAsState(if (isHovered) 0.75f else 1f) |
| 239 | composable / TitleAuthorByline | @Composable |
| 240 | ui-function / TitleAuthorByline | private fun TitleAuthorByline(authors: String?) { |
| 250 | ui-function / authorByline | private fun String.authorByline(): String? { |
| 262 | composable / TitleInfoTooltip | @Composable |
| 263 | ui-function / TitleInfoTooltip | private fun TitleInfoTooltip(title: HeaderTitle) { |
| 281 | dialog-or-popup / Popup | Popup( |
| 308 | ui-function / searchFieldValue | private fun searchFieldValue(text: String) = TextFieldValue(text, TextRange(text.length)) |
| 310 | composable / GlobalSearchBar | @Composable |
| 311 | ui-function / GlobalSearchBar | fun GlobalSearchBar() { |
| 334 | animation / withFrameNanos | withFrameNanos { } |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/I18n.kt

`file-efe3190790d83d0fef93`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 7 | ui-function / translationKeyOrNull | internal fun translationKeyOrNull(value: Any?): String? { |
| 13 | ui-function / localizedText | fun localizedText(key: String?, fallback: Any?): Any { |
| 19 | ui-function / localizedString | fun localizedString(key: String?, fallback: Any?): String { |
| 34 | ui-function / localizedValue | fun localizedValue(value: Any?): Any? = |
| 38 | ui-function / localizedLabel | fun localizedLabel(text: String?): String? = |
| 41 | ui-function / localizedTitle | fun Node.localizedTitle(): Any = localizedText(getMetadata("titleKey"), title ?: id ?: "") |
| 43 | ui-function / localizedDescription | fun Node.localizedDescription(): Any? { |
| 49 | ui-function / localizedGroup | fun Node.localizedGroup(metadataKey: String, keyMetadataKey: String, default: String): String { |
| 53 | ui-function / translated | fun translated(key: String, fallback: String, vararg args: Any?): String = |
| 58 | ui-function / resolve | fun PageText.resolve(): String { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/Icon.kt

`file-22b72d5bf2f7674c4cb0`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 60 | ui-function / peek | fun peek(key: String, lastModified: Long): RasterIcon? = |
| 63 | ui-function / put | fun put(key: String, lastModified: Long, icon: RasterIcon) { |
| 73 | ui-function / bytes | fun bytes(path: String): ByteArray? = cache.getOrPut(path) { Entry(load(path)) }.bytes |
| 75 | ui-function / exists | fun exists(path: String): Boolean = bytes(path) != null |
| 77 | ui-function / fileBytes | fun fileBytes(file: File, stamp: Long): ByteArray? = |
| 82 | ui-function / warm | fun warm(names: Collection<String>) { |
| 98 | ui-function / load | private fun load(path: String): ByteArray? { |
| 110 | composable / rememberAsyncRasterIcon | @Composable |
| 111 | ui-function / rememberAsyncRasterIcon | private fun rememberAsyncRasterIcon(key: String, lastModified: Long, read: () -> ByteArray?): RasterIcon? { |
| 124 | ui-function / decodeRasterIcon | private fun decodeRasterIcon(bytes: ByteArray): RasterIcon? { |
| 136 | ui-function / applyAlpha | override fun applyAlpha(alpha: Float): Boolean { |
| 141 | ui-function / onDraw | override fun DrawScope.onDraw() { |
| 155 | composable / Icon | @Composable |
| 156 | ui-function / Icon | fun Icon( |
| 163 | composable / Icon | @Composable |
| 164 | ui-function / Icon | fun Icon( |
| 255 | ui-function / applyAlpha | override fun applyAlpha(alpha: Float): Boolean { |
| 260 | ui-function / onDraw | override fun DrawScope.onDraw() { |
| 265 | composable / tinted | @Composable |
| 266 | ui-function / tinted | private fun tinted(painter: Painter, tint: (() -> Color)?): Painter { |
| 271 | composable / rememberSvgResourcePainter | @Composable |
| 272 | ui-function / rememberSvgResourcePainter | fun rememberSvgResourcePainter(path: String): Painter? { |
| 286 | ui-function / brandFillPeak | private fun brandFillPeak(path: String): Float = brandFillPeakCache.getOrPut(path) { |
| 297 | composable / rememberBrandTint | @Composable |
| 298 | ui-function / rememberBrandTint | fun rememberBrandTint(path: String, tint: Color): ColorFilter? = remember(path, tint) { |
| 314 | composable / rememberAccentBrandTint | @Composable |
| 315 | ui-function / rememberAccentBrandTint | fun rememberAccentBrandTint(path: String): ColorFilter? = rememberBrandTint(path, if (AccentGradient) Color.White else Accent) |
| 317 | ui-function / accentGradientFill | fun Modifier.accentGradientFill(): Modifier = |
| 325 | composable / rememberIconRasterPainter | @Composable |
| 326 | ui-function / rememberIconRasterPainter | private fun rememberIconRasterPainter(path: String): Painter? { |
| 331 | ui-function / warmIconCache | fun warmIconCache(names: Collection<String>) = IconResource.warm(names) |
| 333 | ui-function / canRenderIcon | fun canRenderIcon(iconName: String): Boolean { |
| 341 | ui-function / toIconResourcePath | private fun String.toIconResourcePath(): String = |
| 344 | ui-function / iconSizeModifier | private fun iconSizeModifier(aspectRatio: Float?): Modifier { |
| 350 | ui-function / readSvgAspectRatio | private fun readSvgAspectRatio(bytes: ByteArray): Float? { |
| 361 | composable / IconWithIndicator | @Composable |
| 362 | ui-function / IconWithIndicator | fun IconWithIndicator( |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/ModToggleState.kt

`file-efb2e8edcf02c8b3ac66`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 17 | composable / rememberModEnabled | @Composable |
| 18 | ui-function / rememberModEnabled | internal fun rememberModEnabled(toggle: ModToggle?): MutableState<Boolean> { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/NotificationsCenter.kt

`file-e50341cb7a3e49eedbd0`；状态 **pending**；领域 notifications；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 从实际宿主入口触发，核对完整状态变化、交互、退出及重进后的结果。 定位类型: peripheral-ui 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 39 | composable / @Composable callback/type | private val PanelShape @Composable get() = LocalTheme.current.popupShape |
| 40 | composable / @Composable callback/type | private val ButtonShape @Composable get() = LocalTheme.current.buttonShape |
| 45 | composable / NotificationsCenter | @Composable |
| 46 | peripheral-ui / NotificationsCenter | fun NotificationsCenter() { |
| 46 | ui-function / NotificationsCenter | fun NotificationsCenter() { |
| 48 | peripheral-ui / NotificationsManager.history | val history = NotificationsManager.history |
| 105 | composable / NotificationRow | @Composable |
| 106 | ui-function / NotificationRow | private fun NotificationRow(notification: Notification) { |
| 182 | composable / ActionButton | @Composable |
| 183 | ui-function / ActionButton | private fun ActionButton(notification: Notification, action: NotificationAction) { |
| 188 | animation / animateColorAsState | val background by animateColorAsState(if (isHovered) Color.White.copy(0.12f).compositeOver(base) else base) |
| 196 | peripheral-ui / NotificationsManager.remove | NotificationsManager.remove(notification) |
| 211 | composable / Footer | @Composable |
| 212 | ui-function / Footer | private fun Footer() { |
| 216 | animation / animateColorAsState | val color by animateColorAsState(if (isHovered) theme.textColor else theme.textColor.copy(0.85f)) |
| 226 | peripheral-ui / NotificationsManager.clearHistory | .onClick(source) { NotificationsManager.clearHistory() } |
| 237 | composable / accentFor | @Composable |
| 238 | ui-function / accentFor | private fun accentFor(type: NotificationType): Color = when (type) { |
| 245 | ui-function / relativeTime | private fun relativeTime(createdAt: Long, now: Long = System.currentTimeMillis()): String { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/OversampledSvg.kt

`file-2d27e6b647cce19eba57`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 59 | ui-function / svgAttr | private fun svgAttr(el: String, name: String): Float? = |
| 70 | ui-function / measure | private fun measure(): Size { |
| 91 | ui-function / applyAlpha | override fun applyAlpha(alpha: Float): Boolean { |
| 96 | ui-function / applyColorFilter | override fun applyColorFilter(colorFilter: ColorFilter?): Boolean { |
| 101 | ui-function / onDraw | override fun DrawScope.onDraw() { |
| 127 | ui-function / rasterize | private fun rasterize(px: Int, mask: Boolean): ImageBitmap? = |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/PixelArt.kt

`file-72532b264aac8219c709`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 5 | ui-function / isPixelArt | fun BufferedImage.isPixelArt(): Boolean { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/PlayerHead.kt

`file-86f16a137e2fa8c000ce`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 17 | composable / PlayerHead | @Composable |
| 18 | ui-function / PlayerHead | fun PlayerHead(modifier: Modifier = Modifier) { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/ReorderableGrid.kt

`file-66468633bf58e95e62fa`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 63 | animation / spring | private val SettleSpec = spring<Offset>( |
| 114 | animation / Animatable | private val settleOffset = Animatable(Offset.Zero, Offset.VectorConverter) |
| 139 | ui-function / trackSlot | private fun trackSlot(): Offset { |
| 153 | ui-function / infoAt | private fun infoAt(index: Int): LazyGridItemInfo? = |
| 156 | ui-function / drawnBounds | private fun drawnBounds(info: LazyGridItemInfo): Rect { |
| 162 | ui-function / isOverSettlingItem | internal fun isOverSettlingItem(position: Offset): Boolean = |
| 165 | ui-function / draggableKeyAt | internal fun draggableKeyAt(position: Offset): Any? { |
| 174 | ui-function / onDragStart | internal fun onDragStart(key: Any): Boolean { |
| 190 | ui-function / onDrag | internal fun onDrag(delta: Offset) { |
| 194 | ui-function / onDragEnd | internal fun onDragEnd() { |
| 205 | animation / animateTo | settleOffset.animateTo(Offset.Zero, SettleSpec) |
| 212 | ui-function / cancel | internal fun cancel() { |
| 218 | ui-function / stop | private fun stop() { |
| 235 | ui-function / settleOnHoveredItem | private fun settleOnHoveredItem() { |
| 252 | ui-function / distance | fun distance(item: LazyGridItemInfo) = (item.bounds().center - dragged.center).getDistance() |
| 264 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.SLIDER_TICK) |
| 264 | sound / UiSoundEvent.SLIDER_TICK | UiSounds.play(UiSoundEvent.SLIDER_TICK) |
| 271 | ui-function / dragLoop | private suspend fun dragLoop() { |
| 272 | animation / withFrameNanos | var lastFrame = withFrameNanos { it } |
| 274 | animation / withFrameNanos | val frame = withFrameNanos { it } |
| 305 | ui-function / slot | private fun LazyGridItemInfo.slot() = offset.toOffset() + Offset(with(density()) { horizontalPadding().toPx() }, 0f) |
| 307 | ui-function / bounds | private fun LazyGridItemInfo.bounds() = Rect(slot(), size.toSize()) |
| 310 | composable / rememberGridReorderState | @Composable |
| 311 | ui-function / rememberGridReorderState | fun rememberGridReorderState( |
| 348 | ui-function / reorderContainer | fun Modifier.reorderContainer(state: GridReorderState): Modifier = pointerInput(state) { |
| 390 | ui-function / awaitPressedChange | private suspend fun AwaitPointerEventScope.awaitPressedChange(id: PointerId, consume: Boolean): PointerInputChange? { |
| 398 | ui-function / reorderableItem | fun Modifier.reorderableItem(state: GridReorderState, key: Any): Modifier = this |
| 407 | composable / reorderOverlay | @Composable |
| 408 | ui-function / reorderOverlay | fun Modifier.reorderOverlay(state: GridReorderState): Modifier { |
| 411 | animation / animateFloatAsState | val scale by animateFloatAsState(if (state.draggingKey != null) 1.04f else 1f) |
| 422 | ui-function / overlap | private fun Rect.overlap(other: Rect): Float { |
| 428 | ui-function / toOffset | private fun IntOffset.toOffset() = Offset(x.toFloat(), y.toFloat()) |
| 430 | ui-function / toSize | private fun IntSize.toSize() = Size(width.toFloat(), height.toFloat()) |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/RetainedVisibility.kt

`file-aa399952b377200d4687`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 核对打开/取消/确认、焦点、边界定位、外点关闭与提交后可见结果。 定位类型: dialog-or-popup 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 44 | composable / shellTransition | @Composable |
| 45 | ui-function / shellTransition | fun Modifier.shellTransition(): Modifier { |
| 57 | ui-function / toTransformOrigin | private fun Offset.toTransformOrigin(size: Size): TransformOrigin? { |
| 66 | ui-function / calculatePosition | override fun calculatePosition( |
| 82 | ui-function / calculatePosition | override fun calculatePosition( |
| 96 | composable / ShellPopup | @Composable |
| 97 | dialog-or-popup / ShellPopup | fun ShellPopup( |
| 97 | ui-function / ShellPopup | fun ShellPopup( |
| 101 | composable / @Composable callback/type | content: @Composable () -> Unit, |
| 105 | dialog-or-popup / Popup | Popup( |
| 114 | composable / ShellPopup | @Composable |
| 115 | dialog-or-popup / ShellPopup | fun ShellPopup( |
| 115 | ui-function / ShellPopup | fun ShellPopup( |
| 120 | composable / @Composable callback/type | content: @Composable () -> Unit, |
| 123 | dialog-or-popup / ShellPopup | ShellPopup(provider, onDismissRequest, properties, content) |
| 126 | composable / RetainedVisibility | @Composable |
| 127 | animation / RetainedVisibility | fun RetainedVisibility( |
| 127 | ui-function / RetainedVisibility | fun RetainedVisibility( |
| 135 | composable / @Composable callback/type | content: @Composable (alpha: Float) -> Unit, |
| 137 | animation / Animatable | val progress = remember(openKey) { Animatable(0f) } |
| 139 | animation / animateTo | progress.animateTo(if (visible) 1f else 0f, if (visible) enter else exit) |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/ScrollbarBox.kt

`file-20b7842063af50d0b065`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 24 | composable / ScrollbarBox | @Composable |
| 25 | ui-function / ScrollbarBox | internal fun ScrollbarBox( |
| 29 | composable / @Composable callback/type | content: @Composable BoxScope.() -> Unit, |
| 32 | composable / ScrollbarBox | @Composable |
| 33 | ui-function / ScrollbarBox | internal fun ScrollbarBox( |
| 37 | composable / @Composable callback/type | content: @Composable BoxScope.() -> Unit, |
| 40 | composable / ScrollbarBox | @Composable |
| 41 | ui-function / ScrollbarBox | internal fun ScrollbarBox( |
| 45 | composable / @Composable callback/type | content: @Composable BoxScope.() -> Unit, |
| 48 | composable / ScrollbarBox | @Composable |
| 49 | ui-function / ScrollbarBox | internal fun ScrollbarBox( |
| 54 | composable / @Composable callback/type | content: @Composable BoxScope.() -> Unit, |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/Sidebar.kt

`file-bf531fd1ebd7cd8b3b3e`；状态 **pending**；领域 shell-navigation；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 核对打开/取消/确认、焦点、边界定位、外点关闭与提交后可见结果。 定位类型: dialog-or-popup 从实际宿主入口触发，核对完整状态变化、交互、退出及重进后的结果。 定位类型: peripheral-ui 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 49 | composable / Sidebar | @Composable |
| 50 | ui-function / Sidebar | fun Sidebar(background: Boolean = true) { |
| 74 | composable / Navigation | @Composable |
| 75 | ui-function / Navigation | private fun Navigation(showTopOptions: Boolean) { |
| 87 | composable / Branding | @Composable |
| 88 | ui-function / Branding | private fun Branding() { |
| 110 | composable / TopOptionsSection | @Composable |
| 111 | ui-function / TopOptionsSection | private fun TopOptionsSection() { |
| 122 | composable / NavigationEntries | @Composable |
| 123 | ui-function / NavigationEntries | private fun NavigationEntries(showTopOptions: Boolean) { |
| 157 | composable / NavigationSection | @Composable |
| 158 | composable / @Composable callback/type | private fun NavigationSection(title: String? = null, content: @Composable () -> Unit) { |
| 158 | ui-function / NavigationSection | private fun NavigationSection(title: String? = null, content: @Composable () -> Unit) { |
| 175 | composable / NavigationEntry | @Composable |
| 176 | ui-function / NavigationEntry | private fun NavigationEntry(icon: String, title: String, route: Any? = null, isSelected: Boolean = false, onSelect: (Any?) -> Unit) { |
| 181 | animation / animateFloatAsState | val selectedAlpha = animateFloatAsState(if (isSelected) 1f else 0f) |
| 182 | animation / animateColorAsState | val textAndIconColor = animateColorAsState( |
| 187 | animation / animateColorAsState | val borderColor = animateColorAsState( |
| 210 | composable / NotificationBell | @Composable |
| 211 | ui-function / NotificationBell | private fun NotificationBell() { |
| 221 | peripheral-ui / NotificationsManager.markAllRead | if (!expanded) NotificationsManager.markAllRead() |
| 229 | peripheral-ui / NotificationsManager.unreadCount | showIndicator = NotificationsManager.unreadCount > 0, |
| 233 | dialog-or-popup / ShellPopup | ShellPopup( |
| 239 | peripheral-ui / NotificationsManager.markAllRead | NotificationsManager.markAllRead() |
| 243 | peripheral-ui / NotificationsCenter | Box(Modifier.shellTransition()) { NotificationsCenter() } |
| 249 | composable / Account | @Composable |
| 250 | ui-function / Account | private fun Account() { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/Text.kt

`file-8f8abd713e8e471bca88`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 21 | ui-function / asRenderText | fun Any.asRenderText(): String { |
| 32 | ui-function / isEmptyText | fun Any.isEmptyText() = this.asRenderText().isEmpty() |
| 45 | ui-function / parseLegacyFormatting | internal fun parseLegacyFormatting(text: String, baseColor: Color): AnnotatedString = buildAnnotatedString { |
| 52 | ui-function / currentStyle | fun currentStyle() = SpanStyle( |
| 65 | ui-function / flush | fun flush() { |
| 107 | composable / Text | @Composable |
| 108 | ui-function / Text | fun Text( |
| 121 | composable / Text | @Composable |
| 122 | ui-function / Text | fun Text( |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/VerticalScrollContainment.kt

`file-f95fb969996ab40a5fbc`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 18 | composable / containVerticalScroll | @Composable |
| 19 | ui-function / containVerticalScroll | internal fun Modifier.containVerticalScroll(scrollState: ScrollState): Modifier { |
| 27 | composable / containVerticalScroll | @Composable |
| 28 | ui-function / containVerticalScroll | internal fun Modifier.containVerticalScroll(isScrollable: () -> Boolean): Modifier { |
| 52 | ui-function / onPostScroll | override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset { |
| 56 | ui-function / onPostFling | override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/WheelScroll.kt

`file-eeee5dd630d24c593b46`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 32 | ui-function / route | fun route(delta: Offset, dispatch: () -> Unit) { |
| 45 | ui-function / claim | internal fun claim(node: WheelScrollNode) { |
| 50 | ui-function / block | internal fun block() { |
| 64 | ui-function / wheelScroll | internal fun Modifier.wheelScroll( |
| 75 | ui-function / create | override fun create() = WheelScrollNode(state, orientation, contain) |
| 77 | ui-function / update | override fun update(node: WheelScrollNode) { |
| 87 | animation / spring | private val spring = WheelSpring() |
| 91 | ui-function / update | fun update(state: ScrollableState, orientation: Orientation, contain: () -> Boolean) { |
| 94 | animation / spring.reset | spring.reset() |
| 101 | ui-function / onPointerEvent | override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) { |
| 118 | ui-function / onCancelPointerInput | override fun onCancelPointerInput() {} |
| 120 | ui-function / push | fun push(delta: Offset) { |
| 121 | animation / spring.push | spring.push(amountOf(delta)) |
| 126 | ui-function / glide | private suspend fun glide() { |
| 130 | animation / spring.active | while (spring.active) { |
| 131 | animation / withFrameNanos | withFrameNanos { now -> |
| 134 | animation / spring.advance | val step = spring.advance(nanos / 1_000_000_000f) * currentValueOf(LocalDensity).density |
| 136 | animation / spring.reset | if (step != 0f && abs(scrollBy(step) - step) > 0.5f) spring.reset() |
| 142 | animation / spring.reset | spring.reset() |
| 146 | ui-function / onDetach | override fun onDetach() { |
| 147 | animation / spring.reset | spring.reset() |
| 150 | ui-function / amountOf | private fun amountOf(delta: Offset) = if (orientation == Orientation.Vertical) delta.y else delta.x |
| 162 | ui-function / push | fun push(delta: Float) { |
| 175 | ui-function / advance | fun advance(seconds: Float): Float { |
| 196 | ui-function / reset | fun reset() { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/dropdown/DropdownMenu.kt

`file-a2f590ea50a986ec3883`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 核对打开/取消/确认、焦点、边界定位、外点关闭与提交后可见结果。 定位类型: dialog-or-popup 核对菜单位置、层级、hover、键鼠选择、点击外部/Esc 关闭和每个命令的结果。 定位类型: menu 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 30 | ui-function / calculatePosition | override fun calculatePosition( |
| 73 | composable / SimpleDropdownMenu | @Composable |
| 74 | menu / SimpleDropdownMenu | fun SimpleDropdownMenu( |
| 74 | ui-function / SimpleDropdownMenu | fun SimpleDropdownMenu( |
| 81 | composable / @Composable callback/type | content: @Composable BoxScope.() -> Unit |
| 94 | dialog-or-popup / ShellPopup | ShellPopup( |
| 112 | ui-function / calculatePosition | override fun calculatePosition( |
| 123 | composable / SimpleDropdownMenuContent | @Composable |
| 124 | ui-function / SimpleDropdownMenuContent | fun SimpleDropdownMenuContent( |
| 127 | composable / @Composable callback/type | content: @Composable BoxScope.() -> Unit |
| 129 | animation / rememberTransition | val transition = rememberTransition(expandedStates, "SimpleDropDownMenu") |
| 130 | animation / animateFloat | val alpha by transition.animateFloat(transitionSpec = { tween() }) { expanded -> |
| 130 | animation / tween | val alpha by transition.animateFloat(transitionSpec = { tween() }) { expanded -> |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/layout/Flex.kt

`file-a99699516bcfbcfb16a2`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 10 | composable / FlexibleLayout | @Composable |
| 11 | ui-function / FlexibleLayout | fun FlexibleLayout( |
| 15 | composable / @Composable callback/type | content: @Composable () -> Unit |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/ButtonOption.kt

`file-ddd648aeedb3bcdc3f7b`；状态 **pending**；领域 settings；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 35 | composable / ButtonOption | @Composable |
| 36 | ui-function / ButtonOption | fun ButtonOption(data: ButtonOptionData) { |
| 42 | animation / animateColorAsState | val bgColor = animateColorAsState( |
| 49 | animation / animateFloatAsState | val accentAlpha = animateFloatAsState(if (confirming) 0f else if (isHovered) 0.75f else 1f) |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/CheckboxControl.kt

`file-d2cec53a2f689412c443`；状态 **pending**；领域 settings；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 31 | composable / CheckboxIndicator | @Composable |
| 32 | ui-function / CheckboxIndicator | fun CheckboxIndicator( |
| 41 | animation / animateFloatAsState | val accentAlpha = animateFloatAsState(if (checked) 1f else 0f) |
| 42 | animation / animateColorAsState | val borderColor = animateColorAsState( |
| 60 | animation / AnimatedVisibility | AnimatedVisibility(checked, enter = fadeIn(), exit = fadeOut()) { |
| 66 | composable / CheckboxControl | @Composable |
| 67 | ui-function / CheckboxControl | fun CheckboxControl(checked: Boolean, onCheckedChange: (Boolean) -> Unit) { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/ColorListOption.kt

`file-72db6a482b0c6ff26364`；状态 **pending**；领域 color-gradient；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 核对打开/取消/确认、焦点、边界定位、外点关闭与提交后可见结果。 定位类型: dialog-or-popup 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 40 | composable / ColorListOption | @Composable |
| 41 | ui-function / ColorListOption | fun ColorListOption(data: ColorListOptionData) { |
| 63 | composable / ColorSwatch | @Composable |
| 64 | ui-function / ColorSwatch | private fun RowScope.ColorSwatch( |
| 78 | animation / animateColorAsState | val textColor = animateColorAsState(if (color.luminance() > 0.6f) Color.Black else Color.White) |
| 99 | dialog-or-popup / ShellPopup | ShellPopup( |
| 105 | dialog-or-popup / ColorPickerPopup | ColorPickerPopup( |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/ColorOption.kt

`file-c53270cb774d281454f5`；状态 **pending**；领域 color-gradient；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 核对打开/取消/确认、焦点、边界定位、外点关闭与提交后可见结果。 定位类型: dialog-or-popup 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 72 | composable / @Composable callback/type | private val PickerShape @Composable get() = LocalTheme.current.popupShape |
| 74 | ui-function / colorToHsb | private fun colorToHsb(color: Color): FloatArray { |
| 92 | ui-function / hsbToColor | private fun hsbToColor(hue: Float, saturation: Float, brightness: Float, alpha: Float = 1f): Color { |
| 108 | ui-function / colorToHex | internal fun colorToHex(color: Color): String { |
| 118 | ui-function / hexToColor | private fun hexToColor(hex: String): Color? { |
| 154 | ui-function / currentColor | fun currentColor(): Color = hsbToColor(hue, saturation, brightness, alpha) |
| 156 | ui-function / applyFrom | fun applyFrom(color: Color) { |
| 165 | composable / ChromaColorAnimation | @Composable |
| 166 | ui-function / ChromaColorAnimation | internal fun ChromaColorAnimation( |
| 186 | composable / ColorOption | @Composable |
| 187 | ui-function / ColorOption | fun ColorOption(data: ColorOptionData) { |
| 222 | animation / animateColorAsState | val textColor = animateColorAsState( |
| 226 | animation / animateColorAsState | val borderColor = animateColorAsState( |
| 230 | ui-function / persistColor | fun persistColor(color: Color) { |
| 284 | dialog-or-popup / ShellPopup | ShellPopup( |
| 290 | dialog-or-popup / ColorPickerPopup | ColorPickerPopup( |
| 302 | composable / ColorPickerPopup | @Composable |
| 303 | dialog-or-popup / ColorPickerPopup | internal fun ColorPickerPopup( |
| 303 | ui-function / ColorPickerPopup | internal fun ColorPickerPopup( |
| 371 | ui-function / update | fun update(x: Float, y: Float) { |
| 424 | ui-function / update | fun update(x: Float) { |
| 511 | ui-function / update | fun update(x: Float) { |
| 696 | ui-function / update | fun update(x: Float) { |
| 787 | composable / SliderValueField | @Composable |
| 788 | ui-function / SliderValueField | private fun SliderValueField( |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/DraggableListOption.kt

`file-33b31e5db72ca1e1407b`；状态 **pending**；领域 settings；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 58 | composable / @Composable callback/type | private val ListContainerShape @Composable get() = LocalTheme.current.sideBarNavigationEntryShape |
| 59 | composable / @Composable callback/type | private val ListItemShape @Composable get() = LocalTheme.current.sideBarNavigationEntryShape |
| 65 | composable / DraggableListOption | @Composable |
| 66 | ui-function / DraggableListOption | fun DraggableListOption(data: DraggableListOptionData) { |
| 107 | ui-function / save | fun save(list: List<ListEntry> = items) { |
| 123 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.SLIDER_TICK) |
| 123 | sound / UiSoundEvent.SLIDER_TICK | UiSounds.play(UiSoundEvent.SLIDER_TICK) |
| 162 | animation / animateFloatAsState | val animatedOffset = animateFloatAsState(targetOffset) |
| 164 | animation / animateColorAsState | val bgColor = animateColorAsState( |
| 167 | animation / animateColorAsState | val contentColor = animateColorAsState( |
| 244 | ui-function / propValueList | private fun propValueList(prop: Property<*>): List<String> = when (val v = prop.shown()) { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/DropdownOption.kt

`file-e90c8a06a6f4f697fb99`；状态 **pending**；领域 settings；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 核对菜单位置、层级、hover、键鼠选择、点击外部/Esc 关闭和每个命令的结果。 定位类型: menu 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 31 | composable / @Composable callback/type | private val DropdownShape @Composable get() = LocalTheme.current.sideBarNavigationEntryShape |
| 32 | composable / @Composable callback/type | private val MenuItemShape @Composable get() = DropdownShape.concentric(MenuPadding) |
| 34 | composable / DropdownOption | @Composable |
| 35 | ui-function / DropdownOption | fun DropdownOption(data: DropdownOptionData) { |
| 71 | animation / animateColorAsState | val borderColor = animateColorAsState( |
| 75 | animation / animateColorAsState | val textColor = animateColorAsState( |
| 79 | animation / animateColorAsState | val backgroundColor = animateColorAsState( |
| 83 | animation / animateFloatAsState | val chevronRotation = animateFloatAsState( |
| 113 | menu / SimpleDropdownMenu | SimpleDropdownMenu( |
| 142 | animation / animateColorAsState | val backgroundColor = animateColorAsState( |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/FileListOption.kt

`file-8015bde8b77b96ede988`；状态 **pending**；领域 settings；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 核对打开/取消/确认、焦点、边界定位、外点关闭与提交后可见结果。 定位类型: dialog-or-popup 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 30 | composable / FileListOption | @Composable |
| 31 | ui-function / FileListOption | fun FileListOption(data: FileListOptionData) { |
| 41 | dialog-or-popup / openDialog | fun openDialog(id: Int, current: String) { |
| 41 | ui-function / openDialog | fun openDialog(id: Int, current: String) { |
| 80 | dialog-or-popup / openDialog | onClick = { openDialog(entry.id, entry.value) }, |
| 85 | composable / PathField | @Composable |
| 86 | ui-function / PathField | private fun RowScope.PathField( |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/FileOption.kt

`file-77268b54d4d8180a19ba`；状态 **pending**；领域 settings；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 核对打开/取消/确认、焦点、边界定位、外点关闭与提交后可见结果。 定位类型: dialog-or-popup 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 48 | composable / FileOption | @Composable |
| 49 | ui-function / FileOption | fun FileOption(data: FileOptionData) { |
| 57 | animation / animateColorAsState | val borderColor = animateColorAsState(if (isHovered \|\| isFocused) theme.textColorSecondary else theme.borderColor) |
| 67 | ui-function / setValue | fun setValue(newText: String) { |
| 75 | dialog-or-popup / openDialog | fun openDialog() { |
| 75 | ui-function / openDialog | fun openDialog() { |
| 144 | composable / rememberPathTransformation | @Composable |
| 145 | ui-function / rememberPathTransformation | internal fun rememberPathTransformation(): VisualTransformation { |
| 163 | composable / EditableValue | @Composable |
| 164 | ui-function / EditableValue | private fun EditableValue( |
| 168 | composable / @Composable callback/type | inner: @Composable () -> Unit, |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/InfoOption.kt

`file-1bc2520621f1622bc104`；状态 **pending**；领域 settings；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 25 | composable / InfoOption | @Composable |
| 26 | ui-function / InfoOption | fun InfoOption(data: InfoOptionData) { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/InheritableSliderOption.kt

`file-7a37af4fad542184158b`；状态 **pending**；领域 settings；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 53 | composable / InheritableSliderOption | @Composable |
| 54 | ui-function / InheritableSliderOption | fun InheritableSliderOption(data: InheritableSliderOptionData) { |
| 65 | animation / animateFloatAsState | val fraction = animateFloatAsState(((value - data.min) / span).coerceIn(0f, 1f), animationSpec = spring()) |
| 65 | animation / spring | val fraction = animateFloatAsState(((value - data.min) / span).coerceIn(0f, 1f), animationSpec = spring()) |
| 67 | animation / animateColorAsState | val filledColor = animateColorAsState(if (inherited) theme.textColorSecondary.copy(alpha = 0.4f) else Accent) |
| 68 | animation / animateColorAsState | val thumbColor = animateColorAsState( |
| 87 | ui-function / maybeTick | fun maybeTick(newValue: Float) { |
| 93 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.SLIDER_TICK) |
| 93 | sound / UiSoundEvent.SLIDER_TICK | UiSounds.play(UiSoundEvent.SLIDER_TICK) |
| 96 | ui-function / snap | fun snap(raw: Float): Float { |
| 102 | ui-function / xToValue | fun xToValue(x: Float): Float { |
| 106 | ui-function / apply | fun apply(newValue: Float) { |
| 173 | composable / InheritChip | @Composable |
| 174 | ui-function / InheritChip | private fun InheritChip(label: String, active: Boolean, onClick: () -> Unit) { |
| 178 | animation / animateColorAsState | val background = animateColorAsState( |
| 181 | animation / animateColorAsState | val border = animateColorAsState(if (active) Accent else theme.borderColor) |
| 182 | animation / animateColorAsState | val textColor = animateColorAsState(if (active) Accent else theme.textColorSecondary) |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/KeybindConflicts.kt

`file-36fb563400419918663e`；状态 **pending**；领域 keybind；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 18 | keybind / OneConfigKeybind | private fun boundKeybinds(): List<Pair<Property<*>, OneConfigKeybind>> { |
| 18 | ui-function / boundKeybinds | private fun boundKeybinds(): List<Pair<Property<*>, OneConfigKeybind>> { |
| 21 | keybind / OneConfigKeybind | (entry.prop.get() as? OneConfigKeybind) |
| 28 | ui-function / conflictMap | fun conflictMap(): Map<Property<*>, List<Property<*>>> { |
| 38 | ui-function / computeConflictMap | private fun computeConflictMap(): Map<Property<*>, List<Property<*>>> { |
| 54 | ui-function / isContextGated | private fun isContextGated(prop: Property<*>): Boolean { |
| 65 | ui-function / conflictingProps | fun conflictingProps(): Set<Property<*>> = conflictMap().keys |
| 67 | ui-function / displayName | fun displayName(prop: Property<*>): String = |
| 70 | keybind / OneConfigKeybind.hasConflictBinding | private fun OneConfigKeybind.hasConflictBinding(): Boolean { |
| 70 | ui-function / hasConflictBinding | private fun OneConfigKeybind.hasConflictBinding(): Boolean { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/KeybindOption.kt

`file-72346c16d19e8c59926b`；状态 **pending**；领域 keybind；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 51 | composable / @Composable callback/type | private val KeybindShape @Composable get() = LocalTheme.current.sideBarNavigationEntryShape |
| 56 | keybind / OneConfigKeybind.keyName | private fun keyCodeToName(code: Int): String = OneConfigKeybind.keyName(code) |
| 56 | ui-function / keyCodeToName | private fun keyCodeToName(code: Int): String = OneConfigKeybind.keyName(code) |
| 59 | ui-function / mouseButtonToName | private fun mouseButtonToName(button: Int): String = Platform.compatibility().keys().mouseName(button) |
| 61 | ui-function / splitModifiers | private fun splitModifiers(codes: List<Int>): Pair<Byte, IntArray> { |
| 72 | keybind / OneConfigKeybind | private fun keybindDisplayName(keybind: OneConfigKeybind?): String = keybind?.displayName() ?: "None" |
| 72 | ui-function / keybindDisplayName | private fun keybindDisplayName(keybind: OneConfigKeybind?): String = keybind?.displayName() ?: "None" |
| 75 | keybind / OneConfigKeybind | private fun writeKeybind(prop: Property<*>, keys: IntArray?, mouse: IntArray?, mods: Byte): OneConfigKeybind { |
| 75 | ui-function / writeKeybind | private fun writeKeybind(prop: Property<*>, keys: IntArray?, mouse: IntArray?, mods: Byte): OneConfigKeybind { |
| 76 | keybind / OneConfigKeybind | val old = prop.get() as? OneConfigKeybind |
| 78 | keybind / OneConfigKeybind | val newKeybind = old?.copyWith(keys, mouse, mods) ?: OneConfigKeybind(keys, mouse, mods, 0L) { true } |
| 80 | keybind / OneConfigKeybind | val applied = prop.get() as? OneConfigKeybind ?: newKeybind |
| 84 | keybind / KeybindManager.replace | KeybindManager.replace(old, applied) |
| 89 | ui-function / resetKeybindOption | fun resetKeybindOption(prop: Property<*>) { |
| 90 | keybind / OneConfigKeybind | val def = prop.getMetadata<Any?>("default") as? OneConfigKeybind |
| 99 | ui-function / unbindKeybindOption | fun unbindKeybindOption(prop: Property<*>) { |
| 104 | ui-function / awtKeyEventId | private fun KeyEvent.awtKeyEventId(): Int? = runCatching { |
| 110 | composable / KeybindOption | @Composable |
| 111 | keybind / KeybindOption | fun KeybindOption(data: KeybindOptionData) { |
| 111 | ui-function / KeybindOption | fun KeybindOption(data: KeybindOptionData) { |
| 120 | keybind / OneConfigKeybind | mutableStateOf(data.prop.get() as? OneConfigKeybind) |
| 133 | ui-function / applyKeybind | fun applyKeybind(keys: IntArray?, mouse: IntArray?, mods: Byte = KeyModifiers.NONE) { |
| 139 | ui-function / commitRecording | fun commitRecording() { |
| 151 | animation / animateColorAsState | val bgColor = animateColorAsState( |
| 158 | animation / animateColorAsState | val borderColor = animateColorAsState( |
| 166 | animation / animateColorAsState | val textColor = animateColorAsState( |
| 206 | keybind / KeybindRecordingBus.setEscapeHandler | KeybindRecordingBus.setEscapeHandler(handler) |
| 207 | keybind / KeybindRecordingBus.setMouseHandler | KeybindRecordingBus.setMouseHandler(mouseHandler) |
| 209 | keybind / KeybindRecordingBus.clearEscapeHandler | KeybindRecordingBus.clearEscapeHandler(handler) |
| 210 | keybind / KeybindRecordingBus.clearMouseHandler | KeybindRecordingBus.clearMouseHandler(mouseHandler) |
| 221 | keybind / onKeyEvent | .onKeyEvent { event -> |
| 222 | keybind / onKeyEvent | if (!recording) return@onKeyEvent false |
| 223 | keybind / onKeyEvent | if (event.awtKeyEventId() == java.awt.event.KeyEvent.KEY_TYPED) return@onKeyEvent true |
| 226 | keybind / Key.Escape | if (event.key == Key.Escape) { |
| 229 | keybind / onKeyEvent | return@onKeyEvent true |
| 231 | keybind / Key.Backspace | if (event.key == Key.Backspace \|\| event.key == Key.Delete) { |
| 231 | keybind / Key.Delete | if (event.key == Key.Backspace \|\| event.key == Key.Delete) { |
| 235 | keybind / onKeyEvent | return@onKeyEvent true |
| 242 | keybind / onKeyEvent | if (keyCode <= 0) return@onKeyEvent true |
| 246 | keybind / onKeyEvent | return@onKeyEvent true |
| 250 | keybind / onKeyEvent | return@onKeyEvent true |
| 257 | keybind / onKeyEvent | return@onKeyEvent true |
| 259 | keybind / onKeyEvent | else -> return@onKeyEvent false |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/ListOptionScaffold.kt

`file-ca8312a75ca7f499faf6`；状态 **pending**；领域 settings；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 76 | composable / @Composable callback/type | private val ContainerShape @Composable get() = LocalTheme.current.modCardShape |
| 77 | composable / @Composable callback/type | private val EntryShape @Composable get() = ContainerShape.concentric(ContainerPadding) |
| 97 | ui-function / add | fun add(value: T) = apply(entries + ListRowEntry(nextId++, value)) |
| 99 | ui-function / update | fun update(id: Int, value: T) = apply(entries.map { if (it.id == id) it.copy(value = value) else it }) |
| 101 | ui-function / remove | fun remove(id: Int) = apply(entries.filterNot { it.id == id }) |
| 103 | ui-function / move | fun move(from: Int, to: Int) { |
| 109 | ui-function / replace | fun replace(values: List<T>) { |
| 119 | ui-function / apply | private fun apply(list: List<ListRowEntry<T>>) { |
| 134 | composable / rememberListEntries | @Composable |
| 135 | ui-function / rememberListEntries | internal fun <T> rememberListEntries(key: Any?, initial: () -> List<T>, onCommit: (List<T>) -> Unit): ListEntriesState<T> = |
| 138 | composable / ListOptionContainer | @Composable |
| 139 | ui-function / ListOptionContainer | internal fun <T> ListOptionContainer( |
| 150 | composable / @Composable callback/type | row: @Composable RowScope.(entry: ListRowEntry<T>, ctx: ListRowContext) -> Unit, |
| 176 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.SLIDER_TICK) |
| 176 | sound / UiSoundEvent.SLIDER_TICK | UiSounds.play(UiSoundEvent.SLIDER_TICK) |
| 221 | animation / animateFloatAsState | val animatedOffset = animateFloatAsState(targetOffset) |
| 258 | composable / EntryRow | @Composable |
| 259 | ui-function / EntryRow | private fun EntryRow( |
| 265 | composable / @Composable callback/type | content: @Composable RowScope.(ListRowContext) -> Unit, |
| 273 | animation / animateColorAsState | val contentColor = animateColorAsState( |
| 276 | animation / animateColorAsState | val borderColor = animateColorAsState(if (valid) theme.borderColor else ListInvalidColor) |
| 313 | composable / AddEntryButton | @Composable |
| 314 | ui-function / AddEntryButton | private fun AddEntryButton(label: Any, entryHeight: Dp, onClick: () -> Unit) { |
| 318 | animation / animateColorAsState | val contentColor = animateColorAsState(if (isHovered) theme.textColor else theme.textColorSecondary) |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/MultiSelectDropdownOption.kt

`file-a3e37651a95765c5275f`；状态 **pending**；领域 settings；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 核对菜单位置、层级、hover、键鼠选择、点击外部/Esc 关闭和每个命令的结果。 定位类型: menu 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 54 | composable / @Composable callback/type | private val DropdownShape @Composable get() = LocalTheme.current.sideBarNavigationEntryShape |
| 55 | composable / @Composable callback/type | private val ItemShape @Composable get() = DropdownShape.concentric(MenuPadding) |
| 58 | composable / MultiSelectDropdownOption | @Composable |
| 59 | ui-function / MultiSelectDropdownOption | fun MultiSelectDropdownOption(data: MultiSelectDropdownOptionData) { |
| 96 | animation / animateColorAsState | val borderColor = animateColorAsState( |
| 99 | animation / animateColorAsState | val textColor = animateColorAsState( |
| 102 | animation / animateColorAsState | val backgroundColor = animateColorAsState( |
| 106 | animation / animateFloatAsState | val chevronRotation = animateFloatAsState(if (expanded) 0f else 180f) |
| 146 | menu / SimpleDropdownMenu | SimpleDropdownMenu( |
| 210 | composable / CheckableListItem | @Composable |
| 211 | ui-function / CheckableListItem | private fun CheckableListItem( |
| 221 | animation / animateColorAsState | val bgColor = animateColorAsState( |
| 224 | animation / animateColorAsState | val contentColor = animateColorAsState( |
| 249 | composable / PlainListItem | @Composable |
| 250 | ui-function / PlainListItem | private fun PlainListItem( |
| 260 | animation / animateColorAsState | val bgColor = animateColorAsState( |
| 265 | animation / animateColorAsState | val contentColor = animateColorAsState( |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/NumberChainOption.kt

`file-e1adbb8af434c1a6bbae`；状态 **pending**；领域 settings；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 68 | composable / @Composable callback/type | private val ChainShape @Composable get() = LocalTheme.current.sideBarNavigationEntryShape |
| 82 | composable / NumberChainOption | @Composable |
| 83 | ui-function / NumberChainOption | fun NumberChainOption(data: NumberChainOptionData) { |
| 110 | ui-function / commit | fun commit(next: List<ChainEntry>) { |
| 125 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.SLIDER_TICK) |
| 125 | sound / UiSoundEvent.SLIDER_TICK | UiSounds.play(UiSoundEvent.SLIDER_TICK) |
| 153 | animation / animateFloatAsState | val animatedOffset = animateFloatAsState(targetOffset) |
| 154 | animation / animateColorAsState | val bgColor = animateColorAsState(if (isDragging) Accent else theme.chipBackground) |
| 155 | animation / animateColorAsState | val contentColor = animateColorAsState( |
| 258 | animation / animateColorAsState | val addColor = animateColorAsState(if (addHovered) theme.textColor else theme.textColorSecondary) |
| 281 | composable / ChainValueEditor | @Composable |
| 282 | ui-function / ChainValueEditor | private fun ChainValueEditor( |
| 304 | ui-function / commit | fun commit() = onCommit(text.toFloatOrNull()?.coerceIn(min, max) ?: initial) |
| 311 | keybind / keyboardActions | keyboardActions = KeyboardActions(onDone = { commit() }), |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/NumberListOption.kt

`file-645397cddd9b6556be79`；状态 **pending**；领域 settings；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 11 | composable / NumberListOption | @Composable |
| 12 | ui-function / NumberListOption | fun NumberListOption(data: NumberListOptionData) { |
| 36 | composable / NumberEntry | @Composable |
| 37 | ui-function / NumberEntry | private fun RowScope.NumberEntry( |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/NumberOption.kt

`file-a8cf9a081ddab4b6c5c1`；状态 **pending**；领域 settings；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 49 | ui-function / toNumberType | fun Float.toNumberType(type: Class<*>): Number = when (type) { |
| 58 | ui-function / filterNumberInput | fun filterNumberInput(input: String): String { |
| 72 | ui-function / spinnerStep | fun spinnerStep(step: Float, min: Float, max: Float): Float = when { |
| 78 | ui-function / formatSpinnerValue | fun formatSpinnerValue(v: Float): String = |
| 81 | composable / rememberUnitSuffix | @Composable |
| 82 | ui-function / rememberUnitSuffix | private fun rememberUnitSuffix(unit: String?): VisualTransformation { |
| 92 | ui-function / originalToTransformed | override fun originalToTransformed(offset: Int) = offset |
| 93 | ui-function / transformedToOriginal | override fun transformedToOriginal(offset: Int) = offset.coerceAtMost(typed.length) |
| 99 | composable / SpinnerArrow | @Composable |
| 100 | ui-function / SpinnerArrow | private fun SpinnerArrow(icon: String, enabled: Boolean, onClick: () -> Unit) { |
| 104 | animation / animateColorAsState | val color = animateColorAsState( |
| 123 | composable / SpinnerArrows | @Composable |
| 124 | ui-function / SpinnerArrows | fun SpinnerArrows(value: Float, min: Float, max: Float, step: Float, onStep: (Float) -> Unit) { |
| 131 | composable / NumberSpinner | @Composable |
| 132 | ui-function / NumberSpinner | fun NumberSpinner( |
| 155 | ui-function / commit | fun commit(v: Float) { |
| 216 | composable / NumberOption | @Composable |
| 217 | ui-function / NumberOption | fun NumberOption(data: NumberOptionData) { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/Options.kt

`file-d6c131c253a5d5c3b04f`；状态 **pending**；领域 settings；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 核对打开/取消/确认、焦点、边界定位、外点关闭与提交后可见结果。 定位类型: dialog-or-popup 实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 核对菜单位置、层级、hover、键鼠选择、点击外部/Esc 关闭和每个命令的结果。 定位类型: menu 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 67 | ui-function / optionHasDefault | fun optionHasDefault(prop: Property<*>): Boolean = prop.getMetadata<Any?>("default") != null |
| 69 | ui-function / bumpResetEpoch | fun bumpResetEpoch(prop: Property<*>) { |
| 75 | ui-function / resetOption | fun resetOption(prop: Property<*>) { |
| 84 | composable / Option | @Composable |
| 85 | ui-function / Option | fun Option(prop: Property<*>) { |
| 112 | composable / BooleanOption | @Composable |
| 113 | ui-function / BooleanOption | fun BooleanOption(data: BooleanOptionData) { |
| 133 | composable / OptionActionButton | @Composable |
| 134 | ui-function / OptionActionButton | fun OptionActionButton( |
| 173 | composable / OptionContextMenu | @Composable |
| 174 | menu / OptionContextMenu | fun OptionContextMenu( |
| 174 | ui-function / OptionContextMenu | fun OptionContextMenu( |
| 185 | keybind / OneConfigKeybind | val isKeybind = remember(System.identityHashCode(prop)) { prop.get() is OneConfigKeybind } |
| 188 | dialog-or-popup / ShellPopup | ShellPopup( |
| 219 | composable / ContextMenuItem | @Composable |
| 220 | ui-function / ContextMenuItem | private fun ContextMenuItem(icon: String, label: String, enabled: Boolean, onClick: () -> Unit) { |
| 225 | animation / animateColorAsState | val hoverBgColor by animateColorAsState( |
| 227 | animation / tween | animationSpec = tween(120), |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/RadioButtonOption.kt

`file-8106b50492d39d3243bc`；状态 **pending**；领域 settings；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 36 | composable / @Composable callback/type | private val RadioShape @Composable get() = LocalTheme.current.sideBarNavigationEntryShape |
| 37 | composable / @Composable callback/type | private val RadioItemShape @Composable get() = RadioShape.concentric(RadioPadding) |
| 40 | composable / RadioButtonOption | @Composable |
| 41 | ui-function / RadioButtonOption | fun RadioButtonOption(data: RadioButtonOptionData) { |
| 83 | animation / animateDpAsState | val indicatorOffset = animateDpAsState( |
| 85 | animation / spring | animationSpec = spring(), |
| 87 | animation / animateDpAsState | val indicatorWidth = animateDpAsState( |
| 89 | animation / spring | animationSpec = spring(), |
| 91 | animation / animateDpAsState | val indicatorHeight = animateDpAsState( |
| 93 | animation / spring | animationSpec = spring(), |
| 116 | animation / animateColorAsState | val color = animateColorAsState( |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/RangeListOption.kt

`file-aad9e613146897212b54`；状态 **pending**；领域 settings；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 21 | composable / RangeListOption | @Composable |
| 22 | ui-function / RangeListOption | fun RangeListOption(data: RangeListOptionData) { |
| 47 | composable / RangeEntry | @Composable |
| 48 | ui-function / RangeEntry | private fun RowScope.RangeEntry( |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/RangeOption.kt

`file-f185dcbbdbd77001943a`；状态 **pending**；领域 settings；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 54 | composable / RangeOption | @Composable |
| 55 | ui-function / RangeOption | fun RangeOption(data: RangeOptionData) { |
| 60 | ui-function / update | fun update(newStart: Float, newEnd: Float) { |
| 112 | composable / RangeSpinners | @Composable |
| 113 | ui-function / RangeSpinners | internal fun RangeSpinners( |
| 159 | composable / RangeSliderTrack | @Composable |
| 160 | ui-function / RangeSliderTrack | internal fun RangeSliderTrack( |
| 180 | animation / animateFloatAsState | val startFraction = animateFloatAsState(((minOf(start, end) - min) / span).coerceIn(0f, 1f), animationSpec = spring()) |
| 180 | animation / spring | val startFraction = animateFloatAsState(((minOf(start, end) - min) / span).coerceIn(0f, 1f), animationSpec = spring()) |
| 181 | animation / animateFloatAsState | val endFraction = animateFloatAsState(((maxOf(start, end) - min) / span).coerceIn(0f, 1f), animationSpec = spring()) |
| 181 | animation / spring | val endFraction = animateFloatAsState(((maxOf(start, end) - min) / span).coerceIn(0f, 1f), animationSpec = spring()) |
| 190 | ui-function / maybeTick | fun maybeTick(newValue: Float) { |
| 196 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.SLIDER_TICK) |
| 196 | sound / UiSoundEvent.SLIDER_TICK | UiSounds.play(UiSoundEvent.SLIDER_TICK) |
| 199 | ui-function / snap | fun snap(raw: Float): Float { |
| 204 | ui-function / xToValue | fun xToValue(x: Float): Float { |
| 214 | ui-function / apply | fun apply(value: Float) { |
| 244 | ui-function / usablePx | fun usablePx() = (trackWidthPx - thumbPx).coerceAtLeast(0f) |
| 245 | ui-function / startTipPx | fun startTipPx() = startFraction.value * usablePx() + thumbPx |
| 274 | composable / TriangleThumb | @Composable |
| 275 | ui-function / TriangleThumb | private fun TriangleThumb( |
| 314 | ui-function / insetTowards | private fun Offset.insetTowards(previous: Offset, next: Offset, radius: Float): Offset { |
| 324 | ui-function / normalizedOrZero | private fun Offset.normalizedOrZero(): Offset { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/SliderControl.kt

`file-3163a18ec00786c84934`；状态 **pending**；领域 settings；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 38 | composable / SliderControl | @Composable |
| 39 | ui-function / SliderControl | fun SliderControl( |
| 51 | animation / animateFloatAsState | val fraction = animateFloatAsState( |
| 53 | animation / spring | animationSpec = spring(), |
| 64 | ui-function / maybeTick | fun maybeTick(newValue: Float) { |
| 70 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.SLIDER_TICK) |
| 70 | sound / UiSoundEvent.SLIDER_TICK | UiSounds.play(UiSoundEvent.SLIDER_TICK) |
| 73 | ui-function / snap | fun snap(raw: Float): Float { |
| 78 | ui-function / xToValue | fun xToValue(x: Float): Float { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/SliderOption.kt

`file-820b51adb1d930abc6cf`；状态 **pending**；领域 settings；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 17 | composable / SliderOption | @Composable |
| 18 | ui-function / SliderOption | fun SliderOption(data: SliderOptionData) { |
| 21 | ui-function / update | fun update(newValue: Float) { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/SwitchControl.kt

`file-4c93dcddb632aaab4fb6`；状态 **pending**；领域 settings；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 32 | composable / SwitchControl | @Composable |
| 33 | ui-function / SwitchControl | fun SwitchControl(checked: Boolean, onCheckedChange: (Boolean) -> Unit) { |
| 37 | animation / animateFloatAsState | val accentAlpha = animateFloatAsState(if (checked) 1f else 0f) |
| 38 | animation / animateDpAsState | val thumbOffset = animateDpAsState(if (checked) 24.dp else 3.dp, animationSpec = spring()) |
| 38 | animation / spring | val thumbOffset = animateDpAsState(if (checked) 24.dp else 3.dp, animationSpec = spring()) |
| 39 | animation / animateColorAsState | val borderColor = animateColorAsState( |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/TextListOption.kt

`file-d97c92051fc1eb5e55ee`；状态 **pending**；领域 settings；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 17 | composable / TextListOption | @Composable |
| 18 | ui-function / TextListOption | fun TextListOption(data: TextListOptionData) { |
| 20 | ui-function / isValid | fun isValid(value: String) = value.isEmpty() \|\| regex == null \|\| regex.matches(value) |
| 43 | composable / EntryTextField | @Composable |
| 44 | ui-function / EntryTextField | internal fun RowScope.EntryTextField( |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/components/settings/TextOption.kt

`file-452288983e18f3ea3319`；状态 **pending**；领域 settings；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 39 | composable / TextOption | @Composable |
| 40 | ui-function / TextOption | fun TextOption(data: TextOptionData) { |
| 47 | animation / animateColorAsState | val iconColor = animateColorAsState( |
| 55 | animation / animateColorAsState | val borderColor = animateColorAsState(if (valid) theme.borderColor else ListInvalidColor) |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/ComposeSupport.kt

`file-a840eccdf06eaf761db6`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 15 | ui-function / unavailableReason | fun unavailableReason(): String? = recordedFailure ?: probed ?: GlHost.unavailableReason() |
| 19 | ui-function / recordSceneFailure | fun recordSceneFailure(error: Throwable) { |
| 30 | ui-function / describe | private fun describe(error: Throwable): String? { |
| 49 | ui-function / probe | private fun probe(): String? { |
| 60 | ui-function / awtInitFailure | private fun awtInitFailure(): String? = try { |
| 73 | ui-function / classExists | private fun classExists(name: String): Boolean = runCatching { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/FixedScrollConfig.kt

`file-92a2bfd132db52aa5bd1`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 17 | ui-function / calculateMouseWheelScroll | override fun Density.calculateMouseWheelScroll(event: PointerEvent, bounds: IntSize): Offset { |
| 23 | ui-function / provided | fun provided(): ProvidedValue<*> = LocalScrollConfig provides this |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/GlBlur.kt

`file-7d5f19d510fc5e967c3a`；状态 **pending**；领域 blur；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐背景模糊开关、强度、HUD 穿透、resize 与关屏后的 GPU/画面恢复。 定位类型: blur 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 14 | blur / GlBlur | internal object GlBlur { |
| 34 | ui-function / draw | fun draw(dest: Int, w: Int, h: Int, alpha: Float, paused: Boolean) { |
| 81 | ui-function / release | fun release() { |
| 93 | ui-function / blit | private fun blit(from: Int, fw: Int, fh: Int, to: Int, tw: Int, th: Int) { |
| 99 | ui-function / pass | private fun pass(fbo: Int, texture: Int, w: Int, h: Int, stepX: Float, stepY: Float, alpha: Float) { |
| 109 | ui-function / ensure | private fun ensure(i: Int, w: Int, h: Int): Boolean { |
| 126 | ui-function / init | private fun init() { |
| 148 | ui-function / shader | private fun shader(type: Int, src: String): Int { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/StbFonts.kt

`file-9f04a553c85cf2f7d397`；状态 **pending**；领域 font-language；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 46 | ui-function / glyph | override fun glyph(codepoint: Int): Int = glyphIds.getOrPut(codepoint) { stbtt_FindGlyphIndex(info, if (codepoint == '\t'.code) ' '.code else codepoint) } |
| 48 | ui-function / advance | override fun advance(glyph: Int): Float = advanceUnits(glyph) * emScale |
| 50 | ui-function / advanceUnits | fun advanceUnits(glyph: Int): Int = advances.getOrPut(glyph) { |
| 56 | ui-function / metrics | fun metrics(size: Float) = PolyFont.Metrics( |
| 64 | ui-function / raster | override fun raster(glyph: Int, pixelSize: Float): PolyGlyphPage.Raster? = raster(glyph, pixelSize, 0f) |
| 66 | ui-function / raster | override fun raster(glyph: Int, pixelSize: Float, shiftX: Float): PolyGlyphPage.Raster? { |
| 71 | ui-function / rasterExact | private fun rasterExact(id: Int, scale: Float, shiftX: Float): PolyGlyphPage.Raster? { |
| 86 | ui-function / rasterDilated | private fun rasterDilated(id: Int, scale: Float, pixelSize: Float, shiftX: Float): PolyGlyphPage.Raster? { |
| 92 | ui-function / dist | fun dist(x: Int, y: Int) = if (x < 0 \|\| x >= sw) -SDF_EDGE / SDF_SCALE else ((sdf.get(y * sw + x).toInt() and 0xFF) - SDF_EDGE) / SDF_SCALE |
| 111 | ui-function / face | override fun face(name: String): Face? { |
| 119 | ui-function / emoji | override fun emoji() = emoji |
| 122 | ui-function / nameOf | private fun nameOf(face: PolyGlyphPage.Face): String? = faces.entries.firstOrNull { it.value === face }?.key |
| 124 | ui-function / recordGlyphs | fun recordGlyphs(): MutableSet<String> { |
| 130 | ui-function / prerasterize | fun prerasterize(line: String): Boolean { |
| 142 | ui-function / measureTextWidth | override fun measureTextWidth(font: PolyFont, text: String): Float { |
| 162 | ui-function / metrics | override fun metrics(font: PolyFont) = face(font.name)?.metrics(font.size) ?: PolyFont.Metrics(0f, 0f, 0f, 0f, 0f) |
| 169 | ui-function / u16 | private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF shl 8) or (b[o + 1].toInt() and 0xFF) |
| 170 | ui-function / s16 | private fun s16(b: ByteArray, o: Int) = u16(b, o).toShort().toInt() |
| 171 | ui-function / u32 | private fun u32(b: ByteArray, o: Int) = (u16(b, o) shl 16) or u16(b, o + 2) |
| 174 | ui-function / table | private fun table(b: ByteArray, tag: String, minLength: Int): Int { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/compose/opengl/StoredGLStateProps.kt

`file-73dfaf324d2f4e464fb7`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/font/UiFontFiles.kt

`file-945962d1a95f53c4dfee`；状态 **pending**；领域 font-language；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 20 | font-language / UiFontFiles | object UiFontFiles { |
| 51 | ui-function / autodetect | private fun autodetect(): File? { |
| 68 | ui-function / set | fun set(directory: File?, regular: String = "pf_normal.ttf", bold: String = "pf_middleblack.ttf") { |
| 76 | ui-function / readRegular | fun readRegular(): ByteArray? = read(regularFile) |
| 79 | ui-function / readBold | fun readBold(): ByteArray? = read(boldFile) ?: read(regularFile) |
| 82 | ui-function / readFallback | fun readFallback(): ByteArray? = read(fallbackFile) |
| 86 | ui-function / isConfigured | fun isConfigured(): Boolean = resolvedDir() != null |
| 94 | ui-function / resolvedDir | private fun resolvedDir(): File? { |
| 99 | ui-function / read | private fun read(name: String): ByteArray? { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/BuiltinHudConfig.kt

`file-1443797addc447ec9f7c`；状态 **pending**；领域 hud-editor；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 33 | ui-function / register | fun register() { |
| 38 | search-provider / SearchCorpus.invalidate | SearchCorpus.invalidate(HudDocumentSource, HudModCardDocumentSource) |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/GuiTargetRedirect.kt

`file-f56bf931f1ec51ef3b32`；状态 **pending**；领域 hud-editor；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 6 | ui-function / map | fun interface ScissorTransform { |
| 7 | ui-function / map | fun map(left: Int, top: Int, right: Int, bottom: Int): IntArray |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/HudModCardData.kt

`file-dac2de730e9d57d75d2a`；状态 **pending**；领域 hud-editor；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 20 | ui-function / ownerIdOf | private fun ownerIdOf(hud: Hud): String = hud.configId ?: BUILTIN_HUD_CONFIG_ID |
| 22 | ui-function / hudCardId | private fun hudCardId(hud: Hud, ownerId: String) = |
| 25 | ui-function / isCompatHud | private fun isCompatHud(hud: Hud) = hud.category.id == Hud.Category.COMPAT.id |
| 39 | ui-function / cardHuds | private fun cardHuds(): List<Hud> = |
| 43 | ui-function / activeInstancesSnapshot | private fun activeInstancesSnapshot(): List<Hud> = |
| 46 | ui-function / isHudModCard | internal fun isHudModCard(id: String) = id.startsWith(HUD_CARD_ID_PREFIX) |
| 48 | ui-function / withHudSuffix | private fun withHudSuffix(title: Any): Any { |
| 53 | ui-function / findOwner | private fun findOwner(ownerId: String): ConfigData? = |
| 58 | ui-function / ownerVisible | private fun ownerVisible(ownerId: String, owner: ConfigData?): Boolean { |
| 67 | ui-function / cardsSupersededByHudCards | internal fun cardsSupersededByHudCards(hudCards: List<ConfigData>): Set<ConfigData> = |
| 72 | ui-function / hudModCardConfigs | internal fun hudModCardConfigs(): List<ConfigData> = synchronized(cardCache) { |
| 108 | composable / @Composable callback/type | override val preview: (@Composable (Modifier) -> Unit)? = |
| 121 | ui-function / placed | private fun placed(): List<Hud> = |
| 125 | ui-function / isEnabled | override fun isEnabled(): Boolean { |
| 131 | ui-function / setEnabled | override fun setEnabled(enabled: Boolean) { |
| 138 | ui-function / needsSetup | override fun needsSetup(): Boolean = placed().isEmpty() |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/HudModInfo.kt

`file-92093634643efb6edd42`；状态 **pending**；领域 hud-editor；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 8 | ui-function / configForHud | internal fun configForHud(configId: String): ConfigData? { |
| 15 | ui-function / modNameFor | internal fun modNameFor(configId: String): String? { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/HudSettingsReset.kt

`file-48415ca7d958750d2729`；状态 **pending**；领域 hud-editor；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 核对打开/取消/确认、焦点、边界定位、外点关闭与提交后可见结果。 定位类型: dialog-or-popup 实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 核对菜单位置、层级、hover、键鼠选择、点击外部/Esc 关闭和每个命令的结果。 定位类型: menu 从实际宿主入口触发，核对完整状态变化、交互、退出及重进后的结果。 定位类型: peripheral-ui 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 81 | ui-function / hudUiEpoch | fun hudUiEpoch(hud: Hud): Int = hudUiEpochs[hud] ?: 0 |
| 83 | ui-function / bumpHudUiEpoch | internal fun bumpHudUiEpoch(hud: Hud) { |
| 87 | ui-function / hudProperty | fun hudProperty(hud: Hud, optionId: String): Property<*>? = |
| 90 | ui-function / resetHudProperty | fun resetHudProperty(hud: Hud, optionId: String) { |
| 96 | ui-function / hudHasResettableDefaults | fun hudHasResettableDefaults(hud: Hud): Boolean { |
| 101 | ui-function / treeHasResettableDefaults | private fun treeHasResettableDefaults(tree: Tree): Boolean { |
| 111 | ui-function / resetAllHudProperties | fun resetAllHudProperties(hud: Hud) { |
| 118 | ui-function / resetHudStaticSize | private fun resetHudStaticSize(hud: Hud) { |
| 126 | ui-function / hudHasPositionDefaults | fun hudHasPositionDefaults(hud: Hud): Boolean = |
| 131 | ui-function / resetHudPosition | fun resetHudPosition(hud: Hud) { |
| 144 | ui-function / finishHudReset | private fun finishHudReset(hud: Hud) { |
| 151 | ui-function / canResetHudOption | private fun canResetHudOption(hud: Hud, prop: Property<*>, optionId: String): Boolean { |
| 156 | ui-function / performHudOptionReset | private fun performHudOptionReset(hud: Hud, prop: Property<*>, optionId: String) { |
| 170 | ui-function / repairHudStaticSize | fun repairHudStaticSize(hud: Hud) { |
| 181 | ui-function / resetTreeDefaults | private fun resetTreeDefaults(hud: Hud, tree: Tree) { |
| 194 | composable / HudSettingTarget | @Composable |
| 195 | ui-function / HudSettingTarget | fun HudSettingTarget( |
| 199 | composable / @Composable callback/type | content: @Composable () -> Unit, |
| 215 | keybind / KeybindRecordingBus.isRecording | if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed && !KeybindRecordingBus.isRecording) { |
| 239 | menu / OptionContextMenu | OptionContextMenu( |
| 254 | composable / HudSettingsContent | @Composable |
| 255 | composable / @Composable callback/type | fun HudSettingsContent(hud: Hud, content: @Composable () -> Unit) { |
| 255 | ui-function / HudSettingsContent | fun HudSettingsContent(hud: Hud, content: @Composable () -> Unit) { |
| 269 | composable / HudCanvasResetMenu | @Composable |
| 270 | menu / HudCanvasResetMenu | fun HudCanvasResetMenu( |
| 270 | ui-function / HudCanvasResetMenu | fun HudCanvasResetMenu( |
| 314 | dialog-or-popup / ShellPopup | ShellPopup( |
| 388 | peripheral-ui / clearAnchor | actionTargets.forEach { it.clearAnchor() } |
| 454 | composable / HudMenuDivider | @Composable |
| 455 | ui-function / HudMenuDivider | private fun HudMenuDivider() { |
| 466 | keybind / OneConfigKeybind | private fun keybindHint(keybind: OneConfigKeybind?): String? = |
| 466 | ui-function / keybindHint | private fun keybindHint(keybind: OneConfigKeybind?): String? = |
| 470 | composable / HudCanvasPasteMenu | @Composable |
| 471 | menu / HudCanvasPasteMenu | fun HudCanvasPasteMenu( |
| 471 | ui-function / HudCanvasPasteMenu | fun HudCanvasPasteMenu( |
| 490 | dialog-or-popup / ShellPopup | ShellPopup( |
| 542 | composable / HudMenuRow | @Composable |
| 543 | ui-function / HudMenuRow | private fun HudMenuRow( |
| 556 | animation / animateColorAsState | val hoverBgColor by animateColorAsState( |
| 562 | animation / tween | animationSpec = tween(120), |
| 591 | composable / HudAlignRow | @Composable |
| 592 | ui-function / HudAlignRow | private fun HudAlignRow(onAlign: (HudAlign) -> Unit) { |
| 601 | animation / animateColorAsState | val hover by animateColorAsState(theme.textColor.copy(alpha = if (isHovered) 0.10f else 0f), tween(120)) |
| 601 | animation / tween | val hover by animateColorAsState(theme.textColor.copy(alpha = if (isHovered) 0.10f else 0f), tween(120)) |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/components/AlignmentPicker.kt

`file-d0901bf1b10de5271320`；状态 **pending**；领域 hud-editor；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 核对打开/取消/确认、焦点、边界定位、外点关闭与提交后可见结果。 定位类型: dialog-or-popup 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 51 | composable / AlignmentPicker | @Composable |
| 52 | dialog-or-popup / AlignmentPicker | fun AlignmentPicker( |
| 52 | ui-function / AlignmentPicker | fun AlignmentPicker( |
| 101 | composable / AlignmentCell | @Composable |
| 102 | ui-function / AlignmentCell | private fun AlignmentCell( |
| 120 | animation / Animatable | val animatedAlpha = remember { Animatable(0f) } |
| 121 | animation / Animatable | val animatedScale = remember { Animatable(1f) } |
| 125 | animation / animateTo | animatedScale.animateTo( |
| 127 | animation / spring | spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium) |
| 129 | animation / animateTo | animatedScale.animateTo(1f, tween(150)) |
| 129 | animation / tween | animatedScale.animateTo(1f, tween(150)) |
| 134 | animation / animateTo | animatedAlpha.animateTo( |
| 136 | animation / tween | tween(150) |
| 172 | composable / CellIndicator | @Composable |
| 173 | ui-function / CellIndicator | private fun CellIndicator( |
| 202 | ui-function / drawCenterPlus | private fun DrawScope.drawCenterPlus( |
| 237 | ui-function / drawCornerBracket | private fun DrawScope.drawCornerBracket( |
| 267 | ui-function / drawEdgeTick | private fun DrawScope.drawEdgeTick( |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/components/Dropdown.kt

`file-958868d527669a405b75`；状态 **pending**；领域 hud-editor；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 核对菜单位置、层级、hover、键鼠选择、点击外部/Esc 关闭和每个命令的结果。 定位类型: menu 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 46 | composable / Dropdown | @Composable |
| 47 | ui-function / Dropdown | inline fun <reified E : Enum<E>> Dropdown( |
| 57 | animation / animateFloatAsState | val chevronRotation by animateFloatAsState( |
| 60 | animation / animateColorAsState | val textColor by animateColorAsState( |
| 92 | menu / SimpleDropdownMenu | SimpleDropdownMenu( |
| 124 | composable / DropdownItem | @Composable |
| 125 | ui-function / DropdownItem | fun <E : Enum<E>> DropdownItem( |
| 134 | animation / animateColorAsState | val backgroundColor by animateColorAsState( |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/components/HudPreview.kt

`file-b17e81df707cc3e77aec`；状态 **pending**；领域 hud-editor；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 48 | ui-function / isFatalPreviewFailure | private fun Throwable.isFatalPreviewFailure(): Boolean = this is VirtualMachineError \|\| this is ThreadDeath |
| 50 | ui-function / updateForPreview | private fun Hud.updateForPreview() { |
| 69 | ui-function / dispose | fun dispose() { |
| 78 | ui-function / get | internal fun get(hud: Hud, revision: Int, build: () -> HudPreviewState): HudPreviewState { |
| 86 | ui-function / releaseAll | private fun releaseAll() { |
| 100 | composable / rememberHudPreview | @Composable |
| 101 | ui-function / rememberHudPreview | internal fun rememberHudPreview(hud: Hud): HudPreviewState { |
| 142 | ui-function / hudPreviewScale | internal fun hudPreviewScale(naturalW: Float, naturalH: Float, availableW: Float, availableH: Float): Float { |
| 147 | composable / HudPreviewCanvas | @Composable |
| 148 | ui-function / HudPreviewCanvas | internal fun HudPreviewCanvas( |
| 158 | animation / withFrameNanos | withFrameNanos { animationRevision++ } |
| 179 | ui-function / measure | override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult { |
| 186 | ui-function / minIntrinsicWidth | override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) = 0 |
| 187 | ui-function / maxIntrinsicWidth | override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) = 0 |
| 188 | ui-function / minIntrinsicHeight | override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) = 0 |
| 189 | ui-function / maxIntrinsicHeight | override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) = 0 |
| 192 | composable / HudPreview | @Composable |
| 193 | ui-function / HudPreview | internal fun HudPreview(hud: Hud, modifier: Modifier = Modifier) { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/components/Radio.kt

`file-d9211849cbd8274e1f8c`；状态 **pending**；领域 hud-editor；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 29 | composable / Radio | @Composable |
| 30 | ui-function / Radio | inline fun <reified E> Radio( |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudAdditions.kt

`file-b1575ca3294b27a5e209`；状态 **pending**；领域 hud-editor；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: page-or-page-helper

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 6 | composable / HudAdditions | @Composable |
| 7 | page-or-page-helper / HudAdditions | fun HudAdditions() { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudDesignSession.kt

`file-8ea0a1eb86e93dc7cc9a`；状态 **pending**；领域 hud-editor；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: page-or-page-helper

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 49 | page-or-page-helper / post | private fun post(command: StudioCommand) { |
| 54 | page-or-page-helper / clearCommands | internal fun clearCommands() { |
| 60 | page-or-page-helper / handleDuplicate | fun handleDuplicate(pressed: Boolean): Boolean { |
| 75 | page-or-page-helper / handleOpenSettings | fun handleOpenSettings(pressed: Boolean): Boolean { |
| 87 | page-or-page-helper / handleToggleVisibility | fun handleToggleVisibility(pressed: Boolean): Boolean { |
| 101 | page-or-page-helper / handleReset | fun handleReset(pressed: Boolean): Boolean { |
| 112 | page-or-page-helper / handleCopy | fun handleCopy(pressed: Boolean): Boolean { |
| 122 | page-or-page-helper / handleCut | fun handleCut(pressed: Boolean): Boolean { |
| 132 | page-or-page-helper / handlePaste | fun handlePaste(pressed: Boolean): Boolean { |
| 141 | page-or-page-helper / handleDelete | fun handleDelete(pressed: Boolean): Boolean { |
| 151 | page-or-page-helper / handleSelectAll | fun handleSelectAll(pressed: Boolean): Boolean { |
| 160 | page-or-page-helper / handleLock | fun handleLock(pressed: Boolean): Boolean { |
| 169 | page-or-page-helper / handleUndo | fun handleUndo(pressed: Boolean): Boolean { |
| 177 | page-or-page-helper / handleRedo | fun handleRedo(pressed: Boolean): Boolean { |
| 185 | page-or-page-helper / handleSearch | fun handleSearch(pressed: Boolean): Boolean { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudDesignStudio.kt

`file-df7ee1154b735d4c669b`；状态 **pending**；领域 hud-editor；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 核对菜单位置、层级、hover、键鼠选择、点击外部/Esc 关闭和每个命令的结果。 定位类型: menu 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: page-or-page-helper 从实际宿主入口触发，核对完整状态变化、交互、退出及重进后的结果。 定位类型: peripheral-ui 注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider 对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 119 | page-or-page-helper / notifyHudLocked | private fun notifyHudLocked() { |
| 120 | peripheral-ui / NotificationsManager.contains | lockedHudToast?.let { if (NotificationsManager.contains(it)) return } |
| 128 | page-or-page-helper / safePointerEvent | private fun Modifier.safePointerEvent( |
| 157 | page-or-page-helper / update | fun update(width: Int, height: Int) { |
| 169 | page-or-page-helper / observe | fun observe() { |
| 244 | page-or-page-helper / snapAxis | private fun snapAxis(pos: Float, size: Float, lines: List<Float>, threshold: Float): AxisSnap { |
| 267 | page-or-page-helper / verticalSnapLines | private fun verticalSnapLines(dragged: Hud): List<Float> { |
| 282 | page-or-page-helper / horizontalSnapLines | private fun horizontalSnapLines(dragged: Hud): List<Float> { |
| 297 | page-or-page-helper / overlapArea | private fun overlapArea(a: Rect, b: Rect): Float { |
| 313 | page-or-page-helper / pickHintSlot | private fun pickHintSlot(root: IntSize, hint: IntSize, margin: Float, obstacles: List<Rect>): Offset { |
| 353 | page-or-page-helper / alignHuds | internal fun alignHuds(huds: List<Hud>, align: HudAlign) { |
| 384 | page-or-page-helper / hudBounds | private fun hudBounds(hud: Hud): HudBounds? { |
| 425 | page-or-page-helper / anchorFractions | private fun anchorFractions(hud: Hud, point: HudAnchor): Pair<Float, Float> { |
| 441 | page-or-page-helper / anchorPointOf | private fun anchorPointOf(hud: Hud, point: HudAnchor): Offset? { |
| 448 | page-or-page-helper / anchorTargetsFor | private fun anchorTargetsFor(sources: List<Hud>): List<Hud> = |
| 454 | page-or-page-helper / hitTestSelfAnchorPoint | private fun hitTestSelfAnchorPoint( |
| 472 | page-or-page-helper / hitTestAnchorPoint | private fun hitTestAnchorPoint( |
| 492 | page-or-page-helper / drawHudContents | private fun DrawScope.drawHudContents(mcToScreen: Float) { |
| 499 | page-or-page-helper / drawHudContents | private fun drawHudContents(ctx: RenderContext, mcToScreen: Float) { |
| 520 | page-or-page-helper / orderedInstances | private fun orderedInstances(): List<Hud> = HudManager.zOrderedInstances { hud -> |
| 524 | page-or-page-helper / hitTestHud | private fun hitTestHud(hud: Hud, screenX: Float, screenY: Float): Boolean { |
| 537 | page-or-page-helper / hudStackAt | private fun hudStackAt(screenX: Float, screenY: Float): List<Hud> { |
| 542 | page-or-page-helper / topHudAt | private fun topHudAt(screenX: Float, screenY: Float): Hud? = hudStackAt(screenX, screenY).lastOrNull() |
| 544 | page-or-page-helper / pickHudAt | private fun pickHudAt(screenX: Float, screenY: Float, current: Hud?): Hud? { |
| 559 | page-or-page-helper / contains | fun contains(px: Float, py: Float) = |
| 565 | page-or-page-helper / hudActionBarIcons | private fun hudActionBarIcons(hud: Hud) = |
| 569 | page-or-page-helper / actionBarPadding | private fun actionBarPadding(gapPx: Float) = gapPx / 2f |
| 571 | page-or-page-helper / hudActionBarLayout | private fun hudActionBarLayout( |
| 610 | page-or-page-helper / hitTestHudActionBar | private fun hitTestHudActionBar( |
| 625 | page-or-page-helper / hitTestHudActionBarZone | private fun hitTestHudActionBarZone( |
| 658 | page-or-page-helper / hitTestHudWithActionBar | private fun hitTestHudWithActionBar( |
| 680 | page-or-page-helper / hitTestResizeHandle | private fun hitTestResizeHandle(hud: Hud, screenX: Float, screenY: Float, mcToScreen: Float): ResizeCorner? { |
| 688 | page-or-page-helper / contains | fun contains(cx: Float, cy: Float): Boolean = |
| 701 | page-or-page-helper / resizeHud | private fun resizeHud( |
| 772 | page-or-page-helper / drawSelectedHudBounds | private fun DrawScope.drawSelectedHudBounds( |
| 810 | page-or-page-helper / drawHudSizeBadge | private fun DrawScope.drawHudSizeBadge(label: String, centerX: Float, topY: Float) { |
| 832 | composable / HudActionButton | @Composable |
| 833 | page-or-page-helper / HudActionButton | private fun HudActionButton( |
| 838 | sound / UiSoundEvent | sound: UiSoundEvent, |
| 850 | sound / UiSounds.play | UiSounds.play(sound) |
| 866 | composable / HudActionBar | @Composable |
| 867 | page-or-page-helper / HudActionBar | private fun HudActionBar( |
| 902 | sound / UiSoundEvent.HUD_SELECT | sound = UiSoundEvent.HUD_SELECT, |
| 910 | sound / UiSoundEvent.CLICK | sound = UiSoundEvent.CLICK, |
| 919 | sound / UiSoundEvent.CLICK | sound = UiSoundEvent.CLICK, |
| 929 | sound / UiSoundEvent.CLICK | sound = UiSoundEvent.CLICK, |
| 936 | page-or-page-helper / pickTourHud | private fun pickTourHud(): Hud? { |
| 943 | page-or-page-helper / hasTourMergePair | private fun hasTourMergePair(): Boolean { |
| 948 | page-or-page-helper / canTourMergeInto | private fun canTourMergeInto(mover: Hud, neighbour: Hud): Boolean = |
| 952 | page-or-page-helper / canTourMerge | private fun canTourMerge(hud: Hud): Boolean = |
| 955 | page-or-page-helper / tourMergeHint | private fun tourMergeHint(mcToScreen: Float): Pair<Rect, Rect>? = |
| 958 | page-or-page-helper / tourMergeHint | private fun tourMergeHint(mcToScreen: Float, stacked: Boolean): Pair<Rect, Rect>? { |
| 987 | page-or-page-helper / scaled | private fun Rect.scaled(factor: Float) = Rect(left * factor, top * factor, right * factor, bottom * factor) |
| 989 | page-or-page-helper / including | private fun Rect.including(other: Rect) = |
| 992 | page-or-page-helper / tourMenuRect | private fun tourMenuRect(hud: Rect, menu: IntSize, root: IntSize, gap: Float): Rect { |
| 999 | page-or-page-helper / placeHudCentered | private fun placeHudCentered(provider: Hud): Hud? = try { |
| 1033 | composable / HudDesignStudio | @Composable |
| 1034 | page-or-page-helper / HudDesignStudio | fun HudDesignStudio(visible: Boolean = true) { |
| 1098 | peripheral-ui / HudTourStep | var tourStep by remember { mutableStateOf<HudTourStep?>(null) } |
| 1111 | page-or-page-helper / primaryHud | fun primaryHud(): Hud? = selectedHuds.lastOrNull() |
| 1113 | page-or-page-helper / closeLibrary | fun closeLibrary() { |
| 1121 | page-or-page-helper / clearTransientEditorState | fun clearTransientEditorState() { |
| 1130 | page-or-page-helper / startTour | fun startTour() { |
| 1140 | peripheral-ui / HudTourStep.entries | tourStep = HudTourStep.entries.first() |
| 1145 | page-or-page-helper / endTour | fun endTour() { |
| 1164 | page-or-page-helper / moveTour | fun moveTour(delta: Int) { |
| 1211 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLICK) |
| 1211 | sound / UiSoundEvent.CLICK | UiSounds.play(UiSoundEvent.CLICK) |
| 1249 | keybind / TextInputFocus.isTyping | if (!TextInputFocus.isTyping) break |
| 1287 | page-or-page-helper / libraryHuds | fun libraryHuds() = providers.filter { it.addableToLibrary } |
| 1288 | page-or-page-helper / libraryModIds | fun libraryModIds(huds: List<Hud> = libraryHuds()) = huds.mapNotNull { it.configId }.distinct() |
| 1316 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLICK) |
| 1316 | sound / UiSoundEvent.CLICK | UiSounds.play(UiSoundEvent.CLICK) |
| 1337 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLICK) |
| 1337 | sound / UiSoundEvent.CLICK | UiSounds.play(UiSoundEvent.CLICK) |
| 1382 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLICK) |
| 1382 | sound / UiSoundEvent.CLICK | UiSounds.play(UiSoundEvent.CLICK) |
| 1449 | page-or-page-helper / chromeRegion | fun Modifier.chromeRegion(key: String) = onGloballyPositioned { coords -> |
| 1454 | page-or-page-helper / chromeBlocker | fun Modifier.chromeBlocker(key: String) = this |
| 1467 | page-or-page-helper / inChrome | fun inChrome(px: Float, py: Float): Boolean { |
| 1472 | page-or-page-helper / movePanel | fun movePanel(delta: Offset) { |
| 1485 | page-or-page-helper / updateMarqueeSelection | fun updateMarqueeSelection() { |
| 1526 | sound / UiSounds.play | if (pickedSelf != null) UiSounds.play(UiSoundEvent.CLICK) |
| 1526 | sound / UiSoundEvent.CLICK | if (pickedSelf != null) UiSounds.play(UiSoundEvent.CLICK) |
| 1532 | peripheral-ui / clearAnchor | secondary -> sources.forEach { it.clearAnchor() } |
| 1534 | peripheral-ui / anchorTo | it.anchorTo(picked.first, picked.second, self ?: HudAnchor.Auto) |
| 1543 | sound / UiSounds.play | if (secondary \|\| picked != null) UiSounds.play(UiSoundEvent.CLICK) |
| 1543 | sound / UiSoundEvent.CLICK | if (secondary \|\| picked != null) UiSounds.play(UiSoundEvent.CLICK) |
| 1554 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.HUD_SELECT) |
| 1554 | sound / UiSoundEvent.HUD_SELECT | UiSounds.play(UiSoundEvent.HUD_SELECT) |
| 1578 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.HUD_RESIZE_START) |
| 1578 | sound / UiSoundEvent.HUD_RESIZE_START | UiSounds.play(UiSoundEvent.HUD_RESIZE_START) |
| 1580 | peripheral-ui / pinCorner | selected.pinCorner( |
| 1610 | keybind / isShiftPressed | val shift = event.keyboardModifiers.isShiftPressed |
| 1614 | sound / UiSounds.play | if (hit !in selectedHuds) UiSounds.play(UiSoundEvent.HUD_SELECT) |
| 1614 | sound / UiSoundEvent.HUD_SELECT | if (hit !in selectedHuds) UiSounds.play(UiSoundEvent.HUD_SELECT) |
| 1675 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.HUD_DRAG_START) |
| 1675 | sound / UiSoundEvent.HUD_DRAG_START | UiSounds.play(UiSoundEvent.HUD_DRAG_START) |
| 1723 | keybind / isShiftPressed | freeResize = event.keyboardModifiers.isShiftPressed, |
| 1752 | keybind / isAltPressed | if (bounds != null && !event.keyboardModifiers.isAltPressed) { |
| 1840 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.HUD_RESIZE_END) |
| 1840 | sound / UiSoundEvent.HUD_RESIZE_END | UiSounds.play(UiSoundEvent.HUD_RESIZE_END) |
| 1850 | sound / UiSounds.play | if (!click.wasSelected \|\| narrowsSelection) UiSounds.play(UiSoundEvent.HUD_SELECT) |
| 1850 | sound / UiSoundEvent.HUD_SELECT | if (!click.wasSelected \|\| narrowsSelection) UiSounds.play(UiSoundEvent.HUD_SELECT) |
| 1882 | sound / UiSounds.play | if (hit !in selectedHuds) UiSounds.play(UiSoundEvent.HUD_SELECT) |
| 1882 | sound / UiSoundEvent.HUD_SELECT | if (hit !in selectedHuds) UiSounds.play(UiSoundEvent.HUD_SELECT) |
| 1889 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.HUD_DRAG_END) |
| 1889 | sound / UiSoundEvent.HUD_DRAG_END | UiSounds.play(UiSoundEvent.HUD_DRAG_END) |
| 1904 | animation / animateFloatAsState | val chromeAlpha by animateFloatAsState( |
| 1906 | animation / tween | animationSpec = tween(150), |
| 1910 | animation / marqueeSelectionAnim | val marqueeAnimEnabled = OneConfigConfig.marqueeSelectionAnim |
| 1911 | animation / marqueeSelectionAnimDuration.toInt | val marqueeAnimDuration = OneConfigConfig.marqueeSelectionAnimDuration.toInt().coerceAtLeast(1) |
| 1912 | animation / animateFloatAsState | val marqueeAlpha by animateFloatAsState( |
| 1914 | animation / tween | animationSpec = tween(if (marqueeAnimEnabled) marqueeAnimDuration else 0), |
| 1931 | animation / animateFloatAsState | val sideChromeAlpha by animateFloatAsState( |
| 1933 | animation / tween | animationSpec = tween(150), |
| 1961 | animation / animateFloatAsState | val panelDockX by animateFloatAsState( |
| 1963 | animation / tween | animationSpec = tween(220), |
| 1981 | keybind / onKeyEvent | .onKeyEvent { keyEvent -> |
| 1982 | keybind / onKeyEvent | if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false |
| 1985 | keybind / Key.DirectionRight | Key.DirectionRight, Key.Enter -> moveTour(1) |
| 1985 | keybind / Key.Enter | Key.DirectionRight, Key.Enter -> moveTour(1) |
| 1986 | keybind / Key.DirectionLeft | Key.DirectionLeft -> moveTour(-1) |
| 1988 | keybind / onKeyEvent | return@onKeyEvent keyEvent.key != Key.Escape |
| 1988 | keybind / Key.Escape | return@onKeyEvent keyEvent.key != Key.Escape |
| 1990 | keybind / Key.Escape | if (keyEvent.key == Key.Escape && anchorPickSources.isNotEmpty()) { |
| 1996 | keybind / onKeyEvent | return@onKeyEvent true |
| 1998 | keybind / Key.Escape | if (keyEvent.key == Key.Escape && (selectedHuds.isNotEmpty() \|\| panelOpen)) { |
| 2003 | keybind / onKeyEvent | return@onKeyEvent true |
| 2005 | keybind / onKeyEvent | if (selectedHuds.isEmpty()) return@onKeyEvent false |
| 2007 | keybind / onKeyEvent | primaryHud() ?: return@onKeyEvent false |
| 2008 | keybind / isShiftPressed | val step = if (keyEvent.isShiftPressed) 10f else 1f |
| 2010 | keybind / Key.DirectionLeft | Key.DirectionLeft -> -step to 0f |
| 2011 | keybind / Key.DirectionRight | Key.DirectionRight -> step to 0f |
| 2012 | keybind / Key.DirectionUp | Key.DirectionUp -> 0f to -step |
| 2013 | keybind / Key.DirectionDown | Key.DirectionDown -> 0f to step |
| 2014 | keybind / onKeyEvent | else -> return@onKeyEvent false |
| 2018 | keybind / onKeyEvent | return@onKeyEvent true |
| 2278 | animation / tween | val hintSpec = tween<Float>(durationMillis = if (hintPlaced) 160 else 0) |
| 2279 | animation / animateFloatAsState | val hintX by animateFloatAsState(hintSlot.x, hintSpec, label = "hintX") |
| 2280 | animation / animateFloatAsState | val hintY by animateFloatAsState(hintSlot.y, hintSpec, label = "hintY") |
| 2281 | animation / AnimatedVisibility | AnimatedVisibility( |
| 2312 | animation / AnimatedVisibility | AnimatedVisibility( |
| 2338 | animation / AnimatedVisibility | AnimatedVisibility( |
| 2355 | animation / AnimatedVisibility | AnimatedVisibility( |
| 2361 | peripheral-ui / HudLibraryPanel | HudLibraryPanel( |
| 2406 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.HUD_DRAG_START) |
| 2406 | sound / UiSoundEvent.HUD_DRAG_START | UiSounds.play(UiSoundEvent.HUD_DRAG_START) |
| 2424 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.HUD_SELECT) |
| 2424 | sound / UiSoundEvent.HUD_SELECT | UiSounds.play(UiSoundEvent.HUD_SELECT) |
| 2464 | menu / HudCanvasResetMenu | HudCanvasResetMenu( |
| 2480 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLICK) |
| 2480 | sound / UiSoundEvent.CLICK | UiSounds.play(UiSoundEvent.CLICK) |
| 2498 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLICK) |
| 2498 | sound / UiSoundEvent.CLICK | UiSounds.play(UiSoundEvent.CLICK) |
| 2506 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLICK) |
| 2506 | sound / UiSoundEvent.CLICK | UiSounds.play(UiSoundEvent.CLICK) |
| 2529 | menu / HudCanvasPasteMenu | HudCanvasPasteMenu( |
| 2541 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLICK) |
| 2541 | sound / UiSoundEvent.CLICK | UiSounds.play(UiSoundEvent.CLICK) |
| 2547 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLICK) |
| 2547 | sound / UiSoundEvent.CLICK | UiSounds.play(UiSoundEvent.CLICK) |
| 2556 | peripheral-ui / HudTourStep.entries | var lastTourStep by remember { mutableStateOf(HudTourStep.entries.first()) } |
| 2558 | animation / AnimatedVisibility | AnimatedVisibility( |
| 2596 | menu / HudCanvasResetMenu | HudCanvasResetMenu( |
| 2627 | peripheral-ui / HudTourOverlay | HudTourOverlay( |
| 2644 | page-or-page-helper / canDuplicateHud | internal fun canDuplicateHud(hud: Hud?): Boolean = |
| 2647 | page-or-page-helper / duplicateHud | internal fun duplicateHud( |
| 2697 | page-or-page-helper / detachedValues | private fun detachedValues(tree: Tree): Tree { |
| 2712 | page-or-page-helper / duplicateHudGroup | internal fun duplicateHudGroup( |
| 2765 | composable / HudDragLayer | @Composable |
| 2766 | page-or-page-helper / HudDragLayer | fun HudDragLayer(modifier: Modifier = Modifier) { |
| 2783 | page-or-page-helper / endDrag | fun endDrag() { |
| 2794 | page-or-page-helper / overShell | fun overShell(px: Float, py: Float) = shellLayout.shellBounds?.contains(Offset(px, py)) == true |
| 2823 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.HUD_DRAG_START) |
| 2823 | sound / UiSoundEvent.HUD_DRAG_START | UiSounds.play(UiSoundEvent.HUD_DRAG_START) |
| 2846 | keybind / isAltPressed | if (bounds != null && !event.keyboardModifiers.isAltPressed) { |
| 2892 | sound / UiSounds.play | if (isDragging) UiSounds.play(UiSoundEvent.HUD_DRAG_END) |
| 2892 | sound / UiSoundEvent.HUD_DRAG_END | if (isDragging) UiSounds.play(UiSoundEvent.HUD_DRAG_END) |
| 3000 | composable / DesignStudioPanel | @Composable |
| 3001 | page-or-page-helper / DesignStudioPanel | private fun DesignStudioPanel( |
| 3092 | animation / AnimatedContent | AnimatedContent( |
| 3096 | animation / tween | (slideInHorizontally(tween(250)) { direction * it / 3 } + fadeIn(tween(250))) |
| 3096 | animation / tween | (slideInHorizontally(tween(250)) { direction * it / 3 } + fadeIn(tween(250))) |
| 3097 | animation / tween | .togetherWith(slideOutHorizontally(tween(250)) { -direction * it / 3 } + fadeOut(tween(250))) |
| 3097 | animation / tween | .togetherWith(slideOutHorizontally(tween(250)) { -direction * it / 3 } + fadeOut(tween(250))) |
| 3129 | composable / rememberHudSearchResults | @Composable |
| 3130 | page-or-page-helper / rememberHudSearchResults | private fun rememberHudSearchResults(providers: List<Hud>, query: String): List<Pair<String?, List<Hud>>>? { |
| 3136 | search-provider / SearchCorpus.searchGrouped | SearchCorpus.searchGrouped(query, setOf(SearchScope.Huds)) { (it.payload as? Hud)?.configId } |
| 3136 | search-provider / SearchScope.Huds | SearchCorpus.searchGrouped(query, setOf(SearchScope.Huds)) { (it.payload as? Hud)?.configId } |
| 3163 | page-or-page-helper / isCompatLibraryEntry | private fun Hud.isCompatLibraryEntry(): Boolean = |
| 3171 | page-or-page-helper / librarySectionKey | private fun librarySectionKey(modId: String?): String = modId ?: "" |
| 3173 | page-or-page-helper / libraryIconFor | private fun libraryIconFor(modId: String?): String = |
| 3206 | page-or-page-helper / hudLibraryScrollMetrics | private fun hudLibraryScrollMetrics( |
| 3233 | page-or-page-helper / scrollTo | override suspend fun scrollTo(scrollOffset: Double) { |
| 3241 | composable / HudLibraryHeader | @Composable |
| 3242 | page-or-page-helper / HudLibraryHeader | private fun HudLibraryHeader(section: HudLibrarySection, active: Boolean) { |
| 3242 | peripheral-ui / HudLibraryHeader | private fun HudLibraryHeader(section: HudLibrarySection, active: Boolean) { |
| 3244 | animation / animateColorAsState | val textColor by animateColorAsState( |
| 3246 | animation / tween | animationSpec = tween(150), |
| 3267 | page-or-page-helper / packHudLibraryRows | private fun packHudLibraryRows(huds: List<Hud>, sizes: Map<Hud, IntSize>, availableWidth: Int, spacing: Int): List<HudLibraryRow> { |
| 3294 | composable / rememberHudLibraryLayout | @Composable |
| 3295 | page-or-page-helper / rememberHudLibraryLayout | private fun rememberHudLibraryLayout(sections: List<HudLibrarySection>, maxCardWidth: Dp): HudLibraryLayout { |
| 3353 | composable / HudLibraryPanel | @Composable |
| 3354 | page-or-page-helper / HudLibraryPanel | private fun HudLibraryPanel( |
| 3354 | peripheral-ui / HudLibraryPanel | private fun HudLibraryPanel( |
| 3432 | animation / animateScrollBy | scrollState.animateScrollBy(distance.toFloat()) |
| 3459 | peripheral-ui / HudLibraryHeader | HudLibraryHeader(libraryItem.section, active = libraryItem.section.modId == activeModId) |
| 3497 | composable / EmptyHudLibraryHint | @Composable |
| 3498 | page-or-page-helper / EmptyHudLibraryHint | private fun EmptyHudLibraryHint(modifier: Modifier) { |
| 3516 | composable / ModIconColumn | @Composable |
| 3517 | page-or-page-helper / ModIconColumn | private fun ModIconColumn( |
| 3545 | composable / HudCardAddOverlay | @Composable |
| 3546 | page-or-page-helper / HudCardAddOverlay | private fun BoxScope.HudCardAddOverlay(visible: Boolean) { |
| 3547 | animation / AnimatedVisibility | AnimatedVisibility( |
| 3550 | animation / tween | enter = fadeIn(tween(150)), |
| 3551 | animation / tween | exit = fadeOut(tween(150)), |
| 3566 | page-or-page-helper / hudLabel | private fun hudLabel(hud: Hud): String = localizedLabel(hud.title) ?: hud.title |
| 3570 | page-or-page-helper / previewScaleFor | private fun previewScaleFor(naturalW: Float, naturalH: Float, maxCardWidth: Dp, density: Float): Float { |
| 3578 | page-or-page-helper / previewCardSize | private fun previewCardSize(naturalW: Float, naturalH: Float, scale: Float, maxCardWidth: Dp, density: Float, legacy: Boolean): DpSize { |
| 3588 | composable / LegacyHudPreviewCard | @Composable |
| 3589 | page-or-page-helper / LegacyHudPreviewCard | private fun LegacyHudPreviewCard( |
| 3600 | animation / animateColorAsState | val backgroundColor by animateColorAsState( |
| 3602 | animation / tween | animationSpec = tween(150) |
| 3669 | composable / ComposeHudPreviewCard | @Composable |
| 3670 | page-or-page-helper / ComposeHudPreviewCard | private fun ComposeHudPreviewCard( |
| 3682 | animation / animateColorAsState | val backgroundColor by animateColorAsState( |
| 3684 | animation / tween | animationSpec = tween(150) |
| 3762 | composable / ModFilterIcon | @Composable |
| 3763 | page-or-page-helper / ModFilterIcon | private fun ModFilterIcon(iconName: String, selected: Boolean, onClick: () -> Unit) { |
| 3767 | animation / animateFloatAsState | val iconAlpha by animateFloatAsState( |
| 3797 | composable / SearchBar | @Composable |
| 3798 | page-or-page-helper / SearchBar | private fun SearchBar( |
| 3822 | animation / animateColorAsState | val borderColor by animateColorAsState( |
| 3825 | animation / animateColorAsState | val iconColor by animateColorAsState( |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudHistory.kt

`file-e1997b22b94837f521cf`；状态 **pending**；领域 hud-editor；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: page-or-page-helper

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 46 | page-or-page-helper / sameValue | private fun sameValue(a: Any?, b: Any?): Boolean = |
| 49 | page-or-page-helper / sameLayout | private fun sameLayout(a: Layout, b: Layout): Boolean { |
| 63 | page-or-page-helper / forEachOption | private fun forEachOption(tree: Tree, prefix: String = "", action: (String, Property<*>) -> Unit) { |
| 76 | page-or-page-helper / comparable | private fun comparable(prop: Property<*>): Any? { |
| 86 | page-or-page-helper / capture | private fun capture(): Layout { |
| 112 | page-or-page-helper / write | private fun write(hud: Hud, entry: Entry): Boolean { |
| 140 | page-or-page-helper / revive | private fun revive(id: String, entry: Entry): Hud? { |
| 151 | page-or-page-helper / restore | private fun restore(target: Layout): List<Hud> { |
| 192 | page-or-page-helper / reset | private fun reset(now: Layout) { |
| 199 | page-or-page-helper / record | private fun record(now: Layout) { |
| 207 | page-or-page-helper / unrecorded | private fun unrecorded(): Boolean = recorded?.let { !sameLayout(it, capture()) } == true |
| 214 | page-or-page-helper / poll | fun poll(busy: Boolean) { |
| 230 | page-or-page-helper / undo | fun undo(): List<Hud>? = step(undoStack, redoStack) |
| 232 | page-or-page-helper / redo | fun redo(): List<Hud>? = step(redoStack, undoStack) |
| 234 | page-or-page-helper / step | private fun step(from: ArrayDeque<Layout>, to: ArrayDeque<Layout>): List<Hud>? { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/HudTour.kt

`file-20d0bde61524365f6326`；状态 **pending**；领域 hud-tour；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: page-or-page-helper 从实际宿主入口触发，核对完整状态变化、交互、退出及重进后的结果。 定位类型: peripheral-ui 核对该导览步骤的遮罩、目标区域、文案、可交互推进条件、上一步/下一步与退出恢复。 定位类型: tour-step

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 59 | peripheral-ui / HudTourStep | internal enum class HudTourStep( |
| 65 | tour-step / Welcome | Welcome("welcome", HudTourFocus.None), |
| 66 | tour-step / Adding | Adding("adding", HudTourFocus.Library), |
| 67 | tour-step / Dragging | Dragging("dragging", HudTourFocus.Hud), |
| 68 | tour-step / Merging | Merging("merging", HudTourFocus.None, interactive = true), |
| 69 | tour-step / Anchoring | Anchoring("anchoring", HudTourFocus.AnchorMenu), |
| 70 | tour-step / Settings | Settings("settings", HudTourFocus.Panel), |
| 71 | tour-step / Designer | Designer("designer", HudTourFocus.Panel, category = StudioCategory.Designer), |
| 72 | tour-step / Fonts | Fonts("fonts", HudTourFocus.Panel, category = StudioCategory.Designer), |
| 73 | tour-step / Done | Done("done", HudTourFocus.None); |
| 80 | page-or-page-helper / offset | fun offset(delta: Int): HudTourStep? = entries.getOrNull(ordinal + delta) |
| 80 | peripheral-ui / HudTourStep | fun offset(delta: Int): HudTourStep? = entries.getOrNull(ordinal + delta) |
| 83 | page-or-page-helper / tourString | internal fun tourString(key: String): String = Platform.i18n().translateString("oneconfig.hud.tour.$key") |
| 88 | page-or-page-helper / placeTourCard | private fun placeTourCard( |
| 122 | composable / HudTourOverlay | @Composable |
| 123 | page-or-page-helper / HudTourOverlay | internal fun HudTourOverlay( |
| 123 | peripheral-ui / HudTourOverlay | internal fun HudTourOverlay( |
| 124 | peripheral-ui / HudTourStep | step: HudTourStep, |
| 143 | animation / tween | val holeSpec = tween<Float>(220) |
| 144 | animation / animateFloatAsState | val holeLeft by animateFloatAsState(target.left, holeSpec, label = "tourHoleLeft") |
| 145 | animation / animateFloatAsState | val holeTop by animateFloatAsState(target.top, holeSpec, label = "tourHoleTop") |
| 146 | animation / animateFloatAsState | val holeRight by animateFloatAsState(target.right, holeSpec, label = "tourHoleRight") |
| 147 | animation / animateFloatAsState | val holeBottom by animateFloatAsState(target.bottom, holeSpec, label = "tourHoleBottom") |
| 148 | animation / animateFloatAsState | val ringAlpha by animateFloatAsState(if (hole != null) 1f else 0f, holeSpec, label = "tourRingAlpha") |
| 149 | animation / animateFloatAsState | val scrimAlpha by animateFloatAsState(if (step.interactive) 0f else 1f, holeSpec, label = "tourScrimAlpha") |
| 166 | animation / tween | val cardSpec = tween<Float>(durationMillis = if (cardPlaced) 220 else 0) |
| 167 | animation / animateFloatAsState | val cardX by animateFloatAsState(cardSlot.x, cardSpec, label = "tourCardX") |
| 168 | animation / animateFloatAsState | val cardY by animateFloatAsState(cardSlot.y, cardSpec, label = "tourCardY") |
| 223 | peripheral-ui / HudTourStep.entries | for (entry in HudTourStep.entries) { |
| 265 | composable / TourDragHint | @Composable |
| 266 | page-or-page-helper / TourDragHint | private fun TourDragHint(from: Rect, to: Rect) { |
| 267 | animation / rememberInfiniteTransition | val transition = rememberInfiniteTransition(label = "tourDragHint") |
| 268 | animation / animateFloat | val progress by transition.animateFloat( |
| 272 | animation / keyframes | keyframes { |
| 302 | composable / TourButton | @Composable |
| 303 | page-or-page-helper / TourButton | private fun TourButton(text: String, primary: Boolean, enabled: Boolean = true, onClick: () -> Unit) { |
| 308 | animation / animateColorAsState | val background by animateColorAsState( |
| 311 | animation / animateFloatAsState | val alpha by animateFloatAsState(if (enabled) 1f else 0.4f, label = "tourButtonAlpha") |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/sections/Designer.kt

`file-c420f7ec373976b23772`；状态 **pending**；领域 hud-editor；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 核对打开/取消/确认、焦点、边界定位、外点关闭与提交后可见结果。 定位类型: dialog-or-popup 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: page-or-page-helper 从实际宿主入口触发，核对完整状态变化、交互、退出及重进后的结果。 定位类型: peripheral-ui

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 85 | composable / Designer | @Composable |
| 86 | page-or-page-helper / Designer | fun Designer(hud: Hud?, query: String) { |
| 172 | dialog-or-popup / AlignmentPicker | AlignmentPicker(if (staticWidth) alignment else growthAlign) { |
| 179 | peripheral-ui / setGrowthAnchorKeepingPosition | hud.setGrowthAnchorKeepingPosition(growthAnchor) |
| 368 | page-or-page-helper / hudSearchMatches | internal fun hudSearchMatches(query: String, vararg labels: String) = |
| 372 | composable / Section | @Composable |
| 373 | composable / @Composable callback/type | fun Section(title: String, query: String, vararg labels: String, content: @Composable () -> Unit) { |
| 373 | page-or-page-helper / Section | fun Section(title: String, query: String, vararg labels: String, content: @Composable () -> Unit) { |
| 381 | composable / ColorButton | @Composable |
| 382 | page-or-page-helper / ColorButton | internal fun ColorButton( |
| 428 | dialog-or-popup / ShellPopup | ShellPopup( |
| 434 | dialog-or-popup / ColorPickerPopup | ColorPickerPopup( |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/hud/screens/sections/Settings.kt

`file-81cd6de291af9224453b`；状态 **pending**；领域 hud-editor；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: page-or-page-helper

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 49 | composable / Settings | @Composable |
| 50 | page-or-page-helper / Settings | fun Settings(hud: Hud?, query: String, onDeleted: () -> Unit) { |
| 349 | composable / ResetHudPositionButton | @Composable |
| 350 | page-or-page-helper / ResetHudPositionButton | private fun ResetHudPositionButton(hud: Hud) { |
| 355 | animation / animateFloatAsState | val disabledAlpha by animateFloatAsState(if (enabled) 0f else 1f) |
| 356 | animation / animateFloatAsState | val accentAlpha by animateFloatAsState( |
| 384 | composable / DeleteHudButton | @Composable |
| 385 | page-or-page-helper / DeleteHudButton | private fun DeleteHudButton(hud: Hud, onDeleted: () -> Unit) { |
| 389 | animation / animateColorAsState | val bgColor by animateColorAsState( |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/keybind/KeybindCatalog.kt

`file-c8069d962ede0e394147`；状态 **pending**；领域 keybind；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 29 | ui-function / groups | fun interface KeybindGroupProvider { |
| 30 | ui-function / groups | fun groups(): List<KeybindGroup> |
| 38 | ui-function / register | fun register(provider: KeybindGroupProvider) { |
| 41 | search-provider / SearchCorpus.invalidate | SearchCorpus.invalidate(KeybindDocumentSource) |
| 45 | ui-function / groups | fun groups(): List<KeybindGroup> = providers.flatMap { it.groups() } |
| 48 | ui-function / collectAllKeybindGroups | fun collectAllKeybindGroups(): List<KeybindGroup> { |
| 53 | ui-function / withUniqueModIds | private fun List<KeybindGroup>.withUniqueModIds(): List<KeybindGroup> { |
| 66 | ui-function / collectKeybindGroups | private fun collectKeybindGroups(configs: List<TreeConfigData>): List<KeybindGroup> { |
| 74 | ui-function / collectKeybindEntries | private fun collectKeybindEntries(tree: Tree): List<KeybindEntry> { |
| 82 | ui-function / collectKeybindEntries | private fun collectKeybindEntries(node: Node, path: String, entries: MutableList<KeybindEntry>) { |
| 100 | ui-function / isKeybindProperty | fun Property<*>.isKeybindProperty(): Boolean { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/keybind/KeybindGroupCollapseStore.kt

`file-91dbe66ea67913b2dd6e`；状态 **pending**；领域 keybind；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 20 | ui-function / file | private fun file(): Path = ConfigManager.internal().folder.resolve(FILE_NAME) |
| 22 | ui-function / defaultCollapsed | private fun defaultCollapsed(modId: String): Boolean = modId.startsWith(MINECRAFT_PREFIX) |
| 24 | ui-function / ensureLoaded | private fun ensureLoaded() { |
| 44 | ui-function / isCollapsed | fun isCollapsed(modId: String): Boolean { |
| 49 | ui-function / setCollapsed | fun setCollapsed(modId: String, value: Boolean) { |
| 55 | ui-function / persist | private fun persist() { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/keybind/KeybindRecordingBus.kt

`file-e57251706c60e847a55b`；状态 **pending**；领域 keybind；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 5 | keybind / KeybindRecordingBus | object KeybindRecordingBus { |
| 12 | ui-function / setEscapeHandler | fun setEscapeHandler(handler: () -> Unit) { |
| 16 | ui-function / clearEscapeHandler | fun clearEscapeHandler(handler: () -> Unit) { |
| 20 | ui-function / setMouseHandler | fun setMouseHandler(handler: (Int, Boolean) -> Unit) { |
| 24 | ui-function / clearMouseHandler | fun clearMouseHandler(handler: (Int, Boolean) -> Unit) { |
| 29 | keybind / KeybindManager.isInputCaptured | val isRecording get() = escapeHandler != null \|\| KeybindManager.isInputCaptured() |
| 32 | ui-function / consumeEscape | fun consumeEscape(): Boolean { |
| 39 | ui-function / consumeMouse | fun consumeMouse(button: Int, pressed: Boolean): Boolean { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/keybind/MinecraftKeybindRegistrar.kt

`file-9cf77ef2e67016385127`；状态 **pending**；领域 keybind；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 20 | keybind / OneConfigKeybind | private val propByBind = WeakHashMap<OneConfigKeybind, Property<*>>() |
| 28 | ui-function / scan | fun scan(tree: Tree, force: Boolean = true) { |
| 36 | ui-function / installRebindListener | private fun installRebindListener() { |
| 39 | keybind / KeybindManager.addRebindListener | KeybindManager.addRebindListener { old, new -> |
| 45 | keybind / KeybindManager.addMenuEditListener | KeybindManager.addMenuEditListener { bind -> |
| 52 | ui-function / walk | private fun walk(tree: Tree, category: String) { |
| 61 | ui-function / wire | private fun wire(prop: Property<*>, category: String) { |
| 63 | keybind / OneConfigKeybind | val bind = prop.get() as? OneConfigKeybind ?: return |
| 66 | keybind / OneConfigKeybind | bind.defaultKeybind = prop.getMetadata<Any?>("default") as? OneConfigKeybind |
| 67 | keybind / KeybindManager.isRegistered | if (KeybindManager.isRegistered(bind)) KeybindManager.refreshMinecraftBinding(bind) |
| 67 | keybind / KeybindManager.refreshMinecraftBinding | if (KeybindManager.isRegistered(bind)) KeybindManager.refreshMinecraftBinding(bind) |
| 68 | keybind / KeybindManager.register | else KeybindManager.register(bind) |
| 73 | keybind / OneConfigKeybind | (value as? OneConfigKeybind)?.let { propByBind[it] = prop } |
| 79 | ui-function / resolveName | private fun resolveName(prop: Property<*>): String = |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/keybind/ProfileKeybinds.kt

`file-d722dcda6ca2c8d8a5a2`；状态 **pending**；领域 keybind；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 23 | keybind / OneConfigKeybind | private val props = LinkedHashMap<String, Property<OneConfigKeybind>>() |
| 28 | ui-function / onProfileChanged | override fun onProfileChanged(newProfile: String) {} |
| 30 | ui-function / onProfileRenamed | override fun onProfileRenamed(oldProfile: String, newProfile: String) = rename(oldProfile, newProfile) |
| 32 | ui-function / onProfileDeleted | override fun onProfileDeleted(profile: String) = remove(profile) |
| 35 | ui-function / file | private fun file(): Path = ConfigManager.internal().folder.resolve(FILE_NAME) |
| 39 | ui-function / init | fun init() { |
| 62 | keybind / OneConfigKeybind | fun property(profile: String): Property<OneConfigKeybind> { |
| 62 | ui-function / property | fun property(profile: String): Property<OneConfigKeybind> { |
| 67 | ui-function / parseCodes | private fun parseCodes(raw: String): IntArray? = |
| 70 | keybind / OneConfigKeybind | private fun create(profile: String, keys: IntArray?, mouse: IntArray?, mods: Byte): Property<OneConfigKeybind> { |
| 70 | ui-function / create | private fun create(profile: String, keys: IntArray?, mouse: IntArray?, mods: Byte): Property<OneConfigKeybind> { |
| 75 | keybind / KeybindManager.register | KeybindManager.register(bind) |
| 76 | keybind / OneConfigKeybind | val prop = Properties.simple<OneConfigKeybind>("profile:$profile", profile, null, bind, OneConfigKeybind::class.java) |
| 76 | keybind / OneConfigKeybind | val prop = Properties.simple<OneConfigKeybind>("profile:$profile", profile, null, bind, OneConfigKeybind::class.java) |
| 77 | keybind / OneConfigKeybind | prop.addCallback(Predicate<OneConfigKeybind> { |
| 85 | ui-function / rename | private fun rename(oldProfile: String, newProfile: String) { |
| 88 | keybind / KeybindManager.unregister | KeybindManager.unregister(bind) |
| 94 | ui-function / remove | private fun remove(profile: String) { |
| 96 | keybind / KeybindManager.unregister | old.get()?.let { KeybindManager.unregister(it) } |
| 101 | ui-function / persist | private fun persist() { |
| 122 | ui-function / switchTo | private fun switchTo(profile: String) { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/navigation/Navigation.kt

`file-819e768794320b7e4535`；状态 **pending**；领域 shell-navigation；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 10 | route / navigation | fun NavGraphBuilder.navigation() { |
| 10 | ui-function / navigation | fun NavGraphBuilder.navigation() { |
| 11 | route / modsGraph | modsGraph() |
| 12 | route / profilesGraph | profilesGraph() |
| 13 | route / themesGraph | themesGraph() |
| 14 | route / preferencesGraph | preferencesGraph() |
| 15 | route / creditsGraph | creditsGraph() |
| 16 | route / pageDestinations | pageDestinations() |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/navigation/Pages.kt

`file-69c354468f845365052f`；状态 **pending**；领域 shell-navigation；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 37 | route / PageRoute | data class PageRoute(val pageId: String) |
| 39 | route / BuiltinPage | internal class BuiltinPage( |
| 46 | composable / @Composable callback/type | private val content: @Composable () -> Unit, |
| 47 | route / OneConfigPage | ) : OneConfigPage( |
| 56 | composable / Content | @Composable |
| 57 | ui-function / Content | override fun Content() = content() |
| 63 | route / BuiltinPage | BuiltinPage("favorites", "常用", "star", PageGroup.FAVORITES, 10, PageRoute("oneconfig:favorites")) { Favorites() }, |
| 63 | route / PageRoute | BuiltinPage("favorites", "常用", "star", PageGroup.FAVORITES, 10, PageRoute("oneconfig:favorites")) { Favorites() }, |
| 64 | route / BuiltinPage | BuiltinPage("mods", "Mods", "settings", PageGroup.MODS_AND_OPTIONS, 100, ModsGraph) { Mods() }, |
| 65 | route / BuiltinPage | BuiltinPage("profiles", "Profiles", "profiles", PageGroup.MODS_AND_OPTIONS, 200, ProfilesGraph) { Profiles() }, |
| 66 | route / BuiltinPage | BuiltinPage("themes", "Themes", "paintbrush", PageGroup.PERSONALIZATION, 100, ThemesGraph) { Themes() }, |
| 67 | route / BuiltinPage | BuiltinPage("preferences", "Preferences", "cog", PageGroup.PERSONALIZATION, 200, PreferencesGraph) { Preferences() }, |
| 68 | route / BuiltinPage | BuiltinPage("credits", "Credits", "star", PageGroup.ONECONFIG, 200, CreditsGraph) { Credits() }, |
| 75 | ui-function / register | fun register() { |
| 98 | ui-function / registeredPages | fun registeredPages(): List<OneConfigPage> { |
| 103 | route / PageRoute | fun pageRoute(page: OneConfigPage): Any = (page as? BuiltinPage)?.route ?: PageRoute(page.id) |
| 103 | ui-function / pageRoute | fun pageRoute(page: OneConfigPage): Any = (page as? BuiltinPage)?.route ?: PageRoute(page.id) |
| 105 | route / resolveOpenTarget | fun resolveOpenTarget(target: Any?): Any? = when (target) { |
| 105 | ui-function / resolveOpenTarget | fun resolveOpenTarget(target: Any?): Any? = when (target) { |
| 124 | route / pageDestinations | fun NavGraphBuilder.pageDestinations() { |
| 124 | ui-function / pageDestinations | fun NavGraphBuilder.pageDestinations() { |
| 125 | route / composable | composable<PageRoute> { entry -> |
| 135 | route / PageRoute | shell.navigation.discard(PageRoute(id), ModsGraph) |
| 140 | ui-function / isOn | fun NavBackStackEntry?.isOn(route: Any): Boolean { |
| 149 | ui-function / currentPage | fun NavBackStackEntry?.currentPage(): OneConfigPage? { |
| 162 | ui-function / sidebarSections | fun sidebarSections(): List<SidebarSection> { |
| 178 | ui-function / currentPageTitle | fun NavBackStackEntry?.currentPageTitle(): String? = |
| 182 | ui-function / searchPlaceholder | fun searchPlaceholder(entry: NavBackStackEntry?): String = searchPlaceholder(entry.currentPage(), entry.currentPageTitle()) |
| 184 | ui-function / searchPlaceholder | fun searchPlaceholder(page: OneConfigPage?, title: String?): String { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/navigation/Routes.kt

`file-c9b8215f4dc6e6ad3e1c`；状态 **pending**；领域 shell-navigation；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 3 | route / NavigationRoute | data class NavigationRoute( |
| 21 | route / NavigationRoute | *pages.map { NavigationRoute(it.id.substringAfter(':'), it.icon, pageRoute(it)) }.toTypedArray(), |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/navigation/graph/Credits.kt

`file-9155283e061e8d0ad927`；状态 **pending**；领域 shell-navigation；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 10 | route / CreditsGraph | data object CreditsGraph |
| 13 | route / CreditsRoute | data object CreditsRoute |
| 15 | route / creditsGraph | fun NavGraphBuilder.creditsGraph() { |
| 15 | ui-function / creditsGraph | fun NavGraphBuilder.creditsGraph() { |
| 16 | route / navigation | navigation<CreditsGraph>(startDestination = CreditsRoute) { |
| 17 | route / composable | composable<CreditsRoute> { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/navigation/graph/Mods.kt

`file-2616a6449a0c5b69ce3b`；状态 **pending**；领域 shell-navigation；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 12 | route / ModsGraph | data object ModsGraph |
| 15 | route / ModsRoute | data object ModsRoute |
| 18 | route / ModConfigRoute | data class ModConfigRoute(val id: String, val category: String? = null) |
| 20 | route / modsGraph | fun NavGraphBuilder.modsGraph() { |
| 20 | ui-function / modsGraph | fun NavGraphBuilder.modsGraph() { |
| 21 | route / navigation | navigation<ModsGraph>(startDestination = ModsRoute) { |
| 22 | route / composable | composable<ModsRoute> { |
| 25 | route / composable | composable<ModConfigRoute> { backStackEntry -> |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/navigation/graph/Preferences.kt

`file-16b4aae53866a68d005d`；状态 **pending**；领域 shell-navigation；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 10 | route / PreferencesGraph | data object PreferencesGraph |
| 13 | route / PreferencesRoute | data object PreferencesRoute |
| 15 | route / preferencesGraph | fun NavGraphBuilder.preferencesGraph() { |
| 15 | ui-function / preferencesGraph | fun NavGraphBuilder.preferencesGraph() { |
| 16 | route / navigation | navigation<PreferencesGraph>(startDestination = PreferencesRoute) { |
| 17 | route / composable | composable<PreferencesRoute> { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/navigation/graph/Profiles.kt

`file-a231c85d2fc61b835709`；状态 **pending**；领域 profiles；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 10 | route / ProfilesGraph | data object ProfilesGraph |
| 13 | route / ProfilesRoute | data object ProfilesRoute |
| 15 | route / profilesGraph | fun NavGraphBuilder.profilesGraph() { |
| 15 | ui-function / profilesGraph | fun NavGraphBuilder.profilesGraph() { |
| 16 | route / navigation | navigation<ProfilesGraph>(startDestination = ProfilesRoute) { |
| 17 | route / composable | composable<ProfilesRoute> { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/navigation/graph/Themes.kt

`file-dc877da9d7c869f5e56f`；状态 **pending**；领域 theme；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 10 | route / ThemesGraph | data object ThemesGraph |
| 13 | route / ThemesRoute | data object ThemesRoute |
| 15 | route / themesGraph | fun NavGraphBuilder.themesGraph() { |
| 15 | ui-function / themesGraph | fun NavGraphBuilder.themesGraph() { |
| 16 | route / navigation | navigation<ThemesGraph>(startDestination = ThemesRoute) { |
| 17 | route / composable | composable<ThemesRoute> { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/ConfigScreen.kt

`file-04f161539dad15f0bc80`；状态 **pending**；领域 settings；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 核对菜单位置、层级、hover、键鼠选择、点击外部/Esc 关闭和每个命令的结果。 定位类型: menu 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: page-or-page-helper 注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 116 | composable / ConfigScreen | @Composable |
| 117 | page-or-page-helper / ConfigScreen | fun ConfigScreen( |
| 129 | composable / ConfigScreenContent | @Composable |
| 130 | page-or-page-helper / ConfigScreenContent | private fun ConfigScreenContent( |
| 145 | page-or-page-helper / selectCategory | fun selectCategory(category: CategoryGroup) { |
| 209 | page-or-page-helper / categoryProperties | private fun categoryProperties(categories: List<CategoryGroup>): List<Property<*>> { |
| 226 | composable / rememberDisplayRevision | @Composable |
| 227 | page-or-page-helper / rememberDisplayRevision | private fun rememberDisplayRevision(categories: List<CategoryGroup>): Int { |
| 243 | composable / rememberSearchResults | @Composable |
| 244 | page-or-page-helper / rememberSearchResults | private fun rememberSearchResults( |
| 253 | search-provider / SearchCorpus.searchGrouped | SearchCorpus.searchGrouped(query, setOf(SearchScope.Config(pageKey))) { document -> |
| 253 | search-provider / SearchScope.Config | SearchCorpus.searchGrouped(query, setOf(SearchScope.Config(pageKey))) { document -> |
| 263 | page-or-page-helper / searchCategories | private fun searchCategories(grouped: Map<SearchRow?, List<SearchDocument<*>>>): List<CategoryGroup> { |
| 275 | page-or-page-helper / filterCategories | private fun filterCategories(categories: List<CategoryGroup>, query: String): List<CategoryGroup> { |
| 290 | page-or-page-helper / filterSettingNode | private fun filterSettingNode( |
| 310 | page-or-page-helper / matchesLocalSearch | private fun Property<*>.matchesLocalSearch(category: String, subcategory: String, query: String): Boolean { |
| 315 | page-or-page-helper / matchesLocalSearch | private fun Tree.matchesLocalSearch(category: String, subcategory: String, query: String): Boolean { |
| 321 | page-or-page-helper / sliderTrailingWidth | private fun sliderTrailingWidth(prop: Property<*>): Dp? { |
| 331 | page-or-page-helper / isFullWidth | private fun isFullWidth(prop: Property<*>): Boolean = prop.getMetadata<Boolean>("fullWidth") == true |
| 333 | page-or-page-helper / isWideControl | private fun isWideControl(prop: Property<*>): Boolean = sliderTrailingWidth(prop) != null \|\| isFullWidth(prop) |
| 340 | composable / ConfigListRow | @Composable |
| 341 | page-or-page-helper / ConfigListRow | internal fun ConfigListRow(entry: ConfigListEntry, compact: (SettingNode) -> Boolean = { false }) { |
| 349 | composable / SettingEntryRow | @Composable |
| 350 | page-or-page-helper / SettingEntryRow | internal fun SettingEntryRow(node: SettingNode, compact: Boolean = false) { |
| 359 | composable / CategoryHeader | @Composable |
| 360 | page-or-page-helper / CategoryHeader | private fun CategoryHeader(title: String) { |
| 370 | composable / SubcategoryHeader | @Composable |
| 371 | page-or-page-helper / SubcategoryHeader | private fun SubcategoryHeader(title: String) { |
| 381 | composable / AccordionRow | @Composable |
| 382 | page-or-page-helper / AccordionRow | private fun AccordionRow(node: SettingNode.Accordion, compact: Boolean = false) { |
| 393 | animation / animateFloatAsState | val chevronRotation by animateFloatAsState(if (expanded) 180f else 90f) |
| 471 | animation / AnimatedVisibility | AnimatedVisibility( |
| 494 | composable / SettingRow | @Composable |
| 495 | page-or-page-helper / SettingRow | fun SettingRow(prop: Property<*>, compact: Boolean = false) { |
| 499 | composable / DecoratedSettingRow | @Composable |
| 500 | page-or-page-helper / DecoratedSettingRow | private fun DecoratedSettingRow(decorators: List<SettingRowDecorator>, index: Int, prop: Property<*>, compact: Boolean) { |
| 505 | composable / SettingRowContent | @Composable |
| 506 | page-or-page-helper / SettingRowContent | private fun SettingRowContent(prop: Property<*>, compact: Boolean) { |
| 541 | composable / AccordionSettingRow | @Composable |
| 542 | page-or-page-helper / AccordionSettingRow | private fun AccordionSettingRow(prop: Property<*>, display: Property.Display, modifier: Modifier, compact: Boolean = false) { |
| 548 | composable / rememberVisibleOptions | @Composable |
| 549 | page-or-page-helper / rememberVisibleOptions | private fun rememberVisibleOptions(body: List<Property<*>>): List<Pair<Property<*>, Property.Display>> { |
| 567 | page-or-page-helper / optionReservedWidth | private fun optionReservedWidth(prop: Property<*>, optionWidth: Dp): Dp { |
| 577 | page-or-page-helper / buildOptionRows | private inline fun buildOptionRows(body: List<Property<*>>, isWide: (Property<*>) -> Boolean): List<OptionRow> { |
| 595 | composable / AccordionOptionsGrid | @Composable |
| 596 | page-or-page-helper / AccordionOptionsGrid | private fun AccordionOptionsGrid(body: List<Property<*>>, compact: Boolean) { |
| 655 | composable / rememberDisplay | @Composable |
| 656 | page-or-page-helper / rememberDisplay | internal fun rememberDisplay(prop: Property<*>): Property.Display { |
| 670 | page-or-page-helper / displayAlpha | internal fun displayAlpha(display: Property.Display): Float { |
| 674 | composable / SettingContent | @Composable |
| 675 | page-or-page-helper / SettingContent | private fun SettingContent(prop: Property<*>, nested: Boolean = false, compact: Boolean = false, enabled: Boolean = true) { |
| 695 | page-or-page-helper / openMenuFromActionButton | fun openMenuFromActionButton() { |
| 717 | keybind / KeybindRecordingBus.isRecording | if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed && !KeybindRecordingBus.isRecording) { |
| 727 | composable / @Composable callback/type | val actionButton = @Composable { |
| 800 | menu / OptionContextMenu | OptionContextMenu(prop, menuOpen, menuOffset, onDismiss = { menuOpen = false }) |
| 804 | composable / SettingLabel | @Composable |
| 805 | page-or-page-helper / SettingLabel | private fun SettingLabel( |
| 820 | composable / @Composable callback/type | val title = @Composable { |
| 851 | composable / HudConfigScreen | @Composable |
| 852 | page-or-page-helper / HudConfigScreen | fun HudConfigScreen(tree: Tree, searchQuery: String) { |
| 856 | composable / HudConfigScreenContent | @Composable |
| 857 | page-or-page-helper / HudConfigScreenContent | private fun HudConfigScreenContent(tree: Tree, searchQuery: String) { |
| 904 | page-or-page-helper / isHudInternal | private fun isHudInternal(node: Node): Boolean { |
| 909 | page-or-page-helper / hasWideControl | private fun hasWideControl(node: SettingNode): Boolean = when (node) { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/Credits.kt

`file-ec393b528744525e8dee`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: page-or-page-helper

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 38 | page-or-page-helper / modVersion | private fun modVersion(modId: String): String? = runCatching { |
| 57 | page-or-page-helper / creditSections | private fun creditSections(configVersion: String?) = listOf( |
| 88 | composable / Credits | @Composable |
| 89 | page-or-page-helper / Credits | fun Credits() { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/Favorites.kt

`file-33b6891ef881782b75d3`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: page-or-page-helper

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 18 | composable / Favorites | @Composable |
| 19 | page-or-page-helper / Favorites | fun Favorites() { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/ModConfig.kt

`file-eac436e7bfd4f71b1054`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: page-or-page-helper

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 22 | composable / ModConfig | @Composable |
| 23 | page-or-page-helper / ModConfig | fun ModConfig(id: String, initialCategory: String? = null) { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/ModSortControl.kt

`file-c73d7490c7363f1cf65e`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 核对打开/取消/确认、焦点、边界定位、外点关闭与提交后可见结果。 定位类型: dialog-or-popup 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: page-or-page-helper

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 44 | composable / ModSortControl | @Composable |
| 45 | page-or-page-helper / ModSortControl | fun ModSortControl(modifier: Modifier = Modifier) { |
| 51 | page-or-page-helper / apply | fun apply(mode: ModSortMode) { |
| 72 | dialog-or-popup / ShellPopup | ShellPopup( |
| 100 | composable / SortSegment | @Composable |
| 101 | page-or-page-helper / SortSegment | private fun SortSegment(mode: ModSortMode, selected: Boolean, onSwitch: () -> Unit) { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/Mods.kt

`file-57fbbd911f38126648c0`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: page-or-page-helper

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 134 | composable / Mods | @Composable |
| 135 | page-or-page-helper / Mods | fun Mods() { |
| 142 | composable / DecoratedModsPage | @Composable |
| 143 | page-or-page-helper / DecoratedModsPage | private fun DecoratedModsPage(decorators: List<ModsPageDecorator>, index: Int) { |
| 148 | composable / ModsPage | @Composable |
| 149 | page-or-page-helper / ModsPage | private fun ModsPage() { |
| 186 | composable / ModsGrid | @Composable |
| 187 | page-or-page-helper / ModsGrid | fun ColumnScope.ModsGrid(category: ModCategory) { |
| 233 | animation / animateItems | var animateItems by remember(windowSize, density) { mutableStateOf(false) } |
| 291 | animation / animateItems | animateItems = true |
| 303 | animation / animateItems | modifier = if (animateItems) Modifier.animateItem(placementSpec = ModCardPlacementSpec) else Modifier, |
| 303 | animation / animateItem | modifier = if (animateItems) Modifier.animateItem(placementSpec = ModCardPlacementSpec) else Modifier, |
| 310 | animation / animateFloatAsState | val outlineAlpha = animateFloatAsState(if (dragging) 1f else 0f) |
| 316 | animation / animateItems | if (animateItems) { |
| 317 | animation / animateItem | Modifier.animateItem( |
| 346 | composable / ModTypeHeader | @Composable |
| 347 | page-or-page-helper / ModTypeHeader | private fun ModTypeHeader(entry: ModGridEntry.Header, modifier: Modifier = Modifier, onToggle: () -> Unit) { |
| 350 | animation / animateFloatAsState | val chevronRotation by animateFloatAsState(if (entry.expanded) 180f else 90f) |
| 382 | composable / Rule | @Composable |
| 383 | page-or-page-helper / Rule | private fun Rule(modifier: Modifier) { |
| 387 | animation / spring | private val ModCardPlacementSpec = spring( |
| 393 | page-or-page-helper / cardData | private fun List<ModGridEntry>.cardData(key: Any): ConfigData? = |
| 396 | page-or-page-helper / openModCard | private fun openModCard(mod: ConfigData, navigation: ShellNavigation) { |
| 405 | page-or-page-helper / commitDrop | private fun commitDrop(entries: List<ModGridEntry>, index: Int) { |
| 419 | composable / dropSlotOutline | @Composable |
| 420 | page-or-page-helper / dropSlotOutline | private fun Modifier.dropSlotOutline(favorite: Boolean, alpha: () -> Float): Modifier { |
| 458 | composable / ModCard | @Composable |
| 459 | page-or-page-helper / ModCard | fun ModCard( |
| 473 | animation / animateFloatAsState | val footerAccent by animateFloatAsState(if (enabled) 1f else 0f) |
| 474 | animation / animateColorAsState | val footerTextColor by animateColorAsState( |
| 477 | animation / animateFloatAsState | val glowStrength by animateFloatAsState(if (enabled) 0.5f else 0f) |
| 670 | composable / FavoriteStar | @Composable |
| 671 | page-or-page-helper / FavoriteStar | private fun FavoriteStar( |
| 683 | animation / animateFloatAsState | val alpha = animateFloatAsState( |
| 692 | animation / animateColorAsState | val color by animateColorAsState(if (favorite) theme.favoriteColor else theme.textColor) |
| 714 | composable / HideToggle | @Composable |
| 715 | page-or-page-helper / HideToggle | private fun HideToggle( |
| 724 | animation / animateFloatAsState | val alpha = animateFloatAsState( |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/Preferences.kt

`file-3b668716146e7a47706b`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: page-or-page-helper

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 16 | composable / Preferences | @Composable |
| 17 | page-or-page-helper / Preferences | fun Preferences() { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/Profiles.kt

`file-2202b326948a530d8dfe`；状态 **pending**；领域 profiles；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 核对打开/取消/确认、焦点、边界定位、外点关闭与提交后可见结果。 定位类型: dialog-or-popup 实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 核对菜单位置、层级、hover、键鼠选择、点击外部/Esc 关闭和每个命令的结果。 定位类型: menu 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: page-or-page-helper

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 145 | composable / Profiles | @Composable |
| 146 | page-or-page-helper / Profiles | fun Profiles() { |
| 169 | page-or-page-helper / runProfileAction | fun runProfileAction( |
| 229 | dialog-or-popup / runProfileDialog | fun <T : Any> runProfileDialog(prompt: () -> T?, action: (T) -> Unit) { |
| 229 | page-or-page-helper / runProfileDialog | fun <T : Any> runProfileDialog(prompt: () -> T?, action: (T) -> Unit) { |
| 367 | dialog-or-popup / runProfileDialog | runProfileDialog( |
| 398 | page-or-page-helper / awaitOverlayDrawn | private suspend fun awaitOverlayDrawn() { |
| 400 | animation / withFrameNanos | withFrameNanos { } |
| 401 | animation / withFrameNanos | withFrameNanos { } |
| 405 | composable / ProfileBusyOverlay | @Composable |
| 406 | page-or-page-helper / ProfileBusyOverlay | private fun ProfileBusyOverlay() { |
| 408 | dialog-or-popup / ShellPopup | ShellPopup( |
| 440 | page-or-page-helper / calculatePosition | override fun calculatePosition( |
| 448 | page-or-page-helper / messageOrType | private fun Throwable.messageOrType(): String = message ?: this::class.java.simpleName |
| 450 | page-or-page-helper / matchesSearch | private fun UiProfile.matchesSearch(query: String): Boolean { |
| 456 | page-or-page-helper / loadProfiles | private fun loadProfiles(): List<UiProfile> { |
| 471 | composable / ProfilesGrid | @Composable |
| 472 | page-or-page-helper / ProfilesGrid | private fun ColumnScope.ProfilesGrid( |
| 590 | page-or-page-helper / nextCloneName | private fun nextCloneName(profileName: String, profileIds: Set<String>): String { |
| 598 | composable / CreateProfileCard | @Composable |
| 599 | page-or-page-helper / CreateProfileCard | private fun CreateProfileCard( |
| 617 | animation / animateColorAsState | val borderColor by animateColorAsState( |
| 620 | animation / animateColorAsState | val iconBackground by animateColorAsState( |
| 623 | animation / animateColorAsState | val iconColor by animateColorAsState( |
| 721 | composable / ProfileCard | @Composable |
| 722 | page-or-page-helper / ProfileCard | private fun ProfileCard( |
| 746 | page-or-page-helper / closeEditor | fun closeEditor() { |
| 752 | page-or-page-helper / submitName | fun submitName() { |
| 767 | animation / animateColorAsState | val selectionBorderColor by animateColorAsState( |
| 838 | menu / ProfileActionsMenu | ProfileActionsMenu( |
| 885 | dialog-or-popup / ProfileIconPicker | ProfileEditor.Icon -> ProfileIconPicker( |
| 928 | keybind / KeybindOption | KeybindOption(KeybindOptionData(keybindProp)) |
| 934 | composable / ProfileActionsMenu | @Composable |
| 935 | menu / ProfileActionsMenu | private fun ProfileActionsMenu( |
| 935 | page-or-page-helper / ProfileActionsMenu | private fun ProfileActionsMenu( |
| 949 | dialog-or-popup / ShellPopup | ShellPopup( |
| 999 | composable / ProfileMenuItem | @Composable |
| 1000 | page-or-page-helper / ProfileMenuItem | private fun ProfileMenuItem( |
| 1013 | animation / animateColorAsState | val hoverBackground by animateColorAsState( |
| 1015 | animation / tween | animationSpec = tween(120), |
| 1039 | composable / ProfileIconPicker | @Composable |
| 1040 | dialog-or-popup / ProfileIconPicker | private fun ProfileIconPicker( |
| 1040 | page-or-page-helper / ProfileIconPicker | private fun ProfileIconPicker( |
| 1064 | composable / ProfileIconChoice | @Composable |
| 1065 | page-or-page-helper / ProfileIconChoice | private fun ProfileIconChoice( |
| 1074 | animation / animateColorAsState | val backgroundColor by animateColorAsState( |
| 1079 | animation / animateColorAsState | val borderColor by animateColorAsState( |
| 1082 | animation / animateColorAsState | val iconColor by animateColorAsState( |
| 1103 | composable / ActionIcon | @Composable |
| 1104 | page-or-page-helper / ActionIcon | private fun ActionIcon( |
| 1113 | animation / animateColorAsState | val iconColor by animateColorAsState( |
| 1132 | composable / ProfileTextField | @Composable |
| 1133 | page-or-page-helper / ProfileTextField | private fun ProfileTextField( |
| 1147 | animation / animateColorAsState | val borderColor by animateColorAsState( |
| 1163 | keybind / keyboardActions | keyboardActions = KeyboardActions(onDone = { if (enabled) onSubmit() }), |
| 1174 | keybind / onPreviewKeyEvent | .onPreviewKeyEvent { event -> |
| 1176 | keybind / Key.Enter | (event.key == Key.Enter \|\| event.key == Key.NumPadEnter) |
| 1176 | keybind / Key.NumPadEnter | (event.key == Key.Enter \|\| event.key == Key.NumPadEnter) |
| 1208 | composable / ProfileError | @Composable |
| 1209 | page-or-page-helper / ProfileError | private fun ProfileError(message: String, width: Dp) { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/SearchResultsScreen.kt

`file-d7c577748d4568392f17`；状态 **pending**；领域 search；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: page-or-page-helper 注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 48 | composable / SearchResultsScreen | @Composable |
| 49 | page-or-page-helper / SearchResultsScreen | fun SearchResultsScreen(query: String) { |
| 56 | search-provider / SearchCorpus.searchGrouped | SearchCorpus.searchGrouped(query, setOf(SearchScope.Mods, SearchScope.Options), GlobalSettingIndex::rowOf) |
| 56 | search-provider / SearchScope.Mods | SearchCorpus.searchGrouped(query, setOf(SearchScope.Mods, SearchScope.Options), GlobalSettingIndex::rowOf) |
| 56 | search-provider / SearchScope.Options | SearchCorpus.searchGrouped(query, setOf(SearchScope.Mods, SearchScope.Options), GlobalSettingIndex::rowOf) |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/Themes.kt

`file-ebf7b4f5e4d1c5279fb3`；状态 **pending**；领域 theme；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: page-or-page-helper

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 43 | composable / Themes | @Composable |
| 44 | page-or-page-helper / Themes | fun Themes() { |
| 73 | composable / ThemeCard | @Composable |
| 74 | page-or-page-helper / ThemeCard | private fun ThemeCard(theme: UITheme) { |
| 78 | animation / animateColorAsState | val borderColor by animateColorAsState( |
| 81 | animation / animateDpAsState | val borderWidth by animateDpAsState( |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/presets/PresetTemplatesPanel.kt

`file-d3b01cbddd41bb4cba0a`；状态 **pending**；领域 presets；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: page-or-page-helper

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 121 | page-or-page-helper / attach | fun attach(scope: CoroutineScope) { |
| 126 | page-or-page-helper / onPageOpened | fun onPageOpened(initialMode: String?) { |
| 137 | page-or-page-helper / awaitPanelDrawn | private suspend fun awaitPanelDrawn() { |
| 139 | animation / withFrameNanos | withFrameNanos { } |
| 140 | animation / withFrameNanos | withFrameNanos { } |
| 145 | page-or-page-helper / callBridge | private fun callBridge(requestJson: String): String { |
| 158 | page-or-page-helper / runCall | private fun runCall( |
| 189 | page-or-page-helper / loadCatalog | fun loadCatalog() { |
| 211 | page-or-page-helper / closeFocus | fun closeFocus() { |
| 219 | page-or-page-helper / enterDetail | fun enterDetail(card: PresetCard) { |
| 228 | page-or-page-helper / enterSave | fun enterSave() { |
| 235 | page-or-page-helper / enterImport | fun enterImport() { |
| 239 | page-or-page-helper / refreshPreview | private fun refreshPreview() { |
| 243 | page-or-page-helper / refreshPreviewFor | fun refreshPreviewFor(card: PresetCard) { |
| 256 | page-or-page-helper / applySelection | fun applySelection(card: PresetCard) { |
| 284 | page-or-page-helper / toggleFacet | fun toggleFacet(facetId: String) { |
| 291 | page-or-page-helper / toggleSaveFacet | fun toggleSaveFacet(facetId: String) { |
| 296 | page-or-page-helper / selectSaveLook | fun selectSaveLook() { |
| 302 | page-or-page-helper / selectSaveAudio | fun selectSaveAudio() { |
| 308 | page-or-page-helper / runUndo | fun runUndo() { |
| 326 | page-or-page-helper / copyShareCode | fun copyShareCode(card: PresetCard) { |
| 344 | page-or-page-helper / deleteConfirmed | fun deleteConfirmed(card: PresetCard) { |
| 362 | page-or-page-helper / saveCurrent | fun saveCurrent(alsoCopy: Boolean) { |
| 413 | page-or-page-helper / importTextContent | fun importTextContent(text: String) { |
| 434 | page-or-page-helper / importFromPickedFile | fun importFromPickedFile(picked: Path) { |
| 456 | page-or-page-helper / pickAndImportFile | fun pickAndImportFile() { |
| 473 | page-or-page-helper / openPresetsFolder | fun openPresetsFolder() { |
| 492 | composable / PresetTemplatesSection | @Composable |
| 493 | page-or-page-helper / PresetTemplatesSection | fun ColumnScope.PresetTemplatesSection(state: PresetTemplatesState) { |
| 585 | composable / SectionHint | @Composable |
| 586 | page-or-page-helper / SectionHint | private fun SectionHint(text: String) { |
| 595 | page-or-page-helper / filterMatches | private fun List<PresetCard>.filterMatches(query: String): List<PresetCard> { |
| 603 | composable / TemplateCard | @Composable |
| 604 | page-or-page-helper / TemplateCard | private fun TemplateCard(state: PresetTemplatesState, card: PresetCard) { |
| 609 | animation / animateColorAsState | val borderColor by animateColorAsState(if (isHovered) Accent else theme.borderColor) |
| 700 | page-or-page-helper / facetSummaryFor | private fun facetSummaryFor(card: PresetCard, catalog: CatalogData?): String { |
| 715 | composable / ActionIconButton | @Composable |
| 716 | page-or-page-helper / ActionIconButton | private fun ActionIconButton( |
| 725 | animation / animateColorAsState | val color by animateColorAsState(if (enabled && isHovered) hoveredTint else tint) |
| 740 | composable / PresetTemplatesFocusView | @Composable |
| 741 | page-or-page-helper / PresetTemplatesFocusView | fun ColumnScope.PresetTemplatesFocusView(state: PresetTemplatesState) { |
| 757 | composable / TemplatesDetailView | @Composable |
| 758 | page-or-page-helper / TemplatesDetailView | private fun ColumnScope.TemplatesDetailView(state: PresetTemplatesState, card: PresetCard) { |
| 875 | composable / TemplatesSaveView | @Composable |
| 876 | page-or-page-helper / TemplatesSaveView | private fun ColumnScope.TemplatesSaveView(state: PresetTemplatesState) { |
| 970 | composable / TemplatesImportView | @Composable |
| 971 | page-or-page-helper / TemplatesImportView | private fun ColumnScope.TemplatesImportView(state: PresetTemplatesState) { |
| 1030 | composable / SectionLabel | @Composable |
| 1031 | page-or-page-helper / SectionLabel | private fun SectionLabel(text: String) { |
| 1040 | page-or-page-helper / facetSections | private fun facetSections( |
| 1045 | page-or-page-helper / groupFacets | private fun groupFacets(facets: List<PresetFacet>): List<Pair<String, List<PresetFacet>>> { |
| 1054 | composable / FacetRow | @Composable |
| 1055 | page-or-page-helper / FacetRow | private fun FacetRow( |
| 1084 | composable / PresetTextField | @Composable |
| 1085 | page-or-page-helper / PresetTextField | private fun PresetTextField( |
| 1096 | animation / animateColorAsState | val borderColor by animateColorAsState(if (isFocused) Accent else theme.borderColor) |
| 1104 | keybind / keyboardActions | keyboardActions = KeyboardActions(onDone = { if (enabled) onSubmit() }), |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/search/ConfigDocuments.kt

`file-f681bfc35cf97df2b785`；状态 **pending**；领域 search；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 30 | ui-function / documents | override fun documents(): List<SearchDocument<*>> { |
| 39 | search-provider / SearchScope | val scopes = mutableSetOf<SearchScope>(SearchScope.Config(config.id)) |
| 39 | search-provider / SearchScope.Config | val scopes = mutableSetOf<SearchScope>(SearchScope.Config(config.id)) |
| 40 | search-provider / SearchScope.Options | if (searchable) scopes += SearchScope.Options // global search |
| 56 | ui-function / documents | override fun documents(): List<SearchDocument<Property<*>>> { |
| 63 | search-provider / SearchScope.Keybinds | scopes = setOf(SearchScope.Keybinds), |
| 81 | ui-function / modDocument | private fun modDocument(config: ConfigData): SearchDocument<ConfigData> { |
| 85 | search-provider / SearchScope.Mods | scopes = setOf(SearchScope.Mods), |
| 98 | ui-function / treeDocuments | private fun treeDocuments( |
| 103 | search-provider / SearchScope | scopes: Set<SearchScope>, |
| 108 | ui-function / walk | fun walk(node: Node, path: String, category: String, subcategory: String, section: String?) { |
| 113 | search-provider / SearchScope.Keybinds | val documentScopes = if (node is Property<*> && node.isKeybindProperty()) scopes + SearchScope.Keybinds |
| 161 | search-provider / SearchCorpus.invalidate | HudManager.addRegistrationListener { SearchCorpus.invalidate(HudDocumentSource) } |
| 164 | ui-function / documents | override fun documents(): List<SearchDocument<Hud>> = HudManager.providers().toList().map { hud -> |
| 168 | search-provider / SearchScope.Huds | scopes = setOf(SearchScope.Huds), |
| 188 | search-provider / SearchCorpus.invalidate | HudManager.addRegistrationListener { SearchCorpus.invalidate(HudModCardDocumentSource) } |
| 191 | ui-function / documents | override fun documents(): List<SearchDocument<ConfigData>> = hudModCardConfigs().mapNotNull { card -> |
| 195 | search-provider / SearchScope.Mods | scopes = setOf(SearchScope.Mods), |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/search/DefaultSearchProvider.kt

`file-329fcb12d97d996ba670`；状态 **pending**；领域 search；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 6 | search-provider / DefaultSearchProvider | internal object DefaultSearchProvider : SearchProvider { |
| 6 | search-provider / SearchProvider | internal object DefaultSearchProvider : SearchProvider { |
| 9 | ui-function / isAvailable | override fun isAvailable(): Boolean = true |
| 11 | ui-function / search | override fun search( |
| 13 | search-provider / SearchScope | scopes: Set<SearchScope> |
| 17 | search-provider / SearchScope.Keybinds | val searchingKeybinds = SearchScope.Keybinds in scopes |
| 18 | search-provider / SearchScope.Config | val searchingConfig = scopes.any { it is SearchScope.Config } |
| 19 | search-provider / SearchCorpus.corpus | return SearchCorpus.corpus.values.filter { |
| 25 | search-provider / SearchScope.Mods | if (it.scopes.contains(SearchScope.Mods)) { |
| 30 | search-provider / SearchScope.Config | if (searchingConfig && it.scopes.any { s -> s is SearchScope.Config } && |
| 34 | search-provider / SearchScope.Huds | if (SearchScope.Huds in it.scopes && ( |
| 39 | search-provider / SearchScope.Keybinds | if (searchingKeybinds && SearchScope.Keybinds in it.scopes && ( |
| 48 | ui-function / searchGrouped | override fun <T> searchGrouped( |
| 50 | search-provider / SearchScope | scopes: Set<SearchScope>, |
| 56 | ui-function / matches | private fun String?.matches(query: String): Boolean = this != null && searchMatches(this, query) |
| 60 | ui-function / levenshtein | private fun levenshtein(a: String, b: String, max: Int): Int { |
| 85 | ui-function / searchMatches | internal fun searchMatches(text: String, query: String): Boolean { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/search/SearchCorpus.kt

`file-d3632b373375f1818694`；状态 **pending**；领域 search；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 26 | search-provider / SearchCorpus | object SearchCorpus { |
| 52 | ui-function / registerSource | fun registerSource(source: SearchDocumentSource) { |
| 60 | ui-function / unregisterSource | fun unregisterSource(source: SearchDocumentSource) { |
| 69 | ui-function / init | fun init() { |
| 75 | ui-function / invalidate | fun invalidate(vararg invalidated: SearchDocumentSource) { |
| 86 | ui-function / schedule | private fun schedule() { |
| 98 | search-provider / SearchScope | fun search(query: String, scope: Set<SearchScope>): List<SearchDocument<*>> { |
| 98 | ui-function / search | fun search(query: String, scope: Set<SearchScope>): List<SearchDocument<*>> { |
| 100 | search-provider / SearchProviderRegistry.get | val provider = SearchProviderRegistry.get() |
| 105 | search-provider / DefaultSearchProvider | if (provider === DefaultSearchProvider) return emptyList() |
| 106 | search-provider / DefaultSearchProvider.search | runCatching { DefaultSearchProvider.search(query, scope) }.onFailure { |
| 116 | ui-function / searchGrouped | fun <T> searchGrouped( |
| 118 | search-provider / SearchScope | scope: Set<SearchScope>, |
| 122 | search-provider / SearchProviderRegistry.get | val provider = SearchProviderRegistry.get() |
| 127 | search-provider / DefaultSearchProvider | if (provider === DefaultSearchProvider) return emptyMap() |
| 128 | search-provider / DefaultSearchProvider.searchGrouped | runCatching { DefaultSearchProvider.searchGrouped(query, scope, grouper) }.onFailure { |
| 139 | search-provider / SearchProvider | internal fun seed(provider: SearchProvider) { |
| 139 | ui-function / seed | internal fun seed(provider: SearchProvider) { |
| 149 | ui-function / rebuild | private suspend fun rebuild() = rebuildMutex.withLock { |
| 211 | search-provider / SearchProviderRegistry.all | SearchProviderRegistry.all().forEach { provider -> |
| 218 | ui-function / expandDirty | private fun expandDirty( |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/search/SearchDocument.kt

`file-421825408ef918774897`；状态 **pending**；领域 search；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 4 | search-provider / SearchScope | sealed interface SearchScope { |
| 6 | search-provider / SearchScope | data object Mods : SearchScope |
| 9 | search-provider / SearchScope | data object Options : SearchScope |
| 12 | search-provider / SearchScope | data object Keybinds : SearchScope |
| 15 | search-provider / SearchScope | data object Huds : SearchScope |
| 18 | search-provider / SearchScope | data class Config(val id: String) : SearchScope |
| 47 | search-provider / SearchScope | val scopes: Set<SearchScope>, |
| 52 | ui-function / contentEquals | fun contentEquals(other: SearchDocument<*>): Boolean = |
| 56 | ui-function / equivalentTo | fun equivalentTo(other: SearchDocument<*>): Boolean = contentEquals(other) && payload === other.payload |
| 58 | ui-function / toString | override fun toString(): String { |
| 64 | ui-function / documents | fun interface SearchDocumentSource { |
| 65 | ui-function / documents | fun documents(): List<SearchDocument<*>> |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/search/SearchProvider.kt

`file-aae9b3bbd905cd7bbb46`；状态 **pending**；领域 search；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 5 | search-provider / SearchProvider | interface SearchProvider { |
| 10 | ui-function / isAvailable | fun isAvailable(): Boolean |
| 19 | ui-function / search | fun search( |
| 21 | search-provider / SearchScope | scopes: Set<SearchScope> |
| 32 | ui-function / searchGrouped | fun <T> searchGrouped( |
| 34 | search-provider / SearchScope | scopes: Set<SearchScope>, |
| 39 | ui-function / onCorpusUpdate | suspend fun onCorpusUpdate(added: List<SearchDocument<*>>, removed: Set<String>) {} |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/search/SearchProviderRegistry.kt

`file-245e6dd0e2ceec003143`；状态 **pending**；领域 search；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

注册/注销及异常 provider，验证优先级、查询范围、结果跳转和空结果。 定位类型: search-provider 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 6 | search-provider / SearchProviderRegistry | object SearchProviderRegistry { |
| 7 | search-provider / SearchProvider | private val providers: CopyOnWriteArrayList<SearchProvider> = CopyOnWriteArrayList() |
| 10 | search-provider / registerSearchProvider | fun registerSearchProvider(provider: SearchProvider) { |
| 10 | search-provider / SearchProvider | fun registerSearchProvider(provider: SearchProvider) { |
| 10 | ui-function / registerSearchProvider | fun registerSearchProvider(provider: SearchProvider) { |
| 16 | search-provider / SearchCorpus.seed | SearchCorpus.seed(provider) |
| 19 | search-provider / SearchProvider | internal fun unregisterSearchProvider(provider: SearchProvider) { |
| 19 | ui-function / unregisterSearchProvider | internal fun unregisterSearchProvider(provider: SearchProvider) { |
| 24 | search-provider / SearchProvider | internal fun get(): SearchProvider = |
| 24 | ui-function / get | internal fun get(): SearchProvider = |
| 25 | search-provider / DefaultSearchProvider | providers.firstOrNull { runCatching { it.isAvailable() }.getOrNull() ?: false } ?: DefaultSearchProvider |
| 27 | search-provider / SearchProvider | internal fun all(): List<SearchProvider> = providers.toList() |
| 27 | ui-function / all | internal fun all(): List<SearchProvider> = providers.toList() |
| 30 | search-provider / registerSearchProvider | registerSearchProvider(DefaultSearchProvider) |
| 30 | search-provider / DefaultSearchProvider | registerSearchProvider(DefaultSearchProvider) |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/search/SettingIndex.kt

`file-d0057aba2f5b9ad819f1`；状态 **pending**；领域 search；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 42 | ui-function / buildCategories | internal fun buildCategories(tree: Tree, include: (Node) -> Boolean = { true }): List<CategoryGroup> { |
| 69 | ui-function / buildAccordionNode | internal fun buildAccordionNode(tree: Tree): SettingNode.Accordion? { |
| 88 | ui-function / isAccordionToggle | private fun isAccordionToggle(prop: Property<*>): Boolean { |
| 93 | ui-function / isRenderableProperty | internal fun isRenderableProperty(prop: Property<*>): Boolean { |
| 98 | ui-function / nodeGroup | internal fun nodeGroup(node: Node, key: String, default: String): String { |
| 102 | ui-function / isHidden | internal fun Property<*>.isHidden(): Boolean = display == Property.Display.HIDDEN |
| 104 | ui-function / filterHiddenNodes | internal fun filterHiddenNodes(category: CategoryGroup): CategoryGroup? { |
| 117 | ui-function / flattenEntries | internal fun flattenEntries(category: CategoryGroup): List<ConfigListEntry> { |
| 131 | ui-function / flattenSearchEntries | internal fun flattenSearchEntries(categories: List<CategoryGroup>): List<ConfigListEntry> { |
| 158 | ui-function / buildSearchIndex | internal fun buildSearchIndex( |
| 189 | ui-function / searchNode | internal fun searchNode(node: SettingNode, documents: List<SearchDocument<*>>): SettingNode? { |
| 207 | ui-function / rowOf | fun rowOf(document: SearchDocument<*>): SearchRow? = (document.payload as? Node)?.let(rows::get) |
| 209 | ui-function / rebuild | fun rebuild() { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/shell/Data.kt

`file-65113bc8aea00e84ef35`；状态 **pending**；领域 shell-navigation；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

实测按下/释放、组合键、录制/冲突、文字输入焦点与开关屏期间的消费顺序。 定位类型: keybind 从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 7 | route / HudEditorRoute | object HudEditorRoute |
| 25 | ui-function / handleSearchKeybind | fun handleSearchKeybind(pressed: Boolean): Boolean { |
| 26 | keybind / KeybindRecordingBus.isRecording | if (KeybindRecordingBus.isRecording) return false |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/shell/ScrollMemory.kt

`file-23ca693b3edda0ccc3e4`；状态 **pending**；领域 shell-navigation；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 31 | composable / rememberRestorableLazyListState | @Composable |
| 32 | ui-function / rememberRestorableLazyListState | fun rememberRestorableLazyListState( |
| 51 | composable / ScrollToTopOnChange | @Composable |
| 52 | ui-function / ScrollToTopOnChange | fun ScrollToTopOnChange(state: LazyListState, key: Any?, initial: Any? = key) { |
| 63 | composable / rememberRestorableLazyGridState | @Composable |
| 64 | ui-function / rememberRestorableLazyGridState | fun rememberRestorableLazyGridState(key: String): LazyGridState { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/shell/Shell.kt

`file-862fd63ac8141745a010`；状态 **pending**；领域 shell-navigation；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 从入口打开目标，验证前进/后退、分类参数、已注销/缺失目标与页面恢复。 定位类型: route 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 77 | ui-function / glowAlphaAt | private fun glowAlphaAt(t: Float): Float { |
| 85 | ui-function / erf | private fun erf(x: Float): Float { |
| 93 | ui-function / cover | private fun cover(x: Float, lo: Float, hi: Float): Float { |
| 98 | ui-function / alphaAt | fun alphaAt(x: Float, y: Float) = color.alpha * cover(x, l, r) * cover(y, t, b) |
| 106 | ui-function / get | fun get(key: List<Any?>, w: Float, h: Float, x0: Float, x1: Float, color: Color, alphaAt: (x: Float, y: Float) -> Float): PolyImageBitmap { |
| 129 | ui-function / draw | fun DrawScope.draw(x0: Float, x1: Float, accent: Color, alpha: Float, fill: Color, shadow: ShadowBake?) { |
| 150 | composable / Shell | @Composable |
| 151 | ui-function / Shell | fun Shell( |
| 230 | animation / pageAnimationDuration | fun pageMs() = (OneConfigConfig.pageAnimationDuration * 1000f).toInt().coerceAtLeast(1) |
| 230 | ui-function / pageMs | fun pageMs() = (OneConfigConfig.pageAnimationDuration * 1000f).toInt().coerceAtLeast(1) |
| 235 | ui-function / enterSlide | fun enterSlide() = |
| 236 | animation / tween | if (transitions.lastNavWasBack) slideInHorizontally(tween(pageMs())) { -it / 5 } |
| 237 | animation / tween | else slideInHorizontally(tween(pageMs())) { it / 5 } |
| 239 | ui-function / exitSlide | fun exitSlide() = |
| 240 | animation / tween | if (transitions.lastNavWasBack) slideOutHorizontally(tween(pageMs())) { it / 5 } |
| 241 | animation / tween | else slideOutHorizontally(tween(pageMs())) { -it / 5 } |
| 248 | animation / tween | enterTransition = { if (transitions.animates()) enterSlide() + fadeIn(tween(pageMs())) else EnterTransition.None }, |
| 249 | animation / tween | exitTransition = { if (transitions.animates()) exitSlide() + fadeOut(tween(pageMs())) else ExitTransition.None }, |
| 251 | route / navigation | navigation() |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/shell/ShellController.kt

`file-2570fc3f9cac12fcb03d`；状态 **pending**；领域 shell-navigation；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 31 | animation / ShellTransitions | val transitions = ShellTransitions() |
| 66 | ui-function / updatePlayerName | fun updatePlayerName(name: String) { |
| 70 | ui-function / updatePlayerHead | fun updatePlayerHead(png: ByteArray?) { |
| 74 | ui-function / updateVersionLabel | fun updateVersionLabel(label: String) { |
| 78 | ui-function / markClosed | fun markClosed(at: Long) { |
| 82 | ui-function / resume | fun resume() { |
| 86 | ui-function / stop | fun stop() { |
| 107 | ui-function / update | fun update(text: String) { |
| 112 | ui-function / clear | fun clear() = update("") |
| 114 | ui-function / queryFor | fun queryFor(entryId: String?): String = |
| 117 | ui-function / requestFocus | fun requestFocus(request: SearchFocus) { |
| 121 | ui-function / consumeFocusRequest | fun consumeFocusRequest() { |
| 125 | ui-function / updateFieldFocused | fun updateFieldFocused(focused: Boolean) { |
| 129 | ui-function / bind | internal fun bind(entryId: String?, text: String) { |
| 135 | ui-function / forgetEntries | internal fun forgetEntries() { |
| 157 | ui-function / update | fun update(flipTopOptionOrder: Boolean, pageOpacity: Float, sidebarOpacity: Float, glowOpacity: Float) { |
| 179 | ui-function / markHudDragging | fun markHudDragging(dragging: Boolean) { |
| 183 | ui-function / markModDragging | fun markModDragging(dragging: Boolean) { |
| 187 | ui-function / updateShellBounds | fun updateShellBounds(bounds: Rect?) { |
| 192 | animation / ShellTransitions | class ShellTransitions { |
| 202 | animation / animatePages | var animatePages: Boolean = false |
| 213 | animation / animatePages | fun animates(): Boolean = !awaitingInitialRoute && animatePages |
| 213 | ui-function / animates | fun animates(): Boolean = !awaitingInitialRoute && animatePages |
| 215 | ui-function / beginOpening | fun beginOpening() { |
| 217 | animation / animatePages | animatePages = false |
| 220 | ui-function / finishOpening | fun finishOpening() { |
| 224 | ui-function / prepare | internal fun prepare(animate: Boolean, back: Boolean) { |
| 225 | animation / animatePages | animatePages = animate |
| 236 | ui-function / anchor | fun anchor(key: String): ScrollAnchor? = anchors[key] |
| 238 | ui-function / saveAnchor | fun saveAnchor(key: String, anchor: ScrollAnchor) { |
| 242 | ui-function / grid | fun grid(key: String): LazyGridState? = grids[key] |
| 244 | ui-function / attachGrid | fun attachGrid(key: String, state: LazyGridState) { |
| 248 | ui-function / detachGrid | fun detachGrid(key: String, state: LazyGridState) { |
| 255 | animation / ShellTransitions | private val transitions: ShellTransitions, |
| 299 | ui-function / attach | fun attach(controller: NavHostController) { |
| 305 | ui-function / detach | fun detach(controller: NavHostController) { |
| 309 | ui-function / recordLastRoute | fun recordLastRoute(route: Any?) { |
| 313 | ui-function / selectedCategory | fun selectedCategory(pageKey: String): String? = selectedCategories[pageKey] |
| 315 | ui-function / canGoBack | fun canGoBack(): Boolean = backStack.isNotEmpty() |
| 317 | ui-function / canGoForward | fun canGoForward(): Boolean = forwardStack.isNotEmpty() |
| 319 | ui-function / updateArrows | private fun updateArrows() { |
| 323 | ui-function / reset | fun reset() { |
| 331 | ui-function / readyHost | private fun readyHost(): NavHostController? = if (isReady) host else null |
| 334 | animation / showPageAnimations | fun navigate(route: Any, clearSearch: Boolean = true, animate: Boolean = OneConfigConfig.showPageAnimations) { |
| 334 | ui-function / navigate | fun navigate(route: Any, clearSearch: Boolean = true, animate: Boolean = OneConfigConfig.showPageAnimations) { |
| 350 | ui-function / discard | fun discard(route: Any, fallback: Any) { |
| 372 | ui-function / selectCategory | fun selectCategory(pageKey: String, category: String) { |
| 382 | ui-function / back | fun back() { |
| 402 | animation / showPageAnimations | transitions.prepare(OneConfigConfig.showPageAnimations, back = true) |
| 405 | animation / showPageAnimations | transitions.prepare(OneConfigConfig.showPageAnimations, back = false) |
| 417 | ui-function / forward | fun forward() { |
| 431 | animation / showPageAnimations | transitions.prepare(OneConfigConfig.showPageAnimations, back = false) |
| 438 | ui-function / instantSearch | private fun instantSearch() { |
| 443 | ui-function / seedRouteCategory | private fun seedRouteCategory(route: Any) { |
| 450 | ui-function / applyCategory | private fun applyCategory(entry: Entry) { |
| 456 | ui-function / withCurrentSearch | private fun Entry.withCurrentSearch() = copy(searchQuery = search.query) |
| 459 | composable / pageSearchQuery | @Composable |
| 460 | ui-function / pageSearchQuery | fun pageSearchQuery(): String { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/sound/UiSoundEvent.kt

`file-44c0d881ed7943583a47`；状态 **pending**；领域 sound；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 单独触发该声音事件，检查主题音色、音量、启用开关和触发次数。 定位类型: sound-event

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 3 | sound / UiSoundEvent | enum class UiSoundEvent { |
| 4 | sound-event / OPEN | OPEN, |
| 5 | sound-event / CLOSE | CLOSE, |
| 6 | sound-event / CLICK | CLICK, |
| 7 | sound-event / SLIDER_TICK | SLIDER_TICK, |
| 8 | sound-event / HUD_SELECT | HUD_SELECT, |
| 9 | sound-event / HUD_DRAG_START | HUD_DRAG_START, |
| 10 | sound-event / HUD_DRAG_END | HUD_DRAG_END, |
| 11 | sound-event / HUD_RESIZE_START | HUD_RESIZE_START, |
| 12 | sound-event / HUD_RESIZE_END | HUD_RESIZE_END, |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/sound/UiSoundService.kt

`file-8f69fed401eecf50fca8`；状态 **pending**；领域 sound；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 3 | sound / UiSoundService | interface UiSoundService { |
| 4 | sound / UiSoundEvent | fun play(event: UiSoundEvent, theme: UiSoundTheme, volume: Float) |
| 4 | sound / UiSoundTheme | fun play(event: UiSoundEvent, theme: UiSoundTheme, volume: Float) |
| 4 | ui-function / play | fun play(event: UiSoundEvent, theme: UiSoundTheme, volume: Float) |
| 6 | sound / startAmbience | fun startAmbience(theme: UiSoundTheme, volume: Float) |
| 6 | sound / UiSoundTheme | fun startAmbience(theme: UiSoundTheme, volume: Float) |
| 6 | ui-function / startAmbience | fun startAmbience(theme: UiSoundTheme, volume: Float) |
| 8 | sound / stopAmbience | fun stopAmbience(setDucking: Boolean) |
| 8 | ui-function / stopAmbience | fun stopAmbience(setDucking: Boolean) |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/sound/UiSoundTheme.kt

`file-871ff2d53a7853d2fbca`；状态 **pending**；领域 sound；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 6 | sound / UiSoundTheme | enum class UiSoundTheme { |
| 11 | sound / UiSoundTheme | fun current(): UiSoundTheme = of(ThemeRegistry.activeTheme) |
| 11 | ui-function / current | fun current(): UiSoundTheme = of(ThemeRegistry.activeTheme) |
| 13 | sound / UiSoundTheme | fun of(theme: UITheme?): UiSoundTheme { |
| 13 | ui-function / of | fun of(theme: UITheme?): UiSoundTheme { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/sound/UiSounds.kt

`file-d35d5110967f7b3d62a5`；状态 **pending**；领域 sound；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 6 | sound / UiSounds | object UiSounds { |
| 7 | sound / UiSoundService | private val service: UiSoundService? by lazy { loadService() } |
| 11 | sound / UiSoundService | private fun loadService(): UiSoundService? = try { |
| 11 | ui-function / loadService | private fun loadService(): UiSoundService? = try { |
| 12 | sound / UiSoundService | val it = ServiceLoader.load(UiSoundService::class.java, UiSoundService::class.java.classLoader).iterator() |
| 12 | sound / UiSoundService | val it = ServiceLoader.load(UiSoundService::class.java, UiSoundService::class.java.classLoader).iterator() |
| 18 | ui-function / soundVolume | private fun soundVolume(): Float = OneConfigConfig.uiSoundVolume.coerceIn(0f, 1f) |
| 20 | ui-function / ambienceVolume | private fun ambienceVolume(): Float = OneConfigConfig.uiAmbienceVolume.coerceIn(0f, 1f) |
| 22 | sound / UiSoundEvent | fun play(event: UiSoundEvent) { |
| 22 | ui-function / play | fun play(event: UiSoundEvent) { |
| 27 | sound / UiSoundTheme.current | service?.play(event, UiSoundTheme.current(), v) |
| 30 | sound / UiSoundEvent | private fun isEventEnabled(event: UiSoundEvent): Boolean = when (event) { |
| 30 | ui-function / isEventEnabled | private fun isEventEnabled(event: UiSoundEvent): Boolean = when (event) { |
| 31 | sound / UiSoundEvent.OPEN | UiSoundEvent.OPEN, |
| 32 | sound / UiSoundEvent.CLOSE | UiSoundEvent.CLOSE -> OneConfigConfig.enableUIMenuSounds |
| 33 | sound / UiSoundEvent.CLICK | UiSoundEvent.CLICK -> OneConfigConfig.enableUIClickSounds |
| 34 | sound / UiSoundEvent.SLIDER_TICK | UiSoundEvent.SLIDER_TICK -> OneConfigConfig.enableUISliderSounds |
| 35 | sound / UiSoundEvent.HUD_SELECT | UiSoundEvent.HUD_SELECT, |
| 36 | sound / UiSoundEvent.HUD_DRAG_START | UiSoundEvent.HUD_DRAG_START, |
| 37 | sound / UiSoundEvent.HUD_DRAG_END | UiSoundEvent.HUD_DRAG_END, |
| 38 | sound / UiSoundEvent.HUD_RESIZE_START | UiSoundEvent.HUD_RESIZE_START, |
| 39 | sound / UiSoundEvent.HUD_RESIZE_END | UiSoundEvent.HUD_RESIZE_END -> OneConfigConfig.enableHudEditorSounds |
| 44 | sound / acquireAmbience | fun acquireAmbience() { |
| 44 | ui-function / acquireAmbience | fun acquireAmbience() { |
| 52 | sound / releaseAmbience | fun releaseAmbience() { |
| 52 | ui-function / releaseAmbience | fun releaseAmbience() { |
| 55 | sound / stopAmbience | service?.stopAmbience(false) |
| 60 | sound / refreshAmbience | fun refreshAmbience() { |
| 60 | ui-function / refreshAmbience | fun refreshAmbience() { |
| 62 | sound / stopAmbience | if (OneConfigConfig.enableUIAmbience) startAmbienceIfEnabled() else service?.stopAmbience(false) |
| 67 | ui-function / onSoundThemeChanged | fun onSoundThemeChanged() { |
| 68 | sound / UiSoundEvent.OPEN | play(UiSoundEvent.OPEN) |
| 70 | sound / stopAmbience | service?.stopAmbience(true) |
| 75 | ui-function / startAmbienceIfEnabled | private fun startAmbienceIfEnabled() { |
| 77 | sound / startAmbience | service?.startAmbience(UiSoundTheme.current(), ambienceVolume()) |
| 77 | sound / UiSoundTheme.current | service?.startAmbience(UiSoundTheme.current(), ambienceVolume()) |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/themes/AnimatedTheme.kt

`file-a38b4d4f0858120ed2e4`；状态 **pending**；领域 theme；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 23 | composable / animateTheme | @Composable |
| 24 | animation / animateTheme | fun animateTheme(target: UITheme): UITheme { |
| 24 | ui-function / animateTheme | fun animateTheme(target: UITheme): UITheme { |
| 25 | animation / tween | val spec = tween<Float>(THEME_ANIM_MS) |
| 26 | animation / tween | val colorSpec = tween<Color>(THEME_ANIM_MS) |
| 28 | animation / animateColorAsState | val pageBackground by animateColorAsState(target.pageBackground, colorSpec, label = "pageBackground") |
| 29 | animation / animateColorAsState | val sidebarBackground by animateColorAsState(target.sidebarBackground, colorSpec, label = "sidebarBackground") |
| 30 | animation / animateColorAsState | val chipBackground by animateColorAsState(target.chipBackground, colorSpec, label = "chipBackground") |
| 31 | animation / animateColorAsState | val controlTrackColor by animateColorAsState(target.controlTrackColor, colorSpec, label = "controlTrackColor") |
| 32 | animation / animateColorAsState | val modCardBackground by animateColorAsState(target.modCardBackground, colorSpec, label = "modCardBackground") |
| 33 | animation / animateColorAsState | val componentBackground by animateColorAsState(target.componentBackground, colorSpec, label = "componentBackground") |
| 34 | animation / animateColorAsState | val popupBackground by animateColorAsState(target.popupBackground, colorSpec, label = "popupBackground") |
| 36 | animation / animateColorAsState | val borderColor by animateColorAsState(target.borderColor, colorSpec, label = "borderColor") |
| 37 | animation / animateColorAsState | val textColor by animateColorAsState(target.textColor, colorSpec, label = "textColor") |
| 38 | animation / animateColorAsState | val textColorSecondary by animateColorAsState(target.textColorSecondary, colorSpec, label = "textColorSecondary") |
| 39 | animation / animateColorAsState | val accentTextColor by animateColorAsState(target.accentTextColor, colorSpec, label = "accentTextColor") |
| 41 | animation / animateColorAsState | val shadowColor by animateColorAsState(target.shadowColor, colorSpec, label = "shadowColor") |
| 42 | animation / animateColorAsState | val controlThumbColor by animateColorAsState(target.controlThumbColor, colorSpec, label = "controlThumbColor") |
| 43 | animation / animateColorAsState | val favoriteColor by animateColorAsState(target.favoriteColor, colorSpec, label = "favoriteColor") |
| 45 | animation / animateCornerShape | val backgroundShape = animateCornerShape(target.backgroundShape, spec) |
| 46 | animation / animateCornerShape | val sideBarNavigationEntryShape = animateCornerShape(target.sideBarNavigationEntryShape, spec) |
| 47 | animation / animateCornerShape | val modCardShape = animateCornerShape(target.modCardShape, spec) |
| 48 | animation / animateCornerShape | val checkBoxShape = animateCornerShape(target.checkBoxShape, spec) |
| 49 | animation / animateCornerShape | val buttonShape = animateCornerShape(target.buttonShape, spec) |
| 50 | animation / animateCornerShape | val popupShape = animateCornerShape(target.popupShape, spec) |
| 80 | composable / animateCornerShape | @Composable |
| 81 | animation / animateCornerShape | private fun animateCornerShape(target: Shape, spec: AnimationSpec<Float>): Shape { |
| 81 | ui-function / animateCornerShape | private fun animateCornerShape(target: Shape, spec: AnimationSpec<Float>): Shape { |
| 83 | animation / animateFloatAsState | val animated by animateFloatAsState(targetRadius.value, spec, label = "cornerRadius") |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/themes/Impls.kt

`file-845cdd883ead34ef1925`；状态 **pending**；领域 theme；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 107 | font-language / UiFontFamilies.ui | family = UiFontFamilies.ui() |
| 142 | font-language / UiFontFamilies.ui | family = UiFontFamilies.ui() |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/themes/Provider.kt

`file-496ec5b189d310331d1a`；状态 **pending**；领域 theme；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐初始/结束状态、时长、曲线、可打断/反向、禁用动画及中途 resize/关闭。 定位类型: animation 逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 51 | ui-function / accentHorizontalBrush | fun accentHorizontalBrush(startX: Float = 0f, endX: Float = Float.POSITIVE_INFINITY): Brush = |
| 54 | ui-function / updateAccent | fun updateAccent() { |
| 77 | ui-function / scrollbarStyle | private fun scrollbarStyle(theme: UITheme) = ScrollbarStyle( |
| 88 | ui-function / surfaceRatio | private fun surfaceRatio(): Float = screenPlatform?.surfaceRatio()?.takeIf { it > 0f } ?: 1f |
| 90 | composable / gridAnchorPx | @Composable |
| 91 | ui-function / gridAnchorPx | private fun gridAnchorPx(scale: Float, anchorSp: Float = GRID_ANCHOR_SP): Float { |
| 96 | composable / pixelGridScale | @Composable |
| 97 | ui-function / pixelGridScale | fun pixelGridScale(scale: Float, max: Float, anchorSp: Float = GRID_ANCHOR_SP): Float { |
| 102 | composable / pixelGridDensity | @Composable |
| 103 | ui-function / pixelGridDensity | private fun pixelGridDensity(designWidth: Dp, designHeight: Dp, theme: UITheme): Density { |
| 132 | ui-function / snapScaleToPixelGrid | internal fun snapScaleToPixelGrid(anchorPx: Float, max: Float): Float { |
| 141 | ui-function / snapScaleToNearestStep | internal fun snapScaleToNearestStep(anchorPx: Float): Float { |
| 146 | composable / Theme | @Composable |
| 147 | composable / @Composable callback/type | fun Theme(content: @Composable () -> Unit) = Theme(pixelGrid = false, content = content) |
| 147 | ui-function / Theme | fun Theme(content: @Composable () -> Unit) = Theme(pixelGrid = false, content = content) |
| 149 | composable / Theme | @Composable |
| 150 | ui-function / Theme | fun Theme( |
| 154 | composable / @Composable callback/type | content: @Composable () -> Unit, |
| 160 | animation / withFrameNanos | withFrameNanos { } |
| 166 | animation / animateTheme | val animated = animateTheme(target) |
| 175 | font-language / UiFontOverride.revision | val fontRevision = UiFontOverride.revision |
| 177 | font-language / UiFontOverride.buildFamily | val family = runCatching { UiFontOverride.buildFamily() }.getOrNull() |
| 191 | ui-function / syncNotificationTheme | internal fun syncNotificationTheme(theme: UITheme) { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/themes/Radii.kt

`file-11668abfaa1dced962f5`；状态 **pending**；领域 theme；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐状态核对布局、字体、尺寸、hover、按下、禁用及焦点表现；函数类型注解须继续追到实际渲染调用者。 定位类型: composable 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 44 | composable / cornerRadiusOrNull | @Composable |
| 45 | ui-function / cornerRadiusOrNull | fun Shape.cornerRadiusOrNull(): Dp? { |
| 67 | composable / concentric | @Composable |
| 68 | ui-function / concentric | fun Shape.concentric(inset: Dp, minRadius: Dp = 0.dp): Shape { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/themes/Structs.kt

`file-faa9aca22a399e567aba`；状态 **pending**；领域 theme；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 13 | ui-function / withOpacityPercent | fun Color.withOpacityPercent(percent: Float): Color = |
| 57 | ui-function / withControlTrackColor | fun withControlTrackColor(color: Color): UITheme = |
| 67 | ui-function / withShapes | fun withShapes(modIconShape: Shape, scrollBarShape: Shape): UITheme = |
| 87 | ui-function / brandedLogoPath | private fun brandedLogoPath(theme: UITheme): String = OneConfigTheme.logoOverride ?: theme.branding.logoPath |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/themes/ThemeRegistry.kt

`file-d3c77d87668afbcd1f4d`；状态 **pending**；领域 theme；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

对齐触发次数、主题、音量/静音、环境循环获取释放和游戏音乐恢复。 定位类型: sound 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 37 | ui-function / init | fun init() {} |
| 39 | ui-function / register | fun register(theme: UITheme) { |
| 43 | ui-function / activate | fun activate(theme: UITheme) { |
| 50 | sound / UiSoundTheme.of | if (UiSoundTheme.of(previous) != UiSoundTheme.of(theme)) { |
| 50 | sound / UiSoundTheme.of | if (UiSoundTheme.of(previous) != UiSoundTheme.of(theme)) { |
| 51 | sound / UiSounds.onSoundThemeChanged | UiSounds.onSoundThemeChanged() |
| 53 | sound / UiSounds.play | UiSounds.play(UiSoundEvent.CLICK) |
| 53 | sound / UiSoundEvent.CLICK | UiSounds.play(UiSoundEvent.CLICK) |
| 58 | ui-function / loadFromConfig | fun loadFromConfig() { |
| 71 | ui-function / saveThemeToConfig | private fun saveThemeToConfig() { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/themes/UiFontFamilies.kt

`file-9b3bcd7e03530ea73164`；状态 **pending**；领域 font-language；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

热切字体/语言，核对 CJK/缺字回退、字重、截断、搜索及重开持久化。 定位类型: font-language 逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 21 | font-language / UiFontFamilies | object UiFontFamilies { |
| 22 | ui-function / ui | fun ui(): FontFamily { |
| 23 | font-language / UiFontFiles.REGULAR | val regular = FontManager.fontData(UiFontFiles.REGULAR) |
| 24 | font-language / UiFontFiles.DEFAULT_REGULAR | ?: FontManager.fontData(UiFontFiles.DEFAULT_REGULAR) |
| 26 | font-language / UiFontFiles.BOLD | val bold = FontManager.fontData(UiFontFiles.BOLD) |
| 27 | font-language / UiFontFiles.DEFAULT_BOLD | ?: FontManager.fontData(UiFontFiles.DEFAULT_BOLD) |
| 37 | ui-function / font | private fun font(name: String, data: ByteArray, weight: FontWeight) = |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/util/CachedDropShadow.kt

`file-78a0929245aba362831b`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

逐个入口核对该源文件的可见行为、状态更新、异常和退出路径。 定位类型: ui-function

| 行号 | 类型 / 名称 | 源码摘录 |
| ---: | --- | --- |
| 16 | ui-function / cachedDropShadow | fun Modifier.cachedDropShadow(shape: Shape, shadow: Shadow, hole: Boolean = false): Modifier { |

### muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/util/LayoutRef.kt

`file-e1ee0f1df3480f6312d9`；状态 **pending**；领域 ui-support；组合 1.20.1/fabric, 1.20.1/forge, 1.21.1/fabric, 1.21.1/neoforge, 1.21.10/fabric, 1.21.10/neoforge, 1.21.11/fabric, 1.21.11/neoforge, 26.1.2/fabric, 26.1.2/neoforge, 26.2/fabric, 26.2/neoforge, 26.3/fabric, 26.3/neoforge。

该文件支撑 ui-support；人工梳理调用者和可见状态，补齐语义案例并验证真实入口。

无直接词法 UI 调用；须人工验证调用链，不能自动视为不适用。

## 验收顺序建议

1. 固定源 HEAD/源树哈希，核对扫描无漂移；先恢复全部字体、图标、图片、语言和声音字节资产。
2. 在同一窗口/GUI scale/主题/语言/字体/设置快照下逐页截图；菜单、弹层、空/错/忙/禁用/hover/焦点均留对照。
3. 录制动画全程，对齐曲线、时长、半途关闭/反向、拖动透明度、页面方向和禁用开关。
4. 逐项测 HUD tour/库/锚定/合并/文字设计、通知、搜索 provider、改键、profile 与预设异常路径。
5. 将实际行为报告、截图与实现哈希填入 feature-map；按 14 个准确 MC/loader 组合补游戏记录。最后再按主仓规则编译和运行验收。

本侧任务不运行 Gradle/游戏，不修改原仓，不提交/推送/发布。当前源码扫描、脚本自测与已有 JAR 检查的结果不能提升为游戏实测结论。
