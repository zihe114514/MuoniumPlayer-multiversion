# 设置页与控件移植核对表

核对日期：2026-11-24（Asia/Shanghai）。原仓只读根：`E:/oneconfig lite/MuoniumConfig-src/muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/internal`。行号为本次实际读取源码，不是永久API白名单；SHA-256前16位用于定位快照。原仓Git元信息受本机ownership检查限制，未更改safe.directory；以下以文件内容证据为准。

## 交付入口与边界

- `public SettingsPage implements UiPage`，`SettingsPage(boolean favorites)`；`SettingsPage(boolean favorites, SettingsSchema presentation)`可显式注入呈现元数据。
- 注册：`view.register(Navigation.Page.SETTINGS, new SettingsPage(false)); view.register(Navigation.Page.FAVORITES, new SettingsPage(true));`。不在本任务越权修改ConfigView。
- 使用`route().category()`选分类，`query()`搜索，`host().favoriteCategories()`提供原FavoritesTree内置常用树（不读取用户pin）；内容坐标原Shell dp，自身内容及accordion/list局部二次clip，popup用独立UiPage/overlay。
- 默认只从真实`ConfigHost.Kind`映射五类。额外样式由`SettingsSchema.Provider`或构造器提供，绝不从id/title猜checkbox/accordion等。Option.value()/title()/disabledReason实时读取，点击和异步回调再次检查enabled+visible。
- 全部新增生产源码在`ui/settings/**`，无MC/loader/Compose/Skiko依赖，无第二份播放器业务或配置存储。原仓和主工程已有未提交文件未写入。未提交/发布/启动游戏/运行Gradle。

## 已实现与自动测试范围

28个visualizer：Switch、Checkbox、Slider、RangeSlider、RangeNumber、InheritableSlider、NumberChain、Number、Text、TextList、File、FileList、ColorList、NumberList、SliderList、RangeSliderList、RangeNumberList、Dropdown、Radio、Color、Keybind、Button、DraggableList、MultiSelect、Info、HUD Dropdown、HUD Radio、Alignment。

局部Java17 harness覆盖：每类挂载、路由/分类chip/搜索、live title/value、动态隐藏/锁定与过期命中、内置八项常用树及空树回退、slider端点/吸附/精确draft/单事务撤销/取消、range交叉draft/排序持久化/失焦、NumberChain lockedLeading/失焦提交、dropdown键盘与外部点击、HSV/ARGB/hex/alpha/gradient端点、duplicate list稳定身份、file callback离屏失效、多键按下/释放和modifier分离、动态数值边界、过期gesture回调隔离、跨Number wrapper的inherit sentinel。400ms确认防双击、context reset/copy（无setting pin）、multi-select全选/保持弹层、AWT Color[]类型保持也有独立行为测试。绘制几何断言仅证明有效几何与canvas栈平衡，不证明视觉1:1。

最近一次局部命令成功输出：`Settings harness passed: 2230 checks, 28 visualizers; no game launched.`、`Text input harness passed: 112 interaction assertions.`、`EntryRows harness passed: 19 integration assertions.`；另单独运行既有`CoreTest`通过258断言。旧逐像素strip/扫描线已删除，逐帧几何断言总量随之降低，交互场景继续保留。 断言数包含每帧几何检查，不将断言次数当成独立交互场景数量。

可复现命令（本脚本不启动Gradle或游戏）：

```powershell
& 'E:/MUSIC/muonium-config-skija/src/test/java/com/muoniumplayer/configskija/settings/verify.ps1'
# 可选：使用已有宿主依赖快照和其相同版本native生成离屏fixture，不是游戏验收
& 'E:/MUSIC/muonium-config-skija/src/test/java/com/muoniumplayer/configskija/settings/verify.ps1' -Raster
```

本任务文件通过独立git diff --no-index --check（以空文件作未跟踪文件基线）；没有stage/commit。

已生成的离屏fixture在`build/settings-previews`，五张为设置页、dropdown、color、accordion、text-list。fixture只用于渲染检查，标题明确为测试数据；不能当成游戏实测或原仓截图对比。

## 原仓逐项证据

