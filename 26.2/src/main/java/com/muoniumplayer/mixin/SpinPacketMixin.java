package com.muoniumplayer.mixin;

import com.muoniumplayer.core.ncm.music.MusicSpin;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 「陀螺模式」的包层朝向替换：yaw（身体旋转 + 发包档的头乱晃叠加）与 pitch（头乱晃
 * 的点头/压低）两条通道都在 {@code sendPosition} 的读取点上替换。
 *
 * <p>yaw：{@code getYRot()} 的每一处读取被替换——朝向变化检测（对 {@code yRotLast}
 * 比较）和 PosRot/Rot 包构造走同一读取点，替换后检测自动看到旋转角的变化，每 tick
 * 自然发出 Rot 包；{@code yRotLast} 回写拿到同一角度，下一 tick 差值口径自洽。相机
 * 走 {@code yRotO} 插值缓存，全类只有 sendPosition 的检测处读 {@code yRotLast}
 * （已逐树反汇编核对），视角不受影响。</p>
 *
 * <p>pitch：{@code getXRot()} 同理整组替换——头乱晃发包开着时把点头/压低叠在真实
 * 俯仰上（加法语义，相机照常），服务端从俯仰推导你的抬头可见于他人并自行钳 ±90。
 * 协议没有「头相对身体」的独立通道，其他人看到的是整个人跟着甩，这是唯一来源。</p>
 *
 * <p>关闭或暂停超过活性窗口时原样返回：下一个差值包把服务端朝向自然收敛回真实
 * 朝向，无跳变。1.20.1~26.x 七棵树的 {@code sendPosition} 形状已逐树反汇编确认
 * （getYRot/getXRot 读取点 8~10 处），本文件七树同文。</p>
 */
@Mixin(LocalPlayer.class)
abstract class SpinPacketMixin {

    @Redirect(method = "sendPosition", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;getYRot()F"))
    private float spin$packetYaw(LocalPlayer player) {
        return MusicSpin.packetYaw(player.getYRot());
    }

    @Redirect(method = "sendPosition", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;getXRot()F"))
    private float spin$packetPitch(LocalPlayer player) {
        return MusicSpin.packetPitch(player.getXRot());
    }
}
