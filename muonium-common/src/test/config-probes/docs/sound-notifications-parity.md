# 声音与通知中心移植证据

核对日期：2026-11-24（主要依赖与原实现取证于 11-23，交付复核于 11-24）。实现目录：`E:/MUSIC/muonium-config-skija`。原附属仓 `E:/oneconfig lite/MuoniumConfig-src` 只读；环境声音频另见 `E:/oneconfig lite/OneConfig/sounds`。本次只写分配的声音、通知、资源、测试和本文；`build/sound-notifications/` 仅保存局部验证证据。未修改主项目或原附属仓，未运行游戏、Gradle、发布、提交或推送。

## 已实现与交付边界

- 中立 `SoundSession`：原同名偏好、九类事件、主题音色、六音高轮转、原音量倍率、环境声洗牌、原生声音 tick 淡出、500ms 完播检查、移屏与重建的静默 acquire/release。
- 七个同名 `McSoundBackend`：只调用各精确组合实际依赖内的 Minecraft `SoundManager`；无 loader 符号、无新增声音线程、无第二份 Skija、Compose 或音频引擎。
- 中立 `Notifications`：历史与活跃 toast 分离、50 条新到旧历史、未读、清空、进度、动作、持久通知、宿主异步事件的同步快照。
- `NotificationsCenter`：真实 `UiPage` 弹层、原 368dp 宽度/420dp 列表上限、上方铃铛锚点、未读点、类型色、相对时间、可滚动正文/进度/动作、清空、外点和 Escape 关闭。
- `NotificationBell`：18dp 铃铛和原 8dp 模糊光晕/6dp 未读点；可用，也可由主壳自行绘制铃铛再打开中心。
- `NotificationToasts` / `ToastTimeline`：340dp 卡片、底右 16dp 边距、12dp 间距、220ms 入/出场、悬停暂停、默认 4000ms、进度完成停留 600ms、动作和正文的不同退休语义。
- 9 个 OGG 与 `images/head.png` 均按字节复制，SHA-256 与源一致。`sounds.json` 仅把资源 namespace 从 `oneconfig:` 改为 `muoniumconfigskija:`。

声音/通知分工代码已完成并冻结。主线程于 2026-11-24 告知已完成持久声音、错误通知、Account 铃铛与游戏全局 toast/input 接线；冻结复核已在当前宿主源码定位到这些调用，详见文末“最终冻结状态”。本文的“通过”仅指下述静态与行为验证；游戏视觉、交互、动画、听感与资源重载仍由主线程统一验收，不能由接线存在推断实测通过。

## 共享接线 API

以下类型均位于 `com.muoniumplayer.configskija.host.sound`；每个车道仅挂载自己 `src/versions/<主仓lane>/java/` 下的 `McSoundBackend`。必须保留同一个 session 跨主菜单、HUD 和淡出中重开；每次开屏创建新 session 会重置 slider 顺序与洗牌袋，且丢失旧淡出句柄。

```java
SoundSession sounds = new SoundSession(preferences, new McSoundBackend());
sounds.open(activeThemeId);       // init / 取消关闭：OPEN + acquire；重复调用幂等
sounds.tick();                    // 客户端 tick；检测环境声设置变化并检查换曲
sounds.play("click");              // 原事件名也可用 UiSoundEvent 枚举
sounds.play("slider_tick");
sounds.themeChanged(activeThemeId); // 激活主题：同声系 CLICK，跨声系 OPEN 并切环境声
sounds.close();                   // 主动 beginClose：CLOSE + release，一次即可
sounds.releaseAmbience();         // removed / 移屏：只 release，不合成额外 CLOSE
sounds.acquireAmbience(activeThemeId); // 场景恢复的 acquire-only 路径，不合成 OPEN
sounds.stopImmediately();         // 客户端退出等真正销毁；常规关闭应保留淡出
```

所有上述方法与 native voice 操作都在 MC 客户端线程调用，版本桥用实际 `Minecraft.isSameThread()` 强制检查。设置数据来自传入的 `UiPreferences`。`play("theme_changed")` 是主题激活事件的同义入口；主入口若已经调用 `themeChanged`，控件不能再发一次同义事件。`play(String)` 对未知事件返回 false；`navigate` 映射 CLICK，`slider` 映射 SLIDER_TICK；不对缺失资源或错误线程吞异常。

偏好默认值：`enableUISounds`、`enableUIMenuSounds`、`enableUIClickSounds`、`enableUISliderSounds`、`enableHudEditorSounds`、`enableUIAmbience`、`enableUIMusicDucking` 均 true；`uiSoundVolume` / `uiAmbienceVolume` 均 1，读取后限制到 0..1。环境声独立于 `enableUISounds`，不受交互音总开关影响。`tick()` 检测环境声启用、音量与 ducking 配置的变化；交互音在每次播放时读取。

主助手需要保留的触发顺序：

1. 原 `internal/ui/components/Common.kt:36-45` 先播放 CLICK，再执行 onClick 回调。开关控件先发声音再写值，否则关闭音效的那一下与源行为不同。
2. 原 `components/settings/SliderControl.kt:62-70,82-84` 对值变化以 70ms 节流发 SLIDER_TICK，然后写值；这层节流由控件保留，SoundSession 不重复全局节流。Range、继承 slider、有序列表也有自己的原触发点。
3. 原 `ui/hud/screens/HudDesignStudio.kt:1554,1578,1614,1675,1840,1850,1882,1889` 分别覆盖选择、resize start、drag start 与结束；结束事件不要替换成普通 CLICK。源中部分开始事件本来静音，仍保留其事件类型。
4. 原 `26.3/.../compose/impls/OneConfigUIScreen.kt:306-308,322-323,415-416,437` 是 beginClose、取消关闭、init、removed 的时序。`removed()` 原本只 release，不应无条件再发 CLOSE。其余原车道的声音服务全文也已分别读取。

