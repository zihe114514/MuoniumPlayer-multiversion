# 1.21.10 移植笔记（2026-11）

基座：`1.21.1` 树的脚手架 + 差异层骨架，渲染栈整体取自 `1.21.11 recode`。
1.21.10 是**过渡代际**，两头都不能整树照抄，下面是实测出的分界线。

## 各子系统的代际归属（javap / 编译实测）

| 子系统 | 1.21.10 的形状 | 差异层来源 |
| --- | --- | --- |
| GUI 绘制 | 即时 `GuiGraphics`（`GuiGraphicsExtractor` 1.21.11 才来） | 1.21.11 树（同形状） |
| 输入事件 | 已是记录类型：`KeyEvent` / `CharacterEvent` / `MouseButtonEvent`（1.21.9 起），`mouseScrolled` 仍四参 | 1.21.11 |
| 世界实体渲染 | submit/collect（`EntityRenderDispatcher.render` 已删，`SubmitNodeCollector` 在） | 1.21.11 |
| 纹理 | `DynamicTexture` 构造已走 `GpuDevice.createTexture`（texStorage 型不可变），但 **`GpuSampler`/`SamplerCache` 还没来**——过滤仍挂在 `AbstractTexture.setFilter/setClamp` | 过渡拼合：1.21.11 的上传路 + 1.21.1 的过滤路 |
| GL 状态机 | `GlStateManager` 已搬 `blaze3d.opengl`（1.21.1 还在 platform） | 1.21.11 |
| 命名 | 还是 `ResourceLocation` / `net.minecraft.Util` / `net.minecraft.client.renderer.RenderType`（静态工厂在 RenderType 本类）/ `npc.AbstractVillager`；1.21.11 才换成 `Identifier`/`util.Util`/`rendertype.RenderTypes`/`npc.villager.*` | 1.21.10 特有，全树改名回退 |
| fabric-api | 旧代际：`KeyBindingHelper`、`HudRenderCallback`、`world.WorldRenderEvents`（无 `COLLECT_SUBMITS`/`HudElementRegistry`） | 1.21.1 外壳 + 渲染尾 mixin |
| `KeyMapping.Category` | 已存在（1.21.9 起）；公开注册口 `register(ResourceLocation)`（`register(String)` 私有） | 1.21.11 客户端改形 |
| `Window` | 句柄口是 `handle()`（1.21.11 改名 `getWindow()`）；`InputConstants.isKeyDown` 吃 `(Window,int)` 而非 long | 1.21.1 外壳改形 |
| `CommandEncoder.writeToTexture` | 10 参子矩形口 `(tex, image, mip, layer, dx, dy, w, h, sx, sy)`，`NativeImage.upload` 已删 | 1.21.11 改参 |

## 树内特殊处理
- `WorldLyricsRenderTailMixin`（fabric 壳）：`LevelRenderer#renderLevel` TAIL 直画，NeoForge 走 `RenderLevelStageEvent.AfterLevel`。
- `FramebufferMixin`：`RenderTarget#blitToScreen()` TAIL + 主帧缓冲判等，Skija 帧末合成的钩子。
- `Port12110StaticTest` 钉住上表的过渡形状，谁「顺手现代化/降级」谁红。

## 验收记录
- `:fabric:compileJava` / `:fabric:test`（1901 项）/ `:neoforge:compileJava` / `:neoforge:test` 全绿。
- `:fabric:runClient` / `:neoforge:runClient` 起到标题界面（见提交信息里的验收日期）。