| 原仓文件（相对上述internal根） | 证据行 | 真实尺寸/算法/时序 | SHA-256前16位 |
| --- | --- | --- | --- |
| `ui/screens/ConfigScreen.kt` | 149–203, 360–488, 506–570, 595–650, 670–845 | chips ≥33，8/19 dp gaps；SHOWN/HIDDEN/DISABLED；锁定 .45、禁用 .65；card16；padding16×12；accordion 2列/8间距；title15/description13；nested tooltip260。 | `c81ef81f42d3a313` |
| `ui/api/settings/BuiltinVisualizers.kt` | 28–101 | 25个注册入口及style变体，共映射当前28种Skija visualizer（含HUD Dropdown/Radio/Alignment）。 | `c511494234d84eb4` |
| `ui/components/settings/SwitchControl.kt` | 37–63 | On/Off12sp；gap8；42×21；thumb15；offset3↔24；spring。 | `a7581098a84c83fd` |
| `ui/components/settings/CheckboxControl.kt` | 41–73 | 24×24；border1.5；tick26；On/Off12/gap8；fade visibility。 | `bc6e737a8a949d68` |
| `ui/components/settings/SliderControl.kt` | 39–121 | 300轨宽由LocalOptionWidth给出；thumb19/track5；usable=width-thumb；min锚点roundToInt；70ms tick。 | `12497e563817bf82` |
| `ui/components/settings/SliderOption.kt` | 26–47 | slider + gap16 + NumberSpinner（默认87）；无step spinner用1。 | `01b8f5bf07305630` |
| `ui/components/settings/NumberOption.kt` | 49–79, 99–213, 217–233 | 单负号/小数点输入过滤；87宽、start8/top-bottom5；箭头18×13；实时输入clamp；format遮罩不污染编辑draft。 | `8def5adc749d3962` |
| `ui/components/settings/InheritableSliderOption.kt` | 54–169, 174–193 | track19/5；80 spinner；16gaps；inherit chip10×6 padding；读取sentinel/实时继承值。 | `fac2bd8dcd77283c` |
| `ui/components/settings/RangeOption.kt` | 55–151, 159–311 | 排序持久化，交叉草稿允许；blur归位；arrow上下限；thumb13×16/track5；nearest thumb；相等时等移动方向；spinner80/20分隔。 | `d914464dced60cb6` |
| `ui/components/settings/TextOption.kt` | 37–87 | LocalOptionWidth300；14sp；icon18、gap12、padding12×7；multiline min96；regex未通过不写宿主。 | `92213e3d411164ca` |
| `ui/components/settings/DropdownOption.kt` | 69–169 | 300×32；padding12；popup gap10/max280/padding4/gap4；item padding12×8/13sp；expanded border/accent与chevron0↔180。 | `6eeff7632f660a45` |
| `ui/components/settings/RadioButtonOption.kt` | 35–143 | outer padding3；item min32、padding8×4/12sp；选中指示器位置/宽/高spring。 | `1be6785e41b6e134` |
| `ui/components/settings/ColorOption.kt` | 74–163, 187–300, 303–826 | AARRGGBB alpha优先；swatch padding12×6、icon14/gap12；popup280/padding16/gap14；SV160；hue/alpha16；selector14/border2；alpha56宽输入；chroma与gradient仅PolyColor可持久化；gradient相同端点+40°。 | `3bf02e6bc0dc5066` |
| `ui/components/settings/ButtonOption.kt` | 30–77 | padding16×6、13sp；confirm危险红E35B5B；首击后至少400ms第二击；确认前后固定宽。 | `4462c5e81fb0025d` |
| `ui/components/settings/InfoOption.kt` | 21–62 | icon24、gap10；heading16sp/19line；body13sp/16line；Info/Success/Warning/Error颜色。 | `520a920a057c6bc2` |
| `ui/components/settings/Options.kt` | 56–81, 112–166, 173–251 | default存在才允许reset；dots24/icon16；context padding4/item10×6/gap8；hover tween120；原默认菜单reset+keybind unbind。Copy为本次明确要求扩展；设置favorite/pin非原行为，已删除。 | `38c60c4b572a64e4` |
| `ui/components/settings/KeybindOption.kt` | 61–101, 111–287 | 原平台键码；KEY_TYPED忽略；全部key/mouse释放提交；modifier分离；modifier-only保留literal；Escape/Delete/Backspace unbind；保持运行时subtype由host负责。 | `f8b9b1d296bb0431` |
| `ui/components/settings/KeybindConflicts.kt` | 28–73 | 真实冲突须host基于全部绑定/上下文给出；context gated条目排除；不是仅比较显示字符串。 | `7015ade3cab30b4b` |
| `ui/components/settings/ListOptionScaffold.kt` | 71–123, 139–340 | stable slot IDs；row34/gap8/container8；max280；handle16/trash16/gap10；add与maxEntries；重复值可独立移动。 | `3f5f3bf6a2edfb12` |
| `ui/components/settings/TextListOption.kt` | 17–70 | 正则无效行留draft/红框；写宿主过滤空白和invalid。 | `f53f99d722f9c254` |
| `ui/components/settings/FileOption.kt` | 48–158 | 只允许一个native dialog；后台选择/UI返回；取消不写；路径末段强调；clear16/folder18；14sp。 | `733f6207bb1d9c93` |
| `ui/components/settings/FileListOption.kt` | 30–113 | 文件列表过滤空白；folder16；callback按stable ID；路径文本13sp。 | `fed3322ff3b965b2` |
| `ui/components/settings/ColorListOption.kt` | 38–109 | 默认白色；padding8，icon12/gap8/12sp；列表swatch popup offset38；无chroma。 | `8badb7c7b26dc333` |
| `ui/components/settings/NumberListOption.kt` | 12–74 | slider14/track4+spinner66；number spinner默认87；number类型按实际容器保留。 | `7df83736db0b752b` |
| `ui/components/settings/RangeListOption.kt` | 21–78 | flat两数一组、缺end用max；排序写入；thumb11×14/track4；spinner56。 | `3010679b5432009c` |
| `ui/components/settings/NumberChainOption.kt` | 83–136, 163–275, 282–334 | lockedLeading不可移/改/删；36初始row/gap8/container12；blur/Enter提交；setter后重读归一化；nextValue由metadata给出。 | `9cb06d47a71240b8` |
| `ui/components/settings/DraggableListOption.kt` | 58–120, 127–238 | plain32/checkable40/gap14/container15/max280；duplicates stable slot；checkable保存启用顺序，disabled补尾。 | `a380f27eb0a6199a` |
| `ui/components/settings/MultiSelectDropdownOption.kt` | 53–200, 210–285 | boolean[] checkable，或int单选；全选/取消全选；40dp checkbox item；保持popup不关闭直到明确dismiss。 | `60de93654fba3d6b` |
| `ui/hud/components/Dropdown.kt` | 47–155 | HUD独立175×34；12sp；popup gap10/item32。 | `602fe63830bb7f7c` |
| `ui/hud/components/Radio.kt` | 29–65 | HUD图标radio每格36、icon18、label14/gap10。 | `062764a3cffc80d9` |
| `ui/hud/components/AlignmentPicker.kt` | 36–98, 120–140, 188–314 | 125×125/3网格；corner radius8；hover150ms/.08；selected1→1.15 medium-bouncy(stiffness1500,damping.5)→1 tween150；stroke1.5/len8。 | `1e3694b8aec0100b` |
| `ui/components/Chips.kt` | 25–66 | min33；padding12×7.5；text14；accent与hover渐变框。 | `414ad0c9de7a71ac` |
| `ui/components/Borders.kt` | 26–52, 118–145 | 边框内缩stroke/2；真实accent brush；滚动fadingEdges32。 | `a2f05a3ba013b229` |
| `ui/api/TextComponentRenderer.kt` | 115–138 | tooltip200ms；rounded8；padding12×8；nested max260由ConfigScreen传入。 | `4dcfcf19fa21bef2` |
| `ui/themes/Radii.kt` | 22–33 | XS6/SM8/MD16/LG20；内嵌concentric radius减padding。 | `4bd571249a711524` |
| `ui/themes/UiFontFamilies.kt` | 21–38 | Medium使用regular字节；SemiBold/Bold使用bold字节，不能用bold代替Medium。 | `a1051fcc7dcba55c` |
| `ui/themes/Provider.kt` | 37–58, 77–84 | accentEnd+chroma由主题宿主提供；scrollbar min24/thickness8/hover300。 | `ba407173e3878725` |
| `ui/api/settings/OptionData.kt` | 20–529 | visualizer完整metadata、动态bounds、容器numeric类型、format、regex、noAlpha、gradient、file types、lockedLeading与nextValue。 | `1ebb27cf8b2379e3` |

