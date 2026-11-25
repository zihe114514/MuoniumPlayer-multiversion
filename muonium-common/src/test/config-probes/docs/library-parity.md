# 模组列表、Profiles、键位与 Credits 对齐记录

核对日期：2026-11-24。原版只读树：`E:/oneconfig lite/MuoniumConfig-src`；实现：`E:/MUSIC/muonium-config-skija`。本文件只描述本次负责的页面和键位触点。本页面分工未启动游戏、未运行 Gradle、未自行提交/发布，未修改原仓/主项目既有改动。后续提交、游戏无人值守验收和实装按用户最新授权由主线程执行。游戏内视觉、交互与动画由主线程按用户最新授权验收，本分工不自行启动游戏。

## 入口与边界

`ui/pages/LibraryPages.java:8` 提供四个 `public static UiPage` 工厂：`mods()`、`profiles()`、`keys()`、`credits()`。Shell 按路由持有实例，离页调用 `leave(ctx)`；绘制和输入均使用同一实例。键位只挂原页面入口，不增加侧栏分类，也不注册第二个 H。页面不复制音乐业务；设置页跳转继续使用主助手的 SETTINGS renderer。

`ctx.host().profiles()/presets()` 仍是唯一业务服务。模组来源可由 `LibraryServices.ModCatalog.modCards()` 提供；没有额外来源时只按宿主已经核实的设置树/HUD提供者建立 MuoniumPlayer 设置卡及两个 HUD 卡，不将设置分类当作模组。

键位宿主接入：

```java
import com.muoniumplayer.configskija.host.keys.MinecraftKeyBindings;
import com.muoniumplayer.configskija.host.keys.NativeKeyAccess;

private final KeyBindings keys = new MinecraftKeyBindings(new NativeKeyAccess());
public KeyBindings keyBindings() { return keys; }
```

宿主实现 `LibraryServices.Keys`；只编入对应主仓车道的 `src/versions/<lane>/java`，与 `src/main/java` 一起构建。七个 `NativeKeyAccess` 同名，不应同时加入 classpath。`PageContext.nativeKeyCode()` 传宿主 GuiScreen 已映射的 legacy LWJGL2 编码；按键录制事件先交当前页面，才处理全局 Esc/搜索快捷键。多键绑定可由 `KeyBindings.InputState` 轮询释放，或调用 `KeysPage.keyReleased(ctx,legacyCode)`；本机原生 MC 映射本来是单键，按下即写入。

页面输入接入还须遵守：`textField` 发现 `TextEdit.focused=true` 时接管活动编辑器（New/Rename/Import请求焦点）；其命中按当前 `ctx.content()` 子滚动区裁剪。滚动列表切换搜索导致编辑卡不可见时页面取消编辑；Enter仅在对应输入框有焦点时提交。Shell在文本框聚焦时也须先允许当前页面消费Enter/Esc；录制状态需优先于鼠标3/4导航。模组卡本身已从hit动作启动startDrag，无需Shell额外先调pointerDown。

## 原版证据与几何

下面路径省略只读原仓中的 `muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/` 前缀。实现路径省略 `src/main/java/com/muoniumplayer/configskija/`。

| 项目 | 原版依据 | 已实现位置/尺寸 | 局部行为检查 | 游戏人工验收 |
| --- | --- | --- | --- | --- |
| Shell 内容坐标 | `internal/ui/shell/Shell.kt:148`，PageGutter=25 | 输入契约 Shell 1391×700、侧栏264、默认内容(289,88)，1077宽 | fixture 同坐标，绘制/命中反向变换由 Shell 管理 | 待验收 |
| 模组分类与排序 | `internal/ui/screens/Mods.kt:152`、`internal/ui/screens/ModSortControl.kt:56`、`internal/ui/api/ModCardGrouping.kt:23`、`internal/ui/api/ModOrder.kt:78` | `ui/pages/LibraryMods.java:85`；类别、收藏/隐藏、custom/alphabetical、持久顺序、分组折叠 | 通过；隐藏收藏不泄漏到 Favorites；排序不覆盖未知槽位 | 待验收 |
| 模组网格/卡片 | `internal/ui/screens/Mods.kt:286`、`:440`、`:487`、`:606` | 四列，间距19；255×140；页脚36；操作24×24并排起点(4,4)；指示环12、点6；见 `LibraryMods.java:25` | 通过；滚动命中限于卡片 viewport，拖动不吞按钮 | 待验收 |
| 模组拖动 | `internal/ui/screens/Mods.kt:234`、`:387`、`:404` | 5dp启动阈值、分组内换位、收藏边界、覆盖卡、虚线槽位、临界阻尼回位；`LibraryMods.java` 的 pointerDown/drag/drop | 通过自定义排列、字母序保留原排列；未做真实鼠标采样精度比较 | 待验收 |
| Profiles 分类与本地CRUD | `internal/ui/screens/Profiles.kt:108`、`:128`、`:278`、`:513`、`:599`、`:722` | 五列；200.2×180；间距19；内联创建/重命名/复制/图标/删除；`ui/pages/LibraryProfiles.java` | 通过；Default 空id独立处理、重名/空名、克隆命名、激活恢复UI、删除二次确认 | 待验收 |
| Profiles 底部模板横条 | `internal/ui/screens/presets/PresetTemplatesPanel.kt:99`、`:493`、`:566`、`:604` | 区块227高=33工具栏+10间距+184横条；卡190×176，间距19；`ui/pages/LibraryTemplates.java:23` | 通过；仅用户模板有复制/删除，横向滚轮不滚本地网格 | 待验收 |
| 模板详情与域选择 | `internal/ui/screens/presets/PresetTemplatesPanel.kt:758`、`:818`、`:840`、`:1055` | 详情接管主体；按外观/音效分组；全选、只选外观/音效、预检提示最多5条；42×21开关/15dp滑块/On-Off；`LibraryTemplates.java` | 通过；详情外观选择包含opt-in分面；默认选择仍由真实session提供 | 待验收 |
| 模板保存/导入/分享 | `internal/ui/screens/presets/PresetTemplatesPanel.kt:876`、`:971` | 名称输入、保存、保存并复制分享码、分享码/JSON输入、导入后预览、不自动套用；`LibraryTemplates.java` | 通过；保存空名称/空选择拦截；导入不调用apply；删除内联确认；撤销转真实服务 | 待验收 |
| 键位目录/录制 | `internal/ui/keybind/KeybindCatalog.kt:15`、`KeybindGroupCollapseStore.kt:19`、`internal/ui/components/settings/KeybindOption.kt:111`、`:218` | `ui/pages/KeysPage.java`、`KeysRecording.java`；分组、搜索、折叠持久化、单键/鼠标录制、多键释放提交、Esc清空、默认重置 | 通过；任意原始物理码，鼠标左键0，modifier-only作为literal，离页不误写 | 待验收 |
| 冲突提示 | `internal/ui/components/settings/KeybindConflicts.kt:30`、`api/ui/v1/keybind/OneConfigKeybind.kt:120` | 精确组合/修饰符集合相等；忽略原版已列的debug/spectator上下文；颜色 `0xFFE0524F`；`model/KeyBindings.java` | 通过；冲突名称来自真实条目，不假造备用键 | 待验收 |
| 搜索字段/模糊算法 | `internal/ui/search/DefaultSearchProvider.kt:12`、`:85`、`internal/ui/screens/Profiles.kt:450`、`presets/PresetTemplatesPanel.kt:595` | `LibrarySearch` 只调用共享 `SearchIndex.matches/trimQuery`；mods title/tags；profiles name/id/icon；templates name/description；keys title/description/tags/category/subcategory/id/path | 通过；Search Distance默认2、可设0；不做多词AND、不把modTitle/键名混进键位字段 | 待验收 |
| Credits 全文/版本 | `internal/ui/screens/Credits.kt:58`、`:98`、`:142` | `ui/pages/CreditsPage.java`；品牌34×22、标题26sp、章节24sp、正文13sp、许可12sp；全部作者与许可文字保留 | 通过；宿主版本与UI版本分别取 `Information`，每帧读取，不缓存初次缺失 | 待验收 |
| 圆角与局部动画 | `internal/ui/themes/Radii.kt:23`、`internal/ui/themes/Impls.kt:100`、`internal/ui/screens/Mods.kt:387` | chip8、card16、popup16；Minecraft主题直角/原SVG覆盖；`LibraryMotion`使用解析临界阻尼，默认刚度1500，卡片换位400，连续保留速度 | 已渲染静态端点；未做逐帧像素差/真实帧时序比较 | 待验收 |
| 忙碌遮罩 | `internal/ui/screens/Profiles.kt:406` | Shell全区域55%黑遮罩、原版两行文案、输入拦截；`LibraryProfiles.busyOverlay` | 已实现；需宿主准确暴露busy生命周期 | 待验收 |

