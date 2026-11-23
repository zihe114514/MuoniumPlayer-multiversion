# HUD Studio 移植与接入清单

核对日期：2026-11-24。原仓 E:/oneconfig lite/MuoniumConfig-src 只读；此文仅记录 HUD 独占范围的代码与证据。游戏内视觉、交互、动画由人工验收。**尚未宣称逐像素 1:1 完成或游戏实测通过。**

**收束交接（2026-11-24）**：HUD 入口、runtime、文档格式及宿主接口的最终单位契约见末节。主线程最新反馈已接 runtime、原 BackgroundUnion 真实多 path、常驻偏好和 EXACT_TEXT；这是主线程接线状态，本任务本轮仅复核中立 API 与局部模型测试，未运行整合构建或游戏。下文历史接线说明以本段及“收束时未补齐的具体触点”为准。随后按真实 raster fixture 反馈修复的 HUD 文字几何见末节；当前局部测试结果以末节为准，公开 API 保持不变。

## 主助手可立即使用的入口

- 页面：`com.muoniumplayer.configskija.ui.hud.HudStudio(ConfigHost, UiPreferences)`，实现现有 `UiPage`；`view.register(Navigation.Page.HUD, studio)`。HUD 的 PageContext 宽高、鼠标、命中和 preview 均为主项目布局像素；不可套 Shell 的 1391×700 缩放。
- 会话：`studio.post(HudStudio.Command)`，`studio.activeSelection()`；命令排队在页面/客户端线程处理，leave 清空队列。
- 模型：`studio.designer()`；原 HudDesigner 构造器、State 的原八参数构造器、Corner、begin/drag/end、history 均保留。
- 数据：`HudDocument(int schema, List<HudDesigner.State>)`，schema=1，`encode()/decode()` 采用 MHUD1: 头的有界二进制 Base64，不使用 Java ObjectInputStream。每个实例保存 ID、type、位置/尺寸/scale/显隐/锁定、完整标量属性、anchor 与 order。本模块不解析原仓 OneConfig 配置；自动旧配置迁移由主线程维护，本轮未核验其接线/完成状态。

## 运行期必挂的中立接口

文件：src/main/java/com/muoniumplayer/configskija/host/HudExtensions.java。由主助手在 MuoniumHost 实现；本任务未改 MuoniumHost/ConfigHost/ConfigView/版本适配。

| 方法 | 主助手职责 |
| --- | --- |
| `hudCapabilities()` | 仅在真实安装相应回调后声明 ADDITIONAL_HUDS/HOST_COMPOSITION/HOST_SETTINGS/EXACT_TEXT/GUI_VISIBILITY。缺 ADDITIONAL_HUDS 时库显示解释并禁用新增，不能把画布模拟视为游戏支持。 |
| `hudOwner()` | 当前 Profile 稳定身份。切换时清空 undo/redo/clipboard，主项目配置先恢复，再加载此 Profile 的扩展布局。 |
| `loadHudDocument()/saveHudDocument(HudDocument)` | 按 Profile 独立存储。宿主原两块 HUD 的真实配置为唯一位置/大小权威；扩展文档保存其锁定/关系/顺序，重载不拿旧副本覆盖主配置。 |
| `bindHudRuntime(HudRuntime)` | 持有运行时直到 Profile 切换/退出，不能在设置页关闭时销毁它。首次进游戏用 `HudRuntime.open(host, preferences)` 构造并绑定，不能等用户打开 HUD 页才恢复扩展 HUD；页面用三参数构造器复用该 runtime。 |
| `hudFrame()` | 构造 `HudRuntime.Frame(width,height,guiScale,time,dateTime,editor,debug,tab,screen,chat,keys,data).withPhysicalScale(physicalPerLayout)`。宽高为同一主项目布局单位；真实 GUI 倍率和每布局单位物理像素必须分别提供。旧12参构造器默认 physicalPerUnit=guiScale，仅适用于纯 GUI 轴。time 用单调秒数；keys 与 nativeKeyCode 的 legacy 码一致；dateTime 提供实际本地时钟。 |
| `hudProviders()` | 实时可注册 HUD 提供者，不冻结首次空列表；单实例/多实例/可删除由 HudElement 真正能力声明。 |
| `hudSettings(HudElement)` | 提供该 HUD 的主项目真实 ConfigHost.Group/Option；不复制 HudConfig。该组的可写标量值由 HudHostElement 纳入 HUD undo。 |
| `hudTextRenderer()` | 实现 TextRenderer.measure/draw；Minecraft 与 Poppins、五种字重、粗体/斜体/下划线、outline/shadow 的真实指标与绘制。传入当前 Painter，不建 Skija/Skiko context。没有此回调时的 Painter 文字降级不能称字体 1:1。 |
| `renderHostHud(Painter,HudElement,Rect,Frame)` | 同一主 Skija 帧中按给定真实矩形绘制两块宿主 HUD；编辑隐藏预览透明度需真实生效。HOST_COMPOSITION 声明后 runtime 将接管运行时两块宿主绘制，主项目旧路径不得重复画。 |
| `renderHudShape(Painter,HudMerge.Shape)` | 将 loops 按 even-odd union 一次填充，用 tangentArc 的 radius 处理凹/凸角。合并成员不得再画各自背景；alpha 不能叠加变深。 |

