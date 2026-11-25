# JavaCPP FFmpeg audio core

This package owns demuxing, audio track selection, decoding and PCM conversion. It is
shared Java 17 code with no Minecraft or loader API. PCM cache and output-device policy live outside the decoder.
Call it from a background worker. Playback, offline analysis and radio each open an
independent decoder; they do not share its seek cursor or native buffers.

## Public contract

```java
DecodeOptions options = DecodeOptions.builder()
        .openTimeout(Duration.ofSeconds(15))
        .readTimeout(Duration.ofSeconds(30))
        .header("User-Agent", "MuoniumPlayer")
        .caFile(trustedCaPem)
        .live(true)
        .build();
AudioInput input = AudioInput.uri(uri).withOptions(options);
CancellationToken cancellation = new CancellationToken();
try (FfmpegAudioDecoder decoder = FfmpegAudioDecoder.open(
        input, OutputSpec.preserveSource(), cancellation)) {
    AudioInfo info = decoder.info();
    PcmBlock block = new PcmBlock(4096, info.outputChannels());
    while (decoder.read(block) != -1) {
        consume(block.samples(), block.frames(), block.channels());
    }
}
```

- Files use `AudioInput.file(Path)`. URI inputs accept file/HTTP/HTTPS, including
  HLS playlists. Detection and the default audio track come from libavformat.
  `DecodeOptions.streamIndex(int)` selects an absolute audio stream index.
- Output is interleaved float32. `OutputSpec.preserveSource()` retains the first
  decoded frame's rate and layout; `mono(rate)`, `stereo(rate)` or
  `new OutputSpec(rate, nativeLayoutName)` request a conversion. A zero rate
  preserves the source rate. Both source and output support at most eight channels.
- `AudioInfo` is an immutable record with `streamIndex`, decoder `codec` name,
  `container`, source/output rate, channel count and layout description,
  `durationMicros` (-1 when unknown), `seekable`, and unconsumed native options.
  Decoder implementation names are literal native names, such as `mp3float`.
- `decoder.outputChannelMask()` is the native speaker mask only for
  `AV_CHANNEL_ORDER_NATIVE`; unspecified/custom/ambisonic orders return zero.
  A consumer must not infer a speaker arrangement solely from the channel count.
- `PcmBlock` is caller-owned and reused. Only `frames() * channels()` entries of
  `samples()` are valid; the unused tail is not cleared. `startFrame()` is the first
  output timeline frame. `read` returns actual positive frames or -1 after both the
  decoder and swresample have drained, and sets block frames to zero at EOF.
- `seek(Duration)` is media-relative. It flushes codec packets/frames, recreates
  swresample, decodes preroll, aligns the resampling phase and discards output up to
  `floor(position * outputRate)`. Fine native timebases use decoded timestamps;
  coarse timebases require decoding from the beginning and can be expensive.
  A required but missing timestamp causes IOException. Live/non-seekable inputs fail
  explicitly and the native `avformat_seek_file` result is authoritative.
- Mid-stream sample format, rate or layout changes fail explicitly; the caller must
  reopen and renegotiate. There is no implicit stereo reduction or PCM16 conversion.

## Bounded video covers

`FfmpegCoverDecoder.decode(File)` opens an independent worker-owned local input,
decodes H.264/HEVC and uses swscale for RGBA output. It samples PTS at 10 fps,
keeps at most fifty 256x256 frames with aspect-preserving transparent padding,
and handles display-matrix right-angle transforms. Input dimensions, packet/frame
counts, retained pixels and the thirty-second deadline are bounded. Cancellation
signals the AVIO callback; native resources are freed by the owner in `finally`.
No command-line process, PNG encoding pipe, avfilter, video encoder or JavaCV is
involved. GIF/image animation remains on the existing image path. Unsupported video
codecs retain static artwork. Fifteen actual Windows native cover tests passed;
Android/Linux device behavior remains separately unverified.

## Thread and resource ownership

Open, read and seek belong to the opening worker thread. They perform blocking IO
and native decode and must never run on an audio/render/client callback. The core
rejects known Minecraft render/client and JSyn thread names, but the caller remains
responsible for its executor. A single source AVFrame is limited to 262144 samples
per channel and a converted frame to 1048576 samples per channel (at most 32 MiB
of retained float output); normal reads copy only the caller's bounded block.
These limits do not purport to cap every allocation inside a codec or demuxer.

