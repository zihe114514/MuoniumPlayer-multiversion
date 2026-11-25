# 主题与偏好设置迁移核对（2026-11-24冻结）

本页记录原仓只读源码对照、实现范围、自动验证和主线程接线契约。页面代码与局部行为检查已完成；主线程负责全局接线和集成编译，游戏内视觉/交互/动画由人工验收。本记录不表示已达到 1:1 或游戏验收通过。

冻结状态：2026-11-24。本轮只修改交接文档；主题/Appearance实现与局部测试写集保持原字节，不改主线程引用。主线程确认已复跑3,087个颜色样本（最大单通道差1/255）及120个spring样本（误差0）；字体已接常驻service，text/run.ps1的23+121条断言已复跑通过。集成包、提交与游戏人工验收由主线程继续。

## 页面入口与渲染契约

- 公共入口：`com.muoniumplayer.configskija.ui.pages.AppearancePages.themes()`、`preferences()`，返回 `UiPage`。
- Theme 保留原 accessor、原构造器、BUILTIN/byId/alpha/mix/radius，新增 `mixOklab(int,int,double)`、`interpolate(Theme,Theme,double)`、`transition(PageContext)`、`oklab(int)`、`fromOklab(double,double,double,double)`、`active(UiPreferences)`、`accent(UiPreferences,double monotonicSeconds)`、`accentEnd(UiPreferences,double)`。350ms 过渡由 Theme 自有的每窗口上下文轨道驱动，WeakHashMap 回收上下文，不受 context.animate 路由前缀影响；颜色不破坏旧 mix 的 sRGB 行为。
- `AppearancePages.Images.image(String path, Rect bounds)` 已由主线程SkijaPainter实现，按SVGDOM原色绘制；`Painter.image(byte[], bounds, nearest)` 的位图入口和icon的整体染色均不作为替代。冻结时重新按字节对照原仓：四张预览SVG、两份Minecraft OTF和四份品牌SVG均已存在且字节相同，指纹见下表。
- 可选原生细节接口 `AppearancePages.Surfaces.settingDecoration(Rect bounds,double radius,int borderColor,int textColor)`：在现有圆角 clip 内先画径向 vignette，center=bounds.center，radius=min(width,height)*.9，颜色为 textColor 替换 alpha 到 0/.02/.05，默认均匀 stops 0/.5/1；再画 1dp 圆角描边，borderColor 从顶部到最底 alpha=0 的纵向渐变。当前SkijaPainter已实现此接口（静态接线已核对）；非该renderer的fallback仍只有分段描边。接口存在不代替游戏中vignette与描边的人工视觉验收。
- Minecraft 字体与图标、branding 立即切换；颜色及 background/sidebar/card/checkbox/button/popup 固定圆角 350ms tween FastOutSlowIn。modIcon=4 / scrollbar=8（PolyGlass）；Minecraft 均为 0；这两种 extra shapes 不参与过渡。circle 在 Minecraft 为方形，PolyGlass 为圆形。

## 全量 UiPreferences 键（原字段名，不加前缀）

`activeTheme` 默认 `PolyGlass Dark`（原值为 name；兼容旧 id 和旧 `theme`）。`accentColor` 默认 `#FF2B4BFF`（兼容旧 `accent`），`animations` 默认 true。强调色扩展键：`accentColor.chroma=false`、`accentColor.chromaSpeed=1`（.1–4）、`accentColor.gradientEnabled=false`、`accentColor.gradientArgb=#FF2B4BFF`。chroma 优先于渐变。

`AppearancePages.RawKeyInput.rawKey(PageContext,String token,boolean pressed)` 和 `rawMouse(PageContext,int button,boolean pressed)` 应在普通页面命中/快捷键逻辑前调用；recording() 为 true 时吞掉宿主动作，且转发全部松开事件。普通按钮/卡片/滑块沿用 Common.kt.keepSearchFocus()：主线程命中处理应保留搜索框焦点；`.input` 与 `.keybind` 则进入各自编辑/录制。本页记录的是原交互契约，最终焦点行为以主线程当前输入路由和人工验收为准，不把早期ConfigView骨架作为验收依据。

字符串 chord 例如 CTRL+SHIFT+Z、ALT+MOUSE:1、NONE；纯修饰键为 KEY:RIGHT_SHIFT，表示字面物理键而非 modifier mask。

ACTION 表示原 KeybindUtils.getActionModifier()：Windows/Linux Ctrl，macOS Super；键位字符串存储格式由页面显式约定，不猜宿主注册 API。

