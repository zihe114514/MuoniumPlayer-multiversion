# 实际玩家头像迁移契约（2026-11-24）

冻结状态：2026-11-24。按用户要求立即停止扩范围；本轮仅补本交接文档，下面23个实现/测试文件保持原字节。稳定工厂与API不再变化，接线、构建、提交由主线程统一完成，游戏验收交人工。本轮没有重复执行已经通过的编译/行为测试，没有改service/host调用引用。

范围：独立Skija工程的Account实际玩家头像。实现只写host/avatar、各src/versions/<lane>/.../host/avatar、tests/avatar和本文件；没有改service、MuoniumHost、主项目或原只读仓。共享主体Java17。主线程负责将返回PNG字节接到host.playerHead，不能将本任务静态/CPU验证写成游戏视觉验收。

## 主线程接入

```java
// 在常驻service的已有资源加载worker初始化；worker由service管理。
AvatarLoader avatar = AvatarLoader.create(existingAvatarWorker);
// 默认 CACHED_SKIN_ONLY：只用本地已加载皮肤，不主动调用profile skin future或web。

// 不等待、不join；可由worker初始化完成、进世界、账号变更或资源重载事件触发。
avatar.request().thenAccept(result -> {
    // Result里只有UUID、状态、PNG、诊断；不要在此worker回调直接操作UI。
    // UI每帧只读loadCached()，不用为它再new图像/每帧clone数组。
});

// host.playerHead：返回稳定read-only PNG字节（null表示当前尚未有真实头像）。
byte[] actual = avatar.loadCached();
// 若host已有当前UUID，优先按它读，避免账号变化事件尚未触发时显示上一账号：
byte[] currentActual = avatar.loadCached(currentUuid);
AvatarLoader.Profile observedAccount = avatar.profile(); // 非阻塞的最近一次身份快照

// 若采用原版完整fallback，初始化时显式选择；没有自动开启网络。
AvatarLoader original = AvatarLoader.create(existingAvatarWorker,
    AvatarLoader.Mode.ORIGINAL_FALLBACK);

// 请求退出/取消只影响本loader，不关闭service拥有的worker。
avatar.cancel();     // 取消本generation，保留已完成的UUID缓存
avatar.invalidate(); // 取消并清空缓存，用于资源重载
avatar.close();      // service最终退出，幂等；关菜单不关闭常驻loader
```

- `request()`返回CompletableFuture<Result>；重复同UUID请求合并同一在途任务，命中已完成缓存不再读像素/编码/请求。可从客户端/worker调用，但不能拿返回future在客户端join/get。
- `loadCached() / loadCached(UUID) / profile() / lastResult()`是纯内存只读API；返回数组只读并保持身份稳定，以配合Painter的encoded-image缓存。不能外部修改，也不要每帧clone。每个loader最多保留64个UUID，LRU淘汰；原版未限制大小，本实现收敛常驻内存。
- `Profile`只有UUID；不保留GameProfile属性、账号名、session或token。`Result.status`区分READY、NO_PROFILE、NO_READABLE_SKIN、FAILED、CANCELLED；source区分CACHE、LOCAL_SKIN、PROFILE_SKIN、MC_HEADS、NONE。错误诊断不会静默改成已加载。
- 默认cache-only在标题菜单没有mc.player、纹理尚未可读，以及旧HttpTexture车道可能返回NO_READABLE_SKIN。它没有用固定head.png顶替真实缓存，也不声称这个状态已实现头像1:1。原UI fallback只应作为未加载/失败降级；主线程若要求这些场景实际头像，需采用已实现的ORIGINAL_FALLBACK并完成游戏验证。
- 仅在初始化/身份变更/进世界/资源reload等时机触发request；不要每帧对失败条目无休止重试。本实现没有暗设周期轮询或认证重试。

## 原源码完整对照

已完整读取原仓 `muoniumconfig-common/.../internal/ui/PlayerHeadLoader.kt`、`PlayerHeadTextureAccess.java`、`components/PlayerHead.kt`，以及七车道各自 `PlayerHeadSkinAccess.java` 和 `PlayerHeadBlit.java`。web连接参数另核对 `utils/v1/NetworkUtils.java`。原仓根只读：`E:/oneconfig lite/MuoniumConfig-src`。

