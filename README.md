# MuoniumPlayer —— 多版本单仓（private）

Minecraft 客户端 mod **MuoniumPlayer**（网易云音乐播放器 + 灵动岛 UI + 一起听房间）
的四个版本移植工程，统一放在这一个私有仓库里管理。

每个版本都是**一个多加载器工程**：共享源码在 `<版本>/src/`，加载器外壳在
`<版本>/fabric/` 与 `<版本>/neoforge/`（1.20.1 是 `forge/`）。结构参照
`F:/donked/modernMP-master`。

## 版本矩阵

| 目录 | Minecraft | 加载器 | 加载器版本 | 加载器插件 | fabric-api | Loader 下限 | Java | Gradle JDK |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `1.20.1 recode` | 1.20.1 | Fabric + **Forge** | 1.20.1-47.4.0 | MDG legacyforge 2.0.144 | 0.92.11+1.20.1 | 0.18.2 | 17 | JDK 21 |
| `1.21.1` | 1.21.1 | Fabric + NeoForge | 21.1.249 | MDG 2.0.144 | 0.116.15+1.21.1 | 0.15.11 | 21 | JDK 21 |
| `1.21.11 recode` | 1.21.11 | Fabric + NeoForge | 21.11.45 | MDG 2.0.144 | 0.141.6+1.21.11 | 0.17.3 | 21 | JDK 21 |
| `26.2` | 26.2 | Fabric + NeoForge | 26.2.0.72 | MDG 2.0.144 | 0.158.0+26.2 | 0.18.4 | 25 | JDK 25 |

### 运行期 Kotlin / 加载器下限

- Kotlin **不再随 mod jar 打包**，改由前置统一提供：
  - `1.20.1 Forge`：`Kotlin for Forge 4.3.0+`
  - `1.21.1 NeoForge`：`Kotlin for Forge 5.4.0+`
  - `1.21.11 NeoForge`：`Kotlin for Forge 6.0.0+`
  - `26.2 NeoForge`：`Kotlin for Forge 6.3.0+`
  - Fabric 侧：`Fabric Language Kotlin 1.13.12+kotlin.2.4.0+`
- 加载器最低支持版本不再跟随构建时的最新版：
  - `Forge 47.0.0+`
  - `NeoForge 21.1.0+ / 21.11.0+ / 26.2.0+`

**1.20.1 用 Forge 而不是 NeoForge**：1.20.1 是 Forge 的最后一个大版本，NeoForge 从
1.20.2 才分家，1.20.1 的模组生态与整合包都在 Forge 上。

四份工程共用：`mod_version=1.5.0`、`maven_group=com.muoniumplayer`、
`archives_base_name=MuoniumPlayer`、Skija `0.143.17`（Fabric 侧 `include` 进 jar，
Forge/NeoForge 侧用 shadow 合并；原生库平台由 `skija_natives` 控制，发布时需要改成四平台全打）。

产物命名：`MuoniumPlayer-<mc版本>-<加载器>-<mod版本>.jar`，
例如 `MuoniumPlayer-1.21.11-neoforge-1.5.0.jar` / `MuoniumPlayer-1.20.1-forge-1.5.0.jar`。
名字里带 Minecraft 版本是必要的，不是好看：四个版本工程是四个独立构建，
8 个 jar 要平铺进同一个 `dist/`，不带版本号的话 1.21.1 与 1.21.11 的 fabric 产物会同名互相覆盖。
`buildAll` 跑完会把成品汇总到仓库根的 `dist/`（`collectDist` 任务，只收成品、不收源码包与改名前的旧 jar）。
（1.20.1 是 `-forge`），各约 55～57 MB。

## 映射：四个版本统一 Mojang 官方名（mojmap）

共享源码只能有一套类名，而 Forge/NeoForge 侧**只有** mojmap 一种命名，所以四个版本
的共享源码都写官方名：

* Fabric 侧显式 `mappings loom.officialMojangMappings()`（26.2 除外，见下），
  `gradle.properties` 里已无 `yarn_mappings`；
* 26.2 的官方 jar 本来就不混淆（`version_manifest` 里没有 `client_mappings`，
  Yarn 与 intermediary 都停在 1.21.11），因此那一版连 `mappings` 声明都没有；
