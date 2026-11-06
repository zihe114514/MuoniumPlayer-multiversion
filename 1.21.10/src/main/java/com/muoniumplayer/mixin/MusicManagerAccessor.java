package com.muoniumplayer.mixin;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读 {@link MusicManager} 的私有字段 {@code currentMusic}。
 *
 * <p>只为一件事：本 mod 出声压掉原版音乐时（见 {@link MusicManagerDuckMixin}），
 * 把已经起播的那一首主动 stop 掉 —— tick 被短路后它的自然收尾也不会发生，
 * 不 stop 的话那首要一直响到自然结束。21.1 → 26.3 字段名与类型一致，五树共用这一个。</p>
 *
 * <p>实例方法形态（不能带参静态）：loom 的 mixin 重映射器按 getter/setter 形状
 * 校验 @Accessor —— {@code (MusicManager)→SoundInstance} 的带参静态方法会被
 * {@code is not getter or setter} 拒绝，remapJar 直接失败。</p>
 */
@Mixin(MusicManager.class)
public interface MusicManagerAccessor {

    @Accessor("currentMusic")
    SoundInstance muonium$getCurrentMusic();
}