通知类型位于 `com.muoniumplayer.configskija.model.Notifications` 与 `com.muoniumplayer.configskija.ui.notifications`：

```java
Notifications notifications = Notifications.shared(); // 或同一个显式实例
notifications.error(actionTitle, errorMessage);
notifications.success("Preset applied", presetName);
notifications.progress("Downloading", fileName, () -> progressFraction);
notifications.builder(title, message)
    .persistent()
    .action(new Notifications.Action("Retry", true, retryOnClientThread))
    .send();

context.overlay(new NotificationsCenter(notifications, bellBounds, clearAllLabel));
// 或保留一个 helper，由主壳 Account 渲染时调用：
NotificationBell bell = new NotificationBell(notifications, clearAllLabel);
bell.render(context, bellBounds);
```

中心默认英文 footer；中文传“清除全部通知”。原中心 title、empty-state 与 relative-time 本来是英文，未擅自翻译。`leave(PageContext)` 是关闭后 markAllRead 的必调路径，`dismissOverlay()`、替换 overlay、关闭壳时均需调用；当前 PageContext 合同已有该生命周期。打开中心不 markAllRead。通知模型不直接引用 MC、Skija 或 Compose；宿主后台线程可 push，进度 supplier 和动作由 UI 线程调用。动作若还要修改 MC，必须符合宿主线程约束。

可选 live toast 接线：保留 `new NotificationToasts(notifications)`，在现有宿主帧绘制并路由它注册的 hit。`render(ctx, viewport, physicalScale, screenOpen)` 支持主 UI 关闭后继续计时/绘制；无打开的 Minecraft Screen 时禁止 hover/click。原 `NotificationsRenderer.kt:40,75-91` 的全局 viewport 是 `scale = guiScale * .5`、`viewWidth = screenWidth * guiScale / scale`（高同理）。主助手需要提供对应 Painter 变换、同一坐标系的鼠标和 hit，不能只在中心弹层打开时 tick toast。默认 `UiPage.render(ctx)` 可用于壳内验收，不能据此声称全局缩放已经接线。

精确排版扩展：现有 Painter 可额外实现 `NotificationTypography.notificationText(..., int weight, ...)` / `notificationMeasure(..., int weight)`，中心标题、动作和 toast 标题使用 500，中心通知标题使用 600；未实现时映射已有 boolean bold，精确字重尚未得到保证。原 Popup 通过 `RetainedVisibility.kt:45-55,65-75` 继承 shell 进度、以铃铛为 pivot；本弹层没有凭空增加独立进场动画，主壳应提供一致的 shell alpha/scale 与输入变换，具体视觉由人工验收。

## 原声音对照与时序证据

原共享路径前缀为 `muoniumconfig-common/src/main/kotlin/org/polyfrost/oneconfig/`；原各树声音路径为 `<原lane>/src/main/kotlin/org/polyfrost/oneconfig/internal/ui/sound/McUiSoundService.kt`。七份各 244 行均完整读取并逐行对照；差异仅旧两树环境声为 MASTER、1.21.10 起为 UI，以及资源标识符类型/构造入口。本文的 API 有效性另以每个 loader 的 JAR 确认，不以原代码单独代证。

| 事件 | Modern | Minecraft |
| --- | --- | --- |
| OPEN / CLOSE | `ui.open` / `ui.close`，pitch 1，gain 1 | 同 Modern |
| CLICK | `ui.click`，pitch 1，gain 1 | `UI_BUTTON_CLICK`，pitch 1，gain .5 |
| SLIDER_TICK | `ui.slider`，pitch 1，gain 1 | `NOTE_BLOCK_BASS`，gain .3，六固定音高轮转 |
| HUD_SELECT | `ui.click`，pitch 1 | `UI_BUTTON_CLICK`，pitch 1，gain .3 |
| HUD_DRAG_START | `ui.click`，pitch .9 | 静音 |
| HUD_DRAG_END | `ui.click`，pitch 1.1 | `UI_BUTTON_CLICK`，pitch 1，gain .3 |
| HUD_RESIZE_START | 静音 | 静音 |
| HUD_RESIZE_END | `ui.click`，pitch 1 | `UI_BUTTON_CLICK`，pitch 1，gain .3 |

以上 gain 均乘 `uiSoundVolume`。证据为各树 McUiSoundService.kt:44-86。六音高依次为 `1.334840, 1.414214, 1.498307, 1.587401, 1.681793, 1.781797`；跨屏/主题保留 slider 计数，静音/禁用事件不推进。

环境声证据：

- `UiSounds.kt:44-78`：acquire 幂等、release 开始淡出、refresh 只在 acquire 状态生效、跨声系先播放 OPEN 再停止/重启 ambience。
- 各树 `McUiSoundService.kt:88-121`：同声系且活动的 sound 立即更新目标音量并取消淡出；否则 stop 旧实例后新建。Modern gain `.5 * uiAmbienceVolume`；Minecraft `.08 * uiAmbienceVolume`。
- `:123-163`：Modern 仅 `ui.pad_loop` 原生循环；Minecraft trees/moog_1/moog_2/lullaby 四曲袋内不重复，袋耗尽后重洗，跨袋第一首与上一首不重复。
- `:165-190`：旧实现守护线程 sleep(500) 后排队到 `mc.execute` 检查 current/isStopped/isFadingOut/isActive；新实现由主入口 `tick()` 以相同 500ms 检查，避免新增线程。已关闭或淡出中的 session 不续播。
- `:192-241`：native tickable sound，relative、NONE 衰减、delay 0、pitch 1，每次 tick 减 `targetVolume / 80f`，直至 <=0 调 protected `stop()`。`FadeEnvelope` 保留 float 算法；浮点舍入时可在第 81 个 tick 才归零。约四秒是 20TPS 下的名义长度，不保证暂停/卡顿下四秒墙钟时间。
- 14 份 `javap/*-calls.txt` 都核对了 `SoundManager.tick(boolean) -> SoundEngine.tick(boolean)`、声音引擎对 `TickableSoundInstance.tick()` 的真实调用，关闭后 fade 无需让已关 UI 继续 tick。不同暂停路径不能据静态测试代替听感验收。

