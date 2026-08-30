# 渲染层重写方案：用 Skija (Skia) 取代自写固定管线模拟层

> 工作目录 `C:\MUSIC\1.20.1 recode`。基线是 `C:\MUSIC\1.20.1\muoniumplayer` 的原样副本，
> 已作为 git 首个提交（`c5503c2`）固化，任何时候都可以 `git diff c5503c2` 看清改了什么。
>
> **范围铁律：只重写渲染层，不动业务逻辑。** 边界定义见第 2 节。

---

## 1. 可行性已验证（不是估计，是实测）

重写的前提是「Skia 能在 Fabric 环境里工作」。这件事已经用探针
（`src/main/java/com/muoniumplayer/render/skija/SkijaProbe.java`）逐级验证完毕，
全部通过，日志在 `build/runclient.out.log`：

| 级别 | 验证内容 | 结果 |
| --- | --- | --- |
| L1 | Skija 原生库能否在 Fabric 的 Knot 类加载器下加载 | **通过**，而且走的是 Skija **默认**路径，不需要我们自己解压 |
| L2 | 从本 mod jar 资源建 `Typeface` | **通过**，`pf_normal.ttf` 10,757,580 字节 / 42,199 个字形 |
| L3 | `TextLine` 度量 | **通过**，样本「网易云音乐 MuoniumPlayer 1.3.0」width=242.0 ascent=-16.96 descent=5.44 |
| L4 | HarfBuzz + ICU 整形 | **通过**（说明 `icudtl.dat` 到位） |
| L5 | CPU 光栅 + 像素读回 | **通过**，32768 字节里 6444 非零 |
| L6 | `DirectContext.makeGL()` 包住 Minecraft 的 GL 上下文 | **通过** |
| L7 | 把 MC 当前绑定的 FBO 包成 Skia `Surface` 并画圆角矩形 | **通过**，FBO=1 viewport=854x480 |
| **L7b** | **从 MC 的 FBO 回读像素验证** | **通过，读到 `RGBA=(255,0,255,255)`，与画上去的不透明品红精确一致** |

L7b 是关键：它把「调用没报错」变成了「像素确实落在 Minecraft 的画面上」。

环境：Windows 11 / amd64 / Java 17.0.12 / LWJGL 3.3.2-snapshot / Skija 0.143.17。

### 1.1 已引入的依赖

`gradle.properties` 新增 `skija_natives=windows-x64`。`build.gradle` 新增：

```groovy
implementation include('io.github.humbleui:skija-shared:0.143.17')
implementation include('io.github.humbleui:types:0.2.0')
implementation include("io.github.humbleui:skija-" + project.skija_natives + ":0.143.17")
compileOnly 'org.jetbrains:annotations:20.1.0'
```

体积：`skija-shared` 0.39 MB + `types` 极小 + 平台原生库
（windows-x64 10.6 MB / linux-x64 13.3 MB / macos-x64 11.3 MB / macos-arm64 10.8 MB）。
**开发期只打本机平台**，发布前把四个都加上（约 46 MB）。

### 1.2 保留的保险机制

`SkijaNatives` 实现了两条加载路径：默认路径失败时，自己从 classpath 解出
`skija.dll` + `icudtl.dat` + `skija.version` 到 `<user.dir>/muonium-skija/<version>/`
再用 `-Dskija.library.path` 指给 Skija。实测默认路径可用，但这条保险要留着——
打成正式 jar（而不是 Loom 的开发类路径）之后 `ResourceFinder` 的行为可能变化，
到时候不用重新研究，日志里的「方式=」会直接说明走了哪条。

---

## 2. 范围边界：什么动，什么不动

### 2.1 判定规则

一个文件属于「渲染层」当且仅当它满足以下任一条：

1. 引用 `com.muoniumplayer.compat.gl.*` / `org.lwjgl.opengl.*` / `com.mojang.blaze3d.*`
2. 位于 `core/rendering/` 下且职责是「怎么画」而不是「画什么」
3. 是 `today/opai/api/impl/` 下的渲染实现（`GLStateManagerImpl` / `RenderUtilImpl` / `VanillaFont`）
4. 是 `compat/` 下的图形替身层

