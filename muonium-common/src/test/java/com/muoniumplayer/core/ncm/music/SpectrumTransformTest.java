package com.muoniumplayer.core.ncm.music;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpectrumTransformTest {
    @Test
    void silenceProducesSilenceAndNoNan() {
        SpectrumTransform transform = new SpectrumTransform();
        float[] left = new float[SpectrumCapture.SIZE];
        float[] right = new float[SpectrumCapture.SIZE];
        float[] result = new float[SpectrumTransform.BANDS];
        transform.analyze(left,right,44100,result);
        for(float value:result) assertEquals(0f,value,0.0001f);
    }
    @Test
    void tonalInputProducesLocalizedStableEnergy() {
        SpectrumTransform transform = new SpectrumTransform();
        float[] left = new float[SpectrumCapture.SIZE];
        float[] right = new float[SpectrumCapture.SIZE];
        double frequency=440.0;
        for(int i=0;i<left.length;i++) {
            float sample=(float)(.7*Math.sin(2*Math.PI*frequency*i/44100.0));
            left[i]=right[i]=sample;
        }
        float[] result = new float[SpectrumTransform.BANDS];
        transform.analyze(left,right,44100,result);
        int strongest=0;
        for(int i=1;i<result.length;i++) if(result[i]>result[strongest]) strongest=i;
        assertTrue(result[strongest]>.45f);
        assertTrue(SpectrumTransform.centerHz(strongest,44100)>250.0);
        assertTrue(SpectrumTransform.centerHz(strongest,44100)<700.0);
    }
    @Test
    void antiPhaseStereoHasTheSameSpectrumInsteadOfCancelling() {
        SpectrumTransform transform=new SpectrumTransform();
        float[] left=tone(1000,48000,.5),right=left.clone();
        float[] normal=new float[SpectrumTransform.BANDS],inverted=new float[SpectrumTransform.BANDS];
        transform.analyze(left,right,48000,normal);
        for(int i=0;i<right.length;i++) right[i]=-right[i];
        transform.analyze(left,right,48000,inverted);
        assertArrayEquals(normal,inverted,.00001f);
        assertTrue(java.util.stream.IntStream.range(0,normal.length).anyMatch(i->normal[i]>.5));
    }
    @Test
    void rightOnlyAudioIsVisibleAndNoiseFloorIsNotAutoAmplified() {
        SpectrumTransform transform=new SpectrumTransform();
        float[] silence=new float[SpectrumCapture.SIZE];
        float[] right=tone(4000,44100,.5);
        float[] result=new float[SpectrumTransform.BANDS];
        transform.analyze(silence,right,44100,result);
        assertTrue(java.util.stream.IntStream.range(0,result.length).anyMatch(i->result[i]>.5));
        transform.analyze(tone(440,44100,.000001),silence,44100,result);
        for(float value:result) assertEquals(0f,value,.0001);
    }
    @Test
    void changingSampleRateStillMapsFrequencyRatherThanSampleIndex() {
        for(int rate:new int[]{32000,44100,48000,96000}) {
            SpectrumTransform transform=new SpectrumTransform();
            float[] samples=tone(6000,rate,.6),result=new float[SpectrumTransform.BANDS];
            transform.analyze(samples,samples,rate,result);
            int strongest=0;for(int i=1;i<result.length;i++) if(result[i]>result[strongest])strongest=i;
            double frequency=SpectrumTransform.centerHz(strongest,rate);
            assertTrue(frequency>4800 && frequency<7200,"rate="+rate+" peak="+frequency);
        }
    }
    @Test
    void invalidSampleValuesRemainFiniteAndBounded() {
        SpectrumTransform transform=new SpectrumTransform();
        float[] input=tone(440,44100,.2);
        input[0]=Float.NaN;input[1]=Float.POSITIVE_INFINITY;
        float[] result=new float[SpectrumTransform.BANDS];transform.analyze(input,input,44100,result);
        for(float value:result)assertTrue(Float.isFinite(value)&&value>=0&&value<=1);
    }
    private static float[] tone(double hz,int rate,double amplitude) {
        float[] result=new float[SpectrumCapture.SIZE];
        for(int i=0;i<result.length;i++)result[i]=(float)(amplitude*Math.sin(2*Math.PI*hz*i/rate));
        return result;
    }
    @Test
    void dcIsRemovedBeforeFft() {
        SpectrumTransform transform = new SpectrumTransform();
        float[] left = new float[SpectrumCapture.SIZE];
        float[] right = new float[SpectrumCapture.SIZE];
        java.util.Arrays.fill(left,.5f); java.util.Arrays.fill(right,.5f);
        float[] result = new float[SpectrumTransform.BANDS];
        transform.analyze(left,right,44100,result);
        for(float value:result) assertTrue(value<.02f);
    }
}
