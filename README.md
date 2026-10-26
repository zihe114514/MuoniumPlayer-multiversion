# MuoniumPlayer **在 Minecraft 里听歌。** 手机音乐 App 般的播放器界面、逐字歌词与 3D 世界歌词、灵动岛 HUD、一起听房间、智能混音过渡、ViPER 音效 —— 全部画在游戏里,不弹浏览器、不切窗口。 支持 5 个 Minecraft 版本 × Fabric / NeoForge(Forge),电脑与手机启动器都能跑。 ## 功能一览 **🎵 多音源,一个播放器**
- 网易云音乐、QQ音乐、酷狗、汽水音乐(抖音)、哔哩哔哩、YouTube、Spotify,支持各平台账号登录(歌单/VIP 音质/每日推荐/私人 FM)
- LX Music 自定义源脚本(酷我、咪咕等渠道由此接入)
- 全渠道导入歌单:粘贴链接自动识别平台
- 版权/地区放不出来?自动换源播放,同队列无缝衔接 **🎤 歌词**
- HUD 歌词(游戏内悬浮)与桌面歌词(Blossom 浮动挂件)
- 全屏歌词页:流体背景、未到行景深模糊、逐字点亮、卡拉 OK 效果
- 3D 世界歌词:歌词浮在世界里,多种动效模式(绘光/心象),颜色字号可调
- 歌词来源可手动指定、支持译文与罗马音 **🏠 灵动岛 HUD**
- 播放控制、下载进度、通知排队抢占,一览无余
- 音乐频谱接入 HUD 与全屏播放器
- 系统播放器控制(SMTC):键盘多媒体键/耳机线控也能控制游戏内播放 **👥 一起听**
- 开房间同听:点歌、排麦、房主转交,谁点的歌用谁的音源权限
- 局域网/直连模式:主机不需要装服务端
- 房内支持 Automix 智能过渡(两首歌无缝交叠) **🎚 音效与过渡**
- ViPER 蝰蛇音效全链移植 + JamesDSP 混响,游戏内调参面板
- 播放时自动压掉原版游戏音乐(音量条右键开关),只让路「音乐」、不碰音效与环境音
- Automix 智能过渡:Folia 引擎自动找接缝,人声让路、频谱避让;手机上走 ONNX,不需要装 Python **📱 手机也能好好玩**
- 触屏手势:列表按住拖动滚动(带惯性甩动)、点按松手触发、队列长按拖动排序
- 渲染组件自动下载安装,按提示重启即用
- 液态玻璃 UI、灵动岛动画、主题取色(支持封面取色) ## 支持的版本 | Minecraft | 加载器 | Java |
| --- | --- | --- |
| 1.20.1 | Fabric / Forge | 17 |
| 1.21.1 | Fabric / NeoForge | 21 |
| 1.21.11 | Fabric / NeoForge | 21 |
| 26.2 | Fabric / NeoForge | 25 |
| 26.3 | Fabric / NeoForge | 25 | ## 安装 1. **下载**:在本仓库 [Releases](../../releases) 或[官网](https://melodifymc.cc.cd/)下载对应「MC 版本 + 加载器」的 jar(如 `MuoniumPlayer-26.3-fabric-2.0.0.jar`)
2. **装前置**: - Fabric:Fabric API + Fabric Language Kotlin - NeoForge / Forge:Kotlin for Forge
3. 把 jar 丢进 `mods/`,进游戏
4. 非 Windows 平台首次启动会**自动下载渲染组件**(约 20 MB)并提示重启,不需要手动放任何文件;Windows 需要 AAC 转码时会有 ffmpeg 一键安装 ## 手机上游玩(安卓启动器) 推荐 FCL / Zalith Launcher 等启动器: - **26.3 用 MobileGL 渲染器**(已实测适配);其它版本建议 MobileGlues 并关闭 ANGLE。Zink / VirGL / Freedreno 等 Mesa 渲染器也已适配(GL 门自动对齐),并开放「真玻璃(实验)」开关,默认关闭
- 触屏操作见上;手感参数(惯性强度、长按时长)可在 `config/muonium/touch.json` 调
- 渲染组件装好后按屏幕提示重启游戏 ## 快速上手 - 默认按键:**右 Shift** 打开/关闭播放器(可在「选项 → 控制」里改)
- 左侧栏:音源、歌单、搜索、私人 FM、每日推荐;侧栏底部可导入歌单、进一起听
- 底部控制条:进度条拖动 seek、音量、音质选档、播放模式;右键有惊喜(如无缝交接下一首)
- 游戏内 **Ctrl+F**:从灵动岛长出快捷搜索栏,不切出游戏找歌 ## 从源码构建 ```bash
# Windows 一键全版本
build-all.bat # 或单个版本(以 26.3 Fabric 为例)
cd 26.3
gradlew :fabric:build
``` 各版本树需要的 JDK(17/21/25)见 `tools/build-all.ps1` 标注;产物汇总在 `dist/`。共享源码在 `muonium-common/`,五棵版本树编译时直接引入。 ## 常见问题 **装上后界面一个像素都没有?**
屏幕上会有一块原版字体的提示告诉你缺什么、下一步做什么,照着走即可。手机端请先确认渲染器选择(见上);仍不行就把 `latest.log` 里搜 `MuoniumPlayer` 的几行发过来。 **歌词匹配错了/想换歌词来源?**
播放器里可以手动指定歌词来源;译文、罗马音都支持。 **触屏滚动太快/太慢?**
`config/muonium/touch.json` 里的 `flingDecel`(惯性)与 `dragThresholdPx`(触发阈值)随便调。 ## 许可与致谢 - 本项目源码以源码可见(source-available)形式公开,权利声明见各版本目录下的 `LICENSE.txt`
- 内置组件许可:Skia(BSD-3)、Skija(Apache-2.0)等,全文见各版本 `licenses/` 与 `docs/third-party/`
- 功能致谢:qplayer、modernMP、sndfilter(JamesDSP 混响) ## 反馈 - 提 [Issue](../../issues)(附 `latest.log` 与版本号)
- 官网与下载:[melodifymc.cc.cd](https://melodifymc.cc.cd/)
