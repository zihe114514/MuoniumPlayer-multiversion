// MuoniumPlayer 的系统媒体会话助手（SMTC / Windows Media Transport Controls）。
//
// 为什么要有这个文件：SMTC 是 WinRT API，JVM 里没有可靠的调用路子（手算 COM vtable
// 偏移写错一次就是整个游戏原生崩溃，不是抛个异常能兜住的）。而 Windows 自带的
// .NET Framework 4.x 能直接吃 C:\Windows\System32\WinMetadata 里的 winmd 元数据。
// 所以这里放一份 C# 源码，模组第一次需要它时用系统自带的 csc.exe 现场编成 exe
// （仓库里不留二进制，用户机也不用装任何东西），之后作为常驻子进程通过 stdin/stdout
// 与本模组通信。子进程再怎么出错最多是「镜像不可用」，带不走游戏。
//
// 语法约束（重要）：系统自带的 csc.exe 只支持到 C# 5。因此本文件里
// 不允许出现插值字符串 $""、空条件运算符 ?.、表达式体成员 => 、nameof。
// 这条不是风格偏好，是编译门槛 —— 违反就会在用户首次开启镜像时表现为
// 「编译失败」，而那个现场在日志里只有一行 csc 的中文乱码报错。
//
// 协议 v2（每行一条，stdout 输出 UTF-8 不带 BOM，由 Java 侧显式按 UTF-8 读）：
//   启动即输出一行 {"event":"ready","version":2}
//   in  poll            -> {"event":"state","v":2,"session":true,"app":"...","title":"...",
//                           "artist":"...","album":"...","subtitle":"...","albumArtist":"...",
//                           "track":3,"genres":"Pop;Rock","posMs":123,"durMs":456,"rate":1.0,
//                           "state":"Playing","caps":{"play":true,"pause":false,
//                           "toggle":true,"next":false,"prev":false,"seek":true},
//                           "art":"art-1a2b3c.png",
//                           "sessions":[{"app":"A","state":"Playing","title":"x"},...]}
//                        v2 新增：subtitle/albumArtist/track/genres/rate/sessions。
//                        posMs 是按 PlaybackRate 与 LastUpdatedTime 外推过的读数，
//                        不是播放器最后一次推的那条原始值。
//                        没有会话时 {"event":"state","session":false}
//                        "art" 是本次会话封面图的文件名（与助手 exe 同目录），没有封面时为空串。
//                        同一首只在第一次 poll 时取流落盘，之后每拍原样回报同一个文件名。
//                        事件驱动：助手订阅了五个 SMTC 事件（SessionsChanged、
//                        CurrentSessionChanged、MediaPropertiesChanged、
//                        TimelinePropertiesChanged、PlaybackInfoChanged），任何一拍变化都会
//                        立刻主动推一条 state，poll 只是兜底的心跳 —— 换歌的感知延迟从
//                        「最多 500ms」降到毫秒级，而轮询里不再每拍都做 WinRT 往返。
//   in  cmd <key>       -> {"event":"cmd","key":"toggle","accepted":true,"error":""}
//                        key: play / pause / toggle / next / prev / stop / seek / rate
//                        命令发给「选中的那个会话」，不是系统觉得你想控的那一个 ——
//                        见下面多会话选择一段。
//   in  seek <毫秒>     -> 同 cmd，key 为 seek
//   in  vol <0..1>      -> {"event":"vol","ok":true,"vol":0.8300,"pid":19520}
//                        写的是「那支程序在音量合成器里的一格」，不是主音量；配不上程序时
//                        ok:false 并且什么都不写。写成功后新的值会随下一条 poll 的状态行
//                        一起回来（"volok"/"vol"/"pid"），所以 Java 侧不必解这条。
//   in  list            -> {"event":"list","sessions":[...]}：所有会话的快照，诊断用。
//   in  use <AUMID>     -> 钉住镜像哪一支会话（按 SourceAppUserModelId 完整匹配）；
//                          "use -" 回到自动。钉住的会话死了就回退自动挑选。
//                          自动挑选的顺序：钉住 > 上一支还在放 > 任何 Playing 的 >
//                          系统的 current > 第一支。这条顺序是实测出来的：两个播放器
//                          同时在放时，只按「谁在放」选会在两台之间来回抖。
//   in  pub on / pub off             -> 开关「本模组自己的系统媒体会话」（发布模式）。
//   in  pub meta <json>              -> {"title":..,"artist":..,"album":..,"albumArtist":..}
//   in  pub state <Playing|Paused|Stopped>
//   in  pub tl <posMs> <durMs>       -> 时间轴；建议播放中每秒推一次（MS 建议约 5 秒）。
//   in  pub art <路径>               -> 封面文件路径（JSON 字符串），换歌时推一次。
//   out {"event":"pubcmd","key":"play|pause|stop|next|prev","arg":""}   媒体键回传
//   out {"event":"pubcmd","key":"seek","arg":"<毫秒>"}                  浮窗拖进度回传
//   out {"event":"pub","ok":false,"error":"..."}                        发布端只报失败
//
// 发布模式的实现说明（Win32 桌面应用接入 SMTC 的唯一正路）：
//   SystemMediaTransportControls 的 GetForCurrentView 是 UWP 专属，桌面进程必须走
//   ISystemMediaTransportControlsInterop::GetForWindow(hwnd, riid, ppv)。这个接口由
//   激活工厂实现（RoGetActivationFactory 拿），IID 是
//   ddb0472d-c911-4a1f-86d9-dc3d71a95f5a —— 抄自 mingw-w64 镜像的 SDK 头
//   systemmediatransportcontrolsinterop.h，不是凭记忆写的；本文件历史教训是
//   凭记忆写 COM 的 IID/方法顺序全错过一遍。
//   GetForWindow 的 riid 要 IUnknown 就够：RCW 会带着 WinRT 运行类名，
//   投影回 Windows.Media.SystemMediaTransportControls 不需要默认接口的 IID。
//
// 刻意不做的事：读不到会话时不返回任何"看起来能播"的假数据。外部那套 LyricSync 为了
// 方便测试在 winsdk 不可用时会退到内部模拟时间轴，结果玩家看到的是「歌词在滚但系统
// 根本没读到」，这类假信号比没有功能更糟。这里 session:false 就是最终答案。

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using Windows.Foundation;
using Windows.Media;
using Windows.Media.Control;
using Windows.Storage;
using Windows.Storage.Streams;

internal static class SmtcWatcher
{
    private const int ProtocolVersion = 2;

    /** 封面落盘的上限。专辑图常见 300KB~1MB，超过这个数就不当它是封面了。 */
    private const long MaxArtBytes = 4L * 1024L * 1024L;
    // 取封面那条的等待轮数（每轮 4ms = 400ms）。助手是串行读 stdin 的，这里等太久
    // 会把「暂停/下一首」这类命令一起拖住，所以宁可这一首没封面。
    private const int ArtSpins = 100;

    private static GlobalSystemMediaTransportControlsSessionManager manager;

    // ── 多会话选择 ─────────────────────────────────────────────────────────────
    //
    // GetCurrentSession() 是「系统觉得用户最想控的那个」，纯启发式：网易云挂着没播 +
    // Chrome 在放歌时，它完全可能指着网易云的暂停会话。v1 只认它，表现就是镜像错了
    // 播放器、命令打到别的程序上。v2 的选择顺序（use 钉住 > 上一支还在放 > 任何在放的
    // > 系统 current > 第一支）里「上一支还在放就别换」这一条专门防抖：两台播放器
    // 都在放时按「谁在放」选，会在两台之间来回横跳。

    /** 选中的会话。事件与命令都打在它身上。 */
    private static GlobalSystemMediaTransportControlsSession selected;
    /** 用户钉住的 AUMID；null 表示自动挑选。 */
    private static string pinnedAumid;
    /** 选中会话的媒体属性缓存；事件驱动后不再每拍重取。 */
    private static GlobalSystemMediaTransportControlsSessionMediaProperties cachedProps;
    /** 这一时长属于哪一首（app|title|artist），时长迟滞只对同一首生效。 */
    private static string lastDurationKey;
    private static long lastKnownDurationMs;
    /** 事件订阅是否成功。投影缺事件或订阅失败时退回纯轮询，功能不塌。 */
    private static bool eventsHooked;

    /** 事件线程与 stdin 主线程都要动这些字段，全部收进这一把锁。 */
    private static readonly object SessionLock = new object();
    /** 事件置脏的标志；poll 兜底时靠它决定要不要做真正的 WinRT 往返。 */
    private static bool stateDirty = true;


    private static GlobalSystemMediaTransportControlsSessionManager Manager()
    {
        lock (SessionLock)
        {
            if (manager != null) return manager;
            GlobalSystemMediaTransportControlsSessionManager created =
                Await(GlobalSystemMediaTransportControlsSessionManager.RequestAsync());
            manager = created;
            HookManagerEvents(created);
            return created;
        }
    }

    // 不用 System.WindowsRuntimeSystemExtensions.AsTask()：那要多引一个 GAC 里的
    // System.Runtime.WindowsRuntime.dll，路径按版本号分目录、在不同机器上要 glob 找，
    // 而且实测它的泛型重载在这个投影组合下解析不出来。轮询 IAsyncOperation.Status
    // 再取 GetResults() 只要 winmd 本身，依赖更少、行为更好推理。
    private static T Await<T>(IAsyncOperation<T> operation)
    {
        return Await(operation, 500);
    }

    /**
     * 轮询等待一个 WinRT 异步操作。
     *
     * @param spins 轮询次数，每次隔 4ms。取封面那条给的是 100（400ms）—— stdin 的
     *              串行处理里卡太久会把「暂停/下一首」这类命令一起拖住。
     */
    private static T Await<T>(IAsyncOperation<T> operation, int spins)
    {
        for (int i = 0; i < spins; i++)
        {
            AsyncStatus status = operation.Status;
            if (status == AsyncStatus.Started)
            {
                Thread.Sleep(4);
                continue;
            }
            if (status == AsyncStatus.Completed) return operation.GetResults();
            throw new InvalidOperationException("async " + status);
        }
        throw new TimeoutException("async timeout");
    }