| 原触点 | 对应实现 | 行为 |
| --- | --- | --- |
| cachedLocalPlayerHeadPng/cache/inFlight | AvatarLoader.loadCached/request | UUID缓存；重复任务合并；只发布完整PNG |
| localPlayerHeadTask | 各车道AvatarSkinAccess.local | client读取当前玩家皮肤，核对UUID |
| profileHeadTask | 各车道AvatarSkinAccess.resolve + worker等待 | client只启动原SkinManager future，worker最多等2秒；不在client等待 |
| onClientThread/submit | AvatarAccess.submit + AvatarLoader.tryClient | 每个client阶段最多5秒；取消只取消自有client任务，不取消游戏共享skin future |
| PlayerHeadTextureAccess.readPixels | 各车道AvatarTextureAccess.copy/copyImage | 仅DynamicTexture.getPixels；借用图像在client复制128个ARGB整数后立即离开 |
| PlayerHeadBlit.extractHead | AvatarPixels.headArgb/composite | face(8,8,8,8)与hat(40,8,8,8)，整数source-over，4倍最近邻→32px |
| NativeImage.writeToFile/临时文件 | AvatarPng.encode | 改为worker CPU BufferedImage+内存PNG encoder，无临时文件；ImageWriter/stream关闭，BufferedImage.flush |
| mc-heads UUID fallback | AvatarWeb | 原固定endpoint、UUID去横线、32.png，connect/read各5秒；仅显式ORIGINAL_FALLBACK |
| 原NativeImage use/晚到close | 不创建分离NativeImage | 不跨线程持有游戏live image；普通数组晚到可GC；游戏图像不由本loader关闭 |

原始顺序：client local（最多5s）→client启动profile future（最多5s）→worker等待profile（最多2s）→client读该texture（最多5s）→worker PNG；只有没有可读像素时才web。profile/client阶段失败会保留诊断，按原fallback顺序继续；encoder失败明确FAILED。

## 每个精确组合的实际依赖/API证据

读取每树gradle.properties、settings.gradle与两侧build.gradle；独立使用全部14份 `build/host/<lane>/<loader>/compile-classpath.txt`，不是用一个loader代证另一loader。加载器版本已和classpath内对应jar核对。旧Fabric走officialMojangMappings/remap；26.x Fabric未混淆不remap；NeoForge为ModDevGradle，1.20.1 Forge为legacyforge；本实现只使用实际public声明，不加AW/AT或Mixin。

| MC车道 | 实际加载器 | NativeImage颜色契约 | texturePath类型/旧车道边界 | Java17目标局部编译 | NativeImage CPU实测 |
| --- | --- | --- | --- | --- | --- |
| `1.20.1 recode` | fabric 0.19.3 | getPixelRGBA→ABGR→ARGB | 不调用旧皮肤future | 独立通过 | 独立通过 |
| `1.20.1 recode` | forge 1.20.1-47.4.0 | getPixelRGBA→ABGR→ARGB | 不调用旧皮肤future | 独立通过 | 独立通过 |
| `1.21.1` | fabric 0.19.3 | getPixelRGBA→ABGR→ARGB | 不调用旧皮肤future | 独立通过 | 独立通过 |
| `1.21.1` | neoforge 21.1.249 | getPixelRGBA→ABGR→ARGB | 不调用旧皮肤future | 独立通过 | 独立通过 |
| `1.21.10` | fabric 0.19.3 | getPixel→ARGB | ResourceLocation | 独立通过 | 独立通过 |
| `1.21.10` | neoforge 21.10.64 | getPixel→ARGB | ResourceLocation | 独立通过 | 独立通过 |
| `1.21.11 recode` | fabric 0.19.3 | getPixel→ARGB | Identifier | 独立通过 | 独立通过 |
| `1.21.11 recode` | neoforge 21.11.45 | getPixel→ARGB | Identifier | 独立通过 | 独立通过 |
| `26.1.2` | fabric 0.19.3 | getPixel→ARGB | Identifier | 独立通过 | 独立通过 |
| `26.1.2` | neoforge 26.1.2.126 | getPixel→ARGB | Identifier | 独立通过 | 独立通过 |
| `26.2` | fabric 0.19.3 | getPixel→ARGB | Identifier | 独立通过 | 独立通过 |
| `26.2` | neoforge 26.2.0.72 | getPixel→ARGB | Identifier | 独立通过 | 独立通过 |
| `26.3` | fabric 0.19.5 | getPixel→ARGB | Identifier | 独立通过 | 独立通过 |
| `26.3` | neoforge 26.3.0.16-beta | getPixel→ARGB | Identifier | 独立通过 | 独立通过 |

