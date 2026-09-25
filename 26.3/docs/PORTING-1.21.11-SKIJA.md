# 交接文档：把 Skija 版 MuoniumPlayer 从 1.20.1 移植到 1.21.11 (Fabric)

> 写给接手移植的 agent。当前代码在 `C:\MUSIC\1.20.1 recode`，已通过静态测试并实机跑通。
> 目标：同一份业务代码跑在 Minecraft 1.21.11 Fabric 上。

---

## 1. 这个项目是什么

MuoniumPlayer —— 一个内嵌在 Minecraft 里的网易云 / QQ 音乐 / 酷狗播放器 mod。
UI 是完全自绘的桌面级界面：圆角面板、封面墙 3D 转场、逐字 KTV 歌词、灵动岛、HUD 编辑器。

**代码分两半，移植时请始终区分：**

| | 位置 | 移植时怎么对待 |
| --- | --- | --- |
| **业务逻辑** | `core/` 下除 `rendering/` 外的绝大部分：音乐 API、歌词解析、布局计算、动画进度、命中测试 | **一个字符都不要改。** 布局与动画的表达式是调出来的，改了视觉就错 |
| **渲染层** | `render/skija/` 加一批「门面」类 | 只有这里需要动 |

上一轮工作把渲染层整体从「自写 GLSL + OpenGL 1.x 固定管线模拟层」换成了 **Skija**（Skia 的 Java 绑定）。
旧的着色器包、Framebuffer、字形图集、43 KB 的固定管线模拟层全部删除。

---

## 2. 渲染层架构（移植的全部工作面）

```
src/main/java/com/muoniumplayer/render/skija/
  SkijaHost.java     692 行  <- 唯一与 Minecraft 打交道的类。移植 90% 的工作在这一个文件
  SkijaGfx.java      482 行  绘制状态：Paint 复用 / 当前颜色 / BlendMode / 矩阵栈 + 属性栈 / 当前绑定纹理
  SkijaImages.java   524 行  GL 纹理 -> Skia Image 的桥；uv 与源矩形规范化
  SkijaShapes.java   326 行  矩形 / 圆角矩形 / 描边 / 渐变 / 扇形 / 环带
  SkijaEffects.java  265 行  唯一的自定义着色器（四角双线性渐变 SkSL）加高斯模糊 / 泛光
  SkijaClip.java     230 行  裁剪（取代旧的 stencil 模板）
  SkijaText.java      89 行  AWT Font -> Skia Typeface 登记表
  SkijaNatives.java  261 行  原生库加载
```

### 最重要的一条设计约束

> **只有 `SkijaHost` 认识 Minecraft。** 其它 7 个文件里不允许出现 `net.minecraft` 或 `com.mojang`。

这条约束就是「换游戏版本只改一个文件」这份收益的**全部来源**。它有单测钉住
（`RenderLayerStaticTest#只有宿主层耦合Minecraft`）——因为违反它不会有任何编译错误来提醒你。

`SkijaHost` 对上层只承诺一件事：**「给我一块 Canvas，坐标系是 GUI 缩放坐标、原点左上、y 向下」**。
它现在做四件事：

1. `beginFrame()`：拿窗口尺寸 -> 建 / 复用 Skia 离屏 render target -> 清成全透明 -> 按 GUI 缩放 scale
2. **拍下 GL 状态快照**（`GlSnapshot`，见第 4 节，别删）
3. `endFrame()`：把离屏内容合成回 Minecraft 当前的 FBO
4. **原样还原 GL 状态**

### 冻结的门面（签名不许改，业务调用方零改动）

`SharedRenderingConstants`（21 个绘制方法）、`core/rendering/rendersystem/RenderSystem`、
`today/opai/api/impl/GLStateManagerImpl`（40 个方法）、`RenderUtilImpl`、
`core/rendering/Image`、`StencilClipManager`、`ScissorClipManager`、
`core/rendering/font/CFontRenderer`、`compat/gl/GL11`（已精简成只剩纹理上传）。

