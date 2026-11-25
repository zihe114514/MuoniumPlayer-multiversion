# 搜索移植与验收记录

核对日期：2026-11-24。原仓 HEAD：`bdaa0eb7f0bdcbbd9d8ceee2ccd748fdcb36503b`。本任务只读原仓，在 Java 17 中实现检索与接线契约；游戏、视觉、交互和动画的最终验收由人工负责。

## 当前状态

| 层次 | 结果 | 边界 |
| --- | --- | --- |
| implementation | 已实现中立搜索引擎、增量语料、设置文档/行归属、SettingsSchema 适配、扩展页面与异步全局页面入口 | 主线程已接 ConfigView MODS 查询与服务单例；本侧未修改主线程文件，动态失效及最终运行仍需核对 |
| automated | Java 17 局部集成编译及 10 组、22,315 条行为断言通过 | 无 MC/loader、真实游戏线程或 GPU；不是 14 组合运行通过 |
| human | pending | 原版/新版同场景视觉、键鼠交互、搜索等待/空结果与动画由人工验收 |

pending 是证据待齐，不是失败功能数，也不是测试包生成的门禁。feature-map 为五项搜索行为记录了实现范围与 automatedNeutral 报告，其中部分入口保留 partial；全部 status 仍 pending，没有把 feature-map 全部置完成。

## 原实现逐项依据

以下路径相对只读原仓；完整源码/资源指纹由 reference/source-manifest.json 管理。

| 源文件定位 | SHA256 | 核实内容 |
| --- | --- | --- |
| muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/search/DefaultSearchProvider.kt:85 | `45d285f987dd53e6f26b872fd7ab7264ff2583ffd89c24a8df0a414c0bbccd00` | 大小写、五分隔符、Levenshtein 与 scope 字段 |
| muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/search/SearchCorpus.kt:98 | `1c1d7bdd2bb347a520453c3dbd9413db673ac8c1ab740b131a9e7ca32997c928` | 250ms 合并、增量源、依赖失效、重复 ID、失败旧缓存、过期结果重解析 |
| muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/search/SearchProviderRegistry.kt:10 | `e661527e2a8177ee0974b07262c363977f4c2d993846d2cdfe54476c10d77e7e` | 最高优先级可用 provider 与稳定注册顺序 |
| muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/search/SettingIndex.kt:158 | `9585eb1ebece10a0b4bdabab99726149dadbf49b7a1294f03e998f3f6d2f6b95` | 行身份、groupLabel 与 accordion whole/child 命中 |
| muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/search/ConfigDocuments.kt:29 | `ff4314cefaee198f90b1f373b14cace3a763911d9b573343ec150f3acde039fe` | 稳定 owner::path 文档 ID、静态 hidden 与真实可渲染属性 |
| muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/internal/OneConfigUISearchBridgeImpl.kt:42 | `fde3da1614b34de7cf78efdc94ae71638da9f7c6fe9e91d5c9c3fca746819a12` | 公开 provider 返回文档 ID 的桥接约定 |
| muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/ConfigScreen.kt:310 | `c81ef81f42d3a3130ab4414889d6178129435dc910465d8f2dbcd6da003cc968` | title/id/description/category/subcategory 五字段 OR |
| muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/screens/SearchResultsScreen.kt:67 | `2d5db069a0711d39b7a01130415ee9d8b9554e8e327cbc32e774dbc3af862bd9` | 全局 Mods/Options，保留首命中顺序并折叠同一设置行 |
| muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/page/OneConfigPages.kt:27 | `13933ccb9cc7cbb038f889d567cbc9e21977d1268f741f453d7ec96ae436a368` | 命名空间、拒绝重复、group/order 稳定排序 |
| muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/api/ui/v1/page/PageSearchQuery.kt:8 | `f94119f565a36695c1b9510dd8c602ba1253c919c465d1dbc00a70726f8681b1` | PageContext 对应的页面 query，不发明全局模糊查询状态 |

## 算法与排序契约

