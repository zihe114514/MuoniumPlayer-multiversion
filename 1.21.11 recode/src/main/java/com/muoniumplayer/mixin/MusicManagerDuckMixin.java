package com.muoniumplayer.mixin;

import com.muoniumplayer.core.ncm.music.VanillaMusicDucker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Unique;

/**
 * 本 mod 出声时压掉原版游戏音乐 —— 拦 {@link MusicManager#tick()}。
 *
 * <p>为什么拦 tick 而不是压 music 音量类别或暂停整个 SoundEngine，取舍见
 * {@link VanillaMusicDucker} 的类注释：短路径只让原版「音乐」这一件事停下来，
 * 音效、环境音、用户的音量设置都不碰。</p>
 *
 * <p>短路期间原版已起播的那首要主动 stop 一次：tick 被我们跳过后，
 * {@code isActive(currentMusic)} 的自然收尾也不会发生，不 stop 的话那首要一直响到
 * 自然结束。只在压住的第一拍 stop（之后每 tick 安静返回，不重复 stop）。唱片机音乐
 * 不受影响 —— 它不挂在 MusicManager 上。</p>
 *
 * <p>本 mod 停止后 {@code active()} 翻 false，tick 原样恢复，倒计时从头走 ——
 * 原版音乐自己回来，无需任何「恢复」动作。</p>
 */
@Mixin(MusicManager.class)
public abstract class MusicManagerDuckMixin {

    /** 压住期间已经 stop 过当前曲目（每轮压住只做一次）。 */
    @Unique
    private boolean muonium$stoppedCurrentMusic;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void muonium$duckVanillaMusic(CallbackInfo ci) {
        if (!VanillaMusicDucker.active()) {
            // 放行的第一拍把标记复位：下一轮压住时还要再 stop 一次。
            this.muonium$stoppedCurrentMusic = false;
            return;
        }

        if (!this.muonium$stoppedCurrentMusic) {
            this.muonium$stoppedCurrentMusic = true;
            try {
                MusicManager self = (MusicManager) (Object) this;
                SoundInstance current = ((MusicManagerAccessor) (Object) self).muonium$getCurrentMusic();
                if (current != null) {
                    Minecraft.getInstance().getSoundManager().stop(current);
                }
            } catch (Throwable ignored) {
                // stop 失败只是「这首继续响完」，短路本身已经挡住了之后的每一首。
            }
        }

        ci.cancel();
    }
}