| 键 | 原始默认值 | 类型 / 范围 / 选项 |
| --- | --- | --- |
| `oneConfigKeybind` | NONE | Keybind |
| `keybindClosesGui` | false | Switch |
| `hudSettingsKeybind` | ACTION+E | Keybind |
| `hudVisibilityKeybind` | ACTION+H | Keybind |
| `hudLockKeybind` | ACTION+L | Keybind |
| `hudCopyKeybind` | ACTION+C | Keybind |
| `hudCutKeybind` | ACTION+X | Keybind |
| `hudPasteKeybind` | ACTION+V | Keybind |
| `hudDuplicateKeybind` | ACTION+D | Keybind |
| `hudResetKeybind` | ACTION+R | Keybind |
| `hudDeleteKeybind` | DELETE | Keybind |
| `hudSelectAllKeybind` | ACTION+A | Keybind |
| `hudUndoKeybind` | ACTION+Z | Keybind |
| `hudRedoKeybind` | ACTION+SHIFT+Z | Keybind |
| `hudRedoAltKeybind` | ACTION+Y | Keybind |
| `showKeybindHints` | true | Switch |
| `marqueeSelectionAnim` | true | Switch |
| `marqueeSelectionAnimDuration` | 150 | 50–500, step 10 |
| `enableBackgroundBlur` | true | Switch |
| `hudDragUiOpacity` | 0.60 | 0–1, step 0.05 |
| `pageOpacity` | 88 | 0–100, step 1 |
| `sidebarOpacity` | 80 | 0–100, step 1 |
| `glowOpacity` | 25 | 0–100, step 1 |
| `pauseGame` | false | Switch |
| `uiScale` | 100 | 50–200, step 5 |
| `pixelGrid` | 0 | 0 Auto / 1 Always / 2 Never |
| `rememberLastPage` | 1 | 0 Never / 1 15s / 2 60s / 3 300s / 4 Always |
| `restoreHudEditor` | false | Switch |
| `showOpeningPageAnimation` | false | Switch |
| `showOptionActionButtons` | true | Switch |
| `flipTopOptionOrder` | false | Switch |
| `instantSearch` | true | Switch |
| `searchKeybind` | ACTION+F | Keybind |
| `searchDistance` | 2 | 0–10, step 1 |
| `guiOpenAnimation` | true | Switch |
| `guiClosingAnimation` | true | Switch |
| `animationTime` | 0.6 | 0.05–2, step 0.05 |
| `showPageAnimations` | true | Switch |
| `enableUISounds` | true | Switch |
| `enableUIMenuSounds` | true | Switch |
| `enableUIClickSounds` | true | Switch |
| `enableUISliderSounds` | true | Switch |
| `enableHudEditorSounds` | true | Switch |
| `enableUIAmbience` | true | Switch |
| `enableUIMusicDucking` | true | Switch |
| `uiSoundVolume` | 1 | 0–1, step 0.05 |
| `uiAmbienceVolume` | 1 | 0–1, step 0.05 |
| `pageAnimationDuration` | 0.3 | 0.1–0.6, step 0.05 |
| `showFirstLaunchMessage` | true | Switch |
| `profileSwitchToast` | true | Switch |

### 依赖和全局监听