    /**
     * 只等完成、不要结果。
     *
     * <p>存在的理由很实在：{@code DataReader.LoadAsync} 返回的是 {@code DataReaderLoadOperation}
     * 这个类，实测 csc 在这个投影组合下不肯把它当 {@code IAsyncOperationWithProgress<uint,uint>}
     * 用（连显式类型参数都推不出接口转换），而我们要的只是「它读完了没有」。IAsyncInfo 是所有
     * WinRT 异步操作的公共底，绕开那一层投影就好。</p>
     */
    private static void WaitDone(object operation)
    {
        WaitDone(operation, 500);
    }

    private static void WaitDone(object operation, int spins)
    {
        IAsyncInfo info = (IAsyncInfo)operation;
        for (int i = 0; i < spins; i++)
        {
            AsyncStatus status = info.Status;
            if (status == AsyncStatus.Started)
            {
                Thread.Sleep(4);
                continue;
            }
            if (status == AsyncStatus.Completed) return;
            throw new InvalidOperationException("async " + status);
        }
        throw new TimeoutException("async timeout");
    }

    // ── 事件订阅 ───────────────────────────────────────────────────────────────
    //
    // 订阅失败（老版本 Windows 缺事件、投影出入）不致命：eventsHooked 保持 false，
    // 之后走纯轮询，行为退回 v1 只是慢一点，不会没有功能。

    // += 事件语法可用，前提是编译引用里带上 GAC 的
    // System.Runtime.InteropServices.WindowsRuntime.dll（EventRegistrationToken 的正主，
    // mscorlib 里那份同名类型是另一种类型身份，带错引用会报 CS0029/CS1545）。

    private static void HookManagerEvents(GlobalSystemMediaTransportControlsSessionManager mgr)
    {
        try
        {
            mgr.CurrentSessionChanged += OnCurrentSessionChanged;
            mgr.SessionsChanged += OnSessionsChanged;
            eventsHooked = true;
        }
        catch (Exception e)
        {
            EmitError("events", e);
            eventsHooked = false;
        }
    }

    private static void OnCurrentSessionChanged(GlobalSystemMediaTransportControlsSessionManager sender,
        CurrentSessionChangedEventArgs args)
    {
        OnMediaEvent();
    }

    private static void OnSessionsChanged(GlobalSystemMediaTransportControlsSessionManager sender,
        SessionsChangedEventArgs args)
    {
        OnMediaEvent();
    }

    private static void OnSessionPropsChanged(GlobalSystemMediaTransportControlsSession sender,
        MediaPropertiesChangedEventArgs args)
    {
        // 换歌就是从这里知道的：先把属性缓存拍掉，BuildState 才会去取新的曲名歌手。
        lock (SessionLock) { cachedProps = null; stateDirty = true; }
        OnMediaEvent();
    }

    private static void OnSessionTimelineChanged(GlobalSystemMediaTransportControlsSession sender,
        TimelinePropertiesChangedEventArgs args)
    {
        OnMediaEvent();
    }

    private static void OnSessionPlaybackChanged(GlobalSystemMediaTransportControlsSession sender,
        PlaybackInfoChangedEventArgs args)
    {
        OnMediaEvent();
    }

    /**
     * 五个事件共同的处理：重选会话、置脏、立刻推一条状态。
     *
     * <p>事件的回调跑在 WinRT 线程池线程上，绝不能在这里做阻塞的 WinRT 往返
     * （TryGetMediaPropertiesAsync 那条）—— 那会把事件线程占住，连环事件会排队。
     * 这里只做同步读：换歌后的属性重取交给下一条 poll 或下一次事件，最多慢半拍。</p>
     */
    private static void OnMediaEvent()
    {
        try
        {
            lock (SessionLock)
            {
                stateDirty = true;
                ReselectLocked();
                EmitLocked(BuildState());
            }
        }
        catch (Exception e)
        {
            EmitError("event", e);
        }
    }

    /** 只在持有 SessionLock 时调用。重选会话；选中对象变了就换订阅、重置缓存。 */
    private static void ReselectLocked()
    {
        if (manager == null) return;
        IReadOnlyList<GlobalSystemMediaTransportControlsSession> sessions = SafeSessions();
        GlobalSystemMediaTransportControlsSession pick = ChooseLocked(sessions);
        if (ReferenceEquals(pick, selected)) return;
        UnhookSessionEvents();
        selected = pick;
        cachedProps = null;
        lastDurationKey = null;
        lastKnownDurationMs = 0L;
        HookSessionEvents();
    }

    private static IReadOnlyList<GlobalSystemMediaTransportControlsSession> SafeSessions()
    {
        try { return manager.GetSessions(); }
        catch (Exception) { return null; }
    }

    /**
     * 多会话挑选。null 表示系统里一个媒体会话都没有。
     *
     * <p>顺序见类头注释。钉住的会话不在了（进程退了、SMTC 注销了）就静默回自动：
     * 钉住只是一个偏好，不是保命符。</p>
     */
    private static GlobalSystemMediaTransportControlsSession ChooseLocked(
        IReadOnlyList<GlobalSystemMediaTransportControlsSession> sessions)
    {
        int count = sessions == null ? 0 : sessions.Count;
        if (count == 0) return null;

        if (!string.IsNullOrEmpty(pinnedAumid))
        {
            for (int i = 0; i < count; i++)
            {
                GlobalSystemMediaTransportControlsSession candidate = sessions[i];
                if (candidate != null
                        && string.Equals(NullToEmpty(candidate.SourceAppUserModelId), pinnedAumid,
                            StringComparison.Ordinal))
                {
                    return candidate;
                }
            }
            // 钉住的死了：回到自动，但不故意清 pinned —— 用户可能只是暂时关了那支播放器。
        }

        // 上一支还在放就别换台（防抖）。
        if (selected != null && StatusOf(selected) == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing)
        {
            return selected;
        }
        // 任何一支在放的优先于暂停的。
        for (int i = 0; i < count; i++)
        {
            GlobalSystemMediaTransportControlsSession candidate = sessions[i];
            if (candidate != null
                    && StatusOf(candidate) == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing)
            {
                return candidate;
            }
        }
        GlobalSystemMediaTransportControlsSession current = null;
        try { current = manager.GetCurrentSession(); }
        catch (Exception) { current = null; }
        if (current != null) return current;
        return sessions[0];
    }

    private static GlobalSystemMediaTransportControlsSessionPlaybackStatus StatusOf(
        GlobalSystemMediaTransportControlsSession session)
    {
        try
        {
            GlobalSystemMediaTransportControlsSessionPlaybackInfo info = session.GetPlaybackInfo();
            return info == null
                ? GlobalSystemMediaTransportControlsSessionPlaybackStatus.Closed
                : info.PlaybackStatus;
        }
        catch (Exception)
        {
            return GlobalSystemMediaTransportControlsSessionPlaybackStatus.Closed;
        }
    }

    private static void HookSessionEvents()
    {
        GlobalSystemMediaTransportControlsSession session = selected;
        if (session == null || !eventsHooked) return;
        try
        {
            session.MediaPropertiesChanged += OnSessionPropsChanged;
            session.TimelinePropertiesChanged += OnSessionTimelineChanged;
            session.PlaybackInfoChanged += OnSessionPlaybackChanged;
        }
        catch (Exception e)
        {
            EmitError("sess-events", e);
        }
    }

    private static void UnhookSessionEvents()
    {
        GlobalSystemMediaTransportControlsSession session = selected;
        if (session == null || !eventsHooked) return;
        try
        {
            session.MediaPropertiesChanged -= OnSessionPropsChanged;
            session.TimelinePropertiesChanged -= OnSessionTimelineChanged;
            session.PlaybackInfoChanged -= OnSessionPlaybackChanged;
        }
        catch (Exception)
        {
            // 退订失败最坏是多几条无害回调，不值得为它打断镜像。
        }
    }

    // ── 输出 ───────────────────────────────────────────────────────────────────

    private static string Esc(string value)
    {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder(value.Length + 8);
        for (int i = 0; i < value.Length; i++)
        {
            char c = value[i];
            if (c == '"') sb.Append("\\\"");
            else if (c == '\\') sb.Append("\\\\");
            else if (c == '\n') sb.Append("\\n");
            else if (c == '\r') sb.Append("\\r");
            else if (c == '\t') sb.Append("\\t");
            else if (c < ' ') sb.Append("\\u").Append(((int)c).ToString("x4"));
            else sb.Append(c);
        }
        return sb.ToString();
    }

    private static void Emit(string line)
    {
        Console.Out.WriteLine(line);
        Console.Out.Flush();
    }

    /** 事件线程走这一条：持有 SessionLock 串行输出，避免两条 state 交错成半句。 */
    private static void EmitLocked(string line)
    {
        if (line == null) return;
        Emit(line);
    }

    // ── 状态构建 ───────────────────────────────────────────────────────────────

    private static string State()
    {
        // Manager() 自己会拿锁（首次要等 RequestAsync），必须在进锁之前调，
        // 否则第一次 poll 会自己锁死自己、永远报 no_manager。
        try { Manager(); }
        catch (Exception e)
        {
            EmitError("manager", e);
            lock (SessionLock) { manager = null; }
        }
        lock (SessionLock)
        {
            // 先重选再清脏：顺序反了的话 BuildState 里「脏了才重选」永远不跑，
            // poll 路径的 selected 恒为 null（info 命令能看见会话、poll 看不见的怪象就是它）。
            string line = BuildState();
            stateDirty = false;
            return line;
        }
    }