原版 `ModConfig.kt:22` 的职责是跳转/锁定真实配置树；本次通过 `Card.open` 跳到 Shell 的真实 SETTINGS/HUD页，不复制主助手维护的设置控件。

## 宿主能力接入与明确差异

页面绘制和交互已经落盘；以下能力必须由现有宿主服务提供。页面阶段没有改 `ConfigHost`、`PageContext`、`ConfigView`、`Theme` 或 Gradle脚本；2026-11-24获追加授权后扩展了 `MuoniumProfiles`、`MuoniumPresets`，具体实现与验收见下节。主助手可直接实现 `LibraryServices`，不需在UI层猜MC API。

下表描述未接入provider时的fallback；追加的HostLibraryExtras已覆盖下节列出的真实能力。

| 默认函数/标志 | 页面用法 | 未接入表现 |
| --- | --- | --- |
| `Information.playerVersion()/configVersion()` | Credits分别显示两个真实版本 | 缺失版本省略，不写死发布号 |
| `ProfileExtras.capabilities()` | 接受 `controls`、`folder`、`export`、`icons` | 对应按钮有说明且不可点 |
| `separateControls()/separateControls(boolean)` | Separate controls | 未证明存在的能力不写入假的UI布尔值 |
| `openProfilesFolder()/exportProfile(id)` | 原Open folder、Export/share菜单 | 不启动空操作 |
| `profileIcon(id)/profileIcon(id,icon)` | 原10图标选择器 | 不宣称已更改业务档案 |
| `profileKey(id)` + 可写对应id的 `Keys.keyBindings()` | 配置卡片内联录制快捷键 | null时不虚构配置键；原生backend只改已注册MC键位，不能替代ProfileKeybinds调度器 |
| `PresetExtras.busy()` | 真实模板操作期间遮罩 | 默认false；页面本身不制造异步任务 |
| `facetDescription(id)/cardSummary(id)` | 分面说明/模板小结 | 没有分面说明时不编造；小结可用现有Card.description |
| `changedKeys()/previewOk()` | 原改动计数/兼容状态/套用按钮计数 | 默认-1/true，只给备份与选择提示，不宣称已确认改动数 |
| `canOpenFolder()/openPresetsFolder()` | 模板打开目录 | 不可用 |
| `canImportFile()/importPresetFile()` | 模板选择文件 | 不可用；分享码/JSON文本导入仍能使用 |

与原版尚不能声明1:1的部分：

1. 原版ConfigManager的配置档案是整套配置目录及profile keybinds，本工程现有Profiles服务是主项目可信本地快照。页面CRUD/激活真实接通，已有原版档案的迁移由专职迁移分工处理；默认档案、配置独立键位与本地ZIP导出已在HostLibraryExtras落地，但归档只包含本工程可信本地快照和可用的原生键位；UI不得把UI偏好冒充完整档案格式。
2. Favorites/Hidden/Order和页面配置收藏使用现有 `UiPreferences` 的 `mods.* / profiles.* / keys.collapsed.*` 存储。没有自动导入原OneConfig的 `favorite-mods`、`hidden-mods`、`mod-order`、`keybind-collapsed` 文件；完整档案导出若要求这些值，应由宿主包含UI快照。
3. 原生键位backend覆盖当前 `options.keyMappings`；本工程Profile快捷键已有全局轮询调度器（宿主需接tick），第三方OneConfig属性键位和额外ModCatalog仍需宿主提供，不通过反射/镜像注册伪造。无法表达为legacy输入的原生SCANCODE/特殊键会带原因只读，保留真实显示名；没有声称所有物理键布局都可改。
4. card描边以32个裁剪带模拟原版纵向渐隐，文本行高目前通过Painter约定计算；未完成Compose与Skija文字度量和抗锯齿的逐像素等价证明。keybind分组的来源布局在只读树中可核实目录/组件/折叠规则；本次未拿旧游戏截图逐像素验证分组页。页面组件的描边、字号、间距有源码依据，最终字体度量、实际HUD卡片预览、绘制栈资源、光影、失焦/输入法、屏幕边缘拖动、缩放和动画时序仍由人工对照原版验收。
5. 小图标缺失按原组件不绘制处理；原 `PresetTemplatesPanel` 的 `add` 资源不存在，本次未擅自补成另一图标。Credits第三方文案按原版保留，包括Compose来源说明；并不表示本工程运行时重新引入Compose。
6. 没有运行目标完整包构建、启动游戏、证明Mixin触发或元数据最低依赖兼容。`javac` 通过不能替代上述结论。