游戏回调：

```java
// 客户端线程，现有主 HUD 的生命周期内；frame.editor() == false。
runtime.tick(frame);
// 主项目现有 Skija 绘制阶段，同一 Painter/Canvas；不要创建第二份 native/context。
runtime.render(painter, frame);
// 明确的 Profile reload 也可调用；tick 会检查 hudOwner 变化。
runtime.reload(document);
```

源码依据：原 HudManager.kt:589–610 实时布局、更新尺寸、settlePin；Hud.kt:438–489 手动 anchor 跟随，822–868 合并轴链接及保位；HudDesignStudio.kt:499–517 合并背景先绘、内容后绘。新增 HUD 的 runtime 独立于 UiPage，leave 保留它。**主线程已反馈 runtime 接入主项目；本次局部验证只证明中立调用链及模型测试通过，游戏回调命中与实际显示待整合验收。**

## 原版逐项证据与当前状态

以下原文件相对原仓 muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/。Studio 指 internal/ui/hud/screens/HudDesignStudio.kt，已完整逐段读取 1–3864；Session/History/Tour/Additions/Designer/Settings 已完整读取。

| 项目 | 原文件/行号、尺寸/时序 | 已实现内容 | 自动验证 / 待完成 |
| --- | --- | --- | --- |
| 点击、重叠轮选 | Studio 236–242、537–550、1608–1651、1668–1695、1847–1855；drag slop 3px | PendingPress、Ctrl toggle、Shift append、组选择延迟缩窄、locked 优先级 | 模型测试通过；人工交互未验收 |
| 框选 | Studio 1484–1499、1775–1787、1910–1924、2194–2218；fill .15，默认 150ms | 可缩回、初始加选基线、排除锁定、保留隐藏项；消退保留最后矩形 | 模型通过；视觉人工 |
| 锁/隐藏/行动条 | Studio 196–216、563–608、619–677、867–934、2071–2118；24px icon、8px gap、4px padding、35% hidden preview | amber 2/4 dash、hidden 4/3 dash、外置 actionbar/corridor、显隐/锁和可删除项 | 输入测试及图层栈通过；主线程已接真实预览，游戏隐藏效果未验收 |
| 多选/合并组移动 | Studio 1675–1695、1766–1774、1857–1896 | 未选中合并组整体移动，已选项单独拖出，释放后 primary 规则；共同 delta 保持组距 | 专项通过；主线程已接 runtime，游戏未验收 |
| resize | Studio 680–770、1573–1598、1713–1724；原四角 hit 14px/draw 7px；投影比例、.25–4、textScale 6/14–64/14、Shift free | 保留四角并扩展八向，宽度专用/不可resize 能力；反角固定，读宿主夹取后的真实尺寸 | 八向/投影/free/width/undo 通过；宿主每组合游戏表现未验收 |
| snap | Studio 218、244–295、1743–1765；严格小于 6px，Alt bypass，guide #B02F1F | 屏幕/其它 HUD 的首中尾；单一拖动主块带组共同 delta | 阈值及组移动通过 |
| 对齐、层级 | Studio 348–381；HudManager.kt 547–569 嵌套深度稳定序 | 六种对齐、3次稳定写入；嵌套优先命中；额外前置/后置 order | 模型通过；宿主游戏 z-order 需 HOST_COMPOSITION |
| 右键菜单 | HudSettingsReset.kt 270–450；4dp padding、原行高/对齐图标 | 设置、显隐锁、六对齐、双步 anchor、移除、copy/cut/paste/duplicate、undo/redo、reset/delete；空白菜单 | 输入测试通过；依赖主助手右键 raw 路由 |
| copy/cut/paste | Studio 2644–2761；默认偏移32；鼠标粘贴按组居中 | 深复制属性、删除后保留剪贴板、组内 anchor ID 重绑、单实例权限 | 深复制/剪切删除后粘贴/redo 通过；与原版活引用 clipboard 的差异已采用更安全快照语义 |
| history | HudHistory.kt 19–38、86–178、186–247；limit100、float .001、poll250ms/连续两次稳定 | 手势1事务，空事务跳过、属性/增删/关系/层级恢复、poll、Profile identity、derived测量排除 | 模型通过；本仓主项目设置消费者接线另验 |
| 库/注册 | Studio 1282–1288、2341–2453、3146–3164 | 实时 providers、主项目单例及隐藏重新启用、居中添加、拖动添加、真能力禁用提示 | 输入通过；主线程已接 runtime，新增 HUD 游戏显示未验收 |
| 库布局/预览 | Studio 3264–3291、3372–3403、3542–3584、3628–3645、3710–3725；401×481 外框，18 gutter，10 gap，.6 行高容差，1.35 stretch，scale≤2，padding12，maxheight160，label20/11sp，drag8px | 最先适合行装箱、分组连续滚动、55% activation line、mod intent、hover150ms、文字label、native preview | 装箱/拖放测试通过；Chrome density及物理8px slop已补齐；已还原 animateScrollBy 默认spring、预先测量全部provider、目标clamp和用户滚轮/滚动条中断 |
| 可拖设置面板 | Studio 1472–1481、1937–1964、3000–3125；500外宽/16边距，header24/14，spacing32/22，dock220ms，tab250ms | 防遮挡左右评分selected×8、手动拖动/边界夹取、实时字段/搜索跨tab、滚动、HUD标题 | 真实状态已实现；已补250ms双侧slide/crossfade；尚未逐像素校验 |
| Designer | sections/Designer.kt 85–365、381–445；20–2000 W、8–2000 H、padding0–100、font6–64、preview58、color32 | 原字段、静态尺寸、增长anchor、内距、字体/字重/样式/大小写、HSV/alpha/hex/chroma事务 | 颜色事务、spinner实时输入/取消通过；精确字体依赖桥 |
| Settings | sections/Settings.kt 49–346；scale .25–4 step .25，radius0–32 | 锁、比例、背景/merge/diagonal/hidden-bg、颜色/阴影、F3/Tab/GUI/chat、文本/日期/图标、按键 | 数据及可见性测试；已接真实默认值的每字段reset菜单；已还原字段说明的200ms/TopCenter/260dp tooltip与原菜单分隔/当前快捷键 |
| 手动 anchor | Studio 412–488、1508–1544、2121–2190；9点，r5/hit10，热点r7，scrim.25；Hud.kt 879–916 | 双步自己点/目标点、Esc返回/退出、右键解除、保位/跟随、循环禁止、关系线 | 模型循环/保位/父移动通过；人工视觉未验收 |
| 背景合并 | api/hud/v1/HudBackgroundMerge.kt 55–64、103–163、166–289、311–383、386–485；touch2/overlap2/eps.01/depth16 | style/chroma/radius匹配、axis spanning tree、manual anchor优先、hidden策略、union轮廓/凹角 | L形面积/样式/hidden/diagonal/跟随通过；主线程已接原 BackgroundUnion 真实多 path；本轮未验证游戏绘制效果 |
| 内置模型 | Hud.kt/ TextHud.kt 完整读取；TextHud.kt 54–77、148–201、210–281；BuiltinHudConfig.kt 27–41；HudAdditions.kt 1–11为空 | 文本、日期、时间；额外icon中立模型；prefix/suffix/brackets、时间格式、动态测量/visibility/key | 格式/keys通过；原仓没有 IconHud 声明，icon 属需求扩展，不能声称原版同名 API |
| tour | HudTour.kt 59–86、138–169、205–259、266–299；9步、280宽、6点/4gap、220ms hole/card、2.2s drag hint(0–.4 hold/.4–1.6 move/1.6–2.2 hold) | 9状态、阻塞/交互区别、真实新merge完成门槛、返回恢复原selection/panel/tab、提示ghost | 状态测试通过；视觉/动画人工；教程锚定示意已复用完整同一菜单渲染器；不添加交互命中 |