    /**
     * 构建一条状态行。持锁调用。
     *
     * <p>属性缓存策略：事件驱动后 MediaPropertiesChanged 会置脏，poll 只在脏的时候才
     * 做 TryGetMediaPropertiesAsync 那次 WinRT 往返；曲名歌手这些同步值每拍照读
     * （它们本来就是同步方法，成本一次跨进程调用，换来的是事件丢失时的兜底正确）。</p>
     */
    private static string BuildState()
    {
        try
        {
            if (manager == null)
            {
                return "{\"event\":\"state\",\"v\":2,\"session\":false,\"error\":\"no_manager\"}";
            }
            if (stateDirty) ReselectLocked();
            GlobalSystemMediaTransportControlsSession session = selected;
            if (session == null)
            {
                return "{\"event\":\"state\",\"v\":2,\"session\":false}";
            }

            if (cachedProps == null)
            {
                try { cachedProps = Await(session.TryGetMediaPropertiesAsync(), 250); }
                catch (Exception e) { cachedProps = null; EmitError("props", e); }
            }

            GlobalSystemMediaTransportControlsSessionTimelineProperties timeline =
                session.GetTimelineProperties();
            GlobalSystemMediaTransportControlsSessionPlaybackInfo playback = session.GetPlaybackInfo();
            GlobalSystemMediaTransportControlsSessionPlaybackControls controls = playback.Controls;

            long posMs = (long)timeline.Position.TotalMilliseconds;
            long durMs = (long)timeline.EndTime.TotalMilliseconds;
            // 倍速不在 TimelineProperties 上，在 PlaybackInfo（可空）；取不到按 1x 处理。
            double? rateBox = playback.PlaybackRate;
            double rate = rateBox.HasValue && rateBox.Value > 0d ? rateBox.Value : 1d;

            string app = NullToEmpty(session.SourceAppUserModelId);
            string title = cachedProps == null ? "" : NullToEmpty(cachedProps.Title);
            string artist = cachedProps == null ? "" : NullToEmpty(cachedProps.Artist);
            bool playing = playback.PlaybackStatus
                == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;

            // 位置外推：Windows 自己的浮窗就是 Position + (now - LastUpdatedTime) × rate。
            // 有的播放器只在换歌/seek 时推一次位置，不外推的话那条值会整段冻结；
            // LastUpdatedTime 是个没有意义的旧值（0001 年）时说明播放器没填，不外推。
            try
            {
                DateTime updated = timeline.LastUpdatedTime.UtcDateTime;
                if (playing && updated.Year > 2000)
                {
                    double elapsed = (DateTime.UtcNow - updated).TotalMilliseconds;
                    if (elapsed > 0d) posMs = (long)(posMs + elapsed * rate);
                }
            }
            catch (Exception)
            {
                // LastUpdatedTime 取不到就退回原始值：宁可静止也不要跳到天上去。
            }
            if (posMs < 0L) posMs = 0L;
            if (durMs > 0L && posMs > durMs) posMs = durMs;

            // 时长迟滞：Firefox 拖动进度时 EndTime 会瞬时归 0（Mozilla bug 2065128），
            // 不迟滞的话进度条会在那一拍闪成 0/0。归 0 且同一首时沿用上一拍的时长。
            string durationKey = app + "|" + title + "|" + artist;
            if (durMs > 0L)
            {
                if (durMs != lastKnownDurationMs || lastDurationKey != durationKey)
                {
                    lastKnownDurationMs = durMs;
                    lastDurationKey = durationKey;
                }
            }
            else if (lastKnownDurationMs > 0L && lastDurationKey == durationKey)
            {
                durMs = lastKnownDurationMs;
            }
            else
            {
                lastKnownDurationMs = 0L;
                lastDurationKey = null;
            }

            string subtitle = cachedProps == null ? "" : NullToEmpty(cachedProps.Subtitle);
            string albumArtist = cachedProps == null ? "" : NullToEmpty(cachedProps.AlbumArtist);
            int trackNumber = cachedProps == null ? 0 : cachedProps.TrackNumber;
            StringBuilder genres = new StringBuilder(16);
            if (cachedProps != null)
            {
                try
                {
                    IReadOnlyList<string> list = cachedProps.Genres;
                    for (int i = 0; i < list.Count; i++)
                    {
                        if (i > 0) genres.Append(';');
                        genres.Append(list[i]);
                    }
                }
                catch (Exception)
                {
                    // Genres 不少播放器根本不给；空着就好。
                }
            }

            // 封面放在状态行一起回，是为了让「这一首」和「这张封面」天然同拍：
            // 分成两条事件就会出现封面先到、曲名后到（或反过来）的错配窗口。
            string art = Artwork(cachedProps, title, artist);

            StringBuilder sb = new StringBuilder(384);
            sb.Append("{\"event\":\"state\",\"v\":2,\"session\":true");
            sb.Append(",\"app\":\"").Append(Esc(app)).Append('"');
            sb.Append(",\"title\":\"").Append(Esc(title)).Append('"');
            sb.Append(",\"artist\":\"").Append(Esc(artist)).Append('"');
            sb.Append(",\"album\":\"").Append(Esc(cachedProps == null ? "" : NullToEmpty(cachedProps.AlbumTitle))).Append('"');
            sb.Append(",\"subtitle\":\"").Append(Esc(subtitle)).Append('"');
            sb.Append(",\"albumArtist\":\"").Append(Esc(albumArtist)).Append('"');
            sb.Append(",\"track\":").Append(trackNumber.ToString(CultureInfo.InvariantCulture));
            sb.Append(",\"genres\":\"").Append(Esc(genres.ToString())).Append('"');
            sb.Append(",\"posMs\":").Append(posMs.ToString(CultureInfo.InvariantCulture));
            sb.Append(",\"durMs\":").Append(durMs.ToString(CultureInfo.InvariantCulture));
            sb.Append(",\"rate\":").Append(rate.ToString("0.####", CultureInfo.InvariantCulture));
            // 循环与随机是只读的旁观信息（会话上没有任何 setter），显示徽标是上限。
            sb.Append(",\"repeat\":\"").Append(Esc(RepeatText(playback.AutoRepeatMode))).Append('"');
            sb.Append(",\"shuffle\":").Append(playback.IsShuffleActive.HasValue
                ? Bool(playback.IsShuffleActive.Value) : "false");
            sb.Append(",\"state\":\"").Append(Esc(playback.PlaybackStatus.ToString())).Append('"');
            sb.Append(",\"caps\":{\"play\":").Append(Bool(controls.IsPlayEnabled))
              .Append(",\"pause\":").Append(Bool(controls.IsPauseEnabled))
              .Append(",\"toggle\":").Append(Bool(controls.IsPlayPauseToggleEnabled))
              .Append(",\"next\":").Append(Bool(controls.IsNextEnabled))
              .Append(",\"prev\":").Append(Bool(controls.IsPreviousEnabled))
              .Append(",\"seek\":").Append(Bool(controls.IsPlaybackPositionEnabled))
              .Append("}");
            sb.Append(",\"art\":\"").Append(Esc(art)).Append('"');
            // 音量跟状态行一起回，理由与封面同一：值要能对上「这一拍这个播放器」。
            sb.Append(VolumeFragment(app));
            sb.Append(",\"sessions\":").Append(SessionsFragment());
            sb.Append('}');
            return sb.ToString();
        }
        catch (Exception e)
        {
            // 会话在两次读取之间结束是常态；这种情况报 no_session 而不是错误，
            // Java 侧据此把镜像切到「无媒体会话」，不要把它记成助手故障。
            manager = null;
            selected = null;
            cachedProps = null;
            return "{\"event\":\"state\",\"v\":2,\"session\":false,\"error\":\""
                + Esc(e.GetType().Name) + "\"}";
        }
    }

    /** 所有会话的一小段 JSON。诊断与「手动挑哪支」都从这里起步。 */
    private static string SessionsFragment()
    {
        StringBuilder sb = new StringBuilder(64);
        sb.Append('[');
        IReadOnlyList<GlobalSystemMediaTransportControlsSession> sessions = SafeSessions();
        if (sessions != null)
        {
            for (int i = 0; i < sessions.Count; i++)
            {
                GlobalSystemMediaTransportControlsSession session = sessions[i];
                if (session == null) continue;
                if (sb.Length > 1) sb.Append(',');
                sb.Append("{\"app\":\"").Append(Esc(session.SourceAppUserModelId)).Append('"');
                sb.Append(",\"state\":\"").Append(Esc(StatusOf(session).ToString())).Append('"');
                string title = "";
                try { title = NullToEmpty(selected != null && ReferenceEquals(session, selected) && cachedProps != null ? cachedProps.Title : ""); }
                catch (Exception) { title = ""; }
                sb.Append(",\"title\":\"").Append(Esc(title)).Append("\"}");
            }
        }
        sb.Append(']');
        return sb.ToString();
    }

    private static string Bool(bool value)
    {
        return value ? "true" : "false";
    }

    /** 循环模式的短词：None/Track/List。取不到（播放器没报）按 None 显示。 */
    private static string RepeatText(MediaPlaybackAutoRepeatMode? mode)
    {
        try
        {
            if (mode.HasValue) return mode.Value.ToString();
        }
        catch (Exception) { }
        return "None";
    }

    private static string NullToEmpty(string value)
    {
        return value == null ? "" : value;
    }