- `restoreHudEditor` 在 rememberLastPage=0 时 disabled；禁用保留现值。
- `enableUIMenuSounds`、`enableUIClickSounds`、`enableUISliderSounds`、`enableHudEditorSounds`、`uiSoundVolume` 依赖 enableUISounds；enableUIAmbience 独立；enableUIMusicDucking 与 uiAmbienceVolume 依赖 enableUIAmbience。
- 其余开屏、闭屏、动画、页面记忆、搜索、blur、pause、缩放、像素网格、HUD 和音频设置均需要主线程/对应页面读取原键。只修改值不代表行为已接通。
- pageOpacity=88/sidebarOpacity=80 是直接替换背景 alpha（不是乘原 alpha）；glowOpacity=25 是全局光晕强度。flipTopOptionOrder 通过 ShellAppearance 更新顺序。
- uiScale=100 范围 50–200%，原实现等滑块停止变化 400ms 才应用；EDGE_MARGIN_FRACTION=.9，Shell 1391×700。pixelGrid=0 仅 Minecraft 启用、1 总开、2 总关；14sp 为网格锚，5px 步长、最小 10px。Provider.kt 的 surfaceRatio/fontScale/density 都参与换算，不能直接按 GUI scale 推断。
- rememberLastPage=0 从不；>=4 始终；1/2/3 仅 closedAt>0 且 now-closedAt<=15000/60000/300000ms。旧 openingBehavior>=0 时：2→4；3 按 timeBeforeReset>37.5→2，否则1；其余→0；迁移后 openingBehavior=-1。hudEditorTourSeen=false 为隐藏持久项。
- keybindClosesGui=false；热键关闭后 300ms 防同次按键重开，标记只能消费一次。oneConfigKeybind=NONE，主项目的 H 是宿主绑定，不能页面默认绑定 H。
- 主线程已确认开屏 EaseOutExpo=1-2^(-10t)、animationTime=.6s，关闭 min(animationTime,160ms) EaseOutCubic；scale=.9+.1*progress，alpha=progress。showOpeningPageAnimation=false 控制首次页面动画，showPageAnimations=true/pageAnimationDuration=.3s 控制页面切换。
- 同一音效族主题切换 CLICK，PolyGlass↔Minecraft 切换由 UiSounds.onSoundThemeChanged 重启 ambience 并播放新主题 OPEN。请全局 sound(event) 尊重这些开关和音量，不依赖页面模拟效果。
- HUD 撤销键需禁止额外 Shift，避免 ACTION+SHIFT+Z 同时执行 undo 和 redo；快捷键持有时间默认 0，鼠标键默认无。

## 静态证据

原仓根：`E:/oneconfig lite/MuoniumConfig-src`（只读）。下表以 `muoniumconfig-common/src/main/` 为基准；ui 指 `kotlin/org/polyfrost/oneconfig/internal/ui`，internal 指 `java/org/polyfrost/oneconfig/internal`。

| 原始证据 | 本页实现/核对 | 自动证据 | 人工验收 |
| --- | --- | --- | --- |
| ui/screens/Themes.kt | 四卡顺序；168×108 SVG；14 padding/gap、26 card gap；选中1→3描边；下方20间距 | 四次原色预览调用、切换键名/音效族 | 待验收 |
| ui/screens/Preferences.kt、internal/OneConfigConfig.java | 原声明顺序的50项可见偏好；无新增占位按钮；六个subcategory | 测试直接解析原源码对照 key/default/type/range/step/dropdown index；所有控件可达 | 待验收 |
| internal/ThemeConfig.java | activeTheme 隐藏；accentColor与animations两项 | 二项列表、默认强调色、主题名回读 | 待验收 |
| ui/themes/Impls.kt、Structs.kt、Radii.kt | 四套palette；background20/nav8/card16/checkbox6/button8/popup16；Minecraft全0；controlTrack74777F；shadow黑，Minecraft禁shadow；modIcon4/scrollbar8 | 默认/extra形状、开关flags、主题名 | 待验收 |
| ui/themes/AnimatedTheme.kt、Provider.kt | 350ms FastOutSlowIn，Oklab色值/固定圆角；font/branding/boolean/circle/extra shape立即切换；渐变被chroma抑制 | 350ms采样和反向；实际Compose JAR对照3,087色样本 | 待验收 |
| ui/themes/UiFontFamilies.kt | PolyGlass宿主字体；Minecraft声明原regular/bold路径 | 路径/模式接口已提供 | 常驻service已接，人工待验收 |
| ui/components/settings/SwitchControl.kt、SliderControl.kt | switch42×21/thumb15、offset3→24；slider300×19/track5/thumb19；步长映射、70ms刻度节流 | 端点拖动、步长/范围、actual JAR spring样本 | 待验收 |
| ui/components/settings/SliderOption.kt、NumberOption.kt | track旁16间距、87数字框；箭头32高度；输入过滤、实时clamp、整型截断 | 数值编辑与非法字符、min/max/step | 待验收 |
| ui/components/settings/DropdownOption.kt | 300×32触发器；popup间距10；4padding；行gap4；保留index | 真正选择4写rememberLastPage=4 | 待验收 |
| ui/components/settings/ColorOption.kt、kotlin/org/polyfrost/compose/render/PolyColor.kt | 280宽拾色器；HSV160高、色相/alpha16；6/8hex；透明度/彩虹/速度/双端渐变；同色启渐变+40deg | SV白角、chroma/gradient持久化与互斥、第二色自动生成 | 待验收 |
| ui/components/settings/KeybindOption.kt、KeybindConflicts.kt、api/ui/v1/keybind/OneConfigKeybind.kt | 多键/鼠标、全松开提交；Esc/空Delete解绑；纯修饰键；本页绑定冲突 | 连续松开、原默认空绑定、备用redo、mouse chord | 全局catalog冲突尚需主线程 |
| ui/screens/ConfigScreen.kt、ui/search/DefaultSearchProvider.kt、SettingIndex.kt | title15/description13/header22；8行间距；禁用整行.65；subtitle分组；原substring+Levenshtein搜索 | 搜索id/中文/group、distance=0/2、禁用无命中、依赖保持旧值 | 字体metrics和折行待验收 |
| ui/components/settings/Options.kt、ui/components/Common.kt | 原Reset to default/Unbind、右键/更多按钮；声音先于值变更；保留search焦点契约 | reset真实回写；菜单触发 | main命中优先级/焦点+人工 |
| 原解析依赖 animation/ui-graphics/ui-util-desktop 1.13.0-alpha02 | Oklab转换与Spring默认stiffness1500/damping1；本版本playTimeNanos截断为ms | 120 spring样本误差0 | 不代替动画观感验收 |


