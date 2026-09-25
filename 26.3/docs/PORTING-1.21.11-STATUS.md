# MuoniumPlayer 1.21.11 Fabric 移植 — 完成状态

工程目录：`C:\MUSIC\1.21.11 recode`
基线：`C:\MUSIC\1.20.1 recode`（Skija 渲染层版，**唯一参考来源**）
本文覆盖 `docs/PORTING-1.21.11-SKIJA.md` 的计划部分；那份是交接说明，这份是实际落地记录。

---

## 一、当前状态

| 项 | 结果 |
| --- | --- |
| `gradlew compileJava` | 通过 |
| `gradlew test` | **39 项全绿** |
| `gradlew build`（含 remapJar / sourcesJar） | 通过，产物 `build/libs/MuoniumPlayer-1.3.0.jar` |
| 实机验证 | **已过**，见第五节 |

本工程不产生 refmap——Loom 1.17 走的是「直接改写注解字符串」这条路，属正常；
可以反编译产物里的 mixin class 确认 `@Mixin`/`@Inject` 的目标已变成 intermediary 名。

移植过程中踩了两个「不报错但画面不对」的坑，都已修复并写进第四节：
帧末合成挂错了地方（界面完全不出现），以及 GL 纹理绑定的还原被 `GlStateManager`
的缓存整段跳过（原版个别汉字变彩色噪点）。这两条是本次移植最值得先读的部分。

---

## 二、构建配置改动

| 文件 | 改动 |
| --- | --- |
| `gradle.properties` | `minecraft_version=1.21.11`、`yarn_mappings=1.21.11+build.6`、`fabric_version=0.141.6+1.21.11`（`loader_version=0.19.3` 不变） |
| `build.gradle` | `targetJavaVersion` 17 → 21；`loom.runs.client` 加了 `MU_QUICKPLAY` 环境变量 → `--quickPlaySingleplayer` |
| `fabric.mod.json` | description 改 1.21.11、`java` 要求改 `>=21` |
| `muonium.mixins.json` | `compatibilityLevel: JAVA_21`、`client` 数组加入 `GuiRendererMixin` |
| `muoniumplayer.accesswidener` | **清空全部条目**（只留 header 与理由注释） |

`accesswidener` 必须保持空：1.20.1 时放开的 `Framebuffer.depthAttachment/colorAttachment`
在 1.21.11 已从 `int` 变成 `GpuTexture`，按旧签名（`I`）放开会让 AW 解析直接失败、启动期崩。
Skija 渲染层也不再需要动 MC 的附件。Loom 会打印一句
`Cannot remap modifiers because it does not exist in any of the targets []`，无条目时属无害提示。
`Port121StaticTest#accessWidener保持空` 钉住了这条。

代理与仓库配置**原样保留**：`gradle.properties` 里的 `127.0.0.1:7890` 与 nonProxyHosts 白名单、
`build.gradle` 里把所有 Maven Central 改写到阿里云的 `repositories.all(retargetCentral)` 都不能删
（本机 repo.maven.apache.org 直连超时、走代理握手被中断）。

---

## 三、1.21.11 API 差异（全部经 javap 实测，未凭记忆）

实测用的 jar：

```
C:\Users\24914\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged\1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2\minecraft-merged-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar
```

### 3.1 blaze3d / GL

| 1.20.1 | 1.21.11 |
| --- | --- |
| `com.mojang.blaze3d.platform.GlStateManager` | **`com.mojang.blaze3d.opengl.GlStateManager`** |
| `_texParameter(int, int, float)` | 只剩 int 重载 |
| `TEXTURE_COUNT` / `_blendEquation` / `_stencil*` / `_genTextures(int[])` / `upload` | 全部删除 |
| `net.minecraft.client.render.BufferRenderer` | **整个类删除**，顶点缓冲绑定由 `GlCommandEncoder` / `RenderPass` 内部管 |
| `Framebuffer.colorAttachment`（int 字段） | 只暴露 `getColorAttachment()` / `getColorAttachmentView()`（`GpuTexture` / `GpuTextureView`）；**没有** public 的 glId 访问器 |

