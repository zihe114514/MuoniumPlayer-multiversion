package com.muoniumplayer.core.ncm.music;

import java.util.Arrays;

/** Cached musical evidence used by the Folia planner. */
final class FoliaTrackProfile {
    static final int VERSION = 3;
    int version = VERSION;
    String cacheKey;
    long durationMillis;
    long tailStartMillis;
    float bpm;
    float outroBpm;
    float beatConfidence;
    float outroBeatConfidence;
    float beatPhaseMillis;
    float barPhaseMillis;
    float headBeatPhaseMillis;
    float headBarPhaseMillis;
    int beatsPerBar = 4;
    int introKeyTonic = -1;
    boolean introKeyMajor = true;
    float introKeyConfidence;
    int outroKeyTonic = -1;
    boolean outroKeyMajor = true;
    float outroKeyConfidence;
    float toneLow;
    float toneMid;
    float toneHigh;
    float leadInMillis;
    float outroLandingMillis;
    float headLufs = Float.NaN;
    float tailLufs = Float.NaN;
    float outroSlopeDbPerSecond;
    boolean startsHot = true;
    boolean endsHot = true;
    FoliaStemProfile stems;
    float[] sectionMillis = new float[0];

    boolean isUsable() { return version == VERSION && durationMillis > 0L; }
    float[] sectionsCopy() { return sectionMillis == null ? new float[0] : Arrays.copyOf(sectionMillis, sectionMillis.length); }
}
