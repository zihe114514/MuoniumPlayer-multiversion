package com.muoniumplayer.mixin;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 3D 歌词晕格通道的 RenderType 构造辅助 —— {@code RenderType.create(String, RenderSetup)}
 * 是包私有（26.x 原版 RenderType 全部经 {@code RenderTypes} 工厂造出），而加色光晕
 * 需要自定义 RenderPipeline（BEACON_BEAM 着色器 + BlendFunction.LIGHTNING），
 * 原版工厂没有这个组合的现成入口。
 */
@Mixin(RenderType.class)
public interface RenderTypeAccessors {

    @Invoker("create")
    static RenderType muonium$create(String name, RenderSetup setup) {
        throw new AssertionError();
    }
}