## 本阶段验证

- Java 17 独立 HUD 源码与 UiPage/Painter/ConfigHost 的真实声明编译通过；未运行 Gradle、游戏、native Skija。
- HudParityTest：409 条基础行为断言 + 80 条细节断言 + 153 条改键断言通过（2026-11-24，本轮源码补齐），涵盖选择/缩放/snap/锁/复制/历史/锚点/合并/document/runtime/page/library/tour。
- 首次尝试现有 CoreTest 完整 main 停在 geometry 的 shell remains in window；未修改 CoreTest，未据此宣称其整体通过。
- 视觉/动效/字体/游戏内行为交人工；主项目14组合集成编译由主助手处理。

## 收束时未补齐的具体触点

1. **字体下拉箭头的中途图形旋转**：原 hud/components/Dropdown.kt:55–119、123–158 旋转原图标。当前 HudPanel.drawDropdownArrow 在 0°/180° 使用真实 up/down 图标，中间角度仍用两段线几何；目标角度、默认 spring 和结束时刻已有测试，但中间图形尤其 Minecraft 图标主题不等价。2026-11-24 最后核对共享 Painter/SkijaPainter 均无 rotate 声明；需要主线程增加实际 `Painter.rotate(double degrees)` 及 Skija 转发，再由 HUD chrome 原样转发角度、围绕中心旋转同一 SVG。此共享方法不在本任务独占写集，本轮未伪造空实现。
2. **剪贴板快照语义仍与原活引用不同**：原 Studio:2644–2761 保留活引用；当前 HudDesigner 使用深复制快照，复制后再编辑原 HUD 不改变已复制内容。此差异已在功能表记录，保持现有测试通过的快照行为；未将其称为逐项完全相同。

