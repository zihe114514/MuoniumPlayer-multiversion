package com.muoniumplayer.mixin;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 3D 歌词晕格通道的 RenderType 构造辅助 —— {@code RenderType.create(String, RenderSetup)}
 * 是包私有（原版 RenderType 全部经 {@code RenderTypes} 工厂造出），而加色光晕需要
 * 自定义 RenderPipeline（beacon_beam 着色器 + BlendFunction.LIGHTNING），原版工厂
 * 没有这个组合的现成入口（beaconBeam(rl,true) 自带的是 TRANSLUCENT alpha 混合，
 * 不是加色 —— 晕格用它会在亮背景上消失）。
 */
@Mixin(RenderType.class)
public interface RenderTypeAccessors {

    @Invoker("create")
    static RenderType muonium$create(String name, RenderSetup setup) {
        throw new AssertionError();
    }
}
