package com.muoniumplayer.loader.forge;

import com.muoniumplayer.MuoniumPlayerClient;
import com.muoniumplayer.party.net.MpNetForge;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Forge 客户端入口。与 {@code fabric/} 下的 {@code MuoniumPlayerMod} 一一对应，
 * 业务逻辑全在共享的 {@link MuoniumPlayerClient} 里，这里只做加载器绑定。
 *
 * <p>四个挂点与 Fabric 的对应关系（都用 javap 核过 Forge 1.20.1-47.4.0 的真实签名）：
 * <table>
 *   <tr><td>Fabric</td><td>Forge</td></tr>
 *   <tr><td>{@code ClientModInitializer}</td><td>{@code @Mod} + {@code FMLClientSetupEvent}</td></tr>
 *   <tr><td>{@code KeyBindingHelper.registerKeyBinding}</td><td>{@code RegisterKeyMappingsEvent#register}</td></tr>
 *   <tr><td>{@code ClientTickEvents.END_CLIENT_TICK}</td><td>{@code TickEvent.ClientTickEvent}（END 阶段）</td></tr>
 *   <tr><td>{@code HudRenderCallback}</td><td>{@code RenderGuiEvent.Post}</td></tr>
 *   <tr><td>{@code ScreenEvents.afterRender}</td><td>{@code ScreenEvent.Render.Post}</td></tr>
 * </table>
 *
 * <p>1.20.1 的 Forge {@code @Mod} 注解只有 {@code value} 一个属性，没有 NeoForge 那个
 * {@code dist}；「客户端专用」由 {@code mods.toml} 的 {@code side = "CLIENT"} 声明，
 * 再加这里的 {@link FMLEnvironment#dist} 判断兜底 —— 万一被装进服务端，
 * 不判断就会在挂客户端事件时 NoClassDefFoundError。
 */
@Mod("muonium")
public final class MuoniumPlayerForge {

    public MuoniumPlayerForge() {
        if (!FMLEnvironment.dist.isClient()) {
            return;
        }

        // ---- mod 总线（加载期一次性事件）----
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::onClientSetup);
        modBus.addListener(this::onRegisterKeyMappings);

        // ---- 游戏总线（每帧 / 每 tick）----
        IEventBus gameBus = MinecraftForge.EVENT_BUS;
        gameBus.addListener(this::onClientTick);
        gameBus.addListener(this::onRenderGui);
        gameBus.addListener(this::onScreenRendered);

        // 一起听通道：1.20.1 是裸 custom payload，登记不需要 mod 总线事件，
        // 但必须在 FMLCommonSetupEvent 之前（Forge 的通道注册表在那之后就锁了）。
        MpNetForge.register();
    }

    /**
     * {@code FMLClientSetupEvent} 继承 {@code ParallelDispatchEvent}（并行派发），
     * 而 {@code MuoniumPlayerClient#init()} 会注入单例、注册模块、起后台线程，
     * 必须回到主线程执行，所以走 {@code enqueueWork}。
     */
    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            // 加载器 id 必须在 init() 之前登记：更新检查按「MC 版本 + 加载器」查清单。
            MuoniumPlayerClient.setLoaderId("forge");
            MuoniumPlayerClient.getInstance().init();
        });
    }

    private void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        for (KeyMapping key : MuoniumPlayerClient.createKeyMappings()) {
            event.register(key);
        }
    }

    /**
     * 对应 Fabric 的 END_CLIENT_TICK。
     *
     * <p>1.20.1 的 Forge 还是「一个事件类 + Phase 枚举」，不是 1.20.2+ 的
     * {@code ClientTickEvent.Post} 子类，所以必须自己判 phase；
     * 不判会让每 tick 的逻辑跑两遍（START 与 END 各一次）。
     */
    private void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
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