DPI/density、设置 tab 双侧250ms slide/crossfade、实时数值框、125dp对齐图形、字段 reset/context、默认 spring、库分组跳转、菜单分隔/改键文案、tooltip、颜色弹层及实际可录制 HUD 快捷键已补齐代码与局部测试，本轮没有扩展新功能。

外部归属与验证边界：主线程负责的启动/保存、runtime、真实字体、原 BackgroundUnion 接线按其最新反馈已接；旧 OneConfig/外部 HUD 格式解析不属于本模块，本轮未核验主线程迁移实现。整合编译、游戏内视觉/交互/动画和各版本组合仍由主线程/人工验收，不能用下述 headless 结果替代。

## 稳定用法（最终契约，2026-11-24）


defaults 不在磁盘路径中硬编码。宿主复用当前 Profile 的数据文件，以独立字段例如 `hud.document.v1` 保存 `document.encode()` 的字符串；或写独立 UTF-8 文档文件。模型不引用 Files/Path。读取无数据时返回 `HudDocument.empty()`；损坏数据必须报告并保留原文件，不要无声覆盖为空。宿主的加载返回内存缓存，异步 I/O 完成后回客户端线程调用 reload。

```java
// 客户端启动，Profile 主项目配置已恢复；不需要打开任何界面。
HudRuntime runtime = HudRuntime.open(host, preferences);
// 打开 HUD 页时复用同一个所有者。
HudStudio studio = new HudStudio(host, preferences, runtime);
view.register(Navigation.Page.HUD, studio);

HudRuntime.Frame frame = new HudRuntime.Frame(
    layoutWidth, layoutHeight, // 同 Painter 的布局像素，正数
    actualGuiScale,            // MC实际 GUI 倍率，正数，与 physicalPerLayout 分开
    System.nanoTime() / 1e9,   // 单调秒数；流彩为10秒周期
    LocalDateTime.now(),       // 实际本地日期时间
    false,                    // 游戏 editor=false；编辑页自动 frame.editing()
    debugVisible, tabVisible, anyScreenOpen, chatOpen,
    pressedLegacyKeyCodes,     // Set<Integer>，与 ctx.nativeKeyCode() 同码系
    variables                 // Map<String,String>，用 {key} 替换文字；无则Map.of()
).withPhysicalScale(physicalWidth / layoutWidth); // 每Painter布局单位的物理像素数
runtime.tick(frame);           // 可在客户端 HUD 更新阶段调用
runtime.render(painter, frame);// 同一个主Skija帧，render也包含幂等tick
// Profile切换先恢复主配置/更新hudOwner，之后reload；tick也会检查owner。
runtime.reload(document);
```

TextStyle.size 及 Metrics.width/height/baseline/lineHeight、TextRenderer.draw 的 x/y 都是未乘 HUD scale 的局部布局单位。draw 的 x/y 是文字块左上角，不是基线；renderer 应保留 runtime 已入栈的 translate/scale。字体样例使用原 Designer 的14px×textScale；运行时 TextHud 使用8px×textScale。

未声明 HOST_COMPOSITION 时，运行时仅绘扩展 HUD，主项目两块保持现有绘制路径。renderHudShape 回调不依赖 HOST_COMPOSITION——它仍须为扩展 HUD 处理完整合并轮廓。hudTextRenderer 为空却声明 EXACT_TEXT 将明确抛错。

UiPage 的内部输入已经先检查 ctx.textFocused()；HUD自有搜索/数值编辑记录焦点并拦住快捷键。Panel chrome 单独按 host.uiDensity 转换绘制/鼠标/命中，HUD本身的选择框/resize/snap和真实preview仍用主项目布局像素。原版corner handles仍为7px画/14px命中；anchor r5/hit10不随chrome误缩放。

## 修改文件（独占范围；含2026-11-24补齐）

- model/HudElement.java：兼容原接口，增加resize轴/方式、9点anchor、属性快照、provider/default能力。
- model/HudDesigner.java：完整选择/群组/resize/snap/align/order/clipboard/history/anchor模型。
- host/HudExtensions.java：中立宿主接线契约，真实能力声明。
- ui/hud/HudStudio.java：公开入口与画布/菜单/面板/库/tour协调。
- ui/hud/HudChromeContext.java：仅chrome的density变换、焦点/文本事务。
- ui/hud/HudDocument.java：有界schema1序列化、anchor循环校验。
- ui/hud/HudNode.java：文字/日期/时间/图标数据模型和增长锚点。
- ui/hud/HudRuntime.java：独立于页面的启动恢复、tick/render、字体/visibility/key/merge生命周期。
- ui/hud/HudMerge.java：合并判定、轴树、union轮廓、凹凸圆角和可见fallback。
- ui/hud/HudLibrary.java：分组/筛选/装箱/预览/拖放。
- ui/hud/HudPanel.java：Settings/Designer、可拖面板、实时spinner/每字段reset。
- ui/hud/HudHostElement.java：主项目可写Option与HUD历史的桥接。
- ui/hud/HudColorPicker.java：SV/hue/alpha/hex/chroma一事务。
- ui/hud/HudTour.java：9步状态、真实merge任务、动画/恢复状态。
- ui/hud/HudWidgets.java：HUD局部控件、popup/输入/滚动。
- src/test/java/com/muoniumplayer/configskija/hud/HudParityTest.java：409条基础行为断言；测试包为 ui.hud，以验证包内状态，未改现有CoreTest。
- docs/hud-parity.md：本对照/接入文档。

