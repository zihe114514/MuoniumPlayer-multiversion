# 字体集成契约（2026-11-24）

适用本独立Skija工程。实现位于 `host/text/HostTextRenderer.java`，生产Java目标17；已核对并局部编译的宿主组合为Minecraft 26.3 × Fabric Loader 0.19.5 × 实际编译classpath中Skija 0.143.17（types 0.2.0）。不据此代证其它MC/loader组合。

冻结状态：2026-11-24。主线程已将本类接入常驻 `SkijaConfigService`；本轮只收束文档，不再修改实现、测试或主线程调用引用。主线程确认已复跑 text/run.ps1：23条契约断言与121条真实绘制断言均通过。提交、集成包和游戏人工验收由主线程后续处理，冻结不等于游戏或1:1验收通过。

## 主线程可直接接入的入口

`HostTextRenderer implements SkijaPainter.Text, HudRuntime.TextRenderer, AutoCloseable`。

```java
// 已有加载worker；此阶段只读资源字节和宿主注册AWT句柄，不创建Skija对象。
CompletableFuture<HostTextRenderer.LoadedAssets> textAssets =
    CompletableFuture.supplyAsync(() -> {
        try {
            return HostTextRenderer.loadAssets(getClass().getClassLoader());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }, existingAssetWorker);

// 主线程加载状态确认ready后，宿主Skija与FontManager已初始化；在渲染线程执行。
HostTextRenderer text = HostTextRenderer.create(
    textAssets.join(), SkijaGfx::canvas, () -> Theme.active(preferences));
// 当前由SkijaConfigService.text()统一创建并交给host.textRenderer(text)。
// painter适配器、菜单与常驻HUD共用此实例；不要每帧new或在worker create。

// SkijaPainter.Text：均为top-left，真实size创建，不缩放pf32/pf34。
double width = text.measure(label, 15, false);
text.draw(label, x, y, 15, false, color);

// 可区分Medium/Semibold等字重的主UI入口。
// 当前service已把Painter.Text的weighted回调成对转发到这两个入口。
text.measureUi(label, 15, "Medium", false);
text.drawUi(label, x, y, 15, "Medium", false, color);

HudRuntime.Metrics metrics = text.measure(content, textStyle);
text.draw(existingPainter, content, x, y, textStyle);

// 仅所属service最终shutdown时，在创建它的渲染线程释放。
// 普通关屏不能关闭这个常驻实例。
text.close();
```

当前实际所有权：`SkijaConfigService` 的 assets worker 调用 `loadAssets(loader)`；`text()` 在渲染路径惰性创建一个实例，同时经 `host.textRenderer(text)` 供常驻HUD使用。`painter()` 的文本适配器转发普通及weighted测绘。`removed(screen)` 只处理菜单离开、声音和持久化，不关闭text/painter；`shutdown()` 才调用text.close并清空引用。上述接线已经按当前源码静态核对，退出回调实际执行线程与游戏资源释放仍由人工/集成验收确认。

本类不拥有传入Canvas或宿主原CFont/Typeface，没有隐式引用计数；LoadedAssets只读，可重复用于独立实例。一般调用方若另建独占实例，必须自行在创建线程关闭。

`create(LoadedAssets, Supplier<Canvas>, Supplier<Theme>, Supplier<HostFonts>)` 是可测试/其它宿主来源的注入入口；HostFonts中的CFont/AWT均借用，不关闭。默认source读取FontManager.pf16/pf16bold、UiFontLibrary.getWiredRegularAwt/getWiredBoldAwt和实际system family。工厂需要这两份宿主CFont已ready，缺失明确失败，不临时loadFonts。

## 真实声明与字体字节来源

