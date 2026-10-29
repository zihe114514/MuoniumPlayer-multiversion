package com.muoniumplayer.mixin;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 3D 世界歌词的关卡投影捕获辅助 —— 读 {@code renderLevel} 实际上传 UBO
 * 用的那个 {@code ProjectionMatrixBuffer} 实例（与 hud3d 的实例区分靠
 * 对象身份，见 {@code ProjectionMatrixBufferMixin}）。
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {

    @Accessor("levelProjectionMatrixBuffer")
    ProjectionMatrixBuffer muonium$levelProjectionMatrixBuffer();
}
