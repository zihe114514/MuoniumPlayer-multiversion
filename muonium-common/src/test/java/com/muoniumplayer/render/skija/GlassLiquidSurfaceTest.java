package com.muoniumplayer.render.skija;

import io.github.humbleui.skija.*;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Actual pixels, not just source-text checks: the same drawOn is used by the live GPU renderer. */
class GlassLiquidSurfaceTest {
    @BeforeAll static void loadNative() { assertTrue(SkijaNatives.ensureLoaded()); }

    @Test void programCompilesWithAllFiveFloat4s() {
        try (RuntimeEffect e = RuntimeEffect.makeForShader(GlassLiquidSurface.source())) {
            assertEquals(80, e.getUniformSize());
        }
        assertTrue(GlassLiquidSurface.compiles());
    }

    private static Image gradient(int size) {
        try (Surface surface = Surface.makeRasterN32Premul(size, size); Paint p = new Paint()) {
            for (int y=0; y<size; y++) for (int x=0; x<size; x++) {
                p.setColor(0xFF000000 | (Math.round(x*255f/(size-1)) << 16)
                        | (Math.round(y*255f/(size-1)) << 8) | 64);
                surface.getCanvas().drawRect(Rect.makeXYWH(x,y,1,1),p);
            }
            return surface.makeImageSnapshot();
        }
    }

    private static byte[] pixels(Surface surface, int width, int height) {
        try (Bitmap bitmap = new Bitmap()) {
            bitmap.allocPixels(ImageInfo.makeN32Premul(width,height));
            assertTrue(surface.readPixels(bitmap,0,0));
            return bitmap.readPixels();
        }
    }

    private static int channel(byte[] bytes, int size, int x, int y, int offset) {
        return bytes[(y*size+x)*4+offset] & 255;
    }

    @Test void referenceSuperellipseMatchesIndependentCpuFormula() {
        int size=128;
        try (Image image=gradient(size);
             Shader source=image.makeShader(FilterTileMode.CLAMP,FilterTileMode.CLAMP,SamplingMode.LINEAR,null);
             Surface target=Surface.makeRasterN32Premul(size,size)) {
            target.getCanvas().clear(0);
            GlassLiquidSurface.drawOn(target.getCanvas(),source,16,16,96,96,18,
                    1,1,0,0,1,1,3,1,0,0,.06f);
            byte[] bytes=pixels(target,size,size);
            int checks=0;
            for(int y=18; y<110; y+=3) for(int x=18; x<110; x+=3) {
                double px=(x+.5-64)/48, py=(y+.5-64)/48;
                double numerator=Math.pow(Math.abs(px),3)+Math.pow(Math.abs(py),3)-1;
                double denominator=3*Math.sqrt(Math.pow(Math.abs(px),4)+Math.pow(Math.abs(py),4))+.00001;
                double distance=numerator/denominator;
                if(distance > .01) { assertEquals(0,channel(bytes,size,x,y,3)); continue; }
                if(distance > -.01) continue;
                double lens=1-2.3*Math.pow(5.2*Math.E,6.9*distance-.7);
                int red=(int)Math.round((64+(x+.5-64)*lens-.5)*255/127);
                int green=(int)Math.round((64+(y+.5-64)*lens-.5)*255/127);
                assertEquals(red,channel(bytes,size,x,y,2),3,"reference red at "+x+","+y);
                assertEquals(green,channel(bytes,size,x,y,1),3,"reference green at "+x+","+y);
                assertEquals(255,channel(bytes,size,x,y,3));
                checks++;
            }
            assertTrue(checks>400);
            // Lens bends the background by many pixels near the silhouette.
            assertTrue(channel(bytes,size,110,64,2) < 212, "flat backdrop is NOT a liquid lens");
        }
    }

    @Test void opacityOnlyChangesPremultiplicationNotGeometry() {
        int size=128;
        try (Image image=gradient(size);
             Shader source=image.makeShader(FilterTileMode.CLAMP,FilterTileMode.CLAMP,SamplingMode.LINEAR,null);
             Surface full=Surface.makeRasterN32Premul(size,size);
             Surface half=Surface.makeRasterN32Premul(size,size)) {
            full.getCanvas().clear(0); half.getCanvas().clear(0);
            GlassLiquidSurface.drawOn(full.getCanvas(),source,8,40,112,32,16,1,1,0,0,1,1,0,1,0,0,.06f);
            GlassLiquidSurface.drawOn(half.getCanvas(),source,8,40,112,32,16,1,1,0,0,1,.5f,0,1,0,0,.06f);
            byte[] f=pixels(full,size,size), h=pixels(half,size,size);
            for (int x=24;x<104;x+=7) for(int y=43;y<69;y+=5) {
                assertEquals(255,channel(f,size,x,y,3));
                assertEquals(128,channel(h,size,x,y,3),1);
                for(int c=0;c<3;c++) assertEquals(channel(f,size,x,y,c)*.5,channel(h,size,x,y,c),2);
            }
            // Existing capsule silhouette must be filled: normalized squircle clipping left this spot empty.
            assertEquals(255,channel(f,size,20,44,3),1);
        }
    }

