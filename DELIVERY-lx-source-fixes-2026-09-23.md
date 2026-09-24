# 交付文档：LX 自定义源「加载失败 / 播放失败」修复与运行时可观测化

日期：2026-09-23
范围：1.21.1 / 1.20.1 recode / 1.21.11 recode / 26.2（四树同步）
状态：代码已落地、四树测试全绿；**已随 1.6.1 提交**（见 `docs/1.6.1-发布说明.md`）

---

## 一、问题与结论（先看这个）

用户报两类现象：

1. 加载 `长青SVIP音源` 后「一直播放失败」。
2. 加载 `https://raw.githubusercontent.com/pdone/lx-music-source/main/flower/latest.js`（野花）「报错、无法加载」。

两个根因，彼此独立，均已修复：

| 症状 | 真因 | 层 |
|---|---|---|
| 长青「无法取流、一直播放失败」 | 脚本返回的中继直链是 `http://…/wy.php?…`，服务器用 301 升级到 `https`；`HttpURLConnection` 不跨协议自动跟随，且 `HttpUtils.request` 对任意 `>=300` 直接抛异常 → 下载即失败 | 通用下载层 |
| 野花「加载即崩」 | 新版 `latest.js`（`@version 1`）用了 `Promise.any`；引擎 **Rhino 1.7.15 没有 `Promise.any`/`AggregateError`**，而负责降级语法的 Closure **不补运行期 built-in** → `TypeError: Cannot find function any in object function Promise()` | LX 脚本运行层 |

用户已实测：**野花现在可以加载**。

---

## 二、改动清单

### 1) 跨协议重定向跟随（修长青类「播放失败」）
- 文件：`core/utils/network/HttpUtils.java`（四树）
- 做法：在 `request()` 里为**幂等的 GET/HEAD** 增加手动重定向跟随（读 `Location`、`new URL(base, loc)` 支持相对地址、`disconnect()` 后换 URL 递归、上限 `MAX_REDIRECT_HOPS = 8`）。POST/PUT 保留「非 2xx 直接抛」的既有语义不变。
- 私有 `request()` 新增 `redirectDepth` 参数（两个内部调用点相应更新）。
- 取证：`http://…`→301 text/html(162B)，`https://…`→200 audio/mpeg；改后 `HttpUtils.get(httpUrl)` 返回以 `fLaC` 魔数开头的真音频。

### 2) 现代 JS 垫片（修野花类「加载即崩」）
- 新增资源：`src/main/resources/muonium/lx/polyfills.js`（四树）
- 接线：`LxRuntime.load()` 里新增 `installPolyfills(cx)`，在 **bootstrap 之前**把垫片 `evaluateString` 进同一个 `scope`；**fail-soft**（缺资源或内部抛错只 warn，绝不连累本来能跑的脚本）。
- 特性检测：每一项只在缺失时才定义，**不覆盖原生**。
- 覆盖：`AggregateError`（Error 子类）、`Promise.any / allSettled / prototype.finally`、`String.matchAll / at / replaceAll / pad* / includes`、`Array.flat / flatMap / includes / at / findLast / findLastIndex / from / of`、`Object.assign / entries / values / fromEntries / hasOwn`、`Number.is*`。
- 注意：函数表达式不能用保留字名（`function finally` 会被 Rhino 打回）。

### 3) 运行时可观测化（D2，防「糊成一团的 Fail」）
- `bootstrap.js`：
  - `request()` 把回调包一层，每次宿主替脚本发的 HTTP 记一行：`[lx.request] GET <url> → <状态> · <字节>`（失败记 `… 失败：<原因>`）。
  - `__lxDispatch` 的 reject / throw 路径统一走 `fail(where, error)`，记 `[lx.<action> <source>] <环节>：<消息> <调用行>`；交给宿主的响应仍只是消息。
- `LxRuntime.describe()`：对 `RhinoException` 追加 `[" + sourceName() + ":" + lineNumber() + "]`。
- 价值：以后任何源报「加载/取流失败」，日志直接指出缺什么、在哪一行，而不是只有 `Fail`。