- `SearchIndex.matches(text, query, distance)` 对 text/query 用 Locale.ROOT lowercase；先 substring，再以带行最小值早退的 Levenshtein 匹配全文或任一非空 token。默认 distance=2；distance<=0 仅保留 substring。按 UTF-16 char 计算，与原 Kotlin String 下标一致，未改为 Damerau/Unicode 码点距离。
- token 只在普通空格、` . _ - / ` 五种字符切分；不按 tab、colon、逗号、反斜线、camelCase、中文分词。query 整体比较，不实现多词 AND、拼音或未核实的分词器。
- helper 保留空查询可作为子串匹配的原始行为；index.search 对 blank query 返回空。trimQuery 使用 Kotlin 风格 Character.isWhitespace 或 isSpaceChar，处理 NBSP 等前后空白。
- 原默认 provider 没有相关度计分和二次排序。语料合并使用 source 注册顺序和各 source 文档顺序；第一个重复 ID 胜出并诊断。精确结果不会越过更早的模糊结果。provider 返回的顺序和重复项保留，未知/被删 ID 丢弃。
- 多 source 合并语料；查询选择最高优先级且 isAvailable 的一个 provider。同优先级保留注册顺序；抛异常回默认 provider，不依次重试低优先级，也不把所有 provider 的结果 union。

| Scope | 默认匹配字段 |
| --- | --- |
| MODS | title、tags；不搜索 mod id/description/category |
| OPTIONS | title、description、tags；全局不额外搜索 category/id |
| config(ownerId) | title、description、tags，再加 category/subcategory/id；不加 path |
| HUDS | title、description、tags、category/subcategory/id/modTitle |
| KEYBINDS | title、description、tags，且请求 KEYBINDS 时加 category/subcategory/id/path；不加 modTitle |

section 与 modDescription 作为元数据保留，默认 provider 不把它们当检索字段。局部 SettingsSearchAdapter.matches 与原 Property.matchesLocalSearch 一致，五字段单独 OR，不跨字段拼接。

## 可直接集成的入口

包 `com.muoniumplayer.configskija.model`：

- `SearchIndex`：构造默认 distance2；registerSource/unregisterSource、init/invalidate、rebuildNow、registerProvider/unregisterProvider、search/searchGrouped/searchAsync、document/documentIds、revision、close。Source.dependencies 支持传递失效；Source 失败保留旧文档并保持 dirty；provider 注册后异步 seed 现有语料。
- `SearchDocuments.settings(ownerId, modTitle, categories[, adapter])` 返回 SettingsSnapshot，带 documents() 和 rows()。默认稳定文档键为 owner::Option.id，accordion 为 owner::Group.id；不从本地化标签或索引生成新键。
- `RowIndex.rowOf(document)`、`rowOfPayload(payload)`；均按对象身份。group(hits)、narrow(row,hits)、globalGroups(hits) 保留 provider 顺序，同一 accordion 只产生一个设置行。tree/head 命中保留完整 body，child 命中仅保留命中 body；运行时全 hidden 不显示。
- `SearchDocuments.mod/keybind/hud` 为额外 source 提供文档工厂；Owner.searchable=false 只保留 config(owner) scope，避免把隐私/非公开设置放进全局。

包 `com.muoniumplayer.configskija.ui.search`：

- `SettingsSearchAdapter(schema[, metadata])` 读取已有 SettingsSchema；自定义 metadata 供应稳定 path、aliases/tags、静态 indexed 与额外 keybind 信息。visible Supplier 在 UI 行过滤时读取，不从搜索 worker 调宿主。
- `SearchPage(index,rowIndex,uiExecutor)` 默认创建已实现的 `GlobalResultsRenderer`；四参版本可注入 renderer。全局 scope 为 MODS+OPTIONS，Results 的分组按原版 owner/groupLabel。实际卡片与设置控件均由下述共享实现渲染。
- `SearchSession` 将 worker 结果投回显式 UI executor，并以代数 ticket 丢弃旧查询、关闭后的回调；交付时再解析当前语料，避免 profile/语言刷新后的旧对象。
- `SearchPages` 注册 namespaced 页面，拒绝重复，按 group.order/group.id/page.order 稳定排序；每页 query 显式来自 PageContext.query()，scopes 自定。当前 Navigation.Page 是固定 enum，新页面路由的宿主接线需主助手适配，不能仅以 registry 类存在认定已在侧栏可用。

