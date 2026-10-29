# muonium-common —— 五棵树共用的主体源码

`1.20.1 recode` / `1.21.1` / `1.21.11 recode` / `26.2` / `26.3` 五个版本工程的**版本中立**代码全部放这里,由各树 build.gradle 挂载编译(挂接用 `tools/share-trees.py wire`,由 `SharedLayoutStaticTest` 钉住,漏挂即红)。

## 规矩

- **不许出现加载器符号**:`net.fabricmc` / `net.neoforged` / `net.minecraftforge` / `cpw.mods` 一律不许 import。
- **不许直接调只有新版本才有的 MC API**。要版本差异,在各树 `compat/` 加同名同签名方法,五树各写各的实现,这里只调这个名字。不要把整个文件搬回树里。
- **资源按字节走**:字体、贴图、dll 不许「读成字符串再写回去」,会把字节撑坏。
- **改这里就是改五个版本**,改完跑五棵树测试:`.\tools\build-all.ps1 -Task ':fabric:test'`。

## 文件何时离开这里

只有当它在某棵树上必须写得不一样才能编过时。此类文件登记在 `SHARE-MANIFEST.md`,由 `SharedLayoutStaticTest` 钉住:名单外的文件出现在树内 `src/main/java` 即红。

## 工具

```powershell
python tools\share-trees.py classify        # 五树体检:SAME / DRIFT / FORK / ONLY
python tools\share-trees.py apply --confirm # 按底片搬运,重新生成 SHARE-MANIFEST.md
python tools\share-trees.py wire            # 把三个共享根挂进 10 个加载器构建(幂等)
python tools\share-trees.py verify          # 核对不丢不重
```
