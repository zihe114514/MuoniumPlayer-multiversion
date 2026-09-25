/*
 * 本程序为自由软件；你可以在自由软件基金会发布的
 * 12/12/99 Initial implementation.        mdm@techie.com.
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


/**
 * 支持的输出通道的类型安全表示
 * 常量。
 * <p>
 * 此类是不可变的，因此是线程安全的。
 *
 * @author Mat McGowan 12/12/99
 * @since 0.0.7
 */
public class OutputChannels {

    /**
     * 指示输出的标志应包括两个通道。
     */
    public static final int BOTH_CHANNELS = 0;

    /**
     * 指示输出的标志应仅包括左通道。
     */
    public static final int LEFT_CHANNEL = 1;

    /**
     * 指示输出应只包含右声道的标志。
     */
    public static final int RIGHT_CHANNEL = 2;

    /**
     * 用于指示输出为单声道的标志。
     */
    public static final int DOWNMIX_CHANNELS = 3;

    public static final OutputChannels LEFT = new OutputChannels(LEFT_CHANNEL);
    public static final OutputChannels RIGHT = new OutputChannels(RIGHT_CHANNEL);
    public static final OutputChannels BOTH = new OutputChannels(BOTH_CHANNELS);
    public static final OutputChannels DOWNMIX = new OutputChannels(DOWNMIX_CHANNELS);

    private int outputChannels;

    /**
     * 创建一个 <code>OutputChannels</code> 实例，
     * 对应于给定的声道代码。
     *
     * @param code OutputChannels 声道代码常量之一。
     * @throws IllegalArgumentException （如果代码无效）
     *                                  信道编码
     */
    static public OutputChannels fromInt(int code) {
        switch (code) {
            case LEFT_CHANNEL:
                return LEFT;
            case RIGHT_CHANNEL:
                return RIGHT;
            case BOTH_CHANNELS:
                return BOTH;
            case DOWNMIX_CHANNELS:
                return DOWNMIX;
            default:
                throw new IllegalArgumentException("Invalid channel code: " + code);
        }
    }

    private OutputChannels(int channels) {
        outputChannels = channels;

        if (channels < 0 || channels > 3)
            throw new IllegalArgumentException("channels");
    }

    /**
     * 获取表示所需输出声道的代码。
     * 为 LEFT_CHANNEL、RIGHT_CHANNEL、BOTH_CHANNELS 之一
     * 或DOWNMIX_CHANNELS。
     *
     * @return 此实例表示的频道代码。
     */
    public int getChannelsOutputCode() {
        return outputChannels;
    }

    /**
     * 获取所表示的输出声道数，
     * 按此通道输出类型。
     *
     * @return 此通道输出的输出通道数
     * 仅 BOTH_CHANNELS 时为 2，其余类型为 1
     * 。
     */
    public int getChannelCount() {
        return (outputChannels == BOTH_CHANNELS) ? 2 : 1;
    }

    public boolean equals(Object o) {
        boolean equals = false;

        if (o instanceof OutputChannels) {
            OutputChannels oc = (OutputChannels) o;
            equals = (oc.outputChannels == outputChannels);
        }

        return equals;
    }

    public int hashCode() {
        return outputChannels;
    }
}
