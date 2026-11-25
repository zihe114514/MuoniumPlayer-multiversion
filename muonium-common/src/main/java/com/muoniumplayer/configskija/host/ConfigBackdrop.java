package com.muoniumplayer.configskija.host;

import com.muoniumplayer.render.skija.GlassBackdrop;
import com.muoniumplayer.render.skija.SkijaGfx;
import io.github.humbleui.skija.*;
import io.github.humbleui.types.Rect;
import java.nio.*;

/** Exact source four-tap downsample + 7-tap horizontal/vertical kernel, using the host's DirectContext/capture. */
final class ConfigBackdrop implements AutoCloseable {
    private final RuntimeEffect down=RuntimeEffect.makeForShader("uniform shader source; uniform float4 sizes; half4 main(float2 p) { float2 uv=p/sizes.zw*sizes.xy; half3 c=source.eval(uv+float2(-1,-1)).rgb+source.eval(uv+float2(1,-1)).rgb+source.eval(uv+float2(-1,1)).rgb+source.eval(uv+float2(1,1)).rgb;return half4(c*.25,1); }");
    private final RuntimeEffect blur=RuntimeEffect.makeForShader("uniform shader source; uniform float4 direction; half4 main(float2 p) { float2 d=direction.xy; half3 c=source.eval(p).rgb*.19967563; c+=(source.eval(p-d*1.4073334).rgb+source.eval(p+d*1.4073334).rgb)*.29732251; c+=(source.eval(p-d*3.2942150).rgb+source.eval(p+d*3.2942150).rgb)*.09184834; c+=(source.eval(p-d*5.2018132).rgb+source.eval(p+d*5.2018132).rgb)*.01099133;return half4(c,1); }");
    private final Paint paint=new Paint().setAntiAlias(true);
    private Surface a,b,c;
    private Image result;
    private int width,height;
    private long lastBuilt;
    private DirectContext context;
    private void ensure(int width,int height) {
        if(this.width==width&&this.height==height&&context==SkijaGfx.context())return;
        clearTargets();this.width=width;this.height=height;context=SkijaGfx.context();
        ImageInfo info=ImageInfo.makeN32Premul(width,height);a=Surface.makeRenderTarget(context,false,info);b=Surface.makeRenderTarget(context,false,info);c=Surface.makeRenderTarget(context,false,info);
        if(a==null||b==null||c==null)throw new IllegalStateException("Host Skija context could not create UI blur targets");
    }
    void draw(double layoutWidth,double layoutHeight,double alpha,boolean paused) {
        Image raw=GlassBackdrop.image();if(raw==null||alpha<=0)return;
        ensure(Math.max(1,(raw.getWidth()+3)/4),Math.max(1,(raw.getHeight()+3)/4));long now=System.nanoTime();
        if(result==null||!paused||now-lastBuilt>=50_000_000L) {
            if(result!=null){result.close();result=null;}
            pass(down,raw,a,new float[]{raw.getWidth(),raw.getHeight(),width,height});
            try(Image first=a.makeImageSnapshot()){pass(blur,first,b,new float[]{1,0,0,0});}
            try(Image second=b.makeImageSnapshot()){pass(blur,second,c,new float[]{0,1,0,0});}
            result=c.makeImageSnapshot();lastBuilt=now;
        }
        paint.setShader(null).setAlphaf((float)Math.max(0,Math.min(1,alpha)));
        SkijaGfx.canvas().drawImageRect(result,Rect.makeWH(width,height),Rect.makeWH((float)layoutWidth,(float)layoutHeight),SamplingMode.LINEAR,paint,true);paint.setAlphaf(1);
    }
    private void pass(RuntimeEffect effect,Image image,Surface target,float[] uniforms) {
        ByteBuffer values=ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());for(float value:uniforms)values.putFloat(value);
        try(Shader child=image.makeShader(FilterTileMode.CLAMP,FilterTileMode.CLAMP,SamplingMode.LINEAR,null);Data data=Data.makeFromBytes(values.array());Shader shader=effect.makeShader(data,new Shader[]{child})) {
            paint.setShader(shader).setColor(0xffffffff);target.getCanvas().drawRect(Rect.makeWH(width,height),paint);paint.setShader(null);
        }
    }
    private void clearTargets(){if(result!=null){result.close();result=null;}if(a!=null){a.close();a=null;}if(b!=null){b.close();b=null;}if(c!=null){c.close();c=null;}context=null;width=height=0;}
    public void close(){clearTargets();paint.close();down.close();blur.close();}
}
