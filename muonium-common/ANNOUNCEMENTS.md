# MuoniumPlayer 远端公告内容维护指南

> 适用范围：维护 `MuoniumPlayer-updates` 分发仓中的 `update.json`，更新客户端公告、赞助专栏、交流群专栏及其图片/跳转内容。
>
> 事实源是公开分发仓的远端 `main`。主仓 `tools/update-manifest/update.json` 是被忽略的本地镜像副本，不是编辑源。维护中不要把这个副本覆盖到分发仓。
>
> 本指南只教内容维护；修改客户端字段、安全域名白名单或渲染行为属于代码改动，需回主仓、逐版本验证并另行发布客户端。

## 数据来源和边界

客户端从 `MuoniumPlayer-updates` 获取完整清单。更新提示读取 `channels` / `fallback`；公告中心读取 `announcements`、`sponsorships`、`communities`。同一份清单按整体选用，客户端不会把一个镜像的版本信息和另一个镜像的公告拼接。

远端清单有两层缓存：客户端缓存有效期 30 分钟；镜像/CDN 缓存可能更久。客户端公告界面的「刷新」会绕过本地 30 分钟缓存，但不能保证绕过上游 CDN 缓存。通常等源缓存过期再复核。

必须保留当前有效的 `channels` 或 `fallback`。只含赞助/交流群的清单虽然新客户端能解析，但旧客户端不认识新专栏，且可能把这份清单当作无效内容。

## 哪个位置可以编辑

先确认本机分发仓工作副本路径，并将 `$UpdatesRepo` 指向该目录。远端仓库是：

```text
https://github.com/zihe114514/MuoniumPlayer-updates.git
```

在本机工作副本操作，不要编辑客户端代码仓的 `tools/update-manifest/update.json`：

```powershell
$UpdatesRepo = '<本机 MuoniumPlayer-updates 工作副本路径>'
git -C $UpdatesRepo status --short
git -C $UpdatesRepo log -1 --format='%H %ci %s' -- update.json
git -C $UpdatesRepo remote -v
```

确认工作树中没有别人尚未提交的修改，再同步远端 `main`：

```powershell
git -C $UpdatesRepo fetch origin
git -C $UpdatesRepo switch main
git -C $UpdatesRepo pull --ff-only origin main
```

若有未提交改动、分支分叉或 pull 不是快进，先停下来辨认改动来源并合并保留，不要 reset、checkout 覆盖或强推。

## 清单结构

保留原有 `channels` 和 `fallback`。顶层扩展字段示例：

```json
{
  "schema": 3,
  "contentRevision": 27,
  "channels": {
    "26.3": {
      "latest": "2.6.0",
      "page": "https://melodifymc.cc.cd/download",
      "notes": "版本更新说明"
    }
  },
  "fallback": {
    "latest": "2.6.0",
    "page": "https://melodifymc.cc.cd/download",
    "notes": "版本更新说明"
  },
  "announcements": [
    {
      "id": "2026-11-18-example-release",
      "date": "2026-11-18",
      "kind": "feature",
      "title": "给玩家看的简短标题",
      "body": "第一段。\n第二段。",
      "image": "https://melodifymc.cc.cd/images/announcement-cover-v1.png",
      "imageAlt": "图片内容说明",
      "imageFit": "cover",
      "link": "https://melodifymc.cc.cd/changelog.html",
      "linkText": "查看更新日志"
    }
  ],
  "sponsorships": [
    {
      "id": "support-project",
      "title": "支持项目",
      "badge": "自愿支持",
      "body": "说明支持方式、用途和是否有对应权益。",
      "image": "https://melodifymc.cc.cd/images/support-qr-v1.png",
      "imageAlt": "项目支持二维码",
      "imageFit": "contain",
      "actions": [
        { "text": "前往支持页面", "url": "https://afdian.com/a/your-project" }
      ]
    }
  ],
  "communities": [
    {
      "id": "official-community",
      "title": "官方交流群",
      "badge": "玩家交流",
      "body": "填写真实群号、主题、入群要求和邀请方式。",
      "image": "https://melodifymc.cc.cd/images/community-v1.png",
      "imageAlt": "交流群说明图片",
      "imageFit": "cover",
      "actions": [
        { "text": "查看入群说明", "url": "https://melodifymc.cc.cd/community.html" }
      ]
    }
  ]
}
```

这是字段示例，不是可直接发布的实际群组/收款信息。发布前用真实且已获授权的内容替换示例值。不要提交探针用图、测试二维码、虚构群号、虚构赞助人、收款账号或邀请链接。

### 字段规则

