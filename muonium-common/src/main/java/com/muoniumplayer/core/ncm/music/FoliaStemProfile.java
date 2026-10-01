package com.muoniumplayer.core.ncm.music;

/** 由 HTDemucs 为一个分析窗口生成的离线音轨分离证据。 */
final class FoliaStemProfile {
    static final int VERSION = 1;
    /** 分离素材的固定采样率：模型只认这个数，素材缓存也按它落盘。 */
    static final int MATERIAL_RATE = 44_100;
    /**
     * 可信的人声投影比例区间。落在区间外说明素材和原曲里的人声对不上（分离失败、
     * 素材错位、或者这首歌根本没有可减的人声），这时候减下去会把伴奏一起削掉，
     * 所以宁可不用素材、退回带限中央声道抵消器。
     */
    static final float MIN_PROJECTION = .7f;
    static final float MAX_PROJECTION = 1.6f;

    int version = VERSION;
    long windowStartMillis;
    long windowLengthMillis;
    long vocalStartMillis = -1L;
    long vocalEndMillis = -1L;
    long sustainedVocalEndMillis = -1L;
    long drumEndMillis = -1L;
    long bassEndMillis = -1L;
    float confidence;
    /**
     * 人声素材相对原曲里那一份人声的实际增益（最小二乘投影系数）。
     * {@code 0} 是「这份 profile 是素材功能上线之前写的」，不是测出来的值。
     */
    float vocalProjection;
    /** 落盘素材的帧数；{@code <= 0} 表示这次分析没写素材（出场侧就不写）。 */
    long materialFrames;

    boolean isUsable() {
        return version == VERSION && windowLengthMillis > 0L && confidence >= .12f;
    }

    /** 素材是否值得接进播放链：时间戳证据可用 + 投影比例落在可信区间 + 确实写了盘。 */
    boolean hasVocalMaterial() {
        return isUsable() && materialFrames > 0L
                && vocalProjection >= MIN_PROJECTION && vocalProjection <= MAX_PROJECTION;
    }
}
