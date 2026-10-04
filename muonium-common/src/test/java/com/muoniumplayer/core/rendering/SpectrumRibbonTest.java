package com.muoniumplayer.core.rendering;
import com.muoniumplayer.render.skija.SkijaNatives;
import io.github.humbleui.skija.*;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Paths;
import static org.junit.jupiter.api.Assertions.*;

class SpectrumRibbonTest {
    @BeforeAll static void load() {assertTrue(SkijaNatives.ensureLoaded());}
    private static float[] sample(float phase) {
        float[] values=new float[48];
        for(int i=0;i<values.length;i++) values[i]=.10f+.62f*(float)Math.exp(-Math.pow((i-10-phase*6)/9.0,2))+.23f*(float)Math.exp(-Math.pow((i-34+phase*3)/5.0,2));
        return values;
    }
    @Test void zeroAudioDoesNotInventVisibleBars() {
        try(Surface surface=Surface.makeRasterN32Premul(400,80);SpectrumRibbon ribbon=new SpectrumRibbon();Bitmap bitmap=new Bitmap()) {
            surface.getCanvas().clear(0);ribbon.draw(surface.getCanvas(),new float[48],0,48,20,65,360,40,.5f,0x8AABCE,0xBDCDE3);
            bitmap.allocN32Pixels(400,80,false);assertTrue(surface.getCanvas().readPixels(bitmap,0,0));
            for(byte value:bitmap.readPixels())assertEquals(0,value);
        }
    }
    @Test void spectrumIsTranslucentClippedAndParentStateIsRestored() {
        try(Surface surface=Surface.makeRasterN32Premul(400,80);SpectrumRibbon ribbon=new SpectrumRibbon();Bitmap bitmap=new Bitmap()) {
            Canvas canvas=surface.getCanvas();canvas.clear(0);
            canvas.save();canvas.clipRect(Rect.makeXYWH(30,25,180,40));
            ribbon.draw(canvas,sample(0),0,48,20,65,360,40,.5f,0x8AABCE,0xBDCDE3);canvas.restore();
            try(Paint paint=new Paint().setColor(0xFFFFFFFF)){canvas.drawRect(Rect.makeXYWH(2,2,2,2),paint);}
            bitmap.allocN32Pixels(400,80,false);assertTrue(canvas.readPixels(bitmap,0,0));
            assertEquals(0xFFFFFFFF,bitmap.getColor(2,2));assertEquals(0,bitmap.getColor(250,55)>>>24);
            int count=0;for(int y=25;y<65;y++)for(int x=30;x<210;x++){int alpha=bitmap.getColor(x,y)>>>24;if(alpha>0){count++;assertTrue(alpha<200);}}
            assertTrue(count>100);
        }
    }
    @Test void fullWidthSpectrumIsBottomAlignedTranslucentAndClipped() {
        try(Surface surface=Surface.makeRasterN32Premul(400,160);SpectrumFullWidth ribbon=new SpectrumFullWidth();Bitmap bitmap=new Bitmap()) {
            float[] values=new float[48];for(int i=0;i<values.length;i++)values[i]=.25f+.6f*(float)Math.exp(-Math.pow((i-24)/10.0,2));
            Canvas canvas=surface.getCanvas();canvas.clear(0);canvas.save();canvas.clipRect(Rect.makeXYWH(0,80,240,80));
            ribbon.draw(canvas,values,400,160,64,.65f,0x8AABCE,0xBDCDE3);canvas.restore();
            bitmap.allocN32Pixels(400,160,false);assertTrue(canvas.readPixels(bitmap,0,0));
            assertEquals(0,bitmap.getColor(300,100)>>>24);assertEquals(0,bitmap.getColor(250,155)>>>24);
            int visible=0,translucent=0;for(int y=80;y<160;y++)for(int x=0;x<240;x++){int alpha=bitmap.getColor(x,y)>>>24;if(alpha>0){visible++;if(alpha<250)translucent++;}}
            assertTrue(visible>100);assertTrue(translucent>visible*.8);
        }
    }

    @Test void writesFullWidthProductionPreviewAndRendersAcrossCenter() throws Exception {
        java.nio.file.Path dir=Paths.get("build","spectrum-preview");Files.createDirectories(dir);
        try(Surface surface=Surface.makeRasterN32Premul(960,540);SpectrumFullWidth ribbon=new SpectrumFullWidth();Bitmap bitmap=new Bitmap()) {
            Canvas canvas=surface.getCanvas();canvas.clear(0);
            float[] values=sample(.4f);
            ribbon.draw(canvas,values,960,540,SpectrumVisualMath.fullHeight(540,1f),.70f,0xD792B9,0x859ED7);
            bitmap.allocN32Pixels(960,540,false);assertTrue(canvas.readPixels(bitmap,0,0));
            boolean center=false,left=false,right=false;
            for(int y=420;y<540;y++)for(int x=0;x<960;x++) {
                int a=bitmap.getColor(x,y)>>>24;
                if(a>0){assertTrue(a<210);if(x>=420&&x<540)center=true;if(x<90)left=true;if(x>870)right=true;}
            }
            assertTrue(center&&left&&right,"The full style must not leave the hotbar center or screen sides empty");
            for(int y=0;y<420;y++)for(int x=0;x<960;x++)assertEquals(0,bitmap.getColor(x,y)>>>24);
            canvas.clear(0xFF1B2535);
            ribbon.draw(canvas,values,960,540,SpectrumVisualMath.fullHeight(540,1f),.70f,0xD792B9,0x859ED7);
            try(io.github.humbleui.skija.Image image=surface.makeImageSnapshot();Data png=EncoderPNG.encode(image)) {
                Files.write(dir.resolve("spectrum-full-width.png"),png.getBytes());
            }
        }
    }

    @Test void writesProductionRendererPreview() throws Exception {
        java.nio.file.Path dir=Paths.get("build","spectrum-preview");Files.createDirectories(dir);
        try(Surface surface=Surface.makeRasterN32Premul(1000,250);SpectrumRibbon player=new SpectrumRibbon();SpectrumRibbon left=new SpectrumRibbon();SpectrumRibbon right=new SpectrumRibbon()) {
            Canvas canvas=surface.getCanvas();canvas.clear(0xFF121923);
            try(Paint paint=new Paint().setColor(0xFF1F2936)){canvas.drawRRect(io.github.humbleui.types.RRect.makeXYWH(24,30,952,84,18),paint);}
            canvas.save();canvas.translate(50,30);canvas.scale(2,2);
            player.draw(canvas,sample(.4f),0,48,12,38,426,11,.62f,0x8DACC7,0xBBC9DD);canvas.restore();
            left.draw(canvas,sample(.4f),0,24,28,232,260,32,.55f,0x8DACC7,0xBBC9DD);
            right.draw(canvas,sample(.4f),24,24,712,232,260,32,.55f,0xBBC9DD,0x8DACC7);
            try(io.github.humbleui.skija.Image image=surface.makeImageSnapshot();Data png=EncoderPNG.encode(image)){Files.write(dir.resolve("spectrum.png"),png.getBytes());}
        }
    }
}