`CancellationToken.cancel()` and `decoder.cancel()` only publish flags. The strongly
referenced `AVIOInterruptCB.Callback_Pointer` checks cancellation, owner thread
interruption and a monotonic operation deadline. Native failures are IOException
with the FFmpeg operation, error text and error code. Cancellation is
InterruptedIOException and timeout is SocketTimeoutException, with a native cause
when a native operation returned an error. Java interruption is never cleared.

`close()` first publishes a close flag, then waits for the operation lock and releases
objects only after the active native call returns. It is idempotent and may be called
from another cleanup worker, but is blocking and must not be called on an audio/UI
callback. DNS/platform operations that do not consult FFmpeg's interrupt callback
cannot be forcibly stopped by freeing live pointers. IO failures are terminal for a
session; close it and open a new session for a retry.

Ownership was checked against the 8.1.2-1.5.14 sources JAR and bytecode:

| Allocation / view | Ownership and release |
| --- | --- |
| `avformat_alloc_context` / `avformat_open_input(@ByPtrPtr ...)` | `avformat_close_input`; open failure frees and nulls the supplied context |
| `avcodec_alloc_context3` | `avcodec_free_context(@ByPtrPtr ...)` |
| `av_packet_alloc` | `av_packet_unref` after accepted sends / ignored streams, then `av_packet_free` |
| `av_frame_alloc` | `av_frame_unref` after conversion / seek, then `av_frame_free` |
| `swr_alloc_set_opts2(@ByPtrPtr ...)` | `swr_free`; null input to `swr_convert` only at decoder EOF |
| JavaCPP `AVChannelLayout` plus `av_channel_layout_copy/from_string` | `av_channel_layout_uninit`, then JavaCPP `close` |
| JavaCPP `FloatPointer`, `PointerPointer`, callback | JavaCPP `close`, callback after format/IO close |
| `AVStream`, `AVCodecParameters`, frame layouts/data, static codec names/configuration/iteration results | Borrowed; never native-free or JavaCPP-close these views |
| `AVDictionary` populated by `av_dict_set` | `av_dict_free`, including unconsumed options |

The receive-first state machine retains a packet on send EAGAIN, sends a null packet
only after demux EOF, receives all delayed frames, then drains swresample. A decoder
that returns EAGAIN from both sides is rejected rather than spinning or losing data.
Corrupt packets and strict decoder checksum failures are errors. FLAC demux durations
are validated as exact source sample counts only when the selected FLAC stream uses
its verified 1/sampleRate timebase. Lossy duration estimates are not used as an exact
length assertion. Whole-source length validation is disabled after an explicit seek.

## Network and native loading

Per-input native options include validated HTTP headers, protocol whitelist, read
microsecond timeout, probe/analyze budget, forced `tls_verify=1` and optional
`ca_file`. The host application supplies a PEM trust bundle; the core does not depend
on game paths or download roots. HTTP/HTTPS/TCP/TLS/crypto remain enabled for nested
HLS playlist, key and media reads. Local files additionally allow the file and subfile protocols.
Reconnect and other demux/protocol options remain explicit; unsupported/unconsumed
options are observable in `AudioInfo`. TLS verification cannot be disabled through
`DecodeOptions.option`.

`FfmpegRuntime` loads JavaCPP avutil, swresample, avcodec and avformat bindings. It
starts FFmpeg network support once for the process lifetime, with no concurrent
network deinitialization and no executable, JavaCV or external codec fallback.
The one MOD embeds the slim Windows x64 runtime, including swscale and only the
H.264/HEVC cover-video decoders. `FfmpegNativeAssets` installs
Android arm64/Linux x64 slim companions on first background use, before any JavaCPP
native initializer. The public `/muonium/ffmpeg-native.properties` descriptor pins
one archive identity and multiple HTTPS mirrors per platform. Existing
`HttpDownloadRace` handles mirror racing; cache and extraction validate lengths,
SHA-256, required libraries and transaction boundaries. Download state is exposed
in the existing dependency manager. No object-storage credential is shipped.
Android copies verified libraries into a launcher-private executable directory;
the versioned content identity prevents overwriting a loaded native cohort.
Temporary JavaCPP search properties are restored after recursive binding initialization. `NativeCapabilities.query()` enumerates the loaded audio decoders, demuxers and
input protocols and reports all four actual versions and build configurations.

