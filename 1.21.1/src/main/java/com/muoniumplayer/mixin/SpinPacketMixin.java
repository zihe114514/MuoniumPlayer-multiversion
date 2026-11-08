package com.muoniumplayer.mixin;

import com.muoniumplayer.core.ncm.music.MusicSpin;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 「陀螺模式·发包·视角不动」的包层朝向替换。
 *
 * <p>{@code sendPosition} 里对 {@code getYRot()} 的每一处读取都被替换：活跃时返回
 * 共享层的旋转角，否则原样。这样改是刻意的——朝向变化检测（与 {@code yRotLast}
 * 比较）和 PosRot/Rot 包构造走的是同一个读取点，替换后检测自动看到旋转角的变化，
 * 每 tick 自然发出 Rot 包，站桩不发朝向包的问题不存在；{@code yRotLast} 的回写也
 * 拿到同一角度，下一 tick 的差值口径自洽。相机走的是 {@code yRotO} 插值缓存，
 * 全类只有 sendPosition 的检测处读 {@code yRotLast}（已逐树反汇编核对），视角不受影响。</p>
 *
 * <p>关闭或暂停超过活性窗口时原样返回：下一个差值包把服务端朝向自然收敛回真实
 * 相机，无跳变。1.20.1~26.x 七棵树的 {@code sendPosition} 形状已逐树反汇编确认
 * （getYRot 读取点 4~5 处、全部经由本替换点），本文件七树同文。</p>
 */
@Mixin(LocalPlayer.class)
abstract class SpinPacketMixin {

    @Redirect(method = "sendPosition", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;getYRot()F"))
    private float spin$packetYaw(LocalPlayer player) {
        return MusicSpin.packetYaw(player.getYRot());
    }
}
