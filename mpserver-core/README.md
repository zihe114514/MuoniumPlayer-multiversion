# mpserver-core —— 内嵌一起听房间引擎（服务端 core 的镜像）

本目录是服务端仓库 `F:\donked\服务端\core\src\main\java`（`yun.shan.mpsync`，房间引擎 +
线协议，只依赖 gson）的**只读镜像**，由 `tools/sync-mpserver-core.ps1` 单向同步进来。

**不要在这里手改代码**：下次同步会被覆盖。要改房间引擎，去服务端仓改 core，
跑同步脚本带过来，两边各提交。

## 它在这边干什么

MuoniumPlayer 客户端 mod 内嵌了这套引擎，让**只装客户端 mod 的局域网主机**也能开
一起听房间：主机开局域网联机时，引擎挂进集成服务端（同 JVM），其他玩家同样只装
客户端 mod 即可加入。房间逻辑、协议与专用服务器上的 mpsync 服务端 mod 逐字节一致。

五棵树的 `build.gradle` 通过 `sourceSets.main.java.srcDirs` 挂载本目录；
`LanMpParty`（各树外壳里）是它在本侧的宿主适配层，对应服务端仓的
`fabric-common` / `forge-common` / `neoforge-common` / `paper-common` 那几个薄文件。

## 同步方向（只此一个）

```
服务端仓 core  ──sync-mpserver-core.ps1──▶  本目录
```

反向改动（在本目录改了代码）会在服务端仓下一次同步时被冲掉。
服务端 core 改动后的完整流程：服务端仓 commit → 本仓跑同步脚本 → 检查 diff →
两仓各自提交。与 `tools/update-manifest/update.json` 的「线上 → 副本」是同一条纪律。

## 不变量

- 本目录**禁止出现任何 Minecraft / 加载器 import**（`net.minecraft`、`net.fabricmc`、
  `net.neoforged`、`net.minecraftforge`、`org.bukkit`、`com.mojang`）——
  编译期没有守卫（这边不是独立 gradle 模块），靠同步脚本末尾的 grep 检查兜底。
- `yun.shan.mpsync.net.Proto` 必须与客户端 `com.muoniumplayer.party.MpProto` 的
  协议版本、通道名、帧格式逐字节一致；改协议是两仓四方的事
  （服务端 Proto + 客户端 MpProto + 两边各自提交）。