所有生产文件位于 src/main/java/com/muoniumplayer/configskija 下。未写 ConfigView、PageContext、Theme、ConfigHost、MuoniumHost 或原仓；共享能力由主助手/其他任务提供，编译时只读。未暂存、提交、发布、启动游戏或执行Gradle。

上一阶段静态检查：当时17个所有权文件逐行检查，无行尾空格、控制字符或缺失末尾换行；限定源路径未检出 MC/loader/Compose/Skiko/Skija 依赖。根 git diff --check 无输出，但本工程当前未跟踪，不能用该命令代替新增文件检查。

## 2026-11-24 五组实际差异补齐

| 触点 | 精确证据 | 本轮实现与测试 |
| --- | --- | --- |
| 库分组跳转 | Studio 3295–3349全部preview尺寸先收齐；3419–3437等待measured，目标offset clamp后animateScrollBy；原resolved foundation-desktop 1.13.0-alpha02 ScrollExtensionsKt.animateScrollBy$default使用spring() | 新HudScrollAnimation按物理像素运行默认stiffness1500、damping1、threshold.01；滚轮和滚动条立即取消；新请求重算目标；80条细节测试覆盖起点不跳、过程中移动、结束、密度与中断 |
| 默认spring/visibility | 同版本FloatSpringSpec/SpringSimulation按毫秒截断；SpringEstimationKt结束时间；EnterExitTransitionKt默认fade/slide stiffness400；AlignmentPicker.kt 120–134 spring(.5,1500)后tween150 | 新HudMotion保留velocity retarget、Float运算、颜色Oklab向量共同duration；float阈值.01、dp.1、IntOffset1物理像素；库/面板slide分离alpha，教程与hint保留退出帧；4,820位置/速度样本误差0，121反向样本匹配，60结束时刻匹配 |
| 菜单分隔/shortcut | HudSettingsReset.kt 294–329、331–450、455–467、486–537、543–620 | 新HudContextMenu：原完整行序；4dp外pad；28dp行；14图标/8gap；text13、hint10；分隔4+1+4；190..240宽；120ms Oklab hover；真实AppearanceSchema偏好中的当前chord，未绑定或showKeybindHints=false不画；教程预览共用完整菜单；字段reset另用HudPropertyMenu |
| tooltip | HudSettingsReset.kt 225–234：说明max260且TopCenter；TextComponentRenderer.kt 115–138：delay200、圆角8、border1、padding12×8；resolved ComponentRect position provider + Popup_skiko clipping | 新HudTooltip锚控件顶部居中，200ms；组件内移动不重计时，离开重置，屏幕夹取；Chrome命中不再触发Shell的不同tooltip；原六条HUD字段说明及宿主Option.description接入 |
| 颜色弹层 | Designer.kt381–445 HUD ColorButton为32dp、TopStart offset(0,40)；ColorOption.kt303–825为280宽、pad16/gap14、SV160、horizontal hue16、selector14白边2、alpha/chroma/速度/hex | 重写HudColorPicker：横向hue；opacity百分比字段；alpha6物理px棋盘；流彩On/Off开关；仅流彩开启展示速度、输入.1..4且bar映射x*4；速度显示(速度-.1)/3.9；白thumb10；末行32swatch/10gap/6或8hex；HUD无gradient UI（源码gradientCapable=false），plain Int宿主无伪chroma；没有原版不存在的Apply/Cancel/Copy/Paste |
| 颜色关闭语义 | ColorOption.kt即时onColorChanged；Designer.kt ShellPopup.onDismissRequest仅expanded=false | Escape/关闭/点外部提交当前一事务，保留已实时写值；统一HUD Undo可回到打开前。此前“Escape回滚”的测试已改正为原源码语义；无效或不完整hex保留可编辑字符串，不改实际颜色 |
| 下拉 | hud/components/Dropdown.kt 55–119、123–158；dropdown/DropdownMenu.kt 25–72、124–144 | 实际DropdownPositionProvider的向上/向下与右边界定位；10dp offset；4pad+4行gap+32行；300ms enter/exit alpha；当前选项、高亮与箭头目标使用default spring。中途箭头旋转待Painter.rotate；临时矢量路径将删除后再标完成 |

