// MuoniumPlayer 的系统媒体会话读取助手（SMTC / Windows Media Transport Controls）。
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
// 协议（每行一条，stdout 输出 UTF-8 不带 BOM，由 Java 侧显式按 UTF-8 读）：
//   启动即输出一行 {"event":"ready","version":1}
//   in  poll            -> {"event":"state","session":true,"app":"...","title":"...",
//                           "artist":"...","album":"...","posMs":123,"durMs":456,
//                           "state":"Playing","caps":{"play":true,"pause":false,
//                           "toggle":true,"next":false,"prev":false,"seek":true},
//                           "art":"art-1a2b3c.png"}
//                        没有会话时 {"event":"state","session":false}
//                        "art" 是本次会话封面图的文件名（与助手 exe 同目录），没有封面时为空串。
//                        同一首只在第一次 poll 时取流落盘，之后每拍原样回报同一个文件名。
//   in  cmd <key>       -> {"event":"cmd","key":"toggle","accepted":true,"error":""}
//                        key: play / pause / toggle / next / prev / stop
//   in  seek <毫秒>     -> 同 cmd，key 为 seek
//   in  vol <0..1>      -> {"event":"vol","ok":true,"vol":0.8300,"pid":19520}
//                        写的是「那支程序在音量合成器里的一格」，不是主音量；配不上程序时
//                        ok:false 并且什么都不写。写成功后新的值会随下一条 poll 的状态行
//                        一起回来（"volok"/"vol"/"pid"），所以 Java 侧不必解这条。
//   in  info            -> {"event":"info","version":1,"session":true}
//   in  exit            -> 进程结束
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
using Windows.Media.Control;
using Windows.Storage.Streams;

internal static class SmtcWatcher
{
    private const int ProtocolVersion = 1;

    /** 封面落盘的上限。专辑图常见 300KB~1MB，超过这个数就不当它是封面了。 */
    private const long MaxArtBytes = 4L * 1024L * 1024L;
    // 取封面那条的等待轮数（每轮 4ms = 400ms）。助手是串行读 stdin 的，这里等太久
    // 会把「暂停/下一首」这类命令一起拖住，所以宁可这一首没封面。
    private const int ArtSpins = 100;

    private static GlobalSystemMediaTransportControlsSessionManager manager;

    /** 上一次取过封面的那首（曲名|歌手）与当时落盘的文件名；空串表示那首确实没有封面。 */
    private static string lastArtKey;
    private static string lastArtFile = "";