    /**
     * 取当前这首的外部封面，落盘成 png，返回文件名（没有封面返回空串）。
     *
     * <p>为什么只在换歌时取一次：SMTC 的 Thumbnail 是一张 {@code RandomAccessStreamReference}，
     * 每次读都要走一遍 WinRT 异步流，而 poll 是每 500ms 一次 —— 每拍都读等于让一个后台
     * 助手常驻着做图片 IO。键用「曲名|歌手」，与 Java 侧的 trackKey 同一族身份。</p>
     *
     * <p>读失败（流被播放器收了、激活不了 DataReader、磁盘写不进去）时<b>不记键</b>,
     * 下一拍会再试一次；确认「这台播放器不给封面」才把空串缓存下来。</p>
     */
    private static string Artwork(GlobalSystemMediaTransportControlsSessionMediaProperties props,
                                  string title, string artist)
    {
        string key = NullToEmpty(title) + "|" + NullToEmpty(artist);
        if (lastArtKey != null && lastArtKey == key) return lastArtFile;
        string file = "";
        try
        {
            byte[] bytes = null;
            if (props != null) bytes = ReadAll(props.Thumbnail, MaxArtBytes);
            if (bytes != null && bytes.Length > 0)
            {
                // 后缀按真实内容写：SMTC 的 Thumbnail 十有八九是 JPEG，而 Java 侧是按
                // 「art-<16位hex>.<png|jpg|gif|bmp>」这个白名单认文件的。
                string name = "art-" + HexHash(key) + Extension(bytes);
                string directory = ArtDirectory();
                File.WriteAllBytes(Path.Combine(directory, name), bytes);
                Prune(directory, name);
                file = name;
            }
        }
        catch (Exception e)
        {
            // 读失败就这一首不再试了：poll 每半秒一拍，留着键不记就等于对着一台卡住的
            // 播放器反复要图。封面纯属装饰，缺它有曲库那张顶着。
            EmitError("art", e);
            file = "";
        }
        lastArtKey = key;
        lastArtFile = file;
        return file;
    }

    /** 上一次取过封面的那首（曲名|歌手）与当时落盘的文件名；空串表示那首确实没有封面。 */
    private static string lastArtKey;
    private static string lastArtFile = "";

    /** 封面文件放哪儿：助手 exe 自己的目录（Java 侧按同一目录去读）。 */
    private static string ArtDirectory()
    {
        try
        {
            string location = typeof(SmtcWatcher).Assembly.Location;
            if (!string.IsNullOrEmpty(location))
            {
                string directory = Path.GetDirectoryName(location);
                if (!string.IsNullOrEmpty(directory)) return directory;
            }
        }
        catch (Exception)
        {
            // 拿不到程序集位置时退回当前工作目录，下面还有 Directory.GetCurrentDirectory()。
        }
        return Directory.GetCurrentDirectory();
    }

    private static byte[] ReadAll(IRandomAccessStreamReference reference, long limit)
    {
        if (reference == null) return null;
        // OpenReadAsync 的返回类型是 IAsyncOperation<IRandomAccessStreamWithContentType>
        // （不是 WithProgress 那种），走普通 Await 就行。
        IRandomAccessStream stream = Await(reference.OpenReadAsync(), ArtSpins);
        if (stream == null) return null;
        try
        {
            ulong size = stream.Size;
            if (size == 0UL || (long)size > limit) return null;
            byte[] buffer = new byte[(int)size];
            DataReader reader = new DataReader(stream.GetInputStreamAt(0UL));
            WaitDone(reader.LoadAsync((uint)size), ArtSpins);
            reader.ReadBytes(buffer);
            return buffer;
        }
        finally
        {
            IDisposable disposable = stream as IDisposable;
            if (disposable != null) disposable.Dispose();
        }
    }

    /** 只留最近四张封面：快速切歌与「两台播放器来回切」都是常态，两张不够。 */
    private static void Prune(string directory, string keep)
    {
        try
        {
            string[] files = Directory.GetFiles(directory, "art-*");
            if (files.Length <= 4) return;
            // 按写入时间排，最新的四张留下。文件名里是哈希，按名字排等于随机排。
            Array.Sort(files, delegate(string a, string b)
            {
                return File.GetLastWriteTimeUtc(a).CompareTo(File.GetLastWriteTimeUtc(b));
            });
            for (int i = 0; i < files.Length - 4; i++)
            {
                if (Path.GetFileName(files[i]) == keep) continue;
                try { File.Delete(files[i]); }
                catch (Exception) { }
            }
        }
        catch (Exception)
        {
            // 清不了旧文件不影响这一首的封面，最多占点磁盘。
        }
    }

    /** 按文件头认图片类型，后缀写对 Java 那边才认得。 */
    private static string Extension(byte[] bytes)
    {
        if (bytes.Length > 3 && bytes[0] == 0x89 && bytes[1] == 0x50) return ".png";
        if (bytes.Length > 2 && bytes[0] == 0xFF && bytes[1] == 0xD8) return ".jpg";
        if (bytes.Length > 2 && bytes[0] == 0x47 && bytes[1] == 0x49) return ".gif";
        if (bytes.Length > 1 && bytes[0] == 0x42 && bytes[1] == 0x4D) return ".bmp";
        return ".png";
    }

    /** FNV-1a 折成 16 位十六进制：文件名要稳、要短、要只含安全字符。 */
    private static string HexHash(string value)
    {
        unchecked
        {
            long hash = unchecked((long)0xcbf29ce484222325UL);
            for (int i = 0; i < value.Length; i++)
            {
                hash ^= (long)value[i];
                hash *= 0x100000001b3L;
            }
            return hash.ToString("x16", CultureInfo.InvariantCulture);
        }
    }

    private static void EmitError(string where, Exception e)
    {
        Console.Error.WriteLine("[smtc] " + where + ": " + e.Message);
    }

    private static void Command(string key, string argument)
    {
        string error = "";
        bool accepted = false;
        try
        {
            // 玩家可能在第一拍 poll 之前就点了按钮；没有 manager 就先建。
            try { Manager(); }
            catch (Exception) { }
            GlobalSystemMediaTransportControlsSession session;
            lock (SessionLock)
            {
                if (stateDirty) ReselectLocked();
                session = selected;
            }
            if (session == null)
            {
                error = "no_session";
            }
            else if (key == "play")
            {
                accepted = Await(session.TryPlayAsync());
            }
            else if (key == "pause")
            {
                accepted = Await(session.TryPauseAsync());
            }
            else if (key == "stop")
            {
                accepted = Await(session.TryStopAsync());
            }
            else if (key == "toggle")
            {
                // 老版本 Windows 上没有 TryTogglePlayPauseAsync。抛异常时按当前状态
                // 退化成 play/pause，而不是让整条命令失败。
                try
                {
                    accepted = Await(session.TryTogglePlayPauseAsync());
                }
                catch (Exception)
                {
                    bool playing = StatusOf(session)
                        == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;
                    accepted = playing ? Await(session.TryPauseAsync()) : Await(session.TryPlayAsync());
                }
            }
            else if (key == "next")
            {
                accepted = Await(session.TrySkipNextAsync());
            }
            else if (key == "prev")
            {
                accepted = Await(session.TrySkipPreviousAsync());
            }
            else if (key == "seek")
            {
                long ms;
                if (!long.TryParse(argument, NumberStyles.Integer, CultureInfo.InvariantCulture, out ms) || ms < 0L)
                {
                    error = "bad_position";
                }
                else
                {
                    // 单位是 100 纳秒 ticks（1ms = 10000），不是毫秒：双进程探针实锤——
                    // 传毫秒会被缩小一万倍，拖 3 分钟实际只跳 0.018 秒，表现就是
                    // 「拖了没反应」。v1 从第一天起就带着这个 bug，拖动能力位是好的、
                    // 命令也被接受，只是量小到看不见。
                    accepted = Await(session.TryChangePlaybackPositionAsync(ms * 10000L));
                }
            }
            else
            {
                error = "unknown_key";
            }
            if (!accepted && error.Length == 0) error = "rejected_by_player";
        }
        catch (Exception e)
        {
            error = e.GetType().Name;
            lock (SessionLock)
            {
                manager = null;
                selected = null;
                cachedProps = null;
                stateDirty = true;
            }
        }
        Emit("{\"event\":\"cmd\",\"key\":\"" + Esc(key) + "\",\"accepted\":" + Bool(accepted)
            + ",\"error\":\"" + Esc(error) + "\"}");
    }

    // ==========================================================================
    // 外部播放器的音量。
    //
    // SMTC 那一层没有任何音量成员（这台机器上把会话接口整个列出来过，只有
    // TryPlay/TryPause/TryStop/TrySkipNext/TrySkipPrevious/TryTogglePlayPause/
    // TryChangePlaybackPosition/TryChangePlaybackRate/... 那一套）。能落到
    // 「某个程序的音量」的只有 Windows 音量合成器的 Core Audio 会话。
    //
    // 三条实测结论，别改回去：
    //   * 下面的 IID 与方法顺序取自 MIDL IDL（抄的是 pycaw 里 comtypes 从 IDL 生成的那份）。
    //     凭记忆写的那一版 IMMDevice / IAudioSessionManager2 / IAudioSessionControl 全错，
    //     症状是清一色 E_NOINTERFACE —— 指针能 QI 到 IUnknown，看着像 CLR 或系统的问题，
    //     其实只是 IID 不对。
    //   * IMMDevice::Activate 的参数顺序是 (iid, dwClsCtx, props, ppv)，iid 在前。
    //   * 会话是跨进程问 audiosrv 拿的，所以不是每拍都枚举：同一个播放器 2 秒最多一次。
    //
    // 配不上程序时一律报 volok:false，Java 侧据此什么都不做。绝不允许退回到主音量 ——
    // 那等于让模组的滑块去拧用户整个系统的声音。
    // ==========================================================================

    private const int VolumeRefreshMs = 2000;
    private const int ClsCtxAll = 0x17;

    private static long volumeAtMs = -1000000L;
    private static bool volumeOk;
    private static float volumeLevel = -1f;
    private static int volumePid;

    /** 状态行里的音量片段。没配对上那支程序时只回 volok:false，连数字都不给。 */
    private static string VolumeFragment(string aumid)
    {
        long now = NowMs();
        if (now - volumeAtMs >= VolumeRefreshMs)
        {
            volumeAtMs = now;
            RefreshVolume(aumid);
        }
        if (!volumeOk) return ",\"volok\":false";
        return ",\"volok\":true"
                + ",\"vol\":" + volumeLevel.ToString("0.0000", CultureInfo.InvariantCulture)
                + ",\"pid\":" + volumePid.ToString(CultureInfo.InvariantCulture);
    }

