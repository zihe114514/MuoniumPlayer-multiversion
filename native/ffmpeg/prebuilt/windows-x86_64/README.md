# Embedded Windows x64 media dependency

Pinned FFmpeg 8.1.2, JavaCPP/Presets 1.5.14. Five FFmpeg DLLs are built from the exact source release: avutil, swresample, avcodec, avformat and swscale. The audio whitelist is retained; only H.264/HEVC software video decoders and their parsers are added for bounded cover preparation. Encoders, muxers, filters, devices, hardware acceleration and command-line programs remain disabled. Windows uses Schannel and static zlib.

`manifest.json` pins every input length and SHA-256. `gradle/ffmpeg-audio.gradle` verifies these before creating the single nested runtime. These are intentional native dependency inputs, not MOD build outputs. The five FFmpeg DLLs total 5,132,288 bytes; the complete runtime including Java/JNI and Windows runtime dependencies is about 5.51 MiB.

Five matching official JNI bindings require 869 FFmpeg imports, all verified present. Actual Windows cover tests exercise H.264/HEVC, PTS, orientation, RGBA scaling, cancellation and native cleanup. Source/dependency versions, audio and cover capability inventory, and license texts are under `native/ffmpeg`. TLS identity hardening remains deferred. Linux and Android companions are source-built separately and downloaded on demand; cross-build/static validation is not a claim of target-device execution.
