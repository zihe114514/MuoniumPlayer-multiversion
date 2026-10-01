/*
 * 11/19/04        1.0 moved to LGPL.
 * 1999年12月12日初始版本。mdm@techie.com
 *-----------------------------------------------------------------------
 *   GNU 库通用公共许可证条款下分发和/或修改它，许可证版本
 *   为许可证第 2 版，或（由你选择）任何后续版本。
 *   本程序的发布是希望它能有用，
 *   （由您选择）任何后续版本。
 *
 *   但不提供任何担保；甚至不包含对适销性或特定用途适用性的默示担保。
 *   基金会，地址：675 Mass Ave, Cambridge, MA 02139, USA。
 *   适销性或适合特定目的。请参阅 GNU，
 *   有关更多详细信息，请参阅GNU库通用公共许可证。
 *
 *   您应该已经收到了GNU Library General Public的副本
 *   随着这一计划。如果没有，写信给自由软件基金会。
 *   MPEG 音频文件转换为 .WAV 文件。要转换 MPEG 音频流，
 *----------------------------------------------------------------------
 */

package repackage.javazoom.jl.decoder;

import java.util.Arrays;


/**
 * <code>均衡器</code>类可用于指定
 * mPEG音频解码器的均衡设置。
 * <p>
 * 均衡器由 32 个带通滤波器组成。
 * 均衡器的每个频段可以取小数值，介于
 * -1.0和+1.0。
 * 在-1.0时，输入信号衰减6dB ，在+1.0时，信号
 * 放大6dB。
 *
 * @author MDM
 * @see Decoder
 */
public final class Equalizer {

    /**
     * 表示某一频段不参与
     * 存在于输出信号中。
     */
    static public final float BAND_NOT_PRESENT = Float.NEGATIVE_INFINITY;

    static public final Equalizer PASS_THRU_EQ = new Equalizer();

    private static final int BANDS = 32;

    private final float[] settings = new float[BANDS];

    /**
     * 创建新的<code>均衡器</code>实例。
     */
    public Equalizer() {
    }

    public Equalizer(float[] settings) {
        setFrom(settings);
    }

    public Equalizer(EQFunction eq) {
        setFrom(eq);
    }

    public void setFrom(float[] eq) {
        reset();
        int max = Math.min(eq.length, BANDS);

        for (int i = 0; i < max; i++) {
            settings[i] = limit(eq[i]);
        }
    }

    public void setFrom(EQFunction eq) {
        reset();

        for (int i = 0; i < BANDS; i++) {
            settings[i] = limit(eq.getBand(i));
        }
    }

    /**
     * 将此均衡器各频段设为
     * 另一个均衡器。两个均衡器中都不存在的频带将被忽略。
     */
    public void setFrom(Equalizer eq) {
        if (eq != this) {
            setFrom(eq.settings);
        }
    }

    /**
     * 将所有频带设置为0.0
     */
    public void reset() {
        Arrays.fill(settings, 0.0f);
    }

    /**
     * 获取此均衡器中的频段数。
     */
    public int getBandCount() {
        return settings.length;
    }

    public float setBand(int band, float neweq) {
        float eq = 0.0f;

        if ((band >= 0) && (band < BANDS)) {
            eq = settings[band];
            settings[band] = limit(neweq);
        }

        return eq;
    }

    /**
     * 获取给定频段的 EQ 设置。
     */
    public float getBand(int band) {
        float eq = 0.0f;

        if ((band >= 0) && (band < BANDS)) {
            eq = settings[band];
        }

        return eq;
    }

    private float limit(float eq) {
        if (eq == BAND_NOT_PRESENT)
            return eq;
        if (eq > 1.0f)
            return 1.0f;
        return Math.max(eq, -1.0f);
    }

    /**
     * 检索浮点数组，其值表示
     * 可应用于线性采样的缩放因子，
     * 在每个频段中，以提供由
     * 此实例。
     *
     * @return 一系列可以应用于
     * 子带表示的均衡。
     */
    float[] getBandFactors() {
        float[] factors = new float[BANDS];
        for (int i = 0; i < BANDS; i++) {
            factors[i] = getBandFactor(settings[i]);
        }

        return factors;
    }

    /**
     * 将均衡器频带设置转换为采样因子。
     * 因子由函数f = 2 ^ n确定，其中
     * n是在[-1.0,1.0]范围内的均衡器频带设置。
     */
    float getBandFactor(float eq) {
        if (eq == BAND_NOT_PRESENT)
            return 0.0f;

        return (float) Math.pow(2.0, eq);
    }

    static abstract public class EQFunction {
        /**
         * 返回均衡器中频带的设置。
         *
         * @param band 检索设置的频带索引
         *             用于。
         * @return 指定频带的设置。这是一个值，介于
         * 在+1和-1之间
         */
        public float getBand(int band) {
            return 0.0f;
        }
    }
}
