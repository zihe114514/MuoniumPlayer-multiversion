# Audio runtime license texts

核对日期：2026-11-25。此目录供运行时打包复制到 `META-INF/muonium/ffmpeg/licenses/`。
上游文本按字节复制，不重排、不改换行；`licenses.lock.json` 记录官方来源、精确 release / archive member、原归档 SHA256 和文本 SHA256。
`.gitattributes` 保留原始 CRLF/LF，仅用于仓库字节稳定性，无需放入 runtime。

- FFmpeg 文本来自官方 `ffmpeg-8.1.2.tar.xz`：`LICENSE.md`、LGPL 2.1、LGPL 3 和 LGPL 3 引用的 GPL 3 全文。
- JavaCPP / JavaCPP Presets 文本来自各自 `1.5.14` release 对应固定 commit 的 `LICENSE.txt`，包括上游提供的 Apache 2.0 / GPL + Classpath exception 许可选择全文。
- 同时保留默认源码配方直接依赖的 OpenSSL 3.5.7、zlib 1.3.1，以及随包 CA 根证书来源 certifi 2026.11.20 的许可文本。
- 已核对的两个官方 Java 主 JAR 没有内嵌 LICENSE / COPYING / NOTICE 条目；这里补充的是上游 release 原文，不伪称从不存在的 JAR 条目提取。

此目录覆盖已列出的核心和默认配方依赖文本，**不是所有平台 stock native 的完整依赖许可审定**。
官方 FFmpeg 四库仍可能静态包含上游第三方 codec/TLS 等依赖；去掉 executable、avdevice、avfilter、swscale 或视频 Java 类不能消除仍链接在库内的组件和再分发义务。
Windows mixed 候选还保留官方 JNI / JavaCPP runtime 的实际依赖闭包；其余六 ABI 仍是 stock-component-trimmed。
必须保留实际 provenance，不能把通用包整体仅标为 LGPL，或称为“全平台已完整合规”。
未来若获授权发布，再按最终产物的真实依赖补齐对应 notices / source / 其他适用材料；当前只补充可追踪文本和打包入口，不开展发布法律专项或改变发布授权。