**不属于渲染层、绝对不动**：音源与登录（`core/ncm/`）、播放引擎、歌词解析、
歌单同步、设置持久化（`core/settings/`）、网络与并发工具（`core/utils/`）、
`repackage/` 下的第三方解码器、以及所有屏幕/组件里的**布局与状态机逻辑**。

### 2.2 关键区分：同一个文件里既有布局又有绘制

`NCMScreen` / `GuiHudEditorPro` / `DownloadDynamicIsland` 这类文件里，
「面板在哪、动画进度是多少、点击命中哪一项」是业务逻辑；
「用什么 GL 调用把这个矩形画出来」是渲染。**只改后者。**

具体做法：这些文件里的 `GL11.glBegin` / `glPushMatrix` / `Shaders.XX.draw` 调用
换成门面调用或 Skia 调用，但**坐标计算、进度插值、命中测试的表达式一个字符都不改**。
改完用 `git diff` 逐行核对：任何一处改了数值表达式都算越界。

---

## 3. 核心策略：冻结门面，替换实现

现有代码有一层设计得相当干净的绘制门面。**业务代码只通过这些门面画东西**，
所以只要门面的方法签名与语义不变，绝大多数文件零改动。

### 3.1 门面冻结清单

| 门面 | 位置 | 方法数 | 调用/实现方 | 处理 |
| --- | --- | --- | --- | --- |
| `SharedRenderingConstants` | `core/interfaces/` | 29 个 default | 16 文件 | **签名冻结**，方法体改到 Skia |
| `RenderSystem` | `core/rendering/rendersystem/` | 33 个 static | 45 文件 | **签名冻结**，方法体改到 Skia |
| `GLStateManager`（接口） | `today/opai/api/interfaces/render/` | 40 | 46 文件 | **接口冻结**，`GLStateManagerImpl` 改到 Skia |
| `Rect` | `core/rendering/` | 2 个 static | 20 文件 | **签名冻结** |
| `Image` | `core/rendering/` | 21 个 static | 8 文件 | **签名冻结**，方法体改到 Skia |
| `CFontRenderer` | `core/rendering/font/` | ~30 | 55 文件 | **签名冻结**，内部换 Skija 文字后端 |
| `StencilClipManager` | `core/rendering/` | 13 个 static | 16 文件 | **签名冻结**，改到 `canvas.clipRRect/clipPath` |
| `ScissorClipManager` | `core/rendering/` | 2 个 static | 10 文件 | **签名冻结**，改到 `canvas.clipRect` |
| `ITextureObject` / `AbstractTexture` | `core/rendering/texture/` | 5 / 14 | 若干 | 语义改为持有 Skia `Image` |
| `Framebuffer` | `core/rendering/` | ~25 | 11 文件 | **大幅收窄**，改到 `canvas.saveLayer` |

「签名冻结」的含义：**方法名、参数列表、参数顺序、返回类型、以及可观察语义完全不变。**
可以新增重载（例如给 `StencilClipManager` 加一个直接吃圆角矩形的 `beginClip(RRect)`），
但不许删除或改动已有签名。

### 3.2 `SharedRenderingConstants` → Skia 的逐条映射

这是最重要的一张表——29 个方法覆盖了播放器界面几乎所有的「形状」绘制。

| 现有方法 | 现在的实现 | Skia 实现 |
| --- | --- | --- |
| `roundedRect(x,y,w,h,radius,color)` 等 6 个重载 | `RQ_SHADER` + `rq.frag` | `canvas.drawRRect(RRect.makeXYWH(x,y,w,h,r), paint)` |
| `roundedOutline(...,thickness,Color)` ×2 | `ROQ_SHADER` + `roq.glsl` | `drawRRect` + `PaintMode.STROKE` + `setStrokeWidth` |
| `roundedOutlineGradient(...4 角颜色)` | `ROGQ_SHADER` + `rogq.frag` | `drawRRect` STROKE + 四角双线性渐变 `Shader`（见 6.2） |
| `roundedRectTextured(...)` ×5 | `RQT_SHADER` + `rqt.frag` | `paint.setShader(image.makeShader(...))` + `drawRRect` |
| `roundedRectGradientHorizontal/Vertical` | `RQG_SHADER` + `rqg.frag` | `Shader.makeLinearGradient` + `drawRRect` |
| `drawGradientCornerLR/RL` | `RQG_SHADER` 四角插值 | 四角双线性渐变 `Shader`（见 6.2） |
| `evenAdd(Color,Color)` | 纯计算 | **不动**（不是渲染，是颜色算术） |
| `scaleAtPos / rotateAtPos` | `GLStateManager` 三次变换 | `canvas.translate/scale/rotate`（语义完全一致） |
| `getWidth/getHeight` | `RenderSystem` | 不动 |
| `hexColor` ×4 / `reAlpha` | `RGBA` 纯计算 | **不动** |
| `isHovered` | 纯计算 | **不动** |

