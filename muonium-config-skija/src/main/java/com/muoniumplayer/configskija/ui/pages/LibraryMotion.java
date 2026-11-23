package com.muoniumplayer.configskija.ui.pages;

import com.muoniumplayer.configskija.ui.*;
import java.util.*;

/** Compose's critically damped spring, integrated analytically and retaining velocity on retarget. */
final class LibraryMotion {
    private static final Map<PageContext,Map<String,Track>> contexts=new WeakHashMap<>();
    private static final class Track { double value,velocity,time,target,seen;Track(double value,double time){this.value=this.target=value;this.time=this.seen=time;} }
    private LibraryMotion(){}
    static double animate(PageContext c,String key,double target,double seconds,Motion.Easing easing){
        return easing==Motion.Easing.SPRING?spring(c,key,target,1500,.01):c.animate(key,target,seconds,easing);
    }
    static double spring(PageContext c,String key,double target,double stiffness,double threshold){
        String scoped=c.route().key()+"/"+key;Map<String,Track> tracks=contexts.computeIfAbsent(c,k->new HashMap<>());double time=c.time();Track t=tracks.computeIfAbsent(scoped,k->new Track(target,time));
        double dt=Math.max(0,time-t.time),omega=Math.sqrt(stiffness),distance=t.value-t.target,coefficient=t.velocity+omega*distance,decay=Math.exp(-omega*dt);
        t.value=t.target+(distance+coefficient*dt)*decay;t.velocity=(t.velocity-omega*coefficient*dt)*decay;t.time=t.seen=time;
        t.target=target;if(Math.abs(t.value-target)<threshold&&Math.abs(t.velocity)<threshold*omega){t.value=target;t.velocity=0;}
        if(tracks.size()>2048)tracks.entrySet().removeIf(e->time-e.getValue().seen>10);return t.value;
    }
}