**精确声明**（本次14份javap输出均已分别核对）：

- Minecraft.getInstance(): Minecraft；Minecraft.getUser(): User；User.getProfileId(): UUID；BlockableEventLoop.isSameThread(): boolean；`<V> CompletableFuture<V> submit(Supplier<V>)`。submit的真实字节码显示client时inline、否则排队；工厂不创建新客户端线程。
- 1.20.1/1.21.1两侧：HttpTexture extends SimpleTexture extends AbstractTexture，没有DynamicTexture继承关系；原车道SkinAccess亦直接返回null。本实现不猜旧SkinManager.get(profile)，也不把1.21.1真实存在但不同契约的getOrLoad当新版get替用。
- 五个现代MC车道两侧：AbstractClientPlayer.getSkin(): net.minecraft.world.entity.player.PlayerSkin；PlayerSkin.body(): ClientAsset.Texture；texturePath见表；Minecraft.getGameProfile(): GameProfile；`SkinManager.get(GameProfile): CompletableFuture<Optional<PlayerSkin>>`。future回调只构造读取closure，不读native像素。
- TextureManager.getTexture(本车道Identifier/ResourceLocation): AbstractTexture；DynamicTexture.getPixels(): NativeImage；NativeImage.getWidth/getHeight: int。1.20.1/1.21.1用getPixelRGBA(int,int)，字节码从RGBA内存int读ABGR；现代getPixel(int,int)字节码调用ARGB.fromABGR，不能再交换红蓝。
- Face/hat坐标对至少48×16皮肤读128个像素；异常/短图均不把不完整像素当真实头像。已生成 `build/avatar/evidence/*-declarations.txt`、`*-bytecode.txt`、`*-texture-contract.txt` 与combinations.json。可复现run.ps1 -InspectApi会再生成每组合的api-declarations.txt/pixel-thread-bytecode.txt。

## 网络、线程和释放边界

- 默认不访问网络。显式原版fallback的唯一应用发起地址是 `https://mc-heads.net/avatar/<UUID去横线>/32.png`，只发送UUID路径和固定User-Agent/Accept；不新增认证、不提交任何表单/账号名/token/profile properties。游戏SkinManager本身的行为仍由原游戏管理，不替换session service。
- AvatarWeb在worker GET，connect/read均5000ms，按原NetworkUtils固定User-Agent；不跟随重定向以免扩到未指定endpoint，不发送GET body。响应限定1MiB，必须解码为32×32 PNG；gzip解压也受上限保护。ImageReader/input stream关闭，BufferedImage.flush；HttpURLConnection最终disconnect。
- close/cancel/账号变化会递增generation、取消自有任务并打断worker等待。每个client closure读前校验generation/UUID，PNG发布前再client核对当前UUID；旧账号、晚到任务和退出后结果不能覆盖新缓存。已完成旧UUID可在该loader内缓存至invalidate/close或LRU淘汰，UI应按当前UUID读取。
- HTTP阻塞中的底层读可能等至5秒read timeout才返回；close不在render等待它，返回后仍会被generation拒绝发布。不能把Future.cancel当成已经终止所有底层网络/游戏skin任务的保证。
- borrowed NativeImage不close、不上传、不从GPU读取；本实现没有新NativeImage/Texture/Surface/GL对象。worker不调用任何NativeImage方法；没有额外native loader。client阶段只复制普通数组，合成/PNG压缩/网络全在worker。

## 验证与交接

```powershell
# 本工程根；逐14实际classpath局部编译，独立异步/像素合成回归：
.\tests\avatar\run.ps1
# 再运行全部14原生图像颜色/所有权CPU检查并输出API证据：
.\tests\avatar\run.ps1 -NativePixels -InspectApi
```

当前完整run已通过：14/14局部Java17目标编译、14/14 NativeImage CPU像素/借用所有权验证；独立Java17无网回归1085条断言、契约/API/资源守卫107条断言通过。JDK25执行javac --release17；原生CPU测试使用车道运行基线JDK17/21/25。fixture UUID仅用于mock transport；未对实际玩家或mc-heads发出网络请求。

未运行游戏、未打整仓包、未commit/push；client真实皮肤是否为可读DynamicTexture、进出世界/账号变更通知时机和Account视觉更新仍须主线程集成及人工游戏验收。cache-only的旧HttpTexture和无玩家场景明确降级，不伪称实际头像1:1已通过。