29 个方法里有 8 个是纯计算（颜色算术、命中测试），**完全不用碰**。真正需要重写的是 21 个绘制方法。

### 3.3 `GLStateManager` → Skia 的映射策略

40 个方法，被 46 个文件用到，是覆盖面最广的门面。它是固定管线形态的，映射分三类：

**A 类 — 直接对应 `canvas` 状态（必须精确实现）**

```
pushMatrix()  popMatrix()          -> canvas.save() / canvas.restore()
pushAttrib()  popAttrib()          -> 与 save/restore 配对 + 我们自己的 paint 状态栈
translate(x,y,z)                   -> canvas.translate(x, y)      z 忽略（2D UI）
scale(x,y,z)                       -> canvas.scale(x, y)
rotate(angle,x,y,z)                -> canvas.rotate(angle)        仅绕 Z 有效，其余记录并告警
color(r,g,b,a)  resetColor()       -> 当前颜色状态（见下）
```

`color()` 是**有状态的**：业务代码习惯先 `color(...)` 再画，颜色要作用于后续绘制，
包括对纹理做调制。Skia 侧用一个「当前颜色」栈：形状绘制时写进 `Paint.setColor`，
贴图绘制时转成 `ColorFilter.makeBlend(color, BlendMode.MODULATE)` 挂到 `Paint` 上。
**这一条是整个映射里最容易出错的地方**，必须单独写测试。

**B 类 — 在 Skia 下无意义，实现为受控 no-op**

```
enableBlend / disableBlend             Skia 的 2D 绘制始终按 Paint 的 BlendMode 混合
enableAlpha / disableAlpha             alpha test 由 Paint / discard 语义取代
alphaFunc(func, ref)                   同上
enableTexture2D / disableTexture2D     由「这次画的是形状还是图片」决定，不是全局开关
enableDepth / disableDepth             2D UI 不用深度
enableCull / disableCull               2D UI 不用背面剔除
enableLighting / disableLighting       从来不需要
enableColorMaterial / ...
shadeModel(mode)
depthMask / colorMask                  离屏层由 saveLayer 管
clearDepth
```

no-op 不是「空实现就完事」：要在实现里注释清楚**为什么**无意义，
否则后来人会以为是没写完。个别方法（如 `blendFunc`）要把常用参数组合映射成
`BlendMode`，不能一律 no-op —— 详见 6.3。

**C 类 — 真实资源操作（要重新落地）**

```
bindTexture(id) / deleteTexture(id) / generateTexture() / setActiveTexture(unit)
callList(list)                    显示列表：Skia 侧用 Picture 或直接取消缓存
clear(mask) / clearColor(...)     -> canvas.clear(argb)
viewport(x,y,w,h)                 -> 由宿主层管，业务侧调用记录并忽略
matrixMode / loadIdentity / ortho  -> 由宿主层建立 GUI 坐标系，业务侧调用大多可忽略
```

**纹理 id 语义的转换是 C 类的核心难点**：现在业务代码拿到的是 GL 纹理整数 id，
Skia 要的是 `Image` 对象。方案见 6.4。

---

## 4. 新增模块（宿主层）

新代码全部放在 `com.muoniumplayer.render.skija` 下，与将被删除的 `compat` / `core.rendering` 隔开。