**Ducking 实际未实现**：`OneConfigConfig.java:562,698,745-748` 只有默认开关、显示依赖和 refresh 回调；共享 UiSounds 不读取该开关决定音乐音量；七树 `McUiSoundService.kt:109` 的 `setDucking` 都标注 `UNUSED_PARAMETER`，体内仅 beginFadeOut。完整源码搜索未发现音乐 gain 修改。新 session 读取同名键以触发同样的 refresh，但绝不伪造音乐压低功能，也不修改主播放器或 MC MUSIC 音量。

## 通知中心、toast 的原实现证据

| 事实 | 原文件与行号 |
| --- | --- |
| 368dp；横 padding 14、纵 padding 16、16dp child gap、18sp/28 行高标题 | `internal/ui/components/NotificationsCenter.kt:38,51-66` |
| 无日期/类型分组；直接 history.forEach；列表最高 420dp，row 间 16+1+16 分割 | `NotificationsCenter.kt:75-97` |
| 32dp icon 容器、内 4dp padding、24dp icon、13dp gap；标题14/20、正文12/21、时间10、未读点6 | `NotificationsCenter.kt:110-147` |
| 进度6dp；actions 横 gap16、padding12/6、12sp/18；动作执行后 remove | `NotificationsCenter.kt:150-207` |
| Footer 18dp trash、12dp icon gap，清空只 clearHistory | `NotificationsCenter.kt:218-233` |
| `<5s Just now`，随后 s/m/h/d，未来时间 clamp 到0 | `NotificationsCenter.kt:245-253` |
| 铃铛18dp；BottomStart，12dp 上方；关闭/外点才 markAllRead | `internal/ui/components/Sidebar.kt:211-245` |
| Bell 未读光晕8dp/blur7，实心6dp、偏移(2,-2) | `internal/ui/components/Icon.kt:362-395` |
| 活跃旧到新；历史新到旧、MAX_HISTORY50；mark/read/remove/clear/dismiss 分离 | `api/notifications/v1/NotificationsManager.kt:37-126` |
| 默认4000ms；persistent 是duration<=0且无progress；progress supplier clamp/异常fallback | `api/notifications/v1/Notification.kt:81-90,137-145` |
| Toast340dp；padding14/16，icon24/gap12，底右16，卡间12 | `api/notifications/v1/NotificationToasts.kt:58-70,92-105` |
| 220ms cubic 进出、hover暂停、进度600ms linger、timer条件、像素quantize | `NotificationToasts.kt:77-79,109-164,190-211,216-220` |
| Toast body只请求dismiss而保留历史；action即使抛异常仍remove | `api/notifications/v1/NotificationsRenderer.kt:119-137` |
| Toast背景/字色/按钮色随当前主题；Modern圆角12/4/3，Minecraft0；精确字体poppins-medium/minecraft-bold | `internal/ui/themes/Provider.kt:191-209` |
| Windows wheel150dp（mac50），精确滚轮Windows150/其余10；原 critically damped spring=.06s | `26.3/.../compose/ComposeScreen.kt:552-570,785-791` 与 `components/WheelScroll.kt:154-207` |

原 api/notifications 下全部文件均已完整读取，包括旧通用 SVG 栅格器 `SvgGeometry.kt` / `SvgRenderer.kt` / `SvgMask.kt`。通知中心复用主助手现有 Painter 的 SVG 图标合同，不复制 SVG 引擎或 Compose；自定义 toast 图标可用已有资源路径或 `Builder.image(byte[], width, height)` 的中立编码图像。旧 OneConfig 的 PolyBitmap/Skia Image 二进制 API 不在本新工程内复刻；如融合时需要承接旧 API，由主助手做公开 API 适配，不能把中立模型冒充原 ABI 兼容。

## 每个精确组合的依赖核实与局部编译

事实来自主仓各树 `gradle.properties` / `settings.gradle` / loader `build.gradle`，以及 `build/host/<lane>/<loader>/compile-classpath.txt` 实际解析路径。旧 Fabric 全部 officialMojangMappings；26.x Fabric 为官方未混淆/non-remap 路线；Forge 是 MDG legacyforge，未套用技能的 ForgeGradle 示例。仅使用已核实 public/protected 成员，无新增 AW/AT/Mixin。Fabric 与另一侧分开执行 javap，不能用一侧代证。

