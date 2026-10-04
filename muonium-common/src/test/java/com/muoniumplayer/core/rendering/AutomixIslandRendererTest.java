package com.muoniumplayer.core.rendering;

import com.muoniumplayer.core.ncm.music.AutomixTransitionCurve;
import com.muoniumplayer.render.skija.SkijaNatives;
import io.github.humbleui.skija.*;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class AutomixIslandRendererTest {
    private static final int WIDTH = 200, HEIGHT = 70;

    @BeforeAll static void nativeReady() { assertTrue(SkijaNatives.ensureLoaded()); }

    private static AutomixIslandRenderer.Frame frame(double progress, double opacity) {
        return new AutomixIslandRenderer.Frame(AutomixTransitionCurve.preview(), progress,
                progress * 10.0, opacity);
    }

    @Test void rollingWindowAndHeadsUseActualPlanValues() {
        AutomixIslandRenderer.Frame frame = frame(.38, 1.0);
        assertEquals(.38, AutomixIslandRenderer.sampleProgress(.38, AutomixIslandRenderer.PLAYHEAD), 1e-9);
        assertEquals(frame.curve.value(AutomixTransitionCurve.Band.INCOMING_GAIN, .38),
                AutomixIslandRenderer.level(frame, true, false, AutomixIslandRenderer.PLAYHEAD), 1e-6);
        double bass = frame.curve.value(AutomixTransitionCurve.Band.INCOMING_BASS, .38);
        assertEquals(frame.curve.value(AutomixTransitionCurve.Band.INCOMING_GAIN, .38)
                        * Math.pow(10.0, bass / 20.0),
                AutomixIslandRenderer.level(frame, true, true, AutomixIslandRenderer.PLAYHEAD), 1e-6);
        assertTrue(AutomixIslandRenderer.sampleProgress(.65, .2)
                > AutomixIslandRenderer.sampleProgress(.35, .2), "线上的数据必须随着交接向前滚动");
        assertTrue(AutomixIslandRenderer.level(frame(.2,1),true,false,.52)
                < AutomixIslandRenderer.level(frame(.8,1),true,false,.52));
        assertTrue(AutomixIslandRenderer.level(frame(.2,1),false,false,.52)
                > AutomixIslandRenderer.level(frame(.8,1),false,false,.52));
    }

    @Test void dimensionsStaySmallAndFitTheScreen() {
        for (double scale : new double[]{.60,1.0,1.35}) {
            for (double screen : new double[]{64,128,320,1920}) {
                double width = AutomixIslandRenderer.targetWidth(screen,scale);
                assertTrue(width > 0 && width <= AutomixIslandRenderer.TARGET_WIDTH);
                assertTrue(width * scale <= screen - 15.9);
            }
        }
        assertTrue(AutomixIslandRenderer.TARGET_HEIGHT <= AutomixIslandRenderer.MAX_HEIGHT);
    }

    @Test void pixelsReallyMoveWithoutLeakingOutsideTheIsland() {
        byte[] early = render(.18), middle = render(.5), late = render(.82);
        assertFalse(Arrays.equals(early,middle), "不能是静态曲线图加一个移动竖线");
        assertFalse(Arrays.equals(middle,late));
        int visible=0, translucent=0;
        for (int y=0; y<HEIGHT; y++) for (int x=0; x<WIDTH; x++) {
            int a=middle[(y*WIDTH+x)*4+3]&255;
            if (x<24 || x>=176 || y<20 || y>=50) assertEquals(0,a,"绘制不能越出灵动岛画布");
            if (a>0) visible++;
            if (a>0 && a<250) translucent++;
        }
        assertTrue(visible>200);
        assertTrue(translucent>visible*.98, "必须保留半透明线条和表面");
    }

    @Test void respectsParentTransformAndClipAndReleasesOwnClip() {
        try (Surface surface=Surface.makeRasterN32Premul(400,160); Bitmap bitmap=new Bitmap()) {
            Canvas canvas=surface.getCanvas();canvas.clear(0);
            canvas.save();canvas.translate(10,6);canvas.scale(2,2);
            canvas.clipRect(Rect.makeXYWH(24,20,70,30));
            AutomixIslandRenderer.draw(canvas,frame(.5,1),24,20,152,30,15,1,0xFF80D9CE);
            canvas.restore();
            try (Paint paint=new Paint()) {
                paint.setColor(0xFFFFFFFF);canvas.drawRect(Rect.makeXYWH(2,2,2,2),paint);
            }
            bitmap.allocN32Pixels(400,160,false);assertTrue(canvas.readPixels(bitmap,0,0));
            assertEquals(0xFFFFFFFF,bitmap.getColor(2,2),"子绘制不能泄漏自己的圆角裁剪");
            assertNotEquals(0,bitmap.getColor(150,65)>>>24);
            assertEquals(0,bitmap.getColor(215,65)>>>24,"必须保留父画布裁剪");
        }
    }

    @Test void rendersActualRendererPreviewFrames() throws Exception {
        java.nio.file.Path directory=Paths.get("build","automix-island-preview");
        Files.createDirectories(directory);
        for (int index=0; index<160; index++) {
            try (Surface surface=Surface.makeRasterN32Premul(420,120)) {
                Canvas canvas=surface.getCanvas();canvas.clear(0xFF344C4D);
                double p=index/159.0;
                canvas.save();canvas.translate(20,12);canvas.scale(2.5f,2.5f);
                AutomixIslandRenderer.draw(canvas,frame(p,1),0,0,152,30,15,1,0xFF84DBCB);
                canvas.restore();
                try (io.github.humbleui.skija.Image image=surface.makeImageSnapshot(); Data png=EncoderPNG.encode(image)) {
                    assertNotNull(png);
                    Files.write(directory.resolve(String.format("frame-%02d.png",index)),png.getBytes());
                }
            }
        }
    }

    private static byte[] render(double progress) {
        try (Surface surface=Surface.makeRasterN32Premul(WIDTH,HEIGHT);Bitmap bitmap=new Bitmap()) {
            surface.getCanvas().clear(0);
            AutomixIslandRenderer.draw(surface.getCanvas(),frame(progress,1),24,20,152,30,15,1,0xFF84DBCB);
            bitmap.allocN32Pixels(WIDTH,HEIGHT,false);
            assertTrue(surface.getCanvas().readPixels(bitmap,0,0));
            return bitmap.readPixels();
        }
    }
}