## 2026-11-24 宿主扩展实现

`host/library/HostLibraryExtras.java` 已实现 `ProfileExtras`、`PresetExtras`、`Information`、`ModCatalog`；`MuoniumProfiles/MuoniumPresets` 现为public并保留原无参构造。此分工没有改 `MuoniumHost`、`SkijaConfigScreen`、迁移层或MC键位后端。

### 构造与生命周期

```java
// mainExecutor 排到客户端主线程；workerExecutor 负责阻塞IO。
// nativeKeys 是尚未合并 profile:* 条目的原生backend，避免递归。
HostLibraryExtras extras = new HostLibraryExtras(
    profiles, presets, host, preferences,
    mainExecutor, workerExecutor, new DesktopLibraryFiles(),
    nativeKeys, configVersionSupplier
);
KeyBindings keys = extras.keyBindings(); // native映射 + 本地profile快捷键
```

- 主线程每个client tick调用 `extras.tick(screenOpen)`；`screenOpen` 为任何游戏界面打开。按下沿在所有界面内都被记住，退出界面仍按着的键不会误触发；配置改变不清held集合，不重复切换。不注册/镜像原生映射，H入口仍只有原宿主那一个。
- 服务在设置界面关闭后继续存活，才有全局profile快捷键。进入Profiles路由、外部迁移完成或目录变化后调用 `extras.refresh()`；不在render中重复扫磁盘。
- 开屏/关屏不能关闭extras/profiles：服务常驻host；screen dispose只清理/刷新页面session。只有真正shutdown才执行下述flush/close。
- 真正shutdown使用 `extras.flush().whenComplete((ignored,error) -> { /* main */ extras.close(); })`；先处理error并保留诊断，不能在主线程join/get。flush等待最后一次元数据写入并checkpoint当前档案。正忙时future显式失败，宿主应在操作完成后重试或明确取消。直接close用于取消，递增generation并拦截迟到回调，不能宣称未完成checkpoint已保存。
- `LibraryTemplates.save()` 必须把 `busy()==true` 的异步SAVE状态当作等待，不能把当帧仍为SAVE当作失败。完整Shell输入分发仍由主线程接入。

### 服务实际行为

| 能力 | 实现 | 线程与过期约束 |
| --- | --- | --- |
| 默认与激活档案 | 空ID对应.default.properties；UUID档案保持version1；.state.properties原子记录active；重新构造服务读取真实active | 扫描/读写worker；快照/恢复main；active落盘失败回滚音乐/UI/controls |
| 档案生命周期 | 切换前捕获并保存离开档案，真实PresetConfigBridge恢复后激活；active删除先切回Default；复制active捕获实时状态 | Future结果main；request/profileGeneration/closed检查 |
| 图标 | 原10图标白名单，写入档案icon字段；复制保留、重命名保持UUID | 原子worker保存，成功后更新图标快照 |
| Separate controls | 捕获宿主KeyBindings可编辑映射的legacy编码；开启随Profile恢复，关闭保留当前键位；共享期间不删旧独立键位 | 现场MC读写由native backend在main完成；库仅存冻结字符串；不宣称覆盖鼠标灵敏度等未提供项 |
| 配置快捷键 | library.properties保存多键/鼠标/修饰符；profileKey返回真实Entry，keyBindings合并可写接口；删除档案清理对应key | 不镜像MC键；主线程tick读InputState，关屏后按下沿激活真实Profiles |
| 导出ZIP | 系统保存对话框；ZIP含profile.properties和格式说明，包含选中档案music/UI/icon/已有controls/profileHotkey | active导出main捕获实时状态；worker写临时ZIP再原子替换；取消/过期不发布结果；非旧OneConfig全目录备份 |
| 打开目录 | 配置目录与真实PresetRepository.userDir | 能力由JDK Desktop实际探测；OS打开/建目录worker；失败有状态与host.error |
| 模板目录/保存/复制/删除 | 缓存真实StylePreset目录；main只捕获service.snapshot，worker进行repository保存/目录扫描/分享编码/删除；share回main写剪贴板 | busy/closed/request/profileGeneration约束；每帧不读目录 |
| 模板预检 | changedKeys读session.previewChanges；previewOk读session.stateJson.previewFailed；facetDescription读Facet.description；cardSummary用真实preset.facets | main纯状态，不从文案猜计数 |
| 文件导入 | worker发起JDK FileDialog，AWT线程拥有modal UI；选定文件worker有界NOFOLLOW读取、真实parse/repository.saveUser；main enterApply预检 | 导航/profile/close generation拦截过期选择；导入不调用apply |
| Information/ModCatalog | 宿主版本取MuoniumPlayerExtension.VERSION；UI版本取supplier/包ImplementationVersion（缺失省略）；设置卡依真实categories，HUD卡遍历host.huds | 不造假mod/档案，不复制音乐业务 |

LibraryIo统一有界UTF8读取、同目录临时写+原子替换和ZIP输出；ProfileFiles保留旧version1兼容；ProfileChords只存已核实legacy码，损坏元数据明确拒绝。测试数据写本工程build/library-host-data。

### 核心API边界

1. 实际PresetService.apply/undo将main配置事务和内部备份IO封在一起，当前没有可拆开的异步事务API。本分工保留这两个核心事务在main，未把MC/音频/资源写入挪到worker，也未复制套用/回滚算法。因此不能声称这两条核心内部路径已全部异步IO；需核心拆分事务接口后才能进一步处理。
2. controls恢复委托原生KeyBindings.set。若backend内部每次set都Options.save，其内部磁盘写入仍在宿主线程；library不在worker调用MC来掩盖问题。批量修改/统一保存由主线程掌管的MC backend处理。捕获恢复仅覆盖明确可编辑映射。
3. 本分工未打开真实OS文件选择器；自动测试注入worker选择替身验证时序/取消。JDK FileDialog由主线程后续验收。文件开始原子提交后的写入不会被伪称为可回滚；generation保证迟到结果不改新页面/新配置。
4. 原目录迁移仍由Popper负责，本分工不重复转换。可信本地ZIP不能当作安全分享模板；旧全目录Profile与本工程music/UI快照范围不同。

### 宿主验收

```powershell
.\src\test\java\com\muoniumplayer\configskija\library\run-host-library-tests.ps1 -AllCombinations
```

