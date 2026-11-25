# FFmpeg 音频与视频封面原生配方

适用范围：MuoniumPlayer 的音频解码、容器、重采样及进程内视频封面最小能力；核对日期：2026-11-25。
本目录是独立原生工具链，不是第八棵 Gradle 工程。版本锁为 FFmpeg `8.1.2-1.5.14` / JavaCPP `1.5.14`，共享 Java 基线仍为 17。

**当前分发：Windows x64 内置；其他 ABI 首次使用按需下载。** 本轮源码精简仅覆盖 Windows x64、Linux x64、Android arm64。
Linux arm64、macOS x64/arm64、Android x64 保留按需使用官方 stock classifier 的入口；它们不属于本轮源码精简目标。
`windows-arm64` 没有对应锁定版本 FFmpeg classifier，不声明支持。每个 MC/加载器仍只交付一个 MOD，不再将七 ABI universal 作为当前最终分发。

本次修改的是配方与验收守卫，不代表已经执行 CI、上传候选或通过游戏实测。
此前 Windows Schannel/zlib 四库候选有独立构建和运行证据；其 TLS 后端、四库能力和官方 JNI 复用来源不能代证本次五库 OpenSSL 配方。
父工程及各平台构建者分别记录本轮实际产物与运行结果，不能用历史包大小证明新候选已构建。

## 源码目标与宿主

| JavaCPP classifier | 本配方的构建宿主与方式 | 运行验收边界 |
| --- | --- | --- |
| `windows-x86_64` | Linux + MinGW-w64 POSIX 交叉编译 | Windows / Java 17 加载五库与 JNI，另验动态封面和音频 |
| `linux-x86_64` | Linux x64 / GCC；工作流为 Ubuntu 22.04 | 同 ABI 实际加载、音频/网络、视频解码与 RGBA 转换 |
| `android-arm64` | Linux x64 + NDK `28.2.13676358`，API 24 | CI 只交叉编译；需启动器/真机加载及全部 ELF 的 16 KiB page 验证 |

以上是配方目标，不是本次已执行结果。Linux 的 glibc、官方 JNI 的 C++ runtime/符号要求须按实际产物单独检查，不声称兼容 musl 或全部启动器。
其他 agent 可用独立工具链原生或交叉构建；它们的实际宿主、TLS 后端和 JNI 来源必须保留在对应报告中。
`recipe.py build` / `build-native.sh` 的既有工具链只在 Linux 宿主运行，传入 Linux 原生的工作目录、缓存、JDK/NDK 路径；
不将 Windows 盘符路径交给 Bash，也不猜测 WSL/MSYS 路径。`plan`、`validate-source`、配方测试及 companion 打包可在其他宿主运行。

## 能力与调用边界

[`capabilities.json`](capabilities.json) 生成 configure 白名单：原有 **63 个音频 decoder、27 个 demuxer、10 个 parser、9 个协议保持**，
新增 `h264` / `hevc` decoder 和 parser，合计 65 decoder、12 parser。第五个共享库为 `swscale` major **9**。

| 需求 | 原生能力 | 应用侧责任 |
| --- | --- | --- |
| 下载音乐、本地容器 | MPEG audio、AAC/LATM、FLAC/ALAC、Vorbis/Opus、APE/WavPack、WMA、TAK/TTA、PCM/ADPCM 等原有白名单 | 源身份、只读输入、缓存失效/原子发布、音轨选择、取消和错误处理 |
| 高精度与多声道 | 原始布局/采样率、float PCM；`libswresample` 做必要转换与重映射 | DSP/设备布局、有限队列、输出精度与生命周期 |
| 电台与 HLS | HTTP(S)、ICY、HLS master/media、TS AAC/MP3、fMP4/CMAF、AES-128 `crypto` | 超时、直播边缘、授权头、重连、取消和有界缓冲 |
| 动态视频封面 | JavaCPP 进程内 H.264/HEVC 解码，`libswscale` 缩放/像素格式转换到 RGBA | 限制输入尺寸、帧数、内存、时间，采样与补边，过期结果丢弃及静态封面降级 |
| FLAC 尾 TAG 有界视图 | `subfile` + `file`；锁定版 `subfile.c` 的 start/end | 不改源文件，保持 CRC/STREAMINFO 校验，不加解码 fallback |

动态封面不再以 ffmpeg 子进程、PNG encoder 或 `fps,scale,format,pad` filter graph 作为能力契约。
不启用其他视频/字幕 decoder、PNG 编码、任何 encoder/muxer、avfilter/avdevice、filter/device/hwaccel 或 ffmpeg/ffprobe/ffplay 工具。
保留 MOV/Matroska/MPEG-TS demuxer 本就允许从含视频文件选择音轨；新增视频能力仅限上述两个软件 decoder。

