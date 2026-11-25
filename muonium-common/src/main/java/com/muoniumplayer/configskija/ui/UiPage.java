package com.muoniumplayer.configskija.ui;

public interface UiPage {
    void render(PageContext context);
    default boolean recording(){return false;}
    default boolean pointerDown(PageContext context,int button) { return false; }
    default void pointerUp(PageContext context,int button) {}
    default boolean wheel(PageContext context,double delta) { return false; }
    default boolean key(PageContext context,ConfigView.Key key,String typed,boolean control,boolean shift,boolean alt) { return false; }
    default void leave(PageContext context) {}
}
