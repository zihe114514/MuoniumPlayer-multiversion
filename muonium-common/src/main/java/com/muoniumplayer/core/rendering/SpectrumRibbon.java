package com.muoniumplayer.core.rendering;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.PaintStrokeCap;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Rect;

/** One batched path / two bounded draws. Paint and gradient live for the ribbon's lifetime. */
final class SpectrumRibbon implements AutoCloseable {
    private Paint paint;
    private Shader shader;
    private int cachedA,cachedB;
    private float cachedX,cachedWidth;

    void draw(Canvas canvas,float[] values,int first,int length,float x,float baseline,float width,
              float maxHeight,float opacity,int a,int b) {
        if(canvas==null || width<6f || maxHeight<1f || opacity<.005f || length<1) return;
        if(paint==null) paint=new Paint().setAntiAlias(true).setMode(PaintMode.STROKE).setStrokeCap(PaintStrokeCap.ROUND);
        a=0xFF000000|(a&0xFFFFFF); b=0xFF000000|(b&0xFFFFFF);
        if(shader==null || cachedA!=a || cachedB!=b || cachedX!=x || cachedWidth!=width) {
            // Detach before closing the old native shader; retained paint never points at a freed handle.
            paint.setShader(null);
            if(shader!=null) shader.close();
            shader=Shader.makeLinearGradient(x,baseline,x+width,baseline,new int[]{a,b});
            cachedA=a; cachedB=b; cachedX=x; cachedWidth=width;
        }
        int count=SpectrumVisualMath.bars(width,length);
        float step=width/count;
        float stroke=Math.max(.8f,Math.min(2.8f,step*.43f));
        canvas.save();
        try (PathBuilder builder=new PathBuilder()) {
            canvas.clipRect(Rect.makeXYWH(x-1f,baseline-maxHeight-2f,width+2f,maxHeight+4f));
            for(int bar=0;bar<count;bar++) {
                int from=first+bar*length/count, to=first+(bar+1)*length/count;
                float value=0f;
                for(int band=from;band<to;band++) value=Math.max(value,SpectrumVisualMath.finite01(values[band]));
                if(value<.008f) continue;
                float edge=Math.min(1f,Math.min(bar+1,count-bar)/3f);
                float h=Math.max(stroke,value*maxHeight*edge);
                float cx=x+(bar+.5f)*step;
                builder.moveTo(cx,baseline-stroke*.5f);
                builder.lineTo(cx,baseline-h+stroke*.5f);
            }
            try(Path path=builder.snapshot()) {
                paint.setShader(shader);
                paint.setColor(0xFFFFFFFF);
                paint.setAlphaf(SpectrumVisualMath.finite01(opacity)*.08f);
                paint.setStrokeWidth(stroke+1.4f);
                canvas.drawPath(path,paint);
                paint.setAlphaf(SpectrumVisualMath.finite01(opacity));
                paint.setStrokeWidth(stroke);
                canvas.drawPath(path,paint);
            }
        } finally { canvas.restore(); }
    }
    @Override public void close() {
        if(paint!=null) {paint.setShader(null);paint.close();paint=null;}
        if(shader!=null) {shader.close();shader=null;}
    }
}