移植时这些方法体可能要改，但**签名和语义必须保持**。

---

## 3. 移植的真正难点：1.21.11 的 blaze3d 已经被重写

这是本次移植 **唯一的高风险项**，请第一件事就验证它。我已经拿
`1.21.11+build.6` 的 yarn 映射 jar 核实过下面这些事实：

| 1.20.1 里的东西 | 1.21.11 的状况 |
| --- | --- |
| `com.mojang.blaze3d.platform.GlStateManager` | **搬到了 `com.mojang.blaze3d.opengl.GlStateManager`**，方法名大体沿用（`_enableBlend` / `_blendFuncSeparate` / `_glBindVertexArray` 等），但**不再有** `_bindTexture` / `_activeTexture` / `TEXTURE_COUNT` / `_blendEquation` / `_viewport` 那一整套 |
| `net.minecraft.client.render.BufferRenderer` | **已删除**。`restoreMinecraftState()` 里那一行 `BufferRenderer.reset()` 需要找新的等价物或去掉 |
| `RenderSystem` 上的即时 GL 封装 | 整体换成 GPU 抽象：`RenderSystem.getDevice()` -> `GpuDevice`（实现是 `net.minecraft.client.gl.GlBackend`）、`CommandEncoder`、`RenderPass`、`RenderPipeline`、`GpuBuffer`、`GpuTexture` / `GpuTextureView` |
| 主 framebuffer 的 GL id | `net.minecraft.client.gl.Framebuffer` 还在，但只暴露 `getColorAttachment()` / `getColorAttachmentView()` 这类 `GpuTexture` 对象。**`GpuTexture` 没有公开的 `glId` 访问器** |
| GUI 是即时绘制 | **改成延迟批处理**：`DrawContext` 的调用只是往 `net.minecraft.client.gui.render.state.GuiRenderState` 里塞 `GuiElementRenderState`，帧末由 `net.minecraft.client.gui.render.GuiRenderer#render` 一次性提交 |

### 这意味着什么

**Skija 需要一个「当前绑定的 GL FBO」才能把画面合成回去**，而 1.21.11 把这个概念藏起来了。
同时「GUI 延迟批处理」意味着：在 `HudRenderCallback` / `ScreenEvents.afterRender` 这类钩子里，
原版 GUI **还没有真正画到任何 framebuffer 上**。如果照 1.20.1 的做法在钩子里直接合成，
Skia 的内容会被随后提交的原版 GUI 覆盖，或者顺序完全错乱。

### 建议的两条路线

**路线 A（优先尝试）：把 Skia 的输出当成一个 GUI 元素提交回去。**
`SkijaHost` 仍然画到自己的离屏 render target，但 `endFrame()` 不再「合成到当前 FBO」，
而是把离屏纹理包成 `GpuTextureView` / 自定义 `GuiElementRenderState`（或走
`Framebuffer#drawBlit(GpuTextureView)`），交给 `GuiRenderer` 在正确的时机画。
`RENDER-REWRITE-PLAN.md` 里当初选择「离屏 + 合成」而不是「直写 MC 的 FBO」，
第三条理由写的就是**为 1.21.11 的延迟批处理铺路**——现在就是兑现这条设计的时候，上层一行都不用改。

**路线 B（A 走不通时）：拿到裸 GL id，继续走 1.20.1 的合成方式。**
后端确定是 OpenGL（`GlBackend`）时，`GpuTexture` 的具体实现类里有 GL 纹理名。
它是包级私有的，需要给 `muoniumplayer.accesswidener` 加条目，或者用 mixin `@Accessor`。
代价是：绑死 OpenGL 后端，且要自己解决「原版 GUI 还没提交」的时序问题。

> 无论走哪条，**这个决定只影响 `SkijaHost` 一个文件**。先花时间把它想清楚，别急着动别的。