- `schema`：当前扩展格式写 `3`。新客户端会忽略未知字段，不会因为未来 schema 数字更高就停止解析。
- `contentRevision`：非负整数。每次修改任何专栏内容、但没有提升软件 `latest` 版本时递增；同一软件版本下客户端用它选更新的整份清单。不要降低或复用旧值。
- `channels` / `fallback`：版本更新信息，保持现有结构和有效下载页。只更新已经实际发布的版本；不要先把 `latest` 写成尚未可下载的版本。
- 三个内容数组各自最多 30 条，顺序就是显示顺序，最新/最重要的放前面；不会按日期自动排序。
- 每个内容项都需要唯一 `id`，唯一性按所属专栏分别计算。标题和正文不能同时为空。
- `announcements[].id` 是本地已读记账键。编辑已经发布的公告内容时必须换新 id，旧 id 已读的用户不会再次看到它；撤回公告则从数组删除。`contentRevision` 不会重置已读状态。
- 赞助和交流群条目不参与公告未读数与自动弹窗；编辑这两个专栏通常保留稳定 id 即可。
- `date` 可省略，格式建议 `YYYY-MM-DD`；`kind` 可用 `feature`、`fix`、`event`、`warning`，其他值会回退为普通公告样式。
- `body` 中的 `\n` 表示分段。折叠态显示前三行，用户展开可读完整正文；建议内容简洁，详细条款放网页并从动作按钮跳转。
- `image` 可省略。不需要配图时直接删掉 `image` 字段，不要填图片占位地址。没有合法图片地址的内容会显示纯文字样式。
- `imageAlt` 用于描述图片含义。`imageFit: "cover"` 等比铺满并裁切，适合封面；`"contain"` 完整显示，二维码/海报应使用它。未指定时按 `contain` 处理。
- `badge` 可选，覆盖卡片分类标签。`actions` 可选，最多 3 项；每项为 `text` / `url`。旧的 `link` / `linkText` 保留兼容，界面会合并去重，最终最多显示 3 个按钮。
- 客户端长度上限：id/title 各 256 字符，body 16,384 字符，每个 URL 2,048 字符，单份 JSON 2,000,000 字符。超出可能被拒绝或截断，不要把长文章塞进字段。

## 图片、链接与内容安全

客户端只有一份内置 HTTPS 域名白名单，不接受远端清单扩充域名。当前可用域及子域：

```text
melodifymc.cc.cd
github.com
githubusercontent.com
jsdelivr.net
modrinth.com
gitee.com
qq.com
afdian.com
afdian.net
```

端口只能省略（HTTPS 默认端口）或 `443`，URL 不得带用户名/密码。新增域名需要客户端代码变更并发布新版，不能只改 `update.json`。旧客户端不会自动获得新白名单；对旧客户端不可用的链接不要作为唯一入口。

图片只能是公开 HTTPS PNG、JPEG 或 WebP。单张编码文件最大 4 MiB；图片边长最大 4096 像素、总像素最多 8,000,000。客户端最多跟随 5 次重定向，每一跳都重新过同一白名单。发布前确认：

- 图片 URL 匿名访问可读，最终响应仍为白名单 HTTPS 地址。
- URL 使用新版本文件名，图片替换时也换 URL，避免客户端/镜像缓存仍显示旧图。
- 二维码清晰且 `imageFit` 为 `contain`；不可把关键文字贴在可能被裁切的封面边缘。
- 图片无追踪像素、无敏感信息；不要使用需要 Cookie、登录或临时签名的 URL。
- 邀请跳转使用 HTTPS 网页，不写 `mqqapi:`、`weixin:`、`javascript:` 等应用/脚本协议。
- 赞助内容明确标明自愿性质、收款主体和用途；交流群内容使用真实群组/规则，并得到管理者同意。

## 发布步骤

在分发仓工作副本内操作：

1. 检查 `git status`、当前分支和 `update.json` 最近提交，确认工作树干净且位于 `main` 最新提交。
2. 只编辑需要的字段。保留其他 channel、fallback、公告和专栏内容；数组顺序按展示优先级维护。
3. 每次内容改动递增 `contentRevision`。若新增/修改公告正文、标题或重要链接，同时按已读规则决定是否换公告 `id`。
4. 本地解析 JSON 并检查结构、条目数、id、文本和 URL 上限（需要 Node.js）：

