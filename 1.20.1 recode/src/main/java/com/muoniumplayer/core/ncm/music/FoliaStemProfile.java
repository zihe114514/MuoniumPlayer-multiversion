package com.muoniumplayer.core.ncm.music;

/** Offline stem evidence produced by HTDemucs for one analysis window. */
final class FoliaStemProfile {
    static final int VERSION = 1;
    int version = VERSION;
    long windowStartMillis;
    long windowLengthMillis;
    long vocalStartMillis = -1L;
    long vocalEndMillis = -1L;
    long sustainedVocalEndMillis = -1L;
    long drumEndMillis = -1L;
    long bassEndMillis = -1L;
    float confidence;

    boolean isUsable() {
        return version == VERSION && windowLengthMillis > 0L && confidence >= .12f;
    }
}