协议仍是 `file,subfile,pipe,http,https,httpproxy,tcp,tls,crypto`，不全开网络协议。
HLS 是 demuxer；AES-128 HLS 不等于 DRM、Widevine/FairPlay、SAMPLE-AES/CENC。
RTMP/RTSP/RTP/UDP/SRT、DASH 和通用 M3U/PLS 列表解析不在此配方承诺范围。

## 精确版本与源码证据

- 锁定源归档与 Java 主 JAR 的 URL/SHA256 见 [`sources.lock.json`](sources.lock.json)；不使用 master 或其他 FFmpeg 版本的声明。
- JavaCPP Presets `1.5.14` 对应 commit `d57d8a28635211e174df378611cb1952f751c943`；其 `ffmpeg/cppbuild.sh` 锁定 FFmpeg 8.1.2 / OpenSSL 3.5.7。
- FFmpeg 8.1.2 `libavcodec/allcodecs.c` / `parsers.c` 分别声明 `ff_h264_decoder`、`ff_hevc_decoder` 与两个对应 parser。
- `libswscale/version_major.h` 声明 `LIBSWSCALE_VERSION_MAJOR 9`；`swscale.h` 提供真实的 `sws_getContext`、`sws_scale`、`sws_freeContext`。
  `validate-source` 核对五库 major；avutil 的声明在 `libavutil/version.h`，另四库在各自 `version_major.h`。
- 匹配 release 的 `org.bytedeco.ffmpeg.presets.swscale` 继承 `avutil`，链接 `swscale@.9`；global 类公开上述 native 调用。
  Java 类裁剪保留 swscale/global/presets 及相关 Graal 配置，仍移除 avfilter/avdevice。
- 五库 major 为 `avutil 60 / swresample 6 / avcodec 62 / avformat 62 / swscale 9`。每库一份 JNI，另有 JavaCPP runtime JNI。
  Linux/Android 共 11 个 native；Windows 另外保留原有 `libwinpthread-1.dll`。Windows API set 与 licensing 路线不由本次修改改变。
- JNI 生成使用固定 release Builder 的真实 `-classpath`、`-properties`、`-Dplatform.*` 参数；显式限制扫描类路径，五模块循环包含 swscale。

固定版本参考：

