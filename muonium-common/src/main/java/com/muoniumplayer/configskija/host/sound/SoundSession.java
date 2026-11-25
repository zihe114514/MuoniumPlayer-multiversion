package com.muoniumplayer.configskija.host.sound;

import com.muoniumplayer.configskija.model.UiPreferences;
import java.util.Objects;
import java.util.Random;
import java.util.function.LongSupplier;

/**
 * One session is retained across menu/HUD screen changes and reopening during a fade.
 * Call open/tick/close/themeChanged/play on Minecraft's client thread. The sound engine
 * owns the fade ticks after close; the session never starts a worker or owns native audio.
 */
public final class SoundSession implements AutoCloseable {
    private static final long CHECK_NANOS = 500_000_000L;
    private final UiPreferences preferences;
    private final SoundBackend backend;
    private final AmbienceShuffle shuffle;
    private final LongSupplier clock;
    private SoundTheme theme = SoundTheme.MODERN;
    private SoundTheme ambienceTheme;
    private SoundBackend.Ambience ambience;
    private boolean opened;
    private int sliderIndex;
    private long nextCheck;
    private AmbientSettings settings;

    private record AmbientSettings(boolean enabled, float volume, boolean musicDucking) {}

    public SoundSession(UiPreferences preferences, SoundBackend backend) {
        this(preferences, backend, new Random(), System::nanoTime);
    }
    public SoundSession(UiPreferences preferences, SoundBackend backend, Random random, LongSupplier clock) {
        this.preferences = Objects.requireNonNull(preferences);
        this.backend = Objects.requireNonNull(backend);
        this.shuffle = new AmbienceShuffle(random);
        this.clock = Objects.requireNonNull(clock);
    }
    private String activeTheme() { return preferences.get("activeTheme", preferences.get("theme", "PolyGlass Dark")); }
    private float volume(String key) { return (float)Math.max(0, Math.min(1, preferences.number(key, 1))); }
    private AmbientSettings ambientSettings() {
        return new AmbientSettings(preferences.flag("enableUIAmbience", true), volume("uiAmbienceVolume"),
            preferences.flag("enableUIMusicDucking", true));
    }
    public boolean opened() { return opened; }
    public SoundTheme theme() { return theme; }
    public void open() { open(activeTheme()); }
    public void open(String activeTheme) {
        if (opened) return;
        theme = SoundTheme.of(activeTheme); opened = true;
        play(UiSoundEvent.OPEN);
        refreshAmbience();
    }
    @Override public void close() {
        if (!opened) return;
        play(UiSoundEvent.CLOSE);
        releaseAmbience();
    }
    /** Source removed()/scene handoff path: release ambience without synthesizing a CLOSE sound. */
    public void releaseAmbience() {
        if (!opened) return;
        opened = false;
        if (ambience != null && !ambience.stopped()) ambience.fadeOut();
    }
    /** Source acquire-only path for restoring a scene whose menu is already logically open. */
    public void acquireAmbience() { acquireAmbience(activeTheme()); }
    public void acquireAmbience(String activeTheme) {
        if (opened) return;
        theme = SoundTheme.of(activeTheme); opened = true; refreshAmbience();
    }
    /** Called on a real theme activation; a same-family change is the source CLICK path. */
    public void themeChanged(String activeTheme) {
        SoundTheme next = SoundTheme.of(activeTheme);
        if (theme == next) { play(UiSoundEvent.CLICK); return; }
        theme = next;
        play(UiSoundEvent.OPEN);
        if (opened) {
            if (ambience != null && !ambience.stopped()) ambience.fadeOut();
            refreshAmbience();
        }
    }
    public void themeChanged() { themeChanged(activeTheme()); }
    /** Handles current PageContext strings as well as the nine original enum names. */
    public boolean play(String event) {
        if ("theme_changed".equalsIgnoreCase(event)) { themeChanged(); return true; }
        UiSoundEvent parsed = UiSoundEvent.parse(event);
        if (parsed == null) return false;
        play(parsed); return true;
    }
    public void play(UiSoundEvent event) {
        Objects.requireNonNull(event);
        if (!preferences.flag("enableUISounds", true) || !enabled(event)) return;
        float volume = volume("uiSoundVolume");
        if (volume <= 0) return;
        SoundBackend.Clip clip = SoundPalette.clip(event, theme, volume, sliderIndex);
        if (clip == null) return;
        if (theme == SoundTheme.MINECRAFT && event == UiSoundEvent.SLIDER_TICK) sliderIndex++;
        backend.play(clip);
    }
    private boolean enabled(UiSoundEvent event) {
        String key = switch (event) {
            case OPEN, CLOSE -> "enableUIMenuSounds";
            case CLICK -> "enableUIClickSounds";
            case SLIDER_TICK -> "enableUISliderSounds";
            default -> "enableHudEditorSounds";
        };
        return preferences.flag(key, true);
    }
    /** Preferences can refresh explicitly, or tick detects the three original callback keys. */
    public void refreshAmbience() {
        if (!opened) return;
        settings = ambientSettings();
        if (!settings.enabled()) {
            if (ambience != null && !ambience.stopped()) ambience.fadeOut();
            return;
        }
        startAmbience();
    }
    private void startAmbience() {
        float target = theme.ambienceVolume(settings.volume());
        if (ambience != null && !ambience.stopped() && ambienceTheme == theme && ambience.active()) {
            ambience.resume(target);
        } else {
            if (ambience != null && !ambience.stopped()) ambience.stop();
            ambience = backend.ambience(shuffle.next(theme), target, true);
            ambienceTheme = theme;
        }
        nextCheck = clock.getAsLong() + CHECK_NANOS;
        // The original enableUIMusicDucking flag refreshes ambience but never changes music gain.
    }
    /** Poll at the source's 500 ms cadence while open; native sound ticks do the four-second fade. */
    public void tick() {
        if (!opened) return;
        AmbientSettings current = ambientSettings();
        if (!current.equals(settings)) refreshAmbience();
        if (settings == null || !settings.enabled() || ambience == null || ambience.stopped() || ambience.fadingOut()) return;
        long now = clock.getAsLong();
        if (now - nextCheck < 0) return;
        nextCheck = now + CHECK_NANOS;
        if (!ambience.active()) startAmbience();
    }
    /** Shutdown only. Ordinary UI close must call close() to retain the audible fade. */
    public void stopImmediately() {
        opened = false;
        if (ambience != null) { ambience.stop(); ambience = null; }
    }
}
