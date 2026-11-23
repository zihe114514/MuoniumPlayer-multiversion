package com.muoniumplayer.configskija.ui.pages;

import com.muoniumplayer.configskija.ui.UiPage;

/** Entry points retained by the Shell for their complete navigation lifetime. */
public final class LibraryPages {
    private LibraryPages() {}
    public static LibraryMods.CardRenderer modCardRenderer() { return LibraryMods.cardRenderer(); }
    public static UiPage mods() { return new LibraryMods(); }
    public static UiPage profiles() { return new LibraryProfiles(); }
    public static UiPage keys() { return new KeysPage(); }
    public static UiPage credits() { return new CreditsPage(); }
}