## 验证与缺口

运行命令（本工程内，纯Java17、无游戏/加载器/Skija native初始化）：

```powershell
.\src\test\java\com\muoniumplayer\configskija\appearance\run.ps1 -ComposeReference
```

- `javac --release 17` 局部编译通过；以 sourcepath 挂当前共享接口与实际需要的中立类，输出仅在本工程 build/appearance-tests。
- `AppearanceParityTest` 本子任务交付时466条断言通过（本轮文档冻结未重跑）：原声明对照、依赖、搜索、主题/强调色、录制、逐项可达、真命中操作、滑块/选择/输入/拾色器。
- `AppearanceComposeReferenceTest` 对原构建实际缓存依赖对照：3,087样本最大单通道差1/255（sRGB↔Oklab的浮点/半精度量化差异仍存在，不称位级一致）；120个弹簧位置样本误差0。主生产代码没有引入Compose/Kotlin依赖。
- 没有运行游戏或全仓构建；主线程负责最终集成编译包，人工负责游戏视觉/交互/动画验收。

### 冻结时接线状态 / 验收边界

1. `Images`、四张原SVG和两份Minecraft OTF已由主线程接入；冻结时字节核对通过，不再列作缺资产。字体常驻接线与测试见font-parity.md。
2. `Surfaces.settingDecoration`已由主线程SkijaPainter实现；本子任务只核对当前声明与接线，阴影、字重/行高、SVG颜色、圆角裁剪和高DPI仍需人工验收。
3. 当前ConfigView已转发`RawKeyInput`与鼠标按下/松开，声明和调用点静态可定位；真实宿主键名、修饰键持有和keyup仍需游戏输入验收。冲突红框本子任务仅核对本页15个绑定，不凭全局catalog名称声称已覆盖Minecraft/第三方冲突。
4. 声音、开闭/页面动画、blur/pause/scale、记忆/欢迎/通知、HUD偏好均依赖主线程/对应页面读取上述原键；写入持久化和局部交互已验证不等于全局行为完成。未接通的键须在集成报告明确列出，不将能拨动等同于有效。
5. `ThemeConfig.animations` 在原仓仅声明，查无消费点；本页将它用于本地控件spring开关，主题350ms过渡保持原Provider调用方式。主线程可把它作为全局动画门控，须作为明确实现决定记录。
6. `enableUIMusicDucking` 原仓26.3仅调用ambience refresh，服务stopAmbience(setDucking)忽略该参，未发现真正ducking消费点；主线程须接实际音乐音量行为或明确禁用/未完成，不能声称原有字段就有效。
7. 当前横向预览滚轮与列表滚动交由共享ctx/host通道；原WheelScroll.kt还有SMOOTH_TIME=.06s的临界阻尼wheel glide、.5dp/20dp/s停止阈值，尚未在此页独立重造滚轮系统。popup继承shell layer/scale由主线程统一处理，页面本身不另加缩放/开屏动画。
8. 当前文案沿原中文资源，主题页两项保留ThemeConfig中的英文literal；动态语言切换尚未引入本页，需后续本地化契约。


## 声音原始算法（只对已读取的26.3参考实现负责）

证据：共享 `ui/sound/UiSounds.kt`、`UiSoundTheme.kt`，及原仓 `26.3/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/sound/McUiSoundService.kt`。这里只提取算法；不能把该树MC API当作其余车道已核实的声明。