    @Test void imageMappingHonorsScaleOffsetAndScreenEdges() {
        int size=128;
        try (Image image=gradient(size);
             Shader source=image.makeShader(FilterTileMode.CLAMP,FilterTileMode.CLAMP,SamplingMode.LINEAR,null);
             Surface target=Surface.makeRasterN32Premul(size,size)) {
            target.getCanvas().clear(0);
            // Strength=0 must be a positionally exact transparent window, including offset/unequal axes.
            GlassLiquidSurface.drawOn(target.getCanvas(),source,0,0,128,128,0,
                    .5f,.75f,10,4,0,1,0,1,0,0,.06f);
            byte[] bytes=pixels(target,size,size);
            for(int y=8;y<124;y+=11) for(int x=4;x<124;x+=13) {
                double rx=((x+.5)*.5+10-.5)*255/127;
                double gy=((y+.5)*.75+4-.5)*255/127;
                assertEquals(rx,channel(bytes,size,x,y,2),3);
                assertEquals(gy,channel(bytes,size,x,y,1),3);
            }
            target.getCanvas().clear(0);
            GlassLiquidSurface.drawOn(target.getCanvas(),source,-8,-8,160,160,12,
                    1,1,0,0,1,1,0,1,0,.3f,.06f);
            bytes=pixels(target,size,size);
            assertEquals(255,channel(bytes,size,2,64,3));
            assertEquals(255,channel(bytes,size,126,64,3));
            assertEquals(255,channel(bytes,size,64,64,3)); // atan(0,0) / SDF center must never yield a NaN.
        }
        assertFalse(GlassLiquidSurface.validRect(Double.NaN,0,100,100,5));
        assertFalse(GlassLiquidSurface.validRect(0,0,Double.POSITIVE_INFINITY,100,5));
        assertFalse(GlassLiquidSurface.validRect(0,0,0,100,5));
    }

    @Test
    @EnabledIfEnvironmentVariable(named="MUONIUM_TEST_GPU", matches="1")
    void realGpuCompilesAndRendersTheLens() throws Exception {
        // No visible window and no interaction with the user's Minecraft instance.
        assertTrue((Boolean)glfw("glfwInit"), "GLFW must initialize for the real GPU acceptance test");
        glfw("glfwDefaultWindowHints");
        glfw("glfwWindowHint",0x00020004,0);
        glfw("glfwWindowHint",0x00022002,3);
        glfw("glfwWindowHint",0x00022003,3);
        glfw("glfwWindowHint",0x00022008,0x00032001);
        long window=(Long)glfw("glfwCreateWindow",64,64,(CharSequence)"LiquidGlass hidden GPU test",0L,0L);
        assertNotEquals(0,window);
        try {
            glfw("glfwMakeContextCurrent",window); GL.createCapabilities();
            System.out.println("LiquidGlass GPU: "+GL11.glGetString(GL11.GL_RENDERER));
            try (DirectContext context=DirectContext.makeGL();
                 Surface target=Surface.makeRenderTarget(context,false,ImageInfo.makeN32Premul(128,128));
                 Image image=gradient(128);
                 Shader shader=image.makeShader(FilterTileMode.CLAMP,FilterTileMode.CLAMP,SamplingMode.LINEAR,null)) {
                target.getCanvas().clear(0);
                GlassLiquidSurface.drawOn(target.getCanvas(),shader,16,16,96,96,18,1,1,0,0,1,1,3,1,0,0,.06f);
                context.flushAndSubmit(true);
                byte[] bytes=pixels(target,128,128);
                assertEquals(255,channel(bytes,128,64,64,3));
                assertTrue(channel(bytes,128,110,64,2)<212,"GPU must distort the real source");
                save(target,Path.of("build","liquid-glass-gpu-gradient.png"));
                verifyExternalTextureRefresh(context);
                referencePhoto(context);
            }
        } finally {
            glfw("glfwMakeContextCurrent",0L); GL.setCapabilities(null);
            glfw("glfwDestroyWindow",window); glfw("glfwTerminate");
        }
    }