仍然存在：`_viewport` / `_pixelStore` / `_activeTexture` / `_bindTexture` / `_genTexture` /
`_deleteTexture` / `_blendFuncSeparate` / `_colorMask` / `_depthFunc` / `_depthMask` /
`_glBindVertexArray` / `_glBindBuffer` / `_glUseProgram` 与各 enable/disable。

`compat/gl/GL11.glTexParameterf` 改成裸调 `org.lwjgl.opengl.GL11.glTexParameterf`：
float 重载已删，而纹理参数不在 MC 的状态缓存里，绕过不会制造缓存分歧。

### 3.2 GUI / 输入

| 1.20.1 | 1.21.11 |
| --- | --- |
| `Screen#renderBackground(DrawContext)` | `renderBackground(DrawContext, int, int, float)` |
| `keyPressed(int, int, int)` | `keyPressed(KeyInput)`，`KeyInput(int key, int scancode, int modifiers)` record |
| `charTyped(char, int)` | `charTyped(CharInput)`，`CharInput(int codepoint, int modifiers)` |
| `mouseClicked(double, double, int)` | `mouseClicked(Click, boolean doubleClick)`；`Click(double x, double y, MouseInput)`；`MouseInput(int button, int modifiers)` |
| `mouseReleased(double, double, int)` | `mouseReleased(Click)` |
| `mouseScrolled(double, double, double)` | `mouseScrolled(double, double, double, double)` |
| `PressableWidget#onPress()` | `onPress(AbstractInput)`；**新增抽象** `protected void drawIcon(DrawContext, int, int, float)` |
| `DrawContext#drawText(...)` 返回 int | 返回 **void** |
| `KeyBinding(String, Type, int, String)` | `KeyBinding(String, Type, int, KeyBinding.Category)`；`Category.create(Identifier)`，翻译键推导为 `key.categories.<namespace>.<path>` |
| `InputUtil.isKeyPressed(long windowHandle, int key)` | `isKeyPressed(net.minecraft.client.util.Window, int)` |
| `PositionedSoundInstance.master(...)` | `PositionedSoundInstance.ui(...)` |
| `Entity.prevX/prevY/prevZ` | `lastX/lastY/lastZ` |
| `RenderSystem.getModelViewStack().push()/pop()` | JOML `Matrix4fStack.pushMatrix()/popMatrix()` |

对应改动落在 `compat/mc/{GuiScreen,GuiButton,GuiTextField,FontRenderer}.java`、
`compat/lwjgl2/Keyboard.java`、`core/rendering/IslandAmbientStatus.java`、
`core/screens/GuiFfmpegNotice.java`、`MuoniumPlayerMod.java` 与两份 lang 文件
（`key.categories.muonium` → `key.categories.muonium.player`）。

三处容易写错的细节：

- `GuiTextField` 转发点击时 `doubleClick` 必须传 `false`，传 `true` 会变成双击选词。
- `charTyped` 拿到的是**码点**，非 BMP 码点按丢弃处理而不是强转 char 截断。
- `GuiButton` 新增的 `drawIcon` 是空实现，**留空是正确的**：1.8.9 的按钮没有图标位。

### 3.3 GUI 延迟批处理（本次移植最关键的一条）

1.20.1 的 GUI 是**即时绘制**；1.21.11 改成了**延迟批处理**：

- `DrawContext` 的每个绘制方法只是往 `net.minecraft.client.gui.render.state.GuiRenderState`
  里追加一条 `GuiElementRenderState`；
- 真正的 GPU 提交集中在帧末的 `net.minecraft.client.gui.render.GuiRenderer#render(GpuBufferSlice)`；
- `DrawContext#createNewRootLayer()` 是 public；吃 `GpuTextureView` 的 `drawTexturedQuad` 重载是 **private**。