## 实际解析的动画依赖证据

原仓`gradle/libs.versions.toml:21`与`gradle/private-runtime.json:28-29`指定Compose `1.13.0-alpha02`。核对本机实际缓存JAR：

- `org.jetbrains.compose.animation:animation-core-desktop:1.13.0-alpha02`，SHA目录`72f7478dbaf8a1a847de7e743ca887d42006ef40`，`javap -c -p androidx.compose.animation.core.AnimationSpecKt/AnimateAsStateKt`：默认spring stiffness1500、damping1，float visibility threshold.01；`javap -constants ...Spring`确认MediumBouncy=.5、MediumLow=400。
- `org.jetbrains.compose.animation:animation-desktop:1.13.0-alpha02`，SHA目录`3dfdfe140dee7b28c6c8b9919015ba0c4b8734c`，`EnterExitTransitionKt.expandVertically$default/shrinkVertically$default`：stiffness400、damping1、Bottom对齐、clip=true。
- `SettingsMotion`采用真实秒数解析阻尼弹簧，重定向保留速度；accordion保留Bottom reveal。显式120ms context hover、150ms alignment hover/return依源码使用tween。

## 仍需宿主/共享接口接线（不能写成不适用）

1. **呈现元数据**：当前MuoniumHost已实现SettingsSchema.Provider，提供实际五类模型样式、slider formatter、主题accentEnd和本地化。原完整hidden/accordion/列表等metadata仍须来源明确的宿主绑定，不能从id/title猜测。Builtin favorites通过独立ConfigHost.favoriteCategories提供，不混入categories。
2. **defaults/reset**：只有Option.reset()!=null注册可操作reset。MuoniumHost已提供回调；控件仅调用真实callback。原MuoniumFavorites确实在注册时捕获getter为default；普通Settings其它类型default来源仍由宿主与其原metadata逐项核对，不能将两者混为一谈。
3. **文件选择**：`SettingsSchema.chooseFile(Option,OptionStyle,String,Consumer<String>)`须使用宿主现有native file API并后台运行、回UI线程。控件对取消/重复打开/离开/隐藏/删slot作了防护。宿主未接线时保留路径手工输入并明确toast，不假装弹出原生选择器。
4. **keybind**：`SettingsPage.rawKey(ctx,int,boolean)`须接真实按下与释放；`modifierBit/keyName/mouseName/bindingConflict`须接真实宿主平台。`Binding`只承载结果，运行时subtype和manager替换由宿主setter负责，不能只改显示字符串。
5. **颜色能力**：Int/AWT Color只写ARGB；`SettingsSchema.ColorValue`才承载chroma/speed/gradient/endpoint。宿主必须真实持久化这些字段；`accentEnd(ctx)`与`text(key,fallback)`负责主题渐变/本地化，默认单色和英文fallback不伪装为已接本地化。
6. **history**：设置页owned drag/popup/reset事务均传入真实Option调用ctx.beginEdit(option)，事务内直接调用真实Option.set，由同一commitEdit捕获；普通离散操作仍走ctx.edit。harness已断言volume/ink事务传实际id，不按label猜测。主线程已将8个favorite Option纳入history byId；ValueSnapshots/per-option scope/diff restore已由主线程接入，不能继续将旧浅快照缺陷写成当前已复现。

