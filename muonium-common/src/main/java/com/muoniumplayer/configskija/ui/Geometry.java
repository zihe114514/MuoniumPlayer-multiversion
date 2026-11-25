package com.muoniumplayer.configskija.ui;

public final class Geometry {
    private Geometry() {}
    public record Point(double x, double y) {}
    public record Rect(double x, double y, double w, double h) {
        public boolean contains(double px, double py) { return px >= x && py >= y && px < x+w && py < y+h; }
        public double right() { return x+w; }
        public double bottom() { return y+h; }
        public Rect inset(double p) { return new Rect(x+p,y+p,Math.max(0,w-2*p),Math.max(0,h-2*p)); }
        public boolean intersects(Rect other) { return x < other.right() && right() > other.x && y < other.bottom() && bottom() > other.y; }
    }
    /** Original Shell.kt is exactly 1391 x 700 dp. The inverse is also used for all hit tests. */
    public record Viewport(double x, double y, double scale, double width, double height) {
        public static Viewport fit(double width, double height, double preferenceScale) {
            return original(width,height,1,preferenceScale,false,1);
        }
        /** Provider.kt: both density stages, 90% window fit, em grid snapping, and the user's percent. */
        public static Viewport original(double width,double height,double density,double userScale,boolean snap,double physicalPerLayout) {
            density=Math.max(.01,density);physicalPerLayout=Math.max(.01,physicalPerLayout);
            double first=Math.max(.25,Math.min(1,Math.min(width*.9/(1391*density),height*.9/(700*density))));
            double adjusted=density*first;
            double headroom=Math.min(width*.9/(1391*adjusted),height*.9/(700*adjusted));
            double anchor=14*adjusted*physicalPerLayout;
            double base=snap?snapScaleToPixelGrid(anchor,headroom):headroom;
            double percent=Geometry.clamp(userScale,.5,2),user=base*percent;
            double scale=percent==1?base:snap?user*snapScaleToNearestStep(anchor*user):user;
            double finalScale=adjusted*scale;
            return new Viewport((width-1391*finalScale)/2,(height-700*finalScale)/2,finalScale,1391,700);
        }
        static double snapScaleToPixelGrid(double anchor,double max) {
            if(anchor<=0) return 1;
            double fits=Math.floor(anchor*max/5)*5,nearest=Math.rint(anchor/5)*5;
            double scale=Math.max(10,Math.max(nearest,fits))/anchor;return Math.min(scale,max);
        }
        static double snapScaleToNearestStep(double anchor) { return anchor<=0?1:Math.max(10,Math.rint(anchor/5)*5)/anchor; }
        public Viewport scaledAboutCenter(double amount) {
            double next=scale*amount;return new Viewport(x+(1391*scale-1391*next)/2,y+(700*scale-700*next)/2,next,1391,700);
        }
        public Point local(double screenX, double screenY) { return new Point((screenX-x)/scale,(screenY-y)/scale); }
        public Point screen(double localX, double localY) { return new Point(x+localX*scale,y+localY*scale); }
    }
    public static double clamp(double v, double lo, double hi) { return Math.max(lo,Math.min(hi,v)); }
}