后果：本渲染层的三个调用点（HUD 覆盖层回调、`Screen#render`、`ScreenEvents.afterRender`）
全部落在「收集」阶段，那时原版 GUI **一个像素都还没画**。照 1.20.1 的做法在 `endFrame` 里合成，
画面会被随后提交的原版 GUI（界面背景的模糊与暗化等）整片盖住。

---

## 四、帧末合成：两次翻车与最终方案

### 4.1 为什么不能在自己的调用点里合成

1.20.1 的做法是在 `SkijaHost.endFrame()` 里直接把离屏图贴回当前 FBO。1.21.11 不行：
GUI 改成延迟批处理后，本渲染层的三个调用点（HUD 覆盖层回调、`Screen#render`、
`ScreenEvents.afterRender`）全部落在「收集」阶段，那时原版 GUI 一个像素都还没画，
贴上去会被随后提交的原版 GUI（界面背景的模糊与暗化等）整片盖住。

于是 `endFrame()` 改成只 `context.flush(offscreen)` 并置 `pendingComposite = true`，
合成推迟到帧末的 `SkijaHost.compositeFrame()`。相应地 `beginOffscreen` 里的
`clear(0x00000000)` 改为**每帧只清第一次**（一帧里 begin/end 会成对出现多次，
每次都清会擦掉前面画好的内容）。

### 4.2 第一次翻车：挂 `GuiRenderer#render` 的出口

直觉上帧末就是 `GuiRenderer#render(GpuBufferSlice)` 的出口。实测的结果是
**Skija 初始化成功、没有任何报错、也没有 GL 错误，界面就是完全不出现**。

原因在 `net.minecraft.client.gl.GlCommandEncoder#closePass()`：它每次收尾都执行
`glBindFramebuffer(GL_FRAMEBUFFER, 0)`（javap 可见 `ldc 36160` + `iconst_0`）。
所以在那个出口读 `GL_FRAMEBUFFER_BINDING` 拿到的是 0，画面被贴到了窗口默认帧缓冲上；
而 `MinecraftClient#render` 随后还要调 `Framebuffer#blitToScreen()`，
把主帧缓冲的颜色附件整屏 blit 到默认帧缓冲，正好把刚贴的内容全部覆盖。

### 4.3 最终方案：挂 `Framebuffer#blitToScreen()` 的出口

`com.muoniumplayer.mixin.FramebufferMixin`：

```java
@Mixin(Framebuffer.class)
public class FramebufferMixin {
    @Inject(method = "blitToScreen()V", at = @At("TAIL"))
    private void muonium$compositeSkijaFrame(CallbackInfo ci) { ... }
}
```

那一刻默认帧缓冲里已经是这一帧的最终画面，而 `Window#swapBuffers` 还没执行——
`MinecraftClient#render` 的字节码里 `blitToScreen` 在偏移 588、`swapBuffers` 在 656，
中间只有计时、profiler 与 Tracy 抓帧，没有任何东西会再动默认帧缓冲。

实现上的三个要点：

- 合成目标写死常量 `WINDOW_FRAMEBUFFER = 0`，**不读「当前绑定」**。
  每个 render pass 结束都会把绑定重置成 0，「当前绑定」在帧末不携带任何有用信息；
  而且 `savedFbo` 兼任过合成目标，正是 4.2 那次贴错目标的原因。
  现在 `composite(int targetFbo)` 把目标写在调用点上。
- mixin 里判等 `client.getFramebuffer() != (Object) this` 后才合成。
  `blitToScreen` 定义在抽象类 `Framebuffer` 上，原版每帧只对主帧缓冲调一次，
  但别的 mod 未必；多合成一次就是多贴一层半透明界面。
- `compositeFrame()` 无论本帧有没有内容都要调用：它同时负责复位 `frameCleared`。

另外留了一条一次性日志「首次帧末合成完成 WxH → 窗口默认帧缓冲」。
合成失败的表现是「没有任何报错、界面就是不出现」，光看日志无法区分
「根本没走到合成」和「合成了但被覆盖」，有这一行就能一眼分辨。

### 4.4 第二次翻车：纹理绑定的还原被 `GlStateManager` 的缓存整段跳过

