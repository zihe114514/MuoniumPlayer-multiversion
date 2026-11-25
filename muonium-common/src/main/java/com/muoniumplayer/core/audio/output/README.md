# Music PCM output

`OpenAlPcmSink.open(int sampleRate, int channels)` returns `StreamingPcmSink`.
Opening performs native initialization on `muonium-openal-output` and reports an
`IOException` if the device, thread-local-context extension or layout is unavailable.
There is no JavaSound or process-context fallback.

```java
try (StreamingPcmSink sink = OpenAlPcmSink.open(48000, 2)) {
    sink.setGain(volume * focusGain); // [0,1], apply policy once at the caller
    sink.write(interleavedFloat32, sampleOffset, frameCount);
    // write accepted/copied the block; it does not prove audible hardware output.
    sink.drain();
}
```

- `write(float[], sampleOffset, frameCount)` counts offsets in samples, lengths in
  complete frames. It copies before returning; the caller may immediately reuse
  the input. Calls serialize; the single-producer contract preserves block order.
  Normal writes allocate no arrays, buffers or context scopes.
- One preallocated 8192-frame Java ring and four 1024-frame native buffers bound
  memory/backpressure. Native buffers may contain shorter blocks. A write larger
  than the ring can be partly accepted before interruption/stop/flush/close; do
  not retry that entire block. Backpressure is interruptible. Interrupt status is
  preserved, and a blocked call wakes on stop, flush, close or output failure.
- `setGain(float)` accepts finite [0,1]. The sink knows nothing about focus,
  broadcast capture, player volume, JSyn DSP, radio sessions or download policy.
  Radio should apply volume * focus gain once. It must keep live broadcasting
  exclusion in its own session/tap integration.
- `pause()` retains queued audio; `resume()` continues it. `stop()` cancels writes
  and discards both queues, leaving the sink stopped until `resume()`.
  `flush()` cancels pending writes and discards both queues while preserving the
  pause/accepting state. Control operations wait for owner acknowledgement.
- `drain()` waits for accepted frames to leave both queues; paused/stopped output
  is rejected. A non-consuming active device fails after five seconds. Latency is
  an upper estimate of queued frames, not a DAC timestamp or audible position.
- `close()` is idempotent and wakes producers before waiting for owner cleanup.
  It waits for cleanup even if its caller is already interrupted, restoring the
  interrupt flag afterwards; callers do not need to clear it. Source stop/unqueue,
  source/buffer deletion, TLS restoration and context/device destruction are owned by the same thread. No MC executor or shared native
  context is borrowed, and no process-wide context/capabilities are replaced.
  Every native operation saves/restores native TLS and AL/ALC capabilities;
  the scope fields are reused on the owner thread without per-write allocation.

## Format and channel contract

Input is float32 interleaved in the canonical OpenAL order:
1 channel; stereo L,R; 5.1(side) L,R,C,LFE,SL,SR;
7.1 L,R,C,LFE,BL,BR,SL,SR. A 5.1(back) layout or another order must be
remapped explicitly upstream. OpenAL Soft's X51Map names its last pair
SideLeft/SideRight; the contract must not relabel them as back channels.
Loopback tests verify channel indices, not physical speaker geometry.
Unsupported counts such as 3/4/5/7 are rejected, not guessed.

Each context probes actual `alBufferData` acceptance plus reported channels/bits.
6/8 channels additionally require `AL_EXT_MCFORMATS` and
`AL_SOFT_direct_channels_remix` so matching channels retain their layout while
unmatched channels are mixed rather than dropped; float upload additionally
requires `AL_EXT_FLOAT32`. Float is preferred; PCM16 is a format-only fallback
on the same OpenAL device (clipping/rounding, NaN/infinity to silence).
Float upload preserves finite headroom until the OpenAL gain/mixer stage;
PCM16 necessarily clips to its representable range. Neither fallback nor the sink resamples. If the requested layout is unavailable,
opening fails with a message requiring upstream explicit stereo conversion
(e.g. libswresample), followed by opening this same backend with two channels.
`capabilities().supportsChannels/Float32/Pcm16` describes buffer formats, **not
physical 5.1/7.1 hardware**; OpenAL can mix them for a stereo output device.

The source is fixed, head-relative and non-spatial: SOFT_source_spatialize is
explicitly disabled when available. Without that extension mono is duplicated to
stereo, whose core AL behavior is non-spatial. `outputChannels()` reports that
conversion. `usesFloat32()` reports actual upload encoding. There is no guessed
positional panning or HRTF on music output.