结果：页面144项断言通过；HostLibraryTest 248项检查通过；新增宿主扩展/两个服务在14份实际compile-classpath上均局部编译通过。宿主测试在26.3 Fabric真实依赖下，使用真实PresetEngine/PresetRepository与隔离档案验证main/worker顺序、预检/导入不套用、controls隔离与共享/重新开启、active重开、长按边界、flush/ZIP、close/profile generation拦截。队列替身验证调度，不等于游戏线程实测。宿主声明分别javap记录于build/library-host-tests/host-api-evidence.json（14组合）。

追加修改文件：

- src/main/java/com/muoniumplayer/configskija/host/MuoniumProfiles.java
- src/main/java/com/muoniumplayer/configskija/host/MuoniumPresets.java
- src/main/java/com/muoniumplayer/configskija/host/library/HostLibraryExtras.java
- src/main/java/com/muoniumplayer/configskija/host/library/DesktopLibraryFiles.java
- src/main/java/com/muoniumplayer/configskija/host/library/LibraryFiles.java
- src/main/java/com/muoniumplayer/configskija/host/library/LibraryExecutors.java
- src/main/java/com/muoniumplayer/configskija/host/library/LibraryIo.java
- src/main/java/com/muoniumplayer/configskija/host/library/ProfileFiles.java
- src/main/java/com/muoniumplayer/configskija/host/library/ProfileChords.java
- src/test/java/com/muoniumplayer/configskija/library/HostLibraryTest.java
- src/test/java/com/muoniumplayer/configskija/library/run-host-library-tests.ps1
- src/test/java/com/muoniumplayer/configskija/library/run-library-tests.ps1（适配完整中立model/ui依赖编译）
- 本文档。

## 十四组合键位API证据

全部使用 `build/host/<主仓lane>/<loader>/compile-classpath.txt` 中实际解析的MC、loader和宿主classes。`javap -p -c -constants` 独立检查以下触点；源码/反编译只作定位，未将一侧API代证另一侧。

- `net.minecraft.client.KeyMapping`：`getName():String`、`getCategory():String/Category`、`getDefaultKey():InputConstants.Key`、`getTranslatedKeyMessage():Component`、`saveString():String`、`setKey(InputConstants.Key):void`、`setDown(boolean):void`、`static resetMapping():void`。字节码确认saveString读取当前key的getName；setKey只写当前key，随后需重建查找表。
- `InputConstants.getKey(String):Key` 只解析已有 `mapping.saveString()`；`InputConstants.Type.MOUSE/KEYSYM/KEYBOARD.getOrCreate(int):Key` 的输入来自已验证范围与宿主转换；`Key.getType()/getValue()/getDisplayName()`；`UNKNOWN`。
- `Options.keyMappings:KeyMapping[]`、`Options.save():void`；`Minecraft.getInstance()/options`。所有调用来自当前客户端/UI线程，不注册事件、不创建新的线程或键位。`KeyMapping.resetMapping()` 重建真实索引；MC侧释放状态、重绑、持久化在同一次操作完成。
- `Keyboard.toGlfw(int)/fromGlfw(int)/isKeyDown(int)` 及 `Mouse.isButtonDown(int)` 逐组合核对；legacy修饰键常量为29/157、42/54、56/184、219/220。26.3中方法名toGlfw仍保留历史名，返回SDL scancode，已与Type.KEYBOARD注册/显示字节码核对。
- 每条组合的类声明、当前JAR完整路径、SHA256与编译结论保存在本地验收产物 `build/library-native/key-api-evidence.json`。下表只表示本次键位适配器的声明和局部编译通过，不代表游戏实测或最低依赖通过。

| 主仓车道 | 实际开发loader/API | 键盘枚举；原生左/右/中鼠标 | 类别文本 | Java局部编译 |
| --- | --- | --- | --- | --- |
| 1.20.1 recode | Fabric 0.19.3 / API 0.92.11+1.20.1 | KEYSYM；GLFW 0/1/2 | String → I18n.get | 通过 |
| 1.20.1 recode | Forge 1.20.1-47.4.0 | KEYSYM；GLFW 0/1/2 | String → I18n.get | 通过 |
| 1.21.1 | Fabric 0.19.3 / API 0.116.15+1.21.1 | KEYSYM；GLFW 0/1/2 | String → I18n.get | 通过 |
| 1.21.1 | NeoForge 21.1.249 | KEYSYM；GLFW 0/1/2 | String → I18n.get | 通过 |
| 1.21.10 | Fabric 0.19.3 / API 0.138.4+1.21.10 | KEYSYM；GLFW 0/1/2 | Category.label().getString() | 通过 |
| 1.21.10 | NeoForge 21.10.64 | KEYSYM；GLFW 0/1/2 | Category.label().getString() | 通过 |
| 1.21.11 recode | Fabric 0.19.3 / API 0.141.6+1.21.11 | KEYSYM；GLFW 0/1/2 | Category.label().getString() | 通过 |
| 1.21.11 recode | NeoForge 21.11.45 | KEYSYM；GLFW 0/1/2 | Category.label().getString() | 通过 |
| 26.1.2 | Fabric 0.19.3 / API 0.155.3+26.1.2 | KEYSYM；GLFW 0/1/2 | Category.label().getString() | 通过 |
| 26.1.2 | NeoForge 26.1.2.126 | KEYSYM；GLFW 0/1/2 | Category.label().getString() | 通过 |
| 26.2 | Fabric 0.19.3 / API 0.158.0+26.2 | KEYSYM；GLFW 0/1/2 | Category.label().getString() | 通过 |
| 26.2 | NeoForge 26.2.0.72 | KEYSYM；GLFW 0/1/2 | Category.label().getString() | 通过 |
| 26.3 | Fabric 0.19.5 / API 0.161.0+26.3 | KEYBOARD；SDL 1/3/2 | Category.label().getString() | 通过 |
| 26.3 | NeoForge 26.3.0.16-beta | KEYBOARD；SDL 1/3/2 | Category.label().getString() | 通过 |

精确MC产物与指纹：