补充边界：原PlayerHeadLoader在profileId不可得时还会尝试无UUID的本地skin；本实现无UUID直接NO_PROFILE，不向任意未知身份发布或请求web。默认仅游戏缓存模式不保证原版所有登录/标题页场景头像可用，主线程可显式选择原fallback；真实网络可用性未进行在线测试。

## 本次修改写集

以下文件均在用户指定范围内；本任务未改MainService/SkijaConfigService/MuoniumHost或既有头像调用方。测试产物、API输出、日志、LWJGL测试提取文件只保留于本工程build/avatar，不作为提交写集。

- `src/main/java/com/muoniumplayer/configskija/host/avatar/AvatarAccess.java`
- `src/main/java/com/muoniumplayer/configskija/host/avatar/AvatarLoader.java`
- `src/main/java/com/muoniumplayer/configskija/host/avatar/AvatarPixels.java`
- `src/main/java/com/muoniumplayer/configskija/host/avatar/AvatarPng.java`
- `src/main/java/com/muoniumplayer/configskija/host/avatar/AvatarWeb.java`
- `src/versions/1.20.1 recode/java/com/muoniumplayer/configskija/host/avatar/AvatarSkinAccess.java`
- `src/versions/1.20.1 recode/java/com/muoniumplayer/configskija/host/avatar/AvatarTextureAccess.java`
- `src/versions/1.21.1/java/com/muoniumplayer/configskija/host/avatar/AvatarSkinAccess.java`
- `src/versions/1.21.1/java/com/muoniumplayer/configskija/host/avatar/AvatarTextureAccess.java`
- `src/versions/1.21.10/java/com/muoniumplayer/configskija/host/avatar/AvatarSkinAccess.java`
- `src/versions/1.21.10/java/com/muoniumplayer/configskija/host/avatar/AvatarTextureAccess.java`
- `src/versions/1.21.11 recode/java/com/muoniumplayer/configskija/host/avatar/AvatarSkinAccess.java`
- `src/versions/1.21.11 recode/java/com/muoniumplayer/configskija/host/avatar/AvatarTextureAccess.java`
- `src/versions/26.1.2/java/com/muoniumplayer/configskija/host/avatar/AvatarSkinAccess.java`
- `src/versions/26.1.2/java/com/muoniumplayer/configskija/host/avatar/AvatarTextureAccess.java`
- `src/versions/26.2/java/com/muoniumplayer/configskija/host/avatar/AvatarSkinAccess.java`
- `src/versions/26.2/java/com/muoniumplayer/configskija/host/avatar/AvatarTextureAccess.java`
- `src/versions/26.3/java/com/muoniumplayer/configskija/host/avatar/AvatarSkinAccess.java`
- `src/versions/26.3/java/com/muoniumplayer/configskija/host/avatar/AvatarTextureAccess.java`
- `tests/avatar/AvatarContractTest.java`
- `tests/avatar/AvatarLoaderTest.java`
- `tests/avatar/AvatarNativePixelsTest.java`
- `tests/avatar/run.ps1`
- `docs/avatar-parity.md`

## 冻结写集指纹

写集共24个文件：下表23个实现/测试文件及docs/avatar-parity.md。本轮只改本文件；下列SHA-256用于核对冻结源码，不含build/avatar的产物、日志和API证据。已完成的局部结果为14/14精确组合Java17目标编译、14/14原生像素CPU契约、1085条异步/合成断言及107条契约守卫。没有进行真实账户网络或游戏实测，不将该结果标成头像1:1验收通过。

默认CACHED_SKIN_ONLY的旧HttpTexture、无玩家和像素不可读场景会返回明确非READY状态；ORIGINAL_FALLBACK的profile及mc-heads顺序已实现，但线上连通性/账户切换的游戏行为未验证。没有用固定head.png填入真实头像缓存，也没有增加空实现能力。