| 触点 | 本次核实的声明/源码 | 用法和边界 |
| --- | --- | --- |
| FontManager.builtinTextMain(boolean) | muonium-common/.../core/management/FontManager.java；public static java.awt.Font | 在worker调用；内部readFont读sfregular.otf/sfbold.otf并把原字节登记SkijaText；不会创建CFont/Skija字体 |
| FontManager.builtinUiFallbackBytes() | 同类public static byte[] | CJK缓存优先真实来源；本生产实现的fallback直接保留宿主链，不再次读它；测试用它构造真实CJK链 |
| CFontRenderer.deriveAtSize(float) | muonium-common/.../core/rendering/font/CFontRenderer.java | 保留sourceFont/sourceFallbacks/fontKerning/embolden；每个真实size独立Font/缓存，本类拥有派生CFont |
| CFontRenderer.metricsRevision() | 同类public long；buildSkiaFonts中递增 | 主/粗体两档的revision和对象身份任一改变，清理本实例全部derived/native face/layout缓存；覆盖retarget、完整fallback包和韩文fallback变更 |
| CFontRenderer.drawPlainTextWithPaintOn(Canvas,String,float,float,Paint) | 同类public boolean | 复用现有blob/fallback/字距，caller paint实现颜色/真实stroke；y传baseline+2-getBaselineOffset以消除旧GUI y-2约定 |
| CFontRenderer.getStringWidthD/getBaselineOffset/lineBox/close | 已对实际class文件javap验证 | UI测绘同一派生CFont；不把getFontHeight的历史-4.25当Compose自然行盒 |
| UiFontLibrary.getWiredRegularAwt/getWiredBoldAwt/getActive | public static；同宿主core/management | 当前AWT身份；系统字体没有可提取文件字节，不能猜文件路径；通过SkijaText已注册Typefaces借用 |
| SkijaText.typefaceFor(java.awt.Font) | public static Typeface | 传原始已注册身份；借用主项目typeface，不关闭、不重建其native loader |
| Skija 0.143.17 | javap FontMgr.makeFromData/matchFamilyStyle/matchFamilyStyleCharacter、Typeface.getVariationAxes/makeClone/getUTF32Glyph/getUniqueId、Font、TextLine、Paint、Canvas | 仅用本次实际JAR真实签名；生产代码不创建Surface/GL/context/texture/nativeLoader |

两个Minecraft文件的精确来源：`src/main/resources/assets/muoniumconfigskija/fonts/minecraft/Minecraft-Regular.otf`、`Minecraft-Bold.otf`。loadAssets在worker通过ClassLoader读取原字节并复制为私有数组，create在渲染线程同一Skija的FontMgr.makeFromData创建owned Typeface。资源错误不吞，不改用猜测的原版fontRenderer。

## 样式与布局算法

- 主UI普通draw/measure读取Theme supplier，每次主题切换立即选择host或Minecraft字体，不对已选宿主字体做缩放；Host常规/粗体均按真实size deriveAtSize，原fallback顺序及字距保留。
- Hud TextStyle.font=`Poppins` 表示原仓移除Poppins资源后沿用宿主字体的角色名；`Minecraft` 使用原OTF。可显式用Host/UI别名，未知值报错。
- weight识别Thin/Regular/Medium/Semibold/Bold/Black等100–900；bold=true按原getPoppinsFontName优先级强制700。系统字体按已确认family+weight/italic请求匹配；可变字体只在真实声明wght轴时clone；静态文件选择可用regular/bold，不声称有不存在的Thin/Medium/Black文件。`resolution(TextStyle)`返回requestedWeight/actualWeight和synthetic flags，供验收报告记录真实降级。
- italic优先真实系统斜体，否则Skija Font.skewX=-.25；CFont fallback在baseline原点同样skew，测量仍用同一glyph advance。italic墨迹可能有advance之外的overhang，这是标准排版逻辑盒，不人为缩放字符间距。
- 分行保留末尾空行并规范化CRLF；每行宽度为实际runs的advance之和，整块为max width；同一次layout保留相同TextLine/CFont，measure和draw共用。baseline/height/lineHeight由同一字体metrics和实际用到的fallback共同计算。align 0/1/2=左/中/右，仅把短行放进max line width。
- fallback顺序：选择的原生face → 原宿主CFont完整fallback链 → OS匹配仍缺的码位 → 原OTF missing glyph。使用宿主实际icu4j 78.3的BreakIterator分grapheme，整体选face，连续同face合成run，CFont run最长1024字符，补充平面/组合字grapheme独立成run，避免把宿主单grapheme shaping退化为整句UTF-16逐字；原生face可shape ligature/组合字，未宣称对任意复杂脚本均达到游戏原渲染器像素一致。
- underline取原生FontMetrics的position/thickness（CFont入口使用size相关fallback值），与同一行baseline/advance绘制。
- shadow=true且outline=false使用shadowX/Y+shadowColor偏移；shadow=true且outline=true是原fullShadow语义，strokeWidth=2*(size/8)，随后画正文；两者均不改变逻辑排版尺寸，符合原PoppinsTextNode。shadow=false时outline单独不画。
- Font cache最多96个size/family/weight/italic项；layout cache最多192个、文本<=2048才保留；超长layout测绘后立即close。glyph blob缓存仍由各owned CFont自身管理。字体revision改变先close layouts，再close fonts，避免引用已释放字体。系统fallback字体跟随其所属Face关闭。
- 只在创建线程measure/draw/invalidate/close，越线程明确异常；close幂等，关闭后拒绝新任务。`invalidate()`用于额外资源reload；通常不需要，因为metricsRevision会自动失效。`statistics()`仅诊断generation/cache/closed，不暴露native句柄。

## 自动验证与未验证范围

在本工程根执行：

