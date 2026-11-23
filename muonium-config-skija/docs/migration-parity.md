# 旧 OneConfig 数据迁移契约与验收

核对日期：2026-11-24。只读原仓 HEAD：bdaa0eb7f0bdcbbd9d8ceee2ccd748fdcb36503b。
本任务实现新工程 host/migration/**，没有改 MuoniumHost、MuoniumProfiles、UiStateStore 或主项目配置；主线程已在SkijaConfigService接全局服务与client应用，冻结前静态核对Snapshot.apply同时保存新侧偏好/HUD并runtime.reload；本侧没有运行真实客户端迁移。

## 当前可用与证据层次

| 层次 | 状态 | 证据与限制 |
| --- | --- | --- |
| implementation | 已实现数据转换与 worker/client 服务 | 旧 UI 偏好、主题/强调色、模组收藏/隐藏/顺序、默认及命名 profile 的安全 UI 快照、已映射 HUD 类型与锚点；不把未知字段当成已迁移 |
| automated | Java 17 集成编译；8 组 / 75 条断言通过 | 根/活动 profile 隔离、JSON/YAML/TOML 数据、重复迁移、旧字节不改、异步/关闭、类型未知诊断、HUD target 文档 roundtrip |
| existing-data read-only | 26.3 Fabric 当前 run 读取成功 | 6 个白名单文件 → 60 项偏好、1 份默认 profile 快照、0 HUD，诊断 0。只输出计数/诊断码，不输出个人配置值或运行数据指纹 |
| runtime / human | pending | 主线程启动应用路径已在源码接通；本侧未运行真实客户端应用、未证明14组合迁移/视觉/输入完全一致 |

任何 pending 都是证据待齐，不是失败功能数量，也不阻止主线程生成测试包。此处没有提交、发布或启动游戏。

## 已精读并核实的旧 schema

路径均相对只读 MuoniumConfig-src/muoniumconfig-common/。

| 原源码 | 已核实契约 |
| --- | --- |
| src/main/java/org/polyfrost/oneconfig/api/config/v1/ConfigManager.java:76-81,160-162,368-413,467-474 | internal=run/oneconfig；root active=run/config；命名 active=run/profiles/<name>；oneconfig/profiles.json 的 activeProfile、favoriteProfiles、profileIcons 等 |
| ConfigManager.java:1042-1098,1253-1281 | profileIcons 元素为 name=icon；profile 名禁止 .、..、路径分隔，最终解析必须留在 profiles 根内 |
| src/main/java/org/polyfrost/oneconfig/api/config/v1/serialize/impl/NightConfigSerializer.java:75-103,139-153 | Property 为原始 scalar；Tree 为嵌套对象；对象带 class 字段交 ObjectSerializer；JSON/TOML/YAML 都是有效落盘格式 |
| src/main/java/org/polyfrost/oneconfig/api/config/v1/serialize/ObjectSerializer.java:173-178,216-227 | enum={class,value}；类型 adapter 输出可包在 {class,value}；迁移只把它们当数据，绝不反射恢复类 |
| src/main/java/org/polyfrost/oneconfig/api/config/v1/serialize/adapter/impl/PolyColorAdapter.java:42-106 | RGBA list/number/hex；丰富值 rgba/chroma/chromaSpeed/gradient，再由 ObjectSerializer 包 PolyColor class |
| src/main/java/org/polyfrost/oneconfig/api/config/v1/serialize/adapter/impl/OneConfigKeybindAdapter.java | keyCodes/mouseBtns 为稳定 key.keyboard.* / key.mouse.* 名称，mods=SHIFT1/CTRL2/ALT4/META8，durationNanos 需明确支持；旧 raw numeric 需要 loader/lane codec |
| src/main/kotlin/org/polyfrost/oneconfig/internal/ui/api/ModIdStore.kt:17-39；ModFavorites.kt；ModHidden.kt | oneconfig/favorite-mods、hidden-mods 每行一 ID，空行丢弃 |
| src/main/kotlin/org/polyfrost/oneconfig/internal/ui/api/ModOrder.kt；ModSortStore.kt | oneconfig/mod-order 每行一 ID，mod-sort 第一非空行 custom/alphabetical |
| src/main/java/org/polyfrost/oneconfig/internal/ThemeConfig.java:10-20 | themes.json: activeTheme、accentColor、animations；四个已登记主题 |
| src/main/java/org/polyfrost/oneconfig/internal/OneConfigConfig.java:849-873 | 旧 openingBehavior>=0 覆盖为 rememberLastPage：2→always4，3→timeBeforeReset>37.5?2:1，其它→0 |
| src/main/kotlin/org/polyfrost/oneconfig/api/hud/v1/Hud.kt:1363-1462 | tree ID=huds/<file>，动态实例带随机前缀；section/relativeX/Y/posSchema、growth/anchor、font/style、背景/显示等逐字段持久化 |
| src/main/kotlin/org/polyfrost/oneconfig/api/hud/v1/HudManager.kt:1359-1360,1427-1455 | active manager gatherAll(huds)；hudClass 对 provider 类型；OneConfigHudCompat 不从此重复加载；无 section 的旧记录走 absolute x/y |
| src/main/kotlin/org/polyfrost/oneconfig/api/hud/v1/TextHud.kt:211-239 | Simple 使用 text_hud.yml、文本字段 it；DateTime 使用 date_time_hud.yml、template |
| src/main/java/org/polyfrost/oneconfig/api/config/v1/backend/impl/FileBackend.java:249-252 | HUD Tree ID 为相对配置根的完整路径，保留扩展名，Windows 分隔符转 / |

实际检查 run/oneconfig、run/profiles、run/config 与 config/huds 的目录结构后，仅读取以上已核实文件。没有递归读取 config/muonium、账号数据库、cookie、其他 mod 配置、备份或 .corrupted 文件。

## 接线 API

包：com.muoniumplayer.configskija.host.migration。

- LegacyMigration.read(Request)：阻塞的 worker 入口，返回 immutable Snapshot。Request 必须在 client 捕获布局轴 viewportWidth/viewportHeight、真实 guiScale，以及 physicalPerUnit（物理像素/布局单位）；可选 hudTypes 为旧 hudClass→已注册 text/date/time/icon 映射，knownModIds 可限制实际存在的卡片，includeProfiles 控制是否转换所有安全 profile。
- Snapshot.preferences()/hud() 是旧活动配置对应的中立数据；Snapshot.profiles() 包含默认根（legacyName 为空字符串）及各命名 profile 的安全 UI/HUD 快照，未激活的根配置不会被覆盖或丢弃。
- Snapshot.issues() 带 code/file/detail；Snapshot.filesRead() 仅计数，不存文件内容或运行指纹。未知 provider/格式/位置 schema 不会伪装成迁移成功。
- Snapshot.apply(UiPreferences target,HudDocument existing,Consumer<HudDocument> applyHud)：仅在 client 调用，现有新侧键与 HUD ID 胜出。先提交 detached HUD，成功后合并偏好并写目标 marker migration.oneconfig.v1=applied；仍有未解决项时为 partial，不能据此跳过后续补迁。它不写任何旧文件，也不自行落地新 profile 文件。
- MigrationService(clientExecutor)：内部单 daemon worker；start(request,onClientSnapshot,errorHandler) 的 future 在 client 应用结束后完成。可注入 ExecutorService（由调用方拥有），invalidate()/close() 会阻止过期/退出后的回调应用。

示例：

~~~java
// Main thread owns this singleton/lifecycle and passes a real main-thread executor.
MigrationService migration = new MigrationService(clientExecutor);
LegacyMigration.Request request = new LegacyMigration.Request(
    runDirectory, layoutWidth, layoutHeight, realGuiScale, physicalPerUnit);
// physicalPerUnit = physicalWidth / layoutWidth; use the host's verified physicalPerLayout().
migration.start(request, snapshot -> {
    // uiPreferences should contain values actually loaded from new disk state, not a map filled with defaults.
    // Otherwise default values would incorrectly mask legacy values under the new-data-wins policy.
    LegacyMigration.Applied applied = snapshot.apply(uiPreferences, currentHudDocument, newHud -> {
        host.saveHudDocument(newHud); // new destination only; client snapshot/dispatch follows host contract
    });
    // Main thread may offer/import snapshot.profiles() as new-side UI/HUD-only documents.
    // Do not attach a music/auth blob or copy old profile directories wholesale.
    // Report snapshot.issues(); re-create/search-invalidate model snapshots after applying.
}, error -> host.error("读取旧界面数据", error));
// on shutdown / changing the intended owner:
migration.close();
~~~

主线程须在新侧偏好异步 load 完成后启动，避免空 map/default 竞态；应用前结束旧 owner 的编辑事务，应用后清局部 history、重建搜索文档与 UI/HUD 运行快照。Snapshot 不持有原版对象、native handle、反射类或游戏 API。

## 转换范围与边界

- 偏好严格使用当前 AppearanceSchema.PREFERENCES 已定义键；number 范围验证，choice 按 choices.size()-1 而非滑块 max；Boolean 类型必须真实 Boolean。旧 openingBehavior 转换照原规则，原文件不置 -1、不保存。
- key.keyboard.* 改写为中立命名 chord；key.mouse.* 变 MOUSE:n；modifiers 按原位值。未解析的数字键码、未知绑定类型、非零 durationNanos 返回 unresolved-keybind，并保留旧文件；不猜 GLFW 与 SDL 数码等价。
- 主题只接受当前四主题，输出 activeTheme/name 与 theme/id。强调色保留 ARGB、Chroma、速度、gradientEnabled/gradientArgb；旧 RGBA 按真实顺序转换，class 只用于核对 wrapper。
- 收藏/隐藏模组用 mods.favorite.<old-id>/mods.hidden.<old-id>；顺序 mods.order 保留换行；profiles.favorite/name 和 profiles.icon/name 只为元数据。主线程需映射旧方案名到新 profile ID 后再保存；这不是复制旧 Music profile。
- 旧“常用”页面 FavoritesTree 是宿主提供的设置视图，不是用户逐选收藏的持久化表。没有臆造 favorite-settings 文件。宿主音乐/HUD真实设置仍在主 mod 自有配置，迁移不读取/复制其账号或业务 schema；主线程保持既有 host binding。
- HUD 只转换已核实 Simple/DateTime/内建测试文本日期时间类或 Request 明确给出的注册类型；未知 provider 报 unsupported-hud。OneConfigHudCompat 报 host-hud-authoritative，不在新文档再造一份主项目 HUD。
- HUD 目标 ID 保留旧完整 huds/path.ext；enum 数据按已核实字体/字重/anchor/section 转换；text it→text；支持隐藏/锁定、显示场景、背景/阴影、布局、scale、文字、锚定；丢失目标解除 link 并报告。循环/过深 anchor 图整体不应用，旧文件保留。
- section 记录只接受确认过的 posSchema1；schema0/missing schema 的 section 记录报错，不猜历史位置意义。无 section 且有 x/y 的旧 absolute 记录按原 loader 路径转换。动态文本度量标为 hud-metrics-pending，等待真实字体测量；不能将夹具尺寸 roundtrip 当作实际渲染位置通过。
- 当前 MHUD1 没有原来的独立 layoutRefW/H 契约；跨旧布局参考尺寸的等价性仍需真实 client 对照。合并 link 的 axis 拆分及未知 provider 自定义内容不宣称已覆盖。

## 文件与凭据边界

只读 allowlist 是 oneconfig/profiles.{json,yml,yaml,toml}、favorite-mods、hidden-mods、mod-order、mod-sort；每个根/命名 profile 的 oneconfig/themes 及 huds/ 下已匹配文件。目录与文件拒绝 symlink，所有路径在请求 run 根内；profile 名拒绝路径分隔与 :；有大小/深度/项目上限。多个格式同时存在报 ambiguous-format，不静默猜覆盖顺序。

LegacyData 只解析数据：JSON 和已核实落盘用到的安全 YAML/TOML 子集；不做 Class.forName/ObjectInputStream/YAML tag 实例化。YAML anchors/tags/多行 literal、复杂 TOML array table 等不支持时明确 invalid-data，旧文件不改。字体/字重等值不是从 class 任意加载。

没有把任何旧原文件字节、token、cookie、账号、密码或完整配置序列化进新 profile。未知键丢弃，已知 UI 值逐个构造。运行文件也不进入源码/资产指纹。tests 的 SHA256 只比较新生成的无敏感夹具，服务实现没有对真实旧文件计算指纹。

## 验证命令与结果

~~~powershell
.\src\test\java\com\muoniumplayer\configskija\migration\run-tests.ps1
# Optional: read-only allowlisted real files, output counts and diagnostic codes only:
.\src\test\java\com\muoniumplayer\configskija\migration\run-tests.ps1 -InspectRun 'E:/MUSIC/26.3/fabric/run'
~~~

结果：Java17 --release17 编译通过；MigrationParityTest 8组/75断言。输出仅本工程 build/migration-tests/，含 results.txt/behavior-results.json 与无敏感 fixtures。没有 Gradle、游戏、部署或旧数据写入。

主线程真实单例/启动/保存/runtime.reload路径已静态接通；旧命名profile→新UUID落地由Franklin补。仍缺真实client动态HUD/字体测量、数字输入码兼容及各运行组合验证；partial标记不能因containsKey直接跳过补迁。主线程应先把 issues 暴露为可审阅迁移报告，保留旧文件供回退，再由用户/游戏验收确认后追加证据。


## Profile 音乐基线接入的准确说明

2026-11-24 已与 Library 队友核对：本服务 Snapshot 没有读取任何 muonium-music-state.json，也不携带旧 Music blob。Library 新 host/library/LegacyProfileImport 输入只接 legacyName/preferences/HudDocument；MuoniumProfiles.importLegacyProfiles 为每个旧方案（含root）分配/复用新UUID，用一次当前 PresetConfigBridge.captureLocalState 作为明确baseline，标记 legacy.import.musicOrigin=CURRENT_CAPTURE_BASELINE 并逐档记录未恢复旧音乐说明。Result 的 oldNameToId 供主线程迁移收藏/图标映射；这不是旧音乐恢复证据。

旧音乐信封路径与源码事实：MuoniumMusicStateProfiles.kt:42,91-106,196 → run/profiles/<name>/muonium-music-state.json；空profile根不写信封，保留主项目现有全局配置。MuoniumPresetBridge.kt:342 的 MusicStateEnvelope.looksValid 只检 format=muonium.profile.music、version1、localOnly=true、state对象；它明确是轻校验。主PresetConfigBridge.java:83-106才经PresetService/PresetEngine.restoreState处理真实LOCAL信封。PresetEngine.java:55-70 captureState包含分享排除项，PresetDomains.all列九个音乐/外观域并明确禁止账号/provider/cache域。因此不能仅凭形状像信封就复制任意state或宣称无凭据安全；本轮未拿到/恢复旧音乐快照，应将CURRENT_CAPTURE_BASELINE的差异向人工验收清楚展示。


## 冻结增量：GUI/布局/物理单位分离

2026-11-24 与 HUD 队友逐项核对并完成本写集：Request 增加 physicalPerUnit；五参为 (runDirectory,layoutWidth,layoutHeight,realGuiScale,physicalPerUnit)，完整八参再接 hudTypes/knownModIds/includeProfiles。旧四参、七参保留，默认 physicalPerUnit=guiScale，仅适用于viewport原本就在纯GUI轴的旧调用。

源依据：旧Hud.kt:414–431 的 section relativeX/Y 使用GUI单位；:525–572 的相对/anchorOffset写入也是GUI标量；:967–971 的 effectiveScale 是GUI轴的 custom 或 custom/gui；HudManager.kt:1083 外层再乘真实GUI scale。新迁移实际调用已落盘的 HudNode.frame(realGuiScale,physicalPerUnit)，尺寸在布局轴为 customScale*(useGuiScale?realGuiScale:1)/physicalPerUnit。relativeX/Y与anchorOffset保持GUI标量，不在导入前预乘ratio；旧无section的absolute x/y则只乘realGuiScale/physicalPerUnit一次再调用absolutePosition。

新增 MigrationUnitsTest：3组、560矩阵组合、5106断言通过。540组为5种GUI scale×6种physicalPerUnit×9个section×2种useGuiScale，独立按原公式对照物理像素宽高/位置；20组验证旧absolute坐标只换算一次及anchorOffset不预乘。保留旧构造语义与MHUD roundtrip。此矩阵证明迁移数据/静态HUD坐标，不代表真实runtime锚链、动态字形或游戏渲染验收。

独立入口 migration/run-tests.ps1 同时通过原8组75断言；新增结果在build/migration-tests/units-results.txt，并进入合并behavior-results.json。未跑Gradle/游戏，未改旧文件或生成运行数据指纹。

冻结时主线程的SkijaConfigService.startMigration仍传四参且viewport取RenderSystem布局宽高，因此必须补第五参WindowAccess.physicalPerLayout()（等价physicalWidth/layoutWidth）。runtime的Frame也需同一physicalPerUnit，HUD队友负责Frame.withPhysicalScale及链路。不应把旧兼容四参传布局轴后当作单位已经正确。mainService globalSearch构造参数已齐；本侧没有修改宿主接线文件。