- **1.20.1 recode / fabric**：`E:/MUSIC/1.20.1 recode/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-825aaf77bd/1.20.1-loom.mappings.1_20_1.layered+hash.2198-v2/minecraft-merged-825aaf77bd-1.20.1-loom.mappings.1_20_1.layered+hash.2198-v2.jar`；SHA256 `95cce53ddecf560ee52be206073ef0aef1253b2598eb706636e4b0ac9dd982bb`。
- **1.20.1 recode / forge**：`E:/MUSIC/1.20.1 recode/forge/build/moddev/artifacts/forge-1.20.1-47.4.0.jar`；SHA256 `6f96ed7aa8080f37121020f7d2f5a61b0f33cd91aa64ae026e2bba25bf8a65e8`。
- **1.21.1 / fabric**：`E:/MUSIC/1.21.1/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-c7fff42769/1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2/minecraft-merged-c7fff42769-1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2.jar`；SHA256 `1697cd63d09b1acd517add30696cfdf4cf7d340bcb997a065f30ec1f11a49b9e`。
- **1.21.1 / neoforge**：`E:/MUSIC/1.21.1/neoforge/build/moddev/artifacts/neoforge-21.1.249.jar`；SHA256 `ebdcc6b7f680bae388b06e037f183f339049b2cffb75adf98d3e87f2c1b48c4d`。
- **1.21.10 / fabric**：`E:/MUSIC/1.21.10/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-5243ed6022/1.21.10-loom.mappings.1_21_10.layered+hash.2198-v2/minecraft-merged-5243ed6022-1.21.10-loom.mappings.1_21_10.layered+hash.2198-v2.jar`；SHA256 `333cd60d257e7e43b829975c85ce093d53f60036f39ecb5055740236b5861ae0`。
- **1.21.10 / neoforge**：`E:/MUSIC/1.21.10/neoforge/build/moddev/artifacts/neoforge-21.10.64.jar`；SHA256 `21c46c14d60855e73d942af9f0c7f339d8e6c2d83f21d735590455a658d21a23`。
- **1.21.11 recode / fabric**：`E:/MUSIC/1.21.11 recode/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-480d24ca11/1.21.11-loom.mappings.1_21_11.layered+hash.2198-v2/minecraft-merged-480d24ca11-1.21.11-loom.mappings.1_21_11.layered+hash.2198-v2.jar`；SHA256 `e3ed55fd65fd15ce6429eadb85ca85c55364b7ff5f7a53248b73f0635c62f21e`。
- **1.21.11 recode / neoforge**：`E:/MUSIC/1.21.11 recode/neoforge/build/moddev/artifacts/neoforge-21.11.45.jar`；SHA256 `59db735aae0c2a1192fe8200dd53d3e59bedffe182134c9fe129f000a266ae69`。
- **26.1.2 / fabric**：`E:/MUSIC/26.1.2/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.1.2/minecraft-merged-043a8b3edf-26.1.2.jar`；SHA256 `1182bfd7b4209f9384cdc24c5c3de699b11b81d93604e3df13d09bcb33627c72`。
- **26.1.2 / neoforge**：`E:/MUSIC/26.1.2/neoforge/build/moddev/artifacts/minecraft-patched-26.1.2.126.jar`；SHA256 `f7147fdb5380a8158b42cdb9c35213d0c9a39bb0a0523adacc2e136223effc64`。
- **26.2 / fabric**：`E:/MUSIC/26.2/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.2/minecraft-merged-043a8b3edf-26.2.jar`；SHA256 `9989776ce617c73ceb1293995c0cc722ccd8b77e550b043ea36978f72c534b74`。
- **26.2 / neoforge**：`E:/MUSIC/26.2/neoforge/build/moddev/artifacts/minecraft-patched-26.2.0.72.jar`；SHA256 `047104eaba2c3788a3d2142d79567ae81b311e2d770584d6f85b4332bffac64b`。
- **26.3 / fabric**：`E:/MUSIC/26.3/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-7e9a32a5b8/26.3/minecraft-merged-7e9a32a5b8-26.3.jar`；SHA256 `edf496ead8954d94878b34514a0e9b76bd3fcac0f92f53021218fb2d88dec9b9`。
- **26.3 / neoforge**：`E:/MUSIC/26.3/neoforge/build/moddev/artifacts/minecraft-patched-26.3.0.16-beta.jar`；SHA256 `5335a0f4af6e792a9f8043e25186e02b178376454023697707d275ab448f7fda`。

共享native facade：`host/keys/NativeKeys.java`、`MinecraftKeyBindings.java`。每组合独立编译自己的 `NativeKeyAccess.java`；源码只在已经逐一确认的共同签名处相同。26.3使用SDL按钮显式映射(legacy 0/1/2 → native1/3/2)，其余六车道分别核对GLFW 0/1/2。

## 可复跑局部验证

在工程根运行：

```powershell
.\src\test\java\com\muoniumplayer\configskija\library\run-library-tests.ps1 -Raster -Native
```

- 页面/模型/测试使用本机 `D:/JDK17`、`--release 17`；native适配器使用能读取MC Java25字节码的 `D:/JDK25/bin/javac.exe --release 17 -proc:none`。启动器路径是本机事实。
- 结果：`LibraryParityTest: 144 assertions passed`。覆盖真实卡片归属、收藏/隐藏/分组、拖动排序、Default配置、配置CRUD/激活、克隆命名、原字段搜索/模糊开关、模板选择/分享导入不自动应用、内联配置录制、任意raw码、鼠标0、组合键释放/修饰符、冲突、离页取消、Credits全文/双版本、native facade不重复注册；真实ConfigView卡片松开打开页面、录制期间鼠标0优先于侧栏命中。
- 结果：14份真实classpath的native适配器局部编译全部通过，最终无编译警告。不是14目标完整Gradle构建。
- 结果：32张1391×700离线Skija fixture PNG（8状态×4主题）输出到 `build/library-previews/`；使用实际宿主 `skija-shared/windows-x64 0.143.17`、`types 0.2.0` 和现有 `pf_normal.ttf/pf_middleblack.ttf`。不是游戏截图，也不是与原版逐像素比较。Raster测试只保留了Skija旧字体API的deprecation提示。
- 已人工查看离线 Profiles、模板详情、Credits与Minecraft主题模组页。原版字体行高没有统一固定值，需最终人工视觉验收。
- 检查改动范围、路径与尾空白；没有修改只读树、禁止写集或宿主已有改动。

## 本次修改文件

