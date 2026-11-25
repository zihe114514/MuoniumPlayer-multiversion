package com.muoniumplayer.configskija.ui;

import com.muoniumplayer.configskija.model.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Shared page integration contract. Coordinates are original Shell dp (HUD pages use layout pixels). */
public interface PageContext {
    Painter painter();
    Theme theme();
    int accent();
    ConfigHost host();
    UiPreferences preferences();
    String query();
    Navigation.Route route();
    double mouseX();
    double mouseY();
    double width();
    double height();
    double time();
    double delta();
    boolean control();
    boolean shift();
    boolean alt();
    int nativeKeyCode();
    boolean textFocused();
    Rect content();
    void content(Rect bounds);
    double scroll();
    void scroll(double value);
    void scrollRange(double maximum);
    void hit(String id,Rect bounds,Runnable action,String tooltip);
    void contentHit(String id,Rect bounds,Runnable action,String tooltip);
    void tooltip(String id,Rect bounds,String text);
    void startDrag(BiConsumer<Double,Double> update,Runnable finish);
    void navigate(Navigation.Route route);
    void toast(String message);
    void sound(String event);
    void edit( ConfigHost.Option option,Object value);
    void beginEdit(String name);
    default void beginEdit(ConfigHost.Option option) { beginEdit(option.title()); }
    void commitEdit();
    void cancelEdit();
    void textField(String id,TextEdit edit,Rect bounds,String placeholder,Consumer<String> changed);
    void textDialog(String title,String hint,String initial,String action,Consumer<String> submit);
    void confirm(String title,String description,Runnable accepted);
    void overlay(UiPage overlay);
    void dismissOverlay();
    void close();
    double animate(String key,double target,double seconds,Motion.Easing easing);
    default Rect shell() { return new Rect(0,0,1391,700); }
}