示例（应在宿主 UI 线程捕获并替换 snapshot）：

~~~java
SearchIndex index = new SearchIndex(2);
SettingsSearchAdapter adapter = new SettingsSearchAdapter(schema);
SearchDocuments.SettingsSnapshot initial =
    SearchDocuments.settings("muonium", "MuoniumPlayer", host.categories(), adapter);
java.util.concurrent.atomic.AtomicReference<SearchDocuments.SettingsSnapshot> snapshot =
    new java.util.concurrent.atomic.AtomicReference<>(initial);
SearchIndex.Source source = () -> snapshot.get().documents();
index.registerSource(source);
SearchPage searchPage = new SearchPage(index, initial.rows(), uiExecutor, renderer);
index.init();

// Render/UI owner thread after labels, schema, profile or registrations change:
SearchDocuments.SettingsSnapshot next =
    SearchDocuments.settings("muonium", "MuoniumPlayer", host.categories(), adapter);
snapshot.set(next);
searchPage.rows(next.rows());
index.invalidate(source);
// index.close() on owning screen/application disposal; worker lifecycle is explicit.
~~~

Source.documents 在 worker 调用，因此不能直接从未知线程读取宿主类别、翻译、设置值或 UI Supplier。推荐持久 source + AtomicReference immutable snapshot；语言/字体/配置注册/profile 更新后重新捕获，再 invalidate。构造器可注入 ScheduledExecutorService 和 diagnostics；默认 worker 为 daemon，close 会释放，仅注入的 worker 由调用方拥有。diagnostics 不吞失败：source/provider 异常和重复 ID 都可交宿主日志。

## 局部自动检查

在本工程根运行：

~~~powershell
.\src\test\java\com\muoniumplayer\configskija\search\run-tests.ps1
node tools/audit-source.mjs --self-test
node tools/check-parity.mjs --self-test
node tools/check-parity.mjs --stage implementation --json
~~~

run-tests 只用 D:/JDK17（可传 -Jdk）、javac --release17 和 java -ea。编译当前 model/ui（排除实际 SkijaPainter，加入中立 HudExtensions 契约），不会触发 Gradle、游戏或任何主仓发布产物。输出 build/search-tests/results.txt 与 behavior-results.json 是中立 harness 结果，不冒充原版/14loader/人工证据。

覆盖：独立完整二维 DP 与 bounded 距离穷举对照、精确分隔符、土耳其 locale 不变量、CJK/代理对、scope 字段排除、语料顺序与 group 顺序、provider 选择/异常/seed、过期 ID、增量缓存/传递依赖/失败保留/重复 ID、250ms debounce、退出释放、accordion/hidden/本地化 ID、字段 OR、异步回调失效、页面排序与全局 UiPage 接口。

## 稳定 ID 与局部 history 只读审计（冻结前复核）

本侧不修改被审主线程文件。早期三个反例是对浅快照/全局事务/受锁setter的危险调用模式的复现；冻结前重新读取现代码，已观察到主线程修复，不把历史发现继续当作当前缺陷。

| 项目 | 冻结前源码事实 | 仍需精确核对 |
| --- | --- | --- |
| group ID | host/MuoniumHost.java:121 仍为 category.id + groupIndex | 插入/重排group时accordion文档/展开/定位键可漂移，需真实持久group key |
| 非setting ID | MuoniumHost.java:125-126 仍以group.title/description/kind/colorTarget计算UUID，并以遍历次数后缀去重 | 文案/语言/重复项重排稳定性尚未证明。setting/<enum.name> 主路径较稳定 |
| history值快照 | ConfigView.capture已调用ValueSnapshots.copy；History用ValueSnapshots.equal；数组/List/Map做递归copy | 不再是早期浅Map原样实现；任意record被视为不可变的假设，含可变字段的record仍需其具体codec证据 |
| history局部范围 | ConfigView.beginEdit(Option)设置historyScope={option.id}；capture按scope过滤；主路径已限制单项事务 | generic beginEdit(String)仍允许无scope；嵌套复合控件/重置/跨profile事务须按实际调用继续验收 |
| restore授权 | ConfigView.restore(desired,previous)按差异直接option.write().accept，避免早期Option.set.enabled门控吞恢复 | 这是源码已缓解；真实依赖联动/异常/回滚仍无游戏证据 |
| profile历史拥有者 | 新MuProfile由Library队友接，HUD已有owner边界 | 切换、异步恢复与活动input/undo/search snapshot需运行验证，不因旧内置页曾history.clear推断新路径全部通过 |

