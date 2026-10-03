package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.ncm.music.dto.Album;
import com.muoniumplayer.core.ncm.music.dto.Music;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackPresentationTest {
    @Test void pendingTrackOwnsHighlightButNotAudio() {
        Music old = song(1, "old");
        Music next = song(2, "next");
        PlaybackPresentation snapshot = new PlaybackPresentation(old, next, 42L);
        assertSame(old, snapshot.audibleTrack);
        assertSame(next, snapshot.displayTrack);
        assertFalse(snapshot.isSelected(old));
        assertTrue(snapshot.isSelected(next));
        assertTrue(snapshot.preparing);
        assertEquals(42L, snapshot.requestNanos);
    }

    @Test void stableIdentityDoesNotFlickerWhenMetadataChanges() {
        PlaybackPresentation snapshot = new PlaybackPresentation(null, song(2, "first title"), 42L);
        assertTrue(snapshot.isSelected(song(2, "updated title")));
        assertFalse(snapshot.isSelected(song(3, "first title")));
        assertFalse(snapshot.isSelected(null));
    }

    @Test void emptyAndAudibleOnlyStatesAreSafe() {
        PlaybackPresentation empty = new PlaybackPresentation(null, null, 42L);
        assertFalse(empty.preparing);
        assertEquals("", empty.displayKey());
        assertFalse(empty.isSelected(song(1, "a")));
        Music audible = song(1, "a");
        PlaybackPresentation playing = new PlaybackPresentation(audible, null, 42L);
        assertSame(audible, playing.displayTrack);
        assertEquals(0L, playing.requestNanos);
        assertTrue(playing.isSelected(audible));
    }

    private static Music song(long id, String title) {
        return new Music(title, title, "", id, Collections.emptyList(), Collections.emptyList(),
                new Album(id, "album", "", Collections.emptyList()),
                180_000L, 0L, 0L, Collections.emptyList());
    }
}