    private static void RefreshVolume(string aumid)
    {
        volumeOk = false;
        volumeLevel = -1f;
        volumePid = 0;
        string[] tokens = Tokens(aumid);
        if (tokens.Length == 0) return;
        try
        {
            for (int role = 1; role >= 0; role--)   // eMultimedia 优先，再退 eConsole
            {
                int pid;
                List<ISimpleAudioVolume> volumes = SessionsFor(tokens, role, out pid);
                if (volumes.Count == 0) continue;
                float level;
                if (volumes[0].GetMasterVolume(out level) != 0) continue;
                volumeOk = true;
                volumeLevel = level;
                volumePid = pid;
                return;
            }
        }
        catch (Exception e)
        {
            EmitError("vol", e);
        }
        volumeOk = false;
    }

    /** 把音量写给那支程序的全部会话（同一进程可能有多条，浏览器就是这样）。 */
    private static void Volume(string argument)
    {
        float requested;
        if (!float.TryParse(argument, NumberStyles.Float, CultureInfo.InvariantCulture, out requested))
        {
            Emit("{\"event\":\"vol\",\"ok\":false,\"error\":\"bad_level\",\"vol\":-1,\"pid\":0}");
            return;
        }
        if (requested < 0f) requested = 0f;
        if (requested > 1f) requested = 1f;

        string aumid = "";
        try
        {
            try { Manager(); }
            catch (Exception) { }
            lock (SessionLock)
            {
                if (stateDirty) ReselectLocked();
                if (selected != null) aumid = NullToEmpty(selected.SourceAppUserModelId);
            }
        }
        catch (Exception e)
        {
            EmitError("vol-session", e);
        }

        bool ok = false;
        float applied = -1f;
        int pid = 0;
        string[] tokens = Tokens(aumid);
        try
        {
            for (int role = 1; role >= 0 && !ok; role--)
            {
                int candidate;
                List<ISimpleAudioVolume> volumes = SessionsFor(tokens, role, out candidate);
                if (volumes.Count == 0) continue;
                for (int i = 0; i < volumes.Count; i++)
                {
                    if (volumes[i].SetMasterVolume(requested, IntPtr.Zero) != 0) continue;
                    ok = true;
                }
                if (!ok) continue;
                pid = candidate;
                // 回报的是写完再从会话读回来的值，不是我们请求的那个：合成器有自己的量化。
                float readBack;
                applied = volumes[0].GetMasterVolume(out readBack) == 0 ? readBack : requested;
                volumeOk = true;
                volumeLevel = applied;
                volumePid = pid;
                volumeAtMs = NowMs();
            }
        }
        catch (Exception e)
        {
            EmitError("setvol", e);
        }
        Emit("{\"event\":\"vol\",\"ok\":" + Bool(ok) + ",\"vol\":"
                + applied.ToString("0.0000", CultureInfo.InvariantCulture)
                + ",\"pid\":" + pid.ToString(CultureInfo.InvariantCulture) + "}");
    }

    /**
     * 该端点上属于「那支程序」的会话音量接口。配不上返回空表 —— 调用方因此没有
     * 「顺手拿第一条用一下」这条路可走。
     */
    private static List<ISimpleAudioVolume> SessionsFor(string[] tokens, int role, out int pid)
    {
        pid = 0;
        List<ISimpleAudioVolume> volumes = new List<ISimpleAudioVolume>();
        if (tokens.Length == 0) return volumes;
        IMMDevice device = DefaultDevice(role);
        if (device == null) return volumes;
        IAudioSessionEnumerator list = SessionList(device);
        if (list == null) return volumes;
        int count;
        if (list.GetCount(out count) != 0) return volumes;

        List<SessionEntry> entries = new List<SessionEntry>();
        SessionEntry best = null;
        for (int i = 0; i < count; i++)
        {
            IAudioSessionControl control;
            if (list.GetSession(i, out control) != 0 || control == null) continue;
            IAudioSessionControl2 control2 = control as IAudioSessionControl2;
            if (control2 == null) continue;
            int sessionPid;
            // pid=0 是系统声音会话（"@%SystemRoot%\System32\AudioSrv.Dll,-202"），永远跳过。
            if (control2.GetProcessId(out sessionPid) != 0 || sessionPid <= 0) continue;
            string identifier;
            control2.GetSessionIdentifier(out identifier);
            int state;
            control.GetState(out state);

            SessionEntry entry = new SessionEntry();
            entry.Pid = sessionPid;
            entry.State = state;
            entry.Identity = IdentityOf(sessionPid, identifier);
            entry.Score = MatchScore(tokens, entry.Identity);
            entry.Volume = control as ISimpleAudioVolume;
            entries.Add(entry);
            if (IsBetter(entry, best)) best = entry;
        }
        if (best == null || best.Score <= 0 || best.Volume == null) return volumes;
        pid = best.Pid;
        for (int i = 0; i < entries.Count; i++)
        {
            SessionEntry entry = entries[i];
            if (entry.Pid != best.Pid || entry.Volume == null) continue;
            volumes.Add(entry.Volume);
        }
        return volumes;
    }

    private static bool IsBetter(SessionEntry candidate, SessionEntry best)
    {
        if (best == null) return candidate.Score > 0;
        if (candidate.Score != best.Score) return candidate.Score > best.Score;
        // 分数相同（同一支程序开了好几条）时优先正在出声的那条：Active=2 > Suspended=1 > Expired=0。
        return candidate.State > best.State;
    }

    private static IMMDevice DefaultDevice(int role)
    {
        object created = Activator.CreateInstance(typeof(MMDeviceEnumeratorComObject));
        IMMDeviceEnumerator enumerator = created as IMMDeviceEnumerator;
        if (enumerator == null) return null;
        IMMDevice device;
        if (enumerator.GetDefaultAudioEndpoint(0, role, out device) != 0) return null;
        return device;
    }

    private static IAudioSessionEnumerator SessionList(IMMDevice device)
    {
        Guid iid = new Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F");
        IntPtr raw;
        if (device.Activate(ref iid, ClsCtxAll, IntPtr.Zero, out raw) != 0) return null;
        if (raw == IntPtr.Zero) return null;
        try
        {
            IAudioSessionManager2 manager2 = Marshal.GetObjectForIUnknown(raw) as IAudioSessionManager2;
            if (manager2 == null) return null;
            IAudioSessionEnumerator list;
            if (manager2.GetSessionEnumerator(out list) != 0) return null;
            return list;
        }
        finally
        {
            Marshal.Release(raw);   // RCW 自己已经加过一次引用
        }
    }

    /** 「进程名|exe 路径」的小写形态，AUMID 的片段就拿它来对。 */
    private static string IdentityOf(int pid, string identifier)
    {
        string process = "";
        try { process = Process.GetProcessById(pid).ProcessName.ToLowerInvariant(); }
        catch (Exception) { process = ""; }
        string path = ExePath(identifier);
        if (process.Length == 0) return path;
        if (path.Length == 0) return process;
        return process + "|" + path;
    }

    /**
     * 从会话标识串里取出可执行文件路径。原形是
     * {@code {0.0.0.00000000}.{guid}|\Device\HarddiskVolume3\Program Files\QPlayer\qplayer.exe%b{guid}}。
     *
     * <p>\Device\ 与 HarddiskVolumeN 这两段必须切掉：留着的话 "\Device\" 里的 "devi"
     * 会跟 AUMID 的片段撞上（dev.t1m3.qplayer 就是这种形状），于是任何会话都能被
     * 任何播放器配上。</p>
     */
    private static string ExePath(string identifier)
    {
        if (identifier == null) return "";
        string tail = identifier;
        int marker = tail.IndexOf("\\Device\\", StringComparison.OrdinalIgnoreCase);
        if (marker >= 0) tail = tail.Substring(marker + 8);
        int volume = tail.IndexOf('\\');
        if (volume >= 0) tail = tail.Substring(volume + 1);
        int percent = tail.IndexOf('%');
        if (percent >= 0) tail = tail.Substring(0, percent);
        return tail.ToLowerInvariant();
    }

    /** AUMID 拆片段：win32 的 "dev.t1m3.qplayer"、打包的 "Microsoft.ZuneMusic_8wekyb3d8bbwe!App" 都走这一条。 */
    private static string[] Tokens(string aumid)
    {
        if (aumid == null || aumid.Length == 0) return new string[0];
        List<string> tokens = new List<string>();
        StringBuilder current = new StringBuilder(24);
        string lower = aumid.ToLowerInvariant();
        for (int i = 0; i < lower.Length; i++)
        {
            char c = lower[i];
            bool letterOrDigit = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (letterOrDigit) { current.Append(c); continue; }
            Push(tokens, current.ToString());
            current.Remove(0, current.Length);
        }
        Push(tokens, current.ToString());
        return tokens.ToArray();
    }

    /**
     * 过滤掉那种「什么都能配上」的片段：短片段、以及 AUMID 里到处都有的通用词。
     * 留下来的片段少，宁可配不上（就是没有音量控制）也不要配错程序。
     */
    private static void Push(List<string> tokens, string value)
    {
        if (value.Length < 4) return;
        if (value == "app" || value == "exe" || value == "ms" || value == "www"
                || value == "windows" || value == "system" || value == "users"
                || value == "user" || value == "home" || value == "desktop"
                || value == "program" || value == "files" || value == "microsoft") return;
        tokens.Add(value);
    }

    /** 3 = 进程名正好等于片段，2 = 片段出现在进程名或 exe 路径里，0 = 不是这支程序。 */
    private static int MatchScore(string[] tokens, string identity)
    {
        if (identity == null || identity.Length == 0) return 0;
        int pipe = identity.IndexOf('|');
        string process = pipe < 0 ? identity : identity.Substring(0, pipe);
        int best = 0;
        for (int i = 0; i < tokens.Length; i++)
        {
            string token = tokens[i];
            if (process == token) { best = 3; continue; }
            if (identity.IndexOf(token, StringComparison.Ordinal) >= 0 && best < 2) best = 2;
        }
        return best;
    }

    private static long NowMs()
    {
        return DateTime.UtcNow.Ticks / TimeSpan.TicksPerMillisecond;
    }

