package com.muoniumplayer.core.rendering;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpectrumVisualMathTest {
    @Test void hudKeepsTheHotbarCenterFreeEvenOnSmallScreens() {
        for(float screen:new float[]{200,320,640,960,1920}) {
            float wing=SpectrumVisualMath.wingWidth(screen);
            assertTrue(wing>=0&&wing<=180);
            if(wing>0)assertTrue(screen-32-wing*2>=224);
        }
    }
    @Test void adaptiveBarCountNeverExceedsFrequencyData() {
        for(int width=5;width<1000;width++)assertTrue(SpectrumVisualMath.bars(width,48)>=1&&SpectrumVisualMath.bars(width,48)<=48);
    }
    @Test void animationIsFrameRateIndependent() {
        float slow=0,fast=0;
        for(int i=0;i<30;i++)slow=SpectrumVisualMath.ease(slow,1f,1.0/30);
        for(int i=0;i<120;i++)fast=SpectrumVisualMath.ease(fast,1f,1.0/120);
        assertEquals(slow,fast,.0001);
        assertTrue(slow>.99);
    }
    @Test void fullWidthLayoutMatchesReferenceGeometry() {
        assertEquals(120f,SpectrumVisualMath.fullHeight(900f,1f),.001f);
        assertEquals(180f,SpectrumVisualMath.fullHeight(900f,1.5f),.001f);
        assertEquals(70f,SpectrumVisualMath.fullHeight(200f,1f),.001f);
        assertEquals(64,SpectrumVisualMath.fullBars(1920f));
        assertEquals(160,SpectrumVisualMath.fullBars(1920f,160));
        assertEquals(106,SpectrumVisualMath.fullBars(320f,160));
        assertEquals(256,SpectrumVisualMath.fullBars(1920f,512));
        assertEquals(1,SpectrumVisualMath.fullBars(320f,0));
        assertEquals(64,SpectrumVisualMath.fullWidthBarLimit(0,200));
        assertEquals(96,SpectrumVisualMath.fullWidthBarLimit(1,200));
        assertEquals(128,SpectrumVisualMath.fullWidthBarLimit(2,200));
        assertEquals(160,SpectrumVisualMath.fullWidthBarLimit(3,200));
        assertEquals(192,SpectrumVisualMath.fullWidthBarLimit(4,200));
        assertEquals(48,SpectrumVisualMath.fullWidthBarLimit(5,1));
        assertEquals(173,SpectrumVisualMath.fullWidthBarLimit(5,173));
        assertEquals(256,SpectrumVisualMath.fullWidthBarLimit(5,999));
        assertTrue(SpectrumVisualMath.fullBarWidth(1920f,64)>0);
        float[] bands=new float[48];bands[0]=1f;bands[47]=.5f;
        assertTrue(SpectrumVisualMath.fullLevel(bands,0,64)>.9f);
        assertTrue(SpectrumVisualMath.fullLevel(bands,63,64)>.15f);
    }
    @Test void blankArtworkFallsBackAndTonalColorsRemainDistinct() {
        int[] fallback=SpectrumVisualMath.coverColors(null,false,0x78AABB);
        assertEquals(0x78AABB,fallback[0]);
        int[] warm=SpectrumVisualMath.coverColors(new int[]{0xE14A35,0x55201A},false,0);
        int[] cool=SpectrumVisualMath.coverColors(new int[]{0x356CE1,0x182A55},false,0);
        assertNotEquals(warm[0],cool[0]);assertNotEquals(warm[0],warm[1]);
    }
    @Test void hudGateHidesSpectrumInMenusInventoryTabAndF1() {
        assertTrue(IslandVisibilityGate.allowSpectrumHud(new IslandVisibilityGate.Scene(true,false,false,false,false)));
        assertFalse(IslandVisibilityGate.allowSpectrumHud(new IslandVisibilityGate.Scene(false,false,false,false,false)));
        assertFalse(IslandVisibilityGate.allowSpectrumHud(new IslandVisibilityGate.Scene(true,true,false,false,false)));
        assertFalse(IslandVisibilityGate.allowSpectrumHud(new IslandVisibilityGate.Scene(true,false,false,true,false)));
        assertFalse(IslandVisibilityGate.allowSpectrumHud(new IslandVisibilityGate.Scene(true,false,false,false,true)));
    }
}
