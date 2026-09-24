# yubal 下载机制分析 + 真机实测报告

测试日期：2026-09-23 · 仓库：`guillevc/yubal` @ `78d6384`（2026-09-07, *fix: bump yt-dlp to resolve YouTube 403 errors*）
本地副本：`C:\MUSIC\.scratch\yubal` · 测试脚本与产物：`C:\MUSIC\.scratch\yubal-test`

## 一、结论先行

| 层 | 结论 |
|---|---|
| 媒体取流（yt-dlp + FFmpeg） | **可用**。同一 Cookie 文件下成功抓到 251/140 格式并落成 opus / m4a / mp3 三种文件 |
| 账号认证（SAPISIDHASH → ） | **可用**。识别为 `zihebaobao`，`AuthType.BROWSER`，`logged_in=1` |
| YouTube Music 元数据层（browse / search / playlist） | **本机网络下整体失效**。服务端返回"该国不可用"，播放列表 13 行全部 `videoId=None` |
| 实际后果 | 粘专辑/歌单链接 → `No tracks extracted, nothing to download`；单曲链接 → 偶发被判 UGC 跳过 |
| 代码健康度 | 自建单测 520 passed / **8 failed**，8 个全是 Windows 路径分隔符假设 |

一句话：**这个项目本身是有效的，但它 60% 的价值（专辑识别、曲库整理）依赖 YouTube Music 的 InnerTube 接口，而这条接口从你这台机器的出口 IP 是被国别封锁的。取流那一半没问题。**

## 二、它是怎么"下载 YouTube 歌曲"的

三层结构，Python 为主（FastAPI + Typer CLI），前端是 bun 构建的静态站，Docker 是唯一官方支持的发行形态。

```
URL ──> [1] 元数据抽取 ( / InnerTube)
          client.py         包装 YTMusic，带 SAPISIDHASH 认证
          extractor.py      判定 album vs playlist，四级匹配回到专辑
       ──> [2] 取流 (yt-dlp 2026.8.19 + FFmpeg)
          download_service.py:YTDLPDownloader
       ──> [3] 入库 (mediafile 打标 / 封面 / .lrc 歌词 / m3u / rsgain 响度)
          tagging_service.py, lyrics.py, m3u.py, replaygain.py
```

### 1. 元数据：不是抓网页，是打 InnerTube

`packages/yubal/src/yubal/client.py` 依赖 ``，核心动作：

- `get_playlist(id, limit=None)` → 拆成 `valid_tracks` 与 `unavailable_tracks`，后者按 `NO_VIDEO_ID` / `REGION_UNAVAILABLE` 归类上报（`client.py:197-248`）。
- 专辑判定四道关（`extractor.py:468`）：有曲 → 全部指向同一 album id → 该专辑能取到 → 歌单包含专辑**全部**曲目，才认定为 ALBUM，否则当歌单处理。
- 曲目回填专辑信息时四级匹配（`extractor.py:794`）：videoId → 标题精确 → 时长唯一 → rapidfuzz 模糊（50–80% 阈值），置信度不够就丢进 `_Unmatched/`。
- 无专辑信息时主动 `search(filter="songs")` 反查专辑（`extractor.py:679`），标题或艺人相似度不足即判 `_Unmatched/`。
- `videoType` 白名单只收 `ATV / OMV / OFFICIAL_SOURCE_MUSIC`（`extractor.py:21`）；UGC 与字段缺失的曲目在 `download_ugc=False`（默认）时直接跳过。

### 2. 取流：靠"版本 + JS 求解器"两件事扛住 403

`download_service.py:108-157` 的 yt-dlp 选项就是全部玄机：

```python
"format": f"bestaudio[ext={codec}]/bestaudio[acodec={codec}]/bestaudio/best",
"postprocessors": [{"key": "FFmpegExtractAudio",
                    "preferredcodec": ..., "preferredquality": str(quality)}],
"retry_sleep_functions": {"http": lambda n: min(2**n, 30),
                          "fragment": lambda n: min(2**n, 30)},
"remote_components": ["ejs:github"],   # ← 关键
```

`remote_components: ["ejs:github"]` 让 yt-dlp 在运行时从 GitHub 拉 `yt-dlp/ejs` 的挑战求解库，配合依赖里强制装的 **deno**（`pyproject.toml` 注释写明"JS runtime required by yt-dlp for YouTube's EJS challenge solver"）解 YouTube 的 sig/nsp 挑战。实测日志印证：

```
[youtube] [jsc:deno] Solving JS challenges using deno
[youtube] [jsc:deno] Downloading challenge solver lib script from
            https://github.com/yt-dlp/ejs/releases/download/0.8.0/yt.solver.lib.min.js
```

这就是那条 `fix: bump yt-dlp to resolve YouTube 403 errors` 提交的全部内容——**没有自建 PO Token 服务，纯靠跟紧 yt-dlp 版本 + EJS**。因此它的有效性天花板就是 yt-dlp 对 YouTube 变动的跟进速度。