- `src/main/java/com/muoniumplayer/configskija/ui/pages/CreditsPage.java`
- `src/main/java/com/muoniumplayer/configskija/ui/pages/KeysPage.java`
- `src/main/java/com/muoniumplayer/configskija/ui/pages/KeysRecording.java`
- `src/main/java/com/muoniumplayer/configskija/ui/pages/LibraryMods.java`
- `src/main/java/com/muoniumplayer/configskija/ui/pages/LibraryMotion.java`
- `src/main/java/com/muoniumplayer/configskija/ui/pages/LibraryPages.java`
- `src/main/java/com/muoniumplayer/configskija/ui/pages/LibraryPaint.java`
- `src/main/java/com/muoniumplayer/configskija/ui/pages/LibraryProfiles.java`
- `src/main/java/com/muoniumplayer/configskija/ui/pages/LibrarySearch.java`
- `src/main/java/com/muoniumplayer/configskija/ui/pages/LibraryServices.java`
- `src/main/java/com/muoniumplayer/configskija/ui/pages/LibraryTemplates.java`
- `src/main/java/com/muoniumplayer/configskija/model/KeyBindings.java`
- `src/main/java/com/muoniumplayer/configskija/host/keys/MinecraftKeyBindings.java`
- `src/main/java/com/muoniumplayer/configskija/host/keys/NativeKeys.java`
- `src/versions/1.20.1 recode/java/com/muoniumplayer/configskija/host/keys/NativeKeyAccess.java`
- `src/versions/1.21.1/java/com/muoniumplayer/configskija/host/keys/NativeKeyAccess.java`
- `src/versions/1.21.10/java/com/muoniumplayer/configskija/host/keys/NativeKeyAccess.java`
- `src/versions/1.21.11 recode/java/com/muoniumplayer/configskija/host/keys/NativeKeyAccess.java`
- `src/versions/26.1.2/java/com/muoniumplayer/configskija/host/keys/NativeKeyAccess.java`
- `src/versions/26.2/java/com/muoniumplayer/configskija/host/keys/NativeKeyAccess.java`
- `src/versions/26.3/java/com/muoniumplayer/configskija/host/keys/NativeKeyAccess.java`
- `src/test/java/com/muoniumplayer/configskija/library/LibraryParityTest.java`
- `src/test/java/com/muoniumplayer/configskija/library/LibraryRasterPreview.java`
- `src/test/java/com/muoniumplayer/configskija/library/LibraryTestContext.java`
- `src/test/java/com/muoniumplayer/configskija/library/run-library-tests.ps1`
- `docs/library-parity.md`

## 全局搜索复用ModCard（2026-11-24）

已公开 `LibraryPages.modCardRenderer()` / `LibraryMods.cardRenderer()`，返回 `LibraryMods.CardRenderer`。全局结果页直接复用模组网格的同一 `drawCard` 实现，不复制卡片绘图。

```java
LibraryMods.CardRenderer renderer = LibraryPages.modCardRenderer();
Optional<LibraryMods.Card> card = renderer.fromDocument(ctx, document);
if (card.isPresent()) {
    renderer.draw(ctx, card.get(), new Rect(x, y, width, renderer.cardHeight()));
}
// 或 boolean drawn = renderer.drawDocument(ctx, document, bounds);
// 全局结果离页：renderer.leave(ctx);
```

- 实际高度由只读原 `Mods.kt:488` 的 `height(140.dp)` 核实，`ModCardFooterHeight` 为36dp；四列、gap19。交接中的156不符合此源码，未采用。可直接用 `LibraryMods.CARD_HEIGHT`、`FOOTER_HEIGHT`、`GAP`、`cardWidth(contentWidth)`。
- `fromDocument` 只接受MODS scope；优先从当前 `LibraryServices.ModCatalog` 查真实卡片，再适配真实Card payload、旧SearchDocuments.Owner、HudElement与document metadata/id；旧owner muonium明确映射到已存在的muonium.hud。找不到真实关联返回Optional.empty，不用标题猜测、不造假卡片。
- `draw` 保留原命中ID、实时开关、收藏、隐藏、HUD preview、tooltip和动画；全局搜索不引入网格重排。点击在释放时打开，移动达到5dp或离页不误打开；`leave`取消待释放点击。
- helper不排序/过滤搜索结果，也不改index/source生命周期；全局renderer保持provider顺序，自己分四列与viewport裁剪。传给draw的高度须为140dp。
- 追加局部验证覆盖registry/旧Owner/Card/HUD适配、错误scope、未知document拒绝、同一收藏隐藏存储、36dp实时开关、释放打开、拖动/离页取消和绘制栈平衡。当前页面测试合计140项断言通过。

本轮只改LibraryMods.java、LibraryPages.java、LibraryParityTest.java和本文档，未改ui/search文件。

Shell录制互斥接入：`LibraryProfiles.recording()`公开委托内部profileKeys；`KeysPage.recording()`公开返回录制状态。UiPage加入default recording=false后两个实现自然覆盖；Shell应在activePage或overlay录制时屏蔽PreferenceBindings全局动作，原始输入优先送录制页面，提交/取消/leave恢复false。

全局结果也可直接保留LibraryMods实例并调用public `catalog(ctx)`、`drawSearchCard(ctx,card,bounds)`；它们委托同一真实来源与CardRenderer，实例leave同时取消search点击。`KeysPage/LibraryProfiles`现已明确@Override UiPage.recording()。

HUD档案身份接入（2026-11-24）：HostLibraryExtras的Lifecycle.changed在恢复UI快照后执行`preferences.set("profile.active",current)`，覆写快照里过时的身份并保留同一快照的HUD document。Profiles层Default的真实id为`""`；宿主hudOwner应将偏好中缺失/空值归一为`"default"`，非空UUID原样返回。服务常驻host，开屏/关屏不close，只有应用shutdown才flush后close。

## 批量迁移全部旧Profiles（2026-11-24，初始基线版）

> 此节记录旧无runDirectory签名的行为。用户随后追加授权读取旧音乐，最新路径重载与schema过滤规则见下方“安全旧音乐补迁”；此前统一baseline结论不适用于新的路径重载。

主线程现在可把迁移层提供的全部安全快照送入批量入口；不是只迁当前Profile：

```java
List<LegacyProfileImport.Profile> detached = snapshot.profiles().stream()
    .map(p -> new LegacyProfileImport.Profile(p.legacyName(), p.preferences(), p.hud()))
    .toList();
profiles.importLegacyProfiles(detached, currentUi).whenComplete((report, error) -> {
    // 主线程完成；error必须报告，不可写“旧档案迁移成功”。
    if (error != null) { host.error("迁移旧配置", error); return; }
    Map<String,String> oldNameToNewUuid = report.oldNameToId();
    // 主线程仍负责当前活动UI的旧名favorite/icon重挂、选定旧active对应的新UUID，
    // 以及把迁移层原issues与此report.issues合并成真实迁移报告。
    // 导入不会自动activate，也不会替换当前HUD或音乐。
});
```

