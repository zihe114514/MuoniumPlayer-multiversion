package com.muoniumplayer.mixin;

import com.muoniumplayer.compat.mc.WorldLyricsHudDraw;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 3D 世界歌词的关卡投影捕获 —— 上一帧口径问题的正解。
 *
 * <p>{@code CameraRenderState.projectionMatrix} 是 extract 透视原值，而
 * {@code GameRenderer.renderLevel} 画世界前把 bobHurt/bobView（视角摇晃）
 * 和 portal 屏效 mul 进一份拷贝，再经 {@code levelProjectionMatrixBuffer
 * .getBuffer(Matrix4f)} 上传 UBO —— 歌词快照若取原值，移动时与方块世界差
 * 一个 bob 增量，观感为歌词相对世界微摆。</p>
 *
 * <p>挂在 {@code getBuffer(Matrix4f)} 的 HEAD：调用方传进来的就是即将上传
 * UBO 的那颗合成矩阵。{@code GameRenderer} 上同名缓冲还有 hud3d 一个实例、
 * Iris 影蚀/手部各有自建实例 —— 用 {@code GameRendererAccessor} 惰性取
 * level 实例做身份比对，非本实例的调用全过滤。</p>
 */
@Mixin(ProjectionMatrixBuffer.class)
public class ProjectionMatrixBufferMixin {

    @Inject(
        method = "getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;",
        at = @At("HEAD")
    )
    private void muonium$captureLevelProjection(org.joml.Matrix4f matrix,
                                                CallbackInfoReturnable<?> cir) {
        WorldLyricsHudDraw.noteLevelProjection(this, matrix);
    }
}