### `SkijaImages` 的纹理桥同样受影响

`SkijaImages` 靠 `Image.borrowTextureFrom(context, glId, w, h, ...)` 把 **Minecraft 的 GL 纹理**
（封面、头像、精灵图）借给 Skia 用。1.21.11 拿不到 `glId` 就走不通。

它的 `glId` 来自本项目自己的纹理链路（`core/rendering/texture/*`、`TextureUtil`、
`DynamicTexture`、`compat/gl/GL11` 的上传方法），**不是** MC 的 `TextureManager`。
所以还有一条更干净的 **路线 C**：让这条链路彻底绕开 MC 的纹理系统，
直接从解码后的像素字节建 Skia `Image`（`Image.makeRasterFromBytes` / 走 `Surface` 上传）。
这样 `SkijaImages` 一劳永逸地不再依赖任何 MC / GL 细节，后续版本升级也不用再碰。
**如果时间允许，路线 C 是最值得做的一步。**

---

## 4. 不许回退的红线

这些都是实机踩出来的坑，每一条都对应一个已修复的具体故障：

1. **`SkijaHost.GlSnapshot` 的「快照 + 原样还原」不许退回成「设成一套已知的好值」。**
   Minecraft 的状态管理器**带缓存**：想设的值和缓存相同就跳过 GL 调用。Skia 用裸 GL 在背后改了真实状态，
   缓存却以为没变。更要命的是它**压根不管**的那些状态：混合方程、`GL_STENCIL_TEST` 开关、
   **采样器对象**（`glBindSampler`，一旦绑上就完全覆盖纹理自身的过滤 / 包裹参数）、
   `GL_FRAMEBUFFER_SRGB`。实测症状是：**天空与云被画成错误色块、主菜单全景图糊掉、手持与掉落物贴图错乱**。
   还原成快照值之后，缓存自动重新变得诚实，一次「设反值再设回」的技巧都不需要。
   -> 1.21.11 上这套逻辑要照搬，只是读写状态的 API 换成新的（大概率是纯 LWJGL `glGet*` 加新 `GlStateManager`）。

2. **`resetPixelUnpackLayout()` 不许删。** MC 的 `NativeImage#upload` 会设
   `GL_UNPACK_ROW_LENGTH/SKIP_PIXELS/SKIP_ROWS` 且**用完不还原**。不清掉，Skia 的字形图集增量上传会错位，
   症状是「形状 / 渐变 / 裁剪全都正常，只有文字画出少数几个错误字形、多数字符缺失」。

3. **`SkijaGfx` 的矩阵栈与属性栈必须保持独立。** GL 里是两个栈，业务代码有交错 push/pop。

4. **`SkijaImages` 的尺寸登记：分配处用 `rememberSize`（权威、作废旧视图），绘制处用 `hintSize`（putIfAbsent）。**
   漏了分配处的登记，Skia 会按错误尺寸算 uv —— 封面只显示左上角一块。

5. **`SkijaImages` 必须容忍反向的 uv 区间。** 旧渲染层把 uv 交给顶点插值，`scale` 为负是合法输入
   （沿该轴翻转采样），而 Skia 的 `Rect.makeLTRB` 是断言式的会直接抛异常。业务代码里确实存在这种调用
   （`CappellaLyricRenderer.drawSheetCell` 的精灵图第二行起就是），崩过一次。
   `source()` / `normalize()` / `drawSource()` 三个 helper 就是干这个的，有单测钉住。