界面出来之后出现另一个症状：**原版界面里个别汉字变成彩色噪点**。
实测是暂停菜单「回到游戏」的「游戏」两字——同一次绘制里「回到」两字是好的，
说明不是采样问题，而是字体图集里那两个格子的**内容**本身是垃圾像素。

根因是 `GlSnapshot.restore()` 恢复纹理状态时走了 `GlStateManager`：

- `_activeTexture` 与 `_bindTexture` 都是「值和缓存相同就跳过 GL 调用」的实现
  （javap 可见 `if_icmpeq` 直接 return）；
- Skia 是用裸 GL 改绑定的，`GlStateManager` 的缓存**压根没变**；
- 于是「把缓存值再设一遍」这个动作每一项都被跳过，真实绑定留在 Skia 那边。

后果不是立刻花屏，而是延迟到**下一次纹理上传**：
`GlCommandEncoder#writeToTexture` 里那句 `_bindTexture` 同样会被跳过
（它自己设的 4 个 `_pixelStore` 是无缓存的直通，所以像素解包参数不是元凶），
字形数据被写进了 Skia 的纹理，原版字体图集对应的格子永远是垃圾像素。
把合成移到帧末之后这个泄漏能跨过帧边界活到下一帧的上传，所以才显形。

修法是每一项都保证「真实状态对」且「缓存对」，见 `GlSnapshot` 里两个新 helper：

- `selectUnit(textureEnum)`：先调 `_activeTexture`（缓存对了，跳过与否都无妨），
  再裸调一次 `glActiveTexture`（真实一定对）。
- `bindTextureExactly(texture)`：快照值非 0 时先经缓存绑 0——此时缓存必然不等于 0
  （它还停在快照值上），这一步一定会发出真正的 `glBindTexture`，紧接着绑回快照值
  也一定会发出，两头都对；快照值为 0 时缓存可能已经是 0 而被跳过，补一次裸调兜底。

`GlStateManager.TEXTURES` 数组长度实测是 12（静态初始化里的 `bipush 12`），
与 `GlSnapshot.UNITS = 12` 正好对上，不需要调整。

其余状态项（blend / depth / cull / scissor / colorMask / polygonOffset / logicOp）
是用**裸 GL** 还原到快照值的，而 `GlStateManager` 的对应缓存自 capture 起就没被动过、
仍然等于快照值，所以真实与缓存天然一致，不需要同样的处理。
`_pixelStore` 是无缓存直通，也不需要。
## 五、实机验证（已过）

启动方式：

```powershell
cd 'C:\MUSIC\1.21.11 recode'; $env:JAVA_HOME='C:/Users/24914/.jdks/ms-21.0.12.1'
$env:MU_QUICKPLAY='新的世界'          # 直接进存档，省去在标题界面盲点鼠标
.\gradlew.bat runClient --console=plain
```

到进入存档约 80 秒。驱动脚本：

```powershell
tools\mcdrive.ps1 -TargetPid <pid> -Keys @('M') -Shot 'build\shot.png'
tools\mcdrive.ps1 -TargetPid <pid> -ClickX @(438) -ClickY @(240) -Shot 'build\shot.png'
```

**注意本机的 `open_music` 已被用户改绑到 `M`**（`run/options.txt` 里
`key_key.muonium.open_music:key.keyboard.m`），不是默认的右 Shift。
合成按键在本机对 MC 是**有效**的（此前那条「只能用鼠标」的记录不成立，
当时是按了没绑定的键）。`mcdrive.ps1` 必须保持纯 ASCII：
PowerShell 5.1 按 ANSI 读无 BOM 的 `.ps1`，中文注释会让它解析失败。

逐项结果：

