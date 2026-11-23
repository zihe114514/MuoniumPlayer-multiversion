package com.muoniumplayer.configskija.sound;

import com.muoniumplayer.configskija.host.sound.*;
import com.muoniumplayer.configskija.model.UiPreferences;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Headless source-parity scenarios. Run with Java assertions enabled; no Minecraft or audio device. */
public final class SoundBehaviorTest {
    private static int checks;
    private static void check(boolean pass, String reason) { checks++; if (!pass) throw new AssertionError(reason); }
    private static void near(double actual, double expected, String reason) { check(Math.abs(actual - expected) < .00001, reason + ": " + actual + " != " + expected); }
    private static final class Voice implements SoundBackend.Ambience {
        final String id; final boolean loop; final FadeEnvelope fade; boolean active = true, stopped;
        int resumes, stops;
        Voice(String id, float target, boolean loop) { this.id = id; this.loop = loop; fade = new FadeEnvelope(target); }
        public boolean active() { return active && !stopped(); }
        public boolean stopped() { return stopped || fade.stopped(); }
        public boolean fadingOut() { return fade.fadingOut(); }
        public void fadeOut() { fade.fadeOut(); }
        public void resume(float volume) { resumes++; fade.resume(volume); }
        public void stop() { stops++; stopped = true; active = false; }
    }
    private static final class Backend implements SoundBackend {
        final List<Clip> clips = new ArrayList<>(); final List<Voice> voices = new ArrayList<>();
        public void play(Clip clip) { clips.add(clip); }
        public Ambience ambience(String id, float target, boolean loop) { Voice voice = new Voice(id, target, loop); voices.add(voice); return voice; }
        Voice lastVoice() { return voices.get(voices.size() - 1); }
        Clip lastClip() { return clips.get(clips.size() - 1); }
    }
    private static final class Fixture {
        final UiPreferences prefs = new UiPreferences(Map.of(), () -> {}); final Backend backend = new Backend();
        final AtomicLong clock = new AtomicLong(); final SoundSession sound = new SoundSession(prefs, backend, new Random(42), clock::get);
    }
    private static void palette() {
        for (SoundTheme theme : SoundTheme.values()) {
            check(SoundPalette.clip(UiSoundEvent.OPEN, theme, 1, 0).id().endsWith(":ui.open"), "both themes use source open sample");
            check(SoundPalette.clip(UiSoundEvent.CLOSE, theme, 1, 0).id().endsWith(":ui.close"), "both themes use source close sample");
            check(SoundPalette.clip(UiSoundEvent.HUD_RESIZE_START, theme, 1, 0) == null, "resize start is deliberately silent");
        }
        near(SoundPalette.clip(UiSoundEvent.HUD_DRAG_START, SoundTheme.MODERN, 1, 0).pitch(), .9, "modern drag start pitch");
        near(SoundPalette.clip(UiSoundEvent.HUD_DRAG_END, SoundTheme.MODERN, 1, 0).pitch(), 1.1, "modern drag end pitch");
        check(SoundPalette.clip(UiSoundEvent.HUD_DRAG_START, SoundTheme.MINECRAFT, 1, 0) == null, "Minecraft drag start is silent");
        for (UiSoundEvent event : List.of(UiSoundEvent.HUD_SELECT, UiSoundEvent.HUD_DRAG_END, UiSoundEvent.HUD_RESIZE_END)) {
            near(SoundPalette.clip(event, SoundTheme.MINECRAFT, .5f, 0).volume(), .15, "Minecraft HUD gain");
        }
        Fixture fixture = new Fixture(); fixture.sound.open("minecraft-dark"); fixture.backend.clips.clear();
        float[] pitches = {1.334840f, 1.414214f, 1.498307f, 1.587401f, 1.681793f, 1.781797f};
        for (int i = 0; i < 14; i++) { fixture.sound.play("slider_tick"); near(fixture.backend.lastClip().pitch(), pitches[i % 6], "six pitch cycle"); near(fixture.backend.lastClip().volume(), .3, "bass gain"); }
        fixture.prefs.set("enableUISliderSounds", false); int before = fixture.backend.clips.size(); fixture.sound.play("slider_tick");
        check(fixture.backend.clips.size() == before, "slider switch gates playback");
        fixture.prefs.set("enableUISliderSounds", true); fixture.sound.play("slider"); near(fixture.backend.lastClip().pitch(), pitches[14 % 6], "muted ticks do not consume pitches");
        check(!fixture.sound.play("unknown"), "unknown event is explicit");
        check(UiSoundEvent.parse("hud.drag.end") == UiSoundEvent.HUD_DRAG_END, "source event alias");
        fixture.sound.play("navigate"); check(fixture.backend.lastClip().id().equals("minecraft:ui.button.click"), "navigation is a source click");
    }
    private static void preferences() {
        Map<String, UiSoundEvent> flags = Map.of("enableUIMenuSounds", UiSoundEvent.OPEN, "enableUIClickSounds", UiSoundEvent.CLICK,
            "enableUISliderSounds", UiSoundEvent.SLIDER_TICK, "enableHudEditorSounds", UiSoundEvent.HUD_SELECT);
        for (Map.Entry<String, UiSoundEvent> entry : flags.entrySet()) {
            Fixture fixture = new Fixture(); fixture.prefs.set(entry.getKey(), false); fixture.sound.play(entry.getValue());
            check(fixture.backend.clips.isEmpty(), entry.getKey() + " is read by its original name");
            fixture.prefs.set(entry.getKey(), true); fixture.sound.play(entry.getValue()); check(fixture.backend.clips.size() == 1, "switch reenables its event");
        }
        Fixture fixture = new Fixture(); fixture.prefs.set("enableUISounds", false); fixture.sound.open("polyglass-dark");
        check(fixture.backend.clips.isEmpty(), "master sound switch"); check(fixture.backend.voices.size() == 1, "master does not disable independent ambience");
        fixture.prefs.set("enableUISounds", true); fixture.prefs.set("uiSoundVolume", .25); fixture.sound.play("click"); near(fixture.backend.lastClip().volume(), .25, "source sound volume");
        fixture.prefs.set("uiSoundVolume", 5); fixture.sound.play("click"); near(fixture.backend.lastClip().volume(), 1, "upper volume clamp");
        int before = fixture.backend.clips.size(); fixture.prefs.set("uiSoundVolume", -2); fixture.sound.play("click"); check(fixture.backend.clips.size() == before, "lower volume clamp");
        Voice voice = fixture.backend.lastVoice(); fixture.prefs.set("uiAmbienceVolume", .4); fixture.sound.tick(); near(voice.fade.target(), .2, "modern ambience half gain");
        fixture.prefs.set("enableUIMusicDucking", false); fixture.sound.tick();
        check(fixture.backend.voices.size() == 1, "unused ducking refresh reuses the active voice"); near(voice.fade.target(), .2, "ducking does not change any gain");
        fixture.prefs.set("enableUIAmbience", false); fixture.sound.tick(); check(voice.fadingOut(), "ambience switch initiates a fade");
        fixture.prefs.set("enableUIAmbience", true); fixture.sound.tick(); check(!voice.fadingOut(), "reenabling resumes the source voice");
        fixture.prefs.set("uiAmbienceVolume", 0); fixture.sound.tick(); near(voice.fade.volume(), 0, "zero ambience gain stays valid");
    }
    private static void lifecycle() {
        Fixture fixture = new Fixture(); fixture.sound.open("PolyGlass Dark"); fixture.sound.open("PolyGlass Dark");
        check(fixture.backend.clips.size() == 1 && fixture.backend.voices.size() == 1, "open idempotence");
        Voice modern = fixture.backend.lastVoice(); check(modern.loop && modern.id.endsWith(":ui.pad_loop"), "modern native loop");
        fixture.sound.close(); fixture.sound.close(); check(fixture.backend.clips.size() == 2 && modern.fadingOut(), "close once starts the native fade");
        modern.fade.tick(); fixture.sound.open("PolyGlass Light"); check(fixture.backend.voices.size() == 1 && !modern.fadingOut(), "reopen cancels fade without overlap"); near(modern.fade.volume(), .5, "reopen restores full target");
        fixture.sound.themeChanged("PolyGlass Dark"); check(fixture.backend.lastClip().id().endsWith(":ui.click"), "same sound family activation clicks");
        fixture.sound.themeChanged("Minecraft Dark"); check(modern.stopped && modern.stops == 1, "theme change retires previous theme");
        Voice minecraft = fixture.backend.lastVoice(); check(!minecraft.loop, "Minecraft ambience is one-shot"); near(minecraft.fade.target(), .08, "Minecraft ambience gain");
        check(fixture.backend.lastClip().id().endsWith(":ui.open"), "new family activation opens");
        minecraft.active = false; fixture.clock.set(499_999_999L); fixture.sound.tick(); check(fixture.backend.voices.size() == 2, "no early next track");
        fixture.clock.set(500_000_000L); fixture.sound.tick(); check(fixture.backend.voices.size() == 3, "source half-second completion check");
        check(!minecraft.id.equals(fixture.backend.lastVoice().id), "next track does not repeat");
        Voice ending = fixture.backend.lastVoice(); fixture.sound.close(); fixture.clock.set(2_000_000_000L); ending.active = false; fixture.sound.tick();
        check(fixture.backend.voices.size() == 3, "closed sessions never restart a song");
        int fadeTicks = 0; while (!ending.stopped() && fadeTicks++ < 82) ending.fade.tick();
        check(ending.stopped() && fadeTicks >= 80 && fadeTicks <= 81, "fade completes on native ticks without UI ticks");
        fixture.sound.open("Minecraft Light"); check(fixture.backend.voices.size() == 4, "fresh voice after fade completion");
        int clipsBeforeRemoval = fixture.backend.clips.size(); fixture.sound.releaseAmbience();
        check(fixture.backend.clips.size() == clipsBeforeRemoval, "removed handoff releases without an extra close effect");
        fixture.sound.acquireAmbience("Minecraft Light"); check(fixture.backend.clips.size() == clipsBeforeRemoval, "scene restore acquire does not synthesize open");
        check(!fixture.backend.lastVoice().fadingOut(), "acquire-only resumes the original fade");
        fixture.sound.stopImmediately(); check(fixture.backend.lastVoice().stopped, "shutdown retires retained native handle");
    }
    private static void shuffleAndFade() {
        AmbienceShuffle shuffle = new AmbienceShuffle(new Random(9)); String last = null;
        for (int round = 0; round < 200; round++) {
            Set<String> bag = new HashSet<>();
            for (int i = 0; i < 4; i++) { String next = shuffle.next(SoundTheme.MINECRAFT); check(!next.equals(last), "no adjacent repeats across bag boundaries"); bag.add(next); last = next; }
            check(bag.size() == 4, "all four tracks occur before a repeat");
            check(shuffle.next(SoundTheme.MODERN).equals(SoundPalette.PAD), "modern loop does not consume the Minecraft bag");
        }
        FadeEnvelope fade = new FadeEnvelope(.5f); fade.fadeOut(); for (int i = 0; i < 40; i++) fade.tick(); near(fade.volume(), .25, "linear midpoint after two nominal seconds");
        fade.resume(.1f); check(!fade.fadingOut(), "cancel fade"); near(fade.volume(), .1, "volume refresh is immediate");
        FadeEnvelope zero = new FadeEnvelope(0); zero.fadeOut(); zero.tick(); check(zero.stopped(), "zero-volume fade retires on the first native tick");
    }
    public static void main(String[] args) {
        palette(); preferences(); lifecycle(); shuffleAndFade();
        System.out.println("SoundBehaviorTest PASS (" + checks + " checks)");
    }
}