另有显式来源重载：`importLegacyProfiles(String source, List<LegacyProfileImport.Profile>, Map<String,String> currentUi)`。默认source为oneconfig-legacy-v1；多个旧安装迁入同一新档案目录时，调用方须给稳定且不同的来源标识。输入最多512个快照，导入期间服务busy，完成回调在main；不要join/get。

- 每个旧名包括空字符串root都创建独立UUID；旧root名显示为Default (legacy)，不覆盖新侧真正的空ID Default。重名显示名加后缀。输入重复旧名明确记入notImported/issues，不把多项伪装成一份成功。
- 新文件保存各自转换偏好及`ui.hud.document`（MHUD1）、正确新`ui.profile.active`、实际icon元数据；已成功UUID的旧profiles.favorite/icon键同步重映射进各自档案快照，避免之后激活又还原旧名。主线程继续负责当前UI的同一映射。
- 当前music只capture一次，复制到每个新档案作为明确基线；当时可用的controls也只作当前基线，旧原生键位/Profile快捷键/其它mod配置目录没有由此入口恢复。每档案写legacy.import.version/source/name/musicOrigin/musicSource/created/scope，并为music/controls遗漏逐项返回Issue。
- Result包含imported（每项旧名、新UUID、显示名、来源、是否复用）、issues、notImported、cancelled及oldNameToId。cancelled不等于未写任何文件：若关闭发生在worker已提交后，返回真实已落盘UUID并标过期；不改旧UI/active，不隐瞒部分成功。单个档案超限/写入失败会记明未迁项，并继续处理余下快照。
- 同来源+旧名重复导入复用已有UUID，保留之后的UI/音乐修改。checkpoint、图标编辑和重命名保留迁移出处；克隆为新档案时去掉迁移身份，避免让两个UUID冒充同一旧档案。既有来源版本/映射歧义明确拒绝，不猜覆盖。
- 本入口只接中立数据，不接旧文件路径、任意Music JSON、账号或目录字节，不读取/写回旧仓文件。真正迁移旧音乐还需要受控的字段级适配，当前不能宣称已完成。

与Popper已协调并复核源码：旧MuoniumMusicStateProfiles将非空profile信封存到run/profiles/<name>/muonium-music-state.json，root以全局配置为权威不写信封；MusicStateEnvelope的format/version/localOnly/state形状校验不证明没有隐私字段。当前LegacyMigration.Snapshot仅有UI/HUD，没有读取音乐信封。因此统一使用`PresetConfigBridge.captureLocalState`的当前受支持LOCAL envelope，字段format=muonium.profile.music/version=1/localOnly=true/state对象；本地基线来源不等于旧档案音乐恢复，不把任意旧state直存进新档案。原仓文件保留供以后有明确证据的字段级迁移。

本轮修改：host/MuoniumProfiles.java、host/library/LegacyProfileImport.java（新增）、host/library/ProfileFiles.java、library/HostLibraryTest.java及本文档；未改Popper的host/migration或主线程MuoniumHost/SkijaConfigScreen。

自动检查新增：旧root+多个命名档案分别UUID/HUD、一次真实baseline capture、旧文件不变、来源持久化/未迁说明、收藏图标重挂、活化后真实HUD恢复、checkpoint/克隆后的幂等、单档失败后继续、部分提交取消及close后不更新过期列表。最新结果为LibraryParityTest 144项断言、HostLibraryTest 248项检查通过，新增宿主代码14份实际classpath均局部编译通过；不把这些离线检查称作旧音乐/游戏迁移实测。

## 最终Favorites catalog收束（2026-11-24）

只读原`internal/ui/screens/Favorites.kt:20`取FavoritesTree，:25显式注册FavoritesCard，:37起定义id=FavoritesTree.ID、title=常用、icon=star、category=QOL；`FavoritesTree.java:47`确认ID为muonium.favorites。主项目MuoniumFavorites填入5开关+3滑块，页面由host.favoriteCategories接真实模型，不能解释为任意设置pin。

HostLibraryExtras.modCards现仅在宿主存在id=muonium.favorites的真实category时补同身份卡，open跳独立FAVORITES路由；共享LibraryMods fallback catalog亦遵守同规则，主/搜索不分叉。现有host.huds提供的真实HUD卡全部保留。原BuiltinHudConfig注册oneconfig.builtin，但ConfigRegistry.kt:25将其隐藏，本实现不额外造一张可见OneConfig卡。

已补host catalog与搜索document复用检查，验证真实FavoritesCard ID/title/icon/QOL、正确路由、两个HUD卡保留、任意setting pin不增加卡片。此项完成后本分工冻结，不再修改MuoniumHost/SkijaConfigScreen/搜索文件或追加旁支功能。

最终Favorite收束局部检查：LibraryParityTest 144项、HostLibraryTest 248项通过；26.2 Fabric首次复编遇到宿主构建输出暂缺，未修改主项目，随后只读复查三个宿主类已恢复，并复核实际classpath。最终重跑十四组合全部局部编译通过；页面144项、宿主248项通过。本分工已冻结，不再追加源码修改。

## 安全旧音乐补迁（2026-11-24，当前实现）

用户追加授权解除此前冻结，在host/library新增OldMusicLoader.java，只允许worker读取已核实的命名档案信封。主线程startMigration务必改用带旧run目录的重载：

```java
profiles.importLegacyProfiles(runDirectory, detachedProfiles, currentUi);
// 或 importLegacyProfiles(sourceId, runDirectory, detachedProfiles, currentUi)
```

旧的无路径签名为兼容而保留，但会明确报告RUN_DIRECTORY_NOT_PROVIDED，不会读旧音乐。新路径签名先在main通过LocalState.capture取得当前完整LOCAL envelope/schema，然后在worker逐档读/过滤/保存。