| MC/主仓 lane | loader 开发版本（Fabric 附 API） | 标识符 | 环境声道 | play 返回 | javap / javac release |
| --- | --- | --- | --- | --- | --- |
| 1.20.1 recode | Fabric 0.19.3 / API 0.92.11+1.20.1 | `ResourceLocation(String,String)` | MASTER | void | 通过 / 通过 17 |
| 1.20.1 recode | Forge 1.20.1-47.4.0 | `ResourceLocation(String,String)` | MASTER | void | 通过 / 通过 17 |
| 1.21.1 | Fabric 0.19.3 / API 0.116.15+1.21.1 | `ResourceLocation.fromNamespaceAndPath` | MASTER | void | 通过 / 通过 21 |
| 1.21.1 | NeoForge 21.1.249 | `ResourceLocation.fromNamespaceAndPath` | MASTER | void | 通过 / 通过 21 |
| 1.21.10 | Fabric 0.19.3 / API 0.138.4+1.21.10 | `ResourceLocation.fromNamespaceAndPath` | UI | SoundEngine.PlayResult | 通过 / 通过 21 |
| 1.21.10 | NeoForge 21.10.64 | `ResourceLocation.fromNamespaceAndPath` | UI | SoundEngine.PlayResult | 通过 / 通过 21 |
| 1.21.11 recode | Fabric 0.19.3 / API 0.141.6+1.21.11 | `Identifier.fromNamespaceAndPath` | UI | SoundEngine.PlayResult | 通过 / 通过 21 |
| 1.21.11 recode | NeoForge 21.11.45 | `Identifier.fromNamespaceAndPath` | UI | SoundEngine.PlayResult | 通过 / 通过 21 |
| 26.1.2 | Fabric 0.19.3 / API 0.155.3+26.1.2 | `Identifier.fromNamespaceAndPath` | UI | SoundEngine.PlayResult | 通过 / 通过 25 |
| 26.1.2 | NeoForge 26.1.2.126 | `Identifier.fromNamespaceAndPath` | UI | SoundEngine.PlayResult | 通过 / 通过 25 |
| 26.2 | Fabric 0.19.3 / API 0.158.0+26.2 | `Identifier.fromNamespaceAndPath` | UI | SoundEngine.PlayResult | 通过 / 通过 25 |
| 26.2 | NeoForge 26.2.0.72 | `Identifier.fromNamespaceAndPath` | UI | SoundEngine.PlayResult | 通过 / 通过 25 |
| 26.3 | Fabric 0.19.5 / API 0.161.0+26.3 | `Identifier.fromNamespaceAndPath` | UI | SoundEngine.PlayResult | 通过 / 通过 25 |
| 26.3 | NeoForge 26.3.0.16-beta | `Identifier.fromNamespaceAndPath` | UI | SoundEngine.PlayResult | 通过 / 通过 25 |

已逐组合核实的调用声明：

```text
Minecraft.getInstance(): Minecraft                                  public static
Minecraft.getSoundManager(): SoundManager                           public instance
BlockableEventLoop.isSameThread(): boolean                          public inherited
SimpleSoundInstance.forUI(SoundEvent, float pitch, float volume)      public static
SoundManager.play(SoundInstance)                                    public; 返回类型见上表
SoundManager.stop(SoundInstance): void                              public
SoundManager.isActive(SoundInstance): boolean                       public
SoundEvents.UI_BUTTON_CLICK, NOTE_BLOCK_BASS                          public static Holder.Reference<SoundEvent>
Holder<T>.value(): T                                                public
SoundEvent.createVariableRangeEvent(ResourceLocation|Identifier)     public static
AbstractTickableSoundInstance(SoundEvent, SoundSource, RandomSource)  protected constructor
AbstractTickableSoundInstance.isStopped(): boolean                   public
AbstractTickableSoundInstance.stop(): void                           protected final
AbstractSoundInstance.volume/pitch: float                            protected
AbstractSoundInstance.looping/relative: boolean                      protected
AbstractSoundInstance.delay: int; attenuation: SoundInstance.Attenuation protected
SoundInstance.Attenuation.NONE; RandomSource.create()                实际声明已核实
```

每侧真实 MC JAR 与 SHA-256：

- `1.20.1 recode/fabric`：`E:/MUSIC/1.20.1 recode/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-825aaf77bd/1.20.1-loom.mappings.1_20_1.layered+hash.2198-v2/minecraft-merged-825aaf77bd-1.20.1-loom.mappings.1_20_1.layered+hash.2198-v2.jar`；SHA-256 `95cce53ddecf560ee52be206073ef0aef1253b2598eb706636e4b0ac9dd982bb`。
- `1.20.1 recode/forge`：`E:/MUSIC/1.20.1 recode/forge/build/moddev/artifacts/forge-1.20.1-47.4.0.jar`；SHA-256 `6f96ed7aa8080f37121020f7d2f5a61b0f33cd91aa64ae026e2bba25bf8a65e8`。
- `1.21.1/fabric`：`E:/MUSIC/1.21.1/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-c7fff42769/1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2/minecraft-merged-c7fff42769-1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2.jar`；SHA-256 `1697cd63d09b1acd517add30696cfdf4cf7d340bcb997a065f30ec1f11a49b9e`。
- `1.21.1/neoforge`：`E:/MUSIC/1.21.1/neoforge/build/moddev/artifacts/neoforge-21.1.249.jar`；SHA-256 `ebdcc6b7f680bae388b06e037f183f339049b2cffb75adf98d3e87f2c1b48c4d`。
- `1.21.10/fabric`：`E:/MUSIC/1.21.10/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-5243ed6022/1.21.10-loom.mappings.1_21_10.layered+hash.2198-v2/minecraft-merged-5243ed6022-1.21.10-loom.mappings.1_21_10.layered+hash.2198-v2.jar`；SHA-256 `333cd60d257e7e43b829975c85ce093d53f60036f39ecb5055740236b5861ae0`。
- `1.21.10/neoforge`：`E:/MUSIC/1.21.10/neoforge/build/moddev/artifacts/neoforge-21.10.64.jar`；SHA-256 `21c46c14d60855e73d942af9f0c7f339d8e6c2d83f21d735590455a658d21a23`。
- `1.21.11 recode/fabric`：`E:/MUSIC/1.21.11 recode/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-480d24ca11/1.21.11-loom.mappings.1_21_11.layered+hash.2198-v2/minecraft-merged-480d24ca11-1.21.11-loom.mappings.1_21_11.layered+hash.2198-v2.jar`；SHA-256 `e3ed55fd65fd15ce6429eadb85ca85c55364b7ff5f7a53248b73f0635c62f21e`。
- `1.21.11 recode/neoforge`：`E:/MUSIC/1.21.11 recode/neoforge/build/moddev/artifacts/neoforge-21.11.45.jar`；SHA-256 `59db735aae0c2a1192fe8200dd53d3e59bedffe182134c9fe129f000a266ae69`。
- `26.1.2/fabric`：`E:/MUSIC/26.1.2/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.1.2/minecraft-merged-043a8b3edf-26.1.2.jar`；SHA-256 `1182bfd7b4209f9384cdc24c5c3de699b11b81d93604e3df13d09bcb33627c72`。
- `26.1.2/neoforge`：`E:/MUSIC/26.1.2/neoforge/build/moddev/artifacts/minecraft-patched-26.1.2.126.jar`；SHA-256 `f7147fdb5380a8158b42cdb9c35213d0c9a39bb0a0523adacc2e136223effc64`。
- `26.2/fabric`：`E:/MUSIC/26.2/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.2/minecraft-merged-043a8b3edf-26.2.jar`；SHA-256 `9989776ce617c73ceb1293995c0cc722ccd8b77e550b043ea36978f72c534b74`。
- `26.2/neoforge`：`E:/MUSIC/26.2/neoforge/build/moddev/artifacts/minecraft-patched-26.2.0.72.jar`；SHA-256 `047104eaba2c3788a3d2142d79567ae81b311e2d770584d6f85b4332bffac64b`。
- `26.3/fabric`：`E:/MUSIC/26.3/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-7e9a32a5b8/26.3/minecraft-merged-7e9a32a5b8-26.3.jar`；SHA-256 `edf496ead8954d94878b34514a0e9b76bd3fcac0f92f53021218fb2d88dec9b9`。
- `26.3/neoforge`：`E:/MUSIC/26.3/neoforge/build/moddev/artifacts/minecraft-patched-26.3.0.16-beta.jar`；SHA-256 `5335a0f4af6e792a9f8043e25186e02b178376454023697707d275ab448f7fda`。

