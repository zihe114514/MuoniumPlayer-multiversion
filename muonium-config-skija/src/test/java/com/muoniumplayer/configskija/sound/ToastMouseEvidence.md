# 原版 Screen 的 toast 鼠标接入证据

核对日期：2026-11-24。本次仅新增七个版本 ConfigToastMouseMixin、共享 ToastInputBridge、本目录行为测试和证据；未改主入口、mixins JSON、Gradle 或原仓，未运行游戏/Gradle/提交。详细逐组合依赖/哈希/注解结果见同目录 `toast-mouse-evidence.json`；完整 javap、AP日志和离线重映射 jar 只写新工程 `build/toast-mouse/`。

## 实现与宿主挂载

- 七树源文件均为 `src/versions/<主仓lane>/java/com/muoniumplayer/configskija/mixin/ConfigToastMouseMixin.java`，类名相同，主线程按车道挂载并在当前JSON的client列表加入 `ConfigToastMouseMixin`。
- `host/notifications/ToastInputBridge.java` 完全中立，只依赖当前NotificationIntegration，没有MC或loader符号。生产调用来自版本Mixin，直接在原MouseHandler回调里同步消费，不排队到下一帧才cancel。
- 两个注入均 `@At("HEAD")`、`require=1`；按钮注入 `cancellable=true`，bridge返回true后调用 `CallbackInfo.cancel()`。未写 `require=0`，未写 `remap=false`，也未在注入目标中硬编码生产混淆名。
- HEAD位置在原版activeButton/触屏clickDepth/平台fake-right-click/loader MouseButtonPre和具体Screen mouseClicked/released下发之前。toast消费的press不进入下层Screen，也不留下原版activeButton导致随后拖拽。move只观察真实坐标，不取消原版移动。
- 仅当前游戏窗口处理；eventWindow=0或外窗口不修改当前toast输入。没有Screen、SkijaConfigScreen自身、失焦和验收disabled开关不拦截。自身Screen继续走主线程已接的输入，避免双触发。
- 真实pixelRatio来自当前window framebuffer宽/原生窗口坐标宽 `getWidth()/getScreenWidth()`，原生窗口宽为0则不触发；鼠标位置经 `NotificationIntegration.pointerMoveWindow` 统一转换。按钮在消费前读取MouseHandler公开 `xpos()/ypos()`，避免点击时用过期hover。
- SDL原始左键=1；GLFW原始左键=0。两个回调接到的action都已是press=1、release=0；SDL原始event type 1025/1026在SDLEventHandler中先转换，不能把1026或GLFW_REPEAT=2当release传给桥。SDL middle=2/right=3不误映射成左键。
- toast动作在press执行；已消费press保留配对release消费权，既使动作已退掉该toast或切到另一个普通Screen也不会把release落到下层。无Screen/自身Screen仍遵循不拦截规则。foreign-window release不偷走当前窗口capture；显式cancel、收到失焦或无Screen事件清除capture/hover。主线程已有Frame.focused与removed/cancel继续负责无鼠标事件时的全局focus/input取消。
- 原GLFW物理按钮回调还未经过MouseHandler的mac fake-right-click/触屏合成；桥按原通知全局MouseInput的物理左键语义处理，不新增原版合成点击业务。

## 十四组合声明与线程

