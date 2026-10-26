# mpparty-common —— 一起听（服务端房间）客户端 MuoniumPlayer 接入服务端 mod **MusicParty / mpsync** 的那一份共用源码。
五个版本工程（`1.20.1 recode` / `1.21.1` / `1.21.11 recode` / `26.2` / `26.3`）
都在自己的 build.gradle 里挂上这一个目录： ```gradle
sourceSets.main.java.srcDirs += ['../mpparty-common/src/main/java']
``` 本目录内**不允许**出现任何 Minecraft 类型 —— 版本差异（通道 API、玩家 UUID、聊天）
全部关在 `MpHost` 接口后面，由各工程自己的
`src/main/java/com/muoniumplayer/party/net/MpNet.java` 实现。 | 文件 | 职责 |
| --- | --- |
| `MpProto` | 线协议：varint 分帧 + 一个 JSON 对象。必须与服务端 `yun.shan.mpsync.net.Proto` 逐字节一致 |
| `MpRoom` / `MpMember` / `MpTrack` | 房间快照的不可变客户端视图 |
| `MpPartyClient` | 通道、房间状态、时钟、点歌上传、本地控制拦截 |
| `MpPlayback` | 与 `CloudMusic` / `AudioPlayer` 的桥接：起播、对齐、暂停、纠偏 |
| `MpNetease` | 用播放器自己的会话查歌曲详情、解取流链接（含链接实际时长，用于识别试听片段） |
| `MpLink` | 把分享文案 / 短链 / 纯数字还原成歌曲 ID |
| `MpHost` | 版本相关那一层的接口 |
| `ui/MpPartyOverlay` | 房间面板（建房 / 加入 / 邀请 / 成员 / 歌单 / 传输控制）。用播放器自己的控件框架画 | 宿主侧改动（都带注释）： * `Music`：新增 `partyStreamUrl` / `partyStreamType`，`getPlayUrl()` 优先用房间下发的链接；
* `CloudMusic`：`startPlaybackList` / `playNext` / `next` / `prev` / `togglePlayPause` / `seekByMillis` / `cyclePlayMode` 开头各加一行房间拦截；另外新增 `seekToMillis(float)` —— 绝对位置跳转的唯一入口（含房间拦截与歌词对齐）；
* `ControlsBar`：播放/暂停按钮改走 `CloudMusic.togglePlayPause()`、进度条改走 `CloudMusic.seekToMillis()`（原先直接动 `player`，会绕过房间的传输控制权）； 右下角新增一个「服务器一起听」入口图标；
* `MusicLyricsPanel`：播放/暂停、进度条拖动、点歌词行跳转同样改走上面两条统一路径；
* `NCMScreen`：新增 `mpPartyOverlay` 字段、渲染块、ESC / 鼠标 / 模态门，以及 `openMpParty()`；
* `MuoniumPlayerMod#onInitializeClient`：`MpNet.register()`。 `ui/MpPartyOverlay` 放在共用模块里的前提是：五个版本工程的控件框架
（`core.rendering.ui.*`、`NCMPanel`、`FontManager`、`compat.lwjgl2.Keyboard/Mouse` 的 API）
目前一致。哪天某个版本的控件框架分叉了，就把这个文件拆回各工程自己的 `screens/ncm` 下。 服务器没装一起听服务端 mod 时，以上全部是空转。
