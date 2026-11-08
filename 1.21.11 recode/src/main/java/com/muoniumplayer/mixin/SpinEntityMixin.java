package com.muoniumplayer.mixin;

import com.muoniumplayer.core.ncm.music.MusicSpin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 「陀螺模式」的实体旋转：extract 阶段把节拍步进角加进渲染朝向字段。
 *
 * <p>1.21.2 起渲染走「extract → submit」两段式，模型朝向完全由
 * {@link LivingEntityRenderState} 的 {@code bodyRot}/{@code yRot}/{@code xRot}
 * 驱动（extract 每帧从实体重新拷贝）。在 extract 的 RETURN 处给 {@code bodyRot}
 * 加一个差值，身体和头一起转、头身相对关系不变，而名牌挂点是纯 Y 偏移（绕 Y
 * 旋转不动它）、名牌朝向由 {@code SubmitNodeCollection.submitNameTag} 里另加的
 * 相机四元数决定——但那个四元数复合在 dispatcher 括号位姿之后，与 bodyRot 无关，
 * 所以名牌既不跟着转也不换位置。火焰（相机四元数另算）、阴影（extract 期定位）、
 * 牵引绳（state 列表）同样不吃这个字段。</p>
 *
 * <p>只改渲染快照，不碰实体本体与服务端状态；extract 每帧每实体一次，submit 可能
 * 被光影重放多次也不会重复累计——角度在字段里，不在括号里。发包两档的朝向替换
 * 见 {@link SpinPacketMixin} 与 {@code MuoniumPlayerClient} 的 tick 接线。</p>
 */
@Mixin(EntityRenderDispatcher.class)
abstract class SpinEntityMixin {

    @Inject(method = "extractEntity", at = @At("RETURN"))
    private void spin$extract(Entity entity, float tick, CallbackInfoReturnable<EntityRenderState> cir) {
        if (!(cir.getReturnValue() instanceof LivingEntityRenderState state)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        boolean isSelf = entity == mc.player;
        float angle = MusicSpin.entityAngleDegrees(isSelf, entity instanceof Player, true,
                entity.getYRot());
        if (angle == 0f) {
            return;
        }
        // yRot 存的是「头相对身体的差」，加在 bodyRot 上头会跟着身体一起转；
        // 差值不变，头身关系保持原样。
        state.bodyRot += angle;
    }
}