```powershell
.\src\test\java\com\muoniumplayer\configskija\text\run.ps1
# 仅局部编译+不初始化native的契约守卫：
.\src\test\java\com\muoniumplayer\configskija\text\run.ps1 -CompileOnly
```

- 使用 `build/host/26.3/fabric/compile-classpath.txt` 的真实依赖，JDK25执行javac --release17，字节码major=61通过。JDK25只是读取宿主Java25 class文件需要，生产body的Java/API目标仍17。
- `HostTextContractTest` 23条断言通过：三接口、Java17字节码、准确OTF SHA-256、无Surface/GL/context/loader/私有反射/固定pf32缩放依赖。
- `HostTextRendererTest` 121条断言通过：原加载worker、默认/注入工厂、真实大小CFont及fallback、两字体与五字重、实际系统family匹配、bold优先级、斜体/下划线/描边/阴影CPU像素、多行对齐、中文/韩文/组合字/补充平面/emoji实际绘制、字体热切与fallback revision失效、缓存上限、跨线程拒绝、幂等释放和屏幕关闭后独立runtime实例继续工作。
- CPU测试使用当前宿主 `SkijaNatives.ensureLoaded` 和完全匹配0.143.17平台JAR，测试专用Bitmap+Canvas，无Surface或GL上下文；生产adapter从不调用loader。测试产物仅在本工程build/text-tests。
- 本任务未启动游戏、未运行全仓构建；本轮文档冻结没有重复运行已由主线程确认通过的行为测试。其它13个组合不由本次26.3/Fabric局部结果代证；主线程集成构建结果另计，游戏视觉/交互人工验收待完成。

## 明确的验收边界

- 静态regular/bold文件没有的Thin/Medium/Black以实际最近字重表达，`resolution`可定位；未复制/伪造Poppins字体文件，原仓已去掉Poppins实体资源。原字体热切字体通过借用宿主族保留，不靠反射提取系统字体文件。
- 高DPI/GUI scale、字体切换观感、所有脚本的连字与双向文字、静态HUD和游戏内光栅结果仍需人工验收；CPU中有像素只证明绘制发生，不证明和原Compose/MC逐像素相等。
- 当前`SkijaPainter.Text`已提供drawWeighted/measureWeighted默认方法；`SkijaConfigService.painter()`已成对转发到本类drawUi/measureUi。原boolean接口仍保留。若其它调用方直接把HostTextRenderer作为Text且不加这个weighted适配器，默认方法只能归为regular/bold两档；冻结时未改变主线程适配器或接口。
- 未识别的family/weight、无active canvas、未就绪宿主字体/坏资源均显式失败，不吞异常假装回退成功。真实系统字体也没有相应glyph时用原OTF.notdef，未声称不存在的字形已被补齐。
- §颜色控制码在此纯TextStyle文本入口移除；文字颜色来自TextStyle.color，不宣称支持游戏富文本/组件/任意format span。墨迹可超逻辑advance（斜体/描边/阴影），这些效果按原PoppinsTextNode不扩HUD逻辑盒。

## 修改路径

- src/main/java/com/muoniumplayer/configskija/host/text/HostTextRenderer.java
- src/test/java/com/muoniumplayer/configskija/text/HostTextRendererTest.java
- src/test/java/com/muoniumplayer/configskija/text/HostTextContractTest.java
- src/test/java/com/muoniumplayer/configskija/text/run.ps1
- docs/font-parity.md

未更改主项目或原仓、SkijaPainter、SkijaConfigScreen、MuoniumHost及其它队友文件；未提交/推送/启动游戏。

## 冻结写集与源码指纹

本轮仅修改本文件与主题/偏好交接文档；下面四个实现/测试文件保持原字节，作为本子任务冻结写集。项目仍未提交，主线程提交时需明确纳入这些新文件及本文件。没有执行stage、commit、push或游戏启动。

| 路径（相对本工程） | SHA-256（2026-11-24冻结） |
| --- | --- |
| `src/main/java/com/muoniumplayer/configskija/host/text/HostTextRenderer.java` | `2ada0fd2f4c28617156a8a5871e429b802676c13553eba6f57b639c246a4cae9` |
| `src/test/java/com/muoniumplayer/configskija/text/HostTextRendererTest.java` | `02e8d0f2d06cededea43ec3c21d4d601d71fefd9b2b705557a2f5aa4366eba4f` |
| `src/test/java/com/muoniumplayer/configskija/text/HostTextContractTest.java` | `ffc8c68f7cec4d98d8a1bd874309e81ead753d51f422f75f32c3744ac6232092` |
| `src/test/java/com/muoniumplayer/configskija/text/run.ps1` | `b5fe2d522cb7c7f5255135b24194361c4a7cc62f0f2409128c6df2cd92f9073e` |
