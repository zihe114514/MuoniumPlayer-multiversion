package com.muoniumplayer.configskija.ui;

import com.muoniumplayer.configskija.ui.Geometry.Rect;

/** Drawing goes into the canvas supplied by MuoniumPlayer's existing Skija frame. */
public interface Painter {
    void save();
    void restore();
    default void layer(Rect bounds,double alpha) { save(); }
    default void shadow(Rect bounds,double radius,double sigma,double spread,double dx,double dy,int color) {}
    default void image(byte[] encoded,Rect bounds,boolean nearest) {}
    void translate(double x, double y);
    void scale(double x, double y);
    void clip(Rect bounds, double radius);
    void rect(Rect bounds, double radius, int color);
    void outline(Rect bounds, double radius, double width, int color);
    void line(double x1, double y1, double x2, double y2, double width, int color);
    void text(String text, double x, double y, double size, boolean bold, int color);
    double measure(String text, double size, boolean bold);
    default void icon(String icon, double x, double y, double size, int color) { text(icon,x,y,size,false,color); }
    default void vignette(Rect bounds,double cx,double cy,double radius,int color) {}
    default void gradientOutline(Rect bounds,double radius,double width,int top,int bottom) { outline(bounds,radius,width,top); }
    default void polygon(double[] xy,double cornerRadius,int color) {}
    default void roundedPolygon(double[][] loops,double radius,int color) { for(double[] loop:loops) polygon(loop,radius,color); }
    default void glow(Rect bounds, double cx, double cy, double radius, int color, double alpha) {}
    default void gradient(Rect bounds, int topLeft, int topRight, int bottomRight, int bottomLeft) { rect(bounds,0,topLeft); }
}
