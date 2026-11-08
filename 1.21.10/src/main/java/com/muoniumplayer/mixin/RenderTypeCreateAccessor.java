package com.muoniumplayer.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 3D 歌词晕格通道的 RenderType 构造辅助 —— 四参 {@code RenderType.create} 在
 * 1.21.10+ 收窄为包私有（1.21.1 世代是 public），而加色光晕需要自定义
 * RenderPipeline（beacon_beam 着色器 + BlendFunction.LIGHTNING），原版工厂
 * 没有这个组合的现成入口（beaconBeam(rl,true) 自带的是 TRANSLUCENT alpha 混合，
 * 不是加色 —— 晕格用它会在亮背景上消失）。
 *
 * <p><b>返回值必须是 {@code CompositeRenderType}，不能写成父类 {@code RenderType}：</b>
 * Invoker 按 {@code MemberInfo} 全描述符匹配目标方法（返回值也参与比较），而 1.21.10
 * 的 {@code create} 实测返回 {@code LRenderType$CompositeRenderType;}。写成 {@code RenderType}
 * 时找不到候选，mixin apply 阶段直接抛 {@code InvalidAccessorException} ——
 * 因为 {@code RenderType} 类一加载就校验，与晕格用没用上无关，整局启动即崩。
 * 调用方赋给 {@code RenderType} 是向上转型，不受影响。</p>
 */
@Mixin(RenderType.class)
public interface RenderTypeCreateAccessor {

    @Invoker("create")
    static RenderType.CompositeRenderType muonium$create(String name, int bufferSize, RenderPipeline pipeline,
                                                         RenderType.CompositeState state) {
        throw new AssertionError();
    }
}