完整反汇编保存为 `build/sound-notifications/javap/<lane>-<loader>.txt`，调用链另为 `*-calls.txt`；`dependencies.json` 记录 JAR 大小与哈希。每组合的 `compile/<lane>/<loader>/javac.args` 与 `result.txt` 记录本次实际命令与结果。仅编译本分工的声音会话、音桥和 UiPreferences，不据此声称整包/元数据最低版本/运行注入已通过。

## 按字节复制的资源清单

目标前缀 `src/main/resources/assets/muoniumconfigskija/`。交互音与头像来源前缀 A = `E:/oneconfig lite/MuoniumConfig-src/muoniumconfig-common/src/main/resources/assets/oneconfig/`；环境声来源前缀 B = `E:/oneconfig lite/OneConfig/`。完整搜索源仓包含隐藏/被忽略文件，并向源仓同级 OneConfig 资产目录扩展，找齐 manifest 需要的环境声。

| 目标相对路径 | 来源 | 字节 | SHA-256 |
| --- | --- | --- | --- |
| `sounds/ui/open.ogg` | A 同路径 | 30372 | `0ea59c5106adba57f2874b66ab373ec375d39ddc96b4c0cc30bee74835718451` |
| `sounds/ui/close.ogg` | A 同路径 | 30879 | `960fc6a173b919e05893fc5d2e71380f6feccdaaf92a517509d92eea570e8b35` |
| `sounds/ui/click.ogg` | A 同路径 | 5203 | `4acb3d7d65bf62fc33209449f53bbc92cc37ed3e51248e88cb6cdb31357844ad` |
| `sounds/ui/slider.ogg` | A 同路径 | 5240 | `a067f59c7dba9159a9a566e619c649d54211d8f5cf50b3b084b56654c7e6fbe3` |
| `sounds/ambient/trees.ogg` | B 同路径 | 3252543 | `ac9e8c4b5d0f281778f8e4026b65ab82d43adcfc0c29aaa8bfe754ba724968c1` |
| `sounds/ambient/moog_1.ogg` | B 同路径 | 2828698 | `adad4ff80d101dceec75ff583b4fc14611faae75dedc8b6d66535557d160d8d1` |
| `sounds/ambient/moog_2.ogg` | B 同路径 | 3011389 | `99fedc25752224de5649f40c8a697142ad582671bdc4236782181f154ea2575e` |
| `sounds/ambient/lullaby.ogg` | B 同路径 | 3341758 | `d75cff9a9506787af9e57d615a428ecc21bf5cbe8d56bb00aae3f447f462dc5a` |
| `sounds/ui/pad_loop.ogg` | B 同路径 | 726131 | `02c60b452675539ec163510850f909840d019a65fe8a18f69b1725d320622c5d` |
| `images/head.png` | A 同路径 | 374 | `3cc7b66ed3bb11ce5cc1f8f58f791db5d0db5c16ea235797e714647b72dc3ed1` |

`sounds.json` 保留原 event 表、stream 标记和分曲 alias，只迁移资源 namespace；它不是字节原样复制文件，不能将此 JSON 的变化混写成音频重新编码。未生成或替换任何音频。头像交给主助手 Account 读取；实际用户名/版本与头像显示接线不在本分工。

## 局部验证与未完成范围

最终局部结果：

- 26 个授权新增文本文件的逐文件 `git diff --no-index --check` 和已有目录 `git diff --check` 通过；图标两套共 12 个必需项齐全。

