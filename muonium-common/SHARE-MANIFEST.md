# 共享层清单（tools/share-trees.py 生成，别手改统计数字） 参考树：`26.3`。分类规则见 `tools/share-trees.py` 顶部注释。 | 根 | 路径总数 | 进共享 | 留守 |
| --- | --- | --- | --- |
| `src/main/java` | 861 | 827 | 34 |
| `src/main/resources` | 55 | 52 | 2 + 清垃圾 1 |
| `src/test/java` | 121 | 108 | 13 |
| 合计 | | 987 | 49 | ## 留在各棵树的文件（版本相关，改动要逐树确认） ### `src/main/java` | 文件 | 形态 | 存在树 | 原因 |
| --- | --- | --- | --- |
| `com/muoniumplayer/MuoniumPlayerClient.java` | FORK | 五树都有 | KeyMapping 构造（KEYSYM vs KEYBOARD scancode）、按键类别 API 世代、MC_VERSION 常量 |
| `com/muoniumplayer/compat/gl/GL11.java` | FORK | 五树都有 | LWJGL 2/3 的 GL 常量与方法签名差异 |
| `com/muoniumplayer/compat/gl/GL14.java` | FORK | 五树都有 | LWJGL 2/3 的 GL 常量与方法签名差异 |
| `com/muoniumplayer/compat/lwjgl2/Display.java` | FORK | 五树都有 | 窗口/刷新率 API：1.20.1-1.21.11 与 26.x 不同形 |
| `com/muoniumplayer/compat/lwjgl2/Keyboard.java` | FORK | 五树都有 | key code 表与 isKeyDown 的类名差异 |
| `com/muoniumplayer/compat/lwjgl2/Mouse.java` | FORK | 五树都有 | 鼠标按钮数量与获取位置的 API 差异 |
| `com/muoniumplayer/compat/lwjgl2/Sys.java` | FORK | 五树都有 | 剪贴板 API 的类路径差异 |
| `com/muoniumplayer/compat/mc/FontRenderer.java` | FORK | 五树都有 | drawString 参数与字体度量 API 差异 |
| `com/muoniumplayer/compat/mc/GuiButton.java` | FORK | 五树都有 | 按钮按下回调签名差异 |
| `com/muoniumplayer/compat/mc/GuiScreen.java` | FORK | 五树都有 | keyPressed/charTyped 事件类型差异（1.20.1 起改 record） |
| `com/muoniumplayer/compat/mc/GuiTextField.java` | FORK | 五树都有 | 文本框事件与绘制 API 差异 |
| `com/muoniumplayer/compat/mc/ScaledResolution.java` | FORK | 五树都有 | 26.x 改名为桌面缩放体系 |
| `com/muoniumplayer/compat/mc/ScreenCapture.java` | FORK | 五树都有 | 截帧 API：26.x 走 renderpearl |
| `com/muoniumplayer/compat/mc/CameraAccess.java` | FORK | 五树都有 | 3D 世界歌词：相机/玩家读取按版本 API 各树一份（共享层只放语义） |
| `com/muoniumplayer/compat/mc/ListenerProbe.java` | FORK | 五树都有 | 场景声学：玩家环境采样（水下/天光/降水/方块射线）按版本 API 各树一份（共享层只放纯数据） |
| `com/muoniumplayer/compat/mc/WorldBillboard.java` | FORK | 五树都有 | 3D 世界歌词：BufferSource/VertexConsumer/RenderType 按版本图形接口各树一份 |
| `com/muoniumplayer/compat/mc/WorldLyricsBridge.java` | FORK | 五树都有 | 3D 世界歌词：世界渲染事件入口随加载器与版本不同形 |
| `com/muoniumplayer/compat/mc/LyricGlyphAtlasBridge.java` | FORK | 五树都有 | 3D 世界歌词现代化档：字形图集页纹理宿主（整页分配 + 子矩形上传 + LINEAR 过滤保超采样），按版本纹理接口各树一份 |
| `com/muoniumplayer/compat/mc/ShaderPackProbe.java` | FORK | 五树都有 | 光影包适配：Iris/Oculus/Embeddium 的 ShaderPack 与 RenderState 访问路径随版本/加载器各树一份 |
| `com/muoniumplayer/compat/mc/BloomComposite.java` | FORK | 五树都有 | 3D 歌词辉光后处理管线本体（裸 GL 3.2 core）：熔断日志走各树 Bridge/HudDraw 的 traceOnce |
| `com/muoniumplayer/compat/mc/HudBloomPass.java` | FORK | 1.20.1/1.21.1/1.21.11 | HUD 直画通路的辉光调度：26.x 的 bloom 在 present 落屏端（drawToFbo0），此件仅 1.20.1-1.21.11 需要 |
| `com/muoniumplayer/core/rendering/IslandAmbientStatus.java` | FORK | 五树都有 | 26.x 取当前屏幕改用 mc.gui.screen() |
| `com/muoniumplayer/core/rendering/IslandVisibilityGate.java` | FORK | 五树都有 | 26.x 取当前屏幕改用 mc.gui.screen() |
| `com/muoniumplayer/core/screens/GuiAnnouncements.java` | FORK | 五树都有 | 26.x 关闭屏幕改用 mc.gui.setScreen(null) |
| `com/muoniumplayer/core/screens/GuiFfmpegNotice.java` | FORK | 五树都有 | 26.x 的屏幕访问/关闭 API 差异 |
| `com/muoniumplayer/core/screens/GuiUpdateNotice.java` | FORK | 五树都有 | 26.x 的屏幕访问/关闭 API 差异 |
| `com/muoniumplayer/core/screens/RenderLayerNotice.java` | FORK | 五树都有 | 老树回调给 GuiGraphics、26.x 给 GuiGraphicsExtractor，文字方法 drawString/text 两代不同 |
| `com/muoniumplayer/core/screens/TitleMenuAnnouncementButton.java` | FORK | 五树都有 | 26.x 的屏幕访问与按钮挂载 API 差异 |
| `com/muoniumplayer/core/screens/hud/HudSettingsModel.java` | FORK | 五树都有 | 26.x 打开公告页改用 mc.gui.screen() |
| `com/muoniumplayer/core/screens/ncm/CoverflowOverlay.java` | FORK | 五树都有 | 同上：老树这里没有乘 HudConfig.playerFadeSpeed |
| `com/muoniumplayer/core/settings/HudConfig.java` | FORK | 五树都有 | 1.20.1 / 1.21.1 还没有界面淡入速度旋钮（playerFadeSpeed / playerUiSwitchSpeed） |
| `com/muoniumplayer/core/settings/HudSetting.java` | FORK | 五树都有 | 同上：枚举项 PLAYER_FADE_SPEED / PLAYER_UI_SWITCH_SPEED 只在 1.21.11+ 存在 |
| `com/muoniumplayer/mixin/FramebufferMixin.java` | ONLY | 1.21.11 | mixin 注入点按版本独有，本就各树一份 |
| `com/muoniumplayer/mixin/GlSurfaceMixin.java` | ONLY | 26.2、26.3 | mixin 注入点按版本独有，本就各树一份 |
| `com/muoniumplayer/mixin/MouseScrollMixin.java` | ONLY | 26.3 | mixin 注入点按版本独有，本就各树一份 |
| `com/muoniumplayer/mixin/MouseButtonStateMixin.java` | ONLY | 26.3 | mixin 注入点按版本独有（26.3 的 SDL 后端里 MouseHandler 三布尔在 Screen 打开期间不更新，按住状态只能自投），本就各树一份 |
| `com/muoniumplayer/party/net/MpPayloads.java` | ONLY | 1.21.1、1.21.11、26.2、26.3 | 一起听的载荷包装按各树网络通道 API 而活，1.20.1 那棵用的是另一套 |
| `com/muoniumplayer/render/skija/SkijaHost.java` | FORK | 五树都有 | GlStateManager 类路径（1.20.1 blaze3d.platform / 1.21.11+ renderpearl.backend.opengl）与 GpuDevice 归属 |
| `com/muoniumplayer/api/impl/GLStateManagerImpl.java` | FORK | 五树都有 | GL 状态机类路径与矩阵栈 API 差异 |
| `com/muoniumplayer/api/impl/OpenAPIImpl.java` | FORK | 五树都有 | 取屏幕/画布/字体度量的 API 差异 | ### `src/main/resources` | 文件 | 形态 | 存在树 | 原因 |
| --- | --- | --- | --- |
| `assets/muonium/lang/en_us.json` | FORK | 五树都有 | lang 文件的键位随版本 API 变动，留各树 |
| `assets/muonium/lang/zh_cn.json` | FORK | 五树都有 | lang 文件的键位随版本 API 变动，留各树 | ### `src/test/java` | 文件 | 形态 | 存在树 | 原因 |
| --- | --- | --- | --- |
| `com/muoniumplayer/LoaderDependencyStaticTest.java` | ONLY | 1.21.11、26.2、26.3 | 只在该树存在（移植进度 / 版本独有断言） |
| `com/muoniumplayer/MultiLoaderStaticTest.java` | ONLY | 1.20.1、1.21.11、26.2、26.3 | 只在该树存在（移植进度 / 版本独有断言） |
| `com/muoniumplayer/Port1211StaticTest.java` | ONLY | 1.21.1 | 只在该树存在（移植进度 / 版本独有断言） |
| `com/muoniumplayer/compat/lwjgl2/Lwjgl2KeycodeProbeTest.java` | FORK | 五树都有 | keycode 探针按各版本真实表断言 |
| `com/muoniumplayer/core/rendering/animation/AnimationSpeedStaticTest.java` | ONLY | 1.21.11、26.2、26.3 | 只在该树存在（移植进度 / 版本独有断言） |
| `com/muoniumplayer/core/screens/hud/FoliaPanelRasterProbeTest.java` | ONLY | 1.21.11 | 只在该树存在（移植进度 / 版本独有断言） |
| `com/muoniumplayer/core/settings/PlayerFadeSpeedProbeTest.java` | ONLY | 1.21.1 | 只在该树存在（移植进度 / 版本独有断言） |
| `com/muoniumplayer/core/update/UpdateCheckStaticTest.java` | FORK | 五树都有 | 更新检查读的是各树自己的构建脚本 |
| `com/muoniumplayer/render/skija/Port121StaticTest.java` | ONLY | 1.21.11 | 只在该树存在（移植进度 / 版本独有断言） |
| `com/muoniumplayer/render/skija/Port262StaticTest.java` | ONLY | 26.2 | 只在该树存在（移植进度 / 版本独有断言） |
| `com/muoniumplayer/render/skija/Port263StaticTest.java` | ONLY | 26.3 | 只在该树存在（移植进度 / 版本独有断言） |
| `com/muoniumplayer/render/skija/RasterFrames.java` | ONLY | 1.21.11 | 只在该树存在（移植进度 / 版本独有断言） |
| `com/muoniumplayer/render/skija/RenderLayerStaticTest.java` | FORK | 五树都有 | 渲染层按版本断言不同的宿主 API | ## 注释归一的文件（代码逐字节相同，统一取 26.3 的注释） * `com/muoniumplayer/core/MuoniumPlayerExtension.java`（与 26.3 不同的树：1.20.1、1.21.1、1.21.11）
* `com/muoniumplayer/core/MusicHotkeys.java`（与 26.3 不同的树：1.20.1、1.21.1、1.21.11）
* `com/muoniumplayer/core/management/FontManager.java`（与 26.3 不同的树：1.21.11）
* `com/muoniumplayer/core/management/UiFontLibrary.java`（与 26.3 不同的树：1.21.11）
* `com/muoniumplayer/core/ncm/music/AudioPlayer.java`（与 26.3 不同的树：1.21.11）
* `com/muoniumplayer/core/ncm/music/NeteaseGreyUnblocker.java`（与 26.3 不同的树：1.21.11）
* `com/muoniumplayer/core/rendering/Image.java`（与 26.3 不同的树：1.21.11）
* `com/muoniumplayer/core/rendering/MusicBrandIcons.java`（与 26.3 不同的树：1.21.11）
* `com/muoniumplayer/core/rendering/animation/Interpolations.java`（与 26.3 不同的树：1.20.1、1.21.1、1.21.11）
* `com/muoniumplayer/core/rendering/font/CFontRenderer.java`（与 26.3 不同的树：1.21.11）
* `com/muoniumplayer/core/rendering/ui/container/ScrollPanel.java`（与 26.3 不同的树：1.21.11）
* `com/muoniumplayer/core/rendering/ui/widgets/ThemedTextureIconWidget.java`（与 26.3 不同的树：1.21.11）
* `com/muoniumplayer/core/screens/ncm/MusicLyricsPanel.java`（与 26.3 不同的树：1.21.1）
* `com/muoniumplayer/core/utils/KeyboardUtils.java`（与 26.3 不同的树：1.21.11）
* `com/muoniumplayer/core/utils/logging/Logger.java`（与 26.3 不同的树：1.21.11）
* `com/muoniumplayer/core/widget/impl/MusicLyricsWidget.java`（与 26.3 不同的树：1.21.11）
* `repackage/be/tarsos/dsp/SilenceDetector.java`（与 26.3 不同的树：1.21.11）
* `repackage/be/tarsos/dsp/beatroot/BeatRootTracker.java`（与 26.3 不同的树：1.21.11）
* `repackage/com/jsyn/engine/SynthesisEngine.java`（与 26.3 不同的树：1.21.11）
* `repackage/com/jsyn/ports/InputMixingBlockPart.java`（与 26.3 不同的树：1.21.11）
* `repackage/com/jsyn/ports/SequentialDataCrossfade.java`（与 26.3 不同的树：1.21.11）
* `repackage/com/jsyn/ports/UnitDataQueuePort.java`（与 26.3 不同的树：1.21.11）
* `repackage/com/jsyn/unitgen/UnitGenerator.java`（与 26.3 不同的树：1.21.11）
* `repackage/com/jsyn/util/soundfile/AIFFFileParser.java`（与 26.3 不同的树：1.21.11）
* `repackage/com/jsyn/util/soundfile/IFFParser.java`（与 26.3 不同的树：1.21.11）
* `repackage/com/jsyn/util/soundfile/WAVEFileParser.java`（与 26.3 不同的树：1.21.11）
* `repackage/com/jsyn/util/soundfile/streamed/buffered/BufferedIFFParser.java`（与 26.3 不同的树：1.21.11）
* `repackage/com/jsyn/util/soundfile/streamed/buffered/BufferedSampleLoader.java`（与 26.3 不同的树：1.21.11）
* `repackage/com/jsyn/util/soundfile/streamed/buffered/BufferedWAVEFileParser.java`（与 26.3 不同的树：1.21.11）
* `repackage/com/jsyn/util/soundfile/streamed/raf/RafIFFParser.java`（与 26.3 不同的树：1.21.11）
* `repackage/com/jsyn/util/soundfile/streamed/raf/RafWAVEFileParser.java`（与 26.3 不同的树：1.21.11）
* `repackage/javazoom/jl/converter/RiffStream.java`（与 26.3 不同的树：1.21.11）
* `repackage/javazoom/jl/converter/WaveFile.java`（与 26.3 不同的树：1.21.11）
* `repackage/javazoom/jl/converter/WaveStream.java`（与 26.3 不同的树：1.21.11）
* `repackage/javazoom/jl/decoder/Bitstream.java`（与 26.3 不同的树：1.21.11）
* `repackage/javazoom/jl/decoder/Header.java`（与 26.3 不同的树：1.21.11）
* `repackage/javazoom/jl/decoder/LayerIIDecoder.java`（与 26.3 不同的树：1.21.11）
* `repackage/javazoom/jl/decoder/LayerIIIDecoder.java`（与 26.3 不同的树：1.21.11）
* `repackage/javazoom/jl/decoder/SynthesisFilter.java`（与 26.3 不同的树：1.21.11）
* `repackage/net/sourceforge/jaad/aac/ps/PS.java`（与 26.3 不同的树：1.21.11）
* `repackage/net/sourceforge/jaad/aac/sbr/AnalysisFilterbank.java`（与 26.3 不同的树：1.21.11）
* `repackage/net/sourceforge/jaad/aac/sbr/NoiseEnvelope.java`（与 26.3 不同的树：1.21.11）
* `repackage/net/sourceforge/jaad/aac/sbr/SBR.java`（与 26.3 不同的树：1.21.11）
* `repackage/net/sourceforge/jaad/aac/sbr/SynthesisFilterbank.java`（与 26.3 不同的树：1.21.11）
* `repackage/net/sourceforge/jaad/mp4/boxes/impl/DataReferenceBox.java`（与 26.3 不同的树：1.21.11）
* `repackage/org/kc7bfi/jflac/FLACDecoder.java`（与 26.3 不同的树：1.21.11）
* `repackage/org/kc7bfi/jflac/FLACEncoder.java`（与 26.3 不同的树：1.21.11）
* `repackage/org/kc7bfi/jflac/FixedPredictor.java`（与 26.3 不同的树：1.21.11）
* `repackage/org/kc7bfi/jflac/LPCPredictor.java`（与 26.3 不同的树：1.21.11）
* `repackage/org/kc7bfi/jflac/frame/BadHeaderException.java`（与 26.3 不同的树：1.21.11）
* `repackage/org/kc7bfi/jflac/frame/ChannelLPC.java`（与 26.3 不同的树：1.21.11）
* `repackage/org/kc7bfi/jflac/frame/EntropyPartitionedRice.java`（与 26.3 不同的树：1.21.11）
* `repackage/org/kc7bfi/jflac/frame/EntropyPartitionedRice2.java`（与 26.3 不同的树：1.21.11）
* `repackage/org/kc7bfi/jflac/frame/Header.java`（与 26.3 不同的树：1.21.11）
* `repackage/org/kc7bfi/jflac/io/BitInputStream.java`（与 26.3 不同的树：1.21.11）
* `repackage/org/kc7bfi/jflac/io/BitOutputStream.java`（与 26.3 不同的树：1.21.11）
* `repackage/org/kc7bfi/jflac/metadata/VorbisComment.java`（与 26.3 不同的树：1.21.11）
* `repackage/org/kc7bfi/jflac/sound/spi/Flac2PcmAudioInputStream.java`（与 26.3 不同的树：1.21.11）
* `repackage/org/kc7bfi/jflac/sound/spi/FlacAudioFileReader.java`（与 26.3 不同的树：1.21.11）
* `repackage/org/kc7bfi/jflac/sound/spi/FlacFormatConversionProvider.java`（与 26.3 不同的树：1.21.11）
* `repackage/org/kc7bfi/jflac/sound/spi/RingedAudioInputStream.java`（与 26.3 不同的树：1.21.11）
* `repackage/org/kc7bfi/jflac/util/CRC16.java`（与 26.3 不同的树：1.21.11）
* `repackage/org/kc7bfi/jflac/util/CRC8.java`（与 26.3 不同的树：1.21.11）
* `repackage/org/kc7bfi/jflac/util/PCMDecoder.java`（与 26.3 不同的树：1.21.11）
* `repackage/org/kc7bfi/jflac/util/WavWriter.java`（与 26.3 不同的树：1.21.11）
* `repackage/processing/sound/AudioSample.java`（与 26.3 不同的树：1.21.11）
* `repackage/processing/sound/Engine.java`（与 26.3 不同的树：1.21.11）
* `repackage/processing/sound/SoundFile.java`（与 26.3 不同的树：1.21.11）
* `repackage/processing/sound/Waveform.java`（与 26.3 不同的树：1.21.11）
* `repackage/processing/sound/WsolaStretchSample.java`（与 26.3 不同的树：1.21.11）
* `repackage/processing/sound/WsolaTimeStretch.java`（与 26.3 不同的树：1.21.11）
* `com/muoniumplayer/api/impl/VanillaFont.java`（与 26.3 不同的树：1.20.1、1.21.1、1.21.11）
* `com/muoniumplayer/core/screens/hud/HudSettingsTaxonomyStaticTest.java`（与 26.3 不同的树：1.20.1、1.21.1、1.21.11、26.2） ## 分类判「该留守」但事后收编进来的 * `com/muoniumplayer/testsupport/ProjectPaths.java` —— 测试路径帮助类：加载器子工程名靠 loaderDir() 现场探（1.20.1 是 forge/，其余 neoforge/），于是能收成一份；共享层测试靠它同时定位「共享根 + 本树根」。
* 同一批还新增了 `com/muoniumplayer/SharedLayoutStaticTest.java`（共享层形状守卫），它本来就只该有一份。 ## 改这里的规矩 * 共享层是五棵树的同一份编译输入：改一次，五棵树同时生效。
* 共享层里不许出现 `net.fabricmc` / `net.neoforged` / `net.minecraftforge` / `cpw.mods`， 也不许直接调用只在新版本存在的 MC API —— 那类代码进各树的 `compat/`。
* 需要版本差异时，在 `compat/` 加一个方法（各树同名不同实现），共享层只调同名方法； 别把整个文件从共享层搬回树里，那等于把同步负担又请回来了。
* 搬完必须跑 `python tools/share-trees.py verify`。 