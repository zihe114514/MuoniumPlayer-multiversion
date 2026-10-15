package com.muoniumplayer;

import com.muoniumplayer.party.net.MpNetFabric;
import com.muoniumplayer.core.screens.TitleMenuAnnouncementButton;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * Fabric 客户端入口。本类只做四件事，业务逻辑全在共享的 {@link MuoniumPlayerClient} 里：
 * <ol>
 *   <li>{@code fabric.mod.json} 的 client entrypoint 让 Loader 加载本类（替代 Forge 的 {@code @Mod}）；</li>
 *   <li>把七个按键交给 {@link KeyMappingHelper} 注册（NeoForge 侧走 {@code RegisterKeyMappingsEvent}）；</li>
 *   <li>挂三个事件：{@link ClientTickEvents#END_CLIENT_TICK}、{@link HudElementRegistry}、
 *       {@code ScreenEvents.afterExtract}（后者必须在 AFTER_INIT 里为每个 Screen 实例挂一次）；</li>
 *   <li>注册一起听通道的 Fabric 实现 {@link MpNetFabric}。</li>
 * </ol>
 *
 * <p>26.2 相对 1.21.11 的 Fabric API 变化（都是重命名/重构，语义不变）：
 * {@code KeyBindingHelper} → {@link KeyMappingHelper}、
 * {@code HudRenderCallback}（已删除）→ {@link HudElementRegistry} 的有序元素列表、
 * {@code ScreenEvents.afterRender} → {@code ScreenEvents.afterExtract}。
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

        // 3. 可配置按键（出现在「选项 → 控制 → MuoniumPlayer」分组下）
        for (KeyMapping key : MuoniumPlayerClient.createKeyMappings()) {
            KeyMappingHelper.registerKeyMapping(key);
        }

        // 4. 事件挂点
        ClientTickEvents.END_CLIENT_TICK.register(client::onClientTick);
        // 26.2 的 Fabric API 删掉了 HudRenderCallback，改成有序的 HUD 元素列表。
        // addLast 让本 mod 的绘制排在原版全部 HUD 元素之后，与旧回调的时机一致。
        // 两个形参（extractor / deltaTracker）都用不上：本渲染层不走原版 GUI 批次，
        // 自己开 Skia 帧画到离屏图上，帧末再合成（见 GlSurfaceMixin）。
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(MuoniumPlayerClient.MOD_ID, "player_hud"),
                (extractor, deltaTracker) -> {
                    client.onHudRender();
                    // 第二行是给「渲染层没起来」时的原版 GUI 提示：Skia 那条路整个跳过时，
                    // 只有原版 GUI 这条路还能说话，而用户此刻看到的正是一片空白。
                    client.onRenderLayerNotice(extractor, false);
                });
        // afterRender 在 26.2 改名 afterExtract：GUI 变成延迟批处理后，
        // 这个回调的实际语义是「Screen 已经把自己的元素录入 render state 之后」，
        // 而不是「已经画完」。对本渲染层没有影响，因为合成本来就推迟到帧末。
        ScreenEvents.AFTER_INIT.register((minecraft, screen, scaledWidth, scaledHeight) -> {
            ScreenEvents.afterExtract(screen).register(
                    (renderedScreen, extractor, mouseX, mouseY, tickDelta) -> {
                        client.onScreenRendered(renderedScreen);
                        client.onRenderLayerNotice(extractor, true);
                    });
            // 主菜单上的「公告」入口。AFTER_INIT 在原版 init() 之后派发，窗口 resize 时会再来一次，
            // 而那时原版已经清空了自己的组件，所以这里 add 一次就是一次，不会越加越多。
            // 26.2 把 Screens.getButtons 改名成了 getWidgets（返回的还是同一份活列表）。
            if (TitleMenuAnnouncementButton.appliesTo(screen)) {
                Screens.getWidgets(screen).add(TitleMenuAnnouncementButton.create());
            }
        });
    }
}
