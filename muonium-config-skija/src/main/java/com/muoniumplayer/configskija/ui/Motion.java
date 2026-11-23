package com.muoniumplayer.configskija.ui;

import java.util.HashMap;
import java.util.Map;

/** Monotonic animation state. Targets can change mid-flight without discontinuities. */
public final class Motion {
    public enum Easing { LINEAR, FAST_OUT_SLOW_IN, LINEAR_OUT_SLOW_IN, FAST_OUT_LINEAR_IN, EASE_OUT, EASE_IN_OUT, EASE_OUT_EXPO, EASE_OUT_CUBIC, SPRING }
    private static final class Track { double from,to,start,duration,last;Easing easing; }
    private final Map<String,Track> tracks=new HashMap<>();
    public double value(String key,double target,double seconds,Easing easing,double time) {
        Track t=tracks.get(key);
        if(t==null) { t=new Track();t.from=t.to=target;t.start=time;t.duration=seconds;t.easing=easing;tracks.put(key,t); }
        if(Double.compare(t.to,target)!=0) { t.from=sample(t,time);t.to=target;t.start=time;t.duration=Math.max(.0001,seconds);t.easing=easing; }
        t.last=time;return sample(t,time);
    }
    public void seed(String key,double value,double time) { Track t=new Track();t.from=t.to=value;t.start=time;t.last=time;t.duration=.0001;t.easing=Easing.LINEAR;tracks.put(key,t); }
    public static double spring(double from,double target,double seconds,double stiffness,double dampingRatio,double velocity) {
        if(seconds<=0) return from;double omega=Math.sqrt(stiffness),distance=from-target;
        if(dampingRatio==1) return target+(distance+(velocity+omega*distance)*seconds)*Math.exp(-omega*seconds);
        if(dampingRatio<1) { double damped=omega*Math.sqrt(1-dampingRatio*dampingRatio);return target+Math.exp(-dampingRatio*omega*seconds)*(distance*Math.cos(damped*seconds)+(velocity+dampingRatio*omega*distance)/damped*Math.sin(damped*seconds)); }
        double root=Math.sqrt(dampingRatio*dampingRatio-1),r1=-omega*(dampingRatio-root),r2=-omega*(dampingRatio+root);double a=(velocity-r2*distance)/(r1-r2),b=distance-a;return target+a*Math.exp(r1*seconds)+b*Math.exp(r2*seconds);
    }
    public void clear() { tracks.clear(); }
    public void prune(double time) { if(tracks.size()>1024) tracks.entrySet().removeIf(e->time-e.getValue().last>10); }
    private static double sample(Track t,double now) { double x=Geometry.clamp((now-t.start)/t.duration,0,1);return t.from+(t.to-t.from)*ease(t.easing,x); }
    public static double ease(Easing easing,double x) {
        x=Geometry.clamp(x,0,1);
        return switch(easing) {
            case LINEAR -> x;case FAST_OUT_SLOW_IN -> bezier(x,.4,0,.2,1);case LINEAR_OUT_SLOW_IN -> bezier(x,0,0,.2,1);case FAST_OUT_LINEAR_IN -> bezier(x,.4,0,1,1);
            case EASE_OUT -> bezier(x,0,0,.58,1);case EASE_IN_OUT -> bezier(x,.42,0,.58,1);
            case EASE_OUT_EXPO -> x>=1?1:1-Math.pow(2,-10*x);
            case EASE_OUT_CUBIC -> 1-Math.pow(1-x,3);
            case SPRING -> 1-(1+Math.sqrt(1500)*x)*Math.exp(-Math.sqrt(1500)*x);
        };
    }
    public static double bezier(double x,double x1,double y1,double x2,double y2) { double lo=0,hi=1;for(int i=0;i<24;i++) { double t=(lo+hi)/2,u=1-t,px=3*u*u*t*x1+3*u*t*t*x2+t*t*t;if(px<x) lo=t;else hi=t; }double t=(lo+hi)/2,u=1-t;return 3*u*u*t*y1+3*u*t*t*y2+t*t*t; }
}