    private static GlobalSystemMediaTransportControlsSessionManager Manager()
    {
        if (manager != null) return manager;
        manager = Await(GlobalSystemMediaTransportControlsSessionManager.RequestAsync());
        return manager;
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
     * <p>不用 System.WindowsRuntimeSystemExtensions.AsTask()：那要多引一个 GAC 里的
     * System.Runtime.WindowsRuntime.dll，路径按版本号分目录、在不同机器上要 glob 找，
     * 而且实测它的泛型重载在这个投影组合下解析不出来。轮询 Status 再取 GetResults()
     * 只要 winmd 本身，依赖更少、行为更好推理。</p>
     *
     * @param spins 轮询次数，每次隔 4ms。取封面那条给的是 100（400ms）—— 助手是串行处理
     *              stdin 的，这里卡太久会把「暂停/下一首」这类命令一起拖住。
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

    private static void State()
    {
        try
        {
            GlobalSystemMediaTransportControlsSessionManager mgr = Manager();
            if (mgr == null) { Emit("{\"event\":\"state\",\"session\":false,\"error\":\"no_manager\"}"); return; }
            GlobalSystemMediaTransportControlsSession session = mgr.GetCurrentSession();
            if (session == null) { Emit("{\"event\":\"state\",\"session\":false}"); return; }

            GlobalSystemMediaTransportControlsSessionMediaProperties props = null;
            try { props = Await(session.TryGetMediaPropertiesAsync()); }
            catch (Exception e) { props = null; EmitError("props", e); }

            GlobalSystemMediaTransportControlsSessionTimelineProperties timeline =
                session.GetTimelineProperties();
            GlobalSystemMediaTransportControlsSessionPlaybackInfo playback = session.GetPlaybackInfo();
            GlobalSystemMediaTransportControlsSessionPlaybackControls controls = playback.Controls;

            long posMs = (long)timeline.Position.TotalMilliseconds;
            long durMs = (long)timeline.EndTime.TotalMilliseconds;
            if (posMs < 0L) posMs = 0L;
            if (durMs < 0L) durMs = 0L;

            string title = props == null ? "" : NullToEmpty(props.Title);
            string artist = props == null ? "" : NullToEmpty(props.Artist);
            string album = props == null ? "" : NullToEmpty(props.AlbumTitle);
            string aumid = NullToEmpty(session.SourceAppUserModelId);
            // 封面放在状态行一起回，是为了让「这一首」和「这张封面」天然同拍：
            // 分成两条事件就会出现封面先到、曲名后到（或反过来）的错配窗口。
            string art = Artwork(props, title, artist);

            StringBuilder sb = new StringBuilder(256);
            sb.Append("{\"event\":\"state\",\"session\":true");
            sb.Append(",\"app\":\"").Append(Esc(aumid)).Append('"');
            sb.Append(",\"title\":\"").Append(Esc(title)).Append('"');
            sb.Append(",\"artist\":\"").Append(Esc(artist)).Append('"');
            sb.Append(",\"album\":\"").Append(Esc(album)).Append('"');
            sb.Append(",\"posMs\":").Append(posMs.ToString(CultureInfo.InvariantCulture));
            sb.Append(",\"durMs\":").Append(durMs.ToString(CultureInfo.InvariantCulture));
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
            sb.Append(VolumeFragment(aumid));
            sb.Append('}');
            Emit(sb.ToString());
        }
        catch (Exception e)
        {
            // 会话在两次读取之间结束是常态；这种情况报 no_session 而不是错误，
            // Java 侧据此把镜像切到「无媒体会话」，不要把它记成助手故障。
            manager = null;
            Emit("{\"event\":\"state\",\"session\":false,\"error\":\"" + Esc(e.GetType().Name) + "\"}");
        }
    }

    private static string Bool(bool value)
    {
        return value ? "true" : "false";
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
     * <p>读失败（流被播放器收了、激活不了 DataReader、磁盘写不进去）时<b>不记键</b>，
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

    /** 只留最近两张封面：切歌是常态，目录不能无限长。 */
    private static void Prune(string directory, string keep)
    {
        try
        {
            string[] files = Directory.GetFiles(directory, "art-*");
            if (files.Length <= 2) return;
            // 按写入时间排，最新的两张留下。文件名里是哈希，按名字排等于随机排。
            Array.Sort(files, delegate(string a, string b)
            {
                return File.GetLastWriteTimeUtc(a).CompareTo(File.GetLastWriteTimeUtc(b));
            });
            for (int i = 0; i < files.Length - 2; i++)
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
            GlobalSystemMediaTransportControlsSessionManager mgr = Manager();
            GlobalSystemMediaTransportControlsSession session = mgr == null ? null : mgr.GetCurrentSession();
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
                    bool playing = session.GetPlaybackInfo().PlaybackStatus.ToString() == "Playing";
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
                    accepted = Await(session.TryChangePlaybackPositionAsync(ms));
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
            manager = null;
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
            GlobalSystemMediaTransportControlsSessionManager mgr = Manager();
            GlobalSystemMediaTransportControlsSession session =
                    mgr == null ? null : mgr.GetCurrentSession();
            if (session != null) aumid = NullToEmpty(session.SourceAppUserModelId);
        }
        catch (Exception e)
        {
            manager = null;
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
        if (args.Length > 0 && args[0] == "--once")
        {
            State();
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
                if (line == "poll") { State(); continue; }
                if (line == "info")
                {
                    bool hasSession = false;
                    try
                    {
                        GlobalSystemMediaTransportControlsSessionManager mgr = Manager();
                        hasSession = mgr != null && mgr.GetCurrentSession() != null;
                    }
                    catch (Exception) { manager = null; }
                    Emit("{\"event\":\"info\",\"version\":" + ProtocolVersion.ToString(CultureInfo.InvariantCulture)
                        + ",\"session\":" + Bool(hasSession) + "}");
                    continue;
                }
                if (line == "exit") break;
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