- play(event)：先检查 enableUISounds；再按 OPEN/CLOSE→enableUIMenuSounds、CLICK→enableUIClickSounds、SLIDER_TICK→enableUISliderSounds、五种HUD事件→enableHudEditorSounds；音量clamp(uiSoundVolume,0,1)，<=0不播放。
- ambienceActive 独立 acquire/release，acquire幂等；release走stopAmbience(false)；refresh仅ambienceActive时工作，enableUIAmbience关则stop，开则start。enableUIAmbience与enableUISounds无依赖。
- onSoundThemeChanged：先play(OPEN)，再停止当前ambience并按新主题start；普通同族切换仅CLICK。页面发出字符串 `theme_changed`，宿主应完成此顺序。
- 26.3 MODERN音量=uiSoundVolume，OPEN/CLOSE/CLICK/SLIDER→oneconfig:ui.open/close/click/slider，pitch1；HUD选择/拖开始/拖结束/resize结束复用click，pitch分别1/.9/1.1/1；resize开始无声。
- 26.3 MINECRAFT：click=UI_BUTTON_CLICK,pitch1,音量.5×；open/close仍复用modern文件；slider=NOTE_BLOCK_BASS,.3×，pitch按1.334840,1.414214,1.498307,1.587401,1.681793,1.781797六步循环；HUD选择/拖结束/resize结束=button click,.3×；其余无声。
- 环境音targetVolume：MODERN=.5×clamp(uiAmbienceVolume)，MINECRAFT=.08×。MODERN单条ui.pad_loop nativeLoop；MINECRAFT在ui.mc_ambient.{trees,moog_1,moog_2,lullaby}洗牌队列依次播放，重填时避免第一条等于上次最后一条。每500ms监测播放结束再启下一条，回MC线程检查当前实例身份，过期回调不接管新实例。
- 新实例立即设targetVolume，没有fade-in；stop触发fade-out，每tick减targetVolume/80，减到<=0停止。同主题还活跃的实例可取消fade-out并立即更新targetVolume。音乐ducking在该参考服务没有实际实现，见上述缺口。
- 滑块每次手势先将lastTickValue设为NaN，因此首次点击即使与原值相同仍可播放SLIDER_TICK；之后只在本次手势的刻度值变化时播放，最短间隔70ms；普通onClick先播CLICK再修改配置，因此关音效本次点击仍会响，开音效本次点击受旧false门控。

## 资源原路径与字节证据

原根下 `muoniumconfig-common/src/main/resources/assets/oneconfig/`，目标根为本工程 `src/main/resources/assets/muoniumconfigskija/`。本任务未移动/改写资源；主线程已原字节补齐，2026-11-24冻结时重新对照原仓，以下十项均已确认字节相同。

| 原相对路径 | 冻结时目标状态 | 原SHA-256 |
| --- | --- | --- |
| images/polyglass-dark.svg | 已存在且字节相同 | c764d03f1eb0ff042374cb089d169eb01a3a97d7072cc617e84fd452e9bb1ee3 |
| images/polyglass-light.svg | 已存在且字节相同 | 81e9529517a3de661a235ce31be979108746d7fa0b485da4ea84dd6164392577 |
| images/minecraft-dark.svg | 已存在且字节相同 | a85bbf7ff54d0b0fc6748e7580a04cafde94d3ebc461a1b5c7ffd7e952a469d0 |
| images/minecraft-light.svg | 已存在且字节相同 | 6b04539be02e1976066ecda25f320c5c1b071cdaa13f617ae046817cfa141d84 |
| fonts/minecraft/Minecraft-Regular.otf | 已存在且字节相同 | c5bcd562c9151e23deb35ce8fb069bf3f8d22e1b71f0e2b70e253ee3925c2174 |
| fonts/minecraft/Minecraft-Bold.otf | 已存在且字节相同 | 62a3173d3d0fb1e7fd836dc08b2c00fab9d0724b77d8a2a0c8341f1b84c1db28 |
| brand/oneconfig.svg | 已存在且字节相同 | cd2cc56bdbd41a60d12b66214dd66cebd3b7c8f6fab0e2a66ef71c98c9fde968 |
| brand/oneconfig-light.svg | 已存在且字节相同 | ef4e400766dbfd08aef11a4ea87931094e02c2a68c3b6ead378960ba53016245 |
| brand/oneconfig-mc.svg | 已存在且字节相同 | cd2cc56bdbd41a60d12b66214dd66cebd3b7c8f6fab0e2a66ef71c98c9fde968 |
| brand/oneconfig-mc-light.svg | 已存在且字节相同 | ef4e400766dbfd08aef11a4ea87931094e02c2a68c3b6ead378960ba53016245 |