| 验收项 | 结果 |
| --- | --- |
| 日志出现「首次帧末合成完成」 | 通过 |
| 日志无 `GL_INVALID` / 无异常 / 无「渲染已关闭」 | 通过 |
| 灵动岛 HUD（文字、图标、圆角、封面） | 通过 |
| 播放器主界面（M） | 通过 |
| 四角圆角无「圆角外套一层直角边框」 | 通过（放大到 4 倍逐像素看过） |
| 歌单详情页（封面网格、VIP/Hi-Res/全景声 标签、悬停态） | 通过 |
| 封面完整显示（不是只有左上角一块） | 通过 |
| 播放歌曲 + 歌词页（模糊背景、逐字填涂、头像、间奏点） | 通过，未崩（反向 uv 兜底生效） |
| 游戏内歌词覆盖层 | 通过 |
| HUD 编辑器（H） | 通过 |
| 窗口尺寸变化（854x480 ↔ 1920x1080 全屏） | 通过，离屏目标随之重建 |
| 世界渲染：天空 / 云 / 手持物 / 掉落实体 / 快捷栏物品与数量 | 通过 |
| 原版界面文字（暂停菜单、按钮） | 修掉 4.4 那个缓存问题后通过 |
| 原版界面背景的模糊/暗化不盖住播放器 | 通过 |

截图留在 `build\shot-*.png`。

未覆盖的：多显示器 / 不同 guiScale / 非 Windows 平台 / 长时间运行的显存增长。
## 六、渲染层红线（每条对应一个已修复的实机故障，不许回退）

1. **`SkijaHost.GlSnapshot` 必须是「快照 + 原样还原」**，不许退回「设成一套已知的好值」。
   MC 的状态管理器带缓存（值相同就跳过 GL 调用），且压根不管混合方程、`GL_STENCIL_TEST` 开关、
   **采样器对象**（`glBindSampler` 一旦绑上就完全覆盖纹理自身的过滤/包裹参数）、`GL_FRAMEBUFFER_SRGB`。
   实测症状：天空与云被画成错误色块、主菜单全景图糊掉、手持与掉落物贴图错乱。
   还原成快照值后缓存自动重新诚实。
   快照项：program / VAO / array+element buffer / 12 个单元的纹理与采样器 / blend 全套含方程 /
   depth 全套含 range / cull / scissor / stencil 全套 / colorMask / polygonOffset / logicOp /
   sRGB / primitiveRestart / unpack 布局。
   **纹理单元必须「缓存与裸 GL 一起写」**：`_activeTexture`/`_bindTexture` 是
   「值和缓存相同就跳过 GL 调用」的实现，只调 `GlStateManager` 会让整段还原变成空操作，
   见第 4.4 节。unpack 布局走 `GlStateManager._pixelStore`（无缓存直通），其余走裸 GL。
2. **`resetPixelUnpackLayout()` 不许删。** MC 的 `NativeImage#upload` 设了
   `GL_UNPACK_ROW_LENGTH/SKIP_PIXELS/SKIP_ROWS` 且用完不还原；不清掉，Skia 字形图集的
   增量上传会错位，症状是「形状/渐变/裁剪全正常，只有文字缺大半字符」。
3. **`SkijaGfx` 的矩阵栈与属性栈必须独立**（GL 里是两个栈，业务代码有交错 push/pop）。
4. **`SkijaImages` 尺寸登记**：分配处 `rememberSize`（权威、作废旧视图），绘制处 `hintSize`（putIfAbsent）。
   漏了分配处登记 → 封面只显示左上角一块。
5. **`SkijaImages` 必须容忍反向 uv**（`source()` / `normalize()` / `drawSource()` 三个 helper）。
   旧渲染层 `scale` 为负是合法输入（翻转采样），而 Skia 的 `Rect.makeLTRB` 是断言式会抛异常。
   `CappellaLyricRenderer.drawSheetCell` 的精灵图第 2 行起就会触发，崩过一次
   （`expected t <= b, got 97.0 > 94.0`）。`SkijaGeometryStaticTest` 钉住了。
