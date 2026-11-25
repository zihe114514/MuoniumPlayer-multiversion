package com.muoniumplayer.configskija.host.sound;

/** Owned and ticked by the native tickable sound, including after the screen closes. */
public final class FadeEnvelope {
    public static final float FADE_TICKS = 80f;
    private float target, volume;
    private boolean fading, stopped;
    public FadeEnvelope(float target) { resume(target); }
    public float volume() { return volume; }
    public float target() { return target; }
    public boolean fadingOut() { return fading; }
    public boolean stopped() { return stopped; }
    public void fadeOut() { fading = true; }
    public void resume(float target) {
        this.target = Math.max(0, Math.min(1, target));
        volume = this.target; fading = false;
    }
    public void tick() {
        if (!fading || stopped) return;
        volume -= target / FADE_TICKS;
        if (volume <= 0) { volume = 0; stopped = true; }
    }
}