## 本任务修改路径

- src/main/java/com/muoniumplayer/configskija/ui/Theme.java
- src/main/java/com/muoniumplayer/configskija/ui/pages/AppearancePages.java
- src/main/java/com/muoniumplayer/configskija/ui/pages/AppearanceSchema.java
- src/main/java/com/muoniumplayer/configskija/ui/pages/AppearanceControls.java
- src/main/java/com/muoniumplayer/configskija/ui/pages/AppearanceBindings.java
- src/main/java/com/muoniumplayer/configskija/ui/pages/AppearanceMotion.java
- src/main/java/com/muoniumplayer/configskija/ui/pages/AppearanceColorPicker.java
- src/test/java/com/muoniumplayer/configskija/appearance/AppearanceParityTest.java
- src/test/java/com/muoniumplayer/configskija/appearance/AppearanceComposeReferenceTest.java
- src/test/java/com/muoniumplayer/configskija/appearance/run.ps1
- docs/appearance-parity.md

未改原仓、主项目、ConfigView/PageContext/Painter/ConfigHost或队友文件；测试产物仅在本工程build/。未提交、推送或运行游戏。

滚轮原事件证据补充：原仓26.3 ComposeScreen.kt WHEEL_NOTCH_DP=150（mac每reported unit=50），precise单位Windows=150/其他平台=10；precise识别保留250ms，Linux把120细步视作wheel。PageContext目前仅一个delta通道，主线程如恢复完整滚轮手感需一并保留精细滚轮标识/横向分量。

## 冻结写集与源码指纹

本轮仅收束本文件与font-parity.md；下列十个主题/页面/测试文件保持原字节。Git当前将这些文件列为未跟踪，冻结不会自动加入暂存区，主线程提交时应按本写集纳入；不含同目录其它队友页面。提交、集成包和人工游戏验收尚未由本子任务执行。

| 路径（相对本工程） | SHA-256（2026-11-24冻结） |
| --- | --- |
| `src/main/java/com/muoniumplayer/configskija/ui/Theme.java` | `391ab823b8a672664a2fad1a1d4b495ae58b49472f3b44bef53f11ba6be9bcb7` |
| `src/main/java/com/muoniumplayer/configskija/ui/pages/AppearancePages.java` | `0c1216ec9079fc976f21518cb138fba22ce8f5cd2d4b76cf7036b22e054aa404` |
| `src/main/java/com/muoniumplayer/configskija/ui/pages/AppearanceSchema.java` | `f13e9f946d100e7840f5c5095c4612847a8dddf4e32d1616d7cea6470eef01f5` |
| `src/main/java/com/muoniumplayer/configskija/ui/pages/AppearanceControls.java` | `17080fa31e3a88f1a059d0e10f3fe843eaf9859895af5483343f6bb07d0bd0ed` |
| `src/main/java/com/muoniumplayer/configskija/ui/pages/AppearanceBindings.java` | `4a6d7cfe052a2de066e4973bd5bb29d9fee0f26dbc14d7b490e0449f2aed6d03` |
| `src/main/java/com/muoniumplayer/configskija/ui/pages/AppearanceMotion.java` | `37f39d4c9dedaecb7fe4d3b8a207b0fec3e2e7ff4c278ba94b197492b6b21f0b` |
| `src/main/java/com/muoniumplayer/configskija/ui/pages/AppearanceColorPicker.java` | `ad2f4c2db23f76bf661ac5e91600099d9bb2e360bf76669fde8a17bd15c068d8` |
| `src/test/java/com/muoniumplayer/configskija/appearance/AppearanceParityTest.java` | `b74d1fe1d0b1808aa649d9541daf23853be0c98c252ea44c53f2bb3dd60eddbd` |
| `src/test/java/com/muoniumplayer/configskija/appearance/AppearanceComposeReferenceTest.java` | `1ab154c1eca854ea02babbb85113d2c81977008699903a7793e1fb485fd5dd52` |
| `src/test/java/com/muoniumplayer/configskija/appearance/run.ps1` | `334064814bcfc7029c016c829f3688b4553b47aa0dd2f03aecf8cad82350175d` |