## 具体差异与待完成接口

以下是代码/契约差异，不与“待人工验收”混为一类；接线完成后逐项更新：

| 项目 | 原仓要求 | 当前实现/差异 |
| --- | --- | --- |
| 普通/accordion卡片vignette | radial 0→.02→.05、原尺寸计算 | 已调用真实Painter.vignette；普通center/minDimension×.9，accordion顶部center/width×.8；自动测试核对传参。 |
| range三角滑块 | cornerRadius2，inset bisector，round stroke | 已调用真实Painter.polygon，三角顶点与radius2均有自动检查；不再保留扫描线。 |
| 数字/颜色/列表文本字段 | 裸field 12/13/14sp；已有外框 | 已实现TextInputController.TextInput子接口，settings与popup实际走bareTextField，零ctx.textField调用，无重复外框；onBlur/Enter/Esc/selection由实际控制器处理，主线程已应用下方接线并由真实ConfigView事件测试验证。 |
| formatter/单位 | focused时原始数值，blur时formatter遮罩与unit | 原始draft与formatter已分离；bare Style.suffix在原始数字后绘单位，不写入文本。 |
| multiline | min96，可换行、上下移动 | TextEdit支持LF/CRLF、逻辑行列，控制器按测量软换行和视觉行导航；TextOption按内容增长且min96。真实宿主多行输入/Enter/Esc已有测试。 |
| tooltip | 200ms、nested max260、12sp、padding12×8 | 页面注册正确description/disabled reason；当前Shell已200ms/max260，但ConfigView.drawTooltip统一fontSize13，nested源12sp的差异仍需主线程收口。 |
| border gradient | 连续垂直渐隐边框/accordion body反向渐变 | 已调用真实Painter.gradientOutline，普通正向和accordion body反向，删除strip。 |
| 文件路径 | 前段secondary、最后段text | bare Style.path已分色目录/文件名，保持原始路径/selection offset，不修改编辑值。 |
| 本地搜索provider | 原ConfigScreen异步corpus scope+provider | 已接`SearchIndex.matches/trimQuery`字段OR、实时searchDistance偏好与whole/child规则；当前本地筛选同步，未在设置页自建worker/index，主Shell的corpus/provider生命周期由主助手集成。 |
| 特殊List subtype | 标准List属性以List契约替换；特定声明类型依宿主 | 标准array元素类型已保留；List结果为ArrayList，需特定concrete subtype的宿主setter负责适配，不反射猜构造器。 |
| 字体行高 | Compose真实font metrics | 当前SettingsSchema.lineHeight可注入，默认size；须接实际字体metrics后再作像素验收。主painter numeric drawWeighted/measureWeighted已接HostTextRenderer，不能再报该后端未接；当前settings标题/测宽仍是Painter.boolean路径，15sp Medium=500需要通过数值字重路径收口。 |
| popup Shell过渡 | 跟随ShellTransition alpha/scale/pivot | popup作为overlay，由Shell统一过渡；未在控件内部另加近似动画。 |

