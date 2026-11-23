package com.muoniumplayer.configskija.host.sound;

/** Only the lane implementation talks to Minecraft's existing SoundManager. All calls use the client thread. */
public interface SoundBackend {
    record Clip(String id, float pitch, float volume) {}

    interface Ambience {
        boolean active();
        boolean stopped();
        boolean fadingOut();
        void fadeOut();
        void resume(float targetVolume);
        void stop();
    }

    void play(Clip clip);
    Ambience ambience(String eventId, float targetVolume, boolean nativeLoop);
}