    private sealed class SessionEntry
    {
        public int Pid;
        public int State;
        public int Score;
        public string Identity;
        public ISimpleAudioVolume Volume;
    }

    // ==========================================================================
    // 发布模式：把本模组自己的播放写成一条系统媒体会话。
    //
    // 与镜像（读系统）相反的这一向：内部播放时媒体键可控、音量浮窗与锁屏可见。
    // 桌面进程接入 SMTC 只有一条正路：激活工厂上 QI 到
    // ISystemMediaTransportControlsInterop（IID ddb0472d-c911-4a1f-86d9-dc3d71a95f5a，
    // 抄自 mingw-w64 镜像的 SDK 头，不是凭记忆），对一条本进程的隐形窗口调
    // GetForWindow。窗口与全部 WinRT 调用收在一条专用线程上 —— WinRT 的 UI 资产
    // 讲究线程归属，跨线程乱调是坑；stdin 主线程只更新状态仓 + PostMessage 叫醒它。
    // ==========================================================================

    private static Publisher publisher;

    /** 发布端在 stdin 主线程上的统一入口：更新状态仓，然后叫醒发布线程。 */
    private static void Publish(string line)
    {
        if (publisher == null) publisher = new Publisher();
        publisher.Accept(line);
    }

    /** 发布端的一次状态仓更新 + 叫醒。解析失败只回 puback，不打断镜像那一路。 */
    private sealed class Publisher
    {
        private const uint WakeMessage = 0x0400 + 0x6D75;   // WM_APP + 'mu'
        // RO_INIT_MULTITHREADED(2) 实测在这台 Win11 上回 E_INVALIDARG；STA 走得通，
        // 消息泵本来就是 STA 的主场。
        private const int RoInitApartmentThreaded = 1;

        private readonly object gate = new object();
        private Thread thread;
        private bool wanted;

        private string title = "";
        private string artist = "";
        private string album = "";
        private string albumArtist = "";
        private string state = "Stopped";
        private string artPath = "";
        private long posMs;
        private long durMs;

        // 发布线程持有的资产；只有发布线程自己读写。
        private IntPtr hwnd;
        private SystemMediaTransportControls smtc;
        private string appliedArtPath = null;
        private bool appliedOnce;

        public void Accept(string line)
        {
            string error = Apply(line);
            if (error != null)
            {
                Emit("{\"event\":\"pub\",\"ok\":false,\"error\":\"" + Esc(error) + "\"}");
                return;
            }
            EnsureThread();
            Wake();
        }

        /** 解析一条 pub 命令并更新状态仓。返回 null 表示成功。 */
        private string Apply(string line)
        {
            if (line == "pub on")
            {
                lock (gate) wanted = true;
                Emit("{\"event\":\"pub\",\"ok\":true,\"op\":\"on\"}");
                return null;
            }
            if (line == "pub off")
            {
                lock (gate)
                {
                    wanted = false;
                    state = "Stopped";
                }
                return null;
            }
            if (line.StartsWith("pub meta ", StringComparison.Ordinal))
            {
                string json = line.Substring(9);
                lock (gate)
                {
                    title = JsonField(json, "title");
                    artist = JsonField(json, "artist");
                    album = JsonField(json, "album");
                    albumArtist = JsonField(json, "albumArtist");
                }
                return null;
            }
            if (line.StartsWith("pub state ", StringComparison.Ordinal))
            {
                lock (gate) state = line.Substring(10).Trim();
                return null;
            }
            if (line.StartsWith("pub tl ", StringComparison.Ordinal))
            {
                string[] parts = line.Substring(7).Trim().Split(' ');
                long pos;
                long dur;
                if (parts.Length < 2
                        || !long.TryParse(parts[0], NumberStyles.Integer, CultureInfo.InvariantCulture, out pos)
                        || !long.TryParse(parts[1], NumberStyles.Integer, CultureInfo.InvariantCulture, out dur))
                {
                    return "bad_timeline";
                }
                lock (gate)
                {
                    posMs = Math.Max(0L, pos);
                    durMs = Math.Max(0L, dur);
                }
                return null;
            }
            if (line.StartsWith("pub art ", StringComparison.Ordinal))
            {
                string value = Unesc(line.Substring(8).Trim());
                lock (gate) artPath = value;
                return null;
            }
            return "unknown_pub_command";
        }

        private void EnsureThread()
        {
            lock (gate)
            {
                if (thread != null && thread.IsAlive) return;
                thread = new Thread(Pump);
                thread.IsBackground = true;
                thread.SetApartmentState(ApartmentState.MTA);
                thread.Start();
            }
        }

        private void Wake()
        {
            IntPtr window;
            lock (gate) window = hwnd;
            if (window != IntPtr.Zero) PostMessageW(window, WakeMessage, IntPtr.Zero, IntPtr.Zero);
        }

        /** 发布线程主体：初始化 WinRT → 建隐形窗口 → 接 SMTC → 消息泵。 */
        private void Pump()
        {
            try
            {
                int hr = RoInitialize(RoInitApartmentThreaded);
                if (hr != 0 && hr != /* S_FALSE */ 1 && hr != /* RPC_E_CHANGED_MODE */ unchecked((int)0x80010106))
                {
                    Emit("{\"event\":\"pub\",\"ok\":false,\"error\":\"roinit_0x"
                        + hr.ToString("x8", CultureInfo.InvariantCulture) + "\"}");
                    return;
                }
                hwnd = CreateMessageWindow();
                if (hwnd == IntPtr.Zero)
                {
                    Emit("{\"event\":\"pub\",\"ok\":false,\"error\":\"no_window\"}");
                    return;
                }
                // 窗口就位后立刻应用一次当前状态仓：第一批 pub 命令里的 Wake 在窗口
                // 建好之前发不出去（PostMessage 的 hwnd 还是零），不主动补这一下，
                // 之后没有新消息进来会话就永远不会建立。
                ApplyState();
                // 探针：发布进程自己用镜像视角看一眼系统里有没有我们的会话。
                try
                {
                    EmitError("pub-selfcheck", new Exception("sessions="
                        + managerSessionsForProbe()));
                }
                catch (Exception) { }
                MSG msg = new MSG();
                while (true)
                {
                    // getMessage 返回 0 = WM_QUIT，-1 = 错误；两种都退。发布线程没有退出消息，
                    // 进程结束时随之消亡（后台线程）。
                    int result = GetMessageW(out msg, IntPtr.Zero, 0u, 0u);
                    if (result <= 0) break;
                    TranslateMessage(ref msg);
                    DispatchMessageW(ref msg);
                    ApplyState();
                }
            }
            catch (Exception e)
            {
                Emit("{\"event\":\"pub\",\"ok\":false,\"error\":\"pump_" + Esc(e.GetType().Name) + "\"}");
            }
        }

        /** 探针用：从镜像侧数一数系统里现在有几条会话、有没有我们的 AUMID。 */
        private static string managerSessionsForProbe()
        {
            GlobalSystemMediaTransportControlsSessionManager mgr =
                Await(GlobalSystemMediaTransportControlsSessionManager.RequestAsync(), 500);
            IReadOnlyList<GlobalSystemMediaTransportControlsSession> list = mgr.GetSessions();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.Count; i++)
            {
                if (sb.Length > 0) sb.Append(',');
                sb.Append(list[i].SourceAppUserModelId);
            }
            return list.Count + "[" + sb.ToString() + "]";
        }

        /** 消息醒来或刚启动时把状态仓的当前值整套应用一遍。 */
        private void ApplyState()
        {
            if (smtc == null)
            {
                lock (gate)
                {
                    if (wanted && !CreateControls()) return;
                }
                if (smtc == null) return;
            }
            string wantedLocal;
            string stateLocal;
            string titleLocal;
            string artistLocal;
            string albumLocal;
            string albumArtistLocal;
            string artLocal;
            long posLocal;
            long durLocal;
            lock (gate)
            {
                wantedLocal = wanted ? "1" : "0";
                stateLocal = state;
                titleLocal = title;
                artistLocal = artist;
                albumLocal = album;
                albumArtistLocal = albumArtist;
                artLocal = artPath;
                posLocal = posMs;
                durLocal = durMs;
            }
            try
            {
                // IsEnabled=false 是「从系统浮窗里消失」的正路：会话还在，只是不被显示。
                smtc.IsEnabled = wantedLocal == "1";
                if (wantedLocal != "1") return;

                SystemMediaTransportControlsDisplayUpdater updater = smtc.DisplayUpdater;
                updater.Type = MediaPlaybackType.Music;
                MusicDisplayProperties music = updater.MusicProperties;
                music.Title = titleLocal;
                music.Artist = artistLocal;
                music.AlbumTitle = albumLocal;
                music.AlbumArtist = albumArtistLocal;
                if (artLocal != appliedArtPath)
                {
                    updater.Thumbnail = ArtReference(artLocal);
                    appliedArtPath = updater.Thumbnail == null ? "" : artLocal;
                }

                SystemMediaTransportControlsTimelineProperties timeline =
                    new SystemMediaTransportControlsTimelineProperties();
                timeline.StartTime = TimeSpan.Zero;
                timeline.MinSeekTime = TimeSpan.Zero;
                timeline.Position = TimeSpan.FromMilliseconds(posLocal);
                timeline.EndTime = TimeSpan.FromMilliseconds(durLocal);
                timeline.MaxSeekTime = TimeSpan.FromMilliseconds(durLocal);
                smtc.UpdateTimelineProperties(timeline);

                smtc.PlaybackStatus = StatusValue(stateLocal);
                updater.Update();
                if (!appliedOnce)
                {
                    appliedOnce = true;
                    EmitError("pub-apply-ok", new Exception("enabled=" + smtc.IsEnabled));
                }
            }
            catch (Exception e)
            {
                // 元数据里有空串/非法时长都会在投影层抛；下一拍 wake 会带着新值再来。
                Emit("{\"event\":\"pub\",\"ok\":false,\"error\":\"apply_" + Esc(e.GetType().Name) + "\"}");
            }
        }