```
com/muoniumplayer/render/skija/
  SkijaNatives.java      已完成。原生库加载（双路径 + ICU 数据）
  SkijaProbe.java        已完成。可行性探针，重写完成后删除
  SkijaHost.java         待写。DirectContext 单例 + 离屏目标 + 与 MC 的 GL 状态隔离
  SkijaFrame.java        待写。每帧的 begin/end，暴露当前 Canvas
  SkijaPaintStack.java   待写。当前颜色 / BlendMode / 变换栈，服务 GLStateManager 映射
  SkijaTextEngine.java   待写。Typeface / Font / Shaper / TextLine 缓存
  SkijaImageCache.java   待写。GL 纹理 id <-> Skia Image 的双向映射
  SkijaEffects.java      待写。四角双线性渐变等需要 SkSL 的效果
```

### 4.1 `SkijaHost` 的职责与边界

这是唯一与 Minecraft 打交道的类，将来换 MC 版本只需要改它：

1. 持有 `DirectContext` 单例（进程级，不要每帧建）
2. 提供**自建离屏 FBO**：颜色纹理 + `GL_DEPTH24_STENCIL8`。
   为什么不直接画到 MC 的主 framebuffer（探针证明可以）：
   - MC 主 framebuffer 在 1.20.1 默认**没有 stencil 附件**，而 Skia 的复杂裁剪需要 stencil
   - 画到自己的目标上，Skia 与 MC 的状态互不干扰，只在合成那一刻交接一次
   - 1.21.11 的 GUI 是延迟批处理的，必须有一个「作为 GUI 元素提交回去」的接口；
     现在就按离屏目标设计，将来移植时这一层不用改
3. 帧边界的 GL 状态隔离：
   - 进入 Skia 之前 `context.resetAll()`（告诉 Skia 外部改过 GL 状态）
   - 离开 Skia 之后恢复 MC 期望的 FBO / viewport，并让 MC 的状态缓存失效
4. 尺寸变化时重建离屏目标

### 4.2 坐标系约定（一次定清，后面全按它写）

- Skia canvas 使用 **GUI 缩放坐标**（等于现在 `RenderSystem.getWidth()/getHeight()` 的值），
  原点左上、y 向下。这与现有业务代码的坐标系**完全一致**，所以业务侧的坐标表达式不用动。
- 离屏 FBO 使用**帧缓冲像素尺寸**。两者的比例是 `Window.getScaleFactor()`。
  在 `SkijaFrame` 开头对 canvas 做一次 `scale(scaleFactor, scaleFactor)` 抹平差异。
- 合成回 MC 时按 `SurfaceOrigin.BOTTOM_LEFT` 处理 v 翻转（探针里已经验证过这个约定）。

---

## 5. 删除清单（重写完成后才删，不要一上来就删）

删除动作放在最后一个阶段。在那之前旧代码保留、只是不再被调用——这样任何一步出问题
都能立刻对照旧实现。

| 路径 | 文件数 | 理由 |
| --- | --- | --- |
| `compat/gl/`（GL11 / GL13 / GL14 / GL20 / internal/FixedFunction） | 5 | 固定管线模拟层整体被 Skia 取代。`FixedFunction` 单文件 1124 行 |
| `compat/lwjgl2/glu/`（GLU / Project） | 2 | 只服务 `ScissorClipManager` 的手工投影，Skia 自己管变换 |
| `core/rendering/shader/`（含 `impl/` 12 个 + `uniform/` 7 个 + 基础设施 5 个） | 24 | 圆角/渐变/模糊/混合全部由 Skia 原生提供 |
| `src/main/resources/muonium/shaders/` | 12 个 GLSL | 同上。`deconverge` / `vf_fadeout` 改写成 SkSL 后也不再需要文件形式 |
| `core/rendering/font/`（CFontRenderer / GlyphGenerator / GlyphInk / GlyphInkMetrics / TextureAtlas / Glyph / FontKerning） | 7 | AWT 逐字形光栅 + 自建图集被 Skia 文字栈取代。**注意 `CFontRenderer` 这个类名与签名要保留**，只是实现搬到 Skija（见 3.1） |
| `core/rendering/StencilClipManager.java` | 1 | `canvas.clipRRect`（抗锯齿）取代手写 stencil 递增 |
| `core/rendering/ScissorClipManager.java` | 1 | `canvas.clipRect` 取代矩阵回读 + gluProject |
| `core/rendering/Framebuffer.java` / `OpenGlHelper.java` / `TextureUtil.java` / `GaussianKernel.java` / `BooleanState.java` | 5 | 离屏层交给 `canvas.saveLayer`；FBO 只在 `SkijaHost` 里出现一次 |
| `core/rendering/GaussianKernel.java` | 1 | 模糊核由 Skia 计算 |
| `src/test/java/.../ShaderPortProbeTest.java` | 1 | 它钉的是 GLSL 不变量，GLSL 删了它就失去对象。**要换成钉 Skia 行为的新测试，不是简单删掉** |

