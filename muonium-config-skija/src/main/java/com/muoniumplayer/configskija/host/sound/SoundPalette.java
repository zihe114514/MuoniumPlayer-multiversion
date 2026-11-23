package com.muoniumplayer.configskija.host.sound;

/** Sound IDs, pitches and gains transcribed from the original McUiSoundService. */
public final class SoundPalette {
    public static final String NAMESPACE = "muoniumconfigskija";
    public static final String PAD = NAMESPACE + ":ui.pad_loop";
    public static final java.util.List<String> MINECRAFT_AMBIENCE = java.util.List.of(
        NAMESPACE + ":ui.mc_ambient.trees", NAMESPACE + ":ui.mc_ambient.moog_1",
        NAMESPACE + ":ui.mc_ambient.moog_2", NAMESPACE + ":ui.mc_ambient.lullaby");
    private static final float[] SLIDER_PITCHES = {1.334840f, 1.414214f, 1.498307f, 1.587401f, 1.681793f, 1.781797f};
    private SoundPalette() {}

    public static SoundBackend.Clip clip(UiSoundEvent event, SoundTheme theme, float volume, int sliderIndex) {
        if (volume <= 0) return null;
        String id; float pitch = 1, gain = 1;
        if (event == UiSoundEvent.OPEN || event == UiSoundEvent.CLOSE) {
            id = NAMESPACE + (event == UiSoundEvent.OPEN ? ":ui.open" : ":ui.close");
        } else if (theme == SoundTheme.MODERN) {
            switch (event) {
                case CLICK, HUD_SELECT, HUD_RESIZE_END -> id = NAMESPACE + ":ui.click";
                case SLIDER_TICK -> id = NAMESPACE + ":ui.slider";
                case HUD_DRAG_START -> { id = NAMESPACE + ":ui.click"; pitch = .9f; }
                case HUD_DRAG_END -> { id = NAMESPACE + ":ui.click"; pitch = 1.1f; }
                default -> { return null; }
            }
        } else {
            switch (event) {
                case CLICK -> { id = "minecraft:ui.button.click"; gain = .5f; }
                case SLIDER_TICK -> { id = "minecraft:block.note_block.bass"; gain = .3f; pitch = SLIDER_PITCHES[Math.floorMod(sliderIndex, SLIDER_PITCHES.length)]; }
                case HUD_SELECT, HUD_DRAG_END, HUD_RESIZE_END -> { id = "minecraft:ui.button.click"; gain = .3f; }
                default -> { return null; }
            }
        }
        return new SoundBackend.Clip(id, pitch, Math.max(0, Math.min(1, gain * volume)));
    }
}
