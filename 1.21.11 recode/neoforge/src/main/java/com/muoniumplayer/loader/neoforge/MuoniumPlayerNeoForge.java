package com.muoniumplayer.loader.neoforge;

import com.muoniumplayer.MuoniumPlayerClient;
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
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge 客户端入口。与 {@code fabric/} 下的 {@code MuoniumPlayerMod} 一一对应，
 * 业务逻辑全在共享的 {@link MuoniumPlayerClient} 里，这里只做加载器绑定。
 *
 * <p>四个挂点与 Fabric 的对应关系（都用 javap 核过 NeoForge 21.11.45 的真实签名）：
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

        // ---- 游戏总线（每帧 / 每 tick）----
        IEventBus gameBus = NeoForge.EVENT_BUS;
        gameBus.addListener(this::onClientTick);
        gameBus.addListener(this::onRenderGui);
        gameBus.addListener(this::onScreenRendered);
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
    }

    /** 对应 Fabric 的 ScreenEvents.afterRender：界面画完之后叠灵动岛。 */
    private void onScreenRendered(ScreenEvent.Render.Post event) {
        MuoniumPlayerClient.getInstance().onScreenRendered(event.getScreen());
    }
}
