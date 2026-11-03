# 26.1.2 移植笔记（2026-11）

基座：`26.2` 树的脚手架 + 差异层。26.1.2 与 26.2 同代，但有五处 26.2 才引入的 API，
逐处退回 26.1.2 的形状（全部 javap 核对 26.1.2 客户端 jar / fabric-api 0.155.3+26.1.2）：

| 26.2 写法 | 26.1.2 的形状 | 影响 |
| --- | --- | --- |
| `com.mojang.blaze3d.opengl.GlSurface` + `present()` | **类不存在**；帧末出口仍是 `RenderTarget#blitToScreen()` | `GlSurfaceMixin` → `RenderTargetPresentMixin`（TAIL + 主帧缓冲判等，1.21.11 FramebufferMixin 同款） |
| `feature.submit.SubmitNode` + `TextFeatureRenderer.Submit` / `CustomFeatureRenderer.Submit` | 节点抽象不存在 | `LyricOnTopSubmitter` 降级为普通相位直通（玻璃/云会盖 3D 歌词，26.1.2 的 API 边界） |
| fabric-api `SubmitRenderPhase(s)` | 不存在 | 同上 |
| `mc.gui.screen()` / `mc.gui.setScreen(x)` / `mc.gui.hud.*` | `mc.screen` / `mc.setScreen(x)` / `mc.gui.getChat()`；F1 状态在 `GameRenderState.optionsRenderState.hideGui` | 全树改名（14 文件） |
| `gameRenderer.mainCamera()/mainRenderTarget()/gameRenderState()` | `getMainCamera()` / `mc.getMainRenderTarget()` / `getGameRenderState()` | 全树改名 |
| `RenderPipeline.builder().withBindGroupLayout(...).withVertexBinding(...).withPrimitiveTopology(...)` | 1.21.x 形状：`withVertexFormat(VertexFormat, VertexFormat.Mode)`，uniform 由 RenderSetup 侧自动供给 | `WorldBillboard.glowType` 改写 |
| `CommandEncoder.writeToTexture(tex, image, 0, 0, x, y)`（6 参平移口） | 只有 2 参全量 / 10 参子矩形 / 9 参 ByteBuffer | `LyricGlyphAtlasBridge` 用 10 参口 `(…, 0, 0, x, y, w, h, 0, 0)` |
| `GpuDevice.getDeviceInfo().backendName()` | `GpuDevice.getBackendName()`（在 blaze3d.systems） | SkijaHost 后端探测 |

不变的部分：`GlStateManager` 已在 `blaze3d.opengl`（与 26.2 同）、`Identifier` 命名、
fabric-api 的 `HudElementRegistry` / `LevelRenderEvents` / `KeyMappingHelper` /
`ScreenEvents.afterExtract`、延迟批处理 GUI（`GuiGraphicsExtractor`）。

## 验收记录
- `:fabric:compileJava` / `:fabric:test` / `:neoforge:compileJava` / `:neoforge:test` 全绿。
- `:fabric:runClient` / `:neoforge:runClient` 起到标题界面（见提交信息里的验收日期）。
