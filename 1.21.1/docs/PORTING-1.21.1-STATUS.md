# MuoniumPlayer 1.20.1 -> 1.21.1 Fabric 移植记录 工程目录：`C:\MUSIC\1.21.1`
基线：`C:\MUSIC\1.20.1 recode`（Skija 渲染层版，唯一参考来源）
旁证：`C:\MUSIC\1.21.11 recode` 是同一份代码到 **1.21.11** 的移植。1.21.11 的 blaze3d 被整体重写，
那边为此加了 mixin、清空了 AW、改了包名；**本次 1.21.1 完全没有这些问题**，两份记录不要混着看。 --- ## 一、当前状态 | 项 | 结果 |
| --- | --- |
| `gradlew compileJava` | 通过（只需改 4 处，全部是签名变更） |
| `gradlew test` | 35 项全绿（原 31 项 + 本轮新增 `Port1211StaticTest` 4 项） |
| `gradlew build` | 通过，产物 `build/libs/MuoniumPlayer-1.3.0.jar`（57.5 MB，含 Jar-in-Jar 依赖） |
| `runClient` 实机 | 通过：Loader 加载 `Minecraft 1.21.1 with Fabric Loader 0.19.3`，进存档，Skija 起来了 |
| GL 错误 / 异常 | 0 条（`GL_INVALID` / `Exception` / 渲染路径自动停用日志全部为空） | 实机日志关键行： ```
[main/INFO] (FabricLoader/GameProvider) Loading Minecraft 1.21.1 with Fabric Loader 0.19.3
[main/INFO] (FabricLoader/Mixin) Compatibility level set to JAVA_21
[STDOUT]: [MuoniumPlayer] ffmpeg startup probe: found
[STDOUT]: [MuoniumPlayer/SkijaHost] DirectContext 创建成功
[STDOUT]: [MuoniumPlayer/SkijaHost] 离屏目标已建立 854x480
[STDOUT]: [MuoniumPlayer/SkijaHost] 离屏目标已建立 1920x1009 <- 窗口放大后重建，尺寸跟随正常
``` --- ## 二、为什么这次工作量这么小 1.21.1 与 1.20.1 之间 **blaze3d 与 GUI 管线没有换代**：`RenderSystem` 还是即时封装、
`GlStateManager` 还在 `com.mojang.blaze3d.platform`、`BufferRenderer` 还在、`DrawContext` 还是即时绘制、
`Framebuffer` 的附件还是 `int`。而渲染层重写之后，全工程只有 12 个文件 import `net.minecraft` / `com.mojang`
（703 个 java 文件里其余的一个都不碰 MC），其中真正与新版本有冲突的只有 3 个文件。 对比：1.21.11 那次因为 blaze3d 改成 GPU 抽象 + GUI 延迟批处理，`SkijaHost` 要重写、还得加 mixin。 --- ## 三、构建配置改动 | 文件 | 改动 |
| --- | --- |
| `gradle.properties` | `minecraft_version=1.21.1`、`yarn_mappings=1.21.1+build.3`、`fabric_version=0.116.15+1.21.1`、`loader_min_version` 0.18.2 -> 0.15.11（见第八节）、nonProxyHosts 两处调整（见第五节） |
| `build.gradle` | `targetJavaVersion` 17 -> 21（守护进程与编译/运行统一 JDK 21，不再需要两个 JDK）；`loom.runs.client` 加 `MU_QUICKPLAY` -> `--quickPlaySingleplayer` |
| `fabric.mod.json` | description 改 1.21.1、`java` 依赖 `>=17` -> `>=21` |
| `muonium.mixins.json` | `compatibilityLevel` `JAVA_17` -> `JAVA_21`（`client` 数组仍然是空的，本工程没有 mixin） |
| `muoniumplayer.accesswidener` | 条目清空（保留文件与理由注释） |
| `tools/api.ps1` | classpath 指向本工程的 `build/compile-classpath.txt`，javap 换 JDK 21 | `loader_min_version` 的取值依据与实测见第八节。 `accesswidener` 清空的两条理由缺一不可：1.20.1 那两条是旧渲染层的 `StencilClipManager` 要改主
framebuffer 的 depth 附件才需要的，Skija 版已经不碰（全工程 grep 不到引用）；而且 1.21.1 的
`Framebuffer.colorAttachment/depthAttachment` 本来就是 `public int`（1.20.1 是 protected），
即使要用也不需要 AW。`Port1211StaticTest#accessWidener保持无条目` 钉住了这条。 --- ## 四、1.21.1 API 差异（全部经 javap 实测，未凭记忆） 实测方式：`gradlew dumpCompileClasspath` 后 `powershell -File tools\api.ps1 -Classes <类名> [-Filter <正则>]`。 | 1.20.1 | 1.21.1 | 受影响的文件 |
| --- | --- | --- |
| `Screen#renderBackground(DrawContext)` | `renderBackground(DrawContext, int, int, float)` | `compat/mc/GuiScreen.java` |
| `Element#mouseScrolled(double, double, double)` | `mouseScrolled(double, double, double horizontal, double vertical)` | `compat/mc/GuiScreen.java` |
| `RenderSystem.getModelViewStack()` 返回 MC 的 `MatrixStack`（`push`/`pop`） | 返回 **JOML `Matrix4fStack`**（`pushMatrix`/`popMatrix`） | `core/screens/GuiFfmpegNotice.java` |
| `Framebuffer.colorAttachment/depthAttachment` 是 protected int | 变成 **public int**（AW 不再需要） | `muoniumplayer.accesswidener` | `renderBackground` 传 `0, 0, 0.0F`：原版实现只按「有没有世界」在 `renderInGameBackground` 与
全景背景贴图之间二选一，这三个新形参一个都没用到（留给子类做悬停/动画背景），
所以 1.8.9 语义的 `drawDefaultBackground()`（拿不到鼠标位置）传零是等价的。 `GuiFfmpegNotice` 的 `pushMatrix/translate/scale/popMatrix` 是**纯改名**，没有补
`RenderSystem.applyModelViewMatrix()`：1.20.1 基线也没有调用它，也就是说那段缩放在基线上
本来就不生效（文字走 `DrawContext` 自己的矩阵）。补上会让标题突然变大，属于改变已验收的视觉，故不动。 **确认仍然存在、不需要改的**（都用 javap 核过，1.21.11 那边则已经删除或搬家）：
`com.mojang.blaze3d.platform.GlStateManager`（`_bindTexture` / `_activeTexture` / `_texParameter(int,int,float)` /
`_pixelStore` / `_viewport` / `_blendFuncSeparate` / `_genTexture` / `_deleteTexture` 全在）、
`net.minecraft.client.render.BufferRenderer.reset()`、`RenderSystem.applyModelViewMatrix()`、
`Util.getOperatingSystem().open(String)`、`Screen#keyPressed(int,int,int)` / `charTyped(char,int)` /
`mouseClicked(double,double,int)` / `mouseReleased(double,double,int)`、`PressableWidget#onPress()`（无抽象 `drawIcon`）、
`DrawContext#drawText(...)` 返回 int、`KeyBinding(String, Type, int, String)`、
fabric-api 的 `HudRenderCallback` / `ScreenEvents` / `ClientTickEvents` / `KeyBindingHelper`。 --- ## 五、环境（本轮踩到的坑，与 1.20.1 那份配置不同） 代理白名单必须按本机实测重排，**照抄 1.20.1 的配置会两次卡死构建**： | 主机 | 1.20.1 时 | 本轮实测 | 处理 |
| --- | --- | --- | --- |
| `maven.fabricmc.net` | 白名单（直连） | 直连 21s 超时；走 7890 代理 200 | **从 nonProxyHosts 删掉**，让它走代理 |
| `resources.download.minecraft.net` | 未涉及（资源早已缓存） | 走代理抽风（同批 3 个对象里第 3 个 curl 返回 000），Loom 抛 `DownloadException` | **加进 nonProxyHosts**，直连 |
| `libraries.minecraft.net` | 白名单 | 直连 200 且更快 | 保持白名单 |
| Maven Central | 直连超时 / 代理握手中断 | 同上 | 保持 `build.gradle` 里改写到阿里云的 `repositories.all(retargetCentral)` | 症状对照：`Could not HEAD 'https://maven.fabricmc.net/...-sources.jar' ... Connection timed out`
= 第一条；`Execution failed for task ':downloadAssets' ... DownloadException: Failed to download`
= 第二条（这条报错不打 URL，只能自己拿 `assets/indexes/1.21.1-*.json` 里的哈希去 curl 对比）。 命令（PowerShell）： ```powershell
cd 'C:\MUSIC\1.21.1'; $env:JAVA_HOME='C:/Users/24914/.jdks/ms-21.0.12.1'
.\gradlew.bat compileJava --console=plain
.\gradlew.bat test --console=plain
.\gradlew.bat build --console=plain # 实机：MU_QUICKPLAY 直接进存档，省掉在标题界面盲点鼠标
$env:MU_QUICKPLAY='新的世界'
Start-Process -FilePath '.\gradlew.bat' -ArgumentList 'runClient','--console=plain' -WindowStyle Hidden ` -WorkingDirectory (Get-Location).Path -RedirectStandardOutput 'build\runclient.out.log' -RedirectStandardError 'build\runclient.err.log'
Select-String -Path build\runclient.out.log -Pattern 'Skija|GL_INVALID|已停用'
``` 首次 `compileJava` 约 4 分钟（含 1.21.1 的 remap sources），之后约 40 秒。javac 的错误走 **stderr**，
用 `Start-Process -RedirectStandardError` 时记得读 `.err.log`。
`run/` 已带上基线的 `options.txt`（右 Shift 开播放器、H 开 HUD 编辑器、`pauseOnLostFocus:false`）、
`config/muonium/*` 与存档；`run/logs`、旧版 `MusicCache`、崩溃报告没有迁。 --- ## 六、静态测试（35 项） 新增 `src/test/java/com/muoniumplayer/Port1211StaticTest.java`（4 项），钉的是「改回去照样编译、
运行期才炸」的四类东西：版本三件套 + Java 21 的四处声明一致；三个改过签名的调用点不许回退；
AW 保持无条目；`SkijaHost` 对 `blaze3d.platform.GlStateManager` / `BufferRenderer.reset()` /
unpack 布局复位这三样的依赖不许被「顺手现代化」。扫源码前先去注释（注释里刻意保留了 1.20.1 的旧写法）。 原有 31 项（`Lwjgl2KeycodeProbeTest` / `DynamicIslandMathProbeTest` / `LyricParserProbeTest` /
`RenderLayerStaticTest` / `SkijaGeometryStaticTest` / `SkijaTextStaticTest`）**一行未改**，全绿。
这也从侧面说明业务层与渲染层在本次移植中确实零改动。 --- ## 七、已知遗留 1. **实机只验到「进存档 + Skija 起来 + 零 GL 错误」这一层**。播放器界面 / HUD 编辑器 / 逐字歌词 / 灵动岛 / 封面墙的逐屏视觉对照没做（基线截图在 `C:\MUSIC\1.20.1 recode\build\shot-*.png`）。 由于渲染层与业务层零改动、且渲染路径的自动停用日志为空，风险集中在 MC 侧 GUI 尺寸/缩放语义而不是绘制本身。
2. 需要真实账号的功能一如基线未验：各平台登录与搜索、解灰、私人 FM、一起听、听歌时长上报、歌单同步。
3. **Skija 原生库只打 `windows-x64`**（`gradle.properties` 的 `skija_natives`）。发布前需补 linux-x64 / macos-x64 / macos-arm64，四平台合计约 46 MB；`SkijaNatives` 会按 `os.name`/`os.arch` 自动挑路径。
4. 本工程**没有 git 仓库**（未 init）。基线 `C:\MUSIC\1.20.1 recode` 那边的改动也从未提交。
5. `docs/PORTING-1.21.11-SKIJA.md` 与 `docs/PORTING-STATUS.md` 是从基线一起带过来的历史文档 （分别是 1.21.11 的交接说明与 1.8.9 -> 1.20.1 的记录），本文只覆盖 1.20.1 -> 1.21.1。 --- ## 八、Fabric Loader 支持范围 **结论：`depends.fabricloader = ">=0.15.11"`，上界不设。** 0.15.11 是本 mod 在 1.21.1 上的真实下界，
不是保守取值；比它更低的 loader 装上去只会变成「装上了但 fabric-api 缺失」这种更难排查的失败。 下界是怎么定出来的（三条都实测过，缺一条都不足以下这个结论）： 1. **fabric-api 是硬依赖，它自己的下限就是天花板。** 解包 `fabric-api-0.116.15+1.21.1.jar`： 根 `fabric.mod.json` 写 `fabricloader >=0.15.11`，内嵌的 **49 个模块逐个核对，无一例外也是 `>=0.15.11`**。 所以 mod 侧再往下写都不会真的多支持一个 loader 版本。
2. **本 mod 自己用到的 loader 侧能力在 0.15.x 上齐备**：`ClientModInitializer`、 mixin 配置（`minVersion 0.8` / `compatibilityLevel JAVA_21`，且 `client` 数组为空、不做任何注入）、 access widener（无条目）。关键是 JAVA_21 这一项：0.15.11 带的是 `sponge-mixin 0.13.3+mixin.0.8.5`（0.16.9 是 0.15.4、0.19.3 是 0.17.3）， 实测日志明确打出 `Compatibility level set to JAVA_21`，没有报不支持。
3. **0.15.11 已实机跑通**（把 `loader_version` 临时压到 0.15.11 后 `runClient`）： `Loading Minecraft 1.21.1 with Fabric Loader 0.15.11` -> 进存档 -> `[MuoniumPlayer/SkijaHost] DirectContext 创建成功` / `离屏目标已建立 854x480`， 全程 0 条 `GL_INVALID` / 异常 / 渲染路径自动停用日志。 截图 `build/shot-ncm.png`（游戏内 HUD 信息胶囊：圆角、图标字体、渐变都对）与 `build/shot-hud.png`（H 打开的「音乐 HUD 设置」：侧栏图标字体、中文、开关、搜索框、滚动条、卡片阴影都对）。 上界不设的依据：1.21.1 在 fabric meta 上可用的 loader 一直到 0.19.4，其中 0.19.3 是本工程
开发期一直在用、也完整跑过 `build` + `runClient` 的版本。loader 在小版本间对本 mod 用到的
这三样能力没有破坏性变更，写死上界只会在用户升级 loader 后凭空多出一个「装不上」。 `gradle.properties` 里 `loader_version`（开发期）与 `loader_min_version`（发布下限）是两个独立的值：
前者保持 0.19.3，只影响本地 `runClient` 与编译类路径；后者才会被 `processResources` 展开进
`fabric.mod.json`。改完记得重新 `build`——产物里的 `depends.fabricloader` 已核对为 `>=0.15.11`。 ### 合成按键的一点说明（已排除） 那一轮实测里用 `keybd_event` 触发 HUD 编辑器（H）成功，但同样方式触发播放器主界面没有反应，
日志里也没有任何异常。后续实机（用户真实键盘）确认播放器一切正常：歌词按逐字 TTML 加载、
Automix 正常编排，日志里能看到
`[Music/AMLL] Loaded word-by-word TTML lyrics` 与 `[Automix] armed ...`。
所以那是合成输入的问题（`keybd_event` 走的扫描码在这条路径上没被 GLFW 认成期望的键），
不是移植缺陷。**结论：验收播放器界面必须用真实键盘，别信合成按键的否定结果。** --- ## 九、新增配置项：播放器开关动画速度 移植之外的一个功能改动（用户要求）：让用户自由调节**播放器界面打开/关闭那段淡入淡出**的速率，
其它动画一律不动。 | 项 | 值 |
| --- | --- |
| 配置字段 | `HudConfig.playerFadeSpeed`，持久化键 `playerFadeSpeed`（`config/muonium/hud.json`） |
| 设置项 | `HudSetting.PLAYER_FADE_SPEED`（标签「开关动画速度」），区间 0.30×~3.00×，默认 1.00× |
| 界面入口 | 现代设置界面新增分类**「播放器」→ 分组「开关动画」**；经典设置界面新增**「播放器」**分组，同一个滑块 |
| 消费点 | `NCMScreen#drawScreen` 里整屏 alpha 的那一次 `Interpolations.interpolate`，系数 = `BASE_FADE_RATE(.4) × 倍率`，钳在 (0,.95] |
| 恢复默认 | `HudConfig.resetPlayerAnimation()`，两套界面的「恢复默认」都会调 | 几个刻意的选择： 1. **基准 .4 不动。** 用户调的是倍率，改基准会让所有存量配置的观感整体偏移一档。
2. **下界 .02 必须留。** 关闭逻辑是 `closing && alpha <= 0.02f` 才 `displayScreen(null)`； 系数为 0 时 alpha 永远到不了阈值，播放器就再也关不掉。
3. **只有一个值，不拆「打开」「关闭」。** 淡入与淡出共用同一次 alpha 逼近调用， 拆成两个参数会给出「能设置但不生效」的假象。
4. **跟着 alpha 走的 0.9→1.0 整窗微缩放不额外乘倍率**——它本来就是同一段动画的一部分，会自动跟随。
5. **明确不动的**：页面切换的交叉淡入（写死 `alphaInterpolateSpeed = 0.4f`）、窗口尺寸档位动画 （写死 `.18f`）、歌词页/各种浮层的开合、灵动岛的六个速度参数。 `PlayerFadeSpeedProbeTest`（5 项，测试总数 35 → 40）钉住上面这些：区间与默认值、越界夹紧、
恢复默认覆盖到、**全工程只有 5 个允许的文件引用这个倍率**（多一个就说明它被接到了别的动画上，
而那种错误不会有任何编译错误）、以及两套界面都有入口。
