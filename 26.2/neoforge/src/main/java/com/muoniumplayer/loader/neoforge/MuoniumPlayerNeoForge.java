package com.muoniumplayer.loader.neoforge;

import com.muoniumplayer.MuoniumPlayerClient;
import com.muoniumplayer.compat.mc.WorldLyricsBridge;
import com.muoniumplayer.core.screens.TitleMenuAnnouncementButton;
import com.muoniumplayer.party.net.MpNetNeoForge;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge 客户端入口。与 {@code fabric/} 下的 {@code MuoniumPlayerMod} 一一对应，
 * 业务逻辑全在共享的 {@link MuoniumPlayerClient} 里，这里只做加载器绑定。
 *
 * <p>四个挂点与 Fabric 的对应关系（都用 javap 核过 NeoForge 26.2.0.72 的真实签名）：
 * <table>
 *   <tr><td>Fabric</td><td>NeoForge</td></tr>
 *   <tr><td>{@code ClientModInitializer}</td><td>{@code @Mod} + {@code FMLClientSetupEvent}</td></tr>
 *   <tr><td>{@code KeyBindingHelper.registerKeyBinding}</td><td>{@code RegisterKeyMappingsEvent#register}</td></tr>
 *   <tr><td>{@code ClientTickEvents.END_CLIENT_TICK}</td><td>{@code ClientTickEvent.Post}</td></tr>
 *   <tr><td>{@code HudRenderCallback}</td><td>{@code RenderGuiEvent.Post}</td></tr>
 *   <tr><td>{@code ScreenEvents.afterRender}</td><td>{@code ScreenEvent.Render.Post}</td></tr>
 * </table>
 *
 * <p>{@code dist = Dist.CLIENT}：客户端专用 mod，装到服务端上直接不加载，
 * 与 {@code fabric.mod.json} 里的 {@code "environment": "client"} 等价。
 */
@Mod(value = "muonium", dist = Dist.CLIENT)
public final class MuoniumPlayerNeoForge {

    public MuoniumPlayerNeoForge(IEventBus modBus) {
        // ---- mod 总线（加载期一次性事件）----
        modBus.addListener(this::onClientSetup);
        modBus.addListener(this::onRegisterKeyMappings);

        // 一起听通道：负载登记也在 mod 总线上，因此把总线传进去。
        MpNetNeoForge.register(modBus);
        // 内嵌一起听房间引擎（局域网主机）：接通 C2S 槽位并挂集成服务端生命周期事件。
        // 不在专用服务器上加载（dist=CLIENT），连远程服时引擎不激活、全程静止。
        com.muoniumplayer.party.lan.LanNetNeoForge.register();

        // ---- 游戏总线（每帧 / 每 tick）----
        IEventBus gameBus = NeoForge.EVENT_BUS;
        gameBus.addListener(this::onClientTick);
        gameBus.addListener(this::onRenderGui);
        gameBus.addListener(this::onScreenRendered);
        gameBus.addListener(this::onScreenInit);
        gameBus.addListener(this::onSubmitCustomGeometry);

        // 3D 世界歌词：装纹理宿主（DynamicTexture 注册）。
        WorldLyricsBridge.install();
    }

    /**
     * {@code FMLClientSetupEvent} 是并行派发事件（ParallelDispatchEvent），
     * 而 {@code MuoniumPlayerClient#init()} 会注入单例、注册模块、起后台线程，
     * 必须回到主线程执行，所以走 {@code enqueueWork}。
     */
    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            // 加载器 id 必须在 init() 之前登记：更新检查按「MC 版本 + 加载器」查清单。
            MuoniumPlayerClient.setLoaderId("neoforge");
            MuoniumPlayerClient.getInstance().init();
        });
    }

    /**
     * 按键分类必须单独登记一次。
     *
     * <p>{@code KeyMapping.Category.register(Identifier)} 只做了「构造 + 查重」，
     * 并不会把分类塞进原版的 {@code SORT_ORDER}（那是 static final 的原版清单）。
     * NeoForge 的控制界面用的是本事件里的 {@code moddedCategories}，
     * 漏了这一步，键位能绑定但分组标题不会出现在「选项 → 控制」里。
     */
    private void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.registerCategory(MuoniumPlayerClient.KEY_CATEGORY);
        for (KeyMapping key : MuoniumPlayerClient.createKeyMappings()) {
            event.register(key);
        }
    }

    /** 对应 Fabric 的 END_CLIENT_TICK：Post 是这一 tick 的末尾。 */
    private void onClientTick(ClientTickEvent.Post event) {
        MuoniumPlayerClient.getInstance().onClientTick(Minecraft.getInstance());
    }

    /** 对应 Fabric 的 HudRenderCallback：原版 HUD 全部画完之后。 */
    private void onRenderGui(RenderGuiEvent.Post event) {
        MuoniumPlayerClient.getInstance().onHudRender();
        // 停用时的原版 GUI 提示：Skia 那条路没起来时，只有原版 GUI 还能说话。
        MuoniumPlayerClient.getInstance().onRenderLayerNotice(event.getGuiGraphics(), false);
    }

    /** 对应 Fabric 的 ScreenEvents.afterRender：界面画完之后叠灵动岛。 */
    private void onScreenRendered(ScreenEvent.Render.Post event) {
        MuoniumPlayerClient.getInstance().onScreenRendered(event.getScreen());
        MuoniumPlayerClient.getInstance().onRenderLayerNotice(event.getGuiGraphics(), true);
    }

    /**
     * 3D 世界歌词：对应 Fabric 的 {@code LevelRenderEvents.COLLECT_SUBMITS} ——
     * 世界渲染采集的最后。1.21.1 用 {@code RenderLevelStageEvent.AFTER_LEVEL}；
     * 26.2 文字进 renderpearl 延迟采集（submitText 进 SubmitNodeStorage，
     * prepareFrame drain 后 main pass 才画），AFTER_LEVEL 在 drain 之后、太迟，
     * 换成 {@code SubmitCustomGeometryEvent}：实体/B/E/粒子提交完、finalizeGizmos
     * 前派发，事件自带 collector（javap 核对 NeoForge 26.2.0.72）。
     * 矩阵与提交由 {@code WorldLyricsBridge.worldRenderCollect} 自己管。
     */
    private void onSubmitCustomGeometry(SubmitCustomGeometryEvent event) {
        WorldLyricsBridge.worldRenderCollect(event.getSubmitNodeCollector());
    }

    /**
     * 主菜单上的「公告」入口，对应 Fabric 侧 {@code Screens.getButtons(screen).add(...)}。
     *
     * <p>{@code ScreenEvent.Init.Post} 由原版 {@code Screen#init} 与 {@code rebuildWidgets} 在装配完
     * 自己的按钮之后派发，而它的 add 消费者会同时写进 renderables / narratables / children，
     * 所以这里只管 add 一颗就够：能被看见、能被点到、能被读屏念到。</p>
     */
    private void onScreenInit(ScreenEvent.Init.Post event) {
        if (!TitleMenuAnnouncementButton.appliesTo(event.getScreen())) {
            return;
        }
        event.addListener(TitleMenuAnnouncementButton.create());
    }
}