* 不用 architectury-loom（它能把 yarn 搬到 Forge）的原因有两条：它最高只发到
  `1.11.454`，跟不上本仓统一的 Gradle 9.6.1；`yarn-mappings-patch-neoforge`
  最高只到 `1.21+build.6`，覆盖不到 1.21.11 与 26.2。

**铁律：API 名一律用 `javap` 对着实际 jar 核实，禁止凭记忆写。**
各版本 `build.gradle` 里的 `dumpCompileClasspath` 任务就是为此准备的。

## 加载器耦合面

共享源码里不允许出现 `net.fabricmc` / `net.neoforged` / `net.minecraftforge`，
这条由每个版本的 `MultiLoaderStaticTest` 扫描断言。耦合面只有两处：

| 关注点 | 共享侧 | Fabric 侧 | Forge / NeoForge 侧 |
| --- | --- | --- | --- |
| 入口 + 事件 | `MuoniumPlayerClient` | `MuoniumPlayerMod`（`ClientModInitializer`） | `loader/{forge,neoforge}/MuoniumPlayer{Forge,NeoForge}`（`@Mod`） |
| 一起听通道 | `party/net/MpPayloads`（1.20.1 无，走裸字节） | `party/net/MpNetFabric` | `party/net/MpNet{Forge,NeoForge}` |

`MuoniumPlayerClient` 的调用契约（两个加载器都必须照做，顺序不能换）：
`createKeyMappings()` → `init()` → 加载器注册一起听通道 → 每 tick `onClientTick(Minecraft)`、
HUD 画完 `onHudRender()`、Screen 画完 `onScreenRendered(Screen)`。

## 各版本实测差异（都已 javap 核实）

| 主题 | 1.20.1 | 1.21.1 | 1.21.11 | 26.2 |
| --- | --- | --- | --- | --- |
| 按键分类 | 字符串翻译键 | 字符串翻译键 | `KeyMapping.Category.register(Identifier)` | 同 1.21.11 |
| 键盘常量 | `InputConstants.isKeyDown` | 同 | 同 | 同 |
| 变换栈 | `PoseStack.pushPose/popPose` | `getModelViewStack()` 返回 JOML `Matrix4fStack`，用 `pushMatrix/popMatrix` | 同 1.21.1 | 同 1.21.1 |
| 按钮渲染 | `renderWidget` 可覆写 | 同 | `renderWidget` 为 **final**，改实现 `renderContents` + `renderDefaultSprite`/`renderDefaultLabel` | 同 1.21.11 |
| 输入事件 | 散装形参 | 散装形参 | 打包成 `KeyEvent` / `CharacterEvent` / `MouseButtonEvent(…, MouseButtonInfo)` | 同 1.21.11 |
| 窗口句柄 | `Window#getWindow()` | 同 | `Window#handle()` | 同 1.21.11 |
| 当前界面 | `Minecraft#screen` | 同 | 同 | `Minecraft#gui.screen()` / `gui.setScreen()` |
| 聊天输出 | `Player#displayClientMessage` | 同 | 同 | `Player#sendSystemMessage`（前者已移除） |
| HUD 挂点（Fabric） | `HudRenderCallback` | 同 | 同 | `HudElementRegistry.addLast`（旧回调已删除） |
| Screen 挂点（Fabric） | `ScreenEvents.afterRender` | 同 | 同 | `ScreenEvents.afterExtract` |
| 客户端发包 | 原版 `ServerboundCustomPayloadPacket` | `PacketDistributor.sendToServer` | `ClientPacketDistributor.sendToServer` | 同 1.21.11 |
| 帧末合成 mixin | 无（GUI 是即时绘制） | 无 | `FramebufferMixin`（`RenderTarget#blitToScreen` 出口） | `GlSurfaceMixin`（`GlSurface#present` 入口） |
| 资源包 pack.mcmeta | 只写 `pack_format` 15 | 只写 `pack_format` 34 | 不写 `pack_format`，`min_format`=`max_format`=`[94,1]`（数据格式） | `pack_format` 88 + `min_format`/`max_format` |
| access widener | 空 | 空 | 空 | 无此文件 |
| 开发期运行 | BootstrapLauncher，走 JPMS 模块路径 | 同 1.20.1 | 纯 classpath，无模块路径 | 同 1.21.11 |

六条容易踩的坑，已写成测试钉住：

* **1.20.1 的 Forge 通道必须用 `EventNetworkChannel`，不能用 `SimpleChannel`。**
  后者的 `IndexedMessageCodec` 会在负载前面写一个消息判别号，那一个字节就让
  mpsync 服务端解不出帧，而线协议要求与 Fabric / Paper 侧逐字节一致。