- Java17 直接编译实际 model/ui 源码（排除 native SkijaPainter），包括真实 ConfigView/PageContext/UiPage/Theme 和本次声音通知源；通过。中途其他协作文件 SearchSession 的泛型错误修复后重新整体局部编译，最终不再使用旧二进制 Key 枚举。
- `SoundBehaviorTest`：1288 项断言，通过。覆盖开关/音量、事件映射、6音高、禁止事件不推进、200轮洗牌与跨袋边界、500ms切曲、80tick float淡出/重开/静默removed/acquire/退出释放。
- `NotificationsBehaviorTest`：4547 项断言，通过。使用真实 UiPage 与 PageContext 动态代理捕获绘制/hit，覆盖异步push、历史/活跃分离、未读、清空、时间边界、正文换行、原中心锚点/宽高、操作回调、滚轮、四主题状态平衡、字重扩展、toast计时/hover/进度/动作失败/no-screen输入。断言数量包括绘制状态/几何守卫，不等价于独立游戏场景数量。
- `SoundResourcesTest`：10 个二进制资源与源逐字节相等，OGG header正确，JSON除了 namespace 与源一致；通过。
- 14 组合声音桥：逐侧 javap 实际声明 + javap 调用链 + 用该组合 compile classpath 直接 javac，全部通过。

本次运行命令（输出仅在新工程 build）：

```powershell
& D:/JDK17/bin/javac.exe "@E:/MUSIC/muonium-config-skija/build/sound-notifications/behavior/all/javac.args"
& D:/JDK17/bin/java.exe -ea -cp E:/MUSIC/muonium-config-skija/build/sound-notifications/behavior/all com.muoniumplayer.configskija.sound.SoundBehaviorTest
& D:/JDK17/bin/java.exe -ea -cp E:/MUSIC/muonium-config-skija/build/sound-notifications/behavior/all com.muoniumplayer.configskija.sound.NotificationsBehaviorTest
& D:/JDK17/bin/java.exe -ea -cp E:/MUSIC/muonium-config-skija/build/sound-notifications/behavior/all com.muoniumplayer.configskija.sound.SoundResourcesTest E:/MUSIC/muonium-config-skija "E:/oneconfig lite/MuoniumConfig-src" "E:/oneconfig lite/OneConfig"
# 每个组合均已运行；示例只展示一个实际参数文件：
& D:/JDK25/bin/javac.exe "@E:/MUSIC/muonium-config-skija/build/sound-notifications/compile/26.3/fabric/javac.args"
# 只读检查实际 JAR 时使用：
& D:/JDK25/bin/javap.exe -classpath $resolvedMinecraftJar -p -s net.minecraft.client.resources.sounds.SimpleSoundInstance net.minecraft.client.resources.sounds.AbstractTickableSoundInstance net.minecraft.client.resources.sounds.AbstractSoundInstance net.minecraft.client.sounds.SoundManager
```

尚未完成/不能由本次结果保证：

1. 主线程已接持久声音、屏幕错误通知、Account 铃铛与全局 toast/input，冻结复核已静态定位调用。完整客户端事件是否在每组合触发、各控件/HUD声音时序、预设完成通知场景、资源最终打包及验收包构建仍以主线程证据为准。本分工没有修改主入口或把静态接线记成游戏实测。
2. 精确 Medium/SemiBold 字重需现有 Painter 实现 NotificationTypography；默认 bool fallback不能证明精确字重一致。字体真实度量、Compose 默认行高/换行断点、弹层 shell pivot/动画、实际 GUI scale/窗口输入需人工对照。
3. 游戏内首次打开/重进/HUD切换/取消关闭、听感、暂停时fade、音量0、设备/资源包重载、环境声连续换曲、实际音乐仍未duck、四主题/高DPI/resize/无资源降级均未启动游戏验证。没有运行全量Gradle或发布构建；14次javac不是14个发布包。
4. 原 ducking 开关虽存在但功能未实现；本次按真实实现保持，不把开关的名字当成已完成音乐压低。旧 OneConfig notification二进制ABI亦未伪造兼容。

## 全局 HUD/Screen 通知 Adapter（2026-11-24）

新增 `host/notifications/NotificationIntegration.java`；复用同一 `Notifications.shared()` 与同一 `NotificationToasts`。没有创建 native renderer、音频线程、MC event/Screen 桥，本分工没有直接修改主入口；当前主线程已完成宿主调用接线。原 `NotificationToasts.render(...)` 仍可调用；新增的 `tick(...)` / `draw(...)` 分别负责单次布局计时输入与纯绘制，防止全局 HUD 和 Screen 重复绘制时推进两次动画。

主线程接线示例（均为静态 API；frame/input/render 同一客户端/UI 线程，只有 notifications 模型接受工作线程 push）：

```java
NotificationIntegration.errors(host::error);
Notifications notices = NotificationIntegration.notifications();
// MC scaled screen units -> framebuffer / 原通知dp，scale = guiScale * .5。
Viewport viewport = Viewport.fromGui(guiWidth, guiHeight, guiScale);
// 先 tick 最新真实 Screen identity；无 Screen 时传 null，不以“是否打开设置”代替它。
NotificationIntegration.tick(new Frame(
    metricsPainter, preferences, viewport, monotonicSeconds, deltaSeconds,
    currentScreenIdentity, windowFocused));
// 全局已有 Skija HUD/Screen 帧最后绘制。Painter 原点为 framebuffer；Adapter自行 translate/scale。
NotificationIntegration.render(painter);
// 窗口坐标先乘 pixelRatio；已经是 framebuffer 坐标则直接 pointerMove。
NotificationIntegration.pointerMoveWindow(currentScreenIdentity, mouseX, mouseY, pixelRatio);
boolean handled = NotificationIntegration.pointerDown(currentScreenIdentity, button);
boolean released = NotificationIntegration.pointerUp(currentScreenIdentity, button);
// 失焦、指针离开、removed/cancel 时清 hover/capture/hit，保留通知及其计时。
NotificationIntegration.cancelInput();
NotificationIntegration.requestDismiss(id); // 动画退出；保留历史
NotificationIntegration.dismiss(id);        // 立即退掉 live；保留历史，匹配原 Notifications.dismiss
```