6. **`CFontRenderer` 的度量口径必须照抄，别「顺手修正」。** 关键几条：
   - `fontHeight = box * 2`，`box = max(ceil(-ascent) + ceil(descent))` over 主字体加全部回退字体
   - `getFontHeight() = (fontHeight - 8.5) * .5` —— 经验公式，原样保留
   - `baselineOffset = (box + mainAscent - mainDescent) * 0.5`；绘制基线 `y = (y - 2) + baselineOffset`
   - **单字步进量化到 0.5 GUI 单位**：`advance = ceil(rawAdvance * 2) * 0.5`。去掉它，图标会居中偏半像素
   - `FontEdging.ANTI_ALIAS`（透明图层上不能用 LCD 子像素）、`FontHinting.NONE`、`setSubpixel(true)`

7. **`SkijaText` 的 Typeface 登记表必须是 `IdentityHashMap`。** 按字体名字查会撞车：
   好几份图标字体的内部名都叫 `fontello`。

8. **`MuoniumPlayerExtension#isCallingFromMainThread()` 必须用 `MinecraftClient#isOnThread()`**，
   改成比较线程名会死锁。**`OpenAPIImpl` 的三个监听器列表必须是 `CopyOnWriteArrayList`。**

---

## 5. 渲染层之外还要动的地方

这些和 Skija 无关，是常规的版本适配。1.8.9 -> 1.20.1 那次的做法记在 `docs/PORTING-STATUS.md`，可以照着推。

- `compat/mc/`、`compat/lwjgl2/`（`Keyboard` / `Mouse` / `Display` / `Sys`）：兼容垫层，跟着新版 API 调整
- 事件钩子：`HudRenderCallback` 在新版 Fabric API 里已换成 `HudLayerRegistrationCallback` 一类，
  `ScreenEvents.afterRender` 的签名也变了（`DrawContext` 参数）
- `Screen#render` / `Screen#renderWithTooltip` 签名、`Identifier` 构造改成 `Identifier.of(...)`
- 键位注册、`accesswidener` 条目、`fabric.mod.json` 的 `depends` 版本
- `build.gradle`：`minecraft` / `yarn` / `loader` / `fabric-api` 版本；Java 版本（1.21.11 要 Java 21）

---

## 6. 环境与命令

| 项 | 值 |
| --- | --- |
| 源工程（本次移植的输入） | `C:\MUSIC\1.20.1 recode` |
| 1.20.1 原始基线（只读对照，出现视觉差异时 diff 用） | `C:\MUSIC\1.20.1\muoniumplayer` |
| 1.21.11 映射 jar（javap 查新 API 用） | `C:\Users\24914\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged\1.21.11-*\*.jar` |
| Skija 离线 jar（javap 查 Skija API 用） | `C:\MUSIC\1.21.11\_tools\skija\{skija-shared-0.143.17.jar, types-0.2.0.jar}` |
| JDK | `C:\Users\24914\.jdks\ms-21.0.12.1` |
| 代理 | `127.0.0.1:7890`（Maven 走阿里云；`build.gradle` 里 `repositories.all` 的改写逻辑**必须保留**） |

```powershell
$env:JAVA_HOME='C:/Users/24914/.jdks/ms-21.0.12.1'

# 编译（javac 常常只报第一个包级错误就停，要反复编译-修-编译）
.\gradlew.bat compileJava --console=plain 2>&1 |
  Select-String -Pattern 'error:|symbol:|location:'

# 静态测试（31 项，全绿才算过）
.\gradlew.bat test --console=plain 2>&1 | Select-String -Pattern 'PASSED|FAILED|BUILD'

# 实机（约 90-110 秒到标题界面）；用 Start-Process 后台跑，别占住终端
Start-Process -FilePath '.\gradlew.bat' -ArgumentList 'runClient','--console=plain' -WindowStyle Hidden -WorkingDirectory (Get-Location).Path -RedirectStandardOutput 'build\runclient.out.log' -RedirectStandardError 'build\runclient.err.log'

Select-String -Path build\runclient.out.log -Pattern 'Skija|GL_INVALID'   # GL_INVALID 必须为 0
```

### 自动化验证

`tools\mcdrive.ps1` 可以给窗口发按键 / 点击 / 输文字并截图：

