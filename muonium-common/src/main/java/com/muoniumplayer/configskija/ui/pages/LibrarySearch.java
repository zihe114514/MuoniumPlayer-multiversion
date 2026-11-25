package com.muoniumplayer.configskija.ui.pages;

import com.muoniumplayer.configskija.model.SearchIndex;
import com.muoniumplayer.configskija.ui.PageContext;

/** Page field selection lives here; matching is owned by the shared search layer. */
final class LibrarySearch {
    private LibrarySearch(){}
    static boolean matches(PageContext c,String text,String query){return SearchIndex.matches(text,SearchIndex.trimQuery(query),(int)c.preferences().number("searchDistance",2));}
}