* **NeoForge 的按键分组要单独 `event.registerCategory(...)`。**
  `KeyMapping.Category.register(Identifier)` 只做「构造 + 查重」，不写原版的
  `SORT_ORDER`；NeoForge 的控制界面读的是 `RegisterKeyMappingsEvent` 自己的
  `moddedCategories`，漏了这一步键能绑定但分组标题不出现。
* **加载器侧 shadow 出来的库 jar 必须再压平 Multi-Release 目录。**
  `skija-shared` / `types` / `gson` / `kotlin-stdlib` 都是 Multi-Release jar：
  根目录那份类是 Java 8 实现，`META-INF/versions/9/` 下才是 Java 9+ 实现，
  由清单里的 `Multi-Release: true` 决定用哪份。shadow 合并时 `exclude` 掉了输入 jar 的
  `MANIFEST.MF`，这个属性随之消失，运行期只剩 Java 8 那份 —— skija 的
  `io.github.humbleui.skija.impl.Cleanable` 基线实现要 `sun.misc.Cleaner`（JDK 9 起已删除），
  静态初始化必抛 `NoClassDefFoundError`，于是任何 Skia 原生对象都造不出来，
  `SkijaHost` 直接停用整个渲染层。**症状是「不崩溃、日志无异常、游戏内一个像素都不显示」**，
  极难从日志反推。因此 `flattenEmbeddedLibs` 把 `versions/N` 里的类提到根目录覆盖基线实现，
  编译期类路径、开发期运行时类路径、以及打进 mod jar 的都用压平后的 jar。
  Fabric 侧不受影响：Loom 的 `include()` 是把原 jar 整个嵌进去（JiJ），清单还在。
* **1.21.9 起 `pack.mcmeta` 不再接受「只写一个 `pack_format`」，而且格式号不能落在 (64, 81] 里。**
  缺格式区间声明时原版判定「元数据读不出来」（1.21.11 报 ERROR，26.2 报 WARN 后走兜底类型），
  mod 自己的 assets 只能靠兜底路径加载。1.20.1（15）与 1.21.1（34）在规则生效之前，不受影响。
  规则是照着 `PackFormat$IntermediaryFormat.validateNewFormat` 的字节码 + 实测报错原文反推的：
  mod 的这一份 `pack.mcmeta` 会被**按资源包和数据包各读一次**，两次的「旧格式窗口上界」不同
  （资源 64、数据 81），于是
  1. 格式号 > 64 → `min_format` / `max_format` 必填，且 `supported_formats` 被禁用；
  2. 格式号 ≤ 81 → 按数据包读时又**必须**有 `supported_formats`。
  落在 (64, 81] 的号（1.21.11 的资源格式 75 正是）两条互斥，写不出能同时过的文件。
  解法就是取一个 81 以上的号：26.2 用资源格式 88，1.21.11 用**数据**格式
  `min_format`=`max_format`=`[94, 1]`——这也正是 NeoForge 自己 jar 里那份 `pack.mcmeta` 的写法。
* **剪贴板不能用 AWT。**
  Minecraft 在 `net.minecraft.client.main.Main` 的静态初始化里写死了
  `System.setProperty("java.awt.headless", "true")`（四个版本的 `Main.class` 都能反编译出这三条字节码）。
  headless 下 `Toolkit#getSystemClipboard()` 必抛 `HeadlessException`，
  原来的 `KeyboardUtils` 又是 `catch (Exception) {}` 全吞，于是复制/粘贴静默变成空操作 ——
  「一起听」的复制链接、复制邀请码按钮照样亮「已复制」，剪贴板里什么都没有。
  正解是走原版自己的 `Minecraft#keyboardHandler`（`getClipboard()` / `setClipboard(String)`，
  内部是 GLFW 剪贴板函数）；这两个方法名与字段名在四个版本上完全一致，共享源码只需要一份实现。
  GLFW 剪贴板只能在渲染线程调用，所以写入时先判 `Minecraft#isSameThread()`，
  非渲染线程排到 `Minecraft#execute`。
