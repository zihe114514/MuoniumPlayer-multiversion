package com.muoniumplayer.core.rendering.font;

import io.github.humbleui.skija.TextBlob;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Render-thread LRU. Both entry count and text length bound native layout memory. */
final class TextBlobCache implements AutoCloseable {
    private final int maxEntries;
    private final int maxCharacters;
    private final LinkedHashMap<String, TextBlob> blobs = new LinkedHashMap<>(32, 0.75f, true);
    private int characters;

    TextBlobCache(int maxEntries, int maxCharacters) {
        this.maxEntries = maxEntries;
        this.maxCharacters = maxCharacters;
    }

    TextBlob get(String text) {
        return blobs.get(text);
    }

    /** Takes ownership of blob; every removal explicitly releases the native object. */
    void put(String text, TextBlob blob) {
        TextBlob previous = blobs.put(text, blob);
        if (previous == null) {
            characters += text.length();
        } else if (previous != blob) {
            previous.close();
        }
        Iterator<Map.Entry<String, TextBlob>> entries = blobs.entrySet().iterator();
        while (blobs.size() > maxEntries || characters > maxCharacters) {
            Map.Entry<String, TextBlob> oldest = entries.next();
            characters -= oldest.getKey().length();
            oldest.getValue().close();
            entries.remove();
        }
    }

    @Override
    public void close() {
        for (TextBlob blob : blobs.values()) {
            blob.close();
        }
        blobs.clear();
        characters = 0;
    }
}