```powershell
$p = Get-Process -Name java | Where-Object { $_.MainWindowTitle -like '*Minecraft*' } | Select-Object -First 1
powershell -File .\tools\mcdrive.ps1 -TargetPid $p.Id -Keys @('RSHIFT') -Shot 'build\shot.png'
```

`run/options.txt` 已设好：**右 Shift** 开播放器、**H** 开 HUD 编辑器、`pauseOnLostFocus:false`。
注意两点：本机 Chrome 约 1 秒后会抢焦点，脚本会报 `still not foreground`，重试即可；
`mcdrive.ps1` **必须保持纯 ASCII**（PowerShell 5.1 按 ANSI 读无 BOM 的 .ps1，中文注释会让它解析失败）。

### 静态测试都在钉什么

| 测试 | 钉住的东西 |
| --- | --- |
| `RenderLayerStaticTest` | 旧 OpenGL 实现文件与 GLSL 目录不得回归；业务代码不得引用旧门面；**渲染层里只有 SkijaHost 认识 Minecraft**；四角渐变 uniform 布局与 Java 侧 96 字节一致 |
| `SkijaGeometryStaticTest` | 反向 uv / 负宽高必须被规范化（含复现过崩溃的那组取值 97/94） |
| `SkijaTextStaticTest` | 字体覆盖、度量、CPU 光栅、HarfBuzz 整形四层都能画出连续墨迹 |
| `LyricParserProbeTest` / `DynamicIslandMathProbeTest` / `Lwjgl2KeycodeProbeTest` | 业务侧不变量，移植时**不许改测试去迁就代码** |

**移植过程中这些测试应当始终全绿。** `RenderLayerStaticTest` 的路径断言可能需要跟着目录调整，
但那几条不变量本身不许放宽。

---

## 7. 已知遗留

1. **Skija 原生库只打了 `windows-x64`**（`gradle.properties` 的 `skija_natives`，
   开发期只打本机平台保构建速度）。要发布给 Linux / macOS 用户得补另外三个平台，四平台合计约 46 MB。
2. **资源文件已经全部就位**，与 1.20.1 基线逐字节一致（18 个字体、11 张贴图、5 个 yrc 样本、lang、`IngameIME.dll`），
   移植时**不需要**再迁一遍。唯一少的是那 12 个已删除的 GLSL。
3. **未提交。** 从 `8d9c159` 之后的全部改动都还在工作区里。回退点 / diff 基准是 `c5503c2`
   （渲染层重写前的原样副本）。
4. 详细的重写方案与门面清单在 `docs/RENDER-REWRITE-PLAN.md`；
   1.8.9 -> 1.20.1 那次的移植记录在 `docs/PORTING-STATUS.md`。

---

## 8. 建议的推进顺序

1. 建好 1.21.11 的 Gradle 工程骨架，先把 `SkijaHost` 之外的编译错误清成 0
   （这些应该都是常规的 API 改名）
2. **单独攻 `SkijaHost`**：先用一个「画一个纯色矩形到屏幕中央」的最小用例，
   把「离屏 -> 合成 -> 状态还原」这条链在新 blaze3d 上打通。这一步不通，后面全是空谈
3. `SkijaImages` 的纹理桥（优先考虑第 3 节的路线 C，彻底摆脱 GL id）
4. 事件钩子与 `compat/` 垫层
5. `gradlew test` 全绿
6. 实机逐屏对照 1.20.1 版本截图：播放器主界面 / 歌词页 / 封面墙 / 灵动岛 / HUD 编辑器
7. 重点回归两个曾经的缺陷：**播放器四角圆角丢失**（圆角外套一层直角边框）、
   **「下一首播放」抽屉收起动画溢出播放器边界**。两者的根因都是旧模板 / 裁剪盒的失效窗口，
   在 Skija 版本上已随 `SkijaClip` 消失，移植后要确认没有以别的形式回来
