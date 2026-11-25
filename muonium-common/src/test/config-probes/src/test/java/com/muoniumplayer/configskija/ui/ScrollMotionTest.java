package com.muoniumplayer.configskija.ui;

/** Pure Java 17 deterministic motion regressions; no renderer or Minecraft dependencies. */
public final class ScrollMotionTest {
    private static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static void near(double a,double b,String why){check(Math.abs(a-b)<1e-8,why+": "+a+" / "+b);}
    public static void main(String[] args){
        for(int hz:new int[]{30,60,144,240}){
            Motion.Scroll scroll=new Motion.Scroll();scroll.range(1000,0);scroll.wheel(440,0,true);
            for(int i=1;i<=hz;i++)scroll.sample((double)i/hz,true);
            near(scroll.sample(1,true),440,"same elapsed time at "+hz+" Hz");
        }
        Motion.Scroll a=new Motion.Scroll(),b=new Motion.Scroll();a.range(1000,0);b.range(1000,0);
        a.wheel(440,0,true);b.wheel(440,0,true);
        for(int i=1;i<=5;i++)a.sample(i*.01,true);
        near(a.sample(.05,true),b.sample(.05,true),"sampling is independent of frame count");
        double current=a.sample(.05,true);near(a.wheel(-44,.05,true),current,"reverse has no position discontinuity");
        check(a.sample(.06,true)<current,"reverse moves immediately without spending old queued distance");
        a.wheel(-1e9,.06,true);near(a.target(),0,"lower target clamp");
        for(int i=0;i<100;i++){double value=a.sample(.06+i*.01,true);check(value>=0&&value<=1000,"sample bounded");}
        a.wheel(1e9,2,true);near(a.target(),1000,"upper target clamp");
        near(a.sample(20,true),1000,"long stalled frame settles");
        near(a.range(100,20),100,"shrinking content clamps immediately");
        near(a.jump(37,20),37,"drag jumps precisely");near(a.sample(21,true),37,"drag cancels inertia");
        near(a.wheel(44,21,false),81,"disabled animations move immediately");
        near(a.wheel(Double.NaN,21,true),81,"invalid input does not poison state");
        Motion.Scroll events=new Motion.Scroll();events.range(1000,0);
        for(int i=0;i<10;i++)events.wheel(44,0,true);
        near(events.target(),440,"wheel bursts retain all deltas");near(events.sample(2,true),440,"wheel burst settles");
        Motion.Entrance entrance=new Motion.Entrance();entrance.start(0);
        near(entrance.progress(0,.3,true),0,"transition starts");check(entrance.progress(.1,.3,true)>0,"transition advances");
        near(entrance.progress(5,.3,true),1,"stalled transition completes");entrance.start(5);
        near(entrance.progress(5,.3,false),1,"disabled transition completes immediately");
        near(entrance.progress(5,.3,true),1,"reenabling does not replay disabled transition");
        System.out.println("ScrollMotionTest PASS: "+checks+" checks");
    }
}