本轮新文件：ui/hud/HudMotion.java、HudScrollAnimation.java、HudTooltip.java、HudContextMenu.java、HudPropertyMenu.java；src/test/java/com/muoniumplayer/configskija/hud/HudUiDetailsTest.java、HudMotionReferenceTest.java、run.ps1。生产没有引入Compose/Kotlin依赖；ReferenceTest只在显式 -ComposeReference 时以原缓存JAR做只读oracle，根verify.ps1已排除*ReferenceTest.java。

局部运行：


工作目录：E:/MUSIC/muonium-config-skija


验证命令：


`.\src\test\java\com\muoniumplayer\configskija\hud\run.ps1 -ComposeReference`

当前此命令通过。此前试跑 verify.ps1 曾停在 HostLibraryTest/迁移测试的缺失 host/Gson classpath 编译错误；该条仅保留历史验证记录。本次未重跑根脚本，主线程已反馈中央改键测试通过，整合结果由主线程记录。

运行桥跟进：编辑帧始终调用HudExtensions.renderHostHud，游戏帧才用HOST_COMPOSITION控制是否接管主项目两块。主线程已提供Painter.roundedPolygon多轮廓/tangent-arc实现，并反馈原 BackgroundUnion 真实多 path/runtime 已接入；HUD Chrome适配器已转发该方法，既不新建context，也不把headless扫描fallback当游戏路径。

## 2026-11-24 HUD快捷键实际行为补齐

原证据：OneConfigConfig.java:28–29为平台ACTION和ACTION|SHIFT；82–214为同名可录制偏好；999–1025的refreshHudEditorKeybind仅Undo拒绝额外Shift；1086–1111分别登记Undo/Redo/RedoAlt。OneConfigKeybind.kt:112–118是modifier子集 + 所有键/所有鼠标键均按下；KeybindManager.kt:235–254按triggered上升沿执行且typing时不触发；KeybindUtils.kt:10–16为Mac Meta/其他Ctrl。

- HudStudio实现现有AppearancePages.RawKeyInput，复用ConfigView.rawKey/rawMouse已有canonical token和释放事件，不要求新的PageContext输入方法。
- HudBindings按AppearanceSchema同名当前偏好匹配：hudSettingsKeybind、hudVisibilityKeybind、hudLockKeybind、hudCopyKeybind、hudCutKeybind、hudPasteKeybind、hudDuplicateKeybind、hudResetKeybind、hudDeleteKeybind、hudSelectAllKeybind、hudUndoKeybind、hudRedoKeybind、hudRedoAltKeybind、searchKeybind。没有固定Ctrl字母后门；NONE立即禁用。
- 左右Ctrl/Shift/Alt/Super、ACTION、KEY:物理modifier、纯modifier组合、多普通键、MOUSE:n以及键盘+鼠标组合全部通过真实raw callback测试。普通modifier保留原subset匹配；Undo拒绝未声明的额外Shift，所以Ctrl+Shift+Z只redo；显式KEY:LSHIFT自身绑定有效。
- 同一键/鼠标按住只触发一次，释放重新激活；原KeyBindings.InputState查询补全页面打开前已按住modifier，并修复录制/弹层截走key-up后的滞留状态。
- 已按住的键盘绑定不吞掉无关鼠标/方向键。HUD自有搜索、ctx.textFocused、任何弹层/录制期间均屏蔽父HUD命令；关闭页面清空输入与latched状态。
- 菜单和工具条提示读取同一个HudBindings.value；当前宿主提供LibraryServices.Keys时使用真正KeyBindings.display，其他情况下走主项目translate对应key.keyboard/key.mouse名。
- 原Modifiers action语义同时用于画布Ctrl/Meta多选与框选，不要求Mac用户使用Control。
- 通过153条改键专项测试；不涉及主线程负责的全局menuKeybind/search路由。主线程raw-first后仅在未消费时走全局命令，避免重复处理。

新增文件：ui/hud/HudBindings.java；src/test/java/com/muoniumplayer/configskija/hud/HudBindingsTest.java。

本轮完整所有权清单共28文件（含最后单位回归）：