```powershell
node -e 'const fs=require("fs"); const d=JSON.parse(fs.readFileSync("update.json","utf8")); if(!d.channels&&!d.fallback) throw Error("missing channels/fallback"); if(d.contentRevision!==undefined&&(!Number.isSafeInteger(d.contentRevision)||d.contentRevision<0)) throw Error("invalid contentRevision"); for(const k of ["announcements","sponsorships","communities"]) { const a=d[k]||[]; if(!Array.isArray(a)||a.length>30) throw Error(k+" invalid count"); const ids=new Set(); for(const x of a) { if(!x.id||x.id.length>256||ids.has(x.id)) throw Error(k+" invalid/duplicate id: "+x.id); ids.add(x.id); if((x.title||"").length>256||(x.body||"").length>16384) throw Error(k+" text too long: "+x.id); for(const u of [x.link,x.image,...(x.actions||[]).map(y=>y.url)]) if(u&&u.length>2048) throw Error(k+" URL too long: "+x.id); if((x.actions||[]).length>3) throw Error(k+" too many actions: "+x.id); } } console.log("JSON OK", Object.fromEntries(["announcements","sponsorships","communities"].map(k=>[k,(d[k]||[]).length])), "contentRevision", d.contentRevision||0)'
```

5. 审核完整差异，尤其确认没有意外覆盖 `channels` / `fallback`、没有重复 id、没有占位符或未经确认的收款/群信息：

```powershell
git diff --check
git diff -- update.json
```

6. 用分发仓自身的提交习惯创建内容提交。推送前再次检查 staged diff 与目标分支。不要对 `main` 强推，不要用主仓的旧副本覆盖分发仓。
7. 推送成功后，把远端 `main` 上的 `update.json` **单向同步到主仓忽略副本**：`C:\MUSIC\tools\update-manifest\update.json`。这是为本地工具/测试保持副本一致，不是回写源文件。
8. 在客户端公告中心点「刷新」复核新内容；镜像缓存可能延迟，首次未更新时先核对远端提交与 JSON，再等 CDN 缓存失效。客户端自动检查仍有 30 分钟本地缓存。

编辑前后至少保留一份本地备份或可恢复的 git diff。撤回错误内容时做一个新的修正提交并递增 revision；不要改写远端历史。

## 本地预览

无需推送即可预览：在对应开发实例的 `config/muonium/announcements_local.json` 放完整测试清单。该文件存在时客户端不访问网络；预览完删除它再测远端路径。不要把本地预览配置或测试图片纳入分发仓。

探针示例应标注为非线上数据，测试二维码必须明确不可付款、不可扫码；截图或本地 PNG 不能替代实际公开 URL 的 CDN、游戏 GPU 和浏览器实测。

## 兼容及回滚

- 保留至少一条有效的 `channels` 或一个有效 `fallback`，否则旧版客户端可能将仅含新专栏的 manifest 判为无效。
- schema 2 客户端会忽略 `sponsorships` / `communities` / 图片扩展字段；若要触达所有老客户端，公告仍写在 `announcements`，不要把必要更新信息只放赞助/交流群专栏。
- 公告关闭后按 id 标为已读；编辑后想让用户再次看到，要创建新 id。赞助和交流群的 id 不受公告已读影响。
- `contentRevision` 解决同版本多镜像内容新旧选择，不是客户端版本号，也不会触发更新安装提示。
- 误发但还未被用户缓存：在远端提交修正并递增 revision；已经缓存的客户端无法远端强制清缓存，用户可点刷新，其他用户需要等待缓存过期。
- 如果白名单外链接已发布，清单无法自行放行；先移除/替换链接，若确需新域名则走客户端代码变更与版本发布流程。

## 关联文件

- 客户端数据、字段限制、缓存、白名单和图片约束：[ANNOUNCEMENTS.md](ANNOUNCEMENTS.md)
- 客户端实现：[Announcement.java](src/main/java/com/muoniumplayer/core/update/Announcement.java)、[UpdateManifest.java](src/main/java/com/muoniumplayer/core/update/UpdateManifest.java)、[AnnouncementChecker.java](src/main/java/com/muoniumplayer/core/update/AnnouncementChecker.java)、[AnnouncementImageCache.java](src/main/java/com/muoniumplayer/core/update/AnnouncementImageCache.java)
- 历史主仓副本：[tools/update-manifest/update.json](../tools/update-manifest/update.json)（只从分发仓同步回来；被 gitignore，不作为提交文件）
- 网站日志与版本口径：`MuoniumPlayer-site/changelog.html`、`MuoniumPlayer-site/assets/js/data.js`
- 测试：`AnnouncementDataTest`、`AnnouncementStaticTest`、`AnnouncementImagePolicyTest`、`AnnouncementImageResourceTest`、`AnnouncementUiStaticTest`