另有 403/429/5xx 指数退避重试（1s/2s/4s，`MAX_RETRIES=3`），`"Video unavailable"` 与含 `Sign in`/`cookies` 的错误则直接失败并给中文可读的提示（`download_service.py:253-300`）。

下载 URL 恒为 `https://music.youtube.com/watch?v={video_id}`，曲目 ID 优先级 `atv > omv > source`（`models/track.py:88`）。

### 3. Cookie：一份文件喂两个消费者，且做了两次防御

`utils/cookies.py` 把 Netscape 格式转成  需要的请求头：

- **白名单过滤**：只保留 15 个必要 Cookie（`ESSENTIAL_COOKIES`），注释说明浏览器导出常带 90KB+ 导致 HTTP 413。
- **SAPISIDHASH 签名**：`SHA1(timestamp + " " + SAPISID + " " + origin)`，origin 固定 `https://music.youtube.com`，配 `x-goog-authuser: 0`。优先用 `__Secure-3PAPISID`。
- **临时副本**：喂 yt-dlp 前 `shutil.copy2` 到临时文件（`download_service.py:203-211`），注释写明原因——yt-dlp 会把 cookie jar 回写，抹掉  还需要的 `SID/HSID/SSID`。

服务端侧：Web API 提供 `GET/POST/DELETE /cookies`，校验首行必须是 `#` 或 `.`，明文写入 `config/ytdlp/cookies.txt`（`routes/cookies.py`、`settings.py:163-168`）。**注意是磁盘明文存储，无加密。**

### 4. 取流之后：为什么它看起来像"正规库"

- `mediafile` 写 ID3/MP4/ogg 标签，封面 JPEG 直接内嵌；
- 文件名走 `pathvalidate.sanitize_filename` + UTF-8 字节预算截断（240 字节/段，`utils/filename.py`）；
- 目录规则：命中 → `Artist/YEAR - Album/NN - Title.opus`；未命中 → `_Unmatched/Artist - Title [videoId]`；UGC → `_Unofficial/`；
- 歌词两级来源：`lrclib.net` 优先，可选回落 YouTube Music 歌词（`services/lyrics.py`），写同名 `.lrc`；
- 歌单额外产 `_Playlists/名字 [id].m3u`（相对路径引用）+ 封面 `.jpg`；
- 响度用外部 `rsgain` 写 RG2.0/R128 标签，**非致命**：找不到就 `ReplayGain skipped (rsgain unavailable or failed)`；
- 已存在文件跳过（不覆盖），但仍会补 `.lrc`；
- 之上还有 subscription + cron 调度（`packages/api/services/scheduler.py`、`sync_service.py`）做定期增量同步，以及一个 WXT 浏览器扩展在 YouTube 页面直接投递链接。

## 三、实测记录

环境：Python 3.12.14 · yt-dlp 2026.8.19 ·  1.12.2 · deno 1.46（PyPI `deno` 包自动装）· ffmpeg 已在 PATH · 出口 IP `212.87.193.69`（Tokyo, AS38136 Akari Networks）