- `src/main/java/com/muoniumplayer/configskija/model/HudDesigner.java`
- `src/main/java/com/muoniumplayer/configskija/model/HudElement.java`
- `src/main/java/com/muoniumplayer/configskija/host/HudExtensions.java`
- `src/main/java/com/muoniumplayer/configskija/ui/hud/HudBindings.java`
- `src/main/java/com/muoniumplayer/configskija/ui/hud/HudChromeContext.java`
- `src/main/java/com/muoniumplayer/configskija/ui/hud/HudColorPicker.java`
- `src/main/java/com/muoniumplayer/configskija/ui/hud/HudContextMenu.java`
- `src/main/java/com/muoniumplayer/configskija/ui/hud/HudDocument.java`
- `src/main/java/com/muoniumplayer/configskija/ui/hud/HudHostElement.java`
- `src/main/java/com/muoniumplayer/configskija/ui/hud/HudLibrary.java`
- `src/main/java/com/muoniumplayer/configskija/ui/hud/HudMerge.java`
- `src/main/java/com/muoniumplayer/configskija/ui/hud/HudMotion.java`
- `src/main/java/com/muoniumplayer/configskija/ui/hud/HudNode.java`
- `src/main/java/com/muoniumplayer/configskija/ui/hud/HudPanel.java`
- `src/main/java/com/muoniumplayer/configskija/ui/hud/HudPropertyMenu.java`
- `src/main/java/com/muoniumplayer/configskija/ui/hud/HudRuntime.java`
- `src/main/java/com/muoniumplayer/configskija/ui/hud/HudScrollAnimation.java`
- `src/main/java/com/muoniumplayer/configskija/ui/hud/HudStudio.java`
- `src/main/java/com/muoniumplayer/configskija/ui/hud/HudTooltip.java`
- `src/main/java/com/muoniumplayer/configskija/ui/hud/HudTour.java`
- `src/main/java/com/muoniumplayer/configskija/ui/hud/HudWidgets.java`
- `src/test/java/com/muoniumplayer/configskija/hud/HudBindingsTest.java`
- `src/test/java/com/muoniumplayer/configskija/hud/HudMotionReferenceTest.java`
- `src/test/java/com/muoniumplayer/configskija/hud/HudParityTest.java`
- `src/test/java/com/muoniumplayer/configskija/hud/HudUiDetailsTest.java`
- `src/test/java/com/muoniumplayer/configskija/hud/HudUnitsTest.java`
- `src/test/java/com/muoniumplayer/configskija/hud/run.ps1`
- `docs/hud-parity.md`

2026-11-24最终局部验证：409基础行为 + 80细节 + 153改键断言通过；4,820实际依赖spring位置/速度样本误差0，121反向样本、60结束时刻匹配；新增文件单独空白/控制字符/换行检查通过。显式tween也按已解析依赖的毫秒采样；Java17编译与headless测试不代表游戏验证。尚待共享Painter.rotate；不将临时箭头线段路径算作完整旋转已支持。

2026-11-24收束复跑：独立 run.ps1 -ComposeReference 再次全部通过（409 + 80 + 153；4,820样本误差0，121反向样本与60结束时刻匹配）。本轮仅更新交接文档，生产/测试 API 未变；完成交接后停止 HUD 写入，等待主线程统一整合和提交。

## 2026-11-24 实际 HudStudio raster 文字几何回归

输入证据：主线程 preview.ps1 生成的 build/previews/hud.png，已通过 view_image 实际查看。PreviewMain 的 Painter.Text.draw 使用 y-font.getMetrics().getAscent() 换算基线，与宿主已核实的 top-left 契约一致。

根因：HudWidgets.label 额外加 size；居中文本使用 (height+size*.72)/2；HudChromeContext.input 使用 (height+9)/2。这三处按基线计算，却传给了接收 top-left 的 Painter，导致 toolbar/search 被底部裁剪、分组标题下沉到下一张卡片。ScaledPainter 的字号、坐标和测量只做一次 density 变换，没有重复乘 density；HUD 画布仍保持 MC 布局像素。

本次修正：

- HudWidgets.java：label 直接使用顶部 y；行内文字使用 (height-size)/2 居中。
- HudChromeContext.java：输入文字同样按顶部居中；文字裁剪只保留左右6dp内缩，纵向采用输入矩形完整高度，避免较矮数字框裁字。绘制、命中、鼠标和字号的 density 变换保持同一契约。
- HudStudio.java：菜单按钮按实际中文字宽加图标/间距/边距留宽，后续按钮和 toolbar 外框同步移动，避免“菜单”显示为省略号。
- HudRuntime.java：fallback sample/title 同样遵守 top-left；移除重复 baseline，underline 位置保留独立基线偏移。未更改 EXACT_TEXT 回调契约。
- HudParityTest.java：记录绘制文字坐标/字号，测试 renderer 遵守 top-left，并使用全宽汉字测量。
- HudUiDetailsTest.java：新增53条回归，覆盖 density .75/1/1.5/2 下的真实 HudStudio toolbar、菜单完整标题、search 裁剪边界、分组标题与图标行/后续卡片间距及 fallback sample。

回归证据：新增测试在旧代码上首先失败（density .75 的 label top 为25.5，预期15）；修正后独立 run.ps1 -ComposeReference 通过：409行为 + 133细节 + 153改键断言；4,820个实际依赖 spring 样本位置/速度误差0，121保速度重定向样本及60结束时刻匹配。

另用当前 HUD 类、宿主实际缓存的 Skija JAR 与原 PreviewMain 重新生成10张 raster fixture，输出 build/previews-hud-text-fix/；已再次用 view_image 查看 hud.png：toolbar 完整、菜单中文不再省略、Search HUDs... 完整，分组标题与图标位于同一行且未压住下一张卡片。未覆盖主线程原 build/previews/，不将 fixture 写成游戏实测。