- `Viewport(x,y,width,height,scale)` 的矩形是 framebuffer 像素；逻辑内容大小为 width/scale × height/scale。输入使用同一逆变换，hit 被 viewport 裁剪，`hitBoxes()` 返回 framebuffer 几何以便人工/无人验收定位。
- `tick(Frame)` 只测量文字，不调用 Painter 绘制；设置完全关闭、HUD-only、窗口零尺寸时也推进通知计时。真实绘制由原宿主已有帧供给；重复 render 不重入计时。主线程必须持续 tick，不能把 Adapter 生命周期绑定到设置 Screen。
- 原 `NotificationsRenderer.kt:96-118` 只在任意 MC Screen 打开且鼠标有效时产生 hover；HUD gameplay 仍展示但不暂停/点击。Adapter额外在失焦/取消后停用输入，真实 pointerMove 或新 Screen tick 才重新绑定有效目标。
- 原 `ToastInput.kt:61-67` 在左键 PRESSED 触发；Adapter同样在 press执行，release只配对消费。动作与body复用原 toast回调实现：action remove历史，body只requestDismiss。
- 真实 Screen identity、viewport 或focus变化时清除旧hit/capture，保留所有时间线和历史。screen旧事件被拒绝；最新真实鼠标坐标按源行为跨Screen保留，新布局重新判断hover。通知外部消失后旧hit只失效，不把同一点击重新定位给重排后的其它通知。
- **源没有 maxVisible/队列条数上限**：`NotificationToasts.kt:93-101` 遍历全部active，`NotificationsManager.kt:37,70-75` 的50仅限制history；源码搜索未发现其它maxVisible配置。Adapter不猜一个3/5/50的toast上限，超出viewport只裁剪，所有active照常计时。新增测试用80个active和50个history验证此区别。
- Adapter用 `Theme.transition` 与 `Theme.accent(preferences,time)` 读取现有同名偏好，原通知正文/标题不自动翻译。UI渲染/输入错误通过主线程提供的errors回调报告；未配置时用JDK系统logger。PageContext中无关配置页面操作明确拒绝，不伪装为可用的宿主页面。

### Locale 与精确文字样式审查

通知中心的 `Notifications`、`You're all caught up.`、相对时间英文后缀本来就是字面量；title/message/action.label是调用方原样字符串，中心和toast不在显示时统一自动翻译。唯一中心固定文案本地化是 `NotificationsCenter.kt:232` 的 `oneconfig.ui.clear_notifications`。该key经 `components/I18n.kt:19-25` -> `Platform.i18n()` -> 原26.3 `I18nPlatformImpl.java:49-56` 的MC `I18n.get` / `Language.has`；来源是当前Minecraft资源语言，不能擅自用系统Locale或创造新的UiPreferences语言键。新中心构造器接受已经按宿主语言解析的clearLabel。

Painter需要的确切字段，建议在主线程的文字样式/字体选择层保留：

| 字段 | 用途/源证据 |
| --- | --- |
| surface/role（CENTER标题、CENTER条目、TOAST标题/正文/动作） | 同为14sp或500weight不能证明同一个字体族 |
| requestedFontKey与resolvedFont identity/revision | Center走当前theme.typography/UiFontOverride；toast走命名字体槽/默认槽，允许缺失而不冒称解析成功 |
| size与numericWeight | Center总标题18/500、条目标题14/600、正文12/400、时间10/400、动作/清空12/500；toast标题14/命名槽、正文12/命名槽、动作12/标题槽 |
| explicitLineHeight或font metrics（ascent/descent/leading） | Center总标题28、条目标题20、正文21、动作18；toast的TextNode高度是descent-ascent，baseline=top-ascent，见 `compose/node/TextNode.kt:20-30`；不能拿21/18固定值冒充真实toast字体度量 |
| game locale与fallback字体revision | clearLabel按MC当前语言；CJK/emoji和字体替换之后measure/draw使用同一实际字体与回退链 |
| font source与host override生效范围 | 原 `Provider.kt:175-185` 给center使用themed(含override)，但 `:181,191-208` 同步toast的是animated而不是themed，toast不自动跟随该Compose字体覆盖 |

中心默认Modern字体族见 `UiFontFamilies.kt:21-34`：Normal/Medium用regular字节，SemiBold/Bold用bold字节；`UiFontOverride.buildFamily` 可提供独立regular/medium/semibold/bold。Minecraft主题中心默认族在 `themes/Impls.kt:175-179` 是Minecraft-Regular/Bold，但同样可能受原宿主override影响。现有 `NotificationTypography` 只显式传递numeric weight，主线程必须给全局toast使用正确字体上下文；仍未提供精确font metrics时当前toast保留之前21/18行高fallback，这不是精确字形排版验收已通过。

**新发现的源代码缺口，不能用名字猜成Poppins已加载**：`Provider.kt:202` Modern toast请求 `poppins-medium`，Minecraft请求 `minecraft-bold`；`:203` body分别请求默认槽与minecraft。七树McFontService中已核对26.3注册入口 `:40-53` 仅注册bundled Minecraft槽，`:64-72` 从宿主加载ui/ui-bold并设置默认槽，源仓及主仓触点搜索未找到poppins-medium的注册。原 `StbFonts.kt:142-143,162` 对未命中的font.name直接返回0宽/0metrics，没有证明会自动回退Poppins。是否运行时有其它附属mod补注册该槽，静态源码未确认；主线程应把requestedFontKey/解析结果分开记录，不能声称真实Poppins字节或其度量已核实，也不能新增另一份字体/native渲染器来掩盖缺口。

### Adapter局部验证