| 主仓lane | Fabric + 另一loader各自核实 | button目标 | move目标 | 屏幕/窗口 |
| --- | --- | --- | --- | --- |
| 1.20.1 recode | Fabric 0.19.3/API 0.92.11+1.20.1；Forge 1.20.1-47.4.0 | `onPress(JIII)V` | `onMove(JDD)V` | Minecraft.screen / Window.getWindow |
| 1.21.1 | Fabric 0.19.3/API 0.116.15+1.21.1；NeoForge 21.1.249 | 同上 | 同上 | Minecraft.screen / Window.getWindow |
| 1.21.10 | Fabric 0.19.3/API 0.138.4+1.21.10；NeoForge 21.10.64 | `onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V` | `onMove(JDD)V` | Minecraft.screen / Window.handle |
| 1.21.11 recode | Fabric 0.19.3/API 0.141.6+1.21.11；NeoForge 21.11.45 | 同上 | 同上 | Minecraft.screen / Window.handle |
| 26.1.2 | Fabric 0.19.3/API 0.155.3+26.1.2；NeoForge 26.1.2.126 | 同上 | 同上 | Minecraft.screen / Window.handle |
| 26.2 | Fabric 0.19.3/API 0.158.0+26.2；NeoForge 26.2.0.72 | 同上 | 同上 | Minecraft.gui.screen() / Window.handle |
| 26.3 | Fabric 0.19.5/API 0.161.0+26.3；NeoForge 26.3.0.16-beta | 同上 | `onMove(JDDDD)V` | Minecraft.gui.screen() / Window.handle，SDL |

以上读取每个 `build/host/<lane>/<loader>/compile-classpath.txt` 的实际Minecraft JAR后分别 javap `MouseHandler/Minecraft/Window/MouseButtonInfo`；26.2/26.3额外核实Gui.screen，所有读取方法/字段为public。回调线程证据：GLFW各组合的MouseHandler.setup回调lambda调用Minecraft.execute后进入onMove/onPress/onButton；26.3两侧的SDLEventHandler.handleMouseMotionEvent/handleMouseButtonEvent同样调用Minecraft.execute，其lambda再调public MouseHandler回调。桥检查mc.isSameThread，不用延迟Runnable假装同步cancel成功。

26.3 SDL handler真实字节码：event.type与1025比较，生成1或0；保留SDL_MouseButtonEvent.button字节为MouseButtonInfo.button；enqueue到Minecraft.execute。Minecraft.MouseHandler用button1作为isLeftPressed条件。两侧均独立反汇编，完整证据 `build/toast-mouse/26.3/<loader>/sdl-bytecode.txt`。

## 旧生产包映射与主线程要求

1. **旧Fabric四树：正常保留named源码默认remap=true，发布必须走named→intermediary。** 已从每个实际Loom `source_mappings/<MC jar hash>.tiny` 读取 named/official/intermediary 三命名空间；四树MouseHandler均映射到 `net/minecraft/class_312`。onMove映射 `method_1600(JDD)V`，旧两树onPress映射 `method_1601(JIII)V`，1.21.10/11的onButton映射 `method_1601(JLnet/minecraft/class_11910;I)V`。不能把named字符串原样放进生产jar。
2. **两条已用实际工具验证的Fabric路线，主线程选择一致的一条：**
   - AP/refmap路线：本次真实运行解析的Mixin AP + fabric-mixin-compile-extensions 0.6.0，使用 `-AinMapFileNamedIntermediary=<该树实际tiny>`、`-AdefaultObfuscationEnv=named:intermediary`、`-AoutRefMapFile=.../muoniumconfigskija.refmap.json`；四树均生成上述method_1600/1601映射。主线程须把生成refmap打入jar、在mixins JSON顶层引用同名refmap，并让jar全部MC类引用通过Loom正常remap。AP生成的named开发class本身不是生产包。
   - 静态Mixin remap路线：本次实际调用Loom解析版本TinyRemapper 0.14.0的 `--mixin` 扩展，四树实际tiny从named→intermediary生成新jar，随后 `javap -p -v` 确认目标class literal与@Inject.method及方法体MC调用都映射成正确intermediary。证明不依赖AP的可用实现，但不证明当前Gradle任务已启用相同扩展。