本次只修改上述6个 Java 文件及本文；公开 API 不变。交接后再次停止 HUD 写入。

## 2026-11-24 GUI轴与宿主布局轴的最终单位修复

源码证据（原仓只读）：

- api/hud/v1/Hud.kt:967–971、1302–1308：effectiveScale 在 GUI 轴中是 custom 或 custom/gui；414–431、525–572 的 section、relativeX/Y、手动 anchor offset 均使用 GUI 坐标；119、511–520 的最少可见边距为12 GUI单位。
- api/hud/v1/HudManager.kt:589–609：尺寸与布局使用内部有效缩放；1083、1142 的外层 gui scale 和局部 hud scale 共同进入绘制。
- internal/ui/hud/screens/HudDesignStudio.kt:147–177、574–588、1511及后续 mcToScreen：viewport 同时跟踪物理窗口和 GUI 尺寸，编辑框在转换后的同一轴绘制/输入。
- 26.3/.../compose/BlazeRenderer.kt:954–996 的 BlazeHud 和 LegacyHudRenderer.kt:67–94 分别核对了 Compose 与 legacy 的实际绘制链。本次只在中立模型补单位换算，没有复制其版本/加载器调用。

修复前只传真实 guiScale，却把 HUD 放在 RenderSystem 布局轴，缺少该轴到物理像素的比值。当前稳定接口：

~~~java
HudRuntime.Frame frame = new HudRuntime.Frame(
    layoutWidth, layoutHeight, realGuiScale, monotonicSeconds, dateTime,
    false, debug, tab, screen, chat, legacyKeys, variables
).withPhysicalScale(physicalWidth / layoutWidth);
// frame.physicalPerUnit()；frame.editing()保留该值。
// 旧12参constructor仍存在，默认physicalPerUnit=guiScale，表示纯GUI单位。

HudNode node = ...;
node.frame(realGuiScale, physicalWidth / layoutWidth);
// 旧 node.frame(guiScale) 同样保留纯GUI单位语义。
~~~

最终有效绘制比例为 customScale*(useGuiScale?realGuiScale:1)/physicalPerUnit。Painter外层已乘 physicalPerUnit，因此8px文字关闭GUI缩放时为8*customScale物理px，开启时为8*customScale*realGuiScale物理px。relativeX/Y 和原生节点手动 Link offset 继续保存原 GUI 标量，出入布局轴只乘/除 realGuiScale/physicalPerUnit 一次；宿主原生两块HUD的offset仍使用其既有布局单位。新增 HudElement.positionUnitScale() 明确这个边界，默认1，HudNode返回该GUI→布局比值。

HudDesigner 在frame更新、启动恢复、undo/redo/duplicate/新增节点前应用单位；HudRuntime 的启动/reload/tick/render、手动anchor及运行期合并轴链接均保留该边界。HudStudio/HudChromeContext/HudLibrary 的preview不再创建单位为1的Frame，新增拖放使用实际effectiveScale。未创建surface/native/context。

迁移协同：已向Popper交付真实双参node.frame；其Request第五参为physicalPerUnit，旧4/7参默认physical=gui。旧relative/anchorOffset原样保留GUI标量，无section的旧绝对x/y乘gui/physical一次。最后只读核对，主线程SkijaConfigService:87已调用五参Request，七条WindowAccess.hudFrame均已withPhysicalScale(physicalPerLayout())；这些宿主文件本任务未改。迁移侧5106断言由Popper/主线程记录，本任务不代称其为自己的游戏验证。

新增 HudUnitsTest：2,284物理场景、24,581断言；GUI1/2/3/4 × PL1/1.5/2/3/4 × 1200×720、1920×1080、2880×1620，九宫格与AUTO/显式CENTER增长点，useGuiScale两态、真实Painter变换后的动态文字/边距/背景、GUI位置双向保存、重复节点、手动offset(7.5,-2.25)、undo/redo、启动/换Profile恢复、库preview，以及父HUD改变高度后的运行期合并跟随。预期值按原GUI公式独立计算，再比较物理px；未依赖native游戏或fixture视觉推断。

测试入口仍为 src/test/java/com/muoniumplayer/configskija/hud/run.ps1 -ComposeReference（Java17独立编译，不跑Gradle）。最后结果：409行为 + 133细节 + 153改键 + 2,284物理场景/24,581断言通过；4,820原依赖spring样本误差0，121重定向样本与60结束时刻匹配。主线程整合构建/游戏人工验收另记。

本次单位修复修改文件：model/HudElement.java、model/HudDesigner.java、ui/hud/HudNode.java、HudRuntime.java、HudStudio.java、HudChromeContext.java、HudLibrary.java、hud/HudUnitsTest.java、hud/run.ps1、本文。公开Frame旧调用可编译；使用宿主布局轴必须传physical比值。此前已列的箭头中间SVG旋转、活引用剪贴板语义两项差异仍未冒称完成；本轮不再扩范围，交接后停止HUD写入。