        private static MediaPlaybackStatus StatusValue(string value)
        {
            if (value == "Playing") return MediaPlaybackStatus.Playing;
            if (value == "Paused") return MediaPlaybackStatus.Paused;
            return MediaPlaybackStatus.Stopped;
        }

        /** 封面 → RandomAccessStreamReference。文件读不到就给 null（没有封面不算错）。 */
        private static RandomAccessStreamReference ArtReference(string path)
        {
            if (string.IsNullOrEmpty(path)) return null;
            try
            {
                StorageFile file = Await(StorageFile.GetFileFromPathAsync(path), ArtSpins);
                if (file == null) return null;
                return RandomAccessStreamReference.CreateFromFile(file);
            }
            catch (Exception)
            {
                return null;
            }
        }

        private bool CreateControls()
        {
            try
            {
                Guid interopIid = new Guid("DDB0472D-C911-4A1F-86D9-DC3D71A95F5A");
                IntPtr factory;
                int hr = RoGetActivationFactory("Windows.Media.SystemMediaTransportControls",
                    ref interopIid, out factory);
                if (hr != 0 || factory == IntPtr.Zero)
                {
                    EmitError("pub-activation", new Exception("0x" + hr.ToString("x8")));
                    return false;
                }
                try
                {
                    // Firefox 这里拿的是默认接口 ISystemMediaTransportControls 的 IID；
                    // 那个接口在 C# 5 的投影里不可访问（typeof 会被编译器拒绝），而实测
                    // IUnknown 完全走得通：拿到指针对象后投影类转换、全部属性与 Update()
                    // 都成功。就按 IUnknown 走，别学 Firefox 这一步。
                    Guid controlIid = new Guid("00000000-0000-0000-C000-000000000046");
                    IntPtr raw;
                    GetForWindow get = Marshal.GetObjectForIUnknown(factory) as GetForWindow;
                    if (get == null)
                    {
                        EmitError("pub-qifactory", new Exception("interop not implemented"));
                        return false;
                    }
                    hr = get.GetForWindow(hwnd, ref controlIid, out raw);
                    if (hr != 0 || raw == IntPtr.Zero)
                    {
                        EmitError("pub-getforwindow", new Exception("0x" + hr.ToString("x8")));
                        return false;
                    }
                    try
                    {
                        smtc = Marshal.GetObjectForIUnknown(raw) as SystemMediaTransportControls;
                    }
                    finally
                    {
                        Marshal.Release(raw);
                    }
                    if (smtc == null) return false;

                    smtc.IsEnabled = true;
                    smtc.IsPlayEnabled = true;
                    smtc.IsPauseEnabled = true;
                    smtc.IsStopEnabled = true;
                    smtc.IsNextEnabled = true;
                    smtc.IsPreviousEnabled = true;
                    // 发布端与进程同寿，不需要退订。
                    smtc.ButtonPressed += OnButtonPressed;
                    smtc.PlaybackPositionChangeRequested += OnPositionRequested;
                    return true;
                }
                finally
                {
                    Marshal.Release(factory);
                }
            }
            catch (Exception e)
            {
                EmitError("pub-create", e);
                return false;
            }
        }

        /** 媒体键按下。只把命令转成一行 JSON 交回 Java，不在这里做任何播放动作。 */
        private void OnButtonPressed(SystemMediaTransportControls sender,
            SystemMediaTransportControlsButtonPressedEventArgs args)
        {
            try
            {
                SystemMediaTransportControlsButton button = args.Button;
                string key;
                if (button == SystemMediaTransportControlsButton.Play) key = "play";
                else if (button == SystemMediaTransportControlsButton.Pause) key = "pause";
                else if (button == SystemMediaTransportControlsButton.Stop) key = "stop";
                else if (button == SystemMediaTransportControlsButton.Next) key = "next";
                else if (button == SystemMediaTransportControlsButton.Previous) key = "prev";
                else key = "";
                if (key.Length == 0) return;
                Emit("{\"event\":\"pubcmd\",\"key\":\"" + key + "\",\"arg\":\"\"}");
            }
            catch (Exception e)
            {
                EmitError("pub-button", e);
            }
        }

        /** 系统浮窗上拖进度。时间戳以毫秒回传，Java 侧自己判断能不能 seek。 */
        private void OnPositionRequested(SystemMediaTransportControls sender,
            PlaybackPositionChangeRequestedEventArgs args)
        {
            try
            {
                long ms = (long)args.RequestedPlaybackPosition.TotalMilliseconds;
                if (ms < 0L) ms = 0L;
                Emit("{\"event\":\"pubcmd\",\"key\":\"seek\",\"arg\":\""
                    + ms.ToString(CultureInfo.InvariantCulture) + "\"}");
            }
            catch (Exception e)
            {
                EmitError("pub-seek", e);
            }
        }

        /** 消息窗口只为了给 SMTC 一个 HWND，收到的所有消息都走默认处理。 */
        private static IntPtr WndProc(IntPtr window, uint message, IntPtr wParam, IntPtr lParam)
        {
            return DefWindowProcW(window, message, wParam, lParam);
        }

        private static IntPtr CreateMessageWindow()
        {
            WndProcDelegate proc = WndProc;
            string className = "MuoniumSmtcPub" + Process.GetCurrentProcess().Id
                .ToString(CultureInfo.InvariantCulture);
            WNDCLASS wc = new WNDCLASS();
            wc.lpfnWndProc = proc;
            wc.lpszClassName = className;
            wc.hInstance = GetModuleHandleW(null);
            if (RegisterClassW(ref wc) == 0u) return IntPtr.Zero;
            // GetForWindow 要求「顶层窗口」，message-only（HWND_MESSAGE）会被拒。
            // 实测：从不显示过的窗口，它这条会话不出现在<b>其他进程</b>的会话列表里
            // （发布进程自己能看到，系统的浮窗看不到）。于是用经典的「隐形但已显示」
            // 手法：WS_POPUP + WS_EX_TOOLWINDOW（不进任务栏、不被 Alt-Tab 看见），
            // 摆到屏幕外 -32000，SW_SHOWNOACTIVATE 显示 —— 对系统它是显示过的窗口，
            // 对用户它不存在。
            IntPtr window = CreateWindowExW(0x08000000u /* WS_EX_TOOLWINDOW */, className,
                "Muonium SMTC", 0x90000000u /* WS_POPUP | WS_VISIBLE */,
                -32000, -32000, 1, 1, IntPtr.Zero, IntPtr.Zero, wc.hInstance, IntPtr.Zero);
            if (window != IntPtr.Zero) ShowWindow(window, 4 /* SW_SHOWNOACTIVATE */);
            return window;
        }

        // ---- 扁平 JSON 的最小解析。发布协议里只有平铺的字符串字段，够用。 ----

        private static string JsonField(string json, string key)
        {
            string marker = "\"" + key + "\"";
            int at = json.IndexOf(marker, StringComparison.Ordinal);
            if (at < 0) return "";
            int colon = json.IndexOf(':', at + marker.Length);
            if (colon < 0) return "";
            int quote = colon + 1;
            while (quote < json.Length && char.IsWhiteSpace(json[quote])) quote++;
            if (quote >= json.Length || json[quote] != '"') return "";
            return Unesc(json.Substring(quote));
        }

        /** 从一段「以开引号开始」的文本里解出带转义的字符串值。 */
        private static string Unesc(string text)
        {
            StringBuilder sb = new StringBuilder(text.Length);
            for (int i = 1; i < text.Length; i++)
            {
                char c = text[i];
                if (c == '\\')
                {
                    if (i + 1 >= text.Length) break;
                    char next = text[i + 1];
                    if (next == '"') { sb.Append('"'); i++; }
                    else if (next == '\\') { sb.Append('\\'); i++; }
                    else if (next == 'n') { sb.Append('\n'); i++; }
                    else if (next == 'r') { sb.Append('\r'); i++; }
                    else if (next == 't') { sb.Append('\t'); i++; }
                    else if (next == 'u' && i + 5 < text.Length)
                    {
                        int code;
                        if (int.TryParse(text.Substring(i + 2, 4), NumberStyles.HexNumber,
                                CultureInfo.InvariantCulture, out code))
                        {
                            sb.Append((char)code);
                            i += 4;
                        }
                    }
                    continue;
                }
                if (c == '"') break;
                sb.Append(c);
            }
            return sb.ToString();
        }

        // ---- Win32。全部取自 mingw-w64 镜像的 SDK 头核对过的签名。 ----