### 4) 测试
- 新增 `LxModernJsPolyfillTest`（4）：真起运行时跑 `Promise.any / matchAll / findLast / hasOwn`，含一次真实 musicUrl 往返；源码扫描钉「垫片在 bootstrap 之前装载」与「垫片是纯 JS」。
- 新增 `LxDiagnosticFailureTest`（1）：脚本 `lx.request('http://127.0.0.1:5/…')` 撞关闭端口 → 断言日志出现 `[lx.request] … 失败` 与 `[lx.musicUrl wy] 脚本拒绝`（全程离线）。
- 既有用例不受影响（`LxSourceProtocolStaticTest` 的 bootstrap 字面量断言、`LxRuntimeSandboxTest` 均通过）。

---

## 三、验证记录

### 自动化
- 四树 `gradlew test --offline --rerun-tasks` 全绿，零失败 / 零错误 / 零跳过：
  - 1.21.1 = 561、1.20.1 recode = 555、1.21.11 recode = 572、26.2 = 571。
- Headless 取证（用宿主真发结构 `LxTrack.toMusicInfoJson()`）：野花 `latest.js` 走真实 `LxRuntime` → `已就绪 可用源 kw/kg/tx/wy/mg`，wy 取流 `GET …/wy/189835/128k → 200`，返回真实网易云 mp3 直链。

### 上游兼容普查（拉了上游仓库逐个真跑）
上游来源：`pdone/lx-music-source`（10 个真源）+ `lyswhut/lx-music-desktop`（运行时契约 preload，已核对我们的 `lx` 面 `request/send/on/utils.{buffer,crypto,zlib.inflate/deflate}/currentScriptInfo/version/env` 与上游一致，**无 API 缺失**）。

| 结果 | 源 |
|---|---|
| 能加载 (7) | changqing、huanyin、huibq、ikun、juhe、lx、qdy |
| 加载失败 (3) | sixyin（BigInt，见「遗留」）、flower、grass（见下） |

- flower / grass：把 `send('inited')` 排在自身「检查更新」那次 `lx.request` 之后；当公共镜像被限流/慢时 inited 会晚到、撞宿主 30s 加载时限。干净网络下稳定 ~5s 就绪（实测 3/3 + 用户真机）。非宿主缺陷。

---

## 四、如何复现验证

探针都在 `.scratch/`，用现成编译产物 + 依赖 jar 直连运行（不改 mod 代码）：
- classpath = `<某树>/fabric/build/classes/java/main` + `resources/main` + gson + rhino + closure-compiler，JDK 在 `~/.gradle/jdks/...`。
- `ProbeCensus`：把上游语料逐个过 `LxRuntime`，输出「加载成没成 + 首个错误」。
- `ProbeFlowerRequest` / `ProbeFlowerQuality`：真实 `LxTrack` payload 走取流，看 `[lx.request]` 与返回链接。

---

## 五、遗留 / 未做（透明起见）

1. **sixyin（六音）BigInt**：转译阶段 `Cannot convert ECMASCRIPT_2020 feature "bigint"`，回退原始脚本后 Rhino 解析不了 BigInt 字面量。这是**引擎级硬缺口**，根治需把 Rhino 升到支持 BigInt 的版本（本地离线仓库里只有 1.7.15，暂无可替换版本）。属较大改动，未动。
2. **core-js 全量垫片**：想让「几乎所有现代脚本都不缺 built-in」的正解是引入 core-js，而非手写清单。本机对 jsdelivr/unpkg 拉包受阻（仅 github raw 可用），其在 Rhino 下的可用性/体积/启动开销**尚未验证**，未引入。`polyfills.js` 是留给「再缺就加一行」的扩展缝。
3. **临时试过的两处超时加固（init 30→45s、HTTP connect 拆 12s）已全部回退**：缺乏「一定有利」的干净证据、且疑似干扰了本可正常加载的野花，故不纳入本次交付。
4. **提交**：本轮改动已并入 1.6.1 一起提交（含版本号六处 × 四树与清单 1.6.1）。

---

## 六、涉及文件一览

新增：
- `src/main/resources/muonium/lx/polyfills.js`
- `src/test/java/com/muoniumplayer/core/ncm/lxsource/LxModernJsPolyfillTest.java`
- `src/test/java/com/muoniumplayer/core/ncm/lxsource/LxDiagnosticFailureTest.java`

修改：
- `src/main/java/com/muoniumplayer/core/utils/network/HttpUtils.java`
- `src/main/java/com/muoniumplayer/core/ncm/lxsource/LxRuntime.java`
- `src/main/resources/muonium/lx/bootstrap.js`

（以上四树各一份，内容逐字节一致）