- 唯一读取位置：run/profiles/<legacyName>/muonium-music-state.json。空旧名root不读信封，按原MuoniumMusicStateProfiles约定使用当前全局capture；不存在的命名信封也使用有来源的基线。其它路径/编码/header/大小/depth错误是明确拒绝，新的该档案记入notImported，不悄悄视作缺失。
- 路径检查：拒绝分隔符/冒号/点跳转/尾点空格/控制字符；逐父级NOFOLLOW验证目录、普通目标文件、符号链接与真实路径；文件只以READ+NOFOLLOW打开。读前后复核父路径/fileKey及文件size/mtime，变化则拒绝。所有输出只写新侧profile目录，旧文件不改。此为Java路径与前后身份检查，不宣称能对抗有同等本地权限的恶意进程进行原子目录交换。
- 输入限制沿用真实LOCAL协议：最多MAX_JSON_CHARS=512Ki字符，字节读取上限为其四倍，严格UTF-8，预解析深度MAX_DEPTH=16；结果也须落在LOCAL协议字符上限。已知LOCAL字符串使用完整信封上限，不误套分享码MAX_VALUE_STRING_CHARS的小限额。
- header严格匹配format=muonium.profile.music、数字version=1、布尔localOnly=true、state对象。九个精确域ID为hud/theme/foliaBg/font/transparent/cine/acoustics/viper/spin（foliaBg区分大小写），依据实际PresetDomains.all及各domain.ID。当前capture本身若含未核实域或非对象域会拒绝作为白名单。
- state由当前capture深拷贝重新构造，只从旧信封投影当前schema中存在的domain/key和类型匹配值；递归对象同规则，未知域/键丢弃并诊断，不原样存旧state。布尔/数字/字符串不互相强转，非有限数字拒绝；null只证明null，空数组不推断元素类型。数组仅迁当前已有下标，额外下标丢弃；对象数组同样过滤键，防止用大量旧元素复制当前schema而放大输出。缺失/不匹配字段保留当前基线并明确报告。
- 顶层额外字段不写入新信封。诊断只含受限键路径/固定原因，不含字段值或原JSON解析错误正文。cookie/auth/provider等未知内容既不入新档案，也不进入日志报告。Loader.Result.toString隐藏envelope内容。
- Imported新增musicKeys计数，musicOrigin为LEGACY_SCHEMA_FILTERED或CURRENT_CAPTURE_BASELINE；属性持久化musicOutcome，区分root/missing/未提供run，另存musicKeys/source。合法信封没有可迁字段时keys=0并有no-compatible诊断，不宣称恢复旧音乐。status按实际有迁入字段的档案统计，不再一律写当前baseline。
- 已有同source/旧名UUID仍复用。对于之前生成的基线，若新侧本地音乐指纹仍与未改动基线相符，可以就地补迁合法旧音乐；新指纹只针对新侧受支持/过滤后的音乐内容，在本地档案用于变化检测，不对原旧文件计算/输出凭据指纹。用户后来修改过音乐或老迁移没有可核对指纹时保留用户状态并返回existing-music-kept，不能静默覆盖；若要重新迁为独立档案，调用方可明确使用不同source。

新测试覆盖所有层级cookie未知键、不匹配类型、准确foliaBg ID、九域白名单、未知数组元素、根/缺失/坏信封区别、字符/字节/depth/UTF-8边界、父目录和文件符号链接、只读前后字节和mtime不变；集成测试验证两个Profile切换确实出现各自旧音乐值，而不是统一baseline，以及未改基线补迁、UUID不变、修改后的用户音乐不覆盖。

本轮修改：host/library/OldMusicLoader.java（新增）、host/library/LegacyProfileImport.java、host/MuoniumProfiles.java、library/OldMusicLoaderTest.java（新增）、library/HostLibraryTest.java、library/run-host-library-tests.ps1和本文档。未改原仓、主项目既有业务、MuoniumHost/SkijaConfigScreen或host/migration解析器。最终测试数字在本节末记录。

本次安全旧音乐补迁最终验证：`LibraryParityTest: 144 assertions passed`；`HostLibraryTest: 287 checks passed`；`OldMusicLoaderTest: 63 checks passed; symlink capability skips=0`；14个实际宿主classpath全部局部编译通过。命令为`run-host-library-tests.ps1 -AllCombinations`，仍未运行Gradle或游戏。此前仅baseline迁移的冻结已按本次新授权解除，以上限定范围完成后重新冻结。

## 本分工最终冻结交接（2026-11-24）

最终通知收束：LibraryPaint.run仅调用一次host.error，返回boolean指示同步任务是否成功；KeysPage录制失败也仅走host.error。LibraryTemplates的删除/复制/套用/保存/导入在helper失败后不再发送info toast。成功与明确的信息提示保留原调用，不改主线程已接的Notifications实现。

主线程在main调用以下最终入口，输入必须包含Snapshot.profiles的全部档案；runDirectory是旧游戏run目录，不能传profiles目录或当前新档案目录：

```java
import com.muoniumplayer.configskija.host.library.LegacyProfileImport;

List<LegacyProfileImport.Profile> incoming = legacySnapshot.profiles().stream()
    .map(p -> new LegacyProfileImport.Profile(p.legacyName(), p.preferences(), p.hud()))
    .toList();
CompletableFuture<LegacyProfileImport.Result> pending = profiles.importLegacyProfiles(
    "oneconfig-legacy-v1", runDirectory, incoming, currentUi);
// 简化重载：profiles.importLegacyProfiles(runDirectory, incoming, currentUi)
```

返回CompletableFuture<LegacyProfileImport.Result>，异常与逐档结果都须展示/记录，不能只看future正常完成就报告全部迁入。无需外层再次执行worker；内部在main捕获当前LocalState schema，worker只读旧信封并写新档案，main更新目录快照；close/request/profile generation保护过期回调。

- imported(): 每项包含legacyName/id/name/musicOrigin/musicSource/reused/musicKeys。musicOrigin=LEGACY_SCHEMA_FILTERED且musicKeys>0才计为实际恢复旧音乐；CURRENT_CAPTURE_BASELINE明确是当前基线。
- issues(): 每条包含legacyName/code/detail，报告未知键丢弃、字段类型不符、基线退化、已有新音乐保留等；没有原字段值或cookie正文。
- notImported(): 拒绝或未完成的旧档案名。cancelled(): 生命周期/请求过期等取消结果。
- oldNameToId(): 全部已导入/复用档案的旧名到新UUID映射，可用于当前UI metadata接线。写入各新档案内部的favorite/icon元数据已转换；调用方仍要处理当前活跃UiPreferences中对应旧名键。
- 相同source保持稳定以复用UUID。旧root也独立建UUID，不覆盖新Default或自动激活。无路径兼容重载明确退化，不能作为完整旧音乐迁移入口。

本次最终重跑：run-host-library-tests.ps1 -AllCombinations退出0；LibraryParityTest 144 assertions，HostLibraryTest 287 checks，OldMusicLoaderTest 63 checks（symlink capability skips=0）；14个实际host compile-classpath全部局部编译通过。UI通知修改没有新增MC API。本分工不再新增范围，不执行Gradle、游戏、暂存/提交/发布；游戏内视觉、交互与动画验收交人工，主线程统一构建与提交。

旧音乐补迁本轮7个文件见上节；通知收束额外修改LibraryPaint.java、KeysPage.java、LibraryTemplates.java及本文档。至此UI/HostLibrary写集冻结。