| 路径（相对本工程） | SHA-256 |
| --- | --- |
| `src/main/java/com/muoniumplayer/configskija/host/avatar/AvatarAccess.java` | `1d087a214722921fb5b1932d0668f44011a3706dd9bffce4ed0583ba044c992d` |
| `src/main/java/com/muoniumplayer/configskija/host/avatar/AvatarLoader.java` | `f0b21f8735a3d6cf550c48f3cb351a45c4f7042026f66470df950c2c93365a3d` |
| `src/main/java/com/muoniumplayer/configskija/host/avatar/AvatarPixels.java` | `1bf1fbfe723a6b6f061fa5278c70441d6b943dc94f20c5a56b56df9329db54a0` |
| `src/main/java/com/muoniumplayer/configskija/host/avatar/AvatarPng.java` | `a7034181440e1cafd12b3ea51385ba09faa900bf525226ee42dea48d4c128965` |
| `src/main/java/com/muoniumplayer/configskija/host/avatar/AvatarWeb.java` | `91673f482a5557829f74f2649e79a6c51b72379f1191202bf56e4967ff9deca2` |
| `src/versions/1.20.1 recode/java/com/muoniumplayer/configskija/host/avatar/AvatarSkinAccess.java` | `e4db5be8333d7b47c30399d55470e375d21b7efd7054d034d54118bbc9369888` |
| `src/versions/1.20.1 recode/java/com/muoniumplayer/configskija/host/avatar/AvatarTextureAccess.java` | `b69e3fc74053cf222d955031aac3cd466d877b84317293fb09bfd64ed41af527` |
| `src/versions/1.21.1/java/com/muoniumplayer/configskija/host/avatar/AvatarSkinAccess.java` | `6bc1b068b568b2df4bc6a476994a22745cd4f1c1572d4c4808edc4072b86dcdc` |
| `src/versions/1.21.1/java/com/muoniumplayer/configskija/host/avatar/AvatarTextureAccess.java` | `b69e3fc74053cf222d955031aac3cd466d877b84317293fb09bfd64ed41af527` |
| `src/versions/1.21.10/java/com/muoniumplayer/configskija/host/avatar/AvatarSkinAccess.java` | `e2d52233e5c51a60dcf23e79d24f6d63a8ec6dbb5a81c653f2d655af919a2e35` |
| `src/versions/1.21.10/java/com/muoniumplayer/configskija/host/avatar/AvatarTextureAccess.java` | `6d284ea82c7132f5db195e3117423f573714c4fa968ed2d089b04e76ccc5c790` |
| `src/versions/1.21.11 recode/java/com/muoniumplayer/configskija/host/avatar/AvatarSkinAccess.java` | `33e0c4bfc4d88416eb09dd8c5fa66a42844502cf59c3ae147ee085ca2fbd2bd0` |
| `src/versions/1.21.11 recode/java/com/muoniumplayer/configskija/host/avatar/AvatarTextureAccess.java` | `6d284ea82c7132f5db195e3117423f573714c4fa968ed2d089b04e76ccc5c790` |
| `src/versions/26.1.2/java/com/muoniumplayer/configskija/host/avatar/AvatarSkinAccess.java` | `d6e84ff2addf0778977e1cc20f3ad54551f8e4f19f80d6aee0b89708f1aa7eb4` |
| `src/versions/26.1.2/java/com/muoniumplayer/configskija/host/avatar/AvatarTextureAccess.java` | `6d284ea82c7132f5db195e3117423f573714c4fa968ed2d089b04e76ccc5c790` |
| `src/versions/26.2/java/com/muoniumplayer/configskija/host/avatar/AvatarSkinAccess.java` | `db632e57131dc6aabc6c0f5aee6d891f8846614f1db2a92e3d9c271517729c53` |
| `src/versions/26.2/java/com/muoniumplayer/configskija/host/avatar/AvatarTextureAccess.java` | `6d284ea82c7132f5db195e3117423f573714c4fa968ed2d089b04e76ccc5c790` |
| `src/versions/26.3/java/com/muoniumplayer/configskija/host/avatar/AvatarSkinAccess.java` | `6f921728ce5de508fcb709b17b56a247e1755b0c0cc4415304ee01c4aa2aca26` |
| `src/versions/26.3/java/com/muoniumplayer/configskija/host/avatar/AvatarTextureAccess.java` | `6d284ea82c7132f5db195e3117423f573714c4fa968ed2d089b04e76ccc5c790` |
| `tests/avatar/AvatarContractTest.java` | `16a37fd72c02f1b9e8facfccd7404a28a7178aa1f868a2dc5b9a429601bcce82` |
| `tests/avatar/AvatarLoaderTest.java` | `85e313ffe6c322f0207088e6b4e95066b6456559a2c2b9d9830c2b435cf73b14` |
| `tests/avatar/AvatarNativePixelsTest.java` | `ab61e336fba0bc1a8985246eab0b9cfa7369e783ac03131efd66aca81d8bd3e0` |
| `tests/avatar/run.ps1` | `88c88b5877a7eae48aa3b2deeb4617c8ec53a36a40b53756a122e354ddd235c3` |
