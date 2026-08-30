package com.muoniumplayer;

import com.muoniumplayer.party.net.MpNetFabric;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.KeyMapping;

/**
 * Fabric 客户端入口。本类只做四件事，业务逻辑全在共享的 {@link MuoniumPlayerClient} 里：
 * <ol>
 *   <li>{@code fabric.mod.json} 的 client entrypoint 让 Loader 加载本类（替代 Forge 的 {@code @Mod}）；</li>
 *   <li>把七个按键交给 {@link KeyBindingHelper} 注册（NeoForge 侧走 {@code RegisterKeyMappingsEvent}）；</li>
 *   <li>挂三个事件：{@link ClientTickEvents#END_CLIENT_TICK}、{@link HudRenderCallback}、
 *       {@link ScreenEvents#afterRender}（后者必须在 AFTER_INIT 里为每个 Screen 实例挂一次）；</li>
 *   <li>注册一起听通道的 Fabric 实现 {@link MpNetFabric}。</li>
 * </ol>
 */
public class MuoniumPlayerMod implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        MuoniumPlayerClient client = MuoniumPlayerClient.getInstance();

        // 1. OpenAPI 单例 + 扩展初始化（顺序见 MuoniumPlayerClient 的调用契约）
        client.init();

        // 2. 一起听（服务端房间）通道：服务器没装一起听服务端 mod 时全程无副作用，
        //    装了则由 MpPartyClient 接管房间内的播放/点歌，复用播放器现有的播放链路。
        MpNetFabric.register();

        // 3. 可配置按键（出现在「选项 → 控制 → MuoniumPlayer」分组下）
        for (KeyMapping key : MuoniumPlayerClient.createKeyMappings()) {
            KeyBindingHelper.registerKeyBinding(key);
        }

        // 4. 事件挂点
        ClientTickEvents.END_CLIENT_TICK.register(client::onClientTick);
        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> client.onHudRender());
        ScreenEvents.AFTER_INIT.register((minecraft, screen, scaledWidth, scaledHeight) ->
                ScreenEvents.afterRender(screen).register(
                        (renderedScreen, drawContext, mouseX, mouseY, tickDelta) ->
                                client.onScreenRendered(renderedScreen)));
    }
}
