package com.muoniumplayer.core.rendering;

import java.util.Arrays;
import com.muoniumplayer.core.ncm.music.AudioSpectrumAnalyzer;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.CoverThemePalette;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.screens.ncm.WindowAdaptiveScale;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.render.skija.SkijaGfx;

/** Theme-aware final-mix frequency ribbon; completely independent of island notification ownership. */
public final class MusicSpectrumRenderer {
    private static final int BANDS=AudioSpectrumAnalyzer.BANDS;
    private static final float[] TARGET=new float[BANDS], DISPLAY=new float[BANDS];
    private static final SpectrumRibbon LEFT=new SpectrumRibbon(), RIGHT=new SpectrumRibbon(), PLAYER=new SpectrumRibbon();
    private static final SpectrumRibbon FULLSCREEN_LEFT=new SpectrumRibbon(), FULLSCREEN_RIGHT=new SpectrumRibbon();
    private static final SpectrumFullWidth FULL=new SpectrumFullWidth(), FULLSCREEN_FULL=new SpectrumFullWidth();
    private static AudioSpectrumAnalyzer.Frame observed;
    private static Music observedMusic;
    private static long lastFrame,lastPaletteCheck;
    private static float reveal;
    private static int colorA,colorB,coverA,coverB,paletteVersion=-1,colorMode=-1;
    private static boolean lightPalette,failed;

