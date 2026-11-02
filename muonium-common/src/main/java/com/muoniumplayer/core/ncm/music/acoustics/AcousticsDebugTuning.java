package com.muoniumplayer.core.ncm.music.acoustics;

/**
 * 声学调参 debug 钩子:运行时改写混响链路的关键增益,供实机调参用。
 *
 * <p><b>设计</b>:所有字段都是 public volatile float,默认值 NaN =「不干预,
 * 走编译期常量」。调参工具(目前是 1.21.11 的 debug 文件热载)把实测倍率写进
 * 来,音频线程/采样线程下一轮读取 —— 无锁、无配置解析、无常驻文件 IO 在
 * 音频线程上。参数定稿后把倍率烘回各常量,本类清空即可,零残留。</p>
 *
 * <h2>可调项(与链路一一对应)</h2>
 * <ul>
 *   <li>{@link #wetBoost} — RoomPlan.wet 全局倍率(SceneResponse 出口乘)</li>
 *   <li>{@link #decayScale} — 三频带 T60 全局倍率(同上)</li>
 *   <li>{@link #tapSumCeil} — 单耳抽头幅度和上限(替代 TAP_SUM_CEIL)</li>
 *   <li>{@link #fdnInputGain} — FDN 注入量(替代 INPUT_GAIN)</li>
 *   <li>{@link #tailMakeup} — FDN 输出补偿(替代 TAIL_MAKEUP)</li>
 *   <li>{@link #erBoost} — ER 抽头通路额外倍率(SceneAcoustics 内乘)</li>
 * </ul>
 *
 * <p>线程契约:游戏/调参线程写,音频线程读 —— volatile float 的原子可见性
 * 足够(每个字段独立,无复合不变量);NaN 检查每缓冲一次,成本可忽略。</p>
 */
public final class AcousticsDebugTuning {

    /** 全局湿声倍率(NaN = 不干预)。 */
    public static volatile float wetBoost = Float.NaN;
    /** 全局衰减时长倍率(NaN = 不干预);夹取仍走 clampDecay 上限。 */
    public static volatile float decayScale = Float.NaN;
    /** 低频带衰减额外倍率(NaN = 不干预):DJ/劲爆歌曲的底鼓能量大头在
     *  低频,长低频尾会把鼓点糊成一片 —— 单独压低频(<150Hz)衰减,中高频
     *  尾巴保持,是流行乐混响的惯例(decay low ratio 0.3-0.7)。 */
    public static volatile float lowDecayScale = Float.NaN;
    /** 单耳抽头幅度和上限(NaN = 用编译期 TAP_SUM_CEIL)。 */
    public static volatile float tapSumCeil = Float.NaN;
    /** FDN 注入量(NaN = 用编译期 INPUT_GAIN)。 */
    public static volatile float fdnInputGain = Float.NaN;
    /** FDN 输出补偿(NaN = 用编译期 TAIL_MAKEUP)。 */
    public static volatile float tailMakeup = Float.NaN;
    /** ER 抽头通路额外倍率(NaN = 不干预)。 */
    public static volatile float erBoost = Float.NaN;

    /** 读 wetBoost,NaN 时给 fallback。 */
    public static float wetBoost(float fallback) {
        final float v = wetBoost;
        return Float.isNaN(v) ? fallback : v;
    }

    public static float decayScale(float fallback) {
        final float v = decayScale;
        return Float.isNaN(v) ? fallback : v;
    }

    public static float lowDecayScale(float fallback) {
        final float v = lowDecayScale;
        return Float.isNaN(v) ? fallback : v;
    }

    public static float tapSumCeil(float fallback) {
        final float v = tapSumCeil;
        return Float.isNaN(v) ? fallback : v;
    }

    public static float fdnInputGain(float fallback) {
        final float v = fdnInputGain;
        return Float.isNaN(v) ? fallback : v;
    }

    public static float tailMakeup(float fallback) {
        final float v = tailMakeup;
        return Float.isNaN(v) ? fallback : v;
    }

    public static float erBoost(float fallback) {
        final float v = erBoost;
        return Float.isNaN(v) ? fallback : v;
    }

    /** 全部回 NaN(不干预)。 */
    public static void clear() {
        wetBoost = Float.NaN;
        decayScale = Float.NaN;
        lowDecayScale = Float.NaN;
        tapSumCeil = Float.NaN;
        fdnInputGain = Float.NaN;
        tailMakeup = Float.NaN;
        erBoost = Float.NaN;
    }

    private AcousticsDebugTuning() {
    }
}