* **加载器侧绝不能重复打包 Minecraft 自己就带的库。**
  1.20.1（BootstrapLauncher 1.1.2）与 1.21.1（2.0.2）的开发期运行会把 `legacyClassPath`
  里每个 jar 变成 JPMS 自动模块，两个模块导出同一个包时 JVM 在解析阶段就崩、游戏一行日志都打不出来：
  `ResolutionException: Modules com.google.gson and MuoniumPlayer.<loader>.embedded.libs export
  package com.google.gson to module org.lz4.java`。
  用「shadow 产物的包名集合 ∩ `clientLegacyClasspath.txt` 里各 jar 的包名集合」实测，
  重合的只有 `gson` / `jna` / `jna-platform` / `error_prone_annotations` 四个，
  全部由 Minecraft 提供，因此从 `embeddedLibs` 里 `exclude` 掉（编译期直接用原版那几个版本）。
  1.21.11 / 26.2 开发期不走模块路径，原本不暴露这个问题，但发布 jar 里同样不该塞重复库，四个版本统一排除。

## 全屏歌词背景 / 主题配色

打开全屏歌词页后，左上角 **A-Z** 快捷面板里有两条独立选项：

- **歌词背景**：`模糊封面`（原有默认） / `隐现`（封面取色的 MeshGradient + Dithering，随后播放包络实时响应）。
- **主题配色**：`当前主题`（原有默认，保留所有预设与自定义颜色） / `封面跟随`（按当前歌曲封面自动生成同色系明暗主题）。

两项都只影响新选择，旧配置没有对应字段时继续使用原有模糊封面和当前主题；`封面跟随`
不会覆盖预设主题的调色数据，随时切回 `当前主题` 即可恢复。

## 更新提示（四版共用一份实现）

启动后在主菜单弹一次提示：有新版本时提示更新，连续三次连不上清单服务器时提示检查网络。
实现与 ffmpeg 提示同构 —— 「守护线程探一次 + 每 tick 轮询该不该弹 + Skija 画界面」。

| 位置 | 职责 |
| --- | --- |
| `core/update/SemVer.java` | 版本号按段比数字（`String#compareTo` 会认为 1.10.0 比 1.9.0 旧） |
| `core/update/UpdateManifest.java` | 解析远端 `update.json`，查表顺序 `<mc>-<loader>` → `<mc>` → `fallback` |
| `core/update/UpdateChecker.java` | 守护线程拉清单、缓存 30 分钟、下载页域名白名单、额度与失败计数落盘 |
| `core/screens/ncm/UpdateNoticeOverlay.java` | 提示界面本体（Skija 画）；宿主薄壳见 `core/screens/GuiUpdateNotice.java` |
| `ConfigPaths.UPDATE_NOTICE` | `config/muonium/update_notice.json`：`skipCount` / `skipCountVersion` / `failureCount` / 清单缓存 |