| # | 测试 | 结果 |
|---|---|---|
| 1 | Cookie → SAPISIDHASH → `YTMusic` | ✅ `AuthType.BROWSER`，`get_account_info` 返回 `zihebaobao / @zihebaobao`，必要 Cookie 15/22 命中，header 1536 B |
| 2 | `has_unlimited_entitlement` | `False` → 该 Google 账号**无 Music Premium**；带 Cookie 与匿名解析到的音频格式数完全相同（48 个，音频 5 个：249/250/140/251），**Cookie 没有带来任何音质收益** |
| 3 | yt-dlp 取流（匿名 / 带 Cookie） | ✅ 两者均成功；EJS 挑战被 deno 解出，选中 251（opus 128k）/140（aac 128k） |
| 4 | 真实下载 opus | ✅ 3.23 MiB / 213 s，`ffmpeg -vn -acodec copy` → **纯 remux，零转码损失** |
| 5 | 真实下载 mp3 | ⚠️ 6.72 MiB，实测平均 264 kbps → 从 128k opus **重编码**成 320k 目标，体积翻倍、信息量没增加 |
| 6 | 真实下载 m4a | ✅ 命中 140（已是 aac），`FixupM4a` 修容器，基本等于原样 |
| 7 | 打标 | ✅ title/artist/album/albumartist/track=1/tracktotal=10/year=1987 全部正确，封面 JPEG 已内嵌（ffprobe 见 `mjpeg` 附件流） |
| 8 | 歌词 | ⚠️ 管道正常但写进 15 字节垃圾：lrclib 对该曲返回 `syncedLyrics="[00:00.00]probe"`（lrclib 侧的测试数据），yubal 无有效性校验，照单全收 |
| 9 | 专辑/歌单浏览 | ❌ 仓库自带测试 URL `OLAK5uy_mxPcDF...`（SABLE, fABLE）返回 13 行，但**每行 `videoId=None`、`isAvailable=False`**；mood 页直接回 `MUSIC_UNAVAILABLE / "YouTube Music isn't available in your country"` |
| 10 | `search(filter="songs")` | ❌ 0 命中（匿名与带 Cookie 一致）；去掉 filter 才有 10 条，且全是 artist/podcast |
| 11 | `yubal download` CLI（单曲） | ⚠️ 一次成功跑出流程但报 `No tracks extracted, nothing to download`；另一次同 ID 提取到 `match=unmatched, year=2025` 正常进入下载阶段 |
| 12 | 自建单测 | ⚠️ 520 passed / 8 failed，失败全在 `test_filename.py`、`test_m3u.py`，原因是断言硬编码 `/` 而 Windows 产出 `\` |

### 关于第 9–11 条的定性

这是**网络侧限制，不是 yubal 的缺陷**，判定依据：匿名客户端（不带任何 Cookie）从同一 IP 得到完全相同的空结果和同一句 `MUSIC_UNAVAILABLE`。`youtube.com/watch` 与 `music.youtube.com/watch` 都返回 200，只有 YTM 的 browse/search 面被挡。

顺带暴露两个脆弱点：
1.  首次请求要 `GET https://music.youtube.com/` 抓 visitorId，本网络该调用高频 `SSLEOFError`（curl 实测三次：200 / 200 / 000），库内无重试 → yubal 直接 `Unexpected error` 崩掉整条流程。
2. 同一 videoId 两次返回的 `videoType` 不一致（`dQw4w9WgXcQ` 一次 `MUSIC_VIDEO_TYPE_UGC`、一次 `MUSIC_VIDEO_TYPE_OMV`），在 UGC 默认跳过（`download_ugc=False`）时会让结果**随机变成"无曲可下"**。且 `download_ugc` 只有 FastAPI 的 `YUBAL_DOWNLOAD_UGC` 环境变量能开，**CLI 没有对应开关**。

## 四、如果要用它

1. **必须换网络出口**，而且是要 YTM 可用的地区（韩国/日本住宅、美国等）。当前 Akari 出口对 YTM 是"能连不能用"。判断标准很简单：`yubal download` 一个专辑链接，如果报 `No tracks extracted` 而不是开始逐曲下载，就是还没通。
2. **Docker 部署**，别在 Windows 上裸跑：8 个路径测试失败与 m3u 里的 `..\Radiohead\...` 反斜杠会直接给下游播放器埋雷。
3. **音质：codec 选 `opus`（默认）**，实测是 `-acodec copy`，拿到的是 YouTube 原始 128k opus，无二次损失；选 `mp3` 会走 320k 重编码，白占一倍空间还引入转码失真。要 FLAC/WAV 得改代码——`AudioCodec` 只有 opus/mp3/m4a 三个枚举值，**不支持无损**。
4. **想下 UGC/翻唱**必须设 `YUBAL_DOWNLOAD_UGC=true`（仅 Web API 侧有效），否则这类曲目静默丢弃。
5. **容器里补 `rsgain`**（官方镜像 amd64 才自带），否则响度归一化静默跳过，不同曲目音量不齐。
6. **Cookie 用小号。** 项目自己的 README 就写了 "Cookie usage may trigger stricter rate limiting and could put your account at risk"，且 Web UI 上传的 Cookie 是磁盘明文。你这组 Cookie 目前具备完整账号登录态。

## 五、和你现有工程的对照

- 它的 `ATV > OMV > source` 选 ID、`videoType` 白名单、四级曲目匹配 + `_Unmatched/` 兜底，和你"跨平台自动换源"里的置信度分档思路同源，`lib/matching.py` 的相似度阈值实现可以直接借。
- 它把"取流"和"元数据"彻底分成两个后端（yt-dlp vs ），并为此专门处理了同一份 Cookie 被两边写坏的冲突——这正是你 mod 与外部 GPL 取码工具之间那类"一份凭据两个进程"问题的另一种形态，值得对照。
- 它没有任何 PO Token / DV 抓取的自建方案，纯靠 yt-dlp 版本跟进，所以抗风险能力低于自带取流链路的做法。

## 六、测试产物

| 路径 | 说明 |
|---|---|
| `C:\MUSIC\.scratch\yubal` | 仓库浅克隆（commit `78d6384`），`.venv` 已就绪 |
| `C:\MUSIC\.scratch\yubal-test\cookies.txt` | ⚠️ 由你提供凭据生成的 Netscape 文件，**含有效登录态** |
| `...\yubal-test\t1_auth.py … t5_videotype.py` | 五个测试脚本，可重复执行 |
| `...\yubal-test\library\`、`quality\` | 实际下载出的 opus/m4a/mp3 成品与标签验证结果 |
