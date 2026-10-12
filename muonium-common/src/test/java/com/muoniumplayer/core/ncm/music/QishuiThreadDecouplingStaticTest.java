package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住「后端卡顿不得影响渲染」这条线。
 *
 * <p>这些约束用编译与运行都测不出来 —— 它们只在后端慢/不可用时才显形，而那正是最难复现的
 * 场景。所以这里用源码级断言把它们钉住：谁把阻塞调用挪回渲染路径、或把后端请求塞进
 * 每帧回调，这条测试就会红。</p>
 *
 * <p>不启动 Minecraft、不联网。</p>
 */
class QishuiThreadDecouplingStaticTest {

    private static String read(String relative) throws Exception {
        Path path = ProjectPaths.sourceFile(relative);
        assertTrue(Files.isRegularFile(path), "找不到源文件: " + relative);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static final String MUSIC = "com/muoniumplayer/core/ncm/music/";
    private static final String SCREENS = "com/muoniumplayer/core/screens/ncm/";

    /** 取某个 {@code int} 常量声明的数值。 */
    private static int intConstant(String source, String constant) {
        java.util.regex.Matcher matcher = Pattern.compile(
                Pattern.quote(constant) + "\\s*=\\s*([0-9_]+)").matcher(source);
        assertTrue(matcher.find(), "找不到常量: " + constant);
        return Integer.parseInt(matcher.group(1).replace("_", ""));
    }

    /** 取包含某段文字的那一整行（用来断言声明式的数值关系，而不是只判断出现与否）。 */
    private static String lineContaining(String source, String needle) {
        for (String line : source.split("\n")) {
            if (line.contains(needle)) return line;
        }
        throw new AssertionError("找不到包含这段文字的行: " + needle);
    }

    /** 方法体粗切：从签名行到下一个同缩进的收尾大括号。够用来做「这段里有没有 X」的断言。 */
    private static String methodBody(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, "找不到方法: " + signature);
        int brace = source.indexOf('{', at);
        assertTrue(brace > 0, "方法没有方法体: " + signature);
        int depth = 0;
        for (int i = brace; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return source.substring(brace, i + 1);
            }
        }
        throw new AssertionError("方法体没有收尾: " + signature);
    }

    @Test
    @DisplayName("汽水多账号切换先验证凭据，保存列表与活动会话分离")
    void qishuiAccountProfilesUseValidatedSwitching() throws Exception {
        String profiles = read(MUSIC + "QishuiAccountProfiles.java");
        assertTrue(profiles.contains("ConfigPaths.QISHUI_ACCOUNTS"), "账号列表必须独立存放");
        String switchTo = methodBody(profiles, "public static QishuiMusicService.CookieImport switchTo(");
        assertTrue(switchTo.contains("QishuiMusicService.importCookie(account.cookie)"),
                "切换不能直接覆盖活动 Cookie，必须先走真实账号接口校验");
        assertTrue(profiles.contains("MAX_ACCOUNTS = 8"), "账号列表需要有明确上限");
        assertTrue(profiles.contains("account.id.equals(old.id)"), "保存记录按账号 ID 去重");

        String service = read(MUSIC + "QishuiMusicService.java");
        String imported = methodBody(service, "public static CookieImport importCookie(");
        assertTrue(imported.contains("QishuiAccountProfiles.saveCurrent()"),
                "验证成功后的 Cookie 导入要自动保存账号资料");
        assertTrue(imported.indexOf("pushCookie(joinCookieMap(merged), true)")
                        < imported.indexOf("QishuiSessionStore.replaceCookies(merged)"),
                "在线后端拒绝新 Cookie 时，本地活动账号不得先切换");
        String cache = methodBody(service, "public static void clearUserPlaylistsCache()");
        assertTrue(cache.contains("likedTrackIds = Collections.emptySet()"),
                "切号后不能保留上一个账号的红心集合");
        assertTrue(cache.contains("likedTrackIdsLoadAttempted = false"),
                "切号后要允许重新加载红心集合");
    }

    @Test
    @DisplayName("汽水头像按账号与签名 URL 隔离，昵称不可见时始终有占位")
    void qishuiAccountIdentityHasStableFallbacks() throws Exception {
        String service = read(MUSIC + "QishuiMusicService.java");
        String display = methodBody(service, "public static String displayName(");
        assertTrue(display.contains("汽水音乐账号"), "空白或仅特殊字符昵称应显示固定占位");
        assertTrue(display.contains("Character.isISOControl"), "昵称显示时必须过滤控制字符");
        String avatar = methodBody(service, "public static Location avatarLocation(");
        assertTrue(avatar.contains("SHA-256"), "头像位置应以账号和头像 URL 的稳定摘要隔离");
        assertFalse(avatar.contains("qishui_avatar.png"), "所有账号不能再共享固定头像位置");

        String cadence = read(MUSIC + "NeteaseMusicService.java");
        String location = methodBody(cadence, "public static Location getAccountAvatarLocation(MusicPlatform platform)");
        assertTrue(location.contains("QishuiMusicService.avatarLocation"),
                "当前账号头像与已保存账号行必须共用头像键生成器");
    }

    @Test
    @DisplayName("汽水后端当前活动 Cookie 优先于旧后端 Cookie")
    void qishuiBackendUsesLocalActiveAccountFirst() throws Exception {
        String backend = read(MUSIC + "QishuiBackend.java");
        String onUp = methodBody(backend, "static void onBackendUp()");
        assertTrue(onUp.indexOf("QishuiSessionStore.isLoggedIn()") < onUp.indexOf("else if (backendHasCookie())"),
                "本地活动账号存在时不得回拉后端残留账号");
        assertTrue(onUp.contains("pushCookie(QishuiSessionStore.getCookieHeader(), true)"),
                "本地活动账号应覆盖同步到签名后端");
        assertTrue(onUp.contains("QishuiMusicService.refreshProfile()"),
                "登录资料与签名头像 URL 应在后端启动后刷新");
    }

    @Test
    @DisplayName("汽水账号页提供保存列表、验证切换与 Cookie 添加入口")
    void qishuiAccountUiSupportsSavedProfiles() throws Exception {
        String ui = read(SCREENS + "AccountManagerOverlay.java");
        assertTrue(ui.contains("showSavedQishuiAccounts()"), "汽水详情页应能打开本地存号列表");
        assertTrue(ui.contains("switchQishuiAccount("), "列表项应能切换活动账号");
        assertTrue(ui.contains("removeQishuiAccount("), "列表项应能删除本地快捷记录");
        assertTrue(ui.contains("qishuiAddAccountMode"), "通过列表添加后应返回汽水账号列表");
    }

    @Test
    @DisplayName("来源卡片副标题（每帧求值）只读后端缓存的探活结论，绝不自己探测")
    void accountLineNeverProbes() throws Exception {
        String source = read(SCREENS + "MusicSourceOverlay.java");
        String body = methodBody(source, "private String accountLine(");
        assertFalse(body.contains("isAvailable()"),
                "accountLine 挂在每帧求值的文本 supplier 上，不能调会发健康检查的 isAvailable()；"
                        + "只读的 peekAvailable() 正是为此存在的");
        assertFalse(body.contains("backendHasCookie()"),
                "backendHasCookie() 读的是同一份缓存，但语义上是「探测过之后」，"
                        + "渲染路径统一用 peekHasCookie()");
        assertTrue(body.contains("peekAvailable()"),
                "渲染路径应该读只读缓存");
    }

    @Test
    @DisplayName("换源点击路径不做阻塞探活")
    void sourceSwitchNeverProbes() throws Exception {
        String source = read(MUSIC + "NeteaseMusicService.java");
        String body = methodBody(source, "void setCurrentPlatform(");
        assertFalse(body.contains("QishuiBackend.isAvailable()"),
                "setCurrentPlatform 是来源按钮的点击处理器，跑在主线程上；"
                        + "探活要发健康检查，必须交给后台的 ensureStartedForGame");
    }

    @Test
    @DisplayName("ensureStartedForGame 整体非阻塞：探测与 Cookie 同步都在后台线程里")
    void backendLifecycleIsAsync() throws Exception {
        String source = read(MUSIC + "QishuiBackendLifecycle.java");
        String entry = methodBody(source, "public static void ensureStartedForGame(");
        assertTrue(entry.contains("runAsync"),
                "入口必须把整段判定排到后台，否则客户端初始化线程要吃一次健康探测 + 两次 Cookie 同步");
        assertFalse(entry.contains("isAvailable()"),
                "探测不该在入口的调用线程上跑");
        // 真正的判定在后台执行：启动前可以先用缓存探测，但需要显式失效，避免沿用上局状态。
        String blocking = methodBody(source, "private static void ensureStartedForGameBlocking(");
        assertTrue(blocking.contains("isAvailable()"), "后台判定应探测后端健康");
        assertTrue(blocking.indexOf("invalidateProbe()") < blocking.indexOf("isAvailable()"),
                "启动判定必须先清掉上一次游戏留下的缓存结论");

        String start = methodBody(source, "private static void startOwned()");
        assertTrue(start.contains("probeAlive()"), "新进程启动后的等待必须绕过 30 秒探活缓存");
        assertTrue(start.indexOf("onBackendUp()") < start.indexOf("markBackendRunning()"),
                "正式运行状态应在探活快照刷新和 Cookie 同步后发布");
        assertTrue(start.contains("peekAvailable()"), "Cookie 同步后若后端失去响应，不能标记为已运行");
    }

    @Test
    @DisplayName("下载器启动检查和健康等待均使用新鲜探活")
    void companionDownloadUsesFreshHealthChecks() throws Exception {
        String source = read(MUSIC + "QishuiCompanionDownload.java");
        String start = methodBody(source, "private static Process startBackend(");
        String wait = methodBody(source, "private static boolean awaitHealthy(");
        assertTrue(start.contains("probeAlive()"), "启动前应直接确认监听状态，不能用旧探活缓存");
        assertTrue(wait.contains("probeAlive()"), "启动等待期间每次检查都必须是新鲜探活");
        assertFalse(wait.contains("isAvailable()"), "等待时的负缓存 TTL 不能长于健康等待窗口");
    }

    @Test
    @DisplayName("来源卡有独立后端状态标记，健康态优先于残留失败态")
    void sourceCardShowsBackendReadinessSeparately() throws Exception {
        String download = read(MUSIC + "QishuiCompanionDownload.java");
        String status = methodBody(download, "public static BackendStatus backendStatus()");
        assertTrue(status.indexOf("peekAvailable()") < status.indexOf("case FAILED"),
                "新鲜探活确认可用时，不能继续显示先前记录的启动失败");
        assertTrue(status.contains("BackendStatus.VERIFYING"), "下载到 100% 后应转为校验状态");

        String ui = read(SCREENS + "MusicSourceOverlay.java");
        assertTrue(ui.contains("QishuiCompanionDownload::backendStatusLabel"),
                "汽水来源卡应显示独立的后端状态胶囊");
        assertTrue(ui.contains("this::qishuiBackendStatusColor"), "状态胶囊应按运行/等待/失败使用不同颜色");
        assertTrue(download.contains("签名后端运行中 · 完整版可用"), "正常启动必须明确标记完整后端已运行");
        assertTrue(download.contains("下载已接收，正在校验"), "100% 进度文案必须说明仍在校验而非已启动");
    }

    @Test
    @DisplayName("会话库读盘不阻塞调用线程：渲染路径不会为「登没登录」等一次文件 I/O")
    void sessionStoreReadsAreNonBlocking() throws Exception {
        String source = read(MUSIC + "QishuiSessionStore.java");
        String snapshot = methodBody(source, "static Session snapshot(");
        assertFalse(snapshot.contains("synchronized"),
                "snapshot() 会在渲染路径被调用，不能进锁 —— 写路径持锁期间正在读写磁盘");
        assertFalse(snapshot.contains("ensureLoaded()\n"),
                "snapshot() 不能同步读盘；未加载时该排后台读并按空会话作答");
        assertTrue(snapshot.contains("loaded"),
                "snapshot() 应以 loaded 标志分叉出「后台读 + 先给空会话」");
        // 写入不得原地改集合：读路径不加锁且会遍历 cookie 表。
        assertTrue(source.contains("private static Data editableCopy()"),
                "写入要走「改副本 → 换引用」，否则读路径会撞并发修改");
        assertTrue(source.contains("Data copy()"), "Data 需要深拷贝");
    }

    @Test
    @DisplayName("汽水封面按用途降分辨率，不再拉 960 的整图")
    void coverPixelsAreBounded() throws Exception {
        String source = read(MUSIC + "QishuiMusicService.java");
        Matcher matcher = Pattern.compile("COVER_PIXELS\\s*=\\s*(\\d+)").matcher(source);
        assertTrue(matcher.find(), "找不到 COVER_PIXELS");
        int pixels = Integer.parseInt(matcher.group(1));
        assertTrue(pixels <= 512,
                "COVER_PIXELS=" + pixels + "：封面最大只画到详情页 hero 那不到 200 像素，"
                        + "取值过大时解码内存与主线程贴图上传会成倍涨");
        assertFalse(source.contains("-resize:960:960"),
                "不该再有写死 960 的封面请求");
    }

    @Test
    @DisplayName("主页卡片分批构建：不在一次主线程 turn 里建完所有卡片")
    void homeCardsAreBuiltInBatches() throws Exception {
        String source = read(SCREENS + "panels/HomePanel.java");
        assertTrue(source.contains("buildPendingCards()"),
                "主页需要按帧补卡片的路径");
        assertTrue(source.contains("CARD_BUILD_BATCH"),
                "分批要有批次上限");
        String layout = methodBody(source, "private void layout(List<PlayList> displayedPlayLists,");
        assertTrue(layout.contains("this.pendingCards = new ArrayList<>(displayedPlayLists)"),
                "layout 应把卡片交给待建队列，而不是当场建完");
        assertFalse(layout.contains("new PlaylistWidget("),
                "layout 里不该直接构造卡片 —— 那就是「一口气建完」");
    }

    @Test
    @DisplayName("推荐歌单采样有整轮与整次两层预算，后端半死时也不会让首屏长时间转圈")
    void recommendSamplingIsBounded() throws Exception {
        String source = read(MUSIC + "QishuiMusicService.java");
        assertTrue(source.contains("RECOMMEND_ROUND_TIMEOUT_MS"),
                "每一轮要有超时");
        assertTrue(source.contains("RECOMMEND_TOTAL_BUDGET_MS"),
                "所有轮加起来也要有上限，否则单轮预算会累加成几十秒");
        String round = methodBody(source, "private static int sampleDiscoverMixRound(");
        assertTrue(round.contains("deadline"),
                "八路并发应共享一份整轮预算，而不是每路各给一份超时");
        assertTrue(source.contains("warmDiscoverConnectionAsync"),
                "冷启动的建连开销要有预热通道，否则首屏会白等约 1.6 秒");
    }

    @Test
    @DisplayName("单轮预算必须大于单个请求超时，否则慢网络下永远等不到结果")
    void roundBudgetExceedsRequestTimeout() throws Exception {
        String source = read(MUSIC + "QishuiMusicService.java");
        int requestTimeout = intConstant(source, "REQUEST_TIMEOUT_MS");
        // 单轮预算现在由 REQUEST_TIMEOUT_MS 推导（+余量）；这里把那条关系钉住。
        String roundDecl = lineContaining(source, "RECOMMEND_ROUND_TIMEOUT_MS =");
        assertTrue(roundDecl.contains("REQUEST_TIMEOUT_MS"),
                "单轮预算必须显式建立在请求超时之上。2026-10-11 真机踩过：预算写死 8 秒、"
                        + "请求超时 15 秒，慢网络下请求永远来不及返回，整轮被判空手 —— 主页全空");
        assertTrue(roundDecl.contains("+"),
                "单轮预算要给请求超时留一点余量，不能恰好相等");
        assertTrue(requestTimeout > 0, "找不到 REQUEST_TIMEOUT_MS");
    }

    @Test
    @DisplayName("第一轮空手不得终止采样，也不得把空结果写进主页缓存")
    void emptyFirstRoundIsNotFatal() throws Exception {
        String service = read(MUSIC + "QishuiMusicService.java");
        String sampling = methodBody(service, "public static List<PlayList> recommendPlaylists(int limit, Consumer<List<PlayList>> progress)");
        // 空手时继续下一轮，而不是 break：单轮空手在慢网络上是常态。
        assertTrue(sampling.contains("continue"),
                "一张都没拿到时应继续下一轮，而不是直接收手");
        assertFalse(sampling.contains("firstError"),
                "不再有「第一轮空手就放弃」这条终止路径 —— 它会把慢网络整个判成失败");

        // 主页侧：采样空手时不能缓存，否则 cache-first 分支会让它永远不再重试。
        String home = read(SCREENS + "panels/HomePanel.java");
        assertTrue(home.contains("if (!sampled.isEmpty())"),
                "空结果不得进缓存，否则下一次进主页被 cache-first 命中，永远不再重试");
    }

    @Test
    @DisplayName("封面 URL 在 template_prefix 缺失时兜底，不退回会 400 的裸路径")
    void coverUrlNeverFallsBackToBarePath() throws Exception {
        String source = read(MUSIC + "QishuiMusicService.java");
        assertTrue(source.contains("COVER_TEMPLATE_PREFIX"),
                "缺少 template_prefix 时要有已知可用的兜底前缀");
        String body = methodBody(source, "private static String coverUrl(JsonObject image)");
        // 只要有 uri 就必须产出一个带 resize 后缀的地址，绝不能把裸 url 交出去。
        assertTrue(body.contains("COVER_TEMPLATE_PREFIX"),
                "uri 存在但 template_prefix 缺失时，必须用兜底前缀而不是回落到裸 url");
        assertTrue(body.indexOf("COVER_TEMPLATE_PREFIX") < body.indexOf("\"urls\""),
                "兜底前缀要在「读 urls 数组」之前生效，否则还会走到裸路径那条分支");
        assertTrue(read("com/muoniumplayer/core/utils/network/HttpUtils.java").contains("getResponseCode()"),
                "下载失败要按 HTTP 状态码区分可重试与不可重试：4xx 重复十次只会挂满线程抢带宽");
    }

    @Test
    @DisplayName("随游戏拉起后端时必带看门狗标志——JVM 被强杀时这才是唯一能收尾的一条路")
    void spawnedBackendCarriesWatchdogs() throws Exception {
        String source = read(MUSIC + "QishuiBackendLifecycle.java");
        String spawn = methodBody(source, "static Process spawn(File rootAbs)");
        assertTrue(spawn.contains("--watchdog-stdin"),
                "stdin EOF 看门狗：父进程一消失就退，是任务管理器强杀 JVM 时最快生效的信号");
        assertTrue(spawn.contains("--parent-pid"),
                "父 pid 看门狗：stdin 那条被管道形态搅乱时还有这条兜底（不依赖管道，更稳）");
        assertTrue(spawn.contains("ProcessHandle.current().pid()"),
                "传的必须是本 JVM 的 pid —— 后端要盯的就是游戏进程本身");
        assertTrue(source.contains("sweepOrphans"),
                "还要有「下一局清扫上一局孤儿」的路径：旧版本后端没有看门狗，只能靠它兜住");
    }

    @Test
    @DisplayName("收孤儿前必须二次确认父进程仍不存在，绝不误杀别人正在用的后端")
    void orphanSweepRechecksBeforeKilling() throws Exception {
        String source = read(MUSIC + "QishuiBackendLifecycle.java");
        String sweep = methodBody(source, "private static boolean sweepOrphans()");
        // 两次 ProcessHandle.of(parentPid).isAlive()：进门前一次、动手前再一次。
        int checks = sweep.split("ProcessHandle\\.of\\(parentPid\\)", -1).length - 1;
        assertTrue(checks >= 2,
                "探活结论最长 30 秒，期间父进程完全可能重新起来；动手前必须再核一次（当前只核了 "
                        + checks + " 次）");
        assertTrue(sweep.contains("peekWatchdog()"),
                "只接管「自报带看门狗」的实例：parent_pid=0 的是用户手动开的，不能动");
    }

    @Test
    @DisplayName("运行时改名后旧名仍要认——升级窗口里 1.1.0 的安装还在跑")
    void runtimeNameFallsBackToLegacy() throws Exception {
        String source = read(MUSIC + "QishuiCompanionDownload.java");
        String find = methodBody(source, "static File findRuntime(File root)");
        assertTrue(find.contains("runtimeFileName()"), "先找新名 qishui-backend.exe");
        assertTrue(find.contains("\"node.exe\""),
                "再回落旧名 node.exe：热补是后台的，只认新名会把存量安装误判成「没装」，"
                        + "触发一次 33 MB 的重复下载");
        assertTrue(source.contains("sweepStaleFiles"),
                "旧运行时要能被清掉，否则改完名后 87 MB 的 node.exe 永远留在盘上；"
                        + "清扫必须按清单走（sweepStaleFiles），不能退回只认 node.exe 的窄口径");
    }

    @Test
    @DisplayName("探活不带副作用：清扫孤儿后等端口的轮询不得触发下载/拉起")
    void probeAliveHasNoSideEffects() throws Exception {
        String source = read(MUSIC + "QishuiBackend.java");
        String probe = methodBody(source, "static boolean probeAlive()");
        assertFalse(probe.contains("onBackendMissing"),
                "「孤儿已杀、新实例未起」的空窗里若触发 onBackendMissing，会多派一个下载线程去抢 8787");
        assertFalse(probe.contains("lastProbe"),
                "单次探活不该写探测缓存（它不返回 has_cookie/pid 那些字段，写了就是脏数据）");
    }

    @Test
    @DisplayName("后端响应匹配必须容忍美化 JSON 的空格——裸查 \\\"ok\\\":true 会把成功当失败")
    void backendResponseMatchingToleratesPrettyJson() throws Exception {
        String source = read(MUSIC + "QishuiBackend.java");
        for (String signature : new String[]{
                "static boolean probeAlive()",
                "static boolean pushCookie(String cookie, boolean force)"}) {
            String body = methodBody(source, signature);
            for (String field : new String[]{"\\\"ok\\\":true", "\\\"has_cookie\\\":false"}) {
                assertFalse(body.contains("body().contains(\"" + field + "\")"),
                        signature + " 裸匹配 " + field + "：qs-backend 所有响应都是"
                                + " JSON.stringify(obj, null, 1) 美化输出，键值对之间有空格，"
                                + "裸匹配永远失败（2026-10-11 健康检查、2026-10-12 Cookie 推送"
                                + "各踩过一次）。必须先 replace(\" \", \"\") 再 contains");
            }
            if (signature.contains("pushCookie")) {
                assertTrue(body.contains("replace(\" \", \"\")"),
                        "推送响应判定必须先去空格再匹配（见上一条断言的教训）");
            }
        }
    }

    @Test
    @DisplayName("热补的落盘顺序：清单先于清扫，清单先于删暂存目录——顺序错了清单就是空的")
    void hotPatchWritesManifestBeforeSweepingAndBeforeDeletingStaging() throws Exception {
        String source = read(MUSIC + "QishuiCompanionDownload.java");
        String refresh = methodBody(source, "static void refreshIfNeeded()");
        int manifest = refresh.indexOf("writeManifest(packRoot, root)");
        int sweep = refresh.indexOf("sweepStaleFiles()");
        int deleteIncoming = refresh.indexOf("deleteRecursively(incoming)");
        assertTrue(manifest > 0 && sweep > 0 && deleteIncoming > 0,
                "热补路径里三件事都要在：写清单 / 清扫 / 删暂存目录");
        assertTrue(manifest < sweep,
                "清单必须先写再清扫：清扫以清单为唯一依据，顺序反了新文件会被当成旧残留删掉");
        assertTrue(manifest < deleteIncoming,
                "清单必须在删暂存目录之前写：清单记录的就是刚解压出来的那份内容，"
                        + "incoming 删掉后无从生成，空清单会让清扫退化成什么都不清");
    }

    @Test
    @DisplayName("首次安装同样先写清单再清扫，且版本标记要等落点校验通过")
    void firstInstallKeepsTheSameOrdering() throws Exception {
        String source = read(MUSIC + "QishuiCompanionDownload.java");
        String run = methodBody(source, "private static void run()");
        int manifest = run.indexOf("writeManifest(packRoot, root)");
        int sweep = run.indexOf("sweepStaleFiles()");
        int marker = run.indexOf("writeVersionMarker()");
        assertTrue(manifest > 0 && sweep > 0,
                "首次安装也要写清单并清扫——否则用户第一次装完就自带一份混装");
        assertTrue(manifest < sweep,
                "首次安装同样是「先清单、后清扫」：清单描述装完后目录该有什么");
        assertTrue(run.indexOf("安装落点校验失败") < marker,
                "版本标记要等落点校验（server.mjs + 运行时都在）通过才写："
                        + "标记一写，热补就不会再跑，半成品安装会被永久当成好的");
    }

    @Test
    @DisplayName("每局保养在生命周期里被调用：被占用的文件只能等旧进程退出后才收得掉")
    void maintenanceRunsEveryGameStart() throws Exception {
        String source = read(MUSIC + "QishuiBackendLifecycle.java");
        String start = methodBody(source, "private static void ensureStartedForGameBlocking()");
        assertTrue(start.contains("maintainInstallRoot()"),
                "安装目录保养必须每局跑：热补时被占用的运行时/清单外文件，"
                        + "只有等旧进程退出后才删得掉，而那时热补不会再跑（版本标记已是新版）");
        assertTrue(start.contains("refreshIfNeeded()"), "热补也在同一条启动链上");
    }

    @Test
    @DisplayName("清扫与暂存启用都不碰用户数据：保留名单里是设备身份、Cookie 与音频缓存")
    void sweepPreservesUserData() throws Exception {
        String source = read(MUSIC + "QishuiCompanionDownload.java");
        String names = declarationOf(source, "PRESERVED_NAMES");
        for (String guarded : new String[]{"config.json", "pack.version", "backend.log"}) {
            assertTrue(names.contains(guarded),
                    "保留名单少了 " + guarded + "——扫掉的每一样都是用户找不回来的东西");
        }
        assertTrue(declarationOf(source, "PRESERVED_PREFIXES").contains("cache/"),
                "已解密的音频缓存整个目录都要保留（那是取流链路重复下载的成本）");
    }

    /** 取一个多行字段声明（从「名字 = 」到配对的右括号），跨行也能读全。 */
    private static String declarationOf(String source, String fieldName) {
        int at = source.indexOf(fieldName + " = ");
        assertTrue(at >= 0, "找不到字段声明: " + fieldName);
        int depth = 0;
        for (int i = at; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return source.substring(at, i + 1);
            } else if (c == ';' && depth == 0) {
                return source.substring(at, i + 1);
            }
        }
        throw new AssertionError("字段声明没有收尾: " + fieldName);
    }
}