预计删除约 **60 个文件 / 250 KB 源码**。

### 5.1 不要删的（容易误判）

- `core/rendering/animation/`（Animation / Easing / Interpolations / spring/）—— 是**动画数学**，不是渲染
- `core/rendering/RGBA.java` —— 颜色算术
- `core/rendering/DynamicIslandMath.java` / `DynamicIslandLayoutCalculator.java` —— 布局计算
- `core/rendering/ChatAllowedCharacters.java` —— 输入过滤
- `core/rendering/*Icons.java`（FontelloIcons / HudSettingsIcons / IslandStatusIcons / IslandVolumeIcons / MusicBrandIcons / PlayerQueueIcons）—— 图标码位常量表
- `compat/lwjgl2/Keyboard.java` / `Mouse.java` / `Display.java` / `Sys.java` —— 输入与窗口，不是绘制
- `compat/mc/` —— GUI 生命周期桥接，不是绘制（`FontRenderer` 除外，它是原版文字包装）

---

## 6. 已知难点与决策

### 6.1 `glCallList` 显示列表缓存（`CFontRenderer.StringRenderCall`）

现状：`CFontRenderer` 把每个字符串按颜色段编译成若干 GL 显示列表，
后续帧直接 `glCallList` 回放。这是它的性能核心。

Skia 侧的对应物有两个：
- `TextBlob`：整形结果的缓存。**首选** —— 缓存 `String -> TextBlob`，绘制时 `drawTextBlob`
- `Picture`：录制任意绘制命令流回放。更接近显示列表，但对纯文字是过度设计

**决策：用 `TextBlob` 缓存**，键是「字符串 + 字体 + 字号」。`§` 颜色码分段的处理见 6.5。

### 6.2 四角双线性渐变（`RQG_SHADER` / `ROGQ_SHADER`）

`rqg.frag` 对矩形四个角的颜色做双线性插值，Skia 的 `makeLinearGradient` 只能做单方向。
用两个线性渐变叠加不等价（会在对角出现错误的亮带，这正是 `evenAdd` 注释里警告的问题）。

**决策：写一个 SkSL `RuntimeEffect`**，把 `rqg.frag` 的插值逻辑原样搬过去。
SkSL 语法与 GLSL 接近，四角插值只有几行。这也顺便验证了「自定义效果不用放弃」这条退路。

放在 `SkijaEffects` 里，作为**唯一**的自定义着色器。如果它出问题，退化方案是
「单方向渐变 + 正确的对角色」，视觉有差异但不崩。

### 6.3 `blendFunc` / `tryBlendFuncSeparate` 的映射

不能一律 no-op：`RenderSystem.drawRect` 显式设了
`(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ZERO)`，这是标准 `SRC_OVER`。
业务代码里也有用加法混合做发光的地方。

**决策**：维护一张「常见 GL 混合因子组合 → `BlendMode`」的映射表，覆盖
`SRC_OVER` / `SRC` / `PLUS`(加法) / `MODULATE` / `DST_OVER` 等；
表里没有的组合退到 `SRC_OVER` 并**打一次日志**（不要静默，否则视觉差异无从追查）。

### 6.4 GL 纹理 id ↔ Skia `Image`

`Image.draw(int textureId, ...)` 和 `GLStateManager.bindTexture(int)` 让业务代码
直接握着 GL 纹理 id。Skia 需要 `Image` 对象。

**决策：`SkijaImageCache` 做双向登记。**
- 纹理的创建方（`DynamicTexture` / `AnimatedCoverTexture` / `AccountAvatar` / `Textures`）
  改成把像素数据交给 Skia，得到 `Image`，同时分配一个**本层自己的整数句柄**冒充「纹理 id」