HistoryBoundaryAuditTest保留3个历史危险模式反例作为设计说明，实际当前integration修复是否通过由主线程对应测试确认。本侧搜索与renderer测试不把这三个反例当成当前生产缺陷数量。

## 人工验收与剩余接线

1. 主线程已在SkijaConfigService.initializeSearch/searchPage创建真实index/source与GlobalResultsRenderer，在ConfigView.drawPage把MODS非空查询切到globalSearchPage。动态locale/profile/schema/HUD/keybind注册后重新捕获与失效，以及服务关闭时资源归属仍需按下方冻结检查核对。
2. 全局搜索页已实际复用LibraryMods.CardRenderer和SettingsPage.EntryRows，局部harness覆盖waiting、no result、真实点击、滚动、锁定、accordion与录制；实际游戏的回退/字体/输入时序和滚动恢复仍需主线程验收。
3. 用户在同一 MC/loader/配置/语言中对照中文、英文错字、默认距离2、距离0、多字段与自定义 provider；验证排序、焦点、查询恢复和过期回调。
4. 搜索无新增位图资产。来源/资产指纹先排除 run/build/dist/log/save、账号/token/cookie/secret 容器、.env/私钥/数据库与任意运行配置目录，仅处理源码/静态资源/构建契约。不得放入账号配置或运行样例。
5. check-parity 的 --stage implementation 保留完整 findings，同时把人工待验收、未出齐测试包标为信息项；静态源码漂移、源侧真缺资产和禁用依赖打包仍阻塞；旧成品缺资源保留为待重建信息。它不阻止生成测试包，fullParityOk 单独表示完整验收，pending 不等同功能失败。


## GlobalResultsRenderer 实际接线（2026-11-24 增量）

新增 ui/search/GlobalResultsRenderer.java，直接实现 SearchPage.Renderer。主线程只需要在 MODS 查询页路由到以下实例，不用另实现一套卡片/控件：

~~~java
SearchPage global = new SearchPage(index, snapshot.rows(), clientExecutor,
    new GlobalResultsRenderer(owner -> schemaForActualOwner(owner)));
// Or new SearchPage(index, snapshot.rows(), clientExecutor) uses the host SettingsSchema.Provider.
// On snapshot update: global.rows(next.rows()); index.invalidate(source).
// On leaving: global.leave(context); on final disposal: global.close().
~~~

