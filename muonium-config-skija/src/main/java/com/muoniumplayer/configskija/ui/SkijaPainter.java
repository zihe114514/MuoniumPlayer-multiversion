package com.muoniumplayer.configskija.ui;

import com.muoniumplayer.configskija.ui.Geometry.Rect;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorFilter;
import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.RuntimeEffect;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import io.github.humbleui.skija.svg.SVGDOM;
import io.github.humbleui.types.RRect;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** A canvas adapter, not a renderer host: it never creates a GL context, surface or native loader. */
public final class SkijaPainter implements Painter,AutoCloseable,com.muoniumplayer.configskija.ui.pages.AppearancePages.Images,com.muoniumplayer.configskija.ui.pages.AppearancePages.Surfaces,com.muoniumplayer.configskija.ui.notifications.NotificationTypography {
    public interface Text {
        void draw(String text,double x,double y,double size,boolean bold,int color);
        double measure(String text,double size,boolean bold);
        default void drawWeighted(String text,double x,double y,double size,int weight,int color) { draw(text,x,y,size,weight>=600,color); }
        default double measureWeighted(String text,double size,int weight) { return measure(text,size,weight>=600); }
    }
    public record Svg(byte[] bytes,double width,double height) {}
    private record Icon(SVGDOM dom,double width,double height) {}
    private final Supplier<Canvas> canvas;
    private final Text text;
    private final Supplier<Theme> theme;
    private final Map<String,Svg> bytes;
    private final Map<String,Icon> icons=new HashMap<>();
    private RuntimeEffect corners;
    private final Map<String,Image> images=new LinkedHashMap<>(32,.75f,true) {
        protected boolean removeEldestEntry(Map.Entry<String,Image> entry){if(size()<=64)return false;entry.getValue().close();return true;}
    };
    private final Paint fill=new Paint().setAntiAlias(true),stroke=new Paint().setAntiAlias(true).setStroke(true),tint=new Paint();
    private final Map<Integer,ColorFilter> filters=new LinkedHashMap<>() {
        protected boolean removeEldestEntry(Map.Entry<Integer,ColorFilter> entry) { if(size()<=48) return false;entry.getValue().close();return true; }
    };
    private final Map<String,Shader> shaders=new LinkedHashMap<>() {
        protected boolean removeEldestEntry(Map.Entry<String,Shader> entry) { if(size()<=48) return false;entry.getValue().close();return true; }
    };
    public SkijaPainter(Supplier<Canvas> canvas,Text text,Map<String,Svg> bytes) { this(canvas,text,bytes,()->Theme.BUILTIN[0]); }
    public SkijaPainter(Supplier<Canvas> canvas,Text text,Map<String,Svg> bytes,Supplier<Theme> theme) { this.canvas=canvas;this.text=text;this.bytes=bytes;this.theme=theme; }
    public static Map<String,Svg> loadIcons() throws IOException {
        ClassLoader loader=SkijaPainter.class.getClassLoader();String prefix="assets/muoniumconfigskija/";
        Map<String,Svg> out=new HashMap<>();
        try(InputStream index=loader.getResourceAsStream(prefix+"icons.index")) {
            if(index==null) throw new IOException("Missing icon index");
            for(String path:new String(index.readAllBytes(),StandardCharsets.UTF_8).split("\n")) {
                if(path.isBlank()) continue;
                try(InputStream stream=loader.getResourceAsStream(prefix+path.trim())) {
                    if(stream==null) throw new IOException("Missing UI asset: "+path);
                    byte[] data=stream.readAllBytes();String xml=new String(data,StandardCharsets.UTF_8);
                    out.put(path,new Svg(data,dimension(xml,"width",24),dimension(xml,"height",24)));
                }
            }
        }
        return Map.copyOf(out);
    }
    private static double dimension(String xml,String name,double fallback) { var matcher=Pattern.compile("\\b"+name+"=[\"']([0-9.]+)").matcher(xml);return matcher.find()?Double.parseDouble(matcher.group(1)):fallback; }
    public void save() { canvas.get().save(); }
    public void restore() { canvas.get().restore(); }
    public void layer(Rect bounds,double alpha) { canvas.get().saveLayerAlpha(io.github.humbleui.types.Rect.makeXYWH((float)bounds.x(),(float)bounds.y(),(float)bounds.w(),(float)bounds.h()),(int)Math.round(Geometry.clamp(alpha,0,1)*255)); }
    public void shadow(Rect r,double radius,double sigma,double spread,double dx,double dy,int color) {
        Rect cast=new Rect(r.x()-spread+dx,r.y()-spread+dy,r.w()+2*spread,r.h()+2*spread);
        try(ImageFilter filter=ImageFilter.makeBlur((float)sigma,(float)sigma,FilterTileMode.DECAL);Paint paint=new Paint().setAntiAlias(true).setColor(color).setImageFilter(filter)) { canvas.get().drawRRect(rr(cast,radius+spread),paint); }
    }
    public void image(byte[] encoded,Rect bounds,boolean nearest) {
        if(encoded==null||encoded.length==0) return;String fingerprint;try{fingerprint=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(encoded));}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
        Image image=images.computeIfAbsent(fingerprint,key->Image.makeDeferredFromEncodedBytes(encoded));
        canvas.get().drawImageRect(image,io.github.humbleui.types.Rect.makeWH(image.getWidth(),image.getHeight()),io.github.humbleui.types.Rect.makeXYWH((float)bounds.x(),(float)bounds.y(),(float)bounds.w(),(float)bounds.h()),nearest?SamplingMode.DEFAULT:SamplingMode.LINEAR,null,true);
    }
    public void translate(double x,double y) { canvas.get().translate((float)x,(float)y); }
    public void scale(double x,double y) { canvas.get().scale((float)x,(float)y); }
    private RRect rr(Rect r,double radius) { return RRect.makeXYWH((float)r.x(),(float)r.y(),(float)Math.max(0,r.w()),(float)Math.max(0,r.h()),(float)Math.max(0,radius)); }
    public void clip(Rect r,double radius) { canvas.get().clipRRect(rr(r,radius),true); }
    public void rect(Rect r,double radius,int color) { fill.setShader(null).setColor(color);canvas.get().drawRRect(rr(r,radius),fill); }
    public void outline(Rect r,double radius,double width,int color) { stroke.setShader(null).setColor(color).setStrokeWidth((float)width);canvas.get().drawRRect(rr(r.inset(width/2),Math.max(0,radius-width/2)),stroke); }
    public void line(double x1,double y1,double x2,double y2,double width,int color) { stroke.setShader(null).setColor(color).setStrokeWidth((float)width);canvas.get().drawLine((float)x1,(float)y1,(float)x2,(float)y2,stroke); }
    public void text(String value,double x,double y,double size,boolean bold,int color) { text.draw(value,x,y,size,bold,color); }
    public double measure(String value,double size,boolean bold) { return text.measure(value,size,bold); }
    public void icon(String name,double x,double y,double size,int color) {
        String path=name.contains("/")?name:theme.get().iconPath(name);if(!bytes.containsKey(path)&&!name.contains("/"))path="ico/"+name+".svg";Svg svg=bytes.get(path);
        if(svg==null) { text("•",x,y,size,false,color);return; }
        Svg sourceSvg=svg;Icon icon=icons.computeIfAbsent(path,key->{ try(Data data=Data.makeFromBytes(sourceSvg.bytes())) { return new Icon(new SVGDOM(data),sourceSvg.width(),sourceSvg.height()); } });
        ColorFilter filter=filters.computeIfAbsent(color,key->ColorFilter.makeBlend(key,BlendMode.SRC_IN));tint.setColorFilter(filter);
        Canvas c=canvas.get();c.save();try {
            double fit=size/Math.max(icon.width(),icon.height());c.translate((float)(x+(size-icon.width()*fit)/2),(float)(y+(size-icon.height()*fit)/2));c.scale((float)fit,(float)fit);
            c.saveLayer(io.github.humbleui.types.Rect.makeWH((float)icon.width(),(float)icon.height()),tint);
            try { icon.dom().render(c); }finally { c.restore(); }
        }finally { c.restore(); }
    }
    public void gradient(Rect r,int tl,int tr,int br,int bl) {
        if(corners==null) corners=RuntimeEffect.makeForShader("uniform float4 bounds; uniform float4 tl; uniform float4 tr; uniform float4 br; uniform float4 bl; half4 main(float2 p) { float2 t=clamp((p-bounds.xy)/max(bounds.zw,float2(.0001)),0,1); float4 c=mix(mix(tl,tr,t.x),mix(bl,br,t.x),t.y); return half4(c.rgb*c.a,c.a); }");
        String key="corners:"+r+":"+tl+":"+tr+":"+br+":"+bl;
        Shader shader=shaders.computeIfAbsent(key,k->{ ByteBuffer data=ByteBuffer.allocate(80).order(ByteOrder.nativeOrder());data.putFloat((float)r.x()).putFloat((float)r.y()).putFloat((float)r.w()).putFloat((float)r.h());for(int c:new int[]{tl,tr,br,bl}) data.putFloat(((c>>>16)&255)/255f).putFloat(((c>>>8)&255)/255f).putFloat((c&255)/255f).putFloat(((c>>>24)&255)/255f);try(Data bytes=Data.makeFromBytes(data.array())) { return corners.makeShader(bytes,new Shader[0]); } });
        fill.setShader(shader).setColor(0xFFFFFFFF);canvas.get().drawRect(io.github.humbleui.types.Rect.makeXYWH((float)r.x(),(float)r.y(),(float)r.w(),(float)r.h()),fill);fill.setShader(null);
    }
    public void notificationText(String value,double x,double y,double size,int weight,int color) { text.drawWeighted(value,x,y,size,weight,color); }
    public double notificationMeasure(String value,double size,int weight) { return text.measureWeighted(value,size,weight); }
    public void image(String path,Rect bounds) {
        Svg svg=bytes.get(path);if(svg==null) throw new IllegalArgumentException("Missing original image: "+path);
        Icon icon=icons.computeIfAbsent(path,key->{try(Data data=Data.makeFromBytes(svg.bytes())) {return new Icon(new SVGDOM(data),svg.width(),svg.height());}});
        Canvas c=canvas.get();c.save();try {c.clipRect(io.github.humbleui.types.Rect.makeXYWH((float)bounds.x(),(float)bounds.y(),(float)bounds.w(),(float)bounds.h()));c.translate((float)bounds.x(),(float)bounds.y());c.scale((float)(bounds.w()/icon.width()),(float)(bounds.h()/icon.height()));icon.dom().render(c);}finally{c.restore();}
    }
    public void settingDecoration(Rect r,double radius,int borderColor,int textColor) {
        save();try {clip(r,radius);vignette(r,r.x()+r.w()/2,r.y()+r.h()/2,Math.min(r.w(),r.h())*.9,textColor);}finally{restore();}
        gradientOutline(r,radius,1,borderColor,borderColor&0xffffff);
    }
    public void vignette(Rect r,double cx,double cy,double radius,int color) {
        String key="vignette:"+cx+":"+cy+":"+radius+":"+color;
        Shader shader=shaders.computeIfAbsent(key,k->Shader.makeRadialGradient((float)cx,(float)cy,(float)Math.max(.001,radius),new int[]{color&0xffffff,Theme.alpha(color,.02),Theme.alpha(color,.05)},new float[]{0,.5f,1}));
        fill.setShader(shader).setColor(0xffffffff);canvas.get().drawRect(io.github.humbleui.types.Rect.makeXYWH((float)r.x(),(float)r.y(),(float)r.w(),(float)r.h()),fill);fill.setShader(null);
    }
    public void gradientOutline(Rect r,double radius,double width,int top,int bottom) {
        String key="stroke-gradient:"+r+":"+top+":"+bottom;
        Shader shader=shaders.computeIfAbsent(key,k->Shader.makeLinearGradient((float)r.x(),(float)r.y(),(float)r.x(),(float)r.bottom(),new int[]{top,bottom}));stroke.setShader(shader).setColor(0xffffffff).setStrokeWidth((float)width);canvas.get().drawRRect(rr(r.inset(width/2),Math.max(0,radius-width/2)),stroke);stroke.setShader(null);
    }
    public void polygon(double[] xy,double radius,int color) { roundedPolygon(new double[][]{xy},radius,color); }
    public void roundedPolygon(double[][] loops,double radius,int color) {
        try(io.github.humbleui.skija.PathBuilder builder=new io.github.humbleui.skija.PathBuilder()) {
            for(double[] points:loops) {
                int count=points.length/2;if(count<3)continue;
                if(radius<=0) {builder.moveTo((float)points[0],(float)points[1]);for(int i=1;i<count;i++)builder.lineTo((float)points[i*2],(float)points[i*2+1]);builder.closePath();continue;}
                double[] start=new double[count*2],end=new double[count*2],distance=new double[count];
                for(int i=0;i<count;i++) {int prev=(i+count-1)%count,next=(i+1)%count;double x=points[i*2],y=points[i*2+1],ax=points[prev*2]-x,ay=points[prev*2+1]-y,bx=points[next*2]-x,by=points[next*2+1]-y,al=Math.hypot(ax,ay),bl=Math.hypot(bx,by);if(al<.00001||bl<.00001){start[i*2]=end[i*2]=x;start[i*2+1]=end[i*2+1]=y;continue;}double cosine=Geometry.clamp((ax*bx+ay*by)/(al*bl),-1,1),theta=Math.acos(cosine),d=Math.min(Math.min(al/2,bl/2),radius/Math.max(.00001,Math.tan(theta/2)));distance[i]=d*Math.tan(theta/2);start[i*2]=x+ax/al*d;start[i*2+1]=y+ay/al*d;end[i*2]=x+bx/bl*d;end[i*2+1]=y+by/bl*d;}
                builder.moveTo((float)start[0],(float)start[1]);for(int i=0;i<count;i++) {if(i>0)builder.lineTo((float)start[i*2],(float)start[i*2+1]);if(distance[i]>.00001)builder.tangentArcTo((float)points[i*2],(float)points[i*2+1],(float)end[i*2],(float)end[i*2+1],(float)distance[i]);else builder.lineTo((float)end[i*2],(float)end[i*2+1]);}builder.closePath();
            }
            try(io.github.humbleui.skija.Path path=builder.build()) {fill.setShader(null).setColor(color);canvas.get().drawPath(path,fill);}
        }
    }
    public void glow(Rect bounds,double cx,double cy,double radius,int color,double alpha) {
        String key="glow:"+cx+":"+cy+":"+radius+":"+color+":"+alpha;
        Shader shader=shaders.computeIfAbsent(key,k->{ float[] stops={0,.2f,.4f,.5f,.6f,.7f,.8f,1};double[] amounts={.95,.77,.42,.25,.13,.055,.018,0};int[] colors=new int[stops.length];for(int i=0;i<colors.length;i++) colors[i]=Theme.alpha(color,alpha*amounts[i]);return Shader.makeRadialGradient((float)cx,(float)cy,(float)radius,colors,stops); });
        fill.setShader(shader).setColor(0xFFFFFFFF);canvas.get().drawRect(io.github.humbleui.types.Rect.makeXYWH((float)bounds.x(),(float)bounds.y(),(float)bounds.w(),(float)bounds.h()),fill);fill.setShader(null);
    }
    public void close() { fill.close();stroke.close();tint.close();for(Icon i:icons.values()) i.dom().close();icons.clear();for(ColorFilter f:filters.values()) f.close();filters.clear();for(Shader s:shaders.values()) s.close();shaders.clear();for(Image image:images.values()) image.close();images.clear();if(corners!=null) { corners.close();corners=null; } }
}
