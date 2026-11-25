package com.muoniumplayer.configskija.host;

import com.muoniumplayer.compat.lwjgl2.Display;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import net.minecraft.client.Minecraft;

/** Version-local declarations are checked against both exact host compile classpaths. */
public final class WindowAccess {
    private WindowAccess() {}
    public static double physicalPerLayout() { return Display.getWidth()/Math.max(1,RenderSystem.getLayoutWidth()); }
    public static double density() {
        var window=Minecraft.getInstance().getWindow();
        double pixelRatio=window.getWidth()/(double)Math.max(1,window.getScreenWidth());
        float[] x=new float[1],y=new float[1];org.lwjgl.glfw.GLFW.glfwGetWindowContentScale(Display.getWindowHandle(),x,y);double contentScale=Math.max(1,Math.max(x[0],y[0]));
        return Math.max(1,contentScale/Math.max(.01,pixelRatio))*pixelRatio/physicalPerLayout();
    }
    public static String playerName() { return Minecraft.getInstance().getUser().getName(); }
    public static java.util.UUID profileId() { return Minecraft.getInstance().getUser().getProfileId(); }
    public static String language() { return Minecraft.getInstance().getLanguageManager().getSelected(); }
    public static String translate(String key,Object... args) { return net.minecraft.client.resources.language.I18n.get(key,args); }
    public static Object screen() { return Minecraft.getInstance().screen; }
    public static boolean focused() { return Minecraft.getInstance().isWindowActive(); }
    public static boolean paused(){return Minecraft.getInstance().isPaused();}
    public static boolean inWorld() { return Minecraft.getInstance().level!=null; }
    public static double guiScale() { return Minecraft.getInstance().getWindow().getGuiScale(); }
    public static double guiWidth() { return Minecraft.getInstance().getWindow().getGuiScaledWidth(); }
    public static double guiHeight() { return Minecraft.getInstance().getWindow().getGuiScaledHeight(); }
    public static boolean chat() { return screen() instanceof net.minecraft.client.gui.screens.ChatScreen; }
    public static com.muoniumplayer.configskija.ui.hud.HudRuntime.Frame hudFrame() {
        var mc=Minecraft.getInstance();java.util.Set<Integer> keys=new java.util.HashSet<>();
        for(int i=1;i<256;i++) if(com.muoniumplayer.compat.lwjgl2.Keyboard.isKeyDown(i)) keys.add(i);
        java.util.Map<String,String> data=java.util.Map.of("player",playerName(),"fps",Integer.toString(mc.getFps()));
        return new com.muoniumplayer.configskija.ui.hud.HudRuntime.Frame(RenderSystem.getLayoutWidth(),RenderSystem.getLayoutHeight(),guiScale(),System.nanoTime()/1_000_000_000d,java.time.LocalDateTime.now(),false,mc.getDebugOverlay().showDebugScreen(),mc.options.keyPlayerList.isDown(),screen()!=null,chat(),keys,data).withPhysicalScale(physicalPerLayout());
    }

}
