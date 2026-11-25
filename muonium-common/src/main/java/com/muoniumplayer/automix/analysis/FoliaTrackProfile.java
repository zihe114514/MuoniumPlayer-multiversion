package com.muoniumplayer.automix.analysis;

import com.muoniumplayer.automix.inference.FoliaStemProfile;

import java.util.Arrays;

/** Folia 规划器使用的缓存音乐证据。 */
public final class FoliaTrackProfile {
    static final int VERSION = 6;
    public int version = VERSION;
    public String cacheKey;
    public long durationMillis;
    public long tailStartMillis;
    public float bpm;
    public float outroBpm;
    public float beatConfidence;
    public float outroBeatConfidence;
    public float beatPhaseMillis;
    public float barPhaseMillis;
    public float headBeatPhaseMillis;
    public float headBarPhaseMillis;
    public int beatsPerBar = 4;
    public int introKeyTonic = -1;
    public boolean introKeyMajor = true;
    public float introKeyConfidence;
    public int outroKeyTonic = -1;
    public boolean outroKeyMajor = true;
    public float outroKeyConfidence;
    public float toneLow;
    public float toneMid;
    public float toneHigh;
    public float leadInMillis;
    public float outroLandingMillis;
    public float headLufs = Float.NaN;
    public float tailLufs = Float.NaN;
    public float outroSlopeDbPerSecond;
    public boolean startsHot = true;
    public boolean endsHot = true;
    public FoliaStemProfile stems;
    public float[] sectionMillis = new float[0];

    public boolean isUsable() { return version == VERSION && durationMillis > 0L; }
    public float[] sectionsCopy() { return sectionMillis == null ? new float[0] : Arrays.copyOf(sectionMillis, sectionMillis.length); }
}