6. **`CFontRenderer` 度量口径照抄**：`fontHeight = box*2`、
   `box = max(ceil(-ascent)+ceil(descent))` over 主+回退字体、`getFontHeight() = (fontHeight-8.5)*.5`、
   `baselineOffset = (box+mainAscent-mainDescent)*0.5`、绘制基线 `y = (y-2)+baselineOffset`、
   **单字步进量化 `advance = ceil(rawAdvance*2)*0.5`**（去掉图标会偏半像素）、
   `FontEdging.ANTI_ALIAS` + `FontHinting.NONE` + `setSubpixel(true)`。
7. **`SkijaText` 的 Typeface 登记表必须是 `IdentityHashMap`**
   （多份图标字体内部名都叫 `fontello`，按名字或 equals 查会串）。
8. **`MuoniumPlayerExtension#isCallingFromMainThread()` 必须用 `MinecraftClient#isOnThread()`**
   （比较线程名会死锁）；**`OpenAPIImpl` 三个监听器列表必须是 `CopyOnWriteArrayList`**。

---

## 七、静态测试（39 项）

| 测试类 | 项数 | 钉的是什么 |
| --- | --- | --- |
| `Lwjgl2KeycodeProbeTest` | 5 | LWJGL2 DirectInput 键码 ↔ GLFW 的双向映射 |
| `DynamicIslandMathProbeTest` | 4 | 灵动岛的插值 / 音量档位 / 图标码点 |
| `LyricParserProbeTest` | 7 | LRC / YRC / QRC / TTML 解析 |
| `RenderLayerStaticTest` | 4 | 旧 OpenGL 实现已删除；渲染层只有 SkijaHost 认识 MC；SkSL uniform 布局 |
| `SkijaGeometryStaticTest` | 6 | 负宽高与反向 uv 的兜底 |
| `SkijaTextStaticTest` | 4 | 字体覆盖率、度量、CPU/整形光栅有墨迹 |
| **`Port121StaticTest`**（本轮新增） | **8** | 帧末合成三段链路完整（`FramebufferMixin` 存在 + 注册 + 判等主帧缓冲，且禁止再注册已被证伪的 `GuiRendererMixin`）；`endFrame` 不合成；合成目标是常量 `WINDOW_FRAMEBUFFER` 而非「当前绑定」；`GlSnapshot` 与 unpack 复位未被回退；`GlStateManager` 包名；纹理还原必须同时写缓存和裸 GL；不引用已删除的类；AW 保持空 |

`Port121StaticTest` 与 `RenderLayerStaticTest` 扫源码时都会**先去注释**：
注释里提到 `BufferRenderer` / `Shaders` 之类已删除的旧 API 是刻意保留的移植说明。

跑法：

```powershell
cd 'C:\MUSIC\1.21.11 recode'; $env:JAVA_HOME='C:/Users/24914/.jdks/ms-21.0.12.1'
.\gradlew.bat test --console=plain
```

javac 的错误走 **stderr**；用 `Start-Process -RedirectStandardError` 时记得读 `.err.log`。
首次编译约 3m40s（含 remap sources），之后约 30s。

---

## 八、已知遗留

1. **Skija 原生库只打 `windows-x64`**（`gradle.properties` 的 `skija_natives`）。
   发布前需补 linux-x64 / macos-x64 / macos-arm64，四平台合计约 46 MB。
   `SkijaNatives` 会按 `os.name`/`os.arch` 自动挑资源路径，只需在 `build.gradle` 加 include。
2. 资源文件已全部就位、与 1.20.1 基线逐字节一致（18 字体 / 11 贴图 / 5 个 yrc / lang / `IngameIME.dll`），
   **不需要再迁**。
3. 本工程**没有 git 仓库**（未 init）。基线 `C:\MUSIC\1.20.1 recode` 那边的改动也从未提交，
   回退点 `c5503c2`。
4. `C:\MUSIC\1.21.11\MuoniumPlayer` 是**坏的旧尝试**，任何情况下都不要参考。
5. `hudRenderDisabled` / `screenRenderDisabled` 兜底闸门仍在：渲染路径首次抛异常时打一条日志
   并永久关闭该路径，保证「mod 装上后游戏还能玩」。实机验证时若界面完全不出现，
   **先去日志里搜这两条**，不要以为是没画。
