package com.muoniumplayer.configskija.host.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/** 26.3: both resolved loader JARs were checked separately; no loader symbols or extra audio engine. */
public final class McSoundBackend implements SoundBackend {
    private final Minecraft minecraft = Minecraft.getInstance();

    private void clientThread() {
        if (!minecraft.isSameThread()) throw new IllegalStateException("UI sounds require the Minecraft client thread");
    }
    private static SoundEvent event(String id) {
        if (id.equals("minecraft:ui.button.click")) return SoundEvents.UI_BUTTON_CLICK.value();
        if (id.equals("minecraft:block.note_block.bass")) return SoundEvents.NOTE_BLOCK_BASS.value();
        int colon = id.indexOf(':');
        if (colon <= 0) throw new IllegalArgumentException("Namespaced UI sound required: " + id);
        String namespace = id.substring(0, colon), name = id.substring(colon + 1);
        return SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(namespace, name));
    }
    @Override public void play(Clip clip) {
        clientThread();
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(event(clip.id()), clip.pitch(), clip.volume()));
    }
    @Override public Ambience ambience(String eventId, float targetVolume, boolean nativeLoop) {
        clientThread();
        LoopSound sound = new LoopSound(event(eventId), targetVolume, nativeLoop);
        minecraft.getSoundManager().play(sound);
        return new Voice(sound);
    }
    private final class Voice implements Ambience {
        private final LoopSound sound;
        private Voice(LoopSound sound) { this.sound = sound; }
        @Override public boolean active() { clientThread(); return minecraft.getSoundManager().isActive(sound); }
        @Override public boolean stopped() { clientThread(); return sound.isStopped(); }
        @Override public boolean fadingOut() { clientThread(); return sound.envelope.fadingOut(); }
        @Override public void fadeOut() { clientThread(); sound.envelope.fadeOut(); }
        @Override public void resume(float targetVolume) { clientThread(); sound.resume(targetVolume); }
        @Override public void stop() { clientThread(); sound.finish(); minecraft.getSoundManager().stop(sound); }
    }
    private static final class LoopSound extends AbstractTickableSoundInstance {
        private final FadeEnvelope envelope;
        private LoopSound(SoundEvent event, float targetVolume, boolean nativeLoop) {
            super(event, SoundSource.UI, RandomSource.create());
            envelope = new FadeEnvelope(targetVolume);
            looping = nativeLoop;
            delay = 0;
            attenuation = SoundInstance.Attenuation.NONE;
            relative = true;
            volume = envelope.volume();
            pitch = 1f;
        }
        private void resume(float targetVolume) { envelope.resume(targetVolume); volume = envelope.volume(); }
        private void finish() { stop(); }
        @Override public void tick() {
            envelope.tick();
            volume = envelope.volume();
            if (envelope.stopped()) stop();
        }
    }
}