3. **当前host.init.gradle的具体注意点（只读快照）：** 编译 `:24` 使用-proc:none；旧Fabric现有 `remapSkijaConfigJar` 在 `:87-99` 复用Loom RemapJarTask且设置useMixinAP=false。Loom 1.17.20源码 `RemapJarTask.java:140-146` 由该task选项设置worker标记/manifest；但真正安装 `new MixinExtension()` 的判断来自 `TinyRemapperService.java:95-113,194-195` 的Options.getUselegacyMixinAP()，创建时读的是全局extension.getMixin().getUseLegacyMixinAp()。不能只看task上的false就声称字符串已改；主线程需要确认服务Options同样选择非legacy AP（或采用AP/refmap路线），最终jar反汇编要看到本节intermediary目标。本侧没改全局Gradle参数。
4. **1.20.1 Forge：** MDG legacyforge的实际 `forge/build/moddev/artifacts/namedToIntermediate.tsrg` 给出onPress→m_91530_、onMove→m_91561_。真实运行依赖的Mixin0.8.5 AP，加 `-AmappingTypes=tsrg`、`-AreobfTsrgFile=<该文件>`、`-AdefaultObfuscationEnv=searge`，生成的refmap分别为 `Lnet/minecraft/client/MouseHandler;m_91530_(JIII)V` 与 `...;m_91561_(JDD)V`。主线程需让生成refmap进入jar并由JSON引用，随后通过已有reobfSkijaConfigJar重映射所有MC调用。当前-proc:none编译不会自动生成此refmap；仅有reobf任务存在不等于新@Inject字符串已经处理。未检查主线程最终生产包，不能保证它已携带refmap。
5. **1.21.x NeoForge：** 三个实际patched开发JAR核实目标Mojmap声明，并额外反汇编各自发布universal.jar的ClientHooks，真实运行期MC引用仍是Minecraft.getInstance/getWindow、MouseHandler.xpos/ypos，而非Fabric intermediary或Forge1.20的SRG，见各侧 `universal-clienthooks.txt`。不把Forge1.20 refmap搬到NeoForge。目标回调仍采用本树已核实Mojmap；最终loader织入仍未运行。
6. **26.x两loader：** 当前官方未混淆目标名与上述声明相同，不添加旧树intermediary/SRG映射。26.3的SDL、26.2的Gui.screen与26.1.2的Minecraft.screen差异已分别适配。保留默认Mixin remap标志不会把它伪装成已执行了发布重映射。

所有AP/refmap结果仅是构建证据，不直接在此复制到src/main/resources来绕过主线程打包；生成路径位于 `build/toast-mouse/<lane>/<loader>/ap/`。JSON/Gradle挂载和最终验收包由主线程完成；不能把“14编译通过”写成“14生产注入/实测通过”。

## 自动验证与未完成范围

- 十四组合新Mixin与ToastInputBridge直接javac通过；对应compile/javac.args、result.txt和mixin-javap.txt记录命令/结果。编译后的注解逐一检查了完整descriptor、require=1、cancellable=true与默认remap；没有require0或remap=false降级。
- 旧Fabric四组真实AP及四个实际静态重映射输出均通过；Forge实际AP/SRG输出通过。生成的refmap、TinyRemapper输出jar与javap可复查；未运行Gradle。
- `ToastInputBridgeBehaviorTest`：49项断言通过，使用真实NotificationIntegration + ToastInputBridge，验证SDL/GLFW编号、raw action已规范化、只消费左press、重复press/配对release、action与body退休差异、普通Screen交接release、focus/cancel、无Screen/自身/disabled绕过、当前窗口和pixelRatio、拒绝跨线程延迟取消。没有MC stub或native renderer。
- 原SoundBehaviorTest/NotificationsBehaviorTest/NotificationIntegrationTest与资源一致性随后回归，具体结果在 `build/toast-mouse/behavior/*Test.txt`。
- 未执行真实Minecraft加载/变换、点击其他Screen、render/输入顺序游戏实测；mixins JSON尚未由本侧挂载；主线程最终生产artifact映射、资源及loader安装均待主线程验证。对未命中的Mixin会require1报错，不吞掉适配失败。

```powershell
& D:/JDK17/bin/javac.exe "@E:/MUSIC/muonium-config-skija/build/toast-mouse/behavior/javac.args"
& D:/JDK17/bin/java.exe -ea -cp E:/MUSIC/muonium-config-skija/build/toast-mouse/behavior com.muoniumplayer.configskija.sound.ToastInputBridgeBehaviorTest
# 完整十四组合与AP命令见各自build/toast-mouse目录内javac.args；本任务未运行Gradle。
```