- `bindTexture(handle)` 只是记录「下一次贴图用哪张 `Image`」
- 真正来自 Minecraft 的纹理（原版方块图集之类）—— 检查是否有业务代码依赖它们；
  如果有，用 `Image` 包裹已存在的 GL 纹理（Skia 支持从后端纹理建 `Image`）

**这一条是整个重写里唯一可能需要改动业务代码签名的地方。** 优先用「假 id」方案避免。

### 6.5 `§` 颜色码分段与整形的冲突

`CFontRenderer.compile()` 遇到 `§` 就切一段，每段单独一个颜色。
但整形是按整行做的：按段分别整形会丢掉跨段的字距调整。

**决策：整行整形一次，再按 cluster 边界切颜色。**
Skia 的 `TextBlob` 不直接支持「一个 blob 多种颜色」，所以实现为：
整行 `shapeLine` 得到 `TextLine` → 用 `getCoordAtOffset(charIndex)` 拿到每段的起止 x →
每段用 `canvas.save(); clipRect(段的x范围); drawTextLine(整行, 0, baseline, 段颜色paint); restore();`。
这样字距完全正确，代价是每段一次裁剪绘制。段数通常是 1-3，可以接受。

### 6.6 图标字体不能走 fallback

13 个图标字体把图标映射到 PUA 码位。如果用带 `FontMgr` 的 `Shaper.make()`，
找不到的 PUA 字会被系统字体替换成豆腐块。

**决策**：`SkijaTextEngine` 里区分两类字体。
- 正文字体：`Shaper.makeShapeDontWrapOrReorder()`，允许 fallback 链
- 图标字体：**不整形**，直接 `Typeface.getStringGlyphs()` + `TextBlobBuilder` 按码位取字形

### 6.7 `Framebuffer` 的收窄

现在 11 个文件用到它，用途有三种：
1. `BloomShader` / `GaussianBlurShader` 的离屏 pass → 换成 `canvas.saveLayer(rect, paintWithImageFilter)`
2. `StencilClipManager` 把 stencil 状态存在 `Framebuffer.currentlyBinding` 上 → 随 stencil 方案一起删
3. `NCMScreen` / `MusicLyricsPanel` / `KaraokeTextCompositor` / `ScrollText` 的自定义合成 → 逐个改成 `saveLayer`

**决策**：`Framebuffer` 整类删除，不保留门面。它的 11 个使用点都在渲染层内部，
没有业务代码直接依赖，所以不需要冻结签名。

### 6.8 视觉不会像素级一致

Skia 的抗锯齿、gamma、圆角几何与现在手写 shader 的输出**必然有差异**。
这不是 bug，但必须被度量而不是被忽略。

**决策：建立截图对照。** 在删除旧实现之前，用基线工程
（`C:\MUSIC\1.20.1\muoniumplayer`，未改动）截一组参考图，
recode 侧截同样的图，逐屏人工比对。对照清单见第 8 节。

---

## 7. 实施顺序

每个阶段结束都要 `gradlew compileJava` 通过 + git 提交。
**不满足验收标准不进下一阶段。**

### S1 — 宿主层（不改任何现有行为）

写 `SkijaHost` / `SkijaFrame` / `SkijaPaintStack`。
挂进 `MuoniumPlayerMod` 的两个渲染回调，但**只开一个帧边界，什么都不画**。

**验收**：客户端启动正常，帧率无明显变化，日志无 GL 错误；
在 `SkijaFrame` 里临时画一个测试图形能出现在正确位置（GUI 坐标系验证），验证后移除。

### S2 — 形状门面（`SharedRenderingConstants` + `Rect` + `RenderSystem` 的绘制部分）

21 个绘制方法改到 Skia。这一步之后，**播放器界面的所有矩形/圆角/渐变/描边都由 Skia 画**，
文字和贴图仍走旧路径（两套并存，刻意的）。

**验收**：打开播放器界面，圆角面板、按钮、渐变背景、描边正常；
和基线截图对比，几何位置一致（抗锯齿差异可接受）。
**这一步同时验证第 10 节的圆角缺陷是否消失。**

### S3 — `GLStateManagerImpl` 映射