    private static Object glfw(String method, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int i=0;i<args.length;i++) types[i]=args[i] instanceof Integer ? int.class
                : args[i] instanceof Long ? long.class : CharSequence.class;
        return Class.forName("org.lwjgl.glfw.GLFW").getMethod(method,types).invoke(null,args);
    }

    private static void verifyExternalTextureRefresh(DirectContext context) {
        // Mirrors the production capture: an externally owned GL texture, borrowed by Skia,
        // updated without rebuilding the Image or child shader. This proves it is not a static image.
        int texture=GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,16,16,0,
                GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,solidPixels(16,50,120,200));
        context.resetGLAll();
        BackendTexture backend=BackendTexture.makeGL(16,16,false,
                new GLTextureInfo(GL11.GL_TEXTURE_2D,texture,GL11.GL_RGBA8));
        try (Image borrowed=Image.borrowTextureFrom(context,backend,SurfaceOrigin.TOP_LEFT,
                    ColorType.RGBA_8888,ColorAlphaType.OPAQUE,ColorSpace.getSRGB(),null);
             Shader sameShader=borrowed.makeShader(FilterTileMode.CLAMP,FilterTileMode.CLAMP,SamplingMode.LINEAR,null);
             Surface target=Surface.makeRenderTarget(context,false,ImageInfo.makeN32Premul(16,16))) {
            for(int frame=0;frame<2;frame++) {
                if(frame==1) {
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
                    GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,16,16,
                            GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,solidPixels(16,220,20,70));
                    context.resetGLAll();
                }
                target.getCanvas().clear(0);
                GlassLiquidSurface.drawOn(target.getCanvas(),sameShader,0,0,16,16,4,
                        1,1,0,0,1,1,0,1,0,0,.06f);
                context.flushAndSubmit(true);
                byte[] rgb=pixels(target,16,16);
                assertEquals(frame==0 ? 50 : 220,channel(rgb,16,8,8,2),2);
                assertEquals(frame==0 ? 120 : 20,channel(rgb,16,8,8,1),2);
                assertEquals(frame==0 ? 200 : 70,channel(rgb,16,8,8,0),2);
            }
            System.out.println("LiquidGlass live external GL texture: refreshed through the same cached shader");
        } finally {
            GL11.glDeleteTextures(texture);
            context.resetGLAll();
        }
    }

    private static java.nio.ByteBuffer solidPixels(int size,int red,int green,int blue) {
        java.nio.ByteBuffer bytes=java.nio.ByteBuffer.allocateDirect(size*size*4);
        for(int i=0;i<size*size;i++) bytes.put((byte)red).put((byte)green).put((byte)blue).put((byte)255);
        bytes.flip(); return bytes;
    }

    private static void referencePhoto(DirectContext context) throws Exception {
        // Local ZIP asset for review only; not copied into shipped resources.
        Path photo=Path.of("..","..",".scratch","LiquidGlass-master-20261001","LiquidGlass-master",
                "assets","textures","background-cubes.jpg");
        if (!Files.exists(photo)) return;
        try (Image original=Image.makeFromEncoded(Files.readAllBytes(photo));
             Surface background=Surface.makeRasterN32Premul(960,600)) {
            background.getCanvas().drawImageRect(original,Rect.makeWH(original.getWidth(),original.getHeight()),
                    Rect.makeWH(960,600));
            try (Image image=background.makeImageSnapshot();
                 Shader source=image.makeShader(FilterTileMode.CLAMP,FilterTileMode.CLAMP,SamplingMode.LINEAR,null);
                 Surface target=Surface.makeRenderTarget(context,false,ImageInfo.makeN32Premul(960,600))) {
                target.getCanvas().drawImage(image,0,0);
                // Canonical reference squircle, identical ZIP defaults, no blur/noise.
                GlassLiquidSurface.drawOn(target.getCanvas(),source,310,160,320,320,90,1,1,0,0,1,1,3,1,0,.3f,.06f);
                context.flushAndSubmit(true); save(target,Path.of("build","liquid-glass-reference-gpu.png"));
                target.getCanvas().drawImage(image,0,0);
                // Actual widget silhouette: no missing corners, same lens curve on a wide panel and capsule.
                GlassLiquidSurface.drawOn(target.getCanvas(),source,110,125,740,365,20,1,1,0,0,1,1,0,1,.006f,.3f,.06f);
                GlassLiquidSurface.drawOn(target.getCanvas(),source,365,30,230,52,26,1,1,0,0,1,1,0,1,.006f,.3f,.06f);
                context.flushAndSubmit(true); save(target,Path.of("build","liquid-glass-panel-gpu.png"));
            }
        }
    }

    private static void save(Surface target,Path path) throws Exception {
        Files.createDirectories(path.getParent());
        try (Bitmap readback=new Bitmap()) {
            readback.allocPixels(ImageInfo.makeN32Premul(target.getWidth(),target.getHeight()));
            assertTrue(target.readPixels(readback,0,0));
            try (Image image=Image.makeFromBitmap(readback); Data data=EncoderPNG.encode(image)) {
                assertNotNull(data);
                Files.write(path,data.getBytes());
            }
        }
    }
}