After loading avutil, the runtime sets `av_log_set_level(AV_LOG_ERROR)` once. This
suppresses native INFO/VERBOSE request logs such as signed HLS URLs while retaining
ERROR/FATAL diagnostics. The level is global to that loaded native library instance
and can affect other mods sharing it; the runtime does not replace the log callback
or repeatedly reset the level for individual decoders. Error messages can still
contain resource URLs, so this is not a complete credential-redaction guarantee.
JavaCPP 1.5.14 caches `Loader.PLATFORM` at class initialization, and preset static
initializers re-enter Loader with default properties. On OpenJDK Android the Android
markers (system build marker / ANDROID_ROOT / ANDROID_DATA) must therefore select
`org.bytedeco.javacpp.platform` before the first active use of Loader. This override
is persistent and affects other JavaCPP consumers in the JVM. Desktop platforms do
not change this property. An already cached Linux platform on Android fails explicitly;
no Linux native is attempted as an Android fallback. Runtime Android linker/cache
permissions and actual hardware compatibility require separate device validation.

## Verified scope and unresolved stock-native boundaries

Verified on 2026-11-25 with JavaCPP 1.5.14 / FFmpeg 8.1.2 Windows x86_64, a real JDK 17,
synthetic fixtures and loopback servers: PCM16/float precision and headroom, 5.1 channel
order, source rate, compressed MP3/FLAC/AAC-in-TS, arbitrary block tails, resampler
EOF, source and resampled seeks, cancellation, timeout, worker ownership, close during
HTTP read, HTTP headers, HTTP HLS, TLS unknown-CA rejection / explicit-CA acceptance,
and HTTPS master/media/key/encrypted-segment HLS with request headers and AES-128.
Tests do not need ffmpeg.exe, keytool, internet access or downloaded music at runtime.
The self-signed localhost PKCS12 is a synthetic test-only fixture. Native tests use an
explicit assumption only for an unpublished platform classifier. A supported
platform's installation/loading failure is a test failure. Windows uses the slim
Schannel build; its two synthetic `ca_file` fixtures require OpenSSL and are skipped
explicitly. Ordinary system-trusted Windows HTTPS/HLS has separate positive smoke evidence.

Local trailing TAG compatibility is implemented in the input bridge. After the native
probe identifies the raw FLAC demuxer, `LocalFileReadView` reads at most the final 128
bytes. A complete ID3v1 tag must have plausible text/year fields (with binary v1.1
track/genre bytes allowed). The legacy truncated form is accepted only for a
24..127-byte TAG followed entirely by spaces/NUL padding. Other suffixes are left
visible so normal strict decode errors are preserved.

For a recognized tail the decoder closes its initial native probe context and reopens
`subfile,,start,0,end,N,,:file:<absolutePath>`, with start/end also forced as final native
options so caller options cannot widen the bound. This syntax and exclusive read bound
were checked in FFmpeg 8.1.2 `doc/protocols.texi`, `libavformat/avio.c` and `subfile.c`.
The view is seekable, creates no copy, edits no source bytes, and leaves decoder CRC
and STREAMINFO sample-count checks intact. It is local-file-only; network inputs
continue unchanged. Large (>2 GiB) subfile seeking has not been verified.

The original 24/40/44/128-byte TAG regression cases now pass. Additional negative
cases retain errors for CRC damage, missing/extra frames and wrong declared counts
with each of those tails. Tests also cover Unicode/spaces/commas in a non-FLAC-named
path, seek after EOF through the view, and an unknown-count FLAC with a full tag.

The stock Windows OpenSSL backend's numeric-IP certificate identity gap remains
recorded and its dedicated remediation/testing is deferred per the current work
priority. A trusted certificate for the wrong DNS hostname is rejected, while the
same mismatched certificate was accepted for a numeric HTTPS IP. The 8.1.2
`tls_openssl.c` path skips `SSL_set1_host` for `numerichost`; `tls_verify=1` checks its
chain but does not fix that identity gap. Existing verification/CA configuration and
ordinary HTTPS/HLS tests stay enabled. No test is changed to bless the insecure
behavior, and stock numeric-IP, redirect or mixed-scheme HLS TLS security is not a
production-verified claim. Media transport remains native; native-dependency downloads use the existing Java download service.

Linux, macOS and Android native loading/playback, packaged trimmed libraries and
Minecraft loader/game integration have not been tested by this package's independent
checks. No Gradle task is launched by this package's development probes.