`OpenALAudioDevice` adapts the existing JSyn double stream to this sink. Its
reported maximum remains two channels because the existing main engine/DSP is
stereo. Direct 5.1/7.1 sink support does not upgrade that engine or its effects.
The manager creates no native resources; stream start creates a sink; both
stream stop and close release it. A later start opens a fresh sink. A terminal
sink failure is reported once; subsequent legacy writes wait interruptibly for
stop/close instead of driving JSyn's catch-and-continue loop at full speed.
The parent owns engine restart/UI policy after an output failure.

## Verified API baseline (2026-11-25)

Each of the fourteen existing `dumpCompileClasspath` files was read separately;
matching resolved JARs and source JARs were inspected with JDK 25 `javap` and the
new output subset was independently compiled with `javac --release 17`.

| MC | Fabric Loader / API | Other loader | Actual LWJGL |
| --- | --- | --- | --- |
| 1.20.1 | 0.19.3 / 0.92.11+1.20.1 | Forge 1.20.1-47.4.0 | 3.3.1 |
| 1.21.1 | 0.19.3 / 0.116.15+1.21.1 | NeoForge 21.1.249 | 3.3.3 |
| 1.21.10 | 0.19.3 / 0.138.4+1.21.10 | NeoForge 21.10.64 | 3.3.3 |
| 1.21.11 | 0.19.3 / 0.141.6+1.21.11 | NeoForge 21.11.45 | 3.3.3 |
| 26.1.2 | 0.19.3 / 0.155.3+26.1.2 | NeoForge 26.1.2.126 | 3.4.1 |
| 26.2 | 0.19.3 / 0.158.0+26.2 | NeoForge 26.2.0.72 | 3.4.1 |
| 26.3 | 0.19.5 / 0.161.0+26.3 | NeoForge 26.3.0.16-beta | 3.4.3 |

All use public declarations in org.lwjgl.openal:
`ALC.createCapabilities(long): ALCCapabilities`,
`ALC.getCapabilities()/setCapabilities(ALCCapabilities)`,
`EXTThreadLocalContext.alcGetThreadContext(): long`,
`alcSetThreadContext(long): boolean`,
`AL.createCapabilities(ALCCapabilities): ALCapabilities`,
`AL.getCapabilities()/setCurrentThread(ALCapabilities)`,
AL10 source generation/configuration/play/pause/stop/deletion, scalar buffer
creation/queue/unqueue/deletion, ByteBuffer alBufferData and alGetBufferi.
EXTFloat32, EXTMCFormats, SOFTDirectChannels and SOFTDirectChannelsRemix constants
and capability fields were checked on all four resolved LWJGL versions. MemoryUtil memAlloc/memCalloc/memFree was checked
in each target's actual core JAR. These calls need no loader events, access
configuration, mappings or Minecraft APIs. Source inspection confirms that
AL.createCapabilities chooses thread capabilities only after nonzero native
TLS binding; the implementation verifies that condition before calling it.

This API evidence is not fourteen full builds, Android/Linux/macOS execution,
or in-game listening. Gradle and game integration belong to parent validation.

## Radio integration

Radio may pre-scale its reusable float block by volume * focus gain and retain
sink gain = 1; do not apply the same gain twice. Alternatively setGain is
thread-safe and waits for owner acknowledgement, but it is not required when
radio already owns per-block scaling. Recompile test fakes against the current
interface (setGain and flush are part of it). write returning means the Java
queue accepted/copied all frames, not that hardware playback has started.
Closing an interrupted producer still waits for cleanup and restores its flag.
Radio owns decoder cancellation, generation/session validity and broadcast
exclusion; the output module has no EngineOutputTap dependency.

## Scoped validation

On 2026-11-25, the output subset and existing JSyn stream interfaces compiled
independently with Java 17 against the exact resolved LWJGL core/OpenAL JARs of
all fourteen loader targets; generated classes use major version 61. This is a
subset compilation, not compilation of each complete mod.

The same 30 focused tests passed separately with LWJGL 3.3.1, 3.3.3, 3.4.1 and
3.4.3 plus their corresponding Windows native JARs: 120 successful executions,
zero failures/aborts/skips. Coverage includes actual 6/8-channel loopback index
mapping, float precision/headroom, foreign TLS and AL/ALC capability restoration
on each operation, silent default-device output, JSyn stop/reopen, bounded
queue cancellation, paused flushing, terminal failure and native ownership.
These results establish neither physical surround output nor game listening,
and do not establish execution on Android, Linux or macOS. No Gradle tasks,
packaging changes or game launches were performed for this output scope.