- 卡片复用 LibraryMods.cardRenderer() 的实际 CardRenderer.fromDocument/draw/leave：真实 catalog 解析、旧 muonium→muonium.hud 映射、live toggle、打开回调、favorite/hidden/tooltip。点击释放才打开，拖移取消；不会借全局结果页修改模组网格排序。找不到真实 card 会明确显示失败并诊断，不合成演示卡片。
- 行复用设置队友的 public EntryRows：beginFrame/render/endFrame，内部调用同一个 SettingsPage.rowHeight/drawRow/accordion。保留真实 Option identity/setter、menu/reset、variable multiline、hidden、每owner临时命名空间与原有text/slider/keybind逻辑。
- 原 SearchResultsScreen.kt:41-46、99-145 的布局：4列、卡片140dp、列gap19dp；宽度按当前physicalPerLayout转物理像素round后整数除4。mod行之间8dp list间距加11dp额外空间=19dp；最后mod行无额外11dp。MODS header为11sp/500 Medium、bottom4；owner组头uppercase/11sp/500/top8/bottom2；列表项gap8。PageContext.content已经包含外壳25dp横边距，renderer不重复添加。滚动条仍由宿主依scrollRange画一份。
- owner开关由真实 SettingsSchema resolver 提供 locked()/lockExemptId()，关闭owner为整层0.45；自己的主开关按真实稳定ID豁免；Option.disabledReason自己的0.65独立，不由标题猜开关归属。
- Typography是可注入的draw/measure/lineHeight接口。默认使用现有NotificationTypography数值字重接口，或已有Painter的Regular/Bold回退。实际宿主必须支持Medium500及正确行高才能声称字形/布局等价；可注入完整构造 new GlobalResultsRenderer(new EntryRows(ownerSchemas), LibraryMods.cardRenderer(), typography) 接HostTextRenderer.drawUi/measureUi。冻结前已确认SkijaConfigService.painter匿名Text.drawWeighted/measureWeighted接HostTextRenderer.drawUi/measureUi数值字重，500接口不再只是默认Regular回退；实际行高、字形光栅与原版仍需游戏对照。
- SearchPage已修正原异步行为：第一次无完成结果才Searching；后续查询期间保留上一轮结果，交付新结果后才重置滚动。searchedQuery标签与显示内容保持同步；live searchDistance变化会重查同一query。旧UI回调、关闭回调和过期卡片release被取消。
- GlobalResultsRenderer与SearchPage均转发pointerDown/up、wheel、key、pointerMove、rawKey、recording。主线程全局快捷键须先尊重UiPage.recording；真实改键raw press/release要调用SearchPage.rawKey，不能只给Key枚举。TextInputController仍由现有宿主管理，renderer不另造输入系统。

新增 GlobalResultsRendererTest 已通过6组/616断言，直接运行真实CardRenderer及EntryRows，仅canvas/host边界替换为记录fake。覆盖物理宽度/坐标/gap、真实卡片开关收藏隐藏/打开/取消、真实菜单reset、owner锁定与主开关例外、accordion whole/child、多owner同localID、实际keybind录制/释放、等待/空结果/滚动/裁剪/缺卡片错误。SearchParityTest现在10组/22315断言，pages夹具提供真实UiPreferences；没有吞ctx.preferences()空值或放宽生产契约。

命令仍为 search/run-tests.ps1；输出build/search-tests/global-results.txt与合并behavior-results.json。此结果是中立行为/几何证据，不是游戏视觉、字体光栅或14组合运行证据。主线程已接index/source单例、ConfigView MODS query切换与实际clientExecutor；本侧只静态核对该接线，游戏验收由主线程负责。


## 冻结前剩余精确项

截至本侧最后读取（2026-11-24）：

- SkijaConfigService.startMigration用containsKey(MARKER)跳过，会连partial一并跳过；应仅对已确认applied做自动跳过，partial保留补迁入口。已向主线程报告，不越权改宿主。
- initializeSearch的SettingsSnapshot默认已经产生mod::muonium.hud，另一个source又注册同ID，当前first-wins会明确duplicate诊断；去重复或Owner.modCard=false须由主线程定。
- 初次immutable searchSnapshot已注册；未核实语言/配置schema/profile/HUD/keybind后重捕获与各SearchPage.rows/index.invalidate路径，旧标签/身份可陈旧。
- searchIndex、MigrationService与每screen SearchPage.close/leave在全局shutdown/移除时的实际归属需确认；中立类退出逻辑已有测试不等于宿主已调用。
- 新Profile名称到UUID存储由Franklin补；旧音乐是CURRENT_CAPTURE_BASELINE，非旧信封恢复。
- 字体500数值入口已静态接通，实际字体metrics/像素光栅、动画/鼠标输入、14组合均未由本侧运行。

这些是精确待确认范围，不将569项pending解释为569个失败功能。之后主线程如已修复，应以其最新差异/测试替换这些快照结论。

## 2026-11-24 主线程收尾复核

冻结后重新执行 run-tests.ps1，SearchParityTest 10 组/22,315 断言、GlobalResultsRendererTest 6 组/615 断言通过。重新生成行为报告并更新五项中立证据的哈希，解决 SettingsPage/LibraryMods 依赖记录过期；status 保持 pending，不据此声称游戏或逐页视觉通过。最终十四目标构建和已知差异见 人工验收交接.md。