## 人工验收（实现后验证，不能当成功能缺失或已完成1:1）

人工确认游戏内字体/字号/换行、GUI scale和resize、卡片/渐变/三角抗锯齿、hover/展开/弹簧回弹与打断、tooltip、焦点/Tab/输入法/复制粘贴、wheel containment、首开重进、关闭与异步结果回收、host当前值及reset/undo/redo持久化。未启动游戏或CUA，本任务不作任何游戏通过结论。主助手负责整合与七车道构建范围，局部javac/raster不外推14组合兼容。

## 文本控制器实际接线（2026-11-24）

新增`ui/TextInputController.java`与扩展`ui/TextEdit.java`，不更改ConfigView/PageContext。`TextInputController.TextInput extends PageContext`含默认可执行的`bareTextField`；主线程只需持有一个控制器并接事件，不需要复制输入实现：

```java
// ConfigView implements TextInputController.TextInput
private final TextInputController textInput = new TextInputController();
@Override public TextInputController textInputController() { return textInput; }

// 一帧：begin在header/page前，end在所有弹层注册后；field()登记时立即裸绘制。
textInput.beginFrame(this);
// render header/page; 它们通过 bareTextField 登记字段
if (overlayPage != null) {
    textInput.pushScope(this, "overlay", true);
    try { overlayPage.render(this); }
    finally { textInput.popScope(); }
}
textInput.endFrame(this);

// 所有ctx坐标先从screen转换到原Shell dp。
if (textInput.pointerDown(this, button)) return true; // 在普通hit/页面点击之前
textInput.pointerMove(this);                         // 鼠标移动，包括控件外拖选
textInput.pointerUp(this, button);
if (textInput.wheel(this, delta)) return;
if (textInput.key(this, key, typed, control, shift, alt)) return true;
// textFocused()须OR textInput.focused()。导航/失焦在切换owner之前：
textInput.blur(this);
// screen disposal:
textInput.clear(this);
```

