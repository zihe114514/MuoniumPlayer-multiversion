# mpparty-common —— 一起听(服务端房间)客户端

接入服务端 mod **MusicParty / mpsync** 的共用源码,五个版本工程都在 build.gradle 里挂载本目录:

```gradle
sourceSets.main.java.srcDirs += ['../mpparty-common/src/main/java']
```

本目录**不允许出现任何 Minecraft 类型** —— 版本差异(通道 API、玩家 UUID、聊天)全部关在 `MpHost` 接口后面,由各树自己的 `party/net/MpNet.java` 实现。

| 文件 | 职责 |
| --- | --- |
| `MpProto` | 线协议:varint 分帧 + 一个 JSON 对象,须与服务端 `yun.shan.mpsync.net.Proto` 逐字节一致 |
| `MpRoom` / `MpMember` / `MpTrack` | 房间快照的不可变客户端视图 |
| `MpPartyClient` | 通道、房间状态、时钟、点歌上传、本地控制拦截 |
| `MpPlayback` | 与播放器内核的桥接:起播、对齐、暂停、纠偏 |
| `MpNetease` | 用播放器会话查歌曲详情、解取流链接 |
| `MpLink` | 分享文案 / 短链 / 纯数字还原成歌曲 ID |
| `MpHost` | 版本相关层的接口 |
| `ui/MpPartyOverlay` | 房间面板(建房 / 加入 / 邀请 / 成员 / 歌单 / 传输控制) |

播放控制(播放/暂停、进度跳转)必须走 `MpPartyClient` 的统一拦截路径,不得绕过 —— 否则会破坏房间的传输控制权。`ui/MpPartyOverlay` 依赖五树控件框架一致;哪天分叉了,就把它拆回各树 `screens/ncm` 下。

服务器没装 mpsync 时,以上全部空转。