清单不在本仓库里 —— 本仓库是私有的，而客户端必须能匿名读到清单。清单在公开仓库
[zihe114514/MuoniumPlayer-updates](https://github.com/zihe114514/MuoniumPlayer-updates)，
本地留了一份副本在 `tools/update-manifest/`（只是副本，改了要自己推到那个仓库）。

发一个新版本要做的事：把 jar 发到 Releases，然后改清单里对应 channel 的 `latest` / `page` / `notes`。
mod 侧不需要动。

几条刻意的设计：

- **加载器 id 由入口外壳登记**（`MuoniumPlayerClient.setLoaderId`，在 `init()` 之前）。共享源码不许
  引用加载器 API，没法自己判断跑在哪个加载器上，而清单是按「MC 版本 + 加载器」分桶的。
- **MC 版本写死成 `MuoniumPlayerClient.MC_VERSION`**，静态测试钉住它与 `gradle.properties` 的
  `minecraft_version` 一致（原版取版本号的入口四个版本各不相同）。
- **下载页有域名白名单**（github / githubusercontent / jsdelivr / modrinth / gitee）。清单是公开仓库里的
  纯文本，万一被改，「点一下跳浏览器」就成了把用户导去任意站点。客户端永远不会自动下载或替换 jar。
- **三个清单源，全部同时拉，取版本号最高的那份**（ghfast + raw + gcore.jsdelivr，实测都是 200）。
  单一源在国内网络下不可靠；而各源缓存策略不同，同一时刻可能一个给新清单、一个给几小时前的旧清单，
  所以「谁先答」不代表「谁更新」—— 全部试一遍取最高才不会漏掉新版本。
  源 1（raw 的加速入口）CDN 只缓存 5 分钟，jsDelivr 对 `@main` 是 12 小时。
  2026-09-16 去掉了 `cdn.jsdmirror.com` 与 `cdn.jsdelivr.net` 两个源。
- **界面是纯 Skija**（`UpdateNoticeOverlay extends NCMPanel`）：宿主 `GuiUpdateNotice` 只是薄壳，
  负责开帧、灌尺寸、转发输入、收起。四版共用同一份界面源码，不再各写一份原生控件版。

## 目录职责

```
C:\MUSIC
├─ 1.20.1 recode/      1.20.1（Fabric + Forge），渲染层 Skija 重写的主战场
├─ 1.21.1/             1.21.1（Fabric + NeoForge）
├─ 1.21.11 recode/     1.21.11（Fabric + NeoForge）
├─ 26.2/               26.2（Fabric + NeoForge，官方名 + Java 25）
├─ mpparty-common/     四个工程共用的「一起听」客户端源码，禁止出现 Minecraft 类型
├─ tools/              跨版本管理脚本（build-all / diff-versions）
├─ .gitignore          全仓统一忽略规则
└─ .gitattributes      换行与二进制规则
```

每个版本目录内部：

```
<版本>/
├─ settings.gradle       pluginManagement（Fabric / NeoForged / Forge / 阿里云）+ include 'fabric','neoforge'
├─ build.gradle          聚合：allprojects 的 group/version + buildAll / collectDist 任务
├─ gradle.properties     版本号、加载器版本、代理与 JDK 登记
├─ src/main/{java,resources}   共享源码与资源（assets、字体、纹理），只用原版 API
├─ src/test/java         离线静态/探针测试（挂在 fabric 子工程上跑）
├─ fabric/               fabric-loom：入口外壳、通道实现、fabric.mod.json、mixins.json、accesswidener
└─ neoforge/（1.20.1 是 forge/）  MDG + shadow：入口外壳、通道实现、mods.toml、pack.mcmeta
```

两个加载器子工程各自把共享源树挂进自己的 source set（**绝不复制**）：

```gradle
sourceSets.main.java.srcDirs = [
        'src/main/java',                                       // 本加载器外壳
        rootProject.file('src/main/java'),                     // 共享主体
        rootProject.file('../mpparty-common/src/main/java')    // 一起听共用源码
]
```

## 不入库的东西

`build/`、`.gradle/`、`.idea/`、`run/`（含账号 cookie 与缓存音频）、`logs/`、`*.log`、
`_mcref/`（反编译 client jar + fabric-api + JDK，约 470 MB，本地生成）、
误建目录 `%SystemDrive%/`、`_legacy-git/`。

入库的是每个版本的 `src/`、`docs/`、`tools/`、`libs/`（三个第三方 jar：
`kotlin-stdlib` / `music-api` / `zstd-jni`，Maven 上取不到，必须随仓走）、
Gradle wrapper 与构建配置。

## 常用操作

在仓库根目录的 PowerShell 里直接点调用脚本（不要用 `pwsh -File`，那样数组参数会被当成一个字符串）：

```powershell
# 全版本构建（默认任务 buildAll = 两个加载器各出一个 jar，并汇总到仓库根 dist/；
# 日志写 _build_all.log，末尾打印 dist/ 里的 8 个 jar，任一版本失败退出码为 1）
# 默认会清理输出目录中旧的 MuoniumPlayer-*.jar；需要保留时加 -KeepOldJars
.\build-all.bat

# PowerShell 里直接调用；构建到指定目录而不是默认 dist/
.\tools\build-all.ps1 -OutputDir releases\1.5.0

# 只构建部分版本 / 换任务 / 离线 / 不复用 Gradle daemon
.\tools\build-all.ps1 -Versions '1.21.1','26.2' -Task ':fabric:build'
.\tools\build-all.ps1 -Task ':fabric:test' -Offline -NoDaemon

# 单版本：两个加载器都构建
cd '1.21.11 recode'; .\gradlew.bat buildAll

# 单版本：只编一侧 / 只跑测试（测试挂在 fabric 子工程上）
cd '1.21.11 recode'; .\gradlew.bat :neoforge:compileJava
cd '1.21.11 recode'; .\gradlew.bat :fabric:test

# 开发期启动客户端
cd '1.21.11 recode'; .\gradlew.bat :fabric:runClient      # 或 :neoforge:runClient / :forge:runClient

# 看两个版本之间的源码漂移（只在一边的文件 / 两边都有但内容不同的文件）
.\tools\diff-versions.ps1 -Left '1.21.1' -Right '1.21.11 recode'
.\tools\diff-versions.ps1 -Left '1.21.1' -Right '26.2' -SubPath 'src/main/java/com/muoniumplayer/core' -ShowSame
```

实测 `1.21.1` 与 `1.21.11 recode` 的 `src/`：748 / 750 个文件里 720 个内容完全相同，
26 个不同，只在一边的各 2 / 4 个 —— 版本差异的实际面积就这么大。

多加载器化之后，每个版本 707 个 java 文件里只有 20 个左右会碰 `net.minecraft`；
真正的加载器差异压缩到了 2 个文件（入口外壳 + 通道实现）。

**首次构建 Forge/NeoForge 侧要等**：MDG 的 `createMinecraftArtifacts`（反编译 + 打补丁 +
重编译整个 Minecraft）单版本约 10～15 分钟，之后走缓存。

## Git 网络配置（本机）

github.com 直连不通、必须走 `127.0.0.1:7890`，且 Windows 默认的 schannel 后端在这个代理下
TLS 握手会失败，所以本仓已写入 **仓库级** 配置（`git config --local`）：

```
http.proxy   = http://127.0.0.1:7890
https.proxy  = http://127.0.0.1:7890
http.sslBackend = openssl
```

换机器或重新 clone 后需要再设一遍这三项，否则 push 会报
`schannel: failed to receive handshake` 或 `Connection was reset`。

Gradle 侧同一个代理会偶发 `Remote host terminated the handshake`，
`maven.neoforged.net` / `maven.minecraftforge.net` 上尤其容易碰到。实测这两个域名**直连**
稳定 200 且约 1s，走 7890 代理 `curl -I` 三次里有两次是 `000`，所以正解是把它们放进直连白名单
（四个版本的 `gradle.properties` 都已加）：

```properties
systemProp.http.nonProxyHosts=…|maven.minecraftforge.net|maven.neoforged.net
systemProp.https.nonProxyHosts=…|maven.minecraftforge.net|maven.neoforged.net
```

TLS 版本也**不能只留 1.2**。1.20.1 那份原来写的是 `systemProp.https.protocols=TLSv1.2`，
`maven.minecraftforge.net`（只有这一版要用它取 `forge-1.20.1-47.4.0-userdev.jar`）会直接拒绝
纯 1.2 的 ClientHello，`:forge:createMinecraftArtifacts` 稳定失败并报
`The server may not support the client's requested TLS protocol versions: (TLSv1.2)`。
现在写成两个都提、1.3 优先：

```properties
systemProp.https.protocols=TLSv1.3,TLSv1.2
```

另外 `repo.maven.apache.org` 在本机完全不可用，各版本 `build.gradle` 里的
`retargetCentral` 会把所有 Maven Central 仓库（含插件自己注入的 `mavenCentral()`）
改写到阿里云。

## 历史

`1.20.1 recode` 迁入前是独立 git 仓库，历史（baseline → Skija 探针 → 渲染层方案 → S1 宿主层）
完整保留在分支 **`archive/1.20.1-recode`**；其原始 `.git` 目录备份在未入库的
`_legacy-git/1.20.1-recode.git`。`main` 的首个提交是四个版本迁入时的磁盘现状
（含当时 1.20.1 未提交的工作区改动）。

## 待办：跨版本去重

加载器维度的重复已经消掉了（同一份 `src/` 同时喂给两个加载器）。剩下的是**版本维度**：
四份 `src/` 各约 28 MB、707 个 java 文件，彼此高度重复（字体资源 21.5 MB 完全相同，
Git 侧已按内容去重，但源码层面没有）。

下一步可把与 Minecraft 无关的部分（`repackage/`、`core/ncm/`、编解码器）按
`mpparty-common` 的模式抽成跨版本共享 source set，版本相关的只留 `compat/` 与 `mixin/`。
改动前先用 `tools\diff-versions.ps1` 确认漂移面积。

## 分支

* `main` —— 四个版本迁入单仓时的状态（当时还都是单加载器 Fabric 工程）。
* `feat/multiloader` —— 本次多加载器化，四个版本各一个提交。
* `archive/1.20.1-recode` —— `1.20.1 recode` 迁入前的独立仓库历史。