40 个方法按 3.3 的 A/B/C 三类落地。重点是 `color()` 的状态语义和 `push/pop` 配对。

**验收**：46 个使用方所在的界面全部走查一遍；
特别检查带 `scaleAtPos` / `rotateAtPos` 的动画（灵动岛、Coverflow）变换正确。

### S4 — 贴图门面（`Image` + `texture/` + `SkijaImageCache`）

21 个 `Image.draw*` 重载 + 纹理类改到 Skia `Image`。

**验收**：专辑封面、账号头像、动态封面（ffmpeg 帧序列）、图标正常；
封面**不是黑块**（基线的 R3 故障模式）；翻转/旋转变体（`FlippedX` / `FlippedY` / `Rotate90`）方向正确。

### S5 — 文字后端（`CFontRenderer` 内部换 Skija）

签名冻结，实现全换。含 6.1 的 `TextBlob` 缓存、6.5 的颜色码分段、6.6 的图标字体分流。

**验收**：所有界面文字位置与基线**接近**（字距会因为真整形而变化，这是预期的改进）；
`getStringWidth` / `trim` / `fitWidth` 的结果自洽（不出现文字溢出容器）；
逐字歌词、滚动文字、搜索框光标命中正常；13 个图标字体无豆腐块。

### S6 — 裁剪（`StencilClipManager` + `ScissorClipManager`）

改到 `canvas.clipRRect` / `clipRect`。

**验收**：**第 10 节两个已知缺陷在这一步检验**——圆角是否完整、抽屉收起动画是否还溢出。
侧栏滚动裁剪、列表裁剪、全屏歌词遮罩正常。

### S7 — 13 个直接调 GL 的业务渲染文件

按调用密度从小到大做（先易后难，积累模式）：

```
FullscreenLyricStage.java          1 处
CappellaLyricRenderer.java         2 处
MuoniumPlayerMod.java              3 处
ScrollText.java                    4 处
MusicToast.java                    4 处
CoverflowOverlay.java              5 处   <- 3D 透视，用 canvas.concat(Matrix44)
MusicLyricsPanel.java              8 处
KaraokeTextCompositor.java        10 处
NCMScreen.java                    31 处
ThemeTransitionRenderer.java      35 处
DownloadDynamicIsland.java        38 处   <- 138 KB 单文件，最大风险
GuiHudEditor.java                 53 处
GuiHudEditorPro.java              56 处
```

**验收**：每个文件改完立刻打开对应界面走查，不要攒着一起测。
`git diff` 必须显示只改了绘制调用，坐标/进度/命中测试的表达式未变。

### S8 — 删除旧实现 + 换测试

按第 5 节清单删。`ShaderPortProbeTest` 换成钉 Skia 行为的新测试
（例如：圆角矩形的四个角像素必须是透明的、文字宽度必须大于 0、颜色码分段数正确）。

**验收**：`gradlew clean build test` 一次过；`git diff c5503c2 --stat` 的改动范围
落在渲染层内；第 8 节的截图对照清单全部走查完毕。

### S9 — 性能与收尾

量帧率（基线从来没量过，S1 时先量一次基线值）；补齐四个平台的原生库；
删除 `SkijaProbe`；写变更说明。

---

## 8. 截图对照清单（S2 起每阶段都用）

基线截图从未改动的 `C:\MUSIC\1.20.1\muoniumplayer` 取，工具是 `tools\mcdrive.ps1`。
**窗口要开大**（854x480 + GUI Scale 2 只有 427x240 逻辑分辨率，面板会被边缘裁到，
那是布局拥挤不是渲染错误）。

| # | 画面 | 关注点 |
| --- | --- | --- |
| 1 | 播放器主界面（`N` 键） | 四角圆角、面板描边、侧栏、底部播放条 |
| 2 | 「下一首播放」抽屉展开 / 收起中间态 | 抽屉是否溢出面板边界（缺陷 10.2） |
| 3 | 搜索界面 + 输入框聚焦 | 光标位置、文字裁剪、占位符 |
| 4 | 歌单列表滚动到中间 | 列表裁剪边界、封面圆角 |
| 5 | 逐字歌词播放中 | 逐字高亮进度、字距、行居中 |
| 6 | 全屏歌词舞台 | 背景模糊、遮罩渐变 |
| 7 | Coverflow 封面墙 | 3D 透视、反射、层序 |
| 8 | 灵动岛（各状态） | 圆角、阴影、展开动画 |
| 9 | HUD 编辑器 / HUD 编辑器 Pro | 网格、拖拽框、缩放手柄 |
| 10 | 游戏内 HUD（歌曲信息卡片 + 歌词） | 与原版 HUD 的层序、是否污染原版文字 |