    private MusicSpectrumRenderer() { }
    public static void renderHud() {
        if(!HudConfig.spectrumEnabled || !HudConfig.spectrumInHud || failed) return;
        IslandVisibilityGate.Scene scene=IslandVisibilityGate.capture();
        if(!IslandVisibilityGate.allowSpectrumHud(scene)) return;
        // 布局轴几何 + 自带 correction 矩阵层：满屏/两翼条带的物理占屏比与 GUI Scale 档位
        // 无关（与灵动岛、两块 widget 同一轴）。renderPlayer/renderFullscreen 是播放器
        // 内部路径（已在使用方坐标系里拿矩形），不走这一层。
        boolean ownedLayout = !RenderSystem.FIXED_SCALE;
        if(ownedLayout) WindowAdaptiveScale.beginForced();
        try { renderHudInner(); } finally { if(ownedLayout) WindowAdaptiveScale.end(); }
    }
    private static void renderHudInner() {
        com.muoniumplayer.api.OpenAPI api=com.muoniumplayer.core.interfaces.SharedConstants.api;
        api.getGLStateManager().pushMatrix();
        api.getGLStateManager().scale(WindowAdaptiveScale.correctionX(),
                WindowAdaptiveScale.correctionY(), 1);
        try {
            float width=(float)RenderSystem.getLayoutWidth(), height=(float)RenderSystem.getLayoutHeight();
            if(width<32f || height<32f) return;
            boolean fullWidth=HudConfig.spectrumHudStyle!=1;
            float wing=SpectrumVisualMath.wingWidth(width);
            if(!fullWidth && (wing<24f || height<120f)) return;
            if(!tick()) return;
            if(fullWidth) {
                int barLimit = SpectrumVisualMath.fullWidthBarLimit(
                        HudConfig.spectrumBarCountPreset, HudConfig.spectrumCustomBarCount);
                FULL.draw(SkijaGfx.canvas(),DISPLAY,0f,width,height,
                        SpectrumVisualMath.fullHeight(height,heightScale()),opacity()*reveal,colorA,colorB,barLimit);
            } else {
                float high=Math.min(24f,height*.06f)*heightScale();
                float opacity=opacity()*.82f*reveal;
                LEFT.draw(SkijaGfx.canvas(),DISPLAY,0,BANDS/2,16f,height-7f,wing,high,opacity,colorA,colorB);
                RIGHT.draw(SkijaGfx.canvas(),DISPLAY,BANDS/2,BANDS/2,width-16f-wing,height-7f,wing,high,opacity,colorB,colorA);
            }
        } catch(Throwable failure) { fail(failure); }
        finally {
            api.getGLStateManager().popMatrix();
        }
    }
    public static void renderPlayer(double x,double y,double width,double height,float alpha) {
        if(!HudConfig.spectrumEnabled || !HudConfig.spectrumInPlayer || failed || alpha<.04f || width<90 || height<12) return;
        try {
            if(!tick()) return;
            float ribbonWidth=(float)Math.min(width*.62,360.0);
            float high=(float)Math.min(10.0,height*.18)*heightScale();
            PLAYER.draw(SkijaGfx.canvas(),DISPLAY,0,BANDS,(float)(x+(width-ribbonWidth)*.5),
                    (float)(y+height-2.0),ribbonWidth,high,opacity()*alpha*reveal,colorA,colorB);
        } catch(Throwable failure) { fail(failure); }
    }
    /** Fullscreen lyric-player spectrum. The split style adapts to two adjacent halves, never HUD-style wings. */
    public static void renderFullscreen(double x,double y,double width,double height,float alpha) {
        if(!HudConfig.spectrumEnabled || !HudConfig.spectrumInPlayer || failed || alpha<.02f || width<90 || height<80) return;
        try {
            if(!tick()) return;
            float bottom=(float)(y+height-2.0);
            float scale=heightScale();
            float amount=opacity()*alpha*reveal*.74f;
            if(HudConfig.spectrumFullscreenStyle==1) {
                float half=(float)width*.5f;
                float high=Math.min(34f,(float)height*.085f)*scale;
                // Map bass to the left half and treble to the right half. Unlike HUD wings there is no center gap.
                FULLSCREEN_LEFT.draw(SkijaGfx.canvas(),DISPLAY,0,BANDS/2,(float)x,bottom,half,high,amount,colorA,colorB);
                FULLSCREEN_RIGHT.draw(SkijaGfx.canvas(),DISPLAY,BANDS/2,BANDS/2,(float)x+half,bottom,
                        (float)width-half,high,amount,colorB,colorA);
            } else {
                float high=Math.min(116f,(float)height*.235f)*scale;
                FULLSCREEN_FULL.draw(SkijaGfx.canvas(),DISPLAY,(float)x,(float)width,bottom,high,
                        amount*.88f,colorA,colorB,160);
            }
        } catch(Throwable failure) { fail(failure); }
    }
    private static boolean tick() {
        if(!SkijaGfx.active() || SkijaGfx.canvas()==null) return false;
        long now=System.nanoTime();
        double elapsed=lastFrame==0L?1.0/60.0:(now-lastFrame)/1e9;
        if(elapsed>.25) {Arrays.fill(DISPLAY,0f);reveal=0f;}
        double dt=Math.max(0,Math.min(.10,elapsed)); lastFrame=now;
        CloudMusic.PlaybackSnapshot playback=CloudMusic.getSnapshot();
        boolean audible=playback.player!=null && playback.playing && playback.player.isUsable() && playback.player.isPlaying();
        if(audible) {
            AudioSpectrumAnalyzer.Frame frame=AudioSpectrumAnalyzer.requestFrame(now);
            if(now-frame.capturedAt<250_000_000L && frame.capturedAt>0L) {
                if(frame!=observed) {frame.copyTo(TARGET);observed=frame;}
            } else Arrays.fill(TARGET,0f);
        } else {Arrays.fill(TARGET,0f);AudioSpectrumAnalyzer.suspend();}
        reveal=SpectrumVisualMath.ease(reveal,audible?1f:0f,dt);
        float max=0f;
        for(int i=0;i<BANDS;i++) {DISPLAY[i]=SpectrumVisualMath.ease(DISPLAY[i],TARGET[i],dt);max=Math.max(max,DISPLAY[i]);}
        if(max<.008f || reveal<.01f) return false;
        colors(playback.music,now,dt);
        return true;
    }
    private static void colors(Music music,long now,double dt) {
        int mode=HudConfig.spectrumColorSource==1?1:0;
        int a=NCMTheme.getAccentColor()&0xFFFFFF;
        int b=NCMTheme.getColor(NCMScreen.ColorType.ACCENT_HOVER)&0xFFFFFF;
        if(mode==1 && (now-lastPaletteCheck>200_000_000L || mode!=colorMode || music!=observedMusic)) {
            lastPaletteCheck=now;
            int version=CoverThemePalette.version(); boolean light=NCMTheme.isLightPreset();
            if(version!=paletteVersion || music!=observedMusic || light!=lightPalette || mode!=colorMode) {
                // A new track with no artwork must use the current theme, not the previous track's colors.
                int[] colors=SpectrumVisualMath.coverColors(music==null?null:music.getCoverPalette(),light,a);
                coverA=colors[0];coverB=colors[1];paletteVersion=version;lightPalette=light;
            }
        }
        observedMusic=music;
        if(mode==1) {a=coverA;b=coverB;}
        if(colorMode<0) {colorA=a;colorB=b;}
        float amount=(float)(1.0-Math.exp(-dt*5.0));
        colorA=SpectrumVisualMath.mix(colorA,a,amount);colorB=SpectrumVisualMath.mix(colorB,b,amount);
        colorMode=mode;
    }
    private static float opacity() {return Float.isFinite(HudConfig.spectrumOpacity)?Math.max(.15f,Math.min(.85f,HudConfig.spectrumOpacity)):.58f;}
    private static float heightScale() {return Float.isFinite(HudConfig.spectrumHeight)?Math.max(.5f,Math.min(1.5f,HudConfig.spectrumHeight)):1f;}
    private static void fail(Throwable failure) {
        failed=true;AudioSpectrumAnalyzer.suspend();
        try { LEFT.close();RIGHT.close();PLAYER.close();FULLSCREEN_LEFT.close();FULLSCREEN_RIGHT.close();FULL.close();FULLSCREEN_FULL.close(); } catch(Throwable ignored) { }
        System.err.println("[Muonium/Spectrum] Visualization disabled; playback unaffected: "+failure);
    }
}
