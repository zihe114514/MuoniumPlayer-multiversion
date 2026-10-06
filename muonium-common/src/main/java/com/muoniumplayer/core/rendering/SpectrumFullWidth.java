package com.muoniumplayer.core.rendering;

import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Rect;
import io.github.humbleui.types.RRect;

/** Continuous bottom spectrum, bounded to 256 columns. One batched path draw; no per-column shaders or full-screen blur. */
final class SpectrumFullWidth implements AutoCloseable {
    private Paint paint;
    private Shader shader;
    private int cachedA,cachedB;
    private float cachedX,cachedWidth,cachedBottom,cachedHeight;

    void draw(Canvas canvas,float[] bands,float width,float bottom,float maxHeight,float opacity,int a,int b) {
        draw(canvas,bands,0f,width,bottom,maxHeight,opacity,a,b,64);
    }

    void draw(Canvas canvas,float[] bands,float x,float width,float bottom,float maxHeight,
              float opacity,int a,int b,int maximumBars) {
        if(canvas==null || width<3f || maxHeight<1f || opacity<.005f || bands==null || bands.length==0) return;
        if(paint==null) paint=new Paint().setAntiAlias(true).setMode(PaintMode.FILL);
        a=0xFF000000|(a&0xFFFFFF);b=0xFF000000|(b&0xFFFFFF);
        if(shader==null || a!=cachedA || b!=cachedB || x!=cachedX || width!=cachedWidth || bottom!=cachedBottom || maxHeight!=cachedHeight) {
            paint.setShader(null);
            if(shader!=null) {shader.close();shader=null;}
            // A restrained brighter crest/darker translucent foot, multiplied by the theme/cover gradient.
            try(Shader horizontal=Shader.makeLinearGradient(x,bottom,x+width,bottom,new int[]{a,b});
                Shader vertical=Shader.makeLinearGradient(0f,bottom-maxHeight,0f,bottom,
                        new int[]{0xFFFFFFFF,0xECDDE3EB,0xB88C96A5},new float[]{0f,.42f,1f})) {
                shader=Shader.makeBlend(BlendMode.MODULATE,horizontal,vertical);
            }
            cachedA=a;cachedB=b;cachedX=x;cachedWidth=width;cachedBottom=bottom;cachedHeight=maxHeight;
        }
        int count=SpectrumVisualMath.fullBars(width,maximumBars);
        float barWidth=SpectrumVisualMath.fullBarWidth(width,count);
        canvas.save();
        // Pack every rounded bar into one path so a dense spectrum still costs one draw call.
        try(PathBuilder builder=new PathBuilder()) {
            canvas.clipRect(Rect.makeXYWH(x,bottom-maxHeight,width,maxHeight));
            for(int index=0;index<count;index++) {
                float value=SpectrumVisualMath.fullLevel(bands,index,count);
                if(value<.001f) continue;
                float h=Math.max(1f,value*maxHeight);
                float barX=x+index*(barWidth+1.5f);
                builder.addRRect(RRect.makeXYWH(barX,bottom-h,barWidth,h,Math.min(1.6f,Math.min(h,barWidth)*.25f)));
            }
            try(Path path=builder.snapshot()) {
                paint.setShader(shader);
                paint.setColor(0xFFFFFFFF);
                paint.setAlphaf(SpectrumVisualMath.finite01(opacity));
                canvas.drawPath(path,paint);
            }
        } finally {canvas.restore();}
    }
    @Override public void close() {
        if(paint!=null) {paint.setShader(null);paint.close();paint=null;}
        if(shader!=null) {shader.close();shader=null;}
    }
}