---

## 9. 红线（从基线工程继承，不许回退）

1. **主线程判定必须用 `MinecraftClient#isOnThread()`**（`MuoniumPlayerExtension#isCallingFromMainThread`）。
   改成比线程名会让 `runOnMainThreadBlocking` 自我死锁，打开播放器整个客户端卡死。
2. **`OpenAPIImpl` 的三个监听器列表必须是 `CopyOnWriteArrayList`**，否则首个 tick 抛 `ConcurrentModificationException`。
3. **不要重构业务层。** 见第 2 节。
4. 原有的 `GL_UNPACK_ROW_LENGTH` / 纹理 id 置 -1 这两条修复随着 GL 代码删除自然消失，
   但要确认新的贴图路径没有引入等价问题（封面花屏 / 黑块是它们的表现）。

---

## 10. 两个已知缺陷（基线上就存在）

重写不是为了修它们，但重写路径正好覆盖它们的成因，S6 要专门检验。

### 10.1 播放器主界面四角圆角丢失，圆角外套一层直角边框

基线上的调查结论：着色器正常、stencil 附件安装成功
（`attachType=0x8D41 GL_RENDERBUFFER, stencilBits=8, err=0`）；
逐像素查过截图，**左上/左下/右下三个角都是带抗锯齿的真圆角**，
唯一没被观察到的是右上角（历史截图那里都被新手提示浮层挡住）。
最强线索是 `StencilClipManager.restoreActiveClip()` 的 javadoc 精确描述了这个症状：
辅助 framebuffer 的 pass 关掉 stencil test 后回到主 framebuffer，
必须在下一个 quad 之前恢复父级裁剪。

**重写后的预期**：`canvas.clipRRect` 是抗锯齿的原生实现，不存在「pass 之间丢失裁剪状态」
这个失败模式。**这个缺陷大概率消失。**

### 10.2 「下一首播放」抽屉收起动画溢出播放器界面

机理已确认：`PlayQueuePanel.onRender` 的注释和代码说明
「收起时整块滑到内容区下沿之外，由内容区的裁剪吃掉」，
即**动画在设计上就依赖裁剪**。看得见溢出就是裁剪没生效，不是动画参数算错。
三层保护里 `PlayQueuePanel$List.beforeRenderChildren` 的 `ScissorClipManager.begin(...)` 最可疑——
只有它依赖回读 GL 矩阵。

**重写后的预期**：`canvas.clipRect` 直接用 canvas 自己的变换矩阵，不存在矩阵回读。
**这个缺陷大概率消失。**

---

## 11. 风险与回退

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| `DownloadDynamicIsland.java` 138 KB / 38 处 GL | 单点最大工作量 | 放在 S7 靠后；先在小文件上把模式固定下来 |
| 视觉差异被当成 bug 反复返工 | 时间黑洞 | 第 8 节截图对照先建立，差异先记录再判断 |
| `color()` 状态语义映射错 | 大面积颜色错误，但不崩 | S3 单独写测试；每个使用方界面走查 |
| 纹理 id 语义（6.4） | 可能被迫改业务签名 | 优先「假 id」方案 |
| 性能未知 | 可能掉帧 | S1 先量基线；`saveLayer` 用量要看着 |
| 原生库体积 46 MB | 分发体积 | 开发期只打本机平台；发布时再全平台 |
| 打成正式 jar 后原生库加载行为变化 | 发布版起不来 | `SkijaNatives` 的第二条路径已备好；发布前必须测一次真实安装 |

**回退点**：git 的 `c5503c2` 是未改动基线。每个阶段一个提交，任何阶段出问题都能单独回退。
基线工程 `C:\MUSIC\1.20.1\muoniumplayer` 全程不动，随时可对照运行。