`bareTextField(id, edit, bounds, Style, onChange, onBlur[, onSubmit])`是真实可运行默认方法。Style含fontSize/lineHeight/multiline/softWrap/filter/maxLength/placeholder/suffix/path/clip/enabled/readOnly/alignment/颜色。bounds是已扣除padding/icon的文字矩形；控制器不画外框。PageContext未实现子接口时SettingsPage与ColorPicker各有实际local controller fallback，字段仍裸绘制；鼠标移动须转发页面的`pointerMove(ctx)`或应用全局控制器。

焦点/事务约定：onChange处理实际过滤后字符串；Enter单行结束，Ctrl+Enter多行结束；普通多行Enter插LF；Esc还原本次focus baseline并经onChange回写，然后onBlur一次，不调用onSubmit；Tab/ShiftTab按当帧视觉登记顺序（含重排后）移动，禁用/隐藏不参与，modal scope不进入背景。导航/移除/关闭/失焦onBlur只一次。NumberChain只在结束时提交，range允许交叉草稿并在blur归位；普通数字实时clamp。单行bare字段保留粘贴中的原始CR/LF，通过显示转换将LF→空格、CR→U+FEFF，复制仍取原值；旧TextEdit调用保留strict单行过滤兼容。Enter/Esc会话行为为本次明确要求，不据此声称所有原BasicTextField的默认ImeAction都相同。

键鼠/渲染实现：UTF-16边界不拆代理对；combining/ZWJ按grapheme移动删除；测量prefix定位光标；点击拖选、词双击、逻辑行三击及按词/行拖选；Home/End视觉行，Ctrl+Home/End文档，Shift扩选，Ctrl词/段落导航；copy/cut/paste；本地undo/redo；水平/垂直caret scroll、wheel、越界拖选scroll；selection和caret在字段clip内；readonly可复制不可写。TextEdit原`text/move/edge/click/insert/delete`调用保留兼容，原单行默认4096限制保留；bare字段按原BasicTextField无maxLength参数设Integer.MAX_VALUE，不给设置文本附加4096上限。

实际依赖核对：`org.jetbrains.compose.foundation:foundation-desktop:1.13.0-alpha02`，SHA缓存目录`d7af3c6be6d9118e49857a526c7b195bad4fc98a`。

- `KeyMappingKt$commonKeyMapping$1/defaultKeyMapping$1$1`字节码含COPY/CUT/PASTE/SELECT_ALL、LINE_START/END、TEXT_START/END、WORD、PARAGRAPH、NEW_LINE/TAB。
- `SelectionGesturesKt.mouseSelection`：2击Word，>2击Paragraph；`ClicksCounter`使用平台ViewConfiguration doubleTapTimeout与distance。控制器提供`clickConfiguration(seconds,slopDp)`接实际值，默认500ms/4dp仅在宿主未提供时使用。
- `TextFieldCursor_desktopKt`真实默认cursor thickness=1dp；`CursorAnimationState$snapToVisibleAndAnimate$2$1`500ms亮/500ms暗；原NumberChain注释中的2dp只用于其局部测量，不代表当前Desktop默认。
- `SingleLineCodepointTransformation.transform`：LF(10)→space(32)、CR(13)→U+FEFF(65279)；`TextFieldSelectionManager.paste`直接拼接原AnnotatedString后onValueChange，无newline删除。
- `DefaultTextSelectionColors_desktopKt`真实默认selection色FF4286F4、background alpha.4；原仓未提供LocalTextSelectionColors覆写。Style可显式覆写。

边界：控制器消费宿主已提交的typed字符串。OS输入法候选/预编辑组合、双向文字或复杂脚本的原生glyph cluster命中仍需宿主提供相应平台/字体事件与布局；未把纯prefix测量外推为这些场景的全等保证。主线程负责应用事件接线和实际字体metrics，视觉/动画/游戏仍待验收。

## 全局搜索行复用

`ui/settings/EntryRows`直接调用SettingsPage包内同一`rowHeight/drawRow/accordion`，不复制控件画法。入口：`EntryRows()` / `EntryRows(SettingsSchema)` / `EntryRows(Function<SearchDocuments.Owner,SettingsSchema>)`；`beginFrame(ctx,viewport)`、`render(ctx,NarrowedRow,offered)->height`、`endFrame(ctx)`。返回高度不含外部8dp gap；offered.x/y/w提供位置和宽度，h不参与测量。`pointerDown/pointerUp/pointerMove/wheel/key/rawKey/recording/leave`可供SearchPage.Renderer转发。每owner保留同一SettingsPage状态，字段/hit/动画带owner命名空间，真正Option对象及history identity不克隆。