新增 `src/test/java/com/muoniumplayer/configskija/sound/NotificationIntegrationTest.java`，用真实中立Adapter/Toast状态机、Java17与无native的trace Painter验证74项断言：HUD无设置显示、tick无绘制计时、重复绘制、viewport逆变换/pixelRatio、hover pause/cancel、左按动作/body、错误回调、focus/Screen/resize、旧hit退休、80active/50history、零尺寸、后台push与单UI线程约束。新增Adapter无Minecraft或loader符号，故没有引入新组合专属API；原14组合声音桥的核实证据不变。主入口接线已由主线程完成并在冻结复核中定位；游戏验收与后续提交仍由主线程统一执行。

```powershell
& D:/JDK17/bin/javac.exe "@E:/MUSIC/muonium-config-skija/build/sound-notifications/integration-tests/javac.args"
& D:/JDK17/bin/java.exe -ea -cp E:/MUSIC/muonium-config-skija/build/sound-notifications/integration-tests com.muoniumplayer.configskija.sound.NotificationIntegrationTest
```

## 最终冻结状态（2026-11-24）

**状态：本分工源文件已冻结，交给主线程统一暂存/提交。** 本侧未执行暂存、commit/push、游戏启动、Gradle、发布或主项目实装。以后遇到视觉/听感问题由主线程重新指派后继续；冻结不是只读权限变更，不修改文件权限或Git状态。

主线程接线复核（只读，行号为本次快照）：

| 接线 | 当前文件/证据 | 验证等级 |
| --- | --- | --- |
| 一次创建持久 SoundSession，并注入 MuoniumHost | `host/SkijaConfigService.java:65`，`host/MuoniumHost.java:51-52` | 已静态核对 |
| 主运行期 tick 声音，主题变化路由 | `SkijaConfigService.java:59-73` | 已静态核对 |
| open / beginClose / removed 的 open、close、静默release；退出立即释放 | `SkijaConfigService.java:113-115,158` | 已静态核对 |
| host错误进入同一 Notifications.shared 模型 | `MuoniumHost.java:71`；Adapter errors桥接 `SkijaConfigService.java:66` | 已静态核对 |
| Account铃铛读取unread，每次打开创建新center | `ui/ConfigView.java:188-189` | 已静态核对 |
| 无设置Screen时全局宿主帧仍进入renderToasts | `SkijaConfigService.java:128-149`；原有Skija帧复用及viewport tick/render调用 | 已静态核对；实际事件触发/缩放需运行验收 |
| 设置Screen上的toast绘制、pointer及关闭cancel | `host/SkijaConfigScreen.java:69,74,78,90` 与service `:115,151` | 已静态核对 |

本表只对看见的当前调用存在与参数路径作结论，没有逐组合审计新宿主的MC事件/Mixin安装，也没有替主线程签署最终包/游戏运行结果。精确字重/font key/字体metrics的剩余限制及源 `poppins-medium` 未找到注册的发现，仍按前节保留。

本轮最终自动测试使用重新扫描的当前源码清单，Java17直接编译实际model/ui、声音、通知与Adapter及本分工测试；不包含native `SkijaPainter` 和主线程MC/loader接线，不使用旧枚举或替代业务实现。旧参数文件遗漏了随后新增的TextInputController/HudMotion/ValueSnapshots等文件，因此已仅在新工程build生成新的完整参数清单，当前97份源码整体局部编译通过。该文件数是本次日志快照，不是工程规范。

| 最终检查 | 结果 |
| --- | --- |
| 当前实际中立model/ui及声音通知Adapter Java17编译 | PASS |
| SoundBehaviorTest | PASS，1288项断言 |
| NotificationsBehaviorTest | PASS，4547项断言 |
| NotificationIntegrationTest | PASS，74项断言 |
| SoundResourcesTest | PASS，9 OGG与head.png逐byte一致；JSON只迁移namespace |
| 14精确组合声音桥的先前javap+直接javac | 先前全部PASS；本轮未改声音桥/调用触点，无需重复构建 |
| 本分工文件diff/中立边界/冻结SHA清单 | 见 `build/sound-notifications/freeze-2026-11-24/static-checks.json` 与 `frozen-files.json` |

最终复跑命令与输出统一在 `build/sound-notifications/freeze-2026-11-24/`，参数文件 `javac.args`、编译记录 `compile.txt`、各测试 `*.txt` 以及 `test-results.json` 都由本次运行生成；运行产物不提交。资源清单依然为 `build/sound-notifications/resources.json`，实际环境声来源明确为 `E:/oneconfig lite/OneConfig/sounds/`，而非tracked原仓四个交互音文件或仅sounds.json声明。

```powershell
& D:/JDK17/bin/javac.exe "@E:/MUSIC/muonium-config-skija/build/sound-notifications/freeze-2026-11-24/javac.args"
& D:/JDK17/bin/java.exe -ea -cp E:/MUSIC/muonium-config-skija/build/sound-notifications/freeze-2026-11-24/classes com.muoniumplayer.configskija.sound.SoundBehaviorTest
& D:/JDK17/bin/java.exe -ea -cp E:/MUSIC/muonium-config-skija/build/sound-notifications/freeze-2026-11-24/classes com.muoniumplayer.configskija.sound.NotificationsBehaviorTest
& D:/JDK17/bin/java.exe -ea -cp E:/MUSIC/muonium-config-skija/build/sound-notifications/freeze-2026-11-24/classes com.muoniumplayer.configskija.sound.NotificationIntegrationTest
& D:/JDK17/bin/java.exe -ea -cp E:/MUSIC/muonium-config-skija/build/sound-notifications/freeze-2026-11-24/classes com.muoniumplayer.configskija.sound.SoundResourcesTest E:/MUSIC/muonium-config-skija "E:/oneconfig lite/MuoniumConfig-src" "E:/oneconfig lite/OneConfig"
```
