package com.muoniumplayer.configskija.host;

import com.muoniumplayer.api.Extension;
import com.muoniumplayer.api.features.ExtensionScreen;
import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.configskija.model.*;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaShapes;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Screen input/IME and drawing are supplied by MuoniumPlayer's existing version-specific GuiScreen. */
public final class SkijaConfigScreen extends ExtensionScreen {
    private final SkijaConfigService session=SkijaConfigService.get();
    private final Navigation.Page initialPage;
    private ConfigView view;
    private UiPreferences preferences;
    private boolean closed,pressed;
    private char highSurrogate;
    private final Set<Integer> keysDown=new HashSet<>();
    public SkijaConfigScreen() { this(Navigation.Page.SETTINGS); }
    private SkijaConfigScreen(Navigation.Page initialPage) { this.initialPage=initialPage; }
    public static void open() {
        if(Extension.getAPI()==null)throw new IllegalStateException("MuoniumPlayer API is not initialized");
        Extension.getAPI().displayScreen(new SkijaConfigScreen());
    }
    /** H and settings buttons always open parameters, independent of the last visited editor page. */
    public static void openSettings() {
        if(Extension.getAPI()==null)throw new IllegalStateException("MuoniumPlayer API is not initialized");
        Extension.getAPI().displayScreen(new SkijaConfigScreen(Navigation.Page.SETTINGS));
    }
    public static void openHud() {
        if(Extension.getAPI()==null)throw new IllegalStateException("MuoniumPlayer API is not initialized");
        Extension.getAPI().displayScreen(new SkijaConfigScreen(Navigation.Page.HUD));
    }
    public boolean recording(){return view!=null&&view.recording();}
    public boolean editingHud(){return view!=null&&view.editingHud();}
    public void search(){if(view!=null)view.requestSearch();}
    public void menuKey(){if(view!=null)view.menuKey();}
    public void initGui() { updateScreen(); }
    public void updateScreen() {
        if(closed)return;
        if(view==null&&session.ready()) {
            MuoniumHost host=session.host();preferences=session.preferences();view=new ConfigView(host,preferences);view.keyTokens(KeyTokens::token);view.globalSearch(session.searchPage());installPages(view,host,preferences);session.opened(this);
            if(initialPage!=null)view.navigate(new Navigation.Route(initialPage));
            System.out.println("[MuoniumConfig/Skija] opened: options="+view.options().size()+", renderer=MuoniumPlayer Skija, compose=false");
        }
        SkijaConfigService.tick();pollKeyReleases();
    }
    private void installPages(ConfigView view,MuoniumHost host,UiPreferences preferences) {
        view.register(Navigation.Page.SETTINGS,new com.muoniumplayer.configskija.ui.settings.SettingsPage(false));
        view.register(Navigation.Page.FAVORITES,new com.muoniumplayer.configskija.ui.settings.SettingsPage(true));
        view.register(Navigation.Page.MODS,com.muoniumplayer.configskija.ui.pages.LibraryPages.mods());
        view.register(Navigation.Page.PROFILES,com.muoniumplayer.configskija.ui.pages.LibraryPages.profiles());
        view.register(Navigation.Page.KEYS,com.muoniumplayer.configskija.ui.pages.LibraryPages.keys());
        view.register(Navigation.Page.CREDITS,com.muoniumplayer.configskija.ui.pages.LibraryPages.credits());
        view.register(Navigation.Page.THEMES,com.muoniumplayer.configskija.ui.pages.AppearancePages.themes());
        view.register(Navigation.Page.PREFERENCES,com.muoniumplayer.configskija.ui.pages.AppearancePages.preferences());
        view.register(Navigation.Page.HUD,new com.muoniumplayer.configskija.ui.hud.HudStudio(host,preferences,session.runtime()));
    }
    private void pollKeyReleases() {
        if(view==null)return;
        for(Integer key:new HashSet<>(keysDown))if(!Keyboard.isKeyDown(key)){keysDown.remove(key);view.rawKey(key,false);}
    }
    public void drawScreen(int mouseX,int mouseY) {
        if(SkijaGfx.canvas()==null||FontManager.pf32==null)return;
        updateScreen();RenderSystem.refreshResolution();double w=RenderSystem.getLayoutWidth(),h=RenderSystem.getLayoutHeight();
        double x=mouseX*w/Math.max(1,width),y=mouseY*h/Math.max(1,height);
        SkijaGfx.pushAttrib();SkijaGfx.pushMatrix();
        try {
            SkijaGfx.scale(width/w,height/h);
            if(view==null){SkijaShapes.roundedRect(w/2-180,h/2-32,360,64,16,0xF011171C);FontManager.pf16.drawString(session.failed()?"Skija 设置中心初始化失败":"正在加载 Skija 设置中心…",w/2-160,h/2-12,0xFFD5DBFF);if(session.failed())FontManager.pf12.drawString(session.failure(),w/2-160,h/2+8,0xFFEE8899);return;}
            view.modifiers(control(),shift(),alt());
            if(pressed&&!Mouse.isButtonDown(0)){view.pointerUp(x,y,0);pressed=false;}
            session.drawBackground(w,h,view.backgroundAlpha());
            view.frame(session.painter(),w,h,x,y,System.nanoTime()/1_000_000_000d);
            int wheel=Mouse.getDWheel();if(wheel!=0)view.wheel(wheel/120d);
            session.pointerMove(x,y);session.renderToasts();
        }finally{SkijaGfx.popMatrix();SkijaGfx.popAttrib();}
    }
    protected void mouseClicked(int mouseX,int mouseY,int button)throws IOException{
        if(view==null)return;RenderSystem.refreshResolution();double x=mouseX*RenderSystem.getLayoutWidth()/Math.max(1,width),y=mouseY*RenderSystem.getLayoutHeight()/Math.max(1,height);
        session.pointerMove(x,y);if(com.muoniumplayer.configskija.host.notifications.NotificationIntegration.pointerDown(this,button))return;
        view.pointerDown(x,y,button,control(),shift(),alt());if(button==0)pressed=true;
    }
    protected void mouseReleased(int mouseX,int mouseY,int button){
        if(view==null)return;com.muoniumplayer.configskija.host.notifications.NotificationIntegration.pointerUp(this,button);
        view.pointerUp(mouseX*RenderSystem.getLayoutWidth()/Math.max(1,width),mouseY*RenderSystem.getLayoutHeight()/Math.max(1,height),button);if(button==0)pressed=false;
    }
    protected void keyTyped(char character,int keyCode)throws IOException{
        if(view==null){super.keyTyped(character,keyCode);return;}
        String typed="";
        if(Character.isHighSurrogate(character)){highSurrogate=character;return;}
        if(Character.isLowSurrogate(character)&&highSurrogate!=0){typed=new String(new char[]{highSurrogate,character});highSurrogate=0;}else{highSurrogate=0;if(character>=32&&character!=127)typed=String.valueOf(character);}
        view.nativeKeyCode(keyCode);if(keyCode>0){keysDown.add(keyCode);if(view.rawKey(keyCode,true))return;}
        view.key(key(keyCode),typed,control(),shift(),alt());
    }
    public boolean doesGuiPauseGame(){return preferences!=null&&preferences.flag("pauseGame",false);}
    public void onGuiClosed(){if(closed)return;closed=true;if(view!=null)view.dispose();session.removed(this);System.out.println("[MuoniumConfig/Skija] screen closed; persistent HUD session retained");}
    private static boolean control(){return Keyboard.isKeyDown(Keyboard.KEY_LCONTROL)||Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);}
    private static boolean shift(){return Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)||Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);}
    private static boolean alt(){return Keyboard.isKeyDown(Keyboard.KEY_LMENU)||Keyboard.isKeyDown(Keyboard.KEY_RMENU);}
    private static ConfigView.Key key(int key){return switch(key){
        case Keyboard.KEY_ESCAPE->ConfigView.Key.ESCAPE;case Keyboard.KEY_RETURN,Keyboard.KEY_NUMPADENTER->ConfigView.Key.ENTER;case Keyboard.KEY_TAB->ConfigView.Key.TAB;
        case Keyboard.KEY_LEFT->ConfigView.Key.LEFT;case Keyboard.KEY_RIGHT->ConfigView.Key.RIGHT;case Keyboard.KEY_UP->ConfigView.Key.UP;case Keyboard.KEY_DOWN->ConfigView.Key.DOWN;
        case Keyboard.KEY_HOME->ConfigView.Key.HOME;case Keyboard.KEY_END->ConfigView.Key.END;case Keyboard.KEY_BACK->ConfigView.Key.BACKSPACE;case Keyboard.KEY_DELETE->ConfigView.Key.DELETE;
        case Keyboard.KEY_A->ConfigView.Key.A;case Keyboard.KEY_C->ConfigView.Key.C;case Keyboard.KEY_V->ConfigView.Key.V;case Keyboard.KEY_X->ConfigView.Key.X;case Keyboard.KEY_F->ConfigView.Key.F;
        case Keyboard.KEY_Z->ConfigView.Key.Z;case Keyboard.KEY_Y->ConfigView.Key.Y;case Keyboard.KEY_H->ConfigView.Key.H;case Keyboard.KEY_L->ConfigView.Key.L;case Keyboard.KEY_E->ConfigView.Key.E;default->ConfigView.Key.NONE;};}
}
