# mpserver-core —— 内嵌一起听房间引擎(只读镜像)

服务端仓库(`yun.shan.mpsync`,房间引擎 + 线协议,只依赖 gson)core 目录的**只读镜像**,由 `tools/sync-mpserver-core.ps1` 单向同步进来。

**不要在这里手改代码** —— 下次同步会被覆盖。改房间引擎去服务端仓改 core,跑同步脚本带过来,两边各提交。

## 用途

客户端 mod 内嵌这套引擎,让只装客户端 mod 的局域网主机也能开一起听房间:引擎挂进集成服务端(同 JVM),其他玩家只装客户端 mod 即可加入。房间逻辑、协议与专用服务器上的 mpsync 逐字节一致。

## 不变量

- 本目录**禁止出现任何 Minecraft / 加载器 import**(`net.minecraft`、`net.fabricmc`、`net.neoforged`、`net.minecraftforge`、`org.bukkit`、`com.mojang`)—— 编译期无守卫,靠同步脚本末尾的 grep 检查兜底。
- `yun.shan.mpsync.net.Proto` 必须与客户端 `com.muoniumplayer.party.MpProto` 的协议版本、通道名、帧格式逐字节一致;改协议是两仓的事,两边各自提交。