- [FFmpeg n8.1.2 swscale major](https://github.com/FFmpeg/FFmpeg/blob/n8.1.2/libswscale/version_major.h)
- [FFmpeg n8.1.2 swscale API](https://github.com/FFmpeg/FFmpeg/blob/n8.1.2/libswscale/swscale.h)
- [FFmpeg n8.1.2 configure](https://github.com/FFmpeg/FFmpeg/blob/n8.1.2/configure)
- [Presets 1.5.14 swscale](https://github.com/bytedeco/javacpp-presets/blob/1.5.14/ffmpeg/src/main/java/org/bytedeco/ffmpeg/presets/swscale.java)
- [Presets 1.5.14 cppbuild](https://github.com/bytedeco/javacpp-presets/blob/1.5.14/ffmpeg/cppbuild.sh)
- [JavaCPP 1.5.14 Builder](https://github.com/bytedeco/javacpp/blob/1.5.14/src/main/java/org/bytedeco/javacpp/tools/Builder.java)

## 配置审计与 TLS

关闭 autodetect、默认组件、GPL/nonfree，保留 `--enable-version3`、shared、PIC、small 与 runtime CPU detection。
`audit_config` 拒绝静默缺少白名单能力、缺少五库，以及意外 decoder、encoder/muxer/filter/device/hwaccel/工具。
不使用 fast-math、UPX 或降低音频精度换体积。必需的 FFmpeg 内部依赖由 configure 选择，并记录在构建报告中。

外部库限 OpenSSL `3.5.7` 与 zlib `1.3.1`，静态链接入 FFmpeg 共享库；不加入 libsoxr/libopus 等额外 native。
OpenSSL 关闭 apps/tests/docs/module/engine/legacy/compression，保留现代 TLS 默认 provider。
zlib 1.3.1 是已校验的锁定源；不能因参考预设的其他下载地址失效而擅自改版本。

**默认 DTLS-disabled UDP helper guard 必须保留。** `patches/disabled-dtls-udp-helper-guard.patch` 按来源锁顺序默认应用，
以 `CONFIG_DTLS_PROTOCOL` 保护禁用 DTLS/UDP 时的 helper 引用，保留普通 TLS/HTTPS。
补丁、上游目标和补后文件逐项校验 SHA256；不接受 fuzzy patch。它是默认构建修复，与证书专项分开。

证书身份专项保持 **deferred**，不作为本轮核心 native/CI gate：

- `patches/hls-tls-options.patch` 属于显式 `--tls-hardening` profile，将既有 HTTPS AVIOContext 的信任选项传播给 HLS 子请求。
  初始 HTTP/本地清单切入 HTTPS 仍需要应用逐次注入策略；此补丁不构成全部嵌套 URL 安全保证。
- `patches/openssl-numeric-ip-identity.patch` 同属可选 profile，针对 n8.1.2 OpenSSL 数字地址身份校验；
  原文件/补丁/结果均受锁约束。启用此 profile 时仍按锁顺序应用默认 UDP helper guard 的对应 profile hash。
- `smoke.py test --tls-identity-tests` 为可选专项。OpenSSL 的结论不能推广到 Schannel/GnuTLS，功能 HTTPS smoke 不能代替身份/重定向/HLS key 测试。

运行时仍须管理协议 whitelist、授权头/代理、信任根、interrupt/deadline 与取消；协议被编入不等于应用行为完成。

## 本地命令与候选包

只读计划、真实源声明检查及配方测试，不使用 Gradle：

```text
python -B native/ffmpeg/recipe.py plan --platform windows-x86_64
python -B native/ffmpeg/recipe.py validate-source <已校验的ffmpeg-8.1.2源码目录>
python -B native/ffmpeg/test_recipe.py
```

Linux 宿主源码构建示例；各次使用新的工作目录，先安装对应目标工具链、JDK 和锁定 Android NDK：

```text
python3 native/ffmpeg/recipe.py build --platform linux-x86_64 --work /absolute/path/new-linux-build
python3 native/ffmpeg/recipe.py build --platform windows-x86_64 --work /absolute/path/new-windows-cross-build
python3 native/ffmpeg/recipe.py build --platform android-arm64 --work /absolute/path/new-android-cross-build
```

内部 JAR 文件名沿用 `ffmpeg-audio-candidate-<platform>.jar`，来源标识沿用 `source-built-audio-candidate`，不因这两个历史名称漏掉视频封面能力。
每个候选保留 `META-INF/muonium/ffmpeg/platforms/<platform>.json`，记录逐文件 byte/hash、配置、来源与运行状态。
`bundle` 只用于内部校验三个源码候选的共同 Java/资源装配，不是当前最终 MOD 的分发方案；
要求三目标齐全、11/12 个 native 布局正确、无重复/篡改、同源锁和能力清单、同 optional patch profile，桌面音频 smoke 状态有效。
固定 Java/CA artifact 与仓库内许可文本足够完成内部聚合，不依赖兄弟源码 checkout 或 Minecraft build 产物。

Linux/Android 按需 companion 保持根目录 `build-manifest.json` 和扁平 native 布局：

```text
python -B native/ffmpeg/package-companion.py --candidate <candidate.jar> --platform linux-x86_64 --output <output-directory>
python -B native/ffmpeg/package-companion.py --candidate <candidate.jar> --platform android-arm64 --output <output-directory>
```

打包要求候选包含本 ABI 的源码构建报告、匹配版本、五库能力、11 个完整 ELF64 native 及相符摘要；拒绝 stock/mixed FFmpeg、其他 ABI、额外模块、缺库/JNI和篡改。
逐库记录 major、文件/hash、原来源和编译状态；JNI 分别记录匹配官方复用或锁定 Java 绑定自生成的来源，不能把官方 JNI 复用写成官方 FFmpeg 五库源码精简。
`source/platform-report.json` 保存原始报告，`source/` 保留锁定补丁；确定性 ZIP 文件名带内容 hash，不上传。
这些静态校验只能约束报告和文件的一致性，不替代编译日志、符号闭包及目标机加载证据。

许可原文来自 [`licenses/`](licenses/README.md)，其 inventory 与源锁保留来源及 SHA256，按字节校验/打包；本次未修改 API set 或许可库存。
stock FFmpeg 共享库可能静态包含额外上游依赖，现有库存不构成其他 stock classifier 的完整许可审定。

## CI 与验收状态

[独立 workflow](../../.github/workflows/ffmpeg-native.yml) 保持三个源码目标，使用固定 action SHA，候选与证据为内部 artifact；本次未 dispatch CI、上传或发布。
既有 smoke 覆盖音频/JNI、HTTP(S)、TS/fMP4/AES-128 HLS，并不等于视频封面验收。
当前 scope 外的 `RuntimeProbe.java` 仍按纯音频库存拒绝视频 decoder，须由其负责方适配 H.264/HEVC 白名单后再执行工作流；不能以跳过此守卫冒充 smoke 通过。
五库 JNI 加载、H.264/HEVC 实际解码、RGBA 转换、尺寸/帧数/时间预算、取消/切换、静态降级和资源释放另需实测。

配方测试保留原有缓存/归档安全、来源/完整性、许可和默认/可选补丁逻辑，新增五库布局、视频白名单、ABI 与 companion provenance 拒绝测试。
合成类/ELF 头只验证打包契约，不是可执行 native。`plan` 与 `validate-source` 也不证明编译或运行成功。
共享业务七树测试、十四目标构建与游戏验收由父工程记录；本任务不占 Gradle、不改 Java/Gradle/native binaries。
