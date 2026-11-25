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
    /** y is the line-top origin; use textCenteredY/textCentered for control labels. */
    void text(String text, double x, double y, double size, boolean bold, int color);
    double measure(String text, double size, boolean bold);
    default long textMetricsRevision() { return 0; }

    /** Visible vertical extent relative to text's line-top origin, in painter units.
     * Providers use the font line box for empty/whitespace text or unavailable ink bounds. */
    record TextBounds(double top, double bottom) {
        public TextBounds {
            if (!Double.isFinite(top) || !Double.isFinite(bottom) || bottom < top)
                throw new IllegalArgumentException("Invalid text bounds");
        }
        public double height() { return bottom - top; }
        public double centeredTop(double centerY) { return centerY - (top + bottom) / 2; }
    }
    /** Legacy painters retain their nominal-size line box until they supply real metrics. */
    default TextBounds textBounds(String text, double size, boolean bold) { return new TextBounds(0, size); }
    default double textTopForCenter(String text, double centerY, double size, boolean bold) {
        return textBounds(text, size, bold).centeredTop(centerY);
    }
    default void textCenteredY(String text, double x, double centerY, double size, boolean bold, int color) {
        text(text, x, textTopForCenter(text, centerY, size, bold), size, bold, color);
    }
    default void textCentered(String text, Rect bounds, double size, boolean bold, int color) {
        textCenteredY(text, bounds.x() + (bounds.w() - measure(text, size, bold)) / 2,
                bounds.y() + bounds.h() / 2, size, bold, color);
    }
    default void icon(String icon, double x, double y, double size, int color) { text(icon,x,y,size,false,color); }
    default void vignette(Rect bounds,double cx,double cy,double radius,int color) {}
    default void gradientOutline(Rect bounds,double radius,double width,int top,int bottom) { outline(bounds,radius,width,top); }
    default void polygon(double[] xy,double cornerRadius,int color) {}
    default void roundedPolygon(double[][] loops,double radius,int color) { for(double[] loop:loops) polygon(loop,radius,color); }
    default void glow(Rect bounds, double cx, double cy, double radius, int color, double alpha) {}
    default void gradient(Rect bounds, int topLeft, int topRight, int bottomRight, int bottomLeft) { rect(bounds,0,topLeft); }
}
