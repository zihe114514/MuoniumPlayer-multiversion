# 1.8.9 Forge -> 1.20.1 Fabric 移植状态 本工程：`C:\MUSIC\1.20.1\muoniumplayer` ## 结论 移植完成，界面能画、游戏能玩。707 个 java 文件（原 718，删掉 `lain/mods/inputfix` 的 11 个 CoreMod 文件）。 | 项 | 状态 |
| --- | --- |
| `gradlew build` | 绿。产物 `build/libs/MuoniumPlayer-1.3.0.jar`（44.6 MB，含 9 个 Jar-in-Jar 依赖） |
| `gradlew test` | 绿。21 条离线探针全过 |
| Mod 被 Loader 加载 | 是。`runClient` 日志出现 `muonium: MuoniumPlayer 1.3.0` |
| 游戏本体 | 正常。进存档、开背包、死亡/重生、返回主菜单都实测过 |
| HUD 设置界面（默认 H） | **正常渲染**：圆角卡片、渐变、图标、中文字体、开关、侧栏裁剪都对 |
| 播放器界面（默认 RShift） | **正常渲染**：首页 / 排行榜切换、歌单封面加载、控制栏、音量条、音质选择器 |
| GL 错误 | `runClient` 全程 0 条 OpenGL debug message，0 异常 |
| 业务层 | ffmpeg 探测通过；网易云首页歌单 / 排行榜可拉取并显示。账号相关未测（需真实账号） | 验证截图（这一轮）：`build/shot-h5.png`（HUD 设置）、`build/shot-ncm16.png`（播放器首页 + 封面）、
`build/shot-charts.png`（排行榜）、`build/shot-inv-clean.png`（原版背包，确认没有 GL 状态泄漏）。 ## 核心难点：固定管线（immediate mode） MC 1.20.1 通过 GLFW 申请的是 OpenGL 3.2 **core + forward-compatible** 上下文，
`glBegin/glVertex/glPushAttrib/glMatrixMode/glAlphaFunc` 这些函数没有入口地址。 关键事实（实测，别再走一遍弯路）：**LWJGL 3 调用无地址函数不是抛异常，而是直接 JVM abort**： ```
FATAL ERROR in native method: Thread[Render thread,10,main]:
No context is current or a function that is not available in the current context was called.
The JVM will abort execution. at org.lwjgl.opengl.GL11.glPushAttrib(Native Method)
``` `try/catch` 拦不住，只能保证「不调用」。本工程有 52 个文件、500 多处固定管线调用，
逐个改写成 VBO + shader 的工作量与视觉回归风险都远大于「在同一层把固定管线语义补出来」。 ### 做法：同名替身类 + 语义模拟 `com.muoniumplayer.compat.gl.GL11 extends org.lwjgl.opengl.GL11`，
靠 Java 的**静态方法隐藏**替换固定管线那几十个入口，其余入口和全部常量靠继承原样透传。
业务代码只改一行 import： ```java
- import org.lwjgl.opengl.GL11;
+ import com.muoniumplayer.compat.gl.GL11;
``` 同理还有 `GL13`（glActiveTexture）、`GL14`（glBlendFuncSeparate）、`GL20`（glUseProgram），
它们只是把状态改动接到 MC 的 `GlStateManager` 上（原因见下）。 引擎在 `com.muoniumplayer.compat.gl.internal.FixedFunction`（放在子包里，
这样里面写 `GL11.` 指的是 LWJGL 原版，不会自己递归）。它维护： - **immediate mode**：`glBegin/glVertex*/glTexCoord*/glColor*` 攒进一个 direct FloatBuffer （位置 3 + 颜色 4 + UV 2），`glEnd` 时上传 VBO 用 VAO 画一次。 `GL_QUADS` 在 core profile 不存在，展开成三角形；`GL_POLYGON -> GL_TRIANGLE_FAN`、 `GL_QUAD_STRIP -> GL_TRIANGLE_STRIP`。
- **矩阵栈**：模型视图 + 投影两个栈，默认是**增量**语义 （最终矩阵 = MC `RenderSystem` 的矩阵 × 本层矩阵），所以不写矩阵代码的 2D 绘制 直接落在 MC 的 GUI 坐标系里（原点左上、GUI Scale 单位），和 1.8.9 一致。 一旦调用 `glLoadIdentity` 就转为**绝对**语义 —— 这正是 1.8.9 里自建投影的写法 （`Framebuffer#framebufferRenderExt` 的 ortho、`CoverflowOverlay` 的 gluPerspective）。
- **属性栈**：`glPushAttrib/glPopAttrib` 按 mask 分组保存/恢复 （current / enable / colorBuffer / depthBuffer / stencilBuffer / viewport / scissor / texture）。 `ScissorClipManager` 只推 `GL_SCISSOR_BIT`，按位处理才不会顺手把调用方的 blend 也还原掉。
- **alpha test**：`glAlphaFunc` + `GL_ALPHA_TEST` 在默认片元着色器里用 `discard` 复现， 8 种比较函数全支持。默认值取 1.8.9 GUI 阶段的基线（GREATER 0.1）。
- **显示列表**：`glGenLists/glNewList/glEndList/glCallList/glDeleteLists` 录制命令流并回放 （`ShaderProgram#drawQuadFlipped`、`Framebuffer#updateRenderCallList` 依赖它）。 录的是命令而不是解析后的状态，所以列表里没有 `glColor` 时用的是**调用时**的当前颜色，与真实 GL 一致。
- **`glShadeModel`**：core profile 只有平滑插值。本工程只在渐变前后切 SMOOTH/FLAT， 非渐变处四个顶点同色，视觉无差异，做成空实现。
- **`glTexEnvi`**：本工程只用 `GL_MODULATE`（颜色 × 纹理），正是默认着色器的行为，空实现。 **每帧归零**：矩阵栈/属性栈/当前颜色现在在 Java 侧，不再像 1.8.9 那样被 MC 每帧重建 GL 矩阵冲掉。
所以 `MuoniumPlayerMod#onRenderOverlay`、`#onDrawScreenPost` 和 `compat.mc.GuiScreen#render`
开头都调 `FixedFunction.beginFrame()`，结尾调 `endFrame()`；
一次不配对的 `glPushMatrix` 只影响当前帧，不会让界面从此错位。 ### 为什么所有状态改动都要走 MC 的 GlStateManager 1.20.1 的 `GlStateManager` 会缓存 blend / depth / colorMask / 纹理绑定 / program。
裸调 GL 会让缓存与真实状态脱钩，之后 MC 自己的绘制会跳过它以为已经设过的状态
（表现为原版界面或世界渲染错乱）。因此 `glEnable/glDisable/glBlendFunc/glDepthMask/
glColorMask/glViewport/glScissor/glClear*/glStencil*/glBindTexture/glDeleteTextures/
glPixelStorei/glActiveTexture/glUseProgram` 全部转发给 `GlStateManager._xxx`。 唯一例外是纹理单元 >= `GlStateManager.TEXTURE_COUNT`（=12，本工程用到 16 和 20）：
`_activeTexture` 会越界访问它内部长度 12 的数组，只能裸调；为了不让缓存以为自己还停在 0 号单元
（那样切回 0 号时它会跳过真正的切换），裸调之前先把缓存推到 11 号。 ## 着色器：GLSL 120 -> 150 11 个片元着色器 + 1 个共用顶点着色器全部改写。core profile 下 `#version 120` 的
`gl_TexCoord / gl_FragColor / texture2D / gl_Vertex / gl_MultiTexCoord0 /
gl_ModelViewProjectionMatrix` 一个都不存在，**编译直接失败**，而本工程对编译失败的处理是
「记一条日志 + 标记不可用」（`ShaderProgram` 构造函数），不崩，只是圆角/渐变/玻璃/模糊一起消失。 改法是机械的：`#version 150`、`in vec2 texCoord0` / `out vec4 fragColor`、
`texture2D(` -> `texture(`；算法一行没动。顶点着色器改成
`in vec3 Position / in vec2 UV0 / uniform mat4 u_mvp / out vec2 texCoord0`，
名字与 `FixedFunction` 里 `glGetAttribLocation` / `glGetUniformLocation` 查的一致 ——
`ShaderPortProbeTest` 把这件事钉住了（改名不会报错，只会让所有自定义着色器绘制变空白）。 ## 移植中查出并修掉的缺陷 按「不修就用不了」的顺序： 1. **主线程判定死锁**（`MuoniumPlayerExtension#isCallingFromMainThread`） 1.8.9 比线程名 `"Client thread"`；1.20.1 同一条线程叫 `"Render thread"`。 照抄名字会让主线程上的调用被判成「不在主线程」，于是 `MultiThreadingUtil#runOnMainThreadBlocking` 把任务塞进队列后在同一条线程上等它执行 —— 自我死锁。实测：按键打开播放器后整个客户端卡死（`Responding = False`）， 窗口停在最后一帧，jstack 显示 Render thread 停在 `FutureTask.awaitDone`。 改成问 MC 自己 `MinecraftClient#isOnThread()`（比线程对象，等价于 1.8.9 的 `IThreadListener#isCallingFromMinecraftThread()`）。 2. **像素解包状态泄漏**（`compat.gl.GL11#glTexImage2D/glTexSubImage2D`） 1.20.1 的 `NativeImage#upload` 会设 `GL_UNPACK_ROW_LENGTH = 图片宽度` 和 `GL_UNPACK_SKIP_PIXELS/SKIP_ROWS`，**用完不还原**。这是全局状态，本工程随后上传 字体图集 / 封面纹理时会按 MC 那张图的宽度切行 —— 整张纹理错位成噪点。 实测：界面里所有文字和图标变成花屏，且随「MC 先上传过哪张图」时好时坏 （对比 `build/shot-h4.png` 花屏 与 `build/shot-h5.png` 正常）。 1.8.9 的 MC 从不用 ROW_LENGTH，所以原工程没这个问题。 现在每次上传前把跨距与跳过量清零（不动 `GL_UNPACK_ALIGNMENT`，那一项业务代码自己在管）。 3. **删除后重绑同名纹理**（`AbstractTexture#deleteTexture`） 原实现删完仍保留旧纹理名，靠下一次 `glBindTexture(旧名字)` 让驱动「隐式重建」—— 那是 compatibility profile 的行为。core profile 下直接是 `GL_INVALID_OPERATION: Texture name does not refer to a texture object generated by OpenGL`， 后续上传全部丢弃。实测：播放器首页歌单封面全是纯黑块，日志刷同一条错误 （对比 `build/shot-ncm14.png` 黑块 与 `build/shot-ncm16.png` 正常）。 现在删完把 `glTextureId` 置回 -1，下次 `getGlTextureId()` 重新 `glGenTextures`。 顺带修掉原来那句 `if (getGlTextureId() == -1) return;` 死代码 —— `getGlTextureId()` 本身会在 -1 时生成一个，永远不可能返回 -1， 于是「为了删除先创建一个纹理」。 4. **EXT 扩展函数**（`Framebuffer#createFramebuffer`） 原来调 `EXTFramebufferObject.glFramebufferRenderbufferEXT`。core + forward-compatible 上下文不暴露 `GL_EXT_framebuffer_object`，LWJGL 3 拿到的函数地址是 0，调用即 JVM abort。 换成语义完全相同的核心版 `glFramebufferRenderbuffer`。 5. **字体图集格式**（在模拟层里处理，业务代码没动） `TextureAtlas` 用 `GL_ALPHA` 作 internalFormat，core profile 不接受 （实测每次字体初始化刷 44 条 `GL_INVALID_VALUE ... <internalFormat> not valid`）。 `compat.gl.GL11` 的纹理上传入口把 `GL_ALPHA` 翻成 `GL_R8/GL_RED` 并记下这个纹理 id， 采样时走「只取 r 通道当 alpha、rgb 用顶点色」的分支 —— 等价于 1.8.9 里 GL_ALPHA 纹理 + GL_MODULATE 的可见效果（彩色文字 + 覆盖率作为透明度）。 6. **首 tick 的 ConcurrentModificationException**（`OpenAPIImpl`） 三个列表从 `ArrayList` 改成 `CopyOnWriteArrayList`。首次 tick 遍历 `eventHandlers` 时会触发 `RenderSystem` 等类的静态初始化，而那些静态块里会 `registerEvent()`， 于是在 `unmodifiableList` 视图上迭代抛 CME（实测崩溃 `crash-2026-08-26_01.23.02`）。 1.8.9 只是碰巧类加载顺序不同没踩到，属同一个潜在缺陷。 ## 兼容层（改动量都压在这里，业务代码基本没动） `com.muoniumplayer.compat.lwjgl2`
- `Keyboard`：**保留 LWJGL2 键码空间**（常量值逐条抽自 `lwjgl-2.9.3-sources.jar`）， 只在查硬件状态时翻成 GLFW。原因：全项目 60+ 处 `Keyboard.KEY_*` 比较， `KeyboardUtils` 直接写裸数字（29/157/42/54/56/184），HudConfig 也按这套码值持久化。 `Lwjgl2KeycodeProbeTest` 锁死了这件事。
- `Mouse`：坐标保持「原点左下、framebuffer 像素」的 LWJGL2 语义； `getDWheel()` 保留「读一次清零 + 120/格」，靠在 MC 的 scroll callback 外再套一层实现 （`glfwSetScrollCallback` 返回的旧回调会继续转发，不会吃掉 MC 的滚轮）。
- `Display`：`getWidth/getHeight` 映射到 `Window#getFramebufferWidth/Height`； `getHwnd()` 用 `GLFWNativeWin32.glfwGetWin32Window` 取代原先反射 `WindowsDisplay.hwnd`。
- `Sys.openURL` -> `Util.getOperatingSystem().open(url)`。
- `glu.GLU.gluProject` / `glu.Project.gluPerspective`：算法逐行照抄 `lwjgl_util-2.9.3-sources.jar`。 `gluProject` 要的 `GL_MODELVIEW_MATRIX / GL_PROJECTION_MATRIX` 由 `FixedFunction` 提供 （`ScissorClipManager` 靠它把控件坐标投影成屏幕裁剪框）。 `com.muoniumplayer.compat.mc`（1.8.9 GuiScreen 家族的替身，类名故意同名，调用点只改 import）
- `GuiScreen`：把 1.8.9 的 `initGui/onGuiClosed/updateScreen/drawScreen/keyTyped/mouseClicked/ mouseReleased/actionPerformed/doesGuiPauseGame` 桥到 1.20.1 的 Screen。 注意键盘事件形状差异：1.8.9 一次 `keyTyped(char,int)` 同时给字符和键码， 1.20.1 拆成 `keyPressed(glfwKey,...)` + `charTyped(char,...)`，桥接时键码走 `Keyboard.fromGlfw`。
- `GuiTextField`：继承 1.20.1 `TextFieldWidget` 复用原版编辑逻辑，外面套 1.8.9 的方法名； `width/height` 用同名 public 字段遮蔽父类 protected 字段（调用点是直接赋值）。
- `GuiButton`：继承 `PressableWidget`，保留原版按钮三态贴图。
- `FontRenderer`：包 `TextRenderer` + 「当前帧 DrawContext」；帧外 drawString 静默丢弃。
- `Gui.drawRect` -> 转发 `RenderSystem.drawRect`；`ScaledResolution` -> `Window` 快照。 `com.muoniumplayer.compat.gl`：见上一节。 ## 事件挂点对照 | 1.8.9 Forge | 1.20.1 Fabric |
| --- | --- |
| `@Mod` + `preInit`/`init` | `fabric.mod.json` client entrypoint + `onInitializeClient()`（两阶段合并） |
| `TickEvent.ClientTickEvent(END)` | `ClientTickEvents.END_CLIENT_TICK` |
| `RenderGameOverlayEvent.Post(TEXT)` | `HudRenderCallback` |
| `GuiScreenEvent.DrawScreenEvent.Post` | `ScreenEvents.AFTER_INIT` 里挂 `ScreenEvents.afterRender(screen)` |
| `ClientRegistry.registerKeyBinding` | `KeyBindingHelper.registerKeyBinding` |
| `KeyBinding.isPressed()`（上升沿） | `KeyBinding.wasPressed()`（1.20.1 的 `isPressed()` 是「当前按住」，别用错） | 其它成批的名字迁移：`net.minecraft.client.shader.Framebuffer` -> `net.minecraft.client.gl.Framebuffer`
并改字段名（`framebufferObject`->`fbo`、`framebufferTexture`->`colorAttachment`、
`framebufferWidth/Height`->`viewportWidth/Height`、`framebufferTextureWidth/Height`->`textureWidth/Height`、
`depthBuffer`->`depthAttachment`）；access widener（`src/main/resources/muoniumplayer.accesswidener`）
放开 `Framebuffer.depthAttachment/colorAttachment`，因为 stencil 补丁要写回它们。
LWJGL2->3 改名：`glGetInteger/Float/Boolean(int,Buffer)` -> `glGetIntegerv/Floatv/Booleanv`、
`glUniform1/4(int,FloatBuffer)` -> `glUniform1fv/4fv`、`glMultMatrix` -> `glMultMatrixf`。
`Logger` 不再实现 `org.apache.logging.log4j.Logger`（1.20.1 的接口大得多），
唯一的使用者 `MuoniumPlayerEventHandler` 改成声明本项目自己的类型。 ## 环境（踩过的坑） - Gradle 守护进程必须 **JDK 21**（`fabric-loom 1.17.19` 硬要求），编译与 `runClient` 用 **JDK 17** toolchain（1.20.1 的目标运行时）。两者都写在 `gradle.properties`。 调 gradlew 前先 `$env:JAVA_HOME='C:\Users\24914\.jdks\ms-21.0.12.1'`。
- `repo.maven.apache.org` 本机直连超时，走 7890 代理时 Gradle 侧 TLS 握手被中断 （curl 同一 URL 走代理却是 200，原因未查明）。`build.gradle` 里把所有 Maven Central 仓库（含 Loom 注入的 `mavenCentral()`）改写到阿里云。
- `libraries.minecraft.net` 反过来是**必须直连**（走代理会 `Connection was aborted`）， 已写进 `nonProxyHosts`。
- Gradle 发行包从 `services.gradle.org` 下载约 50KB/s，改用腾讯镜像（4 秒下完 140MB）， 已写进 `gradle-wrapper.properties`。
- `libs/` 下的本地 jar 通过 `flatDir` 仓库以坐标形式引用 （`muoniumplayer.libs:music-api:0.1.1` 等）—— Loom 的 `include` 不接受裸 `files()` 依赖。 ## 辅助脚本 - `tools/api.ps1`：`-Classes <类名> [-Filter <正则>]`，对着 Loom 生成的 named jar 跑 javap。 移植红线「不许猜 API」靠它落实，改任何 Yarn 调用前先用它确认签名。 依赖 `gradlew dumpCompileClasspath` 生成的 `build/compile-classpath.txt`。
- `tools/mcui.ps1`：`-TargetPid <pid> [-ClickX/-ClickY] [-Keys H,ESC,J,...] [-MiddleClicks n] [-Shot <png>]`， 给 `runClient` 起来的客户端发点击/按键并截图，用来做界面级实测。 注意：`keybd_event` 只投递给前台窗口，脚本每次按键前会重新抢焦点并校验； 中键点击前会把光标移到窗口中心（否则点在终端上）。
- `gradlew dumpBundleDeps`：列出需要 Jar-in-Jar 的第三方依赖（判断哪些 MC 已自带）。 ## 还没验证的 需要真实账号：网易云 / QQ / 酷狗登录与搜索、灰色歌曲解灰、GD 音乐台各音源、
B 站 m4s 解析与转码、私人 FM 预取、一起听、无缝切换、听歌时长上报、歌单同步、动态封面。
需要实际播放才能看到的：逐字歌词（YRC/QRC/TTML 的解析本身有 7 条单测覆盖）、
全屏歌词舞台、灵动岛动画、Coverflow 的 3D 透视。 已知的观察项（不影响可用性）：854x480 窗口 + GUI Scale 2 时逻辑分辨率只有 427x240，
播放器面板与 HUD 设置面板都被窗口边缘裁到，侧栏最后一项文字露不全 —— 属于小窗口下的布局拥挤，
不是渲染错误；放大窗口即可。
