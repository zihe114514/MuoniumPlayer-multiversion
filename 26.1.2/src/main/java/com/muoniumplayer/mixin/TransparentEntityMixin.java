package com.muoniumplayer.mixin;

import com.muoniumplayer.core.ncm.music.TransparentMode;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 「通透模式」的实体压缩：把果冻矩阵包住原版的实体渲染括号。
 *
 * <p>26.x 的渲染走「extract → submit」两段式，注入点与 1.21.x 的
 * {@code render(Entity, ...)} 不同形：extract 阶段按实体算一次缩放并存进
 * {@link WeakHashMap}（extract 每帧每实体一次，submit 可能被光影等多次重放），
 * submit 阶段在原版首次 {@code translate} 落地后压矩阵、在火焰动画读取前还原——
 * 火焰、阴影、碰撞箱调试框都画在括号外，不受矩阵污染。缩放只动本机画面，
 * 不碰碰撞箱与服务端状态。</p>
 */
@Mixin(EntityRenderDispatcher.class)
abstract class TransparentEntityMixin {

    @Unique
    private static final Map<EntityRenderState, float[]> TRANSMODE_SCALES =
            Collections.synchronizedMap(new WeakHashMap<>());

    @Inject(method = "extractEntity", at = @At("RETURN"))
    private void transmode$extract(net.minecraft.world.entity.Entity entity, float tick,
                                   CallbackInfoReturnable<EntityRenderState> cir) {
        EntityRenderState state = cir.getReturnValue();
        if (state == null) return;
        if (!TransparentMode.isEnabled()) {
            // 关着也要清残留：上个节拍留下的映射不该在下一帧复活。
            TRANSMODE_SCALES.remove(state);
            return;
        }
        // WeakHashMap 只按 state 判等：换歌/停播后同一 state 的新值会覆盖旧值。
        TRANSMODE_SCALES.put(state, new float[] {
                TransparentMode.widthScale(), TransparentMode.heightScale()});
    }

    @Inject(method = "submit", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V", ordinal = 0, shift = At.Shift.AFTER))
    private void transmode$begin(EntityRenderState state, CameraRenderState camera, double x, double y, double z,
                                 PoseStack pose, SubmitNodeCollector collector, CallbackInfo ci) {
        float[] scales = TRANSMODE_SCALES.get(state);
        if (scales == null) return;
        pose.pushPose();
        pose.scale(scales[0], scales[1], 1f);
    }

    @Inject(method = "submit", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/entity/state/EntityRenderState;displayFireAnimation:Z", ordinal = 0))
    private void transmode$end(EntityRenderState state, CameraRenderState camera, double x, double y, double z,
                               PoseStack pose, SubmitNodeCollector collector, CallbackInfo ci) {
        if (TRANSMODE_SCALES.containsKey(state)) pose.popPose();
    }
}