SearchDocuments.NarrowedRow.body决定whole/child范围，dynamic hidden再次过滤。owner locked整层.45，option disabled独立.65；lockExemptId按真实master id豁免leaf，accordion owner alpha仅合成一次。19项集成断言覆盖多owner同local id、真实setter、height、hidden/focus、whole/child、主开关例外和独立alpha。

并发新增`model/PreferenceBindings.java:17`曾出现Java转义错误，主线程现已修正。2026-11-24再次直接运行本任务verify.ps1，全部当前model/ui（排除SkijaPainter）及中立HudExtensions以Java17集成重编通过，三组harness结果2230/112/19。没有造stub或修改该外部文件。

ConfigView已由主线程接TextInput子接口、frame/scope/keyboard/blur等，本文记录实际接线点；本次中立源码重编已验证这些声明可集成，游戏输入运行仍由主线程验收。曾发现其pointerDown忽略控制器返回值，会使同次down又走hit；已通知应`if (textInput.pointerDown(...)) return true`，控制器也增加同次按下幂等保护并以事件测试防回归。`SettingsPage.recording()`返回实际capture状态，用于全局快捷键闸。

2026-11-24最终本地检查：完整当前中立源码重编+三组harness通过2230/112/19；`-Raster`复用宿主Skija 0.143.17生成5张fixture成功；现有CoreTest通过258。新增TextInputHarness包含真实ConfigView的viewport转换、pointerDown→hit幂等、字符写宿主、Ctrl+C和Esc恢复/释放焦点，未启动游戏。没有把OS输入法组合、复杂脚本/双向排版或视觉像素对照写成已测通过。

## 冻结前纠正：原内置常用页，无 setting pin

2026-11-24已按原源码移除早期误加的设置pin行为。`SettingsPage(true)`仅取`ctx.host().favoriteCategories()`，无cap/default返回List.of时空页，绝不从普通categories+旧favorite偏好推导内容。多分类时沿用原ConfigScreen chips；一分类时不凭空补chip。普通Settings不混入内置项。Options菜单已移除设置favorite/pin；mod收藏属于其它页面的独立语义。

| 内容证据 | 行/结论 | SHA-256前16位 |
| --- | --- | --- |
| 原仓 ui/screens/Favorites.kt | 19–28：favoriteTree→ConfigScreen；没有pin | `319a657787299bb4` |
| 原仓 api/hud/v1/FavoritesTree.java | 46–65：ID=muonium.favorites，宿主填入空树 | `73cb04056382668b` |
| 主仓 MuoniumFavorites.java | 50–95/145–170：五toggle+三slider，显示开关/常用调节 | `7f9946e1833a8229` |

八个稳定ID依原顺序：`muonium.fav.lyrics`、`muonium.fav.info`、`muonium.fav.island`、`muonium.fav.spectrum`、`muonium.fav.acoustics`、`muonium.fav.lyric.font`、`muonium.fav.island.scale`、`muonium.fav.spectrum.opacity`。category=`HUD 叠加层`；两组`显示开关`、`常用调节`；宿主真实实现由主线程持有，本写集只消费Option，不复制音乐配置。新增harness覆盖8项结构、实时值/setter、搜索、chip路由、没有pin时显示/旧pin不混入、默认空树、普通Settings隔离。

冻结时验证口径：`verify.ps1 -Raster`重新编译当前中立代码并通过控件2230/文本112/EntryRows19，5张离屏fixture成功。控件1963→2230来自内置Favorites新增场景及绘制检查，删除旧pin断言，不是放宽原行为断言。视觉/动画/游戏仍交主线程验收；本写集不自行提交、全量Gradle或启动游戏。

冻结确认（2026-11-24）：最后一次直接执行 settings/verify.ps1 -Raster 成功：控件2230、文本112、EntryRows19；随后以当前源码重编CoreTest并通过258项（数量随主线程统一回归增补变化），5张Skija fixture成功，22个本写集文件空白检查通过。停止新增功能和写入，主线程统一最终构建/暂存/提交；本任务未启动游戏。
