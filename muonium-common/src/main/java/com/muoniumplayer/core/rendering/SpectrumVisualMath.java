package com.muoniumplayer.core.rendering;

import java.awt.Color;

/** Headless visual policy: bounded layout, time-based response and cover-derived tonal colors. */
final class SpectrumVisualMath {
    private SpectrumVisualMath() { }
    static float ease(float current,float target,double seconds) {
        target=finite01(target); current=finite01(current);
        double dt=Double.isFinite(seconds)?Math.max(0.0,Math.min(.10,seconds)):0.0;
        return target+(current-target)*(float)Math.exp(-dt*(target>current?23.0:9.0));
    }
    static float finite01(float value) { return Float.isFinite(value)?Math.max(0f,Math.min(1f,value)):0f; }
    static int bars(float width,int maximum) { return Math.max(1,Math.min(maximum,(int)(width/5f))); }
    /** modernMP's logical HUD geometry: baseline at the bottom, 120px cap / 35% on short screens. */
    static float fullHeight(float screenHeight,float scale) {
        if(!Float.isFinite(screenHeight) || screenHeight<=0f) return 0f;
        float multiplier=Float.isFinite(scale)?Math.max(.5f,Math.min(1.5f,scale)):1f;
        return Math.min(screenHeight,Math.min(120f,screenHeight*.35f)*multiplier);
    }
    private static final int MAX_FULL_WIDTH_BARS = 256;
    static int fullBars(float width) { return fullBars(width, 64); }
    static int fullBars(float width, int maximum) {
        int cap = Math.max(1, Math.min(MAX_FULL_WIDTH_BARS, maximum));
        return Math.max(1, Math.min(cap, (int) (width / 3f)));
    }
    /** Maps the HUD's saved preset to a bounded, aesthetically readable maximum bar count. */
    static int fullWidthBarLimit(int preset, int customBars) {
        switch (preset) {
            case 1: return 96;
            case 2: return 128;
            case 3: return 160;
            case 4: return 192;
            case 5: return Math.max(48, Math.min(MAX_FULL_WIDTH_BARS, customBars));
            case 0:
            default: return 64;
        }
    }
    static float fullBarWidth(float width,int bars) { return Math.max(.5f,(width-1.5f*(bars-1))/bars); }
    /** Display response, not synthesized data: interpolates logarithmic bands to the requested bar count. */
    static float fullLevel(float[] bands,int index,int count) {
        if(bands==null || bands.length==0) return 0f;
        float position=count<=1?0f:index*(bands.length-1f)/(count-1f);
        int first=Math.min(bands.length-1,Math.max(0,(int)position));
        int second=Math.min(first+1,bands.length-1);
        float fraction=position-first;
        float value=finite01(bands[first])+(finite01(bands[second])-finite01(bands[first]))*fraction;
        float response=1f-(float)Math.pow(1f-value,1.5);
        return response*response;
    }
    static float wingWidth(float screenWidth) { return Math.max(0f,Math.min(180f,(screenWidth-256f)/2f)); }
    static int mix(int a,int b,float t) {
        t=finite01(t);
        int r=Math.round(((a>>16)&255)+(((b>>16)&255)-((a>>16)&255))*t);
        int g=Math.round(((a>>8)&255)+(((b>>8)&255)-((a>>8)&255))*t);
        int blue=Math.round((a&255)+((b&255)-(a&255))*t);
        return (r<<16)|(g<<8)|blue;
    }
    static int[] coverColors(int[] palette,boolean light,int fallback) {
        if(palette==null || palette.length==0) return new int[]{fallback,mix(fallback,0xDDE4F1,.28f)};
        float[] hsb=new float[3]; int accent=palette[0]; float best=-1f;
        for(int color:palette) {
            Color.RGBtoHSB((color>>16)&255,(color>>8)&255,color&255,hsb);
            float score=hsb[1]*(.45f+hsb[2]);
            if(score>best) { best=score; accent=color; }
        }
        Color.RGBtoHSB((accent>>16)&255,(accent>>8)&255,accent&255,hsb);
        // Muted chroma keeps this a musical accent, not a neon equalizer pasted over the theme.
        float saturation=Math.max(.18f,Math.min(.62f,hsb[1]*.70f));
        float value=light?.55f:.88f;
        int a=Color.HSBtoRGB(hsb[0],saturation,value)&0xFFFFFF;
        int b=Color.HSBtoRGB((hsb[0]+.055f)%1f,saturation*.68f,Math.min(.95f,value+.07f))&0xFFFFFF;
        return new int[]{a,b};
    }
}
