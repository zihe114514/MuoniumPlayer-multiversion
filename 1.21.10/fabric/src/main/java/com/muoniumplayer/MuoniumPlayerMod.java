package com.muoniumplayer;

import com.muoniumplayer.party.net.MpNetFabric;
import com.muoniumplayer.compat.mc.WorldLyricsBridge;
import com.muoniumplayer.core.screens.TitleMenuAnnouncementButton;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;

/**
 * Fabric 客户端入口。本类只做四件事，业务逻辑全在共享的 {@link MuoniumPlayerClient} 里：
 * <ol>
 *   <li>{@code fabric.mod.json} 的 client entrypoint 让 Loader 加载本类（替代 Forge 的 {@code @Mod}）；</li>
 *   <li>把七个按键交给 {@link KeyBindingHelper} 注册（NeoForge 侧走 {@code RegisterKeyMappingsEvent}）；</li>
 *   <li>挂三个事件：{@link ClientTickEvents#END_CLIENT_TICK}、{@link HudRenderCallback}、
 *       {@link ScreenEvents#afterRender}（后者必须在 AFTER_INIT 里为每个 Screen 实例挂一次）；
 *       3D 世界歌词由 {@code WorldLyricsRenderTailMixin} 挂在帧图之后（见 onInitializeClient 内注释）；</li>
 *   <li>注册一起听通道的 Fabric 实现 {@link MpNetFabric}。</li>
 * </ol>
 */
public class MuoniumPlayerMod implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        MuoniumPlayerClient client = MuoniumPlayerClient.getInstance();

        // 1. OpenAPI 单例 + 扩展初始化（顺序见 MuoniumPlayerClient 的调用契约）
        // 加载器 id 必须在 init() 之前登记：更新检查按「MC 版本 + 加载器」查清单。
        MuoniumPlayerClient.setLoaderId("fabric");
        client.init();

        // 2. 一起听（服务端房间）通道：服务器没装一起听服务端 mod 时全程无副作用，
        //    装了则由 MpPartyClient 接管房间内的播放/点歌，复用播放器现有的播放链路。
        MpNetFabric.register();

        // 内嵌一起听房间引擎（局域网主机）：注册服务端方向的 C2S 接收器与生命周期钩子。
        // 集成服务端不存在时全程静止；专用服务器上本 mod 不加载，与此处代码无关。
        com.muoniumplayer.party.lan.LanNetFabric.register();

        // 3D 世界歌词：装纹理宿主（DynamicTexture 注册），挂在下面的世界渲染事件前。
        WorldLyricsBridge.install();

        // 3. 可配置按键（出现在「选项 → 控制 → MuoniumPlayer」分组下）
        for (KeyMapping key : MuoniumPlayerClient.createKeyMappings()) {
            KeyBindingHelper.registerKeyBinding(key);
        }

        // 4. 事件挂点
        ClientTickEvents.END_CLIENT_TICK.register(client::onClientTick);
                // 更激进的适配：光影包激活时 3D 歌词推迟到 HUD 阶段直画（Iris 后处理链之后）。
        // 注册序 = 调用序，放在 HUD 回调之前 → 歌词画在 HUD 之下。
        HudRenderCallback.EVENT.register((drawContext, tickCounter) ->
                com.muoniumplayer.compat.mc.WorldLyricsBridge.hudPhaseDraw());
HudRenderCallback.EVENT.register((drawContext, tickCounter) -> {
            client.onHudRender();
            // 第二行是给「渲染层没起来」时的原版 GUI 提示：Skia 那条路整个跳过时，
            // 只有原版 GUI 这条路还能说话，而用户此刻看到的正是一片空白。
            client.onRenderLayerNotice(drawContext, false);
        });
        // 3D 世界歌词：不再挂 WorldRenderEvents.END_MAIN —— 那个钩子注入在 main pass
        // 执行体末尾，云（clouds pass）/天气（weather pass）都是 main pass 之后的独立
        // pass，歌词画得太早会被云直接混叠盖住。fabric-api 0.141.6 没有「云之后」的
        // 世界事件，改用本 mod 的 WorldLyricsRenderTailMixin 注入
        // LevelRenderer.renderLevel 的 TAIL（帧图执行完、modelViewStack 还在栈上），
        // 与 NeoForge 侧 RenderLevelStageEvent.AfterLevel 同一时机。
        // 矩阵与 GL 状态由 WorldLyricsBridge.worldRenderEnd() 自己设。
        ScreenEvents.AFTER_INIT.register((minecraft, screen, scaledWidth, scaledHeight) -> {
            ScreenEvents.afterRender(screen).register(
                    (renderedScreen, drawContext, mouseX, mouseY, tickDelta) -> {
                        client.onScreenRendered(renderedScreen);
                        client.onRenderLayerNotice(drawContext, true);
                    });
            // 主菜单上的「公告」入口。AFTER_INIT 在原版 init() 之后派发，窗口 resize 时会再来一次，
            // 而那时原版已经清空了自己的组件，所以这里 add 一次就是一次，不会越加越多。
            if (TitleMenuAnnouncementButton.appliesTo(screen)) {
                Screens.getButtons(screen).add(TitleMenuAnnouncementButton.create());
            }
        });
    }
}
