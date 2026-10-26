# muonium-common —— 五棵树共用的主体源码 `1.20.1 recode` / `1.21.1` / `1.21.11 recode` / `26.2` / `26.3` 五个版本工程，
把自己的**版本中立**代码全部放这里，五个工程各挂同一份编译： ```gradle
sourceSets.main.java.srcDirs = [ 'src/main/java', // 本加载器外壳（fabric / neoforge / forge） rootProject.file('../muonium-common/src/main/java'), // 五树共享主体（本目录） rootProject.file('src/main/java'), // 本树版本差异层（compat / mixin） rootProject.file('../mpparty-common/src/main/java') // 一起听共用源码
]
``` 资源与测试同理，三个共享根分别是 `src/main/resources`、`src/test/java`。
挂接由 `tools/share-trees.py wire` 完成，`SharedLayoutStaticTest` 里有一条断言钉着，
漏挂一棵树会在跑测试时立刻红。 ## 为什么能共享 五个版本统一用 Mojang 官方名（mojmap）编译， Minecraft 类型的差异全被压在
各树的 `com/muoniumplayer/compat/` 里；剩下的代码只调用 compat 的同名方法
与本仓库自己的 API。于是同一份源码在五棵树下都能编过 —— 搬家前实测：
861 个主源码路径里 611 个逐字节相同、144 个只差行尾、72 个只差注释，
真正按版本分叉的只有 30 个。 ## 这里的规矩 * **不许出现加载器符号**：`net.fabricmc` / `net.neoforged` / `net.minecraftforge` / `cpw.mods` 一律不许 import。加载器相关的代码属于各树的 `fabric/`、`neoforge/`（1.20.1 是 `forge/`）。
* **不许直接调只有新版本才有的 MC API**。要版本差异，就在各树的 `compat/` 里 加一个**同名同签名**的方法，五棵树各写各的实现，共享层只调这个名字。 别把整个文件从共享层搬回树里 —— 那等于把五份手工同步的负担请回来了。
* **资源按字节走**。字体、贴图、dll 不是文本，任何「读成字符串再写回去」的处理 都会把字节撑大（真事故：21 MB 的字体被写成 41 MB，游戏里表现为字渲染不出来）。
* **改这里就是改五个版本**。一处改动会同时进五个 jar，所以改完要跑五棵树的测试： `.\tools\build-all.ps1 -Task ':fabric:test'`。 ## 什么情况下文件该离开这里 只有当它在某棵树上**必须写得不一样**才能编过时。目前这样的文件有 34 个，
全部登记在 `SHARE-MANIFEST.md`（含每一条的理由），并由
`src/test/java/com/muoniumplayer/SharedLayoutStaticTest.java` 钉住名单：
名单外的文件出现在本树 `src/main/java` 里就是红。 ## 工具 ```powershell
python tools\share-trees.py classify # 五树体检：SAME / DRIFT / FORK / ONLY，写底片
python tools\share-trees.py apply --confirm # 按底片搬运，并重新生成 SHARE-MANIFEST.md
python tools\share-trees.py wire # 把三个共享根挂进 10 个加载器构建（幂等）
python tools\share-trees.py verify # 核对：共享 + 留守 == 底片并集，不丢不重
```