        private delegate IntPtr WndProcDelegate(IntPtr window, uint message, IntPtr wParam, IntPtr lParam);

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct WNDCLASS
        {
            public uint style;
            public WndProcDelegate lpfnWndProc;
            public int cbClsExtra;
            public int cbWndExtra;
            public IntPtr hInstance;
            public IntPtr hIcon;
            public IntPtr hCursor;
            public IntPtr hbrBackground;
            [MarshalAs(UnmanagedType.LPWStr)] public string lpszMenuName;
            [MarshalAs(UnmanagedType.LPWStr)] public string lpszClassName;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct MSG
        {
            public IntPtr hwnd;
            public uint message;
            public IntPtr wParam;
            public IntPtr lParam;
            public uint time;
            public int ptX;
            public int ptY;
        }

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
        private static extern IntPtr GetModuleHandleW([MarshalAs(UnmanagedType.LPWStr)] string name);

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern ushort RegisterClassW(ref WNDCLASS cls);

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern IntPtr CreateWindowExW(uint style, [MarshalAs(UnmanagedType.LPWStr)] string className,
            [MarshalAs(UnmanagedType.LPWStr)] string windowName, uint windowStyle, int x, int y, int width,
            int height, IntPtr parent, IntPtr menu, IntPtr instance, IntPtr param);

        [DllImport("user32.dll")]
        private static extern IntPtr DefWindowProcW(IntPtr window, uint message, IntPtr wParam, IntPtr lParam);

        [DllImport("user32.dll")]
        private static extern int GetMessageW(out MSG message, IntPtr window, uint min, uint max);

        [DllImport("user32.dll")]
        private static extern bool TranslateMessage(ref MSG message);

        [DllImport("user32.dll")]
        private static extern IntPtr DispatchMessageW(ref MSG message);

        [DllImport("user32.dll")]
        private static extern bool PostMessageW(IntPtr window, uint message, IntPtr wParam, IntPtr lParam);

        // ShowWindow 没有 W 变体（user32 里就叫这个名字），写 ShowWindowW 会 EntryPointNotFound。
        [DllImport("user32.dll")]
        private static extern bool ShowWindow(IntPtr window, int command);

        // Win10 起 RoInitialize 由 combase.dll 导出（roapi.dll 只是转发，有些系统上找不到）。
        [DllImport("combase.dll")]
        private static extern int RoInitialize(int initType);

        /** ISystemMediaTransportControlsInterop::GetForWindow。挂在激活工厂上。 */
        [ComImport, Guid("DDB0472D-C911-4A1F-86D9-DC3D71A95F5A"),
         InterfaceType(ComInterfaceType.InterfaceIsIInspectable)]
        private interface GetForWindow
        {
            [PreserveSig] int GetForWindow(IntPtr appWindow, ref Guid riid, [Out] out IntPtr control);
        }

        [DllImport("combase.dll")]
        private static extern int RoGetActivationFactory([MarshalAs(UnmanagedType.HString)] string activationId,
            ref Guid iid, out IntPtr factory);
    }

    // ---- Core Audio 互操作。全部 IUnknown 虚表，PreserveSig 自己收 HRESULT。----

    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    private class MMDeviceEnumeratorComObject { }

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceEnumerator
    {
        [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
        [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
        [PreserveSig] int GetDevice([MarshalAs(UnmanagedType.LPWStr)] string id, out IMMDevice device);
        [PreserveSig] int RegisterEndpointNotificationCallback(IntPtr client);
        [PreserveSig] int UnregisterEndpointNotificationCallback(IntPtr client);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDevice
    {
        [PreserveSig] int Activate(ref Guid iid, int clsCtx, IntPtr properties, out IntPtr iface);
        [PreserveSig] int OpenPropertyStore(int stgmAccess, out IntPtr properties);
        [PreserveSig] int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
        [PreserveSig] int GetState(out int state);
    }

    // IAudioSessionManager2 继承 IAudioSessionManager，所以前两个槽是那两个老方法，
    // GetSessionEnumerator 排在第 3 槽（vtable 顺序 = IDL 声明顺序）。
    [ComImport, Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioSessionManager2
    {
        [PreserveSig] int GetAudioSessionControl(IntPtr audioSessionGuid, int streamFlags, out IntPtr control);
        [PreserveSig] int GetSimpleAudioVolume(IntPtr audioSessionGuid, int flags, out IntPtr volume);
        [PreserveSig] int GetSessionEnumerator(out IAudioSessionEnumerator sessionList);
        [PreserveSig] int RegisterSessionNotification(IntPtr notification);
        [PreserveSig] int UnregisterSessionNotification(IntPtr notification);
        [PreserveSig] int RegisterDuckNotification([MarshalAs(UnmanagedType.LPWStr)] string sessionId, IntPtr duck);
        [PreserveSig] int UnregisterDuckNotification(IntPtr duck);
    }

    [ComImport, Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioSessionEnumerator
    {
        [PreserveSig] int GetCount(out int count);
        [PreserveSig] int GetSession(int index, out IAudioSessionControl session);
    }

    [ComImport, Guid("F4B1A599-7266-4319-A8CA-E70ACB11E8CD"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioSessionControl
    {
        [PreserveSig] int GetState(out int state);
        [PreserveSig] int GetDisplayName([MarshalAs(UnmanagedType.LPWStr)] out string name);
        [PreserveSig] int SetDisplayName([MarshalAs(UnmanagedType.LPWStr)] string value, IntPtr eventContext);
        [PreserveSig] int GetIconPath([MarshalAs(UnmanagedType.LPWStr)] out string path);
        [PreserveSig] int SetIconPath([MarshalAs(UnmanagedType.LPWStr)] string value, IntPtr eventContext);
        [PreserveSig] int GetGroupingParam(out Guid grouping);
        [PreserveSig] int SetGroupingParam(IntPtr grouping, IntPtr eventContext);
        [PreserveSig] int RegisterAudioSessionNotification(IntPtr events);
        [PreserveSig] int UnregisterAudioSessionNotification(IntPtr events);
    }

    // 前 9 个槽与 IAudioSessionControl 完全一致（同一个对象 QI 到另一个 IID），后面才是新增的。
    [ComImport, Guid("BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioSessionControl2
    {
        [PreserveSig] int GetState(out int state);
        [PreserveSig] int GetDisplayName([MarshalAs(UnmanagedType.LPWStr)] out string name);
        [PreserveSig] int SetDisplayName([MarshalAs(UnmanagedType.LPWStr)] string value, IntPtr eventContext);
        [PreserveSig] int GetIconPath([MarshalAs(UnmanagedType.LPWStr)] out string path);
        [PreserveSig] int SetIconPath([MarshalAs(UnmanagedType.LPWStr)] string value, IntPtr eventContext);
        [PreserveSig] int GetGroupingParam(out Guid grouping);
        [PreserveSig] int SetGroupingParam(IntPtr grouping, IntPtr eventContext);
        [PreserveSig] int RegisterAudioSessionNotification(IntPtr events);
        [PreserveSig] int UnregisterAudioSessionNotification(IntPtr events);
        [PreserveSig] int GetSessionIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string id);
        [PreserveSig] int GetSessionInstanceIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string id);
        [PreserveSig] int GetProcessId(out int processId);
        [PreserveSig] int IsSystemSoundsSession();
        [PreserveSig] int SetDuckingPreference(int optOut);
    }

    [ComImport, Guid("87CE5498-68D6-44E5-9215-6DA47EF883D8"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface ISimpleAudioVolume
    {
        [PreserveSig] int SetMasterVolume(float level, IntPtr eventContext);
        [PreserveSig] int GetMasterVolume(out float level);
        [PreserveSig] int SetMute(int mute, IntPtr eventContext);
        [PreserveSig] int GetMute(out int mute);
    }

    private static int Main(string[] args)
    {
        try
        {
            Console.OutputEncoding = new UTF8Encoding(false);
            Console.SetOut(new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(false))
            {
                AutoFlush = true
            });
        }
        catch (Exception)
        {
            // 重定向句柄被游戏进程接管时 SetOut 可能失败；保持默认输出流继续跑。
        }

        Emit("{\"event\":\"ready\",\"version\":" + ProtocolVersion.ToString(CultureInfo.InvariantCulture) + "}");

        // --once 用于自检（设置页的「重新探测」按钮、以及离线验证脚本）。
        // RequestAsync 完成后系统里的会话列表是异步填充的：读得太早会拿到空表，
        // 把「有会话」误报成「没有」。这里给最多两秒的落位窗口，没有就按真没有报。
        if (args.Length > 0 && args[0] == "--once")
        {
            try
            {
                Manager();
                for (int i = 0; i < 10; i++)
                {
                    IReadOnlyList<GlobalSystemMediaTransportControlsSession> sessions =
                        manager == null ? null : manager.GetSessions();
                    if (sessions != null && sessions.Count > 0) break;
                    Thread.Sleep(200);
                }
            }
            catch (Exception)
            {
                // 建管理器失败就交给 State() 报错。
            }
            Console.Out.WriteLine(State());
            return 0;
        }

        Stream input = Console.OpenStandardInput();
        using (StreamReader reader = new StreamReader(input, new UTF8Encoding(false), false))
        {
            while (true)
            {
                string line = reader.ReadLine();
                if (line == null) break;
                line = line.Trim();
                if (line.Length == 0) continue;
                if (line == "poll") { Emit(State()); continue; }
                if (line == "info")
                {
                    bool hasSession = false;
                    try
                    {
                        GlobalSystemMediaTransportControlsSessionManager mgr = Manager();
                        lock (SessionLock)
                        {
                            if (stateDirty) ReselectLocked();
                            hasSession = mgr != null && selected != null;
                        }
                    }
                    catch (Exception) { lock (SessionLock) { manager = null; selected = null; } }
                    Emit("{\"event\":\"info\",\"version\":" + ProtocolVersion.ToString(CultureInfo.InvariantCulture)
                        + ",\"session\":" + Bool(hasSession) + "}");
                    continue;
                }
                if (line == "exit") break;
                if (line == "list")
                {
                    string fragment;
                    lock (SessionLock)
                    {
                        if (stateDirty) ReselectLocked();
                        fragment = SessionsFragment();
                    }
                    Emit("{\"event\":\"list\",\"sessions\":" + fragment + "}");
                    continue;
                }
                if (line.StartsWith("use ", StringComparison.Ordinal))
                {
                    string value = line.Substring(4).Trim();
                    lock (SessionLock)
                    {
                        pinnedAumid = value.Length == 0 || value == "-" ? null : value;
                        stateDirty = true;
                        ReselectLocked();
                    }
                    Emit("{\"event\":\"use\",\"ok\":true}");
                    continue;
                }
                if (line.StartsWith("pub", StringComparison.Ordinal))
                {
                    Publish(line);
                    continue;
                }
                if (line.StartsWith("cmd ", StringComparison.Ordinal))
                {
                    Command(line.Substring(4).Trim(), "");
                    continue;
                }
                if (line.StartsWith("seek ", StringComparison.Ordinal))
                {
                    Command("seek", line.Substring(5).Trim());
                    continue;
                }
                if (line.StartsWith("vol ", StringComparison.Ordinal))
                {
                    Volume(line.Substring(4).Trim());
                    continue;
                }
                Emit("{\"event\":\"error\",\"error\":\"unknown_command\"}");
            }
        }
        return 0;
    }
}
